package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;

public enum InteractionEnumType {
	ACCOMMODATE,
	ALLY,
	BARTER,
	BEFRIEND,
	BETRAY,
	BREAK_UP,
	COERCE,
	COMBAT,
	COMMERCE,
	COMMUNICATE,
	COMPETE,
	CONFLICT,
	CONGREGATE,
	COOPERATE,
	CORRESPOND,
	CRITICIZE,
	DATE,
	DEBATE,
	DEFEND,
	ENTERTAIN,
	EXCHANGE,
	EXPRESS_GRATITUDE,
	EXPRESS_INDIFFERENCE,
	INTIMATE,
	MENTOR,
	NEGOTIATE,
	NONE,
	OPPOSE,
	PEER_PRESSURE,
	RECREATE,
	RELATE,
	ROMANCE,
	SHUN,
	SEPARATE,
	SOCIALIZE,
	THREATEN,
	UNKNOWN;

	private static List<InteractionEnumType> positiveInteractions = Arrays.asList(new InteractionEnumType[] {
		ACCOMMODATE, ALLY, BEFRIEND, COOPERATE, DATE, ENTERTAIN, EXPRESS_GRATITUDE, INTIMATE, MENTOR, RECREATE, ROMANCE
		
	});
	private static List<InteractionEnumType> neutralInteractions = Arrays.asList(new InteractionEnumType[] {
		COMMERCE, COMPETE, CORRESPOND, DEBATE, DEFEND, EXCHANGE, NEGOTIATE, SOCIALIZE, UNKNOWN, NONE
	});
	private static List<InteractionEnumType> negativeInteractions = Arrays.asList(new InteractionEnumType[] {
		BREAK_UP, COERCE, COMBAT, CONFLICT, CRITICIZE, EXPRESS_INDIFFERENCE, OPPOSE, PEER_PRESSURE, SHUN, THREATEN
	});
	
	public static List<InteractionEnumType> getPositiveInteractions() {
		return positiveInteractions;
	}

	public static List<InteractionEnumType> getNeutralInteractions() {
		return neutralInteractions;
	}

	public static List<InteractionEnumType> getNegativeInteractions() {
		return negativeInteractions;
	}

	public static boolean isPositive(InteractionEnumType type) {
		return positiveInteractions.contains(type);
	}
	
	public static boolean isNeutral(InteractionEnumType type) {
		return neutralInteractions.contains(type);
	}

	public static boolean isNegative(InteractionEnumType type) {
		return negativeInteractions.contains(type);
	}
	
	public static int getCompare(InteractionEnumType type) {
		if(positiveInteractions.contains(type)) return 1;
		if(negativeInteractions.contains(type)) return -1;
		return 0;
	}


}
