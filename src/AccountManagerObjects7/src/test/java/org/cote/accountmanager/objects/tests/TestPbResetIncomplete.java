package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Objects7-level reproduction and regression test for the reported defect: <em>"PictureBook delete
 * fails if the picturebook was incomplete or failed with errors, so it's impossible to delete."</em>
 * <p>
 * <b>The exact state under test</b> is the one {@code PbBookUtil.createBook} leaves behind when world
 * creation throws AFTER the book row has been written. {@code createBook} does two things in order:
 * (1) {@code writeBookRow(...)} creates the {@code olio.pb.book} row <b>owned by the olio principal</b>
 * via {@code RecordUtil.createRecord} (a PBAC-bypass write, because no grant exists on the group yet),
 * then (2) {@code getCreateBookContext(...)} builds the world and applies the acting user's grants. If
 * step 2 fails (SD/LLM error, crash, timeout), the row survives olio-owned with <b>no grants for the
 * acting user</b> — the "incomplete/failed" book the user then cannot remove.
 * <p>
 * This test builds that state faithfully and without any SD/LLM: it resolves (or bootstraps, exactly as
 * {@code PbOlioContextUtil} does) the olio principal, writes the book row as that principal at the real
 * PB2 group path {@code /Olio/Universes/Books/Worlds/{slug}/Book} with {@code bookType=STORY} and
 * {@code bookStatus=FAILED}, and grants the acting user nothing. It then calls
 * {@code PictureBookUtil.reset(actor, bookObjectId)} as a distinct non-admin user and asserts that the
 * reset both reports success and actually removes the row.
 * <p>
 * No admin user is used as the actor; no schema reset; runs against the live DB only.
 */
public class TestPbResetIncomplete extends BaseTest {

	/** Reuse the already-seeded Books universe org (avoids the multi-minute universe seed). */
	private static final String ORG_A = "/Development/World Building";

	/** The ACTOR whose reset must succeed. A non-admin, ordinary user. */
	private static final String ACTOR_NAME = "pbResetIncompleteActor";

	@Before
	public void resetSetup() {
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

	/**
	 * Bootstrap (idempotently, exactly as {@code PbOlioContextUtil.createBookUniverseContext} does at
	 * line 207) the olio principal, so the book row can be written under its ownership even though no
	 * full Olio world has been seeded for this test.
	 */
	private BaseRecord olioPrincipal(OrganizationContext o) {
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, o.getOrganizationId());
		if (olioUser == null) {
			olioUser = ioContext.getFactory().getCreateUser(o.getAdminUser(), OlioContext.OLIO_USER_NAME, o.getOrganizationId());
		}
		assertNotNull("Olio principal must resolve/bootstrap", olioUser);
		return olioUser;
	}

	/**
	 * Write an incomplete book row exactly as {@code PbBookUtil.writeBookRow} does: create the book
	 * group as the olio principal, then the {@code olio.pb.book} row owned by that principal via
	 * {@code RecordUtil.createRecord}. The acting user receives NO grants — the failed-mid-flight state.
	 */
	private BaseRecord writeIncompleteBookRow(BaseRecord olioUser, String slug, String title, long orgId) throws Exception {
		return writeBookRow(olioUser, slug, title, orgId, PbBookStatusEnumType.FAILED, null);
	}

