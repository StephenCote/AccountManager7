package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 10, P10-B 1 (PictureBookV3Implementation.md §6.C 260): a book straight out of the wizard
 * renders from the reader. Until this slice the wizard built scenes but never a graph: {@code scene.sceneNode} is
 * only ever set by the PB1 pipeline, so the reader's Render answered 409 "has no graph node yet; build the book's
 * graph before rendering it", Render all planned nothing, and the only thing the UI offered - "Open in wizard" -
 * cannot render under v3. The facade now creates the workflow and each scene's RENDER_PLAN terminal on first use,
 * after the 404 / 409-v3 / 403 gates and as the book's updater (the {@code executePlanScene} pattern), and
 * {@link PbServiceFacade#requestBuildGraph} does the same without rendering.
 * <p>
 * Every case starts from a fresh book of its own (random slug, three scenes with setting/action/mood, no workflow,
 * no node, no {@code sceneNode}) and deletes it afterwards. The offline cases prove the materialisation happens
 * after the gates and before the planner (the planner's own 500 "SD server not configured" is the hand-off
 * evidence, exactly as TestPbRenderJob case12 uses it); the live case renders one scene through
 * {@link PbServiceFacade#requestSceneRender} on the real {@code .39} backend and then proves Render all plans
 * only the two scenes that are not yet current. Timings are logged, never asserted.
 */
public class TestPbNewBookRender extends BaseTest {

	private static final String OWNER = "pbNewBookOwner";
	private static final String STRANGER = "pbNewBookStranger";
	private static final String[] SETTINGS = {
		"a lighthouse on a basalt headland at dusk",
		"a narrow cobbled lane under gas lamps, wet with rain",
		"a sunlit orchard with ladders against the apple trees"
	};
	private static final String[] ACTIONS = {
		"gulls wheel over the breakwater",
		"a figure in a long coat hurries past shuttered shops",
		"children carry baskets between the rows"
	};
	private static final String[] MOODS = { "windswept and expectant", "furtive and damp", "warm and unhurried" };
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// Render budgets are generous on purpose: a timing is never a pass/fail threshold here.
	private static final long RENDER_TIMEOUT_MS = 30L * 60L * 1000L;
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;
	private static final long POLL_MS = 250L;

	private String server;

	@Before
	public void newBookSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/**
	 * {@code requestBuildGraph}: no workflow and no node before; afterwards one workflow and exactly one
	 * RENDER_PLAN node per scene under the deterministic handle, scoped to the scene; a second call finds them
	 * all (nothing materialised, same workflow, still three nodes). {@code scene.sceneNode} stays unset.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case01_buildGraphMaterialisesOneRenderPlanNodePerSceneAndIsIdempotent() throws Exception {
		Fixture f = fixture("p10b1", 3);
		try {
			assertNull("precondition: no workflow", PbGraphUtil.findWorkflow(f.owner, f.book));

			Map<String, Object> first = PbServiceFacade.requestBuildGraph(f.owner, f.bookOid);
			logger.info("P10-B1 case01 first build: " + first);
			assertEquals(f.bookOid, first.get("bookObjectId"));
			assertEquals(Integer.valueOf(3), first.get("sceneCount"));
			assertEquals(Integer.valueOf(3), first.get("nodesMaterialized"));
			BaseRecord wf = PbGraphUtil.findWorkflow(f.owner, f.book);
			assertNotNull("the workflow now exists", wf);
			assertEquals(wf.get(FieldNames.FIELD_OBJECT_ID), first.get("workflowObjectId"));
			List<BaseRecord> nodes = PbGraphUtil.listNodes(f.owner, wf);
			assertEquals("one node per scene", 3, nodes.size());
			for(String sceneOid : f.sceneOids) {
				BaseRecord n = nodeByHandle(nodes, PbPipelineUtil.renderPlanHandle(sceneOid));
				assertNotNull("a RENDER_PLAN node under the scene's handle", n);
				assertEquals(PbNodeTypeEnumType.RENDER_PLAN.name(), status(n, OlioFieldNames.FIELD_PB_NODE_TYPE));
				assertEquals(PbPipelineUtil.SCOPE_SCENE, n.get(OlioFieldNames.FIELD_PB_SCOPE));
				assertEquals(sceneOid, n.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
				BaseRecord scene = PbBookUtil.readScene(f.owner, sceneOid, f.orgId);
				assertNull("scene.sceneNode is never written by the V3 path", scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE));
			}

			Map<String, Object> second = PbServiceFacade.requestBuildGraph(f.owner, f.bookOid);
			logger.info("P10-B1 case01 second build: " + second);
			assertEquals("idempotent: nothing new", Integer.valueOf(0), second.get("nodesMaterialized"));
			assertEquals(Integer.valueOf(3), second.get("sceneCount"));
			assertEquals(first.get("workflowObjectId"), second.get("workflowObjectId"));
			assertEquals("still one node per scene", 3, PbGraphUtil.listNodes(f.owner, wf).size());
		}
		finally {
			cleanup(f);
		}
	}

	/**
	 * The reader's per-scene Render on a fresh book: the terminal is created before the planner runs. With no
	 * SD server the planner's own 500 is the hand-off evidence (TestPbRenderJob case12's idiom) - at HEAD this
	 * call was the 409 "has no graph node yet" that blocked UAT. The workflow and the one node exist afterwards,
	 * no run row was written, and a second scene's Render materialises only its own node.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case02_sceneRenderOnFreshBookMaterialisesTheTerminalBeforePlanning() throws Exception {
		Fixture f = fixture("p10b2", 3);
		try {
			assertNull("precondition: no workflow", PbGraphUtil.findWorkflow(f.owner, f.book));
			expectPlannerHandOff(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, f.sceneOids.get(0), SDAPIEnumType.SWARM.name(), null, false), "scene 0");
			BaseRecord wf = PbGraphUtil.findWorkflow(f.owner, f.book);
			assertNotNull("the workflow was created on first use", wf);
			List<BaseRecord> nodes = PbGraphUtil.listNodes(f.owner, wf);
			assertEquals("only the rendered scene's node", 1, nodes.size());
			assertNotNull(nodeByHandle(nodes, PbPipelineUtil.renderPlanHandle(f.sceneOids.get(0))));
			assertEquals("no run row is written on the planner's refusal", -1L, newestRunId(f.owner, wf));

			expectPlannerHandOff(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, f.sceneOids.get(2), SDAPIEnumType.SWARM.name(), null, true), "scene 2 forced");
			nodes = PbGraphUtil.listNodes(f.owner, wf);
			assertEquals("the second scene added only its own node", 2, nodes.size());
			assertNotNull(nodeByHandle(nodes, PbPipelineUtil.renderPlanHandle(f.sceneOids.get(2))));
			assertNull("scene 1 was never asked for", nodeByHandle(nodes, PbPipelineUtil.renderPlanHandle(f.sceneOids.get(1))));
		}
		finally {
			cleanup(f);
		}
	}

	/**
	 * Render all on a fresh book materialises every scene's terminal before the planner; a book with no scenes
	 * is the 409 that names the missing step, and writes nothing.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case03_renderAllOnFreshBookMaterialisesEveryTerminalAndEmptyBookIs409() throws Exception {
		Fixture f = fixture("p10b3", 3);
		Fixture empty = fixture("p10b3e", 0);
		try {
			expectPlannerHandOff(() -> PbServiceFacade.requestRenderAll(f.owner, f.bookOid, SDAPIEnumType.SWARM.name(), null), "render all");
			BaseRecord wf = PbGraphUtil.findWorkflow(f.owner, f.book);
			assertNotNull("the workflow was created on first use", wf);
			List<BaseRecord> nodes = PbGraphUtil.listNodes(f.owner, wf);
			assertEquals("every scene has its node", 3, nodes.size());
			for(String sceneOid : f.sceneOids) {
				assertNotNull(nodeByHandle(nodes, PbPipelineUtil.renderPlanHandle(sceneOid)));
			}
			assertEquals("no run row is written on the planner's refusal", -1L, newestRunId(f.owner, wf));

			try {
				PbServiceFacade.requestRenderAll(empty.owner, empty.bookOid, SDAPIEnumType.SWARM.name(), null);
				fail("a book with no scenes must be refused");
			}
			catch(PictureBookException e) {
				logger.info("P10-B1 case03 empty-book refusal: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().startsWith("This book has no scenes yet"));
			}
			assertNull("the empty book gets no workflow", PbGraphUtil.findWorkflow(empty.owner, empty.book));
		}
		finally {
			cleanup(f);
			cleanup(empty);
		}
	}

	/**
	 * The gates come first: a stranger (no grant on the book) is a 404 from every one of the three entry points
	 * and nothing is created - the materialisation is an authorized write by the book's updater, never a side
	 * effect of an unauthorized read.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case04_strangerIs404AndNothingIsMaterialised() throws Exception {
		Fixture f = fixture("p10b4", 2);
		try {
			BaseRecord stranger = getCreateUser(STRANGER);
			assertNotNull("stranger", stranger);
			assertFalse("precondition: a different user", f.owner.get(FieldNames.FIELD_ID).equals(stranger.get(FieldNames.FIELD_ID)));
			expect404(() -> PbServiceFacade.requestBuildGraph(stranger, f.bookOid), "build graph");
			expect404(() -> PbServiceFacade.requestSceneRender(stranger, f.bookOid, f.sceneOids.get(0), SDAPIEnumType.SWARM.name(), server, false), "scene render");
			expect404(() -> PbServiceFacade.requestRenderAll(stranger, f.bookOid, SDAPIEnumType.SWARM.name(), server), "render all");
			assertNull("no workflow was created for the stranger", PbGraphUtil.findWorkflow(f.owner, f.book));
		}
		finally {
			cleanup(f);
		}
	}

	/**
	 * The user's path, live on {@code .39}: Render on scene 0 of a fresh book is accepted (202-shaped: jobId,
	 * runObjectId, the one planned handle, {@code nodeMaterialized:true}), the job completes, the run is
	 * COMPLETED with one executed node, the node is DONE with a selected base artifact of real PNG bytes. Then
	 * Render all on the same book plans exactly the two scenes that are not current (scene 0's node is DONE and
	 * unchanged, so the planner reuses it), materialising their nodes, and completes with two executed nodes.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case05_liveSceneRenderThenRenderAllOnAFreshBook() throws Exception {
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", server);
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P10-B1 live new-book render");
		logger.info("P10-B1 case05 renders on " + server + " with checkpoint " + renderCfg.get(OlioFieldNames.FIELD_SD_MODEL));
		Fixture f = fixture("p10b5", 3);
		try {
			assertNull("precondition: no workflow", PbGraphUtil.findWorkflow(f.owner, f.book));
			String scene0 = f.sceneOids.get(0);
			String handle0 = PbPipelineUtil.renderPlanHandle(scene0);

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbServiceFacade.requestSceneRender(f.owner, f.bookOid, scene0, SDAPIEnumType.SWARM.name(), server, false);
			logger.info("P10-B1 case05 scene render dto=" + dto);
			assertEquals(scene0, dto.get("sceneObjectId"));
			assertEquals(Boolean.TRUE, dto.get("nodeMaterialized"));
			assertNotNull(dto.get("nodeObjectId"));
			assertNotNull("jobId", dto.get("jobId"));
			assertNotNull("runObjectId", dto.get("runObjectId"));
			assertEquals("the one planned handle is the scene's RENDER_PLAN node", Arrays.asList(handle0), dto.get("planned"));
			assertTrue("accepted job at submit: " + dto.get("jobStatus"), Arrays.asList("queued", "running").contains(dto.get("jobStatus")));

			AsyncJob job = awaitTerminal(f.owner, (String) dto.get("jobId"), RENDER_TIMEOUT_MS);
			logger.info("P10-B1 case05 scene render durationMs=" + (System.currentTimeMillis() - t0) + " job=" + job.getStatus() + " result=" + job.getResult());
			assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertEquals(PbRunStatusEnumType.COMPLETED.name(), result.get("runStatus"));
			assertEquals(Integer.valueOf(1), result.get("executedNodeCount"));
			Map<?, ?> outcome = (Map<?, ?>) ((List<?>) result.get("nodes")).get(0);
			assertEquals(handle0, outcome.get("handle"));
			/// A plan render completes DONE_UNVERIFIED (PbNodeExecutor.executeRenderPlan → completeNode), the same
			/// status TestPbRenderJob asserts; DONE is reserved for a verified / approved node.
			assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), outcome.get("status"));
			String artifactOid = (String) outcome.get("artifactObjectId");
			assertNotNull("the outcome names the base artifact", artifactOid);

			BaseRecord run = PbGraphUtil.readRun(f.owner, (String) dto.get("runObjectId"), f.orgId);
			assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals(Integer.valueOf(1), run.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
			assertEquals(Integer.valueOf(0), run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));

			BaseRecord node0 = PbGraphUtil.readNode(f.owner, (String) dto.get("nodeObjectId"), f.orgId);
			assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node0, OlioFieldNames.FIELD_PB_NODE_STATUS));
			BaseRecord selected = PbArtifactUtil.findSelected(f.owner, node0, PbPipelineUtil.ROLE_BASE);
			assertNotNull("a selected base artifact", selected);
			assertEquals(artifactOid, selected.get(FieldNames.FIELD_OBJECT_ID));
			byte[] bytes = artifactBytes(f.owner, selected, f.orgId);
			assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
			for(int i = 0; i < PNG_MAGIC.length; i++) {
				assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
			}
			File png = new File("target/TestPbNewBookRender/p10b5-scene0-" + System.currentTimeMillis() + ".png");
			png.getParentFile().mkdirs();
			FileUtil.emitFile(png.getPath(), bytes);
			assertTrue(png.exists() && png.length() == bytes.length);
			logger.info("P10-B1 case05 PNG written: " + png.getAbsolutePath() + " (" + bytes.length + " bytes)");

			/// Render all: scene 0 is DONE and current, so only scenes 1 and 2 are planned; their nodes are
			/// created by this call.
			PbStaleUtil.clear();
			long t1 = System.currentTimeMillis();
			Map<String, Object> all = PbServiceFacade.requestRenderAll(f.owner, f.bookOid, SDAPIEnumType.SWARM.name(), server);
			logger.info("P10-B1 case05 render-all dto=" + all);
			assertEquals(Integer.valueOf(3), all.get("sceneCount"));
			assertEquals("the two remaining scenes' nodes were created now", Integer.valueOf(2), all.get("nodesMaterialized"));
			assertEquals("scenesWithoutNode is kept for the reader and always empty", new ArrayList<String>(), all.get("scenesWithoutNode"));
			List<String> expected = Arrays.asList(PbPipelineUtil.renderPlanHandle(f.sceneOids.get(1)), PbPipelineUtil.renderPlanHandle(f.sceneOids.get(2)));
			assertEquals("the current scene is reused, the other two are planned", expected, all.get("planned"));
			assertEquals("one reused node", Integer.valueOf(1), all.get("reusedNodeCount"));
			assertEquals(Integer.valueOf(2), all.get("plannedNodeCount"));

			AsyncJob jobAll = awaitTerminal(f.owner, (String) all.get("jobId"), RENDER_TIMEOUT_MS);
			logger.info("P10-B1 case05 render-all durationMs=" + (System.currentTimeMillis() - t1) + " job=" + jobAll.getStatus() + " result=" + jobAll.getResult());
			assertEquals("job error: " + jobAll.getError(), AsyncJob.Status.COMPLETED, jobAll.getStatus());
			Map<?, ?> resultAll = JSONUtil.importObject(jobAll.getResult(), Map.class);
			assertEquals(PbRunStatusEnumType.COMPLETED.name(), resultAll.get("runStatus"));
			assertEquals(Integer.valueOf(2), resultAll.get("executedNodeCount"));
			BaseRecord wf = PbGraphUtil.findWorkflow(f.owner, f.book);
			List<BaseRecord> nodes = PbGraphUtil.listNodes(f.owner, wf);
			assertEquals("three nodes, one per scene", 3, nodes.size());
			for(String sceneOid : f.sceneOids) {
				BaseRecord n = nodeByHandle(nodes, PbPipelineUtil.renderPlanHandle(sceneOid));
				assertNotNull(n);
				BaseRecord fresh = PbGraphUtil.readNode(f.owner, n.get(FieldNames.FIELD_OBJECT_ID), f.orgId);
				assertEquals("every scene is rendered: " + sceneOid, PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
				assertNotNull("every scene has a selected base artifact: " + sceneOid, PbArtifactUtil.findSelected(f.owner, fresh, PbPipelineUtil.ROLE_BASE));
			}
			assertEquals("scene 0 kept its first artifact (reused, not re-rendered)", artifactOid,
				PbArtifactUtil.findSelected(f.owner, PbGraphUtil.readNode(f.owner, (String) dto.get("nodeObjectId"), f.orgId), PbPipelineUtil.ROLE_BASE).get(FieldNames.FIELD_OBJECT_ID));
		}
		finally {
			cleanup(f);
		}
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		String slug;
		String bookOid;
		long orgId;
		List<String> sceneOids = new ArrayList<>();
	}

	/// A V3 book with {@code sceneCount} scenes carrying setting/action/mood (what the wizard's extraction
	/// leaves behind), owned by {@code OWNER}. No workflow, no node, no sceneNode - on purpose.
	private Fixture fixture(String prefix, int sceneCount) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser(OWNER);
		assertNotNull("owner", f.owner);
		f.orgId = ((Number) f.owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "New-book render " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = f.book.get(FieldNames.FIELD_OBJECT_ID);
		for(int i = 0; i < sceneCount; i++) {
			BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, i, "Scene " + i, PbBookUtil.bookGroupPath(f.slug));
			assertNotNull("scene " + i, scene);
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD);
			patch.set(OlioFieldNames.FIELD_PB_SETTING, SETTINGS[i % SETTINGS.length]);
			patch.set(OlioFieldNames.FIELD_PB_ACTION, ACTIONS[i % ACTIONS.length]);
			patch.set(OlioFieldNames.FIELD_PB_MOOD, MOODS[i % MOODS.length]);
			assertNotNull("scene text patch must persist", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
			String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord fresh = PbBookUtil.readScene(f.owner, sceneOid, f.orgId);
			assertNotNull("re-read scene " + i, fresh);
			assertNull("precondition: no sceneNode", fresh.get(OlioFieldNames.FIELD_PB_SCENE_NODE));
			f.sceneOids.add(sceneOid);
		}
		assertEquals("precondition: the book lists its scenes", sceneCount, PbBookUtil.listScenes(f.owner, f.book).size());
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

	// ─────────────────────────────── helpers ───────────────────────────────

	private interface Call {
		void run() throws Exception;
	}

	/// The planner's 500 "SD server not configured" is thrown after the terminal is materialised and before
	/// any run row - so it proves the facade handed the fresh node to the planner (TestPbRenderJob case12).
	private void expectPlannerHandOff(Call c, String what) throws Exception {
		try {
			c.run();
			fail(what + ": with no SD server the planner must refuse with its own 500");
		}
		catch(PictureBookException e) {
			logger.info("P10-B1 " + what + " hand-off: " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": " + e.getMessage(), 500, e.getStatus());
			assertEquals("SD server not configured", e.getMessage());
		}
	}

	private void expect404(Call c, String what) throws Exception {
		try {
			c.run();
			fail(what + ": a stranger must be refused");
		}
		catch(PictureBookException e) {
			logger.info("P10-B1 stranger " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": " + e.getMessage(), 404, e.getStatus());
		}
	}

	private static BaseRecord nodeByHandle(List<BaseRecord> nodes, String handle) {
		for(BaseRecord n : nodes) {
			if(handle.equals(n.get(OlioFieldNames.FIELD_PB_HANDLE))) {
				return n;
			}
		}
		return null;
	}

	/** The newest olio.pb.run of the workflow (highest id), read uncached; -1 when there is none. */
	private long newestRunId(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(u, q).getResults();
		long bestId = -1L;
		if(recs != null) {
			for(BaseRecord r : recs) {
				long id = ((Number) r.get(FieldNames.FIELD_ID)).longValue();
				if(id > bestId) {
					bestId = id;
				}
			}
		}
		return bestId;
	}

	/** Poll the registry (the way a client polls {@code GET /rest/job/{id}}) until the job is terminal. */
	private AsyncJob awaitTerminal(BaseRecord u, String jobId, long maxMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + maxMs;
		AsyncJob job = null;
		while(System.currentTimeMillis() < deadline) {
			job = AsyncJobRegistry.get(u, jobId);
			assertNotNull("job " + jobId + " must stay visible to its owner", job);
			if(job.isTerminal()) {
				return job;
			}
			Thread.sleep(POLL_MS);
		}
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: " + (job != null ? job.getStatus() + " " + job.getProgress().getPhase() : "null") + ")");
		return null;
	}

	private byte[] artifactBytes(BaseRecord u, BaseRecord artifact, long orgId) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("artifact.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(u, q);
		assertNotNull("artifact data record", data);
		return ByteModelUtil.getValue(data);
	}

	/// Enum fields read back as the enum (or, on list projections, the raw string); never hand a generic
	/// get() to String.valueOf - type inference picks the char[] overload and throws ClassCastException.
	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? v.toString().toUpperCase() : null);
	}
}
