package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// LIVE: AM7 -> LiteLLM proxy (127.0.0.1:4000) -> upstream Ollama (`qwen3:8b` alias) -> Langfuse,
/// exercised through the REAL Chat.chat() path, plus a direct-to-Ollama control.
///
/// WHY THIS CLASS EXISTS ALONGSIDE TestLiteLLMRoundTrip — they cover DIFFERENT resolution paths,
/// and the difference is the whole point:
///
///   TestLiteLLMRoundTrip builds its connection with OlioTestUtil.getCreateConnection, which has NO
///   dialect parameter and never sets one, so the persisted row keeps the model default UNKNOWN. It
///   then sets chatConfig.serviceType = OPENAI_COMPAT. ChatUtil.resolveServiceType
///   (ChatUtil.java:1915-1931) only consults serviceType when dialect is UNKNOWN or the connection is
///   absent — so that class proves the DEPRECATED FALLBACK branch works. Useful, but it is not the
///   path that ships.
///
///   THIS class persists system.connection.dialect explicitly and deliberately leaves
///   chatConfig.serviceType at its schema default. That default is OPENAI (Azure), NOT the value under
///   test — see chatConfigModel.json — which makes the assertions self-enforcing: if dialect resolution
///   ever regressed, serviceType=OPENAI would build the Azure deployment URL
///   (<base>/openai/deployments/qwen3:8b/chat/completions) instead of /v1/chat/completions (or
///   /api/chat), the proxy would not serve it, and the test would fail rather than quietly passing on
///   the fallback. So a green run here proves the AUTHORITATIVE dialect path really drove the wire.
///
/// GATING. Two gates, both must pass or the test reports Skipped, never Failed:
///   1. LITELLM_LIVE=1 (or -Dlitellm.live=1). These tests make real GPU generations; the default suite
///      must never fire one, and never several in parallel. Run them serially.
///   2. Reachability of the LiteLLM liveliness endpoint and the Langfuse public-health endpoint (and,
///      for the control, the direct Ollama server).
///
/// HOSTS. Objects7 JUnit runs on the WINDOWS HOST, so every URL here is a published host port
/// (127.0.0.1:4000 / 127.0.0.1:3001). `http://litellm:4000` resolves only inside the compose network.
///
/// USERS. Every record is created as a dedicated NON-admin test user via BaseTest.getCreateUser.
///
/// The Langfuse HTTP verification helper is PRIVATE TO THIS TEST on purpose: Langfuse is an
/// observability concern of the proxy, and Langfuse HTTP must not leak into production code.
public class TestLiteLLMOllamaProxy extends BaseTest {

	private static final String DEFAULT_LITELLM_MODEL = "qwen3:8b";
	private static final String DEFAULT_MASTER_KEY = "sk-am7-litellm-test";
	private static final String DEFAULT_LF_PK = "pk-lf-am7-test";
	private static final String DEFAULT_LF_SK = "sk-lf-am7-test";
	private static final String DEFAULT_DIRECT_OLLAMA = "http://localhost:11434";

	/// Chat feeds this to CompletableFuture.orTimeout() via system.connection.requestTimeout,
	/// and the chat() latch waits requestTimeout + 5.
	///
	/// MUST STAY ABOVE the LiteLLM per-model `timeout` in src/litellm/config.yaml (240s).
	/// Counter-intuitively, a LOWER value here is the dangerous one: a client disconnect does
	/// NOT release LiteLLM's max_parallel_requests semaphore slot (measured 2026-09-14 --
	/// abandoning a call left the next one undispatched for the abandoned call's full
	/// remaining generation), whereas LiteLLM's own timeout DOES release it promptly. So if
	/// AM7 gives up first, the slot stays held and, at N=1, every later call in this class
	/// queues behind a caller that is already gone. Matching the live connection value (300)
	/// keeps LiteLLM innermost, which is the invariant the config file documents.
	private static final int REQUEST_TIMEOUT_SEC = 300;

