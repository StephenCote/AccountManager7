package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ClientUtil;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// LIVE Tier B round-trip: AM7 -> LiteLLM (http://127.0.0.1:4000) -> upstream model -> Langfuse.
///
/// UPSTREAM IS CONFIGURABLE (and no longer Azure by default). The model alias comes from
/// test.llm.litellm.model and defaults to `qwen3:8b`, which LiteLLM proxies to Ollama. It was
/// hardcoded to the Azure alias `gpt-5.6-terra`, which made every test here unrunnable without Azure
/// credentials. Set the property back to `gpt-5.6-terra` to restore the Azure path when real
/// AZURE_API_KEY/AZURE_API_BASE values are supplied; read the per-test notes below first, because
/// two of them are upstream-sensitive.
///
/// Double-gated: (1) LITELLM_LIVE=1 / -Dlitellm.live=1, because these are real GPU generations and the
/// default suite must never fire one; (2) assumeStackLive() probes the LiteLLM liveliness endpoint and
/// the Langfuse public-health endpoint. Either gate failing reports Skipped, never Failed. Run
/// serially. All records are created as a dedicated NON-admin test user (BaseTest.getCreateUser),
/// never the admin user. No genuine upstream key appears here - only the LiteLLM master key (env
/// LITELLM_MASTER_KEY, falling back to the committed non-secret test placeholder), which is the Bearer
/// token AM7 already sends. Langfuse is verified with the committed non-secret pk/sk. The
/// Langfuse-verification helper lives IN THIS TEST, never in production code.
///
/// RESOLUTION PATH COVERED HERE: createOpenAICompatConfig sets chatConfig.serviceType and builds its
/// connection through OlioTestUtil.getCreateConnection, which takes no dialect argument and never sets
/// one - so the connection persists at dialect=UNKNOWN and ChatUtil.resolveServiceType falls through to
/// serviceType. This class therefore exercises the DEPRECATED FALLBACK branch. The authoritative
/// system.connection.dialect path is covered by TestLiteLLMOllamaProxy. Keep both.
///
/// Three tests, all GREEN as of 2026-09-14:
///   A (round-trip): drive a real chat through AM7's own Chat path with `user` set (no session_id),
///      assert HTTP 200 + a real completion echoing a unique nonce, then poll the Langfuse public API
///      and assert a trace landed for this run's unique userId.
///   C (header mechanism): send the request through AM7's ClientUtil transport (the exact 4-arg method
///      Chat.chatInternal uses) with the session grouping carried as the x-langfuse-session-id HEADER
///      and NO session_id in the body. Assert HTTP 200 + nonce, and that the Langfuse trace's sessionId
///      equals the header value. This proves the CORRECT correlation mechanism end-to-end.
///   B (was RED, now GREEN - see its own javadoc): AM7's Chat with `session_id` SET on the request.
///
/// GPU COST: three chat completions plus three Langfuse polls; ~45s total against a local qwen3:8b.
public class TestLiteLLMRoundTrip extends BaseTest {

	/// Proxied model ALIAS (as declared in src/litellm/config.yaml `model_list`), resolved from
	/// test.llm.litellm.model. This was HARDCODED to "gpt-5.6-terra", which made the whole class
	/// unrunnable without Azure credentials (AZURE_API_KEY/AZURE_API_BASE): every request came back
	/// as an upstream auth/connect error, not a completion. The Azure alias is NOT removed -- it is
	/// still in the proxy config and setting test.llm.litellm.model=gpt-5.6-terra restores that path
	/// verbatim once real Azure credentials are supplied. The default is the Ollama-backed alias that
	/// actually resolves in this stack.
	private static final String DEFAULT_LITELLM_MODEL = "qwen3:8b";
	private static final String DEFAULT_MASTER_KEY = "sk-am7-litellm-test";
	private static final String DEFAULT_LF_PK = "pk-lf-am7-test";
	private static final String DEFAULT_LF_SK = "sk-lf-am7-test";

	private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

