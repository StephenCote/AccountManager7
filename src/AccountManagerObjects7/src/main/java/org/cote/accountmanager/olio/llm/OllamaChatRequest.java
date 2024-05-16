package org.cote.accountmanager.olio.llm;

import java.util.UUID;

/// OllamaChatRequest used for proxied API connections
///
public class OllamaChatRequest {
	private String uid = null;
	private ESRBEnumType rating = ESRBEnumType.E;
	private String systemCharacter = null;
	private String userCharacter = null;
	private String message = null;
	private boolean assist = false;
	private boolean useNLP = false;
	private String model = null;
	public OllamaChatRequest() {
		
	}

	public boolean isUseNLP() {
		return useNLP;
	}

	public void setUseNLP(boolean useNLP) {
		this.useNLP = useNLP;
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

	public boolean isAssist() {
		return assist;
	}

	public void setAssist(boolean assist) {
		this.assist = assist;
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
