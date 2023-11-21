package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public enum AlignmentEnumType {
	CHAOTICEVIL(-4),
	NEUTRALEVIL(-3),
	LAWFULEVIL(-2),
	CHAOTICNEUTRAL(-1),
	NEUTRAL(0),
	UNKNOWN(0),
	LAWFULNEUTRAL(1),
	CHAOTICGOOD(2),
	NEUTRALGOOD(3),
	LAWFULGOOD(4),
    ;

    private int val;

    private static Map<Integer, AlignmentEnumType> alignMap = new HashMap<Integer, AlignmentEnumType>();

    static {
        for (AlignmentEnumType align : AlignmentEnumType.values()) {
            alignMap.put(align.val, align);
        }
    }

    private AlignmentEnumType(final int val) {
    	this.val = val;
    }
    public static int getValue(AlignmentEnumType aet) {
    	return aet.val;
    }
    public static int getAlignmentScore(BaseRecord rec) {
    	int out = 0;
    	if(rec.inherits(ModelNames.MODEL_ALIGNMENT)) {
    		AlignmentEnumType aet = AlignmentEnumType.valueOf((String)rec.get(FieldNames.FIELD_ALIGNMENT));
    		out = aet.val;
    	}
    	return out;
    }

    public static AlignmentEnumType valueOf(int val) {
		if(val < -4) {
			return CHAOTICEVIL;
		}
		else if(val > 4) {
			return LAWFULGOOD;
		}
        return alignMap.get(val);
    }
}