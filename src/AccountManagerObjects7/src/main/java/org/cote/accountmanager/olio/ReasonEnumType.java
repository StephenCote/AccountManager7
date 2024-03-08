package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;

public enum ReasonEnumType {
	UNKNOWN,
	AGE,
	AVARICE,
	IMMATURITY,
	MATURITY,
	COMMUNITY,
	CONFIDENCE,
	COWARDICE,
	COMPANIONSHIP,
	FRIENDSHIP,
	INTRAVERSION,
	EXTRAVERSION,
	INSTINCT,
	MORALITY,
	NONE,
	AMORALITY,
	SENILITY,
	SPIRITUALITY,
	LESS_ATTRACTIVE,
	ATTRACTIVE_NARCISSISM,
	NARCISSISM,
	PSYCHOPATHY,
	SANITY,
	MACHIAVELLIANISM
	;
	
	private static List<ReasonEnumType> positiveReasons = Arrays.asList(new ReasonEnumType[] {
		MATURITY, COMMUNITY, CONFIDENCE, FRIENDSHIP, MATURITY, MORALITY, SANITY, SPIRITUALITY
	});
	
	private static List<ReasonEnumType> neutralReasons = Arrays.asList(new ReasonEnumType[] {
		UNKNOWN, AGE,COMPANIONSHIP, INTRAVERSION, EXTRAVERSION, INSTINCT, NONE
	});
	
	private static List<ReasonEnumType> negativeReasons = Arrays.asList(new ReasonEnumType[] {
		IMMATURITY, COWARDICE, AVARICE, AMORALITY, SENILITY, LESS_ATTRACTIVE, ATTRACTIVE_NARCISSISM, NARCISSISM, PSYCHOPATHY, MACHIAVELLIANISM  
	});
	
	public static boolean isPositive(ReasonEnumType type) {
		return positiveReasons.contains(type);
	}
	
	public static boolean isNeutral(ReasonEnumType type) {
		return neutralReasons.contains(type);
	}

	public static boolean isNegative(ReasonEnumType type) {
		return negativeReasons.contains(type);
	}
	
	
	public static List<ReasonEnumType> getPositiveReasons() {
		return positiveReasons;
	}

	public static List<ReasonEnumType> getNeutralReasons() {
		return neutralReasons;
	}

	public static List<ReasonEnumType> getNegativeReasons() {
		return negativeReasons;
	}

	public static int getCompare(ReasonEnumType ret) {
		if(positiveReasons.contains(ret)) return 1;
		if(negativeReasons.contains(ret)) return -1;
		return 0;
	}
}
