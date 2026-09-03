package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for ChapBook "issue #3": the render path must report a TRUTHFUL, distinct
 * "LLM unavailable" signal instead of silently degrading when the landscape-prompt LLM step cannot run.
 * <p>
 * These are live, integration tests (they hit the real DB and — on the hard path — a real, deliberately
 * unreachable network endpoint; on the healthy path the live LLM + SD). Nothing is mocked. They exercise
 * the three outcomes the fix distinguishes:
 * <ol>
 *   <li><b>HARD failure → degrade-render.</b> A scene whose stored prompt is the {@code "landscape, "}
 *       no-LLM fallback, rendered with a chatConfig pointing at an UNREACHABLE host, so the landscape LLM
 *       recovery genuinely fails at the network layer. The render must still produce an image on the
 *       stored prompt AND report {@code llmUnavailable == true} and {@code llmDegraded == true}.</li>
 *   <li><b>Healthy path → no false alarm.</b> A scene created + rendered with the live LLM must render
 *       with BOTH flags false — a soft/blank decline or a successful call must never raise the alarm.</li>
 *   <li><b>No-config path → determined in Objects7.</b> A GENUINE-prompt scene rendered with
 *       {@code chatConfig == null} still renders on the stored prompt and does NOT false-alarm
 *       ({@code llmUnavailable == false} — the PRESERVE invariant). A FALLBACK-only scene rendered with
 *       {@code chatConfig == null} truthfully reports {@code llmUnavailable == true}. This determination
 *       now lives ENTIRELY in Objects7 ({@code renderResolvedScene} folds {@code chatConfig == null} into
 *       its {@code llmUnavailable} signal); Service7 passes it straight through rather than computing it by
 *       arithmetic (architecture.md — no business logic in the transport layer).</li>
 * </ol>
 * Gated on {@code test.swarm.server} (all three need SD to render) and, where the healthy LLM path is
 * exercised, {@code test.llm.ollama.server}. Never uses the admin user.
 */
