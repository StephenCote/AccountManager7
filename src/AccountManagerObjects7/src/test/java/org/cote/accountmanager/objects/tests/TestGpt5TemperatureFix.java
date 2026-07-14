package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/// Regression coverage for the Azure "GPT 5.6 Terra" bug:
///   1. gpt-5 / o-series reasoning models reject a non-default `temperature`
///      (HTTP 400 "Unsupported value: 'temperature' does not support 0.9 ...").
///      Fix: ChatUtil.supportsSamplingParams() is false for those models and
///      Chat.chatInternal strips temperature/top_p/frequency_penalty from the
///      wire request.
///   2. When the LLM call fails (any non-200), the buffer-mode stream reader used
///      to feed the multi-line JSON error body line-by-line to processStreamChunk,
///      spamming one parse failure per line and surfacing a null message. Fix:
///      ChatUtil.extractLLMError() turns the provider error body into one clean
///      message that Chat surfaces via onerror/bufferError.
///
/// The live test (TestGpt5ChatSucceedsWithStrippedTemperature) drives the real
/// Chat pipeline against the Azure endpoint in resource.properties. It is the
/// end-to-end proof; the others are fast, offline unit checks of the two helpers.
public class TestGpt5TemperatureFix extends BaseTest {

	private static final String ORG_PATH = "/Development/Gpt5 Temperature Tests";

	/// The exact HTTP 400 body Azure returned in the reported bug (pretty-printed,
	/// so a naive per-line stream parse fails on every line).
	private static final String AZURE_TEMP_ERROR_BODY =
		"{\n" +
		"  \"error\": {\n" +
		"    \"message\": \"Unsupported value: 'temperature' does not support 0.9 with this model. Only the default (1) value is supported.\",\n" +
		"    \"type\": \"invalid_request_error\",\n" +
		"    \"param\": \"temperature\",\n" +
		"    \"code\": \"unsupported_value\"\n" +
		"  }\n" +
		"}";

	private BaseRecord getTestUser() {
		OrganizationContext testOrgContext = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(testOrgContext.getAdminUser(), "gpt5TempTestUser1", testOrgContext.getOrganizationId());
	}

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

	/// Fix 1 (unit): reasoning models don't support custom sampling params.
	@Test
	public void TestSupportsSamplingParams() {
		BaseRecord testUser = getTestUser();
		String cfgName = "GPT5 SamplingParams Test " + UUID.randomUUID() + ".chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OPENAI, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		cfg.setValue("model", "gpt-5.6-terra");
		assertFalse("gpt-5.6-terra must NOT support custom sampling params", ChatUtil.supportsSamplingParams(cfg));

		cfg.setValue("model", "o3-mini");
		assertFalse("o-series must NOT support custom sampling params", ChatUtil.supportsSamplingParams(cfg));

		cfg.setValue("model", "gpt-4o");
		assertTrue("gpt-4o must support custom sampling params", ChatUtil.supportsSamplingParams(cfg));
	}

	/// Fix 2 (unit): the multi-line provider error body becomes one clean message.
	@Test
	public void TestExtractLLMError() {
		String msg = ChatUtil.extractLLMError(AZURE_TEMP_ERROR_BODY, 400);
		assertNotNull("extractLLMError returned null", msg);
		logger.info("extractLLMError -> " + msg);
		assertTrue("Message should reference the HTTP status", msg.contains("400"));
		assertTrue("Message should surface the temperature error", msg.toLowerCase().contains("temperature"));
		assertTrue("Message should surface the provider code", msg.contains("unsupported_value"));

		/// Non-JSON / empty bodies still produce something actionable, never null.
		assertNotNull(ChatUtil.extractLLMError("<html>Bad Gateway</html>", 502));
		assertNotNull(ChatUtil.extractLLMError("", 500));
		assertNotNull(ChatUtil.extractLLMError(null, 500));
	}

	/// Fix 1 (wire shape): the pruned wire request for a gpt-5 model drops the
	/// sampling params, while a plain model keeps them. Mirrors the ignore-field
	/// computation in Chat.chatInternal.
	@Test
	public void TestSamplingParamsStrippedFromWire() throws Exception {
		OpenAIRequest req = new OpenAIRequest();
		req.set("temperature", 0.9);
		req.set("top_p", 0.5);
		req.set("frequency_penalty", 0.3);

		BaseRecord testUser = getTestUser();
		String cfgName = "GPT5 Wire Test " + UUID.randomUUID() + ".chat";
		BaseRecord gpt5 = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OPENAI, cfgName, testProperties);
		gpt5.setValue("model", "gpt-5.6-terra");

		java.util.List<String> ignore = new java.util.ArrayList<>();
		if(!ChatUtil.supportsSamplingParams(gpt5)) {
			ignore.add("temperature");
			ignore.add("top_p");
			ignore.add("frequency_penalty");
		}
		OpenAIRequest wire = ChatUtil.getPrunedRequest(req, ignore);
		assertFalse("temperature must be pruned for gpt-5", wire.hasField("temperature"));
		assertFalse("top_p must be pruned for gpt-5", wire.hasField("top_p"));
		assertFalse("frequency_penalty must be pruned for gpt-5", wire.hasField("frequency_penalty"));

