package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CalibrationSample;
import org.cote.accountmanager.olio.illustration.ComfyGraph;
import org.cote.accountmanager.olio.illustration.ImageDims;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.RenderEstimate;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.illustration.WorkflowCompiler;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.PbCalibrationUtil;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * P5-2 T11 (PictureBook V3 implementation §13.4): the executor's plan-driven path end to end against the
 * live Swarm node ({@code test.swarm.server}) with the FLUX.2 Klein checkpoint, through the product entry
 * point {@link PbServiceFacade#requestRenderPlanScene(BaseRecord, String, String, String, String)}.
 * <p>
 * Gated exactly like {@link TestP51GraphLive}: {@code PB_SD_TESTS=1} (env) or {@code -Dpb.sd.tests=1}; a skip
 * is <b>not</b> a pass. Pure SD - no LLM call is made by any case here. Every case builds its own fresh V3
 * book as {@code pbCustomTestUser} and deletes it in {@code finally}; the cast portraits and the landscape
 * are read from Stephen's book {@code catatone-custom-book-6} (never mutated) and copied into the fixture
 * book as approved PORTRAIT / LANDSCAPE artifacts.
 * <ul>
 * <li>t01 two actors + location: DIRECT_REFERENCE, one BASE stage, three references, calibration row.</li>
 * <li>t02 three actors + location: SEQUENTIAL_INPAINT, BASE then one ACTOR_REGION pass, ROLE_STAGE + ROLE_BASE,
 * two graph envelopes, ACTOR_REGION calibration row at the effective (post-start) step count.</li>
 * <li>t03 the ACTOR_REGION actor's approved portrait is not a PNG: the pass fails, the node degrades to the
 * BASE output (DONE_UNVERIFIED, ACTOR_FAILED degradation, one stage error with a coded reason).</li>
 * <li>t04 a BASE reference is not a PNG: the node fails before any output (FAILED, lastError names the plan
 * artifact, no ROLE_BASE, run FAILED), the exception is host-free and classifiable.</li>
 * <li>t05 a second user's estimate read never creates that user's calibration group and never reports the
 * owner's rows as "your renders".</li>
 * </ul>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestP52SequentialInpaintLive extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestP52SequentialInpaintLive.class);

	static final File OUT = new File("target/p52");
	static final String STYLE_PATH = "~/p52styles";
	static final String READER = "p52Reader";
	static final String LANDSCAPE_FALLBACK = "./media/flux/landscape1.png";
	static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	static final byte[] CORRUPT_PORTRAIT = "p52: this approved portrait is deliberately not a PNG\n".getBytes(StandardCharsets.UTF_8);
	static final String[] CAST_GENDER = { "female", "male", "male" };
	static final String SCENE_TITLE = "Wet alley";
	static final Set<String> KNOWN_CODES = new HashSet<>(Arrays.asList(
		RendererAdapter.CODE_UNREACHABLE, RendererAdapter.CODE_NO_IMAGES, RendererAdapter.CODE_CANCELLED,
		RendererAdapter.CODE_RENDERER_ERROR, RendererAdapter.ERROR_GRAPH_INVALID, RendererAdapter.ERROR_RENDERER_REJECTED
	));
	/// SwarmGraphClient.parseNodeFailure's "node <id> <class> (<tags>): ..." for the first reference loader, the
	/// node a corrupt first-reference PNG fails in; both corrupt-reference cases put the bad bytes in that slot.
	static final Pattern NODE_ATTRIBUTION = Pattern.compile(
		"node \\d+ " + Pattern.quote(ComfyGraph.CLASS_LOAD_IMAGE_B64) + " \\([^)]*\\b" + Pattern.quote(PbNodeExecutor.referenceName(0)) + "\\b[^)]*\\)"
	);

	private String server;
	private String flux2Model;
	private SDUtil sdu;
	private SwarmGraphClient client;

	private OrganizationContext org;
	private BaseRecord owner;
	private BaseRecord olioUser;
	private long orgId;

	/// Stephen's cast, read once per case from book 58 and never written back.
	private String[] castNames;
	private byte[][] castPng;
	private int[][] castDims;
	private String[] castPhysical;
	private String[] castOutfit;
	private byte[] landscapePng;
	private int[] landscapeDims;
	private String landscapeSource;
	private String setting;
	private String action;
	private String mood;

	@Before
	public void liveSetup() throws Exception {
		Assume.assumeTrue(TestP51GraphLive.SKIP, TestP51GraphLive.gated());
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
		SdTestGate.resolveInstalledCheckpoints(testProperties);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SdTestGate.requireSwarmReachable(server);
		SwarmModelInventory.invalidateAll();
		flux2Model = prop("test.swarm.flux2Model", "flux2Klein_9b");
		sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		SdTestGate.requireModelInstalled(sdu, server, flux2Model, "the FLUX.2 plan-driven BASE / ACTOR_REGION render");
		client = new SwarmGraphClient(sdu);
		OUT.mkdirs();
		logger.info("[p52] LLM tier: none - this class is pure SD against " + server + " (Swarm " + client.swarmVersion() + ")");

		org = getTestOrganization(TestP51GraphLive.CUSTOM_ORG_PATH);
		assertNotNull("custom test organization", org);
		owner = getCreateUser(TestP51GraphLive.CUSTOM_OWNER, org);
		assertNotNull("owner " + TestP51GraphLive.CUSTOM_OWNER, owner);
		orgId = orgOf(owner);
		olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		readCast();
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t01 - DIRECT_REFERENCE                                                                             */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TestP51GraphLive.TIMEOUT_MS)
	public void t01_directReferenceTwoActors() throws Exception {
		Fixture f = null;
		try {
			f = fixture("t01", 2, -1);
			long t0 = System.currentTimeMillis();
			Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(owner, f.bookOid, f.sceneOid, "SWARM", server);
			log("t01 render returned in " + (System.currentTimeMillis() - t0) + " ms: " + summary(out));

			RenderPlan plan = planOf(out);
			assertEquals("strategy", PbStrategyEnumType.DIRECT_REFERENCE.name(), out.get("strategy"));
			assertEquals("stagesPlanned", List.of(RenderPlan.STAGE_BASE), strings(out, "stagesPlanned"));
			assertEquals("stagesRendered", List.of(RenderPlan.STAGE_BASE), strings(out, "stagesRendered"));
			assertTrue("no stage errors: " + out.get(PbNodeExecutor.KEY_STAGE_ERRORS), ((List<?>) out.get(PbNodeExecutor.KEY_STAGE_ERRORS)).isEmpty());
			assertEquals("calibrationPersisted", Boolean.TRUE, out.get(PbNodeExecutor.KEY_CALIBRATION_PERSISTED));
			assertTrue("no degradations: " + out.get("degradations"), strings(out, "degradations").isEmpty());
			for(RenderPlan.Actor a : plan.getActors()) {
				assertTrue(a.getName() + " referenced", a.isReferenced());
				assertEquals(a.getName() + " reference stage", RenderPlan.STAGE_BASE, a.getReferenceStage());
			}
			RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
			assertTrue("BASE pick resolved: " + pick.getReason(), pick.isResolved());
			RendererCapabilities caps = RendererCapabilities.forModel(pick.getModel());
			assertTrue("BASE pick '" + pick.getModel() + "' is a reference-capable family", caps.isSupportsReferences());
			log("t01 BASE pick " + pick.getModel() + " family " + pick.getFamily());

			BaseRecord node = node(out);
			assertEquals("node status", PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertTrue("no ROLE_STAGE artifact", PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_STAGE).isEmpty());

			List<BaseRecord> graphs = PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("one render graph envelope", 1, graphs.size());
			Map<String, Object> env = envelope(graphs.get(0));
			assertEquals("envelope stage", RenderPlan.STAGE_BASE, env.get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertNotNull("envelope graphHash", env.get(PbRenderPlanUtil.GRAPH_KEY_HASH));
			assertNotNull("envelope swarmVersion", env.get(PbRenderPlanUtil.GRAPH_KEY_SWARM_VERSION));
			assertNotNull("envelope graph", env.get(PbRenderPlanUtil.GRAPH_KEY_GRAPH));

			BaseRecord base = PbArtifactUtil.findSelected(owner, node, PbPipelineUtil.ROLE_BASE);
			assertNotNull("selected ROLE_BASE", base);
			assertEquals("artifactObjectId is the ROLE_BASE", oidOf(base), out.get("artifactObjectId"));
			Set<String> refs = referenceOids(base);
			Set<String> expected = new HashSet<>(Arrays.asList(f.portraitOids[0], f.portraitOids[1], f.landscapeOid));
			assertEquals("BASE references = two portraits + landscape", expected, refs);
			String backendGraph = base.get(OlioFieldNames.FIELD_PB_BACKEND_GRAPH);
			assertTrue("backendGraph recorded", backendGraph != null && !backendGraph.isBlank());
			assertEquals("mime", "image/png", base.get(OlioFieldNames.FIELD_PB_MIME_TYPE));
			assertEquals("width", plan.getCanvas().getWidth(), ((Number) base.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH)).intValue());
			assertEquals("height", plan.getCanvas().getHeight(), ((Number) base.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT)).intValue());

			byte[] png = artifactBytes(owner, base);
			int[] dims = ImageDims.decode(png);
			assertEquals("png width", plan.getCanvas().getWidth(), dims[0]);
			assertEquals("png height", plan.getCanvas().getHeight(), dims[1]);
			File outFile = new File(OUT, "p52-t01-direct.png");
			Files.write(outFile.toPath(), png);
			log("t01 wrote " + outFile.getPath() + " (" + png.length + " bytes, " + dims[0] + "x" + dims[1] + ")");

			BaseRecord cal = PbCalibrationUtil.findRow(owner, server, pick.getFamily(), RenderPlan.STAGE_BASE);
			assertNotNull("BASE calibration row for " + pick.getFamily(), cal);
			assertEquals("BASE lastSteps = preset steps", plan.getPreset().getSteps(), ((Number) cal.get(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS)).intValue());
			assertEquals("BASE lastReferences", 3, ((Number) cal.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES)).intValue());
			assertFalse("BASE row is measured, not seeded", Boolean.TRUE.equals(cal.get(OlioFieldNames.FIELD_SD_CAL_SEEDED)));
			log("t01 calibration " + pick.getFamily() + "/BASE sampleCount=" + cal.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT)
				+ " lastSteps=" + cal.get(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS) + " lastReferences=" + cal.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES));
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t02 - SEQUENTIAL_INPAINT                                                                           */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TestP51GraphLive.TIMEOUT_MS)
	public void t02_sequentialInpaintThreeActors() throws Exception {
		Fixture f = null;
		try {
			f = fixture("t02", 3, -1);
			long t0 = System.currentTimeMillis();
			Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(owner, f.bookOid, f.sceneOid, "SWARM", server);
			log("t02 render returned in " + (System.currentTimeMillis() - t0) + " ms: " + summary(out));

			RenderPlan plan = planOf(out);
			assertEquals("strategy", PbStrategyEnumType.SEQUENTIAL_INPAINT.name(), out.get("strategy"));
			RenderPlan.Actor third = actorByCharacter(plan.getActors(), f.charOids[2]);
			assertEquals("priority-2 actor is " + castNames[2], castNames[2], third.getName());
			assertEquals("priority-2 actor inpaints", RenderPlan.STAGE_ACTOR_REGION, third.getReferenceStage());
			String regionKey = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, third.getActorId());
			/// stagesPlanned is the strategy's stage list; only stagesRendered is keyed per actor.
			assertEquals("stagesPlanned", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION), strings(out, "stagesPlanned"));
			assertEquals("stagesRendered", List.of(RenderPlan.STAGE_BASE, regionKey), strings(out, "stagesRendered"));
			assertTrue("no stage errors: " + out.get(PbNodeExecutor.KEY_STAGE_ERRORS), ((List<?>) out.get(PbNodeExecutor.KEY_STAGE_ERRORS)).isEmpty());
			assertEquals("calibrationPersisted", Boolean.TRUE, out.get(PbNodeExecutor.KEY_CALIBRATION_PERSISTED));
			assertTrue("no degradations: " + out.get("degradations"), strings(out, "degradations").isEmpty());
			for(int i = 0; i < 2; i++) {
				RenderPlan.Actor a = actorByCharacter(plan.getActors(), f.charOids[i]);
				assertEquals(a.getName() + " is a BASE reference", RenderPlan.STAGE_BASE, a.getReferenceStage());
			}
			RenderPlan.ModelPick basePick = plan.pickForStage(RenderPlan.STAGE_BASE);
			RenderPlan.ModelPick inpaintPick = plan.pickForStage(RenderPlan.STAGE_ACTOR_REGION);
			assertTrue("BASE pick resolved", basePick.isResolved());
			assertTrue("ACTOR_REGION pick resolved", inpaintPick.isResolved());
			RendererCapabilities caps = RendererCapabilities.forModel(inpaintPick.getModel());
			assertTrue("inpaint pick supports masks", caps.isSupportsMasks());
			int effectiveSteps = caps.getInpaint().getSteps() - WorkflowCompiler.startAtStep(caps.getInpaint().getSteps(), caps.getInpaint().getCreativity());
			log("t02 picks BASE=" + basePick.getModel() + " (" + basePick.getFamily() + ") ACTOR_REGION=" + inpaintPick.getModel()
				+ " (" + inpaintPick.getFamily() + ") inpaint steps " + caps.getInpaint().getSteps() + " creativity " + caps.getInpaint().getCreativity()
				+ " effective " + effectiveSteps);

			BaseRecord node = node(out);
			assertEquals("node status", PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));

			List<BaseRecord> graphs = PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("two render graph envelopes", 2, graphs.size());
			Set<String> envStages = new HashSet<>();
			for(BaseRecord g : graphs) {
				Map<String, Object> env = envelope(g);
				envStages.add(String.valueOf(env.get(PbRenderPlanUtil.GRAPH_KEY_STAGE)));
				assertNotNull("envelope graphHash", env.get(PbRenderPlanUtil.GRAPH_KEY_HASH));
				assertNotNull("envelope graph", env.get(PbRenderPlanUtil.GRAPH_KEY_GRAPH));
			}
			assertEquals("envelope stages", new HashSet<>(Arrays.asList(RenderPlan.STAGE_BASE, regionKey)), envStages);

			List<BaseRecord> stages = PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_STAGE);
			assertEquals("one ROLE_STAGE artifact (the BASE output)", 1, stages.size());
			BaseRecord stage = PbArtifactUtil.readArtifact(owner, oidOf(stages.get(0)), orgId);
			Set<String> baseRefs = new HashSet<>(Arrays.asList(f.portraitOids[0], f.portraitOids[1], f.landscapeOid));
			assertEquals("ROLE_STAGE references = two portraits + landscape", baseRefs, referenceOids(stage));

			BaseRecord fin = PbArtifactUtil.findSelected(owner, node, PbPipelineUtil.ROLE_BASE);
			assertNotNull("selected ROLE_BASE", fin);
			assertEquals("artifactObjectId is the final ROLE_BASE", oidOf(fin), out.get("artifactObjectId"));
			Set<String> finalRefs = new HashSet<>(Arrays.asList(f.portraitOids[2], oidOf(stage)));
			assertEquals("final references = third portrait + the BASE stage artifact", finalRefs, referenceOids(fin));
			String backendGraph = fin.get(OlioFieldNames.FIELD_PB_BACKEND_GRAPH);
			assertTrue("final backendGraph recorded", backendGraph != null && !backendGraph.isBlank());

			byte[] basePng = artifactBytes(owner, stage);
			byte[] finalPng = artifactBytes(owner, fin);
			int[] bd = ImageDims.decode(basePng);
			int[] fd = ImageDims.decode(finalPng);
			assertEquals("base width", plan.getCanvas().getWidth(), bd[0]);
			assertEquals("base height", plan.getCanvas().getHeight(), bd[1]);
			assertEquals("final width", plan.getCanvas().getWidth(), fd[0]);
			assertEquals("final height", plan.getCanvas().getHeight(), fd[1]);
			File baseFile = new File(OUT, "p52-t02-base.png");
			File finalFile = new File(OUT, "p52-t02-final.png");
			Files.write(baseFile.toPath(), basePng);
			Files.write(finalFile.toPath(), finalPng);
			log("t02 wrote " + baseFile.getPath() + " (" + basePng.length + " bytes) and " + finalFile.getPath() + " (" + finalPng.length + " bytes), " + fd[0] + "x" + fd[1]);

			RenderPlan.Box box = third.getBox();
			assertNotNull("third actor box", box);
			int[] boxPx = {
				(int) Math.round(box.getX() * plan.getCanvas().getWidth()), (int) Math.round(box.getY() * plan.getCanvas().getHeight()),
				(int) Math.round(box.getW() * plan.getCanvas().getWidth()), (int) Math.round(box.getH() * plan.getCanvas().getHeight())
			};
			BufferedImage bi = ImageIO.read(new ByteArrayInputStream(basePng));
			BufferedImage fi = ImageIO.read(new ByteArrayInputStream(finalPng));
			int[] margin = TestP51GraphLive.untouchedMargin(bi, fi, boxPx, 128, 32);
			log("t02 box px " + Arrays.toString(boxPx) + " untouchedMargin " + Arrays.toString(margin) + " (observation only, never a threshold)");

			BaseRecord calBase = PbCalibrationUtil.findRow(owner, server, basePick.getFamily(), RenderPlan.STAGE_BASE);
			BaseRecord calRegion = PbCalibrationUtil.findRow(owner, server, inpaintPick.getFamily(), RenderPlan.STAGE_ACTOR_REGION);
			assertNotNull("BASE calibration row", calBase);
			assertNotNull("ACTOR_REGION calibration row", calRegion);
			assertEquals("BASE lastReferences", 3, ((Number) calBase.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES)).intValue());
			assertEquals("ACTOR_REGION lastSteps = effective (post-start) steps", effectiveSteps, ((Number) calRegion.get(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS)).intValue());
			assertEquals("ACTOR_REGION lastReferences", 1, ((Number) calRegion.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES)).intValue());
			log("t02 calibration ACTOR_REGION sampleCount=" + calRegion.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT)
				+ " lastSteps=" + calRegion.get(OlioFieldNames.FIELD_SD_CAL_LAST_STEPS) + " lastReferences=" + calRegion.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES));
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t03 - ACTOR_REGION failure degrades, BASE kept                                                     */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TestP51GraphLive.TIMEOUT_MS)
	public void t03_actorRegionFailureDegrades() throws Exception {
		Fixture f = null;
		try {
			f = fixture("t03", 3, 2);
			long t0 = System.currentTimeMillis();
			Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(owner, f.bookOid, f.sceneOid, "SWARM", server);
			log("t03 render returned in " + (System.currentTimeMillis() - t0) + " ms: " + summary(out));

			RenderPlan plan = planOf(out);
			assertEquals("strategy", PbStrategyEnumType.SEQUENTIAL_INPAINT.name(), out.get("strategy"));
			RenderPlan.Actor third = actorByCharacter(plan.getActors(), f.charOids[2]);
			String regionKey = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, third.getActorId());
			assertEquals("stagesPlanned", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION), strings(out, "stagesPlanned"));
			List<String> rendered = strings(out, "stagesRendered");
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> errors = (List<Map<String, Object>>) out.get(PbNodeExecutor.KEY_STAGE_ERRORS);
			if(rendered.contains(regionKey) || errors == null || errors.isEmpty()) {
				fail("corrupt reference did not fail the ACTOR_REGION pass; lever invalid - rendered=" + rendered + " errors=" + errors);
			}
			assertEquals("stagesRendered = BASE only", List.of(RenderPlan.STAGE_BASE), rendered);
			assertEquals("one stage error", 1, errors.size());
			Map<String, Object> err = errors.get(0);
			assertEquals("stage error stage", regionKey, err.get("stage"));
			String code = String.valueOf(err.get("code"));
			assertTrue("stage error code '" + code + "' is one of " + KNOWN_CODES, KNOWN_CODES.contains(code));
			if(RendererAdapter.CODE_UNREACHABLE.equals(code) || RendererAdapter.CODE_CANCELLED.equals(code)) {
				fail("ACTOR_REGION failed as " + code + " - the renderer dropped out mid-run, not the corrupt-reference lever: " + err);
			}
			String message = String.valueOf(err.get("message"));
			assertTrue("message attributes the failure to the pass's reference loader node (" + NODE_ATTRIBUTION.pattern() + "): " + message,
				NODE_ATTRIBUTION.matcher(message).find());
			log("t03 OBSERVED ACTOR_REGION failure code=" + code + " message=" + message + " detail=" + err.get("detail"));

			List<String> degradations = strings(out, "degradations");
			String prefix = RenderPlan.Degradation.ACTOR_FAILED + ": ACTOR_REGION pass for " + castNames[2] + " failed: ";
			boolean found = false;
			for(String d : degradations) {
				if(d.startsWith(prefix)) found = true;
			}
			assertTrue("degradations carry '" + prefix + "...': " + degradations, found);
			assertEquals("autoApproved", Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));

			BaseRecord node = node(out);
			assertEquals("node status", PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertTrue("no ROLE_STAGE artifact (the pass never produced one)", PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			List<BaseRecord> graphs = PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("one render graph envelope (BASE)", 1, graphs.size());
			assertEquals("envelope stage", RenderPlan.STAGE_BASE, envelope(graphs.get(0)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));

			BaseRecord fin = PbArtifactUtil.findSelected(owner, node, PbPipelineUtil.ROLE_BASE);
			assertNotNull("selected ROLE_BASE (the kept BASE output)", fin);
			Set<String> baseRefs = new HashSet<>(Arrays.asList(f.portraitOids[0], f.portraitOids[1], f.landscapeOid));
			assertEquals("final references = the BASE references", baseRefs, referenceOids(fin));

			BaseRecord planArt = PbArtifactUtil.readArtifact(owner, (String) out.get("planArtifactObjectId"), orgId);
			RenderPlan persisted = PbRenderPlanUtil.readPlanArtifact(planArt);
			assertNotNull("plan artifact re-read", persisted);
			boolean planCarries = false;
			for(RenderPlan.Degradation d : persisted.getDegradations()) {
				if(RenderPlan.Degradation.ACTOR_FAILED.equals(d.getCode()) && third.getActorId().equals(d.getActorId())) planCarries = true;
			}
			assertTrue("plan artifact carries ACTOR_FAILED for " + castNames[2] + ": " + persisted.getDegradations(), planCarries);

			byte[] png = artifactBytes(owner, fin);
			int[] dims = ImageDims.decode(png);
			File outFile = new File(OUT, "p52-t03-base-kept.png");
			Files.write(outFile.toPath(), png);
			log("t03 wrote " + outFile.getPath() + " (" + png.length + " bytes, " + dims[0] + "x" + dims[1] + ")");
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t04 - BASE failure surfaces on the node, host-free                                                 */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TestP51GraphLive.TIMEOUT_MS)
	public void t04_graphInvalidSurfacesNode() throws Exception {
		Fixture f = null;
		try {
			f = fixture("t04", 2, 0);
			BaseRecord wf = PbGraphUtil.findWorkflow(owner, PbBookUtil.readBook(owner, f.bookOid, orgId));
			assertNotNull("fixture workflow", wf);
			long newestBefore = newestRunId(owner, wf);
			long t0 = System.currentTimeMillis();
			PictureBookException caught = null;
			try {
				Map<String, Object> out = PbServiceFacade.requestRenderPlanScene(owner, f.bookOid, f.sceneOid, "SWARM", server);
				fail("corrupt BASE reference did not fail the render; lever invalid - " + summary(out));
			}
			catch(PictureBookException e) {
				caught = e;
			}
			log("t04 render refused in " + (System.currentTimeMillis() - t0) + " ms: " + caught.getStatus() + " " + caught.getMessage());
			assertTrue("status 500 (503 only when the renderer was unreachable): " + caught.getStatus(), caught.getStatus() == 500 || caught.getStatus() == 503);
			assertTrue("message names the scene: " + caught.getMessage(), caught.getMessage().startsWith("Render of scene 0 failed:"));
			assertTrue("message names the plan artifact", caught.getMessage().contains("(plan artifact "));
			assertFalse("message is host-free", caught.getMessage().contains("127.0.0.1") || caught.getMessage().contains("192.168.") || caught.getMessage().contains("http://"));
			String planOid = planOidOf(caught);
			if(caught instanceof PbNodeExecutor.PbStageFailedException) {
				List<Map<String, Object>> errs = ((PbNodeExecutor.PbStageFailedException) caught).getStageErrors();
				assertNotNull("stage errors travel with the exception", errs);
				assertEquals("one stage error", 1, errs.size());
				Map<String, Object> err = errs.get(0);
				assertEquals("stage", RenderPlan.STAGE_BASE, err.get("stage"));
				String code = String.valueOf(err.get("code"));
				assertTrue("code '" + code + "' is one of " + KNOWN_CODES, KNOWN_CODES.contains(code));
				if(RendererAdapter.CODE_UNREACHABLE.equals(code) || RendererAdapter.CODE_CANCELLED.equals(code)) {
					fail("BASE failed as " + code + " - the renderer dropped out mid-run, not the corrupt-reference lever: " + err);
				}
				String message = String.valueOf(err.get("message"));
				assertTrue("message attributes the failure to the BASE reference loader node (" + NODE_ATTRIBUTION.pattern() + "): " + message,
					NODE_ATTRIBUTION.matcher(message).find());
				log("t04 OBSERVED BASE failure code=" + code + " message=" + message + " detail=" + err.get("detail"));
			}
			else {
				fail("a BASE stage failure must surface as PbStageFailedException with its stage errors, not a plain "
					+ caught.getClass().getName() + ": " + caught.getMessage());
			}

			BaseRecord node = PbPipelineUtil.findNodeByHandle(owner, wf, PbPipelineUtil.renderPlanHandle(f.sceneOid));
			assertNotNull("render plan node", node);
			BaseRecord fresh = PbGraphUtil.readNode(owner, oidOf(node), orgId);
			assertEquals("node FAILED", PbNodeStatusEnumType.FAILED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
			String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
			assertNotNull("lastError", lastError);
			assertTrue("lastError names the plan artifact: " + lastError, lastError.contains("plan artifact " + planOid));
			assertFalse("lastError is host-free", lastError.contains("127.0.0.1") || lastError.contains("192.168.") || lastError.contains("http://"));
			assertTrue("no ROLE_BASE artifact", PbArtifactUtil.listChain(owner, fresh, PbPipelineUtil.ROLE_BASE).isEmpty());
			assertTrue("no ROLE_STAGE artifact", PbArtifactUtil.listChain(owner, fresh, PbPipelineUtil.ROLE_STAGE).isEmpty());
			BaseRecord planArt = PbArtifactUtil.findSelected(owner, fresh, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertNotNull("plan artifact persisted before the render", planArt);
			assertEquals("selected plan is the one the message names", planOid, oidOf(planArt));
			assertNotNull("plan artifact parses", PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(owner, planOid, orgId)));

			BaseRecord run = latestRun(owner, wf);
			assertNotNull("a run was recorded", run);
			long runId = ((Number) run.get(FieldNames.FIELD_ID)).longValue();
			assertTrue("the run is new (" + runId + " > " + newestBefore + ")", runId > newestBefore);
			assertEquals("run FAILED", PbRunStatusEnumType.FAILED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			String runError = run.get(OlioFieldNames.FIELD_PB_ERROR);
			assertNotNull("run error", runError);
			log("t04 node lastError=" + lastError + " | run " + runId + " error=" + runError);
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t05 - estimate read as another user never creates a calibration group                             */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TestP51GraphLive.TIMEOUT_MS)
	public void t05_calibrationReadNeverCreates() throws Exception {
		BaseRecord reader = getCreateUser(READER, org);
		assertNotNull("reader " + READER, reader);
		long readerOrg = orgOf(reader);
		assertEquals("reader shares the owner's organization", orgId, readerOrg);
		BaseRecord bk = null;
		BaseRecord style = null;
		try {
			assertNull("reader has no calibration group before", calibrationGroup(reader));
			String slug = "p52rdr" + TestP51GraphLive.shortId();
			bk = PbBookUtil.createBook(reader, dataPath(), slug, "P52 reader " + slug);
			assertNotNull("reader book", bk);
			bookContext(reader, bk);
			BaseRecord scene = addScene(reader, bk, 0, "Reader scene", setting, null, null);
			style = pinBookStyle(reader, PbBookUtil.readBook(reader, oidOf(bk), readerOrg), "reader", flux2Model);

			Map<String, Object> out = PbServiceFacade.planScene(reader, oidOf(bk), oidOf(scene), null, "SWARM", server);
			assertNotNull("planScene", out);
			@SuppressWarnings("unchecked")
			Map<String, Object> estimate = (Map<String, Object>) out.get("estimate");
			assertNotNull("estimate", estimate);
			@SuppressWarnings("unchecked")
			Map<String, Object> sources = (Map<String, Object>) estimate.get("sources");
			assertNotNull("estimate.sources", sources);
			String src = String.valueOf(sources.get(RenderPlan.STAGE_BASE));
			log("t05 estimate BASE source: " + src + " | estimate " + estimate);
			assertFalse("a reader's estimate never claims the owner's rows as 'your renders': " + src, src.startsWith(CalibrationSample.SOURCE_PERSISTED_OWN));
			assertTrue("source is a recognised kind: " + src,
				src.startsWith(CalibrationSample.SOURCE_MEASURED) || src.equals(CalibrationSample.SOURCE_SEED)
				|| src.equals(RenderEstimate.SOURCE_NONE) || src.startsWith(CalibrationSample.SOURCE_PERSISTED_OTHER));
			assertNull("reader still has no calibration group after", calibrationGroup(reader));
			assertNull("the estimate carries no server URL", find(out, server));
		}
		finally {
			deleteBook(reader, bk);
			deleteStyle(reader, style);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Fixture                                                                                           */
	/* ------------------------------------------------------------------------------------------------ */

	static final class Fixture {
		String slug;
		String bookOid;
		String sceneOid;
		String locationOid;
		String landscapeOid;
		String[] charOids;
		String[] portraitOids;
		BaseRecord style;
	}

	/**
	 * A fresh V3 book with {@code actorCount} of Stephen's cast (in {@link TestP51GraphLive#CAST_ORDER}), each
	 * with narrative + an approved PORTRAIT artifact carrying the real portrait bytes - except index
	 * {@code corruptIndex} (when >= 0), whose approved portrait is {@link #CORRUPT_PORTRAIT} stamped as 64x96 -
	 * plus an EXTERIOR location with an approved LANDSCAPE render, a composed scene with every actor boxed in
	 * priority order, and the book style pinned to the FLUX.2 checkpoint for all four tasks.
	 */
	private Fixture fixture(String tag, int actorCount, int corruptIndex) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p52seq" + tag + TestP51GraphLive.shortId();
		BaseRecord bk = PbBookUtil.createBook(owner, dataPath(), f.slug, "P52 " + tag + " " + f.slug);
		assertNotNull("fixture book", bk);
		f.bookOid = oidOf(bk);
		try {
			BookContext bctx = bookContext(owner, bk);
			BaseRecord popGroup = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("population group", popGroup);

			BaseRecord location = PbLocationUtil.getCreateLocation(owner, PbBookUtil.readBook(owner, f.bookOid, orgId), "Wet alley",
				setting, PbLocationKindEnumType.EXTERIOR, List.of("the alley"));
			assertNotNull("location", location);
			f.locationOid = oidOf(location);
			BaseRecord scene = addScene(owner, bk, 0, SCENE_TITLE, setting, null, location);
			f.sceneOid = oidOf(scene);
			f.landscapeOid = approveLandscape(owner, bk, f.locationOid);

			f.charOids = new String[actorCount];
			f.portraitOids = new String[actorCount];
			long[] charIds = new long[actorCount];
			for(int i = 0; i < actorCount; i++) {
				f.charOids[i] = createCharPersonIn(owner, popGroup, popPath, castNames[i], CAST_GENDER[i]);
				BaseRecord cp = readChar(owner, f.charOids[i]);
				charIds[i] = idOf(cp);
				giveNarrative(owner, cp, castPhysical[i], castOutfit[i].isEmpty() ? null : castOutfit[i]);
				enrol(owner, scene, cp);
			}
			BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			for(int i = 0; i < actorCount; i++) {
				boolean corrupt = (i == corruptIndex);
				byte[] bytes = corrupt ? CORRUPT_PORTRAIT : castPng[i];
				int w = corrupt ? 64 : castDims[i][0];
				int h = corrupt ? 96 : castDims[i][1];
				BaseRecord art = approvedPortraitFor(owner, book, f.slug, f.charOids[i], bytes, w, h);
				f.portraitOids[i] = oidOf(art);
				log(tag + " portrait " + castNames[i] + " -> artifact " + f.portraitOids[i] + (corrupt ? " (CORRUPT, " + bytes.length + " non-PNG bytes stamped " + w + "x" + h + ")" : " (" + bytes.length + " bytes " + w + "x" + h + ")"));
			}

			ComposeResult cr = PbComposeUtil.composeMigrated(owner, book, scene, false);
			assertNotNull("compose", cr);
			assertTrue("compose written: " + cr.failureReason, cr.isWritten());
			BaseRecord comp = PbComposeUtil.readComposition(owner, oidOf(cr.composition), orgId);
			List<BaseRecord> rows = PbComposeUtil.listActors(owner, comp);
			assertEquals("actor rows", actorCount, rows.size());
			for(int i = 0; i < actorCount; i++) {
				placeActor(owner, rowByCharacter(rows, charIds[i]), i, 0.04 + i * 0.32, 0.15, 0.28, 0.80);
			}
			f.style = pinBookStyle(owner, book, tag, flux2Model);
			log(tag + " fixture book " + f.slug + " scene " + f.sceneOid + " location " + f.locationOid + " landscape " + f.landscapeOid
				+ " (" + landscapeSource + ") actors " + Arrays.toString(f.charOids));
			return f;
		}
		catch(Exception | AssertionError e) {
			cleanup(f);
			throw e;
		}
	}

	private void cleanup(Fixture f) {
		if(f == null) return;
		if(f.bookOid != null) {
			try {
				DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, f.bookOid);
				log("cleanup " + f.slug + " deleted=" + res.deleted + " reason=" + res.reason);
			}
			catch(Exception e) {
				logger.error("cleanup " + f.slug + " failed", e);
			}
		}
		deleteStyle(owner, f.style);
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) return;
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			log("cleanup " + bk.get(OlioFieldNames.FIELD_PB_SLUG) + " deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup book failed", e);
		}
	}

	private void deleteStyle(BaseRecord u, BaseRecord style) {
		if(style == null) return;
		try {
			ioContext.getAccessPoint().delete(u, style);
		}
		catch(Exception e) {
			logger.error("cleanup style failed", e);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Book 58 (read-only)                                                                               */
	/* ------------------------------------------------------------------------------------------------ */

	private void readCast() throws Exception {
		int n = TestP51GraphLive.CAST_ORDER.length;
		castNames = new String[n];
		castPng = new byte[n][];
		castDims = new int[n][];
		castPhysical = new String[n];
		castOutfit = new String[n];
		BaseRecord book = PbBookUtil.findBookBySlug(owner, TestP51GraphLive.CUSTOM_BOOK_SLUG, orgId);
		assertNotNull("book " + TestP51GraphLive.CUSTOM_BOOK_SLUG + " as " + TestP51GraphLive.CUSTOM_OWNER, book);
		List<BaseRecord> scenes = PbBookUtil.listScenes(owner, book);
		assertTrue("book 58 has scenes", scenes != null && !scenes.isEmpty());
		BaseRecord s0 = scenes.get(0);
		setting = TestP51GraphLive.text(s0.get(OlioFieldNames.FIELD_PB_SETTING), TestP51GraphLive.FALLBACK_SETTING);
		action = TestP51GraphLive.text(s0.get(OlioFieldNames.FIELD_PB_ACTION), TestP51GraphLive.FALLBACK_ACTION);
		mood = TestP51GraphLive.text(s0.get(OlioFieldNames.FIELD_PB_MOOD), TestP51GraphLive.FALLBACK_MOOD);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		List<Map<String, Object>> chars = PictureBookUtil.listCharacters(owner, bookOid);
		assertTrue("book 58 has characters", chars != null && !chars.isEmpty());
		for(int i = 0; i < n; i++) {
			String name = TestP51GraphLive.CAST_ORDER[i];
			String oid = null;
			for(Map<String, Object> c : chars) {
				if(name.equals(c.get("name"))) {
					oid = (String) c.get("objectId");
					break;
				}
			}
			assertNotNull("book 58 character " + name, oid);
			BaseRecord person = TestP51GraphLive.readPerson(owner, oid, orgId);
			BaseRecord profile = person.get(FieldNames.FIELD_PROFILE);
			assertNotNull(name + " profile", profile);
			BaseRecord portrait = profile.get("portrait");
			assertNotNull(name + " portrait", portrait);
			String portraitOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull(name + " portrait objectId", portraitOid);
			BaseRecord data = TestP51GraphLive.freshReadData(owner, portraitOid, orgId);
			if(data == null && olioUser != null) {
				data = TestP51GraphLive.freshReadData(olioUser, portraitOid, orgId);
			}
			assertNotNull(name + " portrait data", data);
			byte[] png = ByteModelUtil.getValue(data);
			assertTrue(name + " portrait bytes", png != null && png.length > 1024);
			assertPng(name + " portrait", png);
			castNames[i] = name;
			castPng[i] = png;
			castDims[i] = ImageDims.decode(png);
			BaseRecord narrative = person.get(OlioFieldNames.FIELD_NARRATIVE);
			castPhysical[i] = (narrative != null ? TestP51GraphLive.text(narrative.get("physicalDescription"), "") : "");
			castOutfit[i] = (narrative != null ? TestP51GraphLive.text(narrative.get("outfitDescription"), "") : "");
			if(castPhysical[i].isEmpty()) {
				castPhysical[i] = name + ", as drawn in the portrait";
			}
			log("cast " + name + " portrait " + castDims[i][0] + "x" + castDims[i][1] + " (" + png.length + " bytes) physical=" + castPhysical[i].length() + " chars outfit=" + castOutfit[i].length() + " chars");
		}
		readLandscape(book, s0);
	}

	/// Scene 0's selected LANDSCAPE render from book 58 when one exists; else the checked-in stand-in.
	private void readLandscape(BaseRecord book58, BaseRecord s0) throws Exception {
		landscapePng = null;
		try {
			BaseRecord wf = PbGraphUtil.findWorkflow(owner, book58);
			BaseRecord node = (wf != null ? PbPipelineUtil.findNodeByHandle(owner, wf, PbPipelineUtil.landscapeHandle(oidOf(s0))) : null);
			if(node != null) {
				List<BaseRecord> chain = PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_LANDSCAPE);
				if(chain == null || chain.isEmpty()) {
					chain = PbArtifactUtil.listChain(olioUser, node, PbPipelineUtil.ROLE_LANDSCAPE);
				}
				if(chain != null && !chain.isEmpty()) {
					BaseRecord art = chain.get(chain.size() - 1);
					BaseRecord dataRef = art.get(OlioFieldNames.FIELD_PB_DATA);
					String dataOid = (dataRef != null ? dataRef.get(FieldNames.FIELD_OBJECT_ID) : null);
					if(dataRef != null && dataOid == null) {
						IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
						dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
					}
					BaseRecord data = (dataOid != null ? TestP51GraphLive.freshReadData(owner, dataOid, orgId) : null);
					if(data == null && dataOid != null && olioUser != null) {
						data = TestP51GraphLive.freshReadData(olioUser, dataOid, orgId);
					}
					byte[] bytes = (data != null ? ByteModelUtil.getValue(data) : null);
					if(bytes != null && bytes.length > 1024 && isPng(bytes)) {
						landscapePng = bytes;
						landscapeSource = "book 58 scene 0 landscape artifact " + oidOf(art);
					}
				}
			}
		}
		catch(Exception e) {
			logger.warn("book 58 landscape not readable (" + e.getMessage() + "); using the stand-in");
		}
		if(landscapePng == null) {
			landscapePng = Files.readAllBytes(new File(LANDSCAPE_FALLBACK).toPath());
			landscapeSource = "stand-in " + LANDSCAPE_FALLBACK;
			logger.warn("[p52] no landscape render on book 58 scene 0; using " + LANDSCAPE_FALLBACK);
		}
		landscapeDims = ImageDims.decode(landscapePng);
		log("landscape " + landscapeSource + " " + landscapeDims[0] + "x" + landscapeDims[1] + " (" + landscapePng.length + " bytes)");
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Fixture helpers (the TestPbRenderPlanActors / TestPbScenePlanLocation idioms)                     */
	/* ------------------------------------------------------------------------------------------------ */

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());
		return bctx;
	}

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String sceneSetting, String sdPrompt, BaseRecord location) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene", scene);
		List<String> fields = new ArrayList<>();
		fields.add(OlioFieldNames.FIELD_PB_SETTING);
		if(sdPrompt != null) fields.add(OlioFieldNames.FIELD_CB_SD_PROMPT);
		if(location != null) fields.add(FieldNames.FIELD_LOCATION);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
		patch.set(OlioFieldNames.FIELD_PB_SETTING, sceneSetting);
		if(sdPrompt != null) patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
		if(location != null) patch.set(FieldNames.FIELD_LOCATION, location);
		assertNotNull("scene patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		if(location != null) {
			assertEquals("scene location", idOf(location), fkId(fresh, FieldNames.FIELD_LOCATION));
		}
		return fresh;
	}

	private String approveLandscape(BaseRecord u, BaseRecord bk, String locOid) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		BaseRecord n = PbGraphUtil.addNode(u, wf, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("location node", n);
		assertTrue("scope location node", PbPipelineUtil.patchNodeScope(u, wf, n, PbPipelineUtil.SCOPE_LOCATION, locOid, 0));
		BaseRecord data = persistPng(u, PbBookUtil.artifactGroupPath(slug), "loc-" + TestP51GraphLive.shortId() + ".png", landscapePng);
		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE, PbBookUtil.artifactGroupPath(slug), data, null, null, null);
		assertNotNull("landscape artifact", a);
		assertTrue("landscape metrics", PbArtifactUtil.recordImageMetrics(u, a, landscapePng, "image/png", landscapeDims[0], landscapeDims[1], null));
		Map<String, Object> approved = PbServiceFacade.approveArtifact(u, oidOf(bk), oidOf(a));
		assertEquals("approve names the location node", oidOf(n), approved.get("nodeObjectId"));
		BaseRecord loc = PbLocationUtil.readLocation(u, locOid, orgOf(u));
		assertNotNull("location re-read", loc);
		assertEquals("approvedRender", idOf(a), fkId(loc, OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return oidOf(a);
	}

	private BaseRecord approvedPortraitFor(BaseRecord u, BaseRecord book, String slug, String charOid, byte[] bytes, int w, int h) throws Exception {
		long oid = orgOf(u);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, book, PbBookUtil.workflowGroupPath(slug));
		BaseRecord pNode = PbGraphUtil.addNode(u, wf, PbPipelineUtil.portraitHandle(charOid), PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("portrait node", pNode);
		String groupPath = PbBookUtil.artifactGroupPath(slug);
		BaseRecord data = persistPng(u, groupPath, "p52-portrait-" + TestP51GraphLive.shortId() + ".png", bytes);
		BaseRecord art = PbArtifactUtil.persistArtifact(u, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE, groupPath, data, null, null, null);
		assertNotNull("portrait artifact", art);
		assertTrue("portrait metrics", PbArtifactUtil.recordImageMetrics(u, art, bytes, "image/png", w, h, null));
		assertNotNull("approve", PbArtifactUtil.approve(u, PbArtifactUtil.readArtifact(u, oidOf(art), oid), "p52"));
		assertEquals("portrait approved", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(PbArtifactUtil.readArtifact(u, oidOf(art), oid)));
		return art;
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

	private BaseRecord pinBookStyle(BaseRecord u, BaseRecord bk, String tag, String model) throws Exception {
		long oid = orgOf(u);
		Map<String, String> pins = new LinkedHashMap<>();
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] { PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION, PbModelTaskEnumType.SCENE_DIRECT, PbModelTaskEnumType.INPAINT }) {
			pins.put(ModelTaskKeys.key(t), model);
		}
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p52-" + tag + "-" + TestP51GraphLive.shortId(), STYLE_PATH, oid);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
		style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, JSONUtil.exportObject(pins));
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("style " + tag, created);
		BaseRecord styleRef = PbStyleUtil.readStyle(u, oidOf(created), oid);
		assertNotNull("style re-read", styleRef);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE });
		patch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, bk.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_STYLE, styleRef);
		assertNotNull("book style patch " + tag, ioContext.getAccessPoint().update(u, patch));
		CacheUtil.clearCache();
		BaseRecord after = PbBookUtil.readBook(u, oidOf(bk), oid);
		BaseRecord linked = after.get(OlioFieldNames.FIELD_STYLE);
		assertNotNull("book style linked", linked);
		assertEquals("book style is " + tag, oidOf(styleRef), oidOf(linked));
		return styleRef;
	}

	private void placeActor(BaseRecord u, BaseRecord row, int priority, double x, double y, double w, double h) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_PB_PRIORITY,
			OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H);
		patch.set(OlioFieldNames.FIELD_PB_PRIORITY, priority);
		patch.set(OlioFieldNames.FIELD_PB_X, x);
		patch.set(OlioFieldNames.FIELD_PB_Y, y);
		patch.set(OlioFieldNames.FIELD_PB_W, w);
		patch.set(OlioFieldNames.FIELD_PB_H, h);
		assertNotNull("place actor row " + row.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME), ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}

	private BaseRecord readChar(BaseRecord u, String oid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID });
		q.setCache(false);
		BaseRecord cp = ioContext.getAccessPoint().find(u, q);
		assertNotNull("character " + oid, cp);
		return cp;
	}

	private void giveNarrative(BaseRecord u, BaseRecord cp, String physical, String outfit) throws Exception {
		BaseRecord narrative = PbSubRecordUtil.createSubRecord(u, null, OlioModelNames.MODEL_NARRATIVE);
		assertNotNull("narrative", narrative);
		List<String> fields = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "physicalDescription"));
		if(outfit != null) fields.add("outfitDescription");
		BaseRecord np = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE, fields.toArray(new String[0]));
		np.set(FieldNames.FIELD_ID, narrative.get(FieldNames.FIELD_ID));
		np.set(FieldNames.FIELD_OBJECT_ID, narrative.get(FieldNames.FIELD_OBJECT_ID));
		np.set("physicalDescription", physical);
		if(outfit != null) np.set("outfitDescription", outfit);
		assertNotNull("narrative patch", ioContext.getAccessPoint().update(u, np));
		BaseRecord lp = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON, new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative" });
		lp.set(FieldNames.FIELD_ID, cp.get(FieldNames.FIELD_ID));
		lp.set(FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		lp.set(FieldNames.FIELD_NAME, cp.get(FieldNames.FIELD_NAME));
		lp.set("narrative", narrative);
		assertNotNull("narrative link patch", ioContext.getAccessPoint().update(u, lp));
	}

	private static final String[] CHAR_SUB_RECORD_FIELDS = new String[] {
		FieldNames.FIELD_PROFILE, OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE, OlioFieldNames.FIELD_INSTINCT,
		FieldNames.FIELD_BEHAVIOR, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
	};

	private static String createCharPersonIn(BaseRecord user, BaseRecord group, String groupPath, String name, String gender) throws Exception {
		long groupId = ((Number) group.get(FieldNames.FIELD_ID)).longValue();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, name.split(" ")[0]);
		cp.set(FieldNames.FIELD_GENDER, gender);
		for(String f : CHAR_SUB_RECORD_FIELDS) {
			BaseRecord sub = cp.get(f);
			if(sub != null) sub.set(FieldNames.FIELD_GROUP_ID, groupId);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("create character " + name, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static BaseRecord rowByCharacter(List<BaseRecord> rows, long charId) {
		for(BaseRecord r : rows) {
			if(fkId(r, OlioFieldNames.FIELD_PB_CHARACTER) == charId) return r;
		}
		fail("no actor row for character id " + charId);
		return null;
	}

	private static RenderPlan.Actor actorByCharacter(List<RenderPlan.Actor> actors, String charOid) {
		for(RenderPlan.Actor a : actors) {
			if(charOid.equals(a.getCharacterId())) return a;
		}
		fail("no plan actor for character " + charOid);
		return null;
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Result helpers                                                                                    */
	/* ------------------------------------------------------------------------------------------------ */

	private RenderPlan planOf(Map<String, Object> out) {
		String planOid = (String) out.get("planArtifactObjectId");
		assertNotNull("planArtifactObjectId", planOid);
		BaseRecord art = PbArtifactUtil.readArtifact(owner, planOid, orgId);
		assertNotNull("plan artifact " + planOid, art);
		assertEquals("plan role", PbPipelineUtil.ROLE_RENDER_PLAN, art.get(OlioFieldNames.FIELD_PB_ROLE));
		RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(art);
		assertNotNull("plan parses", plan);
		assertEquals("planContentHash", plan.contentHash(), out.get("planContentHash"));
		assertEquals("executed", Boolean.TRUE, out.get("executed"));
		assertEquals("renderPath", "v3", out.get("renderPath"));
		return plan;
	}

	private BaseRecord node(Map<String, Object> out) {
		String nodeOid = (String) out.get("nodeObjectId");
		assertNotNull("nodeObjectId", nodeOid);
		BaseRecord node = PbGraphUtil.readNode(owner, nodeOid, orgId);
		assertNotNull("node " + nodeOid, node);
		assertEquals("node type", PbNodeTypeEnumType.RENDER_PLAN.name(), status(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
		return node;
	}

	private Map<String, Object> envelope(BaseRecord graphArtifact) {
		BaseRecord full = PbArtifactUtil.readArtifact(owner, oidOf(graphArtifact), orgId);
		assertNotNull("graph artifact re-read", full);
		Map<String, Object> env = PbRenderPlanUtil.readGraphEnvelope(full);
		assertNotNull("graph envelope " + oidOf(graphArtifact), env);
		return env;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> referenceOids(BaseRecord artifact) {
		String req = artifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
		assertNotNull("generatorRequest on " + oidOf(artifact), req);
		assertFalse("generatorRequest carries no session_id", req.contains("session_id"));
		Map<String, Object> m = JSONUtil.importObject(req, Map.class);
		assertNotNull("generatorRequest parses", m);
		Object refs = m.get(PbArtifactUtil.REFERENCE_ARTIFACTS_KEY);
		assertTrue("referenceArtifactObjectIds is a list: " + refs, refs instanceof List);
		Set<String> out = new HashSet<>();
		for(Object o : (List<Object>) refs) out.add(String.valueOf(o));
		return out;
	}

	private byte[] artifactBytes(BaseRecord u, BaseRecord artifact) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("artifact points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = TestP51GraphLive.freshReadData(u, dataOid, orgOf(u));
		assertNotNull("data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		assertPng("artifact " + oidOf(artifact), bytes);
		return bytes;
	}

	private static void assertPng(String what, byte[] bytes) {
		assertTrue(what + " is a PNG", isPng(bytes));
	}

	private static boolean isPng(byte[] bytes) {
		if(bytes == null || bytes.length < PNG_MAGIC.length) return false;
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			if(bytes[i] != PNG_MAGIC[i]) return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(Map<String, Object> out, String key) {
		Object v = out.get(key);
		assertTrue(key + " is a list: " + v, v instanceof List);
		List<String> res = new ArrayList<>();
		for(Object o : (List<Object>) v) res.add(String.valueOf(o));
		return res;
	}

	private static String planOidOf(PictureBookException e) {
		String msg = e.getMessage();
		String marker = "(plan artifact ";
		int at = msg.lastIndexOf(marker);
		assertTrue("message names the plan artifact: " + msg, at >= 0);
		int end = msg.indexOf(')', at);
		assertTrue("plan artifact marker closed: " + msg, end > at);
		return msg.substring(at + marker.length(), end).trim();
	}

	private BaseRecord calibrationGroup(BaseRecord u) {
		return ioContext.getPathUtil().findPath(u, ModelNames.MODEL_GROUP, PbCalibrationUtil.CALIBRATION_PATH, GroupEnumType.DATA.toString(), orgOf(u));
	}

	/// Depth-first search of a DTO for a string containing {@code needle}; null when absent.
	private static String find(Object v, String needle) {
		if(v == null || needle == null) return null;
		if(v instanceof String) return ((String) v).contains(needle) ? (String) v : null;
		if(v instanceof Map) {
			for(Object o : ((Map<?, ?>) v).values()) {
				String hit = find(o, needle);
				if(hit != null) return hit;
			}
		}
		if(v instanceof List) {
			for(Object o : (List<?>) v) {
				String hit = find(o, needle);
				if(hit != null) return hit;
			}
		}
		return null;
	}

	private Query runsQuery(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		return q;
	}

	private BaseRecord latestRun(BaseRecord u, BaseRecord wf) {
		BaseRecord[] recs = ioContext.getAccessPoint().list(u, runsQuery(u, wf)).getResults();
		if(recs == null || recs.length == 0) return null;
		BaseRecord best = null;
		long bestId = -1L;
		for(BaseRecord r : recs) {
			if(r.getSchema() == null) r.setSchema(OlioModelNames.MODEL_PB_RUN);
			long id = ((Number) r.get(FieldNames.FIELD_ID)).longValue();
			if(id > bestId) {
				bestId = id;
				best = r;
			}
		}
		return best;
	}

	private long newestRunId(BaseRecord u, BaseRecord wf) {
		BaseRecord r = latestRun(u, wf);
		return (r != null ? ((Number) r.get(FieldNames.FIELD_ID)).longValue() : -1L);
	}

	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? v.toString().toUpperCase() : null);
	}

	private static String summary(Map<String, Object> out) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(String k : new String[] { "strategy", "stagesPlanned", "stagesRendered", PbNodeExecutor.KEY_STAGE_ERRORS,
				PbNodeExecutor.KEY_CALIBRATION_PERSISTED, "degradations", "nodeStatus", "artifactObjectId", "planArtifactObjectId", "runStatus",
				PbNodeExecutor.KEY_AUTO_APPROVED, "planSummary" }) {
			if(out.containsKey(k)) m.put(k, out.get(k));
		}
		return m.toString();
	}

	private String prop(String key, String def) {
		String v = testProperties.getProperty(key);
		return (v != null && !v.isBlank() ? v.trim() : def);
	}

	private static void log(String msg) {
		logger.info("[p52] " + msg);
	}
}
