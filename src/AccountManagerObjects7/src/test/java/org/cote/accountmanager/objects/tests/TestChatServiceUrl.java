package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;

import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.junit.Test;

/**
 * Phase B1: verifies {@link Chat#getServiceUrl(OpenAIRequest)} URL assembly for the three
 * wire dialects, and the SSE stream-parser gate {@code isOpenAiCompatible()} that lets
 * OPENAI_COMPAT reuse the Azure/OpenAI choices/delta parser.
 *
 * <p>These are pure in-process checks against real production code — {@code getServiceUrl}
 * reads instance fields and {@code processStreamChunk} parses real SSE frames through
 * {@code RecordFactory.importRecord(MODEL_OPENAI_RESPONSE, ...)} (model registered by
 * {@code OlioModelNames.use()} in {@link BaseTest#setup()}). No live LLM is contacted.
 * {@code processStreamChunk} is private, so it is driven via reflection.</p>
 */
public class TestChatServiceUrl extends BaseTest {

	private static final String SERVER = "https://ai-host.example.com";

	@Test
	public void testOpenAiCompatUrl() {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI_COMPAT);
		chat.setServerUrl(SERVER);
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-4o");
		/// OPENAI_COMPAT (e.g. LiteLLM) uses the plain /v1/chat/completions route,
		/// model is carried in the body, not the URL.
		assertEquals(SERVER + "/v1/chat/completions", chat.getServiceUrl(req));
	}

	@Test
	public void testOpenAiAzureUrlUnchanged() {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI);
		chat.setServerUrl(SERVER);
		chat.setApiVersion("2025-04-01-preview");
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-5.6-terra");
		/// Existing Azure deployment scheme must be UNCHANGED by the B1 additive change.
		assertEquals(SERVER + "/openai/deployments/gpt-5.6-terra/chat/completions?api-version=2025-04-01-preview",
				chat.getServiceUrl(req));
	}

	@Test
	public void testOpenAiAzureUrlNoApiVersion() {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI);
		chat.setServerUrl(SERVER);
		/// apiVersion null (default) -> no query string appended.
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-5.6-terra");
		assertEquals(SERVER + "/openai/deployments/gpt-5.6-terra/chat/completions",
				chat.getServiceUrl(req));
	}

	@Test
	public void testOllamaUrl() {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OLLAMA);
		chat.setServerUrl(SERVER);
		chat.setChatMode(true);
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("qwen3:8b");
		/// OLLAMA chatMode=true -> /api/chat (chatMode=false would yield /api/generate).
		assertEquals(SERVER + "/api/chat", chat.getServiceUrl(req));
	}

	@Test
	public void testOllamaGenerateUrl() {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OLLAMA);
		chat.setServerUrl(SERVER);
		chat.setChatMode(false);
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("qwen3:8b");
		assertEquals(SERVER + "/api/generate", chat.getServiceUrl(req));
	}

	/// ---- SSE gate: exercises Chat.java:4154 (strip "data: ") and :4192 (skip empty-choices
	/// preamble) which are both gated by the private isOpenAiCompatible() for OPENAI_COMPAT. ----

	private boolean invokeProcessStreamChunk(Chat chat, String line, OpenAIRequest req, OpenAIResponse aresp) throws Exception {
		Method m = Chat.class.getDeclaredMethod("processStreamChunk", String.class, OpenAIRequest.class, OpenAIResponse.class, boolean.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(chat, line, req, aresp, false);
	}

	@Test
	public void testOpenAiCompatSseDeltaParsed() throws Exception {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI_COMPAT);
		chat.setServerUrl(SERVER);
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-4o");
		OpenAIResponse aresp = new OpenAIResponse();

		/// A real OPENAI_COMPAT SSE content frame: "data: " prefix + choices/delta shape.
		boolean done = invokeProcessStreamChunk(chat,
				"data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", req, aresp);
		/// Not end-of-stream (no [DONE], no finish_reason).
		assertFalse("content delta chunk must not signal stream complete", done);
		/// The delta content must have been accumulated onto the response message.
		assertNotNull("message should be populated after content delta", aresp.getMessage());
		assertEquals("hi", aresp.getMessage().get("content"));
	}

	@Test
	public void testOpenAiCompatSsePreambleSkipped() throws Exception {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI_COMPAT);
		chat.setServerUrl(SERVER);
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-4o");
		OpenAIResponse aresp = new OpenAIResponse();

		/// The empty-choices preamble (content-filter results, no delta) must be SKIPPED,
		/// not treated as end-of-stream. Before the B1/gate fix this returned true and
		/// terminated every OpenAI/compat stream after one empty line.
		boolean done = invokeProcessStreamChunk(chat,
				"data: {\"choices\":[]}", req, aresp);
		assertFalse("empty-choices preamble must be skipped (not end-of-stream) for OPENAI_COMPAT", done);
	}

	@Test
	public void testOpenAiCompatSseDoneSentinel() throws Exception {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI_COMPAT);
		chat.setServerUrl(SERVER);
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-4o");
		OpenAIResponse aresp = new OpenAIResponse();

		/// After stripping "data: ", the [DONE] sentinel signals completion.
		boolean done = invokeProcessStreamChunk(chat, "data: [DONE]", req, aresp);
		org.junit.Assert.assertTrue("[DONE] sentinel must signal stream complete", done);
	}
}
