package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// V3 P10-A 2 (ledger 213 e / 219 f / 240 f): a V3 scene render (requestRenderPlanScene -> executePlanScene)
/// writes its image as the selected 'base' of the scene's RENDER_PLAN node (handle render_plan@<shortRef>)
/// and links neither scene.sceneNode nor scene.imageObjectId. bookPageView used to resolve a page image only
/// through those two, so every V3-rendered page had dataObjectId null and the reader's cover read
/// "0 rendered of N". The fixture lays the RENDER_PLAN node + base artifact down exactly as the executor
/// leaves them (no LLM, no SD) and asserts what the reader now gets. Real DB, non-admin user.
public class TestPbPageViewRenderPlanBase extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestPbPageViewRenderPlanBase.class);

	private BaseRecord owner;
	private String slug;
	private String bookOid;
	private BaseRecord book;
	private BaseRecord wf;
	private String wfPath;
	private String artPath;
	private long orgId;

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		owner = getCreateUser("pbPageViewOwner");
		assertNotNull("owner", owner);
		orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		slug = "pvb" + shortId();
		String dataPath = testProperties.getProperty("test.datagen.path");
		book = PbBookUtil.createBook(owner, dataPath, slug, "PageView " + slug);
		assertNotNull("book", book);
		bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		wfPath = PbBookUtil.workflowGroupPath(slug);
		artPath = PbBookUtil.artifactGroupPath(slug);
		wf = PbGraphUtil.getCreateWorkflow(owner, book, wfPath);
		assertNotNull("workflow", wf);
	}

	@After
	public void cleanupEach() {
		if (bookOid == null) return;
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
			if (!res.deleted) {
				logger.warn("[pageView] cleanup of " + slug + " failed: " + res.reason);
			}
		}
		catch (Exception e) {
			logger.warn("[pageView] cleanup of " + slug + " failed: " + e.getMessage());
		}
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private static byte[] pngBytes(int shade) throws Exception {
		BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 2; y++) {
			for (int x = 0; x < 2; x++) {
				img.setRGB(x, y, (shade << 16) | (shade << 8) | shade);
			}
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	private BaseRecord persistPng(String name, int shade) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, artPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);
		ByteModelUtil.setValue(data, pngBytes(shade));
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, data);
		assertNotNull("data.data " + name, created);
		return created;
	}

	private BaseRecord createScene(int idx) throws Exception {
		BaseRecord scene = PbBookUtil.createScene(owner, book, idx, "Scene " + idx, PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene " + idx, scene);
		return PbBookUtil.readScene(owner, scene.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/// The scene's RENDER_PLAN node as ensureRenderPlanNode creates it: handle render_plan@<shortRef>, scene
	/// scope, ordinal = sceneIndex, scene.sceneNode left alone.
	private BaseRecord addRenderPlanNode(BaseRecord scene) throws Exception {
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		int idx = ((Number) scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX)).intValue();
		BaseRecord node = PbGraphUtil.addNode(owner, wf, PbPipelineUtil.renderPlanHandle(sceneOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, idx);
		assertNotNull("render_plan node for scene " + idx, node);
		assertTrue("render_plan scope patch", PbPipelineUtil.patchNodeScope(owner, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		return PbGraphUtil.readNode(owner, node.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/// A PB1-shaped COMPOSITE node linked as scene.sceneNode (dualWriteScene's shape) holding a composite image.
	private BaseRecord addLinkedCompositeNode(BaseRecord scene, BaseRecord compositeData) throws Exception {
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		int idx = ((Number) scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX)).intValue();
		BaseRecord node = PbGraphUtil.addNode(owner, wf, PbPipelineUtil.compositeHandle(sceneOid), PbNodeTypeEnumType.COMPOSITE, wfPath, idx);
		assertNotNull("composite node for scene " + idx, node);
		assertTrue("composite scope patch", PbPipelineUtil.patchNodeScope(owner, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
		BaseRecord art = PbArtifactUtil.persistArtifact(owner, node, "composite", PbArtifactTypeEnumType.IMAGE, artPath, compositeData, null, null, null);
		assertNotNull("composite artifact", art);
		return PbGraphUtil.readNode(owner, node.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	private BaseRecord persistBase(BaseRecord planNode, BaseRecord data) {
		BaseRecord art = PbArtifactUtil.persistArtifact(owner, planNode, PbPipelineUtil.ROLE_BASE, PbArtifactTypeEnumType.IMAGE, artPath, data, null, null, null);
		assertNotNull("base artifact", art);
		return art;
	}

	private Map<String, Object> page(int idx) {
		List<Map<String, Object>> pages = PbServiceFacade.bookPageView(owner, bookOid);
		for (Map<String, Object> p : pages) {
			if (((Number) p.get("sceneIndex")).intValue() == idx) {
				return p;
			}
		}
		throw new AssertionError("no page for scene " + idx + " in " + pages);
	}

	private static int renderedCount(List<Map<String, Object>> pages) {
		int n = 0;
		for (Map<String, Object> p : pages) {
			if (p.get("dataObjectId") != null) n++;
		}
		return n;
	}

	private static String oid(BaseRecord r) {
		return r.get(FieldNames.FIELD_OBJECT_ID);
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Case 1: a RENDER_PLAN base with no sceneNode and no imageObjectId is the page's image, node and artifact;
	/// the untouched sibling page stays empty; "rendered of N" counts 1 of 2.
	@Test
	public void TestRenderPlanBaseIsThePageImage() throws Exception {
		BaseRecord s0 = createScene(0);
		createScene(1);
		assertNull("precondition: scene 0 has no sceneNode", s0.get(OlioFieldNames.FIELD_PB_SCENE_NODE));
		assertNull("precondition: scene 0 has no imageObjectId", s0.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));

		BaseRecord planNode = addRenderPlanNode(s0);
		BaseRecord png = persistPng("base-0-" + slug + ".png", 0x40);
		BaseRecord base = persistBase(planNode, png);

		List<Map<String, Object>> pages = PbServiceFacade.bookPageView(owner, bookOid);
		assertEquals("two pages", 2, pages.size());
		assertEquals("one rendered of two", 1, renderedCount(pages));

		Map<String, Object> p0 = page(0);
		assertEquals("page 0 image is the base's data", oid(png), p0.get("dataObjectId"));
		assertNotNull("page 0 resolves the image record: " + p0, p0.get("imageName"));
		assertEquals("image/png", p0.get("imageContentType"));
		assertNotNull("page 0 imageGroupPath", p0.get("imageGroupPath"));
		assertEquals("page 0 node is the RENDER_PLAN node", oid(planNode), p0.get("sceneNodeObjectId"));
		assertEquals("page 0 artifact is the base", oid(base), p0.get("artifactObjectId"));
		assertEquals("page 0 review state", PbReviewStatusEnumType.GENERATED.name(), p0.get("reviewStatus"));
		assertEquals("page 0 not pinned", Boolean.FALSE, p0.get("pinned"));
		assertNotNull("page 0 nodeStatus is the stored status", p0.get("nodeStatus"));

		Map<String, Object> p1 = page(1);
		assertNull("page 1 has no image", p1.get("dataObjectId"));
		assertNull("page 1 has no node", p1.get("sceneNodeObjectId"));
		assertNull("page 1 has no artifact", p1.get("artifactObjectId"));
		assertNull("page 1 has no review state", p1.get("reviewStatus"));
		assertEquals("page 1 pinned is an explicit false", Boolean.FALSE, p1.get("pinned"));
	}

	/// Case 2: a second base revision is auto-selected and becomes the page; approving it surfaces APPROVED
	/// and the pin approve() places on the node.
	@Test
	public void TestNewerSelectedBaseAndApprovalShowOnThePage() throws Exception {
		BaseRecord s0 = createScene(0);
		BaseRecord planNode = addRenderPlanNode(s0);
		BaseRecord png1 = persistPng("base-0-r1-" + slug + ".png", 0x40);
		persistBase(planNode, png1);
		assertEquals("first revision shows", oid(png1), page(0).get("dataObjectId"));

		BaseRecord png2 = persistPng("base-0-r2-" + slug + ".png", 0x80);
		BaseRecord base2 = persistBase(planNode, png2);
		Map<String, Object> after = page(0);
		assertEquals("the newer selected revision is the page", oid(png2), after.get("dataObjectId"));
		assertEquals(oid(base2), after.get("artifactObjectId"));
		assertEquals(PbReviewStatusEnumType.GENERATED.name(), after.get("reviewStatus"));

		BaseRecord approved = PbArtifactUtil.approve(owner, base2, oid(owner));
		assertNotNull("approve", approved);
		Map<String, Object> approvedPage = page(0);
		assertEquals(oid(png2), approvedPage.get("dataObjectId"));
		assertEquals(PbReviewStatusEnumType.APPROVED.name(), approvedPage.get("reviewStatus"));
		assertEquals("approve pins the node and the page shows it", Boolean.TRUE, approvedPage.get("pinned"));
		assertEquals(oid(planNode), approvedPage.get("sceneNodeObjectId"));
	}

	/// Case 3: precedence. A selected base wins over a linked COMPOSITE sceneNode's composite; a RENDER_PLAN
	/// node with no base yet leaves the composite page alone; with no sceneNode at all the base-less RENDER_PLAN
	/// node is still the page's node (its status reaches the reader) while the image falls back to imageObjectId.
	@Test
	public void TestRenderPlanBasePrecedence() throws Exception {
		BaseRecord s0 = createScene(0);
		BaseRecord s1 = createScene(1);
		BaseRecord s2 = createScene(2);

		/// scene 0: composite linked, then a RENDER_PLAN base -> base wins
		BaseRecord comp0 = persistPng("composite-0-" + slug + ".png", 0x20);
		BaseRecord compNode0 = addLinkedCompositeNode(s0, comp0);
		Map<String, Object> before = page(0);
		assertEquals("composite shows before any V3 render", oid(comp0), before.get("dataObjectId"));
		assertEquals(oid(compNode0), before.get("sceneNodeObjectId"));
		BaseRecord planNode0 = addRenderPlanNode(PbBookUtil.readScene(owner, oid(s0), orgId));
		Map<String, Object> plannedNoBase = page(0);
		assertEquals("a base-less RENDER_PLAN node does not take the page from the composite", oid(comp0), plannedNoBase.get("dataObjectId"));
		assertEquals(oid(compNode0), plannedNoBase.get("sceneNodeObjectId"));
		BaseRecord png0 = persistPng("base-0-" + slug + ".png", 0x60);
		BaseRecord base0 = persistBase(planNode0, png0);
		Map<String, Object> p0 = page(0);
		assertEquals("the RENDER_PLAN base wins over the composite", oid(png0), p0.get("dataObjectId"));
		assertEquals("and the page's node is the RENDER_PLAN node", oid(planNode0), p0.get("sceneNodeObjectId"));
		assertEquals(oid(base0), p0.get("artifactObjectId"));

		/// scene 1: RENDER_PLAN node, no base, no sceneNode, imageObjectId set -> node from the plan, image from the fallback
		BaseRecord fallback1 = persistPng("fallback-1-" + slug + ".png", 0x90);
		BaseRecord imgPatch = PbGraphUtil.patchOf(s1, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		imgPatch.set(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, oid(fallback1));
		assertNotNull("scene 1 imageObjectId patch", IOSystem.getActiveContext().getAccessPoint().update(owner, imgPatch));
		BaseRecord planNode1 = addRenderPlanNode(PbBookUtil.readScene(owner, oid(s1), orgId));
		Map<String, Object> p1 = page(1);
		assertEquals("image falls back to imageObjectId", oid(fallback1), p1.get("dataObjectId"));
		assertEquals("the base-less RENDER_PLAN node is still the page's node", oid(planNode1), p1.get("sceneNodeObjectId"));
		assertNotNull("its status is surfaced", p1.get("nodeStatus"));
		assertNull("no artifact", p1.get("artifactObjectId"));
		assertNull("no review state", p1.get("reviewStatus"));
		assertEquals(Boolean.FALSE, p1.get("pinned"));

		/// scene 2: nothing at all
		Map<String, Object> p2 = page(2);
		assertNull(p2.get("dataObjectId"));
		assertNull(p2.get("sceneNodeObjectId"));
		assertEquals("scene 2 untouched: " + s2, Boolean.FALSE, p2.get("pinned"));

		assertEquals("two rendered of three", 2, renderedCount(PbServiceFacade.bookPageView(owner, bookOid)));
	}
}
