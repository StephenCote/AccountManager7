package org.cote.accountmanager.olio;

import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.util.ComputeUtil;

public class RollUtil {
	public static final Logger logger = LogManager.getLogger(RollUtil.class);
	private static SecureRandom rand = new SecureRandom();
	
	protected static double minMax(double val) {
		return Math.max(Math.min(val, Rules.ROLL_MAXIMUM_MODIFICATION), Rules.ROLL_MINIMUM_MODIFICATION);
	}
	public static double modifyForMobility(BaseRecord rec, double val) {
		boolean immobile = rec.get("state.immobilized");
		boolean incap = rec.get("state.incapacitated");
		boolean awake = rec.get("state.awake");
		double reaction = rec.get("statistics.reaction");
		if(!awake) {
			val += (20 - reaction);
		}
		if(immobile || incap) {
			val = 18;
		}
		return minMax(val);
	}
	public static double roll20Dbl() {
		return rand.nextDouble(0, Rules.ROLL_MAXIMUM);
	}
	public static double roll1Dbl() {
		return rand.nextDouble();
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
		return (val < Rules.ROLL_FAILURE_THRESHOLD_1);
	}
	public static boolean naturalSuccess(double val) {
		return (val >= (Rules.ROLL_MAXIMUM_1 - Rules.ROLL_NATURAL_VARIANCE_1));
	}

	public static RollEnumType rollStat20(BaseRecord rec, String statName) {
		int sval = rec.get("statistics." + statName);
		if(sval == 0) {
			logger.warn("Statistic " + statName + " is 0");
			return RollEnumType.INVALID_STATISTIC;
		}
		return rollStat20(sval);
	}
	
	public static RollEnumType rollStat20(int stat) {
		if(stat == 0) {
			logger.warn("Statistic is 0");
			return RollEnumType.INVALID_STATISTIC;
		}
		int roll = roll20Int();
		if(catastrophicFailure(roll)) {
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		if(naturalSuccess(roll)) {
			return RollEnumType.NATURAL_SUCCESS;
		}
		if(stat >= roll) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}

	public static RollEnumType rollStat1(BaseRecord rec, String statName) {
		double sval = rec.get("statistics." + statName);
		if(sval == 0) {
			logger.warn("Statistic " + statName + " is 0");
			return RollEnumType.INVALID_STATISTIC;
		}
		return rollStat1(sval);
	}
	
	public static RollEnumType rollStat1(double stat) {
		if(stat == 0) {
			logger.warn("Statistic is 0");
			return RollEnumType.INVALID_STATISTIC;
		}
		double roll = roll1Dbl();
		if(catastrophicFailure(roll)) {
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		if(naturalSuccess(roll)) {
			return RollEnumType.NATURAL_SUCCESS;
		}
		if(stat >= roll) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}
	
	public static OutcomeEnumType rollToOutcome(RollEnumType ret) {
		OutcomeEnumType oet = OutcomeEnumType.EQUILIBRIUM;
		switch(ret) {
			case CATASTROPHIC_FAILURE:
				oet = OutcomeEnumType.VERY_UNFAVORABLE;
				break;
			case FAILURE:
				oet = OutcomeEnumType.UNFAVORABLE;
				break;
			case SUCCESS:
				oet = OutcomeEnumType.FAVORABLE;
				break;
			case NATURAL_SUCCESS:
				oet = OutcomeEnumType.VERY_FAVORABLE;
				break;
			default:
				logger.warn("Unhandled roll: " + ret.toString());
				break;
		}
		return oet;
	}
	
	
	public static RollEnumType rollReaction(BaseRecord rec) {
		return rollStat20(rec, "reaction");
	}
	
	public static RollEnumType rollPerception(BaseRecord rec) {
		return rollStat20(rec, "perception");
	}
	
	public static RollEnumType rollCharisma(BaseRecord rec) {
		return rollStat20(rec, "charisma");
	}
	public static RollEnumType rollCounterCharisma(BaseRecord rec) {
		return rollStat20(ComputeUtil.getAverage(rec.get(OlioFieldNames.FIELD_STATISTICS), new String[] {"charisma", "intelligence"}));
	}

	/*
	public static RollEnumType rollDeception(BaseRecord actor, BaseRecord interactor) {
		boolean dec = false;
		/// Deception Mod Stat = actor(max(charisma, intelligence) + min(creativity, perception) / 2) - interactor(avg(perception, wisdom, mentalEndurance) / 2)
		int decStat = DarkTriadUtil.getDeceptionStatistic(actor);
		int decMod = decStat - DarkTriadUtil.getDeceptionCounterStatistic(interactor);
		if(decMod <= 0) {
			logger.warn(actor.get(FieldNames.FIELD_FIRST_NAME) + " has no chance to deceive " + interactor.get(FieldNames.FIELD_FIRST_NAME));
			return RollEnumType.FAILURE;
		}
		return rollStat20(decMod);
	}
	*/
	public static boolean isBetterStat(BaseRecord rec, BaseRecord rec2, String statName) {
		int sval = rec.get("statistics." + statName);
		int sval2 = rec2.get("statistics." + statName);
		if(sval == 0 || sval2 == 2) {
			logger.warn("Statistic " + statName + " is 0");
			return false;
		}
		return (sval >= sval2);

	}
	
	public static RollEnumType rollContact(BaseRecord rec, BaseRecord targ) {

		double rel = GeoLocationUtil.getDistanceToState(rec, targ);
		int iavg = ((int)rec.get("statistics.agility") + (int)rec.get("statistics.speed"))/2;

		double relPerc =  ((double)iavg * rel);
		int irelp = (int)relPerc;
		double d = GeoLocationUtil.getDistanceToState(rec.get(FieldNames.FIELD_STATE), targ.get(FieldNames.FIELD_STATE));
		logger.info("Contact " + d + " relativity " + rel + " / Relative reach: " + relPerc + " of " + iavg);
		return rollStat20(irelp);
	}
	
	public static RollEnumType rollPerception(BaseRecord rec, BaseRecord targ) {

		double rel = GeoLocationUtil.distanceRelativityToState(rec, targ);
		int iperc = rec.get("statistics.perception");
		double relPerc =  ((double)iperc * rel);
		int irelp = (int)relPerc;
		double d = GeoLocationUtil.getDistanceToState(rec.get(FieldNames.FIELD_STATE), targ.get(FieldNames.FIELD_STATE));
		logger.info("Distance " + d + " relativity " + rel + " / Relative perception: " + relPerc + " of " + rec.get("statistics.perception"));
		return rollStat20(irelp);
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
