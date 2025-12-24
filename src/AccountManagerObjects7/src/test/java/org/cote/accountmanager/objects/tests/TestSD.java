package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.SecureRandom;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.automatic1111.SDOverrideSettings;
import org.cote.accountmanager.olio.sd.automatic1111.SDResponse;
import org.cote.accountmanager.olio.sd.automatic1111.SDTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWImageInfo;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWSessionResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

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
	
	@Test
	public void TestSwarmAPI() {
		logger.info("Test Swarm API");
		String session = getAnonymousSession();
		assertNotNull("Session is null", session);
		
		
		
		//logger.info
		SWTxt2Img req = new SWTxt2Img();
		req.setSession_id(session);
		req.setPrompt("4k HD high resolution realistic photograph of a(white) (irish) (woman)) with (red hair) in a futuristic alien city");
		req.setNegativeprompt("Washed out colors, illogical, disgusting, dumb, illogical, bad anatomy, errors, glitches, mistakes, horrid, low resolution, pixilated, cartoon, drawing, blurry, out of focus, low res, mutated, distorted, melting, cropped, disproportionate, wonky, low quality, compressed, muddy colors, overexposed, censored, mosaic, rotten, fake, plastic smooth skin, low poly, lacking detail, watermark, malformed, failed, failure, extra fingers, anime, cloned face, missing legs, extra arms, fused fingers, too many fingers, poorly drawn face. American Indian/Alaska Native people, Asian people, Black people, Native Hawaiian or other Pacific Islander people");
		req.setModel("lustifySDXLNSFW_endgame.safetensors");
		
		SWImageResponse resp = txt2img(req);
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
		req.setRefinerModel("realmixXL_v10.safetensors");
		req.setRefinerMethod("PostApply");
		req.setRefinerUpscaleMethod("pixel-lanczos");
		req.setRefinerSteps(20);
		req.setRefinerControlPercentage(0.2);
		req.setCfgScale(6);
		req.setRefinerUpscale(2);
		
		SWImageResponse resp2 = txt2img(req);
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

	private String server = "http://192.168.1.42:7801";
	public String getAnonymousSession() {
		// curl -X POST -H "Content-Type: application/json" -d "{}" http://192.168.1.42:7801/API/GetNewSession
		
		SWSessionResponse test = ClientUtil.post(SWSessionResponse.class, ClientUtil.getResource(server + "/API/GetNewSession"), "{}", MediaType.APPLICATION_JSON_TYPE);
		if (test == null) {
			logger.error("Session response was null");
			return null;
		}
		return test.getSession_id();
		
	}
	public SWImageResponse txt2img(SWTxt2Img req) {
		return ClientUtil.post(SWImageResponse.class, ClientUtil.getResource(server + "/API/GenerateText2Image"), JSONUtil.exportObject(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	public void TestSDImageToTextMARK() {
		logger.info("Test SD API");
		
		logger.info("Test import");
		String txt = FileUtil.getFileAsString("./samp.doesnt.exist.json");
		assertTrue("Expected data", txt != null && txt.length() > 0);

		SDResponse is = JSONUtil.importObject(txt, SDResponse.class);
		assertNotNull("Import was null", is);
		SecureRandom rand = new SecureRandom();
		
		logger.info("Test prompt");
		SDTxt2Img s2i = new SDTxt2Img();
		s2i.setPrompt("8k highly detailed professional photograph ((highest quality)) ((ultra realistic)) (full body:1.5) of a gorgeous woman (twenty-four year old:1.5) (24 yo:1.5) Irish, (long tangled red hair), (emerald green eyes), wearing a cowgirl outfit and hat. Sharp focus, ultra sharp image. Natural light only.  <lora:add-detail-xl:1> <lora:xl_more_art-full_v1:1.2>");
		// s2i.setAll_prompts(new String[] {s2i.getPrompt()});
		s2i.setNegative_prompt("Washed out colors, lifeless, illogical, wonky, boring, bland, ugly, disgusting, uncanny, dumb, illogical, bad anatomy, errors, glitches, mistakes, horrid, low resolution, pixilated, cartoon, drawing, blurry, out of focus, low res, fugly, mutated, distorted, melting, cropped, disproportionate, weird, wonky, low quality, compressed, muddy colors, overexposed, bland, censored, mosaic, ugliness, rotten, fake, plastic smooth skin, low poly, lacking detail, watermark, malformed, failed, failure, old, masculine, (busty:1.3), extra fingers, anime, cloned face, missing legs, extra arms, fused fingers, too many fingers, poorly drawn face, negativeXL_D");
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
		
		SDOverrideSettings sos = new SDOverrideSettings();
		sos.setSd_model_checkpoint("sdXL_v10VAEFix");
		sos.setSd_vae("sdxl_vae.safetensors");
		SDUtil sdu = new SDUtil();
		
		
		String txt2 = FileUtil.getFileAsString("./samp2.doesnt.exist.json");
		
		SDTxt2Img is2 = JSONUtil.importObject(txt2, SDTxt2Img.class);
		assertNotNull("Obj is null", is2);
		//logger.info(JSONUtil.exportObject(is2));
		SDResponse rep = sdu.txt2img(s2i);
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
