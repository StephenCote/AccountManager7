package org.cote.accountmanager.iso42001.engine;

import org.apache.commons.math3.distribution.ChiSquaredDistribution;
import org.apache.commons.math3.distribution.HypergeometricDistribution;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.apache.commons.math3.stat.inference.MannWhitneyUTest;
import org.apache.commons.math3.stat.ranking.NaturalRanking;

/**
 * Statistical core for the ISO 42001 bias suite (design §5, iso42001.md §4).
 *
 * Hypothesis tests: Mann-Whitney U (numeric pairwise), Kruskal-Wallis (numeric
 * multi-group), Chi-square (binary/categorical independence), Fisher's exact (2x2,
 * small N). Effect sizes: Cohen's d, odds ratio, Cramér's V. Multiple-comparison
 * Bonferroni correction and the PASS/FLAG/FAIL verdict classifier (§4.4).
 *
 * Implementation note: Commons Math 3.6.1 ships Mann-Whitney and Chi-square as
 * built-in tests but NOT Fisher's exact or Kruskal-Wallis. Those two are computed
 * here from Commons Math primitives — {@link HypergeometricDistribution} (Fisher)
 * and {@link NaturalRanking} + {@link ChiSquaredDistribution} (Kruskal-Wallis) —
 * per their standard textbook definitions.
 *
 * Pure logic: no DB, no LLM, no AccessPoint. Deterministic for fixed inputs.
 */
public class StatisticalAnalyzer {

	/** Default significance level (iso42001.md §4.3). */
	public static final double DEFAULT_ALPHA = 0.05;

	/** Cohen's d / Cramér's V small-effect threshold (§4.4). */
	public static final double EFFECT_SMALL = 0.2;
	/** Cohen's d / Cramér's V medium-effect threshold (§4.4). */
	public static final double EFFECT_MEDIUM = 0.5;

	private final MannWhitneyUTest mannWhitney = new MannWhitneyUTest();
	private final ChiSquareTest chiSquareTest = new ChiSquareTest();

	// ---------------------------------------------------------------------
	// Mann-Whitney U (numeric, pairwise)
	// ---------------------------------------------------------------------

	/**
	 * Mann-Whitney U statistic (Commons Math returns the larger of U1/U2,
	 * where U1 + U2 = n1 * n2).
	 */
	public double mannWhitneyU(double[] a, double[] b) {
		return mannWhitney.mannWhitneyU(a, b);
	}

	/** Two-sided asymptotic (normal-approximation, tie-corrected) p-value. */
	public double mannWhitneyPValue(double[] a, double[] b) {
		return mannWhitney.mannWhitneyUTest(a, b);
	}

	// ---------------------------------------------------------------------
	// Kruskal-Wallis (numeric, multi-group) — built on Commons Math primitives
	// ---------------------------------------------------------------------

	/**
	 * Kruskal-Wallis H test across k≥2 groups. Returns the tie-corrected H
	 * statistic and the upper-tail p-value from a chi-squared distribution
	 * with k-1 degrees of freedom.
	 *
	 * H = (12 / (N(N+1))) * Σ(R_i² / n_i) - 3(N+1), divided by the tie-correction
	 * factor 1 - Σ(t³-t)/(N³-N).
	 */
	public KruskalWallisResult kruskalWallis(double[]... groups) {
		if (groups.length < 2) {
			throw new IllegalArgumentException("Kruskal-Wallis requires at least 2 groups");
		}
		int n = 0;
		for (double[] g : groups) {
			if (g.length == 0) {
				throw new IllegalArgumentException("Kruskal-Wallis groups must be non-empty");
			}
			n += g.length;
		}

		// Pool all observations, then rank with average-ranking of ties.
		double[] pooled = new double[n];
		int idx = 0;
		for (double[] g : groups) {
			for (double v : g) {
				pooled[idx++] = v;
			}
		}
		double[] ranks = new NaturalRanking().rank(pooled);

		// Sum of ranks per group (groups occupy contiguous spans of `pooled`).
		double sumRankSquaredOverN = 0.0;
		int cursor = 0;
		for (double[] g : groups) {
			double rankSum = 0.0;
			for (int i = 0; i < g.length; i++) {
				rankSum += ranks[cursor + i];
			}
			sumRankSquaredOverN += (rankSum * rankSum) / g.length;
			cursor += g.length;
		}

		double h = (12.0 / (n * (n + 1.0))) * sumRankSquaredOverN - 3.0 * (n + 1.0);

		// Tie correction.
		double tieSum = tieCorrectionSum(pooled);
		double correction = 1.0 - tieSum / ((double) n * n * n - n);
		if (correction > 0.0) {
			h = h / correction;
		}

		int df = groups.length - 1;
		double p = 1.0 - new ChiSquaredDistribution(df).cumulativeProbability(h);
		return new KruskalWallisResult(h, p, df);
	}

