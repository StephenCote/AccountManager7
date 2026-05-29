package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/// Phase 2 (ConversationQualityPlan) pure scoring + reranking util for
/// memory retrieval. Decoupled from Chat / BaseRecord so it can be unit
/// tested without a DB or LLM.
///
/// Three operations, applied in this order by Chat.retrieveRelevantMemories:
///   1. applyRecencyPenalty — down-weights memories created within the
///      last N minutes (likely still on the conversation wire and don't
///      need re-injection). Memories with importance >= essentialImportance
///      are EXEMPT.
///   2. mmrRerank — diversity-aware reranking. Picks `finalCount` items
///      maximizing `λ * relevance - (1-λ) * max_similarity_to_picked`.
///   3. dedupBySimilarity — final pass that drops near-duplicates that
///      survived earlier scoring.
///
/// Similarity is supplied as a BiFunction so callers can pass in
/// ConversationQualityMetrics::shingleJaccard or any other text-similarity
/// function — keeps this util free of project-specific dependencies.
public final class MemoryRetrievalScorer {

	private MemoryRetrievalScorer() { /* no instances */ }

	/// Lightweight scored-memory holder. `payload` is an opaque caller-owned
	/// reference (typically the underlying BaseRecord) so the caller can
	/// recover the original record after scoring/reranking.
	public static final class ScoredMemory {
		public final String content;
		public final int importance;
		public final long createdMs;
		public double score;
		public final Object payload;

		public ScoredMemory(String content, int importance, long createdMs, double score, Object payload) {
			this.content = content == null ? "" : content;
			this.importance = importance;
			this.createdMs = createdMs;
			this.score = score;
			this.payload = payload;
		}
	}

	// ── Recency penalty ─────────────────────────────────────────────────

	/// Exponential ramp-up: returns 0 at age=0, approaches 1 as age grows.
	/// At age == halfLifeMinutes the multiplier is ~0.63.
	/// At age == 3*halfLifeMinutes the multiplier is ~0.95.
	/// halfLifeMinutes <= 0 disables the penalty (returns 1.0 for any age).
	/// Negative ages (future timestamp / clock skew) return 0.0 — penalize
	/// hard rather than trust corrupt timestamps.
	public static double recencyMultiplier(long memoryCreatedMs, long nowMs, double halfLifeMinutes) {
		if (halfLifeMinutes <= 0.0) return 1.0;
		double ageMinutes = (nowMs - memoryCreatedMs) / 60000.0;
		if (ageMinutes <= 0.0) return 0.0;
		return 1.0 - Math.exp(-ageMinutes / halfLifeMinutes);
	}

	/// Multiplies each memory's `score` by `recencyMultiplier(...)` UNLESS
	/// importance >= essentialImportance, in which case the score is left
	/// untouched (load-bearing facts bypass recency dampening).
	/// Modifies in place; returns the same list for chaining.
	public static List<ScoredMemory> applyRecencyPenalty(
			List<ScoredMemory> mems,
			long nowMs,
			double halfLifeMinutes,
			int essentialImportance) {
		if (mems == null || mems.isEmpty()) return mems;
		for (ScoredMemory m : mems) {
			if (m.importance >= essentialImportance) continue;
			double mult = recencyMultiplier(m.createdMs, nowMs, halfLifeMinutes);
			m.score = m.score * mult;
		}
		return mems;
	}

	// ── MMR rerank ──────────────────────────────────────────────────────

	/// Maximal Marginal Relevance reranking for diversity.
	///
	/// Each iteration picks the candidate maximizing:
	///   mmrScore = λ * relevance(c, query) - (1-λ) * max_sim(c, alreadyPicked)
	///
	/// where relevance(c, query) is `c.score / maxScore` (normalized so the
	/// MMR terms are comparable) plus a fallback to textSimilarity(c.content, queryText)
	/// when the input scores are uniform (e.g. all 1.0).
	///
	/// λ=1.0 → pure score-based ranking (no diversity).
	/// λ=0.0 → pure diversity (ignore relevance).
	/// λ=0.5 → balanced (recommended default).
	///
	/// Returns a NEW list. Never longer than min(candidates.size(), finalCount).
	/// Null/empty candidates → empty list. finalCount <= 0 → empty list.
	/// textSimilarity null → falls back to score-only ranking (no diversity term).
	public static List<ScoredMemory> mmrRerank(
			List<ScoredMemory> candidates,
			String queryText,
			int finalCount,
			double lambda,
			BiFunction<String, String, Double> textSimilarity) {
		List<ScoredMemory> picked = new ArrayList<>();
		if (candidates == null || candidates.isEmpty() || finalCount <= 0) return picked;

		List<ScoredMemory> pool = new ArrayList<>(candidates);

		double maxScore = 0.0;
		for (ScoredMemory c : pool) if (c.score > maxScore) maxScore = c.score;
		if (maxScore <= 0.0) maxScore = 1.0;

		double lam = Math.max(0.0, Math.min(1.0, lambda));
		String q = queryText == null ? "" : queryText;

		while (!pool.isEmpty() && picked.size() < finalCount) {
			ScoredMemory best = null;
			double bestMmr = -Double.MAX_VALUE;
			for (ScoredMemory c : pool) {
				double relevance = c.score / maxScore;
				if (textSimilarity != null && !q.isEmpty()) {
					Double qSim = textSimilarity.apply(c.content, q);
					if (qSim != null) {
						/// Blend normalized score with query-text similarity 50/50.
						/// Both are in [0,1] so this preserves the MMR scale.
						relevance = 0.5 * relevance + 0.5 * qSim;
					}
				}
				double maxSimToPicked = 0.0;
				if (textSimilarity != null) {
					for (ScoredMemory p : picked) {
						Double s = textSimilarity.apply(c.content, p.content);
						if (s != null && s > maxSimToPicked) maxSimToPicked = s;
					}
				}
				double mmr = lam * relevance - (1.0 - lam) * maxSimToPicked;
				if (mmr > bestMmr) {
					bestMmr = mmr;
					best = c;
				}
			}
			if (best == null) break;
			picked.add(best);
			pool.remove(best);
		}
		return picked;
	}

	// ── Dedup ───────────────────────────────────────────────────────────

	/// Walk the input in order. Keep each item only if its content has
	/// similarity <= threshold to every already-kept item.
	/// threshold >= 1.0 effectively disables dedup (only exact same-string
	/// matches drop; even those need similarity==1.0).
	/// textSimilarity null → identity-based dedup (drop exact content duplicates only).
	public static List<ScoredMemory> dedupBySimilarity(
			List<ScoredMemory> in,
			double similarityThreshold,
			BiFunction<String, String, Double> textSimilarity) {
		List<ScoredMemory> out = new ArrayList<>();
		if (in == null || in.isEmpty()) return out;
		Set<String> seenExact = new HashSet<>();
		for (ScoredMemory cand : in) {
			boolean drop = false;
			if (textSimilarity == null) {
				if (!seenExact.add(cand.content)) drop = true;
			} else {
				for (ScoredMemory kept : out) {
					Double s = textSimilarity.apply(cand.content, kept.content);
					if (s != null && s > similarityThreshold) {
						drop = true;
						break;
					}
				}
			}
			if (!drop) out.add(cand);
		}
		return out;
	}
}
