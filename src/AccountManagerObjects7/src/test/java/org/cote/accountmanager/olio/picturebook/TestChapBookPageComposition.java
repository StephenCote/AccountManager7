package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-8: a ChapBook page's landscape prompt lives on a {@code CHAPBOOK_PAGE} composition
 * ({@code promptOverride}) that the prompt writers create on demand and keep in step with the page's own
 * {@code sdPrompt}. Live {@code am7db}, {@code chapbookTestUser} in {@code /Development/ChapBook Tests}, a real
 * ChapBook from Stephen's poem ({@code ChapBookUtil.createChapBook}); no render path, no GPU, no SD gate. The
 * one LLM case goes through {@code LlmTestGate} (the {@code [LLM-GATE]} line in the run log names the tier).
 */
public class TestChapBookPageComposition extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	/// Budgets, never thresholds: the LLM and plan cases get ample room and fail only on error.
	private static final long LONG_TIMEOUT_MS = 20L * 60L * 1000L;
	/// A renderer that can never answer: the plan builder must not need one to choose its location text.
	private static final String DEAD_SERVER = "http://127.0.0.1:1";

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

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	@Before
	public void setUpChapBookPage() {
		OlioModelNames.use();
		assertTrue("resource.properties must put ChapBooks on v3 for this class (BaseTest re-pins it)",
			PbRenderPath.V3.equals(PbRenderPath.valueFor(chapBookStub())));
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		assertNotNull("organization " + ORG_PATH, ctx);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
	}

	// ─────────────────────────────── set / clear ───────────────────────────────

	/**
	 * A fresh page has no composition. The first {@code setSceneLandscapePrompt} creates the CHAPBOOK_PAGE
	 * composition in the book's group, named {@code page.<sceneOid>}, with {@code promptOverride} = the prompt,
	 * {@code promptLocked} true, source USER, userEdited true, revision 2 (1 at create + the write), while the
	 * scene mirrors it ({@code sdPrompt} = prompt, locked). The facade board GET sees it as a page composition.
	 * A second set replaces the prompt on both and bumps the revision. Clearing (null) unlocks both: scene
	 * {@code sdPrompt} null / unlocked, composition {@code promptOverride} null / unlocked, revision + 1, and the
	 * composition record survives (userEdited stays true). Setting again after a clear locks again.
	 */
	@Test
	public void setPromptCreatesPageCompositionAndMirrorsSceneThenClearUnlocks() throws Exception {
		Fixture fx = createFixture("setclear");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord scene0 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNull("precondition: a fresh page has no composition",
			PbComposeUtil.findSceneComposition(testUser, scene0, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
		refused("the board 404s a page without a composition", 404, "Page " + sceneOid + " has no composition; set its prompt first",
			() -> PbServiceFacade.getSceneComposition(testUser, fx.bookOid, sceneOid));

		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));

		BaseRecord scene1 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("the page mirrors the prompt", GENUINE_PROMPT, scene1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("a hand-set prompt locks the page", Boolean.TRUE, scene1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord comp1 = PbComposeUtil.findSceneComposition(testUser, scene1, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
		assertNotNull("the set created the page composition", comp1);
		comp1 = PbComposeUtil.readComposition(testUser, comp1.get(FieldNames.FIELD_OBJECT_ID), fx.orgId);
		assertNotNull(comp1);
		assertEquals("named page.<sceneOid>", PbComposeUtil.PAGE_COMPOSITION_NAME_PREFIX + sceneOid, comp1.get(FieldNames.FIELD_NAME));
		assertEquals("CHAPBOOK_PAGE purpose", PbCompositionPurposeEnumType.CHAPBOOK_PAGE.name(), status(comp1, OlioFieldNames.FIELD_PB_PURPOSE));
		assertEquals("in the book's group", Long.valueOf(groupId), Long.valueOf(((Number) comp1.get(FieldNames.FIELD_GROUP_ID)).longValue()));
		assertEquals("the composition carries the prompt", GENUINE_PROMPT, comp1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals("the composition is locked", Boolean.TRUE, comp1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals("a hand-set prompt is a USER composition", PbCompositionSourceEnumType.USER.name(), status(comp1, OlioFieldNames.FIELD_PB_SOURCE));
		assertEquals("a hand-set prompt is a user edit", Boolean.TRUE, comp1.get(OlioFieldNames.FIELD_PB_USER_EDITED));
		assertEquals("revision 1 at create + 1 for the write", 2, intOf(comp1.get(OlioFieldNames.FIELD_PB_REVISION)));
		assertEquals("the composition belongs to the scene", idOf(scene1), fkId(comp1, OlioFieldNames.FIELD_PB_SCENE));
		assertEquals("the composition belongs to the book", idOf(book), fkId(comp1, OlioFieldNames.FIELD_PB_BOOK));
		assertTrue("the plan builder sees a page composition", PbRenderPlanUtil.isPageComposition(comp1));
		String compOid = comp1.get(FieldNames.FIELD_OBJECT_ID);

		Map<String, Object> board = PbServiceFacade.getSceneComposition(testUser, fx.bookOid, sceneOid);
		assertEquals("the board resolves the page composition", compOid, board.get("compositionObjectId"));
		@SuppressWarnings("unchecked")
		Map<String, Object> dto = (Map<String, Object>) board.get("composition");
		assertEquals(GENUINE_PROMPT, dto.get("promptOverride"));
		assertEquals(Boolean.TRUE, dto.get("promptLocked"));
		assertEquals("CHAPBOOK_PAGE", String.valueOf(dto.get("purpose")).toUpperCase());

		/// Second set through the transport form: same composition, new prompt on both sides, revision bumped,
		/// and the result names the composition it wrote.
		ChapBookUtil.PromptWriteResult second = ChapBookUtil.setSceneLandscapePromptResult(testUser, sceneOid, SECOND_PROMPT);
		assertTrue("the scene copy was written", second.isUpdated());
		assertTrue("the composition was written", second.isCompositionUpdated());
		assertFalse("a complete write is not partial", second.isPartial());
		assertEquals("the result names the page composition", compOid, second.getCompositionObjectId());
		assertNull("a complete write carries no error", second.getError());
		assertEquals(SECOND_PROMPT, second.getPrompt());
		assertTrue(second.isLocked());
		Map<String, Object> wire = second.toMap();
		assertEquals(Boolean.TRUE, wire.get("updated"));
		assertEquals(Boolean.TRUE, wire.get("compositionUpdated"));
		assertEquals(Boolean.TRUE, wire.get("promptLocked"));
		assertEquals(compOid, wire.get("compositionObjectId"));
		assertFalse("no error key on a complete write", wire.containsKey("error"));
		BaseRecord scene2 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals(SECOND_PROMPT, scene2.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals(Boolean.TRUE, scene2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord comp2 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		assertEquals("one composition per page: the second set reuses it", compOid,
			PbComposeUtil.findSceneComposition(testUser, scene2, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE).get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(SECOND_PROMPT, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals(Boolean.TRUE, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals(3, intOf(comp2.get(OlioFieldNames.FIELD_PB_REVISION)));

		/// Clear: both sides null and unlocked, the composition survives.
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, null));
		BaseRecord scene3 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNull("a clear removes the page prompt", scene3.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("a clear unlocks the page", Boolean.FALSE, scene3.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord comp3 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		assertNotNull("the composition survives a clear", comp3);
		assertNull("a clear removes the composition prompt", comp3.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals("a clear unlocks the composition", Boolean.FALSE, comp3.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals("a clear is still a user edit", Boolean.TRUE, comp3.get(OlioFieldNames.FIELD_PB_USER_EDITED));
		assertEquals(4, intOf(comp3.get(OlioFieldNames.FIELD_PB_REVISION)));
		Map<String, Object> board3 = PbServiceFacade.getSceneComposition(testUser, fx.bookOid, sceneOid);
		@SuppressWarnings("unchecked")
		Map<String, Object> dto3 = (Map<String, Object>) board3.get("composition");
		assertNull(dto3.get("promptOverride"));
		assertEquals(Boolean.FALSE, dto3.get("promptLocked"));

		/// A blank string clears the same way (idempotent on an already-clear page, still a write).
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, "   "));
		assertNull(PbBookUtil.readScene(testUser, sceneOid, fx.orgId).get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertNull(PbComposeUtil.readComposition(testUser, compOid, fx.orgId).get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));

		/// Set again after a clear: locked again on both sides.
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		assertEquals(Boolean.TRUE, PbBookUtil.readScene(testUser, sceneOid, fx.orgId).get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord comp5 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		assertEquals(GENUINE_PROMPT, comp5.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals(Boolean.TRUE, comp5.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

		/// The other page is untouched: no composition of its own.
		String otherOid = fx.sceneOids.get(1);
		BaseRecord other = PbBookUtil.readScene(testUser, otherOid, fx.orgId);
		assertNull("the sibling page gets no composition",
			PbComposeUtil.findSceneComposition(testUser, other, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));

		refused("a missing page", 404, "Scene not found: ", () -> ChapBookUtil.setSceneLandscapePrompt(testUser, "no-such-scene", GENUINE_PROMPT));
		refused("no page id", 400, "user and sceneObjectId are required", () -> ChapBookUtil.setSceneLandscapePrompt(testUser, null, GENUINE_PROMPT));
	}

	/**
	 * A partial write, produced for real rather than mocked: an {@code olio.pb.scene} created by the test user
	 * with no {@code book} (so no page composition can be created for it — {@code writePageComposition} refuses
	 * with "has no readable book"). The scene patch lands first, so the transport form reports
	 * {@code updated:true, compositionUpdated:false}, no composition id, and an error naming both the scene
	 * write and the refusal; the scene really does carry the prompt afterwards. The in-process form turns the
	 * same state into the 500 the render path relies on. A regenerate on the same scene never reaches the
	 * write (no stanza → 400), so its partial branch is covered by the shared {@code writePageComposition}.
	 */
	@Test
	public void partialWriteIsReportedOnTheResultAndThrownInProcess() throws Exception {
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String sceneOid = createBooklessScene("partial");

		ChapBookUtil.PromptWriteResult r = ChapBookUtil.setSceneLandscapePromptResult(testUser, sceneOid, GENUINE_PROMPT);
		logger.info("[p48] partial write result: " + r.toMap());
		assertTrue("the scene copy was written", r.isUpdated());
		assertFalse("the composition was not", r.isCompositionUpdated());
		assertTrue("that is a partial write", r.isPartial());
		assertNull("no composition to name", r.getCompositionObjectId());
		assertNotNull("the refusal is on the result", r.getError());
		assertTrue("the error names the scene write: " + r.getError(), r.getError().contains("Scene " + sceneOid + " sdPrompt persisted but its page composition was not"));
		assertTrue("the error names the refusal: " + r.getError(), r.getError().contains("has no readable book"));
		assertEquals(GENUINE_PROMPT, r.getPrompt());
		assertTrue(r.isLocked());
		Map<String, Object> wire = r.toMap();
		assertEquals(Boolean.TRUE, wire.get("updated"));
		assertEquals(Boolean.FALSE, wire.get("compositionUpdated"));
		assertEquals(Boolean.TRUE, wire.get("promptLocked"));
		assertFalse("no composition id on the wire", wire.containsKey("compositionObjectId"));
		assertEquals(r.getError(), wire.get("error"));

		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, orgId);
		assertNotNull(scene);
		assertEquals("the scene copy really moved", GENUINE_PROMPT, scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals(Boolean.TRUE, scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

		refused("the in-process form raises the partial write", 500, "Scene " + sceneOid + " sdPrompt persisted but its page composition was not",
			() -> ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, SECOND_PROMPT));
		assertEquals("the in-process form still wrote the scene copy before refusing", SECOND_PROMPT,
			PbBookUtil.readScene(testUser, sceneOid, orgId).get(OlioFieldNames.FIELD_CB_SD_PROMPT));

		/// A clear on the same scene is the same partial shape (updated, composition refused), unlocked.
		ChapBookUtil.PromptWriteResult cleared = ChapBookUtil.setSceneLandscapePromptResult(testUser, sceneOid, null);
		assertTrue(cleared.isUpdated());
		assertTrue(cleared.isPartial());
		assertFalse(cleared.isLocked());
		assertNull(cleared.getPrompt());
		assertNull(PbBookUtil.readScene(testUser, sceneOid, orgId).get(OlioFieldNames.FIELD_CB_SD_PROMPT));
	}

	// ─────────────────────────────── regenerate (live LLM) ───────────────────────────────

	/**
	 * Regenerating against the live LLM replaces the hand-set prompt on the page (unlocked) and on the page
	 * composition: {@code promptOverride} = the new prompt, {@code promptLocked} false, source LLM, revision + 1,
	 * {@code userEdited} untouched (still true from the hand-set), and the page's DONE node goes STALE. Read the
	 * {@code [LLM-GATE] route=... tier=...} line in the run log for which tier answered; with no tier the
	 * {@code Assume} in {@code requireLlmReachable} skips, which is NOT a pass.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void regenerateSetsSourceLlm() throws Exception {
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		logger.info("[p48] test.llm.ollama.server=" + llmServer + " route=" + testProperties.getProperty("test.llm.route")
			+ " tier=" + testProperties.getProperty("test.llm.resolvedTier"));
		SdTestGate.requireLlmReachable(llmServer);
		BaseRecord chatConfig = OlioTestUtil.getPbChatConfig(testUser, "chapbookP48RegenLlm", testProperties);
		assertNotNull("The live chatConfig must be built", chatConfig);

		Fixture fx = createFixture("regen");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		BaseRecord scene1 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNotNull("precondition: the page carries a stanza to regenerate from", scene1.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
		BaseRecord comp1 = PbComposeUtil.findSceneComposition(testUser, scene1, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
		assertNotNull(comp1);
		String compOid = comp1.get(FieldNames.FIELD_OBJECT_ID);
		comp1 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		int rev1 = intOf(comp1.get(OlioFieldNames.FIELD_PB_REVISION));
		assertEquals(PbCompositionSourceEnumType.USER.name(), status(comp1, OlioFieldNames.FIELD_PB_SOURCE));
		assertEquals(Boolean.TRUE, comp1.get(OlioFieldNames.FIELD_PB_USER_EDITED));

		/// A DONE page node so the regenerate's stale mark is observable.
		BaseRecord wf = workflow(book);
		BaseRecord node = ensurePageNode(book, wf, sceneOid);
		assertTrue(PbGraphUtil.persistStatus(testUser, node, PbNodeStatusEnumType.DONE));
		assertEquals(PbNodeStatusEnumType.DONE.name(), status(PbGraphUtil.readNode(testUser, node.get(FieldNames.FIELD_OBJECT_ID), fx.orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

		long t0 = System.currentTimeMillis();
		String regenerated = ChapBookUtil.regenerateSceneLandscapePrompt(testUser, sceneOid, chatConfig);
		logger.info("[p48] regenerate durationMs=" + (System.currentTimeMillis() - t0) + " prompt='" + regenerated + "'");
		assertNotNull(regenerated);
		assertFalse(regenerated.isBlank());
		assertTrue("The regenerated prompt must be a genuine LLM prompt, got: " + regenerated, ChapBookUtil.isGenuineStoredPrompt(regenerated));
		assertNotEquals("The regenerate must replace the hand-set prompt", GENUINE_PROMPT, regenerated);

		BaseRecord scene2 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The regenerated prompt is persisted on the page", regenerated, scene2.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("A regenerate unlocks the page", Boolean.FALSE, scene2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord comp2 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		assertNotNull(comp2);
		assertEquals("The regenerated prompt is the composition's promptOverride", regenerated, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals("A regenerate unlocks the composition", Boolean.FALSE, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals("A regenerate is an LLM composition", PbCompositionSourceEnumType.LLM.name(), status(comp2, OlioFieldNames.FIELD_PB_SOURCE));
		assertEquals("A regenerate bumps the revision", rev1 + 1, intOf(comp2.get(OlioFieldNames.FIELD_PB_REVISION)));
		assertEquals("A regenerate does not touch userEdited", Boolean.TRUE, comp2.get(OlioFieldNames.FIELD_PB_USER_EDITED));
		assertEquals("The same composition, not a second one", compOid,
			PbComposeUtil.findSceneComposition(testUser, scene2, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE).get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord nodeAfter = PbGraphUtil.readNode(testUser, node.get(FieldNames.FIELD_OBJECT_ID), fx.orgId);
		assertEquals("A regenerated prompt marks the page node STALE", PbNodeStatusEnumType.STALE.name(), status(nodeAfter, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("The plan builder reads the regenerated prompt as the location text", regenerated, PbRenderPlanUtil.locationText(scene2, comp2));
	}

	// ─────────────────────────────── plan ───────────────────────────────

	/**
	 * The plan builder prefers the page composition's {@code promptOverride} over the scene's own
	 * {@code sdPrompt}: after a set (both carry P1) the scene is patched directly to a divergent P2 (the pre-P4-8
	 * copy drifting), {@code locationText} still answers P1, and one {@code buildScenePlan} against a dead
	 * renderer puts P1, verbatim, in {@code plan.getLocation().getDescription()}. Only that is asserted: the
	 * model pick and degradations depend on the inventory, which this case does not need.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void pagePlanPrefersCompositionPrompt() throws Exception {
		Fixture fx = createFixture("plan");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		BaseRecord scene1 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		BaseRecord comp = PbComposeUtil.findSceneComposition(testUser, scene1, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
		assertNotNull(comp);
		comp = PbComposeUtil.readComposition(testUser, comp.get(FieldNames.FIELD_OBJECT_ID), fx.orgId);
		assertEquals(GENUINE_PROMPT, comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertTrue(PbRenderPlanUtil.isPageComposition(comp));
		assertEquals("in step: the composition prompt is the location text", GENUINE_PROMPT, PbRenderPlanUtil.locationText(scene1, comp));
		assertEquals("without a composition the scene prompt is the location text", GENUINE_PROMPT, PbRenderPlanUtil.locationText(scene1, null));

		/// Drift the pre-P4-8 copy directly (not through the prompt writer, which would update both).
		BaseRecord drift = PbGraphUtil.patchOf(scene1, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_CB_SD_PROMPT);
		drift.set(OlioFieldNames.FIELD_CB_SD_PROMPT, SECOND_PROMPT);
		assertNotNull("scene.sdPrompt drift patch", IOSystem.getActiveContext().getAccessPoint().update(testUser, drift));
		BaseRecord scene2 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("precondition: the scene copy diverged", SECOND_PROMPT, scene2.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		BaseRecord compAgain = PbComposeUtil.readComposition(testUser, comp.get(FieldNames.FIELD_OBJECT_ID), fx.orgId);
		assertEquals("precondition: the composition still carries P1", GENUINE_PROMPT, compAgain.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals("the composition prompt wins over the drifted scene copy", GENUINE_PROMPT, PbRenderPlanUtil.locationText(scene2, compAgain));

		long t0 = System.currentTimeMillis();
		RenderPlan plan = PbRenderPlanUtil.buildScenePlan(testUser, book, scene2, PbRenderPlanUtil.PlanOptions.NONE, SDAPIEnumType.SWARM, DEAD_SERVER);
		logger.info("[p48] buildScenePlan durationMs=" + (System.currentTimeMillis() - t0) + " degradations=" + plan.getDegradations());
		assertNotNull("the plan builder returns a plan without a renderer", plan);
		assertNotNull("the plan has a location", plan.getLocation());
		assertEquals("the plan's location text is the composition prompt, verbatim", GENUINE_PROMPT, plan.getLocation().getDescription());
		assertNotEquals("never the drifted scene copy", SECOND_PROMPT, plan.getLocation().getDescription());
	}

	// ─────────────────────────────── refusals ───────────────────────────────

	/** Actors are off on ChapBook pages: the board's add-actor on a page composition is 409 and writes no row. */
	@Test
	public void addActorOnPage409() throws Exception {
		Fixture fx = createFixture("actor");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		BaseRecord comp = PbComposeUtil.findSceneComposition(testUser, scene, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
		assertNotNull(comp);
		String compOid = comp.get(FieldNames.FIELD_OBJECT_ID);
		comp = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		int rev0 = intOf(comp.get(OlioFieldNames.FIELD_PB_REVISION));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put(FieldNames.FIELD_DESCRIPTION, "a crone by the hearth");
		refused("add actor on a page", 409, "Actors are not enabled on ChapBook pages",
			() -> PbServiceFacade.addActor(testUser, fx.bookOid, compOid, body));
		assertTrue("no actor row was written", PbComposeUtil.listActors(testUser, comp).isEmpty());
		assertEquals("revision untouched", rev0, intOf(PbComposeUtil.readComposition(testUser, compOid, fx.orgId).get(OlioFieldNames.FIELD_PB_REVISION)));

		/// Elements are allowed on every purpose; a page element lands and bumps the revision.
		Map<String, Object> el = new LinkedHashMap<>();
		el.put(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop");
		el.put(FieldNames.FIELD_DESCRIPTION, "an age-old spellbound tome");
		Map<String, Object> added = PbServiceFacade.addElement(testUser, fx.bookOid, compOid, el);
		assertEquals(Boolean.TRUE, added.get("created"));
		assertEquals(rev0 + 1, intOf(added.get("revision")));
		assertEquals(1, PbComposeUtil.listElements(testUser, comp).size());
	}

	/**
	 * A page node whose page has no prompt anywhere (scene {@code sdPrompt} null, no page composition) is refused
	 * by the executor with the exact 409 before anything is rendered. Per {@link PbNodeExecutor#executeNode}'s
	 * V3 P0-8 contract the executor stamps the node FAILED + lastError for any exception from the per-type
	 * executor (the 409 included) and rethrows it unchanged, so the node reads FAILED afterwards with the
	 * refusal as its lastError; nothing else is written.
	 */
	@Test
	public void executeChapBookPageWithNoPrompt409() throws Exception {
		Fixture fx = createFixture("noprompt");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		assertNotNull(sceneIndex);
		if(scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT) != null) {
			/// Blank the pre-P4-8 copy directly, never through the prompt writer (that would create a composition).
			BaseRecord blank = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_CB_SD_PROMPT);
			blank.set(OlioFieldNames.FIELD_CB_SD_PROMPT, null);
			assertNotNull(IOSystem.getActiveContext().getAccessPoint().update(testUser, blank));
			scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		}
		assertNull("precondition: no scene prompt", scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertNull("precondition: no page composition",
			PbComposeUtil.findSceneComposition(testUser, scene, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));

		BaseRecord wf = workflow(book);
		BaseRecord node = ensurePageNode(book, wf, sceneOid);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		assertNotEquals("precondition: the fresh page node is not already FAILED",
			PbNodeStatusEnumType.FAILED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		CapturingSdu sdu = new CapturingSdu(DEAD_SERVER);
		String expected = "Page " + sceneIndex + " has no prompt; set or generate its landscape prompt before rendering it";
		refused("execute a page without a prompt", 409, expected,
			() -> PbNodeExecutor.executeNode(testUser, book, wf, node, sdu));
		assertTrue("the renderer was never reached", sdu.captured.isEmpty());
		BaseRecord after = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals("executeNode stamps the node FAILED on the refusal and rethrows it (V3 P0-8)",
			PbNodeStatusEnumType.FAILED.name(), status(after, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = after.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertNotNull("the refusal is recorded as the node's lastError", lastError);
		assertTrue("lastError carries the refusal text but was: " + lastError, lastError.contains("has no prompt"));
		assertNull("no artifact was selected", PbArtifactUtil.findSelected(testUser, after, PbPipelineUtil.ROLE_RENDER_PLAN));
	}

	/**
	 * The executor's fallback (P4-8): a page whose scene {@code sdPrompt} is blank but whose CHAPBOOK_PAGE
	 * composition carries a {@code promptOverride} passes the no-prompt gate, builds and persists its plan from the
	 * composition's text, and goes on toward the renderer. The state is a set (both copies carry P1) followed by a
	 * direct blank of the scene copy — what a board edit of the page prompt or the P4-10 migration leaves behind.
	 * Against a dead renderer the run is refused later, by the model resolver (503 "Renderer inventory
	 * unavailable", the P2-5 shape), so the node reads FAILED with THAT error, never the 409; the selected
	 * renderPlan artifact carries P1 verbatim as the location text and inside the compiled BASE prompt.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void executeChapBookPageFallsBackToCompositionPrompt() throws Exception {
		Fixture fx = createFixture("fallback");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, GENUINE_PROMPT));
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		BaseRecord comp = PbComposeUtil.findSceneComposition(testUser, scene, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
		assertNotNull("precondition: the set created the page composition", comp);
		String compOid = comp.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(GENUINE_PROMPT, PbComposeUtil.readComposition(testUser, compOid, fx.orgId).get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));

		/// Blank the pre-P4-8 copy directly, never through the prompt writer (that would clear both).
		BaseRecord blank = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_CB_SD_PROMPT);
		blank.set(OlioFieldNames.FIELD_CB_SD_PROMPT, null);
		assertNotNull(IOSystem.getActiveContext().getAccessPoint().update(testUser, blank));
		scene = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNull("precondition: the scene copy is blank", scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("precondition: the composition still carries the prompt", GENUINE_PROMPT,
			PbComposeUtil.readComposition(testUser, compOid, fx.orgId).get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));

		BaseRecord wf = workflow(book);
		BaseRecord node = ensurePageNode(book, wf, sceneOid);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		assertNull("precondition: no plan artifact yet", PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_RENDER_PLAN));
		CapturingSdu sdu = new CapturingSdu(DEAD_SERVER);
		long t0 = System.currentTimeMillis();
		PictureBookException thrown = null;
		try {
			PbNodeExecutor.executeNode(testUser, book, wf, node, sdu);
			fail("a dead renderer cannot complete the page");
		}
		catch(PictureBookException e) {
			thrown = e;
		}
		logger.info("[p48] executeNode(fallback) durationMs=" + (System.currentTimeMillis() - t0) + " -> " + thrown.getStatus() + " " + thrown.getMessage());
		assertFalse("the composition prompt satisfies the no-prompt gate, but the executor said: " + thrown.getMessage(),
			thrown.getMessage().contains("has no prompt"));
		assertEquals("the refusal is the model resolver's, after the plan was built (P2-5 shape): " + thrown.getMessage(), 503, thrown.getStatus());
		assertTrue(thrown.getMessage().contains("Renderer inventory unavailable"));
		assertTrue("the renderer was never reached", sdu.captured.isEmpty());

		BaseRecord after = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertEquals(PbNodeStatusEnumType.FAILED.name(), status(after, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = after.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertNotNull(lastError);
		assertFalse("lastError is the resolver's refusal, not the no-prompt one: " + lastError, lastError.contains("has no prompt"));
		BaseRecord planArtifact = PbArtifactUtil.findSelected(testUser, after, PbPipelineUtil.ROLE_RENDER_PLAN);
		assertNotNull("the plan was built and persisted before the resolver refused", planArtifact);
		RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(planArtifact);
		assertNotNull("the renderPlan artifact decodes", plan);
		assertNotNull("the plan has a location", plan.getLocation());
		assertEquals("the plan's location text is the composition prompt, verbatim", GENUINE_PROMPT, plan.getLocation().getDescription());
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		assertNotNull("a BASE prompt was compiled", base);
		assertNotNull(base.getPrompt());
		assertTrue("the compiled BASE prompt carries the composition text but was: " + base.getPrompt(), base.getPrompt().contains(GENUINE_PROMPT));
		assertFalse("the persisted plan never carries the server URL", plan.toJson().contains(DEAD_SERVER));
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static final class CapturingSdu extends SDUtil {
		final List<SWTxt2Img> captured = new ArrayList<>();

		CapturingSdu(String server) {
			super(SDAPIEnumType.SWARM, server);
		}

		@Override
		public SWImageResponse txt2img(SWTxt2Img req) {
			captured.add(req);
			return null;
		}
	}

	private static final class Fixture {
		String bookOid;
		long orgId;
		List<BaseRecord> scenes;
		List<String> sceneOids = new ArrayList<>();
	}

	/** A fresh ChapBook from Stephen's poem, created WITHOUT a chatConfig (pages carry the fallback prompt shape). */
	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		fx.orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookP48-" + tag + "-" + ts, "Poem P48 " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-p48-" + tag + "-" + ts,
			"ChapBook P48 " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", fx.bookOid);
		BaseRecord bookCheck = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertNotNull("Book must be readable", bookCheck);
		assertTrue("the fixture is a ChapBook", PbNodeExecutor.isChapBook(bookCheck));
		fx.scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertTrue("At least two pages must be created", fx.scenes.size() >= 2);
		for(BaseRecord s : fx.scenes) {
			fx.sceneOids.add(s.get(FieldNames.FIELD_OBJECT_ID));
		}
		logger.info("[p48] fixture " + tag + " book=" + fx.bookOid + " pages=" + fx.sceneOids.size());
		return fx;
	}

	/**
	 * An {@code olio.pb.scene} in the test user's own directory with NO {@code book} — {@code PbBookUtil.createScene}
	 * refuses that (400), so it is assembled the way that method does and created through {@code AccessPoint}
	 * as the test user. Readable through {@code readScene}, has no {@code sceneNode}, and no page composition
	 * can be created for it: the real shape of a partial prompt write.
	 */
	private String createBooklessScene(String tag) throws Exception {
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();
		String name = "bookless-p48-" + tag + "-" + ts;
		BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE);
		IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(testUser, scene, name,
			"~/Data/ChapBookP48-bookless-" + ts, orgId);
		scene.set(FieldNames.FIELD_NAME, name);
		scene.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, Integer.valueOf(0));
		scene.set(OlioFieldNames.FIELD_PB_TITLE, "Bookless " + tag);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, scene);
		assertNotNull("the bookless scene is created", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord back = PbBookUtil.readScene(testUser, oid, orgId);
		assertNotNull("the bookless scene reads back", back);
		assertEquals("precondition: no book", 0L, fkId(back, OlioFieldNames.FIELD_PB_BOOK));
		assertEquals("precondition: no page node", 0L, fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));
		logger.info("[p48] bookless scene " + tag + " " + oid);
		return oid;
	}

	private BaseRecord workflow(BaseRecord book) {
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(testUser, book, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	/** The page's LANDSCAPE node (created and linked through {@code sceneNode} when the page has none), re-read. */
	private BaseRecord ensurePageNode(BaseRecord book, BaseRecord wf, String sceneOid) throws Exception {
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneOid, orgId);
		assertNotNull(scene);
		long linked = fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE);
		if(linked > 0L) {
			BaseRecord existing = PbGraphUtil.readNodeById(testUser, linked, orgId);
			assertNotNull("linked page node", existing);
			return existing;
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(testUser, wf, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(testUser, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(slug), idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(testUser, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(testUser, patch));
		BaseRecord fresh = PbGraphUtil.readNode(testUser, node.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertNotNull("page node re-read", fresh);
		return fresh;
	}

	private void refused(String what, int status, String contains, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			logger.info("[p48] " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": status (" + e.getMessage() + ")", status, e.getStatus());
			assertNotNull(what + ": message", e.getMessage());
			assertTrue(what + ": message must contain '" + contains + "' but was: " + e.getMessage(), e.getMessage().contains(contains));
			return;
		}
		fail(what + ": expected PictureBookException " + status + " but nothing was thrown");
	}

	/** A detached CHAPBOOK-typed book record, only for asking {@link PbRenderPath} which switch applies. */
	private static BaseRecord chapBookStub() {
		try {
			BaseRecord stub = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] { OlioFieldNames.FIELD_PB_BOOK_TYPE });
			stub.setValue(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
			return stub;
		}
		catch(Exception e) {
			throw new AssertionError("Cannot build a chapbook stub: " + e.getMessage(), e);
		}
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		}
		catch(Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	private static int intOf(Object o) {
		assertTrue("a number but was " + o, o instanceof Number);
		return ((Number) o).intValue();
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}
}
