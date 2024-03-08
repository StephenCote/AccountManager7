package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.schema.type.ComparatorEnumType;

public enum OutcomeEnumType {
		VERY_FAVORABLE(2),
		FAVORABLE(1),
		EQUILIBRIUM(0),
		UNFAVORABLE(-1),
		VERY_UNFAVORABLE(-2)
	;
	
    private int val;

    private static Map<Integer, OutcomeEnumType> outcomeMap = new HashMap<Integer, OutcomeEnumType>();

    static {
        for (OutcomeEnumType Outcome : OutcomeEnumType.values()) {
            outcomeMap.put(Outcome.val, Outcome);
        }
    }

    private OutcomeEnumType(final int val) {
    	this.val = val;
    }
	public static OutcomeEnumType margin(OutcomeEnumType h1, OutcomeEnumType h2) {
		return valueOf(Math.abs(h1.val - h2.val));
	}
	public static boolean marginCompare(OutcomeEnumType h1, OutcomeEnumType h2, OutcomeEnumType h3, ComparatorEnumType comp) {
		return compare(margin(h1, h2), h3, comp);
	}

    public static int valueOf(OutcomeEnumType Outcome) {
    	return Outcome.val;
    }

    public static OutcomeEnumType valueOf(int oval) {
    	if(oval < VERY_UNFAVORABLE.val) oval = VERY_UNFAVORABLE.val;
    	else if(oval > VERY_FAVORABLE.val) oval = VERY_FAVORABLE.val;
        return outcomeMap.get(oval);
    }
    public static boolean compare(OutcomeEnumType lvl1, OutcomeEnumType lvl2, ComparatorEnumType comp) {
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
    public static ComparatorEnumType compare(OutcomeEnumType lvl1, OutcomeEnumType lvl2) {
    	ComparatorEnumType comp = ComparatorEnumType.UNKNOWN;
    	int val1 = lvl1.val;
    	int val2 = lvl2.val;
    	if(val1 < val2) comp = ComparatorEnumType.LESS_THAN;
    	else if (val1 == val2) comp = ComparatorEnumType.GREATER_THAN;
    	else comp = ComparatorEnumType.EQUALS;
    	return comp;
    }
	
}
