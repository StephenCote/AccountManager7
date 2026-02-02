package org.cote.accountmanager.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of applying McpContextFilter to message content.
 * Contains the filtered content and categorized context blocks.
 */
public class McpFilterResult {

	private String content;
	private List<McpContext> citations = new ArrayList<>();
	private List<McpContext> reminders = new ArrayList<>();
	private List<McpContext> keyframes = new ArrayList<>();
	private List<McpContext> metrics = new ArrayList<>();
	private List<McpContext> reasoning = new ArrayList<>();
	private List<McpContext> media = new ArrayList<>();

	public String getContent() { return content; }
	public void setContent(String content) { this.content = content; }

	public List<McpContext> getCitations() { return citations; }
	public List<McpContext> getReminders() { return reminders; }
	public List<McpContext> getKeyframes() { return keyframes; }
	public List<McpContext> getMetrics() { return metrics; }
	public List<McpContext> getReasoning() { return reasoning; }
	public List<McpContext> getMedia() { return media; }
}
