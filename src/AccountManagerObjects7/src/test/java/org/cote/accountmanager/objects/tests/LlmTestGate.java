package org.cote.accountmanager.objects.tests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resolves, ONCE per JVM, which LLM the Objects7 tests should talk to and through what, and writes
 * the answer back into the test {@link Properties} so every existing consumer of
 * {@code test.llm.ollama.server} / {@code test.llm.ollama.model} sees the resolved values without
 * being edited. Same shape as {@link SdTestGate#resolveInstalledCheckpoints(Properties)}, which
 * {@code BaseTest.setup()} calls right before this.
 *
 * <p>Stephen, 2026-09-29: "update test instructions to first try the local ollama container before
 * trying Azure or the remote ollama on .42 ... plan to use LiteLLM w/ LLM tests so that it's easier
 * for you to debug issues."
 *
 * <h3>Route</h3>
 * <ul>
 * <li>{@code litellm} - {@code GET {test.llm.litellm.server}/health/liveliness} answered 200. Every
 *     chat call then goes through the proxy (OPENAI_COMPAT dialect, master key, Langfuse trace).</li>
 * <li>{@code direct} - otherwise. Native Ollama {@code /api/chat}.</li>
 * </ul>
 *
 * <h3>Tier</h3>
 * <ol>
 * <li>{@code local} - {@code GET {test.llm.ollama.local.server}/api/tags} lists BOTH
 *     {@code test.llm.ollama.model} and {@code test.llm.pb.model} (exact name match; a trailing
 *     {@code :latest} on either side is tolerated).</li>
 * <li>{@code azure} - route is {@code litellm}, the existing {@code LITELLM_LIVE=1} /
 *     {@code -Dlitellm.live=1} opt-in is set (reused from {@link TestLiteLLMOllamaProxy#liveEnabled()};
 *     there is deliberately no second gate), and
 *     {@code GET {litellm}/health?model={test.llm.litellm.azure.model}} with the master key reports the
 *     alias healthy. That probe is one small PAID Azure call per JVM, so its URL and result are logged
 *     at INFO before anything acts on it. Azure is LiteLLM-only: a direct Azure route would need the
 *     {@code test.llm.openai.*} deployment/version scheme AND has no picture-book model counterpart, so
 *     it is not attempted here.</li>
 * <li>{@code remote} - fallback: the LAN Spark ({@code test.llm.ollama.server} as configured).</li>
 * </ol>
 *
 * <h3>Write-back keys</h3>
 * <table>
 * <tr><th>key</th><th>value</th></tr>
 * <tr><td>{@code test.llm.route}</td><td>{@code litellm} | {@code direct}</td></tr>
 * <tr><td>{@code test.llm.resolvedTier}</td><td>{@code local} | {@code azure} | {@code remote}</td></tr>
 * <tr><td>{@code test.llm.ollama.server}</td><td>the DIRECT Ollama URL for the tier (local container or .42) -
 *     rewritten in place so reachability gates and {@code /api/tags}-style probes point at the box
 *     that is actually serving</td></tr>
 * <tr><td>{@code test.llm.connection.server}</td><td>what {@code system.connection.serverUrl} should be:
 *     the LiteLLM URL when route=litellm, else the direct Ollama URL</td></tr>
 * <tr><td>{@code test.llm.connection.dialect}</td><td>{@code OPENAI_COMPAT} (litellm) / {@code OLLAMA} (direct)</td></tr>
 * <tr><td>{@code test.llm.connection.upstream}</td><td>{@code OLLAMA} (KI-72: OPENAI_COMPAT must never
 *     infer it, so it is asserted explicitly; LiteLLM runs with {@code drop_params: true} so the Ollama
 *     extension params are harmless on the Azure alias)</td></tr>
 * <tr><td>{@code test.llm.connection.apiKey}</td><td>the LiteLLM master key when route=litellm, else EMPTY</td></tr>
 * <tr><td>{@code test.llm.model.analysis}</td><td>litellm+local {@code qwen3:8b-ctr}; litellm+azure
 *     {@code test.llm.litellm.azure.model}; litellm+remote {@code qwen3:8b}; direct: {@code test.llm.ollama.model}</td></tr>
 * <tr><td>{@code test.llm.model.pb}</td><td>litellm+local {@code qwen3:8b-jos-ctr}; litellm+azure/remote
 *     {@code qwen3:8b-jos}; direct: {@code test.llm.pb.model}</td></tr>
 * </table>
 * The LiteLLM aliases are the {@code model_name}s declared in {@code src/litellm/config.yaml}.
 *
 * <h3>Bypass</h3>
 * {@code -Dtest.llm.ollama.server=<url>} on the mvn command line pins a remote-style DIRECT route to
 * that URL and skips every probe: route=direct, tier=remote, real model names, no API key. Use it to
 * aim a run at one specific Ollama without touching a tracked file.
 *
 * <p>Exactly one summary line is logged per JVM:
 * {@code [LLM-GATE] route=... tier=... analysis=... pb=... server=...}
 *
 * <p>Probes are injectable ({@link #setProbes}) so the resolution logic is unit-testable with no
 * network; {@link #resetForTest()} clears the memo. Neither is used outside tests of this class.
 */
public final class LlmTestGate {
	public static final Logger logger = LogManager.getLogger(LlmTestGate.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LlmTestGate() {}

	/// ---- inputs (resource.properties) ----
	public static final String PROP_LITELLM_SERVER = "test.llm.litellm.server";
	public static final String PROP_OLLAMA_LOCAL_SERVER = "test.llm.ollama.local.server";
	public static final String PROP_OLLAMA_SERVER = "test.llm.ollama.server";
	public static final String PROP_OLLAMA_MODEL = "test.llm.ollama.model";
	public static final String PROP_PB_MODEL = "test.llm.pb.model";
	public static final String PROP_LITELLM_AZURE_MODEL = "test.llm.litellm.azure.model";

	/// ---- write-back ----
	public static final String PROP_ROUTE = "test.llm.route";
	public static final String PROP_RESOLVED_TIER = "test.llm.resolvedTier";
	public static final String PROP_CONNECTION_SERVER = "test.llm.connection.server";
	public static final String PROP_CONNECTION_DIALECT = "test.llm.connection.dialect";
	public static final String PROP_CONNECTION_UPSTREAM = "test.llm.connection.upstream";
	public static final String PROP_CONNECTION_API_KEY = "test.llm.connection.apiKey";
	public static final String PROP_MODEL_ANALYSIS = "test.llm.model.analysis";
	public static final String PROP_MODEL_PB = "test.llm.model.pb";

	public static final String ROUTE_LITELLM = "litellm";
	public static final String ROUTE_DIRECT = "direct";
	public static final String TIER_LOCAL = "local";
	public static final String TIER_AZURE = "azure";
	public static final String TIER_REMOTE = "remote";

	/// LiteLLM aliases, as declared in src/litellm/config.yaml model_list.
	public static final String ALIAS_LOCAL_ANALYSIS = "qwen3:8b-ctr";
	public static final String ALIAS_LOCAL_PB = "qwen3:8b-jos-ctr";
	public static final String ALIAS_REMOTE_ANALYSIS = "qwen3:8b";
	public static final String ALIAS_REMOTE_PB = "qwen3:8b-jos";
	public static final String DEFAULT_AZURE_ALIAS = "gpt-5.6-terra";

	public static final String DEFAULT_MASTER_KEY = "sk-am7-litellm-test";

	/// ---- probes (injectable) ----
	/** True when a GET of the URL answered 2xx. */
	private static Predicate<String> reachable = null;
	/** Ollama base URL -> model names from /api/tags; null when the server did not answer. */
	private static Function<String, List<String>> listModels = null;
	/** LiteLLM alias -> whether {litellm}/health?model=alias reports it healthy. */
	private static Function<String, Boolean> litellmModelHealthy = null;

	/** Inject probes for a no-network unit test. Pass null for any probe to keep the HTTP default. */
	static synchronized void setProbes(Predicate<String> reachableProbe, Function<String, List<String>> listModelsProbe, Function<String, Boolean> litellmHealthyProbe) {
		reachable = reachableProbe;
		listModels = listModelsProbe;
		litellmModelHealthy = litellmHealthyProbe;
	}

	/** Clear the per-JVM memo and injected probes. Test use only. */
	static synchronized void resetForTest() {
		resolution = null;
		reachable = null;
		listModels = null;
		litellmModelHealthy = null;
	}

	/** Immutable result of one resolution; re-applied to the properties on every call after the first. */
	static final class Resolution {
		final String route;
		final String tier;
		final String ollamaServer;
		final String connectionServer;
		final String dialect;
		final String upstream;
		final String apiKey;
		final String analysisModel;
		final String pbModel;
		Resolution(String route, String tier, String ollamaServer, String connectionServer, String dialect, String upstream, String apiKey, String analysisModel, String pbModel) {
			this.route = route;
			this.tier = tier;
			this.ollamaServer = ollamaServer;
			this.connectionServer = connectionServer;
			this.dialect = dialect;
			this.upstream = upstream;
			this.apiKey = apiKey;
			this.analysisModel = analysisModel;
			this.pbModel = pbModel;
		}
	}

	private static Resolution resolution = null;

	/** The memoized resolution, or null before {@link #resolve(Properties)} has run. */
	static synchronized Resolution current() {
		return resolution;
	}

	/**
	 * Resolve route and tier once per JVM and write the result back into {@code testProperties}. Safe
	 * to call from every {@code @Before}: after the first call it only re-applies the memoized values
	 * (no probes), so a test that mutated the shared properties gets the same answer as the rest.
	 */
	public static synchronized void resolve(Properties testProperties) {
		if (testProperties == null) return;
		if (resolution == null) {
			resolution = compute(testProperties);
			logger.info("[LLM-GATE] route=" + resolution.route + " tier=" + resolution.tier
				+ " analysis=" + resolution.analysisModel + " pb=" + resolution.pbModel
				+ " server=" + resolution.connectionServer);
		}
		apply(resolution, testProperties);
	}

	private static Resolution compute(Properties p) {
		String analysisModel = trimOrNull(p.getProperty(PROP_OLLAMA_MODEL));
		String pbModel = trimOrNull(p.getProperty(PROP_PB_MODEL));
		String remoteServer = trimOrNull(p.getProperty(PROP_OLLAMA_SERVER));

		/// Bypass: -Dtest.llm.ollama.server pins a direct route and skips every probe.
		String pinned = trimOrNull(System.getProperty(PROP_OLLAMA_SERVER));
		if (pinned != null) {
			logger.info("[LLM-GATE] -D" + PROP_OLLAMA_SERVER + "=" + pinned + " set - pinning a direct route, probes skipped");
			return new Resolution(ROUTE_DIRECT, TIER_REMOTE, pinned, pinned, "OLLAMA", "OLLAMA", "", analysisModel, pbModel);
		}

		String litellm = trimOrNull(p.getProperty(PROP_LITELLM_SERVER));
		boolean viaLitellm = litellm != null && probeReachable(stripSlash(litellm) + "/health/liveliness");
		String route = viaLitellm ? ROUTE_LITELLM : ROUTE_DIRECT;
		String masterKey = envOr("LITELLM_MASTER_KEY", DEFAULT_MASTER_KEY);

		/// Tier 1: local container serving both 8B models.
		String local = trimOrNull(p.getProperty(PROP_OLLAMA_LOCAL_SERVER));
		String tier = null;
		if (local != null && analysisModel != null && pbModel != null) {
			List<String> tags = probeListModels(local);
			if (tags == null) {
				logger.info("[LLM-GATE] local Ollama " + local + " did not answer /api/tags");
			} else {
				boolean hasAnalysis = hasModel(tags, analysisModel);
				boolean hasPb = hasModel(tags, pbModel);
				if (hasAnalysis && hasPb) {
					tier = TIER_LOCAL;
				} else {
					logger.info("[LLM-GATE] local Ollama " + local + " lists " + analysisModel + "=" + hasAnalysis
						+ ", " + pbModel + "=" + hasPb + " (" + tags.size() + " models) - not usable as the local tier");
				}
			}
		}

		/// Tier 2: Azure through LiteLLM, only behind the existing LITELLM_LIVE opt-in.
		String azureAlias = trimOrNull(p.getProperty(PROP_LITELLM_AZURE_MODEL));
		if (tier == null && viaLitellm && azureAlias != null) {
			if (!TestLiteLLMOllamaProxy.liveEnabled()) {
				logger.info("[LLM-GATE] LITELLM_LIVE not set - Azure tier not probed (no paid call)");
			} else {
				String probeUrl = stripSlash(litellm) + "/health?model=" + azureAlias;
				logger.info("[LLM-GATE] probing Azure alias (one small paid call): GET " + probeUrl);
				boolean healthy = probeLitellmHealthy(litellm, masterKey, azureAlias);
				logger.info("[LLM-GATE] Azure alias " + azureAlias + " healthy=" + healthy);
				if (healthy) tier = TIER_AZURE;
			}
		}

		/// Tier 3: the LAN Spark.
		if (tier == null) tier = TIER_REMOTE;

		String ollamaServer = TIER_LOCAL.equals(tier) ? local : remoteServer;
		if (viaLitellm) {
			String analysis;
			String pb;
			switch (tier) {
				case TIER_LOCAL: analysis = ALIAS_LOCAL_ANALYSIS; pb = ALIAS_LOCAL_PB; break;
				case TIER_AZURE: analysis = azureAlias; pb = ALIAS_REMOTE_PB; break;
				default: analysis = ALIAS_REMOTE_ANALYSIS; pb = ALIAS_REMOTE_PB; break;
			}
			return new Resolution(route, tier, ollamaServer, litellm, "OPENAI_COMPAT", "OLLAMA", masterKey, analysis, pb);
		}
		return new Resolution(route, tier, ollamaServer, ollamaServer, "OLLAMA", "OLLAMA", "", analysisModel, pbModel);
	}

	private static void apply(Resolution r, Properties p) {
		p.setProperty(PROP_ROUTE, r.route);
		p.setProperty(PROP_RESOLVED_TIER, r.tier);
		if (r.ollamaServer != null) p.setProperty(PROP_OLLAMA_SERVER, r.ollamaServer);
		if (r.connectionServer != null) p.setProperty(PROP_CONNECTION_SERVER, r.connectionServer);
		p.setProperty(PROP_CONNECTION_DIALECT, r.dialect);
		p.setProperty(PROP_CONNECTION_UPSTREAM, r.upstream);
		p.setProperty(PROP_CONNECTION_API_KEY, r.apiKey == null ? "" : r.apiKey);
		if (r.analysisModel != null) p.setProperty(PROP_MODEL_ANALYSIS, r.analysisModel);
		if (r.pbModel != null) p.setProperty(PROP_MODEL_PB, r.pbModel);
	}

	/** Exact name match, tolerating a trailing {@code :latest} on either side. */
	static boolean hasModel(List<String> names, String model) {
		if (names == null || model == null) return false;
		String want = stripLatest(model);
		for (String n : names) {
			if (n == null) continue;
			if (n.equals(model) || stripLatest(n).equals(want)) return true;
		}
		return false;
	}

	private static String stripLatest(String s) {
		return s.endsWith(":latest") ? s.substring(0, s.length() - ":latest".length()) : s;
	}

	private static String stripSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private static String trimOrNull(String s) {
		return (s != null && !s.isBlank()) ? s.trim() : null;
	}

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	/// ---- probe dispatch ----

	private static boolean probeReachable(String url) {
		Predicate<String> probe = reachable;
		return probe != null ? probe.test(url) : httpOk(url, null);
	}

	private static List<String> probeListModels(String ollamaBase) {
		Function<String, List<String>> probe = listModels;
		return probe != null ? probe.apply(ollamaBase) : ollamaTags(ollamaBase);
	}

	private static boolean probeLitellmHealthy(String litellm, String masterKey, String alias) {
		Function<String, Boolean> probe = litellmModelHealthy;
		if (probe != null) {
			Boolean b = probe.apply(alias);
			return b != null && b;
		}
		return litellmHealth(litellm, masterKey, alias);
	}

	/// ---- default HTTP probes ----

	/**
	 * HTTP/1.1 forced for the same reason as {@code TestLiteLLMOllamaProxy.httpOk}: Java's default h2c
	 * upgrade is mishandled by some of the Node servers in this stack. Timeouts are generous on purpose
	 * (see {@link SdTestGate#isReachable}).
	 */
	private static HttpResponse<String> get(String url, String bearer) throws Exception {
		HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
		HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(20));
		if (bearer != null && !bearer.isBlank()) b.header("Authorization", "Bearer " + bearer);
		return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static boolean httpOk(String url, String bearer) {
		try {
			int status = get(url, bearer).statusCode();
			logger.info("[LLM-GATE] " + url + " answered HTTP " + status);
			return status >= 200 && status < 300;
		} catch (Exception e) {
			logger.info("[LLM-GATE] " + url + " did not answer: " + e.getMessage());
			return false;
		}
	}

	/** Ollama {@code /api/tags} -> {@code models[].name}; null when the server did not answer 2xx. */
	private static List<String> ollamaTags(String ollamaBase) {
		try {
			HttpResponse<String> resp = get(stripSlash(ollamaBase) + "/api/tags", null);
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				logger.info("[LLM-GATE] " + ollamaBase + "/api/tags answered HTTP " + resp.statusCode());
				return null;
			}
			return parseTagNames(resp.body());
		} catch (Exception e) {
			logger.info("[LLM-GATE] " + ollamaBase + "/api/tags did not answer: " + e.getMessage());
			return null;
		}
	}

	/** Package-visible so the unit test can feed it a real {@code /api/tags} payload. */
	static List<String> parseTagNames(String json) throws Exception {
		List<String> names = new ArrayList<>();
		if (json == null || json.isBlank()) return names;
		JsonNode root = MAPPER.readTree(json);
		JsonNode models = root == null ? null : root.get("models");
		if (models == null || !models.isArray()) return names;
		for (JsonNode m : models) {
			JsonNode n = m.get("name");
			if (n != null && n.isTextual()) names.add(n.asText());
		}
		return names;
	}

	/**
	 * LiteLLM {@code GET /health?model=alias} with the master key. Healthy when the response is 2xx and
	 * the alias appears under {@code healthy_endpoints} (or, for a proxy build that omits the per-model
	 * lists, when {@code unhealthy_count} is 0).
	 */
	private static boolean litellmHealth(String litellm, String masterKey, String alias) {
		try {
			HttpResponse<String> resp = get(stripSlash(litellm) + "/health?model=" + alias, masterKey);
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				logger.info("[LLM-GATE] /health?model=" + alias + " answered HTTP " + resp.statusCode() + " body=" + abbreviate(resp.body()));
				return false;
			}
			JsonNode root = MAPPER.readTree(resp.body());
			if (root == null) return false;
			JsonNode unhealthyCount = root.get("unhealthy_count");
			JsonNode healthyCount = root.get("healthy_count");
			boolean anyHealthy = healthyCount != null && healthyCount.asInt(0) > 0;
			boolean noneUnhealthy = unhealthyCount == null || unhealthyCount.asInt(0) == 0;
			if (!anyHealthy || !noneUnhealthy) {
				logger.info("[LLM-GATE] /health?model=" + alias + " -> healthy_count=" + healthyCount + " unhealthy_count=" + unhealthyCount + " body=" + abbreviate(resp.body()));
			}
			return anyHealthy && noneUnhealthy;
		} catch (Exception e) {
			logger.info("[LLM-GATE] /health?model=" + alias + " did not answer: " + e.getMessage());
			return false;
		}
	}

	private static String abbreviate(String s) {
		if (s == null) return "null";
		return s.length() > 400 ? s.substring(0, 400) + "..." : s;
	}

	/** Names as an unmodifiable list, for callers that want to log them. */
	static List<String> safeList(List<String> l) {
		return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
	}
}
