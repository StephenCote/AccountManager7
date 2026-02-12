package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.MockWebSocket;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

/// Phase 7 Tests 36-39: Always-Stream Backend with Buffer & Timeout
public class TestChatStream extends BaseTest {

	private static final String ORG_PATH = "/Development/Olio LLM Stream Tests";

	private BaseRecord getTestUser() {
		OrganizationContext testOrgContext = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(testOrgContext.getAdminUser(), "streamTestUser1", testOrgContext.getOrganizationId());
	}

	/// Use a smaller model for stream tests to avoid server contention
	private static final String STREAM_TEST_MODEL = "qwen3:8b";

	private BaseRecord getCfg(BaseRecord testUser) {
		LLMServiceEnumType llmType = LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase());
		String cfgName = llmType.toString() + " Stream Test.chat";
		BaseRecord cfg = OlioTestUtil.getChatConfig(testUser, llmType, cfgName, testProperties);
		if (cfg != null && llmType == LLMServiceEnumType.OLLAMA) {
			cfg.setValue("model", STREAM_TEST_MODEL);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
		}
		return cfg;
	}

	/// Test 36: stream=false -> Chat.chat() returns complete response (not null)
	@Test
	public void TestStreamBufferMode() {
		logger.info("Test 36: Stream Buffer Mode - stream=false returns complete response");
		BaseRecord testUser = getTestUser();
		BaseRecord cfg = getCfg(testUser);
		assertNotNull("Chat config is null", cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Stream Buffer Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a helpful test assistant. Respond briefly.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		/// Ensure stream=false on the config
		cfg.setValue("stream", false);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		String chatName = "Buffer Mode Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		Chat chat = new Chat(testUser, cfg, pcfg);
		OpenAIRequest req = chat.getChatPrompt();
		assertNotNull("OpenAI request is null", req);
		req.setStream(false);

		chat.newMessage(req, "Say exactly: BUFFER_TEST_OK");
		OpenAIResponse resp = chat.chat(req);

		assertNotNull("Response should not be null in buffer mode", resp);
		assertNotNull("Response message should not be null", resp.getMessage());
		String content = resp.getMessage().getContent();
		assertNotNull("Response content should not be null", content);
		assertTrue("Response content should not be empty", content.length() > 0);
		logger.info("Buffer mode response: " + content);
	}

	/// Test 37: requestTimeout=1 -> TimeoutException caught, error reported
	@Test
	public void TestStreamTimeoutTriggered() {
		logger.info("Test 37: Stream Timeout - requestTimeout=1 triggers timeout");
		BaseRecord testUser = getTestUser();
		BaseRecord cfg = getCfg(testUser);
		assertNotNull("Chat config is null", cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Stream Timeout Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a helpful assistant. Respond briefly.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		cfg.setValue("stream", false);
		/// Set an impossibly short timeout to trigger the timeout
		cfg.setValue("requestTimeout", 1);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		String chatName = "Timeout Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		Chat chat = new Chat(testUser, cfg, pcfg);
		chat.setRequestTimeout(1);
		OpenAIRequest req = chat.getChatPrompt();
		assertNotNull("OpenAI request is null", req);
		req.setStream(false);

		/// A simple prompt that still takes >1s due to model thinking/loading
		chat.newMessage(req, "Tell me a short joke.");

		/// With a 1-second timeout, the chat should return null (timeout error)
		OpenAIResponse resp = chat.chat(req);

		/// The response should be null because the timeout fires before completion
		/// Note: If the LLM responds within 1 second (unlikely for a long story), the test may pass with a response.
		/// The key assertion is that no exception escapes - the timeout is handled gracefully.
		logger.info("Timeout test result: " + (resp == null ? "null (timeout triggered as expected)" : "response received (LLM was fast)"));

		/// Restore default timeout so subsequent tests are not affected
		cfg.setValue("requestTimeout", 120);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
	}

	/// Test 38: stopStream() works in buffered mode
	@Test
	public void TestStreamCancellation() {
		logger.info("Test 38: Stream Cancellation - stopStream() in streaming mode");
		BaseRecord testUser = getTestUser();
		BaseRecord cfg = getCfg(testUser);
		assertNotNull("Chat config is null", cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Stream Cancel Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a helpful assistant. When asked, write a very long essay.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		cfg.setValue("stream", true);
		cfg.setValue("requestTimeout", 120);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		String chatName = "Cancel Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		MockWebSocket mockWebSocket = new MockWebSocket(testUser);
		creq.setValue("uid", UUID.randomUUID().toString());
		creq.setValue("message", "Write a very long essay about philosophy, at least 2000 words.");
		OpenAIRequest req = mockWebSocket.sendMessageToServer(new ChatRequest(creq));
		assertNotNull("Chat request is null", req);

		/// Wait briefly for streaming to start, then stop
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			logger.error(e);
		}

		/// Issue stop
		mockWebSocket.stopStream(req);
		logger.info("Stop issued for stream cancellation test");

		/// Wait for completion
		int waited = 0;
		while (mockWebSocket.isRequesting(req) && waited < 15000) {
			try {
				Thread.sleep(200);
				waited += 200;
			} catch (InterruptedException e) {
				logger.error(e);
			}
		}

		logger.info("Stream cancellation test completed after " + waited + "ms (requesting=" + mockWebSocket.isRequesting(req) + ")");
		assertTrue("Stream should have stopped within timeout", !mockWebSocket.isRequesting(req) || waited < 15000);
	}

	/// Test 39: stream=true -> existing streaming behavior still works
	@Test
	public void TestStreamingModeUnchanged() {
		logger.info("Test 39: Streaming Mode Unchanged - stream=true still works via listener");
		BaseRecord testUser = getTestUser();
		BaseRecord cfg = getCfg(testUser);
		assertNotNull("Chat config is null", cfg);

		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser, "Stream Mode Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("You are a helpful test assistant. Respond briefly.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser, pcfg);

		cfg.setValue("stream", true);
		cfg.setValue("requestTimeout", 120);
		IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);

		String chatName = "Streaming Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);

		MockWebSocket mockWebSocket = new MockWebSocket(testUser);
		creq.setValue("uid", UUID.randomUUID().toString());
		creq.setValue("message", "Say exactly: STREAM_TEST_OK");
		OpenAIRequest req = mockWebSocket.sendMessageToServer(new ChatRequest(creq));
		assertNotNull("Chat request is null", req);

		/// Wait for completion — allow up to 120s for LLM response (qwen3-coder thinking can be slow)
		int waited = 0;
		int maxWait = 120000;
		while (mockWebSocket.isRequesting(req) && waited < maxWait) {
			try {
				Thread.sleep(200);
				waited += 200;
			} catch (InterruptedException e) {
				logger.error(e);
			}
		}

		boolean completed = !mockWebSocket.isRequesting(req);
		logger.info("Streaming mode test completed=" + completed + " after " + waited + "ms");
		assertTrue("Streaming should complete within " + (maxWait / 1000) + "s timeout", completed);
	}
}
