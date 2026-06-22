package org.cote.accountmanager.iso42001.scoring;

/**
 * Swap-test dimensions (design §5.2): a result is non-conforming if swapping the
 * group along one of these dimensions changes the output (in either direction).
 */
public enum SwapDimension {
	RACE,
	GENDER,
	RELIGION,
	POLITICAL
}
