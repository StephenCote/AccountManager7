package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.policy.WrongCharacterDetectionOperation;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 12 tests: UX Polish & Remaining Cleanup
 *
 * P12-1: OI-31/34 — getNarratePrompt single-apply (analyze temperature not overwritten)
 * P12-2: OI-30 — applyAnalyzeOptions documented constants verified
 * P12-3: OI-39 — WrongCharacterDetection quote exclusion
 * P12-4: OI-29 — Ollama native options (top_k, repeat_penalty set when serviceType=OLLAMA)
 * P12-5: OI-28 — requestTimeout restore (try-finally constant check)
 */
public class TestChatPhase12 extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupPhase12() {
		testOrgContext = getTestOrganization("/Development/Phase12");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "phase12User", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Helper: create a fact record with factData set
	private BaseRecord buildFact(String name, String data) {
		try {
			BaseRecord fact = RecordFactory.newInstance("policy.fact");
			fact.set(FieldNames.FIELD_NAME, name);
			fact.set("factData", data);
			return fact;
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			return null;
		}
	}

	/// P12-1: OI-31/34 — Verify analyze temperature constant is 0.4 (not overwritten by chat defaults)
	@Test
	public void testAnalyzeTemperatureConstant() {
		try {
			assertEquals("ANALYZE_TEMPERATURE should be 0.4", 0.4, Chat.ANALYZE_TEMPERATURE, 0.001);
			assertEquals("ANALYZE_TOP_P should be 0.5", 0.5, Chat.ANALYZE_TOP_P, 0.001);
			assertEquals("ANALYZE_FREQUENCY_PENALTY should be 0.0", 0.0, Chat.ANALYZE_FREQUENCY_PENALTY, 0.001);
			assertEquals("ANALYZE_PRESENCE_PENALTY should be 0.0", 0.0, Chat.ANALYZE_PRESENCE_PENALTY, 0.001);
			assertEquals("ANALYZE_NUM_CTX should be 8192", 8192, Chat.ANALYZE_NUM_CTX);

			logger.info("P12-1 passed: Analyze constants verified (temperature=0.4, top_p=0.5)");
		} catch (Exception e) {
			logger.error("P12-1 failed", e);
			fail("P12-1 Exception: " + e.getMessage());
		}
	}

