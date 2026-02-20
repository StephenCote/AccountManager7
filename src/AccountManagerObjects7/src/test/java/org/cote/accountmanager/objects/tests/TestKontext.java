package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
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

		/// Test pass 1 (place character into landscape)
		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(1,
			"a tall man with brown hair",
			null,
			"walking through a meadow",
			"a sunlit countryside",
			null
		);

		assertNotNull("SWTxt2Img should not be null", s2i);
		assertEquals("Model should be default Kontext", "flux1Kontext_flux1KontextDev", s2i.getModel());
		assertEquals("Steps should be 28", 28, s2i.getSteps());
		assertEquals("CFG should be 1", 1, s2i.getCfgScale());
		assertEquals("Sampler should be euler", "euler", s2i.getSampler());
		assertEquals("Scheduler should be simple", "simple", s2i.getScheduler());
		assertEquals("Width should be 1024", 1024, s2i.getWidth());
		assertEquals("Height should be 1024", 1024, s2i.getHeight());
		assertEquals("Refiner control should be 0.0", 0.0, s2i.getRefinerControlPercentage(), 0.001);
		assertTrue("Seed should be positive", s2i.getSeed() > 0);

		String prompt = s2i.getPrompt();
		assertNotNull("Prompt should not be null", prompt);
		assertTrue("Pass 1 should mention placing person", prompt.contains("Place the person from the reference portrait"));
		assertTrue("Pass 1 prompt should contain char desc", prompt.contains("a tall man with brown hair"));
		assertTrue("Pass 1 prompt should mention setting", prompt.contains("a sunlit countryside"));
		assertEquals("Negative prompt should be empty for Kontext", "", s2i.getNegativePrompt());
		logger.info("Pass 1 prompt: " + prompt);

		/// Test pass 2 (add second character to existing scene)
		SWTxt2Img s2i2 = SWUtil.newKontextSceneTxt2Img(2,
			"a short woman with red hair",
			"a tall man with brown hair",
			"walking through a meadow",
			"a sunlit countryside",
			null
		);

		String prompt2 = s2i2.getPrompt();
		assertNotNull("Pass 2 prompt should not be null", prompt2);
		assertTrue("Pass 2 should mention adding person", prompt2.contains("Add the person from the reference portrait"));
		assertTrue("Pass 2 should describe existing person", prompt2.contains("The existing person in the scene is a tall man with brown hair"));
		assertTrue("Pass 2 should keep existing person", prompt2.contains("Keep the existing person"));
		assertTrue("Pass 2 prompt should contain new char desc", prompt2.contains("a short woman with red hair"));
		assertTrue("Pass 2 prompt should contain scene action", prompt2.contains("walking through a meadow"));
		logger.info("Pass 2 prompt: " + prompt2);
	}

	@Test
	public void testNewKontextSceneTxt2ImgNullDescriptions() {
		logger.info("testNewKontextSceneTxt2ImgNullDescriptions");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(1, null, null, null, null, null);
		assertNotNull("SWTxt2Img should not be null", s2i);

		String prompt = s2i.getPrompt();
		assertNotNull("Prompt should not be null", prompt);
		assertTrue("Prompt should have base instruction", prompt.contains("Place the person from the reference portrait"));
		assertTrue("Prompt should not contain 'null'", !prompt.contains("null"));
		logger.info("Null-safe prompt: " + prompt);
	}

	@Test
	public void testNewKontextSceneTxt2ImgSerialization() {
		logger.info("testNewKontextSceneTxt2ImgSerialization");

		SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(1,
			"a man with blue eyes",
			null,
			"sitting at a cafe",
			"Paris street",
			null
		);

		String json = JSONUtil.exportObject(s2i);
		assertNotNull("JSON should not be null", json);
		assertTrue("JSON should contain model", json.contains("flux1Kontext_flux1KontextDev"));
		assertTrue("JSON should contain setting in prompt", json.contains("Paris street"));
		assertTrue("JSON should contain sampler=euler", json.contains("euler"));

		java.io.File dir = new java.io.File(OUTPUT_DIR);
		if (!dir.exists()) dir.mkdirs();
		FileUtil.emitFile(OUTPUT_DIR + "/kontext-request.json", json.getBytes());

		logger.info("Serialized JSON length: " + json.length());
		logger.info("JSON: " + json.substring(0, Math.min(500, json.length())));
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
			SDUtil.randomSDConfig(),
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
	@Test
	public void testKontextSceneWithOlioCharacters() throws Exception {
		logger.info("testKontextSceneWithOlioCharacters");

		String swarmServer = testProperties.getProperty("test.swarm.server");
		if (swarmServer == null || swarmServer.isEmpty()) {
			logger.warn("test.swarm.server not configured — skipping");
			return;
		}

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

		if (ready.size() < 2) {
			logger.warn("Could not prepare 2 characters with apparel + portrait — skipping");
			return;
		}

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
			logger.warn("Landscape generation failed — SwarmUI may be unavailable");
			return;
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

		/// Step 5: PASS 1 — Place person 1 into the landscape using promptImages
		logger.info("=== Kontext Pass 1: Place " + person1.get(FieldNames.FIELD_NAME) + " into landscape ===");

		SWTxt2Img s2iPass1 = SWUtil.newKontextSceneTxt2Img(1, desc1, null, sceneDesc, settingDesc, null);
		List<String> pass1PromptImages = new ArrayList<>();
		pass1PromptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(portrait1Bytes));
		pass1PromptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(landscapeBytes));
		s2iPass1.setPromptImages(pass1PromptImages);
		logger.info("Pass 1 prompt: " + s2iPass1.getPrompt());
		logger.info("Pass 1 promptImages count: " + pass1PromptImages.size());

		List<BaseRecord> pass1Images = sdu.createSceneImage(testUser, "~/Gallery",
			"Kontext Pass1 - " + person1.get("firstName") + " - " + UUID.randomUUID().toString(),
			s2iPass1, person1.get(FieldNames.FIELD_OBJECT_ID), null);

		if (pass1Images.isEmpty()) {
			logger.warn("Kontext pass 1 returned no images — FLUX Kontext model may not be available");
			return;
		}

		byte[] pass1Bytes = pass1Images.get(0).get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("Pass 1 result should have bytes", pass1Bytes);
		exportImage("kontext-pass1-result.png", pass1Bytes);
		logger.info("Pass 1 result: " + pass1Bytes.length + " bytes");

		/// Step 6: PASS 2 — Add person 2 into the pass 1 result using promptImages
		logger.info("=== Kontext Pass 2: Add " + person2.get(FieldNames.FIELD_NAME) + " to scene ===");

		SWTxt2Img s2iPass2 = SWUtil.newKontextSceneTxt2Img(2, desc2, desc1, sceneDesc, settingDesc, null);
		List<String> pass2PromptImages = new ArrayList<>();
		pass2PromptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(portrait2Bytes));
		pass2PromptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(pass1Bytes));
		s2iPass2.setPromptImages(pass2PromptImages);
		logger.info("Pass 2 prompt: " + s2iPass2.getPrompt());
		logger.info("Pass 2 promptImages count: " + pass2PromptImages.size());

		List<BaseRecord> sceneImages = sdu.createSceneImage(testUser, "~/Gallery",
			"Kontext Scene - " + person1.get("firstName") + " & " + person2.get("firstName") + " - " + UUID.randomUUID().toString(),
			s2iPass2,
			person1.get(FieldNames.FIELD_OBJECT_ID),
			person2.get(FieldNames.FIELD_OBJECT_ID));

		if (sceneImages.isEmpty()) {
			logger.warn("Kontext pass 2 returned no images — FLUX Kontext model may not be available");
			return;
		}

		for (int i = 0; i < sceneImages.size(); i++) {
			byte[] sceneBytes = sceneImages.get(i).get(FieldNames.FIELD_BYTE_STORE);
			assertNotNull("Scene image " + i + " should have bytes", sceneBytes);
			exportImage("kontext-scene-result-" + i + ".png", sceneBytes);
			logger.info("Final scene " + i + ": " + sceneBytes.length + " bytes");
		}

		logger.info("testKontextSceneWithOlioCharacters PASSED — 2-pass pipeline produced " + sceneImages.size() + " scene image(s)");
	}
}
