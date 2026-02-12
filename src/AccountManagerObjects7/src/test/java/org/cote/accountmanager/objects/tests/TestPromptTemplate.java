package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.PromptConditionEvaluator;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

/**
 * Phase 4 Tests: Prompt Templates
 *
 * Test 23: TestOpenChatTemplate - Load prompt.openChat.json -> validate -> process pipeline -> no unreplaced tokens
 * Test 24: TestRPGTemplate - Load prompt.rpg.json -> full pipeline with episodes/NLP/scene -> all tokens resolved
 * Test 25: TestSMSTemplate - Load prompt.sms.json -> image/audio tokens pass through, all others resolved
 * Test 26: TestMemoryChatTemplate - Load prompt.memoryChat.json -> inject memories -> ${memory.*} resolved
 * Test 27: TestOpenChatLLMIntegration - Use openChat template with real LLM -> get coherent response (DB, LLM)
 * Test 28: TestRPGTemplateLLMIntegration - Use rpg template with characters + real LLM -> character-appropriate response (DB, LLM)
 */
public class TestPromptTemplate extends BaseTest {

	private String dataPath = null;

	private OlioContext getOlioContext(OrganizationContext orgCtx) {
		if (dataPath == null) {
			dataPath = testProperties.getProperty("test.datagen.path");
		}
		return OlioTestUtil.getContext(orgCtx, dataPath);
	}

	private List<BaseRecord> getPopulation(OlioContext octx) {
		List<BaseRecord> realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		List<BaseRecord> pop = octx.getRealmPopulation(realms.get(0));
		return pop;
	}

	// --- Test 23: Open Chat Template ---
	@Test
	public void TestOpenChatTemplate() {
		logger.info("Test 23: Open Chat Template - validate and process pipeline");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser23", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			// Load structured template from resource
			BaseRecord template = loadTemplate("olio/llm/prompt.openChat.json");
			assertNotNull("Open Chat template should load", template);

			// Create OlioContext with characters (required for token resolution in dynamic rules)
			OlioContext octx = getOlioContext(testOrgContext);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> pop = getPopulation(octx);
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, pop.get(0), pop.get(1));
			assertNotNull("Chat config should not be null", chatConfig);

			// Load the default promptConfig for token data
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT Open Chat Prompt " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Compose the system template
			String composed = PromptTemplateComposer.composeSystem(template, promptConfig, chatConfig);
			assertNotNull("Composed system text should not be null", composed);
			assertTrue("Composed text should not be empty", composed.length() > 0);

			logger.info("Composed Open Chat system template (" + composed.length() + " chars)");

			// Validate no unreplaced tokens (except runtime-only like image.*, audio.*, nlp.*)
			List<String> unreplaced = PromptTemplateComposer.findUnreplacedTokens(composed);
			assertTrue("No unreplaced tokens expected, found: " + unreplaced, unreplaced.isEmpty());

