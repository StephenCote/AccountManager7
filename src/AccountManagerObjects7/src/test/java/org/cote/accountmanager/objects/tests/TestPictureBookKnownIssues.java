package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.rules.BookWorldInitializationRule;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/**
 * Regression coverage for the OPEN PictureBook items carried in aiDocs/KnownIssues.md.
 *
 * <p>Everything here runs against the live Postgres/Ollama backend the other Objects7 tests use —
 * there is no in-memory stand-in. Tests that need an LLM state that plainly.
 */
public class TestPictureBookKnownIssues extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook KI Tests";

	/**
	 * The seven olio/identity sub-models {@code createCharPerson} persists per character through
	 * {@code createPersistedForeignInstance}, each of which independently resolves
	 * {@code "~/" + schema.getGroup()}. These are the group names KI-42's duplicate-key abort
	 * fires on ({@code Narratives} was simply the first one a live run happened to hit).
	 */
	private static final String[] FOREIGN_SUB_MODELS = new String[] {
		OlioModelNames.MODEL_NARRATIVE,
		ModelNames.MODEL_PROFILE,
		OlioModelNames.MODEL_CHAR_STATISTICS,
		OlioModelNames.MODEL_STORE,
		OlioModelNames.MODEL_INSTINCT,
		ModelNames.MODEL_PERSONALITY,
		OlioModelNames.MODEL_CHAR_STATE
	};

	private OrganizationContext testOrgCtx;
	private BaseRecord testUser;

	/** A user nobody has used before, so none of the sub-model groups exist under its home yet. */
	private BaseRecord newVirginUser(String prefix) {
		testOrgCtx = getTestOrganization(ORG_PATH);
		Factory mf = IOSystem.getActiveContext().getFactory();
		/// $minLen5 requires five consecutive alphanumerics; a bare timestamp suffix satisfies it.
		String name = prefix + System.currentTimeMillis();
		BaseRecord u = mf.getCreateUser(testOrgCtx.getAdminUser(), name, testOrgCtx.getOrganizationId());
		assertNotNull("Factory.getCreateUser returned null for '" + name + "' — check $minLen5", u);
		return u;
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/**
	 * Counts auth.group rows literally present in the DB for a given name under the user's home —
	 * NOT through makePath (which would create one). The unique constraint is
	 * (name, parentId, organizationId), so this is the exact key the KI-42 insert collides on.
	 */
	private int countHomeGroups(BaseRecord user, String groupName) {
		BaseRecord home = user.get(FieldNames.FIELD_HOME_DIRECTORY);
		IOSystem.getActiveContext().getRecordUtil().populate(home);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, home.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, groupName);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, orgId(user));
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/** Captures every ERROR/WARN the production code logs while the body runs. */
	private static final class LogCapture implements AutoCloseable {
		private final List<String> messages = new CopyOnWriteArrayList<>();
		private final LoggerContext ctx;
		private final LoggerConfig root;
		private final AbstractAppender appender;

		LogCapture() {
			ctx = (LoggerContext) LogManager.getContext(false);
			Configuration cfg = ctx.getConfiguration();
			root = cfg.getRootLogger();
			appender = new AbstractAppender("ki42Capture", null, null, true, null) {
				@Override
				public void append(LogEvent event) {
					if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
						messages.add(event.getLoggerName() + " | " + event.getMessage().getFormattedMessage());
					}
				}
			};
			appender.start();
			root.addAppender(appender, Level.WARN, null);
		}

		List<String> matching(String needle) {
			List<String> out = new ArrayList<>();
			for (String m : messages) {
				if (m.toLowerCase().contains(needle.toLowerCase())) out.add(m);
			}
			return out;
		}

		@Override
		public void close() {
			root.removeAppender("ki42Capture");
			appender.stop();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-42
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * KI-42 — the datum the KnownIssues entry says must be established before any code is written:
	 * does a single request duplicate ONLY the {@code Narratives} group, or every foreign-model
	 * group? Drives {@code createPersistedForeignInstance}'s exact code path (which is private, so
	 * this reproduces its two lines verbatim: a path-parameterized {@code Factory.newInstance}
	 * followed by {@code AccessPoint.create}) for all seven sub-models, three times over, as a user
	 * whose home directory contains none of those groups yet — the state a first-ever
	 * /create-from-scenes runs in.
	 *
	 * <p>Asserts on the DB, not on logs alone: exactly ONE group row may exist per group name, and
	 * every instance must have persisted. A duplicate-key abort shows up as a null instance and a
	 * DBWriter error, both of which are checked.
	 */
	@Test
	public void TestKi42ForeignSubModelGroupsResolveOncePerRequest() {
		logger.info("KI-42: repeated createPersistedForeignInstance-shaped creates must not collide on "
			+ "the (name, parentId, organizationId) group constraint");
		BaseRecord user = newVirginUser("ki42a");

		// Pre-state: a virgin home has none of these groups.
		for (String model : FOREIGN_SUB_MODELS) {
			String grp = RecordFactory.getSchema(model).getGroup();
			assertEquals("Precondition: virgin user's home must not already contain '" + grp + "'",
				0, countHomeGroups(user, grp));
		}

		List<String> created = new ArrayList<>();
		try (LogCapture capture = new LogCapture()) {
			for (int pass = 0; pass < 3; pass++) {
				for (String model : FOREIGN_SUB_MODELS) {
					BaseRecord inst = null;
					try {
						ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
							"~/" + RecordFactory.getSchema(model).getGroup());
						BaseRecord rec = IOSystem.getActiveContext().getFactory().newInstance(model, user, null, plist);
						inst = IOSystem.getActiveContext().getAccessPoint().create(user, rec);
					} catch (Exception e) {
						logger.error("pass " + pass + " " + model + " threw: " + e.getMessage(), e);
					}
					assertNotNull("pass " + pass + ": " + model + " failed to persist — this is exactly the "
						+ "'createCharPerson failed for ... character will be absent from the book' abort", inst);
					created.add(model);
				}
			}

			List<String> dupes = capture.matching("duplicate key");
			assertTrue("No group insert may violate the (name, parentId, organizationId) unique "
				+ "constraint. Offending log lines: " + dupes, dupes.isEmpty());
		}

		assertEquals(FOREIGN_SUB_MODELS.length * 3, created.size());

		// Post-state: exactly one group per sub-model, no matter how many instances were created.
		for (String model : FOREIGN_SUB_MODELS) {
			String grp = RecordFactory.getSchema(model).getGroup();
			assertEquals("Exactly one '" + grp + "' group must exist under the user's home after 3 "
				+ "rounds of creates — more than one means the get-or-create raced its own write",
				1, countHomeGroups(user, grp));
		}
	}

	/**
	 * KI-42, the actual mechanism.
	 *
	 * <p>The sequential get-or-create above passes, so "13 call sites re-resolve the same path" is
	 * not by itself sufficient — something has to make the lookup MISS a row that exists. It does not
	 * matter which cause does it (a type-filtered lookup is the deterministic one reproduced here);
	 * what matters is what {@code makePath} does when its own INSERT then loses the race with the
	 * row that was already there.
	 *
	 * <p>{@code makePath}'s create branch ignores {@code writer.write(node)}'s return value.
	 * {@code DBWriter.write} catches the {@code SQLException}, logs it and returns 0 — but it has
	 * already stamped a sequence-allocated id onto the in-memory record. So makePath reads that id
	 * back, returns a perfectly ordinary-looking auth.group whose id matches NO row in the database.
	 * Everything downstream then persists against a phantom groupId, which is precisely the
	 * "PolicyUtil - Group could not be found / Resolve resource by groupId: 3490" line in the
	 * reported log, and why the id differs on every run while the collision key does not: each run
	 * burns a fresh sequence value on an insert that never lands.
	 *
	 * <p>Reproduced deterministically here by pre-creating the group with a non-DATA type: the unique
	 * constraint is (name, parentId, organizationId) and does NOT include type, so a DATA-filtered
	 * lookup misses while the insert still collides.
	 */
	@Test
	public void TestKi42MakePathNeverReturnsAGroupThatIsNotInTheDatabase() throws Exception {
		logger.info("KI-42 mechanism: a losing get-or-create insert must not yield a phantom group");
		BaseRecord user = newVirginUser("ki42c");
		BaseRecord home = user.get(FieldNames.FIELD_HOME_DIRECTORY);
		IOSystem.getActiveContext().getRecordUtil().populate(home);

		// Pre-create 'Narratives' under the home with a type the DATA-filtered lookup won't match.
		BaseRecord pre = RecordFactory.model(ModelNames.MODEL_GROUP).newInstance();
		pre.set(FieldNames.FIELD_NAME, "Narratives");
		pre.set(FieldNames.FIELD_PARENT_ID, home.get(FieldNames.FIELD_ID));
		pre.set(FieldNames.FIELD_ORGANIZATION_ID, orgId(user));
		pre.set(FieldNames.FIELD_TYPE, GroupEnumType.BUCKET.toString());
		pre.set(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		assertTrue("Precondition: the colliding row must be created",
			IOSystem.getActiveContext().getRecordUtil().createRecord(pre));
		long preId = ((Number) pre.get(FieldNames.FIELD_ID)).longValue();
		assertTrue(preId > 0L);

		BaseRecord result = makeGroup(user, "~/Narratives");

		// Whatever makePath decides to do, it may NOT hand back a group id that has no row.
		if (result != null) {
			long gid = ((Number) result.get(FieldNames.FIELD_ID)).longValue();
			Query verify = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, gid);
			verify.setCache(false);
			assertNotNull("makePath returned auth.group id=" + gid + " but no such row exists — a "
				+ "phantom group. Everything persisted against it fails PBAC with 'Group could not "
				+ "be found', which is how KI-42 loses a character.",
				IOSystem.getActiveContext().getSearch().findRecord(verify));
		}

		// And it must not have created a second row under the same constraint key.
		assertEquals("Exactly one 'Narratives' row may exist under this home", 1, countHomeGroups(user, "Narratives"));
	}

	/**
	 * KI-42, end to end: a real {@code createFromScenes} run for a brand-new user must create every
	 * character. This is the reported symptom — "createCharPerson failed for 'Jideon de Rosa' —
	 * character will be absent from the book" — rather than the mechanism.
	 *
	 * <p>Uses pre-built character stubs carrying {@code appearance}, which skips the per-character
	 * reduce-character LLM call, so the only LLM traffic is whatever createCharPerson itself needs.
	 */
	@Test
	public void TestKi42CreateFromScenesCreatesEveryCharacterForANewUser() throws Exception {
		logger.info("KI-42 end-to-end: createFromScenes must not lose characters to a group "
			+ "duplicate-key abort on a user whose sub-model groups don't exist yet");
		BaseRecord user = newVirginUser("ki42b");
		String chatConfigName = ensureChatConfig(user);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "KI42 Source " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		work.set("text", "Jideon de Rosa walked the harbour road at dusk. Duna de Rosa followed, "
			+ "carrying a lantern. Francois Touvier waited at the pier with the boat.");
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull(createdWork);

		List<Map<String, Object>> sceneList = new ArrayList<>();
		Map<String, Object> scene0 = new LinkedHashMap<>();
		scene0.put("title", "The Harbour Road");
		scene0.put("blurb", "Three travellers meet at the pier.");
		scene0.put("setting", "a stone harbour at dusk");
		scene0.put("action", "walking toward the pier");
		scene0.put("mood", "quiet");
		sceneList.add(scene0);

		// Three characters — the multi-character case is the one that hit the collision, because
		// each character re-resolves all seven sub-model group paths.
		List<Map<String, Object>> charDataList = new ArrayList<>();
		charDataList.add(charStub("Jideon de Rosa", "MALE", "a lean man in a salt-stained coat"));
		charDataList.add(charStub("Duna de Rosa", "FEMALE", "a young woman carrying a brass lantern"));
		charDataList.add(charStub("Francois Touvier", "MALE", "a heavyset boatman with a grey beard"));

		BaseRecord meta;
		List<String> dupes;
		try (LogCapture capture = new LogCapture()) {
			meta = PictureBookUtil.createFromScenes(user, createdWork.get(FieldNames.FIELD_OBJECT_ID),
				chatConfigName, null, "KI42 Book " + System.currentTimeMillis(), sceneList, charDataList,
				testProperties.getProperty("test.datagen.path"));
			dupes = capture.matching("duplicate key");
		}
		assertNotNull("createFromScenes must return meta", meta);

		List<Object> failed = meta.get("failedCharacters");
		assertTrue("No character may be lost. failedCharacters=" + failed, failed == null || failed.isEmpty());
		assertTrue("No group insert may violate the unique constraint. Offending lines: " + dupes, dupes.isEmpty());

		List<Map<String, Object>> listed = PictureBookUtil.listCharacters(user, meta.get("bookObjectId"));
		assertEquals("All three characters must exist in the book", 3, listed.size());

		// UPDATED 2026-08-18, and the update is the point rather than an accommodation.
		//
		// This loop used to assert exactly ONE group per sub-model under the user's home — a proxy for
		// "the repeated get-or-create did not produce duplicates", which only held while the sub-records
		// were being created in the home at all. They are not any more: phase 3 routed them into the book
		// world's groups, and (fixed 2026-08-17) createFromScenes now threads its OlioContext into
		// PbSubRecordUtil.prepareGroups, so nothing pre-creates the legacy home groups for the reroute's
		// sake either.
		//
		// Measured, and the two halves are different — do not simplify this into one assertion:
		//
		//  * 'Narratives' is now ZERO. Nothing creates it, which is precisely what removes this pipeline
		//    from the set of ~/Narratives writers — KI-60's collision target.
		//  * The other six home groups still EXIST, and that is NOT the reroute regressing. Verified in
		//    code: CharPersonFactory.implement() builds an in-memory placeholder for each of them via
		//    Factory.newInstance(..., ParameterList{path: "~/{schemaGroup}"}), and Factory.java:80
		//    makePath()s that group before it builds anything. So the GROUP is created as a side effect
		//    of instantiating the character, whether or not a record ever lands in it. Narratives is the
		//    exception only because the factory does not pre-build a narrative.
		//
		// The assertion that actually proves the reroute is therefore not the group count but the group
		// CONTENTS: the home group exists and holds ZERO records of its model, because the records are in
		// the world group. A regression puts records back in the home and this fails.
		String narrativeGrp = RecordFactory.getSchema(OlioModelNames.MODEL_NARRATIVE).getGroup();
		assertEquals("Nothing may create '" + narrativeGrp + "' under the acting user's home any more —"
			+ " that group is KI-60's collision target and the narrative now goes to {world}/Narratives"
			+ " through NarrativeUtil.getCreateNarrative", 0, countHomeGroups(user, narrativeGrp));

		for (String model : FOREIGN_SUB_MODELS) {
			if (OlioModelNames.MODEL_NARRATIVE.equals(model)) {
				continue;
			}
			String grp = RecordFactory.getSchema(model).getGroup();
			assertEquals("Exactly one '" + grp + "' group under the user's home — created empty by"
				+ " CharPersonFactory/Factory.java:80, never twice (KI-42's duplicate-key race)",
				1, countHomeGroups(user, grp));
			assertEquals("The home '" + grp + "' group must be EMPTY of " + model + ": the records belong in"
				+ " the book world's group now. A non-zero count here is the phase-3 sub-record reroute"
				+ " regressing — which is exactly how it was found to be bypassed in the first place.",
				0, countRecordsInHomeGroup(user, model, grp));
		}
	}

	/** How many records of {@code model} actually live in the user's home group named {@code groupName}. */
	private int countRecordsInHomeGroup(BaseRecord user, String model, String groupName) {
		BaseRecord home = user.get(FieldNames.FIELD_HOME_DIRECTORY);
		IOSystem.getActiveContext().getRecordUtil().populate(home);
		Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, home.get(FieldNames.FIELD_ID));
		gq.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, groupName);
		gq.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, orgId(user));
		gq.setCache(false);
		BaseRecord grp = IOSystem.getActiveContext().getSearch().findRecord(gq);
		if (grp == null) {
			return 0;
		}
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, orgId(user));
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/**
	 * KI-42's original property, preserved on the path that still uses the home directory: with <b>no</b>
	 * {@code dataPath} there is no {@code OlioContext}, so {@code PbSubRecordUtil} falls back to
	 * {@code ~/{schemaGroup}} — and a 3-character book must still produce exactly <b>one</b> group per
	 * sub-model there, never two, and must not lose a character to a duplicate-key abort.
	 *
	 * <p>Split out when the sibling case above moved to asserting zero home groups. Without this leg the
	 * suite would no longer cover the get-or-create race KI-42 is actually about, since the context-bearing
	 * path no longer touches the home at all.
	 *
	 * <p>All seven home groups are asserted to hold exactly one group AND at least one record. With no
	 * context nothing is detached, so {@code CharPersonFactory}'s placeholders are auto-created in the home
	 * by {@code DBWriter.applyAutoCreateList} — which is what the pre-phase-3 behaviour was, and is what
	 * this leg exists to keep asserting.
	 */
	@Test
	public void TestKi42ContextlessCreateFromScenesUsesTheHomeAndDoesNotDuplicate() throws Exception {
		logger.info("KI-42 contextless: with no dataPath the sub-record destination is ~/{schemaGroup}, "
			+ "and a 3-character book must create exactly one such group per model");
		BaseRecord user = newVirginUser("ki42c");
		String chatConfigName = ensureChatConfig(user);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "KI42C Source " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		work.set("text", "Jideon de Rosa walked the harbour road at dusk. Duna de Rosa followed, "
			+ "carrying a lantern. Francois Touvier waited at the pier with the boat.");
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull(createdWork);

		List<Map<String, Object>> sceneList = new ArrayList<>();
		Map<String, Object> scene0 = new LinkedHashMap<>();
		scene0.put("title", "The Harbour Road");
		scene0.put("blurb", "Three travellers meet at the pier.");
		scene0.put("setting", "a stone harbour at dusk");
		scene0.put("action", "walking toward the pier");
		scene0.put("mood", "quiet");
		sceneList.add(scene0);

		List<Map<String, Object>> charDataList = new ArrayList<>();
		charDataList.add(charStub("Jideon de Rosa", "MALE", "a lean man in a salt-stained coat"));
		charDataList.add(charStub("Duna de Rosa", "FEMALE", "a young woman carrying a brass lantern"));
		charDataList.add(charStub("Francois Touvier", "MALE", "a heavyset boatman with a grey beard"));

		BaseRecord meta;
		List<String> dupes;
		try (LogCapture capture = new LogCapture()) {
			/// null dataPath is the whole point: no context ⇒ the legacy home destination.
			meta = PictureBookUtil.createFromScenes(user, createdWork.get(FieldNames.FIELD_OBJECT_ID),
				chatConfigName, null, "KI42C Book " + System.currentTimeMillis(), sceneList, charDataList,
				null);
			dupes = capture.matching("duplicate key");
		}
		assertNotNull("createFromScenes must return meta", meta);
		List<Object> failed = meta.get("failedCharacters");
		assertTrue("No character may be lost on the contextless path either. failedCharacters=" + failed,
			failed == null || failed.isEmpty());
		assertTrue("No group insert may violate the unique constraint. Offending lines: " + dupes, dupes.isEmpty());
		assertEquals("All three characters must exist in the book", 3,
			PictureBookUtil.listCharacters(user, meta.get("bookObjectId")).size());

		/// Exactly one group per sub-model, and it is actually USED — KI-42's original property, on the one
		/// path that still writes into the acting user's home.
		for (String model : FOREIGN_SUB_MODELS) {
			String grp = RecordFactory.getSchema(model).getGroup();
			assertEquals("Exactly one '" + grp + "' group under the user's home after a 3-character"
				+ " contextless book — two would be KI-42's duplicate-key race", 1,
				countHomeGroups(user, grp));
			assertTrue("With no OlioContext the home IS the destination, so '" + grp + "' must actually hold"
				+ " " + model + " records — a 0 here means this leg is no longer covering the contextless"
				+ " path it was written for", countRecordsInHomeGroup(user, model, grp) > 0);
		}
	}

	private Map<String, Object> charStub(String name, String gender, String appearance) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("gender", gender);
		m.put("appearance", appearance);
		return m;
	}

	private String ensureChatConfig(BaseRecord user) throws Exception {
		String model = testProperties.getProperty("test.llm.ollama.model");
		String serverUrl = testProperties.getProperty("test.llm.ollama.server");
		assertNotNull("test.llm.ollama.server must be set", serverUrl);
		String cfgName = "PictureBook KI " + model + ".chat";
		BaseRecord existing = org.cote.accountmanager.util.DocumentUtil.getRecord(
			user, OlioModelNames.MODEL_CHAT_CONFIG, cfgName, "~/Chat");
		if (existing != null) return cfgName;

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, cfgName);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
		cfg.set("connection", OlioTestUtil.getCreateConnection(user, cfgName + " Connection", serverUrl, null, 300));
		cfg.set("model", model);
		cfg.set("stream", false);
		BaseRecord opts = cfg.get("chatOptions");
		if (opts == null) {
			opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			cfg.set("chatOptions", opts);
		}
		opts.set("think", false);
		opts.set("temperature", 0.3);
		assertNotNull(IOSystem.getActiveContext().getAccessPoint().create(user, cfg));
		return cfgName;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-36
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * KI-36 — a model name that resolves to no schema must produce a clean null (and an INVALID
	 * audit), never a NullPointerException. Before the fix, "undefined" reached
	 * {@code RecordUtil.getCommonFields}, whose {@code ms.getQuery()} dereferenced the null
	 * ModelSchema; the NPE propagated out of {@code AuthorizationService.enableMember} as a 500.
	 */
	@Test
	public void TestKi36UnresolvableModelTypeReturnsNullInsteadOfNpe() {
		BaseRecord user = newVirginUser("ki36a");
		assertTrue("A real model must still resolve",
			org.cote.accountmanager.client.AccessPoint.isResolvableModel(ModelNames.MODEL_GROUP));
		for (String bogus : new String[] { "undefined", "null", "not.a.model" }) {
			assertTrue("'" + bogus + "' must not resolve to a schema",
				!org.cote.accountmanager.client.AccessPoint.isResolvableModel(bogus));
			// The three by-identity lookups the member endpoints use. Each must return null rather
			// than throwing — that is the whole 500-vs-400 difference at the REST boundary.
			assertNull(IOSystem.getActiveContext().getAccessPoint()
				.findByObjectId(user, bogus, "00000000-0000-0000-0000-000000000000"));
			assertNull(IOSystem.getActiveContext().getAccessPoint().findById(user, bogus, 1L));
			assertNull(IOSystem.getActiveContext().getAccessPoint().findByUrn(user, bogus, "urn:whatever"));
		}
		assertTrue("null model must also be handled",
			!org.cote.accountmanager.client.AccessPoint.isResolvableModel(null));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-37
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * KI-37 — {@code PictureBookUtil.callLlmInternal} must compose DB prompt templates through the
	 * canonical {@code PromptTemplateComposer}, not its own inline section loop.
	 *
	 * <p>That loop matched only the literal roles "system"/"user", but a section's {@code role} is
	 * optional per {@code promptSectionModel.json} — so a role-less section was silently dropped and
	 * its content never reached the LLM. The composer instead includes a section when its role is
	 * empty OR equals the target ({@code PromptTemplateComposer.java:70-74}).
	 *
	 * <p>Observed deterministically and WITHOUT an LLM call, via callLlmInternal's own
	 * unsubstituted-placeholder guard: the role-less section carries a {@code {ki37RoleLessMarker}}
	 * placeholder that no caller var can fill. If the section reaches the composed USER half, the
	 * guard refuses the call and logs the offending placeholder by name. If it were still being
	 * dropped, the user half would be the clean "Extract {count} scenes from: {text}" line, both vars
	 * would substitute, and a real LLM call would proceed instead.
	 */
	@Test
	public void TestKi37RoleLessTemplateSectionReachesBothHalves() throws Exception {
		logger.info("KI-37: role-less prompt-template sections must no longer be dropped");
		BaseRecord user = newVirginUser("ki37a");
		String chatConfigName = ensureChatConfig(user);

		String templateName = "ki37 RoleLess " + System.currentTimeMillis();
		BaseRecord tmpl = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_PROMPT_TEMPLATE, user, null,
			ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat"));
		tmpl.set(FieldNames.FIELD_NAME, templateName);
		List<BaseRecord> sections = tmpl.get("sections");
		sections.add(section("sys", "system", 10, "You extract scenes."));
		/// The section under test: NO role at all.
		sections.add(section("shared", null, 20, "SHARED-KI37-MARKER {ki37RoleLessMarker}"));
		sections.add(section("usr", "user", 30, "Extract {count} scenes from: {text}"));
		assertNotNull("Template must persist", IOSystem.getActiveContext().getAccessPoint().create(user, tmpl));

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "KI37 Work " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		work.set("text", "A short story about a lighthouse keeper who repaints the lamp room each spring.");
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull(createdWork);

		List<String> refusals;
		try (LogCapture capture = new LogCapture()) {
			PictureBookUtil.extractScenesOnly(user, createdWork.get(FieldNames.FIELD_OBJECT_ID), 2,
				chatConfigName, templateName);
			refusals = capture.matching("ki37RoleLessMarker");
		}
		assertTrue("The role-less section's content must reach the composed USER half — callLlmInternal's "
			+ "unsubstituted-placeholder guard should have named '{ki37RoleLessMarker}'. Nothing did, which "
			+ "means the section was dropped exactly as the pre-KI-37 inline loop dropped it.",
			!refusals.isEmpty());
	}

	private BaseRecord section(String name, String role, int priority, String line) throws Exception {
		BaseRecord sec = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_SECTION);
		sec.set("sectionName", name);
		if (role != null) sec.set("role", role);
		sec.set("priority", priority);
		List<String> lines = sec.get("lines");
		lines.add(line);
		return sec;
	}

	/**
	 * KI-37 companion: every PictureBook prompt that has NO DB template record must still resolve
	 * from the classpath. The fix narrowed the classpath fallback so it only fires when no template
	 * record resolved at all (backfilling one half of a DB template from an unrelated classpath
	 * resource produced a mismatched, incoherent prompt) — this guards the remaining, legitimate case.
	 */
	@Test
	public void TestKi37ClasspathFallbackStillResolvesRealPictureBookPrompts() {
		String[] prompts = {
			"pictureBook.extract-scenes", "pictureBook.extract-chunk", "pictureBook.extract-character",
			"pictureBook.scene-blurb", "pictureBook.landscape-prompt", "pictureBook.scene-image-prompt"
		};
		for (String name : prompts) {
			assertNotNull(name + " system half must load from the classpath",
				org.cote.accountmanager.olio.llm.PromptResourceUtil.getString(name, "system"));
			assertNotNull(name + " user half must load from the classpath",
				org.cote.accountmanager.olio.llm.PromptResourceUtil.getString(name, "user"));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-34
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * KI-34/KI-61 — the character-scoped portrait path is REVERTED, and this pins why so it is not
	 * reintroduced the same way.
	 *
	 * <p>Returning {@code groupPath + "/" + name + "/Gallery"} fixed the name collision and broke
	 * character reimage outright: both callers ({@code generateSDImages}/{@code generateSDFigurines})
	 * run as the OLIO USER, while a PictureBook character's groupPath is inside the ACTING user's
	 * home — so the write target moved somewhere the Olio user cannot create, and PathUtil refused it
	 * with "Not authorized to create auth.group ... /home/steve/Data/PictureBooks/.../Gallery".
	 *
	 * <p>So the assertion worth having is not "the path is character-scoped" — that is exactly what
	 * broke — but "the resolved path is one the CALLING PRINCIPAL can actually create in". Any future
	 * fix must satisfy both that and the collision requirement.
	 */
	@Test
	public void TestKi34PortraitPathIsResolvableByTheOlioUserThatWritesIt() throws Exception {
		logger.info("KI-34/KI-61: the portrait path must be creatable by the principal that writes it");
		OrganizationContext worldOrg = getTestOrganization("/Development/World Building");
		org.cote.accountmanager.olio.OlioContext ctx =
			org.cote.accountmanager.objects.tests.olio.OlioTestUtil.getContext(worldOrg,
				testProperties.getProperty("test.datagen.path"));
		assertNotNull("Olio context must initialize", ctx);

		BaseRecord olioUser = ctx.getOlioUser();
		BaseRecord charPerson = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		charPerson.set(FieldNames.FIELD_NAME, "KI34 Path Probe");

		String path = org.cote.accountmanager.olio.sd.SDUtil.resolveCharacterImagePath(ctx, charPerson);
		logger.info("KI-34 resolved portrait path: " + path);
		assertNotNull("A portrait path must resolve", path);

		// The whole point: the OLIO USER — the principal both callers pass — must be able to create it.
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().makePath(olioUser,
			ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(),
			((Number) olioUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
		assertNotNull("makePath returned null for '" + path + "' — the Olio user cannot create the "
			+ "portrait storage group, which is exactly the reimage break: 'Not authorized to create "
			+ "auth.group ... in path /home/<user>/Data/PictureBooks/.../Gallery'", grp);
	}

	/**
	 * KI-34's ORIGINAL defect is still present and is deliberately not fixed — recorded here so the
	 * collision is not forgotten now that the attempted fix has been reverted. Two charPerson records
	 * sharing a name resolve to the SAME world-gallery storage group.
	 */
	@Test
	public void TestKi34SameNamedCharactersStillCollide_KnownOpen() throws Exception {
		OrganizationContext worldOrg = getTestOrganization("/Development/World Building");
		org.cote.accountmanager.olio.OlioContext ctx =
			org.cote.accountmanager.objects.tests.olio.OlioTestUtil.getContext(worldOrg,
				testProperties.getProperty("test.datagen.path"));
		BaseRecord a = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		a.set(FieldNames.FIELD_NAME, "Jideon de Rosa");
		BaseRecord b = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		b.set(FieldNames.FIELD_NAME, "Jideon de Rosa");

		String pa = org.cote.accountmanager.olio.sd.SDUtil.resolveCharacterImagePath(ctx, a);
		String pb = org.cote.accountmanager.olio.sd.SDUtil.resolveCharacterImagePath(ctx, b);
		assertEquals("KI-34 remains OPEN: same-named characters still share one storage group. If this "
			+ "ever stops being true, the collision was fixed — update KI-34 and delete this test.",
			pa, pb);
	}

	private BaseRecord newNamedCharacter(BaseRecord user, String name, String path) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("Character '" + name + "' must persist in " + path, created);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID,
			created.get(FieldNames.FIELD_OBJECT_ID));
		q.planMost(false);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-35
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * KI-35 — the ACTING user must be able to toggle {@code inuse} on a wearable that
	 * {@code ApparelUtil} created as the OLIO user in the world's Wearables group.
	 *
	 * <p>Apparel/wearables/qualities are deliberately created by {@code ctx.getOlioUser()} in the
	 * world groups so colours resolve from the shared colour library (needed for complementary-colour
	 * computation). The character and its store, however, belong to the acting user. Dressing
	 * up/down is a patch of {@code olio.wearable.inuse} performed BY the acting user, and PBAC
	 * refused it on another owner's record: the patch failed silently, {@code inuse} stayed true
	 * forever, and {@code describeOutfit} kept reporting the full outfit after dressing down.
	 *
	 * <p>Runs against the shared "/Development/World Building" org so the Olio universe/world seed is
	 * reused rather than re-generated (a fresh org name costs minutes of seed loading).
	 */
	@Test
	public void TestKi35ActingUserCanToggleInuseOnOlioOwnedWearable() throws Exception {
		logger.info("KI-35: the acting user must be able to patch inuse on an Olio-owned wearable");
		OrganizationContext worldOrg = getTestOrganization("/Development/World Building");
		org.cote.accountmanager.olio.OlioContext ctx =
			org.cote.accountmanager.objects.tests.olio.OlioTestUtil.getContext(worldOrg,
				testProperties.getProperty("test.datagen.path"));
		assertNotNull("Olio context must initialize", ctx);
		BaseRecord actingUser = ctx.getConfig().getUser();
		assertNotNull("Acting (config) user must be present", actingUser);
		assertTrue("Precondition: the wearables must be owned by the OLIO user, not the acting user — "
			+ "otherwise this test proves nothing about cross-owner authorization",
			!actingUser.get(FieldNames.FIELD_ID).equals(ctx.getOlioUser().get(FieldNames.FIELD_ID)));

		BaseRecord apparel = org.cote.accountmanager.olio.ApparelUtil.randomApparel(ctx,
			((Number) ctx.getOlioUser().get(FieldNames.FIELD_ID)).longValue(), "female");
		assertNotNull("randomApparel must produce apparel", apparel);
		List<BaseRecord> wearables = apparel.get(
			org.cote.accountmanager.olio.schema.OlioFieldNames.FIELD_WEARABLES);
		assertNotNull("Apparel must carry wearables", wearables);
		assertTrue("Apparel must carry at least one wearable", !wearables.isEmpty());

		// Persist through the Olio user, exactly as constructApparel's own callers do.
		assertNotNull("Apparel must persist",
			IOSystem.getActiveContext().getAccessPoint().create(ctx.getOlioUser(), apparel));

		BaseRecord wearable = null;
		for (BaseRecord w : wearables) {
			Long wid = w.get(FieldNames.FIELD_ID);
			if (wid != null && wid > 0L) { wearable = w; break; }
		}
		assertNotNull("At least one wearable must have persisted with a real id", wearable);
		boolean before = Boolean.TRUE.equals(wearable.get("inuse"));

		// The dress-down patch, BY THE ACTING USER. `name` is included deliberately: olio.wearable
		// inherits common.name, whose name field is required/$notEmpty, and the writer validates the
		// PATCH RECORD ITSELF rather than the merged result — so an identity+inuse patch is rejected
		// with "Failed to modify record" before authorization is ever the deciding factor. See
		// .claude/rules/model-api.md. TestKi35PatchWithoutNameIsRejected below pins that separately.
		BaseRecord patch = RecordFactory.newInstance(
			org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_WEARABLE);
		patch.set(FieldNames.FIELD_ID, wearable.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, wearable.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, wearable.get(FieldNames.FIELD_NAME));
		patch.set("inuse", !before);
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(actingUser, patch);
		assertNotNull("The acting user must be permitted to patch inuse on the Olio-owned wearable — "
			+ "AccessPoint.update returned null, which is the silent failure KI-35 describes", updated);

		Query verify = QueryUtil.createQuery(
			org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_WEARABLE,
			FieldNames.FIELD_OBJECT_ID, wearable.get(FieldNames.FIELD_OBJECT_ID));
		verify.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "inuse" });
		verify.setCache(false);
		BaseRecord reread = IOSystem.getActiveContext().getSearch().findRecord(verify);
		assertNotNull(reread);
		assertEquals("inuse must actually have flipped in the database, not just reported success",
			!before, Boolean.TRUE.equals(reread.get("inuse")));

		// And pin the actual cause, so the Ux contract can't silently regress: the SAME patch minus
		// `name` must be refused. Authorization is identical in both cases — the difference is purely
		// that the writer validates the patch record itself.
		BaseRecord nameless = RecordFactory.newInstance(
			org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_WEARABLE);
		nameless.set(FieldNames.FIELD_ID, wearable.get(FieldNames.FIELD_ID));
		nameless.set(FieldNames.FIELD_OBJECT_ID, wearable.get(FieldNames.FIELD_OBJECT_ID));
		nameless.set("inuse", before);
		assertNull("A wearable patch WITHOUT the required `name` must be refused — this is the real "
			+ "cause of KI-35's 'always worn' symptom, not a cross-owner PBAC denial",
			IOSystem.getActiveContext().getAccessPoint().update(actingUser, nameless));
	}

	/**
	 * KI-35, the real cause: a wearable owned by the OLIO user is not writable by an acting user who
	 * did not happen to initialise the world.
	 *
	 * <p>{@code OlioContext.configureEnvironment} enrols the acting user in {@code ~/Roles/Olio User}
	 * — the role that carries the world-group grants — but that call sat BELOW the early return taken
	 * whenever the Olio Admin role already exists. So exactly one user per organization, ever, got
	 * enrolled: whoever first initialised Olio there. Every other user then had no grant on the world
	 * groups where {@code ApparelUtil} deliberately creates apparel/wearables/qualities as the Olio
	 * user, so their dress-up/down patch was refused, {@code inuse} stayed true forever, and
	 * {@code describeOutfit} kept listing the full outfit.
	 *
	 * <p>This test uses a SECOND, brand-new acting user against an already-initialised world, which is
	 * the condition the reporter hit. An earlier isolation run of mine used the FIRST user and so
	 * could not have exhibited this — it is why I wrongly concluded authorization was not involved.
	 */
	@Test
	public void TestKi35SecondActingUserCanToggleInuseOnOlioOwnedWearable() throws Exception {
		logger.info("KI-35: a SECOND acting user (not the world's initialiser) must be able to patch "
			+ "inuse on an Olio-owned wearable");
		OrganizationContext worldOrg = getTestOrganization("/Development/World Building");
		// Ensure the world exists and is owned/initialised by the usual first user.
		org.cote.accountmanager.olio.OlioContext primary =
			org.cote.accountmanager.objects.tests.olio.OlioTestUtil.getContext(worldOrg,
				testProperties.getProperty("test.datagen.path"));
		assertNotNull("Primary Olio context must initialize", primary);

		// A user who has never touched Olio in this org.
		Factory mf = IOSystem.getActiveContext().getFactory();
		String secondName = "ki35b" + System.currentTimeMillis();
		BaseRecord second = mf.getCreateUser(worldOrg.getAdminUser(), secondName, worldOrg.getOrganizationId());
		assertNotNull("Second acting user must be created", second);
		assertTrue("Precondition: the second user must NOT be the world's initialiser",
			!second.get(FieldNames.FIELD_ID).equals(primary.getConfig().getUser().get(FieldNames.FIELD_ID)));

		// Build a context AS THAT USER against the SAME, already-initialised universe/world. This is
		// the path that takes configureEnvironment's early return.
		org.cote.accountmanager.olio.OlioContext ctx = org.cote.accountmanager.olio.OlioContextUtil
			.getGridContext(second, testProperties.getProperty("test.datagen.path"),
				"Olio Universe", "Olio World", false);
		assertNotNull("Second user's Olio context must initialize", ctx);

		BaseRecord olioUserRole = IOSystem.getActiveContext().getPathUtil().findPath(ctx.getOlioUser(),
			ModelNames.MODEL_ROLE, "~/Roles/Olio User",
			org.cote.accountmanager.schema.type.RoleEnumType.USER.toString(), orgId(second));
		assertNotNull("The Olio User role must exist", olioUserRole);
		assertTrue("The second acting user must be enrolled in ~/Roles/Olio User — without it they hold "
			+ "no grant on the world groups where the Olio user owns the wearables, and every "
			+ "dress-up/down patch is refused",
			IOSystem.getActiveContext().getMemberUtil().isMember(second, olioUserRole, null));

		// Apparel created BY THE OLIO USER in the world groups, exactly as ApparelUtil does.
		BaseRecord apparel = org.cote.accountmanager.olio.ApparelUtil.randomApparel(ctx,
			((Number) ctx.getOlioUser().get(FieldNames.FIELD_ID)).longValue(), "female");
		assertNotNull("randomApparel must produce apparel", apparel);
		assertNotNull("Apparel must persist",
			IOSystem.getActiveContext().getAccessPoint().create(ctx.getOlioUser(), apparel));

		List<BaseRecord> wearables = apparel.get(
			org.cote.accountmanager.olio.schema.OlioFieldNames.FIELD_WEARABLES);
		BaseRecord wearable = null;
		for (BaseRecord w : wearables) {
			Long wid = w.get(FieldNames.FIELD_ID);
			if (wid != null && wid > 0L) { wearable = w; break; }
		}
		assertNotNull("At least one wearable must have persisted", wearable);
		assertTrue("Precondition: the wearable must be owned by the OLIO user, not the acting user",
			wearable.get(FieldNames.FIELD_OWNER_ID).equals(ctx.getOlioUser().get(FieldNames.FIELD_ID)));

		boolean before = Boolean.TRUE.equals(wearable.get("inuse"));
		BaseRecord patch = RecordFactory.newInstance(
			org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_WEARABLE);
		patch.set(FieldNames.FIELD_ID, wearable.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, wearable.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, wearable.get(FieldNames.FIELD_NAME));
		patch.set("inuse", !before);
		assertNotNull("The SECOND acting user must be permitted to patch inuse on the Olio-owned "
			+ "wearable — a null result here is the 'always worn' failure KI-35 reports",
			IOSystem.getActiveContext().getAccessPoint().update(second, patch));

		Query verify = QueryUtil.createQuery(
			org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_WEARABLE,
			FieldNames.FIELD_OBJECT_ID, wearable.get(FieldNames.FIELD_OBJECT_ID));
		verify.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "inuse" });
		verify.setCache(false);
		BaseRecord reread = IOSystem.getActiveContext().getSearch().findRecord(verify);
		assertNotNull(reread);
		assertEquals("inuse must actually have flipped in the database", !before,
			Boolean.TRUE.equals(reread.get("inuse")));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// SDUtil.createImage Integer/Double cast (found live in accountManagerService-error.log)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Live-observed portrait failure, 2026-08-08:
	 * <pre>
	 * PictureBookUtil - Portrait generation error for Catatonic Figure:
	 *   class java.lang.Integer cannot be cast to class java.lang.Double
	 *   at SDUtil.createImage(SDUtil.java:860)
	 *   at PictureBookUtil.generateSceneImage(...)
	 * </pre>
	 * {@code BaseRecord.get} is generic, so {@code s2i.setCfgScale(sdConfig.get("cfg"))} compiled to a
	 * cast to Double while olio.sd.config declares {@code cfg} as an INT — the field-type trap in
	 * objects7-reference.md. Every picture-book portrait that reached this path died before any image
	 * was requested.
	 *
	 * <p>No SD backend needed: the exception fired while BUILDING the request, so driving
	 * createImage with a schema-default config against an unreachable server still proves the fix —
	 * the ClassCastException would be thrown before the first HTTP call.
	 */
	@Test
	public void TestCreateImageDoesNotClassCastOnIntegerCfg() throws Exception {
		BaseRecord user = newVirginUser("sdcast");
		BaseRecord sdConfig = org.cote.accountmanager.olio.sd.SDUtil.randomSDConfig();
		assertNotNull(sdConfig);
		// Exactly the shape that failed: cfg is an int on the schema (default 7), so the record holds
		// an Integer while SWTxt2Img.setCfgScale takes a double.
		Object cfg = sdConfig.get("cfg");
		assertTrue("Precondition: olio.sd.config.cfg must be an Integer for this regression to mean "
			+ "anything (got " + (cfg == null ? "null" : cfg.getClass().getName()) + ")", cfg instanceof Integer);

		org.cote.accountmanager.olio.sd.SDUtil sdu = new org.cote.accountmanager.olio.sd.SDUtil(
			org.cote.accountmanager.olio.sd.SDAPIEnumType.SWARM, "http://127.0.0.1:1");
		try {
			sdu.createImage(user, "~/Gallery/KI Cast Probe", sdConfig, "cast probe", 1, false, -1);
		} catch (ClassCastException cce) {
			throw new AssertionError("createImage still throws while building the request: " + cce.getMessage(), cce);
		} catch (Exception transportFailure) {
			// Expected and irrelevant: the address is deliberately dead, so the call dies at the HTTP
			// boundary. Getting THAT far is the proof — the ClassCastException happened while building
			// the request, well before any socket was opened.
			assertTrue("A ClassCastException must not be the cause of the transport failure: "
				+ transportFailure, !hasCause(transportFailure, ClassCastException.class));
			logger.info("Request was built and failed only at the (deliberately dead) transport: "
				+ transportFailure.getClass().getSimpleName());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-53 — per-character style leaking into the composite prompt
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Reported live by Stephen 2026-08-10: a FLUX composite prompt carried THREE art styles even
	 * though the book and both portraits were configured as {@code photograph}.
	 *
	 * <pre>
	 * ... The first person is 8k ... circa 2500 AD.
	 *     Comic book panel in Archie Comics style from the manga-influenced Western 2000s ...
	 * ... The second person is 8k ... Dystopian North American city.
	 *     Fashion photography for CR Fashion Book in 1950s New Look elegance style by Cindy Sherman.
	 * ... Photograph taken with a Polaroid SX-70,Nikon F camera ...        &lt;- the book's real style
	 * </pre>
	 *
	 * A character's narrative bakes in a style clause at CREATION time from a random config
	 * ({@code NarrativeUtil.getSDPrompt} → {@code getSDConfigPrompt(randomSDConfig())}). The portrait
	 * path strips it before applying the book style; the scene-narration path did not, so each
	 * character dragged its own style into the composite.
	 *
	 * <p>Asserts on {@code stripTrailingConfigStyle}, the seam the fix installs, using clauses
	 * produced by the real generator for the two styles from the report — not hand-typed strings.
	 */
	@Test
	public void TestKi53PerCharacterStyleIsStrippedFromSceneNarration() throws Exception {
		logger.info("KI-53: a character's own baked-in style clause must not reach the composite prompt");
		String appearance = "8k highly detailed full body of a lean forty five 45yo man with dark brown hair. "
			+ "He is wearing charm pink spandex jeans. He is a cyber medic, circa 2500 AD.";

		for (String style : new String[] { "comic", "fashion", "photograph", "digitalArt", "anime", "vintage",
				"movie", "selfie", "portrait", "art" }) {
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("style", style);
			org.cote.accountmanager.olio.sd.SDUtil.fillStyleDefaults(cfg);
			String clause = org.cote.accountmanager.olio.sd.SDUtil.getSDConfigPrompt(cfg);
			assertNotNull("getSDConfigPrompt must produce a clause for style '" + style + "'", clause);

			// Exactly the shape a character narrative stores: description + its creation-time style.
			String baked = appearance + " " + clause;
			String stripped = org.cote.accountmanager.olio.picturebook.PictureBookUtil
				.stripTrailingConfigStyle(baked);

			assertTrue("The '" + style + "' style clause must be gone from the narration — it is what "
				+ "put a second and third art style into Stephen's composite prompt. Got [" + stripped + "]",
				!stripped.contains(clause));
			assertTrue("Stripping must not eat the character description itself — got [" + stripped + "]",
				stripped.contains("cyber medic") && stripped.contains("charm pink spandex jeans"));
		}
	}

	/**
	 * The strip must be a no-op on a description that carries no style clause, so a character whose
	 * narration is plain prose is not truncated.
	 */
	@Test
	public void TestKi53StripIsNoOpOnStyleFreeNarration() {
		String plain = "A lean man with dark brown hair wearing a blue coat and worn boots";
		assertEquals(plain, org.cote.accountmanager.olio.picturebook.PictureBookUtil
			.stripTrailingConfigStyle(plain));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// KI-56 — style changes mangled between the Ux and the prompt
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Reported by Stephen 2026-08-10: "the composite image style gets perverted somewhere from the Ux".
	 *
	 * Every branch of {@code getSDConfigPrompt} concatenates its per-style detail fields straight into
	 * the clause, so a config whose style was CHANGED (details belong to the old style, none exist for
	 * the new one) renders the missing values as the literal text "null":
	 * {@code "(Comic book panel) in (null) style from the (null) with (null)."}
	 * The Ux style picker set {@code style} without repopulating the details, so this is exactly what
	 * a style change produced.
	 */
	@Test
	public void TestKi56StyleClauseNeverContainsLiteralNull() throws Exception {
		for (String style : new String[] { "art", "movie", "photograph", "selfie", "anime", "portrait",
				"comic", "digitalArt", "fashion", "vintage" }) {
			// A config with the style set and NO detail fields — the post-style-change shape.
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("style", style);
			String clause = org.cote.accountmanager.olio.sd.SDUtil.getSDConfigPrompt(cfg);
			assertNotNull(clause);
			assertTrue("Style '" + style + "' produced a clause containing the literal text \"null\" — "
				+ "the per-style detail fields were never filled. Got: " + clause,
				!clause.toLowerCase().contains("(null)") && !clause.toLowerCase().contains(" null "));
		}
	}

	/**
	 * The counterpart to KI-56: switching style must not leave the PREVIOUS style's clause in place.
	 * Detail fields from the old style linger on the record, so completing the new style must still
	 * produce the new style's clause.
	 */
	@Test
	public void TestKi56StyleChangeProducesTheNewStyleClause() throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("style", "photograph");
		org.cote.accountmanager.olio.sd.SDUtil.fillStyleDefaults(cfg);
		String photographClause = org.cote.accountmanager.olio.sd.SDUtil.getSDConfigPrompt(cfg);
		assertTrue("baseline: " + photographClause, photographClause.contains("Photograph"));

		// The user picks a different style; the photograph details are still on the record.
		cfg.set("style", "comic");
		String comicClause = org.cote.accountmanager.olio.sd.SDUtil.getSDConfigPrompt(cfg);
		assertTrue("After a style change the clause must describe the NEW style. Got: " + comicClause,
			comicClause.contains("Comic book panel"));
		assertTrue("...and must not still contain the previous style's clause", !comicClause.equals(photographClause));
		assertTrue("...and must not contain a literal null: " + comicClause,
			!comicClause.toLowerCase().contains("(null)"));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// tagApparelSceneIndex PB1 guard
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * PB1 guard: {@code tagApparelSceneIndex} must return {@code false} without throwing
	 * when the character belongs to a group that has no associated {@code olio.pb.book} row
	 * — the PB1 scenario where a book predates the PB2 migration.
	 *
	 * <p>Simulated by creating a character in a non-"Characters" group (no book group
	 * navigation possible), which is what a PB1 world character looks like post-migration.
	 */
	@Test
	public void TestTagApparelSceneIndexSkipsPb1Books() throws Exception {
		logger.info("tagApparelSceneIndex PB1 guard: must return false without throwing for PB1 characters");
		BaseRecord user = newVirginUser("pb1guard");
		// Character in "~/Pb1Sim" — NOT in a group named "Characters", so book-group nav
		// finds nothing; the guard returns false instead of delegating to authorizeCharacterApparel.
		BaseRecord char1 = newNamedCharacter(user, "Pb1TestChar" + System.currentTimeMillis(), "~/Pb1Sim");
		assertNotNull("test character must exist", char1);

		boolean result = PictureBookUtil.tagApparelSceneIndex(
			user,
			char1.get(FieldNames.FIELD_OBJECT_ID),
			java.util.UUID.randomUUID().toString(),
			1);
		assertFalse("tagApparelSceneIndex must return false for a PB1 character (no olio.pb.book)", result);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Q5 — OlioContext auth-failure propagation
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Q5: {@code OlioContext.initialize()} must throw {@code RuntimeException} (not silently
	 * swallow the exception) when authorization configuration fails, and
	 * {@code isAuthorizationConfigured()} must remain {@code false} after the throw.
	 *
	 * <p>Triggered by a half-configured universe authorization pair (user role set, admin role
	 * null) — the explicit {@code OlioException} thrown at line 905 of {@code initialize()} when
	 * exactly one of the two roles is non-null.
	 */
	@Test
	public void TestQ5OlioContextThrowsOnAuthFailure() throws Exception {
		logger.info("Q5: OlioContext.initialize() must propagate instead of swallowing auth failure");
		BaseRecord user = newVirginUser("q5auth");

		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			testProperties.getProperty("test.datagen.path"),
			"Q5Universe", "Q5World",
			new String[0], 1, 1, false, false);
		cfg.setRequireRealms(false);
		cfg.getContextRules().add(new BookWorldInitializationRule());

		// Half-configured universe pair: user role set, admin role null → OlioException at initialize():905.
		// After the fix the catch block re-throws as RuntimeException because !authorizationConfigured.
		BaseRecord fakeUserRole = RecordFactory.newInstance(ModelNames.MODEL_ROLE);
		fakeUserRole.set(FieldNames.FIELD_NAME, "Q5FakeUserRole");
		cfg.setUniverseAuthorizationUserRole(fakeUserRole);
		// cfg.setUniverseAuthorizationAdminRole intentionally NOT set (null)

		OlioContext ctx = new OlioContext(cfg);
		RuntimeException caught = null;
		try {
			ctx.initialize();
		} catch (RuntimeException e) {
			caught = e;
		}
		assertNotNull("initialize() must throw RuntimeException when auth config fails", caught);
		assertFalse("isAuthorizationConfigured() must be false after a failed initialize()", ctx.isAuthorizationConfigured());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// helpers shared with later KI tests
	// ─────────────────────────────────────────────────────────────────────────

	private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (type.isInstance(c)) return true;
		}
		return false;
	}

	protected BaseRecord makeGroup(BaseRecord user, String path) {
		return IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId(user));
	}
}