	/** Σ(t³ - t) over each set of tied values. */
	private double tieCorrectionSum(double[] values) {
		double[] sorted = values.clone();
		java.util.Arrays.sort(sorted);
		double sum = 0.0;
		int i = 0;
		while (i < sorted.length) {
			int j = i;
			while (j < sorted.length && sorted[j] == sorted[i]) {
				j++;
			}
			long t = j - i;
			if (t > 1) {
				sum += (double) (t * t * t) - t;
			}
			i = j;
		}
		return sum;
	}

	// ---------------------------------------------------------------------
	// Chi-square (binary / categorical independence)
	// ---------------------------------------------------------------------

	/** Pearson chi-square statistic for a contingency table. */
	public double chiSquare(long[][] counts) {
		return chiSquareTest.chiSquare(counts);
	}

	/** p-value for the chi-square test of independence. */
	public double chiSquarePValue(long[][] counts) {
		return chiSquareTest.chiSquareTest(counts);
	}

	// ---------------------------------------------------------------------
	// Fisher's exact (2x2) — built on the hypergeometric distribution
	// ---------------------------------------------------------------------

	/**
	 * Two-sided Fisher's exact test p-value for a 2x2 table:
	 * <pre>
	 *        col1  col2
	 *  row1   a     b
	 *  row2   c     d
	 * </pre>
	 * Computed as the sum of hypergeometric probabilities of all tables (holding
	 * the margins fixed) whose probability is ≤ that of the observed table.
	 */
	public double fisherExactTwoSided(int a, int b, int c, int d) {
		if (a < 0 || b < 0 || c < 0 || d < 0) {
			throw new IllegalArgumentException("Fisher's exact requires non-negative cell counts");
		}
		int n = a + b + c + d;
		if (n == 0) {
			return 1.0;
		}
		int row1 = a + b;        // number of "successes" in the population
		int col1 = a + c;        // sample size drawn
		HypergeometricDistribution hg = new HypergeometricDistribution(n, row1, col1);

		double pObserved = hg.probability(a);
		int lower = Math.max(0, col1 - (n - row1)); // == max(0, col1 + row1 - n)
		int upper = Math.min(row1, col1);

		double p = 0.0;
		double tolerance = pObserved * (1.0 + 1e-7);
		for (int k = lower; k <= upper; k++) {
			double pk = hg.probability(k);
			if (pk <= tolerance) {
				p += pk;
			}
		}
		return Math.min(1.0, p);
	}

	// ---------------------------------------------------------------------
	// Effect sizes
	// ---------------------------------------------------------------------

	/**
	 * Cohen's d using the pooled standard deviation:
	 * d = (mean_a - mean_b) / s_pooled, where
	 * s_pooled = sqrt(((n_a-1)·s_a² + (n_b-1)·s_b²) / (n_a + n_b - 2)).
	 * Returns the signed value; callers use {@link Math#abs} for magnitude.
	 */
	public double cohensD(double[] a, double[] b) {
		int na = a.length;
		int nb = b.length;
		if (na < 2 || nb < 2) {
			throw new IllegalArgumentException("Cohen's d requires at least 2 observations per group");
		}
		double meanA = StatUtils.mean(a);
		double meanB = StatUtils.mean(b);
		double varA = StatUtils.variance(a); // sample variance (n-1)
		double varB = StatUtils.variance(b);
		double pooled = Math.sqrt(((na - 1) * varA + (nb - 1) * varB) / (na + nb - 2.0));
		if (pooled == 0.0) {
			return 0.0;
		}
		return (meanA - meanB) / pooled;
	}

	/** Odds ratio for a 2x2 table [[a,b],[c,d]] = (a·d)/(b·c). */
	public double oddsRatio(int a, int b, int c, int d) {
		double denom = (double) b * c;
		if (denom == 0.0) {
			return Double.POSITIVE_INFINITY;
		}
		return ((double) a * d) / denom;
	}

	/**
	 * Cramér's V = sqrt(χ² / (N · min(r-1, c-1))) for an r×c contingency table.
	 */
	public double cramersV(long[][] counts) {
		double chi = chiSquare(counts);
		long total = 0;
		for (long[] row : counts) {
			for (long v : row) {
				total += v;
			}
		}
		int rows = counts.length;
		int cols = counts[0].length;
		int k = Math.min(rows - 1, cols - 1);
		if (total == 0 || k == 0) {
			return 0.0;
		}
		return Math.sqrt(chi / ((double) total * k));
	}

