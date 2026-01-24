package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Test;

public class TestSDStyles extends BaseTest {

	private BaseRecord getSwarmConfig() {
		BaseRecord sdConfig = SDUtil.randomSDConfig();
		sdConfig.setValue("model", testProperties.getProperty("test.swarm.model"));
		sdConfig.setValue("refinerModel", testProperties.getProperty("test.swarm.refinerModel"));
		sdConfig.setValue("scheduler", "Karras");
		sdConfig.setValue("sampler", "dpm_2");
		sdConfig.setValue("hires", false);
		sdConfig.setValue("steps", 20);
		return sdConfig;
	}

	private BaseRecord getSwarmConfigWithStyle(String style) {
		BaseRecord sdConfig = getSwarmConfig();
		sdConfig.setValue("style", style);
		BaseRecord cfgData = SDUtil.getConfigData();
		switch(style) {
			case "art":
				sdConfig.setValue("artStyle", SDUtil.randomSDConfigValue(cfgData, "artStyles"));
				break;
			case "photograph":
				sdConfig.setValue("colorProcess", SDUtil.randomSDConfigValue(cfgData, "colorProcesses"));
				sdConfig.setValue("stillCamera", SDUtil.randomSDConfigValue(cfgData, "stillCameras"));
				sdConfig.setValue("photographer", SDUtil.randomSDConfigValue(cfgData, "photographers"));
				sdConfig.setValue("lens", SDUtil.randomSDConfigValue(cfgData, "lenses"));
				sdConfig.setValue("film", SDUtil.randomSDConfigValue(cfgData, "films"));
				break;
			case "movie":
				sdConfig.setValue("colorProcess", SDUtil.randomSDConfigValue(cfgData, "colorProcesses"));
				sdConfig.setValue("movieFilm", SDUtil.randomSDConfigValue(cfgData, "movieFilms"));
				sdConfig.setValue("movieCamera", SDUtil.randomSDConfigValue(cfgData, "movieCameras"));
				sdConfig.setValue("director", SDUtil.randomSDConfigValue(cfgData, "directors"));
				break;
			case "selfie":
				sdConfig.setValue("selfiePhone", SDUtil.randomSDConfigValue(cfgData, "selfiePhones"));
				sdConfig.setValue("selfieAngle", SDUtil.randomSDConfigValue(cfgData, "selfieAngles"));
				sdConfig.setValue("selfieLighting", SDUtil.randomSDConfigValue(cfgData, "selfieLightings"));
				break;
			case "anime":
				sdConfig.setValue("animeStudio", SDUtil.randomSDConfigValue(cfgData, "animeStudios"));
				sdConfig.setValue("animeEra", SDUtil.randomSDConfigValue(cfgData, "animeEras"));
				break;
			case "portrait":
				sdConfig.setValue("portraitLighting", SDUtil.randomSDConfigValue(cfgData, "portraitLightings"));
				sdConfig.setValue("portraitBackdrop", SDUtil.randomSDConfigValue(cfgData, "portraitBackdrops"));
				sdConfig.setValue("photographer", SDUtil.randomSDConfigValue(cfgData, "photographers"));
				break;
			case "comic":
				sdConfig.setValue("comicPublisher", SDUtil.randomSDConfigValue(cfgData, "comicPublishers"));
				sdConfig.setValue("comicEra", SDUtil.randomSDConfigValue(cfgData, "comicEras"));
				sdConfig.setValue("comicColoring", SDUtil.randomSDConfigValue(cfgData, "comicColorings"));
				break;
			case "digitalArt":
				sdConfig.setValue("digitalMedium", SDUtil.randomSDConfigValue(cfgData, "digitalMediums"));
				sdConfig.setValue("digitalSoftware", SDUtil.randomSDConfigValue(cfgData, "digitalSoftwares"));
				sdConfig.setValue("digitalArtist", SDUtil.randomSDConfigValue(cfgData, "digitalArtists"));
				break;
			case "fashion":
				sdConfig.setValue("fashionMagazine", SDUtil.randomSDConfigValue(cfgData, "fashionMagazines"));
				sdConfig.setValue("fashionDecade", SDUtil.randomSDConfigValue(cfgData, "fashionDecades"));
				sdConfig.setValue("photographer", SDUtil.randomSDConfigValue(cfgData, "photographers"));
				break;
			case "vintage":
				sdConfig.setValue("vintageDecade", SDUtil.randomSDConfigValue(cfgData, "vintageDecades"));
				sdConfig.setValue("vintageProcessing", SDUtil.randomSDConfigValue(cfgData, "vintageProcessings"));
				sdConfig.setValue("vintageCamera", SDUtil.randomSDConfigValue(cfgData, "vintageCameras"));
				break;
			case "custom":
				sdConfig.setValue("customPrompt", "watercolor painting with pastel colors and soft lighting");
				break;
		}
		return sdConfig;
	}

