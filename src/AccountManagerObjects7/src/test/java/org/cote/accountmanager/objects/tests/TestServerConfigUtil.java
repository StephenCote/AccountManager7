package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ServerConfigUtil;
import org.cote.accountmanager.util.SetupUtil;
import org.junit.After;
import org.junit.Test;

/**
 * Deployment media/AI server configuration (system.connection records in /System's
 * /Library/Connections) — the DB-backed replacement for the web.xml init-params that
 * docker/entrypoint.sh regenerates on every boot.
 *
 * NOTE ON THE ACTOR: ServerConfigUtil.putConnection() requires the /System organization admin
 * record by design — the six URLs are /System-global deployment configuration and this very
 * feature HARD-REJECTS creating any non-provisioning user in /System (SetupUtil
 * .validateInitialUserOrganization), so there is no other principal that can write them. That is
 * the only place the /System admin record is used here.
 *
 * The test writes and then REMOVES a record for the 'tag' server name only. 'tag' is read per-call
 * (ServerConfigUtil.applyToBoundUtils touches only voice/embedding), so the test cannot perturb the
 * live embedding or voice singletons.
 */
public class TestServerConfigUtil extends BaseTest {

	private static final String TEST_NAME = ServerConfigUtil.SERVER_TAG;

	private BaseRecord systemAdmin() {
		OrganizationContext octx = SetupUtil.systemOrganizationContext();
		assertNotNull("The /System organization context should not be null", octx);
		BaseRecord admin = octx.getAdminUser();
		assertNotNull("The /System admin user should not be null", admin);
		return admin;
	}

