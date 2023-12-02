package org.cote.accountmanager.olio;

public class Rules {

	public static final int INITIAL_STATISTICS_ALLOTMENT = 110;
	public static final int INITIAL_MINIMUM_STATISTIC = 1;
	public static final int MAXIMUM_STATISTIC = 20;
	public static final int MAXIMUM_CHILD_AGE = 10;
	public static final int MINIMUM_ADULT_AGE = 16;
	public static final int SENIOR_AGE = 72;
	public static final int MINIMUM_MARRY_AGE = MINIMUM_ADULT_AGE;
	public static final int MAXIMUM_AGE = 125;
	public static final int MAXIMUM_MARRY_AGE = MAXIMUM_AGE;
	public static final int MAXIMUM_FERTILITY_AGE_FEMALE = 50;
	public static final int MAXIMUM_FERTILITY_AGE_MALE = 70;
	
	public static final double ODDS_DEATH_BASE = 0.0001;
	public static final double ODDS_DEATH_MOD_LEADER = 0.0001;
	public static final double ODDS_DEATH_MOD_GENERAL = 0.0002;
	public static final double ODDS_DEATH_MOD_CHILD = 0.0025;
	
	public static final double ODDS_BIRTH_BASE = 0.001;
	public static final double ODDS_BIRTH_SINGLE = 0.001;
	public static final double ODDS_BIRTH_MARRIED = 0.025;
	public static final double ODDS_BIRTH_FAMILY_SIZE = 0.001;
	
	public static final int INITIAL_AVERAGE_DEATH_AGE = 75;
	public static final double INITIAL_MARRIAGE_RATE = 0.05;
	public static final double INITIAL_DIVORCE_RATE = 0.01;
	public static final boolean IS_PATRIARCHAL = true;
	
	/// odds someone will just magically show up
	/// Note: may be an individual, or a family, or groups of families
	///
	public static final double INITIAL_IMMIGRATION_RATE = 0.075;
	
	/// odds someone will just decide to leave
	/// Note: if a family, should drag the whole group with them
	///
	public static final double INITIAL_EMMIGRATION_RATE = 0.01;
}
