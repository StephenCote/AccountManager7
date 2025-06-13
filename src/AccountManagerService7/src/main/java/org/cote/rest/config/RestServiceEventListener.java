package org.cote.rest.config;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.cote.jaas.AM7LoginModule;
import org.cote.sockets.WebSocketService;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Context;

public class RestServiceEventListener implements ApplicationEventListener {
	private static final Logger logger = LogManager.getLogger(RestServiceEventListener.class);
	
	@Context
	private ServletContext context = null;
	private static boolean debugEnableVector = true;

	public RestServiceEventListener() {
		
	}
	
	public RestServiceEventListener(ServletContext ctx) {
		this.context = ctx;
	}
	
    @Override
    public void onEvent(ApplicationEvent event) {
        switch (event.getType()) {
            case INITIALIZATION_FINISHED:
            		logger.info("Init");
                	startup();
                break;
            case DESTROY_FINISHED:
            		logger.info("Destroy");
            		shutdown();
            	break;
		default:
			logger.warn("Unhandled ApplicationEvent type: " + event.getType());
			break;
        }
    }
    
	private List<Threaded> maintenanceThreads = new ArrayList<>();

	public void shutdown() {

		int cleanup = 0;
		try {
			cleanup = StreamUtil.clearUnboxedStreams();
		} catch (ModelException e) {
			logger.error(e);
		}
		if (cleanup > 0) {
			logger.info("Cleaned up " + cleanup + " unboxed streams");
		}

		logger.info("Chirping users");
		WebSocketService.activeSessions().forEach(session -> {
			// WebSocketService.chirpUser(user, new String[] {"Service going offline"});
			WebSocketService.sendMessage(session, new String[] { "Service going offline" }, true, false, true);
		});

		logger.info("Cleaning up AccountManager");

		IOSystem.close();

		try {
			logger.info("Stopping maintenance threads");
			for (Threaded svc : maintenanceThreads) {
				svc.requestStop();
			}
			/// Sleep to give the threads a chance to shut down
			///
			Thread.sleep(100);

		} catch (InterruptedException e) {
			logger.error(e.getMessage());
		}
	}

	public void startup() {
		initializeAccountManager();
	}

	protected IOProperties getDBProperties(String dataUrl, String dataUser, String dataPassword, String jndiName) {
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(dataUrl);
		props.setDataSourceUserName(dataUser);
		props.setDataSourcePassword(dataPassword);
		props.setJndiName(jndiName);
		props.setSchemaCheck(false);
		props.setReset(false);
		return props;
	}

	private static final String threads = "org.cote.service.threads.NotificationThread";

	private void testVectorStore(IOContext ioContext, OrganizationContext octx) {

		DBUtil util = ioContext.getDbUtil();
		if (!debugEnableVector) {
			util.setEnableVectorExtension(false);
		} else {
			List<BaseRecord> store = new ArrayList<>();
			if (util.isEnableVectorExtension()) {
				try {
					/// NOTE: This only tests the vector storage capability
					/// Any failure of the embedding API is allowed/not handled here.
					///
					store = IOSystem.getActiveContext().getVectorUtil().createVectorStore(octx.getDocumentControl(),
							"Random content - " + UUID.randomUUID(), ChunkEnumType.UNKNOWN, 0);
				} catch (FieldException e) {
					logger.error(e);
				}
				if (store == null || store.size() == 0) {
					logger.error("Expected a vector store.  Disabling vector store.");
					util.setEnableVectorExtension(false);
				}
			}
		}
	}

