package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.cote.accountmanager.olio.llm.ConversationQualityMetrics;
import org.junit.Test;

/// Pure-function tests for ConversationQualityMetrics. No DB / no LLM —
/// this test class deliberately does NOT extend BaseTest so it runs
/// without any I/O setup and is fast.
public class TestConversationQualityMetrics {

	private static final double EPS = 1e-9;

	// ── tokens ────────────────────────────────────────────────────────

	@Test
	public void tokens_nullAndEmptyReturnEmptyList() {
		assertTrue(ConversationQualityMetrics.tokens(null).isEmpty());
		assertTrue(ConversationQualityMetrics.tokens("").isEmpty());
	}

	@Test
	public void tokens_splitsAndLowercases() {
		List<String> t = ConversationQualityMetrics.tokens("Hello, WORLD! It's a test.");
		assertEquals(Arrays.asList("hello", "world", "it's", "a", "test"), t);
	}

	@Test
	public void tokens_dropsPunctuationButKeepsApostrophes() {
		List<String> t = ConversationQualityMetrics.tokens("don't can't won't");
		assertEquals(Arrays.asList("don't", "can't", "won't"), t);
	}

	// ── shingles ──────────────────────────────────────────────────────

	@Test(expected = IllegalArgumentException.class)
	public void shingles_kZeroThrows() {
		ConversationQualityMetrics.shingles("hello world", 0);
	}

	@Test
	public void shingles_kTooLargeReturnsEmpty() {
		Set<String> sh = ConversationQualityMetrics.shingles("hello world", 5);
		assertTrue(sh.isEmpty());
	}

	@Test
	public void shingles_k1IsBagOfWords() {
		Set<String> sh = ConversationQualityMetrics.shingles("the quick brown fox", 1);
		assertEquals(4, sh.size());
		assertTrue(sh.contains("the"));
		assertTrue(sh.contains("fox"));
	}

	@Test
	public void shingles_k3SlidesProperly() {
		Set<String> sh = ConversationQualityMetrics.shingles("a b c d e", 3);
		assertEquals(3, sh.size());
		assertTrue(sh.contains("a b c"));
		assertTrue(sh.contains("b c d"));
		assertTrue(sh.contains("c d e"));
	}

	@Test
	public void shingles_nullAndEmptyReturnEmpty() {
		assertTrue(ConversationQualityMetrics.shingles(null, 3).isEmpty());
		assertTrue(ConversationQualityMetrics.shingles("", 3).isEmpty());
	}

	// ── shingleJaccard ────────────────────────────────────────────────

	@Test
	public void shingleJaccard_identicalIsOne() {
		double j = ConversationQualityMetrics.shingleJaccard(
			"the quick brown fox", "the quick brown fox", 3);
		assertEquals(1.0, j, EPS);
	}

	@Test
	public void shingleJaccard_completelyDifferentIsZero() {
		double j = ConversationQualityMetrics.shingleJaccard(
			"the quick brown fox", "lorem ipsum dolor sit", 3);
		assertEquals(0.0, j, EPS);
	}

	@Test
	public void shingleJaccard_partialOverlap() {
		/// "a b c d" → {a b c, b c d}
		/// "b c d e" → {b c d, c d e}
		/// intersection: {b c d} (1)
		/// union: {a b c, b c d, c d e} (3)
		/// jaccard = 1/3
		double j = ConversationQualityMetrics.shingleJaccard("a b c d", "b c d e", 3);
		assertEquals(1.0 / 3.0, j, EPS);
	}

	@Test
	public void shingleJaccard_bothEmptyIsOne() {
		assertEquals(1.0, ConversationQualityMetrics.shingleJaccard("", "", 3), EPS);
		assertEquals(1.0, ConversationQualityMetrics.shingleJaccard(null, null, 3), EPS);
	}

	@Test
	public void shingleJaccard_oneEmptyIsZero() {
		assertEquals(0.0, ConversationQualityMetrics.shingleJaccard("hello world today", "", 3), EPS);
		assertEquals(0.0, ConversationQualityMetrics.shingleJaccard("", "hello world today", 3), EPS);
	}

	@Test
	public void shingleJaccard_isOrderIndependent() {
		double j1 = ConversationQualityMetrics.shingleJaccard("a b c d", "b c d e", 3);
		double j2 = ConversationQualityMetrics.shingleJaccard("b c d e", "a b c d", 3);
		assertEquals(j1, j2, EPS);
	}

	@Test
	public void shingleJaccard_paraphraseGivesPartialScore() {
		/// Real-world: same idea, different surface words.
		/// Should be < 1.0 (not identical) but > 0 (some shared shingles
		/// when there's overlap).
		double j = ConversationQualityMetrics.shingleJaccard(
			"I am swinging high in the air today",
			"I am swinging high in the wind today",
			3);
		assertTrue("expected some overlap, got " + j, j > 0.0);
		assertTrue("expected not identical, got " + j, j < 1.0);
	}

	// ── avgPairwiseShingleJaccard ─────────────────────────────────────

