package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CivilUtil {

	public static final Logger logger = LogManager.getLogger(CivilUtil.class);
	
	/// https://futurism.com/the-kardashev-scale-type-i-ii-iii-iv-v-civilization
	public static final String[] KARDASHEV_SCALE = new String[] { "Type I", "Type II", "Type III", "Type IV", "Type V"};

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
	
	// https://education.nationalgeographic.org/resource/key-components-civilization/
	public static final String[] COMPONENTS = new String[] {
		"Urban Areas",
		"Monuments",
		"Shared Communication",
		"Administration",
		"Infrastructure",
		"Division of Labor",
		"Class Structure",
		"Trade",
		"Conflict",
		"Exploration",
		"Innovation",
		"Internal Change",
		"External Pressure",
		"Environmental Collapse",
		"Lost"
	};
}
