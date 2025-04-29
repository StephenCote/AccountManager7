package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;

public enum ReasonEnumType {
	AGE,
	ALOOFNESS,
	AMORALITY,
	ATTRACTION,
	ATTRACTIVE_NARCISSISM,
	AVARICE,
	COERCION,
	COMMERCE,
	COMMUNITY,
	COMPANIONSHIP,
	CONFIDENCE,
	COWARDICE,
	EXTRAVERSION,
	FRIENDSHIP,
	GENEROSITY,
	GUARDIANSHIP,
	HOSTILITY,
	IMMATURITY,
	INSTINCT,
	INTIMACY,
	INTIMIDATION,
	INTRAVERSION,
	LESS_ATTRACTIVE,
	MACHIAVELLIANISM,
	MATURITY,
	MORALITY,
	NARCISSISM,
	NONE,
	PEER_PRESSURE,
	POLITICAL,
	PSYCHOPATHY,
	REVENGE,
	REVULSION,
	SADISM,
	SANITY,
	SENILITY,
	SENSUALITY,
	SPIRITUALITY,
	SOCIALIZE,
	UNKNOWN
	;
	
	private static List<ReasonEnumType> negativeReasons = Arrays.asList(new ReasonEnumType[] {
		ALOOFNESS, REVULSION, IMMATURITY, COWARDICE, AVARICE, AMORALITY, SENILITY, LESS_ATTRACTIVE, ATTRACTIVE_NARCISSISM, NARCISSISM, PSYCHOPATHY, MACHIAVELLIANISM, SADISM  
	});
	
	private static List<ReasonEnumType> neutralReasons = Arrays.asList(new ReasonEnumType[] {
		UNKNOWN, COMMERCE, AGE,COMPANIONSHIP, INTRAVERSION, EXTRAVERSION, INSTINCT, NONE
	});
	
	private static List<ReasonEnumType> positiveReasons = Arrays.asList(new ReasonEnumType[] {
		ATTRACTION, INTIMACY, SENSUALITY, GENEROSITY, MATURITY, COMMUNITY, CONFIDENCE, FRIENDSHIP, MATURITY, MORALITY, SANITY, SPIRITUALITY
	});
	
	public static int getCompare(ReasonEnumType ret) {
		if(positiveReasons.contains(ret)) return 1;
		if(negativeReasons.contains(ret)) return -1;
		return 0;
	}
	
	public static List<ReasonEnumType> getNegativeReasons() {
		return negativeReasons;
	}

	public static List<ReasonEnumType> getNeutralReasons() {
		return neutralReasons;
	}
	
	
	public static List<ReasonEnumType> getPositiveReasons() {
		return positiveReasons;
	}

	public static boolean isNegative(ReasonEnumType type) {
		return negativeReasons.contains(type);
	}

	public static boolean isNeutral(ReasonEnumType type) {
		return neutralReasons.contains(type);
	}

	public static boolean isPositive(ReasonEnumType type) {
		return positiveReasons.contains(type);
	}
}
