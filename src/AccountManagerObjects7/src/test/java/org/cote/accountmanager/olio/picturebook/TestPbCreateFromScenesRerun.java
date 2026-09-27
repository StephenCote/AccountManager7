package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Regression proof for the PictureBook RE-RUN scene-wipe defect in
/// {@link PictureBookUtil#createFromScenes(BaseRecord, String, String, String, String, List, List, String, String)}.
///
/// Live mechanism (Docker log, 2026-09-26): re-running the wizard on a manuscript whose chapter books
/// already exist reuses the slug book group + its Scenes sub-group (both find-or-create by path);
/// every {@code createSceneNote} then hit the {@code data.note (name, groupId, organizationId)} unique
/// key ({@code Key (name, groupid, organizationid)=(Harlot's Eight, 1967, 2) already exists}), returned
/// null, the scene fell out of {@code metaScenes}, and {@code saveMeta} overwrote {@code .pictureBookMeta}
/// with {@code scenes: []} while the call returned success. Every chapter then listed 0 scenes.
///
/// Required behaviour under test:
///  1. Re-run is idempotent: an existing scene note with the same name is UPDATED in place (identity
///     reused, content replaced), no duplicate notes, meta reflects exactly the supplied scenes.
///  2. Two scenes with the SAME title in ONE run are disambiguated (" (2)" suffix) and survive a re-run.
///  3. If any scene cannot be persisted, {@code createFromScenes} throws {@code PictureBookException}
///     naming the scene and the previously persisted meta is left intact - never a silent success
///     with a shorter/empty scene list.
///
/// Uses a standalone PB2 book ({@code PbBookUtil.createBook}) rather than a series chapter: the scene
/// loop is shared and the group reuse ({@code pb2GroupName = bookSlug}) is identical, so the standalone
/// path reproduces the exact defect without the manuscript/sourceRange scaffolding. Scenes carry NO
/// cast and {@code chatConfigName} is null, so no LLM/embedding work runs. Real DB; non-admin test user.
public class TestPbCreateFromScenesRerun extends BaseTest {

	private static final String WORK_PATH = "~/PbRerunWork";

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static Map<String, Object> castlessScene(String title, String summary) {
		Map<String, Object> scene = new LinkedHashMap<>();
		scene.put("title", title);
		scene.put("summary", summary);
		scene.put("setting", "a rain-dark harbour at dusk");
		scene.put("action", "lanterns are lit one by one along the quay");
		scene.put("mood", "hushed, expectant");
		scene.put("sourceText", "The lamps came on down the quay while the boats rocked at their moorings.");
		return scene;
	}

