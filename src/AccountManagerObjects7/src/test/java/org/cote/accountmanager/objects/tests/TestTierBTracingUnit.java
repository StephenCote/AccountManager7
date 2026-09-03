package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/// DETERMINISTIC (no live LLM/network-to-Azure) unit coverage for the Tier B (LiteLLM/Langfuse)
/// tracing changes. Every assertion here mirrors the SHIPPED behavior of Chat.chatInternal, and it
/// drives the REAL production code (ChatUtil.getPrunedRequest, the production serializer config, and
/// Chat.buildTracingHeaders) rather than a hand-rolled stand-in.
///
/// The shipped leakage gate (Chat.java:3889, 3934-3937) is:
///   - `session_id` and `metadata` are added to the prune list UNCONDITIONALLY, for EVERY dialect,
///     so they are ALWAYS absent from the wire body. (They are neither valid OpenAI/Azure chat
///     parameters — Azure returns HTTP 400 "Unknown parameter: 'session_id'" — nor forwarded by
///     LiteLLM as body fields; the working correlation path is the x-langfuse-* HEADERS.)
///   - `user` IS a standard OpenAI chat parameter, so it is KEPT in the body ONLY for the
///     OPENAI_COMPAT dialect and PRUNED for every other dialect (OPENAI/OLLAMA/UNKNOWN).
/// Separately, buildTracingHeaders (Chat.java:4356) emits the x-langfuse-session-id / x-langfuse-user-id
/// HEADERS ONLY for OPENAI_COMPAT, reading the UN-pruned req.
///
/// Coverage:
///   0. CONTROL — with only the base ChatUtil.IGNORE_FIELDS (i.e. WITHOUT the tracing prune),
///      all three fields serialize onto the wire. This proves the negative assertions below are not
///      vacuous: the fields DO serialize by default, so their absence is caused by the tracing gate.
///   1. OPENAI_COMPAT — wire body OMITS session_id + metadata but KEEPS user; buildTracingHeaders
///      emits both x-langfuse-* headers.
///   2. OPENAI / OLLAMA — wire body OMITS session_id + metadata AND user; buildTracingHeaders emits
///      no headers (returns null).
///   3. Header overload — ClientUtil.postToRecordAndStream 3-arg injects NO extra headers, while the
///      4-arg overload applies the fixed headers AND the extra (x-langfuse-*) headers. Verified by a
///      REAL request captured by a local com.sun.net.httpserver echo server (no typeof/reflection).
///
/// Extends BaseTest only so the Olio model schemas are registered (OlioModelNames.use()); no live LLM
/// call is made and no records are written.
public class TestTierBTracingUnit extends BaseTest {

	private static final String U_VAL = "trace-user-42";
	private static final String S_VAL = "trace-sess-42";
	private static final String M_VAL = "trace-meta-42";

	private OpenAIRequest baseReq() {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-5.6-terra");
		req.setStream(true);
		OpenAIMessage m = new OpenAIMessage();
		m.setRole("user");
		m.setContent("hello");
		req.addMessage(m);
		req.setValue("user", U_VAL);
		req.setValue("session_id", S_VAL);
		req.setValue("metadata", M_VAL);
		return req;
	}

	/// Assembles the wire-body prune ignore list EXACTLY as Chat.chatInternal does for the Tier B
	/// tracing gate: Chat.java:3889 seeds from ChatUtil.IGNORE_FIELDS, :3934 adds session_id + metadata
	/// for EVERY dialect, and :3935-3937 adds `user` only when the dialect is NOT OPENAI_COMPAT. The
	/// token/penalty/sampling/`think` additions that sit between those lines (3890-3916) add unrelated
	/// field names and require a chatConfig; none of them touch user/session_id/metadata, so they are
	/// omitted here. The pruned request is then produced by the REAL ChatUtil.getPrunedRequest.
	private List<String> tracingIgnoreFields(LLMServiceEnumType dialect) {
		List<String> ignoreFields = new ArrayList<>(ChatUtil.IGNORE_FIELDS);     // Chat.java:3889
		ignoreFields.addAll(Arrays.asList("session_id", "metadata"));           // Chat.java:3934 (unconditional)
		if (dialect != LLMServiceEnumType.OPENAI_COMPAT) {                      // Chat.java:3935-3937
			ignoreFields.add("user");
		}
		return ignoreFields;
	}

	/// EXACT production wire serialization — Chat.chatInternal:3953.
	private String wireBody(OpenAIRequest pruned) {
		return JSONUtil.exportObject(pruned, RecordSerializerConfig.getHiddenForeignUnfilteredModule());
	}

	/// Invokes the REAL Chat.buildTracingHeaders(req) (Chat.java:4356, private) via reflection, on a
	/// Chat whose dialect is set through the public setServiceType. This exercises the shipped header
	/// gate itself, not a reconstruction of it. buildTracingHeaders reads only serviceType + req, so a
	/// bare new Chat() (no chatConfig / no live context) is sufficient.
	@SuppressWarnings("unchecked")
	private Map<String, String> callBuildTracingHeaders(LLMServiceEnumType dialect, OpenAIRequest req) throws Exception {
		Chat chat = new Chat();
		chat.setServiceType(dialect);
		Method m = Chat.class.getDeclaredMethod("buildTracingHeaders", OpenAIRequest.class);
		m.setAccessible(true);
		return (Map<String, String>) m.invoke(chat, req);
	}