	private void initializeAccountManager() {

		boolean disableSSLVerification = Boolean
				.parseBoolean(context.getInitParameter("ssl.verification.disabled"));
		if (disableSSLVerification) {
			logger.warn("SSL VERIFICATION DISABLED");
			ClientUtil.setDisableSSLVerification(true);
		}

		AuditUtil.setLogToConsole(Boolean.parseBoolean(context.getInitParameter("logToConsole")));

		logger.info("Initializing Account Manager");
		String streamCut = context.getInitParameter("stream.cutoff");
		if (streamCut != null) {
			StreamUtil.setStreamCutoff(Integer.parseInt(streamCut));
		}

		String path = context.getInitParameter("store.path");
		ImageIO.setCacheDirectory(new File(path));
		OlioModelNames.use();

		IOFactory.DEFAULT_FILE_BASE = path;
		IOFactory.addPermittedPath(path + "/.streams");
		String dsName = context.getInitParameter("database.dsname");
		boolean chkSchema = Boolean.parseBoolean(context.getInitParameter("database.checkSchema"));
		IOProperties props = getDBProperties(null, null, null, dsName);
		props.setSchemaCheck(chkSchema);
		try {
			IOContext ioContext = IOSystem.open(RecordIO.DATABASE, props);
			String authToken = context.getInitParameter("embedding.authorizationToken");
			if (authToken != null && authToken.length() == 0)
				authToken = null;
			ioContext.setVectorUtil(new VectorUtil(
					LLMServiceEnumType.valueOf(context.getInitParameter("embedding.type").toUpperCase()),
					context.getInitParameter("embedding.server"), authToken));
			boolean testVector = false;
			for (String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
				OrganizationContext octx = ioContext.getOrganizationContext(org,
						OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
				if (!octx.isInitialized()) {
					logger.error("**** Organizations are not configured.  Run /rest/setup");
					break;
				} else {
					logger.info("Working with existing organization " + org);
					if (!testVector) {
						testVectorStore(ioContext, octx);
						testVector = true;
					}
				}
			}

			int jobPeriod = 10000;
			String jobPeriodStr = context.getInitParameter("maintenance.interval");
			if (jobPeriodStr != null)
				jobPeriod = Integer.parseInt(jobPeriodStr);
			if (threads != null) {
				String[] jobs = threads.split(",");
				for (int i = 0; i < jobs.length; i++) {
					try {
						logger.info("Starting " + jobs[i]);
						Class<?> cls = Class.forName(jobs[i]);
						Threaded f = (Threaded) cls.getDeclaredConstructor().newInstance();
						f.setThreadDelay(jobPeriod);
						maintenanceThreads.add(f);
					} catch (InvocationTargetException | ClassNotFoundException | InstantiationException
							| IllegalAccessException | IllegalArgumentException | NoSuchMethodException
							| SecurityException e) {
						logger.error(e);
					}

				}
			}

			String roleAuth = context.getInitParameter("amauthrole");
			if (roleAuth != null && roleAuth.length() > 0) {
				AM7LoginModule.setAuthenticatedRole(roleAuth);
			}

			String roleMapPath = context.getInitParameter("amrolemap");
			InputStream resourceContent = null;
			Map<String, String> roleMap = new HashMap<>();
			try {
				resourceContent = context.getResourceAsStream(roleMapPath);
				roleMap = JSONUtil.getMap(StreamUtil.getStreamBytes(resourceContent), String.class, String.class);
			} catch (IOException e) {

				logger.error(e);
				e.printStackTrace();
			} finally {
				if (resourceContent != null)
					try {
						resourceContent.close();
					} catch (IOException e) {

						logger.error(e);
					}
			}

			boolean pollRemote = Boolean.parseBoolean(context.getInitParameter("task.poll.remote"));
			String taskServer = context.getInitParameter("task.server");
			String taskApiKey = context.getInitParameter("task.api.key");
			if (pollRemote) {
				logger.warn("REMOTE POLLING ENABLED");
				ioContext.getTaskQueue().setRemotePoll(true);
				ioContext.getTaskQueue().setServerUrl(taskServer);
				ioContext.getTaskQueue().setAuthorizationToken(taskApiKey);
			}

			AM7LoginModule.setRoleMap(roleMap);
		} catch (Exception e) {
			logger.error(e);
		}
	}

    @Override
    public RequestEventListener onRequest(RequestEvent requestEvent) {
        // Return null if you don't need to handle request-level events
        return null;
    }

}