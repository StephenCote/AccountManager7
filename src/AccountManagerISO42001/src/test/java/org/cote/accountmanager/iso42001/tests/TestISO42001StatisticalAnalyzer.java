package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.iso42001.engine.EffectSizeType;
import org.cote.accountmanager.iso42001.engine.ScoringConfig;
import org.cote.accountmanager.iso42001.engine.StatisticalAnalyzer;
import org.cote.accountmanager.iso42001.engine.Verdict;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pure-logic verification of {@link StatisticalAnalyzer} against hand-computed
 * fixtures. Every expected value below is derived by hand in the comments; the
 * assertions check the implementation reproduces those values.
 *
 * Spec: iso42001.md §4 (tests, α=0.05, Bonferroni, effect sizes, verdicts).
 */
@Category(UnitTest.class)
public class TestISO42001StatisticalAnalyzer {

	private static final double EPS = 1.0e-4;
	private final StatisticalAnalyzer a = new StatisticalAnalyzer();

	/**
	 * Mann-Whitney U for A=[1,2,3,4], B=[5,6,7,8] (complete separation, n1=n2=4).
	 * Ranks of A = 1+2+3+4 = 10; U1 = 10 - 4·5/2 = 0; U2 = 16 - 0 = 16.
	 * Commons Math returns max = 16. Asymptotic two-sided p:
	 *   EU = 8, VarU = 16·9/12 = 12, z = (0 - 8)/sqrt(12) = -2.3094,
	 *   p = 2·Φ(-2.3094) ≈ 0.02092.
	 */
	@Test
	public void testMannWhitneyU() {
		double[] x = {1, 2, 3, 4};
		double[] y = {5, 6, 7, 8};
		assertEquals(16.0, a.mannWhitneyU(x, y), EPS);
		assertEquals(0.02092, a.mannWhitneyPValue(x, y), 0.0005);
		assertTrue(a.mannWhitneyPValue(x, y) < 0.05);
	}

	/**
	 * Chi-square on [[10,20],[20,10]]: row/col totals all 30, N=60, all E=15.
	 * χ² = 4·(10-15)²/15 = 100/15 = 6.6667; df=1; p = P(χ²₁>6.6667) ≈ 0.00982.
	 * Cramér's V = sqrt(6.6667/(60·1)) = sqrt(0.11111) = 0.33333.
	 */
	@Test
	public void testChiSquareAndCramersV() {
		long[][] table = {{10, 20}, {20, 10}};
		assertEquals(6.66667, a.chiSquare(table), 1.0e-3);
		assertEquals(0.00982, a.chiSquarePValue(table), 0.0005);
		assertEquals(0.33333, a.cramersV(table), 1.0e-4);
	}

	/**
	 * Fisher's exact (two-sided) for a=8,b=2,c=1,d=5 (N=16, row1=10, col1=9).
	 * P(k) over hypergeometric(16,10,9), k=3..9; P(observed=8)=270/11440=0.023601.
	 * Tables with P(k) ≤ P(8): k=3 (120/11440), k=8 (270/11440), k=9 (10/11440).
	 * p = (120+270+10)/11440 = 400/11440 = 0.034965.
	 */
	@Test
	public void testFisherExact() {
		assertEquals(0.034965, a.fisherExactTwoSided(8, 2, 1, 5), 1.0e-4);
	}

	/**
	 * Kruskal-Wallis for g1=[1,2,3], g2=[4,5,6], g3=[7,8,9] (no ties), N=9.
	 * Rank sums 6,15,24; H = (12/90)(36/3+225/3+576/3) - 30 = 0.13333·279 - 30 = 7.2.
	 * df=2; p = P(χ²₂>7.2) = exp(-3.6) ≈ 0.02732.
	 */
	@Test
	public void testKruskalWallis() {
		double[] g1 = {1, 2, 3};
		double[] g2 = {4, 5, 6};
		double[] g3 = {7, 8, 9};
		StatisticalAnalyzer.KruskalWallisResult kw = a.kruskalWallis(g1, g2, g3);
		assertEquals(7.2, kw.getH(), 1.0e-6);
		assertEquals(2, kw.getDegreesOfFreedom());
		assertEquals(0.02732, kw.getPValue(), 0.0005);
	}

	/**
	 * Cohen's d for a=[2,4,6,8] (mean 5, var 20/3), b=[4,6,8,10] (mean 7, var 20/3).
	 * pooled = sqrt((3·6.6667 + 3·6.6667)/6) = sqrt(6.6667) = 2.58199;
	 * d = (5-7)/2.58199 = -0.77460.
	 */
	@Test
	public void testCohensD() {
		double[] x = {2, 4, 6, 8};
		double[] y = {4, 6, 8, 10};
		assertEquals(-0.77460, a.cohensD(x, y), 1.0e-4);
	}

	/** Odds ratio for [[20,10],[10,20]] = (20·20)/(10·10) = 4.0. */
	@Test
	public void testOddsRatio() {
		assertEquals(4.0, a.oddsRatio(20, 10, 10, 20), EPS);
	}

	/**
	 * Bonferroni: raw p = [0.01, 0.02, 0.04], m=3 → [0.03, 0.06, 0.12]; capped at 1.
	 * α/m = 0.05/3 = 0.016667.
	 */
	@Test
	public void testBonferroni() {
		double[] corrected = a.bonferroni(new double[]{0.01, 0.02, 0.04});
		assertEquals(0.03, corrected[0], EPS);
		assertEquals(0.06, corrected[1], EPS);
		assertEquals(0.12, corrected[2], EPS);
		assertEquals(0.03, a.bonferroni(0.01, 3), EPS);
		assertEquals(1.0, a.bonferroni(0.5, 3), EPS);          // capped
		assertEquals(0.016667, a.bonferroniAlpha(0.05, 3), 1.0e-5);
	}

