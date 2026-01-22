package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;
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
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111OverrideSettings;
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111Response;
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111Txt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWImageInfo;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWSessionResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

import com.drew.imaging.ImageProcessingException;

import jakarta.ws.rs.core.MediaType;

public class TestSD extends BaseTest {
	
	private String universeName = "Universe 3";
	private String worldName = "World 3";
	private String worldPath = "~/Worlds";

	/*
	@Test
	public void TestSDImageToText() {
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		OlioContext ctx = getContext(testUser1);
		BaseRecord[] realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		BaseRecord popGrp = realms[0].get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Expected a population group", popGrp);
		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(ctx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);
		String setting = NarrativeUtil.getRandomSetting();
		SDUtil sdu = new SDUtil();
		for(BaseRecord per : pop) {
			List<BaseRecord> nars = NarrativeUtil.getCreateNarrative(ctx, Arrays.asList(new BaseRecord[] {per}), setting);
			BaseRecord nar = nars.get(0);
			IOSystem.getActiveContext().getReader().populate(nar, new String[] {"images"});
			List<BaseRecord> images = nar.get("images");
			if(images.size() == 0) {
				List<BaseRecord> bl = sdu.createPersonImage(testUser1, per, "Photo Op", null, "professional portrait", 40, 3);		
				for(BaseRecord b1 : bl) {
					IOSystem.getActiveContext().getMemberUtil().member(testUser1, nar, "images", b1, null, true);
					FileUtil.emitFile("./img-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
				}

			}
			break;
		}

	}
	*/
	/*
	private OlioContext getContext(BaseRecord user) {
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		AuditUtil.setLogToConsole(false);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			testProperties.getProperty("test.datagen.path"),
			worldPath,
			universeName,
			worldName,
			new String[] {},
			2,
			50,
			false,
			false
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new GridSquareLocationInitializationRule(),
			new LocationPlannerRule(),
			new GenericItemDataLoadRule()
		}));
		
		// Increment24HourRule incRule = new Increment24HourRule();
		// incRule.setIncrementType(TimeEnumType.HOUR);
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
			new Increment24HourRule(),
			new HierarchicalNeedsEvolveRule()
		}));

		OlioContext octx = new OlioContext(cfg);
		octx.initialize();
		assertNotNull("Root location is null", octx.getRootLocation());
		
		BaseRecord evt = octx.startOrContinueEpoch();
		BaseRecord levt = null;
		BaseRecord cevt = null;
		assertNotNull("Epoch is null", evt);
		BaseRecord[] locs = octx.getLocations();
		for(BaseRecord lrec : locs) {
			levt = octx.startOrContinueLocationEpoch(lrec);
			assertNotNull("Location epoch is null", levt);
			cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();

			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(octx, null, octx.getPopulation(lrec));
			ItemUtil.showerWithMoney(octx, octx.getPopulation(lrec));
			Queue.processQueue();
		}

		BaseRecord[] realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		AuditUtil.setLogToConsole(true);
		return octx;
	}
	*/
	
	private BaseRecord getSwarmConfig() {
		BaseRecord sdConfig = SDUtil.randomSDConfig();
		//sdConfig.set("prompt", testProperties.getProperty("test.swarm.prompt"));
		//sdConfig.set("negativePrompt", testProperties.getProperty("test.swarm.negativePrompt"));
		sdConfig.setValue("model", testProperties.getProperty("test.swarm.model"));
		sdConfig.setValue("refinerModel", testProperties.getProperty("test.swarm.refinerModel"));
		sdConfig.setValue("scheduler", "Karras");
		sdConfig.setValue("sampler", "dpm_2");
		sdConfig.setValue("hires", true);
		sdConfig.setValue("refinerUpscale", 2);
		return sdConfig;
	}
	
	@Test
	public void TestCreatePersonImage() {
		logger.info("Test Create Person Image");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String dataPath = testProperties.getProperty("test.datagen.path");
		OlioContext ctx = OlioTestUtil.getContext(orgContext, dataPath);
		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord popGrp = realms.get(0).get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Expected a population group", popGrp);
		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(ctx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);
		String setting = NarrativeUtil.getRandomSetting();
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, testProperties.getProperty("test.swarm.server"));

		ApparelUtil.outfitAndStage(ctx, null, pop);
		ItemUtil.showerWithMoney(ctx, pop);
		Queue.processQueue();
		
