package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.Test;

/// Phase 8 Tests 40-45: LLM Config & ChatConfig Templates
public class TestChatOptions extends BaseTest {

	private static final String ORG_PATH = "/Development/Olio LLM Options Tests";

	private BaseRecord getTestUser() {
		OrganizationContext testOrgContext = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(testOrgContext.getAdminUser(), "optionsTestUser1", testOrgContext.getOrganizationId());
	}

	/// Populate chatOptions on a config read from DB, creating if absent
	private BaseRecord ensureChatOptions(BaseRecord cfg) {
		IOSystem.getActiveContext().getReader().populate(cfg, new String[] {"chatOptions"});
		BaseRecord opts = cfg.get("chatOptions");
		if(opts == null) {
			try {
				opts = RecordFactory.newInstance("olio.llm.chatOptions");
				cfg.set("chatOptions", opts);
			} catch(Exception e) {
				logger.error("Failed to create chatOptions: " + e.getMessage());
			}
		}
		return opts;
	}

	/// Test 40: top_k accepts values > 1 (50, 200)
	@Test
	public void TestTopKRangeFixed() {
		logger.info("Test 40: top_k range fix - accepts values > 1");
		BaseRecord testUser = getTestUser();
		LLMServiceEnumType llmType = LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase());
		String cfgName = llmType.toString() + " TopK Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, llmType, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		BaseRecord opts = ensureChatOptions(cfg);
		assertNotNull("chatOptions is null", opts);

		/// Verify top_k can be set to 50 (was blocked by maxValue=1)
		opts.setValue("top_k", 50);
		int val50 = opts.get("top_k");
		assertEquals("top_k should accept value 50", 50, val50);

		/// Verify top_k can be set to 200
		opts.setValue("top_k", 200);
		int val200 = opts.get("top_k");
		assertEquals("top_k should accept value 200", 200, val200);

		/// Verify top_k can be set to 500 (max)
		opts.setValue("top_k", 500);
		int val500 = opts.get("top_k");
		assertEquals("top_k should accept value 500", 500, val500);

		/// Verify the new fields exist on chatOptions
		opts.setValue("frequency_penalty", 0.5);
		double fp = opts.get("frequency_penalty");
		assertEquals("frequency_penalty should accept 0.5", 0.5, fp, 0.001);

		opts.setValue("presence_penalty", 0.3);
		double pp = opts.get("presence_penalty");
		assertEquals("presence_penalty should accept 0.3", 0.3, pp, 0.001);

		opts.setValue("max_tokens", 8192);
		int mt = opts.get("max_tokens");
		assertEquals("max_tokens should accept 8192", 8192, mt);

		opts.setValue("seed", 42);
		int seed = opts.get("seed");
		assertEquals("seed should accept 42", 42, seed);

