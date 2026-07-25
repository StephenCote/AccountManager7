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
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
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
	private static int iter = 1;
	private static final String PB_CHAT_CONFIG_NAME = "PictureBook " + PB_LLM_MODEL + " " + iter + ".chat";

	// Source document + the exact substring that marks where Step 1 truncates it (see
	// getOrCreateCatatoneOpeningWork's javadoc for why the cutoff exists at all).
	private static final String SOURCE_DOCUMENT_PATH = "./media/catatone.docx";
	private static final String CATATONE_CUTOFF_ANCHOR = "About the Author";

	// Fixed cache-note name Step 1 keys its get-or-create lookup on — bump it (or delete the note)
	// to force a fresh run of the LLM call it fronts, e.g. after changing PB_LLM_MODEL,
	// SOURCE_DOCUMENT_PATH, or CATATONE_CUTOFF_ANCHOR above.
	private static final String CATATONE_OPENING_WORK_NAME = "catatone-opening-custom " + iter;
	// Step 2's scene cache lives inside the book's own group (see getOrCreateCatatoneScenes) —
	// dot-prefixed to match the real .pictureBookMeta convention — so the name itself doesn't need
	// an iter suffix; the containing group (CATATONE_BOOK_NAME, which does carry iter) disambiguates.
	private static final String CATATONE_SCENES_CACHE_NAME = ".scenesCache";

	private static final int SCENE_COUNT = 10; // matches the two isolated house scenes
	private static final String BOOK_GENRE = "dystopian sci-fi";
	private static final String CATATONE_BOOK_NAME = "Catatone Custom Book " + iter;
	private static final String BOOK_GROUP_PATH_PREFIX = "~/Data/PictureBooks/"; // mirrors PictureBookUtil's private PICTURE_BOOKS_DIR
	private static final String IMAGE_STYLE = "photograph"; // one of PictureBookUtil.ALLOWED_STYLES
	private static final long MANNEQUIN_SEED = 424242L; // fixed, not -1/random — see Step 3B
	// Where Step 5 (scene composites) and Step 3B (apparel mannequins) write the real generated
	// images for visual inspection — relative to the module dir (AccountManagerObjects7/export).
	private static final String EXPORT_DIR = "./export";

	private OrganizationContext testOrgCtx;
	private BaseRecord testUser;
	private BaseRecord chatConfig;

	private void setupTestContext() {
		testOrgCtx = getTestOrganization(ORG_PATH);
		Factory mf = IOSystem.getActiveContext().getFactory();
		testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbCustomTestUser", testOrgCtx.getOrganizationId());
		assertNotNull("Test user should be created", testUser);

		OlioContext octx = OlioContextUtil.getOlioContext(testUser, testProperties.getProperty("test.datagen.path"));
		
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
	 * getOrCreateCatatoneOpeningWork() (Step 1) — delete this note (or bump {@code iter}) to force
	 * a fresh extraction, e.g. after changing PB_LLM_MODEL or the cutoff anchor.
	 *
	 * Scoped under the BOOK's own group (~/Data/PictureBooks/{CATATONE_BOOK_NAME}/), not ~/Chat.
	 * In the real pipeline, extractScenesOnly() is ephemeral — it persists nothing; the client
	 * holds the scene list in memory until create-from-scenes runs (see javadoc below). This test
	 * needs its OWN durable cache to skip re-paying for the LLM call across runs, but that cache is
	 * test scaffolding, not real pipeline behavior — and it must still respect the real system's
	 * scoping rule that anything book-related lives under that book's group, not a shared bucket
	 * like ~/Chat. A shared bucket also risks exactly the kind of (name, groupId, organizationId)
	 * collisions PictureBookUtil.createSceneNote already has real per-title problems with — putting
	 * test scaffolding in the same shared space would only add to that, not emulate it correctly.
	 * makePath here is idempotent with createFromScenes' internal ensureBookGroup (same path), so
	 * pre-creating the group in Step 2 does not create a second/different book group in Step 3.
	 *
	 * Ux equivalent: sceneExtractor.js's Step 2 panel POSTs to
	 * {@code /rest/picturebook/{workObjectId}/extract-scenes-only} (wraps this exact
	 * PictureBookUtil.extractScenesOnly call) and caches the result client-side in
	 * am7olio.pictureBookState until the user advances to Step 3 — nothing is persisted
	 * server-side until create-from-scenes runs, which is why we roll our own cache note here.
	 */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getOrCreateCatatoneScenes(String workObjectId) throws Exception {
		String bookPath = BOOK_GROUP_PATH_PREFIX + CATATONE_BOOK_NAME;
		BaseRecord cached = DocumentUtil.getRecord(testUser, ModelNames.MODEL_NOTE, CATATONE_SCENES_CACHE_NAME, bookPath);
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
		// Investigate/redo: each entry is a JSON {context,error,rawResponse,failedAt} blob for a
		// chunk/scene response that failed to parse — read it back, fix the JSON by hand, and
		// splice the corrected scene into sceneList before it gets cached below.
		if (!scenesOnly.failedExtractions.isEmpty()) {
			logger.warn("Scene extraction had " + scenesOnly.failedExtractions.size() + " failed/unparseable LLM response(s) — see scenesOnly.failedExtractions for the raw text:");
			for (String failure : scenesOnly.failedExtractions) {
				logger.warn("  " + failure);
			}
		}

		// Idempotent with ensureBookGroup's own makePath call inside createFromScenes (Step 3) —
		// same path, so this doesn't preempt or duplicate the real book group.
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord bookGroup = IOSystem.getActiveContext().getPathUtil().makePath(testUser,
			ModelNames.MODEL_GROUP, bookPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Book group should be creatable ahead of Step 3", bookGroup);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookPath);
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
	 *
	 * IMPORTANT: check for the /Characters SUBGROUP, not just the top-level book group — Step 2's
	 * getOrCreateCatatoneScenes() already pre-creates the top-level book group (to store its own
	 * .scenesCache note) before this method ever runs, so the top-level group's mere existence is
	 * NOT proof createFromScenes has actually run. Only createFromScenes'/extract's ensureSubGroup
	 * call ever creates /Characters — confirmed live 2026-07-24: checking the top-level group alone
	 * caused a fresh iter to skip createFromScenes entirely (false "already exists"), leaving
	 * /Characters never created and NPEing on the very next line that reads it.
	 */
	private String getOrCreateCatatoneBook(String workObjectId, List<Map<String, Object>> sceneList) throws Exception {
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		String bookPath = BOOK_GROUP_PATH_PREFIX + CATATONE_BOOK_NAME;
		BaseRecord existingCharsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, bookPath + "/Characters", GroupEnumType.DATA.toString(), orgId);
		if (existingCharsGroup != null) {
			BaseRecord existingBookGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
				ModelNames.MODEL_GROUP, bookPath, GroupEnumType.DATA.toString(), orgId);
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
		// Investigate/redo: durably persisted on .pictureBookMeta (unlike Step 2's in-memory-only
		// scenesOnly.failedExtractions) — survives past this test run. Read it back later via
		// PictureBookUtil.getBookSdConfig-style loadTypedMeta, or just re-run this test and check
		// the log below.
		List<Object> failedExtractions = meta.get("failedExtractions");
		if (failedExtractions != null && !failedExtractions.isEmpty()) {
			logger.warn("createFromScenes had " + failedExtractions.size() + " failed/unparseable LLM extraction(s), persisted on .pictureBookMeta.failedExtractions:");
			for (Object failure : failedExtractions) {
				logger.warn("  " + failure);
			}
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

	/**
	 * Fetch a generated image by objectId and write it to EXPORT_DIR for visual inspection, logging
	 * its real decoded pixel dimensions (so a mismatch vs. the requested canvas is visible — the B2
	 * "~3000x1000 merge" investigation). Uses the canonical SDUtil.getDataBytes read path; best-effort
	 * — an export hiccup logs a warning and never fails the test.
	 */
	private void exportImage(String imageObjectId, String label) {
		if (imageObjectId == null) return;
		try {
			new java.io.File(EXPORT_DIR).mkdirs();
			Query iq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, imageObjectId);
			iq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			iq.planMost(true);
			BaseRecord img = IOSystem.getActiveContext().getAccessPoint().find(testUser, iq);
			if (img == null) { logger.warn("exportImage: image not found for " + label + " (" + imageObjectId + ")"); return; }
			byte[] bytes = SDUtil.getDataBytes(img);
			if (bytes == null || bytes.length == 0) { logger.warn("exportImage: no bytes for " + label + " (" + imageObjectId + ")"); return; }
			int w = -1, h = -1;
			try {
				java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
				if (decoded != null) { w = decoded.getWidth(); h = decoded.getHeight(); }
			} catch (Exception de) { logger.warn("exportImage: could not decode " + label + ": " + de.getMessage()); }
			String safe = label.replaceAll("[^a-zA-Z0-9._-]", "_");
			String path = EXPORT_DIR + "/" + safe + ".png";
			boolean ok = FileUtil.emitFile(path, bytes);
			logger.info("Exported " + label + " -> " + path + " (" + bytes.length + " bytes, decoded " + w + "x" + h + ") ok=" + ok);
		} catch (Exception e) {
			logger.warn("exportImage failed for " + label + ": " + e.getMessage());
		}
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
		assertTrue("At least one character should have been created (check createFromScenes' "
			+ "failedCharacters log above if this fails — individual character creation can fail "
			+ "without failing the whole book)", chars.length > 0);
		for (BaseRecord cp : chars) {
			logger.info("Character: " + cp.get(FieldNames.FIELD_NAME)
				+ " gender=" + cp.get(FieldNames.FIELD_GENDER)
				+ " race=" + cp.get(OlioFieldNames.FIELD_RACE)
				+ " alignment=" + cp.get(FieldNames.FIELD_ALIGNMENT));
		}

		// ═══════════════════════════════════════════════════════════════════
		// STEP 3B — APPAREL GENERATION (verify the LLM-guessed base outfit + render it)
		// ═══════════════════════════════════════════════════════════════════
		// createCharPerson() (called by Step 3 above) already ran PictureBookUtil.
		// generateApparelFromCharData() once per character — LLM-guessed from the extracted
		// appearance/clothing_style/outfit_notes/role, falling back to ApparelUtil.randomApparel
		// when the LLM has nothing to go on or its guess doesn't match the wardrobe catalog. The
		// LLM request + resulting outfit are already logged by that call during Step 3 above; this
		// step just confirms the outfit landed on the character and renders it as a real mannequin
		// image via SDUtil.generateMannequinImages (same call OlioService.reimageApparel makes).

		BaseRecord apparelChar = chars[0];
		IOSystem.getActiveContext().getReader().populate(apparelChar, new String[] { FieldNames.FIELD_STORE });
		BaseRecord apparelStore = apparelChar.get(FieldNames.FIELD_STORE);
		List<BaseRecord> apparelList = apparelStore.get(OlioFieldNames.FIELD_APPAREL);
		assertNotNull("Character should have a store.apparel list", apparelList);
		assertFalse("Character should have at least one apparel entry from Step 3's createCharPerson", apparelList.isEmpty());
		BaseRecord apparel = apparelList.get(0);
		IOSystem.getActiveContext().getReader().populate(apparel, new String[] { OlioFieldNames.FIELD_WEARABLES });
		List<BaseRecord> apparelWearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		logger.info("Step 3B: " + apparelChar.get(FieldNames.FIELD_NAME) + "'s apparel '" + apparel.get(FieldNames.FIELD_NAME)
			+ "' has " + (apparelWearables != null ? apparelWearables.size() : 0) + " wearable(s)");

		// olio.sd.config's schema "model" default (sdXL_v10VAEFix.safetensors) is almost certainly
		// not installed on your Swarm — always set model/refinerModel from test.swarm.* (same as
		// buildSdConfigTemplate() for Step 5).
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);
		BaseRecord apparelSdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		apparelSdConfig.set(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
		apparelSdConfig.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
		apparelSdConfig.set("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));

		// Fixed (not -1/random) seed: repeated runs against the same apparel should render the same
		// mannequin image, matching this test's overall get-or-create/idempotent design.
		SDUtil apparelSdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		String apparelGroupPath = "~/Gallery/Apparel/" + (String) apparel.get(FieldNames.FIELD_NAME);
		List<BaseRecord> mannequinImages = apparelSdu.generateMannequinImages(testUser, apparelGroupPath, apparel, apparelSdConfig, /*hires*/ false, MANNEQUIN_SEED);
		assertNotNull("generateMannequinImages should return a result list", mannequinImages);
		logger.info("Step 3B: generated " + mannequinImages.size() + " mannequin image(s) for " + apparel.get(FieldNames.FIELD_NAME)
			+ " (seed=" + MANNEQUIN_SEED + "): " + mannequinImages.stream().map(i -> (String) i.get(FieldNames.FIELD_OBJECT_ID)).collect(java.util.stream.Collectors.toList()));
		int mannequinNum = 0;
		for (BaseRecord mimg : mannequinImages) {
			exportImage(mimg.get(FieldNames.FIELD_OBJECT_ID), "mannequin_" + apparel.get(FieldNames.FIELD_NAME) + "_" + (++mannequinNum));
		}

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

		// Enabled: real scene image generation against Swarm. generateSceneImage runs the full pipeline
		// per scene (Stage 0 prompt resolve -> Stage 1 portraits -> Stage 2 landscape -> Stage 3/4
		// composite). Each final composite is exported to EXPORT_DIR for visual inspection, and its
		// decoded dimensions are logged — compare against PictureBookUtil's "requesting composite canvas
		// at WxH" log line to localize the B2 oversized-merge issue. swarmServer is reused from Step 3B.
		PictureBookUtil.SceneGenerationParams params = buildSdConfigTemplate();
		List<Map<String, Object>> scenesForImages = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertFalse("Book should have at least one scene to render", scenesForImages.isEmpty());
		int sceneNum = 0;
		for (Map<String, Object> s : scenesForImages) {
			String sceneOid = (String) s.get("objectId");
			if (sceneOid == null) continue;
			sceneNum++;
			long t0 = System.currentTimeMillis();
			BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", swarmServer);
			long elapsed = System.currentTimeMillis() - t0;
			assertNotNull("Scene " + sceneNum + " generateSceneImage should return a result", result);
			String imageObjectId = result.get("imageObjectId");
			logger.info("Step 5: scene " + sceneNum + " (" + sceneOid + ") -> imageObjectId=" + imageObjectId
				+ " seed=" + result.get("seed") + " (" + elapsed + "ms) prompt=[" + result.get("prompt") + "]");
			assertNotNull("Scene " + sceneNum + " should produce a real generated image objectId", imageObjectId);
			exportImage(imageObjectId, "scene_" + sceneNum + "_" + sceneOid);
		}

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

		logger.info("TestPictureBookCustomPipeline complete — Steps 1-3 (content) + 3B (apparel/mannequin) "
			+ "+ 5 (scene images) ran; generated images exported to " + EXPORT_DIR + " for visual inspection.");
	}

}
