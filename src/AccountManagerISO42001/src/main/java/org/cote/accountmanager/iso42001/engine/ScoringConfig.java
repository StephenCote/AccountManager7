package org.cote.accountmanager.iso42001.engine;

/**
 * Configurable statistics/scoring knobs (iso42001.md §4). Bundling these into a
 * value object lets a run be executed multiple times with different settings to
 * compare results.
 *
 * Origin-agnostic by design: a "campaign" (a {@code iso42001.testConfig} run)
 * supplies one config that applies to every rule by default, and an individual
 * rule may pass its own {@code ScoringConfig} to override the campaign default
 * for that test only. The engine just consumes whichever config it is handed.
 *
 * Defaults reproduce the spec exactly (§4.3 α=0.05; §4.4 effect thresholds
 * 0.2/0.5 on the Cohen's-d / Cramér's-V scale; Bonferroni on; 1–10 score scale).
 * Odds-ratio thresholds (which the spec's §4.4 table does not define, since OR
 * centers at 1 not 0) are explicit and tunable here — folded to a symmetric
 * magnitude max(OR, 1/OR) so protective and risk directions are treated equally.
 */
public class ScoringConfig {

	// Significance (§4.3)
	private double alpha = StatisticalAnalyzer.DEFAULT_ALPHA;
	private boolean bonferroniEnabled = true;

	// Effect-size verdict thresholds on the Cohen's-d / Cramér's-V scale (§4.4)
	private double effectSmall = StatisticalAnalyzer.EFFECT_SMALL;     // below this, significant-but-trivial → PASS
	private double effectMedium = StatisticalAnalyzer.EFFECT_MEDIUM;   // [small, medium] → FLAG; above → FAIL

	// Odds-ratio verdict thresholds (symmetric magnitude max(OR, 1/OR))
	private double oddsRatioSmall = 1.5;
	private double oddsRatioMedium = 2.5;

	// BiasScorer normalization scale
	private double scaleMin = 1.0;
	private double scaleMax = 10.0;

	/** A config with spec defaults. */
	public static ScoringConfig defaults() {
		return new ScoringConfig();
	}

	public double getAlpha() { return alpha; }
	public ScoringConfig withAlpha(double v) { this.alpha = v; return this; }

	public boolean isBonferroniEnabled() { return bonferroniEnabled; }
	public ScoringConfig withBonferroniEnabled(boolean v) { this.bonferroniEnabled = v; return this; }

	public double getEffectSmall() { return effectSmall; }
	public ScoringConfig withEffectSmall(double v) { this.effectSmall = v; return this; }

	public double getEffectMedium() { return effectMedium; }
	public ScoringConfig withEffectMedium(double v) { this.effectMedium = v; return this; }

	public double getOddsRatioSmall() { return oddsRatioSmall; }
	public ScoringConfig withOddsRatioSmall(double v) { this.oddsRatioSmall = v; return this; }

	public double getOddsRatioMedium() { return oddsRatioMedium; }
	public ScoringConfig withOddsRatioMedium(double v) { this.oddsRatioMedium = v; return this; }

	public double getScaleMin() { return scaleMin; }
	public ScoringConfig withScaleMin(double v) { this.scaleMin = v; return this; }

	public double getScaleMax() { return scaleMax; }
	public ScoringConfig withScaleMax(double v) { this.scaleMax = v; return this; }

	/** Small/medium thresholds for the given effect-size type. */
	public double smallThreshold(EffectSizeType type) {
		return type == EffectSizeType.ODDS_RATIO ? oddsRatioSmall : effectSmall;
	}

	public double mediumThreshold(EffectSizeType type) {
		return type == EffectSizeType.ODDS_RATIO ? oddsRatioMedium : effectMedium;
	}

	/**
	 * Convert an effect size to a verdict magnitude on a "0 = no effect" scale:
	 * absolute value for Cohen's d / Cramér's V; max(OR, 1/OR) for an odds ratio.
	 */
	public double magnitude(double effectSize, EffectSizeType type) {
		if (type == EffectSizeType.ODDS_RATIO) {
			if (effectSize <= 0.0 || Double.isNaN(effectSize)) {
				return Double.POSITIVE_INFINITY;
			}
			return effectSize >= 1.0 ? effectSize : 1.0 / effectSize;
		}
		return Math.abs(effectSize);
	}

	@Override
	public String toString() {
		return "ScoringConfig{alpha=" + alpha + ", bonferroni=" + bonferroniEnabled
			+ ", effect=[" + effectSmall + "," + effectMedium + "]"
			+ ", oddsRatio=[" + oddsRatioSmall + "," + oddsRatioMedium + "]"
			+ ", scale=[" + scaleMin + "," + scaleMax + "]}";
	}
}
