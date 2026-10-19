package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, §6.C 189 (d): {@link PbBookUtil#listScenes} and {@link PbBookUtil#countScenes} are constrained by the
 * book's group, not only by the book FK and the organization. {@code AccessPoint.list} is not a per-record boundary,
 * so without the group condition a scene row written into any other group that points at the book would be listed
 * and counted as the book's own.
 * <p>
 * Live {@code am7db} as {@code testUser1} in {@code /Development/World Building}; no LLM, no SD. Each case creates
 * its own throwaway book and deletes it.
 * <ol>
 * <li>A scene row created in another group of the same organization (the user's own {@code ~/Data}), pointing at
 * the book, is readable by the user as a row but is neither listed nor counted for the book; the book's own scene
 * is.</li>
 * <li>A book record that carries no group is refused by both with a 409, before any query runs.</li>
 * </ol>
 */
public class TestPbSceneListGroup extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case01_sceneInAnotherGroupIsNeitherListedNorCounted() throws Exception {
		BaseRecord u = user();
		long orgId = ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord bk = null;
		BaseRecord foreign = null;
		try {
			bk = newBook(u, "sl1");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			long bookGid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertTrue("the book row carries its group", bookGid > 0L);

			BaseRecord own = PbBookUtil.createScene(u, bk, 0, "Own scene", PbBookUtil.bookGroupPath(slug));
			assertNotNull("own scene", own);
			assertEquals("the book's scene is in the book group", bookGid, ((Number) own.get(FieldNames.FIELD_GROUP_ID)).longValue());

			/// Created FIRST so that, were it listed, it would also sort ahead of the real scene at the same index.
			foreign = PbBookUtil.createScene(u, bk, 1, "Foreign scene", "~/Data/P44 Foreign Scene " + shortId());
			assertNotNull("foreign-group scene", foreign);
			long foreignGid = ((Number) foreign.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertTrue("the foreign row really is in another group", foreignGid != bookGid);
			assertNotNull("the foreign row is the user's to read by id", PbBookUtil.readScene(u, foreign.get(FieldNames.FIELD_OBJECT_ID), orgId));
			BaseRecord foreignBook = foreign.get(OlioFieldNames.FIELD_PB_BOOK);
			assertNotNull("the foreign row points at the book", foreignBook);
			assertEquals(((Number) bk.get(FieldNames.FIELD_ID)).longValue(), ((Number) foreignBook.get(FieldNames.FIELD_ID)).longValue());

			List<BaseRecord> listed = PbBookUtil.listScenes(u, bk);
			List<String> names = listed.stream().map(r -> (String) r.get(FieldNames.FIELD_NAME)).collect(Collectors.toList());
			logger.info("P4-4 189(d) listScenes: " + names);
			assertEquals("only the book-group scene is listed: " + names, 1, listed.size());
			assertEquals((String) own.get(FieldNames.FIELD_OBJECT_ID), (String) listed.get(0).get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("only the book-group scene is counted", 1, PbBookUtil.countScenes(u, bk));

			/// The same two rows by FK and organization alone (the pre-ruling shape) would be two - the control that
			/// proves the group condition, not PBAC, is what excludes the foreign row.
			assertEquals("by FK + organization alone both rows are visible to this user", 2, countByBookAndOrgOnly(u, bk));
		}
		finally {
			deleteQuietly(u, foreign);
			deleteBook(u, bk);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case02_bookWithoutGroupIsRefused() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "sl2");
			PbBookUtil.createScene(u, bk, 0, "Own scene", PbBookUtil.bookGroupPath(bk.get(OlioFieldNames.FIELD_PB_SLUG)));
			assertEquals(1, PbBookUtil.countScenes(u, bk));

			/// A field-limited copy; BaseRecord.get materialises an absent field at its default, so the group reads as 0,
			/// which is the shape a groupless book record presents.
			BaseRecord groupless = bk.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID,
				OlioFieldNames.FIELD_PB_SLUG});
			Long gid = groupless.get(FieldNames.FIELD_GROUP_ID);
			assertTrue("the copy carries no group: " + gid, gid == null || gid.longValue() <= 0L);
			try {
				PbBookUtil.listScenes(u, groupless);
				fail("listScenes must refuse a groupless book");
			}
			catch(PictureBookException e) {
				logger.info("P4-4 189(d) listScenes groupless: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().endsWith("has no group"));
			}
			try {
				PbBookUtil.countScenes(u, groupless);
				fail("countScenes must refuse a groupless book");
			}
			catch(PictureBookException e) {
				assertEquals(409, e.getStatus());
			}
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private int countByBookAndOrgOnly(BaseRecord u, BaseRecord bk) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, bk);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(bk));
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		q.setCache(false);
		return ioContext.getAccessPoint().count(u, q);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p44" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-4 189d " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, bk.get(FieldNames.FIELD_OBJECT_ID));
			logger.info("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + " was not deleted");
			}
		}
		catch(Exception e) {
			logger.error("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}
}
