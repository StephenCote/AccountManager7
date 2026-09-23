package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// B2 regression proof: the /scenes and /characters read endpoints resolve a SERIES-CHAPTER
/// {@code olio.pb.book} objectId, not just a PB1 scene-GROUP objectId.
///
/// The series canvas navigates to {@code /picture-book/{chapterBookObjectId}/workflow}, i.e. it holds
/// the {@code olio.pb.book} objectId. But {@link PictureBookUtil#listScenes} / {@link
/// PictureBookUtil#listCharacters} used to resolve the book group ONLY via {@code findBookGroup} (a
/// {@code data.group} objectId lookup) or, in {@code resolveBookGroupEither}, via a scene group NAMED
/// after the book slug. A series-chapter scene group is created under the chapter's bookName (its human
/// TITLE), which differs from the slug — so both lookups missed and {@code /scenes} + {@code /characters}
/// returned HTTP 404 even though the scenes were persisted correctly.
///
/// The fix makes {@code resolveBookGroupEither} fall back to a NAME-INDEPENDENT link: the scene group's
/// own {@code .pictureBookMeta} note records {@code pb2BookObjectId} = the owning book, so the group is
/// found by that meta link regardless of what it was named. This test proves it by resolving the SAME
/// persisted scenes through BOTH handles — the scene-GROUP objectId (the path that already worked) and
/// the chapter {@code olio.pb.book} objectId (the path that used to 404).
///
/// Setup mirrors {@link TestPbSeriesCreateFromScenesOneWorld}: a real {@code olio.pb.series}, a chapter
/// book, then the production {@link PictureBookUtil#createFromScenes} with ONE CASTLESS scene and an
/// empty charDataList (zero characters — no LLM/embedding needed). Crucially the chapter's bookName
/// ("...Chapter... {tag}") deliberately DIFFERS from its slug ("chap{tag}") so the slug lookup misses and
/// the meta-link fallback is the ONLY thing that can resolve the book objectId. Real DB (am7db), run as a
/// dedicated non-admin user.
public class TestPbSeriesChapterScenesResolve extends BaseTest {

	private File locateFixture(String fileName) {
		File[] candidates = new File[] {
			new File("media", fileName),
			new File("AccountManagerObjects7/media", fileName),
			new File("src/AccountManagerObjects7/media", fileName)
		};
		for (File f : candidates) {
			if (f.exists()) {
				return f;
			}
		}
		return null;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/// One minimal scene with NO cast (no "characters" key) so createFromScenes creates zero characters.
	private static List<Map<String, Object>> oneCastlessScene(String title) {
		Map<String, Object> scene = new LinkedHashMap<>();
		scene.put("title", title);
		scene.put("summary", "A quiet establishing shot with no named characters.");
		scene.put("setting", "an empty windswept shoreline at dawn");
		scene.put("action", "waves roll in over grey sand");
		scene.put("mood", "still, expectant");
		scene.put("sourceText", "The tide went out before anyone woke, leaving the long beach to the gulls.");
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene);
		return scenes;
	}

