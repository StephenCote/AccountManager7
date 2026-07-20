package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	private static final Pattern HEIGHT_FEET_INCHES = Pattern.compile("(\\d+)\\s*(?:['’]|ft|feet)\\s*(\\d{1,2})");
	private static final Pattern HEIGHT_CM = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*cm");
	private static final Pattern HEIGHT_FEET_ONLY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:ft|feet)\\b");

	/**
	 * Parse a free-text height description (e.g. {@code 5'8"}, {@code 172cm}, {@code 5.5 ft})
	 * into the schema's compound feet.inches double format (5.10 = 5ft 10in — see
	 * statisticsModel.json). Deliberately conservative: returns {@code null} for anything
	 * prose-like ("tall", "average height") rather than guessing, per the same "be conservative"
	 * instruction {@link #rollHeight} already follows for its own random distribution.
	 */
	private static Double parseHeightToFeetInches(String text) {
		if (text == null || text.isBlank()) return null;
		String t = text.trim();
		Matcher m = HEIGHT_FEET_INCHES.matcher(t);
		if (m.find()) {
			try {
				int feet = Integer.parseInt(m.group(1));
				int inches = Integer.parseInt(m.group(2));
				if (feet >= 1 && feet <= 8 && inches >= 0 && inches < 12) {
					return feet + (inches / 100.0);
				}
			} catch (NumberFormatException ignored) { /* fall through to other patterns */ }
		}
		m = HEIGHT_CM.matcher(t);
		if (m.find()) {
			try {
				double cm = Double.parseDouble(m.group(1));
				double totalInches = cm / 2.54;
				int feet = (int) (totalInches / 12);
				int inches = (int) Math.round(totalInches % 12);
				if (inches >= 12) { feet++; inches = 0; }
				if (feet >= 1 && feet <= 8) return feet + (inches / 100.0);
			} catch (NumberFormatException ignored) { /* fall through */ }
		}
		m = HEIGHT_FEET_ONLY.matcher(t);
		if (m.find()) {
			try {
				double feet = Double.parseDouble(m.group(1));
				if (feet >= 1 && feet <= 8) return feet + 0.00;
			} catch (NumberFormatException ignored) { /* not parseable */ }
		}
		return null;
	}

	/**
	 * Nudge physicalStrength/agility/physicalEndurance (±3-4, clamped 0-20) off keyword matches in
	 * a free-text build description, on top of whatever baseline rollStatistics() already rolled.
	 * This is what actually varies BodyStatsProvider's computed weight per character (driven by
	 * physicalStrength/agility/maximumHealth, not height alone) — without this, every character
	 * estimated via this path would still get an identical computed weight.
	 */
	private static void applyBuildKeywords(BaseRecord stats, String build) {
		if (build == null || build.isBlank()) return;
		String b = build.toLowerCase();
		int dStrength = 0, dAgility = 0, dEndurance = 0;
		if (b.matches(".*(muscular|athletic|broad|burly|brawny|strong).*")) {
			dStrength += 3; dEndurance += 3;
		}
		if (b.matches(".*(slender|thin|lean|lithe|willowy|slight|petite).*")) {
			dAgility += 3; dStrength -= 3;
		}
		if (b.matches(".*(heavyset|stocky|portly|overweight|heavy|large).*")) {
			dEndurance += 4; dAgility -= 3;
		}
		if (b.matches(".*(frail|weak|delicate|fragile).*")) {
			dStrength -= 4; dEndurance -= 4;
		}
		if (dStrength != 0) nudgeStat(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, dStrength);
		if (dAgility != 0) nudgeStat(stats, OlioFieldNames.FIELD_AGILITY, dAgility);
		if (dEndurance != 0) nudgeStat(stats, OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, dEndurance);
	}

	private static void nudgeStat(BaseRecord stats, String fieldName, int delta) {
		try {
			Integer cur = stats.get(fieldName);
			int val = (cur != null ? cur : 0) + delta;
			val = Math.max(0, Math.min(20, val));
			stats.set(fieldName, val);
		} catch (Exception e) {
			logger.warn("Failed to nudge statistic " + fieldName + ": " + e.getMessage());
		}
	}

	/**
	 * Best-effort mapping of an extracted character's free-text {@code physical} description
	 * (from the pictureBook.extract-character LLM prompt: height/build/etc.) onto a persisted
	 * olio.statistics record. Always rolls a random baseline first (avoids the degenerate
	 * all-zero/all-default case), then overrides height/build-driven stats only where the
	 * extracted text actually parses to something concrete — never guesses on prose text.
	 */
	@SuppressWarnings("unchecked")
	public static void estimateFromExtractedPhysical(BaseRecord stats, Map<String, Object> physical, String gender, int age) {
		rollStatistics(stats, age);
		if (physical == null) return;
		Object heightObj = physical.get("height");
		Double parsedHeight = (heightObj instanceof String) ? parseHeightToFeetInches((String) heightObj) : null;
		if (parsedHeight != null) {
			try {
				stats.set(OlioFieldNames.FIELD_HEIGHT, parsedHeight);
			} catch (Exception e) {
				logger.warn("Failed to set parsed height: " + e.getMessage());
			}
		} else {
			rollHeight(stats, null, gender, age);
		}
		Object buildObj = physical.get("build");
		if (buildObj instanceof String) {
			applyBuildKeywords(stats, (String) buildObj);
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
