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
		s2i.setModel(org.cote.accountmanager.olio.sd.SDUtil.resolveModel(cfgModel));
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
			s2i.setRefinerControlPercentage(cfg.get("refinerControlPercentage") instanceof Number
				? ((Number) cfg.get("refinerControlPercentage")).doubleValue() : 0.2);
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
		s2i.setModel(org.cote.accountmanager.olio.sd.SDUtil.resolveModel(cfgModel));
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
			s2i.setRefinerControlPercentage(cfg.get("refinerControlPercentage") instanceof Number
				? ((Number) cfg.get("refinerControlPercentage")).doubleValue() : 0.2);
		} else {
			s2i.setRefinerControlPercentage(0.0);
		}

		return s2i;
	}

	/// Fallback FLUX.2 checkpoint, read from the olio.sd.config schema's `flux2Model` default rather
	/// than duplicated as a literal here — checkpoint availability is per-deployment (verified
	/// 2026-08-07: the local Swarm has flux2Klein_9b and flux2_dev but NOT flux1Kontext_flux1KontextDev
	/// or sdXL_v10VAEFix, while another server has the opposite pair). Change the model default in
	/// configModel.json, not in Java.
	public static String defaultFlux2Model() {
		String m = org.cote.accountmanager.olio.sd.SDUtil.schemaDefault("flux2Model");
		return (m != null) ? m : org.cote.accountmanager.olio.sd.SDUtil.getDefaultModel();
	}

	/// Fallback FLUX Kontext checkpoint, likewise from the schema's `kontextModel` default.
	public static String defaultKontextModel() {
		String m = org.cote.accountmanager.olio.sd.SDUtil.schemaDefault("kontextModel");
		return (m != null) ? m : org.cote.accountmanager.olio.sd.SDUtil.getDefaultModel();
	}

	/// FLUX.2 generation defaults now live in the editable resource olio/sd/flux2Defaults.json, read
	/// through Flux2Defaults - NOT as constants here. They were `static final` (cfg 2.5, steps 24,
	/// 1024x768, euler/simple, the short negative prompt), which meant tuning required a code change
	/// and gave a future UI no source for its defaults but hardcoded Java. The model is the one thing
	/// deliberately still configuration rather than resource, because checkpoint names differ per
	/// deployment. These accessors exist so callers and tests read the same resolved values.
	public static double flux2CfgScale()       { return org.cote.accountmanager.olio.sd.Flux2Defaults.cfgScale(); }
	public static int flux2Steps()             { return org.cote.accountmanager.olio.sd.Flux2Defaults.steps(); }
	public static int flux2Width()             { return org.cote.accountmanager.olio.sd.Flux2Defaults.width(); }
	public static int flux2Height()            { return org.cote.accountmanager.olio.sd.Flux2Defaults.height(); }
	public static String flux2NegativePrompt() { return org.cote.accountmanager.olio.sd.Flux2Defaults.negativePrompt(); }

	/// Build a FLUX.2 multi-reference scene request.
	///
	/// Differs from the Kontext builder in the three ways that matter:
	///  1. MULTIPLE independent reference images (people, then setting) instead of one pre-stitched
	///     panel strip. FLUX.2 is multi-reference capable; this is the doc's escalation path when
	///     identity drifts, and it avoids the stitched sheet being read as a picture to draw.
	///  2. Generation params suited to an edit model: CFG 2.5 (not the SDXL cfg the Kontext call was
	///     being handed), 24 steps, landscape output, no refiner block.
	///  3. The doc's explicit multi-reference prompt wording, which names the sources positionally,
	///     demands identity preservation, and forbids extra people.
	///
	/// The prompt also carries an explicit instruction NOT to depict the references as physical
	/// objects. That is not defensive boilerplate: it is the exact observed failure — with the
	/// stitched Kontext reference the model produced a scene containing a printed board of the
	/// character sheet leaning against a wall (media/flux/bad.composite.png).
	///
	/// @param leftDesc    appearance description of the first person; may be null/empty
	/// @param rightDesc   appearance description of the second person; may be null/empty
	/// @param action      what the characters are doing
	/// @param setting     environment description
	/// @param mood        atmosphere/lighting; may be null/empty
	/// @param sdConfig    config for model/steps/cfg/size overrides and the style suffix; may be null
	/// @param refCount    how many reference images will be attached; controls the positional wording
	public static SWTxt2Img newFlux2SceneTxt2Img(String leftDesc, String rightDesc, String action,
			String setting, String mood, BaseRecord sdConfig, int refCount) {
		SWTxt2Img s2i = new SWTxt2Img();

		String model = null;
		if (sdConfig != null) {
			try { model = sdConfig.get("flux2Model"); } catch (Exception e) { /* field may not exist */ }
		}
		s2i.setModel((model != null && !model.isEmpty()) ? model : defaultFlux2Model());

		/// Read ONLY flux2-prefixed fields. Deliberately NOT the shared steps/width/height/cfg: those
		/// carry SDXL-tuned values for the portrait and landscape stages, and the model schema gives
		/// them non-null defaults (steps 20, width 1024, height 1024), so "fall back when unset" would
		/// never fire — a real config would silently force the composite to 1024x1024 square at the
		/// SDXL step count. That is precisely the defect this path exists to fix (the Kontext builder
		/// was handed the SDXL cfg of 5 the same way). One family of parameters per model family.
		Integer cfgSteps = null;
		Integer cfgWidth = null;
		Integer cfgHeight = null;
		Double cfgCfgScale = null;
		if (sdConfig != null) {
			try { cfgSteps = sdConfig.get("flux2Steps"); } catch (Exception e) { /* field may not exist */ }
			try { cfgWidth = sdConfig.get("flux2Width"); } catch (Exception e) { /* field may not exist */ }
			try { cfgHeight = sdConfig.get("flux2Height"); } catch (Exception e) { /* field may not exist */ }
			try { cfgCfgScale = sdConfig.get("flux2Cfg"); } catch (Exception e) { /* field may not exist */ }
		}
		/// Per-book config override, else the editable resource. Two levels only - no Java constants.
		s2i.setSteps(cfgSteps != null && cfgSteps > 0 ? cfgSteps : org.cote.accountmanager.olio.sd.Flux2Defaults.steps());
		s2i.setCfgScale(cfgCfgScale != null && cfgCfgScale > 0 ? cfgCfgScale : org.cote.accountmanager.olio.sd.Flux2Defaults.cfgScale());
		s2i.setWidth(cfgWidth != null && cfgWidth > 0 ? cfgWidth : org.cote.accountmanager.olio.sd.Flux2Defaults.width());
		s2i.setHeight(cfgHeight != null && cfgHeight > 0 ? cfgHeight : org.cote.accountmanager.olio.sd.Flux2Defaults.height());
		s2i.setSampler(org.cote.accountmanager.olio.sd.Flux2Defaults.sampler());
		s2i.setScheduler(org.cote.accountmanager.olio.sd.Flux2Defaults.scheduler());
		s2i.setSeed(Math.abs(rand.nextInt()));
		s2i.setImages(1);
		/// No refiner: the SDXL refiner block the Kontext requests carried (refinercfgscale 7,
		/// PostApply, pixel-lanczos) is meaningless to a FLUX checkpoint. 0.0 keeps it inert.
		s2i.setRefinerControlPercentage(0.0);
		s2i.setNegativePrompt(org.cote.accountmanager.olio.sd.Flux2Defaults.negativePrompt());

		/// Through appendLoras, which is also where SD prompt typography is normalized to plain ASCII.
		/// This called setPrompt directly and so bypassed it entirely - confirmed live 2026-08-08 in a
		/// received Swarm payload containing "graffiti<U+2011>scarred" and "gull<U+2011>wing", the exact
		/// non-breaking hyphens that normalization exists to remove. The whole typography fix was
		/// installed at the appendLoras seam on the grounds that every setPrompt call passed through it;
		/// this builder and the Kontext one did not.
		s2i.setPrompt(org.cote.accountmanager.olio.sd.SDUtil.appendLoras(
			buildFlux2ScenePrompt(leftDesc, rightDesc, action, setting, mood, sdConfig, refCount), sdConfig));
		return s2i;
	}

	/// Compose the FLUX.2 multi-reference prompt. Split out so it can be asserted on directly without
	/// building a whole request or touching a live server.
	public static String buildFlux2ScenePrompt(String leftDesc, String rightDesc, String action,
			String setting, String mood, BaseRecord sdConfig, int refCount) {
		String cleanLeft = stripSDXLWeighting(leftDesc);
		String cleanRight = stripSDXLWeighting(rightDesc);
		boolean twoPeople = (cleanRight != null && !cleanRight.isEmpty());
		boolean hasSettingRef = refCount > (twoPeople ? 2 : 1);

		StringBuilder p = new StringBuilder();
		/// Name the sources positionally, as the doc's example prompt does.
		if (twoPeople) {
			p.append("Combine the exact person and face from the first reference image with the exact "
				+ "person and face from the second reference image. Place both people together in ");
		}
		else {
			p.append("Take the exact person and face from the first reference image and place them in ");
		}
		if (hasSettingRef) {
			p.append("the environment shown in the ").append(twoPeople ? "third" : "second")
				.append(" reference image");
			if (setting != null && !setting.isEmpty()) {
				p.append(" (").append(setting).append(")");
			}
			p.append(". ");
		}
		else if (setting != null && !setting.isEmpty()) {
			p.append(setting).append(". ");
		}
		else {
			p.append("a coherent shared setting. ");
		}

		if (cleanLeft != null && !cleanLeft.isEmpty()) {
			p.append("The first person is ").append(cleanLeft).append(". ");
		}
		if (twoPeople) {
			p.append("The second person is ").append(cleanRight).append(". ");
		}
		if (action != null && !action.isEmpty()) {
			p.append("They are ").append(action).append(". ");
		}
		if (mood != null && !mood.isEmpty()) {
			p.append("The mood is ").append(mood).append(". ");
		}

		/// MEDIUM ANCHOR, positioned where aiDocs/imageComposite.md puts it - immediately after the
		/// placement clause and before the identity demands, not trailing at the very end.
		///
		/// Two things were wrong here. The doc's example reads "Photorealistic, matching lighting,
		/// scale, and perspective" and I dropped "Photorealistic" - the only word in that sentence that
		/// states the medium. And the config's own style string (which declares the medium far more
		/// specifically: "Photograph taken with a Minolta HI-Matic E SLR camera...") was appended LAST,
		/// after a wall of prohibitions. Reported symptom: a cartoonish composite while the SDXL
		/// portraits and landscape it was built from are photorealistic.
		/// The style comes ONLY from getSDConfigPrompt - the same seam the portraits and landscape use,
		/// so one book renders in one style throughout. Nothing is substituted when it is absent.
		///
		/// A "Photorealistic." fallback was briefly added here (the doc's wording, which assumes
		/// photorealism) and removed immediately: injecting a medium this stage invented would break the
		/// invariant that the book's art style carries through portraits -> landscape -> composite from
		/// the one common olio.sd.config. For a comic/anime/digitalArt book it would have actively
		/// fought the configured style. If no style is configured, this prompt says nothing about medium
		/// and lets the checkpoint decide - the same as every other stage.
		///
		/// The legitimate exception is a per-CHARACTER style override, and it does NOT arrive through
		/// this prompt at all. The override is applied when that character's PORTRAIT is (re)generated
		/// during character review, so the style is carried in the portrait's PIXELS - which then reach
		/// FLUX.2 as a reference image. That is why one character can render in a different style inside
		/// an otherwise photographic book without this style clause needing to know anything about it,
		/// and why the clause must stay the book's single style rather than trying to describe per-character
		/// variation in words.
		String cfgStyle = (sdConfig != null)
			? stripSDXLWeighting(org.cote.accountmanager.olio.sd.SDUtil.getSDConfigPrompt(sdConfig)) : null;
		if (cfgStyle != null && !cfgStyle.isEmpty()) {
			p.append(cfgStyle.trim());
			if (!cfgStyle.trim().endsWith(".")) p.append(".");
			p.append(" ");
		}

		/// Identity + coherence demands, per the doc.
		p.append("Preserve facial identity, hair, and clothing precisely. ");
		p.append("Matching lighting, scale, and perspective across the whole image. ");
		p.append("No extra people. ");
		/// The observed failure mode, addressed explicitly: the reference must inform the output, not
		/// appear in it. See media/flux/bad.composite.png.
		// Plain ASCII hyphen deliberately: this string is literal SD input, and CLIP tokenizes a
		// U+2014 em dash as its own junk token (same class of problem SDUtil.appendLoras normalizes).
		p.append("Do not draw the reference images themselves - no photograph, poster, screen, mirror, "
			+ "billboard, framed picture or character sheet anywhere in the scene. ");
		/// "photographic" removed from this clause - the medium is now stated once, up front, by the
		/// config style (or "Photorealistic" when there is none). Saying it twice in different words is
		/// how a comic-styled book ended up being told "photographic" mid-prompt.
		p.append("A single continuous scene, no panels, no split screen, no collage. ");
		return p.toString().trim();
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
		return newKontextSceneTxt2Img(sysCharDesc, usrCharDesc, sceneDesc, settingDesc, style, mood, sdConfig, steps, cfgScale, negativePrompt, false);
	}

	/// Config-style-aware overload. When {@code useConfigStyle} is true the style suffix is derived from
	/// the sdConfig via {@code SDUtil.getSDConfigPrompt} — the canonical, detail-field-driven style used
	/// everywhere else — with SDXL {@code (...)} weighting stripped because FLUX Kontext ignores it. When
	/// false it uses the legacy {@link #styleClause(String)} clause, preserving byte-for-byte behavior for
	/// existing callers (ChatService.generateScene, TestKontext, TestPictureBookPipeline). PictureBook's
	/// composite step passes true so the composite shares the SAME config style as its portraits/landscape.
	public static SWTxt2Img newKontextSceneTxt2Img(String sysCharDesc, String usrCharDesc, String sceneDesc, String settingDesc,
			String style, String mood, BaseRecord sdConfig, Integer steps, Integer cfgScale, String negativePrompt, boolean useConfigStyle) {
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
		if (useConfigStyle) {
			String cfgStyle = stripSDXLWeighting(org.cote.accountmanager.olio.sd.SDUtil.getSDConfigPrompt(sdConfig));
			prompt.append(cfgStyle != null ? cfgStyle : "");
		}
		else {
			prompt.append(styleClause(style));
		}

		/// Through appendLoras for the ASCII typography normalization - see the FLUX.2 builder above;
		/// this call site had the same bypass.
		s2i.setPrompt(org.cote.accountmanager.olio.sd.SDUtil.appendLoras(prompt.toString(), sdConfig));
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
			kontextModel = defaultKontextModel();
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
