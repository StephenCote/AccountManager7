package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.server.IResourceProvider;
import org.cote.accountmanager.mcp.server.IToolProvider;
import org.cote.accountmanager.mcp.server.McpJsonRpc;
import org.cote.accountmanager.mcp.server.McpServer;
import org.cote.accountmanager.mcp.server.McpSession;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Protocol compliance tests for the MCP server.
 * Uses stub providers to isolate protocol logic from AM7 data access.
 */
public class TestMcpServer extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpServer.class);
	private final ObjectMapper mapper = new ObjectMapper();

	// =========================================================================
	// Initialize handshake tests
	// =========================================================================

	@Test
	public void TestInitializeHandshake() throws Exception {
		logger.info("TestInitializeHandshake");

		McpServer server = createTestServer();
		BaseRecord user = getTestUser();

		/// Step 1: Send initialize
		String initJson = buildRequest("req-1", "initialize", Map.of(
			"protocolVersion", "2025-03-26",
			"capabilities", Map.of()
		));
		McpServer.McpDispatchResult result = server.handleRequest(initJson, null, user);

		assertEquals("Should return 200", 200, result.getHttpStatus());
		assertNotNull("Should have session ID", result.getSessionId());
		assertNotNull("Should have response body", result.getResponseJson());

		/// Verify response structure
		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertEquals("2.0", resp.get("jsonrpc"));
		assertEquals("req-1", resp.get("id"));

		Map<String, Object> initResult = asMap(resp.get("result"));
		assertEquals("2025-03-26", initResult.get("protocolVersion"));
		assertNotNull("Should have capabilities", initResult.get("capabilities"));
		assertNotNull("Should have serverInfo", initResult.get("serverInfo"));

		Map<String, Object> caps = asMap(initResult.get("capabilities"));
		assertNotNull("Should declare resources", caps.get("resources"));
		assertNotNull("Should declare tools", caps.get("tools"));

		Map<String, Object> serverInfo = asMap(initResult.get("serverInfo"));
		assertEquals("AccountManager7", serverInfo.get("name"));

		/// Step 2: Send notifications/initialized
		String sessionId = result.getSessionId();
		String notifJson = buildNotification("notifications/initialized");
		McpServer.McpDispatchResult notifResult = server.handleRequest(notifJson, sessionId, user);

		assertEquals("Should return 204", 204, notifResult.getHttpStatus());
		assertNull("Should have no response body", notifResult.getResponseJson());

		/// Verify session is now initialized
		McpSession session = server.getSession(sessionId);
		assertNotNull("Session should exist", session);
		assertTrue("Session should be initialized", session.isInitialized());
	}

	// =========================================================================
	// Session enforcement tests
	// =========================================================================

	@Test
	public void TestSessionRequired() throws Exception {
		logger.info("TestSessionRequired");

		McpServer server = createTestServer();
		BaseRecord user = getTestUser();

		/// Send resources/list without session
		String json = buildRequest("req-2", "resources/list", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, null, user);

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> error = asMap(resp.get("error"));
		assertNotNull("Should have error", error);
		assertEquals(-32600, ((Number) error.get("code")).intValue());
	}

	@Test
	public void TestInvalidSessionRejected() throws Exception {
		logger.info("TestInvalidSessionRejected");

		McpServer server = createTestServer();
		BaseRecord user = getTestUser();

		String json = buildRequest("req-3", "ping", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, "nonexistent-session-id", user);

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertNotNull("Should have error", resp.get("error"));
	}

	@Test
	public void TestUninitializedSessionRejected() throws Exception {
		logger.info("TestUninitializedSessionRejected");

		McpServer server = createTestServer();
		BaseRecord user = getTestUser();

		/// Initialize but don't send notifications/initialized
		String initJson = buildRequest("req-4", "initialize", Map.of(
			"protocolVersion", "2025-03-26",
			"capabilities", Map.of()
		));
		McpServer.McpDispatchResult initResult = server.handleRequest(initJson, null, user);
		String sessionId = initResult.getSessionId();

		/// Try to call resources/list before initialized
		String json = buildRequest("req-5", "resources/list", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, user);

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertNotNull("Should have error", resp.get("error"));
		Map<String, Object> error = asMap(resp.get("error"));
		assertEquals(-32600, ((Number) error.get("code")).intValue());
	}

	// =========================================================================
	// Ping test
	// =========================================================================

	@Test
	public void TestPing() throws Exception {
		logger.info("TestPing");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-ping", "ping", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertEquals("req-ping", resp.get("id"));
		Map<String, Object> pingResult = asMap(resp.get("result"));
		assertNotNull("Ping result should not be null", pingResult);
		assertTrue("Ping result should be empty map", pingResult.isEmpty());
	}

	// =========================================================================
	// Method not found test
	// =========================================================================

	@Test
	public void TestMethodNotFound() throws Exception {
		logger.info("TestMethodNotFound");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-bad", "nonexistent/method", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> error = asMap(resp.get("error"));
		assertNotNull("Should have error", error);
		assertEquals(-32601, ((Number) error.get("code")).intValue());
		assertTrue("Error message should mention method", ((String) error.get("message")).contains("nonexistent/method"));
	}

	// =========================================================================
	// Resource method tests
	// =========================================================================

	@Test
	public void TestResourceList() throws Exception {
		logger.info("TestResourceList");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-rl", "resources/list", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertNull("Should have no error", resp.get("error"));

		Map<String, Object> listResult = asMap(resp.get("result"));
		assertNotNull("Should have result", listResult);
		assertNotNull("Should have resources array", listResult.get("resources"));
		assertTrue("Resources should be a list", listResult.get("resources") instanceof List);
	}

	@Test
	public void TestResourceTemplatesList() throws Exception {
		logger.info("TestResourceTemplatesList");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-rt", "resources/templates/list", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> templResult = asMap(resp.get("result"));
		assertNotNull("Should have resourceTemplates", templResult.get("resourceTemplates"));

		List<?> templates = (List<?>) templResult.get("resourceTemplates");
		assertTrue("Should have at least one template", templates.size() >= 1);
	}

	@Test
	public void TestResourceReadMissingUri() throws Exception {
		logger.info("TestResourceReadMissingUri");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		/// Missing uri param
		String json = buildRequest("req-rr", "resources/read", Map.of());
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> error = asMap(resp.get("error"));
		assertNotNull("Should have error for missing uri", error);
		assertEquals(-32602, ((Number) error.get("code")).intValue());
	}

	// =========================================================================
	// Tool method tests
	// =========================================================================

	@Test
	public void TestToolsList() throws Exception {
		logger.info("TestToolsList");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-tl", "tools/list", null);
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> toolsResult = asMap(resp.get("result"));
		assertNotNull("Should have tools array", toolsResult.get("tools"));

		List<?> tools = (List<?>) toolsResult.get("tools");
		assertEquals("Should have 4 tools", 4, tools.size());

		/// Verify each tool has required fields
		for (Object toolObj : tools) {
			Map<String, Object> tool = asMap(toolObj);
			assertNotNull("Tool should have name", tool.get("name"));
			assertNotNull("Tool should have description", tool.get("description"));
			assertNotNull("Tool should have inputSchema", tool.get("inputSchema"));
		}
	}

	@Test
	public void TestToolCallMissingName() throws Exception {
		logger.info("TestToolCallMissingName");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-tc", "tools/call", Map.of("arguments", Map.of()));
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> error = asMap(resp.get("error"));
		assertNotNull("Should have error for missing tool name", error);
		assertEquals(-32602, ((Number) error.get("code")).intValue());
	}

	@Test
	public void TestToolCallUnknown() throws Exception {
		logger.info("TestToolCallUnknown");

		McpServer server = createTestServer();
		String sessionId = doInitialize(server);

		String json = buildRequest("req-tu", "tools/call", Map.of(
			"name", "nonexistent_tool",
			"arguments", Map.of()
		));
		McpServer.McpDispatchResult result = server.handleRequest(json, sessionId, getTestUser());

		assertEquals(200, result.getHttpStatus());
		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertNull("Should not have error (tool errors are in result)", resp.get("error"));

		Map<String, Object> toolResult = asMap(resp.get("result"));
		assertTrue("Should have isError=true", (Boolean) toolResult.get("isError"));
	}

	// =========================================================================
	// JSON-RPC validation tests
	// =========================================================================

	@Test
	public void TestInvalidJsonRpc() throws Exception {
		logger.info("TestInvalidJsonRpc");

		McpServer server = createTestServer();
		BaseRecord user = getTestUser();

		/// Bad jsonrpc version
		String json = "{\"jsonrpc\":\"1.0\",\"id\":\"bad\",\"method\":\"ping\"}";
		McpServer.McpDispatchResult result = server.handleRequest(json, null, user);

		Map<String, Object> resp = parseJson(result.getResponseJson());
		assertNotNull("Should have error", resp.get("error"));
	}

	@Test
	public void TestMalformedJson() throws Exception {
		logger.info("TestMalformedJson");

		McpServer server = createTestServer();
		BaseRecord user = getTestUser();

		McpServer.McpDispatchResult result = server.handleRequest("not json at all", null, user);

		Map<String, Object> resp = parseJson(result.getResponseJson());
		Map<String, Object> error = asMap(resp.get("error"));
		assertNotNull("Should have error", error);
		assertEquals(-32700, ((Number) error.get("code")).intValue());
	}

	// =========================================================================
	// Session timeout test
	// =========================================================================

	@Test
	public void TestSessionCount() throws Exception {
		logger.info("TestSessionCount");

		McpServer server = createTestServer();
		assertEquals("Should start with no sessions", 0, server.getActiveSessionCount());

		doInitialize(server);
		assertEquals("Should have one session", 1, server.getActiveSessionCount());

		doInitialize(server);
		assertEquals("Should have two sessions", 2, server.getActiveSessionCount());
	}

	// =========================================================================
	// Helper methods
	// =========================================================================

	private McpServer createTestServer() {
		return new McpServer(new StubResourceProvider(), new StubToolProvider());
	}

	private BaseRecord getTestUser() {
		try {
			BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
			user.set("name", "testUser");
			user.set("organizationId", 1L);
			return user;
		}
		catch (Exception e) {
			logger.error("Failed to create test user", e);
			return null;
		}
	}

	private String doInitialize(McpServer server) throws Exception {
		BaseRecord user = getTestUser();
		String initJson = buildRequest("init", "initialize", Map.of(
			"protocolVersion", "2025-03-26",
			"capabilities", Map.of()
		));
		McpServer.McpDispatchResult initResult = server.handleRequest(initJson, null, user);
		String sessionId = initResult.getSessionId();

		String notifJson = buildNotification("notifications/initialized");
		server.handleRequest(notifJson, sessionId, user);

		return sessionId;
	}

	private String buildRequest(String id, String method, Map<String, Object> params) throws Exception {
		Map<String, Object> req = new HashMap<>();
		req.put("jsonrpc", "2.0");
		req.put("id", id);
		req.put("method", method);
		if (params != null) {
			req.put("params", params);
		}
		return mapper.writeValueAsString(req);
	}

	private String buildNotification(String method) throws Exception {
		Map<String, Object> req = new HashMap<>();
		req.put("jsonrpc", "2.0");
		req.put("method", method);
		return mapper.writeValueAsString(req);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseJson(String json) throws Exception {
		return mapper.readValue(json, Map.class);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object obj) {
		if (obj instanceof Map) return (Map<String, Object>) obj;
		return null;
	}

	// =========================================================================
	// Stub providers for protocol-level testing
	// =========================================================================

	private static class StubResourceProvider implements IResourceProvider {
		@Override
		public Map<String, Object> listResources(McpSession session, String cursor) {
			Map<String, Object> result = new HashMap<>();
			List<McpJsonRpc.Resource> resources = new ArrayList<>();
			resources.add(new McpJsonRpc.Resource(
				"am7://test/data.data/stub-1", "Test Document", "A stub document", "text/plain"
			));
			result.put("resources", resources);
			return result;
		}

		@Override
		public List<McpJsonRpc.ResourceContent> readResource(McpSession session, String uri) {
			List<McpJsonRpc.ResourceContent> contents = new ArrayList<>();
			contents.add(McpJsonRpc.ResourceContent.text(uri, "text/plain", "Stub resource content"));
			return contents;
		}

		@Override
		public List<McpJsonRpc.ResourceTemplate> listTemplates(McpSession session) {
			List<McpJsonRpc.ResourceTemplate> templates = new ArrayList<>();
			templates.add(new McpJsonRpc.ResourceTemplate(
				"am7://{org}/data.data/{id}", "Stub Template", "A stub template", "text/plain"
			));
			return templates;
		}
	}

	private static class StubToolProvider implements IToolProvider {
		@Override
		public List<McpJsonRpc.Tool> listTools(McpSession session) {
			List<McpJsonRpc.Tool> tools = new ArrayList<>();
			tools.add(new McpJsonRpc.Tool("am7_vector_search", "Search", Map.of("type", "object")));
			tools.add(new McpJsonRpc.Tool("am7_document_list", "List docs", Map.of("type", "object")));
			tools.add(new McpJsonRpc.Tool("am7_document_read", "Read doc", Map.of("type", "object")));
			tools.add(new McpJsonRpc.Tool("am7_chat_history", "Chat history", Map.of("type", "object")));
			return tools;
		}

		@Override
		public McpJsonRpc.ToolResult callTool(McpSession session, String toolName, Map<String, Object> arguments) {
			if ("am7_vector_search".equals(toolName)) {
				return McpJsonRpc.ToolResult.success("Stub search result for: " + arguments.get("query"));
			}
			return McpJsonRpc.ToolResult.error("Unknown tool: " + toolName);
		}
	}
}
