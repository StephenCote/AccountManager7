package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/// The stale-graph defect and its two cures. The old PictureBook reset() deleted the olio.pb.book row and
/// left the world, its container groups and the olio.pb.workflow "Workflow Book <slug>" behind; on the next
/// extract the recreated book adopted the world, the stale workflow collided on the unique
/// (name, groupId, organizationId) index, getCreateWorkflow threw 500, generateSceneImage swallowed it, and
/// every rendered image was saved without an olio.pb.scene row ("N scenes extracted - none rendered yet").
///
///  1. Create-time cure: reproduce the row-only delete, recreate the slug, and the stale workflow / node /
///     scene are purged by createBook before anything binds to the new row; getCreateWorkflow then returns a
///     workflow bound to the new row.
///  2. Health-check cure: with a live book whose Workflow group holds a workflow bound to a dead row,
///     getCreateWorkflow fails exactly as in production, checkBook reports STALE_GRAPH (+ WORKFLOW_MISSING
///     noting the block), healBook purges it, the live scene row survives, and a re-check is clean.
/// Real DB, no LLM, non-admin users throughout.
public class TestPbHealthStaleGraph extends BaseTest {

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

	private static BaseRecord rawRowById(IOContext ioContext, String model, long id, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = rec.hasField(field) ? rec.get(field) : null;
		return (o instanceof BaseRecord) ? idOf((BaseRecord) o) : 0L;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> findings(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.get("findings");
	}

	private static Set<String> findingCodes(Map<String, Object> report) {
		Set<String> codes = new HashSet<>();
		for (Map<String, Object> f : findings(report)) {
			codes.add((String) f.get("code"));
		}
		return codes;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> healedCodes(Map<String, Object> report) {
		Set<String> codes = new HashSet<>();
		for (Map<String, Object> h : (List<Map<String, Object>>) report.get("healed")) {
			codes.add((String) h.get("code"));
		}
		return codes;
	}

	private static Map<String, Object> finding(Map<String, Object> report, String code) {
		for (Map<String, Object> f : findings(report)) {
			if (code.equals(f.get("code"))) {
				return f;
			}
		}
		return null;
	}

	@Test
	public void TestCreateBookPurgesStaleGraphLeftByOldDelete() {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord owner = getCreateUser("pbHealthOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		String slug = "hsg" + shortId();

		BaseRecord bookA = PbBookUtil.createBook(owner, dataPath, slug, "Health " + slug);
		assertNotNull("book A", bookA);
		long bookAId = idOf(bookA);
		String wfPath = PbBookUtil.workflowGroupPath(slug);
		BaseRecord wfA = PbGraphUtil.getCreateWorkflow(owner, bookA, wfPath);
		assertNotNull("workflow A", wfA);
		BaseRecord nodeA = PbGraphUtil.addNode(owner, wfA, "scene-0-" + slug, PbNodeTypeEnumType.SCENE_PROMPT, wfPath, 0);
		assertNotNull("node A", nodeA);
		BaseRecord sceneA = PbBookUtil.createScene(owner, bookA, 0, "Scene 0", PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene A", sceneA);
		long wfAId = idOf(wfA);
		long nodeAId = idOf(nodeA);
		long sceneAId = idOf(sceneA);

		/// Reproduce the old reset(): the book row alone goes. World, groups and the whole graph stay behind.
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord rawA = rawRowBySlug(ioContext, slug, orgId);
		assertNotNull("raw row A", rawA);
		DeleteResult rowDel = PictureBookUtil.deleteRecordExplained(olioUser, rawA);
		assertTrue("row-only delete: " + rowDel.reason, rowDel.deleted);
		assertNull("row A gone", rawRowBySlug(ioContext, slug, orgId));
		assertNotNull("stale workflow must survive the row-only delete (precondition)", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfAId, orgId));
		assertNotNull("stale node must survive the row-only delete (precondition)", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, nodeAId, orgId));
		assertNotNull("stale scene must survive the row-only delete (precondition)", rawRowById(ioContext, OlioModelNames.MODEL_PB_SCENE, sceneAId, orgId));

		/// Recreate the slug the way a second extract does. createBook adopts the world and must purge first.
		BaseRecord bookB = PbBookUtil.createBook(owner, dataPath, slug, "Health " + slug + " again");
		assertNotNull("book B", bookB);
		long bookBId = idOf(bookB);
		assertTrue("B is a new row", bookBId != bookAId);
		assertNull("stale workflow purged on recreate", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfAId, orgId));
		assertNull("stale node purged on recreate", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, nodeAId, orgId));
		assertNull("stale scene purged on recreate", rawRowById(ioContext, OlioModelNames.MODEL_PB_SCENE, sceneAId, orgId));

		/// The collision is gone: the workflow is created and bound to the NEW row.
		BaseRecord wfB = PbGraphUtil.getCreateWorkflow(owner, bookB, wfPath);
		assertNotNull("workflow B", wfB);
		assertTrue("workflow B is a new row", idOf(wfB) != wfAId);
		assertEquals("workflow B bound to book B", bookBId, fkId(wfB, OlioFieldNames.FIELD_PB_BOOK));

		Map<String, Object> report = PbHealthUtil.checkBook(owner, bookB.get(FieldNames.FIELD_OBJECT_ID));
		Set<String> codes = findingCodes(report);
		assertFalse("no STALE_GRAPH after recreate: " + codes, codes.contains(PbHealthUtil.STALE_GRAPH));
		assertFalse("no WORKFLOW_MISSING after recreate: " + codes, codes.contains(PbHealthUtil.WORKFLOW_MISSING));

		DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookB.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("cleanup: " + res.reason, res.deleted);
	}

