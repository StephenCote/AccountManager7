package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.olio.llm.OllamaModelUtil.UnloadResult;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

/// Deterministic wire-level tests (no DB / no LLM) for OllamaModelUtil.unloadAll() behaving per
/// dialect/upstream (LLM lane item 4, 2026-10-07). Two local HttpServers stand in for a NATIVE
/// Ollama server and for a PROXY (LiteLLM) that fronts one; each records every POST it receives.
///
/// What is pinned:
///  - native (dialect OLLAMA) entries are unloaded via POST {server}/api/generate {"model","keep_alive":0}
///    and counted as `unloaded` ONLY when the server answers 200;
///  - a non-200 answer is reported as `failed`, never as a success (the old code logged "Unloaded"
///    on a 404 because ClientUtil.postJSON returns null rather than throwing);
///  - proxied entries (dialect OPENAI_COMPAT + upstream OLLAMA) are NEVER posted to the proxy; with
///    no direct URL registered they are counted as `skippedProxied` and retained; once a direct URL
///    is registered the next pass routes them there;
///  - a non-Ollama upstream records nothing;
///  - nothing throws on a dead host.
///
/// Deliberately does NOT extend BaseTest (see TestOllamaUnloadToggle for why).
public class TestOllamaModelUnload {
	public static final Logger logger = LogManager.getLogger(TestOllamaModelUnload.class);

	private HttpServer nativeServer = null;
	private HttpServer proxyServer = null;
	private final List<String> nativeHits = new CopyOnWriteArrayList<>();
	private final List<String> proxyHits = new CopyOnWriteArrayList<>();

	@After
	public void stopServers() {
		if (nativeServer != null) { nativeServer.stop(0); nativeServer = null; }
		if (proxyServer != null) { proxyServer.stop(0); proxyServer = null; }
		OllamaModelUtil.setUnloadEnabled(false);
	}

	/// A server whose /api/generate answers `status` and records each request body.
	private static HttpServer startServer(int status, List<String> hits) throws Exception {
		HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		s.createContext("/api/generate", ex -> {
			try {
				hits.add(readAll(ex.getRequestBody()));
				byte[] body = (status == 200 ? "{\"model\":\"x\",\"done\":true}" : "{\"error\":\"not found\"}")
					.getBytes(StandardCharsets.UTF_8);
				ex.getResponseHeaders().add("Content-Type", "application/json");
				ex.sendResponseHeaders(status, body.length);
				ex.getResponseBody().write(body);
			} catch (Exception e) {
				logger.error("handler error", e);
			} finally {
				ex.close();
			}
		});
		s.setExecutor(null);
		s.start();
		return s;
	}

	private static String base(HttpServer s) {
		return "http://127.0.0.1:" + s.getAddress().getPort();
	}

