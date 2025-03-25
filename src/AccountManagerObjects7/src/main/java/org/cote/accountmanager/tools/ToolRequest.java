package org.cote.accountmanager.tools;

public class ToolRequest{
	private String text = null;
	private int max_length = 130;
	private int min_length = 30;
	private int top_n = 5;
	private int num_keywords = 5;
	private int num_topics = 5;
	
	public ToolRequest(String text) {
		this.text = text;
	}
	public String getText() {
		return text;
	}
	public void setText(String text) {
		this.text = text;
	}
	public int getMax_length() {
		return max_length;
	}
	public void setMax_length(int max_length) {
		this.max_length = max_length;
	}
	public int getMin_length() {
		return min_length;
	}
	public void setMin_length(int min_length) {
		this.min_length = min_length;
	}
	public int getTop_n() {
		return top_n;
	}
	public void setTop_n(int top_n) {
		this.top_n = top_n;
	}
	public int getNum_keywords() {
		return num_keywords;
	}
	public void setNum_keywords(int num_keywords) {
		this.num_keywords = num_keywords;
	}
	public int getNum_topics() {
		return num_topics;
	}
	public void setNum_topics(int num_topics) {
		this.num_topics = num_topics;
	}
	
}