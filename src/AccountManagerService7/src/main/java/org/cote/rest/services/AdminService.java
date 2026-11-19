package org.cote.rest.services;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.AdminMaintenanceException;
import org.cote.accountmanager.util.AdminMaintenanceUtil;
import org.cote.accountmanager.util.AdminMaintenanceUtil.AuditFilter;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

/// PURE TRANSPORT over AdminMaintenanceUtil (Objects7) for the Ux752 Admin panel (/admin/system).
/// No authorization decision, scope resolution, query or delete lives here: architecture.md - "No
/// business logic in Service7."
///
/// Every route is @RolesAllowed({"admin"}) - the container gate - AND AdminMaintenanceUtil re-checks the
/// same role membership in-process, so an in-JVM call (a test, Console7) gets the same 401/403 as HTTP.
/// The all-organizations scope is a plain boolean; which organizations that means, and whether the caller
/// may ask for it (administrators of the /System organization only - a per-organization role never reaches
/// past its organization), is decided in Objects7. An organization id is never accepted from the client.
///
///   GET    /rest/admin/scope                                   -> {organizationId, organizationPath, allOrganizationsAllowed, organizations:[{id,path}]}
///   GET    /rest/admin/thumbnails/count?allOrganizations=      -> {scope, total, organizations:[{id,path,count}]}
///   DELETE /rest/admin/thumbnails?allOrganizations=            -> same shape; count = deleted
///   GET    /rest/admin/audit/count?allOrganizations=           -> {scope, total, organizations:[{id,path,count}]}
///   POST   /rest/admin/audit/search                            -> io.queryResult (results newest first; totalCount)
///          body: {allOrganizations, startRecord, recordCount, action, response, resourceType, subjectType, text, since, until}
///   GET    /rest/admin/audit/{objectId}?allOrganizations=      -> {audit:{...}, organizationPath, contextUser, subject, resource}
///   DELETE /rest/admin/audit?allOrganizations=&days=N          -> trim: delete records older than N days (N >= 1)
///   DELETE /rest/admin/audit?allOrganizations=&purge=true      -> purge: delete every record in scope
///          (neither days nor purge=true -> 400; both -> 400)
@DeclareRoles({"admin", "user"})
@Path("/admin")
public class AdminService {
	private static final Logger logger = LogManager.getLogger(AdminService.class);

	/// {"error":"..."} through the serializer, never by concatenation (FeatureConfigService precedent).
	private static Response errorResponse(int status, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", message);
		return Response.status(status).entity(JSONUtil.exportObject(body)).build();
	}

