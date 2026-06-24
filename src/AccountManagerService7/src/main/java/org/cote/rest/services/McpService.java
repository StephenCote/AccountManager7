package org.cote.rest.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.server.McpServer;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.rest.mcp.CompositeResourceProvider;
import org.cote.rest.mcp.CompositeToolProvider;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS endpoint for the MCP server protocol.
 * Implements Streamable HTTP transport: single POST endpoint for JSON-RPC 2.0.
 * Authentication handled by existing TokenFilter on /rest/*.
 */
@DeclareRoles({"admin", "user"})
@Path("/mcp")
public class McpService {

	private static final Logger logger = LogManager.getLogger(McpService.class);

	private static McpServer mcpServer;

	private static synchronized McpServer getServer() {
		if (mcpServer == null) {
			// Compose the core Am7 providers with the ISO 42001 providers (Phase 7). The ISO MCP tools/resources
			// live in the ISO module (Objects7 cannot depend on it), so they are merged here at the transport layer.
			CompositeResourceProvider rp = new CompositeResourceProvider();
			CompositeToolProvider tp = new CompositeToolProvider();
			mcpServer = new McpServer(rp, tp);
		}
		return mcpServer;
	}

	@RolesAllowed({"admin", "user"})
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response handlePost(
		String body,
		@Context HttpServletRequest request
	) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401)
				.entity("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Authentication required\"}}")
				.type(MediaType.APPLICATION_JSON)
				.build();
		}

		String sessionId = request.getHeader("Mcp-Session-Id");

		McpServer.McpDispatchResult result = getServer().handleRequest(body, sessionId, user);

		Response.ResponseBuilder rb;
		if (result.getHttpStatus() == 204) {
			rb = Response.noContent();
		}
		else {
			rb = Response.status(result.getHttpStatus())
				.entity(result.getResponseJson())
				.type(MediaType.APPLICATION_JSON);
		}

		if (result.getSessionId() != null) {
			rb.header("Mcp-Session-Id", result.getSessionId());
		}

		return rb.build();
	}
}
