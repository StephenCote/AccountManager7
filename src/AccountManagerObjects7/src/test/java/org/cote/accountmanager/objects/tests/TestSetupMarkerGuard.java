package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.util.SetupUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * BUG 4 — the .setupState marker guard (SetupUtil.runSetup step 6).
 *
 * The marker is IRREVERSIBLE: writing it closes the setup latch permanently, and the setup
 * endpoint is the only way to set the first administrator password. A runSetup() call with a null
 * adminPassword must therefore REFUSE to write it, and must leave the latch OPEN so the operator
 * can still complete setup.
 *
 * =========================== WHY THIS TEST IS NOT PART OF THE SUITE ===========================
 * It is only reachable in first-run state (open latch, no administrator credential anywhere), and
 * calling runSetup() against a database that is NOT disposable risks exactly the damage the guard
 * exists to prevent. It therefore:
 *   - runs ONLY when -Dam7test.db.url is supplied explicitly (otherwise it is SKIPPED), and
 *   - HARD-REFUSES to run against the development database.
 * It does not extend BaseTest, because BaseTest opens the development database in @Before.
 *
 * Run it against the disposable am7test stack only:
 *   mvn -o -pl AccountManagerObjects7 -Dtest=TestSetupMarkerGuard test \
 *       -Dam7test.db.url=jdbc:postgresql://localhost:15433/am72db \
 *       -Dam7test.db.user=am7user -Dam7test.db.password=password
 *
 * NOTE: this opens the disposable database from a SECOND JVM, so any vault credential files it
 * creates land on the host filesystem rather than inside the container. Reset the stack
 * (docker compose -p am7test -f docker-compose.test.yml down; rm -rf ./docker-data) before using
 * that database for anything else.
 */
public class TestSetupMarkerGuard {
	public static final Logger logger = LogManager.getLogger(TestSetupMarkerGuard.class);

	/// The development database. Never, under any circumstance, this one.
	private static final String[] FORBIDDEN_URL_FRAGMENTS = new String[] { "15430", "/am7db" };

	private IOContext ioContext = null;

	@Before
	public void openDisposableDatabase() throws Exception {
		String url = System.getProperty("am7test.db.url");
		assumeTrue("SKIPPED: -Dam7test.db.url was not supplied (this test requires a DISPOSABLE"
			+ " first-run database and will not run by default)", url != null && url.trim().length() > 0);

		for(String forbidden : FORBIDDEN_URL_FRAGMENTS) {
			if(url.contains(forbidden)) {
				fail("REFUSING to run against '" + url + "': it matches the development database ('"
					+ forbidden + "'). This test calls runSetup() and could latch it permanently.");
			}
		}

		OlioModelNames.use();
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(url);
		props.setDataSourceUserName(System.getProperty("am7test.db.user", "am7user"));
		props.setDataSourcePassword(System.getProperty("am7test.db.password", "password"));
		/// Explicitly NEVER reset, and no schema rebuild — the container already built the schema.
		props.setReset(false);
		props.setSchemaCheck(false);
		props.setDropColumns(false);
		ioContext = IOSystem.open(RecordIO.DATABASE, props);
		assertNotNull("IO context should not be null", ioContext);
		logger.info("Opened disposable database " + url);
	}

	@After
	public void closeDatabase() {
		IOSystem.close();
		ioContext = null;
	}

	@Test
	public void testRunSetupWithoutAdminPasswordRefusesToWriteTheMarker() {
		/// ---- Preconditions: this must be a genuine first-run database. ----
		boolean marker = SetupUtil.markerExists();
		boolean cred = SetupUtil.adminCredentialExists();
		boolean complete = SetupUtil.isSetupComplete();
		logger.info("PRE: markerExists=" + marker + " adminCredentialExists=" + cred
			+ " isSetupComplete=" + complete);
		assumeTrue("SKIPPED: the target database is already configured (marker=" + marker
			+ ", adminCredential=" + cred + "); the guard is only reachable in first-run state",
			!marker && !cred && !complete);

		/// ---- The dangerous call: runSetup with NO administrator password. ----
		SetupUtil.SetupRequest req = new SetupUtil.SetupRequest();
		req.setAdminPassword(null);
		req.setInitialUserName(null);
		SetupUtil.SetupResult res = null;
		try {
			res = SetupUtil.runSetup(req);
		}
		catch(Exception e) {
			fail("runSetup threw instead of returning a refusal: " + e.getMessage());
		}
		assertNotNull("runSetup must return a result", res);

		List<String> warnings = res.getWarnings();
		logger.info("runSetup(ok=" + res.isOk() + ") warnings=" + warnings);

		/// 1. It must report failure, not success.
		assertFalse("runSetup with no administrator password must NOT report ok=true", res.isOk());

		/// 2. It must say why, and name the marker.
		assertTrue("A warning must be returned", warnings != null && !warnings.isEmpty());
		boolean explained = false;
		for(String w : warnings) {
			if(w.contains(SetupUtil.SETUP_MARKER_NAME) && w.contains("NOT written")) {
				explained = true;
			}
		}
		assertTrue("A warning must state that the " + SetupUtil.SETUP_MARKER_NAME
			+ " marker was NOT written. Actual: " + warnings, explained);

		/// 3. THE MARKER MUST NOT EXIST. This is the irreversible part.
		assertFalse("The " + SetupUtil.SETUP_MARKER_NAME + " marker must NOT have been written",
			SetupUtil.markerExists());

		/// 4. No credential was invented either.
		assertFalse("No administrator credential may exist after a passwordless run",
			SetupUtil.adminCredentialExists());

		/// 5. THE POINT: the latch must still be OPEN, so the operator can still run setup.
		assertFalse("The setup latch must remain OPEN — a passwordless run must be recoverable",
			SetupUtil.isSetupComplete());
	}

	/// The passwordless run must not have half-configured anything that would block a later,
	/// correct run: the organizations must still be present and initialized.
	@Test
	public void testOrganizationsRemainUsableAfterTheRefusal() {
		assumeTrue("SKIPPED: the target database is already configured",
			!SetupUtil.markerExists() && !SetupUtil.adminCredentialExists());

		SetupUtil.SetupRequest req = new SetupUtil.SetupRequest();
		SetupUtil.SetupResult res = null;
		try {
			res = SetupUtil.runSetup(req);
		}
		catch(Exception e) {
			fail("runSetup threw: " + e.getMessage());
		}
		assertFalse("Must report failure", res.isOk());

		int initialized = 0;
		for(String orgPath : OrganizationContext.DEFAULT_ORGANIZATIONS) {
			OrganizationContext octx = ioContext.getOrganizationContext(orgPath,
				OrganizationEnumType.valueOf(orgPath.substring(1).toUpperCase()));
			assertNotNull("Organization context for " + orgPath, octx);
			logger.info("POST-REFUSAL ORG " + orgPath + " initialized=" + octx.isInitialized()
				+ " adminUser=" + (octx.getAdminUser() != null));
			if(octx.isInitialized() && octx.getAdminUser() != null) {
				initialized++;
			}
		}
		assertEquals("All three default organizations must remain usable for a later, correct run",
			OrganizationContext.DEFAULT_ORGANIZATIONS.length, initialized);
		assertFalse("The latch must still be OPEN", SetupUtil.isSetupComplete());
	}
}
