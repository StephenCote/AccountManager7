package org.cote.accountmanager.olio.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class OllamaMessage {
	private String role = null;
	private String content = null;
	private String images = null;
	
	@JsonIgnore
	private boolean pruned = false;
	public OllamaMessage() {
		
	}
	
	
	public boolean isPruned() {
		return pruned;
	}


	public void setPruned(boolean pruned) {
		this.pruned = pruned;
	}


	public String getRole() {
		return role;
	}
	public void setRole(String role) {
		this.role = role;
	}
	public String getContent() {
		return content;
	}
	public void setContent(String content) {
		this.content = content;
	}
	public String getImages() {
		return images;
	}
	public void setImages(String images) {
		this.images = images;
	}
	
	
}
