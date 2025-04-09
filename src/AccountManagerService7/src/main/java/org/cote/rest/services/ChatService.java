package org.cote.rest.services;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.service.util.ServiceUtil;

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
		BaseRecord chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		if(chatConfig != null && promptConfig != null) {
	
			String key = ChatUtil.getKey(user, chatConfig, promptConfig, chatReq);
			//dataMap.remove(key);
			ChatUtil.getReqMap().remove(key);
			ChatUtil.getChatMap().remove(key);
			ChatUtil.getConfigMap().remove(chatConfig.get("objectId"));
			ChatUtil.getConfigMap().remove(promptConfig.get("objectId"));
			/*
			charMap.remove(user.get(FieldNames.FIELD_NAME) + "-" + chatReq.getSystemCharacter());
			charMap.remove(user.get(FieldNames.FIELD_NAME) + "-" + chatReq.getUserCharacter());
			*/
		}
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/history")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chatHistory(String json, @Context HttpServletRequest request){
		logger.info("History ....");
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
		BaseRecord chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		if(chatConfig != null && promptConfig != null) {
			String key = ChatUtil.getKey(user, chatConfig, promptConfig, chatReq);
			crep = new ChatResponse();
			if(ChatUtil.getReqMap().containsKey(key)) {
				crep = ChatUtil.getChatResponse(user, ChatUtil.getReqMap().get(key), chatReq);
			}
			else if (chatReq.getSessionName() != null){
				String sessionName = ChatUtil.getSessionName(user, chatConfig, promptConfig, chatReq.getSessionName());
				OpenAIRequest oreq = ChatUtil.getSession(user, sessionName);
				if(oreq != null) {
					crep = ChatUtil.getChatResponse(user, oreq, chatReq);
				}
			}
		}
		else {
			logger.error("Invalid chat or prompt configuration");
		}
		
		return Response.status((crep != null ? 200 : 404)).entity(crep).build();
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
	public Response getPromptConfig(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = ChatUtil.getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, null, name);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}
	

	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/chat/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getChatConfig(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, name);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}
	
	private Map<String, String> objectSummary = new HashMap<>();
	
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
		
		OpenAIRequest req = ChatUtil.getOpenAIRequest(user, chatReq);
		String citRef = "";
		if(chatReq.getMessage() != null && chatReq.getMessage().length() > 0) {
			List<String> cits = ChatUtil.getDataCitations(user, req, chatReq);
			if(cits.size() > 0) {
				citRef = System.lineSeparator() + cits.stream().collect(Collectors.joining(System.lineSeparator()));
			}
		}
		
		BaseRecord chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		String key = ChatUtil.getKey(user, chatConfig, promptConfig, chatReq); 
		logger.info("Chat request: " + key);
		Chat chat = ChatUtil.getChat(user, chatReq, key, Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));
		chat.continueChat(req, chatReq.getMessage() + citRef);
		if(chatReq.getMessage().startsWith("/next")) {
			/// Dump the request from the cache when moving episodes
			ChatUtil.forgetRequest(user, chatReq);
		}
		ChatResponse creq = ChatUtil.getChatResponse(user, req, chatReq);
		
		return Response.status((creq != null ? 200 : 404)).entity(creq).build();
	}
	
	
	

}
