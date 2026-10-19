package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.enumWord;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-4 (design §9.1): <b>Quick start</b> - the {@code pb.compose} job with {@code quickStart=true}
 * composes every scene in story order and, right after each one is written, renders a DRAFT-preset image of it
 * through the same executor the per-scene render-plan route uses, so a fresh book gets a thumbnail per scene in
 * one submit. Because the job composed the scene first, no draft render is {@code COMPOSED_OUT_OF_ORDER}.
 * <p>
 * Live {@code am7db} as {@code testUser1} in {@code /Development/World Building}, the real LLM the gate resolved
 * ({@code OlioTestUtil.getOllamaOpenAIConfig}) and the live Swarm node ({@code test.swarm.server}) through
 * {@link SdTestGate} - a gate skip is a skip, never a pass. The live model does not always return a parseable
 * staging; a scene it fails is written as a MIGRATED composition (§29) and Quick start still renders it (any
 * written composition is rendered), so the LLM-vs-MIGRATED split is MEASURED and the invariant asserted is
 * "every scene written, every written scene drafted". Two real renders; PNGs land in
 * {@code target/p44-quickstart-<slug>-<sceneIndex>.png} and must be looked at. Timings are MEASURED, never asserted.
 */
public class TestPbQuickStart extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String CFG_NAME = "P44 Quick Start Cfg";
	private static final long CASE_TIMEOUT_MS = 30L * 60L * 1000L;
	/// Two LLM composes + two DRAFT renders + a location pass; a poll budget, never a threshold.
	private static final long JOB_TIMEOUT_MS = 25L * 60L * 1000L;
	private static final long PHASE_POLL_MS = 2_000L;
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };

	private static final String SETTING_1 = "the spiral stair of the Varrow Point lighthouse, salt-damp stone, a storm rising outside";
	private static final String ACTION_1 = "Ilse climbs with the lamp oil while Corin follows two steps behind";
	private static final String TEXT_1 = "Ilse Brandt took the stair two at a time, the oil can knocking against her hip. "
		+ "Corin Hale followed with the lantern, its light swinging across the salt-damp stone. Below them the door "
		+ "banged in the wind and the first thunder rolled in off the water. 'Keep up,' she said, 'the lamp has been "
		+ "dark an hour already.'";
	private static final String SETTING_2 = "the lamp room at the top of the Varrow Point lighthouse, brass and glass, lightning beyond the panes";
	private static final String ACTION_2 = "Ilse trims the wick and lights the great lamp while Corin watches the ship on the rocks";
	private static final String TEXT_2 = "The lamp room was all brass and glass and the smell of cold oil. Ilse Brandt knelt at the "
		+ "burner, trimming the wick with a steady hand, while Corin Hale pressed his face to the pane. Lightning showed "
		+ "him the schooner on the Varrow rocks, her foremast gone. 'Now,' Ilse said, and struck the match, and the "
		+ "great lens turned and threw its beam out over the wreck.";

	private String server;

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
	}

	/**
	 * Two scenes with a cast, Quick start on: the job completes with every scene written and every written scene
	 * drafted ({@code quickStartRendered == composed + migrated}, {@code quickStartFailed == 0}), the location
	 * pass ran; each scene's {@code draftRender} names a base artifact and the scene's RENDER_PLAN node, whose
	 * selected plan is the DRAFT preset with no {@code COMPOSED_OUT_OF_ORDER} degradation; the artifact is a real
	 * PNG, GENERATED (auto-approve off), revision 1 and selected.
	 */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case01_quickStartRendersADraftPerComposedScene() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P4-4 quick start");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		assertEquals("precondition: the deployment default this JVM resolves is the installed checkpoint (BaseTest pins it)",
			installed, SDUtil.getDefaultModel());

		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		try {
			cfg = liveConfig(u);
			bk = newBook(u, "qs");
			String bkOid = oidOf(bk);
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			List<BaseRecord> scenes = twoScenesWithCast(u, bk);
			long gid = ((Number) scenes.get(0).get(FieldNames.FIELD_GROUP_ID)).longValue();
			for(BaseRecord s : scenes) {
				assertNull("precondition: no composition", PbComposeUtil.findSceneComposition(u, s, gid, orgId));
			}
			assertEquals("precondition: auto-approve is off", Boolean.FALSE, PbServiceFacade.getAutoApprove(u, bkOid).get("autoApprove"));

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbComposeJobUtil.submitCompose(u, bk, true, false, CFG_NAME, SDAPIEnumType.SWARM.name(), server);
			logger.info("P4-4 quick start submit: " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Boolean.FALSE, dto.get("reused"));
			assertEquals(Boolean.TRUE, dto.get("quickStart"));
			assertEquals(Boolean.FALSE, dto.get("force"));
			assertEquals(PbRenderJobUtil.KIND_COMPOSE, dto.get("kind"));
			assertEquals(Integer.valueOf(2), dto.get("sceneCount"));
			assertEquals(Integer.valueOf(2), dto.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), dto.get("scenesCurrent"));
			String jobId = (String) dto.get("jobId");
			assertNotNull("jobId", jobId);
			assertEquals("the claim names the job", jobId, PbComposeJobUtil.inflightJobId(bkOid));

			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "quickstart");
			long wall = System.currentTimeMillis() - t0;
			logger.info("MEASURED: P4-4 quick start job wall " + wall + " ms -> " + job.getStatus() + " error=" + job.getError());
			logger.info("P4-4 quick start result: " + job.getResult());
			assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull("the claim is released on completion", PbComposeJobUtil.inflightJobId(bkOid));
			assertFalse(PbComposeJobUtil.isComposing(bkOid));

			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull("the result parses: " + job.getResult(), result);
			assertEquals(bkOid, result.get("bookObjectId"));
			assertEquals(slug, result.get("slug"));
			assertEquals(Boolean.TRUE, result.get("quickStart"));
			assertEquals(Boolean.FALSE, result.get("force"));
			assertEquals(2, num(result, "sceneCount"));
			assertEquals(2, num(result, "visited"));
			int composed = num(result, "composed");
			int migrated = num(result, "migrated");
			logger.info("MEASURED: P4-4 quick start composed=" + composed + " migrated=" + migrated
				+ " quickStartRendered=" + num(result, "quickStartRendered") + " quickStartFailed=" + num(result, "quickStartFailed")
				+ " durationMs=" + lng(result, "durationMs"));
			assertEquals("every scene is written (LLM or the §29 MIGRATED fallback): " + job.getResult(), 2, composed + migrated);
			assertEquals(0, num(result, "current"));
			assertEquals(0, num(result, "hardFailures"));
			assertEquals(0, num(result, "skippedUserEdited"));
			assertEquals(0, num(result, "pagesSkipped"));
			assertEquals("every written scene got its draft", composed + migrated, num(result, "quickStartRendered"));
			assertEquals("no draft failed: " + job.getResult(), 0, num(result, "quickStartFailed"));
			assertEquals(2, num(result, "lastSceneIndex"));
			assertEquals(Boolean.FALSE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("interrupted"));
			assertNull("no note on a complete run", result.get("note"));
			assertEquals(Boolean.TRUE, result.get("locationPassRan"));
			Map<?, ?> lp = (Map<?, ?>) result.get("locationPass");
			assertNotNull("locationPass DTO", lp);
			logger.info("P4-4 quick start location pass: " + lp);
			assertEquals("both scenes carry a setting", 2, num(lp, "scenesConsidered"));
			assertEquals(Boolean.FALSE, lp.get("cancelled"));

			List<?> outcomes = (List<?>) result.get("scenes");
			assertEquals(2, outcomes.size());
			Set<String> nodeOids = new HashSet<>();
			Set<String> artifactOids = new HashSet<>();
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) outcomes.get(i);
				BaseRecord scene = scenes.get(i);
				String sceneOid = oidOf(scene);
				int sceneIndex = ((Number) scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX)).intValue();
				String label = "outcome[" + i + "] scene " + sceneIndex;
				assertEquals(label + " story order", sceneOid, o.get("sceneObjectId"));
				assertEquals(label, Integer.valueOf(sceneIndex), o.get("sceneIndex"));
				String status = (String) o.get("status");
				assertTrue(label + " status is composed or migrated: " + o, "composed".equals(status) || "migrated".equals(status));
				String expectedSource = ("composed".equals(status) ? PbCompositionSourceEnumType.LLM : PbCompositionSourceEnumType.MIGRATED).name();
				assertEquals(label, expectedSource, o.get("source"));
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
				assertNotNull(label + " composition row", comp);
				assertEquals(label + " compositionObjectId is the scene's row", oidOf(comp), o.get("compositionObjectId"));
				assertEquals(label, expectedSource, enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
				assertEquals(label + " elapsedMs present", true, o.get("elapsedMs") instanceof Number);

				assertNull(label + " draftRenderError: " + o.get("draftRenderError"), o.get("draftRenderError"));
				assertTrue(label + " draftRenderMs is recorded (presence only)", o.get("draftRenderMs") instanceof Number);
				Map<?, ?> dr = (Map<?, ?>) o.get("draftRender");
				assertNotNull(label + " draftRender", dr);
				logger.info("MEASURED: P4-4 quick start " + label + " status=" + status + " composeMs=" + o.get("elapsedMs")
					+ " draftRenderMs=" + o.get("draftRenderMs") + " draftRender=" + dr);
				String artifactOid = (String) dr.get("artifactObjectId");
				String nodeOid = (String) dr.get("nodeObjectId");
				assertNotNull(label + " draftRender.artifactObjectId", artifactOid);
				assertNotNull(label + " draftRender.nodeObjectId", nodeOid);
				assertTrue(label + " a distinct node per scene", nodeOids.add(nodeOid));
				assertTrue(label + " a distinct artifact per scene", artifactOids.add(artifactOid));
				assertEquals(label, PbNodeStatusEnumType.DONE_UNVERIFIED.name(), dr.get("nodeStatus"));
				assertNotNull(label + " draftRender.runObjectId", dr.get("runObjectId"));
				assertEquals(label + " BASE only", List.of(RenderPlan.STAGE_BASE), dr.get("stagesRendered"));
				assertEquals(label + " auto-approve is off", Boolean.FALSE, dr.get(PbNodeExecutor.KEY_AUTO_APPROVED));
				assertEquals(label, PbNodeExecutor.SKIP_OFF, dr.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
				List<?> degradations = (List<?>) dr.get("degradations");
				if(degradations != null) {
					for(Object d : degradations) {
						assertFalse(label + " the job composed the scene in order before drafting it; never out of order: " + degradations,
							String.valueOf(d).startsWith(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER + ": "));
					}
				}

				/// The node is the scene's RENDER_PLAN node and its selected plan is the DRAFT preset.
				BaseRecord node = PbGraphUtil.readNode(u, nodeOid, orgId);
				assertNotNull(label + " node", node);
				assertEquals(label, PbNodeTypeEnumType.RENDER_PLAN.name(), enumWord(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
				assertEquals(label, PbPipelineUtil.SCOPE_SCENE, node.get(OlioFieldNames.FIELD_PB_SCOPE));
				assertEquals(label, sceneOid, node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
				assertEquals(label, PbPipelineUtil.renderPlanHandle(sceneOid), node.get(OlioFieldNames.FIELD_PB_HANDLE));
				assertEquals(label, PbNodeStatusEnumType.DONE_UNVERIFIED.name(), enumWord(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
				List<BaseRecord> plans = PbArtifactUtil.listChain(u, node, PbPipelineUtil.ROLE_RENDER_PLAN);
				assertEquals(label + " one plan revision", 1, plans.size());
				BaseRecord selectedPlan = PbArtifactUtil.findSelected(u, node, PbPipelineUtil.ROLE_RENDER_PLAN);
				assertNotNull(label + " selected plan", selectedPlan);
				assertEquals(label, oidOf(plans.get(0)), oidOf(selectedPlan));
				RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(selectedPlan);
				assertNotNull(label + " the plan parses", plan);
				assertNotNull(label + " preset", plan.getPreset());
				assertTrue(label + " quick start drafts at the DRAFT preset: " + plan.getPreset().getPreset(),
					PbQualityPresetEnumType.DRAFT.name().equalsIgnoreCase(plan.getPreset().getPreset()));
				for(RenderPlan.Degradation d : plan.getDegradations()) {
					assertFalse(label + " persisted plan never COMPOSED_OUT_OF_ORDER: " + plan.getDegradations(),
						RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER.equals(d.getCode()));
				}
				assertNotNull(label + " plan location", plan.getLocation());
				assertTrue(label + " the plan's location text is the scene's own setting: " + plan.getLocation().getDescription(),
					String.valueOf(plan.getLocation().getDescription()).contains(i == 0 ? SETTING_1 : SETTING_2));
				assertNotNull(label + " compiled BASE prompt", plan.getCompiled().get(RenderPlan.STAGE_BASE));
				logger.info("P4-4 quick start " + label + " BASE prompt: " + plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt());

				/// The artifact: the node's selected base, revision 1, GENERATED, a real PNG.
				BaseRecord artifact = PbArtifactUtil.readArtifact(u, artifactOid, orgId);
				assertNotNull(label + " artifact", artifact);
				assertEquals(label, PbPipelineUtil.ROLE_BASE, artifact.get(OlioFieldNames.FIELD_PB_ROLE));
				assertEquals(label, Integer.valueOf(1), artifact.get(OlioFieldNames.FIELD_PB_REVISION));
				assertEquals(label, Boolean.TRUE, artifact.get(OlioFieldNames.FIELD_PB_SELECTED));
				assertEquals(label, PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(artifact));
				BaseRecord selectedBase = PbArtifactUtil.findSelected(u, node, PbPipelineUtil.ROLE_BASE);
				assertNotNull(label + " selected base", selectedBase);
				assertEquals(label, artifactOid, oidOf(selectedBase));
				BaseRecord snapshot = artifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
				assertNotNull(label + " snapshot", snapshot);
				assertEquals(label + " rendered with the installed checkpoint", installed, snapshot.get(OlioFieldNames.FIELD_SD_MODEL));
				byte[] bytes = artifactBytes(u, artifact);
				File png = new File("target", "p44-quickstart-" + slug + "-" + sceneIndex + ".png");
				Files.write(png.toPath(), bytes);
				logger.info("MEASURED: P4-4 quick start draft of scene " + sceneIndex + " on " + server + " model=" + snapshot.get(OlioFieldNames.FIELD_SD_MODEL)
					+ " preset=" + plan.getPreset().getPreset() + " steps=" + plan.getPreset().getSteps() + " canvas=" + plan.getCanvas()
					+ " durationMs=" + artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS) + " bytes=" + bytes.length
					+ " seed=" + artifact.get(OlioFieldNames.FIELD_PB_SEED) + " png=" + png.getAbsolutePath());
			}
			assertEquals("one scene composition per scene", 2, PbComposeUtil.listSceneCompositions(u, bk).size());
		}
		finally {
			deleteBook(u, bk);
			deleteConfig(u, cfg);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static int num(Map<?, ?> m, String key) {
		Object o = m.get(key);
		assertNotNull(key + " in " + m, o);
		return ((Number) o).intValue();
	}

	private static long lng(Map<?, ?> m, String key) {
		Object o = m.get(key);
		assertNotNull(key + " in " + m, o);
		return ((Number) o).longValue();
	}

	/** Poll every ~2 s, logging each phase change, until terminal; fails only on timeout (no progress), never on time taken. */
	private AsyncJob awaitTerminalLoggingPhases(BaseRecord u, String jobId, long maxMs, String tag) throws InterruptedException {
		long start = System.currentTimeMillis();
		long deadline = start + maxMs;
		String lastPhase = null;
		AsyncJob job = null;
		while(System.currentTimeMillis() < deadline) {
			job = AsyncJobRegistry.get(u, jobId);
			assertNotNull("job " + jobId + " must stay visible to its owner", job);
			String phase = job.getProgress().getPhase();
			String key = job.getStatus() + " " + phase + " " + job.getProgress().getCurrent() + "/" + job.getProgress().getTotal();
			if(!key.equals(lastPhase)) {
				logger.info("P4-4 " + tag + " +" + (System.currentTimeMillis() - start) + " ms: " + key);
				lastPhase = key;
			}
			if(job.isTerminal()) {
				return job;
			}
			Thread.sleep(PHASE_POLL_MS);
		}
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: " + lastPhase + ")");
		return null;
	}

	private BaseRecord liveConfig(BaseRecord u) {
		BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(u, CFG_NAME, testProperties);
		assertNotNull("chatConfig " + CFG_NAME, cfg);
		logger.info("[p44-live] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
			+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));
		return cfg;
	}

	/** Two scenes in story order with Ilse and Corin enrolled in both, so the LLM has a cast to resolve. */
	private List<BaseRecord> twoScenesWithCast(BaseRecord u, BaseRecord bk) throws Exception {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("The book must carry a world", bctx);
		String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Population group path", popPath);
		BaseRecord s1 = addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1);
		BaseRecord s2 = addScene(u, bk, 2, "The Lamp Room", SETTING_2, ACTION_2, "resolute", TEXT_2);
		BaseRecord ilse = createCharPerson(u, popPath, "Ilse Brandt", "Ilse", "Brandt", "female");
		BaseRecord corin = createCharPerson(u, popPath, "Corin Hale", "Corin", "Hale", "male");
		enrol(u, s1, ilse);
		enrol(u, s1, corin);
		enrol(u, s2, ilse);
		enrol(u, s2, corin);
		List<BaseRecord> out = new ArrayList<>();
		out.add(s1);
		out.add(s2);
		return out;
	}

	private BaseRecord freshReadData(BaseRecord u, String objectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().find(u, q);
	}

	/// The artifact's bytes through the data ref, read fresh and decoded through ByteModelUtil; must be a real PNG.
	private byte[] artifactBytes(BaseRecord u, BaseRecord artifact) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("base artifact points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			ioContext.getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = freshReadData(u, dataOid);
		assertNotNull("data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		return bytes;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p44" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-4 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			logger.info("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private void deleteConfig(BaseRecord u, BaseRecord cfg) {
		if(cfg == null) {
			return;
		}
		BaseRecord conn = cfg.get("connection");
		deleteQuietly(u, cfg);
		deleteQuietly(u, conn);
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + " was not deleted");
			}
		}
		catch(Exception e) {
			logger.error("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}

	/** A scene with its setting/action/mood and text, re-read after the patch. */
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING,
			OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD, FieldNames.FIELD_DESCRIPTION);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
		patch.set(OlioFieldNames.FIELD_PB_ACTION, action);
		patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
		patch.set(FieldNames.FIELD_DESCRIPTION, description);
		assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable", fresh);
		return fresh;
	}

	private BaseRecord createCharPerson(BaseRecord u, String groupPath, String name, String firstName, String lastName, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, firstName);
		if(lastName != null) {
			cp.set(FieldNames.FIELD_LAST_NAME, lastName);
		}
		cp.set(FieldNames.FIELD_GENDER, gender);
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME) + " in scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX),
			ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}
}
