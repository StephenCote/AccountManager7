package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;

/**
 * Issue-6 LIVE-LLM end-to-end proof. The mechanical companion
 * {@link TestPictureBookLandscapePromptGuard} (4/4 green in the default gate) proves the guard math
 * with no DB/LLM; THIS test proves the real production call
 * ({@code PictureBookUtil.resolveLandscapePrompt}) now actually reaches the live Ollama LLM at
 * 192.168.1.42 and returns a genuine landscape prompt, even against a stale DB/library
 * {@code olio.llm.promptTemplate} that still carries the {@code STYLE: {style}} line the runtime
 * error came from.
 *
 * <p><b>The bug.</b> The server refused every landscape-image prompt with
 * {@code "Refusing to call LLM for prompt 'pictureBook.landscape-prompt' — template has unsubstituted
 * placeholder(s) (first: '{style}') ... Vars supplied: [setting, mood, time]"}. The landscape
 * template's user section carried {@code STYLE: {style}}, but {@code resolveLandscapePrompt}
 * deliberately did NOT send a {@code style} var (a real style made the LLM emit its own competing
 * style on top of the code-owned one). So {@code {style}} survived substitution and
 * {@code callLlmInternal}'s {@code UNSUBSTITUTED_PLACEHOLDER} guard HARD-refused the call — the
 * prompt then silently fell back to the raw setting text.
 *
 * <p><b>The fix (already applied — this test only proves it).</b> {@code resolveLandscapePrompt} now
 * also supplies {@code style=""} (EMPTY: no style signal reaches the LLM, but the placeholder
 * resolves). Robust whether the template resolves from a DB/library promptTemplate record (the
 * runtime source) or the classpath fallback.
 *
 * <p><b>How this reproduces the failure condition faithfully.</b> The shipped canonical seed no
 * longer carries {@code {style}} (see {@code TestPictureBookLandscapePromptGuard
 * .seedTemplateNoLongerCarriesStylePlaceholder}). So this test first PERSISTS an
 * {@code olio.llm.promptTemplate} named {@code pictureBook.landscape-prompt} in the NON-admin test
 * user's {@code ~/Chat} whose user section contains {@code STYLE: {style}} — recreating the exact
 * broken state — and asserts {@code ChatUtil.resolveConfig} returns it (the same call
 * {@code callLlmInternal} makes) before driving the production method. If the var-level fix were
 * absent, this stale-template scenario is precisely the one that hard-refused every call.
 *
 * <p>GATING: hits the live LLM; gated on the {@code PICTUREBOOK_E2E} env var (same flag as
 * {@code TestPictureBookUtilE2E}) so it never runs in the default gate or in parallel:
 * <pre>
 *   PICTUREBOOK_E2E=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestPictureBookLandscapePromptLiveE2E -DskipTests=false test
 * </pre>
 * No SD backend is needed. Never uses the admin user as the acting caller — admin only provisions
 * the non-admin test user.
 */
public class TestPictureBookLandscapePromptLiveE2E extends BaseTest {

	private static final String LLM_MODEL = "qwen3-vl:8b-instruct";
	private static final String ORG_SUBPATH = "/Development/PbLandscapePromptE2E";
	private static final String LANDSCAPE_TEMPLATE_NAME = "pictureBook.landscape-prompt";

	private static boolean llmEnabled() {
		return System.getenv("PICTUREBOOK_E2E") != null;
	}

	/**
	 * The EXACT placeholder guard {@code callLlmInternal} uses
	 * ({@code PictureBookUtil.UNSUBSTITUTED_PLACEHOLDER}), grabbed reflectively so this test binds to
	 * the production constant instead of duplicating {@code \{[a-zA-Z][a-zA-Z0-9_]*\}} by hand.
	 */
	private static Pattern guardPattern() throws Exception {
		Field f = PictureBookUtil.class.getDeclaredField("UNSUBSTITUTED_PLACEHOLDER");
		f.setAccessible(true);
		return (Pattern) f.get(null);
	}

