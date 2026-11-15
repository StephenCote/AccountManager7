package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/// Regression proof for {@code GET /rest/picturebook/{id}/settings} accepting the SAME two handles as
/// {@code /scenes} and {@code /characters}: a PB1 scene-group ({@code data.group}) objectId OR an
/// {@code olio.pb.book} objectId.
///
/// Measured live 2026-09-30 as {@code e2etest_shared}: {@code /scenes} and {@code /characters} resolved a
/// PB2 book objectId, but {@code /settings} returned 404 "Book not found" for it, because
/// {@link PictureBookUtil#getBookSdConfig} (and the since-deleted PB1 writer {@code setBookSdConfig})
/// resolved the book only through {@code findBookGroup} (a {@code data.group} objectId lookup). The Ux
/// wizard's resume path ({@code tryResumeExistingBook}) swallows that 404, so opening the editor from a
/// PB2 surface silently dropped the book's saved SD config. The fix routes the lookup through
/// {@code resolveBookGroupEither}, the resolver the sibling read endpoints already use.
///
/// 2026-11-14 (§24 C-1): the PB1 writer {@code PictureBookUtil.setBookSdConfig} (route retired 216 d,
/// no main-code caller) was deleted, so this class pins the READ side only — both handles resolve
/// (null, not 404) for the owner, and a second non-admin user in the same org is refused 404 through
/// both. The V3 settings write path is {@code PbBookUtil.setBookSdConfig}, pinned by
/// {@link TestPbBookSettingsAuthz}.
///
/// Setup mirrors {@link TestPbCreateFromScenesRerun}: a standalone PB2 book via {@code PbBookUtil
/// .createBook}, then the production {@link PictureBookUtil#createFromScenes} with ONE castless scene and
/// an empty cast (no LLM/embedding work). Real DB (am7db); dedicated non-admin user.
public class TestPbBookSettingsResolveEither extends BaseTest {

