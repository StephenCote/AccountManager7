package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.JSONUtil;

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

	public String getPromptConfig() {
		return get("promptConfig");
	}

	public void setPromptConfig(String cfg) {
		setValue("promptConfig", cfg);
	}

	
	public String getChatConfig() {
		return get("chatConfig");
	}

	public void setChatConfig(String cfg) {
		setValue("chatConfig", cfg);
	}

	public String getUid() {
		return get("uid");
	}

	public void setUid(String uid) {
		setValue("uid", uid);
	}
	
	public String getSessionName() {
		return get("sessionName");
	}

	public void setSessionName(String name) {
		setValue("sessionName", name);
	}
	
	public String getMessage() {
		return get("message");
	}

	public void setMessage(String message) {
		setValue("message", message);
	}
	
	
}
