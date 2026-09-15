package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.cote.accountmanager.util.PageIndexUtil;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * KI-72 ACCEPTANCE TEST. DETERMINISTIC, no GPU and no live LLM.
 *
 * <p>Drives the REAL {@code Chat.chat()} path against a local {@code com.sun.net.httpserver}
 * capture server (the pattern in {@code TestTierBTracingUnit#headerOverload_threeArgNoExtraS},
 * extended to record the request BODY off the socket) and asserts on the <b>exact bytes AM7 put
 * on the wire</b>. Asserting the captured body rather than the returned {@code OpenAIResponse} is
 * deliberate: the assertions then hold whatever the fake server answers, so a null/garbage
 * completion cannot mask a wire-level regression.</p>
 *
 * <p>The connections are PERSISTED (the pattern in
 * {@code TestLiteLLMOllamaProxy#createPersistedConnection}) and read back with {@code dialect} AND
 * {@code upstream} explicitly projected, because {@code Chat.configureChat} re-queries the
 * connection by FK id and resolves both axes from THAT row - a value set only on an in-memory
 * record would never be seen.</p>
 *
 * <pre>
 * Case A  dialect=OPENAI_COMPAT, upstream=OLLAMA  (the KI-72 fix: Ollama behind a LiteLLM proxy)
 * Case B  dialect=OPENAI_COMPAT, upstream unset   (the Azure case: nothing Ollama-only may be sent)
 * Case C  dialect=OLLAMA                          (native control - must be unchanged)
 * Case D  dialect=OLLAMA + an analyze/keyframe path that overrides the token value
 * </pre>
 *
 * <p><b>Case B is the non-vacuous half and matters as much as A.</b> If A passed and B also sent
 * the extensions, the change would simply be "always emit", which is the very thing KI-72
 * forbids: {@code OPENAI_COMPAT} also fronts Azure OpenAI, which rejects unknown parameters.</p>
 *
 * <p><b>Case D</b> guards the deliberate per-path overrides. {@code Chat.applyAnalyzeOptions}
 * calls {@code applyChatOptions} FIRST (which now emits {@code num_ctx} from chatOptions) and THEN
 * writes the analyze value over the resolved token field; the scene path writes 256 and the
 * keyframe path writes {@code KEYFRAME_MAX_TOKENS} the same way. If the new {@code num_ctx}
 * emission landed after those, an analyze call would silently run at the full conversational
 * context - a regression in cost and latency that nothing else would surface.</p>
 */
public class TestUpstreamWireEmission extends BaseTest {

	/// Distinctive on purpose: not the openaiRequest model default (2048), not the chatOptions
	/// default (8192), not Chat.ANALYZE_NUM_CTX (8192), and not the 40960 pinned on the LiteLLM
	/// model entry. If this exact number appears on the wire it can only have come from chatOptions.
	private static final int DISTINCT_NUM_CTX = 12288;
	private static final int DISTINCT_MAX_TOKENS = 1777;
	private static final int DISTINCT_TOP_K = 37;
	private static final int DISTINCT_REPEAT_LAST_N = 71;
	private static final int DISTINCT_NUM_GPU = 3;
	private static final double DISTINCT_REPEAT_PENALTY = 1.13;
	private static final double DISTINCT_TYPICAL_P = 0.83;
	private static final double DISTINCT_MIN_P = 0.07;

	/// Deliberately short: every case talks to a local socket that answers instantly, so the only
	/// thing this bounds is how long a broken case stalls before failing.
	private static final int REQUEST_TIMEOUT_SEC = 20;

	/// Every Ollama-only extension the KI-72 block emits. Case B asserts ALL of these are absent.
	private static final String[] OLLAMA_EXTENSIONS = {
		"num_ctx", "top_k", "repeat_penalty", "typical_p", "min_p", "repeat_last_n", "num_gpu"
	};
	/// The same list without num_ctx, which case A splits out into its own test (see caseA2).
	private static final String[] OLLAMA_EXTENSIONS_EXCEPT_NUM_CTX = {
		"top_k", "repeat_penalty", "typical_p", "min_p", "repeat_last_n", "num_gpu"
	};

	private HttpServer server = null;
	/// path -> captured request body, exactly as read off the socket.
	private final Map<String, String> captured = new ConcurrentHashMap<>();

	@After
	public void stopCaptureServer() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	/// Bind 127.0.0.1:<ephemeral> and serve BOTH transport routes Chat.getServiceUrl can build:
	/// /v1/chat/completions (OPENAI_COMPAT) and /api/chat (native OLLAMA). The response is the
	/// minimal terminating frame for each wire format so chat() returns promptly instead of
	/// waiting out the latch.
	private String startCaptureServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", ex -> {
			try {
				captured.put("/v1/chat/completions", readAll(ex.getRequestBody()));
				byte[] body = ("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}\n"
					+ "data: [DONE]\n").getBytes(StandardCharsets.UTF_8);
				ex.getResponseHeaders().add("Content-Type", "text/event-stream");
				ex.sendResponseHeaders(200, body.length);
				ex.getResponseBody().write(body);
			} catch (Exception e) {
				logger.error("capture /v1/chat/completions handler error", e);
			} finally {
				ex.close();
			}
		});
		server.createContext("/api/chat", ex -> {
			try {
				captured.put("/api/chat", readAll(ex.getRequestBody()));
				/// Native Ollama streams bare JSON lines; done=true terminates processStreamChunk.
				byte[] body = ("{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}\n")
					.getBytes(StandardCharsets.UTF_8);
				ex.getResponseHeaders().add("Content-Type", "application/x-ndjson");
				ex.sendResponseHeaders(200, body.length);
				ex.getResponseBody().write(body);
			} catch (Exception e) {
				logger.error("capture /api/chat handler error", e);
			} finally {
				ex.close();
			}
		});
		server.setExecutor(null);
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static String readAll(InputStream in) {
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			byte[] buf = new byte[4096];
			int r;
			while ((r = in.read(buf)) != -1) {
				bos.write(buf, 0, r);
			}
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	/// PERSIST a system.connection carrying BOTH axes, then read it back with both explicitly
	/// projected and assert they actually stored. Modeled on
	/// TestLiteLLMOllamaProxy.createPersistedConnection, which explains why persistence is required
	/// rather than incidental; `upstream` is added here for the same reason `dialect` is there.
	private BaseRecord persistConnection(BaseRecord user, String name, String serverUrl,
			ConnectionDialectEnumType dialect, ConnectionUpstreamEnumType upstream) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = IOSystem.getActiveContext().getFactory()
			.newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		assertNotNull("connection factory newInstance returned null", c);
		c.set("serverUrl", serverUrl);
		c.set("requestTimeout", REQUEST_TIMEOUT_SEC);
		c.set(FieldNames.FIELD_DIALECT, dialect);
		if (upstream != null) {
			c.set(FieldNames.FIELD_UPSTREAM, upstream);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, c);
		assertNotNull("AccessPoint.create returned null for system.connection '" + name + "'", created);

		long connId = created.get(FieldNames.FIELD_ID);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "serverUrl",
			"requestTimeout", "apiKey", FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM });
		cq.setCache(false);
		BaseRecord back = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
		assertNotNull("persisted connection id=" + connId + " could not be read back", back);
		assertEquals("system.connection.dialect did not persist", dialect, back.getEnum(FieldNames.FIELD_DIALECT));
		ConnectionUpstreamEnumType backUp = back.getEnum(FieldNames.FIELD_UPSTREAM);
		if (upstream != null) {
			assertEquals("system.connection.upstream did not persist - without it the connection"
				+ " falls back to the dialect inference and this test would prove the wrong path",
				upstream, backUp);
		}
		logger.info("[KI-72][WIRE] connection '" + name + "' id=" + connId + " dialect="
			+ back.getEnum(FieldNames.FIELD_DIALECT) + " upstream=" + backUp + " url=" + serverUrl);
		return back;
	}

	/// chatConfig IN MEMORY (Chat reads only its fields, never its id) with DISTINCTIVE chatOptions.
	/// serviceType is deliberately left at its schema default (OPENAI) so the connection's dialect
	/// is the only thing that can produce a working transport - the same self-enforcing setup
	/// TestLiteLLMOllamaProxy documents.
	private BaseRecord chatConfigWithDistinctOptions(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory()
			.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		/// A model name that trips none of getMaxTokenField's per-model nuances ("o..." ->
		/// max_completion_tokens, "gpt-5..." -> no token field at all).
		cfg.set("model", "qwen3:8b");
		cfg.set("stream", false);

		BaseRecord opts = cfg.get("chatOptions");
		if (opts == null) {
			opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			cfg.set("chatOptions", opts);
		}
		opts.set("num_ctx", DISTINCT_NUM_CTX);
		opts.set("max_tokens", DISTINCT_MAX_TOKENS);
		opts.set("top_k", DISTINCT_TOP_K);
		opts.set("repeat_penalty", DISTINCT_REPEAT_PENALTY);
		opts.set("typical_p", DISTINCT_TYPICAL_P);
		opts.set("min_p", DISTINCT_MIN_P);
		opts.set("repeat_last_n", DISTINCT_REPEAT_LAST_N);
		opts.set("num_gpu", DISTINCT_NUM_GPU);
		opts.set("temperature", 0.61);
		return cfg;
	}

	/// The OpenAIRequest the last dispatchAndCapture() handed to chat(), i.e. the request state
	/// BEFORE Chat.chatInternal prunes it into the wire copy. Comparing this against the captured
	/// body is what distinguishes "applyChatOptions never set the field" from "chatInternal set it
	/// and then pruned it off the wire" - two very different defects with one identical symptom.
	private OpenAIRequest lastRequest = null;

	/// Run one real chat() against the capture server and return the parsed wire body.
	private JsonNode dispatchAndCapture(String path, BaseRecord user, BaseRecord cfg,
			LLMServiceEnumType expectService, ConnectionUpstreamEnumType expectUpstream) throws Exception {
		Chat chat = new Chat(user, cfg, null);
		assertEquals("Chat resolved the wrong wire dialect", expectService, chat.getServiceType());
		assertEquals("Chat resolved the wrong upstream family", expectUpstream, chat.getUpstream());

		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false); /// buffer mode: chat() blocks
		/// EXPLICITLY populate `think` with false. Chat.chatInternal's keepThink gate is
		/// (upstream == OLLAMA && req.hasField("think")), and applyOllamaUpstreamOptions only emits
		/// `think` when it is TRUE - so `think:false` reaching the wire depends entirely on the
		/// upstream half of that gate. This is exactly what a chatConfig that wants thinking OFF on a
		/// hybrid reasoning model (qwen3) does; see TestLiteLLMOllamaProxy testD.
		req.set("think", false);
		chat.newMessage(req, "ping", Chat.userRole);

		assertEquals("Chat built the wrong transport URL",
			chat.getServerUrl() + path, chat.getServiceUrl(req));

		lastRequest = req;
		chat.chat(req);

		String body = captured.get(path);
		assertNotNull("the capture server recorded NO request body at " + path
			+ " - AM7 never dispatched, so there is nothing to assert about the wire", body);
		logger.info("[KI-72][WIRE] " + path + " captured body = " + body);
		return new ObjectMapper().readTree(body);
	}

	private static void assertHas(JsonNode n, String field, String why) {
		assertTrue("wire body is MISSING `" + field + "` - " + why, n.has(field));
	}

	private static void assertHasNot(JsonNode n, String field, String why) {
		assertFalse("wire body CONTAINS `" + field + "` - " + why, n.has(field));
	}

	/// CASE A, part 1 - the KI-72 fix, everything EXCEPT num_ctx.
	/// dialect=OPENAI_COMPAT + upstream=OLLAMA: an Ollama reached through a LiteLLM proxy must
	/// receive think:false, every other Ollama extension, and max_tokens.
	///
	/// SPLIT FROM PART 2 DELIBERATELY, and not to make anything pass: JUnit stops a method at its
	/// first failed assertion, so a single combined case would report only `num_ctx` and say nothing
	/// about whether the other eight parameters landed. Split, the run states exactly which half of
	/// the fix reaches the wire. Both halves keep their full strength.
	@Test
	public void caseA1_openAiCompatWithOllamaUpstream_sendsExtensionsAndThink() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserA");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 A1 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 A1 " + nonce);
		cfg.set("connection", conn);

		JsonNode body = dispatchAndCapture("/v1/chat/completions", user, cfg,
			LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);

		/// `think` must be present AND false. Present-and-true would be a different bug (thinking
		/// back on); absent means the upstream gate dropped it and a chatConfig that turned thinking
		/// off gets it back the moment it is repointed at a proxy.
		assertHas(body, "think", "KI-72: `think` is an Ollama extension and must survive on a proxied"
			+ " Ollama upstream, or a chatConfig that deliberately set think:false gets thinking back ON");
		assertFalse("`think` must be false, not true", body.get("think").asBoolean());

		for (String f : OLLAMA_EXTENSIONS_EXCEPT_NUM_CTX) {
			assertHas(body, f, "KI-72: every Ollama extension parameter must ride on a proxied Ollama upstream");
		}
		assertEquals(DISTINCT_TOP_K, body.get("top_k").asInt());
		assertEquals(DISTINCT_REPEAT_PENALTY, body.get("repeat_penalty").asDouble(), 0.0001);
		assertEquals(DISTINCT_TYPICAL_P, body.get("typical_p").asDouble(), 0.0001);
		assertEquals(DISTINCT_MIN_P, body.get("min_p").asDouble(), 0.0001);
		assertEquals(DISTINCT_REPEAT_LAST_N, body.get("repeat_last_n").asInt());
		assertEquals(DISTINCT_NUM_GPU, body.get("num_gpu").asInt());

		/// max_tokens is the OPENAI_COMPAT token field AND is additionally emitted by the Ollama
		/// block so generation terminates at the user's cap rather than running unbounded.
		assertHas(body, "max_tokens", "the resolved token field for an OPENAI_COMPAT dialect");
		assertEquals(DISTINCT_MAX_TOKENS, body.get("max_tokens").asInt());

		/// The extensions must ride at the TOP LEVEL - there is no `options` sub-object on this wire.
		assertHasNot(body, "options", "the Ollama OpenAI-compatible endpoint has no `options`"
			+ " sub-object; applyOllamaUpstreamOptions strips it and promotes the extensions");
		logger.info("[KI-72][WIRE][A1] PASS");
	}

	/// CASE A, part 2 - THE HEADLINE KI-72 ACCEPTANCE CRITERION: `num_ctx` must reach the wire, at
	/// the chatOptions value, on a proxied Ollama upstream.
	///
	/// This is the parameter KI-72 is named for. It also pins the MECHANISM, so a failure here is
	/// self-diagnosing: it asserts that applyChatOptions DID put num_ctx on the OpenAIRequest and
	/// then asserts it survived onto the wire. If the first passes and the second fails, the value
	/// was set and then PRUNED, which points at Chat.chatInternal's token-field prune
	/// (`ignoreFields.addAll({num_ctx, max_tokens, max_completion_tokens} minus tokField)`) - a
	/// DIALECT-keyed filter that was not re-keyed on the upstream family. For an OPENAI_COMPAT
	/// dialect ChatUtil.getMaxTokenField resolves "max_tokens", so "num_ctx" lands in the prune
	/// list and applyOllamaUpstreamOptions' new `req.set("num_ctx", ...)` is stripped off the wire
	/// copy. Measured live: a body num_ctx ALWAYS wins over the value pinned on the LiteLLM model
	/// entry and is NOT dropped by drop_params, so the parameter genuinely is the working channel -
	/// AM7 just is not sending it.
	@Test
	public void caseA2_openAiCompatWithOllamaUpstream_sendsNumCtx() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserA");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 A2 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 A2 " + nonce);
		cfg.set("connection", conn);

		JsonNode body = dispatchAndCapture("/v1/chat/completions", user, cfg,
			LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);

		/// Mechanism, half 1: the request AM7 built really does carry it.
		assertNotNull(lastRequest);
		assertTrue("applyChatOptions did not even put num_ctx on the OpenAIRequest for a proxied"
			+ " Ollama upstream - the defect is in ChatUtil.applyOllamaUpstreamOptions, not in the"
			+ " wire prune", lastRequest.hasField("num_ctx"));
		assertEquals("the request carries the wrong num_ctx", DISTINCT_NUM_CTX,
			(int) (Integer) lastRequest.get("num_ctx"));

		/// Mechanism, half 2: it must survive onto the wire.
		assertHas(body, "num_ctx", "KI-72 NOT MET: an Ollama upstream behind an OPENAI_COMPAT proxy"
			+ " must receive num_ctx. The value IS on the OpenAIRequest (asserted above) but is"
			+ " absent from the captured body, so Chat.chatInternal pruned it: its token-field"
			+ " ignore-list is keyed on the wire DIALECT (getMaxTokenField -> \"max_tokens\" for"
			+ " OPENAI_COMPAT) and therefore prunes \"num_ctx\", undoing"
			+ " applyOllamaUpstreamOptions. The model then silently runs at whatever the proxy or the"
			+ " server defaults to - which is the original KI-72 symptom, unfixed on the wire.");
		assertEquals("num_ctx must carry the chatOptions value, not a default",
			DISTINCT_NUM_CTX, body.get("num_ctx").asInt());
		logger.info("[KI-72][WIRE][A2] PASS");
	}

	/// CASE B - THE AZURE CASE, and the non-vacuous half. dialect=OPENAI_COMPAT with `upstream`
	/// unset: NOTHING Ollama-only may reach the wire. Azure OpenAI rejects unknown parameters
	/// outright ("Unknown parameter: 'think'"), so "just always emit them" is not an available fix.
	@Test
	public void caseB_openAiCompatWithNoUpstream_sendsNoOllamaExtensions() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserB");
		assertNotNull("test user is null", user);

		/// upstream deliberately NOT asserted - the row keeps the schema default.
		BaseRecord conn = persistConnection(user, "KI72 B Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, null);
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 B " + nonce);
		cfg.set("connection", conn);

		JsonNode body = dispatchAndCapture("/v1/chat/completions", user, cfg,
			LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.UNKNOWN);

		assertHasNot(body, "num_ctx", "KI-72 PROHIBITION: an OPENAI_COMPAT endpoint with no asserted"
			+ " upstream may be Azure OpenAI, which rejects num_ctx");
		assertHasNot(body, "think", "KI-72 PROHIBITION: `think` is Ollama-only and Azure rejects the"
			+ " parameter in ANY form - even though the request explicitly set think:false");
		for (String f : OLLAMA_EXTENSIONS) {
			assertHasNot(body, f, "KI-72 PROHIBITION: no Ollama extension may be sent to an"
				+ " OPENAI_COMPAT endpoint whose upstream is not asserted as Ollama");
		}
		/// ...but the standard OpenAI parameters must still be there, or this case would pass
		/// vacuously by sending nothing at all.
		assertHas(body, "max_tokens", "the standard OpenAI token field must still be sent");
		assertEquals(DISTINCT_MAX_TOKENS, body.get("max_tokens").asInt());
		assertHas(body, "temperature", "standard OpenAI sampling parameters must still be sent");
		assertHas(body, "messages", "the request must still carry its messages");
		assertHas(body, "model", "the request must still carry the model");
		logger.info("[KI-72][WIRE][B] PASS");
	}

	/// CASE C - NATIVE CONTROL. dialect=OLLAMA. Unchanged from the pre-change behaviour in these
	/// keys: num_ctx present (it is the native token field), think:false present, extensions at the
	/// top level with no `options` sub-object.
	@Test
	public void caseC_nativeOllamaDialect_isUnchanged() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserC");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 C Conn " + nonce, base,
			ConnectionDialectEnumType.OLLAMA, null);
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 C " + nonce);
		cfg.set("connection", conn);

		/// upstream unset + dialect OLLAMA must INFER an OLLAMA upstream, which is what keeps every
		/// pre-existing native connection (11 such rows in am7db, all with a NULL upstream column)
		/// behaving exactly as before.
		JsonNode body = dispatchAndCapture("/api/chat", user, cfg,
			LLMServiceEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA);

		assertHas(body, "num_ctx", "native Ollama must receive num_ctx");
		assertEquals("native num_ctx must carry the chatOptions value",
			DISTINCT_NUM_CTX, body.get("num_ctx").asInt());
		assertHas(body, "think", "an explicitly populated think must survive on the native path");
		assertFalse("`think` must be false", body.get("think").asBoolean());
		for (String f : OLLAMA_EXTENSIONS) {
			assertHas(body, f, "native Ollama must receive every extension parameter");
		}
		assertEquals(DISTINCT_TOP_K, body.get("top_k").asInt());

		/// max_tokens is ABSENT on the native wire, and that is PRE-EXISTING, not a KI-72 effect.
		/// applyOllamaUpstreamOptions sets it on the request, but Chat.chatInternal's token-field
		/// ignore-list prunes every one of {num_ctx, max_tokens, max_completion_tokens} except the
		/// resolved tokField, which for a native OLLAMA dialect IS num_ctx. Measured from the
		/// captured body (I first asserted it present - that was my expectation, not the shipped
		/// behaviour). The same prune is what removes num_ctx on the PROXIED path; see caseA2.
		assertHasNot(body, "max_tokens", "pre-existing native behaviour: only the resolved token"
			+ " field (num_ctx) survives Chat.chatInternal's token-field prune");
		assertHasNot(body, "options", "there must be no `options` sub-object - the extensions ride"
			+ " at the top level (a sub-object auto-populated with model defaults and silently"
			+ " overrode the user's temperature/top_p/num_ctx/max_tokens)");
		/// max_completion_tokens is the o-series field and must never be sent for this model.
		assertHasNot(body, "max_completion_tokens", "only the resolved token field may be sent");
		logger.info("[KI-72][WIRE][C] PASS");
	}

	/// CASE D - the deliberate per-path token overrides must SURVIVE the new num_ctx emission.
	///
	/// Exercises the REAL private Chat.applyAnalyzeOptions (reflection, the idiom
	/// TestTierBTracingUnit uses for buildTracingHeaders) and the REAL private
	/// Chat.buildKeyframeRequest. Both call applyChatOptions FIRST - which now emits num_ctx from
	/// chatOptions - and THEN write their own value over the resolved token field. On the native
	/// OLLAMA path that field IS num_ctx, so ordering is the whole question: if the new emission
	/// ran last, an analyze call would quietly run at the full conversational context.
	@Test
	public void caseD_nativeOllamaAnalyzeAndKeyframeOverridesSurvive() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserD");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 D Conn " + nonce, base,
			ConnectionDialectEnumType.OLLAMA, null);
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 D " + nonce);
		cfg.set("connection", conn);

		Chat chat = new Chat(user, cfg, null);
		assertEquals(LLMServiceEnumType.OLLAMA, chat.getServiceType());
		assertEquals(ConnectionUpstreamEnumType.OLLAMA, chat.getUpstream());
		assertEquals("precondition: the native token field must be num_ctx, which is what makes this"
			+ " test about the new emission at all", "num_ctx",
			ChatUtil.getMaxTokenField(cfg, LLMServiceEnumType.OLLAMA));

		/// A plain chat request first, to prove the fixture really does carry the distinctive value
		/// that the analyze path must then override (otherwise D could pass vacuously).
		OpenAIRequest plain = chat.newRequest(chat.getModel());
		chat.newMessage(plain, "hello there", Chat.userRole);
		chat.newMessage(plain, "hi, how can I help?", Chat.assistantRole);
		assertEquals("precondition: a normal request carries the chatOptions num_ctx",
			DISTINCT_NUM_CTX, (int) (Integer) plain.get("num_ctx"));

		/// --- analyze path ---
		OpenAIRequest areq = new OpenAIRequest();
		Method m = Chat.class.getDeclaredMethod("applyAnalyzeOptions", OpenAIRequest.class, OpenAIRequest.class);
		m.setAccessible(true);
		m.invoke(chat, plain, areq);
		int analyzeNumCtx = areq.get("num_ctx");
		logger.info("[KI-72][WIRE][D] analyze num_ctx=" + analyzeNumCtx
			+ " (ANALYZE_NUM_CTX=" + Chat.ANALYZE_NUM_CTX + ", chatOptions=" + DISTINCT_NUM_CTX + ")");
		assertEquals("the analyze num_ctx override was STOMPED by the new KI-72 num_ctx emission -"
			+ " analyze calls would silently run at the full conversational context",
			Chat.ANALYZE_NUM_CTX, analyzeNumCtx);
		assertEquals("applyAnalyzeOptions must also still pin its own temperature",
			Chat.ANALYZE_TEMPERATURE, (double) (Double) areq.get("temperature"), 0.0001);

		/// --- keyframe path (same mechanism, different cap) ---
		Method kf = Chat.class.getDeclaredMethod("buildKeyframeRequest", OpenAIRequest.class, int.class);
		kf.setAccessible(true);
		OpenAIRequest kfReq = (OpenAIRequest) kf.invoke(chat, plain, 0);
		assertNotNull("buildKeyframeRequest returned null - the fixture has no formattable history,"
			+ " so this half of case D did not exercise anything", kfReq);
		int kfNumCtx = kfReq.get("num_ctx");
		logger.info("[KI-72][WIRE][D] keyframe num_ctx=" + kfNumCtx
			+ " (KEYFRAME_MAX_TOKENS=" + Chat.KEYFRAME_MAX_TOKENS + ")");
		assertEquals("the keyframe token cap was STOMPED by the new KI-72 num_ctx emission",
			Chat.KEYFRAME_MAX_TOKENS, kfNumCtx);

		/// The Ollama extensions must STILL be applied on the analyze request - the override is
		/// scoped to the token field only.
		for (String f : new String[] { "top_k", "repeat_penalty", "typical_p", "min_p", "repeat_last_n", "num_gpu" }) {
			assertTrue("analyze request lost the Ollama extension `" + f + "`", areq.hasField(f));
		}
		assertEquals(DISTINCT_TOP_K, (int) (Integer) areq.get("top_k"));
		logger.info("[KI-72][WIRE][D] PASS");
	}

	/// CONTROL, so none of the negative assertions above can be vacuous: the SAME chatOptions and
	/// the SAME serializer, but with the upstream forced to OLLAMA on a Chat whose dialect is
	/// OPENAI_COMPAT, produce a body that DOES contain the extensions - while the identical setup
	/// with upstream UNKNOWN does not. This isolates the upstream axis as the only difference.
	@Test
	public void control_upstreamAxisIsTheOnlyDifference() throws Exception {
		BaseRecord user = getCreateUser("ki72WireUserCtl");
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 CTL "
			+ UUID.randomUUID().toString().substring(0, 8));

		OpenAIRequest withOllama = new OpenAIRequest();
		ChatUtil.applyChatOptions(withOllama, cfg, LLMServiceEnumType.OPENAI_COMPAT,
			ConnectionUpstreamEnumType.OLLAMA);
		OpenAIRequest withUnknown = new OpenAIRequest();
		ChatUtil.applyChatOptions(withUnknown, cfg, LLMServiceEnumType.OPENAI_COMPAT,
			ConnectionUpstreamEnumType.UNKNOWN);

		assertEquals("upstream=OLLAMA must put the chatOptions num_ctx on the request",
			DISTINCT_NUM_CTX, (int) (Integer) withOllama.get("num_ctx"));
		assertEquals("upstream=OLLAMA must put the chatOptions top_k on the request",
			DISTINCT_TOP_K, (int) (Integer) withOllama.get("top_k"));

		/// With the upstream unasserted, applyChatOptions must not touch the extensions at all: they
		/// stay at the openaiRequest schema defaults (num_ctx 2048, top_k 0), NOT the chatOptions
		/// values. This is the in-memory counterpart of case B's wire assertion.
		assertFalse("upstream=UNKNOWN must NOT apply the chatOptions num_ctx",
			DISTINCT_NUM_CTX == (int) (Integer) withUnknown.get("num_ctx"));
		assertFalse("upstream=UNKNOWN must NOT apply the chatOptions top_k",
			DISTINCT_TOP_K == (int) (Integer) withUnknown.get("top_k"));
		logger.info("[KI-72][WIRE][CONTROL] PASS - upstream=OLLAMA num_ctx=" + withOllama.get("num_ctx")
			+ " top_k=" + withOllama.get("top_k") + " vs upstream=UNKNOWN num_ctx="
			+ withUnknown.get("num_ctx") + " top_k=" + withUnknown.get("top_k"));
	}

	// ─────────────────────── CASE E - the RESUMED-SESSION path ───────────────────────

	/// PERSIST a chatConfig (unlike chatConfigWithDistinctOptions, which is in-memory on purpose).
	/// The resumed-session branch of ChatUtil.getOpenAIRequest re-reads the chatConfig through
	/// OlioUtil.getFullRecord, i.e. BY ID, so an in-memory config resolves to null there and the
	/// branch is never even entered. `think` is set TRUE in chatOptions deliberately: the emission
	/// block only writes `think` when it is truthy, so its presence on the wire cannot come from
	/// anywhere else and is unambiguous proof the block ran.
	private BaseRecord persistChatConfigWithDistinctOptions(BaseRecord user, String name,
			BaseRecord conn, LLMServiceEnumType serviceType) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory()
			.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("model", "qwen3:8b");
		cfg.set("stream", false);
		cfg.set("serviceType", serviceType);
		cfg.set("connection", conn);

		BaseRecord opts = cfg.get("chatOptions");
		if (opts == null) {
			opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			cfg.set("chatOptions", opts);
		}
		opts.set("num_ctx", DISTINCT_NUM_CTX);
		opts.set("max_tokens", DISTINCT_MAX_TOKENS);
		opts.set("top_k", DISTINCT_TOP_K);
		opts.set("repeat_penalty", DISTINCT_REPEAT_PENALTY);
		opts.set("typical_p", DISTINCT_TYPICAL_P);
		opts.set("min_p", DISTINCT_MIN_P);
		opts.set("repeat_last_n", DISTINCT_REPEAT_LAST_N);
		opts.set("num_gpu", DISTINCT_NUM_GPU);
		opts.set("temperature", 0.61);
		opts.set("think", true);

		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		assertNotNull("AccessPoint.create returned null for olio.llm.chatConfig " + name, created);
		BaseRecord back = OlioUtil.getFullRecord(created);
		assertNotNull("the persisted chatConfig could not be read back by id", back);
		BaseRecord backOpts = back.get("chatOptions");
		assertNotNull("chatOptions did not persist with the chatConfig - the whole fixture would then"
			+ " prove nothing, since every extension value would be a schema default", backOpts);
		assertEquals("the distinctive num_ctx did not persist onto chatOptions",
			DISTINCT_NUM_CTX, (int) (Integer) backOpts.get("num_ctx"));
		return back;
	}

	/// Build a PERSISTED chatRequest with a PERSISTED session, then hand it back re-read so its
	/// `session` FK is populated. ChatUtil.getCreateChatRequest is the production path that creates
	/// the session; AccessPoint.create returns identity fields only, so the SECOND call - which
	/// finds the existing record rather than creating one - is what yields a record carrying
	/// `session`, `chatConfig` and `promptConfig`.
	private ChatRequest persistedResumableChatRequest(BaseRecord user, String name, BaseRecord cfg) {
		BaseRecord pcfg = OlioTestUtil.getPromptConfig(user, name + " Prompt");
		assertNotNull("promptConfig is null - getOpenAIRequest requires promptConfig or"
			+ " promptTemplate alongside chatConfig, so the branch under test would be skipped", pcfg);

		BaseRecord created = ChatUtil.getCreateChatRequest(user, name, cfg, pcfg);
		assertNotNull("getCreateChatRequest returned null on the CREATE pass", created);
		BaseRecord found = ChatUtil.getCreateChatRequest(user, name, cfg, pcfg);
		assertNotNull("getCreateChatRequest returned null on the FIND pass", found);
		assertNotNull("the re-read chatRequest carries no session, so getOpenAIRequest would take"
			+ " the NEW-session branch and this test would silently exercise the wrong path",
			found.get("session"));
		return new ChatRequest(found);
	}

	/// Fields that ONLY the upstream-gated extension block writes, and which therefore discriminate
	/// "the resumed-session branch applied chat options" from "it did not".
	///
	/// num_ctx IS DELIBERATELY NOT IN THIS LIST, and that omission is the whole point. On a native
	/// OLLAMA dialect getMaxTokenField resolves "num_ctx", so applyChatOptions' shared token-field
	/// route (ChatUtil.java:2276-2279) sets it REGARDLESS of the upstream. Asserting num_ctx on this
	/// path therefore proves nothing about the upstream gate. Nor does max_tokens: on the native
	/// path the token-field prune strips it off the wire (see caseC).
	private static final String[] UPSTREAM_GATED_ONLY = {
		"top_k", "repeat_penalty", "typical_p", "min_p", "repeat_last_n", "num_gpu"
	};
	/// Split by SCHEMA TYPE, not by convenience: openaiRequest declares top_k/repeat_last_n/num_gpu
	/// as int and repeat_penalty/typical_p/min_p as double, and BaseRecord.set dispatches on the
	/// schema's type - handing an Integer to a double field throws ClassCastException inside
	/// DoubleValueType.setValue. (Hit while writing this test.)
	private static final String[] UPSTREAM_GATED_INT = { "top_k", "repeat_last_n", "num_gpu" };
	private static final String[] UPSTREAM_GATED_DOUBLE = { "repeat_penalty", "typical_p", "min_p" };

	/// Neutralize the extension values ON THE PERSISTED SESSION, then prove the neutralization
	/// actually stuck.
	///
	/// WITHOUT THIS THE TEST CANNOT FAIL. ChatUtil.getCreateChatRequest builds the session through
	/// the NEW-session path, which already applies chat options and then persists the request - so a
	/// resumed session arrives carrying the extension values from its creation, and the wire body
	/// shows them whether or not the resumed branch re-applied anything. The first version of this
	/// test asserted mere presence and passed against the known-broken code; it was a negative
	/// result from a setup incapable of exhibiting the condition. Zeroing these first makes the
	/// resumed-session apply the ONLY thing that can put the distinctive values back.
	///
	/// It also models the real scenario for the 113 live connections: a session persisted earlier,
	/// with chatOptions edited since.
	private void neutralizeSessionExtensions(BaseRecord user, ChatRequest chatReq) throws Exception {
		OpenAIRequest sess = new OpenAIRequest(OlioUtil.getFullRecord(chatReq.get("session"), false));
		for (String f : UPSTREAM_GATED_INT) {
			sess.set(f, 0);
		}
		for (String f : UPSTREAM_GATED_DOUBLE) {
			sess.set(f, 0.0);
		}
		sess.set("think", false);
		assertTrue("saveSession did not persist the neutralized session", ChatUtil.saveSession(user, sess));

		OpenAIRequest back = new OpenAIRequest(OlioUtil.getFullRecord(chatReq.get("session"), false));
		assertEquals("PRECONDITION FAILED: top_k was not neutralized on the persisted session, so a"
			+ " later assertion could be satisfied by the value the NEW-session path persisted at"
			+ " creation rather than by the resumed-session apply - i.e. this test would be unable to"
			+ " fail", 0, (int) (Integer) back.get("top_k"));
		assertFalse("PRECONDITION FAILED: think was not neutralized on the persisted session",
			(boolean) (Boolean) back.get("think"));
	}

	/// CASE E1 - THE REGRESSION GUARD. dialect UNKNOWN + chatConfig.serviceType OLLAMA, resumed
	/// session: the Ollama extensions and think MUST reach the wire.
	///
	/// WHY THIS SHAPE IS THE ONE THAT MATTERS. This is the shape of EVERY chatConfig-referenced
	/// connection on am7db - 113 of them carry serviceType OLLAMA with dialect NULL or UNKNOWN. The
	/// upstream family is therefore reachable only through the inference floor
	/// (ChatUtil.applyUpstreamFloor: explicit upstream -> dialect -> resolved serviceType). The
	/// resumed-session branch of getOpenAIRequest originally applied resolveUpstream(conn) WITHOUT
	/// that floor, so it resolved UNKNOWN, the extension block (gated on the upstream alone) was
	/// skipped, and every one of those 113 connections lost all nine parameters on resume - while
	/// the new-session path, which floors via Chat.getUpstream(), kept them. Same config, two
	/// different wire bodies depending on whether the user had chatted before.
	///
	/// Asserted on the CAPTURED WIRE BODY, not on the returned request: a request-level assertion
	/// would not have caught the token-field prune that removed num_ctx after it had been set.
	@Test
	public void caseE1_resumedSession_unknownDialectOllamaServiceType_sendsExtensions() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserE1");
		assertNotNull("test user is null", user);

		/// dialect UNKNOWN and upstream UNSET - the live-data shape.
		BaseRecord conn = persistConnection(user, "KI72 E1 Conn " + nonce, base,
			ConnectionDialectEnumType.UNKNOWN, null);
		BaseRecord cfg = persistChatConfigWithDistinctOptions(user, "KI72 E1 " + nonce, conn,
			LLMServiceEnumType.OLLAMA);
		ChatRequest chatReq = persistedResumableChatRequest(user, "KI72 E1 Req " + nonce, cfg);
		/// Makes the resumed-session apply the only possible source of the distinctive values.
		neutralizeSessionExtensions(user, chatReq);

		/// THE BRANCH UNDER TEST.
		OpenAIRequest resumed = ChatUtil.getOpenAIRequest(user, chatReq);
		assertNotNull("getOpenAIRequest returned null for a resumed session", resumed);

		Chat chat = ChatUtil.getChat(user, chatReq, false);
		assertNotNull("ChatUtil.getChat returned null", chat);
		assertEquals("precondition: the dialect must resolve OLLAMA from chatConfig.serviceType,"
			+ " since the connection dialect is UNKNOWN", LLMServiceEnumType.OLLAMA, chat.getServiceType());
		assertEquals("precondition: the upstream must FLOOR to OLLAMA from the resolved serviceType;"
			+ " if this is UNKNOWN the floor is missing and E1 is testing nothing",
			ConnectionUpstreamEnumType.OLLAMA, chat.getUpstream());

		resumed.setStream(false);
		chat.newMessage(resumed, "ping", Chat.userRole);
		lastRequest = resumed;
		chat.chat(resumed);

		String raw = captured.get("/api/chat");
		assertNotNull("the capture server recorded NO body at /api/chat - nothing was dispatched", raw);
		logger.info("[KI-72][WIRE][E1] /api/chat captured body = " + raw);
		JsonNode body = new ObjectMapper().readTree(raw);

		String why = "REGRESSION: a RESUMED session on an Ollama upstream reached only via the"
			+ " inference floor (dialect UNKNOWN, serviceType OLLAMA) must still receive the Ollama"
			+ " extensions. The resumed-session branch of ChatUtil.getOpenAIRequest must resolve the"
			+ " upstream through ChatUtil.applyUpstreamFloor - the same floor Chat.getUpstream()"
			+ " applies on the new-session path - or the two paths disagree and 113 live connections"
			+ " lose this on resume. The session was neutralized first, so the value on the wire can"
			+ " ONLY have come from the resumed-session apply.";
		for (String f : UPSTREAM_GATED_ONLY) {
			assertHas(body, f, why);
		}
		/// VALUES, not presence: neutralization leaves these fields present-but-zero, so a presence
		/// assertion would pass on the broken code.
		assertEquals("top_k must carry the chatOptions value - " + why,
			DISTINCT_TOP_K, body.get("top_k").asInt());
		assertEquals("repeat_last_n must carry the chatOptions value - " + why,
			DISTINCT_REPEAT_LAST_N, body.get("repeat_last_n").asInt());
		assertEquals("num_gpu must carry the chatOptions value - " + why,
			DISTINCT_NUM_GPU, body.get("num_gpu").asInt());
		assertEquals("repeat_penalty must carry the chatOptions value - " + why,
			DISTINCT_REPEAT_PENALTY, body.get("repeat_penalty").asDouble(), 0.0001);
		assertEquals("typical_p must carry the chatOptions value - " + why,
			DISTINCT_TYPICAL_P, body.get("typical_p").asDouble(), 0.0001);
		assertEquals("min_p must carry the chatOptions value - " + why,
			DISTINCT_MIN_P, body.get("min_p").asDouble(), 0.0001);
		assertHas(body, "think", "think was neutralized to false on the session and ONLY the"
			+ " upstream-gated emission block sets it true - " + why);
		assertTrue("think must be TRUE on the wire - " + why, body.get("think").asBoolean());
		/// num_ctx is asserted only for its value, and only as a sanity check: on this native path
		/// the shared token-field route sets it regardless of the upstream, so it is NOT a
		/// discriminator (see UPSTREAM_GATED_ONLY).
		assertEquals("num_ctx must carry the chatOptions value on the resumed path",
			DISTINCT_NUM_CTX, body.get("num_ctx").asInt());
		logger.info("[KI-72][WIRE][E1] PASS - resumed session re-applied the upstream-gated"
			+ " extensions after neutralization: top_k=" + body.get("top_k").asInt()
			+ " num_gpu=" + body.get("num_gpu").asInt() + " think=" + body.get("think").asBoolean()
			+ " num_ctx=" + body.get("num_ctx").asInt());
	}

	/// CASE E2 - THE NEGATIVE ARM, and it is the KI-72 prohibition verified THROUGH the floor
	/// rather than assumed. dialect OPENAI_COMPAT with upstream unset, resumed session: nothing
	/// Ollama-only may be sent, EVEN THOUGH chatConfig.serviceType still says OLLAMA.
	///
	/// This is the case a careless floor would break. resolveServiceType gives the non-UNKNOWN
	/// dialect precedence over chatConfig.serviceType, so the resolved service is OPENAI_COMPAT and
	/// inferUpstream(OPENAI_COMPAT) is UNKNOWN - never OLLAMA. If the floor had instead fallen back
	/// to the raw chatConfig.serviceType, this endpoint (which may be Azure OpenAI) would be sent
	/// Ollama-only parameters and would reject the request.
	@Test
	public void caseE2_resumedSession_openAiCompatDialectWithOllamaServiceType_sendsNothing() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserE2");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 E2 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, null);
		/// serviceType OLLAMA on purpose - the dialect must win.
		BaseRecord cfg = persistChatConfigWithDistinctOptions(user, "KI72 E2 " + nonce, conn,
			LLMServiceEnumType.OLLAMA);
		ChatRequest chatReq = persistedResumableChatRequest(user, "KI72 E2 Req " + nonce, cfg);

		OpenAIRequest resumed = ChatUtil.getOpenAIRequest(user, chatReq);
		assertNotNull("getOpenAIRequest returned null for a resumed session", resumed);

		Chat chat = ChatUtil.getChat(user, chatReq, false);
		assertNotNull("ChatUtil.getChat returned null", chat);
		assertEquals("precondition: a non-UNKNOWN dialect must beat chatConfig.serviceType",
			LLMServiceEnumType.OPENAI_COMPAT, chat.getServiceType());
		assertEquals("KI-72 PROHIBITION: an OPENAI_COMPAT connection with no asserted upstream must"
			+ " resolve UNKNOWN even when chatConfig.serviceType says OLLAMA - the floor must key off"
			+ " the RESOLVED service, which the dialect already won",
			ConnectionUpstreamEnumType.UNKNOWN, chat.getUpstream());

		resumed.setStream(false);
		chat.newMessage(resumed, "ping", Chat.userRole);
		lastRequest = resumed;
		chat.chat(resumed);

		String raw = captured.get("/v1/chat/completions");
		assertNotNull("the capture server recorded NO body at /v1/chat/completions", raw);
		logger.info("[KI-72][WIRE][E2] /v1/chat/completions captured body = " + raw);
		JsonNode body = new ObjectMapper().readTree(raw);

		for (String f : OLLAMA_EXTENSIONS) {
			assertHasNot(body, f, "KI-72 PROHIBITION BROKEN on the resumed path: an OPENAI_COMPAT"
				+ " endpoint with no asserted upstream may be Azure OpenAI, which rejects Ollama-only"
				+ " parameters. The inference floor must NOT promote chatConfig.serviceType=OLLAMA"
				+ " past a dialect that already resolved OPENAI_COMPAT.");
		}
		assertHasNot(body, "think", "think is an Ollama-only extension and must not reach a"
			+ " possibly-Azure OPENAI_COMPAT endpoint");
		logger.info("[KI-72][WIRE][E2] PASS - resumed session on an unasserted OPENAI_COMPAT"
			+ " endpoint sent no Ollama-only parameters.");
	}

	// ──────────── CASE G3 - Guardrail 3 on the wire BODY, against production code ────────────

	/// Dispatch a real chat() with a given `user` value and return the captured wire body.
	/// OPENAI_COMPAT, because that is the ONLY dialect whose body is allowed to carry `user` at all
	/// (Azure and native Ollama prune it unconditionally) - so it is the only dialect where the
	/// opaqueness half of the gate is the deciding factor.
	private JsonNode dispatchWithUser(String caseTag, String userValue, ConnectionUpstreamEnumType upstream)
			throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserG3" + caseTag);
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 G3" + caseTag + " Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, upstream);
		BaseRecord cfg = chatConfigWithDistinctOptions(user, "KI72 G3" + caseTag + " " + nonce);
		cfg.set("connection", conn);

		Chat chat = new Chat(user, cfg, null);
		assertEquals("precondition: the body `user` gate only applies to OPENAI_COMPAT",
			LLMServiceEnumType.OPENAI_COMPAT, chat.getServiceType());

		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		req.setValue("user", userValue);
		chat.newMessage(req, "ping", Chat.userRole);
		lastRequest = req;
		chat.chat(req);

		String raw = captured.get("/v1/chat/completions");
		assertNotNull("the capture server recorded NO body - nothing was dispatched", raw);
		logger.info("[KI-72][WIRE][G3" + caseTag + "] captured body = " + raw);
		return new ObjectMapper().readTree(raw);
	}

	/// CASE G3-NEG - a NON-OPAQUE `user` must not reach the wire body.
	///
	/// WHY THIS EXISTS AS A WIRE TEST. LiteLLM maps the request-body `user` onto the Langfuse
	/// trace.userId, so suppressing only the x-langfuse-user-id HEADER would still deliver PII to
	/// the trace store. The body half of Guardrail 3 was, until now, covered ONLY by a hand-written
	/// reimplementation of the gate inside TestTierBTracingUnit - a copy that had already gone stale
	/// once, and which would have stayed green if the TracingIdValidator call were deleted from
	/// Chat.chatInternal outright. This case asserts the REAL bytes from a REAL Chat.chat().
	///
	/// The value is an email address on the reserved example.com domain - never a real one, least of
	/// all in the fixtures of a PII guardrail.
	@Test
	public void caseG3neg_nonOpaqueUser_isAbsentFromTheWireBody() throws Exception {
		String pii = "someone@example.com";
		assertFalse("fixture check: the value under test must be non-opaque, or this case would be"
			+ " asserting nothing", org.cote.accountmanager.olio.llm.TracingIdValidator.isOpaque(pii));

		JsonNode body = dispatchWithUser("neg", pii, ConnectionUpstreamEnumType.OLLAMA);

		assertHasNot(body, "user", "GUARDRAIL 3: a non-opaque `user` must be pruned from the wire"
			+ " BODY even on OPENAI_COMPAT. LiteLLM maps the body `user` onto Langfuse trace.userId,"
			+ " and Langfuse already holds the full prompt and completion, so the identifier is what"
			+ " makes the trace attributable to a person.");
		/// session_id is pruned from the body for every dialect; asserted here too so this case also
		/// covers the unconditional half of the same gate.
		assertHasNot(body, "session_id", "session_id is not a valid OpenAI/Azure parameter and must"
			+ " be pruned from the body for every dialect; correlation rides the header instead");
		logger.info("[KI-72][WIRE][G3neg] PASS - a non-opaque user never reached the wire body.");
	}

	/// CASE G3-POS - the PAIRED POSITIVE, and it is what stops the negative case above from passing
	/// vacuously. If `user` were simply never serialized on this path, G3-NEG would pass for an
	/// entirely unrelated reason. With an OPAQUE value the SAME setup must put `user` on the wire.
	@Test
	public void caseG3pos_opaqueUser_isPresentOnTheWireBody() throws Exception {
		/// A dashed UUID: the canonical opaque correlation key the design tells callers to use.
		String opaque = UUID.randomUUID().toString();
		assertTrue("fixture check: the value under test must be opaque",
			org.cote.accountmanager.olio.llm.TracingIdValidator.isOpaque(opaque));

		JsonNode body = dispatchWithUser("pos", opaque, ConnectionUpstreamEnumType.OLLAMA);

		assertHas(body, "user", "an OPAQUE `user` MUST still reach the wire body on OPENAI_COMPAT -"
			+ " otherwise Guardrail 3 has broken LiteLLM/Langfuse user correlation entirely, and the"
			+ " negative case above would be passing for the wrong reason");
		assertEquals("the opaque `user` must reach the wire UNCHANGED", opaque, body.get("user").asText());
		logger.info("[KI-72][WIRE][G3pos] PASS - an opaque user reached the wire body unchanged.");
	}

	// ------------------------------------------------------------------------------------------
	// KI-72 residual family, site 5: ChatUtil.supportsSamplingParams
	// ------------------------------------------------------------------------------------------

	/// Values distinct from the SCHEMA defaults of both olio.llm.chatOptions and
	/// olio.llm.openai.openaiRequest, so a captured value can only have come from the config. Set
	/// per-case rather than in the shared helper to keep the blast radius of these additions to the
	/// two cases below.
	///
	/// NON-DEFAULT IS A CORRECTNESS REQUIREMENT HERE, NOT TIDINESS, and it cost a red run to learn:
	/// RecordSerializer OMITS any field whose value equals its schema default
	/// (RecordSerializer.java:193-200, FieldUtil.isDefault). frequency_penalty defaults to 0.0 in
	/// BOTH models, so with the default left in place it never appears on the wire no matter what
	/// the prune does - an assertion on its presence would be testing the serializer's compaction,
	/// and an assertion on its ABSENCE (caseH2) would pass vacuously.
	private static final double DISTINCT_TOP_P = 0.44;
	private static final double DISTINCT_FREQUENCY_PENALTY = 0.29;

	/// chatConfig whose MODEL NAME is the variable under test. supportsSamplingParams' fallback
	/// heuristic fires on the model string ("gpt-5..." / "o..."), so the model name is what makes
	/// the difference between the two cases here - everything else is held constant.
	private BaseRecord chatConfigWithModel(BaseRecord user, String name, String modelName) throws Exception {
		BaseRecord cfg = chatConfigWithDistinctOptions(user, name);
		cfg.set("model", modelName);
		BaseRecord opts = cfg.get("chatOptions");
		assertNotNull("chatOptions is null", opts);
		opts.set("top_p", DISTINCT_TOP_P);
		opts.set("frequency_penalty", DISTINCT_FREQUENCY_PENALTY);
		return cfg;
	}

	/// CASE H1 - THE FIX. dialect=OPENAI_COMPAT + upstream=OLLAMA with a model tag that STARTS WITH
	/// "o" (openchat; olmo and orca-mini are the other real ones). An Ollama model accepts
	/// temperature/top_p/frequency_penalty whatever its tag happens to start with - the gpt-5 /
	/// o-series prohibition is a property of OpenAI's REASONING MODELS, not of a leading letter.
	///
	/// Keyed on the dialect (the pre-change state), supportsSamplingParams sees OPENAI_COMPAT,
	/// falls through to the model-prefix heuristic, matches "openchat".startsWith("o") and returns
	/// false - so Chat.chatInternal adds temperature/top_p/frequency_penalty to the ignore list and
	/// the proxied Ollama model runs at the server defaults instead of the user's settings, silently.
	///
	/// Asserted on the CAPTURED BYTES, not on supportsSamplingParams' return value: the boolean is
	/// a means, and a test that re-derived it would pass against the old code too.
	@Test
	public void caseH1_proxiedOllamaWithOPrefixModel_keepsSamplingParams() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserH");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 H1 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);
		/// "openchat" is a real Ollama tag and it starts with the letter the o-series heuristic keys on.
		BaseRecord cfg = chatConfigWithModel(user, "KI72 H1 " + nonce, "openchat");
		cfg.set("connection", conn);

		JsonNode body = dispatchAndCapture("/v1/chat/completions", user, cfg,
			LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);

		assertHas(body, "temperature", "KI-72 residual site: a PROXIED Ollama model whose tag starts"
			+ " with \"o\" (openchat/olmo/orca-mini) must still receive temperature."
			+ " ChatUtil.supportsSamplingParams short-circuits on the wire DIALECT being OLLAMA, so on"
			+ " OPENAI_COMPAT it falls through to the gpt-5/o-series REASONING-MODEL heuristic, which"
			+ " matches the Ollama tag by accident and makes Chat.chatInternal prune the sampling"
			+ " parameters. Key it on the upstream family instead.");
		assertEquals("temperature must carry the chatOptions value", 0.61,
			body.get("temperature").asDouble(), 0.0001);
		assertHas(body, "top_p", "same site: top_p is pruned by the same boolean");
		assertEquals("top_p must carry the chatOptions value", DISTINCT_TOP_P,
			body.get("top_p").asDouble(), 0.0001);
		assertHas(body, "frequency_penalty", "same site: frequency_penalty is the third member of"
			+ " the pruned triple");
		assertEquals("frequency_penalty must carry the chatOptions value", DISTINCT_FREQUENCY_PENALTY,
			body.get("frequency_penalty").asDouble(), 0.0001);
		logger.info("[KI-72][WIRE][H1] PASS");
	}

	/// CASE H2 - THE PROHIBITION HALF, and it is what stops H1 from being satisfied by "always send
	/// them". dialect=OPENAI_COMPAT with upstream UNSET is the Azure case, and a gpt-5 deployment
	/// rejects a non-default temperature outright with HTTP 400
	/// ("Unsupported value: 'temperature' does not support 0.9 with this model").
	///
	/// Be precise about what this proves: it discriminates the fix from the forbidden shortcut of
	/// widening the test to include OPENAI_COMPAT, but it would ALSO pass with the fix reverted, so
	/// it is not evidence the fix works. H1 is that evidence.
	@Test
	public void caseH2_azureCompatGpt5Model_prunesSamplingParams() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserH");
		assertNotNull("test user is null", user);

		/// upstream deliberately NOT asserted - the Azure-behind-LiteLLM shape.
		BaseRecord conn = persistConnection(user, "KI72 H2 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, null);
		BaseRecord cfg = chatConfigWithModel(user, "KI72 H2 " + nonce, "gpt-5.6-terra");
		cfg.set("connection", conn);

		JsonNode body = dispatchAndCapture("/v1/chat/completions", user, cfg,
			LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.UNKNOWN);

		assertHasNot(body, "temperature", "an OPENAI_COMPAT endpoint with no asserted upstream may be"
			+ " an Azure gpt-5 deployment, which rejects a non-default temperature with HTTP 400");
		assertHasNot(body, "top_p", "same: gpt-5 accepts only the default top_p");
		assertHasNot(body, "frequency_penalty", "same: gpt-5 accepts only the default frequency_penalty");
		/// Non-vacuity: the request still went out and still carries its payload.
		assertHas(body, "messages", "the request must still carry its messages");
		assertHas(body, "model", "the request must still carry the model");
		logger.info("[KI-72][WIRE][H2] PASS");
	}

	// ------------------------------------------------------------------------------------------
	// KI-72 residual family, site 6: PageIndexUtil's `think` gate
	// ------------------------------------------------------------------------------------------

	/// chatConfig with THINKING TURNED ON in chatOptions, which is what makes the PageIndexUtil
	/// cases below non-vacuous - MEASURED, after a first fixture that was not.
	///
	/// With chatOptions.think left at its default (false) the gate under test changes NOTHING on the
	/// wire: olio.llm.openai.openaiRequest declares `think` with `"default": false`, `new
	/// OpenAIRequest()` materialises it into the fieldMap, and BaseRecord.hasField is
	/// `fieldMap.containsKey(name)` - so `req.hasField("think")` is already TRUE for a request nobody
	/// touched, Chat.chatInternal's keepThink is satisfied on any Ollama upstream, and `think:false`
	/// reaches the wire whether or not PageIndexUtil set it. I ran exactly that fixture against
	/// unmodified production code and it PASSED, which is the only reason this comment exists.
	///
	/// chatOptions.think=true is the configuration where the gate is load-bearing:
	/// ChatUtil.applyOllamaUpstreamOptions sets `think:true` on the request, and the gate is the only
	/// thing that forces it back off for LLM-TOC / summarization - the calls whose output is parsed
	/// as strict JSON or stored as a summary, and which a chain-of-thought preamble breaks.
	private BaseRecord chatConfigWithThinkingOn(BaseRecord user, String name) throws Exception {
		BaseRecord cfg = chatConfigWithDistinctOptions(user, name);
		BaseRecord opts = cfg.get("chatOptions");
		assertNotNull("chatOptions is null", opts);
		opts.set("think", true);
		return cfg;
	}

	/// Drive the REAL private PageIndexUtil.callChat - the shared invocation behind BOTH document
	/// summarization and LLM-TOC extraction - against the capture server, and return the parsed wire
	/// body. Reflection on the production method is the same idiom caseD uses for the private
	/// Chat.applyAnalyzeOptions, and it is the point: the `think` gate under test lives INSIDE
	/// callChat, so a test that called anything else would be asserting a copy of it.
	private JsonNode dispatchPageIndexChatAndCapture(BaseRecord user, BaseRecord cfg) throws Exception {
		Method m = PageIndexUtil.class.getDeclaredMethod("callChat", BaseRecord.class, BaseRecord.class,
			String.class, String.class);
		m.setAccessible(true);
		m.invoke(null, user, cfg, "You are a test assistant. Answer with one word.", "ping");

		String body = captured.get("/v1/chat/completions");
		assertNotNull("the capture server recorded NO request body - PageIndexUtil.callChat never"
			+ " dispatched, so there is nothing to assert about the wire", body);
		logger.info("[KI-72][WIRE][PAGEINDEX] captured body = " + body);
		return new ObjectMapper().readTree(body);
	}

	/// CASE I1 - THE FIX. A chatConfig repointed at the LiteLLM proxy exactly as dockerDevSetup.md
	/// 12.4 instructs an operator to do it: connection dialect=OPENAI_COMPAT with upstream=OLLAMA
	/// asserted, and chatConfig.serviceType LEFT ALONE at its schema default (OPENAI - verified in
	/// chatConfigModel.json).
	///
	/// PageIndexUtil gated req.set("think", false) on that deprecated chatConfig.serviceType, so
	/// following the documentation produced a config for which the gate NEVER FIRED - and on a
	/// chatConfig whose chatOptions turned thinking ON (the realistic case for a hybrid reasoning
	/// model like qwen3), ChatUtil.applyOllamaUpstreamOptions' `think:true` then rode the wire for
	/// the LLM-TOC and summarization calls too. That is the unstructured chain-of-thought prose that
	/// breaks strict-JSON TOC parsing and pollutes stored summaries - the failure that site's own
	/// comment exists to prevent.
	///
	/// SCOPE OF THE DEFECT, measured rather than assumed: with chatOptions.think left at its default
	/// the gate is a no-op either way (see chatConfigWithThinkingOn). So this is the configuration
	/// under which the two axes differ at all.
	///
	/// Note the old gate also failed to fire for a NATIVE dialect=OLLAMA connection whose
	/// serviceType is left at the default, since it never consulted the dialect either. The proxied
	/// shape is asserted here because it is the documented one.
	@Test
	public void caseI1_pageIndexThinkGate_proxiedOllamaUpstream_sendsThinkFalse() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserI");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 I1 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);
		BaseRecord cfg = chatConfigWithThinkingOn(user, "KI72 I1 " + nonce);
		cfg.set("connection", conn);
		/// FIXTURE CHECK, and it is load-bearing: if serviceType were OLLAMA here the OLD gate would
		/// also fire and this case would prove nothing. It must be the schema default.
		assertEquals("fixture check: chatConfig.serviceType must be left at its schema default for"
			+ " this case to distinguish the two axes", LLMServiceEnumType.OPENAI,
			cfg.getEnum("serviceType"));

		JsonNode body = dispatchPageIndexChatAndCapture(user, cfg);

		assertHas(body, "think", "an Ollama upstream must receive an explicit `think` on this path");
		assertFalse("KI-72 residual site: `think` reached the wire as TRUE, so LLM-TOC and"
			+ " summarization ran with thinking ON. PageIndexUtil gated req.set(\"think\", false) on"
			+ " the DEPRECATED chatConfig.serviceType, which defaults to OPENAI - so a proxied Ollama"
			+ " config built by following dockerDevSetup.md 12.4 never fires that gate, and"
			+ " applyOllamaUpstreamOptions' think:true survives onto the wire for calls whose output"
			+ " is parsed as strict JSON or stored as a summary. Gate on chat.getUpstream() == OLLAMA"
			+ " instead - the Chat instance is already in hand at that call site.",
			body.get("think").asBoolean());
		logger.info("[KI-72][WIRE][I1] PASS");
	}

	/// CASE I2 - THE PROHIBITION HALF for the same site, with the SAME thinking-on chatConfig as I1
	/// so the connection's upstream axis is the only variable. dialect=OPENAI_COMPAT with upstream
	/// unset: `think` is an Ollama-only extension and Azure rejects the parameter in ANY form, even
	/// false. As with H2 this would also pass with the fix reverted; it exists to rule out the
	/// forbidden "just always set think" shortcut, not to evidence the fix.
	@Test
	public void caseI2_pageIndexThinkGate_azureCompat_sendsNoThink() throws Exception {
		String base = startCaptureServer();
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord user = getCreateUser("ki72WireUserI");
		assertNotNull("test user is null", user);

		BaseRecord conn = persistConnection(user, "KI72 I2 Conn " + nonce, base,
			ConnectionDialectEnumType.OPENAI_COMPAT, null);
		BaseRecord cfg = chatConfigWithThinkingOn(user, "KI72 I2 " + nonce);
		cfg.set("connection", conn);

		JsonNode body = dispatchPageIndexChatAndCapture(user, cfg);

		assertHasNot(body, "think", "KI-72 PROHIBITION: `think` is Ollama-only and an OPENAI_COMPAT"
			+ " endpoint with no asserted upstream may be Azure, which rejects it in any form");
		assertHas(body, "messages", "the request must still carry its messages");
		assertHas(body, "model", "the request must still carry the model");
		logger.info("[KI-72][WIRE][I2] PASS");
	}
}
