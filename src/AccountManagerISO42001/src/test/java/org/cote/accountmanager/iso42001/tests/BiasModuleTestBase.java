package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.engine.BiasModule;
import org.cote.accountmanager.iso42001.engine.TestRunner;
import org.cote.accountmanager.iso42001.engine.TrialPlan;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.NameBank;
import org.cote.accountmanager.iso42001.util.NameBankLoader;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

/**
 * Shared base for the live-LLM bias-module tests (design §11 Phase 3). Builds the ollama
 * chat config against the configured endpoint (DGX Spark, from {@code resource.properties}),
 * loads the name bank, and provides the common run + assertions every per-module test reuses:
 *
 * <ol>
 *   <li><b>Reproducibility</b> — the seeded {@link TrialPlan} is identical for the same seed
 *       (asserted on the plan, never on nondeterministic LLM text).</li>
 *   <li><b>Persistence</b> — the {@code testRun} (and its embedded {@code testResult}) is
 *       re-readable; {@code randomSeedUsed} matches.</li>
 *   <li><b>Logging</b> — {@code rawLogRef} points to a {@code data.data} whose bytes carry the
 *       verbatim request/response capture (endpoint-independent: the request prompt is logged
 *       even when the endpoint is down).</li>
 *   <li><b>Verdict</b> — a verdict is assigned via the resolved {@code ScoringConfig}; when the
 *       endpoint is reachable it is one of PASS/FLAG/FAIL, otherwise ERROR (LLM-dependent
 *       assertions are marked not-run, per the standing honesty rule).</li>
 * </ol>
 *
 * All operations run as the non-admin {@code isoTester}; Admin only created the role users in
 * {@link ISO42001BaseTest}. The {@code testRun} is created in the shared (admin-owned) group via
 * the model-level {@code ISO42001Testers} create role — so it is a genuine RBAC exercise.
 */
public abstract class BiasModuleTestBase extends ISO42001BaseTest {

	protected static final Logger log = LogManager.getLogger(BiasModuleTestBase.class);

	/** Small N to keep the single-threaded ollama endpoint runtime bounded. */
	protected static final int PER_GROUP = 2;
	protected static final int TIER = 1;
	protected static final long SEED = 20260622L;

	private BaseRecord chatConfigCache;
	private NameBank bankCache;

	/** Idempotent ollama chat config owned by isoTester, pointed at the test endpoint. */
	protected BaseRecord chatConfig() {
		if (chatConfigCache == null) {
			chatConfigCache = OlioTestUtil.getOllamaOpenAIConfig(isoTester, "ISO42001 Bias Chat", testProperties);
			assertNotNull("ollama chat config is null", chatConfigCache);
		}
		return chatConfigCache;
	}

	protected NameBank bank() {
		if (bankCache == null) {
			bankCache = new NameBankLoader().loadDefault();
		}
		return bankCache;
	}

