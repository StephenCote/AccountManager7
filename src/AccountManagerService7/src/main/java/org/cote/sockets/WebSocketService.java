package org.cote.sockets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatListener;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.IChatHandler;
import org.cote.accountmanager.olio.llm.IChatListener;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
import org.cote.accountmanager.schema.type.ValueEnumType;
import org.cote.accountmanager.security.AM7SigningKeyLocator;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.JSONUtil;

import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServlet;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/wss", configurator = WebSocketSecurityConfigurator.class)
public class WebSocketService  extends HttpServlet implements IChatHandler {
	private static final long serialVersionUID = 1L;

	public static final Logger logger = LogManager.getLogger(WebSocketService.class);

	private static Map<String, BaseRecord> userMap = Collections.synchronizedMap(new HashMap<>());
	private static Map<String, Session> urnToSession = Collections.synchronizedMap(new HashMap<>());
	private static Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
	
	private IChatListener listener = null;
	public WebSocketService() {
		listener = new ChatListener();
		listener.addChatHandler(this);
	}
	
	public static List<Session> activeSessions(){
		return new ArrayList<>(sessions);
	}
	public static List<BaseRecord> activeUsers(){
		return new ArrayList<>(userMap.values());
	}
	public static Session getSessionByUrn(String urn) {
		return urnToSession.get(urn);
	}
	public static BaseRecord getUserBySession(Session session) {
		BaseRecord user = null;
		if(userMap.containsKey(session.getId())) {
			user = userMap.get(session.getId());
		}
		return user;
	}
	
	private UserPrincipal getPrincipal(Session session) {
	    UserPrincipal principal = null;
		if(session.getUserProperties().containsKey(WebSocketSecurityConfigurator.SESSION_PRINCIPAL)) {
			principal = (UserPrincipal)session.getUserProperties().get(WebSocketSecurityConfigurator.SESSION_PRINCIPAL);
		}
		else {
			// logger.warn("Null principal property");
		}
		return principal;
	}
	
	private BaseRecord getRegisterUser(Session session) {
		BaseRecord user = null;
		if(userMap.containsKey(session.getId())) {
			user = userMap.get(session.getId());
		}
		else {
		    UserPrincipal principal = getPrincipal(session);
		    if(principal != null) {
		    	registerUserSession(session, principal);
		    	user = userMap.get(session.getId());
		    }
		}
		return user;
	}
	
	@OnOpen
	public void onOpen(Session session) {
		logger.info("Opened socket for " + session.getId());
		sessions.add(session);
		BaseRecord user = getRegisterUser(session);
	    if(user != null) {
	    	logger.info("Register user by principal");
	    	sendMessage(session, new String[] {"Authenticated"}, false, true, false);
	    }
	    else {
	    	logger.info("Null principal");
	    	sendMessage(session, new String[] {"Anonymous"}, false, true, false);
	    }
		
	}

	@OnMessage
	public void onMessage(String txt, Session session) throws IOException {
		BaseRecord user = getRegisterUser(session);

		SocketMessage msg = new SocketMessage(JSONUtil.importObject(txt, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()));
		BaseRecord message = msg.getMessage();
		if(message != null) {
			if(user == null) {
				String token = msg.get(FieldNames.FIELD_TOKEN);
				if(token != null) {
			    	String urn = Jwts.parser().keyLocator(new AM7SigningKeyLocator()).build().parseSignedClaims(token).getPayload().getId();
			    	user = IOSystem.getActiveContext().getRecordUtil().getRecordByUrn(null, ModelNames.MODEL_USER, urn);
			    	if(user != null) {
			    		logger.info("Register user by token");
			    		registerUserSession(session, user);
			    	}
			    	else {
			    		logger.warn("Failed to find user based on urn: " + urn);
			    	}
				}
				else {
					logger.warn("Token not defined");
					logger.warn(msg.toFullString());
				}
				if(user == null) {
					logger.error("Null user.  TODO: Add token auth support");
					return;
				}
			}

			long recId = message.get("recipientId");
			String recType = message.get("recipientType");
			//user != null &&
			String urn = user.get(FieldNames.FIELD_URN);
			String uid = user.get(FieldNames.FIELD_OBJECT_ID);

			logger.info("Send message " + message.get(FieldNames.FIELD_NAME) + " to " + recId + " from " + user.get(FieldNames.FIELD_URN) + " (" + uid + ")");
			if(recId > 0L && recType.equals(ModelNames.MODEL_USER)) {
					
				BaseRecord targUser = IOSystem.getActiveContext().getRecordUtil().getRecordById(null, recType, recId);
				if (targUser != null) {
					newMessage(user, targUser, message.get(FieldNames.FIELD_NAME), new String(message.get(FieldNames.FIELD_DATA), StandardCharsets.UTF_8), user);
					if(!targUser.get(FieldNames.FIELD_OBJECT_ID).equals(user.get(FieldNames.FIELD_OBJECT_ID)) && urnToSession.containsKey(targUser.get(FieldNames.FIELD_URN))) {
						logger.info("Let active session for " + targUser.get(FieldNames.FIELD_URN) + " know about their message");
						sendMessage(urnToSession.get(targUser.get(FieldNames.FIELD_URN)));
					}
					else {
						logger.info("No active session for " + targUser.get(FieldNames.FIELD_URN));
					}
				}
				else {
					logger.error("Did not find user #" + recId);
				}
			}
			else {
				BaseRecord smsg = msg.getMessage();
				if(smsg != null && "chat".equals(smsg.get(FieldNames.FIELD_NAME))) {
					handleChatRequest(session, user, msg);
				}
				else {
					logger.warn("Handle message with no recipient");
				}
			}
		}
		else {
			logger.error("User or message was null");
		}
		//session.getBasicRemote().sendText(txt.toUpperCase());
	}
	
