package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;

public class OpenAIMessage extends LooseRecord {
	public OpenAIMessage(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public OpenAIMessage() {
		
		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_MESSAGE, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

	}
	
	
	public boolean isPruned() {
		return get("pruned");
	}


	public void setPruned(boolean pruned) {
		setValue("pruned", pruned);
	}


	public String getRole() {
		return get("role");
	}
	public void setRole(String role) {
		setValue("role", role);
	}
	public String getContent() {
		return get("content");
	}
	public void setContent(String content) {
		setValue("content", content);
	}
	
}