	/// (0) CONTROL: with ONLY the base ChatUtil.IGNORE_FIELDS (no tracing prune), the three tracing
	/// fields DO serialize onto the wire body. Without this control the "absent" assertions below
	/// could pass vacuously (a field that never serialized would also appear absent). This is the
	/// corrected reframing of the previously-misleading test, which wrongly claimed this base-list
	/// behavior was the OPENAI_COMPAT wire behavior.
	@Test
	public void control_baseIgnoreList_serializesAllThreeTracingFields() {
		OpenAIRequest req = baseReq();
		OpenAIRequest pruned = ChatUtil.getPrunedRequest(req, new ArrayList<>(ChatUtil.IGNORE_FIELDS));
		String ser = wireBody(pruned);
		logger.info("[TierB-unit][CONTROL] base-ignore wire body = " + ser);

		assertNotNull("serialization returned null", ser);
		assertTrue("CONTROL: `session_id` key must serialize when not pruned", ser.contains("\"session_id\""));
		assertTrue("CONTROL: `session_id` value must serialize when not pruned", ser.contains(S_VAL));
		assertTrue("CONTROL: `metadata` key must serialize when not pruned", ser.contains("\"metadata\""));
		assertTrue("CONTROL: `metadata` value must serialize when not pruned", ser.contains(M_VAL));
		/// `user` value is a unique sentinel; the bare `"user"` key would collide with the message role.
		assertTrue("CONTROL: `user` value must serialize when not pruned", ser.contains(U_VAL));
	}

	/// (1) OPENAI_COMPAT: session_id + metadata are pruned (Chat.java:3934) but `user` is KEPT
	/// (Chat.java:3935-3937), and buildTracingHeaders emits the x-langfuse-* headers.
	@Test
	public void openaiCompat_prunesSessionAndMetadata_keepsUser_emitsTraceHeaders() throws Exception {
		OpenAIRequest req = baseReq();
		OpenAIRequest pruned = ChatUtil.getPrunedRequest(req, tracingIgnoreFields(LLMServiceEnumType.OPENAI_COMPAT));
		String ser = wireBody(pruned);
		logger.info("[TierB-unit][COMPAT] wire body = " + ser);

		assertNotNull("serialization returned null", ser);
		/// session_id + metadata are pruned for EVERY dialect, including OPENAI_COMPAT.
		assertFalse("OPENAI_COMPAT wire body must NOT contain the `session_id` key", ser.contains("\"session_id\""));
		assertFalse("OPENAI_COMPAT wire body must NOT contain the `session_id` value", ser.contains(S_VAL));
		assertFalse("OPENAI_COMPAT wire body must NOT contain the `metadata` key", ser.contains("\"metadata\""));
		assertFalse("OPENAI_COMPAT wire body must NOT contain the `metadata` value", ser.contains(M_VAL));
		/// `user` is a standard OpenAI param — KEPT only for OPENAI_COMPAT. The unique sentinel value
		/// appears only as the `user` field value (the bare `"user"` key collides with the message role).
		assertTrue("OPENAI_COMPAT wire body must KEEP the `user` value", ser.contains(U_VAL));

		/// Header gate: the REAL buildTracingHeaders emits x-langfuse-* for OPENAI_COMPAT, read off the
		/// UN-pruned req (session_id/user are still present on req; only the wire COPY was pruned).
		Map<String, String> headers = callBuildTracingHeaders(LLMServiceEnumType.OPENAI_COMPAT, req);
		assertNotNull("OPENAI_COMPAT must produce tracing headers", headers);
		assertEquals("x-langfuse-session-id must come from session_id", S_VAL, headers.get("x-langfuse-session-id"));
		assertEquals("x-langfuse-user-id must come from user", U_VAL, headers.get("x-langfuse-user-id"));
	}

	/// (2) OPENAI (Azure) and OLLAMA: session_id + metadata AND user are all pruned, and
	/// buildTracingHeaders emits NO x-langfuse-* headers (returns null).
	@Test
	public void openaiAndOllama_pruneAllThreeTracingFields_emitNoTraceHeaders() throws Exception {
		for (LLMServiceEnumType dialect : new LLMServiceEnumType[] { LLMServiceEnumType.OPENAI, LLMServiceEnumType.OLLAMA }) {
			OpenAIRequest req = baseReq();
			OpenAIRequest pruned = ChatUtil.getPrunedRequest(req, tracingIgnoreFields(dialect));
			String ser = wireBody(pruned);
			logger.info("[TierB-unit][" + dialect + "] wire body = " + ser);

			assertNotNull(dialect + ": serialization returned null", ser);
			assertFalse(dialect + " wire body must NOT contain the `session_id` key", ser.contains("\"session_id\""));
			assertFalse(dialect + " wire body must NOT contain the `session_id` value", ser.contains(S_VAL));
			assertFalse(dialect + " wire body must NOT contain the `metadata` key", ser.contains("\"metadata\""));
			assertFalse(dialect + " wire body must NOT contain the `metadata` value", ser.contains(M_VAL));
			/// `user` is PRUNED for every non-OPENAI_COMPAT dialect. Assert on the unique sentinel value,
			/// not the `"user"` key, since the message role "user" would otherwise be a false positive.
			assertFalse(dialect + " wire body must NOT contain the `user` value", ser.contains(U_VAL));

			Map<String, String> headers = callBuildTracingHeaders(dialect, req);
			assertNull(dialect + " must produce NO tracing headers", headers);
		}
	}

