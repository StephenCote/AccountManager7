package org.cote.accountmanager.iso42001.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.scoring.BiasScorer;
import org.cote.accountmanager.iso42001.scoring.LexicalAnalyzer;
import org.cote.accountmanager.iso42001.scoring.ParsedResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Executes a {@link BiasModule} against the live endpoint per a {@link TrialPlan} and
 * produces a single verdicted {@code iso42001.testResult} record (design §5.4): name banks →
 * prompts → chat → {@link BiasScorer}/{@link LexicalAnalyzer} → per-group aggregation →
 * {@link StatisticalAnalyzer#classifyVerdict} under the resolved {@link ScoringConfig}.
 *
 * <p>Trials run in the plan's interleaved order (one per group, round-robin — §5.5). The
 * verbatim request/response capture is accumulated on the {@link TestExecutor} base; the
 * {@link TestRunner} persists it as the run's {@code rawLogRef}.</p>
 */
public class BiasTestExecutor extends TestExecutor {

	private static final Logger logger = LogManager.getLogger(BiasTestExecutor.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public BiasTestExecutor(BaseRecord user, BaseRecord chatConfig) {
		super(user, chatConfig);
	}

	/**
	 * Run the module per the plan under {@code cfg} and return an unpersisted
	 * {@code iso42001.testResult} (to be embedded in a {@code testRun.results} list).
	 */
	public BaseRecord execute(BiasModule module, TrialPlan plan, ScoringConfig cfg) {
		BiasScorer scorer = new BiasScorer(cfg);
		LexicalAnalyzer lex = LexicalAnalyzer.fromClasspath();
		StatisticalAnalyzer stat = new StatisticalAnalyzer();

		List<String> groups = plan.getGroups();
		/// Per-group accumulators.
		Map<String, List<Double>> numeric = new LinkedHashMap<>();
		Map<String, int[]> binary = new LinkedHashMap<>();   // [favorable, unfavorable]
		Map<String, Integer> refusals = new LinkedHashMap<>();
		Map<String, Integer> seen = new LinkedHashMap<>();    // reachable responses per group
		for (String g : groups) {
			numeric.put(g, new ArrayList<>());
			binary.put(g, new int[2]);
			refusals.put(g, 0);
			seen.put(g, 0);
		}

		for (TrialSpec spec : plan.getTrials()) {
			String content;
			if (spec.getTier() == 2) {
				content = runTier2(module.tier2Turns(spec.getGroup(), spec.getToken()), spec);
			} else {
				content = runTier1(module.tier1System(),
					module.tier1User(spec.getGroup(), spec.getToken()), spec);
			}
			ParsedResponse parsed = scorer.parse(content);
			if (content != null) {
				seen.merge(spec.getGroup(), 1, Integer::sum);
			}
			if (parsed.isRefusal()) {
				refusals.merge(spec.getGroup(), 1, Integer::sum);
			}
			double obs = module.observe(spec.getGroup(), spec.getToken(), parsed, content, scorer, lex);
			if (Double.isNaN(obs)) {
				continue;
			}
			if (module.kind() == AnalysisKind.NUMERIC) {
				numeric.get(spec.getGroup()).add(obs);
			} else {
				int[] c = binary.get(spec.getGroup());
				if (obs >= 1.0) {
					c[0]++;     // favorable / refused
				} else {
					c[1]++;     // unfavorable / engaged
				}
			}
		}

		return buildResult(module, plan, cfg, stat, groups, numeric, binary, refusals, seen);
	}

	private BaseRecord buildResult(BiasModule module, TrialPlan plan, ScoringConfig cfg,
			StatisticalAnalyzer stat, List<String> groups,
			Map<String, List<Double>> numeric, Map<String, int[]> binary,
			Map<String, Integer> refusals, Map<String, Integer> seen) {

		String gA = groups.get(0);
		String gB = groups.size() > 1 ? groups.get(1) : groups.get(0);

		double pValue = 1.0;
		double effect = (module.effectType() == EffectSizeType.ODDS_RATIO) ? 1.0 : 0.0;
		String statStr;
		String notes = "";
		String verdict;

		Map<String, Object> groupResults = new LinkedHashMap<>();

		if (reachableCalls == 0) {
			/// Endpoint unreachable for every trial — record ERROR, do not fabricate stats.
			verdict = "ERROR";
			notes = "Endpoint unreachable: 0/" + attemptedCalls + " LLM calls returned content.";
			statStr = "n/a (endpoint unreachable)";
		} else if (module.kind() == AnalysisKind.NUMERIC) {
			double[] a = toArray(numeric.get(gA));
			double[] b = toArray(numeric.get(gB));
			groupResults.put(gA, summarizeNumeric(numeric.get(gA), seen.get(gA), refusals.get(gA)));
			groupResults.put(gB, summarizeNumeric(numeric.get(gB), seen.get(gB), refusals.get(gB)));
			if (a.length >= 2 && b.length >= 2) {
				pValue = stat.mannWhitneyPValue(a, b);
				effect = stat.cohensD(a, b);
				statStr = "Mann-Whitney U=" + round(stat.mannWhitneyU(a, b)) + " (n=" + a.length + "/" + b.length + ")";
			} else {
				notes = "Insufficient numeric observations for MWU (n=" + a.length + "/" + b.length + "); treated as no-difference.";
				statStr = "Mann-Whitney n/a (n=" + a.length + "/" + b.length + ")";
			}
			double corrected = cfg.isBonferroniEnabled() ? stat.bonferroni(pValue, 1) : pValue;
			verdict = stat.classifyVerdict(corrected, effect, module.effectType(), cfg).name();
			return assemble(module, plan, cfg, pValue, corrected, effect, statStr, verdict, notes,
				groupResults, refusals, seen, groups);
		} else {
			int[] ca = binary.get(gA);
			int[] cb = binary.get(gB);
			groupResults.put(gA, summarizeBinary(ca, seen.get(gA), refusals.get(gA)));
			groupResults.put(gB, summarizeBinary(cb, seen.get(gB), refusals.get(gB)));
			boolean rowsOk = (ca[0] + ca[1]) > 0 && (cb[0] + cb[1]) > 0;
			boolean colsVary = (ca[0] + cb[0]) > 0 && (ca[1] + cb[1]) > 0;
			if (rowsOk && colsVary) {
				long[][] table = { { ca[0], ca[1] }, { cb[0], cb[1] } };
				pValue = stat.chiSquarePValue(table);
				if (module.effectType() == EffectSizeType.ODDS_RATIO) {
					effect = stat.oddsRatio(ca[0], ca[1], cb[0], cb[1]);
					statStr = "Chi-square=" + round(stat.chiSquare(table)) + ", OR=" + round(effect);
				} else {
					effect = stat.cramersV(table);
					statStr = "Chi-square=" + round(stat.chiSquare(table)) + ", Cramer's V=" + round(effect);
				}
			} else {
				notes = "Insufficient/degenerate 2x2 (A=[" + ca[0] + "," + ca[1] + "], B=[" + cb[0] + "," + cb[1]
					+ "]); treated as no-difference.";
				statStr = "Chi-square n/a";
			}
			double corrected = cfg.isBonferroniEnabled() ? stat.bonferroni(pValue, 1) : pValue;
			verdict = stat.classifyVerdict(corrected, effect, module.effectType(), cfg).name();
			return assemble(module, plan, cfg, pValue, corrected, effect, statStr, verdict, notes,
				groupResults, refusals, seen, groups);
		}

		/// Unreachable-endpoint branch falls through to here.
		double corrected = pValue;
		return assemble(module, plan, cfg, pValue, corrected, effect, statStr, verdict, notes,
			groupResults, refusals, seen, groups);
	}

	private BaseRecord assemble(BiasModule module, TrialPlan plan, ScoringConfig cfg,
			double pValue, double corrected, double effect, String statStr, String verdict,
			String notes, Map<String, Object> groupResults, Map<String, Integer> refusals,
			Map<String, Integer> seen, List<String> groups) {
		try {
			BaseRecord r = RecordFactory.model(ISO42001ModelNames.MODEL_TEST_RESULT).newInstance();
			r.set(FieldNames.FIELD_NAME, module.testId() + "-t" + plan.getTier());
			r.set("testId", module.testId());
			r.set("testModule", module.moduleId());
			r.set("tier", plan.getTier());
			r.set("protectedClass", module.protectedClass());
			r.set("samplesPerGroup", plan.getSamplesPerGroup());
			r.set("groupResults", truncate(toJson(groupResults), 4096));
			r.set("testStatistic", truncate(statStr, 256));
			r.set("pValue", pValue);
			r.set("correctedPValue", corrected);
			r.set("effectSize", sanitize(effect));
			r.set("effectSizeType", module.effectType().name());
			r.set("verdict", verdict);
			Map<String, Object> refRates = new LinkedHashMap<>();
			for (String g : groups) {
				int n = seen.getOrDefault(g, 0);
				refRates.put(g, n == 0 ? 0.0 : (double) refusals.getOrDefault(g, 0) / n);
			}
			r.set("refusalRates", truncate(toJson(refRates), 1024));
			r.set("notes", truncate(notes, 4096));
			return r;
		} catch (Exception e) {
			logger.error("Failed to build testResult for " + module.testId(), e);
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Summaries / helpers
	// ------------------------------------------------------------------

	private Map<String, Object> summarizeNumeric(List<Double> values, int seen, int refusals) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("n", values.size());
		m.put("responses", seen);
		m.put("refusals", refusals);
		double sum = 0.0;
		for (double v : values) {
			sum += v;
		}
		m.put("mean", values.isEmpty() ? null : round(sum / values.size()));
		return m;
	}

	private Map<String, Object> summarizeBinary(int[] counts, int seen, int refusals) {
		Map<String, Object> m = new LinkedHashMap<>();
		int total = counts[0] + counts[1];
		m.put("favorable", counts[0]);
		m.put("unfavorable", counts[1]);
		m.put("responses", seen);
		m.put("refusals", refusals);
		m.put("favorableRate", total == 0 ? null : round((double) counts[0] / total));
		return m;
	}

	private static double[] toArray(List<Double> list) {
		double[] a = new double[list.size()];
		for (int i = 0; i < list.size(); i++) {
			a[i] = list.get(i);
		}
		return a;
	}

	private static double round(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return v;
		}
		return Math.round(v * 10000.0) / 10000.0;
	}

	/** Replace non-finite effect sizes (e.g. odds ratio with a zero cell) with a storable sentinel. */
	private static double sanitize(double v) {
		if (Double.isNaN(v)) {
			return 0.0;
		}
		if (Double.isInfinite(v)) {
			return v > 0 ? 9999.0 : -9999.0;
		}
		return round(v);
	}

	private static String toJson(Object o) {
		try {
			return MAPPER.writeValueAsString(o);
		} catch (Exception e) {
			return "{}";
		}
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max);
	}
}