	private BaseRecord createWork(BaseRecord user, String tag) throws Exception {
		ParameterList wplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, WORK_PATH);
		wplist.parameter(FieldNames.FIELD_NAME, "rerun-src-" + tag);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, wplist);
		work.set("text", "The lamps came on down the quay while the boats rocked at their moorings. "
			+ "Nobody spoke. The tide was turning and everyone on the harbour wall knew it.");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull("work note must be created", created);
		return created;
	}

	/// Every data.note in the book's Scenes sub-group (the meta note lives in the PARENT book group, so
	/// it is not counted here). Uncached; identity + name + text projected.
	private List<BaseRecord> sceneNotesInBook(BaseRecord user, String bookGroupObjectId) throws Exception {
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(user, bookGroupObjectId);
		assertNotNull("PB1 book group must resolve from meta.bookObjectId", bookGroup);
		String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
		assertNotNull("book group path", bookGroupPath);
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scenesGroup = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP,
			bookGroupPath + "/Scenes", GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Scenes sub-group must exist at " + bookGroupPath + "/Scenes", scenesGroup);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, scenesGroup.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		q.setRequestRange(0, 1000);
		q.setCache(false);
		QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
		List<BaseRecord> out = new ArrayList<>();
		if (qr != null && qr.getResults() != null) {
			for (BaseRecord r : qr.getResults()) out.add(r);
		}
		return out;
	}

	private static Map<String, Object> textJson(BaseRecord note) {
		String text = note.get("text");
		assertNotNull("scene note text", text);
		return JSONUtil.getMap(text.getBytes(), String.class, Object.class);
	}

	@SuppressWarnings("unchecked")
	private static List<String> sceneObjectIds(BaseRecord meta) {
		List<String> oids = new ArrayList<>();
		List<BaseRecord> scenes = meta.get("scenes");
		assertNotNull("meta.scenes", scenes);
		for (BaseRecord s : scenes) oids.add(s.get(FieldNames.FIELD_OBJECT_ID));
		return oids;
	}

	@Test
	public void testRerunUpdatesExistingSceneNotesInPlace() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbRerunUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);

		String slug = "pbrerun" + tag;
		String bookName = "PB Rerun Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);

		List<Map<String, Object>> run1 = new ArrayList<>();
		run1.add(castlessScene("Harbour Lamps", "run1 summary A"));
		run1.add(castlessScene("The Turning Tide", "run1 summary B"));
		run1.add(castlessScene("Wall Watchers", "run1 summary C"));

		// ── RUN 1: first extraction → 3 fresh notes, meta with 3 scenes ──
		BaseRecord meta1 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, run1, new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("run 1 returned meta", meta1);
		String bookGroupOid = meta1.get("bookObjectId");
		assertNotNull("meta.bookObjectId (PB1 group)", bookGroupOid);
		List<String> oids1 = sceneObjectIds(meta1);
		assertEquals("run 1 meta carries one entry per supplied scene", 3, oids1.size());
		assertEquals("run 1 persisted 3 scene notes", 3, sceneNotesInBook(testUser, bookGroupOid).size());
		assertEquals("run 1 listScenes (persisted meta) reports 3", 3, PictureBookUtil.listScenes(testUser, bookGroupOid).size());

		// Simulate wizard render state on scene 0 so the carry-over rule can be verified: status is
		// written into the note's text JSON by the same path the wizard uses (setSceneStatus →
		// updateSceneStatus → updateSceneTextField).
		PictureBookUtil.setSceneStatus(testUser, oids1.get(0), "accepted");

		// ── RUN 2: SAME titles (what a wizard re-run on the same manuscript supplies), new content ──
		List<Map<String, Object>> run2 = new ArrayList<>();
		run2.add(castlessScene("Harbour Lamps", "run2 summary A"));
		run2.add(castlessScene("The Turning Tide", "run2 summary B"));
		run2.add(castlessScene("Wall Watchers", "run2 summary C"));

		BaseRecord meta2 = null;
		try {
			meta2 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
				bookName, run2, new ArrayList<>(), dataPath, pb2BookOid);
		} catch (PictureBookException e) {
			fail("re-run must not fail: " + e.getStatus() + " " + e.getMessage());
		}
		assertNotNull("run 2 returned meta", meta2);
		assertEquals("run 2 reuses the same PB1 book group", bookGroupOid, meta2.get("bookObjectId"));
		List<String> oids2 = sceneObjectIds(meta2);
		assertEquals("run 2 meta carries one entry per supplied scene (NOT wiped to 0)", 3, oids2.size());
		assertEquals("run 2 reused the run-1 note identities, in order", oids1, oids2);

		List<BaseRecord> notes2 = sceneNotesInBook(testUser, bookGroupOid);
		assertEquals("no duplicate scene notes were created by the re-run", 3, notes2.size());
		Set<String> names = new HashSet<>();
		for (BaseRecord n : notes2) names.add(((String) n.get(FieldNames.FIELD_NAME)).toLowerCase());
		assertEquals("the three note names are distinct", 3, names.size());

		// Content was UPDATED in place (blurb/summary reflect run 2; sceneIndex correct); render state carried.
		Map<String, BaseRecord> byOid = new LinkedHashMap<>();
		for (BaseRecord n : notes2) byOid.put(n.get(FieldNames.FIELD_OBJECT_ID), n);
		for (int i = 0; i < oids2.size(); i++) {
			BaseRecord n = byOid.get(oids2.get(i));
			assertNotNull("meta scene " + i + " points at a persisted note", n);
			Map<String, Object> tj = textJson(n);
			assertEquals("scene " + i + " blurb was replaced by run 2's summary", "run2 summary " + (char) ('A' + i), tj.get("blurb"));
			assertEquals("scene " + i + " sceneIndex", i, ((Number) tj.get("sceneIndex")).intValue());
			assertNull("transient sourceText never persists", tj.get("sourceText"));
		}
		assertEquals("render state (status) on scene 0 survived the in-place update", "accepted",
			textJson(byOid.get(oids2.get(0))).get("status"));

		// The PERSISTED meta (what GET /scenes reads) has all 3 - this is the assertion the live re-run failed.
		List<Map<String, Object>> listed = PictureBookUtil.listScenes(testUser, bookGroupOid);
		assertEquals("listScenes after re-run reports 3, not 0", 3, listed.size());
		Set<String> listedOids = new HashSet<>();
		for (Map<String, Object> s : listed) listedOids.add((String) s.get("objectId"));
		assertEquals("listed scene objectIds are the reused note identities", new HashSet<>(oids1), listedOids);
		assertEquals("listed scene 1 description reflects run 2's blurb", "run2 summary B", listed.get(1).get("description"));

		System.out.println("=== createFromScenes RE-RUN PROOF === slug=" + slug + " scenes run1=" + oids1.size()
			+ " run2=" + oids2.size() + " notesInGroup=" + notes2.size() + " identitiesReused=" + oids1.equals(oids2));
	}

	@Test
	public void testDuplicateTitlesWithinOneRunAreDisambiguatedAndSurviveRerun() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbRerunUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String slug = "pbdup" + tag;
		String bookName = "PB Dup Title Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);

		// Two chunks both titled "Chapter 1" - a real LLM does this - plus one distinct title.
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(castlessScene("Chapter 1", "first chunk"));
		scenes.add(castlessScene("Chapter 1", "second chunk"));
		scenes.add(castlessScene("Chapter 2", "third chunk"));

		BaseRecord meta1 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("run 1 returned meta", meta1);
		String bookGroupOid = meta1.get("bookObjectId");
		List<String> oids1 = sceneObjectIds(meta1);
		assertEquals("all 3 scenes persisted despite two identical titles", 3, oids1.size());
		assertEquals("3 distinct note identities", 3, new HashSet<>(oids1).size());

		List<BaseRecord> notes1 = sceneNotesInBook(testUser, bookGroupOid);
		assertEquals("3 scene notes in the Scenes group", 3, notes1.size());
		Set<String> names1 = new HashSet<>();
		for (BaseRecord n : notes1) names1.add(n.get(FieldNames.FIELD_NAME));
		assertTrue("first duplicate keeps its title as note name: " + names1, names1.contains("Chapter 1"));
		assertTrue("second duplicate is suffixed: " + names1, names1.contains("Chapter 1 (2)"));
		assertTrue("distinct title unchanged: " + names1, names1.contains("Chapter 2"));

		// Re-run the identical list: the suffixed name must resolve to the SAME note, not collide/create.
		BaseRecord meta2 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("run 2 returned meta", meta2);
		List<String> oids2 = sceneObjectIds(meta2);
		assertEquals("re-run keeps 3 scenes", 3, oids2.size());
		assertEquals("re-run reused all 3 identities in order", oids1, oids2);
		assertEquals("re-run created no extra notes", 3, sceneNotesInBook(testUser, bookGroupOid).size());
		assertEquals("listScenes after re-run", 3, PictureBookUtil.listScenes(testUser, bookGroupOid).size());
	}

	@Test
	public void testUnpersistableSceneFailsLoudAndLeavesMetaIntact() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbRerunUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String slug = "pbfail" + tag;
		String bookName = "PB Fail Loud Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);

		// Baseline: a good first run with 2 scenes.
		List<Map<String, Object>> good = new ArrayList<>();
		good.add(castlessScene("Good One", "ok"));
		good.add(castlessScene("Good Two", "ok"));
		BaseRecord meta1 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, good, new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("baseline run returned meta", meta1);
		String bookGroupOid = meta1.get("bookObjectId");
		assertEquals("baseline persisted 2 scenes", 2, PictureBookUtil.listScenes(testUser, bookGroupOid).size());

		// Force ONE unpersistable scene: data.note.name is maxLength 256 (common.name), so a 400-char
		// title cannot be written as a note name. Cheap, deterministic, and exactly the "create returned
		// null" branch the live defect hit.
		StringBuilder longTitle = new StringBuilder();
		while (longTitle.length() < 400) longTitle.append("toolong-").append(tag).append('-');
		List<Map<String, Object>> bad = new ArrayList<>();
		bad.add(castlessScene("Good One", "ok again"));
		bad.add(castlessScene(longTitle.toString(), "cannot persist"));
		bad.add(castlessScene("Good Two", "ok again"));

		PictureBookException thrown = null;
		BaseRecord metaBad = null;
		try {
			metaBad = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
				bookName, bad, new ArrayList<>(), dataPath, pb2BookOid);
		} catch (PictureBookException e) {
			thrown = e;
		}
		assertNull("a run with an unpersistable scene must NOT return a meta", metaBad);
		assertNotNull("a run with an unpersistable scene must throw PictureBookException", thrown);
		assertEquals("mapped to 500 by Service7's handlePictureBookException", 500, thrown.getStatus());
		assertTrue("message names the failing scene index: " + thrown.getMessage(), thrown.getMessage().contains("1: toolong-"));
		assertTrue("message states the count: " + thrown.getMessage(), thrown.getMessage().contains("1 of 3 scene(s)"));

		// The previously persisted meta was NOT replaced by a shorter/empty one.
		List<Map<String, Object>> after = PictureBookUtil.listScenes(testUser, bookGroupOid);
		assertEquals("persisted meta still has the baseline 2 scenes (not 0, not 2-of-3)", 2, after.size());
		Set<String> afterOids = new HashSet<>();
		for (Map<String, Object> s : after) afterOids.add((String) s.get("objectId"));
		assertEquals("persisted meta still points at the baseline notes", new HashSet<>(sceneObjectIds(meta1)), afterOids);
		assertFalse("nothing was deleted", sceneNotesInBook(testUser, bookGroupOid).isEmpty());
		assertTrue("sanity: the failing title really exceeded the 256-char name limit", longTitle.length() > 256);
	}
}