public class TestChapBookRenderSignal extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	/**
	 * REAL poem content — the same two-stanza corpus {@link TestChapBook} uses (verbatim from the UX
	 * e2e). {@code maxLinesPerPage=5} yields two scenes, so the bulk path has more than one scene to
	 * aggregate over.
	 */
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

	private BaseRecord testUser;

	@Before
	public void setUpChapBookSignal() {
		// BaseTest.setup() (@Before) runs first and calls OlioModelNames.use(). Reuse the same stable
		// non-admin test user the ChapBook suite uses.
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
	}

	/**
	 * HARD path (per-scene): the landscape LLM genuinely cannot run (unreachable host), the scene's
	 * stored prompt is the {@code "landscape, "} fallback shape, so the render DEGRADES onto the stored
	 * prompt and truthfully reports both {@code llmUnavailable} and {@code llmDegraded}.
	 * <p>
	 * The failure is a REAL network failure: the chatConfig's connection points at {@code http://127.0.0.1:1},
	 * which refuses immediately. No mock. Gated on {@code test.swarm.server} (the degrade-render needs SD).
	 */
	@Test
	public void testPerSceneHardLlmFailureDegradeRenders() {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping ChapBook hard-failure degrade render",
			swarmServer != null && !swarmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		// Create the ChapBook with NO chatConfig → each scene stores the "landscape, …" fallback shape,
		// i.e. an un-prompted scene (exactly the precondition the render-skip test relies on). This is the
		// natural state that forces landscape recovery at render time.
		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookSignalHard-" + ts, "Poem Signal Hard " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-sig-hard-" + ts,
			"ChapBook Signal Hard " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		BaseRecord bookCheck = reloadBook(book, orgId);
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", scenes.isEmpty());

		BaseRecord targetScene = scenes.get(0);
		String sceneObjectId = targetScene.get(FieldNames.FIELD_OBJECT_ID);
		String storedPrompt = targetScene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertNotNull("Scene must carry a stored sdPrompt", storedPrompt);
		assertTrue("This test requires a fallback-shaped ('landscape, ') stored prompt so recovery is "
			+ "invoked at render time, got: " + storedPrompt, storedPrompt.startsWith("landscape, "));

		// chatConfig pointing at an UNREACHABLE host: the landscape recovery call genuinely fails at connect.
		BaseRecord unreachable = OlioTestUtil.getUnreachableOllamaConfig(testUser,
			"chapbookUnreachableTestConfig", "http://127.0.0.1:1", "qwen3:8b", 5);
		assertNotNull("Unreachable chatConfig must be built", unreachable);

		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(
			testUser, sceneObjectId, "SWARM", swarmServer, unreachable, null);

		assertNotNull("renderChapBookScene must return a result", result);
		// The landscape LLM genuinely could not run → the truthful "unavailable" signal must be set,
		// regardless of whether SD then rendered.
		assertTrue("A HARD landscape-LLM failure MUST set llmUnavailable=true (got status=" + result.status
			+ ", llmUnavailable=" + result.llmUnavailable + ")", result.llmUnavailable);
		// With a non-blank stored prompt to degrade onto and a live SD server, the scene must RENDER on the
		// stored prompt and report llmDegraded=true (rendered WITHOUT a fresh LLM prompt).
		assertEquals("A hard LLM failure WITH a stored prompt and live SD must DEGRADE-RENDER, not skip",
			ChapBookUtil.SceneRenderStatus.RENDERED, result.status);
		assertTrue("A degrade-render (rendered on the stored prompt after a hard LLM failure) MUST set "
			+ "llmDegraded=true", result.llmDegraded);
		assertNotNull("A RENDERED result must carry an imageObjectId", result.imageObjectId);
		assertFalse("imageObjectId must be non-blank", result.imageObjectId.isBlank());

		// Prove the degrade-render persisted an image on the scene.
		BaseRecord sceneAfter = PbBookUtil.readScene(testUser, sceneObjectId, orgId);
		String persistedImageOid = sceneAfter.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		assertNotNull("Degrade-render must persist an imageObjectId", persistedImageOid);
		assertEquals("Persisted imageObjectId must equal the returned one", result.imageObjectId, persistedImageOid);
		logger.info("HARD degrade-render: scene {} rendered on stored prompt, llmUnavailable={}, llmDegraded={}, image={}",
			sceneObjectId, result.llmUnavailable, result.llmDegraded, persistedImageOid);
	}

	/**
	 * Healthy path (per-scene) must NOT false-alarm, and the no-config render must still be attempted.
	 * <ol>
	 *   <li>Create + render one scene with the LIVE LLM: the render must succeed with BOTH flags false —
	 *       a working landscape step is not "unavailable" and not "degraded".</li>
	 *   <li>Re-render the SAME scene with {@code chatConfig == null}: the render STILL proceeds on the
	 *       now-genuine stored prompt (a RENDERED, not a 503/skip), and at the Objects7 layer the
	 *       deliberate no-LLM path reports neither flag (the transport adds the wire signal itself).</li>
	 * </ol>
	 * Gated on {@code test.swarm.server} AND {@code test.llm.ollama.server}.
	 */
	@Test
	public void testPerSceneHealthyPathAndNoConfigDoNotFalseAlarm() {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping healthy-path ChapBook render",
			swarmServer != null && !swarmServer.isBlank());
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping healthy-path ChapBook render",
			llmServer != null && !llmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		BaseRecord liveConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookLlmTestConfig", testProperties);
		assertNotNull("Live chatConfig must be built", liveConfig);

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookSignalOk-" + ts, "Poem Signal Ok " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-sig-ok-" + ts,
			"ChapBook Signal Ok " + ts, poemOids, 5, liveConfig);
		assertNotNull("createChapBook must return a book", book);
		BaseRecord bookCheck = reloadBook(book, orgId);
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", scenes.isEmpty());
		String sceneObjectId = scenes.get(0).get(FieldNames.FIELD_OBJECT_ID);

		// 1. Healthy render with the live LLM: must succeed and NOT raise either flag.
		ChapBookUtil.SceneRenderResult healthy = ChapBookUtil.renderChapBookScene(
			testUser, sceneObjectId, "SWARM", swarmServer, liveConfig, null);
		assertNotNull("Healthy render must return a result", healthy);
		assertEquals("Healthy live-LLM render must RENDER (genuine stored or fresh-recovered prompt)",
			ChapBookUtil.SceneRenderStatus.RENDERED, healthy.status);
		assertFalse("A healthy render must NOT set llmUnavailable (no hard failure occurred)", healthy.llmUnavailable);
		assertFalse("A healthy render must NOT set llmDegraded (a usable prompt was resolved)", healthy.llmDegraded);
		logger.info("HEALTHY render: scene {} rendered, llmUnavailable=false, llmDegraded=false", sceneObjectId);

		// A successful RENDERED-with-config outcome guarantees a genuine prompt is now stored on the scene
		// (either it already was, or recovery persisted the fresh one). So the no-config re-render below
		// deterministically finds a genuine stored prompt and renders on it.
		BaseRecord sceneMid = PbBookUtil.readScene(testUser, sceneObjectId, orgId);
		String midPrompt = sceneMid.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertTrue("After a successful live render the scene must carry a GENUINE stored prompt, got: " + midPrompt,
			ChapBookUtil.isGenuineStoredPrompt(midPrompt));

		// 2. No-config re-render on a GENUINE stored prompt: render STILL proceeds and must NOT false-alarm.
		//    Under the corrected Objects7 semantics chatConfig == null only sets llmUnavailable when there is
		//    no genuine prompt to render on; here the prompt IS genuine, so the PRESERVE invariant holds.
		ChapBookUtil.SceneRenderResult noConfig = ChapBookUtil.renderChapBookScene(
			testUser, sceneObjectId, "SWARM", swarmServer, null, null);
		assertNotNull("No-config render must return a result", noConfig);
		assertEquals("A no-config render of a genuine-prompt scene must STILL render (attempt not blocked)",
			ChapBookUtil.SceneRenderStatus.RENDERED, noConfig.status);
		assertFalse("PRESERVE invariant: a GENUINE stored prompt rendered with chatConfig == null MUST NOT "
			+ "set llmUnavailable — the LLM step was never needed (a fallback-only scene WOULD report true)",
			noConfig.llmUnavailable);
		assertFalse("The no-config path must NOT set llmDegraded (a genuine stored prompt was used)", noConfig.llmDegraded);
		logger.info("NO-CONFIG render: scene {} still rendered on stored prompt, both flags false (Objects7 layer)", sceneObjectId);
	}

	/**
	 * Bulk path: {@link ChapBookUtil#renderChapBookSummary} must AGGREGATE the per-scene llmUnavailable
	 * signal. Every scene of a fallback-prompt book rendered with an unreachable chatConfig hard-fails its
	 * landscape step, degrade-renders on the stored prompt, and is counted in {@code llmUnavailable}.
	 * Gated on {@code test.swarm.server}.
	 */
	@Test
	public void testBulkRenderSummaryAggregatesLlmUnavailable() {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping bulk ChapBook render summary",
			swarmServer != null && !swarmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookSignalBulk-" + ts, "Poem Signal Bulk " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		// No chatConfig at create → fallback-shaped stored prompts on every scene.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-sig-bulk-" + ts,
			"ChapBook Signal Bulk " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord bookCheck = reloadBook(book, orgId);
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		int sceneCount = scenes.size();
		assertTrue("Expected at least 2 scenes from the two-stanza poem, got " + sceneCount, sceneCount >= 2);

		BaseRecord unreachable = OlioTestUtil.getUnreachableOllamaConfig(testUser,
			"chapbookUnreachableTestConfig", "http://127.0.0.1:1", "qwen3:8b", 5);
		assertNotNull("Unreachable chatConfig must be built", unreachable);

		ChapBookUtil.ChapBookRenderSummary summary = ChapBookUtil.renderChapBookSummary(
			testUser, bookObjectId, "SWARM", swarmServer, unreachable, null);
		assertNotNull("renderChapBookSummary must return a summary", summary);
		// Every scene's landscape LLM step hard-failed, so every scene must be counted as llmUnavailable,
		// and (with live SD + a fallback stored prompt to degrade onto) every scene must degrade-render.
		assertEquals("Every scene must be counted as llmUnavailable when the LLM host is unreachable",
			sceneCount, summary.llmUnavailable);
		assertEquals("Every scene must degrade-render on its stored prompt", sceneCount, summary.rendered);
		assertEquals("No scene should be skipped when a stored prompt exists to degrade onto", 0, summary.skipped);
		logger.info("BULK summary: rendered={}, skipped={}, llmUnavailable={} over {} scenes",
			summary.rendered, summary.skipped, summary.llmUnavailable, sceneCount);
	}

	/**
	 * The {@code chatConfig == null} determination now lives in Objects7 (per architecture.md — the
	 * transport layer must not compute the signal). This proves the CORRECTED semantics DIRECTLY at the
	 * Objects7 layer, with NO live LLM (the genuine prompt is set by a patch, not recovered):
	 * <ol>
	 *   <li><b>(a) Genuine stored prompt + {@code chatConfig == null} → NO alarm.</b> The scene renders on
	 *       its genuine stored prompt; the LLM step was never needed, so {@code llmUnavailable == false} and
	 *       {@code llmDegraded == false} — the PRESERVE invariant (a book made WITH an LLM but re-rendered
	 *       without a config must not false-alarm).</li>
	 *   <li><b>(b) No genuine prompt (fallback shape) + {@code chatConfig == null} → TRUTHFUL alarm.</b> The
	 *       LLM step could not run (no config) and there is no genuine prompt, so the render degrades onto
	 *       the stored fallback and reports {@code llmUnavailable == true} (and {@code llmDegraded == true}
	 *       for the successful degraded render). This is exactly the signal Service7 now passes straight
	 *       through instead of computing by arithmetic.</li>
	 * </ol>
	 * Gated on {@code test.swarm.server} ONLY — both cases render against live SD; NEITHER needs the LLM.
	 */
	@Test
	public void testPerSceneNoConfigDeterminedInObjects7() throws Exception {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping no-config Objects7 determination test",
			swarmServer != null && !swarmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookSignalNoCfg-" + ts, "Poem Signal NoCfg " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		// No chatConfig at create → every scene carries the "landscape, " fallback-shaped prompt.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-sig-nocfg-" + ts,
			"ChapBook Signal NoCfg " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		BaseRecord bookCheck = reloadBook(book, orgId);
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertTrue("Expected at least 2 scenes from the two-stanza poem, got " + scenes.size(), scenes.size() >= 2);

		// ── (a) Genuine stored prompt + chatConfig == null → NO alarm ──
		// Establish a GENUINE stored prompt WITHOUT the LLM by patching the scene (identity + name + the
		// changed prompt field, the explicit field-name idiom so validation accepts the patch), so
		// resolveScenePrompt returns it as-is and the LLM step is never needed.
		BaseRecord genuineScene = scenes.get(0);
		String genuineSceneOid = genuineScene.get(FieldNames.FIELD_OBJECT_ID);
		String genuinePrompt = "a serene alpine lake at dawn, oil painting, wide natural vista, soft diffuse light";
		patchScenePrompt(genuineScene, genuinePrompt);
		BaseRecord genuineReloaded = PbBookUtil.readScene(testUser, genuineSceneOid, orgId);
		assertTrue("Patched prompt must read back as a GENUINE stored prompt, got: "
			+ genuineReloaded.get(OlioFieldNames.FIELD_CB_SD_PROMPT),
			ChapBookUtil.isGenuineStoredPrompt(genuineReloaded.get(OlioFieldNames.FIELD_CB_SD_PROMPT)));

		ChapBookUtil.SceneRenderResult genuineResult = ChapBookUtil.renderChapBookScene(
			testUser, genuineSceneOid, "SWARM", swarmServer, null, null);
		assertNotNull("Genuine-prompt no-config render must return a result", genuineResult);
		assertEquals("A genuine-prompt scene must RENDER even with chatConfig == null",
			ChapBookUtil.SceneRenderStatus.RENDERED, genuineResult.status);
		assertFalse("PRESERVE invariant: a genuine stored prompt rendered with chatConfig == null MUST NOT "
			+ "set llmUnavailable (the LLM step was never needed)", genuineResult.llmUnavailable);
		assertFalse("A genuine-prompt render must NOT set llmDegraded", genuineResult.llmDegraded);
		logger.info("NO-CONFIG (a): genuine-prompt scene {} rendered, llmUnavailable=false, llmDegraded=false", genuineSceneOid);

		// ── (b) No genuine prompt (fallback shape) + chatConfig == null → TRUTHFUL alarm (degrade-render) ──
		BaseRecord fallbackScene = scenes.get(1);
		String fallbackSceneOid = fallbackScene.get(FieldNames.FIELD_OBJECT_ID);
		String fallbackPrompt = fallbackScene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertTrue("This case requires a fallback-shaped ('landscape, ') stored prompt, got: " + fallbackPrompt,
			fallbackPrompt != null && fallbackPrompt.startsWith("landscape, "));

		ChapBookUtil.SceneRenderResult fallbackResult = ChapBookUtil.renderChapBookScene(
			testUser, fallbackSceneOid, "SWARM", swarmServer, null, null);
		assertNotNull("Fallback no-config render must return a result", fallbackResult);
		assertTrue("A fallback-only scene rendered with chatConfig == null MUST report llmUnavailable=true "
			+ "(the LLM step could not run and no genuine prompt existed) — the signal Service7 now passes "
			+ "straight through", fallbackResult.llmUnavailable);
		assertEquals("With a stored prompt to degrade onto and live SD, the no-config render must DEGRADE-RENDER",
			ChapBookUtil.SceneRenderStatus.RENDERED, fallbackResult.status);
		assertTrue("A no-config degrade-render MUST set llmDegraded=true", fallbackResult.llmDegraded);
		assertNotNull("A RENDERED result must carry an imageObjectId", fallbackResult.imageObjectId);
		logger.info("NO-CONFIG (b): fallback scene {} degrade-rendered, llmUnavailable=true, llmDegraded=true, image={}",
			fallbackSceneOid, fallbackResult.imageObjectId);
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	/**
	 * Patch a scene's {@code cbSdPrompt} to a genuine value WITHOUT the LLM — identity + name + the changed
	 * field only (the explicit field-name {@code newInstance} idiom so validation doesn't reject the patch).
	 */
	private void patchScenePrompt(BaseRecord scene, String prompt) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_CB_SD_PROMPT});
		patch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, scene.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, prompt);
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(testUser, patch);
		assertNotNull("Scene prompt patch must succeed (non-null update result)", updated);
	}

	/** Re-load a freshly-created book by objectId with the fields {@code listScenes} needs. */
	private BaseRecord reloadBook(BaseRecord book, long orgId) {
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookObjectId);
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable by objectId", bookCheck);
		return bookCheck;
	}

	/** Create an {@code olio.cb.poem} at the given path (identity-only record returned). */
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
}