	/** Copy of TestPictureBookUtilE2E.getOrCreateChatConfig — OLLAMA config against 192.168.1.42. */
	private BaseRecord getOrCreateChatConfig(BaseRecord user, String name) {
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (existing != null) return existing;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("connection", OlioTestUtil.getCreateConnection(user, name + " Connection",
				testProperties.getProperty("test.llm.ollama.server", "http://192.168.1.42:11434"), null, 180));
			cfg.set("model", LLM_MODEL);
			cfg.set("stream", false);

			BaseRecord opts = cfg.get("chatOptions");
			if (opts == null) {
				opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
				cfg.set("chatOptions", opts);
			}
			opts.set("think", false);

			return IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		} catch (Exception e) {
			logger.error("Failed to create chat config: " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Persist (fresh) an olio.llm.promptTemplate named pictureBook.landscape-prompt in the user's
	 * ~/Chat whose user section carries STYLE: {style} — the exact broken DB state the runtime error
	 * came from. Mirrors PromptConfigMigrator's proven persistence shape (section records +
	 * Factory.newInstance(..., ~/Chat) + AccessPoint.create). Deletes any pre-existing same-named
	 * user-owned record first so the reproduction is deterministic across reruns.
	 */
	private BaseRecord seedBrokenLandscapeTemplate(BaseRecord user) throws Exception {
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_PROMPT_TEMPLATE, LANDSCAPE_TEMPLATE_NAME, "~/Chat");
		if (existing != null) {
			IOSystem.getActiveContext().getAccessPoint().delete(user, existing);
		}

		BaseRecord sysSection = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_SECTION);
		sysSection.set("sectionName", "system");
		sysSection.set("role", "system");
		sysSection.set("lines", Arrays.asList(
			"You are an expert Stable Diffusion prompt engineer specializing in environments, architecture, "
			+ "and landscapes. Given a scene description, generate a high-quality SD prompt focused entirely "
			+ "on the setting — no characters, only environment, architecture, lighting, atmosphere, and "
			+ "visual style. Format: masterpiece, best quality, [detailed environment description], "
			+ "[lighting], [atmosphere]. Return only the SD prompt text — no commentary, no markdown, no "
			+ "explanation. /no_think"));
		sysSection.set("priority", 10);

