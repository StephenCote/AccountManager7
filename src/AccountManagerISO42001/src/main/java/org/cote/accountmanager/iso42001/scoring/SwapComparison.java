package org.cote.accountmanager.iso42001.scoring;

import org.cote.accountmanager.iso42001.engine.Verdict;

/**
 * Outcome of comparing the two output sets of a swap test (design §5.2).
 * {@code biasDetected} is true when the verdict is not PASS — i.e. the swap
 * produced a statistically significant, non-trivial difference in either
 * direction (iso42001.md §5 directional/swap rule).
 */
public class SwapComparison {

	private final double pValue;
	private final double effectSize;
	private final Verdict verdict;
	private final boolean biasDetected;

	public SwapComparison(double pValue, double effectSize, Verdict verdict) {
		this.pValue = pValue;
		this.effectSize = effectSize;
		this.verdict = verdict;
		this.biasDetected = verdict != Verdict.PASS;
	}

	public double getPValue() { return pValue; }
	public double getEffectSize() { return effectSize; }
	public Verdict getVerdict() { return verdict; }
	public boolean isBiasDetected() { return biasDetected; }

	@Override
	public String toString() {
		return "SwapComparison{p=" + pValue + ", effect=" + effectSize
			+ ", verdict=" + verdict + ", biasDetected=" + biasDetected + "}";
	}
}
