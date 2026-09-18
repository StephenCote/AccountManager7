package org.cote.accountmanager.provider;

public enum ComputeEnumType {
	UNKNOWN,
	AVG,
	/// Spread-preserving average. See ComputeUtil.getSpreadAverage.
	SAVG,
	PERC,
	PERC20
}
