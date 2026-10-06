package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/// PbDeleteUtil.deleteBookComplete must remove EVERY entity a PictureBook created, so a same-slug recreate
/// on any environment starts clean. The old reset() left the world, its Book/Workflow/Artifacts groups and
/// the olio.pb.workflow row behind; the stale workflow then collided on the unique (name, groupId,
/// organizationId) index on re-extract and every rendered image went unrecorded ("N scenes extracted -
/// none rendered yet"). Real DB, no LLM, non-admin users throughout.
///
///  1. Full footprint: workflow + nodes + binding + artifact + scene + shadow cast group + per-book roles +
///     the caller's ~/Data/PictureBooks/<slug> tree with a .pictureBookMeta note linked by pb2BookObjectId.
///     A stranger is refused and nothing changes. The owner's delete leaves every raw lookup null, the
///     result carries a per-step audit, and a second call is a clean 404.
///  2. Series chapter: the chapter's container, its olio.pb.sourceRange row and (once no other chapter
///     references the manuscript) its extraction checkpoint go; the series' shared world, the series row and
///     the sibling chapter survive.
public class TestPbDeleteComplete extends BaseTest {

	private static final String PB1_BOOKS_ROOT = "~/Data/PictureBooks/";

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static File locateFixture(String fileName) {
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

	private static BaseRecord rawRowBySlug(IOContext ioContext, String slug, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SLUG});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private static BaseRecord rawRowById(IOContext ioContext, String model, long id, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private static BaseRecord findGroup(BaseRecord actor, String path, long orgId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(actor, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId);
	}

	private static BaseRecord findRole(BaseRecord actor, String path, long orgId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(actor, ModelNames.MODEL_ROLE, path,
			RoleEnumType.USER.toString(), orgId);
	}

	private static BaseRecord createNote(BaseRecord user, String groupPath, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set(FieldNames.FIELD_TEXT, text);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("create note " + name, created);
		return created;
	}

	private static BaseRecord findNote(BaseRecord user, String groupPath, String name, long orgId) {
		BaseRecord grp = findGroup(user, groupPath, orgId);
		if (grp == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME});
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private static Set<String> stepNames(DeleteResult res) {
		Set<String> out = new HashSet<>();
		for (Map<String, Object> s : res.steps) {
			out.add((String) s.get("step"));
		}
		return out;
	}

	private static Map<String, Object> step(DeleteResult res, String name) {
		for (Map<String, Object> s : res.steps) {
			if (name.equals(s.get("step"))) {
				return s;
			}
		}
		return null;
	}

	/// The world + container groups + roles + graph rows createBook and the graph utils wrote for a slug.
	private static final class Footprint {
		BaseRecord bookRow;
		long workflowId;
		long nodeId;
		long node2Id;
		long bindingId;
		long artifactId;
		long sceneId;
		long castGroupId;
	}

	private static void assertFootprintPresent(IOContext ioContext, BaseRecord olioUser, BaseRecord owner, String slug,
			Footprint fp, long orgId, String when) {
		assertNotNull(when + ": pb.book row", rawRowBySlug(ioContext, slug, orgId));
		assertNotNull(when + ": workflow", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, fp.workflowId, orgId));
		assertNotNull(when + ": node", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, fp.nodeId, orgId));
		assertNotNull(when + ": node2", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, fp.node2Id, orgId));
		assertNotNull(when + ": binding", rawRowById(ioContext, OlioModelNames.MODEL_PB_BINDING, fp.bindingId, orgId));
		assertNotNull(when + ": artifact", rawRowById(ioContext, OlioModelNames.MODEL_PB_ARTIFACT, fp.artifactId, orgId));
		assertNotNull(when + ": scene", rawRowById(ioContext, OlioModelNames.MODEL_PB_SCENE, fp.sceneId, orgId));
		assertNotNull(when + ": cast group", rawRowById(ioContext, OlioModelNames.MODEL_PB_CAST_GROUP, fp.castGroupId, orgId));
		assertNotNull(when + ": world", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));
		assertNotNull(when + ": container group", findGroup(olioUser, PbBookUtil.bookContainerPath(slug), orgId));
		assertNotNull(when + ": Book group", findGroup(olioUser, PbBookUtil.bookGroupPath(slug), orgId));
		assertNotNull(when + ": Workflow group", findGroup(olioUser, PbBookUtil.workflowGroupPath(slug), orgId));
		assertNotNull(when + ": Artifacts group", findGroup(olioUser, PbBookUtil.artifactGroupPath(slug), orgId));
		assertNotNull(when + ": Writer role", findRole(olioUser, PbOlioContextUtil.writerRolePath(slug), orgId));
		assertNotNull(when + ": Admin role", findRole(olioUser, PbOlioContextUtil.adminRolePath(slug), orgId));
		assertNotNull(when + ": role parent", findRole(olioUser, PbOlioContextUtil.BOOK_ROLE_BASE + "/" + slug, orgId));
		assertNotNull(when + ": PB1 tree", findGroup(owner, PB1_BOOKS_ROOT + slug, orgId));
		assertNotNull(when + ": PB1 Scenes group", findGroup(owner, PB1_BOOKS_ROOT + slug + "/Scenes", orgId));
		assertNotNull(when + ": meta note", findNote(owner, PB1_BOOKS_ROOT + slug, PictureBookUtil.META_NOTE_NAME, orgId));
	}

	private static void assertFootprintGone(IOContext ioContext, BaseRecord olioUser, BaseRecord owner, String slug,
			Footprint fp, long orgId) {
		assertNull("pb.book row must be gone", rawRowBySlug(ioContext, slug, orgId));
		assertNull("workflow must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, fp.workflowId, orgId));
		assertNull("node must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, fp.nodeId, orgId));
		assertNull("node2 must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, fp.node2Id, orgId));
		assertNull("binding must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_BINDING, fp.bindingId, orgId));
		assertNull("artifact must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_ARTIFACT, fp.artifactId, orgId));
		assertNull("scene must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_SCENE, fp.sceneId, orgId));
		assertNull("cast group must be gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_CAST_GROUP, fp.castGroupId, orgId));
		assertNull("world must be gone", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug));
		assertNull("container group must be gone", findGroup(olioUser, PbBookUtil.bookContainerPath(slug), orgId));
		assertNull("Book group must be gone", findGroup(olioUser, PbBookUtil.bookGroupPath(slug), orgId));
		assertNull("Workflow group must be gone", findGroup(olioUser, PbBookUtil.workflowGroupPath(slug), orgId));
		assertNull("Artifacts group must be gone", findGroup(olioUser, PbBookUtil.artifactGroupPath(slug), orgId));
		assertNull("Writer role must be gone", findRole(olioUser, PbOlioContextUtil.writerRolePath(slug), orgId));
		assertNull("Admin role must be gone", findRole(olioUser, PbOlioContextUtil.adminRolePath(slug), orgId));
		assertNull("role parent must be gone", findRole(olioUser, PbOlioContextUtil.BOOK_ROLE_BASE + "/" + slug, orgId));
		assertNull("PB1 tree must be gone", findGroup(owner, PB1_BOOKS_ROOT + slug, orgId));
		assertNull("PB1 Scenes group must be gone", findGroup(owner, PB1_BOOKS_ROOT + slug + "/Scenes", orgId));
		assertTrue("stale-workflow probe: no workflow may reference the dead book",
			PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_WORKFLOW, OlioFieldNames.FIELD_PB_BOOK, fp.bookRow, orgId).isEmpty());
		assertTrue("no scene may reference the dead book",
			PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, fp.bookRow, orgId).isEmpty());
		assertTrue("no cast group may reference the dead book",
			PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_CAST_GROUP, OlioFieldNames.FIELD_PB_BOOK, fp.bookRow, orgId).isEmpty());
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	@Test
	public void testFullFootprintStrangerDeniedOwnerDeletesSecondCall404() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbDelCompOwner");
		BaseRecord stranger = getCreateUser("pbDelCompStranger");
		assertNotNull("owner", owner);
		assertNotNull("stranger", stranger);
		assertFalse("owner and stranger must differ", idOf(owner) == idOf(stranger));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String id = shortId();
		String slug = "dcp" + id;

		// --- Fixture: everything createBook + an extract/render pass would leave behind.
		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "DelComplete " + slug);
		assertNotNull("createBook", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);

		Footprint fp = new Footprint();
		fp.bookRow = rawRowBySlug(ioContext, slug, orgId);
		assertNotNull("raw book row", fp.bookRow);

		String wfPath = PbBookUtil.workflowGroupPath(slug);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(owner, book, wfPath);
		assertNotNull("workflow", wf);
		fp.workflowId = idOf(wf);
		BaseRecord sceneNode = PbGraphUtil.addNode(owner, wf, "scene-0-" + id, PbNodeTypeEnumType.SCENE_PROMPT, wfPath, 0);
		assertNotNull("scene node", sceneNode);
		fp.nodeId = idOf(sceneNode);
		BaseRecord portraitNode = PbGraphUtil.addNode(owner, wf, "portrait-" + id, PbNodeTypeEnumType.PORTRAIT, wfPath, 1);
		assertNotNull("portrait node", portraitNode);
		fp.node2Id = idOf(portraitNode);
		BaseRecord binding = PbGraphUtil.addBinding(owner, wf, sceneNode, "portrait", 0, portraitNode, null, wfPath);
		assertNotNull("binding portrait -> scene", binding);
		fp.bindingId = idOf(binding);
		BaseRecord artifact = PbArtifactUtil.persistArtifact(owner, portraitNode, "portrait", PbArtifactTypeEnumType.PROMPT,
			PbBookUtil.artifactGroupPath(slug), null, "test prompt " + id, null, null);
		assertNotNull("artifact", artifact);
		fp.artifactId = idOf(artifact);
		BaseRecord scene = PbBookUtil.createScene(owner, book, 0, "Scene 0", PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene row", scene);
		fp.sceneId = idOf(scene);
		BaseRecord castGroup = PbCastUtil.getCreateShadowCastGroup(owner, book, slug, PbBookUtil.bookGroupPath(slug), orgId);
		assertNotNull("shadow cast group", castGroup);
		fp.castGroupId = idOf(castGroup);

		// The caller's PB1 tree, exactly as the extractor writes it: group, Scenes subgroup, meta note linked
		// to the PB2 row by pb2BookObjectId.
		BaseRecord pb1Group = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, PB1_BOOKS_ROOT + slug,
			GroupEnumType.DATA.toString(), orgId);
		assertNotNull("PB1 group", pb1Group);
		assertNotNull("PB1 Scenes group", ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP,
			PB1_BOOKS_ROOT + slug + "/Scenes", GroupEnumType.DATA.toString(), orgId));
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("schema", "olio.pictureBookMeta");
		meta.put("pb2BookObjectId", bookOid);
		meta.put("scenes", new ArrayList<>());
		createNote(owner, PB1_BOOKS_ROOT + slug, PictureBookUtil.META_NOTE_NAME, JSONUtil.exportObject(meta));

		assertFootprintPresent(ioContext, olioUser, owner, slug, fp, orgId, "before");

		// --- A stranger is refused and nothing changes.
		try {
			DeleteResult denied = PbDeleteUtil.deleteBookComplete(stranger, bookOid);
			assertFalse("stranger must not delete", denied.deleted);
			assertFalse("stranger must be reported as unauthorized", denied.authorized);
		}
		catch (PictureBookException e) {
			assertEquals("stranger refused with 403, got " + e.getStatus() + ": " + e.getMessage(), 403, e.getStatus());
		}
		assertFootprintPresent(ioContext, olioUser, owner, slug, fp, orgId, "after stranger");

		// --- The owner's delete.
		DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
		assertNotNull("result", res);
		assertTrue("deleted: " + res.reason + " steps=" + JSONUtil.exportObject(res.steps), res.deleted);
		assertTrue("authorized", res.authorized);
		Set<String> names = stepNames(res);
		for (String expected : new String[] {"scenesByBook", "castGroupsByBook", "bindingsByNode", "artifactsByNode",
				"nodesByWorkflow", "workflow", "bookRoles", "footprint", "uxTree", "metaNotes", "cleanupOrphans"}) {
			assertTrue("step audit must record '" + expected + "': " + names, names.contains(expected));
		}
		for (Map<String, Object> s : res.steps) {
			assertTrue("every step ok: " + s, Boolean.TRUE.equals(s.get("ok")));
		}
		assertEquals("one scene deleted", 1, ((Number) step(res, "scenesByBook").get("count")).intValue());
		assertEquals("one cast group deleted", 1, ((Number) step(res, "castGroupsByBook").get("count")).intValue());
		assertEquals("two nodes deleted", 2, ((Number) step(res, "nodesByWorkflow").get("count")).intValue());
		assertEquals("one artifact deleted", 1, ((Number) step(res, "artifactsByNode").get("count")).intValue());
		assertEquals("three roles deleted", 3, ((Number) step(res, "bookRoles").get("count")).intValue());

		assertFootprintGone(ioContext, olioUser, owner, slug, fp, orgId);
		assertNull("readBook after delete", PbBookUtil.readBook(owner, bookOid, orgId));

		// --- A second call is a clean 404, not a crash and not a silent success.
		try {
			PbDeleteUtil.deleteBookComplete(owner, bookOid);
			fail("second delete must 404");
		}
		catch (PictureBookException e) {
			assertEquals("second delete", 404, e.getStatus());
		}

		// --- Recreating the slug now starts clean: a fresh world and a fresh workflow bound to the new row.
		BaseRecord again = PbBookUtil.createBook(owner, dataPath, slug, "DelComplete again " + slug);
		assertNotNull("recreate same slug", again);
		assertFalse("recreate must be a new row", bookOid.equals(again.get(FieldNames.FIELD_OBJECT_ID)));
		BaseRecord wf2 = PbGraphUtil.getCreateWorkflow(owner, again, wfPath);
		assertNotNull("workflow on the recreated slug", wf2);
		assertFalse("workflow must be a new row, not the stale one", fp.workflowId == idOf(wf2));
		DeleteResult cleanup = PbDeleteUtil.deleteBookComplete(owner, again.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("cleanup of the recreated book: " + cleanup.reason, cleanup.deleted);
		assertNull("recreated book row gone", rawRowBySlug(ioContext, slug, orgId));
	}

	@Test
	public void testChapterDeleteLeavesSeriesWorldAndRemovesSourceRangeAndCheckpoint() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbDelCompChapOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		String workPath = "~/PbDelCompData";
		BaseRecord sourceData = getCreateFileData(owner, workPath, docx.getAbsolutePath());
		assertNotNull("sourceData", sourceData);
		String sourceDataOid = sourceData.get(FieldNames.FIELD_OBJECT_ID);

		String tag = shortId();
		String seriesSlug = "dcs" + tag;
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "DelComplete Series " + tag);
		assertNotNull("series", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);

		String slug1 = "dcc" + tag;
		String slug2 = "dcd" + tag;
		Map<String, Object> range1 = new LinkedHashMap<>();
		range1.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(0));
		range1.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1200));
		range1.put(OlioFieldNames.FIELD_PB_TITLE, "Chapter A");
		Map<String, Object> range2 = new LinkedHashMap<>();
		range2.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(1200));
		range2.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(2600));
		range2.put(OlioFieldNames.FIELD_PB_TITLE, "Chapter B");
		Map<String, Object> r1 = PbServiceFacade.createChapter(owner, dataPath, seriesOid, null, slug1,
			"Chapter A Title", Integer.valueOf(1), sourceDataOid, range1, null, null);
		assertNotNull("createChapter #1", r1);
		String book1Oid = (String) r1.get("bookObjectId");
		Map<String, Object> r2 = PbServiceFacade.createChapter(owner, dataPath, seriesOid, null, slug2,
			"Chapter B Title", Integer.valueOf(2), sourceDataOid, range2, null, null);
		assertNotNull("createChapter #2", r2);
		String book2Oid = (String) r2.get("bookObjectId");

		BaseRecord row1 = PbDeleteUtil.rawBookRow(book1Oid, orgId);
		BaseRecord row2 = PbDeleteUtil.rawBookRow(book2Oid, orgId);
		assertNotNull("chapter 1 raw row", row1);
		assertNotNull("chapter 2 raw row", row2);
		BaseRecord sr1 = row1.get(OlioFieldNames.FIELD_PB_SOURCE_RANGE);
		BaseRecord sr2 = row2.get(OlioFieldNames.FIELD_PB_SOURCE_RANGE);
		assertNotNull("chapter 1 sourceRange FK", sr1);
		assertNotNull("chapter 2 sourceRange FK", sr2);
		long sr1Id = idOf(sr1);
		long sr2Id = idOf(sr2);
		assertTrue("chapter 1 sourceRange id", sr1Id > 0L);
		assertNotNull("chapter 1 sourceRange row", rawRowById(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, sr1Id, orgId));
		assertNotNull("chapter 2 sourceRange row", rawRowById(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, sr2Id, orgId));

		// An extraction checkpoint for the shared manuscript, where the extractor writes it (the work's group).
		Map<String, Object> cp = new LinkedHashMap<>();
		cp.put("workObjectId", sourceDataOid);
		cp.put("chunksProcessed", 1);
		cp.put("chunkCount", 3);
		String cpName = PictureBookUtil.progressNoteName(sourceDataOid, 0, 1200);
		createNote(owner, workPath, cpName, JSONUtil.exportObject(cp));
		assertNotNull("checkpoint note before", findNote(owner, workPath, cpName, orgId));

		assertNotNull("chapter 1 container", findGroup(olioUser, PbBookUtil.bookContainerPath(slug1), orgId));
		assertNotNull("shared series world", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));

		// --- Delete chapter 1: its container + sourceRange go; the manuscript is still referenced by
		//     chapter 2, so the checkpoint is kept; the shared world, series and chapter 2 survive.
		DeleteResult res1 = PbDeleteUtil.deleteBookComplete(owner, book1Oid);
		assertTrue("chapter 1 deleted: " + res1.reason + " steps=" + JSONUtil.exportObject(res1.steps), res1.deleted);
		assertNull("chapter 1 raw row gone", PbDeleteUtil.rawBookRow(book1Oid, orgId));
		assertNull("chapter 1 container gone", findGroup(olioUser, PbBookUtil.bookContainerPath(slug1), orgId));
		assertNull("chapter 1 sourceRange row gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, sr1Id, orgId));
		Map<String, Object> srStep = step(res1, "sourceRange");
		assertNotNull("sourceRange step recorded", srStep);
		assertTrue("sourceRange step ok: " + srStep, Boolean.TRUE.equals(srStep.get("ok")));
		Map<String, Object> cpStep = step(res1, "checkpoints");
		assertNotNull("checkpoints step recorded", cpStep);
		assertTrue("checkpoint kept while chapter 2 references the manuscript: " + cpStep,
			String.valueOf(cpStep.get("reason")).startsWith("kept"));
		assertNotNull("checkpoint survives chapter 1 delete", findNote(owner, workPath, cpName, orgId));
		assertNotNull("shared series world survives", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));
		assertNotNull("series row survives", PbSeriesUtil.readSeries(owner, seriesOid, orgId));
		assertNotNull("chapter 2 survives", PbBookUtil.readBook(owner, book2Oid, orgId));
		assertNotNull("chapter 2 sourceRange survives", rawRowById(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, sr2Id, orgId));
		assertNotNull("manuscript survives", rawRowById(ioContext, ModelNames.MODEL_DATA, idOf(sourceData), orgId));

		// --- Delete chapter 2: last reference to the manuscript, so the checkpoint goes too.
		DeleteResult res2 = PbDeleteUtil.deleteBookComplete(owner, book2Oid);
		assertTrue("chapter 2 deleted: " + res2.reason + " steps=" + JSONUtil.exportObject(res2.steps), res2.deleted);
		assertNull("chapter 2 raw row gone", PbDeleteUtil.rawBookRow(book2Oid, orgId));
		assertNull("chapter 2 sourceRange row gone", rawRowById(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, sr2Id, orgId));
		assertNull("checkpoint removed with the last chapter", findNote(owner, workPath, cpName, orgId));
		assertNotNull("manuscript itself is never deleted", rawRowById(ioContext, ModelNames.MODEL_DATA, idOf(sourceData), orgId));
		assertNotNull("shared series world still survives a chapter delete",
			WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));

		// --- Cleanup through the series path, which is the only thing allowed to drop the shared world.
		PbSeriesUtil.deleteSeries(owner, seriesOid);
		assertNull("series world gone after deleteSeries", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), seriesSlug));
		assertNull("series row gone after deleteSeries", PbSeriesUtil.readSeries(owner, seriesOid, orgId));
	}
}
