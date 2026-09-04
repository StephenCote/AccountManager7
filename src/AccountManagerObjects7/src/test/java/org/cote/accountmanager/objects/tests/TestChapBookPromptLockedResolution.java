package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
 * Proves the {@code promptLocked} correctness fix for ChapBook landscape prompts.
 * <p>
 * <b>The bug:</b> a scene's stored landscape prompt ({@code olio.pb.scene.sdPrompt},
 * {@link OlioFieldNames#FIELD_CB_SD_PROMPT}) had its provenance inferred from TEXT SHAPE — anything
 * starting {@code "landscape, "} was treated as the create-time no-LLM auto fallback and REGENERATED
 * on render. So a deliberate human edit that happened to start {@code "landscape, "} was misclassified
 * and silently reverted at render time.
 * <p>
 * <b>The fix:</b> an explicit boolean {@link OlioFieldNames#FIELD_PB_PROMPT_LOCKED promptLocked} on the
 * scene. {@link ChapBookUtil#setSceneLandscapePrompt} sets it true on a real edit / false on a clear.
 * {@link ChapBookUtil#resolveScenePrompt} (the resolution seam that both the bulk and per-scene render
 * paths funnel through) now has a first branch: when {@code promptLocked && NarrativeUtil.isMeaningful(stored)}
 * it returns the stored prompt VERBATIM and NEVER invokes the LLM recovery supplier — regardless of shape.
 * <p>
 * <b>Test 1 (seam, unconditional, pure — no LLM/SD):</b> drives {@link ChapBookUtil#resolveScenePrompt}
 * directly with a spy recovery {@link Supplier} whose invocation is counted, proving:
 * <ol>
 *   <li>(a) {@code promptLocked=true} + a {@code "landscape, "}-shaped stored prompt → returned VERBATIM,
 *       recovery NEVER invoked (this is the exact bug — pre-fix this shape regenerated);</li>
 *   <li>(b) {@code promptLocked=false} + the same {@code "landscape, "}-shaped prompt (the genuine no-LLM
 *       fallback) → recovery IS invoked (the fix did not over-lock the fallback);</li>
 *   <li>(c) {@code promptLocked=false} + a genuine non-fallback LLM prompt → returned VERBATIM, recovery
 *       NOT invoked (the existing {@code isGenuineStoredPrompt} branch still works).</li>
 * </ol>
 * <b>Test 2 (live bulk-render end-to-end, gated on SD+LLM):</b> creates a one-scene ChapBook, applies a
 * deliberately {@code "landscape, "}-shaped human edit (setting {@code promptLocked=true}), then runs the
 * ACTUAL whole-book bulk render entry point {@link ChapBookUtil#renderChapBookSummary} with a live,
 * regeneration-capable {@code chatConfig}. It asserts the scene got an image AND its stored
 * {@code sdPrompt} re-reads (fresh, cache:false) EXACTLY equal to the edit — proving the bulk path honored
 * the locked edit verbatim and never regenerated it.
 * <p>
 * A non-admin {@code getCreateUser} user is used throughout; the admin user only provisions it. Nothing is
 * mocked. Sibling of {@code TestChapBookSceneLandscapePrompt} (which covers the per-scene 7-arg override) —
 * that class is not modified.
 */
public class TestChapBookPromptLockedResolution extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	/** A single short (2-line) stanza → exactly ONE scene → exactly ONE SDXL render, to bound Test 2. */
	private static final String SINGLE_STANZA =
		"A lone lighthouse keeps its watch,\n" +
		"Above the cold and churning sea.";

	/** The exact bug shape: a human edit that happens to start with the fallback discriminator prefix. */
	private static final String LANDSCAPE_SHAPED_EDIT = "landscape, a lone lighthouse on a stormy cliff";

	private BaseRecord testUser;
	private long orgId;

	@Before
	public void setUpPromptLocked() {
		// BaseTest.setup() (@Before) runs first and calls OlioModelNames.use(). Reuse the ChapBook suite's
		// stable NON-ADMIN test user (admin only provisions it via getCreateUser).
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookPromptLockedUser", ctx.getOrganizationId());
		assertNotNull("chapbookPromptLockedUser must be created", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/**
	 * SEAM TEST (pure, no LLM/SD; runs unconditionally). The load-bearing proof of the bulk-render fix:
	 * exercises {@link ChapBookUtil#resolveScenePrompt} directly with a spy recovery supplier whose
	 * invocation count is asserted, covering cases (a), (b), (c) above.
	 */
	@Test
	public void testResolveScenePromptHonorsLockVerbatimWithoutRecovery() throws Exception {
		// ── (a) promptLocked=true + "landscape, "-shaped stored → VERBATIM, recovery NEVER invoked ──
		//    THE EXACT BUG: pre-fix this shape was misclassified as the no-LLM fallback and REGENERATED.
		BaseRecord lockedScene = buildScene(LANDSCAPE_SHAPED_EDIT, true);
		AtomicInteger callsA = new AtomicInteger(0);
		String outA = ChapBookUtil.resolveScenePrompt(lockedScene, "any non-blank stanza text",
			spyRecovery(callsA, "RECOVERED-A-should-never-be-used"));
		assertEquals("A locked human edit must be returned VERBATIM regardless of its 'landscape, ' shape",
			LANDSCAPE_SHAPED_EDIT, outA);
		assertEquals("recovery supplier must NEVER be invoked when promptLocked && stored is meaningful "
			+ "(this is the bug the fix removes)", 0, callsA.get());

		// ── (b) promptLocked=false + same "landscape, "-shaped stored (genuine no-LLM fallback) → recovery IS invoked ──
		BaseRecord unlockedFallbackScene = buildScene(LANDSCAPE_SHAPED_EDIT, false);
		AtomicInteger callsB = new AtomicInteger(0);
		String outB = ChapBookUtil.resolveScenePrompt(unlockedFallbackScene, "any non-blank stanza text",
			spyRecovery(callsB, "RECOVERED-B-fresh-llm-prompt"));
		assertEquals("An UNLOCKED fallback-shaped prompt must be regenerated via recovery",
			"RECOVERED-B-fresh-llm-prompt", outB);
		assertEquals("recovery MUST be invoked exactly once for an unlocked fallback-shaped prompt "
			+ "(the fix must NOT over-lock the auto fallback)", 1, callsB.get());

		// ── (c) promptLocked=false + genuine non-fallback LLM prompt → VERBATIM, recovery NOT invoked ──
		String genuine = "A lone lighthouse stands against a churning slate-gray sea under storm clouds, "
			+ "oil painting, dramatic light";
		BaseRecord unlockedGenuineScene = buildScene(genuine, false);
		AtomicInteger callsC = new AtomicInteger(0);
		String outC = ChapBookUtil.resolveScenePrompt(unlockedGenuineScene, "any non-blank stanza text",
			spyRecovery(callsC, "RECOVERED-C-should-never-be-used"));
		assertEquals("An unlocked GENUINE stored prompt must be returned VERBATIM (isGenuineStoredPrompt branch)",
			genuine, outC);
		assertEquals("recovery must NOT be invoked when the unlocked stored prompt is genuine", 0, callsC.get());

		logger.info("resolveScenePrompt seam: (a) locked-landscape verbatim/no-recovery, "
			+ "(b) unlocked-landscape recovered, (c) unlocked-genuine verbatim/no-recovery — all verified");
	}

	/**
	 * LIVE BULK-RENDER END-TO-END. Gated on {@code test.swarm.server} (SD at 192.168.1.39) and
	 * {@code test.llm.ollama.server} (LLM at 192.168.1.42), both reachable from the HOST this test runs on.
	 * If either is genuinely unconfigured the test SKIPS via {@link org.junit.Assume} — it never fakes a pass.
	 * <p>
	 * A live, regeneration-CAPABLE {@code chatConfig} is supplied so that IF the fix were absent the
	 * {@code "landscape, "}-shaped edit WOULD be regenerated by the bulk path. The exact-match assertion on
	 * the re-read stored prompt would then fail, so a pass proves the bulk render honored the locked edit
	 * verbatim. Long: a real SDXL render is ~13 min for the single scene.
	 */
	@Test
	public void testBulkRenderHonorsLockedEditVerbatim() {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping ChapBook bulk-render lock test",
			swarmServer != null && !swarmServer.isBlank());
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping ChapBook bulk-render lock test",
			llmServer != null && !llmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long ts = System.currentTimeMillis();

		// A live, regeneration-CAPABLE chatConfig. Deliberately supplied: if the locked edit were
		// misclassified as the no-LLM fallback, recovery WOULD run and overwrite the stored prompt.
		BaseRecord liveConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookBulkLockLlmConfig", testProperties);
		assertNotNull("Live chatConfig must be built", liveConfig);

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookBulkLock-" + ts,
			"Poem Bulk Lock " + ts, SINGLE_STANZA);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		// No chatConfig at create → the single scene carries the "landscape, "-shaped no-LLM fallback with
		// promptLocked=false, so the human edit below is genuinely competing against the fallback shape.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-bulklock-" + ts,
			"ChapBook Bulk Lock " + ts, poemOids, 8, null);
		assertNotNull("createChapBook must return a book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord bookCheck = reloadBook(book);
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", scenes.isEmpty());
		assertEquals("Test expects EXACTLY ONE scene to bound the SDXL render time", 1, scenes.size());
		String sceneOid = scenes.get(0).get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Scene must carry an objectId", sceneOid);

		// Apply the deliberately "landscape, "-shaped HUMAN edit — the exact bug shape — and confirm the
		// explicit lock reads back true.
		assertTrue("setSceneLandscapePrompt must return true on a successful edit",
			ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, LANDSCAPE_SHAPED_EDIT));
		BaseRecord afterEdit = PbBookUtil.readScene(testUser, sceneOid, orgId);
		assertNotNull("Scene must be re-readable after the edit", afterEdit);
		Boolean lockedBefore = afterEdit.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		assertTrue("promptLocked must read back TRUE after a human edit",
			lockedBefore != null && lockedBefore.booleanValue());
		assertEquals("stored sdPrompt must read back as the edit BEFORE render",
			LANDSCAPE_SHAPED_EDIT, (String) afterEdit.get(OlioFieldNames.FIELD_CB_SD_PROMPT));

		// ── The ACTUAL whole-book BULK render entry point (NOT the per-scene 7-arg override path). ──
		ChapBookUtil.ChapBookRenderSummary summary =
			ChapBookUtil.renderChapBookSummary(testUser, bookOid, "SWARM", swarmServer, liveConfig, null);
		assertNotNull("renderChapBookSummary must return a summary", summary);
		assertEquals("The single scene must have RENDERED in the bulk pass", 1, summary.rendered);

		// Fresh (cache:false) re-read: an image was produced AND the stored prompt is UNCHANGED.
		BaseRecord rendered = PbBookUtil.readScene(testUser, sceneOid, orgId);
		assertNotNull("Scene must be re-readable after the bulk render", rendered);
		String imageOid = rendered.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		assertNotNull("Bulk render must persist a non-null imageObjectId on the scene", imageOid);
		assertFalse("imageObjectId must be non-blank", imageOid.isBlank());

		String storedAfter = rendered.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		logger.info("BULK-LOCK-PROOF: scene {} RENDERED image={}; stored sdPrompt after bulk render='{}'",
			sceneOid, imageOid, storedAfter);
		assertEquals("The bulk render MUST honor the promptLocked human edit VERBATIM and NOT regenerate it "
			+ "despite its 'landscape, ' shape — a live, regeneration-capable chatConfig was supplied and the "
			+ "edit still won", LANDSCAPE_SHAPED_EDIT, storedAfter);
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	/** A spy recovery supplier: counts invocations in {@code counter} and returns {@code recovered}. */
	private static Supplier<String> spyRecovery(AtomicInteger counter, String recovered) {
		return () -> {
			counter.incrementAndGet();
			return recovered;
		};
	}

	/**
	 * Build a pure in-memory {@code olio.pb.scene} carrying only the two fields
	 * {@link ChapBookUtil#resolveScenePrompt} reads: the stored landscape prompt and the promptLocked flag.
	 */
	private static BaseRecord buildScene(String storedPrompt, boolean locked) throws Exception {
		BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[] { OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_PROMPT_LOCKED });
		scene.set(OlioFieldNames.FIELD_CB_SD_PROMPT, storedPrompt);
		scene.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, locked);
		return scene;
	}

	/** Re-load a freshly-created book by objectId with the fields {@code listScenes} needs. */
	private BaseRecord reloadBook(BaseRecord book) {
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
