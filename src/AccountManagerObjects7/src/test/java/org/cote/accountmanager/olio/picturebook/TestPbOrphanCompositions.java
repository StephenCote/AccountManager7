package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/// P4-13 (c2): the orphan scan knows the three composition models. Seeds, in one live book as a non-admin
/// user: a SCENE composition (MIGRATED, no LLM) with an actor and an element whose scene is then deleted
/// row-only (the shape the pre-P4-13 ChapBook scene delete/merge left behind); a CHAPBOOK_PAGE composition
/// with an element whose composition is then deleted row-only; a live page composition with an element; and
/// a COVER composition whose scene is null by design. scan(own) must list the composition whose scene is
/// gone and the element whose composition is gone, and nothing live and not the cover; purge must delete the
/// listed composition child→parent (its actor and element go with it) and the stray element, leave the live
/// rows and the cover alone, and a rescan must list none of them. Real DB, no LLM, no SD.
public class TestPbOrphanCompositions extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static BaseRecord rawRowByOid(IOContext ioContext, String model, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		BaseRecord rec = ioContext.getSearch().findRecord(q);
		if (rec != null && rec.getSchema() == null) {
			rec.setSchema(model);
		}
		return rec;
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

	private static String oid(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	/// A scene with the setting/action/mood the MIGRATED compose reads, re-read as the user.
	private static BaseRecord sceneWithFields(BaseRecord user, BaseRecord book, int index, String slug) throws Exception {
		BaseRecord scene = PbBookUtil.createScene(user, book, index, "Scene " + index, PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene " + index, scene);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
			OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, "a rain-dark harbour street at dusk");
		patch.set(OlioFieldNames.FIELD_PB_ACTION, "walking toward the tavern door");
		patch.set(OlioFieldNames.FIELD_PB_MOOD, "wary");
		assertNotNull("patch scene " + index, IOSystem.getActiveContext().getAccessPoint().update(user, patch));
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord fresh = PbBookUtil.readScene(user, oid(scene), orgId);
		assertNotNull("scene " + index + " re-read", fresh);
		return fresh;
	}

	private static BaseRecord elementOn(BaseRecord user, BaseRecord comp, String description) {
		Map<String, Object> in = new HashMap<>();
		in.put(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, PbElementTypeEnumType.PROP.toString());
		in.put(FieldNames.FIELD_DESCRIPTION, description);
		BaseRecord element = PbComposeUtil.createElementRow(user, comp, in);
		assertNotNull("element '" + description + "' on " + comp.get(FieldNames.FIELD_NAME), element);
		return element;
	}

	/// A scene-less COVER composition, the shape a later phase's cover job will write: scene is null by design,
	/// so the scan must never read it as "scene points at a deleted record".
	private static BaseRecord coverComposition(BaseRecord user, BaseRecord book, long orgId, String tag) throws Exception {
		BaseRecord comp = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION);
		IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, comp, orgId);
		comp.set(FieldNames.FIELD_GROUP_ID, Long.valueOf(((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue()));
		comp.set(FieldNames.FIELD_NAME, "cover." + tag);
		comp.set(OlioFieldNames.FIELD_PB_BOOK, book);
		comp.set(OlioFieldNames.FIELD_PB_PURPOSE, PbCompositionPurposeEnumType.COVER.toString());
		comp.set(OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.USER.toString());
		comp.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
		comp.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(1));
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, comp);
		assertNotNull("cover composition created", created);
		BaseRecord fresh = PbComposeUtil.readComposition(user, oid(created), orgId);
		assertNotNull("cover composition re-read", fresh);
		assertNull("the cover carries no scene by design", fresh.get(OlioFieldNames.FIELD_PB_SCENE));
		return fresh;
	}

	@Test
	public void TestScanListsDanglingCompositionRowsAndPurgeRemovesThemChildFirst() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord user = getCreateUser("pbOrphanCompUser");
		assertNotNull("user", user);
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		String tag = shortId();
		String slug = "orc" + tag;
		BaseRecord book = PbBookUtil.createBook(user, dataPath, slug, "Orphan compositions " + slug);
		assertNotNull("book", book);
		String bookOid = oid(book);
		try {
			/// Scene 0: a SCENE composition (MIGRATED, from the scene's own fields - no LLM) with an actor and
			/// an element. The scene then goes row-only, leaving the composition's scene FK dangling.
			BaseRecord scene0 = sceneWithFields(user, book, 0, slug);
			ComposeResult cr = PbComposeUtil.composeMigrated(user, book, scene0, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord sceneComp = PbComposeUtil.readComposition(user, oid(cr.composition), orgId);
			assertNotNull("scene composition re-read", sceneComp);
			String purpose = sceneComp.get(OlioFieldNames.FIELD_PB_PURPOSE);
			assertNotNull("SCENE purpose present", purpose);
			assertEquals("SCENE purpose", PbCompositionPurposeEnumType.SCENE.toString(), purpose.toUpperCase());
			Map<String, Object> actorIn = new HashMap<>();
			actorIn.put(FieldNames.FIELD_DESCRIPTION, "a lamplighter on the quay");
			BaseRecord actor = PbComposeUtil.createActorRow(user, book, sceneComp, actorIn);
			assertNotNull("actor row on the scene composition", actor);
			BaseRecord element0 = elementOn(user, sceneComp, "a weathered bollard");
			String sceneCompOid = oid(sceneComp);
			String actorOid = oid(actor);
			String element0Oid = oid(element0);

			/// Scene 1: a CHAPBOOK_PAGE composition with an element; the composition then goes row-only,
			/// leaving the element's composition FK dangling.
			BaseRecord scene1 = sceneWithFields(user, book, 1, slug);
			BaseRecord pageComp1 = PbComposeUtil.getCreatePageComposition(user, book, scene1, PbCompositionSourceEnumType.USER);
			assertNotNull("page composition on scene 1", pageComp1);
			BaseRecord strayElement = elementOn(user, pageComp1, "a brass harbor lamp");
			String pageComp1Oid = oid(pageComp1);
			String strayElementOid = oid(strayElement);

			/// Scene 2: a live page composition with an element - the negative control.
			BaseRecord scene2 = sceneWithFields(user, book, 2, slug);
			BaseRecord pageComp2 = PbComposeUtil.getCreatePageComposition(user, book, scene2, PbCompositionSourceEnumType.USER);
			BaseRecord liveElement = elementOn(user, pageComp2, "a coil of wet rope");
			String pageComp2Oid = oid(pageComp2);
			String liveElementOid = oid(liveElement);

			/// A COVER composition: scene null by design, never an orphan.
			BaseRecord cover = coverComposition(user, book, orgId, tag);
			String coverOid = oid(cover);

			/// Row-only deletes, the way the old ChapBook delete/merge and a raw writer leave things.
			BaseRecord rawScene0 = rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE, oid(scene0), orgId);
			assertNotNull("raw scene 0", rawScene0);
			assertTrue("row-only delete of scene 0", ioContext.getRecordUtil().deleteRecord(rawScene0, true));
			BaseRecord rawPageComp1 = rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, pageComp1Oid, orgId);
			assertNotNull("raw page composition 1", rawPageComp1);
			assertTrue("row-only delete of page composition 1", ioContext.getRecordUtil().deleteRecord(rawPageComp1, true));
			assertNull("scene 0 is gone", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE, oid(scene0), orgId));
			assertNull("page composition 1 is gone", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, pageComp1Oid, orgId));
			assertNotNull("the scene composition survives its scene (precondition)",
				rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, sceneCompOid, orgId));
			assertNotNull("the stray element survives its composition (precondition)",
				rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE_ELEMENT, strayElementOid, orgId));
			CacheUtil.clearCache();

			/// ── scan(own) ──
			Map<String, Object> report = PbOrphanUtil.scan(user, false);
			assertEquals("own", report.get("scope"));
			Set<String> pb2 = objectIds(report, PbOrphanUtil.ORPHAN_PB2_ROW);
			logger.info("ORPHAN_PB2_ROW items: " + items(report, PbOrphanUtil.ORPHAN_PB2_ROW));
			assertTrue("ORPHAN_PB2_ROW lists the SCENE composition whose scene is gone", pb2.contains(sceneCompOid));
			assertTrue("ORPHAN_PB2_ROW lists the element whose composition is gone", pb2.contains(strayElementOid));
			assertFalse("the actor of the dangling composition is not listed on its own (its composition still exists)", pb2.contains(actorOid));
			assertFalse("the element of the dangling composition is not listed on its own (its composition still exists)", pb2.contains(element0Oid));
			Set<String> listed = allObjectIds(report);
			for (String live : new String[] { bookOid, oid(scene1), oid(scene2), pageComp2Oid, liveElementOid, coverOid }) {
				assertFalse("nothing live is listed: " + live, listed.contains(live));
			}
			assertFalse("the COVER composition (scene null by design) is never listed", pb2.contains(coverOid));

			/// scan is read-only.
			assertNotNull("scan deleted nothing: scene composition", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, sceneCompOid, orgId));
			assertNotNull("scan deleted nothing: stray element", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE_ELEMENT, strayElementOid, orgId));

			/// ── purge(own, PB2 rows only) ──
			Map<String, Object> purge = PbOrphanUtil.purge(user, false, List.of(PbOrphanUtil.ORPHAN_PB2_ROW));
			assertDeleted(purge, "scene composition", sceneCompOid);
			assertDeleted(purge, "stray element", strayElementOid);
			Map<String, Object> compResult = result(purge, sceneCompOid);
			assertEquals("the composition purge counts actor + element + composition", 3, ((Number) compResult.get("count")).intValue());

			assertNull("scene composition purged", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, sceneCompOid, orgId));
			assertNull("its actor purged with it", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid, orgId));
			assertNull("its element purged with it", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE_ELEMENT, element0Oid, orgId));
			assertNull("stray element purged", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE_ELEMENT, strayElementOid, orgId));
			assertNotNull("live page composition intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, pageComp2Oid, orgId));
			assertNotNull("live element intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE_ELEMENT, liveElementOid, orgId));
			assertNotNull("cover composition intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_COMPOSITION, coverOid, orgId));
			assertNotNull("scene 1 intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE, oid(scene1), orgId));
			assertNotNull("scene 2 intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_SCENE, oid(scene2), orgId));
			assertNotNull("book intact", rawRowByOid(ioContext, OlioModelNames.MODEL_PB_BOOK, bookOid, orgId));

			/// ── rescan ──
			Map<String, Object> again = PbOrphanUtil.scan(user, false);
			Set<String> remaining = allObjectIds(again);
			for (String o : new String[] { sceneCompOid, actorOid, element0Oid, strayElementOid, coverOid, pageComp2Oid, liveElementOid }) {
				assertFalse("rescan does not list " + o, remaining.contains(o));
			}
		}
		finally {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(user, bookOid);
			logger.info("cleanup " + slug + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
	}
}
