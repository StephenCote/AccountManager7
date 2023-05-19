/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.rest.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.jaas.AM7LoginModule;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.glassfish.jersey.server.spi.AbstractContainerLifecycleListener;
import org.glassfish.jersey.server.spi.Container;

public class RestServiceConfig extends ResourceConfig{
	private static final Logger logger = LogManager.getLogger(RestServiceConfig.class);
    public static final String[] DEFAULT_ORGANIZATIONS = new String[] {
        	"/System",
        	"/Development",
        	"/Public"
      };
    
	public RestServiceConfig(@Context ServletContext servletContext){
		register(StartupHandler.class);
		register(RolesAllowedDynamicFeature.class);
		
	}
	
    private static class StartupHandler extends  AbstractContainerLifecycleListener {
        @Context
        ServletContext context;

        // private List<ThreadService> maintenanceThreads = new ArrayList<>();

        @Override
        public void onShutdown(Container container) {
        	/*
        	logger.info("Chirping users");
        	WebSocketService.activeUsers().forEach(user ->{
        		WebSocketService.chirpUser(user, new String[] {"Service going offline"});
        	});
        	*/
            logger.info("Cleaning up AccountManager");

            IOSystem.close();
            /*
            try {
            	logger.info("Stopping maintenance threads");
                for(ThreadService svc : maintenanceThreads){
                	svc.requestStop();
                }
                /// Sleep to give the threads a chance to shut down
                ///
				Thread.sleep(1000);
				
			} catch (InterruptedException e) {
				logger.error(e.getMessage());
			}
			*/
            // maintenanceThreads.clear();

        }

    	
        @Override
        public void onStartup(Container container) {
        	initializeAccountManager();
        }
    	private void resetH2DBUtil(String url, String user, String pwd, boolean reset) {
    		if(reset) {
    			logger.info("Resetting database");
    			File f = new File(url + ".mv.db");
    			File f2 = new File(url + ".trace.db");
    			if(f.exists()) {
    				logger.info("Cleanup database: " + f.delete());
    			}
    			else {
    				logger.info("No database to cleanup: " + url + ".mv");
    			}
    			if(f2.exists()) {
    				logger.info("Cleanup database trace: " + f2.delete());
    			}
    		}
    	}

        /// 
    	protected IOProperties getDBProperties(String dataUrl, String dataUser, String dataPassword, String jndiName) {
    		IOProperties props = new IOProperties();
    		props.setDataSourceUrl(dataUrl);
    		props.setDataSourceUserName(dataUser);
    		props.setDataSourcePassword(dataPassword);
    		props.setJndiName(jndiName);
    		props.setSchemaCheck(false);
    		return props;
    	}
		private void initializeAccountManager(){
			logger.info("Initializing Account Manager");
			
			String path = context.getInitParameter("store.path");
			ResourceUtil.RESOURCE_PREFIX = "";
			IOFactory.DEFAULT_FILE_BASE = path;
			String dsName = context.getInitParameter("database.dsname");
			//IOProperties props = getDBProperties("jdbc:h2:" + path + "/h2", "sa", "1234");
			IOProperties props = getDBProperties(null, null, null, dsName);
			//IOContext ioContext = IOSystem.open(RecordIO.FILE, null, null);
			IOContext ioContext = IOSystem.open(RecordIO.DATABASE, props);
			
			for(String org : DEFAULT_ORGANIZATIONS) {
				OrganizationContext octx = ioContext.getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
				if(!octx.isInitialized()) {
					logger.error("**** Organizations are not configured.  Run /rest/setup");
					break;
					/*
					logger.info("Creating organization " + org);
					try {
						octx.createOrganization();
					} catch (NullPointerException | SystemException e) {
						logger.error(e);
						e.printStackTrace();
					}
					*/
				}
				else {
					logger.info("Working with existing organization " + org);
				}
			}
			
		/*
			
			logger.info("Starting Maintenance Threads");
			String addJob = context.getInitParameter("maintenance.jobs");
			int jobPeriod = 10000;
			String jobPeriodStr = context.getInitParameter("maintenance.jobs.period");
			if(jobPeriodStr != null) jobPeriod = Integer.parseInt(jobPeriodStr);
			if(addJob != null){
				String[] jobs = addJob.split(",");
				for(int i = 0; i < jobs.length;i++){
					try {
						logger.info("Starting " + jobs[i]);
						Class<?> cls = Class.forName(jobs[i]);
						ThreadService f = (ThreadService)cls.getDeclaredConstructor().newInstance();
						f.setThreadDelay(jobPeriod);
						maintenanceThreads.add(f);
					} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
						logger.error(FactoryException.TRACE_EXCEPTION, e);
					}
					
				}
			}
		*/
			String roleAuth = context.getInitParameter("amauthrole");
			if(roleAuth != null && roleAuth.length() > 0){
				AM7LoginModule.setAuthenticatedRole(roleAuth);
			}
			
			String roleMapPath = context.getInitParameter("amrolemap");
			InputStream resourceContent = null;
			Map<String,String> roleMap = new HashMap<>();
			try {
				resourceContent = context.getResourceAsStream(roleMapPath);
				roleMap = JSONUtil.getMap(StreamUtil.getStreamBytes(resourceContent), String.class, String.class);
			} catch (IOException e) {
				
				logger.error(e);
				e.printStackTrace();
			}
			finally{
				if(resourceContent != null)
					try {
						resourceContent.close();
					} catch (IOException e) {
						
						logger.error(e);
					}
			}
			AM7LoginModule.setRoleMap(roleMap);

		}
    }
}
