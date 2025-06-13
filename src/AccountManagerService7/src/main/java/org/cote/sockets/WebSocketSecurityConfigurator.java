package org.cote.sockets;

import java.security.Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

public class WebSocketSecurityConfigurator extends ServerEndpointConfig.Configurator {
	public static final Logger logger = LogManager.getLogger(WebSocketSecurityConfigurator.class);
	public static final String SESSION_PRINCIPAL = "socket.principal";
	
    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        final Principal principal = request.getUserPrincipal();
        if(principal != null) {
        	sec.getUserProperties().put(SESSION_PRINCIPAL, principal);
        }
    }

}