package org.cote.rest.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
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
import org.cote.accountmanager.scim.ScimPatchHandler;
import org.cote.accountmanager.scim.ScimUserAdapter;
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
@Path("/v2/Users")
public class ScimUserService {

	private static final Logger logger = LogManager.getLogger(ScimUserService.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	@RolesAllowed({"user"})
	@GET
	@Path("/{id}")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response getUser(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		Query q = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_OBJECT_ID, id);
		q.planMost(true);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if (rec == null) {
			return ScimService.scimError(404, "User not found: " + id);
		}

		BaseRecord person = ScimUserAdapter.findPersonForUser(user, rec);
		String baseUrl = ScimService.getBaseUrl(request);
		Map<String, Object> scimUser = ScimUserAdapter.toScim(rec, person, baseUrl);

		return ScimService.scimOk(scimUser);
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response listUsers(
		@QueryParam("filter") String filter,
		@QueryParam("startIndex") @DefaultValue("1") int startIndex,
		@QueryParam("count") @DefaultValue("100") int count,
		@QueryParam("sortBy") String sortBy,
		@QueryParam("sortOrder") @DefaultValue("ascending") String sortOrder,
		@Context HttpServletRequest request
	) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		if (count > 200) count = 200;
		if (count < 1) count = 1;
		if (startIndex < 1) startIndex = 1;

		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_USER);
			q.planMost(true);

			if (filter != null && !filter.isBlank()) {
				ScimFilterParser parser = new ScimFilterParser(ModelNames.MODEL_USER);
				parser.applyFilter(filter, q);
			}

			long amStartIndex = startIndex - 1;
			q.setRequestRange(amStartIndex, count);

