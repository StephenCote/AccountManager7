package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.enumWord;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-4 (design §9.1): the <b>lazy per-scene compose fallback</b> on the render path. A scene
 * rendered before the compose job reached it is composed by that render itself, out of story order, and
 * the plan says so with a {@code COMPOSED_OUT_OF_ORDER} degradation, which vetoes auto-approve (§10 rule 5).
 * The next render of the same scene finds the composition, reports no such degradation, writes no second
 * composition row, and - nothing else being degraded - is auto-approved.
 * <p>
 * Live {@code am7db} as {@code testUser1} in {@code /Development/World Building}; the SD backend is the
 * live Swarm node ({@code test.swarm.server}) through {@link SdTestGate} - a gate skip is a skip, never a
 * pass. No chat config is named: {@code ensureComposition} composes with the organization's {@code generalChat}
 * fallback, so the composition is {@code MIGRATED} ("LLM unavailable") when the organization has none, or
 * {@code LLM} when one answered; which one is logged, both are "composed now". Two real renders; the PNGs land
 * in {@code target/p44-lazy-<slug>-1.png} and {@code -2.png}. Timings are logged as MEASURED, never asserted.
 */
public class TestPbComposeLazyRender extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final long CASE_TIMEOUT_MS = 30L * 60L * 1000L;
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };

	private static final String TITLE = "The Breakwater";
	private static final String SETTING = "the Varrow Point breakwater at first light, wet granite blocks, a grey swell breaking white";
	private static final String ACTION = "Maren Voss hauls the swamped skiff up the slip, rope over her shoulder, boots slipping on the weed";
	private static final String MOOD = "exhausted and stubborn";
	private static final String TEXT = "By first light the storm had blown itself out and Maren Voss was on the breakwater, hauling "
		+ "the swamped skiff up the slip a foot at a time. The rope bit into her shoulder. Behind her the lighthouse "
		+ "beam was still turning, pale now against the grey, and she did not look up at it once.";

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
	 * The hook itself, with no SD in the loop (so it runs while the Swarm node is away): a cancelled token
	 * writes nothing and reports nothing composed; the first real call composes the scene (one row, revision 1,
	 * {@code composedNow}); the second call finds that row and leaves it untouched either way - found with no
	 * compose attempted when the organization's chat answered the first time ({@code LLM}), or re-attempted
	 * (a MIGRATED row is flagged for re-compose) and left alone as a {@code hardFailure} when the chat is still
	 * unavailable ({@code MIGRATED}; P4-3 "dead LLM writes MIGRATED then leaves it alone until forced") -
	 * so {@code composedNow} is false and nothing is written; a user-edited row is found and never re-composed.
	 */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case00_ensureCompositionComposesOnceThenFindsAndNeverForces() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "ensure");
			BaseRecord scene = addScene(u, bk, 1, TITLE, SETTING, ACTION, MOOD, TEXT);
			long gid = ((Number) scene.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertNull("precondition: the scene has no composition", PbComposeUtil.findSceneComposition(u, scene, gid, orgId));

			/// 1. A cancelled token: nothing written, nothing composed, no row.
			SummarizeProgress cancelled = new SummarizeProgress();
			cancelled.cancel();
			PbComposeUtil.EnsureResult e0 = PbComposeUtil.ensureComposition(u, bk, scene, cancelled);
			assertFalse("a cancelled ensure composed nothing", e0.composedNow);
			assertNotNull("...but reports the outcome", e0.result);
			assertTrue("...as cancelled", e0.result.cancelled);
			assertNull("...with no composition to plan from", e0.composition);
			assertNull("...and wrote no row", PbComposeUtil.findSceneComposition(u, scene, gid, orgId));

			/// 2. First real call: composes the scene.
			long t1 = System.currentTimeMillis();
			PbComposeUtil.EnsureResult e1 = PbComposeUtil.ensureComposition(u, bk, scene, new SummarizeProgress());
			long ms1 = System.currentTimeMillis() - t1;
			assertTrue("the first ensure composed the scene", e1.composedNow);
			assertNotNull(e1.result);
			assertTrue("...and wrote it", e1.result.isWritten());
			assertNotNull(e1.composition);
			String compOid = oidOf(e1.composition);
			assertEquals("the hook's composition is the written one", compOid, oidOf(e1.result.composition));
			BaseRecord comp1 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertNotNull("the row is readable", comp1);
			assertEquals(compOid, oidOf(comp1));
			assertEquals("the row is the scene's", idOf(scene), fkId(comp1, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("a new row is revision 1", Integer.valueOf(1), comp1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());
			String source1 = enumWord(comp1, OlioFieldNames.FIELD_PB_SOURCE);
			boolean migrated = PbCompositionSourceEnumType.MIGRATED.name().equals(source1);
			assertTrue("MIGRATED (no generalChat answered) or LLM (one did): " + source1,
				migrated || PbCompositionSourceEnumType.LLM.name().equals(source1));
			assertEquals("the outcome says the same", migrated, e1.result.migratedFallback);
			logger.info("MEASURED: P4-4 ensureComposition first call " + ms1 + " ms source=" + source1
				+ (migrated ? " (the organization's generalChat fallback did not answer; §29 MIGRATED row)" : " (the organization's generalChat answered)"));

			/// 3. Second call: found and left alone (LLM), or re-attempted and left alone as a hardFailure (MIGRATED, chat still away).
			long t2 = System.currentTimeMillis();
			PbComposeUtil.EnsureResult e2 = PbComposeUtil.ensureComposition(u, bk, scene, new SummarizeProgress());
			long ms2 = System.currentTimeMillis() - t2;
			logger.info("MEASURED: P4-4 ensureComposition second call " + ms2 + " ms composedNow=" + e2.composedNow
				+ (e2.result != null ? " hardFailure=" + e2.result.hardFailure + " reason=" + e2.result.failureReason : " (found, no compose attempted)"));
			assertFalse("the second ensure composed nothing (so a render would report no COMPOSED_OUT_OF_ORDER)", e2.composedNow);
			assertNotNull(e2.composition);
			assertEquals("the same row either way", compOid, oidOf(e2.composition));
			BaseRecord comp2 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertEquals("still one row", compOid, oidOf(comp2));
			assertEquals("...untouched", Integer.valueOf(1), comp2.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("...same source", source1, enumWord(comp2, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("still one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());
			if(migrated) {
				assertNotNull("a MIGRATED row is re-attempted (flagged for re-compose)...", e2.result);
				assertTrue("...and, the chat still unavailable, left alone as a hardFailure", e2.result.hardFailure);
				assertFalse(e2.result.isWritten());
				assertEquals("...the existing row is what the render plans from", compOid, oidOf(e2.result.composition));
			}
			else {
				assertNull("an LLM row is found; no compose is attempted", e2.result);
			}

			/// 4. A user-edited row is found and never re-composed, whatever its source.
			Integer revBefore = comp2.get(OlioFieldNames.FIELD_PB_REVISION);
			BaseRecord edit = PbGraphUtil.patchOf(comp2, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_USER_EDITED);
			edit.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			assertNotNull("mark userEdited", ioContext.getAccessPoint().update(u, edit));
			PbComposeUtil.EnsureResult e3 = PbComposeUtil.ensureComposition(u, bk, scene, new SummarizeProgress());
			assertFalse("a user-edited row is never re-composed by the hook", e3.composedNow);
			assertNull("...no compose was attempted", e3.result);
			assertEquals("...the edited row is what the render plans from", compOid, oidOf(e3.composition));
			BaseRecord comp3 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertEquals("...untouched", revBefore, comp3.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(Boolean.TRUE, comp3.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("still one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * Render 1 of a never-composed scene with auto-approve on: the render composes the scene itself and
	 * reports exactly one {@code COMPOSED_OUT_OF_ORDER} degradation (DTO and persisted plan, on the BASE
	 * stage), auto-approve is vetoed ("degraded: ...") and the artifact stays GENERATED. Render 2: no such
	 * degradation, the same single composition row untouched, and the attempt is auto-approved.
	 */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case01_firstRenderComposesOutOfOrderAndVetoesAutoApprove() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P4-4 lazy compose on render");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		assertEquals("precondition: the deployment default this JVM resolves is the installed checkpoint (BaseTest pins it)",
			installed, SDUtil.getDefaultModel());

		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		String bkOid = null;
		try {
			bk = newBook(u, "lazy");
			bkOid = oidOf(bk);
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			BaseRecord scene = addScene(u, bk, 1, TITLE, SETTING, ACTION, MOOD, TEXT);
			String sceneOid = oidOf(scene);
			long gid = ((Number) scene.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertNull("precondition: the scene has no composition", PbComposeUtil.findSceneComposition(u, scene, gid, orgId));

			Map<String, Object> sw = PbServiceFacade.setAutoApprove(u, bkOid, true);
			assertEquals("auto-approve is on: " + sw, Boolean.TRUE, sw.get("autoApprove"));

			// ─── render 1: composes out of order, degraded, not auto-approved ───
			long t1 = System.currentTimeMillis();
			Map<String, Object> first = PbServiceFacade.requestRenderPlanScene(u, bkOid, sceneOid, sdu);
			long ms1 = System.currentTimeMillis() - t1;
			logger.info("MEASURED: P4-4 lazy render 1 of " + slug + " wall " + ms1 + " ms on " + server);
			logger.info("P4-4 lazy render 1 DTO: " + JSONUtil.exportObject(first));
			assertEquals(Boolean.TRUE, first.get("executed"));
			assertEquals("v3", first.get("renderPath"));
			assertEquals(sceneOid, first.get("sceneObjectId"));
			assertEquals("COMPLETED", String.valueOf(first.get("runStatus")).toUpperCase());
			assertEquals("DONE_UNVERIFIED", first.get("nodeStatus"));
			assertEquals(Integer.valueOf(1), first.get("artifactRevision"));
			String nodeOid = (String) first.get("nodeObjectId");
			assertNotNull("nodeObjectId", nodeOid);

			BaseRecord comp1 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertNotNull("the render composed the scene itself", comp1);
			String source1 = enumWord(comp1, OlioFieldNames.FIELD_PB_SOURCE);
			boolean migrated = PbCompositionSourceEnumType.MIGRATED.name().equals(source1);
			assertTrue("the lazy compose wrote a MIGRATED row (no generalChat in the organization) or an LLM one (a generalChat answered): " + source1,
				migrated || PbCompositionSourceEnumType.LLM.name().equals(source1));
			logger.info("MEASURED: P4-4 lazy compose source=" + source1
				+ (migrated ? " (the organization's generalChat fallback did not answer; §29 MIGRATED row)" : " (the organization's generalChat answered)"));
			assertEquals("the lazy row is the scene's", idOf(scene), fkId(comp1, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("a new row is revision 1", Integer.valueOf(1), comp1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());

			List<String> degradations1 = strings(first, "degradations");
			assertEquals("exactly one degradation on a lazy first render (nothing else is degraded on a fresh book): " + degradations1, 1, degradations1.size());
			String d1 = degradations1.get(0);
			assertTrue("the DTO entry is 'COMPOSED_OUT_OF_ORDER: <message>': " + d1, d1.startsWith(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER + ": "));
			assertTrue("the message says how to clear it: " + d1, d1.contains("re-compose in order"));
			assertTrue("the message says the render composed it: " + d1, d1.contains("out of story order"));
			if(migrated) {
				assertTrue("a MIGRATED fallback is named in the message: " + d1, d1.contains("(MIGRATED: "));
			}
			assertEquals("the DTO's degradation list is what degradationsOf reads", degradations1, PbNodeExecutor.degradationsOf(first));

			String planOid1 = (String) first.get("planArtifactObjectId");
			assertNotNull("planArtifactObjectId", planOid1);
			RenderPlan plan1 = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(u, planOid1, orgId));
			assertNotNull("the persisted plan parses", plan1);
			List<RenderPlan.Degradation> planDeg1 = new ArrayList<>();
			for(RenderPlan.Degradation d : plan1.getDegradations()) {
				if(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER.equals(d.getCode())) {
					planDeg1.add(d);
				}
			}
			assertEquals("the persisted plan carries exactly one COMPOSED_OUT_OF_ORDER degradation: " + plan1.getDegradations(), 1, planDeg1.size());
			assertEquals("...on the BASE stage", RenderPlan.STAGE_BASE, planDeg1.get(0).getStage());
			assertNull("...bound to no actor", planDeg1.get(0).getActorId());
			assertTrue(planDeg1.get(0).getMessage().contains("re-compose in order"));
			assertEquals("the DTO entry is the plan's code + message", planDeg1.get(0).getCode() + ": " + planDeg1.get(0).getMessage(), d1);

			assertEquals("a degraded attempt is never auto-approved", Boolean.FALSE, first.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped1 = (String) first.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
			assertNotNull("the veto is reported", skipped1);
			assertTrue("vetoed as degraded: " + skipped1, skipped1.startsWith("degraded: "));
			assertTrue("...naming the out-of-order compose: " + skipped1, skipped1.contains(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER));
			assertNull("a vetoed attempt claims no review status on the DTO", first.get("reviewStatus"));

			String artifactOid1 = (String) first.get("artifactObjectId");
			assertNotNull(artifactOid1);
			BaseRecord artifact1 = PbArtifactUtil.readArtifact(u, artifactOid1, orgId);
			assertNotNull(artifact1);
			assertEquals("the vetoed attempt stays GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(artifact1));
			assertNull("...with no approver", artifact1.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertEquals("the first attempt is auto-selected (rule 1)", Boolean.TRUE, artifact1.get(OlioFieldNames.FIELD_PB_SELECTED));
			BaseRecord node1 = PbGraphUtil.readNode(u, nodeOid, orgId);
			assertNotNull(node1);
			assertFalse("a vetoed attempt never pins the node", Boolean.TRUE.equals(node1.get(OlioFieldNames.FIELD_PB_PINNED)));
			writePng(u, artifact1, "p44-lazy-" + slug + "-1.png", ms1);

			// ─── render 2: the composition exists; nothing out of order; auto-approved ───
			long t2 = System.currentTimeMillis();
			Map<String, Object> second = PbServiceFacade.requestRenderPlanScene(u, bkOid, sceneOid, sdu);
			long ms2 = System.currentTimeMillis() - t2;
			logger.info("MEASURED: P4-4 lazy render 2 of " + slug + " wall " + ms2 + " ms on " + server);
			logger.info("P4-4 lazy render 2 DTO: " + JSONUtil.exportObject(second));
			assertEquals(Boolean.TRUE, second.get("executed"));
			assertEquals("COMPLETED", String.valueOf(second.get("runStatus")).toUpperCase());
			assertEquals("the same node", nodeOid, second.get("nodeObjectId"));
			assertEquals(Integer.valueOf(2), second.get("artifactRevision"));
			assertNotEquals("a new base artifact", artifactOid1, second.get("artifactObjectId"));

			List<String> degradations2 = strings(second, "degradations");
			for(String d : degradations2) {
				assertFalse("the second render found the composition; nothing is out of order: " + degradations2,
					d.startsWith(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER + ": "));
			}
			assertTrue("nothing else is degraded on a fresh book, so the second render is clean: " + degradations2, degradations2.isEmpty());
			RenderPlan plan2 = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(u, (String) second.get("planArtifactObjectId"), orgId));
			assertNotNull(plan2);
			for(RenderPlan.Degradation d : plan2.getDegradations()) {
				assertNotEquals("the second plan carries no COMPOSED_OUT_OF_ORDER: " + plan2.getDegradations(),
					RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER, d.getCode());
			}

			BaseRecord comp2 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertNotNull(comp2);
			assertEquals("the same composition row", oidOf(comp1), oidOf(comp2));
			assertEquals("...untouched (no re-compose)", (Integer) comp1.get(OlioFieldNames.FIELD_PB_REVISION), (Integer) comp2.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("...same source", source1, enumWord(comp2, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("still one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());

			assertEquals("a clean attempt with the switch on is auto-approved", Boolean.TRUE, second.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertNull("nothing was skipped", second.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), second.get("reviewStatus"));
			BaseRecord artifact2 = PbArtifactUtil.readArtifact(u, (String) second.get("artifactObjectId"), orgId);
			assertNotNull(artifact2);
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(artifact2));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, artifact2.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertEquals(Boolean.TRUE, artifact2.get(OlioFieldNames.FIELD_PB_SELECTED));
			BaseRecord older = PbArtifactUtil.readArtifact(u, artifactOid1, orgId);
			assertEquals("revision 1 is no longer selected", Boolean.FALSE, older.get(OlioFieldNames.FIELD_PB_SELECTED));
			assertEquals("...and still GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(older));
			writePng(u, artifact2, "p44-lazy-" + slug + "-2.png", ms2);
		}
		finally {
			if(bkOid != null) {
				try {
					PbServiceFacade.setAutoApprove(u, bkOid, false);
				}
				catch(Exception e) {
					logger.warn("reset auto-approve: " + e.getMessage());
				}
			}
			deleteBook(u, bk);
		}
	}

	/**
	 * The LLM branch of the hook, proven rather than accepted-either-way: with a {@code generalChat} the user
	 * owns (the only config the lazy hook consults - it names none; {@code callLlmInternal} resolves
	 * {@code generalChat} through {@code ChatUtil.resolveConfig}, user's ~/Chat then the shared library), the
	 * first ensure composes from the LLM ({@code LLM} source, not {@code MIGRATED}, actors staged), and the
	 * second finds that row with no compose attempted. No SD in the loop. The config is this JVM's resolved
	 * LLM route ({@code OlioTestUtil.getOllamaOpenAIConfig}); a pre-existing {@code generalChat} is reconciled
	 * and kept, one created here is deleted.
	 */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case02_ensureCompositionComposesFromTheLlmWhenGeneralChatExists() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		boolean preExisting = ChatUtil.getConfig(u, OlioModelNames.MODEL_CHAT_CONFIG, null, ChatUtil.DEFAULT_GENERAL_CHAT_CONFIG_NAME) != null;
		try {
			cfg = OlioTestUtil.getOllamaOpenAIConfig(u, ChatUtil.DEFAULT_GENERAL_CHAT_CONFIG_NAME, testProperties);
			assertNotNull("chatConfig generalChat", cfg);
			/// The policy-decision cache keys a group-less chatConfig lookup by its query hash and holds a miss
			/// as DENY for the read policy's decisionAge (120 s); the pre-check above and case00's lazy ensure
			/// both planted one, and creating the config does not evict it (CachePolicyUtil.clearCache(BaseRecord)
			/// matches by record urn). Evict it so resolveConfig sees the row just created, as a fresh JVM would.
			CacheUtil.clearCache();
			logger.info("[p44-lazy-llm] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
				+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model")
				+ " generalChat " + (preExisting ? "pre-existing (reconciled)" : "created"));
			assertNotNull("the hook's fallback lookup resolves the user's generalChat",
				ChatUtil.resolveConfig(u, OlioModelNames.MODEL_CHAT_CONFIG, ChatUtil.DEFAULT_GENERAL_CHAT_CONFIG_NAME, null));

			bk = newBook(u, "llm");
			BaseRecord scene = addScene(u, bk, 1, TITLE, SETTING, ACTION, MOOD, TEXT);
			long gid = ((Number) scene.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertNull("precondition: the scene has no composition", PbComposeUtil.findSceneComposition(u, scene, gid, orgId));

			long t1 = System.currentTimeMillis();
			PbComposeUtil.EnsureResult e1 = PbComposeUtil.ensureComposition(u, bk, scene, new SummarizeProgress());
			long ms1 = System.currentTimeMillis() - t1;
			assertTrue("the first ensure composed the scene", e1.composedNow);
			assertNotNull(e1.result);
			logger.info("MEASURED: P4-4 ensureComposition (generalChat present) " + ms1 + " ms written=" + e1.result.isWritten()
				+ " hardFailure=" + e1.result.hardFailure + " migratedFallback=" + e1.result.migratedFallback + " reason=" + e1.result.failureReason
				+ " actors=" + (e1.result.actors != null ? e1.result.actors.size() : -1));
			assertTrue("...and wrote it", e1.result.isWritten());
			assertFalse("the LLM answered: no MIGRATED fallback (reason: " + e1.result.failureReason + ")", e1.result.migratedFallback);
			assertFalse("...and no hard failure", e1.result.hardFailure);
			BaseRecord comp1 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertNotNull("the row is readable", comp1);
			String compOid = oidOf(comp1);
			assertEquals("the hook's composition is the written one", compOid, oidOf(e1.composition));
			assertEquals("source LLM", PbCompositionSourceEnumType.LLM.name(), enumWord(comp1, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("revision 1", Integer.valueOf(1), comp1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(Boolean.FALSE, comp1.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());

			/// Found, no compose attempted: the render after this one reports no COMPOSED_OUT_OF_ORDER.
			PbComposeUtil.EnsureResult e2 = PbComposeUtil.ensureComposition(u, bk, scene, new SummarizeProgress());
			assertFalse("the second ensure composed nothing", e2.composedNow);
			assertNull("an LLM row is found; no compose is attempted", e2.result);
			assertEquals("the same row", compOid, oidOf(e2.composition));
			BaseRecord comp2 = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
			assertEquals("...untouched", Integer.valueOf(1), comp2.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("still one SCENE composition in the book", 1, PbComposeUtil.listSceneCompositions(u, bk).size());
		}
		finally {
			deleteBook(u, bk);
			if(cfg != null && !preExisting) {
				try {
					if(!ioContext.getAccessPoint().delete(u, cfg)) {
						logger.warn("cleanup: generalChat was not deleted");
					}
				}
				catch(Exception e) {
					logger.error("cleanup: generalChat: " + e.getMessage());
				}
			}
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	@SuppressWarnings("unchecked")
	private static List<String> strings(Map<String, Object> out, String key) {
		Object v = out.get(key);
		assertTrue(key + " is a list: " + v, v instanceof List);
		return (List<String>) v;
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

	private void writePng(BaseRecord u, BaseRecord artifact, String fileName, long wallMs) throws Exception {
		byte[] bytes = artifactBytes(u, artifact);
		File png = new File("target", fileName);
		Files.write(png.toPath(), bytes);
		BaseRecord snapshot = artifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
		logger.info("MEASURED: P4-4 lazy render on " + server + " model=" + (snapshot != null ? snapshot.get(OlioFieldNames.FIELD_SD_MODEL) : null)
			+ " wallMs=" + wallMs + " durationMs=" + artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS) + " bytes=" + bytes.length
			+ " seed=" + artifact.get(OlioFieldNames.FIELD_PB_SEED) + " png=" + png.getAbsolutePath());
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
		assertEquals(setting, fresh.get(OlioFieldNames.FIELD_PB_SETTING));
		return fresh;
	}
}
