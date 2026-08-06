package org.cote.accountmanager.util;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.tools.VoiceUtil;

/// Deployment-global media/AI server configuration, backed by system.connection records.
///
/// SCOPE (Stephen's decision): all six URLs are /System-GLOBAL deployment configuration. There is
/// deliberately NO per-org override and therefore NO user parameter on the read path — a caller
/// asking "where is the SD server" gets the deployment's answer, not their organization's.
///
/// WHY DB-BACKED: docker/entrypoint.sh regenerates WEB-INF/web.xml from a template via envsubst on
/// EVERY container boot (entrypoint.sh:44-48), so nothing written into web.xml at runtime survives.
/// Runtime configuration must live in the database.
///
/// The web.xml init-param remains the FALLBACK: getServerUrl(name, defaultUrl) returns defaultUrl
/// when no record exists. That keeps every existing deployment working unchanged.
public class ServerConfigUtil {
	public static final Logger logger = LogManager.getLogger(ServerConfigUtil.class);

	/// The six deployment-config connection names. These are the record names inside
	/// /System's /Library/Connections group. They must not collide with
	/// ChatLibraryUtil.DEFAULT_CONNECTION_NAME ("Local Ollama"), which lives in the SAME group.
	public static final String SERVER_SD = "sd";
	public static final String SERVER_FACE = "face";
	public static final String SERVER_TAG = "tag";
	public static final String SERVER_VOICE_TTS = "voice.tts";
	public static final String SERVER_VOICE_STT = "voice.stt";
	public static final String SERVER_EMBEDDING = "embedding";

	public static final String[] SERVER_NAMES = new String[] {
		SERVER_SD, SERVER_FACE, SERVER_TAG, SERVER_VOICE_TTS, SERVER_VOICE_STT, SERVER_EMBEDDING
	};

	/// The matching web.xml init-param name for each connection name, in SERVER_NAMES order.
	/// Kept here so Service7/Console7 don't each re-derive the mapping.
	public static final String[] SERVER_INIT_PARAMS = new String[] {
		"sd.server", "face.server", "tag.server", "voice.tts.server", "voice.stt.server", "embedding.server"
	};

	public static final long CACHE_TTL_MS = 30000L;

	public static boolean isServerName(String name) {
		return name != null && Arrays.asList(SERVER_NAMES).contains(name);
	}

	public static String getInitParameterName(String name) {
		for(int i = 0; i < SERVER_NAMES.length; i++) {
			if(SERVER_NAMES[i].equals(name)) {
				return SERVER_INIT_PARAMS[i];
			}
		}
		return null;
	}

	/// Immutable resolved value. `present` distinguishes "no record" (leave the caller's default and
	/// the live singletons alone) from "record with an empty URL".
	private static final class Entry {
		private final boolean present;
		private final String serverUrl;
		private final String authorizationToken;
		private final long expires;
		Entry(boolean present, String serverUrl, String authorizationToken, long expires) {
			this.present = present;
			this.serverUrl = serverUrl;
			this.authorizationToken = authorizationToken;
			this.expires = expires;
		}
	}

	private static final Map<String, Entry> cache = new ConcurrentHashMap<>();

	/// Resolve the configured URL for one of SERVER_NAMES, falling back to defaultUrl (the web.xml
	/// init-param value) when there is no record or resolution fails.
	///
	/// NEVER throws. In the "orphan state" (the /data volume was lost, so OrganizationContext
	/// stores/vault fail to initialize against a live DB) this degrades to defaultUrl with a null
	/// token rather than failing the request. This is a hot path — SD/face/tag/voice/embedding calls
	/// go through it.
	public static String getServerUrl(String name, String defaultUrl) {
		Entry e = resolve(name);
		if(e != null && e.present && e.serverUrl != null && e.serverUrl.trim().length() > 0) {
			return e.serverUrl.trim();
		}
		return defaultUrl;
	}

	/// Resolve the configured API key/token for one of SERVER_NAMES, or defaultToken when absent.
	///
	/// The value is decrypted IN PROCESS by EncryptFieldProvider. It is for internal consumption
	/// only. The resolved system.connection record must NEVER be serialized back to a REST caller.
	public static String getAuthorizationToken(String name, String defaultToken) {
		Entry e = resolve(name);
		if(e != null && e.present && e.authorizationToken != null && e.authorizationToken.trim().length() > 0) {
			return e.authorizationToken.trim();
		}
		return defaultToken;
	}

