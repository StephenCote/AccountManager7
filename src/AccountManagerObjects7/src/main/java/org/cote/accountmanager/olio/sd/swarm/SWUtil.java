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
	public static SWTxt2Img newTxt2Img(BaseRecord person, BaseRecord sdConfig, String setting, String pictureType, String bodyType, String verb, int steps) {
		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(NarrativeUtil.getSDPrompt(null,  ProfileUtil.getProfile(null, person), person, sdConfig, setting, pictureType, bodyType, verb));
		s2i.setNegativePrompt(NarrativeUtil.getSDNegativePrompt(person));
		s2i.setSeed(Math.abs(rand.nextInt()));
		s2i.setSteps(sdConfig.get("steps"));
		s2i.setModel(sdConfig.get("model"));
		s2i.setScheduler(sdConfig.get("scheduler"));
		s2i.setSampler(sdConfig.get("sampler"));
		s2i.setCfgScale(sdConfig.get("cfg"));

		if((Boolean)sdConfig.get("hires") == true) {
			s2i.setRefinerScheduler(sdConfig.get("refinerScheduler"));
			s2i.setRefinerSampler(sdConfig.get("refinerSampler"));
			s2i.setRefinerMethod(sdConfig.get("refinerMethod"));
			s2i.setRefinerModel(sdConfig.get("refinerModel"));
			s2i.setRefinerSteps((Integer) sdConfig.get("refinerSteps"));
			s2i.setRefinerUpscale((Integer) sdConfig.get("refinerUpscale"));
			s2i.setRefinerUpscaleMethod(sdConfig.get("refinerUpscaleMethod"));
			s2i.setRefinerCfgScale((Integer) sdConfig.get("refinerCfg"));
			s2i.setRefinerControlPercentage((Double) sdConfig.get("refinerControlPercentage"));
		}
		else {
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
