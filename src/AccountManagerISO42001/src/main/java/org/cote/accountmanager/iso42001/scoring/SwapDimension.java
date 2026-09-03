package org.cote.accountmanager.iso42001.scoring;

/**
 * Swap-test dimensions (design §5.2): a result is non-conforming if swapping the
 * group along one of these dimensions changes the output (in either direction).
 *
 * <p>Reserve implementation: part of the separate swap-pair design, not wired into the current
 * two-group bias run pipeline.</p>
 */
public enum SwapDimension {
	RACE,
	GENDER,
	RELIGION,
	POLITICAL
}