			try {
				if (sortBy != null) {
					String mappedField = new ScimFilterParser(ModelNames.MODEL_USER).mapScimAttribute(sortBy);
					q.set(FieldNames.FIELD_SORT_FIELD, mappedField);
				} else {
					q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
				}
				q.set(FieldNames.FIELD_ORDER, "descending".equalsIgnoreCase(sortOrder) ? OrderEnumType.DESCENDING : OrderEnumType.ASCENDING);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("Sort configuration error: " + e.getMessage());
			}

			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);

			String baseUrl = ScimService.getBaseUrl(request);
			List<Map<String, Object>> resources = new ArrayList<>();
			int totalResults = 0;
			if (qr != null) {
				totalResults = (int) qr.get(FieldNames.FIELD_TOTAL_COUNT);
				for (BaseRecord rec : qr.getResults()) {
					BaseRecord person = ScimUserAdapter.findPersonForUser(user, rec);
					resources.add(ScimUserAdapter.toScim(rec, person, baseUrl));
				}
			}

			return ScimService.scimOk(buildListResponse(resources, totalResults, startIndex, count));
		} catch (Exception e) {
			logger.error("Error listing users: " + e.getMessage());
			return ScimService.scimError(500, "Internal error listing users");
		}
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response createUser(String json, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		try {
			Map<String, Object> scimUser = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
			String userName = (String) scimUser.get("userName");
			if (userName == null || userName.isBlank()) {
				return ScimService.scimError(400, "userName is required");
			}

			BaseRecord newUser = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_USER, contextUser, null, null
			);
			newUser.set(FieldNames.FIELD_NAME, userName);

			Boolean active = (Boolean) scimUser.get("active");
			if (active != null) {
				newUser.set(FieldNames.FIELD_STATUS, ScimUserAdapter.mapActiveToStatus(active));
			}

			String externalId = (String) scimUser.get("externalId");
			if (externalId != null) {
				newUser.set(FieldNames.FIELD_URN, externalId);
			}

			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(contextUser, newUser);
			if (created == null) {
				return ScimService.scimError(409, "uniqueness", "User already exists or could not be created");
			}

			BaseRecord person = createPersonFromScim(contextUser, created, scimUser);

			String baseUrl = ScimService.getBaseUrl(request);
			Query fullQ = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_OBJECT_ID, (String) created.get(FieldNames.FIELD_OBJECT_ID));
			fullQ.planMost(true);
			BaseRecord fullUser = IOSystem.getActiveContext().getAccessPoint().find(contextUser, fullQ);

			Map<String, Object> response = ScimUserAdapter.toScim(fullUser != null ? fullUser : created, person, baseUrl);
			String location = baseUrl + "/v2/Users/" + created.get(FieldNames.FIELD_OBJECT_ID);
			return ScimService.scimCreated(response, location);

		} catch (Exception e) {
			logger.error("Error creating user: " + e.getMessage());
			return ScimService.scimError(400, "Invalid user data: " + e.getMessage());
		}
	}

	@RolesAllowed({"user"})
	@PUT
	@Path("/{id}")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response replaceUser(@PathParam("id") String id, String json, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, ModelNames.MODEL_USER, id);
		if (existing == null) {
			return ScimService.scimError(404, "User not found: " + id);
		}

		try {
			Map<String, Object> scimUser = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

			String userName = (String) scimUser.get("userName");
			if (userName != null) {
				existing.set(FieldNames.FIELD_NAME, userName);
			}

			Boolean active = (Boolean) scimUser.get("active");
			if (active != null) {
				existing.set(FieldNames.FIELD_STATUS, ScimUserAdapter.mapActiveToStatus(active));
			}

			String externalId = (String) scimUser.get("externalId");
			if (externalId != null) {
				existing.set(FieldNames.FIELD_URN, externalId);
			}

			IOSystem.getActiveContext().getAccessPoint().update(contextUser, existing);

			BaseRecord person = ScimUserAdapter.findPersonForUser(contextUser, existing);
			@SuppressWarnings("unchecked")
			Map<String, Object> nameObj = (Map<String, Object>) scimUser.get("name");
			if (nameObj != null && person != null) {
				updatePersonFromName(person, nameObj);
				IOSystem.getActiveContext().getAccessPoint().update(contextUser, person);
			}

			String baseUrl = ScimService.getBaseUrl(request);
			Query fullQ = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_OBJECT_ID, id);
			fullQ.planMost(true);
			BaseRecord fullUser = IOSystem.getActiveContext().getAccessPoint().find(contextUser, fullQ);

			return ScimService.scimOk(ScimUserAdapter.toScim(fullUser != null ? fullUser : existing, person, baseUrl));

		} catch (Exception e) {
			logger.error("Error replacing user: " + e.getMessage());
			return ScimService.scimError(400, "Invalid user data: " + e.getMessage());
		}
	}

	@RolesAllowed({"user"})
	@PATCH
	@Path("/{id}")
	@Consumes(ScimErrorHandler.SCIM_CONTENT_TYPE)
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response updateUser(@PathParam("id") String id, String json, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, ModelNames.MODEL_USER, id);
		if (existing == null) {
			return ScimService.scimError(404, "User not found: " + id);
		}

		try {
			Map<String, Object> patchRequest = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> operations = (List<Map<String, Object>>) patchRequest.get("Operations");
			if (operations == null) {
				return ScimService.scimError(400, "Missing Operations array");
			}

			BaseRecord person = ScimUserAdapter.findPersonForUser(contextUser, existing);

			List<String> errors = ScimPatchHandler.applyPatch(existing, person, operations);
			if (!errors.isEmpty()) {
				return ScimService.scimError(400, String.join("; ", errors));
			}

			IOSystem.getActiveContext().getAccessPoint().update(contextUser, existing);
			if (person != null) {
				IOSystem.getActiveContext().getAccessPoint().update(contextUser, person);
			}

			String baseUrl = ScimService.getBaseUrl(request);
			Query fullQ = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_OBJECT_ID, id);
			fullQ.planMost(true);
			BaseRecord fullUser = IOSystem.getActiveContext().getAccessPoint().find(contextUser, fullQ);
			person = ScimUserAdapter.findPersonForUser(contextUser, fullUser != null ? fullUser : existing);

			return ScimService.scimOk(ScimUserAdapter.toScim(fullUser != null ? fullUser : existing, person, baseUrl));

		} catch (Exception e) {
			logger.error("Error patching user: " + e.getMessage());
			return ScimService.scimError(400, "Invalid patch data: " + e.getMessage());
		}
	}

	@RolesAllowed({"user"})
	@DELETE
	@Path("/{id}")
	@Produces(ScimErrorHandler.SCIM_CONTENT_TYPE)
	public Response deleteUser(@PathParam("id") String id, @Context HttpServletRequest request) {
		BaseRecord contextUser = ServiceUtil.getPrincipalUser(request);
		if (contextUser == null) {
			return ScimService.scimError(401, "Unauthorized");
		}

		BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, ModelNames.MODEL_USER, id);
		if (existing == null) {
			return ScimService.scimError(404, "User not found: " + id);
		}

		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(contextUser, existing);
		if (!deleted) {
			return ScimService.scimError(500, "Failed to delete user");
		}

		return Response.status(204).build();
	}

	private BaseRecord createPersonFromScim(BaseRecord contextUser, BaseRecord createdUser, Map<String, Object> scimUser) {
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> nameObj = (Map<String, Object>) scimUser.get("name");
			if (nameObj == null) return null;

			BaseRecord person = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_PERSON, contextUser, null, null
			);

			String firstName = (String) nameObj.get("givenName");
			String lastName = (String) nameObj.get("familyName");
			String displayName = firstName != null ? firstName : "";
			if (lastName != null) displayName += (displayName.isEmpty() ? "" : " ") + lastName;
			if (displayName.isEmpty()) displayName = (String) createdUser.get(FieldNames.FIELD_NAME);

			person.set(FieldNames.FIELD_NAME, displayName);
			if (firstName != null) person.set(FieldNames.FIELD_FIRST_NAME, firstName);
			if (nameObj.containsKey("middleName")) person.set(FieldNames.FIELD_MIDDLE_NAME, nameObj.get("middleName"));
			if (lastName != null) person.set(FieldNames.FIELD_LAST_NAME, lastName);

			BaseRecord createdPerson = IOSystem.getActiveContext().getAccessPoint().create(contextUser, person);

			if (createdPerson != null) {
				IOSystem.getActiveContext().getAccessPoint().member(contextUser, createdPerson, "users", createdUser, null, true);
			}

			return createdPerson;
		} catch (Exception e) {
			logger.warn("Error creating person record: " + e.getMessage());
			return null;
		}
	}

	private void updatePersonFromName(BaseRecord person, Map<String, Object> nameObj) throws FieldException, ValueException, ModelNotFoundException {
		if (nameObj.containsKey("givenName")) {
			person.set(FieldNames.FIELD_FIRST_NAME, nameObj.get("givenName"));
		}
		if (nameObj.containsKey("middleName")) {
			person.set(FieldNames.FIELD_MIDDLE_NAME, nameObj.get("middleName"));
		}
		if (nameObj.containsKey("familyName")) {
			person.set(FieldNames.FIELD_LAST_NAME, nameObj.get("familyName"));
		}
	}

	private Map<String, Object> buildListResponse(List<Map<String, Object>> resources, int totalResults, int startIndex, int count) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
		response.put("totalResults", totalResults);
		response.put("startIndex", startIndex);
		response.put("itemsPerPage", resources.size());
		response.put("Resources", resources);
		return response;
	}
}
