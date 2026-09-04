package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.iso42001.engine.EffectSizeType;
import org.cote.accountmanager.iso42001.engine.ScoringConfig;
import org.cote.accountmanager.iso42001.engine.StatisticalAnalyzer;
import org.cote.accountmanager.iso42001.engine.TestRunner;
import org.cote.accountmanager.iso42001.engine.Verdict;
import org.cote.accountmanager.iso42001.engine.modules.AttrModule;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.NameBank;
import org.cote.accountmanager.iso42001.util.NameBankLoader;
import org.cote.accountmanager.iso42001.util.ScoringConfigMapper;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * P3-4 LIVE — differential scoring over a REAL proxy-driven run.
 *
 * <p>Task (2): prove two different persisted {@code iso42001.analysisProfile}s produce DIFFERENT
 * scored output over the SAME real, proxy-driven run — not a reconstruction, and not two separate
 * runs (which would confound scoring differences with LLM nondeterminism).</p>
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Drive ONE real {@link TestRunner} bias run ({@link AttrModule}, NUMERIC / Cohen's d) through
 *       the OPENAI_COMPAT LiteLLM connection at {@code http://127.0.0.1:4000} (Azure upstream). This
 *       makes real LLM calls and produces a persisted {@code testRun} whose embedded
 *       {@code testResult}(s) carry the run's REAL {@code correctedPValue} / {@code effectSize} /
 *       {@code effectSizeType}.</li>
 *   <li>Persist a PERMISSIVE and an AGGRESSIVE {@code analysisProfile} via AccessPoint, re-read them,
 *       and map each to a {@link ScoringConfig} through the production {@link ScoringConfigMapper}.</li>
 *   <li>Re-score the SAME raw per-result statistics through the engine's real scoring entry point
 *       ({@link StatisticalAnalyzer#classifyVerdict(double, double, EffectSizeType, ScoringConfig)})
 *       under each config, and assert the two verdict sequences DIFFER.</li>
 * </ol>
 *
 * <p>The two profiles are deliberately at the extremes of the {@code alpha} / effect-threshold knobs
 * so the differential is robust to whatever specific (p, effect) the nondeterministic run yields:
 * PERMISSIVE ({@code alpha=0}) treats nothing as significant → every result PASSes; AGGRESSIVE
 * ({@code alpha=1}, effect thresholds ~0) treats every real comparison as significant and any
 * non-trivial effect as a failure. Any result with {@code correctedPValue < 1.0} and a non-zero
 * effect therefore scores PASS under one profile and FAIL/FLAG under the other. These are legitimate
 * profile values; the point is that the profile — not the data — decides the verdict.</p>
 *
 * <p>Gating: runs only with {@code -Diso.live.langfuse=true} AND the LiteLLM proxy answering 200 on
 * its liveliness endpoint (otherwise Skipped — the default suite never reaches Azure). Every record is
 * created as the non-admin {@code isoTester} role user. Only the LiteLLM master key (a committed
 * non-secret test placeholder) is used as the Bearer token; the real Azure key lives only in the proxy.</p>
 */
@Category(IntegrationTest.class)
public class TestDifferentialScoringLiveRun extends ISO42001BaseTest {

	private static final String DEFAULT_LITELLM = "http://127.0.0.1:4000";
	private static final String LITELLM_MODEL = "gpt-5.6-terra";
	private static final String DEFAULT_MASTER_KEY = "sk-am7-litellm-test";

	private static String envOr(String key, String fallback) {
		String v = System.getenv(key);
		return (v != null && !v.isBlank()) ? v.trim() : fallback;
	}

	private String litellmServer() { return envOr("LITELLM_SERVER", DEFAULT_LITELLM); }
	private String masterKey()     { return envOr("LITELLM_MASTER_KEY", DEFAULT_MASTER_KEY); }

	@Test
	@Category(LiveTest.class)
	public void testTwoProfilesDifferOverSameRealRun() throws Exception {
		assumeTrue("Set -Diso.live.langfuse=true to run the live differential-scoring leg",
			Boolean.getBoolean("iso.live.langfuse"));
		assumeStackLive();

		OlioModelNames.use(); // idempotent; ensure chatConfig/connection models are registered

		// --- Build the OPENAI_COMPAT (LiteLLM) chat config IN-MEMORY (Chat re-queries the persisted
		//     connection by FK id; the chatConfig row itself is never written — matches the round-trip
		//     and metrics-report live tests, sidestepping any stale serviceType column). ---
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord conn = OlioTestUtil.getCreateConnection(isoTester, "ISO P34 LiteLLM " + nonce,
			litellmServer(), masterKey(), 120);
		assertNotNull("LiteLLM connection create returned null", conn);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "ISO P34 OPENAI_COMPAT " + nonce);
		BaseRecord chatConfig = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG,
			isoTester, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", chatConfig);
		chatConfig.set("serviceType", LLMServiceEnumType.OPENAI_COMPAT);
		chatConfig.set("connection", conn);
		chatConfig.set("model", LITELLM_MODEL);

		// --- ONE real run: AttrModule (NUMERIC → Mann-Whitney U + Cohen's d). defaultRaceMaleGroups is
		//     exactly TWO groups, so samplesPerGroup=8 → 16 real LLM calls and, after NaN parses, comfortably
		//     ≥2 parsed 1-10 trait means per group — the minimum Cohen's d needs, and enough that the single
		//     tier-1 result is a genuine comparison (p<1, effect≠0) rather than a degenerate no-difference. ---
		BaseRecord tc = createTestConfig(8, 1, 20260903L);
		NameBank bank = new NameBankLoader().loadDefault();
		BaseRecord run = new TestRunner(isoTester, chatConfig).run(tc, new AttrModule(), bank);
		assertNotNull("TestRunner.run returned null (RBAC/create failure)", run);

		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord reread = findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_RUN, runOid);
		assertNotNull("run not re-readable", reread);
		List<BaseRecord> results = reread.get("results");
		assertNotNull("run.results null", results);
		assertTrue("run must embed at least one result", !results.isEmpty());

		// --- Persist two REAL analysisProfiles at the extremes and map them via the production mapper. ---
		// PERMISSIVE: alpha=0 → correctedP>=0 is always true → every result PASSes (nothing "significant").
		// AGGRESSIVE: alpha=1 → any correctedP<1 is "significant", and effect thresholds ~0 → any non-trivial
		// effect FLAGs/FAILs. Both are legitimate profile values; the point is the profile — not the raw
		// statistics — decides the verdict, which is exactly what P3-4 must demonstrate.
		BaseRecord permissiveRec = createProfile("P34-permissive-" + nonce, 0.0, 0.2, 0.5, 1.5, 2.5);
		BaseRecord aggressiveRec = createProfile("P34-aggressive-" + nonce, 1.0, 0.0001, 0.0002, 1.00001, 1.00002);
		ScoringConfig permissive = ScoringConfigMapper.fromRecord(
			findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE, permissiveRec.get(FieldNames.FIELD_OBJECT_ID)));
		ScoringConfig aggressive = ScoringConfigMapper.fromRecord(
			findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE, aggressiveRec.get(FieldNames.FIELD_OBJECT_ID)));
		assertNotNull("permissive config null", permissive);
		assertNotNull("aggressive config null", aggressive);

		// --- Re-score the SAME raw results through the engine's real scoring path under each config. ---
		StatisticalAnalyzer analyzer = new StatisticalAnalyzer();
		List<Verdict> permissiveVerdicts = new ArrayList<>();
		List<Verdict> aggressiveVerdicts = new ArrayList<>();
		boolean sawRealComparison = false;

		for (BaseRecord r : results) {
			String origVerdict = r.get("verdict");
			double corrected = numeric(r, "correctedPValue", 1.0);
			double effect = numeric(r, "effectSize", 0.0);
			EffectSizeType type = EffectSizeType.valueOf((String) r.get("effectSizeType"));

			// The stack was probed live; an ERROR verdict means the OPENAI_COMPAT calls never reached the
			// endpoint — fail loudly rather than score a fabricated no-difference result.
			assertFalse("LLM endpoint unreachable through OPENAI_COMPAT (verdict ERROR) — cannot prove "
				+ "differential scoring; check LiteLLM->Azure", "ERROR".equals(origVerdict));

			Verdict vPermissive = analyzer.classifyVerdict(corrected, effect, type, permissive);
			Verdict vAggressive = analyzer.classifyVerdict(corrected, effect, type, aggressive);
			permissiveVerdicts.add(vPermissive);
			aggressiveVerdicts.add(vAggressive);

			// A comparison the profile can actually flip: aggressive (alpha=1) needs correctedP<1 to treat it
			// as significant, and |effect| above its (tiny) small threshold to score it non-PASS. Matching the
			// guard to the aggressive effectSmall (0.0001) makes the differential a guaranteed consequence.
			boolean realComparison = corrected < 1.0 && Math.abs(effect) > 0.0001;
			if (realComparison) {
				sawRealComparison = true;
			}
			logger.info("[P34][diff] result=" + r.get("testId") + " corrected_p=" + corrected
				+ " effect=" + effect + " [" + type + "] engineVerdict=" + origVerdict
				+ " -> permissive=" + vPermissive + " aggressive=" + vAggressive
				+ " (realComparison=" + realComparison + ")");
		}

		// A meaningful differential requires the real run to have produced at least one genuine
		// statistical comparison (p<1.0, effect!=0). With samplesPerGroup=5 against a real model this is
		// effectively certain; if it were ever degenerate this asserts loudly rather than passing hollowly.
		assertTrue("Real run produced no genuine comparison (all results p>=1.0 or effect==0) — cannot "
			+ "demonstrate a scoring differential over this run", sawRealComparison);

		// The core claim: the SAME real per-result statistics score to DIFFERENT verdicts under the two
		// persisted profiles. Extreme-but-legitimate configs make this robust to the run's specific values.
		assertFalse("Two different analysisProfiles produced IDENTICAL verdicts over the same real run "
			+ "(permissive=" + permissiveVerdicts + " aggressive=" + aggressiveVerdicts + ") — scoring is "
			+ "not actually driven by the profile", permissiveVerdicts.equals(aggressiveVerdicts));

		// And, concretely, every genuine comparison must PASS under permissive yet NOT PASS under
		// aggressive — the direction the profile knobs are supposed to move the verdict.
		for (int i = 0; i < results.size(); i++) {
			double corrected = numeric(results.get(i), "correctedPValue", 1.0);
			double effect = numeric(results.get(i), "effectSize", 0.0);
			if (corrected < 1.0 && Math.abs(effect) > 0.0001) {
				assertTrue("permissive (alpha=0) must PASS result " + i + " (p=" + corrected + ")",
					permissiveVerdicts.get(i) == Verdict.PASS);
				assertTrue("aggressive (alpha=1, effect~0) must NOT PASS result " + i + " (effect="
					+ effect + ")", aggressiveVerdicts.get(i) != Verdict.PASS);
			}
		}

		logger.info("[P34][diff] PASS — same real run " + runOid + " scored permissive=" + permissiveVerdicts
			+ " vs aggressive=" + aggressiveVerdicts + " (verdicts differ; profile drives the scoring).");
	}

	// ── Helpers (IN-TEST only) ────────────────────────────────────────────────

	/** Create a BIAS testConfig owned by isoTester in the shared group (mirrors the metrics-report test). */
	private BaseRecord createTestConfig(int perGroup, int tier, long seed) {
		BaseRecord tc = newRec(ISO42001ModelNames.MODEL_TEST_CONFIG);
		set(tc, FieldNames.FIELD_NAME, "p34-tc-" + UUID.randomUUID());
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

	/** Persist an analysisProfile with the given scoring knobs as isoTester in the shared group. */
	private BaseRecord createProfile(String name, double alpha, double effectSmall, double effectMedium,
			double orSmall, double orMedium) {
		BaseRecord p = newRec(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(p, FieldNames.FIELD_NAME, name);
		set(p, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(p, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(p, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		set(p, "alpha", alpha);
		set(p, "bonferroniEnabled", true);
		set(p, "effectSmall", effectSmall);
		set(p, "effectMedium", effectMedium);
		set(p, "oddsRatioSmall", orSmall);
		set(p, "oddsRatioMedium", orMedium);
		BaseRecord created = ioContext.getAccessPoint().create(isoTester, p);
		assertNotNull("analysisProfile CREATE as isoTester returned null", created);
		return created;
	}

	private static double numeric(BaseRecord r, String field, double dflt) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).doubleValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return dflt;
	}

	/** Skip (not fail) when the LiteLLM proxy is not up — never reach Azure by default. */
	private void assumeStackLive() {
		boolean litellm = httpOk(litellmServer() + "/health/liveliness");
		if (!litellm) {
			logger.warn("[P34][live] LiteLLM not live at " + litellmServer() + " — SKIPPING.");
		}
		assumeTrue("LiteLLM liveliness not 200 (" + litellmServer() + ")", litellm);
	}

	private boolean httpOk(String url) {
		try {
			HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(5)).build();
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET()
				.timeout(Duration.ofSeconds(8)).build();
			return c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
		} catch (Exception e) {
			logger.warn("[P34][live] probe failed " + url + " : " + e.getMessage());
			return false;
		}
	}
}
