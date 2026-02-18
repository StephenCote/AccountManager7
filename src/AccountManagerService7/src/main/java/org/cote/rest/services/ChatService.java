package org.cote.rest.services;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.PromptBuilderContext;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.llm.TemplatePatternEnumType;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;
import org.cote.sockets.WebSocketService;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/chat")
public class ChatService {
	
	private static final Logger logger = LogManager.getLogger(ChatService.class);
	
	@Context
	ServletContext context;
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/clearAll")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response clearAll(@Context HttpServletRequest request){
		ChatUtil.clearCache();
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/clear")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response clear(String json, @Context HttpServletRequest request){
		logger.info("Clear ....");
		ChatRequest chatReq = ChatRequest.importRecord(json);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return Response.status(404).entity(null).build();
		}
		if(ChatUtil.getChatTrack().contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.getChatConfig());
		BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.getPromptConfig());
		if(chatConfig != null && promptConfig != null) {
				logger.warn("Deprecate - Nothing to do");
		}
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/history")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chatHistory(String json, @Context HttpServletRequest request){
		logger.info("History .... ");
		ChatRequest chatReq = ChatRequest.importRecord(json);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			logger.warn(JSONUtil.exportObject(chatReq));
			return Response.status(404).entity(null).build();
		}
		if(ChatUtil.getChatTrack().contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		ChatResponse crep = null;
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		//ChatRequest vChatReq = new ChatRequest(IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, chatReq.getSchema(), chatReq.get(FieldNames.FIELD_OBJECT_ID)));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatReq.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		ChatRequest vChatReq = new ChatRequest(IOSystem.getActiveContext().getAccessPoint().find(user, q));
		BaseRecord vreq = OlioUtil.getFullRecord(vChatReq.get("session"), false);
		if(vreq != null) {
			OpenAIRequest oreq = new OpenAIRequest(vreq);
			crep = ChatUtil.getChatResponse(user, oreq, vChatReq);
		}
		else {
			logger.error("Invalid chat or prompt configuration");
		}
		
		return Response.status((crep != null ? 200 : 404)).entity(crep != null ? crep.toFullString() : null).build();
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/prompt")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getSystemPrompt(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		return Response.status(200).entity(ChatUtil.getDefaultPrompt().toFullString()).build();
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/prompt/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getPromptConfig(@PathParam(FieldNames.FIELD_NAME) String name, @QueryParam("group") String group, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String groupPath = (group != null && !group.isEmpty()) ? group : null;
		BaseRecord cfg = ChatUtil.getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, null, name, groupPath);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}



	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/chat/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getChatConfig(@PathParam(FieldNames.FIELD_NAME) String name, @QueryParam("group") String group, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String groupPath = (group != null && !group.isEmpty()) ? group : null;
		BaseRecord cfg = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, name, groupPath);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/prompt/id/{objectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPromptConfigById(@PathParam("objectId") String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = ChatUtil.getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, objectId, null);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/chat/id/{objectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getChatConfigById(@PathParam("objectId") String objectId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, objectId, null);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/new")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response newChat(String json, @Context HttpServletRequest request){
		ChatRequest chatReq = ChatRequest.importRecord(json);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String name = chatReq.get(FieldNames.FIELD_NAME);
		String chatConfigId = null;
		String promptConfigId = null;

		if (chatReq.getChatConfig() != null) {
			chatConfigId = chatReq.get("chatConfig.objectId");
		}

		if (chatReq.getPromptConfig() != null) {
			promptConfigId = chatReq.get("promptConfig.objectId");
		}

		if(promptConfigId == null || promptConfigId.length() == 0|| chatConfigId == null || chatConfigId.length() == 0 || name == null || name.length() == 0) {
			logger.warn("Name, prompt, or chat config was null or empty");
			return Response.status(404).entity(null).build();
		}

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, chatConfigId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord chatConfig = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if (chatConfig == null) {
			logger.warn("Chat config not found");
			return Response.status(404).entity(null).build();
		}
		
		Query q2 = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_OBJECT_ID, promptConfigId);
		q2.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord promptConfig = IOSystem.getActiveContext().getAccessPoint().find(user, q2);
		
		if (promptConfig == null) {
			logger.warn("Prompt config not found");
			return Response.status(404).entity(null).build();
		}
		
		BaseRecord req = ChatUtil.getChatRequest(user, name, chatConfig, promptConfig);
		if(req != null) {
			logger.warn("Chat request '" + name + "' already exists");
			return Response.status(404).entity(null).build();
		}
		
		req = ChatUtil.getCreateChatRequest(user, name, chatConfig, promptConfig);
		if(req != null) {
			/// Fetch again since the create will only return the identifiers. 
			req = ChatUtil.getChatRequest(user, name, chatConfig, promptConfig);
		}
		if(req != null) {
			logger.info("Created new chat request: " + name);
		}
		else {
			logger.error("Failed to create chat request: " + name);
		}
		return Response.status((req != null ? 200 : 404)).entity(req.toFullString()).build();
		
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/text")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chat(String json, @Context HttpServletRequest request){
		ChatRequest chatReq = ChatRequest.importRecord(json);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return Response.status(404).entity(null).build();
		}
		if(ChatUtil.getChatTrack().contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		ChatUtil.getChatTrack().add(chatReq.getUid());

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatReq.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		ChatRequest vChatReq = new ChatRequest(IOSystem.getActiveContext().getAccessPoint().find(user, q));

		vChatReq.setValue(FieldNames.FIELD_DATA, chatReq.get(FieldNames.FIELD_DATA));
		vChatReq.setValue(FieldNames.FIELD_MESSAGE, chatReq.get(FieldNames.FIELD_MESSAGE));
		
		OpenAIRequest req = ChatUtil.getOpenAIRequest(user, vChatReq);

		String citRef = "";
		if(vChatReq.getMessage() != null && vChatReq.getMessage().length() > 0) {
			List<String> cits = ChatUtil.getDataCitations(user, req, vChatReq);
			if(cits.size() > 0) {
				citRef = System.lineSeparator() + cits.stream().collect(Collectors.joining(System.lineSeparator()));
			}
		}
		else {
			logger.warn("Chat message is null for " + vChatReq.get(FieldNames.FIELD_NAME));
		}
		
		Chat chat = ChatUtil.getChat(user, vChatReq, Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));
		
		String citDesc = "";
		if(citRef.length() > 0) {
			citDesc = PromptUtil.getUserCitationTemplate(chat.getPromptConfig(), chat.getChatConfig());
			if(citDesc == null || citDesc.length() == 0) {
				/// MCP blocks are self-describing; no wrapper needed
				citDesc = citRef + System.lineSeparator();
			}
			else {
			    PromptBuilderContext ctx = new PromptBuilderContext(chat.getPromptConfig(), chat.getChatConfig(), citDesc, true);
			    ctx.replace(TemplatePatternEnumType.USER_QUESTION, vChatReq.getMessage());
			    ctx.replace(TemplatePatternEnumType.USER_CITATION, citRef);
			    citDesc = ctx.template.trim();
			}

		}
		chat.continueChat(req, citDesc + vChatReq.getMessage());

		ChatResponse creq = ChatUtil.getChatResponse(user, req, vChatReq);

		return Response.status((creq != null ? 200 : 404)).entity(creq.toFullString()).build();
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/context/attach")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response attachContext(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord body = JSONUtil.importObject(json, org.cote.accountmanager.record.LooseRecord.class, org.cote.accountmanager.record.RecordDeserializerConfig.getUnfilteredModule());
		if (body == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String sessionId = body.get("sessionId");
		String attachType = body.get("attachType");
		String objectId = body.get("objectId");
		String objectType = body.get("objectType");

		if (sessionId == null || attachType == null || objectId == null) {
			return Response.status(400).entity("{\"error\":\"sessionId, attachType, and objectId are required\"}").build();
		}

		try {
			Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, sessionId);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			sq.planMost(false);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return Response.status(404).entity("{\"error\":\"Session not found\"}").build();
			}

			switch (attachType) {
				case "chatConfig": {
					BaseRecord cfg = findByObjectId(user, OlioModelNames.MODEL_CHAT_CONFIG, objectId);
					if (cfg == null) return Response.status(404).entity("{\"error\":\"Chat config not found\"}").build();
					chatReq.set("chatConfig", cfg);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					break;
				}
				case "promptConfig": {
					BaseRecord cfg = findByObjectId(user, OlioModelNames.MODEL_PROMPT_CONFIG, objectId);
					if (cfg == null) return Response.status(404).entity("{\"error\":\"Prompt config not found\"}").build();
					chatReq.set("promptConfig", cfg);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					break;
				}
				case "systemCharacter":
				case "userCharacter": {
					BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
					if (chatConfig == null) return Response.status(404).entity("{\"error\":\"Session has no chatConfig\"}").build();
					BaseRecord character = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, objectId);
					if (character == null) return Response.status(404).entity("{\"error\":\"Character not found\"}").build();
					chatConfig.set(attachType, character);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig);
					break;
				}
				case "context": {
					if (objectType == null || objectType.isEmpty()) {
						return Response.status(400).entity("{\"error\":\"objectType required for context attach\"}").build();
					}
					BaseRecord contextObj = findByObjectId(user, objectType, objectId);
					if (contextObj == null) return Response.status(404).entity("{\"error\":\"Context object not found\"}").build();
					chatReq.set("contextType", objectType);
					chatReq.set("context", contextObj);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					break;
				}
				default:
					return Response.status(400).entity("{\"error\":\"Invalid attachType: " + attachType + "\"}").build();
			}

			return Response.status(200).entity("{\"attached\":true,\"attachType\":\"" + attachType + "\"}").build();
		}
		catch (Exception e) {
			logger.error("Error attaching context", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/context/detach")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response detachContext(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord body = JSONUtil.importObject(json, org.cote.accountmanager.record.LooseRecord.class, org.cote.accountmanager.record.RecordDeserializerConfig.getUnfilteredModule());
		if (body == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

		String sessionId = body.get("sessionId");
		String detachType = body.get("detachType");

		if (sessionId == null || detachType == null) {
			return Response.status(400).entity("{\"error\":\"sessionId and detachType are required\"}").build();
		}

		try {
			Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, sessionId);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			sq.planMost(false);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return Response.status(404).entity("{\"error\":\"Session not found\"}").build();
			}

			switch (detachType) {
				case "systemCharacter":
				case "userCharacter": {
					BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
					if (chatConfig == null) return Response.status(404).entity("{\"error\":\"Session has no chatConfig\"}").build();
					chatConfig.set(detachType, null);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig);
					break;
				}
				case "context": {
					chatReq.set("contextType", null);
					chatReq.set("context", null);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					break;
				}
				default:
					return Response.status(400).entity("{\"error\":\"Cannot detach " + detachType + "\"}").build();
			}

			return Response.status(200).entity("{\"detached\":true,\"detachType\":\"" + detachType + "\"}").build();
		}
		catch (Exception e) {
			logger.error("Error detaching context", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/context/{objectId:[A-Fa-f0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSessionContext(@PathParam("objectId") String sessionId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		try {
			Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, sessionId);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			/// No recursion — sub-records are loaded individually via getFullRecord below
			sq.planMost(false);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return Response.status(200).entity("{}").build();
			}

			/// Build a summary of all context bindings using comma-safe approach
			StringBuilder sb = new StringBuilder();
			sb.append("{");
			boolean hasField = false;

			BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
			if (chatConfig != null) {
				if (hasField) sb.append(",");
				sb.append("\"chatConfig\":{\"name\":\"").append(escJson((String)chatConfig.get(FieldNames.FIELD_NAME)));
				sb.append("\",\"objectId\":\"").append((String)chatConfig.get(FieldNames.FIELD_OBJECT_ID));
				sb.append("\",\"model\":\"").append(escJson((String)chatConfig.get("model"))).append("\"}");
				hasField = true;

				BaseRecord sysCh = OlioUtil.getFullRecord(chatConfig.get("systemCharacter"));
				if (sysCh != null) {
					sb.append(",\"systemCharacter\":{\"name\":\"").append(escJson((String)sysCh.get(FieldNames.FIELD_NAME)));
					sb.append("\",\"objectId\":\"").append((String)sysCh.get(FieldNames.FIELD_OBJECT_ID)).append("\"}");
				}
				BaseRecord usrCh = OlioUtil.getFullRecord(chatConfig.get("userCharacter"));
				if (usrCh != null) {
					sb.append(",\"userCharacter\":{\"name\":\"").append(escJson((String)usrCh.get(FieldNames.FIELD_NAME)));
					sb.append("\",\"objectId\":\"").append((String)usrCh.get(FieldNames.FIELD_OBJECT_ID)).append("\"}");
				}
			}

			BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.get("promptConfig"));
			if (promptConfig != null) {
				if (hasField) sb.append(",");
				sb.append("\"promptConfig\":{\"name\":\"").append(escJson((String)promptConfig.get(FieldNames.FIELD_NAME)));
				sb.append("\",\"objectId\":\"").append((String)promptConfig.get(FieldNames.FIELD_OBJECT_ID)).append("\"}");
				hasField = true;
			}

			String contextType = chatReq.get("contextType");
			if (contextType != null && !contextType.isEmpty()) {
				if (hasField) sb.append(",");
				BaseRecord contextObj = OlioUtil.getFullRecord(chatReq.get("context"));
				sb.append("\"context\":{\"type\":\"").append(contextType).append("\"");
				if (contextObj != null) {
					sb.append(",\"name\":\"").append(escJson((String)contextObj.get(FieldNames.FIELD_NAME)));
					sb.append("\",\"objectId\":\"").append((String)contextObj.get(FieldNames.FIELD_OBJECT_ID)).append("\"");
				}
				sb.append("}");
				hasField = true;
			}

			sb.append("}");
			return Response.status(200).entity(sb.toString()).build();
		}
		catch (Exception e) {
			logger.error("Error reading session context", e);
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

	private static String escJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/chain")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chain(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chainReq = JSONUtil.importObject(json, org.cote.accountmanager.record.LooseRecord.class, org.cote.accountmanager.record.RecordDeserializerConfig.getUnfilteredModule());
		if (chainReq == null) {
			return Response.status(400).entity("{\"error\":\"Invalid chain request\"}").build();
		}

		String planQuery = chainReq.get("planQuery");
		if (planQuery == null || planQuery.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"planQuery is required\"}").build();
		}

		logger.info("Synchronous chain execution for user " + user.get("name") + ": " + planQuery);

		// Synchronous chain execution placeholder - full implementation requires AgentToolManager
		// which depends on Agent7 module not available in Service7 classpath
		return Response.status(200).entity("{\"status\":\"chain_submitted\",\"planQuery\":\"" + planQuery + "\"}").build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/chain/status/{planId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response chainStatus(@PathParam("planId") String planId, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		logger.info("Chain status query for plan " + planId + " by user " + user.get("name"));

		// Status query placeholder - will resolve plan by objectId once persistence is wired
		return Response.status(200).entity("{\"planId\":\"" + planId + "\",\"status\":\"unknown\"}").build();
	}

	/// Phase 15: 3-stage scene generation pipeline.
	/// Stage 1: User submits SD config via POST body (model, steps, cfg, sampler, etc.)
	/// Stage 2: Generate landscape reference image from chatConfig setting/terrain
	/// Stage 3: Composite scene — landscape as initImage + character portraits via IP-Adapter
	/// POST body: SD config JSON with sceneCreativity and skipLandscape fields
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/{objectId:[A-Fa-f0-9\\-]+}/generateScene")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response generateScene(String json, @PathParam("objectId") String objectId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		/// 1. Load chat request — planMost(false) avoids recursive foreign-ref expansion
		/// which exceeds PostgreSQL's 100-argument limit on chatRequest → chatConfig → charPerson chains.
		/// Child objects are loaded separately via OlioUtil.getFullRecord() below.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.planMost(false);
		BaseRecord chatReqRec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if (chatReqRec == null) {
			logger.error("generateScene: Chat request not found: " + objectId);
			return Response.status(404).entity("{\"error\":\"Chat request not found\"}").build();
		}
		ChatRequest chatReq = new ChatRequest(chatReqRec);

		/// 2. Load configs and create Chat instance
		BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.getChatConfig());
		BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.getPromptConfig());
		if (chatConfig == null || promptConfig == null) {
			logger.error("generateScene: Missing chatConfig or promptConfig");
			return Response.status(400).entity("{\"error\":\"Missing chatConfig or promptConfig\"}").build();
		}

		BaseRecord systemChar = OlioUtil.getFullRecord(chatConfig.get("systemCharacter"));
		BaseRecord userChar = OlioUtil.getFullRecord(chatConfig.get("userCharacter"));
		if (systemChar == null || userChar == null) {
			logger.error("generateScene: Both systemCharacter and userCharacter must be set on chatConfig");
			return Response.status(400).entity("{\"error\":\"Both characters must be set\"}").build();
		}

		Chat chat = ChatUtil.getChat(user, chatReq, Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));

		/// 3. Load existing session to get chat history for the scene description LLM call
		OpenAIRequest req = ChatUtil.getOpenAIRequest(user, chatReq);
		if (req == null) {
			logger.error("generateScene: Failed to create OpenAIRequest");
			return Response.status(500).entity("{\"error\":\"Failed to load chat session\"}").build();
		}

		/// Stage 1: Parse SD config from request body
		BaseRecord sdConfig = null;
		if (json != null && !json.isBlank()) {
			sdConfig = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		}
		if (sdConfig != null) {
			if (sdConfig.get("model") == null) {
				sdConfig.setValue("model", context.getInitParameter("sd.model"));
			}
			if (sdConfig.get("refinerModel") == null) {
				sdConfig.setValue("refinerModel", context.getInitParameter("sd.refinerModel"));
			}
		}

		/// Create SDUtil early — needed for both landscape and scene stages
		String apiType = context.getInitParameter("sd.server.apiType");
		String server = context.getInitParameter("sd.server");
		if (apiType == null || server == null) {
			logger.error("generateScene: sd.server.apiType or sd.server not configured");
			return Response.status(500).entity("{\"error\":\"SD server not configured\"}").build();
		}
		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(apiType), server);
		sdu.setDeferRemote(Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));
		sdu.setImageAccessUser(user);

		/// Stage 2: Generate landscape reference image (unless skipLandscape=true)
		boolean skipLandscape = false;
		double sceneCreativity = 0.85;
		if (sdConfig != null) {
			try { skipLandscape = (boolean) sdConfig.get("skipLandscape"); } catch (Exception e) { /* default false */ }
			try {
				Double sc = sdConfig.get("sceneCreativity");
				if (sc != null) sceneCreativity = sc;
			} catch (Exception e) { /* default 0.85 */ }
		}

		byte[] landscapeBytes = null;
		if (!skipLandscape) {
			String setting = null;
			String terrain = null;
			try { setting = chatConfig.get("setting"); } catch (Exception e) { /* ignore */ }
			try { terrain = chatConfig.get("terrain"); } catch (Exception e) { /* ignore */ }

			if (setting != null && !setting.isEmpty() && !"random".equalsIgnoreCase(setting)) {
				String landscapePrompt = "8k highly detailed landscape photograph, " + setting;
				if (terrain != null && !terrain.isEmpty()) {
					landscapePrompt += ", " + terrain + " terrain";
				}
				landscapePrompt += ", cinematic lighting, wide angle, no people, no text";
				logger.info("generateScene Stage 2: Generating landscape — " + landscapePrompt.substring(0, Math.min(100, landscapePrompt.length())));
				WebSocketService.chirpUser(user, new String[] {"bgActivity", "landscape", "Generating landscape..."});
				landscapeBytes = sdu.generateLandscapeBytes(landscapePrompt, null, sdConfig);
				if (landscapeBytes != null) {
					logger.info("generateScene Stage 2: Landscape generated — " + landscapeBytes.length + " bytes");
				} else {
					logger.warn("generateScene Stage 2: Landscape generation failed, falling back to text-only");
				}
			} else {
				logger.info("generateScene Stage 2: No setting on chatConfig, skipping landscape");
			}
		} else {
			logger.info("generateScene Stage 2: Skipped (skipLandscape=true)");
		}

		/// Stage 3: Composite scene — LLM scene description + character portraits + landscape reference
		WebSocketService.chirpUser(user, new String[] {"bgActivity", "landscape", "Generating scene..."});

		/// 3a. Generate the scene prompt (LLM call + prompt assembly)
		Chat.ScenePromptResult sceneResult = chat.generateScenePrompt(req);
		if (sceneResult == null) {
			logger.error("generateScene: Failed to generate scene prompt");
			return Response.status(500).entity("{\"error\":\"Failed to generate scene prompt\"}").build();
		}

		/// 3b. Build SWTxt2Img with scene prompt + SD config
		SWTxt2Img s2i = SWUtil.newSceneTxt2Img(sceneResult.prompt, sceneResult.negativePrompt, sdConfig);

		/// 3c. Composite portrait images onto the landscape canvas, then use as initImage.
		/// This merges the character faces directly into the reference image so the
		/// img2img pass refines them into the scene — no IP-Adapter required.
		byte[] compositeBytes = SDUtil.compositeSceneCanvas(
			landscapeBytes,
			sceneResult.sysPortraitBytes,
			sceneResult.usrPortraitBytes,
			s2i.getWidth(),
			s2i.getHeight()
		);
		if (compositeBytes != null) {
			s2i.setInitImage("data:image/png;base64," + Base64.getEncoder().encodeToString(compositeBytes));
			s2i.setInitImageCreativity(sceneCreativity);
			logger.info("generateScene Stage 3c: Composite canvas as initImage (creativity=" + sceneCreativity
				+ " sysPortrait=" + (sceneResult.sysPortraitBytes != null)
				+ " usrPortrait=" + (sceneResult.usrPortraitBytes != null) + ")");
		}

		/// 3d. Generate the final composite scene image
		String groupPath = "~/Gallery/Scenes/" + sceneResult.label;
		String name = sceneResult.label;
		String sysOid = systemChar.get(FieldNames.FIELD_OBJECT_ID);
		String usrOid = userChar.get(FieldNames.FIELD_OBJECT_ID);

		List<BaseRecord> images = sdu.createSceneImage(user, groupPath, name, s2i, sysOid, usrOid);

		if (images.isEmpty()) {
			logger.error("generateScene: No images generated");
			return Response.status(500).entity("{\"error\":\"No images generated\"}").build();
		}

		/// Notify via WebSocket and clear bgActivity
		String imageOid = images.get(0).get(FieldNames.FIELD_OBJECT_ID);
		WebSocketService.chirpUser(user, new String[] {"sceneImage", objectId, imageOid});
		WebSocketService.chirpUser(user, new String[] {"bgActivity", "", ""});

		/// Return first image record
		return Response.status(200).entity(images.get(0).toFullString()).build();
	}

}
