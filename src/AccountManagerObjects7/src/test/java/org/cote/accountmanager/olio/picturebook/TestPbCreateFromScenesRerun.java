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
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
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

	/// As above, but carrying chunk provenance the way extractChunkedInternal's scenes do.
	private static Map<String, Object> castlessScene(String title, String summary, int sourceChunk) {
		Map<String, Object> scene = castlessScene(title, summary);
		scene.put("sourceChunk", sourceChunk);
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
		// Run 2 carries chunk provenance (as extractChunkedInternal's scenes do): chunk = 10 + scene index.
		run2.add(castlessScene("Harbour Lamps", "run2 summary A", 10));
		run2.add(castlessScene("The Turning Tide", "run2 summary B", 11));
		run2.add(castlessScene("Wall Watchers", "run2 summary C", 12));

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
			assertNotNull("scene " + i + " sourceChunk provenance persists in the scene note JSON", tj.get("sourceChunk"));
			assertEquals("scene " + i + " sourceChunk is the chunk index supplied by run 2", 10 + i,
				((Number) tj.get("sourceChunk")).intValue());
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

	/// Chunk-0 provenance. RecordSerializer omits INT fields equal to the schema default, and
	/// olio.pictureBookScene.sourceChunk had no declared default, so every scene extracted from the
	/// FIRST chunk of a chapter (sourceChunk == 0) was written to .pictureBookMeta without the key
	/// while chunk >= 1 scenes kept theirs (measured 2026-09-28: 29 chunk-0 scenes across 10
	/// chapters came back from GET /scenes with no sourceChunk). Chunk >= 1 was already covered
	/// above (10 + i); this pins 0, and that a directly-supplied scene (no chunk) stays absent
	/// rather than acquiring a bogus value.
	@Test
	public void testChunkZeroSourceChunkSurvivesInMetaAndListScenes() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbRerunUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String slug = "pbchunk0" + tag;
		String bookName = "PB Chunk Zero Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);

		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(castlessScene("First Chunk Scene", "from chunk 0", 0));
		scenes.add(castlessScene("Second Chunk Scene", "from chunk 1", 1));
		scenes.add(castlessScene("Direct Scene", "supplied directly, no chunk"));

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, new ArrayList<>(), dataPath, pb2BookOid);
		assertNotNull("createFromScenes returned meta", meta);
		String bookGroupOid = meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId (PB1 group)", bookGroupOid);

		// The persisted .pictureBookMeta JSON itself (what listScenes / GET /scenes parse).
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(testUser, bookGroupOid);
		assertNotNull("PB1 book group", bookGroup);
		Query mq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, bookGroup.get(FieldNames.FIELD_ID));
		mq.field(FieldNames.FIELD_NAME, PictureBookUtil.META_NOTE_NAME);
		mq.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		mq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		mq.setCache(false);
		BaseRecord metaNote = IOSystem.getActiveContext().getAccessPoint().find(testUser, mq);
		assertNotNull(".pictureBookMeta note", metaNote);
		String metaJson = metaNote.get("text");
		assertNotNull(".pictureBookMeta text", metaJson);
		String compactMeta = metaJson.replaceAll("\\s+", "");
		assertTrue("meta JSON carries sourceChunk 0 for the first-chunk scene: " + metaJson, compactMeta.contains("\"sourceChunk\":0,"));
		assertFalse("the unset default (-1) never serializes: " + metaJson, compactMeta.contains("\"sourceChunk\":-1"));

		List<Map<String, Object>> listed = PictureBookUtil.listScenes(testUser, bookGroupOid);
		assertEquals("listScenes reports all 3", 3, listed.size());
		assertEquals("scene 0 title", "First Chunk Scene", listed.get(0).get("title"));
		assertTrue("scene 0 sourceChunk is present", listed.get(0).containsKey("sourceChunk"));
		assertEquals("scene 0 sourceChunk is 0", 0, ((Number) listed.get(0).get("sourceChunk")).intValue());
		assertEquals("scene 1 sourceChunk is 1", 1, ((Number) listed.get(1).get("sourceChunk")).intValue());
		assertFalse("a directly-supplied scene carries no sourceChunk at all", listed.get(2).containsKey("sourceChunk"));
	}

	/// olio.charPerson by objectId, uncached, with race AND raceLabel projected (neither is a default
	/// query field; an unprojected raceLabel would read as absent and hide a stored value).
	private BaseRecord findCharPersonWithRace(BaseRecord user, String charObjectId) throws Exception {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_RACE, OlioFieldNames.FIELD_RACE_LABEL });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static String soleCharacterObjectId(BaseRecord meta) {
		List<BaseRecord> scenes = meta.get("scenes");
		assertNotNull("meta.scenes", scenes);
		assertEquals("one scene", 1, scenes.size());
		List<String> chars = scenes.get(0).get("characters");
		assertNotNull("scene.characters", chars);
		assertEquals("one character on the scene: " + chars, 1, chars.size());
		return chars.get(0);
	}

	private static Map<String, Object> cast(String name, String race) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("name", name);
		if (race != null) c.put("race", race);
		return c;
	}

	/// Fill-only race enrichment of an EXISTING charPerson. Chaptered manuscripts create each character
	/// on the first chapter it appears in; race is written from the text alone (never the random
	/// baseline), so a character whose first chapter states no race is created with race []. Measured
	/// 2026-09-28 on the JOSIEFIED run: chapter 4 grounded "Fairy" for a character chapter 1 had created
	/// raceless, and createCharPerson's existing-record branch returned the record untouched — the
	/// stated race was lost. Rule under test (Stephen, 2026-09-28): fill when empty, NEVER overwrite.
	/// The cast entries carry only name/race and chatConfigName is null, so no LLM call is made.
	@Test
	public void testRaceFillOnlyOnExistingCharPerson() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbRerunUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String slug = "pbrace" + tag;
		String bookName = "PB Race Fill Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);

		String charName = "Wren Ashcombe " + tag;
		List<Map<String, Object>> scenes = new ArrayList<>();
		Map<String, Object> scene = castlessScene("Wren on the Quay", "Wren watches the lamps");
		scene.put("characters", new ArrayList<>(List.of(charName)));
		scenes.add(scene);

		// ── RUN 1: the text states no race → created with race [] ──
		List<Map<String, Object>> cast1 = new ArrayList<>();
		cast1.add(cast(charName, null));
		BaseRecord meta1 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, cast1, dataPath, pb2BookOid);
		assertNotNull("run 1 returned meta", meta1);
		String charOid = soleCharacterObjectId(meta1);
		BaseRecord cp1 = findCharPersonWithRace(testUser, charOid);
		assertNotNull("run 1 charPerson", cp1);
		List<String> race1 = cp1.get("race");
		assertTrue("run 1: no race stated, none written (random baseline must not leak): " + race1,
			race1 == null || race1.isEmpty());

		// ── RUN 2: a later chapter grounds "Fairy" → filled onto the SAME record ──
		List<Map<String, Object>> cast2 = new ArrayList<>();
		cast2.add(cast(charName, "Fairy"));
		BaseRecord meta2 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, cast2, dataPath, pb2BookOid);
		assertNotNull("run 2 returned meta", meta2);
		assertEquals("run 2 reuses the existing charPerson", charOid, soleCharacterObjectId(meta2));
		BaseRecord cp2 = findCharPersonWithRace(testUser, charOid);
		assertNotNull("run 2 charPerson", cp2);
		assertEquals("run 2: empty race filled with the stated Fairy (Z)", List.of("Z"), cp2.get("race"));

		// ── RUN 3: a still-later chapter says "White" → ignored, first grounded race stands ──
		List<Map<String, Object>> cast3 = new ArrayList<>();
		cast3.add(cast(charName, "White"));
		BaseRecord meta3 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, cast3, dataPath, pb2BookOid);
		assertNotNull("run 3 returned meta", meta3);
		assertEquals("run 3 reuses the existing charPerson", charOid, soleCharacterObjectId(meta3));
		BaseRecord cp3 = findCharPersonWithRace(testUser, charOid);
		assertNotNull("run 3 charPerson", cp3);
		assertEquals("run 3: a non-empty race is never overwritten", List.of("Z"), cp3.get("race"));
	}

	/// A cast entry in the shape the grounding gate writes into charData: race="Custom" plus
	/// race_label=<the text's own word>. See the design note on testCustomRaceLabelFillOnly for why
	/// the test supplies this shape rather than the raw off-list word.
	private static Map<String, Object> customCast(String name, String label) {
		Map<String, Object> c = cast(name, RaceEnumType.valueOf(RaceEnumType.O));
		c.put(PictureBookUtil.KEY_RACE_LABEL, label);
		return c;
	}

	private void assertColumnExists(String modelName, String column) {
		DBUtil dbUtil = ioContext.getDbUtil();
		assertTrue("table for " + modelName + " must exist", dbUtil.haveTable(modelName));
		String table = dbUtil.getTableName(modelName);
		List<String> cols = dbUtil.getTableColumns(table);
		assertFalse("information_schema returned no columns for " + table, cols.isEmpty());
		assertTrue("column '" + column + "' must exist on " + table + " (boot DDL patch adds it); columns: " + cols,
			cols.contains(column.toLowerCase()));
	}

	/// Custom race (RaceEnumType.O) + raceLabel on a LIVE charPerson: the new nullable column exists on
	/// both tables, an O race is created together with its label, the fill-only rule never overwrites
	/// a stored label, and a stored O whose label is blank gets ONLY the label filled.
	///
	/// Cast-supplied races are user-authored and are NOT grounded against the text ({@code
	/// groundRaceAndEthnicity} runs only on LLM output). A raw off-list cast race such as
	/// {@code race:"Mer-folk"} is normalized by {@code normalizeCastRace} into the same shape the LLM
	/// grounding gate writes ({@code race:"Custom", race_label:"Mer-folk"}) before createCharPerson sees
	/// it — the first block pins that this is the no-recompile path to a race the enum does not name.
	/// The fill-only blocks below use the explicit Custom+label shape, which passes through unchanged.
	/// No LLM is called (chatConfigName null).
	@Test
	public void testCustomRaceLabelFillOnly() throws Exception {
		OlioModelNames.use();
		BaseRecord testUser = getCreateUser("pbRerunUser");
		assertNotNull("test user", testUser);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		// ── The column the feature persists into must exist on both tables (boot DDL patch). ──
		assertColumnExists(ModelNames.MODEL_PERSON, OlioFieldNames.FIELD_RACE_LABEL);
		assertColumnExists(OlioModelNames.MODEL_CHAR_PERSON, OlioFieldNames.FIELD_RACE_LABEL);

		String tag = shortId();
		BaseRecord work = createWork(testUser, tag);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String slug = "pbcustom" + tag;
		String bookName = "PB Custom Race Book " + tag;
		BaseRecord pb2Book = PbBookUtil.createBook(testUser, dataPath, slug, bookName);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);

		// ── A raw off-list cast race becomes Custom + label without any recompile or grounding ──
		String rawName = "Nerine Saltwater " + tag;
		List<Map<String, Object>> rawScenes = new ArrayList<>();
		Map<String, Object> rawScene = castlessScene("Nerine Surfaces", "Nerine, eldest of the Mer-folk, surfaces at dusk");
		rawScene.put("sourceText", "The Mer-folk of the bay surfaced at dusk. Nerine, eldest of the Mer-folk, watched the quay.");
		rawScene.put("characters", new ArrayList<>(List.of(rawName)));
		rawScenes.add(rawScene);
		List<Map<String, Object>> rawCast = new ArrayList<>();
		rawCast.add(cast(rawName, "Mer-folk"));
		BaseRecord rawMeta = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, rawScenes, rawCast, dataPath, pb2BookOid);
		assertNotNull("raw off-list run returned meta", rawMeta);
		BaseRecord rawCp = findCharPersonWithRace(testUser, soleCharacterObjectId(rawMeta));
		assertNotNull("raw off-list charPerson", rawCp);
		assertEquals("raw cast race 'Mer-folk' is off-list, so it is stored as Custom",
			List.of(RaceEnumType.O.name()), rawCp.get(OlioFieldNames.FIELD_RACE));
		assertEquals("the cast's own word is the label", "Mer-folk", NarrativeUtil.getRaceLabel(rawCp));
		// An ethnicity word supplied as a cast race is still dropped, never promoted (same rule as the LLM path).
		String ethName = "Moira Saltwater " + tag;
		List<Map<String, Object>> ethScenes = new ArrayList<>();
		Map<String, Object> ethScene = castlessScene("Moira on the Strand", "Moira walks the strand");
		ethScene.put("characters", new ArrayList<>(List.of(ethName)));
		ethScenes.add(ethScene);
		List<Map<String, Object>> ethCast = new ArrayList<>();
		ethCast.add(cast(ethName, "Scottish"));
		BaseRecord ethMeta = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, ethScenes, ethCast, dataPath, pb2BookOid);
		assertNotNull("ethnicity-word run returned meta", ethMeta);
		BaseRecord ethCp = findCharPersonWithRace(testUser, soleCharacterObjectId(ethMeta));
		assertNotNull("ethnicity-word charPerson", ethCp);
		List<String> ethRace = ethCp.get(OlioFieldNames.FIELD_RACE);
		assertTrue("cast race 'Scottish' names an ethnicity and is dropped, not promoted: " + ethRace,
			ethRace == null || ethRace.isEmpty());
		assertNull("no label for a dropped race", NarrativeUtil.getRaceLabel(ethCp));

		// ── RUN 1: post-grounding shape → created with race ["O"] and raceLabel "Mer-folk" ──
		String charName = "Nerine Deepwater " + tag;
		List<Map<String, Object>> scenes = new ArrayList<>();
		Map<String, Object> scene = castlessScene("Nerine on the Quay", "Nerine watches the lamps");
		scene.put("characters", new ArrayList<>(List.of(charName)));
		scenes.add(scene);
		List<Map<String, Object>> cast1 = new ArrayList<>();
		cast1.add(customCast(charName, "Mer-folk"));
		BaseRecord meta1 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, cast1, dataPath, pb2BookOid);
		assertNotNull("run 1 returned meta", meta1);
		String charOid = soleCharacterObjectId(meta1);
		BaseRecord cp1 = findCharPersonWithRace(testUser, charOid);
		assertNotNull("run 1 charPerson", cp1);
		assertEquals("run 1: Custom race stored as the constant name", List.of(RaceEnumType.O.name()), cp1.get(OlioFieldNames.FIELD_RACE));
		assertEquals("run 1: the text's own word is the label", "Mer-folk", cp1.get(OlioFieldNames.FIELD_RACE_LABEL));
		assertEquals("Mer-folk", NarrativeUtil.getRaceLabel(cp1));
		assertEquals("the record renders its label, never the word Custom", "Mer-folk",
			NarrativeUtil.getRaceDescription(cp1.get(OlioFieldNames.FIELD_RACE), NarrativeUtil.getRaceLabel(cp1)));

		// ── RUN 2: a later chapter says Selkie → stored race AND label are untouched ──
		List<Map<String, Object>> cast2 = new ArrayList<>();
		cast2.add(customCast(charName, "Selkie"));
		BaseRecord meta2 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, cast2, dataPath, pb2BookOid);
		assertNotNull("run 2 returned meta", meta2);
		assertEquals("run 2 reuses the existing charPerson", charOid, soleCharacterObjectId(meta2));
		BaseRecord cp2 = findCharPersonWithRace(testUser, charOid);
		assertNotNull("run 2 charPerson", cp2);
		assertEquals("run 2: race never overwritten", List.of(RaceEnumType.O.name()), cp2.get(OlioFieldNames.FIELD_RACE));
		assertEquals("run 2: a stored label is never overwritten", "Mer-folk", cp2.get(OlioFieldNames.FIELD_RACE_LABEL));
		// An on-list race on a later chapter does not re-race a Custom character either.
		List<Map<String, Object>> cast2b = new ArrayList<>();
		cast2b.add(cast(charName, "Fairy"));
		PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction", bookName, scenes, cast2b, dataPath, pb2BookOid);
		BaseRecord cp2b = findCharPersonWithRace(testUser, charOid);
		assertEquals("run 2b: Fairy does not replace Custom", List.of(RaceEnumType.O.name()), cp2b.get(OlioFieldNames.FIELD_RACE));
		assertEquals("run 2b: label untouched", "Mer-folk", cp2b.get(OlioFieldNames.FIELD_RACE_LABEL));

		// ── The PB2 staleness hash watches raceLabel: a Custom race renders as its label, so a label edit
		//    must stale a portrait binding exactly as a race edit would. Stable across an unchanged re-read.
		String hashLabelled = PbWatchedFields.computeRefHash(testUser, OlioModelNames.MODEL_CHAR_PERSON, charOid);
		assertNotNull("refHash for a live charPerson", hashLabelled);
		assertEquals("refHash is deterministic for an unchanged record", hashLabelled,
			PbWatchedFields.computeRefHash(testUser, OlioModelNames.MODEL_CHAR_PERSON, charOid));

		// ── RUN 3: blank the stored label (explicit-field PATCH), then a chapter says Selkie → label-only fill ──
		cp2b.set(OlioFieldNames.FIELD_RACE_LABEL, null);
		BaseRecord nullPatch = cp2b.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID,
			FieldNames.FIELD_NAME, OlioFieldNames.FIELD_RACE_LABEL });
		BaseRecord nulled = IOSystem.getActiveContext().getAccessPoint().update(testUser, nullPatch);
		assertNotNull("PATCH {id, objectId, name, raceLabel=null} must succeed", nulled);
		BaseRecord cpBlank = findCharPersonWithRace(testUser, charOid);
		assertNotNull(cpBlank);
		assertEquals("race survives the label patch", List.of(RaceEnumType.O.name()), cpBlank.get(OlioFieldNames.FIELD_RACE));
		assertNull("label read back blank after the null patch; raw value: '" + cpBlank.get(OlioFieldNames.FIELD_RACE_LABEL) + "'",
			NarrativeUtil.getRaceLabel(cpBlank));
		String hashBlank = PbWatchedFields.computeRefHash(testUser, OlioModelNames.MODEL_CHAR_PERSON, charOid);
		assertNotNull(hashBlank);
		assertFalse("blanking raceLabel (race unchanged) must change the watched-field refHash", hashLabelled.equals(hashBlank));

		List<Map<String, Object>> cast3 = new ArrayList<>();
		cast3.add(customCast(charName, "Selkie"));
		BaseRecord meta3 = PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction",
			bookName, scenes, cast3, dataPath, pb2BookOid);
		assertNotNull("run 3 returned meta", meta3);
		assertEquals("run 3 reuses the existing charPerson", charOid, soleCharacterObjectId(meta3));
		BaseRecord cp3 = findCharPersonWithRace(testUser, charOid);
		assertNotNull("run 3 charPerson", cp3);
		assertEquals("run 3: race still O", List.of(RaceEnumType.O.name()), cp3.get(OlioFieldNames.FIELD_RACE));
		assertEquals("run 3: a blank label on a stored O is filled (label-only fill)", "Selkie", cp3.get(OlioFieldNames.FIELD_RACE_LABEL));
		assertEquals("Selkie", NarrativeUtil.getRaceDescription(cp3.get(OlioFieldNames.FIELD_RACE), NarrativeUtil.getRaceLabel(cp3)));

		// ── RUN 4: label is now set → a further Custom label does not overwrite it ──
		List<Map<String, Object>> cast4 = new ArrayList<>();
		cast4.add(customCast(charName, "Kelpie"));
		PictureBookUtil.createFromScenes(testUser, workObjectId, null, "fiction", bookName, scenes, cast4, dataPath, pb2BookOid);
		BaseRecord cp4 = findCharPersonWithRace(testUser, charOid);
		assertEquals("run 4: filled label is never overwritten", "Selkie", cp4.get(OlioFieldNames.FIELD_RACE_LABEL));
	}
}
