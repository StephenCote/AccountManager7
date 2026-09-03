package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.iso42001.engine.TestRunner;
import org.cote.accountmanager.iso42001.engine.modules.AttrModule;
import org.cote.accountmanager.iso42001.metrics.LangfuseMetrics;
import org.cote.accountmanager.iso42001.metrics.LangfuseMetricsClient;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.reporting.ReportTemplates;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.NameBank;
import org.cote.accountmanager.iso42001.util.NameBankLoader;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * B5 — LLM operational-metrics report leg (design §2.6). Proves the Langfuse-metrics feature added to
 * {@link ReportGenerator}/{@link ReportTemplates}/{@link LangfuseMetricsClient} without ever faking a
 * metric:
 *
 * <ol>
 *   <li><b>{@link #testLlmMetricsSectionRendersFromFixture()}</b> (deterministic, always runs) — the
 *       {@code ReportTemplates.llmMetrics}/{@code llmMetricsChartJson} render is a pure function of a
 *       {@link LangfuseMetrics} aggregate; asserts the section markdown + chart JSON reflect real token
 *       / cost / latency figures.</li>
 *   <li><b>{@link #testReportWithoutLangfuseHasFourSectionsAndStableHash()}</b> (deterministic, always
 *       runs) — with Langfuse unconfigured, {@code generate()} produces the byte-for-byte-unchanged 4
 *       sections and a deterministic 32-byte content hash (the invariant {@link TestISO42001Report}
 *       depends on; the optional metrics section must be a no-op when unavailable).</li>
 *   <li><b>{@link #testLiveRunMetricsSectionOverProxy()}</b> (LIVE, gated) — drives a REAL
 *       {@link TestRunner} bias run through the OPENAI_COMPAT (LiteLLM) connection so every LLM call
 *       carries {@code session_id = testRun.objectId} (emitted by Objects7 {@code Chat} as the
 *       {@code x-langfuse-session-id} header for that dialect), polls the live Langfuse until that
 *       session has ingested traces + observations, then generates the report and asserts the appended
 *       LLM_METRICS section carries the session's REAL token/cost/latency figures.</li>
 * </ol>
 *
 * <p>Gating: the live leg only runs when {@code -Diso.live.langfuse=true} AND both the LiteLLM
 * liveliness endpoint and Langfuse public-health endpoint answer 200 (otherwise it reports Skipped —
 * the default suite never fires at Azure). Every record is created as a NON-admin role user
 * ({@code isoTester}/{@code isoReporter}); the genuine Azure upstream key never appears here — only the
 * LiteLLM master key (env {@code LITELLM_MASTER_KEY}, falling back to the committed non-secret
 * placeholder). The Langfuse-verification helper lives IN THIS TEST, never in production code.</p>
 */
@Category(IntegrationTest.class)
public class TestISO42001LlmMetricsReport extends ISO42001BaseTest {

	private static final String DEFAULT_LITELLM = "http://127.0.0.1:4000";
	private static final String DEFAULT_LANGFUSE = "http://127.0.0.1:3001";
	private static final String LITELLM_MODEL = "gpt-5.6-terra";
	private static final String DEFAULT_MASTER_KEY = "sk-am7-litellm-test";
	private static final String DEFAULT_LF_PK = "pk-lf-am7-test";
	private static final String DEFAULT_LF_SK = "sk-lf-am7-test";

	/// The JVM-property keys LangfuseMetricsClient.resolveConfig() reads (its own PROP_* constants are
	/// package-private to the metrics package, so this test drives the SAME config source by key literal).
	private static final String LF_PROP_HOST = "langfuse.host";
	private static final String LF_PROP_PK = "langfuse.public.key";
	private static final String LF_PROP_SK = "langfuse.secret.key";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	private String litellmServer() { return envOr("LITELLM_SERVER", DEFAULT_LITELLM); }
	private String langfuseHost()  { return envOr("LANGFUSE_HOST", DEFAULT_LANGFUSE); }
	private String masterKey()     { return envOr("LITELLM_MASTER_KEY", DEFAULT_MASTER_KEY); }
	private String langfusePk()    { return envOr("LANGFUSE_PUBLIC_KEY", DEFAULT_LF_PK); }
	private String langfuseSk()    { return envOr("LANGFUSE_SECRET_KEY", DEFAULT_LF_SK); }

	// ─────────────────────────────────────────────────────────────────────────
	// 1) Deterministic: the LLM_METRICS render reflects a populated metrics aggregate.
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testLlmMetricsSectionRendersFromFixture() throws Exception {
		/// A populated aggregate as LangfuseMetricsClient would return after a real session. Public fields
		/// (the value carrier's contract); finish() derives the mean latency exactly as the client does.
		LangfuseMetrics m = LangfuseMetrics.empty();
		m.traceCount = 6;
		m.observationCount = 6;
		m.promptTokens = 1234;
		m.completionTokens = 567;
		m.totalTokens = 1801;
		m.totalCostUsd = 0.024680;
		m.totalLatencyMs = 9000.0;
		m.latencySamples = 6;
		m.finish();
		assertTrue("fixture aggregate must report hasData()", m.hasData());
		assertEquals("mean latency = total/samples", 1500.0, m.averageLatencyMs, 0.0001);

		String md = ReportTemplates.llmMetrics(m);
		assertTrue("heading present", md.contains("# LLM Operational Metrics"));
		assertTrue("traces row", md.contains("| Traces | 6 |"));
		assertTrue("observations row", md.contains("| LLM calls (observations) | 6 |"));
		assertTrue("prompt tokens row", md.contains("| Prompt tokens | 1234 |"));
		assertTrue("completion tokens row", md.contains("| Completion tokens | 567 |"));
		assertTrue("total tokens row", md.contains("| Total tokens | 1801 |"));
		assertTrue("cost row (4dp)", md.contains("| Total cost (USD) | 0.0247 |"));
		assertTrue("mean latency row (1dp)", md.contains("| Mean trace latency (ms) | 1500.0 |"));

		JsonNode chart = MAPPER.readTree(ReportTemplates.llmMetricsChartJson(m));
		assertEquals(6, chart.path("traceCount").asInt());
		assertEquals(1801, chart.path("totalTokens").asInt());
		assertEquals(1234, chart.path("promptTokens").asInt());
		assertEquals(567, chart.path("completionTokens").asInt());
		assertEquals(0.024680, chart.path("totalCostUsd").asDouble(), 1e-9);
		assertEquals(1500.0, chart.path("averageLatencyMs").asDouble(), 1e-9);

		/// Negative: an unavailable/empty aggregate has no data → render says so; caller omits the section.
		assertFalse("unavailable() must not report data", LangfuseMetrics.unavailable().hasData());
		assertFalse("empty() (0 traces) must not report data", LangfuseMetrics.empty().hasData());
		logger.info("[B5][render] LLM_METRICS section + chart JSON render correctly from a populated aggregate.");
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 2) Deterministic: Langfuse unset ⇒ exactly 4 sections + stable 32-byte hash.
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testReportWithoutLangfuseHasFourSectionsAndStableHash() {
		/// Force LangfuseMetricsClient.resolveConfig() to be incomplete: clear its JVM-property source.
		/// (No LANGFUSE_* env is set in the JUnit JVM; and even if it were, these fixture runs made no LLM
		/// calls so the session has no traces → hasData() false → the section is omitted regardless.)
		clearLangfuseProps();

		List<BaseRecord> run1Results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-HIRE-001", "BIAS", "Gender", "FLAG", 0.31, "ODDS_RATIO", 0.02)));
		BaseRecord run1 = createFixtureTestRun(isoTester, "qwen3:8b", run1Results);
		List<BaseRecord> run2Results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-REF-001", "BIAS", "Religion", "FAIL", 0.62, "COHENS_D", 0.001)));
		BaseRecord run2 = createFixtureTestRun(isoTester, "llama3.1:8b", run2Results);

		String reportName = "b5-nolf-" + UUID.randomUUID();
		ReportGenerator gen = new ReportGenerator(isoReporter);
		BaseRecord report = gen.generate(reportName, "COMPLIANCE", Arrays.asList(run1, run2),
			sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
		assertNotNull("ReportGenerator.generate returned null (RBAC?)", report);

		List<BaseRecord> sections = report.get("sections");
		assertNotNull("sections null", sections);
		assertEquals("Langfuse unavailable ⇒ exactly 4 sections (no LLM_METRICS)", 4, sections.size());
		String[] expected = { "EXECUTIVE_SUMMARY", "METHODOLOGY", "RESULTS", "MITIGATION" };
		for (int i = 0; i < expected.length; i++) {
			assertEquals("section " + i + " order", i, (int) sections.get(i).get("sectionOrder"));
			assertEquals("section " + i + " type", expected[i], sections.get(i).get("sectionType"));
		}
		for (BaseRecord s : sections) {
			assertFalse("no LLM_METRICS section may be present when Langfuse is unavailable",
				"LLM_METRICS".equals(s.get("sectionType")));
		}

		/// Hash is set (32-byte SHA-256) AND is a deterministic pure function of the persisted report:
		/// recomputing over the same record reproduces it byte-for-byte (Phase-6 sign/verify invariant).
		Object hashObj = report.get("hash");
		assertTrue("report.hash must be a byte[]", hashObj instanceof byte[]);
		byte[] stored = (byte[]) hashObj;
		assertEquals("SHA-256 hash must be 32 bytes", 32, stored.length);
		byte[] recomputed = ReportGenerator.computeReportHash(report);
		assertTrue("recomputed canonical hash must equal the stored hash (deterministic; metrics-free "
			+ "report unchanged)", Arrays.equals(stored, recomputed));
		logger.info("[B5][no-langfuse] 4 sections, stable 32-byte hash — metrics feature is a no-op when "
			+ "Langfuse is unavailable.");
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 3) LIVE (gated): real ISO run over OPENAI_COMPAT ⇒ LLM_METRICS with real figures.
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	@Category(LiveTest.class)
	public void testLiveRunMetricsSectionOverProxy() throws Exception {
		assumeTrue("Set -Diso.live.langfuse=true to run the live Langfuse metrics leg",
			Boolean.getBoolean("iso.live.langfuse"));
		assumeStackLive();

		/// Point the production LangfuseMetricsClient (used inside ReportGenerator) at the live stack via
		/// its JVM-property config source. Cleared in finally so no other test inherits it.
		String prevHost = System.setProperty(LF_PROP_HOST, langfuseHost());
		String prevPk = System.setProperty(LF_PROP_PK, langfusePk());
		String prevSk = System.setProperty(LF_PROP_SK, langfuseSk());
		try {
			OlioModelNames.use(); // idempotent; ensure the chatConfig/connection models are registered

			/// --- Build the OPENAI_COMPAT (LiteLLM) chat config IN-MEMORY (never persisted: Chat only
			///     re-queries the persisted connection by FK id — matches TestLiteLLMRoundTrip and sidesteps
			///     any stale serviceType column, since we never write the chatConfig row). ---
			String nonce = UUID.randomUUID().toString().substring(0, 8);
			BaseRecord conn = OlioTestUtil.getCreateConnection(isoTester, "ISO B5 LiteLLM " + nonce,
				litellmServer(), masterKey(), 120);
			assertNotNull("LiteLLM connection create returned null", conn);
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, "ISO B5 OPENAI_COMPAT " + nonce);
			BaseRecord chatConfig = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG,
				isoTester, null, plist);
			assertNotNull("chatConfig factory newInstance returned null", chatConfig);
			chatConfig.set("serviceType", LLMServiceEnumType.OPENAI_COMPAT);
			chatConfig.set("connection", conn);
			chatConfig.set("model", LITELLM_MODEL);

			/// --- Persist a BIAS testConfig (tiny N) and drive a REAL run: TestRunner stamps
			///     session_id = testRun.objectId onto every OPENAI_COMPAT LLM call. ---
			BaseRecord tc = createTestConfig(1, 1, 20260903L);
			NameBank bank = new NameBankLoader().loadDefault();
			BaseRecord run = new TestRunner(isoTester, chatConfig).run(tc, new AttrModule(), bank);
			assertNotNull("TestRunner.run returned null (RBAC/create failure)", run);

			String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("run objectId null", runOid);
			BaseRecord reread = findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_RUN, runOid);
			assertNotNull("run not re-readable", reread);
			List<BaseRecord> results = reread.get("results");
			assertNotNull("run.results null", results);
			assertTrue("run must embed at least one result", !results.isEmpty());
			String verdict = results.get(0).get("verdict");
			logger.info("[B5][live] run " + runOid + " verdict=" + verdict + " totalTrials="
				+ reread.get("totalTrials"));
			/// The stack was probed live; an ERROR verdict means the OPENAI_COMPAT calls did not reach the
			/// endpoint — fail loudly rather than pretend the metrics leg ran.
			assertFalse("LLM endpoint unreachable through OPENAI_COMPAT (verdict ERROR) — cannot prove "
				+ "Langfuse metrics; check LiteLLM->Azure", "ERROR".equals(verdict));

			/// --- Poll the live Langfuse (via the PRODUCTION client) until this session has ingested traces
			///     AND per-call observations (token usage / cost land on observations, which lag the trace). ---
			LangfuseMetricsClient client = new LangfuseMetricsClient();
			LangfuseMetrics metrics = LangfuseMetrics.unavailable();
			long deadline = System.currentTimeMillis() + 120_000L;
			int attempt = 0;
			while (System.currentTimeMillis() < deadline) {
				attempt++;
				metrics = client.fetchSessionMetrics(runOid);
				if (metrics != null && metrics.hasData() && metrics.observationCount > 0
						&& metrics.totalTokens > 0) {
					break;
				}
				Thread.sleep(3000);
			}
			assertNotNull("fetchSessionMetrics returned null (must never)", metrics);
			logger.info("[B5][live] session " + runOid + " after " + attempt + " poll(s): traces="
				+ metrics.traceCount + " observations=" + metrics.observationCount + " promptTokens="
				+ metrics.promptTokens + " completionTokens=" + metrics.completionTokens + " totalTokens="
				+ metrics.totalTokens + " costUsd=" + metrics.totalCostUsd + " meanLatencyMs="
				+ metrics.averageLatencyMs);
			assertTrue("Langfuse must report traces for sessionId=" + runOid + " (session correlation)",
				metrics.hasData());
			assertTrue("Langfuse must report per-call observations for the session", metrics.observationCount > 0);
			assertTrue("observations must carry REAL token usage (totalTokens>0)", metrics.totalTokens > 0);

			/// --- Generate the report over the real run; ReportGenerator re-pulls the same session metrics
			///     and appends the LLM_METRICS section (5th). ---
			String reportName = "b5-live-" + UUID.randomUUID();
			BaseRecord report = new ReportGenerator(isoReporter).generate(reportName, "BIAS",
				Arrays.asList(reread), sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
			assertNotNull("live report generate returned null (RBAC?)", report);

			List<BaseRecord> sections = report.get("sections");
			assertNotNull("sections null", sections);
			assertEquals("live report with metrics must have 5 sections", 5, sections.size());
			BaseRecord metricsSection = sections.get(4);
			assertEquals("5th section must be LLM_METRICS", "LLM_METRICS", metricsSection.get("sectionType"));
			assertEquals("LLM_METRICS section order", 4, (int) metricsSection.get("sectionOrder"));
			String content = metricsSection.get("content");
			assertNotNull("LLM_METRICS content null", content);
			assertTrue("LLM_METRICS content must render the operational-metrics table",
				content.contains("# LLM Operational Metrics") && content.contains("| Total tokens |"));
			assertFalse("LLM_METRICS must not be the metrics-free placeholder text",
				content.contains("No operational metrics were available"));
			String chartData = metricsSection.get("chartData");
			assertNotNull("LLM_METRICS chartData null", chartData);
			JsonNode chart = MAPPER.readTree(chartData);
			assertTrue("chart totalTokens must be > 0", chart.path("totalTokens").asLong() > 0);
			assertTrue("chart traceCount must be > 0", chart.path("traceCount").asInt() > 0);

			logger.info("[B5][live] PASS — real ISO run over OPENAI_COMPAT produced Langfuse-traced session "
				+ runOid + "; report LLM_METRICS section carries real token/cost/latency figures. "
				+ "content=\n" + content);
		} finally {
			restoreProp(LF_PROP_HOST, prevHost);
			restoreProp(LF_PROP_PK, prevPk);
			restoreProp(LF_PROP_SK, prevSk);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Helpers (IN-TEST only — no Langfuse/HTTP wiring leaks into production).
	// ─────────────────────────────────────────────────────────────────────────

	/** Create a BIAS testConfig owned by isoTester in the shared group (mirrors BiasModuleTestBase). */
	private BaseRecord createTestConfig(int perGroup, int tier, long seed) {
		BaseRecord tc = newRec(ISO42001ModelNames.MODEL_TEST_CONFIG);
		set(tc, FieldNames.FIELD_NAME, "b5-tc-" + UUID.randomUUID());
		set(tc, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(tc, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(tc, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		set(tc, "moduleId", "BIAS");
		set(tc, "endpointName", "litellm-openai-compat");
		set(tc, "endpointType", "openai_compat");
		set(tc, "samplesPerGroup", perGroup);
		set(tc, "tier", tier);
		set(tc, "randomSeed", seed);
		BaseRecord created = ioContext.getAccessPoint().create(isoTester, tc);
		assertNotNull("testConfig CREATE as isoTester returned null", created);
		return created;
	}

	private void clearLangfuseProps() {
		System.clearProperty(LF_PROP_HOST);
		System.clearProperty(LF_PROP_PK);
		System.clearProperty(LF_PROP_SK);
	}

	private static void restoreProp(String key, String prev) {
		if (prev == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, prev);
		}
	}

	/** Skip (not fail) the live leg when the LiteLLM/Langfuse stack is not up — never reach Azure by default. */
	private void assumeStackLive() {
		boolean litellm = httpOk(litellmServer() + "/health/liveliness");
		boolean langfuse = httpOk(langfuseHost() + "/api/public/health");
		if (!litellm || !langfuse) {
			logger.warn("[B5][live] stack not live (litellm=" + litellm + " langfuse=" + langfuse
				+ ") — SKIPPING. Bring it up: cd src && docker compose -p am7test -f docker-compose.test.yml "
				+ "--env-file ./volatile/llmproxy.env --profile llmproxy up -d");
		}
		assumeTrue("LiteLLM liveliness not 200 (" + litellmServer() + ")", litellm);
		assumeTrue("Langfuse public health not 200 (" + langfuseHost() + ")", langfuse);
	}

	private boolean httpOk(String url) {
		try {
			/// Force HTTP/1.1: Langfuse's Next.js server mishandles the HTTP/2 h2c upgrade Java's client
			/// defaults to (closes the socket) even though it answers 200 to curl.
			HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5)).build();
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET()
				.timeout(Duration.ofSeconds(8)).build();
			return c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
		} catch (Exception e) {
			logger.warn("[B5][live] probe failed " + url + " : " + e.getMessage());
			return false;
		}
	}

	/** Directly poll the Langfuse traces API for a session (independent confirmation; unused in asserts). */
	@SuppressWarnings("unused")
	private JsonNode pollTrace(String sessionId, int timeoutSec) throws Exception {
		HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(8)).build();
		String basic = Base64.getEncoder().encodeToString(
			(langfusePk() + ":" + langfuseSk()).getBytes(StandardCharsets.UTF_8));
		String url = langfuseHost() + "/api/public/traces?sessionId="
			+ URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name());
		long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
		while (System.currentTimeMillis() < deadline) {
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
				.header("Authorization", "Basic " + basic).GET().timeout(Duration.ofSeconds(15)).build();
			HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 200) {
				JsonNode data = MAPPER.readTree(r.body()).get("data");
				if (data != null && data.isArray() && data.size() > 0) {
					return data.get(0);
				}
			}
			Thread.sleep(1000);
		}
		return null;
	}
}
