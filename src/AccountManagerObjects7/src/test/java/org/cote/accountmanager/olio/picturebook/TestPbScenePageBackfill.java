package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/// A rendered-but-unrecorded scene is backfilled from the saved image, never re-rendered. This is the
/// user-visible half of the stale-graph defect: generateSceneImage saved the image and wrote imageObjectId
/// into the PB1 scene note, but the olio.pb.scene row was never created, so the PB2 reader shows
/// "N scenes extracted - none rendered yet" for a book whose images all exist.
///
/// Fixture: a PB2 book, its PB1 ~/Data/PictureBooks/<slug> tree with a .pictureBookMeta note linked by
/// pb2BookObjectId, one scene note carrying imageObjectId, and the image as a real data.data row. No
/// olio.pb.scene row. checkBook must report SCENE_ROW_MISSING; healBook must create the row with the
/// image bound; bookPageView must then yield a page whose dataObjectId is that image; re-check is clean.
/// Real DB, no LLM, no SD, non-admin user.
public class TestPbScenePageBackfill extends BaseTest {

	private static final String PB1_BOOKS_ROOT = "~/Data/PictureBooks/";

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
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

	private static byte[] pngBytes() throws Exception {
		BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(img, "png", baos));
		return baos.toByteArray();
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
	private static List<Map<String, Object>> healedOf(Map<String, Object> report, String code) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> h : (List<Map<String, Object>>) report.get("healed")) {
			if (code.equals(h.get("code"))) {
				out.add(h);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> refs(Map<String, Object> entry) {
		return (Map<String, Object>) entry.get("refs");
	}

	@Test
	public void TestHealBackfillsSceneRowFromSavedImage() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord owner = getCreateUser("pbHealthOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		String slug = "hbf" + shortId();

		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Backfill " + slug);
		assertNotNull("book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue("no scene rows yet", PbBookUtil.listScenes(owner, book).isEmpty());

		/// The PB1 side, exactly as extraction + render leave it.
		String pb1Path = PB1_BOOKS_ROOT + slug;
		String scenesPath = pb1Path + "/Scenes";
		assertNotNull(ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, pb1Path, GroupEnumType.DATA.toString(), orgId));
		assertNotNull(ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, scenesPath, GroupEnumType.DATA.toString(), orgId));

		BaseRecord image = getCreateData(owner, "scene-0-" + slug + ".png", "image/png", pngBytes(), scenesPath, orgId);
		assertNotNull("image row", image);
		String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("image objectId", imageOid);

		Map<String, Object> noteText = new LinkedHashMap<>();
		noteText.put("imageObjectId", imageOid);
		noteText.put("blurb", "A rendered scene nobody recorded");
		noteText.put("status", "done");
		BaseRecord sceneNote = createNote(owner, scenesPath, "scene-0-" + slug, JSONUtil.exportObject(noteText));
		String noteOid = sceneNote.get(FieldNames.FIELD_OBJECT_ID);

		Map<String, Object> sceneEntry = new LinkedHashMap<>();
		sceneEntry.put("objectId", noteOid);
		sceneEntry.put("index", 0);
		sceneEntry.put("title", "The Unrecorded Scene");
		sceneEntry.put("blurb", "meta blurb");
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(sceneEntry);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("schema", "olio.pictureBookMeta");
		meta.put("pb2BookObjectId", bookOid);
		meta.put("scenes", scenes);
		createNote(owner, pb1Path, PictureBookUtil.META_NOTE_NAME, JSONUtil.exportObject(meta));

		/// The reader is what the user sees: the PB1 side says rendered, the PB2 side has no page.
		List<Map<String, Object>> pb1 = PictureBookUtil.listScenes(owner, bookOid);
		assertEquals("PB1 lists the scene", 1, pb1.size());
		assertEquals("PB1 scene carries the image", imageOid, pb1.get(0).get("imageObjectId"));
		assertTrue("PB2 reader has no pages before heal", PbServiceFacade.bookPageView(owner, bookOid).isEmpty());

		/// Read-only check names the gap and does not fix it.
		Map<String, Object> before = PbHealthUtil.checkBook(owner, bookOid);
		Set<String> codes = findingCodes(before);
		assertTrue("SCENE_ROW_MISSING reported: " + codes, codes.contains(PbHealthUtil.SCENE_ROW_MISSING));
		Map<String, Object> gap = null;
		for (Map<String, Object> f : findings(before)) {
			if (PbHealthUtil.SCENE_ROW_MISSING.equals(f.get("code"))) {
				gap = f;
			}
		}
		assertNotNull(gap);
		assertEquals("ERROR", gap.get("severity"));
		assertEquals(Boolean.TRUE, gap.get("healable"));
		assertEquals(imageOid, refs(gap).get("imageObjectId"));
		assertEquals(0, ((Number) refs(gap).get("sceneIndex")).intValue());
		assertTrue("checkBook created no scene row", PbBookUtil.listScenes(owner, book).isEmpty());

		/// Repair: the row is created from the saved image, nothing is rendered.
		Map<String, Object> healReport = PbHealthUtil.healBook(owner, dataPath, bookOid, null);
		List<Map<String, Object>> healed = healedOf(healReport, PbHealthUtil.SCENE_ROW_MISSING);
		assertEquals("one SCENE_ROW_MISSING heal: " + healReport.get("healed") + " skipped=" + healReport.get("skipped"), 1, healed.size());
		assertEquals(OlioModelNames.MODEL_PB_SCENE, refs(healed.get(0)).get("model"));
		assertFalse("post-heal audit clear of SCENE_ROW_MISSING: " + findingCodes(healReport), findingCodes(healReport).contains(PbHealthUtil.SCENE_ROW_MISSING));

		List<BaseRecord> rows = PbBookUtil.listScenes(owner, book);
		assertEquals("exactly one scene row", 1, rows.size());
		BaseRecord row = rows.get(0);
		assertEquals(0, ((Number) row.get(OlioFieldNames.FIELD_PB_SCENE_INDEX)).intValue());
		assertEquals("row bound to the saved image", imageOid, row.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertEquals("row title from meta", "The Unrecorded Scene", row.get(OlioFieldNames.FIELD_PB_TITLE));
		assertEquals("row blurb from the scene note", "A rendered scene nobody recorded", row.get(OlioFieldNames.FIELD_PB_BLURB));

		/// What the reader shows now.
		List<Map<String, Object>> pages = PbServiceFacade.bookPageView(owner, bookOid);
		assertEquals("one page", 1, pages.size());
		Map<String, Object> page = pages.get(0);
		assertEquals(imageOid, page.get("dataObjectId"));
		assertEquals(0, ((Number) page.get("sceneIndex")).intValue());
		assertNotNull("page resolves the image record: " + page, page.get("imageName"));
		assertEquals("image/png", page.get("imageContentType"));

		/// Idempotent: nothing left to backfill, no second row.
		Map<String, Object> again = PbHealthUtil.healBook(owner, dataPath, bookOid, null);
		assertTrue("second heal backfills nothing", healedOf(again, PbHealthUtil.SCENE_ROW_MISSING).isEmpty());
		assertFalse(findingCodes(again).contains(PbHealthUtil.SCENE_ROW_MISSING));
		assertEquals("still one scene row", 1, PbBookUtil.listScenes(owner, book).size());

		DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
		assertTrue("cleanup: " + res.reason, res.deleted);
	}
}