	private static final String WORK_PATH = "~/PbSettingsEitherWork";


	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static List<Map<String, Object>> oneCastlessScene(String title) {
		Map<String, Object> scene = new LinkedHashMap<>();
		scene.put("title", title);
		scene.put("summary", "An empty lighthouse gallery at first light.");
		scene.put("setting", "a lighthouse gallery above a grey sea");
		scene.put("action", "the lamp turns once and goes dark");
		scene.put("mood", "still");
		scene.put("sourceText", "The lamp made its last turn as the sky greyed, and then the keeper let it rest.");
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene);
		return scenes;
	}

	private BaseRecord createWork(BaseRecord user, String tag) throws Exception {
		ParameterList wplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, WORK_PATH);
		wplist.parameter(FieldNames.FIELD_NAME, "settings-src-" + tag);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, wplist);
		work.set("text", "The lamp made its last turn as the sky greyed, and then the keeper let it rest.");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull("work note must be created", created);
		return created;
	}

	@Test
	public void testSettingsResolveByPb2BookObjectIdAndPb1GroupObjectId() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbSettingsEitherUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);

		// Normal PB2 creation path: the olio.pb.book row, then the wizard's createFromScenes keyed to it.
		String slug = "pbset" + tag;
		String bookName = "PB Settings Either Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("pb.book objectId", pb2BookOid);

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, oneCastlessScene("Last Turn " + tag), new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("createFromScenes returned meta", meta);

		// The two handles are DISTINCT objectIds — that distinction is the whole bug.
		String sceneGroupOid = meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId (PB1 scene GROUP objectId)", sceneGroupOid);
		assertNotEquals("scene GROUP objectId and pb.book objectId differ", pb2BookOid, sceneGroupOid);
		assertEquals("meta.pb2BookObjectId is the pb.book objectId", pb2BookOid, meta.get("pb2BookObjectId"));

		// Sanity: the sibling read endpoint already resolves BOTH handles (this is the contract /settings
		// must match).
		assertEquals(1, PictureBookUtil.listScenes(testUser, sceneGroupOid).size());
		assertEquals(1, PictureBookUtil.listScenes(testUser, pb2BookOid).size());

		// ── BEFORE any settings are saved: both handles RESOLVE (no config yet => null, NOT 404). ──
		assertNull("fresh book: no sdConfig via the scene-group objectId",
			PictureBookUtil.getBookSdConfig(testUser, sceneGroupOid));
		try {
			assertNull("fresh book: no sdConfig via the pb.book objectId",
				PictureBookUtil.getBookSdConfig(testUser, pb2BookOid));
		} catch (PictureBookException e) {
			fail("getBookSdConfig(pb.book objectId) must resolve like /scenes does; got HTTP "
				+ e.getStatus() + " " + e.getMessage());
		}

		// The write side of this contract (save by one handle, read back by the other) was pinned here
		// against PictureBookUtil.setBookSdConfig until that PB1 writer was deleted 2026-11-14 (§24 C-1);
		// nothing writes the meta sdConfig key any more, so the read-side resolution above is the whole pin.
		System.out.println("=== /settings EITHER-ID RESOLUTION PROOF (standalone PB2) ===");
		System.out.println("  slug=" + slug);
		System.out.println("  scene GROUP objectId = " + sceneGroupOid);
		System.out.println("  pb.book   objectId   = " + pb2BookOid);
		System.out.println("  getBookSdConfig(groupOid) resolved (null, not 404)");
		System.out.println("  getBookSdConfig(bookOid)  resolved (null, not 404)  <= the fix (was 404)");
	}

	@FunctionalInterface
	private interface SettingsCall {
		BaseRecord call();
	}

	/// Runs a settings call that MUST be refused, and pins the refusal shape: {@code resolveBookGroupEither}
	/// returns null for absent-or-denied (AccessPoint.find returns null on a PBAC deny, it does not throw),
	/// and {@code getBookSdConfig} turns that null into
	/// {@code PictureBookException(404, "Book not found")}. A non-null return is a disclosure and fails here.
	private static void assertRefused404(String what, SettingsCall call) {
		BaseRecord leaked;
		try {
			leaked = call.call();
		} catch (PictureBookException e) {
			assertEquals(what + ": must be refused as 404 (absent-or-denied), got HTTP " + e.getStatus()
				+ " " + e.getMessage(), 404, e.getStatus());
			assertEquals(what + ": refusal message", "Book not found", e.getMessage());
			return;
		}
		fail(what + ": DISCLOSED another user's book settings instead of refusing; returned "
			+ (leaked == null ? "null" : leaked.toFullString()));
	}

	/// Negative (cross-user) case for the either-id resolver behind {@code GET /settings}: a SECOND non-admin
	/// user in the SAME organization must not be able to read another user's book SD config (or learn that
	/// the book exists) through EITHER handle (PB2 {@code olio.pb.book} objectId or PB1 scene-group objectId).
	///
	/// Expected refusal path, traced through {@link PictureBookUtil#resolveBookGroupEither}:
	/// - by pb.book objectId: {@code findBookGroup} misses (not a group id), then {@code AccessPoint.find}
	///   on {@code olio.pb.book} runs {@code canRead} for B against A's record -> null -> 404;
	/// - by scene-group objectId: {@code findBookGroup}'s {@code AccessPoint.find} on {@code auth.group}
	///   is denied for B -> null, then the pb.book lookup by that id has no row -> null -> 404.
	/// The log WILL carry {@code AUDIT DENY ... could not be authorized} ERROR lines for B: that is the
	/// deny being measured, not a failure (see .claude/rules/troubleshooting.md).
	///
	/// Self-contained fixture (JUnit 4 does not order methods): same owner user, a fresh standalone PB2
	/// book via the same {@code createBook} + {@code createFromScenes} setup as the positive test.
	@Test
	public void testOtherUserInSameOrgCannotReadOrWriteBookSettingsByEitherHandle() throws Exception {
		OlioModelNames.use();
		BaseRecord ownerA = getCreateUser("pbSettingsEitherUser");
		assertNotNull("owner user A", ownerA);
		BaseRecord otherB = getCreateUser("pbSettingsEitherOther");
		assertNotNull("other user B", otherB);
		assertNotEquals("A and B are different users", ownerA.get(FieldNames.FIELD_ID), otherB.get(FieldNames.FIELD_ID));
		assertEquals("A and B are in the SAME organization",
			(Object) ownerA.get(FieldNames.FIELD_ORGANIZATION_ID), otherB.get(FieldNames.FIELD_ORGANIZATION_ID));
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// ── A's fixture: standalone PB2 book + wizard scene group (same setup as the positive test). ──
		String tag = shortId();
		BaseRecord work = createWork(ownerA, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String slug = "pbsetx" + tag;
		String bookName = "PB Settings Either XUser Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(ownerA, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord meta = PictureBookUtil.createFromScenes(ownerA, workObjectId, null, "fiction",
			bookName, oneCastlessScene("Last Turn X " + tag), new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("createFromScenes returned meta", meta);
		String sceneGroupOid = meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId (PB1 scene GROUP objectId)", sceneGroupOid);
		assertNotEquals("the two handles differ", pb2BookOid, sceneGroupOid);

		// ── A (the owner) RESOLVES both handles (baseline: no config stored => null, never 404). ──
		try {
			assertNull("A: fresh book has no sdConfig via the pb.book objectId",
				PictureBookUtil.getBookSdConfig(ownerA, pb2BookOid));
			assertNull("A: fresh book has no sdConfig via the scene-group objectId",
				PictureBookUtil.getBookSdConfig(ownerA, sceneGroupOid));
		} catch (PictureBookException e) {
			fail("owner A must resolve own book through both handles; got HTTP " + e.getStatus() + " " + e.getMessage());
		}

		// ── B (same org, not the owner, not admin) must be refused on READ via BOTH handles — and the
		// refusal must be indistinguishable from "no such book" (404, not the owner's null). ──
		assertRefused404("B getBookSdConfig(pb.book objectId)",
			() -> PictureBookUtil.getBookSdConfig(otherB, pb2BookOid));
		assertRefused404("B getBookSdConfig(scene-group objectId)",
			() -> PictureBookUtil.getBookSdConfig(otherB, sceneGroupOid));

		// ── A still resolves after B's attempts. ──
		assertNull("A still resolves via pb.book objectId", PictureBookUtil.getBookSdConfig(ownerA, pb2BookOid));
		assertNull("A still resolves via scene-group objectId", PictureBookUtil.getBookSdConfig(ownerA, sceneGroupOid));

		System.out.println("=== /settings CROSS-USER REFUSAL PROOF (same org, non-admin B) ===");
		System.out.println("  owner A id=" + ownerA.get(FieldNames.FIELD_ID) + "  other B id=" + otherB.get(FieldNames.FIELD_ID)
			+ "  org=" + ownerA.get(FieldNames.FIELD_ORGANIZATION_ID));
		System.out.println("  pb.book objectId     = " + pb2BookOid + "  -> A: null (resolved); B read: 404 Book not found");
		System.out.println("  scene GROUP objectId = " + sceneGroupOid + "  -> A: null (resolved); B read: 404 Book not found");
	}
}
