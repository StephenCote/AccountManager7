package org.cote.accountmanager.iso42001.metrics;

/**
 * Immutable-by-convention value object holding the aggregated LLM operational metrics for one session
 * (= one {@code iso42001.testRun.objectId}), produced by {@link LangfuseMetricsClient}.
 *
 * <p>The {@link #available} flag distinguishes three states the report generator must handle:
 * <ul>
 *   <li>{@code unavailable()} — Langfuse is not configured, unreachable, or errored. The report omits
 *       the operational-metrics section entirely (so a metrics-free report is byte-for-byte the same as
 *       before this feature existed, preserving the existing 4-section report test and hash).</li>
 *   <li>{@code empty()} — configured and reachable, but no traces exist for the session yet (available
 *       with zero aggregates). The section may render with zeros or the caller may still choose to omit.</li>
 *   <li>a populated aggregate — available with real cost/latency/token totals.</li>
 * </ul></p>
 *
 * <p>No Langfuse types leak out of this package; this is a plain data carrier the report layer consumes.</p>
 */
public class LangfuseMetrics {

	/** True only when Langfuse was configured AND reachable (even if it returned zero traces). */
	public boolean available;

	public int traceCount;
	public int observationCount;

	public long promptTokens;
	public long completionTokens;
	public long totalTokens;

	public double totalCostUsd;

	/** Sum of per-trace latencies in milliseconds, over {@link #latencySamples} traces. */
	public double totalLatencyMs;
	public int latencySamples;

	/** Mean per-trace latency in milliseconds (0 when no latency samples). */
	public double averageLatencyMs;

	private LangfuseMetrics(boolean available) {
		this.available = available;
	}

	/** Not configured / unreachable / errored: report omits the section. */
	public static LangfuseMetrics unavailable() {
		return new LangfuseMetrics(false);
	}

	/** Configured and reachable, zero aggregates so far. */
	public static LangfuseMetrics empty() {
		return new LangfuseMetrics(true);
	}

	/** Finalize derived aggregates (mean latency) after summation; returns {@code this} for chaining. */
	public LangfuseMetrics finish() {
		this.averageLatencyMs = (latencySamples > 0) ? (totalLatencyMs / latencySamples) : 0.0;
		return this;
	}

	/**
	 * Fold another session's aggregate into this one (used when a report spans multiple test-run
	 * sessions). Once any contributing session is available, the combined result is available. Sums
	 * counts/tokens/cost/latency; the caller must call {@link #finish()} after all merges to recompute
	 * the mean latency. Returns {@code this} for chaining.
	 */
	public LangfuseMetrics merge(LangfuseMetrics other) {
		if (other == null || !other.available) {
			return this;
		}
		this.available = true;
		this.traceCount += other.traceCount;
		this.observationCount += other.observationCount;
		this.promptTokens += other.promptTokens;
		this.completionTokens += other.completionTokens;
		this.totalTokens += other.totalTokens;
		this.totalCostUsd += other.totalCostUsd;
		this.totalLatencyMs += other.totalLatencyMs;
		this.latencySamples += other.latencySamples;
		return this;
	}

	public boolean isAvailable() {
		return available;
	}

	public boolean hasData() {
		return available && traceCount > 0;
	}
}
