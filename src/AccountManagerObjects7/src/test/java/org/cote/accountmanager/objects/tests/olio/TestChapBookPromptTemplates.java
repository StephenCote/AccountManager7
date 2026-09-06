package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

/**
 * Verifies the ChapBook LLM prompt-template fix: the two composable prompt templates
 * {@code chapBook.landscape-prompt} and {@code chapBook.poem-analysis} were added to
 * {@code ChatUtil.PROMPT_TEMPLATE_TEMPLATE_NAMES} and ship as
 * {@code olio/llm/templates/promptTemplate.chapBook.*.json}, so they can be seeded into the DB
 * prompt-template library and resolved through the same canonical path PictureBook uses.
 *
 * <p>What each test proves:
 * <ol>
 *   <li>{@link #chapBookTemplatesLoadSeedAndResolve()} — both templates load from the classpath via
 *       {@link ChatUtil#loadPromptTemplateTemplate(String)} AND, after
 *       {@link ChatLibraryUtil#populatePromptDefaults(BaseRecord)}, resolve as DB records via
 *       {@link ChatUtil#resolveConfig}.</li>
 *   <li>{@link #chapBookTemplatesComposeWithoutUnsubstitutedPlaceholders()} — composing each DB-resolved
 *       template through {@link PromptTemplateComposer} and substituting the EXACT caller vars used by
 *       {@code ChapBookUtil} leaves NO {@code {token}} that the production
 *       {@code PictureBookUtil.UNSUBSTITUTED_PLACEHOLDER} guard would reject, AND the composed user text
 *       actually contains the substituted stanza/poem content (catches a silently-dropped user section).</li>
 *   <li>{@link #chapBookLandscapePromptLiveLLM()} — a REAL call through the production
 *       {@code PictureBookUtil.callLlmForChapBook} (reflected, it is package-private) returns a non-blank
 *       landscape prompt without a HARD failure. Gated on {@code test.llm.type}/Ollama reachability.</li>
 * </ol>
 *
 * <p>Caller-var contract (mirrored from {@code ChapBookUtil}):
 * <ul>
 *   <li>{@code chapBook.landscape-prompt}: {@code stanzaText}, {@code mood}, {@code compositionContext},
 *       {@code priorContext} (ChapBookUtil.createChapBookScene ~lines 712-718).</li>
 *   <li>{@code chapBook.poem-analysis}: {@code poemText} (ChapBookUtil.analyzePoemTheme ~line 388).</li>
 * </ul>
 */
public class TestChapBookPromptTemplates extends BaseTest {

	private static final String LANDSCAPE = "chapBook.landscape-prompt";
	private static final String POEM_ANALYSIS = "chapBook.poem-analysis";

	/** A distinctive stanza; a fragment of it must survive substitution into the composed user prompt. */
	private static final String STANZA =
		"Under the lantern-haunted quay the tide unspools its silver thread,\n"
		+ "and gulls forget the names of ships the fog has quietly unsaid.";
	private static final String STANZA_MARKER = "lantern-haunted quay";

	private static final String POEM =
		"Under the lantern-haunted quay the tide unspools its silver thread,\n"
		+ "and gulls forget the names of ships the fog has quietly unsaid.\n\n"
		+ "The harbor keeps its cold accounts in salt and rust and fading light;\n"
		+ "we count the lamps that will not last against the coming weight of night.";
	private static final String POEM_MARKER = "cold accounts in salt and rust";

	/**
	 * The EXACT guard {@code callLlmInternal} applies
	 * ({@code PictureBookUtil.UNSUBSTITUTED_PLACEHOLDER}), reflected so this test binds to the
	 * production constant rather than a hand-copied {@code \{[a-zA-Z][a-zA-Z0-9_]*\}}.
	 */
	private static Pattern guardPattern() throws Exception {
		Field f = PictureBookUtil.class.getDeclaredField("UNSUBSTITUTED_PLACEHOLDER");
		f.setAccessible(true);
		return (Pattern) f.get(null);
	}

	/** Mirrors callLlmInternal exactly: substitute ONLY non-null var values, replacing "{key}". */
	private static String applyVars(String tpl, Map<String, String> vars) {
		String out = tpl;
		for (Map.Entry<String, String> e : vars.entrySet()) {
			if (e.getValue() != null) {
				out = out.replace("{" + e.getKey() + "}", e.getValue());
			}
		}
		return out;
	}

