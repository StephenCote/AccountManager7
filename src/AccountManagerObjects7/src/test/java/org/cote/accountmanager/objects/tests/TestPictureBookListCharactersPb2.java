package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * The reported, repeatedly-mis-"fixed" PictureBook defect, reproduced at the layer it actually lives:
 * <em>"it extracts characters, but then doesn't show any characters having been extracted."</em>
 *
 * <p><b>Layer verdict this test establishes.</b> Extraction ({@code createFromScenes}) succeeds and the
 * charPerson records are genuinely persisted; the "Manage Characters" screen shows zero because the READ
 * ({@code listCharacters}) looked in a different group than the WRITE used. For a PB2 book the write path
 * re-routes the characters into the book world's <b>population group</b>
 * ({@code olio.world.population.path}), while the read path was hard-coded to the legacy
 * {@code {bookGroupPath}/Characters} sub-group — which the write path creates but leaves EMPTY. So the raw
 * data is correct and complete; only the client-side/read-side query was wrong. This is a
 * <b>client-query / read-path bug</b>, fixed in {@code listCharacters}, not a backend/query-plan defect.
 *
 * <p><b>Why this reproduction and not KI-42's.</b> The two {@code createFromScenes} overloads differ in
 * exactly this respect: the 8-arg legacy overload never re-routes {@code charsGroup} (it stays
 * {@code {bookGroupPath}/Characters}, so write and read already agree — which is why the KI-42 tests pass
 * and never exposed this). Only the 9-arg PB2 overload (with a real {@code pb2BookObjectId}) re-routes
 * into the world population group. This test therefore drives the 9-arg overload with a real PB2 book.
 *
 * <p><b>Independent proof, not the field under test.</b> The character count is verified by resolving the
 * world's population group directly (world → {@code population.path} → group → count charPersons by
 * {@code groupId}) — never through {@code meta.charsGroupPath} (the value the fix persists). It then
 * asserts {@code listCharacters(...)} returns those same characters by name, and that the legacy
 * {@code {bookGroupPath}/Characters} group the old read path used is genuinely EMPTY. The three together
 * are the mechanism: characters exist in group A, the old read looked in empty group B, the fix reads A.
 *
 * <p>Runs against the live Postgres + live Ollama ({@code test.llm.ollama.server}) backend, single
 * character-creation at a time. No SD: {@code createCharPerson} is LLM-only (baseline + apparel wizard +
 * statistics estimation); no image generation is invoked, so the SD host is never touched. Non-admin
 * actor; no schema reset. Gated behind the {@code PICTUREBOOK_E2E} env var so an unattended full-suite
 * run does not fire LLM traffic in parallel — a skip reports as Skipped (never as a pass).
 */
public class TestPictureBookListCharactersPb2 extends BaseTest {

	/** Reuse the already-seeded Books universe org (avoids the multi-minute universe seed). */
	private static final String ORG_A = "/Development/World Building";

	@Before
	public void resetSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private OrganizationContext org() {
		return getTestOrganization(ORG_A);
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/** A non-admin, ordinary user. $minLen5 is satisfied by the timestamp suffix. */
	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve non-admin test user " + name, u);
		return u;
	}

	/** A chat config pointed at the live Ollama host in resource.properties. */
	private String ensureChatConfig(BaseRecord user) throws Exception {
		String model = testProperties.getProperty("test.llm.ollama.model");
		String serverUrl = testProperties.getProperty("test.llm.ollama.server");
		assertNotNull("test.llm.ollama.model must be set", model);
		assertNotNull("test.llm.ollama.server must be set", serverUrl);
		String cfgName = "PB2 ListChars " + model + ".chat";
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, cfgName, "~/Chat");
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

