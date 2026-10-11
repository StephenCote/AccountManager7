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
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
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

		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNull("The un-prompted page must not get a node",
			PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(bareOid)));
		BaseRecord node = PbPipelineUtil.findNodeByHandle(testUser, workflow, PbPipelineUtil.landscapeHandle(promptedOid));
		assertNotNull(node);
		assertEquals(((List<?>) nodeOids).get(0), node.get(FieldNames.FIELD_OBJECT_ID));

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
		assertTrue("The new artifact is the selection", Boolean.TRUE.equals(artifact.get(OlioFieldNames.FIELD_PB_SELECTED)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(artifact));
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

	private String swarmServer() {
		String s = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", s);
		return s;
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
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