		/// A plain model keeps them (empty ignore list).
		OpenAIRequest wire2 = ChatUtil.getPrunedRequest(req, new java.util.ArrayList<>());
		assertTrue("temperature must be kept for a non-reasoning model", wire2.hasField("temperature"));
	}

	/// Fix 1 (LIVE end-to-end): a real chat against the Azure gpt-5 deployment with
	/// a non-default temperature set. Before the fix this returned HTTP 400 and a
	/// null message; after the fix temperature is stripped and Azure responds 200.
	@Test
	public void TestGpt5ChatSucceedsWithStrippedTemperature() {
		BaseRecord testUser = getTestUser();
		String cfgName = "GPT5 Live Temperature Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OPENAI, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		/// Reproduce the exact reported scenario: gpt-5.6-terra with temperature 0.9.
		cfg.setValue("model", "gpt-5.6-terra");
		cfg.setValue("stream", false);
		BaseRecord opts = ensureChatOptions(cfg);
		opts.setValue("temperature", 0.9);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Gpt5 Temperature Test");
		java.util.List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a helpful test assistant. Respond briefly.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		Chat chat = new Chat(testUser, cfg, pcfg);
		OpenAIRequest req = chat.getChatPrompt();
		assertNotNull("OpenAI request is null", req);
		req.setStream(false);

		chat.newMessage(req, "Say exactly: PONG");
		OpenAIResponse resp = chat.chat(req);

		assertNotNull("Response is null — gpt-5 rejected the request (temperature not stripped?)", resp);
		assertNotNull("Response message is null", resp.getMessage());
		String content = resp.getMessage().getContent();
		assertNotNull("Response content is null", content);
		assertFalse("Response content is empty", content.trim().isEmpty());
		logger.info("gpt-5 live response: " + content.trim());
	}

	/// Fix 2 (LIVE end-to-end): when the LLM call genuinely fails (here: an invalid
	/// deployment name → Azure 404/400 with a JSON error body), buffer-mode chat()
	/// must fail CLEANLY. Before the fix it fed the multi-line error body line-by-line
	/// to processStreamChunk (a parse failure per line) and returned a present-but-empty
	/// OpenAIResponse (message=null). After the fix the whole error body is drained,
	/// turned into one message, and chat() returns null (buffer mode surfaces the error).
	@Test
	public void TestGpt5ChatFailsCleanlyOnError() {
		BaseRecord testUser = getTestUser();
		String cfgName = "GPT5 Live Error Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OPENAI, cfgName, testProperties);
		assertNotNull("Chat config is null", cfg);

		/// Invalid deployment — still gpt-5* so sampling params are stripped, isolating
		/// the failure to a genuine provider error (DeploymentNotFound).
		cfg.setValue("model", "gpt-5-nonexistent-deployment-" + UUID.randomUUID());
		cfg.setValue("stream", false);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Gpt5 Temperature Test");

		Chat chat = new Chat(testUser, cfg, pcfg);
		OpenAIRequest req = chat.getChatPrompt();
		assertNotNull("OpenAI request is null", req);
		req.setStream(false);

		chat.newMessage(req, "Say exactly: PONG");
		OpenAIResponse resp = chat.chat(req);

		/// Clean failure: null, not a present-but-empty response.
		assertNull("Buffer-mode chat() should return null on a provider error, not an empty response", resp);
	}

	/// gpt-5 streaming chunks carry `service_tier` and `obfuscation` fields that
	/// weren't in the openaiResponse schema, so RecordDeserializer logged an
	/// "Invalid field" ERROR for each field on every chunk (hundreds of lines per
	/// response). After adding them to the model they deserialize cleanly.
	@Test
	public void TestGpt5ResponseFieldsDeserialize() {
		String chunk = "{\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"index\":0,\"finish_reason\":null}],"
			+ "\"created\":1234567890,\"id\":\"chatcmpl-abc\",\"model\":\"gpt-5.6-terra\","
			+ "\"object\":\"chat.completion.chunk\",\"service_tier\":\"default\","
			+ "\"system_fingerprint\":\"fp_x\",\"obfuscation\":\"TqKimuL5lqGa0k6\"}";

		BaseRecord resp = RecordFactory.importRecord(
			org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_OPENAI_RESPONSE, chunk);
		assertNotNull("gpt-5 streaming chunk failed to deserialize", resp);
		assertTrue("service_tier should now be a known field", resp.hasField("service_tier"));
		assertTrue("obfuscation should now be a known field", resp.hasField("obfuscation"));
		assertEquals("service_tier value should deserialize", "default", resp.get("service_tier"));
		assertEquals("obfuscation value should deserialize", "TqKimuL5lqGa0k6", resp.get("obfuscation"));
	}
}
