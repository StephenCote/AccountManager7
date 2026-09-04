package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

/**
 * Issue-6 regression guard (pure mechanical proof — no DB, no LLM, no SD, runs in the normal gate).
 *
 * <p><b>The defect.</b> The server refused every landscape-image prompt with:
 * <pre>Refusing to call LLM for prompt 'pictureBook.landscape-prompt' — template has unsubstituted
 * placeholder(s) (first: '{style}') ... Vars supplied: [setting, mood, time]</pre>
 * Two correct-in-isolation decisions collided. The landscape prompt template's user section carried a
 * {@code "STYLE: {style}"} line, while {@code PictureBookUtil.resolveLandscapePrompt} deliberately
 * stopped sending a {@code style} var to the LLM (a real style value made the model emit its own
 * competing "cinematic photograph" style on top of the code-owned style from
 * {@code appendConfigStyleOnce(sdConfig)}). With no {@code style} var supplied, {@code {style}} survived
 * substitution, and {@code callLlmInternal}'s {@code UNSUBSTITUTED_PLACEHOLDER} guard HARD-refused the
 * call — so the landscape prompt silently fell back to the raw setting text on every scene.
 *
 * <p><b>The fix.</b> {@code resolveLandscapePrompt} now supplies {@code style=""} (an EMPTY value: no
 * style signal reaches the LLM, so no competing style, but the {@code {style}} placeholder resolves).
 * This is robust whether the template resolves from a DB/library {@code olio.llm.promptTemplate} record
 * (which the runtime error proves was the source — the flat classpath fallback never carried
 * {@code {style}}) or the classpath fallback. Separately, the {@code {style}} line was removed from the
 * canonical seed so future seeds are clean.
 *
 * <p>This test reproduces the guard math against the REAL guard {@code Pattern} (grabbed reflectively,
 * so it binds to the production constant rather than a hand-copied duplicate) and the REAL shipped
 * resources. A live-LLM end-to-end proof (a landscape-prompt promptTemplate carrying {@code {style}}
 * driven through the real production call, gated on PICTUREBOOK_E2E) is a separate integration test.
 */
public class TestPictureBookLandscapePromptGuard {

	/**
	 * The EXACT placeholder guard {@code callLlmInternal} uses
	 * ({@code PictureBookUtil.UNSUBSTITUTED_PLACEHOLDER}), grabbed reflectively so this test tracks the
	 * production constant instead of duplicating {@code \{[a-zA-Z][a-zA-Z0-9_]*\}} by hand.
	 */
	private static Pattern guardPattern() throws Exception {
		Field f = PictureBookUtil.class.getDeclaredField("UNSUBSTITUTED_PLACEHOLDER");
		f.setAccessible(true);
		return (Pattern) f.get(null);
	}

	/** Mirrors callLlmInternal:2123-2129 exactly: only NON-null var values are substituted. */
	private static String applyVars(String tpl, Map<String, String> vars) {
		String out = tpl;
		for (Map.Entry<String, String> e : vars.entrySet()) {
			if (e.getValue() != null) {
				out = out.replace("{" + e.getKey() + "}", e.getValue());
			}
		}
		return out;
	}

	/**
	 * The landscape template's user section AS IT WAS before the fix (a {@code STYLE: {style}} line) —
	 * i.e. the broken DB/library promptTemplate state the runtime error came from. Kept inline as an
	 * explicit negative-control reproduction; the current shipped seed no longer has this line (see
	 * {@link #seedTemplateNoLongerCarriesStylePlaceholder()}).
	 */
	private static final String PRE_FIX_TEMPLATE_USER_WITH_STYLE =
		"Create a Stable Diffusion landscape/environment prompt for this scene:\n"
		+ "SETTING: {setting}\n"
		+ "MOOD: {mood}\n"
		+ "TIME: {time}\n"
		+ "STYLE: {style}\n"
		+ "\n"
		+ "Return only the SD prompt text.";

	@Test
	public void preFixVarSetLeavesStylePlaceholder_theIssue6Defect() throws Exception {
		// NEGATIVE CONTROL — this IS the bug. resolveLandscapePrompt supplied {setting, mood, time}
		// only, so {style} survived and the guard hard-refused the call. Reproduces the exact reported
		// "Vars supplied: [setting, mood, time] ... first: '{style}'".
		Map<String, String> preFix = new LinkedHashMap<>();
		preFix.put("setting", "a derelict orbital station, rusted bulkheads");
		preFix.put("mood", "eerie, abandoned");
		preFix.put("time", ""); // resolveLandscapePrompt has always passed time=""
		String substituted = applyVars(PRE_FIX_TEMPLATE_USER_WITH_STYLE, preFix);
		Matcher m = guardPattern().matcher(substituted);
		assertTrue("Pre-fix var set {setting, mood, time} must leave an unsubstituted placeholder — the "
			+ "Issue-6 hard-fail the guard raises", m.find());
		assertEquals("the surviving placeholder must be {style}", "{style}", m.group());
	}

	@Test
	public void postFixVarSetSatisfiesGuard_evenAgainstAStaleTemplateThatStillHasStyle() throws Exception {
		// THE FIX — resolveLandscapePrompt now also supplies style="" (empty: no style signal reaches
		// the LLM, but {style} resolves). Proven against the STALE template that still carries
		// STYLE:{style} (an already-seeded library record), which is exactly why the var-level fix is
		// required and a template-only edit would not have been enough.
		Map<String, String> postFix = new LinkedHashMap<>();
		postFix.put("setting", "a derelict orbital station, rusted bulkheads");
		postFix.put("mood", "eerie, abandoned");
		postFix.put("time", "");
		postFix.put("style", ""); // <-- the fix
		String substituted = applyVars(PRE_FIX_TEMPLATE_USER_WITH_STYLE, postFix);
		assertFalse("Post-fix var set (adds style=\"\") must leave NO unsubstituted placeholder — the "
			+ "guard no longer hard-refuses the landscape-prompt call",
			guardPattern().matcher(substituted).find());
	}

	@Test
	public void seedTemplateNoLongerCarriesStylePlaceholder() {
		// BELT (the code style="" is the suspenders): the canonical seed the library re-seeds from —
		// olio/llm/templates/promptTemplate.pictureBook.landscape-prompt.json — no longer contains
		// {style}, so future seeds are clean. Loaded the same way ChatUtil.loadPromptTemplateTemplate
		// loads it.
		String seed = ResourceUtil.getInstance().getResource(
			"olio/llm/templates/promptTemplate.pictureBook.landscape-prompt.json");
		assertNotNull("landscape-prompt seed template resource must load", seed);
		assertFalse("seed template must no longer contain the {style} placeholder (removed in the fix)",
			seed.contains("{style}"));
	}

	@Test
	public void classpathFallbackPromptSatisfiesGuardWithProductionVars() throws Exception {
		// The flat classpath fallback (prompts/pictureBook.landscape-prompt.json) is what
		// callLlmInternal uses when NO DB template resolves. Prove the production var set fully
		// satisfies the guard against the ACTUAL shipped fallback too (it never carried {style}, and
		// the extra style="" is harmless).
		String userTpl = PromptResourceUtil.getString("pictureBook.landscape-prompt", "user");
		assertNotNull("classpath fallback user prompt must load", userTpl);
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("setting", "a derelict orbital station");
		vars.put("mood", "eerie");
		vars.put("time", "");
		vars.put("style", "");
		assertFalse("production var set must fully satisfy the shipped classpath fallback template",
			guardPattern().matcher(applyVars(userTpl, vars)).find());
	}
}
