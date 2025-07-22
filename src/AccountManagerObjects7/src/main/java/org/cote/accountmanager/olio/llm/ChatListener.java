package org.cote.accountmanager.olio.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
	private static Map<String, Chat> asyncChats = new ConcurrentHashMap<>();
	private static Map<String, OpenAIRequest> asyncRequests = new ConcurrentHashMap<>();
	private static Map<String, Integer> asyncRequestCount = new ConcurrentHashMap<>();
	private static Map<String, Boolean> asyncRequestStop = new ConcurrentHashMap<>();
	private boolean deferRemote = false;
	private int maximumResponseTokens = -1;
	private List<IChatHandler> handlers = new CopyOnWriteArrayList<>();
	
	public ChatListener() {
		
	}
	
	@Override
	public void addChatHandler(IChatHandler handler) {
		handlers.add(handler);
	}
	
	
	public boolean isDeferRemote() {
		return deferRemote;
	}



	public void setDeferRemote(boolean deferRemote) {
		this.deferRemote = deferRemote;
	}

	private void clearCache(String oid) {
		asyncRequests.remove(oid);
		asyncRequestCount.remove(oid);
		asyncRequestStop.remove(oid);
		asyncChats.remove(oid);
	}


	@Override
	public OpenAIRequest sendMessageToServer(BaseRecord user, ChatRequest chatReq) {
		logger.info("sendMessageToServer");
		if(chatReq.getUid() == null) {
			logger.warn("A uid is required for every chat");
			return null;
		}
		if(ChatUtil.getChatTrack().contains(chatReq.getUid())) {
			logger.warn("Uid already used in a chat");
			return null;
		}
		ChatUtil.getChatTrack().add(chatReq.getUid());

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, chatReq.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		ChatRequest vChatReq = new ChatRequest(IOSystem.getActiveContext().getAccessPoint().find(user, q));
		
		vChatReq.setValue(FieldNames.FIELD_DATA, chatReq.get(FieldNames.FIELD_DATA));
		vChatReq.setValue(FieldNames.FIELD_MESSAGE, chatReq.get(FieldNames.FIELD_MESSAGE));
		
		OpenAIRequest req = ChatUtil.getOpenAIRequest(user, vChatReq);
		if(req == null) {
			logger.error("Failed to create OpenAIRequest from chat request.");
			return null;
		}
		
		String oid = req.get(FieldNames.FIELD_OBJECT_ID);
		if (oid == null || oid.length() == 0) {
			logger.warn("Request does not have an object id");
			return null;
		}
		logger.info("Chat request object id: " + oid);
		
		if(false == (boolean)req.get("stream")) {
			logger.warn("Chat request is not a stream request - forcing to stream");
			req.setValue("stream", true);
		}
		
		if(vChatReq.getMessage() != null && vChatReq.getMessage().equals("[stop]") && asyncRequests.containsKey(oid)) {
			logger.info("Stopping chat request: " + oid);
			asyncRequestStop.put(oid, true);
			return req;
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
		
		asyncRequests.put(oid, req);
		asyncRequestCount.put(oid, 0);
		asyncRequestStop.put(oid, false);
		asyncChats.put(oid, chat);

		chat.continueChat(req, vChatReq.getMessage() + citRef);
		
		handlers.forEach(h -> h.onChatStart(user, chatReq, req));
		
		return req;
	}

	@Override
	public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest) {
		System.out.println("MockWebSocket.getMessage: " + messageRequest);
		return messageRequest;
	}

	@Override
	public boolean isStopStream(OpenAIRequest request) {
		String oid = getRequestId(request);
		if(oid == null) {
			return false;
		}

		if(!asyncRequests.containsKey(oid)) {
            logger.warn("OpenAIRequest object id not found in async requests: " + oid);
            return false;
        }
		return asyncRequestStop.get(oid);
	}
	
	@Override
	public void stopStream(OpenAIRequest request) {
		String oid = getRequestId(request);
		if(oid == null) {
			return;
		}
		
		if(!asyncRequests.containsKey(oid)) {
            logger.warn("OpenAIRequest object id not found in async requests: " + oid);
            return;
        }
		asyncRequestStop.put(oid, true);
	}
	
	private String getRequestId(OpenAIRequest request) {
		if (request == null) {
			logger.warn("OpenAIRequest is null");
			return null;
		}
		
		String oid = request.get(FieldNames.FIELD_OBJECT_ID);
		if(oid == null) {
			logger.warn("OpenAIRequest does not have an object id");
			return null;
		}
		return oid;
	}

	@Override
	public void onupdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message) {
		logger.info("sendMessageToClient: '" + message + "'");
		String oid = getRequestId(request);
		if(oid == null) {
			return;
		}
		int tokenCount = 0;
		if (!asyncRequests.containsKey(oid)) {
			logger.warn("OpenAIRequest object id not found in async requests: " + oid);
		}
		else {
			tokenCount = asyncRequestCount.get(oid) + 1;
		}
		asyncRequestCount.put(oid, tokenCount);
		if (maximumResponseTokens > -1 && tokenCount >= maximumResponseTokens) {
			logger.info("Maximum response tokens reached for request: " + oid + " (" + tokenCount + ") - Stopping stream");
			stopStream(request);
		}
		handlers.forEach(h -> h.onChatUpdate(user, request, response, message));

	}

	@Override
	public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
		// TODO Auto-generated method stub
		logger.info("MockWebSocket.oncomplete");
		String oid = getRequestId(request);
		if(oid == null) {
			return;
		}

		if (!asyncRequests.containsKey(oid)) {
			logger.warn("OpenAIRequest object id not found in async requests: " + oid);
			return;
		}
		Chat chat = asyncChats.get(oid);
		chat.handleResponse(request, response, false);

		if(asyncRequestStop.containsKey(oid) && asyncRequestStop.containsKey(oid) == true) {
			List<BaseRecord> msgs = request.get("messages");
			if(msgs != null && msgs.size() > 0) {
				BaseRecord lastMsg = msgs.get(msgs.size() - 1);
				if(lastMsg.get("content") != null) {
					lastMsg.setValue("content", lastMsg.get("content") + System.lineSeparator() + "[interrupted]");
				}
			}
		}		

		chat.saveSession(request);
		logger.info("Chat session saved for request: " + oid);
		logger.info(request.toFullString());
		handlers.forEach(h -> h.onChatComplete(user, request, response));
		clearCache(oid);
	}


	@Override
	public boolean isRequesting(OpenAIRequest request) {
		if (request == null) {
			logger.warn("OpenAIRequest is null");
			return false;
		}
		
		String oid = request.get(FieldNames.FIELD_OBJECT_ID);
		if(oid == null) {
			logger.warn("OpenAIRequest does not have an object id");
			return false;
		}
		return asyncRequests.containsKey(oid);
	}



	@Override
	public void onerror(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg) {
		// TODO Auto-generated method stub
		logger.error("Error received: " + msg);
		if (request == null) {
			logger.warn("OpenAIRequest is null");
			return;
		}
		
		String oid = request.get(FieldNames.FIELD_OBJECT_ID);
		if(oid == null) {
			logger.warn("OpenAIRequest does not have an object id");
			return;
		}
		handlers.forEach(h -> h.onChatError(user, request, response, msg));
		clearCache(oid);
		
	}

}
