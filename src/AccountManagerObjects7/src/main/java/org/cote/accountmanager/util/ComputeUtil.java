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
				logger.error("(getAverage) Field is missing: " + model.getModel() + "." + f);
				//ErrorUtil.printStackTrace();
			}
		}
		if(val > 0) {
			// logger.info("Compute average: " + val + " / " + fields.length);
			avg = val / fields.length;
		}
		return avg;
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
				logger.error("(getDblAverage) Field is missing: " + model.getModel() + "." + f);
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
		FieldSchema fs = RecordFactory.getSchema(model.getModel()).getFieldSchema(fieldName);
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
