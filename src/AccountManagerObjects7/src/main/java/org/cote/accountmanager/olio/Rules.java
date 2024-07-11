package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class Rules {
	
	private static final SecureRandom random = new SecureRandom();
	
	public static final int MAP_EXTERIOR_FEATURE_WIDTH = 100;
	public static final int MAP_EXTERIOR_FEATURE_HEIGHT = 100;
	/// exterior cell = cell_width * cell_multiplier.  E.G.: 10 x 10 = 100 meters.
	public static final int MAP_EXTERIOR_CELL_WIDTH = 10;
	public static final int MAP_EXTERIOR_CELL_HEIGHT = 10;
	public static final int MAP_EXTERIOR_CELL_MULTIPLIER = 10;
	
	public static final double ROLL_MAXIMUM = 20;
	public static final double ROLL_MAXIMUM_MODIFICATION = 19.5;
	public static final double ROLL_MINIMUM = 0;
	public static final double ROLL_MINIMUM_MODIFICATION = 0.5;
	public static final double ROLL_NATURAL_VARIANCE = 0.1;
	public static final double ROLL_NATURAL_VARIANCE_1 = 0.01;
	public static final double ROLL_FAILURE_THRESHOLD = 1;
	public static final double ROLL_FAILURE_THRESHOLD_1 = 0.01;
	public static final double ROLL_MAXIMUM_1 = 1.0;
	public static final int INITIAL_STATISTICS_ALLOTMENT = 130;
	public static final int INITIAL_STATISTICS_ALLOTMENT_CHILD = 65;
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
	
	public static final double ODDS_INVERT_ALIGNMENT = 0.3;
	
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
	
	/// chance there's some kind of wildlife in a given cell
	/// note: When the cell map is larger, like 100x100, then this can lead to a very large animal population
	public static final double ODDS_ANY_ANIMAL_GROUP = 0.2;
	
	/// 20% chance per animal group
	public static final double ODDS_ANIMAL_GROUP = 0.2;
	
	/// 99% chance of not tripping/slowing down
	public static final double ODDS_MOVEMENT = 0.99;
	
	/// 99.999% chance of moving through a natural habitat
	public static final double ODDS_HABITAT_MOVEMENT = 0.99999;

	/// 50.000% chance of moving through a non-native natural habitat
	public static final double ODDS_NONHABITAT_MOVEMENT = 0.50;
	
	/// General guideline when dropping a small group of animals in a location
	/// When using the map grid, the location size is a square km
	///
	public static final int ANIMAL_GROUP_COUNT = 10;
	
	/// 15% chance of an animal being dead
	///
	public static final double ANIMAL_CARCASS_ODDS = 0.15;

	/// Number of cells to include when evaluating populations across cells
	/// This is directly related to the map cell size, and effectively makes anything beyond invisible for certain evaluations
	/// E.G.: a distance of 3 cells = cell_width * cell multiplier * distance == meters, or 300 meters
	/// It's up to the implementing rule to look across features
	///
	public static final int MAXIMUM_OBSERVATION_DISTANCE = 3;
	
	public static double getAnimalOdds(TerrainEnumType type) {
		double typeOdds = ODDS_ANIMAL_GROUP;
		switch(type) {
			case GLACIER:
				typeOdds -= 0.1;
				break;
			case TUNDRA:
				typeOdds -= 0.05;
				break;
			case OCEAN:
				typeOdds += 0.2;
				break;
			case SHORELINE:
				break;
			case MARSH:
			case SWAMP:
				typeOdds += 0.1;
				break;
			case CLEAR:
				break;
			case DESERT:
			case DUNES:
				typeOdds -= 0.1;
				break;
			case OASIS:
			case POND:
				typeOdds += 0.3;
				break;
			case PLAINS:
				typeOdds += 0.1;
				break;
			case GRASS:
				typeOdds += 0.2;
				break;
			case SAVANNA:
				typeOdds += 0.3;
				break;
			case LAKE:
				typeOdds += 0.2;
				break;
			case VALLEY:
				typeOdds += 0.2;
				break;
			case RIVER:
				typeOdds += 0.1;
				break;
			case JUNGLE:
				typeOdds += 0.5;
				break;
			case FOREST:
				typeOdds += 0.2;
				break;
			case STREAM:
			case HILL:
				typeOdds += 0.1;
				break;
			case PLATEAU:
				typeOdds -= 0.05;
				break;
			case MOUNTAIN:
				typeOdds -= 0.1;
				break;
			case CAVE:
				typeOdds -= 0.05;
				break;
		}
		
		return typeOdds;
	}
	
	public static double getMovementOdds(TerrainEnumType type) {
		double typeOdds = ODDS_MOVEMENT;
		switch(type) {
			case SHELTER:
			case INDOORS:
			case CAVE:
			case VALLEY:
			case PLAINS:
			case GRASS:
			case SAVANNA:
			case OASIS:
			case CLEAR:
				/// default
				break;

			case FOREST:
			case SHORELINE:
			case TUNDRA:
			case MARSH:
			case SWAMP:
			case POND:
			case DUNES:
				typeOdds -= 0.2;
				break;
			case GLACIER:
				typeOdds -= 0.3;
				break;
			case JUNGLE:
			case RIVER:
				typeOdds -= 0.35;
				break;
			case LAKE:
				typeOdds -= 0.5;
				break;
			case OCEAN:
				typeOdds = 0.05;
				break;
			case DESERT:
				typeOdds -= 0.15;
				break;

			case STREAM:
			case HILL:
				typeOdds -= 0.1;
				break;
			case PLATEAU:
				typeOdds -= 0.05;
				break;
			case MOUNTAIN:
				typeOdds -= 0.3;
				break;
			case AIR:
			case UNDERWATER:
			case UNKNOWN:
			case VOID:
			default:
				typeOdds = 0.0;
				break;
		}
		
		return typeOdds;
	}
	
	public static final Double DEFAULT_TWO_OR_MORE_RACE_PERCENTAGE = 0.03;
	public static Map<RaceEnumType, Double> DEFAULT_RACE_PERCENTAGE = new HashMap<>();
	static {
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.A, 0.013);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.B, 0.063);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.C, 0.136);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.D, 0.003);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.E, 0.755);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.L, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.R, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.S, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.V, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.W, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.X, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.Y, 0.001);
		DEFAULT_RACE_PERCENTAGE.put(RaceEnumType.Z, 0.001);
	}
	
	public static boolean ruleNotUsually(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.NOT_USUALLY, ComparatorEnumType.LESS_THAN_OR_EQUALS);
	}
	public static boolean ruleLessFrequently(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.LESS_FREQUENTLY, ComparatorEnumType.LESS_THAN_OR_EQUALS);
	}
	public static boolean ruleSomewhat(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.SOMEWHAT, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public static boolean ruleLessSomewhat(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.SOMEWHAT, ComparatorEnumType.LESS_THAN_OR_EQUALS);
	}
	public static boolean ruleMostly(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.MOSTLY, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public static boolean ruleUsually(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.USUALLY, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public static boolean ruleFrequently(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.FREQUENTLY, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public static boolean ruleSlightly(VeryEnumType v1) {
		return VeryEnumType.compare(v1, VeryEnumType.SLIGHTLY, ComparatorEnumType.LESS_THAN_OR_EQUALS);
	}

	public static boolean rulePrettyGood(HighEnumType h1) {
		return HighEnumType.compare(h1, HighEnumType.ELEVATED, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}	
	public static boolean ruleBetterThan(HighEnumType h1, HighEnumType h2) {
		return HighEnumType.marginCompare(h1, h2, HighEnumType.ADEQUATE, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
	public static boolean ruleFair(HighEnumType h1) {
		return HighEnumType.compare(h1, HighEnumType.FAIR, ComparatorEnumType.GREATER_THAN_OR_EQUALS);
	}
}
