package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.olio.sd.SDExtraGenerationParams;
import org.cote.accountmanager.olio.sd.SDOverrideSettings;
import org.cote.accountmanager.olio.sd.SDResponse;
import org.cote.accountmanager.olio.sd.SDTxt2Img;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestSD extends BaseTest {
	
	private String universeName = "Universe 3";
	private String worldName = "World 3";
	private String worldPath = "~/Worlds";


	@Test
	public void TestSDImageToText() {
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		OlioContext ctx = getContext(testUser1);
		BaseRecord[] realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		BaseRecord popGrp = realms[0].get("population");
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
					FileUtil.emitFile("./img-" + b1.get("name") + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
				}

			}
			break;
		}
		/*
		BaseRecord per1 = pop.get((new Random()).nextInt(pop.size()));
		SDUtil sdu = new SDUtil();
		List<BaseRecord> bl = sdu.createPersonImage(testUser1, per1, "Photo Op", null, "professional portrait", 50, 5);
		assertTrue("Expected at least one image", bl.size() > 0);
		for(BaseRecord b1 : bl) {
			FileUtil.emitFile("./img-" + b1.get("name") + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
		}
		*/
		/*
		SDUtil sdu = new SDUtil();
		try {
		SDTxt2Img t2i = sdu.newTxt2Img(per1, null, "professional portrait", 50);
		SDResponse rep = sdu.txt2img(t2i);
		assertNotNull("Rep is null", rep);
		int seed = rep.getParameters().getSeed();
		int counter = 1;
		for(String bai : rep.getImages()) {
			byte[] data = BinaryUtil.fromBase64(bai.getBytes());
			FileUtil.emitFile("./img-" + per1.get("firstName") + "-" + seed + "-" + (counter++) + ".png", data);
		}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		*/
	}
	
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
			new HierarchicalNeedsRule()
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
			octx.processQueue();
		}

		BaseRecord[] realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		AuditUtil.setLogToConsole(true);
		return octx;
	}
	
	public void TestSDImageToTextMARK() {
		logger.info("Test SD API");
		
		logger.info("Test import");
		String txt = FileUtil.getFileAsString("./samp.json");
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
		
		
		String txt2 = FileUtil.getFileAsString("./samp2.json");
		
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
