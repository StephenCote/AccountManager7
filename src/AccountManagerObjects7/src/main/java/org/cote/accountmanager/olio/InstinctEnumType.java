package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

public enum InstinctEnumType {
	DISREGARDED(-1.1),
	INCONCEIVABLE(-1),
	FATAL(-1),
	VAGUE(-0.9),
	WEAK(-0.8),
	IMBALANCED(-0.7),
	INADEQUATE(-0.6),
    INSIGNIFICANT(-0.5),
	DIMINISHED(-0.4),
	BELOW_AVERAGE(-0.3),
	MINIMAL(-0.2),
	LOW(-0.1),
	STASIS(0),
    MODEST(0.1),
    MARGINAL(0.2),
    AVERAGE(0.3),
    FAIR(0.4),
    ADEQUATE(0.5),
    BALANCED(0.6),
    ELEVATED(0.7),
    STRONG(0.8),
    PROFOUND(0.9),
    PEAK(.95),
    UNCONTROLLABLE(1)
    ;
	
    private double val;

    private static Map<Double, InstinctEnumType> instinctMap = new HashMap<Double, InstinctEnumType>();

    static {
        for (InstinctEnumType high : InstinctEnumType.values()) {
            instinctMap.put(high.val, high);
        }
    }

    private InstinctEnumType(final double val) {
    	this.val = val;
    }

    public static InstinctEnumType valueOf(double val) {
		DecimalFormat df = new DecimalFormat("#.#");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		double pval = Double.parseDouble(df.format(val));
		if(pval < 0) {
			return DISREGARDED;
		}
		else if(pval > 1) {
			return PEAK;
		}
        return instinctMap.get(pval);
    }
}