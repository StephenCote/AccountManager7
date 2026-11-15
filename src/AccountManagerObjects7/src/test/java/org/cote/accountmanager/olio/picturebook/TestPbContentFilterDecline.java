package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.LlmEmulator;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * UAT-5 (PictureBook V3 §24, 2026-11-15): a completion the provider's content filter withheld is a
 * DECLINE, not an infrastructure failure and not an empty answer.
 *
 * <p>What was measured on the fan-out set (LiteLLM → Azure, chat config {@code 000-litellm-azure}):
 * every chapter-2 passage came back {@code finish_reason: content_filter} with the content empty or a
 * {@code {"} fragment while usage billed the full generation (1204 completion tokens); a non-streamed
 * replay of the same request confirmed {@code content_filter} with every prompt filter {@code safe}.
 * {@code Chat} read {@code finish_reason} only to end the stream, so {@code callLlmInternal} saw "no
 * message" (→ "Null LLM response", HARD) and the chunk loop reported "the model did not finish within
 * the request timeout"; the fragment case parsed as an object with no keys and counted as a successful
 * reply with no scenes. The wizard then said "Passage 2 of 4 — the model did not respond in time".
 *
 * <p>Everything below runs the REAL {@code Chat} SSE parser and the REAL {@code callLlmInternal} /
 * chunk loop over the in-process {@link LlmEmulator}, whose {@code content_filter} fault mode emits
 * exactly the stream shape measured. Real DB (connection record), no model server.
 */
public class TestPbContentFilterDecline extends BaseTest {

	private static final String EXTRACT_CHUNK = "pictureBook.extract-chunk";
	private static final String MODEL = "qwen3:8b";
	private static final int REQUEST_TIMEOUT_SEC = 20;

	private static final String CHUNK = "Chapter 2: The Lantern Under The Stairs\n\n"
		+ "Darby Wren carried the lantern down the cellar steps while Old Marsh waited at the top, one hand on the rail. "
		+ "The flame guttered when the draft found it, and the shadows of the shelves leaned in across the packed-earth floor. "
		+ "Darby set the lantern on the barrel, crouched, and pulled the loose board aside; underneath lay a tin box wrapped in oilcloth.";

	private Path tmpRoot;

	@Before
	public void setUpEmulator() throws Exception {
		tmpRoot = Files.createTempDirectory("am7-uat5-content-filter-");
		LlmEmulator.configure(tmpRoot.toString(), null);
		LlmEmulator.resetCounters();
		Chat.clearLastCallError();
		PictureBookUtil.clearLastRefusal();
	}

	@After
	public void tearDownEmulator() throws Exception {
		LlmEmulator.configure(null, null);
		LlmEmulator.resetCounters();
		Chat.clearLastCallError();
		PictureBookUtil.clearLastRefusal();
		if (tmpRoot != null) {
			try (java.util.stream.Stream<Path> s = Files.walk(tmpRoot)) {
				s.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
					try { Files.deleteIfExists(q); } catch (Exception ignore) { /* best effort */ }
				});
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// helpers (the TestLlmEmulator shapes: a persisted EMULATOR connection, an in-memory chatConfig)
	// ------------------------------------------------------------------------------------------------

	private String newSet(String manifestJson) throws Exception {
		String set = "cf-" + UUID.randomUUID().toString().substring(0, 8);
		Path dir = tmpRoot.resolve(set);
		Files.createDirectories(dir);
		Files.write(dir.resolve(LlmEmulator.MANIFEST_FILE), manifestJson.getBytes(StandardCharsets.UTF_8));
		return set;
	}

	private static String template(String promptName, String field) {
		String t = PromptResourceUtil.getString(promptName, field);
		assertNotNull("classpath prompt resource " + promptName + "." + field + " is missing", t);
		return t;
	}

	private static Map<String, String> chunkVars(String chunk) {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("previousScenes", "[]");
		vars.put("knownCharacters", "(none yet)");
		vars.put("maxNew", "4");
		vars.put("chunk", chunk);
		return vars;
	}

	private BaseRecord persistConnection(BaseRecord user, String name, String serverUrl) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		assertNotNull("connection factory newInstance returned null", c);
		c.set("serverUrl", serverUrl);
		c.set("requestTimeout", REQUEST_TIMEOUT_SEC);
		c.set(FieldNames.FIELD_DIALECT, ConnectionDialectEnumType.EMULATOR);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, c);
		assertNotNull("AccessPoint.create returned null for system.connection '" + name + "'", created);
		long connId = created.get(FieldNames.FIELD_ID);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "serverUrl", "requestTimeout", "apiKey",
			FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM });
		cq.setCache(false);
		BaseRecord back = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
		assertNotNull("persisted connection id=" + connId + " could not be read back", back);
		return back;
	}

	private BaseRecord emulatorConfig(BaseRecord user, String set) throws Exception {
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord conn = persistConnection(user, "CF Conn " + nonce, LlmEmulator.SCHEME + set);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "CF Cfg " + nonce);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("model", MODEL);
		cfg.set("stream", false);
		cfg.set("connection", conn);
		return cfg;
	}

	/// The exact call shape callLlmInternal makes, one level down: Chat alone, buffer mode.
	private OpenAIResponse dispatch(BaseRecord user, BaseRecord cfg, String system, String userPrompt) {
		Chat chat = new Chat(user, cfg, null);
		assertEquals("Chat resolved the wrong wire dialect", LLMServiceEnumType.EMULATOR, chat.getServiceType());
		chat.setLlmSystemPrompt(system);
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		chat.newMessage(req, userPrompt);
		Chat.clearLastCallError();
		return chat.chat(req);
	}

	private static Map<String, Object> failure(String json) {
		Map<String, Object> f = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertNotNull("failure record must be JSON: " + json, f);
		return f;
	}

	/// Enough sentence-ended prose to split into several extract chunks (mirrors TestExtractChunkLoop.longText).
	private static String longText(int sentences) {
		int sentenceChars = 92 * (PictureBookUtil.EXTRACT_CHUNK_SIZE - PictureBookUtil.EXTRACT_CHUNK_OVERLAP) / 1800;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sentences; i++) {
			StringBuilder s = new StringBuilder("Sentence number ").append(i)
				.append(" carries enough words to make the passage realistically long for chunking");
			while (s.length() < sentenceChars - 2) s.append(", and then some more words to fill it out");
			s.setLength(sentenceChars - 2);
			sb.append(s).append(". ");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------------------------------------
	// 1. Chat records the stream's finish_reason per call, on the calling thread
	// ------------------------------------------------------------------------------------------------

	@Test
	public void TestChatRecordsContentFilterFinishReasonAndResetsItPerCall() throws Exception {
		BaseRecord user = getCreateUser("cfChatUser");
		String set = newSet("{\"faults\": [{\"kind\": \"extract-chunk\", \"occurrence\": 1, \"mode\": \"content_filter\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);
		String sys = template(EXTRACT_CHUNK, "system");
		String usr = template(EXTRACT_CHUNK, "user").replace("{previousScenes}", "[]").replace("{knownCharacters}", "(none yet)")
			.replace("{maxNew}", "4").replace("{chunk}", CHUNK) + "\n/no_think";

		OpenAIResponse resp = dispatch(user, cfg, sys, usr);
		/// The proof is only worth something if the stream was consumed on ANOTHER thread, as it is in
		/// Tomcat (an HttpClient worker). The first UAT-5 fix set the ThreadLocal inside the consumer;
		/// the emulator then completed its future inline, the consumer ran on this thread, and the test
		/// passed while the live path read a ThreadLocal nobody had set (ledger §6.C 313).
		String consumer = LlmEmulator.lastConsumerThread();
		assertNotNull("the emulated body was consumed", consumer);
		assertNotEquals("the stream must have been consumed off the calling thread, like production — consumer=" + consumer,
			Thread.currentThread().getName(), consumer);
		assertEquals("the finish_reason the server sent must be readable the moment chat() returns",
			"content_filter", Chat.getLastCallFinishReason());
		assertTrue(Chat.isLastCallContentFiltered());
		assertFalse("a withheld completion is not a connectivity failure", Chat.isLastCallUnreachable());
		assertFalse("...nor a stalled stream", Chat.isLastCallStalled());
		String got = (resp != null && resp.getMessage() != null) ? resp.getMessage().getContent() : null;
		assertTrue("no content is delivered when the filter withholds everything: [" + got + "]", got == null || got.isEmpty());
		assertEquals(1L, (long) LlmEmulator.stats().get("fault"));

		/// occurrence:1 fired; the next call is a normal synthesized reply and must NOT inherit the reason.
		OpenAIResponse again = dispatch(user, cfg, sys, usr);
		assertNotNull("the fault fires only on its configured occurrence", again);
		assertNotNull(again.getMessage());
		assertFalse("the second call's content is the synthesizer's", again.getMessage().getContent().isEmpty());
		assertEquals("a clean stop resets the per-call reason", "stop", Chat.getLastCallFinishReason());
		assertFalse(Chat.isLastCallContentFiltered());

		/// clearLastCallError() — what the chunk loop calls before every attempt — drops it too.
		dispatch(user, cfg, sys, usr);
		Chat.clearLastCallError();
		assertNull(Chat.getLastCallFinishReason());
	}

	// ------------------------------------------------------------------------------------------------
	// 2. callLlmInternal: a withheld completion is a SOFT decline with a reason, never "Null LLM response"
	// ------------------------------------------------------------------------------------------------

	@Test
	public void TestCallLlmTreatsWithheldCompletionAsSoftDecline() throws Exception {
		BaseRecord user = getCreateUser("cfChatUser");
		String set = newSet("{\"faults\": [{\"kind\": \"extract-chunk\", \"occurrence\": 1, \"mode\": \"content_filter\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);

		boolean[] hard = new boolean[1];
		PictureBookUtil.clearLastRefusal();
		String out = PictureBookUtil.callLlmForChapBook(user, cfg, EXTRACT_CHUNK, chunkVars(CHUNK), hard);

		assertNull("nothing usable came back", out);
		assertFalse("the provider answered and withheld it — that is NOT an infrastructure failure "
			+ "(before UAT-5 this was 'Null LLM response' → 'did not finish within the request timeout')", hard[0]);
		String refusal = PictureBookUtil.getLastRefusal();
		assertNotNull("the decline must be recorded for the chunk loop's refusal branch", refusal);
		assertTrue("the reason names the cause: " + refusal, refusal.contains("content filter"));
		assertTrue("...and the finish_reason: " + refusal, refusal.contains("content_filter"));
		assertTrue("...and that nothing arrived: " + refusal, refusal.contains("no content"));

		/// The next call on this thread is clean.
		PictureBookUtil.clearLastRefusal();
		String ok = PictureBookUtil.callLlmForChapBook(user, cfg, EXTRACT_CHUNK, chunkVars(CHUNK), hard);
		assertNotNull("occurrence 2 is a normal synthesized reply", ok);
		assertNull(PictureBookUtil.getLastRefusal());
		assertFalse(hard[0]);
	}

	/// The fragment case: Azure delivered {@code {"} and then declared the completion withheld. Before
	/// UAT-5 that fragment parsed as "an object with 0 top-level keys", i.e. a SUCCESSFUL reply with no
	/// scenes — the passage was silently dropped with no failure record at all.
	@Test
	public void TestCallLlmDiscardsTheFragmentAWithheldCompletionLeavesBehind() throws Exception {
		BaseRecord user = getCreateUser("cfChatUser");
		String set = newSet("{\"faults\": [{\"kind\": \"extract-chunk\", \"occurrence\": 1, \"mode\": \"content_filter\", \"content\": \"{\\\"\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);

		boolean[] hard = new boolean[1];
		PictureBookUtil.clearLastRefusal();
		String out = PictureBookUtil.callLlmForChapBook(user, cfg, EXTRACT_CHUNK, chunkVars(CHUNK), hard);

		assertNull("the fragment must never be returned as content", out);
		assertFalse(hard[0]);
		String refusal = PictureBookUtil.getLastRefusal();
		assertNotNull(refusal);
		assertTrue("the reason says how much arrived before the cut: " + refusal, refusal.contains("2 chars received before the cut"));
		assertEquals(1L, (long) LlmEmulator.stats().get("fault"));
	}

	// ------------------------------------------------------------------------------------------------
	// 3. The chunk loop through the REAL LLM path (defaultChunkLlm → callLlmInternal → Chat → emulator):
	//    the withheld passage is recorded ONCE as a refusal ("declined"), not retried with the same model,
	//    not a timeout, not a silent "no new scenes"; the run carries on and reaches the end.
	// ------------------------------------------------------------------------------------------------

	@Test
	public void TestChunkLoopRecordsAWithheldPassageAsDeclinedNotTimedOut() throws Exception {
		BaseRecord user = getCreateUser("cfLoopUser");
		String set = newSet("{\"faults\": [{\"kind\": \"extract-chunk\", \"occurrence\": 2, \"mode\": \"content_filter\", \"content\": \"{\\\"\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);

		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.extractChunkedInternal(user, cfg, longText(60), token, failed,
			null, reachedEnd, null /* production path: defaultChunkLlm */);

		assertTrue("the text must span at least 3 chunks, got " + token.getTotal(), token.getTotal() >= 3);
		assertTrue("one withheld passage does not stop the run", reachedEnd[0]);
		assertEquals("every chunk was attempted", token.getTotal(), token.getCurrent());
		assertTrue("the other passages produced scenes", scenes.size() > 0);
		assertEquals("exactly one failure — the withheld passage — and it was NOT silently dropped: " + failed, 1, failed.size());
		String f = failed.get(0);
		Map<String, Object> rec = failure(f);
		assertEquals("passage 2 is the one the filter withheld", 2, PictureBookUtil.failedChunkNumber(f));
		assertEquals("typed as a refusal so the client says 'declined': " + f, "refusal", rec.get("kind"));
		String err = String.valueOf(rec.get("error"));
		assertTrue("the user is told to retry with a different model: " + err, err.contains("different model"));
		assertFalse("it is NOT reported as a timeout: " + err, err.toLowerCase().contains("timeout") || err.toLowerCase().contains("did not respond"));
		String raw = String.valueOf(rec.get("rawResponse"));
		assertTrue("the recorded reason names the content filter: " + raw, raw.contains("content filter"));
		assertFalse("a decline is not a breaker stop", f.contains("stoppedEarly"));
		assertTrue("a declined passage is retryable with another model", PictureBookUtil.isRetryableFailure(f));
		assertEquals("the fault fired exactly once — the same model was not asked again", 1L, (long) LlmEmulator.stats().get("fault"));
	}
}
