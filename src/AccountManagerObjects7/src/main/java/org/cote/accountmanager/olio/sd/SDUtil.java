package org.cote.accountmanager.olio.sd;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class SDUtil {
	public static final Logger logger = LogManager.getLogger(SDUtil.class);
	private String autoserver = "http://localhost:7860";
	private static SecureRandom rand = new SecureRandom();
	private int steps = 70;
	private String modelCheckpoint = "sdXL_v10VAEFix";
	private String modelVae = "sdxl_vae.safetensors";
	private String refiner = "juggernautXL_juggXIByRundiffusion";
	private String scheduler = "Karras";
	private String sampler = "DPM++ SDE"; 
	public SDUtil() {
		
	}
	
	public SDUtil(String server) {
		autoserver = server;
	}
	
	
	
	public String getScheduler() {
		return scheduler;
	}

	public void setScheduler(String scheduler) {
		this.scheduler = scheduler;
	}

	public String getSampler() {
		return sampler;
	}

	public void setSampler(String sampler) {
		this.sampler = sampler;
	}

	public String getModelCheckpoint() {
		return modelCheckpoint;
	}

	public void setModelCheckpoint(String modelCheckpoint) {
		this.modelCheckpoint = modelCheckpoint;
	}

	public String getModelVae() {
		return modelVae;
	}

	public void setModelVae(String modelVae) {
		this.modelVae = modelVae;
	}

	public String getRefiner() {
		return refiner;
	}

	public void setRefiner(String refiner) {
		this.refiner = refiner;
	}

	public SDResponse txt2img(SDTxt2Img req) {
		return ClientUtil.post(SDResponse.class, ClientUtil.getResource(autoserver + "/sdapi/v1/txt2img"), JSONUtil.exportObject(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	public SDResponse txt2img(String req) {
		return ClientUtil.postJSON(SDResponse.class, ClientUtil.getResource(autoserver + "/sdapi/v1/txt2img"), req, MediaType.APPLICATION_JSON_TYPE);
	}
	
	public int getSteps() {
		return steps;
	}

	public void setSteps(int steps) {
		this.steps = steps;
	}
	
	public void generateSDFigurines(OlioContext octx, List<BaseRecord> pop, int batchSize, boolean export, boolean hires, int seed) {

		SecureRandom rand = new SecureRandom();
		String basePath = octx.getWorld().get("gallery.path");
		for(BaseRecord per : pop) {
			List<BaseRecord> nars = NarrativeUtil.getCreateNarrative(octx, Arrays.asList(new BaseRecord[] {per}), "random");
			BaseRecord nar = nars.get(0);
			BaseRecord prof = per.get(FieldNames.FIELD_PROFILE);
			
			IOSystem.getActiveContext().getReader().populate(nar, new String[] {"images"});
			String path = basePath + "/Characters/" + per.get(FieldNames.FIELD_NAME);
			List<BaseRecord> bl = createPersonFigurine(octx.getOlioUser(), per, path, "Photo Op", steps, batchSize, hires, seed);
		
			if(bl.size() > 0) {
				// if(prof.get("portrait") == null) {
					prof.setValue("portrait", bl.get(rand.nextInt(bl.size())));
					Queue.queueUpdate(prof, new String[] {FieldNames.FIELD_ID, "portrait"});
				//}
				for(BaseRecord b1 : bl) {
					IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), nar, "images", b1, null, true);
					IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), prof, "album", b1, null, true);
					if(export) {
						FileUtil.emitFile("./img-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
					}
				}
			}
	
			//}
		}
		Queue.processQueue();
	}

	public void generateSDImages(OlioContext octx, List<BaseRecord> pop, String setting, String style, String bodyStyle, String verb, int batchSize, boolean export, boolean hires, int seed) {
		generateSDImages(octx, pop, randomSDConfig(), setting, style, bodyStyle, verb, batchSize, export, hires, seed);
	}
	public void generateSDImages(OlioContext octx, List<BaseRecord> pop, BaseRecord sdConfig, String setting, String style, String bodyStyle, String verb, int batchSize, boolean export, boolean hires, int seed) {

		SecureRandom rand = new SecureRandom();
		String useStyle = style;
		String useBodyStyle = bodyStyle;
		if(useStyle == null) {
			useStyle = "professional photograph";
		}
		if(useBodyStyle == null) {
			useBodyStyle = "full body";
		}
		if(setting != null && setting.equals("random")) {
			setting = NarrativeUtil.getRandomSetting();
		}
		String basePath = octx.getWorld().get("gallery.path");
		for(BaseRecord per : pop) {
			List<BaseRecord> nars = NarrativeUtil.getCreateNarrative(octx, Arrays.asList(new BaseRecord[] {per}), setting);
			BaseRecord nar = nars.get(0);
			BaseRecord prof = per.get(FieldNames.FIELD_PROFILE);
			
			IOSystem.getActiveContext().getReader().populate(nar, new String[] {"images"});
			
			String path = basePath + "/Characters/" + per.get(FieldNames.FIELD_NAME);
			//List<BaseRecord> images = nar.get("images");
			//if(images.size() == 0) {
				List<BaseRecord> bl = createPersonImage(octx.getOlioUser(), per, path, sdConfig,"Photo Op",  setting, useStyle, useBodyStyle, verb, steps, batchSize, hires, seed);
			
				if(bl.size() > 0) {
					// if(prof.get("portrait") == null) {
						prof.setValue("portrait", bl.get(rand.nextInt(bl.size())));
						Queue.queueUpdate(prof, new String[] {FieldNames.FIELD_ID, "portrait"});
					//}
					for(BaseRecord b1 : bl) {
						IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), nar, "images", b1, null, true);
						IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), prof, "album", b1, null, true);
						if(export) {
							FileUtil.emitFile("./img-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
						}
					}
				}
	
			//}
		}
		Queue.processQueue();
	}
	
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, String name) {
		return createPersonImage(user, person, groupPath, name, null, "professional portrait", 50, 1);
	}
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, String name, String setting, String pictureType, int steps, int batch) {
		return createPersonImage(user, person, groupPath, randomSDConfig(), name, null, pictureType, "full body", null, steps, batch, false, 0);
	}
		
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, BaseRecord sdConfig, String name, String setting, String pictureType, String bodyType, String verb, int steps, int batch, boolean hires, int seed) {

		SDTxt2Img s2i = newTxt2Img(person, sdConfig, setting, pictureType, bodyType, verb, steps);
		if(seed > 0) {
			s2i.setSeed(seed);
		}
		s2i.setBatch_size(batch);
		if(hires) {
			logger.info("Apply hires/refiner configuration");
			applyHRRefiner(s2i);
		}
		return createPersonImage(user, person, groupPath, name, s2i, seed);

	}
	
	public List<BaseRecord> createPersonFigurine(BaseRecord user, BaseRecord person, String groupPath, String name, int steps, int batch, boolean hires, int seed) {
		SDTxt2Img s2i = newTxt2Img(person, randomSDConfig(), "random", "professional portrait", "full body", null, steps);
		
		s2i.setPrompt(NarrativeUtil.getSDFigurinePrompt(ProfileUtil.getProfile(null, person)));
		if(seed > 0) {
			s2i.setSeed(seed);
		}
		s2i.setBatch_size(batch);
		s2i.setScheduler(scheduler);
		s2i.setSampler_name(sampler);

		SDOverrideSettings sos = new SDOverrideSettings();
		//sos.setSd_model_checkpoint("Juggernaut_X_RunDiffusion_Hyper");
		sos.setSd_model_checkpoint("dreamshaperXL_v21TurboDPMSDE");
		sos.setSd_vae(null);
		s2i.setOverride_settings(sos);
		s2i.setOverride_settings_restore_afterwards(true);

		if(hires) {
			logger.info("Apply hires/refiner configuration");
			applyHRRefiner(s2i);
		}
		return createPersonImage(user, person, groupPath, name, s2i, seed);

	}
	
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, String name, SDTxt2Img s2i, int seed) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		List<BaseRecord> datas = new ArrayList<>();
		int rando = Math.abs(rand.nextInt());
		try {

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
					ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
					clist.parameter(FieldNames.FIELD_NAME, dname);
					data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
					data.set(FieldNames.FIELD_BYTE_STORE, datab);
					data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
					AttributeUtil.addAttribute(data, "seed", seedl);
					// TODO: Fix issue when setting attribute flexValue on create for a null value 
					//AttributeUtil.addAttribute(data, "character", (String)person.get(FieldNames.FIELD_OBJECT_ID));
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
		sgp.setRefiner(refiner);
		sgp.setRefinerSwitchAt(0.75);
		
		s2i.setDenoising_strength(0.45);
		//s2i.setRefiner_checkpoint("Juggernaut_X_RunDiffusion_Hyper");
		//s2i.setRefiner_switch_at(0.75);
		s2i.setHr_scale(2);
		s2i.setHr_upscaler("Latent");
		s2i.setEnable_hr(true);
		
		SDAlwaysOnScripts sas = new SDAlwaysOnScripts();
		SDRefiner sdr = new SDRefiner();
		sdr.setArgs(new Object[] {true, refiner, 0.8});
		sas.setRefiner(sdr);
		s2i.setAlwayson_scripts(sas);
		
		
	}
	public SDTxt2Img newTxt2Img(BaseRecord person, BaseRecord sdConfig, String setting, String pictureType, String bodyType, String verb, int steps) {
		SDTxt2Img s2i = new SDTxt2Img();
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
		
		SDOverrideSettings sos = new SDOverrideSettings();
		sos.setSd_model_checkpoint(modelCheckpoint);
		sos.setSd_vae(modelVae);
		s2i.setOverride_settings(sos);
		s2i.setOverride_settings_restore_afterwards(true);
		
		return s2i;
	}
	
	private static BaseRecord configData = null;

	public static String getSDConfigPrompt(BaseRecord cfg) {
		StringBuilder buff = new StringBuilder();
		
		String style = cfg.get("style");
		buff.append("(");
		if(style.equals("art")) {
			buff.append("(" + (String)cfg.get("artStyle") + ")");
		}
		else if(style.equals("photograph")) {
			buff.append("(Photograph) taken with a (" + cfg.get("stillCamera") + ") camera and (" + cfg.get("lens") + ") lens using (" + cfg.get("film") + ") film processed with (" + cfg.get("colorProcess") + ") by (" + cfg.get("photographer") + ")");
		}
		else {
			buff.append("(Movie still) taken with a (" + cfg.get("movieCamera") + ") camera using (" + cfg.get("movieFilm") + ") film processed with (" + cfg.get("colorProcess") + ") by (" + cfg.get("director") + ")");
		}
		buff.append(").");
		return buff.toString();
		
	}
	public static BaseRecord getConfigData() {
		if(configData == null) {
			configData = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/sd/sdConfigData.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		}
		return configData;
	}
	public static BaseRecord randomSDConfig() {
		BaseRecord sd = null;
		try {
			sd = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		BaseRecord cfg = getConfigData();

		String style = randomSDConfigValue(cfg, "styles");
		sd.setValue("style", style);
		if(style.equals("art")) {
			sd.setValue("artStyle", randomSDConfigValue(cfg, "artStyles"));
		}
		else {
			sd.setValue("colorProcess", randomSDConfigValue(cfg, "colorProcesses"));
			if(style.equals("photograph")) {
				sd.setValue("stillCamera", randomSDConfigValue(cfg, "stillCameras"));
				sd.setValue("photographer", randomSDConfigValue(cfg, "photographers"));
				sd.setValue("lens", randomSDConfigValue(cfg, "lenses"));
				sd.setValue("film", randomSDConfigValue(cfg, "films"));
			}
			else {
				sd.setValue("movieFilm", randomSDConfigValue(cfg, "movieFilms"));
				sd.setValue("movieCamera", randomSDConfigValue(cfg, "movieCameras"));
				sd.setValue("director", randomSDConfigValue(cfg, "directors"));
			}
		}
		
		return sd;
	}

	protected static String randomSDConfigValue(BaseRecord cfg, String fieldName) {
		List<String> vals = cfg.get(fieldName);
		return vals.get(rand.nextInt(vals.size()));	
	}
	
}