	@Test
	public void avgPairwise_emptyOrSingleReturnsZero() {
		assertEquals(0.0,
			ConversationQualityMetrics.avgPairwiseShingleJaccard(null, 3), EPS);
		assertEquals(0.0,
			ConversationQualityMetrics.avgPairwiseShingleJaccard(Collections.emptyList(), 3), EPS);
		assertEquals(0.0,
			ConversationQualityMetrics.avgPairwiseShingleJaccard(Collections.singletonList("alone"), 3), EPS);
	}

	@Test
	public void avgPairwise_allIdenticalIsOne() {
		List<String> echo = Arrays.asList(
			"the same words repeated",
			"the same words repeated",
			"the same words repeated"
		);
		assertEquals(1.0, ConversationQualityMetrics.avgPairwiseShingleJaccard(echo, 3), EPS);
	}

	@Test
	public void avgPairwise_allDifferentIsZero() {
		List<String> diverse = Arrays.asList(
			"alpha beta gamma delta",
			"lorem ipsum dolor sit",
			"quick brown fox jumps"
		);
		assertEquals(0.0, ConversationQualityMetrics.avgPairwiseShingleJaccard(diverse, 3), EPS);
	}

	@Test
	public void avgPairwise_detectsRealisticEcho() {
		/// The actual echo pattern seen in qwen3:8b duels.
		List<String> echo = Arrays.asList(
			"I see you up there! I'm swinging as high as I can - watch out for that ball!",
			"I see you up there! I'm swinging as high as I can - watch out for that ball!",
			"I see you up there! I'm swinging as high as I can - watch out for that ball!"
		);
		double avg = ConversationQualityMetrics.avgPairwiseShingleJaccard(echo, 3);
		assertTrue("echo should produce avg > 0.9, got " + avg, avg > 0.9);
	}

	@Test
	public void avgPairwise_acceptsNaturalConversation() {
		List<String> natural = Arrays.asList(
			"That's a great idea, let's head to the park instead.",
			"Sure, I can pick up snacks on the way if you want.",
			"Sounds good — meet you at the gate in ten minutes."
		);
		double avg = ConversationQualityMetrics.avgPairwiseShingleJaccard(natural, 3);
		assertTrue("natural conversation should give low echo, got " + avg, avg < 0.3);
	}

	// ── normalizedLevenshtein ─────────────────────────────────────────

	@Test
	public void normLev_identicalIsZero() {
		assertEquals(0.0, ConversationQualityMetrics.normalizedLevenshtein("abc", "abc"), EPS);
		assertEquals(0.0, ConversationQualityMetrics.normalizedLevenshtein("", ""), EPS);
		assertEquals(0.0, ConversationQualityMetrics.normalizedLevenshtein(null, null), EPS);
	}

	@Test
	public void normLev_disjointIsOne() {
		assertEquals(1.0, ConversationQualityMetrics.normalizedLevenshtein("aaa", "bbb"), EPS);
	}

	@Test
	public void normLev_oneEmptyIsOne() {
		assertEquals(1.0, ConversationQualityMetrics.normalizedLevenshtein("abc", ""), EPS);
		assertEquals(1.0, ConversationQualityMetrics.normalizedLevenshtein("", "xyz"), EPS);
	}

	@Test
	public void normLev_singleEditOverShortString() {
		/// "kitten" vs "sitten" = 1 edit, max len 6 → 1/6
		double d = ConversationQualityMetrics.normalizedLevenshtein("kitten", "sitten");
		assertEquals(1.0 / 6.0, d, EPS);
	}

	@Test
	public void normLev_classicKittenSitting() {
		/// Classic example: edit distance 3, max len 7 → 3/7
		double d = ConversationQualityMetrics.normalizedLevenshtein("kitten", "sitting");
		assertEquals(3.0 / 7.0, d, EPS);
	}

	// ── distinctTokenRatio ────────────────────────────────────────────

	@Test
	public void distinctRatio_emptyIsZero() {
		assertEquals(0.0, ConversationQualityMetrics.distinctTokenRatio(null), EPS);
		assertEquals(0.0, ConversationQualityMetrics.distinctTokenRatio(Collections.emptyList()), EPS);
	}

	@Test
	public void distinctRatio_allUniqueIsOne() {
		List<String> diverse = Collections.singletonList("alpha beta gamma delta epsilon");
		assertEquals(1.0, ConversationQualityMetrics.distinctTokenRatio(diverse), EPS);
	}

	@Test
	public void distinctRatio_allSameIsLow() {
		List<String> repetitive = Arrays.asList(
			"repeat repeat repeat",
			"repeat repeat repeat"
		);
		/// 6 total tokens, 1 distinct → 1/6
		assertEquals(1.0 / 6.0, ConversationQualityMetrics.distinctTokenRatio(repetitive), EPS);
	}

	// ── memoryUtilization ─────────────────────────────────────────────

