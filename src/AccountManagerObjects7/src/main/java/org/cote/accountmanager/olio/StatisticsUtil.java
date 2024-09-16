package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;

public class StatisticsUtil {
	public static final Logger logger = LogManager.getLogger(StatisticsUtil.class);
	private static SecureRandom rand = new SecureRandom();
	private static final StatisticRule[] statistics = new StatisticRule[]{
		new StatisticRule(OlioFieldNames.FIELD_PHYSICAL_STRENGTH), new StatisticRule(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE), new StatisticRule(OlioFieldNames.FIELD_MANUAL_DEXTERITY), new StatisticRule(OlioFieldNames.FIELD_AGILITY), new StatisticRule(OlioFieldNames.FIELD_SPEED), new StatisticRule(OlioFieldNames.FIELD_MENTAL_STRENGTH),
		new StatisticRule(OlioFieldNames.FIELD_MENTAL_ENDURANCE), new StatisticRule(OlioFieldNames.FIELD_INTELLIGENCE), new StatisticRule(OlioFieldNames.FIELD_WISDOM), new StatisticRule(OlioFieldNames.FIELD_CHARISMA), new StatisticRule(OlioFieldNames.FIELD_CREATIVITY), new StatisticRule(OlioFieldNames.FIELD_SPIRITUALITY), new StatisticRule(OlioFieldNames.FIELD_LUCK)
		, new StatisticRule(OlioFieldNames.FIELD_PERCEPTION)
	};
	
	public static void rollStatistics(BaseRecord rec) {
		rollStatistics(rec, 0);
	}
	public static void rollStatistics(BaseRecord rec, int age) {
		if(!rec.inherits(OlioModelNames.MODEL_CHAR_STATISTICS)) {
			logger.error("Record is not a statistics record");
			return;
		}
		// IOSystem.getActiveContext().getReader().populate(rec);
		
		/// Every statistic receives a minimum of 1
		///  - (statistics.length * Rules.INITIAL_MINIMUM_STATISTIC)
		int allotment = Rules.INITIAL_STATISTICS_ALLOTMENT;
		int maxStat = Rules.MAXIMUM_STATISTIC;
		if(age > 0 && age < 13) {
			allotment = Rules.INITIAL_STATISTICS_ALLOTMENT_CHILD;
			maxStat = 10;
		}
		

		int total = 0;
		try {
			List<StatisticRule> slist = Arrays.asList(statistics);
			Collections.shuffle(slist);
			for(StatisticRule stat : slist) {
				int max = Math.max(Math.min(allotment, maxStat), 1);
				int rstat = Math.max(rand.nextInt(max), 1) + Rules.INITIAL_MINIMUM_STATISTIC;
				allotment -= rstat;
				total += rstat;
				rec.set(stat.getName(), (rstat * stat.getMultiplier()));
			}

			rec.set("potential", (allotment > 0 ? allotment : 0));
			
			/// Invoke inspect to perform any calculations on virtual fields
			///
			(new MemoryReader()).inspect(rec);
		}
		catch(ModelNotFoundException | FieldException | ValueException | ReaderException e) {
			logger.error(e);
		}
		
	}
	

}

class StatisticRule{
	private String name = null;
	private int multiplier = 0;
	public StatisticRule(String name){
		this(name, 1);
	}
	public StatisticRule(String name, int mult){
		this.name = name;
		this.multiplier = mult;
	}
	public String getName() {
		return name;
	}
	public int getMultiplier() {
		return multiplier;
	}
}
