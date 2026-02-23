package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

/// Provider for derived body statistics.
/// Handles fields on both olio.statistics (weight) and olio.charPerson (bmi, bodyType, bodyShape).
///
public class BodyStatsProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(BodyStatsProvider.class);

	private static final DecimalFormat df = new DecimalFormat("#.#");
	static {
		df.setRoundingMode(RoundingMode.HALF_EVEN);
	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do at model level
	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if (!RecordOperation.READ.equals(operation) && !RecordOperation.INSPECT.equals(operation)) {
			return;
		}

		String fieldName = lfield.getName();

		if (OlioFieldNames.FIELD_WEIGHT.equals(fieldName)) {
			provideWeight(model);
		}
		else if (OlioFieldNames.FIELD_BMI.equals(fieldName)) {
			provideBmi(model);
		}
		else if (OlioFieldNames.FIELD_BODY_TYPE.equals(fieldName)) {
			provideBodyType(model);
		}
		else if (OlioFieldNames.FIELD_BODY_SHAPE.equals(fieldName)) {
			provideBodyShape(model);
		}
	}

	/// Compute weight on statistics model from BMI and height.
	/// Weight (lbs) = (BMI * height_inches^2) / 703
	///
	private void provideWeight(BaseRecord stats) {
		if (!stats.hasField(OlioFieldNames.FIELD_HEIGHT) || !stats.hasField(OlioFieldNames.FIELD_PHYSICAL_STRENGTH)) {
			return;
		}

		double height = stats.get(OlioFieldNames.FIELD_HEIGHT);
		if (height <= 0) return;

		double heightInches = heightToInches(height);
		double bmi = computeBmi(stats);
		double weight = (bmi * heightInches * heightInches) / 703.0;

		stats.setValue(OlioFieldNames.FIELD_WEIGHT, Double.parseDouble(df.format(weight)));
	}

	/// Resolve the statistics sub-record on a charPerson.
	/// If not already populated, attempt to load via IOSystem reader.
	///
	private BaseRecord resolveStatistics(BaseRecord person) {
		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		if (stats == null || !stats.hasField(OlioFieldNames.FIELD_PHYSICAL_STRENGTH)) {
			try {
				IOSystem.getActiveContext().getReader().populate(person, new String[] { OlioFieldNames.FIELD_STATISTICS });
				stats = person.get(OlioFieldNames.FIELD_STATISTICS);
				if (stats != null) {
					IOSystem.getActiveContext().getReader().populate(stats);
				}
			} catch (Exception e) {
				logger.debug("Unable to resolve statistics: " + e.getMessage());
			}
		}
		if (stats == null || !stats.hasField(OlioFieldNames.FIELD_PHYSICAL_STRENGTH)) {
			return null;
		}
		return stats;
	}

	/// Compute BMI on charPerson model from statistics.
	///
	private void provideBmi(BaseRecord person) {
		BaseRecord stats = resolveStatistics(person);
		if (stats == null) {
			return;
		}
		double bmi = computeBmi(stats);
		person.setValue(OlioFieldNames.FIELD_BMI, Double.parseDouble(df.format(bmi)));
	}

	/// Compute body type on charPerson model using stat-driven archetype scoring.
	/// Mesomorph (Male): physicalStrength, athleticism, maximumHealth
	/// Mesomorph (Female): physicalStrength, agility, physicalAppearance
	/// Ectomorph: speed, agility, manualDexterity
	/// Endomorph: physicalEndurance, mentalEndurance, potential
	///
	private void provideBodyType(BaseRecord person) {
		BaseRecord stats = resolveStatistics(person);
		if (stats == null) {
			return;
		}

		String gender = person.get(FieldNames.FIELD_GENDER);
		boolean isMale = "male".equals(gender);

		double mesoScore;
		if (isMale) {
			mesoScore = getAverage(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, OlioFieldNames.FIELD_ATHLETICISM, "maximumHealth");
		} else {
			mesoScore = getAverage(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, OlioFieldNames.FIELD_AGILITY, "physicalAppearance");
		}

		double ectoScore = getAverage(stats, OlioFieldNames.FIELD_SPEED, OlioFieldNames.FIELD_AGILITY, OlioFieldNames.FIELD_MANUAL_DEXTERITY);
		double endoScore = getEndoScore(stats);

		BodyTypeEnumType bodyType;
		if (mesoScore >= ectoScore && mesoScore >= endoScore) {
			bodyType = BodyTypeEnumType.MESOMORPH;
		} else if (ectoScore >= endoScore) {
			bodyType = BodyTypeEnumType.ECTOMORPH;
		} else {
			bodyType = BodyTypeEnumType.ENDOMORPH;
		}

		person.setValue(OlioFieldNames.FIELD_BODY_TYPE, bodyType.toString());
	}

	/// Compute body shape on charPerson model using stat-driven archetype scoring.
	/// Male shapes: V_TAPER (str/ath/maxHp), RECTANGLE (spd/agi/dex), ROUND (end/mEnd/pot)
	/// Female shapes: HOURGLASS (str/agi/physApp), RECTANGLE, ROUND, INVERTED_TRIANGLE (str/ath), PEAR (maxHp/pot)
	///
	private void provideBodyShape(BaseRecord person) {
		BaseRecord stats = resolveStatistics(person);
		if (stats == null) {
			return;
		}

		String gender = person.get(FieldNames.FIELD_GENDER);
		boolean isMale = "male".equals(gender);

		double rectangleScore = getAverage(stats, OlioFieldNames.FIELD_SPEED, OlioFieldNames.FIELD_AGILITY, OlioFieldNames.FIELD_MANUAL_DEXTERITY);
		double roundScore = getEndoScore(stats);

		BodyShapeEnumType bestShape;
		double bestScore;

		if (isMale) {
			/// Male: V_TAPER, RECTANGLE, ROUND
			bestScore = getAverage(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, OlioFieldNames.FIELD_ATHLETICISM, "maximumHealth");
			bestShape = BodyShapeEnumType.V_TAPER;

			if (rectangleScore > bestScore) {
				bestShape = BodyShapeEnumType.RECTANGLE;
				bestScore = rectangleScore;
			}
			if (roundScore > bestScore) {
				bestShape = BodyShapeEnumType.ROUND;
			}
		} else {
			/// Female: HOURGLASS, RECTANGLE, ROUND, INVERTED_TRIANGLE, PEAR
			bestScore = getAverage(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, OlioFieldNames.FIELD_AGILITY, "physicalAppearance");
			bestShape = BodyShapeEnumType.HOURGLASS;

			if (rectangleScore > bestScore) {
				bestShape = BodyShapeEnumType.RECTANGLE;
				bestScore = rectangleScore;
			}
			if (roundScore > bestScore) {
				bestShape = BodyShapeEnumType.ROUND;
				bestScore = roundScore;
			}

			double invertedTriScore = getAverage(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, OlioFieldNames.FIELD_ATHLETICISM);
			if (invertedTriScore > bestScore) {
				bestShape = BodyShapeEnumType.INVERTED_TRIANGLE;
				bestScore = invertedTriScore;
			}

			double pearScore = getPearScore(stats);
			if (pearScore > bestScore) {
				bestShape = BodyShapeEnumType.PEAR;
			}
		}

		person.setValue(OlioFieldNames.FIELD_BODY_SHAPE, bestShape.toString());
	}

	/// Compute BMI from statistics fields using the stat-based formula.
	/// Base BMI 22.0, modified by strength, maxHealth, and agility.
	///
	public static double computeBmi(BaseRecord stats) {
		double baseBmi = 22.0;

		int strength = stats.get(OlioFieldNames.FIELD_PHYSICAL_STRENGTH);
		int agility = stats.get(OlioFieldNames.FIELD_AGILITY);
		int maxHealth = 10;
		if (stats.hasField("maximumHealth")) {
			maxHealth = stats.get("maximumHealth");
		}

		double strengthMod = (strength - 10) * 0.5;
		double healthMod = (maxHealth - 10) * 0.3;
		double agilityMod = (agility - 10) * 0.4;

		double bmi = baseBmi + strengthMod + healthMod - agilityMod;

		/// Clamp to reasonable range
		return Math.max(15.0, Math.min(45.0, bmi));
	}

	/// Convert height from compound feet.inches format to total inches.
	/// E.g., 5.10 -> 5 feet 10 inches -> 70 inches
	///
	public static double heightToInches(double height) {
		int feet = (int) height;
		int inches = (int) Math.round((height - feet) * 100);
		return feet * 12.0 + inches;
	}

	/// Get average of specified int fields from a record.
	///
	private double getAverage(BaseRecord rec, String... fields) {
		double sum = 0;
		int count = 0;
		for (String f : fields) {
			if (rec.hasField(f)) {
				sum += (int) rec.get(f);
				count++;
			}
		}
		return count > 0 ? sum / count : 0;
	}

	/// Compute endomorph score from physicalEndurance, mentalEndurance, and normalized potential.
	///
	private double getEndoScore(BaseRecord stats) {
		double endurance = stats.hasField(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE) ? (int) stats.get(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE) : 0;
		double mentalEnd = stats.hasField(OlioFieldNames.FIELD_MENTAL_ENDURANCE) ? (int) stats.get(OlioFieldNames.FIELD_MENTAL_ENDURANCE) : 0;
		double normPotential = getNormalizedPotential(stats);
		return (endurance + mentalEnd + normPotential) / 3.0;
	}

	/// Compute pear shape score from maximumHealth and normalized potential.
	///
	private double getPearScore(BaseRecord stats) {
		double maxHealth = stats.hasField("maximumHealth") ? (int) stats.get("maximumHealth") : 10;
		double normPotential = getNormalizedPotential(stats);
		return (maxHealth + normPotential) / 2.0;
	}

	/// Normalize potential (0-150) to 0-20 scale for stat comparisons.
	///
	private double getNormalizedPotential(BaseRecord stats) {
		if (!stats.hasField("potential")) return 10.0;
		int potential = stats.get("potential");
		return Math.min(20.0, potential / 7.5);
	}

	/// Get the beauty adjustment for a body shape and gender combination.
	/// Returns a modifier to be applied to the overall beauty score.
	///
	public static int getBeautyAdjustment(String bodyShape, String gender) {
		if (bodyShape == null || gender == null) return 0;
		boolean isMale = "male".equals(gender);
		BodyShapeEnumType shape;
		try {
			shape = BodyShapeEnumType.valueOf(bodyShape);
		} catch (IllegalArgumentException e) {
			return 0;
		}

		switch (shape) {
			case HOURGLASS:
				return isMale ? 0 : 3;
			case V_TAPER:
				return isMale ? 3 : 0;
			case INVERTED_TRIANGLE:
				return isMale ? 3 : 0;
			case RECTANGLE:
				return 0;
			case PEAR:
				return isMale ? -3 : 1;
			case ROUND:
				return -4;
			default:
				return 0;
		}
	}

	/// Get BMI descriptor for narrative purposes.
	///
	public static String getBmiDescriptor(double bmi) {
		if (bmi < 17.5) return "emaciated";
		if (bmi < 21.0) return "lean";
		if (bmi < 25.0) return "fit";
		if (bmi < 30.0) return "solid";
		if (bmi < 35.0) return "heavy";
		return "massive";
	}

	/// Get body shape descriptor for narrative purposes.
	///
	public static String getBodyShapeDescriptor(String bodyShape, String gender) {
		if (bodyShape == null) return "average";
		boolean isMale = "male".equals(gender);
		BodyShapeEnumType shape;
		try {
			shape = BodyShapeEnumType.valueOf(bodyShape);
		} catch (IllegalArgumentException e) {
			return "average";
		}
		switch (shape) {
			case V_TAPER:
				return isMale ? "broad-shouldered with a narrow waist" : "athletic with wide shoulders";
			case HOURGLASS:
				return isMale ? "well-proportioned" : "curvaceous with a defined waist";
			case RECTANGLE:
				return "straight-framed";
			case ROUND:
				return "round and stout";
			case INVERTED_TRIANGLE:
				return isMale ? "powerfully built with broad shoulders" : "athletically top-heavy";
			case PEAR:
				return isMale ? "bottom-heavy" : "full-hipped";
			default:
				return "average";
		}
	}

	@Override
	public String describe(ModelSchema lmodel, BaseRecord model) {
		return null;
	}
}
