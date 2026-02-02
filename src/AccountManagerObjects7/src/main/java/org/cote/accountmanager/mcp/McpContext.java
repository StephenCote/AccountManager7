package org.cote.accountmanager.mcp;

import java.util.ArrayList;
import java.util.List;

public class McpContext {
	private String type;
	private String uri;
	private boolean ephemeral;
	private boolean inline;
	private String body;
	private List<String> tags = new ArrayList<>();
	private int start;
	private int end;

	public McpContext() {}

	public McpContext(String type, String uri, boolean ephemeral) {
		this.type = type;
		this.uri = uri;
		this.ephemeral = ephemeral;
	}

	public String getType() { return type; }
	public void setType(String type) { this.type = type; }

	public String getUri() { return uri; }
	public void setUri(String uri) { this.uri = uri; }

	public boolean isEphemeral() { return ephemeral; }
	public void setEphemeral(boolean ephemeral) { this.ephemeral = ephemeral; }

	public boolean isInline() { return inline; }
	public void setInline(boolean inline) { this.inline = inline; }

	public String getBody() { return body; }
	public void setBody(String body) { this.body = body; }

	public List<String> getTags() { return tags; }
	public void setTags(List<String> tags) { this.tags = tags; }

	public int getStart() { return start; }
	public void setStart(int start) { this.start = start; }

	public int getEnd() { return end; }
	public void setEnd(int end) { this.end = end; }
}
