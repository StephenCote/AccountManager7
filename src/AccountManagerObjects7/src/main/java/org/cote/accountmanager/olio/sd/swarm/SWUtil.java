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

		s2i.setPrompt(org.cote.accountmanager.olio.sd.SDUtil.appendLoras(NarrativeUtil.getSDPrompt(null,  ProfileUtil.getProfile(null, person), person, cfg, setting, pictureType, bodyType, verb), cfg));
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

		s2i.setPrompt(org.cote.accountmanager.olio.sd.SDUtil.appendLoras(prompt, cfg));
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

	/// Build a FLUX Kontext scene request using a single stitched composite as the prompt image.
	/// The composite contains [portrait1 | portrait2 | landscape] stitched side-by-side.
	/// Caller creates the composite via SDUtil.stitchSceneImages() and passes it as a single promptImage.
	/// @param sysCharDesc   SD description of the first character (left panel)
	/// @param usrCharDesc   SD description of the second character (center panel)
	/// @param sceneDesc     LLM-generated scene verb phrase (what the characters are doing)
	/// @param settingDesc   Setting/location description from chatConfig
	/// @param sdConfig      SD config record (for kontextModel override); may be null
	public static SWTxt2Img newKontextSceneTxt2Img(String sysCharDesc, String usrCharDesc, String sceneDesc, String settingDesc, BaseRecord sdConfig) {
		return newKontextSceneTxt2Img(sysCharDesc, usrCharDesc, sceneDesc, settingDesc, null, null, sdConfig);
	}

	/// Style/mood-aware overload of the FLUX Kontext scene request builder.
	/// When style is null/empty AND mood is null/empty the produced prompt is BYTE-IDENTICAL to the
	/// legacy 5-arg output, so existing callers (ChatService, tests) are unaffected.
	/// @param style   user-selected style (see configModel.json style limit); may be null/empty
	/// @param mood    per-scene mood; appended as a clause when non-empty; may be null/empty
	public static SWTxt2Img newKontextSceneTxt2Img(String sysCharDesc, String usrCharDesc, String sceneDesc, String settingDesc, String style, String mood, BaseRecord sdConfig) {
		return newKontextSceneTxt2Img(sysCharDesc, usrCharDesc, sceneDesc, settingDesc, style, mood, sdConfig, null, null, null);
	}

	/// Full overload accepting explicit steps/cfgScale/negativePrompt overrides. Added so callers
	/// that already resolve their own generation params (e.g. PictureBookUtil's params.steps/cfg)
	/// aren't silently ignored by Kontext the way every other stage (portraits, landscape, classic
	/// pipeline) respects them. Passing null for steps/cfgScale/negativePrompt reproduces the exact
	/// legacy behavior (steps=28, cfgScale=1, negativePrompt="") that ChatService.generateScene and
	/// existing tests (TestKontext, TestPictureBookPipeline) depend on byte-for-byte — this overload
	/// is purely additive, no existing call site's behavior changes.
	/// @param steps          explicit sampling steps; null preserves the legacy hardcoded 28
	/// @param cfgScale       explicit CFG scale; null preserves the legacy hardcoded 1
	/// @param negativePrompt explicit negative prompt; null preserves the legacy hardcoded ""
	public static SWTxt2Img newKontextSceneTxt2Img(String sysCharDesc, String usrCharDesc, String sceneDesc, String settingDesc,
			String style, String mood, BaseRecord sdConfig, Integer steps, Integer cfgScale, String negativePrompt) {
		SWTxt2Img s2i = newKontextBase(sdConfig, steps, cfgScale);

		/// Strip SDXL-style prompt weighting — FLUX doesn't support ((...:1.5)) syntax
		String cleanSys = stripSDXLWeighting(sysCharDesc);
		String cleanUsr = stripSDXLWeighting(usrCharDesc);

		StringBuilder prompt = new StringBuilder();
		prompt.append("Combine the reference images into one cohesive scene. ");
		prompt.append("Place both people from the left and center panels into the environment shown in the right panel. ");
		if (cleanSys != null && !cleanSys.isEmpty()) {
			prompt.append("The first person is ").append(cleanSys).append(". ");
		}
		if (cleanUsr != null && !cleanUsr.isEmpty()) {
			prompt.append("The second person is ").append(cleanUsr).append(". ");
		}
		if (sceneDesc != null && !sceneDesc.isEmpty()) {
			prompt.append("They are ").append(sceneDesc).append(". ");
		}
		if (settingDesc != null && !settingDesc.isEmpty()) {
			prompt.append("The setting is ").append(settingDesc).append(". ");
		}
		if (mood != null && !mood.isEmpty()) {
			prompt.append("The mood is ").append(mood).append(". ");
		}
		prompt.append("Maintain their exact appearances, clothing, and features. ");
		prompt.append(styleClause(style));

		s2i.setPrompt(prompt.toString());
		s2i.setNegativePrompt(negativePrompt != null ? negativePrompt : "");
		return s2i;
	}

	/// Map a configModel.json style value to short natural-language FLUX phrasing.
	/// The default (null/empty/unknown) is the legacy photograph phrasing, so the byte-identical
	/// guarantee of the legacy Kontext prompt holds. Comparison is case-insensitive.
	public static String styleClause(String style) {
		String photo = "Natural lighting consistent with the background. High quality photograph.";
		if (style == null) return photo;
		String s = style.trim().toLowerCase();
		if (s.isEmpty()) return photo;
		switch (s) {
			case "photograph":
			case "selfie":
			case "custom":
				return photo;
			case "illustration":
				return "Rendered as a detailed illustration.";
			case "art":
			case "digitalart":
				return "Digital painting art style.";
			case "movie":
				return "Cinematic film still.";
			case "anime":
				return "Anime art style.";
			case "comic":
				return "Comic book art style.";
			case "portrait":
				return "Studio portrait photograph.";
			case "fashion":
				return "High fashion editorial photograph.";
			case "vintage":
				return "Vintage film photograph aesthetic.";
			default:
				return photo;
		}
	}

	/// Create base Kontext SWTxt2Img with model and optimal defaults.
	private static SWTxt2Img newKontextBase(BaseRecord sdConfig) {
		return newKontextBase(sdConfig, null, null);
	}

	/// Same as {@link #newKontextBase(BaseRecord)} but with optional explicit steps/cfgScale
	/// overrides — null preserves the legacy hardcoded 28/1 (see
	/// {@link #newKontextSceneTxt2Img(String, String, String, String, String, String, BaseRecord, Integer, Integer, String)}).
	private static SWTxt2Img newKontextBase(BaseRecord sdConfig, Integer steps, Integer cfgScale) {
		SWTxt2Img s2i = new SWTxt2Img();

		String kontextModel = null;
		if (sdConfig != null) {
			try { kontextModel = sdConfig.get("kontextModel"); } catch (Exception e) { /* ignore */ }
		}
		if (kontextModel == null || kontextModel.isEmpty()) {
			kontextModel = "flux1Kontext_flux1KontextDev";
		}
		s2i.setModel(kontextModel);

		s2i.setSteps(steps != null ? steps : 28);
		s2i.setCfgScale(cfgScale != null ? cfgScale : 1);
		s2i.setSampler("euler");
		s2i.setScheduler("simple");
		s2i.setSeed(Math.abs(rand.nextInt()));
		s2i.setWidth(1024);
		s2i.setHeight(1024);
		s2i.setRefinerControlPercentage(0.0);

		return s2i;
	}

	/// Strip SDXL-style prompt weighting syntax from descriptions.
	/// Removes patterns like ((word:1.5)), (word), and ((word)) leaving just the text.
	/// FLUX Kontext ignores this syntax, so it just clutters the prompt.
	public static String stripSDXLWeighting(String desc) {
		if (desc == null || desc.isEmpty()) return desc;
		/// Remove weight numbers like :1.5)
		String clean = desc.replaceAll(":\\d+\\.?\\d*\\)", ")");
		/// Remove all parentheses used for emphasis grouping
		clean = clean.replaceAll("[()]", "");
		/// Collapse multiple spaces
		clean = clean.replaceAll("\\s+", " ").trim();
		return clean;
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
