package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for the reported ChapBook delete/recreate defect:
 * <blockquote>"if a chapbook is deleted, and another with the same name created, there are still
 * artifacts leftover that cause index collisions and a blank chapbook is produced."</blockquote>
 * <p>
 * Root cause: {@code deleteChapBook}'s readable path deleted ONLY the {@code olio.pb.book} row (via
 * {@code deleteRecordExplained}), leaving the {@code /Book} group, every {@code olio.pb.scene} row,
 * the workflow graph, the {@code olio.world}, and the cached {@code OlioContext} behind. On a
 * same-slug recreate, {@code writeBookRow} reused the leftover {@code /Book} group (same {@code groupId}),
 * and each new scene {@code "Scene <slug> <idx>"} collided with the leftover scene of the same name on
 * the unique {@code (name, groupId, organizationId)} index. {@code createScene} threw, {@code createChapBook}
 * swallowed the exception, and {@code sceneIndex} stayed 0 — a BLANK book.
 * <p>
 * This test is a PURE PERSISTENCE reproduction: {@code chatConfig=null} (no LLM) and no render, so it
 * runs unconditionally without the LLM or SD server. It proves:
 * <ol>
 *   <li>create "X" yields a fully populated book (N scenes);</li>
 *   <li>{@code deleteChapBook} leaves NO {@code olio.pb.scene} row in the former book group, NO book row,
 *       and NO world container group — a complete teardown;</li>
 *   <li>recreate "X" (same slug/title) is again fully populated (N scenes), NOT blank, with no index
 *       collision.</li>
 * </ol>
 */
public class TestChapBookDeleteRecreate extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Delete Tests";

	private BaseRecord testUser;
	private long orgId;

	@Before
	public void setUpDeleteRecreate() {
		// BaseTest.setup() runs first (@Before) and calls OlioModelNames.use().
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookDelRecreateUser", ctx.getOrganizationId());
		assertNotNull("test user must be created", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	@Test
	public void testDeleteThenRecreateSameSlugIsNotBlank() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long ts = System.currentTimeMillis();
		String slug = "cb-delrecreate-" + ts;
		String title = "ChapBook Delete Recreate " + ts;

		// Two 2-line poems + maxLinesPerPage=4 → each stanza is one scene → 2 scenes. No LLM.
		String poemPath = "~/Data/ChapBookDelRecreate-" + ts;
		BaseRecord poem1 = createPoem(testUser, poemPath, "DR Poem One " + ts,
			"The light falls soft on winter boughs,\nA silver hush where no bird calls.");
		BaseRecord poem2 = createPoem(testUser, poemPath, "DR Poem Two " + ts,
			"Come spring and break the ice apart,\nLet green reclaim the barren earth.");
		assertNotNull("poem 1 must be created", poem1);
		assertNotNull("poem 2 must be created", poem2);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem1.get(FieldNames.FIELD_OBJECT_ID));
		poemOids.add(poem2.get(FieldNames.FIELD_OBJECT_ID));

		// ── 1. First create ──────────────────────────────────────────────────────
		BaseRecord book1 = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 4, null);
		assertNotNull("first createChapBook must return a book", book1);
		String book1Oid = book1.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("first book must have an objectId", book1Oid);

		int scenes1 = countScenesForBook(book1Oid);
		assertTrue("first ChapBook must be fully populated (>= 2 scenes), got " + scenes1, scenes1 >= 2);

		// Capture the book group id NOW (delete will remove the group; leftover scene rows would still
		// carry this groupId, so a raw group-scoped search still detects them afterwards).
		long bookGroupId = resolveBookGroupId(slug);
		assertTrue("book group id must resolve after first create", bookGroupId > 0);
		assertEquals("scene rows in the book group must equal the created scene count",
			scenes1, countSceneRowsInGroup(bookGroupId));

		// ── 2. Delete ─────────────────────────────────────────────────────────────
		boolean deleted = ChapBookUtil.deleteChapBook(testUser, book1Oid);
		assertTrue("deleteChapBook must return true", deleted);

		// ── 3. Complete teardown — NOTHING may remain that can collide on recreate ──
		assertEquals("NO olio.pb.scene row may remain in the (former) book group after delete — "
			+ "leftover scenes are exactly what collided on the same-slug recreate",
			0, countSceneRowsInGroup(bookGroupId));
		assertNull("the olio.pb.book row must be gone after delete", findBookRowAsOlio(book1Oid));
		assertFalse("the book world container group (/Olio/Universes/Books/Worlds/<slug>) must be gone after delete",
			bookContainerExists(slug));

		// ── 4. Recreate the SAME slug/title — must be fully populated, not BLANK ───
		BaseRecord book2 = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 4, null);
		assertNotNull("recreate createChapBook must return a book", book2);
		String book2Oid = book2.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("recreated book must have an objectId", book2Oid);
		assertFalse("recreated book must be a genuinely new row, not the deleted one",
			book1Oid.equals(book2Oid));

		int scenes2 = countScenesForBook(book2Oid);
		assertTrue("RECREATED ChapBook must be fully populated (>= 2 scenes), not BLANK (got " + scenes2
			+ ") — leftover artifacts from the deleted book must not collide on the "
			+ "(name, groupId, organizationId) scene index", scenes2 >= 2);
		assertEquals("recreate must produce the same scene count as the original", scenes1, scenes2);

		logger.info("ChapBook delete/recreate: create#1={} scenes, delete clean, recreate#2={} scenes (slug={})",
			scenes1, scenes2, slug);

		// Cleanup so repeated test runs stay tidy (best-effort).
		try { ChapBookUtil.deleteChapBook(testUser, book2Oid); } catch (Exception ignore) { /* best-effort */ }
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private BaseRecord olioUser() {
		return IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
	}

	/** Raw (PBAC-bypassing) lookup of the book row by objectId, so ownership (olio principal) is irrelevant. */
	private BaseRecord findBookRowAsOlio(String bookOid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/** Count scenes whose {@code book} FK points at this book (raw search — ownership irrelevant). */
	private int countScenesForBook(String bookOid) {
		BaseRecord book = findBookRowAsOlio(bookOid);
		if (book == null) {
			return 0;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, book);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		BaseRecord[] rs = IOSystem.getActiveContext().getSearch().findRecords(q);
		return rs != null ? rs.length : 0;
	}

	/** Count scene rows physically present in a group id (detects orphans even after the group is deleted). */
	private int countSceneRowsInGroup(long groupId) {
		if (groupId <= 0) {
			return 0;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		BaseRecord[] rs = IOSystem.getActiveContext().getSearch().findRecords(q);
		return rs != null ? rs.length : 0;
	}

	private long resolveBookGroupId(String slug) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(olioUser(),
			ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		return grp != null ? ((Number) grp.get(FieldNames.FIELD_ID)).longValue() : -1L;
	}

	private boolean bookContainerExists(String slug) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(olioUser(),
			ModelNames.MODEL_GROUP, PbBookUtil.bookContainerPath(slug), GroupEnumType.DATA.toString(), orgId);
		return grp != null;
	}

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
}
