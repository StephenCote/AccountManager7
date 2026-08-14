package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/**
 * Characterization suite for {@code PathUtil.makePath} / {@code findPath}.
 *
 * <p>REPRODUCE-AND-REPORT. Every assertion here states what the behaviour ought to be. Where the
 * behaviour is correct the test is a regression guard; where it is wrong the test is deliberately
 * left RED and the defect is reported. Nothing in the product is changed by this suite.
 *
 * <p>Grounding (verified against {@code PathUtil.java} as of 2026-08-13, line refs corrected):
 * <ul>
 *   <li>{@code findPath} :55-57 delegates to the private overload with {@code doCreate=false}, and is
 *       NOT synchronized.</li>
 *   <li>public {@code makePath} :62-64 IS {@code synchronized} on the instance and routes with
 *       {@code doCreate=true}.</li>
 *   <li>{@code ~} expands to {@code owner.homeDirectory.path} at :72-88.</li>
 *   <li>the path is {@code split("/")} at :90 and empty segments are filtered at :102 — so there is
 *       no normalization of any kind: no leading-slash semantics, no {@code .}/{@code ..}, and no
 *       detection of a path that already contains its own prefix.</li>
 *   <li>one {@code findByNameInParent}/{@code findByNameInGroup} per segment at :105-131, with a
 *       per-segment TYPE OVERRIDE at :113-120 ({@code utype}) that is used for the LOOKUP but never
 *       for the CREATE (:151-153 writes {@code type}, not {@code utype}).</li>
 *   <li>creates {@code writer.write} + {@code writer.flush()} at :170-171, inside the monitor, with
 *       the KI-42 lost-write recovery at :172-200 and {@code findExistingNode} at :236-251.</li>
 * </ul>
 *
 * <p>Runs against the live Postgres the other Objects7 tests use. No LLM, embedding or image backend
 * is touched. Every test creates its own uuid-suffixed scratch tree under a brand-new user's home so
 * the suite is re-runnable and cannot collide with existing data or with itself.
 */
public class TestPathUtilBehavior extends BaseTest {

	/** KI-60's reported sibling set, in the order a picture-book run resolves them. */
	private static final String[] KI60_SIBLINGS = new String[] {
		"Apparel", "Wearables", "Qualities", "Narratives", "Profiles",
		"Statistics", "Instincts", "Personalities", "States"
	};

	// ─────────────────────────────────────────────────────────────────────────
	// harness
	// ─────────────────────────────────────────────────────────────────────────

	private static String uuid8() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/** A user nobody has used before, so its home tree is empty. Never the admin user. */
	private BaseRecord newUser(String prefix) {
		Factory mf = IOSystem.getActiveContext().getFactory();
		/// $minLen5 needs five consecutive alphanumerics; prefix+hex satisfies it.
		String name = prefix + uuid8();
		BaseRecord u = mf.getCreateUser(orgContext.getAdminUser(), name, orgContext.getOrganizationId());
		assertNotNull("Factory.getCreateUser returned null for '" + name + "'", u);
		IOSystem.getActiveContext().getRecordUtil().populate(u);
		return u;
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String homePath(BaseRecord user) {
		String hp = user.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
		assertNotNull("The test user must have a resolvable home directory path", hp);
		assertTrue("Home path must be absolute: " + hp, hp.startsWith("/"));
		return hp;
	}

	private long homeId(BaseRecord user) {
		BaseRecord home = user.get(FieldNames.FIELD_HOME_DIRECTORY);
		assertNotNull("The test user must have a home directory", home);
		IOSystem.getActiveContext().getRecordUtil().populate(home);
		return ((Number) home.get(FieldNames.FIELD_ID)).longValue();
	}

	private BaseRecord makePath(BaseRecord user, String path, String type) {
		return IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path,
			type, orgId(user));
	}

