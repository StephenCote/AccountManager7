package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.cote.accountmanager.iso42001.engine.EffectSizeType;
import org.cote.accountmanager.iso42001.engine.ScoringConfig;
import org.cote.accountmanager.iso42001.engine.StatisticalAnalyzer;
import org.cote.accountmanager.iso42001.engine.Verdict;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.ScoringConfigMapper;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Phase 3 P3-4: two DIFFERENT persisted {@code iso42001.analysisProfile}s produce DIFFERENT scored
 * output on the SAME fixed synthetic trial inputs — proving the profile's scoring config actually
 * influences the verdict, end-to-end through the persistence + mapping + engine path.
 *
 * <p>Each profile is created via AccessPoint (isoTester, shared group), re-read from the DB, mapped
 * to a runtime {@link ScoringConfig} via {@link ScoringConfigMapper#fromRecord}, then run over a
 * fixed set of (correctedP, Cohen's-d effect) trials through
 * {@link StatisticalAnalyzer#classifyVerdict(double, double, EffectSizeType, ScoringConfig)}. The
 * two verdict sequences must differ, and the differential must move in the expected direction
 * (conservative profile PASSes more; lenient profile FAILs more).</p>
 *
 * <p>Fully deterministic — the inputs are fixed constants and the scoring is closed-form. No live LLM.</p>
 */
public class TestDifferentialScoring extends ISO42001BaseTest {

	/** Fixed synthetic trials: {correctedPValue, Cohen's-d effect size}. */
	private static final double[][] TRIALS = {
		{ 0.02,  0.30 },
		{ 0.005, 0.45 },
		{ 0.04,  0.25 },
		{ 0.008, 0.70 },
		{ 0.03,  0.15 },
	};

	private final StatisticalAnalyzer analyzer = new StatisticalAnalyzer();

	private BaseRecord createProfile(String name, Consumer<BaseRecord> fieldSetter) {
		BaseRecord rec = newRec(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(rec, FieldNames.FIELD_NAME, name);
		set(rec, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(rec, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(rec, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		fieldSetter.accept(rec);
		BaseRecord created = ioContext.getAccessPoint().create(isoTester, rec);
		assertNotNull("analysisProfile CREATE returned null", created);
		return findByObjectId(isoTester, ISO42001ModelNames.MODEL_ANALYSIS_PROFILE,
			created.get(FieldNames.FIELD_OBJECT_ID));
	}

	private List<Verdict> score(ScoringConfig cfg) {
		List<Verdict> out = new ArrayList<>();
		for (double[] t : TRIALS) {
			out.add(analyzer.classifyVerdict(t[0], t[1], EffectSizeType.COHENS_D, cfg));
		}
		return out;
	}

	private long count(List<Verdict> verdicts, Verdict v) {
		return verdicts.stream().filter(x -> x == v).count();
	}

	@Test
	public void testDifferentProfilesScoreDifferently() {
		/// Conservative profile: high bar to significance (small alpha), wide effect thresholds.
		BaseRecord conservativeRec = createProfile("scoring-conservative-" + UUID.randomUUID(), rec -> {
			set(rec, ScoringConfigMapper.FIELD_ALPHA, 0.01);
			set(rec, ScoringConfigMapper.FIELD_EFFECT_SMALL, 0.4);
			set(rec, ScoringConfigMapper.FIELD_EFFECT_MEDIUM, 0.6);
		});
		/// Lenient profile: easy significance (large alpha), narrow effect thresholds.
		BaseRecord lenientRec = createProfile("scoring-lenient-" + UUID.randomUUID(), rec -> {
			set(rec, ScoringConfigMapper.FIELD_ALPHA, 0.10);
			set(rec, ScoringConfigMapper.FIELD_EFFECT_SMALL, 0.1);
			set(rec, ScoringConfigMapper.FIELD_EFFECT_MEDIUM, 0.3);
		});

		try {
			ScoringConfig conservative = ScoringConfigMapper.fromRecord(conservativeRec);
			ScoringConfig lenient = ScoringConfigMapper.fromRecord(lenientRec);

			/// Guard: the persisted-then-remapped configs carry the intended (non-default) knobs.
			assertEquals(0.01, conservative.getAlpha(), 1e-9);
			assertEquals(0.4, conservative.getEffectSmall(), 1e-9);
			assertEquals(0.6, conservative.getEffectMedium(), 1e-9);
			assertEquals(0.10, lenient.getAlpha(), 1e-9);
			assertEquals(0.1, lenient.getEffectSmall(), 1e-9);
			assertEquals(0.3, lenient.getEffectMedium(), 1e-9);

			List<Verdict> conservativeVerdicts = score(conservative);
			List<Verdict> lenientVerdicts = score(lenient);

			/// Exact per-trial verdicts under each config (closed-form; locks the behavior).
			assertEquals(List.of(Verdict.PASS, Verdict.FLAG, Verdict.PASS, Verdict.FAIL, Verdict.PASS),
				conservativeVerdicts);
			assertEquals(List.of(Verdict.FLAG, Verdict.FAIL, Verdict.FLAG, Verdict.FAIL, Verdict.FLAG),
				lenientVerdicts);

			/// The SAME inputs yield DIFFERENT scored output — the config influences the verdict.
			assertNotEquals("two different scoring profiles must not produce identical verdicts on the same inputs",
				conservativeVerdicts, lenientVerdicts);

			/// Differential moves in the expected direction.
			assertTrue("conservative profile must PASS more than lenient",
				count(conservativeVerdicts, Verdict.PASS) > count(lenientVerdicts, Verdict.PASS));
			assertTrue("lenient profile must FAIL more than conservative",
				count(lenientVerdicts, Verdict.FAIL) > count(conservativeVerdicts, Verdict.FAIL));
		}
		finally {
			ioContext.getAccessPoint().delete(isoTester, conservativeRec);
			ioContext.getAccessPoint().delete(isoTester, lenientRec);
		}
	}
}
