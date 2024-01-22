package org.cote.accountmanager.olio;

import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class RollUtil {
	public static final Logger logger = LogManager.getLogger(RollUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	protected static double minMax(double val) {
		return Math.max(Math.min(val, Rules.ROLL_MAXIMUM_MODIFICATION), Rules.ROLL_MINIMUM_MODIFICATION);
	}
	public static double modifyForMobility(BaseRecord rec, double val) {
		boolean immobile = rec.get("state.immobilized");
		boolean awake = rec.get("state.awake");
		double reaction = rec.get("statistics.reaction");
		if(!awake) {
			val += (20 - reaction);
		}
		if(immobile) {
			val = 15;
		}
		return minMax(val);
	}
	public static double roll20Dbl() {
		return rand.nextDouble(0, Rules.ROLL_MAXIMUM);
	}
	public static int roll20Int() {
		return rand.nextInt(0, (int)Rules.ROLL_MAXIMUM);
	}
	public static boolean catastrophicFailure(int val) {
		return (val == 0);
	}
	public static boolean naturalSuccess(int val) {
		return (val == (int)Rules.ROLL_MAXIMUM);
	}
	
	public static boolean catastrophicFailure(double val) {
		return (val < Rules.ROLL_FAILURE_THRESHOLD);
	}
	public static boolean naturalSuccess(double val) {
		return (val >= (Rules.ROLL_MAXIMUM - Rules.ROLL_NATURAL_VARIANCE));
	}

	protected static RollEnumType rollStat20(BaseRecord rec, String statName) {
		int sval = rec.get("statistics." + statName);
		if(sval == 0) {
			logger.warn("Statistic " + statName + " is 0");
			return RollEnumType.INVALID_STATISTIC;
		}
		int roll = roll20Int();
		if(catastrophicFailure(roll)) {
			// logger.warn("Catastrophic failure of " + statName + "!");
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		if(naturalSuccess(roll)) {
			// logger.warn("Natural success of " + statName + "!");
			return RollEnumType.NATURAL_SUCCESS;
		}
		if(sval >= roll) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}

	public static RollEnumType rollReaction(BaseRecord rec) {
		return rollStat20(rec, "reaction");
	}
	
	public static RollEnumType rollPerception(BaseRecord rec) {
		return rollStat20(rec, "perception");
	}
	public static boolean isBetterStat(BaseRecord rec, BaseRecord rec2, String statName) {
		int sval = rec.get("statistics." + statName);
		int sval2 = rec2.get("statistics." + statName);
		if(sval == 0 || sval2 == 2) {
			logger.warn("Statistic " + statName + " is 0");
			return false;
		}
		return (sval >= sval2);

	}
	public static RollEnumType rollPerception(BaseRecord rec, BaseRecord targ) {
		// RollEnumType ret = rollPerception(rec);
		RollEnumType per = rollPerception(targ);
		double d = StateUtil.getDistance(rec.get("state"), targ.get("state"));
		logger.info(rec.get("name") + " is " + d + " meters from " + targ.get("name"));
		return per;
	}
	public static ComparatorEnumType compare(double val1, double val2, double val1mod) {
		return compare(val1 * val1mod, val2);
	}
    public static ComparatorEnumType compare(double val1, double val2) {
    	ComparatorEnumType comp = ComparatorEnumType.UNKNOWN;
    	if(val1 < val2) comp = ComparatorEnumType.LESS_THAN;
    	else if (val1 > val2) comp = ComparatorEnumType.GREATER_THAN;
    	else comp = ComparatorEnumType.EQUALS;
    	return comp;
    }

	
}
