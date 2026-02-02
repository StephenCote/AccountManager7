package org.cote.accountmanager.mcp.server;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP server protocol handler.
 * Manages sessions, dispatches JSON-RPC methods, and enforces the MCP lifecycle.
 * Transport-agnostic — receives raw JSON strings and returns McpDispatchResult.
 */
public class McpServer {

	private static final Logger logger = LogManager.getLogger(McpServer.class);
	private static final String PROTOCOL_VERSION = "2025-03-26";
	private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

	private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();
	private final IResourceProvider resourceProvider;
	private final IToolProvider toolProvider;
	private final ObjectMapper mapper = new ObjectMapper();

	public McpServer(IResourceProvider resourceProvider, IToolProvider toolProvider) {
		this.resourceProvider = resourceProvider;
		this.toolProvider = toolProvider;
	}

	/**
	 * Handle an incoming JSON-RPC request.
	 *
	 * @param jsonBody         Raw JSON-RPC request body
	 * @param sessionIdHeader  Value of the Mcp-Session-Id header (null if absent)
	 * @param user             Authenticated AM7 user from JWT
	 * @return McpDispatchResult with response JSON, session ID, and HTTP status
	 */
	public McpDispatchResult handleRequest(String jsonBody, String sessionIdHeader, BaseRecord user) {
		McpJsonRpc.Request request;
		try {
			request = mapper.readValue(jsonBody, McpJsonRpc.Request.class);
		}
		catch (Exception e) {
			logger.error("Failed to parse JSON-RPC request", e);
			return errorResult(null, McpJsonRpc.ErrorBody.PARSE_ERROR, "Failed to parse JSON-RPC request");
		}

		if (!"2.0".equals(request.getJsonrpc())) {
			return errorResult(request.getId(), McpJsonRpc.ErrorBody.INVALID_REQUEST, "jsonrpc must be '2.0'");
		}

		String method = request.getMethod();
		if (method == null || method.isEmpty()) {
			return errorResult(request.getId(), McpJsonRpc.ErrorBody.INVALID_REQUEST, "method is required");
		}

		/// Initialize does not require a session
		if ("initialize".equals(method)) {
			return handleInitialize(request, user);
		}

		/// All other methods require a valid session
		if (sessionIdHeader == null || !sessions.containsKey(sessionIdHeader)) {
			return errorResult(request.getId(), McpJsonRpc.ErrorBody.INVALID_REQUEST, "Missing or invalid Mcp-Session-Id");
		}

		McpSession session = sessions.get(sessionIdHeader);
		session.touch();

		/// Handle initialized notification
		if ("notifications/initialized".equals(method)) {
			session.setInitialized(true);
			return new McpDispatchResult(null, sessionIdHeader, 204);
		}

		/// Reject operations before initialization completes (except ping)
		if (!session.isInitialized() && !"ping".equals(method)) {
			return errorResult(request.getId(), McpJsonRpc.ErrorBody.INVALID_REQUEST, "Session not yet initialized");
		}

		return dispatch(request, session);
	}

	// =========================================================================
	// Method dispatch
	// =========================================================================

	@SuppressWarnings("unchecked")
	private McpDispatchResult dispatch(McpJsonRpc.Request request, McpSession session) {
		String method = request.getMethod();
		Map<String, Object> params = request.getParams() != null ? request.getParams() : Map.of();

		Object result;
		try {
			switch (method) {
				case "ping":
					result = Map.of();
					break;

				case "resources/list":
					String cursor = (String) params.get("cursor");
					result = resourceProvider.listResources(session, cursor);
					break;

				case "resources/read":
					String uri = (String) params.get("uri");
					if (uri == null) {
						return errorResult(request.getId(), McpJsonRpc.ErrorBody.INVALID_PARAMS, "uri is required");
					}
					List<McpJsonRpc.ResourceContent> contents = resourceProvider.readResource(session, uri);
					result = Map.of("contents", contents);
					break;

				case "resources/templates/list":
					result = Map.of("resourceTemplates", resourceProvider.listTemplates(session));
					break;

				case "tools/list":
					result = Map.of("tools", toolProvider.listTools(session));
					break;

				case "tools/call":
					String toolName = (String) params.get("name");
					Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
					if (toolName == null) {
						return errorResult(request.getId(), McpJsonRpc.ErrorBody.INVALID_PARAMS, "name is required");
					}
					result = toolProvider.callTool(session, toolName, arguments);
					break;

				default:
					return errorResult(request.getId(), McpJsonRpc.ErrorBody.METHOD_NOT_FOUND, "Method not found: " + method);
			}
		}
		catch (Exception e) {
			logger.error("Error dispatching method: " + method, e);
			return errorResult(request.getId(), McpJsonRpc.ErrorBody.INTERNAL_ERROR, "Internal error: " + e.getMessage());
		}

		return successResult(request.getId(), result, session.getSessionId());
	}

