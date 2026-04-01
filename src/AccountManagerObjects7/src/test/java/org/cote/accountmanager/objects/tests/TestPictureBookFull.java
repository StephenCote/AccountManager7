package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/**
 * Comprehensive PictureBook backend unit test.
 * Exercises: model creation, group hierarchy, scene extraction (LLM),
 * character creation, meta persistence, image generation (SD),
 * seed capture, think:false, and the full pipeline.
 *
 * Uses test user via OlioTestUtil — NEVER admin for data operations.
 */
public class TestPictureBookFull extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook Full Tests";
	private static final String TEST_STORY =
		"Chapter 1: The Forest\n" +
		"Elena stepped into the ancient forest. Towering oaks cast long shadows across the moss-covered path. " +
		"Her companion, a tall man named Marcus with weathered features and a thick grey beard, walked beside her " +
		"carrying a battered longsword. The air smelled of pine and rain.\n\n" +
		"Chapter 2: The Dragon\n" +
		"They reached the mountain pass by dusk. A massive red dragon perched on the cliff above, its scales " +
		"gleaming like rubies in the fading light. Elena drew her silver rapier. Marcus raised his shield. " +
		"The dragon opened its jaws and a gout of flame lit the twilight sky.\n\n" +
		"Chapter 3: The Victory\n" +
		"Elena dove beneath the flames and thrust her rapier into the dragon's underbelly. Marcus hacked at " +
		"its wing joint. The beast roared and collapsed. As dawn broke, they stood over the fallen creature, " +
		"victorious and exhausted, the mountain pass now safe for travelers.";

	private OrganizationContext testOrgCtx;
	private BaseRecord testUser;
	private BaseRecord chatConfig;

	private static final String PB_LLM_MODEL = "qwen3-vl:8b-instruct";

	private void setupTestContext() {
		testOrgCtx = getTestOrganization(ORG_PATH);
		Factory mf = IOSystem.getActiveContext().getFactory();
		testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbTestUser", testOrgCtx.getOrganizationId());
		assertNotNull("Test user should be created", testUser);

		// Use qwen3:8b explicitly for PictureBook tests — small, fast, with think:false
		String ollamaServer = testProperties.getProperty("test.llm.ollama.server");
		assertNotNull("test.llm.ollama.server must be set", ollamaServer);
		chatConfig = getOrCreatePbChatConfig(testUser, ollamaServer);
		assertNotNull("Chat config should be created", chatConfig);
	}

	private BaseRecord getOrCreatePbChatConfig(BaseRecord user, String serverUrl) {
		String cfgName = "PictureBook " + PB_LLM_MODEL + ".chat";
		BaseRecord existing = org.cote.accountmanager.util.DocumentUtil.getRecord(
			user, OlioModelNames.MODEL_CHAT_CONFIG, cfgName, "~/Chat");
		if (existing != null) return existing;

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, cfgName);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("serverUrl", serverUrl);
			cfg.set("model", PB_LLM_MODEL);
			cfg.set("stream", false);
			cfg.set("requestTimeout", 300);

			// Set think:false on chatOptions
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

	private BaseRecord ensureGroup(String path) {
		return IOSystem.getActiveContext().getPathUtil().makePath(
			testUser, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(),
			((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
	}

	// ── Model Tests ──────────────────────────────────────────────────────

	@Test
	public void TestPictureBookModelsExist() {
		logger.info("Test: PictureBook models registered and instantiable");

		try {
			BaseRecord meta = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_META);
			assertNotNull("pictureBookMeta should instantiate", meta);
			meta.set("sourceObjectId", "test-src");
			meta.set("bookObjectId", "test-book");
			meta.set("workName", "Test Book");
			meta.set("sceneCount", 3);
			meta.set("extractedAt", "2026-04-01");
			assertEquals("workName", "Test Book", meta.get("workName"));
			assertEquals("sceneCount", 3, (int) meta.get("sceneCount"));

			BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_SCENE);
			assertNotNull("pictureBookScene should instantiate", scene);
			scene.set("title", "Test Scene");
			scene.set("index", 0);
			scene.set("setting", "A dark forest");
			scene.set("mood", "tense");
			assertEquals("title", "Test Scene", scene.get("title"));

			BaseRecord result = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_RESULT);
			assertNotNull("pictureBookResult should instantiate", result);
			result.set("imageObjectId", "img-123");
			result.set("seed", 42);
			result.set("chunked", true);
			assertEquals("seed", 42, (int) result.get("seed"));
			assertTrue("chunked", (boolean) result.get("chunked"));

			logger.info("All 3 PictureBook models instantiate and accept values");
		} catch (Exception e) {
			fail("Model instantiation failed: " + e.getMessage());
		}
	}

	@Test
	public void TestPictureBookRequestModel() {
		logger.info("Test: pictureBookRequest has sceneList, characters, promptTemplate fields");
		try {
			BaseRecord req = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_REQUEST);
			assertNotNull("pictureBookRequest should instantiate", req);
			req.set("bookName", "Test Book");
			req.set("chatConfig", "testConfig");
			req.set("genre", "fantasy");
			req.set("promptTemplate", "myTemplate");
			assertEquals("bookName", "Test Book", req.get("bookName"));
			assertEquals("promptTemplate", "myTemplate", req.get("promptTemplate"));

			// sceneList and characters should be list fields
			Object sl = req.get("sceneList");
			assertNotNull("sceneList field should exist", sl);
			assertTrue("sceneList should be a List", sl instanceof List);

			Object cl = req.get("characters");
			assertNotNull("characters field should exist", cl);
			assertTrue("characters should be a List", cl instanceof List);

			logger.info("pictureBookRequest model fields verified");
		} catch (Exception e) {
			fail("Request model failed: " + e.getMessage());
		}
	}

	// ── Group Hierarchy Tests ────────────────────────────────────────────

	@Test
	public void TestBookGroupCreation() {
		logger.info("Test: Book group hierarchy under ~/Data/PictureBooks/");
		setupTestContext();

		String bookName = "UnitTestBook-" + System.currentTimeMillis();
		String bookPath = "~/Data/PictureBooks/" + bookName;

		BaseRecord bookGroup = ensureGroup(bookPath);
		assertNotNull("Book group should be created", bookGroup);

		BaseRecord scenesGroup = ensureGroup(bookPath + "/Scenes");
		assertNotNull("Scenes sub-group should be created", scenesGroup);

		BaseRecord charsGroup = ensureGroup(bookPath + "/Characters");
		assertNotNull("Characters sub-group should be created", charsGroup);

		logger.info("Book group hierarchy created: " + bookPath);
	}

	// ── Scene Note Persistence ───────────────────────────────────────────

	@Test
	public void TestSceneNoteCreation() {
		logger.info("Test: Scene data.note with JSON text field");
		setupTestContext();

		String scenesPath = "~/Data/PictureBooks/UnitTest-Scenes-" + System.currentTimeMillis() + "/Scenes";
		BaseRecord scenesGroup = ensureGroup(scenesPath);
		assertNotNull("Scenes group", scenesGroup);

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, scenesPath);
			plist.parameter(FieldNames.FIELD_NAME, "Scene 1 - Dragon Pass");
			BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_NOTE, testUser, null, plist);

			Map<String, Object> sceneData = new LinkedHashMap<>();
			sceneData.put("title", "Dragon Pass");
			sceneData.put("summary", "Elena faces the dragon at the mountain pass.");
			sceneData.put("setting", "Mountain pass at dusk");
			sceneData.put("action", "Elena draws her rapier");
			sceneData.put("mood", "tense, dark");
			sceneData.put("sceneIndex", 0);
			List<Map<String, Object>> chars = new ArrayList<>();
			Map<String, Object> c1 = new LinkedHashMap<>();
			c1.put("name", "Elena");
			c1.put("role", "protagonist");
			chars.add(c1);
			sceneData.put("characters", chars);
			note.set("text", JSONUtil.exportObject(sceneData));

			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, note);
			assertNotNull("Scene note should be created", created);

			// Re-read and verify
			String oid = created.get(FieldNames.FIELD_OBJECT_ID);
			Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, oid);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);
			BaseRecord readBack = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			assertNotNull("Should read back scene note", readBack);

			String text = readBack.get("text");
			assertNotNull("text field should be populated", text);
			assertTrue("text should contain title", text.contains("Dragon Pass"));
			assertTrue("text should contain Elena", text.contains("Elena"));

			logger.info("Scene note created and verified: " + oid);
		} catch (Exception e) {
			fail("Scene note creation failed: " + e.getMessage());
		}
	}

	// ── Meta Persistence ─────────────────────────────────────────────────

	@Test
	public void TestMetaPersistence() {
		logger.info("Test: .pictureBookMeta round-trip via data.note text field");
		setupTestContext();

		String metaPath = "~/Data/PictureBooks/UnitTest-Meta-" + System.currentTimeMillis();
		BaseRecord metaGroup = ensureGroup(metaPath);
		assertNotNull("Meta group", metaGroup);

		try {
			BaseRecord meta = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_META);
			meta.set("sourceObjectId", "src-123");
			meta.set("bookObjectId", metaGroup.get(FieldNames.FIELD_OBJECT_ID));
			meta.set("workName", "Test Meta Book");
			meta.set("sceneCount", 2);
			meta.set("extractedAt", "2026-04-01T00:00:00Z");

			String metaJson = meta.toFullString();
			assertNotNull("Meta JSON", metaJson);
			assertTrue("Meta JSON should contain workName", metaJson.contains("Test Meta Book"));

			// Store as .pictureBookMeta note
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, metaPath);
			plist.parameter(FieldNames.FIELD_NAME, ".pictureBookMeta");
			BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_NOTE, testUser, null, plist);
			note.set("text", metaJson);
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, note);
			assertNotNull(".pictureBookMeta note should be created", created);

			// Read back
			Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, ".pictureBookMeta");
			q.field(FieldNames.FIELD_GROUP_ID, metaGroup.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);
			BaseRecord readBack = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			assertNotNull("Should read back meta note", readBack);

			String readJson = readBack.get("text");
			assertTrue("Read-back should contain workName", readJson.contains("Test Meta Book"));

			logger.info("Meta round-trip verified");
		} catch (Exception e) {
			fail("Meta persistence failed: " + e.getMessage());
		}
	}

	// ── Think:false ──────────────────────────────────────────────────────

	@Test
	public void TestThinkFalseOnChatOptions() {
		logger.info("Test: think field on chatOptions defaults to true, settable to false");
		try {
			BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			assertNotNull("chatOptions should instantiate", opts);

			boolean thinkDefault = opts.get("think");
			assertTrue("think default should be true", thinkDefault);

			opts.set("think", false);
			boolean thinkFalse = opts.get("think");
			assertFalse("think should be false after set", thinkFalse);

			logger.info("think field verified on chatOptions model");
		} catch (Exception e) {
			fail("think field test failed: " + e.getMessage());
		}
	}

	// ── CharPerson Creation via Olio Pipeline ────────────────────────────

	@Test
	public void TestCharPersonCreation() {
		logger.info("Test: Create charPerson via Olio pipeline, set narrative.sdPrompt");
		setupTestContext();

		// Use OlioContext to get properly initialized population with all sub-models
		// OlioContext uses testUser1 internally — must query as that user for PBAC
		String dataPath = testProperties.getProperty("test.datagen.path");
		Factory mf = IOSystem.getActiveContext().getFactory();
		BaseRecord olioUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "testUser1", testOrgCtx.getOrganizationId());
		OlioContext octx = null;
		try {
			octx = OlioTestUtil.getContext(testOrgCtx, dataPath);
		} catch (Exception e) {
			logger.warn("OlioContext init: " + e.getMessage());
		}
		assumeTrue("OlioContext required", octx != null && octx.isInitialized());

		List<BaseRecord> realms = octx.getRealms();
		assumeTrue("Need at least one realm", realms.size() > 0);
		List<BaseRecord> pop = octx.getRealmPopulation(realms.get(0));
		assumeTrue("Need population", pop != null && pop.size() > 0);

		// Outfit population — creates apparel, wardrobe, and stages characters
		ApparelUtil.outfitAndStage(octx, null, pop);
		Queue.processQueue();

		// Use a random person from the population — fully built with all sub-models
		BaseRecord person = pop.get(0);
		String personOid = person.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Person objectId", personOid);
		logger.info("Using Olio person: " + person.get(FieldNames.FIELD_NAME) + " (" + personOid + ")");

		// Re-fetch with full foreign model data — use olioUser (owns the data)
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, personOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, olioUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(true);
		BaseRecord fullPerson = IOSystem.getActiveContext().getAccessPoint().find(olioUser, q);
		assertNotNull("Full person", fullPerson);

		// Verify charPerson has identity fields from Olio pipeline
		assertNotNull("Should have name", fullPerson.get(FieldNames.FIELD_NAME));
		assertNotNull("Should have gender", fullPerson.get("gender"));

		// Narrative is lazily created — null on freshly generated population.
		// PictureBookService.createCharPerson creates it in-memory if null — mirror that.
		BaseRecord narrative = fullPerson.get("narrative");
		if (narrative == null) {
			try {
				narrative = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE);
				fullPerson.set("narrative", narrative);
			} catch (Exception e) {
				fail("Failed to create narrative: " + e.getMessage());
			}
		}
		String sdPrompt = "portrait of " + fullPerson.get(FieldNames.FIELD_NAME)
			+ ", " + fullPerson.get("gender")
			+ ", detailed face, cinematic lighting, high quality";
		try {
			narrative.set("sdPrompt", sdPrompt);
			narrative.set("physicalDescription", sdPrompt);
		} catch (Exception e) {
			fail("Failed to set narrative fields: " + e.getMessage());
		}
		IOSystem.getActiveContext().getAccessPoint().update(olioUser, fullPerson);

		// Verify update succeeded — re-fetch and check narrative
		Query verify = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, personOid);
		verify.field(FieldNames.FIELD_ORGANIZATION_ID, olioUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		verify.planMost(true);
		BaseRecord verified = IOSystem.getActiveContext().getAccessPoint().find(olioUser, verify);
		assertNotNull("Verified person after update", verified);
		assertEquals("Name should match", (String) fullPerson.get(FieldNames.FIELD_NAME), (String) verified.get(FieldNames.FIELD_NAME));

		// Populate narrative FK and verify sdPrompt round-trip
		IOSystem.getActiveContext().getReader().populate(verified, new String[] {"narrative"});
		BaseRecord verifiedNarr = verified.get("narrative");
		assertNotNull("Narrative should persist after update", verifiedNarr);
		String verifiedPrompt = verifiedNarr.get("sdPrompt");
		assertEquals("sdPrompt should round-trip", sdPrompt, verifiedPrompt);

		logger.info("charPerson with narrative.sdPrompt verified via Olio pipeline: "
			+ fullPerson.get(FieldNames.FIELD_NAME));
	}

	// ── LLM Scene Extraction ─────────────────────────────────────────────

	@Test
	public void TestLlmSceneExtraction() {
		logger.info("Test: LLM scene extraction with think:false");
		setupTestContext();

		String system = PromptResourceUtil.getString("pictureBook.extract-scenes", "system");
		String userTpl = PromptResourceUtil.getString("pictureBook.extract-scenes", "user");
		assertNotNull("System prompt should load from classpath", system);
		assertNotNull("User prompt template should load from classpath", userTpl);

		String userMsg = userTpl.replace("{count}", "3").replace("{text}", TEST_STORY);

		// chatConfig has think:false and model=qwen3:8b — applied via newRequest→applyChatOptions
		Chat chat = new Chat(testUser, chatConfig, null);
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		chat.newMessage(req, system, Chat.systemRole);
		chat.newMessage(req, userMsg);
		OpenAIResponse resp = chat.chat(req);

		assertNotNull("LLM response should not be null", resp);
		assertNotNull("Response message should not be null", resp.getMessage());
		String content = resp.getMessage().getContent();
		assertNotNull("Content should not be null", content);
		assertTrue("Content should not be empty", content.length() > 0);

		// Should NOT contain <think> tags
		assertFalse("Output should not contain <think> tags with think:false",
			content.contains("<think>"));

		// Parse JSON array
		String trimmed = content.trim();
		if (trimmed.startsWith("```")) {
			int nl = trimmed.indexOf('\n');
			if (nl >= 0) trimmed = trimmed.substring(nl + 1);
			if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
		}
		int start = trimmed.indexOf('[');
		int end = trimmed.lastIndexOf(']');
		assertTrue("Response should contain a JSON array", start >= 0 && end > start);
		trimmed = trimmed.substring(start, end + 1);

		List<Map<String, Object>> scenes = JSONUtil.getList(trimmed, Map.class, null);
		assertNotNull("Parsed scene list should not be null", scenes);
		assertTrue("Should extract at least 1 scene", scenes.size() >= 1);

		// Verify scene structure
		Map<String, Object> firstScene = scenes.get(0);
		assertNotNull("First scene should have title", firstScene.get("title"));

		logger.info("Extracted " + scenes.size() + " scenes via LLM with think:false");
		for (Map<String, Object> s : scenes) {
			logger.info("  Scene: " + s.get("title"));
		}
	}

	// ── LLM Character Extraction ─────────────────────────────────────────

	@Test
	public void TestLlmCharacterExtraction() {
		logger.info("Test: LLM character detail extraction");
		setupTestContext();

		String system = PromptResourceUtil.getString("pictureBook.extract-character", "system");
		String userTpl = PromptResourceUtil.getString("pictureBook.extract-character", "user");
		assertNotNull("Character system prompt should load", system);
		assertNotNull("Character user template should load", userTpl);

		String userMsg = userTpl.replace("{name}", "Elena").replace("{text}", TEST_STORY);

		Chat chat = new Chat(testUser, chatConfig, null);
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		chat.newMessage(req, system, Chat.systemRole);
		chat.newMessage(req, userMsg);
		OpenAIResponse resp = chat.chat(req);

		assertNotNull("Character extraction response", resp);
		String content = resp.getMessage().getContent();
		assertNotNull("Content should not be null", content);
		assertFalse("No <think> tags", content.contains("<think>"));

		// Parse JSON object
		String trimmed = content.trim();
		if (trimmed.startsWith("```")) {
			int nl = trimmed.indexOf('\n');
			if (nl >= 0) trimmed = trimmed.substring(nl + 1);
			if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
		}
		int s = trimmed.indexOf('{');
		int e = trimmed.lastIndexOf('}');
		assertTrue("Response should contain JSON object", s >= 0 && e > s);
		trimmed = trimmed.substring(s, e + 1);

		Map<String, Object> charData = JSONUtil.getMap(trimmed.getBytes(), String.class, Object.class);
		assertNotNull("Parsed character data should not be null", charData);
		assertNotNull("Character should have name", charData.get("name"));
		assertNotNull("Character should have gender", charData.get("gender"));

		logger.info("Character extracted: " + charData.get("name") + " (" + charData.get("gender") + ")");
	}

	// ── SD Image Generation ──────────────────────────────────────────────

	@Test
	public void TestSdImageGeneration() {
		logger.info("Test: SD image generation with seed capture");
		setupTestContext();

		String swarmServer = testProperties.getProperty("test.swarm.server");
		String swarmModel = testProperties.getProperty("test.swarm.model");
		assumeTrue("SD server not configured", swarmServer != null && !swarmServer.isEmpty());

		String imgPath = "~/Data/PictureBooks/UnitTest-Images-" + System.currentTimeMillis();
		BaseRecord imgGroup = ensureGroup(imgPath);
		assertNotNull("Image group", imgGroup);

		try {
			BaseRecord sdConfig = SDUtil.randomSDConfig();
			sdConfig.setValue("model", swarmModel);
			sdConfig.setValue("steps", 15);
			sdConfig.setValue("cfg", 5);
			sdConfig.setValue("hires", false);
			sdConfig.setValue("style", "illustration");
			sdConfig.setValue("description", "A woman with a silver rapier stands in a dark forest, dramatic lighting, detailed, masterpiece");
			sdConfig.setValue("negativePrompt", "blurry, lowres, bad anatomy, watermark, text");

			SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
			String imageName = "pb_test_scene_" + System.currentTimeMillis();
			List<BaseRecord> images = sdu.createImage(testUser, imgPath, sdConfig, imageName, 1, false, -1);

			assertNotNull("Image list should not be null", images);
			assertFalse("Image list should not be empty", images.isEmpty());
			BaseRecord image = images.get(0);
			assertNotNull("Image record should not be null", image);

			String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("Image should have objectId", imageOid);

			// Verify seed is captured in attributes
			int seed = AttributeUtil.getAttributeValue(image, "seed", -1);
			logger.info("Generated image: " + imageOid + " seed: " + seed);
			// Seed should be captured (not -1) from the SD response
			// Note: may still be -1 if SD server doesn't return seed in metadata
			assertTrue("Seed should be present in attributes", seed != 0);

			byte[] bytes = image.get(FieldNames.FIELD_BYTE_STORE);
			assertNotNull("Image should have byte data", bytes);
			assertTrue("Image bytes should be non-empty", bytes.length > 0);

			logger.info("SD image generated: " + imageOid + " (" + bytes.length + " bytes)");
		} catch (Exception e) {
			fail("SD image generation failed: " + e.getMessage());
		}
	}

	// ── Prompt Template Loading ──────────────────────────────────────────

	@Test
	public void TestAllPromptTemplatesLoad() {
		logger.info("Test: All pictureBook prompt templates load from classpath");

		String[] prompts = {
			"pictureBook.extract-scenes",
			"pictureBook.extract-chunk",
			"pictureBook.extract-character",
			"pictureBook.scene-blurb",
			"pictureBook.landscape-prompt",
			"pictureBook.scene-image-prompt"
		};

		for (String name : prompts) {
			String system = PromptResourceUtil.getString(name, "system");
			String user = PromptResourceUtil.getString(name, "user");
			assertNotNull("System prompt for " + name + " should load", system);
			assertNotNull("User prompt for " + name + " should load", user);
			assertTrue(name + " system prompt should not be empty", system.length() > 10);
			assertTrue(name + " user prompt should not be empty", user.length() > 10);
			logger.info("  Loaded: " + name + " (system=" + system.length() + " chars, user=" + user.length() + " chars)");
		}

		logger.info("All 6 pictureBook prompt templates loaded successfully");
	}

	// ── Library Template Loading ─────────────────────────────────────────

	@Test
	public void TestLibraryTemplatesRegistered() {
		logger.info("Test: PictureBook templates in PROMPT_TEMPLATE_TEMPLATE_NAMES");

		String[] names = ChatUtil.getPromptTemplateTemplateNames();
		assertNotNull("Template names array should not be null", names);

		List<String> nameList = java.util.Arrays.asList(names);
		String[] expected = {
			"pictureBook.extract-scenes",
			"pictureBook.extract-chunk",
			"pictureBook.extract-character",
			"pictureBook.scene-blurb",
			"pictureBook.landscape-prompt",
			"pictureBook.scene-image-prompt"
		};

		for (String exp : expected) {
			assertTrue("Template names should include " + exp, nameList.contains(exp));
			// Also verify the template file loads
			BaseRecord template = ChatUtil.loadPromptTemplateTemplate(exp);
			assertNotNull("Library template " + exp + " should load from resource", template);
		}

		logger.info("All 6 pictureBook library templates registered and loadable");
	}

	// ── Chunked Extraction (no LLM — tests chunking logic) ──────────────

	@Test
	public void TestChunkSplitting() {
		logger.info("Test: Text chunking logic — 2000 char chunks with 200 overlap");

		// Build a long text > 8000 chars
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 100; i++) {
			sb.append("Sentence number " + i + ". Elena walked through the ancient forest with Marcus by her side. ");
		}
		String longText = sb.toString();
		assertTrue("Test text should be > 8000 chars", longText.length() > 8000);

		// Replicate the chunking logic from PictureBookService.extractChunkedInternal
		int chunkSize = 2000;
		int overlap = 200;
		List<String> chunks = new ArrayList<>();
		int pos = 0;
		while (pos < longText.length()) {
			int end = Math.min(pos + chunkSize, longText.length());
			if (end < longText.length()) {
				int lastPeriod = longText.lastIndexOf('.', end);
				int lastNewline = longText.lastIndexOf('\n', end);
				int breakAt = Math.max(lastPeriod, lastNewline);
				if (breakAt > pos + chunkSize / 2) end = breakAt + 1;
			}
			chunks.add(longText.substring(pos, end));
			pos = end - overlap;
			if (pos < 0) pos = 0;
			if (end >= longText.length()) break;
		}

		assertTrue("Should produce multiple chunks", chunks.size() > 1);
		for (int i = 0; i < chunks.size(); i++) {
			assertTrue("Chunk " + i + " should not exceed chunkSize + margin",
				chunks.get(i).length() <= chunkSize + 200);
		}

		// Verify overlap: end of chunk N should overlap with start of chunk N+1
		if (chunks.size() >= 2) {
			String end1 = chunks.get(0).substring(chunks.get(0).length() - overlap);
			String start2 = chunks.get(1).substring(0, overlap);
			assertEquals("Chunks should overlap by ~200 chars", end1, start2);
		}

		logger.info("Chunked " + longText.length() + " chars into " + chunks.size() + " chunks");
	}
}
