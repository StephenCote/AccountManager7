package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * Objects7-level integration tests for the two scene-level prompt/analysis operations behind the new
 * REST endpoints {@code POST /scene/{oid}/prompt/regenerate} and {@code POST /scene/{oid}/analyze}:
 * <ul>
 *   <li>{@link ChapBookUtil#regenerateSceneLandscapePrompt(BaseRecord, String, BaseRecord)} — re-runs the
 *       {@code chapBook.landscape-prompt} LLM on ONE scene's {@code poemStanza} + {@code mood}, persists a
 *       fresh {@code sdPrompt}, and sets {@code promptLocked=false}. NO SD image is rendered.</li>
 *   <li>{@link ChapBookUtil#analyzeSceneTheme(BaseRecord, String, BaseRecord)} — runs the same LLM
 *       theme/mood analysis {@code analyzePoemTheme} runs, but on ONE scene's stanza, and persists the
 *       analyzed {@code mood} onto the scene.</li>
 * </ul>
 * <p>
 * The LLM tests are gated on {@code test.llm.ollama.server} (live Ollama at 192.168.1.42). If it is
 * genuinely unconfigured they SKIP via {@link org.junit.Assume} — they never fake a pass. A non-admin
 * {@code getCreateUser} user is used throughout; the admin user only provisions it. NO SD server is
 * contacted (these are prompt/analysis only). Books are seeded with {@code chatConfig=null} so scene
 * creation costs no LLM call; the LLM is exercised ONLY by the two operations under test.
 * <p>
 * The guard-contract test ({@code guardContracts_*}) runs UNCONDITIONALLY — it exercises the 400/503
 * error paths that fire before any LLM or scene read.
 */
public class TestChapBookScenePromptOps extends BaseTest {

	/** Reuse the ChapBook suite's stable org (avoids the multi-minute Olio seed). */
	private static final String ORG_PATH = "/Development/ChapBook Tests";

	/** REAL two-stanza poem corpus (verbatim from {@link TestChapBookSceneLandscapePrompt}). */
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
	private long orgId;

	@Before
	public void setUpScenePromptOps() {
		// BaseTest.setup() (@Before) runs first and calls OlioModelNames.use(). Reuse the ChapBook suite's
		// stable NON-ADMIN test user (admin only provisions it via getCreateUser).
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookScenePromptOpsUser", ctx.getOrganizationId());
		assertNotNull("chapbookScenePromptOpsUser must be created", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/**
	 * regenerate must produce a GENUINE (LLM-authored) landscape prompt and leave the scene UNLOCKED —
	 * even when it starts from a LOCKED, human-edited prompt (an explicit regenerate OVERRIDES a lock).
	 * <p>
	 * Live LLM only, no SD. Steps: seed a book (no LLM at create), LOCK a hand-written prompt on scene[0]
	 * via {@code setSceneLandscapePrompt} (which sets {@code promptLocked=true}), then regenerate and prove
	 * (a) the returned prompt is non-blank and genuine (not the {@code "landscape, "} no-LLM fallback shape),
	 * (b) it differs from the locked human text, (c) the scene re-reads with that exact prompt and with
	 * {@code promptLocked=false}.
	 */
	@Test
	public void regenerateSceneLandscapePrompt_producesGenuineUnlockedPrompt_evenFromLockedStart() {
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping ChapBook scene regenerate test",
			llmServer != null && !llmServer.isBlank());

		BaseRecord liveConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookScenePromptOpsLlm", testProperties);
		assertNotNull("Live chatConfig must be built", liveConfig);

		String sceneOid = seedFirstSceneOid("regen");

		// LOCK a deliberate human edit so we prove regenerate OVERRIDES an existing lock.
		String lockedHuman = "a hand written prompt, deliberately locked by the author";
		assertTrue("setSceneLandscapePrompt must lock the human edit",
			ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, lockedHuman));
		BaseRecord lockedScene = readSceneFresh(sceneOid);
		assertEquals("precondition: the human edit is stored verbatim",
			lockedHuman, lockedScene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("precondition: the human edit LOCKED the prompt",
			Boolean.TRUE, lockedScene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

		// Regenerate against the LIVE LLM.
		String newPrompt = ChapBookUtil.regenerateSceneLandscapePrompt(testUser, sceneOid, liveConfig);
		assertNotNull("regenerate must return a prompt", newPrompt);
		assertFalse("regenerated prompt must be non-blank", newPrompt.isBlank());
		assertTrue("regenerated prompt must be a GENUINE LLM prompt (not the 'landscape, ' no-LLM fallback "
			+ "shape); got: " + newPrompt, ChapBookUtil.isGenuineStoredPrompt(newPrompt));
		assertFalse("regenerated prompt must differ from the locked human text (a real regeneration ran)",
			lockedHuman.equals(newPrompt));

		// Re-read the scene: the new prompt is persisted and the scene is UNLOCKED.
		BaseRecord after = readSceneFresh(sceneOid);
		assertEquals("the regenerated prompt must be persisted verbatim on the scene",
			newPrompt, after.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("an explicit regenerate must UNLOCK the scene (promptLocked=false) even though it "
			+ "started from a locked human prompt", Boolean.FALSE, after.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

		logger.info("regenerateSceneLandscapePrompt: scene {} regenerated genuine unlocked prompt='{}'",
			sceneOid, newPrompt);
	}

	/**
	 * analyze must persist a non-blank {@code mood} onto the scene via the same LLM analysis
	 * {@code analyzePoemTheme} uses. Live LLM only, no SD.
	 */
	@Test
	public void analyzeSceneTheme_persistsNonBlankMoodOntoScene() {
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping ChapBook scene analyze test",
			llmServer != null && !llmServer.isBlank());

		BaseRecord liveConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookScenePromptOpsLlm", testProperties);
		assertNotNull("Live chatConfig must be built", liveConfig);

		String sceneOid = seedFirstSceneOid("analyze");

		String mood = ChapBookUtil.analyzeSceneTheme(testUser, sceneOid, liveConfig);
		assertNotNull("analyzeSceneTheme must return a mood", mood);
		assertFalse("analyzed mood must be non-blank", mood.isBlank());

		BaseRecord after = readSceneFresh(sceneOid);
		String storedMood = after.get(OlioFieldNames.FIELD_PB_MOOD);
		assertNotNull("the analyzed mood must be persisted onto the scene", storedMood);
		assertFalse("the persisted mood must be non-blank", storedMood.isBlank());
		assertEquals("the persisted mood must equal the returned mood", mood, storedMood);

		logger.info("analyzeSceneTheme: scene {} persisted mood='{}'", sceneOid, storedMood);
	}

	/**
	 * Guard contracts (no LLM, no SD — runs unconditionally). A null {@code chatConfig} yields 503; a
	 * missing/blank {@code sceneObjectId} or null {@code user} yields 400. These fire before any LLM call
	 * or scene read, so they are independent of the live server.
	 */
	@Test
	public void guardContracts_nullChatConfig_503_and_missingArgs_400() {
		// null chatConfig → 503 for both ops
		assertStatus(503, () -> ChapBookUtil.regenerateSceneLandscapePrompt(testUser, "some-scene", null));
		assertStatus(503, () -> ChapBookUtil.analyzeSceneTheme(testUser, "some-scene", null));

		// missing args → 400 (arg check precedes the chatConfig check)
		assertStatus(400, () -> ChapBookUtil.regenerateSceneLandscapePrompt(null, "some-scene", null));
		assertStatus(400, () -> ChapBookUtil.regenerateSceneLandscapePrompt(testUser, "", null));
		assertStatus(400, () -> ChapBookUtil.analyzeSceneTheme(null, "some-scene", null));
		assertStatus(400, () -> ChapBookUtil.analyzeSceneTheme(testUser, "  ", null));
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	private interface ThrowingOp { void run(); }

	/** Assert that {@code op} throws a {@link PictureBookException} with the expected HTTP status. */
	private static void assertStatus(int expected, ThrowingOp op) {
		try {
			op.run();
			fail("expected PictureBookException(" + expected + ") but no exception was thrown");
		} catch (PictureBookException pbe) {
			assertEquals("unexpected PictureBookException status", expected, pbe.getStatus());
		}
	}

	/** Seed a real CHAPBOOK (chatConfig=null → no LLM at create) and return its first scene's objectId. */
	private String seedFirstSceneOid(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookScenePromptOps-" + tag + "-" + ts,
			"Poem ScenePromptOps " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-scenepromptops-" + tag + "-" + ts,
			"ChapBook ScenePromptOps " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, book);
		assertFalse("At least one scene must be created", scenes.isEmpty());
		String sceneOid = scenes.get(0).get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Scene must carry an objectId", sceneOid);
		return sceneOid;
	}

	/** Fresh (projected) read of a scene by objectId. */
	private BaseRecord readSceneFresh(String sceneObjectId) {
		BaseRecord scene = PbBookUtil.readScene(testUser, sceneObjectId, orgId);
		assertNotNull("Scene must be re-readable by objectId " + sceneObjectId, scene);
		return scene;
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
