package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Unit tests for FLUX Kontext scene compositing pipeline using promptImages.
/// Includes prompt/config unit tests and a full integration test that calls SwarmUI with FLUX Kontext.
public class TestKontext extends BaseTest {

	private static final String OUTPUT_DIR = "./kontext-test-output";

	/// Export bytes to disk for review.
	private void exportImage(String filename, byte[] data) {
		java.io.File dir = new java.io.File(OUTPUT_DIR);
		if (!dir.exists()) dir.mkdirs();
		FileUtil.emitFile(OUTPUT_DIR + "/" + filename, data);
		logger.info("Exported: " + OUTPUT_DIR + "/" + filename + " (" + data.length + " bytes)");
	}

	/// Get portrait byte data from a person's profile.portrait, populating as needed.
	private byte[] getPortraitBytes(BaseRecord character) {
		try {
			BaseRecord profile = character.get("profile");
			if (profile == null) {
				IOSystem.getActiveContext().getReader().populate(character, new String[] {"profile"});
				profile = character.get("profile");
			}
			if (profile == null) return null;

			BaseRecord portrait = profile.get("portrait");
			if (portrait == null) {
				IOSystem.getActiveContext().getReader().populate(profile, new String[] {"portrait"});
				portrait = profile.get("portrait");
			}
			if (portrait == null) return null;

			byte[] bytes = portrait.get(FieldNames.FIELD_BYTE_STORE);
			if (bytes == null || bytes.length == 0) {
				IOSystem.getActiveContext().getReader().populate(portrait, new String[] {FieldNames.FIELD_BYTE_STORE});
				bytes = portrait.get(FieldNames.FIELD_BYTE_STORE);
			}
			return (bytes != null && bytes.length > 0) ? bytes : null;
		} catch (Exception e) {
			logger.debug("Could not load portrait: " + e.getMessage());
		}
		return null;
	}

	@Test
	public void testNewKontextSceneTxt2ImgDefaults() {
		logger.info("testNewKontextSceneTxt2ImgDefaults");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(
			"a tall man with brown hair",
			"a short woman with red hair",
			"walking through a meadow",
			"a sunlit countryside",
			null
		);

		assertNotNull("SWTxt2Img should not be null", s2i);
		/// kontextModel deliberately has NO schema default any more. It used to default to
		/// flux1Kontext_flux1KontextDev, a checkpoint absent from the local Swarm — and because a
		/// schema default is never null it was sent on EVERY kontext-mode request and refused with
		/// "Invalid model value for param Model", so the composite silently produced nothing. The
		/// composite now defaults to compositeMode=flux2 (flux2Model=flux2Klein_9b, which ships).
		///
		/// With no config, defaultKontextModel() therefore resolves to the deployment's configured SD
		/// checkpoint rather than inventing a Kontext name nobody has. Assert that, and assert the
		/// specific dead name can never come back.
		assertEquals("With no config, the Kontext builder must fall back to the deployment's configured "
			+ "checkpoint, not a hardcoded Kontext name",
			SWUtil.defaultKontextModel(), s2i.getModel());
		assertTrue("The uninstalled flux1Kontext_flux1KontextDev must never be reintroduced as a default",
			!"flux1Kontext_flux1KontextDev".equals(s2i.getModel()));
		assertEquals("Steps should be 28", 28, s2i.getSteps());
		// delta form: cfgScale widened to double so FLUX.2 can use a fractional CFG (2.5). Kontext's
		// own default is unchanged at 1.
		assertEquals("CFG should be 1", 1.0, s2i.getCfgScale(), 0.001);
		assertEquals("Sampler should be euler", "euler", s2i.getSampler());
		assertEquals("Scheduler should be simple", "simple", s2i.getScheduler());
		assertEquals("Width should be 1024", 1024, s2i.getWidth());
		assertEquals("Height should be 1024", 1024, s2i.getHeight());
		assertEquals("Refiner control should be 0.0", 0.0, s2i.getRefinerControlPercentage(), 0.001);
		assertTrue("Seed should be positive", s2i.getSeed() > 0);

		String prompt = s2i.getPrompt();
		assertNotNull("Prompt should not be null", prompt);
		assertTrue("Should mention combining reference images", prompt.contains("Combine the reference images"));
		assertTrue("Should mention panels", prompt.contains("left and center panels"));
		assertTrue("Should contain first char desc", prompt.contains("a tall man with brown hair"));
		assertTrue("Should contain second char desc", prompt.contains("a short woman with red hair"));
		assertTrue("Should contain scene action", prompt.contains("walking through a meadow"));
		assertTrue("Should contain setting", prompt.contains("a sunlit countryside"));
		assertEquals("Negative prompt should be empty for Kontext", "", s2i.getNegativePrompt());
		logger.info("Prompt: " + prompt);
	}

