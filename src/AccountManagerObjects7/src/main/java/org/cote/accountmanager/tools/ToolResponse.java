package org.cote.accountmanager.tools;

public class ToolResponse{
	private float[] embedding = new float[0];
	private String[] keywords = new String[0];
	private String sentiment = null;
	private String summary = null;
	private float confidence = 0;
	private String[] tokens = new String[0];
	private String[] topics = new String[0];
	private String[] tags = new String[0];
	private String[] entities = new String[0];
	
	public ToolResponse() {
		
	}
	public float[] getEmbedding() {
		return embedding;
	}
	public void setEmbedding(float[] embedding) {
		this.embedding = embedding;
	}
	public String[] getKeywords() {
		return keywords;
	}
	public void setKeywords(String[] keywords) {
		this.keywords = keywords;
	}
	public String getSentiment() {
		return sentiment;
	}
	public void setSentiment(String sentiment) {
		this.sentiment = sentiment;
	}
	public String getSummary() {
		return summary;
	}
	public void setSummary(String summary) {
		this.summary = summary;
	}
	public float getConfidence() {
		return confidence;
	}
	public void setConfidence(float confidence) {
		this.confidence = confidence;
	}
	public String[] getTokens() {
		return tokens;
	}
	public void setTokens(String[] tokens) {
		this.tokens = tokens;
	}
	public String[] getTopics() {
		return topics;
	}
	public void setTopics(String[] topics) {
		this.topics = topics;
	}
	public String[] getTags() {
		return tags;
	}
	public void setTags(String[] tags) {
		this.tags = tags;
	}
	public String[] getEntities() {
		return entities;
	}
	public void setEntities(String[] entities) {
		this.entities = entities;
	}

	
}