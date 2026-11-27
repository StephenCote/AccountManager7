package org.cote.rest.services;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.AdminMaintenanceException;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ServerConfigUtil;
import org.cote.accountmanager.util.SystemCheckUtil;
import org.cote.service.util.ServiceUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/// PURE TRANSPORT over SystemCheckUtil (Objects7) for the Ux752 Service Status app (/admin/status).
/// Design of record: aiDocs/ServiceStatusDesign.md §7. No probe, authorization decision, query or cache
/// lives here (architecture.md: no business logic in Service7). The only thing this class contributes is
/// the web.xml fallback URL map, built from ServerConfigUtil.SERVER_INIT_PARAMS, because only Service7
/// has the ServletContext.
///
/// The client never sends a URL: the inputs are a connection objectId, a check name (whitelisted in
/// Objects7) and `deep`.
///
/// Two gates on every admin route: @RolesAllowed({"admin"}) here, and AdminMaintenanceUtil.requireAdministrator
/// inside every SystemCheckUtil entry point. /summary is open to role user and returns names only.
/// The server does NOT gate on the `serviceStatus` feature id (B6): that id is a UI gate; authorization is
/// the admin role.
///
///   GET  /rest/system/check/summary                      -> {status, degraded[], checkedAt}            (user, admin)
///   GET  /rest/system/check                              -> the last report for the caller             (admin)
///   POST /rest/system/check?deep=                        -> a forced full run; 409 deep while busy     (admin)
///   GET  /rest/system/check/connections                  -> readable connections, no probing           (admin)
///   POST /rest/system/check/connection/{objectId}?deep=  -> one connection; 404 unreadable, 409 busy   (admin)
///   POST /rest/system/check/server/{name}?deep=          -> one deployment check; 400 unknown name     (admin)
@DeclareRoles({"admin", "user"})
@Path("/system/check")
public class SystemCheckService {
	private static final Logger logger = LogManager.getLogger(SystemCheckService.class);

	/// Default mapper, not JSONUtil.exportObject: that one is Include.NON_EMPTY and would drop the null
	/// `target.url`, `urlRedacted:false`, empty `undetermined` / `degraded` lists the client reads.
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static Response errorResponse(int status, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", message);
		return Response.status(status).entity(JSONUtil.exportObject(body)).build();
	}

	private static Response ok(Object body) {
		try {
			return Response.status(200).entity(MAPPER.writeValueAsString(body)).build();
		}
		catch(JsonProcessingException e) {
			logger.error("Failed to serialize the response", e);
			return errorResponse(500, "Failed to serialize the response");
		}
	}

	/// web.xml init-param fallbacks for the six /System servers (ServerConfigUtil's mapping).
	static Map<String, String> defaults(ServletContext context) {
		Map<String, String> d = new LinkedHashMap<>();
		if(context == null) {
			return d;
		}
		for(String p : ServerConfigUtil.SERVER_INIT_PARAMS) {
			String v = context.getInitParameter(p);
			if(v != null && !v.isBlank()) {
				d.put(p, v.trim());
			}
		}
		return d;
	}

	private interface Op {
		Object run(BaseRecord user);
	}

	/// Resolve the principal and map AdminMaintenanceException to its status (400/401/403/404/409/500).
	/// A generic failure is logged and answered without its message, which may name a host.
	private Response run(HttpServletRequest request, Op op) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return errorResponse(401, "Not authenticated");
		}
		try {
			return ok(op.run(user));
		}
		catch(AdminMaintenanceException e) {
			return errorResponse(e.getStatus(), e.getMessage());
		}
		catch(RuntimeException e) {
			logger.error("System check request failed", e);
			return errorResponse(500, "System check request failed");
		}
	}

	@RolesAllowed({"user", "admin"})
	@GET
	@Path("/summary")
	@Produces(MediaType.APPLICATION_JSON)
	public Response summary(@Context HttpServletRequest request, @Context ServletContext context) {
		return run(request, user -> SystemCheckUtil.summary(user, defaults(context)));
	}

	@RolesAllowed({"admin"})
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response last(@Context HttpServletRequest request, @Context ServletContext context) {
		return run(request, user -> SystemCheckUtil.report(user, defaults(context), false, false));
	}

	@RolesAllowed({"admin"})
	@POST
	@Produces(MediaType.APPLICATION_JSON)
	public Response runCheck(@QueryParam("deep") @DefaultValue("false") boolean deep, @Context HttpServletRequest request, @Context ServletContext context) {
		return run(request, user -> SystemCheckUtil.report(user, defaults(context), true, deep));
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/connections")
	@Produces(MediaType.APPLICATION_JSON)
	public Response connections(@Context HttpServletRequest request) {
		return run(request, user -> SystemCheckUtil.connections(user));
	}

	@RolesAllowed({"admin"})
	@POST
	@Path("/connection/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response testConnection(@PathParam("objectId") String objectId, @QueryParam("deep") @DefaultValue("false") boolean deep, @Context HttpServletRequest request) {
		return run(request, user -> SystemCheckUtil.testConnection(user, objectId, deep));
	}

	@RolesAllowed({"admin"})
	@POST
	@Path("/server/{name}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response testServer(@PathParam("name") String name, @QueryParam("deep") @DefaultValue("false") boolean deep, @Context HttpServletRequest request, @Context ServletContext context) {
		return run(request, user -> SystemCheckUtil.testServer(user, defaults(context), name, deep));
	}
}
