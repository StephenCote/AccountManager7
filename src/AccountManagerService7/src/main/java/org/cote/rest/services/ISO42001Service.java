package org.cote.rest.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.certification.CertificationVerification;
import org.cote.accountmanager.iso42001.engine.BiasModuleRegistry;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.service.ISO42001ServiceFacade;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST transport shim for the ISO 42001 subsystem (design §6.4). <b>Marshaling only — no business logic.</b>
 * Every endpoint resolves the REST principal to an AM7 context user ({@link ServiceUtil#getPrincipalUser})
 * and delegates to the already-Track-A-tested engine/factory methods via {@link ISO42001ServiceFacade}
 * (run/report/PDF/certification) or to generic {@code AccessPoint} CRUD (test-config). RBAC is enforced by
 * those methods + the model access roles; this class adds the {@code @RolesAllowed} coarse gate and a
 * 401 when there is no principal.
 *
 * <p>Auto-discovered by {@code RestServiceConfig} (package {@code org.cote.rest.services}).</p>
 */
@DeclareRoles({ "admin", "user" })
@Path("/iso42001")
public class ISO42001Service {

	private static final Logger logger = LogManager.getLogger(ISO42001Service.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static Response unauthorized() {
		return Response.status(401).entity("{\"error\":true,\"message\":\"Authentication required\"}")
			.type(MediaType.APPLICATION_JSON).build();
	}

	private static Response notFound(String msg) {
		return Response.status(404).entity("{\"error\":true,\"message\":\"" + msg + "\"}")
			.type(MediaType.APPLICATION_JSON).build();
	}

	private static Response badRequest(String msg) {
		return Response.status(400).entity("{\"error\":true,\"message\":\"" + msg + "\"}")
			.type(MediaType.APPLICATION_JSON).build();
	}

	private static Response ok(String json) {
		return Response.status(200).entity(json).type(MediaType.APPLICATION_JSON).build();
	}

	private static JsonNode body(String json) {
		try {
			return (json == null || json.isBlank()) ? MAPPER.createObjectNode() : MAPPER.readTree(json);
		}
		catch (Exception e) {
			return MAPPER.createObjectNode();
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = (node == null) ? null : node.get(field);
		return (v == null || v.isNull()) ? null : v.asText();
	}

	// ── Test Management ──────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/config")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response createConfig(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if (imp == null) {
			return badRequest("Invalid testConfig payload");
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, imp);
		if (created == null) {
			return Response.status(403).entity("{\"error\":true,\"message\":\"Create denied\"}")
				.type(MediaType.APPLICATION_JSON).build();
		}
		return ok(created.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/config/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getConfig(@PathParam("id") String id, @Context HttpServletRequest request) {
		return findResponse(request, ISO42001ModelNames.MODEL_TEST_CONFIG, id, "Test config not found");
	}

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/run")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response startRun(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		String testConfigId = text(body(json), "testConfigId");
		if (testConfigId == null) {
			return badRequest("'testConfigId' is required");
		}
		BaseRecord run = ISO42001ServiceFacade.runFromConfig(user, testConfigId);
		if (run == null) {
			return badRequest("Run failed (config not found, endpoint unresolved, or access denied)");
		}
		return ok(run.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/run/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRun(@PathParam("id") String id, @Context HttpServletRequest request) {
		return findResponse(request, ISO42001ModelNames.MODEL_TEST_RUN, id, "Test run not found");
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/run/{id}/results")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRunResults(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		BaseRecord run = ISO42001ServiceFacade.findByObjectId(user, ISO42001ModelNames.MODEL_TEST_RUN, id);
		if (run == null) {
			return notFound("Test run not found");
		}
		List<BaseRecord> results = run.get("results");
		return ok(JSONUtil.exportObject(results, RecordSerializerConfig.getForeignUnfilteredModule()));
	}

	// ── Reporting ────────────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/report")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response generateReport(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		JsonNode b = body(json);
		String name = text(b, "name");
		String reportType = text(b, "reportType");
		List<String> runIds = stringList(b, "testRunIds");
		if (name == null || runIds.isEmpty()) {
			return badRequest("'name' and a non-empty 'testRunIds' are required");
		}
		BaseRecord report = ISO42001ServiceFacade.generateReport(user, name, reportType, runIds);
		if (report == null) {
			return badRequest("Report generation failed (no resolvable runs or access denied)");
		}
		return ok(report.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/report/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getReport(@PathParam("id") String id, @Context HttpServletRequest request) {
		return findResponse(request, ISO42001ModelNames.MODEL_REPORT, id, "Report not found");
	}

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/report/{id}/export")
	@Produces(MediaType.APPLICATION_JSON)
	public Response exportReport(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		BaseRecord pdf = ISO42001ServiceFacade.exportPdf(user, id);
		if (pdf == null) {
			return badRequest("Export failed (report not found or access denied)");
		}
		return ok(pdf.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/report/{id}/pdf")
	@Produces("application/pdf")
	public Response downloadPdf(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		BaseRecord report = ISO42001ServiceFacade.findByObjectId(user, ISO42001ModelNames.MODEL_REPORT, id);
		if (report == null) {
			return notFound("Report not found");
		}
		BaseRecord pdfRef = report.get("exportedPdf");
		if (pdfRef == null) {
			return notFound("Report has no exported PDF; POST /report/" + id + "/export first");
		}
		String pdfObjectId = pdfRef.get(FieldNames.FIELD_OBJECT_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, pdfObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(true);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if (data == null) {
			return notFound("Exported PDF data not found");
		}
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		byte[] bytes = data.get(FieldNames.FIELD_BYTE_STORE);
		if (bytes == null || bytes.length == 0) {
			return notFound("Exported PDF is empty");
		}
		return Response.status(200).entity(bytes).type("application/pdf")
			.header("Content-Disposition", "inline; filename=\"iso42001-report-" + id + ".pdf\"").build();
	}

	// ── Certification ─────────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/certification/request")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response requestCertification(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		JsonNode b = body(json);
		String reportId = text(b, "reportId");
		if (reportId == null) {
			return badRequest("'reportId' is required");
		}
		String certifierId = text(b, "certifierId");
		String justification = text(b, "justification");
		BaseRecord req = ISO42001ServiceFacade.requestCertification(user, reportId, certifierId,
			justification != null ? justification : "Certification requested");
		if (req == null) {
			return badRequest("Certification request failed (report not found or access denied)");
		}
		return ok(req.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/certification/approve/{requestId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response approve(@PathParam("requestId") String requestId, String json,
			@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		String note = text(body(json), "note");
		BaseRecord cert = ISO42001ServiceFacade.approveRequest(user, requestId, note != null ? note : "Approved");
		if (cert == null) {
			return badRequest("Approve failed (request not found, not a certifier, or access denied)");
		}
		return ok(cert.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/certification/deny/{requestId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response deny(@PathParam("requestId") String requestId, String json,
			@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		String reason = text(body(json), "reason");
		BaseRecord req = ISO42001ServiceFacade.denyRequest(user, requestId, reason != null ? reason : "Denied");
		if (req == null) {
			return badRequest("Deny failed (request not found, not a certifier, or access denied)");
		}
		return ok(req.toFullString());
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/certification/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getCertification(@PathParam("id") String id, @Context HttpServletRequest request) {
		return findResponse(request, ISO42001ModelNames.MODEL_CERTIFICATION, id, "Certification not found");
	}

	@RolesAllowed({ "user", "admin" })
	@POST
	@Path("/certification/verify/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response verify(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		CertificationVerification v = ISO42001ServiceFacade.verify(user, id);
		if (v == null) {
			return notFound("Certification not found");
		}
		try {
			return ok(MAPPER.writeValueAsString(v));
		}
		catch (Exception e) {
			logger.error("Failed to serialize verification", e);
			return Response.status(500).entity("{\"error\":true,\"message\":\"Serialization failed\"}")
				.type(MediaType.APPLICATION_JSON).build();
		}
	}

	// ── Library & Dashboard ────────────────────────────────────────────────────

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/modules")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listModules(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		try {
			return ok(MAPPER.writeValueAsString(BiasModuleRegistry.describe()));
		}
		catch (Exception e) {
			return Response.status(500).entity("{\"error\":true}").type(MediaType.APPLICATION_JSON).build();
		}
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/endpoints")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listEndpoints(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		var qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		BaseRecord[] results = (qr != null) ? qr.getResults() : new BaseRecord[0];
		return ok(JSONUtil.exportObject(List.of(results), RecordSerializerConfig.getForeignUnfilteredModule()));
	}

	@RolesAllowed({ "user", "admin" })
	@GET
	@Path("/dashboard")
	@Produces(MediaType.APPLICATION_JSON)
	public Response dashboard(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		// Lightweight rollup over the org's reports (already-computed verdicts) — no new aggregation logic.
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_REPORT);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "overallVerdict",
			"passCount", "flagCount", "failCount" });
		var qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		BaseRecord[] reports = (qr != null) ? qr.getResults() : new BaseRecord[0];
		int pass = 0;
		int flag = 0;
		int fail = 0;
		for (BaseRecord r : reports) {
			String v = r.get("overallVerdict");
			if ("PASS".equals(v)) {
				pass++;
			}
			else if ("FLAG".equals(v)) {
				flag++;
			}
			else if ("FAIL".equals(v)) {
				fail++;
			}
		}
		String body = String.format("{\"reportCount\":%d,\"verdicts\":{\"PASS\":%d,\"FLAG\":%d,\"FAIL\":%d}}",
			reports.length, pass, flag, fail);
		return ok(body);
	}

	// ── Shared helpers ──────────────────────────────────────────────────────────

	private Response findResponse(HttpServletRequest request, String model, String objectId, String notFoundMsg) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return unauthorized();
		}
		BaseRecord rec = ISO42001ServiceFacade.findByObjectId(user, model, objectId);
		if (rec == null) {
			return notFound(notFoundMsg);
		}
		return ok(rec.toFullString());
	}

	private static List<String> stringList(JsonNode node, String field) {
		List<String> out = new java.util.ArrayList<>();
		JsonNode arr = (node == null) ? null : node.get(field);
		if (arr != null && arr.isArray()) {
			arr.forEach(n -> {
				if (n != null && !n.isNull()) {
					out.add(n.asText());
				}
			});
		}
		return out;
	}
}