	/// Success bodies are plain maps/lists and must keep EVERY key the util put there - an empty
	/// `organizations` list on GET /scope is the signal "no all-organizations option", so it cannot
	/// be dropped. JSONUtil.exportObject serializes with Include.NON_EMPTY, which would drop it; use a
	/// default mapper here. (QueryResult bodies arrive already serialized by RecordSerializer.)
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static Response ok(Object body) {
		if(body instanceof String) {
			return Response.status(200).entity(body).build();
		}
		try {
			return Response.status(200).entity(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body)).build();
		}
		catch(JsonProcessingException e) {
			logger.error("Failed to serialize the response", e);
			return errorResponse(500, "Failed to serialize the response");
		}
	}

	private interface Op {
		Object run(BaseRecord user);
	}

	/// Resolve the principal and map AdminMaintenanceException to its status. 401 here means no
	/// principal at all; the util's own 401/403 pass through unchanged.
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
			logger.error("Admin maintenance request failed", e);
			return errorResponse(500, "Admin maintenance request failed: " + e.getMessage());
		}
	}

	// ───────────────────────────────── scope ─────────────────────────────────

	@RolesAllowed({"admin"})
	@GET
	@Path("/scope")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getScope(@Context HttpServletRequest request) {
		return run(request, user -> AdminMaintenanceUtil.describeScope(user));
	}

	// ───────────────────────────────── thumbnails ─────────────────────────────────

	@RolesAllowed({"admin"})
	@GET
	@Path("/thumbnails/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countThumbnails(@QueryParam("allOrganizations") @DefaultValue("false") boolean allOrganizations, @Context HttpServletRequest request) {
		return run(request, user -> AdminMaintenanceUtil.countThumbnails(user, allOrganizations));
	}

	@RolesAllowed({"admin"})
	@DELETE
	@Path("/thumbnails")
	@Produces(MediaType.APPLICATION_JSON)
	public Response cleanupThumbnails(@QueryParam("allOrganizations") @DefaultValue("false") boolean allOrganizations, @Context HttpServletRequest request) {
		return run(request, user -> {
			logger.info("Thumbnail cleanup requested by " + user.get("name") + " (allOrganizations=" + allOrganizations + ")");
			return AdminMaintenanceUtil.cleanupThumbnails(user, allOrganizations);
		});
	}

	// ───────────────────────────────── audit ─────────────────────────────────

	@RolesAllowed({"admin"})
	@GET
	@Path("/audit/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response countAudit(@QueryParam("allOrganizations") @DefaultValue("false") boolean allOrganizations, @Context HttpServletRequest request) {
		return run(request, user -> AdminMaintenanceUtil.countAudit(user, allOrganizations));
	}

	@RolesAllowed({"admin"})
	@POST
	@Path("/audit/search")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response searchAudit(String json, @Context HttpServletRequest request) {
		Map<String, Object> in = new LinkedHashMap<>();
		if(json != null && !json.isBlank()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = JSONUtil.importObject(json, LinkedHashMap.class);
			if(parsed == null) {
				return errorResponse(400, "Invalid JSON");
			}
			in = parsed;
		}
		final boolean allOrganizations = Boolean.TRUE.equals(in.get("allOrganizations"));
		final AuditFilter filter = new AuditFilter();
		filter.action = str(in.get("action"));
		filter.response = str(in.get("response"));
		filter.resourceType = str(in.get("resourceType"));
		filter.subjectType = str(in.get("subjectType"));
		filter.text = str(in.get("text"));
		filter.startRecord = lng(in.get("startRecord"), 0L);
		filter.recordCount = (int) lng(in.get("recordCount"), 25L);
		try {
			filter.since = date(in.get("since"));
			filter.until = date(in.get("until"));
		}
		catch(IllegalArgumentException e) {
			return errorResponse(400, e.getMessage());
		}
		return run(request, user -> {
			QueryResult qr = AdminMaintenanceUtil.listAudit(user, allOrganizations, filter);
			return qr.toFullString();
		});
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/audit/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAudit(@PathParam("objectId") String objectId, @QueryParam("allOrganizations") @DefaultValue("false") boolean allOrganizations, @Context HttpServletRequest request) {
		return run(request, user -> AdminMaintenanceUtil.getAuditDetail(user, allOrganizations, objectId));
	}

	/// One DELETE route for both trim and purge so a purge is an explicit, separate flag rather than
	/// "days omitted" - omitting a parameter must never be the destructive case.
	@RolesAllowed({"admin"})
	@DELETE
	@Path("/audit")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteAudit(
		@QueryParam("allOrganizations") @DefaultValue("false") boolean allOrganizations,
		@QueryParam("days") Integer days,
		@QueryParam("purge") @DefaultValue("false") boolean purge,
		@Context HttpServletRequest request
	) {
		if(purge && days != null) {
			return errorResponse(400, "Specify either days (trim) or purge=true, not both");
		}
		if(!purge && days == null) {
			return errorResponse(400, "Specify days (trim to the last N days) or purge=true");
		}
		return run(request, user -> {
			if(purge) {
				logger.info("Audit purge requested by " + user.get("name") + " (allOrganizations=" + allOrganizations + ")");
				return AdminMaintenanceUtil.purgeAudit(user, allOrganizations);
			}
			logger.info("Audit trim to " + days + " day(s) requested by " + user.get("name") + " (allOrganizations=" + allOrganizations + ")");
			return AdminMaintenanceUtil.trimAudit(user, allOrganizations, days.intValue());
		});
	}

	// ───────────────────────────────── helpers ─────────────────────────────────

	private static String str(Object o) {
		return (o == null ? null : String.valueOf(o));
	}

	private static long lng(Object o, long def) {
		if(o instanceof Number) {
			return ((Number) o).longValue();
		}
		if(o instanceof String && !((String) o).isBlank()) {
			try {
				return Long.parseLong(((String) o).trim());
			}
			catch(NumberFormatException e) {
				return def;
			}
		}
		return def;
	}

	/// Epoch millis (number or numeric string) - the wire format RecordSerializer uses for timestamps.
	private static Date date(Object o) {
		if(o == null) {
			return null;
		}
		if(o instanceof Number) {
			return new Date(((Number) o).longValue());
		}
		String s = String.valueOf(o).trim();
		if(s.isEmpty()) {
			return null;
		}
		try {
			return new Date(Long.parseLong(s));
		}
		catch(NumberFormatException e) {
			throw new IllegalArgumentException("Dates must be epoch milliseconds: " + s);
		}
	}
}
