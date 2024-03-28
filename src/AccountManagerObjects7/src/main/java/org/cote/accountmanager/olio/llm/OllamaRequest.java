package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

public class OllamaRequest{
	private String model = null;
	private String prompt = null;
	private String system = null;
	private boolean stream = false;
	private List<Integer> context = new ArrayList<>();
	private List<OllamaMessage> messages = new ArrayList<>();
	private OllamaOptions options = null;
	public OllamaRequest() {
		
	}
	
	public OllamaOptions getOptions() {
		return options;
	}

	public void setOptions(OllamaOptions options) {
		this.options = options;
	}

	public List<OllamaMessage> getMessages() {
		return messages;
	}

	public void setMessages(List<OllamaMessage> messages) {
		this.messages = messages;
	}

	public String getModel() {
		return model;
	}
	public void setModel(String model) {
		this.model = model;
	}
	
	public String getSystem() {
		return system;
	}

	public void setSystem(String system) {
		this.system = system;
	}

	public String getPrompt() {
		return prompt;
	}
	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}
	public boolean isStream() {
		return stream;
	}
	public void setStream(boolean stream) {
		this.stream = stream;
	}
	public List<Integer> getContext() {
		return context;
	}
	public void setContext(List<Integer> context) {
		this.context = context;
	}
	
	
}