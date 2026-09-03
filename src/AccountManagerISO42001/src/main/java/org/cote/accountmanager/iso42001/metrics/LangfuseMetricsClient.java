package org.cote.accountmanager.iso42001.metrics;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Langfuse public-API client for pulling per-session LLM operational metrics (cost / latency / tokens)
 * to embed in an ISO 42001 report (design §2.6).
 *
 * <p><b>Layering (hard boundary).</b> ALL Langfuse HTTP and ALL token/cost/latency JSON parsing live
 * here, in {@code AccountManagerISO42001}. Objects7 and Service7 never import a Langfuse client or its
 * parsing. The only edge into Objects7 is generic: the ISO engine sets {@code session_id =
 * testRun.objectId} on the OPENAI_COMPAT request (a plain tracing string; Objects7's {@code Chat}
 * emits it as the body {@code session_id} field AND the {@code x-langfuse-session-id} header for that
 * dialect only). This client then filters Langfuse traces by that session.</p>
 *
 * <p><b>Config resolution — find-only, per call, never cached.</b> The Langfuse host and public/secret
 * keys are read from environment variables (falling back to JVM system properties) on <em>every</em>
 * call via {@link #resolveConfig()}. There is deliberately no process-global mutable holder: a per-call
 * read has no staleness window and no cross-JVM cache-invalidation problem (Console7 changing a value
 * cannot leave this JVM stale because nothing is retained). This is a read path — it performs no
 * create-or-get and no writes. Keys are read but <b>never logged</b>.</p>
 */
public class LangfuseMetricsClient {

	private static final Logger logger = LogManager.getLogger(LangfuseMetricsClient.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ── Config source: env vars first, then JVM system properties. Read per call; never logged. ──
	static final String ENV_HOST = "LANGFUSE_HOST";
	static final String ENV_PUBLIC_KEY = "LANGFUSE_PUBLIC_KEY";
	static final String ENV_SECRET_KEY = "LANGFUSE_SECRET_KEY";
	static final String PROP_HOST = "langfuse.host";
	static final String PROP_PUBLIC_KEY = "langfuse.public.key";
	static final String PROP_SECRET_KEY = "langfuse.secret.key";

	/**
	 * THE single place the Langfuse "which field to filter a session on" is defined.
	 *
	 * <p>The ISO engine sets {@code session_id = testRun.objectId} on the OPENAI_COMPAT request; Objects7
	 * {@code Chat} emits that for the OPENAI_COMPAT dialect only, as both the request-body
	 * {@code session_id} field and the {@code x-langfuse-session-id} header, and Langfuse groups the
	 * resulting traces into a session queryable on its public {@code GET /api/public/traces} list by
	 * this query parameter. If the proven wiring turns out to key on a different param/field, change
	 * ONLY this constant (and, if the value source changes, {@link #sessionFilterQuery(String)}).</p>
	 */
	public static final String SESSION_FILTER_PARAM = "sessionId";

	/// Langfuse's public list APIs (/api/public/traces, /api/public/observations) cap the `limit` query
	/// parameter at 100 and reject anything larger with HTTP 400 ("Number must be less than or equal to
	/// 100"). This value is used BOTH as that page-size limit and as the local per-session processing cap,
	/// so it must not exceed 100 or every real fetch 400s and the metrics section is silently omitted.
	private static final int MAX_TRACES = 100;
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	private final HttpClient http;

	public LangfuseMetricsClient() {
		/// Pin HTTP/1.1: Langfuse's Next.js server mishandles the HTTP/2 h2c upgrade Java's HttpClient
		/// attempts by default — it closes the socket without sending a response, surfacing as
		/// "HTTP/1.1 header parser received no bytes". Every /api/public read then fails and
		/// fetchSessionMetrics() falls through to unavailable(), silently omitting the operational-metrics
		/// section from every report even though the server answers 200 to an HTTP/1.1 request.
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(TIMEOUT)
			.build();
	}

	/** Immutable Langfuse connection holder — host + key pair swapped as one unit (never a torn pair). */
	static final class LangfuseConfig {
		final String host;
		final String publicKey;
		final String secretKey;

		LangfuseConfig(String host, String publicKey, String secretKey) {
			this.host = host;
			this.publicKey = publicKey;
			this.secretKey = secretKey;
		}

		boolean isComplete() {
			return notBlank(host) && notBlank(publicKey) && notBlank(secretKey);
		}

		/** Base URL with any trailing slash trimmed. */
		String baseUrl() {
			String h = host.trim();
			return h.endsWith("/") ? h.substring(0, h.length() - 1) : h;
		}

		/** {@code Authorization: Basic base64(pk:sk)}. Never logged. */
		String basicAuth() {
			String raw = publicKey + ":" + secretKey;
			return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}
	}

	static LangfuseConfig resolveConfig() {
		String host = firstNonBlank(System.getenv(ENV_HOST), System.getProperty(PROP_HOST));
		String pk = firstNonBlank(System.getenv(ENV_PUBLIC_KEY), System.getProperty(PROP_PUBLIC_KEY));
		String sk = firstNonBlank(System.getenv(ENV_SECRET_KEY), System.getProperty(PROP_SECRET_KEY));
		return new LangfuseConfig(host, pk, sk);
	}

	/** Build the session filter fragment for the traces list query (the single filter-field seam). */
	static String sessionFilterQuery(String sessionId) {
		return SESSION_FILTER_PARAM + "=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
	}

	/**
	 * Fetch and aggregate the LLM operational metrics (cost / latency / tokens) for one session
	 * (= one {@code iso42001.testRun.objectId}).
	 *
	 * <p>Returns {@link LangfuseMetrics#unavailable()} — never {@code null}, never a throw — when
	 * Langfuse is not configured, the session is blank, or any HTTP/parse step fails. Report
	 * generation must never fail because Langfuse is down; the caller simply omits the section.</p>
	 */
	public LangfuseMetrics fetchSessionMetrics(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return LangfuseMetrics.unavailable();
		}
		LangfuseConfig cfg = resolveConfig();
		if (!cfg.isComplete()) {
			return LangfuseMetrics.unavailable();
		}
		try {
			String tracesUrl = cfg.baseUrl() + "/api/public/traces?" + sessionFilterQuery(sessionId)
				+ "&limit=" + MAX_TRACES;
			JsonNode traces = getJson(cfg, tracesUrl);
			JsonNode data = (traces == null) ? null : traces.get("data");
			if (data == null || !data.isArray() || data.isEmpty()) {
				/// Configured and reachable, but no traces for this session yet: available with zeros.
				return LangfuseMetrics.empty();
			}

			LangfuseMetrics agg = LangfuseMetrics.empty();
			int processed = 0;
			for (JsonNode trace : data) {
				if (processed >= MAX_TRACES) {
					break;
				}
				processed++;
				agg.traceCount++;
				/// Trace-level latency (Langfuse reports it in seconds) → milliseconds when present.
				double traceLatencySec = firstNumber(trace, "latency");
				if (traceLatencySec > 0) {
					agg.totalLatencyMs += traceLatencySec * 1000.0;
					agg.latencySamples++;
				}
				String traceId = text(trace, "id");
				if (traceId != null) {
					aggregateObservations(cfg, traceId, agg);
				}
			}
			return agg.finish();
		} catch (Exception e) {
			logger.warn("Langfuse session metrics fetch failed (section will be omitted): " + e.getMessage());
			return LangfuseMetrics.unavailable();
		}
	}

	/** Sum token usage and cost from a trace's observations (the per-call generations). */
	private void aggregateObservations(LangfuseConfig cfg, String traceId, LangfuseMetrics agg) {
		try {
			String url = cfg.baseUrl() + "/api/public/observations?traceId="
				+ URLEncoder.encode(traceId, StandardCharsets.UTF_8) + "&limit=" + MAX_TRACES;
			JsonNode obs = getJson(cfg, url);
			JsonNode data = (obs == null) ? null : obs.get("data");
			if (data == null || !data.isArray()) {
				return;
			}
			for (JsonNode o : data) {
				agg.observationCount++;
				agg.promptTokens += (long) firstNumber(o, "promptTokens", "inputTokens");
				agg.completionTokens += (long) firstNumber(o, "completionTokens", "outputTokens");
				long total = (long) firstNumber(o, "totalTokens");
				if (total <= 0) {
					total = (long) (firstNumber(o, "promptTokens", "inputTokens")
						+ firstNumber(o, "completionTokens", "outputTokens"));
				}
				agg.totalTokens += total;
				agg.totalCostUsd += firstNumber(o, "calculatedTotalCost", "totalCost", "cost");
			}
		} catch (Exception e) {
			logger.warn("Langfuse observations fetch failed for trace " + traceId + ": " + e.getMessage());
		}
	}

	private JsonNode getJson(LangfuseConfig cfg, String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
			.timeout(TIMEOUT)
			.header("Authorization", cfg.basicAuth())
			.header("Accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			logger.warn("Langfuse API returned HTTP " + resp.statusCode() + " for " + redactUrl(url));
			return null;
		}
		return MAPPER.readTree(resp.body());
	}

	// ── Defensive JSON helpers: numeric field names differ across Langfuse versions, so try in order. ──

	private static double firstNumber(JsonNode node, String... fields) {
		if (node == null) {
			return 0.0;
		}
		for (String f : fields) {
			JsonNode v = node.get(f);
			if (v != null && v.isNumber()) {
				return v.asDouble();
			}
		}
		return 0.0;
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = (node == null) ? null : node.get(field);
		return (v == null || v.isNull()) ? null : v.asText();
	}

	/** Strip the query string so a logged URL never carries an encoded session id or other data. */
	private static String redactUrl(String url) {
		int q = url.indexOf('?');
		return q < 0 ? url : url.substring(0, q) + "?…";
	}

	private static String firstNonBlank(String a, String b) {
		if (notBlank(a)) {
			return a;
		}
		return notBlank(b) ? b : null;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
