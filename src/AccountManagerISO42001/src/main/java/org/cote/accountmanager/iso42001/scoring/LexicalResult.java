package org.cote.accountmanager.iso42001.scoring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a {@link LexicalAnalyzer} pass: per-category match counts over a text,
 * the total token count, and per-category relative frequencies (count / totalTokens).
 *
 * Feeds the "word frequency per category per group → Chi-square" path
 * (iso42001-bias.md §2.3).
 */
public class LexicalResult {

	private final Map<String, Integer> categoryCounts;
	private final int totalTokens;

	public LexicalResult(Map<String, Integer> categoryCounts, int totalTokens) {
		this.categoryCounts = categoryCounts;
		this.totalTokens = totalTokens;
	}

	public Map<String, Integer> getCategoryCounts() {
		return Collections.unmodifiableMap(categoryCounts);
	}

	public int getCount(String category) {
		return categoryCounts.getOrDefault(category, 0);
	}

	public int getTotalTokens() {
		return totalTokens;
	}

	/** Relative frequency of a category: matches / total tokens (0 if no tokens). */
	public double getFrequency(String category) {
		if (totalTokens == 0) {
			return 0.0;
		}
		return (double) getCount(category) / totalTokens;
	}

	/** All category frequencies. */
	public Map<String, Double> getFrequencies() {
		Map<String, Double> freqs = new LinkedHashMap<>();
		for (String cat : categoryCounts.keySet()) {
			freqs.put(cat, getFrequency(cat));
		}
		return freqs;
	}

	@Override
	public String toString() {
		return "LexicalResult{totalTokens=" + totalTokens + ", counts=" + categoryCounts + "}";
	}
}
