package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;

public class OpenAIChoice extends LooseRecord {

	public OpenAIChoice(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public OpenAIChoice() {
		
		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_CHOICE, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

	}
	
	public OpenAIMessage getMessage(){
		return new OpenAIMessage(get("message"));
	}
}
