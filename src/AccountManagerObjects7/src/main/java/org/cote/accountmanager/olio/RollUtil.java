package org.cote.accountmanager.olio;

import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

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

	protected static boolean rollStat20(BaseRecord rec, String statName) {
		int sval = rec.get("statistics." + statName);
		if(sval == 0) {
			logger.warn("Statistic " + statName + " is 0");
			return false;
		}
		int roll = roll20Int();
		if(catastrophicFailure(roll)) {
			logger.warn("Catastrophic failure of " + statName + "!");
			return false;
		}
		if(naturalSuccess(roll)) {
			logger.warn("Natural success of " + statName + "!");
			return false;
		}
		return (sval >= roll);
	}

	public static boolean rollReaction(BaseRecord rec) {
		return rollStat20(rec, "reaction");
	}
	
	public static boolean rollPerception(BaseRecord rec) {
		return rollStat20(rec, "perception");
	}
	
	public static boolean rollPerception(BaseRecord rec, BaseRecord targ) {
		boolean per = rollPerception(rec);
		int x1 = rec.get("state.currentEast");
		int y1 = rec.get("state.currentNorth");
		int x2 = targ.get("state.currentEast");
		int y2 = targ.get("state.currentNorth");
		/// How far away (in meters)
		double d = GeoLocationUtil.distance(x1, y1, x2, y2);
		logger.info(rec.get("name") + " is " + d + " meters from " + targ.get("name"));
		return per;
	}
	
}