	@Test
	public void TestRandomConfigAllStyles() {
		Set<String> seenStyles = new HashSet<>();
		for(int i = 0; i < 300; i++) {
			BaseRecord cfg = SDUtil.randomSDConfig();
			assertNotNull("Config should not be null", cfg);
			String style = cfg.get("style");
			assertNotNull("Style should not be null", style);
			seenStyles.add(style);
			verifyStyleFields(cfg, style);
		}
		logger.info("Seen styles: " + seenStyles);
		assertTrue("Expected at least 8 distinct styles, got " + seenStyles.size(), seenStyles.size() >= 8);
	}

	@Test
	public void TestGetSDConfigPromptAllStyles() {
		String[] styles = {"art", "movie", "photograph", "selfie", "anime", "portrait",
				"comic", "digitalArt", "fashion", "vintage", "custom"};
		for(String style : styles) {
			BaseRecord cfg = getSwarmConfigWithStyle(style);
			String prompt = SDUtil.getSDConfigPrompt(cfg);
			assertNotNull("Prompt null for style: " + style, prompt);
			assertTrue("Prompt too short for style: " + style + " = '" + prompt + "'", prompt.length() > 5);
			logger.info(style + ": " + prompt);
		}
	}

	@Test
	public void TestCreatePersonImageWithStyles() {
		logger.info("Test Create Person Image With All Styles");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
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

		Random rand = new Random();
		BaseRecord per = pop.get(rand.nextInt(pop.size()));
		logger.info("Using character: " + per.get(FieldNames.FIELD_NAME));

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, testProperties.getProperty("test.swarm.server"));
		String galleryPath = "~/Gallery/StyleTest-" + UUID.randomUUID().toString();

		String[] styles = {"selfie", "anime", "portrait", "comic", "digitalArt", "fashion", "vintage"};
		for(String style : styles) {
			logger.info("Testing style: " + style);
			BaseRecord sdCfg = getSwarmConfigWithStyle(style);
			String prompt = SDUtil.getSDConfigPrompt(sdCfg);
			logger.info("Prompt: " + prompt);

			List<BaseRecord> bl = sdu.createPersonImage(testUser1, per, galleryPath, sdCfg,
					"StyleTest-" + style + "-" + per.get(FieldNames.FIELD_NAME),
					"random", "professional portrait", "full body", null, 20, 1, false, -1);
			assertTrue("Expected image for style: " + style, bl.size() > 0);
			for(BaseRecord b1 : bl) {
				FileUtil.emitFile("./img-style-" + style + "-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
			}
		}
	}

	@Test
	public void TestCreateImageWithoutCharPerson() {
		logger.info("Test Create Image Without CharPerson (data.data style)");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, testProperties.getProperty("test.swarm.server"));
		String galleryPath = "~/Gallery/NoCharTest-" + UUID.randomUUID().toString();

		// Test with a custom prompt (no character dependency)
		BaseRecord sdCfg = getSwarmConfigWithStyle("custom");
		sdCfg.setValue("customPrompt", "A serene mountain landscape at sunset with golden light");
		sdCfg.setValue("description", "Beautiful nature scene");

