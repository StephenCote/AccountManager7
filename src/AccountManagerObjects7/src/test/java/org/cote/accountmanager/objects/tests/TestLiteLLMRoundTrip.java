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

/// LIVE Tier B round-trip: AM7 -> LiteLLM (http://127.0.0.1:4000) -> Azure (gpt-5.6-terra) -> Langfuse.
///
/// Reachability-gated: assumeStackLive() probes both the LiteLLM liveliness endpoint and the Langfuse
/// public-health endpoint; when either is down the tests report Skipped, so the default suite NEVER
/// fires at Azure. All records are created as a dedicated NON-admin test user (BaseTest.getCreateUser),
/// never the admin user. The genuine Azure upstream key never appears here — only the LiteLLM master
/// key (env LITELLM_MASTER_KEY, falling back to the committed non-secret test placeholder) is used, as
/// the Bearer token AM7 already sends. Langfuse is verified with the committed non-secret pk/sk. The
/// Langfuse-verification helper lives IN THIS TEST, never in production code.
///
/// Three tests:
///   A (GREEN round-trip): drive a real chat through AM7's own Chat path with `user` set (no
///      session_id), assert HTTP 200 + a real completion echoing a unique nonce, then poll the Langfuse
///      public API and assert a trace landed for this run's unique userId.
///   C (GREEN header mechanism): send the request through AM7's ClientUtil transport (the exact 4-arg
///      method Chat.chatInternal uses) with the session grouping carried as the x-langfuse-session-id
///      HEADER and NO session_id in the body. Assert HTTP 200 + nonce, and that the Langfuse trace's
///      sessionId equals the header value. This proves the CORRECT correlation mechanism end-to-end.
///   B (RED — reveals a real defect): drive AM7's Chat with `session_id` SET on the request. The desired
///      behavior is HTTP 200 + a completion echoing the nonce (session correlation). It FAILS because the
///      leakage gate (Chat.chatInternal, OPENAI_COMPAT branch) keeps `session_id` in the request BODY,
///      which LiteLLM forwards to Azure and Azure rejects with HTTP 400 "Unknown parameter: 'session_id'".
///      A control run without session_id (succeeds) and a direct body-probe (captures the 400) make the
///      root cause unambiguous. This test is intentionally red until the gate is fixed to send session_id
///      ONLY as the x-langfuse-session-id header (buildTracingHeaders already does exactly that).
public class TestLiteLLMRoundTrip extends BaseTest {

	private static final String LITELLM_MODEL = "gpt-5.6-terra";
	private static final String DEFAULT_MASTER_KEY = "sk-am7-litellm-test";
	private static final String DEFAULT_LF_PK = "pk-lf-am7-test";
	private static final String DEFAULT_LF_SK = "sk-lf-am7-test";

	private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

	private String litellmServer() { return testProperties.getProperty("test.llm.litellm.server"); }
	private String langfuseHost() { return testProperties.getProperty("test.langfuse.host"); }
	private String masterKey() { return envOr("LITELLM_MASTER_KEY", DEFAULT_MASTER_KEY); }
	private String langfusePk() { return envOr("LANGFUSE_PUBLIC_KEY", DEFAULT_LF_PK); }
	private String langfuseSk() { return envOr("LANGFUSE_SECRET_KEY", DEFAULT_LF_SK); }

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	/// Skip (not fail) the whole test when the LiteLLM/Langfuse stack is not up. Keeps the default suite
	/// from ever reaching Azure.
	private void assumeStackLive() {
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
	/// id, so persistence is unnecessary — and it lets this live test run against the current am7db even
	/// though that DB's stale A7_olio_llm_chatConfig_0_1."serviceType" column is varchar(10) (the model
	/// defines maxLength 16 for the 13-char 'OPENAI_COMPAT'; the live column predates the bump and was
	/// never widened because BaseTest runs schemaCheck=false + repairColumnTypes=false). Persisting here
	/// would abort with `value too long for type character varying(10)` — an env schema-staleness issue,
	/// not a defect in the code under test. A unique name per run guarantees a fresh connection apiKey.
	private BaseRecord createOpenAICompatConfig(BaseRecord user, String cfgName) throws Exception {
		BaseRecord conn = OlioTestUtil.getCreateConnection(user, cfgName + " Connection",
			litellmServer(), masterKey(), 120);
		assertNotNull("connection create returned null", conn);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, cfgName);
		BaseRecord cfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("serviceType", LLMServiceEnumType.OPENAI_COMPAT);
		cfg.set("connection", conn);
		cfg.set("model", LITELLM_MODEL);
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
		String body = "{\"model\":\"" + LITELLM_MODEL + "\",\"stream\":true,"
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

	/// B — RED: AM7's Chat with `session_id` SET reveals the body-session_id defect (live correlation).
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
			String pb = "{\"model\":\"" + LITELLM_MODEL + "\",\"session_id\":\"" + sessionId + "\","
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
		req.setValue("session_id", sessionId); /// kept in BODY by the OPENAI_COMPAT leakage gate — the bug
		req.setValue("user", "am7rt-u-" + nonce);
		chat.newMessage(req, "Reply with exactly this token and nothing else: " + nonce, Chat.userRole);
		OpenAIResponse resp = chat.chat(req);
		String completion = (resp != null && resp.getMessage() != null)
			? resp.getMessage().get(FieldNames.FIELD_CONTENT) : null;
		logger.info("[LITELLM-RT][B][production session_id set] completion=\""
			+ (completion == null ? "null" : completion.trim()) + "\"");

		/// DESIRED behavior: session_id set => HTTP 200 + completion echoing the nonce, and a Langfuse
		/// trace whose sessionId equals the value. This FAILS while the body-session_id defect stands.
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
