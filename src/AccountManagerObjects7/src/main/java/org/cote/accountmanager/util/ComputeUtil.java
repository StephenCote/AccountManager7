package org.cote.accountmanager.util;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;

public class ComputeUtil {
	
	public static final Logger logger = LogManager.getLogger(ComputeUtil.class);
	
	public static void computeSum(BaseRecord model, FieldSchema field, String[] fields) throws ValueException, FieldException, ModelNotFoundException {
		model.set(field.getName(), getSum(model, fields));
	}

	public static int getSum(BaseRecord model, String[] fields) {
		int val = 0;
		for(String f : fields) {
			val += (int)model.get(f);
		}
		return val;
	}
	
	public static void computeAverage(BaseRecord model, FieldSchema field, String[] fields) throws ValueException, FieldException, ModelNotFoundException {
		// IOSystem.getActiveContext().getReader().populate(model, fields);
		model.set(field.getName(), getAverage(model, fields));
	}
	public static int getMaximumInt(BaseRecord model, String[] fields) {
		int val = 0;
		Optional<Integer> opt = Arrays.stream(fields).map(s -> (int)model.get(s)).max(Comparator.naturalOrder());
		if(opt.isPresent()) {
			val = opt.get();
		}
		return val;
	}
	public static int getMinimumInt(BaseRecord model, String[] fields) {
		int val = 0;
		Optional<Integer> opt = Arrays.stream(fields).map(s -> (int)model.get(s)).min(Comparator.naturalOrder());
		if(opt.isPresent()) {
			val = opt.get();
		}
		return val;
	}
	public static int getAverage(BaseRecord model, String[] fields) {
		int val = 0;
		int avg = 0;
		// IOSystem.getActiveContext().getReader().populate(model, fields);
		for(String f : fields) {
			if(model.hasField(f)) {
				val += (int)model.get(f);
			}
			else {
				logger.error("(getAverage) Field is missing: " + model.getSchema() + "." + f);
				//ErrorUtil.printStackTrace();
			}
		}
		if(val > 0) {
			// logger.info("Compute average: " + val + " / " + fields.length);
			avg = val / fields.length;
		}
		return avg;
	}
	
	public static void computeSpreadAverage(BaseRecord model, FieldSchema field, String[] fields, double center) throws ValueException, FieldException, ModelNotFoundException {
		model.set(field.getName(), getSpreadAverage(model, field, fields, center));
	}

	/// Spread-preserving average, for composite values declared on the same scale as their inputs.
	///
	/// A plain arithmetic mean of k inputs carries only 1/sqrt(k) of its inputs' spread, so a composite
	/// assembled from nested means collapses toward its centre and can never reach the ends of its own
	/// declared range. Measured on olio.statistics.beauty (a mean of five terms, three of them means
	/// themselves): across 20k rolled characters it never left 3-14 of its declared 0-20 range, and four
	/// of the seven narrative labels keyed to it were unreachable at any stat allocation.
	///
	/// Restoring the deviation by sqrt(k) gives the composite the same spread as one of its inputs,
	/// which is what declaring it on the inputs' scale is supposed to mean.
	///
	/// 'center' is the value the transform holds fixed, and it matters as much as the gain: the gain
	/// amplifies the distance from it, so a centre that does not match where the inputs actually sit
	/// is amplified into a systematic bias. Callers that know the inputs' baseline should pass it
	/// (see StatCompositeProvider, which passes the character's own mean base statistic). Pass
	/// Double.NaN to fall back to the midpoint of the field's declared minValue/maxValue.
	///
	/// Note this rounds where getAverage truncates. Truncation biases every level of a nested chain
	/// downward, which is a second, independent reason a nested composite reads below its own centre.
	public static int getSpreadAverage(BaseRecord model, FieldSchema field, String[] fields, double center) {
		int sum = 0;
		int count = 0;
		for(String f : fields) {
			if(model.hasField(f)) {
				sum += (int)model.get(f);
				count++;
			}
			else {
				logger.error("(getSpreadAverage) Field is missing: " + model.getSchema() + "." + f);
			}
		}
		if(count == 0) {
			return 0;
		}
		double raw = (double)sum / count;
		double min = field.getMinValue();
		double max = field.getMaxValue();
		boolean ranged = (max > min);
		double mid = center;
		if(Double.isNaN(mid)) {
			if(!ranged) {
				/// No caller-supplied centre and no declared range to derive one from, so there is
				/// nothing to restore spread around: this degrades to a plain rounded mean. Warn,
				/// because a field declared SAVG has then silently become AVG.
				logger.warn("SAVG field " + model.getSchema() + "." + field.getName()
					+ " has no centre and no minValue/maxValue range; falling back to a plain mean");
				return (int)Math.round(raw);
			}
			mid = (min + max) / 2.0;
		}
		long val = Math.round(mid + ((raw - mid) * Math.sqrt(count)));
		if(ranged) {
			if(val < (long)min) val = (long)min;
			if(val > (long)max) val = (long)max;
		}
		return (int)val;
	}

	public static double getDblAverage(BaseRecord model, String[] fields) {
		double val = 0;
		double avg = 0;
		// IOSystem.getActiveContext().getReader().populate(model, fields);
		for(String f : fields) {
			if(model.hasField(f)) {
				val += (double)model.get(f);
			}
			else {
				logger.error("(getDblAverage) Field is missing: " + model.getSchema() + "." + f);
			}
		}
		if(val > 0) {
			// logger.info("Compute average: " + val + " / " + fields.length);
			avg = val / fields.length;
		}
		return avg;
	}
	
	public static void addDouble(BaseRecord model, String fieldName, double val) {
		double cval = model.get(fieldName);
		double mval = val + cval;
		FieldSchema fs = RecordFactory.getSchema(model.getSchema()).getFieldSchema(fieldName);
		if(fs.isValidateRange()) {
			if(mval > fs.getMaxValue()) mval = fs.getMaxValue();
			if(mval < fs.getMinValue()) mval = fs.getMinValue();
		}
		try {
			model.set(fieldName, mval);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		
	}
}