	private String litellmServer() { return testProperties.getProperty("test.llm.litellm.server"); }
	private String litellmModel() {
		String v = testProperties.getProperty("test.llm.litellm.model");
		return (v != null && !v.isBlank()) ? v.trim() : DEFAULT_LITELLM_MODEL;
	}
	private String langfuseHost() { return testProperties.getProperty("test.langfuse.host"); }
	private String masterKey() { return envOr("LITELLM_MASTER_KEY", DEFAULT_MASTER_KEY); }
	private String langfusePk() { return envOr("LANGFUSE_PUBLIC_KEY", DEFAULT_LF_PK); }
	private String langfuseSk() { return envOr("LANGFUSE_SECRET_KEY", DEFAULT_LF_SK); }

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	/// Opt-in flag for the LIVE LLM tests in this class. Reachability alone is no longer a sufficient
	/// gate: since test.llm.litellm.model now defaults to the Ollama-backed alias, a reachable proxy
	/// means a real GPU generation, and the default suite must never fire one (and never in parallel).
	/// Set LITELLM_LIVE=1 (or -Dlitellm.live=1) to run. Mirrors TestFlux2Composite#liveEnabled.
	static boolean liveEnabled() {
		return "1".equals(System.getenv("LITELLM_LIVE")) || "1".equals(System.getProperty("litellm.live"));
	}

	/// Skip (not fail) the whole test when the opt-in flag is unset or the LiteLLM/Langfuse stack is
	/// not up. Keeps the default suite from ever reaching an LLM.
	private void assumeStackLive() {
		if (!liveEnabled()) {
			logger.warn("[LITELLM-RT] LITELLM_LIVE not set - SKIPPING live LLM test.");
		}
		assumeTrue("LITELLM_LIVE=1 (or -Dlitellm.live=1) not set; live LLM test skipped", liveEnabled());
		boolean litellm = httpOk(litellmServer() + "/health/liveliness");
		boolean langfuse = httpOk(langfuseHost() + "/api/public/health");
		if (!litellm || !langfuse) {
			logger.warn("[LITELLM-RT] stack not live (litellm=" + litellm + " langfuse=" + langfuse
				+ ") — SKIPPING. Bring it up: cd src && docker compose -p am7test -f docker-compose.test.yml "
				+ "--env-file ./volatile/llmproxy.env --profile llmproxy up -d");
		}
		assumeTrue("LiteLLM liveliness not 200 (" + litellmServer() + ")", litellm);
		assumeTrue("Langfuse public health not 200 (" + langfuseHost() + ")", langfuse);
	}

