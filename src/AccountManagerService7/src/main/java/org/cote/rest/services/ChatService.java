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
	
	private static Set<String> chatTrack = new HashSet<>();
	private static HashMap<String, OpenAIRequest> reqMap = new HashMap<>();
	private static HashMap<String, Chat> chatMap = new HashMap<>();
	private static HashMap<String, BaseRecord> configMap = new HashMap<>();
	
	public static void clearCache() {
		chatTrack.clear();
		reqMap.clear();
		configMap.clear();
	}
	
	private String getKey(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, ChatRequest request) {
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
		ChatRequest chatReq = ChatRequest.importRecord(json);
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
		ChatRequest chatReq = ChatRequest.importRecord(json);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			logger.warn(JSONUtil.exportObject(chatReq));
			return Response.status(404).entity(null).build();
		}
		if(chatTrack.contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		ChatResponse crep = null;
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		if(chatConfig != null && promptConfig != null) {
			String key = getKey(user, chatConfig, promptConfig, chatReq);
			crep = new ChatResponse();
			if(reqMap.containsKey(key)) {
				crep = getChatResponse(user, reqMap.get(key), chatReq);
			}
			else if (chatReq.getSessionName() != null){
				String sessionName = ChatUtil.getSessionName(user, chatConfig, promptConfig, chatReq.getSessionName());
				OpenAIRequest oreq = ChatUtil.getSession(user, sessionName);
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
	
	private Map<String, String> objectSummary = new HashMap<>();
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/text")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chatRPG(String json, @Context HttpServletRequest request){
		ChatRequest chatReq = ChatRequest.importRecord(json);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return Response.status(404).entity(null).build();
		}
		if(chatTrack.contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return Response.status(404).entity(null).build();
		}
		chatTrack.add(chatReq.getUid());
		
		OpenAIRequest req = getOpenAIRequest(user, chatReq);
		String citRef = "";
		if(chatReq.getMessage() != null && chatReq.getMessage().length() > 0) {
			List<String> cits = getDataCitations(user, req, chatReq);
			if(cits.size() > 0) {
				citRef = System.lineSeparator() + cits.stream().collect(Collectors.joining(System.lineSeparator()));
			}
		}
		
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, chatReq.getPromptConfig(), null);
		///logger.info(JSONUtil.exportObject(req));
		// if(chatReq.getMessage() != null) {
			//String key = user.get(FieldNames.FIELD_NAME) + "-" + chatConfig.get("systemCharacter.name") + "-" + chatConfig.get("userCharacter.name");
			String key = getKey(user, chatConfig, promptConfig, chatReq); 
			logger.info("Chat request: " + key);
			Chat chat = getChat(user, chatReq, key);
			/*
			boolean defer = Boolean.parseBoolean(context.getInitParameter("task.defer.remote"));
			if(defer) {
				if(chatReq.getMessage() != null && chatReq.getMessage().length() > 0) {
					chat.newMessage(req, chatReq.getMessage());
				}
				BaseRecord task = OlioTaskAgent.createTaskRequest(req, chatConfig.copyRecord(new String[]{"apiVersion", "serviceType", "serverUrl", "apiKey", "model"}));
				BaseRecord rtask = OlioTaskAgent.executeTask(task);
				if(rtask != null) {
					BaseRecord resp = rtask.get("taskModel");
					if(resp != null) {
						chat.handleResponse(req, new OpenAIResponse(resp), false);
						chat.saveSession(req);
					}
					else {
						logger.error("Task response was null");
					}
				}
			}
			else {
			*/
				chat.continueChat(req, chatReq.getMessage() + citRef);
			//}
			if(chatReq.getMessage().startsWith("/next")) {
				/// Dump the request from the cache when moving episodes
				forgetRequest(user, chatReq);
			}
		// }
		ChatResponse creq = getChatResponse(user, req, chatReq);
		// logger.info("Chat Response:");
		// logger.info((creq != null ? JSONUtil.exportObject(creq) : null));
		
		return Response.status((creq != null ? 200 : 404)).entity(creq).build();
	}
	
	private String getFilteredCitationText(OpenAIRequest req, BaseRecord recRef, String type, int chunk, String content) {
		if(content == null || content.length() == 0) {
			return null;
		}
		String citationKey = "<citation schema=\"" + recRef.getSchema() + "\" type = \"" + type + "\" chunk = \"" + chunk + "\" id = \"" + recRef.get(FieldNames.FIELD_OBJECT_ID) + "\">";
		String citationSuff = "</citation>";
		List<OpenAIMessage> msgs = req.getMessages().stream().filter(m -> m.getContent() != null && m.getContent().contains(citationKey)).collect(Collectors.toList());
		String citation = null;
		if(msgs.size() == 0) {
			citation = citationKey + content + citationSuff;
		}
		return citation;
	}
	
	private List<String> getDataCitations(BaseRecord user, OpenAIRequest req, ChatRequest creq) {
		List<String> dataRef = creq.get(FieldNames.FIELD_DATA);
		List<String> dataCit = new ArrayList<>();
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		for(String dataR : dataRef) {
			BaseRecord recRef = RecordFactory.importRecord(dataR);

			
			String objId = recRef.get(FieldNames.FIELD_OBJECT_ID);


			String summary = null;
			if(objectSummary.containsKey(objId)) {
				summary = objectSummary.get(objId);
			}
			else {
				Query rq = QueryUtil.createQuery(recRef.getSchema(), FieldNames.FIELD_OBJECT_ID, objId);
				rq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
				rq.planMost(false);
				BaseRecord frec = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
				if(frec != null) {
					String content = DocumentUtil.getStringContent(frec);
					if(content != null) {
						summary = content;
						objectSummary.put(objId, content);
						/*
						summary = vu.getEmbedUtil().getSummary(content);
						if(summary != null) {
							objectSummary.put(objId, summary);
						}
						else {
							logger.warn("Summary was null");
						}
						*/
					}
					else {
						logger.warn("Content was null for " + recRef.getSchema() + " " + objId);
					}
					
				}
			}
			if(summary != null) {
				dataCit.add(getFilteredCitationText(req, recRef, "summary", 0, summary));
			}
			List<BaseRecord> vects = new ArrayList<>();
			String msg = creq.getMessage();
			if(msg != null && msg.length() > 0) {
				if(recRef.getSchema().equals(OlioModelNames.MODEL_CHAR_PERSON)) {
					vects.addAll(vu.find(null, ModelNames.MODEL_DATA, new String[] {OlioModelNames.MODEL_VECTOR_CHAT_HISTORY}, msg, 5, 0.6));
				}
				else {
					/// Skip looking up vectorized char person since the summary will include the narrative description
					///
					vects.addAll(vu.find(recRef, recRef.getSchema(), msg, 3, 0.6));
				}
			}

			for(BaseRecord vect : vects) {
				/*
				BaseRecord chunk = RecordFactory.importRecord(ModelNames.MODEL_VECTOR_CHUNK, vect.get("content"));
				if(chunk == null) {
					logger.error(vect.toFullString());
				}
				else {
				*/
					String txt = getFilteredCitationText(req, recRef, "chunk", vect.get("chunk"), vect.get("content"));
					if(txt != null) {
						dataCit.add(txt);
					}
				//}
			}

		}
		return dataCit;
	}
	
	private ChatResponse getChatResponse(BaseRecord user, OpenAIRequest req, ChatRequest creq) {
		if(req == null || creq == null) {
			return null;
		}
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
		ChatResponse rep = new ChatResponse();
		rep.setUid(creq.getUid());
		rep.setModel(chatConfig.get("model"));
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

	private void forgetRequest(BaseRecord user, ChatRequest creq) {
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, creq.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, creq.getPromptConfig(), null);
		String key = getKey(user, chatConfig, promptConfig, creq);
		if(reqMap.containsKey(key)) {
			reqMap.remove(key);
		}
	}
	
	private OpenAIRequest getOpenAIRequest(BaseRecord user, ChatRequest creq) {
		OpenAIRequest req = null;
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
				OpenAIRequest oreq = ChatUtil.getSession(user, sessionName);
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
	
	private Chat getChat(BaseRecord user, ChatRequest req, String key) {
		if(chatMap.containsKey(key)) {
			return chatMap.get(key);
		}
		BaseRecord chatConfig = getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, req.getChatConfig(), null);
		BaseRecord promptConfig = getConfig(user, OlioModelNames.MODEL_PROMPT_CONFIG, req.getPromptConfig(), null);
		Chat chat = null;
		if(chatConfig != null && promptConfig != null) {
			chat = new Chat(user, chatConfig, promptConfig);
			chat.setDeferRemote(Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));
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
