package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/// Pure-Java conversation-quality metrics. No LLM calls, no I/O.
///
/// Used by:
///   - TestChatDuelLong for per-turn [QUAL] emission and end-of-pair
///     [QUAL-SUMMARY] rollups
///   - Phase 1 echo detection / suppression in Chat (planned)
///   - Phase 3 keyframe skip-on-echo in Chat (planned)
///   - Phase 6 ConversationQualityEvaluator (planned)
///
/// Design constraints:
///   - Cheap: callable per-turn without measurable latency cost
///   - Deterministic: same input → same output
///   - No mutating state on inputs
///   - No external deps
public final class ConversationQualityMetrics {

	/// Conservative token splitter — alphanumeric runs, lowercased.
	/// Apostrophe stays inside words; everything else is a separator.
	/// Cheap enough to run hundreds of times per second.
	private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9']+");

	private ConversationQualityMetrics() { /* no instances */ }

	// ───────────────────────────────────────────────────────────────────
	// Tokenization
	// ───────────────────────────────────────────────────────────────────

	/// Tokenize: lowercase, split on non-alphanumeric, drop empties.
	public static List<String> tokens(String s) {
		List<String> out = new ArrayList<>();
		if (s == null || s.isEmpty()) return out;
		for (String t : TOKEN_SPLIT.split(s.toLowerCase())) {
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	// ───────────────────────────────────────────────────────────────────
	// Shingle Jaccard — primary echo metric
	// ───────────────────────────────────────────────────────────────────

	/// Build the set of consecutive k-grams (token shingles) from text.
	/// k=3 is a good default for echo detection — catches paraphrases
	/// while ignoring minor word swaps. k=1 = bag-of-words; k=5+ becomes
	/// near-Levenshtein-strict.
	///
	/// Returns the empty set for null/empty input or when token count
	/// is less than k.
	public static Set<String> shingles(String s, int k) {
		if (k < 1) throw new IllegalArgumentException("k must be >= 1");
		Set<String> out = new HashSet<>();
		List<String> toks = tokens(s);
		if (toks.size() < k) return out;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i + k <= toks.size(); i++) {
			sb.setLength(0);
			for (int j = 0; j < k; j++) {
				if (j > 0) sb.append(' ');
				sb.append(toks.get(i + j));
			}
			out.add(sb.toString());
		}
		return out;
	}

	/// Jaccard similarity over k-shingles. 1.0 = identical (modulo
	/// tokenization), 0.0 = no shared shingles.
	///
	/// Two empty texts → 1.0 (vacuously identical). One empty, one not → 0.0.
	public static double shingleJaccard(String a, String b, int k) {
		Set<String> sa = shingles(a, k);
		Set<String> sb = shingles(b, k);
		if (sa.isEmpty() && sb.isEmpty()) return 1.0;
		if (sa.isEmpty() || sb.isEmpty()) return 0.0;
		int inter = 0;
		Set<String> smaller = sa.size() <= sb.size() ? sa : sb;
		Set<String> larger = sa.size() <= sb.size() ? sb : sa;
		for (String sh : smaller) {
			if (larger.contains(sh)) inter++;
		}
		int union = sa.size() + sb.size() - inter;
		if (union == 0) return 0.0;
		return (double) inter / (double) union;
	}

	/// Average pairwise Jaccard over a list of recent strings.
	/// Used to detect when the last N assistant responses are too similar
	/// to one another (echo loop).
	///
	/// Lists with 0 or 1 elements return 0.0 (nothing to compare).
	public static double avgPairwiseShingleJaccard(List<String> recent, int k) {
		if (recent == null || recent.size() < 2) return 0.0;
		int pairs = 0;
		double sum = 0.0;
		for (int i = 0; i < recent.size(); i++) {
			for (int j = i + 1; j < recent.size(); j++) {
				sum += shingleJaccard(recent.get(i), recent.get(j), k);
				pairs++;
			}
		}
		return pairs == 0 ? 0.0 : sum / pairs;
	}

	// ───────────────────────────────────────────────────────────────────
	// Normalized Levenshtein — backup similarity for very short text
	// ───────────────────────────────────────────────────────────────────

	/// Levenshtein edit distance normalized by max(len(a), len(b)).
	/// Returns 0.0 (identical) to 1.0 (entirely different).
	///
	/// Two empty strings → 0.0. One empty → 1.0.
	public static double normalizedLevenshtein(String a, String b) {
		if (a == null) a = "";
		if (b == null) b = "";
		int la = a.length();
		int lb = b.length();
		if (la == 0 && lb == 0) return 0.0;
		if (la == 0 || lb == 0) return 1.0;
		int[] prev = new int[lb + 1];
		int[] curr = new int[lb + 1];
		for (int j = 0; j <= lb; j++) prev[j] = j;
		for (int i = 1; i <= la; i++) {
			curr[0] = i;
			char ca = a.charAt(i - 1);
			for (int j = 1; j <= lb; j++) {
				int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
				curr[j] = Math.min(
					Math.min(curr[j - 1] + 1, prev[j] + 1),
					prev[j - 1] + cost
				);
			}
			int[] swap = prev; prev = curr; curr = swap;
		}
		int dist = prev[lb];
		int max = Math.max(la, lb);
		return (double) dist / (double) max;
	}

	// ───────────────────────────────────────────────────────────────────
	// Vocabulary diversity
	// ───────────────────────────────────────────────────────────────────

	/// Distinct-token ratio over a rolling window of recent strings.
	/// 1.0 = every token unique; near 0 = heavy repetition.
	///
	/// Empty input → 0.0 (no signal).
	public static double distinctTokenRatio(List<String> recent) {
		if (recent == null || recent.isEmpty()) return 0.0;
		int total = 0;
		Set<String> distinct = new HashSet<>();
		for (String s : recent) {
			List<String> toks = tokens(s);
			total += toks.size();
			distinct.addAll(toks);
		}
		if (total == 0) return 0.0;
		return (double) distinct.size() / (double) total;
	}

	// ───────────────────────────────────────────────────────────────────
	// Memory utilization — did the LLM actually use the injected memories?
	// ───────────────────────────────────────────────────────────────────

	/// Tokens worth treating as "content tokens" for memory-utilization:
	/// alphabetic tokens length >= 4. Filters out stopwords (the, and, etc.)
	/// and digits.
	private static Set<String> contentTokens(String s) {
		Set<String> out = new HashSet<>();
		for (String t : tokens(s)) {
			if (t.length() < 4) continue;
			boolean allAlpha = true;
			for (int i = 0; i < t.length(); i++) {
				if (!Character.isLetter(t.charAt(i))) { allAlpha = false; break; }
			}
			if (allAlpha) out.add(t);
		}
		return out;
	}

	/// Fraction of content tokens (length >= 4 alphabetic) drawn from
	/// the injected memory summaries that appear in the response.
	/// Returns 0.0 when memories are empty or response is empty.
	///
	/// Crude but actionable signal — high values mean the LLM is drawing
	/// from injected memory content; low values mean memories are being
	/// ignored.
	public static double memoryUtilization(List<String> memorySummaries, String response) {
		if (memorySummaries == null || memorySummaries.isEmpty()) return 0.0;
		if (response == null || response.isEmpty()) return 0.0;
		Set<String> memTokens = new HashSet<>();
		for (String m : memorySummaries) memTokens.addAll(contentTokens(m));
		if (memTokens.isEmpty()) return 0.0;
		Set<String> respTokens = contentTokens(response);
		if (respTokens.isEmpty()) return 0.0;
		int hits = 0;
		for (String t : memTokens) {
			if (respTokens.contains(t)) hits++;
		}
		return (double) hits / (double) memTokens.size();
	}

	// ───────────────────────────────────────────────────────────────────
	// Latency-vs-msgCount slope (ordinary least squares)
	// ───────────────────────────────────────────────────────────────────

	/// Compute ordinary-least-squares slope of y against x.
	/// Used by the duel test to assess whether latency grows with
	/// msgCount (positive slope = degradation) or stays flat (slope ~= 0).
	///
	/// Returns 0.0 if fewer than 2 points or if x has zero variance.
	public static double linearSlope(double[] x, double[] y) {
		if (x == null || y == null) return 0.0;
		int n = Math.min(x.length, y.length);
		if (n < 2) return 0.0;
		double sumX = 0, sumY = 0;
		for (int i = 0; i < n; i++) { sumX += x[i]; sumY += y[i]; }
		double meanX = sumX / n;
		double meanY = sumY / n;
		double num = 0, den = 0;
		for (int i = 0; i < n; i++) {
			double dx = x[i] - meanX;
			num += dx * (y[i] - meanY);
			den += dx * dx;
		}
		if (den == 0.0) return 0.0;
		return num / den;
	}
}
