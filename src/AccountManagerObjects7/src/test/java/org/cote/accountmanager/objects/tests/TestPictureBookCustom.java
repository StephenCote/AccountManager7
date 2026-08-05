package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.ColorUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
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
import org.cote.accountmanager.util.AttributeUtil;
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

	private static final String PB_LLM_MODEL = "gpt-oss:120b";//"qwen3:8b";
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

	private static final int SCENE_COUNT = 3; // matches the two isolated house scenes
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
	// Kept as a field (not just a setup local) because the custom-character helpers below —
	// setColorByNameOnCharacter (needs ctx.getUniverse().colors) and imprintCustomCharacter
	// (ApparelUtil.constructApparel + ctx.getOlioUser()) — need the same OlioContext this pipeline runs on.
	private OlioContext testOlioCtx;

	private void setupTestContext() {
		testOrgCtx = getTestOrganization(ORG_PATH);
		Factory mf = IOSystem.getActiveContext().getFactory();
		testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbCustomTestUser", testOrgCtx.getOrganizationId());
		assertNotNull("Test user should be created", testUser);

		testOlioCtx = OlioContextUtil.getOlioContext(testUser, testProperties.getProperty("test.datagen.path"));
		
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
	 * Build the Step 5 SceneGenerationParams. Under the config-driven design there is ONE common
	 * olio.sd.config (style + composition + generation params) — {@link #buildCommonSdConfig()} —
	 * that drives portraits, landscape, and scene consistently via SDUtil.getSDConfigPrompt. The
	 * flattened single-word style / per-field scalars are gone; everything image-related lives on the
	 * config record. Per-scene overrides (a sparse olio.sd.config delta) would go on params.sdConfigOverride.
	 */
	private PictureBookUtil.SceneGenerationParams buildSdConfigTemplate() {
		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		params.chatConfigName = PB_CHAT_CONFIG_NAME;
		params.isBookOverride = true;       // persist/reuse portraits under the book's Characters/ group
		params.promptTemplateOverride = null;
		params.sdConfig = buildCommonSdConfig();
		return params;
	}

	/**
	 * The book's ONE common olio.sd.config. SDUtil.randomSDConfig() populates a full style (style +
	 * its detail fields from the shared pools) so getSDConfigPrompt yields a rich, cohesive style
	 * across every image; we then pin it to this book's intent: a fixed canonical style (IMAGE_STYLE —
	 * illustration/custom no longer exist), the classic Graphics2D+img2img pipeline (useKontext=false /
	 * hires=false, the deliberate picture-book default since Kontext doesn't reliably preserve
	 * character likeness), and the test's Swarm model/refiner. The single source of truth for style —
	 * no separate styleClause.
	 */
	private BaseRecord buildCommonSdConfig() {
		BaseRecord cfg = SDUtil.randomSDConfig();
		cfg.setValue("style", IMAGE_STYLE);
		SDUtil.fillStyleDefaults(cfg);              // repopulate detail fields for the pinned style
		cfg.setValue("useKontext", false);          // classic pipeline (likeness-safe)
		cfg.setValue("hires", false);
		cfg.setValue("cfg", 5);
		cfg.setValue("steps", 40);
		cfg.setValue("seed", -1);
		cfg.setValue(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
		cfg.setValue(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
		cfg.setValue("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));
		return cfg;
	}

	/**
	 * Fetch a generated image by objectId and write it to EXPORT_DIR for visual inspection, logging
	 * its real decoded pixel dimensions (so a mismatch vs. the requested canvas is visible — the B2
	 * "~3000x1000 merge" investigation). Uses the canonical SDUtil.getDataBytes read path; best-effort
	 * — an export hiccup logs a warning and never fails the test.
	 */
	private void exportImage(String imageObjectId, String label) {
		if (imageObjectId == null) {
			logger.warn("exportImage: null objectId for '" + label + "' — nothing to export (the image "
				+ "record carries no objectId; likely a partial foreign-ref portrait, or generation produced no image)");
			return;
		}
		try {
			new java.io.File(EXPORT_DIR).mkdirs();
			Query iq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, imageObjectId);
			iq.planMost(true);
			// Read via the search path, NOT accessPoint.find(testUser, ...). Portrait images are created
			// by generateSDImages with octx.getOlioUser() as owner in the world gallery group, so a
			// PBAC-enforced find as testUser returns null ("image not found") — testUser isn't the owner
			// (KI-35-class). objectId is globally unique, so an owner/PBAC-free lookup by it is correct for
			// a test export helper. (Mannequin/scene images are testUser-owned and also resolve here.)
			BaseRecord img = IOSystem.getActiveContext().getSearch().findRecord(iq);
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

	// ═══════════════════════════════════════════════════════════════════
	// CUSTOM-CHARACTER HELPERS (a/b/c) — example stubs for hand-tuning the extracted characters
	// before Step 4/5 image generation. Modeled on OlioTestUtil's Duke/Laurel CharacterPrint +
	// getImprintedCharacter(), which "imprint" fixed traits/outfit over an existing population
	// member. The difference here: Step 3 has ALREADY created a charPerson for this book, so these
	// operate on that existing record in place — no gendered-random population pick. Wire them into
	// Step 3 via the commented example there once you know which characters to pin down.
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * Helper A — pick one character out of a list (e.g. Step 3's charPerson[] results) by a LOOSE,
	 * case-insensitive name pattern (regex, matched with find() so a bare substring works too).
	 * Mirrors the name filter at the top of OlioTestUtil.getImprintedCharacter
	 * ({@code pop.stream().filter(name equals ...)}), but fuzzy on purpose so it survives the
	 * "Duña" -> "Duna" diacritic drop Step 3 warns about. Returns the first match, or null.
	 *
	 * Example:
	 *   BaseRecord duna   = pickCharacterByName(Arrays.asList(chars), "du.?a");   // Duña or Duna
	 *   BaseRecord jideon = pickCharacterByName(Arrays.asList(chars), "jideon");
	 */
	private BaseRecord pickCharacterByName(List<BaseRecord> characters, String namePattern) {
		if (characters == null || namePattern == null) return null;
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(namePattern, java.util.regex.Pattern.CASE_INSENSITIVE);
		return characters.stream()
			.filter(c -> {
				String n = c.get(FieldNames.FIELD_NAME);
				return n != null && p.matcher(n).find();
			})
			.findFirst().orElse(null);
	}

	/**
	 * Helper B — resolve a free-text color NAME to a shared-library {@code data.color} record via
	 * ColorUtil.getColorByName (case-insensitive match against the world's colors group) and set it
	 * as a FOREIGN reference on a charPerson color field — hairColor or eyeColor (both are
	 * {@code data.color} foreign refs on olio.charPerson; see charPersonModel.json). Applies it the
	 * way OlioTestUtil.getLaurelPrint does — as a {@code {id:N}} foreign ref inside an importRecord-
	 * sourced person patch (Laurel's own person JSON hardcodes {@code hairColor: {id:181}} /
	 * {@code eyeColor:{id: 291}}; here we look the id up by name instead) — so the patch touches
	 * only this one field and never re-persists a full planMost graph. Returns true if the color
	 * resolved and was patched; false (field left unchanged) if the name matched nothing.
	 *
	 * Example:
	 *   setColorByNameOnCharacter(duna, OlioFieldNames.FIELD_HAIR_COLOR, "Auburn");
	 *   setColorByNameOnCharacter(duna, OlioFieldNames.FIELD_EYE_COLOR,  "Hazel");
	 */
	private boolean setColorByNameOnCharacter(BaseRecord charPerson, String colorField, String colorName) {
		if (charPerson == null) return false;
		BaseRecord color = ColorUtil.getColorByName(testOlioCtx, colorName);
		if (color == null) {
			logger.warn("No color named '" + colorName + "' in the shared color library — leaving " + colorField + " unchanged");
			return false;
		}
		// Set the color exactly the way OlioTestUtil.getLaurelPrint does — a {id:N} foreign ref
		// carried inside a person-imprint patch sourced from importRecord (Laurel's person JSON is
		// "{... hairColor: {id:181}, ... eyeColor:{id: 291}, ...}"). importRecord yields a source
		// with ONLY the field(s) named in the JSON, so RecordUtil.patch touches just this one color
		// field — unlike a bare newInstance(model), which instantiates EVERY field with its default
		// (name=null, birthDate epoch, bmi, bodyShape=RECTANGLE, ...) and, copied wholesale onto the
		// target, clobbers the real name with null and trips the name \S validation on write.
		long colorId = color.get(FieldNames.FIELD_ID);
		String personJson = "{" + colorField + ": {id:" + colorId + "}}";
		IOSystem.getActiveContext().getRecordUtil().patch(
			RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_PERSON, personJson), charPerson);
		logger.info("Set " + colorField + "='" + color.get(FieldNames.FIELD_NAME) + "' (id " + colorId + ") on " + charPerson.get(FieldNames.FIELD_NAME));
		return true;
	}

	/**
	 * Helper C — imprint custom properties + a custom outfit over an EXISTING charPerson, from the
	 * same plain-string formats OlioTestUtil's Duke/Laurel prints use. This is the picturebook
	 * analogue of getImprintedCharacter(): that method finds/derives a population member and stamps
	 * a CharacterPrint over it; here the target is the charPerson Step 3 already built, so we
	 * imprint in place (any null argument is skipped).
	 *
	 * - personJson / statisticsJson / personalityJson: partial-record JSON (any subset of fields),
	 *   patched onto the charPerson and its statistics/personality sub-records. Statistics &
	 *   personality patch FULL (true) because computed/derived fields (athleticism, willpower, etc.)
	 *   depend on several inputs and won't recompute from a fields-only copy — the same reason
	 *   getImprintedCharacter passes full=true there.
	 * - outfitCsv: comma-separated wearable spec (getImprintedCharacter's outfit branch), e.g.
	 *     "camisole,underwear,thigh-high heeled boots,amulet,jewelry:piercing:7:f:ear"  (Laurel's outfit)
	 *   built via ApparelUtil.constructApparel and swapped in as the character's sole in-use apparel.
	 *
	 * Example (Duke-style villain, imprinted over whatever character matched Step 3):
	 *   imprintCustomCharacter(villain,
	 *       "{alignment:\"CHAOTICEVIL\",trades:[\"serial killer\"]}",
	 *       "{physicalStrength:17,agility:17,intelligence:18,perception:19,charisma:12}",
	 *       "{psychopathy:0.9,narcissism:0.65}",
	 *       "trenchcoat,slacks,dress shirt,leather gloves");
	 */
	private void imprintCustomCharacter(BaseRecord charPerson, String personJson, String statisticsJson,
			String personalityJson, String outfitCsv) {
		if (charPerson == null) return;

		// --- Custom outfit from a CSV string (mirrors getImprintedCharacter's outfit branch) ---
		if (outfitCsv != null && !outfitCsv.trim().isEmpty()) {
			String[] outfit = outfitCsv.split(",");
			
			BaseRecord apparel = ApparelUtil.constructApparel(testOlioCtx, 0L, charPerson, outfit);
			apparel.setValue(OlioFieldNames.FIELD_IN_USE, true);
			List<BaseRecord> wearl = apparel.get(OlioFieldNames.FIELD_WEARABLES);
			if (wearl != null) wearl.forEach(w -> w.setValue(OlioFieldNames.FIELD_IN_USE, true));
			IOSystem.getActiveContext().getRecordUtil().createRecord(apparel);
			BaseRecord store = charPerson.get(FieldNames.FIELD_STORE);
			List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
			for (BaseRecord a : appl) {
				IOSystem.getActiveContext().getMemberUtil().member(testOlioCtx.getOlioUser(), store, OlioFieldNames.FIELD_APPAREL, a, null, false);
			}
			appl.clear();
			appl.add(apparel);
			IOSystem.getActiveContext().getMemberUtil().member(testOlioCtx.getOlioUser(), store, OlioFieldNames.FIELD_APPAREL, apparel, null, true);
		}

		// --- Custom stats / personality / person from partial-record JSON (patched like the prints) ---
		if (statisticsJson != null) {
			IOSystem.getActiveContext().getRecordUtil().patch(
				RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_STATISTICS, statisticsJson),
				charPerson.get(OlioFieldNames.FIELD_STATISTICS), true);
		}
		if (personalityJson != null) {
			IOSystem.getActiveContext().getRecordUtil().patch(
				RecordFactory.importRecord(ModelNames.MODEL_PERSONALITY, personalityJson),
				charPerson.get(FieldNames.FIELD_PERSONALITY), true);
		}
		if (personJson != null) {
			IOSystem.getActiveContext().getRecordUtil().patch(
				RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_PERSON, personJson), charPerson);
		}
		logger.info("Imprinted custom properties over " + charPerson.get(FieldNames.FIELD_NAME));
	}

	/**
	 * Look up any seed previously saved for this character's portrait, so a regenerate can reproduce
	 * the same face. SDUtil records the actual seed it used as a "seed" attribute on the generated
	 * image data record (AttributeUtil.addAttribute(data, "seed", seedl) — SDUtil.java), and a
	 * charPerson's portrait IS such a record (profile.portrait). Returns that seed if one is stored
	 * and > 0, else -1 (random) — same convention as PictureBookUtil.extractSeedFromImage. Re-loads
	 * the portrait with planMost(true) so its REFERENCED attributes are actually populated (a
	 * minimally-projected portrait carries an empty attributes list and would just yield -1).
	 */
	private int savedPortraitSeed(BaseRecord charPerson) {
		try {
			BaseRecord portrait = charPerson.get("profile.portrait");
			if (portrait == null) return -1;
			String portraitOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
			if (portraitOid == null) return -1;
			Query pq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, portraitOid);
			pq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			pq.planMost(true);
			BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(testUser, pq);
			if (full == null) return -1;
			int seed = AttributeUtil.getAttributeValue(full, "seed", -1);
			return seed > 0 ? seed : -1;
		} catch (Exception e) {
			logger.warn("savedPortraitSeed: could not read prior seed for " + charPerson.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			return -1;
		}
	}

	private void generatePortrait(BaseRecord charPerson) {
		generatePortrait(charPerson, false);
	}
	
	private void generatePortrait(BaseRecord charPerson, boolean random) {
		try {
			OlioContext octx = OlioContextUtil.getOlioContext(testUser, testProperties.getProperty("test.datagen.path"));
			SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, testProperties.getProperty("test.swarm.server"));
			BaseRecord sdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			sdConfig.set(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
			sdConfig.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
			//sdConfig.set("negativePrompt", NarrativeUtil.getSDNegativePrompt(charPerson));
			sdConfig.set(OlioFieldNames.FIELD_HIRES, true);
			// Reuse any seed previously saved on this character's portrait (SDUtil persists the seed
			// it used as a "seed" attribute on the generated image), so a regenerate reproduces the
			// same face; -1 = random when the character has no prior portrait/seed yet. On the SWARM
			// path the seed is taken from the sdConfig "seed" field (SWUtil.newTxt2Img honors
			// cfg.get("seed") when > 0; its own seed parameter is unused) — the generateSDImages seed
			// arg below is passed to match only so the AUTO1111 path stays consistent.
			int seed = savedPortraitSeed(charPerson);
			sdConfig.set("seed", (random ? -1 : seed));
			logger.info("****** generatePortrait: " + charPerson.get(FieldNames.FIELD_NAME) + " using seed " + seed);
			logger.info(sdConfig.toFullString());
			sdu.generateSDImages(octx, Arrays.asList(charPerson), sdConfig, null, "((DEPRECATED))", /*body*/ null, /*verb*/ null, 1, false, /*hires*/ true, /*seed*/ seed);
		}
		catch(FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
		// Resolve the portrait to export. generateSDImages only replaces profile.portrait when it
		// actually produced an image (bl.size() > 0) — with SD down it produces none, leaving whatever
		// was loaded at Step 3, which is a PARTIAL foreign ref (id only, no objectId). exportImage keys
		// off objectId, so resolve the full record by id when objectId is absent, and export the
		// character's existing portrait rather than silently doing nothing.
		BaseRecord newPortrait = charPerson.get("profile.portrait");
		if (newPortrait == null) {
			logger.warn("generatePortrait: no portrait on " + charPerson.get(FieldNames.FIELD_NAME)
				+ " (generateSDImages set no profile.portrait — SD backend returned no image?) — nothing to export");
			return;
		}
		String portraitOid = newPortrait.get(FieldNames.FIELD_OBJECT_ID);
		if (portraitOid == null) {
			Long portraitId = newPortrait.get(FieldNames.FIELD_ID);
			if (portraitId != null && portraitId > 0L) {
				Query pq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, portraitId);
				pq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				pq.planMost(true);
				BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(testUser, pq);
				if (full != null) portraitOid = full.get(FieldNames.FIELD_OBJECT_ID);
			}
		}
		if (portraitOid == null) {
			logger.warn("generatePortrait: could not resolve a portrait objectId for "
				+ charPerson.get(FieldNames.FIELD_NAME) + " — nothing to export");
			return;
		}
		logger.info("generatePortrait: exporting portrait " + portraitOid + " for " + charPerson.get(FieldNames.FIELD_NAME));
		exportImage(portraitOid, charPerson.get(FieldNames.FIELD_NAME) + " " + portraitOid);
	}
	
	private void generateApparelImage(BaseRecord charPerson) {
		if (charPerson == null) return;
try {
		IOSystem.getActiveContext().getReader().populate(charPerson, new String[] { FieldNames.FIELD_STORE });
		BaseRecord apparelStore = charPerson.get(FieldNames.FIELD_STORE);
		List<BaseRecord> apparelList = apparelStore.get(OlioFieldNames.FIELD_APPAREL);
		assertNotNull("Character should have a store.apparel list", apparelList);
		assertFalse("Character should have at least one apparel entry", apparelList.isEmpty());

		BaseRecord apparel = apparelList.get(apparelList.size() - 1); // last apparel is the one we just imprinted
		IOSystem.getActiveContext().getReader().populate(apparel, new String[] { OlioFieldNames.FIELD_WEARABLES });
		List<BaseRecord> apparelWearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);

		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);
		BaseRecord apparelSdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		apparelSdConfig.set(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
		apparelSdConfig.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
		apparelSdConfig.set("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));

		SDUtil apparelSdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		String apparelGroupPath = "~/Gallery/Apparel/" + (String) apparel.get(FieldNames.FIELD_NAME);
		List<BaseRecord> mannequinImages = apparelSdu.generateMannequinImages(testUser, apparelGroupPath, apparel, apparelSdConfig, /*hires*/ false, MANNEQUIN_SEED);
		assertNotNull("generateMannequinImages should return a result list", mannequinImages);
		logger.info("generated " + mannequinImages.size() + " mannequin image(s) for " + apparel.get(FieldNames.FIELD_NAME)
			+ " (seed=" + MANNEQUIN_SEED + "): " + mannequinImages.stream().map(i -> (String) i.get(FieldNames.FIELD_OBJECT_ID)).collect(java.util.stream.Collectors.toList()));
		int mannequinNum = 0;
		for (BaseRecord mimg : mannequinImages) {
			exportImage(mimg.get(FieldNames.FIELD_OBJECT_ID), "mannequin_" + apparel.get(FieldNames.FIELD_NAME) + "_" + (++mannequinNum));
		}
}
catch(FieldException | ValueException | ModelNotFoundException e) {
	logger.error(e.getMessage());
	e.printStackTrace();
}
	}



	/**
	 * Utility — DRESS a character UP to (and including) a target wear level. Sets inuse=true on every
	 * wearable of the character's active apparel whose level is <= the target (and marks the apparel
	 * itself worn). Mirrors Olio's Dress action (Dress.java executeAction: wl <= cwl -> inuse=true),
	 * minus the action-result/roll/interaction machinery — a direct self-dress for test setup. Wear
	 * levels run low->high: NONE(0)..ON(3), BASE(4), ACCENT(5), SUIT(6), GARNITURE(7), ACCESSORY(8),
	 * OVER(9), OUTER(10).. (WearLevelEnumType). Persists each changed wearable with a minimal inuse
	 * patch via persistInUse (utility writer path, bypasses AccessPoint/PBAC — same effect as the
	 * Dress action's Queue.queueUpdate, and not blocked by the Olio-owned-wearable write issue KI-35).
	 * Returns true if anything actually changed — a wearable that was newly put on (a wearable already
	 * at/below the level and already worn is not a change), or the apparel itself being activated.
	 *
	 * Example: dressToLevel(duna, WearLevelEnumType.OUTER);   // fully clothed, coat and all
	 */
	private boolean dressToLevel(BaseRecord charPerson, WearLevelEnumType level) {
		if (charPerson == null || level == null || level == WearLevelEnumType.UNKNOWN) return false;
		IOSystem.getActiveContext().getReader().populate(charPerson, new String[] { FieldNames.FIELD_STORE });
		boolean changed = false;
		BaseRecord app = ApparelUtil.getWearingApparel(charPerson);
		if (app == null) {
			// Nothing marked in-use yet — turn the most-recently-added outfit "on" so dress-up has
			// something to work with (imprintCustomCharacter appends its apparel to store.apparel).
			BaseRecord store = charPerson.get(FieldNames.FIELD_STORE);
			List<BaseRecord> appl = (store != null) ? store.get(OlioFieldNames.FIELD_APPAREL) : null;
			if (appl == null || appl.isEmpty()) {
				logger.warn("dressToLevel: " + charPerson.get(FieldNames.FIELD_NAME) + " has no apparel to dress");
				return false;
			}
			app = appl.get(appl.size() - 1);
			persistInUse(app, true);
			changed = true;
		}
		IOSystem.getActiveContext().getReader().populate(app, new String[] { OlioFieldNames.FIELD_WEARABLES });
		int cwl = WearLevelEnumType.valueOf(level);
		int worn = 0;
		List<BaseRecord> wearl = app.get(OlioFieldNames.FIELD_WEARABLES);
		if (wearl != null) {
			for (BaseRecord w : wearl) {
				WearLevelEnumType wlvl = w.getEnum(OlioFieldNames.FIELD_LEVEL);
				if (wlvl == null) continue;
				if (WearLevelEnumType.valueOf(wlvl) <= cwl) {
					Boolean cur = w.get(OlioFieldNames.FIELD_IN_USE);
					if (cur != null && cur) continue; // already worn — no change
					logger.info("  wear: " + NarrativeUtil.describeWearable(w));
					persistInUse(w, true);
					worn++;
					changed = true;
				}
			}
		}
		logger.info("dressToLevel: " + charPerson.get(FieldNames.FIELD_NAME) + " dressed up to " + level + " (" + worn + " wearable(s) newly worn)");
		return changed;
	}

	/**
	 * Utility — UNDRESS a character DOWN to a target wear level. Sets inuse=false on every currently-
	 * worn wearable whose level is > the target, leaving everything at or below it on. Mirrors Olio's
	 * Undress action (Undress.java executeAction: wl > cwl -> inuse=false), minus the action-result/
	 * roll/interaction machinery. E.g. undressToLevel(c, WearLevelEnumType.BASE) strips everything
	 * above the base layer (suit, accents, coat, jewelry), leaving just the base garments. Persists via
	 * the same minimal-inuse-patch path as dressToLevel (see its KI-35 note). Returns true if at least
	 * one wearable was actually removed (getWearing only yields currently-worn items, so every strip is
	 * a real change) — false if nothing was above the level / nothing was worn.
	 *
	 * Example: undressToLevel(duna, WearLevelEnumType.BASE);  // down to base garments
	 */
	private boolean undressToLevel(BaseRecord charPerson, WearLevelEnumType level) {
		if (charPerson == null || level == null || level == WearLevelEnumType.UNKNOWN) return false;
		IOSystem.getActiveContext().getReader().populate(charPerson, new String[] { FieldNames.FIELD_STORE });
		BaseRecord app = ApparelUtil.getWearingApparel(charPerson);
		if (app == null) {
			logger.warn("undressToLevel: " + charPerson.get(FieldNames.FIELD_NAME) + " is not wearing any apparel");
			return false;
		}
		IOSystem.getActiveContext().getReader().populate(app, new String[] { OlioFieldNames.FIELD_WEARABLES });
		int cwl = WearLevelEnumType.valueOf(level);
		int stripped = 0;
		for (BaseRecord w : ApparelUtil.getWearing(charPerson)) {
			WearLevelEnumType wlvl = w.getEnum(OlioFieldNames.FIELD_LEVEL);
			if (wlvl == null) continue;
			if (WearLevelEnumType.valueOf(wlvl) > cwl) {
				logger.info("  strip: " + NarrativeUtil.describeWearable(w));
				persistInUse(w, false);
				stripped++;
			}
		}
		logger.info("undressToLevel: " + charPerson.get(FieldNames.FIELD_NAME) + " undressed down to " + level + " (" + stripped + " wearable(s) removed)");
		return stripped > 0;
	}

	/**
	 * Persist a single wearable/apparel record's inuse flag with a minimal identity + field patch.
	 * Scopes the patch source to just objectId + inuse (see setColorByNameOnCharacter's note on why the
	 * bare newInstance(model) overload is wrong here) and writes via RecordUtil.patch — the utility
	 * path that bypasses AccessPoint/PBAC, same effect as the Dress/Undress actions' Queue.queueUpdate.
	 */
	private void persistInUse(BaseRecord wearableOrApparel, boolean value) {
		try {
			BaseRecord patch = RecordFactory.newInstance(wearableOrApparel.getSchema(),
				new String[] { FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_IN_USE });
			patch.set(FieldNames.FIELD_OBJECT_ID, wearableOrApparel.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(OlioFieldNames.FIELD_IN_USE, value);
			IOSystem.getActiveContext().getRecordUtil().patch(patch, wearableOrApparel);
		} catch (Exception e) {
			logger.error("persistInUse failed for " + wearableOrApparel.getSchema() + ": " + e.getMessage());
		}
	}

	/**
	 * Force Step 4 to RE-DERIVE a scene's landscape/scene prompts instead of returning the value
	 * cached on the scene note. resolveLandscapePrompt/resolveScenePrompt short-circuit on a non-blank,
	 * non-error cached prompt (PictureBookUtil), so a scene whose prompt was cached BEFORE a
	 * prompt-assembly change (e.g. the style/setting-free charNarration fix) would otherwise keep
	 * serving the stale value. Strips just the "scenePrompt"/"landscapePrompt" keys from the note's
	 * text JSON, leaving every other key (status, imageObjectId, characters, setting, ...) intact.
	 */
	private void clearCachedScenePrompts(String sceneOid) {
		try {
			Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneOid);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			sq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "text" });
			BaseRecord scene = IOSystem.getActiveContext().getSearch().findRecord(sq);
			if (scene == null) return;
			String text = scene.get("text");
			if (text == null || text.isEmpty()) return;
			Map<String, Object> data = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
			if (data.remove("scenePrompt") == null & data.remove("landscapePrompt") == null) return;
			scene.set("text", JSONUtil.exportObject(data));
			IOSystem.getActiveContext().getAccessPoint().update(testUser, scene);
			logger.info("Cleared cached scene/landscape prompt on scene " + sceneOid + " to force re-derivation");
		} catch (Exception e) {
			logger.warn("clearCachedScenePrompts failed for " + sceneOid + ": " + e.getMessage());
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
		//logger.info("Chars: " + chars.length + " total, in group " + charsGroupPath);


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
/*
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
*/


		// ── CUSTOM-CHARACTER TUNING (examples; helpers a/b/c defined above exportImage). Uncomment
		//    + adapt to pin down specific characters before Step 4/5 image generation — the
		//    picturebook analogue of OlioTestUtil's Duke/Laurel imprint-over-existing pattern.
		// BaseRecord duna = pickCharacterByName(Arrays.asList(chars), "du.?a");   // (a) Duña or Duna
		// if (duna != null) {
		//     setColorByNameOnCharacter(duna, OlioFieldNames.FIELD_HAIR_COLOR, "Auburn"); // (b) hair
		//     setColorByNameOnCharacter(duna, OlioFieldNames.FIELD_EYE_COLOR,  "Hazel");  // (b) eyes
		//     imprintCustomCharacter(duna,                                                // (c)
		//         "{alignment:\"NEUTRALGOOD\",hairStyle:\"short and damp\"}",   // custom person props
		//         "{physicalStrength:6,agility:9,intelligence:14,perception:12}", // custom statistics
		//         "{neuroticism:0.7}",                                            // custom personality
		//         "raincoat,blouse,slacks,flats");                                // custom outfit CSV
		// }

		// olio.sd.config's schema "model" default (sdXL_v10VAEFix.safetensors) is almost certainly
		// not installed on your Swarm — always set model/refinerModel from test.swarm.* (same as
		// buildSdConfigTemplate() for Step 5).
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);

		BaseRecord duna = pickCharacterByName(Arrays.asList(chars), "du.?a");   // (a) Duña or Duna
		assertNotNull("Should have found a character named Duña/Duna", duna);
		if((int)duna.get("age") != 15){
			logger.info("Patching Duña/Duna to age 15 for this test run (was " + duna.get("age") + ")");
			setColorByNameOnCharacter(duna, OlioFieldNames.FIELD_HAIR_COLOR, "Auburn"); // (b) hair
			setColorByNameOnCharacter(duna, OlioFieldNames.FIELD_EYE_COLOR,  "Hazel");  // (b) eyes
			imprintCustomCharacter(duna,                                                // (c)
			"{age: 15, firstName: \"Duña\", lastName: \"de Rosa\", name: \"Duña de Rosa\", alignment:\"CHAOTICGOOD\",hairStyle:\"long and damp\", race:[\"E\"],ethnicity:[\"FIFTEEN\"]}",   // custom person props
			"{physicalStrength:12,agility:16,intelligence:14,perception:12, charisma: 19}", // custom statistics
			"{neuroticism:0.7}",                                            // custom personality
			"bra,panties,blouse,skirt,thigh-high heeled boots,anklet,amulet,jewelry:piercing:7:f:ear");
			generateApparelImage(duna);                        // custom outfit CSV
			undressToLevel(duna, WearLevelEnumType.ON);
			generatePortrait(duna, true);
			dressToLevel(duna, WearLevelEnumType.BASE);
			generatePortrait(duna);
			dressToLevel(duna, WearLevelEnumType.SUIT);
			generatePortrait(duna);
		}
		
		BaseRecord jid = pickCharacterByName(Arrays.asList(chars), "Jideon");   // (a) Duña or Duna
		assertNotNull("Should have found a character named Jideon", jid);
		if((int)jid.get("age") != 45){
			logger.info("Patching Jideon to age 45 for this test run (was " + jid.get("age") + ")");
			setColorByNameOnCharacter(jid, OlioFieldNames.FIELD_HAIR_COLOR, "Dark Brown"); // (b) hair
			setColorByNameOnCharacter(jid, OlioFieldNames.FIELD_EYE_COLOR,  "Hazel");  // (b) eyes
			imprintCustomCharacter(jid,                                                // (c)
			"{age: 45, firstName: \"Jideon\", lastName: \"de Rosa\", name: \"Jideon de Rosa\", alignment:\"CHAOTICGOOD\",hairStyle:\"unkempt\", race:[\"E\"],ethnicity:[\"FIFTEEN\"]}",   // custom person props
			"{physicalStrength:16, agility:16,intelligence:18,perception:16,charisma: 16}", // custom statistics
			"{neuroticism:0.7}",                                            // custom personality
			"t-shirt,cargo pants,socks,shoes,clothing:leather jacket:5:m:shoulder");                     
			generateApparelImage(jid);           // custom outfit CSV
			undressToLevel(jid, WearLevelEnumType.ON);
			generatePortrait(jid, true);
			dressToLevel(jid, WearLevelEnumType.BASE);
			generatePortrait(jid);
			dressToLevel(jid, WearLevelEnumType.SUIT);
			generatePortrait(jid);
		}
		
		//generateApparelImage(duna);                        // custom outfit CSV
		/*
		undressToLevel(duna, WearLevelEnumType.ON);
		generatePortrait(duna, true);
		dressToLevel(duna, WearLevelEnumType.BASE);
		generatePortrait(duna);
		dressToLevel(duna, WearLevelEnumType.SUIT);
		generatePortrait(duna);
		//generateApparelImage(jid);           // custom outfit CSV
		undressToLevel(jid, WearLevelEnumType.ON);
		generatePortrait(jid, true);
		dressToLevel(jid, WearLevelEnumType.BASE);
		generatePortrait(jid);
		dressToLevel(jid, WearLevelEnumType.SUIT);
		generatePortrait(jid);
		*/

		

		// ═══════════════════════════════════════════════════════════════════
		// STEP 4 — PROMPT RESOLUTION (check the prompt BEFORE spending SD time on it)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: resolve+cache landscape/scene prompts, then re-read the scene note's own "text"
		// JSON to see exactly what got cached (and what would actually be sent to SDUtil.txt2img).

		 List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		 List<String> sceneOids = new ArrayList<>();
		 for (Map<String, Object> s : scenes) sceneOids.add((String) s.get("objectId"));
		 // Re-derive from scratch so the logged prompts reflect the CURRENT assembly code (the
		 // style/setting-free charNarration fix), not a value cached by an earlier run.
		 for (String sceneOid : sceneOids) clearCachedScenePrompts(sceneOid);
		 PictureBookUtil.prepareSceneImagePrompts(testUser, sceneOids,
		     chatConfig.get(FieldNames.FIELD_NAME), buildCommonSdConfig(), null);
		
		 for (String sceneOid : sceneOids) {
		     Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneOid);
		     sq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		     sq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "text" });
		     BaseRecord sceneNote = IOSystem.getActiveContext().getSearch().findRecord(sq);
		     Map<String, Object> sceneData = JSONUtil.getMap(((String) sceneNote.get("text")).getBytes(), String.class, Object.class);
		     logger.info("Scene " + sceneOid + " landscapePrompt=[" + sceneData.get("landscapePrompt") + "]");
		     logger.info("Scene " + sceneOid + " scenePrompt=[" + sceneData.get("scenePrompt") + "]");
		}

		if(true){
			return;
		}

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

	/**
	 * Focused verification of the per-character portrait STYLE override + the double-style fix
	 * ("global unless overridden locally"). Two parts, both REAL:
	 *  1. Pure logic: {@link PictureBookUtil#stripTrailingConfigStyle} removes the RANDOM style a
	 *     narrative sdPrompt bakes in, and {@link PictureBookUtil#buildPortraitDescription} applies
	 *     exactly ONE effective style (global photograph, or a local comic override) while keeping the
	 *     appearance/outfit text.
	 *  2. Live DB round-trip: {@link PictureBookUtil#setCharacterStyleOverride}/{@code
	 *     getCharacterStyleOverride} persist and read back a per-character override on the (cached)
	 *     catatone book's {@code pictureBookMeta}, and a second character stays override-free.
	 * Deliberately does NOT render an image — this verifies the SD REQUEST PROMPT and the persistence
	 * (per .claude/rules "verify the actual SD payload"); a full portrait render needs a GPU-heavy
	 * Swarm scene pass and is out of this test's scope.
	 */
	@Test
	public void TestPortraitStyleOverride() throws Exception {
		setupTestContext();

		// (1) strip removes a baked-in random art style; keeps appearance/outfit.
		String randomStyled = "8k highly detailed ((full body)) of a 45 year old man wearing a leather jacket. "
			+ "He is a medic in a desert, circa 100 AD. (Romantic art with soft focus and nostalgic themes).";
		String stripped = PictureBookUtil.stripTrailingConfigStyle(randomStyled);
		assertFalse("strip must remove the baked-in art style", stripped.contains("Romantic art"));
		assertTrue("strip must keep appearance/outfit text", stripped.contains("wearing a leather jacket"));

		// (2) global (photograph) applied ONCE, random style gone.
		BaseRecord photo = SDUtil.randomSDConfig();
		photo.setValue("style", "photograph");
		SDUtil.fillStyleDefaults(photo);
		String globalDesc = PictureBookUtil.buildPortraitDescription(randomStyled, photo);
		assertFalse("portrait desc must drop the random art style", globalDesc.contains("Romantic art"));
		assertTrue("portrait desc must carry the photograph style", globalDesc.contains("Photograph"));
		assertEquals("exactly one style clause", 1, countOccurrences(globalDesc, "taken with a"));

		// (2b) a LOCAL comic override replaces only the style, not the appearance.
		BaseRecord comic = SDUtil.randomSDConfig();
		comic.setValue("style", "comic");
		SDUtil.fillStyleDefaults(comic);
		String overrideDesc = PictureBookUtil.buildPortraitDescription(randomStyled, comic);
		assertTrue("override desc carries the comic style", overrideDesc.contains("Comic book panel"));
		assertFalse("override desc must NOT carry the photograph style", overrideDesc.contains("taken with a"));
		assertTrue("override desc keeps appearance/outfit", overrideDesc.contains("wearing a leather jacket"));

		// (3) live DB round-trip on the (cached) catatone book.
		String workObjectId = getOrCreateCatatoneWork();
		List<Map<String, Object>> sceneList = getOrCreateCatatoneScenes(workObjectId);
		String bookObjectId = getOrCreateCatatoneBook(workObjectId, sceneList);
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(),
			(long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query charQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		charQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		charQ.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(charQ);
		assertTrue("need at least one character to override", chars.length > 0);
		String charOid = chars[0].get(FieldNames.FIELD_OBJECT_ID);
		String charName = chars[0].get(FieldNames.FIELD_NAME);

		BaseRecord cartoon = SDUtil.randomSDConfig();
		cartoon.setValue("style", "comic");
		SDUtil.fillStyleDefaults(cartoon);
		PictureBookUtil.setCharacterStyleOverride(testUser, bookObjectId, charOid, charName, cartoon);
		BaseRecord readBack = PictureBookUtil.getCharacterStyleOverride(testUser, bookObjectId, charOid);
		assertNotNull("override should persist on pictureBookMeta", readBack);
		assertEquals("persisted override style", "comic", readBack.get("style"));
		if (chars.length > 1) {
			assertNull("other characters must have no override",
				PictureBookUtil.getCharacterStyleOverride(testUser, bookObjectId, (String) chars[1].get(FieldNames.FIELD_OBJECT_ID)));
		}
		// clearing removes it
		PictureBookUtil.setCharacterStyleOverride(testUser, bookObjectId, charOid, charName, null);
		assertNull("override should clear", PictureBookUtil.getCharacterStyleOverride(testUser, bookObjectId, charOid));

		logger.info("TestPortraitStyleOverride: strip + single-style + comic-override + DB round-trip verified "
			+ "(SD request-prompt + persistence; no image render)");
	}

	private static int countOccurrences(String hay, String needle) {
		int n = 0, i = 0;
		while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
		return n;
	}

	private static boolean containsIgnoreCase(String hay, String needle) {
		return hay != null && hay.toLowerCase().contains(needle.toLowerCase());
	}

	/** Read a string attribute off a charPerson the same way resolveSceneCharacter does (query with
	 *  FIELD_ATTRIBUTES, then AttributeUtil), so this verifies the exact read path imaging relies on. */
	private String readCharAttr(String charsGroupPath, String charName, String attr) throws Exception {
		BaseRecord g = IOSystem.getActiveContext().getPathUtil().findPath(testUser, ModelNames.MODEL_GROUP,
			charsGroupPath, GroupEnumType.DATA.toString(), (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, charName);
		q.field(FieldNames.FIELD_GROUP_ID, g.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ATTRIBUTES });
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("character '" + charName + "' should exist", cp);
		return AttributeUtil.getAttributeValue(cp, attr, (String) null);
	}

	/**
	 * Verifies the scene-referenced, block-reduced character extraction: a character that appears ONLY
	 * in a later scene's content block gets its details reduced from THAT block (not the opening), with
	 * scene refs (Attribute 1) + a condensed description (Attribute 2) persisted, and the description
	 * used for imaging. Drives createFromScenes with a synthetic 2-scene book (unique per run so the
	 * reduce always fires). Live LLM (Ollama) + DB required.
	 */
	@Test
	public void TestSceneReducedCharacterDescription() throws Exception {
		setupTestContext();

		String passageA = "Anna strode into the crowded market at dawn. She was a tall young woman with bright "
			+ "red hair tied back and green eyes, wearing a simple blue linen dress and leather sandals.";
		String passageB = "At the far gate stood the guard, a burly bald older man with a deep scar across his "
			+ "cheek. He wore heavy grey chainmail armor over a padded tunic and gripped a long iron halberd.";

		// Source work note (createFromScenes.extractWorkText fallback only; the reduce uses the per-scene blocks).
		ParameterList wplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
		wplist.parameter(FieldNames.FIELD_NAME, "reduce-src-" + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, wplist);
		work.set("text", passageA + "\n\n" + passageB);
		work = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);

		// Synthetic sceneList: Anna only in scene 0's block, The Guard only in scene 1's block.
		List<Map<String, Object>> sceneList = new ArrayList<>();
		sceneList.add(scene(0, "Market", "A crowded market at dawn", "Anna enters the market", "busy, hopeful", "Anna", passageA));
		sceneList.add(scene(1, "Gate", "A fortified stone gate", "The guard blocks the gate", "tense, cold", "The Guard", passageB));

		String bookName = "Reduce Test Book " + System.currentTimeMillis();
		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId,
			chatConfig.get(FieldNames.FIELD_NAME), "fantasy", bookName, sceneList, new ArrayList<>(),
			testProperties.getProperty("test.datagen.path"));
		assertNotNull("createFromScenes should return meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";

		// --- Attribute 2 (condensed description) reduced from the RIGHT block ---
		String guardDesc = readCharAttr(charsGroupPath, "The Guard", PictureBookUtil.ATTR_DESCRIPTION);
		logger.info("The Guard ATTR_DESCRIPTION = [" + guardDesc + "]");
		assertNotNull("The Guard should have a reduced description attribute", guardDesc);
		assertFalse("description should not be blank", guardDesc.isBlank());
		assertTrue("The Guard's description must come from HIS block (bald/scar/chainmail/halberd), not the opening",
			containsIgnoreCase(guardDesc, "bald") || containsIgnoreCase(guardDesc, "scar")
			|| containsIgnoreCase(guardDesc, "chainmail") || containsIgnoreCase(guardDesc, "halberd"));
		assertFalse("The Guard's description must NOT leak Anna's block (no 'red' hair from passage A)",
			containsIgnoreCase(guardDesc, "red"));

		String annaDesc = readCharAttr(charsGroupPath, "Anna", PictureBookUtil.ATTR_DESCRIPTION);
		logger.info("Anna ATTR_DESCRIPTION = [" + annaDesc + "]");
		assertNotNull("Anna should have a reduced description attribute", annaDesc);
		assertTrue("Anna's description should reflect her own block (red hair)", containsIgnoreCase(annaDesc, "red"));

		// --- Attribute 1 (scene refs) ---
		assertEquals("The Guard appears only in scene 1", "1", readCharAttr(charsGroupPath, "The Guard", PictureBookUtil.ATTR_SCENE_REFS));
		assertEquals("Anna appears only in scene 0", "0", readCharAttr(charsGroupPath, "Anna", PictureBookUtil.ATTR_SCENE_REFS));

		// --- Imaging uses Attribute 2: the Guard's scene prompt reflects his reduced description ---
		List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		List<String> sceneOids = new ArrayList<>();
		for (Map<String, Object> s : scenes) sceneOids.add((String) s.get("objectId"));
		PictureBookUtil.prepareSceneImagePrompts(testUser, sceneOids, chatConfig.get(FieldNames.FIELD_NAME), buildCommonSdConfig(), null);
		// Find the Guard's scene (index 1) and read its cached scenePrompt.
		String guardScenePrompt = null;
		for (Map<String, Object> s : scenes) {
			Object idx = s.get("index");
			if (idx instanceof Number && ((Number) idx).intValue() == 1) {
				Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, (String) s.get("objectId"));
				sq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				sq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "text" });
				BaseRecord note = IOSystem.getActiveContext().getSearch().findRecord(sq);
				Map<String, Object> sd = JSONUtil.getMap(((String) note.get("text")).getBytes(), String.class, Object.class);
				guardScenePrompt = (String) sd.get("scenePrompt");
			}
		}
		logger.info("Guard scene prompt = [" + guardScenePrompt + "]");
		assertNotNull("scene 1 should have a generated scenePrompt", guardScenePrompt);
		assertTrue("the Guard's scene prompt must reflect his reduced description (bald/scar/chainmail/halberd)",
			containsIgnoreCase(guardScenePrompt, "bald") || containsIgnoreCase(guardScenePrompt, "scar")
			|| containsIgnoreCase(guardScenePrompt, "chainmail") || containsIgnoreCase(guardScenePrompt, "halberd"));

		logger.info("TestSceneReducedCharacterDescription: block-scoped reduce + Attr1/Attr2 + imaging-uses-Attr2 verified");
	}

	/**
	 * Verifies the LEGACY all-in-one extract() now goes through the SAME reduce/attribute path as
	 * createFromScenes (not the old inline extract-character loop): after extract() runs, at least one
	 * created character carries a non-blank ATTR_DESCRIPTION (Attribute 2), which only the reduce path
	 * produces. Live LLM + DB. Uses a unique book per run so extract() actually creates fresh.
	 */
	@Test
	public void TestExtractLegacyUsesReduce() throws Exception {
		setupTestContext();
		String text = "Captain Mara Voss stood at the helm of her airship at dawn, a lean woman with cropped "
			+ "silver hair and sharp grey eyes, wearing a long crimson greatcoat and black leather gloves. "
			+ "She raised a brass telescope toward the gathering storm over the mountains.\n\n"
			+ "Later, in the engine room, the mechanic Bran Kell, a stocky young man with soot-streaked "
			+ "freckled skin and a patched leather apron over a grease-stained shirt, hammered at a cracked "
			+ "boiler valve while sparks rained around him.";
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
		plist.parameter(FieldNames.FIELD_NAME, "extract-legacy-src-" + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", text);
		work = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);

		String bookName = "Extract Legacy Book " + System.currentTimeMillis();
		BaseRecord meta = PictureBookUtil.extract(testUser, workObjectId, 3, chatConfig.get(FieldNames.FIELD_NAME),
			"steampunk", bookName, testProperties.getProperty("test.datagen.path"));
		assertNotNull("extract() should return meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull("extract() meta should carry a bookObjectId", bookObjectId);

		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser, ModelNames.MODEL_GROUP,
			charsGroupPath, GroupEnumType.DATA.toString(), (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("extract() should have created a Characters group", charsGroup);
		Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		cq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ATTRIBUTES });
		BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(cq);
		assertTrue("extract() should have created at least one character", chars != null && chars.length > 0);

		boolean anyDesc = false;
		for (BaseRecord cp : chars) {
			String d = AttributeUtil.getAttributeValue(cp, PictureBookUtil.ATTR_DESCRIPTION, (String) null);
			logger.info("extract() char '" + cp.get(FieldNames.FIELD_NAME) + "' ATTR_DESCRIPTION=[" + d + "]");
			if (d != null && !d.isBlank()) anyDesc = true;
		}
		assertTrue("legacy extract() must go through the reduce path (a character has an ATTR_DESCRIPTION)", anyDesc);
		logger.info("TestExtractLegacyUsesReduce: legacy extract() delegates to the reduce/attribute path");
	}

	/**
	 * Guards the prompt-bloat regression: the transient raw {@code sourceText} content block (carried
	 * on scenes to feed the per-character reduce) must NEVER be serialized into the chunk extractor's
	 * {@code previousScenes} LLM prompt — otherwise each chunk re-sends the full raw text of every
	 * prior scene (100KB+, growing O(n^2)). Pure serialization check — no LLM/DB.
	 */
	@Test
	public void TestPreviousScenesPromptExcludesSourceText() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene(0, "S0", "setting-zero", "action0", "mood0", "Anna", "RAW_BLOCK_ZERO must not reach the LLM"));
		scenes.add(scene(1, "S1", "setting-one", "action1", "mood1", "Bran", "RAW_BLOCK_ONE must not reach the LLM"));
		// Verbose output-only fields that also should NOT be re-sent as running context each chunk.
		scenes.get(0).put("diffusionPrompt", "VERBOSE_DIFFUSION_ZERO must not reach the LLM");
		scenes.get(1).put("diffusionPrompt", "VERBOSE_DIFFUSION_ONE must not reach the LLM");
		// The raw list DOES carry sourceText + diffusionPrompt — that's the full scene data.
		assertTrue("scenes should carry sourceText for the reduce", JSONUtil.exportObject(scenes).contains("sourceText"));

		// The prompt-facing projection keeps only PROMPT_SCENE_FIELDS.
		String promptJson = JSONUtil.exportObject(PictureBookUtil.scenesForPrompt(scenes));
		assertFalse("previousScenes must not include the raw sourceText block", promptJson.contains("sourceText") || promptJson.contains("RAW_BLOCK"));
		assertFalse("previousScenes must not include the verbose diffusionPrompt", promptJson.contains("diffusionPrompt") || promptJson.contains("VERBOSE_DIFFUSION"));
		assertFalse("previousScenes must not include bookkeeping fields", promptJson.contains("userEdited") || promptJson.contains("\"index\""));
		assertTrue("scene fields the LLM needs must still be present", promptJson.contains("S0") && promptJson.contains("setting-zero")
			&& promptJson.contains("Anna") && promptJson.contains("mood0"));
	}

	private static Map<String, Object> scene(int index, String title, String setting, String action, String mood,
			String characterName, String sourceText) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("index", index);
		s.put("title", title);
		s.put("blurb", action);
		s.put("setting", setting);
		s.put("action", action);
		s.put("mood", mood);
		Map<String, Object> ch = new LinkedHashMap<>();
		ch.put("name", characterName);
		ch.put("role", "scene character");
		s.put("characters", new ArrayList<>(Arrays.asList(ch)));
		s.put("sourceText", sourceText);
		return s;
	}

}
