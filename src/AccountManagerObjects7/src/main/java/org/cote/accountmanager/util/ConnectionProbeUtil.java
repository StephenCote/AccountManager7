package org.cote.accountmanager.util;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Network-only probes for the Service Status app (aiDocs/ServiceStatusDesign.md §3). No authorization,
/// no records, no caching: SystemCheckUtil decides WHAT to probe and as whom; this class only answers
/// "what happened when I asked that URL".
///
/// TRANSPORT RULES (design §3, not negotiable):
///   - java.net.http.HttpClient with a 5 s connect timeout and a 5 s request timeout;
///   - the JVM's DEFAULT trust store - never a trust-all SSLContext;
///   - redirects are NEVER followed (a 3xx is reported as http_3xx; a probe must not be steerable);
///   - ClientUtil is NOT used: its static authToken is dereferenced unguarded (ClientUtil.java:263-264)
///     and its read timeout runs to 1200 s.
///
/// OUTPUT RULES (design §3.1, B2): a probe yields a REASON CODE. Exception messages are never surfaced -
/// java.net.http exceptions name the host and port - and the response body is handed back only for the
/// caller to PARSE (model lists, health flags); it must never be copied into a detail string.
public final class ConnectionProbeUtil {

	private static final Logger logger = LogManager.getLogger(ConnectionProbeUtil.class);

	public static final int TIMEOUT_SECONDS = 5;

	/// Azure OpenAI data-plane api-version used for GET /openai/models when the referencing chatConfig
	/// carries no apiVersion of its own.
	public static final String DEFAULT_AZURE_API_VERSION = "2024-10-21";

	public static final String REASON_OK = "ok";
	public static final String REASON_UNREACHABLE = "unreachable";
	public static final String REASON_TIMEOUT = "timeout";
	public static final String REASON_TLS = "tls";
	public static final String REASON_AUTH = "auth";
	public static final String REASON_NOT_CONFIGURED = "not_configured";
	public static final String REASON_EMPTY_RESPONSE = "empty_response";
	public static final String REASON_MODEL_MISSING = "model_missing";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private ConnectionProbeUtil() {
	}

	/// The result of one GET. `reason` is REASON_OK whenever an HTTP response arrived at all (whatever its
	/// status); callers map the status. `body` is for parsing only.
	public static final class Probe {
		private final String reason;
		private final int httpStatus;
		private final String body;
		private final long latencyMs;

		Probe(String reason, int httpStatus, String body, long latencyMs) {
			this.reason = reason;
			this.httpStatus = httpStatus;
			this.body = body;
			this.latencyMs = latencyMs;
		}

		public String getReason() {
			return reason;
		}

		/// 0 when no HTTP response arrived.
		public int getHttpStatus() {
			return httpStatus;
		}

		public String getBody() {
			return body;
		}

		public long getLatencyMs() {
			return latencyMs;
		}

		public boolean isAnswered() {
			return httpStatus > 0;
		}

		/// http_<code> for an answered probe, otherwise the transport reason.
		public String statusReason() {
			return (httpStatus > 0 ? "http_" + httpStatus : reason);
		}
	}

	/// GET url with the given headers. Never throws. A blank or unparseable URL is REASON_NOT_CONFIGURED.
	public static Probe get(String url, Map<String, String> headers) {
		long start = System.currentTimeMillis();
		if(url == null || url.isBlank()) {
			return new Probe(REASON_NOT_CONFIGURED, 0, null, 0L);
		}
		HttpRequest.Builder b;
		try {
			URI uri = URI.create(url.trim());
			if(uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
				return new Probe(REASON_NOT_CONFIGURED, 0, null, 0L);
			}
			b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET();
		}
		catch(IllegalArgumentException e) {
			return new Probe(REASON_NOT_CONFIGURED, 0, null, 0L);
		}
		if(headers != null) {
			for(Map.Entry<String, String> h : headers.entrySet()) {
				if(h.getKey() != null && h.getValue() != null) {
					b.header(h.getKey(), h.getValue());
				}
			}
		}
		try {
			HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return new Probe(REASON_OK, resp.statusCode(), resp.body(), System.currentTimeMillis() - start);
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Probe(REASON_TIMEOUT, 0, null, System.currentTimeMillis() - start);
		}
		catch(Exception e) {
			String reason = classify(e);
			/// Debug only and without the message: the message names host:port, and this is a diagnostic
			/// that any admin can trigger repeatedly.
			logger.debug("Probe failed: " + reason + " (" + e.getClass().getSimpleName() + ")");
			return new Probe(reason, 0, null, System.currentTimeMillis() - start);
		}
	}

	/// Map a transport exception to a reason code. HttpConnectTimeoutException is a subclass of
	/// HttpTimeoutException; both are a timeout. TLS failures are anywhere in the cause chain.
	public static String classify(Throwable t) {
		Throwable c = t;
		int depth = 0;
		while(c != null && depth++ < 8) {
			if(c instanceof SSLException) {
				return REASON_TLS;
			}
			if(c instanceof HttpConnectTimeoutException || c instanceof HttpTimeoutException) {
				return REASON_TIMEOUT;
			}
			if(c instanceof ConnectException) {
				return REASON_UNREACHABLE;
			}
			c = c.getCause();
		}
		return REASON_UNREACHABLE;
	}

	// ─── model lists (moved from VlmQaProvider.listModels) ──────────────────────────────

	/// Ollama GET /api/tags → models[].name; OpenAI-compatible GET /v1/models → data[].id. Null when the
	/// body is not JSON or not the expected shape. Extracted from VlmQaProvider.listModels, which now
	/// delegates here.
	@SuppressWarnings("unchecked")
	public static List<String> parseModelNames(String raw, boolean ollama) {
		if(raw == null) {
			return null;
		}
		Map<String, Object> m = null;
		try {
			m = JSONUtil.getMap(raw.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.warn("Model list was not JSON: " + e.getMessage());
		}
		if(m == null) {
			return null;
		}
		Object arr = m.get(ollama ? "models" : "data");
		if(!(arr instanceof List)) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for(Object o : (List<?>) arr) {
			if(o instanceof Map) {
				Object n = ((Map<String, Object>) o).get(ollama ? "name" : "id");
				if(n != null) {
					names.add(n.toString());
				}
			}
		}
		return names;
	}

	/// Exact match tolerating a trailing ":latest" on either side (the LlmTestGate rule).
	public static boolean hasModel(List<String> names, String model) {
		if(names == null || model == null) {
			return false;
		}
		String want = stripLatest(model);
		for(String n : names) {
			if(n != null && stripLatest(n).equals(want)) {
				return true;
			}
		}
		return false;
	}

	public static String stripLatest(String s) {
		String t = s.trim();
		return (t.endsWith(":latest") ? t.substring(0, t.length() - ":latest".length()) : t);
	}

	/// Join a base URL and a path without doubling the slash.
	public static String join(String base, String path) {
		if(base == null) {
			return null;
		}
		String b = base.trim();
		while(b.endsWith("/")) {
			b = b.substring(0, b.length() - 1);
		}
		return b + path;
	}
}
