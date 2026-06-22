package org.cote.accountmanager.iso42001.engine;

/**
 * Immutable result of a single statistical comparison: the test name + statistic,
 * raw and (optionally) Bonferroni-corrected p-values, the effect size + its type,
 * and the resulting {@link Verdict}.
 *
 * Maps to the AM7 {@code iso42001.testResult} model fields (design §2.4):
 * {@code testStatistic}, {@code pValue}, {@code correctedPValue}, {@code effectSize},
 * {@code effectSizeType}, {@code verdict}.
 */
public class StatResult {

	private final String testName;
	private final double statistic;
	private final double pValue;
	private final double correctedPValue;
	private final double effectSize;
	private final EffectSizeType effectSizeType;
	private final Verdict verdict;

	public StatResult(String testName, double statistic, double pValue, double correctedPValue,
			double effectSize, EffectSizeType effectSizeType, Verdict verdict) {
		this.testName = testName;
		this.statistic = statistic;
		this.pValue = pValue;
		this.correctedPValue = correctedPValue;
		this.effectSize = effectSize;
		this.effectSizeType = effectSizeType;
		this.verdict = verdict;
	}

	public String getTestName() { return testName; }
	public double getStatistic() { return statistic; }
	public double getPValue() { return pValue; }
	public double getCorrectedPValue() { return correctedPValue; }
	public double getEffectSize() { return effectSize; }
	public EffectSizeType getEffectSizeType() { return effectSizeType; }
	public Verdict getVerdict() { return verdict; }

	@Override
	public String toString() {
		return testName + " = " + statistic + " (p=" + pValue + ", corrected=" + correctedPValue
			+ ", effect=" + effectSize + " [" + effectSizeType + "], verdict=" + verdict + ")";
	}
}
