package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * §24 UAT-6 (found by U-6's solo re-run of pictureBookNavigation.spec.js): the PB1 read routes behind
 * {@code GET /olio/picture-book/{id}/scenes} and {@code /characters} resolved the book through its PB1 scene
 * {@code data.group} only, so a V3-only {@code olio.pb.book} - every {@code POST /chapter} fixture, and a
 * product-created chapter whose extraction has not run - answered 404 "Book not found" although the book
 * exists and the caller owns it. The Manage Characters dialog took the 404 at face value (no catch: it stayed on
 * "Loading characters…" with an unhandled rejection).
 * <p>
 * Now: a readable V3-only book answers with what it has (no PB1 scenes; its world population characters, or
 * none); an absent id and another user's book still 404 - the fix must not turn the authorization miss into a
 * disclosure. Against the live am7db as two non-admin users under the test org.
 */
public class TestPbV3OnlyBookLegacyReads extends BaseTest {

	private static final String ORG_PATH = "/Development/PictureBook Custom Tests";
	private static final String TEST_USER = "pbCustomTestUser";

	private OrganizationContext ctx;
	private BaseRecord user;
	private long orgId;

	@Before
	public void setUp() {
		OlioContextUtil.clearCache();
		OlioModelNames.use();
		ctx = getTestOrganization(ORG_PATH);
		assertNotNull(ctx);
		orgId = ctx.getOrganizationId();
		user = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), TEST_USER, orgId);
		assertNotNull("Failed to resolve " + TEST_USER, user);
	}

	@Test
	public void v3OnlyBookReadsEmptyNotFourOhFour() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

		BaseRecord otherUser = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), TEST_USER + "Other", orgId);
		assertNotNull("second test user", otherUser);
		assertNotEquals("two distinct users", ((Number) user.get(FieldNames.FIELD_ID)).longValue(), ((Number) otherUser.get(FieldNames.FIELD_ID)).longValue());

		String ownOid = null;
		String otherOid = null;
		try {
			BaseRecord ownBook = PbBookUtil.createBook(user, dataPath, "uat6own" + nonce, "UAT-6 V3-only book " + nonce);
			assertNotNull("own V3-only book", ownBook);
			ownOid = ownBook.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord otherBook = PbBookUtil.createBook(otherUser, dataPath, "uat6other" + nonce, "UAT-6 other user's book " + nonce);
			assertNotNull("the other user's V3-only book", otherBook);
			otherOid = otherBook.get(FieldNames.FIELD_OBJECT_ID);

			// Premise: neither book has a PB1 scene group (resolveBookGroupEither misses for both).
			assertNull("own book has no PB1 scene group", PictureBookUtil.resolveBookGroupEither(user, ownOid, orgId));
			assertNotNull("...yet it reads as its owner", PbBookUtil.readBook(user, ownOid, orgId));
			assertNull("the other user's book is invisible to this user", PbBookUtil.readBook(user, otherOid, orgId));

			// (1) A readable V3-only book: no PB1 scenes, and the cast it has (none for a bare createBook), never a 404.
			List<Map<String, Object>> scenes = PictureBookUtil.listScenes(user, ownOid);
			assertNotNull("listScenes answers a list", scenes);
			assertEquals("a V3-only book has no PB1 scenes", 0, scenes.size());
			List<Map<String, Object>> chars = PictureBookUtil.listCharacters(user, ownOid);
			assertNotNull("listCharacters answers a list", chars);
			logger.info("=== UAT-6 PROOF own V3-only book {}: scenes={} characters={}", ownOid, scenes.size(), chars.size());

			// (2) An absent id is still 404.
			String absent = UUID.randomUUID().toString();
			assertFourOhFour("listScenes(absent)", () -> PictureBookUtil.listScenes(user, absent));
			assertFourOhFour("listCharacters(absent)", () -> PictureBookUtil.listCharacters(user, absent));

			// (3) Another user's book is still 404 to this user - existence is not disclosed through the new branch.
			final String other = otherOid;
			assertFourOhFour("listScenes(another user's book)", () -> PictureBookUtil.listScenes(user, other));
			assertFourOhFour("listCharacters(another user's book)", () -> PictureBookUtil.listCharacters(user, other));
			// ...and reads for its owner.
			assertEquals(0, PictureBookUtil.listScenes(otherUser, otherOid).size());
			assertNotNull(PictureBookUtil.listCharacters(otherUser, otherOid));
			logger.info("=== UAT-6 PROOF absent id and another user's book {} still 404 for {}", otherOid, user.get(FieldNames.FIELD_NAME));
		}
		finally {
			cleanupBook(user, ownOid);
			cleanupBook(otherUser, otherOid);
		}
	}

	private interface Call {
		void run();
	}

	private static void assertFourOhFour(String label, Call call) {
		try {
			call.run();
			fail(label + " should have thrown 404 Book not found");
		}
		catch(PictureBookException e) {
			assertEquals(label + " status", 404, e.getStatus());
		}
	}

	private void cleanupBook(BaseRecord owner, String oid) {
		if(oid == null) {
			return;
		}
		try {
			DeleteResult del = PbDeleteUtil.deleteBookComplete(owner, oid);
			logger.info("[uat6] cleanup book {}: deleted={} reason={}", oid, del.deleted, del.reason);
		}
		catch(Exception e) {
			logger.warn("[uat6] cleanup book {} failed: {}", oid, e.getMessage());
		}
	}
}
