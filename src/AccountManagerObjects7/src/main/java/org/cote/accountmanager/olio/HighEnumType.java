package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

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
}