	/** Character stub carrying {@code appearance}, which skips the per-character reduce-character LLM call. */
	private Map<String, Object> charStub(String name, String gender, String appearance) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("gender", gender);
		m.put("appearance", appearance);
		return m;
	}

	/**
	 * Resolve the book world's population group path independently of the value the fix persists on the
	 * meta ({@code charsGroupPath}). Goes world → {@code population.path}; re-queries with a fresh,
	 * PBAC-bypassing, uncached search if the in-memory world was loaded shallow (same fallback shape the
	 * production write path uses).
	 */
	private String resolvePopulationPath(BaseRecord user, OlioContext ctx, long orgId) {
		BaseRecord world = ctx.getWorld();
		assertNotNull("PB2 book world must exist (getCreateBookContext built it)", world);
		String popPath = world.get("population.path");
		if (popPath == null || popPath.isBlank()) {
			String worldObjId = world.get(FieldNames.FIELD_OBJECT_ID);
			Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldObjId);
			wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			wq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "population.path" });
			wq.setCache(false);
			BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(wq);
			assertNotNull("World must re-resolve for population.path projection", full);
			popPath = full.get("population.path");
		}
		assertNotNull("World must expose a population.path", popPath);
		assertFalse("population.path must not be blank", popPath.isBlank());
		return popPath;
	}

	/** Count charPerson rows physically present in the group at {@code groupPath} (PBAC-bypass, uncached). */
	private int countCharPersonsInGroup(BaseRecord user, String groupPath, long orgId) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		if (grp == null) return 0;
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	/**
	 * The gate. Extract two characters into a real PB2 book via the 9-arg {@code createFromScenes}, then
	 * assert {@code listCharacters} returns them — the read path must find the characters the write path
	 * actually persisted (the world population group), not the empty legacy sub-group.
	 */
	@Test
	public void pb2_extractedCharacters_areReturnedByListCharacters() throws Exception {
		assumeTrue("Set PICTUREBOOK_E2E to run this live-LLM PB2 gate (single-threaded, no SD)",
			System.getenv("PICTUREBOOK_E2E") != null);

		logger.info("PB2 listCharacters gate START: extract into the world population group via the 9-arg "
			+ "createFromScenes, then prove listCharacters returns the same characters (read path == write path)");

		BaseRecord user = user("pb2ListChars" + System.currentTimeMillis());
		long orgId = orgId(user);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		String chatConfigName = ensureChatConfig(user);

		long ts = System.currentTimeMillis();

		// ── The source work: a short story, kept small so the LLM run is fast. ──────────────────────────
		ParameterList wplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		wplist.parameter(FieldNames.FIELD_NAME, "PB2 ListChars Source " + ts);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, wplist);
		work.set("text", "Mira Vance crossed the empty market square at first light. Her brother Aldous Vance "
			+ "waited by the dry fountain, a satchel over one shoulder. They spoke briefly, then walked north "
			+ "together toward the old gate.");
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull("Source work must be created", createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		// ── The PB2 book (creates its universe/world + population group). ───────────────────────────────
		String slug = "pb2-listchars-" + ts;
		String title = "PB2 ListChars " + ts;
		BaseRecord pb2Book = PbBookUtil.createBook(user, dataPath, slug, title);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookObjectId = pb2Book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("PB2 book must have an objectId", pb2BookObjectId);
		logger.info("PB2 book created slug={} objectId={}", slug, pb2BookObjectId);

		// ── One scene referencing both characters; two stubs carrying appearance (skip reduce-character LLM). ─
		String nameA = "Mira Vance";
		String nameB = "Aldous Vance";
		List<Map<String, Object>> sceneList = new ArrayList<>();
		Map<String, Object> scene0 = new LinkedHashMap<>();
		scene0.put("title", "The Empty Square");
		scene0.put("blurb", "Two siblings meet at the fountain and leave together.");
		scene0.put("setting", "an empty market square at first light");
		scene0.put("action", "walking toward the old gate");
		scene0.put("mood", "quiet");
		scene0.put("sourceText", (String) work.get("text"));
		List<String> sceneChars = new ArrayList<>();
		sceneChars.add(nameA);
		sceneChars.add(nameB);
		scene0.put("characters", sceneChars);
		sceneList.add(scene0);

		List<Map<String, Object>> charDataList = new ArrayList<>();
		charDataList.add(charStub(nameA, "FEMALE", "a wiry young woman in a grey travelling cloak"));
		charDataList.add(charStub(nameB, "MALE", "a tall young man with a leather satchel"));

		String bookName = "PB2 ListChars Book " + ts;

		// ── THE WRITE: 9-arg PB2 overload — this is the overload that re-routes charsGroup into the world
		//    population group, i.e. the only path the reported defect lives on. ──────────────────────────
		BaseRecord meta = PictureBookUtil.createFromScenes(user, workObjectId, chatConfigName, null,
			bookName, sceneList, charDataList, dataPath, pb2BookObjectId);
		assertNotNull("createFromScenes(pb2) must return meta", meta);

		List<Object> failedCharacters = meta.get("failedCharacters");
		assertTrue("No character may fail extraction. failedCharacters=" + failedCharacters,
			failedCharacters == null || failedCharacters.isEmpty());

		// ── INDEPENDENT PROOF the characters were genuinely persisted — resolve the world population group
		//    directly (NOT via meta.charsGroupPath, the value the fix writes), and count charPersons. ────
		OlioContext ctx = PbOlioContextUtil.getCreateBookContext(user, dataPath, slug);
		String populationPath = resolvePopulationPath(user, ctx, orgId);
		int independentCount = countCharPersonsInGroup(user, populationPath, orgId);
		logger.info("Independent population-group count at {} = {}", populationPath, independentCount);
		assertTrue("Extraction must have persisted the characters into the world population group "
			+ "(independent of the read path) — got 0, which would mean extraction itself failed",
			independentCount > 0);

		// ── MECHANISM: the legacy {bookGroupPath}/Characters group the OLD read path used is created but
		//    EMPTY (the characters went to the population group). This is exactly why the old read returned 0. ─
		String legacyCharsPath = "~/Data/PictureBooks/" + bookName + "/Characters";
		int legacyCount = countCharPersonsInGroup(user, legacyCharsPath, orgId);
		logger.info("Legacy read-target count at {} = {}", legacyCharsPath, legacyCount);
		assertEquals("The legacy " + legacyCharsPath + " group must be EMPTY — the PB2 write path re-routed "
			+ "the characters to the population group, so a read that looks here returns 0 (the defect)",
			0, legacyCount);
		assertNotEquals("The PB2 re-route must genuinely have moved the characters elsewhere than the legacy "
			+ "group — otherwise this test degenerated to the 8-arg path and does not exercise the defect",
			legacyCharsPath, populationPath);

		// ── THE READ under test: listCharacters, called exactly as the UI calls it (meta.bookObjectId). It
		//    MUST return the characters the write path persisted — the whole reported symptom. ───────────
		List<Map<String, Object>> listed = PictureBookUtil.listCharacters(user, meta.get("bookObjectId"));
		assertNotNull("listCharacters must not return null", listed);
		logger.info("listCharacters returned {} character(s)", listed.size());

		assertEquals("listCharacters must return exactly the characters the write path persisted into the "
			+ "population group — a mismatch is the read path looking in the wrong group",
			independentCount, listed.size());
		assertTrue("listCharacters must NOT report zero characters after a successful extraction — that is "
			+ "the exact reported symptom ('extracts characters but shows none')", listed.size() > 0);

		Set<String> listedNames = new HashSet<>();
		for (Map<String, Object> e : listed) listedNames.add((String) e.get("name"));
		assertTrue("Extracted character '" + nameA + "' must appear in listCharacters, got " + listedNames,
			listedNames.contains(nameA));
		assertTrue("Extracted character '" + nameB + "' must appear in listCharacters, got " + listedNames,
			listedNames.contains(nameB));

		logger.info("PB2 listCharacters gate PASS: independentCount={} listed={} legacyEmpty={} names={}",
			independentCount, listed.size(), legacyCount, listedNames);
	}
}
