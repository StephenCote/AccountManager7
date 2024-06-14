package org.cote.accountmanager.olio.sd;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class SDUtil {
	public static final Logger logger = LogManager.getLogger(SDUtil.class);
	private String autoserver = "http://localhost:7860";
	private SecureRandom rand = new SecureRandom();
	
	public SDResponse txt2img(SDTxt2Img req) {
		//SDRequest sreq = new SDRequest();
		//sreq.setData(req);
		return ClientUtil.post(SDResponse.class, ClientUtil.getResource(autoserver + "/sdapi/v1/txt2img"), JSONUtil.exportObject(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	public SDResponse txt2img(String req) {
		/// "{\"data\":" + req + "}"
		return ClientUtil.postJSON(SDResponse.class, ClientUtil.getResource(autoserver + "/sdapi/v1/txt2img"), req, MediaType.APPLICATION_JSON_TYPE);
	}
	
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String name) {
		return createPersonImage(user, person, name, null, "professional portrait", 50, 1);
	}
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String name, String setting, String pictureType, int steps, int batch) {
		return createPersonImage(user, person, name, null, "professional portrait", steps, batch, false, 0);
	}	
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String name, String setting, String pictureType, int steps, int batch, boolean hires, int seed) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/GalleryHome/Characters", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		List<BaseRecord> datas = new ArrayList<>();
		int rando = Math.abs(rand.nextInt());
		try {
			SDTxt2Img s2i = newTxt2Img(person, setting, pictureType, steps);
			s2i.setBatch_size(batch);
			if(hires) {
				logger.info("Apply hires/refiner configuration");
				applyHRRefiner(s2i);
			}
			logger.info("Generating image for " + person.get(FieldNames.FIELD_NAME));
			SDResponse rep = txt2img(s2i);

			if(seed <= 0) {
				seed = rep.getParameters().getSeed();
			}
			int counter = 1;
			int seedl = seed;
			for(String bai : rep.getImages()) {
				logger.info("Processing image " + counter);
				byte[] datab = BinaryUtil.fromBase64(bai.getBytes());
				
				Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				String dname = person.get(FieldNames.FIELD_NAME) + " - " + name + " - " + counter + " - " + rando + " - " + seedl;
				q.field(FieldNames.FIELD_NAME, dname);
				BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);

				if(data == null) {
					ParameterList clist = ParameterList.newParameterList("path", "~/GalleryHome/Characters");
					clist.parameter("name", dname);
					data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
					data.set(FieldNames.FIELD_BYTE_STORE, datab);
					data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
					AttributeUtil.addAttribute(data, "seed", seedl);
					AttributeUtil.addAttribute(data, "character", person.get(FieldNames.FIELD_URN));
					AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i));
					IOSystem.getActiveContext().getAccessPoint().create(user, data);
				}
				else {
					data.set(FieldNames.FIELD_BYTE_STORE, datab);
					IOSystem.getActiveContext().getAccessPoint().update(user, data);
				}
				datas.add(data);
				counter++;
				seedl = seedl + 1;
			}
		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		return datas;
	}
	/*
	public BaseRecord createPersonImage(BaseRecord user, BaseRecord person, String name, String setting, String pictureType, int steps, int batch) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/GalleryHome/Characters", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		String dname = person.get(FieldNames.FIELD_NAME) + " - " + name;
		q.field(FieldNames.FIELD_NAME, dname);
		BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);
		if(data == null) {
			ParameterList clist = ParameterList.newParameterList("path", "~/Gallery/Characters");
			clist.parameter("name", dname);

			BaseRecord dat = null;
			try {
				dat = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
				SDTxt2Img s2i = newTxt2Img(person, setting, pictureType, steps);
				byte[] datab = new byte[0];
				SDResponse rep = txt2img(s2i);
				
				int seed = rep.getParameters().getSeed();
				int counter = 1;
				for(String bai : rep.getImages()) {
					datab = BinaryUtil.fromBase64(bai.getBytes());
					//FileUtil.emitFile("./img-" + per1.get("firstName") + "-" + seed + "-" + (counter++) + ".png", data);
				}
			}
			catch(FactoryException e) {
				logger.error(e);
			}
		}
		return data;
	}
	*/
	public void applyHRRefiner(SDTxt2Img s2i) {
		/*
		 * 	"extra_generation_params": {
		"Denoising strength": 0.7,
		"Hires prompt": null,
		"Hires negative prompt": null,
		"Hires schedule type": null,
		"Hires upscale": 2,
		"Hires upscaler": "Latent",
		"Lora hashes": "add-detail-xl: 9c783c8ce46c, xl_more_art-full_v1: fe3b4816be83",
		"TI hashes": "negativeXL_D: fff5d51ab655, negativeXL_D: fff5d51ab655",
		"Schedule type": "Karras",
		"Refiner": "Juggernaut_X_RunDiffusion_Hyper [010be7341c]",
		"Refiner switch at": 0.8
	},
		 */
		SDExtraGenerationParams sgp = s2i.getExtra_generation_params();
		if(sgp == null) {
			sgp = new SDExtraGenerationParams();
			s2i.setExtra_generation_params(sgp);
		}
		//sgp.setDenoisingStrength(0.45);
		//sgp.setHiresUpscale(2);
		//sgp.setHiresUpscaler("Latent");
		sgp.setRefiner("Juggernaut_X_RunDiffusion_Hyper");
		sgp.setRefinerSwitchAt(0.75);
		
		s2i.setDenoising_strength(0.45);
		//s2i.setRefiner_checkpoint("Juggernaut_X_RunDiffusion_Hyper");
		//s2i.setRefiner_switch_at(0.75);
		s2i.setHr_scale(2);
		s2i.setHr_upscaler("Latent");
		s2i.setEnable_hr(true);
		
		SDAlwaysOnScripts sas = new SDAlwaysOnScripts();
		SDRefiner sdr = new SDRefiner();
		sdr.setArgs(new Object[] {true, "Juggernaut_X_RunDiffusion_Hyper", 0.8});
		sas.setRefiner(sdr);
		s2i.setAlwayson_scripts(sas);
		
		
	}
	public SDTxt2Img newTxt2Img(BaseRecord person, String setting, String pictureType, int steps) {
		SDTxt2Img s2i = new SDTxt2Img();
		s2i.setPrompt(NarrativeUtil.getSDPrompt(null,  ProfileUtil.getProfile(null, person), person, setting, pictureType));
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
		
		SDOverrideSettings sos = new SDOverrideSettings();
		sos.setSd_model_checkpoint("sdXL_v10VAEFix");
		sos.setSd_vae("sdxl_vae.safetensors");
		s2i.setOverride_settings(sos);
		s2i.setOverride_settings_restore_afterwards(true);
		
		return s2i;
	}
	
}