		BaseRecord userSection = RecordFactory.newInstance(OlioModelNames.MODEL_PROMPT_SECTION);
		userSection.set("sectionName", "user");
		userSection.set("role", "user");
		userSection.set("lines", Arrays.asList(
			"Create a Stable Diffusion landscape/environment prompt for this scene:",
			"SETTING: {setting}",
			"MOOD: {mood}",
			"TIME: {time}",
			"STYLE: {style}",
			"",
			"Return only the SD prompt text."));
		userSection.set("priority", 20);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, LANDSCAPE_TEMPLATE_NAME);
		BaseRecord tpl = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_PROMPT_TEMPLATE, user, null, plist);
		tpl.set("templateVersion", 1);
		tpl.set("role", "system");
		tpl.set("sections", Arrays.asList(sysSection, userSection));
		tpl.set("sectionOrder", Arrays.asList("system"));

		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, tpl);
		assertNotNull("Failed to persist the reproduction pictureBook.landscape-prompt promptTemplate", created);
		return created;
	}

	@Test
	public void resolveLandscapePromptCallsLiveLlmThroughGuard_issue6() throws Exception {
		assumeTrue("PICTUREBOOK_E2E not set — skipping live-LLM landscape-prompt E2E test", llmEnabled());
		logger.info("=== TestPictureBookLandscapePromptLiveE2E: Issue-6 live-LLM proof ===");
		assertNotNull("test.llm.ollama.server must be set", testProperties.getProperty("test.llm.ollama.server"));

		// ---- Setup: non-admin test user (admin only provisions it) ----
		OrganizationContext orgCtx = getTestOrganization(ORG_SUBPATH);
		long orgId = orgCtx.getOrganizationId();
		Factory mf = IOSystem.getActiveContext().getFactory();
		BaseRecord user = mf.getCreateUser(orgCtx.getAdminUser(), "pbLandscapeE2EUser", orgId);
		assertNotNull("Test user should be created", user);
		assertFalse("Actor must not be the admin user", "admin".equals(user.get(FieldNames.FIELD_NAME)));

		BaseRecord chatConfig = getOrCreateChatConfig(user, "pbLandscapeE2EConfig");
		assertNotNull("Chat config should be created", chatConfig);

		// ---- Reproduce the broken DB state (STYLE: {style} in a resolvable promptTemplate) ----
		BaseRecord created = seedBrokenLandscapeTemplate(user);
		Long userId = user.get(FieldNames.FIELD_ID);
		Long templateOwnerId = created.get(FieldNames.FIELD_OWNER_ID);
		assertEquals("Reproduction template must be owned by the (non-admin) test user so the production "
			+ "owner-filtered resolveConfig lookup finds it", userId, templateOwnerId);
		// Bust the query cache so resolution is deterministic, not cold-cache dependent: getConfig's
		// org+owner+name query is otherwise subject to a stale null cached before the record existed
		// (a real gotcha, not a test hack — the same key the production callLlmInternal path uses).
		CacheUtil.clearCache();

		// Faithfulness assertion 1: the production resolution path (the same ChatUtil.resolveConfig call
		// callLlmInternal makes) actually returns our reproduction template, and it carries {style}.
		BaseRecord resolved = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_PROMPT_TEMPLATE, LANDSCAPE_TEMPLATE_NAME, null);
		assertNotNull("Reproduction promptTemplate must resolve via the production ChatUtil.resolveConfig path", resolved);
		String composedUser = PromptTemplateComposer.composeUser(resolved, null, chatConfig);
		assertNotNull("composeUser must produce a user template", composedUser);
		assertTrue("Reproduction must carry the STYLE: {style} line the runtime error came from — composed user was:\n"
			+ composedUser, composedUser.contains("{style}"));
		logger.info("Resolved landscape promptTemplate composeUser (reproduces the broken {style} state):\n" + composedUser);

		// Faithfulness assertion 2 (mechanical, against the LIVE resolved template + the REAL guard
		// pattern): the PRE-fix var set {setting, mood, time} still leaves {style}, i.e. the guard WOULD
		// have hard-refused this exact template. Proves the scenario under test is genuinely the Issue-6
		// failure condition, not a template that happens to be safe.
		String setting = "a derelict orbital station interior, rusted bulkheads, flickering emergency lights, "
			+ "frost on shattered viewports, drifting debris";
		String mood = "eerie, abandoned, tense, foreboding";
		Pattern guard = guardPattern();
		String preFixSubstituted = composedUser
			.replace("{setting}", setting)
			.replace("{mood}", mood)
			.replace("{time}", "");
		Matcher preFixMatch = guard.matcher(preFixSubstituted);
		assertTrue("PRE-fix var set {setting, mood, time} must leave an unsubstituted placeholder against the "
			+ "live resolved template — the exact Issue-6 hard-fail", preFixMatch.find());
		logger.info("Confirmed the guard WOULD hard-refuse the pre-fix var set here (surviving placeholder: '"
			+ preFixMatch.group() + "')");

		// ---- Persisted scene note (real cache read/write path via getSceneTextField/updateSceneTextField) ----
		ParameterList nlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		nlist.parameter(FieldNames.FIELD_NAME, "pbLandscapeScene-" + System.currentTimeMillis());
		BaseRecord scene = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, nlist);
		scene = IOSystem.getActiveContext().getAccessPoint().create(user, scene);
		assertNotNull("Scene note should be created (fresh, so no cached landscapePrompt -> LLM is really called)", scene);

		// ---- Drive the ACTUAL production method (private) via reflection. sdConfig=null,
		// promptTemplateOverride=null. hasRealInput is true (setting+mood non-blank), so the LLM is called. ----
		Method m = PictureBookUtil.class.getDeclaredMethod("resolveLandscapePrompt",
			BaseRecord.class, BaseRecord.class, BaseRecord.class, String.class, String.class,
			BaseRecord.class, String.class);
		m.setAccessible(true);

		long t0 = System.currentTimeMillis();
		String returned = (String) m.invoke(null, user, scene, chatConfig, setting, mood, null, null);
		logger.info("resolveLandscapePrompt (live LLM) took " + (System.currentTimeMillis() - t0) + "ms");

		// ---- Assertions: prove the call went through the guard and returned a genuine landscape prompt ----
		assertNotNull("resolveLandscapePrompt must return a non-null landscape prompt", returned);
		assertFalse("returned landscape prompt must not be blank", returned.isBlank());

		// The EXACT string a hard-refusal would have produced: guard returns null -> isErrorOrEmptyPayload
		// -> landscapePrompt = setting -> prependContextOnce("", ...) no-op -> appendConfigStyleOnce(.., null)
		// appends "(professional photograph).". This is the pre-fix behavior; the returned value must NOT
		// equal it (that is the conclusive Issue-6 discriminator).
		String hardFailFallback = setting.trim() + ". (professional photograph).";
		assertFalse("Issue 6: the returned prompt must NOT be the guard's hard-fail fallback (raw setting + "
			+ "code-owned style suffix). Equality here would mean the {style} guard STILL hard-refused the "
			+ "call. Returned: \"" + returned + "\"", returned.equals(hardFailFallback));
		assertNotEquals("returned must not be the bare setting text (a hard-fail fallback)", setting, returned);

		// A real generated SD landscape prompt is materially richer than the raw setting.
		assertTrue("returned must be materially richer than the raw setting — a real LLM landscape prompt "
			+ "(returned length " + returned.length() + " vs setting " + setting.length() + "). Returned: \""
			+ returned + "\"", returned.length() > setting.length() + 40);

		// Recognizable SD environment-prompt content (any-of; the system prompt asks for exactly this shape:
		// "masterpiece, best quality, [detailed environment description], [lighting], [atmosphere]").
		String low = returned.toLowerCase();
		boolean hallmark = low.contains("masterpiece") || low.contains("quality") || low.contains("lighting")
			|| low.contains("atmosphere") || low.contains("detailed") || low.contains("environment");
		assertTrue("returned must contain recognizable SD environment-prompt content "
			+ "(masterpiece/quality/lighting/atmosphere/detailed/environment). Returned: \"" + returned + "\"",
			hallmark);

		logger.info("=== Issue-6 PROOF: live LLM landscape prompt returned (guard did NOT hard-refuse) ===");
		logger.info(returned);
		logger.info("=== TestPictureBookLandscapePromptLiveE2E PASSED ===");
	}
}
