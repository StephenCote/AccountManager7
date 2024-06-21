package org.cote.accountmanager.olio.llm;

public class ChatConfiguration {
	
	private String uid = null;
	private String chatConfig = null;
	private String promptConfig = null;
	private String sessionName = null;
	public ChatConfiguration() {
		
	}

	public String getUid() {
		return uid;
	}

	public void setUid(String uid) {
		this.uid = uid;
	}

	public String getChatConfig() {
		return chatConfig;
	}

	public void setChatConfig(String chatConfig) {
		this.chatConfig = chatConfig;
	}

	public String getPromptConfig() {
		return promptConfig;
	}

	public void setPromptConfig(String promptConfig) {
		this.promptConfig = promptConfig;
	}

	public String getSessionName() {
		return sessionName;
	}

	public void setSessionName(String session) {
		this.sessionName = session;
	}

	
	
	
}
