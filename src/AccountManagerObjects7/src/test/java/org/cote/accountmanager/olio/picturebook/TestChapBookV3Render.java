package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-9 (design §24): a ChapBook page on {@code chapbook.renderPath=v3} renders as
 * a LANDSCAPE graph node with an artifact through the same {@code pb.render} job PictureBooks use, the
 * page's {@code imageStale} maps to node STALE, and select/approve/reject plus auto-approve apply.
 * <p>
 * Live against {@code am7db} and the Swarm node named by {@code test.swarm.server}; nothing is mocked.
 * {@code BaseTest.setup()} pins both render paths to {@code v3} from {@code resource.properties} before every
 * case, so this class never pins them itself except for the one v1-default case, which restores inside a
 * {@code finally}. Non-GPU cases hand the package-private seams an {@link SDUtil} subclass that records the
 * config reaching {@code createImage} and returns no images (the node then FAILS without touching a GPU);
 * the live cases delegate to the real {@code createImage}. The v3 path drops the per-request client
 * overlay by design, so the installed checkpoint from {@link SdTestGate#requireRenderConfig} is stored on
 * the book tier through {@link ChapBookUtil#setBookSdConfig}. Timings are recorded, never asserted.
 */
public class TestChapBookV3Render extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// Render budgets, not thresholds: timings are recorded, never asserted.
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long JOB_WAIT_MS = 15L * 60L * 1000L;

	private static final String POEM_TEXT =
		"Outside, all is pristine,\n" +
		"From cobalt skies of charcoal unity\n" +
		"Descending upon snow canvassed green\n" +
		"To silver veins of icy sheens,\n" +
		"Born of spells and sorcery.\n" +
		"\n" +
		"Inside hearts and hearths and homes,\n" +
		"Ochre embers and ebon cinders,\n" +
		"Faded life stirred by motherly crones,\n" +
		"Dry damp clothes and warm cold bones\n" +
		"And illuminate the age-old spellbound tomes.";

	private static final String GENUINE_PROMPT =
		"a serene alpine lake at dawn, wide natural vista, soft diffuse light, snow on distant peaks";
	private static final String SECOND_PROMPT =
		"a warm cottage hearth at dusk, ochre embers, wide natural vista through a frosted window";

	private BaseRecord testUser;

	@Before
	public void setUpChapBookV3() {
		assertTrue("resource.properties must put ChapBooks on v3 for this class (BaseTest re-pins it)",
			PbRenderPath.V3.equals(PbRenderPath.valueFor(chapBookStub())));
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
	}

	// ─────────────────────────────── graph shape (no GPU) ───────────────────────────────

	/**
	 * The creating user may render (the facade's {@code requireBookUpdate} permits the owner - R1), the page
	 * node is created once and reused (two submits yield the same {@code nodeObjectId}), it is a LANDSCAPE node
	 * with the scene's landscape handle, the scene is linked to it through {@code sceneNode}, and its
	 * {@code promptText} mirrors the scene's {@code sdPrompt}. The job carries the caller's {@link SDUtil}: the
	 * config reaching {@code createImage} starts with the stored prompt. With no image returned the node FAILS.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void pageNodeIsCreatedOnceAndLinkedToTheScene() throws Exception {
		Fixture fx = createFixture("graph");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));

		CapturingSdu sdu1 = new CapturingSdu(swarmServer(), false);
		Map<String, Object> first = PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, sdu1, swarmServer());
		assertNotNull("The page submit must return a DTO", first);
		assertEquals("The DTO must echo the scene", sceneOid, first.get("sceneObjectId"));
		assertEquals("The DTO must carry the page index", Integer.valueOf(0), first.get("sceneIndex"));
		assertEquals("A page render is a pb.render job", PbRenderJobUtil.KIND_RENDER, first.get("kind"));
		String nodeOid = (String) first.get("nodeObjectId");
		String jobId = (String) first.get("jobId");
		assertNotNull("The DTO must carry the node", nodeOid);
		assertNotNull("The DTO must carry the job", jobId);
		assertNotNull("The DTO must carry the run", first.get("runObjectId"));
		AsyncJob job1 = awaitJob(jobId);
		logger.info("P1-9 graph submit 1: job " + jobId + " -> " + job1.getStatus() + " result=" + job1.getResult());
		assertEquals("createImage must have been called exactly once by the job", 1, sdu1.captured.size());
		String description = sdu1.captured.get(0).get("description");
		assertTrue("The job must render the stored prompt (styled), got: " + description,
			description != null && description.startsWith(GENUINE_PROMPT));

		BaseRecord node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertNotNull("The page node must be readable", node);
		assertEquals("A page is a LANDSCAPE node", PbNodeTypeEnumType.LANDSCAPE.name(), status(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
		assertEquals("The page node uses the scene's landscape handle", PbPipelineUtil.landscapeHandle(sceneOid), node.get(OlioFieldNames.FIELD_PB_HANDLE));
		assertEquals("The node mirrors the scene prompt", GENUINE_PROMPT, node.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));
		assertEquals("The node carries the scene index", Integer.valueOf(0), node.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
		assertEquals("With no image returned the page node FAILS", PbNodeStatusEnumType.FAILED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertFalse("A failed page node is not pinned", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));

		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		BaseRecord sceneNode = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
		assertNotNull("The scene must be linked to its page node", sceneNode);
		long nodeId = node.get(FieldNames.FIELD_ID);
		long linkedId = sceneNode.get(FieldNames.FIELD_ID);
		assertEquals("scene.sceneNode must point at the page node", nodeId, linkedId);

		CapturingSdu sdu2 = new CapturingSdu(swarmServer(), false);
		Map<String, Object> second = PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, sdu2, swarmServer());
		assertEquals("A second submit must reuse the page node", nodeOid, second.get("nodeObjectId"));
		assertNotEquals("A second submit is a new job", jobId, second.get("jobId"));
		AsyncJob job2 = awaitJob((String) second.get("jobId"));
		logger.info("P1-9 graph submit 2: job " + second.get("jobId") + " -> " + job2.getStatus());
		assertEquals("The second job must re-execute the (unpinned) node", 1, sdu2.captured.size());

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNotNull("The book must have a workflow after a submit", workflow);
		BaseRecord byHandle = PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(sceneOid));
		assertNotNull("The node must be findable by its handle", byHandle);
		assertEquals("Exactly one node per page", nodeOid, byHandle.get(FieldNames.FIELD_OBJECT_ID));
	}

	/**
	 * A page with no prompt is a 409 from the facade and gets no node; through the ChapBook seam it is
	 * {@code SKIPPED_NO_PROMPT} reporting the book's actual render path ({@code v3}, not the v1 ctor default),
	 * and {@code createImage} is never reached. A ChapBook created without a chatConfig stores a fallback-shaped
	 * prompt, so the page is un-prompted explicitly through {@link ChapBookUtil#setSceneLandscapePrompt}.
	 */
	@Test
	public void unPromptedPageIsSkippedAndGetsNoNode() throws Exception {
		Fixture fx = createFixture("noprompt");
		String sceneOid = fx.sceneOids.get(1);
		assertTrue("Clearing the prompt must succeed", ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, null));
		BaseRecord cleared = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		String stored = cleared.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertTrue("The stored prompt must be blank after clearing, got: " + stored, stored == null || stored.isBlank());

		CapturingSdu sdu = new CapturingSdu(swarmServer(), false);
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, null, null, null);
		assertNotNull(result);
		assertEquals(ChapBookUtil.SceneRenderStatus.SKIPPED_NO_PROMPT, result.status);
		assertEquals("A skipped page must report the book's real render path", PbRenderPath.V3, result.renderPath);
		assertTrue("With chatConfig == null and no stored prompt the LLM step is reported unavailable", result.llmUnavailable);
		assertNull("No job for a skipped page", result.jobId);
		assertNull("No node for a skipped page", result.nodeObjectId);
		assertNull("No image for a skipped page", result.imageObjectId);
		assertTrue("createImage must never be reached for an un-prompted page", sdu.captured.isEmpty());

		try {
			PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, sdu, swarmServer());
			fail("The facade must refuse an un-prompted page");
		} catch (PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue("409 must name the missing prompt, got: " + e.getMessage(), e.getMessage().contains("has no prompt"));
		}

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		if (workflow != null) {
			assertNull("An un-prompted page must not get a node",
				PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(sceneOid)));
		}
		BaseRecord after = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNull("The scene must stay unlinked", after.get(OlioFieldNames.FIELD_PB_SCENE_NODE));
	}

	/**
	 * Render-all (§6.C item 61 gap 1): pages without a prompt are skipped and listed, one node per prompted
	 * page is submitted as a {@code pb.renderAll} job; when every prompted page is pinned the planner refuses
	 * with 409; when no page has a prompt the facade refuses with 409 before any graph work.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void renderAllSkipsUnPromptedPagesAndRefusesWhenNothingToRender() throws Exception {
		Fixture fx = createFixture("all");
		String promptedOid = fx.sceneOids.get(0);
		String bareOid = fx.sceneOids.get(1);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, promptedOid, GENUINE_PROMPT));
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, bareOid, null));

		CapturingSdu sdu = new CapturingSdu(swarmServer(), false);
		Map<String, Object> dto = PbServiceFacade.requestChapBookRenderAll(testUser, fx.bookOid, sdu, swarmServer());
		assertNotNull(dto);
		assertEquals("Render-all submits a pb.renderAll job", PbRenderJobUtil.KIND_RENDER_ALL, dto.get("kind"));
		assertEquals("Only the prompted page counts", Integer.valueOf(1), dto.get("sceneCount"));
		Object without = dto.get("scenesWithoutPrompt");
		assertTrue("scenesWithoutPrompt must be a list, got " + without, without instanceof List);
		assertEquals("The un-prompted page must be listed", Arrays.asList(bareOid), without);
		Object nodeOids = dto.get("nodeObjectIds");
		assertTrue("nodeObjectIds must be a list", nodeOids instanceof List);
		assertEquals("One node per prompted page", 1, ((List<?>) nodeOids).size());
		String jobId = (String) dto.get("jobId");
		assertNotNull(jobId);
		AsyncJob job = awaitJob(jobId);
		logger.info("P1-9 render-all: job " + jobId + " -> " + job.getStatus() + " result=" + job.getResult());
		assertEquals("Only the prompted page reaches createImage", 1, sdu.captured.size());
		// The no-image double is deliberate (no GPU): the job completes, the run records the one planned node as FAILED.
		assertEquals("The job itself completes; the node outcome lives in the run", AsyncJob.Status.COMPLETED, job.getStatus());
		Map<?, ?> runResult = JSONUtil.importObject(job.getResult(), Map.class);
		assertNotNull("The job result must be readable", runResult);
		assertEquals("The run FAILS when its only node returns no image", "FAILED", runResult.get("runStatus"));
		assertEquals(Integer.valueOf(1), runResult.get("plannedNodeCount"));
		assertEquals(Integer.valueOf(0), runResult.get("executedNodeCount"));
		assertEquals(Integer.valueOf(1), runResult.get("failedNodeCount"));

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNull("The un-prompted page must not get a node",
			PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(bareOid)));
		BaseRecord node = PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(promptedOid));
		assertNotNull(node);
		assertEquals(((List<?>) nodeOids).get(0), node.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("With no image returned the page node FAILS", PbNodeStatusEnumType.FAILED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = node.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertTrue("The node must carry the no-image error, got: " + lastError, lastError != null && lastError.contains("no images"));

		assertTrue("Pinning the only prompted page's node must succeed", PbGraphUtil.setPinned(testUser, node, true));
		try {
			PbServiceFacade.requestChapBookRenderAll(testUser, fx.bookOid, new CapturingSdu(swarmServer(), false), swarmServer());
			fail("With every prompted page pinned there is nothing to render");
		} catch (PictureBookException e) {
			assertEquals("The planner must refuse with 409, got " + e.getStatus() + ": " + e.getMessage(), 409, e.getStatus());
			assertTrue("409 must say nothing is to render, got: " + e.getMessage(), e.getMessage().contains("Nothing to render"));
		}

		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, promptedOid, null));
		try {
			PbServiceFacade.requestChapBookRenderAll(testUser, fx.bookOid, new CapturingSdu(swarmServer(), false), swarmServer());
			fail("With no prompted page the facade must refuse");
		} catch (PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue("409 must name the missing prompts, got: " + e.getMessage(), e.getMessage().contains("has a prompt"));
		}
	}

	/**
	 * With {@code chapbook.renderPath=v1} the old path stays live and default: the seam renders synchronously
	 * and reports {@code v1}, the detachable submit returns {@code null}, and the v3 facade entry refuses 409.
	 */
	@Test
	public void v1DefaultKeepsTheOldPath() throws Exception {
		Fixture fx = createFixture("v1");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
		try {
			CapturingSdu sdu = new CapturingSdu(swarmServer(), false);
			ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, null, null, null);
			assertEquals("v1 renders on the request thread and reports v1", PbRenderPath.V1, result.renderPath);
			assertEquals(ChapBookUtil.SceneRenderStatus.FAILED, result.status);
			assertNull("v1 has no job", result.jobId);
			assertNull("v1 has no node", result.nodeObjectId);
			assertEquals("v1 calls createImage directly", 1, sdu.captured.size());

			assertNull("The detachable submit must return null on v1",
				ChapBookUtil.submitChapBookRender(testUser, fx.bookOid, "SWARM", swarmServer(), null, null));

			try {
				PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, sdu, swarmServer());
				fail("The v3 facade entry must refuse on v1");
			} catch (PictureBookException e) {
				assertEquals(409, e.getStatus());
				assertTrue("The refusal must name the render path, got: " + e.getMessage(),
					e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
			}
			// createChapBook makes the workflow (and its unlinked cb-scene-<i> nodes) on every path; what v1 must
			// never do is add the LANDSCAPE page node or link the scene to one.
			BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
			BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
			if (workflow != null) {
				assertNull("v1 creates no page node",
					PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(sceneOid)));
			}
			BaseRecord after = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
			assertNull("v1 never links the scene to a node", after.get(OlioFieldNames.FIELD_PB_SCENE_NODE));
		} finally {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		}
	}

	// ─────────────────────────────── live renders ───────────────────────────────

	/**
	 * One real page render through the ChapBook seam on v3: the result is {@code RENDERED} with the job / run /
	 * node / artifact identifiers, the artifact row reads back selected, {@code landscape}, {@code GENERATED},
	 * pointing at the same {@code data.data} the scene's {@code imageObjectId} names, the page is no longer
	 * stale, and the PNG decodes. Then the review verbs on that artifact: select is a no-op on the selection,
	 * approve ends APPROVED + pinned, a pinned page refuses re-render with 409, reject ends REJECTED and keeps
	 * the pin.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void liveRenderProducesArtifactAndReviewApplies() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-9 page render");
		Fixture fx = createFixture("live");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		String expectedModel = storeFastBookConfig(fx, renderConfig);

		CapturingSdu sdu = new CapturingSdu(server, true);
		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, null, null, null);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-9 live page render durationMs=" + durationMs + " status=" + result.status + " node=" + result.nodeObjectId
			+ " artifact=" + result.artifactObjectId + " review=" + result.reviewStatus);

		assertEquals("The live render must RENDER", ChapBookUtil.SceneRenderStatus.RENDERED, result.status);
		assertEquals(PbRenderPath.V3, result.renderPath);
		assertNotNull(result.jobId);
		assertNotNull(result.runObjectId);
		assertNotNull(result.nodeObjectId);
		assertNotNull(result.artifactObjectId);
		assertNotNull(result.imageObjectId);
		assertEquals("A fresh render is DONE_UNVERIFIED", PbNodeStatusEnumType.DONE_UNVERIFIED.name(), result.nodeStatus);
		assertEquals("A fresh artifact is GENERATED", PbReviewStatusEnumType.GENERATED.name(), result.reviewStatus);
		assertFalse("Auto-approve is off by default", result.autoApproved);
		assertFalse(result.llmUnavailable);
		assertFalse(result.llmDegraded);

		assertEquals("createImage must be called once", 1, sdu.captured.size());
		BaseRecord cfg = sdu.captured.get(0);
		assertEquals("The job must render with the book-tier installed checkpoint", expectedModel, cfg.get(OlioFieldNames.FIELD_SD_MODEL));
		assertEquals("The job must honour the book-tier steps", Integer.valueOf(12), cfg.get(OlioFieldNames.FIELD_SD_STEPS));
		String description = cfg.get("description");
		assertTrue("The job must render the stored prompt, got: " + description, description.startsWith(GENUINE_PROMPT));

		BaseRecord artifact = PbArtifactUtil.readArtifact(testUser, result.artifactObjectId, fx.orgId);
		assertNotNull("The artifact row must read back", artifact);
		assertEquals(PbPipelineUtil.ROLE_LANDSCAPE, artifact.get(OlioFieldNames.FIELD_PB_ROLE));
		assertEquals("The first render of a page is revision 1", Integer.valueOf(1), artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		assertNull("Revision 1 supersedes nothing", artifact.get(OlioFieldNames.FIELD_PB_SUPERSEDES));
		assertTrue("The new artifact is the selection", Boolean.TRUE.equals(artifact.get(OlioFieldNames.FIELD_PB_SELECTED)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(artifact));
		assertNull("A GENERATED artifact has no approver", artifact.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		BaseRecord producedBy = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		assertNotNull("The artifact names its producing node", producedBy);
		assertEquals("The producing node is the page node", result.nodeObjectId, producedBy.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("The artifact records the backend that rendered it", SDAPIEnumType.SWARM.name(), status(artifact, OlioFieldNames.FIELD_PB_BACKEND));
		assertEquals("image/png", artifact.get(OlioFieldNames.FIELD_PB_MIME_TYPE));
		Integer width = artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH);
		Integer height = artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT);
		assertTrue("The artifact records the decoded width, got " + width, width != null && width.intValue() > 0);
		assertTrue("The artifact records the decoded height, got " + height, height != null && height.intValue() > 0);
		BaseRecord data = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("The artifact must reference its data", data);
		assertEquals("The artifact data is the page image", result.imageObjectId, data.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord node = PbGraphUtil.readNode(testUser, result.nodeObjectId, fx.orgId);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertFalse(Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));
		assertNotNull("completeNode must stamp the inputHash", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));

		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The page must point at the new image", result.imageObjectId, scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertFalse("The page is no longer stale", Boolean.TRUE.equals(scene.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));

		BaseRecord image = freshReadData(result.imageObjectId, fx.orgId);
		assertNotNull(image);
		byte[] bytes = ByteModelUtil.getValue(image);
		assertNotNull(bytes);
		assertTrue("The image must be larger than a PNG header (was " + bytes.length + ")", bytes.length > 1024);
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		assertEquals("The artifact's byteLength is the stored image's length", Long.valueOf(bytes.length), artifact.get(OlioFieldNames.FIELD_PB_BYTE_LENGTH));
		File out = new File("target/chapbook-v3-p1-9-render.png");
		FileUtil.emitFile(out.getPath(), bytes);
		assertTrue(out.exists() && out.length() == bytes.length);
		logger.info("P1-9 PNG written: " + out.getAbsolutePath() + " (" + bytes.length + " bytes)");

		Map<String, Object> selected = PbServiceFacade.selectArtifact(testUser, fx.bookOid, result.artifactObjectId);
		assertTrue("Selecting the selection is a no-op success", Boolean.TRUE.equals(selected.get("selected")));
		assertEquals(result.nodeObjectId, selected.get("nodeObjectId"));

		Map<String, Object> approved = PbServiceFacade.approveArtifact(testUser, fx.bookOid, result.artifactObjectId);
		assertEquals(PbReviewStatusEnumType.APPROVED.name(), approved.get("reviewStatus"));
		assertEquals("Approve records the caller", testUser.get(FieldNames.FIELD_OBJECT_ID), approved.get("approvedBy"));
		assertTrue(Boolean.TRUE.equals(approved.get("selected")));
		node = PbGraphUtil.readNode(testUser, result.nodeObjectId, fx.orgId);
		assertTrue("Approve pins the page node", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));

		try {
			ChapBookUtil.renderChapBookScene(testUser, sceneOid, new CapturingSdu(server, false), null, null, null);
			fail("A pinned page must refuse re-render");
		} catch (PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue("409 must name the pin, got: " + e.getMessage(), e.getMessage().contains("is pinned"));
		}

		Map<String, Object> rejected = PbServiceFacade.rejectArtifact(testUser, fx.bookOid, result.artifactObjectId);
		assertEquals(PbReviewStatusEnumType.REJECTED.name(), rejected.get("reviewStatus"));
		assertNull("Reject clears approvedBy", rejected.get("approvedBy"));
		artifact = PbArtifactUtil.readArtifact(testUser, result.artifactObjectId, fx.orgId);
		assertEquals(PbReviewStatusEnumType.REJECTED, PbArtifactUtil.reviewStatusOf(artifact));
		node = PbGraphUtil.readNode(testUser, result.nodeObjectId, fx.orgId);
		assertTrue("Reject does not touch the pin", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));

		Map<String, Object> queue = PbServiceFacade.listReviewQueue(testUser, fx.bookOid, PbServiceFacade.REVIEW_FILTER_ALL);
		assertEquals(Integer.valueOf(1), queue.get("artifactCount"));
		assertEquals("A rejected artifact is not pending", Integer.valueOf(0), queue.get("pendingCount"));
	}

	/**
	 * Auto-approve (§10 rule 5) on a ChapBook: with {@code autoApprove} on, the rendered page's artifact is
	 * APPROVED by {@code PbArtifactUtil.APPROVED_BY_AUTO}, the node is pinned, the seam reports it, and the
	 * review queue's {@code auto-approved} filter lists it.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void autoApproveApprovesAndPinsTheRenderedPage() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-9 auto-approve render");
		Fixture fx = createFixture("auto");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		storeFastBookConfig(fx, renderConfig);

		assertFalse("A new book has auto-approve off", Boolean.TRUE.equals(PbServiceFacade.getAutoApprove(testUser, fx.bookOid).get("autoApprove")));
		assertTrue(Boolean.TRUE.equals(PbServiceFacade.setAutoApprove(testUser, fx.bookOid, true).get("autoApprove")));
		assertTrue(Boolean.TRUE.equals(PbServiceFacade.getAutoApprove(testUser, fx.bookOid).get("autoApprove")));

		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(testUser, sceneOid, new CapturingSdu(server, true), null, null, null);
		logger.info("P1-9 auto-approve render durationMs=" + (System.currentTimeMillis() - t0) + " status=" + result.status
			+ " autoApproved=" + result.autoApproved + " skip=" + result.autoApproveSkipReason);
		assertEquals(ChapBookUtil.SceneRenderStatus.RENDERED, result.status);
		assertTrue("The seam must report the auto-approval", result.autoApproved);
		assertNull(result.autoApproveSkipReason);
		assertEquals(PbReviewStatusEnumType.APPROVED.name(), result.reviewStatus);

		BaseRecord artifact = PbArtifactUtil.readArtifact(testUser, result.artifactObjectId, fx.orgId);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(artifact));
		assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, artifact.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		BaseRecord node = PbGraphUtil.readNode(testUser, result.nodeObjectId, fx.orgId);
		assertTrue("Auto-approve pins", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));

		Map<String, Object> queue = PbServiceFacade.listReviewQueue(testUser, fx.bookOid, PbServiceFacade.REVIEW_FILTER_AUTO_APPROVED);
		assertTrue(Boolean.TRUE.equals(queue.get("autoApprove")));
		List<?> nodes = (List<?>) queue.get("nodes");
		assertEquals("The auto-approved page is listed", 1, nodes.size());
		Map<?, ?> qn = (Map<?, ?>) nodes.get(0);
		assertEquals(result.nodeObjectId, qn.get("nodeObjectId"));
		Map<?, ?> qa = (Map<?, ?>) ((List<?>) qn.get("artifacts")).get(0);
		assertEquals(result.artifactObjectId, qa.get("objectId"));
		assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, qa.get("approvedBy"));

		assertFalse(Boolean.TRUE.equals(PbServiceFacade.setAutoApprove(testUser, fx.bookOid, false).get("autoApprove")));
	}

	/**
	 * Drift on a ChapBook page: a prompt edit and a config override each mark the page node STALE and mirror
	 * onto it; merging the next stanza up sets {@code imageStale} and STALE; a re-render then supersedes the
	 * artifact (chain of two, newest selected), returns the node to DONE_UNVERIFIED and clears
	 * {@code imageStale} - using the mirrored override (steps) rather than the book tier.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void editsMarkThePageStaleAndRerenderClearsIt() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-9 stale/re-render");
		Fixture fx = createFixture("stale");
		assertTrue("Two scenes are needed, got " + fx.sceneOids.size(), fx.sceneOids.size() >= 2);
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		String expectedModel = storeFastBookConfig(fx, renderConfig);

		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult first = ChapBookUtil.renderChapBookScene(testUser, sceneOid, new CapturingSdu(server, true), null, null, null);
		logger.info("P1-9 stale case render 1 durationMs=" + (System.currentTimeMillis() - t0) + " status=" + first.status);
		assertEquals(ChapBookUtil.SceneRenderStatus.RENDERED, first.status);
		String nodeOid = first.nodeObjectId;

		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, SECOND_PROMPT));
		BaseRecord node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals("A prompt edit marks the page node STALE", PbNodeStatusEnumType.STALE.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("The edit is mirrored onto the node", SECOND_PROMPT, node.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));

		assertTrue(PbBookUtil.setSceneConfigOverride(testUser, sceneOid,
			"{\"schema\":\"" + OlioModelNames.MODEL_SD_CONFIG + "\",\"steps\":13}"));
		node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals(PbNodeStatusEnumType.STALE.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String nodeOverride = node.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
		assertNotNull("The override is mirrored onto the node", nodeOverride);
		assertEquals(Integer.valueOf(13), PbConfigUtil.parseOverride(nodeOverride).get(OlioFieldNames.FIELD_SD_STEPS));

		String survivor = ChapBookUtil.mergeSceneUp(testUser, sceneOid);
		assertEquals("Merge-up keeps the absorbing scene", sceneOid, survivor);
		BaseRecord merged = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertTrue("Merge-up stales the page image", Boolean.TRUE.equals(merged.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
		assertEquals("Merge-up keeps the page prompt", SECOND_PROMPT, merged.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("Merge-up keeps the page's image until re-render", first.imageObjectId, merged.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals(PbNodeStatusEnumType.STALE.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));

		CapturingSdu sdu = new CapturingSdu(server, true);
		t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult second = ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, null, null, null);
		logger.info("P1-9 stale case render 2 durationMs=" + (System.currentTimeMillis() - t0) + " status=" + second.status);
		assertEquals(ChapBookUtil.SceneRenderStatus.RENDERED, second.status);
		assertEquals("The re-render reuses the page node", nodeOid, second.nodeObjectId);
		assertNotEquals("The re-render produces a new artifact", first.artifactObjectId, second.artifactObjectId);
		assertNotEquals("The re-render produces a new image", first.imageObjectId, second.imageObjectId);
		BaseRecord cfg = sdu.captured.get(0);
		assertEquals("The mirrored page override beats the book tier", Integer.valueOf(13), cfg.get(OlioFieldNames.FIELD_SD_STEPS));
		assertEquals(expectedModel, cfg.get(OlioFieldNames.FIELD_SD_MODEL));
		assertTrue(((String) cfg.get("description")).startsWith(SECOND_PROMPT));

		node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		BaseRecord after = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertFalse("Re-render clears imageStale", Boolean.TRUE.equals(after.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
		assertEquals(second.imageObjectId, after.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));

		List<BaseRecord> chain = PbArtifactUtil.listChain(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE);
		assertEquals("Two renders, two artifacts", 2, chain.size());
		BaseRecord sel = PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE);
		assertNotNull(sel);
		assertEquals("The newest artifact is selected", second.artifactObjectId, sel.get(FieldNames.FIELD_OBJECT_ID));
	}

	/**
	 * {@code force} and the reuse skip. Every other re-render in this class starts from a FAILED or STALE node,
	 * both of which the planner executes anyway; this one re-renders a page whose node is current
	 * (DONE_UNVERIFIED, unpinned, nothing edited). Proven on the same node, both ways: the planner without
	 * {@code force} reuses it and plans nothing, with {@code force} it plans exactly that node, and the ChapBook
	 * seam (which submits with {@code force=true}) renders it again - same node, new job, revision 2 superseding
	 * revision 1, new image, newest selected, revision 1 kept and unselected. A pin still wins over
	 * {@code force} at the planner.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void forceRerendersACurrentUnpinnedPage() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-9 force re-render");
		Fixture fx = createFixture("force");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		storeFastBookConfig(fx, renderConfig);

		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult first = ChapBookUtil.renderChapBookScene(testUser, sceneOid, new CapturingSdu(server, true), null, null, null);
		logger.info("P1-9 force case render 1 durationMs=" + (System.currentTimeMillis() - t0) + " status=" + first.status);
		assertEquals(ChapBookUtil.SceneRenderStatus.RENDERED, first.status);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), first.nodeStatus);

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNotNull("The rendered book has a workflow", workflow);
		BaseRecord node = PbGraphUtil.readNode(testUser, first.nodeObjectId, fx.orgId);
		assertEquals("Precondition: the page node is current", PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertFalse("Precondition: the page node is not pinned", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));
		Long nodeId = node.get(FieldNames.FIELD_ID);

		PbRenderJobUtil.Plan unforced = PbRenderJobUtil.plan(testUser, book, workflow, List.of(nodeId), false);
		assertTrue("Without force a current page is reused, but the planner executes " + unforced.execute.size(), unforced.execute.isEmpty());
		assertEquals("The planner counts the reuse", 1, unforced.reused);
		assertEquals(0, unforced.pinned);
		PbRenderJobUtil.Plan forced = PbRenderJobUtil.plan(testUser, book, workflow, List.of(nodeId), true);
		assertEquals("With force the current page is executed", 1, forced.execute.size());
		assertEquals("The forced plan executes the page node itself", nodeId, (Long) forced.execute.get(0).get(FieldNames.FIELD_ID));
		assertEquals("Force bypasses the reuse skip", 0, forced.reused);
		assertTrue("A page node is SD-backed", forced.needsSd);

		CapturingSdu sdu = new CapturingSdu(server, true);
		t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult second = ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, null, null, null);
		logger.info("P1-9 force case render 2 durationMs=" + (System.currentTimeMillis() - t0) + " status=" + second.status);
		assertEquals("The forced re-render must RENDER", ChapBookUtil.SceneRenderStatus.RENDERED, second.status);
		assertEquals("The re-render reuses the page node", first.nodeObjectId, second.nodeObjectId);
		assertNotEquals("The re-render is a new job", first.jobId, second.jobId);
		assertNotEquals("The re-render is a new run", first.runObjectId, second.runObjectId);
		assertNotEquals("The re-render produces a new artifact", first.artifactObjectId, second.artifactObjectId);
		assertNotEquals("The re-render produces a new image", first.imageObjectId, second.imageObjectId);
		assertEquals("createImage must be called exactly once by the forced re-render", 1, sdu.captured.size());

		BaseRecord revision2 = PbArtifactUtil.readArtifact(testUser, second.artifactObjectId, fx.orgId);
		assertNotNull(revision2);
		assertEquals(Integer.valueOf(2), revision2.get(OlioFieldNames.FIELD_PB_REVISION));
		BaseRecord supersedes = revision2.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		assertNotNull("Revision 2 supersedes revision 1", supersedes);
		assertEquals(first.artifactObjectId, supersedes.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("Revision 2 is selected", Boolean.TRUE.equals(revision2.get(OlioFieldNames.FIELD_PB_SELECTED)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(revision2));
		BaseRecord revision1 = PbArtifactUtil.readArtifact(testUser, first.artifactObjectId, fx.orgId);
		assertNotNull("Revision 1 is kept", revision1);
		assertFalse("Revision 1 is no longer selected", Boolean.TRUE.equals(revision1.get(OlioFieldNames.FIELD_PB_SELECTED)));
		assertNotNull("Revision 1's image is kept", freshReadData(first.imageObjectId, fx.orgId));

		node = PbGraphUtil.readNode(testUser, first.nodeObjectId, fx.orgId);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		List<BaseRecord> chain = PbArtifactUtil.listChain(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE);
		assertEquals("Two renders, two artifacts", 2, chain.size());
		BaseRecord sel = PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE);
		assertEquals("The newest artifact is selected", second.artifactObjectId, sel.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The page points at the new image", second.imageObjectId, scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertFalse(Boolean.TRUE.equals(scene.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));

		assertTrue(PbGraphUtil.setPinned(testUser, node, true));
		PbRenderJobUtil.Plan pinnedPlan = PbRenderJobUtil.plan(testUser, book, workflow, List.of(nodeId), true);
		assertTrue("Force never overrides a pin", pinnedPlan.execute.isEmpty());
		assertEquals("The pinned page is counted as pinned, not reused", 1, pinnedPlan.pinned);
		assertEquals(0, pinnedPlan.reused);
	}

	/**
	 * P1-10 (§6.C 109): the facade's explicit per-scene render carries the planner's {@code force} flag, on the
	 * one LANDSCAPE path whose node can be current - the ChapBook page, whose executor stamps {@code inputHash}
	 * through {@code completeNode} (the plain PictureBook landscape executor never does, §8.9, so a current
	 * node cannot be built there). After a live render through the ChapBook seam the same page requested
	 * through {@link PbServiceFacade#requestSceneRender(BaseRecord, String, String, String, String, boolean)}
	 * without force is the planner's 409 "(1 reused, 0 pinned)" and opens no run - the refusal the reader's
	 * Re-render hit after an unpin; with force it is a new run, rendered live, producing revision 2 selected and
	 * superseding revision 1 (both kept), the page pointing at the new image. Pinning the node and forcing again
	 * is still a 409 "(0 reused, 1 pinned)" that opens no run: force never overrides a pin.
	 */
	@Test(timeout = 2L * RENDER_TIMEOUT_MS)
	public void facadeForceRerendersACurrentPageAndPinStillWins() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-10 facade force re-render");
		Fixture fx = createFixture("facade-force");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		storeFastBookConfig(fx, renderConfig);

		/// 1. Render 1 through the ChapBook seam: creates the page node and completes it with an inputHash.
		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult first = ChapBookUtil.renderChapBookScene(testUser, sceneOid, new CapturingSdu(server, true), null, null, null);
		logger.info("P1-10 facade force render 1 durationMs=" + (System.currentTimeMillis() - t0) + " status=" + first.status);
		assertEquals(ChapBookUtil.SceneRenderStatus.RENDERED, first.status);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), first.nodeStatus);

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNotNull("The rendered book has a workflow", workflow);
		BaseRecord node = PbGraphUtil.readNode(testUser, first.nodeObjectId, fx.orgId);
		assertEquals("Precondition: the page node is current", PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNotNull("Precondition: the page node carries an inputHash", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		assertFalse("Precondition: the page node is not pinned", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));
		String handle = node.get(OlioFieldNames.FIELD_PB_HANDLE);
		assertNotNull(handle);
		BaseRecord sceneBefore = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNotNull("Precondition: the scene is linked to its node", sceneBefore.get(OlioFieldNames.FIELD_PB_SCENE_NODE));

		/// 2. The facade without force: the page is current, so the planner refuses and opens no run.
		long runsBefore = newestRunId(workflow, fx.orgId);
		try {
			PbServiceFacade.requestSceneRender(testUser, fx.bookOid, sceneOid, SDAPIEnumType.SWARM.name(), server, false);
			fail("An un-forced render of a current page must be refused");
		}
		catch(PictureBookException e) {
			logger.info("P1-10 facade force un-forced refusal: " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertEquals("Nothing to render: every node of this scene is current (1 reused, 0 pinned)", e.getMessage());
		}
		assertEquals("The refusal opens no run", runsBefore, newestRunId(workflow, fx.orgId));

		/// 3. The facade with force: a new run re-rolls the current page into revision 2.
		t0 = System.currentTimeMillis();
		Map<String, Object> forced = PbServiceFacade.requestSceneRender(testUser, fx.bookOid, sceneOid, SDAPIEnumType.SWARM.name(), server, true);
		logger.info("P1-10 facade force render 2 dto=" + forced);
		assertEquals(sceneOid, forced.get("sceneObjectId"));
		assertEquals(List.of(handle), forced.get("planned"));
		assertEquals(Integer.valueOf(1), forced.get("plannedNodeCount"));
		assertEquals(Integer.valueOf(0), forced.get("reusedNodeCount"));
		assertEquals(Integer.valueOf(0), forced.get("pinnedNodeCount"));
		assertNotEquals("The forced render is a new run", first.runObjectId, forced.get("runObjectId"));
		assertNotEquals("The forced render is a new job", first.jobId, forced.get("jobId"));
		AsyncJob job = awaitJob((String) forced.get("jobId"));
		logger.info("P1-10 facade force render 2 durationMs=" + (System.currentTimeMillis() - t0) + " job=" + job.getStatus() + " result=" + job.getResult());
		assertEquals("Job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
		Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), result.get("runStatus"));
		assertEquals(Integer.valueOf(1), result.get("executedNodeCount"));
		List<?> outcomes = (List<?>) result.get("nodes");
		assertEquals(1, outcomes.size());
		Map<?, ?> outcome = (Map<?, ?>) outcomes.get(0);
		assertEquals(handle, outcome.get("handle"));
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), outcome.get("status"));
		String artifact2Oid = (String) outcome.get("artifactObjectId");
		assertNotNull("The forced render names its artifact", artifact2Oid);
		assertNotEquals("The forced render produces a new artifact", first.artifactObjectId, artifact2Oid);

		BaseRecord revision2 = PbArtifactUtil.readArtifact(testUser, artifact2Oid, fx.orgId);
		assertNotNull(revision2);
		assertEquals(Integer.valueOf(2), revision2.get(OlioFieldNames.FIELD_PB_REVISION));
		assertTrue("Revision 2 is selected", Boolean.TRUE.equals(revision2.get(OlioFieldNames.FIELD_PB_SELECTED)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(revision2));
		BaseRecord supersedes = revision2.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		assertNotNull("Revision 2 supersedes revision 1", supersedes);
		assertEquals(first.artifactObjectId, supersedes.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord revision1 = PbArtifactUtil.readArtifact(testUser, first.artifactObjectId, fx.orgId);
		assertNotNull("Revision 1 is kept", revision1);
		assertFalse("Revision 1 is no longer selected", Boolean.TRUE.equals(revision1.get(OlioFieldNames.FIELD_PB_SELECTED)));

		node = PbGraphUtil.readNode(testUser, first.nodeObjectId, fx.orgId);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("Two renders, two artifacts", 2, PbArtifactUtil.listChain(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE).size());
		assertEquals("The newest artifact is selected", artifact2Oid,
			PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE).get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord data = revision2.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("Revision 2 references its data", data);
		String image2Oid = data.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(image2Oid);
		assertNotEquals("The forced render produces a new image", first.imageObjectId, image2Oid);
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The page points at the new image", image2Oid, scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertFalse(Boolean.TRUE.equals(scene.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
		byte[] bytes = ByteModelUtil.getValue(freshReadData(image2Oid, fx.orgId));
		assertTrue("Real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File out = new File("target/chapbook-v3-p110-facade-force.png");
		FileUtil.emitFile(out.getPath(), bytes);
		assertTrue(out.exists() && out.length() == bytes.length);
		logger.info("P1-10 facade force PNG written: " + out.getAbsolutePath() + " (" + bytes.length + " bytes)");

		/// 4. A pin still wins over force, and the pinned refusal opens no run.
		assertTrue(PbGraphUtil.setPinned(testUser, node, true));
		runsBefore = newestRunId(workflow, fx.orgId);
		try {
			PbServiceFacade.requestSceneRender(testUser, fx.bookOid, sceneOid, SDAPIEnumType.SWARM.name(), server, true);
			fail("A forced render of a pinned page must be refused");
		}
		catch(PictureBookException e) {
			logger.info("P1-10 facade force pinned refusal: " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertEquals("Nothing to render: every node of this scene is current (0 reused, 1 pinned)", e.getMessage());
		}
		assertEquals("The pinned refusal opens no run", runsBefore, newestRunId(workflow, fx.orgId));
		assertEquals("The pinned page keeps its selected artifact", artifact2Oid,
			PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE).get(FieldNames.FIELD_OBJECT_ID));
	}

	/**
	 * The fifth {@code markSceneNodeStale} write site: the health heal for a page whose image record is gone.
	 * After a real render the page image is deleted as the owner; {@link PbHealthUtil#checkBook} reports
	 * {@code SCENE_IMAGE_DANGLING} for that page, {@link PbHealthUtil#healBook} scoped to that code clears
	 * {@code imageObjectId}, sets {@code imageStale} and marks the page node STALE (and says so in the healed
	 * entry), the post-heal audit is clear of the finding, and the planner now executes the page without
	 * {@code force} (STALE is in the execute set).
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void healingADanglingPageImageMarksTheNodeStale() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-9 dangling-image heal");
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = createFixture("heal");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		storeFastBookConfig(fx, renderConfig);

		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(testUser, sceneOid, new CapturingSdu(server, true), null, null, null);
		logger.info("P1-9 heal case render durationMs=" + (System.currentTimeMillis() - t0) + " status=" + result.status);
		assertEquals(ChapBookUtil.SceneRenderStatus.RENDERED, result.status);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), result.nodeStatus);

		Map<String, Object> clean = PbHealthUtil.checkBook(testUser, fx.bookOid);
		assertFalse("A freshly rendered page has no dangling image: " + findingCodes(clean),
			findingCodes(clean).contains(PbHealthUtil.SCENE_IMAGE_DANGLING));

		BaseRecord image = freshReadData(result.imageObjectId, fx.orgId);
		assertNotNull("The page image must exist before it is deleted", image);
		assertTrue("The owner deletes the page image", IOSystem.getActiveContext().getAccessPoint().delete(testUser, image));
		assertNull("The page image is gone", freshReadData(result.imageObjectId, fx.orgId));

		Map<String, Object> report = PbHealthUtil.checkBook(testUser, fx.bookOid);
		Map<String, Object> dangling = null;
		for (Map<String, Object> f : findings(report)) {
			if (PbHealthUtil.SCENE_IMAGE_DANGLING.equals(f.get("code"))) {
				dangling = f;
			}
		}
		assertNotNull("The audit must report the dangling page image: " + findingCodes(report), dangling);
		assertTrue("The finding is healable", Boolean.TRUE.equals(dangling.get("healable")));
		Map<?, ?> refs = (Map<?, ?>) dangling.get("refs");
		assertEquals("The finding names the deleted image", result.imageObjectId, refs.get("imageObjectId"));
		assertEquals("The finding names the page", sceneOid, refs.get("objectId"));
		BaseRecord before = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The audit writes nothing: the page still names the deleted image", result.imageObjectId, before.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertEquals("The audit writes nothing: the node is still current", PbNodeStatusEnumType.DONE_UNVERIFIED.name(),
			status(PbGraphUtil.readNode(testUser, result.nodeObjectId, fx.orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

		Map<String, Object> healReport = PbHealthUtil.healBook(testUser, dataPath, fx.bookOid, Set.of(PbHealthUtil.SCENE_IMAGE_DANGLING));
		List<Map<String, Object>> healed = healedOf(healReport, PbHealthUtil.SCENE_IMAGE_DANGLING);
		assertEquals("One SCENE_IMAGE_DANGLING heal: healed=" + healReport.get("healed") + " skipped=" + healReport.get("skipped"), 1, healed.size());
		assertEquals(sceneOid, ((Map<?, ?>) healed.get(0).get("refs")).get("objectId"));
		String action = (String) healed.get(0).get("action");
		assertTrue("The heal must report the node STALE, got: " + action, action != null && action.contains("page node marked STALE"));
		assertFalse("The post-heal audit is clear of the finding: " + findingCodes(healReport),
			findingCodes(healReport).contains(PbHealthUtil.SCENE_IMAGE_DANGLING));

		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNull("The heal clears the dangling imageObjectId", scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertTrue("The heal marks the page stale", Boolean.TRUE.equals(scene.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
		assertEquals("The heal keeps the page prompt", GENUINE_PROMPT, scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		BaseRecord node = PbGraphUtil.readNode(testUser, result.nodeObjectId, fx.orgId);
		assertEquals("imageStale maps to node STALE through the heal", PbNodeStatusEnumType.STALE.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertFalse("The heal does not pin", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		PbRenderJobUtil.Plan plan = PbRenderJobUtil.plan(testUser, book, workflow, List.of((Long) node.get(FieldNames.FIELD_ID)), false);
		assertEquals("A STALE page is planned without force", 1, plan.execute.size());
		assertEquals((Long) node.get(FieldNames.FIELD_ID), (Long) plan.execute.get(0).get(FieldNames.FIELD_ID));
	}

	// ─────────────────────────────── live LLM ───────────────────────────────

	/**
	 * The second {@code markSceneNodeStale} write site, the only LLM-backed one: regenerating a page's landscape
	 * prompt against the live LLM persists the new prompt, unlocks it, mirrors it onto the page node and marks
	 * the node STALE. The node is created without a GPU (a no-image submit leaves it FAILED), so this case
	 * depends on the LLM alone; a current node would go STALE by the same write.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void regeneratingThePromptMarksTheNodeStale() throws Exception {
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		SdTestGate.requireLlmReachable(llmServer);
		BaseRecord chatConfig = OlioTestUtil.getPbChatConfig(testUser, "chapbookV3RegenLlm", testProperties);
		assertNotNull("The live chatConfig must be built", chatConfig);

		Fixture fx = createFixture("regen");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		Map<String, Object> submit = PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, new CapturingSdu(swarmServer(), false), swarmServer());
		String nodeOid = (String) submit.get("nodeObjectId");
		awaitJob((String) submit.get("jobId"));
		BaseRecord node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals("Precondition: the no-image submit leaves the node FAILED", PbNodeStatusEnumType.FAILED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("Precondition: the node mirrors the hand-set prompt", GENUINE_PROMPT, node.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));
		BaseRecord locked = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("Precondition: a hand-set prompt is locked", Boolean.TRUE, locked.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

		long t0 = System.currentTimeMillis();
		String regenerated = ChapBookUtil.regenerateSceneLandscapePrompt(testUser, sceneOid, chatConfig);
		logger.info("P1-9 regenerate durationMs=" + (System.currentTimeMillis() - t0) + " prompt='" + regenerated + "'");
		assertNotNull(regenerated);
		assertFalse(regenerated.isBlank());
		assertTrue("The regenerated prompt must be a genuine LLM prompt, got: " + regenerated, ChapBookUtil.isGenuineStoredPrompt(regenerated));
		assertNotEquals("The regenerate must replace the hand-set prompt", GENUINE_PROMPT, regenerated);

		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The regenerated prompt is persisted on the page", regenerated, scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("A regenerate unlocks the prompt", Boolean.FALSE, scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals("A regenerated prompt marks the page node STALE", PbNodeStatusEnumType.STALE.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("The regenerated prompt is mirrored onto the node", regenerated, node.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));
	}

	/**
	 * The detachable bulk submit on v3: one {@code pb.renderAll} job for every prompted page, returned as
	 * {@code pending} with the job / run identifiers (what Service7 turns into a 202), the un-prompted page
	 * counted as skipped; awaiting the job shows one executed node and the page pointing at its image.
	 * The bulk path builds its own {@link SDUtil}, so this renders on the real Swarm node.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void bulkSubmitRunsAsOneJob() throws Exception {
		String server = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "ChapBook V3 P1-9 bulk render");
		Fixture fx = createFixture("bulk");
		String promptedOid = fx.sceneOids.get(0);
		String bareOid = fx.sceneOids.get(1);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, promptedOid, GENUINE_PROMPT));
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, bareOid, null));
		storeFastBookConfig(fx, renderConfig);

		long t0 = System.currentTimeMillis();
		ChapBookUtil.ChapBookRenderSummary summary = ChapBookUtil.submitChapBookRender(testUser, fx.bookOid, "SWARM", server, null, null);
		assertNotNull("The bulk submit must return a summary on v3", summary);
		assertEquals(PbRenderPath.V3, summary.renderPath);
		assertTrue("The bulk submit must detach", summary.pending);
		assertNotNull(summary.jobId);
		assertNotNull(summary.runObjectId);
		assertEquals("The un-prompted page is skipped by the pre-step", 1, summary.skipped);
		assertEquals("One node planned for the prompted page", 1, summary.plannedNodeCount);
		assertEquals("Nothing has rendered at submit time", 0, summary.rendered);

		AsyncJob job = awaitJob(summary.jobId);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-9 bulk render durationMs=" + durationMs + " job=" + summary.jobId + " -> " + job.getStatus() + " result=" + job.getResult());
		assertEquals("The bulk job must complete, error=" + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
		Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
		assertNotNull(result);
		assertEquals(Integer.valueOf(1), result.get("executedNodeCount"));
		assertEquals(Integer.valueOf(0), result.get("failedNodeCount"));

		BaseRecord scene = PbBookUtil.readScene(testUser, promptedOid, fx.orgId);
		assertNotNull("The prompted page must point at its image", scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertFalse(Boolean.TRUE.equals(scene.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
		BaseRecord bare = PbBookUtil.readScene(testUser, bareOid, fx.orgId);
		assertNull("The un-prompted page stays image-less", bare.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		assertNull("The un-prompted page gets no node", bare.get(OlioFieldNames.FIELD_PB_SCENE_NODE));
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/** Records every config reaching {@code createImage}; renders for real only when {@code delegate}. */
	private static final class CapturingSdu extends SDUtil {
		final List<BaseRecord> captured = new ArrayList<>();
		private final boolean delegate;

		CapturingSdu(String server, boolean delegate) {
			super(SDAPIEnumType.SWARM, server);
			this.delegate = delegate;
		}

		@Override
		public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name, int batch, boolean hires, int seed) {
			captured.add(sdConfig);
			if (delegate) {
				return super.createImage(user, groupPath, sdConfig, name, batch, hires, seed);
			}
			return new ArrayList<>();
		}
	}

	private static final class Fixture {
		String bookOid;
		long orgId;
		List<BaseRecord> scenes;
		List<String> sceneOids = new ArrayList<>();
	}

	/** A fresh two-scene ChapBook created WITHOUT a chatConfig (scenes carry the fallback prompt shape). */
	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		fx.orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookV3-" + tag + "-" + ts, "Poem V3 " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-v3-" + tag + "-" + ts,
			"ChapBook V3 " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", fx.bookOid);
		BaseRecord bookCheck = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertNotNull("Book must be readable", bookCheck);
		fx.scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", fx.scenes.isEmpty());
		for (BaseRecord s : fx.scenes) {
			fx.sceneOids.add(s.get(FieldNames.FIELD_OBJECT_ID));
		}
		return fx;
	}

	/** The installed checkpoint at 12 steps, no hires, on the book tier; returns the checkpoint name. */
	private String storeFastBookConfig(Fixture fx, BaseRecord renderConfig) throws Exception {
		String model = renderConfig.get(OlioFieldNames.FIELD_SD_MODEL);
		assertNotNull("The render gate must name an installed checkpoint", model);
		BaseRecord sparse = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG,
			new String[] { OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires" });
		sparse.setValue(OlioFieldNames.FIELD_SD_MODEL, model);
		sparse.setValue(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
		sparse.setValue("hires", Boolean.FALSE);
		BaseRecord stored = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, sparse);
		assertNotNull("The book sdConfig must be stored", stored);
		assertEquals(model, stored.get(OlioFieldNames.FIELD_SD_MODEL));
		return model;
	}

	private AsyncJob awaitJob(String jobId) {
		AsyncJob job = PbRenderJobUtil.awaitTerminal(testUser, jobId, JOB_WAIT_MS);
		assertNotNull("Job " + jobId + " must reach a terminal state within the wait budget", job);
		return job;
	}

	/** The newest olio.pb.run of the workflow (highest id), read uncached; -1 when there is none. */
	private long newestRunId(BaseRecord workflow, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(testUser, q).getResults();
		long bestId = -1L;
		if (recs != null) {
			for (BaseRecord r : recs) {
				long id = ((Number) r.get(FieldNames.FIELD_ID)).longValue();
				if (id > bestId) {
					bestId = id;
				}
			}
		}
		return bestId;
	}

	private String swarmServer() {
		String s = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", s);
		return s;
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> findings(Map<String, Object> report) {
		List<Map<String, Object>> f = (List<Map<String, Object>>) report.get("findings");
		return (f != null ? f : new ArrayList<>());
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
		List<Map<String, Object>> healed = (List<Map<String, Object>>) report.get("healed");
		for (Map<String, Object> h : (healed != null ? healed : new ArrayList<Map<String, Object>>())) {
			if (code.equals(h.get("code"))) {
				out.add(h);
			}
		}
		return out;
	}

	/** A detached CHAPBOOK-typed book record, only for asking {@link PbRenderPath} which switch applies. */
	private static BaseRecord chapBookStub() {
		try {
			BaseRecord stub = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] { OlioFieldNames.FIELD_PB_BOOK_TYPE });
			stub.setValue(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
			return stub;
		} catch (Exception e) {
			throw new AssertionError("Cannot build a chapbook stub: " + e.getMessage(), e);
		}
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		} catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	private BaseRecord freshReadData(String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
	}
}
