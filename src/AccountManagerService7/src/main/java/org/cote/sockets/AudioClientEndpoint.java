package org.cote.sockets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.tools.VoiceResponse;
import org.cote.accountmanager.util.JSONUtil;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;

@ClientEndpoint
public class AudioClientEndpoint {

	public static final Logger logger = LogManager.getLogger(AudioClientEndpoint.class);
    private final BaseRecord user;

    /**
     * Constructor to pass necessary context, like the user object.
     * @param user The user for whom this connection is being made.
     */
    public AudioClientEndpoint(BaseRecord user) {
        this.user = user;
    }

    @OnOpen
    public void onOpen(Session session) {
        logger.info("✅ Connection to Python service established. Session ID: " + session.getId());
    }

    /**
     * Handles incoming text messages (transcripts) from the Python service.
     * @param message The JSON string from Python, e.g., {"transcript": "Hello"}
     * @param session The WebSocket session.
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        logger.info("Received transcript from Python: " + message);
        VoiceResponse response = JSONUtil.importObject(message, VoiceResponse.class);
        if(response == null) {
        	WebSocketService.chirpUser(user, new String[]{"audioError", "Failed to parse response", message});
			logger.error("Failed to parse VoiceResponse from message: " + message);
			return;
		}
        // "Chirp" the received transcript back to the original browser client
        WebSocketService.chirpUser(user, new String[]{"audioSTTUpdate", response.getText()});
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        logger.info("Connection to Python service closed. Session ID: " + session.getId() + ", Reason: " + reason);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        logger.error("Error in Python WebSocket connection. Session ID: " + session.getId(), throwable);
    }
}