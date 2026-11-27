package org.cote.accountmanager.util;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;

/// Service Status (aiDocs/ServiceStatusDesign.md - the design of record; section numbers below refer to it).
///
/// Orchestration, authorization and caching for the deployment + connection health report. Network work
/// lives in ConnectionProbeUtil. Service7's SystemCheckService is pure transport over the public methods
/// here; every method that returns the full report or a probe result calls
/// AdminMaintenanceUtil.requireAdministrator itself, so an in-JVM caller gets the same 401/403 as HTTP.
///
/// WHAT THIS NEVER DOES (§2, §3.1, §5):
///   - accept a URL from a caller. Inputs are a connection objectId, a check name from DEPLOYMENT_CHECKS,
///     and `deep`;
///   - persist anything. The only state is the in-memory cache below. Deep chat runs on an identity-free,
///     in-memory chatConfig (§4.1); deep embedding on a scratch EmbeddingUtil (§4.2);
///   - put a URL, host, response body, exception message or generated text into `detail`. Every check
///     carries a reason code and fixed text chosen from it; URLs travel only in `target.url` (deployment)
///     and `serverUrl` (connections), and both are redacted per caller at serve time;
///   - hand an apiKey to a map. The key is read in-process (AccessPoint.find, projected) only to send the
///     probe's auth header and is reduced to `hasApiKey` at once.
///
/// AUTHORIZATION OF THE CONNECTION LIST (§5, B4). AccessPoint.list authorizes the query SHAPE only
/// (model-api.md: "list is NOT a per-record authorization boundary"), so every listed record is then
/// filtered through AuthorizationUtil.canRead as the caller. A connection referenced by a readable
/// chatConfig but not itself readable is reported in `undetermined` and makes the view `complete:false`.
///
/// SANCTIONED PBAC BYPASSES (§5.5): ServerConfigUtil's own internal read of the six /System records (no
/// record leaves it), and the `vectors` row's raw-SQL deployment-wide counts, which are served ONLY to
/// /System administrators and removed at serve time for everyone else (N1).
///
/// CACHE (§6, B5). The deployment section is one volatile report shared by every caller - safe because no
/// user's view shapes it (redaction is per caller, at serve time) - with a 60 s TTL and single-flight, so a
/// burst of /summary calls from plain users probes once. Per-user connection sections live in a bounded map
/// (64 users), served only to the user that computed them, cached only if the invalidation epoch did not
/// move while computing. PROPAGATION: an edit made through generic PATCH /rest/model, Console7 or another
/// JVM invalidates nothing here; it appears after at most 30 s (ServerConfigUtil TTL) + 60 s (this cache),
/// or on the next forced run. In-JVM ServerConfigUtil.putConnection calls invalidate().
public final class SystemCheckUtil {

	private static final Logger logger = LogManager.getLogger(SystemCheckUtil.class);

	public static final long TTL_MS = 60000L;
	public static final int DEEP_TIMEOUT_SECONDS = 30;
	public static final int POOL_SIZE = 8;
	public static final int USER_CACHE_CAP = 64;
	private static final int LIST_PAGE = 100;
	private static final int LIST_MAX = 1000;
	/// Output cap for the deep chat. Small, but not so small that a reasoning model is cut off before it
	/// emits anything.
	private static final int DEEP_CHAT_OUTPUT_CAP = 64;
	private static final String DEEP_CHAT_PROMPT = "Reply with the single word OK.";
	private static final String DEEP_EMBED_TEXT = "system check";

	public static final String CHECK_DB = "db";
	public static final String CHECK_EMBEDDING = ServerConfigUtil.SERVER_EMBEDDING;
	public static final String CHECK_EMBEDDING_CONFIG = "embedding.config";
	public static final String CHECK_VECTORS = "vectors";

	/// The whitelist for POST /server/{name}, in display order.
	public static final List<String> DEPLOYMENT_CHECKS = Collections.unmodifiableList(Arrays.asList(
		CHECK_DB, CHECK_EMBEDDING, CHECK_EMBEDDING_CONFIG, CHECK_VECTORS,
		ServerConfigUtil.SERVER_SD, ServerConfigUtil.SERVER_FACE, ServerConfigUtil.SERVER_TAG,
		ServerConfigUtil.SERVER_VOICE_TTS, ServerConfigUtil.SERVER_VOICE_STT));

	/// The only names /summary may ever emit.
	public static final List<String> SUMMARY_NAMES = Collections.unmodifiableList(Arrays.asList(
		CHECK_DB, CHECK_EMBEDDING, ServerConfigUtil.SERVER_SD, ServerConfigUtil.SERVER_FACE, ServerConfigUtil.SERVER_TAG,
		ServerConfigUtil.SERVER_VOICE_TTS, ServerConfigUtil.SERVER_VOICE_STT));

	public static final String STATUS_OK = "ok";
	public static final String STATUS_WARN = "warn";
	public static final String STATUS_FAIL = "fail";
	public static final String STATUS_SKIP = "skip";
	public static final String OVERALL_OK = "ok";
	public static final String OVERALL_DEGRADED = "degraded";
	public static final String OVERALL_FAIL = "fail";

	public static final String DEPTH_PROBE = "probe";
	public static final String DEPTH_DEEP = "deep";

	public static final String SOURCE_RECORD = "record";
	public static final String SOURCE_LIVE = "live";
	public static final String SOURCE_DEFAULT = "default";
	public static final String SOURCE_NONE = "none";

	public static final String REASON_OK = ConnectionProbeUtil.REASON_OK;
	public static final String REASON_UNREACHABLE = ConnectionProbeUtil.REASON_UNREACHABLE;
	public static final String REASON_TIMEOUT = ConnectionProbeUtil.REASON_TIMEOUT;
	public static final String REASON_TLS = ConnectionProbeUtil.REASON_TLS;
	public static final String REASON_AUTH = ConnectionProbeUtil.REASON_AUTH;
	public static final String REASON_MODEL_MISSING = ConnectionProbeUtil.REASON_MODEL_MISSING;
	public static final String REASON_NOT_CONFIGURED = ConnectionProbeUtil.REASON_NOT_CONFIGURED;
	public static final String REASON_EMPTY_RESPONSE = ConnectionProbeUtil.REASON_EMPTY_RESPONSE;
	public static final String REASON_TYPE_URL_MISMATCH = "type_url_mismatch";
	public static final String REASON_EMULATOR = "emulator";
	public static final String REASON_DEEP_REQUIRED = "deep_required";
	public static final String REASON_WIDTH_MISMATCH = "width_mismatch";
	public static final String REASON_IN_PROGRESS = "in_progress";
	public static final String REASON_UNREADABLE = "unreadable";

	public static final String PROPAGATION = "Edits made through the generic model API, the console or another server instance"
		+ " appear here after at most 30 s (server configuration cache) + 60 s (this report), or on the next Run.";

	private static final String DETAIL_NO_DEEP_FORM = " This check has no deep form; the standard probe was run.";

	private static final Pattern TAG_MODEL_LOADED = Pattern.compile("\"model_loaded\"\\s*:\\s*true");

