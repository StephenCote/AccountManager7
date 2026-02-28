package org.cote.rest.services;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.agent.AgentToolManager;
import org.cote.accountmanager.agent.AM7AgentTool;
import org.cote.accountmanager.agent.ChainExecutor;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
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
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
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

	/// Track in-flight async summarizations: sessionId → set of objectIds being summarized
	private static final Map<String, Set<String>> summarizingRefs = new ConcurrentHashMap<>();

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
	@GET
	@Path("/library/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getLibraryStatus(@Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		boolean populated = ChatLibraryUtil.isLibraryPopulated(user);
		boolean promptPopulated = ChatLibraryUtil.isPromptLibraryPopulated(user);
		String json = "{\"initialized\":" + populated
			+ ",\"promptInitialized\":" + promptPopulated
			+ ",\"chatLibraryPath\":\"" + ChatLibraryUtil.LIBRARY_PATH_CHAT + "\""
			+ ",\"promptLibraryPath\":\"" + ChatLibraryUtil.LIBRARY_PATH_PROMPT + "\"}";
		return Response.status(200).entity(json).build();
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/library/init")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response initializeLibrary(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord params = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(params == null) {
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}
		String serverUrl = params.get("serverUrl");
		String model = params.get("model");
		String serviceType = params.get("serviceType");
		ChatLibraryUtil.populateDefaults(user, serverUrl, model, serviceType);
		return Response.status(200).entity("{\"status\":\"ok\"}").build();
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/library/prompt/init")
	@Produces(MediaType.APPLICATION_JSON)
	public Response initializePromptLibrary(@Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		ChatLibraryUtil.populatePromptDefaults(user);
		return Response.status(200).entity("{\"status\":\"ok\"}").build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/library/dir/{type:chat|prompt}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getLibraryDir(@PathParam("type") String type, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String libName = "chat".equals(type) ? ChatLibraryUtil.LIBRARY_CHAT_CONFIGS : ChatLibraryUtil.LIBRARY_PROMPT_CONFIGS;
		BaseRecord dir = ChatLibraryUtil.findLibraryDir(user, libName);
		return Response.status((dir != null ? 200 : 404)).entity((dir != null ? dir.toFullString() : null)).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/library/chat/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getLibraryChatConfig(@PathParam(FieldNames.FIELD_NAME) String name, @QueryParam("group") String group, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String groupPath = (group != null && !group.isEmpty()) ? group : null;
		BaseRecord cfg = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, name, groupPath);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/library/prompt/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getLibraryPromptConfig(@PathParam(FieldNames.FIELD_NAME) String name, @QueryParam("group") String group, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		String groupPath = (group != null && !group.isEmpty()) ? group : null;
		BaseRecord cfg = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, name, groupPath);
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
		String promptTemplateId = null;

		if (chatReq.getChatConfig() != null) {
			chatConfigId = chatReq.get("chatConfig.objectId");
		}

		if (chatReq.getPromptConfig() != null) {
			promptConfigId = chatReq.get("promptConfig.objectId");
		}

		if (chatReq.get("promptTemplate") != null) {
			BaseRecord ptRef = chatReq.get("promptTemplate");
			if (ptRef != null) promptTemplateId = ptRef.get(FieldNames.FIELD_OBJECT_ID);
		}

		/// Require chatConfig + name, plus at least one of promptConfig or promptTemplate
		boolean hasPrompt = (promptConfigId != null && promptConfigId.length() > 0)
			|| (promptTemplateId != null && promptTemplateId.length() > 0);
		if(chatConfigId == null || chatConfigId.length() == 0 || name == null || name.length() == 0 || !hasPrompt) {
			logger.warn("Name, chat config, or prompt (config/template) was null or empty");
			return Response.status(404).entity(null).build();
		}

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, chatConfigId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord chatConfig = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if (chatConfig == null) {
			logger.warn("Chat config not found");
			return Response.status(404).entity(null).build();
		}

		BaseRecord promptConfig = null;
		if (promptConfigId != null && promptConfigId.length() > 0) {
			Query q2 = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG, FieldNames.FIELD_OBJECT_ID, promptConfigId);
			q2.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			promptConfig = IOSystem.getActiveContext().getAccessPoint().find(user, q2);
			if (promptConfig == null) {
				logger.warn("Prompt config not found: " + promptConfigId);
				return Response.status(404).entity(null).build();
			}
		}

		BaseRecord promptTemplate = null;
		if (promptTemplateId != null && promptTemplateId.length() > 0) {
			Query q3 = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_TEMPLATE, FieldNames.FIELD_OBJECT_ID, promptTemplateId);
			q3.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			promptTemplate = IOSystem.getActiveContext().getAccessPoint().find(user, q3);
			if (promptTemplate == null) {
				logger.warn("Prompt template not found: " + promptTemplateId);
				return Response.status(404).entity(null).build();
			}
		}

		BaseRecord req = ChatUtil.getChatRequest(user, name, chatConfig, promptConfig);
		if(req != null) {
			logger.warn("Chat request '" + name + "' already exists");
			return Response.status(404).entity(null).build();
		}

		req = ChatUtil.getCreateChatRequest(user, name, chatConfig, promptConfig, promptTemplate);
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
		/// Set chatRequestObjectId so auto-title can persist to the chatRequest record
		if (chat != null) {
			String chatReqOid = vChatReq.get(FieldNames.FIELD_OBJECT_ID);
			if (chatReqOid != null) {
				chat.setChatRequestObjectId(chatReqOid);
			}
		}

		String citDesc = "";
		if(citRef.length() > 0) {
			String citTpl = (chat.getPromptConfig() != null) ? PromptUtil.getUserCitationTemplate(chat.getPromptConfig(), chat.getChatConfig()) : null;
			if(citTpl == null || citTpl.length() == 0) {
				/// MCP blocks are self-describing; no wrapper needed
				citDesc = citRef + System.lineSeparator();
			}
			else {
			    PromptBuilderContext ctx = new PromptBuilderContext(chat.getPromptConfig(), chat.getChatConfig(), citTpl, true);
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

		String sessionId = null;
		String attachType = null;
		String objectId = null;
		String objectType = null;
		try {
			ObjectMapper om = new ObjectMapper();
			JsonNode body = om.readTree(json);
			sessionId = body.has("sessionId") ? body.get("sessionId").asText() : null;
			attachType = body.has("attachType") ? body.get("attachType").asText() : null;
			objectId = body.has("objectId") ? body.get("objectId").asText() : null;
			objectType = body.has("objectType") ? body.get("objectType").asText() : null;
		} catch (Exception e) {
			logger.error("Failed to parse attach request body", e);
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

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

					/// Backward compat: set single context/contextType for primary display
					chatReq.set("contextType", objectType);
					chatReq.set("context", contextObj);

					/// Append to persisted contextRefs list (deduplicated)
					appendContextRef(chatReq, objectType, objectId);

					/// Verify contextRefs was set before update
					List<String> preUpdateRefs = chatReq.get("contextRefs");
					logger.info("Pre-update contextRefs count: " + (preUpdateRefs != null ? preUpdateRefs.size() : "null"));

					BaseRecord updateResult = IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					logger.info("Update result: " + (updateResult != null ? "success" : "FAILED"));

					/// Auto-vectorize if no vectors exist for this object
					autoVectorize(user, contextObj, objectType);

					/// Auto-summarize if no summary exists and object has content
					boolean summarizeStarted = autoSummarize(user, chatReq, contextObj);
					return contextResult(true, attachType, null, summarizeStarted);
				}
				case "tag": {
					BaseRecord tagObj = findByObjectId(user, ModelNames.MODEL_TAG, objectId);
					if (tagObj == null) return Response.status(404).entity("{\"error\":\"Tag not found\"}").build();

					/// Append tag to persisted contextRefs list
					appendContextRef(chatReq, ModelNames.MODEL_TAG, objectId);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					break;
				}
				default:
					return Response.status(400).entity("{\"error\":\"Invalid attachType: " + attachType + "\"}").build();
			}

			return contextResult(true, attachType, null, false);
		}
		catch (Exception e) {
			logger.error("Error attaching context", e);
			return contextResultError(e.getMessage());
		}
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/context/detach")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response detachContext(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		String sessionId = null;
		String detachType = null;
		String objectId = null;
		try {
			ObjectMapper om = new ObjectMapper();
			JsonNode body = om.readTree(json);
			sessionId = body.has("sessionId") ? body.get("sessionId").asText() : null;
			detachType = body.has("detachType") ? body.get("detachType").asText() : null;
			objectId = body.has("objectId") ? body.get("objectId").asText() : null;
		} catch (Exception e) {
			logger.error("Failed to parse detach request body", e);
			return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
		}

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
				case "context":
				case "tag": {
					if (objectId != null && !objectId.isEmpty()) {
						/// Remove specific ref from contextRefs by objectId
						removeContextRef(chatReq, objectId);
					}
					/// If removing the primary context, also clear context/contextType
					if ("context".equals(detachType)) {
						String primaryCtxType = chatReq.get("contextType");
						if (primaryCtxType != null) {
							BaseRecord primaryCtx = chatReq.get("context");
							String primaryOid = primaryCtx != null ? (String) primaryCtx.get(FieldNames.FIELD_OBJECT_ID) : null;
							if (objectId == null || objectId.equals(primaryOid)) {
								chatReq.set("contextType", null);
								chatReq.set("context", null);
							}
						}
					}
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					break;
				}
				default:
					return Response.status(400).entity("{\"error\":\"Cannot detach " + detachType + "\"}").build();
			}

			return contextResult(false, null, detachType, false);
		}
		catch (Exception e) {
			logger.error("Error detaching context", e);
			return contextResultError(e.getMessage());
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
			sq.planMost(false);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return Response.status(200).entity("{}").build();
			}

			BaseRecord ctx = RecordFactory.newInstance(OlioModelNames.MODEL_SESSION_CONTEXT);

			BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
			if (chatConfig != null) {
				BaseRecord ccRef = newContextRef(null,
					(String) chatConfig.get(FieldNames.FIELD_OBJECT_ID),
					(String) chatConfig.get(FieldNames.FIELD_NAME));
				/// chatConfig ref carries the model name for display
				ccRef.set("schema", (String) chatConfig.get("model"));
				ctx.set("chatConfig", ccRef);

				BaseRecord sysChRef = chatConfig.get("systemCharacter");
				if (sysChRef != null) {
					BaseRecord sysCh = OlioUtil.getFullRecord(sysChRef);
					if (sysCh != null) {
						ctx.set("systemCharacter", newContextRef(null,
							(String) sysCh.get(FieldNames.FIELD_OBJECT_ID),
							(String) sysCh.get(FieldNames.FIELD_NAME)));
					}
				}
				BaseRecord usrChRef = chatConfig.get("userCharacter");
				if (usrChRef != null) {
					BaseRecord usrCh = OlioUtil.getFullRecord(usrChRef);
					if (usrCh != null) {
						ctx.set("userCharacter", newContextRef(null,
							(String) usrCh.get(FieldNames.FIELD_OBJECT_ID),
							(String) usrCh.get(FieldNames.FIELD_NAME)));
					}
				}
			}

			BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.get("promptConfig"));
			if (promptConfig != null) {
				ctx.set("promptConfig", newContextRef(null,
					(String) promptConfig.get(FieldNames.FIELD_OBJECT_ID),
					(String) promptConfig.get(FieldNames.FIELD_NAME)));
			}

			String contextType = chatReq.get("contextType");
			if (contextType != null && !contextType.isEmpty()) {
				BaseRecord contextObj = OlioUtil.getFullRecord(chatReq.get("context"));
				BaseRecord legacyRef = newContextRef(contextType, null, null);
				if (contextObj != null) {
					legacyRef.set("objectId", (String) contextObj.get(FieldNames.FIELD_OBJECT_ID));
					legacyRef.set("name", (String) contextObj.get(FieldNames.FIELD_NAME));
				}
				ctx.set("context", legacyRef);
			}

			/// Build contextRefs list with summarizing status
			List<String> contextRefs = chatReq.get("contextRefs");
			Set<String> inFlightSet = summarizingRefs.getOrDefault(sessionId, Collections.emptySet());
			boolean anySummarizing = false;
			if (contextRefs != null && !contextRefs.isEmpty()) {
				List<BaseRecord> refList = new ArrayList<>();
				for (String ref : contextRefs) {
					try {
						BaseRecord refRec = RecordFactory.importRecord(ref);
						String refSchema = refRec.getSchema();
						String refOid = refRec.get(FieldNames.FIELD_OBJECT_ID);
						BaseRecord resolved = findByObjectId(user, refSchema, refOid);
						String resolvedName = (resolved != null && resolved.hasField(FieldNames.FIELD_NAME))
							? (String) resolved.get(FieldNames.FIELD_NAME) : null;

						BaseRecord entry = newContextRef(refSchema, refOid, resolvedName);
						boolean isSummarizing = inFlightSet.contains(refOid);
						if (isSummarizing) {
							entry.set("summarizing", true);
							anySummarizing = true;
						}
						refList.add(entry);
					} catch (Exception e) {
						logger.warn("Error resolving contextRef: " + ref, e);
					}
				}
				ctx.set("contextRefs", refList);
			}

			if (anySummarizing) {
				ctx.set("summarizing", true);
			}

			return Response.status(200).entity(ctx.toFullString()).build();
		}
		catch (Exception e) {
			logger.error("Error reading session context", e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Create a new contextRef model instance with optional fields
	private BaseRecord newContextRef(String schema, String objectId, String name) {
		try {
			BaseRecord ref = RecordFactory.newInstance(OlioModelNames.MODEL_CONTEXT_REF);
			if (schema != null) ref.set("schema", schema);
			if (objectId != null) ref.set("objectId", objectId);
			if (name != null) ref.set("name", name);
			return ref;
		} catch (Exception e) {
			logger.error("Failed to create contextRef instance", e);
			return null;
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

	/// Build a contextResult response for attach/detach operations
	private Response contextResult(boolean attached, String attachType, String detachType, boolean summarizing) {
		try {
			BaseRecord result = RecordFactory.newInstance(OlioModelNames.MODEL_CONTEXT_RESULT);
			if (attached) {
				result.set("attached", true);
				if (attachType != null) result.set("attachType", attachType);
			} else {
				result.set("detached", true);
				if (detachType != null) result.set("detachType", detachType);
			}
			if (summarizing) result.set("summarizing", true);
			return Response.status(200).entity(result.toFullString()).build();
		} catch (Exception e) {
			logger.error("Failed to build contextResult", e);
			return Response.status(200).entity("{\"attached\":" + attached + "}").build();
		}
	}

	private Response contextResultError(String message) {
		try {
			BaseRecord result = RecordFactory.newInstance(OlioModelNames.MODEL_CONTEXT_RESULT);
			result.set("error", message);
			return Response.status(500).entity(result.toFullString()).build();
		} catch (Exception e) {
			logger.error("Failed to build contextResult error", e);
			return Response.status(500).entity("{\"error\":\"" + message + "\"}").build();
		}
	}

	/// Append a serialized {schema, objectId} ref to chatRequest.contextRefs, deduplicating by objectId
	private void appendContextRef(BaseRecord chatReq, String schema, String objectId) {
		try {
			List<String> existingRefs = chatReq.get("contextRefs");
			/// Always create a new list to ensure the field is marked dirty for the update mechanism
			List<String> refs = new ArrayList<>();
			if (existingRefs != null) {
				refs.addAll(existingRefs);
			}
			/// Check for existing ref with same objectId
			for (String existing : refs) {
				try {
					BaseRecord r = RecordFactory.importRecord(existing);
					if (objectId.equals(r.get(FieldNames.FIELD_OBJECT_ID))) {
						logger.info("contextRef already attached for objectId: " + objectId);
						return; /// Already attached
					}
				} catch (Exception e) {
					logger.warn("Malformed contextRef entry: " + existing, e);
				}
			}
			String newRef = "{\"schema\":\"" + escJson(schema) + "\",\"objectId\":\"" + escJson(objectId) + "\"}";
			refs.add(newRef);
			chatReq.set("contextRefs", refs);
			logger.info("Appended contextRef: " + newRef + " (total: " + refs.size() + ")");
		} catch (Exception e) {
			logger.error("Failed to append contextRef", e);
		}
	}

	/// Remove a ref from contextRefs by objectId
	private void removeContextRef(BaseRecord chatReq, String objectId) {
		try {
			List<String> existingRefs = chatReq.get("contextRefs");
			if (existingRefs == null || existingRefs.isEmpty()) return;
			/// Always create a new list to ensure the field is marked dirty
			List<String> newRefs = new ArrayList<>();
			for (String ref : existingRefs) {
				try {
					BaseRecord r = RecordFactory.importRecord(ref);
					if (!objectId.equals(r.get(FieldNames.FIELD_OBJECT_ID))) {
						newRefs.add(ref);
					}
				} catch (Exception e) {
					newRefs.add(ref); /// Keep malformed entries
				}
			}
			chatReq.set("contextRefs", newRefs);
			logger.info("Removed contextRef for objectId: " + objectId + " (remaining: " + newRefs.size() + ")");
		} catch (Exception e) {
			logger.warn("Failed to remove contextRef", e);
		}
	}

	/// Auto-vectorize a context object if no vector store exists for it
	private void autoVectorize(BaseRecord user, BaseRecord contextObj, String objectType) {
		try {
			VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
			if (vu == null) {
				logger.warn("VectorUtil not available — skipping auto-vectorize");
				return;
			}
			if (vu.countVectorStore(contextObj) > 0) {
				logger.info("Vectors already exist for " + objectType + " " + contextObj.get(FieldNames.FIELD_OBJECT_ID) + " — skipping auto-vectorize");
				return;
			}
			String content = DocumentUtil.getStringContent(contextObj);
			if (content == null || content.isEmpty()) {
				logger.info("No string content for " + objectType + " — skipping auto-vectorize");
				return;
			}
			logger.info("Auto-vectorizing " + objectType + " " + contextObj.get(FieldNames.FIELD_OBJECT_ID));
			vu.createVectorStore(contextObj, ChunkEnumType.WORD, 500);
		} catch (Exception e) {
			logger.warn("Auto-vectorize failed for " + objectType + ": " + e.getMessage());
		}
	}

	/// Auto-summarize a context object if no summary exists.
	/// Returns true if async summarization was started.
	private boolean autoSummarize(BaseRecord user, BaseRecord chatReq, BaseRecord contextObj) {
		try {
			String content = DocumentUtil.getStringContent(contextObj);
			if (content == null || content.isEmpty()) {
				return false;
			}
			BaseRecord existingSummary = ChatUtil.getSummary(user, contextObj);
			if (existingSummary != null) {
				logger.info("Summary already exists for " + contextObj.get(FieldNames.FIELD_OBJECT_ID) + " — skipping auto-summarize");
				return false;
			}
			/// Resolve chatConfig and promptConfig from the chatRequest for the summarization LLM call
			BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
			BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.get("promptConfig"));
			if (chatConfig == null || promptConfig == null) {
				logger.warn("Cannot auto-summarize: chatConfig or promptConfig not available on session");
				return false;
			}

			String sessionId = chatReq.get(FieldNames.FIELD_OBJECT_ID);
			String objectId = contextObj.get(FieldNames.FIELD_OBJECT_ID);

			/// Mark as in-flight so the context endpoint can report summarizing status
			summarizingRefs.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(objectId);
			logger.info("Auto-summarizing " + objectId + " (async)");

			/// Run summarization asynchronously so the attach response returns immediately
			CompletableFuture.runAsync(() -> {
				try {
					ChatUtil.createSummary(user, chatConfig, promptConfig, contextObj, false);
					logger.info("Auto-summarize complete for " + objectId);
				} catch (Exception e) {
					logger.warn("Auto-summarize failed for " + objectId + ": " + e.getMessage());
				} finally {
					/// Remove from in-flight set
					Set<String> refs = summarizingRefs.get(sessionId);
					if (refs != null) {
						refs.remove(objectId);
						if (refs.isEmpty()) {
							summarizingRefs.remove(sessionId);
						}
					}
				}
			});
			return true;
		} catch (Exception e) {
			logger.warn("Auto-summarize failed: " + e.getMessage());
			return false;
		}
	}

	@RolesAllowed({"admin","user"})
	@POST
	@Path("/chain")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chain(String json, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);

		String planQuery = null;
		String chatConfigObjectId = null;
		String planJson = null;
		try {
			ObjectMapper om = new ObjectMapper();
			JsonNode body = om.readTree(json);
			planQuery = body.has("planQuery") ? body.get("planQuery").asText() : null;
			chatConfigObjectId = body.has("chatConfigObjectId") ? body.get("chatConfigObjectId").asText() : null;
			if (body.has("plan") && !body.get("plan").isNull()) {
				JsonNode planNode = body.get("plan");
				planJson = planNode.isTextual() ? planNode.asText() : planNode.toString();
			}
		} catch (Exception e) {
			logger.error("Failed to parse chain request body", e);
			return Response.status(400).entity("{\"error\":\"Invalid chain request\"}").build();
		}

		if (planQuery == null || planQuery.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"planQuery is required\"}").build();
		}

		logger.info("Synchronous chain execution for user " + user.get("name") + ": " + planQuery);

		/// Resolve chatConfig if provided (needed for AgentToolManager context)
		BaseRecord chatConfig = null;
		if (chatConfigObjectId != null && !chatConfigObjectId.isEmpty()) {
			chatConfig = OlioUtil.getFullRecord(findByObjectId(user, "olio.llm.chatConfig", chatConfigObjectId));
		}

		/// Check for pre-built plan JSON

		try {
			AM7AgentTool agentTool = new AM7AgentTool(user);
			AgentToolManager toolMgr = new AgentToolManager(user, chatConfig, agentTool);
			ChainExecutor executor = toolMgr.getChainExecutor();

			BaseRecord plan;
			if (planJson != null && !planJson.isEmpty()) {
				plan = JSONUtil.importObject(planJson, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
				if (plan == null) {
					return Response.status(400).entity("{\"error\":\"Invalid plan JSON\"}").build();
				}
				toolMgr.preparePlanSteps(plan);
			} else {
				plan = toolMgr.createChainPlan(planQuery);
				if (plan == null) {
					return Response.status(500).entity("{\"error\":\"Failed to create chain plan\"}").build();
				}
			}

			executor.executeChain(plan);

			/// Build MCP context from chain results
			java.util.Map<String, Object> ctx = executor.getChainContext();
			McpContextBuilder mcpBuilder = new McpContextBuilder();
			String planName = plan.get(FieldNames.FIELD_NAME);
			mcpBuilder.addResource("am7://chain/" + (planName != null ? planName : "result"),
				"urn:am7:agent:chain-result",
				ctx, true);
			String mcpResult = mcpBuilder.build();

			return Response.status(200).entity("{\"status\":\"complete\",\"planQuery\":\"" + escJson(planQuery) + "\",\"mcpContext\":\"" + escJson(mcpResult) + "\"}").build();
		} catch (Exception e) {
			logger.error("Chain execution failed for user " + user.get("name"), e);
			return Response.status(500).entity("{\"error\":\"Chain execution failed: " + escJson(e.getMessage()) + "\"}").build();
		}
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

		/// Parse scene config flags from sdConfig
		boolean skipLandscape = false;
		boolean useKontext = true;
		if (sdConfig != null) {
			try { skipLandscape = (boolean) sdConfig.get("skipLandscape"); } catch (Exception e) { /* default false */ }
			try { Boolean uk = sdConfig.get("useKontext"); if (uk != null) useKontext = uk; } catch (Exception e) { /* default true */ }
		}
		/// Kontext 2-pass needs moderate creativity — enough to restructure panels while preserving faces
		double sceneCreativity = useKontext ? 0.65 : 0.85;
		if (sdConfig != null) {
			try {
				Double sc = sdConfig.get("sceneCreativity");
				if (sc != null) sceneCreativity = sc;
			} catch (Exception e) { /* use default */ }
		}

		/// Stage 2: Generate landscape reference image (unless skipLandscape=true)

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
		Chat.ScenePromptResult sceneResult = chat.generateScenePrompt(req, sdConfig);
		if (sceneResult == null) {
			logger.error("generateScene: Failed to generate scene prompt");
			return Response.status(500).entity("{\"error\":\"Failed to generate scene prompt\"}").build();
		}

		/// 3b–3d: Branch between Kontext (stitch-and-prompt) and classic (Graphics2D composite) pipelines
		String groupPath = "~/Gallery/Scenes/" + sceneResult.label;
		String name = sceneResult.label;
		String sysOid = systemChar.get(FieldNames.FIELD_OBJECT_ID);
		String usrOid = userChar.get(FieldNames.FIELD_OBJECT_ID);
		List<BaseRecord> images = new java.util.ArrayList<>();

		if (useKontext) {
			/// KONTEXT SINGLE-PASS PIPELINE: stitch [sysPortrait | usrPortrait | landscape]
			/// into one composite reference image, send as a single promptImage.
			String settingDesc = "";
			try { String s = chatConfig.get("setting"); if (s != null && !s.isEmpty()) settingDesc = s; } catch (Exception e) { /* ignore */ }

			WebSocketService.chirpUser(user, new String[] {"bgActivity", "landscape", "Kontext compositing..."});

			byte[] stitchedBytes = SDUtil.stitchSceneImages(
				sceneResult.sysPortraitBytes,
				sceneResult.usrPortraitBytes,
				landscapeBytes,
				1024
			);

			SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(
				sceneResult.sysCharDesc, sceneResult.usrCharDesc,
				sceneResult.sceneDesc, settingDesc, sdConfig
			);

			if (stitchedBytes != null) {
				List<String> promptImages = new java.util.ArrayList<>();
				promptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(stitchedBytes));
				s2i.setPromptImages(promptImages);
				logger.info("generateScene Kontext: stitched composite " + stitchedBytes.length + " bytes");
			}

			images = sdu.createSceneImage(user, groupPath, name, s2i, sysOid, usrOid);

			if (images.isEmpty()) {
				logger.warn("generateScene: Kontext pipeline produced no images — falling back to classic");
				useKontext = false;
			}
		}

		if (!useKontext) {
			/// CLASSIC PIPELINE: Graphics2D composite + SDXL img2img refinement
			SWTxt2Img s2i = SWUtil.newSceneTxt2Img(sceneResult.prompt, sceneResult.negativePrompt, sdConfig);
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
				logger.info("generateScene Classic: composite " + compositeBytes.length + " bytes, creativity=" + sceneCreativity);
			}
			images = sdu.createSceneImage(user, groupPath, name, s2i, sysOid, usrOid);
		}

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
