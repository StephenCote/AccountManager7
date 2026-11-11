package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
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
 * (The old-path live bulk-render case went with the {@code chapbook.renderPath} key at Phase 10 P10-1; on the
 * V3 page job the locked edit reaching the render path verbatim is proven by {@code TestChapBookRerenderEcho}.)
 * <p>
 * A non-admin {@code getCreateUser} user is used throughout; the admin user only provisions it. Nothing is
 * mocked. Sibling of {@code TestChapBookSceneLandscapePrompt} (the persistence contract of the edit).
 */
public class TestChapBookPromptLockedResolution extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	/** The exact bug shape: a human edit that happens to start with the fallback discriminator prefix. */
	private static final String LANDSCAPE_SHAPED_EDIT = "landscape, a lone lighthouse on a stormy cliff";

	private BaseRecord testUser;

	@Before
	public void setUpPromptLocked() {
		// BaseTest.setup() (@Before) runs first and calls OlioModelNames.use(). Reuse the ChapBook suite's
		// stable NON-ADMIN test user (admin only provisions it via getCreateUser).
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookPromptLockedUser", ctx.getOrganizationId());
		assertNotNull("chapbookPromptLockedUser must be created", testUser);
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
}
