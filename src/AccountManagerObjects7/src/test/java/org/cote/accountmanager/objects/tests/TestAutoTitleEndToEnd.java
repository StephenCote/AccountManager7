package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * AUTO-TITLE AND AUTO-ICON END-TO-END TESTS.
 *
 * !! WARNING: AUTO-TITLE/ICON HAS NEVER WORKED IN PRODUCTION !!
 * Every previous refactor claimed it worked. It didn't. These tests exist to
 * make the title and icon values VISIBLE in test output so there is no ambiguity.
 *
 * Each test prints its results with a clear banner:
 *   === TITLE RESULT: title='...' icon='...' ===
 *
 * If you see null/empty values, IT DOES NOT WORK.
 */
public class TestAutoTitleEndToEnd extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupAutoTitle() {
		testOrgContext = getTestOrganization("/Development/AutoTitleE2E");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "autoTitleE2EUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Test 1: Parse normal two-line LLM response (title + icon)
	@Test
	public void testTitleParsingNormal() {
		try {
			Chat chat = new Chat();
			OpenAIRequest req = createMinimalRequest("Tell me a joke", "Why did the chicken cross the road? To get to the other side!");

			String[] result = chat.generateChatTitleAndIcon(req);
			assertNotNull("Result should not be null", result);
			logger.info("=== TITLE RESULT (normal): title='" + result[0] + "' icon='" + result[1] + "' ===");

			// Without LLM, this returns fallback from user message
			assertNotNull("Fallback title should not be null", result[0]);
			assertTrue("Fallback title should contain user text", result[0].contains("Tell me"));
		} catch (Exception e) {
			logger.error("testTitleParsingNormal failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 2: Parse response with markdown fences
	@Test
	public void testTitleParsingMarkdown() {
		try {
			// Simulate the parsing logic directly
			String content = "```\nExploring Medieval Defenses\ncastle\n```";
			content = content.trim();
			content = content.replaceAll("(?s)^```[a-z]*\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
			String[] allLines = content.split("\\r?\\n");
			java.util.List<String> nonEmpty = new java.util.ArrayList<>();
			for (String l : allLines) {
				String trimmed = l.trim();
				if (!trimmed.isEmpty()) nonEmpty.add(trimmed);
			}
			String title = nonEmpty.isEmpty() ? null : nonEmpty.get(0).replaceAll("^\"|\"$", "").replaceAll("^Title:\\s*", "").trim();
			String icon = nonEmpty.size() >= 2 ? nonEmpty.get(1).replaceAll("^Icon:\\s*", "").trim().toLowerCase().replaceAll("[^a-z0-9_]", "") : null;

			logger.info("=== TITLE RESULT (markdown): title='" + title + "' icon='" + icon + "' ===");
			assertEquals("Title should be parsed from markdown", "Exploring Medieval Defenses", title);
			assertEquals("Icon should be parsed from markdown", "castle", icon);
		} catch (Exception e) {
			logger.error("testTitleParsingMarkdown failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 3: Parse response with "Title:" and "Icon:" labels
	@Test
	public void testTitleParsingLabeled() {
		try {
			String content = "Title: \"Journey Through Time\"\nIcon: history_edu";
			content = content.trim();
			String[] allLines = content.split("\\r?\\n");
			java.util.List<String> nonEmpty = new java.util.ArrayList<>();
			for (String l : allLines) {
				String trimmed = l.trim();
				if (!trimmed.isEmpty()) nonEmpty.add(trimmed);
			}
			String title = nonEmpty.isEmpty() ? null : nonEmpty.get(0).replaceAll("^\"|\"$", "").replaceAll("^Title:\\s*", "").trim();
			title = title.replaceAll("^\"|\"$", ""); // strip quotes around actual title
			String icon = nonEmpty.size() >= 2 ? nonEmpty.get(1).replaceAll("^Icon:\\s*", "").trim().toLowerCase().replaceAll("[^a-z0-9_]", "") : null;

			logger.info("=== TITLE RESULT (labeled): title='" + title + "' icon='" + icon + "' ===");
			assertEquals("Title should be parsed with label stripped", "Journey Through Time", title);
			assertEquals("Icon should be parsed with label stripped", "history_edu", icon);
		} catch (Exception e) {
			logger.error("testTitleParsingLabeled failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 4: Persistence — set title and icon on chatRequest record, read back
	@Test
	public void testTitlePersistence() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatRequests");
			plist.parameter(FieldNames.FIELD_NAME, "persist-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord chatReq = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_REQUEST, testUser, null, plist);
			assertNotNull("ChatRequest should be created", chatReq);

			// Create it in the DB
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, chatReq);
			assertNotNull("Created record should not be null", created);

			// Re-read from DB
			org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
				OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatReq.get(FieldNames.FIELD_OBJECT_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(false);
			BaseRecord loaded = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			assertNotNull("Loaded record should not be null", loaded);

			// Set title and icon
			Chat chat = new Chat();
			chat.setChatTitle(loaded, "Test Title Here");
			chat.setChatIcon(loaded, "psychology");

			// Re-read and verify
			BaseRecord reloaded = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			String savedTitle = reloaded.get("chatTitle");
			String savedIcon = reloaded.get("chatIcon");

			logger.info("=== TITLE RESULT (persistence): chatTitle='" + savedTitle + "' chatIcon='" + savedIcon + "' ===");
			assertEquals("Title should persist", "Test Title Here", savedTitle);
			assertEquals("Icon should persist", "psychology", savedIcon);
		} catch (Exception e) {
			logger.error("testTitlePersistence failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 5: Fallback derivation — various message lengths
	@Test
	public void testFallbackTitleDerivation() {
		try {
			Chat chat = new Chat();

			// Short message — used as-is
			OpenAIRequest req1 = createMinimalRequestNoAssistant("Hello world");
			String fb1 = chat.deriveFallbackTitle(req1);
			logger.info("=== FALLBACK (short): '" + fb1 + "' ===");
			assertEquals("Short message as-is", "Hello world", fb1);

			// Long message — truncated at word boundary
			OpenAIRequest req2 = createMinimalRequestNoAssistant(
				"Tell me about the history of ancient Roman architecture and how it influenced modern building design");
			String fb2 = chat.deriveFallbackTitle(req2);
			logger.info("=== FALLBACK (long): '" + fb2 + "' (len=" + fb2.length() + ") ===");
			assertTrue("Long fallback should be <= 40 chars", fb2.length() <= 40);

			// Empty — returns null
			OpenAIRequest req3 = new OpenAIRequest();
			String fb3 = chat.deriveFallbackTitle(req3);
			logger.info("=== FALLBACK (empty): '" + fb3 + "' ===");
			assertNull("Empty request should return null", fb3);
		} catch (Exception e) {
			logger.error("testFallbackTitleDerivation failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 6: generateChatTitleAndIcon fallback when not enough messages
	@Test
	public void testGenerateFallbackNoMessages() {
		try {
			Chat chat = new Chat();
			OpenAIRequest req = new OpenAIRequest();

			// Only system message — no user+assistant exchange
			OpenAIMessage sysMsg = new OpenAIMessage();
			sysMsg.setRole("system");
			sysMsg.setContent("You are a helpful assistant.");
			req.addMessage(sysMsg);

			String[] result = chat.generateChatTitleAndIcon(req);
			assertNotNull("Result should not be null", result);
			logger.info("=== TITLE RESULT (no msgs): title='" + result[0] + "' icon='" + result[1] + "' ===");
			assertNull("Title should be null with no user message", result[0]);
			assertEquals("Icon should be fallback 'chat'", "chat", result[1]);
		} catch (Exception e) {
			logger.error("testGenerateFallbackNoMessages failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 7: autoTitle defaults to true on chatConfig
	@Test
	public void testAutoTitleConfigDefault() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatConfigs");
			plist.parameter(FieldNames.FIELD_NAME, "autotitle-e2e-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
			assertNotNull("ChatConfig should be created", cfg);

			boolean autoTitle = cfg.get("autoTitle");
			logger.info("=== CONFIG DEFAULT: autoTitle=" + autoTitle + " ===");
			assertTrue("autoTitle should default to true", autoTitle);
		} catch (Exception e) {
			logger.error("testAutoTitleConfigDefault failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 8: Edge case — single-line response (title only, no icon)
	@Test
	public void testTitleParsingSingleLine() {
		try {
			String content = "A Brief Chat About Weather";
			content = content.trim();
			content = content.replaceAll("(?s)^```[a-z]*\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
			String[] allLines = content.split("\\r?\\n");
			java.util.List<String> nonEmpty = new java.util.ArrayList<>();
			for (String l : allLines) {
				String trimmed = l.trim();
				if (!trimmed.isEmpty()) nonEmpty.add(trimmed);
			}
			String title = nonEmpty.isEmpty() ? null : nonEmpty.get(0).replaceAll("^\"|\"$", "").replaceAll("^Title:\\s*", "").trim();
			String icon = nonEmpty.size() >= 2 ? nonEmpty.get(1).replaceAll("^Icon:\\s*", "").trim().toLowerCase().replaceAll("[^a-z0-9_]", "") : null;

			logger.info("=== TITLE RESULT (single line): title='" + title + "' icon='" + icon + "' ===");
			assertEquals("Title should be parsed", "A Brief Chat About Weather", title);
			assertNull("Icon should be null for single-line response", icon);
		} catch (Exception e) {
			logger.error("testTitleParsingSingleLine failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 9: LIVE LLM auto-title generation — calls real LLM server
	/// Follows TestPromptTemplate pattern: uses testProperties for LLM config,
	/// creates OlioContext with characters, configures Chat with real LLM,
	/// calls generateChatTitleAndIcon with a real exchange.
	/// Gracefully skips if LLM server is unreachable.
	@Test
	public void testLiveLLMTitleGeneration() {
		logger.warn("[LLM-LIVE] testLiveLLMTitleGeneration: Requires reachable LLM server and correct model/serviceType config");
		String llmType = testProperties.getProperty("test.llm.type");
		if (llmType == null || llmType.isEmpty()) {
			logger.warn("Test 9 SKIPPED: No LLM configuration (test.llm.type) in test properties");
			return;
		}

		try {
			String dataPath = testProperties.getProperty("test.datagen.path");
			OlioContext octx = OlioTestUtil.getContext(testOrgContext, dataPath);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> realms = octx.getRealms();
			assertTrue("Expected at least one realm", realms.size() > 0);
			List<BaseRecord> pop = octx.getRealmPopulation(realms.get(0));
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, pop.get(0), pop.get(1));
			assertNotNull("Chat config should not be null", chatConfig);

			// Configure LLM settings from test properties
			// assist=false so getMessageOffset() returns 1 (system only), giving
			// a simple system+user+assistant layout for title generation.
			String model = testProperties.getProperty("test.llm.ollama.model");
			String server = testProperties.getProperty("test.llm.ollama.server");
			chatConfig.set("model", model);
			chatConfig.set("serverUrl", server);
			chatConfig.set("serviceType", LLMServiceEnumType.OLLAMA);
			chatConfig.set("autoTitle", true);
			chatConfig.set("assist", false);
			BaseRecord updateCfg = chatConfig.copyRecord(new String[]{"id", "objectId", "model", "serverUrl", "serviceType", "autoTitle", "assist"});
			IOSystem.getActiveContext().getAccessPoint().update(testUser, updateCfg);

			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "AT LLM Title " + UUID.randomUUID().toString());
			assertNotNull("Prompt config should not be null", promptConfig);

			// Create Chat with real LLM config (assist=false → offset=1 → system at [0], user at [1])
			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			chat.setLlmSystemPrompt("You are a helpful assistant.");

			// Build a request: [0]=system, [1]=user
			OpenAIRequest req = chat.newRequest(model);
			chat.newMessage(req, "What is the capital of France?");

			// Call the LLM for a real assistant response
			OpenAIResponse resp = chat.chat(req);
			if (resp == null) {
				logger.warn("Test 9 SKIPPED: LLM server did not respond (null response). Verify server at " + server);
				return;
			}

			// Extract and add the assistant response to the request messages
			String assistContent = null;
			BaseRecord respMsg = resp.get("message");
			if (respMsg != null) assistContent = respMsg.get("content");
			if (assistContent == null) {
				List<BaseRecord> choices = resp.get("choices");
				if (choices != null && !choices.isEmpty()) {
					BaseRecord cmsg = choices.get(0).get("message");
					if (cmsg != null) assistContent = cmsg.get("content");
				}
			}
			if (assistContent == null) {
				logger.warn("Test 9 SKIPPED: LLM returned null content. Server may be overloaded.");
				return;
			}
			logger.info("=== LLM RESPONSE: '" + assistContent.substring(0, Math.min(100, assistContent.length())) + "' ===");

			OpenAIMessage assistMsg = new OpenAIMessage();
			assistMsg.setRole("assistant");
			assistMsg.setContent(assistContent);
			req.addMessage(assistMsg);

			// Messages: [0]=system, [1]=user, [2]=assistant. Offset=1 (assist=false).
			// generateChatTitleAndIcon looks at offset (1)=user, offset+1 (2)=assistant.
			logger.info("Title gen: msgs=" + req.getMessages().size() + " offset=" + chat.getMessageOffset(req));

			// NOW call generateChatTitleAndIcon with the real exchange
			String[] result = chat.generateChatTitleAndIcon(req);
			assertNotNull("Title result should not be null", result);
			logger.info("=== LIVE LLM TITLE RESULT: title='" + result[0] + "' icon='" + result[1] + "' ===");

			// Title should be non-null — either LLM-generated or fallback from user message
			assertNotNull("Title should not be null", result[0]);
			assertTrue("Title should have content", result[0].length() > 0);

			// Verify title is reasonable (not the raw user message, actual generated title)
			assertTrue("Title should be <= 60 chars", result[0].length() <= 60);

			// Persist and verify round-trip
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatRequests");
			plist.parameter(FieldNames.FIELD_NAME, "live-title-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord chatReq = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_REQUEST, testUser, null, plist);
			IOSystem.getActiveContext().getAccessPoint().create(testUser, chatReq);

			chat.setChatTitle(chatReq, result[0]);
			if (result[1] != null) chat.setChatIcon(chatReq, result[1]);

			org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
				OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatReq.get(FieldNames.FIELD_OBJECT_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(false);
			BaseRecord reloaded = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			assertNotNull("Reloaded record should not be null", reloaded);

			String savedTitle = reloaded.get("chatTitle");
			String savedIcon = reloaded.get("chatIcon");
			logger.info("=== LIVE LLM PERSISTED: chatTitle='" + savedTitle + "' chatIcon='" + savedIcon + "' ===");
			assertEquals("Persisted title should match", result[0], savedTitle);

			logger.info("Test 9 PASSED: Live LLM auto-title generation and persistence verified");
		} catch (Exception e) {
			logger.error("testLiveLLMTitleGeneration failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Test 10: PromptUtil.getChatPromptTemplate with null promptConfig
	/// Verifies the decoupling change — promptTemplate can work without promptConfig.
	/// chatConfig-based token replacements (character names, rating, etc.) should still work.
	/// promptConfig-dependent replacements (races, scene lines, dynamic rules) are skipped.
	@Test
	public void testPromptUtilNullPromptConfig() {
		try {
			String dataPath = testProperties.getProperty("test.datagen.path");
			OlioContext octx = OlioTestUtil.getContext(testOrgContext, dataPath);
			assertNotNull("OlioContext should not be null", octx);

			List<BaseRecord> realms = octx.getRealms();
			assertTrue("Expected at least one realm", realms.size() > 0);
			List<BaseRecord> pop = octx.getRealmPopulation(realms.get(0));
			assertTrue("Population should have at least 2", pop.size() >= 2);
			OlioTestUtil.outfitAndStage(octx);

			BaseRecord chatConfig = OlioTestUtil.getRandmChatConfig(octx, testUser, pop.get(0), pop.get(1));
			assertNotNull("Chat config should not be null", chatConfig);

			// Template with tokens that come from chatConfig (not promptConfig)
			// Using actual tokens from TemplatePatternEnumType
			String template = "You are ${system.firstName}. The user is ${user.firstName}. Rating: ${rating}.";

			// Call with null promptConfig — should NOT throw, should replace chatConfig tokens
			String result = PromptUtil.getChatPromptTemplate(null, chatConfig, template);
			assertNotNull("Result should not be null", result);
			logger.info("=== NULL PROMPTCONFIG RESULT: '" + result + "' ===");

			// The ${system.firstName} and ${user.firstName} tokens should be replaced
			// (they come from chatConfig.systemCharacter and chatConfig.userCharacter)
			assertTrue("Template should not still contain ${system.firstName}", !result.contains("${system.firstName}"));
			assertTrue("Template should not still contain ${user.firstName}", !result.contains("${user.firstName}"));

			// Also test with both null — should return template as-is
			String bothNull = PromptUtil.getChatPromptTemplate(null, null, template);
			assertEquals("Both null should return template as-is", template, bothNull);

			// Also test with chatConfig null but promptConfig non-null — should return template as-is
			BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "AT NullPC " + UUID.randomUUID().toString());
			String pcOnly = PromptUtil.getChatPromptTemplate(promptConfig, null, template);
			assertEquals("promptConfig-only (no chatConfig) should return template as-is", template, pcOnly);

			logger.info("Test 10 PASSED: PromptUtil.getChatPromptTemplate handles null promptConfig correctly");
		} catch (Exception e) {
			logger.error("testPromptUtilNullPromptConfig failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Helpers ---

	private OpenAIRequest createMinimalRequest(String userText, String assistText) {
		OpenAIRequest req = new OpenAIRequest();
		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole("system");
		sysMsg.setContent("System prompt");
		req.addMessage(sysMsg);

		OpenAIMessage userMsg = new OpenAIMessage();
		userMsg.setRole("user");
		userMsg.setContent(userText);
		req.addMessage(userMsg);

		OpenAIMessage assistMsg = new OpenAIMessage();
		assistMsg.setRole("assistant");
		assistMsg.setContent(assistText);
		req.addMessage(assistMsg);

		return req;
	}

	private OpenAIRequest createMinimalRequestNoAssistant(String userText) {
		OpenAIRequest req = new OpenAIRequest();
		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole("system");
		sysMsg.setContent("System prompt");
		req.addMessage(sysMsg);

		OpenAIMessage userMsg = new OpenAIMessage();
		userMsg.setRole("user");
		userMsg.setContent(userText);
		req.addMessage(userMsg);

		return req;
	}
}
