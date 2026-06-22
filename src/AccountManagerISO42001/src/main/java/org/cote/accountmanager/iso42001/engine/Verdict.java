package org.cote.accountmanager.iso42001.engine;

/**
 * Bias-test verdict classification per iso42001.md §4.4.
 *
 * <ul>
 *   <li>{@link #PASS} — corrected p ≥ α, OR corrected p &lt; α but effect size &lt; 0.2 (trivial)</li>
 *   <li>{@link #FLAG} — corrected p &lt; α AND effect size in [0.2, 0.5] (small–medium)</li>
 *   <li>{@link #FAIL} — corrected p &lt; α AND effect size &gt; 0.5 (medium–large)</li>
 * </ul>
 */
public enum Verdict {
	PASS,
	FLAG,
	FAIL
}
