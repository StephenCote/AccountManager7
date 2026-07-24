package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/**
 * TestPictureBookCustom — scaffolding for hand-driven PictureBook pipeline exploration.
 *
 * This is deliberately a SKELETON, not a finished regression test: one test method, five clearly
 * labeled steps (source text -> scene extraction -> character generation -> prompt resolution ->
 * image generation) plus two optional sub-steps (3B apparel, 5B standalone portrait regen), each
 * stubbed with TODOs and commented-out example calls into the real PictureBookUtil API. Fill in /
 * uncomment what you need per step and drop the placeholder assertion once you're asserting real
 * things.
 *
 * Steps 1-3 are get-or-create and REAL (not stubs): each caches its output behind a fixed name
 * (a data.note, or the book group itself) so repeated runs of this test reuse prior LLM/SD work
 * instead of re-paying for it — see getOrCreateCatatoneOpeningWork(), getOrCreateCatatoneScenes(),
 * and getOrCreateCatatoneBook()'s javadocs. Steps 3B, 4, 5, and 5B stay commented-out stubs since
 * they're optional/expensive (SD calls) and something you'll want to drive by hand.
 *
 * Reference for a fully-worked version of the same pipeline: TestPictureBookFull.java's
 * TestCatatoneOpeningScenesRealPromptsAndImages (real .docx source) and
 * TestSceneTaggedApparelSelectsCorrectOutfitPerScene (full generateSceneImage() call + apparel).
 *
 * Live backend required: Ollama (test.llm.ollama.server) for extraction/prompt LLM calls, and
 * Swarm (test.swarm.server) for actual image generation — both already configured in
 * src/test/resources/resource.properties for this module. Never resets the DB schema.
 */
public class TestPictureBookCustom extends BaseTest {

	// ═══════════════════════════════════════════════════════════════════
	// TEST SCENARIO CONFIG — change these to try alternate source content, models, or book
	// setups. Everything else in this file derives from these constants; nothing else should
	// need touching just to point the test at a different story/book/model.
	// ═══════════════════════════════════════════════════════════════════

	// Isolated org path so this doesn't collide with TestPictureBookFull's own fixtures.
	private static final String ORG_PATH = "/Development/PictureBook Custom Tests";
	// Where the chat config + source/cache notes live (must be a path getCreateUser's home resolves).
	private static final String CHAT_PATH = "~/Chat";

	private static final String PB_LLM_MODEL = "qwen3:8b";
	private static int iter = 3;
	private static final String PB_CHAT_CONFIG_NAME = "PictureBook " + PB_LLM_MODEL + " " + iter + ".chat";

	// Source document + the exact substring that marks where Step 1 truncates it (see
	// getOrCreateCatatoneOpeningWork's javadoc for why the cutoff exists at all).
	private static final String SOURCE_DOCUMENT_PATH = "./media/catatone.docx";
	private static final String CATATONE_CUTOFF_ANCHOR = "About the Author";

	// Fixed cache-note names Steps 1-2 key their get-or-create lookups on — bump these (or delete
	// the note) to force a fresh run of the LLM call they front, e.g. after changing PB_LLM_MODEL,
	// SOURCE_DOCUMENT_PATH, or CATATONE_CUTOFF_ANCHOR above.
	private static final String CATATONE_OPENING_WORK_NAME = "catatone-opening-custom " + iter;
	private static final String CATATONE_SCENES_CACHE_NAME = "catatone-custom-scenes-cache " + iter;

	private static final int SCENE_COUNT = 2; // matches the two isolated house scenes
	private static final String BOOK_GENRE = "dystopian sci-fi";
	private static final String CATATONE_BOOK_NAME = "Catatone Custom Book " + iter;
	private static final String BOOK_GROUP_PATH_PREFIX = "~/Data/PictureBooks/"; // mirrors PictureBookUtil's private PICTURE_BOOKS_DIR
	private static final String IMAGE_STYLE = "photograph"; // one of PictureBookUtil.ALLOWED_STYLES

