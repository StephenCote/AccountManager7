package org.cote.accountmanager.olio;

import org.cote.accountmanager.schema.type.TerrainEnumType;

public class Rules {
	
	/// exterior cell = cell_width * cell_multiplier.  E.G.: 10 x 10 = 100 meters.
	public static final int MAP_EXTERIOR_CELL_WIDTH = 10;
	public static final int MAP_EXTERIOR_CELL_HEIGHT = 10;
	public static final int MAP_EXTERIOR_CELL_MULTIPLIER = 10;
	
	public static final double ROLL_MAXIMUM = 20;
	public static final double ROLL_MAXIMUM_MODIFICATION = 19.5;
	public static final double ROLL_MINIMUM = 0;
	public static final double ROLL_MINIMUM_MODIFICATION = 0.5;
	public static final double ROLL_NATURAL_VARIANCE = 0.1;
	public static final double ROLL_FAILURE_THRESHOLD = 1;

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
		double typeOdds = Rules.ODDS_ANIMAL_GROUP;
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
}
