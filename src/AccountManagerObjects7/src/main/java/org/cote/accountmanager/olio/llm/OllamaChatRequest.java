package org.cote.accountmanager.olio.llm;


/// OllamaChatRequest used for proxied API connections
///
public class OllamaChatRequest extends ChatConfiguration {
	private String message = null;

	public OllamaChatRequest() {
		
	}
	
	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
	
	
}
