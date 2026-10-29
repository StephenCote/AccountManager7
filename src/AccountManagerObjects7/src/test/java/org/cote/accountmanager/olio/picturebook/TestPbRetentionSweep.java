package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 6, P6-3b: the retention sweep ({@link PbRetentionUtil} over {@link PbRetentionSelector})
 * and {@link PbArtifactUtil#archive}, through the {@link PbServiceFacade} retention surface.
 * <p>
 * Live {@code am7db}. No SD server is involved: the attempts are byte-free TEXT artifacts (case01, 03, 04) or
 * IMAGE artifacts whose {@code data.data} bytes are written directly by the test (case02), so that the
 * "delete bytes" leg is exercised against real rows. The book is this test's own fixture ({@link #SLUG}, find or
 * create) rather than the shared {@code pb2c-graph} one: {@code apply} writes to every node of the book it is
 * run against, and this test must not archive artifacts the review and node-run tests left behind. Every node
 * has a random handle and every assertion over a sweep is scoped to the node the case created
 * ({@link #itemsOfNode}), because the book accumulates nodes across runs.
 * <p>
 * Not covered here, honestly: the per-item {@code denied} outcome (a user who may update the book but not one of
 * its artifacts) cannot be built from this fixture, and the organization-wide apply is never run - it needs an
 * AccountAdministrators member, and the only one the fixture has is the admin user, which tests never use.
 */
public class TestPbRetentionSweep extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	/// Same read-only principal as TestPbReview / TestPbNodeRun.case06: never enrolled in any per-book role.
	private static final String READER_USER = "p13ReadOnly";
	private static final String SLUG = "pb63-retention";
	private static final String ROLE = "portrait0";

	@Before
	public void retentionSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── case01: scan is read-only, apply archives ───────────────────────────────

	/**
	 * Five attempts, the second approved, K=2: the two newest attempts and the approval are kept and the other two
	 * are archived. The dry run writes nothing; the apply stamps {@code ARCHIVED} + {@code archivedAt} and clears
	 * {@code approvedBy}; a rescan reports the archived TEXT artifacts as "bytes already deleted" (nothing to
	 * reclaim) and never touches the approval.
	 */
	@Test
	public void case01_scanIsReadOnlyAndApplyArchivesOlderAttempts() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p63-c01");

		BaseRecord a1 = textAttempt(u, n, "c01 first");
		BaseRecord a2 = textAttempt(u, n, "c01 second");
		BaseRecord a3 = textAttempt(u, n, "c01 third");
		BaseRecord a4 = textAttempt(u, n, "c01 fourth");
		BaseRecord a5 = textAttempt(u, n, "c01 fifth");
		assertTrue("the newest attempt is selected while nothing is approved", selected(a5));
		BaseRecord approved = PbArtifactUtil.approve(u, reread(u, a2, orgId), u.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
		assertTrue("approve selects", selected(approved));

		/// Dry run.
		Map<String, Object> scan = PbServiceFacade.scanRetention(u, bookOid, Integer.valueOf(2), Integer.valueOf(30));
		assertEquals("book", scan.get("scope"));
		assertEquals(bookOid, scan.get("bookObjectId"));
		assertEquals(Integer.valueOf(2), scan.get("keepAttempts"));
		assertEquals(Integer.valueOf(30), scan.get("deleteBytesAfterDays"));
		assertNotNull("a scan carries checkedAt", scan.get("checkedAt"));
		assertNull("a scan is not an apply", scan.get("appliedAt"));
		assertNull("a scan reports no outcomes", scan.get("archived"));
		List<Map<String, Object>> items = itemsOfNode(scan, n);
		assertEquals("one item per artifact of the node", 5, items.size());
		assertItem(items, a5, "KEEP", "within the last 2 attempt(s)");
		assertItem(items, a4, "KEEP", "within the last 2 attempt(s)");
		assertItem(items, a2, "KEEP", "approved");
		assertItem(items, a3, "ARCHIVE", "generated, older than the kept attempts");
		assertItem(items, a1, "ARCHIVE", "generated, older than the kept attempts");
		Map<String, Object> i1 = byObjectId(items, a1);
		assertEquals(Boolean.FALSE, i1.get("hasBytes"));
		assertNull("a TEXT artifact has no byteLength", i1.get("byteLength"));
		assertNull("not archived yet", i1.get("archivedAt"));
		assertNull("a scan item carries no outcome", i1.get("outcome"));
		/// Nothing was written.
		BaseRecord a1Fresh = reread(u, a1, orgId);
		assertEquals("a dry run writes nothing", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a1Fresh));
		assertNull(PbArtifactUtil.archivedAtOf(a1Fresh));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, a3, orgId)));

		/// Apply.
		ZonedDateTime before = ZonedDateTime.now().minusSeconds(5);
		Map<String, Object> apply = PbServiceFacade.applyRetention(u, bookOid, Integer.valueOf(2), Integer.valueOf(30));
		assertNotNull("an apply carries appliedAt", apply.get("appliedAt"));
		assertNull(apply.get("checkedAt"));
		items = itemsOfNode(apply, n);
		assertEquals(5, items.size());
		assertEquals(PbRetentionUtil.OUTCOME_ARCHIVED, byObjectId(items, a1).get("outcome"));
		assertEquals(PbRetentionUtil.OUTCOME_ARCHIVED, byObjectId(items, a3).get("outcome"));
		assertEquals(PbRetentionUtil.OUTCOME_KEPT, byObjectId(items, a2).get("outcome"));
		assertEquals(PbRetentionUtil.OUTCOME_KEPT, byObjectId(items, a4).get("outcome"));
		assertEquals(PbRetentionUtil.OUTCOME_KEPT, byObjectId(items, a5).get("outcome"));
		assertTrue("the apply counts its archives", ((Number) apply.get("archived")).intValue() >= 2);
		assertEquals("nothing was refused on this node", 0, refusedOf(items));
		for(BaseRecord a : new BaseRecord[] {a1, a3}) {
			BaseRecord fresh = reread(u, a, orgId);
			assertEquals(a.get(FieldNames.FIELD_NAME) + " is ARCHIVED", PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(fresh));
			ZonedDateTime stamp = PbArtifactUtil.archivedAtOf(fresh);
			assertNotNull("archivedAt is stamped", stamp);
			assertTrue("archivedAt is now, not epoch", stamp.isAfter(before));
			assertNull("an archived artifact carries no approval", fresh.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertFalse(selected(fresh));
		}
		BaseRecord a2Fresh = reread(u, a2, orgId);
		assertEquals("the approval is untouched", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a2Fresh));
		assertTrue(selected(a2Fresh));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, a4, orgId)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, a5, orgId)));

		/// Rescan: the archived TEXT artifacts have no bytes to delete, ever.
		Map<String, Object> rescan = PbServiceFacade.scanRetention(u, bookOid, Integer.valueOf(2), Integer.valueOf(0));
		items = itemsOfNode(rescan, n);
		assertItem(items, a1, "NONE", "archived, bytes already deleted");
		assertItem(items, a3, "NONE", "archived, bytes already deleted");
		assertNotNull("the stamp is reported", byObjectId(items, a1).get("archivedAt"));
		assertItem(items, a2, "KEEP", "approved");
	}

	// ─────────────────────────────── case02: bytes are deleted after the grace period ───────────────────────────────

	/**
	 * Four IMAGE attempts with real {@code data.data} rows, K=1: the apply archives the three older ones; with
	 * D=30 their bytes are "due" and left alone; with D=0 the scan reports them reclaimable (sum of the recorded
	 * {@code byteLength}s) and the apply deletes the {@code data.data} rows as the caller, clears the artifacts'
	 * {@code data} references and keeps the artifact rows and their metrics. The selected attempt keeps its bytes.
	 */
	@Test
	public void case02_bytesAreDeletedAfterTheGracePeriodAndTheRowIsKept() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p63-c02");

		int[] sizes = new int[] {1000, 2000, 3000, 4000};
		BaseRecord[] a = new BaseRecord[4];
		long[] dataIds = new long[4];
		for(int i = 0; i < 4; i++) {
			a[i] = imageAttempt(u, n, "c02-" + i, bytesOf(sizes[i], (byte) (i + 1)));
			dataIds[i] = PbHealthUtil.fkId(a[i], OlioFieldNames.FIELD_PB_DATA);
			assertTrue("attempt " + i + " references its data.data row", dataIds[i] > 0L);
			assertEquals("byteLength is recorded", sizes[i], PbRetentionUtil.byteLength(reread(u, a[i], orgId)));
		}
		assertTrue("the newest attempt is selected", selected(reread(u, a[3], orgId)));

		/// Archive the three older attempts.
		Map<String, Object> apply = PbServiceFacade.applyRetention(u, bookOid, Integer.valueOf(1), Integer.valueOf(30));
		List<Map<String, Object>> items = itemsOfNode(apply, n);
		assertEquals(4, items.size());
		for(int i = 0; i < 3; i++) {
			assertEquals("attempt " + i + " archived", PbRetentionUtil.OUTCOME_ARCHIVED, byObjectId(items, a[i]).get("outcome"));
			assertEquals(PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(reread(u, a[i], orgId)));
		}
		assertItem(items, a[3], "KEEP", "selected");

		/// Within the grace period the bytes are due, not deleted.
		Map<String, Object> due = PbServiceFacade.scanRetention(u, bookOid, Integer.valueOf(1), Integer.valueOf(30));
		items = itemsOfNode(due, n);
		for(int i = 0; i < 3; i++) {
			Map<String, Object> it = byObjectId(items, a[i]);
			assertEquals("NONE", it.get("action"));
			assertTrue(String.valueOf(it.get("reason")), String.valueOf(it.get("reason")).startsWith("archived, bytes due "));
			assertNotNull("bytesDueAt is reported", it.get("bytesDueAt"));
			assertEquals(Boolean.TRUE, it.get("hasBytes"));
			assertEquals(Long.valueOf(sizes[i]), it.get("byteLength"));
		}
		for(int i = 0; i < 3; i++) {
			assertNotNull("the data.data row is still there", rawData(dataIds[i], orgId));
		}

		/// After the grace period (D=0) the scan reports them reclaimable.
		Map<String, Object> scan = PbServiceFacade.scanRetention(u, bookOid, Integer.valueOf(1), Integer.valueOf(0));
		items = itemsOfNode(scan, n);
		for(int i = 0; i < 3; i++) {
			assertItem(items, a[i], "DELETE_BYTES", "archived 0+ day(s) ago");
		}
		assertItem(items, a[3], "KEEP", "selected");
		assertTrue("bytesReclaimable counts the three archived attempts: " + scan.get("bytesReclaimable"),
			((Number) scan.get("bytesReclaimable")).longValue() >= 1000L + 2000L + 3000L);
		for(int i = 0; i < 3; i++) {
			assertNotNull("a scan deletes nothing", rawData(dataIds[i], orgId));
		}

		/// ...and the apply deletes them.
		apply = PbServiceFacade.applyRetention(u, bookOid, Integer.valueOf(1), Integer.valueOf(0));
		items = itemsOfNode(apply, n);
		long freed = 0L;
		for(int i = 0; i < 3; i++) {
			Map<String, Object> it = byObjectId(items, a[i]);
			assertEquals("attempt " + i + ": " + it.get("outcomeReason"), PbRetentionUtil.OUTCOME_BYTES_DELETED, it.get("outcome"));
			assertEquals(Long.valueOf(sizes[i]), it.get("bytesFreed"));
			freed += sizes[i];
			assertNull("the data.data row is gone", rawData(dataIds[i], orgId));
			BaseRecord fresh = reread(u, a[i], orgId);
			assertFalse("the artifact's data reference is cleared", PbRetentionUtil.hasBytes(fresh));
			assertEquals("the artifact row and its metrics are kept", sizes[i], PbRetentionUtil.byteLength(fresh));
			assertEquals(PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(fresh));
			assertNotNull(PbArtifactUtil.archivedAtOf(fresh));
		}
		assertTrue("bytesFreed sums the deleted rows: " + apply.get("bytesFreed"), ((Number) apply.get("bytesFreed")).longValue() >= freed);
		assertTrue(((Number) apply.get("bytesDeleted")).intValue() >= 3);
		assertEquals(PbRetentionUtil.OUTCOME_KEPT, byObjectId(items, a[3]).get("outcome"));
		assertNotNull("the selected attempt keeps its bytes", rawData(dataIds[3], orgId));
		assertTrue(PbRetentionUtil.hasBytes(reread(u, a[3], orgId)));

		/// Idempotent: nothing left to do on this node.
		Map<String, Object> again = PbServiceFacade.scanRetention(u, bookOid, Integer.valueOf(1), Integer.valueOf(0));
		items = itemsOfNode(again, n);
		for(int i = 0; i < 3; i++) {
			assertItem(items, a[i], "NONE", "archived, bytes already deleted");
			assertEquals(Boolean.FALSE, byObjectId(items, a[i]).get("hasBytes"));
		}
	}

	// ─────────────────────────────── case03: archive refuses the selection and approvals ───────────────────────────────

	/**
	 * {@link PbArtifactUtil#archive} is refused with 409 for the chain's selection and for an APPROVED artifact,
	 * archives a GENERATED unselected one (stamp set, approval cleared), and is idempotent (the stamp survives a
	 * second call).
	 */
	@Test
	public void case03_archiveRefusesSelectedAndApprovedAndIsIdempotent() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		BaseRecord n = node(u, wf, "p63-c03");

		BaseRecord a1 = textAttempt(u, n, "c03 first");
		BaseRecord a2 = textAttempt(u, n, "c03 second");
		assertTrue(selected(a2));
		try {
			PbArtifactUtil.archive(u, reread(u, a2, orgId));
			fail("the selected revision cannot be archived");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
		}
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, a2, orgId)));

		BaseRecord approved = PbArtifactUtil.approve(u, reread(u, a1, orgId), u.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
		try {
			PbArtifactUtil.archive(u, approved);
			fail("an APPROVED artifact cannot be archived");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
		}
		BaseRecord a1Fresh = reread(u, a1, orgId);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a1Fresh));
		assertTrue(selected(a1Fresh));

		/// a2 is now GENERATED and unselected (approving a1 moved the selection).
		BaseRecord a2Fresh = reread(u, a2, orgId);
		assertFalse(selected(a2Fresh));
		ZonedDateTime before = ZonedDateTime.now().minusSeconds(5);
		BaseRecord archived = PbArtifactUtil.archive(u, a2Fresh);
		assertEquals(PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(archived));
		ZonedDateTime stamp = PbArtifactUtil.archivedAtOf(archived);
		assertNotNull(stamp);
		assertTrue(stamp.isAfter(before));
		assertNull(archived.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertFalse(selected(archived));

		BaseRecord again = PbArtifactUtil.archive(u, reread(u, a2, orgId));
		assertEquals(PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(again));
		assertEquals("a second archive keeps the original stamp", stamp.toInstant(), PbArtifactUtil.archivedAtOf(again).toInstant());

		/// The approval still stands and still is the selection.
		BaseRecord current = PbArtifactUtil.findSelected(u, n, ROLE);
		assertNotNull(current);
		String a1Oid = a1.get(FieldNames.FIELD_OBJECT_ID);
		String currentOid = current.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(a1Oid, currentOid);
	}

	// ─────────────────────────────── case04: policy bounds and the gates ───────────────────────────────

	/**
	 * {@link PbRetentionUtil.Policy#of} rejects out-of-range values with 400 and fills nulls with the defaults; the
	 * facade 400s an invalid policy, 404s an unknown book, 403s an organization-wide scan by a non-administrator
	 * and 403s an apply by a reader who may not update the book (the scan is open to that reader).
	 */
	@Test
	public void case04_policyBoundsAndGates() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p63-c04");
		BaseRecord only = textAttempt(u, n, "c04 only");

		PbRetentionUtil.Policy defaults = PbRetentionUtil.Policy.of(null, null);
		assertEquals(PbRetentionSelector.DEFAULT_KEEP_ATTEMPTS, defaults.keepAttempts);
		assertEquals(PbRetentionSelector.DEFAULT_DELETE_BYTES_AFTER_DAYS, defaults.deleteBytesAfterDays);
		PbRetentionUtil.Policy edge = PbRetentionUtil.Policy.of(Integer.valueOf(PbRetentionSelector.MAX_KEEP_ATTEMPTS), Integer.valueOf(0));
		assertEquals(PbRetentionSelector.MAX_KEEP_ATTEMPTS, edge.keepAttempts);
		assertEquals(0, edge.deleteBytesAfterDays);
		Integer[][] bad = new Integer[][] {
			{Integer.valueOf(0), null},
			{null, Integer.valueOf(-1)},
			{Integer.valueOf(PbRetentionSelector.MAX_KEEP_ATTEMPTS + 1), null},
			{null, Integer.valueOf(PbRetentionSelector.MAX_DELETE_BYTES_AFTER_DAYS + 1)}
		};
		for(Integer[] b : bad) {
			try {
				PbRetentionUtil.Policy.of(b[0], b[1]);
				fail("Policy.of(" + b[0] + ", " + b[1] + ") must be refused");
			}
			catch(PictureBookException e) {
				assertEquals(400, e.getStatus());
			}
		}

		try {
			PbServiceFacade.scanRetention(u, bookOid, Integer.valueOf(0), Integer.valueOf(30));
			fail("keepAttempts=0 is a 400");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
		}
		try {
			PbServiceFacade.applyRetention(u, bookOid, Integer.valueOf(3), Integer.valueOf(-1));
			fail("deleteBytesAfterDays=-1 is a 400");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
		}
		try {
			PbServiceFacade.scanRetention(u, UUID.randomUUID().toString(), null, null);
			fail("an unknown book is a 404");
		}
		catch(PictureBookException e) {
			assertEquals(404, e.getStatus());
		}
		try {
			PbServiceFacade.scanRetention(null, bookOid, null, null);
			fail("no principal is a 401");
		}
		catch(PictureBookException e) {
			assertEquals(401, e.getStatus());
		}

		assertFalse("precondition: the test user must not be an AccountAdministrators member, or the 403 proves nothing",
			PbHealthUtil.isAccountAdministrator(u, orgId));
		try {
			PbServiceFacade.scanRetentionOrg(u, null, null);
			fail("an organization-wide scan by a non-administrator is a 403");
		}
		catch(PictureBookException e) {
			assertEquals(403, e.getStatus());
		}
		try {
			PbServiceFacade.applyRetentionOrg(u, null, null);
			fail("an organization-wide apply by a non-administrator is a 403");
		}
		catch(PictureBookException e) {
			assertEquals(403, e.getStatus());
		}

		/// A reader of the book may scan it but not apply. Unlike the sibling tests' reader, this one also needs
		/// READ on the {container}/Workflow and {container}/Artifacts groups: scanRetention resolves the workflow
		/// (404 without it) and walks the artifacts, so a reader holding only the Book group would never reach
		/// the scan at all.
		BaseRecord reader = readOnlyReader(u, bk, wf, only);
		Map<String, Object> scan = PbServiceFacade.scanRetention(reader, bookOid, null, null);
		assertEquals("the reader sees the book", bookOid, scan.get("bookObjectId"));
		assertItem(itemsOfNode(scan, n), only, "KEEP", "selected");
		try {
			PbServiceFacade.applyRetention(reader, bookOid, null, null);
			fail("an apply by a reader who may not update the book is a 403");
		}
		catch(PictureBookException e) {
			assertEquals(403, e.getStatus());
		}
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("organization " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("user " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", p);
		return p;
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 6 retention fixture");
		}
		assertNotNull("book " + SLUG, bk);
		return bk;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(SLUG), 0);
		assertNotNull("node " + handle, n);
		return n;
	}

	/** A byte-free TEXT attempt (its own attempt id, variant 0) in the fixture's chain for {@link #ROLE}. */
	private BaseRecord textAttempt(BaseRecord u, BaseRecord n, String text) {
		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, ROLE, PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p6-3b " + text, null, null, null, 0, UUID.randomUUID().toString(), true);
		assertNotNull("artifact " + text, a);
		return a;
	}

	/** An IMAGE attempt whose bytes are a real {@code data.data} row created as the user, with its byteLength recorded. */
	private BaseRecord imageAttempt(BaseRecord u, BaseRecord n, String tag, byte[] bytes) throws Exception {
		String groupPath = PbBookUtil.artifactGroupPath(SLUG);
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, image, "p6-3b-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + ".bin", groupPath, orgOf(u));
		image.set(FieldNames.FIELD_CONTENT_TYPE, "application/octet-stream");
		ByteModelUtil.setValue(image, bytes);
		BaseRecord data = ioContext.getAccessPoint().create(u, image);
		assertNotNull("data " + tag, data);
		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, ROLE, PbArtifactTypeEnumType.IMAGE,
			groupPath, data, null, null, null, null, 0, UUID.randomUUID().toString(), true);
		assertNotNull("artifact " + tag, a);
		assertTrue("metrics " + tag, PbArtifactUtil.recordImageMetrics(u, a, bytes, "application/octet-stream", 0, 0, null));
		return a;
	}

	private static byte[] bytesOf(int length, byte fill) {
		byte[] b = new byte[length];
		java.util.Arrays.fill(b, fill);
		return b;
	}

	/**
	 * Same shape as TestPbReview.readOnlyReader - direct READ grants on the book group and its projected
	 * foreigns - plus READ on the groups of {@code wf} ({container}/Workflow) and {@code artifact}
	 * ({container}/Artifacts), which a retention scan walks and the sibling tests' readers never needed.
	 * Still no update anywhere: the precondition that the reader cannot update the book is asserted at the
	 * call site through the 403.
	 */
	private BaseRecord readOnlyReader(BaseRecord owner, BaseRecord bk, BaseRecord wf, BaseRecord artifact) {
		OrganizationContext org = getTestOrganization(ORG_A);
		long orgId = orgOf(owner);
		BaseRecord admin = org.getAdminUser();
		BaseRecord reader = ioContext.getFactory().getCreateUser(admin, READER_USER, orgId);
		assertNotNull("reader " + READER_USER, reader);
		assertFalse("precondition: the reader must not be a group administrator, or the 403 proves nothing",
			ioContext.getAuthorizationUtil().isModelAdministrator(ModelNames.MODEL_GROUP, reader));

		BaseRecord readGroup = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		BaseRecord readData = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read/GROUP permission", readGroup);
		assertNotNull("Read/DATA permission", readData);
		for(BaseRecord holder : new BaseRecord[] { bk, wf, artifact }) {
			long gid = ((Number) holder.get(FieldNames.FIELD_GROUP_ID)).longValue();
			BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
			assertNotNull("the group of " + holder.get(FieldNames.FIELD_NAME) + " must be readable by its owner", dir);
			ioContext.getMemberUtil().member(admin, dir, reader, readGroup, true);
			ioContext.getMemberUtil().member(admin, dir, reader, readData, true);
		}
		for(String f : new String[] { OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_SERIES, OlioFieldNames.FIELD_PB_CHAPTER,
				OlioFieldNames.FIELD_PB_SOURCE_DATA, OlioFieldNames.FIELD_PB_SD_CONFIG, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG }) {
			Object v = bk.get(f);
			if(v instanceof BaseRecord) {
				ioContext.getMemberUtil().member(admin, (BaseRecord) v, reader, readData, true);
			}
		}
		CacheUtil.clearCache();
		return reader;
	}

	// ─────────────────────────────── reads (all uncached) ───────────────────────────────

	private BaseRecord reread(BaseRecord u, BaseRecord a, long orgId) {
		BaseRecord fresh = PbArtifactUtil.readArtifact(u, a.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("artifact " + a.get(FieldNames.FIELD_NAME) + " must still be readable", fresh);
		return fresh;
	}

	/** The {@code data.data} row by id, raw (no PBAC), uncached: null when the row is gone. */
	private static BaseRecord rawData(long dataId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, Long.valueOf(dataId));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME});
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private static boolean selected(BaseRecord a) {
		Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
		return sel != null && sel.booleanValue();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> itemsOfNode(Map<String, Object> sweep, BaseRecord n) {
		Object items = sweep.get("items");
		assertNotNull("the sweep carries items", items);
		String oid = n.get(FieldNames.FIELD_OBJECT_ID);
		List<Map<String, Object>> out = new ArrayList<>();
		for(Map<String, Object> it : (List<Map<String, Object>>) items) {
			if(oid.equals(it.get("nodeObjectId"))) {
				out.add(it);
			}
		}
		return out;
	}

	private static Map<String, Object> byObjectId(List<Map<String, Object>> items, BaseRecord a) {
		String oid = a.get(FieldNames.FIELD_OBJECT_ID);
		for(Map<String, Object> it : items) {
			if(oid.equals(it.get("objectId"))) {
				return it;
			}
		}
		fail("no sweep item for " + a.get(FieldNames.FIELD_NAME) + " (" + oid + ")");
		return null;
	}

	private static void assertItem(List<Map<String, Object>> items, BaseRecord a, String action, String reason) {
		Map<String, Object> it = byObjectId(items, a);
		String name = a.get(FieldNames.FIELD_NAME);
		assertEquals(name + " action", action, it.get("action"));
		assertEquals(name + " reason", reason, it.get("reason"));
	}

	private static int refusedOf(List<Map<String, Object>> items) {
		int count = 0;
		for(Map<String, Object> it : items) {
			if(PbRetentionUtil.OUTCOME_REFUSED.equals(it.get("outcome"))) {
				count++;
			}
		}
		return count;
	}
}