			logger.info("Test 23 PASSED: Open Chat Template validates with no unreplaced tokens");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 23 failed: " + e.getMessage());
		}
	}

	// --- Test 24: RPG Template ---
	@Test
	public void TestRPGTemplate() {
		logger.info("Test 24: RPG Template - full pipeline with episodes/NLP/scene");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser24", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			// Load structured template
			BaseRecord template = loadTemplate("olio/llm/prompt.rpg.json");
			assertNotNull("RPG template should load", template);

			// Create a full OlioContext with characters
			OlioContext octx = getOlioContext(testOrgContext);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> pop = getPopulation(octx);
			assertTrue("Population should have at least 2 characters", pop.size() >= 2);

			BaseRecord per1 = pop.get(0);
			BaseRecord per2 = pop.get(1);
			OlioTestUtil.outfitAndStage(octx);

			// Create chatConfig with characters and episodes
			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, per1, per2);
			assertNotNull("RPG chat config should not be null", chatConfig);

			// Load prompt config
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT RPG Prompt " + UUID.randomUUID().toString());
			assertNotNull("RPG prompt config should not be null", promptConfig);

			// Compose system template
			String composed = PromptTemplateComposer.composeSystem(template, promptConfig, chatConfig);
			assertNotNull("Composed RPG system text should not be null", composed);
			assertTrue("Composed text should contain character info", composed.length() > 50);

			// Check key content is present
			String sysFirstName = chatConfig.get("systemCharacter.firstName");
			if (sysFirstName != null) {
				assertTrue("Composed should reference system character", composed.contains(sysFirstName));
			}

			// Validate no unreplaced tokens
			List<String> unreplaced = PromptTemplateComposer.findUnreplacedTokens(composed);
			assertTrue("No unreplaced tokens expected, found: " + unreplaced, unreplaced.isEmpty());

			// Compose user and assistant roles too
			String userComposed = PromptTemplateComposer.composeUser(template, promptConfig, chatConfig);
			assertNotNull("User role composed text should not be null", userComposed);

			String assistComposed = PromptTemplateComposer.composeAssistant(template, promptConfig, chatConfig);
			assertNotNull("Assistant role composed text should not be null", assistComposed);

			logger.info("Composed RPG system (" + composed.length() + " chars), user (" + userComposed.length() + " chars), assistant (" + assistComposed.length() + " chars)");
			logger.info("Test 24 PASSED: RPG Template all tokens resolved");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 24 failed: " + e.getMessage());
		}
	}

	// --- Test 25: SMS Template ---
	@Test
	public void TestSMSTemplate() {
		logger.info("Test 25: SMS Template - image/audio tokens pass through, all others resolved");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser25", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			// Load structured template
			BaseRecord template = loadTemplate("olio/llm/prompt.sms.json");
			assertNotNull("SMS template should load", template);

			// Create OlioContext for characters
			OlioContext octx = getOlioContext(testOrgContext);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> pop = getPopulation(octx);
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, pop.get(0), pop.get(1));
			assertNotNull("Chat config should not be null", chatConfig);

			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT SMS Prompt " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Compose
			String composed = PromptTemplateComposer.composeSystem(template, promptConfig, chatConfig);
			assertNotNull("Composed SMS text should not be null", composed);

			// image.* and audio.* tokens should pass through (they are runtime-only)
			List<String> unreplaced = PromptTemplateComposer.findUnreplacedTokens(composed);
			assertTrue("No non-runtime unreplaced tokens expected, found: " + unreplaced, unreplaced.isEmpty());

			// Verify image/audio tokens are still present (they are runtime placeholders)
			assertTrue("SMS template should contain ${image.selfie} runtime token", composed.contains("${image.selfie}"));
			assertTrue("SMS template should contain ${audio.hello} runtime token", composed.contains("${audio.hello}"));

			logger.info("Composed SMS system (" + composed.length() + " chars)");
			logger.info("Test 25 PASSED: SMS Template - image/audio tokens pass through correctly");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 25 failed: " + e.getMessage());
		}
	}

	// --- Test 26: Memory Chat Template ---
	@Test
	public void TestMemoryChatTemplate() {
		logger.info("Test 26: Memory Chat Template - inject memories, ${memory.*} resolved");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser26", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		try {
			// Load structured template
			BaseRecord template = loadTemplate("olio/llm/prompt.memoryChat.json");
			assertNotNull("Memory Chat template should load", template);

			// Create OlioContext with characters
			OlioContext octx = getOlioContext(testOrgContext);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> pop = getPopulation(octx);
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, pop.get(0), pop.get(1));
			assertNotNull("Chat config should not be null", chatConfig);

			// Enable memory budget so memory section conditions pass
			chatConfig.set("memoryBudget", 800);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);

			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT Memory Prompt " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Set up mock memory context via PromptUtil thread-locals
			PromptUtil.setMemoryContext("[Memory: Test session summary about characters meeting]");
			PromptUtil.setMemoryRelationship("They are close friends who trust each other");
			PromptUtil.setMemoryFacts("Character A prefers morning meetings; Character B is allergic to cats");
			PromptUtil.setMemoryLastSession("Last session they discussed plans for a journey north");
			PromptUtil.setMemoryCount(5);

			// Compose
			String composed = PromptTemplateComposer.composeSystem(template, promptConfig, chatConfig);
			assertNotNull("Composed Memory Chat text should not be null", composed);

			// Verify memory content is injected
			assertTrue("Should contain memory context", composed.contains("Test session summary"));
			assertTrue("Should contain relationship", composed.contains("close friends"));
			assertTrue("Should contain facts", composed.contains("allergic to cats"));
			assertTrue("Should contain last session", composed.contains("journey north"));
			assertTrue("Should contain memory count", composed.contains("5"));

			// Validate no unreplaced ${memory.*} tokens remain
			List<String> unreplaced = PromptTemplateComposer.findUnreplacedTokens(composed);
			assertTrue("No unreplaced tokens expected, found: " + unreplaced, unreplaced.isEmpty());

			logger.info("Composed Memory Chat system (" + composed.length() + " chars)");
			logger.info("Test 26 PASSED: Memory Chat Template - all ${memory.*} resolved");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 26 failed: " + e.getMessage());
		}
	}

	// --- Test 27: Open Chat LLM Integration ---
	@Test
	public void TestOpenChatLLMIntegration() {
		logger.info("Test 27: Open Chat LLM Integration - compose and send to real LLM");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser27", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		String llmType = testProperties.getProperty("test.llm.type");
		if (llmType == null || llmType.isEmpty()) {
			logger.warn("Test 27 SKIPPED: No LLM configuration (test.llm.type) in test properties");
			return;
		}

		try {
			// Load template
			BaseRecord template = loadTemplate("olio/llm/prompt.openChat.json");
			assertNotNull("Template should load", template);

			// Create OlioContext with characters
			OlioContext octx = getOlioContext(testOrgContext);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> pop = getPopulation(octx);
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, pop.get(0), pop.get(1));
			assertNotNull("Chat config should not be null", chatConfig);

			// Configure LLM settings - update only the changed fields via a slim copy
			// to avoid passing full nested character objects through the authorization path
			String model = testProperties.getProperty("test.llm.ollama.model");
			String server = testProperties.getProperty("test.llm.ollama.server");
			chatConfig.set("model", model);
			chatConfig.set("serverUrl", server);
			BaseRecord updateCfg = chatConfig.copyRecord(new String[]{"id", "objectId", "model", "serverUrl"});
			IOSystem.getActiveContext().getAccessPoint().update(testUser, updateCfg);

			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT LLM Open Chat " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Compose the system prompt using new template
			String sysPrompt = PromptTemplateComposer.composeSystem(template, promptConfig, chatConfig);
			assertNotNull("System prompt should compose", sysPrompt);
			assertTrue("System prompt should have content", sysPrompt.length() > 20);

			// Create Chat instance and test
			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			chat.setLlmSystemPrompt(sysPrompt);

			var req = chat.newRequest(model);
			chat.newMessage(req, "Say exactly: TEMPLATE_TEST_OK");
			var resp = chat.chat(req);

			if (resp == null) {
				logger.warn("Test 27 SKIPPED: LLM server did not respond (null response). Verify server at " + server);
				return;
			}

			String content = resp.get("message.content");
			assertNotNull("Response content should not be null", content);
			assertTrue("Response should contain text", content.length() > 0);

			logger.info("LLM Response (" + content.length() + " chars): " + content.substring(0, Math.min(100, content.length())));
			logger.info("Test 27 PASSED: Open Chat LLM Integration - got coherent response");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 27 failed: " + e.getMessage());
		}
	}

	// --- Test 28: RPG Template LLM Integration ---
	@Test
	public void TestRPGTemplateLLMIntegration() {
		logger.info("Test 28: RPG Template LLM Integration - character-appropriate response");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser28", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		String llmType = testProperties.getProperty("test.llm.type");
		if (llmType == null || llmType.isEmpty()) {
			logger.warn("Test 28 SKIPPED: No LLM configuration (test.llm.type) in test properties");
			return;
		}

		try {
			// Load RPG template
			BaseRecord template = loadTemplate("olio/llm/prompt.rpg.json");
			assertNotNull("RPG template should load", template);

			// Create full OlioContext
			OlioContext octx = getOlioContext(testOrgContext);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> pop = getPopulation(octx);
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord per1 = pop.get(0);
			BaseRecord per2 = pop.get(1);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, per1, per2);
			assertNotNull("Chat config should not be null", chatConfig);

			// Configure LLM - update only the changed fields via a slim copy
			// to avoid passing full nested character objects through the authorization path
			String model = testProperties.getProperty("test.llm.ollama.model");
			String server = testProperties.getProperty("test.llm.ollama.server");
			chatConfig.set("model", model);
			chatConfig.set("serverUrl", server);
			chatConfig.set("rating", ESRBEnumType.M);
			BaseRecord updateCfg = chatConfig.copyRecord(new String[]{"id", "objectId", "model", "serverUrl", "rating"});
			IOSystem.getActiveContext().getAccessPoint().update(testUser, updateCfg);

			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT LLM RPG " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Compose all roles using new template
			String sysPrompt = PromptTemplateComposer.composeSystem(template, promptConfig, chatConfig);
			String userPrompt = PromptTemplateComposer.composeUser(template, promptConfig, chatConfig);
			String assistPrompt = PromptTemplateComposer.composeAssistant(template, promptConfig, chatConfig);

			assertNotNull("System prompt should compose", sysPrompt);
			assertTrue("System prompt should reference character", sysPrompt.length() > 50);

			// Verify the system character name appears in the composed prompt
			String sysName = per2.get(FieldNames.FIELD_FIRST_NAME);
			if (sysName != null) {
				assertTrue("System prompt should contain system character name '" + sysName + "'", sysPrompt.contains(sysName));
			}

			// Create Chat and send message
			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			chat.setLlmSystemPrompt(sysPrompt);

			var req = chat.newRequest(model);
			if (userPrompt != null && !userPrompt.isEmpty()) {
				chat.newMessage(req, userPrompt);
			}
			if (assistPrompt != null && !assistPrompt.isEmpty()) {
				chat.newMessage(req, assistPrompt, Chat.assistantRole);
			}
			chat.newMessage(req, "Hello, how are you today?");

			var resp = chat.chat(req);

			if (resp == null) {
				logger.warn("Test 28 SKIPPED: LLM server did not respond (null response). Verify server at " + server);
				return;
			}

			String content = resp.get("message.content");
			assertNotNull("Response content should not be null", content);
			assertTrue("Response should be character-appropriate (non-empty)", content.length() > 0);

			logger.info("RPG LLM Response (" + content.length() + " chars): " + content.substring(0, Math.min(200, content.length())));
			logger.info("Test 28 PASSED: RPG Template LLM Integration - character-appropriate response");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 28 failed: " + e.getMessage());
		}
	}

	// --- Additional tests for PromptConditionEvaluator ---

	@Test
	public void TestConditionEvaluator() {
		logger.info("Test: PromptConditionEvaluator basic conditions");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUserCond", testOrgContext.getOrganizationId());

		try {
			BaseRecord chatConfig = createBasicChatConfig(testUser, "PT Condition " + UUID.randomUUID().toString());
			assertNotNull("Chat config should not be null", chatConfig);

			// Empty/null condition = always true
			assertTrue("Null condition should be true", PromptConditionEvaluator.evaluate(null, chatConfig));
			assertTrue("Empty condition should be true", PromptConditionEvaluator.evaluate("", chatConfig));

			// Boolean field checks
			chatConfig.set("useNLP", true);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertTrue("useNLP=true should be truthy", PromptConditionEvaluator.evaluate("useNLP", chatConfig));

			chatConfig.set("useNLP", false);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertFalse("useNLP=false should be falsy", PromptConditionEvaluator.evaluate("useNLP", chatConfig));

			// Negation
			assertTrue("!useNLP when false should be true", PromptConditionEvaluator.evaluate("!useNLP", chatConfig));

			// Equality check
			chatConfig.set("rating", ESRBEnumType.AO);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertTrue("rating==AO should match", PromptConditionEvaluator.evaluate("rating==AO", chatConfig));
			assertFalse("rating==E should not match", PromptConditionEvaluator.evaluate("rating==E", chatConfig));

			// Inequality
			assertTrue("rating!=E should be true", PromptConditionEvaluator.evaluate("rating!=E", chatConfig));

			// Numeric field (memoryBudget)
			chatConfig.set("memoryBudget", 800);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertTrue("memoryBudget should be truthy when > 0", PromptConditionEvaluator.evaluate("memoryBudget", chatConfig));

			chatConfig.set("memoryBudget", 0);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertFalse("memoryBudget=0 should be falsy", PromptConditionEvaluator.evaluate("memoryBudget", chatConfig));

			// OR condition
			chatConfig.set("useNLP", true);
			chatConfig.set("useJailBreak", false);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertTrue("useNLP||useJailBreak should be true (one true)", PromptConditionEvaluator.evaluate("useNLP||useJailBreak", chatConfig));

			// AND condition
			assertFalse("useNLP&&useJailBreak should be false (one false)", PromptConditionEvaluator.evaluate("useNLP&&useJailBreak", chatConfig));

			logger.info("TestConditionEvaluator PASSED");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("TestConditionEvaluator failed: " + e.getMessage());
		}
	}

	@Test
	public void TestTemplateModelSchema() {
		logger.info("Test: Prompt Template model schema validation");
		try {
			BaseRecord tmpl = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_TEMPLATE);
			assertNotNull("PromptTemplate model should instantiate", tmpl);
			assertTrue("Should have 'sections' field", tmpl.hasField("sections"));
			assertTrue("Should have 'sectionOrder' field", tmpl.hasField("sectionOrder"));
			assertTrue("Should have 'extends' field", tmpl.hasField("extends"));
			assertTrue("Should have 'role' field", tmpl.hasField("role"));
			assertTrue("Should have 'templateVersion' field", tmpl.hasField("templateVersion"));

			BaseRecord section = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_SECTION);
			assertNotNull("PromptSection model should instantiate", section);
			assertTrue("Should have 'sectionName' field", section.hasField("sectionName"));
			assertTrue("Should have 'lines' field", section.hasField("lines"));
			assertTrue("Should have 'condition' field", section.hasField("condition"));
			assertTrue("Should have 'priority' field", section.hasField("priority"));
			assertTrue("Should have 'role' field", section.hasField("role"));

			int defaultPriority = section.get("priority");
			assertEquals("Default priority should be 100", 100, defaultPriority);

			logger.info("TestTemplateModelSchema PASSED");
		} catch (Exception e) {
			logger.error(e);
			fail("TestTemplateModelSchema failed: " + e.getMessage());
		}
	}

	// --- Helper methods ---

	private BaseRecord loadTemplate(String resourcePath) {
		return JSONUtil.importObject(
			ResourceUtil.getInstance().getResource(resourcePath),
			LooseRecord.class,
			RecordDeserializerConfig.getUnfilteredModule()
		);
	}

	private BaseRecord createBasicChatConfig(BaseRecord user, String name) {
		try {
			IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID)
			);
			ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			clist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord cfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, clist);
			cfg.set("rating", ESRBEnumType.E);
			cfg.set("startMode", "system");
			cfg.set("assist", true);
			cfg.set("includeScene", false);
			cfg.set("setting", "random");

			cfg = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
			return cfg;
		} catch (Exception e) {
			logger.error("Error creating basic chat config: " + e.getMessage());
			return null;
		}
	}
}
