package org.cote.accountmanager.olio.llm;

public class OllamaExchange {
	private OllamaRequest request = null;
	private OllamaResponse response = null;
	public OllamaExchange() {
		
	}
	public OllamaExchange(OllamaRequest req, OllamaResponse rep) {
		request = req;
		response = rep;
	}
	public OllamaRequest getRequest() {
		return request;
	}
	public void setRequest(OllamaRequest request) {
		this.request = request;
	}
	public OllamaResponse getResponse() {
		return response;
	}
	public void setResponse(OllamaResponse response) {
		this.response = response;
	}
	
}