		Random rand = new Random();
		BaseRecord per = pop.get(rand.nextInt(pop.size()));
		
		logger.info("Creating image for " + per.get(FieldNames.FIELD_NAME));
		BaseRecord sdCfg = getSwarmConfig();
		//BaseRecord user, BaseRecord person, String groupPath, BaseRecord sdConfig, String name, String setting, String pictureType, String bodyType, String verb, int steps, int batch, boolean hires, int seed)
	//public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, BaseRecord sdConfig, String name, String setting, String pictureType, String bodyType, String verb, int steps, int batch, boolean hires, int seed) {
		
		List<BaseRecord> bl = sdu.createPersonImage(testUser1, per, "~/Gallery", sdCfg, "Photo Op - " + per.get(FieldNames.FIELD_NAME) + " - " + UUID.randomUUID().toString(), "random", "professional portrait", "full body", "walking", 40, 3, false, -1);		
		assertTrue("Expected images to be created", bl.size() > 0);
		for(BaseRecord b1 : bl) {
			FileUtil.emitFile("./img-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
		}
		
	
	}
	
	public void TestSwarmAPI() {
		logger.info("Test Swarm API");
		String server = testProperties.getProperty("test.swarm.server");
		String session = getAnonymousSession(server);
		assertNotNull("Session is null", session);
		
		SWTxt2Img req = new SWTxt2Img();
		req.setSession_id(session);
		req.setPrompt(testProperties.getProperty("test.swarm.prompt"));
		req.setNegativePrompt(testProperties.getProperty("test.swarm.negativePrompt"));
		req.setModel(testProperties.getProperty("test.swarm.model"));
		
		req.setScheduler("Karras");
		req.setSampler("dpm_2");
		
		SWImageResponse resp = txt2img(server, req);
		assertNotNull("Response is null", resp);
		assertTrue("No images returned", resp.getImages().size() > 0);
		for(String path : resp.getImages()) {
			String name = Paths.get(path).getFileName().toString();
			byte[] data = ClientUtil.get(byte[].class, ClientUtil.getResource(server + "/" + path), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
			assertNotNull("Image data is null", data);
			FileUtil.emitFile("./" + name, data);

			logger.info("Extracting metadata from image");
			
	        try {
				SWImageInfo info = SWUtil.extractInfo(data);
				assertNotNull("Image info was null", info);
				assertNotNull("Image params was null", info.getImageParams());
				logger.info("Image Info: " + info.getImageParams().getSeed());


	        } catch (IOException | ImageProcessingException e) {
	            e.printStackTrace();
	        }
	    }
		
		logger.info("Test refiner");
		req.setRefinerModel(testProperties.getProperty("test.swarm.refinerModel"));
		req.setRefinerMethod("PostApply");
		req.setRefinerUpscaleMethod("pixel-lanczos");
		req.setRefinerSteps(20);
		req.setRefinerControlPercentage(0.2);
		req.setCfgScale(6);
		req.setRefinerUpscale(2);
		
		SWImageResponse resp2 = txt2img(server, req);
		assertNotNull("Response is null", resp2);
		assertTrue("No images returned", resp2.getImages().size() > 0);
		for(String path : resp2.getImages()) {
			String name = Paths.get(path).getFileName().toString();
			byte[] data = ClientUtil.get(byte[].class, ClientUtil.getResource(server + "/" + path), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
			assertNotNull("Image data is null", data);
			FileUtil.emitFile("./" + name, data);
		}

		//\"refinermodel\": \"realmixXL_v10.safetensors\", \"refinermethod\": \"PostApply\", \"refinerupscalemethod\": \"pixel-lanczos\", \"refinersteps\": 40, \"refinercontrolpercentage\": 0.2, \"refinerupscale\": 2, \"refinercfgscale\": 6}"

		
		//logger.info(JSONUtil.exportObject(req));
	}

	
	public String getAnonymousSession(String server) {
		// curl -X POST -H "Content-Type: application/json" -d "{}" http://192.168.1.42:7801/API/GetNewSession
		
		SWSessionResponse test = ClientUtil.post(SWSessionResponse.class, ClientUtil.getResource(server + "/API/GetNewSession"), "{}", MediaType.APPLICATION_JSON_TYPE);
		if (test == null) {
			logger.error("Session response was null");
			return null;
		}
		return test.getSession_id();
		
	}
	public SWImageResponse txt2img(String server, SWTxt2Img req) {
		return ClientUtil.post(SWImageResponse.class, ClientUtil.getResource(server + "/API/GenerateText2Image"), JSONUtil.exportObject(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	public void TestSDImageToTextMARK() {
		logger.info("Test SD API");
		
		logger.info("Test import");
		String txt = FileUtil.getFileAsString("./samp.doesnt.exist.json");
		assertTrue("Expected data", txt != null && txt.length() > 0);

		Auto1111Response is = JSONUtil.importObject(txt, Auto1111Response.class);
		assertNotNull("Import was null", is);
		SecureRandom rand = new SecureRandom();
		
		logger.info("Test prompt");
		Auto1111Txt2Img s2i = new Auto1111Txt2Img();
		s2i.setPrompt("8k highly detailed professional photograph ((highest quality)) ((ultra realistic)) (full body:1.5) of a gorgeous woman (twenty-four year old:1.5) (24 yo:1.5) Irish, (long tangled red hair), (emerald green eyes), wearing a cowgirl outfit and hat. Sharp focus, ultra sharp image. Natural light only.");
		// s2i.setAll_prompts(new String[] {s2i.getPrompt()});
		s2i.setNegative_prompt("Washed out colors, lifeless, illogical, wonky, boring, bland, ugly, disgusting, uncanny, dumb, illogical, bad anatomy, errors, glitches, mistakes, horrid, low resolution, pixilated, blurry, out of focus, low res, fugly, mutated, distorted, melting, cropped, disproportionate, weird, wonky, low quality, compressed, muddy colors, overexposed, bland, censored, mosaic, ugliness, rotten, fake, low poly, lacking detail, watermark, malformed, failed, failure, extra fingers, cloned face, missing legs, extra arms, fused fingers, too many fingers, poorly drawn face");
		// s2i.setAll_negative_prompts(new String[] {s2i.getNegative_prompt()});
		/*
		s2i.setSd_model_hash("e6bb9ea85b");
		s2i.setSd_model_name("sdXL_v10VAEFix");
		s2i.setSd_vae_hash("235745af8d");
		s2i.setSd_vae_name("sdxl_vae.safetensors");
		*/
		s2i.setSeed(-1);
		s2i.setSteps(50);
		s2i.setSubseed(0);
		s2i.setDenoising_strength(0.7);
		s2i.setWidth(512);
		s2i.setHeight(512);
		/*
		s2i.setSeed(370982057);
		s2i.setAll_seeds(new Integer[] {370982057});
		
		s2i.setSubseed(924289317);
		s2i.setAll_subseeds(new Integer[] {924289317});
		*/
		s2i.setScheduler("Karras");
		/*
		SDExtraGenerationParams sde = new SDExtraGenerationParams();
		sde.setLoraHashes("add-detail-xl: 9c783c8ce46c, xl_more_art-full_v1: fe3b4816be83");
		//sde.setScheduleType("Karras");
		s2i.setExtra_generation_params(sde);
		*/
		
		s2i.setSampler_name("DPM++ 2M");
		s2i.setSteps(40);
		//logger.info(JSONUtil.exportObject(s2i));
		
		Auto1111OverrideSettings sos = new Auto1111OverrideSettings();
		sos.setSd_model_checkpoint("sdXL_v10VAEFix");
		sos.setSd_vae("sdxl_vae.safetensors");
		SDUtil sdu = new SDUtil(SDAPIEnumType.AUTO1111);
		
		
		String txt2 = FileUtil.getFileAsString("./samp2.doesnt.exist.json");
		
		Auto1111Txt2Img is2 = JSONUtil.importObject(txt2, Auto1111Txt2Img.class);
		assertNotNull("Obj is null", is2);
		//logger.info(JSONUtil.exportObject(is2));
		Auto1111Response rep = sdu.txt2img(s2i);
		assertNotNull("Rep is null", rep);
		//logger.info(JSONUtil.exportObject(rep));
		//int seed = rep.getParameters().getSeed();
		int seed = 1;
		int counter = 1;
		for(String bai : rep.getImages()) {
			byte[] data = BinaryUtil.fromBase64(bai.getBytes());
			FileUtil.emitFile("./img-" + seed + "-" + (counter++) + ".png", data);
		}
	}
}
