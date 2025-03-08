package org.cote.accountmanager.olio.llm;

import java.util.List;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;

/// OllamaChatResponse used for proxied API connections
///
public class ChatResponse extends LooseRecord {

	public ChatResponse(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public ChatResponse() {
		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_RESPONSE, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	public String getModel() {
		return get("model");
	}

	public void setModel(String model) {
		setValue("model", model);
	}

	public String getUid() {
		return get("uid");
	}
	public void setUid(String uid) {
		setValue("uid", uid);
	}
	public List<BaseRecord> getMessages() {
		return get("messages");
	}
	public void setMessages(List<BaseRecord> messages) {
		setValue("messages", messages);
	}
	
}
