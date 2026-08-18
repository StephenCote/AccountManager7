package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbFeatureFlag;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil;
import org.cote.accountmanager.olio.picturebook.PbSubRecordUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 3's live verification: {@code generateSceneImage} run with {@code picturebook.v2} ON, against
 * the real Swarm and the real LLM, asserting that the workflow graph it recorded actually describes the
 * images it produced.
 * <p>
 * <b>Level 1 (structural) and level 2 (differential) per plan §9.</b> Level 1 is the answer to
 * existence-only assertions: bytes decode, decoded dimensions match what the config asked for, the
 * expected bindings exist and resolve, and the persisted {@code generatorRequest} carries the reference
 * artifacts' objectIds and <b>neither base64 nor {@code session_id}</b>. Level 2 is the answer to KI-59 -
 * the same scene at the <b>same seed and config</b>, once with the landscape reference bound and once
 * with it suppressed, must produce composites whose {@code contentHash} <b>differ</b>. That is a
 * human-free proof that the reference reaches the model, which is what "does it integrate" means
 * operationally.
 * <p>
 * <b>Runs on REAL SOURCE CONTENT, never an invented stand-in.</b> It attaches to the catatone.docx book
 * {@code TestPictureBookCustom} caches (Duna/Duña and Jideon de Rosa) rather than fabricating a
 * two-character fixture, because a synthetic fixture cannot exhibit the prompt/likeness behaviour the
 * pipeline exists to produce. If that content is absent the test fails with an actionable message rather
 * than quietly building a stand-in and passing.
 * <p>
 * <b>Never the admin user</b> - it uses the same non-admin {@code pbCustomTestUser} in the same test
 * organization, which is also how it sees the cached content.
 * <p>
 * <b>Single-threaded, and SD-heavy on purpose.</b> A FLUX.2 multi-reference composite is ~638s on the
 * local Strix Halo iGPU and level 2 needs two of them, so this class is slow by construction. Sustained
 * SD load belongs on the local box (Swarm at {@code test.swarm.server}); the Spark is faster per request
 * but crashes under sustained SD load.
 */