	/// Direct read of the connection record so the test can verify and clean up. ServerConfigUtil
	/// deliberately exposes no record getter (the apiKey decrypts on READ and must never escape).
	private BaseRecord findRecord(String name) {
		BaseRecord admin = systemAdmin();
		long orgId = admin.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord dir = ioContext.getPathUtil().findPath(admin, ModelNames.MODEL_GROUP,
			ChatLibraryUtil.LIBRARY_PATH_CONNECTION, GroupEnumType.DATA.toString(), orgId);
		if(dir == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_NAME, "serverUrl", "apiKey", "vaulted" });
		return ioContext.getSearch().findRecord(q);
	}

	/// Raw column read, bypassing EncryptFieldProvider entirely, so the test can prove the key is
	/// not sitting in the database in plaintext.
	private String readRawApiKeyColumn(long id) {
		String table = ioContext.getDbUtil().getTableName(ModelNames.MODEL_CONNECTION);
		String sql = "SELECT apikey FROM " + table + " WHERE id = " + id;
		try (java.sql.Connection con = ioContext.getDbUtil().getDataSource().getConnection();
				java.sql.Statement st = con.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			if(rs.next()) {
				return rs.getString(1);
			}
		}
		catch(java.sql.SQLException e) {
			throw new RuntimeException("Raw apiKey read failed: " + e.getMessage(), e);
		}
		return null;
	}

	@After
	public void cleanupServerConfig() {
		try {
			BaseRecord rec = findRecord(TEST_NAME);
			if(rec != null) {
				logger.info("Cleaning up the test '" + TEST_NAME + "' connection record");
				ioContext.getAccessPoint().delete(systemAdmin(), rec);
			}
			ServerConfigUtil.invalidate(null);
		}
		catch(Exception e) {
			logger.error("Cleanup failed: " + e.getMessage());
		}
	}

	/// A name that is not one of the six deployment names resolves to NOTHING, and the caller's
	/// web.xml fallback comes back verbatim.
	@Test
	public void testUnknownNameReturnsFallbackVerbatim() {
		String fallback = "http://fallback.example:7801/x?a=b";
		assertFalse("'am7.test.sd' is not a deployment server name", ServerConfigUtil.isServerName("am7.test.sd"));
		assertEquals("An unknown name must return the fallback verbatim", fallback,
			ServerConfigUtil.getServerUrl("am7.test.sd", fallback));
		assertEquals("An unknown name must return the default token verbatim", "tok",
			ServerConfigUtil.getAuthorizationToken("am7.test.sd", "tok"));
		assertNull("An unknown name has no init-param mapping", ServerConfigUtil.getInitParameterName("am7.test.sd"));
		assertFalse("An unknown name never reports an apiKey", ServerConfigUtil.hasApiKey("am7.test.sd"));
		assertNull("A null name must not resolve", ServerConfigUtil.getServerUrl(null, null));

		/// A KNOWN name with no record must also fall back verbatim.
		assertNull("Precondition: no '" + TEST_NAME + "' record exists yet", findRecord(TEST_NAME));
		ServerConfigUtil.invalidate(null);
		assertEquals("A known name with no record must return the fallback verbatim", fallback,
			ServerConfigUtil.getServerUrl(TEST_NAME, fallback));
	}

	/// The six names map to the six web.xml init-params, in order.
	@Test
	public void testNameToInitParameterMapping() {
		assertEquals("There must be six deployment server names", 6, ServerConfigUtil.SERVER_NAMES.length);
		assertEquals("sd.server", ServerConfigUtil.getInitParameterName(ServerConfigUtil.SERVER_SD));
		assertEquals("face.server", ServerConfigUtil.getInitParameterName(ServerConfigUtil.SERVER_FACE));
		assertEquals("tag.server", ServerConfigUtil.getInitParameterName(ServerConfigUtil.SERVER_TAG));
		assertEquals("voice.tts.server", ServerConfigUtil.getInitParameterName(ServerConfigUtil.SERVER_VOICE_TTS));
		assertEquals("voice.stt.server", ServerConfigUtil.getInitParameterName(ServerConfigUtil.SERVER_VOICE_STT));
		assertEquals("embedding.server", ServerConfigUtil.getInitParameterName(ServerConfigUtil.SERVER_EMBEDDING));
		for(String name : ServerConfigUtil.SERVER_NAMES) {
			assertTrue(name + " must be a recognized server name", ServerConfigUtil.isServerName(name));
		}
		assertFalse("The seeded chat connection name must not collide with a deployment name",
			ServerConfigUtil.isServerName(ChatLibraryUtil.DEFAULT_CONNECTION_NAME));
	}

	/// Write a record, read it back through the resolver, overwrite it, and confirm putConnection's
	/// own invalidation makes the new value visible immediately.
	@Test
	public void testRecordOverridesFallbackAndUpdateIsVisible() {
		BaseRecord admin = systemAdmin();
		String fallback = "http://fallback.example:7801";
		String url1 = "http://tagserver-one.example:8124";
		String url2 = "http://tagserver-two.example:8125";

		assertTrue("putConnection must succeed", ServerConfigUtil.putConnection(admin, TEST_NAME, url1, null));
		BaseRecord rec = findRecord(TEST_NAME);
		assertNotNull("The connection record must exist after putConnection", rec);
		assertEquals("The record must live in /System", (long)admin.get(FieldNames.FIELD_ORGANIZATION_ID),
			(long)rec.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals("The record must carry the written URL", url1, rec.get("serverUrl"));

		assertEquals("The record URL must override the fallback", url1,
			ServerConfigUtil.getServerUrl(TEST_NAME, fallback));

		assertTrue("An update through putConnection must succeed",
			ServerConfigUtil.putConnection(admin, TEST_NAME, url2, null));
		assertEquals("putConnection invalidates its own cache entry, so the new URL is visible at once",
			url2, ServerConfigUtil.getServerUrl(TEST_NAME, fallback));
		assertEquals("The update must have been persisted", url2, findRecord(TEST_NAME).get("serverUrl"));

		assertFalse("A keyless connection must not report an apiKey", ServerConfigUtil.hasApiKey(TEST_NAME));
		assertEquals("A keyless connection must return the default token",
			"boot-token", ServerConfigUtil.getAuthorizationToken(TEST_NAME, "boot-token"));
	}

	/// The 30s TTL cache is real: an out-of-band update (one that does NOT go through
	/// putConnection, e.g. an edit made by the Console7 JVM) stays invisible until the entry is
	/// invalidated or expires. invalidate(name) must make it visible immediately.
	@Test
	public void testCacheHoldsStaleValueUntilInvalidated() {
		BaseRecord admin = systemAdmin();
		String url1 = "http://tagserver-cached.example:8124";
		String url2 = "http://tagserver-outofband.example:8125";

		assertTrue(ServerConfigUtil.putConnection(admin, TEST_NAME, url1, null));
		assertEquals("Seed the cache", url1, ServerConfigUtil.getServerUrl(TEST_NAME, null));

		/// Out-of-band PATCH: identity + changed field only, no ServerConfigUtil involvement.
		BaseRecord rec = findRecord(TEST_NAME);
		assertNotNull(rec);
		try {
			BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION);
			patch.set(FieldNames.FIELD_ID, rec.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, rec.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_GROUP_ID, rec.get(FieldNames.FIELD_GROUP_ID));
			patch.set(FieldNames.FIELD_ORGANIZATION_ID, rec.get(FieldNames.FIELD_ORGANIZATION_ID));
			/// `name` is included deliberately: system.connection carries a non-empty `name`
			/// validation rule, and the writer validates the PATCH record itself, so a patch that
			/// omits `name` fails validation outright. See
			/// testRecordOverridesFallbackAndUpdateIsVisible — that is the bug this test must route
			/// around in order to test the CACHE rather than re-testing the bug.
			patch.set(FieldNames.FIELD_NAME, rec.get(FieldNames.FIELD_NAME));
			patch.set("serverUrl", url2);
			IOSystem.getActiveContext().getAccessPoint().update(admin, patch);
		}
		catch(Exception e) {
			throw new RuntimeException("Out-of-band update failed: " + e.getMessage(), e);
		}
		assertEquals("The out-of-band update must be persisted", url2, findRecord(TEST_NAME).get("serverUrl"));

		assertEquals("Within the " + ServerConfigUtil.CACHE_TTL_MS + "ms TTL the resolver must still"
			+ " serve the cached value", url1, ServerConfigUtil.getServerUrl(TEST_NAME, null));

		ServerConfigUtil.invalidate(TEST_NAME);
		assertEquals("invalidate(name) must expose the new value immediately", url2,
			ServerConfigUtil.getServerUrl(TEST_NAME, null));
	}

	/// The apiKey round-trips through the /System vault: written encrypted by
	/// EncryptFieldProvider, resolved back in-process by the explicit groupId+apiKey projection
	/// (Chat.java:377-384 / TestConnection.java:52). Omitting either field from the projection
	/// yields a silent null, which is the trap this asserts against.
	@Test
	public void testApiKeyRoundTripsThroughTheVault() {
		BaseRecord admin = systemAdmin();
		String url = "http://tagserver-keyed.example:8124";
		String key = "tk-" + UUID.randomUUID().toString();

		assertTrue("putConnection with an apiKey must succeed",
			ServerConfigUtil.putConnection(admin, TEST_NAME, url, key));

		assertEquals("The apiKey must decrypt back to the original", key,
			ServerConfigUtil.getAuthorizationToken(TEST_NAME, null));
		assertTrue("hasApiKey must be true once a key is stored", ServerConfigUtil.hasApiKey(TEST_NAME));
		assertEquals("The URL must still resolve alongside the key", url,
			ServerConfigUtil.getServerUrl(TEST_NAME, null));

		/// The stored value must actually be encrypted at rest.
		BaseRecord rec = findRecord(TEST_NAME);
		assertNotNull(rec);
		boolean vaulted = rec.get("vaulted", Boolean.FALSE);
		logger.info("Connection vaulted=" + vaulted);
		assertTrue("The connection record must be marked vaulted", vaulted);

		String raw = readRawApiKeyColumn((long)rec.get(FieldNames.FIELD_ID));
		assertNotNull("The apikey column must hold something", raw);
		assertFalse("The apiKey must NOT be stored in plaintext", key.equals(raw));
		assertFalse("The apiKey must not even appear inside the stored value", raw.contains(key));
	}

	/// DIAGNOSTIC for the failure above: is the apiKey unstorable because /System has no vault, or
	/// only because putConnection's patch omits `name` and therefore fails validation?
	///
	/// This performs the SAME write putConnection intends, with `name` added to the patch. If it
	/// passes, the apiKey path itself (vault, EncryptFieldProvider, projection) is sound and the
	/// single defect is the missing `name` in ServerConfigUtil.putConnection.
	@Test
	public void testApiKeyPersistsWhenThePatchIncludesName() {
		BaseRecord admin = systemAdmin();
		String url = "http://tagserver-keyed2.example:8124";
		String key = "tk-" + UUID.randomUUID().toString();

		assertTrue(ServerConfigUtil.putConnection(admin, TEST_NAME, url, null));
		BaseRecord rec = findRecord(TEST_NAME);
		assertNotNull(rec);
		try {
			BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION);
			patch.set(FieldNames.FIELD_ID, rec.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, rec.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_GROUP_ID, rec.get(FieldNames.FIELD_GROUP_ID));
			patch.set(FieldNames.FIELD_ORGANIZATION_ID, rec.get(FieldNames.FIELD_ORGANIZATION_ID));
			patch.set(FieldNames.FIELD_NAME, rec.get(FieldNames.FIELD_NAME));
			patch.set("apiKey", key);
			IOSystem.getActiveContext().getAccessPoint().update(admin, patch);
		}
		catch(Exception e) {
			throw new RuntimeException("Patch with name failed: " + e.getMessage(), e);
		}
		ServerConfigUtil.invalidate(TEST_NAME);

		assertEquals("With `name` in the patch the apiKey must round-trip through the /System vault",
			key, ServerConfigUtil.getAuthorizationToken(TEST_NAME, null));
		assertTrue("hasApiKey must be true", ServerConfigUtil.hasApiKey(TEST_NAME));

		BaseRecord after = findRecord(TEST_NAME);
		boolean vaulted = after.get("vaulted", Boolean.FALSE);
		logger.info("Connection vaulted=" + vaulted);
		assertTrue("The connection record must be marked vaulted", vaulted);
		String raw = readRawApiKeyColumn((long)after.get(FieldNames.FIELD_ID));
		assertNotNull("The apikey column must hold something", raw);
		assertFalse("The apiKey must NOT be stored in plaintext", raw.contains(key));
	}

	/// The 30s TTL must expire on its own: this is how a change written by ANOTHER JVM (Console7
	/// writes these records from a separate process, so in-process invalidate() can never reach the
	/// running WAR) becomes visible without a restart. This test waits the TTL out for real.
	@Test
	public void testCacheTtlExpiresWithoutInvalidation() {
		BaseRecord admin = systemAdmin();
		String url1 = "http://tagserver-ttl-one.example:8124";
		String url2 = "http://tagserver-ttl-two.example:8125";

		assertTrue(ServerConfigUtil.putConnection(admin, TEST_NAME, url1, null));
		assertEquals("Seed the cache", url1, ServerConfigUtil.getServerUrl(TEST_NAME, null));

		BaseRecord rec = findRecord(TEST_NAME);
		assertNotNull(rec);
		try {
			BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION);
			patch.set(FieldNames.FIELD_ID, rec.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, rec.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_GROUP_ID, rec.get(FieldNames.FIELD_GROUP_ID));
			patch.set(FieldNames.FIELD_ORGANIZATION_ID, rec.get(FieldNames.FIELD_ORGANIZATION_ID));
			/// `name` is included deliberately: system.connection carries a non-empty `name`
			/// validation rule, and the writer validates the PATCH record itself, so a patch that
			/// omits `name` fails validation outright. See
			/// testRecordOverridesFallbackAndUpdateIsVisible — that is the bug this test must route
			/// around in order to test the CACHE rather than re-testing the bug.
			patch.set(FieldNames.FIELD_NAME, rec.get(FieldNames.FIELD_NAME));
			patch.set("serverUrl", url2);
			IOSystem.getActiveContext().getAccessPoint().update(admin, patch);
		}
		catch(Exception e) {
			throw new RuntimeException("Out-of-band update failed: " + e.getMessage(), e);
		}
		assertEquals("The out-of-band update must be persisted", url2, findRecord(TEST_NAME).get("serverUrl"));
		assertEquals("Still cached", url1, ServerConfigUtil.getServerUrl(TEST_NAME, null));

		long waitMs = ServerConfigUtil.CACHE_TTL_MS + 2000L;
		logger.info("Waiting " + waitMs + "ms for the server config cache TTL to expire");
		try {
			Thread.sleep(waitMs);
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
		assertEquals("After the TTL expires the resolver must pick up the out-of-band value with no"
			+ " invalidate() call", url2, ServerConfigUtil.getServerUrl(TEST_NAME, null));
	}

	/// listServerUrls() is the CLI/admin view: all six names, URLs only, NEVER a token.
	@Test
	public void testListServerUrlsExposesNoTokens() {
		BaseRecord admin = systemAdmin();
		String url = "http://tagserver-listed.example:8124";
		String key = "tk-secret-" + UUID.randomUUID().toString();
		assertTrue(ServerConfigUtil.putConnection(admin, TEST_NAME, url, key));

		Map<String, String> urls = ServerConfigUtil.listServerUrls();
		assertNotNull(urls);
		assertEquals("All six names must be listed", 6, urls.size());
		for(String name : ServerConfigUtil.SERVER_NAMES) {
			assertTrue("'" + name + "' must be present in the listing", urls.containsKey(name));
		}
		assertEquals("The configured URL must be listed", url, urls.get(TEST_NAME));
		for(Map.Entry<String, String> e : urls.entrySet()) {
			if(e.getValue() != null) {
				assertFalse("listServerUrls() must never leak a token (" + e.getKey() + ")",
					e.getValue().contains(key));
				assertFalse("listServerUrls() must never leak a token fragment (" + e.getKey() + ")",
					e.getValue().contains("tk-secret-"));
			}
		}
		/// The token is still resolvable through the token accessor — it is simply not in the listing.
		assertEquals(key, ServerConfigUtil.getAuthorizationToken(TEST_NAME, null));
	}

	/// putConnection must refuse anything that is not one of the six names, and refuse a null actor.
	@Test
	public void testPutConnectionRejectsBadInput() {
		BaseRecord admin = systemAdmin();
		assertFalse("A non-deployment name must be refused",
			ServerConfigUtil.putConnection(admin, "am7.test.sd", "http://x", null));
		assertFalse("A null name must be refused",
			ServerConfigUtil.putConnection(admin, null, "http://x", null));
		assertFalse("A null actor must be refused",
			ServerConfigUtil.putConnection(null, TEST_NAME, "http://x", null));
		assertNull("Nothing may have been written", findRecord(TEST_NAME));
	}
}
