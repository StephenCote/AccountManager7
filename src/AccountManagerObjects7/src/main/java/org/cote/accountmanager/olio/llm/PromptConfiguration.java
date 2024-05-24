package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.RaceEnumType;

public class PromptConfiguration {

	private List<String> system = new ArrayList<>();
	private List<String> systemCensorWarning = new ArrayList<>();
	private List<String> systemNlp = new ArrayList<>();
	
	private List<String> assistant = new ArrayList<>();
	private List<String> assistantNlp = new ArrayList<>();
	private List<String> assistantCensorWarning = new ArrayList<>();
	
	private List<String> user = new ArrayList<>();
	private List<String> scene = new ArrayList<>();
	private List<String> setting = new ArrayList<>();
	private List<PromptRaceConfiguration> races = new ArrayList<>();
	private List<String> userConsentPrefix = new ArrayList<>();
	private List<String> userConsentRating = new ArrayList<>();
	private List<String> userConsentNlp = new ArrayList<>();
	
	public PromptConfiguration() {
		
	}

	public List<String> getSystem() {
		return system;
	}

	public void setSystem(List<String> system) {
		this.system = system;
	}

	public List<String> getSystemCensorWarning() {
		return systemCensorWarning;
	}

	public void setSystemCensorWarning(List<String> systemCensorWarning) {
		this.systemCensorWarning = systemCensorWarning;
	}

	public List<String> getSystemNlp() {
		return systemNlp;
	}

	public void setSystemNlp(List<String> systemNlp) {
		this.systemNlp = systemNlp;
	}

	public List<String> getAssistant() {
		return assistant;
	}

	public void setAssistant(List<String> assistant) {
		this.assistant = assistant;
	}

	public List<String> getAssistantNlp() {
		return assistantNlp;
	}

	public void setAssistantNlp(List<String> assistantNlp) {
		this.assistantNlp = assistantNlp;
	}

	public List<String> getAssistantCensorWarning() {
		return assistantCensorWarning;
	}

	public void setAssistantCensorWarning(List<String> assistantCensorWarning) {
		this.assistantCensorWarning = assistantCensorWarning;
	}

	public List<String> getUser() {
		return user;
	}

	public void setUser(List<String> user) {
		this.user = user;
	}

	public List<String> getScene() {
		return scene;
	}

	public void setScene(List<String> scene) {
		this.scene = scene;
	}

	public List<String> getSetting() {
		return setting;
	}

	public void setSetting(List<String> setting) {
		this.setting = setting;
	}

	public List<PromptRaceConfiguration> getRaces() {
		return races;
	}

	public void setRaces(List<PromptRaceConfiguration> races) {
		this.races = races;
	}

	public List<String> getUserConsentPrefix() {
		return userConsentPrefix;
	}

	public void setUserConsentPrefix(List<String> userConsentPrefix) {
		this.userConsentPrefix = userConsentPrefix;
	}

	public List<String> getUserConsentRating() {
		return userConsentRating;
	}

	public void setUserConsentRating(List<String> userConsentRating) {
		this.userConsentRating = userConsentRating;
	}

	public List<String> getUserConsentNlp() {
		return userConsentNlp;
	}

	public void setUserConsentNlp(List<String> userConsentNlp) {
		this.userConsentNlp = userConsentNlp;
	}
	
	
	
}

