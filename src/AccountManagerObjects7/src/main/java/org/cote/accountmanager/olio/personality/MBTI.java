package org.cote.accountmanager.olio.personality;

public class MBTI {
	private String key = null;
	private String name = null;
	private String description = null;
	public MBTI(String key, String name, String description) {
		this.key = key;
		this.name = name;
		this.description = description;
	}
	public String getKey() {
		return key;
	}
	public String getName() {
		return name;
	}
	public String getDescription() {
		return description;
	}
	public void setKey(String key) {
		this.key = key;
	}
	public void setName(String name) {
		this.name = name;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	
}