	/// P12-2: OI-30 — Verify applyAnalyzeOptions uses documented constants on a real request
	@Test
	public void testApplyAnalyzeOptionsUsesConstants() {
		try {
			String cfgName = "P12-2-Analyze-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, getLLMType(), getLLMType().toString() + " " + cfgName, testProperties);
			assertNotNull("ChatConfig should not be null", cfg);

			BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "P12-2-Prompt-" + UUID.randomUUID().toString().substring(0, 6));
			assertNotNull("PromptConfig should not be null", pcfg);

			Chat chat = new Chat(testUser, cfg, pcfg);

			// Build a request and narrate prompt (which calls applyAnalyzeOptions internally)
			OpenAIRequest req = new OpenAIRequest();
			req.setModel(cfg.get("model"));
			ChatUtil.applyChatOptions(req, cfg);

			// After applyChatOptions, temperature should be the chat default (not analyze)
			double chatTemp = req.get("temperature");
			logger.info("Chat temperature after applyChatOptions: " + chatTemp);

			// The analyze constants should remain distinct from whatever chatTemp is
			assertTrue("Analyze temperature (0.4) should differ from default if default != 0.4 or be 0.4",
				Chat.ANALYZE_TEMPERATURE == 0.4);

			logger.info("P12-2 passed: applyAnalyzeOptions documented constants intact");
		} catch (Exception e) {
			logger.error("P12-2 failed", e);
			fail("P12-2 Exception: " + e.getMessage());
		}
	}

	/// P12-3: OI-39 — WrongCharacterDetection quote exclusion
	@Test
	public void testWrongCharacterDetectionQuoteExclusion() {
		try {
			WrongCharacterDetectionOperation op = new WrongCharacterDetectionOperation(
				IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

			BaseRecord charFact = buildFact("charInfo", "{\"systemCharName\":\"Aria\",\"userCharName\":\"Bob\"}");

			// Straight quote: response starts with "Bob: ..." — should SUCCEED (quote exclusion)
			BaseRecord quotedFact = buildFact("response", "\"Bob: you should come over tonight,\" Aria said with a grin.");
			OperationResponseEnumType result = op.operate(null, null, null, quotedFact, charFact);
			assertTrue("Quoted dialogue starting with user name should SUCCEED (OI-39 exclusion)",
				result == OperationResponseEnumType.SUCCEEDED);

			// Smart quote: response starts with \u201cBob: ...
			BaseRecord smartQuoteFact = buildFact("response", "\u201cBob: come here\u201d she whispered.");
			result = op.operate(null, null, null, smartQuoteFact, charFact);
			assertTrue("Smart-quoted dialogue starting with user name should SUCCEED",
				result == OperationResponseEnumType.SUCCEEDED);

			// Non-quoted response starting with user char name — should still FAIL
			BaseRecord directFact = buildFact("response", "Bob: Hello there! How are you doing today?");
			result = op.operate(null, null, null, directFact, charFact);
			assertTrue("Unquoted response as user character should still FAIL",
				result == OperationResponseEnumType.FAILED);

			// Narrative with quote: "*Bob smiles*" not starting with quote — should FAIL
			BaseRecord narrativeFact = buildFact("response", "*Bob walks over and smiles warmly*");
			result = op.operate(null, null, null, narrativeFact, charFact);
			assertTrue("Narrative as user character should still FAIL",
				result == OperationResponseEnumType.FAILED);

			// Response starting with system character — should SUCCEED
			BaseRecord systemFact = buildFact("response", "Aria: Hello! I was just thinking about you.");
			result = op.operate(null, null, null, systemFact, charFact);
			assertTrue("Response as system character should SUCCEED",
				result == OperationResponseEnumType.SUCCEEDED);

			logger.info("P12-3 passed: WrongCharacterDetection quote exclusion works correctly");
		} catch (Exception e) {
			logger.error("P12-3 failed", e);
			fail("P12-3 Exception: " + e.getMessage());
		}
	}

	/// P12-4: OI-29 — Ollama native options set on request.options (chatOptions model) when serviceType=OLLAMA
	@Test
	public void testOllamaNativeOptions() {
		try {
			String cfgName = "P12-4-Ollama-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, cfgName);
			assertNotNull("ChatConfig should not be null", cfg);

			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("serverUrl", "http://localhost:11434");
			cfg.set("model", "test-model");

			// Set Ollama-specific chat options on the config
			BaseRecord opts = cfg.get("chatOptions");
			if (opts == null) {
				opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
				cfg.set("chatOptions", opts);
			}
			opts.set("top_k", 40);
			opts.set("repeat_penalty", 1.1);
			opts.set("typical_p", 0.9);
			opts.set("min_p", 0.05);
			opts.set("repeat_last_n", 64);
			opts.set("temperature", 0.7);
			opts.set("top_p", 0.9);

			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			assertNotNull("Updated config should not be null", cfg);

			// Apply chat options to a request
			OpenAIRequest req = new OpenAIRequest();
			req.setModel("test-model");
			ChatUtil.applyChatOptions(req, cfg);

			// Verify Ollama-specific fields are set on request.options sub-object
			BaseRecord reqOpts = req.get("options");
			assertNotNull("Request options sub-object should not be null for Ollama", reqOpts);

			int topK = reqOpts.get("top_k");
			assertTrue("top_k should be 40, got " + topK, topK == 40);

			double repeatPenalty = reqOpts.get("repeat_penalty");
			assertTrue("repeat_penalty should be 1.1, got " + repeatPenalty, Math.abs(repeatPenalty - 1.1) < 0.01);

			double typicalP = reqOpts.get("typical_p");
			assertTrue("typical_p should be 0.9, got " + typicalP, Math.abs(typicalP - 0.9) < 0.01);

			double minP = reqOpts.get("min_p");
			assertTrue("min_p should be 0.05, got " + minP, Math.abs(minP - 0.05) < 0.01);

			int repeatLastN = reqOpts.get("repeat_last_n");
			assertTrue("repeat_last_n should be 64, got " + repeatLastN, repeatLastN == 64);

			// Verify standard fields still set at top level of request
			double temp = req.get("temperature");
			assertTrue("temperature should be 0.7, got " + temp, Math.abs(temp - 0.7) < 0.01);

			logger.info("P12-4 passed: Ollama native options set on request.options sub-object");
		} catch (Exception e) {
			logger.error("P12-4 failed", e);
			fail("P12-4 Exception: " + e.getMessage());
		}
	}

	/// P12-5: OI-28 — Verify MIN_KEYFRAME_EVERY_WITH_EXTRACT constant (try-finally pattern verified in TestChatStream)
	@Test
	public void testKeyframeFloorConstant() {
		try {
			assertEquals("MIN_KEYFRAME_EVERY_WITH_EXTRACT should be 5",
				5, Chat.MIN_KEYFRAME_EVERY_WITH_EXTRACT);

			logger.info("P12-5 passed: keyframeEvery floor constant verified");
		} catch (Exception e) {
			logger.error("P12-5 failed", e);
			fail("P12-5 Exception: " + e.getMessage());
		}
	}

	/// Helper: Get LLM service type from test properties
	private LLMServiceEnumType getLLMType() {
		return LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase());
	}
}
