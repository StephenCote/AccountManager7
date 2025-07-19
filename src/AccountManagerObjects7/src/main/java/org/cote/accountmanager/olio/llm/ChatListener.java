package org.cote.accountmanager.olio.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

import jakarta.ws.rs.core.Response;

public class ChatListener implements IChatListener {
	private static final Logger logger = LogManager.getLogger(ChatListener.class);
	private static Map<String, OpenAIRequest> asyncRequests = new ConcurrentHashMap<>();
	private boolean deferRemote = false;
	
	public ChatListener() {
		
	}
	
	
	
	public boolean isDeferRemote() {
		return deferRemote;
	}



	public void setDeferRemote(boolean deferRemote) {
		this.deferRemote = deferRemote;
	}



	@Override
	public void sendMessageToClient(BaseRecord user, OpenAIRequest request, String message) {
		logger.info("sendMessageToClient: " + message);
	}

	@Override
	public void sendMessageToServer(BaseRecord user, ChatRequest chatReq) {
		logger.info("sendMessageToServer");
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return;
		}
		if(ChatUtil.getChatTrack().contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return;
		}
		ChatUtil.getChatTrack().add(chatReq.getUid());

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatReq.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		ChatRequest vChatReq = new ChatRequest(IOSystem.getActiveContext().getAccessPoint().find(user, q));
		
		vChatReq.setValue(FieldNames.FIELD_DATA, chatReq.get(FieldNames.FIELD_DATA));
		vChatReq.setValue(FieldNames.FIELD_MESSAGE, chatReq.get(FieldNames.FIELD_MESSAGE));
		
		OpenAIRequest req = ChatUtil.getOpenAIRequest(user, vChatReq);
		if(false == (boolean)req.get("stream")) {
			logger.warn("Chat request is not a stream request - forcing to stream");
			req.setValue("stream", true);
		}

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
		
		Chat chat = ChatUtil.getChat(user, vChatReq, deferRemote);	
		chat.setListener(this);
		
		String oid = req.get(FieldNames.FIELD_OBJECT_ID);
		if (oid == null || oid.length() == 0) {
			logger.warn("Request does not have an object id");
		}
		else {
			asyncRequests.put(oid, req);
		}
		
		chat.continueChat(req, vChatReq.getMessage() + citRef);
		
		
	}

	@Override
	public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest) {
		System.out.println("MockWebSocket.getMessage: " + messageRequest);
		return messageRequest;
	}

	@Override
	public boolean isStopStream(OpenAIRequest request) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
		// TODO Auto-generated method stub
		logger.info("MockWebSocket.oncomplete");
		
	}

}