		logger.info("Test 40 passed: top_k range and new fields verified");
	}

	/// Test 41: frequency_penalty and presence_penalty mapped correctly for OpenAI
	@Test
	public void TestApplyChatOptionsOpenAI() {
		logger.info("Test 41: applyChatOptions maps correctly for OpenAI");
		BaseRecord testUser = getTestUser();
		String cfgName = "OPENAI Options Mapping Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OPENAI, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		BaseRecord opts = ensureChatOptions(cfg);
		assertNotNull("chatOptions is null", opts);

		/// Set specific values on chatOptions
		opts.setValue("temperature", 0.7);
		opts.setValue("top_p", 0.85);
		opts.setValue("frequency_penalty", 0.4);
		opts.setValue("presence_penalty", 0.2);
		opts.setValue("max_tokens", 2048);
		opts.setValue("seed", 99);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		OpenAIRequest req = new OpenAIRequest();
		ChatUtil.applyChatOptions(req, cfg);

		/// Verify correct mapping: frequency_penalty comes from chatOptions.frequency_penalty (NOT repeat_penalty)
		double freqPen = req.get("frequency_penalty");
		assertEquals("frequency_penalty should be 0.4 (from chatOptions.frequency_penalty)", 0.4, freqPen, 0.001);

		/// Verify correct mapping: presence_penalty comes from chatOptions.presence_penalty (NOT typical_p)
		double presPen = req.get("presence_penalty");
		assertEquals("presence_penalty should be 0.2 (from chatOptions.presence_penalty)", 0.2, presPen, 0.001);

		/// Verify temperature and top_p pass through correctly
		double temp = req.get("temperature");
		assertEquals("temperature should be 0.7", 0.7, temp, 0.001);

		double topP = req.get("top_p");
		assertEquals("top_p should be 0.85", 0.85, topP, 0.001);

		/// Verify seed is set
		int seed = req.get("seed");
		assertEquals("seed should be 99", 99, seed);

		/// For OpenAI, max_tokens field should be used (not num_ctx)
		/// The exact field depends on getMaxTokenField() for the model
		String tokField = ChatUtil.getMaxTokenField(cfg);
		int tokVal = req.get(tokField);
		assertEquals("max_tokens should be 2048 for OpenAI", 2048, tokVal);

		logger.info("Test 41 passed: OpenAI mapping verified");
	}

	/// Test 42: repeat_penalty and typical_p no longer incorrectly mapped for Ollama
	@Test
	public void TestApplyChatOptionsOllama() {
		logger.info("Test 42: applyChatOptions maps correctly for Ollama");
		BaseRecord testUser = getTestUser();
		String cfgName = "OLLAMA Options Mapping Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		BaseRecord opts = ensureChatOptions(cfg);
		assertNotNull("chatOptions is null", opts);

		/// Set Ollama-native fields that WERE previously mis-mapped
		opts.setValue("repeat_penalty", 1.5);
		opts.setValue("typical_p", 0.9);
		/// Set OpenAI-compatible fields
		opts.setValue("frequency_penalty", 0.0);
		opts.setValue("presence_penalty", 0.0);
		opts.setValue("temperature", 0.8);
		opts.setValue("num_ctx", 16384);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		OpenAIRequest req = new OpenAIRequest();
		ChatUtil.applyChatOptions(req, cfg);

		/// The key fix: frequency_penalty should now be 0.0 (from chatOptions.frequency_penalty)
		/// NOT 1.5 (which was the old bug mapping repeat_penalty -> frequency_penalty)
		double freqPen = req.get("frequency_penalty");
		assertEquals("frequency_penalty should be 0.0 (NOT the old repeat_penalty value of 1.5)", 0.0, freqPen, 0.001);

		/// presence_penalty should now be 0.0 (from chatOptions.presence_penalty)
		/// NOT 0.9 (which was the old bug mapping typical_p -> presence_penalty)
		double presPen = req.get("presence_penalty");
		assertEquals("presence_penalty should be 0.0 (NOT the old typical_p value of 0.9)", 0.0, presPen, 0.001);

		/// For Ollama, num_ctx should be used as the token field
		String tokField = ChatUtil.getMaxTokenField(cfg);
		assertEquals("Ollama should use num_ctx field", "num_ctx", tokField);
		int numCtx = req.get("num_ctx");
		assertEquals("num_ctx should be 16384", 16384, numCtx);

		logger.info("Test 42 passed: Ollama mapping verified - no cross-contamination from repeat_penalty/typical_p");
	}

	/// Test 43: All 6 chatConfig templates deserialize correctly
	@Test
	public void TestChatConfigTemplateLoads() {
		logger.info("Test 43: chatConfig templates load and deserialize");
		String[] templateNames = ChatUtil.getChatConfigTemplateNames();
		assertNotNull("Template names should not be null", templateNames);
		assertEquals("Should have 6 templates", 6, templateNames.length);

		for(String name : templateNames) {
			BaseRecord template = ChatUtil.loadChatConfigTemplate(name);
			assertNotNull("Template '" + name + "' should load", template);

			/// Verify essential chatConfig fields exist
			assertNotNull("Template '" + name + "' should have rating", template.get("rating"));
			assertNotNull("Template '" + name + "' should have chatOptions", template.get("chatOptions"));

			BaseRecord opts = template.get("chatOptions");
			assertNotNull("Template '" + name + "' chatOptions should not be null", opts);

			/// Verify chatOptions has temperature
			double temp = opts.get("temperature");
			assertTrue("Template '" + name + "' temperature should be >= 0", temp >= 0.0);
			assertTrue("Template '" + name + "' temperature should be <= 2.0", temp <= 2.0);

			logger.info("Template '" + name + "' loaded: rating=" + template.get("rating") + ", temperature=" + temp);
		}

		logger.info("Test 43 passed: all 6 templates load correctly");
	}

	/// Test 44: Each template's chatOptions fields are within valid ranges
	@Test
	public void TestChatConfigTemplateDefaults() {
		logger.info("Test 44: chatConfig template field ranges");
		String[] templateNames = ChatUtil.getChatConfigTemplateNames();

		for(String name : templateNames) {
			BaseRecord template = ChatUtil.loadChatConfigTemplate(name);
			assertNotNull("Template '" + name + "' should load", template);

			BaseRecord opts = template.get("chatOptions");
			assertNotNull("Template '" + name + "' chatOptions should not be null", opts);

			/// Validate temperature: 0.0 - 2.0
			double temp = opts.get("temperature");
			assertTrue(name + ": temperature " + temp + " out of range", temp >= 0.0 && temp <= 2.0);

			/// Validate top_p: 0.0 - 1.0
			double topP = opts.get("top_p");
			assertTrue(name + ": top_p " + topP + " out of range", topP >= 0.0 && topP <= 1.0);

			/// Validate frequency_penalty: -2.0 - 2.0
			double fp = opts.get("frequency_penalty");
			assertTrue(name + ": frequency_penalty " + fp + " out of range", fp >= -2.0 && fp <= 2.0);

			/// Validate presence_penalty: -2.0 - 2.0
			double pp = opts.get("presence_penalty");
			assertTrue(name + ": presence_penalty " + pp + " out of range", pp >= -2.0 && pp <= 2.0);

			/// Validate max_tokens: > 0
			int mt = opts.get("max_tokens");
			assertTrue(name + ": max_tokens " + mt + " should be positive", mt > 0);

			/// Validate num_ctx: > 0
			int nc = opts.get("num_ctx");
			assertTrue(name + ": num_ctx " + nc + " should be positive", nc > 0);

			/// Validate top_k: 0 - 500
			int tk = opts.get("top_k");
			assertTrue(name + ": top_k " + tk + " out of range", tk >= 0 && tk <= 500);

			/// Validate seed: >= 0
			int seed = opts.get("seed");
			assertTrue(name + ": seed " + seed + " should be non-negative", seed >= 0);

			/// Validate chatConfig-level fields
			int msgTrim = template.get("messageTrim");
			assertTrue(name + ": messageTrim should be positive", msgTrim > 0);

			int reqTimeout = template.get("requestTimeout");
			assertTrue(name + ": requestTimeout should be positive", reqTimeout > 0);

			logger.info("Template '" + name + "' ranges validated");
		}

		logger.info("Test 44 passed: all template field ranges valid");
	}

	/// Test 45: Use corrected chatOptions with real LLM -> successful response
	@Test
	public void TestChatWithFixedOptions() {
		logger.info("Test 45: Chat with fixed chatOptions -> successful LLM response");
		BaseRecord testUser = getTestUser();
		LLMServiceEnumType llmType = LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase());
		String cfgName = llmType.toString() + " Fixed Options Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, llmType, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Fixed Options Test");
		java.util.List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a helpful test assistant. Respond briefly.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		/// Populate chatOptions and apply the coding template (low temperature for deterministic output)
		ensureChatOptions(cfg);
		ChatUtil.applyChatConfigTemplate(cfg, "coding");
		cfg.setValue("stream", false);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		/// Verify the template was applied
		BaseRecord opts = cfg.get("chatOptions");
		assertNotNull("chatOptions should not be null after template apply", opts);
		double temp = opts.get("temperature");
		assertEquals("Temperature should be 0.3 from coding template", 0.3, temp, 0.001);

		String chatName = "Fixed Options Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		Chat chat = new Chat(testUser, cfg, pcfg);
		OpenAIRequest req = chat.getChatPrompt();
		assertNotNull("OpenAI request is null", req);
		req.setStream(false);

		chat.newMessage(req, "Say exactly: OPTIONS_TEST_OK");
		OpenAIResponse resp = chat.chat(req);

		assertNotNull("Response should not be null", resp);
		assertNotNull("Response message should not be null", resp.getMessage());
		String content = resp.getMessage().getContent();
		assertNotNull("Response content should not be null", content);
		assertTrue("Response content should not be empty", content.length() > 0);
		logger.info("Fixed options response: " + content);

		logger.info("Test 45 passed: LLM responded with corrected chatOptions");
	}

	/// Verify openaiRequestModel.json defaults are corrected
	@Test
	public void TestRequestModelDefaults() {
		logger.info("Test: openaiRequestModel.json defaults verification");
		OpenAIRequest req = new OpenAIRequest();

		double fp = req.get("frequency_penalty");
		assertEquals("Default frequency_penalty should be 0.0 (was 1.3)", 0.0, fp, 0.001);

		double pp = req.get("presence_penalty");
		assertEquals("Default presence_penalty should be 0.0 (was 1.3)", 0.0, pp, 0.001);

		logger.info("Request model defaults verified: frequency_penalty=0.0, presence_penalty=0.0");
	}
}
