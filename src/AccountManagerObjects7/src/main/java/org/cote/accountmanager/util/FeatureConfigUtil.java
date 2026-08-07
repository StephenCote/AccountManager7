package org.cote.accountmanager.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;

/// PER-ORGANIZATION Ux feature configuration.
///
/// SCOPE (Stephen's decision, 2026-08-07, recorded in aiDocs/UxFeatureFlagDesign.md
/// "Decision: scope is per-organization"): the enabled feature set is resolved per ORGANIZATION.
/// There is deliberately NO deployment-wide fallback and NO per-user setting: an organization with no
/// record resolves to the DEFAULT profile (every manifest id), never to some /System record. If a
/// deployment-wide setting is ever wanted it must be a second, explicitly-scoped resolver - not a
/// repurposing of this one. Collapsing the keyed cache below into a process-global field would be the
/// exact cross-tenant defect .claude/rules/architecture.md
/// section "Per-org config must never be written to process-global state" prohibits.
///
/// STORAGE: one data.data record named ".featureConfig", contentType application/json, in the
/// organization's /Library/Configuration group. The payload lives in `dataBytesStore` (UTF-8), NOT in
/// `description` (maxLength 512, models/common/descriptionModel.json:8). data.data inherits
/// crypto.cryptoByteStore (models/data/dataModel.json:4), which is the designed payload slot.
///
/// ORPHANED BY DESIGN: the previous implementation wrote ".featureConfig" into the CALLING USER's home
/// directory (FeatureConfigService.findConfigRecord, pre-2026-08-07). Those per-user records are
/// deliberately NOT migrated and are simply ignored - they were never readable by anyone else, which is
/// the bug this class replaces. Delete them by hand if they are in the way.
///
/// PROPAGATION BOUND (honest statement, same standard ServerConfigUtil holds itself to): the cache is
/// PER-JVM. A write from Console7, or from a second Tomcat, CANNOT invalidate this JVM's cache. Inside
/// this process a write invalidates the org's entry immediately; across processes the TTL
/// (CACHE_TTL_MS) is the only bound. Nothing here propagates live across processes.
///
/// LAYERING: feature ids are OPAQUE STRINGS. No code in this class - or anywhere in Objects7 - may
/// branch on a specific id, and nothing here may reference an ISO42001 or Service7 type. The manifest
/// is data; adding or removing a feature is a resource edit, not a code change. The one exception is
/// the structural "core" constant, which is a manifest invariant (required:true, deps:[]) and not
/// feature behavior.
public class FeatureConfigUtil {
	public static final Logger logger = LogManager.getLogger(FeatureConfigUtil.class);

	/// Manifest resource: resources/features/uxFeatureManifest.json
	public static final String MANIFEST_NAME = "uxFeature";

	/// The record name inside /Library/Configuration.
	public static final String CONFIG_NAME = ".featureConfig";

	/// Library group name + path. /Library is already per-org (LibraryUtil.basePath resolved against
	/// the organization context), so this reuses an existing convention rather than inventing one.
	public static final String LIBRARY_CONFIGURATION = "Configuration";
	public static final String LIBRARY_PATH_CONFIGURATION = LibraryUtil.basePath + "/" + LIBRARY_CONFIGURATION;

	/// Structural invariant of the manifest, not a feature branch: "core" is required:true with no
	/// deps, and is force-included on both read and write so a hand-edited record cannot produce a
	/// routeless application.
	public static final String FEATURE_CORE = "core";

	public static final String CONTENT_TYPE = "application/json";
	public static final String FEATURES_KEY = "features";

	public static final long CACHE_TTL_MS = 30000L;