	private String litellmServer() { return testProperties.getProperty("test.llm.litellm.server"); }
	private String langfuseHost() { return testProperties.getProperty("test.langfuse.host"); }

	private String litellmModel() { return propOr("test.llm.litellm.model", DEFAULT_LITELLM_MODEL); }

	/// Direct (unproxied) Ollama base URL for the control case.
	///
	/// Deliberately its OWN property rather than reusing test.llm.ollama.server, so the control can be
	/// aimed at a different box from the one under test without touching shared config.
	///
	/// Background: on 2026-09-14 the LAN Spark (192.168.1.42) was transiently wedged — /api/tags
	/// answered in ~13ms (so a reachability probe said "fine") while a 1-token generate hung past 60s.
	/// Stephen bounced Ollama and it has been healthy since; this is NOT a standing property of that
	/// box. The episode is recorded because the failure shape is worth recognising: a healthy-looking
	/// probe plus hung generation. The host Ollama serves the same qwen3:8b either way.
	private String directOllamaServer() { return propOr("test.llm.ollama.direct.server", DEFAULT_DIRECT_OLLAMA); }

	private String masterKey() { return envOr("LITELLM_MASTER_KEY", DEFAULT_MASTER_KEY); }
	private String langfusePk() { return envOr("LANGFUSE_PUBLIC_KEY", DEFAULT_LF_PK); }
	private String langfuseSk() { return envOr("LANGFUSE_SECRET_KEY", DEFAULT_LF_SK); }

	private String propOr(String key, String fallback) {
		String v = testProperties.getProperty(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	/// Opt-in flag shared with TestLiteLLMRoundTrip so one switch governs every live LiteLLM test.
	static boolean liveEnabled() {
		return "1".equals(System.getenv("LITELLM_LIVE")) || "1".equals(System.getProperty("litellm.live"));
	}

	private void assumeLiveFlag() {
		if (!liveEnabled()) {
			logger.warn("[LITELLM-OLLAMA] LITELLM_LIVE not set - SKIPPING live LLM test.");
		}
		assumeTrue("LITELLM_LIVE=1 (or -Dlitellm.live=1) not set; live LLM test skipped", liveEnabled());
	}

	private void assumeStackLive() {
		assumeLiveFlag();
		boolean litellm = httpOk(litellmServer() + "/health/liveliness");
		boolean langfuse = httpOk(langfuseHost() + "/api/public/health");
		if (!litellm || !langfuse) {
			logger.warn("[LITELLM-OLLAMA] stack not live (litellm=" + litellm + " langfuse=" + langfuse
				+ ") - SKIPPING. Bring it up: cd src && docker compose -p am7test -f docker-compose.test.yml "
				+ "--env-file ./volatile/llmproxy.env --profile llmproxy up -d");
		}
		assumeTrue("LiteLLM liveliness not 200 (" + litellmServer() + ")", litellm);
		assumeTrue("Langfuse public health not 200 (" + langfuseHost() + ")", langfuse);
	}

	private void assumeDirectOllamaLive() {
		assumeLiveFlag();
		boolean ollama = httpOk(directOllamaServer() + "/api/tags");
		if (!ollama) {
			logger.warn("[LITELLM-OLLAMA] direct Ollama not reachable at " + directOllamaServer() + " - SKIPPING control.");
		}
		assumeTrue("Direct Ollama /api/tags not 200 (" + directOllamaServer() + ")", ollama);
	}

	private boolean httpOk(String url) {
		try {
			/// Force HTTP/1.1: Java's HttpClient defaults to HTTP/2 with an h2c upgrade that the
			/// Langfuse Next.js/Node server mishandles (closes the socket), even though the endpoint
			/// returns 200 to curl. Same reasoning as TestLiteLLMRoundTrip.httpOk.
			HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5)).build();
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET()
				.timeout(Duration.ofSeconds(8)).build();
			return c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
		} catch (Exception e) {
			logger.warn("[LITELLM-OLLAMA] probe failed " + url + " : " + e.getMessage());
			return false;
		}
	}