	@Test
	public void testNewKontextSceneTxt2ImgNullDescriptions() {
		logger.info("testNewKontextSceneTxt2ImgNullDescriptions");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(null, null, null, null, null);
		assertNotNull("SWTxt2Img should not be null", s2i);

		String prompt = s2i.getPrompt();
		assertNotNull("Prompt should not be null", prompt);
		assertTrue("Prompt should have base instruction", prompt.contains("Combine the reference images"));
		assertTrue("Prompt should not contain 'null'", !prompt.contains("null"));
		logger.info("Null-safe prompt: " + prompt);
	}

	@Test
	public void testNewKontextSceneTxt2ImgSerialization() {
		logger.info("testNewKontextSceneTxt2ImgSerialization");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(
			"a man with blue eyes",
			"a woman with green eyes",
			"sitting at a cafe",
			"Paris street",
			null
		);

		String json = JSONUtil.exportObject(s2i);
		assertNotNull("JSON should not be null", json);
		/// See testNewKontextSceneTxt2ImgDefaults: kontextModel has no schema default any more, so the
		/// serialized model is whatever defaultKontextModel() resolves to for this deployment.
		assertTrue("JSON should contain the resolved model (" + SWUtil.defaultKontextModel() + ")",
			json.contains(SWUtil.defaultKontextModel()));
		assertTrue("JSON should contain setting in prompt", json.contains("Paris street"));
		assertTrue("JSON should contain sampler=euler", json.contains("euler"));

		java.io.File dir = new java.io.File(OUTPUT_DIR);
		if (!dir.exists()) dir.mkdirs();
		FileUtil.emitFile(OUTPUT_DIR + "/kontext-request.json", json.getBytes());

		logger.info("Serialized JSON length: " + json.length());
		logger.info("JSON: " + json.substring(0, Math.min(500, json.length())));
	}

