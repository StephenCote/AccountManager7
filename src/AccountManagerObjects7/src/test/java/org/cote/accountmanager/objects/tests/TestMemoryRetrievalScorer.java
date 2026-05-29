package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import org.cote.accountmanager.olio.llm.ConversationQualityMetrics;
import org.cote.accountmanager.olio.llm.MemoryRetrievalScorer;
import org.cote.accountmanager.olio.llm.MemoryRetrievalScorer.ScoredMemory;
import org.junit.Test;

/// Phase 2 (ConversationQualityPlan) unit tests for the pure
/// retrieval scorer (recency penalty + MMR + dedup). No DB / no LLM.
public class TestMemoryRetrievalScorer {

	private static final BiFunction<String, String, Double> JACCARD =
		(a, b) -> ConversationQualityMetrics.shingleJaccard(a, b, 3);

	private static ScoredMemory mem(String content, int importance, long createdMs, double initialScore) {
		return new ScoredMemory(content, importance, createdMs, initialScore, null);
	}

	// ── recencyMultiplier ───────────────────────────────────────────────

	@Test
	public void recency_disabledWhenHalfLifeZero() {
		assertEquals(1.0, MemoryRetrievalScorer.recencyMultiplier(0L, 1000L, 0.0), 1e-9);
		assertEquals(1.0, MemoryRetrievalScorer.recencyMultiplier(0L, 0L, 0.0), 1e-9);
	}

	@Test
	public void recency_disabledWhenHalfLifeNegative() {
		assertEquals(1.0, MemoryRetrievalScorer.recencyMultiplier(0L, 1000L, -5.0), 1e-9);
	}

	@Test
	public void recency_futureTimestampReturnsZero() {
		long now = 1_000_000L;
		long future = now + 60_000L;
		assertEquals(0.0, MemoryRetrievalScorer.recencyMultiplier(future, now, 30.0), 1e-9);
	}

	@Test
	public void recency_sameInstantReturnsZero() {
		long now = 1_000_000L;
		assertEquals(0.0, MemoryRetrievalScorer.recencyMultiplier(now, now, 30.0), 1e-9);
	}

	@Test
	public void recency_atHalfLifeApproxOnePoint63() {
		long created = 0L;
		long now = 30L * 60_000L; // 30 minutes
		double mult = MemoryRetrievalScorer.recencyMultiplier(created, now, 30.0);
		assertEquals(1.0 - Math.exp(-1.0), mult, 1e-6);
		assertTrue("expected ~0.63, got " + mult, mult > 0.6 && mult < 0.7);
	}

	@Test
	public void recency_atThreeHalfLivesApproxOnePoint95() {
		long created = 0L;
		long now = 90L * 60_000L; // 90 minutes = 3 * 30
		double mult = MemoryRetrievalScorer.recencyMultiplier(created, now, 30.0);
		assertTrue("expected >0.94, got " + mult, mult > 0.94);
		assertTrue("expected <1.0, got " + mult, mult < 1.0);
	}

	@Test
	public void recency_veryOldApproachesOne() {
		long created = 0L;
		long now = 10_000L * 60_000L; // way past
		double mult = MemoryRetrievalScorer.recencyMultiplier(created, now, 30.0);
		assertTrue("expected ~1.0, got " + mult, mult > 0.999);
	}

	// ── applyRecencyPenalty ─────────────────────────────────────────────

	@Test
	public void apply_nullOrEmptyNoOp() {
		assertEquals(null, MemoryRetrievalScorer.applyRecencyPenalty(null, 0L, 30.0, 8));
		List<ScoredMemory> empty = new ArrayList<>();
		assertTrue(MemoryRetrievalScorer.applyRecencyPenalty(empty, 0L, 30.0, 8).isEmpty());
	}

