package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RenderCalibration;
import org.cote.accountmanager.olio.illustration.RenderEstimate;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.illustration.SwarmRendererAdapter;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P2-4 (PictureBook V3 design §12-§15, §18-§20) against the live Swarm node ({@code test.swarm.server})
 * and {@code am7db}, as a non-admin owner. The plan a scene gets is built by {@link PbRenderPlanUtil}
 * and rendered verbatim through {@link SwarmRendererAdapter}: "the prompt shown is the prompt sent".
 * <ol>
 * <li>{@link #textOnlyRenderOnLiveSwarm}: a book + scene with setting text -> TEXT_ONLY plan with an
 *     explicit canvas, a resolved installed checkpoint and no model degradation -> one BASE stage on
 *     the real node -> bytes decode to the asked dimensions, the calibration sample exists afterwards.
 *     The PNG is written under {@code target/} for inspection. Skips visibly (NOT a pass) when the node
 *     is unreachable or lists no SDXL checkpoint.</li>
 * <li>{@link #planArtifactRoundTrip}: the plan persists as a {@code renderPlan} JSON artifact as the
 *     owner and reads back with the same contentHash. No SD.</li>
 * <li>{@link #unreachableRendererFailsFastWithoutUrl}: a plan against an unreachable server still builds
 *     (§19: a degradation never stops the plan) and the stage fails with a result, never a throw, and
 *     never leaks the URL (§20). No SD.</li>
 * <li>{@link #facadePlanAsOwner}: the facade's plan-inspector contract on a real DB - 404 for a
 *     stranger, 400 for a bad preset, 500 for no SD server, and the owner's response carries the plan,
 *     the estimate and the content hash with no server URL anywhere in it.</li>
 * </ol>
 */
public class TestRenderPlanLive extends BaseTest {

	private static final String SETTING = "a lighthouse on a basalt headland at dusk";
	private static final String ACTION = "gulls wheel over the breakwater";
	private static final String MOOD = "windswept and expectant";

	private String server;

	@Before
	public void liveSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/// A V3 book with one scene carrying setting/action/mood, owned by {@code owner}.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord scene;
		BaseRecord node;
		String slug;
		String bookOid;
	}

	private Fixture fixture(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Plan " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = f.book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("scene", scene);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
			OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, SETTING);
		patch.set(OlioFieldNames.FIELD_PB_ACTION, ACTION);
		patch.set(OlioFieldNames.FIELD_PB_MOOD, MOOD);
		assertNotNull("scene text patch must persist", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		f.scene = PbBookUtil.readScene(f.owner, scene.get(FieldNames.FIELD_OBJECT_ID), orgOf(f.owner));
		assertNotNull("re-read scene", f.scene);
		assertEquals(SETTING, f.scene.get(OlioFieldNames.FIELD_PB_SETTING));
		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		assertNotNull("workflow", wf);
		f.node = PbGraphUtil.addNode(f.owner, wf, "scene-0-" + f.slug, PbNodeTypeEnumType.SCENE_PROMPT, wfPath, 0);
		assertNotNull("scene node", f.node);
		return f;
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

	private static RenderPlan.ModelPick locationPick(RenderPlan plan) {
		RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
		assertNotNull("the BASE stage must carry a model pick", pick);
		return pick;
	}

	private static void assertCommonPlanShape(RenderPlan plan, Fixture f) {
		assertEquals(RenderPlan.PLAN_VERSION, plan.getPlanVersion());
		assertEquals(RenderPlan.PURPOSE_SCENE, plan.getPurpose());
		assertNotNull("canvas is always explicit (§12)", plan.getCanvas());
		assertTrue("canvas width > 0: " + plan.getCanvas(), plan.getCanvas().getWidth() > 0);
		assertTrue("canvas height > 0: " + plan.getCanvas(), plan.getCanvas().getHeight() > 0);
		assertNotNull(plan.getPreset());
		assertNotNull(plan.getStyle());
		assertNotNull("the style revision hash is always present", plan.getStyle().getRevisionHash());
		assertNotNull(plan.getLocation());
		assertEquals(f.scene.get(FieldNames.FIELD_OBJECT_ID), plan.getLocation().getLocationId());
		assertTrue("location text comes from the scene's setting: " + plan.getLocation().getDescription(),
			plan.getLocation().getDescription().contains(SETTING));
		assertTrue("...and action", plan.getLocation().getDescription().contains(ACTION));
		assertTrue("olio.pb.scene.characters has no writer in Phase 2, so actors are empty (seam, §5)", plan.getActors().isEmpty());
		assertNotNull(plan.getStrategy());
		assertEquals(PbStrategyEnumType.TEXT_ONLY, plan.getStrategy().getName());
		assertTrue("Phase 2 strategy stages begin with BASE: " + plan.getStrategy().getStages(),
			!plan.getStrategy().getStages().isEmpty() && RenderPlan.STAGE_BASE.equals(plan.getStrategy().getStages().get(0)));
		assertNotNull("models carries the LOCATION pick", plan.getModels().get("location"));
		assertNotNull("models carries the SCENE_DIRECT pick too", plan.getModels().get("sceneDirect"));
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		assertNotNull("BASE is compiled", base);
		assertTrue("the compiled BASE prompt carries the scene text: " + base.getPrompt(), base.getPrompt().contains(SETTING));
		assertFalse("the config snapshot is populated", plan.getConfig().isEmpty());
		for(String k : new String[] { "name", "groupId", "ownerId", "organizationId", "id", "objectId" }) {
			assertFalse("config snapshot never carries " + k, plan.getConfig().containsKey(k));
		}
	}

	private static boolean hasDegradation(RenderPlan plan, String code) {
		for(RenderPlan.Degradation d : plan.getDegradations()) {
			if(code.equals(d.getCode())) return true;
		}
		return false;
	}

	// ─── 1. live render ──────────────────────────────────────────────────────────

	@Test
	public void textOnlyRenderOnLiveSwarm() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P2-4 TEXT_ONLY plan render");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		Fixture f = fixture("p24r");
		try {
			PbRenderPlanUtil.PlanOptions opts = new PbRenderPlanUtil.PlanOptions(null, false, PbQualityPresetEnumType.DRAFT, installed);
			RenderPlan plan = PbRenderPlanUtil.buildScenePlan(f.owner, f.book, f.scene, opts, SDAPIEnumType.SWARM, server);
			assertCommonPlanShape(plan, f);
			logger.info("PLAN: " + plan.toJson());

			RenderPlan.ModelPick pick = locationPick(plan);
			assertEquals("the explicit model is the OVERRIDE pick", ModelResolver.Step.OVERRIDE.name(), pick.getStep());
			assertEquals(ModelResolver.Reason.OK.name(), pick.getReason());
			assertFalse("an installed override is not degraded", pick.isDegraded());
			assertFalse("no MODEL_SUBSTITUTED for an installed checkpoint: " + plan.getDegradations(),
				hasDegradation(plan, RenderPlan.Degradation.MODEL_SUBSTITUTED));
			assertEquals(PbQualityPresetEnumType.DRAFT.name(), plan.getPreset().getPreset());
			assertFalse("DRAFT never upscales", plan.getPreset().isUpscale());
			assertEquals(List.of(RenderPlan.STAGE_BASE), plan.getStrategy().getStages());
			RendererCapabilities caps = RendererCapabilities.forModel(pick.getModel());
			assertNotNull("the installed checkpoint is of a known family", caps);
			assertEquals(caps.getPattern(), pick.getFamily());

			CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
			Map<String, Object> cfg = plan.getConfig();
			StageRequest.Builder rb = StageRequest.builder().plan(plan).stage(RenderPlan.STAGE_BASE).prompt(base)
				.model(pick.getModel()).width(plan.getCanvas().getWidth()).height(plan.getCanvas().getHeight())
				.steps(plan.getPreset().getSteps()).images(1).seed(-1).config(cfg);
			if(cfg.get("cfg") instanceof Number) rb.cfg(((Number) cfg.get("cfg")).doubleValue());
			if(cfg.get("sampler") != null) rb.sampler(cfg.get("sampler").toString());
			if(cfg.get("scheduler") != null) rb.scheduler(cfg.get("scheduler").toString());
			StageRequest req = rb.build();

			RenderCalibration.Sample before = RenderCalibration.sample(server, caps.getPattern());
			RendererAdapter adapter = new SwarmRendererAdapter(SDAPIEnumType.SWARM, server);
			StageResult result = adapter.execute(req, CancelToken.NONE);
			assertNotNull(result);
			assertTrue("the BASE stage must render on " + server + ": " + result.getError(), result.isOk());
			assertNotNull("the adapter records the request it sent", result.getRequestJson());
			assertTrue("the request sent carries the compiled scene text (the prompt shown is the prompt sent)",
				result.getRequestJson().contains(SETTING));
			assertTrue("the request sent names the picked model", result.getRequestJson().contains(pick.getModel()));
			assertFalse("the request JSON never carries a session id", result.getRequestJson().contains("session_id"));
			assertEquals(1, result.getImages().size());
			StageImage img = result.getImages().get(0);
			assertTrue("image bytes", img.getLength() > 0);
			assertEquals("decoded width matches the canvas", plan.getCanvas().getWidth(), img.getWidth());
			assertEquals("decoded height matches the canvas", plan.getCanvas().getHeight(), img.getHeight());

			File out = new File("target", "p24-live-render-" + f.slug + ".png");
			Files.write(out.toPath(), img.getBytes());
			logger.info("MEASURED: TEXT_ONLY BASE on " + server + " model=" + pick.getModel() + " family=" + pick.getFamily()
				+ " canvas=" + plan.getCanvas().getWidth() + "x" + plan.getCanvas().getHeight() + " steps=" + req.getSteps()
				+ " durationMs=" + result.getDurationMs() + " bytes=" + img.getLength() + " seed=" + img.getSeed()
				+ (img.isSeedReported() ? " (reported)" : " (inferred)") + " png=" + out.getAbsolutePath());

			RenderCalibration.Sample after = RenderCalibration.sample(server, caps.getPattern());
			assertNotNull("a successful stage records a calibration sample (§18)", after);
			assertTrue("the sample count advanced", after.getCount() > (before == null ? 0 : before.getCount()));
			Map<String, Object> est = RenderEstimate.estimate(plan, after);
			assertEquals(Boolean.TRUE, est.get("measured"));
			assertTrue("a measured estimate is positive: " + est, ((Number) est.get("seconds")).doubleValue() > 0);
			logger.info("MEASURED: estimate after one sample = " + JSONUtil.exportObject(est));

			/// The facade's `model` body key reaches the resolver as the override, and the facade's estimate
			/// sees this JVM's sample: a measured estimate after a measured render.
			Map<String, Object> viaFacade = PbServiceFacade.planScene(f.owner, f.bookOid, f.scene.get(FieldNames.FIELD_OBJECT_ID),
				Map.of("model", installed, "preset", "draft"), "SWARM", server);
			assertNotNull("facade plan with a model override", viaFacade);
			@SuppressWarnings("unchecked")
			Map<String, Object> facadeLoc = (Map<String, Object>) ((Map<String, Object>) viaFacade.get("models"))
				.get(PbModelTaskEnumType.LOCATION.name().toLowerCase());
			assertNotNull("the facade reports the LOCATION pick", facadeLoc);
			assertEquals("the facade's `model` key is the OVERRIDE", ModelResolver.Step.OVERRIDE.name(), facadeLoc.get("step"));
			assertEquals(installed, facadeLoc.get("model"));
			@SuppressWarnings("unchecked")
			Map<String, Object> facadeEst = (Map<String, Object>) viaFacade.get("estimate");
			assertEquals("the facade's estimate is measured once this JVM has rendered the family: " + facadeEst, Boolean.TRUE, facadeEst.get("measured"));
			assertTrue("...and positive: " + facadeEst, ((Number) facadeEst.get("seconds")).doubleValue() > 0);
			logger.info("MEASURED: facade estimate with model override = " + JSONUtil.exportObject(facadeEst));

			/// The round trip: the plan that was rendered is the plan that persists.
			BaseRecord artifact = PbRenderPlanUtil.persistPlanArtifact(f.owner, f.node, PbBookUtil.artifactGroupPath(f.slug), plan);
			assertNotNull("plan artifact", artifact);
			RenderPlan back = PbRenderPlanUtil.readPlanArtifact(artifact);
			assertNotNull(back);
			assertEquals(plan.contentHash(), back.contentHash());
			assertEquals(base.getPrompt(), back.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt());
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 2. artifact round trip (no SD) ──────────────────────────────────────────

	@Test
	public void planArtifactRoundTrip() throws Exception {
		Fixture f = fixture("p24a");
		try {
			RenderPlan plan = PbRenderPlanUtil.buildScenePlan(f.owner, f.book, f.scene, PbRenderPlanUtil.PlanOptions.NONE,
				SDAPIEnumType.SWARM, "http://127.0.0.1:1");
			assertCommonPlanShape(plan, f);
			BaseRecord artifact = PbRenderPlanUtil.persistPlanArtifact(f.owner, f.node, PbBookUtil.artifactGroupPath(f.slug), plan);
			assertNotNull("persistArtifact as the owner through PBAC", artifact);
			assertEquals(PbPipelineUtil.ROLE_RENDER_PLAN, artifact.get(OlioFieldNames.FIELD_PB_ROLE));
			BaseRecord selected = PbArtifactUtil.findSelected(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertNotNull("the plan artifact is the node's selected renderPlan", selected);
			RenderPlan back = PbRenderPlanUtil.readPlanArtifact(selected);
			assertNotNull("readPlanArtifact", back);
			assertEquals("same content hash after the DB round trip", plan.contentHash(), back.contentHash());
			assertEquals(plan.toJson(), back.toJson());
			assertNull("no text -> no plan", PbRenderPlanUtil.readPlanArtifact(null));
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 3. unreachable renderer (no SD) ─────────────────────────────────────────

	@Test
	public void unreachableRendererFailsFastWithoutUrl() throws Exception {
		String dead = "http://127.0.0.1:1";
		Fixture f = fixture("p24u");
		try {
			RenderPlan plan = PbRenderPlanUtil.buildScenePlan(f.owner, f.book, f.scene, PbRenderPlanUtil.PlanOptions.NONE,
				SDAPIEnumType.SWARM, dead);
			assertCommonPlanShape(plan, f);
			assertTrue("an unreadable inventory is a MODEL_SUBSTITUTED degradation, never a throw (§19): " + plan.getDegradations(),
				hasDegradation(plan, RenderPlan.Degradation.MODEL_SUBSTITUTED));
			assertFalse("the plan JSON never carries the server URL", plan.toJson().contains(dead));
			RenderPlan.ModelPick pick = locationPick(plan);
			assertNull("an unreadable inventory resolves no model (§20: say so, never guess)", pick.getModel());
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), pick.getReason());
			assertTrue("the plan still compiles BASE", plan.getCompiled().containsKey(RenderPlan.STAGE_BASE));
			logger.info("MEASURED: unreachable inventory -> step=" + pick.getStep() + " reason=" + pick.getReason()
				+ " degradations=" + plan.getDegradations());

			StageRequest req = StageRequest.builder().plan(plan).stage(RenderPlan.STAGE_BASE)
				.prompt(plan.getCompiled().get(RenderPlan.STAGE_BASE)).model("p24-unreachable")
				.width(plan.getCanvas().getWidth()).height(plan.getCanvas().getHeight()).steps(plan.getPreset().getSteps()).build();
			long start = System.nanoTime();
			StageResult result = new SwarmRendererAdapter(SDAPIEnumType.SWARM, dead).execute(req, CancelToken.NONE);
			long ms = (System.nanoTime() - start) / 1_000_000L;
			assertNotNull("a failed stage is a result, never a throw", result);
			assertFalse(result.isOk());
			assertEquals(RendererAdapter.ERROR_UNREACHABLE, result.getError());
			assertFalse("the error never carries the URL (§20)", result.getError().contains("127.0.0.1"));
			assertTrue(result.getImages().isEmpty());
			logger.info("MEASURED: unreachable stage failed in " + ms + " ms with '" + result.getError() + "'");
		}
		finally {
			cleanup(f);
		}
	}

	// ─── 4. facade contract on a real DB ─────────────────────────────────────────

	@Test
	public void facadePlanAsOwner() throws Exception {
		Fixture f = fixture("p24f");
		BaseRecord stranger = getCreateUser("pbPlanStranger");
		assertNotNull(stranger);
		try {
			String sceneOid = f.scene.get(FieldNames.FIELD_OBJECT_ID);
			String live = (server != null && SdTestGate.isReachable(server) ? server : "http://127.0.0.1:1");

			try {
				PbServiceFacade.planScene(stranger, f.bookOid, sceneOid, null, "SWARM", live);
				fail("a stranger must not see the plan");
			}
			catch(PictureBookException e) {
				assertEquals("stranger -> 404 (" + e.getMessage() + ")", 404, e.getStatus());
			}
			try {
				PbServiceFacade.planScene(f.owner, f.bookOid, "no-such-scene", null, "SWARM", live);
				fail("unknown scene");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
			}
			try {
				PbServiceFacade.planScene(f.owner, f.bookOid, sceneOid, null, "SWARM", "  ");
				fail("no SD server");
			}
			catch(PictureBookException e) {
				assertEquals(500, e.getStatus());
				assertEquals("SD server is not configured", e.getMessage());
			}
			try {
				PbServiceFacade.planScene(f.owner, f.bookOid, sceneOid, null, "nonsense", live);
				fail("bad API type");
			}
			catch(PictureBookException e) {
				assertEquals(500, e.getStatus());
				assertEquals("SD server API type is not recognised", e.getMessage());
			}
			Map<String, Object> bad = new LinkedHashMap<>();
			bad.put("preset", "ULTRA");
			try {
				PbServiceFacade.planScene(f.owner, f.bookOid, sceneOid, bad, "SWARM", live);
				fail("bad preset");
			}
			catch(PictureBookException e) {
				assertEquals(400, e.getStatus());
			}

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("preset", "final");
			body.put("promptOverride", "a lantern-lit harbour at midnight");
			Map<String, Object> out = PbServiceFacade.planScene(f.owner, f.bookOid, sceneOid, body, "swarm", live);
			assertNotNull(out);
			String json = JSONUtil.exportObject(out);
			logger.info("FACADE: " + json);
			assertFalse("the response never carries the server URL", json.contains(live));
			assertEquals(RenderPlan.PLAN_VERSION, out.get("planVersion"));
			assertNotNull("estimate", out.get("estimate"));
			assertNotNull("contentHash", out.get("contentHash"));
			RenderPlan plan = RenderPlan.fromMap(out);
			assertNotNull("the response body is a RenderPlan", plan);
			assertEquals(out.get("contentHash"), plan.contentHash());
			assertEquals(PbQualityPresetEnumType.FINAL.name(), plan.getPreset().getPreset());
			assertTrue("FINAL upscales", plan.getPreset().isUpscale());
			assertEquals(List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_UPSCALE), plan.getStrategy().getStages());
			assertEquals("a lantern-lit harbour at midnight", plan.getIntent().getPromptOverride());
			assertTrue("the override becomes the main prompt", plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt().contains("lantern-lit harbour"));
			assertFalse("...and the scene's own text does not", plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt().contains(SETTING));
			@SuppressWarnings("unchecked")
			Map<String, Object> est = (Map<String, Object>) out.get("estimate");
			assertEquals("FINAL counts BASE + UPSCALE", 2, ((Number) est.get("stages")).intValue());
			assertEquals(1, ((Number) est.get("scenes")).intValue());
			assertNotNull(est.get("source"));
			assertTrue("the LOCATION pick is reported under models", out.get("models") instanceof Map);
			assertNotNull(((Map<?, ?>) out.get("models")).get(PbModelTaskEnumType.LOCATION.name().toLowerCase()));
		}
		finally {
			cleanup(f);
		}
	}
}
