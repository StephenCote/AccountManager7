package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.FeatureConfigUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Integration tests for the PER-ORGANIZATION Ux feature configuration (aiDocs/UxFeatureFlagDesign.md
/// D1). These run against the live database through IOSystem - no Tomcat/Service7 is involved.
///
/// TWO NON-ADMIN USERS, ONE ORGANIZATION. The whole point of D1 is that one user's save is visible to
/// a DIFFERENT user in the same organization, which no single-user test can express. The organization
/// admin user appears here ONLY as a setup actor (granting a role, which is inherently an admin act);
/// it is never an assertion subject, per .claude/rules/llm-conduct.md rule 3.
///
/// USER NAMES: system.user carries the $minLen5 validation rule, which is NOT a length check - it is
/// the expression [A-Za-z0-9]{5} evaluated with Matcher.find(), i.e. "contains five CONSECUTIVE
/// alphanumerics". Factory.getCreateUser enforces it independently and returns null for a failing
/// name, so both names below are chosen to contain a long alphanumeric run and are asserted non-null.
public class TestFeatureConfigUtil extends BaseTest {

	/// "featurecfgadmin" / "featurecfgreader" - long alphanumeric runs, so $minLen5 passes.
	private static final String WRITER_NAME = "featurecfgadmin";
	private static final String READER_NAME = "featurecfgreader";

	/// Mirrors FeatureConfigUtil.READ_REQUEST (private there). compressionType is the load-bearing
	/// entry: without it ByteModelUtil.getValue cannot know the payload was gzipped.
	private static final String[] READ_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
		FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_BYTE_STORE
	};

	/// ------------------------------------------------------------------------------------------
	/// Users
	/// ------------------------------------------------------------------------------------------

	/// A non-admin user that is a member of AccountAdministrators - i.e. exactly what the transport
	/// layer's @RolesAllowed({"admin"}) resolves to (Service7 WEB-INF/resource/roleMap.json maps the
	/// JAAS role "admin" onto the AccountAdministrators role). NOT the built-in admin user.
	private BaseRecord getWriter() {
		BaseRecord user = getCreateUser(WRITER_NAME);
		assertNotNull("Writer user is null - check the $minLen5 rule against '" + WRITER_NAME + "'", user);
		BaseRecord adminRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR,
			RoleEnumType.USER.toString(), orgContext.getOrganizationId());
		assertNotNull("AccountAdministrators role is null", adminRole);
		/// Role grant is an admin act, so the org admin is the setup actor here. It is idempotent.
		ioContext.getMemberUtil().member(orgContext.getAdminUser(), adminRole, user, null, true);
		assertNotNull("Writer has no organizationPath - LibraryUtil resolves the org context from it",
			user.get(FieldNames.FIELD_ORGANIZATION_PATH));
		return user;
	}

	/// A plain non-admin user. Every new user is placed in AccountUsers, which is the role the
	/// /Library read grant keys on.
	private BaseRecord getReader() {
		BaseRecord user = getCreateUser(READER_NAME);
		assertNotNull("Reader user is null - check the $minLen5 rule against '" + READER_NAME + "'", user);
		assertNotNull("Reader has no organizationPath", user.get(FieldNames.FIELD_ORGANIZATION_PATH));
		return user;
	}

	/// ------------------------------------------------------------------------------------------
	/// Raw record access - deliberately bypasses FeatureConfigUtil so a payload the resolver would
	/// never produce (no core, non-UTF8-safe text, > 512 bytes) can be stored and read back.
	/// ------------------------------------------------------------------------------------------

	private BaseRecord findConfigDir(BaseRecord user) {
		return IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP,
			FeatureConfigUtil.LIBRARY_PATH_CONFIGURATION, GroupEnumType.DATA.toString(),
			user.get(FieldNames.FIELD_ORGANIZATION_ID));
	}

	private BaseRecord findConfigRecord(BaseRecord user) {
		BaseRecord dir = findConfigDir(user);
		if(dir == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(READ_REQUEST);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Overwrite the stored payload verbatim. contentType is set BEFORE the byte store because
	/// ByteModelUtil.tryCompress reads contentType at set() time to decide whether to gzip.
	private boolean writeRawPayload(BaseRecord user, String json) throws Exception {
		return writeRawPayload(user, json, true);
	}

	/// invalidate=false writes the record WITHOUT touching FeatureConfigUtil's cache - i.e. exactly what a
	/// write from another process (Console7, a second Tomcat) looks like to this JVM. Required by
	/// TestAbsentResolutionIsNotCachedForTheOrg, which cannot use setEnabledFeatures because that
	/// invalidates in its own finally block and would hide the defect under test.
	private boolean writeRawPayload(BaseRecord user, String json, boolean invalidate) throws Exception {
		BaseRecord dir = findConfigDir(user);
		assertNotNull("/Library/Configuration does not exist - call setEnabledFeatures first", dir);
		BaseRecord existing = findConfigRecord(user);
		if(existing == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, FeatureConfigUtil.LIBRARY_PATH_CONFIGURATION);
			plist.parameter(FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
			BaseRecord rec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
			rec.set(FieldNames.FIELD_CONTENT_TYPE, FeatureConfigUtil.CONTENT_TYPE);
			rec.set(FieldNames.FIELD_BYTE_STORE, json.getBytes(StandardCharsets.UTF_8));
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, rec);
			if(invalidate) {
				FeatureConfigUtil.invalidate((long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
			}
			return created != null;
		}
		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		patch.set(FieldNames.FIELD_ID, existing.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, existing.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_ORGANIZATION_ID, (long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		patch.set(FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
		patch.set(FieldNames.FIELD_CONTENT_TYPE, FeatureConfigUtil.CONTENT_TYPE);
		patch.set(FieldNames.FIELD_BYTE_STORE, json.getBytes(StandardCharsets.UTF_8));
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, patch);
		if(invalidate) {
			FeatureConfigUtil.invalidate((long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		return updated != null;
	}

	private void deleteConfigRecord(BaseRecord user) {
		BaseRecord existing = findConfigRecord(user);
		if(existing != null) {
			assertTrue("Failed to delete the existing feature configuration record",
				IOSystem.getActiveContext().getAccessPoint().delete(user, existing));
		}
		FeatureConfigUtil.invalidate((long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNull("The feature configuration record still exists after delete", findConfigRecord(user));
	}

	/// compressionType is an enum field. Read it into an Object first: BaseRecord.get is generic, so
	/// String.valueOf(rec.get(...)) binds to String.valueOf(char[]) and throws ClassCastException.
	private String compressionOf(BaseRecord rec) {
		Object ct = rec.get(FieldNames.FIELD_COMPRESSION_TYPE);
		return String.valueOf(ct).toUpperCase();
	}

	private String payloadOf(BaseRecord rec) throws Exception {
		byte[] b = rec.get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("The stored payload is null", b);
		return new String(b, StandardCharsets.UTF_8);
	}

	/// ------------------------------------------------------------------------------------------
	/// (g) Manifest
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestManifest() {
		String json = FeatureConfigUtil.getManifestJson();
		assertNotNull("The Ux feature manifest resource could not be read", json);
		assertTrue("The Ux feature manifest resource is empty", json.trim().length() > 0);

		List<String> ids = FeatureConfigUtil.getManifestIds();
		assertNotNull("Manifest ids are null", ids);

		/// Deliberate drift guard on the count stated in aiDocs/UxFeatureFlagDesign.md (13 features).
		/// The old TestFeatureConfigService asserted 11 against a list literal in its own body, so it
		/// could not notice that either the service list (12) or the manifest (13) had moved. This
		/// asserts against the production accessor.
		assertEquals("Manifest feature count drifted from the 13 documented in UxFeatureFlagDesign.md",
			13, ids.size());

		/// 'media' is the id that rotted out of the server-side list unnoticed (design doc §3.2).
		assertTrue("'media' is missing from the manifest", ids.contains("media"));
		assertTrue("isKnownFeature('media') is false", FeatureConfigUtil.isKnownFeature("media"));
		assertTrue("isKnownFeature('core') is false", FeatureConfigUtil.isKnownFeature(FeatureConfigUtil.FEATURE_CORE));
		assertFalse("isKnownFeature accepted a bogus id", FeatureConfigUtil.isKnownFeature("bogusFeature"));
		assertFalse("isKnownFeature accepted an empty id", FeatureConfigUtil.isKnownFeature(""));
		assertFalse("isKnownFeature accepted null", FeatureConfigUtil.isKnownFeature(null));

		/// DEFAULT_FEATURES must be DERIVED from the manifest, not a second hand-maintained list.
		assertEquals("getDefaultFeatures() is not derived from getManifestIds()",
			FeatureConfigUtil.getManifestIds(), FeatureConfigUtil.getDefaultFeatures());

		/// Declared deps, read off the production accessor.
		assertTrue("core should declare no deps", FeatureConfigUtil.getDeps(FeatureConfigUtil.FEATURE_CORE).isEmpty());
		assertTrue("cardGame should declare a chat dep", FeatureConfigUtil.getDeps("cardGame").contains("chat"));
		assertTrue("getDeps of an unknown id should be empty", FeatureConfigUtil.getDeps("bogusFeature").isEmpty());

		/// resolveFeatures: core forced, unknown dropped, deps closed, manifest order preserved.
		assertEquals("resolveFeatures(null) should be core only",
			Arrays.asList("core"), FeatureConfigUtil.resolveFeatures(null));
		assertEquals("resolveFeatures(empty) should be core only",
			Arrays.asList("core"), FeatureConfigUtil.resolveFeatures(new ArrayList<String>()));
		assertEquals("resolveFeatures should drop unknown ids and force core",
			Arrays.asList("core"), FeatureConfigUtil.resolveFeatures(Arrays.asList("bogusFeature")));
		assertEquals("resolveFeatures(cardGame) should close the chat+core deps in manifest order",
			Arrays.asList("core", "chat", "cardGame"), FeatureConfigUtil.resolveFeatures(Arrays.asList("cardGame")));
		assertEquals("resolveFeatures should emit in manifest order regardless of input order",
			FeatureConfigUtil.resolveFeatures(Arrays.asList("cardGame", "chat")),
			FeatureConfigUtil.resolveFeatures(Arrays.asList("chat", "cardGame")));
		assertEquals("resolveFeatures of every id should be the full manifest",
			FeatureConfigUtil.getManifestIds(), FeatureConfigUtil.resolveFeatures(FeatureConfigUtil.getManifestIds()));
	}

	/// ------------------------------------------------------------------------------------------
	/// (a) D1: an admin-role user's save is visible to a DIFFERENT user in the same organization
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestOrgPropagationToSecondUser() {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();
		assertEquals("Both users must be in the same organization",
			(long)writer.get(FieldNames.FIELD_ORGANIZATION_ID), (long)reader.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertFalse("The two users must be distinct",
			((Long)writer.get(FieldNames.FIELD_ID)).equals(reader.get(FieldNames.FIELD_ID)));

		/// A REDUCED set: it must differ from the default profile, otherwise a fallback to
		/// DEFAULT_FEATURES would satisfy the assertion below and the test would prove nothing.
		List<String> reduced = Arrays.asList("core", "media", "chat");
		List<String> expected = FeatureConfigUtil.resolveFeatures(reduced);
		assertFalse("The reduced set must NOT equal the default profile or this test is vacuous",
			expected.equals(FeatureConfigUtil.getDefaultFeatures()));

		assertTrue("An AccountAdministrators member failed to store the feature configuration",
			FeatureConfigUtil.setEnabledFeatures(writer, reduced));

		/// The other user - who never wrote anything and cannot write - must see exactly that set.
		/// Pre-change, the record lived in the WRITER's home directory, so this read returned
		/// DEFAULT_FEATURES; see TestLegacyPerUserRecordDoesNotPropagate, which reproduces that.
		List<String> seen = FeatureConfigUtil.getEnabledFeatures(reader);
		assertEquals("The second user did not see the organization's saved feature set", expected, seen);

		/// And the writer sees the same thing.
		assertEquals("The writer sees a different set than the reader", expected,
			FeatureConfigUtil.getEnabledFeatures(writer));
	}

	/// The pre-change storage location, exercised for real: a .featureConfig record in the WRITER's
	/// own home directory does not - and cannot - propagate to another user. This is what D1 replaced.
	@Test
	public void TestLegacyPerUserRecordDoesNotPropagate() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();
		long orgId = writer.get(FieldNames.FIELD_ORGANIZATION_ID);

		/// No org-scoped record at all.
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core")));
		deleteConfigRecord(writer);

		/// Write the OLD shape into the writer's home directory.
		String homePath = writer.get("homeDirectory.path");
		assertNotNull("Writer has no home directory path", homePath);
		BaseRecord homeDir = IOSystem.getActiveContext().getPathUtil().findPath(writer, ModelNames.MODEL_GROUP,
			homePath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Writer home directory not found: " + homePath, homeDir);

		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("features", Arrays.asList("core", "chat"));
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, homePath);
		plist.parameter(FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
		BaseRecord legacy = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, writer, null, plist);
		legacy.set(FieldNames.FIELD_CONTENT_TYPE, FeatureConfigUtil.CONTENT_TYPE);
		legacy.set(FieldNames.FIELD_BYTE_STORE, JSONUtil.exportObject(cfg).getBytes(StandardCharsets.UTF_8));
		BaseRecord createdLegacy = IOSystem.getActiveContext().getAccessPoint().create(writer, legacy);
		assertNotNull("Failed to create the legacy per-user record", createdLegacy);
		FeatureConfigUtil.invalidate(orgId);

		try {
			assertEquals("A per-user home-directory record must NOT be visible to another user - it resolves to the default profile",
				FeatureConfigUtil.getDefaultFeatures(), FeatureConfigUtil.getEnabledFeatures(reader));
			assertEquals("A per-user home-directory record must not even be visible to its own author now that resolution is org-scoped",
				FeatureConfigUtil.getDefaultFeatures(), FeatureConfigUtil.getEnabledFeatures(writer));
		}
		finally {
			IOSystem.getActiveContext().getAccessPoint().delete(writer, createdLegacy);
		}
	}

	/// ------------------------------------------------------------------------------------------
	/// (b) present/absent: no record => default profile; stored [] => core only
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestAbsentRecordVersusEmptySelection() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		/// Establish /Library/Configuration + the read grant, then remove the record.
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core", "chat")));
		deleteConfigRecord(writer);

		assertEquals("No record must resolve to the default profile for the reader",
			FeatureConfigUtil.getDefaultFeatures(), FeatureConfigUtil.getEnabledFeatures(reader));
		assertEquals("No record must resolve to the default profile for the writer",
			FeatureConfigUtil.getDefaultFeatures(), FeatureConfigUtil.getEnabledFeatures(writer));

		/// An explicit empty selection is NOT the same thing: core is force-included, so the smallest
		/// legitimate stored answer is ["core"] - the minimal profile.
		assertTrue("Failed to store an empty selection",
			FeatureConfigUtil.setEnabledFeatures(writer, new ArrayList<String>()));
		assertEquals("An empty selection must resolve to core only, not to the default profile",
			Arrays.asList("core"), FeatureConfigUtil.getEnabledFeatures(reader));

		/// Prove the payload really is the empty-selection shape and not an absent record.
		BaseRecord rec = findConfigRecord(reader);
		assertNotNull("The reader cannot read the org's feature configuration record", rec);
		assertTrue("Stored payload should carry the resolved core-only list, got: " + payloadOf(rec),
			payloadOf(rec).contains("core"));
	}

	/// ------------------------------------------------------------------------------------------
	/// (c) core is force-included on READ
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestCoreForcedOnRead() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core", "games")));

		/// Hand-edit the stored record so it deliberately omits core - what setEnabledFeatures would
		/// never write. A routeless application must not be reachable this way.
		assertTrue("Failed to write a core-less payload",
			writeRawPayload(writer, "{\"features\":[\"games\"]}"));
		BaseRecord raw = findConfigRecord(writer);
		assertNotNull("Raw record missing", raw);
		assertFalse("The stored payload was supposed to omit core: " + payloadOf(raw),
			payloadOf(raw).contains("core"));

		List<String> seen = FeatureConfigUtil.getEnabledFeatures(reader);
		assertTrue("core must be force-included on read even when the stored record omits it",
			seen.contains(FeatureConfigUtil.FEATURE_CORE));
		assertEquals("read should resolve the stored set in manifest order with core forced",
			Arrays.asList("core", "games"), seen);
	}

	/// ------------------------------------------------------------------------------------------
	/// (d) a plain non-admin cannot write
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestPlainUserCannotWrite() {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		List<String> stored = Arrays.asList("core", "games");
		List<String> expected = FeatureConfigUtil.resolveFeatures(stored);
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, stored));
		assertEquals("Setup did not take effect", expected, FeatureConfigUtil.getEnabledFeatures(reader));

		/// The plain user holds only the AccountUsers READ grant on /Library/Configuration.
		List<String> attempt = Arrays.asList("core", "chat", "cardGame", "iso42001");
		assertFalse("A plain non-admin user must not be able to write the organization's feature configuration",
			FeatureConfigUtil.setEnabledFeatures(reader, attempt));

		/// ...and nothing may have changed.
		FeatureConfigUtil.invalidate((long)reader.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals("A rejected write must not mutate the stored feature configuration",
			expected, FeatureConfigUtil.getEnabledFeatures(reader));
	}

	/// The companion to the test above, and a deliberately harder case. TestPlainUserCannotWrite only
	/// proves a plain user cannot MODIFY a record somebody else owns - which could be explained by
	/// ownership alone rather than by the admin role. This case removes the record first, so the plain
	/// user is attempting the very FIRST write in the organization with no owner in the way. If a plain
	/// AccountUsers member can seed /Library/Configuration/.featureConfig, then at the Objects7 layer
	/// the only thing gating the write is Service7's @RolesAllowed({"admin"}) annotation, and any other
	/// caller of FeatureConfigUtil (Console7, a future service, an MCP tool) has no gate at all.
	@Test
	public void TestPlainUserCannotCreateFirstRecord() {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		/// Establish the group + grants, then remove the record so there is no owner to deny against.
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core", "chat")));
		deleteConfigRecord(writer);

		boolean created = FeatureConfigUtil.setEnabledFeatures(reader, Arrays.asList("core", "games"));
		try {
			assertFalse("A plain AccountUsers member created the organization's feature configuration from scratch - "
				+ "the /Library/Configuration grant is supposed to be READ ONLY (LibraryUtil.getCreateSharedLibrary "
				+ "with enableCRU=false), so the only remaining write gate would be Service7's @RolesAllowed({\"admin\"})",
				created);
		}
		finally {
			if(created) {
				BaseRecord rec = findConfigRecord(reader);
				if(rec != null) {
					IOSystem.getActiveContext().getAccessPoint().delete(reader, rec);
				}
				FeatureConfigUtil.invalidate((long)reader.get(FieldNames.FIELD_ORGANIZATION_ID));
			}
		}
	}

	/// ------------------------------------------------------------------------------------------
	/// (e) UTF-8 round trip through dataBytesStore, including the > 512 byte gzip path
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestPayloadRoundTripSmall() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		List<String> ids = Arrays.asList("core", "chat", "schema");
		assertTrue("Write failed", FeatureConfigUtil.setEnabledFeatures(writer, ids));

		BaseRecord rec = findConfigRecord(reader);
		assertNotNull("Record not readable", rec);
		String payload = payloadOf(rec);
		assertTrue("Payload is not the expected JSON object: " + payload, payload.startsWith("{"));
		assertEquals("contentType must be application/json so ByteModelUtil will consider gzip",
			FeatureConfigUtil.CONTENT_TYPE, rec.get(FieldNames.FIELD_CONTENT_TYPE));
		/// Under 512 bytes: stored uncompressed.
		assertTrue("A short payload should not be gzipped, payload length=" + payload.length(),
			payload.length() <= 512);
		/// ByteModelUtil.setValue only ever WRITES compressionType when it actually gzips (:160-164), so
		/// a short payload keeps the model default, which is observably UNKNOWN - not NONE.
		/// ByteModelUtil.getValue only gunzips on an explicit GZIP, so UNKNOWN is correct and harmless.
		/// Asserting "not GZIP" is the real invariant; asserting NONE would be asserting a default this
		/// code path does not set.
		assertFalse("A short payload must not be gzipped, got compressionType " + compressionOf(rec),
			"GZIP".equals(compressionOf(rec)));

		@SuppressWarnings("unchecked")
		Map<String, Object> cfg = JSONUtil.importObject(payload, LinkedHashMap.class);
		assertNotNull("Payload did not parse", cfg);
		assertEquals("Stored ids do not match the resolved ids",
			FeatureConfigUtil.resolveFeatures(ids), cfg.get("features"));
	}

	/// A payload OVER ByteModelUtil.MINIMUM_COMPRESSION_SIZE (512) with contentType application/json
	/// is gzipped on write. FeatureConfigUtil projects compressionType specifically so getValue can
	/// gunzip it; unprojected, the raw gzip bytes come back and silently fail to parse. Non-ASCII
	/// characters are included so the UTF-8 encode/decode is proven, not assumed.
	@Test
	public void TestPayloadRoundTripLargeGzipAndUtf8() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core", "chat")));

		/// Non-ASCII, multi-byte UTF-8, plus enough filler to clear 512 bytes. setEnabledFeatures can
		/// never emit a payload this large from ids alone, so it is written raw on purpose.
		StringBuilder pad = new StringBuilder();
		String unicode = "éü中文Жא🚀";
		while(pad.length() < 700) {
			pad.append(unicode).append("-abcdefghij-");
		}
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("features", Arrays.asList("core", "chat", "media"));
		cfg.put("pad", pad.toString());
		String json = JSONUtil.exportObject(cfg);
		assertTrue("The test payload must exceed 512 bytes to exercise the gzip path, got "
			+ json.getBytes(StandardCharsets.UTF_8).length,
			json.getBytes(StandardCharsets.UTF_8).length > 512);

		assertTrue("Failed to write the large payload", writeRawPayload(writer, json));

		BaseRecord rec = findConfigRecord(reader);
		assertNotNull("Record not readable", rec);
		assertEquals("A payload over 512 bytes with contentType application/json must be stored GZIP - "
			+ "if this is NONE, the gzip path is not being exercised and the compressionType projection is untested",
			"GZIP", compressionOf(rec));

		/// Byte-exact round trip through the compressed byte store.
		assertEquals("The stored payload did not round-trip byte-exactly through dataBytesStore",
			json, payloadOf(rec));

		/// ...and the resolver reads it correctly through the same projection.
		FeatureConfigUtil.invalidate((long)reader.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals("The gzipped payload did not resolve correctly for the reader",
			Arrays.asList("core", "media", "chat"), FeatureConfigUtil.getEnabledFeatures(reader));
	}

	/// ------------------------------------------------------------------------------------------
	/// (f) deps closure survives storage
	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestDepsClosurePersisted() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		/// cardGame declares deps [core, chat]. Storing cardGame alone must read back with both.
		assertTrue("Write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("cardGame")));

		List<String> seen = FeatureConfigUtil.getEnabledFeatures(reader);
		assertTrue("chat is missing from the dep closure of cardGame", seen.contains("chat"));
		assertTrue("core is missing from the dep closure of cardGame", seen.contains("core"));
		assertTrue("cardGame itself is missing", seen.contains("cardGame"));
		assertEquals("The dep closure should be exactly core, chat, cardGame in manifest order",
			Arrays.asList("core", "chat", "cardGame"), seen);

		/// The closure is applied BEFORE storage, so the persisted payload itself carries it - the
		/// client applies its own closure and must not diverge from what the server stored.
		BaseRecord rec = findConfigRecord(reader);
		assertNotNull("Record not readable", rec);
		String payload = payloadOf(rec);
		assertTrue("The stored payload should already contain the closed dep set: " + payload,
			payload.contains("chat") && payload.contains("core") && payload.contains("cardGame"));
	}

	/// ------------------------------------------------------------------------------------------
	/// (h) M1: an ABSENT (or DENIED) resolution must never be cached for the whole organization
	/// ------------------------------------------------------------------------------------------

	/// The cache is keyed by organizationId, but load() computes the value under a PER-USER authorization
	/// decision: AccessPoint.find returns null for a PBAC DENIAL exactly as it does for a genuinely ABSENT
	/// record, and both land on the same `absent` return. Caching that published one user's negative
	/// outcome as the WHOLE organization's answer for a TTL window - so an unprivileged user looping on
	/// the read endpoint could keep the org pinned to the default profile (every feature) and silently
	/// override the admin's saved reduction.
	///
	/// This exercises the deterministic trigger (no record). The write deliberately goes through the raw
	/// helper with invalidate=false, because setEnabledFeatures() invalidates in its own finally block,
	/// which would mask the defect and make this test vacuous. That is also the honest cross-process case:
	/// a Console7 or second-Tomcat write cannot invalidate THIS JVM's cache, so the only thing that can
	/// make the new record visible is not having cached the absence in the first place.
	///
	/// BEFORE the fix this fails on the second read - it returns the cached default profile. AFTER the fix
	/// the absence is never cached, so the stored set is observed on the next read, inside the TTL.
	@Test
	public void TestAbsentResolutionIsNotCachedForTheOrg() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();
		long orgId = reader.get(FieldNames.FIELD_ORGANIZATION_ID);

		/// Establish /Library/Configuration + the AccountUsers read grant, then remove the record itself so
		/// the organization resolves to "absent".
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core", "chat")));
		deleteConfigRecord(writer);

		/// A reduced set, so a stale default profile cannot accidentally satisfy the assertion below.
		List<String> expected = FeatureConfigUtil.resolveFeatures(Arrays.asList("core", "media"));
		assertFalse("The stored set must differ from the default profile or this test is vacuous",
			expected.equals(FeatureConfigUtil.getDefaultFeatures()));

		try {
			/// Read #1 - the low-privileged user warms the org's cache entry from an ABSENT resolution.
			long t0 = System.currentTimeMillis();
			assertEquals("With no stored record the organization must resolve to the default profile",
				FeatureConfigUtil.getDefaultFeatures(), FeatureConfigUtil.getEnabledFeatures(reader));

			/// The record now exists, with NO in-process cache invalidation.
			assertTrue("Failed to write the payload",
				writeRawPayload(writer, "{\"features\":[\"core\",\"media\"]}", false));

			/// Read #2, inside the TTL.
			List<String> seen = FeatureConfigUtil.getEnabledFeatures(reader);
			long elapsed = System.currentTimeMillis() - t0;

			/// If the two reads straddled the TTL the entry would have expired on its own and this would
			/// prove nothing, so assert the window explicitly rather than assuming it.
			assertTrue("This assertion only means something INSIDE the TTL window, but " + elapsed
				+ "ms elapsed since the first read and CACHE_TTL_MS is " + FeatureConfigUtil.CACHE_TTL_MS + "ms",
				elapsed < FeatureConfigUtil.CACHE_TTL_MS);
			assertEquals("An absent resolution was cached for the whole organization: a record stored after the "
				+ "first read stays invisible for the rest of the TTL. Only a POSITIVE resolution may be cached, "
				+ "because an absent result is indistinguishable from a per-user PBAC denial.",
				expected, seen);
		}
		finally {
			/// Plain cleanup, no assertions - an assertion here would mask a real failure above.
			BaseRecord rec = findConfigRecord(writer);
			if(rec != null) {
				IOSystem.getActiveContext().getAccessPoint().delete(writer, rec);
			}
			FeatureConfigUtil.invalidate(orgId);
		}
	}

	/// L2 companion: a stored id carrying CR/LF reaches the "Ignoring an unknown feature id" log line on
	/// the READ path, so it must be sanitized before it is logged. This asserts the BEHAVIOUR around that
	/// path (the crafted id is dropped, core survives, nothing throws) and executes the sanitizing branch;
	/// it does not - and cannot, from here - assert on the emitted log text itself.
	@Test
	public void TestCrlfBearingFeatureIdIsDroppedAndDoesNotThrow() throws Exception {
		BaseRecord writer = getWriter();
		BaseRecord reader = getReader();

		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core", "chat")));

		/// resolveFeatures is the code path that logs the id; a forged trailing line is included verbatim.
		List<String> forged = Arrays.asList("core", "media\r\n2026-08-07 ERROR [forged] injected log line");
		assertEquals("A CR/LF-bearing id is not a known feature and must be dropped, leaving the valid ids",
			Arrays.asList("core"), FeatureConfigUtil.resolveFeatures(forged));

		/// ...and the same thing through the STORED payload, which is where the value really originates.
		assertTrue("Failed to write the forged payload", writeRawPayload(writer,
			"{\"features\":[\"core\",\"media\\r\\nforged ERROR line\"]}"));
		assertEquals("A CR/LF-bearing stored id must be dropped on read, not resolved",
			Arrays.asList("core"), FeatureConfigUtil.getEnabledFeatures(reader));
	}
}