	@Test
	public void apply_essentialImportanceExempt() {
		long now = 1_000_000L;
		ScoredMemory essential = mem("anchor fact", 9, now, 5.0);
		ScoredMemory ordinary = mem("ordinary fact", 5, now, 5.0);
		MemoryRetrievalScorer.applyRecencyPenalty(Arrays.asList(essential, ordinary), now, 30.0, 8);
		assertEquals("essential should be untouched", 5.0, essential.score, 1e-9);
		assertEquals("ordinary should be zeroed (age=0)", 0.0, ordinary.score, 1e-9);
	}

	@Test
	public void apply_scoresDampedAtHalfLife() {
		long now = 1_000_000L;
		long created = now - (long)(30 * 60_000L);
		ScoredMemory m = mem("c", 5, created, 10.0);
		MemoryRetrievalScorer.applyRecencyPenalty(Arrays.asList(m), now, 30.0, 8);
		assertEquals(10.0 * (1.0 - Math.exp(-1.0)), m.score, 1e-6);
	}

	@Test
	public void apply_disabledNoChange() {
		long now = 1_000_000L;
		ScoredMemory m = mem("c", 5, now - 1000L, 7.0);
		MemoryRetrievalScorer.applyRecencyPenalty(Arrays.asList(m), now, 0.0, 8);
		assertEquals(7.0, m.score, 1e-9);
	}

	// ── mmrRerank ───────────────────────────────────────────────────────

	@Test
	public void mmr_nullOrEmptyOrZeroCountReturnsEmpty() {
		assertTrue(MemoryRetrievalScorer.mmrRerank(null, "q", 5, 0.5, JACCARD).isEmpty());
		assertTrue(MemoryRetrievalScorer.mmrRerank(new ArrayList<>(), "q", 5, 0.5, JACCARD).isEmpty());
		assertTrue(MemoryRetrievalScorer.mmrRerank(Arrays.asList(mem("a", 5, 0L, 1.0)), "q", 0, 0.5, JACCARD).isEmpty());
		assertTrue(MemoryRetrievalScorer.mmrRerank(Arrays.asList(mem("a", 5, 0L, 1.0)), "q", -1, 0.5, JACCARD).isEmpty());
	}

	@Test
	public void mmr_lambdaOneIsPureScoreRanking() {
		ScoredMemory hi = mem("the quick brown fox jumps", 5, 0L, 10.0);
		ScoredMemory mid = mem("a totally unrelated phrase", 5, 0L, 5.0);
		ScoredMemory lo = mem("yet another distinct sentence", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(lo, mid, hi), "anything", 3, 1.0, JACCARD);
		assertEquals(3, r.size());
		assertEquals(hi, r.get(0));
		assertEquals(mid, r.get(1));
		assertEquals(lo, r.get(2));
	}

	@Test
	public void mmr_lambdaZeroFavorsDiversityOverScore() {
		/// Two near-identical high-score items + one unique low-score item.
		/// λ=0 should pick the unique one second (after whichever wins on tie),
		/// not the duplicate.
		ScoredMemory a = mem("the brown fox jumps over the lazy dog", 5, 0L, 10.0);
		ScoredMemory b = mem("the brown fox jumps over the lazy dog and more", 5, 0L, 9.9);
		ScoredMemory uniq = mem("completely different unrelated material here", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(a, b, uniq), "fox", 2, 0.0, JACCARD);
		assertEquals(2, r.size());
		assertTrue("expected uniq picked, got [" + r.get(0).content + ", " + r.get(1).content + "]",
			r.contains(uniq));
	}

	@Test
	public void mmr_capsAtCandidateCount() {
		ScoredMemory a = mem("one", 5, 0L, 1.0);
		ScoredMemory b = mem("two", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(a, b), "x", 99, 0.5, JACCARD);
		assertEquals(2, r.size());
	}