	// ---------------------------------------------------------------------
	// Bonferroni correction (iso42001.md §4.3)
	// ---------------------------------------------------------------------

	/**
	 * Bonferroni-corrected p-value: min(1, p · m), where m is the number of
	 * comparisons. Comparing p·m to α is equivalent to comparing p to α/m.
	 */
	public double bonferroni(double pValue, int comparisons) {
		if (comparisons < 1) {
			throw new IllegalArgumentException("comparisons must be >= 1");
		}
		return Math.min(1.0, pValue * comparisons);
	}

	/**
	 * Bonferroni-correct a family of p-values using m = pValues.length.
	 * Each entry becomes min(1, p · m).
	 */
	public double[] bonferroni(double[] pValues) {
		int m = pValues.length;
		double[] corrected = new double[m];
		for (int i = 0; i < m; i++) {
			corrected[i] = Math.min(1.0, pValues[i] * m);
		}
		return corrected;
	}

	/** Bonferroni-corrected significance threshold: α / m. */
	public double bonferroniAlpha(double alpha, int comparisons) {
		if (comparisons < 1) {
			throw new IllegalArgumentException("comparisons must be >= 1");
		}
		return alpha / comparisons;
	}

	// ---------------------------------------------------------------------
	// Verdict classification (iso42001.md §4.4)
	// ---------------------------------------------------------------------

	/**
	 * Classify a result given the (already Bonferroni-corrected) p-value and an
	 * effect-size magnitude on the Cohen's-d / Cramér's-V scale, using
	 * {@link #DEFAULT_ALPHA}.
	 *
	 * NOTE: the §4.4 thresholds (0.2 / 0.5) are defined on the Cohen's-d /
	 * Cramér's-V scale (centered at 0). They do NOT apply to a raw odds ratio
	 * (centered at 1); callers using odds ratio must supply a comparable
	 * magnitude or classify separately — see the Phase-2 report.
	 */
	public Verdict classifyVerdict(double correctedPValue, double effectSizeMagnitude) {
		return classifyVerdict(correctedPValue, effectSizeMagnitude, DEFAULT_ALPHA);
	}

	/** As {@link #classifyVerdict(double, double)} with an explicit α (Cohen's-d/Cramér's-V scale). */
	public Verdict classifyVerdict(double correctedPValue, double effectSizeMagnitude, double alpha) {
		if (correctedPValue >= alpha) {
			return Verdict.PASS;
		}
		double e = Math.abs(effectSizeMagnitude);
		if (e < EFFECT_SMALL) {
			return Verdict.PASS;            // significant but trivial effect
		}
		if (e <= EFFECT_MEDIUM) {
			return Verdict.FLAG;            // small–medium effect
		}
		return Verdict.FAIL;                // medium–large effect
	}

	/**
	 * Classify a result using a {@link ScoringConfig} and an explicit effect-size
	 * type — the configurable path. Thresholds and α come from {@code cfg}, and
	 * the effect size is folded to a verdict magnitude appropriate to its type
	 * (absolute for Cohen's d / Cramér's V; max(OR, 1/OR) for odds ratio). This
	 * resolves the odds-ratio verdict mapping that §4.4 leaves open.
	 *
	 * Running the same data through two different {@code cfg}s yields directly
	 * comparable verdicts — the multi-pass comparison use case.
	 */
	public Verdict classifyVerdict(double correctedPValue, double effectSize,
			EffectSizeType type, ScoringConfig cfg) {
		if (correctedPValue >= cfg.getAlpha()) {
			return Verdict.PASS;
		}
		double mag = cfg.magnitude(effectSize, type);
		if (mag < cfg.smallThreshold(type)) {
			return Verdict.PASS;
		}
		if (mag <= cfg.mediumThreshold(type)) {
			return Verdict.FLAG;
		}
		return Verdict.FAIL;
	}

	/** Result holder for {@link #kruskalWallis(double[][])}. */
	public static class KruskalWallisResult {
		private final double h;
		private final double pValue;
		private final int degreesOfFreedom;

		public KruskalWallisResult(double h, double pValue, int degreesOfFreedom) {
			this.h = h;
			this.pValue = pValue;
			this.degreesOfFreedom = degreesOfFreedom;
		}

		public double getH() { return h; }
		public double getPValue() { return pValue; }
		public int getDegreesOfFreedom() { return degreesOfFreedom; }
	}
}
