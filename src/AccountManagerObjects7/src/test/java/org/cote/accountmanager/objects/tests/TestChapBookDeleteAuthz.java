package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Objects7-level integration test for the {@code ChapBookUtil.deleteChapBook} hardening: an explicit
 * {@code AuthorizationUtil.canDelete} PBAC check before the delete, so a delete DENIAL surfaces as a
 * {@link PictureBookException} with status 403 rather than a bare {@code AccessPoint.delete} false-return
 * that the transport reads as 500.
 * <p>
 * This is the one branch the REST spec ({@code e2e/chapBookDeleteAuthz.spec.js}) could NOT reach:
 * making a book readable-but-not-deletable by a distinct non-admin user requires per-book role
 * provisioning that is not available to a fresh REST user. It IS the essence of the hardening, so it is
 * covered here directly.
 * <p>
 * <b>Why a targeted Read grant and not the per-book Writer role.</b> The first draft of this test
 * enrolled the denied user in the per-book Writer role ({@code ctx.registerUser(admin, b, false)}),
 * expecting Writer to grant read-but-not-delete. Running it against the live DB proved otherwise: the
 * audit log showed {@code AUDIT PERMIT ... to DELETE olio.pb.book}, and {@code OlioContext} logs
 * {@code "own receives Delete, shared does not"} — the per-book Writer role DOES carry Delete on the
 * book's OWN world groups; the "never Delete" rule applies only to the SHARED universe corpora. So Writer
 * enrolment cannot produce a delete denial. To make a user read-but-not-delete, the fixture instead grants
 * the denied user a targeted {@code Read} entitlement (DATA + GROUP, NO Delete) directly on the book's own
 * group, as the org admin — a privileged FIXTURE provisioning step, not authorizing B's delete.
 * <p>
 * <b>Scenario.</b>
 * <ol>
 *   <li>User A (a non-admin, ordinary user) creates a real CHAPBOOK. The book row is owned by the OLIO
 *       PRINCIPAL, not by A.</li>
 *   <li>User B is a second, distinct non-admin user in the same organization — the ACTOR whose delete is
 *       denied. B is enrolled in NO per-book role.</li>
 *   <li>The fixture grants B a {@code Read}-only entitlement (DATA + GROUP, never Delete) on the book's
 *       own group, via {@code AuthorizationUtil.setEntitlement} as the org admin. This is FIXTURE
 *       PROVISIONING of a read grant — it does not authorize B's delete.</li>
 *   <li>POSITIVE CONTROL: {@code PbBookUtil.readBook(b, bookOid, orgId)} must resolve NON-NULL, proving
 *       the read grant is real — so {@code deleteChapBook}'s internal {@code readBook} succeeds and we hit
 *       the {@code canDelete} DENY branch, not the 404 not-found path. If the read grant does not take
 *       (readBook null → the call would 404), the positive-control assertion fails LOUDLY rather than the
 *       test loosening its expectation.</li>
 *   <li>ASSERTION: {@code ChapBookUtil.deleteChapBook(b, bookOid)} throws {@link PictureBookException}
 *       with {@code getStatus() == 403} and the message of the {@code canDelete} branch ("Not authorized
 *       to delete ChapBook"), pinning it to the PBAC-denied branch and NOT the "is not a CHAPBOOK" 403.</li>
 * </ol>
 * <p>
 * No LLM and no SD server are required or contacted: the CHAPBOOK is created with {@code chatConfig=null}
 * (no LLM prompt path) and the test never calls {@code renderChapBook}. It runs against the live DB only.
 */
public class TestChapBookDeleteAuthz extends BaseTest {

	/** Reuse the already-seeded Books universe org that TestPbSecurity uses (avoids the multi-minute seed). */
	private static final String ORG_A = "/Development/World Building";

	/** Creator of the ChapBook. A non-admin, ordinary user; the book is olio-principal-owned. */
	private static final String OWNER_NAME = "cbDelAuthzOwner";
	/** The ACTOR whose delete is denied. A distinct non-admin user in the same org. */
	private static final String DENIED_NAME = "cbDelAuthzDenied";

	@Before
	public void authzSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
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

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	/**
	 * Create an {@code olio.cb.poem} at the given group path, as {@code TestChapBook.createPoem} does.
	 */
	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		} catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	/**
	 * The DENY branch of {@code deleteChapBook}: a non-admin user who can READ a ChapBook but is not
	 * authorized to DELETE it must get a {@link PictureBookException} with status 403.
	 */
	@Test
	public void deleteChapBook_readableButNotDeletable_throws403() throws Exception {
		String dataPath = dataPath();
		assertNotNull("test.datagen.path must be set", dataPath);

		OrganizationContext o = org();
		BaseRecord owner = user(OWNER_NAME);
		BaseRecord denied = user(DENIED_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Both users must live in the same organization (org-scope precondition for enrolment).
		assertTrue("Both test users must share the organization",
			((Number) denied.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue() == orgId);

		long ts = System.currentTimeMillis();
		String slug = "cb-delauthz-" + ts;
		String title = "ChapBook Delete-Authz " + ts;

		// ── 1. Create a real CHAPBOOK owned by the olio principal (chatConfig=null → no LLM) ──────
		String poemText =
			"The tide withdraws across the stone,\n" +
			"And leaves the shells to dry alone,\n" +
			"A gull cries once above the bay,\n" +
			"Then folds its wings and slips away.";
		BaseRecord poem = createPoem(owner, "~/Data/CbDelAuthz-" + ts, "Poem " + ts, poemText);
		assertNotNull("Poem should be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		BaseRecord book = ChapBookUtil.createChapBook(owner, dataPath, slug, title, poemOids, 4, null);
		assertNotNull("createChapBook must return a book record", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookOid);
		logger.info("Created CHAPBOOK slug={} objectId={}", slug, bookOid);

		// ── 2. Provision a READ-but-not-DELETE grant for the denied user (FIXTURE SETUP as admin) ──
		// Resolve the book's OWN group, then grant the denied user Read (DATA + GROUP) on it — and only
		// Read, never Delete. Granting as the org admin authorizes the ENTITLEMENT write; it does NOT
		// authorize B's later delete. (The per-book Writer role is NOT used: it carries Delete on the
		// book's own groups, so it could not produce a delete denial — proven by the first run.)
		BaseRecord ownerBook = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull("Owner must be able to read the book it created", ownerBook);
		long bookGroupId = ((Number) ownerBook.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("Olio principal must resolve", olioUser);
		Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, bookGroupId);
		gq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		gq.setCache(false);
		BaseRecord bookGroup = IOSystem.getActiveContext().getSearch().findRecord(gq);
		assertNotNull("Book's own group must resolve for the fixture grant", bookGroup);

		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(
			o.getAdminUser(), denied, new BaseRecord[] { bookGroup },
			new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		// Flush policy/authorization caches so the just-written grant is visible to canRead/canDelete.
		CacheUtil.clearCache();

		// ── 3. POSITIVE CONTROL: the denied user CAN read the book record via the Read grant ────────
		// If this is null the delete call would take the 404 path, not the canDelete DENY path — so
		// assert it loudly rather than proceeding to a 403 that would be a false positive.
		BaseRecord readByDenied = PbBookUtil.readBook(denied, bookOid, orgId);
		assertNotNull("Positive control: the denied user must be able to READ the ChapBook (the Read "
			+ "grant is real), so deleteChapBook hits the canDelete DENY branch and not the 404 path",
			readByDenied);
		String bookType = readByDenied.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		assertTrue("The book the denied user reads must be a CHAPBOOK (else a 403 would be the wrong "
			+ "branch); got bookType=" + bookType,
			bookType != null && "CHAPBOOK".equalsIgnoreCase(bookType));

		// ── 4. THE HARDENING: delete as the denied (non-admin) user must throw 403 (canDelete DENY) ─
		try {
			boolean deleted = ChapBookUtil.deleteChapBook(denied, bookOid);
			fail("deleteChapBook must throw PictureBookException(403) for a user who can read but not "
				+ "delete the ChapBook; instead it returned deleted=" + deleted
				+ " (the Read-only fixture grant unexpectedly permitted Delete)");
		} catch (PictureBookException e) {
			logger.info("deleteChapBook denied as expected: status={} message={}", e.getStatus(), e.getMessage());
			assertTrue("Denied delete must map to HTTP 403, got status=" + e.getStatus(),
				e.getStatus() == 403);
			// Pin the branch: canDelete DENY ("Not authorized to delete ChapBook"), NOT the
			// "is not a CHAPBOOK" 403 (which would mean we never reached the PBAC check).
			assertTrue("The 403 must be the canDelete DENY branch, not the bookType branch; message="
				+ e.getMessage(),
				e.getMessage() != null && e.getMessage().contains("Not authorized to delete"));
		}

		// ── 5. And the DENY must be non-destructive: the book still exists after the failed delete ──
		BaseRecord stillThere = PbBookUtil.readBook(denied, bookOid, orgId);
		assertNotNull("The ChapBook must survive the denied delete (the delete must not have run)", stillThere);
	}
}