	private boolean httpOk(String url) {
		try {
			/// Force HTTP/1.1: Java's HttpClient defaults to HTTP/2 with an h2c upgrade that the Langfuse
			/// Next.js/Node server mishandles (closes the socket → "header parser received no bytes"),
			/// even though the endpoint returns 200 to curl. LiteLLM (uvicorn) tolerates the upgrade.
			HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5)).build();
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET()
				.timeout(Duration.ofSeconds(8)).build();
			return c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
		} catch (Exception e) {
			logger.warn("[LITELLM-RT] probe failed " + url + " : " + e.getMessage());
			return false;
		}
	}

	private String extractContent(String rawBody) {
		StringBuilder sb = new StringBuilder();
		Matcher m = CONTENT.matcher(rawBody);
		while (m.find()) {
			sb.append(m.group(1));
		}
		return sb.toString();
	}

	/// Create an OPENAI_COMPAT chatConfig pointed at LiteLLM. The connection (which carries the
	/// serverUrl + encrypted apiKey) IS persisted through AccessPoint as the test user, because
	/// Chat.configureChat re-queries it by FK id to decrypt the apiKey. The chatConfig itself is built
	/// IN-MEMORY and NOT persisted: Chat only reads its fields (serviceType/model/connection), never its
	/// id, so persistence is unnecessary. A unique name per run guarantees a fresh connection apiKey.
	///
	/// HISTORICAL NOTE (resolved 2026-09-14): this javadoc used to warn that persisting the chatConfig
	/// would abort with `value too long for type character varying(10)`, because the live
	/// A7_olio_llm_chatConfig_0_1."serviceType" column was a stale varchar(10) too narrow for the
	/// 13-char 'OPENAI_COMPAT'. That column has since been widened non-destructively on am7db
	/// (ALTER ... TYPE varchar(16)); am72db was already 16. The in-memory approach is kept because it
	/// is simpler and sufficient, NOT because the schema still blocks it.
	private BaseRecord createOpenAICompatConfig(BaseRecord user, String cfgName) throws Exception {
		// 300s, NOT a smaller "safety" value: it must stay ABOVE the LiteLLM per-model
		// timeout (240s, src/litellm/config.yaml). A client disconnect does not release
		// LiteLLM's semaphore slot; only LiteLLM's own timeout does. Giving up first would
		// leave the slot held and wedge the N=1 queue for the rest of that generation.
		BaseRecord conn = OlioTestUtil.getCreateConnection(user, cfgName + " Connection",
			litellmServer(), masterKey(), 300);
		assertNotNull("connection create returned null", conn);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, cfgName);
		BaseRecord cfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("serviceType", LLMServiceEnumType.OPENAI_COMPAT);
		cfg.set("connection", conn);
		cfg.set("model", litellmModel());
		return cfg;
	}

	/// Poll the Langfuse public traces API for a trace matching a single filter (userId or sessionId).
	/// Returns the first matching trace JsonNode, or null if none appeared within timeoutSec. Basic-auth
	/// with the committed non-secret pk/sk. IN-TEST helper only.
	private JsonNode pollLangfuseTrace(String filterKey, String filterVal, int timeoutSec) throws Exception {
		HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(8)).build();
		String basic = Base64.getEncoder().encodeToString(
			(langfusePk() + ":" + langfuseSk()).getBytes(StandardCharsets.UTF_8));
		String url = langfuseHost() + "/api/public/traces?" + filterKey + "="
			+ URLEncoder.encode(filterVal, StandardCharsets.UTF_8.name());
		ObjectMapper om = new ObjectMapper();
		long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
		int attempt = 0;
		while (System.currentTimeMillis() < deadline) {
			attempt++;
			try {
				HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
					.header("Authorization", "Basic " + basic).GET()
					.timeout(Duration.ofSeconds(15)).build();
				HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
				if (r.statusCode() == 200) {
					JsonNode data = om.readTree(r.body()).get("data");
					if (data != null && data.isArray() && data.size() > 0) {
						logger.info("[LITELLM-RT][LF] matched " + filterKey + "=" + filterVal
							+ " on attempt " + attempt + " (" + data.size() + " trace(s))");
						return data.get(0);
					}
				} else {
					logger.warn("[LITELLM-RT][LF] poll HTTP " + r.statusCode() + " body=" + r.body());
				}
			} catch (Exception e) {
				logger.warn("[LITELLM-RT][LF] poll attempt " + attempt + " failed: " + e.getMessage());
			}
			Thread.sleep(1000);
		}
		return null;
	}

	private String traceText(JsonNode trace) {
		if (trace == null) return "";
		StringBuilder sb = new StringBuilder();
		if (trace.get("input") != null) sb.append(trace.get("input").toString());
		if (trace.get("output") != null) sb.append(trace.get("output").toString());
		return sb.toString();
	}

	/// A — GREEN round-trip through AM7's own Chat path with `user` set (no session_id).
	@Test
	public void testA_roundTrip_userTracing_reachesLangfuse() throws Exception {
		assumeStackLive();
		String nonce = "AM7RT-A-" + UUID.randomUUID().toString().substring(0, 8);
		String userId = "am7rt-u-" + nonce;

		BaseRecord user = getCreateUser("tierBRtUserA");
		assertNotNull("test user is null", user);
		BaseRecord cfg = createOpenAICompatConfig(user, "TierB RT A " + nonce);

		Chat chat = new Chat(user, cfg, null);
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false); /// buffer mode — chat() blocks and returns the OpenAIResponse
		req.setValue("user", userId); /// LiteLLM/Langfuse tracing user; also emitted as x-langfuse-user-id
		chat.newMessage(req, "Reply with exactly this token and nothing else: " + nonce, Chat.userRole);

		OpenAIResponse resp = chat.chat(req);
		assertNotNull("AM7 Chat returned null OpenAIResponse (no HTTP 200 completion) — see log", resp);
		assertNotNull("OpenAIResponse carried no message", resp.getMessage());
		String completion = resp.getMessage().get(FieldNames.FIELD_CONTENT);
		logger.info("[LITELLM-RT][A] completion=\"" + (completion == null ? "null" : completion.trim()) + "\"");
		assertTrue("completion empty", completion != null && !completion.trim().isEmpty());
		assertTrue("completion did not echo the nonce (" + nonce + "); got: " + completion,
			completion.contains(nonce));

		JsonNode trace = pollLangfuseTrace("userId", userId, 40);
		assertNotNull("No Langfuse trace landed for userId=" + userId + " within 40s "
			+ "(AM7->LiteLLM->Azure->Langfuse callback failed)", trace);
		logger.info("[LITELLM-RT][A] Langfuse trace id=" + trace.get("id") + " userId=" + trace.get("userId"));
		logger.info("[LITELLM-RT][A] Langfuse trace nonce-in-io=" + traceText(trace).contains(nonce));
		logger.info("[LITELLM-RT][A] PASS — round-trip verified end to end; Langfuse trace present for this run.");
	}

	/// C — GREEN correlation mechanism: session grouping via the x-langfuse-session-id HEADER through
	/// AM7's ClientUtil 4-arg transport, NO session_id in the body.
	@Test
	public void testC_headerSessionMechanism_reachesLangfuse() throws Exception {
		assumeStackLive();
		String nonce = "AM7RT-C-" + UUID.randomUUID().toString().substring(0, 8);
		String sessionId = "am7rt-s-" + nonce;

		String url = litellmServer() + "/v1/chat/completions";
		String body = "{\"model\":\"" + litellmModel() + "\",\"stream\":true,"
			+ "\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly this token and nothing else: "
			+ nonce + "\"}]}";
		Map<String, String> extraHeaders = new HashMap<>();
		extraHeaders.put("x-langfuse-session-id", sessionId);

		int status = -1;
		String completion = "";
		StringBuilder raw = new StringBuilder();
		CompletableFuture<HttpResponse<Stream<String>>> future =
			ClientUtil.postToRecordAndStream(url, masterKey(), body, extraHeaders);
		HttpResponse<Stream<String>> httpResp = future.get(90, TimeUnit.SECONDS);
		status = httpResp.statusCode();
		Iterator<String> it = httpResp.body().iterator();
		while (it.hasNext()) {
			String line = it.next();
			raw.append(line).append("\n");
			if (line.startsWith("data: ")) {
				String data = line.substring(6).trim();
				if (!"[DONE]".equals(data)) completion += extractContent(data);
			}
		}
		if (status == 200 && completion.isEmpty()) completion = extractContent(raw.toString());
		logger.info("[LITELLM-RT][C] HTTP " + status + " completion=\"" + completion.trim() + "\"");
		if (status != 200) logger.warn("[LITELLM-RT][C] non-200 body: " + raw);

		assertTrue("LiteLLM did not return HTTP 200 with header-only session id. Got HTTP " + status,
			status == 200);
		assertTrue("completion did not echo nonce (" + nonce + "); got: " + completion,
			completion.contains(nonce));

		JsonNode trace = pollLangfuseTrace("sessionId", sessionId, 40);
		assertNotNull("No Langfuse trace landed for sessionId=" + sessionId + " within 40s", trace);
		String lfSession = trace.get("sessionId") == null ? null : trace.get("sessionId").asText();
		logger.info("[LITELLM-RT][C] Langfuse trace id=" + trace.get("id") + " sessionId=" + lfSession
			+ " nonce-in-io=" + traceText(trace).contains(nonce));
		assertTrue("Langfuse trace.sessionId (" + lfSession + ") != header value (" + sessionId + ")",
			sessionId.equals(lfSession));
		logger.info("[LITELLM-RT][C] PASS — x-langfuse-session-id HEADER correctly correlates to "
			+ "trace.sessionId end to end through AM7's ClientUtil transport.");
	}

	/// B - GREEN as of 2026-09-14: AM7's Chat with `session_id` SET returns a completion AND correlates
	/// to a Langfuse trace by sessionId.
	///
	/// This javadoc previously declared the test "intentionally red until the gate is fixed", claiming
	/// Chat.chatInternal's OPENAI_COMPAT branch kept `session_id` in the request BODY, which Azure
	/// rejected with HTTP 400 "Unknown parameter: 'session_id'". That description is STALE - the gate
	/// was fixed. Chat.chatInternal now does `ignoreFields.add("session_id")` UNCONDITIONALLY, for every
	/// dialect (Chat.java:4023), pruning it from the wire body copy while leaving it readable on `req`
	/// so buildTracingHeaders (Chat.java:4461-4475) can emit it as x-langfuse-session-id.
	///
	/// MEASURED on this run: completion "AM7RT-B-364a2874" came back, and a Langfuse trace with
	/// sessionId=am7rt-s-AM7RT-B-364a2874 was matched on poll attempt 8. The fail() branch below is
	/// retained as a live regression trap, not as a known-failure marker.
	///
	/// KNOWN LIMIT OF THIS TEST ON AN OLLAMA UPSTREAM - state it rather than overclaim. With the default
	/// test.llm.litellm.model (qwen3:8b via Ollama), the in-test diagnostic body-probe now returns HTTP
	/// 200, not the 400 the old javadoc describes: LiteLLM's `drop_params: true` silently discards a
	/// body-level session_id before it reaches Ollama. So on THIS upstream the test confirms the DESIRED
	/// behavior but would also pass if the body prune were reverted - it no longer discriminates that
	/// specific defect. The discriminating upstream is Azure (set test.llm.litellm.model=gpt-5.6-terra
	/// with real credentials), which 400s on the unknown parameter. Test C is the unconditional proof
	/// that the header mechanism - not the body - is what produces trace.sessionId.
	@Test
	public void testB_bodySessionId_defect_liveCorrelation() throws Exception {
		assumeStackLive();
		String nonce = "AM7RT-B-" + UUID.randomUUID().toString().substring(0, 8);
		String sessionId = "am7rt-s-" + nonce;

		BaseRecord user = getCreateUser("tierBRtUserB");
		assertNotNull("test user is null", user);

		/// --- CONTROL: same config/path WITHOUT session_id must succeed (isolates session_id as cause) ---
		BaseRecord cfgCtl = createOpenAICompatConfig(user, "TierB RT B ctl " + nonce);
		Chat chatCtl = new Chat(user, cfgCtl, null);
		OpenAIRequest reqCtl = chatCtl.newRequest(chatCtl.getModel());
		reqCtl.setStream(false);
		chatCtl.newMessage(reqCtl, "Reply with exactly this token and nothing else: " + nonce + "-ctl",
			Chat.userRole);
		OpenAIResponse respCtl = chatCtl.chat(reqCtl);
		String controlCompletion = (respCtl != null && respCtl.getMessage() != null)
			? respCtl.getMessage().get(FieldNames.FIELD_CONTENT) : null;
		logger.info("[LITELLM-RT][B][control no-session_id] completion=\""
			+ (controlCompletion == null ? "null" : controlCompletion.trim()) + "\"");

		/// --- DIAGNOSTIC: direct body-probe with session_id in the body captures the exact upstream error ---
		int probeStatus = -1;
		String probeBody = "";
		try {
			HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(10)).build();
			String pb = "{\"model\":\"" + litellmModel() + "\",\"session_id\":\"" + sessionId + "\","
				+ "\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word: pong\"}]}";
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(litellmServer() + "/v1/chat/completions"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + masterKey())
				.POST(HttpRequest.BodyPublishers.ofString(pb))
				.timeout(Duration.ofSeconds(60)).build();
			HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
			probeStatus = r.statusCode();
			probeBody = r.body();
			logger.info("[LITELLM-RT][B][diagnostic body-session_id] HTTP " + probeStatus + " body=" + probeBody);
		} catch (Exception e) {
			logger.warn("[LITELLM-RT][B][diagnostic] probe failed: " + e.getMessage());
		}

		/// --- ACTUAL PRODUCTION PATH: AM7 Chat with session_id SET on the request ---
		BaseRecord cfg = createOpenAICompatConfig(user, "TierB RT B " + nonce);
		Chat chat = new Chat(user, cfg, null);
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		/// Set on the request object. Chat.chatInternal now prunes `session_id` from the WIRE body
		/// unconditionally (Chat.java:4023) and re-emits it as the x-langfuse-session-id header
		/// (buildTracingHeaders, Chat.java:4461-4475). The comment here used to read "kept in BODY
		/// ... the bug", describing the pre-fix state; that defect is fixed.
		req.setValue("session_id", sessionId);
		req.setValue("user", "am7rt-u-" + nonce);
		chat.newMessage(req, "Reply with exactly this token and nothing else: " + nonce, Chat.userRole);
		OpenAIResponse resp = chat.chat(req);
		String completion = (resp != null && resp.getMessage() != null)
			? resp.getMessage().get(FieldNames.FIELD_CONTENT) : null;
		logger.info("[LITELLM-RT][B][production session_id set] completion=\""
			+ (completion == null ? "null" : completion.trim()) + "\"");

		/// DESIRED behavior: session_id set => HTTP 200 + completion echoing the nonce, and a Langfuse
		/// trace whose sessionId equals the value. This now PASSES (the body-session_id defect is
		/// fixed); the branch below is retained as a regression guard, not an expected outcome.
		/// Caveat, stated in the class javadoc too: on an OLLAMA-backed upstream LiteLLM's
		/// `drop_params: true` would discard a body-level session_id anyway, so this case no longer
		/// DISCRIMINATES the body-vs-header defect here — Azure is the discriminating upstream, and
		/// testC is the unconditional proof that the HEADER produces trace.sessionId.
		if (completion == null || !completion.contains(nonce)) {
			logger.error("[LITELLM-RT][B][FINDING] DEFECT CONFIRMED — AM7 Chat sends `session_id` in the "
				+ "request BODY for the OPENAI_COMPAT dialect (Chat.chatInternal leakage gate does NOT prune "
				+ "user/session_id/metadata for OPENAI_COMPAT). LiteLLM forwards it to Azure, which rejects "
				+ "with HTTP 400 \"Unknown parameter: 'session_id'\" — so the completion comes back null. The "
				+ "CONTROL run without session_id returned a real completion, and the header-only mechanism "
				+ "(test C) works. Correct fix: send session_id ONLY as the x-langfuse-session-id header "
				+ "(Chat.buildTracingHeaders already builds it) and stop leaking it into the body.");
			fail("DEFECT: AM7 Chat with session_id set returned "
				+ (completion == null ? "a null completion" : "\"" + completion + "\"")
				+ " (expected HTTP 200 echoing nonce " + nonce + "). "
				+ "Control (no session_id) => " + (controlCompletion == null ? "null" : "\"" + controlCompletion.trim() + "\"")
				+ "; direct body-session_id probe => HTTP " + probeStatus + " body=" + probeBody
				+ ". Root cause in [LITELLM-RT][B][FINDING] above. This test stays RED until session_id is "
				+ "carried ONLY as the x-langfuse-session-id header.");
		}

		/// If we get here the defect is fixed — assert the full desired correlation.
		JsonNode trace = pollLangfuseTrace("sessionId", sessionId, 40);
		assertNotNull("session_id set but no Langfuse trace for sessionId=" + sessionId, trace);
		String lfSession = trace.get("sessionId") == null ? null : trace.get("sessionId").asText();
		assertTrue("Langfuse trace.sessionId (" + lfSession + ") != request session_id (" + sessionId + ")",
			sessionId.equals(lfSession));
		logger.info("[LITELLM-RT][B] session_id correlation now works end to end (defect fixed).");
	}
}
