package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

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
}