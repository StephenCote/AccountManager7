package org.cote.rest.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
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
			logger.warn("Person lookup failed for pair endpoint (PBAC race or missing record): p1=" + (person1 != null) + " p2=" + (person2 != null));
			return Response.status(200).entity("[]").build();
		}
		long pId1 = person1.get(FieldNames.FIELD_ID);
		long pId2 = person2.get(FieldNames.FIELD_ID);
		List<BaseRecord> memories = MemoryUtil.searchMemoriesByPersonPair(user, pId1, pId2, limit);
		return Response.status(200).entity(serializeList(memories)).build();
	}

	/// ChatConfig-based pair memory endpoint. Resolves characters through the
	/// user-owned chatConfig foreign references.
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/chat/{chatConfigObjectId:[A-Fa-f0-9\\-]+}/{limit:\\d+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getMemoriesByChatConfig(@PathParam("chatConfigObjectId") String chatConfigObjectId, @PathParam("limit") int limit, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = findByObjectId(user, "olio.llm.chatConfig", chatConfigObjectId);
		if (chatConfig == null) {
			return Response.status(404).entity("{\"error\":\"chatConfig not found\"}").build();
		}
		chatConfig = OlioUtil.getFullRecord(chatConfig);
		if (chatConfig == null) {
			return Response.status(404).entity("{\"error\":\"chatConfig could not be resolved\"}").build();
		}
		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");
		if (systemChar == null || userChar == null) {
			return Response.status(404).entity("{\"error\":\"chatConfig missing character references\"}").build();
		}
		long sysId = systemChar.get(FieldNames.FIELD_ID);
		long usrId = userChar.get(FieldNames.FIELD_ID);
		List<BaseRecord> memories = MemoryUtil.searchMemoriesByPersonPair(user, sysId, usrId, limit);
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
			logger.warn("Person lookup failed for count endpoint (PBAC race or missing record): p1=" + (person1 != null) + " p2=" + (person2 != null));
			return Response.status(200).entity(0).build();
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
		String conversationId = rec.get("conversationId");
		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(user, rec);
		if (deleted && conversationId != null && !conversationId.isEmpty()) {
			resetLastKeyframeAt(user, conversationId);
		}
		return Response.status(deleted ? 200 : 404).entity(deleted).build();
	}

	private void resetLastKeyframeAt(BaseRecord user, String chatConfigObjectId) {
		try {
			BaseRecord cfg = findByObjectId(user, "olio.llm.chatConfig", chatConfigObjectId);
			if (cfg != null) {
				cfg.setValue("lastKeyframeAt", 0);
				IOSystem.getActiveContext().getAccessPoint().update(user, cfg.copyRecord(new String[] {
					FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_ID, "lastKeyframeAt" }));
				logger.info("Reset lastKeyframeAt for chatConfig " + chatConfigObjectId);
			}
		} catch (Exception e) {
			logger.warn("Failed to reset lastKeyframeAt: " + e.getMessage());
		}
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/create")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response createMemory(String body, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		try {
			BaseRecord rec = RecordFactory.importRecord(ModelNames.MODEL_MEMORY, body);
			if (rec == null) {
				return Response.status(400).entity("{\"error\":\"Invalid JSON\"}").build();
			}
			String content = rec.get("content");
			String summary = rec.get("summary");
			String memoryTypeStr = rec.get("memoryType");
			int importance = 5;
			try { importance = rec.get("importance"); } catch (Exception e) { /* use default */ }
			String person1ObjectId = rec.get("person1ObjectId");
			String person2ObjectId = rec.get("person2ObjectId");
			String conversationId = rec.get("conversationId");

			if (content == null || content.trim().isEmpty()) {
				return Response.status(400).entity("{\"error\":\"content is required\"}").build();
			}
			if (summary == null || summary.trim().isEmpty()) {
				summary = content.length() > 60 ? content.substring(0, 60) + "..." : content;
			}

			MemoryTypeEnumType memType = MemoryTypeEnumType.NOTE;
			if (memoryTypeStr != null) {
				try { memType = MemoryTypeEnumType.valueOf(memoryTypeStr); } catch (Exception e) { /* default NOTE */ }
			}

			long pId1 = 0L;
			long pId2 = 0L;
			String personModel = null;
			if (person1ObjectId != null && person2ObjectId != null) {
				BaseRecord person1 = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, person1ObjectId);
				BaseRecord person2 = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, person2ObjectId);
				if (person1 != null && person2 != null) {
					pId1 = person1.get(FieldNames.FIELD_ID);
					pId2 = person2.get(FieldNames.FIELD_ID);
					personModel = OlioModelNames.MODEL_CHAR_PERSON;
				}
			}

			BaseRecord memory = MemoryUtil.createMemory(user, content, summary, memType, importance,
				"am7://manual", conversationId, pId1, pId2, personModel);

			if (memory == null) {
				return Response.status(500).entity("{\"error\":\"Failed to create memory\"}").build();
			}
			return Response.status(200).entity(memory.toFullString()).build();
		} catch (Exception e) {
			logger.error("Failed to create memory", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Force-run memory extraction on an existing conversation without sending a new message.
	/// Use case: testing prompt changes for memory analysis without continuing the conversation.
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/extract/{chatRequestObjectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response forceExtractMemories(@PathParam("chatRequestObjectId") String chatRequestObjectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		/// Load the ChatRequest by objectId (same pattern as ChatService)
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatRequestObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		ChatRequest chatReq = new ChatRequest(IOSystem.getActiveContext().getAccessPoint().find(user, q));
		if (chatReq == null || chatReq.getChatConfig() == null) {
			return Response.status(404).entity("{\"error\":\"ChatRequest not found\"}").build();
		}

		/// Load the session (conversation messages)
		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(user, chatReq);
		if (oreq == null || oreq.getMessages() == null || oreq.getMessages().isEmpty()) {
			return Response.status(400).entity("{\"error\":\"No conversation session found\"}").build();
		}

		/// Create a Chat instance from the ChatRequest's config
		Chat chat = ChatUtil.getChat(user, chatReq, false);
		if (chat == null) {
			return Response.status(500).entity("{\"error\":\"Failed to initialize Chat\"}").build();
		}

		try {
			List<BaseRecord> memories = chat.forceExtractMemories(oreq);
			return Response.status(200).entity(serializeList(memories)).build();
		} catch (Exception e) {
			logger.error("Force extract failed", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	private BaseRecord findByObjectId(BaseRecord user, String modelName, String objectId) {
		try {
			Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(false);
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
