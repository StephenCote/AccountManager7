package org.cote.accountmanager.iso42001.engine;

/**
 * Effect-size metric reported alongside a p-value (iso42001.md §4.3).
 *
 * <ul>
 *   <li>{@link #COHENS_D} — numeric scores (Mann-Whitney/Kruskal-Wallis paths)</li>
 *   <li>{@link #ODDS_RATIO} — binary decisions (Chi-square / Fisher's exact 2x2)</li>
 *   <li>{@link #CRAMERS_V} — categorical / multi-class (Chi-square)</li>
 * </ul>
 */
public enum EffectSizeType {
	COHENS_D,
	ODDS_RATIO,
	CRAMERS_V
}
