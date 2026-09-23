package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Regression proof for the N-series duplicate-world defect in
/// {@link PictureBookUtil#createFromScenes(BaseRecord, String, String, String, String, java.util.List, java.util.List, String, String)}
/// (the 9-arg overload). Before the fix, that method resolved a chapter book's Olio world by the
/// chapter's OWN slug via {@code PbOlioContextUtil.getCreateBookContext(bookSlug)}, which is
/// find-or-create keyed on that slug. A series chapter book does NOT own a per-chapter world - its
/// {@code world} FK points at the ONE shared series world (named after the SERIES slug). Chapter slug
/// != series-world name, so the old call MISSED the shared world and MINTED a duplicate per-chapter
/// world, stranding every chapter-after-the-first's scenes/cast in an orphan world.
///
/// This test builds a real {@code olio.pb.series} (which creates its ONE shared world) plus TWO chapter
/// books, then runs the production {@link PictureBookUtil#createFromScenes} for EACH chapter and asserts
/// that NO world named after either chapter slug was created under the shared book-worlds group
/// ({@code /Olio/Universes/Books/Worlds}), while the single series world (named after the series slug)
/// still resolves. The buggy code would leave two per-chapter worlds here; the fix leaves none.
///
/// The scenes carry NO cast and {@code charDataList} is empty, so {@code createFromScenes} creates ZERO
/// characters - the world-resolution branch under test runs at the very top of the method, before any
/// character/scene work, so this exercises the exact defect without the character-generation
/// (embedding/LLM) machinery. Real DB (am7db); runs as a dedicated non-admin test user. Series/world
/// rows are olio-principal-owned, so world lookups are performed as the olio principal (their owner),
/// mirroring {@code PbOlioContextUtil.findBookWorld}/{@code resolveSeriesWorldSlug}.
public class TestPbSeriesCreateFromScenesOneWorld extends BaseTest {

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

	private static Long idOf(BaseRecord fk) {
		if (fk == null) {
			return null;
		}
		Object id = fk.get(FieldNames.FIELD_ID);
		return (id instanceof Number) ? Long.valueOf(((Number) id).longValue()) : null;
	}

	private static boolean isNullOrEmpty(Object listVal) {
		return listVal == null || (listVal instanceof List && ((List<?>) listVal).isEmpty());
	}

	/// One minimal scene with NO cast (no "characters" key) so createFromScenes creates zero
	/// characters. sourceText/setting/action/mood/title/summary are the ordinary scene keys the
	/// scene-note path persists; none are character-dependent.
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
	public void testTwoChaptersCreateFromScenesShareOneWorld() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbCfsOneWorldUser");
		assertNotNull("test user", testUser);
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal must resolve (owner of the book-worlds tree)", olioUser);

