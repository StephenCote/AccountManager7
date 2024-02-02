package org.cote.accountmanager.olio.personality;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Sloan {
	private String key = null;
	private String mbtiKey = null;
	private String description = null;
	private List<String> favoredCareers = null;
	private List<String> disfavoredCareers = null;
	public Sloan(String key, String mbtiKey, String description, String favored, String disfavored) {
		this.key = key;
		this.mbtiKey = mbtiKey;
		this.description = description;
		this.favoredCareers = Arrays.asList(favored.split(",")).stream().map(s -> s.trim()).collect(Collectors.toList());
		this.disfavoredCareers = Arrays.asList(disfavored.split(",")).stream().map(s -> s.trim()).collect(Collectors.toList());
	}
	public String getKey() {
		return key;
	}
	public String getDescription() {
		return description;
	}
	public List<String> getFavoredCareers() {
		return favoredCareers;
	}
	public List<String> getDisfavoredCareers() {
		return disfavoredCareers;
	}

	public String getMbtiKey() {
		return mbtiKey;
	}
	public void setMbtiKey(String mbtiKey) {
		this.mbtiKey = mbtiKey;
	}
	public void setKey(String key) {
		this.key = key;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public void setFavoredCareers(List<String> favoredCareers) {
		this.favoredCareers = favoredCareers;
	}
	public void setDisfavoredCareers(List<String> disfavoredCareers) {
		this.disfavoredCareers = disfavoredCareers;
	}
	
	
	
}