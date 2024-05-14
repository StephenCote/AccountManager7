package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// OllamaChatResponse used for proxied API connections
///
public class OllamaChatResponse {
	private String uid = null;
	private String model = null;
	private List<OllamaMessage> messages = new ArrayList<>();
	public OllamaChatResponse() {
		
	}
	
	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getUid() {
		return uid;
	}
	public void setUid(String uid) {
		this.uid = uid;
	}
	public List<OllamaMessage> getMessages() {
		return messages;
	}
	public void setMessages(List<OllamaMessage> messages) {
		this.messages = messages;
	}
	
}
