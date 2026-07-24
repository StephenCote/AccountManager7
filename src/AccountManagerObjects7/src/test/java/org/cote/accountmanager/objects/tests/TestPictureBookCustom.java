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
 * image generation), each stubbed with TODOs and commented-out example calls into the real
 * PictureBookUtil API. Fill in / uncomment what you need per step and drop the placeholder
 * assertion once you're asserting real things.
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

	// Isolated org path so this doesn't collide with TestPictureBookFull's own fixtures.
	private static final String ORG_PATH = "/Development/PictureBook Custom Tests";
	private static final String PB_LLM_MODEL = "qwen3-vl:8b-instruct";

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
		String cfgName = "PictureBook " + PB_LLM_MODEL + ".chat";
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, cfgName, "~/Chat");
		if (existing != null) return existing;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, cfgName);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("connection", OlioTestUtil.getCreateConnection(user, cfgName + " Connection", serverUrl, null, 300));
			cfg.set("model", PB_LLM_MODEL);
			cfg.set("stream", false);
			BaseRecord opts = cfg.get("chatOptions");
			if (opts == null) {
				opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
				cfg.set("chatOptions", opts);
			}
			opts.set("think", false);
			opts.set("temperature", 0.3);
			return IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		} catch (Exception e) {
			logger.error("Failed to create PB chat config: " + e.getMessage());
			return null;
		}
	}

	/**
	 * SD CONFIG TEMPLATE — edit these to taste before Step 5. Mirrors the fields
	 * PictureBookUtil.generateSceneImage()'s real callers set (see TestPictureBookFull.java's
	 * SceneGenerationParams usage) — every field here maps directly to something SDUtil/SWUtil
	 * eventually sends to the SD backend.
	 */
	private PictureBookUtil.SceneGenerationParams buildSdConfigTemplate() {
		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		params.chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";
		params.steps = 20;                  // sampling steps
		params.cfg = 5;                     // CFG scale
		params.hires = false;               // classic (Graphics2D composite + img2img) pipeline only — see KI-10/KI notes on Kontext caveats
		params.isBookOverride = true;       // persist/reuse portraits under the book's Characters/ group (vs. ~/Chat fallback)
		params.style = "illustration";      // one of ALLOWED_STYLES: illustration | photograph | anime | art | digitalart | movie
		params.seed = -1;                   // -1 = random
		params.sdModelName = testProperties.getProperty("test.swarm.model"); // schema default may not be installed on your Swarm — see resource.properties
		params.sdRefinerModelName = null;   // null = use schema/config default
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
		// STEP 1 — SOURCE TEXT
		// ═══════════════════════════════════════════════════════════════════
		// TODO: pick ONE of the two patterns below and set workObjectId.

		// -- Pattern A: plain text (fastest to iterate on) --
		// ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		// plist.parameter(FieldNames.FIELD_NAME, "Custom Story " + System.currentTimeMillis());
		// BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		// work.set("text", "YOUR STORY TEXT HERE");
		// BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		// String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		// -- Pattern B: real document (.docx/.pdf) via DocumentUtil, mirrors TestPictureBookFull's
		//    catatone.docx tests --
		// byte[] fileBytes = FileUtil.getFile("./media/YOUR_FILE.docx");
		// ParameterList docPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		// docPlist.parameter(FieldNames.FIELD_NAME, "custom-source-" + System.currentTimeMillis() + ".docx");
		// BaseRecord docWork = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, docPlist);
		// docWork.set(FieldNames.FIELD_CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		// ByteModelUtil.setValue(docWork, fileBytes);
		// BaseRecord createdDocWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, docWork);
		// String workObjectId = createdDocWork.get(FieldNames.FIELD_OBJECT_ID);
		// // Optional: isolate a specific passage the way KI-31's catatone test does — extract() only
		// // sends the first 8000 chars of whatever text you give it, and pictureBook.extract-scenes'
		// // own system prompt spreads scene selection across the WHOLE input, not just the start.
		// String fullText = DocumentUtil.getStringContent(createdDocWork);
		// String isolatedText = fullText.substring(0, fullText.indexOf("YOUR CUTOFF ANCHOR PHRASE"));

		String workObjectId = null; // TODO: set from Pattern A or B above
		String bookObjectId = null; // set in Step 2

		// ═══════════════════════════════════════════════════════════════════
		// STEP 2 — SCENE EXTRACTION
		// ═══════════════════════════════════════════════════════════════════
		// TODO: choose auto (extract) or manual-curation (extractScenesOnly + createFromScenes).

		// -- Auto: extraction + character creation in one call --
		// BaseRecord meta = PictureBookUtil.extract(testUser, workObjectId, /*count*/ 2,
		//     chatConfig.get(FieldNames.FIELD_NAME), /*genre*/ "your genre here",
		//     "Custom Book " + System.currentTimeMillis(), testProperties.getProperty("test.datagen.path"));
		// bookObjectId = meta.get("bookObjectId");
		// List<Object> failedCharacters = meta.get("failedCharacters");
		// logger.info("failedCharacters: " + failedCharacters);

		// -- Manual: review/edit scenes yourself before character creation --
		// PictureBookUtil.ScenesOnlyResult scenesOnly = PictureBookUtil.extractScenesOnly(
		//     testUser, workObjectId, /*count*/ 2, chatConfig.get(FieldNames.FIELD_NAME), null);
		// List<Map<String, Object>> sceneList = scenesOnly.scenes; // edit setting/action/mood/characters here
		// List<Map<String, Object>> charDataList = new ArrayList<>(); // [{name, gender, role}, ...]
		// BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId,
		//     chatConfig.get(FieldNames.FIELD_NAME), null, "Custom Book " + System.currentTimeMillis(),
		//     sceneList, charDataList, testProperties.getProperty("test.datagen.path"));
		// bookObjectId = meta.get("bookObjectId");

		// ═══════════════════════════════════════════════════════════════════
		// STEP 3 — CHARACTER GENERATION (inspect what extract()/createFromScenes() built)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: query the book's Characters group and log each character's key fields —
		// race/alignment/instinct/personality/state are the KI-30 random-baseline signal;
		// statistics/store/profile/narrative are the hard-required persisted sub-records.

		// BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		// String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";
		// BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
		//     ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(),
		//     (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		// Query charQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		// charQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		// charQ.planMost(true);
		// BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(charQ);
		// for (BaseRecord cp : chars) {
		//     logger.info("Character: " + cp.get(FieldNames.FIELD_NAME)
		//         + " gender=" + cp.get(FieldNames.FIELD_GENDER)
		//         + " race=" + cp.get(OlioFieldNames.FIELD_RACE)
		//         + " alignment=" + cp.get(FieldNames.FIELD_ALIGNMENT));
		// }

		// ═══════════════════════════════════════════════════════════════════
		// STEP 4 — PROMPT RESOLUTION (check the prompt BEFORE spending SD time on it)
		// ═══════════════════════════════════════════════════════════════════
		// TODO: resolve+cache landscape/scene prompts, then re-read the scene note's own "text"
		// JSON to see exactly what got cached (and what would actually be sent to SDUtil.txt2img).

		// List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		// List<String> sceneOids = new ArrayList<>();
		// for (Map<String, Object> s : scenes) sceneOids.add((String) s.get("objectId"));
		// PictureBookUtil.prepareSceneImagePrompts(testUser, sceneOids,
		//     chatConfig.get(FieldNames.FIELD_NAME), "illustration", null);
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

		// String swarmServer = testProperties.getProperty("test.swarm.server");
		// PictureBookUtil.SceneGenerationParams params = buildSdConfigTemplate();
		// for (String sceneOid : sceneOids) {
		//     BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", swarmServer);
		//     logger.info("Scene " + sceneOid + " -> imageObjectId=" + (result != null ? result.get("imageObjectId") : "null")
		//         + " prompt=[" + (result != null ? result.get("prompt") : "null") + "]");
		// }

		// Placeholder so this compiles/runs as-is before you fill in the steps above.
		assertTrue("Skeleton test — fill in Steps 1-5 above and replace this with real assertions", true);
	}

}