	private static String readAll(InputStream in) {
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
			byte[] buf = new byte[4096];
			int r;
			while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	/// Drain anything another test in this JVM left in the registry so counts below are exact.
	private static void drainRegistry() {
		OllamaModelUtil.unloadAll(true);
	}

	@Test
	public void nativeUnloadIsCountedOnlyOn200AndPostsKeepAliveZero() throws Exception {
		drainRegistry();
		nativeServer = startServer(200, nativeHits);
		String url = base(nativeServer);
		String model = "unload-test-native-" + System.nanoTime();

		OllamaModelUtil.recordUsage(url, model, LLMServiceEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA);
		assertTrue("dialect OLLAMA must be tracked as NATIVE", OllamaModelUtil.trackedNativeModels(url).contains(model));
		assertTrue("dialect OLLAMA must not be tracked as proxied", OllamaModelUtil.trackedProxiedModels(url).isEmpty());

		UnloadResult r = OllamaModelUtil.unloadAll(true);
		logger.info("native 200 pass: " + r);
		assertTrue(r.attempted);
		assertEquals("one model acknowledged", 1, r.unloaded);
		assertEquals(0, r.failed);
		assertEquals(0, r.skippedProxied);
		assertEquals("exactly one POST to the native server", 1, nativeHits.size());

		JsonNode body = new ObjectMapper().readTree(nativeHits.get(0));
		assertNotNull(body);
		assertEquals(model, body.get("model").asText());
		assertEquals("keep_alive:0 is the documented unload trick", 0, body.get("keep_alive").asInt());
		assertTrue("registry drained after a successful unload", OllamaModelUtil.trackedNativeModels(url).isEmpty());
	}

	@Test
	public void non200IsReportedAsFailureNotSuccess() throws Exception {
		drainRegistry();
		nativeServer = startServer(404, nativeHits);
		String url = base(nativeServer);
		String model = "unload-test-404-" + System.nanoTime();

		OllamaModelUtil.recordUsage(url, model);
		UnloadResult r = OllamaModelUtil.unloadAll(true);
		logger.info("native 404 pass: " + r);
		assertEquals("a 404 must NOT be counted as unloaded", 0, r.unloaded);
		assertEquals("a 404 is a failed attempt", 1, r.failed);
		assertEquals(1, nativeHits.size());
		assertTrue("a failed entry is still cleared (no retry-loop against a refusing server)",
			OllamaModelUtil.trackedNativeModels(url).isEmpty());
	}

	@Test
	public void proxiedEntriesAreSkippedNotPostedToTheProxy() throws Exception {
		drainRegistry();
		proxyServer = startServer(404, proxyHits);
		String proxyUrl = base(proxyServer);
		String model = "unload-test-proxied-" + System.nanoTime();

		OllamaModelUtil.recordUsage(proxyUrl, model, LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA);
		assertTrue("OPENAI_COMPAT + upstream OLLAMA must be tracked as PROXIED",
			OllamaModelUtil.trackedProxiedModels(proxyUrl).contains(model));
		assertTrue("...and NOT as native", OllamaModelUtil.trackedNativeModels(proxyUrl).isEmpty());

		UnloadResult r = OllamaModelUtil.unloadAll(true);
		logger.info("proxied (no direct url) pass: " + r);
		assertEquals("nothing may be reported unloaded", 0, r.unloaded);
		assertEquals(0, r.failed);
		assertEquals("the proxied model is reported as skipped", 1, r.skippedProxied);
		assertEquals("the proxy's /api/generate must never be called", 0, proxyHits.size());
		assertTrue("a skipped entry is RETAINED so a later pass can act on it",
			OllamaModelUtil.trackedProxiedModels(proxyUrl).contains(model));

		/// Now tell the registry where the real Ollama is: the next pass must route there.
		nativeServer = startServer(200, nativeHits);
		String directUrl = base(nativeServer);
		OllamaModelUtil.registerDirectUrl(proxyUrl, directUrl);
		try {
			UnloadResult r2 = OllamaModelUtil.unloadAll(true);
			logger.info("proxied (direct url registered) pass: " + r2);
			assertEquals("routed to the direct server and acknowledged", 1, r2.unloaded);
			assertEquals(0, r2.skippedProxied);
			assertEquals("still nothing posted to the proxy", 0, proxyHits.size());
			assertEquals("one POST to the direct server", 1, nativeHits.size());
			JsonNode body = new ObjectMapper().readTree(nativeHits.get(0));
			assertEquals(model, body.get("model").asText());
			assertTrue(OllamaModelUtil.trackedProxiedModels(proxyUrl).isEmpty());
		} finally {
			OllamaModelUtil.registerDirectUrl(proxyUrl, null);
		}
	}

	@Test
	public void nonOllamaUpstreamRecordsNothing() {
		drainRegistry();
		String url = "http://127.0.0.1:1";
		OllamaModelUtil.recordUsage(url, "gpt-5.6-terra", LLMServiceEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.UNKNOWN);
		OllamaModelUtil.recordUsage(url, "gpt-5.6-terra", LLMServiceEnumType.OPENAI, ConnectionUpstreamEnumType.UNKNOWN);
		assertTrue(OllamaModelUtil.trackedNativeModels(url).isEmpty());
		assertTrue(OllamaModelUtil.trackedProxiedModels(url).isEmpty());
		UnloadResult r = OllamaModelUtil.unloadAll(true);
		assertEquals(0, r.unloaded + r.failed + r.skippedProxied);
	}

	@Test
	public void deadHostNeverThrowsAndIsReportedFailed() {
		drainRegistry();
		String url = "http://127.0.0.1:1";
		OllamaModelUtil.recordUsage(url, "unload-test-dead", LLMServiceEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA);
		UnloadResult r = OllamaModelUtil.unloadAll(true);
		assertEquals(0, r.unloaded);
		assertEquals(1, r.failed);
		assertFalse("forcing must not flip the global switch", OllamaModelUtil.isUnloadEnabled());
	}

	@Test
	public void opportunisticPassIsAllZerosWhenDisabled() {
		drainRegistry();
		OllamaModelUtil.setUnloadEnabled(false);
		OllamaModelUtil.recordUsage("http://127.0.0.1:1", "unload-test-disabled");
		UnloadResult r = OllamaModelUtil.unloadAll();
		assertFalse("disabled pass must not even attempt", r.attempted);
		assertEquals(0, r.unloaded + r.failed + r.skippedProxied);
		assertTrue("registry preserved across a disabled pass",
			OllamaModelUtil.trackedNativeModels("http://127.0.0.1:1").contains("unload-test-disabled"));
		drainRegistry();
	}
}