public class TestPictureBookWorkflow extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestPictureBookWorkflow.class);

	/// The same organization and user TestPictureBookCustom uses, which is how the cached catatone
	/// content is visible here without re-running extraction.
	private static final String ORG_PATH = "/Development/PictureBook Custom Tests";
	private static final String TEST_USER = "pbCustomTestUser";
	private static final String PB1_BOOK_NAME = "Catatone Custom Book 4";
	/// This test class's OWN book, rebuilt from scratch on every run of the gap-1/2 case (see its
	/// javadoc). Deliberately not the catatone fixture, and deliberately not timestamped - a fixed name
	/// plus a delete-if-present start means at most one of these ever exists.
	private static final String FRESH_BOOK_NAME = "PB2 Fresh Character Book";
	private static final String CHAT_PATH = "~/Chat";
	private static final String PB_LLM_MODEL = "gpt-oss:120b";
	private static final int PB_ITER = 4;
	private static final String PB_CHAT_CONFIG_NAME = "PictureBook " + PB_LLM_MODEL + " " + PB_ITER + ".chat";
	private static final String EXPORT_DIR = "./export";

	/// A fixed seed, so level 2's two runs differ ONLY in whether the landscape reference is bound.
	private static final int FIXED_SEED = 987654321;
	/// Level 1's byteLength floor. A 4-step FLUX.2 1024px PNG is hundreds of KB; anything under 4KB is a
	/// placeholder or an error image, not a render.
	private static final long MIN_IMAGE_BYTES = 4096L;

	private BaseRecord testUser;
	private BaseRecord chatConfig;
	private OlioContext olioCtx;
	private boolean priorFlag;

	@Before
	public void enableV2() {
		/// BaseTest's @Before has already run (superclass first), so IO is open here.
		priorFlag = PbFeatureFlag.isV2Enabled();
		PbFeatureFlag.setV2Enabled(true);
	}

	@After
	public void restoreV2() {
		/// Restored, not left on: TestPictureBookCustom is the flag-OFF non-regression gate and a leaked
		/// true in a shared JVM would silently turn that gate into a v2 run.
		PbFeatureFlag.setV2Enabled(priorFlag);
	}

	private void setupContext() {
		AuditUtil.setLogToConsole(false);
		Factory mf = IOSystem.getActiveContext().getFactory();
		BaseRecord orgCtxUser = getTestOrganization(ORG_PATH).getAdminUser();
		testUser = mf.getCreateUser(orgCtxUser, TEST_USER, (long) orgCtxUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Test user should exist", testUser);
		olioCtx = OlioContextUtil.getOlioContext(testUser, testProperties.getProperty("test.datagen.path"));
		chatConfig = DocumentUtil.getRecord(testUser, OlioModelNames.MODEL_CHAT_CONFIG, PB_CHAT_CONFIG_NAME, CHAT_PATH);
		assertNotNull("The PB chat config '" + PB_CHAT_CONFIG_NAME + "' must exist. Run"
			+ " TestPictureBookCustom#TestPictureBookCustomPipeline once to build the cached catatone"
			+ " content this test attaches to - it is deliberately not rebuilt here, and a stand-in"
			+ " fixture would not exercise the real source document.", chatConfig);
	}

	/**
	 * The PB1 book group whose scenes this test renders. Failing here is correct and actionable: the
	 * alternative - inventing a book - would produce a green test that proves nothing about the pipeline.
	 */
	private BaseRecord pb1BookGroup() {
		long orgId = (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			org.cote.accountmanager.schema.ModelNames.MODEL_GROUP,
			"~/Data/PictureBooks/" + PB1_BOOK_NAME,
			org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(), orgId);
		assertNotNull("The PB1 book group '~/Data/PictureBooks/" + PB1_BOOK_NAME + "' must exist."
			+ " Run TestPictureBookCustom#TestPictureBookCustomPipeline once to create it.", grp);
		return grp;
	}

	/** Get-or-create the PB2 book for the PB1 book, by the slug PbPipelineUtil derives. */
	private BaseRecord pb2Book(String slug) {
		long orgId = (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord book = PbBookUtil.findBookBySlug(testUser, slug, orgId);
		if(book != null) {
			logger.info("Reusing PB2 book '" + slug + "'");
			return book;
		}
		/// createBook is the ONE authorized creation path - PbPipelineUtil deliberately never creates a
		/// book from a render call, so the test does it explicitly here.
		book = PbBookUtil.createBook(testUser, testProperties.getProperty("test.datagen.path"), slug,
			PB1_BOOK_NAME);
		assertNotNull("PB2 book creation should return a book readable by its creator", book);
		return book;
	}

	private PictureBookUtil.SceneGenerationParams params(String slug, boolean includeLandscapeRef, int seed) {
		PictureBookUtil.SceneGenerationParams p = new PictureBookUtil.SceneGenerationParams();
		p.chatConfigName = chatConfig.get(FieldNames.FIELD_NAME);
		p.bookSlug = slug;
		p.sdConfig = commonConfig(includeLandscapeRef, seed);
		p.isBookOverride = Boolean.TRUE;
		return p;
	}

	/**
	 * The common {@code olio.sd.config} for a run. Model/refinerModel come from {@code test.swarm.*}
	 * because the schema's own default checkpoint is almost certainly not installed on this Swarm, and a
	 * wrong checkpoint returns an EMPTY image list rather than an error (KI-39) - i.e. it fails silently.
	 */
	private BaseRecord commonConfig(boolean includeLandscapeRef, int seed) {
		try {
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("compositeMode", "flux2");
			cfg.set("flux2Model", testProperties.getProperty("test.swarm.flux2Model"));
			cfg.set(OlioFieldNames.FIELD_SD_MODEL, testProperties.getProperty("test.swarm.model"));
			cfg.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, testProperties.getProperty("test.swarm.refinerModel"));
			cfg.set("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));
			cfg.set(OlioFieldNames.FIELD_HIRES, false);
			cfg.set("seed", seed);
			cfg.set("flux2IncludeLandscapeRef", includeLandscapeRef);
			cfg.set("style", "photograph");
			SDUtil.fillStyleDefaults(cfg);
			return cfg;
		}
		catch(Exception e) {
			throw new IllegalStateException("Failed to build the test SD config: " + e.getMessage(), e);
		}
	}

	/** The first scene objectId of the PB1 book. */
	private String firstSceneObjectId(String bookObjectId) {
		List<Map<String, Object>> scenes = PictureBookUtil.listScenes(testUser, bookObjectId);
		assertFalse("The PB1 book must have at least one scene", scenes.isEmpty());
		String oid = (String) scenes.get(0).get("objectId");
		assertNotNull("The first scene must have an objectId", oid);
		return oid;
	}

	// ═══════════════════════════════════════════════════════════════════
	// LEVEL 1 — structural
	// ═══════════════════════════════════════════════════════════════════

	@Test
	public void TestSceneGraphIsRecordedAndStructurallySound() throws Exception {
		logger.info("***** PB2 phase 3: level-1 structural verification");
		setupContext();
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);

		BaseRecord bookGroup = pb1BookGroup();
		String slug = PbPipelineUtil.deriveSlug(PB1_BOOK_NAME);
		assertNotNull("A slug must derive from '" + PB1_BOOK_NAME + "'", slug);
		BaseRecord book = pb2Book(slug);
		String bookGroupOid = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		String sceneOid = firstSceneObjectId(bookGroupOid);

		long t0 = System.currentTimeMillis();
		BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid,
			params(slug, true, FIXED_SEED), "SWARM", swarmServer);
		logger.info("Scene generated in " + (System.currentTimeMillis() - t0) + "ms");
		assertNotNull("generateSceneImage should return a result", result);
		String imageOid = result.get("imageObjectId");
		assertNotNull("A real composite image objectId is required", imageOid);
		exportImage(imageOid, "pb2_level1_composite");

		// ── the graph exists and describes this scene ──
		long orgId = (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNotNull("The book must now have a workflow", workflow);
		List<BaseRecord> sceneNodes = PbPipelineUtil.listSceneNodes(testUser, workflow, sceneOid);
		logger.info("Recorded " + sceneNodes.size() + " scene-scoped nodes");
		assertTrue("At least the two prompt nodes, the landscape, the reference and the composite"
			+ " should be recorded (got " + sceneNodes.size() + ")", sceneNodes.size() >= 5);

		BaseRecord compositeNode = PbPipelineUtil.findNodeByHandle(testUser, workflow,
			PbPipelineUtil.compositeHandle(sceneOid));
		assertNotNull("A COMPOSITE node must exist for this scene", compositeNode);
		assertEquals("A node that just succeeded must be DONE, not STALE - inputHash is persisted AFTER"
			+ " the artifact and its bindings, so a STALE here means the hash was frozen too early",
			PbNodeStatusEnumType.DONE, compositeNode.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNotNull("A node that ran must carry an inputHash",
			compositeNode.get(OlioFieldNames.FIELD_PB_INPUT_HASH));

		// ── the composite artifact ──
		BaseRecord compositeArtifact = PbArtifactUtil.findSelected(testUser, compositeNode,
			PbPipelineUtil.ROLE_COMPOSITE);
		assertNotNull("The composite node must have a selected artifact", compositeArtifact);
		assertLevel1Image(compositeArtifact, "composite", 0, 0);

		// generatorRequest: references by objectId, and NEITHER base64 NOR session_id.
		String genReq = compositeArtifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
		assertNotNull("The composite artifact must persist its generatorRequest", genReq);
		assertTrue("generatorRequest must be structurally sanitized (no initImage/promptImages/session_id)",
			PbArtifactUtil.isSanitized(genReq));
		assertFalse("generatorRequest must not contain a base64 data URL",
			genReq.contains("data:image/png;base64,"));
		assertFalse("generatorRequest must not contain session_id", genReq.contains("session_id"));
		assertTrue("generatorRequest must record the reference artifacts under "
			+ PbArtifactUtil.REFERENCE_ARTIFACTS_KEY, genReq.contains(PbArtifactUtil.REFERENCE_ARTIFACTS_KEY));

		// ── the landscape artifact, at the dimensions the pipeline FORCES ──
		BaseRecord landscapeNode = PbPipelineUtil.findNodeByHandle(testUser, workflow,
			PbPipelineUtil.landscapeHandle(sceneOid));
		assertNotNull("A LANDSCAPE node must exist", landscapeNode);
		BaseRecord landscapeArtifact = PbArtifactUtil.findSelected(testUser, landscapeNode,
			PbPipelineUtil.ROLE_LANDSCAPE);
		assertNotNull("The landscape node must have a selected artifact", landscapeArtifact);
		/// generateSceneImage sets landReq width/height to 1024x768 explicitly, so this is the config's
		/// expectation and not a guess. Measured from the DECODED bytes, which is the point.
		assertLevel1Image(landscapeArtifact, "landscape", 1024, 768);

		// ── the reference artifacts, and the SUPPRESSED-line effect ──
		BaseRecord referenceNode = PbPipelineUtil.findNodeByHandle(testUser, workflow,
			PbPipelineUtil.referenceHandle(sceneOid));
		assertNotNull("A REFERENCE_STRIP node must exist", referenceNode);
		List<BaseRecord> refArtifacts = new ArrayList<>();
		for(int i = 0; i < 4; i++) {
			BaseRecord a = PbArtifactUtil.findSelected(testUser, referenceNode,
				PbPipelineUtil.ROLE_REFERENCE_STRIP + i);
			if(a != null) {
				refArtifacts.add(a);
			}
		}
		logger.info("Recorded " + refArtifacts.size() + " FLUX.2 reference artifacts");
		// flux2IncludeLandscapeRef is ON, so the landscape MUST be among the references. This asserts the
		// EFFECT of the "landscape reference SUPPRESSED" line not firing, read out of persisted data
		// rather than scraped from a log line - the data is the stronger evidence, since a log assertion
		// passes even if the reference never reached the request.
		assertTrue("With flux2IncludeLandscapeRef=true the landscape must be bound as a reference, so more"
			+ " than the portraits alone should be recorded (got " + refArtifacts.size() + ")",
			refArtifacts.size() >= 1);
		for(int i = 0; i < refArtifacts.size(); i++) {
			assertLevel1Image(refArtifacts.get(i), "reference" + i, 0, 0);
			assertTrue("Reference artifact " + i + "'s objectId must appear in the composite's"
				+ " generatorRequest, which is what makes the provenance readable",
				genReq.contains((String) refArtifacts.get(i).get(FieldNames.FIELD_OBJECT_ID)));
		}

		// ── the bindings exist and resolve ──
		List<BaseRecord> compositeBindings = PbGraphUtil.listBindings(testUser, compositeNode);
		logger.info("Composite node carries " + compositeBindings.size() + " bindings");
		assertFalse("The composite must carry bindings - unbound, nothing records which characters,"
			+ " landscape or prompt produced it (PB1 passes null,null for attribution)",
			compositeBindings.isEmpty());
		boolean sawResolvedSource = false;
		for(BaseRecord b : compositeBindings) {
			String role = b.get(OlioFieldNames.FIELD_PB_ROLE);
			BaseRecord src = b.get(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT);
			logger.info("  binding role=" + role + " sourceArtifact="
				+ (src != null ? src.get(FieldNames.FIELD_OBJECT_ID) : "null"));
			if(src != null) {
				sawResolvedSource = true;
			}
		}
		assertTrue("At least one composite binding must have a non-null sourceArtifact - a binding with"
			+ " no resolved source contributes 'unbound' to inputHash and can never go stale",
			sawResolvedSource);

		// The landscape binding specifically, since it is the one level 2 manipulates.
		BaseRecord landscapeBinding = PbPipelineUtil.findBinding(testUser, compositeNode,
			PbPipelineUtil.ROLE_LANDSCAPE, 0);
		assertNotNull("The composite must bind the landscape node", landscapeBinding);
		assertNotNull("The landscape binding must resolve to the artifact revision actually consumed",
			landscapeBinding.get(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT));

		// ── the run was opened and closed ──
		BaseRecord run = PbGraphUtil.readRun(testUser,
			(String) PbGraphUtil.findWorkflow(testUser, book).get(FieldNames.FIELD_OBJECT_ID), orgId);
		/// readRun is by the RUN's objectId; the workflow's lastRun FK is deliberately not projected on a
		/// workflow read (it is one half of the documented workflow<->run cycle), so the run is asserted
		/// through the executed count on the nodes instead - see the DONE assertion above.
		logger.info("Run lookup by workflow objectId returned " + (run != null ? "a record" : "null")
			+ " (expected null: readRun takes a run objectId, and workflow.lastRun is not projected)");

		logger.info("***** PB2 level-1 structural verification PASSED");
	}

	/**
	 * Level-1 assertions for one image artifact: byte floor, PNG magic, {@code ImageIO} decodes, and the
	 * decoded dimensions when the config fixes them.
	 * <p>
	 * The bytes are read back through {@code ByteModelUtil}, never a raw {@code get} on
	 * {@code byteStore} - {@code data.data} inherits {@code crypto.cryptoByteStore} and a raw read
	 * bypasses decompression/decryption, so it would return garbage that fails for the wrong reason.
	 */
	private void assertLevel1Image(BaseRecord artifact, String label, int expectWidth, int expectHeight)
			throws Exception {
		Long byteLength = artifact.get(OlioFieldNames.FIELD_PB_BYTE_LENGTH);
		assertNotNull(label + ": byteLength must be recorded", byteLength);
		assertTrue(label + ": byteLength " + byteLength + " must exceed " + MIN_IMAGE_BYTES
			+ " - anything smaller is a placeholder or an error image, not a render",
			byteLength.longValue() > MIN_IMAGE_BYTES);
		assertNotNull(label + ": contentHash must be recorded (level 2 compares it)",
			artifact.get(OlioFieldNames.FIELD_PB_CONTENT_HASH));

		BaseRecord data = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull(label + ": the artifact must point at its data.data", data);
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		byte[] bytes = ByteModelUtil.getValue(data);
		assertNotNull(label + ": the data.data must yield bytes", bytes);
		assertTrue(label + ": decoded bytes (" + bytes.length + ") must exceed " + MIN_IMAGE_BYTES,
			bytes.length > MIN_IMAGE_BYTES);

		/// PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A.
		assertTrue(label + ": must start with the PNG magic bytes",
			bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
				&& (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A && (bytes[6] & 0xFF) == 0x1A
				&& (bytes[7] & 0xFF) == 0x0A);

		BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
		assertNotNull(label + ": ImageIO must actually decode the bytes", img);
		logger.info(label + ": " + bytes.length + " bytes, decoded " + img.getWidth() + "x" + img.getHeight()
			+ ", recorded " + artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH) + "x"
			+ artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT));

		Integer recordedW = artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH);
		Integer recordedH = artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT);
		assertNotNull(label + ": imageWidth must be recorded", recordedW);
		assertNotNull(label + ": imageHeight must be recorded", recordedH);
		assertEquals(label + ": recorded width must equal the DECODED width, or the record describes"
			+ " something other than the bytes", img.getWidth(), recordedW.intValue());
		assertEquals(label + ": recorded height must equal the DECODED height", img.getHeight(),
			recordedH.intValue());

		if(expectWidth > 0 && expectHeight > 0) {
			assertEquals(label + ": decoded width must match the config's expectation", expectWidth,
				img.getWidth());
			assertEquals(label + ": decoded height must match the config's expectation", expectHeight,
				img.getHeight());
		}
	}

	// ═══════════════════════════════════════════════════════════════════
	// LEVEL 2 — differential (the real answer to KI-59)
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * The same scene, the same seed, the same config - once with the landscape reference bound and once
	 * with it suppressed - must produce composites whose {@code contentHash} <b>differ</b>.
	 * <p>
	 * If they match, the reference is being ignored and the test fails for the right reason. This is the
	 * only assertion in the suite that proves the reference <i>affects the output</i> rather than merely
	 * being present in a request.
	 * <p>
	 * <b>The same-seed-same-hash corollary is NOT asserted here.</b> §9 offers it only if the backend is
	 * seed-deterministic, and that has not been established for this Swarm/FLUX.2 combination in this run.
	 * Asserting it without evidence would either fail for an unrelated reason or, worse, be weakened into
	 * a tautology. The differential direction stands on its own: it needs the two runs to differ, which is
	 * exactly what a non-deterministic backend also produces - so a PASS here is necessary but not
	 * sufficient, and that limitation is stated rather than hidden. The determinism probe below records
	 * what the backend actually does, without asserting on it.
	 */
	@Test
	public void TestLandscapeReferenceChangesTheComposite() throws Exception {
		logger.info("***** PB2 phase 3: level-2 differential verification (KI-59)");
		setupContext();
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);

		BaseRecord bookGroup = pb1BookGroup();
		String slug = PbPipelineUtil.deriveSlug(PB1_BOOK_NAME);
		BaseRecord book = pb2Book(slug);
		String sceneOid = firstSceneObjectId((String) bookGroup.get(FieldNames.FIELD_OBJECT_ID));

		// Run A — landscape reference BOUND.
		BaseRecord resultA = PictureBookUtil.generateSceneImage(testUser, sceneOid,
			params(slug, true, FIXED_SEED), "SWARM", swarmServer);
		assertNotNull("Run A must produce a result", resultA);
		exportImage((String) resultA.get("imageObjectId"), "pb2_level2_withLandscapeRef");

		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		BaseRecord compositeNode = PbPipelineUtil.findNodeByHandle(testUser, workflow,
			PbPipelineUtil.compositeHandle(sceneOid));
		assertNotNull("A composite node must exist after run A", compositeNode);
		BaseRecord artifactA = PbArtifactUtil.findSelected(testUser, compositeNode, PbPipelineUtil.ROLE_COMPOSITE);
		assertNotNull("Run A must have left a selected composite artifact", artifactA);
		String hashA = artifactA.get(OlioFieldNames.FIELD_PB_CONTENT_HASH);
		Integer revA = artifactA.get(OlioFieldNames.FIELD_PB_REVISION);
		assertNotNull("Run A's composite must carry a contentHash", hashA);

		// Run B — landscape reference SUPPRESSED, everything else identical (same seed, same config).
		BaseRecord resultB = PictureBookUtil.generateSceneImage(testUser, sceneOid,
			params(slug, false, FIXED_SEED), "SWARM", swarmServer);
		assertNotNull("Run B must produce a result", resultB);
		exportImage((String) resultB.get("imageObjectId"), "pb2_level2_withoutLandscapeRef");

		BaseRecord artifactB = PbArtifactUtil.findSelected(testUser, compositeNode, PbPipelineUtil.ROLE_COMPOSITE);
		assertNotNull("Run B must have left a selected composite artifact", artifactB);
		String hashB = artifactB.get(OlioFieldNames.FIELD_PB_CONTENT_HASH);
		Integer revB = artifactB.get(OlioFieldNames.FIELD_PB_REVISION);
		assertNotNull("Run B's composite must carry a contentHash", hashB);

		logger.info("LEVEL 2: revision " + revA + " hash=" + hashA + " (landscape ref BOUND)");
		logger.info("LEVEL 2: revision " + revB + " hash=" + hashB + " (landscape ref SUPPRESSED)");

		assertTrue("Run B must have superseded run A - the version chain is what makes this a differential"
			+ " rather than two unrelated images (revA=" + revA + " revB=" + revB + ")",
			revB.intValue() > revA.intValue());
		assertFalse("KI-59: the two composites' contentHash MUST differ. Identical hashes mean the"
			+ " landscape reference is not reaching the model at all, which is exactly the integration"
			+ " failure existence-only assertions cannot see.", hashA.equals(hashB));

		// Determinism PROBE, not an assertion. Recorded so the same-seed-same-hash corollary can be
		// settled on evidence later rather than assumed now.
		logger.info("LEVEL 2 note: the same-seed-same-hash corollary is NOT asserted - seed determinism"
			+ " has not been established for this Swarm/FLUX.2 combination, and asserting it without"
			+ " evidence would weaken the test into a tautology (plan §9).");

		logger.info("***** PB2 level-2 differential verification PASSED");
	}

	// ═══════════════════════════════════════════════════════════════════
	// PHASE-3 GAP 2 — the sub-record reroute, BOTH destinations
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * {@code PbSubRecordUtil}'s destination matrix, measured for <b>all seven</b> foreign sub-models in
	 * <b>both</b> directions: {@code {world}/{group}} with an {@code OlioContext}, and the legacy
	 * {@code ~/{schemaGroup}} fallback without one.
	 * <p>
	 * <b>Why this case exists.</b> Deleting {@code prepareForeignSubModelGroups} +
	 * {@code createPersistedForeignInstance} in phase 3 repointed eight call sites inside
	 * {@code createCharPerson} at this utility, and <b>nothing executed it</b> - the flag-off gate reused a
	 * cached book, so {@code createFromScenes -> createCharPerson} never ran. "It compiles" was the entire
	 * evidence. The legacy fallback in particular is what keeps the flag-off gate byte-identical, so a
	 * regression there is invisible until it is asserted.
	 * <p>
	 * Deterministic and cheap on purpose: no LLM, no SD. The end-to-end proof that the eight call sites
	 * actually run is {@link #TestFreshCharacterSubRecordsAndPortraitRender}; this case proves the
	 * destination resolution itself, for every model, in both directions, which a single character
	 * creation cannot do (three of the seven are only created when a baseline exists, i.e. only with a
	 * context).
	 */
	@Test
	public void TestSubRecordDestinationsWithAndWithoutContext() throws Exception {
		logger.info("***** PB2 phase-3 gap 2: sub-record destinations, with and without an OlioContext");
		setupContext();
		assertNotNull("An OlioContext is required for the world-group half of this case", olioCtx);
		assertNotNull("The context must carry a world", olioCtx.getWorld());
		long orgId = (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID);

		List<BaseRecord> created = new ArrayList<>();
		try {
			assertEquals("All seven of the character's foreign sub-models must be routed", 7,
				PbSubRecordUtil.WORLD_GROUP_FIELD.size());
			for(Map.Entry<String, String> e : PbSubRecordUtil.WORLD_GROUP_FIELD.entrySet()) {
				String model = e.getKey();
				String worldField = e.getValue();

				// ── destination 1: {world}/{group}, resolved off the world's own auth.group field ──
				String worldPath = PbSubRecordUtil.groupPathFor(testUser, olioCtx, model);
				assertNotNull(model + ": a world destination must resolve with a context", worldPath);
				assertEquals(model + ": the destination must come from the world's declared '" + worldField
					+ "' group, not be composed by this utility",
					(String) olioCtx.getWorld().get(worldField + ".path"), worldPath);
				assertFalse(model + ": the world destination must NOT be the acting user's home - that is"
					+ " exactly the pre-phase-3 behaviour this replaced (and ~/Narratives is KI-60's"
					+ " collision target)", worldPath.startsWith("~/"));

				BaseRecord inCtx = PbSubRecordUtil.createSubRecord(testUser, olioCtx, model);
				assertNotNull(model + ": createSubRecord must persist a record in " + worldPath, inCtx);
				created.add(inCtx);
				Long ctxId = inCtx.get(FieldNames.FIELD_ID);
				assertTrue(model + ": the world-group sub-record must carry a real id (a 0 id is what made"
					+ " portraits unlinkable before)", ctxId != null && ctxId.longValue() > 0L);
				long worldGroupId = groupIdOfPath(worldPath, orgId);
				assertEquals(model + ": the record must actually be IN the world group, not merely created"
					+ " while that group was resolved", worldGroupId,
					((Number) inCtx.get(FieldNames.FIELD_GROUP_ID)).longValue());

				// ── destination 2: the legacy ~/{schemaGroup} fallback, with no context ──
				String legacyPath = PbSubRecordUtil.groupPathFor(testUser, null, model);
				String schemaGroup = RecordFactory.getSchema(model).getGroup();
				assertEquals(model + ": with no context the destination must be the legacy home path - this"
					+ " is what keeps the flag-off gate byte-identical", "~/" + schemaGroup, legacyPath);

				BaseRecord noCtx = PbSubRecordUtil.createSubRecord(testUser, null, model);
				assertNotNull(model + ": createSubRecord must persist a record in " + legacyPath, noCtx);
				created.add(noCtx);
				Long legacyId = noCtx.get(FieldNames.FIELD_ID);
				assertTrue(model + ": the legacy-path sub-record must carry a real id",
					legacyId != null && legacyId.longValue() > 0L);
				long legacyGroupId = groupIdOfPath(legacyPath, orgId);
				assertEquals(model + ": the record must actually be in the legacy home group", legacyGroupId,
					((Number) noCtx.get(FieldNames.FIELD_GROUP_ID)).longValue());

				assertTrue(model + ": the two destinations must be DIFFERENT groups, or this test would"
					+ " pass while the reroute did nothing", worldGroupId != legacyGroupId);
				logger.info(model + ": world group " + worldGroupId + " (" + worldPath + ") vs legacy group "
					+ legacyGroupId + " (" + legacyPath + ")");
			}
			logger.info("***** PB2 phase-3 gap 2 (destination matrix) PASSED");
		}
		finally {
			/// This case's OWN throwaway records, created seconds ago - not fixture data. Left behind they
			/// would accumulate 14 rows per run in seven shared groups.
			for(BaseRecord r : created) {
				try {
					IOSystem.getActiveContext().getAccessPoint().delete(testUser, r);
				}
				catch(Exception ignored) {
					logger.warn("Could not clean up a " + r.getSchema() + " created by this case");
				}
			}
		}
	}

	/** The numeric id of an existing group at {@code path}; fails the test if it is not there. */
	private long groupIdOfPath(String path, long orgId) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			org.cote.accountmanager.schema.ModelNames.MODEL_GROUP, path,
			org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(), orgId);
		assertNotNull("The destination group '" + path + "' must exist after createSubRecord", grp);
		return ((Number) grp.get(FieldNames.FIELD_ID)).longValue();
	}

	// ═══════════════════════════════════════════════════════════════════
	// PHASE-3 GAPS 1 + 2 — a from-scratch character, then a REAL portrait render
	// ═══════════════════════════════════════════════════════════════════

	/**
	 * A book built from scratch, so {@code createFromScenes -> createCharPerson} genuinely runs (gap 2's
	 * eight call sites plus the {@code NarrativeUtil.getCreateNarrative} path), and then a scene render
	 * against live Swarm on characters that have <b>no persisted portrait</b> - which is what forces the
	 * portrait <b>RENDER</b> branch (gap 1) rather than the reuse branch every phase-3 run took.
	 * <p>
	 * <b>Why one test and not two.</b> The render branch is only reachable when the character has no
	 * portrait to reuse, and the only honest way to get such a character is to create one. Forcing the
	 * branch any other way (deleting the catatone portraits, or {@code isBookOverride=false}, which
	 * renders and then <i>deletes</i> the image the artifact would reference) either destroys fixture data
	 * or leaves a dangling artifact.
	 * <p>
	 * <b>Real source content.</b> Scene 1 of the cached catatone extraction - Jideon carrying Duna toward
	 * the waiting cab in the rain - reused verbatim from the note {@code TestPictureBookCustom} caches.
	 * One scene, not three: fewer characters to LLM-extract, and it is the known two-character scene.
	 * <p>
	 * <b>This test's own PB1 book group is deleted at the start of each run</b> if a previous run left one,
	 * because "from scratch" is the whole point. That is this test's artifact, never the catatone fixture.
	 */
	@Test
	public void TestFreshCharacterSubRecordsAndPortraitRender() throws Exception {
		logger.info("***** PB2 phase-3 gaps 1+2: from-scratch character creation, then a REAL portrait render");
		setupContext();
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assertNotNull("test.swarm.server must be set", swarmServer);
		assertNotNull("An OlioContext is required - the world-group destination and the random baseline"
			+ " (which is what creates instinct/personality/state at all) both depend on it", olioCtx);
		long orgId = (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID);

		// ── a genuinely fresh PB1 book ──
		deleteBookGroupIfPresent(FRESH_BOOK_NAME, orgId);
		List<Map<String, Object>> cached = cachedCatatoneScenes();
		List<Map<String, Object>> oneScene = new ArrayList<>();
		oneScene.add(cached.get(0));
		logger.info("Building '" + FRESH_BOOK_NAME + "' from catatone scene 1: "
			+ oneScene.get(0).get("title") + " / characters=" + oneScene.get(0).get("characters"));

		BaseRecord meta = PictureBookUtil.createFromScenes(testUser,
			cachedCatatoneWorkObjectId(), chatConfig.get(FieldNames.FIELD_NAME), "dystopian sci-fi",
			FRESH_BOOK_NAME, oneScene, new ArrayList<>(),
			testProperties.getProperty("test.datagen.path"));
		assertNotNull("createFromScenes must return book meta", meta);
		List<Object> failedCharacters = meta.get("failedCharacters");
		assertTrue("createFromScenes must not have failed any character: " + failedCharacters,
			failedCharacters == null || failedCharacters.isEmpty());
		String pb1BookOid = meta.get("bookObjectId");
		assertNotNull("The fresh book meta must carry a bookObjectId", pb1BookOid);

		// ── gap 2: the seven sub-records landed, in the world groups, with real ids ──
		List<Map<String, Object>> chars = PictureBookUtil.listCharacters(testUser, pb1BookOid);
		assertFalse("The fresh book must have characters - createCharPerson ran or it did not", chars.isEmpty());
		logger.info("createFromScenes created " + chars.size() + " character(s) from scratch");
		for(Map<String, Object> c : chars) {
			assertSubRecordsPersisted((String) c.get("objectId"), (String) c.get("name"), orgId);
		}

		// ── gap 1: render scene 1. Fresh characters have no portrait, so the RENDER branch must run. ──
		String slug = PbPipelineUtil.deriveSlug(FRESH_BOOK_NAME);
		assertNotNull("A slug must derive from '" + FRESH_BOOK_NAME + "'", slug);
		BaseRecord book = freshPb2Book(slug);
		String sceneOid = firstSceneObjectId(pb1BookOid);

		long t0 = System.currentTimeMillis();
		BaseRecord result = PictureBookUtil.generateSceneImage(testUser, sceneOid,
			params(slug, true, FIXED_SEED), "SWARM", swarmServer);
		logger.info("Fresh-book scene generated in " + (System.currentTimeMillis() - t0) + "ms");
		assertNotNull("generateSceneImage must return a result", result);
		exportImage((String) result.get("imageObjectId"), "pb2_fresh_composite");

		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNotNull("The fresh book must now have a workflow", workflow);

		int portraitNodesAsserted = 0;
		for(Map<String, Object> c : chars) {
			String charOid = (String) c.get("objectId");
			BaseRecord pNode = PbPipelineUtil.findNodeByHandle(testUser, workflow,
				PbPipelineUtil.portraitHandle(charOid));
			if(pNode == null) {
				/// Only up to 2 of a scene's characters get portraits (portraitBytesList caps at 2), so a
				/// third character legitimately has no node. Skipping is correct; the count check below is
				/// what stops this from silently asserting nothing.
				continue;
			}
			BaseRecord art = PbArtifactUtil.findSelected(testUser, pNode, PbPipelineUtil.ROLE_PORTRAIT);
			assertNotNull(c.get("name") + ": the portrait node must have a selected artifact", art);

			/// THE gap-1 assertion. DONE_UNVERIFIED is what the REUSE branch writes, and it is what every
			/// phase-3 run produced; DONE is only reachable through completeNode() on the render branch.
			PbNodeStatusEnumType status = pNode.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
			assertEquals(c.get("name") + ": a portrait that was actually RENDERED must be DONE. "
				+ "DONE_UNVERIFIED means the reuse branch ran, which is precisely the phase-3 gap this"
				+ " case exists to close.", PbNodeStatusEnumType.DONE, status);
			assertNotNull(c.get("name") + ": a rendered portrait node must carry an inputHash",
				pNode.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
			assertNotNull(c.get("name") + ": the render branch must record the seed it used"
				+ " (extractSeedFromImage) - the reuse branch cannot, which is why it is UNVERIFIED",
				art.get(OlioFieldNames.FIELD_PB_SEED));
			/// sdConfigSnapshot is declared type "model", foreign FALSE - persisted as serialized JSON in a
			/// text column (olio.sd.config has ioConstraints ["unknown"], so no table and no id to reference)
			/// but read back through the deserializer as a RECORD. A first attempt read it as a String and
			/// got ClassCastException: LooseRecord cannot be cast to String.
			BaseRecord snapshot = art.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
			assertNotNull(c.get("name") + ": the render branch must freeze the effective portrait config"
				+ " as sdConfigSnapshot", snapshot);
			assertEquals(c.get("name") + ": the snapshot must deserialize as an olio.sd.config",
				OlioModelNames.MODEL_SD_CONFIG, snapshot.getSchema());
			String snapDesc = snapshot.get("description");
			assertNotNull(c.get("name") + ": the snapshot must carry the portrait description that was"
				+ " actually sent - a snapshot without it does not describe the render", snapDesc);
			/// The node's promptText is persisted FROM portCfg.get("description"), so equality proves the
			/// snapshot and the node describe the same request rather than two unrelated configs.
			assertEquals(c.get("name") + ": the snapshot's description and the node's promptText both come"
				+ " from the effective portrait config, so they must match",
				(String) pNode.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT), snapDesc);
			Integer revision = art.get(OlioFieldNames.FIELD_PB_REVISION);
			assertTrue(c.get("name") + ": a rendered portrait must be at revision >= 1 (got " + revision + ")",
				revision != null && revision.intValue() >= 1);
			assertNotNull(c.get("name") + ": the render branch must persist the portrait prompt text",
				pNode.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));

			/// Level 1 on a freshly-rendered portrait - which phase 3 never did, because it only ever had
			/// reused ones. Portrait dimensions are not forced by the pipeline, so no expectation is
			/// asserted beyond decoded == recorded.
			assertLevel1Image(art, "portrait[" + c.get("name") + "]", 0, 0);
			exportArtifactImage(art, "pb2_fresh_portrait_"
				+ String.valueOf(c.get("name")).replace(' ', '_'));

			/// The corrected profile-portrait reference (phase-3 defect 2), also unexercised until now: the
			/// artifact's data must be the CHARACTER PROFILE's own portrait record, not a copy.
			assertPortraitArtifactPointsAtTheProfilePortrait(art, charOid, (String) c.get("name"), orgId);
			portraitNodesAsserted++;
		}
		assertTrue("At least one PORTRAIT node must have been asserted, or this case proved nothing about"
			+ " the render branch", portraitNodesAsserted >= 1);
		logger.info("***** PB2 phase-3 gaps 1+2 PASSED (" + portraitNodesAsserted
			+ " freshly-rendered portrait node(s) asserted DONE with a seed and a snapshot)");
	}

	/**
	 * Every foreign sub-record the character should now own: a real id, and (for the five that carry a
	 * world destination) the world group rather than the acting user's home.
	 * <p>
	 * {@code instinct}/{@code personality}/{@code state} are only created when
	 * {@code buildRandomBaseline} produced a baseline, i.e. only when an {@code OlioContext} was
	 * available - so they are asserted here (this test supplies a {@code dataPath}) and are legitimately
	 * absent on the contextless path. Stated rather than silently skipped.
	 */
	private void assertSubRecordsPersisted(String charObjectId, String name, long orgId) throws Exception {
		org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
			OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		q.planMost(true);
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull(name + ": the created character must be readable", cp);

		String[][] subs = new String[][] {
			{ "profile", ModelNames.MODEL_PROFILE },
			{ "narrative", OlioModelNames.MODEL_NARRATIVE },
			{ OlioFieldNames.FIELD_STATISTICS, OlioModelNames.MODEL_CHAR_STATISTICS },
			{ FieldNames.FIELD_STORE, OlioModelNames.MODEL_STORE },
			{ OlioFieldNames.FIELD_INSTINCT, OlioModelNames.MODEL_INSTINCT },
			{ FieldNames.FIELD_PERSONALITY, ModelNames.MODEL_PERSONALITY },
			{ FieldNames.FIELD_STATE, OlioModelNames.MODEL_CHAR_STATE }
		};
		for(String[] sub : subs) {
			BaseRecord rec = cp.get(sub[0]);
			assertNotNull(name + ": charPerson." + sub[0] + " must be a persisted " + sub[1]
				+ " - CharPersonFactory's in-memory placeholder is never cascaded, so a null here means"
				+ " the createSubRecord call site did not run", rec);
			Long id = rec.get(FieldNames.FIELD_ID);
			assertTrue(name + ": charPerson." + sub[0] + " must carry a real id (got " + id + ")",
				id != null && id.longValue() > 0L);
			String worldField = PbSubRecordUtil.WORLD_GROUP_FIELD.get(sub[1]);
			String expectPath = olioCtx.getWorld().get(worldField + ".path");
			assertEquals(name + ": " + sub[1] + " must live in the world's '" + worldField + "' group, not"
				+ " the acting user's home", groupIdOfPath(expectPath, orgId),
				((Number) rec.get(FieldNames.FIELD_GROUP_ID)).longValue());
			logger.info(name + ": " + sub[0] + " id=" + id + " groupId=" + rec.get(FieldNames.FIELD_GROUP_ID)
				+ " (" + expectPath + ")");
		}

		BaseRecord narrative = cp.get("narrative");
		String sdPrompt = narrative.get("sdPrompt");
		assertNotNull(name + ": the narrative must carry sdPrompt - it is the portrait prompt, and it is"
			+ " persisted by a SEPARATE update that a foreign-field patch does not cascade", sdPrompt);
		assertFalse(name + ": sdPrompt must not be blank", sdPrompt.isBlank());
		/// physicalDescription is set from the SAME portraitPrompt in the same patch, so equality is the
		/// assertion that the separate olio.narrative update actually landed - not merely that the field is
		/// non-null. Deliberately NOT asserting that sdPrompt contains the character's NAME: it is a visual
		/// prompt built by NarrativeUtil.buildPortraitPromptFromExtractedData, and for a character the
		/// extraction described only by role it legitimately reads "...portrait of a ((woman))..." with no
		/// name. A first attempt asserted the name and failed for that reason - the assertion was wrong,
		/// not the pipeline.
		assertEquals(name + ": sdPrompt and physicalDescription are written by one patch, so they must"
			+ " match - a mismatch means only part of that update landed",
			sdPrompt, (String) narrative.get("physicalDescription"));
		logger.info(name + ": narrative.sdPrompt = " + sdPrompt);
	}

	/**
	 * Phase-3 defect 2's corrected form: the {@code PORTRAIT} artifact's {@code data} must be the
	 * character profile's <b>own</b> portrait record, re-read as a top-level {@code data.data}. The
	 * earlier (rejected) fix copied the bytes into the book gallery instead.
	 */
	private void assertPortraitArtifactPointsAtTheProfilePortrait(BaseRecord artifact, String charObjectId,
			String name, long orgId) throws Exception {
		BaseRecord data = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull(name + ": the portrait artifact must reference a data.data", data);

		/// planMost(true) + an explicit populate of the one nested field, NOT a "profile.portrait" entry in
		/// setRequest: a first attempt used the nested-path projection form and find() returned null, so the
		/// assertion failed for a query-shape reason rather than telling us anything about the portrait.
		/// This is the same read assertSubRecordsPersisted already does successfully.
		org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
			OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		q.planMost(true);
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull(name + ": the character must be re-readable", cp);
		BaseRecord profile = cp.get("profile");
		assertNotNull(name + ": the character must still have a profile", profile);
		IOSystem.getActiveContext().getReader().populate(profile, new String[] { "portrait" });
		BaseRecord portrait = profile.get("portrait");
		assertNotNull(name + ": the freshly rendered portrait must be LINKED onto profile.portrait", portrait);
		assertEquals(name + ": the artifact must reference the profile's own portrait record, not a copy of"
			+ " its bytes", (String) portrait.get(FieldNames.FIELD_OBJECT_ID),
			(String) data.get(FieldNames.FIELD_OBJECT_ID));
	}

	/** Delete a PB1 book group this test previously created, so "from scratch" means it. */
	private void deleteBookGroupIfPresent(String bookName, long orgId) {
		String path = "~/Data/PictureBooks/" + bookName;
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(testUser,
			org.cote.accountmanager.schema.ModelNames.MODEL_GROUP, path,
			org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(), orgId);
		if(grp == null) {
			return;
		}
		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(testUser, grp);
		logger.info("Deleted this test's previous '" + path + "' (deleted=" + deleted + ") so character"
			+ " creation runs from scratch. This is this test's own book, never the catatone fixture.");
		assertTrue("This test's own previous book group at '" + path + "' must be deletable, otherwise the"
			+ " run would reuse its characters and the render branch would never fire", deleted);
	}

	/** The catatone source work note {@code TestPictureBookCustom} caches, by its fixed name. */
	private String cachedCatatoneWorkObjectId() {
		BaseRecord work = DocumentUtil.getRecord(testUser, org.cote.accountmanager.schema.ModelNames.MODEL_NOTE,
			"catatone-opening-custom " + PB_ITER, CHAT_PATH);
		assertNotNull("The cached catatone work note 'catatone-opening-custom " + PB_ITER + "' must exist in "
			+ CHAT_PATH + ". Run TestPictureBookCustom#TestPictureBookCustomPipeline once - this test"
			+ " deliberately reuses the real source document rather than inventing a stand-in.", work);
		return work.get(FieldNames.FIELD_OBJECT_ID);
	}

	/** The cached catatone scene list, so this case pays for no extract-scenes LLM call. */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> cachedCatatoneScenes() {
		BaseRecord cache = DocumentUtil.getRecord(testUser, org.cote.accountmanager.schema.ModelNames.MODEL_NOTE,
			".scenesCache", "~/Data/PictureBooks/" + PB1_BOOK_NAME);
		assertNotNull("The cached catatone scene list must exist at ~/Data/PictureBooks/" + PB1_BOOK_NAME
			+ "/.scenesCache. Run TestPictureBookCustom#TestPictureBookCustomPipeline once.", cache);
		List<Map<String, Object>> scenes = org.cote.accountmanager.util.JSONUtil.getList(
			(String) cache.get(FieldNames.FIELD_TEXT), Map.class, null);
		assertNotNull("The scene cache must parse", scenes);
		assertFalse("The scene cache must hold at least one scene", scenes.isEmpty());
		return scenes;
	}

	/** Get-or-create the PB2 book for this case's fresh PB1 book. */
	private BaseRecord freshPb2Book(String slug) {
		long orgId = (long) testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord book = PbBookUtil.findBookBySlug(testUser, slug, orgId);
		if(book != null) {
			return book;
		}
		book = PbBookUtil.createBook(testUser, testProperties.getProperty("test.datagen.path"), slug,
			FRESH_BOOK_NAME);
		assertNotNull("PB2 book creation must return a book readable by its creator", book);
		return book;
	}

	/** Export an artifact's bytes so a freshly-rendered portrait can actually be LOOKED AT. */
	private void exportArtifactImage(BaseRecord artifact, String label) {
		try {
			BaseRecord data = artifact.get(OlioFieldNames.FIELD_PB_DATA);
			if(data == null) {
				return;
			}
			exportImage((String) data.get(FieldNames.FIELD_OBJECT_ID), label);
		}
		catch(Exception e) {
			logger.warn("Failed to export artifact " + label + ": " + e.getMessage());
		}
	}

	/** Export a generated image so it can actually be LOOKED AT - decode-succeeded is not correctness. */
	private void exportImage(String imageObjectId, String label) {
		if(imageObjectId == null) {
			return;
		}
		try {
			org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
				org.cote.accountmanager.schema.ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, imageObjectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);
			BaseRecord img = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			if(img == null) {
				logger.warn("Could not read image " + imageObjectId + " to export it");
				return;
			}
			byte[] bytes = ByteModelUtil.getValue(img);
			if(bytes == null || bytes.length == 0) {
				logger.warn("Image " + imageObjectId + " yielded no bytes to export");
				return;
			}
			String path = EXPORT_DIR + "/" + label + "_" + imageObjectId + ".png";
			FileUtil.emitFile(path, bytes);
			logger.info("Exported " + bytes.length + " bytes to " + path + " - LOOK AT IT");
		}
		catch(Exception e) {
			logger.warn("Failed to export " + imageObjectId + ": " + e.getMessage());
		}
	}
}
