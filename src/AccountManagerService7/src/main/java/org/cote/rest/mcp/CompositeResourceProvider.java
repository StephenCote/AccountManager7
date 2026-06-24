package org.cote.rest.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.iso42001.mcp.ISO42001ResourceProvider;
import org.cote.accountmanager.mcp.server.Am7ResourceProvider;
import org.cote.accountmanager.mcp.server.IResourceProvider;
import org.cote.accountmanager.mcp.server.McpJsonRpc;
import org.cote.accountmanager.mcp.server.McpSession;

/**
 * Combines the core {@link Am7ResourceProvider} (Objects7) with the {@link ISO42001ResourceProvider}
 * (ISO module) under the single {@code McpServer}. In the Service7 transport layer for the same dependency
 * reason as {@link CompositeToolProvider}.
 *
 * <p>Routing: {@code readResource} dispatches to the ISO provider when the URI is an ISO resource
 * ({@code .../iso42001/...}); otherwise to Am7. {@code listResources}/{@code listTemplates} merge both.</p>
 */
public class CompositeResourceProvider implements IResourceProvider {

	private final Am7ResourceProvider am7 = new Am7ResourceProvider();
	private final ISO42001ResourceProvider iso = new ISO42001ResourceProvider();

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, Object> listResources(McpSession session, String cursor) {
		Map<String, Object> result = am7.listResources(session, cursor);
		if (result == null) {
			result = new HashMap<>();
		}
		List<McpJsonRpc.Resource> resources = (List<McpJsonRpc.Resource>) result.get("resources");
		if (resources == null) {
			resources = new ArrayList<>();
			result.put("resources", resources);
		}
		// Append the ISO resources' first page (audit artifacts). Am7's nextCursor drives continuation.
		Map<String, Object> isoResult = iso.listResources(session, null);
		if (isoResult != null) {
			List<McpJsonRpc.Resource> isoResources = (List<McpJsonRpc.Resource>) isoResult.get("resources");
			if (isoResources != null) {
				resources.addAll(isoResources);
			}
		}
		return result;
	}

	@Override
	public List<McpJsonRpc.ResourceContent> readResource(McpSession session, String uri) {
		if (ISO42001ResourceProvider.handles(uri)) {
			return iso.readResource(session, uri);
		}
		return am7.readResource(session, uri);
	}

	@Override
	public List<McpJsonRpc.ResourceTemplate> listTemplates(McpSession session) {
		List<McpJsonRpc.ResourceTemplate> templates = new ArrayList<>(am7.listTemplates(session));
		templates.addAll(iso.listTemplates(session));
		return templates;
	}
}
