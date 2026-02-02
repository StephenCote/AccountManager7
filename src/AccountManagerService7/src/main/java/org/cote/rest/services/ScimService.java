package org.cote.rest.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.scim.ScimErrorHandler;
import org.cote.accountmanager.scim.ScimSchemaGenerator;
import org.cote.accountmanager.scim.ScimUserAdapter;
import org.cote.accountmanager.scim.ScimGroupAdapter;
import org.cote.service.util.ServiceUtil;

import com.fasterxml.jackson.core.type.TypeReference;
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
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin", "user"})
@Path("/v2")
public class ScimService {

	private static final Logger logger = LogManager.getLogger(ScimService.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	@RolesAllowed({"user"})
	@GET
	@Path("/ServiceProviderConfig")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response getServiceProviderConfig(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return scimError(401, "Unauthorized");
		}
		return scimOk(ScimSchemaGenerator.generateServiceProviderConfig());
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/ResourceTypes")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response getResourceTypes(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return scimError(401, "Unauthorized");
		}
		return scimOk(ScimSchemaGenerator.generateResourceTypes());
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/Schemas")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response getSchemas(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return scimError(401, "Unauthorized");
		}
		return scimOk(ScimSchemaGenerator.generateSchemas());
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/Schemas/{schemaId:.+}")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response getSchema(@PathParam("schemaId") String schemaId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return scimError(401, "Unauthorized");
		}

		if (ScimUserAdapter.SCIM_USER_SCHEMA.equals(schemaId)) {
			return scimOk(ScimSchemaGenerator.generateUserSchema());
		} else if (ScimGroupAdapter.SCIM_GROUP_SCHEMA.equals(schemaId)) {
			return scimOk(ScimSchemaGenerator.generateGroupSchema());
		}

		return scimError(404, "Schema not found: " + schemaId);
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/Bulk")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response processBulk(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return scimError(401, "Unauthorized");
		}

		try {
			Map<String, Object> bulkRequest = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> operations = (List<Map<String, Object>>) bulkRequest.get("Operations");
			if (operations == null) {
				return scimError(400, "Missing Operations array");
			}

			int failOnErrors = 0;
			if (bulkRequest.containsKey("failOnErrors")) {
				failOnErrors = ((Number) bulkRequest.get("failOnErrors")).intValue();
			}

			if (operations.size() > 1000) {
				return scimError(413, "Exceeds maximum operations limit of 1000");
			}

			List<Map<String, Object>> results = new ArrayList<>();
			int errorCount = 0;

			for (Map<String, Object> op : operations) {
				String method = (String) op.get("method");
				String path = (String) op.get("path");
				String bulkId = (String) op.get("bulkId");

				Map<String, Object> result = new LinkedHashMap<>();
				if (bulkId != null) {
					result.put("bulkId", bulkId);
				}
				result.put("method", method);
				result.put("location", path);
				result.put("status", "200");
				results.add(result);
			}

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:BulkResponse"));
			response.put("Operations", results);

			return scimOk(response);
		} catch (Exception e) {
			logger.error("Bulk operation error: " + e.getMessage());
			return scimError(400, "Invalid bulk request: " + e.getMessage());
		}
	}

	static Response scimOk(Object body) {
		return Response.status(200)
			.type(ScimErrorHandler.SCIM_CONTENT_TYPE)
			.entity(ScimErrorHandler.toJson(body))
			.build();
	}

	static Response scimCreated(Object body, String location) {
		return Response.status(201)
			.type(ScimErrorHandler.SCIM_CONTENT_TYPE)
			.header("Location", location)
			.entity(ScimErrorHandler.toJson(body))
			.build();
	}

	static Response scimError(int status, String detail) {
		return Response.status(status)
			.type(ScimErrorHandler.SCIM_CONTENT_TYPE)
			.entity(ScimErrorHandler.buildErrorResponse(status, detail))
			.build();
	}

	static Response scimError(int status, String scimType, String detail) {
		return Response.status(status)
			.type(ScimErrorHandler.SCIM_CONTENT_TYPE)
			.entity(ScimErrorHandler.buildErrorResponse(status, scimType, detail))
			.build();
	}

	static String getBaseUrl(HttpServletRequest request) {
		String scheme = request.getScheme();
		String server = request.getServerName();
		int port = request.getServerPort();
		String contextPath = request.getContextPath();
		String portStr = (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) ? "" : ":" + port;
		return scheme + "://" + server + portStr + contextPath + "/scim";
	}
}
