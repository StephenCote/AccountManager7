package org.cote.rest.services;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaChatRequest;
import org.cote.accountmanager.olio.llm.OllamaChatResponse;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.PromptConfiguration;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.FileUtil;
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
	//private static HashMap<String, BaseRecord> charMap = new HashMap<>();
	private static HashMap<String, BaseRecord> configMap = new HashMap<>();
	//private static HashMap<String, List<BaseRecord>> popMap = new HashMap<>();
	//private static HashMap<String, BaseRecord> dataMap = new HashMap<>();
	
	private String getKey(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, OllamaChatRequest request) {
		String sess = "";
		if(request.getSessionName() != null && request.getSessionName().length() > 0) {
			sess = "-" + request.getSessionName();
		}
		return user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get("name") + "-" + promptConfig.get("name") + sess;
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
		BaseRecord chatConfig = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, ModelNames.MODEL_PROMPT_CONFIG, chatReq.getChatConfig(), null);
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
		BaseRecord chatConfig = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, ModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		if(chatConfig != null && promptConfig != null) {
			String key = getKey(user, chatConfig, promptConfig, chatReq);
			crep = new OllamaChatResponse();
			if(reqMap.containsKey(key)) {
				crep = getChatResponse(user, reqMap.get(key), chatReq);
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
	public Response getSystemPrompt(@PathParam("name") String name, @Context HttpServletRequest request){
		return Response.status(200).entity(ChatUtil.getDefaultPrompt().toFullString()).build();
	}
	
	/*
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/character/{name:[\\.A-Za-z\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getCharactor(@PathParam("name") String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chart = getCharacter(user, name);
		
		return Response.status((chart != null ? 200 : 404)).entity((chart != null ? chart.toFullString() : null)).build();
	}
	*/
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/config/prompt/{name:[\\.A-Za-z0-9%\\s]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getPromptConfig(@PathParam("name") String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = getConfig(user, ModelNames.MODEL_PROMPT_CONFIG, null, name);
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
			q.setValue(FieldNames.FIELD_LIMIT_FIELDS, false);
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
	public Response getChatConfig(@PathParam("name") String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord cfg = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, null, name);
		return Response.status((cfg != null ? 200 : 404)).entity((cfg != null ? cfg.toFullString() : null)).build();
	}
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/text")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chatRPG(String json, @Context HttpServletRequest request){
		// logger.info(json);
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
		BaseRecord chatConfig = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, ModelNames.MODEL_PROMPT_CONFIG, chatReq.getChatConfig(), null);
		///logger.info(JSONUtil.exportObject(req));
		// if(chatReq.getMessage() != null) {
			//String key = user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get("systemCharacter.name") + "-" + chatConfig.get("userCharacter.name");
			String key = getKey(user, chatConfig, promptConfig, chatReq); 
			logger.info("Chat request: " + key);
			Chat chat = getChat(user, chatReq, key);
			chat.continueChat(req, chatReq.getMessage());
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
		BaseRecord chatConfig = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
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
	/*
	private BaseRecord getCharacter(BaseRecord user, String name) {
		String uname = user.get(FieldNames.FIELD_NAME);
		String key = uname + "-" + name;
		
		if(chatMap.containsKey(key)) {
			return charMap.get(key);
		}
		BaseRecord chart = null;
		List<BaseRecord> pop = new ArrayList<>();
		if(!popMap.containsKey(uname)) {
			OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
			octx.startOrContinueEpoch();
			BaseRecord[] locs = octx.getLocations();
			for(BaseRecord lrec : locs) {
				octx.startOrContinueLocationEpoch(lrec);
				octx.startOrContinueIncrement();
				octx.evaluateIncrement();
				pop.addAll(octx.getPopulation(lrec));
				/// Depending on the staging rule, the population may not yet be dressed or have possessions
				///
				ApparelUtil.outfitAndStage(octx, null, pop);
				ItemUtil.showerWithMoney(octx, pop);
				octx.processQueue();
			}
			popMap.put(uname, pop);
		}
		else {
			pop = popMap.get(uname);
		}
		Optional<BaseRecord> brec = pop.stream().filter(r -> name.equals(r.get("firstName"))).findFirst();
		if(brec.isPresent()) {
			chart = brec.get();
		}
		return chart;
	}
	*/
	private OllamaRequest getOllamaRequest(BaseRecord user, OllamaChatRequest creq) {
		OllamaRequest req = null;
		Chat chat = null;
		BaseRecord chatConfig = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, ModelNames.MODEL_PROMPT_CONFIG, creq.getPromptConfig(), null);
		String key = getKey(user, chatConfig, promptConfig, creq);
		//String key = user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get("name") + "-" + promptConfig.get("name");
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
	
	/*
	private OllamaRequest getOllamaRequest(BaseRecord user, OllamaChatRequest creq) {
		String systemName = creq.getSystemCharacter();
		String userName = creq.getUserCharacter();
		ESRBEnumType rating = creq.getRating();
		String key = user.get(FieldNames.FIELD_NAME) + "-" + systemName + "-" + userName;
		//logger.info("Chat: " + key + " " + (reqMap.containsKey(key) ? "continues":"begins"));
		if(reqMap.containsKey(key)) {
			return reqMap.get(key);
		}
		
		OlioContext octx = OlioContextUtil.getOlioContext(user, context.getInitParameter("datagen.path"));
		BaseRecord epoch = null;
		List<BaseRecord> pop = new ArrayList<>();
		BaseRecord char1 = null;
		BaseRecord char2 = null;
		BaseRecord inter = null;
		BaseRecord evt = null;
		BaseRecord cevt = null;

		NarrativeUtil.setDescribePatterns(false);
		NarrativeUtil.setDescribeFabrics(false);
		// octx = OlioContextUtil.getGridContext(user, context.getInitParameter("datagen.path"), "My Grid Universe", "My Grid World", false);
		epoch = octx.startOrContinueEpoch();
		BaseRecord[] locs = octx.getLocations();
		for(BaseRecord lrec : locs) {
			evt = octx.startOrContinueLocationEpoch(lrec);
			cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();
			pop.addAll(octx.getPopulation(lrec));
			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(octx, null, pop);
			ItemUtil.showerWithMoney(octx, pop);
			octx.processQueue();
			
		}

		Optional<BaseRecord> brec = pop.stream().filter(r -> systemName.equals(r.get("firstName"))).findFirst();
		if(brec.isPresent()) {
			char1 = brec.get();
		}

		Optional<BaseRecord> brec2 = pop.stream().filter(r -> userName.equals(r.get("firstName"))).findFirst();
		if(brec2.isPresent()) {
			char2 = brec2.get();
		}
		
		if(char1 != null && char2 != null) {
			for(int i = 0; i < 10; i++) {
				inter = InteractionUtil.randomInteraction(octx, char1, char2);
				if(inter != null) {
					break;
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecord(inter);
		}
		ChatBAK chat = getChat(user, creq, key);

		String prompt = "You are assistant, a superhelpful friend to all.";

		OllamaRequest req = chat.getChatPrompt(octx, prompt, null, evt, cevt, char1, char2, inter, false);
		reqMap.put(key, req);
		octx.processQueue();
		octx.clearCache();
		/// chat.continueChat(req, null);
		return req;
	}
	*/
	private Chat getChat(BaseRecord user, OllamaChatRequest req, String key) {
		if(chatMap.containsKey(key)) {
			return chatMap.get(key);
		}
		BaseRecord chatConfig = getConfig(user, ModelNames.MODEL_CHAT_CONFIG, req.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, ModelNames.MODEL_PROMPT_CONFIG, req.getPromptConfig(), null);
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
	/*
	private ChatBAK getChat(BaseRecord user, OllamaChatRequest req, String key) {
		if(chatMap.containsKey(key)) {
			return chatMap.get(key);
		}
		PromptConfiguration pc = null;
		if(req.getUserPrompt() != null) {
			pc = JSONUtil.importObject(getCreateUserPrompt(user, req.getUserPrompt()), PromptConfiguration.class);
		}
		else {
			pc = JSONUtil.importObject(ResourceUtil.getResource("olio/llm/chat.config.json"), PromptConfiguration.class);
		}
		if(pc == null) {
			logger.error("Failed to load prompt configuration");
			return null;
		}

		ChatBAK chat = new ChatBAK(user);
		chat.setPromptConfig(pc);
		chat.setRating(req.getRating());
		chat.setSettingStr(NarrativeUtil.getRandomSetting());
		chat.setIncludeScene(true);
		chat.setUseAssist(req.isAssist());
		chat.setUseNLP(req.isUseNLP());
		String model = req.getModel();
		if(model == null || model.length() == 0) {
			//model = "dolphin-llama3:8b-256k-v2.9-q5_K_M";
			model = "dolphin-llama3:latest";
			//model = "llama2-uncensored:7b-chat-q8_0";
		}
		chat.setModel(model);
		
		chatMap.put(key, chat);
		return chat;
	
	}
	*/
	private String getCreateUserPrompt(BaseRecord user, String name) {
		BaseRecord dat = getCreatePromptData(user, name);
		IOSystem.getActiveContext().getReader().populate(dat, new String[] {FieldNames.FIELD_BYTE_STORE});
		return new String((byte[])dat.get(FieldNames.FIELD_BYTE_STORE));
	}
	
	private BaseRecord getCreatePromptData(BaseRecord user, String name) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord dat = IOSystem.getActiveContext().getRecordUtil().getRecord(user, ModelNames.MODEL_DATA, name, 0L, (long)dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dat == null) {
			dat = newPromptData(user, name, ResourceUtil.getResource("olio/llm/chat.config.json"));
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
