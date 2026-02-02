package org.cote.rest.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.scim.ScimErrorHandler;
import org.cote.accountmanager.scim.ScimFilterParser;
import org.cote.accountmanager.scim.ScimGroupAdapter;
import org.cote.accountmanager.scim.ScimPatchHandler;
import org.cote.service.util.ServiceUtil;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin", "user"})
@Path("/v2/Groups")
public class ScimGroupService {

	private static final Logger logger = LogManager.getLogger(ScimGroupService.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	@RolesAllowed({"user"})
	@GET
	@Path("/{id}")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response getGroup(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, id);
		q.planMost(true);
		BaseRecord group = IOSystem.getActiveContext().getAccessPoint().find(contextUser, q);
		if (group == null) {
			return ScimService.scimError(404, "Group not found: " + id);
		}

		String baseUrl = ScimService.getBaseUrl(request);
		return ScimService.scimOk(ScimGroupAdapter.toScim(group, contextUser, baseUrl));
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response listGroups(
		@QueryParam("filter") String filter,
		@QueryParam("startIndex") @DefaultValue("1") int startIndex,
		@QueryParam("count") @DefaultValue("100") int count,
		@QueryParam("sortBy") String sortBy,
		@QueryParam("sortOrder") @DefaultValue("ascending") String sortOrder,
		@Context HttpServletRequest request
	) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		if (count > 200) count = 200;
		if (count < 1) count = 1;
		if (startIndex < 1) startIndex = 1;

		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP);
			q.planMost(true);

