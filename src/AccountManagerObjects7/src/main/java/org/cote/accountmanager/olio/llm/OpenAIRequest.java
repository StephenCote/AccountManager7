package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.TypeUtil;

public class OpenAIRequest extends LooseRecord {
	
	public OpenAIRequest(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public OpenAIRequest() {
		
		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_REQUEST, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

	}
	
	public static OpenAIRequest importRecord(String json) {
		return new OpenAIRequest(JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()));
	}
	
	public List<OpenAIMessage> getMessages() {
		return TypeUtil.convertRecordList(get("messages"));
	}

	public void setMessages(List<OpenAIMessage> messages) {
		setValue("messages", messages);
	}

	public String getModel() {
		return get("model");
	}
	public void setModel(String model) {
		setValue("model", model);
	}
	public boolean isStream() {
		return get("stream");
	}
	public void setStream(boolean stream) {
		setValue("stream", stream);
	}
	
	
}