	@Test
	public void TestCheckReportsAndHealClearsStaleGraph() {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord owner = getCreateUser("pbHealthOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		String slug = "hsg" + shortId();

		BaseRecord bookB = PbBookUtil.createBook(owner, dataPath, slug, "Health " + slug);
		assertNotNull("book B", bookB);
		String bOid = bookB.get(FieldNames.FIELD_OBJECT_ID);
		long bookBId = idOf(bookB);
		String wfPath = PbBookUtil.workflowGroupPath(slug);
		BaseRecord wfB = PbGraphUtil.getCreateWorkflow(owner, bookB, wfPath);
		assertNotNull("workflow B", wfB);
		BaseRecord nodeB = PbGraphUtil.addNode(owner, wfB, "scene-0-" + slug, PbNodeTypeEnumType.SCENE_PROMPT, wfPath, 0);
		assertNotNull("node B", nodeB);
		BaseRecord liveScene = PbBookUtil.createScene(owner, bookB, 0, "Scene 0", PbBookUtil.bookGroupPath(slug));
		assertNotNull("live scene", liveScene);
		long wfBId = idOf(wfB);
		long nodeBId = idOf(nodeB);
		long liveSceneId = idOf(liveScene);

		Map<String, Object> clean = PbHealthUtil.checkBook(owner, bOid);
		assertFalse("fresh book has no STALE_GRAPH: " + findingCodes(clean), findingCodes(clean).contains(PbHealthUtil.STALE_GRAPH));

		/// A dead book row to point at: create a throwaway book, keep its identity, delete it completely.
		String slugD = "hsd" + shortId();
		BaseRecord bookD = PbBookUtil.createBook(owner, dataPath, slugD, "Dead " + slugD);
		assertNotNull("book D", bookD);
		BaseRecord deadStub = rawRowBySlug(ioContext, slugD, orgId);
		assertNotNull("raw row D", deadStub);
		DeleteResult dDel = PbDeleteUtil.deleteBookComplete(owner, bookD.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("delete D: " + dDel.reason, dDel.deleted);
		assertNull("row D gone", rawRowBySlug(ioContext, slugD, orgId));

		/// Re-point B's workflow at the dead row - the exact shape the old reset() left in the Workflow group.
		BaseRecord patch = PbGraphUtil.patchOf(wfB, OlioModelNames.MODEL_PB_WORKFLOW, OlioFieldNames.FIELD_PB_BOOK);
		try {
			patch.set(OlioFieldNames.FIELD_PB_BOOK, deadStub);
		}
		catch (Exception e) {
			fail("assemble dead-FK patch: " + e.getMessage());
		}
		boolean repointed = ioContext.getAccessPoint().update(owner, patch) != null;
		if (!repointed) {
			repointed = ioContext.getRecordUtil().updateRecord(patch);
		}
		assertTrue("workflow re-pointed at the dead row", repointed);
		assertNull("no workflow is bound to B any more", PbGraphUtil.findWorkflow(owner, bookB));

		/// Precondition: this is the production failure. The stale row owns the unique name, so creation fails.
		try {
			PbGraphUtil.getCreateWorkflow(owner, bookB, wfPath);
			fail("expected the stale workflow to block creation on the unique name index");
		}
		catch (PictureBookException e) {
			assertEquals("getCreateWorkflow fails with 500 while the stale row exists", 500, e.getStatus());
		}

		/// A stranger cannot even see the book, let alone its health.
		BaseRecord stranger = getCreateUser("pbHealthStranger");
		assertNotNull("stranger", stranger);
		try {
			PbHealthUtil.checkBook(stranger, bOid);
			fail("stranger should get 404");
		}
		catch (PictureBookException e) {
			assertEquals(404, e.getStatus());
		}
		try {
			PbHealthUtil.healBook(stranger, dataPath, bOid, null, false);
			fail("stranger should get 404 on heal");
		}
		catch (PictureBookException e) {
			assertEquals(404, e.getStatus());
		}
		assertNotNull("stranger's heal attempt changed nothing", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfBId, orgId));

		/// The read-only check sees it and says so.
		Map<String, Object> before = PbHealthUtil.checkBook(owner, bOid);
		Set<String> codes = findingCodes(before);
		assertTrue("STALE_GRAPH reported: " + codes, codes.contains(PbHealthUtil.STALE_GRAPH));
		Map<String, Object> stale = finding(before, PbHealthUtil.STALE_GRAPH);
		assertEquals("ERROR", stale.get("severity"));
		assertEquals(Boolean.TRUE, stale.get("healable"));
		assertTrue("WORKFLOW_MISSING reported alongside: " + codes, codes.contains(PbHealthUtil.WORKFLOW_MISSING));
		Map<String, Object> wfMissing = finding(before, PbHealthUtil.WORKFLOW_MISSING);
		assertTrue("WORKFLOW_MISSING names the block: " + wfMissing.get("message"),
			((String) wfMissing.get("message")).contains("blocked by the stale graph"));
		assertEquals(Boolean.FALSE, wfMissing.get("healable"));
		@SuppressWarnings("unchecked")
		Map<String, Object> summary = (Map<String, Object>) before.get("summary");
		assertTrue("summary.errors >= 1", ((Number) summary.get("errors")).intValue() >= 1);
		assertTrue("summary.healable >= 1", ((Number) summary.get("healable")).intValue() >= 1);
		assertTrue("checkBook is read-only: stale workflow still there", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfBId, orgId) != null);
		assertTrue("checkBook is read-only: stale node still there", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, nodeBId, orgId) != null);