	/// Fields projected for the connection LIST. No apiKey - nothing listed ever carries the key.
	private static final String[] CONN_LIST_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH,
		FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM, "serverUrl"
	};
	/// The in-process read that carries the key. Its result never reaches a map.
	private static final String[] CONN_SECRET_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID,
		"serverUrl", "apiKey", FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM
	};
	private static final String[] CFG_LIST_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID,
		FieldNames.FIELD_ORGANIZATION_ID, "model", "apiVersion", "serviceType", "connection"
	};
	/// The transport fields copied onto the identity-free deep-chat config (§4.1).
	private static final String[] CFG_TRANSPORT_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID,
		"model", "analyzeModel", "serviceType", "apiVersion", "chatOptions"
	};

	private SystemCheckUtil() {
	}

	// ───────────────────────────────── executors ─────────────────────────────────

	private static ExecutorService probePool;
	private static ExecutorService deepChatExec;
	private static ExecutorService deepEmbedExec;
	private static final AtomicBoolean DEEP_CHAT_BUSY = new AtomicBoolean(false);
	private static final AtomicBoolean DEEP_EMBED_BUSY = new AtomicBoolean(false);

	private static ThreadFactory daemon(String prefix) {
		AtomicInteger n = new AtomicInteger(0);
		return r -> {
			Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	private static synchronized ExecutorService probePool() {
		if(probePool == null || probePool.isShutdown()) {
			probePool = Executors.newFixedThreadPool(POOL_SIZE, daemon("am7-syscheck-probe"));
		}
		return probePool;
	}

	private static synchronized ExecutorService deepChatExec() {
		if(deepChatExec == null || deepChatExec.isShutdown()) {
			deepChatExec = Executors.newSingleThreadExecutor(daemon("am7-syscheck-deepchat"));
		}
		return deepChatExec;
	}

	private static synchronized ExecutorService deepEmbedExec() {
		if(deepEmbedExec == null || deepEmbedExec.isShutdown()) {
			deepEmbedExec = Executors.newSingleThreadExecutor(daemon("am7-syscheck-deepembed"));
		}
		return deepEmbedExec;
	}

	/// Called from RestServiceEventListener.shutdown(). Idempotent; a later call re-creates the pools.
	public static synchronized void shutdown() {
		for(ExecutorService ex : new ExecutorService[] { probePool, deepChatExec, deepEmbedExec }) {
			if(ex != null) {
				ex.shutdownNow();
			}
		}
		probePool = null;
		deepChatExec = null;
		deepEmbedExec = null;
		invalidate();
	}

	public static boolean isDeepChatBusy() {
		return DEEP_CHAT_BUSY.get();
	}

	public static boolean isDeepEmbeddingBusy() {
		return DEEP_EMBED_BUSY.get();
	}

	private static AdminMaintenanceException busy() {
		return new AdminMaintenanceException(409, "A deep test is already running");
	}

	private static void acquire(AtomicBoolean flag) {
		if(!flag.compareAndSet(false, true)) {
			throw busy();
		}
	}

	/// Submit to a deep executor whose flag the caller has ALREADY acquired. The worker releases the flag
	/// when it finishes, not the waiting caller: a hung deep call keeps its executor busy (and later deep
	/// requests answer 409) until it really ends.
	private static <T> Future<T> submitAcquired(AtomicBoolean flag, ExecutorService ex, Callable<T> task) {
		try {
			return ex.submit(() -> {
				try {
					return task.call();
				}
				finally {
					flag.set(false);
				}
			});
		}
		catch(RejectedExecutionException e) {
			flag.set(false);
			throw new AdminMaintenanceException(500, "The system check executor is not available");
		}
	}

	// ───────────────────────────────── cache ─────────────────────────────────

	private static final class DeploymentReport {
		final List<Map<String, Object>> checks;
		final long checkedAt;
		final long expires;
		DeploymentReport(List<Map<String, Object>> checks, long checkedAt) {
			this.checks = checks;
			this.checkedAt = checkedAt;
			this.expires = checkedAt + TTL_MS;
		}
	}

	private static final class ConnectionSection {
		final List<Map<String, Object>> rows;
		final boolean complete;
		final List<Map<String, Object>> undetermined;
		final long checkedAt;
		final long expires;
		ConnectionSection(List<Map<String, Object>> rows, boolean complete, List<Map<String, Object>> undetermined, long checkedAt) {
			this.rows = rows;
			this.complete = complete;
			this.undetermined = undetermined;
			this.checkedAt = checkedAt;
			this.expires = checkedAt + TTL_MS;
		}
	}

	private static volatile DeploymentReport deploymentCache = null;
	private static final Object DEPLOY_LOCK = new Object();
	private static final AtomicLong GLOBAL_EPOCH = new AtomicLong(0L);
	private static final Map<Long, AtomicLong> USER_EPOCH = new ConcurrentHashMap<>();
	private static final Map<Long, ConnectionSection> USER_CACHE = new ConcurrentHashMap<>();

	/// Drop every cached result. Called by ServerConfigUtil.putConnection and shutdown().
	public static void invalidate() {
		GLOBAL_EPOCH.incrementAndGet();
		deploymentCache = null;
		USER_CACHE.clear();
	}

	/// Drop one user's connection section (a write path in this JVM that changed what that user can see).
	public static void invalidateUser(long userId) {
		USER_EPOCH.computeIfAbsent(userId, k -> new AtomicLong(0L)).incrementAndGet();
		USER_CACHE.remove(userId);
	}

	private static long userEpoch(long userId) {
		return USER_EPOCH.computeIfAbsent(userId, k -> new AtomicLong(0L)).get();
	}

	private static void putUser(long userId, ConnectionSection cs, long globalEpoch, long userEpoch) {
		if(GLOBAL_EPOCH.get() != globalEpoch || userEpoch(userId) != userEpoch) {
			return;
		}
		USER_CACHE.put(userId, cs);
		if(USER_CACHE.size() > USER_CACHE_CAP) {
			Long oldest = null;
			long oldestAt = Long.MAX_VALUE;
			for(Map.Entry<Long, ConnectionSection> e : USER_CACHE.entrySet()) {
				if(e.getValue().checkedAt < oldestAt) {
					oldestAt = e.getValue().checkedAt;
					oldest = e.getKey();
				}
			}
			if(oldest != null) {
				USER_CACHE.remove(oldest);
			}
		}
	}

	// ───────────────────────────────── public API ─────────────────────────────────

	/// GET /summary: open to any authenticated user. Names only, from SUMMARY_NAMES; no URL, host or token.
	/// A cold call runs the (non-deep) deployment probes once, single-flight, and persists nothing.
	public static Map<String, Object> summary(BaseRecord user, Map<String, String> defaults) {
		if(user == null) {
			throw new AdminMaintenanceException(401, "Not authenticated");
		}
		DeploymentReport dep = deployment(defaults);
		List<String> degraded = new ArrayList<>();
		for(Map<String, Object> c : dep.checks) {
			String name = (String) c.get("name");
			String st = (String) c.get("status");
			if(SUMMARY_NAMES.contains(name) && (STATUS_WARN.equals(st) || STATUS_FAIL.equals(st))) {
				degraded.add(name);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("status", overall(dep.checks, Collections.emptyList()));
		out.put("degraded", degraded);
		out.put("checkedAt", dep.checkedAt);
		return out;
	}

	/// GET "" (force=false, deep=false): the last report for the caller, computing it when the caches are
	/// empty or expired. POST ?deep= (force=true): a forced full run. 409 when a deep run is requested while
	/// another deep test is in progress.
	public static Map<String, Object> report(BaseRecord user, Map<String, String> defaults, boolean force, boolean deep) {
		long orgId = AdminMaintenanceUtil.requireAdministrator(user);
		boolean systemAdmin = AdminMaintenanceUtil.isSystemOrganizationAdministrator(user, orgId);
		long userId = idOf(user);

		if(deep) {
			acquire(DEEP_CHAT_BUSY);
			try {
				acquire(DEEP_EMBED_BUSY);
			}
			catch(AdminMaintenanceException e) {
				DEEP_CHAT_BUSY.set(false);
				throw e;
			}
		}
		/// Ownership of the two deep flags passes to computeDeployment / computeConnections, which hand
		/// them to their workers or release them themselves on every path.
		DeploymentReport dep;
		try {
			if(deep) {
				dep = computeDeployment(defaults, true);
			}
			else if(force) {
				dep = computeDeployment(defaults, false);
			}
			else {
				dep = deployment(defaults);
			}
		}
		catch(RuntimeException e) {
			if(deep) {
				DEEP_CHAT_BUSY.set(false);
			}
			throw e;
		}
		if(force || deep) {
			/// A fresh run replaces the shared section (deep results included: they are not shaped by any
			/// user either).
			deploymentCache = dep;
		}

		ConnectionSection cs = null;
		if(!force && !deep) {
			ConnectionSection cached = USER_CACHE.get(userId);
			if(cached != null && cached.expires > System.currentTimeMillis()) {
				cs = cached;
			}
		}
		if(cs == null) {
			long ge = GLOBAL_EPOCH.get();
			long ue = userEpoch(userId);
			cs = computeConnections(user, systemAdmin, true, deep);
			putUser(userId, cs, ge, ue);
		}
		return assemble(dep, cs, systemAdmin);
	}

	/// GET /connections: every connection the caller can read, with `referencedBy` and `hasApiKey`, without
	/// probing anything.
	public static Map<String, Object> connections(BaseRecord user) {
		long orgId = AdminMaintenanceUtil.requireAdministrator(user);
		boolean systemAdmin = AdminMaintenanceUtil.isSystemOrganizationAdministrator(user, orgId);
		ConnectionSection cs = computeConnections(user, systemAdmin, false, false);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("connections", cs.rows);
		out.put("complete", cs.complete);
		out.put("undetermined", cs.undetermined);
		out.put("propagation", PROPAGATION);
		out.put("checkedAt", cs.checkedAt);
		return out;
	}

	/// POST /connection/{objectId}?deep=: test one connection. 404 when the caller cannot read it (absent
	/// and unreadable are deliberately indistinguishable); 409 for deep while another deep chat runs.
	public static Map<String, Object> testConnection(BaseRecord user, String objectId, boolean deep) {
		long orgId = AdminMaintenanceUtil.requireAdministrator(user);
		boolean systemAdmin = AdminMaintenanceUtil.isSystemOrganizationAdministrator(user, orgId);
		if(objectId == null || objectId.isBlank()) {
			throw new AdminMaintenanceException(404, "Connection not found");
		}
		BaseRecord conn = findReadable(user, ModelNames.MODEL_CONNECTION, FieldNames.FIELD_OBJECT_ID, objectId.trim(), CONN_LIST_FIELDS);
		if(conn == null) {
			throw new AdminMaintenanceException(404, "Connection not found");
		}
		long connId = idOf(conn);
		List<BaseRecord> cfgs = listReadable(user, OlioModelNames.MODEL_CHAT_CONFIG, CFG_LIST_FIELDS);
		List<BaseRecord> refs = new ArrayList<>();
		for(BaseRecord cfg : cfgs) {
			if(connIdOf(cfg) == connId) {
				refs.add(cfg);
			}
		}
		if(deep) {
			acquire(DEEP_CHAT_BUSY);
		}
		Map<String, Object> row = connectionRow(conn, refs, systemAdmin);
		Map<String, Object> result;
		if(deep) {
			result = waitDeepChat(user, connId, refs);
		}
		else {
			result = await(probePool().submit(() -> probeConnection(user, connId, refs)), ConnectionProbeUtil.TIMEOUT_SECONDS + 1, STATUS_FAIL);
		}
		applySecretFacts(row, result);
		row.putAll(result);
		return row;
	}

	/// POST /server/{name}?deep=: test one deployment check. 400 outside DEPLOYMENT_CHECKS. `vectors` is a
	/// /System-administrator row (N1), so any other administrator gets 403 for it. deep=true on a check with
	/// no deep form runs the standard probe and says so in `detail`.
	public static Map<String, Object> testServer(BaseRecord user, Map<String, String> defaults, String name, boolean deep) {
		long orgId = AdminMaintenanceUtil.requireAdministrator(user);
		boolean systemAdmin = AdminMaintenanceUtil.isSystemOrganizationAdministrator(user, orgId);
		if(name == null || !DEPLOYMENT_CHECKS.contains(name)) {
			throw new AdminMaintenanceException(400, "Unknown check name");
		}
		if(CHECK_VECTORS.equals(name) && !systemAdmin) {
			throw new AdminMaintenanceException(403, "The vectors check is shown to /System administrators only");
		}
		Map<String, Object> check;
		if(deep && CHECK_EMBEDDING.equals(name)) {
			acquire(DEEP_EMBED_BUSY);
			check = awaitDeepEmbedding(liveEmbedding());
		}
		else {
			final Map<String, String> d = defaults;
			check = await(probePool().submit(() -> runCheck(name, d)), ConnectionProbeUtil.TIMEOUT_SECONDS + 1, optionalStatus(name));
			if(check.get("name") == null) {
				check.put("name", name);
			}
			if(deep) {
				check.put("detail", String.valueOf(check.get("detail")) + DETAIL_NO_DEEP_FORM);
			}
		}
		return shape(check, systemAdmin);
	}

	// ───────────────────────────────── deployment ─────────────────────────────────

	private static DeploymentReport deployment(Map<String, String> defaults) {
		DeploymentReport d = deploymentCache;
		if(d != null && d.expires > System.currentTimeMillis()) {
			return d;
		}
		synchronized(DEPLOY_LOCK) {
			d = deploymentCache;
			if(d != null && d.expires > System.currentTimeMillis()) {
				return d;
			}
			long epoch = GLOBAL_EPOCH.get();
			d = computeDeployment(defaults, false);
			if(GLOBAL_EPOCH.get() == epoch) {
				deploymentCache = d;
			}
			return d;
		}
	}

	/// deep=true REQUIRES the caller to hold DEEP_EMBED_BUSY; it is handed to the deep-embedding worker.
	private static DeploymentReport computeDeployment(Map<String, String> defaults, boolean deep) {
		long start = System.currentTimeMillis();
		Map<String, Object> deepEmbed = null;
		if(deep) {
			/// First statement: awaitDeepEmbedding hands the flag to the worker (or releases it).
			deepEmbed = awaitDeepEmbedding(liveEmbedding());
			start = System.currentTimeMillis();
		}
		Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
		for(String name : DEPLOYMENT_CHECKS) {
			if(deep && CHECK_EMBEDDING.equals(name)) {
				continue;
			}
			futures.put(name, probePool().submit(() -> runCheck(name, defaults)));
		}
		long deadline = start + (ConnectionProbeUtil.TIMEOUT_SECONDS + 1) * 1000L;
		List<Map<String, Object>> checks = new ArrayList<>();
		for(String name : DEPLOYMENT_CHECKS) {
			if(deep && CHECK_EMBEDDING.equals(name)) {
				checks.add(deepEmbed);
				continue;
			}
			long remaining = Math.max(1L, deadline - System.currentTimeMillis());
			Map<String, Object> c = awaitMillis(futures.get(name), remaining, optionalStatus(name));
			if(c.get("name") == null) {
				c.put("name", name);
			}
			checks.add(c);
		}
		return new DeploymentReport(checks, System.currentTimeMillis());
	}

	/// The status a check gets when its probe does not come back in time.
	private static String optionalStatus(String name) {
		if(CHECK_DB.equals(name) || CHECK_EMBEDDING.equals(name)) {
			return STATUS_FAIL;
		}
		if(CHECK_VECTORS.equals(name) || CHECK_EMBEDDING_CONFIG.equals(name)) {
			return STATUS_SKIP;
		}
		return STATUS_WARN;
	}

	/// Exactly one deployment check, by name (fast form). Runs on the probe pool.
	private static Map<String, Object> runCheck(String name, Map<String, String> defaults) {
		switch(name) {
			case CHECK_DB:
				return checkDb();
			case CHECK_EMBEDDING:
				return checkEmbeddingProbe(liveEmbedding());
			case CHECK_EMBEDDING_CONFIG:
				return checkEmbeddingConfig(liveEmbedding());
			case CHECK_VECTORS:
				return checkVectors();
			case ServerConfigUtil.SERVER_TAG:
				return checkTag(defaults);
			default:
				return checkOptionalServer(name, defaults);
		}
	}

	private static Map<String, Object> newCheck(String name, String depth, String source) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("name", name);
		c.put("status", STATUS_SKIP);
		c.put("reason", REASON_NOT_CONFIGURED);
		c.put("detail", detailFor(REASON_NOT_CONFIGURED));
		c.put("latencyMs", 0L);
		c.put("checkedAt", System.currentTimeMillis());
		c.put("depth", depth);
		c.put("source", source);
		c.put("bounded", true);
		c.put("urlRedacted", false);
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("url", null);
		target.put("type", null);
		target.put("hasApiKey", false);
		c.put("target", target);
		return c;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> target(Map<String, Object> c) {
		return (Map<String, Object>) c.get("target");
	}

	private static void outcome(Map<String, Object> c, String status, String reason, String detail, long latencyMs) {
		c.put("status", status);
		c.put("reason", reason);
		c.put("detail", detail != null ? detail : detailFor(reason));
		c.put("latencyMs", latencyMs);
		c.put("checkedAt", System.currentTimeMillis());
	}

	private static Map<String, Object> checkDb() {
		Map<String, Object> c = newCheck(CHECK_DB, DEPTH_PROBE, SOURCE_LIVE);
		IOContext ctx = IOSystem.getActiveContext();
		DBUtil db = (ctx != null ? ctx.getDbUtil() : null);
		if(db == null || db.getDataSource() == null) {
			outcome(c, STATUS_FAIL, REASON_NOT_CONFIGURED, "No database is configured", 0L);
			return c;
		}
		target(c).put("type", db.getConnectionType() != null ? db.getConnectionType().toString().toLowerCase() : null);
		long start = System.currentTimeMillis();
		try(Connection con = db.getDataSource().getConnection(); Statement st = con.createStatement()) {
			st.setQueryTimeout(ConnectionProbeUtil.TIMEOUT_SECONDS);
			try(ResultSet rs = st.executeQuery("select 1")) {
				if(rs.next()) {
					outcome(c, STATUS_OK, REASON_OK, "The database answered", System.currentTimeMillis() - start);
					return c;
				}
			}
			outcome(c, STATUS_FAIL, REASON_EMPTY_RESPONSE, null, System.currentTimeMillis() - start);
		}
		catch(Exception e) {
			logger.warn("System check: database probe failed (" + e.getClass().getSimpleName() + ")");
			outcome(c, STATUS_FAIL, REASON_UNREACHABLE, "The database could not be reached", System.currentTimeMillis() - start);
		}
		return c;
	}

	private static EmbeddingUtil liveEmbedding() {
		IOContext ctx = IOSystem.getActiveContext();
		VectorUtil vu = (ctx != null ? ctx.getVectorUtil() : null);
		return (vu != null ? vu.getEmbedUtil() : null);
	}

	private static String lower(Object e) {
		return (e == null ? null : e.toString().toLowerCase());
	}

	/// Fast embedding probe against the LIVE singleton's URL (source "live"), by its service type.
	/// Unreachable is fail: embeddings are not optional.
	static Map<String, Object> checkEmbeddingProbe(EmbeddingUtil live) {
		Map<String, Object> c = newCheck(CHECK_EMBEDDING, DEPTH_PROBE, SOURCE_LIVE);
		if(live == null) {
			c.put("source", SOURCE_NONE);
			outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, "Vector support is not configured in this process", 0L);
			return c;
		}
		EmbeddingUtil.Endpoint ep = live.getEndpoint();
		String url = ep.getServerUrl();
		String token = ep.getAuthorizationToken();
		LLMServiceEnumType type = live.getServiceType();
		target(c).put("url", url);
		target(c).put("type", lower(type));
		target(c).put("hasApiKey", token != null && !token.isBlank());
		if(url == null || url.isBlank()) {
			outcome(c, STATUS_FAIL, REASON_NOT_CONFIGURED, null, 0L);
			return c;
		}
		if(type == LLMServiceEnumType.LOCAL) {
			ConnectionProbeUtil.Probe p = ConnectionProbeUtil.get(ConnectionProbeUtil.join(url, "/heartbeat"), null);
			if(p.getHttpStatus() == 200) {
				outcome(c, STATUS_OK, REASON_OK, "The embedding service heartbeat answered", p.getLatencyMs());
			}
			else {
				outcome(c, STATUS_FAIL, p.statusReason(), null, p.getLatencyMs());
			}
			return c;
		}
		if(type == LLMServiceEnumType.OPENAI_COMPAT) {
			String u = url.trim();
			String modelsUrl = (u.endsWith("/embeddings") ? u.substring(0, u.length() - "/embeddings".length()) + "/models" : ConnectionProbeUtil.join(u, "/v1/models"));
			Map<String, String> h = new HashMap<>();
			if(token != null && !token.isBlank()) {
				h.put("Authorization", "Bearer " + token);
			}
			ConnectionProbeUtil.Probe p = ConnectionProbeUtil.get(modelsUrl, h);
			if(p.getHttpStatus() == 401 || p.getHttpStatus() == 403) {
				outcome(c, STATUS_FAIL, REASON_AUTH, null, p.getLatencyMs());
			}
			else if(p.getHttpStatus() == 200) {
				List<String> models = ConnectionProbeUtil.parseModelNames(p.getBody(), false);
				String want = live.getEmbeddingModel();
				if(models == null) {
					outcome(c, STATUS_FAIL, REASON_EMPTY_RESPONSE, null, p.getLatencyMs());
				}
				else if(want != null && !ConnectionProbeUtil.hasModel(models, want)) {
					outcome(c, STATUS_WARN, REASON_MODEL_MISSING, "The configured embedding model is not listed by this server", p.getLatencyMs());
				}
				else {
					outcome(c, STATUS_OK, REASON_OK, "The embedding server listed its models", p.getLatencyMs());
				}
			}
			else {
				outcome(c, STATUS_FAIL, p.statusReason(), null, p.getLatencyMs());
			}
			return c;
		}
		if(type == LLMServiceEnumType.OPENAI) {
			/// Azure: there is no cheap authenticated embeddings probe, so reachability only.
			String origin = origin(url);
			ConnectionProbeUtil.Probe p = ConnectionProbeUtil.get(origin, null);
			if(p.isAnswered()) {
				outcome(c, STATUS_OK, REASON_DEEP_REQUIRED, null, p.getLatencyMs());
			}
			else {
				outcome(c, STATUS_FAIL, p.getReason(), null, p.getLatencyMs());
			}
			return c;
		}
		outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, "Unsupported embedding service type", 0L);
		return c;
	}

	private static String origin(String url) {
		try {
			URI u = URI.create(url.trim());
			if(u.getScheme() == null || u.getHost() == null) {
				return null;
			}
			return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/";
		}
		catch(IllegalArgumentException e) {
			return null;
		}
	}

	static Map<String, Object> checkEmbeddingConfig(EmbeddingUtil live) {
		Map<String, Object> c = newCheck(CHECK_EMBEDDING_CONFIG, DEPTH_PROBE, SOURCE_LIVE);
		if(live == null) {
			c.put("source", SOURCE_NONE);
			outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, "Vector support is not configured in this process", 0L);
			return c;
		}
		String url = live.getServerUrl();
		target(c).put("url", url);
		target(c).put("type", lower(live.getServiceType()));
		target(c).put("hasApiKey", live.getAuthorizationToken() != null && !live.getAuthorizationToken().isBlank());
		String code = EmbeddingUtil.describeTypeUrlMismatch(live.getServiceType(), url);
		if(code == null) {
			outcome(c, STATUS_OK, REASON_OK, "The embedding type and URL agree in shape", 0L);
		}
		else {
			outcome(c, STATUS_WARN, REASON_TYPE_URL_MISMATCH, mismatchDetail(code), 0L);
		}
		return c;
	}

	private static String mismatchDetail(String code) {
		switch(code) {
			case EmbeddingUtil.MISMATCH_UNPARSEABLE_URL:
				return "The embedding URL is not a parseable URL";
			case EmbeddingUtil.MISMATCH_LOCAL_OPENAI_SHAPED_URL:
				return "embedding.type is local but the URL is an OpenAI-shaped embeddings endpoint";
			case EmbeddingUtil.MISMATCH_OPENAI_HOST_ONLY_URL:
				return "embedding.type is OpenAI-shaped but the URL has no path (the local service shape)";
			case EmbeddingUtil.MISMATCH_COMPAT_AZURE_URL:
				return "embedding.type is openai_compat but the URL is an Azure OpenAI deployment";
			case EmbeddingUtil.MISMATCH_OPENAI_COMPAT_URL:
				return "embedding.type is openai (Azure) but the URL is a /v1/embeddings endpoint";
			default:
				return "The embedding type and URL disagree in shape";
		}
	}

	/// Deployment-wide vector row counts (all organizations, raw SQL). Sanctioned bypass, /System admins
	/// only - removed at serve time for everyone else. The table list is DERIVED from every registered
	/// model that inherits common.vectorExt, never hard-coded.
	private static Map<String, Object> checkVectors() {
		Map<String, Object> c = newCheck(CHECK_VECTORS, DEPTH_PROBE, SOURCE_LIVE);
		IOContext ctx = IOSystem.getActiveContext();
		DBUtil db = (ctx != null ? ctx.getDbUtil() : null);
		if(db == null || db.getDataSource() == null) {
			outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, null, 0L);
			return c;
		}
		long start = System.currentTimeMillis();
		Map<String, Object> counts = new LinkedHashMap<>();
		long total = 0L;
		try(Connection con = db.getDataSource().getConnection(); Statement st = con.createStatement()) {
			st.setQueryTimeout(ConnectionProbeUtil.TIMEOUT_SECONDS);
			for(String model : new ArrayList<>(ModelNames.MODELS)) {
				if(ModelNames.MODEL_VECTOR_EXT.equals(model)) {
					continue;
				}
				ModelSchema ms = null;
				try {
					ms = RecordFactory.getSchema(model);
				}
				catch(Exception e) {
					/// Not loadable in this process: not a vector table we can count.
				}
				if(ms == null || !RecordUtil.inherits(ms, ModelNames.MODEL_VECTOR_EXT) || !db.haveTable(model)) {
					continue;
				}
				/// The table name is derived from a registered model name, never from caller input.
				try(ResultSet rs = st.executeQuery("select count(*) from " + db.getTableName(model))) {
					long n = (rs.next() ? rs.getLong(1) : 0L);
					counts.put(model, n);
					total += n;
				}
			}
			outcome(c, STATUS_OK, REASON_OK, "Changing the embedding provider invalidates " + total
				+ " stored vectors (deployment-wide, all organizations)", System.currentTimeMillis() - start);
			c.put("counts", counts);
			c.put("total", total);
		}
		catch(Exception e) {
			logger.warn("System check: vector count failed (" + e.getClass().getSimpleName() + ")");
			outcome(c, STATUS_SKIP, REASON_UNREACHABLE, "Vector counts could not be read", System.currentTimeMillis() - start);
		}
		return c;
	}

	/// Where a /System server's URL comes from: the record (ServerConfigUtil, internal bypass), else the
	/// web.xml default handed in by Service7, else nothing.
	private static Map<String, Object> resolveServer(String name, Map<String, String> defaults) {
		Map<String, Object> c = newCheck(name, DEPTH_PROBE, SOURCE_NONE);
		String rec = ServerConfigUtil.getServerUrl(name, null);
		if(rec != null && !rec.isBlank()) {
			c.put("source", SOURCE_RECORD);
			target(c).put("url", rec.trim());
			target(c).put("hasApiKey", ServerConfigUtil.hasApiKey(name));
		}
		else {
			String d = (defaults != null ? defaults.get(ServerConfigUtil.getInitParameterName(name)) : null);
			if(d != null && !d.isBlank()) {
				c.put("source", SOURCE_DEFAULT);
				target(c).put("url", d.trim());
			}
		}
		target(c).put("type", "http");
		return c;
	}

	/// sd / face / voice.tts / voice.stt: any HTTP answer below 500 is reachable. Optional, so an
	/// unreachable server is warn, never fail.
	private static Map<String, Object> checkOptionalServer(String name, Map<String, String> defaults) {
		Map<String, Object> c = resolveServer(name, defaults);
		String url = (String) target(c).get("url");
		if(url == null) {
			outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, null, 0L);
			return c;
		}
		ConnectionProbeUtil.Probe p = ConnectionProbeUtil.get(url, null);
		if(p.isAnswered() && p.getHttpStatus() < 500) {
			outcome(c, STATUS_OK, REASON_OK, "The server answered", p.getLatencyMs());
		}
		else if(p.isAnswered()) {
			outcome(c, STATUS_WARN, p.statusReason(), null, p.getLatencyMs());
		}
		else {
			outcome(c, STATUS_WARN, p.getReason(), null, p.getLatencyMs());
		}
		return c;
	}

	/// tag: the same rule as ImageTagUtil.isHealthy (GET /health, 200 and "model_loaded":true), made here
	/// on the 5 s probe client; ImageTagUtil builds a 10 s-connect client and registers it globally.
	private static Map<String, Object> checkTag(Map<String, String> defaults) {
		Map<String, Object> c = resolveServer(ServerConfigUtil.SERVER_TAG, defaults);
		String url = (String) target(c).get("url");
		if(url == null) {
			outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, null, 0L);
			return c;
		}
		ConnectionProbeUtil.Probe p = ConnectionProbeUtil.get(ConnectionProbeUtil.join(url, "/health"), null);
		if(p.getHttpStatus() == 200 && p.getBody() != null && TAG_MODEL_LOADED.matcher(p.getBody()).find()) {
			outcome(c, STATUS_OK, REASON_OK, "The tagger is up and its model is loaded", p.getLatencyMs());
		}
		else if(p.getHttpStatus() == 200) {
			outcome(c, STATUS_WARN, REASON_MODEL_MISSING, "The tagger answered but its model is not loaded", p.getLatencyMs());
		}
		else {
			outcome(c, STATUS_WARN, p.statusReason(), null, p.getLatencyMs());
		}
		return c;
	}

	// ───────────────────────────────── deep embedding (§4.2) ─────────────────────────────────

	/// Deep embedding for the LIVE singleton: scratch EmbeddingUtil, URL/token from ServerConfigUtil (the
	/// live values as the fallback). The caller holds DEEP_EMBED_BUSY; it is handed to the worker.
	private static Map<String, Object> awaitDeepEmbedding(EmbeddingUtil live) {
		if(live == null) {
			DEEP_EMBED_BUSY.set(false);
			Map<String, Object> c = newCheck(CHECK_EMBEDDING, DEPTH_DEEP, SOURCE_NONE);
			c.put("bounded", false);
			outcome(c, STATUS_SKIP, REASON_NOT_CONFIGURED, "Vector support is not configured in this process", 0L);
			return c;
		}
		EmbeddingUtil.Endpoint ep = live.getEndpoint();
		String url = ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_EMBEDDING, ep.getServerUrl());
		String token = ServerConfigUtil.getAuthorizationToken(ServerConfigUtil.SERVER_EMBEDDING, ep.getAuthorizationToken());
		return awaitDeep(DEEP_EMBED_BUSY, deepEmbedExec(), () -> runDeepEmbedding(live, url, token), CHECK_EMBEDDING, false);
	}

	/// Deep embedding with an explicit live instance and endpoint, on the dedicated single-thread executor;
	/// 409 while another one runs. Public for TestSystemCheckUtil, which needs a live instance whose type
	/// disagrees with its URL - the production path is testServer/report, which resolve both themselves.
	/// Requires an administrator like every other probe entry point.
	public static Map<String, Object> deepEmbedding(BaseRecord user, EmbeddingUtil live, String url, String token) {
		long orgId = AdminMaintenanceUtil.requireAdministrator(user);
		boolean systemAdmin = AdminMaintenanceUtil.isSystemOrganizationAdministrator(user, orgId);
		acquire(DEEP_EMBED_BUSY);
		return shape(awaitDeep(DEEP_EMBED_BUSY, deepEmbedExec(), () -> runDeepEmbedding(live, url, token), CHECK_EMBEDDING, false), systemAdmin);
	}

	/// Runs ON the deep-embedding thread. No setter is ever called on `live`: type, model and dimensions
	/// are copied onto a scratch instance, whose own width baseline is what its getEmbedding() pins.
	private static Map<String, Object> runDeepEmbedding(EmbeddingUtil live, String url, String token) {
		Map<String, Object> c = newCheck(CHECK_EMBEDDING, DEPTH_DEEP, SOURCE_LIVE);
		c.put("bounded", false);
		String recUrl = ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_EMBEDDING, null);
		if(recUrl != null && recUrl.trim().equals(url != null ? url.trim() : null)) {
			c.put("source", SOURCE_RECORD);
		}
		target(c).put("url", url);
		target(c).put("type", lower(live.getServiceType()));
		target(c).put("hasApiKey", token != null && !token.isBlank());
		if(url == null || url.isBlank()) {
			outcome(c, STATUS_FAIL, REASON_NOT_CONFIGURED, null, 0L);
			return c;
		}
		long start = System.currentTimeMillis();
		try {
			EmbeddingUtil scratch = new EmbeddingUtil(live.getServiceType(), url, token);
			scratch.setEmbeddingModel(live.getEmbeddingModel());
			scratch.setEmbeddingDimensions(live.getEmbeddingDimensions());
			float[] emb = scratch.getEmbedding(DEEP_EMBED_TEXT);
			long lat = System.currentTimeMillis() - start;
			int observed = live.getObservedEmbeddingWidth();
			int expected = (observed > 0 ? observed : live.resolveExpectedDimensions());
			if(emb == null || emb.length == 0) {
				outcome(c, STATUS_FAIL, REASON_EMPTY_RESPONSE, "The embedding server returned no vector", lat);
			}
			else if(expected > 0 && emb.length != expected) {
				outcome(c, STATUS_FAIL, REASON_WIDTH_MISMATCH, "The embedding width (" + emb.length + ") does not match the expected width (" + expected + ")", lat);
			}
			else {
				outcome(c, STATUS_OK, REASON_OK, "The embedding server returned a " + emb.length + "-wide vector", lat);
			}
			c.put("width", emb != null ? emb.length : 0);
			c.put("expectedWidth", expected);
		}
		catch(Exception e) {
			logger.warn("System check: deep embedding failed (" + e.getClass().getSimpleName() + ")");
			outcome(c, STATUS_FAIL, ConnectionProbeUtil.classify(e), null, System.currentTimeMillis() - start);
		}
		return c;
	}

	// ───────────────────────────────── deep chat (§4.1) ─────────────────────────────────

	/// The caller holds DEEP_CHAT_BUSY; it is handed to the worker.
	private static Map<String, Object> waitDeepChat(BaseRecord user, long connId, List<BaseRecord> refs) {
		return awaitDeep(DEEP_CHAT_BUSY, deepChatExec(), () -> runDeepChat(user, connId, refs), null, true);
	}

	/// Runs ON the deep-chat thread. Builds an identity-free, in-memory chatConfig (no id/objectId/urn, all
	/// cadence counters and triggers zero, no characters, stream off) so Chat's trigger checks have nothing
	/// to write, then calls chat(req) ONLY - never continueChat, which saves sessions, events and titles.
	private static Map<String, Object> runDeepChat(BaseRecord user, long connId, List<BaseRecord> refs) {
		Map<String, Object> r = probeResult(DEPTH_DEEP);
		BaseRecord ref = null;
		String model = null;
		for(BaseRecord cfg : refs) {
			String m = cfg.get("model");
			if(m != null && !m.isBlank()) {
				ref = cfg;
				model = m.trim();
				break;
			}
		}
		ConnSecretProbe sp = secretProbe(user, connId, refs);
		if(sp.svc == LLMServiceEnumType.EMULATOR) {
			result(r, STATUS_SKIP, REASON_EMULATOR, null, 0L);
			return r;
		}
		if(model == null && sp.models != null && !sp.models.isEmpty()) {
			model = sp.models.get(0);
		}
		if(model == null) {
			result(r, STATUS_SKIP, REASON_NOT_CONFIGURED, "No model is available to test with", 0L);
			return r;
		}
		long start = System.currentTimeMillis();
		try {
			BaseRecord full = null;
			if(ref != null) {
				full = findReadable(user, OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, idOf(ref), CFG_TRANSPORT_FIELDS);
			}
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
			if(full != null) {
				for(String f : new String[] { "analyzeModel", "serviceType", "apiVersion", "chatOptions" }) {
					if(full.hasField(f) && full.get(f) != null) {
						cfg.set(f, full.get(f));
					}
				}
			}
			cfg.set("model", model);
			BaseRecord connRef = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION, new String[] { FieldNames.FIELD_ID });
			connRef.set(FieldNames.FIELD_ID, connId);
			cfg.set("connection", connRef);
			for(String f : new String[] { "lastKeyframeAt", "lastMemoryExtractionAt", "lastInteractionAt",
				"keyframeEvery", "memoryExtractionEvery", "interactionEvery", "remindEvery" }) {
				cfg.set(f, 0);
			}
			for(String f : new String[] { "extractMemories", "extractInteractions", "stream", "assist", "prune", "autoTitle", "complianceCheck" }) {
				cfg.set(f, false);
			}

			Chat chat = new Chat(user, cfg, null);
			chat.setPersistSession(false);
			chat.setRequestTimeout(DEEP_TIMEOUT_SECONDS);
			OpenAIRequest req = chat.newRequest(model);
			String cap = ChatUtil.getOutputCapField(cfg, chat.getServiceType());
			if(cap != null && !cap.isEmpty()) {
				req.set(cap, DEEP_CHAT_OUTPUT_CAP);
			}
			chat.newMessage(req, DEEP_CHAT_PROMPT);
			Chat.clearLastCallError();
			OpenAIResponse resp = chat.chat(req);
			/// ThreadLocals: read HERE, on the thread that ran chat().
			String err = Chat.getLastCallError();
			boolean unreachable = Chat.isLastCallUnreachable();
			String finish = Chat.getLastCallFinishReason();
			long lat = System.currentTimeMillis() - start;
			if(resp != null && err == null) {
				result(r, STATUS_OK, REASON_OK, "Model " + model + " answered" + (finish != null ? " (finish: " + finish + ")" : ""), lat);
			}
			else if(unreachable) {
				result(r, STATUS_FAIL, REASON_UNREACHABLE, "Model " + model + ": " + detailFor(REASON_UNREACHABLE), lat);
			}
			else if(err != null && err.toLowerCase().contains("timed out")) {
				result(r, STATUS_FAIL, REASON_TIMEOUT, "Model " + model + ": " + detailFor(REASON_TIMEOUT), lat);
			}
			else {
				result(r, STATUS_FAIL, REASON_EMPTY_RESPONSE, "Model " + model + ": " + detailFor(REASON_EMPTY_RESPONSE), lat);
			}
			r.put("model", model);
		}
		catch(Exception e) {
			logger.warn("System check: deep chat failed (" + e.getClass().getSimpleName() + ")");
			result(r, STATUS_FAIL, ConnectionProbeUtil.classify(e), null, System.currentTimeMillis() - start);
		}
		finally {
			Chat.clearLastCallError();
		}
		r.put("hasApiKey", sp.hasApiKey);
		return r;
	}

	/// Wait for a deep task (the flag was acquired by the caller and is released by the worker).
	private static Map<String, Object> awaitDeep(AtomicBoolean flag, ExecutorService ex, Callable<Map<String, Object>> task, String name, boolean connection) {
		Future<Map<String, Object>> f = submitAcquired(flag, ex, task);
		try {
			return f.get(DEEP_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS);
		}
		catch(TimeoutException e) {
			/// Not cancelled: an interrupted deep chat would still hold resources; the flag stays set until
			/// the worker really finishes, so further deep requests answer 409 meanwhile.
			return timeoutResult(name, DEPTH_DEEP, STATUS_FAIL, connection);
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return timeoutResult(name, DEPTH_DEEP, STATUS_FAIL, connection);
		}
		catch(Exception e) {
			logger.warn("System check: deep task failed (" + e.getClass().getSimpleName() + ")");
			Map<String, Object> r = timeoutResult(name, DEPTH_DEEP, STATUS_FAIL, connection);
			result(r, STATUS_FAIL, REASON_UNREACHABLE, null, 0L);
			return r;
		}
	}

	private static Map<String, Object> timeoutResult(String name, String depth, String status, boolean connection) {
		Map<String, Object> r = connection ? probeResult(depth) : newCheck(name, depth, SOURCE_LIVE);
		if(!connection && CHECK_EMBEDDING.equals(name) && DEPTH_DEEP.equals(depth)) {
			r.put("bounded", false);
		}
		result(r, status, REASON_TIMEOUT, null, 0L);
		return r;
	}

	private static Map<String, Object> await(Future<Map<String, Object>> f, int seconds, String timeoutStatus) {
		return awaitMillis(f, seconds * 1000L, timeoutStatus);
	}

	private static Map<String, Object> awaitMillis(Future<Map<String, Object>> f, long millis, String timeoutStatus) {
		try {
			return f.get(millis, TimeUnit.MILLISECONDS);
		}
		catch(TimeoutException e) {
			f.cancel(true);
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			f.cancel(true);
		}
		catch(Exception e) {
			logger.warn("System check: probe task failed (" + e.getClass().getSimpleName() + ")");
		}
		Map<String, Object> r = probeResult(DEPTH_PROBE);
		result(r, timeoutStatus, REASON_TIMEOUT, null, millis);
		return r;
	}

	// ───────────────────────────────── connections (§5) ─────────────────────────────────

	private static long idOf(BaseRecord r) {
		if(r == null || !r.hasField(FieldNames.FIELD_ID)) {
			return 0L;
		}
		Object o = r.get(FieldNames.FIELD_ID);
		return (o instanceof Number ? ((Number) o).longValue() : 0L);
	}

	private static long connIdOf(BaseRecord cfg) {
		if(cfg == null || !cfg.hasField("connection")) {
			return 0L;
		}
		BaseRecord c = cfg.get("connection");
		return idOf(c);
	}

	private static void setSort(Query q) {
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		}
		catch(Exception e) {
			logger.error(e);
		}
	}

	/// AccessPoint.list (authorizeQuery runs and is audited), then canRead per record AS THE CALLER.
	/// Paged, uncached, capped at LIST_MAX.
	/// Public so the two-step rule can be exercised directly (TestSystemCheckUtil) and reused; it returns
	/// records, so callers must not serialize them to a client without their own projection review.
	public static List<BaseRecord> listReadable(BaseRecord user, String model, String[] fields) {
		IOContext ctx = IOSystem.getActiveContext();
		List<BaseRecord> out = new ArrayList<>();
		Object org = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		for(long start = 0L; start < LIST_MAX; start += LIST_PAGE) {
			Query q = QueryUtil.createQuery(model);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, org);
			q.setRequest(fields);
			q.setCache(false);
			setSort(q);
			q.setRequestRange(start, LIST_PAGE);
			QueryResult qr = ctx.getAccessPoint().list(user, q);
			BaseRecord[] recs = (qr != null ? qr.getResults() : null);
			if(recs == null || recs.length == 0) {
				break;
			}
			for(BaseRecord r : recs) {
				if(r.getSchema() == null) {
					r.setSchema(model);
				}
				PolicyResponseType prr = ctx.getAuthorizationUtil().canRead(user, user, r);
				if(prr != null && prr.getType() == PolicyResponseEnumType.PERMIT) {
					out.add(r);
				}
			}
			if(recs.length < LIST_PAGE) {
				break;
			}
		}
		return out;
	}

	/// AccessPoint.find (query authorization + per-record canRead on the result) by one field.
	private static BaseRecord findReadable(BaseRecord user, String model, String field, Object value, String[] fields) {
		Query q = QueryUtil.createQuery(model, field, value);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(fields);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// The connection rows the caller can read, deduplicated against the deployment section for /System
	/// administrators, optionally probed (fast or deep). Deep requires the caller to hold DEEP_CHAT_BUSY,
	/// which is handed to the deep-chat worker.
	private static ConnectionSection computeConnections(BaseRecord user, boolean systemAdmin, boolean probe, boolean deep) {
		boolean[] handedOff = new boolean[] { false };
		try {
			return computeConnections(user, systemAdmin, probe, deep, handedOff);
		}
		finally {
			if(deep && !handedOff[0]) {
				DEEP_CHAT_BUSY.set(false);
			}
		}
	}

	private static ConnectionSection computeConnections(BaseRecord user, boolean systemAdmin, boolean probe, boolean deep, boolean[] handedOff) {
		long now = System.currentTimeMillis();
		List<BaseRecord> conns = listReadable(user, ModelNames.MODEL_CONNECTION, CONN_LIST_FIELDS);
		List<BaseRecord> cfgs = listReadable(user, OlioModelNames.MODEL_CHAT_CONFIG, CFG_LIST_FIELDS);

		Map<Long, BaseRecord> byId = new LinkedHashMap<>();
		for(BaseRecord c : conns) {
			if(systemAdmin && isDeploymentRecord(c)) {
				continue;
			}
			byId.put(idOf(c), c);
		}
		Map<Long, List<BaseRecord>> refs = new HashMap<>();
		for(BaseRecord cfg : cfgs) {
			long cid = connIdOf(cfg);
			if(cid > 0L) {
				refs.computeIfAbsent(cid, k -> new ArrayList<>()).add(cfg);
			}
		}
		List<Map<String, Object>> undetermined = new ArrayList<>();
		Set<Long> listed = new LinkedHashSet<>();
		for(BaseRecord c : conns) {
			listed.add(idOf(c));
		}
		for(Long cid : refs.keySet()) {
			if(listed.contains(cid)) {
				continue;
			}
			BaseRecord found = findReadable(user, ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, cid, CONN_LIST_FIELDS);
			if(found != null) {
				byId.put(cid, found);
			}
			else {
				Map<String, Object> u = new LinkedHashMap<>();
				u.put("id", cid);
				u.put("reason", REASON_UNREADABLE);
				List<String> names = new ArrayList<>();
				for(BaseRecord cfg : refs.get(cid)) {
					names.add(cfg.get(FieldNames.FIELD_NAME));
				}
				u.put("referencedBy", names);
				undetermined.add(u);
			}
		}

		List<Map<String, Object>> rows = new ArrayList<>();
		List<Future<Map<String, Object>>> futures = new ArrayList<>();
		long start = System.currentTimeMillis();
		for(Map.Entry<Long, BaseRecord> e : byId.entrySet()) {
			List<BaseRecord> r = refs.getOrDefault(e.getKey(), Collections.emptyList());
			rows.add(connectionRow(e.getValue(), r, systemAdmin));
			final long cid = e.getKey();
			futures.add(probePool().submit(() -> probe ? probeConnection(user, cid, r) : secretFacts(user, cid)));
		}
		long deadline = start + (ConnectionProbeUtil.TIMEOUT_SECONDS + 1) * 1000L;
		for(int i = 0; i < rows.size(); i++) {
			long remaining = Math.max(1L, deadline - System.currentTimeMillis());
			Map<String, Object> res = awaitMillis(futures.get(i), remaining, STATUS_FAIL);
			Map<String, Object> row = rows.get(i);
			applySecretFacts(row, res);
			if(probe) {
				row.putAll(res);
			}
		}

		if(deep) {
			/// One deep-chat worker task runs every connection whose fast probe came back ok/warn, one at a
			/// time (LiteLLM local parallelism is 1). A connection the fast probe already failed keeps its
			/// fast result: a deep request to an unreachable server proves nothing more.
			List<Long> ids = new ArrayList<>(byId.keySet());
			List<Integer> targets = new ArrayList<>();
			for(int i = 0; i < rows.size(); i++) {
				Object st = rows.get(i).get("status");
				if(STATUS_OK.equals(st) || STATUS_WARN.equals(st)) {
					targets.add(i);
				}
			}
			handedOff[0] = true;
			Future<List<Map<String, Object>>> f = submitAcquired(DEEP_CHAT_BUSY, deepChatExec(), () -> {
				List<Map<String, Object>> out = new ArrayList<>();
				for(Integer i : targets) {
					long cid = ids.get(i);
					out.add(runDeepChat(user, cid, refs.getOrDefault(cid, Collections.emptyList())));
				}
				return out;
			});
			try {
				List<Map<String, Object>> res = f.get((long) (DEEP_TIMEOUT_SECONDS + 1) * Math.max(1, targets.size()), TimeUnit.SECONDS);
				for(int k = 0; k < targets.size(); k++) {
					rows.get(targets.get(k)).putAll(res.get(k));
				}
			}
			catch(Exception e) {
				if(e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				for(Integer i : targets) {
					Map<String, Object> r = probeResult(DEPTH_DEEP);
					result(r, STATUS_FAIL, REASON_TIMEOUT, null, 0L);
					rows.get(i).putAll(r);
				}
			}
		}
		return new ConnectionSection(rows, undetermined.isEmpty(), undetermined, now);
	}

	/// A /System administrator's list also returns the six deployment records; they are shown in the
	/// deployment section instead.
	private static boolean isDeploymentRecord(BaseRecord c) {
		String name = c.get(FieldNames.FIELD_NAME);
		String path = c.hasField(FieldNames.FIELD_GROUP_PATH) ? c.get(FieldNames.FIELD_GROUP_PATH) : null;
		return ServerConfigUtil.isServerName(name) && ChatLibraryUtil.LIBRARY_PATH_CONNECTION.equals(path);
	}

	private static Map<String, Object> connectionRow(BaseRecord c, List<BaseRecord> refs, boolean systemAdmin) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("objectId", c.get(FieldNames.FIELD_OBJECT_ID));
		row.put("id", c.get(FieldNames.FIELD_ID));
		row.put("name", c.get(FieldNames.FIELD_NAME));
		row.put("groupPath", c.hasField(FieldNames.FIELD_GROUP_PATH) ? c.get(FieldNames.FIELD_GROUP_PATH) : null);
		row.put("dialect", lower(c.hasField(FieldNames.FIELD_DIALECT) ? c.getEnum(FieldNames.FIELD_DIALECT) : null));
		row.put("upstream", lower(c.hasField(FieldNames.FIELD_UPSTREAM) ? c.getEnum(FieldNames.FIELD_UPSTREAM) : null));
		row.put("hasApiKey", false);
		List<String> names = new ArrayList<>();
		for(BaseRecord cfg : refs) {
			String n = cfg.get(FieldNames.FIELD_NAME);
			if(n != null) {
				names.add(n);
			}
		}
		row.put("referencedBy", names);
		/// The URL is the caller's own readable data, but the full report served to a non-/System
		/// administrator carries no URL at all (§3.1); the Ux table does not render it.
		String url = c.hasField("serverUrl") ? c.get("serverUrl") : null;
		row.put("serverUrl", systemAdmin ? url : null);
		row.put("urlRedacted", !systemAdmin && url != null);
		return row;
	}

	/// The only fields a probe/secret result may put onto a row besides the probe outcome itself.
	private static void applySecretFacts(Map<String, Object> row, Map<String, Object> res) {
		Object k = res.remove("hasApiKey");
		if(k instanceof Boolean) {
			row.put("hasApiKey", k);
		}
	}

	private static Map<String, Object> probeResult(String depth) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("status", STATUS_SKIP);
		r.put("reason", REASON_NOT_CONFIGURED);
		r.put("detail", detailFor(REASON_NOT_CONFIGURED));
		r.put("latencyMs", 0L);
		r.put("checkedAt", System.currentTimeMillis());
		r.put("depth", depth);
		r.put("bounded", true);
		return r;
	}

	private static void result(Map<String, Object> r, String status, String reason, String detail, long latencyMs) {
		r.put("status", status);
		r.put("reason", reason);
		r.put("detail", detail != null ? detail : detailFor(reason));
		r.put("latencyMs", latencyMs);
		r.put("checkedAt", System.currentTimeMillis());
	}

	/// The outcome of the in-process secret read, never leaving this class except as a boolean.
	private static final class ConnSecretProbe {
		boolean hasApiKey = false;
		LLMServiceEnumType svc = null;
		List<String> models = null;
		Map<String, Object> result = null;
	}

	/// hasApiKey only (GET /connections): one projected find, reduced to a boolean at once.
	private static Map<String, Object> secretFacts(BaseRecord user, long connId) {
		Map<String, Object> r = new LinkedHashMap<>();
		BaseRecord full = findReadable(user, ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId, CONN_SECRET_FIELDS);
		r.put("hasApiKey", full != null && keyOf(full) != null);
		return r;
	}

	private static String keyOf(BaseRecord full) {
		try {
			String k = full.get("apiKey");
			return (k != null && !k.isBlank() ? k : null);
		}
		catch(Exception e) {
			logger.warn("System check: apiKey could not be resolved (" + e.getClass().getSimpleName() + ")");
			return null;
		}
	}

	/// Fast probe of one connection AS THE CALLER. Runs on the probe pool.
	private static Map<String, Object> probeConnection(BaseRecord user, long connId, List<BaseRecord> refs) {
		ConnSecretProbe sp = secretProbe(user, connId, refs);
		sp.result.put("hasApiKey", sp.hasApiKey);
		return sp.result;
	}

	private static ConnSecretProbe secretProbe(BaseRecord user, long connId, List<BaseRecord> refs) {
		ConnSecretProbe sp = new ConnSecretProbe();
		Map<String, Object> r = probeResult(DEPTH_PROBE);
		sp.result = r;
		BaseRecord full = findReadable(user, ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId, CONN_SECRET_FIELDS);
		if(full == null) {
			result(r, STATUS_SKIP, REASON_UNREADABLE, "The connection could not be read", 0L);
			return sp;
		}
		String key = keyOf(full);
		sp.hasApiKey = (key != null);
		String url = full.get("serverUrl");
		BaseRecord cfg0 = (refs.isEmpty() ? null : refs.get(0));
		sp.svc = ChatUtil.resolveServiceType(full, cfg0);
		if(sp.svc == LLMServiceEnumType.EMULATOR) {
			result(r, STATUS_SKIP, REASON_EMULATOR, null, 0L);
			return sp;
		}
		if(url == null || url.isBlank()) {
			result(r, STATUS_SKIP, REASON_NOT_CONFIGURED, null, 0L);
			return sp;
		}
		Map<String, String> h = new HashMap<>();
		ConnectionProbeUtil.Probe p;
		LLMServiceEnumType svc = (sp.svc != null ? sp.svc : LLMServiceEnumType.UNKNOWN);
		switch(svc) {
			case OLLAMA:
				p = ConnectionProbeUtil.get(ConnectionProbeUtil.join(url, "/api/tags"), null);
				break;
			case OPENAI_COMPAT:
				if(key != null) {
					h.put("Authorization", "Bearer " + key);
				}
				p = ConnectionProbeUtil.get(ConnectionProbeUtil.join(url, "/v1/models"), h);
				break;
			case OPENAI:
				if(key != null) {
					h.put("api-key", key);
				}
				String v = null;
				for(BaseRecord cfg : refs) {
					String av = cfg.get("apiVersion");
					if(av != null && !av.isBlank()) {
						v = av.trim();
						break;
					}
				}
				p = ConnectionProbeUtil.get(ConnectionProbeUtil.join(url, "/openai/models?api-version="
					+ java.net.URLEncoder.encode(v != null ? v : ConnectionProbeUtil.DEFAULT_AZURE_API_VERSION, java.nio.charset.StandardCharsets.UTF_8)), h);
				break;
			default:
				p = ConnectionProbeUtil.get(url, null);
				if(p.isAnswered() && p.getHttpStatus() < 500) {
					result(r, STATUS_OK, REASON_OK, "The server answered", p.getLatencyMs());
				}
				else {
					result(r, STATUS_FAIL, p.statusReason(), null, p.getLatencyMs());
				}
				return sp;
		}
		key = null;
		if(!p.isAnswered()) {
			result(r, STATUS_FAIL, p.getReason(), null, p.getLatencyMs());
			return sp;
		}
		if(p.getHttpStatus() == 401 || p.getHttpStatus() == 403) {
			result(r, STATUS_FAIL, REASON_AUTH, null, p.getLatencyMs());
			return sp;
		}
		if(p.getHttpStatus() != 200) {
			result(r, STATUS_FAIL, p.statusReason(), null, p.getLatencyMs());
			return sp;
		}
		if(svc == LLMServiceEnumType.OPENAI) {
			result(r, STATUS_OK, REASON_OK, "The Azure OpenAI endpoint accepted the key", p.getLatencyMs());
			return sp;
		}
		boolean ollama = (svc == LLMServiceEnumType.OLLAMA);
		sp.models = ConnectionProbeUtil.parseModelNames(p.getBody(), ollama);
		if(sp.models == null) {
			result(r, STATUS_FAIL, REASON_EMPTY_RESPONSE, null, p.getLatencyMs());
			return sp;
		}
		r.put("modelCount", sp.models.size());
		if(ollama) {
			for(BaseRecord cfg : refs) {
				String m = cfg.get("model");
				if(m != null && !m.isBlank() && !ConnectionProbeUtil.hasModel(sp.models, m)) {
					result(r, STATUS_WARN, REASON_MODEL_MISSING, null, p.getLatencyMs());
					r.put("modelCount", sp.models.size());
					return sp;
				}
			}
		}
		result(r, STATUS_OK, REASON_OK, "The server listed " + sp.models.size() + " model(s)", p.getLatencyMs());
		return sp;
	}

	// ───────────────────────────────── shaping ─────────────────────────────────

	/// Per-caller copy of a deployment check: a non-/System administrator never sees a URL (D5).
	@SuppressWarnings("unchecked")
	private static Map<String, Object> shape(Map<String, Object> check, boolean systemAdmin) {
		Map<String, Object> c = new LinkedHashMap<>(check);
		Object t = check.get("target");
		if(t instanceof Map) {
			Map<String, Object> tc = new LinkedHashMap<>((Map<String, Object>) t);
			Object url = tc.get("url");
			if(!systemAdmin) {
				tc.put("url", null);
				c.put("urlRedacted", url != null);
			}
			else {
				c.put("urlRedacted", false);
			}
			c.put("target", tc);
		}
		return c;
	}

	private static Map<String, Object> assemble(DeploymentReport dep, ConnectionSection cs, boolean systemAdmin) {
		List<Map<String, Object>> checks = new ArrayList<>();
		for(Map<String, Object> c : dep.checks) {
			if(!systemAdmin && CHECK_VECTORS.equals(c.get("name"))) {
				continue;
			}
			checks.add(shape(c, systemAdmin));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("status", overall(dep.checks, cs.rows));
		out.put("checkedAt", Math.max(dep.checkedAt, cs.checkedAt));
		out.put("complete", cs.complete);
		out.put("undetermined", cs.undetermined);
		out.put("propagation", PROPAGATION);
		out.put("checks", checks);
		out.put("connections", cs.rows);
		return out;
	}

	/// fail when the database fails; degraded when any other check (vectors excluded) or tested connection
	/// is warn/fail; ok otherwise.
	private static String overall(List<Map<String, Object>> checks, List<Map<String, Object>> conns) {
		boolean degraded = false;
		for(Map<String, Object> c : checks) {
			Object st = c.get("status");
			if(CHECK_DB.equals(c.get("name")) && STATUS_FAIL.equals(st)) {
				return OVERALL_FAIL;
			}
			if(!CHECK_VECTORS.equals(c.get("name")) && (STATUS_WARN.equals(st) || STATUS_FAIL.equals(st))) {
				degraded = true;
			}
		}
		for(Map<String, Object> c : conns) {
			Object st = c.get("status");
			if(STATUS_WARN.equals(st) || STATUS_FAIL.equals(st)) {
				degraded = true;
			}
		}
		return degraded ? OVERALL_DEGRADED : OVERALL_OK;
	}

	/// Fixed text per reason code. Never a URL, host, body or exception message.
	public static String detailFor(String reason) {
		if(reason == null) {
			return "";
		}
		if(reason.startsWith("http_")) {
			return "The server answered with HTTP " + reason.substring("http_".length());
		}
		switch(reason) {
			case REASON_OK: return "OK";
			case REASON_UNREACHABLE: return "The server could not be reached";
			case REASON_TIMEOUT: return "No answer within the timeout";
			case REASON_TLS: return "The TLS handshake or certificate validation failed";
			case REASON_AUTH: return "The server rejected the credentials";
			case REASON_MODEL_MISSING: return "A model referenced by a chat configuration is not installed on this server";
			case REASON_TYPE_URL_MISMATCH: return "The embedding type and URL disagree in shape";
			case REASON_EMULATOR: return "In-process emulator; no network call is made";
			case REASON_NOT_CONFIGURED: return "Not configured";
			case REASON_DEEP_REQUIRED: return "Reachable; a deep test is required to verify embeddings";
			case REASON_WIDTH_MISMATCH: return "The embedding width does not match the expected width";
			case REASON_EMPTY_RESPONSE: return "The server answered without the expected content";
			case REASON_IN_PROGRESS: return "A deep test is already running";
			case REASON_UNREADABLE: return "Not readable by you";
			default: return "";
		}
	}
}