	/// A. styleClause mapping — every configModel.json style limit value (plus illustration),
	/// case-insensitivity, and the null/empty/unknown fallback to the exact photograph default.
	@Test
	public void testStyleClauseMapping() {
		logger.info("testStyleClauseMapping");

		String photo = "Natural lighting consistent with the background. High quality photograph.";

		/// Values that fall back to the photograph default
		assertEquals("photograph -> photo default", photo, SWUtil.styleClause("photograph"));
		assertEquals("selfie -> photo default", photo, SWUtil.styleClause("selfie"));
		assertEquals("custom -> photo default", photo, SWUtil.styleClause("custom"));

		/// Distinct style phrasing
		assertEquals("illustration", "Rendered as a detailed illustration.", SWUtil.styleClause("illustration"));
		assertEquals("art", "Digital painting art style.", SWUtil.styleClause("art"));
		assertEquals("digitalArt", "Digital painting art style.", SWUtil.styleClause("digitalArt"));
		assertEquals("movie", "Cinematic film still.", SWUtil.styleClause("movie"));
		assertEquals("anime", "Anime art style.", SWUtil.styleClause("anime"));
		assertEquals("comic", "Comic book art style.", SWUtil.styleClause("comic"));
		assertEquals("portrait", "Studio portrait photograph.", SWUtil.styleClause("portrait"));
		assertEquals("fashion", "High fashion editorial photograph.", SWUtil.styleClause("fashion"));
		assertEquals("vintage", "Vintage film photograph aesthetic.", SWUtil.styleClause("vintage"));

		/// Fallbacks: null, empty, whitespace, unknown
		assertEquals("null -> photo default", photo, SWUtil.styleClause(null));
		assertEquals("empty -> photo default", photo, SWUtil.styleClause(""));
		assertEquals("whitespace -> photo default", photo, SWUtil.styleClause("   "));
		assertEquals("unknown -> photo default", photo, SWUtil.styleClause("banana"));

		/// Case-insensitivity
		assertEquals("Anime (mixed case)", "Anime art style.", SWUtil.styleClause("Anime"));
		assertEquals("ANIME (upper)", "Anime art style.", SWUtil.styleClause("ANIME"));
		assertEquals("anime (lower)", "Anime art style.", SWUtil.styleClause("anime"));
		assertEquals("DigitalArt (mixed case)", "Digital painting art style.", SWUtil.styleClause("DigitalArt"));
		assertEquals("  Movie  (padded)", "Cinematic film still.", SWUtil.styleClause("  Movie  "));

		logger.info("testStyleClauseMapping PASSED");
	}

	/// B. 7-arg overload appends style + mood clauses and drops the photograph default.
	@Test
	public void testKontextOverloadAddsStyleAndMood() {
		logger.info("testKontextOverloadAddsStyleAndMood");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(
			"a tall man with brown hair",
			"a short woman with red hair",
			"walking through a meadow",
			"a sunlit countryside",
			"anime",
			"tense",
			null
		);
		assertNotNull("SWTxt2Img should not be null", s2i);

		String prompt = s2i.getPrompt();
		assertNotNull("Prompt should not be null", prompt);
		logger.info("Style+mood prompt: " + prompt);

		assertTrue("Prompt should contain the anime style clause", prompt.contains("Anime art style."));
		assertTrue("Prompt should contain the mood clause", prompt.contains("The mood is tense."));
		assertFalse("Prompt should NOT contain the photograph default when a style is set",
			prompt.contains("High quality photograph."));
		/// mood clause must come after the setting sentence
		assertTrue("Setting sentence should precede mood clause",
			prompt.indexOf("The setting is a sunlit countryside.") < prompt.indexOf("The mood is tense."));
	}

	/// C. Backward-compat regression guard — legacy 5-arg and 7-arg(null,null) must produce the
	/// legacy output: ends with the exact photograph line and carries no mood clause.
	@Test
	public void testKontextBackwardCompatRegression() {
		logger.info("testKontextBackwardCompatRegression");

		String legacyTail = "Natural lighting consistent with the background. High quality photograph.";

		/// Legacy 5-arg
		SWTxt2Img legacy = SWUtil.newKontextSceneTxt2Img(
			"a tall man with brown hair",
			"a short woman with red hair",
			"walking through a meadow",
			"a sunlit countryside",
			null
		);
		String legacyPrompt = legacy.getPrompt();
		assertNotNull("Legacy prompt should not be null", legacyPrompt);
		assertTrue("Legacy prompt should end with the exact photograph line",
			legacyPrompt.endsWith(legacyTail));
		assertFalse("Legacy prompt should have no mood clause", legacyPrompt.contains("The mood is"));

		/// 7-arg with null style + null mood must be byte-identical to legacy
		SWTxt2Img delegated = SWUtil.newKontextSceneTxt2Img(
			"a tall man with brown hair",
			"a short woman with red hair",
			"walking through a meadow",
			"a sunlit countryside",
			null,
			null,
			null
		);
		String delegatedPrompt = delegated.getPrompt();
		assertEquals("7-arg(null style, null mood) must be byte-identical to legacy 5-arg",
			legacyPrompt, delegatedPrompt);

		/// Empty-string style/mood must also fall back to legacy tail with no mood clause
		SWTxt2Img empties = SWUtil.newKontextSceneTxt2Img(
			"a tall man with brown hair",
			"a short woman with red hair",
			"walking through a meadow",
			"a sunlit countryside",
			"",
			"",
			null
		);
		String emptiesPrompt = empties.getPrompt();
		assertTrue("Empty style/mood should end with the photograph line",
			emptiesPrompt.endsWith(legacyTail));
		assertFalse("Empty mood should add no mood clause", emptiesPrompt.contains("The mood is"));

		logger.info("Legacy prompt: " + legacyPrompt);
	}

