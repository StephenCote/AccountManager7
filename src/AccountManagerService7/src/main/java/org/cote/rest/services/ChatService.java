package org.cote.rest.services;

import java.util.List;
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
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

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

}
