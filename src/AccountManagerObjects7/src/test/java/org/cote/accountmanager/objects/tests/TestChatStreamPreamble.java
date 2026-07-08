package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

/// Focused regression test for the shared streaming chunk parser Chat.processStreamChunk.
///
/// Bug: Azure OpenAI streams a content-filter PREAMBLE as the FIRST SSE event with an
/// empty `choices` array (e.g. {"choices":[],"prompt_filter_results":[...]}). The old code
/// treated an empty `choices` + null top-level `message` as "stream completed" and returned
/// true, terminating every Azure/OpenAI stream after one line with zero content. This test
/// feeds a simulated Azure sequence (preamble -> deltas -> finish/[DONE]) and asserts the
/// preamble does NOT terminate the stream, the deltas accumulate, and termination fires only
/// at the real end. It also feeds an Ollama-style sequence as a regression guard that the
/// Ollama "empty message = done" behavior is unchanged.
///
/// processStreamChunk is private; it is exercised through reflection (the smallest testable
/// seam). Buffer mode (forwardToClient=false, no listener) is used so no network/DB/listener
/// dependencies are involved — only the parse/accumulate/terminate logic under test.
public class TestChatStreamPreamble extends BaseTest {

	private Method getProcessStreamChunk() throws NoSuchMethodException {
		Method m = Chat.class.getDeclaredMethod("processStreamChunk",
			String.class, OpenAIRequest.class, OpenAIResponse.class, boolean.class);
		m.setAccessible(true);
		return m;
	}

	private boolean invoke(Method m, Chat chat, String line, OpenAIRequest req, OpenAIResponse aresp) throws Exception {
		return (boolean) m.invoke(chat, line, req, aresp, false);
	}

	private String accumulatedContent(OpenAIResponse aresp) {
		BaseRecord msg = aresp.get("message");
		if (msg == null) {
			return null;
		}
		return msg.get("content");
	}

	/// Azure/OpenAI: empty-choices preamble must be skipped (NOT treated as completion),
	/// deltas must accumulate, and the stream must terminate only at finish_reason / [DONE].
	@Test
	public void TestAzurePreambleThenDeltasThenDone() throws Exception {
		Method m = getProcessStreamChunk();
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI);

		OpenAIRequest req = new OpenAIRequest();
		OpenAIResponse aresp = new OpenAIResponse();

		/// (a) Content-filter preamble — empty choices, prompt_filter_results present.
		/// This is exactly what Azure emits as the first SSE event.
		String preamble = "data: {\"choices\":[],\"prompt_filter_results\":{\"hate\":{\"filtered\":false,\"severity\":\"safe\"}}}";
		boolean donePreamble = invoke(m, chat, preamble, req, aresp);
		assertFalse("Preamble (empty choices) must NOT terminate the stream", donePreamble);
		assertNull("Preamble must not accumulate any content", accumulatedContent(aresp));

		/// (b) Content delta chunks.
		String delta1 = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"finish_reason\":null}]}";
		boolean doneDelta1 = invoke(m, chat, delta1, req, aresp);
		assertFalse("A content delta must NOT terminate the stream", doneDelta1);

		String delta2 = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\", world\"},\"finish_reason\":null}]}";
		boolean doneDelta2 = invoke(m, chat, delta2, req, aresp);
		assertFalse("A content delta must NOT terminate the stream", doneDelta2);

		assertEquals("Deltas must accumulate into the message content", "Hello, world", accumulatedContent(aresp));

		/// (c) Terminating chunk with finish_reason on the last choice.
		String finish = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
		boolean doneFinish = invoke(m, chat, finish, req, aresp);
		assertTrue("finish_reason on the last choice must terminate the stream", doneFinish);

		/// Content must be intact after the terminating chunk (the empty delta adds nothing).
		assertEquals("Accumulated content must be intact at termination", "Hello, world", accumulatedContent(aresp));
	}

	/// The `data: [DONE]` sentinel must also terminate the OpenAI stream (alternate real end).
	@Test
	public void TestAzureDoneSentinelTerminates() throws Exception {
		Method m = getProcessStreamChunk();
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OPENAI);

		OpenAIRequest req = new OpenAIRequest();
		OpenAIResponse aresp = new OpenAIResponse();

		String preamble = "data: {\"choices\":[]}";
		assertFalse("Preamble must not terminate", invoke(m, chat, preamble, req, aresp));

		String delta = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}";
		assertFalse("Delta must not terminate", invoke(m, chat, delta, req, aresp));
		assertEquals("Content accumulated", "Hi", accumulatedContent(aresp));

		String done = "data: [DONE]";
		assertTrue("[DONE] sentinel must terminate the stream", invoke(m, chat, done, req, aresp));
		assertEquals("Content intact at [DONE]", "Hi", accumulatedContent(aresp));
	}

	/// Regression guard: Ollama behavior (top-level message + done, and empty-message=done)
	/// must be UNCHANGED. Ollama lines are raw JSON (no `data: ` prefix).
	@Test
	public void TestOllamaSequenceUnchanged() throws Exception {
		Method m = getProcessStreamChunk();
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OLLAMA);

		OpenAIRequest req = new OpenAIRequest();
		OpenAIResponse aresp = new OpenAIResponse();

		String chunk1 = "{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"done\":false}";
		assertFalse("Ollama non-final chunk must not terminate", invoke(m, chat, chunk1, req, aresp));

		String chunk2 = "{\"message\":{\"role\":\"assistant\",\"content\":\" there\"},\"done\":false}";
		assertFalse("Ollama non-final chunk must not terminate", invoke(m, chat, chunk2, req, aresp));
		assertEquals("Ollama deltas accumulate", "Hi there", accumulatedContent(aresp));

		String finalChunk = "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}";
		assertTrue("Ollama done=true must terminate", invoke(m, chat, finalChunk, req, aresp));
		assertEquals("Ollama content intact at completion", "Hi there", accumulatedContent(aresp));
	}

	/// Regression guard: for OLLAMA, an empty-choices chunk with NO top-level message still
	/// means "completed" (the original behavior the OpenAI fix must NOT change for Ollama).
	@Test
	public void TestOllamaEmptyMessageStillCompletes() throws Exception {
		Method m = getProcessStreamChunk();
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OLLAMA);

		OpenAIRequest req = new OpenAIRequest();
		OpenAIResponse aresp = new OpenAIResponse();

		String chunk = "{\"done\":true}";
		assertTrue("Ollama empty-message chunk must still terminate the stream", invoke(m, chat, chunk, req, aresp));
	}

	/// Direct contrast of the bug: the SAME empty-choices preamble that Ollama treats as
	/// completion must be a skip (continue) for OpenAI. Guards the serviceType gate.
	@Test
	public void TestEmptyChoicesGatedByServiceType() throws Exception {
		Method m = getProcessStreamChunk();
		OpenAIRequest req = new OpenAIRequest();

		String emptyChoicesNoMessage = "{\"choices\":[]}";

		Chat openai = new Chat();
		openai.setServiceType(LLMServiceEnumType.OPENAI);
		OpenAIResponse arespOpenai = new OpenAIResponse();
		assertFalse("OpenAI empty choices = preamble skip (continue reading)",
			invoke(m, openai, "data: " + emptyChoicesNoMessage, req, arespOpenai));

		Chat ollama = new Chat();
		ollama.setServiceType(LLMServiceEnumType.OLLAMA);
		OpenAIResponse arespOllama = new OpenAIResponse();
		assertTrue("Ollama empty choices + no message = completed (unchanged)",
			invoke(m, ollama, emptyChoicesNoMessage, req, arespOllama));

		assertNotNull(req);
	}
}
