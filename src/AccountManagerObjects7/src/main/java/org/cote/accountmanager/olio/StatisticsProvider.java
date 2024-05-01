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

	private String[] willpowerAttributes = new String[] {"mentalEndurance", "mentalStrength"};
	private String[] magicAttributes = new String[] {"willpower", "wisdom", "creativity", "spirituality"};
	private String[] scienceAttributes = new String[] {"intelligence", "wisdom", "creativity"};
	private String[] reactionAttributes = new String[] {"agility", "speed", "wisdom", "perception"};
	private String[] maxHealthAttributes = new String[] {"physicalStrength", "physicalEndurance", "mentalStrength", "mentalEndurance", "charisma"};
	private String[] saveAttributes = new String[] {"willpower", "health", "physicalStrength"};
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if(!RecordOperation.READ.equals(operation) && !RecordOperation.INSPECT.equals(operation)) {
			return;
		}
		if(lfield.getName().equals("willpower")) {
			IOSystem.getActiveContext().getReader().populate(model, willpowerAttributes);
			StatisticsUtil.computeAverage(model, lfield, willpowerAttributes);
		}
		else if(lfield.getName().equals("magic")) {
			IOSystem.getActiveContext().getReader().populate(model, magicAttributes);
			StatisticsUtil.computeAverage(model, lfield, magicAttributes);
		}
		else if(lfield.getName().equals("science")) {
			IOSystem.getActiveContext().getReader().populate(model, scienceAttributes);
			StatisticsUtil.computeAverage(model, lfield, scienceAttributes);
		}
		else if(lfield.getName().equals("reaction")) {
			IOSystem.getActiveContext().getReader().populate(model, reactionAttributes);
			StatisticsUtil.computeAverage(model, lfield, reactionAttributes);
		}
		else if(lfield.getName().equals("maximumHealth")) {
			IOSystem.getActiveContext().getReader().populate(model, maxHealthAttributes);
			StatisticsUtil.computeAverage(model, lfield, maxHealthAttributes);
			int maxHealth = model.get("maximumHealth");
			int health = model.get("health");
			if(health < 0) {
				model.set("health", maxHealth);
			}
		}
		else if(lfield.getName().equals("save")) {
			IOSystem.getActiveContext().getReader().populate(model, saveAttributes);
			int avg = StatisticsUtil.getAverage(model, saveAttributes);
			double val = (avg * 5)/100;
			DecimalFormat df = new DecimalFormat("#.#");
			df.setRoundingMode(RoundingMode.HALF_EVEN);
			model.set(lfield.getName(), Double.parseDouble(df.format(val)));
		}
	}



}
