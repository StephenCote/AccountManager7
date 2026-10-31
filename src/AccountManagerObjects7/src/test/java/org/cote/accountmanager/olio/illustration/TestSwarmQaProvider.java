package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/// V3 Phase 8, P8-3: the Swarm face-detector QA provider offline (design §17 fallback, ledger §6.C 220 f).
/// Mask → faces on synthetic masks and on the real `.39` mask of the P5-2 two-actor page
/// ({@code media/flux/qa/p83-faces-two.png}, the 2026-10-30 spike output); faces → report through the
/// plan's actor boxes; the detection graph's shape; `object_info` parsing; and {@link SwarmQaProvider#check}
/// end to end over a scripted backend, including every degradation note. No renderer is touched here;
/// {@code TestSwarmQaLive} runs the same graph on `.39`.
public class TestSwarmQaProvider {

	static final File REAL_MASK = new File("media/flux/qa/p83-faces-two.png");
	static final String PAGE_KEY = ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, SwarmQaProvider.INPUT_PAGE);

	/// `.39`'s single-type object_info body for SwarmYoloDetection (2026-10-30), abridged to the inputs.
	static final String YOLO_INFO = "{\"SwarmYoloDetection\":{\"input\":{\"required\":{\"image\":[\"IMAGE\"],"
		+ "\"model_name\":[[\"face_yolov8m.pt\"]],\"index\":[\"INT\",{\"default\":0,\"min\":0,\"max\":256,\"step\":1}]},"
		+ "\"optional\":{\"class_filter\":[\"STRING\",{\"default\":\"\",\"multiline\":false}],"
		+ "\"sort_order\":[[\"left-right\",\"right-left\",\"top-bottom\",\"bottom-top\",\"largest-smallest\",\"smallest-largest\"]],"
		+ "\"threshold\":[\"FLOAT\",{\"default\":0.25,\"min\":0.0,\"max\":1.0,\"step\":0.01}]}},"
		+ "\"output\":[\"MASK\"],\"name\":\"SwarmYoloDetection\"}}";

	/* ------------------------------------------------------------------------------------------------ */
	/* mask → faces                                                                                      */
	/* ------------------------------------------------------------------------------------------------ */

	@Test
	public void facesOnSyntheticMask() {
		/// Two boxes on a 200x100 canvas: 40x20 px at (20,10) and 20x10 px at (120,50).
		byte[] mask = MaskImage.rect(List.of(new RenderPlan.Box(0.6, 0.5, 0.1, 0.1), new RenderPlan.Box(0.1, 0.1, 0.2, 0.2)), 200, 100);
		List<SwarmQaProvider.Face> faces = SwarmQaProvider.faces(mask);
		assertNotNull(faces);
		assertEquals(2, faces.size());
		SwarmQaProvider.Face left = faces.get(0);
		SwarmQaProvider.Face right = faces.get(1);
		assertEquals("sorted left to right regardless of box order", 800, left.getPixels());
		assertEquals(200, right.getPixels());
		assertEquals(0.20, left.getCx(), 0.0001);
		assertEquals(0.20, left.getCy(), 0.0001);
		assertEquals(0.10, left.getX(), 0.0001);
		assertEquals(0.10, left.getY(), 0.0001);
		assertEquals(0.20, left.getW(), 0.0001);
		assertEquals(0.20, left.getH(), 0.0001);
		assertEquals(0.65, right.getCx(), 0.0001);
		assertEquals(0.55, right.getCy(), 0.0001);
		assertEquals(0.60, right.getX(), 0.0001);
		assertEquals(0.50, right.getY(), 0.0001);
		assertEquals(0.10, right.getW(), 0.0001);
		assertEquals(0.10, right.getH(), 0.0001);
		assertTrue(left.centroidIn(new RenderPlan.Box(0.0, 0.0, 0.5, 1.0)));
		assertFalse(left.centroidIn(new RenderPlan.Box(0.5, 0.0, 0.5, 1.0)));
		assertTrue(right.centroidIn(new RenderPlan.Box(0.5, 0.0, 0.5, 1.0)));
		assertFalse(right.centroidIn(null));
		assertEquals("(0.20,0.20)", left.toString());
	}

	@Test
	public void facesDropsNoiseMergesTouchingBoxesAndReportsGarbage() {
		assertEquals("empty mask", 0, SwarmQaProvider.faces(MaskImage.rect(List.of(), 64, 48)).size());
		/// 3x3 = 9 px is below MIN_COMPONENT_PIXELS; 4x4 = 16 px is exactly on it and kept.
		assertEquals(0, SwarmQaProvider.faces(MaskImage.rect(List.of(new RenderPlan.Box(0.5, 0.5, 0.03, 0.03)), 100, 100)).size());
		List<SwarmQaProvider.Face> one = SwarmQaProvider.faces(MaskImage.rect(List.of(new RenderPlan.Box(0.5, 0.5, 0.04, 0.04)), 100, 100));
		assertEquals(1, one.size());
		assertEquals(SwarmQaProvider.MIN_COMPONENT_PIXELS, one.get(0).getPixels());
		/// Two boxes that touch (x 10..29 and 30..49) are one 4-connected component: the documented under-count.
		List<SwarmQaProvider.Face> merged = SwarmQaProvider.faces(MaskImage.rect(List.of(new RenderPlan.Box(0.1, 0.1, 0.2, 0.2), new RenderPlan.Box(0.3, 0.1, 0.2, 0.2)), 100, 100));
		assertEquals(1, merged.size());
		assertEquals(0.40, merged.get(0).getW(), 0.0001);
		assertEquals(800, merged.get(0).getPixels());
		/// A one-pixel gap keeps them apart.
		List<SwarmQaProvider.Face> apart = SwarmQaProvider.faces(MaskImage.rect(List.of(new RenderPlan.Box(0.1, 0.1, 0.2, 0.2), new RenderPlan.Box(0.31, 0.1, 0.2, 0.2)), 100, 100));
		assertEquals(2, apart.size());
		/// Lit is red >= 128: a 127 grey block is background, a 128 one is a face.
		assertEquals(0, SwarmQaProvider.faces(grey(127)).size());
		assertEquals(1, SwarmQaProvider.faces(grey(128)).size());
		assertNull(SwarmQaProvider.faces(null));
		assertNull(SwarmQaProvider.faces(new byte[0]));
		assertNull(SwarmQaProvider.faces("not a png".getBytes()));
	}

	/// A 20x20 image with a 10x10 block of the given grey level in the middle.
	private static byte[] grey(int level) {
		BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
		int rgb = (level << 16) | (level << 8) | level;
		for(int y = 5; y < 15; y++) {
			for(int x = 5; x < 15; x++) {
				img.setRGB(x, y, rgb);
			}
		}
		return MaskImage.png(img);
	}

	@Test
	public void facesOnTheRealMask() throws IOException {
		List<SwarmQaProvider.Face> faces = SwarmQaProvider.faces(realMask());
		assertNotNull(faces);
		assertEquals("two faces on the P5-2 two-actor page: " + faces, 2, faces.size());
		SwarmQaProvider.Face left = faces.get(0);
		SwarmQaProvider.Face right = faces.get(1);
		/// Spike measurement 2026-10-30 (1024x1024): left box ~x368-432/y172-260, right ~x600-672/y115-216.
		assertEquals(0.39, left.getCx(), 0.04);
		assertEquals(0.21, left.getCy(), 0.04);
		assertEquals(0.62, right.getCx(), 0.04);
		assertEquals(0.16, right.getCy(), 0.04);
		for(SwarmQaProvider.Face f : faces) {
			assertTrue("a face box is a few percent of the page, not a speck or the whole page: " + f.getW() + "x" + f.getH(),
				f.getW() > 0.03 && f.getW() < 0.15 && f.getH() > 0.03 && f.getH() < 0.15);
			assertTrue(f.getPixels() > 1000);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* faces → report                                                                                    */
	/* ------------------------------------------------------------------------------------------------ */

	@Test
	public void mapFacesOnTheRealMaskAgainstThePlans() throws IOException {
		List<SwarmQaProvider.Face> faces = SwarmQaProvider.faces(realMask());

		QaReport two = SwarmQaProvider.mapFaces(twoActorPlan(), faces, 12L);
		assertEquals("two faces, two boxed actors: " + two.toJson(), QaReport.Result.PASS, two.getResult());
		assertEquals(SwarmQaProvider.NAME, two.getProvider());
		assertEquals(SwarmQaProvider.DETECTOR_MODEL, two.getModel());
		assertEquals(12L, two.getDurationMs());
		QaReport.Check count = two.checksOf(QaReport.KIND_PERSON_COUNT).get(0);
		assertEquals(QaReport.Result.PASS, count.getResult());
		assertEquals("2", count.getExpected());
		assertEquals("2", count.getObserved());
		assertTrue(count.getDetail(), count.getDetail().contains(SwarmQaProvider.DETECTOR_MODEL));
		List<QaReport.Check> present = two.checksOf(QaReport.KIND_ACTOR_PRESENT);
		assertEquals(2, present.size());
		assertEquals("a1", present.get(0).getActorId());
		assertEquals(QaReport.Result.PASS, present.get(0).getResult());
		assertEquals("Left", present.get(0).getExpected());
		assertEquals("a2", present.get(1).getActorId());
		assertEquals(QaReport.Result.PASS, present.get(1).getResult());
		/// identity is never judged; outfit only appears where the plan carries outfit text (a1 here)
		List<QaReport.Check> identity = two.checksOf(QaReport.KIND_IDENTITY);
		assertEquals(2, identity.size());
		assertEquals(QaReport.Result.UNAVAILABLE, identity.get(0).getResult());
		assertEquals(QaReport.Result.UNAVAILABLE, identity.get(1).getResult());
		List<QaReport.Check> outfit = two.checksOf(QaReport.KIND_OUTFIT);
		assertEquals(1, outfit.size());
		assertEquals("a1", outfit.get(0).getActorId());
		assertEquals(QaReport.Result.UNAVAILABLE, outfit.get(0).getResult());
		assertTrue(two.checksOf(QaReport.KIND_LOCATION).isEmpty());
		assertTrue(two.checksOf(QaReport.KIND_COMPOSITION).isEmpty());

		/// A third actor planned on the right where no face is: count short and that actor absent.
		QaReport three = SwarmQaProvider.mapFaces(threeActorPlan(), faces, 1L);
		assertEquals(QaReport.Result.FAIL, three.getResult());
		count = three.checksOf(QaReport.KIND_PERSON_COUNT).get(0);
		assertEquals(QaReport.Result.FAIL, count.getResult());
		assertEquals("3", count.getExpected());
		assertEquals("2", count.getObserved());
		present = three.checksOf(QaReport.KIND_ACTOR_PRESENT);
		assertEquals(3, present.size());
		assertEquals(QaReport.Result.PASS, present.get(0).getResult());
		assertEquals(QaReport.Result.PASS, present.get(1).getResult());
		assertEquals("a3", present.get(2).getActorId());
		assertEquals(QaReport.Result.FAIL, present.get(2).getResult());
		assertEquals("absent", present.get(2).getObserved());
		assertTrue(present.get(2).getDetail(), present.get(2).getDetail().startsWith("no unclaimed face inside the planned box"));
	}

	@Test
	public void mapFacesEdgeCases() {
		List<SwarmQaProvider.Face> one = List.of(new SwarmQaProvider.Face(900, 0.3, 0.3, 0.25, 0.25, 0.1, 0.1));
		List<SwarmQaProvider.Face> none = List.of();

		/// Nobody planned, nobody found: PASS.
		QaReport r = SwarmQaProvider.mapFaces(plan(), none, 0L);
		assertEquals(QaReport.Result.PASS, r.getResult());
		assertEquals(1, r.getChecks().size());
		assertEquals("0", r.getChecks().get(0).getObserved());

		/// Nobody planned, one found: a WARN (extra person), no actor checks.
		r = SwarmQaProvider.mapFaces(plan(), one, 0L);
		assertEquals(QaReport.Result.WARN, r.getResult());
		assertEquals(1, r.getChecks().size());

		/// A referenced actor without a box: presence UNAVAILABLE, the count still PASSes and so does the rollup.
		r = SwarmQaProvider.mapFaces(plan(actor("a1", "Mara", null, true, RenderPlan.ROLE_FOREGROUND, null)), one, 0L);
		assertEquals(QaReport.Result.PASS, r.getResult());
		QaReport.Check p = r.checksOf(QaReport.KIND_ACTOR_PRESENT).get(0);
		assertEquals(QaReport.Result.UNAVAILABLE, p.getResult());
		assertEquals("Mara", p.getExpected());
		assertTrue(p.getDetail(), p.getDetail().startsWith("no planned box"));
		assertTrue("no outfit text, no OUTFIT check", r.checksOf(QaReport.KIND_OUTFIT).isEmpty());
		assertEquals(1, r.checksOf(QaReport.KIND_IDENTITY).size());

		/// An unreferenced foreground actor counts toward the expected people but gets no presence check;
		/// a background actor counts for nothing.
		r = SwarmQaProvider.mapFaces(plan(
			actor("a1", "Mara", new RenderPlan.Box(0, 0, 1, 1), false, RenderPlan.ROLE_FOREGROUND, "red cloak"),
			actor("bg", "Crowd", new RenderPlan.Box(0, 0, 1, 1), true, RenderPlan.ROLE_BACKGROUND, null)), one, 0L);
		assertEquals(QaReport.Result.PASS, r.getResult());
		assertEquals("1", r.checksOf(QaReport.KIND_PERSON_COUNT).get(0).getExpected());
		assertEquals(1, r.getChecks().size());

		/// Two actors with the same box and one face: the first claims it, the second is absent.
		r = SwarmQaProvider.mapFaces(plan(
			actor("a1", "Mara", new RenderPlan.Box(0, 0, 1, 1), true, RenderPlan.ROLE_FOREGROUND, null),
			actor("a2", "", new RenderPlan.Box(0, 0, 1, 1), true, RenderPlan.ROLE_FOREGROUND, "  ")), one, 0L);
		assertEquals(QaReport.Result.FAIL, r.getResult());
		List<QaReport.Check> present = r.checksOf(QaReport.KIND_ACTOR_PRESENT);
		assertEquals(QaReport.Result.PASS, present.get(0).getResult());
		assertTrue(present.get(0).getDetail(), present.get(0).getDetail().startsWith("face (0.30,0.30) inside the planned box"));
		assertEquals(QaReport.Result.FAIL, present.get(1).getResult());
		assertEquals("a blank name falls back to the actor id", "a2", present.get(1).getExpected());
		assertEquals("blank outfit text emits no OUTFIT check", 0, r.checksOf(QaReport.KIND_OUTFIT).size());
		assertEquals(QaReport.Result.FAIL, r.checksOf(QaReport.KIND_PERSON_COUNT).get(0).getResult());

		/// A face outside every box: the count matches but the actor is absent.
		r = SwarmQaProvider.mapFaces(plan(actor("a1", "Mara", new RenderPlan.Box(0.5, 0.5, 0.5, 0.5), true, RenderPlan.ROLE_FOREGROUND, null)), one, 0L);
		assertEquals(QaReport.Result.FAIL, r.getResult());
		assertEquals(QaReport.Result.PASS, r.checksOf(QaReport.KIND_PERSON_COUNT).get(0).getResult());
		assertEquals(QaReport.Result.FAIL, r.checksOf(QaReport.KIND_ACTOR_PRESENT).get(0).getResult());

		/// The report round-trips.
		QaReport back = QaReport.fromJson(r.toJson());
		assertEquals(r.getResult(), back.getResult());
		assertEquals(r.getChecks().size(), back.getChecks().size());
		assertEquals(SwarmQaProvider.NAME, back.getProvider());
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* graph and object_info                                                                             */
	/* ------------------------------------------------------------------------------------------------ */

	@Test
	public void qaGraphShape() {
		ComfyGraph g = SwarmQaProvider.qaGraph();
		assertEquals(4, g.size());
		assertEquals(ComfyGraph.CLASS_LOAD_IMAGE_B64, g.classType(SwarmQaProvider.NODE_LOAD));
		assertEquals(SwarmQaProvider.CLASS_YOLO, g.classType(SwarmQaProvider.NODE_YOLO));
		assertEquals(SwarmQaProvider.CLASS_MASK_TO_IMAGE, g.classType(SwarmQaProvider.NODE_MASK_IMAGE));
		assertEquals(ComfyGraph.CLASS_SAVE_IMAGE_WS, g.classType(ComfyGraph.SAVE_FINAL));
		assertEquals(List.of(ComfyGraph.SAVE_FINAL), g.saveNodeIds());
		assertEquals("the page arrives by tag, never inline", ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, SwarmQaProvider.INPUT_PAGE),
			g.inputs(SwarmQaProvider.NODE_LOAD).get(ComfyGraph.INPUT_IMAGE_BASE64));
		g.assertNoInlineImages();
		Map<String, Object> yolo = g.inputs(SwarmQaProvider.NODE_YOLO);
		assertEquals(SwarmQaProvider.DETECTOR_MODEL, yolo.get("model_name"));
		assertEquals("index 0 = every detection in one mask", 0, ((Number) yolo.get("index")).intValue());
		assertEquals(SwarmQaProvider.THRESHOLD, ((Number) yolo.get("threshold")).doubleValue(), 0.0);
		assertEquals("left-right", yolo.get("sort_order"));
		assertEquals(SwarmQaProvider.NODE_LOAD, ComfyGraph.linkTarget(yolo.get("image")));
		assertEquals(SwarmQaProvider.NODE_YOLO, ComfyGraph.linkTarget(g.inputs(SwarmQaProvider.NODE_MASK_IMAGE).get("mask")));
		assertEquals(SwarmQaProvider.NODE_MASK_IMAGE, ComfyGraph.linkTarget(g.inputs(ComfyGraph.SAVE_FINAL).get("images")));
		assertTrue(g.danglingLinks().isEmpty());
		assertEquals(Set.of(PAGE_KEY), g.tags());
		assertEquals(SwarmQaProvider.INPUT_PAGE, ComfyGraph.tagName(PAGE_KEY));
		/// The page input the provider builds fills the one tag.
		GraphInputs inputs = new GraphInputs().image(SwarmQaProvider.INPUT_PAGE, MaskImage.rect(List.of(), 8, 8));
		assertEquals(Set.of(PAGE_KEY), inputs.names());
		assertTrue(NodeCatalogue.describe(NodeCatalogue.validateStructure(g, inputs.names())), NodeCatalogue.validateStructure(g, inputs.names()).isEmpty());
		assertEquals("missing page input is an unfilled tag", 1, NodeCatalogue.validateStructure(g, Set.of()).size());
		assertEquals("the graph is deterministic", g.graphHash(), SwarmQaProvider.qaGraph().graphHash());
		assertTrue(g.toJson().contains("\"${" + PAGE_KEY + "}\""));
	}

	@Test
	public void detectorModelsParsesObjectInfo() {
		assertEquals(List.of("face_yolov8m.pt"), SwarmQaProvider.detectorModels(YOLO_INFO));
		/// The full object_info body carries the same shape among other types.
		String full = "{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":[[\"a.safetensors\"]]}}},"
			+ YOLO_INFO.substring(1, YOLO_INFO.length() - 1) + ",\"MaskToImage\":{\"input\":{\"required\":{\"mask\":[\"MASK\"]}}}}";
		assertEquals(List.of("face_yolov8m.pt"), SwarmQaProvider.detectorModels(full));
		assertEquals(List.of("hand_yolov8n.pt", "face_yolov8m.pt"), SwarmQaProvider.detectorModels(
			"{\"SwarmYoloDetection\":{\"input\":{\"required\":{\"model_name\":[[\"hand_yolov8n.pt\",\"face_yolov8m.pt\"]]}}}}"));
		assertEquals("a free-text model_name offers no choices", List.of(), SwarmQaProvider.detectorModels(
			"{\"SwarmYoloDetection\":{\"input\":{\"required\":{\"model_name\":[\"STRING\"]}}}}"));
		assertEquals("no model_name at all", List.of(), SwarmQaProvider.detectorModels("{\"SwarmYoloDetection\":{\"input\":{\"required\":{}}}}"));
		assertNull("the type is absent", SwarmQaProvider.detectorModels("{\"MaskToImage\":{\"input\":{\"required\":{\"mask\":[\"MASK\"]}}}}"));
		assertNull("Swarm's answer for an unknown type", SwarmQaProvider.detectorModels("{}"));
		assertNull(SwarmQaProvider.detectorModels("{\"SwarmYoloDetection\":7}"));
		assertNull(SwarmQaProvider.detectorModels("<html>502</html>"));
		assertNull(SwarmQaProvider.detectorModels(null));
		assertNull(SwarmQaProvider.detectorModels("  "));
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* check() over a scripted backend                                                                   */
	/* ------------------------------------------------------------------------------------------------ */

	@Test
	public void checkEndToEndOverAScriptedBackend() throws IOException {
		byte[] page = MaskImage.solid(64, 64, java.awt.Color.GRAY);
		Scripted be = new Scripted(YOLO_INFO, new SwarmGraphClient.RawResponse(List.of("View/local/raw/mask.png"), null, 1700L, false), realMask());
		SwarmQaProvider p = new SwarmQaProvider(be, "http://127.0.0.1:9", "OfficialStableDiffusion/sd_xl_base_1.0.safetensors");
		assertEquals(SwarmQaProvider.NAME, p.name());
		assertEquals("http://127.0.0.1:9", p.getServer());
		assertEquals("OfficialStableDiffusion/sd_xl_base_1.0.safetensors", p.getModel());
		assertTrue(p.isAvailable());
		assertTrue("probed once", p.isAvailable());
		assertEquals(1, be.probes);

		QaReport rep = p.check(twoActorPlan(), page);
		assertEquals(rep.toJson(), QaReport.Result.PASS, rep.getResult());
		assertEquals(2, rep.checksOf(QaReport.KIND_ACTOR_PRESENT).size());
		assertEquals(1, be.generates);
		assertEquals("the render's checkpoint is what the raw post names", "OfficialStableDiffusion/sd_xl_base_1.0.safetensors", be.lastModel);
		assertEquals(1, be.lastImages);
		assertEquals(SwarmQaProvider.qaGraph().graphHash(), be.lastGraph.graphHash());
		assertEquals(Set.of(PAGE_KEY), be.lastInputs.names());
		assertSame(page, be.lastInputs.image(SwarmQaProvider.INPUT_PAGE));
		assertEquals("View/local/raw/mask.png", be.lastFetched);

		QaReport three = p.check(threeActorPlan(), page);
		assertEquals(QaReport.Result.FAIL, three.getResult());
		assertEquals("still probed once", 1, be.probes);
		assertEquals(2, be.generates);
	}

	@Test
	public void checkDegradesWithAReasonAndNeverThrows() throws IOException {
		byte[] page = MaskImage.solid(16, 16, java.awt.Color.GRAY);
		SwarmGraphClient.RawResponse ok = new SwarmGraphClient.RawResponse(List.of("View/local/raw/mask.png"), null, 5L, false);
		RenderPlan plan = twoActorPlan();

		Scripted be = new Scripted(YOLO_INFO, ok, realMask());
		SwarmQaProvider p = new SwarmQaProvider(be, "s", "m");
		assertUnavailable(p.check(null, page), "no render plan");
		assertUnavailable(p.check(plan, null), "no image bytes");
		assertUnavailable(p.check(plan, new byte[0]), "no image bytes");
		assertEquals("input checks come before the probe", 0, be.probes);

		be = new Scripted(null, ok, realMask());
		p = new SwarmQaProvider(be, "s", "m");
		assertFalse(p.isAvailable());
		assertUnavailable(p.check(plan, page), "no object_info from s");
		assertEquals("never posts a graph when unavailable", 0, be.generates);

		assertUnavailable(new SwarmQaProvider(new Scripted("{}", ok, realMask()), "s", "m").check(plan, page), "SwarmYoloDetection not listed by s");
		assertUnavailable(new SwarmQaProvider(new Scripted("{\"SwarmYoloDetection\":{\"input\":{\"required\":{\"model_name\":[[\"hand_yolov8n.pt\"]]}}}}", ok, realMask()), "s", "m")
			.check(plan, page), "face_yolov8m.pt not installed on s ([hand_yolov8n.pt])");

		be = new Scripted(YOLO_INFO, ok, realMask());
		be.probeThrows = true;
		p = new SwarmQaProvider(be, "s", "m");
		assertUnavailable(p.check(plan, page), "no object_info from s");

		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, new SwarmGraphClient.RawResponse(null, null, 0L, true), realMask()), "s", "m").check(plan, page),
			"renderer unreachable: s");
		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, null, realMask()), "s", "m").check(plan, page), "renderer unreachable: s");
		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, new SwarmGraphClient.RawResponse(null, "Unsupported node type 'SwarmYoloDetection'", 3L, false), realMask()), "s", "m")
			.check(plan, page), "renderer error: Unsupported node type 'SwarmYoloDetection'");
		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, new SwarmGraphClient.RawResponse(List.of(), null, 3L, false), realMask()), "s", "m").check(plan, page),
			"renderer returned no mask");
		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, ok, null), "s", "m").check(plan, page), "mask image could not be fetched");
		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, ok, new byte[0]), "s", "m").check(plan, page), "mask image could not be fetched");
		assertUnavailable(new SwarmQaProvider(new Scripted(YOLO_INFO, ok, "not a png".getBytes()), "s", "m").check(plan, page), "mask image did not decode");

		be = new Scripted(YOLO_INFO, ok, realMask());
		be.generateThrows = true;
		assertUnavailable(new SwarmQaProvider(be, "s", "m").check(plan, page), "provider error: scripted generate failure");

		try {
			new SwarmQaProvider((SwarmQaProvider.Backend) null, "s", "m");
			fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
		try {
			new SwarmQaProvider.ClientBackend(null);
			fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
	}

	private static void assertUnavailable(QaReport r, String note) {
		assertEquals(r.toJson(), QaReport.Result.UNAVAILABLE, r.getResult());
		assertEquals(note, r.getNote());
		assertEquals(SwarmQaProvider.NAME, r.getProvider());
		assertEquals(SwarmQaProvider.DETECTOR_MODEL, r.getModel());
		assertTrue(r.getChecks().isEmpty());
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* helpers                                                                                           */
	/* ------------------------------------------------------------------------------------------------ */

	static byte[] realMask() throws IOException {
		assertTrue("fixture " + REAL_MASK.getAbsolutePath(), REAL_MASK.isFile());
		return Files.readAllBytes(REAL_MASK.toPath());
	}

	/// The P8-2 two-actor layout: left 45% and right 45%, which hold the real mask's faces at ~(0.39,0.21) and ~(0.62,0.16).
	static RenderPlan twoActorPlan() {
		return plan(
			actor("a1", "Left", new RenderPlan.Box(0.05, 0.10, 0.45, 0.85), true, RenderPlan.ROLE_FOREGROUND, "red cloak"),
			actor("a2", "Right", new RenderPlan.Box(0.50, 0.05, 0.45, 0.90), true, RenderPlan.ROLE_FOREGROUND, null));
	}

	/// The same two plus a third on the right 28%, where the real mask has no face.
	static RenderPlan threeActorPlan() {
		return plan(
			actor("a1", "Left", new RenderPlan.Box(0.05, 0.10, 0.45, 0.85), true, RenderPlan.ROLE_FOREGROUND, "red cloak"),
			actor("a2", "Middle", new RenderPlan.Box(0.50, 0.05, 0.45, 0.90), true, RenderPlan.ROLE_FOREGROUND, null),
			actor("a3", "Right", new RenderPlan.Box(0.70, 0.10, 0.28, 0.85), true, RenderPlan.ROLE_FOREGROUND, null));
	}

	static RenderPlan plan(RenderPlan.Actor... actors) {
		RenderPlan.Builder b = RenderPlan.builder()
			.purpose("scene")
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 1, false, "CANONICAL"))
			.location(new RenderPlan.Location("loc-1", "a narrow wet alley"))
			.camera(new RenderPlan.Camera("medium wide shot"));
		for(RenderPlan.Actor a : actors) {
			b.actor(a);
		}
		return b.build();
	}

	static RenderPlan.Actor actor(String id, String name, RenderPlan.Box box, boolean referenced, String role, String outfitText) {
		RenderPlan.Actor.Builder b = new RenderPlan.Actor.Builder(id).characterId("char-" + id).name(name).role(role).box(box).referenced(referenced);
		if(outfitText != null) {
			b.outfit(new RenderPlan.Outfit(null, outfitText, null));
		}
		if(referenced) {
			b.referenceStage(RenderPlan.STAGE_BASE);
		}
		return b.build();
	}

	/// A backend that answers from canned values and records what the provider sent.
	static final class Scripted implements SwarmQaProvider.Backend {
		final String objectInfo;
		final SwarmGraphClient.RawResponse response;
		final byte[] mask;
		boolean probeThrows;
		boolean generateThrows;
		int probes;
		int generates;
		ComfyGraph lastGraph;
		GraphInputs lastInputs;
		String lastModel;
		int lastImages;
		String lastFetched;
		final List<String> fetched = new ArrayList<>();

		Scripted(String objectInfo, SwarmGraphClient.RawResponse response, byte[] mask) {
			this.objectInfo = objectInfo;
			this.response = response;
			this.mask = mask;
		}

		@Override
		public String objectInfoJson(String classType) {
			probes++;
			assertEquals(SwarmQaProvider.CLASS_YOLO, classType);
			if(probeThrows) {
				throw new IllegalStateException("scripted probe failure");
			}
			return objectInfo;
		}

		@Override
		public SwarmGraphClient.RawResponse generateRaw(ComfyGraph graph, GraphInputs inputs, String model, int images) {
			generates++;
			lastGraph = graph;
			lastInputs = inputs;
			lastModel = model;
			lastImages = images;
			if(generateThrows) {
				throw new IllegalStateException("scripted generate failure");
			}
			return response;
		}

		@Override
		public byte[] fetchImage(String relativePath) {
			lastFetched = relativePath;
			fetched.add(relativePath);
			return mask;
		}
	}
}
