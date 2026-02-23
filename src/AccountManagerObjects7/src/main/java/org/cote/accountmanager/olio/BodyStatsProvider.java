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

	/// Compute BMI on charPerson model from statistics.
	///
	private void provideBmi(BaseRecord person) {
		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		if (stats == null || !stats.hasField(OlioFieldNames.FIELD_PHYSICAL_STRENGTH)) {
			return;
		}
		double bmi = computeBmi(stats);
		person.setValue(OlioFieldNames.FIELD_BMI, Double.parseDouble(df.format(bmi)));
	}

	/// Compute body type on charPerson model from statistics.
	/// Determines somatotype: ECTOMORPH, MESOMORPH, or ENDOMORPH.
	///
	private void provideBodyType(BaseRecord person) {
		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		if (stats == null || !stats.hasField(OlioFieldNames.FIELD_PHYSICAL_STRENGTH)) {
			return;
		}

		double powerStats = getAverage(stats, OlioFieldNames.FIELD_PHYSICAL_STRENGTH, OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, "maximumHealth");
		double finesseStats = getAverage(stats, OlioFieldNames.FIELD_AGILITY, OlioFieldNames.FIELD_SPEED);

		BodyTypeEnumType bodyType;
		if (finesseStats > powerStats + 3) {
			bodyType = BodyTypeEnumType.ECTOMORPH;
		}
		else if (powerStats > finesseStats + 3) {
			bodyType = BodyTypeEnumType.ENDOMORPH;
		}
		else {
			bodyType = BodyTypeEnumType.MESOMORPH;
		}

		person.setValue(OlioFieldNames.FIELD_BODY_TYPE, bodyType.toString());
	}

	/// Compute body shape on charPerson model from statistics and gender.
	/// Uses stat patterns and gender to determine shape.
	///
	private void provideBodyShape(BaseRecord person) {
		BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
		if (stats == null || !stats.hasField(OlioFieldNames.FIELD_PHYSICAL_STRENGTH)) {
			return;
		}

		String gender = person.get(FieldNames.FIELD_GENDER);
		boolean isMale = "male".equals(gender);

		int strength = stats.get(OlioFieldNames.FIELD_PHYSICAL_STRENGTH);
		int agility = stats.get(OlioFieldNames.FIELD_AGILITY);
		int speed = stats.get(OlioFieldNames.FIELD_SPEED);
		int endurance = stats.get(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE);
		int maxHealth = stats.get("maximumHealth");

		double powerStats = (strength + endurance + maxHealth) / 3.0;
		double finesseStats = (agility + speed) / 2.0;

		BodyShapeEnumType shape;

		if (isMale) {
			if (strength > 16 && agility > 12) {
				shape = BodyShapeEnumType.V_TAPER;
			}
			else if (maxHealth > 15 && speed < 8) {
				shape = BodyShapeEnumType.ROUND;
			}
			else if (powerStats > finesseStats + 5 && endurance > 14) {
				shape = BodyShapeEnumType.ROUND;
			}
			else if (finesseStats > powerStats + 4) {
				shape = BodyShapeEnumType.RECTANGLE;
			}
			else if (strength > 14 && agility > 10) {
				shape = BodyShapeEnumType.INVERTED_TRIANGLE;
			}
			else if (agility < 8 && speed < 8 && endurance > 12) {
				shape = BodyShapeEnumType.PEAR;
			}
			else {
				shape = BodyShapeEnumType.RECTANGLE;
			}
		}
		else {
			/// Female shape determination
			if (strength > 16 && agility > 12) {
				shape = BodyShapeEnumType.HOURGLASS;
			}
			else if (maxHealth > 15 && speed < 8) {
				shape = BodyShapeEnumType.ROUND;
			}
			else if (agility > 14 && strength > 10) {
				shape = BodyShapeEnumType.HOURGLASS;
			}
			else if (strength > 14 && agility < 10) {
				shape = BodyShapeEnumType.INVERTED_TRIANGLE;
			}
			else if (finesseStats > powerStats + 4) {
				shape = BodyShapeEnumType.RECTANGLE;
			}
			else if (maxHealth > 13 && agility < 10 && speed < 10) {
				shape = BodyShapeEnumType.PEAR;
			}
			else if (powerStats > finesseStats + 5 && endurance > 14) {
				shape = BodyShapeEnumType.ROUND;
			}
			else {
				shape = BodyShapeEnumType.RECTANGLE;
			}
		}

		person.setValue(OlioFieldNames.FIELD_BODY_SHAPE, shape.toString());
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