	@Test
	public void mmr_balancedAvoidsDuplicateAtTop() {
		ScoredMemory hi1 = mem("they walked to the river together", 5, 0L, 9.0);
		ScoredMemory hi2 = mem("they walked to the river together again", 5, 0L, 9.0);
		ScoredMemory diverse = mem("the storm broke before dawn", 5, 0L, 7.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(hi1, hi2, diverse), "river", 2, 0.5, JACCARD);
		assertEquals(2, r.size());
		/// Whichever hi was picked first, second pick should be `diverse`
		/// not the other hi (similarity ≈ 1, diversity penalty wins).
		assertTrue("second should be diverse, got " + r.get(1).content,
			r.get(1) == diverse);
	}

	@Test
	public void mmr_nullSimilarityFallsBackToScoreRanking() {
		ScoredMemory hi = mem("x", 5, 0L, 10.0);
		ScoredMemory lo = mem("y", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(lo, hi), "q", 2, 0.5, null);
		assertEquals(hi, r.get(0));
		assertEquals(lo, r.get(1));
	}

	@Test
	public void mmr_lambdaOutOfRangeClamped() {
		ScoredMemory a = mem("a", 5, 0L, 3.0);
		ScoredMemory b = mem("b", 5, 0L, 1.0);
		List<ScoredMemory> withHi = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(a, b), "q", 2, 99.0, JACCARD);
		List<ScoredMemory> withLo = MemoryRetrievalScorer.mmrRerank(
			Arrays.asList(a, b), "q", 2, -99.0, JACCARD);
		/// λ=99 clamps to 1.0 → score ranking → a first
		assertEquals(a, withHi.get(0));
		/// λ=-99 clamps to 0.0 → diversity-only → ties on first; second is the other
		assertEquals(2, withLo.size());
		assertTrue(withLo.contains(a) && withLo.contains(b));
	}

	// ── dedupBySimilarity ───────────────────────────────────────────────

	@Test
	public void dedup_nullOrEmptyReturnsEmpty() {
		assertTrue(MemoryRetrievalScorer.dedupBySimilarity(null, 0.8, JACCARD).isEmpty());
		assertTrue(MemoryRetrievalScorer.dedupBySimilarity(new ArrayList<>(), 0.8, JACCARD).isEmpty());
	}

	@Test
	public void dedup_keepsAllWhenAllUnique() {
		ScoredMemory a = mem("the brown fox jumped", 5, 0L, 1.0);
		ScoredMemory b = mem("the storm broke before dawn", 5, 0L, 1.0);
		ScoredMemory c = mem("she carried the lantern", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.dedupBySimilarity(
			Arrays.asList(a, b, c), 0.8, JACCARD);
		assertEquals(3, r.size());
	}

	@Test
	public void dedup_dropsNearDuplicate() {
		ScoredMemory a = mem("the brown fox jumped over the lazy dog", 5, 0L, 1.0);
		ScoredMemory dup = mem("the brown fox jumped over the lazy dog", 5, 0L, 1.0);
		ScoredMemory c = mem("the storm broke before dawn", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.dedupBySimilarity(
			Arrays.asList(a, dup, c), 0.8, JACCARD);
		assertEquals(2, r.size());
		assertEquals(a, r.get(0));
		assertEquals(c, r.get(1));
	}

	@Test
	public void dedup_preservesInputOrder() {
		ScoredMemory hi = mem("alpha bravo charlie delta", 5, 0L, 10.0);
		ScoredMemory mid = mem("echo foxtrot golf hotel", 5, 0L, 5.0);
		ScoredMemory lo = mem("india juliet kilo lima", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.dedupBySimilarity(
			Arrays.asList(hi, mid, lo), 0.8, JACCARD);
		assertEquals(hi, r.get(0));
		assertEquals(mid, r.get(1));
		assertEquals(lo, r.get(2));
	}

	@Test
	public void dedup_thresholdOneOnlyDropsExactJaccard() {
		ScoredMemory a = mem("the brown fox jumped", 5, 0L, 1.0);
		ScoredMemory similar = mem("the brown fox jumped over", 5, 0L, 1.0);
		/// similar's shingles overlap heavily but not 100%, so 1.0 threshold keeps both
		List<ScoredMemory> r = MemoryRetrievalScorer.dedupBySimilarity(
			Arrays.asList(a, similar), 1.0, JACCARD);
		assertEquals(2, r.size());
	}

	@Test
	public void dedup_nullSimilarityFallsBackToExactStringDedup() {
		ScoredMemory a = mem("exact same", 5, 0L, 1.0);
		ScoredMemory b = mem("exact same", 5, 0L, 1.0);
		ScoredMemory c = mem("different", 5, 0L, 1.0);
		List<ScoredMemory> r = MemoryRetrievalScorer.dedupBySimilarity(
			Arrays.asList(a, b, c), 0.5, null);
		assertEquals(2, r.size());
		assertEquals(a, r.get(0));
		assertEquals(c, r.get(1));
	}

	// ── Pipeline integration ────────────────────────────────────────────

	@Test
	public void pipeline_recencyThenMmrThenDedup_endToEnd() {
		long now = 100_000_000L;
		long oldEnough = now - (long)(120 * 60_000L); // 2 hours ago
		long brandNew = now - 1_000L; // 1s ago

		/// Brand-new low-importance memory — should be heavily penalized.
		ScoredMemory recentNoise = mem("the brown fox jumps", 4, brandNew, 8.0);
		/// Older, varied, high-relevance — should survive.
		ScoredMemory olderUsefulA = mem("she carried the lantern through the cellar", 5, oldEnough, 7.0);
		/// Near-dup of olderUsefulA, slightly lower score — should be dedup'd.
		ScoredMemory olderUsefulADup = mem("she carried the lantern through the cellar quietly", 5, oldEnough, 6.5);
		/// Different topic, older — should survive for diversity.
		ScoredMemory olderUsefulB = mem("the storm finally broke before dawn", 5, oldEnough, 6.0);
		/// Pinned recent — should survive recency penalty.
		ScoredMemory pinnedRecent = mem("his name is Alder of the Northwood", 9, brandNew, 9.5);

		List<ScoredMemory> all = Arrays.asList(
			recentNoise, olderUsefulA, olderUsefulADup, olderUsefulB, pinnedRecent);
		MemoryRetrievalScorer.applyRecencyPenalty(all, now, 30.0, 8);
		/// pinnedRecent untouched
		assertEquals(9.5, pinnedRecent.score, 1e-9);
		/// recentNoise essentially zero
		assertTrue("recentNoise should be heavily damped, score=" + recentNoise.score,
			recentNoise.score < 1.0);
		/// older items barely touched
		assertTrue("olderUsefulA should retain most of its score, got " + olderUsefulA.score,
			olderUsefulA.score > 6.5);

		List<ScoredMemory> reranked = MemoryRetrievalScorer.mmrRerank(
			all, "lantern cellar", 4, 0.5, JACCARD);
		assertEquals(4, reranked.size());

		List<ScoredMemory> deduped = MemoryRetrievalScorer.dedupBySimilarity(
			reranked, 0.8, JACCARD);
		/// One of the two near-dup lantern entries should be gone
		long lanternCount = deduped.stream()
			.filter(m -> m.content.contains("lantern"))
			.count();
		assertEquals("near-dup lantern entry should be deduped", 1, lanternCount);
		/// Pinned and at least one diverse memory should survive
		assertTrue(deduped.contains(pinnedRecent));
		assertTrue(deduped.contains(olderUsefulB));
	}

	@Test
	public void pipeline_scoreMutationVisibleAfterApply() {
		long now = 1_000_000L;
		ScoredMemory m = mem("test", 5, now - (long)(15 * 60_000L), 10.0);
		double before = m.score;
		MemoryRetrievalScorer.applyRecencyPenalty(Arrays.asList(m), now, 30.0, 8);
		assertNotEquals(before, m.score, 1e-9);
	}
}
