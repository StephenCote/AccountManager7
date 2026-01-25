package org.cote.accountmanager.olio;

/**
 * Critical determination outcomes based on olio.txt rules:
 * 0 - 50%: Regular outcome
 * 51 - 85%: Double outcome
 * 86 - 95%: Triple/messed-up outcome
 * 96 - 100%: Deadly/total outcome
 */
public enum CriticalEnumType {
	REGULAR(1.0),       // 0-50%: Normal effect
	DOUBLE(2.0),        // 51-85%: Double effect
	TRIPLE(3.0),        // 86-95%: Triple/messed-up effect
	DEADLY(10.0);       // 96-100%: Deadly/total effect

	private double multiplier;

	private CriticalEnumType(double multiplier) {
		this.multiplier = multiplier;
	}

	public double getMultiplier() {
		return multiplier;
	}

	/**
	 * Determine critical level from a percentage roll (0-100)
	 */
	public static CriticalEnumType fromPercentage(int percentage) {
		if (percentage <= 50) {
			return REGULAR;
		} else if (percentage <= 85) {
			return DOUBLE;
		} else if (percentage <= 95) {
			return TRIPLE;
		} else {
			return DEADLY;
		}
	}
}