	// =========================================================================
	// Initialize handshake
	// =========================================================================

	@SuppressWarnings("unchecked")
	private McpDispatchResult handleInitialize(McpJsonRpc.Request request, BaseRecord user) {
		purgeExpiredSessions();

		String sessionId = UUID.randomUUID().toString();
		McpSession session = new McpSession(sessionId, user);

		Map<String, Object> params = request.getParams();
		if (params != null) {
			session.setClientProtocolVersion((String) params.get("protocolVersion"));
			Object caps = params.get("capabilities");
			if (caps instanceof Map) {
				session.setClientCapabilities((Map<String, Object>) caps);
			}
		}

		sessions.put(sessionId, session);

		McpJsonRpc.InitializeResult initResult = new McpJsonRpc.InitializeResult();
		initResult.setProtocolVersion(PROTOCOL_VERSION);

		McpJsonRpc.ServerCapabilities caps = new McpJsonRpc.ServerCapabilities();
		caps.setResources(Map.of());
		caps.setTools(Map.of());
		initResult.setCapabilities(caps);

		initResult.setServerInfo(new McpJsonRpc.ServerInfo("AccountManager7", "7.0.0"));

		return successResult(request.getId(), initResult, sessionId);
	}

	// =========================================================================
	// Session management
	// =========================================================================

	private void purgeExpiredSessions() {
		long now = System.currentTimeMillis();
		sessions.entrySet().removeIf(e -> (now - e.getValue().getLastAccessedAt()) > SESSION_TIMEOUT_MS);
	}

	public McpSession getSession(String sessionId) {
		return sessions.get(sessionId);
	}

	public int getActiveSessionCount() {
		return sessions.size();
	}

	// =========================================================================
	// Response builders
	// =========================================================================

	private McpDispatchResult successResult(Object id, Object result, String sessionId) {
		McpJsonRpc.Response response = new McpJsonRpc.Response(id, result);
		try {
			String json = mapper.writeValueAsString(response);
			return new McpDispatchResult(json, sessionId, 200);
		}
		catch (Exception e) {
			logger.error("Failed to serialize response", e);
			return errorResult(id, McpJsonRpc.ErrorBody.INTERNAL_ERROR, "Failed to serialize response");
		}
	}

	private McpDispatchResult errorResult(Object id, int code, String message) {
		McpJsonRpc.ErrorBody errorBody = new McpJsonRpc.ErrorBody(code, message);
		McpJsonRpc.ErrorResponse errorResp = new McpJsonRpc.ErrorResponse(id, errorBody);
		try {
			String json = mapper.writeValueAsString(errorResp);
			return new McpDispatchResult(json, null, 200);
		}
		catch (Exception e) {
			logger.error("Failed to serialize error response", e);
			return new McpDispatchResult(
				"{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}",
				null, 200
			);
		}
	}

	// =========================================================================
	// Dispatch result
	// =========================================================================

	public static class McpDispatchResult {
		private final String responseJson;
		private final String sessionId;
		private final int httpStatus;

		public McpDispatchResult(String responseJson, String sessionId, int httpStatus) {
			this.responseJson = responseJson;
			this.sessionId = sessionId;
			this.httpStatus = httpStatus;
		}

		public String getResponseJson() { return responseJson; }
		public String getSessionId() { return sessionId; }
		public int getHttpStatus() { return httpStatus; }
	}
}
