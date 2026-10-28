package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.FixtureGraphClient;
import org.cote.accountmanager.olio.illustration.MaskImage;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PbStageFailedException;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PlannedRender;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.RenderBackend;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil.SceneGraph;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.PbCalibrationUtil;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P5-3 (§6.C 209 b): {@code PbNodeExecutor.renderPlanned} / {@code renderStages} run against {@code am7db}
 * with <b>no Swarm server</b>, through the package-private {@link RenderBackend} seam - a scripted
 * {@link RendererAdapter} answers each stage and a {@link FixtureGraphClient} answers the compiler's
 * generated-workflow and version calls from the captured P5-0 fixtures. What the live path persists
 * (plan artifact, renderGraph envelopes, stage images, calibration rows) is asserted on the database;
 * what it sends is asserted on the requests the fake adapter received. The {@link SDUtil} points at a
 * closed port and nothing opens a connection.
 * <ol>
 * <li>{@link #directReferenceBaseCarriesBothPortraits}: DIRECT_REFERENCE, two actors referenced on BASE
 *     -> one graph request carrying {@code actora} / {@code actorb} with the portraits' bytes, one
 *     renderGraph envelope stamped {@link FixtureGraphClient#FIXTURE_VERSION}, no stage image, a persisted
 *     calibration row for (offline server, flux2 family, BASE).</li>
 * <li>{@link #sequentialInpaintChainsAPassOverTheBase}: SEQUENTIAL_INPAINT, one actor on BASE and one on
 *     ACTOR_REGION -> two requests (the second a masked init-image graph), the BASE image persisted as the
 *     one {@code stage} artifact, two renderGraph envelopes, the final references = the pass's portrait plus
 *     the stage artifact it painted over, calibration rows for both stages.</li>
 * <li>{@link #failedPassIsADegradationAndTheBaseStands}: the ACTOR_REGION pass answers an error -> one
 *     {@code ACTOR_FAILED} degradation (also recorded on the persisted plan artifact), one stage error
 *     labelled {@code ACTOR_REGION#<actor>}, the final bytes are the BASE bytes, no stage image.</li>
 * <li>{@link #baseFailureIs503WithNothingButThePlanPersisted}: the BASE stage answers UNREACHABLE ->
 *     {@link PbStageFailedException} 503 carrying the {@code UNREACHABLE} stage error; the plan artifact is
 *     the only artifact on the node and no calibration row was written.</li>
 * </ol>
 */
public class TestPbRenderStagesOffline extends BaseTest {

	private static final String MODEL = "flux2Klein_9b";
	private static final String OFFLINE_SERVER = "http://127.0.0.1:9";
	private static final String PROMPT = "two travellers on a basalt headland at dusk";
	private static final String PASS_PROMPT = "Tobin, a weathered sailor in an oilskin coat";
	private static final String NEGATIVE = "blurry, text";
	private static final int W = 1024;
	private static final int H = 768;
	private static final int PORTRAIT_W = 96;
	private static final int PORTRAIT_H = 128;
	private static final String ACTOR_A = "actorA";
	private static final String ACTOR_B = "actorB";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };

	@Before
	public void offlineSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	// ─────────────────────────────── scripted renderer ───────────────────────────────

	/// Answers the queued results in order and keeps every request it was handed; a request past the
	/// script is a test defect, not a silent miss.
	private static final class ScriptedAdapter implements RendererAdapter {
		final Deque<StageResult> script = new ArrayDeque<>();
		final List<StageRequest> requests = new ArrayList<>();

		ScriptedAdapter answer(StageResult... results) {
			for(StageResult r : results) {
				script.add(r);
			}
			return this;
		}

		@Override
		public RendererCapabilities capabilities(String model) {
			return RendererCapabilities.forModel(model);
		}

		@Override
		public List<String> listModels() {
			return List.of(MODEL);
		}

		@Override
		public StageResult execute(StageRequest request, CancelToken cancel) {
			requests.add(request);
			StageResult r = script.poll();
			if(r == null) {
				throw new IllegalStateException("The script has no answer for request " + requests.size() + " (" + request.getStage() + ")");
			}
			return r;
		}
	}

	private static StageResult ok(byte[] png, int seed, long durationMs) {
		return StageResult.builder()
			.image(new StageImage(png, seed, true, W, H))
			.durationMs(durationMs)
			.requestJson("{\"offline\":true}")
			.backendGraph("{\"stage\":\"offline\"}")
			.build();
	}

	private static RenderBackend backend(ScriptedAdapter adapter, FixtureGraphClient client) {
		return new RenderBackend() {
			@Override
			public RendererAdapter adapter(SDUtil sdu) {
				return adapter;
			}
			@Override
			public SwarmGraphClient client(SDUtil sdu) {
				return client;
			}
		};
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A V3 book with one scene, its RENDER_PLAN node, and two portrait artifacts with distinct solid-colour
	/// PNG bytes, owned by a non-admin user.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		String slug;
		String bookOid;
		String sceneOid;
		String portraitA;
		String portraitB;
		byte[] pngA;
		byte[] pngB;
		SceneGraph graph;
		SDUtil sdu;
	}

	private Fixture fixture(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Offline stages " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = oidOf(f.book);
		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("scene", scene);
		f.sceneOid = oidOf(scene);

		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		f.workflow = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		assertNotNull("workflow", f.workflow);
		BaseRecord n = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.renderPlanHandle(f.sceneOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
		assertNotNull("render-plan node", n);
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, f.sceneOid);
		assertNotNull("node scope patch must be written", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		f.node = PbGraphUtil.readNode(f.owner, oidOf(n), orgOf(f.owner));
		assertNotNull("re-read node", f.node);

		f.pngA = MaskImage.solid(PORTRAIT_W, PORTRAIT_H, new Color(200, 40, 40));
		f.pngB = MaskImage.solid(PORTRAIT_W, PORTRAIT_H, new Color(40, 40, 200));
		f.portraitA = portraitArtifact(f, "a", f.pngA);
		f.portraitB = portraitArtifact(f, "b", f.pngB);

		f.graph = PbPipelineUtil.openNodeGraph(f.owner, f.book, f.workflow, f.node, null, null);
		assertNotNull("scene graph", f.graph);
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER);
		return f;
	}

	private String portraitArtifact(Fixture f, String tag, byte[] png) throws Exception {
		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord pNode = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.portraitHandle(UUID.randomUUID().toString()),
			PbNodeTypeEnumType.PORTRAIT, wfPath, 0);
		assertNotNull("portrait node " + tag, pNode);
		String groupPath = PbBookUtil.artifactGroupPath(f.slug);
		BaseRecord data = persistPng(f.owner, groupPath, "p53-portrait-" + tag + "-" + shortId() + ".png", png);
		BaseRecord art = PbArtifactUtil.persistArtifact(f.owner, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
			groupPath, data, null, null, null);
		assertNotNull("portrait artifact " + tag, art);
		assertTrue("portrait metrics " + tag, PbArtifactUtil.recordImageMetrics(f.owner, art, png, "image/png", PORTRAIT_W, PORTRAIT_H, null));
		return oidOf(art);
	}

	private BaseRecord persistPng(BaseRecord u, String groupPath, String name, byte[] bytes) throws Exception {
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, image, name, groupPath, orgOf(u));
		image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(image, bytes);
		BaseRecord created = ioContext.getAccessPoint().create(u, image);
		assertNotNull("data " + name, created);
		return created;
	}

	private void cleanup(Fixture f) {
		if(f == null || f.book == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("Cleanup of " + f.slug + ": " + e.getMessage());
		}
	}

	/// A resolved plan for the two fixture actors: both referenced, on the stages given, under
	/// {@code strategy}; the INPAINT pick and the per-actor pass prompt only for SEQUENTIAL_INPAINT.
	private static RenderPlan plan(Fixture f, PbStrategyEnumType strategy, String stageA, String stageB) {
		String family = RendererCapabilities.forModel(MODEL).getPattern();
		assertNotNull("family of " + MODEL, family);
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("cfg", 2.0);
		cfg.put("sampler", "euler");
		cfg.put("scheduler", "simple");
		cfg.put("steps", 4);
		boolean sequential = (strategy == PbStrategyEnumType.SEQUENTIAL_INPAINT);
		List<String> stages = (sequential ? List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION) : List.of(RenderPlan.STAGE_BASE));
		RenderPlan.Builder b = RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(W, H))
			.preset(new RenderPlan.PresetValues("p53", 4, 1, false, null))
			.config(cfg)
			.actor(actor(ACTOR_A, "Mara", 0, f.portraitA, stageA, new RenderPlan.Box(0.05, 0.1, 0.4, 0.8)))
			.actor(actor(ACTOR_B, "Tobin", 1, f.portraitB, stageB, new RenderPlan.Box(0.55, 0.1, 0.4, 0.8)))
			.strategy(new RenderPlan.Strategy(strategy, stages, "p53 offline"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, MODEL, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null))
			.compiled(RenderPlan.STAGE_BASE, new CompiledPrompt(PROMPT, NEGATIVE, 0));
		if(sequential) {
			b.model(new RenderPlan.ModelPick(PbModelTaskEnumType.INPAINT, MODEL, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null));
			b.compiled(RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B), new CompiledPrompt(PASS_PROMPT, NEGATIVE, 0));
		}
		return b.build();
	}

	private static RenderPlan.Actor actor(String id, String name, int priority, String portraitOid, String stage, RenderPlan.Box box) {
		return new RenderPlan.Actor.Builder(id)
			.name(name)
			.role(RenderPlan.ROLE_FOREGROUND)
			.priority(priority)
			.box(box)
			.identity(new RenderPlan.Identity(portraitOid, null, name))
			.referenced(true)
			.referenceStage(stage)
			.build();
	}

	private static PlannedRender render(Fixture f, RenderPlan plan, RenderBackend backend) {
		return PbNodeExecutor.renderPlanned(f.graph, f.node, plan, "scene 0 of " + f.slug, f.sdu, "p53_" + f.slug, "Scene has no text to render", backend);
	}

	private static void assertPng(String label, byte[] bytes) {
		assertNotNull(label + " bytes", bytes);
		assertTrue(label + " is a PNG", bytes.length > PNG_MAGIC.length);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals(label + " PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
	}

	private static Map<String, Object> envelope(BaseRecord graphArtifact) {
		Map<String, Object> env = PbRenderPlanUtil.readGraphEnvelope(graphArtifact);
		assertNotNull("renderGraph envelope", env);
		return env;
	}

	private static void assertCalibrationRow(BaseRecord owner, String family, String stage, int references) {
		BaseRecord row = PbCalibrationUtil.findRow(owner, OFFLINE_SERVER, family, stage);
		assertNotNull("calibration row for " + stage + " on " + OFFLINE_SERVER, row);
		Number count = row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT);
		assertTrue("sampleCount >= 1 for " + stage + ": " + count, count != null && count.intValue() >= 1);
		Number refs = row.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES);
		assertEquals("lastReferences for " + stage, references, refs == null ? -1 : refs.intValue());
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void directReferenceBaseCarriesBothPortraits() throws Exception {
		logger.info("directReferenceBaseCarriesBothPortraits");
		Fixture f = null;
		try {
			f = fixture("p53dr");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(basePng, 4242, 1500));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE, RenderPlan.STAGE_BASE);

			PlannedRender pr = render(f, plan, backend(adapter, client));

			/// One graph request, both portraits in it under the letter-suffixed tags, their bytes intact.
			assertEquals("requests sent", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertEquals(RenderPlan.STAGE_BASE, sent.getStage());
			assertTrue("BASE went through the graph path", sent.isGraph());
			assertNotNull("graph inputs", sent.getInputs());
			assertArrayEquals("actora carries portrait A", f.pngA, sent.getInputs().image("actora"));
			assertArrayEquals("actorb carries portrait B", f.pngB, sent.getInputs().image("actorb"));
			assertEquals("reference provenance", List.of(f.portraitA, f.portraitB), sent.getInputs().getReferenceArtifactIds());
			assertEquals("the client was asked for one generated workflow", 1, client.requests().size());

			/// The result is the scripted image, with the BASE stage as the only one rendered.
			assertArrayEquals("final bytes are the scripted BASE image", basePng, pr.bytes);
			assertEquals(Long.valueOf(4242), pr.seed);
			assertEquals(List.of(RenderPlan.STAGE_BASE), pr.stagesRendered);
			assertTrue("no stage errors: " + pr.stageErrors, pr.stageErrors.isEmpty());
			assertEquals("degradations: " + pr.degradations, 0, pr.degradations.size());
			assertEquals("final references", List.of(f.portraitA, f.portraitB), pr.referenceArtifactObjectIds);
			assertTrue("calibration persisted", pr.calibrationPersisted);
			assertNotNull("final data.data", pr.data);
			assertPng("final", pr.bytes);

			/// Persisted: the plan, one renderGraph envelope stamped with the fixture client's version, no stage image.
			assertEquals("plan artifacts", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).size());
			List<BaseRecord> graphs = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("renderGraph artifacts", 1, graphs.size());
			Map<String, Object> env = envelope(graphs.get(0));
			assertEquals(RenderPlan.STAGE_BASE, env.get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(FixtureGraphClient.FIXTURE_VERSION, env.get(PbRenderPlanUtil.GRAPH_KEY_SWARM_VERSION));
			assertEquals("graph hash matches the request graph", sent.getGraph().graphHash(), env.get(PbRenderPlanUtil.GRAPH_KEY_HASH));
			assertTrue("no stage image on a one-stage render", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertCalibrationRow(f.owner, plan.pickForStage(RenderPlan.STAGE_BASE).getFamily(), RenderPlan.STAGE_BASE, 2);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void sequentialInpaintChainsAPassOverTheBase() throws Exception {
		logger.info("sequentialInpaintChainsAPassOverTheBase");
		Fixture f = null;
		try {
			f = fixture("p53si");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] passPng = MaskImage.solid(W, H, new Color(160, 100, 30));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(basePng, 11, 1200), ok(passPng, 12, 900));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);

			PlannedRender pr = render(f, plan, backend(adapter, client));

			/// BASE carried only actor A; the pass carried actor B's portrait over the BASE image with a mask.
			assertEquals("requests sent", 2, adapter.requests.size());
			StageRequest base = adapter.requests.get(0);
			StageRequest pass = adapter.requests.get(1);
			assertEquals(RenderPlan.STAGE_BASE, base.getStage());
			assertTrue("BASE is a graph", base.isGraph());
			assertArrayEquals("BASE actora is portrait A", f.pngA, base.getInputs().image("actora"));
			assertNull("BASE carries no actorb", base.getInputs().image("actorb"));
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, pass.getStage());
			assertTrue("pass is a graph", pass.isGraph());
			assertArrayEquals("pass actora is portrait B", f.pngB, pass.getInputs().image("actora"));
			assertArrayEquals("pass init image is the BASE output", basePng, pass.getInputs().image("init"));
			assertNotNull("pass carries a mask", pass.getInputs().image("mask"));
			assertEquals("pass prompt", PASS_PROMPT, pass.getPrompt().getPrompt());
			assertEquals("the client was asked for two generated workflows", 2, client.requests().size());
			assertTrue("second workflow request was an inpaint", client.requests().get(1).containsKey("initimage"));

			String passLabel = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B);
			assertArrayEquals("final bytes are the pass output", passPng, pr.bytes);
			assertEquals(Long.valueOf(12), pr.seed);
			assertEquals(List.of(RenderPlan.STAGE_BASE, passLabel), pr.stagesRendered);
			assertTrue("no stage errors: " + pr.stageErrors, pr.stageErrors.isEmpty());
			assertEquals("degradations: " + pr.degradations, 0, pr.degradations.size());
			assertTrue("calibration persisted", pr.calibrationPersisted);

			/// Persisted: the BASE image as the one stage artifact, two envelopes, the final chaining to the stage.
			List<BaseRecord> stageArts = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE);
			assertEquals("stage artifacts", 1, stageArts.size());
			String stageOid = oidOf(stageArts.get(0));
			assertEquals("final references: the pass's portrait and the stage it painted over", List.of(f.portraitB, stageOid), pr.referenceArtifactObjectIds);
			List<BaseRecord> graphs = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("renderGraph artifacts", 2, graphs.size());
			assertEquals(RenderPlan.STAGE_BASE, envelope(graphs.get(0)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(passLabel, envelope(graphs.get(1)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(FixtureGraphClient.FIXTURE_VERSION, envelope(graphs.get(1)).get(PbRenderPlanUtil.GRAPH_KEY_SWARM_VERSION));
			String family = plan.pickForStage(RenderPlan.STAGE_BASE).getFamily();
			assertCalibrationRow(f.owner, family, RenderPlan.STAGE_BASE, 1);
			assertCalibrationRow(f.owner, family, RenderPlan.STAGE_ACTOR_REGION, 1);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void failedPassIsADegradationAndTheBaseStands() throws Exception {
		logger.info("failedPassIsADegradationAndTheBaseStands");
		Fixture f = null;
		try {
			f = fixture("p53pf");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(basePng, 21, 1100),
				StageResult.failed(RendererAdapter.ERROR_NO_IMAGES, 300, "{\"offline\":true}"));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);

			PlannedRender pr = render(f, plan, backend(adapter, client));

			String passLabel = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B);
			assertEquals("requests sent", 2, adapter.requests.size());
			assertArrayEquals("final bytes are the BASE image", basePng, pr.bytes);
			assertEquals(List.of(RenderPlan.STAGE_BASE), pr.stagesRendered);
			assertEquals("one stage error", 1, pr.stageErrors.size());
			assertEquals(passLabel, pr.stageErrors.get(0).get("stage"));
			assertEquals(RendererAdapter.errorCode(RendererAdapter.ERROR_NO_IMAGES), pr.stageErrors.get(0).get("code"));
			boolean actorFailed = false;
			for(String d : pr.degradations) {
				actorFailed |= d.startsWith(RenderPlan.Degradation.ACTOR_FAILED + ": ");
			}
			assertTrue("ACTOR_FAILED degradation reported: " + pr.degradations, actorFailed);
			assertEquals("final references are the BASE references", List.of(f.portraitA), pr.referenceArtifactObjectIds);
			assertTrue("the BASE stage still calibrated", pr.calibrationPersisted);

			/// The persisted plan artifact says what the render did; nothing was persisted for the failed pass.
			List<BaseRecord> plans = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertEquals("plan artifacts", 1, plans.size());
			RenderPlan persisted = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, oidOf(plans.get(0)), orgOf(f.owner)));
			assertNotNull("persisted plan", persisted);
			boolean recorded = false;
			for(RenderPlan.Degradation d : persisted.getDegradations()) {
				recorded |= RenderPlan.Degradation.ACTOR_FAILED.equals(d.getCode()) && ACTOR_B.equals(d.getActorId());
			}
			assertTrue("ACTOR_FAILED recorded on the plan artifact for " + ACTOR_B, recorded);
			assertTrue("no stage image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertEquals("one renderGraph envelope (BASE only)", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH).size());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void baseFailureIs503WithNothingButThePlanPersisted() throws Exception {
		logger.info("baseFailureIs503WithNothingButThePlanPersisted");
		Fixture f = null;
		try {
			f = fixture("p53bf");
			ScriptedAdapter adapter = new ScriptedAdapter().answer(StageResult.failed(RendererAdapter.ERROR_UNREACHABLE, 5, "{\"offline\":true}"));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE, RenderPlan.STAGE_BASE);
			/// The owner's calibration rows are shared across the class, so the check is before/after on the
			/// BASE row's sample count (absent counts as 0).
			String family = plan.pickForStage(RenderPlan.STAGE_BASE).getFamily();
			int samplesBefore = calibrationSamples(f.owner, family, RenderPlan.STAGE_BASE);

			try {
				render(f, plan, backend(adapter, client));
				fail("A BASE failure must throw PbStageFailedException");
			}
			catch(PbStageFailedException e) {
				assertEquals("UNREACHABLE is a 503", 503, e.getStatus());
				assertTrue("message names the scene: " + e.getMessage(), e.getMessage().startsWith("Render of scene 0 of " + f.slug + " failed: "));
				assertTrue("message carries the plan artifact: " + e.getMessage(), e.getMessage().contains("(plan artifact "));
				assertEquals("one stage error", 1, e.getStageErrors().size());
				assertEquals(RenderPlan.STAGE_BASE, e.getStageErrors().get(0).get("stage"));
				assertEquals(RendererAdapter.CODE_UNREACHABLE, e.getStageErrors().get(0).get("code"));
			}
			assertEquals("exactly one request was sent", 1, adapter.requests.size());

			/// The plan is the only artifact; no image, no envelope, no calibration sample.
			assertEquals("plan artifacts", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).size());
			assertTrue("no renderGraph", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH).isEmpty());
			assertTrue("no stage image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertTrue("no base image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_BASE).isEmpty());
			assertEquals("a failed BASE records no calibration sample", samplesBefore, calibrationSamples(f.owner, family, RenderPlan.STAGE_BASE));
		}
		finally {
			cleanup(f);
		}
	}

	/// The owner's persisted sample count for (offline server, family, stage); 0 when there is no row.
	private static int calibrationSamples(BaseRecord owner, String family, String stage) {
		BaseRecord row = PbCalibrationUtil.findRow(owner, OFFLINE_SERVER, family, stage);
		if(row == null) {
			return 0;
		}
		Number count = row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT);
		return (count == null ? 0 : count.intValue());
	}
}
