package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.util.LibraryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbSharingUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.cache.CacheUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * §9's {@code TestPbSecurity}, <b>Objects7-level assertions only</b>. Its REST-level assertions
 * ({@code POST /rest/model/search}, {@code DELETE /{A}/reset}, {@code /scene/{S}/generate},
 * {@code /cancel}) cannot exist until phase 4 and live there; listing them as a phase-2 exit was
 * incoherent.
 * <p>
 * Two users, two books, and the isolation properties asserted as <b>negative</b> tests. A negative
 * result is only evidence if the check actually ran against the objects the case believes it did, so
 * every negative leg is paired with a positive control - user A can read what user B cannot - and the
 * PBAC trace is available (bracketed to one call) where a false green would otherwise be invisible.
 * <p>
 * Also carries the <b>role-hierarchy direction test</b> ratified for phase 2
 * ({@link #case08_roleHierarchyInheritanceDirection()}): §5.3's verification test 1, designated a
 * phase-1 one-run settlement and never run. §10 Q10 depends on the answer.
 * <p>
 * <b>Not covered here, stated rather than faked:</b> §9's "force {@code rootEvent == null} during
 * creation" leg needs a hook that does not exist - {@code BookWorldInitializationRule} always creates
 * the CONSTRUCT root event and there is no supported way to suppress it from a test without editing
 * production code. It is left unwritten rather than approximated.
 */
public class TestPbSecurity extends BaseTest {

	private static final String ORG_A = "/Development/World Building";

	/** Owner of book A. The shared Olio test user; never the admin. */
	private static final String USER_A = "testUser1";
	/** Owner of book B. A different, ordinary user. */
	private static final String USER_B = "pbSecUserB";
	/** Enrolled in book A's Writer role ONLY - never the universe tier. See case 6. */
	private static final String USER_BOOKONLY = "pbSecBookOnly";

	private static final String SLUG_A = "pb2c-sec-a";
	private static final String SLUG_B = "pb2c-sec-b";

	@Before
	public void securitySetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private OrganizationContext org() {
		return getTestOrganization(ORG_A);
	}

	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve test user " + name, u);
		return u;
	}

	/** Get-or-create a book. {@code createBook} refuses a duplicate slug by design, so probe first. */
	private BaseRecord book(BaseRecord u, String slug) {
		long orgId = u.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord existing = PbBookUtil.findBookBySlug(u, slug, orgId);
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), slug, "Phase 2c security fixture " + slug);
		assertNotNull("Failed to create book " + slug, created);
		return created;
	}

	private long orgOf(BaseRecord u) {
		return (long) u.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private BaseRecord role(String path) {
		OrganizationContext o = org();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, o.getOrganizationId());
		assertNotNull("No olio principal", olioUser);
		return ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, path,
			RoleEnumType.USER.toString(), o.getOrganizationId());
	}

	private BaseRecord readPermission() {
		BaseRecord p = ioContext.getPathUtil().findPath(org().getAdminUser(), ModelNames.MODEL_PERMISSION,
			"/Read", PermissionEnumType.DATA.toString(), org().getOrganizationId());
		assertNotNull("Read permission not resolved", p);
		return p;
	}

	private BaseRecord permission(String name) {
		BaseRecord p = ioContext.getPathUtil().findPath(org().getAdminUser(), ModelNames.MODEL_PERMISSION,
			"/" + name, PermissionEnumType.DATA.toString(), org().getOrganizationId());
		assertNotNull(name + " permission not resolved", p);
		return p;
	}

	/**
	 * Build a small graph in {@code slug}'s workflow so the isolation cases have real nodes, bindings,
	 * artifacts and scenes to fail to read. Handles are randomly suffixed, so nothing an earlier run left
	 * behind can satisfy a positive control by accident.
	 */
	private BaseRecord seedGraph(BaseRecord u, BaseRecord bk, String slug) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull(wf);
		String tag = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, "sec-" + tag, PbNodeTypeEnumType.SCENE,
			PbBookUtil.workflowGroupPath(slug), 0);
		PbGraphUtil.addBinding(u, wf, n, "sceneText", 0, null, null, PbBookUtil.workflowGroupPath(slug));
		PbArtifactUtil.persistArtifact(u, n, "sceneText", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(slug), null, "secret scene text " + tag, null, null);
		/// Get-or-create: the scene name is derived from (slug, sceneIndex), so creating index 0 again on a
		/// second run collides on the unique (name, groupId, organizationId) index. The isolation cases only
		/// need A to own SOME scene, not a fresh one.
		if(PbBookUtil.listScenes(u, bk).isEmpty()) {
			PbBookUtil.createScene(u, bk, 0, "Secret scene " + tag, PbBookUtil.bookGroupPath(slug));
		}
		return wf;
	}

	// ───────── 1: B cannot read any of A's PB records ─────────

	/**
	 * The headline isolation property, across all five models plus the scene. The positive control (A can
	 * read each one) is what makes each null meaningful: without it, a bug that made every read return
	 * null would pass every assertion here.
	 */
	@Test
	public void case01_bCannotReadAnyOfAsPictureBookRecords() {
		BaseRecord a = user(USER_A);
		BaseRecord b = user(USER_B);
		BaseRecord bookA = book(a, SLUG_A);
		book(b, SLUG_B);
		BaseRecord wfA = seedGraph(a, bookA, SLUG_A);
		long orgId = orgOf(a);
		assertEquals("Both users must be in the same organization for this to be a PBAC test rather than a"
			+ " tenant test", orgId, orgOf(b));

		BaseRecord nodeA = PbGraphUtil.listNodes(a, wfA).get(0);
		List<BaseRecord> bindingsA = PbGraphUtil.listBindings(a, nodeA);
		List<BaseRecord> scenesA = PbBookUtil.listScenes(a, bookA);
		BaseRecord artifactA = PbArtifactUtil.findSelected(a, nodeA, "sceneText");
		assertFalse("Positive control: A's workflow must have nodes", PbGraphUtil.listNodes(a, wfA).isEmpty());
		assertFalse("Positive control: A's node must have bindings", bindingsA.isEmpty());
		assertFalse("Positive control: A's book must have scenes", scenesA.isEmpty());
		assertNotNull("Positive control: A's node must have a selected artifact", artifactA);

		CacheUtil.clearCache();

		assertNull("B must not read A's book by slug",
			PbBookUtil.findBookBySlug(b, SLUG_A, orgId));
		assertNull("B must not read A's book by objectId",
			PbBookUtil.readBook(b, bookA.get(FieldNames.FIELD_OBJECT_ID), orgId));
		assertNull("B must not read A's workflow",
			ioContext.getAccessPoint().findByObjectId(b, OlioModelNames.MODEL_PB_WORKFLOW,
				wfA.get(FieldNames.FIELD_OBJECT_ID)));
		assertNull("B must not read A's node",
			PbGraphUtil.readNode(b, nodeA.get(FieldNames.FIELD_OBJECT_ID), orgId));
		assertNull("B must not read A's binding",
			PbGraphUtil.readBinding(b, bindingsA.get(0).get(FieldNames.FIELD_OBJECT_ID), orgId));
		assertNull("B must not read A's artifact",
			PbArtifactUtil.readArtifact(b, artifactA.get(FieldNames.FIELD_OBJECT_ID), orgId));
		assertNull("B must not read A's scene",
			PbBookUtil.readScene(b, scenesA.get(0).get(FieldNames.FIELD_OBJECT_ID), orgId));
	}

	// ───────── 2: the list path is NOT a security boundary - MEASURED DEFECT ─────────

	/**
	 * <b>§9's assertion here does not hold today, and this case says so rather than being deleted.</b>
	 * §9 asks that "B's {@code POST /rest/model/search} for {@code olio.pb.node} with an explicit numeric
	 * {@code organizationId} condition returns zero of A's nodes". <b>Measured on {@code am7db}
	 * 2026-08-16: it returns ALL of them.</b>
	 * <p>
	 * Cause, read out of {@code AccessPoint} rather than guessed:
	 * <ul>
	 * <li>{@code AccessPoint.find} authorizes the query shape and then runs
	 * {@code AuthorizationUtil.canRead} on the result before returning it
	 * ({@code AccessPoint.java:513-517}) - a genuine per-record check, and it correctly DENIES B
	 * (audit: {@code AUDIT DENY pbSecUserB to READ ... (objectId = ... && organizationId = 7)});</li>
	 * <li>{@code AccessPoint.list} ({@code :623-636}) authorizes the query shape via
	 * {@code authorizeQuery} and then returns whatever {@code search} returned, <b>with no per-record
	 * filtering at all</b> (audit: {@code AUDIT PERMIT pbSecUserB to READ ... (organizationId = 7)}).</li>
	 * </ul>
	 * So the enforced compartment boundary is the by-identity read, not the list. This is <b>pre-existing
	 * platform behaviour, not introduced by phase 2c</b> - every group-scoped model is exposed the same way -
	 * but PB2 raises the stakes, because a book's whole graph becomes listable by any authenticated user in
	 * the organization.
	 * <p>
	 * <b>REWRITTEN 2026-08-17 to §9's original form, deliberately, because the leak was FIXED.</b> This case
	 * was a labelled characterization asserting the leak, with an instruction in this javadoc to rewrite it
	 * to {@code assertFalse} if the leak were ever closed. {@code AccessPoint.list} now runs
	 * {@code canRead} on each row it returns ({@code filterReadable}), so that is what this asserts.
	 * <p>
	 * <b>What this proves is a COMPARTMENT boundary, not a per-record one.</b> A and B own different books,
	 * so their nodes sit in different groups - and {@code canRead} on a {@code data.directory} record
	 * resolves against the <b>group's</b> urn ({@code PolicyUtil.java:761-789}). Two records in the SAME
	 * group can never get different answers, here or in {@code find}. The name of this case says "security
	 * boundary" in that sense only; nothing below demonstrates record-level isolation, because nothing in
	 * the platform provides it.
	 * <p>
	 * <b>Residues asserted/recorded here rather than implied away</b> (see KI-67 and the comment on
	 * {@code AccessPoint.filterReadable}): {@code count} is still unfiltered, so the CARDINALITY of
	 * unreadable records is still observable; and because the filter runs after LIMIT/OFFSET, a paged list
	 * can return a short page while {@code totalCount} reports the unfiltered total.
	 */
	@Test
	public void case02_theListPathNowEnforcesTheCompartmentBoundary() {
		BaseRecord a = user(USER_A);
		BaseRecord b = user(USER_B);
		BaseRecord bookA = book(a, SLUG_A);
		BaseRecord wfA = seedGraph(a, bookA, SLUG_A);
		long orgId = orgOf(a);
		BaseRecord nodeA = PbGraphUtil.listNodes(a, wfA).get(0);
		Long nodeAId = nodeA.get(FieldNames.FIELD_ID);
		String nodeAOid = nodeA.get(FieldNames.FIELD_OBJECT_ID);

		CacheUtil.clearCache();
		assertTrue("Positive control: A's own org-wide list must contain A's node. Without this, a filter"
			+ " that removed EVERYTHING would pass the assertion below.",
			orgWideNodeIds(a, orgId).contains(nodeAId));

		/// The boundary that was always enforced: a by-identity read runs canRead and denies B.
		assertNull("The by-identity read must deny B", PbGraphUtil.readNode(b, nodeAOid, orgId));

		/// KI-67, now closed for content: the org-wide list must not carry A's node to B either.
		assertFalse("KI-67: an org-wide olio.pb.node list with an explicit numeric organizationId must NOT"
			+ " return A's node to B. If this fails, AccessPoint.list has stopped filtering per record and"
			+ " a whole book graph is enumerable by any authenticated user in the organization.",
			orgWideNodeIds(b, orgId).contains(nodeAId));

		/// Residue 1, asserted as a measured fact so it cannot be mistaken for closed: count is not filtered.
		Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		cq.setCache(false);
		int bCount = ioContext.getAccessPoint().count(b, cq);
		int aCount = ioContext.getAccessPoint().count(a, cq);
		logger.warn("KI-67 RESIDUE (open): AccessPoint.count is NOT filtered - B counts " + bCount
			+ " olio.pb.node rows org-wide while its filtered list returns far fewer (A counts " + aCount
			+ "). The content leak is closed; the cardinality leak is not, and closing it needs a SQL-level"
			+ " restriction rather than a post-filter.");
		assertEquals("Residue: count still reports the same unfiltered org-wide total for both users",
			aCount, bCount);
	}

	// ───────── 11: what per-record list filtering actually costs ─────────

	/**
	 * The cost of KI-67's fix, <b>measured rather than argued</b>, because both earlier positions on it were
	 * asserted without a number: the original disposition claimed per-record filtering means a policy
	 * evaluation per row (retracted), and the retraction claimed N records in one group cost "one evaluation,
	 * then decision-cache hits" - which the code does not support either, since there is no policy DECISION
	 * cache ({@code PolicyUtil} caches only the raw template text, {@code :125}).
	 * <p>
	 * What IS true, read out of the code: for a {@code data.directory}- or {@code common.parent}-scoped
	 * record the policy resource is rewritten to the GROUP's urn ({@code PolicyUtil.java:761-789},
	 * {@code :795-821}), so N records in one group produce ONE policyBase string and their entitlement
	 * lookups hit the shared participation/query cache. The repeated work is policy assembly (a
	 * {@code JSONUtil.importObject} per row) plus an evaluator pass.
	 * <p>
	 * <b>Asserts correctness, logs cost.</b> A timing threshold in a suite that shares a JVM with live LLM
	 * and SD tests would be flaky, and a flaky performance gate is worse than a recorded number.
	 */
	@Test
	public void case11_listFilteringCost_MEASURED() throws Exception {
		BaseRecord a = user(USER_A);
		BaseRecord bookA = book(a, SLUG_A);
		seedGraph(a, bookA, SLUG_A);
		long orgId = orgOf(a);

		CacheUtil.clearCache();
		/// Warm the caches the way any second request in a session would find them, so the number describes
		/// steady state rather than a cold JVM.
		orgWideNodeIds(a, orgId);

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbGraphUtil.nodeRequest());
		q.setRequestRange(0, 1000);
		q.setCache(false);
		long t0 = System.currentTimeMillis();
		BaseRecord[] rows = ioContext.getAccessPoint().list(a, q).getResults();
		long filteredMs = System.currentTimeMillis() - t0;
		int n = (rows != null ? rows.length : 0);
		assertTrue("The measurement needs rows to measure", n > 0);

		/// The same read WITHOUT the filter, straight through the search layer, as the baseline.
		Query q2 = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q2.setRequest(PbGraphUtil.nodeRequest());
		q2.setRequestRange(0, 1000);
		q2.setCache(false);
		long t1 = System.currentTimeMillis();
		int unfiltered = IOSystem.getActiveContext().getSearch().find(q2).getResults().length;
		long unfilteredMs = System.currentTimeMillis() - t1;

		java.util.Set<Object> groups = new java.util.HashSet<>();
		for(BaseRecord r : rows) {
			groups.add(r.get(FieldNames.FIELD_GROUP_ID));
		}
		double perRow = (n > 0) ? ((double) (filteredMs - unfilteredMs) / n) : 0d;
		logger.warn("KI-67 COST (measured): AccessPoint.list returned " + n + " readable of " + unfiltered
			+ " matched rows across " + groups.size() + " distinct group(s). Filtered list " + filteredMs
			+ "ms vs unfiltered search " + unfilteredMs + "ms => ~"
			+ String.format(java.util.Locale.ROOT, "%.2f", perRow) + "ms per row of authorization work."
			+ " There is no policy decision cache; the per-row work is policy assembly + evaluation, with the"
			+ " DB reads shared via the group-urn rewrite and the participation/query cache.");
		assertTrue("The filtered list must not return more rows than the unfiltered search matched",
			n <= unfiltered);
	}

	private List<Long> orgWideNodeIds(BaseRecord u, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbGraphUtil.nodeRequest());
		q.setRequestRange(0, 1000);
		q.setCache(false);
		BaseRecord[] recs = ioContext.getAccessPoint().list(u, q).getResults();
		List<Long> ids = new ArrayList<>();
		if(recs != null) {
			for(BaseRecord r : recs) {
				ids.add((Long) r.get(FieldNames.FIELD_ID));
			}
		}
		return ids;
	}

	// ───────── 3: B cannot create in A's Workflow group ─────────

	@Test
	public void case03_bCannotCreateInAsWorkflowGroup() {
		BaseRecord a = user(USER_A);
		BaseRecord b = user(USER_B);
		BaseRecord bookA = book(a, SLUG_A);
		BaseRecord wfA = PbGraphUtil.getCreateWorkflow(a, bookA, PbBookUtil.workflowGroupPath(SLUG_A));

		CacheUtil.clearCache();
		/// addNode goes through AccessPoint.create and throws when the create is refused.
		try {
			PbGraphUtil.addNode(b, wfA, "intruder-" + UUID.randomUUID().toString().substring(0, 8),
				PbNodeTypeEnumType.SCENE, PbBookUtil.workflowGroupPath(SLUG_A), 99);
			fail("B must not be able to create a node in A's Workflow group");
		}
		catch(PictureBookException e) {
			assertEquals(500, e.getStatus());
		}
	}

	// ───────── 4: reading never enrols ─────────

	/**
	 * §9 asks for this twice - once with the reader having never built a context, once after. The
	 * "never" leg uses a <b>brand-new user per run</b>, because a stable fixture user cannot be
	 * "never initialized" on the second run and the leg would silently stop testing anything.
	 */
	@Test
	public void case04_readingNeverEnrolsTheReader() {
		BaseRecord a = user(USER_A);
		BaseRecord bookA = book(a, SLUG_A);
		BaseRecord wfA = seedGraph(a, bookA, SLUG_A);
		long orgId = orgOf(a);
		BaseRecord writerRole = role(PbOlioContextUtil.writerRolePath(SLUG_A));
		BaseRecord adminRole = role(PbOlioContextUtil.adminRolePath(SLUG_A));
		BaseRecord uniReader = role(PbOlioContextUtil.universeReaderRolePath());
		assertNotNull(writerRole);
		assertNotNull(adminRole);
		assertNotNull(uniReader);

		/// LEG 1 - a user who has never built any Olio context.
		BaseRecord fresh = user("pbSecFresh" + UUID.randomUUID().toString().substring(0, 6));
		assertFalse("Precondition: the fresh user is in no book role",
			ioContext.getMemberUtil().isMember(fresh, writerRole, null));
		readSweep(fresh, bookA, wfA, orgId);
		assertFalse("A read sweep must not enrol the reader in the book Writer role",
			ioContext.getMemberUtil().isMember(fresh, writerRole, null));
		assertFalse("...nor the book Admin role", ioContext.getMemberUtil().isMember(fresh, adminRole, null));
		assertFalse("...nor the universe Reader role", ioContext.getMemberUtil().isMember(fresh, uniReader, null));

		/// LEG 2 - a user who HAS built a context (their own book).
		BaseRecord b = user(USER_B);
		book(b, SLUG_B);
		readSweep(b, bookA, wfA, orgId);
		assertFalse("A reader who owns their own book must still not be enrolled in A's book",
			ioContext.getMemberUtil().isMember(b, writerRole, null));
		assertFalse(ioContext.getMemberUtil().isMember(b, adminRole, null));
	}

	/** Every read a curious caller could make against another user's book. All expected to be refused. */
	private void readSweep(BaseRecord reader, BaseRecord bookA, BaseRecord wfA, long orgId) {
		PbBookUtil.findBookBySlug(reader, SLUG_A, orgId);
		PbBookUtil.readBook(reader, bookA.get(FieldNames.FIELD_OBJECT_ID), orgId);
		ioContext.getAccessPoint().findByObjectId(reader, OlioModelNames.MODEL_PB_WORKFLOW,
			wfA.get(FieldNames.FIELD_OBJECT_ID));
		PbGraphUtil.listNodes(reader, wfA);
		PbBookUtil.listScenes(reader, bookA);
		orgWideNodeIds(reader, orgId);
	}

	// ───────── 5: sharing a book requires holding it ─────────

	/**
	 * Ratification 16: add-by-writer-only. The negative leg is the one that matters - an outsider must not
	 * be able to share somebody else's book with themselves, which is the shape that would turn the whole
	 * compartment into a no-op.
	 */
	@Test
	public void case05_sharingRequiresHoldingTheBook() {
		BaseRecord a = user(USER_A);
		BaseRecord b = user(USER_B);
		book(a, SLUG_A);

		try {
			PbSharingUtil.shareBook(b, b, dataPath(), SLUG_A, false);
			fail("B must not be able to share A's book with themselves");
		}
		catch(PictureBookException e) {
			assertEquals("An unentitled share is a 403", 403, e.getStatus());
		}
		assertFalse("The refused share must not have enrolled B",
			ioContext.getMemberUtil().isMember(b, role(PbOlioContextUtil.writerRolePath(SLUG_A)), null));

		/// A holds the book but only the WRITER role; registerUser's authorizing role is Admin, so even
		/// the owner cannot enrol somebody else until they hold Admin. That is the ratified shape
		/// (nothing auto-enrols into Admin), and it is asserted rather than assumed.
		try {
			PbSharingUtil.shareBook(a, b, dataPath(), SLUG_A, false);
			logger.info("CASE 5 - the book Writer role WAS sufficient to enrol another user");
			assertTrue("If the share succeeded, B must actually be enrolled in both tiers",
				ioContext.getMemberUtil().isMember(b, role(PbOlioContextUtil.writerRolePath(SLUG_A)), null)
				&& ioContext.getMemberUtil().isMember(b, role(PbOlioContextUtil.universeReaderRolePath()), null));
		}
		catch(PictureBookException e) {
			assertEquals(403, e.getStatus());
			logger.info("CASE 5 - enrolling requires the book ADMIN role; the Writer role alone is refused: "
				+ e.getMessage());
			assertFalse("A refused share must enrol nobody",
				ioContext.getMemberUtil().isMember(b, role(PbOlioContextUtil.writerRolePath(SLUG_A)), null));
		}
	}

	// ───────── 6: the book role alone does not reach the universe corpora ─────────

	/**
	 * §9's "a user holding the book role but not the universe role cannot read the universe corpora",
	 * which proves the two-role requirement is real rather than decorative.
	 * <p>
	 * <b>Scoped to a book created after the phase-2a split</b> (ratification 3): the split is not
	 * retroactive, so books whose roles predate it keep their universe grants permanently. That scoping is
	 * a test-fixture detail, which is why the case creates its own slug rather than reusing SLUG_A.
	 */
	@Test
	public void case06_theBookRoleAloneCannotReadTheUniverseCorpora() throws Exception {
		BaseRecord a = user(USER_A);
		BaseRecord bookOnly = user(USER_BOOKONLY);
		String slug = "pb2c-sec-split-" + UUID.randomUUID().toString().substring(0, 8);
		OrganizationContext o = org();

		OlioContext ctx = PbOlioContextUtil.getCreateBookContext(a, dataPath(), slug);
		assertTrue(ctx.isAuthorizationConfigured());

		/// Enrol the probe user in the BOOK tier only. registerUser as the org admin is authorized by
		/// definition, which is exactly what makes this a controlled fixture rather than a policy test.
		assertTrue("Failed to enrol the probe user in the book Writer role",
			ctx.registerUser(o.getAdminUser(), bookOnly, false));
		BaseRecord uniReader = role(PbOlioContextUtil.universeReaderRolePath());
		assertNotNull(uniReader);
		assertFalse("Precondition: the probe user must NOT be in the universe Reader role, or the case"
			+ " proves nothing", ioContext.getMemberUtil().isMember(bookOnly, uniReader, null));

		/// The corpus record to probe. Traits is universe-local (TestBookWorld case 1).
		BaseRecord traits = ctx.getUniverse().get(OlioFieldNames.FIELD_TRAITS);
		assertNotNull("The universe must have a Traits group", traits);
		ioContext.getReader().populate(traits);
		long traitsGroupId = traits.get(FieldNames.FIELD_ID);
		BaseRecord[] anyTrait = IOSystem.getActiveContext().getSearch().findRecords(
			QueryUtil.getGroupQuery(ModelNames.MODEL_TRAIT, null, traitsGroupId, o.getOrganizationId()));
		assertTrue("The universe Traits corpus is empty, so both probes below would prove nothing",
			anyTrait.length > 0);
		String traitName = anyTrait[0].get(FieldNames.FIELD_NAME);

		CacheUtil.clearCache();
		/// POSITIVE CONTROL: the creator, who IS in both tiers, can read it.
		assertNotNull("Positive control: the creator holds both tiers and must be able to read a Traits record",
			ioContext.getAccessPoint().findByNameInGroup(a, ModelNames.MODEL_TRAIT, traitsGroupId, traitName));
		/// NEGATIVE: the book tier alone cannot.
		assertNull("A user holding only the per-book role must NOT reach the universe corpora - the two-role"
			+ " requirement is what makes the split real",
			ioContext.getAccessPoint().findByNameInGroup(bookOnly, ModelNames.MODEL_TRAIT, traitsGroupId, traitName));
	}

	// ───────── 7: creating a second book changes nothing about the first ─────────

	/**
	 * §9's cross-book leg: after creating book B, the effective entitlements on book A's groups are
	 * unchanged - no grant naming B's role and none naming the org-wide {@code Olio User} role.
	 * <p>
	 * <b>Both books are created inside this case</b>, with random slugs, so their roles are new: a legacy
	 * over-granted role from before the phase-2a split could otherwise satisfy the negative assertions for
	 * the wrong reason.
	 */
	@Test
	public void case07_creatingASecondBookDoesNotWidenTheFirst() throws Exception {
		BaseRecord a = user(USER_A);
		OrganizationContext o = org();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String slug1 = "pb2c-x1-" + tag;
		String slug2 = "pb2c-x2-" + tag;

		OlioContext ctx1 = PbOlioContextUtil.getCreateBookContext(a, dataPath(), slug1);
		List<BaseRecord> groups1 = ctx1.getAuthorizationGroups(ctx1.getWorld(), ctx1.getConfig().getWorldPath());
		assertFalse("Book 1 must enumerate world groups", groups1.isEmpty());

		OlioContext ctx2 = PbOlioContextUtil.getCreateBookContext(a, dataPath(), slug2);
		assertTrue(ctx2.isAuthorizationConfigured());

		BaseRecord role1 = role(PbOlioContextUtil.writerRolePath(slug1));
		BaseRecord role2 = role(PbOlioContextUtil.writerRolePath(slug2));
		BaseRecord admin2 = role(PbOlioContextUtil.adminRolePath(slug2));
		BaseRecord orgWide = ioContext.getPathUtil().findPath(
			ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, o.getOrganizationId()),
			ModelNames.MODEL_ROLE, "~/Roles/Olio User", RoleEnumType.USER.toString(), o.getOrganizationId());
		assertNotNull(role1);
		assertNotNull(role2);
		assertNotNull(orgWide);

		BaseRecord readPerm = readPermission();
		BaseRecord deletePerm = permission("Delete");

		/// getAuthorizationGroups returns the world's own groups AND the 7 shared /Library corpora, and
		/// EVERY book's world pass grants on the shared ones - legitimately (Appendix D: shared groups get
		/// userWrite ? CRU : Read, never Delete). So the cross-book negative assertions apply only to
		/// book 1's OWN groups; asserting them over the shared corpora would fail a correct system.
		/// Partitioned by parentId, the same way TestBookWorld case19 does it.
		List<Long> sharedIds = libraryChildIds(o);

		CacheUtil.clearCache();
		int own = 0;
		for(BaseRecord g : groups1) {
			if(sharedIds.contains((Long) g.get(FieldNames.FIELD_ID))) {
				continue;
			}
			own++;
			String label = g.get(FieldNames.FIELD_NAME) + " (#" + g.get(FieldNames.FIELD_ID) + ")";
			assertTrue("Positive control: book 1's own role must hold Read on its own group " + label,
				ioContext.getAuthorizationUtil().checkEntitlement(role1, readPerm, g));
			assertFalse("Book 2's Writer role must hold nothing on book 1's group " + label,
				ioContext.getAuthorizationUtil().checkEntitlement(role2, readPerm, g));
			assertFalse("Book 2's Admin role must hold nothing on book 1's group " + label,
				ioContext.getAuthorizationUtil().checkEntitlement(admin2, readPerm, g));
			assertFalse("The org-wide Olio User role must hold nothing on a book world group " + label,
				ioContext.getAuthorizationUtil().checkEntitlement(orgWide, readPerm, g));
		}
		assertTrue("Book 1's own-group set is empty, so the negative assertions above proved nothing", own > 0);

		/// No NEW Delete grant naming a PB role on the shared /Library corpora. Asserted against roles
		/// created in this case, so setEntitlement's add-only history cannot make it pass or fail for a
		/// reason unrelated to the code under test.
		if(sharedIds.isEmpty()) {
			logger.warn("No shared " + LibraryUtil.basePath + " groups in this organization; skipping the Delete leg");
			return;
		}
		int probed = 0;
		for(BaseRecord g : groups1) {
			if(!sharedIds.contains((Long) g.get(FieldNames.FIELD_ID))) {
				continue;
			}
			assertFalse("A newly created per-book role must NOT hold Delete on the shared corpus "
				+ g.get(FieldNames.FIELD_NAME) + " - the world pass grants CRU there, never Delete",
				ioContext.getAuthorizationUtil().checkEntitlement(role2, deletePerm, g));
			assertFalse("...and neither must its Admin role",
				ioContext.getAuthorizationUtil().checkEntitlement(admin2, deletePerm, g));
			probed++;
		}
		assertTrue("No shared group was probed, so the Delete leg proved nothing", probed > 0);
		logger.info("CASE 7 - probed " + own + " own world group(s) and " + probed
			+ " shared /Library group(s) against two freshly created book role pairs");
	}

	/** The ids of the shared {@code /Library} corpora, which every world pass grants on legitimately. */
	private List<Long> libraryChildIds(OrganizationContext o) {
		List<Long> ids = new ArrayList<>();
		BaseRecord library = ioContext.getPathUtil().findPath(o.getAdminUser(), ModelNames.MODEL_GROUP,
			LibraryUtil.basePath, GroupEnumType.DATA.toString(), o.getOrganizationId());
		if(library == null) {
			return ids;
		}
		for(BaseRecord g : IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(
				ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, library.get(FieldNames.FIELD_ID),
				o.getOrganizationId()))) {
			ids.add((Long) g.get(FieldNames.FIELD_ID));
		}
		return ids;
	}

	// ───────── 8: the role-hierarchy direction test (ratified) ─────────

	/**
	 * <b>§5.3's verification test 1, finally run.</b> Designated a phase-1 one-run settlement and never
	 * executed; §10 Q10 (per-book grant scale) depends on the answer, and it may also confirm or refute
	 * the §5.3 suspicion that ISO42001's role-to-role wiring is inert.
	 * <p>
	 * <b>Both directions are measured</b>, because "does hierarchy work at all" and "which way does it
	 * flow" are different questions and only the pair is actionable:
	 * <ol>
	 * <li><b>grant to PARENT, member of CHILD</b> - the direction the ratified test names;</li>
	 * <li><b>grant to CHILD, member of PARENT</b> - the inverse.</li>
	 * </ol>
	 * The verdict is asserted, not merely logged, so the answer is pinned against a future change to
	 * {@code roles_to_leaf} or the effective-role views.
	 * <p>
	 * Construction notes that make the result evidence rather than coincidence: the scratch group and the
	 * record in it are owned by the <b>olio principal</b> and the record is written with
	 * {@code RecordUtil.createRecord} (no PBAC), so the probing user's only possible route is the role
	 * entitlement - not ownership, not group ownership. Two independent role trees are used so one leg's
	 * grant cannot satisfy the other. {@code CacheUtil.clearCache()} runs before each probe.
	 */
	@Test
	public void case08_roleHierarchyInheritanceDirection() {
		OrganizationContext o = org();
		long orgId = o.getOrganizationId();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("No olio principal - create any Olio context first", olioUser);
		String tag = UUID.randomUUID().toString().substring(0, 8);

		/// LEG 1 - grant on the PARENT role, membership in the CHILD role only.
		boolean parentGrantChildMember = probeHierarchy(olioUser, orgId, "PbHierA-" + tag, true);
		/// LEG 2 - grant on the CHILD role, membership in the PARENT role only.
		boolean childGrantParentMember = probeHierarchy(olioUser, orgId, "PbHierB-" + tag, false);

		logger.info("ROLE HIERARCHY DIRECTION (measured on am7db, " + tag + "):"
			+ "\n  grant on PARENT + member of CHILD  -> AccessPoint permits = " + parentGrantChildMember
			+ "\n  grant on CHILD  + member of PARENT -> AccessPoint permits = " + childGrantParentMember);

		assertFalse("Measured: a permission granted to a PARENT role is NOT inherited by a member of the"
			+ " CHILD role. Entitlement resolution joins the grant-holding role against the set of roles"
			+ " REACHABLE DOWNWARD from the role the actor is a member of (effectiveActorRoles emits"
			+ " roles_to_leaf(membershipRole).leafid; effectiveRoles emits the leaf that holds the grant),"
			+ " so membership flows DOWN the tree and grants do not flow UP it."
			+ " If this assertion fails, the direction changed and §10 Q10 must be revisited.",
			parentGrantChildMember);
		assertTrue("Measured: a permission granted to a CHILD role IS inherited by a member of the PARENT"
			+ " role. This is the usable direction: enrol high, grant low.", childGrantParentMember);
	}

	/**
	 * One direction of {@link #case08_roleHierarchyInheritanceDirection()}.
	 *
	 * @param grantOnParent true to grant on the parent role and enrol in the child, false for the inverse
	 * @return whether {@code AccessPoint} let the probing user read the record
	 */
	private boolean probeHierarchy(BaseRecord olioUser, long orgId, String base, boolean grantOnParent) {
		String rolePath = "~/Roles/" + base;
		BaseRecord parent = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, rolePath + "/Parent",
			RoleEnumType.USER.toString(), orgId);
		BaseRecord child = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE,
			rolePath + "/Parent/Child", RoleEnumType.USER.toString(), orgId);
		assertNotNull("Failed to create the parent role", parent);
		assertNotNull("Failed to create the child role", child);
		/// Typed locals: assertEquals(x.get(..), y.get(..)) infers Object[] from the generic get() and
		/// binds the deprecated assertEquals(Object[], Object[]) overload, which ClassCastExceptions.
		Long parentId = parent.get(FieldNames.FIELD_ID);
		Long childParentId = child.get(FieldNames.FIELD_PARENT_ID);
		assertEquals("The child role must actually be a child of the parent, or this measures nothing",
			parentId, childParentId);

		/// Owned by the olio principal, so the probing user has no ownership route to it.
		String groupPath = "~/" + base;
		BaseRecord group = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, groupPath,
			GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Failed to create the scratch group", group);
		String recName = base + "-record";
		BaseRecord rec = newData(olioUser, recName, "text/plain", "hierarchy probe".getBytes(), groupPath, orgId);
		assertTrue("Failed to create the probe record", ioContext.getRecordUtil().createRecord(rec));

		BaseRecord grantTo = (grantOnParent ? parent : child);
		BaseRecord enrolIn = (grantOnParent ? child : parent);
		ioContext.getAuthorizationUtil().setEntitlement(org().getAdminUser(), grantTo,
			new BaseRecord[] {group}, new String[] {"Read"},
			new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});

		BaseRecord probe = user("pbHier" + base.substring(base.length() - 6));
		assertTrue("Failed to enrol the probe user",
			ioContext.getMemberUtil().member(olioUser, enrolIn, probe, null, true));
		assertTrue("Precondition: the probe user must be a DIRECT member of the enrolment role",
			ioContext.getMemberUtil().isMember(probe, enrolIn, null));

		CacheUtil.clearCache();
		/// The grant landed on grantTo, so a direct entitlement check against grantTo is the control:
		/// if this is false the setEntitlement call itself failed and neither leg means anything.
		assertTrue("Control: the grant must exist on the role it was granted to",
			ioContext.getAuthorizationUtil().checkEntitlement(grantTo, readPermission(), group));

		BaseRecord read = ioContext.getAccessPoint().findByNameInGroup(probe, ModelNames.MODEL_DATA,
			(long) group.get(FieldNames.FIELD_ID), recName);
		return read != null;
	}

	// ───────── 9: the create ordering works in an organization with no Olio history ─────────

	/**
	 * {@code PbBookUtil.createBook} writes the book row <b>before</b> the world (ratification 7), which
	 * means it pre-creates the {@code /Olio/Universes/Books/Worlds/{slug}/Book} group skeleton ahead of
	 * the universe and world <i>records</i>. In an organization that has never held an Olio context, that
	 * skeleton does not exist at all, so this is the case where the ordering could break the
	 * universe/world bootstrap - and the only way to know is to run it.
	 * <p>
	 * A brand-new organization is deliberate and its name is randomly suffixed: the first-run bootstrap
	 * runs once per organization and is unreachable in a pre-existing test organization, so a re-run
	 * landing on an already-bootstrapped organization would make this pass for the wrong reason.
	 */
	@Test
	public void case09_createBookWorksInAnOrganizationWithNoOlioHistory() {
		String orgPath = "/Development/PB2C-" + UUID.randomUUID().toString().substring(0, 8);
		OrganizationContext fresh = getTestOrganization(orgPath);
		assertNotNull("Failed to create a fresh organization", fresh);
		long orgId = fresh.getOrganizationId();
		assertNull("Precondition: a fresh organization must have no olio principal, or the first-run"
			+ " bootstrap this case exercises is unreachable",
			ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId));

		BaseRecord u = ioContext.getFactory().getCreateUser(fresh.getAdminUser(), "pbFreshOrgUser", orgId);
		assertNotNull(u);
		String slug = "pb2c-fresh";

		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "Fresh org book");
		assertNotNull("createBook must work in an organization with no Olio history", bk);
		assertNotNull("The book must carry its world FK after the patch step",
			bk.get(OlioFieldNames.FIELD_PB_WORLD));
		assertEquals(slug, bk.get(OlioFieldNames.FIELD_PB_SLUG));

		/// And the world it built must be fully authorized, not merely present.
		try {
			OlioContext ctx = PbOlioContextUtil.getCreateBookContext(u, dataPath(), slug);
			assertTrue("The book world must complete authorization", ctx.isAuthorizationConfigured());
			assertNotNull(ctx.getUniverse());
			assertNotNull(ctx.getWorld());
		}
		catch(OlioException e) {
			fail("Re-opening the fresh-org book failed: " + e.getMessage());
		}

		/// The workflow/artifact groups the pre-created skeleton did not include must exist too, created
		/// by BookWorldInitializationRule, and be writable by the creator.
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("The creator must be able to write into the Workflow group of a fresh-org book", wf);
		BaseRecord n = PbGraphUtil.addNode(u, wf, "fresh-node", PbNodeTypeEnumType.SOURCE_TEXT,
			PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull(n);
		assertNotNull("And into the Artifacts group",
			PbArtifactUtil.persistArtifact(u, n, "sourceText", PbArtifactTypeEnumType.TEXT,
				PbBookUtil.artifactGroupPath(slug), null, "fresh org body", null, null));

		/// A duplicate slug in the same organization must still be refused - the serialization point has
		/// to work on the fresh path too.
		try {
			PbBookUtil.createBook(u, dataPath(), slug, "Duplicate");
			fail("A duplicate slug must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
		}
		logger.info("CASE 9 - createBook's ratification-7 ordering works in a virgin organization ("
			+ orgPath + "), including the duplicate-slug refusal");
	}

	// ───────── 10: no PB path reaches a destructive Olio utility ─────────

	/**
	 * §9's last Objects7-level leg, as a source-level assertion because it is a statement about what the
	 * code can reach rather than about a runtime value. Asserted over the six PB2 utilities plus
	 * {@code PbOlioContextUtil}: none may name {@code WorldUtil.cleanupLocation} or
	 * {@code getWriter().delete(Query)}, either of which would let a book operation delete by query.
	 */
	@Test
	public void case10_noPbUtilityReachesADestructiveOlioPath() throws Exception {
		String base = "src/main/java/org/cote/accountmanager/olio/picturebook/";
		List<String> files = Arrays.asList("PbBookUtil.java", "PbGraphUtil.java", "PbArtifactUtil.java",
			"PbConfigUtil.java", "PbWatchedFields.java", "PbSharingUtil.java", "PbOlioContextUtil.java");
		List<String> offenders = new ArrayList<>();
		for(String f : files) {
			java.nio.file.Path p = java.nio.file.Paths.get(base + f);
			assertTrue("Expected " + p + " to exist - if a utility was renamed this case must be updated,"
				+ " not silently skipped", java.nio.file.Files.exists(p));
			String src = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
			if(src.contains("cleanupLocation")) {
				offenders.add(f + " -> cleanupLocation");
			}
			if(src.contains("getWriter().delete(")) {
				offenders.add(f + " -> getWriter().delete(");
			}
		}
		assertTrue("No PB2 utility may reach a destructive Olio path: " + offenders, offenders.isEmpty());
	}
}