	@Test
	public void testSeriesChapterBookObjectIdResolvesScenesAndCharacters() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbSeriesScenesEndpointUser");
		assertNotNull("test user", testUser);
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// Real manuscript uploaded as this user's own data.data — it is BOTH the chapter sourceData and
		// the createFromScenes work (workObjectId).
		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(testUser, "~/PbSeriesScenesData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String workObjectId = sourceData.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("work/sourceData objectId", workObjectId);

		String tag = shortId();
		String seriesSlug = "scnwld" + tag; // lowercase alnum -> matches BOOK_SLUG_PATTERN
		BaseRecord series = PbSeriesUtil.getCreateSeries(testUser, dataPath, seriesSlug, "Scenes Endpoint Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("series objectId", seriesOid);

		// One chapter. Its slug ("chap{tag}") is DELIBERATELY UNRELATED to the bookName/title so the
		// slug-named scene-group lookup CANNOT resolve it — only the .pictureBookMeta link can.
		String slug = "chap" + tag;
		Map<String, Object> range = new LinkedHashMap<>();
		range.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(0));
		range.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1200));
		range.put(OlioFieldNames.FIELD_PB_TITLE, "Scenes Chapter One");

		Map<String, Object> r = PbServiceFacade.createChapter(testUser, dataPath, seriesOid, null, slug,
			"Scenes Chapter One Title", Integer.valueOf(1), workObjectId, range, null, null);
		assertNotNull("createChapter returned a result", r);

		BaseRecord book = PbBookUtil.findBookBySlug(testUser, slug, orgId);
		assertNotNull("chapter book must be readable", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("chapter book objectId (what the series canvas navigates by)", bookOid);

		// Run createFromScenes with the chapter book objectId as pb2BookObjectId, and a bookName that
		// DIFFERS from the slug (the scene group is created under bookName, not slug).
		String bookName = "Scenes Chapter One " + tag;
		assertNotEquals("bookName must differ from slug so the slug lookup cannot resolve it", slug, bookName);
		String sceneTitle = "Opening " + tag;
		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, oneCastlessScene(sceneTitle), new ArrayList<>(), dataPath, bookOid);
		assertNotNull("createFromScenes returned meta", meta);

		// The PB1 scene GROUP objectId (the handle the standalone/PB1 path already resolves) and the
		// chapter book objectId are DISTINCT — that distinction is the whole bug.
		String sceneGroupObjectId = meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId (PB1 scene GROUP objectId) must be present", sceneGroupObjectId);
		assertNotEquals("scene GROUP objectId and pb.book objectId are different objectIds",
			bookOid, sceneGroupObjectId);
		assertEquals("meta.pb2BookObjectId must be the chapter book objectId (the resolution link)",
			bookOid, meta.get("pb2BookObjectId"));

		// ── PROOF (scenes): the SAME persisted scene resolves via BOTH handles. ──
		// Path that already worked: the scene-GROUP objectId (findBookGroup resolves it directly).
		List<Map<String, Object>> scenesViaGroup = PictureBookUtil.listScenes(testUser, sceneGroupObjectId);
		assertNotNull("listScenes must resolve the scene-group objectId", scenesViaGroup);
		assertEquals("one persisted scene via the scene-group objectId", 1, scenesViaGroup.size());

		// THE FIX: the chapter pb.book objectId now resolves (was PictureBookException 404 before, because
		// findBookGroup(bookOid) is null AND the slug-named scene group does not exist).
		List<Map<String, Object>> scenesViaBook = PictureBookUtil.listScenes(testUser, bookOid);
		assertNotNull("listScenes must resolve a SERIES-CHAPTER pb.book objectId (was 404 before the fix)",
			scenesViaBook);
		assertEquals("the series-chapter book objectId returns the SAME persisted scenes as the group",
			scenesViaGroup.size(), scenesViaBook.size());
		assertEquals("exactly the one scene persisted by createFromScenes", 1, scenesViaBook.size());

		// ── PROOF (characters): same resolution path. Castless => empty cast, but the endpoint must
		// RESOLVE (return a list) for the series-chapter book objectId rather than throw 404. ──
		List<Map<String, Object>> castViaGroup = PictureBookUtil.listCharacters(testUser, sceneGroupObjectId);
		List<Map<String, Object>> castViaBook = PictureBookUtil.listCharacters(testUser, bookOid);
		assertNotNull("listCharacters must resolve the scene-group objectId", castViaGroup);
		assertNotNull("listCharacters must resolve a SERIES-CHAPTER pb.book objectId (was 404 before the fix)",
			castViaBook);
		assertEquals("same (empty) cast via both handles — castless scene", castViaGroup.size(), castViaBook.size());
		assertTrue("castless scene => zero characters", castViaBook.isEmpty());

		System.out.println("=== SERIES-CHAPTER /scenes + /characters RESOLUTION PROOF ===");
		System.out.println("  chapter slug=" + slug + "  bookName=" + bookName + "  (deliberately different)");
		System.out.println("  scene GROUP objectId = " + sceneGroupObjectId);
		System.out.println("  pb.book   objectId   = " + bookOid);
		System.out.println("  listScenes(groupOid)  size=" + scenesViaGroup.size());
		System.out.println("  listScenes(bookOid)   size=" + scenesViaBook.size() + "  <= the fix (was 404)");
		System.out.println("  listCharacters(bookOid) resolved (size=" + castViaBook.size() + ", was 404)");
	}
}
