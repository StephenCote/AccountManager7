package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;

public enum CharacterRoleEnumType {

	ACQUAINTENCE,
	ANTAGONIST,
	ANTIHERO,
	CONFIDANT,
	CONTAGONIST,
	DEUTERAGONIST,
	ENEMY_INTEREST,
	FOIL,
	GUIDE,
	HENCHMAN,
	INDETERMINATE,
	LOVE_INTEREST,
	STRANGER,
	FRIEND_INTEREST,
	COMPANION,
	PROTAGONIST,
	TEMPTRESS,
	UNKNOWN
	;
	
	private static List<CharacterRoleEnumType> positiveRoles = Arrays.asList(new CharacterRoleEnumType[] {
		DEUTERAGONIST, CONFIDANT, LOVE_INTEREST, FRIEND_INTEREST, COMPANION, PROTAGONIST
	});
	private static List<CharacterRoleEnumType> neutralRoles = Arrays.asList(new CharacterRoleEnumType[] {
			ACQUAINTENCE, ANTIHERO, FOIL, GUIDE, INDETERMINATE, STRANGER, UNKNOWN
		});
	private static List<CharacterRoleEnumType> negativeRoles = Arrays.asList(new CharacterRoleEnumType[] {
		ANTAGONIST, CONTAGONIST, ENEMY_INTEREST, HENCHMAN, TEMPTRESS
	});
	
	public static boolean isPositive(CharacterRoleEnumType type) {
		return positiveRoles.contains(type);
	}
	
	public static boolean isNeutral(CharacterRoleEnumType type) {
		return neutralRoles.contains(type);
	}

	public static boolean isNegative(CharacterRoleEnumType type) {
		return negativeRoles.contains(type);
	}
	
	public static int getCompare(CharacterRoleEnumType type) {
		if(positiveRoles.contains(type)) return 1;
		if(negativeRoles.contains(type)) return -1;
		return 0;
	}

}
