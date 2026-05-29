package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.cote.accountmanager.olio.llm.ConversationQualityMetrics;
import org.junit.Test;

/// Phase 1 (ConversationQualityPlan) unit tests for echo-detection
/// decision logic. The detection itself is a pure function of the
/// rolling window + threshold; the wire injection step touches Chat
/// instance state and is exercised via the duel integration test.
///
/// These tests verify the GATING decision Chat.maybeInjectEchoSteering
/// makes — same threshold logic, in isolation.
public class TestEchoSuppression {

	/// Mirror of the gating decision in Chat.maybeInjectEchoSteering.
	/// Kept here as a free function so the decision logic is testable
	/// without spinning up a Chat instance + chatConfig + LLM pipeline.
	///
	/// Returns true if the steering message SHOULD be injected given
	/// the current rolling window and threshold.
	private static boolean shouldSteer(LinkedList<String> recent, double threshold) {
		if (threshold <= 0.0) return false;          // disabled
		if (recent.size() < 2) return false;         // not enough signal
		double sim = ConversationQualityMetrics
			.avgPairwiseShingleJaccard(recent, 3);
		return sim > threshold;
	}

	// ── gating: disabled / insufficient signal ───────────────────────

	@Test
	public void thresholdZeroDisables() {
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"same line over and over",
			"same line over and over",
			"same line over and over"
		));
		assertFalse("threshold=0 disables steering", shouldSteer(w, 0.0));
	}

	@Test
	public void thresholdNegativeDisables() {
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"echo echo echo line",
			"echo echo echo line"
		));
		assertFalse("threshold<0 disables steering", shouldSteer(w, -0.5));
	}

	@Test
	public void emptyWindowDoesNotSteer() {
		assertFalse(shouldSteer(new LinkedList<>(), 0.6));
	}

	@Test
	public void singleEntryDoesNotSteer() {
		LinkedList<String> w = new LinkedList<>();
		w.add("just one response, nothing to compare");
		assertFalse(shouldSteer(w, 0.6));
	}

	// ── triggers ─────────────────────────────────────────────────────

	@Test
	public void identicalResponsesTrigger() {
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"swinging high and watching the clouds drift by",
			"swinging high and watching the clouds drift by",
			"swinging high and watching the clouds drift by"
		));
		assertTrue("identical responses should trigger at 0.6", shouldSteer(w, 0.6));
	}

	@Test
	public void nearIdenticalTriggers() {
		/// Single word difference at end. Most 3-shingles overlap.
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"the breeze is good and we should keep going",
			"the breeze is good and we should keep going now",
			"the breeze is good and we should keep going forever"
		));
		assertTrue("near-identical responses should trigger", shouldSteer(w, 0.6));
	}

	// ── does NOT trigger ─────────────────────────────────────────────

	@Test
	public void naturalVariationDoesNotTrigger() {
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"That's an interesting idea — let's take the long way home.",
			"I'll grab the bag and meet you at the gate.",
			"Sure, just give me five minutes to finish up."
		));
		assertFalse("varied responses should not trigger at 0.6",
			shouldSteer(w, 0.6));
	}

	@Test
	public void thresholdJustAboveAvgDoesNotTrigger() {
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"alpha beta gamma delta epsilon",
			"alpha beta gamma zeta eta",
			"alpha beta theta iota kappa"
		));
		double sim = ConversationQualityMetrics
			.avgPairwiseShingleJaccard(w, 3);
		assertFalse("threshold just above avg should not trigger",
			shouldSteer(w, sim + 0.01));
		assertTrue("threshold just below avg should trigger",
			shouldSteer(w, sim - 0.01));
	}

	// ── stability properties ─────────────────────────────────────────

	@Test
	public void recoveryAfterAnEchoTurnReleasesSteering() {
		/// Simulate: 3 identical responses → echo. Then one fresh
		/// response gets added (and oldest evicted). The new window
		/// has only 2 identical entries + 1 fresh — Jaccard should
		/// drop below threshold.
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"echoing same line again",
			"echoing same line again",
			"echoing same line again"
		));
		assertTrue("baseline echo state",
			shouldSteer(w, 0.6));
		/// addFirst + cap at 3 (matches Chat.recordAssistantResponse)
		w.addFirst("Let's switch topics and talk about the storm instead.");
		while (w.size() > 3) w.removeLast();
		assertFalse("steering should release after one varied turn",
			shouldSteer(w, 0.6));
	}

	// ── tunable threshold ───────────────────────────────────────────

	@Test
	public void higherThresholdRequiresMoreSeverity() {
		/// Build a window with KNOWN moderate similarity. Two identical
		/// lines and one varied line gives ~0.33 avg pairwise (1/3 of
		/// pairs identical, 2/3 of pairs disjoint).
		LinkedList<String> w = new LinkedList<>(Arrays.asList(
			"the breeze is good today friend",
			"the breeze is good today friend",
			"completely different topic over here"
		));
		double sim = ConversationQualityMetrics
			.avgPairwiseShingleJaccard(w, 3);
		assertTrue("test premise: sim > 0.2 (got " + sim + ")", sim > 0.2);
		assertTrue("test premise: sim < 0.5 (got " + sim + ")", sim < 0.5);
		/// Steers at lenient threshold, not at strict.
		assertTrue("triggers below sim",  shouldSteer(w, sim - 0.05));
		assertFalse("does not trigger above sim", shouldSteer(w, sim + 0.05));
	}
}
