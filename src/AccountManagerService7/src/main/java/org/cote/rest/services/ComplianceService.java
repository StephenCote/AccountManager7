package org.cote.rest.services;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.service.util.ServiceUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Compliance dashboard REST service (ISO42001Plan.md §6/§8a). Reports on policy/access violations recorded
 * in {@code system.audit} and exposes the bias-pattern + prompt-call-path configuration that drives the
 * overcorrection directive.
 *
 * <p><b>Violation signal:</b> an audit record with {@code response=DENY} is treated as a violation
 * (an access/policy denial). Compliance-specific action typing on {@code system.audit} is a future refinement
 * (ISO42001Plan.md §8a — "use existing action types if suitable"); DENY is the concrete existing signal, so
 * no audit-model change is made here.</p>
 *
 * <p>All reads go through {@code AccessPoint} as the authenticated principal — audit visibility is RBAC-gated.
 * The {@code PUT /patterns} override is admin-only.</p>
 */
@DeclareRoles({ "admin", "user" })
@Path("/compliance")
public class ComplianceService {

	private static final Logger logger = LogManager.getLogger(ComplianceService.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String PATTERNS_RESOURCE = "olio/llm/prompts/biasPatterns.json";
	private static final String PATTERNS_OVERRIDE_NAME = "iso42001-biasPatterns";

	/** The 5 LLM call paths that MUST carry the training-bias overcorrection directive (CLAUDE.md). */
	private static final String[][] CALL_PATHS = {
		{ "prompt.config.json", "system", "Main chat system prompt" },
		{ "prompt.config.json", "systemAnalyze", "Analysis calls" },
		{ "prompt.config.json", "systemNarrate", "Narration calls" },
		{ "chatOperations.json", "analyzeSystem", "Chat operations analyzer" },
		{ "compliance.json", "system", "Compliance evaluator (evaluates its own bias too)" }
	};

	private static Response unauthorized() {
		return Response.status(401).entity("{\"error\":true,\"message\":\"Authentication required\"}")
			.type(MediaType.APPLICATION_JSON).build();
	}

	private static Response ok(String json) {
		return Response.status(200).entity(json).type(MediaType.APPLICATION_JSON).build();
	}

	private long sinceMillis(String period) {
		if (period == null || period.isBlank()) {
			return 7L * 24 * 60 * 60 * 1000;
		}
		try {
			char unit = Character.toLowerCase(period.charAt(period.length() - 1));
			long n = Long.parseLong(period.substring(0, period.length() - 1).trim());
			switch (unit) {
				case 'h':
					return n * 60 * 60 * 1000;
				case 'd':
					return n * 24 * 60 * 60 * 1000;
				case 'w':
					return n * 7 * 24 * 60 * 60 * 1000;
				default:
					return 7L * 24 * 60 * 60 * 1000;
			}
		}
		catch (Exception e) {
			return 7L * 24 * 60 * 60 * 1000;
		}
	}

	private Query violationQuery(BaseRecord user, Date since, String area) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.field("response", ResponseEnumType.DENY.name());
		if (since != null) {
			q.field("createdDate", ComparatorEnumType.GREATER_THAN_OR_EQUALS, since);
		}
		if (area != null && !area.isBlank()) {
			q.field("resourceType", ComparatorEnumType.LIKE, "%" + area + "%");
		}
		return q;
	}

	// ── Summary ─────────────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/summary")
	@Produces(MediaType.APPLICATION_JSON)
	public Response summary(@QueryParam("period") @DefaultValue("7d") String period,
			@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		Date since = new Date(System.currentTimeMillis() - sinceMillis(period));
		Query q = violationQuery(user, since, null);
		int violations = IOSystem.getActiveContext().getAccessPoint().count(user, q);

		// Total audited actions in the period (denominator for a pass rate).
		Query totalQ = QueryUtil.createQuery(ModelNames.MODEL_AUDIT);
		totalQ.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		totalQ.field("createdDate", ComparatorEnumType.GREATER_THAN_OR_EQUALS, since);
		int total = IOSystem.getActiveContext().getAccessPoint().count(user, totalQ);

		double passRate = (total > 0) ? ((double) (total - violations) / (double) total) : 1.0;
		String body = String.format(
			"{\"period\":\"%s\",\"totalAudited\":%d,\"violations\":%d,\"passRate\":%.4f}",
			period, total, violations, passRate);
		return ok(body);
	}

