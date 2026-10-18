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
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
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

	/// The candidate query listBooks runs, with or without the CHAPBOOK exclusion, over an explicit window.
	/// Same shape as PbServiceFacade.listBooks: creator-scoped, sorted by name ascending, no cache.
	private static Query candidateQuery(BaseRecord user, long orgId, boolean excludeChapBooks, int window) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, user.get(FieldNames.FIELD_OBJECT_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_BOOK_TYPE});
		q.setCache(false);
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		if(excludeChapBooks) {
			q.field(OlioFieldNames.FIELD_PB_BOOK_TYPE, ComparatorEnumType.NOT_EQUALS, PbBookTypeEnumType.CHAPBOOK.toString());
		}
		q.setRequestRange(0, window);
		return q;
	}

	private static int indexOf(BaseRecord[] rows, String objectId) {
		if(rows == null) {
			return -1;
		}
		for(int i = 0; i < rows.length; i++) {
			if(objectId.equals(rows[i].get(FieldNames.FIELD_OBJECT_ID))) {
				return i;
			}
		}
		return -1;
	}

	private static String typeOf(BaseRecord row) {
		Object t = (row != null && row.hasField(OlioFieldNames.FIELD_PB_BOOK_TYPE) ? row.get(OlioFieldNames.FIELD_PB_BOOK_TYPE) : null);
		return (t != null ? t.toString() : null);
	}

	/// Turn a freshly created STORY book into a CHAPBOOK the way ChapBookUtil does (ChapBookUtil.java:764-767):
	/// the row is olio-owned so the patch goes through RecordUtil, and the type is verified by a raw re-read.
	private static void makeChapBook(IOContext ioContext, String slug, long orgId) throws Exception {
		BaseRecord row = rawRowBySlug(ioContext, slug, orgId);
		assertNotNull("row for " + slug, row);
		BaseRecord typePatch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_BOOK_TYPE);
		typePatch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		assertTrue("bookType patch of " + slug, ioContext.getRecordUtil().updateRecord(typePatch));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE});
		q.setCache(false);
		BaseRecord back = ioContext.getSearch().findRecord(q);
		assertNotNull("re-read " + slug, back);
		assertTrue("persisted bookType of " + slug + " must be CHAPBOOK, was " + typeOf(back),
			"CHAPBOOK".equalsIgnoreCase(typeOf(back)));
	}

	/// P3-8 run 5: 550 ChapBooks hid every story book because the old candidate query enumerated the first
	/// N rows of ALL the creator's books and filtered CHAPBOOK afterwards. The exclusion now lives inside the
	/// candidate query, so a window that CHAPBOOKs would otherwise fill still reaches the story book.
	///
	/// Leftover-proof: the window is not a guessed constant. It is computed from the live exclusion query
	/// as (index of the STORY among this creator's non-CHAPBOOK books) + 1, so any rows a failed earlier
	/// run left behind for this user only shift the index; they cannot make either half pass or fail
	/// spuriously. The two CHAPBOOK slugs sort BEFORE the STORY slug by name (name == "Book <slug>"), so
	/// in the un-excluded query they occupy slots inside the same window and push the STORY out of it.
	@Test
	public void listBooksSkipsChapBooksInsideTheCandidateRange() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbListCapOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String run = shortId();
		String chap1 = "aa" + run + "1";
		String chap2 = "aa" + run + "2";
		String story = "zz" + run;
		BaseRecord olioUser = null;
		try {
			BaseRecord c1 = PbBookUtil.createBook(owner, dataPath, chap1, "Chap one " + run);
			BaseRecord c2 = PbBookUtil.createBook(owner, dataPath, chap2, "Chap two " + run);
			BaseRecord st = PbBookUtil.createBook(owner, dataPath, story, "Story " + run);
			assertNotNull("chapbook 1", c1);
			assertNotNull("chapbook 2", c2);
			assertNotNull("story", st);
			String storyOid = st.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("story objectId", storyOid);
			olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			assertNotNull("olio principal", olioUser);

			makeChapBook(ioContext, chap1, orgId);
			makeChapBook(ioContext, chap2, orgId);
			BaseRecord storyRow = rawRowBySlug(ioContext, story, orgId);
			assertNotNull("story row", storyRow);
			assertEquals("story must be stamped with its creator", (String) owner.get(FieldNames.FIELD_OBJECT_ID),
				(String) storyRow.get(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID));

			/// Where does the STORY land among this creator's non-CHAPBOOK books? That decides the window.
			BaseRecord[] excluded = ioContext.getSearch().findRecords(candidateQuery(owner, orgId, true, 10000));
			int storyIdx = indexOf(excluded, storyOid);
			assertTrue("story must appear in the exclusion query (idx=" + storyIdx + ")", storyIdx >= 0);
			for(BaseRecord r : excluded) {
				assertFalse("exclusion query must not return a CHAPBOOK: " + r.get(OlioFieldNames.FIELD_PB_SLUG),
					"CHAPBOOK".equalsIgnoreCase(typeOf(r)));
			}
			int cap = storyIdx + 1;

			/// Negative control: the SAME query shape without the bookType condition, over the SAME window,
			/// does not reach the STORY - the two CHAPBOOKs that sort before it consume the slots.
			BaseRecord[] unexcluded = ioContext.getSearch().findRecords(candidateQuery(owner, orgId, false, cap));
			assertNotNull("un-excluded window", unexcluded);
			assertEquals("un-excluded window must be full", cap, unexcluded.length);
			/// With a fresh creator the STORY is idx 0, so cap == 1 and only ONE of the two CHAPBOOKs fits the window;
			/// what matters is that a CHAPBOOK took the slot the STORY would otherwise have had.
			assertTrue("negative control: un-excluded window of " + cap + " must contain a CHAPBOOK",
				indexOf(unexcluded, c1.get(FieldNames.FIELD_OBJECT_ID)) >= 0
				|| indexOf(unexcluded, c2.get(FieldNames.FIELD_OBJECT_ID)) >= 0);
			assertEquals("negative control: STORY must be pushed out of the un-excluded window of " + cap,
				-1, indexOf(unexcluded, storyOid));

			/// The facade with that same window must still list the STORY, and neither CHAPBOOK.
			long t0 = System.currentTimeMillis();
			List<Map<String, Object>> books = PbServiceFacade.listBooks(owner, cap);
			logger.info("listBooks(owner, cap=" + cap + ") returned " + books.size() + " in " + (System.currentTimeMillis() - t0) + "ms");
			assertTrue("listBooks(owner, " + cap + ") must contain the STORY " + story, listed(books, story));
			boolean oidListed = false;
			for(Map<String, Object> b : books) {
				if(storyOid.equals(b.get("objectId"))) {
					oidListed = true;
				}
			}
			assertTrue("listBooks(owner, " + cap + ") must carry the STORY objectId", oidListed);
			assertFalse("listBooks must not list CHAPBOOK " + chap1, listed(books, chap1));
			assertFalse("listBooks must not list CHAPBOOK " + chap2, listed(books, chap2));

			/// And the production cap behaves the same way for these three.
			List<Map<String, Object>> prod = PbServiceFacade.listBooks(owner);
			assertTrue("listBooks(owner) must contain the STORY", listed(prod, story));
			assertFalse("listBooks(owner) must not list CHAPBOOK " + chap1, listed(prod, chap1));
			assertFalse("listBooks(owner) must not list CHAPBOOK " + chap2, listed(prod, chap2));
		}
		finally {
			if(olioUser == null) {
				olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			}
			if(olioUser != null) {
				for(String slug : new String[] {chap1, chap2, story}) {
					try {
						cleanup(ioContext, olioUser, slug, orgId);
					}
					catch(Throwable t) {
						logger.error("cleanup of " + slug + " failed: " + t.getMessage());
					}
				}
			}
		}
	}
}