			if (filter != null && !filter.isBlank()) {
				ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_GROUP);
				parser.applyFilter(filter, q);
			}

			long amStartIndex = startIndex - 1;
			q.setRequestRange(amStartIndex, count);

			try {
				if (sortBy != null) {
					String mappedField = new ScimFilterParser(ModelNames.MODEL_GROUP).mapScimAttribute(sortBy);
					q.set(FieldNames.FIELD_SORT_FIELD, mappedField);
				} else {
					q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
				}
				q.set(FieldNames.FIELD_ORDER, "descending".equalsIgnoreCase(sortOrder) ? OrderEnumType.DESCENDING : OrderEnumType.ASCENDING);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("Sort configuration error: " + e.getMessage());
			}

			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(contextUser, q);

			String baseUrl = ScimService.getBaseUrl(request);
			List<Map<String, Object>> resources = new ArrayList<>();
			int totalResults = 0;
			if (qr != null) {
				totalResults = (int) qr.get(FieldNames.FIELD_TOTAL_COUNT);
				for (BaseRecord rec : qr.getResults()) {
					resources.add(ScimGroupAdapter.toScim(rec, contextUser, baseUrl));
				}
			}

			Map<String, Object> response = new LinkedHashMap<>();
			response.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
			response.put("totalResults", totalResults);
			response.put("startIndex", startIndex);
			response.put("itemsPerPage", resources.size());
			response.put("Resources", resources);

			return ScimService.scimOk(response);
		} catch (Exception e) {
			logger.error("Error listing groups: " + e.getMessage());
			return ScimService.scimError(500, "Internal error listing groups");
		}
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response createGroup(String json, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		try {
			Map<String, Object> scimGroup = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
			String displayName = (String) scimGroup.get("displayName");
			if (displayName == null || displayName.isBlank()) {
				return ScimService.scimError(400, "displayName is required");
			}

			BaseRecord newGroup = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_GROUP, contextUser, null, null
			);
			newGroup.set(FieldNames.FIELD_NAME, displayName);
			newGroup.set(FieldNames.FIELD_TYPE, "DATA");

			String externalId = (String) scimGroup.get("externalId");
			if (externalId != null) {
				newGroup.set(FieldNames.FIELD_URN, externalId);
			}

			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(contextUser, newGroup);
			if (created == null) {
				return ScimService.scimError(409, "uniqueness", "Group already exists or could not be created");
			}

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> members = (List<Map<String, Object>>) scimGroup.get("members");
			if (members != null) {
				addMembers(contextUser, created, members);
			}

			String baseUrl = ScimService.getBaseUrl(request);
			Query fullQ = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, (String) created.get(FieldNames.FIELD_OBJECT_ID));
			fullQ.planMost(true);
			BaseRecord fullGroup = IOSystem.getActiveContext().getAccessPoint().find(contextUser, fullQ);

			Map<String, Object> response = ScimGroupAdapter.toScim(fullGroup != null ? fullGroup : created, contextUser, baseUrl);
			String location = baseUrl + "/v2/Groups/" + created.get(FieldNames.FIELD_OBJECT_ID);
			return ScimService.scimCreated(response, location);

		} catch (Exception e) {
			logger.error("Error creating group: " + e.getMessage());
			return ScimService.scimError(400, "Invalid group data: " + e.getMessage());
		}
	}

	@RolesAllowed({"user"})
	@PUT
	@Path("/{id}")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response replaceGroup(@PathParam("id") String id, String json, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, ModelNames.MODEL_GROUP, id);
		if (existing == null) {
			return ScimService.scimError(404, "Group not found: " + id);
		}

		try {
			Map<String, Object> scimGroup = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

			String displayName = (String) scimGroup.get("displayName");
			if (displayName != null) {
				existing.set(FieldNames.FIELD_NAME, displayName);
			}

			String externalId = (String) scimGroup.get("externalId");
			if (externalId != null) {
				existing.set(FieldNames.FIELD_URN, externalId);
			}

			IOSystem.getActiveContext().getAccessPoint().update(contextUser, existing);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> members = (List<Map<String, Object>>) scimGroup.get("members");
			if (members != null) {
				addMembers(contextUser, existing, members);
			}

			String baseUrl = ScimService.getBaseUrl(request);
			Query fullQ = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, id);
			fullQ.planMost(true);
			BaseRecord fullGroup = IOSystem.getActiveContext().getAccessPoint().find(contextUser, fullQ);

			return ScimService.scimOk(ScimGroupAdapter.toScim(fullGroup != null ? fullGroup : existing, contextUser, baseUrl));

		} catch (Exception e) {
			logger.error("Error replacing group: " + e.getMessage());
			return ScimService.scimError(400, "Invalid group data: " + e.getMessage());
		}
	}

	@RolesAllowed({"user"})
	@PATCH
	@Path("/{id}")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response updateGroup(@PathParam("id") String id, String json, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, ModelNames.MODEL_GROUP, id);
		if (existing == null) {
			return ScimService.scimError(404, "Group not found: " + id);
		}

		try {
			Map<String, Object> patchRequest = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> operations = (List<Map<String, Object>>) patchRequest.get("Operations");
			if (operations == null) {
				return ScimService.scimError(400, "Missing Operations array");
			}

			for (Map<String, Object> op : operations) {
				String operation = ((String) op.get("op")).toLowerCase();
				String path = (String) op.get("path");
				Object value = op.get("value");

				if ("displayName".equals(path) && ("add".equals(operation) || "replace".equals(operation))) {
					existing.set(FieldNames.FIELD_NAME, value);
					IOSystem.getActiveContext().getAccessPoint().update(contextUser, existing);
				} else if ("members".equals(path)) {
					handleMemberOperation(contextUser, existing, operation, value);
				}
			}

			String baseUrl = ScimService.getBaseUrl(request);
			Query fullQ = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, id);
			fullQ.planMost(true);
			BaseRecord fullGroup = IOSystem.getActiveContext().getAccessPoint().find(contextUser, fullQ);

			return ScimService.scimOk(ScimGroupAdapter.toScim(fullGroup != null ? fullGroup : existing, contextUser, baseUrl));

		} catch (Exception e) {
			logger.error("Error patching group: " + e.getMessage());
			return ScimService.scimError(400, "Invalid patch data: " + e.getMessage());
		}
	}

	@RolesAllowed({"user"})
	@DELETE
	@Path("/{id}")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response deleteGroup(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, ModelNames.MODEL_GROUP, id);
		if (existing == null) {
			return ScimService.scimError(404, "Group not found: " + id);
		}

		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(contextUser, existing);
		if (!deleted) {
			return ScimService.scimError(500, "Failed to delete group");
		}

		return Response.status(204).build();
	}

	private void addMembers(BaseRecord contextUser, BaseRecord group, List<Map<String, Object>> members) {
		for (Map<String, Object> member : members) {
			String memberId = (String) member.get("value");
			String memberType = (String) member.get("type");
			if (memberId == null) continue;

			String model = "Group".equals(memberType) ? ModelNames.MODEL_GROUP : ModelNames.MODEL_USER;
			BaseRecord memberRec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, model, memberId);
			if (memberRec != null) {
				IOSystem.getActiveContext().getAccessPoint().member(contextUser, group, memberRec, null, true);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void handleMemberOperation(BaseRecord contextUser, BaseRecord group, String operation, Object value) {
		if (value instanceof List) {
			List<Map<String, Object>> members = (List<Map<String, Object>>) value;
			if ("add".equals(operation)) {
				addMembers(contextUser, group, members);
			} else if ("remove".equals(operation)) {
				for (Map<String, Object> member : members) {
					String memberId = (String) member.get("value");
					String memberType = (String) member.get("type");
					if (memberId == null) continue;
					String model = "Group".equals(memberType) ? ModelNames.MODEL_GROUP : ModelNames.MODEL_USER;
					BaseRecord memberRec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, model, memberId);
					if (memberRec != null) {
						IOSystem.getActiveContext().getAccessPoint().member(contextUser, group, memberRec, null, false);
					}
				}
			}
		}
	}
}