		List<BaseRecord> bl = sdu.createImage(testUser1, galleryPath, sdCfg, "CustomLandscape", 1, false, -1);
		assertTrue("Expected image from createImage", bl.size() > 0);
		for(BaseRecord b1 : bl) {
			FileUtil.emitFile("./img-nochar-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
		}

		// Test img2img using the first generated image as reference
		if(bl.size() > 0) {
			String refImageId = bl.get(0).get(FieldNames.FIELD_OBJECT_ID);
			logger.info("Testing img2img with reference: " + refImageId);

			BaseRecord img2imgCfg = getSwarmConfigWithStyle("anime");
			img2imgCfg.setValue("referenceImageId", refImageId);
			img2imgCfg.setValue("denoisingStrength", 0.6);
			img2imgCfg.setValue("description", "Anime version of a mountain landscape");

			List<BaseRecord> img2imgResults = sdu.createImage(testUser1, galleryPath, img2imgCfg, "Img2ImgAnime", 1, false, -1);
			assertTrue("Expected image from img2img", img2imgResults.size() > 0);
			for(BaseRecord b1 : img2imgResults) {
				FileUtil.emitFile("./img-img2img-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
			}
		}
	}

	private void verifyStyleFields(BaseRecord cfg, String style) {
		switch(style) {
			case "art":
				assertNotNull("artStyle should be set for art", cfg.get("artStyle"));
				break;
			case "photograph":
				assertNotNull("stillCamera should be set", cfg.get("stillCamera"));
				assertNotNull("photographer should be set", cfg.get("photographer"));
				assertNotNull("lens should be set", cfg.get("lens"));
				assertNotNull("film should be set", cfg.get("film"));
				assertNotNull("colorProcess should be set", cfg.get("colorProcess"));
				break;
			case "movie":
				assertNotNull("movieCamera should be set", cfg.get("movieCamera"));
				assertNotNull("movieFilm should be set", cfg.get("movieFilm"));
				assertNotNull("director should be set", cfg.get("director"));
				assertNotNull("colorProcess should be set", cfg.get("colorProcess"));
				break;
			case "selfie":
				assertNotNull("selfiePhone should be set", cfg.get("selfiePhone"));
				assertNotNull("selfieAngle should be set", cfg.get("selfieAngle"));
				assertNotNull("selfieLighting should be set", cfg.get("selfieLighting"));
				break;
			case "anime":
				assertNotNull("animeStudio should be set", cfg.get("animeStudio"));
				assertNotNull("animeEra should be set", cfg.get("animeEra"));
				break;
			case "portrait":
				assertNotNull("portraitLighting should be set", cfg.get("portraitLighting"));
				assertNotNull("portraitBackdrop should be set", cfg.get("portraitBackdrop"));
				assertNotNull("photographer should be set", cfg.get("photographer"));
				break;
			case "comic":
				assertNotNull("comicPublisher should be set", cfg.get("comicPublisher"));
				assertNotNull("comicEra should be set", cfg.get("comicEra"));
				assertNotNull("comicColoring should be set", cfg.get("comicColoring"));
				break;
			case "digitalArt":
				assertNotNull("digitalMedium should be set", cfg.get("digitalMedium"));
				assertNotNull("digitalSoftware should be set", cfg.get("digitalSoftware"));
				assertNotNull("digitalArtist should be set", cfg.get("digitalArtist"));
				break;
			case "fashion":
				assertNotNull("fashionMagazine should be set", cfg.get("fashionMagazine"));
				assertNotNull("fashionDecade should be set", cfg.get("fashionDecade"));
				assertNotNull("photographer should be set", cfg.get("photographer"));
				break;
			case "vintage":
				assertNotNull("vintageDecade should be set", cfg.get("vintageDecade"));
				assertNotNull("vintageProcessing should be set", cfg.get("vintageProcessing"));
				assertNotNull("vintageCamera should be set", cfg.get("vintageCamera"));
				break;
		}
	}

}
