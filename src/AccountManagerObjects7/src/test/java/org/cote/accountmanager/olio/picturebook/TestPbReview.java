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
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-4: the review state machine of design §10 rules 1-4 over
 * {@link PbArtifactUtil#persistArtifact}, {@link PbArtifactUtil#select}, {@link PbArtifactUtil#approve},
 * {@link PbArtifactUtil#reject}, and the {@link PbServiceFacade} review surface (select/approve/reject
 * gates, review queue and its {@code auto-approved} filter).
 * <p>
 * Live {@code am7db}; every artifact is a byte-free TEXT artifact (the state machine never reads bytes), so
 * no SD server is involved. The shared {@code pb2c-graph} fixture book is used, as {@code TestPbGraph} and
 * {@code TestPbNodeRun} do; every node has a random handle and every review-queue assertion is scoped to
 * the nodes this test created, never to a global count, because other tests leave artifacts in the same
 * workflow.
 */
public class TestPbReview extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	/// Same read-only principal as TestPbNodeRun.case06: never enrolled in any per-book role by any test.
	private static final String READER_USER = "p13ReadOnly";
	private static final String SLUG = "pb2c-graph";
	/// A second book of the same owner (TestPbSecurity's fixture), for the cross-book 404.
	private static final String OTHER_SLUG = "pb2c-sec-a";
	private static final String ROLE = "portrait0";

	@Before
	public void reviewSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── rule 1: a new attempt never displaces an approval ───────────────────────────────

	/**
	 * With nothing approved every new attempt is auto-selected (the book always has something to show). Once
	 * the selection is APPROVED, a new attempt arrives GENERATED and unselected and {@code findSelected} keeps
	 * returning the approved one.
	 */
	@Test
	public void case01_newAttemptAutoSelectsUnlessSelectionIsApproved() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		BaseRecord n = node(u, wf, "p14-c01");

		BaseRecord a1 = artifact(u, n, "c01 first");
		assertTrue("the first attempt is selected", selected(a1));
		BaseRecord a2 = artifact(u, n, "c01 second");
		assertTrue("the newest attempt is selected while nothing is approved", selected(a2));
		assertFalse("...and the earlier one is not", selected(reread(u, a1, orgId)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a2));

		BaseRecord approved = PbArtifactUtil.approve(u, a2, u.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
		assertTrue(selected(approved));

		BaseRecord a3 = artifact(u, n, "c01 third");
		assertEquals("a new attempt arrives GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a3));
		assertFalse("a new attempt never displaces an APPROVED selection (rule 1)", selected(a3));
		BaseRecord current = PbArtifactUtil.findSelected(u, n, ROLE);
		assertNotNull(current);
		/// Typed locals on purpose (see TestPbGraph): assertEquals(x.get(..), y.get(..)) binds the Object[] overload.
		String a2Oid = a2.get(FieldNames.FIELD_OBJECT_ID);
		String currentOid = current.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("the approved artifact is still the selection", a2Oid, currentOid);
		BaseRecord supersedes = a3.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		assertNotNull("the newcomer records what it supersedes", supersedes);
		Long a2Id = a2.get(FieldNames.FIELD_ID);
		Long supersedesId = supersedes.get(FieldNames.FIELD_ID);
		assertEquals("the newcomer supersedes the approved selection", a2Id, supersedesId);
		assertExactlyOneSelected(u, n);
	}

	// ─────────────────────────────── rule 2: select ───────────────────────────────

	/**
	 * "Use this image" writes {@code selected} only: review status and the node's pin are untouched. When the
	 * chain's current selection is APPROVED the select is refused with 409 and nothing changes.
	 */
	@Test
	public void case02_selectWritesSelectedOnlyAndIsRefusedOverAnApproval() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		BaseRecord n = node(u, wf, "p14-c02");
		BaseRecord a1 = artifact(u, n, "c02 first");
		BaseRecord a2 = artifact(u, n, "c02 second");
		assertTrue(selected(a2));

		BaseRecord sel = PbArtifactUtil.select(u, reread(u, a1, orgId));
		assertTrue("select makes a1 the selection", selected(sel));
		assertFalse(selected(reread(u, a2, orgId)));
		assertEquals("select never touches reviewStatus", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(sel));
		assertFalse("select never pins", pinned(u, n, orgId));
		assertExactlyOneSelected(u, n);

		/// Selecting the current selection again is a no-op, not an error.
		assertTrue(selected(PbArtifactUtil.select(u, reread(u, a1, orgId))));

		PbArtifactUtil.approve(u, reread(u, a1, orgId), u.get(FieldNames.FIELD_OBJECT_ID));
		try {
			PbArtifactUtil.select(u, reread(u, a2, orgId));
			fail("a select must not displace an APPROVED selection");
		}
		catch(PictureBookException pbe) {
			assertEquals("select over an approval → 409", 409, pbe.getStatus());
			assertTrue(pbe.getMessage(), pbe.getMessage().contains("APPROVED"));
		}
		assertTrue("the approved artifact is still selected", selected(reread(u, a1, orgId)));
		assertFalse(selected(reread(u, a2, orgId)));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(reread(u, a1, orgId)));
		/// Re-selecting the approved selection itself is still fine (same record, no displacement).
		assertTrue(selected(PbArtifactUtil.select(u, reread(u, a1, orgId))));
	}

	// ─────────────────────────────── rule 2: approve ───────────────────────────────

	/**
	 * Approve implies select: approving a variant that is not the selection selects it, demotes the other
	 * APPROVED sibling to GENERATED with {@code approvedBy} cleared, pins the node and stamps
	 * {@code approvedBy}/{@code approvedAt}. One APPROVED per (node, role) at all times.
	 */
	@Test
	public void case03_approveImpliesSelectDemotesSiblingPinsAndStamps() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		String uoid = u.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p14-c03");
		BaseRecord a1 = artifact(u, n, "c03 first");
		BaseRecord a2 = artifact(u, n, "c03 second");
		assertTrue("precondition: the newest is selected", selected(a2));
		assertFalse("precondition: a fresh node is not pinned", pinned(u, n, orgId));

		ZonedDateTime before = ZonedDateTime.now().minusSeconds(5);
		BaseRecord ap1 = PbArtifactUtil.approve(u, reread(u, a1, orgId), uoid);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(ap1));
		assertTrue("approve implies select", selected(ap1));
		assertEquals("approvedBy is the caller", uoid, ap1.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		ZonedDateTime at = ap1.get(OlioFieldNames.FIELD_PB_APPROVED_AT);
		assertNotNull(at);
		assertTrue("approvedAt is stamped now, not left at the DDL/epoch default (" + at + ")", at.isAfter(before));
		assertFalse("the former selection is deselected", selected(reread(u, a2, orgId)));
		assertTrue("approve pins the node", pinned(u, n, orgId));
		assertExactlyOneSelected(u, n);
		assertEquals(1, approvedCount(u, n));

		BaseRecord ap2 = PbArtifactUtil.approve(u, reread(u, a2, orgId), uoid);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(ap2));
		assertTrue(selected(ap2));
		BaseRecord demoted = reread(u, a1, orgId);
		assertEquals("the previously approved sibling is demoted to GENERATED", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(demoted));
		assertNull("...with approvedBy cleared", demoted.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertFalse(selected(demoted));
		assertTrue("the node stays pinned", pinned(u, n, orgId));
		assertExactlyOneSelected(u, n);
		assertEquals("one APPROVED per (node, role)", 1, approvedCount(u, n));
	}

	/**
	 * Every approve step guards on its own terminal state: re-approving an APPROVED artifact whose node was
	 * unpinned re-pins it, and a human approval over {@code AUTO} re-stamps {@code approvedBy}. A blank
	 * {@code approvedBy} is a 400 and writes nothing.
	 */
	@Test
	public void case04_reapproveRepinsAndHumanOverAutoRestamps() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		String uoid = u.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p14-c04");
		BaseRecord a1 = artifact(u, n, "c04 only");

		try {
			PbArtifactUtil.approve(u, a1, " ");
			fail("a blank approvedBy must be refused");
		}
		catch(PictureBookException pbe) {
			assertEquals(400, pbe.getStatus());
		}
		assertEquals("a refused approve writes nothing", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(reread(u, a1, orgId)));
		assertFalse(pinned(u, n, orgId));

		BaseRecord auto = PbArtifactUtil.approve(u, reread(u, a1, orgId), PbArtifactUtil.APPROVED_BY_AUTO);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(auto));
		assertEquals(OlioFieldNames.PB_APPROVED_BY_AUTO, auto.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue(pinned(u, n, orgId));

		/// Simulate an explicit unpin (a legal user state: pinned-but-unapproved is allowed, the reverse is not).
		assertTrue(PbGraphUtil.setPinned(u, PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgId), false));
		assertFalse("precondition: unpinned", pinned(u, n, orgId));

		BaseRecord human = PbArtifactUtil.approve(u, reread(u, a1, orgId), uoid);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(human));
		assertEquals("a human approval over AUTO re-stamps approvedBy", uoid, human.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue("re-approving re-pins the node", pinned(u, n, orgId));
		assertTrue(selected(human));
		assertExactlyOneSelected(u, n);
	}

	// ─────────────────────────────── rule 4: reject ───────────────────────────────

	/**
	 * Rejecting the selection marks it REJECTED (kept, {@code approvedBy} cleared), hands the selection to the
	 * newest sibling that is neither REJECTED nor ARCHIVED, and leaves the node's pin alone. When no such
	 * sibling exists the rejected artifact stays selected so the chain still has exactly one selection.
	 */
	@Test
	public void case05_rejectSelectedFallsBackToNewestSurvivorAndKeepsPin() throws Exception {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		String uoid = u.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p14-c05");
		BaseRecord a1 = artifact(u, n, "c05 first");
		BaseRecord a2 = artifact(u, n, "c05 second");
		BaseRecord a3 = artifact(u, n, "c05 third");
		PbArtifactUtil.approve(u, reread(u, a3, orgId), uoid);
		assertTrue("precondition: pinned by the approval", pinned(u, n, orgId));

		BaseRecord r3 = PbArtifactUtil.reject(u, reread(u, a3, orgId));
		assertEquals(PbReviewStatusEnumType.REJECTED, PbArtifactUtil.reviewStatusOf(r3));
		assertNull("reject clears approvedBy", r3.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertFalse("the rejected artifact is no longer selected", selected(r3));
		assertTrue("the newest non-rejected sibling takes over", selected(reread(u, a2, orgId)));
		assertFalse(selected(reread(u, a1, orgId)));
		assertEquals("the fallback is not promoted to APPROVED", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(reread(u, a2, orgId)));
		assertTrue("reject never unpins", pinned(u, n, orgId));
		assertExactlyOneSelected(u, n);

		/// Rejecting a non-selected artifact changes only its own status.
		BaseRecord r1 = PbArtifactUtil.reject(u, reread(u, a1, orgId));
		assertEquals(PbReviewStatusEnumType.REJECTED, PbArtifactUtil.reviewStatusOf(r1));
		assertTrue("the selection is untouched by rejecting a non-selected sibling", selected(reread(u, a2, orgId)));
		assertExactlyOneSelected(u, n);

		/// No survivor left: the rejected selection stays selected rather than leaving the chain empty.
		BaseRecord r2 = PbArtifactUtil.reject(u, reread(u, a2, orgId));
		assertEquals(PbReviewStatusEnumType.REJECTED, PbArtifactUtil.reviewStatusOf(r2));
		assertTrue("with no GENERATED/IN_REVIEW/APPROVED sibling the rejected artifact stays selected", selected(r2));
		assertExactlyOneSelected(u, n);
		assertTrue("still pinned", pinned(u, n, orgId));

		/// A new attempt after a wholesale rejection is auto-selected (the selection is REJECTED, not APPROVED).
		BaseRecord a4 = artifact(u, n, "c05 fourth");
		assertTrue(selected(a4));
		assertFalse(selected(reread(u, a2, orgId)));
		assertExactlyOneSelected(u, n);
	}

	// ─────────────────────────────── facade: queue + filter ───────────────────────────────

	/**
	 * The review queue groups every non-archived artifact by node with its review state; the
	 * {@code auto-approved} filter keeps only APPROVED artifacts stamped {@code AUTO}; an unknown filter is a
	 * 400. Assertions are scoped to this test's two nodes because the fixture workflow is shared.
	 */
	@Test
	public void case06_reviewQueueAllAndAutoApprovedFilter() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String uoid = u.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord nA = node(u, wf, "p14-c06a");
		BaseRecord a1 = artifact(u, nA, "c06 a1");
		BaseRecord a2 = artifact(u, nA, "c06 a2");
		PbArtifactUtil.approve(u, reread(u, a2, orgId), PbArtifactUtil.APPROVED_BY_AUTO);
		BaseRecord nB = node(u, wf, "p14-c06b");
		BaseRecord b1 = artifact(u, nB, "c06 b1");
		PbArtifactUtil.approve(u, reread(u, b1, orgId), uoid);
		BaseRecord nC = node(u, wf, "p14-c06c");
		assertNotNull(nC);

		try {
			PbServiceFacade.listReviewQueue(u, bookOid, "bogus");
			fail("an unknown filter must be refused");
		}
		catch(PictureBookException pbe) {
			assertEquals(400, pbe.getStatus());
			/// The message names the accepted filters and deliberately does NOT echo the caller's value
			/// (P1-4 security finding 4: escapeJson covers quotes/backslash/CR/LF only).
			assertTrue(pbe.getMessage(), pbe.getMessage().contains("Unknown review filter"));
			assertFalse("the caller's value must not be echoed", pbe.getMessage().contains("bogus"));
			assertTrue(pbe.getMessage(), pbe.getMessage().contains(PbServiceFacade.REVIEW_FILTER_AUTO_APPROVED));
		}

		Map<String, Object> all = PbServiceFacade.listReviewQueue(u, bookOid, null);
		assertEquals(PbServiceFacade.REVIEW_FILTER_ALL, all.get("filter"));
		assertEquals(bookOid, all.get("bookObjectId"));
		assertNotNull("autoApprove is reported with the queue", all.get("autoApprove"));
		Map<String, Object> qa = nodeEntry(all, nA);
		assertNotNull("node A is in the queue", qa);
		String handleA = nA.get(OlioFieldNames.FIELD_PB_HANDLE);
		assertEquals(handleA, qa.get("handle"));
		assertEquals(Boolean.TRUE, qa.get("pinned"));
		List<Map<String, Object>> aArts = artifactsOf(qa);
		assertEquals("node A lists both artifacts", 2, aArts.size());
		Map<String, Object> q1 = byObjectId(aArts, a1);
		Map<String, Object> q2 = byObjectId(aArts, a2);
		assertNotNull(q1);
		assertNotNull(q2);
		assertEquals("GENERATED", q1.get("reviewStatus"));
		assertFalse("a GENERATED entry carries no approvedBy", q1.containsKey("approvedBy"));
		assertEquals(Boolean.FALSE, q1.get("selected"));
		assertEquals("APPROVED", q2.get("reviewStatus"));
		assertEquals(OlioFieldNames.PB_APPROVED_BY_AUTO, q2.get("approvedBy"));
		assertNotNull("an APPROVED entry carries approvedAt", q2.get("approvedAt"));
		assertEquals(Boolean.TRUE, q2.get("selected"));
		assertNull("durationMs is omitted when unrecorded", q2.get("durationMs"));
		Map<String, Object> qb = nodeEntry(all, nB);
		assertNotNull("node B is in the queue", qb);
		assertEquals(1, artifactsOf(qb).size());
		assertEquals(uoid, artifactsOf(qb).get(0).get("approvedBy"));
		assertNull("a node with no artifacts is omitted", nodeEntry(all, nC));
		int total = ((Number) all.get("artifactCount")).intValue();
		int pending = ((Number) all.get("pendingCount")).intValue();
		assertTrue("artifactCount covers at least this test's three (" + total + ")", total >= 3);
		assertTrue("pendingCount covers at least a1 (" + pending + ")", pending >= 1);
		assertTrue(pending <= total);

		Map<String, Object> auto = PbServiceFacade.listReviewQueue(u, bookOid, "Auto-Approved");
		assertEquals("the filter is normalised", PbServiceFacade.REVIEW_FILTER_AUTO_APPROVED, auto.get("filter"));
		Map<String, Object> qaAuto = nodeEntry(auto, nA);
		assertNotNull("node A has an auto-approved artifact", qaAuto);
		assertEquals("only the AUTO-approved artifact survives the filter", 1, artifactsOf(qaAuto).size());
		String a2Oid = a2.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(a2Oid, artifactsOf(qaAuto).get(0).get("objectId"));
		assertNull("a human approval is not auto-approved", nodeEntry(auto, nB));
		assertEquals("nothing auto-approved is pending", 0, ((Number) auto.get("pendingCount")).intValue());
		for(Map<String, Object> ne : nodesOf(auto)) {
			for(Map<String, Object> ae : artifactsOf(ne)) {
				assertEquals("APPROVED", ae.get("reviewStatus"));
				assertEquals(OlioFieldNames.PB_APPROVED_BY_AUTO, ae.get("approvedBy"));
			}
		}
	}

	// ─────────────────────────────── facade: gates ───────────────────────────────

	/**
	 * The facade's three review writes: a happy path through {@code selectArtifact}/{@code approveArtifact}/
	 * {@code rejectArtifact} (the result carries the artifact's post-action state and its node), an unknown
	 * artifact and an artifact of another book are both 404, and a user who can read the book but not update
	 * it is 403 on all three with nothing written.
	 */
	@Test
	public void case07_facadeReviewWritesGateAndReport() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String uoid = u.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord n = node(u, wf, "p14-c07");
		BaseRecord a1 = artifact(u, n, "c07 first");
		BaseRecord a2 = artifact(u, n, "c07 second");
		String nodeOid = n.get(FieldNames.FIELD_OBJECT_ID);
		String a1Oid = a1.get(FieldNames.FIELD_OBJECT_ID);
		String a2Oid = a2.get(FieldNames.FIELD_OBJECT_ID);

		Map<String, Object> sel = PbServiceFacade.selectArtifact(u, bookOid, a1Oid);
		assertEquals(a1Oid, sel.get("objectId"));
		assertEquals(nodeOid, sel.get("nodeObjectId"));
		assertEquals(Boolean.TRUE, sel.get("selected"));
		assertEquals("GENERATED", sel.get("reviewStatus"));

		Map<String, Object> ap = PbServiceFacade.approveArtifact(u, bookOid, a2Oid);
		assertEquals(a2Oid, ap.get("objectId"));
		assertEquals("APPROVED", ap.get("reviewStatus"));
		assertEquals("the facade stamps the caller's objectId", uoid, ap.get("approvedBy"));
		assertEquals(Boolean.TRUE, ap.get("selected"));
		assertTrue(pinned(u, n, orgId));

		try {
			PbServiceFacade.selectArtifact(u, bookOid, a1Oid);
			fail("select over an approval must be 409 through the facade too");
		}
		catch(PictureBookException pbe) {
			assertEquals(409, pbe.getStatus());
		}

		Map<String, Object> rj = PbServiceFacade.rejectArtifact(u, bookOid, a2Oid);
		assertEquals("REJECTED", rj.get("reviewStatus"));
		assertEquals(Boolean.FALSE, rj.get("selected"));
		assertFalse("a REJECTED entry carries no approvedBy", rj.containsKey("approvedBy"));
		assertTrue("the fallback took the selection", selected(reread(u, a1, orgId)));

		try {
			PbServiceFacade.approveArtifact(u, bookOid, UUID.randomUUID().toString());
			fail("an unknown artifact must be 404");
		}
		catch(PictureBookException pbe) {
			assertEquals(404, pbe.getStatus());
		}
		BaseRecord other = otherBook(u);
		try {
			PbServiceFacade.approveArtifact(u, other.get(FieldNames.FIELD_OBJECT_ID), a1Oid);
			fail("an artifact addressed through another book must be 404, not acted on");
		}
		catch(PictureBookException pbe) {
			assertEquals("cross-book addressing → 404", 404, pbe.getStatus());
		}
		assertEquals("the cross-book attempt wrote nothing", PbReviewStatusEnumType.GENERATED,
			PbArtifactUtil.reviewStatusOf(reread(u, a1, orgId)));

		BaseRecord reader = readOnlyReader(u, bk);
		BaseRecord bookAsReader = PbBookUtil.readBook(reader, bookOid, orgId);
		assertNotNull("precondition: the reader must be able to read the book, or the 403 proves nothing", bookAsReader);
		PolicyResponseType upd = ioContext.getAuthorizationUtil().canUpdate(reader, reader, bookAsReader);
		assertFalse("precondition: the reader must NOT be permitted to update the book",
			upd != null && upd.getType() == PolicyResponseEnumType.PERMIT);
		String[] actions = { "select", "approve", "reject" };
		for(String action : actions) {
			try {
				switch(action) {
					case "select": PbServiceFacade.selectArtifact(reader, bookOid, a2Oid); break;
					case "approve": PbServiceFacade.approveArtifact(reader, bookOid, a2Oid); break;
					default: PbServiceFacade.rejectArtifact(reader, bookOid, a1Oid); break;
				}
				fail("a read-only user must not be able to " + action);
			}
			catch(PictureBookException pbe) {
				assertEquals("read-only " + action + " → 403", 403, pbe.getStatus());
				assertTrue(pbe.getMessage(), pbe.getMessage().contains("Not authorized"));
			}
		}
		BaseRecord a1After = reread(u, a1, orgId);
		BaseRecord a2After = reread(u, a2, orgId);
		assertTrue("nothing changed: a1 still selected", selected(a1After));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a1After));
		assertEquals(PbReviewStatusEnumType.REJECTED, PbArtifactUtil.reviewStatusOf(a2After));
		assertFalse(selected(a2After));
		assertExactlyOneSelected(u, n);
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
		return bookBySlug(u, SLUG, "Phase 2c graph fixture");
	}

	private BaseRecord otherBook(BaseRecord u) {
		return bookBySlug(u, OTHER_SLUG, "Phase 2c security fixture " + OTHER_SLUG);
	}

	private BaseRecord bookBySlug(BaseRecord u, String slug, String title) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, slug, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), slug, title);
		}
		assertNotNull("book " + slug, bk);
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

	/** A byte-free TEXT artifact in the fixture's chain for {@link #ROLE}, as persistArtifact returns it (fresh read). */
	private BaseRecord artifact(BaseRecord u, BaseRecord n, String text) {
		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, ROLE, PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "p1-4 " + text, null, null);
		assertNotNull("artifact " + text, a);
		return a;
	}

	/** Same shape as TestPbNodeRun.readOnlyReader: direct READ grants on the book group and its projected foreigns. */
	private BaseRecord readOnlyReader(BaseRecord owner, BaseRecord bk) {
		OrganizationContext org = getTestOrganization(ORG_A);
		long orgId = orgOf(owner);
		BaseRecord admin = org.getAdminUser();
		BaseRecord reader = ioContext.getFactory().getCreateUser(admin, READER_USER, orgId);
		assertNotNull("reader " + READER_USER, reader);
		assertFalse("precondition: the reader must not be a group administrator, or the 403 proves nothing",
			ioContext.getAuthorizationUtil().isModelAdministrator(ModelNames.MODEL_GROUP, reader));

		long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("the book's group must be readable by its owner", dir);
		BaseRecord readGroup = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		BaseRecord readData = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read/GROUP permission", readGroup);
		assertNotNull("Read/DATA permission", readData);
		ioContext.getMemberUtil().member(admin, dir, reader, readGroup, true);
		ioContext.getMemberUtil().member(admin, dir, reader, readData, true);
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

	private static boolean selected(BaseRecord a) {
		Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
		return sel != null && sel.booleanValue();
	}

	private boolean pinned(BaseRecord u, BaseRecord n, long orgId) {
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("node must still be readable", fresh);
		Boolean p = fresh.get(OlioFieldNames.FIELD_PB_PINNED);
		return p != null && p.booleanValue();
	}

	private void assertExactlyOneSelected(BaseRecord u, BaseRecord n) {
		List<String> sel = new ArrayList<>();
		for(BaseRecord a : PbArtifactUtil.listChain(u, n, ROLE)) {
			if(selected(a)) {
				sel.add(a.get(FieldNames.FIELD_NAME));
			}
		}
		assertEquals("exactly one selected artifact per (node, role): " + sel, 1, sel.size());
	}

	private int approvedCount(BaseRecord u, BaseRecord n) {
		int count = 0;
		for(BaseRecord a : PbArtifactUtil.listChain(u, n, ROLE)) {
			if(PbArtifactUtil.reviewStatusOf(a) == PbReviewStatusEnumType.APPROVED) {
				count++;
			}
		}
		return count;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> nodesOf(Map<String, Object> queue) {
		Object nodes = queue.get("nodes");
		assertNotNull("the queue carries nodes", nodes);
		return (List<Map<String, Object>>) nodes;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> artifactsOf(Map<String, Object> nodeEntry) {
		return (List<Map<String, Object>>) nodeEntry.get("artifacts");
	}

	private static Map<String, Object> nodeEntry(Map<String, Object> queue, BaseRecord n) {
		String oid = n.get(FieldNames.FIELD_OBJECT_ID);
		for(Map<String, Object> ne : nodesOf(queue)) {
			if(oid.equals(ne.get("nodeObjectId"))) {
				return ne;
			}
		}
		return null;
	}

	private static Map<String, Object> byObjectId(List<Map<String, Object>> arts, BaseRecord a) {
		String oid = a.get(FieldNames.FIELD_OBJECT_ID);
		for(Map<String, Object> ae : arts) {
			if(oid.equals(ae.get("objectId"))) {
				return ae;
			}
		}
		return null;
	}
}