	@Test
	public void testStripSDXLWeighting() {
		logger.info("testStripSDXLWeighting");

		/// SDXL-style weighted prompt
		String input = "a beautiful athletic ((twenty five:1.5) (25yo:1.5) (white) (woman)) with ((short and curly) (blond hair)) and (electric blue eyes)";
		String cleaned = SWUtil.stripSDXLWeighting(input);
		assertNotNull("Cleaned prompt should not be null", cleaned);
		assertTrue("Should not contain parentheses", !cleaned.contains("(") && !cleaned.contains(")"));
		assertTrue("Should not contain weight numbers", !cleaned.contains(":1.5"));
		assertTrue("Should preserve text content", cleaned.contains("twenty five"));
		assertTrue("Should preserve text content", cleaned.contains("blond hair"));
		logger.info("Input:   " + input);
		logger.info("Cleaned: " + cleaned);

		/// Already clean prompt
		assertEquals("Clean prompt should pass through", "a man with brown hair", SWUtil.stripSDXLWeighting("a man with brown hair"));

		/// Null/empty
		assertNull("Null should return null", SWUtil.stripSDXLWeighting(null));
		assertEquals("Empty should return empty", "", SWUtil.stripSDXLWeighting(""));
	}

	/// Ensure a character has apparel. If not, create a random outfit and persist it.
	private void ensureApparel(OlioContext ctx, BaseRecord person) throws Exception {
		BaseRecord wearing = ApparelUtil.getWearingApparel(person);
		if (wearing != null) {
			logger.info(person.get(FieldNames.FIELD_NAME) + " already has apparel");
			return;
		}
		logger.info(person.get(FieldNames.FIELD_NAME) + " has no apparel — creating random outfit");
		ApparelUtil.outfitAndStage(ctx, null, java.util.Arrays.asList(person));
		Queue.processQueue();
	}

	/// Ensure a character has a portrait. If not, generate one via SwarmUI.
	private byte[] ensurePortrait(SDUtil sdu, OlioContext ctx, BaseRecord testUser, BaseRecord person) throws Exception {
		byte[] pb = getPortraitBytes(person);
		if (pb != null && pb.length > 0) {
			logger.info(person.get(FieldNames.FIELD_NAME) + " already has portrait (" + pb.length + " bytes)");
			return pb;
		}
		logger.info(person.get(FieldNames.FIELD_NAME) + " has no portrait — generating");

		List<BaseRecord> images = sdu.createPersonImage(testUser, person, "~/Gallery",
			/// KI-39: randomSDConfig() carries the model SCHEMA default, a per-deployment checkpoint
			/// name that this node may not have. Stamp the one test.swarm.model says is installed.
			SdTestGate.stampInstalledModel(SDUtil.randomSDConfig(), testProperties),
			"Kontext Portrait - " + person.get(FieldNames.FIELD_NAME) + " - " + UUID.randomUUID().toString(),
			"random", "professional portrait", "full body", "standing", 20, 1, false, -1);

		if (images.isEmpty()) {
			logger.warn("Portrait generation failed for " + person.get(FieldNames.FIELD_NAME));
			return null;
		}

		/// Set portrait on profile
		BaseRecord profile = person.get("profile");
		if (profile == null) {
			IOSystem.getActiveContext().getReader().populate(person, new String[] {"profile"});
			profile = person.get("profile");
		}
		if (profile != null) {
			profile.setValue("portrait", images.get(0));
			Queue.queueUpdate(profile, new String[] {FieldNames.FIELD_ID, "portrait"});
			Queue.processQueue();
		}
		return getPortraitBytes(person);
	}