	@Test
	public void memUtil_noMemoriesIsZero() {
		assertEquals(0.0,
			ConversationQualityMetrics.memoryUtilization(null, "any response"), EPS);
		assertEquals(0.0,
			ConversationQualityMetrics.memoryUtilization(Collections.emptyList(), "any response"), EPS);
	}

	@Test
	public void memUtil_emptyResponseIsZero() {
		assertEquals(0.0,
			ConversationQualityMetrics.memoryUtilization(
				Collections.singletonList("memory content here"), ""), EPS);
		assertEquals(0.0,
			ConversationQualityMetrics.memoryUtilization(
				Collections.singletonList("memory content here"), null), EPS);
	}

	@Test
	public void memUtil_fullReuseIsOne() {
		List<String> mems = Collections.singletonList("frisbee park birthday cake");
		String resp = "Let's bring the frisbee to the park for the birthday cake.";
		double u = ConversationQualityMetrics.memoryUtilization(mems, resp);
		assertEquals(1.0, u, EPS);
	}

	@Test
	public void memUtil_partialReuse() {
		/// Memory tokens (length >= 4, alphabetic):
		///   "alpha", "beta", "gamma" (3 total)
		/// Response uses: "alpha" only
		/// → 1/3
		List<String> mems = Collections.singletonList("alpha beta gamma");
		String resp = "Today let's talk about alpha and nothing else.";
		double u = ConversationQualityMetrics.memoryUtilization(mems, resp);
		assertEquals(1.0 / 3.0, u, EPS);
	}

	@Test
	public void memUtil_ignoresShortTokensAndDigits() {
		/// "a", "by", "123" are NOT content tokens (too short or non-alpha).
		/// Only "important" qualifies.
		List<String> mems = Collections.singletonList("a by 123 important");
		String resp = "important is what matters";
		double u = ConversationQualityMetrics.memoryUtilization(mems, resp);
		assertEquals(1.0, u, EPS);
	}

	// ── linearSlope ───────────────────────────────────────────────────

	@Test
	public void slope_lessThanTwoPointsIsZero() {
		assertEquals(0.0,
			ConversationQualityMetrics.linearSlope(new double[]{1}, new double[]{2}), EPS);
		assertEquals(0.0,
			ConversationQualityMetrics.linearSlope(new double[]{}, new double[]{}), EPS);
		assertEquals(0.0,
			ConversationQualityMetrics.linearSlope(null, null), EPS);
	}

	@Test
	public void slope_perfectLineRecoversSlope() {
		double[] x = {1, 2, 3, 4, 5};
		double[] y = {10, 20, 30, 40, 50};  // slope 10
		assertEquals(10.0,
			ConversationQualityMetrics.linearSlope(x, y), EPS);
	}

	@Test
	public void slope_perfectLineWithIntercept() {
		double[] x = {1, 2, 3, 4, 5};
		double[] y = {103, 105, 107, 109, 111};  // slope 2
		assertEquals(2.0,
			ConversationQualityMetrics.linearSlope(x, y), EPS);
	}

	@Test
	public void slope_flatIsZero() {
		double[] x = {1, 2, 3, 4, 5};
		double[] y = {42, 42, 42, 42, 42};
		assertEquals(0.0,
			ConversationQualityMetrics.linearSlope(x, y), EPS);
	}

	@Test
	public void slope_negativeSlope() {
		double[] x = {1, 2, 3, 4, 5};
		double[] y = {50, 40, 30, 20, 10};
		assertEquals(-10.0,
			ConversationQualityMetrics.linearSlope(x, y), EPS);
	}

	@Test
	public void slope_zeroVarianceXIsZero() {
		double[] x = {5, 5, 5, 5};
		double[] y = {1, 2, 3, 4};
		assertEquals(0.0,
			ConversationQualityMetrics.linearSlope(x, y), EPS);
	}

	// ── Realistic-end-to-end-ish sanity check ────────────────────────

	@Test
	public void sanity_realisticDuelEchoSignature() {
		/// Mimic what we observed in TestChatDuelLong: first 5 turns
		/// varied, then degenerates into echo. avgPairwise over the
		/// FULL window should be ~moderate, but over the last 3 alone
		/// should be high.
		List<String> fullRun = new ArrayList<>();
		fullRun.add("Hey there, how's it going? Beautiful day for a walk.");
		fullRun.add("Glad you came along — the breeze is amazing today.");
		fullRun.add("Want to head to the pond? The ducks should be out.");
		fullRun.add("I see you up there! I'm swinging as high as I can.");
		fullRun.add("I see you up there! I'm swinging as high as I can.");
		fullRun.add("I see you up there! I'm swinging as high as I can.");

		double full = ConversationQualityMetrics.avgPairwiseShingleJaccard(fullRun, 3);
		double tail = ConversationQualityMetrics.avgPairwiseShingleJaccard(
			fullRun.subList(3, 6), 3);
		assertTrue("tail echo should be near 1.0, got " + tail, tail > 0.9);
		assertFalse("full-run echo should be lower than tail, got "
			+ full + " vs " + tail, full > tail);
	}
}
