package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
		return new OpenAIRequest(RecordFactory.importRecord(OlioModelNames.MODEL_OPENAI_REQUEST, json));
	}
	
	public void addMessage(OpenAIMessage msg) {
		List<OpenAIMessage> msgs = get("messages");
		msgs.add(msg);
	}
	
	public void addMessage(List<OpenAIMessage> amsg) {
		List<OpenAIMessage> msgs = get("messages");
		msgs.addAll(amsg);
	}
	
	public final List<OpenAIMessage> getMessages() {
		List<BaseRecord> msgs = get("messages");
		return msgs.stream().map(msg -> new OpenAIMessage(msg)).collect(Collectors.toList());
	}

	public void setMessages(List<OpenAIMessage> messages) {
		setValue("messages", messages);
	}

	/// TODO: Conflicts with 'model' in BaseRecord, which needs to be refactored after changing the key name
	
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