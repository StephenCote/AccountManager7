package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
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
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;
import org.junit.Test;

/// Orphan cleanup as a non-admin user. Seeds one specimen of every leftover the old delete and a failed
/// extraction produce - a row-only deleted book (world + workflow + scene + roles survive), a
/// ~/Data/PictureBooks group with no meta, a meta note linked to a gone book, a source range no book
/// references and an extraction checkpoint for a gone document - next to a live book with the same graph
/// shape. scan(own) must list exactly the seeded leftovers and nothing of the live book; purge removes
/// them; a rescan no longer lists them; an org-wide request by a non-admin is 403. Assertions are
/// relative to the seeded identities, since the organization may carry leftovers from other tests.
/// Real DB, no LLM.
public class TestPbOrphanScan extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static BaseRecord rawRowBySlug(IOContext ioContext, String slug, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SLUG});
		q.setCache(false);
		BaseRecord rec = ioContext.getSearch().findRecord(q);
		if (rec != null && rec.getSchema() == null) {
			rec.setSchema(OlioModelNames.MODEL_PB_BOOK);
		}
		return rec;
	}

	private static BaseRecord rawRowByOid(IOContext ioContext, String model, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private static BaseRecord createNote(BaseRecord user, String groupPath, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set(FieldNames.FIELD_TEXT, text);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("note " + name + " created", created);
		return created;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Map<String, Object> report, String code) {
		for (Map<String, Object> cat : (List<Map<String, Object>>) report.get("categories")) {
			if (code.equals(cat.get("code"))) {
				return (List<Map<String, Object>>) cat.get("items");
			}
		}
		fail("category " + code + " missing from report");
		return null;
	}

	private static Set<String> objectIds(Map<String, Object> report, String code) {
		Set<String> out = new HashSet<>();
		for (Map<String, Object> it : items(report, code)) {
			out.add((String) it.get("objectId"));
		}
		return out;
	}

	private static Set<String> names(Map<String, Object> report, String code) {
		Set<String> out = new HashSet<>();
		for (Map<String, Object> it : items(report, code)) {
			out.add((String) it.get("name"));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> allObjectIds(Map<String, Object> report) {
		Set<String> out = new HashSet<>();
		for (Map<String, Object> cat : (List<Map<String, Object>>) report.get("categories")) {
			for (Map<String, Object> it : (List<Map<String, Object>>) cat.get("items")) {
				out.add((String) it.get("objectId"));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> result(Map<String, Object> purge, String objectId) {
		for (Map<String, Object> r : (List<Map<String, Object>>) purge.get("results")) {
			if (objectId.equals(r.get("objectId"))) {
				return r;
			}
		}
		return null;
	}

	private static void assertDeleted(Map<String, Object> purge, String what, String objectId) {
		Map<String, Object> r = result(purge, objectId);
		assertNotNull(what + " was acted on", r);
		assertEquals(what + " outcome: " + r.get("reason"), PbOrphanUtil.OUTCOME_DELETED, r.get("outcome"));
	}

	@Test
	public void TestScanListsSeededLeftoversAndPurgeRemovesThem() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord user = getCreateUser("pbOrphanUser");
		assertNotNull("user", user);
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		String tag = shortId();

		/// Live book A with a workflow, node and scene: nothing of it may ever be listed.
		String slugA = "orl" + tag;
		BaseRecord bookA = PbBookUtil.createBook(user, dataPath, slugA, "Live " + slugA);
		assertNotNull("book A", bookA);
		String bookAOid = bookA.get(FieldNames.FIELD_OBJECT_ID);
		String wfPathA = PbBookUtil.workflowGroupPath(slugA);
		BaseRecord wfA = PbGraphUtil.getCreateWorkflow(user, bookA, wfPathA);
		BaseRecord nodeA = PbGraphUtil.addNode(user, wfA, "scene-0-" + slugA, PbNodeTypeEnumType.SCENE_PROMPT, wfPathA, 0);
		BaseRecord sceneA = PbBookUtil.createScene(user, bookA, 0, "Scene 0", PbBookUtil.bookGroupPath(slugA));
		assertNotNull("workflow A", wfA);
		assertNotNull("node A", nodeA);
		assertNotNull("scene A", sceneA);
		Set<String> liveOids = new HashSet<>();
		liveOids.add(bookAOid);
		liveOids.add(wfA.get(FieldNames.FIELD_OBJECT_ID));
		liveOids.add(nodeA.get(FieldNames.FIELD_OBJECT_ID));
		liveOids.add(sceneA.get(FieldNames.FIELD_OBJECT_ID));

		/// Book B with the same graph, then the old reset(): row only. World, groups, graph and roles stay.
		olioUser = (olioUser != null) ? olioUser : ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		String slugB = "orb" + tag;
		BaseRecord bookB = PbBookUtil.createBook(user, dataPath, slugB, "Gone " + slugB);
		assertNotNull("book B", bookB);
		String wfPathB = PbBookUtil.workflowGroupPath(slugB);
		BaseRecord wfB = PbGraphUtil.getCreateWorkflow(user, bookB, wfPathB);
		BaseRecord nodeB = PbGraphUtil.addNode(user, wfB, "scene-0-" + slugB, PbNodeTypeEnumType.SCENE_PROMPT, wfPathB, 0);
		BaseRecord sceneB = PbBookUtil.createScene(user, bookB, 0, "Scene 0", PbBookUtil.bookGroupPath(slugB));
		assertNotNull("workflow B", wfB);
		assertNotNull("node B", nodeB);
		assertNotNull("scene B", sceneB);
		String wfBOid = wfB.get(FieldNames.FIELD_OBJECT_ID);
		String nodeBOid = nodeB.get(FieldNames.FIELD_OBJECT_ID);
		String sceneBOid = sceneB.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord rawB = rawRowBySlug(ioContext, slugB, orgId);
		assertNotNull("raw row B", rawB);
		DeleteResult rowDel = PictureBookUtil.deleteRecordExplained(olioUser, rawB);
		assertTrue("row-only delete of B: " + rowDel.reason, rowDel.deleted);
		assertNull("row B gone", rawRowBySlug(ioContext, slugB, orgId));
		BaseRecord worldB = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slugB);
		assertNotNull("world B survives the row-only delete (precondition)", worldB);
		String worldBOid = worldB.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord roleB = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.BOOK_ROLE_BASE + "/" + slugB, RoleEnumType.USER.toString(), orgId);
		assertNotNull("role container B survives the row-only delete (precondition)", roleB);
		String roleBOid = roleB.get(FieldNames.FIELD_OBJECT_ID);

		/// A failed extraction's UX group: ~/Data/PictureBooks/<name> with no .pictureBookMeta.
		String uxName = "orphan-ux-" + tag;
		BaseRecord uxGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
			"~/Data/" + PictureBookUtil.PICTURE_BOOKS_DIR + "/" + uxName, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("ux group", uxGroup);
		String uxGroupOid = uxGroup.get(FieldNames.FIELD_OBJECT_ID);

		/// A meta note linking to a PB2 book that no longer exists, outside any PictureBooks group.
		String scratchPath = "~/Data/PbOrphanScratch-" + tag;
		String goneBookOid = UUID.randomUUID().toString();
		BaseRecord metaNote = createNote(user, scratchPath, PictureBookUtil.META_NOTE_NAME,
			"{\"schema\":\"olio.pictureBookMeta\",\"pb2BookObjectId\":\"" + goneBookOid + "\",\"workName\":\"gone\"}");
		String metaNoteOid = metaNote.get(FieldNames.FIELD_OBJECT_ID);

		/// An extraction checkpoint for a document that no longer exists.
		String goneWorkOid = UUID.randomUUID().toString();
		BaseRecord checkpoint = createNote(user, scratchPath, PictureBookUtil.progressNoteName(goneWorkOid, null, null),
			"{\"workObjectId\":\"" + goneWorkOid + "\",\"chunksProcessed\":1,\"totalChunks\":3,\"scenes\":[]}");
		String checkpointOid = checkpoint.get(FieldNames.FIELD_OBJECT_ID);

		/// A source range no book references.
		BaseRecord range = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SOURCE_RANGE);
		ioContext.getRecordUtil().applyOwnership(user, range, orgId);
		range.set(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(10));
		range.set(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(20));
		range.set(OlioFieldNames.FIELD_PB_TITLE, "orphan range " + tag);
		assertTrue("range created", ioContext.getRecordUtil().createRecord(range));
		String rangeOid = range.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("range objectId", rangeOid);

		/// ── scan(own) ──
		Map<String, Object> report = PbOrphanUtil.scan(user, false);
		assertEquals("own", report.get("scope"));
		Set<String> listed = allObjectIds(report);
		for (String oid : liveOids) {
			assertFalse("live book A is never listed: " + oid, listed.contains(oid));
		}
		assertTrue("ORPHAN_WORLD lists world B: " + names(report, PbOrphanUtil.ORPHAN_WORLD),
			objectIds(report, PbOrphanUtil.ORPHAN_WORLD).contains(worldBOid));
		assertFalse("ORPHAN_WORLD does not list world A", names(report, PbOrphanUtil.ORPHAN_WORLD).contains(slugA));
		Set<String> pb2 = objectIds(report, PbOrphanUtil.ORPHAN_PB2_ROW);
		assertTrue("ORPHAN_PB2_ROW lists workflow B (dead book FK)", pb2.contains(wfBOid));
		assertTrue("ORPHAN_PB2_ROW lists scene B (dead book FK)", pb2.contains(sceneBOid));
		assertFalse("ORPHAN_PB2_ROW does not list node B (its workflow still exists)", pb2.contains(nodeBOid));
		assertTrue("ORPHAN_BOOK_ROLE lists the role container for B: " + names(report, PbOrphanUtil.ORPHAN_BOOK_ROLE),
			objectIds(report, PbOrphanUtil.ORPHAN_BOOK_ROLE).contains(roleBOid));
		assertFalse("ORPHAN_BOOK_ROLE does not list A", names(report, PbOrphanUtil.ORPHAN_BOOK_ROLE).contains(slugA));
		assertFalse("ORPHAN_CHAPTER_CONTAINER does not list B while its world exists: " + names(report, PbOrphanUtil.ORPHAN_CHAPTER_CONTAINER),
			names(report, PbOrphanUtil.ORPHAN_CHAPTER_CONTAINER).contains(slugB));
		assertFalse("ORPHAN_BOOK_ROW does not list A", objectIds(report, PbOrphanUtil.ORPHAN_BOOK_ROW).contains(bookAOid));
		assertTrue("ORPHAN_UX_GROUP lists the meta-less group", objectIds(report, PbOrphanUtil.ORPHAN_UX_GROUP).contains(uxGroupOid));
		assertTrue("ORPHAN_META_NOTE lists the dead-link meta", objectIds(report, PbOrphanUtil.ORPHAN_META_NOTE).contains(metaNoteOid));
		assertTrue("ORPHAN_CHECKPOINT lists the dead-document checkpoint", objectIds(report, PbOrphanUtil.ORPHAN_CHECKPOINT).contains(checkpointOid));
		assertTrue("ORPHAN_SOURCE_RANGE lists the unreferenced range", objectIds(report, PbOrphanUtil.ORPHAN_SOURCE_RANGE).contains(rangeOid));
		assertTrue("total counts the seeded items", ((Number) report.get("total")).intValue() >= 7);

		/// scan is read-only.
		assertNotNull("scan deleted nothing: workflow B", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfBOid, orgId));
		assertNotNull("scan deleted nothing: ux group", rawRowByOid(ioContext, ModelNames.MODEL_GROUP, uxGroupOid, orgId));
		assertNotNull("scan deleted nothing: range", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, rangeOid, orgId));

		/// ── purge(own, all) ──
		Map<String, Object> purge = PbOrphanUtil.purge(user, false, null);
		assertEquals("own", purge.get("scope"));
		assertDeleted(purge, "world B", worldBOid);
		assertDeleted(purge, "workflow B", wfBOid);
		assertDeleted(purge, "scene B", sceneBOid);
		assertDeleted(purge, "role container B", roleBOid);
		assertDeleted(purge, "ux group", uxGroupOid);
		assertDeleted(purge, "meta note", metaNoteOid);
		assertDeleted(purge, "checkpoint", checkpointOid);
		assertDeleted(purge, "source range", rangeOid);
		assertEquals(Boolean.TRUE, purge.get("cleanupOrphansRan"));

		assertNull("workflow B purged", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfBOid, orgId));
		assertNull("node B purged with its workflow", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_NODE, nodeBOid, orgId));
		assertNull("scene B purged", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE, sceneBOid, orgId));
		assertNull("world B purged", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slugB));
		assertNull("container B purged", ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP,
			PbBookUtil.bookContainerPath(slugB), GroupEnumType.DATA.toString(), orgId));
		assertNull("role container B purged", ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.BOOK_ROLE_BASE + "/" + slugB, RoleEnumType.USER.toString(), orgId));
		assertNull("ux group purged", rawRowByOid(ioContext, ModelNames.MODEL_GROUP, uxGroupOid, orgId));
		assertNull("meta note purged", rawRowByOid(ioContext, ModelNames.MODEL_NOTE, metaNoteOid, orgId));
		assertNull("checkpoint purged", rawRowByOid(ioContext, ModelNames.MODEL_NOTE, checkpointOid, orgId));
		assertNull("range purged", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SOURCE_RANGE, rangeOid, orgId));

		/// Live book A is intact.
		assertNotNull("book A intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_BOOK, bookAOid, orgId));
		assertNotNull("workflow A intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfA.get(FieldNames.FIELD_OBJECT_ID), orgId));
		assertNotNull("scene A intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE, sceneA.get(FieldNames.FIELD_OBJECT_ID), orgId));
		assertNotNull("world A intact", WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slugA));

		/// ── rescan: none of the seeded identities remain ──
		Map<String, Object> again = PbOrphanUtil.scan(user, false);
		Set<String> remaining = allObjectIds(again);
		List<String> seeded = new ArrayList<>(List.of(worldBOid, wfBOid, sceneBOid, roleBOid, uxGroupOid, metaNoteOid, checkpointOid, rangeOid));
		for (String oid : seeded) {
			assertFalse("rescan no longer lists " + oid, remaining.contains(oid));
		}
		assertFalse("rescan lists no world named B", names(again, PbOrphanUtil.ORPHAN_WORLD).contains(slugB));
		assertFalse("rescan lists no role named B", names(again, PbOrphanUtil.ORPHAN_BOOK_ROLE).contains(slugB));
		assertFalse("rescan lists no container named B", names(again, PbOrphanUtil.ORPHAN_CHAPTER_CONTAINER).contains(slugB));

		/// ── org-wide by a non-admin is refused, for both scan and purge ──
		try {
			PbOrphanUtil.scan(user, true);
			fail("non-admin org-wide scan should be 403");
		}
		catch (PictureBookException e) {
			assertEquals(403, e.getStatus());
		}
		try {
			PbOrphanUtil.purge(user, true, null);
			fail("non-admin org-wide purge should be 403");
		}
		catch (PictureBookException e) {
			assertEquals(403, e.getStatus());
		}

		DeleteResult res = PbDeleteUtil.deleteBookComplete(user, bookAOid);
		assertTrue("cleanup A: " + res.reason, res.deleted);
		BaseRecord scratch = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_GROUP, scratchPath, GroupEnumType.DATA.toString(), orgId);
		if (scratch != null) {
			PictureBookUtil.deleteGroupRecursive(user, scratch);
		}
	}
}
