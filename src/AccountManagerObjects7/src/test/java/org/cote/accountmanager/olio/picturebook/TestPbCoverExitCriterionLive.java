package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.ComfyGraph;
import org.cote.accountmanager.olio.illustration.ImageDims;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.illustration.WorkflowCompiler;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.CastTier;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil.CoverResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.Flux2Defaults;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.schema.type.PbTitleSafeBandEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * P9-5 (V3 §40; ledger §17), the Phase 9 exit criterion, live: one <b>book</b> and one <b>ChapBook</b> each end
 * with an APPROVED {@code COVER} artifact that reads back through the {@code GET /{book}/covers} view
 * ({@link PbServiceFacade#coverView}, the {@code approved} entry; 232 a H1). Both covers are staged AUTO by the
 * real LLM on the JVM's resolved route ({@code .42} direct, or LiteLLM in front of it) and rendered through the
 * composition-plan path on Swarm {@code .39} under the pinned FLUX.2 checkpoint.
 * <ul>
 * <li>{@link #t01_bookCoverApprovedAndReadBackThroughTheView}: a fresh book under {@code pbCustomTestUser} whose
 * population is one book-58 cast member (read-only source: their approved portrait PNG and narrative, as in
 * P9-2) and whose description is book 58's own (or, when it has none, its first scenes' titles and settings).
 * AUTO offers the population as the hero cast ({@code CastTier.POPULATION}, deterministic), the LLM stages the
 * cover (its picture is its own: logged, never asserted), the run renders, auto-approve is <b>off</b> so the
 * explicit {@link PbServiceFacade#approveArtifact} path is the one that approves, and the view reports it.</li>
 * <li>{@link #t02_chapBookCoverApprovedAndReadBackThroughTheView}: a ChapBook of the standing fixture poem (two
 * stanzas, one scene each) with the poem as its description; no population, no series, so AUTO composes without
 * a cast ({@code CastTier.NONE}, deterministic). Auto-approve is <b>on</b>: when the QA verdict approves, that is
 * the approval the view reports; when it skips, the explicit approve is used. Either way the ChapBook ends with
 * an APPROVED cover - the first time the ChapBook cover path has been exercised end to end (§18.6).</li>
 * </ul>
 * Every final PNG and the approved PNG (read back through the view's {@code dataObjectId}, the reader's path) are
 * written under {@code target/TestPbCoverExitCriterionLive/} to be looked at. Gated like every {@code .39} test
 * ({@code PB_SD_TESTS=1} / {@code -Dpb.sd.tests=1}); a skip is not a pass. Timings are logged, never asserted;
 * the JUnit timeout is a hang guard, not a threshold.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestPbCoverExitCriterionLive extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestPbCoverExitCriterionLive.class);

	private static final File OUT = new File("target/TestPbCoverExitCriterionLive");
	private static final String STYLE_PATH = "~/p95styles";
	private static final String CFG_NAME = "P9-5 cover exit";
	private static final String CAST_GENDER = "female";
	private static final byte[] PNG_MAGIC = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// Hang guard only (an LLM compose plus FLUX.2 finals plus QA on a busy box); never a pass/fail threshold.
	private static final long TIMEOUT_MS = 60L * 60L * 1000L;
	/// common.description is varchar(512).
	private static final int DESCRIPTION_MAX = 500;

	/// The standing ChapBook fixture poem (Stephen's; the same text the twelve ChapBook tests use).
	static final String POEM_TITLE = "Outside, all is pristine";
	static final String POEM_TEXT =
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

	private String server;
	private String flux2Model;
	private SDUtil sdu;
	private OrganizationContext org;
	private BaseRecord owner;
	private BaseRecord olioUser;
	private long orgId;
	private BaseRecord cfg;

	private BaseRecord book58;
	private String castName;
	private byte[] castPng;
	private int[] castDims;
	private String castPhysical;
	private String castOutfit;

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
		SdTestGate.requireModelInstalled(sdu, server, flux2Model, "the FLUX.2 cover BASE render");
		OUT.mkdirs();
		org = getTestOrganization(TestP51GraphLive.CUSTOM_ORG_PATH);
		assertNotNull("custom org", org);
		owner = getCreateUser(TestP51GraphLive.CUSTOM_OWNER, org);
		assertNotNull("custom owner", owner);
		orgId = orgOf(owner);
		olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);

		/// The LLM for AUTO: the analysis model on whatever tier LlmTestGate resolved, idempotent by name.
		cfg = OlioTestUtil.getOllamaOpenAIConfig(owner, CFG_NAME, testProperties);
		assertNotNull("chatConfig " + CFG_NAME, cfg);
		String tier = testProperties.getProperty("test.llm.resolvedTier");
		log("LLM-GATE in words: tier=" + tier + " (" + tierWords(tier) + ") route=" + testProperties.getProperty("test.llm.route")
			+ " connection=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));
		log("SD: Swarm " + server + " model " + flux2Model);
		readCast();
	}

	@After
	public void liveTeardown() {
		if(cfg != null) {
			BaseRecord conn = cfg.get("connection");
			deleteQuietly(owner, cfg);
			deleteQuietly(owner, conn);
			cfg = null;
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* The two cases                                                                                    */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TIMEOUT_MS)
	public void t01_bookCoverApprovedAndReadBackThroughTheView() throws Exception {
		log("t01_bookCoverApprovedAndReadBackThroughTheView");
		Fixture f = null;
		try {
			f = bookFixture("book");
			/// The population is the one cast member and there are no SCENE compositions and no series, so the
			/// cast offered to the LLM is the population - that part is the util's, not the model's.
			CoverResult cr = autoCover(f);
			assertEquals("the hero cast came from the population", CastTier.POPULATION, cr.castTier);
			assertTrue("the cast member was offered: " + cr.heroCast, cr.heroCast.contains(castName));
			exitCriterion(f, false);
		}
		finally {
			cleanup(f);
		}
	}

	@Test(timeout = TIMEOUT_MS)
	public void t02_chapBookCoverApprovedAndReadBackThroughTheView() throws Exception {
		log("t02_chapBookCoverApprovedAndReadBackThroughTheView");
		Fixture f = null;
		try {
			f = chapBookFixture("chap");
			/// A ChapBook has no population and no series: AUTO composes without a cast.
			CoverResult cr = autoCover(f);
			assertEquals("no cast on a ChapBook", CastTier.NONE, cr.castTier);
			assertTrue("no hero cast offered: " + cr.heroCast, cr.heroCast.isEmpty());
			exitCriterion(f, true);
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* The exit criterion: AUTO compose -> render -> APPROVED -> read back through the covers view        */
	/* ------------------------------------------------------------------------------------------------ */

	/// AUTO compose of the COVER with the real LLM. The LLM's staging is its own (logged); what is asserted is
	/// that it answered, that a complete LLM-source COVER row was written, and that the band is a real value.
	private CoverResult autoCover(Fixture f) throws Exception {
		long t0 = System.currentTimeMillis();
		CoverResult r = PbCoverUtil.getCreate(owner, f.book, PbCompositionPurposeEnumType.COVER, PbCoverUtil.Mode.AUTO, null, cfg, dataPath());
		long ms = System.currentTimeMillis() - t0;
		assertNotNull("getCreate", r);
		log("MEASURED: AUTO cover of " + f.slug + " in " + ms + " ms (informational): mode=" + r.mode + " created=" + r.created
			+ " existing=" + r.existingReturned + " source=" + r.source + " tier=" + r.castTier + " heroCast=" + r.heroCast
			+ " hard=" + r.hardFailure + " llmFailure=" + r.llmFailure + " reason=" + r.failureReason
			+ " actors=" + r.actors.size() + " elements=" + r.elements.size() + " warnings=" + r.warnings
			+ " unresolved=" + r.unresolvedNames + " placements=" + r.unrecognizedPlacements + " locationHint=" + r.locationHint);
		assertFalse("the LLM answered (" + r.failureReason + ")", r.llmFailure);
		assertTrue("AUTO wrote the cover: " + r.failureReason, r.isWritten());
		assertTrue("created now", r.created);
		assertEquals("source LLM", PbCompositionSourceEnumType.LLM, r.source);
		f.coverOid = oidOf(r.composition);
		f.cover = PbComposeUtil.readComposition(owner, f.coverOid, orgId);
		assertNotNull("cover re-read", f.cover);
		assertEquals("purpose COVER", PbCompositionPurposeEnumType.COVER.name(), status(f.cover.get(OlioFieldNames.FIELD_PB_PURPOSE)));
		String band = status(f.cover.get(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));
		log("AUTO staged: band=" + band + " shot=" + status(f.cover.get(OlioFieldNames.FIELD_PB_SHOT_TYPE))
			+ " viewpoint=" + status(f.cover.get(OlioFieldNames.FIELD_PB_VIEWPOINT))
			+ " setting=" + f.cover.get(OlioFieldNames.FIELD_PB_SETTING_TEXT)
			+ " action=" + f.cover.get(OlioFieldNames.FIELD_PB_ACTION) + " mood=" + f.cover.get(OlioFieldNames.FIELD_PB_MOOD));
		assertNotNull("titleSafeBand written", band);
		assertNotEquals("titleSafeBand is a real value on an LLM cover", PbTitleSafeBandEnumType.UNKNOWN.toString(), band);
		for(BaseRecord row : PbComposeUtil.listActors(owner, f.cover)) {
			BaseRecord ch = (row.hasField(OlioFieldNames.FIELD_PB_CHARACTER) ? row.get(OlioFieldNames.FIELD_PB_CHARACTER) : null);
			log("  actor " + row.get(FieldNames.FIELD_NAME) + " character=" + (ch != null ? oidOf(ch) : null)
				+ " role=" + status(row.get(OlioFieldNames.FIELD_PB_ROLE)) + " pose=" + row.get(OlioFieldNames.FIELD_PB_POSE)
				+ " box=(" + row.get(OlioFieldNames.FIELD_PB_X) + "," + row.get(OlioFieldNames.FIELD_PB_Y) + ","
				+ row.get(OlioFieldNames.FIELD_PB_W) + "," + row.get(OlioFieldNames.FIELD_PB_H) + ")");
		}
		for(BaseRecord el : PbComposeUtil.listElements(owner, f.cover)) {
			log("  element " + el.get(FieldNames.FIELD_NAME) + " type=" + status(el.get(OlioFieldNames.FIELD_PB_ELEMENT_TYPE)) + " " + el.get(FieldNames.FIELD_DESCRIPTION));
		}
		return r;
	}

	/**
	 * Render the COVER through the facade's synchronous seam (same gates as the route), approve, and read the
	 * result back through the covers view exactly as {@code GET /{book}/covers} would return it.
	 *
	 * @param autoApprove whether the book's auto-approve is switched on for the run. Off: the explicit
	 *                    {@link PbServiceFacade#approveArtifact} is the approval. On: the QA verdict decides
	 *                    (a model result); when it skipped, the explicit approve is used instead.
	 */
	private void exitCriterion(Fixture f, boolean autoApprove) throws Exception {
		f.style = pinBookStyle(owner, f.book, f.tag, flux2Model);
		assertTrue("setAutoApprove(" + autoApprove + ")", PbBookUtil.setAutoApprove(owner, f.book, autoApprove));
		CacheUtil.clearCache();
		f.book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		assertEquals("autoApprove flag", Boolean.valueOf(autoApprove), f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));

		/// Before the render: the view already shows the composition, with no node and no image.
		Map<String, Object> before = coverEntry(PbServiceFacade.coverView(owner, f.bookOid));
		log("covers view before the render: " + before);
		assertEquals("the view's composition is the AUTO one", f.coverOid, before.get("compositionObjectId"));
		assertEquals("source LLM in the view", PbCompositionSourceEnumType.LLM.name(), before.get("source"));
		assertNull("no node before the render", before.get("nodeObjectId"));
		assertNull("no approved image before the render", before.get("approved"));
		assertNull("no latest image before the render", before.get("latest"));
		assertEquals("empty chain before the render", 0, intOf(before.get("chainLength")));

		/// The render, synchronous, under the facade's gates (404 book -> 409 v1 -> 403 updater -> cover exists).
		long t0 = System.currentTimeMillis();
		Map<String, Object> out = PbServiceFacade.renderCover(owner, f.bookOid, "cover", sdu);
		long wallMs = System.currentTimeMillis() - t0;
		assertNotNull("run DTO", out);
		log("MEASURED: cover run of " + f.slug + " on Swarm " + server + " model=" + flux2Model + " status=" + out.get("runStatus")
			+ " strategy=" + out.get("strategy") + " planned=" + out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)
			+ " rendered=" + out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED) + " qaMode=" + out.get(PbNodeExecutor.KEY_QA_MODE)
			+ " autoApproved=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVED) + " skipped=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)
			+ " stageErrors=" + out.get(PbNodeExecutor.KEY_STAGE_ERRORS) + " degradations=" + PbNodeExecutor.degradationsOf(out)
			+ " wallMs=" + wallMs + " (informational, never a threshold)");
		assertEquals("executed synchronously", Boolean.TRUE, out.get("executed"));
		assertEquals("run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
		assertEquals("compositionObjectId", f.coverOid, out.get("compositionObjectId"));
		assertEquals("purpose", PbCompositionPurposeEnumType.COVER.name(), status(out.get("purpose")));
		String runOid = str(out.get("runObjectId"));
		assertNotNull("runObjectId", runOid);
		String nodeOid = str(out.get("nodeObjectId"));
		assertNotNull("nodeObjectId", nodeOid);
		f.node = PbGraphUtil.readNode(owner, nodeOid, orgId);
		assertNotNull("node", f.node);
		assertEquals("node handle", PbPipelineUtil.compositionPlanHandle(f.coverOid), f.node.get(OlioFieldNames.FIELD_PB_HANDLE));
		assertEquals("node type", PbNodeTypeEnumType.RENDER_PLAN.name(), status(f.node.get(OlioFieldNames.FIELD_PB_NODE_TYPE)));
		int rendered = intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED));
		assertTrue("at least one final rendered, got " + rendered, rendered >= 1);
		List<Map<String, Object>> vs = variants(out);
		for(int i = 0; i < vs.size(); i++) {
			log("variant " + i + " QA: " + vs.get(i).get(PbNodeExecutor.KEY_VARIANT_QA));
		}

		/// The finals on the database: every BASE artifact of this run is a real PNG; written out to be looked at.
		List<BaseRecord> finals = runFinals(f, runOid);
		assertEquals("base artifacts of the run", rendered, finals.size());
		for(int i = 0; i < finals.size(); i++) {
			byte[] png = artifactPng(f, finals.get(i), "cover-v" + i);
			int[] dims = ImageDims.decode(png);
			assertTrue("variant " + i + " has dimensions", dims[0] > 0 && dims[1] > 0);
			String req = finals.get(i).get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
			assertNotNull("variant " + i + " generator request", req);
			assertTrue("variant " + i + " generator request is sanitized", PbArtifactUtil.isSanitized(req));
			log("variant " + i + " references=" + PbArtifactUtil.referenceArtifactIdsOf(finals.get(i)));
			assertFlux2FamilyKnobs(i, req);
		}
		log("configNotApplied=" + out.get(PbNodeExecutor.KEY_CONFIG_NOT_APPLIED));

		/// Approval. Auto-approve is a QA verdict on a real image - a model result - so only its invariant is
		/// asserted; whatever it did, the book must end with exactly one APPROVED cover artifact.
		Boolean autoApproved = (Boolean) out.get(PbNodeExecutor.KEY_AUTO_APPROVED);
		String approvedOid;
		String how;
		if(Boolean.TRUE.equals(autoApproved)) {
			assertTrue("auto-approve happened only with the flag on", autoApprove);
			approvedOid = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			assertNotNull("auto-approved artifact", approvedOid);
			assertTrue("the auto-approved artifact is one of this run's finals: " + approvedOid, oids(finals).contains(approvedOid));
			how = "auto-approved by QA";
		}
		else {
			if(autoApprove) {
				assertNotNull("auto-approve on but not approved: a skip reason is present", str(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)));
				log("auto-approve skipped: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED) + "; approving explicitly");
			}
			for(BaseRecord a : finals) {
				assertTrue("no final reads APPROVED before the explicit approve: " + oidOf(a),
					PbArtifactUtil.reviewStatusOf(a) != PbReviewStatusEnumType.APPROVED);
			}
			/// The reader's "Approve latest": the highest-revision final.
			BaseRecord latest = finals.get(finals.size() - 1);
			approvedOid = oidOf(latest);
			Map<String, Object> res = PbServiceFacade.approveArtifact(owner, f.bookOid, approvedOid);
			log("approveArtifact(" + approvedOid + ") -> " + res);
			assertEquals("approve result is the artifact", approvedOid, res.get("objectId"));
			assertEquals("approve result reads APPROVED", PbReviewStatusEnumType.APPROVED.name(), res.get("reviewStatus"));
			assertEquals("approve result is selected", Boolean.TRUE, res.get("selected"));
			assertEquals("approve result names the node", nodeOid, res.get("nodeObjectId"));
			how = "approved explicitly through PbServiceFacade.approveArtifact";
		}
		BaseRecord approvedRec = PbArtifactUtil.readArtifact(owner, approvedOid, orgId);
		assertNotNull("approved artifact re-read", approvedRec);
		assertEquals("it reads APPROVED on the database", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approvedRec));
		int approvedCount = 0;
		for(BaseRecord a : PbArtifactUtil.listChain(owner, f.node, PbPipelineUtil.ROLE_BASE)) {
			if(PbArtifactUtil.reviewStatusOf(a) == PbReviewStatusEnumType.APPROVED) approvedCount++;
		}
		assertEquals("exactly one APPROVED artifact in the cover's base chain", 1, approvedCount);

		/// The exit criterion proper: GET /{book}/covers reports the approved cover.
		Map<String, Object> view = PbServiceFacade.coverView(owner, f.bookOid);
		assertEquals("view bookObjectId", f.bookOid, view.get("bookObjectId"));
		assertNotNull("view workflowObjectId", view.get("workflowObjectId"));
		Map<String, Object> covers = coversOf(view);
		assertNull("no TITLE_PAGE", covers.get(PbCompositionPurposeEnumType.TITLE_PAGE.name()));
		assertNull("no CHAPTER_OPENER", covers.get(PbCompositionPurposeEnumType.CHAPTER_OPENER.name()));
		assertNull("no SERIES_ART", covers.get(PbCompositionPurposeEnumType.SERIES_ART.name()));
		Map<String, Object> entry = coverEntry(view);
		log("covers view after approve (" + how + "): " + entry);
		assertEquals("entry composition", f.coverOid, entry.get("compositionObjectId"));
		assertEquals("entry purpose", PbCompositionPurposeEnumType.COVER.name(), entry.get("purpose"));
		assertEquals("entry source", PbCompositionSourceEnumType.LLM.name(), entry.get("source"));
		assertEquals("entry node", nodeOid, entry.get("nodeObjectId"));
		String nodeStatus = str(entry.get("nodeStatus"));
		assertTrue("cover node reads DONE_UNVERIFIED or DONE, got " + nodeStatus,
			PbNodeStatusEnumType.DONE_UNVERIFIED.name().equals(nodeStatus) || PbNodeStatusEnumType.DONE.name().equals(nodeStatus));
		assertEquals("approve pinned the node", Boolean.TRUE, entry.get("pinned"));
		assertNull("no lastError", entry.get("lastError"));
		assertEquals("chain length is the finals", finals.size(), intOf(entry.get("chainLength")));
		assertEquals("the selected artifact is the approved one", approvedOid, entry.get("selectedArtifactObjectId"));
		assertEquals("headline reviewStatus APPROVED", PbReviewStatusEnumType.APPROVED.name(), entry.get("reviewStatus"));
		Map<String, Object> approved = ref(entry.get("approved"), "approved");
		assertEquals("approved.artifactObjectId", approvedOid, approved.get("artifactObjectId"));
		assertEquals("approved.reviewStatus", PbReviewStatusEnumType.APPROVED.name(), approved.get("reviewStatus"));
		assertEquals("approved.revision is the artifact's", approvedRec.get(OlioFieldNames.FIELD_PB_REVISION), approved.get("revision"));
		String dataOid = str(approved.get("dataObjectId"));
		assertNotNull("approved.dataObjectId", dataOid);
		assertNotNull("approved.imageGroupPath", approved.get("imageGroupPath"));
		assertNotNull("approved.imageName", approved.get("imageName"));
		assertEquals("approved.imageContentType", "image/png", approved.get("imageContentType"));
		assertTrue("the image lives in the book's artifact group: " + approved.get("imageGroupPath"),
			str(approved.get("imageGroupPath")).endsWith(PbBookUtil.artifactGroupPath(f.slug).replace("~", "")));
		Map<String, Object> latest = ref(entry.get("latest"), "latest");
		assertNotNull("latest.artifactObjectId", latest.get("artifactObjectId"));

		/// The reader's image: the bytes the view points at, read fresh, a real PNG; written out to be looked at.
		BaseRecord data = TestP51GraphLive.freshReadData(owner, dataOid, orgId);
		assertNotNull("approved data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("approved image bytes", bytes != null && bytes.length > 1024);
		assertPng("approved cover", bytes);
		int[] dims = ImageDims.decode(bytes);
		File outFile = new File(OUT, f.slug + "-approved.png");
		Files.write(outFile.toPath(), bytes);
		log("MEASURED: APPROVED cover of " + f.slug + " (" + how + ") artifact=" + approvedOid + " data=" + dataOid
			+ " " + dims[0] + "x" + dims[1] + " bytes=" + bytes.length + " band=" + entry.get("titleSafeBand")
			+ " image=" + approved.get("imageGroupPath") + "/" + approved.get("imageName") + " png=" + outFile.getAbsolutePath());
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Fixtures                                                                                         */
	/* ------------------------------------------------------------------------------------------------ */

	private static final class Fixture {
		String tag;
		String slug;
		String bookOid;
		BaseRecord book;
		BaseRecord node;
		BaseRecord cover;
		String coverOid;
		String charOid;
		String portraitOid;
		BaseRecord style;
		BaseRecord poem;
	}

	/// A fresh book whose population is the one book-58 cast member (narrative + APPROVED portrait, the P9-2
	/// shape) and whose description is book 58's (or its first scenes').
	private Fixture bookFixture(String tag) throws Exception {
		Fixture f = new Fixture();
		f.tag = tag;
		f.slug = "p95" + tag + TestP51GraphLive.shortId();
		BaseRecord bk = PbBookUtil.createBook(owner, dataPath(), f.slug, "P95 exit cover " + f.slug);
		assertNotNull("fixture book", bk);
		f.bookOid = oidOf(bk);
		try {
			BookContext bctx = PbBookUtil.openBookContext(owner, bk);
			assertNotNull("book context", bctx);
			assertNotNull("book world", bctx.getWorld());
			BaseRecord popGroup = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("population group", popGroup);

			f.charOid = createCharPersonIn(owner, popGroup, popPath, castName, CAST_GENDER);
			BaseRecord cp = readChar(owner, f.charOid);
			giveNarrative(owner, cp, castPhysical, castOutfit.isEmpty() ? null : castOutfit);
			f.book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			assertNotNull("book re-read", f.book);
			BaseRecord art = approvedPortraitFor(owner, f.book, f.slug, f.charOid, castPng, castDims[0], castDims[1]);
			f.portraitOid = oidOf(art);
			describeBook(owner, f.book, book58Premise());
			f.book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			log(tag + " fixture book " + f.slug + " " + f.bookOid + " cast " + castName + "=" + f.charOid + " portrait=" + f.portraitOid
				+ " (" + castDims[0] + "x" + castDims[1] + ") description=" + f.book.get(FieldNames.FIELD_DESCRIPTION));
			return f;
		}
		catch(Exception | AssertionError e) {
			cleanup(f);
			throw e;
		}
	}

	/// A ChapBook of the standing poem (two stanzas -> two scenes at five lines a page), no chatConfig for the
	/// scene prompts (the fallback shape), the poem as its description.
	private Fixture chapBookFixture(String tag) throws Exception {
		Fixture f = new Fixture();
		f.tag = tag;
		f.slug = "p95" + tag + TestP51GraphLive.shortId();
		f.poem = createPoem(owner, "~/Data/P95-" + f.slug, POEM_TITLE + " " + f.slug, POEM_TEXT);
		assertNotNull("poem", f.poem);
		BaseRecord bk = ChapBookUtil.createChapBook(owner, dataPath(), f.slug, "P95 exit ChapBook " + f.slug,
			Arrays.asList(oidOf(f.poem)), 5, null);
		assertNotNull("fixture ChapBook", bk);
		f.bookOid = oidOf(bk);
		try {
			f.book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			assertNotNull("ChapBook re-read", f.book);
			assertEquals("bookType CHAPBOOK", "CHAPBOOK", status(f.book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE)));
			assertTrue("ChapBooks render on v3 in this JVM", PbRenderPath.isV3(f.book));
			List<BaseRecord> scenes = PbBookUtil.listScenes(owner, f.book);
			assertFalse("the poem made at least one scene", scenes.isEmpty());
			describeBook(owner, f.book, POEM_TEXT);
			f.book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			log(tag + " fixture ChapBook " + f.slug + " " + f.bookOid + " scenes=" + scenes.size() + " poem=" + oidOf(f.poem));
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
				logger.error("[p95] cleanup " + f.slug + " failed: " + e.getMessage(), e);
			}
		}
		deleteQuietly(owner, f.style);
		deleteQuietly(owner, f.poem);
	}

	/// Book 58's own description when it has one; otherwise its first scenes' titles and settings. Either way
	/// Stephen's content, never a synthetic premise.
	private String book58Premise() {
		String desc = (book58.hasField(FieldNames.FIELD_DESCRIPTION) ? TestP51GraphLive.text(book58.get(FieldNames.FIELD_DESCRIPTION), "") : "");
		if(desc.isBlank()) {
			StringBuilder sb = new StringBuilder();
			for(BaseRecord s : PbBookUtil.listScenes(owner, book58)) {
				String title = TestP51GraphLive.text(s.get(OlioFieldNames.FIELD_PB_TITLE), "");
				String setting = TestP51GraphLive.text(s.get(OlioFieldNames.FIELD_PB_SETTING), "");
				String summary = TestP51GraphLive.text(s.get(OlioFieldNames.FIELD_PB_SUMMARY), "");
				String part = (!summary.isBlank() ? summary : (title + (setting.isBlank() ? "" : ": " + setting))).trim();
				if(part.isBlank()) continue;
				if(sb.length() + part.length() + 2 > DESCRIPTION_MAX) break;
				if(sb.length() > 0) sb.append(" ");
				sb.append(part);
				if(!part.endsWith(".")) sb.append(".");
			}
			desc = sb.toString();
		}
		if(desc.length() > DESCRIPTION_MAX) desc = desc.substring(0, DESCRIPTION_MAX);
		assertFalse("book 58 yields a premise (description or scenes)", desc.isBlank());
		return desc;
	}

	private void describeBook(BaseRecord u, BaseRecord bk, String description) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(bk, OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_DESCRIPTION);
		patch.set(FieldNames.FIELD_DESCRIPTION, description);
		assertNotNull("book description patch", ioContext.getAccessPoint().update(u, patch));
		CacheUtil.clearCache();
		BaseRecord fresh = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertEquals("description persisted", description, fresh.get(FieldNames.FIELD_DESCRIPTION));
	}

	private BaseRecord createPoem(BaseRecord u, String groupPath, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, u, null, plist);
		poem.set("text", text);
		poem.set("title", name);
		BaseRecord created = ioContext.getAccessPoint().create(u, poem);
		assertNotNull("create poem " + name, created);
		return created;
	}

	private BaseRecord approvedPortraitFor(BaseRecord u, BaseRecord book, String slug, String charOid, byte[] bytes, int w, int h) throws Exception {
		long oid = orgOf(u);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, book, PbBookUtil.workflowGroupPath(slug));
		BaseRecord pNode = PbGraphUtil.addNode(u, wf, PbPipelineUtil.portraitHandle(charOid), PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("portrait node", pNode);
		String groupPath = PbBookUtil.artifactGroupPath(slug);
		BaseRecord data = persistPng(u, groupPath, "p95-portrait-" + TestP51GraphLive.shortId() + ".png", bytes);
		BaseRecord art = PbArtifactUtil.persistArtifact(u, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE, groupPath, data, null, null, null);
		assertNotNull("portrait artifact", art);
		assertTrue("portrait metrics", PbArtifactUtil.recordImageMetrics(u, art, bytes, "image/png", w, h, null));
		assertNotNull("approve", PbArtifactUtil.approve(u, PbArtifactUtil.readArtifact(u, oidOf(art), oid), "p95"));
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

	/// P10-A 5 (239 c): every task is pinned to the FLUX.2 checkpoint, so the request each final keeps must carry the
	/// FLUX.2 family knobs - cfg inside capabilities.json's flux2* range and the resource's sampler / scheduler (the
	/// fixture config carries no flux2Sampler / flux2Scheduler) - never the SDXL-tuned cfg 7 / dpmpp_2m / karras that
	/// 239 c measured. Both request records carry the knobs under the Swarm key names: the T2I record as sent, the
	/// graph record (graphHash present) as the graph was compiled from, with the negative inside its `inputs` as the
	/// `negative` text tag. The knobs are logged so the hand-back can quote them.
	private void assertFlux2FamilyKnobs(int i, String requestJson) {
		Map<?, ?> sent = JSONUtil.importObject(requestJson, Map.class);
		assertNotNull("variant " + i + " generator request parses", sent);
		boolean graph = (sent.get("graphHash") != null);
		Object negative = sent.get("negativeprompt");
		if(graph && sent.get("inputs") instanceof Map) {
			negative = ((Map<?, ?>) sent.get("inputs")).get(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_NEGATIVE));
		}
		log("variant " + i + " request knobs (" + (graph ? "graph " + sent.get("graphHash") : "t2i") + "): model=" + sent.get("model")
			+ " cfgscale=" + sent.get("cfgscale") + " steps=" + sent.get("steps") + " sampler=" + sent.get("sampler")
			+ " scheduler=" + sent.get("scheduler") + " negative=" + negative);
		RendererCapabilities caps = RendererCapabilities.forModel(flux2Model);
		assertNotNull("capabilities for " + flux2Model, caps);
		assertEquals("the pinned checkpoint is a FLUX.2 family model", RendererCapabilities.PATTERN_FLUX2, caps.getPattern());
		assertTrue("variant " + i + " cfgscale is a number: " + sent.get("cfgscale"), sent.get("cfgscale") instanceof Number);
		double cfgSent = ((Number) sent.get("cfgscale")).doubleValue();
		assertTrue("variant " + i + " cfg " + cfgSent + " inside " + caps.getPattern() + "'s range [" + caps.getCfgMin() + ", " + caps.getCfgMax() + "]",
			cfgSent >= caps.getCfgMin() && cfgSent <= caps.getCfgMax());
		assertEquals("variant " + i + " sampler is the FLUX.2 resource's", Flux2Defaults.sampler(), sent.get("sampler"));
		assertEquals("variant " + i + " scheduler is the FLUX.2 resource's", Flux2Defaults.scheduler(), sent.get("scheduler"));
	}

	private BaseRecord pinBookStyle(BaseRecord u, BaseRecord bk, String tag, String model) throws Exception {
		long oid = orgOf(u);
		Map<String, String> pins = new LinkedHashMap<>();
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] { PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION, PbModelTaskEnumType.SCENE_DIRECT, PbModelTaskEnumType.INPAINT }) {
			pins.put(ModelTaskKeys.key(t), model);
		}
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p95-" + tag + "-" + TestP51GraphLive.shortId(), STYLE_PATH, oid);
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

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Book-58 cast member (read-only): the real portrait and narrative                                 */
	/* ------------------------------------------------------------------------------------------------ */

	private void readCast() throws Exception {
		book58 = PbBookUtil.findBookBySlug(owner, TestP51GraphLive.CUSTOM_BOOK_SLUG, orgId);
		assertNotNull("book " + TestP51GraphLive.CUSTOM_BOOK_SLUG, book58);
		Map<String, String> oidByName = new LinkedHashMap<>();
		for(Map<String, Object> c : PictureBookUtil.listCharacters(owner, oidOf(book58))) {
			oidByName.put(String.valueOf(c.get("name")), String.valueOf(c.get("objectId")));
		}
		String name = TestP51GraphLive.CAST_ORDER[0];
		String oid = oidByName.get(name);
		assertNotNull("book-58 cast member " + name + " in " + oidByName.keySet(), oid);
		BaseRecord person = TestP51GraphLive.readPerson(owner, oid, orgId);
		assertNotNull("person " + name, person);
		BaseRecord profile = person.get(FieldNames.FIELD_PROFILE);
		assertNotNull("profile " + name, profile);
		BaseRecord portrait = profile.get("portrait");
		assertNotNull("portrait ref " + name, portrait);
		String portraitOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord data = TestP51GraphLive.freshReadData(owner, portraitOid, orgId);
		if(data == null && olioUser != null) data = TestP51GraphLive.freshReadData(olioUser, portraitOid, orgId);
		assertNotNull("portrait data " + name, data);
		byte[] png = ByteModelUtil.getValue(data);
		assertTrue("portrait bytes " + name, png != null && png.length > 1024);
		assertPng("portrait " + name, png);
		castName = name;
		castPng = png;
		castDims = ImageDims.decode(png);
		BaseRecord narrative = person.get(OlioFieldNames.FIELD_NARRATIVE);
		String physical = (narrative != null ? TestP51GraphLive.text(narrative.get("physicalDescription"), "") : "");
		String outfit = (narrative != null ? TestP51GraphLive.text(narrative.get("outfitDescription"), "") : "");
		castPhysical = physical.isEmpty() ? name + ", as drawn in the portrait" : physical;
		castOutfit = outfit;
		log("cast " + name + " portrait " + castDims[0] + "x" + castDims[1] + " physical=" + castPhysical.length() + " chars outfit=" + outfit.length() + " chars");
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Run / view inspection                                                                            */
	/* ------------------------------------------------------------------------------------------------ */

	/// The run's BASE artifacts on the composition-plan node, re-read in full, in variant order.
	private List<BaseRecord> runFinals(Fixture f, String runOid) {
		List<BaseRecord> chain = PbArtifactUtil.listChain(owner, f.node, PbPipelineUtil.ROLE_BASE);
		List<BaseRecord> finals = new ArrayList<>();
		for(BaseRecord a : chain) {
			BaseRecord full = PbArtifactUtil.readArtifact(owner, oidOf(a), orgId);
			assertNotNull("artifact " + oidOf(a), full);
			if(runOid.equals(PbArtifactUtil.attemptOf(full))) {
				finals.add(full);
			}
		}
		finals.sort(Comparator.comparingInt(PbArtifactUtil::variantIndexOf));
		return finals;
	}

	/// The artifact's bytes, read fresh from its data.data; asserts a real PNG and writes it under target/ to be looked at.
	private byte[] artifactPng(Fixture f, BaseRecord artifact, String label) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull(label + " points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			ioContext.getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = TestP51GraphLive.freshReadData(owner, dataOid, orgId);
		assertNotNull(label + " data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue(label + " real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		assertPng(label, bytes);
		File outFile = new File(OUT, f.slug + "-" + label + ".png");
		Files.write(outFile.toPath(), bytes);
		log("MEASURED: " + label + " seed=" + seedOf(artifact) + " bytes=" + bytes.length
			+ " width=" + artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH) + " height=" + artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT)
			+ " durationMs=" + artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS) + " reviewStatus=" + PbArtifactUtil.reviewStatusOf(artifact)
			+ " png=" + outFile.getAbsolutePath());
		return bytes;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> variants(Map<String, Object> out) {
		Object vs = out.get(PbNodeExecutor.KEY_VARIANTS);
		assertTrue("variants is a list: " + vs, vs instanceof List);
		return (List<Map<String, Object>>) vs;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> coversOf(Map<String, Object> view) {
		Object covers = view.get("covers");
		assertTrue("covers is a map: " + covers, covers instanceof Map);
		return (Map<String, Object>) covers;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> coverEntry(Map<String, Object> view) {
		Object e = coversOf(view).get(PbCompositionPurposeEnumType.COVER.name());
		assertTrue("the COVER entry is present: " + e, e instanceof Map);
		return (Map<String, Object>) e;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> ref(Object v, String what) {
		assertTrue(what + " image ref is present: " + v, v instanceof Map);
		return (Map<String, Object>) v;
	}

	private static List<String> oids(List<BaseRecord> recs) {
		List<String> out = new ArrayList<>();
		for(BaseRecord r : recs) out.add(oidOf(r));
		return out;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Small helpers                                                                                     */
	/* ------------------------------------------------------------------------------------------------ */

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) return;
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("[p95] cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + " was not deleted");
			}
		}
		catch(Exception e) {
			logger.error("[p95] cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}

	/// The LlmTestGate tier, said in words so a report cannot mistake "local" for Azure.
	private static String tierWords(String tier) {
		String t = lower(tier);
		if(t.contains("local")) return "the in-stack Ollama container on this host, not Azure, not .42";
		if(t.contains("azure")) return "Azure through LiteLLM";
		if(t.contains("remote")) return "the remote Ollama on the DGX Spark at .42";
		return "unknown tier";
	}

	private static long seedOf(BaseRecord a) {
		Object s = a.get(OlioFieldNames.FIELD_PB_SEED);
		return (s instanceof Number ? ((Number) s).longValue() : 0L);
	}

	private static int intOf(Object v) {
		assertTrue("a number, got " + v, v instanceof Number);
		return ((Number) v).intValue();
	}

	/// Enum values come back as the enum or, through JSON, the raw string; never hand a generic get() to
	/// String.valueOf - inference picks the char[] overload and throws ClassCastException.
	private static String status(Object v) {
		return (v != null ? v.toString().toUpperCase(Locale.ROOT) : null);
	}

	private static String str(Object v) {
		return (v != null ? v.toString() : null);
	}

	private static String lower(String s) {
		return (s != null ? s.toLowerCase(Locale.ROOT) : "");
	}

	private static void assertPng(String label, byte[] bytes) {
		assertTrue(label + " is a PNG", isPng(bytes));
	}

	private static boolean isPng(byte[] bytes) {
		if(bytes == null || bytes.length < PNG_MAGIC.length) return false;
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			if(bytes[i] != PNG_MAGIC[i]) return false;
		}
		return true;
	}

	private String prop(String key, String def) {
		String v = testProperties.getProperty(key);
		return (v != null && !v.trim().isEmpty() ? v.trim() : def);
	}

	private static void log(String msg) {
		logger.info("[p95] " + msg);
	}
}