	private OrganizationContext testOrgCtx;
	private BaseRecord testUser;
	private BaseRecord chatConfig;

	private void setupTestContext() {
		testOrgCtx = getTestOrganization(ORG_PATH);
		Factory mf = IOSystem.getActiveContext().getFactory();
		testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbCustomTestUser", testOrgCtx.getOrganizationId());
		assertNotNull("Test user should be created", testUser);

		String ollamaServer = testProperties.getProperty("test.llm.ollama.server");
		assertNotNull("test.llm.ollama.server must be set", ollamaServer);
		chatConfig = getOrCreatePbChatConfig(testUser, ollamaServer);
		assertNotNull("Chat config should be created", chatConfig);
	}

	private BaseRecord getOrCreatePbChatConfig(BaseRecord user, String serverUrl) {
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, PB_CHAT_CONFIG_NAME, CHAT_PATH);
		if (existing != null) return existing;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
			plist.parameter(FieldNames.FIELD_NAME, PB_CHAT_CONFIG_NAME);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("connection", OlioTestUtil.getCreateConnection(user, PB_CHAT_CONFIG_NAME + " Connection", serverUrl, null, 300));
			cfg.set("model", PB_LLM_MODEL);
			cfg.set("stream", false);
			BaseRecord opts = cfg.get("chatOptions");
			if (opts == null) {
				opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
				cfg.set("chatOptions", opts);
			}
			opts.set("think", false);
			opts.set("num_ctx", (16384 * 4));
			opts.set("repeat_penalty", 1.05);
			opts.set("typical_p", 0.0);
			opts.set("temperature", 0.3);
			return IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		} catch (Exception e) {
			logger.error("Failed to create PB chat config: " + e.getMessage());
			return null;
		}
	}

	/**
	 * STEP 1 helper — get-or-create the catatone.docx opening as a reusable, idempotent source
	 * text: Duña and Jideon; scene 1 outside the ramshackle house (carrying Duña to the waiting
	 * cab in the rain), scene 2 inside the house (Varah's cluttered bedroom, the call to Maria) —
	 * dystopian near-future North America. Uses a FIXED name (not timestamped) so repeated runs
	 * reuse the same data.note instead of piling up duplicates — same idiom as
	 * getOrCreatePbChatConfig() above.
	 *
	 * The isolation cutoff (CATATONE_CUTOFF_ANCHOR) matters: extract() only sends the first
	 * PictureBookUtil.MAX_EXTRACTION_TEXT_CHARS chars of whatever text it's given to the LLM, and
	 * pictureBook.extract-scenes' own system prompt spreads scene selection across the WHOLE input
	 * rather than the literal start — without this cutoff, a 2-scene extraction can just as easily
	 * pick a later scene (a third character, "Touvier", shows up almost immediately after) instead
	 * of the two house scenes. Confirmed live 2026-07-23 while fixing KI-31's follow-up.
	 */
	private String getOrCreateCatatoneWork() throws Exception {
		BaseRecord existing = DocumentUtil.getRecord(testUser, ModelNames.MODEL_NOTE, CATATONE_OPENING_WORK_NAME, CHAT_PATH);
		if (existing != null) {
			logger.info("Reusing existing catatone opening work: " + existing.get(FieldNames.FIELD_OBJECT_ID));
			return existing.get(FieldNames.FIELD_OBJECT_ID);
		}

		byte[] fileBytes = FileUtil.getFile(SOURCE_DOCUMENT_PATH);
		assertNotNull("Source document should be readable at " + SOURCE_DOCUMENT_PATH, fileBytes);

		ParameterList docPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
		docPlist.parameter(FieldNames.FIELD_NAME, "catatone-source-" + System.currentTimeMillis() + ".docx");
		BaseRecord docWork = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, docPlist);
		docWork.set(FieldNames.FIELD_CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		ByteModelUtil.setValue(docWork, fileBytes);
		BaseRecord createdDocWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, docWork);
		assertNotNull(createdDocWork);

		String fullText = DocumentUtil.getStringContent(createdDocWork);
		assertNotNull("catatone.docx should extract real text content", fullText);
		int cutIdx = fullText.indexOf(CATATONE_CUTOFF_ANCHOR);
		assertTrue("Expected to find the outside/inside-house scene boundary anchor in the real "
			+ "extracted text — catatone.docx's content may have changed", cutIdx > 0);
		String openingText = fullText.substring(0, cutIdx);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
		plist.parameter(FieldNames.FIELD_NAME, CATATONE_OPENING_WORK_NAME);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", openingText);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull("catatone opening work should be created", createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);
		logger.info("Created catatone opening work " + workObjectId + " (" + openingText.length() + " chars)");
		return workObjectId;
	}

	/**
	 * STEP 2 helper — get-or-create the extracted scene list, cached as JSON on a fixed-name note
	 * so repeated runs skip the {@code pictureBook.extract-scenes} LLM call entirely. Same idiom as
	 * getOrCreateCatatoneOpeningWork() (Step 1) — delete this note (or bump the constant) to force
	 * a fresh extraction, e.g. after changing PB_LLM_MODEL or the cutoff anchor.
	 *
	 * Ux equivalent: sceneExtractor.js's Step 2 panel POSTs to
	 * {@code /rest/picturebook/{workObjectId}/extract-scenes-only} (wraps this exact
	 * PictureBookUtil.extractScenesOnly call) and caches the result client-side in
	 * am7olio.pictureBookState until the user advances to Step 3 — nothing is persisted
	 * server-side until create-from-scenes runs, which is why we roll our own cache note here.
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getOrCreateCatatoneScenes(String workObjectId) throws Exception {
		BaseRecord cached = DocumentUtil.getRecord(testUser, ModelNames.MODEL_NOTE, CATATONE_SCENES_CACHE_NAME, CHAT_PATH);
		if (cached != null) {
			String json = cached.get(FieldNames.FIELD_TEXT);
			List<Map<String, Object>> scenes = JSONUtil.getList(json, Map.class, null);
			logger.info("Reusing " + scenes.size() + " cached catatone scenes (skipped LLM extraction)");
			return scenes;
		}

		PictureBookUtil.ScenesOnlyResult scenesOnly = PictureBookUtil.extractScenesOnly(
			testUser, workObjectId, SCENE_COUNT, chatConfig.get(FieldNames.FIELD_NAME), null);
		assertNotNull("Scene extraction should return a result", scenesOnly);
		assertFalse("Scene extraction should produce at least one scene", scenesOnly.scenes.isEmpty());

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
		plist.parameter(FieldNames.FIELD_NAME, CATATONE_SCENES_CACHE_NAME);
		BaseRecord cacheNote = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		cacheNote.set(FieldNames.FIELD_TEXT, JSONUtil.exportObject(scenesOnly.scenes));
		BaseRecord createdCache = IOSystem.getActiveContext().getAccessPoint().create(testUser, cacheNote);
		assertNotNull("Scene cache note should be created", createdCache);
		logger.info("Extracted and cached " + scenesOnly.scenes.size() + " catatone scenes (chunked=" + scenesOnly.chunked + ")");
		return scenesOnly.scenes;
	}

	/**
	 * STEP 3 helper — get-or-create the book (scene notes + charPerson records), reusing the
	 * existing book group instead of re-running character creation (one {@code pictureBook.extract-
	 * character} LLM call per unique character, plus the ApparelUtil/statistics best-effort wizards)
	 * on every run. Passing an empty charDataList makes createFromScenes derive character stubs
	 * from sceneList's own "characters" arrays and LLM-extract details itself — same as extract().
	 *
	 * Ux equivalent: pictureBook.js's Step 3 ("Manage Characters") POSTs the curated sceneList to
	 * {@code /rest/picturebook/{workObjectId}/create-from-scenes} exactly once per book — the UI's
	 * own guard against this is that the book group already existing routes the wizard straight to
	 * the character list instead of re-POSTing. Mirror that guard here via findPath instead of
	 * makePath so a re-run never creates a second (Catatone Custom Book 2) group.
	 */
	private String getOrCreateCatatoneBook(String workObjectId, List<Map<String, Object>> sceneList) throws Exception {
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		String bookPath = BOOK_GROUP_PATH_PREFIX + CATATONE_BOOK_NAME;
		BaseRecord existingBookGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, bookPath, GroupEnumType.DATA.toString(), orgId);
		if (existingBookGroup != null) {
			String existingBookObjectId = existingBookGroup.get(FieldNames.FIELD_OBJECT_ID);
			logger.info("Reusing existing catatone book: " + existingBookObjectId);
			return existingBookObjectId;
		}

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId,
			chatConfig.get(FieldNames.FIELD_NAME), BOOK_GENRE, CATATONE_BOOK_NAME,
			sceneList, new ArrayList<>(), testProperties.getProperty("test.datagen.path"));
		assertNotNull("createFromScenes should return book meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull("Book meta should carry a bookObjectId", bookObjectId);
		List<Object> failedCharacters = meta.get("failedCharacters");
		if (failedCharacters != null && !failedCharacters.isEmpty()) {
			logger.warn("createFromScenes failedCharacters: " + failedCharacters);
		}
		logger.info("Created catatone book " + bookObjectId);
		return bookObjectId;
	}

	/**
	 * SD CONFIG TEMPLATE — edit these to taste before Step 5. Mirrors the fields
	 * PictureBookUtil.generateSceneImage()'s real callers set (see TestPictureBookFull.java's
	 * SceneGenerationParams usage) — every field here maps directly to something SDUtil/SWUtil
	 * eventually sends to the SD backend.
	 */
	private PictureBookUtil.SceneGenerationParams buildSdConfigTemplate() {
		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		params.chatConfigName = PB_CHAT_CONFIG_NAME;
		params.steps = 40;                  // sampling steps
		params.cfg = 5;                     // CFG scale
		params.hires = false;               // classic (Graphics2D composite + img2img) pipeline only — see KI-10/KI notes on Kontext caveats
		params.isBookOverride = true;       // persist/reuse portraits under the book's Characters/ group (vs. ~/Chat fallback)
		params.style = IMAGE_STYLE;         // one of ALLOWED_STYLES: illustration | photograph | anime | art | digitalart | movie
		params.seed = -1;                   // -1 = random
		params.sdModelName = testProperties.getProperty("test.swarm.model"); // schema default (sdXL_v10VAEFix.safetensors) may not be installed on your Swarm — see resource.properties
		params.sdRefinerModelName = testProperties.getProperty("test.swarm.refinerModel"); // ditto — don't rely on olio.sd.config's schema default here
		params.sdSampler = null;            // e.g. "dpmpp_2m" — null = default
		params.sdScheduler = null;          // e.g. "karras" — null = default
		params.denoisingStrength = -1;      // -1 = pipeline default (classic ~0.85, Kontext ~0.65)
		params.sdLoras = null;              // e.g. Arrays.asList("myLoraName:0.8")
		params.promptTemplateOverride = null; // name of a custom olio.llm.promptTemplate — leave null
		                                       // unless you've confirmed its vars match the operation
		                                       // you're overriding (see KI-31's "cross-purpose template"
		                                       // root cause before setting this to anything non-null)
		return params;
	}

	@Test
	public void TestPictureBookCustomPipeline() throws Exception {
		setupTestContext();

		// ═══════════════════════════════════════════════════════════════════
		// STEP 1 — SOURCE TEXT (get-or-create, real, not a stub)
		// ═══════════════════════════════════════════════════════════════════
		// Reuses the same catatone-opening data.note across runs (see getOrCreateCatatoneOpeningWork's
		// javadoc for why the text is isolated to just the two house scenes). Swap this out for your
		// own get-or-create helper if you want a different source — Pattern A/B below show the two
		// raw building blocks (plain text vs. real document) if you're starting from scratch.

		String workObjectId = getOrCreateCatatoneWork();
		String bookObjectId = null; // set in Step 2

		// -- Pattern A: plain text (fastest to iterate on) --
		// ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		// plist.parameter(FieldNames.FIELD_NAME, "Custom Story " + System.currentTimeMillis());
		// BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		// work.set("text", "YOUR STORY TEXT HERE");
		// BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		// String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		// -- Pattern B: real document (.docx/.pdf) via DocumentUtil — see getOrCreateCatatoneOpeningWork()
		//    above for the full get-or-create + text-isolation version of this pattern.
		// byte[] fileBytes = FileUtil.getFile("./media/YOUR_FILE.docx");
		// ParameterList docPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		// docPlist.parameter(FieldNames.FIELD_NAME, "custom-source-" + System.currentTimeMillis() + ".docx");
		// BaseRecord docWork = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, docPlist);
		// docWork.set(FieldNames.FIELD_CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		// ByteModelUtil.setValue(docWork, fileBytes);
		// BaseRecord createdDocWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, docWork);
		// String workObjectId = createdDocWork.get(FieldNames.FIELD_OBJECT_ID);

		// ═══════════════════════════════════════════════════════════════════
		// STEP 2 — SCENE EXTRACTION (Duña + Jideon; outside-house then inside-house)
		// ═══════════════════════════════════════════════════════════════════
		// Get-or-create, real, not a stub (mirrors Step 1): reuses the cached scene list from
		// CATATONE_SCENES_CACHE_NAME on repeat runs, so the pictureBook.extract-scenes LLM call
		// only fires once. See getOrCreateCatatoneScenes()'s javadoc for the Ux equivalent.

		List<Map<String, Object>> sceneList = getOrCreateCatatoneScenes(workObjectId);

		// ═══════════════════════════════════════════════════════════════════
		// STEP 3 — CHARACTER GENERATION (Duña + Jideon; get-or-create like Steps 1-2)
		// ═══════════════════════════════════════════════════════════════════
		// Get-or-create, real, not a stub: reuses the existing "Catatone Custom Book" group (and
		// therefore its already-created charPerson records) on repeat runs instead of re-running
		// one pictureBook.extract-character LLM call + the ApparelUtil/statistics best-effort
		// wizards per unique character. See getOrCreateCatatoneBook()'s javadoc for the Ux
		// equivalent (pictureBook.js Step 3's create-from-scenes POST).

		bookObjectId = getOrCreateCatatoneBook(workObjectId, sceneList);

		// Inspect what got built — race/alignment/instinct/personality/state are the KI-30
		// random-baseline signal; statistics/store/profile/narrative are the hard-required
		// persisted sub-records checked by TestPictureBookFull's TestCatatoneOpeningScenesReal-
		// PromptsAndImages. Expect two characters here: Duña and Jideon (de Rosa) — extraction
		// sometimes drops the "ñ" (comes back as "Duna"), so match loosely if you assert on name.

		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(),
			(long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query charQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		charQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		charQ.planMost(true);
		BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(charQ);
		for (BaseRecord cp : chars) {
			logger.info("Character: " + cp.get(FieldNames.FIELD_NAME)
				+ " gender=" + cp.get(FieldNames.FIELD_GENDER)
				+ " race=" + cp.get(OlioFieldNames.FIELD_RACE)
				+ " alignment=" + cp.get(FieldNames.FIELD_ALIGNMENT));
		}

		// ═══════════════════════════════════════════════════════════════════
		// STEP 3B — APPAREL GENERATION (optional per-character/per-scene outfits)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: createCharPerson() (called by Step 3 above) already runs ApparelUtil.contextApparel
		// once per character as a best-effort base outfit (see createFromScenes's failedApparel
		// collection) — store.apparel should already have 1 entry per character. Uncomment below
		// to add a SECOND, scene-tagged outfit the way the apparel wizard does.
		//
		// Java path (what the wizard itself calls — see ApparelUtil.contextApparel's use in
		// TestPictureBookFull#TestSceneTaggedApparelSelectsCorrectOutfitPerScene):
		//   BaseRecord octx = null; // OlioContextUtil.getOlioContext(testUser, testProperties.getProperty("test.datagen.path"));
		//   BaseRecord secondApparel = ApparelUtil.contextApparel(octx, cp /* a charPerson from Step 3 */,
		//       /*sceneIndex*/ 1, CivilUtil.ClimateType.TEMPERATE);
		//   secondApparel.setValue(OlioFieldNames.FIELD_IN_USE, true);
		//   IOSystem.getActiveContext().getRecordUtil().createRecord(secondApparel);
		//   IOSystem.getActiveContext().getMemberUtil().member(testUser, cp.get("store"),
		//       OlioFieldNames.FIELD_APPAREL, secondApparel, null, true);
		//   PictureBookUtil.tagApparelSceneIndex(testUser, secondApparel.get(FieldNames.FIELD_OBJECT_ID), 1);
		//
		// Mannequin image (the actual apparel PHOTO, not just outfit data) — see
		// OlioService.reimageApparel for the real REST equivalent. Same sdConfig caveat as Step
		// 5B: olio.sd.config's schema "model" default (sdXL_v10VAEFix.safetensors) is almost
		// certainly not installed on your Swarm — always set model/refinerModel from test.swarm.*.
		//   SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, testProperties.getProperty("test.swarm.server"));
		//   BaseRecord sdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		//   sdConfig.set(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
		//   sdConfig.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
		//   sdConfig.set("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));
		//   String apparelGroupPath = "~/Gallery/Apparel/" + (String) secondApparel.get(FieldNames.FIELD_NAME);
		//   List<BaseRecord> mannequinImages = sdu.generateMannequinImages(testUser, apparelGroupPath, secondApparel, sdConfig, /*hires*/ false, /*seed*/ -1L);
		//
		// Ux path (what actually runs when a human clicks "Tag" in pictureBookCharacters.js's apparel
		// panel, or opens the outfit builder): outfitBuilder.js opens the wizard panel that drives
		// apparel creation (am7olio.outfitBuilderState) and, separately, pictureBookCharacters.js's
		// tagApparel() calls sceneExtractor.js's tagApparelSceneIndex(bookOid, apparelObjectId,
		// sceneIndex) — a thin REST wrapper over PictureBookUtil.tagApparelSceneIndex. There is no
		// picturebook-specific apparel-generation endpoint; it's the same generic charPerson wizard
		// used everywhere else in Olio, just tagged with a sceneIndex afterward.

		// ═══════════════════════════════════════════════════════════════════
		// STEP 4 — PROMPT RESOLUTION (check the prompt BEFORE spending SD time on it)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: resolve+cache landscape/scene prompts, then re-read the scene note's own "text"
		// JSON to see exactly what got cached (and what would actually be sent to SDUtil.txt2img).

		// List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		// List<String> sceneOids = new ArrayList<>();
		// for (Map<String, Object> s : scenes) sceneOids.add((String) s.get("objectId"));
		// PictureBookUtil.prepareSceneImagePrompts(testUser, sceneOids,
		//     chatConfig.get(FieldNames.FIELD_NAME), IMAGE_STYLE, null);
		//
		// for (String sceneOid : sceneOids) {
		//     Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneOid);
		//     sq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		//     sq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "text" });
		//     BaseRecord sceneNote = IOSystem.getActiveContext().getSearch().findRecord(sq);
		//     Map<String, Object> sceneData = JSONUtil.getMap(((String) sceneNote.get("text")).getBytes(), String.class, Object.class);
		//     logger.info("Scene " + sceneOid + " landscapePrompt=[" + sceneData.get("landscapePrompt") + "]");
		//     logger.info("Scene " + sceneOid + " scenePrompt=[" + sceneData.get("scenePrompt") + "]");
		// }

		// ═══════════════════════════════════════════════════════════════════
		// STEP 5 — IMAGE GENERATION (real Swarm call)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: uncomment once Steps 1-4 look right. Uses buildSdConfigTemplate() above.
		// NOTE: this ALREADY does per-character portrait generation as part of the pipeline —
		// generateSceneImage()'s Stage 1 renders SDXL portraits for up to 2 scene characters
		// (using the narrative prompt stored on charPerson from Step 3) before Stage 2's
		// landscape and Stage 3/4's composite — see "Stage 1 complete: N portraits generated" in
		// PictureBookUtil. You do NOT need Step 5B below just to get portraits into the scene.

		// String swarmServer = testProperties.getProperty("test.swarm.server");
		// PictureBookUtil.SceneGenerationParams params = buildSdConfigTemplate();
		// List<String> sceneOids = new ArrayList<>();
		// for (Map<String, Object> s : PictureBookUtil.listScenes(testUser, bookObjectId)) sceneOids.add((String) s.get("objectId"));
		// for (String sceneOid : sceneOids) {
		//     BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", swarmServer);
		//     logger.info("Scene " + sceneOid + " -> imageObjectId=" + (result != null ? result.get("imageObjectId") : "null")
		//         + " prompt=[" + (result != null ? result.get("prompt") : "null") + "]");
		// }

		// ═══════════════════════════════════════════════════════════════════
		// STEP 5B — STANDALONE PORTRAIT REGENERATION (outside the picturebook pipeline)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: only needed if a character's portrait looks wrong and you want to redo it WITHOUT
		// re-running Step 5's whole scene composite. This is a generic Olio charPerson operation,
		// not PictureBook-specific — same call the Ux's "Regenerate Portrait" button makes.
		//
		// Java path (server-side of the REST call below — see OlioService.reimageWithConfig).
		// IMPORTANT: build sdConfig from OlioModelNames.MODEL_SD_CONFIG (olio.sd.config), not the
		// bare ModelNames.MODEL_MODEL — the SD config schema ships its OWN "model" default
		// (sdXL_v10VAEFix.safetensors, see configModel.json) that's almost certainly NOT what's
		// loaded on your Swarm instance. Always set model/refinerModel explicitly from the same
		// test.swarm.* properties buildSdConfigTemplate() uses for Step 5, or this call silently
		// requests a model your server doesn't have.
		//   OlioContext octx = OlioContextUtil.getOlioContext(testUser, testProperties.getProperty("test.datagen.path"));
		//   SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, testProperties.getProperty("test.swarm.server"));
		//   BaseRecord sdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		//   sdConfig.set(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
		//   sdConfig.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
		//   sdConfig.set("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));
		//   sdConfig.set(OlioFieldNames.FIELD_HIRES, false);
		//   sdConfig.set("seed", -1);
		//   sdu.generateSDImages(octx, Arrays.asList(cp /* a charPerson from Step 3 */), sdConfig,
		//       /*setting*/ null, "((DEPRECATED))", /*bodyStyle*/ null, /*verb*/ null, 1, false, /*hires*/ false, /*seed*/ -1L);
		//   BaseRecord newPortrait = cp.get("profile.portrait");
		//
		// Ux path (reimage.js / pictureBookCharacters.js's doReimage() + renderPortraitPanel()):
		//   POST {am7client.base()}/olio/olio.charPerson/{charObjectId}/reimage
		//   body: { model, refinerModel, hires, seed, imageAction, bodyStyle, imageSetting }
		//   — reimage.js's Dialog mutates entity.profile.portrait directly on success; the panel
		//   then just re-renders <PortraitImage objectId={profile.portrait.objectId}/>.

		// Placeholder so this compiles/runs as-is before you fill in the steps above.
		assertTrue("Skeleton test — fill in Steps 1-5 above and replace this with real assertions", true);
	}

}
