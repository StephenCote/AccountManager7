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
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.util.LLMConnectionManager;
import org.cote.accountmanager.util.ServerConfigUtil;
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

	/// Maintenance threads, comma separated. Each is constructed (which starts it) and given
	/// maintenance.interval as its delay, AFTER setVectorUtil/setVoiceUtil have run.
	/// ServerConfigRefreshThread is what makes a DB edit to embedding/voice.tts/voice.stt reach the
	/// bound singletons without a restart — see that class for why no request seam can do it.
	private static final String threads = "org.cote.service.threads.NotificationThread,org.cote.service.threads.ServerConfigRefreshThread";

	/// Boot-time sanity check that embedding.type and the effective embedding.server URL agree in
	/// SHAPE. The two are configured independently (type is boot-pinned from web.xml, the URL is
	/// DB-backed with web.xml as fallback), so they drift apart on an existing data volume when the
	/// provider is switched: the stored system.connection URL keeps pointing at the old server while
	/// the type changes. The symptom is a silent "Embedding for chunk 1 is null or empty" with no
	/// hint at the cause. WARN only — never fail boot; an unreachable/mismatched embedding server is
	/// already tolerated by testVectorStore.
	///
	///   LOCAL         expects <host:port> for the bundled embedApiMini.py; it POSTs <url>/generate_embedding
	///   OPENAI        expects a full Azure URL (/openai/deployments/<dep>/embeddings?api-version=...)
	///   OPENAI_COMPAT expects a full /v1/embeddings URL (LiteLLM, Ollama)
	public static void warnOnEmbeddingTypeUrlMismatch(LLMServiceEnumType type, String url) {
		if (type == null || url == null || url.isBlank()) {
			return;
		}
		String u = url.trim();
		String path = "";
		try {
			java.net.URI uri = java.net.URI.create(u);
			path = (uri.getPath() != null ? uri.getPath() : "");
		} catch (IllegalArgumentException e) {
			logger.warn("embedding.server '" + u + "' is not a parseable URL");
			return;
		}
		boolean looksAzure = path.contains("/openai/deployments/");
		boolean looksCompat = path.endsWith("/v1/embeddings");
		boolean looksOpenAi = looksCompat || looksAzure;
		/// "No path beyond host:port" — the shape the LOCAL custom service takes.
		boolean looksLocal = path.isEmpty() || path.equals("/");
		if (type == LLMServiceEnumType.LOCAL && looksOpenAi) {
			logger.warn("embedding.type=" + type + " but embedding.server '" + u + "' looks like an OpenAI-shaped"
					+ " embeddings endpoint. The LOCAL branch POSTs <server>/generate_embedding and will get 404s;"
					+ " set embedding.type=openai (Azure) or openai_compat (LiteLLM/Ollama /v1/embeddings).");
		} else if ((type == LLMServiceEnumType.OPENAI || type == LLMServiceEnumType.OPENAI_COMPAT) && looksLocal) {
			logger.warn("embedding.type=" + type + " but embedding.server '" + u + "' has no path — it looks like"
					+ " the LOCAL /generate_embedding custom service (host:port only). The " + type + " branch POSTs"
					+ " an OpenAI body to that URL verbatim. Expected a full embeddings URL"
					+ (type == LLMServiceEnumType.OPENAI ? " (/openai/deployments/<deployment>/embeddings?api-version=...)."
							: " (.../v1/embeddings).")
					+ " If embedding.server is DB-backed (system.connection), the stored URL is still the old one.");
		} else if (type == LLMServiceEnumType.OPENAI_COMPAT && looksAzure) {
			/// Seen live 2026-09-29: --ollama set type=openai_compat/model=nomic-embed-text in env, but a
			/// stored system.connection 'embedding' still pointed at Azure. OPENAI_COMPAT sends no
			/// `dimensions` (Ollama ignores it) and Bearer auth, so Azure text-embedding-3-small answered
			/// at its native 1536 against a 768 schema — the probe passed and the width guard pinned 1536.
			logger.warn("embedding.type=" + type + " but embedding.server '" + u + "' is an Azure OpenAI deployment URL."
					+ " OPENAI_COMPAT sends Bearer auth and NO `dimensions`, so Azure returns the model's native width"
					+ " (1536 for text-embedding-3-small), not embedding.dimensions. Use embedding.type=openai for Azure,"
					+ " or repoint the stored system.connection 'embedding' at the intended /v1/embeddings server"
					+ " (the env/web.xml embedding.server is only a fallback when no record exists).");
		} else if (type == LLMServiceEnumType.OPENAI && looksCompat) {
			logger.warn("embedding.type=" + type + " but embedding.server '" + u + "' is a /v1/embeddings endpoint"
					+ " (LiteLLM/Ollama shape). The OPENAI branch is Azure-specific: it sends the `api-key` header (not"
					+ " Bearer — LiteLLM will 401) and `dimensions`. Use embedding.type=openai_compat with embedding.model set.");
		}
	}

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
		/// Report the EFFECTIVE embedding configuration (DB-backed URL, else the web.xml init-param;
		/// plus the boot-pinned type and model actually bound to the EmbeddingUtil), not the raw
		/// init-params — printing the init-param while a different DB value is actually in use
		/// sends you debugging the wrong endpoint.
		String effective = describeEffectiveEmbeddingConfig(ioContext);
		try {
			store = IOSystem.getActiveContext().getVectorUtil().createVectorStore(octx.getDocumentControl(),
					"Random content - " + UUID.randomUUID(), ChunkEnumType.UNKNOWN, 0);
		} catch (Exception e) {
			logger.warn("Embedding-server startup probe failed (vector support remains ENABLED) at " + effective
					+ ": " + e.getMessage());
			return;
		}
		if (store == null || store.size() == 0) {
			logger.warn("Embedding-server startup probe returned no vector store (server may be down at "
					+ effective
					+ "). Vector support remains ENABLED and will "
					+ "recover when the embedding server is reachable.");
		} else {
			logger.info("Embedding-server startup probe OK (" + effective + "); vector DB support enabled");
		}
	}

	/// "server=<effective url> type=<type> model=<model or blank>" for the boot log lines above.
	private String describeEffectiveEmbeddingConfig(IOContext ioContext) {
		String url = ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_EMBEDDING, context.getInitParameter("embedding.server"));
		String type = "";
		String model = "";
		VectorUtil vu = (ioContext != null ? ioContext.getVectorUtil() : null);
		if (vu != null && vu.getEmbedUtil() != null) {
			type = String.valueOf(vu.getEmbedUtil().getServiceType());
			model = (vu.getEmbedUtil().getEmbeddingModel() != null ? vu.getEmbedUtil().getEmbeddingModel() : "");
		}
		return "server=" + url + " type=" + type + " model=" + model;
	}

	/// Per-organization post-initialization provisioning for the default organizations:
	/// vault initialization plus idempotent ISO 42001 role/entitlement provisioning.
	///
	/// This is the SINGLE implementation, called from boot (initializeAccountManager) AND from
	/// setup completion (org.cote.rest.services.Setup) so freshly created organizations are usable
	/// without a Tomcat restart. There must be no second copy.
	///
	/// The ISO42001Provisioning call intentionally stays in Service7: ISO 42001 must never be
	/// reachable from Objects7, so SetupUtil cannot make it.
	///
	/// Returns true when every default organization is initialized, false as soon as one is not
	/// (preserving the original break-on-first-unconfigured behavior on a first boot).
	public static boolean provisionDefaultOrganizations() {
		IOContext ioContext = IOSystem.getActiveContext();
		if (ioContext == null) {
			return false;
		}
		for (String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
			OrganizationContext octx = ioContext.getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
			if (octx == null || !octx.isInitialized()) {
				logger.error("**** Organizations are not configured.  Run /rest/setup");
				return false;
			}
			/// Initialize vault
			octx.getVault();
			logger.info("Working with existing organization " + org);
			/// Phase 7: idempotently provision the 6 ISO 42001 roles + their PBAC entitlement wiring
			/// for this org (the production seam for what ISO42001BaseTest does in test setup).
			try {
				ISO42001Provisioning.ensureRoles(octx.getAdminUser(), octx.getOrganizationId());
			} catch (Exception e) {
				logger.error("Failed to provision ISO 42001 roles for " + org, e);
			}
		}
		return true;
	}

	private void initializeAccountManager() {

		boolean disableSSLVerification = Boolean
				.parseBoolean(context.getInitParameter("ssl.verification.disabled"));
		if (disableSSLVerification) {
			logger.warn("SSL VERIFICATION DISABLED");
			ClientUtil.setDisableSSLVerification(true);
		}

		AuditUtil.setLogToConsole(Boolean.parseBoolean(context.getInitParameter("logToConsole")));

		/// Opportunistic Ollama model unload before GPU-heavy (SD) work. Defaults OFF — with a large
		/// model the reload it forces on the next LLM call costs more than the freed VRAM saves. The
		/// explicit unloadAll(true) path is unaffected by this switch.
		OllamaModelUtil.setUnloadEnabled(parseBoolean(context.getInitParameter(OllamaModelUtil.CONFIG_KEY), false));

		/// Deployment's fallback SD checkpoint. Checkpoint names differ per Swarm install and a wrong
		/// one returns an empty image list rather than an error, so this is worth setting explicitly.
		/// sd.default.model is the canonical key; sd.model is the legacy per-service override used
		/// by OlioService/ChatService. When sd.default.model is blank, fall back to sd.model so that
		/// ChapBook renders (which call randomSDConfig) pick up the deployment-configured checkpoint.
		String sdDefaultModel = context.getInitParameter(org.cote.accountmanager.olio.sd.SDUtil.DEFAULT_MODEL_CONFIG_KEY);
		if (sdDefaultModel == null || sdDefaultModel.isBlank()) {
			sdDefaultModel = context.getInitParameter("sd.model");
		}
		org.cote.accountmanager.olio.sd.SDUtil.setDefaultModel(sdDefaultModel);

		/// LLM emulator (system.connection.dialect = EMULATOR) — deployment-global, boot-pinned. Both
		/// params blank (the default in web.xml.template / entrypoint.sh) leaves it inert, and an
		/// EMULATOR connection then fails fast with "LLM emulator not configured on this deployment".
		/// fixtureRoot enables replay/synthesis; recordDir enables the recorder (WARNs loudly inside
		/// configure()). Propagation bound is a Tomcat restart; Console7 never configures this.
		org.cote.accountmanager.olio.llm.LlmEmulator.configure(
			context.getInitParameter("llm.emulator.fixtureRoot"),
			context.getInitParameter("llm.emulator.recordDir"));

		/// Must run before any HTTP call - the shared Client caches the timeout at first use. Sized by
		/// the slowest legitimate SD generation, which is GPU-dependent (see ClientUtil).
		String readTo = context.getInitParameter(ClientUtil.READ_TIMEOUT_CONFIG_KEY);
		if(readTo != null && !readTo.isBlank()) {
			try { ClientUtil.setReadTimeoutSeconds(Integer.parseInt(readTo.trim())); }
			catch(NumberFormatException nfe) {
				logger.warn("Invalid " + ClientUtil.READ_TIMEOUT_CONFIG_KEY + "='" + readTo + "'; keeping default");
			}
		}

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
		boolean repairColTypes = Boolean.parseBoolean(context.getInitParameter("database.repairColumnTypes"));
		IOProperties props = getDBProperties(null, null, null, dsName);
		props.setSchemaCheck(chkSchema);
		props.setDropColumns(dropCols);
		props.setRepairColumnTypes(repairColTypes);
		try {
			IOContext ioContext = IOSystem.open(RecordIO.DATABASE, props);
			String authToken = context.getInitParameter("embedding.authorizationToken");
			if (authToken != null && authToken.length() == 0) authToken = null;
			/// Deployment server URLs are DB-backed (ServerConfigUtil) with the web.xml init-param
			/// as the FALLBACK: docker/entrypoint.sh regenerates WEB-INF/web.xml from a template on
			/// EVERY boot, so runtime configuration cannot live there.
			/// embedding.type is boot-pinned config. An absent/blank value must default to LOCAL (the
			/// bundled embedApiMini.py custom service) rather than NPE on .toUpperCase() and abort the
			/// whole init block via the catch below (which would leave VectorUtil/VoiceUtil/threads unset).
			String embType = context.getInitParameter("embedding.type");
			LLMServiceEnumType embServiceType = (embType == null || embType.isBlank())
					? LLMServiceEnumType.LOCAL
					: LLMServiceEnumType.valueOf(embType.trim().toUpperCase());
			String embServerUrl = ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_EMBEDDING, context.getInitParameter("embedding.server"));
			VectorUtil vectorUtil = new VectorUtil(embServiceType, embServerUrl, authToken);
			/// Configurable embedding dimensions, synced to the common.vectorExt.embedding column
			/// (setEmbeddingDimensions enforces the match and throws on mismatch).
			String embeddingDimensions = context.getInitParameter("embedding.dimensions");
			if (embeddingDimensions != null && embeddingDimensions.trim().length() > 0) {
				vectorUtil.getEmbedUtil().setEmbeddingDimensions(Integer.parseInt(embeddingDimensions.trim()));
			}
			/// embedding.model is boot-pinned like embedding.type: the model name sent in the
			/// OpenAI-shaped request body (OPENAI / OPENAI_COMPAT branch). Ollama's /v1/embeddings
			/// rejects a body without one; Azure resolves the model from the deployment URL, so
			/// blank leaves it unset (null) and the Azure body is unchanged. Ignored by LOCAL.
			/// ServerConfigUtil's TTL refresh swaps only the endpoint on this same EmbeddingUtil
			/// instance, so the model set here survives a DB-backed URL change.
			String embModel = context.getInitParameter("embedding.model");
			vectorUtil.getEmbedUtil().setEmbeddingModel(embModel);
			embModel = vectorUtil.getEmbedUtil().getEmbeddingModel();
			logger.info("Embedding configuration: server=" + embServerUrl + " type=" + embServiceType
					+ " model=" + (embModel != null ? embModel : ""));
			warnOnEmbeddingTypeUrlMismatch(embServiceType, embServerUrl);
			ioContext.setVectorUtil(vectorUtil);
			authToken = context.getInitParameter("voice.authorizationToken");
			
			if (authToken != null && authToken.length() == 0) authToken = null;
			ioContext.setVoiceUtil(new VoiceUtil(
					LLMServiceEnumType.valueOf(context.getInitParameter("voice.type").toUpperCase()),
					ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_VOICE_TTS, context.getInitParameter("voice.tts.server")),
					ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_VOICE_STT, context.getInitParameter("voice.stt.server")), authToken));
			
			/// Vector support is config-governed (decoupled from embedding-server health). Default ON.
			vectorEnabled = parseBoolean(context.getInitParameter("vector.enabled"), true);
			probeEmbedding = parseBoolean(context.getInitParameter("vector.probe.embedding"), true);

			/// ONE implementation of per-organization post-initialization provisioning, shared by
			/// boot and by setup completion (org.cote.rest.services.Setup). Do not copy it.
			if (provisionDefaultOrganizations()) {
				OrganizationContext probeOctx = ioContext.getOrganizationContext(
						OrganizationContext.DEFAULT_ORGANIZATIONS[0],
						OrganizationEnumType.valueOf(OrganizationContext.DEFAULT_ORGANIZATIONS[0].substring(1).toUpperCase()));
				if (probeOctx != null && probeOctx.isInitialized()) {
					testVectorStore(ioContext, probeOctx);
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