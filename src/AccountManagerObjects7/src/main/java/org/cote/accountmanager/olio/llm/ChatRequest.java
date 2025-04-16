package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;

/// OllamaChatRequest used for proxied API connections
///
public class ChatRequest extends LooseRecord {

	public ChatRequest(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public ChatRequest() {
		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_REQUEST, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

	}
	
	public static ChatRequest importRecord(String json) {
		return new ChatRequest(RecordFactory.importRecord(OlioModelNames.MODEL_CHAT_REQUEST, json));
	}

	public BaseRecord getPromptConfig() {
		return get("promptConfig");
	}

	public void setPromptConfig(BaseRecord cfg) {
		setValue("promptConfig", cfg);
	}

	public BaseRecord getSession() {
		return get("session");
	}

	public void setSession(BaseRecord cfg) {
		setValue("session", cfg);
	}
	
	public String getSessionType() {
		return get("sessionType");
	}
	
	public void setSessionType(String type) {
		setValue("sessionType", type);
	}
	
	public BaseRecord getChatConfig() {
		return get("chatConfig");
	}

	public void setChatConfig(BaseRecord cfg) {
		setValue("chatConfig", cfg);
	}

	public String getUid() {
		return get("uid");
	}

	public void setUid(String uid) {
		setValue("uid", uid);
	}
	
	public String getModel() {
		return get("model");
	}

	public String getMessage() {
		return get("message");
	}

	public void setMessage(String message) {
		setValue("message", message);
	}
	
	
}