	// ── Violations (paginated) ───────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/violations")
	@Produces(MediaType.APPLICATION_JSON)
	public Response violations(@QueryParam("startRecord") @DefaultValue("0") long startRecord,
			@QueryParam("recordCount") @DefaultValue("25") int recordCount,
			@QueryParam("period") @DefaultValue("30d") String period,
			@QueryParam("area") String area, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		Date since = new Date(System.currentTimeMillis() - sinceMillis(period));
		Query q = violationQuery(user, since, area);
		q.setRequestRange(startRecord, recordCount);
		q.setRequest(new String[] { FieldNames.FIELD_OBJECT_ID, "action", "response", "resourceType",
			"message", "createdDate" });
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		return ok(qr != null ? qr.toFullString() : "{\"results\":[],\"count\":0}");
	}

	// ── Date-range report ─────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/report")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response report(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		Date start = null;
		Date end = null;
		try {
			var node = (json == null || json.isBlank()) ? null : MAPPER.readTree(json);
			if (node != null) {
				if (node.hasNonNull("startDate")) {
					start = new Date(node.get("startDate").asLong());
				}
				if (node.hasNonNull("endDate")) {
					end = new Date(node.get("endDate").asLong());
				}
			}
		}
		catch (Exception e) {
			logger.warn("compliance/report: bad date payload", e);
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.field("response", ResponseEnumType.DENY.name());
		if (start != null) {
			q.field("createdDate", ComparatorEnumType.GREATER_THAN_OR_EQUALS, start);
		}
		if (end != null) {
			q.field("createdDate", ComparatorEnumType.LESS_THAN_OR_EQUALS, end);
		}
		int violations = IOSystem.getActiveContext().getAccessPoint().count(user, q);
		String body = String.format(
			"{\"startDate\":%s,\"endDate\":%s,\"violations\":%d}",
			start != null ? start.getTime() : "null", end != null ? end.getTime() : "null", violations);
		return ok(body);
	}

	// ── Bias patterns ─────────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/patterns")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPatterns(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		// Prefer an org override (data.data named iso42001-biasPatterns) if one was stored; else the default.
		BaseRecord override = findOverride(user);
		if (override != null) {
			IOSystem.getActiveContext().getReader().populate(override, new String[] { FieldNames.FIELD_BYTE_STORE });
			byte[] bytes = override.get(FieldNames.FIELD_BYTE_STORE);
			if (bytes != null && bytes.length > 0) {
				return ok(new String(bytes, StandardCharsets.UTF_8));
			}
		}
		String defaults = loadDefaultPatterns();
		return ok(defaults != null ? defaults : "{\"patterns\":[]}");
	}

	@RolesAllowed({ "admin" })
	@PUT
	@Path("/patterns")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response putPatterns(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		if (json == null || json.isBlank()) {
			return Response.status(400).entity("{\"error\":true,\"message\":\"Empty patterns body\"}")
				.type(MediaType.APPLICATION_JSON).build();
		}
		try {
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			BaseRecord override = findOverride(user);
			if (override == null) {
				override = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null,
					org.cote.accountmanager.io.ParameterList.newParameterList(FieldNames.FIELD_NAME,
						PATTERNS_OVERRIDE_NAME));
				override.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
				override.set(FieldNames.FIELD_BYTE_STORE, bytes);
				BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, override);
				if (created == null) {
					return Response.status(403).entity("{\"error\":true,\"message\":\"Create denied\"}")
						.type(MediaType.APPLICATION_JSON).build();
				}
			}
			else {
				override.set(FieldNames.FIELD_BYTE_STORE, bytes);
				IOSystem.getActiveContext().getAccessPoint().update(user, override);
			}
			return ok("{\"updated\":true}");
		}
		catch (Exception e) {
			logger.error("Failed to store bias patterns override", e);
			return Response.status(500).entity("{\"error\":true,\"message\":\"Store failed\"}")
				.type(MediaType.APPLICATION_JSON).build();
		}
	}

	// ── Prompt call-path directive status ───────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/prompts")
	@Produces(MediaType.APPLICATION_JSON)
	public Response prompts(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		List<Map<String, Object>> paths = new java.util.ArrayList<>();
		for (String[] cp : CALL_PATHS) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("file", cp[0]);
			m.put("field", cp[1]);
			m.put("description", cp[2]);
			m.put("overcorrectionRequired", true);
			paths.add(m);
		}
		try {
			return ok(MAPPER.writeValueAsString(Map.of("callPaths", paths)));
		}
		catch (Exception e) {
			return Response.status(500).entity("{\"error\":true}").type(MediaType.APPLICATION_JSON).build();
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private BaseRecord findOverride(BaseRecord user) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, PATTERNS_OVERRIDE_NAME);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private String loadDefaultPatterns() {
		try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(PATTERNS_RESOURCE)) {
			if (in == null) {
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (Exception e) {
			logger.error("Failed to load default bias patterns", e);
			return null;
		}
	}
}
