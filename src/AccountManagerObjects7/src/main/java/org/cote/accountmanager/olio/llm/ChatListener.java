package org.cote.accountmanager.olio.llm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyEvaluationResult;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class ChatListener implements IChatListener {
	private static final Logger logger = LogManager.getLogger(ChatListener.class);
	private static Map<String, Chat> asyncChats = new ConcurrentHashMap<>();
	private static Map<String, OpenAIRequest> asyncRequests = new ConcurrentHashMap<>();
	private static Map<String, Integer> asyncRequestCount = new ConcurrentHashMap<>();
	private static Map<String, Boolean> asyncRequestStop = new ConcurrentHashMap<>();
	/// Phase 9: Track stream futures for forced cancellation failover
	private static Map<String, CompletableFuture<?>> asyncStreamFutures = new ConcurrentHashMap<>();
	/// Phase 12: OI-27 — Track response body streams for server-side abort on cancel
	private static Map<String, java.net.http.HttpResponse<?>> asyncHttpResponses = new ConcurrentHashMap<>();
	private static final int FAILOVER_SECONDS = 5;
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
		asyncStreamFutures.remove(oid);
		asyncHttpResponses.remove(oid);
	}

	/// Phase 9: Register a stream future for failover cancellation
	public void registerStreamFuture(String oid, CompletableFuture<?> future) {
		if (oid != null && future != null) {
			asyncStreamFutures.put(oid, future);
		}
	}

	/// Phase 12: OI-27 — Register HTTP response for server-side abort on cancel
	public void registerHttpResponse(String oid, java.net.http.HttpResponse<?> response) {
		if (oid != null && response != null) {
			asyncHttpResponses.put(oid, response);
		}
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

		// Inject prior interaction history between the chat characters
		String interactionCtx = "";
		BaseRecord chatCfg = chat.getChatConfig();
		if (chatCfg != null) {
			BaseRecord sysChar = chatCfg.get("systemCharacter");
			BaseRecord userChar = chatCfg.get("userCharacter");
			if (sysChar != null && userChar != null) {
				// Ensure characters are populated enough for history query and formatting
				IOSystem.getActiveContext().getReader().populate(sysChar, new String[] {FieldNames.FIELD_FIRST_NAME, FieldNames.FIELD_OBJECT_ID});
				IOSystem.getActiveContext().getReader().populate(userChar, new String[] {FieldNames.FIELD_FIRST_NAME, FieldNames.FIELD_OBJECT_ID});
				interactionCtx = GameUtil.getInteractionHistoryContext(userChar, sysChar);
				if (interactionCtx.length() > 0) {
					interactionCtx = interactionCtx + System.lineSeparator() + System.lineSeparator();
				}
			}
		}

		chat.continueChat(req, interactionCtx + citDesc + vChatReq.getMessage());
		
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
		/// Phase 1: Set graceful stop flag (existing behavior)
		asyncRequestStop.put(oid, true);

		/// Phase 12: OI-27 — Close HTTP response body to signal server-side abort
		/// Closing the response body stream closes the TCP connection, which tells
		/// Ollama to stop generation (best-effort — no /api/abort endpoint exists)
		java.net.http.HttpResponse<?> httpResponse = asyncHttpResponses.get(oid);
		if (httpResponse != null && httpResponse.body() instanceof java.util.stream.BaseStream) {
			try {
				((java.util.stream.BaseStream<?, ?>) httpResponse.body()).close();
				logger.info("Closed HTTP response body stream for server-side abort: " + oid);
			} catch (Exception e) {
				logger.warn("Error closing HTTP response body: " + e.getMessage());
			}
		}

		/// Phase 9: Phase 2 — Schedule forced cancellation after failover window
		CompletableFuture<?> streamFuture = asyncStreamFutures.get(oid);
		if (streamFuture != null) {
			CompletableFuture.delayedExecutor(FAILOVER_SECONDS, TimeUnit.SECONDS)
				.execute(() -> {
					if (!streamFuture.isDone()) {
						logger.warn("Force-cancelling hung stream: " + oid);
						streamFuture.cancel(true);
					}
				});
		}
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
		// logger.info("sendMessageToClient: '" + message + "'");
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

		/// Phase 9: Post-response policy evaluation (streaming mode)
		PolicyEvaluationResult policyResult = chat.evaluateResponsePolicy(request, response);
		if (policyResult != null && !policyResult.isPermitted()) {
			logger.warn("Policy violation in stream response: " + policyResult.getViolationSummary());
			handlers.forEach(h -> h.onPolicyViolation(user, request, response, policyResult));
		}

		chat.saveSession(request);
		logger.info("Chat session saved for request: " + oid);

		/// Phase 13: Auto-generate title after first real user+assistant exchange
		boolean autoTitle = false;
		BaseRecord titleChatCfg = chat.getChatConfig();
		if (titleChatCfg != null) {
			autoTitle = Boolean.TRUE.equals(titleChatCfg.get("autoTitle"));
		}
		int offset = chat.getMessageOffset(request);
		List<BaseRecord> allMsgs = request.get("messages");
		int userMsgCount = 0;
		for (int i = offset; i < allMsgs.size(); i++) {
			String role = allMsgs.get(i).get("role");
			if ("user".equals(role)) userMsgCount++;
		}
		if (autoTitle && userMsgCount == 1) {
			CompletableFuture.runAsync(() -> {
				try {
					String title = chat.generateChatTitle(request);
					if (title != null) {
						Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, oid);
						cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
						BaseRecord chatReqRec = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
						if (chatReqRec != null) {
							chat.setChatTitle(chatReqRec, title);
						}
						handlers.forEach(h -> h.onChatTitle(user, request, title));
					}
				} catch (Exception e) {
					logger.warn("Async title generation failed: " + e.getMessage());
				}
			});
		}

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
