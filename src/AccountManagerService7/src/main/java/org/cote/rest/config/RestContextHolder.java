package org.cote.rest.config;

import jakarta.servlet.ServletContext;

public class RestContextHolder {
    private static ServletContext servletContext;

    public static void setServletContext(ServletContext context) {
        servletContext = context;
    }

    public static ServletContext getServletContext() {
        if (servletContext == null) {
            throw new IllegalStateException("ServletContext has not been initialized. Check your ServletContextListener.");
        }
        return servletContext;
    }
}