	public static void invalidate(String name) {
		if(name == null) {
			cache.clear();
		}
		else {
			cache.remove(name);
		}
	}

	private static Entry resolve(String name) {
		if(!isServerName(name)) {
			logger.warn("Not a deployment server config name: " + name);
			return null;
		}
		long now = System.currentTimeMillis();
		Entry cached = cache.get(name);
		if(cached != null && cached.expires > now) {
			return cached;
		}
		Entry fresh = load(name, now + CACHE_TTL_MS);
		cache.put(name, fresh);
		/// Cross-JVM propagation: Console7 writes these records from a SEPARATE JVM, so in-process
		/// invalidation from the CLI can never reach the running WAR. Re-applying the resolved
		/// voice/embedding values onto the live process-wide singletons here — on every TTL refresh,
		/// not only at setup completion — is what lets a CLI edit take effect without a restart.
		///
		/// IMPORTANT — this only fires when something CALLS resolve(). That is the whole story for
		/// sd/face/tag, which are read per request. It is NOT the whole story for embedding/voice:
		/// those three are read exactly once, at boot, and every consumer thereafter uses the bound
		/// singleton (VoiceService, VectorService, ModelService, AccessPoint.createVectorStore,
		/// VectorListFactory). Nothing would ever re-resolve them. refreshBoundServers() below is the
		/// scheduled caller that closes that gap; Service7 drives it from its maintenance thread.
		applyToBoundUtils(name, fresh);
		return fresh;
	}

	/// The names whose resolved value is cached inside a long-lived singleton (EmbeddingUtil /
	/// VoiceUtil, one each per process, held by IOContext) instead of being read per call.
	/// sd/face/tag are deliberately absent: they are resolved on every request, so the TTL alone
	/// already bounds their propagation.
	public static final String[] BOUND_SERVER_NAMES = new String[] {
		SERVER_EMBEDDING, SERVER_VOICE_TTS, SERVER_VOICE_STT
	};

	/// Re-resolve the singleton-bound names so an out-of-process edit (Console7, or
	/// PATCH /rest/model against the connection record) reaches the live EmbeddingUtil/VoiceUtil.
	///
	/// Cheap by construction: inside the TTL every call is a ConcurrentHashMap hit that returns
	/// before touching the database or the singletons. Only the first call after a TTL expiry does
	/// real work — three indexed single-row reads.
	///
	/// MUST be invoked only after IOContext.setVectorUtil()/setVoiceUtil() have run, otherwise
	/// applyToBoundUtils has nothing to apply to.
	public static void refreshBoundServers() {
		for(String name : BOUND_SERVER_NAMES) {
			try {
				resolve(name);
			}
			catch(Exception e) {
				logger.warn("Failed to refresh the bound server config '" + name + "': " + e.getMessage());
			}
		}
	}

