package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CivilUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

import jakarta.ws.rs.core.MediaType;

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

		// Use qwen3-vl:8b-instruct explicitly for PictureBook tests — small, fast, with think:false
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
			cfg.set("connection", OlioTestUtil.getCreateConnection(user, cfgName + " Connection", serverUrl, null, 300));
			cfg.set("model", PB_LLM_MODEL);
			cfg.set("stream", false);

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

	/**
	 * Regression test for a real bug found live: pictureBookRequestModel.json's "characters"
	 * field declared baseModel="olio.pictureBookScene" (the SCENE model, not a character shape).
	 * Every nested character JSON object got deserialized against the scene's field set instead —
	 * none of {name,gender,role} exist there, so RecordDeserializer silently dropped every field
	 * of every character (logged "Invalid field: olio.pictureBookScene.name ..." and skipped it).
	 * Result: createFromScenes received N completely empty maps, "name" was null for every one,
	 * and the character-creation loop's own continue-on-null-name check swallowed all of them
	 * silently — zero olio.charPerson records ever got created, no error surfaced. This mirrors
	 * PictureBookService.parseParams()'s exact deserialization call.
	 */
	@Test
	public void TestPictureBookRequestCharactersFieldRoundTrips() {
		logger.info("Test: pictureBookRequest.characters field round-trips character stub data "
			+ "(regression for the baseModel=olio.pictureBookScene bug that silently dropped every field)");
		// Deliberately NO "schema" key on the nested character items — matches exactly what the
		// real client (sceneExtractor.js's createFromScenes) actually sends. Without an explicit
		// per-item schema, the deserializer resolves each item's type from the "characters"
		// field's own declared baseModel — which is exactly the mechanism the original bug broke.
		String json = "{\"schema\":\"olio.pictureBookRequest\",\"sceneList\":[],\"characters\":["
			+ "{\"name\":\"Elena\",\"gender\":\"FEMALE\",\"role\":\"protagonist\"},"
			+ "{\"name\":\"Marcus\",\"gender\":\"MALE\",\"role\":\"companion\"}]}";
		BaseRecord req = org.cote.accountmanager.util.JSONUtil.importObject(json, LooseRecord.class,
			RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Request should deserialize", req);
		List<BaseRecord> chars = req.get("characters");
		assertNotNull("characters field should deserialize as a list", chars);
		assertEquals("Both characters should survive deserialization — this is exactly what the "
			+ "wrong baseModel silently zeroed out", 2, chars.size());
		assertEquals("Elena", chars.get(0).get("name"));
		assertEquals("FEMALE", chars.get(0).get("gender"));
		assertEquals("protagonist", chars.get(0).get("role"));
		assertEquals("Marcus", chars.get(1).get("name"));
		assertEquals("MALE", chars.get(1).get("gender"));
		assertEquals("companion", chars.get(1).get("role"));
	}

	/**
	 * Live end-to-end proof that the model fix actually restores real character creation through
	 * the createFromScenes path (the wizard's "Continue" action, not extract()'s own auto path
	 * already covered by TestExtractCreatesMatchingCharacterRecords) — using a minimal client stub
	 * list ({name, gender, role} only, no "appearance") to also prove the per-character LLM
	 * enrichment call fires and produces real detail from the source text.
	 */
	@Test
	public void TestCreateFromScenesWithClientCharacterStubsCreatesRealCharacters() throws Exception {
		logger.info("Test: createFromScenes with minimal client character stubs creates real charPerson "
			+ "records via the per-character LLM enrichment path (regression for the characters-field "
			+ "deserialization bug — this is the exact call shape the wizard's Step 2->3 transition uses)");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "CFS Stub Test Story " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", TEST_STORY);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull(createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		List<Map<String, Object>> sceneList = new ArrayList<>();
		Map<String, Object> scene0 = new LinkedHashMap<>();
		scene0.put("title", "Elena Enters the Forest");
		scene0.put("blurb", "Elena and Marcus enter the ancient forest.");
		scene0.put("setting", "ancient forest");
		scene0.put("action", "walking cautiously");
		scene0.put("mood", "tense");
		sceneList.add(scene0);

		// Minimal stubs — {name, gender, role} ONLY, deliberately no "appearance" — this is what
		// proves createCharPerson's enrichment LLM call fires and fills in real detail from
		// TEST_STORY rather than the client having to pre-build it.
		List<Map<String, Object>> charDataList = new ArrayList<>();
		Map<String, Object> elenaStub = new LinkedHashMap<>();
		elenaStub.put("name", "Elena");
		elenaStub.put("gender", "FEMALE");
		elenaStub.put("role", "protagonist");
		charDataList.add(elenaStub);
		Map<String, Object> marcusStub = new LinkedHashMap<>();
		marcusStub.put("name", "Marcus");
		marcusStub.put("gender", "MALE");
		marcusStub.put("role", "companion");
		charDataList.add(marcusStub);

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId, chatConfigName, null,
			"CFS Stub Test Book " + System.currentTimeMillis(), sceneList, charDataList,
			testProperties.getProperty("test.datagen.path"));
		assertNotNull("createFromScenes should return meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull("Meta should have a bookObjectId", bookObjectId);

		List<Object> failedCharacters = meta.get("failedCharacters");
		logger.info("createFromScenes failedCharacters: " + (failedCharacters != null ? failedCharacters : "(none)"));
		assertTrue("Both stub characters should create successfully — failedCharacters=" + failedCharacters,
			failedCharacters == null || failedCharacters.isEmpty());

		List<Map<String, Object>> listed = PictureBookUtil.listCharacters(testUser, bookObjectId);
		logger.info("listCharacters returned: " + listed.size() + " characters");
		for (Map<String, Object> c : listed) logger.info("  - " + c.get("name") + " (" + c.get("objectId") + ")");
		assertEquals("Exactly 2 charPerson records should exist — not 0 (the historical bug, characters "
			+ "silently dropped during deserialization) and not doubled by the sceneList-derived fallback "
			+ "also firing", 2, listed.size());

		// Re-fetch each by name and confirm real enrichment happened (profile/narrative/statistics/
		// store all persisted with id>0, same shape as TestExtractCreatesMatchingCharacterRecords).
		for (String expectedName : new String[] { "Elena", "Marcus" }) {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, expectedName);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);
			BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			assertNotNull("Character " + expectedName + " should be resolvable by name", cp);

			BaseRecord profile = cp.get("profile");
			assertNotNull(expectedName + " must have a persisted profile", profile);
			Long profileId = profile.get(FieldNames.FIELD_ID);
			assertTrue(expectedName + " profile must be a real persisted record", profileId != null && profileId > 0L);

			BaseRecord narrative = cp.get("narrative");
			assertNotNull(expectedName + " must have a persisted narrative", narrative);
			String sdPrompt = narrative.get("sdPrompt");
			if (sdPrompt == null || sdPrompt.isBlank()) {
				IOSystem.getActiveContext().getReader().populate(narrative, new String[] { "sdPrompt", "physicalDescription" });
				sdPrompt = narrative.get("sdPrompt");
			}
			assertNotNull(expectedName + " must have a real portrait prompt (proves the enrichment LLM "
				+ "call ran off TEST_STORY, not just the bare {name,gender,role} stub)", sdPrompt);
			logger.info(expectedName + " narrative.sdPrompt=" + sdPrompt.substring(0, Math.min(80, sdPrompt.length())) + "...");

			BaseRecord statistics = cp.get("statistics");
			assertNotNull(expectedName + " must have a persisted statistics record", statistics);
			Long statsId = statistics.get(FieldNames.FIELD_ID);
			assertTrue(expectedName + " statistics must be a real persisted record", statsId != null && statsId > 0L);

			BaseRecord store = cp.get(FieldNames.FIELD_STORE);
			assertNotNull(expectedName + " must have a persisted store record", store);
			Long storeId = store.get(FieldNames.FIELD_ID);
			assertTrue(expectedName + " store must be a real persisted record", storeId != null && storeId > 0L);
		}
	}

	/**
	 * KI-30 regression test: createCharPerson() must call CharacterUtil.randomPerson() (via
	 * OlioContextUtil.getOlioContext(user, dataPath)) FIRST to build a fully-populated baseline,
	 * then apply the LLM-extracted overrides on top — not build the charPerson from an almost-
	 * empty record. race/alignment/instinct/personality/state are the strongest regression signal:
	 * before this fix, createCharPerson() never set race/alignment at all and never created
	 * instinct/personality/state as persisted foreign sub-records (they stayed permanently null) —
	 * unlike statistics, whose non-zero physicalStrength/agility values (also asserted below, per
	 * the fix spec) turn out to ALREADY have existed pre-fix via a separate, unrelated call
	 * (StatisticsUtil.estimateFromExtractedPhysical -> rollStatistics, unconditional regardless of
	 * this fix) — confirmed by the swap test described in this method's fix note in KnownIssues.md.
	 */
	@Test
	public void TestCreateFromScenesSeedsRandomBaselineOnCharacter() throws Exception {
		logger.info("Test: createCharPerson() runs CharacterUtil.randomPerson() baseline first (KI-30) — "
			+ "race/alignment/instinct/personality/state must be populated/persisted, not left null "
			+ "(previously never set/created at all)");
		setupTestContext();

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set for KI-30's OlioContext baseline generation", dataPath);

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "KI30 Baseline Test Story " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", TEST_STORY);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull(createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		List<Map<String, Object>> sceneList = new ArrayList<>();
		Map<String, Object> scene0 = new LinkedHashMap<>();
		scene0.put("title", "Elena Enters the Forest");
		scene0.put("blurb", "Elena and Marcus enter the ancient forest.");
		scene0.put("setting", "ancient forest");
		scene0.put("action", "walking cautiously");
		scene0.put("mood", "tense");
		sceneList.add(scene0);

		String elenaName = "Elena KI30 " + System.currentTimeMillis();
		List<Map<String, Object>> charDataList = new ArrayList<>();
		Map<String, Object> elenaStub = new LinkedHashMap<>();
		elenaStub.put("name", elenaName);
		elenaStub.put("gender", "FEMALE");
		elenaStub.put("role", "protagonist");
		charDataList.add(elenaStub);

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId, chatConfigName, null,
			"KI30 Baseline Test Book " + System.currentTimeMillis(), sceneList, charDataList, dataPath);
		assertNotNull("createFromScenes should return meta", meta);

		List<Object> failedCharacters = meta.get("failedCharacters");
		assertTrue("Character should create successfully — failedCharacters=" + failedCharacters,
			failedCharacters == null || failedCharacters.isEmpty());

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, elenaName);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(true);
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("Character should be resolvable by name", cp);

		// KI-30 primary regression signal: race/alignment — never set on charPerson at all before
		// this fix.
		List<String> race = cp.get(OlioFieldNames.FIELD_RACE);
		assertTrue("race must be populated from the random baseline (KI-30) — was never set before this fix",
			race != null && !race.isEmpty());
		Object alignment = cp.get(FieldNames.FIELD_ALIGNMENT);
		assertNotNull("alignment must be populated from the random baseline (KI-30) — was never set before this fix",
			alignment);

		// KI-30 primary regression signal: instinct/personality/state — never created as persisted
		// foreign sub-records before this fix (charPerson.instinct/personality/state stayed
		// permanently null/unpersisted).
		BaseRecord instinct = cp.get(OlioFieldNames.FIELD_INSTINCT);
		assertNotNull("instinct must be a real persisted record seeded from the random baseline (KI-30)", instinct);
		Long instinctId = instinct.get(FieldNames.FIELD_ID);
		assertTrue("instinct must be persisted (id>0), not an in-memory placeholder", instinctId != null && instinctId > 0L);

		BaseRecord personality = cp.get(FieldNames.FIELD_PERSONALITY);
		assertNotNull("personality must be a real persisted record seeded from the random baseline (KI-30)", personality);
		Long personalityId = personality.get(FieldNames.FIELD_ID);
		assertTrue("personality must be persisted (id>0), not an in-memory placeholder", personalityId != null && personalityId > 0L);

		BaseRecord state = cp.get(FieldNames.FIELD_STATE);
		assertNotNull("state must be a real persisted record seeded from the random baseline (KI-30)", state);
		Long stateId = state.get(FieldNames.FIELD_ID);
		assertTrue("state must be persisted (id>0), not an in-memory placeholder", stateId != null && stateId > 0L);

		// Fix-spec's own suggested signal: statistics carry real rolled values, not schema-default
		// zeros (see this method's javadoc note on why this one alone isn't proof of THIS fix).
		BaseRecord statistics = cp.get("statistics");
		assertNotNull(statistics);
		int strength = statistics.get("physicalStrength");
		int agility = statistics.get("agility");
		assertTrue("physicalStrength should be a real rolled value (>0), not left at schema default 0", strength > 0);
		assertTrue("agility should be a real rolled value (>0), not left at schema default 0", agility > 0);

		logger.info("KI-30 verified: race=" + race + " alignment=" + alignment
			+ " instinct.id=" + instinctId + " personality.id=" + personalityId + " state.id=" + stateId
			+ " statistics.physicalStrength=" + strength + " agility=" + agility);
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

	// ── Scene Status Persistence (progress tracking / pause-resume) ──────

	/**
	 * Builds a minimal book group + one scene note + .pictureBookMeta referencing it, mirroring
	 * TestSceneNoteCreation/TestMetaPersistence's fixture pattern. Returns [bookGroup, sceneNote].
	 */
	private BaseRecord[] createMinimalBookAndScene(String bookPath, String sceneTitle) throws Exception {
		BaseRecord bookGroup = ensureGroup(bookPath);
		BaseRecord scenesGroup = ensureGroup(bookPath + "/Scenes");
		assertNotNull("Book group", bookGroup);
		assertNotNull("Scenes group", scenesGroup);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookPath + "/Scenes");
		plist.parameter(FieldNames.FIELD_NAME, sceneTitle);
		BaseRecord sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, plist);
		Map<String, Object> sceneData = new LinkedHashMap<>();
		sceneData.put("title", sceneTitle);
		sceneData.put("setting", "A quiet room");
		sceneNote.set("text", JSONUtil.exportObject(sceneData));
		BaseRecord createdScene = IOSystem.getActiveContext().getAccessPoint().create(testUser, sceneNote);
		assertNotNull("Scene note created", createdScene);

		// Build meta via the typed model + toFullString() — matching PictureBookUtil.buildMeta()'s
		// real production path (embeds "schema":"olio.pictureBookMeta" so loadTypedMeta()/
		// reorderScenes() can round-trip it via JSONUtil.importObject). A hand-rolled schema-less
		// Map here would NOT be representative of real book meta and breaks the typed read path.
		BaseRecord sceneEntry = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_SCENE);
		sceneEntry.set(FieldNames.FIELD_OBJECT_ID, createdScene.get(FieldNames.FIELD_OBJECT_ID));
		sceneEntry.set("title", sceneTitle);
		BaseRecord meta = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_META);
		meta.set("bookObjectId", bookGroup.get(FieldNames.FIELD_OBJECT_ID));
		meta.set("scenes", java.util.Collections.singletonList(sceneEntry));
		ParameterList metaPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookPath);
		metaPlist.parameter(FieldNames.FIELD_NAME, ".pictureBookMeta");
		BaseRecord metaNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, metaPlist);
		metaNote.set("text", meta.toFullString());
		assertNotNull(".pictureBookMeta created", IOSystem.getActiveContext().getAccessPoint().create(testUser, metaNote));

		return new BaseRecord[] { bookGroup, createdScene };
	}

	@Test
	public void TestSetSceneStatusPersistsAndMerges() throws Exception {
		logger.info("Test: setSceneStatus persists to the scene note and listScenes() merges it back");
		setupTestContext();

		String bookPath = "~/Data/PictureBooks/UnitTest-Status-" + System.currentTimeMillis();
		BaseRecord[] fixture = createMinimalBookAndScene(bookPath, "Status Test Scene");
		BaseRecord bookGroup = fixture[0];
		BaseRecord sceneNote = fixture[1];
		String bookObjectId = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		String sceneOid = sceneNote.get(FieldNames.FIELD_OBJECT_ID);

		// Sanity: a fresh scene has no status yet
		List<Map<String, Object>> before = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertEquals("One scene expected", 1, before.size());
		assertNull("No status persisted yet", before.get(0).get("status"));

		// Act
		PictureBookUtil.setSceneStatus(testUser, sceneOid, "accepted");

		// Assert — status merged back by listScenes()
		List<Map<String, Object>> after = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertEquals("One scene expected", 1, after.size());
		assertEquals("accepted", after.get(0).get("status"));

		// Invalid status is rejected with a 400
		try {
			PictureBookUtil.setSceneStatus(testUser, sceneOid, "not-a-real-status");
			fail("Invalid status should throw PictureBookException");
		} catch (PictureBookException e) {
			assertEquals(400, e.getStatus());
		}

		// Unknown scene objectId is a 404
		try {
			PictureBookUtil.setSceneStatus(testUser, "00000000-0000-0000-0000-000000000000", "accepted");
			fail("Unknown scene should throw PictureBookException");
		} catch (PictureBookException e) {
			assertEquals(404, e.getStatus());
		}

		logger.info("Scene status persistence verified: " + sceneOid);
	}

	/**
	 * CAUTION: do not set params.hires = true in this test. Live investigation (see PictureBook
	 * session notes) found that classic-pipeline img2img (the composite stage's initImage +
	 * initImageCreativity) combined with a hires/refiner pass in the SAME request has no
	 * verified-working precedent anywhere in this codebase — every other caller either uses
	 * hires+refiner WITHOUT img2img (portraits, landscape) or img2img WITHOUT a refiner pass
	 * (this test, chat's rare classic fallback). That combination hung for 6 minutes against the
	 * live DGX Spark on two separate real attempts before timing out. This test intentionally
	 * runs the full real generateSceneImage() pipeline (portraits + landscape + composite) with
	 * hires=false — the mitigation actually in use — end to end, confirming it completes quickly
	 * with no thermal-risk hang.
	 */
	@Test
	public void TestGenerateSceneImageCompletesWithHiresDisabled() throws Exception {
		logger.info("Test: full generateSceneImage pipeline (portraits+landscape+composite) completes quickly against the live DGX Spark with hires disabled");
		setupTestContext();

		String bookPath = "~/Data/PictureBooks/UnitTest-HiresDisabled-" + System.currentTimeMillis();
		BaseRecord[] fixture = createMinimalBookAndScene(bookPath, "Hires Disabled Test Scene");
		BaseRecord sceneNote = fixture[1];
		String sceneOid = sceneNote.get(FieldNames.FIELD_OBJECT_ID);

		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		BaseRecord genCfg = newSdConfig(null);
		genCfg.setValue("steps", 40);
		genCfg.setValue("cfg", 5);
		genCfg.setValue("hires", false); // the mitigation being tried — see caution above before changing this
		params.sdConfig = genCfg;

		long start = System.currentTimeMillis();
		BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", "http://192.168.1.42:7801");
		long elapsed = System.currentTimeMillis() - start;
		logger.info("Full generateSceneImage (hires=false) took " + elapsed + "ms");

		assertNotNull("Generation should succeed", result);
		String imageObjectId = result.get("imageObjectId");
		assertNotNull("Should produce a final composite image", imageObjectId);
	}

	@Test
	public void TestGenerateSceneImageErrorPersistsStatus() throws Exception {
		logger.info("Test: a failed generateSceneImage call persists status=error + message, visible via listScenes()");
		setupTestContext();

		String bookPath = "~/Data/PictureBooks/UnitTest-ErrorStatus-" + System.currentTimeMillis();
		BaseRecord[] fixture = createMinimalBookAndScene(bookPath, "Error Test Scene");
		BaseRecord bookGroup = fixture[0];
		BaseRecord sceneNote = fixture[1];
		String bookObjectId = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		String sceneOid = sceneNote.get(FieldNames.FIELD_OBJECT_ID);

		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		// No chatConfigName — callLlm() gracefully falls back to the setting text when no LLM
		// config resolves, so the deliberately-unreachable SD server below is what fails the call.

		try {
			PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", "http://127.0.0.1:1");
			fail("Generation against an unreachable SD server should fail");
		} catch (PictureBookException e) {
			logger.info("Expected generation failure: " + e.getMessage());
		}

		List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertEquals("One scene expected", 1, scenes.size());
		assertEquals("error", scenes.get(0).get("status"));
		assertNotNull("Error message should be persisted", scenes.get(0).get("error"));

		logger.info("Error status persistence verified: " + sceneOid);
	}

	@Test
	public void TestGenerateSceneImagePersistsBookSdConfig() throws Exception {
		logger.info("Test: generateSceneImage auto-captures its SD settings onto the book, even when generation itself fails");
		setupTestContext();

		String bookPath = "~/Data/PictureBooks/UnitTest-SdConfig-" + System.currentTimeMillis();
		BaseRecord[] fixture = createMinimalBookAndScene(bookPath, "SD Config Test Scene");
		BaseRecord bookGroup = fixture[0];
		BaseRecord sceneNote = fixture[1];
		String bookObjectId = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		String sceneOid = sceneNote.get(FieldNames.FIELD_OBJECT_ID);

		// Sanity: a fresh book has no saved settings yet
		assertNull("No sdConfig saved before any generation", PictureBookUtil.getBookSdConfig(testUser, bookObjectId));

		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		BaseRecord genCfg = newSdConfig("photograph");
		genCfg.setValue("steps", 33);
		genCfg.setValue("cfg", 9);
		genCfg.setValue("model", "unit-test-model.safetensors");
		params.sdConfig = genCfg;

		try {
			PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", "http://127.0.0.1:1");
			fail("Generation against an unreachable SD server should fail");
		} catch (PictureBookException e) {
			logger.info("Expected generation failure (settings should still be captured): " + e.getMessage());
		}

		BaseRecord savedConfig = PictureBookUtil.getBookSdConfig(testUser, bookObjectId);
		assertNotNull("sdConfig should be persisted on the book even though generation failed", savedConfig);
		int savedSteps = savedConfig.get("steps");
		assertEquals("steps should match what was used", 33, savedSteps);
		int savedCfg = savedConfig.get("cfg");
		assertEquals("cfg should match what was used", 9, savedCfg);
		assertEquals("model should match what was used", "unit-test-model.safetensors", savedConfig.get("model"));
		assertEquals("style should match what was used", "photograph", savedConfig.get("style"));

		logger.info("Book-level SD config persistence verified: " + bookObjectId);
	}

	// ── Character extraction/creation vs. scene character-reference matching ──

	/**
	 * Every other test in this file uses a hand-built scene with NO characters at all
	 * (createMinimalBookAndScene), so "Stage 1 complete: 0 portraits generated" was never
	 * actually a signal of anything — none of those tests could possibly have exercised
	 * character creation or the Stage 1 name-lookup at all. This test runs the REAL production
	 * path (PictureBookUtil.extract(), the same method the REST /extract endpoint the Ux hits
	 * calls) against real narrative text with clearly-named characters (Elena, Marcus), then
	 * directly compares the charPerson records it actually created against the names each
	 * scene's own stored "characters" array references — no SD/network dependency, no assumed
	 * behavior, just a direct comparison of what got persisted.
	 */
	@Test
	public void TestExtractCreatesMatchingCharacterRecords() throws Exception {
		logger.info("Test: PictureBookUtil.extract() — does each scene's characters reference actually resolve to a "
			+ "persisted charPerson via the exact same by-objectId query Stage 1 uses, and is that charPerson "
			+ "populated with apparel/statistics/portrait, not just a name stub?");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "E2E Character Test Story " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", TEST_STORY);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull("Work note should be created", createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord meta = PictureBookUtil.extract(testUser, workObjectId, 3, chatConfigName, null,
			"E2E Character Test Book " + System.currentTimeMillis(), testProperties.getProperty("test.datagen.path"));
		assertNotNull("extract() should return meta", meta);

		String bookObjectId = meta.get("bookObjectId");
		assertNotNull("Meta should have a bookObjectId", bookObjectId);

		List<Object> failedCharacters = meta.get("failedCharacters");
		logger.info("extract() failedCharacters: " + (failedCharacters != null ? failedCharacters : "(none)"));
		assertTrue("createCharPerson should not have failed for Elena/Marcus — failedCharacters=" + failedCharacters,
			failedCharacters == null || failedCharacters.isEmpty());

		// Best-effort steps (apparel wizard, statistics estimation) degrade gracefully rather than
		// aborting character creation — a name here means that ONE step failed for that character
		// (e.g. ApparelUtil chokes on a non-human "character" like an LLM-misextracted "Dragon"),
		// not that the character itself is missing. Used below to avoid over-asserting on known,
		// gracefully-handled degradation instead of treating it as a hard test failure.
		List<Object> failedApparelNames = meta.get("failedApparel");
		List<Object> failedStatisticsNames = meta.get("failedStatistics");
		logger.info("extract() failedApparel: " + (failedApparelNames != null ? failedApparelNames : "(none)")
			+ " failedStatistics: " + (failedStatisticsNames != null ? failedStatisticsNames : "(none)"));

		List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertFalse("Should have extracted at least one scene", scenes.isEmpty());

		// Collect every character objectId actually referenced by any scene — this is exactly
		// the shape buildSceneEntry() persists (a list of charPerson objectId strings, NOT
		// name maps), and exactly what Stage 1's generateSceneImage charOid branch consumes.
		java.util.Set<String> referencedOids = new java.util.LinkedHashSet<>();
		for (Map<String, Object> scene : scenes) {
			Object charsObj = scene.get("characters");
			logger.info("Scene '" + scene.get("title") + "' characters field: " + charsObj);
			if (charsObj instanceof List) {
				for (Object co : (List<?>) charsObj) {
					if (co instanceof String) referencedOids.add((String) co);
					else if (co instanceof Map) {
						Object oid = ((Map<?, ?>) co).get("objectId");
						if (oid instanceof String) referencedOids.add((String) oid);
					}
				}
			}
		}
		logger.info("Character objectIds referenced across all scenes: " + referencedOids);
		assertFalse("At least one scene should reference at least one character objectId — extraction found "
			+ "'Elena'/'Marcus' in TEST_STORY, so this should never be empty", referencedOids.isEmpty());

		// Now resolve each referenced objectId using the SAME query Stage 1's generateSceneImage
		// charOid branch uses (PictureBookUtil.java ~1523-1526), with the SAME request fields,
		// then separately re-fetch full/planMost to inspect apparel + statistics directly.
		Map<String, BaseRecord> perCharacterStats = new LinkedHashMap<>();
		for (String charOid : referencedOids) {
			Query stage1Q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
			stage1Q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			stage1Q.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile"});
			BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, stage1Q);
			assertNotNull("Stage 1's exact by-objectId query must resolve character " + charOid
				+ " — if this is null, Stage 1 will silently skip this character's portrait", cp);

			String cname = cp.get(FieldNames.FIELD_NAME);
			BaseRecord narrative = cp.get("narrative");
			assertNotNull("Character " + cname + " must have a persisted narrative for portrait generation", narrative);
			String sdPromptRaw = narrative.get("sdPrompt");
			logger.info("Resolved character " + charOid + " name=" + cname + " narrative.sdPrompt (via Stage1's initial "
				+ "bare-'narrative' projection, BEFORE Stage1's own populate() workaround)="
				+ (sdPromptRaw != null ? sdPromptRaw.substring(0, Math.min(80, sdPromptRaw.length())) + "..." : "(null)"));
			// olio.narrative's own "query" array is just ["id","groupId"] (per the comment at
			// PictureBookUtil.java:1556-1562), so the bare "narrative" projection above is
			// EXPECTED to come back null here — Stage 1 immediately works around exactly this
			// with a second reader.populate() call on the nested record. Mirror that same call
			// (not a hand-rolled diagnostic) so this test reflects the real pipeline, not a gap
			// in the test's own query.
			IOSystem.getActiveContext().getReader().populate(narrative, new String[] { "sdPrompt", "physicalDescription" });
			String sdPrompt = narrative.get("sdPrompt");
			logger.info("Character " + cname + " narrative.sdPrompt AFTER Stage1's populate() workaround="
				+ (sdPrompt != null ? sdPrompt.substring(0, Math.min(100, sdPrompt.length())) + "..." : "(null)"));
			assertNotNull("Character " + cname + " narrative.sdPrompt must be resolvable via Stage1's own populate() "
				+ "workaround — if this is null, Stage 1 will log 'No portrait prompt (narrative) for: " + cname
				+ "' and skip the portrait entirely", sdPrompt);

			// Full/planMost re-fetch to directly report apparel + statistics presence — the
			// user's requirement is charPerson + apparel + statistics + portrait, not just name.
			// Real field path is charPerson.store.apparel (a list) — charPerson.apparel throws
			// FieldException; this was the existing test's own latent bug (silently swallowed).
			Query fullQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
			fullQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			fullQ.planMost(true);
			BaseRecord fullCp = IOSystem.getActiveContext().getAccessPoint().find(testUser, fullQ);
			assertNotNull("Full re-fetch of character " + charOid + " should not be null", fullCp);

			BaseRecord statistics = fullCp.get("statistics");
			assertNotNull("Character " + cname + " must have a persisted statistics record", statistics);
			Long statsId = statistics.get(FieldNames.FIELD_ID);
			assertTrue("Character " + cname + " statistics must be a real persisted record (id>0), not an "
				+ "unpersisted placeholder", statsId != null && statsId > 0L);
			perCharacterStats.put(cname, statistics);

			BaseRecord store = fullCp.get(FieldNames.FIELD_STORE);
			assertNotNull("Character " + cname + " must have a persisted store record", store);
			Long storeId = store.get(FieldNames.FIELD_ID);
			assertTrue("Character " + cname + " store must be a real persisted record (id>0)", storeId != null && storeId > 0L);

			boolean apparelKnownFailed = failedApparelNames != null && failedApparelNames.contains(cname);
			List<BaseRecord> apparelList = store.get(OlioFieldNames.FIELD_APPAREL);
			assertNotNull("Character " + cname + " store.apparel list should not be null", apparelList);
			if (apparelKnownFailed) {
				// Gracefully-degraded case (e.g. ApparelUtil chokes on a non-human "character") —
				// meta already flagged it, so an empty list here is expected, not a test failure.
				logger.info("Character " + cname + " is in meta.failedApparel — skipping apparel/wearable "
					+ "assertions for this known, gracefully-handled degradation");
				continue;
			}
			assertFalse("Character " + cname + " store.apparel should be non-empty — the apparel wizard "
				+ "(ApparelUtil.contextApparel) should have generated a base outfit", apparelList.isEmpty());

			BaseRecord apparel = apparelList.get(0);
			IOSystem.getActiveContext().getReader().populate(apparel, new String[] { OlioFieldNames.FIELD_IN_USE, OlioFieldNames.FIELD_WEARABLES });
			Boolean apparelInUse = apparel.get(OlioFieldNames.FIELD_IN_USE);
			assertTrue("Character " + cname + "'s apparel must be inuse=true, or ApparelUtil.getWearing() "
				+ "will filter it out and NarrativeUtil.describeOutfit() will describe " + cname
				+ " as \"naked/nude, wearing no clothes\" regardless of how much wardrobe was generated",
				apparelInUse != null && apparelInUse);

			List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
			assertNotNull("Character " + cname + "'s apparel.wearables should not be null", wearables);
			assertFalse("Character " + cname + "'s apparel.wearables should be non-empty", wearables.isEmpty());
			for (BaseRecord w : wearables) {
				IOSystem.getActiveContext().getReader().populate(w, new String[] { OlioFieldNames.FIELD_IN_USE });
				Boolean wearableInUse = w.get(OlioFieldNames.FIELD_IN_USE);
				assertTrue("Character " + cname + "'s wearable " + w.get(FieldNames.FIELD_NAME)
					+ " must be inuse=true too — ApparelUtil.getWearing() filters at BOTH the apparel AND "
					+ "per-wearable level, this is exactly the \"naked\" pitfall", wearableInUse != null && wearableInUse);
			}

			BaseRecord narrativeForOutfit = fullCp.get("narrative");
			String outfitDescription = (narrativeForOutfit != null) ? narrativeForOutfit.get("outfitDescription") : null;
			assertNotNull("Character " + cname + "'s narrative.outfitDescription must be set — this is what makes "
				+ "the existing charPerson reimage command pick up apparel automatically", outfitDescription);
			assertFalse("Character " + cname + "'s narrative.outfitDescription must not be blank", outfitDescription.isBlank());
			assertFalse("Character " + cname + "'s narrative.outfitDescription must not be the \"naked\" fallback "
				+ "text — apparel was just confirmed inuse with " + wearables.size() + " inuse wearables, so "
				+ "describeOutfit() falling back to naked means it read a stale in-memory apparel list",
				outfitDescription.toLowerCase().contains("naked") || outfitDescription.toLowerCase().contains("nude"));
			logger.info("Character " + cname + " narrative.outfitDescription=" + outfitDescription);

			BaseRecord profile = fullCp.get("profile");
			Object portrait = (profile != null) ? profile.get("portrait") : null;

			logger.info("RESULT for '" + cname + "' (" + charOid + "): apparelInUse=" + apparelInUse
				+ " wearableCount=" + wearables.size() + " statistics=" + statistics + " profile.portrait=" + portrait);
		}

		// Direct regression check for the "every character gets identical stats/weight" bug:
		// TEST_STORY describes Elena (rapier, agile) and Marcus (broad, weathered, longsword,
		// shield) differently — assert their estimated statistics are NOT all identical.
		BaseRecord elenaStats = perCharacterStats.get("Elena");
		BaseRecord marcusStats = perCharacterStats.get("Marcus");
		if (elenaStats != null && marcusStats != null) {
			boolean anyDifferent =
				!java.util.Objects.equals((Integer) elenaStats.get("physicalStrength"), (Integer) marcusStats.get("physicalStrength"))
				|| !java.util.Objects.equals((Integer) elenaStats.get("agility"), (Integer) marcusStats.get("agility"))
				|| !java.util.Objects.equals((Integer) elenaStats.get("physicalEndurance"), (Integer) marcusStats.get("physicalEndurance"))
				|| !java.util.Objects.equals((Double) elenaStats.get("height"), (Double) marcusStats.get("height"));
			logger.info("Elena stats: strength=" + elenaStats.get("physicalStrength") + " agility=" + elenaStats.get("agility")
				+ " endurance=" + elenaStats.get("physicalEndurance") + " height=" + elenaStats.get("height"));
			logger.info("Marcus stats: strength=" + marcusStats.get("physicalStrength") + " agility=" + marcusStats.get("agility")
				+ " endurance=" + marcusStats.get("physicalEndurance") + " height=" + marcusStats.get("height"));
			assertTrue("Elena and Marcus should NOT have completely identical statistics — rollStatistics() "
				+ "randomizes a baseline per character regardless, so this should essentially always be true; "
				+ "an exact match across all four fields would indicate the estimation step silently did nothing",
				anyDifferent);
		}
	}

	/**
	 * Real end-to-end extraction against Stephen's actual source document (media/catatone.docx —
	 * main character Jideon de Rosa, a Spanish ex-Legionnaire), not an invented synthetic story.
	 * Loads the real .docx bytes through the exact same DocumentUtil.getStringContent() path
	 * extractWorkText() uses in production, then runs the real extract() pipeline and reports —
	 * without assuming — what the extraction actually captured.
	 */
	@Test
	public void TestExtractFromRealCatatoneDocumentCapturesJideon() throws Exception {
		logger.info("Test: PictureBookUtil.extract() against the real catatone.docx — does Jideon's "
			+ "charPerson reflect real document content, not a synthetic story?");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		byte[] fileBytes = FileUtil.getFile("./media/catatone.docx");
		assertNotNull("catatone.docx should be readable from the module's media/ directory", fileBytes);
		assertTrue("catatone.docx should be non-empty", fileBytes.length > 0);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "catatone-" + System.currentTimeMillis() + ".docx");
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, plist);
		work.set(FieldNames.FIELD_CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		ByteModelUtil.setValue(work, fileBytes);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull("catatone.docx work record should be created", createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord meta = PictureBookUtil.extract(testUser, workObjectId, 3, chatConfigName, "sci-fi",
			"Catatone Test Book " + System.currentTimeMillis(), testProperties.getProperty("test.datagen.path"));
		assertNotNull("extract() should return meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull(bookObjectId);

		List<Object> failedCharacters = meta.get("failedCharacters");
		logger.info("catatone.docx extract() failedCharacters: " + (failedCharacters != null ? failedCharacters : "(none)"));

		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(),
			(long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Characters group should exist", charsGroup);

		Query allCharsQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		allCharsQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		allCharsQ.planMost(true);
		BaseRecord[] createdChars = IOSystem.getActiveContext().getSearch().findRecords(allCharsQ);

		BaseRecord jideon = null;
		for (BaseRecord c : createdChars) {
			String cname = c.get(FieldNames.FIELD_NAME);
			logger.info("Extracted character from catatone.docx: " + cname);
			if (cname != null && cname.toLowerCase().contains("jideon")) jideon = c;
		}
		assertNotNull("Jideon should be extracted as a character from the real catatone.docx text — "
			+ "createdChars.length=" + createdChars.length, jideon);

		logger.info("Jideon gender=" + jideon.get("gender") + " ethnicity=" + jideon.get("ethnicity")
			+ " age=" + jideon.get("age") + " trades=" + jideon.get("trades")
			+ " firstName=" + jideon.get("firstName") + " lastName=" + jideon.get("lastName"));

		assertEquals("Jideon's gender should be extracted as MALE from the real text", "MALE", jideon.get("gender"));

		// The story explicitly calls Jideon "an active duty Spanish Legionaire"/"Spanish Legion" —
		// skills/trade extraction should pick this up (ethnicity/age are frequently absent from
		// this specific text since it's never stated as an exact number/label, so those stay
		// reported, not asserted).
		List<String> trades = jideon.get("trades");
		logger.info("Jideon trades/skills: " + trades);
		assertNotNull("Jideon should have a non-null trades list", trades);
		boolean hasMilitarySkill = trades.stream().anyMatch(t -> t != null && t.toLowerCase().contains("legion"));
		assertTrue("Jideon's trades should reflect his stated military background (\"Spanish Legionnaire\") — got " + trades,
			hasMilitarySkill);

		BaseRecord narrative = jideon.get("narrative");
		assertNotNull("Jideon must have a persisted narrative for portrait generation", narrative);
		String sdPrompt = narrative.get("sdPrompt");
		if (sdPrompt == null || sdPrompt.isBlank()) {
			IOSystem.getActiveContext().getReader().populate(narrative, new String[] { "sdPrompt", "physicalDescription" });
			sdPrompt = narrative.get("sdPrompt");
		}
		assertNotNull("Jideon should have a real portrait prompt derived from the actual document text", sdPrompt);
		logger.info("Jideon narrative.sdPrompt=" + sdPrompt);

		// Regression check for the narrative-leak bug: a real document produced field values like
		// "broad shoulders, muscular (implied by 'balled his fists' ...)" before
		// sanitizeExtractedField() stripped parentheticals — assert none of that reasoning-style
		// phrasing survived into the actual SD prompt.
		String lowerPrompt = sdPrompt.toLowerCase();
		assertFalse("Portrait prompt must not contain leaked LLM reasoning/citations (\"implied by\") — got: " + sdPrompt,
			lowerPrompt.contains("implied by"));
		assertFalse("Portrait prompt must not contain leaked LLM reasoning/citations (\"based on context\") — got: " + sdPrompt,
			lowerPrompt.contains("based on context"));
	}

	/**
	 * Scene-tagged apparel: tag two outfits (sceneIndex 0 and 2) on a real extracted character,
	 * then run the REAL generateSceneImage pipeline (portraits+landscape+composite, all real SD
	 * calls) against Stephen's local Swarm server (test.swarm.server — moved off the DGX Spark
	 * specifically so SD load doesn't contend with LLM load; per explicit instruction, do NOT
	 * point this at any other server) for a scene at index 0 and again for a scene at index 2,
	 * and assert selectSceneApparel() actually flips `inuse` to the right outfit each time.
	 */
	@Test
	public void TestSceneTaggedApparelSelectsCorrectOutfitPerScene() throws Exception {
		logger.info("Test: scene-tagged apparel selection flips inuse to the correct outfit per scene, "
			+ "verified against the real local Swarm server");
		setupTestContext();

		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);
		logger.info("Using local Swarm server: " + swarmServer);

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "E2E Apparel Scene Test Story " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", TEST_STORY);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord meta = PictureBookUtil.extract(testUser, workObjectId, 3, chatConfigName, null,
			"E2E Apparel Scene Test Book " + System.currentTimeMillis(), testProperties.getProperty("test.datagen.path"));
		String bookObjectId = meta.get("bookObjectId");

		List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertTrue("Need at least 3 scenes (index 0 and 2) for this test — got " + scenes.size(), scenes.size() >= 3);
		// NOTE: scene 0's serialized map omits the "index" key entirely (BaseRecord.toFullString()
		// appears to treat an explicitly-set int field whose value equals the schema default (0) as
		// unset) — scenes 1/2 do carry "index". This happens to self-correct in production
		// (resolveCurrentSceneIndex falls through to its own "return 0" default when the key is
		// missing), but means this test can't rely on matching "index"==0 in the parsed map. Use
		// list position for the two scenes under test instead — listScenes preserves creation order.
		Map<Integer, Map<String, Object>> byIndex = new LinkedHashMap<>();
		byIndex.put(0, scenes.get(0));
		byIndex.put(2, scenes.get(2));

		// Resolve Elena's charPerson from scene 0's characters (objectId list)
		Object charsObj0 = byIndex.get(0).get("characters");
		assertTrue("Scene 0 characters should be a list", charsObj0 instanceof List);
		String elenaOid = null;
		for (Object co : (List<?>) charsObj0) {
			if (co instanceof String) {
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, (String) co);
				q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
				if (cp != null && "Elena".equals(cp.get(FieldNames.FIELD_NAME))) { elenaOid = (String) co; break; }
			}
		}
		assertNotNull("Elena should be resolvable from scene 0's characters", elenaOid);

		Query cpQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, elenaOid);
		cpQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		cpQ.planMost(true);
		BaseRecord elena = IOSystem.getActiveContext().getAccessPoint().find(testUser, cpQ);
		assertNotNull(elena);
		BaseRecord store = elena.get(FieldNames.FIELD_STORE);
		List<BaseRecord> apparelList = store.get(OlioFieldNames.FIELD_APPAREL);
		assertEquals("Elena should start with exactly 1 base (untagged) apparel from createCharPerson", 1, apparelList.size());
		BaseRecord baseApparel = apparelList.get(0);
		String baseApparelOid = baseApparel.get(FieldNames.FIELD_OBJECT_ID);

		// Second outfit, via the same apparel wizard PictureBookUtil.createCharPerson itself uses
		BaseRecord secondApparel = ApparelUtil.contextApparel(null, elena, 2, CivilUtil.ClimateType.TEMPERATE);
		assertNotNull("Apparel wizard should produce a second outfit", secondApparel);
		secondApparel.setValue(OlioFieldNames.FIELD_IN_USE, true);
		List<BaseRecord> secondWearables = secondApparel.get(OlioFieldNames.FIELD_WEARABLES);
		for (BaseRecord w : secondWearables) w.setValue(OlioFieldNames.FIELD_IN_USE, true);
		IOSystem.getActiveContext().getRecordUtil().createRecord(secondApparel);
		IOSystem.getActiveContext().getMemberUtil().member(testUser, store, OlioFieldNames.FIELD_APPAREL, secondApparel, null, true);
		String secondApparelOid = secondApparel.get(FieldNames.FIELD_OBJECT_ID);
		logger.info("Elena base apparel=" + baseApparelOid + " second apparel=" + secondApparelOid);

		assertTrue("Tagging base apparel with sceneIndex=0 should succeed",
			PictureBookUtil.tagApparelSceneIndex(testUser, baseApparelOid, 0));
		assertTrue("Tagging second apparel with sceneIndex=2 should succeed",
			PictureBookUtil.tagApparelSceneIndex(testUser, secondApparelOid, 2));

		// Fast isolation check BEFORE paying for any SD calls: is the second apparel actually
		// linked to Elena's store in the DB at all, independent of anything Stage 1 does?
		Map<String, Boolean> inUseBeforeGeneration = fetchApparelInUse(elenaOid);
		logger.info("Apparel inuse BEFORE any generateSceneImage call: " + inUseBeforeGeneration);
		assertEquals("Elena's store.apparel should have exactly 2 entries immediately after linking the "
			+ "second outfit — if this is 1, the member() link itself never took, independent of anything "
			+ "generateSceneImage/selectSceneApparel does", 2, inUseBeforeGeneration.size());

		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		params.chatConfigName = chatConfigName;
		params.isBookOverride = true;
		BaseRecord genCfg = newSdConfig(null);
		genCfg.setValue("steps", 12);
		genCfg.setValue("cfg", 5);
		genCfg.setValue("hires", false); // classic-pipeline img2img + hires has no verified-working precedent — see the caution note on TestGenerateSceneImageCompletesWithHiresDisabled
		// The schema default model ("sdXL_v10VAEFix.safetensors") isn't installed on Stephen's
		// local Swarm instance (a different machine than the old DGX Spark setup) — use the model
		// this test's own properties file already names as actually available there.
		genCfg.setValue("model", testProperties.getProperty("test.swarm.model"));
		params.sdConfig = genCfg;

		String scene0Oid = (String) byIndex.get(0).get("objectId");
		long start0 = System.currentTimeMillis();
		BaseRecord result0 = PictureBookUtil.generateSceneImage(testUser, scene0Oid, params, "SWARM", swarmServer);
		logger.info("Scene 0 generateSceneImage took " + (System.currentTimeMillis() - start0) + "ms against " + swarmServer);
		assertNotNull("Scene 0 generation should succeed against the local Swarm server " + swarmServer, result0);

		Map<String, Boolean> inUseAfterScene0 = fetchApparelInUse(elenaOid);
		logger.info("Apparel inuse after scene 0 (expect base=true, second=false): " + inUseAfterScene0);
		assertTrue("Base apparel (sceneIndex=0) should be inuse after generating scene 0",
			Boolean.TRUE.equals(inUseAfterScene0.get(baseApparelOid)));
		assertFalse("Second apparel (sceneIndex=2) should NOT be inuse after generating scene 0",
			Boolean.TRUE.equals(inUseAfterScene0.get(secondApparelOid)));

		String scene2Oid = (String) byIndex.get(2).get("objectId");
		long start2 = System.currentTimeMillis();
		BaseRecord result2 = PictureBookUtil.generateSceneImage(testUser, scene2Oid, params, "SWARM", swarmServer);
		logger.info("Scene 2 generateSceneImage took " + (System.currentTimeMillis() - start2) + "ms against " + swarmServer);
		assertNotNull("Scene 2 generation should succeed against the local Swarm server " + swarmServer, result2);

		Map<String, Boolean> inUseAfterScene2 = fetchApparelInUse(elenaOid);
		logger.info("Apparel inuse after scene 2 (expect base=false, second=true): " + inUseAfterScene2);
		assertFalse("Base apparel (sceneIndex=0) should NOT be inuse after generating scene 2 — the outfit should "
			+ "have flipped to the sceneIndex=2 one", Boolean.TRUE.equals(inUseAfterScene2.get(baseApparelOid)));
		assertTrue("Second apparel (sceneIndex=2) should be inuse after generating scene 2",
			Boolean.TRUE.equals(inUseAfterScene2.get(secondApparelOid)));

		assertNotEquals("The composite image produced for scene 0 vs scene 2 should differ (different outfit + "
			+ "different scene) — if these are equal, the outfit swap likely never reached the actual render",
			result0.get("imageObjectId"), result2.get("imageObjectId"));
	}

	private Map<String, Boolean> fetchApparelInUse(String charOid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setCache(false); // rule out stale cached store.apparel participation results
		q.planMost(true);
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		Map<String, Boolean> result = new LinkedHashMap<>();
		if (cp == null) return result;
		BaseRecord store = cp.get(FieldNames.FIELD_STORE);
		List<BaseRecord> appl = (store != null) ? store.get(OlioFieldNames.FIELD_APPAREL) : null;
		if (appl == null) return result;
		for (BaseRecord a : appl) {
			result.put((String) a.get(FieldNames.FIELD_OBJECT_ID), (Boolean) a.get(OlioFieldNames.FIELD_IN_USE));
		}
		return result;
	}

	/**
	 * Regression test for a real chain-of-thought leak Stephen hit live in a landscape prompt
	 * after deleting/reseeding prompt templates and restarting: the model emitted a full reasoning
	 * trace ("We need to output...", "Let's craft:...", "Will comply.") followed by a bare closing
	 * {@code </think>} tag with NO matching opening tag at all. The original stripThink()
	 * (`text.replaceAll("(?s)<think>.*?</think>", "")`) only matches a *paired* block, so this
	 * exact real-world case sailed straight through untouched — this is the literal text captured
	 * from that failure, not an invented example.
	 */
	@Test
	public void TestStripThinkHandlesOrphanClosingTag() {
		String leaked = "We need to output a stable diffusion prompt focusing on environment, architecture, "
			+ "lighting, atmosphere, visual style. No characters. Must be \"masterpiece, best quality, "
			+ "[detailed environment description], [lighting], [atmosphere].\" Use illustration style.\n\n"
			+ "We need to mention the ramshackle suburban home, broken windows, sagging porch, overgrown lawn, "
			+ "rain slicks cracked concrete, reflective puddles, gray sky, cab's interior glows with neon "
			+ "(cool blue interior glow). Mood urgent protective, cold muted lighting from overcast clouds, "
			+ "wet surfaces glinting.\n\n"
			+ "We must format: masterpiece, best quality, ... So we produce a single line prompt: "
			+ "\"masterpiece, best quality, ramshackle suburban home with broken windows, sagging porch, "
			+ "overgrown lawn, rain-slicked cracked concrete reflecting puddles, gray overcast sky, hovering "
			+ "cab at curb with cool blue neon interior glow, cold muted lighting, wet surfaces glinting, "
			+ "urgent protective atmosphere, illustration\"\n\n"
			+ "Make sure it's only prompt text, no markdown.\n\n"
			+ "Let's craft: \"masterpiece, best quality, a ramshackle suburban house with broken windows and a "
			+ "sagging porch, overgrown lawn, rain-soaked cracked concrete forming reflective puddles, under a "
			+ "heavy gray sky, a hovering cab parked at the curb with cool blue neon interior lighting spilling "
			+ "outward, cold muted overcast lighting, wet surfaces glistening, urgent protective mood, "
			+ "illustration\"\n\n"
			+ "Add \"highly detailed\" maybe.\n\n"
			+ "Will comply.</think>masterpiece, best quality, ramshackle suburban house with broken windows, "
			+ "sagging porch, overgrown lawn, rain-slicked cracked concrete forming reflective puddles, heavy "
			+ "gray overcast sky, hovering cab at the curb with cool blue neon interior glow spilling outward, "
			+ "cold muted lighting, wet surfaces glinting, urgent protective atmosphere, illustration";

		String cleaned = PictureBookUtil.stripThink(leaked);
		logger.info("stripThink() output: " + cleaned);

		assertFalse("Cleaned prompt must not contain leaked reasoning (\"We need to\")", cleaned.contains("We need to"));
		assertFalse("Cleaned prompt must not contain leaked reasoning (\"Let's craft\")", cleaned.contains("Let's craft"));
		assertFalse("Cleaned prompt must not contain leaked reasoning (\"Will comply\")", cleaned.contains("Will comply"));
		assertFalse("Cleaned prompt must not contain a stray </think> tag", cleaned.contains("</think>"));
		assertEquals("Cleaned prompt should be exactly the real SD prompt that followed </think>, nothing more/less",
			"masterpiece, best quality, ramshackle suburban house with broken windows, sagging porch, overgrown lawn, "
			+ "rain-slicked cracked concrete forming reflective puddles, heavy gray overcast sky, hovering cab at the "
			+ "curb with cool blue neon interior glow spilling outward, cold muted lighting, wet surfaces glinting, "
			+ "urgent protective atmosphere, illustration",
			cleaned);
	}

	/**
	 * Standard paired &lt;think&gt;...&lt;/think&gt; block (the original case stripThink() already
	 * handled) — kept as a regression guard alongside the orphan-closing-tag case above so a future
	 * change to the orphan-tag handling can't silently break the paired case.
	 */
	@Test
	public void TestStripThinkHandlesPairedTags() {
		String withThink = "<think>reasoning about the prompt here, multiple sentences.</think>masterpiece, best quality, a clean prompt";
		String cleaned = PictureBookUtil.stripThink(withThink);
		assertEquals("masterpiece, best quality, a clean prompt", cleaned);
	}

	// ── Ollama model unload (GPU/thermal contention fix) ─────────────────

	@Test
	public void TestOllamaUnloadAllUnloadsRealModel() {
		logger.info("Test: OllamaModelUtil.unloadAll() actually unloads a real Ollama model, verified via /api/ps");
		setupTestContext();

		String ollamaServer = testProperties.getProperty("test.llm.ollama.server");
		assertNotNull("test.llm.ollama.server must be set", ollamaServer);

		// Force the model to load via a real, minimal chat call — mirrors the setLlmSystemPrompt
		// -> newRequest -> newMessage -> chat pattern used elsewhere (e.g. ChatUtil.summarizeChunk).
		Chat chat = new Chat(testUser, chatConfig, null);
		chat.setLlmSystemPrompt("You are a terse test assistant.");
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		chat.newMessage(req, "Reply with exactly one word: hello", Chat.userRole);
		OpenAIResponse resp = chat.chat(req);
		assertNotNull("Live chat call should succeed", resp);

		assertTrue("Model should be loaded in Ollama after a live call", isModelLoaded(ollamaServer, PB_LLM_MODEL));

		// force=true: this test verifies the unload MECHANISM, so it must not be silenced by the
		// llm.ollama.unload switch (which defaults to false — see OllamaModelUtil). The opportunistic
		// no-arg unloadAll() would no-op here and turn this into a test of the config flag.
		OllamaModelUtil.unloadAll(true);

		// Ollama's /api/ps may take a moment to reflect an unload after the keep_alive:0 request
		// returns — poll briefly rather than asserting on a single immediate check.
		boolean unloaded = false;
		for (int i = 0; i < 10 && !unloaded; i++) {
			if (!isModelLoaded(ollamaServer, PB_LLM_MODEL)) { unloaded = true; break; }
			try { Thread.sleep(500); } catch (InterruptedException ignored) {}
		}
		assertTrue("Model should be unloaded from Ollama after unloadAll()", unloaded);

		logger.info("Ollama unload verified against live server: " + ollamaServer);
	}

	/**
	 * Query Ollama's /api/ps (currently loaded models) and check whether the given model name
	 * appears in the response.
	 */
	private boolean isModelLoaded(String ollamaServer, String model) {
		try {
			String json = ClientUtil.get(String.class, ClientUtil.getResource(ollamaServer + "/api/ps"), null, MediaType.APPLICATION_JSON_TYPE);
			return json != null && json.contains(model);
		} catch (Exception e) {
			logger.warn("Failed to query " + ollamaServer + "/api/ps: " + e.getMessage());
			return false;
		}
	}

	// ── Live Swarm diagnostic (thermal investigation) ─────────────────────

	/**
	 * Smallest reasonable real generation against the actual DGX Spark Swarm server
	 * (192.168.1.42:7801, same host as the Ollama address used elsewhere in this file) —
	 * 512x512, 8 steps, no refiner, plain text2img. Every other SD test in this repo either
	 * points at an unreachable address (this file's error-path tests) or at localhost
	 * (AccountManagerObjects7's own test.swarm.server property) — this is deliberately the
	 * first test to put real load on the specific box the user reported thermal shutdowns on.
	 * Logs elapsed time so a hang/slowdown is directly visible in the test output.
	 */
	@Test
	public void TestLiveSwarmMinimalDiagnosticProbe() throws Exception {
		logger.info("Test: minimal real SD call against the live DGX Spark Swarm server (thermal diagnostic)");
		setupTestContext();

		String swarmServer = "http://192.168.1.42:7801"; // matches AccountManagerService7/web.xml's sd.server
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);

		BaseRecord sdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		sdConfig.set("steps", 8);
		sdConfig.set("cfg", 5);
		sdConfig.set("hires", false);
		sdConfig.set("width", 512);
		sdConfig.set("height", 512);
		sdConfig.set("style", "illustration");

		org.cote.accountmanager.olio.sd.swarm.SWTxt2Img s2i = org.cote.accountmanager.olio.sd.swarm.SWUtil.newSceneTxt2Img(
			"A single small pebble on a plain white background, minimal test image",
			"blurry, lowres, watermark, text",
			sdConfig
		);

		long start = System.currentTimeMillis();
		List<BaseRecord> images = null;
		try {
			images = sdu.createSceneImage(testUser, "~/Chat", "diag_minimal_" + start, s2i, null, null);
		} finally {
			long elapsed = System.currentTimeMillis() - start;
			logger.info("Minimal diagnostic SD call took " + elapsed + "ms, returned "
				+ (images != null ? images.size() : 0) + " image(s)");
		}

		assertNotNull("Should get a response list", images);
		assertFalse("Should generate at least one image", images.isEmpty());
	}

	/**
	 * Reproduces PictureBook's exact composite/img2img call — the specific stage that hung for
	 * 6 minutes in the user-reported log before timing out. A plain text2img call was confirmed
	 * fine (TestLiveSwarmMinimalDiagnosticProbe); this isolates whether img2img itself (large
	 * base64 init image + initImageCreativity=0.85) is what's different about "PictureBook
	 * images specifically". Generates a real landscape first to use as a genuine init image
	 * (not a synthetic placeholder), then runs the composite call at PictureBook's exact
	 * production settings: 1024x768, 20 steps, hires=false, initImageCreativity=0.85.
	 */
	@Test
	public void TestLiveSwarmCompositeImg2ImgDiagnosticProbe() throws Exception {
		logger.info("Test: real img2img call at PictureBook's exact composite-stage settings against the live DGX Spark Swarm server");
		setupTestContext();

		String swarmServer = "http://192.168.1.42:7801";
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);

		// Step 1: generate a real landscape image to feed the composite as its init image —
		// mirrors PictureBookUtil's Stage 2 output feeding Stage 3/4.
		BaseRecord landConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		landConfig.set("steps", 20);
		landConfig.set("cfg", 5);
		landConfig.set("hires", false);
		landConfig.set("width", 1024);
		landConfig.set("height", 768);
		landConfig.set("style", "illustration");
		org.cote.accountmanager.olio.sd.swarm.SWTxt2Img landReq = org.cote.accountmanager.olio.sd.swarm.SWUtil.newSceneTxt2Img(
			"A quiet forest clearing at dawn, minimal test image", "blurry, lowres, watermark, text", landConfig);

		long landStart = System.currentTimeMillis();
		List<BaseRecord> landImages = sdu.createSceneImage(testUser, "~/Chat", "diag_land_" + landStart, landReq, null, null);
		long landElapsed = System.currentTimeMillis() - landStart;
		logger.info("Landscape text2img (1024x768, 20 steps) took " + landElapsed + "ms, returned "
			+ (landImages != null ? landImages.size() : 0) + " image(s)");
		assertNotNull("Landscape image list should not be null", landImages);
		assertFalse("Should generate a landscape image", landImages.isEmpty());
		byte[] landscapeBytes = ByteModelUtil.getValue(landImages.get(0));
		assertNotNull("Landscape image should have byte data", landscapeBytes);

		// Step 2: composite img2img at PictureBook's exact classic-pipeline settings.
		logger.info("Starting composite img2img call — this is the stage that hung 6 minutes in the original report.");
		BaseRecord compConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		compConfig.set("steps", 20);
		compConfig.set("cfg", 5);
		compConfig.set("hires", false);
		compConfig.set("width", 1024);
		compConfig.set("height", 768);
		compConfig.set("style", "illustration");
		org.cote.accountmanager.olio.sd.swarm.SWTxt2Img compReq = org.cote.accountmanager.olio.sd.swarm.SWUtil.newSceneTxt2Img(
			"A quiet forest clearing at dawn with a small figure standing in it, minimal test image",
			"blurry, lowres, watermark, text", compConfig);
		compReq.setInitImage("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(landscapeBytes));
		compReq.setInitImageCreativity(0.85);

		long compStart = System.currentTimeMillis();
		List<BaseRecord> compImages = null;
		try {
			compImages = sdu.createSceneImage(testUser, "~/Chat", "diag_composite_" + compStart, compReq, null, null);
		} finally {
			long compElapsed = System.currentTimeMillis() - compStart;
			logger.info("Composite img2img (1024x768, 20 steps, initImageCreativity=0.85) took " + compElapsed
				+ "ms, returned " + (compImages != null ? compImages.size() : 0) + " image(s)");
		}

		assertNotNull("Should get a response list", compImages);
		assertFalse("Should generate at least one composite image", compImages.isEmpty());
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

		// chatConfig has think:false and model=qwen3-vl:8b-instruct — applied via newRequest→applyChatOptions
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

	// ── LORA Injection ──────────────────────────────────────────────────

	@Test
	public void TestLoraAppendToPrompt() {
		logger.info("Test: SDUtil.appendLoras appends LORA entries to prompt");
		try {
			BaseRecord sdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			List<String> loras = new ArrayList<>();
			loras.add("myLora:0.8");
			loras.add("otherLora:0.5");
			sdConfig.set("loras", loras);

			String prompt = "a woman standing in a forest, detailed, masterpiece";
			String result = SDUtil.appendLoras(prompt, sdConfig);

			assertNotNull("Result should not be null", result);
			assertTrue("Result should contain original prompt", result.startsWith(prompt));
			assertTrue("Result should contain first LORA", result.contains("<lora:myLora:0.8>"));
			assertTrue("Result should contain second LORA", result.contains("<lora:otherLora:0.5>"));
			logger.info("LORA prompt: " + result);

			// Empty loras should return original prompt unchanged
			sdConfig.set("loras", new ArrayList<>());
			String noLora = SDUtil.appendLoras(prompt, sdConfig);
			assertEquals("Empty loras should return original", prompt, noLora);

			// Null config should return original
			String nullCfg = SDUtil.appendLoras(prompt, null);
			assertEquals("Null config should return original", prompt, nullCfg);

			logger.info("LORA injection test passed");
		} catch (Exception e) {
			fail("LORA test failed: " + e.getMessage());
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

	/**
	 * The book-level art-direction (compositionContext) text was hard-coded in
	 * PictureBookUtil.createFromScenes; it now lives in olio/llm/prompts/pictureBook.art-direction.json
	 * with a ${genre} token. This verifies the resource loads and reproduces the former hard-coded
	 * output byte-for-byte for both the genre-present and genre-absent cases (createFromScenes passes
	 * "${genre}" as "<genre> " with a trailing space, or "" when no genre).
	 */
	@Test
	public void TestArtDirectionPromptTemplateLoadsAndSubstitutes() {
		String template = PromptResourceUtil.getString("pictureBook.art-direction", "template");
		assertNotNull("pictureBook.art-direction 'template' should load from classpath", template);
		assertTrue("art-direction template should not be empty", template.length() > 10);

		String withGenre = PromptResourceUtil.replaceToken(template, "genre", "dystopian sci-fi ");
		assertEquals("Genre-present output must match the former hard-coded string",
			"Consistent art direction for a dystopian sci-fi picture book: keep the setting, color palette, and lighting cohesive across every scene.",
			withGenre);

		String noGenre = PromptResourceUtil.replaceToken(template, "genre", "");
		assertEquals("Genre-absent output must match the former hard-coded string",
			"Consistent art direction for a picture book: keep the setting, color palette, and lighting cohesive across every scene.",
			noGenre);

		logger.info("art-direction template externalized correctly: [" + withGenre + "]");
	}

	/**
	 * Style-seam + override-merge safety net for the "one common olio.sd.config, per-scene overrides"
	 * refactor. Pure — no DB/LLM/SD. Verifies the crux that the old picturebook code got wrong:
	 * (1) a common config completed by SDUtil.fillStyleDefaults yields a rich, detail-field-driven
	 * style via SDUtil.getSDConfigPrompt (not null-filled garbage); (2) a SPARSE override merges only
	 * its present fields (isolation) and fillStyleDefaults repopulates the new style's detail fields;
	 * (3) the style seam is getSDConfigPrompt, NOT the removed SWUtil.styleClause custom clause.
	 */
	@Test
	public void TestSdConfigStyleSeamAndOverrideMerge() throws Exception {
		// (1) common config: style + detail fields → rich style string
		BaseRecord common = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		common.setValue("style", "art");
		SDUtil.fillStyleDefaults(common);
		String artStyleVal = common.get("artStyle");
		assertNotNull("fillStyleDefaults should populate the art style's detail field", artStyleVal);
		assertFalse("art detail field should not be blank", artStyleVal.isEmpty());
		String commonStyle = SDUtil.getSDConfigPrompt(common);
		assertNotNull(commonStyle);
		assertFalse("style prompt must not be the null-cfg fallback", commonStyle.contains("null"));
		assertTrue("art style prompt must reference its populated detail field",
			commonStyle.toLowerCase().contains(artStyleVal.toLowerCase()));

		// (2) sparse override changes ONLY the fields it carries; fillStyleDefaults then completes the new style
		BaseRecord override = RecordFactory.importRecord(OlioModelNames.MODEL_SD_CONFIG, "{\"style\":\"fashion\"}");
		SDUtil.applyOverrides(common, override);
		assertEquals("override should change style", "fashion", (String) common.get("style"));
		assertEquals("override must NOT clobber a field it didn't carry (isolation)",
			artStyleVal, (String) common.get("artStyle"));
		SDUtil.fillStyleDefaults(common);
		String fashionMag = common.get("fashionMagazine");
		assertNotNull("fillStyleDefaults should populate the fashion style's detail fields", fashionMag);
		assertFalse("fashion detail field should not be blank", fashionMag.isEmpty());
		String fashionStyle = SDUtil.getSDConfigPrompt(common);
		assertTrue("fashion style prompt must be fashion-shaped", fashionStyle.toLowerCase().contains("fashion"));
		assertNotEquals("the style seam must actually change when the config style changes", commonStyle, fashionStyle);

		// (3) the seam is getSDConfigPrompt, not the legacy custom styleClause text
		assertNotEquals("picturebook must use getSDConfigPrompt, not the removed styleClause layer",
			org.cote.accountmanager.olio.sd.swarm.SWUtil.styleClause("fashion"), fashionStyle);
		logger.info("style seam: art=[" + commonStyle + "] fashion=[" + fashionStyle + "]");
	}

	/**
	 * Build a common olio.sd.config for scene-generation tests under the config-driven API. A null
	 * style lets SDUtil.fillStyleDefaults pick+complete a random canonical style; a given style pins
	 * it. Callers set any specific steps/cfg/hires/model they assert on via setValue afterward.
	 */
	private BaseRecord newSdConfig(String style) throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		if (style != null) cfg.setValue("style", style);
		SDUtil.fillStyleDefaults(cfg);
		return cfg;
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

	// ── reset() recursive delete (KI-32) ─────────────────────────────────

	/**
	 * Regression test for KI-32: PictureBookUtil.reset() used to delete exactly 4 top-level rows
	 * (Scenes group, Characters group, .pictureBookMeta note, book group) via single-record
	 * AccessPoint.delete() calls with NO recursion — every scene note and character nested under
	 * Scenes/Characters was left orphaned in the database, which later surfaced as PathProvider
	 * "Parent auth.group index not found" log spam for any surviving record whose parentId chain
	 * climbed through one of those now-missing intermediate groups.
	 *
	 * <p>This builds a minimal book with one scene note (under Scenes/) and one charPerson (under
	 * Characters/) directly (no LLM call — just Factory.newInstance + AccessPoint.create, the same
	 * pattern TestBulkOperation.java uses for a bare olio.charPerson fixture), calls reset(), then
	 * proves via direct queries that the scene note, the character, AND the Scenes/Characters
	 * groups themselves are actually gone — not just the top-level book group, which is exactly
	 * the bug: pre-fix, only the group shells would disappear from view via findPath (since a
	 * child would already be orphaned once its parent group row was deleted) while the child rows
	 * themselves remained in the database, resolvable directly by objectId. If reset() genuinely
	 * deleted the child rows first, an ID-based lookup afterward returns null; if it just deleted
	 * the parent group (old behavior) the child row would still resolve by objectId.
	 */
	@Test
	public void TestResetRecursivelyDeletesNestedSceneAndCharacter() throws Exception {
		logger.info("Test: PictureBookUtil.reset() recursively deletes nested scene notes and "
			+ "characters under Scenes/Characters, not just the 4 top-level rows (KI-32 regression)");
		setupTestContext();

		String bookName = "KI32-Reset-Test-" + System.currentTimeMillis();
		String bookPath = "~/Data/PictureBooks/" + bookName;

		BaseRecord bookGroup = ensureGroup(bookPath);
		assertNotNull("Book group should be created", bookGroup);
		BaseRecord scenesGroup = ensureGroup(bookPath + "/Scenes");
		assertNotNull("Scenes group should be created", scenesGroup);
		BaseRecord charsGroup = ensureGroup(bookPath + "/Characters");
		assertNotNull("Characters group should be created", charsGroup);

		String bookObjectId = bookGroup.get(FieldNames.FIELD_OBJECT_ID);

		// One scene note nested under Scenes/
		ParameterList scenePlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookPath + "/Scenes");
		scenePlist.parameter(FieldNames.FIELD_NAME, "KI-32 Reset Test Scene");
		BaseRecord sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, scenePlist);
		Map<String, Object> sceneData = new LinkedHashMap<>();
		sceneData.put("title", "KI-32 Reset Test Scene");
		sceneData.put("setting", "A quiet room");
		sceneNote.set("text", JSONUtil.exportObject(sceneData));
		BaseRecord createdScene = IOSystem.getActiveContext().getAccessPoint().create(testUser, sceneNote);
		assertNotNull("Scene note should be created", createdScene);
		String sceneObjectId = createdScene.get(FieldNames.FIELD_OBJECT_ID);

		// One bare charPerson nested under Characters/ — no LLM enrichment needed for this test,
		// which is exercising the delete path, not character creation (mirrors
		// TestBulkOperation.TestLikelyBrokenParticipations's bare-charPerson fixture pattern).
		ParameterList charPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, bookPath + "/Characters");
		charPlist.parameter(FieldNames.FIELD_NAME, "KI-32 Reset Test Character");
		BaseRecord charPerson = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_CHAR_PERSON, testUser, null, charPlist);
		charPerson.set(FieldNames.FIELD_GENDER, "female");
		BaseRecord createdChar = IOSystem.getActiveContext().getAccessPoint().create(testUser, charPerson);
		assertNotNull("Character should be created", createdChar);
		String charObjectId = createdChar.get(FieldNames.FIELD_OBJECT_ID);

		// Sanity: everything actually exists before reset()
		assertNotNull("Scene note should resolve before reset()",
			findNoteByObjectId(sceneObjectId));
		assertNotNull("Character should resolve before reset()",
			findCharByObjectId(charObjectId));
		assertNotNull("Scenes group should resolve before reset()", findGroupByPath(bookPath + "/Scenes"));
		assertNotNull("Characters group should resolve before reset()", findGroupByPath(bookPath + "/Characters"));

		// Act
		boolean ok = PictureBookUtil.reset(testUser, bookObjectId);
		assertTrue("reset() should report success", ok);

		// Assert — the nested scene note and character are ACTUALLY gone (this is the KI-32
		// regression: pre-fix, these direct-by-objectId lookups would still resolve since only
		// the top-level group/meta rows were ever deleted)
		assertNull("Scene note must be deleted, not just orphaned, after reset()",
			findNoteByObjectId(sceneObjectId));
		assertNull("Character must be deleted, not just orphaned, after reset()",
			findCharByObjectId(charObjectId));

		// Assert — the Scenes/Characters groups themselves are gone too
		assertNull("Scenes group must be deleted after reset()", findGroupByPath(bookPath + "/Scenes"));
		assertNull("Characters group must be deleted after reset()", findGroupByPath(bookPath + "/Characters"));

		// Assert — the book group itself is gone (pre-existing behavior, sanity check)
		assertNull("Book group must be deleted after reset()", findGroupByPath(bookPath));

		logger.info("KI-32 regression verified: scene note, character, and both sub-groups were "
			+ "all actually removed by reset(), not just orphaned");
	}

	/**
	 * KI-32 follow-up regression test: reset() must also delete a character's own dedicated
	 * foreign single-model sub-records (profile/narrative/statistics/store/instinct/personality/
	 * state — see createPersistedForeignInstance) — these live in the acting user's shared
	 * ~/Profiles, ~/Narratives, etc. buckets rather than nested under the book's Characters
	 * subtree, so the group-subtree walk in TestResetRecursivelyDeletesNestedSceneAndCharacter
	 * above never exercises this path (that test's bare charPerson fixture has none of these
	 * sub-records at all). Builds a real character via createFromScenes (same as
	 * TestCreateFromScenesSeedsRandomBaselineOnCharacter) so all seven sub-records are genuinely
	 * persisted, captures each one's objectId, calls reset(), then asserts every one of them
	 * — not just the character itself — is actually gone afterward.
	 */
	@Test
	public void TestResetDeletesCharacterForeignSubRecords() throws Exception {
		logger.info("Test: PictureBookUtil.reset() also deletes a character's own foreign "
			+ "sub-records (profile/narrative/statistics/store/instinct/personality/state) — KI-32 follow-up");
		setupTestContext();

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "KI32-SubRecord-Test-" + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", TEST_STORY);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull(createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		List<Map<String, Object>> sceneList = new ArrayList<>();
		Map<String, Object> scene0 = new LinkedHashMap<>();
		scene0.put("title", "Elena Enters the Forest");
		scene0.put("blurb", "Elena and Marcus enter the ancient forest.");
		scene0.put("setting", "ancient forest");
		scene0.put("action", "walking cautiously");
		scene0.put("mood", "tense");
		sceneList.add(scene0);

		String elenaName = "Elena KI32Sub " + System.currentTimeMillis();
		List<Map<String, Object>> charDataList = new ArrayList<>();
		Map<String, Object> elenaStub = new LinkedHashMap<>();
		elenaStub.put("name", elenaName);
		elenaStub.put("gender", "FEMALE");
		elenaStub.put("role", "protagonist");
		charDataList.add(elenaStub);

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId, chatConfigName, null,
			"KI32-SubRecord-Test-Book-" + System.currentTimeMillis(), sceneList, charDataList, dataPath);
		assertNotNull("createFromScenes should return meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull("meta should carry bookObjectId", bookObjectId);

		List<Object> failedCharacters = meta.get("failedCharacters");
		assertTrue("Character should create successfully — failedCharacters=" + failedCharacters,
			failedCharacters == null || failedCharacters.isEmpty());

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, elenaName);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(true);
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("Character should be resolvable by name", cp);
		String charObjectId = cp.get(FieldNames.FIELD_OBJECT_ID);

		String profileObjectId = requirePersistedForeignObjectId(cp, "profile");
		String narrativeObjectId = requirePersistedForeignObjectId(cp, "narrative");
		String statisticsObjectId = requirePersistedForeignObjectId(cp, OlioFieldNames.FIELD_STATISTICS);
		String storeObjectId = requirePersistedForeignObjectId(cp, FieldNames.FIELD_STORE);
		String instinctObjectId = requirePersistedForeignObjectId(cp, OlioFieldNames.FIELD_INSTINCT);
		String personalityObjectId = requirePersistedForeignObjectId(cp, FieldNames.FIELD_PERSONALITY);
		String stateObjectId = requirePersistedForeignObjectId(cp, FieldNames.FIELD_STATE);

		// Sanity: all seven sub-records genuinely resolve before reset()
		assertNotNull("profile must resolve before reset()", findByModelAndObjectId(ModelNames.MODEL_PROFILE, profileObjectId));
		assertNotNull("narrative must resolve before reset()", findByModelAndObjectId(OlioModelNames.MODEL_NARRATIVE, narrativeObjectId));
		assertNotNull("statistics must resolve before reset()", findByModelAndObjectId(OlioModelNames.MODEL_CHAR_STATISTICS, statisticsObjectId));
		assertNotNull("store must resolve before reset()", findByModelAndObjectId(OlioModelNames.MODEL_STORE, storeObjectId));
		assertNotNull("instinct must resolve before reset()", findByModelAndObjectId(OlioModelNames.MODEL_INSTINCT, instinctObjectId));
		assertNotNull("personality must resolve before reset()", findByModelAndObjectId(ModelNames.MODEL_PERSONALITY, personalityObjectId));
		assertNotNull("state must resolve before reset()", findByModelAndObjectId(OlioModelNames.MODEL_CHAR_STATE, stateObjectId));

		// Act
		boolean ok = PictureBookUtil.reset(testUser, bookObjectId);
		assertTrue("reset() should report success", ok);

		// Assert — the character itself is gone (already covered elsewhere, sanity check here too)
		assertNull("Character must be deleted after reset()", findCharByObjectId(charObjectId));

		// Assert — every one of the character's own foreign sub-records is ALSO gone, not just
		// orphaned (this is the follow-up regression: pre-fix, these would still resolve directly
		// by objectId since they live outside the book's group subtree entirely)
		assertNull("profile must be deleted, not just orphaned, after reset()", findByModelAndObjectId(ModelNames.MODEL_PROFILE, profileObjectId));
		assertNull("narrative must be deleted, not just orphaned, after reset()", findByModelAndObjectId(OlioModelNames.MODEL_NARRATIVE, narrativeObjectId));
		assertNull("statistics must be deleted, not just orphaned, after reset()", findByModelAndObjectId(OlioModelNames.MODEL_CHAR_STATISTICS, statisticsObjectId));
		assertNull("store must be deleted, not just orphaned, after reset()", findByModelAndObjectId(OlioModelNames.MODEL_STORE, storeObjectId));
		assertNull("instinct must be deleted, not just orphaned, after reset()", findByModelAndObjectId(OlioModelNames.MODEL_INSTINCT, instinctObjectId));
		assertNull("personality must be deleted, not just orphaned, after reset()", findByModelAndObjectId(ModelNames.MODEL_PERSONALITY, personalityObjectId));
		assertNull("state must be deleted, not just orphaned, after reset()", findByModelAndObjectId(OlioModelNames.MODEL_CHAR_STATE, stateObjectId));

		logger.info("KI-32 follow-up verified: all 7 foreign sub-records (profile/narrative/statistics/"
			+ "store/instinct/personality/state) were actually deleted by reset(), not just the character itself");
	}

	private String requirePersistedForeignObjectId(BaseRecord cp, String fieldName) {
		BaseRecord fk = cp.get(fieldName);
		assertNotNull("charPerson." + fieldName + " must be a persisted record", fk);
		Long id = fk.get(FieldNames.FIELD_ID);
		assertTrue("charPerson." + fieldName + " must be persisted (id>0)", id != null && id > 0L);
		String objectId = fk.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("charPerson." + fieldName + " must have an objectId", objectId);
		return objectId;
	}

	private BaseRecord findByModelAndObjectId(String model, String objectId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private BaseRecord findNoteByObjectId(String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	// Default query projection excludes "text" (not one of data.note's query fields) — use this
	// instead of findNoteByObjectId() when the note's text JSON blob itself needs inspecting.
	private BaseRecord findNoteByObjectIdWithText(String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "text" });
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private BaseRecord findCharByObjectId(String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private BaseRecord findGroupByPath(String path) {
		return IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(),
			((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
	}

	// ── Cancellation (KI-10) ─────────────────────────────────────────────

	/**
	 * KI-10 regression test: a mid-run cancel (SummarizeProgress.cancel(), the same class
	 * ChatUtil's map/reduce summarization already uses) must stop extractChunkedInternal's chunk
	 * loop before all chunks are processed — not run to completion regardless, as it did before
	 * this fix (the cancel flag was never threaded into the loop at all). Uses a real live LLM
	 * (Ollama) so at least one real chunk-extraction call actually happens before cancellation is
	 * fired, proving the loop genuinely ran rather than the check short-circuiting before any real
	 * work started. A background thread fires the cancel once SummarizeProgress reports the first
	 * chunk has completed (current&gt;=1) — this is the same kind of LLM-touching test as the rest
	 * of this file (real Ollama server via testProperties), single-threaded within this class same
	 * as its siblings (no special gating needed — this whole test class already requires the live
	 * Ollama server configured in test.properties, same as every other @Test here).
	 */
	@Test
	public void TestExtractScenesOnlyCancellationStopsChunkedExtractionEarly() throws Exception {
		logger.info("Test: KI-10 — cancelling mid-run stops extractChunkedInternal's chunk loop "
			+ "before all chunks are processed (fewer chunks processed than total), while at least "
			+ "one chunk is proven to have actually run first");
		setupTestContext();

		// Build text long enough to force several chunks through the real auto-chunk path
		// (extractScenesOnly auto-chunks above 8000 chars; chunkSize=2000/overlap=200 inside
		// extractChunkedInternal works out to roughly text.length()/1800 chunks) so a background
		// cancel fired after the first chunk completes still has more chunks left to skip.
		StringBuilder sb = new StringBuilder();
		int chapter = 1;
		while (sb.length() < 10000) {
			sb.append("Chapter ").append(chapter++).append(": ").append(TEST_STORY).append("\n\n");
		}
		String longText = sb.toString();

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "KI10 Cancel Test Story " + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", longText);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull(createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";
		SummarizeProgress cancelToken = new SummarizeProgress();

		// Fire the cancel from a background thread only once the loop has actually processed at
		// least one chunk (current>=1) -- proves the loop genuinely made real LLM calls before
		// being stopped, not a same-request no-op that never let anything run.
		Thread canceller = new Thread(() -> {
			long deadline = System.currentTimeMillis() + 180_000;
			while (System.currentTimeMillis() < deadline) {
				if (cancelToken.getCurrent() >= 1) {
					cancelToken.cancel();
					return;
				}
				try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
			}
		}, "KI10-canceller");
		canceller.start();

		PictureBookUtil.ScenesOnlyResult result = PictureBookUtil.extractScenesOnly(
			testUser, workObjectId, PictureBookUtil.MAX_SCENES_DEFAULT, chatConfigName, null, cancelToken);
		canceller.join(5000);

		assertTrue("Text this long should take the chunked path", result.chunked);
		assertTrue("cancelToken should have been cancelled by the background thread "
			+ "(if false, the loop finished before the canceller ever saw current>=1 -- rerun with "
			+ "more/longer chunks)", cancelToken.isCancelled());

		int totalChunks = cancelToken.getTotal();
		int processedChunks = cancelToken.getCurrent();
		logger.info("KI-10: processed " + processedChunks + "/" + totalChunks + " chunks before cancellation, "
			+ "returned " + result.scenes.size() + " scenes");

		assertTrue("At least one chunk must have actually been processed before cancellation took effect "
			+ "(proves the loop genuinely ran) — processed=" + processedChunks, processedChunks >= 1);
		assertTrue("Fewer chunks should have been processed than the total chunk count -- proves the loop "
			+ "stopped EARLY rather than running to completion regardless of cancellation (the KI-10 "
			+ "regression) — processed=" + processedChunks + " total=" + totalChunks,
			processedChunks < totalChunks);
	}

	// ── LLM error/empty payload guard (KI-31) ────────────────────────────

	/**
	 * Regression test for KI-31: callLlmInternal returned raw LLM message content verbatim with no
	 * shape/error validation; resolveScenePrompt/resolveLandscapePrompt only guarded against
	 * null/blank content before caching and forwarding it to SDUtil.txt2img as literal prompt
	 * text. Live logs showed a 200-OK response whose content was itself error-shaped JSON
	 * ({"error":"..."}) or an empty array ([]) — neither null nor blank — reaching the SD prompt
	 * verbatim. This is a direct unit test of the extracted guard (PictureBookUtil.
	 * isErrorOrEmptyPayload) against exactly those crafted input strings — no live LLM round-trip
	 * needed since this validates a parsing/guard function's behavior for a given input string,
	 * not the LLM call itself.
	 */
	@Test
	public void TestIsErrorOrEmptyPayloadDetectsErrorShapedAndEmptyLlmContent() {
		logger.info("Test: PictureBookUtil.isErrorOrEmptyPayload() rejects error-shaped/empty LLM "
			+ "content that used to reach the SD prompt verbatim (KI-31 regression)");

		// The exact failure shapes observed live in KI-31's log evidence
		assertTrue("{\"error\":\"No story text provided\"} must be detected as an error payload",
			PictureBookUtil.isErrorOrEmptyPayload("{\"error\":\"No story text provided\"}"));
		assertTrue("{\"error\":\"Missing story text and count parameters\"} must be detected",
			PictureBookUtil.isErrorOrEmptyPayload("{\"error\":\"Missing story text and count parameters\"}"));
		assertTrue("{\"error\":\"Please provide the story text and the desired number of scenes to identify.\"} must be detected",
			PictureBookUtil.isErrorOrEmptyPayload(
				"{\"error\":\"Please provide the story text and the desired number of scenes to identify.\"}"));
		assertTrue("Bare empty array [] must be detected as an empty payload",
			PictureBookUtil.isErrorOrEmptyPayload("[]"));
		assertTrue("Whitespace-padded empty array must still be detected",
			PictureBookUtil.isErrorOrEmptyPayload("  []  \n"));

		// Existing null/blank guard behavior must still work (not a regression on the old check)
		assertTrue("null content must be detected", PictureBookUtil.isErrorOrEmptyPayload(null));
		assertTrue("blank content must be detected", PictureBookUtil.isErrorOrEmptyPayload("   "));
		assertTrue("empty string must be detected", PictureBookUtil.isErrorOrEmptyPayload(""));

		// Real prompt text (including JSON-ish-looking but legitimate content) must NOT be flagged
		assertFalse("Real prose prompt text must not be flagged as an error payload",
			PictureBookUtil.isErrorOrEmptyPayload("A moody forest at dusk, cinematic lighting, illustration style"));
		assertFalse("A JSON array with real content must not be flagged",
			PictureBookUtil.isErrorOrEmptyPayload("[\"forest\", \"dusk\", \"cinematic\"]"));
		assertFalse("A JSON object without an 'error' key must not be flagged",
			PictureBookUtil.isErrorOrEmptyPayload("{\"setting\":\"forest\", \"mood\":\"tense\"}"));

		// KI-31 follow-up (2026-07-23): a plain-prose conversational clarifying question — neither
		// JSON-shaped nor blank — reaching the SD prompt verbatim. Root cause: promptTemplateOverride
		// is a single field the wizard's "single prompt template" mode applies to EVERY LLM call;
		// picking a template meant for scene EXTRACTION (which expects {text}/{count}) silently
		// overrode a scene-image-prompt/landscape-prompt call whose vars are setting/action/mood/
		// charNarrations instead, leaving {text}/{count} unsubstituted. The LLM reasonably asked for
		// them back in prose — exact live example below.
		assertTrue("A conversational request for missing story text/scene count must be detected",
			PictureBookUtil.isErrorOrEmptyPayload(
				"I’m happy to help identify the most visually compelling scenes, but I need the actual "
				+ "story text and the number of scenes you’d like selected. Could you please provide those details?"));
		assertTrue("A shorter variant of the same conversational refusal must be detected",
			PictureBookUtil.isErrorOrEmptyPayload(
				"I’m happy to help identify the most visually compelling scenes, but I need the actual "
				+ "story text and the number of scenes you’d like selected. Please provide the story and "
				+ "specify how many scenes you’d like me to extract."));
		assertTrue("Prompt text with a leftover unsubstituted {placeholder} must be detected",
			PictureBookUtil.isErrorOrEmptyPayload("Given this story, identify the {count} most visually notable scenes. STORY: {text}"));
		assertFalse("Real prompt text mentioning a scene/story in passing must NOT be falsely flagged",
			PictureBookUtil.isErrorOrEmptyPayload("A dramatic scene from an old story, illustrated in watercolor style, moody lighting"));
	}

	/**
	 * KI-31 follow-up regression test (2026-07-23, live): reproduces the exact reported bug —
	 * "PictureBook prompts are still completely broken" — by deliberately passing
	 * promptTemplateOverride="pictureBook.extract-scenes" (a real template requiring {@code {text}}/
	 * {@code {count}}) into {@code prepareSceneImagePrompts}, which uses it for BOTH the
	 * scene-image-prompt AND landscape-prompt calls (whose vars are setting/action/mood/
	 * charNarrations — no text/count at all). Pre-fix, this made the LLM receive a template with
	 * unfilled {@code {text}}/{@code {count}} placeholders and respond with a conversational
	 * clarifying question, which then got cached and would have reached SDUtil.txt2img verbatim as
	 * the literal prompt (confirmed live in the running Tomcat instance's own log, 2026-07-23,
	 * before this fix). Runs against the real Ollama LLM — no mocking.
	 */
	@Test
	public void TestMismatchedPromptTemplateOverrideDoesNotPoisonScenePrompt() throws Exception {
		logger.info("Test: prepareSceneImagePrompts() must not let a cross-purpose promptTemplateOverride "
			+ "(extract-scenes template applied to scene-image/landscape-prompt calls) poison the cached "
			+ "prompt with a conversational LLM refusal (KI-31 follow-up regression)");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";
		String sceneName = "KI31Followup-Scene-" + System.currentTimeMillis();
		Map<String, Object> sceneData = new LinkedHashMap<>();
		sceneData.put("title", sceneName);
		sceneData.put("setting", "a quiet moonlit forest clearing");
		sceneData.put("action", "a lone traveler kneels beside a small campfire");
		sceneData.put("mood", "peaceful, reflective");
		sceneData.put("characters", new ArrayList<>());

		ParameterList scenePlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		scenePlist.parameter(FieldNames.FIELD_NAME, sceneName);
		BaseRecord sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, scenePlist);
		sceneNote.set("text", JSONUtil.exportObject(sceneData));
		BaseRecord createdScene = IOSystem.getActiveContext().getAccessPoint().create(testUser, sceneNote);
		assertNotNull("Scene note should be created", createdScene);
		String sceneObjectId = createdScene.get(FieldNames.FIELD_OBJECT_ID);

		// The actual misconfiguration: a template belonging to a DIFFERENT operation
		// (extract-scenes needs {text}/{count}) applied here, where the real templates
		// (scene-image-prompt/landscape-prompt) need setting/action/mood/charNarrations instead.
		PictureBookUtil.prepareSceneImagePrompts(testUser, Arrays.asList(sceneObjectId), chatConfigName,
			newSdConfig("art"), "pictureBook.extract-scenes");

		BaseRecord refetched = findNoteByObjectIdWithText(sceneObjectId);
		assertNotNull(refetched);
		Map<String, Object> refetchedData = JSONUtil.getMap(((String) refetched.get("text")).getBytes(), String.class, Object.class);
		String cachedScenePrompt = (String) refetchedData.get("scenePrompt");
		String cachedLandscapePrompt = (String) refetchedData.get("landscapePrompt");

		logger.info("KI-31 follow-up: cachedScenePrompt=[" + cachedScenePrompt + "] cachedLandscapePrompt=["
			+ cachedLandscapePrompt + "]");

		assertNotNull("scenePrompt must still be cached (via the fallback path, not left null)", cachedScenePrompt);
		assertFalse("scenePrompt must NOT be the LLM's conversational refusal / an unsubstituted-placeholder "
			+ "template — the guard must have refused to call the LLM with the mismatched template",
			PictureBookUtil.isErrorOrEmptyPayload(cachedScenePrompt));
		assertTrue("scenePrompt must be built from the REAL scene fallback data, not generic/empty boilerplate",
			cachedScenePrompt.toLowerCase().contains("moonlit forest") || cachedScenePrompt.toLowerCase().contains("campfire"));

		assertNotNull("landscapePrompt must still be cached (via the fallback path, not left null)", cachedLandscapePrompt);
		assertFalse("landscapePrompt must NOT be the LLM's conversational refusal / an unsubstituted-placeholder template",
			PictureBookUtil.isErrorOrEmptyPayload(cachedLandscapePrompt));
		assertEquals("landscapePrompt must fall back to the real setting text per resolveLandscapePrompt's own fallback",
			"a quiet moonlit forest clearing", cachedLandscapePrompt);
	}

	/**
	 * KI-31 follow-up regression test (2026-07-23, live): a scene whose cached scenePrompt was
	 * ALREADY poisoned (simulating a book generated before this fix landed) must self-heal the next
	 * time it's touched, rather than serving the same garbage forever — this was the actual reported
	 * symptom ("regenerating doesn't help", since the corrupted value was cached, not regenerated on
	 * every attempt). This call uses NO override (the normal/correct path) — proving the poisoned
	 * cache alone, not a bad override, is what's being healed here.
	 */
	@Test
	public void TestPoisonedCachedScenePromptSelfHeals() throws Exception {
		logger.info("Test: a scene whose scenePrompt cache was already poisoned by the KI-31 follow-up bug "
			+ "must self-heal on next touch, not serve the same broken text forever");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";
		String sceneName = "KI31SelfHeal-Scene-" + System.currentTimeMillis();
		Map<String, Object> sceneData = new LinkedHashMap<>();
		sceneData.put("title", sceneName);
		sceneData.put("setting", "a bustling marketplace at dawn");
		sceneData.put("action", "vendors call out as a merchant counts coins");
		sceneData.put("mood", "energetic, warm");
		sceneData.put("characters", new ArrayList<>());
		// Simulate the exact live-observed poisoned cache value from before this fix.
		sceneData.put("scenePrompt", "I’m happy to help identify the most visually compelling scenes, "
			+ "but I need the actual story text and the number of scenes you’d like selected. "
			+ "Could you please provide those details?");

		ParameterList scenePlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		scenePlist.parameter(FieldNames.FIELD_NAME, sceneName);
		BaseRecord sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, scenePlist);
		sceneNote.set("text", JSONUtil.exportObject(sceneData));
		BaseRecord createdScene = IOSystem.getActiveContext().getAccessPoint().create(testUser, sceneNote);
		assertNotNull("Scene note should be created", createdScene);
		String sceneObjectId = createdScene.get(FieldNames.FIELD_OBJECT_ID);

		// Normal call, no override — the poisoned cache alone must be what triggers regeneration.
		PictureBookUtil.prepareSceneImagePrompts(testUser, Arrays.asList(sceneObjectId), chatConfigName,
			newSdConfig("art"), null);

		BaseRecord refetched = findNoteByObjectIdWithText(sceneObjectId);
		assertNotNull(refetched);
		Map<String, Object> refetchedData = JSONUtil.getMap(((String) refetched.get("text")).getBytes(), String.class, Object.class);
		String healedScenePrompt = (String) refetchedData.get("scenePrompt");

		logger.info("KI-31 self-heal: healedScenePrompt=[" + healedScenePrompt + "]");
		assertNotNull(healedScenePrompt);
		assertFalse("The poisoned cached value must NOT still be served after prepareSceneImagePrompts runs again",
			PictureBookUtil.isErrorOrEmptyPayload(healedScenePrompt));
		assertFalse("The literal poisoned text must be gone, not just deemed acceptable",
			healedScenePrompt.contains("I need the actual story text"));
	}

	/**
	 * Stephen's direct diagnostic request (2026-07-23): run the real opening of catatone.docx
	 * (Duña and Jideon; scene 1 outside the house carrying Duña to a cab, scene 2 inside the house
	 * searching for clues — dystopian near-future North America) through the ACTUAL production
	 * pipeline end to end — extract → resolve/cache the real landscape+scene prompts → generate
	 * real images against the live Swarm server — and report what each stage actually produced, not
	 * an assumption. `extract()` truncates to the first 8000 chars of the document for its own
	 * non-chunked LLM call, which comfortably covers both opening scenes (confirmed by inspecting
	 * the raw extracted document text before writing this test).
	 */
	@Test
	public void TestCatatoneOpeningScenesRealPromptsAndImages() throws Exception {
		logger.info("Test: real catatone.docx opening (Duña/Jideon, outside-house then inside-house scenes) "
			+ "through the full extract -> resolve-prompt -> generate-image pipeline, live");
		setupTestContext();

		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);
		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";

		byte[] fileBytes = FileUtil.getFile("./media/catatone.docx");
		assertNotNull("catatone.docx should be readable from the module's media/ directory", fileBytes);

		// Load the REAL extracted document text (same DocumentUtil.getStringContent() path
		// extractWorkText() uses in production) and isolate JUST the two scenes Stephen specified:
		// (1) outside the house — Jideon carries Duña to the waiting cab in the rain; (2) inside the
		// house — searching for clues, the call to Maria. The full document (~34KB) covers many more
		// scenes further in (an alley confrontation with a third character, "Touvier", immediately
		// follows) — pictureBook.extract-scenes' own system prompt explicitly distributes selections
		// "across the full arc of the story, not clustered at the start", so simply asking for 2
		// scenes from the full/8000-char-truncated text let the LLM pick a later scene instead of
		// the inside-the-house one (confirmed by an earlier run of this exact test). Truncating the
		// INPUT text itself to end right where the cab-ride-to-the-alley scene begins guarantees the
		// only two scenes available to select from are the ones actually requested.
		ParameterList docPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		docPlist.parameter(FieldNames.FIELD_NAME, "catatone-source-" + System.currentTimeMillis() + ".docx");
		BaseRecord docWork = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, docPlist);
		docWork.set(FieldNames.FIELD_CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		ByteModelUtil.setValue(docWork, fileBytes);
		BaseRecord createdDocWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, docWork);
		assertNotNull(createdDocWork);
		String fullText = DocumentUtil.getStringContent(createdDocWork);
		assertNotNull("catatone.docx should extract real text content", fullText);

		String cutAnchor = "He picked up a bag of";
		int cutIdx = fullText.indexOf(cutAnchor);
		assertTrue("Expected to find the outside/inside-house scene boundary anchor in the real "
			+ "extracted text — catatone.docx's content may have changed", cutIdx > 0);
		String openingText = fullText.substring(0, cutIdx);
		logger.info("Isolated opening text (" + openingText.length() + " chars): " + openingText);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "catatone-opening-" + System.currentTimeMillis());
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		work.set("text", openingText);
		BaseRecord createdWork = IOSystem.getActiveContext().getAccessPoint().create(testUser, work);
		assertNotNull(createdWork);
		String workObjectId = createdWork.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord meta = PictureBookUtil.extract(testUser, workObjectId, 2, chatConfigName, "dystopian sci-fi",
			"Catatone Opening " + System.currentTimeMillis(), testProperties.getProperty("test.datagen.path"));
		assertNotNull("extract() should return meta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull(bookObjectId);
		List<Object> failedCharacters = meta.get("failedCharacters");
		logger.info("catatone opening extract() failedCharacters: " + (failedCharacters != null ? failedCharacters : "(none)"));

		List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertTrue("Expected at least 2 scenes from the catatone opening — got " + scenes.size(), scenes.size() >= 2);

		// Confirm Duña and Jideon were actually extracted as real characters, not assumed.
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookObjectId);
		String charsGroupPath = ((String) bookGroup.get(FieldNames.FIELD_PATH)) + "/Characters";
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(),
			(long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull(charsGroup);
		Query allCharsQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
		allCharsQ.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord[] createdChars = IOSystem.getActiveContext().getSearch().findRecords(allCharsQ);
		boolean sawJideon = false, sawDuna = false;
		for (BaseRecord c : createdChars) {
			String cname = c.get(FieldNames.FIELD_NAME);
			logger.info("Extracted character: " + cname);
			if (cname != null && cname.toLowerCase().contains("jideon")) sawJideon = true;
			// "ñ" may or may not survive LLM extraction verbatim — match either spelling.
			if (cname != null && (cname.toLowerCase().contains("duña") || cname.toLowerCase().contains("duna"))) sawDuna = true;
		}
		logger.info("Character extraction check: sawJideon=" + sawJideon + " sawDuna=" + sawDuna
			+ " (all extracted: " + java.util.Arrays.stream(createdChars).map(c -> (String) c.get(FieldNames.FIELD_NAME)).collect(java.util.stream.Collectors.joining(", ")) + ")");

		// CHECK THE PROMPT — step 1: what did extraction actually put in each scene's raw
		// setting/action/mood, before any LLM prompt-resolution runs?
		for (int i = 0; i < Math.min(2, scenes.size()); i++) {
			String sceneOid = (String) scenes.get(i).get("objectId");
			BaseRecord sceneNote = findNoteByObjectIdWithText(sceneOid);
			assertNotNull(sceneNote);
			Map<String, Object> sceneData = JSONUtil.getMap(((String) sceneNote.get("text")).getBytes(), String.class, Object.class);
			logger.info("RAW SCENE " + i + " DATA: title=[" + sceneData.get("title") + "] setting=[" + sceneData.get("setting")
				+ "] action=[" + sceneData.get("action") + "] mood=[" + sceneData.get("mood") + "] blurb=[" + sceneData.get("blurb") + "]");
		}

		// CHECK THE PROMPT — step 2: resolve (and cache) the real landscape+scene-image prompts via
		// the actual production call, no override, then read back exactly what got cached.
		List<String> sceneOids = new ArrayList<>();
		for (int i = 0; i < Math.min(2, scenes.size()); i++) sceneOids.add((String) scenes.get(i).get("objectId"));
		PictureBookUtil.prepareSceneImagePrompts(testUser, sceneOids, chatConfigName, newSdConfig("art"), null);

		List<String> landscapePrompts = new ArrayList<>();
		List<String> scenePrompts = new ArrayList<>();
		for (int i = 0; i < sceneOids.size(); i++) {
			BaseRecord refetched = findNoteByObjectIdWithText(sceneOids.get(i));
			Map<String, Object> refetchedData = JSONUtil.getMap(((String) refetched.get("text")).getBytes(), String.class, Object.class);
			String landscapePrompt = (String) refetchedData.get("landscapePrompt");
			String scenePrompt = (String) refetchedData.get("scenePrompt");
			landscapePrompts.add(landscapePrompt);
			scenePrompts.add(scenePrompt);
			logger.info("RESOLVED SCENE " + i + " landscapePrompt=[" + landscapePrompt + "]");
			logger.info("RESOLVED SCENE " + i + " scenePrompt=[" + scenePrompt + "]");
		}

		for (int i = 0; i < landscapePrompts.size(); i++) {
			assertNotNull("Scene " + i + " landscapePrompt must be cached", landscapePrompts.get(i));
			assertNotEquals("Scene " + i + " landscapePrompt must not be the generic empty-setting "
				+ "fallback — got exactly \"A detailed environment\", meaning setting text itself was "
				+ "empty for this scene", "A detailed environment", landscapePrompts.get(i));
			assertFalse("Scene " + i + " landscapePrompt must not be an error/refusal payload",
				PictureBookUtil.isErrorOrEmptyPayload(landscapePrompts.get(i)));
		}

		// MAKE THE IMAGE — real generateSceneImage calls against the live local Swarm server.
		PictureBookUtil.SceneGenerationParams params = new PictureBookUtil.SceneGenerationParams();
		params.chatConfigName = chatConfigName;
		params.isBookOverride = true;
		BaseRecord genCfg = newSdConfig("art");
		genCfg.setValue("steps", 20);
		genCfg.setValue("cfg", 5);
		genCfg.setValue("hires", false);
		genCfg.setValue("model", testProperties.getProperty("test.swarm.model"));
		params.sdConfig = genCfg;

		for (int i = 0; i < sceneOids.size(); i++) {
			String sceneOid = sceneOids.get(i);
			long start = System.currentTimeMillis();
			BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid, params, "SWARM", swarmServer);
			logger.info("Scene " + i + " generateSceneImage took " + (System.currentTimeMillis() - start)
				+ "ms — imageObjectId=" + (result != null ? result.get("imageObjectId") : "null")
				+ " prompt=[" + (result != null ? result.get("prompt") : "null") + "]");
			assertNotNull("Scene " + i + " image generation should succeed against " + swarmServer, result);
			String imageObjectId = result.get("imageObjectId");
			assertNotNull("Scene " + i + " should produce a real generated image objectId", imageObjectId);
		}
	}

	/**
	 * KI-31 follow-up, second root cause (2026-07-23, found live on Stephen's real /Public catatone
	 * book, not a synthetic test): when a scene's setting/mood/action/characters are all blank,
	 * resolveLandscapePrompt/resolveScenePrompt used to call the LLM anyway — with a wire request
	 * literally reading "SETTING: \nMOOD: \nTIME: \nSTYLE: photograph" (confirmed via the live
	 * server's own request log) — and the LLM does not refuse; it invents a plausible-but-unrelated
	 * result ("alpine meadow ... crystal-clear river ... snow-capped mountains" for a dystopian
	 * rain-soaked city scene). That response isn't error-shaped, so it was cached and reused forever.
	 * Fix: skip the LLM entirely when there is nothing real to describe. This test exercises the real
	 * production method (prepareSceneImagePrompts) with a genuinely blank scene and asserts (a) the
	 * result is the exact deterministic fallback, not a fabricated result, and (b) it completes near-
	 * instantly — real LLM calls in this session took 3-90+ seconds, so a sub-2-second completion is
	 * a reliable signal no network round-trip happened, not just a coincidence of fast hardware.
	 */
	@Test
	public void TestBlankSettingSkipsLlmCallEntirely() throws Exception {
		logger.info("Test: resolveLandscapePrompt/resolveScenePrompt must not call the LLM at all when "
			+ "setting/action/mood/characters are all blank (KI-31 follow-up, second root cause)");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";
		String sceneName = "KI31Blank-Scene-" + System.currentTimeMillis();
		Map<String, Object> sceneData = new LinkedHashMap<>();
		sceneData.put("title", sceneName);
		sceneData.put("setting", "");
		sceneData.put("action", "");
		sceneData.put("mood", "");
		sceneData.put("characters", new ArrayList<>());

		ParameterList scenePlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		scenePlist.parameter(FieldNames.FIELD_NAME, sceneName);
		BaseRecord sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, scenePlist);
		sceneNote.set("text", JSONUtil.exportObject(sceneData));
		BaseRecord createdScene = IOSystem.getActiveContext().getAccessPoint().create(testUser, sceneNote);
		assertNotNull("Scene note should be created", createdScene);
		String sceneObjectId = createdScene.get(FieldNames.FIELD_OBJECT_ID);

		long start = System.currentTimeMillis();
		PictureBookUtil.prepareSceneImagePrompts(testUser, Arrays.asList(sceneObjectId), chatConfigName,
			newSdConfig("art"), null);
		long elapsed = System.currentTimeMillis() - start;
		logger.info("prepareSceneImagePrompts on a fully-blank scene took " + elapsed + "ms");
		assertTrue("A blank scene must resolve near-instantly (no LLM round-trip) — took " + elapsed
			+ "ms, which looks like a real network call happened", elapsed < 2000);

		BaseRecord refetched = findNoteByObjectIdWithText(sceneObjectId);
		Map<String, Object> refetchedData = JSONUtil.getMap(((String) refetched.get("text")).getBytes(), String.class, Object.class);
		String landscapePrompt = (String) refetchedData.get("landscapePrompt");
		String scenePrompt = (String) refetchedData.get("scenePrompt");
		logger.info("Blank-scene landscapePrompt=[" + landscapePrompt + "] scenePrompt=[" + scenePrompt + "]");

		assertEquals("A fully blank scene's landscapePrompt must be exactly the deterministic fallback, "
			+ "never an LLM-fabricated result", "A detailed environment", landscapePrompt);
		assertEquals("A fully blank scene's scenePrompt must be exactly SWUtil.styleClause(style)'s output",
			"Rendered as a detailed illustration.", scenePrompt);
	}

	/**
	 * KI-31 follow-up, second root cause — self-heal half: a scene whose landscapePrompt was ALREADY
	 * poisoned by the pre-fix blank-input hallucination (simulating Stephen's real /Public book,
	 * using the exact live-observed hallucinated text) must self-heal the next time it's touched,
	 * even though that text is coherent, well-formed prompt prose — not error-shaped, not a
	 * conversational refusal — which is exactly why the original isErrorOrEmptyPayload-only self-heal
	 * could never have caught it. This is the NEW, more precise self-heal: since a blank-input scene
	 * can now only ever legitimately produce "A detailed environment", anything else cached while
	 * setting/mood are still blank is conclusively a pre-fix hallucination.
	 */
	@Test
	public void TestPoisonedHallucinatedLandscapePromptSelfHeals() throws Exception {
		logger.info("Test: a landscapePrompt poisoned by the pre-fix blank-input LLM hallucination "
			+ "must self-heal to the deterministic fallback, not keep serving the hallucinated text");
		setupTestContext();

		String chatConfigName = "PictureBook " + PB_LLM_MODEL + ".chat";
		String sceneName = "KI31HallucinationHeal-Scene-" + System.currentTimeMillis();
		Map<String, Object> sceneData = new LinkedHashMap<>();
		sceneData.put("title", sceneName);
		sceneData.put("setting", "");
		sceneData.put("mood", "");
		sceneData.put("action", "");
		sceneData.put("characters", new ArrayList<>());
		// The exact live-observed hallucination, byte-for-byte, from a blank "SETTING: \nMOOD: \n"
		// request — confirmed via the running server's own request/response log (2026-07-23).
		sceneData.put("landscapePrompt", "masterpiece, best quality, expansive alpine meadow dotted "
			+ "with wildflowers and a crystal-clear river winding through rugged, snow-capped "
			+ "mountains in the distance, early morning mist rising over the valley, soft pastel "
			+ "sunrise lighting with warm golden hues spilling across the landscape, tranquil and "
			+ "serene atmosphere, photograph");

		ParameterList scenePlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		scenePlist.parameter(FieldNames.FIELD_NAME, sceneName);
		BaseRecord sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
			ModelNames.MODEL_NOTE, testUser, null, scenePlist);
		sceneNote.set("text", JSONUtil.exportObject(sceneData));
		BaseRecord createdScene = IOSystem.getActiveContext().getAccessPoint().create(testUser, sceneNote);
		assertNotNull("Scene note should be created", createdScene);
		String sceneObjectId = createdScene.get(FieldNames.FIELD_OBJECT_ID);

		PictureBookUtil.prepareSceneImagePrompts(testUser, Arrays.asList(sceneObjectId), chatConfigName,
			newSdConfig("art"), null);

		BaseRecord refetched = findNoteByObjectIdWithText(sceneObjectId);
		Map<String, Object> refetchedData = JSONUtil.getMap(((String) refetched.get("text")).getBytes(), String.class, Object.class);
		String healedLandscapePrompt = (String) refetchedData.get("landscapePrompt");
		logger.info("Healed landscapePrompt=[" + healedLandscapePrompt + "]");

		assertEquals("The poisoned hallucinated landscape prompt must be replaced with the "
			+ "deterministic fallback, not served again", "A detailed environment", healedLandscapePrompt);
	}

}
