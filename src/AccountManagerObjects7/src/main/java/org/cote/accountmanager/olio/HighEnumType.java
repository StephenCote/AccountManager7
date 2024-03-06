package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.schema.type.ComparatorEnumType;

public enum HighEnumType {
	DISREGARDED(-0.9),
    NEVER(0),
    LOW(0.5),
    MINIMAL(0.1),
    DIMINISHED(0.15),
    INSIGNIFICANT(0.2),
    WEAK(0.25),
    MODEST(0.3),
    MARGINAL(0.35),
    AVERAGE(0.4),
    MODERATE(0.45),
    INTERMEDIATE(0.5),
    FAIR(0.55),
    ADEQUATE(0.6),
    BALANCED(0.65),
    ELEVATED(0.7),
    SUBSTANTIAL(0.75),
    STRONG(0.8),
    PROFOUND(0.85),
    EXTENSIVE(0.9),
    PEAK(0.95),
    MAXIMUM(1),
    HERO(1.1)
    ;
	
    private double val;

    private static Map<Double, HighEnumType> highMap = new HashMap<Double, HighEnumType>();

    static {
        for (HighEnumType high : HighEnumType.values()) {
            highMap.put(high.val, high);
        }
    }

    private HighEnumType(final double val) {
    	this.val = val;
    }
	public static HighEnumType margin(HighEnumType h1, HighEnumType h2) {
		return valueOf(Math.abs(h1.val - h2.val));
	}
	public static boolean marginCompare(HighEnumType h1, HighEnumType h2, HighEnumType h3, ComparatorEnumType comp) {
		return compare(margin(h1, h2), h3, comp);
	}

    public static double valueOf(HighEnumType high) {
    	return high.val;
    }

    public static HighEnumType valueOf(double val) {
		DecimalFormat df = new DecimalFormat("#.#");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		double pval = Double.parseDouble(df.format(val));
		if(pval < 0) {
			return DISREGARDED;
		}
		else if(pval > 1) {
			return HERO;
		}
        return highMap.get(pval);
    }
    public static boolean compare(HighEnumType lvl1, HighEnumType lvl2, ComparatorEnumType comp) {
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
    public static ComparatorEnumType compare(HighEnumType lvl1, HighEnumType lvl2) {
    	ComparatorEnumType comp = ComparatorEnumType.UNKNOWN;
    	double val1 = lvl1.val;
    	double val2 = lvl2.val;
    	if(val1 < val2) comp = ComparatorEnumType.LESS_THAN;
    	else if (val1 == val2) comp = ComparatorEnumType.EQUALS;
    	else comp = ComparatorEnumType.GREATER_THAN;
    	return comp;
    }

}