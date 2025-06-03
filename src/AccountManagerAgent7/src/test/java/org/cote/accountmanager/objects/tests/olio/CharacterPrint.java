package org.cote.accountmanager.objects.tests.olio;

public class CharacterPrint{
	private String name = null;
	private String gender = "male";
	private String outfit = null;
	private String person = null;
	private String statistics = null;
	private String personality = null;
	public CharacterPrint(String name) {
		this.name = name; 
	}
	
	public String getGender() {
		return gender;
	}

	public void setGender(String gender) {
		this.gender = gender;
	}

	public String getOutfit() {
		return outfit;
	}
	public void setOutfit(String outfit) {
		this.outfit = outfit;
	}
	public String getPerson() {
		return person;
	}
	public void setPerson(String person) {
		this.person = person;
	}
	public String getStatistics() {
		return statistics;
	}
	public void setStatistics(String statistics) {
		this.statistics = statistics;
	}
	public String getPersonality() {
		return personality;
	}
	public void setPersonality(String personality) {
		this.personality = personality;
	}
	public String getName() {
		return name;
	}
	
}