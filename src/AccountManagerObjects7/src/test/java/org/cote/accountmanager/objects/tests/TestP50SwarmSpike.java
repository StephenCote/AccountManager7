package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Placement;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbShotTypeEnumType;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/// PictureBook V3 Phase 5, task P5-0: the Swarm contract spike (PictureBookV3Design.md §38.5,
/// PictureBookV3Implementation.md §13). Measures, against the live SwarmUI at test.swarm.server,
/// what the ACTOR_REGION / inpaint / segment / IP-Adapter / raw-workflow surfaces actually do with
/// the staged FLUX.2 composite fixtures, and writes every request, response, graph and PNG under
/// C:/tmp/p50-spike/. No product code is exercised beyond the request builders it reuses; no DB,
/// no user, no org. Gated: it runs only with PB_SD_TESTS=1 (or -Dpb.sd.tests=1) and skips visibly
/// otherwise. Run alone, after all other gates, with
///   cd src && PB_SD_TESTS=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestP50SwarmSpike -DskipTests=false test
/// Timings are recorded, never asserted. Assertions cover only the contract already verified in
/// §38.1 (base render, generated-workflow capture, raw re-post); the unknowns are recorded.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestP50SwarmSpike {
	public static final Logger logger = LogManager.getLogger(TestP50SwarmSpike.class);

	static final String SKIP = "SKIPPED: set PB_SD_TESTS=1 (or -Dpb.sd.tests=1) to run the Phase 5 Swarm spike. This is NOT a pass.";
	static final String MEDIA = "./media/flux/";
	static final int W = 1024;
	static final int H = 768;
	static final int REF_SIZE = 1024;
	static final int BASE_SEED = 424242;
	static final int SDXL_STEPS = 25;
	static final double SDXL_CFG = 5.0;

	static final String ITEM_BASE = "01-base";
	static final String ITEM_REGION = "02-region";
	static final String ITEM_INPAINT = "03-inpaint";
	static final String ITEM_SEGMENT = "04-segment";
	static final String ITEM_TIMINGS = P50Swarm.TIMINGS_ITEM;
	static final String ITEM_WS = "06-ws";

	/// Scene constants copied verbatim from TestFlux2Composite so the base render is the same scene
	/// the staged fixtures were made for (character1 = A, character2 = B, landscape1 = setting).
	static final String LEFT_DESC = "a lean man in his fifties with short grey hair and a grey beard, wearing a coral-pink jacket over a patterned shirt, rust-red cargo trousers and olive boots";
	static final String RIGHT_DESC = "a young woman with long wavy strawberry-blonde hair, wearing a green and white striped satin tunic over lime-green trousers and teal boots";
	static final String ACTION = "standing together on the wet pavement, talking";
	static final String SETTING = "a rain-soaked street beside a weathered clapboard house, overhead power lines, bare winter trees, overcast light, puddles reflecting the sky";
	static final String MOOD = "bleak and quiet, flat grey overcast daylight";

	/// Third and fourth actors, derived from the two roles media/AIME.pdf actually names: the
	/// abandoned single at "the other table" (the tear through heavy foundation) and the
	/// "solemn faced caretaker" across the rain-and-neon street. AIME names no one and describes no
	/// one's appearance, so the appearance below is invented for the spike and kept visually distinct
	/// from A and B. Recorded as a deviation in PictureBookV3Implementation.md §6.C 204.
	static final String C_DESC = "a woman in her forties with a dark chin-length bob, heavy tear-streaked foundation, wearing a red cocktail dress and holding a phone";
	static final String D_DESC = "a gaunt clean-shaven man in his sixties with slicked-back silver hair, wearing a black suit and white gloves";

	static final String CHANGE_TOKEN = "The first person wears a bright yellow scarf.";

	private static boolean initDone = false;
	private static Properties props;
	private static String server;
	private static String flux2;
	private static String sdxl;
	private static String negative;
	private static SDUtil sdu;
	private static P50Swarm sw;
	private static byte[] charA;
	private static byte[] charB;
	private static byte[] landscape;
	private static List<String> refsBase;
	private static String basePrompt;

	static boolean gated() {
		return "1".equals(System.getenv("PB_SD_TESTS")) || "1".equals(System.getProperty("pb.sd.tests"));
	}

	@Before
	public void gate() throws Exception {
		Assume.assumeTrue(SKIP, gated());
		if (!initDone) init();
	}

	private static synchronized void init() throws Exception {
		if (initDone) return;
		props = new Properties();
		InputStream fis = ClassLoader.getSystemResourceAsStream("./resource.properties");
		props.load(fis);
		fis.close();
		OlioModelNames.use();
		server = props.getProperty("test.swarm.server");
		flux2 = props.getProperty("test.swarm.flux2Model", "flux2Klein_9b");
		sdxl = props.getProperty("test.swarm.refinerModel", "juggernautXL_ragnarokBy");
		negative = props.getProperty("test.swarm.negativePrompt", "");
		SdTestGate.resolveInstalledCheckpoints(props);
		SdTestGate.requireSwarmReachable(server);
		sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		SdTestGate.requireModelInstalled(sdu, server, flux2, "P5-0 FLUX.2 renders");
		SdTestGate.requireModelInstalled(sdu, server, sdxl, "P5-0 SDXL renders");
		sw = new P50Swarm(server);
		charA = fixture("character1.png");
		charB = fixture("character2.png");
		landscape = fixture("landscape1.png");
		refsBase = SDUtil.buildFlux2References(REF_SIZE, charA, charB, landscape);
		assertEquals("three staged references", 3, refsBase.size());
		basePrompt = flux2BaseRequest(refsBase, BASE_SEED).getPrompt();
		sw.writeReadme(readme());
		logger.info("P5-0 spike against " + server + " flux2=" + flux2 + " sdxl=" + sdxl + " artefacts=" + P50Swarm.ROOT);
		initDone = true;
	}

	static byte[] fixture(String name) throws Exception {
		Path p = Paths.get(MEDIA + name);
		assertTrue("fixture missing: " + p.toAbsolutePath(), Files.exists(p));
		return Files.readAllBytes(p);
	}

	static String readme() {
		return String.join("\n",
			"P5-0 Phase 5 Swarm spike artefacts (PictureBookV3Design.md §38.5; PictureBookV3Implementation.md §13 / §4.11).",
			"Produced by TestP50SwarmSpike against " + server + " with checkpoints " + flux2 + " (FLUX.2) and " + sdxl + " (SDXL).",
			"",
			"Per render <label>:",
			"  <label>.request.json          what was POSTed to /API/GenerateText2Image, REDACTED: session_id replaced,",
			"                                every data:image base64 string replaced by its length, image_base64 values",
			"                                inside comfyworkflowraw elided. The bytes actually sent were not redacted.",
			"  <label>.response.json         Swarm's response, verbatim.",
			"  <label>.png                   the first returned image, fetched from <server>/<path>.",
			"  <label>.params.json           the PNG's embedded Swarm parameters (SWUtil.extractInfo).",
			"  <label>.error.txt             present only when the request failed or Swarm returned an error.",
			"  <label>.workflow.json         Swarm's own generated graph for the same parameters (/API/ComfyGetGeneratedWorkflow),",
			"                                pretty-printed, image_base64 elided; .workflow.response.json is verbatim.",
			"Items: 01-base (base render, generated graph, IP-Adapter probes, portraits C/D, raw FLUX.2 re-post),",
			"       02-region (<region:>, mask inpaint, 4-actor, <object:>), 03-inpaint (SDXL useinpaintingencode),",
			"       04-segment (<segment:> yolo/clipseg, FACEID probe), 05-timings (timings.txt CSV + system_stats),",
			"       06-ws (GenerateText2ImageWS frames.jsonl). placements.json in 02-region/03-inpaint holds the",
			"       PbLayoutUtil.defaultPlacement boxes the region/mask geometry came from.",
			"Timings are measurements on this GPU on this day; they are not thresholds.",
			"");
	}

	// ---- request shapes ------------------------------------------------------------------------

	/// Exactly the product's FLUX.2 scene request (SWUtil.newFlux2SceneTxt2Img + promptImages), as
	/// TestFlux2Composite.liveFlux2CompositeAgainstStagedFixtures sends it, with a fixed seed.
	static SWTxt2Img flux2BaseRequest(List<String> refs, int seed) {
		SWTxt2Img req = SWUtil.newFlux2SceneTxt2Img(LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, null, refs.size());
		req.setPromptImages(refs);
		req.setSeed(seed);
		req.setModel(flux2);
		req.setWidth(W);
		req.setHeight(H);
		return req;
	}

	static Map<String, Object> flux2Base(int seed) {
		return sw.toMap(flux2BaseRequest(refsBase, seed));
	}

	/// FLUX.2 request with an arbitrary prompt and reference list (portraits, 4-actor scenes).
	static Map<String, Object> flux2With(String prompt, List<String> refs, int seed, int w, int h) {
		SWTxt2Img req = flux2BaseRequest(refsBase, seed);
		req.setPrompt(prompt);
		req.setPromptImages(refs);
		req.setWidth(w);
		req.setHeight(h);
		Map<String, Object> m = sw.toMap(req);
		if (refs == null || refs.isEmpty()) m.remove("promptimages");
		return m;
	}

	/// SDXL request on the juggernaut checkpoint with the product's SDXL sampler defaults.
	static Map<String, Object> sdxlWith(String prompt, int seed) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("model", sdxl);
		m.put("prompt", prompt);
		m.put("negativeprompt", negative);
		m.put("steps", SDXL_STEPS);
		m.put("cfgscale", SDXL_CFG);
		m.put("sampler", "dpmpp_2m");
		m.put("scheduler", "Karras");
		m.put("seed", seed);
		m.put("width", W);
		m.put("height", H);
		m.put("images", 1);
		return m;
	}

	static String sdxlScenePrompt(String... people) {
		return "A photograph of " + String.join(" and ", people) + ", " + ACTION + ", in " + SETTING + ". " + MOOD + ".";
	}

	static String fourActorPrompt() {
		return "Combine the exact person and face from the first reference image, the exact person and face from the second reference image, "
			+ "the exact person and face from the third reference image and the exact person and face from the fourth reference image. "
			+ "Place all four people together in the environment shown in the fifth reference image (" + SETTING + "). "
			+ "The first person is " + LEFT_DESC + ". The second person is " + RIGHT_DESC + ". "
			+ "The third person is " + C_DESC + ". The fourth person is " + D_DESC + ". "
			+ "They are " + ACTION + ". The mood is " + MOOD + ".";
	}

	static String portraitPrompt(String desc) {
		return "A photorealistic head-and-shoulders portrait of " + desc + ", facing the camera, neutral grey studio background, soft even light, sharp focus on the face.";
	}

	// ---- geometry ------------------------------------------------------------------------------

	static Placement pA() { return PbLayoutUtil.defaultPlacement(0, 2, PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO); }
	static Placement pB() { return PbLayoutUtil.defaultPlacement(1, 2, PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.HERO); }

	static List<Placement> fourActor() {
		PbActorRoleEnumType[] roles = { PbActorRoleEnumType.HERO, PbActorRoleEnumType.HERO, PbActorRoleEnumType.SUPPORTING, PbActorRoleEnumType.SUPPORTING };
		List<Placement> out = new ArrayList<>();
		for (int i = 0; i < 4; i++) out.add(PbLayoutUtil.defaultPlacement(i, 4, PbShotTypeEnumType.MEDIUM, roles[i]));
		return out;
	}

	static void writePlacements(String item) {
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.add(P50Swarm.placementJson("A (0 of 2)", pA()));
		rows.add(P50Swarm.placementJson("B (1 of 2)", pB()));
		List<Placement> four = fourActor();
		String[] names = { "A (0 of 4, HERO)", "B (1 of 4, HERO)", "C (2 of 4, SUPPORTING)", "D (3 of 4, SUPPORTING)" };
		for (int i = 0; i < 4; i++) rows.add(P50Swarm.placementJson(names[i], four.get(i)));
		rows.add(P50Swarm.placementJson("prop (2 of 3, SUPPORTING)", propBox()));
		sw.save(item, "placements", "json", sw.pretty(rows).getBytes(StandardCharsets.UTF_8));
	}

	static Placement propBox() { return PbLayoutUtil.defaultPlacement(2, 3, PbShotTypeEnumType.MEDIUM, PbActorRoleEnumType.SUPPORTING); }

	// ---- bookkeeping ---------------------------------------------------------------------------

	/// -Dp50.only=<label>[,<label>…] re-measures only those renders into the existing artefact directory; every
	/// other render is logged as skipped and leaves its earlier artefacts untouched. Nothing is asserted either way.
	private static boolean selected(String label) {
		String only = System.getProperty("p50.only");
		if (only == null || only.isBlank()) return true;
		for (String s : only.split(",")) {
			if (s.trim().equals(label)) return true;
		}
		return false;
	}

	private P50Swarm.Result render(String item, String label, Map<String, Object> params, boolean captureGraph, String note) {
		if (!selected(label)) {
			logger.info(item + "/" + label + ": not in p50.only — not rendered this run");
			return null;
		}
		if (captureGraph) sw.generatedWorkflow(item, label, params);
		Map<String, Object> before = sw.systemStats(item, label + ".before");
		P50Swarm.Result r = sw.generate(item, label, params);
		Map<String, Object> after = sw.systemStats(item, label + ".after");
		sw.timing(item + "/" + label, params.get("seed"), params.get("steps"), params.get("width"), params.get("height"), r.ms,
			P50Swarm.vram(before, "vram_free"), P50Swarm.vram(after, "vram_free"), P50Swarm.vram(after, "vram_total"),
			(r.error != null ? "ERROR " + r.error : "ok") + (note != null ? " | " + note : ""));
		if (r.png != null) describeDims(item, label, r.png);
		return r;
	}

	private static void describeDims(String item, String label, byte[] png) {
		try {
			java.awt.image.BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
			if (img != null) {
				logger.info(item + "/" + label + ": PNG " + img.getWidth() + "x" + img.getHeight() + " (" + png.length + " bytes)");
			}
			else {
				logger.warn(item + "/" + label + ": ImageIO could not decode the returned bytes");
			}
		}
		catch (Exception e) {
			logger.warn(item + "/" + label + ": decode failed: " + e);
		}
	}

	private static void requireBase() {
		byte[] base = sw.load(ITEM_BASE, "base", "png");
		Assume.assumeTrue("SKIPPED: " + P50Swarm.ROOT + ITEM_BASE + "/base.png is missing — run t01 first. This is NOT a pass.", base != null);
	}

	private static String baseDataUri() {
		return P50Swarm.pngDataUri(sw.load(ITEM_BASE, "base", "png"));
	}

	private static List<String> fiveRefs() {
		byte[] c = sw.load(ITEM_BASE, "portrait_C", "png");
		byte[] d = sw.load(ITEM_BASE, "portrait_D", "png");
		Assume.assumeTrue("SKIPPED: portraits C/D missing under " + P50Swarm.ROOT + ITEM_BASE + " — run t01 first. This is NOT a pass.", c != null && d != null);
		return SDUtil.buildFlux2References(REF_SIZE, charA, charB, c, d, landscape);
	}

	// ---- t01: base, generated graph, IP-Adapter probes, portraits, raw re-post -----------------

	@Test(timeout = 75L * 60 * 1000)
	public void t01_base_graph_ipadapter_portraits_raw() throws Exception {
		Map<String, Object> base = flux2Base(BASE_SEED);
		Map<String, Object> graph = sw.generatedWorkflow(ITEM_BASE, "base", base);
		assertNotNull("ComfyGetGeneratedWorkflow must return a parsable graph for the product's FLUX.2 request (contract §38.1)", graph);

		P50Swarm.Result b = render(ITEM_BASE, "base", base, false, "product FLUX.2 scene request, 3 refs");
		Assume.assumeTrue("SKIPPED: t01 needs base, portrait_C and portrait_D in p50.only (or no filter). This is NOT a pass.",
			b != null && selected("portrait_C") && selected("portrait_D"));
		assertTrue("base FLUX.2 render failed: " + b.error, b.ok());
		java.awt.image.BufferedImage bimg = ImageIO.read(new ByteArrayInputStream(b.png));
		assertNotNull("base PNG decodes", bimg);
		assertEquals(W, bimg.getWidth());
		assertEquals(H, bimg.getHeight());

		Map<String, Object> ipaSdxl = sdxlWith(sdxlScenePrompt(LEFT_DESC, RIGHT_DESC), BASE_SEED);
		ipaSdxl.put("useipadapter", "PLUS FACE (portraits)");
		ipaSdxl.put("ipadapterweight", 0.8);
		ipaSdxl.put("promptimages", Arrays.asList(refsBase.get(0)));
		render(ITEM_BASE, "ipadapter_sdxl", ipaSdxl, true, "IP-Adapter PLUS FACE on SDXL, ref = character1");

		Map<String, Object> ipaFlux = flux2Base(BASE_SEED);
		ipaFlux.put("useipadapter", "PLUS FACE (portraits)");
		ipaFlux.put("ipadapterweight", 0.8);
		render(ITEM_BASE, "ipadapter_flux2", ipaFlux, true, "IP-Adapter PLUS FACE on FLUX.2 — refusal expected, recorded verbatim");

		P50Swarm.Result c = render(ITEM_BASE, "portrait_C", flux2With(portraitPrompt(C_DESC), null, 1001, REF_SIZE, REF_SIZE), false, "AIME other-table single");
		P50Swarm.Result d = render(ITEM_BASE, "portrait_D", flux2With(portraitPrompt(D_DESC), null, 1002, REF_SIZE, REF_SIZE), false, "AIME caretaker");
		assertTrue("portrait C failed: " + c.error, c.ok());
		assertTrue("portrait D failed: " + d.error, d.ok());

		rawRepost(graph);
	}

	/// Q7: re-post Swarm's own FLUX.2 graph through comfyworkflowraw with the seed, the positive text
	/// and the first reference image lifted out as ${comfyrawworkflowinput...} tags, plus a second
	/// save node with a string id. Measures: tag filling on a reference-latent graph, the image tag
	/// type, and how many images come back for two real save nodes.
	@SuppressWarnings("unchecked")
	private void rawRepost(Map<String, Object> graph) {
		Map<String, Object> g = (Map<String, Object>) P50Swarm.deepCopy(graph);
		StringBuilder edits = new StringBuilder();
		String samplerId = null;
		String saveId = null;
		String firstImageId = null;
		for (Map.Entry<String, Object> e : g.entrySet()) {
			Map<String, Object> node = (Map<String, Object>) e.getValue();
			String ct = String.valueOf(node.get("class_type"));
			Map<String, Object> in = (Map<String, Object>) node.get("inputs");
			if (in == null) continue;
			if (samplerId == null && (in.containsKey("noise_seed") || in.containsKey("seed")) && ct.toLowerCase().contains("sampler")) {
				samplerId = e.getKey();
				String key = in.containsKey("noise_seed") ? "noise_seed" : "seed";
				in.put(key, "${comfyrawworkflowinputseedv3seed}");
				edits.append(e.getKey()).append('.').append(key).append(" -> ${comfyrawworkflowinputseedv3seed}\n");
			}
			if (saveId == null && ct.equals("SwarmSaveImageWS")) saveId = e.getKey();
			if (firstImageId == null && ct.equals("SwarmLoadImageB64") && in.get("image_base64") instanceof String) {
				firstImageId = e.getKey();
				in.put("image_base64", "${comfyrawworkflowinputimageactorA}");
				edits.append(e.getKey()).append(".image_base64 -> ${comfyrawworkflowinputimageactorA}\n");
			}
		}
		String textId = samplerId != null ? findTextNode(g, (Map<String, Object>) g.get(samplerId), "positive", 0) : null;
		if (textId != null) {
			Map<String, Object> in = (Map<String, Object>) ((Map<String, Object>) g.get(textId)).get("inputs");
			String key = textKey(in);
			in.put(key, "${comfyrawworkflowinputtextscene}");
			edits.append(textId).append('.').append(key).append(" -> ${comfyrawworkflowinputtextscene}\n");
		}
		if (saveId != null) {
			Map<String, Object> save = (Map<String, Object>) g.get(saveId);
			Map<String, Object> extra = new LinkedHashMap<>();
			extra.put("class_type", "SwarmSaveImageWS");
			extra.put("inputs", new LinkedHashMap<>((Map<String, Object>) save.get("inputs")));
			g.put("v3_save_final", extra);
			edits.append("+ v3_save_final = SwarmSaveImageWS(").append(save.get("inputs")).append(")\n");
		}
		sw.note(ITEM_BASE, "raw_flux2.edits", "sampler=" + samplerId + " text=" + textId + " image=" + firstImageId + " save=" + saveId + "\n" + edits);
		assertNotNull("generated FLUX.2 graph has a sampler node with a seed input", samplerId);
		assertNotNull("generated FLUX.2 graph has a SwarmSaveImageWS node", saveId);

		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("model", flux2);
		extra.put("images", 1);
		extra.put("comfyrawworkflowinputseedv3seed", BASE_SEED);
		if (textId != null) extra.put("comfyrawworkflowinputtextscene", basePrompt + " " + CHANGE_TOKEN);
		if (firstImageId != null) extra.put("comfyrawworkflowinputimageactorA", refsBase.get(0));
		P50Swarm.Result r = sw.rawWorkflow(ITEM_BASE, "raw_flux2", sw.toJson(g), extra);
		int images = (r.json != null && r.json.get("images") instanceof List) ? ((List<?>) r.json.get("images")).size() : 0;
		sw.timing(ITEM_BASE + "/raw_flux2", BASE_SEED, null, W, H, r.ms, null, null, null, (r.error != null ? "ERROR " + r.error : "ok") + " | images=" + images + " textTag=" + (textId != null) + " imageTag=" + (firstImageId != null));
		if (r.png != null) describeDims(ITEM_BASE, "raw_flux2", r.png);
		assertTrue("raw comfyworkflowraw re-post of Swarm's own FLUX.2 graph failed (contract §38.1): " + r.error, r.ok());
	}

	@SuppressWarnings("unchecked")
	private static String findTextNode(Map<String, Object> g, Map<String, Object> node, String via, int depth) {
		if (node == null || depth > 12) return null;
		Map<String, Object> in = (Map<String, Object>) node.get("inputs");
		if (in == null) return null;
		Object link = in.get(via);
		if (!(link instanceof List) || ((List<?>) link).isEmpty()) return null;
		String id = String.valueOf(((List<?>) link).get(0));
		Map<String, Object> target = (Map<String, Object>) g.get(id);
		if (target == null) return null;
		Map<String, Object> tin = (Map<String, Object>) target.get("inputs");
		if (textKey(tin) != null) return id;
		for (String next : new String[] { "conditioning", "positive", "clip" }) {
			String found = findTextNode(g, target, next, depth + 1);
			if (found != null) return found;
		}
		return null;
	}

	/// CLIPTextEncode carries the prompt as `text`; Swarm's FLUX.2 SwarmTextEncodeAdvanced as `prompt`.
	private static String textKey(Map<String, Object> in) {
		if (in == null) return null;
		if (in.get("text") instanceof String) return "text";
		if (in.get("prompt") instanceof String) return "prompt";
		return null;
	}

	// ---- t02: region, mask inpaint, 4-actor, object ------------------------------------------

	@Test(timeout = 165L * 60 * 1000)
	public void t02_region_inpaint_fouractor_object() throws Exception {
		requireBase();
		writePlacements(ITEM_REGION);
		Placement a = pA();
		Placement b = pB();
		String regionSuffix = " " + P50Swarm.regionTag(a, 0.8) + " " + LEFT_DESC + " " + P50Swarm.regionTag(b, 0.8) + " " + RIGHT_DESC;
		String baseUri = baseDataUri();

		Map<String, Object> rf = flux2Base(BASE_SEED);
		rf.put("prompt", basePrompt + regionSuffix);
		rf.put("initimage", baseUri);
		rf.put("initimagecreativity", 0.6);
		rf.put("globalregionfactor", 0.5);
		render(ITEM_REGION, "region_flux2", rf, true, "<region:> A/B over base, creativity 0.6, globalregionfactor 0.5");

		Map<String, Object> rs = sdxlWith(sdxlScenePrompt(LEFT_DESC, RIGHT_DESC) + regionSuffix, BASE_SEED);
		rs.put("initimage", baseUri);
		rs.put("initimagecreativity", 0.6);
		rs.put("globalregionfactor", 0.5);
		render(ITEM_REGION, "region_sdxl", rs, true, "<region:> A/B over base on SDXL");

		String maskA = P50Swarm.rectMaskDataUri(Arrays.asList(a), W, H);
		Map<String, Object> mf = flux2Base(BASE_SEED);
		mf.put("prompt", basePrompt + " " + CHANGE_TOKEN);
		mf.put("initimage", baseUri);
		mf.put("maskimage", maskA);
		mf.put("initimagecreativity", 0.6);
		mf.put("maskblur", 8);
		mf.put("maskgrow", 16);
		mf.put("initimagerecompositemask", true);
		render(ITEM_REGION, "inpaint_A_flux2", mf, true, "mask = A box, yellow scarf, blur 8 grow 16 recomposite");

		Map<String, Object> ms = sdxlWith(sdxlScenePrompt(LEFT_DESC + " wearing a bright yellow scarf", RIGHT_DESC), BASE_SEED);
		ms.put("initimage", baseUri);
		ms.put("maskimage", maskA);
		ms.put("initimagecreativity", 0.6);
		ms.put("maskblur", 8);
		ms.put("maskgrow", 16);
		ms.put("initimagerecompositemask", true);
		render(ITEM_REGION, "inpaint_A_sdxl", ms, true, "mask = A box on SDXL");

		List<String> five = fiveRefs();
		List<Placement> four = fourActor();
		String[] descs = { LEFT_DESC, RIGHT_DESC, C_DESC, D_DESC };
		StringBuilder fourRegions = new StringBuilder();
		for (int i = 0; i < 4; i++) fourRegions.append(' ').append(P50Swarm.regionTag(four.get(i), 0.8)).append(' ').append(descs[i]);

		Map<String, Object> f4 = flux2With(fourActorPrompt() + fourRegions, five, BASE_SEED, W, H);
		render(ITEM_REGION, "4actor_region_flux2", f4, true, "fresh render, 5 refs, 4 <region:> boxes");

		String maskCD = P50Swarm.rectMaskDataUri(Arrays.asList(four.get(2), four.get(3)), W, H);
		Map<String, Object> f4i = flux2With(fourActorPrompt(), five, BASE_SEED, W, H);
		f4i.put("initimage", baseUri);
		f4i.put("maskimage", maskCD);
		f4i.put("initimagecreativity", 0.6);
		f4i.put("maskblur", 8);
		f4i.put("maskgrow", 16);
		f4i.put("initimagerecompositemask", true);
		render(ITEM_REGION, "4actor_inpaint_flux2", f4i, true, "base + mask over C/D boxes, 5 refs");

		Map<String, Object> s4 = sdxlWith(sdxlScenePrompt(LEFT_DESC, RIGHT_DESC, C_DESC, D_DESC) + fourRegions, BASE_SEED);
		render(ITEM_REGION, "4actor_region_sdxl", s4, false, "4 <region:> boxes on SDXL, no refs");

		Map<String, Object> op = flux2Base(BASE_SEED);
		op.put("prompt", basePrompt + " " + P50Swarm.objectTag(propBox(), 0.8, 0.5) + " a red umbrella leaning against the clapboard wall");
		render(ITEM_REGION, "object_prop_flux2", op, false, "<object:> prop in the 3rd-of-3 slot");

		Map<String, Object> oa = flux2Base(BASE_SEED);
		oa.put("prompt", basePrompt + " " + P50Swarm.objectTag(a, 0.8, 0.5) + " " + LEFT_DESC + " wearing a bright yellow scarf");
		render(ITEM_REGION, "object_actorA_flux2", oa, false, "<object:> on A's box");

		Map<String, Object> os = sdxlWith(sdxlScenePrompt(LEFT_DESC, RIGHT_DESC) + " " + P50Swarm.objectTag(a, 0.8, 0.5) + " " + LEFT_DESC + " wearing a bright yellow scarf", BASE_SEED);
		render(ITEM_REGION, "object_actorA_sdxl", os, false, "<object:> on A's box, SDXL");
	}

	// ---- t03: SDXL inpainting encode -------------------------------------------------------------

	@Test(timeout = 30L * 60 * 1000)
	public void t03_sdxl_inpainting_encode() throws Exception {
		requireBase();
		writePlacements(ITEM_INPAINT);
		String baseUri = baseDataUri();
		Placement[] boxes = { pA(), pB() };
		String[] labels = { "inpaintencode_A_to_C_sdxl", "inpaintencode_B_to_D_sdxl" };
		String[] prompts = { sdxlScenePrompt(C_DESC, RIGHT_DESC), sdxlScenePrompt(LEFT_DESC, D_DESC) };
		for (int i = 0; i < 2; i++) {
			Map<String, Object> m = sdxlWith(prompts[i], BASE_SEED);
			m.put("initimage", baseUri);
			m.put("maskimage", P50Swarm.rectMaskDataUri(Arrays.asList(boxes[i]), W, H));
			m.put("useinpaintingencode", true);
			m.put("initimagecreativity", 0.55);
			m.put("maskblur", 8);
			m.put("maskgrow", 16);
			m.put("initimagerecompositemask", true);
			render(ITEM_INPAINT, labels[i], m, i == 0, "SDXL useinpaintingencode, replace " + (i == 0 ? "A with C" : "B with D"));
		}
	}

	// ---- t04: segment + FACEID -----------------------------------------------------------------

	@Test(timeout = 60L * 60 * 1000)
	public void t04_segment_and_faceid() throws Exception {
		sw.objectInfo(ITEM_SEGMENT, "SwarmYoloDetection");
		sw.note(ITEM_SEGMENT, "nodes", "SwarmYoloDetection=" + sw.hasNode("SwarmYoloDetection")
			+ " SwarmClipSeg=" + sw.hasNode("SwarmClipSeg")
			+ " IPAdapterFaceID=" + sw.hasNode("IPAdapterFaceID")
			+ " IPAdapterUnifiedLoaderFaceID=" + sw.hasNode("IPAdapterUnifiedLoaderFaceID")
			+ " SwarmLoadImageB64=" + sw.hasNode("SwarmLoadImageB64"));

		Map<String, Object> y = flux2Base(BASE_SEED);
		y.put("prompt", basePrompt + " <segment:yolo-face_yolov8m.pt,0.6,0.5> detailed photorealistic face, sharp eyes, natural skin");
		render(ITEM_SEGMENT, "segment_yolo_flux2", y, true, "<segment:yolo-face_yolov8m.pt,0.6,0.5> on FLUX.2");

		Map<String, Object> c = flux2Base(BASE_SEED);
		c.put("prompt", basePrompt + " <segment:face,0.6,0.5> detailed photorealistic face, sharp eyes, natural skin");
		render(ITEM_SEGMENT, "segment_clipseg_flux2", c, true, "<segment:face,0.6,0.5> (CLIPSeg) on FLUX.2");

		Map<String, Object> ys = sdxlWith(sdxlScenePrompt(LEFT_DESC, RIGHT_DESC) + " <segment:yolo-face_yolov8m.pt,0.6,0.5> detailed photorealistic face, sharp eyes, natural skin", BASE_SEED);
		render(ITEM_SEGMENT, "segment_yolo_sdxl", ys, true, "<segment:yolo-...> on SDXL");

		Map<String, Object> f = sdxlWith(sdxlScenePrompt(LEFT_DESC, RIGHT_DESC), BASE_SEED);
		f.put("useipadapter", "FACEID PLUS V2");
		f.put("ipadapterweight", 0.8);
		f.put("promptimages", Arrays.asList(refsBase.get(0)));
		render(ITEM_SEGMENT, "faceid_sdxl", f, true, "FACEID PLUS V2 on SDXL — InsightFace refusal expected, recorded verbatim");
	}

	// ---- t05: timings --------------------------------------------------------------------------

	@Test(timeout = 90L * 60 * 1000)
	public void t05_timings() throws Exception {
		requireBase();
		sw.systemStats(ITEM_TIMINGS, "run.before");
		int[] seeds = { 7001, 7002, 7003 };
		for (int i = 0; i < seeds.length; i++) {
			render(ITEM_TIMINGS, "base_" + seeds[i], flux2Base(seeds[i]), false, i == 0 ? "BASE sample 1 (warm-up)" : "BASE");
		}
		String baseUri = baseDataUri();
		Placement a = pA();
		Placement b = pB();
		for (int i = 0; i < seeds.length; i++) {
			Map<String, Object> rf = flux2Base(seeds[i]);
			rf.put("prompt", basePrompt + " " + CHANGE_TOKEN + " " + P50Swarm.regionTag(a, 0.8) + " " + LEFT_DESC + " wearing a bright yellow scarf " + P50Swarm.regionTag(b, 0.8) + " " + RIGHT_DESC);
			rf.put("initimage", baseUri);
			rf.put("initimagecreativity", 0.6);
			rf.put("globalregionfactor", 0.5);
			render(ITEM_TIMINGS, "actor_region_" + seeds[i], rf, false, i == 0 ? "ACTOR_REGION sample 1 (warm-up)" : "ACTOR_REGION");
		}
		sw.systemStats(ITEM_TIMINGS, "run.after");
	}

	// ---- t06: websocket (optional) --------------------------------------------------------------

	@Test(timeout = 30L * 60 * 1000)
	public void t06_websocket_progress() throws Exception {
		Map<String, Object> params = flux2Base(7777);
		params.put("session_id", sw.session());
		params.put("images", 1);
		Path frames = sw.dir(ITEM_WS).resolve("frames.jsonl");
		Files.deleteIfExists(frames);
		StringBuilder frameLog = new StringBuilder();
		AtomicLong lastFrame = new AtomicLong(System.currentTimeMillis());
		CountDownLatch closed = new CountDownLatch(1);
		StringBuilder partial = new StringBuilder();
		List<String> kinds = new ArrayList<>();
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override public void onOpen(WebSocket ws) { ws.request(1); }
			@Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
				partial.append(data);
				if (last) {
					String frame = partial.toString();
					partial.setLength(0);
					lastFrame.set(System.currentTimeMillis());
					Map<String, Object> f = sw.parseObject(frame);
					String kind = f == null ? "?" : String.join("+", f.keySet());
					kinds.add(kind);
					synchronized (frameLog) {
						frameLog.append(System.currentTimeMillis()).append(' ').append(kind).append(' ').append(f != null ? sw.toJson(P50Swarm.redact(f)) : frame).append('\n');
					}
					if (f != null && ("close".equals(String.valueOf(f.get("socket_intention"))) || f.get("error") != null)) {
						closed.countDown();
					}
				}
				ws.request(1);
				return null;
			}
			@Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
				synchronized (frameLog) { frameLog.append(System.currentTimeMillis()).append(" CLOSE ").append(code).append(' ').append(reason).append('\n'); }
				closed.countDown();
				return null;
			}
			@Override public void onError(WebSocket ws, Throwable error) {
				synchronized (frameLog) { frameLog.append(System.currentTimeMillis()).append(" ERROR ").append(error).append('\n'); }
				closed.countDown();
			}
		};
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
		WebSocket ws;
		try {
			ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(30))
				.buildAsync(URI.create(sw.wsUrl("/API/GenerateText2ImageWS")), listener).get(60, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			sw.note(ITEM_WS, "handshake", "WebSocket handshake to " + sw.wsUrl("/API/GenerateText2ImageWS") + " failed: " + e);
			Assume.assumeTrue("SKIPPED: WebSocket handshake failed (" + e + ") — recorded under " + ITEM_WS + "; not a spike failure. This is NOT a pass.", false);
			return;
		}
		long t0 = System.currentTimeMillis();
		CompletableFuture<WebSocket> sent = ws.sendText(sw.toJson(params), true);
		sent.get(5, TimeUnit.MINUTES);
		while (closed.getCount() > 0) {
			if (closed.await(5, TimeUnit.SECONDS)) break;
			if (System.currentTimeMillis() - lastFrame.get() > 10L * 60 * 1000) {
				synchronized (frameLog) { frameLog.append(System.currentTimeMillis()).append(" NO FRAME FOR 10 MIN\n"); }
				break;
			}
		}
		long ms = System.currentTimeMillis() - t0;
		try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS); } catch (Exception ignore) { }
		synchronized (frameLog) { Files.write(frames, frameLog.toString().getBytes(StandardCharsets.UTF_8)); }
		sw.timing(ITEM_WS + "/ws_7777", 7777, params.get("steps"), W, H, ms, null, null, null, "frames=" + kinds.size() + " kinds=" + String.join(";", kinds));
		sw.note(ITEM_WS, "summary", "frames=" + kinds.size() + " ms=" + ms + "\n" + String.join("\n", kinds));
		assertTrue("no frame at all arrived on /API/GenerateText2ImageWS within the no-progress window (" + kinds.size() + " frames)", !kinds.isEmpty());
	}
}
