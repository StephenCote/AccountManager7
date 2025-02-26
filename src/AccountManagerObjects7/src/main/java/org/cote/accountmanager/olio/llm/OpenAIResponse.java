package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.TypeUtil;

public class OpenAIResponse extends LooseRecord {

	public OpenAIResponse(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public OpenAIResponse() {
		
		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_RESPONSE, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

	}
	
	public OpenAIMessage getMessage() {
		List<OpenAIChoice> choices = getChoices();
		if (choices.size() > 0) {
			return choices.get(0).getMessage();
		}
		return null;
	}
	
	public String getId(){
		return get("id");
	}
	
	public void setId(String id){
		setValue("id", id);
	}
	
	public String getSystemFingerprint(){
		return get("system_fingerprint");
	}
	
	public void setSystemFingerprint(String id){
		setValue("system_fingerprint", id);
	}
	public long getCreated(){
		return get("created");
	}
	
	public void setCreated(String id){
		setValue("created", id);
	}

	public String getObject(){
		return get("object");
    }
	
	public void setObject(String id) {
		setValue("object", id);
	}

	public BaseRecord getUsage(){
        return get("usage");
    }
	
	public void setUsage(BaseRecord id) {
		setValue("usage", id);
	}

	public BaseRecord getPromptFilterResults() {
		return get("prompt_filter_results");
	}

	public void setPromptFilterResults(BaseRecord id) {
		setValue("prompt_filter_results", id);
	}
	
	public List<OpenAIChoice> getChoices() {
		List<BaseRecord> chs = get("choices");
		return chs.stream().map(msg -> new OpenAIChoice(msg)).collect(Collectors.toList());
	}

}
