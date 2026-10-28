package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.ComfyGraph;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.GraphProblem;
import org.cote.accountmanager.olio.illustration.ImageDims;
import org.cote.accountmanager.olio.illustration.MaskImage;
import org.cote.accountmanager.olio.illustration.NodeCatalogue;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.illustration.SwarmRendererAdapter;
import org.cote.accountmanager.olio.illustration.WorkflowCompiler;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * P5-1 (PictureBook V3 design §38) against the live Swarm node ({@code test.swarm.server}) and
 * {@code am7db}: the generate-and-patch compiler's graphs actually render through
 * {@code POST /API/GenerateText2Image} + {@code comfyworkflowraw}, the adapter's graph path returns
 * provenance the artifact layer round-trips, and the two FLUX.2 inpaint shapes Stephen asked to
 * "measure both" on (2026-10-24: more steps vs creativity 1.0) are measured side by side.
 * <p>
 * <b>Gated</b> - set {@code PB_SD_TESTS=1} (env) or {@code -Dpb.sd.tests=1}. Every skip says so and
 * is <b>not</b> a pass. No LLM is involved anywhere in this class: it is pure SD on
 * {@code 192.168.1.39}. Timings are logged, never asserted - the per-method timeout ({@link #TIMEOUT_MS},
 * 180 minutes - twice Stephen's 90-minute spike ceiling, for t01's three-reference FLUX.2 render plus
 * the four inpaint variants in one method) is a hang guard, not a threshold.
 * <ol>
 * <li>{@link #t00_catalogueAndDriftProbe}: {@code object_info} + {@code swarmVersion} captured; the
 *     four compiled graph shapes (FLUX.2 / SDXL x BASE / ACTOR_REGION) validate clean against the
 *     live catalogue; node-class drift against the P5-0 fixtures is logged, not asserted;
 *     {@code target/p51/object_info.subset.json} is written for hand-copy into the p50 fixture dir.</li>
 * <li>{@link #t01_baseFlux2WithRealCast}: book 58's three real portraits (Duña de Rosa, Jideon de Rosa,
 *     Touvier) read as their non-admin owner through PBAC, a 3-reference FLUX.2 BASE over the book's
 *     actual scene text, then the full artifact round-trip (plan, renderGraph, base image,
 *     recordImageMetrics with backendGraph) on a throwaway book owned by {@code pbPlanOwner}.</li>
 * <li>{@link #t02_measureBothFlux2Inpaint}: V1 4/0.6 (control), V2 4/1.0, V3 8/0.6, V4 12/0.6
 *     (V5 8/1.0 behind {@code -Dpb.p51.v5=1}) over t01's base, Touvier's portrait as the only
 *     reference, box A over Touvier; pixels outside the grown+blurred mask must be untouched.</li>
 * <li>{@link #t03_inpaintSdxlControl}: the SDXL inpaint shape (25 / 0.6 -> start 10) over an SDXL BASE.</li>
 * <li>{@link #t04_rejectionPaths}: an uninstalled checkpoint the catalogue cannot see is
 *     {@code RENDERER_REJECTED} and drops the cached catalogue; an unknown node class is
 *     {@code GRAPH_INVALID} and never leaves the JVM.</li>
 * </ol>
 * Run: {@code PB_SD_TESTS=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestP51GraphLive -DskipTests=false test}
 * (one surefire JVM against am7db at a time). Every PNG under {@code target/p51/} is to be opened
 * and described in the report.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestP51GraphLive extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestP51GraphLive.class);

	static final String SKIP = "P5-1 live graph tests are gated: set PB_SD_TESTS=1 (env) or -Dpb.sd.tests=1 to run them. This is NOT a pass.";
	static final long TIMEOUT_MS = 180L * 60L * 1000L;
	static final File OUT = new File("target/p51");
	static final String FIXTURE_DIR = "/olio/illustration/p50/";

	static final int W = 1024;
	static final int H = 768;
	static final int BASE_SEED = 424242;
	static final String FLUX2_NEGATIVE = "blurry faces, deformed, extra people, mismatched lighting, low quality";

	/// The P5-0 spike's prompt tail, verbatim (design §38.5): what keeps FLUX.2 from drawing the references.
	static final String SPIKE_TAIL = " Preserve facial identity, hair, and clothing precisely. Matching lighting, scale, and"
		+ " perspective across the whole image. No extra people. Do not draw the reference images themselves - no poster,"
		+ " screen, mirror, billboard, framed picture or character sheet anywhere in the scene. A single continuous scene,"
		+ " no panels, no split screen, no collage.";

	/// Book 58 (slug catatone-custom-book-6), owned by pbCustomTestUser in the custom test org: the only
	/// V3-era book with real portraits on 2026-10-24. Read through PBAC as that owner, never as admin.
	static final String CUSTOM_ORG_PATH = "/Development/PictureBook Custom Tests";
	static final String CUSTOM_OWNER = "pbCustomTestUser";
	static final String CUSTOM_BOOK_SLUG = "catatone-custom-book-6";
	static final String[] CAST_ORDER = { "Du" + (char) 0xF1 + "a de Rosa", "Jideon de Rosa", "Touvier" };
	/// Reference names are Comfy tag names ([A-Za-z0-9_]+), so the display names cannot be used as-is.
	static final String[] CAST_TAGS = { "duna_de_rosa", "jideon_de_rosa", "touvier" };
	static final String ACTOR_A = "Touvier";
	/// Right 40% of the frame, where the layout sentence puts Touvier. Override with -Dpb.p51.boxA=x,y,w,h.
	static final RenderPlan.Box DEFAULT_BOX_A = new RenderPlan.Box(0.55, 0.15, 0.40, 0.80);

	/// Book 58's scene 0 text as read on 2026-10-24; the live read wins when it answers.
	static final String FALLBACK_SETTING = "A narrow, wet alley with a cement wall on one side and an open sky above. The street is slick from recent rain.";
	static final String FALLBACK_ACTION = "Touvier crouches to pick up neuro chips while Jideon spills more onto the pavement, trying to force him into revealing information about his source.";
	static final String FALLBACK_MOOD = "Damp with cold rain; tension thickens as both men exchange accusations and threats";

	/// Carried from t01 to t02 inside one JVM; t02 falls back to the PNG on disk when run alone.
	static byte[] t01Png;
	static String t01Prompt;
	static Cast t01Cast;

	String server;
	String flux2Model;
	String sdxlModel;
	String sdxlNegative;
	SDUtil sdu;
	SwarmGraphClient client;
	SwarmRendererAdapter adapter;

	static boolean gated() {
		return "1".equals(System.getenv("PB_SD_TESTS")) || "1".equals(System.getProperty("pb.sd.tests"));
	}

	@Before
	public void liveSetup() {
		Assume.assumeTrue(SKIP, gated());
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		SdTestGate.resolveInstalledCheckpoints(testProperties);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SdTestGate.requireSwarmReachable(server);
		SwarmModelInventory.invalidateAll();
		flux2Model = prop("test.swarm.flux2Model", "flux2Klein_9b");
		sdxlModel = testProperties.getProperty(SdTestGate.PROP_SWARM_MODEL);
		sdxlNegative = prop("test.swarm.negativePrompt", FLUX2_NEGATIVE);
		sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		SdTestGate.requireModelInstalled(sdu, server, flux2Model, "the FLUX.2 BASE / ACTOR_REGION graphs");
		client = new SwarmGraphClient(sdu);
		adapter = new SwarmRendererAdapter(sdu);
		OUT.mkdirs();
		logger.info("[p51] LLM tier: none - this class is pure SD against " + server + " (Swarm " + client.swarmVersion() + ")");
	}

	// ─────────────────────────────── t00 ───────────────────────────────

	@Test(timeout = TIMEOUT_MS)
	public void t00_catalogueAndDriftProbe() throws Exception {
		String version = client.swarmVersion();
		logger.info("[p51][t00] swarmVersion=" + version + " (P5-0 spike measured under 0.9.8.4.GIT-5dc94afa)");

		AtomicInteger fetches = new AtomicInteger();
		NodeCatalogue catalogue = NodeCatalogue.forServer(server, () -> { fetches.incrementAndGet(); return client.objectInfoJson(); });
		assertNotNull("object_info did not parse from " + server, catalogue);
		assertTrue("catalogue should be live, not a stale cache entry (fetches=" + fetches.get() + ")", catalogue.size() > 0);
		logger.info("[p51][t00] catalogue types=" + catalogue.size() + " fetchedAt=" + catalogue.getFetchedAt() + " fetches=" + fetches.get());

		Map<String, ComfyGraph> fixtures = new LinkedHashMap<>();
		fixtures.put("base_flux2", fixtureGraph("base_flux2.workflow.json"));
		fixtures.put("inpaint_A_flux2", fixtureGraph("inpaint_A_flux2.workflow.json"));
		fixtures.put("inpaint_A_sdxl", fixtureGraph("inpaint_A_sdxl.workflow.json"));

		WorkflowCompiler wc = new WorkflowCompiler();
		List<WorkflowCompiler.Reference> greys = greyReferences(3);
		byte[] greyBase = MaskImage.solid(W, H, Color.GRAY);
		RendererCapabilities.InpaintParams fluxInpaint = inpaintOf(flux2Model);
		RendererCapabilities.InpaintParams sdxlInpaint = inpaintOf(sdxlModel);

		/// Three grey references on the inpaint probe too, so the capture has the fixture's loader shape
		/// (mask + init + three references) and can replace inpaint_A_flux2.workflow.json one for one.
		RecordingGraphClient recorder = new RecordingGraphClient(sdu);
		Map<String, ComfyGraph> live = new LinkedHashMap<>();
		live.put("base_flux2", wc.compileBase(flux2Request("probe", FLUX2_NEGATIVE, 4), greys, recorder).getGraph());
		live.put("inpaint_A_flux2", wc.compileActorRegion(flux2Request("probe", FLUX2_NEGATIVE, fluxInpaint.getSteps()),
			greys, target(greyBase, DEFAULT_BOX_A), fluxInpaint, recorder).getGraph());
		live.put("base_sdxl", wc.compileBase(sdxlRequest("probe", 25), Collections.emptyList(), recorder).getGraph());
		live.put("inpaint_A_sdxl", wc.compileActorRegion(sdxlRequest("probe", sdxlInpaint.getSteps()), Collections.emptyList(),
			target(greyBase, DEFAULT_BOX_A), sdxlInpaint, recorder).getGraph());
		assertEquals("one generator exchange per live compile", live.size(), recorder.answers.size());

		TreeSet<String> union = new TreeSet<>();
		for(Map.Entry<String, ComfyGraph> e : live.entrySet()) {
			ComfyGraph g = e.getValue();
			union.addAll(classTypes(g));
			List<GraphProblem> problems = catalogue.validate(g, Collections.emptySet());
			List<GraphProblem> errors = NodeCatalogue.errors(problems);
			List<GraphProblem> structural = new ArrayList<>();
			for(GraphProblem p : errors) {
				if(!GraphProblem.UNFILLED_TAG.equals(p.getCode())) structural.add(p);
			}
			logger.info("[p51][t00] live " + e.getKey() + ": nodes=" + g.size() + " hash=" + g.graphHash()
				+ " classes=" + multiset(g) + " problems=" + NodeCatalogue.describe(problems));
			assertTrue("live " + e.getKey() + " must validate against the live catalogue (tags excluded): "
				+ NodeCatalogue.describe(structural), structural.isEmpty());
		}
		for(Map.Entry<String, ComfyGraph> e : fixtures.entrySet()) {
			union.addAll(classTypes(e.getValue()));
			ComfyGraph liveGraph = live.get(e.getKey());
			Map<String, Integer> a = multiset(e.getValue());
			Map<String, Integer> b = multiset(liveGraph);
			if(a.equals(b)) {
				logger.info("[p51][t00] drift " + e.getKey() + ": none - node-class multiset identical to the P5-0 fixture");
			}
			else {
				logger.warn("[p51][t00] drift " + e.getKey() + ": fixture=" + a + " live=" + b
					+ " (Swarm " + version + "; the fixture-backed compiler tests still describe the spike's generator)");
			}
		}

		NodeCatalogue subset = catalogue.subset(union);
		assertEquals("every class type the compiled graphs use must be in the live object_info", union.size(), subset.size());
		File out = new File(OUT, "object_info.subset.json");
		Files.write(out.toPath(), subset.toJson().getBytes(StandardCharsets.UTF_8));
		logger.info("[p51][t00] wrote " + out.getPath() + " (" + subset.size() + " types: " + union + ")"
			+ " - copy by hand (LF) to src/test/resources" + FIXTURE_DIR + "object_info.subset.json after review");

		/// T12: the generator's answer and the params that produced it, redacted the way P50Swarm wrote the
		/// spike fixtures, so a drifted fixture can be replaced one for one after review.
		int i = 0;
		for(String label : live.keySet()) {
			File wf = new File(OUT, label + ".workflow.json");
			File rq = new File(OUT, label + ".workflow.request.json");
			Files.write(wf.toPath(), redactGraph(recorder.answers.get(i)).toPrettyJson().getBytes(StandardCharsets.UTF_8));
			Files.write(rq.toPath(), JSONUtil.exportObject(redactRequest(recorder.requests.get(i))).getBytes(StandardCharsets.UTF_8));
			logger.info("[p51][t00] wrote " + wf.getPath() + " (" + recorder.answers.get(i).size() + " nodes) and " + rq.getPath()
				+ " - copy by hand (LF) to src/test/resources" + FIXTURE_DIR + " after review");
			i++;
		}
	}

	/// Records each raw generator answer (before the compiler patches it) and the params that produced it.
	static final class RecordingGraphClient extends SwarmGraphClient {
		final List<Map<String, Object>> requests = new ArrayList<>();
		final List<ComfyGraph> answers = new ArrayList<>();

		RecordingGraphClient(SDUtil sdu) {
			super(sdu);
		}

		@Override
		public ComfyGraph generatedWorkflow(Map<String, Object> params) {
			ComfyGraph g = super.generatedWorkflow(params);
			requests.add(new LinkedHashMap<>(params));
			answers.add(g.copy());
			return g;
		}
	}

	/// Every image_base64 becomes "<N chars base64>" whatever its length: the probes' grey PNGs are small, and the
	/// fixture-backed tests substitute their own images anyway (FixtureGraphClient).
	static ComfyGraph redactGraph(ComfyGraph g) {
		ComfyGraph c = g.copy();
		for(String id : c.nodeIds()) {
			Map<String, Object> inputs = c.inputs(id);
			Object b64 = inputs.get(ComfyGraph.INPUT_IMAGE_BASE64);
			if(b64 instanceof String) {
				inputs.put(ComfyGraph.INPUT_IMAGE_BASE64, "<" + ((String) b64).length() + " chars base64>");
			}
		}
		return c;
	}

	/// session_id is added by the client after the recording point, so it is written as the redacted marker directly.
	static Map<String, Object> redactRequest(Map<String, Object> params) {
		Map<String, Object> out = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : params.entrySet()) {
			out.put(e.getKey(), redactValue(e.getValue()));
		}
		out.put("session_id", "<redacted>");
		return out;
	}

	static Object redactValue(Object v) {
		if(v instanceof String && ((String) v).startsWith("data:")) {
			return "<" + ((String) v).length() + " chars data-uri>";
		}
		if(v instanceof List) {
			List<Object> out = new ArrayList<>();
			for(Object o : (List<?>) v) out.add(redactValue(o));
			return out;
		}
		return v;
	}

	// ─────────────────────────────── t01 ───────────────────────────────

	@Test(timeout = TIMEOUT_MS)
	public void t01_baseFlux2WithRealCast() throws Exception {
		Cast cast = readCast();
		String prompt = basePrompt(cast);
		logger.info("[p51][t01] cast=" + cast.describe() + "\n[p51][t01] prompt=" + prompt);

		StageRequest req = flux2Request(prompt, FLUX2_NEGATIVE, 4);
		WorkflowCompiler.CompiledGraph compiled = new WorkflowCompiler().compileBase(req, cast.refs, client);
		ComfyGraph graph = compiled.getGraph();
		assertEquals(RenderPlan.STAGE_BASE, compiled.getStage());
		assertEquals(BASE_SEED, compiled.getSeed());
		logger.info("[p51][t01] graph nodes=" + graph.size() + " hash=" + graph.graphHash() + " inputs=" + compiled.getInputs().names());

		String vramBefore = vram(client.systemStats());
		StageResult result = adapter.execute(req.toBuilder().graph(graph).inputs(compiled.getInputs()).build(), CancelToken.NONE);
		String vramAfter = vram(client.systemStats());
		logger.info("[p51][t01] BASE flux2 wall=" + result.getDurationMs() + " ms error=" + result.getError()
			+ " vramBefore=" + vramBefore + " vramAfter=" + vramAfter);
		assertTrue("BASE render failed: " + result.getError(), result.isOk());

		StageImage img = result.getImages().get(0);
		File png = new File(OUT, "t01_base_flux2.png");
		Files.write(png.toPath(), img.getBytes());
		int[] dims = ImageDims.decode(img.getBytes());
		logger.info("[p51][t01] wrote " + png.getPath() + " " + dims[0] + "x" + dims[1] + " seed=" + img.getSeed()
			+ " reported=" + img.isSeedReported() + " bytes=" + img.getLength());
		assertEquals(W, dims[0]);
		assertEquals(H, dims[1]);
		assertEquals(BASE_SEED, img.getSeed());

		Map<String, Object> backend = JSONUtil.getMap(result.getBackendGraph().getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(graph.graphHash(), backend.get("graphHash"));
		assertEquals(ComfyGraph.SAVE_FINAL, backend.get("saveNodeId"));
		assertEquals(RenderPlan.STAGE_BASE, backend.get("stage"));
		assertFalse("requestJson must carry no image bytes", result.getRequestJson().contains("data:image"));
		/// Bytes-free, not structurally clean: the redacted inputs keep the image tag keys, which the
		/// pipeline sanitiser strips before persisting (design §38.3; TestGraphRequestShape).
		assertFalse("requestJson keeps the image tag keys until sanitised: " + result.getRequestJson(), PbArtifactUtil.isSanitized(result.getRequestJson()));
		String persisted = PbArtifactUtil.sanitizeGeneratorRequest(result.getRequestJson(), cast.artifactIds());
		assertTrue("sanitised requestJson must be clean: " + persisted, PbArtifactUtil.isSanitized(persisted));
		Map<String, Object> reqMap = JSONUtil.getMap(result.getRequestJson().getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(cast.artifactIds(), Optional.ofNullable((List<?>) reqMap.get("referenceArtifactIds")).orElse(List.of()));
		Map<String, Object> persistedMap = JSONUtil.getMap(persisted.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(graph.graphHash(), persistedMap.get("graphHash"));
		assertEquals(cast.artifactIds(), Optional.ofNullable((List<?>) persistedMap.get("referenceArtifactIds")).orElse(List.of()));

		t01Png = img.getBytes();
		t01Prompt = prompt;
		t01Cast = cast;

		Fixture f = fixture("p51base");
		try {
			String artifactPath = PbBookUtil.artifactGroupPath(f.slug);
			RenderPlan plan = PbRenderPlanUtil.buildScenePlan(f.owner, f.book, f.scene, PbRenderPlanUtil.PlanOptions.NONE, SDAPIEnumType.SWARM, server);
			assertNotNull(plan);
			BaseRecord planArt = PbRenderPlanUtil.persistPlanArtifact(f.owner, f.planNode, artifactPath, plan);
			assertNotNull(planArt);

			BaseRecord graphArt = PbRenderPlanUtil.persistGraphArtifact(f.owner, f.renderNode, artifactPath, RenderPlan.STAGE_BASE, graph, client.swarmVersion());
			assertNotNull(graphArt);
			String graphOid = graphArt.get(FieldNames.FIELD_OBJECT_ID);

			BaseRecord data = persistPng(f.owner, artifactPath, "t01 base " + f.slug + ".png", img.getBytes());
			/// What recordImage does (sanitise, then persist); recordImage itself needs a SceneGraph this fixture has none of.
			BaseRecord baseArt = PbArtifactUtil.persistArtifact(f.owner, f.renderNode, PbPipelineUtil.ROLE_BASE,
				PbArtifactTypeEnumType.IMAGE, artifactPath, data, null, null, persisted);
			assertNotNull(baseArt);
			assertTrue("recordImageMetrics must report the write", PbArtifactUtil.recordImageMetrics(f.owner, baseArt, img.getBytes(),
				"image/png", dims[0], dims[1], Long.valueOf(img.getSeed()), SDAPIEnumType.SWARM, result.getDurationMs(), result.getBackendGraph()));

			long orgId = orgOf(f.owner);
			BaseRecord reread = PbArtifactUtil.readArtifact(f.owner, baseArt.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull(reread);
			String storedBackend = reread.get(OlioFieldNames.FIELD_PB_BACKEND_GRAPH);
			assertEquals("backendGraph must round-trip through the artifact", result.getBackendGraph(), storedBackend);
			Long storedSeed = reread.get(OlioFieldNames.FIELD_PB_SEED);
			assertEquals(Long.valueOf(BASE_SEED), storedSeed);
			String storedReq = reread.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
			assertNotNull(storedReq);
			assertTrue(PbArtifactUtil.isSanitized(storedReq));
			assertFalse(storedReq.contains("data:image"));

			BaseRecord rereadGraph = PbArtifactUtil.readArtifact(f.owner, graphOid, orgId);
			assertNotNull(rereadGraph);
			assertEquals(PbPipelineUtil.ROLE_RENDER_GRAPH, rereadGraph.get(OlioFieldNames.FIELD_PB_ROLE));
			ComfyGraph stored = PbRenderPlanUtil.readGraphArtifact(rereadGraph);
			assertEquals("renderGraph artifact must parse back to the same hash the backendGraph names", graph.graphHash(), stored.graphHash());
			Map<String, Object> env = PbRenderPlanUtil.readGraphEnvelope(rereadGraph);
			assertEquals(graph.graphHash(), env.get(PbRenderPlanUtil.GRAPH_KEY_HASH));
			logger.info("[p51][t01] artifact round-trip ok: base=" + baseArt.get(FieldNames.FIELD_OBJECT_ID) + " graph=" + graphOid
				+ " plan=" + planArt.get(FieldNames.FIELD_OBJECT_ID) + " on throwaway book " + f.slug);
		}
		finally {
			cleanup(f);
		}
	}

	// ─────────────────────────────── t02 ───────────────────────────────

	@Test(timeout = TIMEOUT_MS)
	public void t02_measureBothFlux2Inpaint() throws Exception {
		byte[] base = t01Png;
		if(base == null) {
			File png = new File(OUT, "t01_base_flux2.png");
			if(!png.isFile()) {
				fail("t02 needs t01's base image: run t01_baseFlux2WithRealCast first (none in this JVM and no " + png.getPath() + ")");
			}
			base = Files.readAllBytes(png.toPath());
			logger.info("[p51][t02] reusing " + png.getPath() + " from a previous run");
		}
		int[] dims = ImageDims.decode(base);
		assertEquals(W, dims[0]);
		assertEquals(H, dims[1]);
		Cast cast = (t01Cast != null ? t01Cast : readCast());
		WorkflowCompiler.Reference actorRef = cast.ref(ACTOR_A);
		RenderPlan.Box boxA = boxA();
		int[] boxPx = MaskImage.pixels(boxA, W, H);
		RendererCapabilities.InpaintParams shipped = inpaintOf(flux2Model);
		String prompt = inpaintPrompt(cast);
		logger.info("[p51][t02] boxA=" + boxA + " px=" + Arrays.toString(boxPx) + " shipped flux2 inpaint=" + shipped.getSteps() + "/" + shipped.getCreativity()
			+ " grow=" + shipped.getMaskGrow() + " blur=" + shipped.getMaskBlur() + "\n[p51][t02] prompt=" + prompt);

		List<int[]> variants = new ArrayList<>();
		variants.add(new int[] { 1, 4, 60 });
		variants.add(new int[] { 2, 4, 100 });
		variants.add(new int[] { 3, 8, 60 });
		variants.add(new int[] { 4, 12, 60 });
		if("1".equals(System.getProperty("pb.p51.v5"))) {
			variants.add(new int[] { 5, 8, 100 });
		}
		int margin = shipped.getMaskGrow() + shipped.getMaskBlur();
		BufferedImage baseImg = MaskImage.decode(base);
		StringBuilder table = new StringBuilder();
		table.append("# P5-1 t02 - FLUX.2 ACTOR_REGION measure-both (").append(flux2Model).append(", Swarm ").append(client.swarmVersion())
			.append(", ").append(W).append("x").append(H).append(", seed ").append(BASE_SEED).append(", box A ").append(Arrays.toString(boxPx))
			.append(" px, grow ").append(shipped.getMaskGrow()).append(" / blur ").append(shipped.getMaskBlur()).append(")\n\n")
			.append("Timings are measurements, never thresholds. \"untouched margin\" is the smallest margin (px, step 8) beyond the box at which\n")
			.append("every pixel equals the base; the assertion is margin <= grow + blur = ").append(margin).append(".\n\n")
			.append("| variant | steps | creativity | start_at_step | wall ms | untouched margin px | max diff @0 / @").append(margin).append(" / @256 | graphHash | VRAM before -> after | png |\n")
			.append("|---|---|---|---|---|---|---|---|---|---|\n");
		List<String> findings = new ArrayList<>();
		WorkflowCompiler wc = new WorkflowCompiler();
		for(int[] v : variants) {
			int n = v[0];
			int steps = v[1];
			double creativity = v[2] / 100.0;
			RendererCapabilities.InpaintParams ip = new RendererCapabilities.InpaintParams(steps, creativity, shipped.getMaskGrow(), shipped.getMaskBlur());
			StageRequest req = flux2Request(prompt, FLUX2_NEGATIVE, steps).toBuilder().stage(RenderPlan.STAGE_ACTOR_REGION).build();
			WorkflowCompiler.CompiledGraph compiled = wc.compileActorRegion(req, List.of(actorRef), target(base, boxA), ip, client);
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, compiled.getStage());
			assertEquals(steps, compiled.getSteps());
			int predictedStart = WorkflowCompiler.startAtStep(steps, creativity);
			String vramBefore = vram(client.systemStats());
			StageResult result = adapter.execute(req.toBuilder().graph(compiled.getGraph()).inputs(compiled.getInputs()).build(), CancelToken.NONE);
			String vramAfter = vram(client.systemStats());
			logger.info("[p51][t02] V" + n + " " + steps + "/" + creativity + " start=" + compiled.getStartAtStep() + " (predicted " + predictedStart
				+ ") wall=" + result.getDurationMs() + " ms error=" + result.getError());
			assertTrue("V" + n + " failed: " + result.getError(), result.isOk());
			byte[] out = result.getImages().get(0).getBytes();
			int[] od = ImageDims.decode(out);
			assertEquals(W, od[0]);
			assertEquals(H, od[1]);
			File png = new File(OUT, "t02_V" + n + "_s" + steps + "_c" + creativity + ".png");
			Files.write(png.toPath(), out);

			int[] probe = untouchedMargin(baseImg, MaskImage.decode(out), boxPx, 256, margin);
			String diffs = probe[1] + " / " + probe[2] + " / " + probe[3];
			logger.info("[p51][t02] V" + n + " untouched margin=" + probe[0] + " px (max channel diff outside box @0/@" + margin + "/@256 = " + diffs + ") -> " + png.getPath());
			table.append("| V").append(n).append(" | ").append(steps).append(" | ").append(creativity).append(" | ").append(compiled.getStartAtStep())
				.append(" | ").append(result.getDurationMs()).append(" | ").append(probe[0] < 0 ? "none <= 256" : String.valueOf(probe[0]))
				.append(" | ").append(diffs).append(" | ").append(compiled.getGraph().graphHash().substring(0, 12)).append(" | ")
				.append(vramBefore).append(" -> ").append(vramAfter).append(" | ").append(png.getName()).append(" |\n");
			if(probe[0] < 0 || probe[0] > margin) {
				findings.add("V" + n + ": pixels outside the box changed up to " + (probe[0] < 0 ? ">256" : probe[0]) + " px from the box edge (max diff " + diffs + "); expected <= " + margin);
			}
		}
		table.append("\nDecision rule (plan 5.2): cheapest variant whose PNG shows the scarf present, identity held, no seam; ties -> fewer steps.\n")
			.append("The read of the PNGs is a human judgement recorded in Implementation 6.C 205; V3 (8 / 0.6) ratified and shipped as the capabilities.json default.\n");
		if(!findings.isEmpty()) {
			table.append("\nFindings:\n");
			for(String s : findings) table.append("- ").append(s).append('\n');
		}
		File tbl = new File(OUT, "t02_table.md");
		Files.write(tbl.toPath(), table.toString().getBytes(StandardCharsets.UTF_8));
		logger.info("[p51][t02] wrote " + tbl.getPath() + "\n" + table);
		assertTrue("outside-mask pixels must be untouched beyond grow + blur (" + margin + " px): " + findings, findings.isEmpty());
	}

	// ─────────────────────────────── t03 ───────────────────────────────

	@Test(timeout = TIMEOUT_MS)
	public void t03_inpaintSdxlControl() throws Exception {
		SdTestGate.requireModelInstalled(sdu, server, sdxlModel, "the SDXL inpaint control");
		RendererCapabilities.InpaintParams ip = inpaintOf(sdxlModel);
		assertEquals(25, ip.getSteps());
		assertEquals(0.6, ip.getCreativity(), 0.0001);
		Cast cast = (t01Cast != null ? t01Cast : readCast());
		WorkflowCompiler wc = new WorkflowCompiler();

		String basePrompt = sdxlPrompt(cast, false);
		StageRequest baseReq = sdxlRequest(basePrompt, 25);
		WorkflowCompiler.CompiledGraph baseCompiled = wc.compileBase(baseReq, Collections.emptyList(), client);
		StageResult baseResult = adapter.execute(baseReq.toBuilder().graph(baseCompiled.getGraph()).inputs(baseCompiled.getInputs()).build(), CancelToken.NONE);
		logger.info("[p51][t03] BASE sdxl " + sdxlModel + " wall=" + baseResult.getDurationMs() + " ms error=" + baseResult.getError() + "\n[p51][t03] prompt=" + basePrompt);
		assertTrue("SDXL BASE failed: " + baseResult.getError(), baseResult.isOk());
		byte[] base = baseResult.getImages().get(0).getBytes();
		int[] bd = ImageDims.decode(base);
		assertEquals(W, bd[0]);
		assertEquals(H, bd[1]);
		Files.write(new File(OUT, "t03_base_sdxl.png").toPath(), base);

		RenderPlan.Box boxA = boxA();
		int[] boxPx = MaskImage.pixels(boxA, W, H);
		String inpaintPrompt = sdxlPrompt(cast, true);
		StageRequest req = sdxlRequest(inpaintPrompt, ip.getSteps()).toBuilder().stage(RenderPlan.STAGE_ACTOR_REGION).build();
		WorkflowCompiler.CompiledGraph compiled = wc.compileActorRegion(req, Collections.emptyList(), target(base, boxA), ip, client);
		assertEquals(10, compiled.getStartAtStep());
		assertEquals(WorkflowCompiler.startAtStep(25, 0.6), compiled.getStartAtStep());
		String vramBefore = vram(client.systemStats());
		StageResult result = adapter.execute(req.toBuilder().graph(compiled.getGraph()).inputs(compiled.getInputs()).build(), CancelToken.NONE);
		String vramAfter = vram(client.systemStats());
		logger.info("[p51][t03] ACTOR_REGION sdxl 25/0.6 start=" + compiled.getStartAtStep() + " wall=" + result.getDurationMs() + " ms error=" + result.getError()
			+ " vram " + vramBefore + " -> " + vramAfter + "\n[p51][t03] prompt=" + inpaintPrompt);
		assertTrue("SDXL ACTOR_REGION failed: " + result.getError(), result.isOk());
		byte[] out = result.getImages().get(0).getBytes();
		int[] od = ImageDims.decode(out);
		assertEquals(W, od[0]);
		assertEquals(H, od[1]);
		File png = new File(OUT, "t03_inpaint_sdxl.png");
		Files.write(png.toPath(), out);
		int margin = ip.getMaskGrow() + ip.getMaskBlur();
		int[] probe = untouchedMargin(MaskImage.decode(base), MaskImage.decode(out), boxPx, 256, margin);
		logger.info("[p51][t03] untouched margin=" + probe[0] + " px (max diff @0/@" + margin + "/@256 = " + probe[1] + " / " + probe[2] + " / " + probe[3] + ") -> " + png.getPath());
		Map<String, Object> backend = JSONUtil.getMap(result.getBackendGraph().getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(RenderPlan.STAGE_ACTOR_REGION, backend.get("stage"));
		assertEquals(compiled.getGraph().graphHash(), backend.get("graphHash"));
		assertTrue("SDXL outside-mask pixels must be untouched beyond grow + blur (" + margin + " px); measured " + probe[0],
			probe[0] >= 0 && probe[0] <= margin);
	}

	// ─────────────────────────────── t04 ───────────────────────────────

	@Test(timeout = TIMEOUT_MS)
	public void t04_rejectionPaths() throws Exception {
		StageRequest req = flux2Request("A grey probe scene for the rejection paths; nothing here is kept." + SPIKE_TAIL, FLUX2_NEGATIVE, 4);
		WorkflowCompiler.CompiledGraph compiled = new WorkflowCompiler().compileBase(req, greyReferences(3), client);

		/// (a) a checkpoint the node does not have: structurally valid, the catalogue cannot know the value
		/// (it keeps no enum lists), so the renderer is the one that says no.
		ComfyGraph missing = compiled.getGraph().copy();
		List<String> loaders = missing.nodesOf("CheckpointLoaderSimple");
		assertFalse("generated FLUX.2 graph should load its checkpoint through CheckpointLoaderSimple", loaders.isEmpty());
		String bogus = "NoSuchCheckpoint_p51_" + shortId() + ".safetensors";
		missing.setInput(loaders.get(0), "ckpt_name", bogus);
		AtomicInteger fetches = new AtomicInteger();
		Supplier<String> counting = () -> { fetches.incrementAndGet(); return client.objectInfoJson(); };
		assertNotNull(NodeCatalogue.forServer(server, counting));
		int before = fetches.get();
		assertNotNull(NodeCatalogue.forServer(server, counting));
		assertEquals("catalogue must be served from cache before the rejection", before, fetches.get());

		StageResult r = adapter.execute(req.toBuilder().graph(missing).inputs(compiled.getInputs()).build(), CancelToken.NONE);
		logger.info("[p51][t04a] uninstalled ckpt_name=" + bogus + " wall=" + r.getDurationMs() + " ms error=" + r.getError());
		assertFalse("an uninstalled checkpoint must not render", r.isOk());
		assertNotNull(r.getError());
		assertFalse("error text must not leak the renderer URL: " + r.getError(), r.getError().contains(server));
		assertTrue("expected " + RendererAdapter.ERROR_RENDERER_REJECTED + " but got: " + r.getError(),
			r.getError().startsWith(RendererAdapter.ERROR_RENDERER_REJECTED));
		assertNotNull(NodeCatalogue.forServer(server, counting));
		assertEquals("a renderer rejection must drop the cached catalogue so the next render re-fetches it", before + 1, fetches.get());

		/// (b) a node class the catalogue does not list: refused before any request leaves the JVM.
		Map<String, Object> map = compiled.getGraph().toMap();
		@SuppressWarnings("unchecked")
		Map<String, Object> node = (Map<String, Object>) map.get(loaders.get(0));
		node.put("class_type", "NoSuchNodeV3");
		ComfyGraph unknown = ComfyGraph.fromMap(map);
		StageResult u = adapter.execute(req.toBuilder().graph(unknown).inputs(compiled.getInputs()).build(), CancelToken.NONE);
		logger.info("[p51][t04b] unknown class error=" + u.getError() + " durationMs=" + u.getDurationMs());
		assertFalse(u.isOk());
		assertTrue("expected " + RendererAdapter.ERROR_GRAPH_INVALID + " but got: " + u.getError(), u.getError().startsWith(RendererAdapter.ERROR_GRAPH_INVALID));
		assertTrue(u.getError().contains("NoSuchNodeV3"));
		assertEquals("GRAPH_INVALID must not have sent a request", 0L, u.getDurationMs());
		assertNull(u.getBackendGraph());
	}

	// ─────────────────────────────── cast ───────────────────────────────

	static final class Cast {
		final List<WorkflowCompiler.Reference> refs = new ArrayList<>();
		final Map<String, String> physical = new LinkedHashMap<>();
		final Map<String, String> outfit = new LinkedHashMap<>();
		String setting = FALLBACK_SETTING;
		String action = FALLBACK_ACTION;
		String mood = FALLBACK_MOOD;
		boolean real;
		/// display name -> who could read the portrait bytes through PBAC ("owner" | "olio principal" | "stand-in")
		final Map<String, String> readAs = new LinkedHashMap<>();

		WorkflowCompiler.Reference ref(String displayName) {
			String tag = tagOf(displayName);
			for(WorkflowCompiler.Reference r : refs) {
				if(r.getName().equals(tag)) return r;
			}
			throw new IllegalStateException("no reference tagged " + tag + " in " + describe());
		}

		List<String> artifactIds() {
			List<String> ids = new ArrayList<>();
			for(WorkflowCompiler.Reference r : refs) {
				if(r.getArtifactObjectId() != null) ids.add(r.getArtifactObjectId());
			}
			return ids;
		}

		String describe() {
			StringBuilder sb = new StringBuilder(real ? "book 58 real portraits" : "AIME stand-ins").append(" [");
			for(WorkflowCompiler.Reference r : refs) {
				sb.append(r.getName()).append('=').append(r.getPng().length).append("B ");
			}
			return sb.append("] readAs=").append(readAs).toString();
		}
	}

	static String tagOf(String displayName) {
		for(int i = 0; i < CAST_ORDER.length; i++) {
			if(CAST_ORDER[i].equals(displayName)) return CAST_TAGS[i];
		}
		throw new IllegalArgumentException("not a cast member: " + displayName);
	}

	/// Book 58's cast through PBAC (decision 6.C 205 a): the book owner first; a portrait the owner is
	/// denied (Duña's Photo-Op portrait is owned by the olio principal, measured 2026-10-24) is re-read
	/// as the olio principal through AccessPoint - never via the unauthorized RecordReader the V2
	/// pipeline uses. Falls back to the AIME stand-ins with a logged note only when even that fails.
	Cast readCast() throws Exception {
		Cast cast = new Cast();
		try {
			OrganizationContext org = getTestOrganization(CUSTOM_ORG_PATH);
			BaseRecord owner = getCreateUser(CUSTOM_OWNER, org);
			long orgId = org.getOrganizationId();
			BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			BaseRecord book = PbBookUtil.findBookBySlug(owner, CUSTOM_BOOK_SLUG, orgId);
			if(book == null) {
				throw new IllegalStateException("book " + CUSTOM_BOOK_SLUG + " not readable by " + CUSTOM_OWNER + " in org " + orgId);
			}
			List<BaseRecord> scenes = PbBookUtil.listScenes(owner, book);
			if(!scenes.isEmpty()) {
				BaseRecord s0 = scenes.get(0);
				cast.setting = text(s0.get(OlioFieldNames.FIELD_PB_SETTING), FALLBACK_SETTING);
				cast.action = text(s0.get(OlioFieldNames.FIELD_PB_ACTION), FALLBACK_ACTION);
				cast.mood = text(s0.get(OlioFieldNames.FIELD_PB_MOOD), FALLBACK_MOOD);
			}
			List<Map<String, Object>> chars = PictureBookUtil.listCharacters(owner, book.get(FieldNames.FIELD_OBJECT_ID));
			for(String name : CAST_ORDER) {
				String oid = null;
				for(Map<String, Object> c : chars) {
					if(name.equals(c.get("name"))) oid = (String) c.get("objectId");
				}
				if(oid == null) throw new IllegalStateException("character " + name + " not in book 58's cast " + chars);
				BaseRecord person = readPerson(owner, oid, orgId);
				BaseRecord profile = person.get(FieldNames.FIELD_PROFILE);
				if(profile == null) throw new IllegalStateException(name + " has no profile");
				BaseRecord portrait = profile.get("portrait");
				if(portrait == null) throw new IllegalStateException(name + " has no portrait");
				String portraitOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
				String readAs = "owner";
				BaseRecord data = freshReadData(owner, portraitOid, orgId);
				if(data == null && olioUser != null) {
					readAs = "olio principal";
					data = freshReadData(olioUser, portraitOid, orgId);
				}
				if(data == null) throw new IllegalStateException(name + "'s portrait " + portraitOid + " is readable by neither " + CUSTOM_OWNER + " nor the olio principal");
				byte[] png = ByteModelUtil.getValue(data);
				if(png == null || png.length == 0) throw new IllegalStateException(name + "'s portrait has no bytes");
				int[] dims = ImageDims.decode(png);
				BaseRecord narrative = person.get(OlioFieldNames.FIELD_NARRATIVE);
				cast.physical.put(name, narrative != null ? text(narrative.get("physicalDescription"), "") : "");
				cast.outfit.put(name, narrative != null ? text(narrative.get("outfitDescription"), "") : "");
				cast.readAs.put(name, readAs);
				cast.refs.add(new WorkflowCompiler.Reference(tagOf(name), portraitOid, png));
				logger.info("[p51][cast] " + name + " portrait " + portraitOid + " (data id " + data.get(FieldNames.FIELD_ID) + ", owner "
					+ data.get(FieldNames.FIELD_OWNER_ID) + ") " + dims[0] + "x" + dims[1] + " " + png.length + " B read as " + readAs);
			}
			cast.real = true;
			return cast;
		}
		catch(Exception e) {
			logger.warn("[p51][cast] book 58 cast not usable in this JVM (" + e.getMessage() + ") - falling back to the AIME stand-ins; the report must say so");
		}
		cast.refs.clear();
		cast.physical.clear();
		cast.outfit.clear();
		cast.readAs.clear();
		String[][] standIns = { { CAST_ORDER[0], "./media/flux/character1.png" }, { CAST_ORDER[1], "./media/flux/character2.png" }, { CAST_ORDER[2], "./media/flux/landscape1.png" } };
		for(String[] s : standIns) {
			cast.refs.add(new WorkflowCompiler.Reference(tagOf(s[0]), null, Files.readAllBytes(new File(s[1]).toPath())));
			cast.physical.put(s[0], "");
			cast.outfit.put(s[0], "");
			cast.readAs.put(s[0], "stand-in");
		}
		return cast;
	}

	static BaseRecord readPerson(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.plan(false);
		q.planField(FieldNames.FIELD_ID);
		q.planField(FieldNames.FIELD_OBJECT_ID);
		q.planField(FieldNames.FIELD_NAME);
		q.planField(FieldNames.FIELD_GROUP_ID);
		q.planField(FieldNames.FIELD_ORGANIZATION_ID);
		q.planField(FieldNames.FIELD_PROFILE, new String[] { "portrait" }, false);
		q.planField(OlioFieldNames.FIELD_NARRATIVE, new String[] { "physicalDescription", "outfitDescription" }, false);
		q.setCache(false);
		BaseRecord person = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(person == null) throw new IllegalStateException("charPerson " + objectId + " not readable");
		return person;
	}

	static BaseRecord freshReadData(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── prompts ───────────────────────────────

	/// Book 58's scene 0 plus each character's own narrative, with a layout sentence that puts Touvier
	/// (the ACTOR_REGION target) in the right 40% of the frame where box A is (decision 6.C 205 b).
	static String basePrompt(Cast c) {
		String[] ord = { "first", "second", "third" };
		StringBuilder sb = new StringBuilder("Combine the exact people and faces from the three reference images into one scene: the ")
			.append(ord[0]).append(" reference image is ").append(CAST_ORDER[0]).append(", the ").append(ord[1]).append(" is ")
			.append(CAST_ORDER[1]).append(", the ").append(ord[2]).append(" is ").append(CAST_ORDER[2]).append(". ");
		sb.append("Setting: ").append(c.setting).append(' ');
		sb.append("Action: ").append(c.action).append(' ');
		sb.append("Mood: ").append(c.mood).append(". ");
		for(int i = 0; i < CAST_ORDER.length; i++) {
			String n = CAST_ORDER[i];
			String p = c.physical.getOrDefault(n, "");
			String o = c.outfit.getOrDefault(n, "");
			if(!p.isBlank() || !o.isBlank()) {
				sb.append(n).append(" is ").append(p.isBlank() ? "" : p + " ").append(o.isBlank() ? "" : o).append(". ");
			}
		}
		sb.append(CAST_ORDER[0]).append(" stands at the left of the frame watching. ").append(CAST_ORDER[1])
			.append(" stands in the middle of the frame spilling neuro chips onto the wet pavement. ").append(CAST_ORDER[2])
			.append(" crouches at the right side of the frame picking up neuro chips, his whole body inside the right third of the picture.");
		return sb.append(SPIKE_TAIL).toString();
	}

	/// The ACTOR_REGION pass: Touvier's portrait is the only reference (design §38 / plan 5.2), the
	/// change is the benign apparel edit Stephen chose - a bright yellow scarf.
	static String inpaintPrompt(Cast c) {
		String p = c.physical.getOrDefault(ACTOR_A, "");
		String o = c.outfit.getOrDefault(ACTOR_A, "");
		return "The person in the reference image is " + ACTOR_A + (p.isBlank() ? "" : ", " + p) + (o.isBlank() ? "" : ", " + o)
			+ ". He crouches at the right side of the frame picking up neuro chips in " + c.setting + " " + ACTOR_A
			+ " wears a bright yellow scarf around his neck. Keep his face, hair and the rest of his clothing exactly as in the reference image."
			+ " Matching lighting, scale, and perspective with the surrounding scene. No extra people. Do not draw the reference image"
			+ " itself - no poster, screen, mirror or framed picture. A single continuous scene.";
	}

	/// SDXL takes no references: the same scene and cast in words only.
	static String sdxlPrompt(Cast c, boolean scarf) {
		StringBuilder sb = new StringBuilder("photo, three people in a narrow wet alley. ");
		sb.append(c.setting).append(' ').append(c.action).append(' ').append(c.mood).append(". ");
		for(String n : CAST_ORDER) {
			String p = c.physical.getOrDefault(n, "");
			String o = c.outfit.getOrDefault(n, "");
			if(!p.isBlank() || !o.isBlank()) sb.append(n).append(" is ").append(p).append(' ').append(o).append(". ");
		}
		sb.append(CAST_ORDER[2]).append(" crouches at the right side of the frame picking up neuro chips.");
		if(scarf) sb.append(' ').append(ACTOR_A).append(" wears a bright yellow scarf around his neck.");
		return sb.toString();
	}

	// ─────────────────────────────── requests ───────────────────────────────

	StageRequest flux2Request(String prompt, String negative, int steps) {
		return StageRequest.builder()
			.stage(RenderPlan.STAGE_BASE)
			.prompt(new CompiledPrompt(prompt, negative, 0))
			.model(flux2Model)
			.width(W).height(H)
			.steps(steps).cfg(2.0)
			.sampler("euler").scheduler("simple")
			.seed(BASE_SEED)
			.build();
	}

	StageRequest sdxlRequest(String prompt, int steps) {
		return StageRequest.builder()
			.stage(RenderPlan.STAGE_BASE)
			.prompt(new CompiledPrompt(prompt, sdxlNegative, 0))
			.model(sdxlModel)
			.width(W).height(H)
			.steps(steps).cfg(5.0)
			.sampler("dpmpp_2m").scheduler("karras")
			.seed(BASE_SEED)
			.build();
	}

	static WorkflowCompiler.ActorTarget target(byte[] base, RenderPlan.Box box) {
		RenderPlan.Actor actor = new RenderPlan.Actor("A", ACTOR_A, RenderPlan.ROLE_FOREGROUND, "lean, straight-framed 25 year old man", true);
		return new WorkflowCompiler.ActorTarget(actor, base, List.of(box));
	}

	static List<WorkflowCompiler.Reference> greyReferences(int n) {
		List<WorkflowCompiler.Reference> refs = new ArrayList<>();
		for(int i = 0; i < n; i++) {
			/// Letters, not digits: Swarm drops digits from request keys, so probe0 / probe1 would be one input (ComfyGraph.swarmKey).
			refs.add(new WorkflowCompiler.Reference("probe" + (char) ('a' + i), null, MaskImage.solid(96, 128, new Color(60 + i * 30, 60 + i * 30, 60 + i * 30))));
		}
		return refs;
	}

	static RendererCapabilities.InpaintParams inpaintOf(String model) {
		RendererCapabilities caps = RendererCapabilities.forModel(model);
		assertNotNull("no capabilities family for " + model, caps);
		assertNotNull("no inpaint block for " + model, caps.getInpaint());
		return caps.getInpaint();
	}

	static RenderPlan.Box boxA() {
		String s = System.getProperty("pb.p51.boxA");
		if(s == null || s.isBlank()) return DEFAULT_BOX_A;
		String[] p = s.split(",");
		return new RenderPlan.Box(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
	}

	// ─────────────────────────────── throwaway book ───────────────────────────────

	static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord scene;
		BaseRecord planNode;
		BaseRecord renderNode;
		String slug;
		String bookOid;
	}

	Fixture fixture(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull(f.owner);
		f.slug = prefix + "-" + shortId();
		String dataPath = testProperties.getProperty("test.datagen.path");
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "P51 " + f.slug);
		assertNotNull(f.book);
		f.bookOid = f.book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, FALLBACK_SETTING);
		patch.set(OlioFieldNames.FIELD_PB_ACTION, FALLBACK_ACTION);
		patch.set(OlioFieldNames.FIELD_PB_MOOD, FALLBACK_MOOD);
		assertNotNull("scene text patch was not written", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		f.scene = PbBookUtil.readScene(f.owner, scene.get(FieldNames.FIELD_OBJECT_ID), orgOf(f.owner));
		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		f.planNode = PbGraphUtil.addNode(f.owner, wf, "plan-0-" + f.slug, PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
		/// No BASE node type exists yet (P5-2 adds the executable stage nodes); COMPOSITE is the closest
		/// present type for the node that owns a scene's rendered image and its renderGraph.
		f.renderNode = PbGraphUtil.addNode(f.owner, wf, "base-0-" + f.slug, PbNodeTypeEnumType.COMPOSITE, wfPath, 1);
		assertNotNull(f.planNode);
		assertNotNull(f.renderNode);
		return f;
	}

	static void cleanup(Fixture f) {
		if(f == null || f.bookOid == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("[p51] cleanup of " + f.slug + " failed: " + e.getMessage());
		}
	}

	static BaseRecord persistPng(BaseRecord user, String groupPath, String name, byte[] bytes) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		ByteModelUtil.setValue(data, bytes);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, data);
		assertNotNull("data.data for " + name + " was not created", created);
		return created;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/// Smallest margin m (0..max, step 8) at which every pixel outside box grown by m equals the base.
	/// Returns {margin or -1, maxDiff@0, maxDiff@report, maxDiff@max}.
	static int[] untouchedMargin(BufferedImage a, BufferedImage b, int[] box, int max, int report) {
		int w = Math.min(a.getWidth(), b.getWidth());
		int h = Math.min(a.getHeight(), b.getHeight());
		int[] pa = a.getRGB(0, 0, w, h, null, 0, w);
		int[] pb = b.getRGB(0, 0, w, h, null, 0, w);
		int found = -1;
		int d0 = -1, dr = -1, dm = -1;
		for(int m = 0; m <= max; m += 8) {
			int x0 = box[0] - m, y0 = box[1] - m, x1 = box[0] + box[2] + m, y1 = box[1] + box[3] + m;
			int maxDiff = 0;
			for(int y = 0; y < h; y++) {
				boolean rowInside = y >= y0 && y < y1;
				for(int x = 0; x < w; x++) {
					if(rowInside && x >= x0 && x < x1) continue;
					int p = pa[y * w + x], q = pb[y * w + x];
					int dR = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
					int dG = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
					int dB = Math.abs((p & 0xFF) - (q & 0xFF));
					int d = Math.max(dR, Math.max(dG, dB));
					if(d > maxDiff) maxDiff = d;
				}
			}
			if(m == 0) d0 = maxDiff;
			if(m == report) dr = maxDiff;
			if(m == max) dm = maxDiff;
			if(found < 0 && maxDiff == 0) found = m;
		}
		return new int[] { found, d0, dr, dm };
	}

	static ComfyGraph fixtureGraph(String file) throws Exception {
		try(InputStream in = TestP51GraphLive.class.getResourceAsStream(FIXTURE_DIR + file)) {
			assertNotNull("fixture " + FIXTURE_DIR + file + " missing", in);
			return ComfyGraph.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	static TreeSet<String> classTypes(ComfyGraph g) {
		TreeSet<String> out = new TreeSet<>();
		for(String id : g.nodeIds()) out.add(g.classType(id));
		return out;
	}

	static Map<String, Integer> multiset(ComfyGraph g) {
		Map<String, Integer> out = new TreeMap<>();
		for(String id : g.nodeIds()) out.merge(g.classType(id), 1, Integer::sum);
		return out;
	}

	@SuppressWarnings("unchecked")
	static String vram(Map<String, Object> stats) {
		if(stats == null) return "n/a";
		Object devs = stats.get("devices");
		if(!(devs instanceof List) || ((List<Object>) devs).isEmpty()) return "no devices";
		Object d0 = ((List<Object>) devs).get(0);
		if(!(d0 instanceof Map)) return "n/a";
		Map<String, Object> d = (Map<String, Object>) d0;
		return "free " + mb(d.get("vram_free")) + " / total " + mb(d.get("vram_total")) + " MB (torch free " + mb(d.get("torch_vram_free")) + ")";
	}

	static String mb(Object bytes) {
		return (bytes instanceof Number) ? String.valueOf(((Number) bytes).longValue() / (1024L * 1024L)) : "?";
	}

	String prop(String key, String def) {
		String v = testProperties.getProperty(key);
		return (v == null || v.isBlank()) ? def : v.trim();
	}

	static String text(Object o, String def) {
		return (o == null || o.toString().isBlank()) ? def : o.toString().trim();
	}

	static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}
}
