package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.personality.DarkTriadUtil;
import org.cote.accountmanager.personality.Sloan;
import org.cote.accountmanager.personality.SloanUtil;
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class PersonalityProvider  implements IProvider {
	public static final Logger logger = LogManager.getLogger(PersonalityProvider.class);
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}

	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		/// If these properties were virtual, then they'd be seat on READ/INSPECT operations
		/// 
		if(!RecordOperation.UPDATE.equals(operation) && !RecordOperation.CREATE.equals(operation)) {
			return;
		}
		if(lfield.getName().equals("sloanKey")) {
			IOSystem.getActiveContext().getReader().populate(model, ProfileUtil.PERSONALITY_FIELDS);
			model.set(lfield.getName(), SloanUtil.getSloanKey(model));
		}
		else if(lfield.getName().equals("sloanCardinal")) {
			IOSystem.getActiveContext().getReader().populate(model, ProfileUtil.PERSONALITY_FIELDS);
			model.set(lfield.getName(), SloanUtil.getSloanCardinal(model));
		}
		else if(lfield.getName().equals("mbtiKey")) {
			Sloan sloan = SloanUtil.getSloan(model.get("sloanKey"));
			if(sloan != null) {
				model.set(lfield.getName(), sloan.getMbtiKey());
			}
			else {
				logger.warn("Sloan key is null or invalid: " + model.get("sloanKey") + " / empty map = " + SloanUtil.getSloanDef().isEmpty());
			}
		}
		else if(lfield.getName().equals("darkTriadKey")) {
			IOSystem.getActiveContext().getReader().populate(model, ProfileUtil.DARK_PERSONALITY_FIELDS);
			model.set(lfield.getName(), DarkTriadUtil.getDarkTriadKey(model));
		}
	}


}
