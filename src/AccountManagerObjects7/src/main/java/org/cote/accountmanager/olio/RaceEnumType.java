package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

public enum RaceEnumType {
	A("American Indian/Alaska Native"),
	B("Asian"),
	C("Black or African American"),
	D("Native Hawaiian or other Pacific Islander"),
	E("White"),
	R("Robot"),
	U("Unknown"),
	V("Vampire"),
	W("Exraterrestrial"),
	X("Elf"),
	Y("Dwarf"),
	Z("Fairy");

	private String val = null;

    private static Map<String, RaceEnumType> raceMap = new HashMap<String, RaceEnumType>();

    static {
        for (RaceEnumType race : RaceEnumType.values()) {
            raceMap.put(race.val, race);
        }
    }

    private RaceEnumType(final String val) {
    	this.val = val;
    }

    public static String valueOf(RaceEnumType ret) {
        return ret.val;
    }
    public static RaceEnumType valueOfVal(String val) {
        return raceMap.get(val);
    }


}