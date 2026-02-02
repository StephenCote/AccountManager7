package org.cote.accountmanager.mcp.server;

import java.util.List;
import java.util.Map;

/**
 * Interface for MCP resource providers.
 * Implementations supply resource listing, reading, and template discovery.
 */
public interface IResourceProvider {

	/**
	 * List available resources visible to the session user.
	 * @param session  Active MCP session (contains user context)
	 * @param cursor   Optional pagination cursor (null for first page)
	 * @return Map with "resources" (List of Resource) and optional "nextCursor" (String)
	 */
	Map<String, Object> listResources(McpSession session, String cursor);

	/**
	 * Read a single resource by URI.
	 * @param session  Active MCP session
	 * @param uri      The am7:// URI to read
	 * @return List of ResourceContent items (typically one)
	 */
	List<McpJsonRpc.ResourceContent> readResource(McpSession session, String uri);

	/**
	 * List URI templates for parameterized resource access.
	 * @param session  Active MCP session
	 * @return List of ResourceTemplate definitions
	 */
	List<McpJsonRpc.ResourceTemplate> listTemplates(McpSession session);
}
