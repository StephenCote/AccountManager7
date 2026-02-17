package org.cote.accountmanager.olio.sd.swarm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

import jakarta.ws.rs.core.MediaType;

public class SWUtil {
	public static final Logger logger = LogManager.getLogger(SWUtil.class);
	private static SecureRandom rand = new SecureRandom();
	public static SWTxt2Img newTxt2Img(BaseRecord person, BaseRecord sdConfig, String setting, String pictureType, String bodyType, String verb, int steps, int seed) {
		SWTxt2Img s2i = new SWTxt2Img();

		// Use provided config or create a random one if null
		BaseRecord cfg = sdConfig != null ? sdConfig : org.cote.accountmanager.olio.sd.SDUtil.randomSDConfig();

		s2i.setPrompt(NarrativeUtil.getSDPrompt(null,  ProfileUtil.getProfile(null, person), person, cfg, setting, pictureType, bodyType, verb));
		s2i.setNegativePrompt(NarrativeUtil.getSDNegativePrompt(person));
		s2i.setSeed(Math.abs(rand.nextInt()));

		// Get config values with null-safe defaults
		Integer cfgSteps = cfg.get("steps");
		String cfgModel = cfg.get("model");
		String cfgScheduler = cfg.get("scheduler");
		String cfgSampler = cfg.get("sampler");
		Integer cfgCfg = cfg.get("cfg");
		Integer cfgSeed = cfg.get("seed");
		Boolean hires = cfg.get("hires");

		s2i.setSteps(cfgSteps != null ? cfgSteps : 20);
		s2i.setModel(cfgModel != null ? cfgModel : "sdXL_v10VAEFix.safetensors");
		s2i.setScheduler(cfgScheduler != null ? cfgScheduler : "Karras");
		s2i.setSampler(cfgSampler != null ? cfgSampler : "dpmpp_2m");
		s2i.setCfgScale(cfgCfg != null ? cfgCfg : 7);
		if(cfgSeed != null && cfgSeed > 0) {
			s2i.setSeed(cfgSeed);
		}

		if(hires != null && hires == true) {
			s2i.setRefinerScheduler(cfg.get("refinerScheduler"));
			s2i.setRefinerSampler(cfg.get("refinerSampler"));
			s2i.setRefinerMethod(cfg.get("refinerMethod"));
			s2i.setRefinerModel(cfg.get("refinerModel"));
			s2i.setRefinerSteps(cfg.get("refinerSteps"));
			s2i.setRefinerUpscale(cfg.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(cfg.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(cfg.get("refinerCfg"));
			s2i.setRefinerControlPercentage(cfg.get("refinerControlPercentage"));
		}
		else {
			s2i.setRefinerControlPercentage(0.0);
		}

		return s2i;
	}
	
	/// Build a scene SWTxt2Img from a pre-assembled prompt + SD config.
	/// The prompt and negative prompt are already built (by Chat.generateScenePrompt).
	/// This method applies the SD config's generation parameters (model, steps, sampler, etc.)
	/// and sets landscape-friendly defaults when dimensions aren't explicitly configured.
	public static SWTxt2Img newSceneTxt2Img(String prompt, String negativePrompt, BaseRecord sdConfig) {
		SWTxt2Img s2i = new SWTxt2Img();
		BaseRecord cfg = sdConfig != null ? sdConfig : org.cote.accountmanager.olio.sd.SDUtil.randomSDConfig();

		s2i.setPrompt(prompt);
		s2i.setNegativePrompt(negativePrompt);
		s2i.setSeed(Math.abs(rand.nextInt()));

		Integer cfgSteps = cfg.get("steps");
		String cfgModel = cfg.get("model");
		String cfgScheduler = cfg.get("scheduler");
		String cfgSampler = cfg.get("sampler");
		Integer cfgCfg = cfg.get("cfg");
		Integer cfgSeed = cfg.get("seed");
		Integer cfgWidth = cfg.get("width");
		Integer cfgHeight = cfg.get("height");
		Boolean hires = cfg.get("hires");

		s2i.setSteps(cfgSteps != null ? cfgSteps : 20);
		s2i.setModel(cfgModel != null ? cfgModel : "sdXL_v10VAEFix.safetensors");
		s2i.setScheduler(cfgScheduler != null ? cfgScheduler : "Karras");
		s2i.setSampler(cfgSampler != null ? cfgSampler : "dpmpp_2m");
		s2i.setCfgScale(cfgCfg != null ? cfgCfg : 7);
		if (cfgSeed != null && cfgSeed > 0) {
			s2i.setSeed(cfgSeed);
		}
		/// Default to 4:3 landscape for scene images unless explicitly set
		s2i.setWidth(cfgWidth != null && cfgWidth > 0 ? cfgWidth : 1024);
		s2i.setHeight(cfgHeight != null && cfgHeight > 0 ? cfgHeight : 768);

		if (hires != null && hires) {
			s2i.setRefinerScheduler(cfg.get("refinerScheduler"));
			s2i.setRefinerSampler(cfg.get("refinerSampler"));
			s2i.setRefinerMethod(cfg.get("refinerMethod"));
			s2i.setRefinerModel(cfg.get("refinerModel"));
			s2i.setRefinerSteps(cfg.get("refinerSteps"));
			s2i.setRefinerUpscale(cfg.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(cfg.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(cfg.get("refinerCfg"));
			s2i.setRefinerControlPercentage(cfg.get("refinerControlPercentage"));
		} else {
			s2i.setRefinerControlPercentage(0.0);
		}

		return s2i;
	}

	public static String getAnonymousSession(String server) {
		SWSessionResponse test = ClientUtil.post(SWSessionResponse.class, ClientUtil.getResource(server + "/API/GetNewSession"), "{}", MediaType.APPLICATION_JSON_TYPE);
		if (test == null) {
			logger.error("Session response was null");
			return null;
		}
		return test.getSession_id();
		
	}
	
	public static SWImageInfo extractInfo(byte[] data) throws ImageProcessingException, IOException {
        Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data));
        SWImageInfo info = null;
        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
            		if(directory.getName().equals("PNG-tEXt") && tag.getTagName().equals("Textual Data") && tag.getDescription().startsWith("parameters:")) {
            			info = JSONUtil.importObject(tag.getDescription().substring(11), SWImageInfo.class);
            		}
            		else {
            			//logger.warn("Skip " + directory.getName() + " - " + tag.getTagName() + " = " + tag.getDescription());
            		}
            }
        }
        return info;
	}
}
