package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/// A failed PbBookUtil.createBook must not leave a slug taken by a row nobody can see. Before this fix a
/// new book whose creation failed after the world was built left the olio.pb.book row (owned by the olio
/// principal, never stamped with createdByObjectId) and the olio.world behind; GET /books listed nothing
/// (it filtered on ownerId, which under uniform olio ownership never matches) and a retry with the same
/// title died on 409. Real DB, no LLM.
///
/// The two rollback branches are driven differently, and this is stated rather than hidden:
/// - worldPreExisted=true is FORCED end-to-end through createBook: a second, un-entitled user attempts the
///   slug while the world already exists, getCreateBookContext refuses (OlioException -> PBE 500), and the
///   rollback must delete only the row this attempt wrote, leaving the first user's world intact.
/// - worldPreExisted=false cannot be made to fail by any createBook input, so rollbackCreate is invoked
///   directly (package-private for exactly this) and the full footprint teardown is asserted.
public class TestPbCreateBookRollback extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/// Raw, PBAC-free existence probe: the slug is either taken in this organization or it is not,
	/// regardless of who can read the row. This is the orphan detector.
	private static BaseRecord rawRowBySlug(IOContext ioContext, String slug, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private static BaseRecord groupAt(IOContext ioContext, BaseRecord olioUser, String path, long orgId) {
		return ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), orgId);
	}

	private static boolean listed(List<Map<String, Object>> books, String slug) {
		for(Map<String, Object> b : books) {
			if(slug.equals(b.get("slug"))) {
				return true;
			}
		}
		return false;
	}

	private void cleanup(IOContext ioContext, BaseRecord olioUser, String slug, long orgId) {
		BaseRecord row = rawRowBySlug(ioContext, slug, orgId);
		if(row == null) {
			PbBookUtil.rollbackCreate(ioContext, slug, orgId, false, new PictureBookException(500, "test cleanup"));
			return;
		}
		PictureBookUtil.DeleteResult del = PictureBookUtil.teardownBookFootprintAsOlio(olioUser, row, orgId);
		assertTrue("cleanup teardown of " + slug + ": " + del.reason, del.deleted);
	}

	@Test
	public void testFailedCreateRollsBackRowSoSlugIsReusable() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbRollbackOwner");
		BaseRecord stranger = getCreateUser("pbRollbackStranger");
		assertNotNull("owner", owner);
		assertNotNull("stranger", stranger);
		assertFalse("owner and stranger must be different users",
			((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) stranger.get(FieldNames.FIELD_ID)));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String slug = "rb" + shortId();
		BaseRecord olioUser = null;
		try {
			// 1. The owner creates the book: row + world, both olio-owned, row stamped with the creator.
			BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Rollback " + slug);
			assertNotNull("owner's createBook", book);
			String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("created book objectId", bookOid);
			olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			assertNotNull("olio principal", olioUser);
			BaseRecord world = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug);
			assertNotNull("book world must exist after create", world);
			String worldOid = world.get(FieldNames.FIELD_OBJECT_ID);

			// The creator-scoped listing (fix for GET /books returning []) shows it to the creator only.
			assertTrue("listBooks(owner) must include the new book", listed(PbServiceFacade.listBooks(owner), slug));
			assertFalse("listBooks(stranger) must not include the owner's book", listed(PbServiceFacade.listBooks(stranger), slug));

			// 2. Reproduce the reported state: the world artifact is there but no book row is. (Row only,
			//    as the olio owner - the world and groups stay.)
			BaseRecord row = rawRowBySlug(ioContext, slug, orgId);
			assertNotNull("raw row before delete", row);
			PictureBookUtil.DeleteResult rowDel = PictureBookUtil.deleteRecordExplained(olioUser, row);
			assertTrue("row-only delete: " + rowDel.reason, rowDel.deleted);
			assertNull("row gone", rawRowBySlug(ioContext, slug, orgId));
			assertNotNull("world still present (orphan state)", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));

			// 3. FORCED FAILURE through createBook: the stranger takes the slug. Pre-flight passes (no row),
			//    the row is written, worldPreExisted=true, then getCreateBookContext refuses because the
			//    stranger holds neither Writer nor Admin on the existing book.
			try {
				PbBookUtil.createBook(stranger, dataPath, slug, "Stranger " + slug);
				fail("stranger's createBook on an existing, unentitled world must fail");
			}
			catch(PictureBookException e) {
				assertEquals("world refusal surfaces as 500", 500, e.getStatus());
			}

			// 4. The rollback took the stranger's row back out and did NOT touch the owner's world.
			assertNull("no orphan row after the failed create", rawRowBySlug(ioContext, slug, orgId));
			BaseRecord worldAfter = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug);
			assertNotNull("pre-existing world must survive a worldPreExisted rollback", worldAfter);
			assertEquals("same world", worldOid, worldAfter.get(FieldNames.FIELD_OBJECT_ID));

			// 5. The slug is reusable: the entitled owner re-creates the same slug and gets a readable book.
			BaseRecord again = PbBookUtil.createBook(owner, dataPath, slug, "Rollback again " + slug);
			assertNotNull("owner's same-slug retry must succeed", again);
			assertEquals("retry adopts the existing world", worldOid,
				((BaseRecord) again.get(OlioFieldNames.FIELD_PB_WORLD)).get(FieldNames.FIELD_OBJECT_ID));
			assertTrue("listBooks(owner) shows the re-created book", listed(PbServiceFacade.listBooks(owner), slug));
			assertFalse("listBooks(stranger) still empty for this slug", listed(PbServiceFacade.listBooks(stranger), slug));
		}
		finally {
			if(olioUser == null) {
				olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			}
			if(olioUser != null) {
				cleanup(ioContext, olioUser, slug, orgId);
			}
		}
	}

	@Test
	public void testRollbackFreshWorldTearsDownRowWorldAndGroups() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbRollbackOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String slug = "rf" + shortId();
		BaseRecord olioUser = null;
		try {
			BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Fresh " + slug);
			assertNotNull("createBook", book);
			olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			assertNotNull("olio principal", olioUser);
			assertNotNull("world exists", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));
			assertNotNull("Book group exists", groupAt(ioContext, olioUser, PbBookUtil.bookGroupPath(slug), orgId));
			assertNotNull("Workflow group exists", groupAt(ioContext, olioUser, PbBookUtil.workflowGroupPath(slug), orgId));
			assertNotNull("Artifacts group exists", groupAt(ioContext, olioUser, PbBookUtil.artifactGroupPath(slug), orgId));

			// The branch createBook takes when the world was built by THIS call and a later step failed.
			PbBookUtil.rollbackCreate(ioContext, slug, orgId, false, new PictureBookException(500, "simulated post-world failure"));

			assertNull("row gone", rawRowBySlug(ioContext, slug, orgId));
			assertNull("world gone", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));
			assertNull("Book group gone", groupAt(ioContext, olioUser, PbBookUtil.bookGroupPath(slug), orgId));
			assertNull("Workflow group gone", groupAt(ioContext, olioUser, PbBookUtil.workflowGroupPath(slug), orgId));
			assertNull("Artifacts group gone", groupAt(ioContext, olioUser, PbBookUtil.artifactGroupPath(slug), orgId));
			assertNull("container group gone", groupAt(ioContext, olioUser, PbBookUtil.bookContainerPath(slug), orgId));

			// And the slug is reusable with a FRESH world - which also proves the cached context was evicted.
			BaseRecord again = PbBookUtil.createBook(owner, dataPath, slug, "Fresh again " + slug);
			assertNotNull("same-slug retry after fresh-world rollback", again);
			assertNotNull("retry built a new world", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));
			assertTrue("listBooks(owner) shows the retried book", listed(PbServiceFacade.listBooks(owner), slug));
		}
		finally {
			if(olioUser == null) {
				olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			}
			if(olioUser != null) {
				cleanup(ioContext, olioUser, slug, orgId);
			}
		}
	}

	@Test
	public void testRollbackWithNoRowUsesSlugStandIn() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbRollbackOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String slug = "rs" + shortId();
		BaseRecord olioUser = null;
		try {
			assertNotNull("createBook", PbBookUtil.createBook(owner, dataPath, slug, "StandIn " + slug));
			olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			assertNotNull("olio principal", olioUser);

			// World without a row: the rollback has nothing to load and must fall back to a slug stand-in.
			BaseRecord row = rawRowBySlug(ioContext, slug, orgId);
			assertNotNull("raw row", row);
			assertTrue("row-only delete", PictureBookUtil.deleteRecordExplained(olioUser, row).deleted);
			assertNotNull("world still present", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));

			PbBookUtil.rollbackCreate(ioContext, slug, orgId, false, new PictureBookException(500, "simulated"));

			assertNull("world gone via slug stand-in", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));
			assertNull("container group gone", groupAt(ioContext, olioUser, PbBookUtil.bookContainerPath(slug), orgId));
		}
		finally {
			if(olioUser == null) {
				olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			}
			if(olioUser != null) {
				cleanup(ioContext, olioUser, slug, orgId);
			}
		}
	}
}
