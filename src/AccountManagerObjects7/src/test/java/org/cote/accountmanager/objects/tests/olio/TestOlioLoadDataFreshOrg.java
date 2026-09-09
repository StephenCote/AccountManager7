package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Assume;
import org.junit.Test;

/**
 * Regression pin for the {@code WorldUtil.loadOlioData} fresh-organization authorization fix.
 * <p>
 * <b>The bug.</b> On a FRESH organization — one that has never initialized an Olio context, so the
 * {@code /Olio} group does not exist — {@code loadOlioData} resolved/created the org's Olio principal
 * ({@link OlioContext#OLIO_USER_NAME}) and then, acting AS that principal, called
 * {@code getCreateWorld(olioUser, "/Olio/Universes", ...)}. Because {@code /Olio} did not exist and the
 * Olio principal held no Create grant on the org root, {@code PathUtil.makePath} was DENIED and returned
 * null, and {@code WorldUtil.getWorld} (line 48) NPE'd on {@code (long)dir.get(FieldNames.FIELD_ID)}.
 * <p>
 * <b>The fix.</b> {@code loadOlioData} now runs {@code new OlioContext(cfg).configureEnvironment()}
 * first. On first run that creates {@code /Olio} as the org ADMIN, grants the Olio principal
 * Read/Update/Create on it, and creates {@code /Olio/Universes} + the world-path group as the Olio
 * principal — so the subsequent {@code makePath} is permitted.
 * <p>
 * <b>Why a fresh org is essential.</b> The bug only reproduces where {@code /Olio} does not yet exist.
 * The pre-existing test organizations all have {@code /Olio} already, so the branch under test is dead
 * there. This test creates a brand-new organization with a random suffix per run
 * ({@code /Development/OLIOLOAD-<uuid>}), so a re-run cannot silently land on an already-bootstrapped
 * org and pass for the wrong reason.
 * <p>
 * <b>Environment.</b> Live PostgreSQL {@code am7db} (BaseTest's {@code test.db.url}). No schema reset,
 * ever. Reads the base corpus from {@code test.datagen.path} (read-only); {@code includeLocations=false}
 * so the large location grids are skipped. No LLM / embedding / SD contact.
 * <p>
 * <b>Acting user.</b> The fresh org's real ADMIN — the "Load Olio data" feature is admin-gated, so this
 * exercises the real admin path in an isolated org (NOT the shared system-admin shortcut anti-pattern).
 */
public class TestOlioLoadDataFreshOrg extends BaseTest {

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	/** A brand-new organization under {@code /Development}, named with a random suffix (see class javadoc). */
	private String freshOrgPath() {
		return "/Development/OLIOLOAD-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** Find-only group lookup as the organization admin. */
	private BaseRecord groupAt(OrganizationContext org, String path) {
		return IOSystem.getActiveContext().getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_GROUP,
			path, GroupEnumType.DATA.toString(), org.getOrganizationId());
	}

	@Test
	public void TestLoadOlioDataOnFreshOrgConfiguresEnvironmentAndUniverse() {
		String dataPath = dataPath();
		Assume.assumeTrue("Skipping: Olio corpus not present at test.datagen.path=" + dataPath,
			WorldUtil.isOlioDataPresent(dataPath));

		/// A brand-new organization: the ONLY place the fix's branch is reachable.
		String orgPath = freshOrgPath();
		OrganizationContext fresh = getTestOrganization(orgPath);
		assertNotNull("Failed to create the fresh organization " + orgPath, fresh);
		assertTrue("Fresh organization " + orgPath + " not initialized", fresh.isInitialized());
		long orgId = fresh.getOrganizationId();
		BaseRecord admin = fresh.getAdminUser();
		assertNotNull("Fresh organization admin user is null", admin);
		logger.info("Fresh organization " + orgPath + " (#" + orgId + ")");

		/// PRECONDITIONS — this org has never had an Olio context, so the bug's exact condition holds:
		/// no Olio principal, and no /Olio group. If either existed the fix's first-run branch would be
		/// dead here and the test would prove nothing.
		assertNull("Precondition: a brand-new organization must have no Olio principal",
			ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId));
		assertNull("Precondition: a brand-new organization must have no /Olio group", groupAt(fresh, "/Olio"));
		assertNull("Precondition: a brand-new organization must have no /Olio/Universes group",
			groupAt(fresh, "/Olio/Universes"));

		/// THE CALL UNDER TEST: real code path, acting as the fresh org's real admin. Before the fix this
		/// NPE'd inside getCreateWorld->getWorld on a fresh org.
		Map<String, Integer> counts = WorldUtil.loadOlioData(admin, dataPath, false);

		/// PRIMARY ASSERTION 1 — a non-empty counts map. An empty map is loadOlioData's early-return
		/// failure path (org/principal resolution, configureEnvironment failure, or universe creation
		/// failure). Before the fix, the NPE never even reached a return.
		assertNotNull("Counts map is null", counts);
		assertFalse("Counts map is empty — configureEnvironment / universe creation failed (the pre-fix bug)",
			counts.isEmpty());

		/// PRIMARY ASSERTION 2 — the universe olio.world record exists after the call. Resolve it as the
		/// Olio principal (the owner of the universe/world groups), exactly as loadOlioData created it.
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("The Olio principal must exist after loadOlioData (configureEnvironment creates it)", olioUser);
		BaseRecord universe = WorldUtil.findWorld(olioUser, "/Olio/Universes", OlioContextUtil.DEFAULT_UNIVERSE_NAME);
		assertNotNull("The universe olio.world '" + OlioContextUtil.DEFAULT_UNIVERSE_NAME
			+ "' must exist in /Olio/Universes after loadOlioData", universe);

		/// PRIMARY ASSERTION 3 — /Olio exists and is owned by the org ADMIN, confirming configureEnvironment
		/// created it as admin (makePath(octx.getAdminUser(), ...)) and not as the Olio principal.
		BaseRecord olioGroup = groupAt(fresh, "/Olio");
		assertNotNull("The /Olio group must exist after loadOlioData", olioGroup);
		long olioGroupOwner = ((Number) olioGroup.get(FieldNames.FIELD_OWNER_ID)).longValue();
		long adminId = ((Number) admin.get(FieldNames.FIELD_ID)).longValue();
		assertEquals("/Olio must be owned by the org ADMIN (created via configureEnvironment as admin), not the Olio principal",
			adminId, olioGroupOwner);

		/// PRIMARY ASSERTION 4 — the Olio principal can now create under /Olio: makePath as the principal
		/// resolves the /Olio/Universes group (created for it by the grant), proving the Read/Update/Create
		/// grant is in place. This is the exact operation that was DENIED before the fix.
		BaseRecord universesAsPrincipal = IOSystem.getActiveContext().getPathUtil().makePath(olioUser,
			ModelNames.MODEL_GROUP, "/Olio/Universes", GroupEnumType.DATA.toString(), orgId);
		assertNotNull("The Olio principal must be able to resolve/create under /Olio (the grant is in place)",
			universesAsPrincipal);

		/// SECONDARY — corpus keys should be present (magnitude is not the point of THIS test; the corpus
		/// volume is covered by TestOlioDataLoad). Assert presence only, do not fail on counts.
		for (String key : new String[] {"dictionary", "occupations", "names", "surnames", "traits", "colors", "patterns"}) {
			assertTrue("Missing corpus count key '" + key + "' in " + counts, counts.containsKey(key));
		}
		/// includeLocations=false — the large location load must not have run.
		assertFalse("locations must not be present when includeLocations=false", counts.containsKey("locations"));

		logger.info("Fresh-org loadOlioData counts: " + counts);
	}
}
