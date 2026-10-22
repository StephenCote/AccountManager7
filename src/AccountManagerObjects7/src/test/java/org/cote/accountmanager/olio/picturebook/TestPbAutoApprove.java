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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-5: auto-approve (design §10 rule 5) over {@link PbNodeExecutor#executeNodeRun},
 * {@link PbArtifactUtil#autoApprove}, {@link PbBookUtil#setAutoApprove} and the {@link PbServiceFacade}
 * settings surface.
 * <p>
 * Live {@code am7db}, no SD server: every run here executes a SCENE_PROMPT node (a TEXT artifact built from the
 * scene's description) or a REFERENCE_STRIP node (pure Java2D), both of which {@code executeNodeRun} accepts
 * with a null {@code SDUtil}. The shared {@code pb2c-graph} fixture book is used, as the other Pb tests do; the
 * book's {@code autoApprove} switch is reset to off in {@link #resetAutoApprove} because other tests in the same
 * workflow assume it. Every assertion is scoped to the nodes this test created.
 */
public class TestPbAutoApprove extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	/// Same read-only principal as TestPbNodeRun.case06 / TestPbReview: never enrolled in any per-book role.
	private static final String READER_USER = "p13ReadOnly";
	private static final String SLUG = "pb2c-graph";

	@Before
	public void autoApproveSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	/** The fixture book is shared with TestPbGraph / TestPbNodeRun / TestPbReview, which expect the switch off. */
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

	// ─────────────────────────────── the switch, off and on ───────────────────────────────

	/**
	 * Default off: a completed run leaves its artifact GENERATED and selected (rule 1's auto-select), the node
	 * unpinned, and the DTO says so ({@code autoApproved=false}, {@code autoApproveSkipped="off"}).
	 */
	@Test
	public void case01_offLeavesTheAttemptGenerated() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), false);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = promptNode(u, bk, wf, "p15-c01", "c01 a lighthouse keeper at dusk");

		Map<String, Object> out = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		logger.info("P1-5 case01 durationMs=" + out.get("durationMs") + " out=" + out);

		assertEquals("the run completed", PbRunStatusEnumType.COMPLETED.name(), status(out.get("runStatus")));
		assertEquals(Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertEquals(PbNodeExecutor.SKIP_OFF, out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
		assertNull("off never claims a review status on the DTO", out.get("reviewStatus"));

		BaseRecord a = artifactOf(u, out, orgId);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a));
		assertTrue("the first attempt is auto-selected (rule 1)", selected(a));
		assertFalse("off never pins", pinned(u, node, orgId));
	}

	/**
	 * On: the attempt's artifact is APPROVED, selected and {@code approvedBy=AUTO}, the node is pinned, the DTO
	 * reports {@code autoApproved=true} + {@code reviewStatus=APPROVED}, and the review queue's
	 * {@code auto-approved} filter lists exactly it for this node.
	 */
	@Test
	public void case02_onApprovesSelectsAndPins() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord node = promptNode(u, bk, wf, "p15-c02", "c02 a market square in the rain");

		Map<String, Object> out = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		logger.info("P1-5 case02 durationMs=" + out.get("durationMs") + " out=" + out);

		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(out.get("runStatus")));
		assertEquals("the DTO reports the approval", Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertNull("nothing was skipped", out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
		assertEquals("the DTO carries the new review status", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));
		assertTrue("a prompt node has no degradations: " + out.get("degradations"), PbNodeExecutor.degradationsOf(out).isEmpty());

		BaseRecord a = artifactOf(u, out, orgId);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a));
		assertTrue("approve implies select", selected(a));
		assertEquals("stamped by the auto-approver", PbArtifactUtil.APPROVED_BY_AUTO, a.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue("auto-approve pins the node", pinned(u, node, orgId));

		Map<String, Object> queue = PbServiceFacade.listReviewQueue(u, bookOid, PbServiceFacade.REVIEW_FILTER_AUTO_APPROVED);
		assertEquals(Boolean.TRUE, queue.get("autoApprove"));
		Map<String, Object> ne = nodeEntry(queue, node);
		assertNotNull("the auto-approved filter lists this node", ne);
		List<Map<String, Object>> arts = artifactsOf(ne);
		assertEquals("exactly the one auto-approved artifact for this node: " + arts, 1, arts.size());
		assertEquals(a.get(FieldNames.FIELD_OBJECT_ID), arts.get(0).get("objectId"));
		assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, arts.get(0).get("approvedBy"));
		assertEquals(Boolean.TRUE, ne.get("pinned"));
	}

	// ─────────────────────────────── guard (a): never replace an existing approval ───────────────────────────────

	/**
	 * A human approval already on the chain wins: with the switch on, the new attempt arrives GENERATED and
	 * unselected (rule 1), the auto-approver skips it with {@code existing-approved: <name>}, and the human
	 * approval - including who approved it - is untouched.
	 */
	@Test
	public void case03_existingHumanApprovalIsNeverReplaced() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), false);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String userOid = u.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord node = promptNode(u, bk, wf, "p15-c03", "c03 two travellers on a mountain pass");

		Map<String, Object> first = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		BaseRecord a1 = artifactOf(u, first, orgId);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a1));
		BaseRecord a1Approved = PbArtifactUtil.approve(u, a1, userOid);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a1Approved));
		assertEquals(userOid, a1Approved.get(OlioFieldNames.FIELD_PB_APPROVED_BY));

		bk = withAutoApprove(u, bk, true);
		Map<String, Object> second = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		logger.info("P1-5 case03 durationMs=" + second.get("durationMs") + " out=" + second);

		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(second.get("runStatus")));
		assertEquals(Boolean.FALSE, second.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		String skipped = (String) second.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
		assertNotNull("the skip reason is reported", skipped);
		assertTrue("skipped for the existing approval: " + skipped, skipped.startsWith("existing-approved: "));
		String a1Name = a1.get(FieldNames.FIELD_NAME);
		assertTrue("...naming it: " + skipped, skipped.contains(a1Name));
		assertNull(second.get("reviewStatus"));

		BaseRecord a2 = artifactOf(u, second, orgId);
		assertFalse("two distinct artifacts", sameOid(a1, a2));
		assertEquals("the newcomer stays GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a2));
		assertFalse("the newcomer never displaces the approval (rule 1)", selected(a2));
		assertNull("the newcomer carries no approver", a2.get(OlioFieldNames.FIELD_PB_APPROVED_BY));

		BaseRecord a1After = reread(u, a1, orgId);
		assertEquals("the human approval is untouched", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a1After));
		assertTrue(selected(a1After));
		assertEquals("...and still credited to the human", userOid, a1After.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertEquals("exactly one approval on the chain", 1, approvedCount(u, node, PbPipelineUtil.ROLE_SCENE_PROMPT));
	}

	// ─────────────────────────────── guard (b): never approve a degraded attempt ───────────────────────────────

	/**
	 * A scene with no description makes the prompt node record an empty prompt and report the degradation; the
	 * auto-approver refuses it ({@code degraded: ...}), the artifact stays GENERATED (selected, since nothing else
	 * is on the chain), the node stays unpinned.
	 */
	@Test
	public void case04_degradedPromptIsNotApproved() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = promptNode(u, bk, wf, "p15-c04", null);

		Map<String, Object> out = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		logger.info("P1-5 case04 durationMs=" + out.get("durationMs") + " out=" + out);

		assertEquals("a degraded attempt still completes its run", PbRunStatusEnumType.COMPLETED.name(), status(out.get("runStatus")));
		List<String> degradations = PbNodeExecutor.degradationsOf(out);
		assertEquals("one degradation: " + degradations, 1, degradations.size());
		assertTrue(degradations.get(0), degradations.get(0).startsWith(PbPipelineUtil.ROLE_SCENE_PROMPT + ": scene has no description"));
		assertEquals(Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		String skipped = (String) out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
		assertNotNull(skipped);
		assertTrue("skipped as degraded: " + skipped, skipped.startsWith("degraded: "));
		assertTrue("...carrying the reason: " + skipped, skipped.contains(degradations.get(0)));
		assertNull(out.get("reviewStatus"));

		BaseRecord a = artifactOf(u, out, orgId);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a));
		assertTrue("rule 1 still auto-selects the only attempt", selected(a));
		assertNull(a.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertFalse("a degraded attempt never pins", pinned(u, node, orgId));
	}

	/**
	 * The reference strip is the executor whose fallbacks used to be invisible (the v1 {@code landscapeFailed}
	 * flag is vacuous on V3): one bound portrait with real bytes, one bound portrait node with no artifact. The
	 * strip renders from what it has, reports {@code portrait1: no selected artifact}, and - switch on - is not
	 * auto-approved because of it. No SD server: the strip is pure Java2D.
	 */
	@Test
	public void case05_referenceStripReportsMissingSourceAndIsNotApproved() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String wfPath = PbBookUtil.workflowGroupPath(SLUG);

		BaseRecord nodeA = node(u, wf, "p15-c05-a", PbNodeTypeEnumType.PORTRAIT);
		BaseRecord nodeB = node(u, wf, "p15-c05-b", PbNodeTypeEnumType.PORTRAIT);
		BaseRecord strip = node(u, wf, "p15-c05-strip", PbNodeTypeEnumType.REFERENCE_STRIP);
		BaseRecord portraitA = imageArtifact(u, nodeA, "c05-a", 64, 96, 0x3366AA);
		assertTrue("precondition: A's portrait is selected", selected(portraitA));
		assertNull("precondition: B has no portrait", PbArtifactUtil.findSelected(u, nodeB, PbPipelineUtil.ROLE_PORTRAIT));
		assertNotNull(PbGraphUtil.addBinding(u, wf, strip, PbPipelineUtil.ROLE_PORTRAIT_0, 0, nodeA, null, wfPath));
		assertNotNull(PbGraphUtil.addBinding(u, wf, strip, PbPipelineUtil.ROLE_PORTRAIT_1, 0, nodeB, null, wfPath));

		Map<String, Object> out = PbNodeExecutor.executeNodeRun(u, bk, wf, strip, (SDUtil) null);
		logger.info("P1-5 case05 durationMs=" + out.get("durationMs") + " out=" + out);

		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(out.get("runStatus")));
		Long byteLength = (Long) out.get("byteLength");
		assertTrue("the strip was rendered from the one available portrait", byteLength != null && byteLength.longValue() > 0L);
		List<String> degradations = PbNodeExecutor.degradationsOf(out);
		assertEquals("exactly the missing portrait1 is reported: " + degradations, 1, degradations.size());
		assertEquals(PbPipelineUtil.ROLE_PORTRAIT_1 + ": no selected artifact", degradations.get(0));
		assertEquals(Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		String skipped = (String) out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
		assertTrue("skipped as degraded: " + skipped, skipped != null && skipped.startsWith("degraded: ")
			&& skipped.contains(PbPipelineUtil.ROLE_PORTRAIT_1 + ": no selected artifact"));

		BaseRecord a = artifactOf(u, out, orgId);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a));
		assertFalse(pinned(u, strip, orgId));
	}

	// ─────────────────────────────── guard (c): switching off un-approves nothing ───────────────────────────────

	/** Switch on, run (auto-approved), switch off: the approval, its approver and the pin all stay. */
	@Test
	public void case06_switchingOffKeepsExistingApprovals() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = promptNode(u, bk, wf, "p15-c06", "c06 a harbour at first light");

		Map<String, Object> out = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		assertEquals(Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		BaseRecord a = artifactOf(u, out, orgId);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a));

		bk = withAutoApprove(u, bk, false);
		BaseRecord aAfter = reread(u, a, orgId);
		assertEquals("switching off un-approves nothing", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(aAfter));
		assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, aAfter.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue(selected(aAfter));
		assertTrue("the pin stays too", pinned(u, node, orgId));

		Map<String, Object> next = PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
		logger.info("P1-5 case06 durationMs=" + next.get("durationMs") + " out=" + next);
		assertEquals("off: the next attempt is not approved", Boolean.FALSE, next.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		assertEquals(PbNodeExecutor.SKIP_OFF, next.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
		BaseRecord a2 = artifactOf(u, next, orgId);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a2));
		assertFalse("...and never displaces the earlier auto-approval (rule 1)", selected(a2));
		assertEquals(1, approvedCount(u, node, PbPipelineUtil.ROLE_SCENE_PROMPT));
	}

	// ─────────────────────────────── the facade: read for any reader, write for updaters ───────────────────────────────

	/**
	 * {@code getAutoApprove} defaults to false and is readable by a read-only principal; {@code setAutoApprove}
	 * is 403 for that principal (nothing written) and round-trips true/false for the owner, re-reading the book
	 * each time. The gate is the same {@code requireBookUpdate} the review routes use.
	 */
	@Test
	public void case07_facadeReadsForReadersAndWritesForUpdatersOnly() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), false);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);

		Map<String, Object> read = PbServiceFacade.getAutoApprove(u, bookOid);
		assertEquals(bookOid, read.get("bookObjectId"));
		assertEquals("default off", Boolean.FALSE, read.get("autoApprove"));

		BaseRecord reader = readOnlyReader(u, bk);
		Map<String, Object> readerRead = PbServiceFacade.getAutoApprove(reader, bookOid);
		assertEquals("any reader of the book may read the switch", Boolean.FALSE, readerRead.get("autoApprove"));
		try {
			PbServiceFacade.setAutoApprove(reader, bookOid, true);
			fail("a read-only principal must not be able to change auto-approve");
		}
		catch(PictureBookException pbe) {
			assertEquals("403 for the reader: " + pbe.getMessage(), 403, pbe.getStatus());
		}
		assertEquals("the refused write changed nothing", Boolean.FALSE,
			PbServiceFacade.getAutoApprove(u, bookOid).get("autoApprove"));

		Map<String, Object> on = PbServiceFacade.setAutoApprove(u, bookOid, true);
		assertEquals(bookOid, on.get("bookObjectId"));
		assertEquals(Boolean.TRUE, on.get("autoApprove"));
		assertEquals("GET sees the write", Boolean.TRUE, PbServiceFacade.getAutoApprove(u, bookOid).get("autoApprove"));
		assertEquals("the workflow view carries it", Boolean.TRUE, PbServiceFacade.workflowView(u, bookOid).get("autoApprove"));
		Map<String, Object> off = PbServiceFacade.setAutoApprove(u, bookOid, false);
		assertEquals(Boolean.FALSE, off.get("autoApprove"));
		assertEquals(Boolean.FALSE, PbServiceFacade.getAutoApprove(u, bookOid).get("autoApprove"));

		try {
			PbServiceFacade.setAutoApprove(u, UUID.randomUUID().toString(), true);
			fail("an unknown book must be 404");
		}
		catch(PictureBookException pbe) {
			assertEquals(404, pbe.getStatus());
		}
	}

	// ─────────────────────────────── P4-7: the veto set over the plan's degradation codes ───────────────────────────────

	/**
	 * P4-7: the plan degradations an attempt carries are classified by their {@code CODE: message} prefix. The three
	 * accepted codes never veto, each of the five {@link PbArtifactUtil#AUTO_APPROVE_VETO_CODES} does, the executor's
	 * {@code upscale:} pseudo-entry and any free-form entry veto (they are not known codes), and a mixed list is
	 * reduced to exactly its vetoing entries, in order. Pure classification: no artifact, no DB write.
	 */
	@Test
	public void case08_vetoSetIsTheFiveCodesPlusAnythingUnknown() {
		assertTrue(PbArtifactUtil.vetoingDegradations(null).isEmpty());
		assertTrue(PbArtifactUtil.vetoingDegradations(List.of()).isEmpty());

		String textOnly = RenderPlan.Degradation.ACTOR_TEXT_ONLY + ": Mara rendered from description (no approved portrait)";
		String substituted = RenderPlan.Degradation.MODEL_SUBSTITUTED + ": location model substituted (flux2Klein_9b not installed)";
		String qaWarn = RenderPlan.Degradation.QA_WARN + ": soft QA warning";
		assertTrue("the three accepted codes never veto", PbArtifactUtil.vetoingDegradations(List.of(textOnly, substituted, qaWarn)).isEmpty());
		for(String code : new String[] { RenderPlan.Degradation.ACTOR_TEXT_ONLY, RenderPlan.Degradation.MODEL_SUBSTITUTED, RenderPlan.Degradation.QA_WARN }) {
			assertTrue(code + " is a known code", RenderPlan.Degradation.isKnownCode(code));
			assertFalse(code + " is not in the veto set", PbArtifactUtil.AUTO_APPROVE_VETO_CODES.contains(code));
			assertTrue("a bare code with no message is still accepted", PbArtifactUtil.vetoingDegradations(List.of(code)).isEmpty());
		}

		assertEquals(5, PbArtifactUtil.AUTO_APPROVE_VETO_CODES.size());
		String[] vetoes = new String[] {
			RenderPlan.Degradation.ACTOR_UNRESOLVED + ": Old Bes rendered from the row's text (character abc could not be read)",
			RenderPlan.Degradation.ACTOR_FAILED + ": Jideon reference could not be bound",
			RenderPlan.Degradation.LOCATION_TEXT_ONLY + ": the tavern rendered from text (no approved render)",
			RenderPlan.Degradation.PROMPT_TRUNCATED + ": ELEM_LOW dropped: gulls (12 tokens over budget 512)",
			RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER + ": composed lazily by the render" };
		for(String entry : vetoes) {
			String code = entry.substring(0, entry.indexOf(':'));
			assertTrue(code + " is a known code", RenderPlan.Degradation.isKnownCode(code));
			assertTrue(code + " is in the veto set", PbArtifactUtil.AUTO_APPROVE_VETO_CODES.contains(code));
			assertEquals("each veto code vetoes on its own", List.of(entry), PbArtifactUtil.vetoingDegradations(List.of(entry)));
			assertEquals("a bare veto code vetoes too", List.of(code), PbArtifactUtil.vetoingDegradations(List.of(code)));
		}

		String upscale = "upscale: UPSCALE stage planned by the preset but not rendered (Phase 2 renders BASE only)";
		assertEquals("the executor's upscale pseudo-entry is not a known code and vetoes", List.of(upscale), PbArtifactUtil.vetoingDegradations(List.of(upscale)));
		String freeForm = PbPipelineUtil.ROLE_PORTRAIT_1 + ": no selected artifact";
		assertEquals("a free-form executor entry vetoes", List.of(freeForm), PbArtifactUtil.vetoingDegradations(List.of(freeForm)));
		assertEquals("no colon at all: the whole entry is the code", List.of("something went sideways"),
			PbArtifactUtil.vetoingDegradations(List.of("something went sideways")));
		assertEquals("codes are case-sensitive: a lowercased accepted code is unknown and vetoes", List.of("actor_text_only: x"),
			PbArtifactUtil.vetoingDegradations(List.of("actor_text_only: x")));
		assertEquals("a leading colon leaves an empty code, which is unknown", List.of(": no code"), PbArtifactUtil.vetoingDegradations(List.of(": no code")));

		List<String> mixed = new java.util.ArrayList<>();
		mixed.add(textOnly);
		mixed.add(vetoes[3]);
		mixed.add(null);
		mixed.add(substituted);
		mixed.add(upscale);
		mixed.add(qaWarn);
		mixed.add(vetoes[0]);
		assertEquals("only the vetoing entries, in list order, nulls skipped", List.of(vetoes[3], upscale, vetoes[0]), PbArtifactUtil.vetoingDegradations(mixed));
	}

	/**
	 * P4-7, the real outcome once: on a fresh PORTRAIT node, an attempt carrying only accepted codes is approved
	 * ({@code null}, APPROVED, {@code approvedBy=AUTO}, pinned); a second attempt carrying one veto code is refused
	 * with {@code degraded:} naming only that entry and stays GENERATED; a third, clean attempt is refused with
	 * {@code existing-approved: <first>}; and no artifact at all is {@code failed: no artifact} before any
	 * classification. No SD server: the artifacts are solid Java2D PNGs.
	 */
	@Test
	public void case09_autoApproveOutcomeFollowsTheVetoSet() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = withAutoApprove(u, book(u), true);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = node(u, wf, "p47-c09", PbNodeTypeEnumType.PORTRAIT);

		String textOnly = RenderPlan.Degradation.ACTOR_TEXT_ONLY + ": Mara rendered from description (no approved portrait)";
		String substituted = RenderPlan.Degradation.MODEL_SUBSTITUTED + ": portrait model substituted";
		String truncated = RenderPlan.Degradation.PROMPT_TRUNCATED + ": main description cut (40 tokens over budget 225)";

		assertEquals("failed: no artifact", PbArtifactUtil.autoApprove(u, null, List.of(truncated)));

		BaseRecord first = imageArtifact(u, node, "c09-a", 64, 96, 0x225588);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(first));
		assertNull("accepted codes only: approved", PbArtifactUtil.autoApprove(u, first, List.of(textOnly, substituted)));
		BaseRecord firstFresh = reread(u, first, orgId);
		assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(firstFresh));
		assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, firstFresh.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertTrue("approve implies select", selected(firstFresh));
		assertTrue("auto-approve pins the node", pinned(u, node, orgId));

		BaseRecord second = imageArtifact(u, node, "c09-b", 64, 96, 0x885522);
		String reason = PbArtifactUtil.autoApprove(u, second, List.of(textOnly, truncated, substituted));
		assertEquals("the veto wins before the chain is consulted, and names only the vetoing entry", "degraded: " + truncated, reason);
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, second, orgId)));
		assertNull(reread(u, second, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_BY));
		assertEquals("the first approval is untouched", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(reread(u, first, orgId)));

		BaseRecord third = imageArtifact(u, node, "c09-c", 64, 96, 0x558822);
		assertEquals("a clean attempt is refused by the existing approval",
			"existing-approved: " + first.get(FieldNames.FIELD_NAME), PbArtifactUtil.autoApprove(u, third, List.of(textOnly)));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(reread(u, third, orgId)));
		assertEquals("exactly one approved on the chain", 1, approvedCount(u, node, PbPipelineUtil.ROLE_PORTRAIT));
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

	/**
	 * Set the switch and hand back the re-read book: the executor reads {@code autoApprove} off the book it is
	 * given, so a stale record would test nothing.
	 */
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
	 * A SCENE_PROMPT node scoped to a fresh scene of the book (the TestPbGraph.case16 idiom: a random high
	 * sceneIndex so it never collides with the seeded scenes, then a scope patch on the node). A null
	 * {@code description} leaves the scene blank, which is the executor's "no description" degradation.
	 */
	private BaseRecord promptNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String prefix, String description) throws Exception {
		int sceneIndex = 100000 + Math.abs(UUID.randomUUID().hashCode() % 800000);
		BaseRecord scene = PbBookUtil.createScene(u, bk, sceneIndex, prefix + " scene", PbBookUtil.bookGroupPath(SLUG));
		assertNotNull("scene " + sceneIndex, scene);
		if(description != null) {
			BaseRecord descPatch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_DESCRIPTION);
			descPatch.set(FieldNames.FIELD_DESCRIPTION, description);
			assertNotNull("scene description patch", ioContext.getAccessPoint().update(u, descPatch));
		}
		BaseRecord n = node(u, wf, prefix, PbNodeTypeEnumType.SCENE_PROMPT);
		BaseRecord scopePatch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE,
			OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF);
		scopePatch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		scopePatch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, scene.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("node scope patch", ioContext.getAccessPoint().update(u, scopePatch));
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull("scoped node re-read", fresh);
		String scopeRef = fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("the node points at its scene", sceneOid, scopeRef);
		return fresh;
	}

	/** A selected IMAGE artifact for {@code ROLE_PORTRAIT} on {@code node}, its data.data a solid Java2D PNG. */
	private BaseRecord imageArtifact(BaseRecord u, BaseRecord node, String tag, int w, int h, int rgb) throws Exception {
		long orgId = orgOf(u);
		String groupPath = PbBookUtil.artifactGroupPath(SLUG);
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(u, image,
			"p15_portrait_" + tag + "_" + UUID.randomUUID().toString().substring(0, 8), groupPath, orgId);
		image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(image, solidPng(w, h, rgb));
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(u, image);
		assertNotNull("portrait image data for " + tag, created);
		BaseRecord a = PbArtifactUtil.persistArtifact(u, node, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
			groupPath, created, null, null, null);
		assertNotNull("portrait artifact for " + tag, a);
		return a;
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

	/** Same shape as TestPbReview.readOnlyReader: direct READ grants on the book group and its projected foreigns. */
	private BaseRecord readOnlyReader(BaseRecord owner, BaseRecord bk) {
		OrganizationContext org = getTestOrganization(ORG_A);
		long orgId = orgOf(owner);
		BaseRecord admin = org.getAdminUser();
		BaseRecord reader = ioContext.getFactory().getCreateUser(admin, READER_USER, orgId);
		assertNotNull("reader " + READER_USER, reader);
		assertFalse("precondition: the reader must not be a group administrator, or the 403 proves nothing",
			ioContext.getAuthorizationUtil().isModelAdministrator(ModelNames.MODEL_GROUP, reader));

		long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("the book's group must be readable by its owner", dir);
		BaseRecord readGroup = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		BaseRecord readData = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read/GROUP permission", readGroup);
		assertNotNull("Read/DATA permission", readData);
		ioContext.getMemberUtil().member(admin, dir, reader, readGroup, true);
		ioContext.getMemberUtil().member(admin, dir, reader, readData, true);
		for(String f : new String[] { OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_SERIES, OlioFieldNames.FIELD_PB_CHAPTER,
				OlioFieldNames.FIELD_PB_SOURCE_DATA, OlioFieldNames.FIELD_PB_SD_CONFIG, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG }) {
			Object v = bk.get(f);
			if(v instanceof BaseRecord) {
				ioContext.getMemberUtil().member(admin, (BaseRecord) v, reader, readData, true);
			}
		}
		CacheUtil.clearCache();
		BaseRecord asReader = PbBookUtil.readBook(reader, bk.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("precondition: the reader must be able to read the book, or the 403 proves nothing", asReader);
		return reader;
	}

	// ─────────────────────────────── reads (all uncached) ───────────────────────────────

	/** The artifact the executor DTO names, re-read with the full artifact projection. */
	private BaseRecord artifactOf(BaseRecord u, Map<String, Object> out, long orgId) {
		String oid = (String) out.get("artifactObjectId");
		assertNotNull("the DTO names its artifact", oid);
		BaseRecord a = PbArtifactUtil.readArtifact(u, oid, orgId);
		assertNotNull("artifact " + oid + " must be readable", a);
		return a;
	}

	private BaseRecord reread(BaseRecord u, BaseRecord a, long orgId) {
		BaseRecord fresh = PbArtifactUtil.readArtifact(u, a.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("artifact " + a.get(FieldNames.FIELD_NAME) + " must still be readable", fresh);
		return fresh;
	}

	private static boolean sameOid(BaseRecord a, BaseRecord b) {
		String ao = a.get(FieldNames.FIELD_OBJECT_ID);
		String bo = b.get(FieldNames.FIELD_OBJECT_ID);
		return ao != null && ao.equals(bo);
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

	private int approvedCount(BaseRecord u, BaseRecord n, String role) {
		int count = 0;
		for(BaseRecord a : PbArtifactUtil.listChain(u, n, role)) {
			if(PbArtifactUtil.reviewStatusOf(a) == PbReviewStatusEnumType.APPROVED) {
				count++;
			}
		}
		return count;
	}

	/// Enum fields read back as the enum (or, on list projections, the raw string); never hand a generic
	/// get() to String.valueOf - type inference picks the char[] overload and throws ClassCastException.
	private static String status(Object v) {
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
