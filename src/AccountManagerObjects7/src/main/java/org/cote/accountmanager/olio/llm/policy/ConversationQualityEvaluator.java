package org.cote.accountmanager.olio.llm.policy;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.cote.accountmanager.olio.llm.ConversationQualityMetrics;

/// Phase 6 (ConversationQualityPlan) periodic quality evaluator.
///
/// Pure local-metric evaluator — no LLM calls. Modelled on the metric
/// emission already shipped in `TestChatDuelLong` so production runs can
/// report the same numbers a baseline duel does.
///
/// Three scores per turn:
///   - echoScore       — avgPairwiseShingleJaccard over last N responses
///   - distinctScore   — distinctTokenRatio over the same window
///   - memUtilScore    — fraction of injected-memory content tokens that
///                       appear in the latest response
///
/// The evaluator also tracks a rolling trend across the last few results
/// so callers (or autotune) can react to "degrading" runs without
/// over-reacting to single-turn noise.
public final class ConversationQualityEvaluator {

	/// How many recent results to consider for trend calculation.
	public static final int TREND_WINDOW = 5;
	/// Minimum delta in echoMean across the trend window to call a direction.
	public static final double TREND_DELTA = 0.05;

	public enum Trend { IMPROVING, STABLE, DEGRADING }

	public static final class QualityResult {
		public final int turnIndex;
		public final double echoScore;
		public final double distinctScore;
		public final double memUtilScore;
		public final Trend trend;

		public QualityResult(int turnIndex, double echo, double distinct, double memUtil, Trend trend) {
			this.turnIndex = turnIndex;
			this.echoScore = echo;
			this.distinctScore = distinct;
			this.memUtilScore = memUtil;
			this.trend = trend;
		}

		@Override
		public String toString() {
			return String.format("turn=%d echo=%.3f distinct=%.3f memUtil=%.3f trend=%s",
				turnIndex, echoScore, distinctScore, memUtilScore, trend);
		}
	}

	private final int shingleK;
	private final LinkedList<QualityResult> history = new LinkedList<>();

	public ConversationQualityEvaluator() {
		this(3);
	}

	public ConversationQualityEvaluator(int shingleK) {
		this.shingleK = Math.max(1, shingleK);
	}

	/// One-shot pure evaluation, no history.
	/// `recentResponses` window MUST include `latestResponse` (caller usually
	/// passes the rolling window straight in).
	public static QualityResult evaluateOnce(
			int turnIndex,
			List<String> recentResponses,
			List<String> injectedMemoriesThisTurn,
			String latestResponse,
			int shingleK) {
		double echo = ConversationQualityMetrics.avgPairwiseShingleJaccard(
			safe(recentResponses), shingleK);
		double distinct = ConversationQualityMetrics.distinctTokenRatio(
			safe(recentResponses));
		double memUtil = ConversationQualityMetrics.memoryUtilization(
			safe(injectedMemoriesThisTurn), latestResponse == null ? "" : latestResponse);
		return new QualityResult(turnIndex, echo, distinct, memUtil, Trend.STABLE);
	}

	/// Stateful evaluation — records the result and returns it with the
	/// trend computed from the rolling history window.
	public QualityResult evaluate(
			int turnIndex,
			List<String> recentResponses,
			List<String> injectedMemoriesThisTurn,
			String latestResponse) {
		QualityResult oneShot = evaluateOnce(turnIndex, recentResponses,
			injectedMemoriesThisTurn, latestResponse, shingleK);
		history.addLast(oneShot);
		while (history.size() > TREND_WINDOW) history.removeFirst();
		Trend t = computeTrend(history);
		QualityResult withTrend = new QualityResult(
			turnIndex, oneShot.echoScore, oneShot.distinctScore, oneShot.memUtilScore, t);
		/// Replace the no-trend entry with the trend-aware one so subsequent
		/// trend calls work on the corrected value.
		history.removeLast();
		history.addLast(withTrend);
		return withTrend;
	}

	/// Trend over the supplied history (oldest first → newest last):
	///   IMPROVING when echoMean(newest half) < echoMean(oldest half) - delta
	///   DEGRADING when echoMean(newest half) > echoMean(oldest half) + delta
	///   STABLE   otherwise (or when fewer than 2 entries)
	public static Trend computeTrend(List<QualityResult> in) {
		if (in == null || in.size() < 2) return Trend.STABLE;
		int half = in.size() / 2;
		double older = avgEcho(in.subList(0, half == 0 ? 1 : half));
		double newer = avgEcho(in.subList(half, in.size()));
		double delta = newer - older;
		if (delta < -TREND_DELTA) return Trend.IMPROVING;
		if (delta >  TREND_DELTA) return Trend.DEGRADING;
		return Trend.STABLE;
	}

	private static double avgEcho(List<QualityResult> rs) {
		if (rs == null || rs.isEmpty()) return 0.0;
		double sum = 0.0;
		for (QualityResult r : rs) sum += r.echoScore;
		return sum / rs.size();
	}

	private static <T> List<T> safe(List<T> in) {
		return in == null ? new ArrayList<>() : in;
	}

	/// Read-only snapshot for inspection / tests.
	public List<QualityResult> history() {
		return new ArrayList<>(history);
	}

	public void reset() {
		history.clear();
	}
}
