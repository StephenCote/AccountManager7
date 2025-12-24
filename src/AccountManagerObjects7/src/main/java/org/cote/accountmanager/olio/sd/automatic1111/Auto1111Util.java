package org.cote.accountmanager.olio.sd.automatic1111;

import java.security.SecureRandom;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.record.BaseRecord;

public class Auto1111Util {
	private static SecureRandom rand = new SecureRandom();
	private static String modelCheckpoint = "sdXL_v10VAEFix";
	private static String modelVae = "sdxl_vae.safetensors";
	private static String refiner = "juggernautXL_juggXIByRundiffusion";
	private static String scheduler = "Karras";
	private static String sampler = "DPM++ SDE";
	public static Auto1111Txt2Img newTxt2Img(BaseRecord person, BaseRecord sdConfig, String setting, String pictureType, String bodyType, String verb, int steps) {
		Auto1111Txt2Img s2i = new Auto1111Txt2Img();
		s2i.setPrompt(NarrativeUtil.getSDPrompt(null,  ProfileUtil.getProfile(null, person), person, sdConfig, setting, pictureType, bodyType, verb));
		s2i.setNegative_prompt(NarrativeUtil.getSDNegativePrompt(person));
		s2i.setSeed(Math.abs(rand.nextInt()));
		s2i.setSteps(steps);
		s2i.setSubseed(0);
		s2i.setDenoising_strength(0.7);
		s2i.setWidth(512);
		s2i.setHeight(512);
		s2i.setScheduler("Karras");
		s2i.setSampler_name("DPM++ 2M");
		s2i.setSteps(steps);
		s2i.setCfg_scale(8);
		
		Auto1111OverrideSettings sos = new Auto1111OverrideSettings();
		sos.setSd_model_checkpoint(modelCheckpoint);
		sos.setSd_vae(modelVae);
		s2i.setOverride_settings(sos);
		s2i.setOverride_settings_restore_afterwards(true);
		
		return s2i;
	}
	public static void applyHRRefiner(Auto1111Txt2Img s2i) {

		Auto1111ExtraGenerationParams sgp = s2i.getExtra_generation_params();
		if(sgp == null) {
			sgp = new Auto1111ExtraGenerationParams();
			s2i.setExtra_generation_params(sgp);
		}
		//sgp.setDenoisingStrength(0.45);
		//sgp.setHiresUpscale(2);
		//sgp.setHiresUpscaler("Latent");
		sgp.setRefiner(refiner);
		sgp.setRefinerSwitchAt(0.75);
		
		s2i.setDenoising_strength(0.45);
		//s2i.setRefiner_checkpoint("Juggernaut_X_RunDiffusion_Hyper");
		// TODO: Make models configurable
		//s2i.setRefiner_checkpoint("juggernautXL_ragnarokBy");
		//s2i.setRefiner_switch_at(0.75);
		s2i.setHr_scale(2);
		s2i.setHr_upscaler("Latent");
		s2i.setEnable_hr(true);
		
		Auto1111AlwaysOnScripts sas = new Auto1111AlwaysOnScripts();
		Auto1111Refiner sdr = new Auto1111Refiner();
		sdr.setArgs(new Object[] {true, refiner, 0.8});
		sas.setRefiner(sdr);
		s2i.setAlwayson_scripts(sas);

	}
}
