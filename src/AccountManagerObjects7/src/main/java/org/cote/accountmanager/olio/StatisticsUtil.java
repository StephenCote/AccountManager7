package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
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
	
	/// Roll a random height for a character based on race and gender.
	/// Height is stored in compound feet.inches format (e.g. 5.10 = 5ft 10in).
	/// Uses a bell curve distribution around race/gender mean heights.
	///
	public static void rollHeight(BaseRecord stats, List<String> races, String gender, int age) {
		if (!stats.inherits(OlioModelNames.MODEL_CHAR_STATISTICS)) {
			return;
		}
		try {
			boolean isMale = "male".equals(gender);

			/// Base mean height in inches by race and gender
			/// Statistical averages from anthropometric data
			double meanInches = isMale ? 69.0 : 64.0;
			double stdDev = isMale ? 3.0 : 2.8;

			if (races != null && !races.isEmpty()) {
				String primaryRace = races.get(0);
				switch (primaryRace) {
					case "A": /// American Indian/Alaska Native
						meanInches = isMale ? 67.0 : 62.0;
						break;
					case "B": /// Asian
						meanInches = isMale ? 67.5 : 62.5;
						break;
					case "C": /// Black
						meanInches = isMale ? 69.5 : 64.5;
						break;
					case "D": /// Native Hawaiian/Pacific Islander
						meanInches = isMale ? 68.0 : 63.0;
						break;
					case "E": /// White
						meanInches = isMale ? 70.0 : 64.5;
						break;
					case "X": /// Elf
						meanInches = isMale ? 72.0 : 67.0;
						stdDev = 2.5;
						break;
					case "Y": /// Dwarf
						meanInches = isMale ? 54.0 : 50.0;
						stdDev = 2.0;
						break;
					case "Z": /// Fairy
						meanInches = isMale ? 42.0 : 39.0;
						stdDev = 1.5;
						break;
					default:
						break;
				}
			}

			/// Age modifier: children are shorter
			if (age > 0 && age < 18) {
				double ageFactor = Math.min(age / 18.0, 1.0);
				meanInches = meanInches * (0.4 + 0.6 * ageFactor);
				stdDev = stdDev * ageFactor;
			}

			/// Gaussian distribution around the mean
			double heightInches = meanInches + rand.nextGaussian() * stdDev;
			heightInches = Math.max(36, Math.min(96, heightInches));

			/// Convert to compound feet.inches format
			int feet = (int)(heightInches / 12);
			int inches = (int) Math.round(heightInches % 12);
			if (inches >= 12) {
				feet++;
				inches = 0;
			}

			DecimalFormat df = new DecimalFormat("#.00");
			df.setRoundingMode(RoundingMode.HALF_EVEN);
			double compoundHeight = Double.parseDouble(df.format(feet + inches / 100.0));

			stats.set(OlioFieldNames.FIELD_HEIGHT, compoundHeight);
		}
		catch (ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}

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
