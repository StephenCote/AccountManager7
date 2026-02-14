package org.cote.rest.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.MemoryUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/memory")
public class MemoryService {

	private static final Logger logger = LogManager.getLogger(MemoryService.class);

	@Context
	ServletContext context;

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/conversation/{configObjectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getConversationMemories(@PathParam("configObjectId") String configObjectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		List<BaseRecord> memories = MemoryUtil.getConversationMemories(user, configObjectId);
		return Response.status(200).entity(serializeList(memories)).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/person/{personObjectId:[A-Fa-f0-9\\-]+}/{limit:\\d+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getMemoriesByPerson(@PathParam("personObjectId") String personObjectId, @PathParam("limit") int limit, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, personObjectId);
		if (person == null) {
			return Response.status(404).entity(null).build();
		}
		long personId = person.get(FieldNames.FIELD_ID);
		List<BaseRecord> memories = MemoryUtil.searchMemoriesByPerson(user, personId, limit);
		return Response.status(200).entity(serializeList(memories)).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/pair/{person1ObjectId:[A-Fa-f0-9\\-]+}/{person2ObjectId:[A-Fa-f0-9\\-]+}/{limit:\\d+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getMemoriesByPersonPair(@PathParam("person1ObjectId") String person1ObjectId, @PathParam("person2ObjectId") String person2ObjectId, @PathParam("limit") int limit, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person1 = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, person1ObjectId);
		BaseRecord person2 = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, person2ObjectId);
		if (person1 == null || person2 == null) {
			return Response.status(404).entity(null).build();
		}
		long pId1 = person1.get(FieldNames.FIELD_ID);
		long pId2 = person2.get(FieldNames.FIELD_ID);
		List<BaseRecord> memories = MemoryUtil.searchMemoriesByPersonPair(user, pId1, pId2, limit);
		return Response.status(200).entity(serializeList(memories)).build();
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/search/{limit:\\d+}/{threshold}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.TEXT_PLAIN)
	public Response searchMemories(@PathParam("limit") int limit, @PathParam("threshold") double threshold, String query, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (query == null || query.trim().isEmpty()) {
			return Response.status(404).entity(null).build();
		}
		List<BaseRecord> memories = MemoryUtil.searchMemories(user, query, limit, threshold);
		return Response.status(200).entity(serializeList(memories)).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/count/{person1ObjectId:[A-Fa-f0-9\\-]+}/{person2ObjectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getMemoryCount(@PathParam("person1ObjectId") String person1ObjectId, @PathParam("person2ObjectId") String person2ObjectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord person1 = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, person1ObjectId);
		BaseRecord person2 = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, person2ObjectId);
		if (person1 == null || person2 == null) {
			return Response.status(404).entity(null).build();
		}
		long pId1 = person1.get(FieldNames.FIELD_ID);
		long pId2 = person2.get(FieldNames.FIELD_ID);
		List<BaseRecord> memories = MemoryUtil.searchMemoriesByPersonPair(user, pId1, pId2, Integer.MAX_VALUE);
		return Response.status(200).entity(memories.size()).build();
	}

	@RolesAllowed({"admin","user"})
	@DELETE
	@Path("/{objectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteMemory(@PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord rec = findByObjectId(user, ModelNames.MODEL_MEMORY, objectId);
		if (rec == null) {
			return Response.status(404).entity(false).build();
		}
		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(user, rec);
		return Response.status(deleted ? 200 : 404).entity(deleted).build();
	}

	private BaseRecord findByObjectId(BaseRecord user, String modelName, String objectId) {
		try {
			Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			return IOSystem.getActiveContext().getAccessPoint().find(user, q);
		}
		catch (Exception e) {
			logger.warn("Failed to find " + modelName + " by objectId: " + objectId, e);
			return null;
		}
	}

	private String serializeList(List<BaseRecord> list) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(list.get(i).toFullString());
		}
		sb.append("]");
		return sb.toString();
	}
}