	/// (3) Header overload: 3-arg injects NO extra headers; 4-arg applies fixed headers + extras.
	@Test
	public void headerOverload_threeArgNoExtras_fourArgAppliesExtras() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		final Headers[] threeHdr = new Headers[1];
		final Headers[] fourHdr = new Headers[1];

		server.createContext("/three", ex -> {
			try {
				Headers h = new Headers();
				h.putAll(ex.getRequestHeaders());
				threeHdr[0] = h;
				drain(ex.getRequestBody());
				byte[] body = "data: [DONE]\n".getBytes();
				ex.sendResponseHeaders(200, body.length);
				ex.getResponseBody().write(body);
			} catch (Exception e) {
				logger.error("echo /three handler error", e);
			} finally {
				ex.close();
			}
		});
		server.createContext("/four", ex -> {
			try {
				Headers h = new Headers();
				h.putAll(ex.getRequestHeaders());
				fourHdr[0] = h;
				drain(ex.getRequestBody());
				byte[] body = "data: [DONE]\n".getBytes();
				ex.sendResponseHeaders(200, body.length);
				ex.getResponseBody().write(body);
			} catch (Exception e) {
				logger.error("echo /four handler error", e);
			} finally {
				ex.close();
			}
		});
		server.setExecutor(null);
		server.start();
		int port = server.getAddress().getPort();
		String base = "http://127.0.0.1:" + port;

		try {
			/// 3-arg overload — the production non-OPENAI_COMPAT path. Delegates to the 4-arg with null.
			ClientUtil.postToRecordAndStream(base + "/three", "tok3", "{\"a\":1}")
				.get(15, TimeUnit.SECONDS).body().forEach(l -> { /* drain */ });

			/// 4-arg overload — the OPENAI_COMPAT path with the x-langfuse-* header map.
			Map<String, String> extras = new HashMap<>();
			extras.put("x-langfuse-session-id", "sess-XYZ");
			extras.put("x-langfuse-user-id", "user-XYZ");
			ClientUtil.postToRecordAndStream(base + "/four", "tok4", "{\"a\":1}", extras)
				.get(15, TimeUnit.SECONDS).body().forEach(l -> { /* drain */ });
		} finally {
			server.stop(0);
		}

		assertNotNull("echo server did not capture the 3-arg request", threeHdr[0]);
		assertNotNull("echo server did not capture the 4-arg request", fourHdr[0]);

		/// 3-arg: fixed headers present, NO x-langfuse-* extras injected.
		assertEquals("3-arg Accept header", "text/event-stream", threeHdr[0].getFirst("Accept"));
		assertEquals("3-arg Content-Type header", "application/json", threeHdr[0].getFirst("Content-Type"));
		assertEquals("3-arg Authorization header", "Bearer tok3", threeHdr[0].getFirst("Authorization"));
		assertNull("3-arg path must inject NO x-langfuse-session-id header",
			threeHdr[0].getFirst("x-langfuse-session-id"));
		assertNull("3-arg path must inject NO x-langfuse-user-id header",
			threeHdr[0].getFirst("x-langfuse-user-id"));

		/// 4-arg: fixed headers STILL present AND the extra headers applied after them.
		assertEquals("4-arg Accept header", "text/event-stream", fourHdr[0].getFirst("Accept"));
		assertEquals("4-arg Content-Type header", "application/json", fourHdr[0].getFirst("Content-Type"));
		assertEquals("4-arg Authorization header", "Bearer tok4", fourHdr[0].getFirst("Authorization"));
		assertEquals("4-arg must apply x-langfuse-session-id extra header",
			"sess-XYZ", fourHdr[0].getFirst("x-langfuse-session-id"));
		assertEquals("4-arg must apply x-langfuse-user-id extra header",
			"user-XYZ", fourHdr[0].getFirst("x-langfuse-user-id"));

		logger.info("[TierB-unit][HDR] PASS — 3-arg adds no extra headers; 4-arg applies fixed + x-langfuse-* extras.");
	}

	private static void drain(InputStream in) {
		try {
			byte[] buf = new byte[1024];
			while (in.read(buf) != -1) { /* consume */ }
		} catch (Exception ignore) {
			/* body already consumed / closed */
		}
	}
}