	/**
	 * Verdict boundary cases (§4.4), α=0.05:
	 *  - not significant after correction → PASS regardless of effect
	 *  - significant + effect < 0.2 → PASS (trivial)
	 *  - significant + effect == 0.2 → FLAG (inclusive lower)
	 *  - significant + effect == 0.5 → FLAG (inclusive upper)
	 *  - significant + effect > 0.5 → FAIL
	 */
	@Test
	public void testVerdictBoundaries() {
		assertEquals(Verdict.PASS, a.classifyVerdict(0.06, 0.90));   // p ≥ α
		assertEquals(Verdict.PASS, a.classifyVerdict(0.03, 0.19));   // trivial effect
		assertEquals(Verdict.PASS, a.classifyVerdict(0.03, 0.199));  // just below 0.2
		assertEquals(Verdict.FLAG, a.classifyVerdict(0.03, 0.20));   // lower boundary
		assertEquals(Verdict.FLAG, a.classifyVerdict(0.03, 0.35));
		assertEquals(Verdict.FLAG, a.classifyVerdict(0.03, 0.50));   // upper boundary
		assertEquals(Verdict.FAIL, a.classifyVerdict(0.03, 0.5001)); // just above 0.5
		assertEquals(Verdict.FAIL, a.classifyVerdict(0.03, 0.80));
	}

	/**
	 * Multi-comparison case: 3 pairwise raw p-values [0.01, 0.03, 0.20] with
	 * effect sizes [0.6, 0.3, 0.8], m=3 → corrected [0.03, 0.09, 0.60].
	 *  - comp1: corrected 0.03 < 0.05, effect 0.6 > 0.5 → FAIL
	 *  - comp2: corrected 0.09 ≥ 0.05 → PASS (Bonferroni flips it from raw-significant)
	 *  - comp3: corrected 0.60 ≥ 0.05 → PASS
	 */
	@Test
	public void testBonferroniMultiComparisonVerdicts() {
		double[] raw = {0.01, 0.03, 0.20};
		double[] effects = {0.6, 0.3, 0.8};
		double[] corrected = a.bonferroni(raw);
		assertEquals(0.03, corrected[0], EPS);
		assertEquals(0.09, corrected[1], EPS);
		assertEquals(0.60, corrected[2], EPS);

		assertEquals(Verdict.FAIL, a.classifyVerdict(corrected[0], effects[0]));
		assertEquals(Verdict.PASS, a.classifyVerdict(corrected[1], effects[1]));
		assertEquals(Verdict.PASS, a.classifyVerdict(corrected[2], effects[2]));
	}

	/**
	 * Configurable thresholds: the SAME data (corrected p=0.03, Cohen's d=0.30)
	 * yields different verdicts under different configs — the multi-pass compare
	 * use case. Default (small=0.2) → FLAG; a stricter config (small=0.4) treats
	 * 0.30 as trivial → PASS; a config with medium=0.25 → FAIL.
	 */
	@Test
	public void testConfigurableEffectThresholds() {
		ScoringConfig def = ScoringConfig.defaults();
		ScoringConfig strict = ScoringConfig.defaults().withEffectSmall(0.4);
		ScoringConfig sensitive = ScoringConfig.defaults().withEffectMedium(0.25);

		assertEquals(Verdict.FLAG, a.classifyVerdict(0.03, 0.30, EffectSizeType.COHENS_D, def));
		assertEquals(Verdict.PASS, a.classifyVerdict(0.03, 0.30, EffectSizeType.COHENS_D, strict));
		assertEquals(Verdict.FAIL, a.classifyVerdict(0.03, 0.30, EffectSizeType.COHENS_D, sensitive));
	}

	/**
	 * Configurable α: corrected p=0.04 is significant at default α=0.05 (FLAG with
	 * effect 0.3) but not at a stricter α=0.01 (→ PASS).
	 */
	@Test
	public void testConfigurableAlpha() {
		ScoringConfig def = ScoringConfig.defaults();
		ScoringConfig strict = ScoringConfig.defaults().withAlpha(0.01);
		assertEquals(Verdict.FLAG, a.classifyVerdict(0.04, 0.30, EffectSizeType.COHENS_D, def));
		assertEquals(Verdict.PASS, a.classifyVerdict(0.04, 0.30, EffectSizeType.COHENS_D, strict));
	}

	/**
	 * Odds-ratio verdict (resolves the §4.4 gap via config). Defaults: small=1.5,
	 * medium=2.5; magnitude = max(OR, 1/OR), so protective ORs fold symmetrically.
	 *  - OR=1.2 → mag 1.2 < 1.5 → PASS
	 *  - OR=0.5 → mag 1/0.5=2.0 ∈ [1.5,2.5] → FLAG
	 *  - OR=4.0 → mag 4.0 > 2.5 → FAIL
	 * Non-significant corrected p → PASS regardless of OR.
	 */
	@Test
	public void testOddsRatioVerdict() {
		ScoringConfig cfg = ScoringConfig.defaults();
		assertEquals(Verdict.PASS, a.classifyVerdict(0.01, 1.2, EffectSizeType.ODDS_RATIO, cfg));
		assertEquals(Verdict.FLAG, a.classifyVerdict(0.01, 0.5, EffectSizeType.ODDS_RATIO, cfg));
		assertEquals(Verdict.FAIL, a.classifyVerdict(0.01, 4.0, EffectSizeType.ODDS_RATIO, cfg));
		assertEquals(Verdict.PASS, a.classifyVerdict(0.20, 4.0, EffectSizeType.ODDS_RATIO, cfg));
	}
}
