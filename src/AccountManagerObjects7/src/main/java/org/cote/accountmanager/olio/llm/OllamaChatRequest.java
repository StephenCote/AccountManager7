package org.cote.accountmanager.olio.llm;

public class OllamaChatRequest {
	private ESRBEnumType rating = ESRBEnumType.E;
	private String systemCharacter = null;
	private String userCharacter = null;
	private String message = null;
	
	public OllamaChatRequest() {
		
	}

	public ESRBEnumType getRating() {
		return rating;
	}

	public void setRating(ESRBEnumType rating) {
		this.rating = rating;
	}

	public String getSystemCharacter() {
		return systemCharacter;
	}

	public void setSystemCharacter(String systemCharacter) {
		this.systemCharacter = systemCharacter;
	}

	public String getUserCharacter() {
		return userCharacter;
	}

	public void setUserCharacter(String userCharacter) {
		this.userCharacter = userCharacter;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
	
	
}
