package org.cote.accountmanager.olio.personality;

import java.util.HashMap;
import java.util.Map;

public enum CompatibilityEnumType{
	UNKNOWN(0),
	NOT_COMPATIBLE(1),
	NOT_IDEAL(2),
	PARTIAL(3),
	COMPATIBLE(4),
	IDEAL(5)
	;
    private int val;

    private static Map<Integer, CompatibilityEnumType> compMat = new HashMap<Integer, CompatibilityEnumType>();

    static {
        for (CompatibilityEnumType comp : CompatibilityEnumType.values()) {
            compMat.put(comp.val, comp);
        }
    }

    private CompatibilityEnumType(final int val) {
    	this.val = val;
    }
}