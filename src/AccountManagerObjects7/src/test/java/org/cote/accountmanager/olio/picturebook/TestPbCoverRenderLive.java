package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ImageDims;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil.CoverResult;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * P9-2 (V3 §40; ledger §17), live: a book cover rendered through the composition-plan path on Swarm {@code .39}
 * with a <b>referenced</b> actor. One book-58 cast member (read-only: their approved portrait PNG and narrative)
 * is re-created as a character of a fresh book under {@code pbCustomTestUser}, given that portrait as an APPROVED
 * PORTRAIT artifact, and placed as the HERO actor of a MANUAL {@code COVER} composition with a TOP title-safe band.
 * <p>
 * {@link #coverRenderedFromReferencedActorWithTopTitleBand}: the plan marks the actor {@code referenced} in BASE
 * (strategy {@code DIRECT_REFERENCE}), reserves the top quarter and clips the actor box below it; a STANDARD run
 * on {@code .39} renders the preset's two finals under the pinned FLUX.2 checkpoint, each a real PNG whose
 * sanitized generator request names the portrait artifact as its reference; QA CANONICAL judges variant 0 through
 * whatever provider production selects (a model result, logged, never asserted); the auto-approve outcome obeys the
 * invariant (approved => the approved artifact is one of this run's finals and reads APPROVED; otherwise a skip
 * reason is present); the node reads DONE / DONE_UNVERIFIED; nothing composed a scene and the cover's revision is
 * untouched. Both PNGs are written under {@code target/TestPbCoverRenderLive/} to be looked at.
 * <p>
 * Gated like every {@code .39} test ({@code PB_SD_TESTS=1} / {@code -Dpb.sd.tests=1}); a skip is not a pass. No
 * LLM is required: the run carries no chatConfig, so the QA hook falls back the way production does. Timings are
 * logged, never asserted; the JUnit timeout is a hang guard, not a threshold.
 */
public class TestPbCoverRenderLive extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestPbCoverRenderLive.class);

	private static final File OUT = new File("target/TestPbCoverRenderLive");
	private static final String STYLE_PATH = "~/p92styles";
	private static final String COVER_SETTING = "a basalt headland above a grey sea at dusk, gulls wheeling";
	private static final String COVER_ACTION = "a lone figure in a storm coat faces the sea";
	private static final String COVER_MOOD = "wind-scoured and expectant";
	private static final String TOP_CLAUSE = "top quarter of the frame is calm, uncluttered negative space";
	private static final String CAST_GENDER = "female";
	private static final byte[] PNG_MAGIC = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final double EPS = 1e-9;
	/// Hang guard only (two FLUX.2 finals + QA on a busy box); never a pass/fail threshold.
	private static final long TIMEOUT_MS = 60L * 60L * 1000L;

	private String server;
	private String flux2Model;
	private SDUtil sdu;
	private OrganizationContext org;
	private BaseRecord owner;
	private BaseRecord olioUser;
	private long orgId;

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
		log("SD: Swarm " + server + " model " + flux2Model + "; no LLM on this run (QA provider = production fallback)");
		org = getTestOrganization(TestP51GraphLive.CUSTOM_ORG_PATH);
		assertNotNull("custom org", org);
		owner = getCreateUser(TestP51GraphLive.CUSTOM_OWNER, org);
		assertNotNull("custom owner", owner);
		orgId = orgOf(owner);
		olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		readCast();
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* The case                                                                                         */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = TIMEOUT_MS)
	public void coverRenderedFromReferencedActorWithTopTitleBand() throws Exception {
		log("coverRenderedFromReferencedActorWithTopTitleBand");
		Fixture f = null;
		try {
			f = fixture("cov");

			/// The plan before the run: COVER, the top quarter reserved, the one actor is the book-58 cast member,
			/// referenced in BASE through the approved portrait, its box pushed below the band.
			RenderPlan plan = PbRenderPlanUtil.buildCompositionPlan(owner, f.book, f.cover, f.node, PlanOptions.NONE, sdu.getApiType(), sdu.getServer());
			assertNotNull("plan", plan);
			log("plan: purpose=" + plan.getPurpose() + " reserved=" + boxText(plan.getReserved()) + " actors=" + plan.getActors().size()
				+ " strategy=" + plan.getStrategy().getName() + " (" + plan.getStrategy().getReason() + ") degradations=" + degradationText(plan));
			assertEquals("purpose", RenderPlan.PURPOSE_COVER, plan.getPurpose());
			assertBox("reserved", plan.getReserved(), 0.0, 0.0, 1.0, PbLayoutUtil.TITLE_SAFE_BAND_HEIGHT);
			assertEquals("one actor", 1, plan.getActors().size());
			RenderPlan.Actor actor = plan.getActors().get(0);
			assertEquals("the actor is the character", f.charOid, actor.getCharacterId());
			assertEquals("named after the cast member", castName, actor.getName());
			assertTrue("referenced", actor.isReferenced());
			assertEquals("referenced in BASE", RenderPlan.STAGE_BASE, actor.getReferenceStage());
			assertNotNull("identity", actor.getIdentity());
			assertEquals("the identity is the approved portrait", f.portraitOid, actor.getIdentity().getPortraitArtifactId());
			assertBox("actor (pushed below the band, height clipped to the canvas)", actor.getBox(), 0.3, 0.25, 0.4, 0.75);
			assertEquals("strategy", PbStrategyEnumType.DIRECT_REFERENCE, plan.getStrategy().getName());
			CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
			assertNotNull("compiled BASE", base);
			log("BASE prompt: " + base.getPrompt());
			assertTrue("the BASE prompt carries the band clause: " + base.getPrompt(), lower(base.getPrompt()).contains(TOP_CLAUSE));
			assertTrue("the BASE prompt carries the subject: " + base.getPrompt(), lower(base.getPrompt()).contains("basalt headland"));
			RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
			assertNotNull("BASE pick", pick);
			/// The pin is the bare name; the plan resolves it to the name the server lists (with its extension).
			log("BASE pick: " + pick.getModel() + " (" + pick.getFamily() + ")");
			assertTrue("BASE renders under the pinned FLUX.2 checkpoint, got " + pick.getModel(), bare(pick.getModel()).equalsIgnoreCase(flux2Model));

			int revisionBefore = intOf(f.cover.get(OlioFieldNames.FIELD_PB_REVISION));
			assertTrue("no SCENE composition before", PbComposeUtil.listSceneCompositions(owner, f.book).isEmpty());

			/// The run: STANDARD on .39, production backend, no chatConfig.
			long t0 = System.currentTimeMillis();
			Map<String, Object> out = PbNodeExecutor.executeNodeRun(owner, f.book, f.workflow, f.node, sdu,
				new PlanOptions(null, false, PbQualityPresetEnumType.STANDARD, null), null);
			long wallMs = System.currentTimeMillis() - t0;
			assertNotNull("run DTO", out);
			log("MEASURED: STANDARD cover run of " + f.slug + " on " + server + " model=" + flux2Model + " status=" + out.get("runStatus")
				+ " strategy=" + out.get("strategy") + " planned=" + out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)
				+ " rendered=" + out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED) + " qaMode=" + out.get(PbNodeExecutor.KEY_QA_MODE)
				+ " autoApproved=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVED) + " skipped=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)
				+ " stageErrors=" + out.get(PbNodeExecutor.KEY_STAGE_ERRORS) + " degradations=" + PbNodeExecutor.degradationsOf(out)
				+ " wallMs=" + wallMs + " (informational, never a threshold)");
			assertEquals("run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
			String runOid = str(out.get("runObjectId"));
			assertNotNull("runObjectId", runOid);
			assertEquals("compositionObjectId", f.coverOid, out.get("compositionObjectId"));
			assertEquals("purpose", PbCompositionPurposeEnumType.COVER.name(), status(out.get("purpose")));
			assertEquals("strategy", PbStrategyEnumType.DIRECT_REFERENCE.name(), out.get("strategy"));
			assertNull("no sceneObjectId on a cover DTO", out.get("sceneObjectId"));
			assertEquals("two finals rendered", 2, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("qaMode", QualityPresets.QA_CANONICAL, out.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs = variants(out, 2);
			Map<?, ?> qa0 = (Map<?, ?>) vs.get(0).get(PbNodeExecutor.KEY_VARIANT_QA);
			log("QA of variant 0 (CANONICAL): " + qa0 + "; variant 1: " + vs.get(1).get(PbNodeExecutor.KEY_VARIANT_QA));

			/// The finals on the database: two BASE artifacts of this run, each a real PNG rendered from the portrait
			/// reference under the pinned checkpoint, with a sanitized generator request.
			List<BaseRecord> finals = runFinals(f, runOid);
			assertEquals("two base artifacts of the run", 2, finals.size());
			List<Long> seeds = new ArrayList<>();
			for(int i = 0; i < finals.size(); i++) {
				BaseRecord a = finals.get(i);
				assertEquals("variant index " + i, i, PbArtifactUtil.variantIndexOf(a));
				assertEquals("variant " + i + " is the DTO's", oidOf(a), vs.get(i).get("artifactObjectId"));
				long seed = seedOf(a);
				assertTrue("variant " + i + " seed is a real seed, got " + seed, seed > 0L);
				seeds.add(Long.valueOf(seed));
				byte[] png = artifactPng(f, a, "cover-v" + i);
				int[] dims = ImageDims.decode(png);
				assertTrue("variant " + i + " has dimensions", dims[0] > 0 && dims[1] > 0);
				String req = a.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
				assertNotNull("variant " + i + " generator request", req);
				assertTrue("variant " + i + " generator request is sanitized", PbArtifactUtil.isSanitized(req));
				assertFalse("variant " + i + " generator request carries no inline image", req.contains("data:image"));
				assertFalse("variant " + i + " generator request carries no session id", lower(req).contains("session_id"));
				List<String> refs = PbArtifactUtil.referenceArtifactIdsOf(a);
				log("variant " + i + " references=" + refs);
				assertTrue("variant " + i + " was rendered from the portrait artifact " + f.portraitOid + ": " + refs, refs.contains(f.portraitOid));
				String graph = a.get(OlioFieldNames.FIELD_PB_BACKEND_GRAPH);
				assertTrue("variant " + i + " names the pinned checkpoint in its request or backend graph",
					req.contains(flux2Model) || (graph != null && graph.contains(flux2Model)));
			}
			assertEquals("seeds are distinct: " + seeds, 2, new java.util.HashSet<>(seeds).size());

			/// Auto-approve: a QA verdict on a real image is a model result, so only the invariant is asserted.
			Boolean autoApproved = (Boolean) out.get(PbNodeExecutor.KEY_AUTO_APPROVED);
			String skipped = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			if(Boolean.TRUE.equals(autoApproved)) {
				String approvedOid = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
				assertNotNull("auto-approved artifact", approvedOid);
				BaseRecord approved = null;
				for(BaseRecord a : finals) {
					if(approvedOid.equals(oidOf(a))) approved = a;
				}
				assertNotNull("the approved artifact is one of this run's finals: " + approvedOid, approved);
				assertEquals("it reads APPROVED", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
				assertEquals("approved by auto", PbArtifactUtil.APPROVED_BY_AUTO, approved.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
				assertEquals("headline reviewStatus", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));
				log("auto-approved " + approvedOid + " (variant " + PbArtifactUtil.variantIndexOf(approved) + ")");
			}
			else {
				assertNotNull("not auto-approved: a skip reason is present", skipped);
				for(BaseRecord a : finals) {
					assertTrue("no final reads APPROVED when the run was not auto-approved: " + oidOf(a),
						PbArtifactUtil.reviewStatusOf(a) != PbReviewStatusEnumType.APPROVED);
				}
				log("not auto-approved: " + skipped);
			}

			/// The node and the composition after the run.
			BaseRecord node = PbGraphUtil.readNode(owner, oidOf(f.node), orgId);
			String nodeStatus = status(node.get(OlioFieldNames.FIELD_PB_NODE_STATUS));
			log("node status after the run: " + nodeStatus);
			assertTrue("node status after the run: " + nodeStatus,
				PbNodeStatusEnumType.DONE_UNVERIFIED.name().equals(nodeStatus) || PbNodeStatusEnumType.DONE.name().equals(nodeStatus));
			assertTrue("still no SCENE composition", PbComposeUtil.listSceneCompositions(owner, f.book).isEmpty());
			BaseRecord coverAfter = PbComposeUtil.readComposition(owner, f.coverOid, orgId);
			assertEquals("the cover's revision is untouched", revisionBefore, intOf(coverAfter.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("the cover is still COVER", PbCompositionPurposeEnumType.COVER.name(), status(coverAfter.get(OlioFieldNames.FIELD_PB_PURPOSE)));
			assertEquals("the band is still TOP", "TOP", status(coverAfter.get(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND)));
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Fixture: a fresh book, one book-58 cast member with an approved portrait, a MANUAL cover          */
	/* ------------------------------------------------------------------------------------------------ */

	private static final class Fixture {
		String slug;
		String bookOid;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		BaseRecord cover;
		String coverOid;
		String charOid;
		String portraitOid;
		BaseRecord actorRow;
		BaseRecord style;
	}

	private Fixture fixture(String tag) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p92" + tag + TestP51GraphLive.shortId();
		BaseRecord bk = PbBookUtil.createBook(owner, dataPath(), f.slug, "P92 live cover " + f.slug);
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

			CoverResult cr = PbCoverUtil.getCreate(owner, f.book, PbCompositionPurposeEnumType.COVER, PbCoverUtil.Mode.MANUAL, null);
			assertNotNull("getCreate", cr);
			assertTrue("MANUAL cover written: " + cr.failureReason, cr.isWritten());
			assertTrue("created now", cr.created);
			f.coverOid = oidOf(cr.composition);
			Map<String, Object> fields = map(
				OlioFieldNames.FIELD_PB_SETTING_TEXT, COVER_SETTING,
				OlioFieldNames.FIELD_PB_ACTION, COVER_ACTION,
				OlioFieldNames.FIELD_PB_MOOD, COVER_MOOD,
				OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "top");
			f.cover = PbComposeUtil.readComposition(owner, f.coverOid, orgId);
			assertNotNull("cover", f.cover);
			PbComposeUtil.patchCompositionFields(owner, f.cover, fields);
			f.cover = PbComposeUtil.readComposition(owner, f.coverOid, orgId);
			assertEquals("settingText persisted", COVER_SETTING, f.cover.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("titleSafeBand persisted", "TOP", status(f.cover.get(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND)));

			/// The HERO actor is the character (not a description): the row's box overlaps the band on purpose.
			f.actorRow = PbComposeUtil.createActorRow(owner, f.book, f.cover, map(
				"characterObjectId", f.charOid,
				OlioFieldNames.FIELD_PB_ROLE, "hero",
				OlioFieldNames.FIELD_PB_X, Double.valueOf(0.3), OlioFieldNames.FIELD_PB_Y, Double.valueOf(0.1),
				OlioFieldNames.FIELD_PB_W, Double.valueOf(0.4), OlioFieldNames.FIELD_PB_H, Double.valueOf(0.8)));
			assertNotNull("actor row", f.actorRow);
			BaseRecord rowChar = f.actorRow.get(OlioFieldNames.FIELD_PB_CHARACTER);
			assertNotNull("the row carries the character", rowChar);

			f.workflow = PbGraphUtil.getCreateWorkflow(owner, f.book, PbBookUtil.workflowGroupPath(f.slug));
			assertNotNull("workflow", f.workflow);
			Map<String, Object> ensured = new LinkedHashMap<>();
			f.node = PbServiceFacade.ensureCompositionPlanNode(owner, f.book, f.workflow, f.cover, ensured);
			assertNotNull("composition-plan node", f.node);
			assertEquals("node handle", PbPipelineUtil.compositionPlanHandle(f.coverOid), f.node.get(OlioFieldNames.FIELD_PB_HANDLE));
			assertEquals("node scope", PbPipelineUtil.SCOPE_COMPOSITION, f.node.get(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals("node type", PbNodeTypeEnumType.RENDER_PLAN.name(), status(f.node.get(OlioFieldNames.FIELD_PB_NODE_TYPE)));

			f.style = pinBookStyle(owner, f.book, tag, flux2Model);
			assertTrue("setAutoApprove", PbBookUtil.setAutoApprove(owner, f.book, true));
			CacheUtil.clearCache();
			f.book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			assertEquals("autoApprove on", Boolean.TRUE, f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
			log(tag + " fixture book " + f.slug + " cover " + f.coverOid + " node " + oidOf(f.node) + " actor " + castName + "=" + f.charOid
				+ " portrait=" + f.portraitOid + " (" + castDims[0] + "x" + castDims[1] + ")");
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
				logger.error("[p92] cleanup " + f.slug + " failed: " + e.getMessage(), e);
			}
		}
		if(f.style != null) {
			try {
				ioContext.getAccessPoint().delete(owner, f.style);
			}
			catch(Exception e) {
				logger.error("[p92] style cleanup failed: " + e.getMessage());
			}
		}
	}

	private BaseRecord approvedPortraitFor(BaseRecord u, BaseRecord book, String slug, String charOid, byte[] bytes, int w, int h) throws Exception {
		long oid = orgOf(u);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, book, PbBookUtil.workflowGroupPath(slug));
		BaseRecord pNode = PbGraphUtil.addNode(u, wf, PbPipelineUtil.portraitHandle(charOid), PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("portrait node", pNode);
		String groupPath = PbBookUtil.artifactGroupPath(slug);
		BaseRecord data = persistPng(u, groupPath, "p92-portrait-" + TestP51GraphLive.shortId() + ".png", bytes);
		BaseRecord art = PbArtifactUtil.persistArtifact(u, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE, groupPath, data, null, null, null);
		assertNotNull("portrait artifact", art);
		assertTrue("portrait metrics", PbArtifactUtil.recordImageMetrics(u, art, bytes, "image/png", w, h, null));
		assertNotNull("approve", PbArtifactUtil.approve(u, PbArtifactUtil.readArtifact(u, oidOf(art), oid), "p92"));
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
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p92-" + tag + "-" + TestP51GraphLive.shortId(), STYLE_PATH, oid);
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
		BaseRecord book58 = PbBookUtil.findBookBySlug(owner, TestP51GraphLive.CUSTOM_BOOK_SLUG, orgId);
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
	/* Run inspection                                                                                   */
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
	private static List<Map<String, Object>> variants(Map<String, Object> out, int expected) {
		Object vs = out.get(PbNodeExecutor.KEY_VARIANTS);
		assertTrue("variants is a list: " + vs, vs instanceof List);
		List<Map<String, Object>> list = (List<Map<String, Object>>) vs;
		assertEquals("variants", expected, list.size());
		return list;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Small helpers                                                                                     */
	/* ------------------------------------------------------------------------------------------------ */

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

	/// Server-listed checkpoint names carry the extension; the pin does not.
	private static String bare(String model) {
		if(model == null) return "";
		return (model.endsWith(".safetensors") ? model.substring(0, model.length() - ".safetensors".length()) : model);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private static void assertBox(String what, RenderPlan.Box box, double x, double y, double w, double h) {
		assertNotNull(what + " box", box);
		assertEquals(what + " x", x, box.getX(), EPS);
		assertEquals(what + " y", y, box.getY(), EPS);
		assertEquals(what + " w", w, box.getW(), EPS);
		assertEquals(what + " h", h, box.getH(), EPS);
	}

	private static String boxText(RenderPlan.Box b) {
		return (b == null ? "null" : "(" + b.getX() + "," + b.getY() + "," + b.getW() + "," + b.getH() + ")");
	}

	private static String degradationText(RenderPlan plan) {
		List<String> out = new ArrayList<>();
		for(RenderPlan.Degradation d : plan.getDegradations()) {
			out.add(d.getCode() + ": " + d.getMessage());
		}
		return out.toString();
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
		logger.info("[p92] " + msg);
	}
}
