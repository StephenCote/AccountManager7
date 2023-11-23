package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CivilUtil {

	public static final Logger logger = LogManager.getLogger(CivilUtil.class);
	
	/// https://futurism.com/the-kardashev-scale-type-i-ii-iii-iv-v-civilization
	public static final String[] KARDASHEV_SCALE = new String[] { "Type I", "Type II", "Type III", "Type IV", "Type V"};

	/// https://en.wikipedia.org/wiki/Settlement_hierarchy
	public static final String[] DENSITY = new String[] {
		/// < 1000
		/// Homestead, Neighborhood (1 - 5 families)
		/// Hamlet or Band ( < 100 people)
		/// Village, Tribe < 150
		/// Church, grocery, post, ...
		"Miniscule",
		/// < 100,000
		/// Town, Satellite Town
		/// Clinic, Pharmacy/Alchemist, Bank, Supermarket, Police, Fire, School, Neighborhoods, Restaurants.
		"Low",
		/// < 250,000
		/// < 100,000
		"Lower Medium",
		/// < 1,000,000
		/// City
		"Upper Medium",
		/// < 3,000,000
		"Metropolis",
		/// < 10,000,000
		"Global City",
		/// < 100,000,000
		"Megalapolis",
		/// >= 100,000,000
		"Gigalopolis"
	};
	/// https://blog.adw.org/2016/10/eight-stages-rise-fall-civilizations/
	public static final String[] CYCLE = new String[] {
		"Bondage",
		"Spiritual Growth",
		"Great Courage",
		"Liberty",
		"Abundance",
		"Complacency",
		"Apathy",
		"Dependence"
	};
	
	private static String[] leaderPopulation = new String[]{"Political","Religious","Military","Business","Social","Trade"};
}
