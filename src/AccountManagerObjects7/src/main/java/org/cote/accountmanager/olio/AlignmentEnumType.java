package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

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
    
    public static ComparatorEnumType compare(AlignmentEnumType lvl1, AlignmentEnumType lvl2) {
    	ComparatorEnumType comp = ComparatorEnumType.UNKNOWN;
    	double val1 = lvl1.val;
    	double val2 = lvl2.val;
    	if(val1 < val2) comp = ComparatorEnumType.LESS_THAN;
    	else if (val1 == val2) comp = ComparatorEnumType.GREATER_THAN;
    	else comp = ComparatorEnumType.EQUALS;
    	return comp;
    }
    
	public static AlignmentEnumType margin(AlignmentEnumType v1, AlignmentEnumType v2) {
		return valueOf(Math.abs(v1.val - v2.val));
	}
	public static boolean marginCompare(AlignmentEnumType v1, AlignmentEnumType v2, AlignmentEnumType v3, ComparatorEnumType comp) {
		return compare(margin(v1, v2), v3, comp);
	}
    public static boolean compare(AlignmentEnumType lvl1, AlignmentEnumType lvl2, ComparatorEnumType comp) {
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