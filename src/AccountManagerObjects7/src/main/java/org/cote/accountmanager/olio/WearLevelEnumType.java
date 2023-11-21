package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

public enum WearLevelEnumType {

		/// Not used or not specific
	    NONE(0),
	    /// Inside the body.  E.G.: Pacemaker
	    INTERNAL(1),
	    /// Under the skin.  E.G.: capsule
	    UNDER(2),
	    /// On the skin. E.G.: Tattoo (yes, you can argue that should be under), makeup
	    ON(3),
	    /// Direct skin contact, 
	    BASE(4),
	    /// Secondary layer
	    ACCENT(5),
	    /// Primary layer
	    SUIT(6),
	    /// Garnish to the primary layer.  E.G.: Bows, ties, lace, buttons, ribbons
	    GARNITURE(7),
	    /// E.G.: Jewelry, gloves, hats, handkerchiefs, canes, fans
	    ACCESSORY(8),
	    /// Overwear, E.G.: Jackets, coats
	    OVER(9),
	    /// Outerwear, E.G.: Overcoats
	    OUTER(10),
	    /// Covers the entire body: Spacesuit, armor
	    FULL_BODY(11),
	    /// Covers and encloses the body.  E.G.: body bag
	    ENCLOSURE(12),
		///
		UNKNOWN(13)
    ;

    private int val;

    private static Map<Integer, WearLevelEnumType> levelMap = new HashMap<Integer, WearLevelEnumType>();

    static {
        for (WearLevelEnumType lvl : WearLevelEnumType.values()) {
            levelMap.put(lvl.val, lvl);
        }
    }

    private WearLevelEnumType(final int val) {
    	this.val = val;
    }

    public static WearLevelEnumType valueOf(int val) {
		if(val < 0 || val > 13) {
			return UNKNOWN;
		}
        return levelMap.get(val);
    }
    public static int valueOf(WearLevelEnumType lvl) {
    	return levelMap.entrySet().stream().filter(entry -> lvl == entry.getValue()).map(Map.Entry::getKey).findFirst().get();
    }
}