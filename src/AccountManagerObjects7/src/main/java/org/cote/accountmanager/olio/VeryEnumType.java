package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.schema.type.ComparatorEnumType;

public enum VeryEnumType {
	DISREGARDED(-0.9),
    NEVER(0),
    HARDLY(0.1),
    UNLIKELY(0.1),
    SLIGHTLY(0.2),
    NOT_USUALLY(0.3),
    LESS_FREQUENTLY(0.4),
    SOMEWHAT(0.5),
    FREQUENTLY(0.6),
    USUALLY(0.7),
    MOSTLY(0.8),
    VERY(0.9),
    ALWAYS(1),
    GUARANTEED(1.1)
    ;

    private double val;

    private static Map<Double, VeryEnumType> veryMap = new HashMap<Double, VeryEnumType>();

    static {
        for (VeryEnumType very : VeryEnumType.values()) {
            veryMap.put(very.val, very);
        }
    }

    private VeryEnumType(final double val) {
    	this.val = val;
    }
    public static VeryEnumType opposite(VeryEnumType val) {
    	return valueOf(Math.abs(1.0 - val.val));
    }
    public static VeryEnumType valueOf(double val) {
		DecimalFormat df = new DecimalFormat("#.#");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		double pval = Double.parseDouble(df.format(val));
		if(pval < 0) {
			return DISREGARDED;
		}
		else if(pval > 1) {
			return GUARANTEED;
		}
        return veryMap.get(pval);
    }
    
    public static ComparatorEnumType compare(VeryEnumType lvl1, VeryEnumType lvl2) {
    	ComparatorEnumType comp = ComparatorEnumType.UNKNOWN;
    	double val1 = lvl1.val;
    	double val2 = lvl2.val;
    	if(val1 < val2) comp = ComparatorEnumType.LESS_THAN;
    	else if (val1 == val2) comp = ComparatorEnumType.GREATER_THAN;
    	else comp = ComparatorEnumType.EQUALS;
    	return comp;
    }
    
	public static VeryEnumType margin(VeryEnumType v1, VeryEnumType v2) {
		return valueOf(Math.abs(v1.val - v2.val));
	}
	public static boolean marginCompare(VeryEnumType v1, VeryEnumType v2, VeryEnumType v3, ComparatorEnumType comp) {
		return compare(margin(v1, v2), v3, comp);
	}
    public static boolean compare(VeryEnumType lvl1, VeryEnumType lvl2, ComparatorEnumType comp) {
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