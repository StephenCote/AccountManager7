package org.cote.rest.services;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

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
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OllamaChatRequest;
import org.cote.accountmanager.olio.llm.OllamaChatResponse;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/chat")
public class ChatService {
	
	private static final Logger logger = LogManager.getLogger(ChatService.class);
	
	@Context
	ServletContext context;
	
	private static Set<String> chatTrack = new HashSet<>();
	private static HashMap<String, OllamaRequest> reqMap = new HashMap<>();
	private static HashMap<String, Chat> chatMap = new HashMap<>();
	private static HashMap<String, BaseRecord> configMap = new HashMap<>();
	
	public static void clearCache() {
		chatTrack.clear();
		reqMap.clear();
		configMap.clear();
	}
	
	private String getKey(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, OllamaChatRequest request) {
		String sess = "";
		if(request.getSessionName() != null && request.getSessionName().length() > 0) {
			sess = "-" + request.getSessionName();
		}
		return user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get(FieldNames.FIELD_NAME) + "-" + promptConfig.get(FieldNames.FIELD_NAME) + sess;
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/clearAll")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response clearAll(@Context HttpServletRequest request){
		clearCache();
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/clear")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response clear(String json, @Context HttpServletRequest request){
		logger.info("Clear ....");
		OllamaChatRequest chatReq = JSONUtil.importObject(json, OllamaChatRequest.class);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return Response.status(404).entity(null).build();
		}
		if(chatTrack.contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		if(chatConfig != null && promptConfig != null) {
	
			String key = getKey(user, chatConfig, promptConfig, chatReq);
			//dataMap.remove(key);
			reqMap.remove(key);
			chatMap.remove(key);
			configMap.remove(chatConfig.get("objectId"));
			configMap.remove(promptConfig.get("objectId"));
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
		OllamaChatRequest chatReq = JSONUtil.importObject(json, OllamaChatRequest.class);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			logger.warn(JSONUtil.exportObject(chatReq));
			return Response.status(404).entity(null).build();
		}
		if(chatTrack.contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		OllamaChatResponse crep = null;
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		if(chatConfig != null && promptConfig != null) {
			String key = getKey(user, chatConfig, promptConfig, chatReq);
			crep = new OllamaChatResponse();
			if(reqMap.containsKey(key)) {
				crep = getChatResponse(user, reqMap.get(key), chatReq);
			}
			else if (chatReq.getSessionName() != null){
				String sessionName = ChatUtil.getSessionName(user, chatConfig, promptConfig, chatReq.getSessionName());
				OllamaRequest oreq = ChatUtil.getSession(user, sessionName);
				if(oreq != null) {
					crep = getChatResponse(user, oreq, chatReq);
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
	
	/*
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/character/{name:[\\.A-Za-z\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getCharactor(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chart = getCharacter(user, name);
		
		return Response.status((chart != null ? 200 : 404)).entity((chart != null ? chart.toFullString() : null)).build();
	}
	*/
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/prompt/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getPromptConfig(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, null, name);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}
	
	private BaseRecord getConfig(BaseRecord user, String modelName, String objectId, String name) {
		if(objectId != null && configMap.containsKey(objectId)) {
			return configMap.get(objectId);
		}
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord cfg = null;
		if(dir != null) {
			Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			if(name != null) {
				q.field(FieldNames.FIELD_NAME, name);
			}
			if(objectId != null) {
				q.field(FieldNames.FIELD_OBJECT_ID, objectId);
			}
			q.planMost(true);
			cfg = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(objectId != null && cfg != null) {
				configMap.put(objectId, cfg);
			}
		}
		return cfg;
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/chat/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getChatConfig(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, name);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/text")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chatRPG(String json, @Context HttpServletRequest request){
		OllamaChatRequest chatReq = JSONUtil.importObject(json, OllamaChatRequest.class);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return Response.status(404).entity(null).build();
		}
		if(chatTrack.contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		chatTrack.add(chatReq.getUid());
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		
		OllamaRequest req = getOllamaRequest(user, chatReq);
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		///logger.info(JSONUtil.exportObject(req));
		// if(chatReq.getMessage() != null) {
			//String key = user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get("systemCharacter.name") + "-" + chatConfig.get("userCharacter.name");
			String key = getKey(user, chatConfig, promptConfig, chatReq); 
			logger.info("Chat request: " + key);
			Chat chat = getChat(user, chatReq, key);
			chat.continueChat(req, chatReq.getMessage());
			if(chatReq.getMessage().startsWith("/next")) {
				/// Dump the request from the cache when moving episodes
				forgetRequest(user, chatReq);
			}
		// }
		OllamaChatResponse creq = getChatResponse(user, req, chatReq);
		// logger.info("Chat Response:");
		// logger.info((creq != null ? JSONUtil.exportObject(creq) : null));
		
		return Response.status((creq != null ? 200 : 404)).entity(creq).build();
	}
	
	private OllamaChatResponse getChatResponse(BaseRecord user, OllamaRequest req, OllamaChatRequest creq) {
		if(req == null || creq == null) {
			return null;
		}
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
		OllamaChatResponse rep = new OllamaChatResponse();
		rep.setUid(creq.getUid());
		rep.setModel(chatConfig.get("llmModel"));
		/// Current template structure for chat and rpg defines prompt and initial user message
		/// Skip prompt, and skip initial user comment
		int startIndex = 2;
		/// With assist enabled, an initial assistant message is included, so skip that as well
		if((boolean)chatConfig.get("assist")) {
			startIndex++;
		}
		for(int i = startIndex; i < req.getMessages().size(); i++) {
			rep.getMessages().add(req.getMessages().get(i));
		}
		return rep;
	}

	private void forgetRequest(BaseRecord user, OllamaChatRequest creq) {
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, creq.getPromptConfig(), null);
		String key = getKey(user, chatConfig, promptConfig, creq);
		if(reqMap.containsKey(key)) {
			reqMap.remove(key);
		}
	}
	
	private OllamaRequest getOllamaRequest(BaseRecord user, OllamaChatRequest creq) {
		OllamaRequest req = null;
		Chat chat = null;
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, creq.getPromptConfig(), null);
		String key = getKey(user, chatConfig, promptConfig, creq);
		//String key = user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get(FieldNames.FIELD_NAME) + "-" + promptConfig.get(FieldNames.FIELD_NAME);
		if(reqMap.containsKey(key)) {
			return reqMap.get(key);
		}
		if(chatConfig != null && promptConfig != null) {

			if(creq.getSessionName() != null && creq.getSessionName().length() > 0) {
				String sessionName = ChatUtil.getSessionName(user, chatConfig, promptConfig, creq.getSessionName());
				OllamaRequest oreq = ChatUtil.getSession(user, sessionName);
				if(oreq != null) {
					req = oreq;
				}
			}
			if(req == null) {
				chat = new Chat(user, chatConfig, promptConfig);
				chat.setSessionName(creq.getSessionName());
				req = chat.getChatPrompt();
			}
			if(req != null) {
				reqMap.put(key, req);
			}

		}
		return req;
	}
	
	private Chat getChat(BaseRecord user, OllamaChatRequest req, String key) {
		if(chatMap.containsKey(key)) {
			return chatMap.get(key);
		}
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, req.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, req.getPromptConfig(), null);
		Chat chat = null;
		if(chatConfig != null && promptConfig != null) {
			chat = new Chat(user, chatConfig, promptConfig);
			if(req.getSessionName() != null && req.getSessionName().length() > 0) {
				chat.setSessionName(req.getSessionName());
			}
			chatMap.put(key,  chat);
		}
		return chat;
	}

	private String getCreateUserPrompt(BaseRecord user, String name) {
		BaseRecord dat = getCreatePromptData(user, name);
		IOSystem.getActiveContext().getReader().populate(dat, new String[] {FieldNames.FIELD_BYTE_STORE});
		return new String((byte[])dat.get(FieldNames.FIELD_BYTE_STORE));
	}
	
	private BaseRecord getCreatePromptData(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord dat = IOSystem.getActiveContext().getRecordUtil().getRecord(user, ModelNames.MODEL_DATA, name, 0L, (long)dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dat == null) {
			dat = newPromptData(user, name, ResourceUtil.getInstance().getResource("olio/llm/chat.config.json"));
			IOSystem.getActiveContext().getRecordUtil().createRecord(dat);
		}
		return dat;
	}
	private BaseRecord newPromptData(BaseRecord user, String name, String data) {
		BaseRecord rec = null;
		boolean error = false;
		try {
			rec = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, rec, name, "~/chat", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			rec.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			rec.set(FieldNames.FIELD_BYTE_STORE, data.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			logger.error(e);
			
			error = true;
		}
		return rec;
	}

}
