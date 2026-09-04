package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Objects7-level reproduction of the PictureBook "deleted book row reappears" defect.
 *
 * <p><b>The defect.</b> The PB1 "Legacy Books" list ({@code loadExistingBooks}) is populated by an
 * ORG-WIDE {@code data.note} search for {@code name=".pictureBookMeta"}, keyed only on the
 * {@code bookObjectId} embedded in each note's JSON {@code text}. {@link PictureBookUtil#reset}'s
 * already-gone branch (no {@code data.group} and no {@code olio.pb.book} row) threw
 * {@code PictureBookException(404, "Book not found")} <b>before</b> ever touching the meta note, so the
 * orphaned {@code .pictureBookMeta} note survived every delete of an already-gone book and the list row
 * REAPPEARED on the next reload.
 *
 * <p><b>The fix.</b> {@code reset} now calls {@code deleteOrphanedMetaNotes(user, bookObjectId)} at the
 * top of the {@code bookGroup == null} branch — BEFORE both the benign {@code ok()} path and the 404
 * throw — removing any {@code .pictureBookMeta} note that references {@code bookObjectId} by the same
 * JSON linkage the list uses. The 404 signal is preserved (so the UX still shows "Already removed").
 *
 * <p><b>This test</b> seeds exactly the orphan condition — a {@code .pictureBookMeta} note whose JSON
 * {@code bookObjectId} points at a book that has NO group and NO {@code olio.pb.book} row — confirms the
 * note is visible to the org-wide list query, calls {@code reset(user, goneBookId)} (which throws the
 * expected 404), and asserts the note is GONE from the same org-wide list. Before the fix this last
 * assertion FAILS (the note survives the 404); after the fix it PASSES.
 *
 * <p>Pure persistence — no LLM, no SD — so it runs unconditionally. Acts as a non-admin
 * {@code getCreateUser} user (admin is used ONLY to provision that user).
 */
public class TestPictureBookOrphanMetaDelete extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook Orphan Meta Tests";

	private BaseRecord testUser;
	private long orgId;

	@Before
	public void setUpOrphanMeta() {
		// BaseTest.setup() runs first (@Before) and registers the Olio/PictureBook models.
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "pbOrphanMetaUser", ctx.getOrganizationId());
		assertNotNull("non-admin test user must be created", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	@Test
	public void resetRemovesOrphanedMetaNoteFromLegacyList() throws Exception {
		long ts = System.currentTimeMillis();

		// A book objectId that has NO data.group and NO olio.pb.book row — the already-gone condition.
		String goneBookId = "gone-" + UUID.randomUUID();

		// ── 1. Seed the orphan: a .pictureBookMeta note referencing goneBookId, the same shape
		//        loadExistingBooks/deleteOrphanedMetaNotes read (JSON text with a bookObjectId key). ──
		String metaGroupPath = "~/Data/PictureBookOrphanTest-" + ts;
		String metaJson = "{\"schema\":\"olio.pictureBookMeta\",\"bookObjectId\":\"" + goneBookId
			+ "\",\"workName\":\"Orphan Meta Test " + ts + "\",\"sceneCount\":0}";

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, metaGroupPath);
		plist.parameter(FieldNames.FIELD_NAME, ".pictureBookMeta");
		BaseRecord note = IOSystem.getActiveContext().getFactory()
			.newInstance(ModelNames.MODEL_NOTE, testUser, null, plist);
		note.set("text", metaJson);
		BaseRecord createdNote = IOSystem.getActiveContext().getAccessPoint().create(testUser, note);
		assertNotNull("seed .pictureBookMeta note must be created", createdNote);

		// ── 2. Confirm the seeded note IS visible to the org-wide list query (i.e. it would show a row). ──
		assertEquals("exactly one .pictureBookMeta note must reference goneBookId before reset",
			1, countMetaNotesReferencing(goneBookId));

		// ── 3. Delete the already-gone book. reset() must run meta cleanup, then throw 404. ──
		try {
			PictureBookUtil.reset(testUser, goneBookId);
			fail("reset() on an already-gone book must throw PictureBookException(404)");
		} catch (PictureBookException pbe) {
			// The 404 signal is intentionally preserved so the UX shows the benign "Already removed" toast.
			assertEquals("already-gone delete must still surface a 404 (drives the 'Already removed' UX)",
				404, pbe.getStatus());
		}

		// ── 4. THE ASSERTION UNDER TEST: the orphaned note is GONE from the org-wide list. ──
		//        Pre-fix this FAILS (note survives the 404 → row reappears); post-fix it PASSES.
		assertEquals("after reset() the orphaned .pictureBookMeta note referencing goneBookId MUST be gone "
			+ "from the org-wide Legacy Books list — otherwise the deleted row reappears on reload",
			0, countMetaNotesReferencing(goneBookId));

		logger.info("PictureBook orphan meta delete: goneBookId={} — note present before reset, gone after",
			goneBookId);
	}

	/**
	 * Mirrors {@code loadExistingBooks} / {@code deleteOrphanedMetaNotes}: an ORG-WIDE search for
	 * {@code .pictureBookMeta} notes, counting those whose JSON {@code text} carries a matching
	 * {@code bookObjectId} (or legacy {@code workObjectId}). Fresh read (cache:false).
	 */
	private int countMetaNotesReferencing(String bookObjectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, ".pictureBookMeta");
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TEXT });
		q.setCache(false);
		BaseRecord[] notes = IOSystem.getActiveContext().getAccessPoint().list(testUser, q).getResults();
		if (notes == null) {
			return 0;
		}
		int count = 0;
		for (BaseRecord n : notes) {
			String text = n.get(FieldNames.FIELD_TEXT);
			if (text == null || text.isBlank()) {
				continue;
			}
			try {
				Map<String, Object> m = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
				if (m == null) {
					continue;
				}
				Object b = m.get("bookObjectId");
				Object w = m.get("workObjectId");
				String ref = (b instanceof String && !((String) b).isBlank()) ? (String) b
					: (w instanceof String && !((String) w).isBlank()) ? (String) w : null;
				if (bookObjectId.equals(ref)) {
					count++;
				}
			} catch (Exception ignore) {
				// not JSON — cannot be a match
			}
		}
		return count;
	}
}