	/// Integration test: Pick 2 characters, ensure they have apparel + portraits,
	/// generate a landscape, and send to SwarmUI with the FLUX Kontext model via promptImages.
	/// DISABLED, not skipped. FLUX Kontext is a SUPERSEDED composite path: compositeMode now defaults
	/// to flux2 (flux2Klein_9b, which ships), and the picture-book pipeline routes composites through
	/// SceneCompositeUtil's FLUX.2 builder. This test needs a flux1Kontext checkpoint that is not
	/// installed, so every run it either fired requests Swarm refused ("Invalid model value for param
	/// Model - 'flux1Kontext_flux1KontextDev'") or reported a permanent Skip — noise and GPU time for
	/// a path nothing uses. The prompt-shape tests in this class still cover the shared builder and
	/// stay enabled. Re-enable by installing a Kontext checkpoint and setting test.swarm.kontextModel.
	@org.junit.Ignore("FLUX Kontext is superseded by compositeMode=flux2; no Kontext checkpoint is installed")
	@Test
	public void testKontextSceneWithOlioCharacters() throws Exception {
		logger.info("testKontextSceneWithOlioCharacters");

		String swarmServer = testProperties.getProperty("test.swarm.server");
		/// KI-39/KI-48: an unconfigured server is a visible Skip, never a silent pass.
		SdTestGate.requireSwarmConfigured(swarmServer);

		/// Gate on the Kontext CHECKPOINT before doing any work. Checking only at the end (after the
		/// portraits and landscape have already been generated) still fires a request Swarm refuses:
		///   [Warning] Refused to generate image for local: Invalid model value for param Model -
		///   'flux1Kontext_flux1KontextDev' - are you sure that model name is correct?
		/// That refusal is noise in the Swarm log for a test that cannot possibly pass here, and it
		/// burns GPU time on portraits/landscape first. Resolve the checkpoint from
		/// test.swarm.kontextModel (falling back to the olio.sd.config schema default) and skip
		/// VISIBLY, up front, when this node does not carry it.
		/// kontextModel has NO schema default (it used to name an uninstalled checkpoint). So Kontext
		/// mode now requires an explicit choice: without test.swarm.kontextModel there is nothing to
		/// exercise here, and that is a visible Skip — never a pass.
		String kontextModel = testProperties.getProperty("test.swarm.kontextModel");
		org.junit.Assume.assumeTrue("SKIPPED: test.swarm.kontextModel is not set and kontextModel has no "
			+ "schema default (deliberately — the old flux1Kontext_flux1KontextDev is not installed and "
			+ "Swarm refused every request carrying it). Set it to an installed Kontext checkpoint to "
			+ "exercise this path. This is NOT a pass.", kontextModel != null && !kontextModel.isBlank());
		SdTestGate.requireModelInstalled(new SDUtil(SDAPIEnumType.SWARM, swarmServer), swarmServer,
			kontextModel, "FLUX Kontext scene compositing");

		/// Step 1: Get Olio context with population
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String dataPath = testProperties.getProperty("test.datagen.path");
		OlioContext ctx = OlioTestUtil.getContext(orgContext, dataPath);
		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord popGrp = realms.get(0).get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Expected a population group", popGrp);
		List<BaseRecord> pop = OlioUtil.listGroupPopulation(ctx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);

		ApparelUtil.outfitAndStage(ctx, null, pop);
		ItemUtil.showerWithMoney(ctx, pop);
		Queue.processQueue();

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);

		/// Step 2: Pick 2 random characters, ensure apparel + portrait
		java.util.Random rand = new java.util.Random();
		java.util.Collections.shuffle(pop, rand);

