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
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class StatisticsProvider  implements IProvider {
	public static final Logger logger = LogManager.getLogger(StatisticsProvider.class);
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}

	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if(!RecordOperation.READ.equals(operation) && !RecordOperation.INSPECT.equals(operation)) {
			return;
		}
		if(lfield.getName().equals("willpower")) {
			computeAverage(model, lfield, new String[] {"mentalEndurance", "mentalStrength"});
		}
		else if(lfield.getName().equals("magic")) {
			computeAverage(model, lfield, new String[] {"willpower", "wisdom", "creativity", "spirituality"});
		}
		else if(lfield.getName().equals("science")) {
			computeAverage(model, lfield, new String[] {"intelligence", "wisdom", "creativity"});
		}
		else if(lfield.getName().equals("reaction")) {
			computeAverage(model, lfield, new String[] {"agility", "speed", "wisdom", "perception"});
		}
		else if(lfield.getName().equals("maximumHealth")) {
			computeAverage(model, lfield, new String[] {"physicalStrength", "physicalEndurance", "mentalStrength", "mentalEndurance", "charisma"});
			int maxHealth = model.get("maximumHealth");
			int health = model.get("health");
			if(health < 0) {
				model.set("health", maxHealth);
			}
		}
		else if(lfield.getName().equals("save")) {
			int avg = getAverage(model, new String[] {"willpower", "health", "physicalStrength"});
			double val = (avg * 5)/100;
			DecimalFormat df = new DecimalFormat("#.#");
			df.setRoundingMode(RoundingMode.HALF_EVEN);
			// logger.info("Save: " + avg + " ~= " + val);
			model.set(lfield.getName(), Double.parseDouble(df.format(val)));
		}
	}

	private void computeSum(BaseRecord model, FieldSchema field, String[] fields) throws ValueException, FieldException, ModelNotFoundException {
		model.set(field.getName(), getSum(model, fields));
	}

	private int getSum(BaseRecord model, String[] fields) {
		int val = 0;
		for(String f : fields) {
			val += (int)model.get(f);
		}
		return val;
	}
	
	private void computeAverage(BaseRecord model, FieldSchema field, String[] fields) throws ValueException, FieldException, ModelNotFoundException {
		model.set(field.getName(), getAverage(model, fields));
	}
	
	private int getAverage(BaseRecord model, String[] fields) {
		int val = 0;
		int avg = 0;
		for(String f : fields) {
			if(model.hasField(f)) {
				val += (int)model.get(f);
			}
			else {
				logger.error("Field is missing: " + model.getModel() + "." + f);
			}
		}
		if(val > 0) {
			// logger.info("Compute average: " + val + " / " + fields.length);
			avg = val / fields.length;
		}
		return avg;
	}

}
