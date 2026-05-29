package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.olio.llm.policy.ConversationQualityEvaluator;
import org.cote.accountmanager.olio.llm.policy.ConversationQualityEvaluator.QualityResult;
import org.cote.accountmanager.olio.llm.policy.ConversationQualityEvaluator.Trend;
import org.junit.Test;

/// Phase 6 (ConversationQualityPlan) unit tests for the quality
/// evaluator. Pure-function tests, no DB / no LLM.
public class TestConversationQualityEvaluator {

	private static QualityResult r(double echo) {
		return new QualityResult(0, echo, 0.5, 0.0, Trend.STABLE);
	}

	// ── evaluateOnce ────────────────────────────────────────────────────

	@Test
	public void evaluateOnce_emptyResponsesReturnsZeroEcho() {
		QualityResult q = ConversationQualityEvaluator.evaluateOnce(
			1, new ArrayList<>(), new ArrayList<>(), "", 3);
		assertEquals(0.0, q.echoScore, 1e-9);
	}

	@Test
	public void evaluateOnce_identicalResponsesScoreOne() {
		List<String> same = Arrays.asList(
			"this is the same response said twice",
			"this is the same response said twice");
		QualityResult q = ConversationQualityEvaluator.evaluateOnce(
			5, same, new ArrayList<>(), same.get(0), 3);
		assertEquals(1.0, q.echoScore, 1e-9);
	}

	@Test
	public void evaluateOnce_variedResponsesScoreLow() {
		List<String> varied = Arrays.asList(
			"the quick brown fox jumps over the lazy dog",
			"never gonna give you up never gonna let you down",
			"all happy families are alike each unhappy family unhappy in its own way");
		QualityResult q = ConversationQualityEvaluator.evaluateOnce(
			10, varied, new ArrayList<>(), varied.get(0), 3);
		assertTrue("expected low echo, got " + q.echoScore, q.echoScore < 0.1);
		assertTrue("expected high distinct, got " + q.distinctScore, q.distinctScore > 0.7);
	}

	@Test
	public void evaluateOnce_memUtilCountsOverlappingTokens() {
		List<String> mem = Arrays.asList("Alder is from the Northwood village");
		String response = "I told Alder that the Northwood village is dangerous";
		QualityResult q = ConversationQualityEvaluator.evaluateOnce(
			1, Arrays.asList(response), mem, response, 3);
		assertTrue("expected non-zero memUtil, got " + q.memUtilScore, q.memUtilScore > 0.0);
	}

	@Test
	public void evaluateOnce_nullInputsSafe() {
		QualityResult q = ConversationQualityEvaluator.evaluateOnce(
			1, null, null, null, 3);
		assertEquals(0.0, q.echoScore, 1e-9);
		assertEquals(0.0, q.memUtilScore, 1e-9);
	}

	@Test
	public void evaluateOnce_trendDefaultsToStable() {
		QualityResult q = ConversationQualityEvaluator.evaluateOnce(
			1, new ArrayList<>(), new ArrayList<>(), "", 3);
		assertEquals(Trend.STABLE, q.trend);
	}

	// ── computeTrend ────────────────────────────────────────────────────

	@Test
	public void trend_emptyHistoryReturnsStable() {
		assertEquals(Trend.STABLE, ConversationQualityEvaluator.computeTrend(new ArrayList<>()));
	}

	@Test
	public void trend_singleEntryReturnsStable() {
		assertEquals(Trend.STABLE,
			ConversationQualityEvaluator.computeTrend(Arrays.asList(r(0.5))));
	}

	@Test
	public void trend_echoRisingReportsDegrading() {
		/// Echo doubles from 0.1 → 0.5 — clearly degrading
		List<QualityResult> hist = Arrays.asList(r(0.1), r(0.1), r(0.5), r(0.5));
		assertEquals(Trend.DEGRADING, ConversationQualityEvaluator.computeTrend(hist));
	}

	@Test
	public void trend_echoFallingReportsImproving() {
		List<QualityResult> hist = Arrays.asList(r(0.8), r(0.7), r(0.3), r(0.1));
		assertEquals(Trend.IMPROVING, ConversationQualityEvaluator.computeTrend(hist));
	}

	@Test
	public void trend_smallNoiseStaysStable() {
		List<QualityResult> hist = Arrays.asList(r(0.20), r(0.21), r(0.22), r(0.19));
		assertEquals(Trend.STABLE, ConversationQualityEvaluator.computeTrend(hist));
	}

	// ── stateful evaluate ──────────────────────────────────────────────

	@Test
	public void evaluate_recordsHistory() {
		ConversationQualityEvaluator ev = new ConversationQualityEvaluator();
		for (int i = 0; i < 3; i++) {
			ev.evaluate(i, Arrays.asList("a b c"), new ArrayList<>(), "a b c");
		}
		assertEquals(3, ev.history().size());
	}

	@Test
	public void evaluate_cappedAtTrendWindow() {
		ConversationQualityEvaluator ev = new ConversationQualityEvaluator();
		for (int i = 0; i < 10; i++) {
			ev.evaluate(i, Arrays.asList("a b c"), new ArrayList<>(), "a b c");
		}
		assertEquals(ConversationQualityEvaluator.TREND_WINDOW, ev.history().size());
	}

	@Test
	public void evaluate_trendDetectsBuildupOfEcho() {
		ConversationQualityEvaluator ev = new ConversationQualityEvaluator();
		/// Two clean turns followed by three echoing turns
		QualityResult q1 = ev.evaluate(1, Arrays.asList("alpha bravo charlie delta"), new ArrayList<>(), "alpha bravo charlie delta");
		QualityResult q2 = ev.evaluate(2, Arrays.asList(
			"alpha bravo charlie delta",
			"echo foxtrot golf hotel"),
			new ArrayList<>(), "echo foxtrot golf hotel");
		QualityResult q3 = ev.evaluate(3, Arrays.asList(
			"echo foxtrot golf hotel",
			"echo foxtrot golf hotel"),
			new ArrayList<>(), "echo foxtrot golf hotel");
		QualityResult q4 = ev.evaluate(4, Arrays.asList(
			"echo foxtrot golf hotel",
			"echo foxtrot golf hotel",
			"echo foxtrot golf hotel"),
			new ArrayList<>(), "echo foxtrot golf hotel");
		QualityResult q5 = ev.evaluate(5, Arrays.asList(
			"echo foxtrot golf hotel",
			"echo foxtrot golf hotel"),
			new ArrayList<>(), "echo foxtrot golf hotel");
		assertEquals(Trend.DEGRADING, q5.trend);
	}

	@Test
	public void resetClearsHistory() {
		ConversationQualityEvaluator ev = new ConversationQualityEvaluator();
		ev.evaluate(1, Arrays.asList("a"), new ArrayList<>(), "a");
		ev.evaluate(2, Arrays.asList("a", "b"), new ArrayList<>(), "b");
		assertTrue(ev.history().size() > 0);
		ev.reset();
		assertEquals(0, ev.history().size());
	}

	@Test
	public void toString_includesAllScores() {
		QualityResult q = new QualityResult(7, 0.25, 0.66, 0.10, Trend.STABLE);
		String s = q.toString();
		assertTrue(s.contains("turn=7"));
		assertTrue(s.contains("echo=0.250"));
		assertTrue(s.contains("distinct=0.660"));
		assertTrue(s.contains("memUtil=0.100"));
		assertTrue(s.contains("trend=STABLE"));
	}
}