		List<BaseRecord> ready = new ArrayList<>();
		for (BaseRecord person : pop) {
			if (ready.size() >= 2) break;
			try {
				ensureApparel(ctx, person);
				byte[] pb = ensurePortrait(sdu, ctx, testUser, person);
				if (pb != null && pb.length > 0) {
					ready.add(person);
				}
			} catch (Exception e) {
				logger.warn("Skipping " + person.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			}
		}

		/// KI-48: this bail-out used to `return`, reporting a PASS for a test that never generated
		/// anything. Preparation failing is a legitimate reason not to run, but it is not evidence.
		SdTestGate.insufficientPreparation("characters with apparel + portrait", ready.size(), 2);

		BaseRecord person1 = ready.get(0);
		BaseRecord person2 = ready.get(1);
		byte[] portrait1Bytes = getPortraitBytes(person1);
		byte[] portrait2Bytes = getPortraitBytes(person2);
		assertNotNull("Portrait 1 should not be null", portrait1Bytes);
		assertNotNull("Portrait 2 should not be null", portrait2Bytes);

		exportImage("kontext-portrait1.png", portrait1Bytes);
		exportImage("kontext-portrait2.png", portrait2Bytes);
		logger.info("Person 1: " + person1.get(FieldNames.FIELD_NAME) + " portrait=" + portrait1Bytes.length + " bytes");
		logger.info("Person 2: " + person2.get(FieldNames.FIELD_NAME) + " portrait=" + portrait2Bytes.length + " bytes");

		/// Step 3: Generate a landscape image
		String landscapePrompt = "wide establishing shot of a peaceful fantasy village, rolling hills, cobblestone paths, warm sunlight, 4k cinematic landscape photography, no people";
		SWTxt2Img landReq = SWUtil.newSceneTxt2Img(landscapePrompt, "people, faces, text, watermark", null);
		landReq.setWidth(1024);
		landReq.setHeight(768);
		landReq.setSteps(20);

		List<BaseRecord> landImages = sdu.createSceneImage(testUser, "~/Gallery",
			"Kontext Landscape - " + UUID.randomUUID().toString(), landReq, null, null);
		if (landImages.isEmpty()) {
			/// KI-48: was a warn+return (silent pass). Classify it: absent checkpoint = visible Skip,
			/// present checkpoint = a real generation failure.
			SdTestGate.emptyResultIsSkipOrFailure(sdu, swarmServer,
				testProperties.getProperty("test.swarm.model"), "Landscape generation");
		}

		byte[] landscapeBytes = landImages.get(0).get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("Landscape bytes should not be null", landscapeBytes);
		exportImage("kontext-landscape.png", landscapeBytes);
		logger.info("Landscape generated: " + landscapeBytes.length + " bytes");

		/// Step 4: Get character descriptions for Kontext prompts
		PersonalityProfile pp1 = ProfileUtil.getProfile(null, person1);
		PersonalityProfile pp2 = ProfileUtil.getProfile(null, person2);
		String desc1 = pp1 != null ? NarrativeUtil.getSDMinPrompt(pp1) : (String) person1.get(FieldNames.FIELD_NAME);
		String desc2 = pp2 != null ? NarrativeUtil.getSDMinPrompt(pp2) : (String) person2.get(FieldNames.FIELD_NAME);
		logger.info("Char 1 SD desc: " + desc1);
		logger.info("Char 1 cleaned: " + SWUtil.stripSDXLWeighting(desc1));
		logger.info("Char 2 SD desc: " + desc2);
		logger.info("Char 2 cleaned: " + SWUtil.stripSDXLWeighting(desc2));

		String sceneDesc = "walking together through a village";
		String settingDesc = "a peaceful fantasy village with cobblestone paths";

		/// Step 5: Stitch [portrait1 | portrait2 | landscape] into single composite reference
		byte[] refComposite = SDUtil.stitchSceneImages(portrait1Bytes, portrait2Bytes, landscapeBytes, 1024);
		assertNotNull("Stitched composite should not be null", refComposite);
		exportImage("kontext-reference-composite.png", refComposite);
		logger.info("Stitched composite: " + refComposite.length + " bytes");

		/// Step 6: Single-pass Kontext — send composite as one promptImage
		logger.info("=== Kontext: Compositing " + person1.get(FieldNames.FIELD_NAME) + " & " + person2.get(FieldNames.FIELD_NAME) + " into scene ===");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(desc1, desc2, sceneDesc, settingDesc, null);
		List<String> promptImages = new ArrayList<>();
		promptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(refComposite));
		s2i.setPromptImages(promptImages);

