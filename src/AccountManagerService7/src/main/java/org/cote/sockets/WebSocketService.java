package org.cote.sockets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
import org.cote.accountmanager.schema.type.ValueEnumType;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.JSONUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/wss/{objectId}")
public class WebSocketService  extends HttpServlet {
	private static final long serialVersionUID = 1L;

	public static final Logger logger = LogManager.getLogger(WebSocketService.class);

	// private Map<String, Map<String, BaseRecord>> sessionMap =
	// Collections.synchronizedMap(new HashMap<>());
	//private Map<String, Session> objectIdSession = Collections.synchronizedMap(new HashMap<>());
	private static Map<String, BaseRecord> userMap = Collections.synchronizedMap(new HashMap<>());
	private static Map<String, Session> urnToSession = Collections.synchronizedMap(new HashMap<>());
	
	public static List<BaseRecord> activeUsers(){
		return new ArrayList<>(userMap.values());
	}
	
	//private static Map<String, BaseRecord> 

	/*
	 * private Map<String, BaseRecord> getMap(String sessionId){
	 * if(!sessionMap.containsKey(sessionId)) { sessionMap.put(sessionId, new
	 * HashMap<>()); } return sessionMap.get(sessionId); }
	 */

	//private Map<String, List<String>> buffer = Collections.synchronizedMap(new HashMap<>());

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
			msg.set("recipientId", recipient.get(FieldNames.FIELD_ID));
			msg.set("recipientType", recipient.getModel());
			msg.set("senderId", sender.get(FieldNames.FIELD_ID));
			msg.set("senderType", sender.getModel());
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
				msg.set(FieldNames.FIELD_REFERENCE_TYPE, ref.getModel());
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
	
	/*
	private List<BaseRecord> getTransmitMessages(Session session){
		BaseRecord user = null;
		if(!userMap.containsKey(session.getId())) {
			return null;
		}
		user = userMap.get(session.getId());
		List<BaseRecord> msgs = new ArrayList<>();
		try {
			MessageFactory mfact = Factories.getFactory(FactoryEnumType.MESSAGE);
			msgs = mfact.getMessagesFromUserGroup(null, SpoolNameEnumType.MESSAGE, SpoolStatusEnumType.SPOOLED, null, user);
			for(BaseRecord m : msgs) {
				m.setSpoolStatus(SpoolStatusEnumType.TRANSMITTED);
				mfact.update(m);
			}
		}
		catch(FactoryException | ArgumentException e) {
			logger.error(e);
		}
		return msgs;
	}
	*/
	
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
		if(urnToSession.containsKey(user.get(FieldNames.FIELD_URN))) {
			logger.warn("User does not have an active WebSocket");
			return false;
		}
		
		sendMessage(urnToSession.get(user.get(FieldNames.FIELD_URN)), chirps, true, false, true);
		return true;
	}
	private static void sendMessage(Session session) {
		sendMessage(session, new String[0], true, false, false);
	}
	private static void sendMessage(Session session, String[] chirps, boolean count, boolean auth, boolean sync) {
		if (!userMap.containsKey(session.getId())) {
			logger.warn("Session is not mapped to a key");
			return;
		}
		BaseRecord user = userMap.get(session.getId());
		//asyncRemote.sendText(builder.build().toString());
		// Send pending messages
		SocketMessage msg = new SocketMessage();
		msg.setUser(user.get(FieldNames.FIELD_URN));
		if(count) {
			msg.setNewMessageCount(countNewMessages(session));
		}
		if(auth) {
			msg.setAuthToken(TokenService.createSimpleJWTToken(user));
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
			asyncRemote.sendText(JSONUtil.exportObject(msg));
		}
	}

	@OnOpen
	public void onOpen(Session session, @PathParam("objectId") String objectId) {
		logger.info("Opened socket for " + session.getId() + " / " + objectId);

		BaseRecord user = IOSystem.getActiveContext().getRecordUtil().getRecordByObjectId(null, ModelNames.MODEL_USER, objectId);
		if (user != null) {
			userMap.put(session.getId(), user);
			urnToSession.put(user.get(FieldNames.FIELD_URN), session);
		}

		sendMessage(session, new String[] {"New Session"}, false, true, false);
	}

	@OnMessage
	public void onMessage(String txt, Session session) throws IOException {
		BaseRecord user = userMap.get(session.getId());
		
		SocketMessage msg = JSONUtil.importObject(txt,  SocketMessage.class);
	
		BaseRecord message = msg.getSendMessage();
		if(user != null && message != null) {
			long recId = message.get("recipientId");
			String recType = message.get("recipientType");
			logger.info("Send message " + message.get(FieldNames.FIELD_NAME) + " to " + recId + " from " + user.get(FieldNames.FIELD_URN) + " (" + user.get(FieldNames.FIELD_OBJECT_ID) + ")");
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
				logger.warn("Handle message with no recipient");
			}
		}
		else {
			logger.error("User or message was null");
		}
		//session.getBasicRemote().sendText(txt.toUpperCase());
	}

	@OnClose
	public void onClose(CloseReason reason, Session session) {
		//logger.info(String.format("Closing a WebSocket (%s) due to %s", session.getId(), reason.getReasonPhrase()));
		//newMessage(session, "Hangup the phone", "Session ended");
		if(userMap.containsKey(session.getId())){
			urnToSession.remove(userMap.get(session.getId()).get(FieldNames.FIELD_URN));
		}
		userMap.remove(session.getId());
		
	}
	
	@OnError
	public void onError(Session session, Throwable t) {
		logger.error(String.format("Error in WebSocket session %s%n", session == null ? "null" : session.getId()), t);
	}
		
}

class SocketMessage {
	private String user = null;
	private int newMessageCount = 0;
	private BaseRecord sendMessage = null;
	private String authToken = null;
	private List<String> chirps = new ArrayList<>();
	public SocketMessage() {
		
	}
	
	public String getAuthToken() {
		return authToken;
	}

	public void setAuthToken(String authToken) {
		this.authToken = authToken;
	}

	public String getUser() {
		return user;
	}
	public void setUser(String user) {
		this.user = user;
	}
	public int getNewMessageCount() {
		return newMessageCount;
	}
	public void setNewMessageCount(int newMessageCount) {
		this.newMessageCount = newMessageCount;
	}
	public BaseRecord getSendMessage() {
		return sendMessage;
	}
	public void setSendMessage(BaseRecord sendMessage) {
		this.sendMessage = sendMessage;
	}
	public List<String> getChirps() {
		return chirps;
	}
	public void setChirps(List<String> chirps) {
		this.chirps = chirps;
	}
	
}

