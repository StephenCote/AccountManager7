package org.cote.listeners;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.sockets.WebSocketService;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

public class AccountManagerContextListener implements ServletContextListener{
	public static final Logger logger = LogManager.getLogger(AccountManagerContextListener.class);
	
	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		logger.info("Closing proxy sessions");
		WebSocketService.closeProxySessions();

    	logger.info("Chirping users");
    	WebSocketService.activeUsers().forEach(user ->{
    		WebSocketService.chirpUser(user, new String[] {"Service going offline"});
    	});
        
        logger.info("Deregistering ImageIO service providers to prevent ClassLoader leaks.");
        ImageIO.scanForPlugins();
        
		logger.info("Context destroyed");
	}

        //Run this before web application is started
	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		logger.info("Context initialized");	
	}
}