	/// Explicit read projection. REQUIRED, not optional: AccessPoint.find() fills an empty request with
	/// RecordUtil.getCommonFields(type) (client/AccessPoint.java:476-478, util/RecordUtil.java:289-305),
	/// and for data.data that union does NOT include dataBytesStore - so the payload would read back
	/// null. findByNameInGroup routes to the same unprojected find (AccessPoint.java:526-544 -> :558-568)
	/// and has the same problem, which is why this class builds its own Query. Same pattern and same
	/// reason as ServerConfigUtil.load() at :213-217, which had to request `apiKey` explicitly.
	///
	/// compressionType is included on purpose: a payload over ByteModelUtil.MINIMUM_COMPRESSION_SIZE
	/// (512) is gzipped on write for contentType application/json (ByteModelUtil.tryCompress), and
	/// ByteModelUtil.getValue only gunzips when it can see compressionType - unprojected it defaults to
	/// NONE and the raw gzip bytes would come back and silently fail to parse.
	private static final String[] READ_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
		FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_BYTE_STORE
	};

	private FeatureConfigUtil() {
		/// static utility
	}

	/// ---------------------------------------------------------------------------------------------
	/// Manifest (S1)
	/// ---------------------------------------------------------------------------------------------

	/// The manifest as the raw cached resource STRING, for serving verbatim on the wire.
	///
	/// Deliberately NOT parse-and-reserialize: round-tripping through List<Map> churns key order and
	/// hand-rolls a shape the resource already states. The parse below is separate and only feeds the
	/// internal id/deps lookups.
	public static String getManifestJson() {
		return ResourceUtil.getInstance().getFeatureManifestResource(MANIFEST_NAME);
	}

	/// Parsed view of the manifest: ids in FILE ORDER (which is the admin-card render order), and the
	/// declared deps per id. Parsed once, lazily; the underlying resource read is itself cached by
	/// ResourceUtil.
	private static volatile List<String> manifestIds = null;
	private static volatile Map<String, List<String>> manifestDeps = null;

	private static synchronized void parseManifest() {
		if(manifestIds != null && manifestDeps != null) {
			return;
		}
		List<String> ids = new ArrayList<>();
		Map<String, List<String>> deps = new LinkedHashMap<>();
		try {
			String json = getManifestJson();
			if(json == null || json.trim().length() == 0) {
				logger.error("The Ux feature manifest resource is missing or empty: features/" + MANIFEST_NAME + "Manifest.json");
			}
			else {
				List<Map<String, Object>> entries = JSONUtil.getList(json, LinkedHashMap.class, null);
				if(entries == null) {
					logger.error("Failed to parse the Ux feature manifest resource");
				}
				else {
					for(Map<String, Object> entry : entries) {
						Object oid = entry.get("id");
						if(oid == null || oid.toString().trim().length() == 0) {
							logger.warn("Skipping a Ux feature manifest entry with no id");
							continue;
						}
						String id = oid.toString().trim();
						List<String> edeps = new ArrayList<>();
						Object odeps = entry.get("deps");
						if(odeps instanceof List) {
							for(Object od : (List<?>)odeps) {
								if(od != null && od.toString().trim().length() > 0) {
									edeps.add(od.toString().trim());
								}
							}
						}
						if(!ids.contains(id)) {
							ids.add(id);
						}
						deps.put(id, Collections.unmodifiableList(edeps));
					}
				}
			}
		}
		catch(Exception e) {
			logger.error("Failed to read the Ux feature manifest: " + e.getMessage());
		}
		manifestDeps = Collections.unmodifiableMap(deps);
		manifestIds = Collections.unmodifiableList(ids);
	}

	/// All manifest ids, in file order.
	public static List<String> getManifestIds() {
		parseManifest();
		return manifestIds;
	}

	public static boolean isKnownFeature(String id) {
		if(id == null) {
			return false;
		}
		return getManifestIds().contains(id);
	}

	/// The DECLARED (non-transitive) deps of one id. Empty for an unknown id.
	public static List<String> getDeps(String id) {
		parseManifest();
		List<String> d = (id != null ? manifestDeps.get(id) : null);
		return (d != null ? d : Collections.<String>emptyList());
	}

	/// The default profile used when an organization has no record: every manifest id, in file order.
	/// Derived, never a second hand-maintained list.
	public static List<String> getDefaultFeatures() {
		return getManifestIds();
	}

	/// Normalize an incoming set: drop unknown ids, force-include `core`, close the `deps` transitive
	/// closure, and emit in manifest (file) order.
	///
	/// The dep closure matters on BOTH sides of the wire: the client applies a recursive closure of its
	/// own (AccountManagerUx752/src/features.js:150-158,183-185), so a stored set containing `cardGame`
	/// without `chat` would read back as one thing and render as another. Closing it before storage
	/// removes that divergence.
	public static List<String> resolveFeatures(List<String> ids) {
		List<String> order = getManifestIds();
		Set<String> resolved = new LinkedHashSet<>();
		addWithDeps(FEATURE_CORE, resolved, 0);
		if(ids != null) {
			for(String id : ids) {
				if(id == null) {
					continue;
				}
				String tid = id.trim();
				if(!isKnownFeature(tid)) {
					logger.warn("Ignoring an unknown feature id: " + tid);
					continue;
				}
				addWithDeps(tid, resolved, 0);
			}
		}
		List<String> out = new ArrayList<>();
		for(String id : order) {
			if(resolved.contains(id)) {
				out.add(id);
			}
		}
		/// Anything resolved but absent from `order` cannot exist (resolved ids are manifest ids), so
		/// `out` is complete.
		return Collections.unmodifiableList(out);
	}

	/// Depth guard: a malformed manifest with a dep cycle must not recurse forever.
	private static void addWithDeps(String id, Set<String> into, int depth) {
		if(id == null || into.contains(id) || !isKnownFeature(id)) {
			return;
		}
		if(depth > 32) {
			logger.error("Refusing to follow a Ux feature dependency chain deeper than 32 - check the manifest for a cycle at: " + id);
			return;
		}
		for(String dep : getDeps(id)) {
			addWithDeps(dep, into, depth + 1);
		}
		into.add(id);
	}

	/// ---------------------------------------------------------------------------------------------
	/// Org-scoped enabled set (S2)
	/// ---------------------------------------------------------------------------------------------

	/// Immutable resolved value. `present` distinguishes "no record" (=> the default profile) from
	/// "record with an empty list" (=> `core` only), exactly as ServerConfigUtil.Entry does (:76-89).
	private static final class Entry {
		private final boolean present;
		private final List<String> features;
		private final long expires;
		Entry(boolean present, List<String> features, long expires) {
			this.present = present;
			this.features = (features != null ? Collections.unmodifiableList(new ArrayList<>(features)) : Collections.<String>emptyList());
			this.expires = expires;
		}
	}

	/// Keyed cache, NOT shared mutable state: one entry per organizationId. There is deliberately no
	/// applyToBoundUtils analogue here and nothing is written to any process-global field.
	private static final Map<Long, Entry> cache = new ConcurrentHashMap<>();

	/// Drop the cached entry for one organization, or (organizationId <= 0) the whole cache.
	/// In-process only - see the PROPAGATION BOUND note on the class.
	public static void invalidate(long organizationId) {
		if(organizationId <= 0L) {
			cache.clear();
		}
		else {
			cache.remove(organizationId);
		}
	}

	/// Resolve the enabled feature ids for the CALLER'S organization. Readable by any authenticated
	/// user in that organization.
	///
	/// NEVER THROWS. Every failure - no IO context, no /Library, no record, an unreadable or unparsable
	/// payload, a PBAC denial - degrades to the default profile. This runs once per login and must not
	/// be able to fail a login.
	///
	/// THE READ PATH CREATES NOTHING. The group is resolved with findPath only. LibraryUtil
	/// .getCreateSharedLibrary is deliberately NOT called here: its create branch runs as the ORG ADMIN
	/// (LibraryUtil.java:43), does a raw createRecord that bypasses PBAC (:45), and grants role
	/// permissions (:49 -> :100-108). Calling it on a read would make a non-admin login trigger
	/// admin-privileged group creation as a side effect of reading.
	public static List<String> getEnabledFeatures(BaseRecord user) {
		if(user == null) {
			return getDefaultFeatures();
		}
		long orgId = 0L;
		try {
			Long lid = user.get(FieldNames.FIELD_ORGANIZATION_ID);
			orgId = (lid != null ? lid.longValue() : 0L);
		}
		catch(Exception e) {
			logger.warn("Failed to read the organization id from the context user: " + e.getMessage());
		}
		if(orgId <= 0L) {
			return getDefaultFeatures();
		}
		long now = System.currentTimeMillis();
		Entry cached = cache.get(orgId);
		if(cached == null || cached.expires <= now) {
			cached = load(user, orgId, now + CACHE_TTL_MS);
			cache.put(orgId, cached);
		}
		if(!cached.present) {
			return getDefaultFeatures();
		}
		return cached.features;
	}

	/// Read /Library/Configuration/.featureConfig for one organization.
	///
	/// ALL record access goes through AccessPoint - never a raw IOSystem search.
	/// ServerConfigUtil.load()'s documented authorization bypass does NOT apply here: that value is
	/// deployment config with no per-org answer to give, on a hot path, often with no principal user.
	/// This value IS per-org, the read happens once per login, and the caller always has a principal
	/// user - so architecture.md's "never bypass PBAC" applies with no exemption.
	///
	/// (findPath itself is the standard group-resolution utility and is used by every /Library caller;
	/// the record read - the thing that carries the data - is the AccessPoint call.)
	private static Entry load(BaseRecord user, long orgId, long expires) {
		Entry absent = new Entry(false, null, expires);
		try {
			IOContext ctx = IOSystem.getActiveContext();
			if(ctx == null) {
				return absent;
			}
			/// findPath ONLY. A miss (first-run, /Library not created yet, org never configured) is
			/// present=false => the default profile, with no error.
			BaseRecord dir = ctx.getPathUtil().findPath(user, ModelNames.MODEL_GROUP, LIBRARY_PATH_CONFIGURATION,
				GroupEnumType.DATA.toString(), orgId);
			if(dir == null) {
				return absent;
			}
			BaseRecord rec = findConfigRecord(user, dir, orgId);
			if(rec == null) {
				return absent;
			}
			String json = null;
			byte[] payload = rec.get(FieldNames.FIELD_BYTE_STORE);
			if(payload != null && payload.length > 0) {
				json = new String(payload, StandardCharsets.UTF_8);
			}
			if(json == null || json.trim().length() == 0) {
				/// A record with no payload is not an intentional empty selection (an intentional empty
				/// selection stores ["core"], because core is force-included). Treat it as absent.
				logger.warn("The feature configuration record for organization " + orgId + " carries no payload - using the default profile");
				return absent;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> cfg = JSONUtil.importObject(json, LinkedHashMap.class);
			if(cfg == null) {
				logger.warn("Failed to parse the feature configuration for organization " + orgId + " - using the default profile");
				return absent;
			}
			Object ofeatures = cfg.get(FEATURES_KEY);
			if(!(ofeatures instanceof List)) {
				logger.warn("The feature configuration for organization " + orgId + " has no '" + FEATURES_KEY + "' array - using the default profile");
				return absent;
			}
			List<String> ids = new ArrayList<>();
			for(Object o : (List<?>)ofeatures) {
				if(o != null) {
					ids.add(o.toString());
				}
			}
			/// Force-include core and close deps on READ as well as write, so a hand-edited or
			/// stale record cannot produce a routeless or internally inconsistent application.
			return new Entry(true, resolveFeatures(ids), expires);
		}
		catch(Exception e) {
			logger.warn("Failed to resolve the feature configuration for organization " + orgId + ": " + e.getMessage());
			return absent;
		}
	}

	private static BaseRecord findConfigRecord(BaseRecord user, BaseRecord dir, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, CONFIG_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(READ_REQUEST);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Store the enabled feature set for the CALLER'S organization. Returns false on any failure.
	///
	/// THE REAL WRITE GATE IS THE TRANSPORT: FeatureConfigService's PUT is @RolesAllowed({"admin"}).
	/// This method does not re-derive "is an admin" - it relies on AccessPoint to reject a write the
	/// caller is not entitled to, and on that role annotation.
	///
	/// GROUP CREATION ORDER MATTERS - DO NOT REORDER. getCreateSharedLibrary must run FIRST, because
	/// configureLibraryRootPermissions does a findPath on /Library and bails out at
	/// LibraryUtil.java:90-94 ("Failed to find /Library") when /Library does not exist yet. On the very
	/// first write in a fresh organization that is exactly the state: /Library is created moments later
	/// by makePath inside getCreateSharedLibrary (LibraryUtil.java:43). Calling the root reader first
	/// therefore grants nothing, and the non-admin read this whole class exists to enable would keep
	/// failing until some later write happened to re-run it. Both grant calls are idempotent
	/// (MemberUtil.member(..., true)), so running them after creation is safe.
	/// NOTE: ChatLibraryUtil.java:47-48 has this same latent ordering bug (root reader before create).
	/// It is not copied here and is not fixed here.
	///
	/// WHAT THE GRANTS ACTUALLY DO: getCreateSharedGroup returns EARLY when the group already exists
	/// (LibraryUtil.java:40-42), BEFORE configureLibraryPermissions (:49) - so that call can only
	/// configure an ACL for a group it just created. It cannot repair a pre-existing over-granted
	/// group, and nothing here removes CREATE/UPDATE from one. The explicit configureLibraryReader call
	/// below is unconditional (LibraryUtil.java:53-55), which is why it is made separately. Also note
	/// enableCRU=false through getCreateSharedLibrary is untrodden ground: every existing caller passes
	/// true.
	public static boolean setEnabledFeatures(BaseRecord user, List<String> ids) {
		if(user == null) {
			logger.error("A context user is required to write the feature configuration");
			return false;
		}
		if(ids == null) {
			logger.error("A feature id list is required");
			return false;
		}
		long orgId = 0L;
		try {
			Long lid = user.get(FieldNames.FIELD_ORGANIZATION_ID);
			orgId = (lid != null ? lid.longValue() : 0L);
		}
		catch(Exception e) {
			logger.error("Failed to read the organization id from the context user: " + e.getMessage());
			return false;
		}
		if(orgId <= 0L) {
			logger.error("The context user has no organization id");
			return false;
		}
		List<String> resolved = resolveFeatures(ids);
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put(FEATURES_KEY, resolved);
		String json = JSONUtil.exportObject(cfg);
		if(json == null) {
			logger.error("Failed to serialize the feature configuration");
			return false;
		}
		boolean ok = false;
		try {
			IOContext ctx = IOSystem.getActiveContext();
			if(ctx == null) {
				logger.error("No active IO context");
				return false;
			}
			BaseRecord dir = LibraryUtil.getCreateSharedLibrary(user, LIBRARY_CONFIGURATION, false);
			if(dir == null) {
				logger.error("Failed to resolve " + LIBRARY_PATH_CONFIGURATION);
				return false;
			}
			/// Both AFTER creation - see the ordering note above.
			LibraryUtil.configureLibraryRootReader(user);
			LibraryUtil.configureLibraryReader(user, LIBRARY_CONFIGURATION);

			BaseRecord existing = findConfigRecord(user, dir, orgId);
			if(existing == null) {
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, LIBRARY_PATH_CONFIGURATION);
				plist.parameter(FieldNames.FIELD_NAME, CONFIG_NAME);
				BaseRecord rec = ctx.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
				rec.set(FieldNames.FIELD_CONTENT_TYPE, CONTENT_TYPE);
				/// Routed through ByteArrayValueType -> ByteModelUtil.setValue, which owns
				/// compression/cipher handling for cryptoByteStore models.
				rec.set(FieldNames.FIELD_BYTE_STORE, json.getBytes(StandardCharsets.UTF_8));
				BaseRecord created = ctx.getAccessPoint().create(user, rec);
				if(created == null) {
					logger.error("Failed to create the feature configuration record for organization " + orgId);
					return false;
				}
				ok = true;
			}
			else {
				/// Partial update: identity + changed fields, PLUS every field the model's validation
				/// requires. `name` IS REQUIRED even though it is neither identity nor changing:
				/// data.data inherits common.nameId, whose `name` carries a \S rule, and the writer
				/// validates THE PATCH RECORD ITSELF rather than the merged result. The exact failure
				/// signature is documented in-tree at ServerConfigUtil.java:318-332 (a silent no-op with
				/// only an AUDIT INVALID line in the log). Use the CONFIG_NAME constant, not a value read
				/// off a created record - AccessPoint.create returns identity fields only.
				BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_DATA);
				patch.set(FieldNames.FIELD_ID, existing.get(FieldNames.FIELD_ID));
				patch.set(FieldNames.FIELD_OBJECT_ID, existing.get(FieldNames.FIELD_OBJECT_ID));
				patch.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				patch.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				patch.set(FieldNames.FIELD_NAME, CONFIG_NAME);
				patch.set(FieldNames.FIELD_CONTENT_TYPE, CONTENT_TYPE);
				patch.set(FieldNames.FIELD_BYTE_STORE, json.getBytes(StandardCharsets.UTF_8));
				/// NEVER discard the update result: it is the only signal that the write happened.
				BaseRecord updated = ctx.getAccessPoint().update(user, patch);
				if(updated == null) {
					logger.error("Failed to persist the feature configuration for organization " + orgId
						+ ": the update was rejected (check the audit log for a validation or authorization failure)");
					return false;
				}
				ok = true;
			}
		}
		catch(Exception e) {
			logger.error("Failed to write the feature configuration for organization " + orgId + ": " + e.getMessage());
			return false;
		}
		finally {
			/// Invalidate regardless of outcome: a partially-applied write must not leave a stale entry.
			invalidate(orgId);
		}
		return ok;
	}
}