		logger.info("Prompt: " + s2i.getPrompt());
		logger.info("promptImages count: " + promptImages.size());

		List<BaseRecord> sceneImages = sdu.createSceneImage(testUser, "~/Gallery",
			"Kontext Scene - " + person1.get("firstName") + " & " + person2.get("firstName") + " - " + UUID.randomUUID().toString(),
			s2i,
			person1.get(FieldNames.FIELD_OBJECT_ID),
			person2.get(FieldNames.FIELD_OBJECT_ID));

		if (sceneImages.isEmpty()) {
			/// This used to warn and `return`, so the test PASSED while generating nothing - the exact
			/// fake-pass pattern KI-39 describes. Confirmed 2026-08-09: run against the local Swarm,
			/// which does not carry flux1Kontext_flux1KontextDev (checkpoints differ per node - see
			/// reference notes), Swarm refused every request with "Invalid model value for param Model"
			/// while this class reported 8/8 green.
			///
			/// A missing checkpoint is a legitimate reason not to run, but it must be VISIBLE. Assume
			/// reports Skipped rather than Passed, so the report distinguishes "not exercised here" from
			/// "exercised and correct". Anything else empty-handed is a real failure and now fails.
			String refusedModel = s2i.getModel();
			boolean modelInstalled = false;
			try {
				List<String> installed = sdu.listModels();
				if (installed != null) {
					for (String m : installed) {
						/// Swarm reports names with the ".safetensors" suffix; configs generally omit it.
						if (m == null) continue;
						String bare = m.endsWith(".safetensors") ? m.substring(0, m.length() - 12) : m;
						if (m.equalsIgnoreCase(refusedModel) || bare.equalsIgnoreCase(refusedModel)) {
							modelInstalled = true;
							break;
						}
					}
				}
				logger.info("Kontext checkpoint '" + refusedModel + "' installed on " + swarmServer + ": "
					+ modelInstalled + " (" + (installed != null ? installed.size() : 0) + " checkpoints reported)");
			} catch (Exception le) {
				logger.warn("Could not list Swarm checkpoints to classify the empty result: " + le.getMessage());
			}
			org.junit.Assume.assumeTrue("SKIPPED: the configured Kontext checkpoint '" + refusedModel
				+ "' is not installed on " + swarmServer + " (checkpoint availability is per-node). This is "
				+ "not a pass - set olio.sd.config.kontextModel to an installed checkpoint, or use "
				+ "compositeMode=flux2, to exercise this path here.", modelInstalled);
			fail("Kontext returned no images even though '" + refusedModel + "' IS installed on "
				+ swarmServer + " - a real generation failure, not a missing model");
		}

		for (int i = 0; i < sceneImages.size(); i++) {
			byte[] sceneBytes = sceneImages.get(i).get(FieldNames.FIELD_BYTE_STORE);
			assertNotNull("Scene image " + i + " should have bytes", sceneBytes);
			exportImage("kontext-scene-result-" + i + ".png", sceneBytes);
			logger.info("Final scene " + i + ": " + sceneBytes.length + " bytes");
		}

		logger.info("testKontextSceneWithOlioCharacters PASSED — single-pass pipeline produced " + sceneImages.size() + " scene image(s)");
	}
}
