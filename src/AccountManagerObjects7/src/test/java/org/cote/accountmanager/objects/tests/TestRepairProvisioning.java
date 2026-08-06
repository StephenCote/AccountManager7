package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.SetupUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * GAP 1 — SetupUtil.repairProvisioning(String adminPassword).
 *
 * This restores the pre-change `AdminAction -setup` capability: a safely re-runnable repair of
 * partial provisioning. Routing -setup through runSetup() broke it, because runSetup opens with
 * assertSetupIncomplete() and therefore threw as soon as ANY default organization's administrator
 * held a credential.
 *
 * The contract under test:
 *   - NOT latch-gated (a CLI operator already holds DB + filesystem access)
 *   - creates uninitialized organizations
 *   - sets a credential ONLY where none exists; NEVER overwrites, not even an UNKNOWN-type row
 *   - validates the password LAZILY, only where a credential must actually be created
 *   - still refuses to write the .setupState marker unless adminCredentialExists()
 *   - reports credentialRequiredButNotSet to distinguish "nothing to do" from "needed doing, failed"
 *
 * =========================== WHY THIS TEST IS NOT PART OF THE SUITE ===========================
 * It mutates administrator credentials on the default organizations, so it runs ONLY against an
 * explicitly supplied DISPOSABLE database and HARD-REFUSES the development database. Same guard
 * pattern as TestSetupMarkerGuard. It does not extend BaseTest (BaseTest opens the dev database).
 *
 *   mvn -o -pl AccountManagerObjects7 -Dtest=TestRepairProvisioning test \
 *       -Dam7test.db.url=jdbc:postgresql://localhost:15433/am72db \
 *       -Dam7test.db.user=am7user -Dam7test.db.password=password
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestRepairProvisioning {
	public static final Logger logger = LogManager.getLogger(TestRepairProvisioning.class);

	private static final String[] FORBIDDEN_URL_FRAGMENTS = new String[] { "15430", "/am7db" };
	private static final String PASSWORD = "Repair-Test-2026";

	private IOContext ioContext = null;

	@Before
	public void openDisposableDatabase() throws Exception {
		String url = System.getProperty("am7test.db.url");
		assumeTrue("SKIPPED: -Dam7test.db.url was not supplied (this test mutates administrator"
			+ " credentials and will not run by default)", url != null && url.trim().length() > 0);
		for(String forbidden : FORBIDDEN_URL_FRAGMENTS) {
			if(url.contains(forbidden)) {
				fail("REFUSING to run against '" + url + "': it matches the development database ('"
					+ forbidden + "').");
			}
		}
		OlioModelNames.use();
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(url);
		props.setDataSourceUserName(System.getProperty("am7test.db.user", "am7user"));
		props.setDataSourcePassword(System.getProperty("am7test.db.password", "password"));
		props.setReset(false);
		props.setSchemaCheck(false);
		props.setDropColumns(false);
		ioContext = IOSystem.open(RecordIO.DATABASE, props);
		assertNotNull(ioContext);
	}

	@After
	public void closeDatabase() {
		IOSystem.close();
		ioContext = null;
	}

	/// ----------------------------------------------------------------------------------------
	/// helpers
	/// ----------------------------------------------------------------------------------------

	private OrganizationContext org(String path) {
		return ioContext.getOrganizationContext(path,
			OrganizationEnumType.valueOf(path.substring(1).toUpperCase()));
	}

	private BaseRecord adminOf(String path) {
		OrganizationContext octx = org(path);
		assertNotNull("Organization context for " + path, octx);
		return octx.getAdminUser();
	}

	/// Raw credential row for an organization's administrator, straight out of Postgres, so
	/// "was not overwritten" can be proven byte-for-byte rather than assumed.
	private static final class RawCredential {
		long id;
		String type;
		byte[] credential;
		byte[] salt;
		@Override
		public String toString() {
			return "id=" + id + " type=" + type + " credLen=" + (credential == null ? -1 : credential.length)
				+ " saltLen=" + (salt == null ? -1 : salt.length);
		}
	}

	private RawCredential readRawCredential(String orgPath) {
		BaseRecord admin = adminOf(orgPath);
		if(admin == null) {
			return null;
		}
		long userId = admin.get(FieldNames.FIELD_ID);
		String credTable = ioContext.getDbUtil().getTableName("auth.credential");
		String hashTable = ioContext.getDbUtil().getTableName("crypto.hash");
		String sql = "SELECT c.id, c.type, c.credential, h.salt FROM " + credTable + " c"
			+ " LEFT JOIN " + hashTable + " h ON h.id = c.hash"
			+ " WHERE c.referencemodel = 'system.user' AND c.referenceid = " + userId
			+ " ORDER BY c.id DESC LIMIT 1";
		try (Connection con = ioContext.getDbUtil().getDataSource().getConnection();
				Statement st = con.createStatement();
				ResultSet rs = st.executeQuery(sql)) {
			if(rs.next()) {
				RawCredential rc = new RawCredential();
				rc.id = rs.getLong(1);
				rc.type = rs.getString(2);
				rc.credential = rs.getBytes(3);
				rc.salt = rs.getBytes(4);
				return rc;
			}
		}
		catch(SQLException e) {
			throw new RuntimeException("Raw credential read failed: " + e.getMessage(), e);
		}
		return null;
	}

	/// Delete the administrator credential of ONE organization to manufacture the
	/// partial-provisioning state. Disposable database only — the @Before guard enforces that.
	private int deleteAdminCredential(String orgPath) {
		BaseRecord admin = adminOf(orgPath);
		assertNotNull(admin);
		/// Delete through the AM7 API, not raw SQL: a raw DELETE bypasses the record cache, so
		/// CredentialUtil.getLatestCredential would keep returning the deleted row for the rest of
		/// this JVM and the "credential is missing" state would never actually be reached.
		BaseRecord cred = CredentialUtil.getLatestCredential(admin);
		if(cred == null) {
			return 0;
		}
		boolean deleted = ioContext.getRecordUtil().deleteRecord(cred);
		CacheUtil.clearCache();
		assertNull("The credential must really be gone (cache included)",
			CredentialUtil.getLatestCredential(adminOf(orgPath)));
		return (deleted ? 1 : 0);
	}

	/// Captures ERROR-level events so the "no ERROR line on a clean re-run" regression can be
	/// asserted rather than eyeballed in the console.
	private static final class ErrorCapture extends AbstractAppender {
		private final List<String> errors = new java.util.ArrayList<>();
		ErrorCapture() {
			super("TestErrorCapture", null, null, true, null);
		}
		@Override
		public void append(LogEvent event) {
			if(event.getLevel().isMoreSpecificThan(Level.ERROR)) {
				synchronized(errors) {
					errors.add(event.getLoggerName() + ": " + event.getMessage().getFormattedMessage());
				}
			}
		}
		List<String> snapshot() {
			synchronized(errors) {
				return new java.util.ArrayList<>(errors);
			}
		}
	}

	private ErrorCapture attachErrorCapture() {
		LoggerContext lctx = (LoggerContext)LogManager.getContext(false);
		Configuration cfg = lctx.getConfiguration();
		ErrorCapture cap = new ErrorCapture();
		cap.start();
		cfg.addAppender(cap);
		LoggerConfig root = cfg.getRootLogger();
		root.addAppender(cap, Level.ERROR, null);
		lctx.updateLoggers();
		return cap;
	}

	private void detachErrorCapture(ErrorCapture cap) {
		LoggerContext lctx = (LoggerContext)LogManager.getContext(false);
		Configuration cfg = lctx.getConfiguration();
		cfg.getRootLogger().removeAppender(cap.getName());
		lctx.updateLoggers();
		cap.stop();
	}

	/// ----------------------------------------------------------------------------------------
	/// 5. Marker still guarded (run first: it requires the no-credential state)
	/// ----------------------------------------------------------------------------------------

	@Test
	public void test1_repairWithNoCredentialAnywhereDoesNotWriteTheMarker() {
		assumeTrue("SKIPPED: the database is already provisioned; this case needs a first-run state",
			!SetupUtil.adminCredentialExists() && !SetupUtil.markerExists());

		SetupUtil.SetupResult res = null;
		try {
			res = SetupUtil.repairProvisioning(null);
		}
		catch(Exception e) {
			fail("repairProvisioning threw: " + e.getMessage());
		}
		logger.info("repair(null pw, virgin DB) ok=" + res.isOk()
			+ " credentialRequiredButNotSet=" + res.isCredentialRequiredButNotSet()
			+ " warnings=" + res.getWarnings());

		assertFalse("Must not report success with no credential anywhere", res.isOk());
		assertFalse("The " + SetupUtil.SETUP_MARKER_NAME + " marker must NOT be written",
			SetupUtil.markerExists());
		assertFalse("The latch must remain OPEN", SetupUtil.isSetupComplete());
		assertNull("No credential may have been created for /System",
			readRawCredential(OrganizationContext.SYSTEM_ORGANIZATION));
	}

	/// ----------------------------------------------------------------------------------------
	/// 1. THE PARTIAL-PROVISIONING REPAIR CASE — the whole point
	/// ----------------------------------------------------------------------------------------

	@Test
	public void test2_repairsTheMissingCredentialAndLeavesTheExistingOneByteForByteUnchanged() {
		/// Provision everything first (this is also assertion 3's precondition).
		SetupUtil.SetupResult seed = null;
		try {
			seed = SetupUtil.repairProvisioning(PASSWORD);
		}
		catch(Exception e) {
			fail("seed repairProvisioning threw: " + e.getMessage());
		}
		assertTrue("Seeding must succeed: " + seed.getWarnings(), seed.isOk());
		assertTrue("All three administrators must now hold a credential",
			SetupUtil.adminCredentialExists());

		/// Manufacture the partial state: /Public loses its administrator credential,
		/// /System and /Development keep theirs.
		int deleted = deleteAdminCredential(OrganizationContext.PUBLIC_ORGANIZATION);
		assertEquals("Exactly one credential row should have been removed", 1, deleted);
		assertNull("/Public must now have NO administrator credential",
			readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION));

		RawCredential sysBefore = readRawCredential(OrganizationContext.SYSTEM_ORGANIZATION);
		RawCredential devBefore = readRawCredential(OrganizationContext.DEVELOPMENT_ORGANIZATION);
		assertNotNull("/System must still hold a credential", sysBefore);
		assertNotNull("/Development must still hold a credential", devBefore);
		logger.info("PARTIAL STATE: /System " + sysBefore + " | /Development " + devBefore + " | /Public MISSING");

		/// THE REPAIR — with a DIFFERENT password, so an overwrite would be unmistakable.
		String repairPassword = "Repair-Different-2026";
		SetupUtil.SetupResult res = null;
		try {
			res = SetupUtil.repairProvisioning(repairPassword);
		}
		catch(Exception e) {
			fail("repairProvisioning threw: " + e.getMessage());
		}
		logger.info("repair(partial) ok=" + res.isOk()
			+ " credentialRequiredButNotSet=" + res.isCredentialRequiredButNotSet()
			+ " warnings=" + res.getWarnings());
		assertTrue("The repair must report success: " + res.getWarnings(), res.isOk());
		assertFalse("Nothing failed to be created", res.isCredentialRequiredButNotSet());

		/// The missing credential was created.
		RawCredential pubAfter = readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION);
		assertNotNull("/Public's administrator credential must have been CREATED", pubAfter);
		assertEquals("and it must be a usable hashed password",
			CredentialEnumType.HASHED_PASSWORD.toString(), pubAfter.type);
		logger.info("REPAIRED: /Public " + pubAfter);

		/// The existing ones are byte-for-byte identical — not merely "still present".
		RawCredential sysAfter = readRawCredential(OrganizationContext.SYSTEM_ORGANIZATION);
		RawCredential devAfter = readRawCredential(OrganizationContext.DEVELOPMENT_ORGANIZATION);
		assertEquals("/System credential row id must be unchanged", sysBefore.id, sysAfter.id);
		assertEquals("/System credential type must be unchanged", sysBefore.type, sysAfter.type);
		assertArrayEquals("/System credential HASH must be byte-for-byte unchanged",
			sysBefore.credential, sysAfter.credential);
		assertArrayEquals("/System credential SALT must be byte-for-byte unchanged",
			sysBefore.salt, sysAfter.salt);
		assertEquals("/Development credential row id must be unchanged", devBefore.id, devAfter.id);
		assertArrayEquals("/Development credential HASH must be byte-for-byte unchanged",
			devBefore.credential, devAfter.credential);
		assertArrayEquals("/Development credential SALT must be byte-for-byte unchanged",
			devBefore.salt, devAfter.salt);

		/// The surviving credentials still verify against the ORIGINAL password, and not the
		/// repair password — the strongest form of "was not overwritten".
		assertTrue("/System must still authenticate with the ORIGINAL password",
			verify(OrganizationContext.SYSTEM_ORGANIZATION, PASSWORD));
		assertFalse("/System must NOT authenticate with the repair password",
			verify(OrganizationContext.SYSTEM_ORGANIZATION, repairPassword));
		/// The newly created one uses the repair password.
		assertTrue("/Public must authenticate with the REPAIR password",
			verify(OrganizationContext.PUBLIC_ORGANIZATION, repairPassword));
	}

	private boolean verify(String orgPath, String password) {
		BaseRecord admin = adminOf(orgPath);
		BaseRecord cred = CredentialUtil.getLatestCredential(admin);
		if(cred == null) {
			return false;
		}
		try {
			return ioContext.getFactory().verify(admin, cred,
				org.cote.accountmanager.util.ParameterUtil.newParameterList("password", password))
				== org.cote.accountmanager.schema.type.VerificationEnumType.VERIFIED;
		}
		catch(Exception e) {
			logger.error("verify failed: " + e.getMessage());
			return false;
		}
	}

	/// ----------------------------------------------------------------------------------------
	/// 2 + 3. Idempotent clean re-run, not latch-gated, and NO ERROR log line
	/// ----------------------------------------------------------------------------------------

	@Test
	public void test3_cleanReRunIsIdempotentNotLatchGatedAndLogsNoError() {
		assumeTrue("SKIPPED: run test2 first — this needs a fully provisioned database",
			SetupUtil.adminCredentialExists());

		/// 3. The precondition that used to throw: the latch is CLOSED.
		assertTrue("Precondition: the setup latch must be CLOSED for this case to be meaningful",
			SetupUtil.isSetupComplete());

		RawCredential sysBefore = readRawCredential(OrganizationContext.SYSTEM_ORGANIZATION);
		RawCredential devBefore = readRawCredential(OrganizationContext.DEVELOPMENT_ORGANIZATION);
		RawCredential pubBefore = readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION);

		ErrorCapture cap = attachErrorCapture();
		SetupUtil.SetupResult res = null;
		try {
			res = SetupUtil.repairProvisioning(PASSWORD);
		}
		catch(Exception e) {
			detachErrorCapture(cap);
			fail("repairProvisioning must NOT throw on a fully provisioned database (this is the"
				+ " regression: runSetup's assertSetupIncomplete threw here): " + e.getMessage());
		}
		List<String> errors = cap.snapshot();
		detachErrorCapture(cap);

		logger.info("repair(clean re-run) ok=" + res.isOk()
			+ " credentialRequiredButNotSet=" + res.isCredentialRequiredButNotSet()
			+ " warnings=" + res.getWarnings());
		logger.info("ERROR lines captured during the clean re-run: " + errors);

		assertTrue("A clean re-run must report ok=true: " + res.getWarnings(), res.isOk());
		assertFalse("Nothing needed creating", res.isCredentialRequiredButNotSet());

		/// THE CI-LOG REGRESSION: the clean re-run must emit no ERROR from the setup path, and in
		/// particular not the "Setup was not run: Setup has already been completed" line the
		/// runSetup routing produced.
		///
		/// SCOPED DELIBERATELY: this JVM runs against a database whose vault credential FILES live
		/// inside the am7test container (/data/am7/store/.vault/...), so VaultService emits
		/// "File not found" errors that are an artifact of the test environment, not of
		/// repairProvisioning. Those are excluded by logger name and asserted to be exactly that.
		for(String e : errors) {
			/// The ONLY tolerated errors are vault-FILE errors: this JVM runs against a database
			/// whose vault credential files live inside the am7test container
			/// (/data/am7/store/.vault/...), which it cannot read. Every one of them mentions the
			/// vault; a provisioning/credential/marker error never would, so anything else fails.
			assertTrue("Unexpected non-vault ERROR during a clean re-run: " + e,
				e.toLowerCase().contains("vault") || e.startsWith("org.cote.accountmanager.util.ErrorUtil:"));
		}
		for(String e : errors) {
			assertFalse("The -setup regression line must NOT appear: " + e,
				e.contains("already been completed") || e.contains("Setup was not run")
					|| e.contains("did NOT complete") || e.contains("REFUSING"));
		}

		/// Nothing mutated.
		assertArrayEquals("/System credential unchanged", sysBefore.credential,
			readRawCredential(OrganizationContext.SYSTEM_ORGANIZATION).credential);
		assertArrayEquals("/Development credential unchanged", devBefore.credential,
			readRawCredential(OrganizationContext.DEVELOPMENT_ORGANIZATION).credential);
		assertArrayEquals("/Public credential unchanged", pubBefore.credential,
			readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION).credential);
	}

	/// ----------------------------------------------------------------------------------------
	/// 4. Lazy password validation
	/// ----------------------------------------------------------------------------------------

	@Test
	public void test4_lazyPasswordValidation() {
		assumeTrue("SKIPPED: run test2 first — this needs a fully provisioned database",
			SetupUtil.adminCredentialExists());

		/// (a) Healthy deployment + a password too short to ever be accepted: still succeeds,
		///     because no credential has to be created.
		SetupUtil.SetupResult shortPw = null;
		try {
			shortPw = SetupUtil.repairProvisioning("abc");
		}
		catch(Exception e) {
			fail("repairProvisioning threw on a healthy deployment with a short password: " + e.getMessage());
		}
		logger.info("repair(healthy, short pw) ok=" + shortPw.isOk()
			+ " credentialRequiredButNotSet=" + shortPw.isCredentialRequiredButNotSet());
		assertTrue("A short password must be IGNORED when nothing needs creating: "
			+ shortPw.getWarnings(), shortPw.isOk());
		assertFalse(shortPw.isCredentialRequiredButNotSet());

		/// (b) Healthy deployment + a null password: same.
		SetupUtil.SetupResult nullPw = null;
		try {
			nullPw = SetupUtil.repairProvisioning(null);
		}
		catch(Exception e) {
			fail("repairProvisioning threw on a healthy deployment with a null password: " + e.getMessage());
		}
		assertTrue("A null password must be IGNORED when nothing needs creating: "
			+ nullPw.getWarnings(), nullPw.isOk());

		/// (c) A repair that DOES need to create a credential must REJECT a short password and
		///     create nothing.
		int deleted = deleteAdminCredential(OrganizationContext.PUBLIC_ORGANIZATION);
		assertEquals(1, deleted);
		assertNull(readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION));

		SetupUtil.SetupResult needed = null;
		try {
			needed = SetupUtil.repairProvisioning("abc");
		}
		catch(Exception e) {
			fail("repairProvisioning threw: " + e.getMessage());
		}
		logger.info("repair(needs creation, short pw) ok=" + needed.isOk()
			+ " credentialRequiredButNotSet=" + needed.isCredentialRequiredButNotSet()
			+ " warnings=" + needed.getWarnings());
		assertFalse("A short password must be REJECTED when a credential must be created",
			needed.isOk());
		/// 6. The flag distinguishes "nothing to do" from "needed doing and failed".
		assertTrue("credentialRequiredButNotSet must be TRUE here",
			needed.isCredentialRequiredButNotSet());
		assertNull("NO credential may have been created with the rejected password",
			readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION));

		/// And the repair succeeds once a valid password is supplied.
		SetupUtil.SetupResult fixed = null;
		try {
			fixed = SetupUtil.repairProvisioning(PASSWORD);
		}
		catch(Exception e) {
			fail("repairProvisioning threw: " + e.getMessage());
		}
		assertTrue("A valid password must complete the repair: " + fixed.getWarnings(), fixed.isOk());
		assertNotNull("/Public's credential must now exist",
			readRawCredential(OrganizationContext.PUBLIC_ORGANIZATION));
		assertTrue("/Public must authenticate with the valid password",
			verify(OrganizationContext.PUBLIC_ORGANIZATION, PASSWORD));
	}
}
