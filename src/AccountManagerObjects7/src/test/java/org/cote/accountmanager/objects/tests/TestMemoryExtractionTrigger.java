package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.olio.llm.Chat;
import org.junit.Test;

/// MemoryKeyframeDecouplingPlan §3 — pure-logic tests for the independent
/// memory extraction trigger decision. No DB / no LLM.
public class TestMemoryExtractionTrigger {

	// ── disabled paths ──────────────────────────────────────────────────

	@Test
	public void disabledWhenExtractMemoriesFalse() {
		assertFalse(Chat.shouldExtractMemory(false, 5, 100, 0, 1));
		assertFalse(Chat.shouldExtractMemory(false, 0, 0, 0, 0));
	}

	@Test
	public void disabledWhenEveryZero() {
		assertFalse(Chat.shouldExtractMemory(true, 0, 100, 0, 1));
	}

	@Test
	public void disabledWhenEveryNegative() {
		assertFalse(Chat.shouldExtractMemory(true, -5, 100, 0, 1));
	}

	@Test
	public void disabledWhenNoMessages() {
		assertFalse(Chat.shouldExtractMemory(true, 5, 0, 0, 1));
		assertFalse(Chat.shouldExtractMemory(true, 5, -1, 0, 1));
	}

	// ── first-trigger semantics (lastAt = 0) ───────────────────────────

	@Test
	public void firstTrigger_belowThresholdNoFire() {
		/// intro=1, msgSize=4, sinceLast=3. every=5. 3 < 5 → no.
		assertFalse(Chat.shouldExtractMemory(true, 5, 4, 0, 1));
	}

	@Test
	public void firstTrigger_atThresholdFires() {
		/// intro=1, msgSize=6, sinceLast=5. every=5. 5 >= 5 → yes.
		assertTrue(Chat.shouldExtractMemory(true, 5, 6, 0, 1));
	}

	@Test
	public void firstTrigger_pastThresholdFires() {
		assertTrue(Chat.shouldExtractMemory(true, 5, 20, 0, 1));
	}

	@Test
	public void firstTrigger_assistIntroOverheadHonored() {
		/// assist=true means intro=pruneSkip+1, e.g. 4. msgSize=8, sinceLast=4.
		/// every=5. 4 < 5 → no.
		assertFalse(Chat.shouldExtractMemory(true, 5, 8, 0, 4));
		/// msgSize=9, sinceLast=5 → yes.
		assertTrue(Chat.shouldExtractMemory(true, 5, 9, 0, 4));
	}

	// ── subsequent-trigger semantics (lastAt > 0) ──────────────────────

	@Test
	public void subsequent_belowThresholdNoFire() {
		/// lastAt=10, msgSize=14, sinceLast=4. every=5. 4 < 5 → no.
		assertFalse(Chat.shouldExtractMemory(true, 5, 14, 10, 1));
	}

	@Test
	public void subsequent_atThresholdFires() {
		assertTrue(Chat.shouldExtractMemory(true, 5, 15, 10, 1));
	}

	@Test
	public void subsequent_lastAtIgnoresIntroOverhead() {
		/// Once lastAt > 0, intro overhead is irrelevant — we measure from
		/// the recorded extraction point.
		assertFalse(Chat.shouldExtractMemory(true, 5, 14, 10, 100));
		assertTrue(Chat.shouldExtractMemory(true, 5, 15, 10, 100));
	}

	// ── stale / corrupt state ──────────────────────────────────────────

	@Test
	public void staleLastAtBeyondMsgSizeResetsToZero() {
		/// e.g. messages were pruned: lastAt=50 but msgSize=20. Treat lastAt as 0
		/// so we use intro overhead and can fire again.
		/// intro=1, msgSize=20, sinceLast=19, every=5 → yes.
		assertTrue(Chat.shouldExtractMemory(true, 5, 20, 50, 1));
	}

	@Test
	public void negativeLastAtResetsToZero() {
		assertTrue(Chat.shouldExtractMemory(true, 5, 20, -3, 1));
		assertFalse(Chat.shouldExtractMemory(true, 5, 4, -3, 1));
	}

	@Test
	public void negativeIntroOverheadTreatedAsZero() {
		/// intro=-5 → treat as 0. msgSize=5, sinceLast=5, every=5 → yes.
		assertTrue(Chat.shouldExtractMemory(true, 5, 5, 0, -5));
	}

	// ── independence from keyframe semantics ───────────────────────────

	@Test
	public void independenceFromKeyframeEvery() {
		/// Caller doesn't pass keyframeEvery — the trigger has no awareness
		/// of it. This test is a self-documenting check that the contract
		/// stays free of keyframe coupling. (Compilation alone proves it.)
		assertTrue(Chat.shouldExtractMemory(true, 5, 6, 0, 1));
		assertFalse(Chat.shouldExtractMemory(true, 5, 4, 0, 1));
	}

	@Test
	public void everyOneFiresOnEveryNewMessage() {
		/// every=1 means "extract on every new message past the intro".
		/// intro=1, msgSize=2, sinceLast=1, every=1 → yes.
		assertTrue(Chat.shouldExtractMemory(true, 1, 2, 0, 1));
		/// After a recorded extraction at msgSize=2, next msgSize=3 should fire again.
		assertTrue(Chat.shouldExtractMemory(true, 1, 3, 2, 1));
	}

	@Test
	public void largeEveryDoesntFirePrematurely() {
		assertFalse(Chat.shouldExtractMemory(true, 100, 50, 0, 1));
		assertTrue(Chat.shouldExtractMemory(true, 100, 101, 0, 1));
	}
}
