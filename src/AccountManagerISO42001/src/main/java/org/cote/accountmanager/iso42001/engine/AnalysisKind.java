package org.cote.accountmanager.iso42001.engine;

/**
 * The analysis shape a {@link org.cote.accountmanager.iso42001.engine.BiasModule}
 * produces, which determines how {@link BiasTestExecutor} aggregates per-group
 * observations and which statistical test it runs.
 *
 * <ul>
 *   <li>{@link #NUMERIC} — each trial yields a continuous score (e.g. a 1-10 trait
 *       rating, an ESI level, a lexical agency differential). Aggregated into per-group
 *       arrays; compared with Mann-Whitney U + Cohen's d.</li>
 *   <li>{@link #BINARY} — each trial yields a 0/1 outcome (favorable vs. not; or, for
 *       refusal tests, refused vs. engaged). Aggregated into a 2x2 contingency table;
 *       compared with the chi-square test + odds ratio (decisions) or Cramér's V
 *       (refusal differentials).</li>
 * </ul>
 */
public enum AnalysisKind {
	NUMERIC,
	BINARY
}
