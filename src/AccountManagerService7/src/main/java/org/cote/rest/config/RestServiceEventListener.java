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
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.schema.ISO42001Provisioning;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.tools.VoiceUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.olio.llm.ChatListener;
import org.cote.accountmanager.util.LLMConnectionManager;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.cote.jaas.AM7LoginModule;
import org.cote.sockets.GameStreamHandler;
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
	/// Vector DB support master switch + embedding-server startup probe toggle (web.xml: vector.enabled,
	/// vector.probe.embedding). A failed embedding probe NO LONGER disables vector support — only an explicit
	/// vector.enabled=false does. Defaults preserve vector support being on.
	private boolean vectorEnabled = true;
	private boolean probeEmbedding = true;

	public RestServiceEventListener() {
		
	}
	
	public RestServiceEventListener(ServletContext ctx) {
		this.context = ctx;
	}
	
    @Override
    public void onEvent(ApplicationEvent event) {
        switch (event.getType()) {
            case INITIALIZATION_FINISHED:
                	startup();
                break;
            case DESTROY_FINISHED:
            		shutdown();
            	break;
		default:
			//logger.warn("Unhandled ApplicationEvent type: " + event.getType());
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

		/// Phase 1: Stop all active LLM work (chat streams, summarization, swarm, analysis)
		logger.info("Stopping all active LLM/chat/swarm connections");
		ChatListener.shutdown();
		LLMConnectionManager.shutdownAll();

		/// Phase 2: Stop game stream executor
		logger.info("Stopping game stream handler");
		GameStreamHandler.shutdown();

		/// Phase 3: Notify connected users before closing IO
		logger.info("Chirping users");
		WebSocketService.activeSessions().forEach(session -> {
			WebSocketService.sendMessage(session, new String[] { "Service going offline" }, true, false, true);
		});

		/// Phase 4: Close IO system (database, file handles, task queue, batch queue)
		logger.info("Cleaning up AccountManager");
		IOSystem.close();

		/// Phase 5: Stop maintenance threads
		try {
			logger.info("Stopping maintenance threads");
			for (Threaded svc : maintenanceThreads) {
				svc.requestStop();
			}
			Thread.sleep(100);
		} catch (InterruptedException e) {
			logger.error(e.getMessage());
		}

		logger.info("Shutdown complete");
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
		/// Master switch: vector support is governed by config, NOT by embedding-server availability.
		if (!vectorEnabled) {
			logger.info("Vector DB support disabled by configuration (vector.enabled=false)");
			util.setEnableVectorExtension(false);
			return;
		}
		if (!util.isEnableVectorExtension()) {
			/// The database itself doesn't support pgvector — nothing to probe.
			return;
		}
		if (!probeEmbedding) {
			logger.info("Vector DB support enabled; embedding-server startup probe skipped (vector.probe.embedding=false)");
			return;
		}
		/// Probe the embedding server as an informational health check ONLY. A failure (e.g. the embedding
		/// server is down) is logged as a warning and DOES NOT disable vector support for the session — the
		/// pgvector extension stays enabled so it recovers automatically once the embedding server returns.
		List<BaseRecord> store = new ArrayList<>();
		try {
			store = IOSystem.getActiveContext().getVectorUtil().createVectorStore(octx.getDocumentControl(),
					"Random content - " + UUID.randomUUID(), ChunkEnumType.UNKNOWN, 0);
		} catch (Exception e) {
			logger.warn("Embedding-server startup probe failed (vector support remains ENABLED): " + e.getMessage());
			return;
		}
		if (store == null || store.size() == 0) {
			logger.warn("Embedding-server startup probe returned no vector store (server may be down at "
					+ context.getInitParameter("embedding.server") + "). Vector support remains ENABLED and will "
					+ "recover when the embedding server is reachable.");
		} else {
			logger.info("Embedding-server startup probe OK; vector DB support enabled");
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
		/// Phase 7: register the ISO 42001 model namespace before IOSystem.open so the additive schema scan
		/// creates the iso42001.* tables (the production seam for what Track A did in test setup).
		ISO42001ModelNames.use();

		IOFactory.DEFAULT_FILE_BASE = path;
		IOFactory.addPermittedPath(path + "/.streams");
		String dsName = context.getInitParameter("database.dsname");
		boolean chkSchema = Boolean.parseBoolean(context.getInitParameter("database.checkSchema"));
		boolean dropCols = Boolean.parseBoolean(context.getInitParameter("database.dropColumns"));
		IOProperties props = getDBProperties(null, null, null, dsName);
		props.setSchemaCheck(chkSchema);
		props.setDropColumns(dropCols);
		try {
			IOContext ioContext = IOSystem.open(RecordIO.DATABASE, props);
			String authToken = context.getInitParameter("embedding.authorizationToken");
			if (authToken != null && authToken.length() == 0) authToken = null;
			ioContext.setVectorUtil(new VectorUtil(
					LLMServiceEnumType.valueOf(context.getInitParameter("embedding.type").toUpperCase()),
					context.getInitParameter("embedding.server"), authToken));
			authToken = context.getInitParameter("voice.authorizationToken");
			
			if (authToken != null && authToken.length() == 0) authToken = null;
			ioContext.setVoiceUtil(new VoiceUtil(
					LLMServiceEnumType.valueOf(context.getInitParameter("voice.type").toUpperCase()),
					context.getInitParameter("voice.tts.server"), context.getInitParameter("voice.stt.server"), authToken));
			
			/// Vector support is config-governed (decoupled from embedding-server health). Default ON.
			vectorEnabled = parseBoolean(context.getInitParameter("vector.enabled"), true);
			probeEmbedding = parseBoolean(context.getInitParameter("vector.probe.embedding"), true);

			boolean testVector = false;
			for (String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
				OrganizationContext octx = ioContext.getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
				if (octx ==null || !octx.isInitialized()) {
					logger.error("**** Organizations are not configured.  Run /rest/setup");
					break;
				} else {
					/// Initialize vault
					octx.getVault();
					logger.info("Working with existing organization " + org);
					if (!testVector) {
						testVectorStore(ioContext, octx);
						testVector = true;
					}
					/// Phase 7: idempotently provision the 6 ISO 42001 roles + their PBAC entitlement wiring
					/// for this org (the production seam for what ISO42001BaseTest does in test setup).
					try {
						ISO42001Provisioning.ensureRoles(octx.getAdminUser(), octx.getOrganizationId());
					} catch (Exception e) {
						logger.error("Failed to provision ISO 42001 roles for " + org, e);
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

    /** Parse a context-param boolean, returning {@code def} when the value is absent/blank. */
    private static boolean parseBoolean(String value, boolean def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(value.trim());
    }

    @Override
    public RequestEventListener onRequest(RequestEvent requestEvent) {
        // Return null if you don't need to handle request-level events
        return null;
    }

}