package org.cote.accountmanager.personality;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.schema.type.ComparatorEnumType;

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
    
    public static ComparatorEnumType compare(CompatibilityEnumType lvl1, CompatibilityEnumType lvl2) {
    	ComparatorEnumType comp = ComparatorEnumType.UNKNOWN;
    	int val1 = lvl1.val;
    	int val2 = lvl2.val;
    	if(val1 < val2) comp = ComparatorEnumType.LESS_THAN;
    	else if (val1 == val2) comp = ComparatorEnumType.GREATER_THAN;
    	else comp = ComparatorEnumType.EQUALS;
    	return comp;
    }
    public static boolean compare(CompatibilityEnumType lvl1, CompatibilityEnumType lvl2, ComparatorEnumType comp) {
    	ComparatorEnumType hcomp = compare(lvl1, lvl2);
    	return (
    		hcomp == comp
    		||
    		(hcomp == ComparatorEnumType.GREATER_THAN && comp == ComparatorEnumType.GREATER_THAN_OR_EQUALS)
    		||
    		(hcomp == ComparatorEnumType.EQUALS && comp == ComparatorEnumType.GREATER_THAN_OR_EQUALS)
    		||
    		(hcomp == ComparatorEnumType.LESS_THAN && comp == ComparatorEnumType.LESS_THAN_OR_EQUALS)
    		||
    		(hcomp == ComparatorEnumType.EQUALS && comp == ComparatorEnumType.LESS_THAN_OR_EQUALS)
    	);

    }
}