package org.cote.accountmanager.mcp.server;

import java.util.List;
import java.util.Map;

/**
 * Interface for MCP tool providers.
 * Implementations supply tool listing and execution.
 */
public interface IToolProvider {

	/**
	 * List available tools.
	 * @param session  Active MCP session
	 * @return List of Tool definitions with JSON Schema inputSchema
	 */
	List<McpJsonRpc.Tool> listTools(McpSession session);

	/**
	 * Call a tool by name with arguments.
	 * @param session   Active MCP session
	 * @param toolName  The tool name (e.g., "am7_vector_search")
	 * @param arguments Map of argument name to value
	 * @return ToolResult with content items
	 */
	McpJsonRpc.ToolResult callTool(McpSession session, String toolName, Map<String, Object> arguments);
}
