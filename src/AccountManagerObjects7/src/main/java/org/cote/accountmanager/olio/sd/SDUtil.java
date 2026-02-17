package org.cote.accountmanager.olio.sd;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111OverrideSettings;
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111Response;
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111Txt2Img;
import org.cote.accountmanager.olio.sd.automatic1111.Auto1111Util;
import org.cote.accountmanager.olio.sd.swarm.SWImageInfo;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWModelListResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;

import com.drew.imaging.ImageProcessingException;

import jakarta.ws.rs.core.MediaType;

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
	
	private boolean deferRemote = false;
	private BaseRecord imageAccessUser = null;
	private SDAPIEnumType apiType = SDAPIEnumType.UNKNOWN;
	public SDUtil(SDAPIEnumType type) {
		this.apiType = type;
	}
	
	public SDUtil(SDAPIEnumType type, String server) {
		this(type);
		autoserver = server;
	}
	

	public boolean isDeferRemote() {
		return deferRemote;
	}

	public void setDeferRemote(boolean deferRemote) {
		this.deferRemote = deferRemote;
	}

	public void setImageAccessUser(BaseRecord imageAccessUser) {
		this.imageAccessUser = imageAccessUser;
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

	public Auto1111Response txt2img(Auto1111Txt2Img req) {
		return ClientUtil.post(Auto1111Response.class, ClientUtil.getResource(autoserver + "/sdapi/v1/txt2img"), JSONUtil.exportObject(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	public SWImageResponse txt2img(SWTxt2Img req) {
		if (req.getSession_id() == null || req.getSession_id().isEmpty()) {
			String sess = SWUtil.getAnonymousSession(autoserver);
			if (sess == null || sess.isEmpty()) {
				logger.error("Could not obtain anonymous session");
				return null;
			}
			req.setSession_id(sess);
		}
		return ClientUtil.post(SWImageResponse.class, ClientUtil.getResource(autoserver + "/API/GenerateText2Image"), JSONUtil.exportObject(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	public List<String> listModels() {
		List<String> models = new ArrayList<>();
		try {
			String sess = SWUtil.getAnonymousSession(autoserver);
			if (sess == null || sess.isEmpty()) {
				logger.warn("Could not obtain anonymous session for listing models");
				return models;
			}
			SWModelListResponse resp = ClientUtil.post(
				SWModelListResponse.class,
				ClientUtil.getResource(autoserver + "/API/ListModels"),
				"{\"session_id\":\"" + sess + "\",\"path\":\"\",\"depth\":2}",
				MediaType.APPLICATION_JSON_TYPE
			);
			if (resp != null && resp.getFiles() != null) {
				for (Map<String, Object> file : resp.getFiles()) {
					Object name = file.get("name");
					if (name != null) {
						models.add(name.toString());
					}
				}
			}
		} catch (Exception e) {
			logger.warn("Could not list models from SD server: " + e.getMessage());
		}
		return models;
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
			List<BaseRecord> bl = createPersonImage(octx.getOlioUser(), per, path, sdConfig,"Photo Op",  setting, useStyle, useBodyStyle, verb, steps, batchSize, hires, seed);
		
			if(bl.size() > 0) {
				prof.setValue("portrait", bl.get(rand.nextInt(bl.size())));
				Queue.queueUpdate(prof, new String[] {FieldNames.FIELD_ID, "portrait"});

				for(BaseRecord b1 : bl) {
					IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), nar, "images", b1, null, true);
					IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), prof, "album", b1, null, true);
					if(export) {
						FileUtil.emitFile("./img-" + b1.get(FieldNames.FIELD_NAME) + ".png", (byte[])b1.get(FieldNames.FIELD_BYTE_STORE));
					}
				}
			}
		}
		Queue.processQueue();
	}
	/*
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, String name) {
		return createPersonImage(user, person, groupPath, name, null, "professional portrait", 50, 1);
	}
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, String name, String setting, String pictureType, int steps, int batch) {
		return createPersonImage(user, person, groupPath, randomSDConfig(), name, null, pictureType, "full body", null, steps, batch, false, 0);
	}
    */

	public List<BaseRecord> createPersonFigurine(BaseRecord user, BaseRecord person, String groupPath, String name, int steps, int batch, boolean hires, int seed) {
		Auto1111Txt2Img s2i = Auto1111Util.newTxt2Img(person, randomSDConfig(), "random", "professional portrait", "full body", null, steps);
		
		s2i.setPrompt(NarrativeUtil.getSDFigurinePrompt(ProfileUtil.getProfile(null, person)));
		if(seed > 0) {
			s2i.setSeed(seed);
		}
		s2i.setBatch_size(batch);
		s2i.setScheduler(scheduler);
		s2i.setSampler_name(sampler);

		Auto1111OverrideSettings sos = new Auto1111OverrideSettings();
		//sos.setSd_model_checkpoint("Juggernaut_X_RunDiffusion_Hyper");
		sos.setSd_model_checkpoint("dreamshaperXL_v21TurboDPMSDE");
		sos.setSd_vae(null);
		s2i.setOverride_settings(sos);
		s2i.setOverride_settings_restore_afterwards(true);

		if(hires) {
			logger.info("Apply hires/refiner configuration");
			Auto1111Util.applyHRRefiner(s2i);
		}
		return createPersonImage(user, person, groupPath, name, s2i, seed);

	}
	
	public Auto1111Response checkRemote(Auto1111Txt2Img req) {
		Auto1111Response oresp = null;
		if (deferRemote) {

			BaseRecord task = OlioTaskAgent.createTaskRequest(req);
			BaseRecord rtask = OlioTaskAgent.executeTask(task);
			if (rtask != null) {
				oresp = JSONUtil.importObject(rtask.get("taskModelData"), Auto1111Response.class);
				if (oresp == null) {
					logger.error("Task response was null");
				}
			}
		}
		return oresp;
	}
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, BaseRecord sdConfig, String name, String setting, String pictureType, String bodyType, String verb, int steps, int batch, boolean hires, int seed) {
		Object s2iObj = null;
		if (apiType == SDAPIEnumType.AUTO1111) {
			Auto1111Txt2Img s2i = Auto1111Util.newTxt2Img(person, sdConfig, setting, pictureType, bodyType, verb, steps);
			if(seed > 0) {
				s2i.setSeed(seed);
			}
			s2i.setBatch_size(batch);
			if(hires) {
				logger.info("Apply hires/refiner configuration");
				Auto1111Util.applyHRRefiner(s2i);
			}
			s2iObj = s2i;
		}
		else if (apiType == SDAPIEnumType.SWARM) {
			s2iObj = SWUtil.newTxt2Img(person, sdConfig, setting, pictureType, bodyType, verb, steps, seed);
			applyImg2Img(user, sdConfig, (SWTxt2Img)s2iObj);
		}
		else if (apiType == SDAPIEnumType.UNKNOWN) {
			logger.error("Unknown API type – cannot create image");
			return new ArrayList<>();
		}
		
		return createPersonImage(user, person, groupPath, name, s2iObj, seed);
	}
	
	public List<BaseRecord> createPersonImage(BaseRecord user, BaseRecord person, String groupPath, String name, Object s2i, int seed) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		List<BaseRecord> datas = new ArrayList<>();
		int rando = Math.abs(rand.nextInt());
		try {

			logger.info("Generating image for " + person.get(FieldNames.FIELD_NAME));
			
			List<String> repImages = new ArrayList<>();
			if (apiType == SDAPIEnumType.UNKNOWN) {
				logger.error("Unknown API type – cannot generate image");
				return datas;
			}
			else if (apiType == SDAPIEnumType.AUTO1111) {
				Auto1111Response rep = null;
				if(deferRemote) {
					if (apiType != SDAPIEnumType.AUTO1111) {
						rep = checkRemote((Auto1111Txt2Img)s2i);
					}
					else {
						logger.warn("Deferred remote generation is only supported for AUTO1111 API type");
					}
				}
				else {
					rep = txt2img((Auto1111Txt2Img)s2i);
				}
				if(rep == null) {
					logger.error("Response is null");
					return datas;
				}
				if(seed <= 0 && rep.getParameters() != null) {
					seed = rep.getParameters().getSeed();
				}
				repImages = Arrays.asList(rep.getImages());
			}
			else if (SDAPIEnumType.SWARM == apiType) {
				SWImageResponse rep = txt2img((SWTxt2Img)s2i);
				if (rep != null && rep.getImages() != null) {
					repImages =  rep.getImages();
					if (rep.getImages().size() == 0) {
						logger.error("No images returned in response");
						return datas;
					}
				} else {
					logger.error("Response is null");
					return datas;
				}
				
			}
			
			int counter = 1;
			
			int seedl = seed;
			for(String bai : repImages) {
				logger.info("Processing image " + counter);
				byte[] datab = new byte[0];
				if(apiType == SDAPIEnumType.AUTO1111) {
					datab = BinaryUtil.fromBase64(bai.getBytes());
				}
				else if (apiType == SDAPIEnumType.SWARM) {
					byte[] dataTest = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);

					SWImageInfo info = SWUtil.extractInfo(dataTest);
					if (info != null && info.getImageParams() != null) {
						seedl = info.getImageParams().getSeed();
						logger.info("Extracted seed " + seed + " from image metadata");
					}
					
					if (dataTest == null || dataTest.length == 0) {
						logger.error("Could not retrieve image data from swarm server for " + bai);
						continue;
					}
					datab = dataTest;
				}
				else {
					logger.error("Unknown API type – cannot process image");
					return datas;
				}
				
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
					if(person.get(FieldNames.FIELD_OBJECT_ID) != null) {
						AttributeUtil.addAttribute(data, "character", (String)person.get(FieldNames.FIELD_OBJECT_ID));
					}
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
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ModelException | ImageProcessingException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		return datas;
	}



	
	private static BaseRecord configData = null;

	public static String getSDConfigPrompt(BaseRecord cfg) {
		StringBuilder buff = new StringBuilder();

		if(cfg == null) {
			return "(professional photograph).";
		}

		String style = cfg.get("style");
		if(style == null) {
			return "(professional photograph).";
		}
		buff.append("(");
		if(style.equals("art")) {
			buff.append("(" + (String)cfg.get("artStyle") + ")");
		}
		else if(style.equals("photograph")) {
			buff.append("(Photograph) taken with a (" + cfg.get("stillCamera") + ") camera and (" + cfg.get("lens") + ") lens using (" + cfg.get("film") + ") film processed with (" + cfg.get("colorProcess") + ") by (" + cfg.get("photographer") + ")");
		}
		else if(style.equals("movie")) {
			buff.append("(Movie still) taken with a (" + cfg.get("movieCamera") + ") camera using (" + cfg.get("movieFilm") + ") film processed with (" + cfg.get("colorProcess") + ") by (" + cfg.get("director") + ")");
		}
		else if(style.equals("selfie")) {
			buff.append("(Selfie) taken with a (" + cfg.get("selfiePhone") + ") at a (" + cfg.get("selfieAngle") + ") angle with (" + cfg.get("selfieLighting") + ") lighting");
		}
		else if(style.equals("anime")) {
			buff.append("(Anime illustration) in the style of (" + cfg.get("animeStudio") + ") with (" + cfg.get("animeEra") + ") aesthetics");
		}
		else if(style.equals("portrait")) {
			buff.append("(Studio portrait) with (" + cfg.get("portraitLighting") + ") using (" + cfg.get("portraitBackdrop") + ") backdrop by (" + cfg.get("photographer") + ")");
		}
		else if(style.equals("comic")) {
			buff.append("(Comic book panel) in (" + cfg.get("comicPublisher") + ") style from the (" + cfg.get("comicEra") + ") with (" + cfg.get("comicColoring") + ")");
		}
		else if(style.equals("digitalArt")) {
			buff.append("(" + cfg.get("digitalMedium") + ") created with (" + cfg.get("digitalSoftware") + ") in the style of (" + cfg.get("digitalArtist") + ")");
		}
		else if(style.equals("fashion")) {
			buff.append("(Fashion photography) for (" + cfg.get("fashionMagazine") + ") in (" + cfg.get("fashionDecade") + ") style by (" + cfg.get("photographer") + ")");
		}
		else if(style.equals("vintage")) {
			buff.append("(Vintage photograph) from the (" + cfg.get("vintageDecade") + ") using (" + cfg.get("vintageProcessing") + ") with a (" + cfg.get("vintageCamera") + ")");
		}
		else if(style.equals("custom")) {
			String cp = cfg.get("customPrompt");
			if(cp != null && cp.length() > 0) {
				buff.append(cp);
			}
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
		switch(style) {
			case "art":
				sd.setValue("artStyle", randomSDConfigValue(cfg, "artStyles"));
				break;
			case "photograph":
				sd.setValue("colorProcess", randomSDConfigValue(cfg, "colorProcesses"));
				sd.setValue("stillCamera", randomSDConfigValue(cfg, "stillCameras"));
				sd.setValue("photographer", randomSDConfigValue(cfg, "photographers"));
				sd.setValue("lens", randomSDConfigValue(cfg, "lenses"));
				sd.setValue("film", randomSDConfigValue(cfg, "films"));
				break;
			case "movie":
				sd.setValue("colorProcess", randomSDConfigValue(cfg, "colorProcesses"));
				sd.setValue("movieFilm", randomSDConfigValue(cfg, "movieFilms"));
				sd.setValue("movieCamera", randomSDConfigValue(cfg, "movieCameras"));
				sd.setValue("director", randomSDConfigValue(cfg, "directors"));
				break;
			case "selfie":
				sd.setValue("selfiePhone", randomSDConfigValue(cfg, "selfiePhones"));
				sd.setValue("selfieAngle", randomSDConfigValue(cfg, "selfieAngles"));
				sd.setValue("selfieLighting", randomSDConfigValue(cfg, "selfieLightings"));
				break;
			case "anime":
				sd.setValue("animeStudio", randomSDConfigValue(cfg, "animeStudios"));
				sd.setValue("animeEra", randomSDConfigValue(cfg, "animeEras"));
				break;
			case "portrait":
				sd.setValue("portraitLighting", randomSDConfigValue(cfg, "portraitLightings"));
				sd.setValue("portraitBackdrop", randomSDConfigValue(cfg, "portraitBackdrops"));
				sd.setValue("photographer", randomSDConfigValue(cfg, "photographers"));
				break;
			case "comic":
				sd.setValue("comicPublisher", randomSDConfigValue(cfg, "comicPublishers"));
				sd.setValue("comicEra", randomSDConfigValue(cfg, "comicEras"));
				sd.setValue("comicColoring", randomSDConfigValue(cfg, "comicColorings"));
				break;
			case "digitalArt":
				sd.setValue("digitalMedium", randomSDConfigValue(cfg, "digitalMediums"));
				sd.setValue("digitalSoftware", randomSDConfigValue(cfg, "digitalSoftwares"));
				sd.setValue("digitalArtist", randomSDConfigValue(cfg, "digitalArtists"));
				break;
			case "fashion":
				sd.setValue("fashionMagazine", randomSDConfigValue(cfg, "fashionMagazines"));
				sd.setValue("fashionDecade", randomSDConfigValue(cfg, "fashionDecades"));
				sd.setValue("photographer", randomSDConfigValue(cfg, "photographers"));
				break;
			case "vintage":
				sd.setValue("vintageDecade", randomSDConfigValue(cfg, "vintageDecades"));
				sd.setValue("vintageProcessing", randomSDConfigValue(cfg, "vintageProcessings"));
				sd.setValue("vintageCamera", randomSDConfigValue(cfg, "vintageCameras"));
				break;
		}

		return sd;
	}

	public static String randomSDConfigValue(BaseRecord cfg, String fieldName) {
		List<String> vals = cfg.get(fieldName);
		return vals.get(rand.nextInt(vals.size()));
	}

	public static byte[] getDataBytes(BaseRecord data) {
		byte[] value = null;
		if (data.hasField(FieldNames.FIELD_STREAM) && data.get(FieldNames.FIELD_STREAM) != null) {
			BaseRecord stream = data.get(FieldNames.FIELD_STREAM);
			StreamSegmentUtil ssu = new StreamSegmentUtil();
			value = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0, 0);
		} else {
			try {
				value = ByteModelUtil.getValue(data);
			} catch (ValueException | FieldException e) {
				logger.error(e);
			}
		}
		return value;
	}

	private void applyImg2Img(BaseRecord user, BaseRecord sdConfig, SWTxt2Img s2i) {
		String refImageId = sdConfig.get("referenceImageId");
		if(refImageId == null || refImageId.length() == 0) {
			return;
		}
		Query refQ = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, refImageId);
		refQ.planMost(true);
		BaseRecord lookupUser = imageAccessUser != null ? imageAccessUser : user;
		BaseRecord refImage = IOSystem.getActiveContext().getAccessPoint().find(lookupUser, refQ);
		if(refImage == null) {
			logger.warn("Reference image not found: " + refImageId);
			return;
		}
		byte[] imageBytes = getDataBytes(refImage);
		if(imageBytes == null || imageBytes.length == 0) {
			logger.warn("Reference image has no data: " + refImageId);
			return;
		}
		String base64Image = BinaryUtil.toBase64Str(imageBytes);
		s2i.setInitImage(base64Image);
		Double ds = sdConfig.get("denoisingStrength");
		s2i.setInitImageCreativity(ds != null ? ds : 0.75);
	}

	public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name, int batch, boolean hires, int seed) {
		if(apiType != SDAPIEnumType.SWARM) {
			logger.error("createImage without charPerson is only supported for SWARM API type");
			return new ArrayList<>();
		}

		String desc = sdConfig.get("description");
		String prompt;
		if(desc != null && desc.length() > 0) {
			prompt = desc;
		} else {
			prompt = getSDConfigPrompt(sdConfig);
		}

		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(prompt);
		String negPrompt = sdConfig.get("negativePrompt");
		if(negPrompt != null && negPrompt.length() > 0) {
			s2i.setNegativePrompt(negPrompt);
		}
		s2i.setSteps(sdConfig.get("steps"));
		s2i.setModel(sdConfig.get("model"));
		s2i.setScheduler(sdConfig.get("scheduler"));
		s2i.setSampler(sdConfig.get("sampler"));
		s2i.setCfgScale(sdConfig.get("cfg"));
		s2i.setSeed(sdConfig.get("seed"));
		s2i.setImages(batch);
		if((Boolean)sdConfig.get("hires") == true) {
			s2i.setRefinerScheduler(sdConfig.get("refinerScheduler"));
			s2i.setRefinerSampler(sdConfig.get("refinerSampler"));
			s2i.setRefinerMethod(sdConfig.get("refinerMethod"));
			s2i.setRefinerModel(sdConfig.get("refinerModel"));
			s2i.setRefinerSteps(sdConfig.get("refinerSteps"));
			s2i.setRefinerUpscale(sdConfig.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(sdConfig.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(sdConfig.get("refinerCfg"));
			s2i.setRefinerControlPercentage(sdConfig.get("refinerControlPercentage"));
		}
		else {
			s2i.setRefinerControlPercentage(0.0);
		}

		applyImg2Img(user, sdConfig, s2i);

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		List<BaseRecord> datas = new ArrayList<>();
		int rando = Math.abs(rand.nextInt());
		try {
			logger.info("Generating image: " + name);
			SWImageResponse rep = txt2img(s2i);
			if(rep == null || rep.getImages() == null || rep.getImages().size() == 0) {
				logger.error("No images returned in response");
				return datas;
			}

			int counter = 1;
			int seedl = seed;
			for(String bai : rep.getImages()) {
				byte[] dataTest = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
				SWImageInfo info = SWUtil.extractInfo(dataTest);
				if(info != null && info.getImageParams() != null) {
					seedl = info.getImageParams().getSeed();
				}
				if(dataTest == null || dataTest.length == 0) {
					logger.error("Could not retrieve image data from swarm server for " + bai);
					continue;
				}

				String dname = name + " - " + counter + " - " + rando + " - " + seedl;
				Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				q.field(FieldNames.FIELD_NAME, dname);
				BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);

				if(data == null) {
					ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
					clist.parameter(FieldNames.FIELD_NAME, dname);
					data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
					data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
					data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
					AttributeUtil.addAttribute(data, "seed", seedl);
					AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i));
					IOSystem.getActiveContext().getAccessPoint().create(user, data);
				}
				else {
					data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
					IOSystem.getActiveContext().getAccessPoint().update(user, data);
				}
				datas.add(data);
				counter++;
				seedl = seedl + 1;
			}
		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ModelException | ImageProcessingException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}

		return datas;
	}

	/// Generate a contextual scene image for a chat, combining both characters in a setting.
	/// Uses pre-assembled prompt from Chat.generateScenePrompt() with IP-Adapter portrait refs.
	/// @param user The user record
	/// @param groupPath Path where the image will be stored
	/// @param name Base name for the generated image
	/// @param s2i Pre-configured SWTxt2Img with prompt, promptImages, and generation params
	/// @param sysCharOid System character objectId for attribution
	/// @param usrCharOid User character objectId for attribution
	/// @return List of generated image data records
	public List<BaseRecord> createSceneImage(BaseRecord user, String groupPath, String name,
			SWTxt2Img s2i, String sysCharOid, String usrCharOid) {
		if (apiType != SDAPIEnumType.SWARM) {
			logger.error("createSceneImage is only supported for SWARM API type");
			return new ArrayList<>();
		}

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		List<BaseRecord> datas = new ArrayList<>();
		int rando = Math.abs(rand.nextInt());
		try {
			logger.info("Generating scene image: " + name);
			SWImageResponse rep = txt2img(s2i);
			if (rep == null || rep.getImages() == null || rep.getImages().isEmpty()) {
				logger.error("No scene images returned in response");
				return datas;
			}

			int counter = 1;
			int seedl = s2i.getSeed();
			for (String bai : rep.getImages()) {
				byte[] dataTest = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
				SWImageInfo info = SWUtil.extractInfo(dataTest);
				if (info != null && info.getImageParams() != null) {
					seedl = info.getImageParams().getSeed();
				}
				if (dataTest == null || dataTest.length == 0) {
					logger.error("Could not retrieve scene image data from swarm server for " + bai);
					continue;
				}

				String dname = name + " - scene - " + counter + " - " + rando + " - " + seedl;
				Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				q.field(FieldNames.FIELD_NAME, dname);
				BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);

				if (data == null) {
					ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
					clist.parameter(FieldNames.FIELD_NAME, dname);
					data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
					data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
					data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
					AttributeUtil.addAttribute(data, "seed", seedl);
					AttributeUtil.addAttribute(data, "imageType", "scene");
					if (sysCharOid != null) AttributeUtil.addAttribute(data, "systemCharacter", sysCharOid);
					if (usrCharOid != null) AttributeUtil.addAttribute(data, "userCharacter", usrCharOid);
					AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i));
					IOSystem.getActiveContext().getAccessPoint().create(user, data);
				} else {
					data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
					IOSystem.getActiveContext().getAccessPoint().update(user, data);
				}
				datas.add(data);
				counter++;
				seedl = seedl + 1;
			}
		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ModelException | ImageProcessingException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}

		return datas;
	}

	/// Generate a landscape image for a location based on terrain types.
	/// @param user The user record
	/// @param groupPath Path where the image will be stored
	/// @param location The location record with terrain information
	/// @param adjacentTerrains Set of terrain types from adjacent cells
	/// @param sdConfig Optional SD configuration
	/// @param hires Enable high resolution upscaling
	/// @param seed Random seed for reproducibility
	/// @return The generated image data record, or null if failed
	public BaseRecord generateLandscapeImage(BaseRecord user, String groupPath, BaseRecord location, java.util.Set<String> adjacentTerrains, BaseRecord sdConfig, boolean hires, long seed) {
		if(apiType != SDAPIEnumType.SWARM) {
			logger.error("generateLandscapeImage is only supported for SWARM API type");
			return null;
		}

		String prompt = NarrativeUtil.getLandscapePrompt(location, adjacentTerrains);
		String negPrompt = NarrativeUtil.getLandscapeNegativePrompt();

		// Initialize with defaults, merge any config overrides
		BaseRecord config = randomSDConfig();
		if(sdConfig != null) {
			String style = sdConfig.get("style");
			if(style != null) config.setValue("style", style);
		}

		// Get config values with fallbacks
		Integer cfgSteps = config.get("steps");
		String cfgModel = config.get("model");
		String cfgScheduler = config.get("scheduler");
		String cfgSampler = config.get("sampler");
		Integer cfgCfg = config.get("cfg");

		if(cfgSteps == null) cfgSteps = 25;
		if(cfgModel == null) cfgModel = "sdXL_v10VAEFix.safetensors";
		if(cfgScheduler == null) cfgScheduler = "normal";
		if(cfgSampler == null) cfgSampler = "dpmpp_2m";
		if(cfgCfg == null) cfgCfg = 7;

		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(prompt);
		s2i.setNegativePrompt(negPrompt);
		s2i.setWidth(1024);  // Landscape aspect ratio
		s2i.setHeight(576);
		s2i.setSteps(cfgSteps);
		s2i.setModel(cfgModel);
		s2i.setScheduler(cfgScheduler);
		s2i.setSampler(cfgSampler);
		s2i.setCfgScale(cfgCfg);
		s2i.setSeed((int)(seed > 0 ? (seed & 0x7FFFFFFF) : Math.abs(rand.nextInt())));
		s2i.setImages(1);

		if(hires && config.get("refinerModel") != null) {
			s2i.setRefinerScheduler(config.get("refinerScheduler"));
			s2i.setRefinerSampler(config.get("refinerSampler"));
			s2i.setRefinerMethod(config.get("refinerMethod"));
			s2i.setRefinerModel(config.get("refinerModel"));
			s2i.setRefinerSteps(config.get("refinerSteps"));
			s2i.setRefinerUpscale(config.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(config.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(config.get("refinerCfg"));
			s2i.setRefinerControlPercentage(config.get("refinerControlPercentage"));
		} else {
			s2i.setRefinerControlPercentage(0.0);
		}

		String locName = location.get(FieldNames.FIELD_NAME);
		if(locName == null) locName = "Landscape";
		String terrain = location.get(FieldNames.FIELD_TERRAIN_TYPE);
		if(terrain == null) terrain = "UNKNOWN";

		int rando = Math.abs(rand.nextInt());
		String name = locName + " - " + terrain + " - " + rando;

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

		try {
			logger.info("Generating landscape image: " + name);
			SWImageResponse rep = txt2img(s2i);
			if(rep == null || rep.getImages() == null || rep.getImages().isEmpty()) {
				logger.error("No images returned in response");
				return null;
			}

			String bai = rep.getImages().get(0);
			byte[] dataTest = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
			SWImageInfo info = SWUtil.extractInfo(dataTest);
			int seedl = (int)seed;
			if(info != null && info.getImageParams() != null) {
				seedl = info.getImageParams().getSeed();
			}
			if(dataTest == null || dataTest.length == 0) {
				logger.error("Could not retrieve image data from swarm server");
				return null;
			}

			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, name);
			BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);

			if(data == null) {
				ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
				clist.parameter(FieldNames.FIELD_NAME, name);
				data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
				data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
				data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
				AttributeUtil.addAttribute(data, "seed", seedl);
				AttributeUtil.addAttribute(data, "terrain", terrain);
				AttributeUtil.addAttribute(data, "imageType", "landscape");
				if(location.get(FieldNames.FIELD_OBJECT_ID) != null) {
					AttributeUtil.addAttribute(data, "location", (String)location.get(FieldNames.FIELD_OBJECT_ID));
				}
				AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i));
				IOSystem.getActiveContext().getAccessPoint().create(user, data);
			} else {
				data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
				IOSystem.getActiveContext().getAccessPoint().update(user, data);
			}
			return data;

		} catch(Exception e) {
			logger.error("Error generating landscape image", e);
		}
		return null;
	}

	/// Phase 15b: Generate a landscape image from a text prompt and SD config.
	/// Returns the raw image bytes for use as an initImage in the composite scene pipeline.
	/// Unlike generateLandscapeImage(), this method does not require a location record —
	/// it works with a simple prompt string (built from chatConfig setting/terrain).
	/// @param prompt The landscape prompt text
	/// @param negPrompt Negative prompt (null for default)
	/// @param sdConfig SD configuration (model, steps, etc.)
	/// @return Raw PNG image bytes, or null on failure
	public byte[] generateLandscapeBytes(String prompt, String negPrompt, BaseRecord sdConfig) {
		if (apiType != SDAPIEnumType.SWARM) {
			logger.error("generateLandscapeBytes is only supported for SWARM API type");
			return null;
		}
		if (negPrompt == null) {
			negPrompt = NarrativeUtil.getLandscapeNegativePrompt();
		}

		BaseRecord config = sdConfig != null ? sdConfig : randomSDConfig();
		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(prompt);
		s2i.setNegativePrompt(negPrompt);
		s2i.setWidth(1024);
		s2i.setHeight(576);
		s2i.setSeed(Math.abs(rand.nextInt()));

		Integer cfgSteps = config.get("steps");
		String cfgModel = config.get("model");
		String cfgScheduler = config.get("scheduler");
		String cfgSampler = config.get("sampler");
		Integer cfgCfg = config.get("cfg");
		Integer cfgSeed = config.get("seed");
		Boolean hires = config.get("hires");

		s2i.setSteps(cfgSteps != null ? cfgSteps : 25);
		s2i.setModel(cfgModel != null ? cfgModel : "sdXL_v10VAEFix.safetensors");
		s2i.setScheduler(cfgScheduler != null ? cfgScheduler : "Karras");
		s2i.setSampler(cfgSampler != null ? cfgSampler : "dpmpp_2m");
		s2i.setCfgScale(cfgCfg != null ? cfgCfg : 7);
		if (cfgSeed != null && cfgSeed > 0) {
			s2i.setSeed(cfgSeed);
		}
		s2i.setImages(1);

		if (hires != null && hires) {
			s2i.setRefinerScheduler(config.get("refinerScheduler"));
			s2i.setRefinerSampler(config.get("refinerSampler"));
			s2i.setRefinerMethod(config.get("refinerMethod"));
			s2i.setRefinerModel(config.get("refinerModel"));
			s2i.setRefinerSteps(config.get("refinerSteps"));
			s2i.setRefinerUpscale(config.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(config.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(config.get("refinerCfg"));
			s2i.setRefinerControlPercentage(config.get("refinerControlPercentage"));
		} else {
			s2i.setRefinerControlPercentage(0.0);
		}

		try {
			logger.info("Generating landscape bytes: " + prompt.substring(0, Math.min(100, prompt.length())) + "...");
			SWImageResponse rep = txt2img(s2i);
			if (rep == null || rep.getImages() == null || rep.getImages().isEmpty()) {
				logger.error("No landscape images returned in response");
				return null;
			}
			String bai = rep.getImages().get(0);
			byte[] data = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
			if (data == null || data.length == 0) {
				logger.error("Could not retrieve landscape image data from swarm server");
				return null;
			}
			logger.info("Landscape image generated: " + data.length + " bytes");
			return data;
		} catch (Exception e) {
			logger.error("Error generating landscape bytes", e);
		}
		return null;
	}

	/// Generate an animal image with optional landscape reference.
	/// @param user The user record
	/// @param animal The animal record
	/// @param groupPath Path where the image will be stored
	/// @param location Optional location for landscape setting
	/// @param adjacentTerrains Optional set of adjacent terrain types
	/// @param landscapeImageId Optional objectId of an existing landscape image to use as reference
	/// @param sdConfig Optional SD configuration
	/// @param hires Enable high resolution upscaling
	/// @param seed Random seed
	/// @return List of generated image records
	public List<BaseRecord> createAnimalImage(BaseRecord user, BaseRecord animal, String groupPath, BaseRecord location, java.util.Set<String> adjacentTerrains, String landscapeImageId, BaseRecord sdConfig, boolean hires, int seed) {
		List<BaseRecord> datas = new ArrayList<>();

		if(apiType != SDAPIEnumType.SWARM) {
			logger.error("createAnimalImage is only supported for SWARM API type");
			return datas;
		}

		// Get landscape setting description if location provided
		String landscapeSetting = null;
		if(location != null) {
			landscapeSetting = NarrativeUtil.getLandscapeSettingDescription(location, adjacentTerrains);
		}

		String prompt = NarrativeUtil.getAnimalPrompt(animal, landscapeSetting, sdConfig);
		String negPrompt = NarrativeUtil.getAnimalNegativePrompt();

		// Initialize with defaults
		BaseRecord config = randomSDConfig();
		if(sdConfig != null) {
			String style = sdConfig.get("style");
			if(style != null) config.setValue("style", style);
		}

		Integer cfgSteps = config.get("steps");
		String cfgModel = config.get("model");
		String cfgScheduler = config.get("scheduler");
		String cfgSampler = config.get("sampler");
		Integer cfgCfg = config.get("cfg");

		if(cfgSteps == null) cfgSteps = 25;
		if(cfgModel == null) cfgModel = "sdXL_v10VAEFix.safetensors";
		if(cfgScheduler == null) cfgScheduler = "normal";
		if(cfgSampler == null) cfgSampler = "dpmpp_2m";
		if(cfgCfg == null) cfgCfg = 7;

		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(prompt);
		s2i.setNegativePrompt(negPrompt);
		s2i.setWidth(768);
		s2i.setHeight(768);
		s2i.setSteps(cfgSteps);
		s2i.setModel(cfgModel);
		s2i.setScheduler(cfgScheduler);
		s2i.setSampler(cfgSampler);
		s2i.setCfgScale(cfgCfg);
		s2i.setSeed(seed > 0 ? seed : Math.abs(rand.nextInt()));
		s2i.setImages(1);

		// Apply landscape as reference image if provided
		if(landscapeImageId != null && !landscapeImageId.isEmpty()) {
			Query refQ = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, landscapeImageId);
			refQ.planMost(true);
			BaseRecord refImage = IOSystem.getActiveContext().getAccessPoint().find(user, refQ);
			if(refImage != null) {
				byte[] imageBytes = getDataBytes(refImage);
				if(imageBytes != null && imageBytes.length > 0) {
					String base64Image = BinaryUtil.toBase64Str(imageBytes);
					s2i.setInitImage(base64Image);
					s2i.setInitImageCreativity(0.85); // Allow significant changes to add animal
					logger.info("Using landscape image as reference: " + landscapeImageId);
				}
			}
		}

		if(hires && config.get("refinerModel") != null) {
			s2i.setRefinerScheduler(config.get("refinerScheduler"));
			s2i.setRefinerSampler(config.get("refinerSampler"));
			s2i.setRefinerMethod(config.get("refinerMethod"));
			s2i.setRefinerModel(config.get("refinerModel"));
			s2i.setRefinerSteps(config.get("refinerSteps"));
			s2i.setRefinerUpscale(config.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(config.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(config.get("refinerCfg"));
			s2i.setRefinerControlPercentage(config.get("refinerControlPercentage"));
		} else {
			s2i.setRefinerControlPercentage(0.0);
		}

		String animalName = animal.get(FieldNames.FIELD_NAME);
		if(animalName == null) animalName = "Animal";
		String animalType = animal.get(FieldNames.FIELD_TYPE);
		if(animalType != null) {
			animalName = animalType + " - " + animalName;
		}

		int rando = Math.abs(rand.nextInt());
		String name = animalName + " - " + rando;

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

		try {
			logger.info("Generating animal image: " + name);
			SWImageResponse rep = txt2img(s2i);
			if(rep == null || rep.getImages() == null || rep.getImages().isEmpty()) {
				logger.error("No images returned in response");
				return datas;
			}

			for(String bai : rep.getImages()) {
				byte[] dataTest = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
				SWImageInfo info = SWUtil.extractInfo(dataTest);
				int seedl = seed;
				if(info != null && info.getImageParams() != null) {
					seedl = info.getImageParams().getSeed();
				}
				if(dataTest == null || dataTest.length == 0) {
					logger.error("Could not retrieve image data from swarm server");
					continue;
				}

				Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				q.field(FieldNames.FIELD_NAME, name);
				BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);

				if(data == null) {
					ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
					clist.parameter(FieldNames.FIELD_NAME, name);
					data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
					data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
					data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
					AttributeUtil.addAttribute(data, "seed", seedl);
					AttributeUtil.addAttribute(data, "imageType", "animal");
					if(animal.get(FieldNames.FIELD_OBJECT_ID) != null) {
						AttributeUtil.addAttribute(data, "animal", (String)animal.get(FieldNames.FIELD_OBJECT_ID));
					}
					AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i));
					IOSystem.getActiveContext().getAccessPoint().create(user, data);
				} else {
					data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
					IOSystem.getActiveContext().getAccessPoint().update(user, data);
				}
				datas.add(data);
			}
		} catch(Exception e) {
			logger.error("Error generating animal image", e);
		}
		return datas;
	}

	/// Generate mannequin images for an apparel record, one image per cumulative wear level
	public List<BaseRecord> generateMannequinImages(BaseRecord user, String groupPath, BaseRecord apparel, BaseRecord sdConfig, boolean hires, long seed) {
		List<BaseRecord> images = new ArrayList<>();
		List<BaseRecord> wears = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		if(wears == null || wears.isEmpty()) {
			logger.warn("No wearables in apparel");
			return images;
		}

		// Determine which wear levels are present
		java.util.Set<WearLevelEnumType> levels = new java.util.TreeSet<>();
		for(BaseRecord wear : wears) {
			String levelStr = wear.get(OlioFieldNames.FIELD_LEVEL);
			if(levelStr != null) {
				try {
					WearLevelEnumType level = WearLevelEnumType.valueOf(levelStr);
					levels.add(level);
				} catch(IllegalArgumentException e) {
					// Skip invalid levels
				}
			}
		}

		if(levels.isEmpty()) {
			logger.warn("No valid wear levels found in apparel");
			return images;
		}

		String apparelName = apparel.get(FieldNames.FIELD_NAME);
		int rando = Math.abs(rand.nextInt());
		long useSeed = seed > 0 ? seed : Math.abs(rand.nextLong());

		// Always start with a properly initialized config (with defaults), then merge any overrides
		BaseRecord config = randomSDConfig();
		if(sdConfig != null) {
			// Copy any style-related values from the incoming config
			String style = sdConfig.get("style");
			if(style != null) config.setValue("style", style);
			// Also copy hires, seed if provided
			Object hiresObj = sdConfig.get("hires");
			if(hiresObj != null) config.setValue("hires", hiresObj);
		}

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

		// Get config values with fallbacks for required fields
		Integer cfgSteps = config.get("steps");
		String cfgModel = config.get("model");
		String cfgScheduler = config.get("scheduler");
		String cfgSampler = config.get("sampler");
		Integer cfgCfg = config.get("cfg");

		// Apply defaults if model schema defaults weren't applied
		if(cfgSteps == null) cfgSteps = 20;
		if(cfgModel == null) cfgModel = "sdXL_v10VAEFix.safetensors";
		if(cfgScheduler == null) cfgScheduler = "normal";
		if(cfgSampler == null) cfgSampler = "dpmpp_2m";
		if(cfgCfg == null) cfgCfg = 7;

		// Generate one image per cumulative level
		for(WearLevelEnumType level : levels) {
			String prompt = NarrativeUtil.getMannequinPrompt(apparel, level, config);
			String negPrompt = NarrativeUtil.getMannequinNegativePrompt();

			SWTxt2Img s2i = new SWTxt2Img();
			s2i.setPrompt(prompt);
			s2i.setNegativePrompt(negPrompt);
			s2i.setWidth(512);
			s2i.setHeight(768);
			s2i.setSteps(cfgSteps);
			s2i.setModel(cfgModel);
			s2i.setScheduler(cfgScheduler);
			s2i.setSampler(cfgSampler);
			s2i.setCfgScale(cfgCfg);
			s2i.setSeed((int)(useSeed & 0x7FFFFFFF));  // Ensure positive int
			s2i.setImages(1);

			if(hires && config.get("refinerModel") != null) {
				s2i.setRefinerScheduler(config.get("refinerScheduler"));
				s2i.setRefinerSampler(config.get("refinerSampler"));
				s2i.setRefinerMethod(config.get("refinerMethod"));
				s2i.setRefinerModel(config.get("refinerModel"));
				s2i.setRefinerSteps(config.get("refinerSteps"));
				s2i.setRefinerUpscale(config.get("refinerUpscale"));
				s2i.setRefinerUpscaleMethod(config.get("refinerUpscaleMethod"));
				s2i.setRefinerCfgScale(config.get("refinerCfg"));
				s2i.setRefinerControlPercentage(config.get("refinerControlPercentage"));
			} else {
				s2i.setRefinerControlPercentage(0.0);
			}

			String name = apparelName + " - " + level.toString() + " - " + rando + " - " + useSeed;

			try {
				logger.info("Generating mannequin image: " + name + " model=" + cfgModel + " steps=" + cfgSteps);
				if(logger.isDebugEnabled()) {
					logger.debug("Prompt: " + prompt);
				}
				SWImageResponse rep = txt2img(s2i);
				if(rep == null) {
					logger.error("Null response from txt2img for level " + level + " - check SWarm server connection");
					continue;
				}
				if(rep.getImages() == null || rep.getImages().isEmpty()) {
					logger.error("No images in response for level " + level);
					continue;
				}

				for(String bai : rep.getImages()) {
					byte[] dataTest = ClientUtil.get(byte[].class, ClientUtil.getResource(autoserver + "/" + bai), null, MediaType.APPLICATION_OCTET_STREAM_TYPE);
					SWImageInfo info = SWUtil.extractInfo(dataTest);
					int seedl = (int)useSeed;
					if(info != null && info.getImageParams() != null) {
						seedl = info.getImageParams().getSeed();
					}
					if(dataTest == null || dataTest.length == 0) {
						logger.error("Could not retrieve image data from swarm server for " + bai);
						continue;
					}

					String dname = name;
					Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
					q.field(FieldNames.FIELD_NAME, dname);
					BaseRecord data = IOSystem.getActiveContext().getSearch().findRecord(q);

					if(data == null) {
						ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
						clist.parameter(FieldNames.FIELD_NAME, dname);
						data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
						data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
						data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
						AttributeUtil.addAttribute(data, "seed", seedl);
						AttributeUtil.addAttribute(data, "wearLevel", level.toString());
						AttributeUtil.addAttribute(data, "s2i", JSONUtil.exportObject(s2i));
						IOSystem.getActiveContext().getAccessPoint().create(user, data);
					} else {
						data.set(FieldNames.FIELD_BYTE_STORE, dataTest);
						IOSystem.getActiveContext().getAccessPoint().update(user, data);
					}
					images.add(data);
				}
			} catch(Exception e) {
				logger.error("Error generating mannequin image for level " + level, e);
			}
			// Don't increment seed - user wants consistent seed across all levels
		}
		return images;
	}

}
