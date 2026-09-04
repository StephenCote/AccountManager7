package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.junit.Test;

/**
 * Cross-poem landscape-prompt leak guard (pure mechanical proof — no DB, no LLM, no SD; runs in the
 * normal gate).
 *
 * <p><b>The defect.</b> In {@code ChapBookUtil.createChapBook} the {@code priorScenePrompts} accumulator
 * was declared OUTSIDE the per-poem loop and never reset between poems, and {@code renderChapBookSummary}
 * had the identical flaw across its flat scene list. Because {@link ChapBookUtil#assemblePriorContext}
 * threads the most recent prior scene prompts into every landscape-prompt LLM call for continuity, poem
 * 1's landscape imagery (e.g. an erupting volcano) bled into every LATER poem's landscape prompts —
 * a meadow poem got volcano imagery.
 *
 * <p><b>The fix.</b> {@code priorScenePrompts} is now scoped per poem in {@code createChapBook} (reset at
 * the top of each poem iteration), and {@code renderChapBookSummary} clears it whenever the scene's
 * source-poem title changes (scenes carry their source poem title in {@code FIELD_PB_TITLE}, so a title
 * change marks a new poem). Intra-poem continuity is preserved; cross-poem continuity is severed.
 *
 * <p><b>What this test proves</b>, directly against the public {@link ChapBookUtil#assemblePriorContext}
 * (the exact carrier both loops call), the reset-vs-leak contract:
 * <ol>
 *   <li>POSITIVE (the fix): a poem-2 context built with a RESET (empty) prior-prompt list carries poem
 *       2's own theme/mood/keywords and NONE of poem 1's distinctive imagery.</li>
 *   <li>NEGATIVE CONTROL (the bug): the SAME poem-2 inputs with a NON-reset prior-prompt list (still
 *       holding poem 1's prompts) DOES leak poem 1's imagery — proving {@code assemblePriorContext} is a
 *       genuine leak vector and the per-poem reset is load-bearing, not cosmetic.</li>
 *   <li>Intra-poem continuity is UNAFFECTED: within a single poem, scene 2's context (built from scene
 *       1's prompt) still carries scene 1's imagery, so the reset is per-poem, not per-scene.</li>
 * </ol>
 */
public class TestChapBookCrossPoemLandscapeGuard {

	// ── Poem 1: volcanic imagery. "volcano"/"molten lava" appear ONLY in poem-1 material. ──
	private static final String POEM1_THEME = "volcanic fury";
	private static final String POEM1_MOOD = "apocalyptic";
	private static final String POEM1_KEYWORDS = "lava, ash, ember";
	private static final String POEM1_SCENE1_PROMPT =
		"masterpiece, best quality, an erupting volcano spewing molten lava, ember-red sky, falling ash";
	private static final String POEM1_SCENE2_PROMPT =
		"masterpiece, best quality, rivers of molten lava down a volcano slope, glowing ember light, choking ash";

	// ── Poem 2: serene meadow imagery. Shares NO token with poem-1 material. ──
	private static final String POEM2_THEME = "spring meadow";
	private static final String POEM2_MOOD = "serene";
	private static final String POEM2_KEYWORDS = "wildflowers, dew, gentle breeze";

	/**
	 * Distinctive tokens that occur ONLY in poem 1's theme/mood/keywords/prompts and never in poem 2's
	 * inputs — so their presence in a poem-2 context is unambiguous cross-poem leakage.
	 */
	private static final String[] POEM1_ONLY_TOKENS =
		{ "volcano", "molten lava", "ember", "ash", "apocalyptic" };

	/** Build the prior-prompt list a poem accumulates as its scenes are generated. */
	private static List<String> poem1Accumulated() {
		return new ArrayList<>(Arrays.asList(POEM1_SCENE1_PROMPT, POEM1_SCENE2_PROMPT));
	}

	@Test
	public void resetPriorPromptsSeversCrossPoemLeak_theFix() {
		// FIX: poem 2 begins with a fresh (reset) prior-prompt list — exactly what createChapBook now
		// does per poem and renderChapBookSummary does on a title change.
		List<String> resetPrior = new ArrayList<>();
		String poem2Context =
			ChapBookUtil.assemblePriorContext(POEM2_THEME, POEM2_KEYWORDS, POEM2_MOOD, resetPrior);

		// Poem 2's OWN analysis must still be carried through.
		assertFalse("poem-2 context must not be the 'none' sentinel", "none".equals(poem2Context));
		assertTrue("poem-2 context must carry poem 2's theme", poem2Context.contains(POEM2_THEME));
		assertTrue("poem-2 context must carry poem 2's mood", poem2Context.contains(POEM2_MOOD));
		assertTrue("poem-2 context must carry poem 2's keywords", poem2Context.contains(POEM2_KEYWORDS));

		// And NONE of poem 1's imagery may appear — the whole point of the fix.
		for (String token : POEM1_ONLY_TOKENS) {
			assertFalse("RESET poem-2 context must NOT leak poem-1 imagery token '" + token
					+ "' — got: " + poem2Context,
				poem2Context.toLowerCase().contains(token.toLowerCase()));
		}
	}

	@Test
	public void nonResetPriorPromptsLeakPoem1Imagery_theBugNegativeControl() {
		// NEGATIVE CONTROL: the pre-fix behaviour — poem 2 reuses poem 1's accumulated prior prompts.
		// Same poem-2 inputs as the fix test; only the prior-prompt list differs. This proves
		// assemblePriorContext genuinely threads prior prompts forward, so a non-reset list DOES leak.
		String leakedContext =
			ChapBookUtil.assemblePriorContext(POEM2_THEME, POEM2_KEYWORDS, POEM2_MOOD, poem1Accumulated());

		assertTrue("NON-reset poem-2 context MUST leak poem-1's 'volcano' imagery (the reported bug) — "
				+ "proving the per-poem reset is load-bearing; got: " + leakedContext,
			leakedContext.toLowerCase().contains("volcano"));
		assertTrue("NON-reset poem-2 context MUST also leak poem-1's 'molten lava' imagery; got: "
				+ leakedContext,
			leakedContext.toLowerCase().contains("molten lava"));

		// Sanity: the two contexts genuinely differ — the reset changes the outcome.
		String resetContext =
			ChapBookUtil.assemblePriorContext(POEM2_THEME, POEM2_KEYWORDS, POEM2_MOOD, new ArrayList<>());
		assertFalse("reset vs non-reset poem-2 contexts must differ (the fix must change behaviour)",
			resetContext.equals(leakedContext));
	}

	@Test
	public void intraPoemContinuityPreserved_resetIsPerPoemNotPerScene() {
		// Within a SINGLE poem, scene 2's context (built from scene 1's prompt) must still carry scene 1's
		// imagery — the reset must be per-poem, not per-scene, or the fix would destroy the continuity the
		// feature is designed to provide.
		List<String> withinPoem1 = new ArrayList<>(Arrays.asList(POEM1_SCENE1_PROMPT));
		String poem1Scene2Context =
			ChapBookUtil.assemblePriorContext(POEM1_THEME, POEM1_KEYWORDS, POEM1_MOOD, withinPoem1);

		assertTrue("intra-poem context must carry the prior scene's 'volcano' imagery (continuity within "
				+ "a poem is preserved); got: " + poem1Scene2Context,
			poem1Scene2Context.toLowerCase().contains("volcano"));
		assertTrue("intra-poem context must carry poem 1's own theme",
			poem1Scene2Context.contains(POEM1_THEME));
	}
}
