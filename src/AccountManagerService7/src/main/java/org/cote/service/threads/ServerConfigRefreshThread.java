package org.cote.service.threads;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.util.ServerConfigUtil;

/// Periodically re-resolves the deployment server configuration names that are BOUND into
/// long-lived singletons (embedding, voice.tts, voice.stt) rather than read per request.
///
/// WHY THIS EXISTS: EmbeddingUtil and VoiceUtil are created once, at boot
/// (RestServiceEventListener), and every consumer thereafter uses the bound instance —
/// VoiceService, VectorService, ModelService, AccessPoint's vector-store path and
/// VectorListFactory all take it from IOContext without ever re-resolving. Those write paths have
/// no request seam to hang a refresh on. Without a scheduled re-resolve, an edit made by Console7
/// (a separate JVM, so in-process cache invalidation cannot reach this one) or by
/// PATCH /rest/model would not take effect until Tomcat restarted — precisely the failure the
/// DB-backed configuration exists to avoid.
///
/// sd/face/tag are intentionally NOT refreshed here: they are resolved on each request, so the
/// 30s ServerConfigUtil TTL already bounds their propagation on its own.
///
/// Cost: inside the TTL each tick is three ConcurrentHashMap hits. Only the first tick after a TTL
/// expiry reads the database (three indexed single-row selects).
///
/// ORDERING: Threaded's constructor starts the thread immediately, and RestServiceEventListener
/// constructs the maintenance threads AFTER ioContext.setVectorUtil()/setVoiceUtil(), so the bound
/// singletons always exist before the first tick. The thread also sleeps threadDelay before its
/// first execute(), which adds further headroom.
public class ServerConfigRefreshThread extends Threaded {

	public static final Logger logger = LogManager.getLogger(ServerConfigRefreshThread.class);

	public ServerConfigRefreshThread() {
		super();
	}

	@Override
	public void execute() {
		ServerConfigUtil.refreshBoundServers();
	}
}
