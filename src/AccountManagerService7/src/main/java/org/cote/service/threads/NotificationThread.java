package org.cote.service.threads;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.thread.Threaded;
import org.cote.sockets.WebSocketService;

import jakarta.websocket.Session;


public class NotificationThread extends Threaded {
	
	public static final Logger logger = LogManager.getLogger(NotificationThread.class);
	private int spoolFlushDelay = 300000;
	private int counter = 0;
	public NotificationThread(){
		super();
		this.setThreadDelay(spoolFlushDelay);
	}
	public void execute(){
    	counter++;
    	//List<BaseRecord> users = WebSocketService.activeUsers();
    	List<Session> sessions = WebSocketService.activeSessions();
		//logger.info("Chirping " + sessions.size() + " sessions: " + counter);
    	sessions.forEach(session ->{
    		BaseRecord user = WebSocketService.getUserBySession(session);
    		String[] msg = new String[] {"Heartbeat - " + counter};
    		if(user != null) {
    			WebSocketService.chirpUser(user, msg);
    		}
    		else {
    			WebSocketService.sendMessage(session, msg, false, false, false);
    		}
    	});
	}
	
}