package org.cote.rest.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

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

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaChatRequest;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/chat")
public class ChatService {
	@Context
	ServletContext context;
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/rpg")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response chatRPG(String json, @Context HttpServletRequest request){
		OllamaChatRequest chatReq = JSONUtil.importObject(json, OllamaChatRequest.class);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OllamaRequest req = getOllamaRequest(user, chatReq.getSystemCharacter(), chatReq.getUserCharacter(), chatReq.getRating());
		if(chatReq.getMessage() != null) {
			String key = user.get(FieldNames.FIELD_NAME) + "-" + chatReq.getSystemCharacter() + "-" + chatReq.getUserCharacter();
			Chat chat = getChat(user, chatReq.getRating(), key);
			chat.continueChat(req, chatReq.getMessage());
		}
		return Response.status(200).entity(req).build();
	}
	
	private HashMap<String, OllamaRequest> reqMap = new HashMap<>();
	private HashMap<String, Chat> chatMap = new HashMap<>();
	
	private OllamaRequest getOllamaRequest(BaseRecord user, String systemName, String userName, ESRBEnumType rating) {
		String key = user.get(FieldNames.FIELD_NAME) + "-" + systemName + "-" + userName;
		if(reqMap.containsKey(key)) {
			return reqMap.get(key);
		}
		
		OlioContext octx = null;
		BaseRecord epoch = null;
		List<BaseRecord> pop = new ArrayList<>();
		BaseRecord char1 = null;
		BaseRecord char2 = null;
		BaseRecord inter = null;
		BaseRecord evt = null;
		BaseRecord cevt = null;

		NarrativeUtil.setDescribePatterns(false);
		NarrativeUtil.setDescribeFabrics(false);
		octx = OlioContextUtil.getGridContext(user, context.getInitParameter("datagen.path"), "My Grid Universe", "My Grid World", false);
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
		Chat chat = getChat(user, rating, key);

		String prompt = "You are assistant, a superhelpful friend to all.";

		OllamaRequest req = chat.getChatPrompt(octx, prompt, null, evt, cevt, char1, char2, inter);
		reqMap.put(key, req);
		octx.processQueue();
		octx.clearCache();
		/// chat.continueChat(req, null);
		return req;
	}
	
	private Chat getChat(BaseRecord user, ESRBEnumType rating, String key) {
		if(chatMap.containsKey(key)) {
			return chatMap.get(key);
		}
		Chat chat = new Chat(user);
		chat.setIncludeScene(true);
		chat.setRating(rating);
		chat.setRandomSetting(true);
	
		String model = "llama2-uncensored:7b-chat-q8_0";
		chat.setModel(model);
		
		chatMap.put(key, chat);
		return chat;
	
	}

}
