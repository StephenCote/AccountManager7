package org.cote.accountmanager.olio.llm;

public class ChatConfiguration {
	
	private String uid = null;
	private String name = null;
	private ESRBEnumType rating = ESRBEnumType.E;
	private String systemCharacter = null;
	private String userCharacter = null;
	private boolean assist = false;
	private boolean useNLP = false;
	private String setting = null;
	private String model = null;
	private String userPrompt = null;
	
	public ChatConfiguration() {
		
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getUid() {
		return uid;
	}

	public void setUid(String uid) {
		this.uid = uid;
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

	public boolean isAssist() {
		return assist;
	}

	public void setAssist(boolean assist) {
		this.assist = assist;
	}

	public boolean isUseNLP() {
		return useNLP;
	}

	public void setUseNLP(boolean useNLP) {
		this.useNLP = useNLP;
	}

	public String getSetting() {
		return setting;
	}

	public void setSetting(String setting) {
		this.setting = setting;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getUserPrompt() {
		return userPrompt;
	}

	public void setUserPrompt(String userPrompt) {
		this.userPrompt = userPrompt;
	}
	
	
}
