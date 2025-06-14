package org.cote.rest.config;

import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HandlesTypes;

@HandlesTypes(jakarta.ws.rs.core.Application.class)
public class RestServiceInitializer implements ServletContainerInitializer {
	private static final Logger logger = LogManager.getLogger(RestServiceInitializer.class);
	
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException {
		logger.info("Initializing container ...");
    }
}