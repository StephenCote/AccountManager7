package org.cote.accountmanager.olio;

import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class InstinctUtil {

	public static boolean checkAdaptInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getAdapt(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkCooperateInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getCooperate(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkCryInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getCry(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkDrinkInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getDrink(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkFeedInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getFeed(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkFightInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getFight(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkFlightInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getFlight(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkHerdInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getHerd(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkHygieneInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getHygiene(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkLaughInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getLaugh(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkMateInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getMate(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkProtectInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getProtect(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkResistInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getResist(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	public static boolean checkSleepInstinct(AnimalProfile profile, InstinctEnumType threshold, double distanceFromTarget) {
		return checkInstinct(profile.getSleep(), threshold, ComparatorEnumType.GREATER_THAN, distanceFromTarget);
	}
	
	public static boolean checkInstinct(InstinctEnumType instinct, InstinctEnumType threshold, ComparatorEnumType comp, double distanceFromTarget) {
		return (InstinctEnumType.compare(instinct, threshold, distanceFromTarget) == comp);		
	}
	
}
