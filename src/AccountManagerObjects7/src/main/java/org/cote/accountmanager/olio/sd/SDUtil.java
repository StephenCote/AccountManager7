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
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.DocumentUtil;
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
	
	/// Shared across ALL SDUtil instances/requests, keyed by server URL — a fresh anonymous
	/// session used to be minted on every single txt2img/listModels/listLoras call (never reused,
	/// never closed), which meant a single multi-scene PictureBook "Generate All" run (2-4 SD
	/// calls per scene: up to 2 portraits + landscape + composite) accumulated one lingering
	/// Swarm session per call — observed as 8 simultaneous sessions from one client on a small
	/// book. Reusing one session per server until Swarm itself rejects it fixes that.
	private static final java.util.concurrent.ConcurrentHashMap<String, String> sessionCache = new java.util.concurrent.ConcurrentHashMap<>();

	private String getOrCreateSession() {
		return sessionCache.computeIfAbsent(autoserver, SWUtil::getAnonymousSession);
	}

	private void invalidateSession() {
		sessionCache.remove(autoserver);
	}

	public SWImageResponse txt2img(SWTxt2Img req) {
		if (req.getSession_id() == null || req.getSession_id().isEmpty()) {
			String sess = getOrCreateSession();
			if (sess == null || sess.isEmpty()) {
				logger.error("Could not obtain anonymous session");
				return null;
			}
			req.setSession_id(sess);
		}
		SWImageResponse resp = doTxt2Img(req);
		// The reused session may have expired/been invalidated server-side since it was cached —
		// retry exactly once with a freshly-minted session before giving up.
		if (resp != null && resp.getError() != null && !resp.getError().isEmpty()) {
			logger.warn("txt2img error (\"" + resp.getError() + "\") — retrying once with a fresh session");
			invalidateSession();
			String freshSess = getOrCreateSession();
			if (freshSess != null && !freshSess.isEmpty()) {
				req.setSession_id(freshSess);
				resp = doTxt2Img(req);
			}
		}
		return resp;
	}

	private SWImageResponse doTxt2Img(SWTxt2Img req) {
		String payload = JSONUtil.exportObject(req);
		int payloadLen = payload != null ? payload.length() : 0;
		boolean hasPromptImages = req.getPromptImages() != null && !req.getPromptImages().isEmpty();
		boolean hasInitImage = req.getInitImage() != null && !req.getInitImage().isEmpty();
		logger.info("txt2img request: payloadSize=" + payloadLen + " hasPromptImages=" + hasPromptImages + " hasInitImage=" + hasInitImage);

		// TEMPORARY diagnostic logging (thermal investigation) — every SD-config parameter
		// actually placed on the outgoing request, so a unit-test run and a real Ux-driven run
		// can be compared line-for-line. Image/promptImages payloads are logged as size/count
		// only (the base64 bodies are megabytes and not the parameter in question). Remove once
		// the Ux-vs-unit-test discrepancy is found.
		logger.info("txt2img sdConfig: model=" + req.getModel()
			+ " steps=" + req.getSteps()
			+ " cfgScale=" + req.getCfgScale()
			+ " sampler=" + req.getSampler()
			+ " scheduler=" + req.getScheduler()
			+ " width=" + req.getWidth()
			+ " height=" + req.getHeight()
			+ " images=" + req.getImages()
			+ " seed=" + req.getSeed()
			+ " refinerModel=" + req.getRefinerModel()
			+ " refinerSteps=" + req.getRefinerSteps()
			+ " refinerCfgScale=" + req.getRefinerCfgScale()
			+ " refinerSampler=" + req.getRefinerSampler()
			+ " refinerScheduler=" + req.getRefinerScheduler()
			+ " refinerMethod=" + req.getRefinerMethod()
			+ " refinerUpscale=" + req.getRefinerUpscale()
			+ " refinerUpscaleMethod=" + req.getRefinerUpscaleMethod()
			+ " refinerControlPercentage=" + req.getRefinerControlPercentage()
			+ " initImageCreativity=" + req.getInitImageCreativity()
			+ " hasInitImage=" + hasInitImage + " initImageChars=" + (req.getInitImage() != null ? req.getInitImage().length() : 0)
			+ " hasPromptImages=" + hasPromptImages + " promptImagesCount=" + (req.getPromptImages() != null ? req.getPromptImages().size() : 0)
			+ " negativePrompt=" + req.getNegativePrompt()
			+ " prompt=" + req.getPrompt());

		SWImageResponse resp = ClientUtil.post(SWImageResponse.class, ClientUtil.getResource(autoserver + "/API/GenerateText2Image"), payload, MediaType.APPLICATION_JSON_TYPE);
		if (resp == null) {
			logger.error("txt2img: null response from Swarm — check ClientUtil warnings for HTTP status/body");
		} else if (resp.getError() != null && !resp.getError().isEmpty()) {
			logger.error("txt2img: Swarm returned error: " + resp.getError());
		} else if (resp.getImages() == null || resp.getImages().isEmpty()) {
			logger.warn("txt2img: Swarm returned 200 but images list is empty");
		}
		return resp;
	}

	public List<String> listModels() {
		List<String> models = new ArrayList<>();
		try {
			String sess = getOrCreateSession();
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

	public List<String> listLoras() {
		List<String> loras = new ArrayList<>();
		try {
			String sess = getOrCreateSession();
			if (sess == null || sess.isEmpty()) {
				logger.warn("Could not obtain anonymous session for listing LORAs");
				return loras;
			}
			SWModelListResponse resp = ClientUtil.post(
				SWModelListResponse.class,
				ClientUtil.getResource(autoserver + "/API/ListModels"),
				"{\"session_id\":\"" + sess + "\",\"path\":\"\",\"depth\":2,\"subtype\":\"LoRA\"}",
				MediaType.APPLICATION_JSON_TYPE
			);
			if (resp != null && resp.getFiles() != null) {
				for (Map<String, Object> file : resp.getFiles()) {
					Object name = file.get("name");
					if (name != null) {
						loras.add(name.toString());
					}
				}
			}
		} catch (Exception e) {
			logger.warn("Could not list LORAs from SD server: " + e.getMessage());
		}
		return loras;
	}

	/**
	 * Append LORA entries from sdConfig to a prompt string, and normalize the prompt's typography
	 * to plain ASCII on the way through.
	 * Entries are formatted as &lt;lora:name:weight&gt; and comma-separated at the end.
	 * <p>
	 * The normalization rides here because this is the seam that every LLM-composed prompt in this
	 * class already passes through on its way to {@code setPrompt(...)}, so it is the last place a
	 * prompt can be corrected before it becomes literal SD input. (One {@code setPrompt} call does
	 * NOT route through here: {@code generateSDFigurines}' {@code NarrativeUtil.getSDFigurinePrompt}
	 * path, which is template-built rather than LLM-composed and so does not exhibit this. It is
	 * therefore still unnormalized — worth revisiting if figurine prompts ever become LLM-composed.)
	 * LLM-composed prompts routinely arrive carrying Unicode typography the
	 * model produced itself — most often U+2011 non-breaking hyphens in compounds
	 * ({@code gull-wing}, {@code neon-lit}, {@code high-altitude}, confirmed live in the picture-book
	 * scene prompts) and U+2014 em dashes. These are not mojibake; the text is well-formed UTF-8 end
	 * to end. But CLIP's byte-level BPE tokenizes {@code gull<U+2011>wing} differently from
	 * {@code gull-wing}, spending extra tokens on a compound the model then matches less cleanly.
	 * Normalizing the LLM's *inputs* cannot fix this — the characters come out of the LLM's own
	 * output — so it has to happen on the finished prompt.
	 */
	public static String appendLoras(String prompt, BaseRecord sdConfig) {
		if (prompt == null) return prompt;
		prompt = DocumentUtil.replaceSmartQuotes(prompt);
		if (sdConfig == null) return prompt;
		try {
			List<String> loras = sdConfig.get("loras");
			if (loras != null && !loras.isEmpty()) {
				StringBuilder buff = new StringBuilder(prompt);
				for (String lora : loras) {
					if (lora == null || lora.isEmpty()) continue;
					// Entry format is "name:weight" — convert to "<lora:name:weight>"
					buff.append(", <lora:").append(lora).append(">");
				}
				return buff.toString();
			}
		} catch (Exception e) {
			// loras field may not exist on the config
		}
		return prompt;
	}

	public int getSteps() {
		return steps;
	}

	public void setSteps(int steps) {
		this.steps = steps;
	}
	
	/**
	 * KI-34. Storage group for a character's generated portrait/figurine images.
	 *
	 * <p>This used to be {@code {world.gallery.path}/Characters/{name}} — the WORLD's gallery, shared
	 * across every book, context and run in the organization, keyed purely by the character's display
	 * name. Two {@code charPerson} records that happen to share a name (a recurring name across
	 * separate PictureBook runs, the same story re-run, or simply two stories that both have a
	 * "Jideon") therefore resolved to the identical storage group and overwrote or shared each
	 * other's portraits.
	 *
	 * <p>Every charPerson already lives somewhere specific, so its OWN group is the natural scope:
	 * a book's {@code .../{Book}/Characters} when created via PictureBook, the world's population
	 * group otherwise. {@code groupPath} is a default query field on everything deriving from
	 * {@code data.directory} (virtual, computed by {@code PathProvider} from {@code groupId}), but it
	 * is virtual, so a caller that planned only {@code groupId} can still arrive here without it —
	 * hence the explicit resolve-from-groupId step before falling back.
	 *
	 * <p>The character name is kept as the leaf so the layout stays browsable and two characters in
	 * the SAME group don't share a folder; it is the ROOT that changes from world-wide to
	 * character-scoped. Falls back to the original world-gallery scheme only when the character has
	 * no resolvable group of its own.
	 *
	 * <p>Out of scope per KI-34: apparel/mannequin image storage
	 * ({@code generateMannequinImages}, {@code OlioService.reimageApparel}) is a separate concern
	 * and is deliberately untouched.
	 */
	public static String resolveCharacterImagePath(OlioContext octx, BaseRecord per) {
		/// REVERTED 2026-08-10 to the original world-gallery path. See KI-34/KI-61.
		///
		/// The KI-34 change returned the character's OWN scope — groupPath + "/" + name + "/Gallery" —
		/// to stop two same-named characters sharing storage. It broke character reimage outright:
		///
		///   PathUtil - Not authorized to create auth.group of type (DATA) node François Touvier
		///     with parent #3535 in path /home/steve/Data/PictureBooks/catatone 3/Characters/François Touvier/Gallery
		///
		/// Both call sites below run as the OLIO USER (createPersonImage/createPersonFigurine are
		/// passed octx.getOlioUser()), and a PictureBook character's groupPath is inside the ACTING
		/// user's home. So the change moved the write target out of the Olio world — where the Olio
		/// user has create rights — and into a tree where it has none. I changed the location without
		/// changing the principal.
		///
		/// A real fix for the name collision has to solve BOTH: pick a collision-free location AND
		/// ensure the principal doing the makePath is authorized to create there (either create as the
		/// character's owner, or scope the path somewhere the Olio user owns). Until then the
		/// collision described in KI-34 stands.
		String basePath = (octx != null && octx.getWorld() != null) ? octx.getWorld().get("gallery.path") : null;
		return basePath + "/Characters/" + per.get(FieldNames.FIELD_NAME);
	}

	public void generateSDFigurines(OlioContext octx, List<BaseRecord> pop, int batchSize, boolean export, boolean hires, int seed) {

		SecureRandom rand = new SecureRandom();
		for(BaseRecord per : pop) {
			List<BaseRecord> nars = NarrativeUtil.getCreateNarrative(octx, Arrays.asList(new BaseRecord[] {per}), "random");
			BaseRecord nar = nars.get(0);
			BaseRecord prof = per.get(FieldNames.FIELD_PROFILE);
			
			IOSystem.getActiveContext().getReader().populate(nar, new String[] {"images"});
			String path = resolveCharacterImagePath(octx, per);
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
		for(BaseRecord per : pop) {
			List<BaseRecord> nars = NarrativeUtil.getCreateNarrative(octx, Arrays.asList(new BaseRecord[] {per}), setting);
			BaseRecord nar = nars.get(0);
			BaseRecord prof = per.get(FieldNames.FIELD_PROFILE);
			
			IOSystem.getActiveContext().getReader().populate(nar, new String[] {"images"});
			
			String path = resolveCharacterImagePath(octx, per);
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
		/// Complete any per-style detail fields the caller left unset BEFORE composing.
		///
		/// Every branch below concatenates cfg.get("<detail>") straight into the clause, so a missing
		/// field becomes the literal text "null" in the prompt:
		///   "(Comic book panel) in (null) style from the (null) with (null)."
		/// That is exactly what a style CHANGE produces — the fields belong to the previous style, and
		/// the new style's are unset. Reported by Stephen 2026-08-10 as styles being "mangled" coming
		/// from the Ux. fillStyleDefaults is idempotent and only fills MISSING fields, so an explicitly
		/// configured detail is never overwritten; this just guarantees the clause is complete.
		fillStyleDefaults(cfg);
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

		sd.setValue("style", randomSDConfigValue(cfg, "styles"));
		fillStyleDefaults(sd);

		/// The schema default for 'model' is a last-resort placeholder; the authoritative
		/// runtime default comes from sd.default.model (Service7 init-param / Console7
		/// resource.properties) via SDUtil.setDefaultModel(). Apply it here so that
		/// randomSDConfig() always uses the deployment-configured checkpoint rather than
		/// the schema placeholder, which may not exist on the configured SD server.
		String runtimeDefault = getDefaultModel();
		if (runtimeDefault != null && !runtimeDefault.isBlank()) {
			sd.setValue(OlioFieldNames.FIELD_SD_MODEL, runtimeDefault);
		}

		return sd;
	}

	public static String randomSDConfigValue(BaseRecord cfg, String fieldName) {
		List<String> vals = cfg.get(fieldName);
		return vals.get(rand.nextInt(vals.size()));
	}

	/**
	 * Fill only the MISSING per-style detail fields for a config's current style, drawing from the same
	 * sdConfigData pools randomSDConfig uses. Idempotent — already-set fields are left alone — so it
	 * safely COMPLETES a config that a caller (or an override) only partially specified, which is what
	 * makes {@link #getSDConfigPrompt(BaseRecord)} produce a full style string instead of null-filled
	 * garbage. If no style is set yet, a random canonical style is chosen first. Mirrors the Ux
	 * am7sd.fillStyleDefaults primitive (components/sdConfig.js).
	 */
	public static void fillStyleDefaults(BaseRecord sd) {
		if(sd == null) {
			return;
		}
		BaseRecord cfg = getConfigData();
		String style = sd.get("style");
		if(style == null || style.isEmpty()) {
			style = randomSDConfigValue(cfg, "styles");
			sd.setValue("style", style);
		}
		switch(style) {
			case "art":
				fillIfBlank(sd, cfg, "artStyle", "artStyles");
				break;
			case "photograph":
				fillIfBlank(sd, cfg, "colorProcess", "colorProcesses");
				fillIfBlank(sd, cfg, "stillCamera", "stillCameras");
				fillIfBlank(sd, cfg, "photographer", "photographers");
				fillIfBlank(sd, cfg, "lens", "lenses");
				fillIfBlank(sd, cfg, "film", "films");
				break;
			case "movie":
				fillIfBlank(sd, cfg, "colorProcess", "colorProcesses");
				fillIfBlank(sd, cfg, "movieFilm", "movieFilms");
				fillIfBlank(sd, cfg, "movieCamera", "movieCameras");
				fillIfBlank(sd, cfg, "director", "directors");
				break;
			case "selfie":
				fillIfBlank(sd, cfg, "selfiePhone", "selfiePhones");
				fillIfBlank(sd, cfg, "selfieAngle", "selfieAngles");
				fillIfBlank(sd, cfg, "selfieLighting", "selfieLightings");
				break;
			case "anime":
				fillIfBlank(sd, cfg, "animeStudio", "animeStudios");
				fillIfBlank(sd, cfg, "animeEra", "animeEras");
				break;
			case "portrait":
				fillIfBlank(sd, cfg, "portraitLighting", "portraitLightings");
				fillIfBlank(sd, cfg, "portraitBackdrop", "portraitBackdrops");
				fillIfBlank(sd, cfg, "photographer", "photographers");
				break;
			case "comic":
				fillIfBlank(sd, cfg, "comicPublisher", "comicPublishers");
				fillIfBlank(sd, cfg, "comicEra", "comicEras");
				fillIfBlank(sd, cfg, "comicColoring", "comicColorings");
				break;
			case "digitalArt":
				fillIfBlank(sd, cfg, "digitalMedium", "digitalMediums");
				fillIfBlank(sd, cfg, "digitalSoftware", "digitalSoftwares");
				fillIfBlank(sd, cfg, "digitalArtist", "digitalArtists");
				break;
			case "fashion":
				fillIfBlank(sd, cfg, "fashionMagazine", "fashionMagazines");
				fillIfBlank(sd, cfg, "fashionDecade", "fashionDecades");
				fillIfBlank(sd, cfg, "photographer", "photographers");
				break;
			case "vintage":
				fillIfBlank(sd, cfg, "vintageDecade", "vintageDecades");
				fillIfBlank(sd, cfg, "vintageProcessing", "vintageProcessings");
				fillIfBlank(sd, cfg, "vintageCamera", "vintageCameras");
				break;
			default:
				break;
		}
	}

	private static void fillIfBlank(BaseRecord sd, BaseRecord cfg, String field, String pool) {
		String cur = sd.get(field);
		if(cur == null || cur.isEmpty()) {
			sd.setValue(field, randomSDConfigValue(cfg, pool));
		}
	}

	private static final java.util.Set<String> SD_OVERRIDE_SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_OWNER_ID,
		FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_NAME
	));

	/**
	 * Overlay a SPARSE override config onto a base config, in place. Only the fields actually PRESENT on
	 * the override record (i.e. a JSON-parsed partial / computed delta) are copied — never a full
	 * newInstance graph, which would clobber the base with model defaults. Identity fields are skipped;
	 * null/blank values are skipped. Mirrors the Ux am7sd.applyOverrides primitive (components/sdConfig.js)
	 * and pairs with a getCardTypeDelta-style per-item delta. Follow with {@link #fillStyleDefaults} on
	 * the base so a style change in the override pulls in that style's detail fields.
	 */
	public static void applyOverrides(BaseRecord base, BaseRecord override) {
		if(base == null || override == null) {
			return;
		}
		for(FieldType f : override.getFields()) {
			String n = f.getName();
			if(SD_OVERRIDE_SKIP.contains(n)) {
				continue;
			}
			Object v = f.getValue();
			if(v == null) {
				continue;
			}
			if(v instanceof String && ((String)v).isEmpty()) {
				continue;
			}
			base.setValue(n, v);
		}
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

	/**
	 * Read a numeric config field as a double, whatever numeric box the record is actually holding.
	 *
	 * <p>{@code BaseRecord.get} is generic, so {@code s2i.setCfgScale(sdConfig.get("cfg"))} compiles
	 * to a cast to Double — but olio.sd.config declares {@code cfg} as an INT, so the record holds an
	 * Integer and the call blew up at runtime with "class java.lang.Integer cannot be cast to class
	 * java.lang.Double". Observed live 2026-08-08 aborting portrait generation:
	 * "Portrait generation error for Catatonic Figure: class java.lang.Integer cannot be cast to
	 * class java.lang.Double" (SDUtil.createImage -> PictureBookUtil.generateSceneImage).
	 *
	 * <p>This is the field-type trap in objects7-reference.md: always match the schema's declared
	 * type. Reading through Number makes the seam immune to it in both directions, which matters
	 * because clients send these values too (reimageApparel's CFG slider even steps by 0.5).
	 */
	private static double numberValue(BaseRecord rec, String fieldName, double defaultValue) {
		if(rec == null) return defaultValue;
		try {
			Object v = rec.get(fieldName);
			return (v instanceof Number) ? ((Number) v).doubleValue() : defaultValue;
		}
		catch(Exception e) {
			return defaultValue;
		}
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
		s2i.setPrompt(appendLoras(prompt, sdConfig));
		String negPrompt = sdConfig.get("negativePrompt");
		if(negPrompt != null && negPrompt.length() > 0) {
			s2i.setNegativePrompt(negPrompt);
		}
		s2i.setSteps(sdConfig.get("steps"));
		s2i.setModel(sdConfig.get("model"));
		s2i.setScheduler(sdConfig.get("scheduler"));
		s2i.setSampler(sdConfig.get("sampler"));
		s2i.setCfgScale(numberValue(sdConfig, "cfg", 7));
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
			s2i.setRefinerControlPercentage(numberValue(sdConfig, "refinerControlPercentage", 0.2));
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
		cfgModel = resolveModel(cfgModel);
		if(cfgScheduler == null) cfgScheduler = "normal";
		if(cfgSampler == null) cfgSampler = "dpmpp_2m";
		if(cfgCfg == null) cfgCfg = 7;

		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(appendLoras(prompt, sdConfig));
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
			s2i.setRefinerControlPercentage(numberValue(config, "refinerControlPercentage", 0.2));
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
		s2i.setPrompt(appendLoras(prompt, config));
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
		s2i.setModel(resolveModel(cfgModel));
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
			s2i.setRefinerControlPercentage(numberValue(config, "refinerControlPercentage", 0.2));
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

	/// Composite portrait images onto a landscape canvas for use as initImage.
	/// Places left portrait in the lower-left third, right portrait in the lower-right third.
	/// If no landscape is provided, creates a blank canvas at the target dimensions.
	/// @param landscapeBytes The landscape PNG bytes (or null for blank canvas)
	/// @param leftPortraitBytes Left character portrait PNG bytes (or null to skip)
	/// @param rightPortraitBytes Right character portrait PNG bytes (or null to skip)
	/// @param canvasWidth Target canvas width (e.g. 1024)
	/// @param canvasHeight Target canvas height (e.g. 768)
	/// @return Composite PNG bytes, or the original landscape if no portraits available
	public static byte[] compositeSceneCanvas(byte[] landscapeBytes, byte[] leftPortraitBytes, byte[] rightPortraitBytes, int canvasWidth, int canvasHeight) {
		// No early-return-the-landscape-unchanged shortcut here even when there are no portraits
		// to draw: the landscape may have come back from a hires/refiner pass already upscaled
		// well past canvasWidth/canvasHeight (e.g. 1024x768 requested, refinerUpscale=2 ->
		// actually 2048x1536), and this method's own resize-to-target-dimensions logic below
		// already handles null portraits correctly (both drawImage blocks are individually
		// null-guarded) — so there's nothing this shortcut saved except skipping that resize,
		// which is exactly the bug: an oversized "composite" then feeds an img2img call that
		// itself requests another hires/refiner/upscale pass on top, compounding.
		try {
			java.awt.image.BufferedImage canvas;
			if (landscapeBytes != null) {
				canvas = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(landscapeBytes));
				/// Scale canvas to target dimensions if needed
				if (canvas.getWidth() != canvasWidth || canvas.getHeight() != canvasHeight) {
					java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(canvasWidth, canvasHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
					java.awt.Graphics2D g = scaled.createGraphics();
					g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g.drawImage(canvas, 0, 0, canvasWidth, canvasHeight, null);
					g.dispose();
					canvas = scaled;
				}
			} else {
				canvas = new java.awt.image.BufferedImage(canvasWidth, canvasHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
			}

			java.awt.Graphics2D g2d = canvas.createGraphics();
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

			/// Portrait target: roughly 1/3 canvas width, placed in lower portion
			int portraitW = canvasWidth / 3;
			int portraitH = (int)(portraitW * 1.33); // ~3:4 aspect for portrait
			if (portraitH > canvasHeight * 3 / 4) {
				portraitH = canvasHeight * 3 / 4;
				portraitW = (int)(portraitH / 1.33);
			}
			int yOffset = canvasHeight - portraitH - (canvasHeight / 20); // slight margin from bottom

			if (leftPortraitBytes != null) {
				java.awt.image.BufferedImage leftImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(leftPortraitBytes));
				int xLeft = canvasWidth / 12; // offset from left edge
				g2d.drawImage(leftImg, xLeft, yOffset, portraitW, portraitH, null);
				logger.info("compositeSceneCanvas: left portrait placed at (" + xLeft + "," + yOffset + ") size " + portraitW + "x" + portraitH);
			}
			if (rightPortraitBytes != null) {
				java.awt.image.BufferedImage rightImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(rightPortraitBytes));
				int xRight = canvasWidth - portraitW - (canvasWidth / 12); // offset from right edge
				g2d.drawImage(rightImg, xRight, yOffset, portraitW, portraitH, null);
				logger.info("compositeSceneCanvas: right portrait placed at (" + xRight + "," + yOffset + ") size " + portraitW + "x" + portraitH);
			}
			g2d.dispose();

			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(canvas, "png", baos);
			byte[] result = baos.toByteArray();
			logger.info("compositeSceneCanvas: composite image " + result.length + " bytes (" + canvasWidth + "x" + canvasHeight + ")");
			return result;
		} catch (Exception e) {
			logger.error("compositeSceneCanvas failed, falling back to landscape only", e);
			return landscapeBytes;
		}
	}

	/// Stitch up to three source images side-by-side into equal square panels for FLUX Kontext.
	/// Each non-null image is center-cropped to square, then scaled to panelSize x panelSize.
	/// Layout: [left | center | right] — e.g. [sysPortrait | usrPortrait | landscape].
	/// @param leftBytes   Left panel image (e.g. system character portrait), or null to skip
	/// @param centerBytes Center panel image (e.g. user character portrait), or null to skip
	/// @param rightBytes  Right panel image (e.g. landscape background), or null to skip
	/// @param panelSize   Size of each square panel in pixels (e.g. 1024)
	/// @return Stitched PNG bytes, or null if all inputs are null
	public static byte[] stitchSceneImages(byte[] leftBytes, byte[] centerBytes, byte[] rightBytes, int panelSize) {
		byte[][] sources = new byte[][] { leftBytes, centerBytes, rightBytes };
		int panelCount = 0;
		for (byte[] src : sources) {
			if (src != null && src.length > 0) panelCount++;
		}
		if (panelCount == 0) return null;

		try {
			int totalWidth = panelCount * panelSize;
			java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(totalWidth, panelSize, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g2d = canvas.createGraphics();
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

			int panelIndex = 0;
			for (byte[] src : sources) {
				if (src == null || src.length == 0) continue;
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(src));
				if (img == null) continue;

				/// Center-crop to square: use the shorter dimension as the crop size
				int w = img.getWidth();
				int h = img.getHeight();
				int cropSize = Math.min(w, h);
				int cropX = (w - cropSize) / 2;
				int cropY = (h - cropSize) / 2;
				java.awt.image.BufferedImage cropped = img.getSubimage(cropX, cropY, cropSize, cropSize);

				/// Draw into panel slot
				int xOffset = panelIndex * panelSize;
				g2d.drawImage(cropped, xOffset, 0, panelSize, panelSize, null);
				panelIndex++;
			}
			g2d.dispose();

			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(canvas, "png", baos);
			byte[] result = baos.toByteArray();
			logger.info("stitchSceneImages: " + panelCount + " panels stitched → " + totalWidth + "x" + panelSize + " (" + result.length + " bytes)");
			return result;
		} catch (Exception e) {
			logger.error("stitchSceneImages failed", e);
			return null;
		}
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
		cfgModel = resolveModel(cfgModel);
		if(cfgScheduler == null) cfgScheduler = "normal";
		if(cfgSampler == null) cfgSampler = "dpmpp_2m";
		if(cfgCfg == null) cfgCfg = 7;

		SWTxt2Img s2i = new SWTxt2Img();
		s2i.setPrompt(appendLoras(prompt, sdConfig));
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
			s2i.setRefinerControlPercentage(numberValue(config, "refinerControlPercentage", 0.2));
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

	/// Deployment-configurable fallback checkpoint, used wherever a request would otherwise have to
	/// invent a model name.
	///
	/// Java used to carry the literal "sdXL_v10VAEFix.safetensors" in seven places. That name does not
	/// exist on every SwarmUI install — verified 2026-08-07, the local server has neither it nor
	/// flux1Kontext_flux1KontextDev, while a second server has both. A wrong checkpoint name does not
	/// fail loudly; Swarm returns an empty image list, which callers log and skip (see KI-39: live SD
	/// tests "passing" green against a server missing the schema-default model).
	///
	/// Resolution order: the value set here at boot (Service7 init-param / Console7 / test
	/// properties), then the olio.sd.config schema's own default for the `model` field, then null.
	/// Null is deliberate — better an explicit "no model configured" error than a guess that silently
	/// produces nothing.
	///
	/// Deployment-global (it describes the SD server this process talks to), boot-pinned, volatile
	/// because the setter runs on the startup thread while readers are request threads.
	public static final String DEFAULT_MODEL_CONFIG_KEY = "sd.default.model";
	private static volatile String defaultModel = null;

	public static void setDefaultModel(String model) {
		String resolved = (model != null && !model.isBlank()) ? model.trim() : null;
		/// Log only on CHANGE - BaseTest re-applies this per test class, and restating an unchanged
		/// value once per class is noise, not information.
		if(!java.util.Objects.equals(resolved, defaultModel)) {
			logger.info("Default SD checkpoint (" + DEFAULT_MODEL_CONFIG_KEY + ") = "
				+ (resolved != null ? resolved : "(unset - will use the olio.sd.config schema default)"));
		}
		defaultModel = resolved;
	}

	/// Resolve the fallback checkpoint. Never invents a literal.
	public static String getDefaultModel() {
		String m = defaultModel;
		if (m != null) return m;
		return schemaDefault(OlioFieldNames.FIELD_SD_MODEL);
	}

	/// Read a field's declared default straight off the olio.sd.config schema, so "use the model
	/// default" means the actual model definition rather than a copy of it in Java.
	public static String schemaDefault(String fieldName) {
		try {
			ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
			if (ms != null) {
				FieldSchema fs = ms.getFieldSchema(fieldName);
				if (fs != null && fs.getDefaultValue() != null) {
					String v = fs.getDefaultValue().toString();
					if (!v.isBlank()) return v;
				}
			}
		} catch (Exception e) {
			logger.warn("Could not read the olio.sd.config schema default for '" + fieldName + "': " + e.getMessage());
		}
		return null;
	}

	/// Apply the resolved fallback when a config supplied no model, logging when nothing is available
	/// rather than shipping a guessed name the server will reject with an empty result.
	public static String resolveModel(String cfgModel) {
		if (cfgModel != null && !cfgModel.isBlank()) return cfgModel;
		String m = getDefaultModel();
		if (m == null) {
			logger.error("No SD checkpoint configured: the request carries no model, "
				+ DEFAULT_MODEL_CONFIG_KEY + " is unset, and olio.sd.config declares no default. "
				+ "Swarm will return no images. Set the model on the config or configure a default.");
		}
		return m;
	}

	/// Classpath home of the mannequin base images. These are byte-identical copies of the two files
	/// Ux752 serves from its own public/media (see getMannequinBaseUrl in Ux752's components/olio.js,
	/// which picks the same x512 pair for the 512x768 size these images are generated at). Ux752
	/// needs its copies to serve them over HTTP and cannot read Objects7's jar, so the duplication is
	/// deliberate; TestMannequinBaseImage asserts the two copies stay byte-identical.
	public static final String MANNEQUIN_BASE_RESOURCE_PATH = "olio/media/";
	public static final String MANNEQUIN_BASE_MALE = "maleModelx512.png";
	public static final String MANNEQUIN_BASE_FEMALE = "femaleModelx512.png";

	/// Output size for mannequin images: SDXL's NATIVE 1024x1024. Do not lower this.
	///
	/// This was 512x768, and the reported "blank white / garbage" mannequins were mostly that. SDXL is
	/// trained at 1024x1024 and degrades badly below it - measured live 2026-08-07 with an identical
	/// prompt, seed, model, sampler and step count, the ONLY variable being resolution:
	///   512x512   flat cartoon illustration, a grid of disconnected garment fragments, no figure
	///   1024x1024 a correct photographic full-body shot wearing the whole described outfit
	/// (I briefly made it 512x512 to match the square base asset, which pushed it even further from
	/// native and made the output worse.)
	///
	/// Square because the base assets are square, so an init image needs neither padding nor stretching.
	/// The 1000x1000 maleModel/femaleModel assets are the near-native pair to use if the init-image path
	/// is ever enabled; the x512 pair is a 2x upscale at this size.
	public static final int MANNEQUIN_IMAGE_WIDTH = 1024;
	public static final int MANNEQUIN_IMAGE_HEIGHT = 1024;

	/// img2img settings for the mannequin base, both MEASURED live 2026-08-07 rather than guessed.
	///
	/// The two are coupled and must be tuned together, which is what I got wrong first time: in img2img
	/// the model only samples for (steps x creativity) iterations. At the shared config's 20 steps a
	/// 0.40-0.70 creativity gives 8-14 effective steps, which renders the base mannequin faithfully but
	/// NEVER paints the garments on - it looks like the init image is being ignored when in fact it is
	/// being obeyed and there simply aren't enough steps to add clothing. I concluded from that sweep
	/// that img2img was the wrong mechanism; Stephen said it should work, and he was right. At 60 steps
	/// the same 0.70/0.85 creativity produces a properly clothed faceless retail mannequin.
	///
	/// MANNEQUIN_STEPS is deliberately separate from the shared `steps` field, which carries a schema
	/// default of 20 - reading that would silently starve the img2img pass.
	public static final double MANNEQUIN_INIT_IMAGE_CREATIVITY = 0.85;
	public static final int MANNEQUIN_STEPS = 60;

	/// Resolve the mannequin base image for a gender ("male"/"female"; anything else, including
	/// null/"unisex", falls to female — the same default Ux752's getMannequinBaseUrl applies with
	/// its `(gender === "male") ? maleModel : femaleModel`). Returns null when the resource is
	/// missing, which callers must treat as "generate without a base image" rather than as fatal.
	///
	/// Returns the image at its stored size (512x512). Callers generating at a different aspect ratio
	/// must run it through fitMannequinBase first — see that method for why.
	public static byte[] getMannequinBaseImage(String gender) {
		String prefix = ("male".equalsIgnoreCase(gender) ? "male" : "female");
		/// Prefer an asset authored at the exact generation size, so a new native-resolution png can be
		/// dropped into olio/media/ and picked up with no code change. Falls back to the x512 pair.
		/// Naming follows the existing convention Stephen used for these assets - {gender}Modelx{SIZE}
		/// (maleModelx512.png, maleModelx1024.png) - NOT {gender}Model{W}x{H}, which is what this
		/// originally looked for and would have silently missed the 1024 pair he added.
		String sized = prefix + "Modelx" + MANNEQUIN_IMAGE_WIDTH + ".png";
		byte[] data = ResourceUtil.getInstance().getBinaryResource(MANNEQUIN_BASE_RESOURCE_PATH + sized);
		if(data != null && data.length > 0) {
			logger.debug("Mannequin base: using size-matched asset " + sized);
			return data;
		}
		String file = ("male".equalsIgnoreCase(gender) ? MANNEQUIN_BASE_MALE : MANNEQUIN_BASE_FEMALE);
		return ResourceUtil.getInstance().getBinaryResource(MANNEQUIN_BASE_RESOURCE_PATH + file);
	}

	/// Fit a mannequin base onto a target canvas without distorting it.
	///
	/// The base images are SQUARE (512x512 — despite Ux752's getMannequinBaseUrl mapping its
	/// "512x768" size to the same "x512" file), while generateMannequinImages renders at 512x768.
	/// Handing a 1:1 init image to a 2:3 request lets the backend stretch it to fit, which elongates
	/// the mannequin — a subtly wrong body that is easy to blame on the prompt. So scale to fit
	/// (preserving aspect) and letterbox the remainder in white, which is also what the mannequin
	/// prompt already asks for ("((white seamless background))"), so the padding reads as background
	/// rather than as an artifact the model has to paint over.
	///
	/// Returns the original bytes unchanged if they already match the target, and null on a decode
	/// failure so the caller can fall back to text-only generation.
	public static byte[] fitMannequinBase(byte[] baseBytes, int targetWidth, int targetHeight) {
		return fitToCanvas(baseBytes, targetWidth, targetHeight, java.awt.Color.WHITE, "fitMannequinBase");
	}

	/// Scale an image to fit a target canvas WITHOUT distorting it, letterboxing the remainder.
	///
	/// This is the alternative to {@link #stitchSceneImages}' center-crop, which discards whatever
	/// doesn't fit the square: a 1024x576 landscape center-cropped to 576x576 loses 44% of its width,
	/// so the setting the scene prompt describes is largely thrown away before the model ever sees it.
	/// For a reference image that is exactly backwards — the whole point is to show the model what the
	/// scene and the people look like.
	///
	/// Returns the original bytes unchanged when they already match, and null on a decode failure.
	/// @param padColor background for the letterbox bars
	/// @param label    caller name for the log line
	public static byte[] fitToCanvas(byte[] srcBytes, int targetWidth, int targetHeight, java.awt.Color padColor, String label) {
		if(srcBytes == null || srcBytes.length == 0) return null;
		try {
			java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(srcBytes));
			if(src == null) {
				logger.warn(label + ": input did not decode as an image");
				return null;
			}
			if(src.getWidth() == targetWidth && src.getHeight() == targetHeight) {
				return srcBytes;
			}
			double scale = Math.min((double)targetWidth / src.getWidth(), (double)targetHeight / src.getHeight());
			int drawW = (int)Math.round(src.getWidth() * scale);
			int drawH = (int)Math.round(src.getHeight() * scale);
			int xOff = (targetWidth - drawW) / 2;
			int yOff = (targetHeight - drawH) / 2;

			java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g2d = canvas.createGraphics();
			g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2d.setColor(padColor);
			g2d.fillRect(0, 0, targetWidth, targetHeight);
			g2d.drawImage(src, xOff, yOff, drawW, drawH, null);
			g2d.dispose();

			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(canvas, "png", baos);
			byte[] out = baos.toByteArray();
			logger.debug(label + ": " + src.getWidth() + "x" + src.getHeight() + " -> " + targetWidth + "x"
				+ targetHeight + " (drawn " + drawW + "x" + drawH + " at " + xOff + "," + yOff + ", letterboxed)");
			return out;
		} catch (Exception e) {
			logger.error(label + " failed", e);
			return null;
		}
	}

	/// Build the reference-image list for a FLUX.2 multi-reference composite.
	///
	/// Unlike the Kontext path, these are NOT pre-stitched into one panel image: FLUX.2 accepts
	/// several independent reference images, which is the guidance's own escalation when identity
	/// drifts (aiDocs/imageComposite.md: "switch to a Flux.2 multi-reference capable checkpoint").
	/// Keeping them separate also removes the two things that made the stitched panel actively
	/// harmful — the center-crop, and the fact that a single wide multi-panel image reads to the
	/// model as one picture OF a reference sheet (observed live: the sheet was rendered into the
	/// output as a poster propped against a wall).
	///
	/// Each reference is normalized to refSize x refSize with aspect preserved and letterboxed, so a
	/// 2048x2048 portrait and a 1024x768 landscape arrive at a consistent scale.
	///
	/// @return base64 data URLs in reference order (people first, setting last); never null, possibly empty
	public static List<String> buildFlux2References(int refSize, byte[]... sources) {
		List<String> refs = new ArrayList<>();
		if(sources == null) return refs;
		int idx = 0;
		for(byte[] src : sources) {
			idx++;
			if(src == null || src.length == 0) continue;
			byte[] fitted = fitToCanvas(src, refSize, refSize, java.awt.Color.WHITE, "flux2Reference[" + idx + "]");
			if(fitted == null) {
				logger.warn("buildFlux2References: reference " + idx + " could not be prepared — skipping");
				continue;
			}
			refs.add("data:image/png;base64," + BinaryUtil.toBase64Str(fitted));
		}
		logger.debug("buildFlux2References: prepared " + refs.size() + " reference image(s) at " + refSize + "x" + refSize);
		return refs;
	}

	/// Generate mannequin images for an apparel record, one image per cumulative wear level.
	/// Seeded from the gender-appropriate mannequin base image (img2img) rather than generated from
	/// text alone, so every wear level of a given apparel set renders the same body and pose and the
	/// images differ only by clothing.
	public List<BaseRecord> generateMannequinImages(BaseRecord user, String groupPath, BaseRecord apparel, BaseRecord sdConfig, boolean hires, long seed) throws FieldException, ValueException, ModelNotFoundException {
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
			// KI-29: the caller's explicit generation parameters were previously discarded here —
			// copy anything the client actually set; fields left unset on sdConfig keep whatever
			// randomSDConfig()/the hardcoded fallbacks below already provided. Use set() (not
			// setValue(), which logs and silently swallows FieldException/ValueException/
			// ModelNotFoundException) so a bad value is a real, visible failure instead of a
			// silently-discarded client override - exactly the failure mode this fix exists to close.
			String style = sdConfig.get(OlioFieldNames.FIELD_STYLE);
			if(style != null) config.set(OlioFieldNames.FIELD_STYLE, style);
			Object hiresObj = sdConfig.get(OlioFieldNames.FIELD_HIRES);
			if(hiresObj != null) config.set(OlioFieldNames.FIELD_HIRES, hiresObj);
			Integer inSteps = sdConfig.get(OlioFieldNames.FIELD_SD_STEPS);
			if(inSteps != null) config.set(OlioFieldNames.FIELD_SD_STEPS, inSteps);
			Integer inCfg = sdConfig.get(OlioFieldNames.FIELD_SD_CFG);
			if(inCfg != null) config.set(OlioFieldNames.FIELD_SD_CFG, inCfg);
			String inSampler = sdConfig.get(OlioFieldNames.FIELD_SD_SAMPLER);
			if(inSampler != null) config.set(OlioFieldNames.FIELD_SD_SAMPLER, inSampler);
			String inScheduler = sdConfig.get(OlioFieldNames.FIELD_SD_SCHEDULER);
			if(inScheduler != null) config.set(OlioFieldNames.FIELD_SD_SCHEDULER, inScheduler);
			String inModel = sdConfig.get(OlioFieldNames.FIELD_SD_MODEL);
			if(inModel != null) config.set(OlioFieldNames.FIELD_SD_MODEL, inModel);
			String inRefinerModel = sdConfig.get(OlioFieldNames.FIELD_SD_REFINER_MODEL);
			if(inRefinerModel != null) config.set(OlioFieldNames.FIELD_SD_REFINER_MODEL, inRefinerModel);
			Object inDenoise = sdConfig.get(OlioFieldNames.FIELD_SD_DENOISING_STRENGTH);
			if(inDenoise != null) config.set(OlioFieldNames.FIELD_SD_DENOISING_STRENGTH, inDenoise);
			List<String> inLoras = sdConfig.get(OlioFieldNames.FIELD_SD_LORAS);
			if(inLoras != null && !inLoras.isEmpty()) config.set(OlioFieldNames.FIELD_SD_LORAS, inLoras);
		}

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));

		// Get config values with fallbacks for required fields
		Integer cfgSteps = config.get(OlioFieldNames.FIELD_SD_STEPS);
		String cfgModel = config.get(OlioFieldNames.FIELD_SD_MODEL);
		String cfgScheduler = config.get(OlioFieldNames.FIELD_SD_SCHEDULER);
		String cfgSampler = config.get(OlioFieldNames.FIELD_SD_SAMPLER);
		Integer cfgCfg = config.get(OlioFieldNames.FIELD_SD_CFG);

		// Apply defaults if model schema defaults weren't applied
		if(cfgSteps == null) cfgSteps = 20;
		cfgModel = resolveModel(cfgModel);
		if(cfgScheduler == null) cfgScheduler = "normal";
		if(cfgSampler == null) cfgSampler = "dpmpp_2m";
		if(cfgCfg == null) cfgCfg = 7;

		// Mannequin base image, resolved once and reused for every wear level so the whole set shares
		// one body/pose. apparel.gender is populated from the wearing character by
		// ApparelUtil.constructApparel (ApparelUtil.java:544) but is not in the default query
		// projection, so read it defensively — a null gender is not an error, it just takes
		// getMannequinBaseImage's female default.
		String apparelGender = apparel.get(FieldNames.FIELD_GENDER);
		byte[] mannequinBase = null;
		String mannequinBase64 = null;
		/// Read the MANNEQUIN-SPECIFIC field, not the shared denoisingStrength.
		///
		/// denoisingStrength is declared "default": 0.75 in configModel.json, so get() never returns
		/// null on a schema-built record - the "else MANNEQUIN_INIT_IMAGE_CREATIVITY" branch could
		/// never fire and the 0.6 was dead code. Confirmed live 2026-08-07: a generation logged
		/// "Init Image Creativity: 0.75". Same trap as steps/width/height and useKontext; the fix is
		/// the same, a dedicated field with no schema default.
		Double cfgDenoise = config.get("mannequinCreativity");
		double mannequinCreativity = (cfgDenoise != null && cfgDenoise > 0)
			? cfgDenoise.doubleValue() : MANNEQUIN_INIT_IMAGE_CREATIVITY;

		/// ON by default: measured working 2026-08-07 at 1024x1024, 60 steps, 0.85 creativity - a
		/// faceless retail mannequin, recognizably the provided asset, wearing the described garments.
		/// The earlier "blank white" reports were two separate mistakes of mine, neither of them the
		/// base image itself: generating at 512x512 (SDXL degrades badly below its native 1024) and
		/// under-sampling the img2img pass. Set false to fall back to text-only generation.
		Boolean useBaseV = config.get("mannequinUseBaseImage");
		boolean useBaseImage = (useBaseV == null) || useBaseV.booleanValue();

		/// Dedicated step count, again NOT the shared `steps` (schema default 20). img2img samples for
		/// only (steps x creativity) iterations, so a 20-step config at 0.85 gives 17 - enough to render
		/// the base mannequin but not to clothe it, which is exactly the failure that made this look
		/// broken. Only applied when the base image is in use; text-only generation keeps the config's
		/// own step count, since it has no creativity multiplier eating into it.
		Integer mqStepsV = config.get("mannequinSteps");
		int mannequinSteps = (mqStepsV != null && mqStepsV > 0) ? mqStepsV.intValue() : MANNEQUIN_STEPS;

		if(useBaseImage) {
			/// The PROVIDED png, unmodified. It was previously run through fitMannequinBase, which
			/// letterboxed the 512x512 asset onto a 512x768 canvas - adding 256px of white padding that
			/// was a third of the init image and none of it Stephen's artwork. Since the asset is square,
			/// the generation is square too (see MANNEQUIN_IMAGE_*), so there is nothing to letterbox
			/// and nothing to stretch.
			mannequinBase = getMannequinBaseImage(apparelGender);
			mannequinBase64 = (mannequinBase != null && mannequinBase.length > 0)
				? BinaryUtil.toBase64Str(mannequinBase) : null;
			if(mannequinBase64 == null) {
				logger.warn("mannequinUseBaseImage=true but the base image for gender '" + apparelGender
					+ "' could not be prepared from " + MANNEQUIN_BASE_RESOURCE_PATH
					+ " - generating text-only instead");
			}
			else {
				logger.debug("Mannequin base image: gender=" + (apparelGender != null ? apparelGender : "(unset, using female default)")
					+ " bytes=" + mannequinBase.length + " creativity=" + mannequinCreativity);
			}
		}

		// Generate one image per cumulative level
		for(WearLevelEnumType level : levels) {
			String prompt = NarrativeUtil.getMannequinPrompt(apparel, level, config);
			String negPrompt = NarrativeUtil.getMannequinNegativePrompt();

			SWTxt2Img s2i = new SWTxt2Img();
			if(mannequinBase64 != null) {
				s2i.setInitImage(mannequinBase64);
				s2i.setInitImageCreativity(mannequinCreativity);
			}
			// Use the merged config (randomSDConfig() defaults + client overrides), not the raw
			// sdConfig param, so LoRAs actually apply (KI-29) even when the client only set some fields.
			s2i.setPrompt(appendLoras(prompt, config));
			s2i.setNegativePrompt(negPrompt);
			s2i.setWidth(MANNEQUIN_IMAGE_WIDTH);
			s2i.setHeight(MANNEQUIN_IMAGE_HEIGHT);
			/// The img2img pass needs the raised count (steps x creativity is what actually samples);
			/// text-only keeps the config's own. Set ONCE here - an earlier attempt set it inside the
			/// init-image block above and this line silently overwrote it.
			s2i.setSteps(mannequinBase64 != null ? mannequinSteps : cfgSteps);
			s2i.setModel(cfgModel);
			s2i.setScheduler(cfgScheduler);
			s2i.setSampler(cfgSampler);
			s2i.setCfgScale(cfgCfg);
			s2i.setSeed((int)(useSeed & 0x7FFFFFFF));  // Ensure positive int
			s2i.setImages(1);

			if(hires && config.get(OlioFieldNames.FIELD_SD_REFINER_MODEL) != null) {
				s2i.setRefinerScheduler(config.get("refinerScheduler"));
				s2i.setRefinerSampler(config.get("refinerSampler"));
				s2i.setRefinerMethod(config.get("refinerMethod"));
				s2i.setRefinerModel(config.get(OlioFieldNames.FIELD_SD_REFINER_MODEL));
				s2i.setRefinerSteps(config.get("refinerSteps"));
				s2i.setRefinerUpscale(config.get("refinerUpscale"));
				s2i.setRefinerUpscaleMethod(config.get("refinerUpscaleMethod"));
				s2i.setRefinerCfgScale(config.get("refinerCfg"));
				s2i.setRefinerControlPercentage(numberValue(config, "refinerControlPercentage", 0.2));
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
					// Link the mannequin image into the apparel's own gallery (foreign list) so it
					// surfaces on the apparel object page — the gallery field exists for exactly this
					// ("images for this apparel at different wear levels") but was never populated,
					// so generated mannequins had no reference from the apparel record and never showed.
					try {
						IOSystem.getActiveContext().getMemberUtil().member(user, apparel, "gallery", data, null, true);
					} catch(Exception me) {
						logger.warn("Failed to link mannequin image " + dname + " into apparel.gallery: " + me.getMessage());
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