	// private static Map<String, IChatListener> listeners = new ConcurrentHashMap<>();

	private void handleChatRequest(Session session, BaseRecord user, SocketMessage msg) {
		BaseRecord smsg = msg.getMessage();
		if (smsg == null) {
			logger.error("Chat request message is null");
			return;
		}
		String chatReqStr = new String((byte[])smsg.get("data"));
		logger.info(chatReqStr);
		ChatRequest chatReq = ChatRequest.importRecord(chatReqStr);
		if(chatReq == null) {
			logger.error("Chat request is null");
			return;
		}
		listener.sendMessageToServer(user, chatReq);
	}

	@OnClose
	public void onClose(CloseReason reason, Session session) {
		//logger.info(String.format("Closing a WebSocket (%s) due to %s", session.getId(), reason.getReasonPhrase()));
		//newMessage(session, "Hangup the phone", "Session ended");
		if(userMap.containsKey(session.getId())){
			cleanupUserSession(userMap.get(session.getId()));
		}
		sessions.remove(session);

	}
	
	@OnError
	public void onError(Session session, Throwable t) {
		logger.error(String.format("Error in WebSocket session %s%n", session == null ? "null" : session.getId()), t);
	}
	
	private void registerUserSession(Session session, BaseRecord userp) {
		if(userp == null) {
			logger.error("User is null");
			return;
		}
		if(!sessions.contains(session)) {
			logger.error("Unknown session: " + session.getId());
			return;
		}
		
		if(userMap.containsKey(session.getId())) {
			logger.warn("User " + userp.get(FieldNames.FIELD_NAME) + " already registered");
		}
		
		BaseRecord org = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, userp.get(FieldNames.FIELD_ORGANIZATION_PATH), null, 0L);
		BaseRecord uuser = IOSystem.getActiveContext().getRecordUtil().getRecord(null, ModelNames.MODEL_USER, userp.get(FieldNames.FIELD_NAME), 0L, 0L, org.get(FieldNames.FIELD_ID));
		if(uuser != null) {
			userMap.put(session.getId(), uuser);
			urnToSession.put(uuser.get(FieldNames.FIELD_URN), session);
		}
	}
	
	private BaseRecord newMessage(Session session, String messageName, String messageContent) {
		BaseRecord user = null;
		if(!userMap.containsKey(session.getId())) {
			return null;
		}
		user = userMap.get(session.getId());
		return newMessage(null, user, messageName, messageContent, null);
	}
	private BaseRecord newMessage(BaseRecord sender, BaseRecord recipient, String messageName, String messageContent, BaseRecord ref) {

		List<BaseRecord> msgs = new ArrayList<>();
		try {
			if(sender == null) {
				OrganizationContext orgC = IOSystem.getActiveContext().getOrganizationContext(recipient.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
				sender = orgC.getDocumentControl();	
			}
			
			BaseRecord msg = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_SPOOL, recipient, null, null);
			msg.set(FieldNames.FIELD_NAME, messageName);
			if(recipient != null) {
				msg.set("recipientId", recipient.get(FieldNames.FIELD_ID));
				msg.set("recipientType", recipient.getSchema());
			}
			if(sender != null) {
				msg.set("senderId", sender.get(FieldNames.FIELD_ID));
				msg.set("senderType", sender.getSchema());
			}
			msg.set(FieldNames.FIELD_DATA, messageContent.getBytes(StandardCharsets.UTF_8));
			msg.set(FieldNames.FIELD_VALUE_TYPE, ValueEnumType.STRING);
			msg.set(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.SPOOLED);
		    GregorianCalendar cal = new GregorianCalendar();
		    cal.setTime(new Date());
		    cal.add(GregorianCalendar.MINUTE, 30);
			msg.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
			msg.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.MESSAGE);
			msg.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.MESSAGE_QUEUE);
			if(ref != null) {
				msg.set(FieldNames.FIELD_REFERENCE_TYPE, ref.getSchema());
				msg.set(FieldNames.FIELD_REFERENCE_ID, ref.get(FieldNames.FIELD_ID));
			}
			boolean added = IOSystem.getActiveContext().getRecordUtil().createRecord(msg);
			if(!added) {
				logger.error("Failed to add message");
			}
			else {
				logger.info("Spooled " + messageName + " for " + recipient.get(FieldNames.FIELD_URN));
			}
			// msgs = mfact.getMessagesFromUserGroup(messageName, SpoolNameEnumType.MESSAGE, SpoolStatusEnumType.SPOOLED, null, recipient);
		}
		catch(FieldException | FactoryException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		/*
		if(msgs.size() > 0) {
			return msgs.get(0);
		}
		*/
		return null;
	}

	private static int countNewMessages(Session session){
		BaseRecord user = null;
		if(!userMap.containsKey(session.getId())) {
			return 0;
		}
		user = userMap.get(session.getId());
		return countNewMessages(user);
	}
	
	private static int countNewMessages(BaseRecord user) {

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/.messages", GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_SPOOL, FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_GROUP_ID, ComparatorEnumType.EQUALS, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.MESSAGE_QUEUE.toString());
		q.field(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.MESSAGE.toString());
		q.field(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.SPOOLED);
		return IOSystem.getActiveContext().getSearch().count(q);
	}
	
	public static boolean chirpUser(BaseRecord user, String[] chirps) {
		if(user == null) {
			logger.error("Null user");
			return false;
		}
		if(!urnToSession.containsKey(user.get(FieldNames.FIELD_URN))) {
			logger.warn("User does not have an active WebSocket");
			return false;
		}
		
		sendMessage(urnToSession.get(user.get(FieldNames.FIELD_URN)), chirps, true, false, true);
		return true;
	}
	
	public static void cleanupUserSession(BaseRecord user) {
		if(user != null) {
			String u2 = user.get(FieldNames.FIELD_URN);
			if(u2 != null) {
				userMap.entrySet().removeIf( u -> {
					if(u.getValue() != null) {
						String u1 = u.getValue().get(FieldNames.FIELD_URN);
						if(u1 != null && u1.equals(u2)) {
							urnToSession.remove(u1);
							return true;
						}
					}
					return false;
				});
			}
		}
	}
	
	public static void sendMessage(Session session) {
		sendMessage(session, new String[0], true, false, false);
	}
	public static void sendMessage(Session session, String[] chirps, boolean count, boolean auth, boolean sync) {
		SocketMessage msg = new SocketMessage();
		


		if (userMap.containsKey(session.getId())) {
			//logger.warn("Session is not mapped to a key");
			BaseRecord user = userMap.get(session.getId());
			//asyncRemote.sendText(builder.build().toString());
			// Send pending messages
			
			//msg.setUser(user.get(FieldNames.FIELD_URN));
			if(count) {
				msg.setNewMessageCount(countNewMessages(session));
			}
			if(auth) {
				msg.setToken(TokenService.createSimpleJWTToken(user));
			}
		}
		msg.getChirps().addAll(Arrays.asList(chirps));
		if(sync) {
			RemoteEndpoint.Basic basicRemote = session.getBasicRemote();
			try {
				basicRemote.sendText(JSONUtil.exportObject(msg));
			}
			catch(IOException e) {
				logger.error(e);
			}
		}
		else {
			RemoteEndpoint.Async asyncRemote = session.getAsyncRemote();
			asyncRemote.sendText(JSONUtil.exportObject(msg, RecordSerializerConfig.getUnfilteredModule()));
		}
	}

	@Override
	public void onChatComplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
		chirpUser(user, new String[] {"chatComplete", request.get(FieldNames.FIELD_OBJECT_ID)});
	}

	@Override
	public void onChatUpdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message) {
		chirpUser(user, new String[] {"chatUpdate", request.get(FieldNames.FIELD_OBJECT_ID), message});
	}

	@Override
	public void onChatError(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg) {
		chirpUser(user, new String[] {"chatError", request.get(FieldNames.FIELD_OBJECT_ID), msg});
	}

	@Override
	public void onChatStart(BaseRecord user, ChatRequest chatRequest, OpenAIRequest request) {
		chirpUser(user, new String[] {"chatStart", request.get(FieldNames.FIELD_OBJECT_ID), request.toFullString()});
	}

		
}

class SocketMessage extends LooseRecord {
	public SocketMessage() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_SOCKET_MESSAGE, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			/// ignore
		}
	}

	public SocketMessage(BaseRecord rec) {
		this();
		this.setFields(rec.getFields());
	}
	
	public void setToken(String token) {
		try {
			this.set(FieldNames.FIELD_TOKEN, token);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			/// ignore
		}
	}
	
	public void setNewMessageCount(int count) {
		try {
			this.set("newMessageCount", count);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			/// ignore
		}
	}

	
	public BaseRecord getMessage() {
		return this.get(FieldNames.FIELD_MESSAGE);
	}
	
	public List<String> getChirps(){
		return this.get("chirps");
	}
	
}