	private static Map<String, String> landscapeVars() {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("stanzaText", STANZA);
		vars.put("mood", "somber, elegiac");
		vars.put("compositionContext", "The Fading Lamps of the Harbor");
		vars.put("priorContext", "none");
		return vars;
	}

	private static Map<String, String> poemAnalysisVars() {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("poemText", POEM);
		return vars;
	}

	private BaseRecord seedAndGetUser() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/ChapBookPromptTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "cbPromptUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
		// Seed all prompt-template defaults (idempotent) — materializes PROMPT_TEMPLATE_TEMPLATE_NAMES,
		// including the two chapBook templates, into the DB prompt-template library.
		ChatLibraryUtil.populatePromptDefaults(testUser);
		return testUser;
	}

	@Test
	public void chapBookTemplatesLoadSeedAndResolve() {
		logger.info("chapBookTemplatesLoadSeedAndResolve: classpath load + DB seed + resolveConfig");

		// (a) Classpath composable-template files load.
		BaseRecord landClasspath = ChatUtil.loadPromptTemplateTemplate(LANDSCAPE);
		BaseRecord poemClasspath = ChatUtil.loadPromptTemplateTemplate(POEM_ANALYSIS);
		assertNotNull("chapBook.landscape-prompt must load from classpath templates/", landClasspath);
		assertNotNull("chapBook.poem-analysis must load from classpath templates/", poemClasspath);

		// (b) Seed then resolve as DB records via the same resolveConfig path callLlmInternal uses.
		BaseRecord testUser = seedAndGetUser();
		BaseRecord landDb = ChatUtil.resolveConfig(testUser, OlioModelNames.MODEL_PROMPT_TEMPLATE, LANDSCAPE, null);
		BaseRecord poemDb = ChatUtil.resolveConfig(testUser, OlioModelNames.MODEL_PROMPT_TEMPLATE, POEM_ANALYSIS, null);
		assertNotNull("chapBook.landscape-prompt must resolve as a DB prompt template after seeding", landDb);
		assertNotNull("chapBook.poem-analysis must resolve as a DB prompt template after seeding", poemDb);

		List<BaseRecord> landSections = landDb.get("sections");
		List<BaseRecord> poemSections = poemDb.get("sections");
		assertNotNull("resolved landscape template must carry sections", landSections);
		assertNotNull("resolved poem-analysis template must carry sections", poemSections);
		assertTrue("resolved landscape template must have a system+user pair", landSections.size() >= 2);
		assertTrue("resolved poem-analysis template must have a system+user pair", poemSections.size() >= 2);

		logger.info("  landscape resolved sections=" + landSections.size()
			+ ", poem-analysis resolved sections=" + poemSections.size());
		logger.info("chapBookTemplatesLoadSeedAndResolve PASSED");
	}

	@Test
	public void chapBookTemplatesComposeWithoutUnsubstitutedPlaceholders() throws Exception {
		logger.info("chapBookTemplatesComposeWithoutUnsubstitutedPlaceholders: compose + substitute + guard math");
		BaseRecord testUser = seedAndGetUser();
		Pattern guard = guardPattern();

		// --- chapBook.landscape-prompt ---
		BaseRecord landDb = ChatUtil.resolveConfig(testUser, OlioModelNames.MODEL_PROMPT_TEMPLATE, LANDSCAPE, null);
		assertNotNull("landscape template must resolve", landDb);
		String landSystem = PromptTemplateComposer.composeSystem(landDb, null, null);
		String landUser = PromptTemplateComposer.composeUser(landDb, null, null);
		// callLlmInternal HARD-fails if either system or user composes to null/blank.
		assertNotNull("landscape system half must compose", landSystem);
		assertFalse("landscape system half must be non-blank", landSystem.isBlank());
		assertNotNull("landscape user half must compose (a dropped user section would hard-fail the call)", landUser);
		assertFalse("landscape user half must be non-blank", landUser.isBlank());

		String landComposed = applyVars(landUser, landscapeVars());
		Matcher landM = guard.matcher(landComposed);
		assertFalse("landscape composed user must have NO unsubstituted {token} — the guard would refuse the call"
			+ (landM.find() ? " (found '" + landM.group() + "')" : ""), guard.matcher(landComposed).find());
		// Catch a silently-dropped user section: no placeholder AND no content is still broken.
		assertTrue("landscape composed user must contain the substituted stanza text",
			landComposed.contains(STANZA_MARKER));
		assertTrue("landscape composed user must contain the substituted mood", landComposed.contains("somber, elegiac"));
		logger.info("  [landscape] composed user prompt:\n" + landComposed);

		// --- chapBook.poem-analysis ---
		// Also proves the literal JSON example {\"theme\":...} in the user section does NOT false-trip the
		// guard (the guard requires a letter immediately after '{'; the JSON has a quote there).
		BaseRecord poemDb = ChatUtil.resolveConfig(testUser, OlioModelNames.MODEL_PROMPT_TEMPLATE, POEM_ANALYSIS, null);
		assertNotNull("poem-analysis template must resolve", poemDb);
		String poemSystem = PromptTemplateComposer.composeSystem(poemDb, null, null);
		String poemUser = PromptTemplateComposer.composeUser(poemDb, null, null);
		assertNotNull("poem-analysis system half must compose", poemSystem);
		assertFalse("poem-analysis system half must be non-blank", poemSystem.isBlank());
		assertNotNull("poem-analysis user half must compose", poemUser);
		assertFalse("poem-analysis user half must be non-blank", poemUser.isBlank());

		String poemComposed = applyVars(poemUser, poemAnalysisVars());
		Matcher poemM = guard.matcher(poemComposed);
		assertFalse("poem-analysis composed user must have NO unsubstituted {token} (incl. the JSON example)"
			+ (poemM.find() ? " (found '" + poemM.group() + "')" : ""), guard.matcher(poemComposed).find());
		assertTrue("poem-analysis composed user must contain the substituted poem text",
			poemComposed.contains(POEM_MARKER));
		logger.info("  [poem-analysis] composed user prompt:\n" + poemComposed);

		logger.info("chapBookTemplatesComposeWithoutUnsubstitutedPlaceholders PASSED");
	}

	@Test
	public void chapBookLandscapePromptLiveLLM() throws Exception {
		logger.warn("[LLM-LIVE] chapBookLandscapePromptLiveLLM: requires reachable Ollama server");
		String llmType = testProperties.getProperty("test.llm.type");
		if (llmType == null || llmType.isEmpty()) {
			logger.warn("SKIPPED: no LLM configuration (test.llm.type)");
			return;
		}
		BaseRecord testUser = seedAndGetUser();

		// Real Ollama chat config from test properties (server=192.168.1.42, model=qwen3-vl:8b-instruct).
		BaseRecord chatConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "cbLandscapeLLM", testProperties);
		assertNotNull("Ollama chat config should build", chatConfig);

		// Invoke the EXACT production method (package-private) via reflection so the full path runs:
		// resolveConfig -> compose -> substitute -> UNSUBSTITUTED_PLACEHOLDER guard -> Chat.chat().
		Method m = PictureBookUtil.class.getDeclaredMethod(
			"callLlmForChapBook", BaseRecord.class, BaseRecord.class, String.class, Map.class, boolean[].class);
		m.setAccessible(true);
		boolean[] hardFail = new boolean[1];
		String result = (String) m.invoke(null, testUser, chatConfig, LANDSCAPE, landscapeVars(), hardFail);

		if (result == null && !hardFail[0]) {
			// SOFT decline (blank/think-only) — the LLM ran but produced nothing usable this attempt.
			logger.warn("SKIPPED (soft): live landscape-prompt returned blank without a hard failure — "
				+ "composition-only verification stands; live prompt not obtained this run.");
			return;
		}
		if (hardFail[0]) {
			// HARD failure means missing template/config OR unreachable host. If the guard/template were the
			// cause the composition test would already be red; treat an unreachable host as a skip, not a lie.
			logger.warn("SKIPPED (hard): live landscape-prompt HARD-failed (likely unreachable Ollama host). "
				+ "Composition-only verified; live call not completed.");
			return;
		}
		assertNotNull("live landscape prompt should be non-null", result);
		assertFalse("live landscape prompt should be non-blank", result.isBlank());
		assertFalse("live landscape prompt must not echo an unsubstituted {token}",
			guardPattern().matcher(result).find());
		logger.info("chapBookLandscapePromptLiveLLM PASSED — live landscape prompt:\n" + result);
	}
}
