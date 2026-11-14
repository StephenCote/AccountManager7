package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 §24 I-5 (218 c): a book slug is organization-wide (the unique index on {@code olio.pb.book.slug} is
 * per organization, and every book row is owned by the org's olio principal), but every slug pre-check was
 * caller-scoped ({@code findBookBySlug} as the caller sees nothing of another user's book). So a second owner
 * choosing a slug the first already holds passed every pre-check and died on the unique index inside
 * {@code writeBookRow} with a "concurrent creation" 409 - after the world and its grants had been created.
 * {@link PbBookUtil#requireSlugFree} now runs at all four sites (the two {@code createBook} overloads,
 * {@code PbServiceFacade.createChapter}, {@code PbMigrationUtil.importV1Book}): the caller's own same-slug
 * book is still the "already exists" 409; a slug held by a book the caller cannot see is an honest 409 that
 * says so, found by {@link PbBookUtil#isSlugTaken} - an existence probe as the olio principal through
 * {@code AccessPoint}, projecting identity + slug only, disclosing nothing the index would not.
 * <p>
 * Two owners in {@code /Development/ChapBook Tests} (Olio seed present). A takes a timestamped slug; B, who
 * cannot see A's book, is refused with the "cannot see" message from {@code createBook}, from
 * {@code createChapter} and from {@code importV1Book} (a PB1 fixture group whose derived slug collides); A's
 * own re-create is still the "already exists" message; A's book is deleted at the end and the slug is free again. Live
 * {@code am7db}; no LLM, no GPU.
 */
public class TestPbSlugCrossOwner extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	private OrganizationContext ctx;
	private BaseRecord ownerA;
	private BaseRecord ownerB;
	private long orgId;

	@Before
	public void setUpUsers() {
		ctx = getTestOrganization(ORG_PATH);
		assertNotNull(ctx);
		orgId = ctx.getOrganizationId();
		ownerA = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "pbSlugOwnerA", orgId);
		ownerB = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), "pbSlugOwnerB", orgId);
		assertNotNull("owner A must be created", ownerA);
		assertNotNull("owner B must be created", ownerB);
		assertFalse("two distinct owners", ((Number) ownerA.get(FieldNames.FIELD_ID)).longValue() == ((Number) ownerB.get(FieldNames.FIELD_ID)).longValue());
	}

	@Test
	public void secondOwnerIsToldTheSlugIsTaken() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		String slug = "slugx-" + System.currentTimeMillis();

		// 0. Nobody holds the slug yet: the org-wide probe is false and both callers see nothing.
		assertFalse("isSlugTaken on a fresh slug", PbBookUtil.isSlugTaken(slug, orgId));
		assertNull(PbBookUtil.findBookBySlug(ownerA, slug, orgId));
		assertNull(PbBookUtil.findBookBySlug(ownerB, slug, orgId));

		// 1. A takes it.
		BaseRecord bookA = PbBookUtil.createBook(ownerA, dataPath, slug, "Slug Cross Owner A " + slug);
		assertNotNull("A's createBook", bookA);
		String bookAOid = bookA.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(bookAOid);
		try {
			runCollisions(dataPath, slug, bookAOid);
		}
		finally {
			// A's book is A's to remove; the slug must be free again for the next run.
			try {
				PictureBookUtil.DeleteResult del = PbDeleteUtil.deleteBookComplete(ownerA, bookAOid);
				logger.info("[i5-218c] cleanup A's book {}: deleted={} reason={}", bookAOid, del.deleted, del.reason);
			}
			catch(Exception e) {
				logger.error("[i5-218c] cleanup A's book " + bookAOid + ": " + e.getMessage());
			}
		}
		assertFalse("the slug is free again after A's delete", PbBookUtil.isSlugTaken(slug, orgId));
	}

	private void runCollisions(String dataPath, String slug, String bookAOid) throws Exception {
		assertTrue("isSlugTaken once A holds it", PbBookUtil.isSlugTaken(slug, orgId));
		assertNotNull("A sees A's book by slug", PbBookUtil.findBookBySlug(ownerA, slug, orgId));

		// 2. B cannot see A's book - the caller-scoped probe is the mismatch 218 c is about.
		assertNull("B must not see A's book by slug", PbBookUtil.findBookBySlug(ownerB, slug, orgId));
		assertNull("B must not read A's book by objectId", PbBookUtil.readBook(ownerB, bookAOid, orgId));

		// 3. B's createBook: 409 with the "cannot see" message, and no book of B's exists afterwards.
		PictureBookException e1 = expect409(() -> PbBookUtil.createBook(ownerB, dataPath, slug, "Slug Cross Owner B " + slug));
		assertTrue("B's createBook message names the cross-owner case: " + e1.getMessage(), e1.getMessage().contains("cannot see"));
		assertNull("B still has no book with the slug", PbBookUtil.findBookBySlug(ownerB, slug, orgId));

		// 4. B's createChapter (no series, no predecessor): the facade site, same message.
		PictureBookException e2 = expect409(() -> PbServiceFacade.createChapter(ownerB, dataPath, null, null, slug,
			"Slug Cross Owner B chapter " + slug, null, null, null, null, null));
		assertTrue("B's createChapter message names the cross-owner case: " + e2.getMessage(), e2.getMessage().contains("cannot see"));

		// 5. B's importV1Book of a PB1 group whose derived slug is A's: the migration site, same message, and
		// B's own caller-scoped re-import check did not fire first (B has no book with the slug).
		String fixturePath = "~/Data/PictureBooks/" + slug;
		BaseRecord fixtureGroup = IOSystem.getActiveContext().getPathUtil().makePath(ownerB, ModelNames.MODEL_GROUP,
			fixturePath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("B's PB1 fixture group", fixtureGroup);
		assertEquals("the fixture name derives to A's slug", slug, PbPipelineUtil.deriveSlug((String) fixtureGroup.get(FieldNames.FIELD_NAME)));
		BaseRecord metaNote = null;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, fixturePath);
			plist.parameter(FieldNames.FIELD_NAME, PbMigrationUtil.V1_META_NOTE_NAME);
			metaNote = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, ownerB, null, plist);
			metaNote.set("text", "{\"schema\":\"olio.pictureBookMeta\",\"workName\":\"Slug Cross Owner B import " + slug + "\",\"scenes\":[]}");
			metaNote = IOSystem.getActiveContext().getAccessPoint().create(ownerB, metaNote);
			assertNotNull("B's PB1 meta note", metaNote);
			String fixtureOid = fixtureGroup.get(FieldNames.FIELD_OBJECT_ID);
			PictureBookException e3 = expect409(() -> PbMigrationUtil.importV1Book(ownerB, dataPath, fixtureOid));
			assertTrue("B's importV1Book message names the cross-owner case: " + e3.getMessage(), e3.getMessage().contains("cannot see"));
			assertFalse("not the re-import message", e3.getMessage().contains("re-importing"));
		}
		finally {
			if(metaNote != null) {
				IOSystem.getActiveContext().getAccessPoint().delete(ownerB, metaNote);
			}
			IOSystem.getActiveContext().getAccessPoint().delete(ownerB, fixtureGroup);
		}

		// 6. A's own re-create is still the caller-scoped "already exists" 409 - the existing message, first.
		PictureBookException e4 = expect409(() -> PbBookUtil.createBook(ownerA, dataPath, slug, "Slug Cross Owner A again " + slug));
		assertTrue("A's re-create message is the own-book case: " + e4.getMessage(), e4.getMessage().contains("already exists"));
		assertFalse("A's re-create is not the cross-owner message", e4.getMessage().contains("cannot see"));

		// 7. A's book is intact: same objectId, still the only holder.
		BaseRecord stillA = PbBookUtil.findBookBySlug(ownerA, slug, orgId);
		assertNotNull(stillA);
		assertEquals("A's book is unchanged", bookAOid, (String) stillA.get(FieldNames.FIELD_OBJECT_ID));

		System.out.println("=== I-5 218c PROOF: slug=" + slug + " A=" + bookAOid + " B createBook/createChapter/importV1Book=409 'cannot see' x3, A re-create=409 'already exists'");
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private interface Call {
		void run() throws Exception;
	}

	private static PictureBookException expect409(Call call) throws Exception {
		try {
			call.run();
		}
		catch(PictureBookException e) {
			assertEquals("expected a 409, got " + e.getStatus() + ": " + e.getMessage(), 409, e.getStatus());
			return e;
		}
		fail("expected a 409 but the call returned");
		return null;
	}
}
