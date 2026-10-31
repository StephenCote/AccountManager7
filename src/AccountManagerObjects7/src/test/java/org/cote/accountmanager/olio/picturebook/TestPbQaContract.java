package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 8, P8-1: the visual-QA contract (design §17; ledger §6.C 220 a-d) with no provider -
 * {@link QaReport} as a value, {@link PbQaUtil} as storage, the {@code QA_FAIL} / {@code QA_UNAVAILABLE} codes,
 * {@link PbArtifactUtil#flagForReview} and QA-ordered
 * {@link PbArtifactUtil#autoApprove(BaseRecord, List, Map, List, String)}.
 * <p>
 * Live {@code am7db}, no SD server, no LLM: the images are solid Java2D PNGs persisted as variants of one attempt
 * on fresh PORTRAIT nodes of the shared {@code pb2c-graph} fixture book (one node per scenario, so nothing here
 * depends on another test's chain). The verdicts are built by hand; what a provider would observe is P8-2 / P8-3.
 */
public class TestPbQaContract extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";

	@Before
	public void qaSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	/** The fixture book is shared with the other Pb tests, which expect the switch off. */
	@After
	public void resetAutoApprove() {
		try {
			BaseRecord u = user();
			BaseRecord bk = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
			if(bk != null) {
				PbBookUtil.setAutoApprove(u, bk, false);
			}
		}
		catch(Exception e) {
			logger.error("resetAutoApprove: " + e.getMessage());
		}
	}

	// ─────────────────────────────── the value object ───────────────────────────────

	/** 220 b: a report with every field and check kind round-trips through its JSON form, result included. */
	@Test
	public void case01_reportRoundTripsThroughJson() {
		QaReport r = QaReport.builder("test-provider").model("test-model").durationMs(1234L).note("hand-built")
			.personCount(2, Integer.valueOf(2), "two figures")
			.actorPresent("a1", "Mara", Boolean.TRUE, "red cloak, left")
			.actorPresent("a2", "Jideon", Boolean.FALSE, "no second figure matches")
			.soft(QaReport.KIND_IDENTITY, "a1", "Mara, dark hair", "dark hair, red cloak", Boolean.TRUE, null)
			.soft(QaReport.KIND_OUTFIT, "a1", "red cloak", "brown coat", Boolean.FALSE, "cloak colour")
			.soft(QaReport.KIND_LOCATION, null, "tavern interior", "tavern interior", Boolean.TRUE, null)
			.soft(QaReport.KIND_COMPOSITION, null, "two-shot", null, null, "not judged")
			.build();
		assertEquals("an absent referenced actor is FAIL and the rollup follows", QaReport.Result.FAIL, r.getResult());
		assertEquals(7, r.getChecks().size());
		assertEquals(2, r.checksOf(QaReport.KIND_ACTOR_PRESENT).size());
		assertNull("not yet bound to an image", r.getImageArtifactObjectId());

		String json = r.toJson();
		logger.info("P8-1 case01 json=" + json);
		QaReport back = QaReport.fromJson(json);
		assertNotNull(back);
		assertEquals(r.getResult(), back.getResult());
		assertEquals("test-provider", back.getProvider());
		assertEquals("test-model", back.getModel());
		assertEquals(1234L, back.getDurationMs());
		assertEquals("hand-built", back.getNote());
		assertEquals(r.getChecks().size(), back.getChecks().size());
		for(int i = 0; i < r.getChecks().size(); i++) {
			QaReport.Check a = r.getChecks().get(i);
			QaReport.Check b = back.getChecks().get(i);
			assertEquals(a.getKind(), b.getKind());
			assertEquals(a.getResult(), b.getResult());
			assertEquals(a.getExpected(), b.getExpected());
			assertEquals(a.getObserved(), b.getObserved());
			assertEquals(a.getDetail(), b.getDetail());
			assertEquals(a.getActorId(), b.getActorId());
		}
		QaReport bound = r.forImage("img-1");
		assertEquals("img-1", bound.getImageArtifactObjectId());
		assertEquals("img-1", QaReport.fromJson(bound.toJson()).getImageArtifactObjectId());
		assertEquals(r.getResult(), bound.getResult());

		QaReport un = QaReport.unavailable("down-provider", null, "vision model unreachable", 50L);
		assertEquals(QaReport.Result.UNAVAILABLE, un.getResult());
		assertTrue(un.getChecks().isEmpty());
		QaReport unBack = QaReport.fromJson(un.toJson());
		assertEquals("an explicit UNAVAILABLE survives the round trip", QaReport.Result.UNAVAILABLE, unBack.getResult());
		assertEquals("vision model unreachable", unBack.getNote());
		assertNull(QaReport.fromJson(null));
		assertNull(QaReport.fromJson("  "));
	}

	/** 220 b rollup: FAIL over WARN over PASS; no checks or only UNAVAILABLE checks is UNAVAILABLE, never PASS. */
	@Test
	public void case02_rollupFollowsTheSeverityOrder() {
		assertEquals(QaReport.Result.UNAVAILABLE, QaReport.rollup(null));
		assertEquals(QaReport.Result.UNAVAILABLE, QaReport.rollup(List.of()));
		QaReport.Check pass = new QaReport.Check(QaReport.KIND_LOCATION, QaReport.Result.PASS, "a", "a", null);
		QaReport.Check warn = new QaReport.Check(QaReport.KIND_OUTFIT, QaReport.Result.WARN, "a", "b", null);
		QaReport.Check failed = new QaReport.Check(QaReport.KIND_ACTOR_PRESENT, QaReport.Result.FAIL, "Mara", "absent", null, "a1");
		QaReport.Check unavailable = new QaReport.Check(QaReport.KIND_COMPOSITION, null, null, null, null);
		assertEquals("a null check result reads UNAVAILABLE", QaReport.Result.UNAVAILABLE, unavailable.getResult());

		assertEquals(QaReport.Result.PASS, QaReport.rollup(List.of(pass)));
		assertEquals(QaReport.Result.PASS, QaReport.rollup(List.of(pass, unavailable)));
		assertEquals(QaReport.Result.WARN, QaReport.rollup(List.of(pass, warn)));
		assertEquals(QaReport.Result.WARN, QaReport.rollup(List.of(warn, unavailable)));
		assertEquals(QaReport.Result.FAIL, QaReport.rollup(List.of(pass, warn, failed)));
		assertEquals(QaReport.Result.FAIL, QaReport.rollup(List.of(unavailable, failed)));
		assertEquals("only UNAVAILABLE checks is UNAVAILABLE, not PASS", QaReport.Result.UNAVAILABLE, QaReport.rollup(List.of(unavailable, unavailable)));
		List<QaReport.Check> withNull = new ArrayList<>();
		withNull.add(null);
		withNull.add(pass);
		assertEquals("null entries are skipped", QaReport.Result.PASS, QaReport.rollup(withNull));

		assertEquals(QaReport.Result.PASS, QaReport.parseResult("pass"));
		assertEquals(QaReport.Result.FAIL, QaReport.parseResult(" FAIL "));
		assertEquals(QaReport.Result.UNAVAILABLE, QaReport.parseResult("nonsense"));
		assertEquals(QaReport.Result.UNAVAILABLE, QaReport.parseResult(null));
	}

	/** 220 b thresholds, applied by the builder so every provider agrees: count shortfall FAIL, surplus WARN, soft mismatches WARN. */
	@Test
	public void case03_builderHelpersApplyTheThresholds() {
		assertEquals(QaReport.Result.FAIL, QaReport.builder("p").personCount(2, Integer.valueOf(1), null).build().getResult());
		assertEquals(QaReport.Result.WARN, QaReport.builder("p").personCount(2, Integer.valueOf(3), null).build().getResult());
		assertEquals(QaReport.Result.PASS, QaReport.builder("p").personCount(2, Integer.valueOf(2), null).build().getResult());
		assertEquals("no observation is UNAVAILABLE", QaReport.Result.UNAVAILABLE, QaReport.builder("p").personCount(2, null, null).build().getResult());
		QaReport.Check pc = QaReport.builder("p").personCount(2, Integer.valueOf(3), "extra").build().getChecks().get(0);
		assertEquals("2", pc.getExpected());
		assertEquals("3", pc.getObserved());
		assertEquals("extra", pc.getDetail());

		assertEquals(QaReport.Result.PASS, QaReport.builder("p").actorPresent("a1", "Mara", Boolean.TRUE, null).build().getResult());
		assertEquals(QaReport.Result.FAIL, QaReport.builder("p").actorPresent("a1", "Mara", Boolean.FALSE, null).build().getResult());
		assertEquals(QaReport.Result.UNAVAILABLE, QaReport.builder("p").actorPresent("a1", "Mara", null, null).build().getResult());
		QaReport.Check ap = QaReport.builder("p").actorPresent("a1", "Mara", Boolean.FALSE, null).build().getChecks().get(0);
		assertEquals("a1", ap.getActorId());
		assertEquals("Mara", ap.getExpected());
		assertEquals("absent", ap.getObserved());

		for(String kind : new String[] { QaReport.KIND_IDENTITY, QaReport.KIND_OUTFIT, QaReport.KIND_LOCATION, QaReport.KIND_COMPOSITION }) {
			assertEquals(kind + " match", QaReport.Result.PASS, QaReport.builder("p").soft(kind, null, "a", "a", Boolean.TRUE, null).build().getResult());
			assertEquals(kind + " mismatch is WARN, never FAIL", QaReport.Result.WARN, QaReport.builder("p").soft(kind, null, "a", "b", Boolean.FALSE, null).build().getResult());
			assertEquals(kind + " unobserved", QaReport.Result.UNAVAILABLE, QaReport.builder("p").soft(kind, null, "a", null, null, null).build().getResult());
		}
		for(String hard : new String[] { QaReport.KIND_PERSON_COUNT, QaReport.KIND_ACTOR_PRESENT, null }) {
			try {
				QaReport.builder("p").soft(hard, null, "a", "b", Boolean.FALSE, null);
				fail("soft() must refuse " + hard);
			}
			catch(IllegalArgumentException expected) {
				/// the hard kinds have their own helpers with their own thresholds
			}
		}
		assertEquals(6, QaReport.KINDS.size());
	}

	// ─────────────────────────────── the codes ───────────────────────────────

	/** 220 c: QA_FAIL and QA_UNAVAILABLE are known codes that never veto; the veto set is still the six P4-7 codes. */
	@Test
	public void case04_qaCodesAreKnownAndNeverVeto() {
		assertTrue(RenderPlan.Degradation.isKnownCode(RenderPlan.Degradation.QA_FAIL));
		assertTrue(RenderPlan.Degradation.isKnownCode(RenderPlan.Degradation.QA_UNAVAILABLE));
		assertTrue(RenderPlan.Degradation.isKnownCode(RenderPlan.Degradation.QA_WARN));
		assertFalse(PbArtifactUtil.AUTO_APPROVE_VETO_CODES.contains(RenderPlan.Degradation.QA_FAIL));
		assertFalse(PbArtifactUtil.AUTO_APPROVE_VETO_CODES.contains(RenderPlan.Degradation.QA_UNAVAILABLE));
		assertFalse(PbArtifactUtil.AUTO_APPROVE_VETO_CODES.contains(RenderPlan.Degradation.QA_WARN));
		assertEquals(6, PbArtifactUtil.AUTO_APPROVE_VETO_CODES.size());
		assertTrue(PbArtifactUtil.vetoingDegradations(List.of(
			RenderPlan.Degradation.QA_FAIL + ": variant 0 (x) failed visual QA",
			RenderPlan.Degradation.QA_UNAVAILABLE + ": vision model unreachable",
			RenderPlan.Degradation.QA_WARN + ": outfit mismatch")).isEmpty());
		assertEquals("qa degradations print like every other code",
			RenderPlan.Degradation.QA_FAIL + ": Mara absent", new RenderPlan.Degradation(RenderPlan.Degradation.QA_FAIL, "Mara absent", null, null).toString());
	}

	// ─────────────────────────────── storage (220 a) ───────────────────────────────

	/**
	 * 220 a: a report persists as a {@code qa} JSON artifact on the image's node carrying the image's attempt,
	 * variantIndex and objectId; it reads back by image; the {@code qa} chain is a log (a second report for the
	 * same image is the newest, no supersedes); and the review queue neither lists nor counts it.
	 */
	@Test
	public void case05_reportPersistsOnTheImageNodeAndStaysOutOfReview() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = node(u, wf, "p81-c05", PbNodeTypeEnumType.PORTRAIT);
		String attempt = UUID.randomUUID().toString();
		List<BaseRecord> variants = variants(u, node, "c05", attempt, 2);
		BaseRecord v0 = variants.get(0);
		BaseRecord v1 = variants.get(1);
		String v0Oid = v0.get(FieldNames.FIELD_OBJECT_ID);
		String v1Oid = v1.get(FieldNames.FIELD_OBJECT_ID);

		QaReport r1 = QaReport.builder("test-provider").model("m").personCount(1, Integer.valueOf(1), null)
			.actorPresent("a1", "Mara", Boolean.TRUE, null).build();
		BaseRecord qa1 = PbQaUtil.persistReport(u, PbBookUtil.artifactGroupPath(SLUG), r1, v1);
		assertNotNull(qa1);
		BaseRecord qa1Fresh = PbArtifactUtil.readArtifact(u, qa1.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull(qa1Fresh);
		assertEquals(PbPipelineUtil.ROLE_QA, qa1Fresh.get(OlioFieldNames.FIELD_PB_ROLE));
		assertEquals("JSON", enumName(qa1Fresh.get(OlioFieldNames.FIELD_PB_ARTIFACT_TYPE)));
		/// Two generic get()s in one assertEquals bind the Object[] overload - type the locals first.
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord qaNode = qa1Fresh.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		assertNotNull("the report names its node", qaNode);
		String qaNodeOid = qaNode.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("on the image's node", nodeOid, qaNodeOid);
		assertEquals("stamped with the image's attempt", attempt, PbArtifactUtil.attemptOf(qa1Fresh));
		assertEquals("stamped with the image's variantIndex", 1, PbArtifactUtil.variantIndexOf(qa1Fresh));
		assertNull("a report never supersedes anything", qa1Fresh.get(OlioFieldNames.FIELD_PB_SUPERSEDES));
		QaReport read1 = PbQaUtil.readReport(qa1Fresh);
		assertNotNull(read1);
		assertEquals("bound to the image it judged", v1Oid, read1.getImageArtifactObjectId());
		assertEquals(QaReport.Result.PASS, read1.getResult());
		assertEquals(2, read1.getChecks().size());

		assertNull("variant 0 was never judged", PbQaUtil.reportFor(u, node, v0Oid));
		assertNull(PbQaUtil.reportFor(u, node, null));
		QaReport byImage = PbQaUtil.reportFor(u, node, v1Oid);
		assertNotNull(byImage);
		assertEquals(QaReport.Result.PASS, byImage.getResult());

		QaReport r2 = QaReport.builder("test-provider").model("m").actorPresent("a1", "Mara", Boolean.FALSE, "re-judged").build();
		BaseRecord qa2 = PbQaUtil.persistReport(u, PbBookUtil.artifactGroupPath(SLUG), r2, v1);
		assertNotNull(qa2);
		assertEquals("the newest report for the image wins", QaReport.Result.FAIL, PbQaUtil.reportFor(u, node, v1Oid).getResult());
		assertEquals(2, PbQaUtil.reportsOf(u, node).size());
		Map<String, QaReport> latest = PbQaUtil.latestReportsOf(u, node);
		assertEquals(1, latest.size());
		assertEquals(QaReport.Result.FAIL, latest.get(v1Oid).getResult());
		assertEquals("the image chain is untouched by the reports", 2, PbArtifactUtil.listChain(u, node, PbPipelineUtil.ROLE_PORTRAIT).size());

		assertNull("an image artifact is not a report", PbQaUtil.readReport(PbArtifactUtil.readArtifact(u, v1Oid, orgId)));
		assertNull(PbQaUtil.readReport(null));

		Map<String, Object> queue = PbServiceFacade.listReviewQueue(u, bk.get(FieldNames.FIELD_OBJECT_ID), PbServiceFacade.REVIEW_FILTER_ALL);
		Map<String, Object> ne = nodeEntry(queue, node);
		assertNotNull("the node is in the queue for its images", ne);
		List<Map<String, Object>> arts = artifactsOf(ne);
		assertEquals("exactly the two images, no qa report: " + arts, 2, arts.size());
		for(Map<String, Object> a : arts) {
			assertEquals(PbPipelineUtil.ROLE_PORTRAIT, a.get("role"));
		}

		try {
			PbQaUtil.persistReport(u, PbBookUtil.artifactGroupPath(SLUG), null, v1);
			fail("a null report is refused");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
		}
		try {
			PbQaUtil.persistReport(u, PbBookUtil.artifactGroupPath(SLUG), r1, null);
			fail("a null image is refused");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
		}
	}

	/**
	 * P8-5 (§17 Ux): {@code nodeView} and the review queue carry each judged image's NEWEST report as {@code qa}
	 * (the report's full map - result, provider, model, checks, note, imageArtifactObjectId), nothing on an
	 * unjudged image, and nothing on the {@code qa} chain's own entries.
	 */
	@Test
	public void case09_nodeViewAndReviewQueueCarryTheNewestReportPerImage() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord node = node(u, wf, "p85-c09", PbNodeTypeEnumType.PORTRAIT);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		List<BaseRecord> variants = variants(u, node, "c09", UUID.randomUUID().toString(), 3);
		String v0Oid = variants.get(0).get(FieldNames.FIELD_OBJECT_ID);
		String v1Oid = variants.get(1).get(FieldNames.FIELD_OBJECT_ID);
		String v2Oid = variants.get(2).get(FieldNames.FIELD_OBJECT_ID);
		String groupPath = PbBookUtil.artifactGroupPath(SLUG);

		assertNotNull(PbQaUtil.persistReport(u, groupPath, QaReport.builder("test-provider").model("m")
			.personCount(2, Integer.valueOf(1), "one figure").actorPresent("a2", "Tobin", Boolean.FALSE, "no second figure").build(), variants.get(0)));
		/// v1 is judged twice: the first verdict must not be the one the views carry.
		assertNotNull(PbQaUtil.persistReport(u, groupPath, QaReport.builder("test-provider").model("m")
			.personCount(2, Integer.valueOf(3), "stale").build(), variants.get(1)));
		assertNotNull(PbQaUtil.persistReport(u, groupPath, QaReport.builder("test-provider").model("m").note("re-judged")
			.personCount(2, Integer.valueOf(2), "two figures").actorPresent("a2", "Tobin", Boolean.TRUE, "right").build(), variants.get(1)));

		Map<String, Object> view = PbServiceFacade.nodeView(u, bookOid, nodeOid);
		@SuppressWarnings("unchecked")
		Map<String, List<Map<String, Object>>> chains = (Map<String, List<Map<String, Object>>>) view.get("artifacts");
		assertNotNull("nodeView lists the artifact chains", chains);
		List<Map<String, Object>> images = chains.get(PbPipelineUtil.ROLE_PORTRAIT);
		assertNotNull(images);
		assertEquals(3, images.size());
		Map<String, Map<String, Object>> byOid = new LinkedHashMap<>();
		for(Map<String, Object> a : images) {
			byOid.put((String) a.get("objectId"), a);
		}
		assertQa(byOid.get(v0Oid), QaReport.Result.FAIL, 2, null, v0Oid);
		assertQa(byOid.get(v1Oid), QaReport.Result.PASS, 2, "re-judged", v1Oid);
		assertFalse("an unjudged image carries no qa key: " + byOid.get(v2Oid), byOid.get(v2Oid).containsKey(PbNodeExecutor.KEY_VARIANT_QA));
		List<Map<String, Object>> reports = chains.get(PbPipelineUtil.ROLE_QA);
		assertNotNull("the qa chain itself is still listed", reports);
		assertEquals(3, reports.size());
		for(Map<String, Object> r : reports) {
			assertFalse("a report carries no qa of its own: " + r, r.containsKey(PbNodeExecutor.KEY_VARIANT_QA));
		}
		logger.info("P8-5 case09 nodeView v0.qa=" + byOid.get(v0Oid).get(PbNodeExecutor.KEY_VARIANT_QA) + " v1.qa=" + byOid.get(v1Oid).get(PbNodeExecutor.KEY_VARIANT_QA));

		Map<String, Object> queue = PbServiceFacade.listReviewQueue(u, bookOid, PbServiceFacade.REVIEW_FILTER_ALL);
		Map<String, Object> ne = nodeEntry(queue, node);
		assertNotNull(ne);
		List<Map<String, Object>> arts = artifactsOf(ne);
		assertEquals("the three images, no report: " + arts, 3, arts.size());
		Map<String, Map<String, Object>> qByOid = new LinkedHashMap<>();
		for(Map<String, Object> a : arts) {
			qByOid.put((String) a.get("objectId"), a);
		}
		assertQa(qByOid.get(v0Oid), QaReport.Result.FAIL, 2, null, v0Oid);
		assertQa(qByOid.get(v1Oid), QaReport.Result.PASS, 2, "re-judged", v1Oid);
		assertFalse("unjudged in the queue too", qByOid.get(v2Oid).containsKey(PbNodeExecutor.KEY_VARIANT_QA));
	}

	@SuppressWarnings("unchecked")
	private static void assertQa(Map<String, Object> summary, QaReport.Result result, int checks, String note, String imageOid) {
		assertNotNull("summary for " + imageOid, summary);
		Object qa = summary.get(PbNodeExecutor.KEY_VARIANT_QA);
		assertNotNull("qa on " + imageOid + ": " + summary, qa);
		Map<String, Object> m = (Map<String, Object>) qa;
		assertEquals(result.name(), m.get("result"));
		assertEquals("test-provider", m.get("provider"));
		assertEquals("m", m.get("model"));
		assertEquals(imageOid, m.get("imageArtifactObjectId"));
		assertEquals(note, m.get("note"));
		List<Map<String, Object>> cl = (List<Map<String, Object>>) m.get("checks");
		assertNotNull(cl);
		assertEquals("checks on " + imageOid + ": " + cl, checks, cl.size());
		QaReport back = QaReport.fromMap(m);
		assertEquals("the map is the report's own serialized form", result, back.getResult());
	}

	// ─────────────────────────────── 220 d: the review flag ───────────────────────────────

	/** 220 d: a GENERATED image goes IN_REVIEW; the flag is idempotent and never demotes an approval or reverses a rejection. */
	@Test
	public void case06_flagForReviewOnlyPromotesGenerated() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = node(u, wf, "p81-c06", PbNodeTypeEnumType.PORTRAIT);
		String attempt = UUID.randomUUID().toString();
		List<BaseRecord> variants = variants(u, node, "c06", attempt, 2);
		BaseRecord v0 = variants.get(0);
		BaseRecord v1 = variants.get(1);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(v1));

		BaseRecord flagged = PbArtifactUtil.flagForReview(u, v1);
		assertEquals(PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(flagged));
		assertEquals(PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(reread(u, v1, orgId)));
		assertFalse("the flag does not select", selected(reread(u, v1, orgId)));
		assertTrue("variant 0 keeps the rule-1 selection", selected(reread(u, v0, orgId)));
		assertFalse("the flag does not pin", pinned(u, node, orgId));
		assertNull(reread(u, v1, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_BY));

		BaseRecord again = PbArtifactUtil.flagForReview(u, flagged);
		assertEquals("idempotent", PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(again));

		BaseRecord approved = PbArtifactUtil.approve(u, v0, u.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
		BaseRecord still = PbArtifactUtil.flagForReview(u, approved);
		assertEquals("never demotes an approval", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(still));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(reread(u, v0, orgId)));

		try {
			PbArtifactUtil.flagForReview(u, null);
			fail("null is refused");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
		}

		Map<String, Object> queue = PbServiceFacade.listReviewQueue(u, bk.get(FieldNames.FIELD_OBJECT_ID), PbServiceFacade.REVIEW_FILTER_ALL);
		Map<String, Object> ne = nodeEntry(queue, node);
		assertNotNull(ne);
		String v1Oid = v1.get(FieldNames.FIELD_OBJECT_ID);
		boolean seen = false;
		for(Map<String, Object> a : artifactsOf(ne)) {
			if(v1Oid.equals(a.get("objectId"))) {
				seen = true;
				assertEquals("the queue shows the flag", PbReviewStatusEnumType.IN_REVIEW.name(), a.get("reviewStatus"));
			}
		}
		assertTrue("the flagged variant is in the queue", seen);
	}

	// ─────────────────────────────── 220 c: QA-ordered auto-approve ───────────────────────────────

	/**
	 * 220 c over real chains, one fresh PORTRAIT node per scenario, the book's switch on:
	 * <ul>
	 * <li>ALL [FAIL, WARN, PASS] approves variant 2 (PASS before WARN; FAIL out), which takes the selection off
	 * variant 0;</li>
	 * <li>ALL [WARN, PASS, PASS] approves variant 1 (the first PASS; ties keep variant order);</li>
	 * <li>ALL [FAIL, FAIL] is skipped with {@code degraded: QA_FAIL: all 2 variant(s) ...} and both stay GENERATED;</li>
	 * <li>ALL [FAIL, unchecked] approves variant 1 (unchecked outranks FAIL);</li>
	 * <li>CANONICAL [FAIL, PASS] is skipped naming variant 0 - only variant 0 is judged;</li>
	 * <li>CANONICAL [PASS, FAIL] approves variant 0;</li>
	 * <li>CANONICAL over a list whose head is variant 1 (variant 0 dropped by a failed re-read) is skipped with
	 * {@code failed: variant 0 is missing; ...} - the unjudged sibling is never approved; OFF over the same list
	 * approves the head;</li>
	 * <li>OFF [FAIL, PASS] approves variant 0 - verdicts are ignored, the pre-Phase-8 behaviour;</li>
	 * <li>null mode reads OFF; an empty list is {@code failed: no artifact}; a vetoing degradation still refuses
	 * the QA-chosen candidate.</li>
	 * </ul>
	 */
	@Test
	public void case07_autoApproveOrdersVariantsByVerdict() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		List<String> clean = List.of(RenderPlan.Degradation.ACTOR_TEXT_ONLY + ": Mara rendered from description");

		// ALL [FAIL, WARN, PASS] -> variant 2
		BaseRecord n1 = node(u, wf, "p81-c07a", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs1 = variants(u, n1, "c07a", UUID.randomUUID().toString(), 3);
		PbArtifactUtil.AutoApproveOutcome o1 = PbArtifactUtil.autoApprove(u, vs1,
			verdicts(vs1, QaReport.Result.FAIL, QaReport.Result.WARN, QaReport.Result.PASS), clean, QualityPresets.QA_ALL);
		assertTrue("ALL [FAIL, WARN, PASS] approves: " + o1.getSkipped(), o1.isApproved());
		assertNull(o1.getSkipped());
		assertEquals(2, PbArtifactUtil.variantIndexOf(o1.getApproved()));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(reread(u, vs1.get(2), orgId)));
		assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, reread(u, vs1.get(2), orgId).get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue("the approved variant is selected", selected(reread(u, vs1.get(2), orgId)));
		assertFalse("variant 0 lost the rule-1 selection", selected(reread(u, vs1.get(0), orgId)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs1.get(0), orgId)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs1.get(1), orgId)));
		assertTrue("auto-approve pins the node", pinned(u, n1, orgId));
		assertEquals(1, approvedCount(u, n1));

		// ALL [WARN, PASS, PASS] -> variant 1
		BaseRecord n2 = node(u, wf, "p81-c07b", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs2 = variants(u, n2, "c07b", UUID.randomUUID().toString(), 3);
		PbArtifactUtil.AutoApproveOutcome o2 = PbArtifactUtil.autoApprove(u, vs2,
			verdicts(vs2, QaReport.Result.WARN, QaReport.Result.PASS, QaReport.Result.PASS), clean, QualityPresets.QA_ALL);
		assertTrue(o2.isApproved());
		assertEquals("the first PASS wins a tie", 1, PbArtifactUtil.variantIndexOf(o2.getApproved()));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs2.get(2), orgId)));

		// ALL [FAIL, FAIL] -> skipped, nothing written
		BaseRecord n3 = node(u, wf, "p81-c07c", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs3 = variants(u, n3, "c07c", UUID.randomUUID().toString(), 2);
		PbArtifactUtil.AutoApproveOutcome o3 = PbArtifactUtil.autoApprove(u, vs3,
			verdicts(vs3, QaReport.Result.FAIL, QaReport.Result.FAIL), clean, QualityPresets.QA_ALL);
		assertFalse(o3.isApproved());
		assertNull(o3.getApproved());
		assertEquals("degraded: " + RenderPlan.Degradation.QA_FAIL + ": all 2 variant(s) failed visual QA", o3.getSkipped());
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs3.get(0), orgId)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs3.get(1), orgId)));
		assertTrue("variant 0 keeps the rule-1 selection when nothing is approved", selected(reread(u, vs3.get(0), orgId)));
		assertFalse("a skipped attempt does not pin", pinned(u, n3, orgId));
		assertEquals(0, approvedCount(u, n3));

		// ALL [FAIL, unchecked] -> variant 1
		BaseRecord n4 = node(u, wf, "p81-c07d", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs4 = variants(u, n4, "c07d", UUID.randomUUID().toString(), 2);
		PbArtifactUtil.AutoApproveOutcome o4 = PbArtifactUtil.autoApprove(u, vs4,
			verdicts(vs4, QaReport.Result.FAIL, null), clean, QualityPresets.QA_ALL);
		assertTrue("an unchecked variant outranks a FAIL: " + o4.getSkipped(), o4.isApproved());
		assertEquals(1, PbArtifactUtil.variantIndexOf(o4.getApproved()));

		// CANONICAL [FAIL, PASS] -> skipped naming variant 0
		BaseRecord n5 = node(u, wf, "p81-c07e", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs5 = variants(u, n5, "c07e", UUID.randomUUID().toString(), 2);
		PbArtifactUtil.AutoApproveOutcome o5 = PbArtifactUtil.autoApprove(u, vs5,
			verdicts(vs5, QaReport.Result.FAIL, QaReport.Result.PASS), clean, QualityPresets.QA_CANONICAL);
		assertFalse("CANONICAL judges variant 0 only", o5.isApproved());
		assertEquals("degraded: " + RenderPlan.Degradation.QA_FAIL + ": variant 0 (" + vs5.get(0).get(FieldNames.FIELD_NAME) + ") failed visual QA", o5.getSkipped());
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs5.get(1), orgId)));
		assertEquals(0, approvedCount(u, n5));

		// CANONICAL [PASS, FAIL] -> variant 0
		BaseRecord n6 = node(u, wf, "p81-c07f", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs6 = variants(u, n6, "c07f", UUID.randomUUID().toString(), 2);
		PbArtifactUtil.AutoApproveOutcome o6 = PbArtifactUtil.autoApprove(u, vs6,
			verdicts(vs6, QaReport.Result.PASS, QaReport.Result.FAIL), clean, QualityPresets.QA_CANONICAL);
		assertTrue(o6.isApproved());
		assertEquals(0, PbArtifactUtil.variantIndexOf(o6.getApproved()));

		// CANONICAL with variant 0 missing from the list (the executor drops a variant it could not re-read, so the
		// head is variant 1, unjudged) -> skipped, never an unjudged sibling; the same list under OFF approves the
		// head as before (OFF ignores verdicts and indices)
		BaseRecord n6b = node(u, wf, "p81-c07f2", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs6b = variants(u, n6b, "c07f2", UUID.randomUUID().toString(), 2);
		List<BaseRecord> tail6b = List.of(vs6b.get(1));
		assertEquals("the fixture's head is variant 1", 1, PbArtifactUtil.variantIndexOf(tail6b.get(0)));
		PbArtifactUtil.AutoApproveOutcome o6b = PbArtifactUtil.autoApprove(u, tail6b,
			verdicts(vs6b, QaReport.Result.FAIL, null), clean, QualityPresets.QA_CANONICAL);
		assertFalse("CANONICAL never approves an unjudged sibling of a missing variant 0", o6b.isApproved());
		assertNull(o6b.getApproved());
		assertEquals("failed: variant 0 is missing; variant 1 (" + vs6b.get(1).get(FieldNames.FIELD_NAME)
			+ ") is unjudged under " + QualityPresets.QA_CANONICAL, o6b.getSkipped());
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs6b.get(1), orgId)));
		assertEquals(0, approvedCount(u, n6b));
		PbArtifactUtil.AutoApproveOutcome o6c = PbArtifactUtil.autoApprove(u, tail6b, Map.of(), clean, QualityPresets.QA_OFF);
		assertTrue("OFF approves the head regardless of its index: " + o6c.getSkipped(), o6c.isApproved());
		assertEquals(1, PbArtifactUtil.variantIndexOf(o6c.getApproved()));

		// OFF [FAIL, PASS] -> variant 0 (verdicts ignored); null mode is OFF
		BaseRecord n7 = node(u, wf, "p81-c07g", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs7 = variants(u, n7, "c07g", UUID.randomUUID().toString(), 2);
		PbArtifactUtil.AutoApproveOutcome o7 = PbArtifactUtil.autoApprove(u, vs7,
			verdicts(vs7, QaReport.Result.FAIL, QaReport.Result.PASS), clean, QualityPresets.QA_OFF);
		assertTrue("OFF ignores the verdicts: " + o7.getSkipped(), o7.isApproved());
		assertEquals(0, PbArtifactUtil.variantIndexOf(o7.getApproved()));
		BaseRecord n8 = node(u, wf, "p81-c07h", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs8 = variants(u, n8, "c07h", UUID.randomUUID().toString(), 2);
		PbArtifactUtil.AutoApproveOutcome o8 = PbArtifactUtil.autoApprove(u, vs8,
			verdicts(vs8, QaReport.Result.FAIL, QaReport.Result.PASS), clean, null);
		assertTrue("null mode reads OFF", o8.isApproved());
		assertEquals(0, PbArtifactUtil.variantIndexOf(o8.getApproved()));

		// edges: empty list; a veto still refuses the QA-chosen candidate; the existing-approved guard still applies
		PbArtifactUtil.AutoApproveOutcome none = PbArtifactUtil.autoApprove(u, List.of(), Map.of(), clean, QualityPresets.QA_ALL);
		assertEquals("failed: no artifact", none.getSkipped());
		List<BaseRecord> withNull = new ArrayList<>();
		withNull.add(null);
		assertEquals("failed: no artifact", PbArtifactUtil.autoApprove(u, withNull, null, null, null).getSkipped());
		BaseRecord n9 = node(u, wf, "p81-c07i", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs9 = variants(u, n9, "c07i", UUID.randomUUID().toString(), 2);
		String truncated = RenderPlan.Degradation.PROMPT_TRUNCATED + ": main description cut";
		PbArtifactUtil.AutoApproveOutcome o9 = PbArtifactUtil.autoApprove(u, vs9,
			verdicts(vs9, QaReport.Result.FAIL, QaReport.Result.PASS), List.of(truncated), QualityPresets.QA_ALL);
		assertFalse(o9.isApproved());
		assertEquals("the veto applies to the QA-chosen candidate", "degraded: " + truncated, o9.getSkipped());
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs9.get(1), orgId)));
		PbArtifactUtil.AutoApproveOutcome o1b = PbArtifactUtil.autoApprove(u, variants(u, n1, "c07a2", UUID.randomUUID().toString(), 2),
			Map.of(), clean, QualityPresets.QA_ALL);
		assertFalse(o1b.isApproved());
		assertEquals("existing-approved: " + vs1.get(2).get(FieldNames.FIELD_NAME), o1b.getSkipped());
		assertEquals("still exactly one approved on n1", 1, approvedCount(u, n1));
	}

	/**
	 * {@link PbNodeExecutor#applyAutoApprove} over a {@code variants[]} DTO: with no verdicts on the DTO and no
	 * {@code qaMode} it approves variant 0 exactly as before (top-level {@code reviewStatus=APPROVED}); with
	 * {@code qaMode=ALL} and per-variant {@code qa.result} it approves the PASS variant, names it in
	 * {@code autoApprovedArtifactObjectId}, flips {@code selected} on the entries and leaves the top-level
	 * {@code reviewStatus} unset (the DTO's own artifact is variant 0, which was not approved). A DTO without
	 * {@code variants[]} is the single-artifact path.
	 */
	@Test
	public void case08_applyAutoApproveReadsTheVariantsDto() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		// no QA on the DTO: variant 0, as before
		BaseRecord n1 = node(u, wf, "p81-c08a", PbNodeTypeEnumType.PORTRAIT);
		PbPipelineUtil.SceneGraph g = PbPipelineUtil.openNodeGraph(u, bk, wf, n1, null, null);
		List<BaseRecord> vs1 = variants(u, n1, "c08a", UUID.randomUUID().toString(), 2);
		Map<String, Object> out1 = dto(vs1, null);
		PbNodeExecutor.applyAutoApprove(g, out1);
		logger.info("P8-1 case08 out1=" + out1);
		assertEquals(Boolean.TRUE, out1.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertNull(out1.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
		assertEquals(vs1.get(0).get(FieldNames.FIELD_OBJECT_ID), out1.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
		assertEquals("variant 0 is the DTO's artifact, so the old key is set", PbReviewStatusEnumType.APPROVED.name(), out1.get("reviewStatus"));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(reread(u, vs1.get(0), orgId)));
		List<Map<String, Object>> ents1 = PbNodeExecutor.variantsOf(out1);
		assertEquals(Boolean.TRUE, ents1.get(0).get("selected"));
		assertEquals(PbReviewStatusEnumType.APPROVED.name(), ents1.get(0).get("reviewStatus"));
		assertEquals(Boolean.FALSE, ents1.get(1).get("selected"));

		// ALL with [FAIL, PASS]: variant 1
		BaseRecord n2 = node(u, wf, "p81-c08b", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs2 = variants(u, n2, "c08b", UUID.randomUUID().toString(), 2);
		Map<String, Object> out2 = dto(vs2, new QaReport.Result[] { QaReport.Result.FAIL, QaReport.Result.PASS });
		out2.put(PbNodeExecutor.KEY_QA_MODE, QualityPresets.QA_ALL);
		PbNodeExecutor.applyAutoApprove(g, out2);
		logger.info("P8-1 case08 out2=" + out2);
		assertEquals(Boolean.TRUE, out2.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertEquals(vs2.get(1).get(FieldNames.FIELD_OBJECT_ID), out2.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
		assertNull("variant 1 was approved, not the DTO's own artifact: the old key stays unset", out2.get("reviewStatus"));
		List<Map<String, Object>> ents2 = PbNodeExecutor.variantsOf(out2);
		assertEquals(Boolean.FALSE, ents2.get(0).get("selected"));
		assertNull(ents2.get(0).get("reviewStatus"));
		assertEquals(Boolean.TRUE, ents2.get(1).get("selected"));
		assertEquals(PbReviewStatusEnumType.APPROVED.name(), ents2.get(1).get("reviewStatus"));
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(reread(u, vs2.get(1), orgId)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, vs2.get(0), orgId)));
		assertTrue(selected(reread(u, vs2.get(1), orgId)));
		assertFalse(selected(reread(u, vs2.get(0), orgId)));

		// CANONICAL with variant 0 FAIL: skipped
		BaseRecord n3 = node(u, wf, "p81-c08c", PbNodeTypeEnumType.PORTRAIT);
		List<BaseRecord> vs3 = variants(u, n3, "c08c", UUID.randomUUID().toString(), 2);
		Map<String, Object> out3 = dto(vs3, new QaReport.Result[] { QaReport.Result.FAIL, QaReport.Result.PASS });
		out3.put(PbNodeExecutor.KEY_QA_MODE, QualityPresets.QA_CANONICAL);
		PbNodeExecutor.applyAutoApprove(g, out3);
		assertEquals(Boolean.FALSE, out3.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertTrue(String.valueOf(out3.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)),
			String.valueOf(out3.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)).startsWith("degraded: " + RenderPlan.Degradation.QA_FAIL + ": variant 0"));
		assertNull(out3.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
		assertNull(out3.get("reviewStatus"));
		assertEquals(0, approvedCount(u, n3));

		// no variants[] at all: the single-artifact path
		BaseRecord n4 = node(u, wf, "p81-c08d", PbNodeTypeEnumType.PORTRAIT);
		BaseRecord single = variants(u, n4, "c08d", UUID.randomUUID().toString(), 1).get(0);
		Map<String, Object> out4 = new LinkedHashMap<>();
		out4.put("artifactObjectId", single.get(FieldNames.FIELD_OBJECT_ID));
		PbNodeExecutor.applyAutoApprove(g, out4);
		assertEquals(Boolean.TRUE, out4.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertEquals(single.get(FieldNames.FIELD_OBJECT_ID), out4.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
		assertEquals(PbReviewStatusEnumType.APPROVED.name(), out4.get("reviewStatus"));

		// switch off: untouched, as before
		withAutoApprove(u, bk, false);
		BaseRecord n5 = node(u, wf, "p81-c08e", PbNodeTypeEnumType.PORTRAIT);
		PbPipelineUtil.SceneGraph gOff = PbPipelineUtil.openNodeGraph(u, PbBookUtil.readBook(u, bk.get(FieldNames.FIELD_OBJECT_ID), orgId), wf, n5, null, null);
		Map<String, Object> out5 = dto(variants(u, n5, "c08e", UUID.randomUUID().toString(), 2), new QaReport.Result[] { QaReport.Result.PASS, QaReport.Result.PASS });
		out5.put(PbNodeExecutor.KEY_QA_MODE, QualityPresets.QA_ALL);
		PbNodeExecutor.applyAutoApprove(gOff, out5);
		assertEquals(Boolean.FALSE, out5.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertEquals(PbNodeExecutor.SKIP_OFF, out5.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
		assertEquals(0, approvedCount(u, n5));
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("organization " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("user " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", p);
		return p;
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		}
		assertNotNull("book " + SLUG, bk);
		return bk;
	}

	private BaseRecord withAutoApprove(BaseRecord u, BaseRecord bk, boolean on) {
		assertTrue("setAutoApprove(" + on + ")", PbBookUtil.setAutoApprove(u, bk, on));
		BaseRecord fresh = PbBookUtil.readBook(u, bk.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull("book re-read", fresh);
		Boolean v = fresh.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE);
		assertEquals("the switch reads back as written", Boolean.valueOf(on), Boolean.valueOf(v != null && v.booleanValue()));
		return fresh;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), 0);
		assertNotNull("node " + handle, n);
		return n;
	}

	/**
	 * {@code count} IMAGE variants of one attempt for {@code ROLE_PORTRAIT} on {@code node}, persisted the way
	 * {@code executeRenderPlan} does (13-arg persist, shared attempt, pre-batch selection resolved once - null on a
	 * fresh node, {@code resolveSupersedes=false}); re-read with the full projection. Variant 0 is auto-selected by
	 * rule 1, the rest arrive unselected.
	 */
	private List<BaseRecord> variants(BaseRecord u, BaseRecord node, String tag, String attempt, int count) throws Exception {
		long orgId = orgOf(u);
		String groupPath = PbBookUtil.artifactGroupPath(SLUG);
		BaseRecord preBatch = PbArtifactUtil.findSelected(u, node, PbPipelineUtil.ROLE_PORTRAIT);
		List<BaseRecord> out = new ArrayList<>();
		int[] colours = new int[] { 0x225588, 0x885522, 0x558822, 0x882255 };
		for(int i = 0; i < count; i++) {
			BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(u, image,
				"p81_" + tag + "_v" + i + "_" + UUID.randomUUID().toString().substring(0, 8), groupPath, orgId);
			image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
			ByteModelUtil.setValue(image, solidPng(64, 96, colours[i % colours.length]));
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(u, image);
			assertNotNull("image data " + tag + " v" + i, created);
			BaseRecord a = PbArtifactUtil.persistArtifact(u, node, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
				groupPath, created, null, null, null, preBatch, i, attempt, false);
			assertNotNull("variant " + i + " for " + tag, a);
			BaseRecord fresh = PbArtifactUtil.readArtifact(u, a.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("variant " + i + " re-read", fresh);
			assertEquals(i, PbArtifactUtil.variantIndexOf(fresh));
			assertEquals(attempt, PbArtifactUtil.attemptOf(fresh));
			out.add(fresh);
		}
		return out;
	}

	private static byte[] solidPng(int w, int h, int rgb) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(rgb));
		g.fillRect(0, 0, w, h);
		g.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue("PNG encoder must be available", ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	/** Verdicts keyed by artifact objectId, in variant order; a null entry leaves that variant unchecked. */
	private static Map<String, QaReport.Result> verdicts(List<BaseRecord> variants, QaReport.Result... results) {
		Map<String, QaReport.Result> m = new LinkedHashMap<>();
		for(int i = 0; i < variants.size() && i < results.length; i++) {
			if(results[i] != null) {
				m.put(variants.get(i).get(FieldNames.FIELD_OBJECT_ID), results[i]);
			}
		}
		return m;
	}

	/** The shape {@code executeRenderPlan} leaves on its DTO: {@code artifactObjectId} (variant 0) + {@code variants[]}. */
	private static Map<String, Object> dto(List<BaseRecord> variants, QaReport.Result[] results) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("artifactObjectId", variants.get(0).get(FieldNames.FIELD_OBJECT_ID));
		List<Map<String, Object>> ents = new ArrayList<>();
		for(int i = 0; i < variants.size(); i++) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("artifactObjectId", variants.get(i).get(FieldNames.FIELD_OBJECT_ID));
			e.put("variantIndex", Integer.valueOf(i));
			e.put("selected", variants.get(i).get(OlioFieldNames.FIELD_PB_SELECTED));
			if(results != null && i < results.length && results[i] != null) {
				Map<String, Object> qa = new LinkedHashMap<>();
				qa.put("result", results[i].name());
				e.put(PbNodeExecutor.KEY_VARIANT_QA, qa);
			}
			ents.add(e);
		}
		out.put(PbNodeExecutor.KEY_VARIANTS, ents);
		return out;
	}

	// ─────────────────────────────── reads (all uncached) ───────────────────────────────

	private BaseRecord reread(BaseRecord u, BaseRecord a, long orgId) {
		BaseRecord fresh = PbArtifactUtil.readArtifact(u, a.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("artifact " + a.get(FieldNames.FIELD_NAME) + " must still be readable", fresh);
		return fresh;
	}

	private static boolean selected(BaseRecord a) {
		Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
		return sel != null && sel.booleanValue();
	}

	private boolean pinned(BaseRecord u, BaseRecord n, long orgId) {
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("node must still be readable", fresh);
		Boolean p = fresh.get(OlioFieldNames.FIELD_PB_PINNED);
		return p != null && p.booleanValue();
	}

	private int approvedCount(BaseRecord u, BaseRecord n) {
		int count = 0;
		for(BaseRecord a : PbArtifactUtil.listChain(u, n, PbPipelineUtil.ROLE_PORTRAIT)) {
			if(PbArtifactUtil.reviewStatusOf(a) == PbReviewStatusEnumType.APPROVED) {
				count++;
			}
		}
		return count;
	}

	/// Enum fields read back as the enum (or, on list projections, the raw string); never hand a generic
	/// get() to String.valueOf - type inference picks the char[] overload and throws ClassCastException.
	private static String enumName(Object v) {
		return (v != null ? v.toString().toUpperCase() : null);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> nodesOf(Map<String, Object> queue) {
		Object nodes = queue.get("nodes");
		assertNotNull("the queue carries nodes", nodes);
		return (List<Map<String, Object>>) nodes;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> artifactsOf(Map<String, Object> nodeEntry) {
		return (List<Map<String, Object>>) nodeEntry.get("artifacts");
	}

	private static Map<String, Object> nodeEntry(Map<String, Object> queue, BaseRecord n) {
		String oid = n.get(FieldNames.FIELD_OBJECT_ID);
		for(Map<String, Object> ne : nodesOf(queue)) {
			if(oid.equals(ne.get("nodeObjectId"))) {
				return ne;
			}
		}
		return null;
	}
}