		// The real manuscript, uploaded as this user's own data.data - it is BOTH the chapter
		// sourceData and the createFromScenes work (workObjectId).
		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(testUser, "~/PbCfsOneWorldData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String workObjectId = sourceData.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("work/sourceData objectId", workObjectId);

		String tag = shortId();
		String seriesSlug = "cfswld" + tag; // lowercase alnum -> matches BOOK_SLUG_PATTERN
		BaseRecord series = PbSeriesUtil.getCreateSeries(testUser, dataPath, seriesSlug, "CFS One World Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("series objectId", seriesOid);

		BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		assertNotNull("series must reference its ONE shared world via universe FK", seriesWorld);
		Long seriesWorldId = idOf(seriesWorld);
		assertNotNull("series world FK must carry an id", seriesWorldId);
		assertEquals("the shared world is named by the series slug", seriesSlug, seriesWorld.get(FieldNames.FIELD_NAME));

		// Two distinct chapters, distinct lowercase slugs, distinct source spans, no cast copy (null).
		String slug1 = "cfsa" + tag;
		String slug2 = "cfsb" + tag;
		Map<String, Object> range1 = new LinkedHashMap<>();
		range1.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(0));
		range1.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1200));
		range1.put(OlioFieldNames.FIELD_PB_TITLE, "CFS Chapter A");
		Map<String, Object> range2 = new LinkedHashMap<>();
		range2.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(1200));
		range2.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(2600));
		range2.put(OlioFieldNames.FIELD_PB_TITLE, "CFS Chapter B");

		Map<String, Object> r1 = PbServiceFacade.createChapter(testUser, dataPath, seriesOid, null, slug1,
			"CFS Chapter A Title", Integer.valueOf(1), workObjectId, range1, null, null);
		assertNotNull("createChapter #1 returned a result", r1);
		Map<String, Object> r2 = PbServiceFacade.createChapter(testUser, dataPath, seriesOid, null, slug2,
			"CFS Chapter B Title", Integer.valueOf(2), workObjectId, range2, null, null);
		assertNotNull("createChapter #2 returned a result", r2);

		BaseRecord book1 = PbBookUtil.findBookBySlug(testUser, slug1, orgId);
		BaseRecord book2 = PbBookUtil.findBookBySlug(testUser, slug2, orgId);
		assertNotNull("chapter 1 must be readable", book1);
		assertNotNull("chapter 2 must be readable", book2);
		String book1Oid = book1.get(FieldNames.FIELD_OBJECT_ID);
		String book2Oid = book2.get(FieldNames.FIELD_OBJECT_ID);
		assertNotEquals("the two chapters must be distinct book rows", book1Oid, book2Oid);

		// Sanity: before createFromScenes, both chapters already share the series world (proved
		// independently by TestPbSeriesOneWorld) and no per-chapter world exists yet.
		assertEquals("chapter 1's world must BE the series world (pre-condition)", seriesWorldId,
			idOf(book1.get(OlioFieldNames.FIELD_PB_WORLD)));
		assertEquals("chapter 2's world must BE the series world (pre-condition)", seriesWorldId,
			idOf(book2.get(OlioFieldNames.FIELD_PB_WORLD)));
		assertNull("no per-chapter world for slug1 should exist before createFromScenes",
			WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug1));
		assertNull("no per-chapter world for slug2 should exist before createFromScenes",
			WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug2));

		// ── THE PATH UNDER TEST: run createFromScenes for EACH chapter (the wizard Step-2->3 call). ──
		// Tag the bookName (and scene title) so each run gets a fresh PB1 book group + Scenes group -
		// data.note has a unique (name, groupId, organizationId) constraint, so a fixed name would
		// collide with a prior run's persisted scene note.
		String bookName1 = "CFS Chapter A " + tag;
		String bookName2 = "CFS Chapter B " + tag;
		String sceneTitle = "Opening " + tag;
		BaseRecord meta1 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName1, oneCastlessScene(sceneTitle), new ArrayList<>(), dataPath, book1Oid);
		assertNotNull("createFromScenes #1 returned meta", meta1);
		BaseRecord meta2 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName2, oneCastlessScene(sceneTitle), new ArrayList<>(), dataPath, book2Oid);
		assertNotNull("createFromScenes #2 returned meta", meta2);

		// Return-key shape (the wizard client reads these): bookObjectId = PB1 book GROUP objectId;
		// pb2BookObjectId = the chapter book objectId (guarded key, present because pb2 arg non-blank);
		// scenes present (one entry per input scene). failedCharacters/failedExtractions are guarded
		// keys - absent here because zero characters were created and nothing failed.
		assertNotNull("meta1.bookObjectId (PB1 group objectId) must be present", meta1.get("bookObjectId"));
		assertNotEquals("meta1.bookObjectId is the PB1 GROUP objectId, not the PB2 book objectId",
			book1Oid, meta1.get("bookObjectId"));
		assertEquals("meta1.pb2BookObjectId must be the chapter book objectId", book1Oid, meta1.get("pb2BookObjectId"));
		assertEquals("meta2.pb2BookObjectId must be the chapter book objectId", book2Oid, meta2.get("pb2BookObjectId"));
		List<?> scenes1 = meta1.get("scenes");
		assertNotNull("meta1.scenes must be present", scenes1);
		assertEquals("meta1.scenes carries one entry per input scene", 1, scenes1.size());
		// failedCharacters/failedExtractions are only SET by createFromScenes when non-empty, but the
		// meta schema instantiates list fields to an empty list, so they read back as [] (not null)
		// when nothing failed. Either way they must be empty here (zero characters created).
		assertTrue("no character failed (list absent or empty)", isNullOrEmpty(meta1.get("failedCharacters")));
		assertTrue("no extraction failed (list absent or empty)", isNullOrEmpty(meta1.get("failedExtractions")));

		// ── THE CORE PROOF: createFromScenes minted NO per-chapter world for either chapter. ──
		BaseRecord chapWorld1 = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug1);
		BaseRecord chapWorld2 = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug2);
		assertNull("createFromScenes must NOT mint a per-chapter world named after chapter slug1", chapWorld1);
		assertNull("createFromScenes must NOT mint a per-chapter world named after chapter slug2", chapWorld2);

		// The ONE shared series world still resolves and is unchanged.
		BaseRecord seriesWorldAfter = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug);
		assertNotNull("the ONE shared series world must still resolve after createFromScenes", seriesWorldAfter);
		assertEquals("the shared series world id is unchanged", seriesWorldId, idOf(seriesWorldAfter));

		// createFromScenes did not repoint either chapter's world FK off the shared world.
		BaseRecord book1After = PbBookUtil.findBookBySlug(testUser, slug1, orgId);
		BaseRecord book2After = PbBookUtil.findBookBySlug(testUser, slug2, orgId);
		assertEquals("chapter 1 still shares the series world after createFromScenes", seriesWorldId,
			idOf(book1After.get(OlioFieldNames.FIELD_PB_WORLD)));
		assertEquals("chapter 2 still shares the series world after createFromScenes", seriesWorldId,
			idOf(book2After.get(OlioFieldNames.FIELD_PB_WORLD)));

		assertTrue("both chapters share ONE world (the series world), zero per-chapter worlds minted",
			chapWorld1 == null && chapWorld2 == null);
		assertFalse("series world resolves (sanity)", seriesWorldAfter == null);

		System.out.println("=== createFromScenes ONE-SHARED-WORLD PROOF ===");
		System.out.println("  series slug=" + seriesSlug + " world.id=" + seriesWorldId);
		System.out.println("  chapter 1 slug=" + slug1 + " book.objectId=" + book1Oid
			+ " per-chapter-world=" + (chapWorld1 == null ? "NONE" : idOf(chapWorld1)));
		System.out.println("  chapter 2 slug=" + slug2 + " book.objectId=" + book2Oid
			+ " per-chapter-world=" + (chapWorld2 == null ? "NONE" : idOf(chapWorld2)));
		System.out.println("  => createFromScenes routed both chapters into the ONE series world id=" + seriesWorldId);
	}
}
