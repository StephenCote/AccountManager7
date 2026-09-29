package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

public enum RaceEnumType {
	A("American Indian/Alaska Native"),
	B("Asian"),
	/// Removed 'African American' since the word 'African' causes LLMs and Diffusion models to misinterpret the context
	/// this can be designated via ethnicity
	///
	C("Black"),
	D("Native Hawaiian or other Pacific Islander"),
	E("White"),
	L("Lunatic"),
	R("Robot"),
	M("Monster"),
	S("Succubus"),
	U("Unknown"),
	V("Vampire"),
	W("Exraterrestrial"),
	X("Elf"),
	Y("Dwarf"),
	Z("Fairy"),
	/// Custom: a race the enum does not name. The human-readable name lives in the record's
	/// raceLabel field (identity.person), which substitutes for this element wherever the race
	/// list is rendered. "Custom" itself is never a label to show and is never offered to the LLM
	/// (raceOptionsCsv skips it — the reduce prompt must stay byte-identical for the fixtures).
	O("Custom");

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

    /// True when the constant NAME (the shape charPerson.race stores) is the Custom sink, O.
    /// Case-insensitive, null-safe. Compares the constant name only, not the "Custom" label.
    public static boolean isCustom(String name) {
        return name != null && O.name().equalsIgnoreCase(name.trim());
    }


}