package org.cote.accountmanager.iso42001.util;

/**
 * An age cohort defined by a birth-year range and a graduation-year range
 * (iso42001-bias.md §2.1 {@code name_banks.age}).
 */
public class AgeRange {

	private final int birthYearStart;
	private final int birthYearEnd;
	private final int graduationYearStart;
	private final int graduationYearEnd;

	public AgeRange(int birthYearStart, int birthYearEnd, int graduationYearStart, int graduationYearEnd) {
		this.birthYearStart = birthYearStart;
		this.birthYearEnd = birthYearEnd;
		this.graduationYearStart = graduationYearStart;
		this.graduationYearEnd = graduationYearEnd;
	}

	public int getBirthYearStart() { return birthYearStart; }
	public int getBirthYearEnd() { return birthYearEnd; }
	public int getGraduationYearStart() { return graduationYearStart; }
	public int getGraduationYearEnd() { return graduationYearEnd; }

	@Override
	public String toString() {
		return "AgeRange{birth=[" + birthYearStart + "," + birthYearEnd + "], grad=["
			+ graduationYearStart + "," + graduationYearEnd + "]}";
	}
}
