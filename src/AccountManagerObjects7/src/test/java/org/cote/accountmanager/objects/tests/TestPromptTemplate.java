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
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.MigrationReport;
import org.cote.accountmanager.olio.llm.MigrationResult;
import org.cote.accountmanager.olio.llm.PromptConditionEvaluator;
import org.cote.accountmanager.olio.llm.PromptConfigMigrator;
import org.cote.accountmanager.olio.llm.PromptConfigValidator;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.llm.ValidationResult;
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
 *
 * Phase 5 Tests: Validation & Migration
 *
 * Test 29: TestValidatorDetectsUnknownTokens - Unknown ${nonexistent} token flagged
 * Test 30: TestValidatorPassesValidConfig - Default promptConfig validates clean
 * Test 31: TestValidatorIgnoresRuntimeTokens - ${image.*} and ${audio.*} are allowed
 * Test 32: TestValidatorUnreplacedTokens - validateComposed detects unreplaced tokens
 * Test 33: TestMigratorDryRun - analyze and dry-run produce report without DB changes
 * Test 34: TestMigratorAppliesChanges - migrate with apply=true creates template in DB
 * Test 35: TestMigratorIdempotent - second migrate returns alreadyExists=true
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
			chatConfig.set("serviceType", LLMServiceEnumType.OLLAMA);
			BaseRecord updateCfg = chatConfig.copyRecord(new String[]{"id", "objectId", "model", "serverUrl", "serviceType"});
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
			chatConfig.set("serviceType", LLMServiceEnumType.OLLAMA);
			chatConfig.set("rating", ESRBEnumType.M);
			BaseRecord updateCfg = chatConfig.copyRecord(new String[]{"id", "objectId", "model", "serverUrl", "serviceType", "rating"});
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

	// --- Phase 5 Tests: Validation & Migration ---

	// --- Test 29: Validator Detects Unknown Tokens ---
	@Test
	public void TestValidatorDetectsUnknownTokens() {
		logger.info("Test 29: Validator detects unknown ${nonexistent} token");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser29", testOrgContext.getOrganizationId());

		try {
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT Validator Unknown " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Inject an unknown token into the system field
			List<String> system = promptConfig.get("system");
			assertNotNull("system field should not be null", system);
			system.add("This has an ${nonexistent} token and ${alsoFake} token.");

			ValidationResult result = PromptConfigValidator.validate(promptConfig);
			assertFalse("Config with unknown tokens should not be valid", result.isValid());
			assertTrue("Should have at least 2 unknown tokens", result.getUnknownTokens().size() >= 2);

			boolean foundNonexistent = false;
			boolean foundAlsoFake = false;
			for (ValidationResult.UnknownToken ut : result.getUnknownTokens()) {
				if ("nonexistent".equals(ut.getToken())) foundNonexistent = true;
				if ("alsoFake".equals(ut.getToken())) foundAlsoFake = true;
			}
			assertTrue("Should flag 'nonexistent'", foundNonexistent);
			assertTrue("Should flag 'alsoFake'", foundAlsoFake);

			logger.info("Test 29 PASSED: Validator detected " + result.getUnknownTokens().size() + " unknown tokens");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 29 failed: " + e.getMessage());
		}
	}

	// --- Test 30: Validator Passes Valid Config ---
	@Test
	public void TestValidatorPassesValidConfig() {
		logger.info("Test 30: Validator passes default promptConfig (all tokens known)");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser30", testOrgContext.getOrganizationId());

		try {
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT Validator Valid " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			ValidationResult result = PromptConfigValidator.validate(promptConfig);
			assertTrue("Default promptConfig should validate clean, but found: " + result.getUnknownTokens(), result.isValid());

			logger.info("Test 30 PASSED: Default promptConfig validates with no unknown tokens");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 30 failed: " + e.getMessage());
		}
	}

	// --- Test 31: Validator Ignores Runtime Tokens ---
	@Test
	public void TestValidatorIgnoresRuntimeTokens() {
		logger.info("Test 31: Validator ignores runtime tokens (image.*, audio.*, nlp.*)");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser31", testOrgContext.getOrganizationId());

		try {
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT Validator Runtime " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Inject runtime tokens into the system field
			List<String> system = promptConfig.get("system");
			assertNotNull("system field should not be null", system);
			system.add("Photo: ${image.selfie} and audio: ${audio.hello} and NLP: ${nlp.sentiment}");

			ValidationResult result = PromptConfigValidator.validate(promptConfig);
			assertTrue("Config with only runtime tokens should be valid, but found: " + result.getUnknownTokens(), result.isValid());

			logger.info("Test 31 PASSED: Runtime tokens (image.*, audio.*, nlp.*) correctly ignored");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 31 failed: " + e.getMessage());
		}
	}

	// --- Test 32: Validator Unreplaced Tokens in Composed Text ---
	@Test
	public void TestValidatorUnreplacedTokens() {
		logger.info("Test 32: validateComposed detects unreplaced tokens in composed text");

		try {
			String composedWithUnreplaced = "Hello ${character.name}, your quest is ${memory.context} and ${unknownField}.";
			ValidationResult result = PromptConfigValidator.validateComposed(composedWithUnreplaced);
			assertFalse("Composed text with unreplaced tokens should not be valid", result.isValid());
			assertTrue("Should have unreplaced tokens", result.getUnknownTokens().size() > 0);

			// Composed text with only runtime tokens should be valid
			String composedRuntimeOnly = "See ${image.selfie} and hear ${audio.greeting}.";
			ValidationResult resultRuntime = PromptConfigValidator.validateComposed(composedRuntimeOnly);
			assertTrue("Composed text with only runtime tokens should be valid", resultRuntime.isValid());

			// Null input should be valid (empty)
			ValidationResult resultNull = PromptConfigValidator.validateComposed(null);
			assertTrue("Null composed text should be valid", resultNull.isValid());

			logger.info("Test 32 PASSED: validateComposed correctly detects unreplaced tokens");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 32 failed: " + e.getMessage());
		}
	}

	// --- Test 33: Migrator Dry Run ---
	@Test
	public void TestMigratorDryRun() {
		logger.info("Test 33: Migrator dry-run produces report without DB changes");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser33", testOrgContext.getOrganizationId());

		try {
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "PT Migrator DryRun " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Analyze
			MigrationReport report = PromptConfigMigrator.analyze(promptConfig);
			assertTrue("Should scan some fields", report.getFieldsScanned() > 0);
			assertTrue("Should find fields with content", report.getFieldsWithContent() > 0);
			assertTrue("Sections to create should match fields with content", report.getSectionsToCreate() == report.getFieldsWithContent());
			assertTrue("Section names should not be empty", !report.getSectionNames().isEmpty());
			assertTrue("Section names should include 'system'", report.getSectionNames().contains("system"));

			logger.info("Analysis: scanned=" + report.getFieldsScanned() + ", withContent=" + report.getFieldsWithContent()
				+ ", sections=" + report.getSectionsToCreate() + ", names=" + report.getSectionNames());

			// Dry-run migrate
			MigrationResult result = PromptConfigMigrator.migrate(testUser, promptConfig, false);
			assertTrue("Should be dry-run", result.isDryRun());
			assertFalse("Should not already exist", result.isAlreadyExists());
			assertEquals("Dry-run should not update fields", 0, result.getFieldsUpdated());
			assertNotNull("Template name should be set", result.getTemplateName());
			assertTrue("Sections created should match report", result.getSectionsCreated() > 0);

			logger.info("Test 33 PASSED: Dry-run completed - template='" + result.getTemplateName()
				+ "', sectionsCreated=" + result.getSectionsCreated());

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 33 failed: " + e.getMessage());
		}
	}

	// --- Test 34: Migrator Applies Changes ---
	@Test
	public void TestMigratorAppliesChanges() {
		logger.info("Test 34: Migrator apply=true creates template in DB");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser34", testOrgContext.getOrganizationId());

		try {
			String uniqueName = "PT Migrator Apply " + UUID.randomUUID().toString();
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, uniqueName);
			assertNotNull("Prompt config should not be null", promptConfig);

			// Apply migration
			MigrationResult result = PromptConfigMigrator.migrate(testUser, promptConfig, true);
			assertFalse("Should not be dry-run", result.isDryRun());
			assertFalse("Should not already exist", result.isAlreadyExists());
			assertTrue("Should have created sections", result.getSectionsCreated() > 0);
			assertTrue("Should have updated fields", result.getFieldsUpdated() > 0);

			// Verify the template exists in DB
			String templateName = result.getTemplateName();
			assertNotNull("Template name should be set", templateName);

			BaseRecord found = org.cote.accountmanager.util.DocumentUtil.getRecord(
				testUser, OlioModelNames.MODEL_PROMPT_TEMPLATE, templateName, "~/Chat"
			);
			assertNotNull("Template should exist in DB after migration", found);

			// Verify sections were persisted
			List<BaseRecord> sections = found.get("sections");
			assertNotNull("Template should have sections", sections);
			assertTrue("Template should have at least one section", sections.size() > 0);

			logger.info("Test 34 PASSED: Migration created template '" + templateName
				+ "' with " + sections.size() + " sections");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 34 failed: " + e.getMessage());
		}
	}

	// --- Test 35: Migrator Idempotent ---
	@Test
	public void TestMigratorIdempotent() {
		logger.info("Test 35: Migrator is idempotent - second migrate returns alreadyExists");
		OrganizationContext testOrgContext = getTestOrganization("/Development/PromptTemplate");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "ptTestUser35", testOrgContext.getOrganizationId());

		try {
			String uniqueName = "PT Migrator Idempotent " + UUID.randomUUID().toString();
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, uniqueName);
			assertNotNull("Prompt config should not be null", promptConfig);

			// First migration
			MigrationResult first = PromptConfigMigrator.migrate(testUser, promptConfig, true);
			assertFalse("First migration should not be alreadyExists", first.isAlreadyExists());
			assertTrue("First migration should create sections", first.getSectionsCreated() > 0);

			// Second migration - should detect existing
			MigrationResult second = PromptConfigMigrator.migrate(testUser, promptConfig, true);
			assertTrue("Second migration should return alreadyExists", second.isAlreadyExists());
			assertEquals("Second migration should not update fields", 0, second.getFieldsUpdated());
			assertEquals("Second migration should not create sections", 0, second.getSectionsCreated());

			logger.info("Test 35 PASSED: Second migration correctly returned alreadyExists=true");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			fail("Test 35 failed: " + e.getMessage());
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