	/// Read the named system.connection out of /System's /Library/Connections group.
	///
	/// INTENTIONAL AUTHORIZATION BYPASS: this is a direct IOSystem search rather than an
	/// AccessPoint call, explicitly scoped to the /System organizationId + the Connections groupId.
	/// Sanctioned per objects7-reference.md ("Utility operations may bypass AccessClient for
	/// performance, going directly: Query -> Execution"). It is justified here because (a) the value
	/// is deployment configuration, not user data — there is no per-user or per-org answer to give;
	/// (b) it is on the hot path for every SD/face/tag/voice/embedding call; and (c) many callers
	/// (the boot listener, background threads) have no principal user at all. Nothing read here is
	/// ever returned to a REST caller.
	private static Entry load(String name, long expires) {
		Entry absent = new Entry(false, null, null, expires);
		try {
			IOContext ctx = IOSystem.getActiveContext();
			if(ctx == null) {
				return absent;
			}
			OrganizationContext octx = ctx.getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, OrganizationEnumType.SYSTEM);
			if(octx == null || octx.getOrganizationId() <= 0L || octx.getAdminUser() == null) {
				/// Not configured yet (or orphan state) — the caller keeps its web.xml default.
				return absent;
			}
			long orgId = octx.getOrganizationId();
			BaseRecord dir = ctx.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_GROUP,
				ChatLibraryUtil.LIBRARY_PATH_CONNECTION, GroupEnumType.DATA.toString(), orgId);
			if(dir == null) {
				return absent;
			}
			Query q = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_NAME, name);
			q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			/// Explicit projection, per the reference pattern at Chat.java:377-384. groupId is
			/// required for authZ, and apiKey MUST be requested or EncryptFieldProvider never runs
			/// and the token silently comes back null.
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID,
				FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_NAME, "serverUrl", "requestTimeout", "apiKey" });
			BaseRecord conn = ctx.getSearch().findRecord(q);
			if(conn == null) {
				return absent;
			}
			String url = conn.get("serverUrl");
			String key = null;
			try {
				key = conn.get("apiKey");
			}
			catch(Exception e) {
				/// Vault unavailable / decrypt failed (the orphan state). Degrade to a null token.
				logger.warn("Failed to resolve apiKey for server config '" + name + "': " + e.getMessage());
			}
			return new Entry(true, url, key, expires);
		}
		catch(Exception e) {
			/// NEVER throw on the hot path.
			logger.warn("Failed to resolve server config '" + name + "': " + e.getMessage());
			return absent;
		}
	}

	/// Re-apply resolved values onto the live process-wide singletons that cache a URL internally.
	///
	/// Only voice and embedding do; sd/face/tag are read per-call so they need nothing here.
	/// Deliberately does NOT rebuild VectorUtil: VectorUtil's constructor (VectorUtil.java:120-122)
	/// creates a fresh EmbeddingUtil, which would DISCARD the configured embeddingDimensions.
	private static void applyToBoundUtils(String name, Entry e) {
		/// No record, or an empty URL: leave the boot-configured value alone. Never clobber the
		/// web.xml value with null.
		if(e == null || !e.present || e.serverUrl == null || e.serverUrl.trim().length() == 0) {
			return;
		}
		String url = e.serverUrl.trim();
		String token = (e.authorizationToken != null && e.authorizationToken.trim().length() > 0 ? e.authorizationToken.trim() : null);
		try {
			IOContext ctx = IOSystem.getActiveContext();
			if(ctx == null) {
				return;
			}
			if(SERVER_EMBEDDING.equals(name)) {
				VectorUtil vu = ctx.getVectorUtil();
				EmbeddingUtil eu = (vu != null ? vu.getEmbedUtil() : null);
				if(eu != null) {
					/// Atomic pair swap so no reader can see new-URL-with-old-token. Keep the
					/// existing (boot/web.xml) token when the record carries no apiKey.
					eu.setEndpoint(url, token != null ? token : eu.getAuthorizationToken());
				}
			}
			else if(SERVER_VOICE_TTS.equals(name) || SERVER_VOICE_STT.equals(name)) {
				/// getVoiceUtil() is legitimately null in Console7 (ConsoleMain only sets vectorUtil).
				VoiceUtil vou = ctx.getVoiceUtil();
				if(vou != null) {
					VoiceUtil.Endpoint cur = vou.getEndpoint();
					String tts = (SERVER_VOICE_TTS.equals(name) ? url : cur.getServerTTSUrl());
					String stt = (SERVER_VOICE_STT.equals(name) ? url : cur.getServerSTTUrl());
					vou.setEndpoint(tts, stt, token != null ? token : cur.getAuthorizationToken());
				}
			}
		}
		catch(Exception ex) {
			logger.warn("Failed to apply server config '" + name + "' to live utilities: " + ex.getMessage());
		}
	}

	/// Create-or-update the named deployment connection record. Reuses
	/// ChatLibraryUtil.createLibraryConnection for the (idempotent) create so library creation and
	/// permission wiring are not reinvented here.
	///
	/// adminUser must be the /System organization admin user.
	///
	/// Returns a BOOLEAN, not the record. This class deliberately exposes NO method that hands a
	/// system.connection BaseRecord to a caller: `apiKey` has no field-level access block in
	/// connectionModel.json, EncryptFieldProvider decrypts it on READ, and neither
	/// RecordSerializerConfig.getFilteredModule() nor toFullString() filters `encrypt` fields — so
	/// any record that escaped this class could be serialized straight to a REST caller with the
	/// plaintext key in it. Only resolved primitives leave here (a URL string, a token consumed
	/// in-process, and hasApiKey()). Never log the record.
	public static boolean putConnection(BaseRecord adminUser, String name, String serverUrl, String apiKey) {
		if(!isServerName(name)) {
			logger.error("Not a deployment server config name: " + name);
			return false;
		}
		if(adminUser == null) {
			logger.error("Admin user is required to write server configuration");
			return false;
		}
		try {
			BaseRecord dir = ChatLibraryUtil.getCreateConnectionLibrary(adminUser);
			if(dir == null) {
				logger.error("Failed to resolve the connection library");
				return false;
			}
			BaseRecord conn = ChatLibraryUtil.createLibraryConnection(adminUser, dir, name, serverUrl, 120);
			if(conn == null) {
				return false;
			}
			/// createLibraryConnection is idempotent: an existing record comes back untouched, so
			/// apply the changed fields as a partial update (identity + changed) via AccessPoint.
			///
			/// `name` IS REQUIRED IN THE PATCH, even though it is not an identity field and is not
			/// changing. system.connection inherits common.nameId, whose `name` carries a non-empty
			/// (\S) validation rule, and the writer validates THE PATCH RECORD ITSELF rather than the
			/// merged result. A patch without `name` therefore fails validation outright:
			///     ValidationUtil - Validation of system.connection.name (null) failed pattern \S
			///     RecordUtil - WriterException: Record failed validation in IO DATABASE
			///     AuditUtil  - AUDIT INVALID system.user admin to MODIFY system.connection null
			/// and the update silently no-ops. That made every URL edit after the first a no-op and
			/// meant apiKey was NEVER stored at all (createLibraryConnection does not set apiKey —
			/// only this patch does), i.e. the whole apiKey feature was dead.
			///
			/// NOTE — DOC CONFLICT: .claude/rules/model-api.md states a PATCH is "schema + identity +
			/// changed fields", which is exactly what this used to send. For any model with a
			/// validated non-identity field that rule is insufficient. Flagged for a rules-file
			/// correction; do not "simplify" this back.
			boolean changed = false;
			BaseRecord patch = org.cote.accountmanager.record.RecordFactory.newInstance(ModelNames.MODEL_CONNECTION);
			patch.set(FieldNames.FIELD_ID, conn.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, conn.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_ORGANIZATION_ID, adminUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			/// Use the `name` argument, not conn.get(name): AccessPoint.create returns only identity
			/// fields, so a freshly created record's `name` is not populated.
			patch.set(FieldNames.FIELD_NAME, name);
			if(serverUrl != null && serverUrl.trim().length() > 0) {
				/// Always write it rather than diffing against conn.get("serverUrl"): an existing
				/// record comes back on the default query projection, which does not include
				/// serverUrl, so the diff compared against null and was never trustworthy.
				patch.set("serverUrl", serverUrl.trim());
				changed = true;
			}
			if(apiKey != null && apiKey.length() > 0) {
				patch.set("apiKey", apiKey);
				changed = true;
			}
			boolean ok = true;
			if(changed) {
				/// Propagate the real outcome. Discarding this return value is what let a failed
				/// write report success to the CLI and to setup.
				BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(adminUser, patch);
				if(updated == null) {
					logger.error("Failed to persist the server configuration for '" + name
						+ "': the update was rejected (check the audit log for a validation or"
						+ " authorization failure)");
					ok = false;
				}
			}
			invalidate(name);
			return ok;
		}
		catch(Exception e) {
			logger.error("Failed to write server configuration '" + name + "': " + e.getMessage());
			return false;
		}
	}

	/// True when the named connection carries an apiKey. This — never the record — is the only
	/// client-safe signal about a stored key.
	public static boolean hasApiKey(String name) {
		Entry e = resolve(name);
		return (e != null && e.present && e.authorizationToken != null && e.authorizationToken.trim().length() > 0);
	}

	/// List the six configured URLs (no tokens). Safe to render in a CLI or admin view.
	public static Map<String, String> listServerUrls() {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		for(String name : SERVER_NAMES) {
			Entry e = resolve(name);
			out.put(name, (e != null && e.present ? e.serverUrl : null));
		}
		return out;
	}

	public static List<String> serverNames() {
		return Arrays.asList(SERVER_NAMES);
	}
}
