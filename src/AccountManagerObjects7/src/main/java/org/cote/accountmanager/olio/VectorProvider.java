package org.cote.accountmanager.olio;

import java.util.List;

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
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class VectorProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(VectorProvider.class);
	
	@Override
	public String describe(ModelSchema lmodel, BaseRecord model) {
	
		StringBuilder content = new StringBuilder();
		if(lmodel.getName().equals(OlioModelNames.MODEL_OPENAI_REQUEST)) {
			logger.info("Extracting conversation history.");
			IOSystem.getActiveContext().getReader().populate(model, new String[] {"messages"});
			List<BaseRecord> msgs = model.get("messages");  
			for(BaseRecord msg : msgs) {
				if (msg.get("role").equals("user")) {
					content.append("User: " + msg.get("content") + System.lineSeparator());
				}
				else if (msg.get("role").equals("assistant")) {
					content.append("Assistant: " + msg.get("content") + System.lineSeparator());
				}
			}
		}
		else if(lmodel.hasField(OlioFieldNames.FIELD_NARRATIVE)) {
			logger.info("Extracting narrative content.");
			IOSystem.getActiveContext().getReader().populate(model, new String[] {OlioFieldNames.FIELD_NARRATIVE, FieldNames.FIELD_GENDER});
			String pro = ("male".equals(model.get(FieldNames.FIELD_GENDER)) ? "He" : "She");
			BaseRecord rnarrative = model.get("narrative");
			if(rnarrative != null) {
				
				BaseRecord nar = OlioUtil.getFullRecord(rnarrative);
				content.append((String)nar.get("fullName") + " is a " + nar.get("physicalDescription") + ".");
				content.append(" " + pro + " is " + nar.get("statisticsDescription") + ".");
				content.append(" " + pro + " is wearing " + nar.get("outfitDescription") + ".");
				
				content.append(System.lineSeparator() + nar.get("name") + " " + nar.get("alignmentDescription") + ".");
				content.append(" " + nar.get("darkTetradDescription") + ".");
				content.append(" " + pro + " is " + nar.get("sloanDescription") + ".");
				content.append(" " + pro + " is " + nar.get("mbtiDescription") + ".");
			}
		}
		// TODO Auto-generated method stub
		return (content.length() > 0 ? content.toString() : null);
	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		// TODO Auto-generated method stub

	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model,
			FieldSchema lfield, FieldType field)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		// TODO Auto-generated method stub

	}

}