	/// Create and PERSIST a system.connection with an EXPLICIT dialect, then read it back and assert
	/// the dialect actually stored.
	///
	/// Not OlioTestUtil.getCreateConnection: that helper takes no dialect argument and never sets one
	/// (OlioTestUtil.java:475-496), so its rows land at the model default UNKNOWN and any test built on
	/// it silently exercises the serviceType fallback instead of the shipped dialect path.
	///
	/// Persistence is REQUIRED, not incidental: Chat.configureChat re-queries the connection BY FK ID
	/// with an explicit projection that includes "dialect" and resolves the transport from THAT row.
	/// A dialect set only on an in-memory record would never be seen. The read-back assertion is the
	/// guard against exactly that class of silent miss.
	private BaseRecord createPersistedConnection(BaseRecord user, String name, String serverUrl,
			String apiKey, ConnectionDialectEnumType dialect) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = IOSystem.getActiveContext().getFactory()
			.newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		assertNotNull("connection factory newInstance returned null", c);
		c.set("serverUrl", serverUrl);
		if (apiKey != null) {
			c.set("apiKey", apiKey);
		}
		c.set("requestTimeout", REQUEST_TIMEOUT_SEC);
		c.set("dialect", dialect);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, c);
		assertNotNull("AccessPoint.create returned null for system.connection '" + name + "'", created);

		/// Read back with dialect explicitly projected - create returns identity fields only.
		long connId = created.get(FieldNames.FIELD_ID);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "serverUrl",
			"requestTimeout", "apiKey", "dialect" });
		cq.setCache(false);
		BaseRecord back = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
		assertNotNull("persisted connection id=" + connId + " could not be read back", back);
		assertEquals("system.connection.dialect did not persist - the connection would fall back to "
			+ "chatConfig.serviceType and the test would prove the wrong path",
			dialect, back.getEnum("dialect"));
		logger.info("[LITELLM-OLLAMA] connection '" + name + "' id=" + connId
			+ " dialect=" + back.getEnum("dialect") + " serverUrl=" + serverUrl);
		return back;
	}

	/// Build the chatConfig IN MEMORY (not persisted). Chat only reads its fields
	/// (connection/model/options); it never reads its id, so persistence buys nothing here and avoids
	/// writing throwaway rows to the shared am7db on every run.
	///
	/// serviceType is deliberately LEFT UNSET so it keeps its schema default (OPENAI). See the class
	/// javadoc: that makes dialect the only thing that can produce a working wire call.
	private BaseRecord createChatConfig(BaseRecord user, String cfgName, BaseRecord conn, String model)
			throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, cfgName);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory()
			.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("connection", conn);
		cfg.set("model", model);
		return cfg;
	}

	/// Poll the Langfuse public traces API for a trace matching a single filter. Returns the first
	/// match or null if none appeared in time. IN-TEST helper only - Langfuse HTTP never belongs in
	/// production code.
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
						logger.info("[LITELLM-OLLAMA][LF] matched " + filterKey + "=" + filterVal
							+ " on attempt " + attempt + " (" + data.size() + " trace(s))");
						return data.get(0);
					}
				} else {
					logger.warn("[LITELLM-OLLAMA][LF] poll HTTP " + r.statusCode() + " body=" + r.body());
				}
			} catch (Exception e) {
				logger.warn("[LITELLM-OLLAMA][LF] poll attempt " + attempt + " failed: " + e.getMessage());
			}
			Thread.sleep(1000);
		}
		return null;
	}

	private static String content(OpenAIResponse resp) {
		if (resp == null || resp.getMessage() == null) {
			return null;
		}
		return resp.getMessage().get(FieldNames.FIELD_CONTENT);
	}

	/// (a) OPENAI_COMPAT dialect -> LiteLLM -> upstream Ollama qwen3:8b, through the real Chat.chat().
	@Test
	public void testA_openAiCompatDialect_throughLiteLLM_returnsCompletion() throws Exception {
		assumeStackLive();
		String nonce = "AM7LP-A-" + UUID.randomUUID().toString().substring(0, 8);

		BaseRecord user = getCreateUser("liteProxyUserA");
		assertNotNull("test user is null", user);

		BaseRecord conn = createPersistedConnection(user, "LiteProxy A Conn " + nonce,
			litellmServer(), masterKey(), ConnectionDialectEnumType.OPENAI_COMPAT);
		BaseRecord cfg = createChatConfig(user, "LiteProxy A " + nonce, conn, litellmModel());

		Chat chat = new Chat(user, cfg, null);

		/// The dialect must have won over the chatConfig.serviceType default (OPENAI). Asserting the
		/// RESOLVED value off the live Chat instance - the same field getServiceUrl and the wire-body
		/// leakage gate read - rather than re-deriving it, so this checks the resolution that actually
		/// happened in configureChat.
		assertEquals("Chat resolved the wrong transport: system.connection.dialect=OPENAI_COMPAT must "
			+ "beat the chatConfig.serviceType schema default",
			LLMServiceEnumType.OPENAI_COMPAT, chat.getServiceType());
		assertEquals("Chat did not take serverUrl from the persisted connection",
			litellmServer(), chat.getServerUrl());
		assertEquals("OPENAI_COMPAT must use the plain /v1/chat/completions route",
			litellmServer() + "/v1/chat/completions", chat.getServiceUrl(chat.newRequest(chat.getModel())));

		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false); /// buffer mode - chat() blocks and returns the OpenAIResponse
		chat.newMessage(req, "/no_think Reply with exactly this token and nothing else: " + nonce,
			Chat.userRole);

		long t0 = System.currentTimeMillis();
		OpenAIResponse resp = chat.chat(req);
		long ms = System.currentTimeMillis() - t0;
		String completion = content(resp);
		logger.info("[LITELLM-OLLAMA][A] " + ms + "ms completion=\""
			+ (completion == null ? "null" : completion.trim()) + "\"");

		assertNotNull("AM7 Chat returned null OpenAIResponse through the LiteLLM proxy "
			+ "(no completion) - see log", resp);
		assertNotNull("OpenAIResponse carried no message", resp.getMessage());
		assertTrue("completion was empty", completion != null && !completion.trim().isEmpty());
		assertTrue("completion did not echo the nonce (" + nonce + "); got: " + completion,
			completion.contains(nonce));
		logger.info("[LITELLM-OLLAMA][A] PASS - OPENAI_COMPAT dialect drove a real proxied completion.");
	}

	/// (b) The proxied chat must land a Langfuse trace, correlated by the x-langfuse-* headers
	/// Chat.buildTracingHeaders derives from `user` / `session_id` on the request.
	@Test
	public void testB_proxiedChat_landsLangfuseTrace() throws Exception {
		assumeStackLive();
		String nonce = "AM7LP-B-" + UUID.randomUUID().toString().substring(0, 8);
		String userId = "am7lp-u-" + nonce;
		String sessionId = "am7lp-s-" + nonce;

		BaseRecord user = getCreateUser("liteProxyUserB");
		assertNotNull("test user is null", user);

		BaseRecord conn = createPersistedConnection(user, "LiteProxy B Conn " + nonce,
			litellmServer(), masterKey(), ConnectionDialectEnumType.OPENAI_COMPAT);
		BaseRecord cfg = createChatConfig(user, "LiteProxy B " + nonce, conn, litellmModel());

		Chat chat = new Chat(user, cfg, null);
		assertEquals("dialect resolution regressed", LLMServiceEnumType.OPENAI_COMPAT, chat.getServiceType());

		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		/// Chat.buildTracingHeaders turns these into x-langfuse-user-id / x-langfuse-session-id for the
		/// OPENAI_COMPAT dialect only; session_id is additionally pruned from the wire BODY.
		req.setValue("user", userId);
		req.setValue("session_id", sessionId);
		chat.newMessage(req, "/no_think Reply with exactly this token and nothing else: " + nonce,
			Chat.userRole);

		OpenAIResponse resp = chat.chat(req);
		String completion = content(resp);
		logger.info("[LITELLM-OLLAMA][B] completion=\""
			+ (completion == null ? "null" : completion.trim()) + "\"");
		assertNotNull("AM7 Chat returned null OpenAIResponse - cannot verify a trace for a call that "
			+ "never completed", resp);
		assertTrue("completion was empty", completion != null && !completion.trim().isEmpty());

		JsonNode trace = pollLangfuseTrace("userId", userId, 60);
		assertNotNull("No Langfuse trace landed for userId=" + userId + " within 60s "
			+ "(AM7 -> LiteLLM -> Langfuse callback failed)", trace);
		String lfUser = trace.get("userId") == null ? null : trace.get("userId").asText();
		String lfSession = trace.get("sessionId") == null ? null : trace.get("sessionId").asText();
		logger.info("[LITELLM-OLLAMA][B] trace id=" + trace.get("id")
			+ " userId=" + lfUser + " sessionId=" + lfSession);
		assertEquals("Langfuse trace.userId != request `user`", userId, lfUser);
		assertEquals("Langfuse trace.sessionId != request `session_id` - the x-langfuse-session-id "
			+ "header correlation is broken", sessionId, lfSession);
		logger.info("[LITELLM-OLLAMA][B] PASS - proxied chat is traced and correlated in Langfuse.");
	}

	/// (d) CONTROL: the pre-existing DIRECT (unproxied) OLLAMA path still works, same prompt.
	/// If (a) fails and this fails too, the problem is the model/host, not the proxy path.
	@Test
	public void testD_control_ollamaDialect_directServer_returnsCompletion() throws Exception {
		assumeDirectOllamaLive();
		String nonce = "AM7LP-D-" + UUID.randomUUID().toString().substring(0, 8);

		BaseRecord user = getCreateUser("liteProxyUserD");
		assertNotNull("test user is null", user);

		BaseRecord conn = createPersistedConnection(user, "LiteProxy D Conn " + nonce,
			directOllamaServer(), null, ConnectionDialectEnumType.OLLAMA);
		BaseRecord cfg = createChatConfig(user, "LiteProxy D " + nonce, conn,
			propOr("test.llm.ollama.model", DEFAULT_LITELLM_MODEL));

		Chat chat = new Chat(user, cfg, null);
		assertEquals("Chat resolved the wrong transport: system.connection.dialect=OLLAMA must beat "
			+ "the chatConfig.serviceType schema default",
			LLMServiceEnumType.OLLAMA, chat.getServiceType());
		assertEquals("OLLAMA must use the native /api/chat route",
			directOllamaServer() + "/api/chat", chat.getServiceUrl(chat.newRequest(chat.getModel())));

		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		/// `think` survives the wire-body prune ONLY when explicitly populated on an OLLAMA request
		/// (Chat.chatInternal keepThink). qwen3 is a hybrid reasoning model that thinks by default;
		/// forcing it off keeps the control cheap and its content non-empty.
		req.set("think", false);
		chat.newMessage(req, "Reply with exactly this token and nothing else: " + nonce, Chat.userRole);

		long t0 = System.currentTimeMillis();
		OpenAIResponse resp = chat.chat(req);
		long ms = System.currentTimeMillis() - t0;
		String completion = content(resp);
		logger.info("[LITELLM-OLLAMA][D] " + ms + "ms completion=\""
			+ (completion == null ? "null" : completion.trim()) + "\"");

		assertNotNull("DIRECT Ollama control returned null OpenAIResponse from "
			+ directOllamaServer() + " - the unproxied path is broken (or that server is wedged: "
			+ "repoint test.llm.ollama.direct.server)", resp);
		assertTrue("control completion was empty", completion != null && !completion.trim().isEmpty());
		assertTrue("control completion did not echo the nonce (" + nonce + "); got: " + completion,
			completion.contains(nonce));
		logger.info("[LITELLM-OLLAMA][D] PASS - direct OLLAMA dialect path still works.");
	}
}