	/**
	 * Write a book row as the olio principal with a specified lifecycle status and (optional)
	 * {@code createdByObjectId}. Mirrors {@code PbBookUtil.writeBookRow} but lets a test simulate the
	 * various olio-owned states (a failed orphan with no creator link; a complete book created by
	 * another user; etc.). The acting user receives NO grants.
	 */
	private BaseRecord writeBookRow(BaseRecord olioUser, String slug, String title, long orgId,
			PbBookStatusEnumType status, String createdByObjectId) throws Exception {
		IOContext io = IOSystem.getActiveContext();
		String bookGroupPath = PbBookUtil.bookGroupPath(slug);
		BaseRecord group = io.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath,
			GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Book group must resolve at " + bookGroupPath, group);
		String name = "Book " + slug;
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		io.getRecordUtil().applyNameGroupOwnership(olioUser, book, name, bookGroupPath, orgId);
		book.set(FieldNames.FIELD_NAME, name);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, status.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		if (createdByObjectId != null) {
			book.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, createdByObjectId);
		}
		if (title != null) {
			book.set(FieldNames.FIELD_DESCRIPTION, title);
		}
		assertTrue("Book row must be created (olio-owned)", io.getRecordUtil().createRecord(book));
		return book;
	}

	/** Direct existence probe (bypasses PBAC — read-only verification of survival), uncached. */
	private BaseRecord probeRow(String bookOid, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_SLUG });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	@Test
	public void reset_incompleteFailedPictureBook_isAlwaysDeletable() throws Exception {
		OrganizationContext o = org();
		BaseRecord actor = user(ACTOR_NAME);
		long orgId = ((Number) actor.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord olioUser = olioPrincipal(o);

		long ts = System.currentTimeMillis();
		String slug = "pb-reset-incomplete-" + ts;
		String title = "Incomplete PictureBook " + ts;

		// ── Build the failed-mid-flight state: olio-owned row, no grants for the actor ─────────────
		BaseRecord book = writeIncompleteBookRow(olioUser, slug, title, orgId);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookOid);
		logger.info("Created incomplete STORY book slug={} objectId={}", slug, bookOid);

		// Sanity: the row really exists in the DB.
		assertNotNull("The incomplete book row must exist before reset", probeRow(bookOid, orgId));

		// Characterize the state: the actor cannot read it through AccessPoint (no grants) — this is
		// precisely why the naive reset path (which reads/deletes AS the actor) cannot clear it.
		BaseRecord readByActor = PbBookUtil.readBook(actor, bookOid, orgId);
		logger.info("Actor read of incomplete book via AccessPoint: {}",
			readByActor == null ? "null (no grants — expected)" : "non-null");

		// ── THE REQUIREMENT: an incomplete/failed book must ALWAYS be deletable by its owner-user ──
		boolean deleted;
		try {
			deleted = PictureBookUtil.reset(actor, bookOid).deleted;
		} catch (PictureBookException e) {
			fail("reset must not fail for an incomplete/failed PICTUREBOOK; it threw status="
				+ e.getStatus() + " message=" + e.getMessage()
				+ " — the book is impossible to delete, which is the reported defect");
			return;
		}
		assertTrue("reset must report success for an incomplete/failed PICTUREBOOK", deleted);

		// ── And it must have actually removed the row (not a silent no-op success) ──────────────────
		BaseRecord survivor = probeRow(bookOid, orgId);
		assertNull("The incomplete book row must be gone after reset (impossible-to-delete defect); "
			+ "it survived: " + (survivor == null ? "" : survivor.get(OlioFieldNames.FIELD_PB_SLUG)), survivor);
	}

	/**
	 * The fix's authorization guard: the olio-principal fallback must NOT become a way for one user to
	 * delete another user's COMPLETE book. A stranger with no grants who names a complete book created
	 * by someone else must be denied (403), and the row must survive.
	 */
	@Test
	public void reset_completeBookOfAnotherUser_deniesStranger() throws Exception {
		OrganizationContext o = org();
		BaseRecord stranger = user(ACTOR_NAME);
		BaseRecord otherCreator = user("pbResetOtherCreator");
		long orgId = ((Number) stranger.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord olioUser = olioPrincipal(o);

		long ts = System.currentTimeMillis();
		String slug = "pb-reset-complete-" + ts;

		// A COMPLETE book created by another user, olio-owned, with NO grants for the stranger.
		String otherCreatorOid = otherCreator.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord book = writeBookRow(olioUser, slug, "Complete Book " + ts, orgId,
			PbBookStatusEnumType.COMPLETE, otherCreatorOid);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookOid);
		assertNotNull("The complete book row must exist before reset", probeRow(bookOid, orgId));

		try {
			boolean deleted = PictureBookUtil.reset(stranger, bookOid).deleted;
			fail("reset must deny a stranger deleting another user's COMPLETE book; it returned deleted="
				+ deleted + " (the olio-principal fallback escalated privileges)");
		} catch (PictureBookException e) {
			logger.info("Stranger delete denied as expected: status={} message={}", e.getStatus(), e.getMessage());
			assertTrue("Denied delete must map to HTTP 403, got status=" + e.getStatus(), e.getStatus() == 403);
			assertTrue("The 403 must be the not-authorized guard, message=" + e.getMessage(),
				e.getMessage() != null && e.getMessage().contains("Not authorized to delete"));
		}

		// The complete book must survive the denied delete.
		assertNotNull("The complete book must survive a denied stranger delete", probeRow(bookOid, orgId));
	}
}
