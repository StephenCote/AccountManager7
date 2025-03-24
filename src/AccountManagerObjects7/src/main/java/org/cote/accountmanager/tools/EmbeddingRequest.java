package org.cote.accountmanager.tools;

public class EmbeddingRequest{
	private String text = null;
	public EmbeddingRequest(String text) {
		this.text = text;
	}
	public String getText() {
		return text;
	}
	public void setText(String text) {
		this.text = text;
	}
	
}