package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum InteractionEnumType {
	ACCOMMODATE,
	ALLY,
	COERCE,
	COMBAT,
	COMMERCE,
	COMPETE,
	CONFLICT,
	COOPERATE,
	CORRESPOND,
	CRITICIZE,
	DATE,
	DEBATE,
	DEFEND,
	ENTERTAIN,
	EXCHANGE,
	EXPRESS_GRATITUDE,
	INTIMATE,
	MENTOR,
	NEGOTIATE,
	NONE,
	OPPOSE,
	PEER_PRESSURE,
	RECREATE,
	ROMANCE,
	SOCIALIZE,
	THREATEN,
	UNKNOWN;
	
	private static List<InteractionEnumType> positiveInteractions = Arrays.asList(new InteractionEnumType[] {
		ACCOMMODATE, ALLY, COOPERATE, DATE, ENTERTAIN, EXPRESS_GRATITUDE, INTIMATE, MENTOR, RECREATE, ROMANCE
		
	});
	private static List<InteractionEnumType> neutralInteractions = Arrays.asList(new InteractionEnumType[] {
		COMMERCE, COMPETE, CORRESPOND, DEBATE, DEFEND, EXCHANGE, NEGOTIATE, SOCIALIZE, UNKNOWN, NONE
	});
	private static List<InteractionEnumType> negativeInteractions = Arrays.asList(new InteractionEnumType[] {
		COERCE, COMBAT, CONFLICT, CRITICIZE, OPPOSE, PEER_PRESSURE, THREATEN
	});
	
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
