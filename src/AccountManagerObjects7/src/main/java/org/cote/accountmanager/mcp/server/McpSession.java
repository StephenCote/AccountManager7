package org.cote.accountmanager.mcp.server;

import java.util.Map;

import org.cote.accountmanager.record.BaseRecord;

/**
 * Per-client MCP session state.
 * Created at initialize, tracks lifecycle and user context.
 */
public class McpSession {

	private String sessionId;
	private boolean initialized;
	private String clientProtocolVersion;
	private Map<String, Object> clientCapabilities;
	private BaseRecord user;
	private long createdAt;
	private long lastAccessedAt;

	public McpSession(String sessionId, BaseRecord user) {
		this.sessionId = sessionId;
		this.user = user;
		this.initialized = false;
		this.createdAt = System.currentTimeMillis();
		this.lastAccessedAt = this.createdAt;
	}

	public void touch() {
		this.lastAccessedAt = System.currentTimeMillis();
	}

	public String getSessionId() { return sessionId; }
	public void setSessionId(String sessionId) { this.sessionId = sessionId; }

	public boolean isInitialized() { return initialized; }
	public void setInitialized(boolean initialized) { this.initialized = initialized; }

	public String getClientProtocolVersion() { return clientProtocolVersion; }
	public void setClientProtocolVersion(String clientProtocolVersion) { this.clientProtocolVersion = clientProtocolVersion; }

	public Map<String, Object> getClientCapabilities() { return clientCapabilities; }
	public void setClientCapabilities(Map<String, Object> clientCapabilities) { this.clientCapabilities = clientCapabilities; }

	public BaseRecord getUser() { return user; }
	public void setUser(BaseRecord user) { this.user = user; }

	public long getCreatedAt() { return createdAt; }

	public long getLastAccessedAt() { return lastAccessedAt; }
}