	private BaseRecord makePath(BaseRecord user, String path) {
		return makePath(user, path, GroupEnumType.DATA.toString());
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	/** Reads one auth.group row straight from the DB, cache off. Null means the id has no row. */
	private BaseRecord row(long id, long organizationId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID,
			FieldNames.FIELD_TYPE, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/**
	 * The independent oracle for D1: the path a returned group ACTUALLY occupies, rebuilt by walking
	 * the parentId chain straight out of the database rather than trusting the virtual {@code path}
	 * field (which is computed by the same PathProvider machinery under test).
	 */
	private String materializedPath(long id, long organizationId) {
		List<String> parts = new ArrayList<>();
		long cur = id;
		for (int guard = 0; cur > 0L && guard < 64; guard++) {
			BaseRecord r = row(cur, organizationId);
			if (r == null) {
				return "<PHANTOM id=" + cur + ">" + (parts.isEmpty() ? "" : "/" + String.join("/", parts));
			}
			parts.add(0, (String) r.get(FieldNames.FIELD_NAME));
			cur = ((Number) r.get(FieldNames.FIELD_PARENT_ID)).longValue();
		}
		return "/" + String.join("/", parts);
	}

	/** Rows literally present for (name, parentId, organizationId) — the unique constraint's own key. */
	private int countRows(long parentId, String name, long organizationId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, parentId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/** Direct, uncached read on the constraint key — no type filter. */
	private BaseRecord uncachedByNameInParent(long parentId, String name, long organizationId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, parentId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID,
			FieldNames.FIELD_TYPE, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/** Creates a group row directly (no makePath), so a specific pre-state can be arranged. */
	private BaseRecord rawGroup(BaseRecord user, long parentId, String name, GroupEnumType type) throws Exception {
		BaseRecord g = RecordFactory.model(ModelNames.MODEL_GROUP).newInstance();
		g.set(FieldNames.FIELD_NAME, name);
		g.set(FieldNames.FIELD_PARENT_ID, parentId);
		g.set(FieldNames.FIELD_ORGANIZATION_ID, orgId(user));
		g.set(FieldNames.FIELD_TYPE, type.toString());
		g.set(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		assertTrue("Precondition: raw group '" + name + "' must be created",
			IOSystem.getActiveContext().getRecordUtil().createRecord(g));
		return g;
	}

	/** Captures WARN/ERROR emitted by production code while the body runs. */
	private static final class LogCapture implements AutoCloseable {
		private final List<String> messages = new CopyOnWriteArrayList<>();
		private final LoggerConfig root;
		private final AbstractAppender appender;
		private final String tag;

		LogCapture(String tag) {
			this.tag = tag;
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			Configuration cfg = ctx.getConfiguration();
			root = cfg.getRootLogger();
			appender = new AbstractAppender(tag, null, null, true, null) {
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
			root.removeAppender(tag);
			appender.stop();
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// D1 — "a whole path emitted under a sub path"
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * D1 baseline / control. A plain absolute path must land exactly where it says, and calling
	 * makePath again with the same string must return the SAME group — not a second copy nested
	 * under the first. If this ever fails, D1 is unconditional rather than input-dependent.
	 */
	@Test
	public void TestD1RepeatedMakePathIsIdempotentAndLandsAtTheStatedPath() {
		BaseRecord user = newUser("pathid");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		String target = scratch + "/A/B/C";

		BaseRecord first = makePath(user, target);
		assertNotNull("makePath must create " + target, first);
		assertEquals("The created group must occupy exactly the requested path", target,
			materializedPath(idOf(first), org));

		BaseRecord second = makePath(user, target);
		assertNotNull(second);
		assertEquals("A second makePath for the same path must return the same group", idOf(first), idOf(second));
		assertEquals("...and must not have re-emitted the path beneath itself", target,
			materializedPath(idOf(second), org));
		assertEquals("Exactly one 'C' may exist under B", 1,
			countRows(((Number) first.get(FieldNames.FIELD_PARENT_ID)).longValue(), "C", org));
	}

	/**
	 * D1 hypothesis (a): absolute vs relative. {@code path.split("/")} plus the empty-segment filter
	 * (:90/:102) means a LEADING SLASH CARRIES NO MEANING — "A/B/C" and "/A/B/C" are the same input.
	 * Pinned here because it means PathUtil has no relative-path concept at all: a caller that passes
	 * a relative path expecting it to resolve under some current node silently gets an org-root walk.
	 */
	@Test
	public void TestD1LeadingSlashIsMeaninglessSoRelativeAndAbsoluteAreTheSamePath() {
		BaseRecord user = newUser("pathrl");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8() + "/Rel";

		BaseRecord abs = makePath(user, scratch);
		assertNotNull(abs);
		BaseRecord rel = makePath(user, scratch.substring(1));
		assertNotNull("A path without a leading slash must still resolve", rel);
		assertEquals("'" + scratch + "' and '" + scratch.substring(1) + "' must resolve to the same group",
			idOf(abs), idOf(rel));
		assertEquals(scratch, materializedPath(idOf(rel), org));
	}

	/**
	 * D1 hypothesis (d): trailing and doubled separators. Empty segments are filtered at :102, so
	 * these must be no-ops rather than producing an empty-named child.
	 */
	@Test
	public void TestD1TrailingAndDoubledSeparatorsDoNotCreateEmptyNodes() {
		BaseRecord user = newUser("pathsp");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		String target = scratch + "/A/B";

		BaseRecord plain = makePath(user, target);
		assertNotNull(plain);
		BaseRecord trailing = makePath(user, target + "/");
		BaseRecord doubled = makePath(user, scratch + "/A//B");
		BaseRecord both = makePath(user, scratch + "//A/B//");

		assertNotNull(trailing);
		assertNotNull(doubled);
		assertNotNull(both);
		assertEquals("A trailing separator must not change the resolved node", idOf(plain), idOf(trailing));
		assertEquals("A doubled separator must not change the resolved node", idOf(plain), idOf(doubled));
		assertEquals("Both together must not change the resolved node", idOf(plain), idOf(both));
		assertEquals(target, materializedPath(idOf(both), org));
	}

	/**
	 * D1 hypothesis (b)+(c), the primary candidate: a caller prefixes {@code "~/"} onto a value that
	 * is ALREADY the absolute home-qualified path. {@code makePath} expands {@code ~} to the home path
	 * (:72-88) and then splits, with no check that the remainder already starts with that same home
	 * path — so the whole path is re-emitted beneath itself.
	 *
	 * <p>This is not a hypothetical input shape: {@code PathProvider.provide} (:53-60) WRITES the
	 * expanded absolute path back onto the record's path field after resolving a {@code "~/"} path, so
	 * a stored {@code groupPath} is absolute, while 15+ production call sites build paths as
	 * {@code "~/" + <something>} ({@code CharPersonFactory}, {@code PictureBookUtil},
	 * {@code VaultService}, {@code RecordUtil.getCreateDirectoryModel}). Feed one to the other and
	 * this is what happens.
	 */
	@Test
	public void TestD1TildePrefixOnAnAlreadyAbsolutePathMustNotReEmitTheWholePath() {
		BaseRecord user = newUser("pathdb");
		long org = orgId(user);
		String hp = homePath(user);
		String leaf = "PU-" + uuid8();
		/// The caller has an absolute path in hand and prefixes "~/" onto it anyway.
		String doubled = "~/" + hp + "/" + leaf;

		/// Path trace, bracketed to this one call only.
		BaseRecord g;
		IOSystem.getActiveContext().getPathUtil().setTrace(true);
		try {
			g = makePath(user, doubled);
		}
		finally {
			IOSystem.getActiveContext().getPathUtil().setTrace(false);
		}
		String actual = (g == null ? null : materializedPath(idOf(g), org));
		logger.info("D1 double-prefix input=[" + doubled + "] resolved=[" + actual + "]");

		assertNotNull("makePath returned null for '" + doubled + "'", g);
		assertTrue("A path that already contains the home prefix must not be re-emitted beneath the "
			+ "home. input=[" + doubled + "] home=[" + hp + "] resolved=[" + actual + "]",
			!actual.startsWith(hp + hp));
		assertEquals("The requested leaf must land directly under the home", hp + "/" + leaf, actual);
	}

	/**
	 * D1 hypothesis (c) without any caller-side concatenation: the requested {@code "~/"} path itself
	 * already names the home tree. Same mechanism as above, reached by a different route.
	 */
	@Test
	public void TestD1TildePathThatAlreadyNamesTheHomeTreeMustNotReEmitTheWholePath() {
		BaseRecord user = newUser("pathth");
		long org = orgId(user);
		String hp = homePath(user);
		String leaf = "PU-" + uuid8();
		/// "~/home/<user>/X" — the home tree named twice: once by ~, once literally.
		String req = "~" + hp + "/" + leaf;

		BaseRecord g = makePath(user, req);
		String actual = (g == null ? null : materializedPath(idOf(g), org));
		logger.info("D1 self-prefix input=[" + req + "] resolved=[" + actual + "]");

		assertNotNull("makePath returned null for '" + req + "'", g);
		assertEquals("'" + req + "' must resolve to " + hp + "/" + leaf + ", not to a copy of the home "
			+ "tree nested inside itself", hp + "/" + leaf, actual);
	}

	/**
	 * D1 hypothesis (f): the leading segment of the requested path matches an existing sibling of a
	 * DIFFERENT GroupEnumType. The per-segment lookup is type-filtered (:130), the unique constraint
	 * is not — so the intermediate node is invisible to the lookup and the create collides with it.
	 * What must NOT happen is the remainder of the path being emitted somewhere else.
	 */
	@Test
	public void TestD1IntermediateSegmentOfAnotherTypeStillResolvesTheRestOfThePathInPlace() throws Exception {
		BaseRecord user = newUser("pathim");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		BaseRecord scratchGrp = makePath(user, scratch);
		assertNotNull(scratchGrp);

		/// An intermediate node that a DATA-filtered lookup cannot see.
		BaseRecord mid = rawGroup(user, idOf(scratchGrp), "Mid", GroupEnumType.BUCKET);

		BaseRecord leaf = makePath(user, scratch + "/Mid/Leaf");
		String actual = (leaf == null ? null : materializedPath(idOf(leaf), org));
		logger.info("D1 mixed-type intermediate resolved=[" + actual + "]");

		assertNotNull("makePath must resolve through a differently-typed intermediate node", leaf);
		assertEquals("The leaf must land under the EXISTING 'Mid', not somewhere else", scratch + "/Mid/Leaf", actual);
		assertEquals("'Leaf' must be a child of the pre-existing Mid #" + idOf(mid), idOf(mid),
			((Number) leaf.get(FieldNames.FIELD_PARENT_ID)).longValue());
		assertEquals("Exactly one 'Mid' may exist", 1, countRows(idOf(scratchGrp), "Mid", org));
	}

	/**
	 * D1 hypothesis (e), the only re-entrancy this codebase actually offers a seam for:
	 * {@code PathProvider.provide} calls {@code makePath} while a record is being constructed
	 * (:49), and that construction can itself be interleaved with a direct makePath for an
	 * OVERLAPPING path. {@code makePath} is synchronized on the instance and Java monitors are
	 * reentrant, so a same-thread nested call is permitted; this pins that the overlap resolves to
	 * one tree rather than two.
	 *
	 * <p>Explicit limitation, stated rather than papered over: this exercises SEQUENTIAL overlap
	 * (provider-driven makePath, then direct makePath of a prefix and an extension). It does NOT
	 * exercise a makePath re-entered from inside another makePath's monitor, because nothing in the
	 * auth.group write path calls back into PathUtil — the group model's own {@code path} field is
	 * parent-derived and carries no baseModel/baseProperty, so PathProvider's NEW branch never fires
	 * during a group create. Producing true re-entrancy would require adding a provider to a
	 * production model, which this run does not do.
	 */
	@Test
	public void TestD1ProviderDrivenMakePathAndDirectMakePathAgreeOnOneTree() throws Exception {
		BaseRecord user = newUser("pathre");
		long org = orgId(user);
		String scratch = "PU-" + uuid8();
		String hp = homePath(user);
		String rel = "~/" + scratch + "/Nested/Deep";
		String abs = hp + "/" + scratch + "/Nested/Deep";

		/// PathProvider's NEW branch runs makePath(model=auth.group, path="~/...", type=null).
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, rel);
		plist.parameter(FieldNames.FIELD_NAME, "PathProbe " + uuid8());
		BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		assertNotNull("Factory.newInstance with a path parameter must produce a record", data);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, data);
		assertNotNull("The probe record must persist", created);

		try (LogCapture cap = new LogCapture("d1reentrant")) {
			BaseRecord direct = makePath(user, abs);
			assertNotNull(direct);
			assertEquals("The provider-created tree and the directly-created tree must be the same tree",
				abs, materializedPath(idOf(direct), org));
			BaseRecord prefix = makePath(user, hp + "/" + scratch + "/Nested");
			assertEquals("...and the overlapping prefix must be the direct parent",
				((Number) direct.get(FieldNames.FIELD_PARENT_ID)).longValue(), idOf(prefix));
			List<String> dupes = cap.matching("duplicate key");
			assertTrue("Overlapping resolution must not attempt a duplicate insert: " + dupes, dupes.isEmpty());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// findPath
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * {@code findPath} (:55-57) must never create. It shares the whole body with makePath and only
	 * differs by the {@code doCreate} flag, so this is worth pinning: a missing intermediate must
	 * yield null with no rows written anywhere along the path.
	 */
	@Test
	public void TestFindPathNeverCreatesAnything() {
		BaseRecord user = newUser("pathfp");
		long org = orgId(user);
		long home = homeId(user);
		String scratch = "PU-" + uuid8();
		String target = homePath(user) + "/" + scratch + "/Missing/Deeper";

		BaseRecord found = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP,
			target, GroupEnumType.DATA.toString(), org);
		assertNull("findPath must return null for a path that does not exist: " + target, found);
		assertEquals("findPath must not have created the first missing segment", 0,
			countRows(home, scratch, org));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// D3 — the type-filtered lookup vs the type-less unique constraint
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * D3, part 1 — IDENTITY. A pre-existing group with the right (name, parentId, organizationId) but
	 * a different {@code GroupEnumType} makes the type-filtered lookup (:130) miss, the insert
	 * collide, and the KI-42 recovery (:191) adopt whatever {@code findExistingNode} re-reads.
	 * Whatever it adopts, it must be the requested node: same name, same parent, same org, and a row
	 * that really exists.
	 */
	@Test
	public void TestD3MismatchedTypeRecoveryReturnsARecordWithTheRequestedIdentity() throws Exception {
		BaseRecord user = newUser("pathd3");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		BaseRecord scratchGrp = makePath(user, scratch);
		assertNotNull(scratchGrp);
		long parent = idOf(scratchGrp);

		BaseRecord pre = rawGroup(user, parent, "Narratives", GroupEnumType.BUCKET);
		assertTrue("Precondition: the colliding row must exist", idOf(pre) > 0L);

		BaseRecord result = makePath(user, scratch + "/Narratives", GroupEnumType.DATA.toString());
		assertNotNull("A get-or-create whose create loses must still return the existing node, not null",
			result);

		BaseRecord actualRow = row(idOf(result), org);
		assertNotNull("makePath returned auth.group id=" + idOf(result) + " but no such row exists "
			+ "(a phantom group)", actualRow);
		assertEquals("The returned group must be named 'Narratives' — KI-60 observed #151 'Apparel' "
			+ "coming back for a 'Narratives' request", "Narratives", actualRow.get(FieldNames.FIELD_NAME));
		assertEquals("...and must sit under the requested parent", parent,
			((Number) actualRow.get(FieldNames.FIELD_PARENT_ID)).longValue());
		assertEquals("...and must be in the requested organization", org,
			((Number) actualRow.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
		assertEquals("...and must be the row that was already there", idOf(pre), idOf(result));
		assertEquals("Exactly one 'Narratives' may exist under the parent", 1,
			countRows(parent, "Narratives", org));
	}

	/**
	 * D3, part 2 — TYPE. Split from part 1 so the two outcomes are distinguishable. The caller asked
	 * for a DATA group; if makePath hands back a BUCKET group with no signal, every subsequent
	 * type-filtered lookup for the same path misses again and re-attempts the same losing insert —
	 * which is exactly KI-60's "the duplicate-key INSERT still fires on every run".
	 */
	@Test
	public void TestD3MismatchedTypeRequestIsNotSilentlySatisfiedByAnotherType() throws Exception {
		BaseRecord user = newUser("pathd4");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		long parent = idOf(makePath(user, scratch));
		rawGroup(user, parent, "Narratives", GroupEnumType.BUCKET);

		BaseRecord result = makePath(user, scratch + "/Narratives", GroupEnumType.DATA.toString());
		assertNotNull(result);
		BaseRecord actualRow = row(idOf(result), org);
		assertNotNull(actualRow);
		Object actualType = actualRow.get(FieldNames.FIELD_TYPE);
		assertEquals("makePath was asked for a DATA group and must not silently return a group of "
			+ "another type — the caller has no way to tell, and every later DATA lookup for this path "
			+ "will miss the row again and re-attempt the same losing insert",
			GroupEnumType.DATA.toString(), String.valueOf(actualType));
	}

	/**
	 * D3, part 3 — REPEATABILITY, and the direct measurement of KI-60's open question ("a lookup that
	 * fails to see a row which is present"). Calling makePath twice for the same mismatched-type path
	 * must not attempt the failing INSERT a second time.
	 */
	@Test
	public void TestD3MismatchedTypeDoesNotReAttemptTheLosingInsertOnEveryCall() throws Exception {
		BaseRecord user = newUser("pathd5");
		String scratch = homePath(user) + "/PU-" + uuid8();
		long parent = idOf(makePath(user, scratch));
		rawGroup(user, parent, "Narratives", GroupEnumType.BUCKET);

		makePath(user, scratch + "/Narratives", GroupEnumType.DATA.toString());

		List<String> dupes;
		try (LogCapture cap = new LogCapture("d3repeat")) {
			BaseRecord again = makePath(user, scratch + "/Narratives", GroupEnumType.DATA.toString());
			assertNotNull(again);
			dupes = cap.matching("duplicate key");
		}
		assertTrue("A SECOND makePath for a path already resolved once must not re-attempt the insert "
			+ "that already lost. Offending lines: " + dupes, dupes.isEmpty());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// The utype/type asymmetry at :113-153 — a lookup that can never see its own create
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * :113-120 overrides the lookup type ({@code utype}) for any segment named {@code "home"} or
	 * named after the owner, forcing DATA for auth.group. :151-153 then creates the node with the
	 * ORIGINAL {@code type}. When the caller asked for a non-DATA group, the node is therefore
	 * written with a type its own lookup can never match — so every subsequent call re-attempts the
	 * same insert and collides on the (name, parentId, organizationId) constraint.
	 *
	 * <p>This is a deterministic instance of KI-60's still-unexplained "a lookup that fails to see a
	 * row which is present", reached with NO hand-placed pre-existing row and no cache involvement.
	 */
	@Test
	public void TestUtypeOverrideCreatesANodeItsOwnLookupCanNeverSee() {
		BaseRecord user = newUser("pathut");
		long org = orgId(user);
		String userName = user.get(FieldNames.FIELD_NAME);
		String scratch = homePath(user) + "/PU-" + uuid8();
		/// The scratch node is created as BUCKET too, so the ONLY type asymmetry left in the path is
		/// the one :113-120 introduces on the owner-named segment. Otherwise this test would also
		/// exhibit the ordinary D3 mixed-type collision and the two causes could not be told apart.
		long parent = idOf(makePath(user, scratch, GroupEnumType.BUCKET.toString()));

		/// A segment named after the owner, in a BUCKET-typed request.
		String target = scratch + "/" + userName + "/Leaf";
		BaseRecord first = makePath(user, target, GroupEnumType.BUCKET.toString());
		assertNotNull("First makePath must create " + target, first);

		List<String> dupes;
		BaseRecord second;
		try (LogCapture cap = new LogCapture("utype")) {
			second = makePath(user, target, GroupEnumType.BUCKET.toString());
			dupes = cap.matching("duplicate key");
		}
		assertNotNull(second);
		assertEquals("Resolving the same path twice must return the same node", idOf(first), idOf(second));
		assertEquals("Exactly one '" + userName + "' node may exist under the scratch group", 1,
			countRows(parent, userName, org));
		assertTrue("A second resolution of an already-created path must not attempt an insert that "
			+ "violates the unique constraint. The lookup at PathUtil:113-130 forces type=DATA for a "
			+ "segment named after the owner, while the create at :151-153 writes the REQUESTED type "
			+ "(BUCKET) — so the node can never be found again. Offending lines: " + dupes,
			dupes.isEmpty());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// D2 / D4 — KI-60's sibling set, with and without the search cache
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * D2 — the reported KI-60 failure, with the identity assertions the existing
	 * {@code TestKi42MakePathNeverReturnsAGroupThatIsNotInTheDatabase} does not make (it checks that
	 * the returned id has a row and that one Narratives row exists, but never that the returned
	 * record IS Narratives — so it would pass on the reported defect).
	 *
	 * <p>Reproduces the reported SHAPE: the full sibling set present under one parent, resolved in
	 * the picture-book order through the same repeated {@code findByNameInParent(auth.group, parent,
	 * <name>, DATA, org)} call, with {@code Narratives} arranged so the type-filtered lookup misses
	 * it and the recovery path (:191 {@code findExistingNode}) has to choose.
	 */
	@Test
	public void TestD2SiblingSetRecoveryReturnsNarrativesAndNotASibling() throws Exception {
		BaseRecord user = newUser("pathd2");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		long parent = idOf(makePath(user, scratch));

		/// Every sibling except Narratives via makePath, in KI-60's order; Narratives placed raw with a
		/// type the DATA-filtered lookup cannot match, so the recovery path is the one under test.
		for (String sib : KI60_SIBLINGS) {
			if ("Narratives".equals(sib)) {
				rawGroup(user, parent, sib, GroupEnumType.BUCKET);
			} else {
				assertNotNull("Sibling '" + sib + "' must be created", makePath(user, scratch + "/" + sib));
			}
		}

		/// Re-resolve the whole sibling set in order, as a picture-book run does, ending on Narratives.
		for (String sib : KI60_SIBLINGS) {
			BaseRecord g = makePath(user, scratch + "/" + sib);
			assertNotNull("Re-resolution of '" + sib + "' returned null", g);
			BaseRecord actualRow = row(idOf(g), org);
			assertNotNull("makePath returned id=" + idOf(g) + " for '" + sib + "' but no such row exists",
				actualRow);
			assertEquals("makePath for '" + sib + "' returned a group named '"
				+ actualRow.get(FieldNames.FIELD_NAME) + "' (id " + idOf(g) + "). KI-60 reported exactly "
				+ "this: #151 'Apparel' handed back for a 'Narratives' request.",
				sib, actualRow.get(FieldNames.FIELD_NAME));
			assertEquals("'" + sib + "' must sit under the requested parent", parent,
				((Number) actualRow.get(FieldNames.FIELD_PARENT_ID)).longValue());
			assertEquals("'" + sib + "' must be in the requested organization", org,
				((Number) actualRow.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
			assertEquals("Exactly one '" + sib + "' row may exist under the parent", 1,
				countRows(parent, sib, org));
		}
	}

	/**
	 * D4 — the open question in KI-60's entry: does {@code CacheDBSearch}'s query key distinguish the
	 * NAME VALUE across the repeated sibling lookups, or does every later lookup return the first
	 * cached sibling?
	 *
	 * <p>The experiment: drive the exact call {@code makePath} uses —
	 * {@code search.findByNameInParent(auth.group, parent, name, "DATA", org)}, which goes through the
	 * cache — across the whole sibling set in the reported order, then repeat the identical sequence
	 * against hand-built queries with {@code setCache(false)}. If the cache key is the problem, the
	 * cached pass returns the wrong sibling and the uncached pass does not. Both passes are asserted
	 * against the sibling's own name, and against each other.
	 */
	@Test
	public void TestD4SearchCacheDoesNotConfuseSiblingLookups() throws Exception {
		BaseRecord user = newUser("pathd6");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		long parent = idOf(makePath(user, scratch));

		for (String sib : KI60_SIBLINGS) {
			assertNotNull("Sibling '" + sib + "' must be created", makePath(user, scratch + "/" + sib));
		}

		/// Pass 1: exactly what makePath does (cached).
		List<Long> cached = new ArrayList<>();
		for (String sib : KI60_SIBLINGS) {
			BaseRecord[] hits = IOSystem.getActiveContext().getSearch().findByNameInParent(
				ModelNames.MODEL_GROUP, parent, sib, GroupEnumType.DATA.toString(), org);
			assertEquals("The cached type-filtered lookup must find exactly one '" + sib + "'", 1, hits.length);
			assertEquals("The CACHED lookup for '" + sib + "' returned '" + hits[0].get(FieldNames.FIELD_NAME)
				+ "'. That is KI-60's CacheDBSearch query-key theory confirmed.",
				sib, hits[0].get(FieldNames.FIELD_NAME));
			cached.add(idOf(hits[0]));
		}

		/// Pass 2: the same sequence with the cache explicitly off.
		List<Long> uncached = new ArrayList<>();
		for (String sib : KI60_SIBLINGS) {
			BaseRecord hit = uncachedByNameInParent(parent, sib, org);
			assertNotNull("The uncached lookup must find '" + sib + "'", hit);
			assertEquals("The UNCACHED lookup for '" + sib + "' returned the wrong row", sib,
				hit.get(FieldNames.FIELD_NAME));
			uncached.add(idOf(hit));
		}

		assertEquals("setCache(false) must not change which rows the sibling lookups resolve to. If "
			+ "these differ, the search cache is the KI-60 cause; if they agree, the cache is eliminated. "
			+ "cached=" + cached + " uncached=" + uncached, uncached, cached);

		/// And the same again through makePath itself, which is what production calls.
		for (int i = 0; i < KI60_SIBLINGS.length; i++) {
			BaseRecord g = makePath(user, scratch + "/" + KI60_SIBLINGS[i]);
			assertNotNull(g);
			assertEquals("makePath for '" + KI60_SIBLINGS[i] + "' must return the same row the direct "
				+ "lookups did", uncached.get(i).longValue(), idOf(g));
		}
	}

	/**
	 * D4 companion — the delete-then-recreate trigger KI-60's reproduction steps name. A sibling is
	 * resolved (populating the cache), deleted, and resolved again. If the cache is not invalidated by
	 * the delete, the second resolution hands back the deleted row's id — a phantom group, which is
	 * the KI-42 symptom by a different route.
	 */
	@Test
	public void TestD4DeleteThenRecreateDoesNotReturnTheDeletedRow() throws Exception {
		BaseRecord user = newUser("pathd7");
		long org = orgId(user);
		String scratch = homePath(user) + "/PU-" + uuid8();
		long parent = idOf(makePath(user, scratch));

		for (String sib : KI60_SIBLINGS) {
			assertNotNull(makePath(user, scratch + "/" + sib));
		}
		BaseRecord narratives = makePath(user, scratch + "/Narratives");
		assertNotNull(narratives);
		long deletedId = idOf(narratives);

		assertTrue("Precondition: the Narratives group must delete",
			IOSystem.getActiveContext().getRecordUtil().deleteRecord(narratives));
		assertEquals("Precondition: the row must be gone", 0, countRows(parent, "Narratives", org));

		BaseRecord recreated = makePath(user, scratch + "/Narratives");
		assertNotNull("makePath must re-create the deleted group", recreated);
		assertTrue("makePath returned the DELETED row's id (#" + deletedId + ") after it was removed — "
			+ "a stale cache entry surviving the delete", idOf(recreated) != deletedId);
		BaseRecord actualRow = row(idOf(recreated), org);
		assertNotNull("The re-created group id must have a real row", actualRow);
		assertEquals("Narratives", actualRow.get(FieldNames.FIELD_NAME));
		assertEquals(1, countRows(parent, "Narratives", org));
	}
}