	/** Create a testConfig owned by isoTester in the shared group (optionally referencing a profile). */
	protected BaseRecord createTestConfig(int perGroup, int tier, long seed, BaseRecord analysisProfile) {
		BaseRecord tc = newRec(ISO42001ModelNames.MODEL_TEST_CONFIG);
		set(tc, FieldNames.FIELD_NAME, "bias-tc-" + UUID.randomUUID());
		set(tc, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(tc, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(tc, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		set(tc, "moduleId", "BIAS");
		set(tc, "endpointName", "spark-ollama");
		set(tc, "endpointType", "ollama");
		set(tc, "samplesPerGroup", perGroup);
		set(tc, "tier", tier);
		set(tc, "randomSeed", seed);
		if (analysisProfile != null) {
			set(tc, "analysisProfile", analysisProfile);
		}
		BaseRecord created = ioContext.getAccessPoint().create(isoTester, tc);
		assertNotNull("testConfig CREATE as isoTester returned null", created);
		return created;
	}

	/**
	 * Assert the seeded plan is reproducible: two builds with the same seed are equal (and so
	 * are their signatures). Pure — no DB/LLM. This is the reproducibility property the spec
	 * requires.
	 */
	protected void assertReproduciblePlan(BiasModule module, int perGroup, int tier, long seed) {
		TrialPlan p1 = TrialPlan.build(module, bank(), perGroup, tier, seed);
		TrialPlan p2 = TrialPlan.build(module, bank(), perGroup, tier, seed);
		assertEquals("Same seed must yield an identical plan (" + module.testId() + ")", p1, p2);
		assertEquals("Same seed must yield an identical plan signature", p1.signature(), p2.signature());
		assertTrue("Plan must contain interleaved trials", p1.size() > 0);
		log.info("[{}] reproducible plan: {} trials, groups={}", module.testId(), p1.size(), p1.getGroups());
	}

	/**
	 * Run the module end-to-end as isoTester and assert the seed/persistence/logging/verdict
	 * invariants. Returns the persisted testRun. LLM-dependent assertions degrade gracefully
	 * to "not-run" when the endpoint is unreachable.
	 */
	protected BaseRecord runAndAssert(BiasModule module, BaseRecord testConfig, long seed,
			String expectedRawLogSubstring) {
		TestRunner runner = new TestRunner(isoTester, chatConfig());
		BaseRecord testRun = runner.run(testConfig, module, bank());
		assertNotNull("TestRunner.run returned null for " + module.testId(), testRun);

		/// Re-readable as isoTester.
		String runOid = testRun.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord reread = findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_RUN, runOid);
		assertNotNull("testRun not re-readable for " + module.testId(), reread);

		/// randomSeedUsed recorded.
		long seedUsed = (long) reread.get("randomSeedUsed");
		assertEquals("randomSeedUsed mismatch", seed, seedUsed);

		/// Embedded result persisted + verdicted.
		List<BaseRecord> results = reread.get("results");
		assertNotNull("testRun.results is null for " + module.testId(), results);
		assertTrue("Expected 1 embedded testResult, got " + results.size(), results.size() == 1);
		BaseRecord result = results.get(0);
		assertEquals("testResult.testId mismatch", module.testId(), result.get("testId"));
		String verdict = result.get("verdict");
		assertNotNull("verdict is null", verdict);
		assertTrue("verdict must be a known value, got " + verdict,
			Arrays.asList("PASS", "FLAG", "FAIL", "ERROR").contains(verdict));

		/// rawLogRef → data.data with verbatim capture.
		String rawLogRef = reread.get("rawLogRef");
		assertNotNull("rawLogRef is null for " + module.testId(), rawLogRef);
		String rawLog = readRawLog(rawLogRef);
		assertNotNull("rawLog data.data not re-readable", rawLog);
		assertTrue("rawLog must contain verbatim prompt text '" + expectedRawLogSubstring + "'",
			rawLog.contains(expectedRawLogSubstring));

		int totalTrials = (int) reread.get("totalTrials");
		if ("ERROR".equals(verdict)) {
			log.warn("[{}] LLM endpoint UNREACHABLE — verdict=ERROR. Seeded/persistence/logging/RBAC "
				+ "paths exercised; statistical verdict assertions marked NOT-RUN.", module.testId());
			String notes = result.get("notes");
			assertTrue("ERROR result should note unreachable endpoint",
				notes != null && notes.toLowerCase().contains("unreachable"));
		} else {
			log.info("[{}] verdict={} totalTrials={} stat={} p={} effect={} ({})",
				module.testId(), verdict, totalTrials, result.get("testStatistic"),
				result.get("pValue"), result.get("effectSize"), result.get("effectSizeType"));
			assertEquals("effectSizeType mismatch", module.effectType().name(), result.get("effectSizeType"));
			assertTrue("Reachable run must record at least one trial", totalTrials > 0);
		}
		return testRun;
	}

	/** Read the raw-log data.data bytes as a UTF-8 string. */
	protected String readRawLog(String rawLogObjectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, rawLogObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		BaseRecord data = ioContext.getAccessPoint().find(isoTester, q);
		if (data == null) {
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		byte[] bytes = data.get(FieldNames.FIELD_BYTE_STORE);
		return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
	}

	/** Negative RBAC: isoReader (no create role) must be denied creating a testRun in the shared group. */
	protected void assertReaderCannotCreateTestRun() {
		BaseRecord bad = newRec(ISO42001ModelNames.MODEL_TEST_RUN);
		set(bad, FieldNames.FIELD_NAME, "denied-run-" + UUID.randomUUID());
		set(bad, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(bad, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(bad, FieldNames.FIELD_OWNER_ID, (long) isoReader.get(FieldNames.FIELD_ID));
		set(bad, "status", "PENDING");
		BaseRecord created = ioContext.getAccessPoint().create(isoReader, bad);
		org.junit.Assert.assertNull("testRun CREATE by unauthorized isoReader MUST be denied", created);
	}
}