		/// Repair.
		Map<String, Object> healReport = PbHealthUtil.healBook(owner, dataPath, bOid, null, false);
		Set<String> healed = healedCodes(healReport);
		assertTrue("STALE_GRAPH healed: " + healed, healed.contains(PbHealthUtil.STALE_GRAPH));
		Set<String> after = findingCodes(healReport);
		assertFalse("STALE_GRAPH cleared in the post-heal audit: " + after, after.contains(PbHealthUtil.STALE_GRAPH));
		assertNull("stale workflow purged", rawRowById(ioContext, OlioModelNames.MODEL_PB_WORKFLOW, wfBId, orgId));
		assertNull("stale node purged", rawRowById(ioContext, OlioModelNames.MODEL_PB_NODE, nodeBId, orgId));
		assertNotNull("live scene row untouched by the purge", rawRowById(ioContext, OlioModelNames.MODEL_PB_SCENE, liveSceneId, orgId));

		/// The collision is gone: creation succeeds and binds to B.
		BaseRecord wfNew = PbGraphUtil.getCreateWorkflow(owner, bookB, wfPath);
		assertNotNull("workflow after heal", wfNew);
		assertTrue("fresh workflow row", idOf(wfNew) != wfBId);
		assertEquals("bound to B", bookBId, fkId(wfNew, OlioFieldNames.FIELD_PB_BOOK));

		Map<String, Object> again = PbHealthUtil.checkBook(owner, bOid);
		Set<String> againCodes = findingCodes(again);
		assertFalse("re-check clean of STALE_GRAPH: " + againCodes, againCodes.contains(PbHealthUtil.STALE_GRAPH));
		assertFalse("re-check clean of WORKFLOW_MISSING: " + againCodes, againCodes.contains(PbHealthUtil.WORKFLOW_MISSING));

		/// A heal on a healthy book is a no-op that reports nothing healed for this code.
		Map<String, Object> idempotent = PbHealthUtil.healBook(owner, dataPath, bOid, null, false);
		assertFalse("nothing to heal second time: " + healedCodes(idempotent), healedCodes(idempotent).contains(PbHealthUtil.STALE_GRAPH));

		DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bOid);
		assertTrue("cleanup: " + res.reason, res.deleted);
	}
}
