package org.cote.rest.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.iso42001.mcp.ISO42001ToolProvider;
import org.cote.accountmanager.mcp.server.Am7ToolProvider;
import org.cote.accountmanager.mcp.server.IToolProvider;
import org.cote.accountmanager.mcp.server.McpJsonRpc;
import org.cote.accountmanager.mcp.server.McpSession;

/**
 * Combines the core {@link Am7ToolProvider} (Objects7) with the {@link ISO42001ToolProvider} (ISO module) so
 * both tool sets are served by the single {@code McpServer}. Lives in the Service7 transport layer because
 * Objects7 cannot depend on the ISO module (the ISO module depends on Objects7) — so the ISO MCP tools are
 * composed here rather than added to {@code Am7ToolProvider}.
 *
 * <p>Routing: tool calls whose name starts with {@code iso42001_} go to the ISO provider; all others go to
 * the Am7 provider. {@code listTools} concatenates both.</p>
 */
public class CompositeToolProvider implements IToolProvider {

	private final Am7ToolProvider am7 = new Am7ToolProvider();
	private final ISO42001ToolProvider iso = new ISO42001ToolProvider();

	@Override
	public List<McpJsonRpc.Tool> listTools(McpSession session) {
		List<McpJsonRpc.Tool> tools = new ArrayList<>(am7.listTools(session));
		tools.addAll(iso.listTools(session));
		return tools;
	}

	@Override
	public McpJsonRpc.ToolResult callTool(McpSession session, String toolName, Map<String, Object> arguments) {
		if (ISO42001ToolProvider.handles(toolName)) {
			return iso.callTool(session, toolName, arguments);
		}
		return am7.callTool(session, toolName, arguments);
	}
}
