package org.cote.accountmanager.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.picturebook.BookContext;
import org.cote.accountmanager.olio.picturebook.BookContextTestAccess;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.rules.BookWorldInitializationRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.LibraryUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook 2.0 phase 1 - integration tests for the Olio <b>book world</b> path
 * ({@code PbOlioContextUtil} / {@code BookContext} / {@code BookWorldInitializationRule} and the
 * {@code OlioContext}/{@code OlioContextUtil} changes they depend on).
 * <p>
 * <b>Package placement.</b> This lives in the production package {@code org.cote.accountmanager.olio}
 * (not {@code org.cote.accountmanager.objects.tests.olio}) so it can reach package-private Olio
 * internals without widening any production modifier. Precedent:
 * {@code org.cote.accountmanager.olio.llm.TestChatMemoryPipelineWiring}.
 * {@code PbOlioContextUtil.assembleBookContext} is package-private in the <i>sub</i>-package
 * {@code ...olio.picturebook}, which this package cannot see either (Java package-private is
 * per-package, not per-package-tree), so case 8 reaches it through the test-only shim
 * {@code org.cote.accountmanager.olio.picturebook.BookContextTestAccess}. The production method is
 * NOT widened.
 * <p>
 * <b>Environment.</b> Live PostgreSQL {@code am7db} on {@code localhost:15430} (BaseTest's
 * {@code test.db.url}). No schema reset - ever. Two pre-existing sub-organizations are used because
 * both already carry a fully loaded {@code /Library} corpus, which keeps
 * {@code WorldUtil.loadWorldData} on its count-short-circuit path instead of re-parsing WordNet.
 * The acting user is the shared Olio test user {@code testUser1}; the organization admin is used only
 * where the product itself uses it (inside {@code getCreateBookContext}) and never as the subject
 * under test.
 * <p>
 * <b>Both phase-1 blockers are fixed.</b> They were the create path throwing
 * ({@link #case00_createPathMustCompleteWithoutThrowing()}) and a grant not being visible to the
 * process that wrote it ({@link #case12_aGrantMustBeVisibleToTheProcessThatWroteIt()}, fixed in
 * {@code MemberUtil}). Both cases remain as regression pins.
 * <p>
 * {@link #openBook(BaseRecord, String)} is retained as the shared entry point for the cases that are
 * not themselves testing the create wrapper. Its recovery branch - re-read the context the product
 * already published to the shared cache when the post-checks threw - is now UNREACHABLE on a green
 * run, and it rethrows when the cache is empty, so it can never convert a genuine build failure into
 * a pass. It is deliberately left in place: it costs nothing, it logs at ERROR when it fires, and if
 * a future change re-breaks the create path it turns one catastrophic cascade into one failing case
 * plus a loud log line.
 */
public class TestBookWorld extends BaseTest {

	/** Primary org. Pre-existing, corpus-loaded (see class javadoc). */
	private static final String ORG_A = "/Development/World Building";
	/** Second org for the cross-tenant cache-key case. Also pre-existing and corpus-loaded. */
	private static final String ORG_B = "/Development/SyncTest";

	/** The shared Olio test user name. Deliberately the SAME name in both orgs (case 6). */
	private static final String TEST_USER = "testUser1";
	/** An ordinary second user - never granted the book Admin role except where a case does it. */
	private static final String OTHER_USER = "bookTestUser2";
	/** Enrolment target for the registerUser case. */
	private static final String TARGET_USER = "bookTestUser3";

	private static final String SLUG_ALPHA = "pb1-alpha";
	private static final String SLUG_BRAVO = "pb1-bravo";
	private static final String SLUG_XORG = "pb1-xorg";
	private static final String SLUG_TIMING = "pb1-timing";

	/**
	 * The universe case 18 builds its grid world in. This is the SAME universe the rest of the Olio
	 * harness uses ({@code OlioTestUtil.universeName}), and it is reused on purpose - see
	 * {@link #case18_theProductionGridPathGeneratesAPopulationWithBulkApprovalOff()}.
	 */
	private static final String GRID_UNIVERSE = "Olio Universe";

	@Before
	public void bookSetup() {
		/// JUnit4 runs the superclass @Before first, so IO is already open here.
		///
		/// The cached OlioContext map is static and survives BaseTest's per-test IOSystem open/close
		/// cycle. Drop it so every case starts from a known cache state.
		OlioContextUtil.clearCache();

		/// Case 7 caveat, stated where it bites: the Olio test harness sets
		/// AccessPoint.setPermitBulkContainerApproval(true) in several places (OlioTestUtil:94 among
		/// them) and mostly never resets it. AccessPoint is per-IOContext and BaseTest re-opens IO per
		/// test, so it should already be false - but assert-after-set is the only order that is not
		/// dependent on what ran before us in a shared JVM.
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private BaseRecord user(OrganizationContext org, String name) {
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), name, org.getOrganizationId());
		assertNotNull("Failed to resolve test user " + name + " in " + org.getOrganizationId(), u);
		return u;
	}

	/**
	 * Run the ONLY create path.
	 * <p>
	 * {@code getCreateBookContext} builds and initializes the world, publishes the context to the
	 * shared cache via {@code OlioContextUtil.getCachedContext}, and only THEN runs its post-checks -
	 * so when a post-check throws, a fully-built context is already in the cache. The catch below
	 * recovers it with a cache READ (the supplier returns null, so nothing is ever rebuilt here) and
	 * logs at ERROR. Both phase-1 blockers are fixed, so on a green run that branch never executes. If
	 * the cache is empty the original exception is rethrown: this must never turn a genuine build
	 * failure into a pass.
	 *
	 * @throws OlioException when the create path failed AND produced no context
	 */
	private OlioContext openBook(BaseRecord u, String slug) throws OlioException {
		try {
			return PbOlioContextUtil.getCreateBookContext(u, dataPath(), slug);
		}
		catch (OlioException e) {
			OlioContext cached = OlioContextUtil.getCachedContext(u, PbOlioContextUtil.BOOKS_UNIVERSE, slug, () -> null);
			if (cached == null) {
				throw e;
			}
			logger.error("UNEXPECTED failure of getCreateBookContext(" + slug + "): " + e.getMessage()
				+ " -- the world WAS built and cached; continuing with the published context so this case can still"
				+ " exercise real behaviour. Both phase-1 blockers are fixed, so this branch firing is itself a"
				+ " regression: see case00 / case12.");
			return cached;
		}
	}

	private int countInGroup(String model, BaseRecord group, long orgId) {
		assertNotNull("Group is null for " + model, group);
		return IOSystem.getActiveContext().getSearch().count(
			QueryUtil.getGroupQuery(model, null, (long) group.get(FieldNames.FIELD_ID), orgId));
	}

	private int countInOrg(String model, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	private BaseRecord group(BaseRecord world, String fieldName) {
		BaseRecord g = world.get(fieldName);
		assertNotNull("World group '" + fieldName + "' is null", g);
		IOSystem.getActiveContext().getReader().populate(g);
		return g;
	}

	private String containerPath(OlioContext ctx) {
		return ctx.getConfig().getWorldPath() + "/" + (String) ctx.getWorld().get(FieldNames.FIELD_NAME);
	}

	private int countContainerChildren(OlioContext ctx) {
		long orgId = ctx.getOlioUser().get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord container = IOSystem.getActiveContext().getPathUtil().findPath(ctx.getOlioUser(),
			ModelNames.MODEL_GROUP, containerPath(ctx), GroupEnumType.DATA.toString(), orgId);
		assertNotNull("World container group not found: " + containerPath(ctx), container);
		return IOSystem.getActiveContext().getSearch().count(QueryUtil.createQuery(ModelNames.MODEL_GROUP,
			FieldNames.FIELD_PARENT_ID, container.get(FieldNames.FIELD_ID), orgId));
	}

	private BaseRecord bookRole(OlioContext ctx, String rolePath) {
		return IOSystem.getActiveContext().getPathUtil().findPath(ctx.getOlioUser(), ModelNames.MODEL_ROLE,
			rolePath, RoleEnumType.USER.toString(), (long) ctx.getOlioUser().get(FieldNames.FIELD_ORGANIZATION_ID));
	}

	private BaseRecord readPermission(OrganizationContext org) {
		BaseRecord p = IOSystem.getActiveContext().getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_PERMISSION,
			"/Read", PermissionEnumType.DATA.toString(), org.getOrganizationId());
		assertNotNull("Read permission not resolved", p);
		return p;
	}

	/**
	 * A brand-new organization under {@code /Development}, named with a random suffix.
	 * <p>
	 * Cases 16 and 17 need an organization in which NO Olio context has ever been constructed, because
	 * the branch they test ({@code OlioContext.configureEnvironment}'s first-run bootstrap) runs once
	 * per organization and is unreachable in the pre-existing test organizations. The suffix is random
	 * so a re-run cannot silently land on an organization a previous run already bootstrapped - which
	 * would turn both cases green for the wrong reason.
	 */
	private String freshOrgPath() {
		return "/Development/PB2H2-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** Find-only group lookup as the organization admin (fixture-level read, never a subject under test). */
	private BaseRecord groupAt(OrganizationContext org, String path) {
		return IOSystem.getActiveContext().getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_GROUP,
			path, GroupEnumType.DATA.toString(), org.getOrganizationId());
	}

	/** Find-only lookup of an ORG-WIDE {@code ~/Roles/Olio *} role, resolved exactly as production does. */
	private BaseRecord orgOlioRole(OrganizationContext org, BaseRecord olioUser, String rolePath) {
		return IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			rolePath, RoleEnumType.USER.toString(), org.getOrganizationId());
	}

	private boolean readGrant(BaseRecord role, BaseRecord readPerm, BaseRecord group) {
		assertNotNull("Group is null for the entitlement probe", group);
		return IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(role, readPerm, group);
	}

	/**
	 * {@link #readGrant(BaseRecord, BaseRecord, BaseRecord)} with the built-in PBAC/authorization trace
	 * on for the duration of the single {@code checkEntitlement} call, and off again immediately.
	 * <p>
	 * Why it is here rather than left to a bare boolean: a NEGATIVE entitlement result is only evidence
	 * if the check actually ran and resolved the role and group this case believes it did. An empty
	 * participation set from a mis-resolved role produces exactly the same {@code false}. The trace
	 * ({@code PolicyUtil.setTrace}, which toggles {@code PolicyEvaluator}, {@code AuthorizationUtil} and
	 * {@code PolicyUtil} together) prints the reasoning, so a false green is visible in the log.
	 * <p>
	 * <b>Bracketed tightly on purpose.</b> It is enabled immediately before the one call and disabled in
	 * a {@code finally}, so it can never leak into the other 17 cases or into the 130-second corpus load.
	 */
	private boolean tracedReadGrant(String label, BaseRecord role, BaseRecord readPerm, BaseRecord group) {
		logger.info("---- PBAC TRACE ON: checkEntitlement(" + label + ") ----");
		IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
		try {
			return readGrant(role, readPerm, group);
		}
		finally {
			IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
			logger.info("---- PBAC TRACE OFF: checkEntitlement(" + label + ") ----");
		}
	}

	/** {@code AccessPoint.findByNameInGroup} with the PBAC trace on for that one read only. */
	private BaseRecord tracedFindByNameInGroup(String label, BaseRecord actor, String model, BaseRecord group, String name) {
		logger.info("---- PBAC TRACE ON: read " + label + " ----");
		IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
		try {
			return IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(actor, model,
				(long) group.get(FieldNames.FIELD_ID), name);
		}
		finally {
			IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
			logger.info("---- PBAC TRACE OFF: read " + label + " ----");
		}
	}

	/**
	 * Assert - and return - the state that proves {@code configureEnvironment}'s FIRST-RUN branch, not
	 * its every-run early return, is what executed in this organization.
	 * <p>
	 * The early return at the top of {@code configureEnvironment} fires when {@code ~/Roles/Olio Admin}
	 * already exists, so the only way to reach the bootstrap is to be the first Olio context in the
	 * organization. Callers assert the ABSENCE of the olio principal beforehand; this asserts the
	 * artefacts the bootstrap alone creates: both org-wide roles, and the olio principal's own
	 * membership of the Admin role (written at the first-run branch only).
	 */
	private BaseRecord[] assertFirstRunBootstrapRan(OrganizationContext org) {
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, org.getOrganizationId());
		assertNotNull("The bootstrap must have created the olio principal", olioUser);
		BaseRecord orgUserRole = orgOlioRole(org, olioUser, "~/Roles/Olio User");
		BaseRecord orgAdminRole = orgOlioRole(org, olioUser, "~/Roles/Olio Admin");
		assertNotNull("The bootstrap must have created ~/Roles/Olio User", orgUserRole);
		assertNotNull("The bootstrap must have created ~/Roles/Olio Admin", orgAdminRole);
		/// member(olioUser, adminRole, olioUser, ...) is written ONLY on the first-run branch.
		assertTrue("The olio principal must be a member of ~/Roles/Olio Admin - that membership is written"
			+ " only by the first-run bootstrap, so its absence would mean the branch under test never ran",
			IOSystem.getActiveContext().getMemberUtil().isMember(olioUser, orgAdminRole, null));
		return new BaseRecord[] {olioUser, orgUserRole, orgAdminRole};
	}

	// ───────────────── Case 0: the create path must work at all ─────────────────

	/**
	 * The single, crisp statement of the first phase-1 blocker (now fixed, kept as a regression pin).
	 * {@code getCreateBookContext} is documented as the ONLY create path; if it throws, no book can be
	 * created or re-opened through the shipped API.
	 * <p>
	 * The re-open legs also cover the B1 authorization split: on every call after the first the world
	 * already exists, so the create path takes its "authorize the caller against the existing book"
	 * branch instead of enrolling. {@code testUser1} created it, hence holds the book Writer role,
	 * hence must still be admitted.
	 */
	@Test
	public void case00_createPathMustCompleteWithoutThrowing() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);

		OlioContext first;
		try {
			first = PbOlioContextUtil.getCreateBookContext(u, dataPath(), SLUG_ALPHA);
		}
		catch (OlioException e) {
			fail("getCreateBookContext threw on the first open of '" + SLUG_ALPHA + "': " + e.getMessage());
			return;
		}
		assertNotNull(first);

		/// Re-opening an existing book is the common case, not an edge case.
		try {
			OlioContext again = PbOlioContextUtil.getCreateBookContext(u, dataPath(), SLUG_ALPHA);
			assertNotNull(again);
		}
		catch (OlioException e) {
			fail("getCreateBookContext threw when RE-OPENING '" + SLUG_ALPHA + "': " + e.getMessage());
		}

		/// ...and after a cache eviction, which forces initialize() to run again.
		PbOlioContextUtil.evictBookContext(u, SLUG_ALPHA);
		try {
			OlioContext rebuilt = PbOlioContextUtil.getCreateBookContext(u, dataPath(), SLUG_ALPHA);
			assertNotNull(rebuilt);
		}
		catch (OlioException e) {
			fail("getCreateBookContext threw on a cache-cold rebuild of '" + SLUG_ALPHA + "': " + e.getMessage());
		}
	}

	// ───────────────── Case 1: universe corpora load, locations do not ─────────────────

	@Test
	public void case01_universeCorporaLoadButLocationsDoNot() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext ctx = openBook(u, SLUG_ALPHA);
		assertNotNull("Book context is null", ctx);

		BaseRecord universe = ctx.getUniverse();
		assertNotNull("Books universe is null", universe);
		assertEquals("Universe must be the single per-org Books universe",
			PbOlioContextUtil.BOOKS_UNIVERSE, (String) universe.get(FieldNames.FIELD_NAME));

		/// Independently re-resolve it from the DB, not just off the context instance.
		BaseRecord found = WorldUtil.findWorld(ctx.getOlioUser(), ctx.getConfig().getUniversePath(), PbOlioContextUtil.BOOKS_UNIVERSE);
		assertNotNull("Books universe not resolvable by path", found);
		assertEquals("Re-resolved universe must be the same record",
			(String) universe.get(FieldNames.FIELD_OBJECT_ID), (String) found.get(FieldNames.FIELD_OBJECT_ID));

		int locs = countInGroup(ModelNames.MODEL_GEO_LOCATION, group(universe, FieldNames.FIELD_LOCATIONS), orgId);
		int traits = countInGroup(ModelNames.MODEL_TRAIT, group(universe, OlioFieldNames.FIELD_TRAITS), orgId);
		int colors = countInGroup(ModelNames.MODEL_COLOR, group(universe, OlioFieldNames.FIELD_COLORS), orgId);
		int names = countInGroup(ModelNames.MODEL_WORD, group(universe, OlioFieldNames.FIELD_NAMES), orgId);
		logger.info("CASE 1 corpora: locations=" + locs + " traits=" + traits + " colors=" + colors + " names=" + names);

		assertEquals("A book universe must load NO locations", 0, locs);
		/// The ratified-Q2 fastDataCheck fix. A Traits-empty universe is the exact silent failure the
		/// Traits probe prevents: every other corpus is repointed at the shared /Library, so a fresh
		/// universe in a library-bearing org would probe as "loaded" and never reach loadTraits.
		assertTrue("Books universe Traits corpus must be loaded (fastDataCheck probe target)", traits > 0);
		assertTrue("Books universe must see the shared Colors corpus", colors > 0);
		assertTrue("Books universe must see the shared Names corpus", names > 0);
	}

	// ───────────────── Case 2: two book worlds coexist and are distinct ─────────────────

	@Test
	public void case02_twoBookWorldsCoexistAndAreDistinct() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);

		OlioContext a = openBook(u, SLUG_ALPHA);
		OlioContext b = openBook(u, SLUG_BRAVO);
		assertNotNull("book-a context is null", a);
		assertNotNull("book-b context is null", b);

		assertNotSame("Two book slugs for one user must not share a context instance", a, b);

		String aId = a.getWorld().get(FieldNames.FIELD_OBJECT_ID);
		String bId = b.getWorld().get(FieldNames.FIELD_OBJECT_ID);
		logger.info("CASE 2 worlds: " + SLUG_ALPHA + "=" + aId + " / " + SLUG_BRAVO + "=" + bId);
		assertNotEquals("Two book slugs must resolve to different olio.world records", aId, bId);
		assertEquals(SLUG_ALPHA, (String) a.getWorld().get(FieldNames.FIELD_NAME));
		assertEquals(SLUG_BRAVO, (String) b.getWorld().get(FieldNames.FIELD_NAME));

		/// Both share the one per-org Books universe.
		assertEquals("Both books must live in the same Books universe",
			(String) a.getUniverse().get(FieldNames.FIELD_OBJECT_ID),
			(String) b.getUniverse().get(FieldNames.FIELD_OBJECT_ID));

		/// And the cache holds two separate keys.
		List<String> keys = OlioContextUtil.getCachedKeys();
		assertTrue("Cache must key " + SLUG_ALPHA + " separately: " + keys,
			keys.contains(org.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_ALPHA));
		assertTrue("Cache must key " + SLUG_BRAVO + " separately: " + keys,
			keys.contains(org.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_BRAVO));
	}

	// ───────────────── Case 3: authorization actually configured + grants present ─────────────────

	@Test
	public void case03_authorizationConfiguredAndEveryGrantPresent() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);

		OlioContext ctx = openBook(u, SLUG_ALPHA);

		/// isInitialized() is deliberately NOT the check: it is set before the grant calls and the
		/// whole of initialize() sits in a swallow-all catch.
		assertTrue("isAuthorizationConfigured() must be true after a successful create", ctx.isAuthorizationConfigured());

		BaseRecord writer = bookRole(ctx, PbOlioContextUtil.writerRolePath(SLUG_ALPHA));
		BaseRecord admin = bookRole(ctx, PbOlioContextUtil.adminRolePath(SLUG_ALPHA));
		assertNotNull("Per-book Writer role must exist: " + PbOlioContextUtil.writerRolePath(SLUG_ALPHA), writer);
		assertNotNull("Per-book Admin role must exist: " + PbOlioContextUtil.adminRolePath(SLUG_ALPHA), admin);

		assertTrue("Creator must be a member of the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(u, writer, null));

		BaseRecord readPerm = readPermission(org);
		List<BaseRecord> groups = ctx.getAuthorizationGroups(ctx.getWorld(), ctx.getConfig().getWorldPath());
		assertTrue("Expected the world authorization to enumerate groups", groups.size() > 0);
		for (BaseRecord g : groups) {
			assertTrue("Missing Read grant for the book Writer role on world group "
				+ g.get(FieldNames.FIELD_NAME) + " (#" + g.get(FieldNames.FIELD_ID) + ")",
				IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(writer, readPerm, g));
		}
		logger.info("CASE 3 verified Read grants on " + groups.size() + " world groups");

		/// The universe tier is addressed to the UNIVERSE role since the phase-2a split: the per-book
		/// Writer role is granted on the book's own world groups and holds nothing on the universe for a
		/// book created after the split. Checking the book role here would assert the very coupling the
		/// split removes, and would pass only on books old enough to still carry the legacy grants
		/// (setEntitlement only adds, so those grants were never revoked). Case 19 asserts the negative
		/// half of this on a book created inside that case.
		BaseRecord universeReader = bookRole(ctx, PbOlioContextUtil.universeReaderRolePath());
		assertNotNull("Universe Reader role must exist: " + PbOlioContextUtil.universeReaderRolePath(), universeReader);
		List<BaseRecord> ugroups = ctx.getAuthorizationGroups(ctx.getUniverse(), ctx.getConfig().getUniversePath());
		assertTrue("Expected the universe authorization to enumerate groups", ugroups.size() > 0);
		for (BaseRecord g : ugroups) {
			assertTrue("Missing Read grant for the universe Reader role on universe group "
				+ g.get(FieldNames.FIELD_NAME) + " (#" + g.get(FieldNames.FIELD_ID) + ")",
				IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(universeReader, readPerm, g));
		}
		logger.info("CASE 3 verified Read grants on " + ugroups.size() + " universe groups");
	}

	// ───────────────── Case 4: idempotency ─────────────────

	@Test
	public void case04_secondCreateIsIdempotent() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext first = openBook(u, SLUG_ALPHA);
		int childrenBefore = countContainerChildren(first);
		int groupsBefore = countInOrg(ModelNames.MODEL_GROUP, orgId);
		int worldsBefore = countInOrg(OlioModelNames.MODEL_WORLD, orgId);
		BaseRecord rootBefore = EventUtil.getRootEvent(first);
		assertNotNull("Root event must exist after create", rootBefore);

		/// Evict so the second call genuinely re-runs initialize() instead of returning the cached
		/// instance - otherwise "idempotent" would only prove the cache works.
		PbOlioContextUtil.evictBookContext(u, SLUG_ALPHA);
		assertFalse("Eviction must remove the cached key",
			OlioContextUtil.getCachedKeys().contains(orgId + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_ALPHA));

		/// Deliberately NOT openBook: re-opening an existing book must not throw, and this is the
		/// natural home for that assertion.
		OlioContext second = PbOlioContextUtil.getCreateBookContext(u, dataPath(), SLUG_ALPHA);
		assertNotNull("Second create returned null", second);
		assertNotSame("Eviction should have forced a rebuild", first, second);

		int childrenAfter = countContainerChildren(second);
		int groupsAfter = countInOrg(ModelNames.MODEL_GROUP, orgId);
		int worldsAfter = countInOrg(OlioModelNames.MODEL_WORLD, orgId);
		BaseRecord rootAfter = EventUtil.getRootEvent(second);

		logger.info("CASE 4 children " + childrenBefore + "->" + childrenAfter
			+ " orgGroups " + groupsBefore + "->" + groupsAfter
			+ " worlds " + worldsBefore + "->" + worldsAfter);

		assertEquals("A repeat getCreateBookContext must not add groups under the world container",
			childrenBefore, childrenAfter);
		assertEquals("A repeat getCreateBookContext must not add groups anywhere in the org",
			groupsBefore, groupsAfter);
		assertEquals("A repeat getCreateBookContext must not add a world", worldsBefore, worldsAfter);
		assertEquals("A repeat getCreateBookContext must reuse the same root event",
			(String) rootBefore.get(FieldNames.FIELD_OBJECT_ID), (String) rootAfter.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("A repeat getCreateBookContext must resolve the same world",
			(String) first.getWorld().get(FieldNames.FIELD_OBJECT_ID),
			(String) second.getWorld().get(FieldNames.FIELD_OBJECT_ID));
	}

	// ───────────────── Case 5: no realms, no locations ─────────────────

	@Test
	public void case05_noRealmsAndNoLocations() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext ctx = openBook(u, SLUG_ALPHA);

		/// getRealms() has a create branch; calling it must still produce nothing for a book world.
		List<BaseRecord> realms = ctx.getRealms();
		int worldLocs = countInGroup(ModelNames.MODEL_GEO_LOCATION, group(ctx.getWorld(), FieldNames.FIELD_LOCATIONS), orgId);
		int uniLocs = countInGroup(ModelNames.MODEL_GEO_LOCATION, group(ctx.getUniverse(), FieldNames.FIELD_LOCATIONS), orgId);
		logger.info("CASE 5 realms=" + realms.size() + " worldLocations=" + worldLocs + " universeLocations=" + uniLocs);

		assertEquals("A book world must have no realms", 0, realms.size());
		assertEquals("getRealms()'s create branch must not create locations in the world", 0, worldLocs);
		assertEquals("getRealms()'s create branch must not create locations in the universe", 0, uniLocs);
		assertEquals("A book world must have no locations list", 0, ctx.getLocations().size());
	}

	// ───────────────── Case 6: cross-organization isolation ─────────────────

	@Test
	public void case06_crossOrganizationIsolationForTheSameUserName() throws Exception {
		OrganizationContext orgA = getTestOrganization(ORG_A);
		OrganizationContext orgB = getTestOrganization(ORG_B);
		assertNotEquals("The two test organizations must be distinct",
			orgA.getOrganizationId(), orgB.getOrganizationId());

		BaseRecord ua = user(orgA, TEST_USER);
		BaseRecord ub = user(orgB, TEST_USER);
		assertEquals("Both users must carry the same NAME (that is the point of the case)",
			(String) ua.get(FieldNames.FIELD_NAME), (String) ub.get(FieldNames.FIELD_NAME));
		assertNotEquals("Same name, different organizations",
			(long) ua.get(FieldNames.FIELD_ORGANIZATION_ID), (long) ub.get(FieldNames.FIELD_ORGANIZATION_ID));

		OlioContext ca = openBook(ua, SLUG_XORG);
		OlioContext cb = openBook(ub, SLUG_XORG);

		assertNotSame("Same user name + same slug in two orgs must not share a cached context", ca, cb);
		assertNotEquals("Two organizations must own two distinct book worlds",
			(String) ca.getWorld().get(FieldNames.FIELD_OBJECT_ID),
			(String) cb.getWorld().get(FieldNames.FIELD_OBJECT_ID));
		assertNotEquals("Two organizations must own two distinct Books universes",
			(String) ca.getUniverse().get(FieldNames.FIELD_OBJECT_ID),
			(String) cb.getUniverse().get(FieldNames.FIELD_OBJECT_ID));

		assertEquals("Org A context must carry org A's olio principal",
			orgA.getOrganizationId(), (long) ca.getOlioUser().get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals("Org B context must carry org B's olio principal",
			orgB.getOrganizationId(), (long) cb.getOlioUser().get(FieldNames.FIELD_ORGANIZATION_ID));
		assertEquals("Org B world must belong to org B",
			orgB.getOrganizationId(), (long) cb.getWorld().get(FieldNames.FIELD_ORGANIZATION_ID));

		List<String> keys = OlioContextUtil.getCachedKeys();
		logger.info("CASE 6 cache keys: " + keys);
		assertTrue("Org A key missing", keys.contains(orgA.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_XORG));
		assertTrue("Org B key missing", keys.contains(orgB.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_XORG));
	}

	// ───────────────── Case 7: bulk container approval stays false ─────────────────

	@Test
	public void case07_bulkContainerApprovalStaysFalse() throws Exception {
		/// CAVEAT: this assertion is order-dependent in a shared JVM. The Olio test harness sets this
		/// flag true (OlioTestUtil:94 and friends) and mostly never resets it. bookSetup() therefore
		/// sets it false FIRST; what is under test is that the BOOK path does not turn it back on -
		/// the four setPermitBulkContainerApproval calls that used to live in OlioContextUtil are gone.
		assertFalse("Precondition: flag forced false in setup",
			IOSystem.getActiveContext().getAccessPoint().isPermitBulkContainerApproval());

		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		openBook(u, SLUG_ALPHA);

		assertFalse("Creating a book world must not enable bulk container approval",
			IOSystem.getActiveContext().getAccessPoint().isPermitBulkContainerApproval());

		/// And a cache-cold rebuild must not either.
		PbOlioContextUtil.evictBookContext(u, SLUG_ALPHA);
		openBook(u, SLUG_ALPHA);
		assertFalse("Rebuilding a book world must not enable bulk container approval",
			IOSystem.getActiveContext().getAccessPoint().isPermitBulkContainerApproval());
	}

	// ───────────────── Case 8: the read path creates nothing ─────────────────

	@Test
	public void case08_assembleBookContextAndFindWorldCreateNothing() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext ctx = openBook(u, SLUG_ALPHA);
		BaseRecord world = ctx.getWorld();

		int groupsBefore = countInOrg(ModelNames.MODEL_GROUP, orgId);
		int usersBefore = countInOrg(ModelNames.MODEL_USER, orgId);
		int worldsBefore = countInOrg(OlioModelNames.MODEL_WORLD, orgId);

		BookContext bc = BookContextTestAccess.assemble(world);
		assertNotNull("assembleBookContext returned null for an existing world", bc);
		assertEquals("BookContext world must be the same record",
			(String) world.get(FieldNames.FIELD_OBJECT_ID), (String) bc.getWorld().get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("BookContext universe must be the Books universe",
			PbOlioContextUtil.BOOKS_UNIVERSE, (String) bc.getUniverse().get(FieldNames.FIELD_NAME));
		assertEquals("BookContext organizationId", orgId, bc.getOrganizationId());

		/// The three PictureBook groups resolve...
		assertNotNull("BookContext must resolve the Book group", bc.getGroup(BookWorldInitializationRule.GROUP_BOOK));
		assertNotNull("BookContext must resolve the Workflow group", bc.getGroup(BookWorldInitializationRule.GROUP_WORKFLOW));
		assertNotNull("BookContext must resolve the Artifacts group", bc.getGroup(BookWorldInitializationRule.GROUP_ARTIFACTS));
		assertNotNull("BookContext must resolve a world FK group", bc.getGroup(OlioFieldNames.FIELD_GALLERY));
		assertNotNull("BookContext must resolve a group PATH", bc.getGroupPath(OlioFieldNames.FIELD_GALLERY));
		/// ...and an unknown name resolves to null rather than creating anything.
		assertNull("An unknown group name must resolve to null", bc.getGroup("no-such-group-" + UUID.randomUUID()));

		int groupsAfter = countInOrg(ModelNames.MODEL_GROUP, orgId);
		int usersAfter = countInOrg(ModelNames.MODEL_USER, orgId);
		int worldsAfter = countInOrg(OlioModelNames.MODEL_WORLD, orgId);
		logger.info("CASE 8 assemble: groups " + groupsBefore + "->" + groupsAfter
			+ " users " + usersBefore + "->" + usersAfter + " worlds " + worldsBefore + "->" + worldsAfter);
		assertEquals("assembleBookContext must not create groups", groupsBefore, groupsAfter);
		assertEquals("assembleBookContext must not create users", usersBefore, usersAfter);
		assertEquals("assembleBookContext must not create worlds", worldsBefore, worldsAfter);

		/// Find-only path for a world that does not exist: null, and no group created for its path.
		String ghost = "pb1-ghost-" + UUID.randomUUID().toString().substring(0, 8);
		String worldPath = ctx.getConfig().getWorldPath();
		assertNull("findWorld must return null for a non-existent world",
			WorldUtil.findWorld(ctx.getOlioUser(), worldPath, ghost));
		assertNull("findWorld must return null when the group path itself is absent",
			WorldUtil.findWorld(ctx.getOlioUser(), worldPath + "/" + ghost, ghost));
		assertNull("findWorld must not have created the missing group path",
			IOSystem.getActiveContext().getPathUtil().findPath(ctx.getOlioUser(), ModelNames.MODEL_GROUP,
				worldPath + "/" + ghost, GroupEnumType.DATA.toString(), orgId));

		int groupsFinal = countInOrg(ModelNames.MODEL_GROUP, orgId);
		logger.info("CASE 8 findWorld: groups " + groupsAfter + "->" + groupsFinal);
		assertEquals("findWorld must not create groups", groupsAfter, groupsFinal);

		/// assembleBookContext for a null world is a null, not an exception.
		assertNull("assembleBookContext(null) must return null", BookContextTestAccess.assemble(null));
	}

	// ───────────────── Case 9: does a container grant reach a sub-subgroup? ─────────────────

	/**
	 * Informational-but-asserted probe of an OPEN PHASE 2 DESIGN QUESTION: group entitlements are
	 * joined on an exact {@code groupId} ({@code effectiveGroupObjectEntitlementTemplate.sql:10}), so
	 * the design expects a grant on the world's own groups NOT to reach a group nested one level
	 * deeper (e.g. the {@code Gallery/Characters} directory {@code SDUtil} creates).
	 * <p>
	 * The test is built so a negative result is evidence: the CONTROL reads a record in the granted
	 * {@code Gallery} group itself as the same user. If the control cannot be read either, the setup
	 * is broken and the test fails rather than reporting a false "no recursion".
	 * <p>
	 * <b>The sub-subgroup name carries a fresh random suffix, and that is load-bearing since phase 2a.</b>
	 * This case measures a PLATFORM property - that the entitlement join is an exact {@code groupId} match -
	 * so the group it probes has to be one that no grant pass could have covered. Phase 2a makes the book
	 * path grant recursively at {@code initialize()} ({@code scanNestedWorldGroups}), and {@code SLUG_ALPHA}
	 * is a fixed slug on a live database, so a literal {@code Gallery/Characters} would survive between runs
	 * and be granted by the recursive pass on the NEXT run - turning this case red for a reason that has
	 * nothing to do with the property it exists to pin. A group created after the scan cannot be.
	 * Case 20 asserts the recursion fix itself.
	 */
	@Test
	public void case09_grantOnWorldGroupsDoesNotRecurseIntoSubSubgroups() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext ctx = openBook(u, SLUG_ALPHA);
		BaseRecord olioUser = ctx.getOlioUser();

		BaseRecord gallery = group(ctx.getWorld(), OlioFieldNames.FIELD_GALLERY);
		String galleryPath = gallery.get(FieldNames.FIELD_PATH);
		assertNotNull("Gallery path is null", galleryPath);

		String tag = UUID.randomUUID().toString().substring(0, 8);

		/// Sub-subgroup: a child of a world group, i.e. a GRANDCHILD of the world container. Same shape
		/// SDUtil creates ({world.gallery.path}/Characters/{name}), created AFTER this context's
		/// recursive grant pass ran - see the javadoc.
		String nestedGroupName = "Characters-case09-" + tag;
		BaseRecord characters = IOSystem.getActiveContext().getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP,
			galleryPath + "/" + nestedGroupName, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Failed to create the " + nestedGroupName + " subgroup", characters);

		String controlName = "case09-control-" + tag;
		String nestedName = "case09-nested-" + tag;

		/// Both records are OWNED BY THE OLIO USER and created without PBAC, so the only way testUser1
		/// can read them is through the book Writer role's group entitlement.
		BaseRecord control = newData(olioUser, controlName, "text/plain", "control".getBytes(), galleryPath, orgId);
		assertTrue("Failed to create the control record", IOSystem.getActiveContext().getRecordUtil().createRecord(control));
		BaseRecord nested = newData(olioUser, nestedName, "text/plain", "nested".getBytes(), galleryPath + "/" + nestedGroupName, orgId);
		assertTrue("Failed to create the nested record", IOSystem.getActiveContext().getRecordUtil().createRecord(nested));

		/// The grants were written earlier in this same process and the membership query for those
		/// groups is cached (see case12). Clear the cache so this case measures ENTITLEMENT RECURSION
		/// and not the read-after-write staleness case12 already pins.
		CacheUtil.clearCache();

		BaseRecord writer = bookRole(ctx, PbOlioContextUtil.writerRolePath(SLUG_ALPHA));
		BaseRecord readPerm = readPermission(org);
		boolean directGrantOnGallery = IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(writer, readPerm, gallery);
		boolean directGrantOnCharacters = IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(writer, readPerm, characters);

		BaseRecord readControl = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(u,
			ModelNames.MODEL_DATA, (long) gallery.get(FieldNames.FIELD_ID), controlName);
		BaseRecord readNested = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(u,
			ModelNames.MODEL_DATA, (long) characters.get(FieldNames.FIELD_ID), nestedName);

		logger.info("CASE 9 RESULT — entitlement on Gallery=" + directGrantOnGallery
			+ ", entitlement on Gallery/" + nestedGroupName + "=" + directGrantOnCharacters
			+ ", PBAC read of Gallery record=" + (readControl != null)
			+ ", PBAC read of Gallery/Characters record=" + (readNested != null));

		/// CONTROL — if this fails the probe below proves nothing.
		assertTrue("CONTROL: the book Writer role must hold a Read entitlement on the world's Gallery group",
			directGrantOnGallery);
		assertNotNull("CONTROL: the creator must be able to read a record in the granted Gallery group "
			+ "(if this fails, the negative result below is not evidence)", readControl);

		/// PROBE — the design's expectation.
		assertFalse("Entitlements are joined on an exact groupId and must NOT recurse into a sub-subgroup",
			directGrantOnCharacters);
		assertNull("A grant on the world's own groups must NOT reach a record in Gallery/" + nestedGroupName,
			readNested);
	}

	// ───────────────── Case 10: registerUser authorizes the actor ─────────────────

	@Test
	public void case10_registerUserAuthorizesTheActor() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		BaseRecord actor = user(org, OTHER_USER);
		BaseRecord target = user(org, TARGET_USER);

		OlioContext ctx = openBook(u, SLUG_ALPHA);
		BaseRecord writer = bookRole(ctx, PbOlioContextUtil.writerRolePath(SLUG_ALPHA));
		BaseRecord admin = bookRole(ctx, PbOlioContextUtil.adminRolePath(SLUG_ALPHA));
		assertNotNull(writer);
		assertNotNull(admin);

		/// Make the negative case reproducible across runs: strip any membership a previous run left.
		IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), admin, actor, null, false);
		IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), writer, target, null, false);
		assertFalse("Precondition: actor must NOT be in the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().isMember(actor, admin, null));
		assertFalse("Precondition: target must NOT already be in the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(target, writer, null));

		/// NEGATIVE FIRST — an ordinary user must not be able to enrol anybody.
		try {
			boolean r = ctx.registerUser(actor, target, false);
			fail("registerUser must reject an unauthorized actor; it returned " + r);
		}
		catch (OlioException e) {
			logger.info("CASE 10 negative: " + e.getMessage());
		}
		assertFalse("A denied registerUser must not have written a membership",
			IOSystem.getActiveContext().getMemberUtil().isMember(target, writer, null));

		/// Authorize the actor by making it a member of the book ADMIN role. This is setup, performed
		/// as the olio principal that owns the role - not the operation under test, and NOT the admin
		/// user acting as the subject.
		assertTrue("Setup: failed to add the actor to the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), admin, actor, null, true));
		assertTrue("Setup: actor should now be in the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().isMember(actor, admin, null));

		/// POSITIVE — an authorized, NON-admin actor succeeds and the target really becomes a member.
		assertTrue("registerUser must succeed for an actor in the book Admin role",
			ctx.registerUser(actor, target, false));
		assertTrue("The target must now be a member of the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(target, writer, null));

		/// Clean up so the next run's negative case starts clean regardless of ordering.
		IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), admin, actor, null, false);
		IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), writer, target, null, false);
	}

	// ───────────────── Case 11: book-creation timing ─────────────────

	@Test
	public void case11_bookCreationTimingCeiling() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);

		String worldPath = "/Olio/Universes/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/Worlds";
		boolean preExisting = (WorldUtil.findWorld(u, worldPath, SLUG_TIMING) != null);

		long t0 = System.currentTimeMillis();
		OlioContext cold = openBook(u, SLUG_TIMING);
		long coldMs = System.currentTimeMillis() - t0;
		assertNotNull("Cold create returned null", cold);

		/// Pure cache read - no builder, so nothing can be rebuilt behind this measurement.
		long t1 = System.currentTimeMillis();
		OlioContext warm = OlioContextUtil.getCachedContext(u, PbOlioContextUtil.BOOKS_UNIVERSE, SLUG_TIMING, () -> null);
		long warmMs = System.currentTimeMillis() - t1;
		assertTrue("A cache hit must return the same instance", cold == warm);

		/// Cache-cold-but-DB-warm: forces initialize() again against existing records.
		PbOlioContextUtil.evictBookContext(u, SLUG_TIMING);
		long t2 = System.currentTimeMillis();
		OlioContext rebuilt = openBook(u, SLUG_TIMING);
		long rebuildMs = System.currentTimeMillis() - t2;
		assertNotSame("A rebuild after eviction must be a new instance", cold, rebuilt);

		logger.info("CASE 11 TIMING — world " + (preExisting ? "ALREADY EXISTED (DB-warm create)" : "was NEW (true cold create)")
			+ ": create=" + coldMs + "ms, cache-hit=" + warmMs + "ms, cache-cold rebuild=" + rebuildMs + "ms");

		/// Generous ceilings: these catch an order-of-magnitude regression, not a benchmark drift.
		assertTrue("Book creation took " + coldMs + "ms (ceiling 120000ms)", coldMs < 120000L);
		assertTrue("Book rebuild took " + rebuildMs + "ms (ceiling 120000ms)", rebuildMs < 120000L);
		assertTrue("A cache hit took " + warmMs + "ms; it should be effectively free", warmMs < 1000L);
	}

	// ───────────────── Case 12: a grant must be visible to the writer ─────────────────

	/**
	 * Minimal repro for the second phase-1 blocker, isolated from the book code entirely.
	 * <p>
	 * {@code PbOlioContextUtil.verifyGrants} reads back the grants {@code configureWorldAuthorization}
	 * has just written, in the same process. That read-after-write returns a STALE membership list,
	 * so the verification reports a missing grant that is in fact present in the database. This case
	 * pins the invariant at the layer where it is violated: a grant written through
	 * {@code AuthorizationUtil.setEntitlement} must be visible to the very process that wrote it,
	 * without an explicit {@code CacheUtil.clearCache()}.
	 */
	@Test
	public void case12_aGrantMustBeVisibleToTheProcessThatWroteIt() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext ctx = openBook(u, SLUG_ALPHA);
		BaseRecord olioUser = ctx.getOlioUser();
		BaseRecord readPerm = readPermission(org);
		long permId = readPerm.get(FieldNames.FIELD_ID);

		/// A shared /Library group is the realistic target: it is the first group verifyGrants checks.
		BaseRecord words = group(ctx.getWorld(), OlioFieldNames.FIELD_WORDS);

		/// 1. Warm the membership query, exactly as initialize() does before it grants.
		int before = IOSystem.getActiveContext().getMemberUtil().findMembers(words, null, null, 0, permId).size();

		/// 2. Grant Read to a brand-new role on that same group.
		String rname = "pb1-visibility-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord role = IOSystem.getActiveContext().getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.BOOK_ROLE_BASE + "/" + rname + "/Writer", RoleEnumType.USER.toString(), orgId);
		assertNotNull("Failed to create the probe role", role);
		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(org.getAdminUser(), role,
			new BaseRecord[] {words}, new String[] {"Read"},
			new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});

		/// 3. Read it straight back - this is precisely what verifyGrants does.
		int after = IOSystem.getActiveContext().getMemberUtil().findMembers(words, null, null, 0, permId).size();
		boolean visible = IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(role, readPerm, words);

		/// 4. And once more with the cache dropped, to show the grant really was written.
		CacheUtil.clearCache();
		int afterClear = IOSystem.getActiveContext().getMemberUtil().findMembers(words, null, null, 0, permId).size();
		boolean visibleAfterClear = IOSystem.getActiveContext().getAuthorizationUtil().checkEntitlement(role, readPerm, words);

		logger.info("CASE 12 members before=" + before + " after=" + after + " afterClearCache=" + afterClear
			+ "; checkEntitlement immediately=" + visible + " afterClearCache=" + visibleAfterClear);

		assertTrue("Sanity: the grant must exist once the cache is dropped", visibleAfterClear);
		assertEquals("Sanity: the membership list must grow by one once the cache is dropped", before + 1, afterClear);

		assertEquals("A grant just written must appear in the membership list read back by the same process",
			before + 1, after);
		assertTrue("checkEntitlement must see a grant this process just wrote, without a manual cache clear",
			visible);
	}

	// ───────────────── Case 13: a malformed slug is refused before anything is created ─────────────────

	/**
	 * The slug is interpolated into a role path AND a group container path. {@code ..} is inert here
	 * (a path segment is a literal group name), but {@code /} is not: {@code "pb1-alpha/pb1-inner"}
	 * would nest the inner book's container inside {@code pb1-alpha}'s container, and
	 * {@code OlioContext.resolveGrantTargets} enumerates a container's children by {@code parentId} -
	 * so {@code pb1-alpha}'s roles would get Read/Update/Create/<b>Delete</b> over the inner book.
	 * <p>
	 * The assertion is not only "it throws" but "it throws having created nothing": validation has to
	 * run before the first {@code makePath}, not after.
	 */
	@Test
	public void case13_aMalformedSlugIsRefusedBeforeAnythingIsCreated() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		String nested = SLUG_ALPHA + "/pb1-inner";

		int groupsBefore = countInOrg(ModelNames.MODEL_GROUP, orgId);
		int rolesBefore = countInOrg(ModelNames.MODEL_ROLE, orgId);
		int worldsBefore = countInOrg(OlioModelNames.MODEL_WORLD, orgId);

		/// The create path must refuse it.
		try {
			PbOlioContextUtil.getCreateBookContext(u, dataPath(), nested);
			fail("getCreateBookContext must reject a slug containing a path separator: '" + nested + "'");
		}
		catch (OlioException e) {
			logger.info("CASE 13 getCreateBookContext rejected '" + nested + "': " + e.getMessage());
		}

		/// ...and so must the configuration builder, which is the thing that calls makePath. It is
		/// package-private in ...olio.picturebook (it creates, so it is not a public entry), hence the
		/// same test-only shim case 8 uses for assembleBookContext.
		try {
			BookContextTestAccess.newBookConfiguration(u, dataPath(), nested);
			fail("newBookConfiguration must reject a slug containing a path separator: '" + nested + "'");
		}
		catch (OlioException e) {
			logger.info("CASE 13 newBookConfiguration rejected '" + nested + "': " + e.getMessage());
		}

		/// Nothing may have been created on either attempt.
		int groupsAfter = countInOrg(ModelNames.MODEL_GROUP, orgId);
		int rolesAfter = countInOrg(ModelNames.MODEL_ROLE, orgId);
		int worldsAfter = countInOrg(OlioModelNames.MODEL_WORLD, orgId);
		logger.info("CASE 13 groups " + groupsBefore + "->" + groupsAfter
			+ " roles " + rolesBefore + "->" + rolesAfter + " worlds " + worldsBefore + "->" + worldsAfter);
		assertEquals("A rejected slug must not create groups", groupsBefore, groupsAfter);
		assertEquals("A rejected slug must not create roles", rolesBefore, rolesAfter);
		assertEquals("A rejected slug must not create worlds", worldsBefore, worldsAfter);

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("Precondition: the org must already have an olio principal", olioUser);
		assertNull("No Writer role may exist for a rejected slug",
			IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
				PbOlioContextUtil.writerRolePath(nested), RoleEnumType.USER.toString(), orgId));

		/// A representative set of other malformed slugs, all refused by the shared validator.
		for (String bad : new String[] {"/", "/leading", "trailing/", "..", "../escape", "a/../b",
				"Alpha", "has space", "-leading-hyphen", ".leading-dot", "", "   ", "~root", "a\\b"}) {
			try {
				PbOlioContextUtil.validateBookSlug(bad);
				fail("validateBookSlug must reject '" + bad + "'");
			}
			catch (OlioException e) {
				/// expected
			}
		}

		/// ...and the slugs this suite actually uses must still be accepted.
		for (String good : new String[] {SLUG_ALPHA, SLUG_BRAVO, SLUG_XORG, SLUG_TIMING, "a", "a.b_c-1"}) {
			PbOlioContextUtil.validateBookSlug(good);
		}
	}

	// ───────────────── Case 14: create-or-get must not grant entitlement on an existing book ─────────────────

	/**
	 * {@code getCreateBookContext} reaches the find-or-create {@code WorldUtil.getCreateWorld}. If it
	 * enrolled the caller unconditionally, calling "create" with somebody else's slug would hand you
	 * Writer on their book - and the enrolment's own authorization check could not stop it, because
	 * the actor it uses is the organization admin, who is authorized by definition.
	 * <p>
	 * Three legs, in order: an unentitled caller is REFUSED and gains nothing; an entitled caller
	 * (book Admin only) is ADMITTED and is still NOT enrolled in Writer as a side effect; the original
	 * creator is unaffected. The last leg is what stops a "fix" that simply refuses everybody.
	 * <p>
	 * The fourth leg pins the {@code registerUser} organization scope: a principal from another
	 * organization can never be enrolled in this context's roles, whoever asks.
	 */
	@Test
	public void case14_openingAnExistingBookMustAuthorizeRatherThanEnrol() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		BaseRecord other = user(org, OTHER_USER);

		/// Guarantee the book exists and that the create path is on its "already existed" branch.
		OlioContext ctx = openBook(u, SLUG_ALPHA);
		BaseRecord writer = bookRole(ctx, PbOlioContextUtil.writerRolePath(SLUG_ALPHA));
		BaseRecord admin = bookRole(ctx, PbOlioContextUtil.adminRolePath(SLUG_ALPHA));
		assertNotNull(writer);
		assertNotNull(admin);

		/// Deterministic precondition regardless of what ran before us.
		IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), writer, other, null, false);
		IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), admin, other, null, false);
		PbOlioContextUtil.evictBookContext(other, SLUG_ALPHA);
		assertFalse("Precondition: " + OTHER_USER + " must not be in the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(other, writer, null));
		assertFalse("Precondition: " + OTHER_USER + " must not be in the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().isMember(other, admin, null));

		/// LEG 1 — an unentitled caller must be refused, and must gain nothing by having asked.
		try {
			PbOlioContextUtil.getCreateBookContext(other, dataPath(), SLUG_ALPHA);
			fail("getCreateBookContext must refuse '" + SLUG_ALPHA + "' to " + OTHER_USER
				+ ", who holds neither book role");
		}
		catch (OlioException e) {
			logger.info("CASE 14 refused: " + e.getMessage());
		}
		assertFalse("A refused open must NOT have enrolled the caller in the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(other, writer, null));
		assertFalse("A refused open must NOT have enrolled the caller in the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().isMember(other, admin, null));
		assertFalse("A refused open must not leave a cached context: " + OlioContextUtil.getCachedKeys(),
			OlioContextUtil.getCachedKeys().contains(org.getOrganizationId() + "/" + OTHER_USER + "/"
				+ PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_ALPHA));

		/// LEG 2 — entitled through the book ADMIN role alone: admitted, and still not made a Writer.
		assertTrue("Setup: failed to add " + OTHER_USER + " to the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), admin, other, null, true));
		try {
			OlioContext asAdmin = PbOlioContextUtil.getCreateBookContext(other, dataPath(), SLUG_ALPHA);
			assertNotNull("A member of the book Admin role must be admitted", asAdmin);
			assertFalse("Opening an EXISTING book must not enrol the caller in the Writer role",
				IOSystem.getActiveContext().getMemberUtil().isMember(other, writer, null));
		}
		finally {
			PbOlioContextUtil.evictBookContext(other, SLUG_ALPHA);
			IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), admin, other, null, false);
		}

		/// LEG 3 — the creator, who does hold Writer, is still admitted.
		assertTrue("Precondition: the creator must still hold the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(u, writer, null));
		assertNotNull("The creator must still be able to re-open their own book",
			PbOlioContextUtil.getCreateBookContext(u, dataPath(), SLUG_ALPHA));

		/// LEG 4 — registerUser is organization-scoped.
		OrganizationContext orgB = getTestOrganization(ORG_B);
		BaseRecord foreign = user(orgB, TEST_USER);
		assertNotEquals("Precondition: the target must be in a different organization",
			(long) foreign.get(FieldNames.FIELD_ORGANIZATION_ID), org.getOrganizationId());
		try {
			ctx.registerUser(org.getAdminUser(), foreign, false);
			fail("registerUser must refuse a target from another organization");
		}
		catch (OlioException e) {
			logger.info("CASE 14 cross-org registerUser refused: " + e.getMessage());
			assertTrue("The refusal must name the organization mismatch: " + e.getMessage(),
				e.getMessage().contains("organization"));
		}
		assertFalse("A cross-organization registerUser must not have written a membership",
			IOSystem.getActiveContext().getMemberUtil().isMember(foreign, writer, null));
	}

	// ───────────────── Case 15: context cache eviction is organization-scoped ─────────────────

	/**
	 * The Olio context cache is a process-wide static map holding every tenant's contexts, and
	 * {@code CacheService.evictOlioContext} is reachable by any {@code admin}-role caller. An evict
	 * that matched on the world objectId alone would therefore let an administrator of one
	 * organization drop another organization's contexts.
	 * <p>
	 * Also pins the per-key build-lock lifecycle: a build that returns null must not leave its lock
	 * behind, and an evict must drop both the context and its lock.
	 */
	@Test
	public void case15_evictByWorldIsOrganizationScoped() throws Exception {
		OrganizationContext orgA = getTestOrganization(ORG_A);
		OrganizationContext orgB = getTestOrganization(ORG_B);
		BaseRecord ua = user(orgA, TEST_USER);
		BaseRecord ub = user(orgB, TEST_USER);

		OlioContext ca = openBook(ua, SLUG_XORG);
		OlioContext cb = openBook(ub, SLUG_XORG);
		String worldA = ca.getWorld().get(FieldNames.FIELD_OBJECT_ID);
		String worldB = cb.getWorld().get(FieldNames.FIELD_OBJECT_ID);
		assertNotEquals("Precondition: two distinct worlds", worldA, worldB);

		String keyA = orgA.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_XORG;
		String keyB = orgB.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_XORG;
		assertTrue("Precondition: org A context cached", OlioContextUtil.getCachedKeys().contains(keyA));
		assertTrue("Precondition: org B context cached", OlioContextUtil.getCachedKeys().contains(keyB));

		/// An org A administrator naming org B's world must evict NOTHING.
		int crossTenant = OlioContextUtil.evictByWorld(orgA.getOrganizationId(), worldB);
		logger.info("CASE 15 cross-tenant evict removed " + crossTenant + " entries");
		assertEquals("An org A evict must not reach org B's world", 0, crossTenant);
		assertTrue("Org B's context must survive an org A evict", OlioContextUtil.getCachedKeys().contains(keyB));
		assertTrue("Org A's context must survive too (its world was not named)",
			OlioContextUtil.getCachedKeys().contains(keyA));

		/// The owning organization's evict works, and drops the lock with the context.
		int owned = OlioContextUtil.evictByWorld(orgB.getOrganizationId(), worldB);
		logger.info("CASE 15 owning-tenant evict removed " + owned + " entries");
		assertTrue("The owning organization's evict must remove at least its own entry", owned >= 1);
		assertFalse("Org B's context must be gone", OlioContextUtil.getCachedKeys().contains(keyB));
		assertFalse("Org B's build lock must be gone with it: " + OlioContextUtil.getLockKeys(),
			OlioContextUtil.getLockKeys().contains(keyB));
		assertTrue("Org A's context must be untouched", OlioContextUtil.getCachedKeys().contains(keyA));

		/// A cache-read probe that builds nothing must not leave a lock behind.
		String ghost = "pb1-ghost-" + UUID.randomUUID().toString().substring(0, 8);
		String ghostKey = orgA.getOrganizationId() + "/" + TEST_USER + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/" + ghost;
		assertNull("A null builder must not produce a context",
			OlioContextUtil.getCachedContext(ua, PbOlioContextUtil.BOOKS_UNIVERSE, ghost, () -> null));
		assertFalse("A null build must not leak a build lock: " + OlioContextUtil.getLockKeys(),
			OlioContextUtil.getLockKeys().contains(ghostKey));
		assertFalse("A null build must not cache anything", OlioContextUtil.getCachedKeys().contains(ghostKey));

		/// And the explicit single evict removes context and lock together.
		PbOlioContextUtil.evictBookContext(ua, SLUG_XORG);
		assertFalse("Explicit evict must drop the context", OlioContextUtil.getCachedKeys().contains(keyA));
		assertFalse("Explicit evict must drop the lock: " + OlioContextUtil.getLockKeys(),
			OlioContextUtil.getLockKeys().contains(keyA));
	}

	// ───────── Case 16: first context in a fresh org is a BOOK - no org-wide Read on Books/Worlds ─────────

	/**
	 * The H2 fix, in the only situation that can exhibit it: a <b>brand-new organization whose very
	 * first Olio context is a book</b>.
	 * <p>
	 * {@code OlioContext.configureEnvironment} returns early whenever {@code ~/Roles/Olio Admin}
	 * already exists, so its first-run bootstrap runs exactly once per organization. Before the fix
	 * that bootstrap granted the ORG-WIDE {@code ~/Roles/Olio User} role Read on
	 * {@code {rootDir, uDir, wDir}} unconditionally. For a book context {@code wDir} is
	 * {@code /Olio/Universes/Books/Worlds} - the single group holding EVERY book's {@code olio.world}
	 * record - so any principal in the org-wide Olio User role could read every book's slug and group
	 * FKs. The fix withholds {@code wDir} when the context carries its own role pair
	 * ({@code config.getAuthorizationUserRole()}/{@code ...AdminRole()} non-null, i.e. a compartment).
	 * <p>
	 * <b>Why a fresh organization.</b> In {@code ORG_A}/{@code ORG_B} the bootstrap ran years of test
	 * runs ago; the branch is dead there and an assertion about it passes with OR without the fix,
	 * proving nothing. The organization is created here with a random suffix precisely so the branch
	 * is guaranteed to execute. {@link #assertFirstRunBootstrapRan(OrganizationContext)} then proves it
	 * did, and the preconditions below prove it had not already.
	 * <p>
	 * <b>Cost, stated openly.</b> A fresh organization has no {@code /Library}, so
	 * {@code WorldUtil.loadWorldData} performs the full corpus load (WordNet, names, surnames,
	 * occupations, colours, patterns, traits). That is minutes, not seconds, and it is the price of
	 * testing a once-per-organization branch honestly. Nothing here touches an LLM, an embedding
	 * server or Stable Diffusion.
	 * <p>
	 * <b>The entry points stay open on purpose</b> ({@code /Olio} and {@code /Olio/Universes}, design
	 * §5.6b): they are the deliberate org-wide root reference and are asserted PRESENT, not denied.
	 */
	@Test
	public void case16_freshOrgWhoseFirstContextIsABookMustNotGrantTheOrgWideRoleOnBooksWorlds() throws Exception {
		long t0 = System.currentTimeMillis();
		String orgPath = freshOrgPath();
		OrganizationContext fresh = getTestOrganization(orgPath);
		assertTrue("Failed to create the fresh organization " + orgPath, fresh.isInitialized());
		long orgId = fresh.getOrganizationId();
		assertNotEquals("The fresh organization must not be " + ORG_A,
			getTestOrganization(ORG_A).getOrganizationId(), orgId);
		logger.info("CASE 16 fresh organization " + orgPath + " (#" + orgId + ")");

		/// PRECONDITIONS - the first-run bootstrap has NOT run here. No olio principal means no
		/// ~/Roles/Olio Admin can exist, which is exactly the condition configureEnvironment tests.
		assertNull("Precondition: a brand-new organization must have no olio principal",
			ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId));
		assertNull("Precondition: a brand-new organization must have no /Olio group", groupAt(fresh, "/Olio"));
		assertNull("Precondition: a brand-new organization must have no /Olio/Universes group",
			groupAt(fresh, "/Olio/Universes"));
		assertNull("Precondition: a brand-new organization must have no Books world container",
			groupAt(fresh, "/Olio/Universes/Books/Worlds"));

		BaseRecord u = user(fresh, TEST_USER);
		String slug = "pb2h2-book";

		/// THE FIRST Olio context in this organization is a BOOK. Any throw fails the case.
		long tCreate = System.currentTimeMillis();
		OlioContext ctx = PbOlioContextUtil.getCreateBookContext(u, dataPath(), slug);
		long createMs = System.currentTimeMillis() - tCreate;
		assertNotNull("getCreateBookContext returned null in a fresh organization", ctx);
		assertTrue("The book context must have completed world authorization", ctx.isAuthorizationConfigured());
		logger.info("CASE 16 fresh-organization book create took " + createMs + "ms (includes the full corpus load)");

		BaseRecord[] boot = assertFirstRunBootstrapRan(fresh);
		BaseRecord olioUser = boot[0];
		BaseRecord orgUserRole = boot[1];
		BaseRecord orgAdminRole = boot[2];

		String worldsPath = ctx.getConfig().getWorldPath();
		assertEquals("A book context's wDir must be the shared Books world container",
			"/Olio/Universes/Books/Worlds", worldsPath);

		BaseRecord rootDir = groupAt(fresh, "/Olio");
		BaseRecord uDir = groupAt(fresh, "/Olio/Universes");
		BaseRecord wDir = groupAt(fresh, worldsPath);
		assertNotNull("The bootstrap must have created /Olio", rootDir);
		assertNotNull("The bootstrap must have created /Olio/Universes", uDir);
		assertNotNull("The book create must have created " + worldsPath, wDir);

		/// Measure the DATABASE, not this process's membership cache.
		CacheUtil.clearCache();
		BaseRecord readPerm = readPermission(fresh);

		boolean userOnRoot = readGrant(orgUserRole, readPerm, rootDir);
		boolean adminOnRoot = readGrant(orgAdminRole, readPerm, rootDir);
		/// The two decisive probes run under the PBAC trace: the positive control first, so the log shows
		/// the SAME role resolving to a real grant, then the negative the fix is about.
		boolean userOnUniverses = tracedReadGrant("org-wide Olio User / Read / /Olio/Universes (CONTROL, must be true)",
			orgUserRole, readPerm, uDir);
		boolean userOnWorlds = tracedReadGrant("org-wide Olio User / Read / " + worldsPath + " (THE FIX, must be false)",
			orgUserRole, readPerm, wDir);
		boolean adminOnWorlds = readGrant(orgAdminRole, readPerm, wDir);
		logger.info("CASE 16 org-wide entitlements — Olio User: /Olio=" + userOnRoot
			+ " /Olio/Universes=" + userOnUniverses + " " + worldsPath + "=" + userOnWorlds
			+ " | Olio Admin: /Olio=" + adminOnRoot + " " + worldsPath + "=" + adminOnWorlds);

		/// §5.6b - the deliberate org-wide root reference. NOT a denial.
		assertTrue("The org-wide Olio User role must keep Read on the /Olio entry point", userOnRoot);
		assertTrue("The org-wide Olio User role must keep Read on the /Olio/Universes entry point", userOnUniverses);
		assertTrue("The org-wide Olio Admin role must keep Read on the /Olio entry point", adminOnRoot);

		/// THE FIX.
		assertFalse("The org-wide ~/Roles/Olio User role must NOT hold Read on " + worldsPath
			+ " - that group holds every book's olio.world record, and any authenticated user can"
			+ " self-enrol into the org-wide role", userOnWorlds);
		assertFalse("The org-wide ~/Roles/Olio Admin role must NOT hold Read on " + worldsPath
			+ " either - the bootstrap grants both org-wide roles the same entry-point set", adminOnWorlds);

		/// END-TO-END: a bystander holding ONLY the org-wide Olio User role.
		BaseRecord bystander = user(fresh, OTHER_USER);
		assertTrue("Setup: failed to enrol the bystander in the org-wide Olio User role",
			IOSystem.getActiveContext().getMemberUtil().member(olioUser, orgUserRole, bystander, null, true));
		CacheUtil.clearCache();

		BaseRecord leakedWorld = tracedFindByNameInGroup("bystander -> olio.world '" + slug + "' in " + worldsPath
			+ " (must be denied)", bystander, OlioModelNames.MODEL_WORLD, wDir, slug);
		BaseRecord rootReference = tracedFindByNameInGroup("bystander -> olio.world '" + PbOlioContextUtil.BOOKS_UNIVERSE
			+ "' in /Olio/Universes (CONTROL, must be permitted)", bystander, OlioModelNames.MODEL_WORLD, uDir,
			PbOlioContextUtil.BOOKS_UNIVERSE);
		logger.info("CASE 16 PBAC as a bystander in the org-wide Olio User role — read of the book world '"
			+ slug + "' = " + (leakedWorld != null) + ", read of the " + PbOlioContextUtil.BOOKS_UNIVERSE
			+ " universe record in /Olio/Universes = " + (rootReference != null));

		assertNull("A bystander holding only the org-wide Olio User role must NOT be able to read the book's"
			+ " olio.world record out of " + worldsPath, leakedWorld);
		/// CONTROL for the leg above: the same bystander CAN read the universe record in the granted
		/// /Olio/Universes entry point. If this were null the negative result would not be evidence.
		assertNotNull("CONTROL (§5.6b): the org-wide role's Read on /Olio/Universes must still admit a read of"
			+ " the " + PbOlioContextUtil.BOOKS_UNIVERSE + " universe record", rootReference);

		/// The book itself works: the creator can read inside the book's OWN groups; the bystander cannot.
		String bookGroupPath = worldsPath + "/" + slug + "/" + BookWorldInitializationRule.GROUP_BOOK;
		BaseRecord bookGroup = IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP,
			bookGroupPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("The book must have its own Book group at " + bookGroupPath, bookGroup);

		String probeName = "case16-probe-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord probe = newData(olioUser, probeName, "text/plain", "probe".getBytes(), bookGroupPath, orgId);
		assertTrue("Failed to create the probe record in the book's Book group",
			IOSystem.getActiveContext().getRecordUtil().createRecord(probe));
		CacheUtil.clearCache();

		BaseRecord byCreator = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(u,
			ModelNames.MODEL_DATA, (long) bookGroup.get(FieldNames.FIELD_ID), probeName);
		BaseRecord byBystander = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(bystander,
			ModelNames.MODEL_DATA, (long) bookGroup.get(FieldNames.FIELD_ID), probeName);
		logger.info("CASE 16 PBAC in the book's own Book group — creator=" + (byCreator != null)
			+ " bystander=" + (byBystander != null));

		assertNotNull("The creator, a member of the book Writer role, must be able to read a record in the"
			+ " book's own Book group", byCreator);
		assertNull("A bystander holding only the org-wide Olio User role must NOT read the book's own Book group",
			byBystander);

		logger.info("CASE 16 TOTAL wall clock (fresh organization creation + book create + assertions): "
			+ (System.currentTimeMillis() - t0) + "ms");
	}

	// ───────── Case 17: first context in a fresh org is a GRID - the wDir grant is unchanged ─────────

	/**
	 * The counterpart to case 16: the fix must not over-narrow. A grid/arena context carries NO role
	 * pair on its configuration, so the first-run bootstrap must still grant the org-wide
	 * {@code ~/Roles/Olio User} role Read on all three of {@code {rootDir, uDir, wDir}} - byte-for-byte
	 * the pre-fix behaviour.
	 * <p>
	 * <b>Scope, stated plainly.</b> This calls {@code OlioContext.configureEnvironment()} - the method
	 * that contains the branch - directly, and deliberately does NOT call {@code initialize()}. A grid
	 * world's {@code initialize()} builds a 100x100 map grid and a full population on top of the same
	 * corpus load case 16 already pays for; none of that is needed to observe a grant the bootstrap
	 * writes before any of it runs. So this case tests the grant, and nothing beyond it.
	 */
	@Test
	public void case17_freshOrgWhoseFirstContextIsAGridKeepsTheOrgWideReadGrantOnItsWorldsGroup() throws Exception {
		long t0 = System.currentTimeMillis();
		String orgPath = freshOrgPath();
		OrganizationContext fresh = getTestOrganization(orgPath);
		assertTrue("Failed to create the fresh organization " + orgPath, fresh.isInitialized());
		long orgId = fresh.getOrganizationId();
		logger.info("CASE 17 fresh organization " + orgPath + " (#" + orgId + ")");

		assertNull("Precondition: a brand-new organization must have no olio principal",
			ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId));
		assertNull("Precondition: a brand-new organization must have no /Olio group", groupAt(fresh, "/Olio"));

		BaseRecord u = user(fresh, TEST_USER);
		OlioContextConfiguration cfg = new OlioContextConfiguration(u, dataPath(),
			"Olio Universe", "Olio World", new String[0], 0, 0, false, false);
		/// The grid/arena shape: no per-context role pair, so compartmentalized == false.
		assertNull("A grid configuration must carry no per-context user role", cfg.getAuthorizationUserRole());
		assertNull("A grid configuration must carry no per-context admin role", cfg.getAuthorizationAdminRole());

		OlioContext gctx = new OlioContext(cfg);
		/// The first-run bootstrap in isolation, under the PBAC trace. Case 16 cannot do this - there the
		/// bootstrap is buried inside a 130-second getCreateBookContext and tracing all of it would be
		/// unusable - so this is where the log shows the bootstrap's grant calls themselves: which role
		/// receives which permission on which group. Enabled immediately before the call, off in a
		/// finally.
		logger.info("---- PBAC TRACE ON: first-run configureEnvironment() in " + orgPath + " ----");
		IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
		try {
			gctx.configureEnvironment();
		}
		finally {
			IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
			logger.info("---- PBAC TRACE OFF: first-run configureEnvironment() ----");
		}

		BaseRecord[] boot = assertFirstRunBootstrapRan(fresh);
		BaseRecord orgUserRole = boot[1];
		BaseRecord orgAdminRole = boot[2];

		String worldsPath = cfg.getWorldPath();
		assertEquals("A grid context's wDir must be its own per-universe Worlds container",
			"/Olio/Universes/Olio Universe/Worlds", worldsPath);

		BaseRecord rootDir = groupAt(fresh, "/Olio");
		BaseRecord uDir = groupAt(fresh, "/Olio/Universes");
		BaseRecord wDir = groupAt(fresh, worldsPath);
		assertNotNull("The bootstrap must have created /Olio", rootDir);
		assertNotNull("The bootstrap must have created /Olio/Universes", uDir);
		assertNotNull("The bootstrap must have created " + worldsPath, wDir);

		CacheUtil.clearCache();
		BaseRecord readPerm = readPermission(fresh);

		boolean userOnRoot = readGrant(orgUserRole, readPerm, rootDir);
		boolean userOnUniverses = readGrant(orgUserRole, readPerm, uDir);
		boolean userOnWorlds = tracedReadGrant("org-wide Olio User / Read / " + worldsPath
			+ " (grid: MUST stay true)", orgUserRole, readPerm, wDir);
		boolean adminOnWorlds = readGrant(orgAdminRole, readPerm, wDir);
		logger.info("CASE 17 org-wide entitlements — Olio User: /Olio=" + userOnRoot
			+ " /Olio/Universes=" + userOnUniverses + " " + worldsPath + "=" + userOnWorlds
			+ " | Olio Admin: " + worldsPath + "=" + adminOnWorlds);

		assertTrue("A grid context must still grant the org-wide Olio User role Read on /Olio", userOnRoot);
		assertTrue("A grid context must still grant the org-wide Olio User role Read on /Olio/Universes", userOnUniverses);
		assertTrue("THE FIX MUST NOT OVER-NARROW: a grid context (no per-context role pair) must still grant"
			+ " the org-wide Olio User role Read on " + worldsPath, userOnWorlds);
		assertTrue("...and the org-wide Olio Admin role too", adminOnWorlds);

		logger.info("CASE 17 TOTAL wall clock: " + (System.currentTimeMillis() - t0) + "ms");
	}

	// ───────── Case 18: the PRODUCTION grid path generates a population with bulk approval OFF ─────────

	/**
	 * The one phase-1 behaviour change that nothing else covers: {@code OlioContextUtil.getGridContext}
	 * and {@code getArenaContext} no longer wrap {@code initialize()} in
	 * {@code AccessPoint.setPermitBulkContainerApproval(true/false)}. Those four calls were a
	 * process-global, non-volatile PBAC relaxation with no {@code finally}, so an exception during init
	 * left it ON for every other thread in the JVM.
	 * <p>
	 * <b>Why the existing suite does not cover it.</b> Every Olio harness that builds a grid world
	 * ({@code OlioTestUtil:94}, {@code TestOlio2:137}, {@code TestGameUtilSync:550},
	 * {@code TestOlioGameFeatures:529}) sets the flag <b>true itself</b> and hand-builds an
	 * {@code OlioContextConfiguration}; none of them calls {@code getGridContext}. The only case that
	 * forces the flag false - case 7 - generates a population-FREE book world. So the whole gate is
	 * green with the flag ON, and the production game path (15+ {@code GameService} endpoints reach
	 * {@code getOlioContext}) was untested with it off.
	 * <p>
	 * <b>Entry point.</b> {@code OlioContextUtil.getOlioContext(user, dataPath, universe, world)} - the
	 * exact call the game endpoints make. It delegates to {@code getGridContext} through the shared
	 * context cache, so this exercises the production wiring, not a hand-built configuration.
	 * <p>
	 * <b>Generation is genuine, not an idempotent no-op.</b> The WORLD name carries a random suffix and
	 * is asserted absent beforehand, and the world's Population group is asserted to hold zero
	 * {@code olio.charPerson} records at the moment it is created; the count afterwards is therefore
	 * entirely this run's writes. The UNIVERSE is deliberately the pre-existing {@code Olio Universe}:
	 * the 100km map grid ({@code prepareMapGrid}/{@code checkK100} - one GZD, 480 kidents, 10,000
	 * feature squares) is universe-scoped and idempotent, and rebuilding it would measure terrain
	 * generation rather than the bulk character writes this case is about. Everything the flag used to
	 * cover - the ~50 {@code charPerson} creates plus their statistics/instinct/behavior/personality/
	 * state/store/profile sub-records, participations and region events - is fresh.
	 * <p>
	 * <b>The flag.</b> Asserted false before and after, and - because the deleted code set it true and
	 * back to false <i>around</i> {@code initialize()}, a shape a before/after pair cannot see - also
	 * sampled by a watcher thread for the duration of the call. Stated honestly: {@code AccessPoint}'s
	 * field is not {@code volatile}, so a positive observation is conclusive while a negative one is
	 * strong evidence rather than proof. {@code bookSetup()} forces the flag false first, because the
	 * other Olio suites set it true and mostly never reset it.
	 * <p>
	 * <b>Cost is measured, not asserted tightly.</b> The wall clock is logged; the ceiling is generous
	 * enough to catch an order-of-magnitude regression without being a benchmark.
	 * <p>
	 * Nothing here touches an LLM, an embedding server or Stable Diffusion.
	 */
	@Test
	public void case18_theProductionGridPathGeneratesAPopulationWithBulkApprovalOff() throws Exception {
		/// Precondition. bookSetup() forced this false; if it is not, nothing below is evidence.
		assertFalse("Precondition: bulk container approval must be OFF before the production grid path runs",
			IOSystem.getActiveContext().getAccessPoint().isPermitBulkContainerApproval());

		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		/// A world name that cannot collide with a previous run, so initialize() cannot short-circuit.
		String worldName = "pb2bulk-" + UUID.randomUUID().toString().substring(0, 8);
		String worldPath = "/Olio/Universes/" + GRID_UNIVERSE + "/Worlds";
		assertNull("Precondition: the world " + worldName + " must not already exist - a pre-existing world"
			+ " would make initialize() skip generation and this case would measure a no-op",
			WorldUtil.findWorld(u, worldPath, worldName));

		boolean universePreExisted = (WorldUtil.findWorld(u, "/Olio/Universes", GRID_UNIVERSE) != null);
		logger.info("CASE 18 universe '" + GRID_UNIVERSE + "' " + (universePreExisted ? "already exists (map grid is reused)"
			: "does NOT exist - this run also pays for the full 100km map grid") + "; new world '" + worldName + "'");

		/// Sample the flag for the whole of the generation. See the javadoc for what a negative
		/// observation is and is not worth. The AccessPoint reference is captured on THIS thread.
		final org.cote.accountmanager.client.AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		final java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean(true);
		final java.util.concurrent.atomic.AtomicBoolean sawTrue = new java.util.concurrent.atomic.AtomicBoolean(false);
		final java.util.concurrent.atomic.AtomicLong samples = new java.util.concurrent.atomic.AtomicLong();
		Thread watcher = new Thread(() -> {
			while(watching.get()) {
				samples.incrementAndGet();
				if(ap.isPermitBulkContainerApproval()) {
					sawTrue.set(true);
				}
				try {
					Thread.sleep(2);
				}
				catch(InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "case18-bulk-approval-watcher");
		watcher.setDaemon(true);

		OlioContext ctx = null;
		long genMs = 0;
		watcher.start();
		try {
			long t0 = System.currentTimeMillis();
			ctx = OlioContextUtil.getOlioContext(u, dataPath(), GRID_UNIVERSE, worldName);
			genMs = System.currentTimeMillis() - t0;
		}
		finally {
			watching.set(false);
			watcher.join(2000);
		}

		assertNotNull("The production grid entry point returned no context", ctx);
		assertTrue("The grid context must report itself initialized", ctx.isInitialized());
		assertEquals("The context must carry the world this case asked for", worldName,
			(String) ctx.getWorld().get(FieldNames.FIELD_NAME));

		/// THE ASSERTION THAT MATTERS: per-record PBAC did not DENY the bulk generation writes.
		BaseRecord popGroup = group(ctx.getWorld(), OlioFieldNames.FIELD_POPULATION);
		int people = countInGroup(OlioModelNames.MODEL_CHAR_PERSON, popGroup, orgId);
		List<BaseRecord> realms = ctx.getRealms();
		int realmPop = (realms.isEmpty() ? 0 : ctx.getRealmPopulation(realms.get(0)).size());
		int configured = ctx.getConfig().getBasePopulationCount();

		logger.info("CASE 18 RESULT — generation of a NEW grid world with permitBulkContainerApproval=false took "
			+ genMs + "ms; olio.charPerson in the world Population group = " + people
			+ " (configured basePopulationCount = " + configured + "), realms = " + realms.size()
			+ ", realm[0] population = " + realmPop + "; watcher took " + samples.get()
			+ " samples and observed the flag true " + sawTrue.get());

		assertTrue("The production grid path generated NO population with bulk container approval off."
			+ " Every charPerson/statistics/apparel write now takes per-record PBAC; a zero count here means"
			+ " those writes are being denied, which is a product defect, not a test problem.", people > 0);
		assertTrue("Expected at least the configured population of " + configured + " but found " + people
			+ " olio.charPerson records in the world's Population group", people >= configured);
		assertTrue("A grid world must have at least one realm", realms.size() > 0);
		assertTrue("The realm population must be readable and non-empty (this is the participation write,"
			+ " not just the record write)", realmPop > 0);

		/// The flag is never turned on.
		assertFalse("The production grid path must not leave bulk container approval enabled",
			ap.isPermitBulkContainerApproval());
		assertFalse("A watcher sampling for the whole of getOlioContext()/getGridContext() observed bulk"
			+ " container approval switched ON. The four setPermitBulkContainerApproval calls removed in"
			+ " phase 1 must not come back: the flag is process-global and had no finally.", sawTrue.get());

		/// A cache-cold rebuild against the world that now exists must also stay off, and must not
		/// duplicate the population.
		OlioContextUtil.evict(orgId, TEST_USER, GRID_UNIVERSE, worldName);
		long t1 = System.currentTimeMillis();
		OlioContext rebuilt = OlioContextUtil.getOlioContext(u, dataPath(), GRID_UNIVERSE, worldName);
		long rebuildMs = System.currentTimeMillis() - t1;
		assertNotNull("A cache-cold rebuild returned no context", rebuilt);
		assertNotSame("Eviction must have forced a genuine rebuild", ctx, rebuilt);
		int peopleAfter = countInGroup(OlioModelNames.MODEL_CHAR_PERSON, group(rebuilt.getWorld(), OlioFieldNames.FIELD_POPULATION), orgId);
		logger.info("CASE 18 REBUILD — cache-cold rebuild took " + rebuildMs + "ms; population " + people + "->" + peopleAfter);
		assertFalse("A cache-cold rebuild must not enable bulk container approval",
			ap.isPermitBulkContainerApproval());
		assertEquals("Re-initializing an existing grid world must not generate a second population",
			people, peopleAfter);

		/// Generous ceilings - an order-of-magnitude guard, not a benchmark. The measured figures are in
		/// the CASE 18 RESULT / CASE 18 REBUILD lines above.
		assertTrue("Grid world generation with bulk container approval off took " + genMs
			+ "ms (ceiling 300000ms)", genMs < 300000L);
		assertTrue("Cache-cold rebuild took " + rebuildMs + "ms (ceiling 300000ms)", rebuildMs < 300000L);
	}

	// ───────── Case 19: the two-tier role split — the book roles hold NOTHING on the universe ─────────

	/**
	 * Phase 2a. The universe grant pass now runs against an organization-wide
	 * {@code ~/Roles/Olio/Books/Reader} / {@code Writer} pair instead of the book's own roles, so a
	 * per-book {@code Admin} role no longer receives Create/Update/<b>Delete</b> on the shared corpora
	 * simply by being some book's admin role.
	 * <p>
	 * <b>The book is created inside this case, under a random slug.</b> The split is deliberately NOT
	 * retroactive - {@code AuthorizationUtil.setEntitlement} only ever adds - so every book that existed
	 * before it keeps its per-book roles' universe grants, and the negative assertions below would fail on
	 * one of those for a correct system. A fresh slug is the only fixture in which the negative half is
	 * meaningful. That scoping is a property of the ratified "no migration" decision, not a weakening.
	 * <p>
	 * <b>Only the universe's OWN groups can carry the negative.</b> The seven shared {@code /Library}
	 * corpora (Colors, Names, Words, Surnames, Patterns, Dictionary, Occupations) are foreign fields of the
	 * BOOK WORLD as well, so the world pass legitimately grants the book Writer Read/Update/Create on them.
	 * They are partitioned out by {@code parentId}, exactly as {@code OlioContext.resolveGrantTargets}
	 * does it, and the case fails if the remaining own-set is empty rather than passing vacuously.
	 */
	@Test
	public void case19_theUniverseTierIsGrantedToTheUniverseRolesAndNotToTheBookRoles() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();
		String slug = "pb2a-split-" + UUID.randomUUID().toString().substring(0, 8);

		OlioContext ctx = openBook(u, slug);
		assertTrue("A book created after the split must still complete world authorization",
			ctx.isAuthorizationConfigured());

		BaseRecord bookWriter = bookRole(ctx, PbOlioContextUtil.writerRolePath(slug));
		BaseRecord bookAdmin = bookRole(ctx, PbOlioContextUtil.adminRolePath(slug));
		BaseRecord uniReader = bookRole(ctx, PbOlioContextUtil.universeReaderRolePath());
		BaseRecord uniWriter = bookRole(ctx, PbOlioContextUtil.universeWriterRolePath());
		assertNotNull("Per-book Writer role must exist", bookWriter);
		assertNotNull("Per-book Admin role must exist", bookAdmin);
		assertNotNull("Universe Reader role must exist: " + PbOlioContextUtil.universeReaderRolePath(), uniReader);
		assertNotNull("Universe Writer role must exist: " + PbOlioContextUtil.universeWriterRolePath(), uniWriter);

		/// MEMBERSHIP — the creator must end up in BOTH tiers, and in neither admin role. A book whose
		/// creator is in the book Writer role alone cannot read the corpora the pipeline needs, which is
		/// why the universe enrolment is part of the create path rather than left to a later share.
		assertTrue("The creator must be a member of the book Writer role",
			IOSystem.getActiveContext().getMemberUtil().isMember(u, bookWriter, null));
		assertTrue("The creator must be a member of the universe Reader role - the book role alone holds"
			+ " nothing on the corpora since the split",
			IOSystem.getActiveContext().getMemberUtil().isMember(u, uniReader, null));
		assertFalse("Nothing may auto-enrol the creator in the book Admin role",
			IOSystem.getActiveContext().getMemberUtil().isMember(u, bookAdmin, null));
		assertFalse("Nothing may auto-enrol the creator in the universe Writer role - that role holds"
			+ " Create/Update/Delete on the shared corpora",
			IOSystem.getActiveContext().getMemberUtil().isMember(u, uniWriter, null));

		/// GRANTS — partition the universe's enumerated groups the way production does.
		BaseRecord readPerm = readPermission(org);
		BaseRecord library = groupAt(org, LibraryUtil.basePath);
		List<Long> sharedIds = new ArrayList<>();
		if(library != null) {
			for(BaseRecord g : IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(
					ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, library.get(FieldNames.FIELD_ID), orgId))) {
				sharedIds.add((long) g.get(FieldNames.FIELD_ID));
			}
		}

		CacheUtil.clearCache();
		int own = 0;
		int shared = 0;
		for(BaseRecord g : ctx.getAuthorizationGroups(ctx.getUniverse(), ctx.getConfig().getUniversePath())) {
			String gname = g.get(FieldNames.FIELD_NAME) + " (#" + g.get(FieldNames.FIELD_ID) + ")";
			if(sharedIds.contains((long) g.get(FieldNames.FIELD_ID))) {
				shared++;
				continue;
			}
			own++;
			assertTrue("Missing Read grant for the universe Reader role on universe group " + gname,
				readGrant(uniReader, readPerm, g));
			assertFalse("The per-book WRITER role must hold NO Read grant on universe group " + gname
				+ " for a book created after the two-tier split", readGrant(bookWriter, readPerm, g));
			assertFalse("The per-book ADMIN role must hold NO grant on universe group " + gname
				+ " - that is the Create/Update/Delete-on-shared-corpora exposure the split closes",
				readGrant(bookAdmin, readPerm, g));
		}
		logger.info("CASE 19 universe tier: " + own + " own group(s) asserted, " + shared
			+ " shared /Library group(s) skipped (the world pass grants those to the book role legitimately)");
		assertTrue("The universe own-group set is empty, so the negative assertions above proved nothing",
			own > 0);

		/// The membership has to buy real access, not just a row in a participation table: read an actual
		/// universe-own corpus record through PBAC as the creator. Traits is universe-local (case 1).
		BaseRecord traits = group(ctx.getUniverse(), OlioFieldNames.FIELD_TRAITS);
		BaseRecord[] anyTrait = IOSystem.getActiveContext().getSearch().findRecords(
			QueryUtil.getGroupQuery(ModelNames.MODEL_TRAIT, null, (long) traits.get(FieldNames.FIELD_ID), orgId));
		assertTrue("The universe Traits corpus is empty, so the PBAC read below would prove nothing",
			anyTrait.length > 0);
		String traitName = anyTrait[0].get(FieldNames.FIELD_NAME);
		assertNotNull("Creator must be able to READ a universe Traits record through the universe Reader"
			+ " role - if this is null the split has taken corpora access away instead of relocating it",
			IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(u, ModelNames.MODEL_TRAIT,
				(long) traits.get(FieldNames.FIELD_ID), traitName));

		/// And the world tier is untouched: the book role still holds Read on the book's own groups.
		for(BaseRecord g : ctx.getAuthorizationGroups(ctx.getWorld(), ctx.getConfig().getWorldPath())) {
			assertTrue("Missing Read grant for the book Writer role on world group "
				+ g.get(FieldNames.FIELD_NAME) + " (#" + g.get(FieldNames.FIELD_ID) + ")",
				readGrant(bookWriter, readPerm, g));
		}
	}

	// ───────── Case 20: the recursive world-tier grant reaches sub-subgroups ─────────

	/**
	 * Phase 2a, the other half. Case 9 pins the platform property - entitlements join on an exact
	 * {@code groupId} and do not inherit down the group tree - which means the book path's two
	 * {@code configureWorldAuthorization} passes stop at the world's own ~36 groups. Because grants are
	 * Read/Update/Create/Delete together, that was a WRITE gap: a portrait written into
	 * {@code Gallery/Characters} would be DENIED, not merely invisible.
	 * <p>
	 * The fix is {@code OlioContext.scanNestedWorldGroups()}, run from {@code initialize()} when the
	 * configuration opts in. This case proves it end to end, in the only order that is evidence:
	 * <ol>
	 * <li>create a sub-subgroup and a sub-sub-subgroup AFTER the context was built, and confirm the book
	 * role reaches neither (the "before", and the same measurement case 9 makes);</li>
	 * <li>evict and re-open, so {@code initialize()} genuinely re-runs rather than the cache answering;</li>
	 * <li>confirm the book role now holds Read on BOTH levels and that the creator can read a record in
	 * the deeper one through PBAC.</li>
	 * </ol>
	 * Step 1 is what makes step 3 meaningful: without it a green result could just mean the group had been
	 * granted by some earlier run.
	 */
	@Test
	public void case20_reopeningABookGrantsTheWorldRolesRecursivelyIntoSubSubgroups() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = user(org, TEST_USER);
		long orgId = org.getOrganizationId();

		OlioContext ctx = openBook(u, SLUG_ALPHA);
		BaseRecord olioUser = ctx.getOlioUser();
		BaseRecord gallery = group(ctx.getWorld(), OlioFieldNames.FIELD_GALLERY);
		String galleryPath = gallery.get(FieldNames.FIELD_PATH);
		assertNotNull("Gallery path is null", galleryPath);

		String tag = UUID.randomUUID().toString().substring(0, 8);
		String lvl2Name = "Characters-case20-" + tag;
		String lvl3Name = "deep-" + tag;
		String lvl2Path = galleryPath + "/" + lvl2Name;
		String lvl3Path = lvl2Path + "/" + lvl3Name;

		BaseRecord lvl2 = IOSystem.getActiveContext().getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP,
			lvl2Path, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Failed to create " + lvl2Path, lvl2);
		BaseRecord lvl3 = IOSystem.getActiveContext().getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP,
			lvl3Path, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Failed to create " + lvl3Path, lvl3);

		/// Owned by the olio user and written without PBAC, so the only route to it is the book role's
		/// group entitlement - the same construction case 9 uses.
		String recName = "case20-deep-" + tag;
		BaseRecord deep = newData(olioUser, recName, "text/plain", "deep".getBytes(), lvl3Path, orgId);
		assertTrue("Failed to create the deep record", IOSystem.getActiveContext().getRecordUtil().createRecord(deep));

		BaseRecord writer = bookRole(ctx, PbOlioContextUtil.writerRolePath(SLUG_ALPHA));
		assertNotNull("Book Writer role must exist", writer);
		BaseRecord readPerm = readPermission(org);

		/// BEFORE — both groups were created after this context's grant pass, so neither is reachable.
		CacheUtil.clearCache();
		boolean before2 = readGrant(writer, readPerm, lvl2);
		boolean before3 = readGrant(writer, readPerm, lvl3);
		BaseRecord readBefore = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(u,
			ModelNames.MODEL_DATA, (long) lvl3.get(FieldNames.FIELD_ID), recName);
		logger.info("CASE 20 BEFORE — entitlement lvl2=" + before2 + " lvl3=" + before3
			+ ", PBAC read of the deep record=" + (readBefore != null));
		assertFalse("Precondition: a group created after the grant pass must not already be granted"
			+ " (if this is true the case cannot prove the recursive pass did anything)", before2);
		assertFalse("Precondition: the deeper group must not already be granted", before3);
		assertNull("Precondition: the deep record must not be readable before the recursive pass", readBefore);

		/// RE-OPEN — eviction forces initialize() to run again, which is what invokes the recursive pass.
		PbOlioContextUtil.evictBookContext(u, SLUG_ALPHA);
		assertFalse("Eviction must remove the cached key",
			OlioContextUtil.getCachedKeys().contains(orgId + "/" + TEST_USER + "/"
				+ PbOlioContextUtil.BOOKS_UNIVERSE + "/" + SLUG_ALPHA));
		OlioContext reopened = openBook(u, SLUG_ALPHA);
		assertNotSame("Eviction must have forced a genuine rebuild", ctx, reopened);
		assertTrue("The re-opened context must have completed world authorization",
			reopened.isAuthorizationConfigured());

		/// AFTER — the same three measurements.
		CacheUtil.clearCache();
		boolean after2 = readGrant(writer, readPerm, lvl2);
		boolean after3 = readGrant(writer, readPerm, lvl3);
		BaseRecord readAfter = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(u,
			ModelNames.MODEL_DATA, (long) lvl3.get(FieldNames.FIELD_ID), recName);
		logger.info("CASE 20 AFTER — entitlement lvl2=" + after2 + " lvl3=" + after3
			+ ", PBAC read of the deep record=" + (readAfter != null));

		assertTrue("The recursive world-tier pass must grant the book Writer role on " + lvl2Path, after2);
		assertTrue("The recursive pass must recurse further than one level: " + lvl3Path, after3);
		assertNotNull("The creator must be able to read a record in " + lvl3Path + " after the recursive"
			+ " pass - a grant that does not produce a readable record is not a fix", readAfter);
	}
}
