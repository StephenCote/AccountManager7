package org.cote.accountmanager.olio.sd;

import java.util.Base64;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;

/// Shared scene-composite pipeline selection and request construction.
///
/// Two callers build "put these people into this setting" image requests: PictureBookUtil's
/// generateSceneImage and ChatService.generateScene. They had independently grown the same
/// three-way branch, which is how they drifted — chat defaulted useKontext=true while the picture
/// book defaulted it false, so the same config produced different pipelines depending on which
/// feature you came through. Worse, the chat copy lives in Service7, which
/// .claude/rules/architecture.md says is transport only. This class is where that decision belongs.
///
/// The three modes and why they differ (verified against Stephen's staged fixtures in
/// AccountManagerObjects7/media/flux):
///   FLUX2   - separate, letterboxed reference images + edit-model parameters. Current best.
///   KONTEXT - one stitched [left|center|right] panel strip. The strip reads to the model as a
///             picture, and it rendered it into the scene as a propped-up board (bad.composite.png).
///   CLASSIC - Graphics2D pastes the portraits onto the landscape, then SDXL img2img. Hard edges,
///             studio backgrounds intact, wrong scale (bad.merge.png).
public class SceneCompositeUtil {

	private static final Logger logger = LogManager.getLogger(SceneCompositeUtil.class);

	public static final String MODE_FLUX2 = "flux2";
	public static final String MODE_KONTEXT = "kontext";
	public static final String MODE_CLASSIC = "classic";

	/// Panel size for the KONTEXT stitched strip. Unchanged from both original call sites.
	private static final int KONTEXT_PANEL_SIZE = 1024;

	private SceneCompositeUtil() { }

	/// Resolve the composite pipeline for a config.
	///
	/// compositeMode wins when set. When it is absent we fall back to the legacy useKontext boolean.
	///
	/// DEFAULTS (corrected 2026-08-10). compositeMode is declared `"default": "flux2"`, so a bare
	/// schema-built record resolves to FLUX.2 — the mode whose checkpoint (flux2Model, default
	/// flux2Klein_9b) actually ships. useKontext now defaults to FALSE and kontextModel has no
	/// default at all; Kontext is an explicit opt-in that must name an installed checkpoint.
	///
	/// It used to be the reverse, and that was the bug: `useKontext` was `"default": true`, and a
	/// schema default is never null, so "unset" was indistinguishable from "explicitly true". Any
	/// book config omitting the field silently ran Kontext, which then sent kontextModel's default
	/// flux1Kontext_flux1KontextDev — absent from the local Swarm — and every composite was refused
	/// with "Invalid model value for param Model", producing nothing. The picture book's comments
	/// claimed classic was its default, but that only held because Ux752 and the tests wrote
	/// useKontext=false explicitly.
	///
	/// Because compositeMode's default is non-null and compositeMode WINS, legacyKontextDefault (and
	/// the legacy boolean) now only take effect for a null config or a record whose compositeMode was
	/// genuinely cleared — i.e. a saved pre-compositeMode config.
	/// (Same schema-default trap as `steps`/`width`/`height`, which is why the flux2 params use
	/// dedicated fields.)
	///
	/// @param legacyKontextDefault what useKontext means when the field is genuinely absent
	public static String resolveMode(BaseRecord sdConfig, boolean legacyKontextDefault) {
		if (sdConfig != null) {
			String mode = null;
			try { mode = sdConfig.get("compositeMode"); } catch (Exception e) { /* field may not exist */ }
			if (mode != null && !mode.isBlank()) {
				String m = mode.trim().toLowerCase();
				if (MODE_FLUX2.equals(m) || MODE_KONTEXT.equals(m) || MODE_CLASSIC.equals(m)) {
					return m;
				}
				logger.warn("Unrecognized compositeMode '" + mode + "' - expected "
					+ MODE_FLUX2 + "|" + MODE_KONTEXT + "|" + MODE_CLASSIC + "; falling back to classic");
				return MODE_CLASSIC;
			}
			try {
				Boolean uk = sdConfig.get("useKontext");
				if (uk != null) return uk.booleanValue() ? MODE_KONTEXT : MODE_CLASSIC;
			} catch (Exception e) { /* field may not exist */ }
		}
		return legacyKontextDefault ? MODE_KONTEXT : MODE_CLASSIC;
	}

	/// Build the fully-configured request for a composite scene, including its reference/init imagery.
	///
	/// Returns null only when the mode is unknown; every mode produces a usable request even with no
	/// reference bytes at all (it degrades to text-only rather than failing the scene).
	///
	/// @param mode        one of MODE_*, from resolveMode
	/// @param leftDesc    appearance description of the first character
	/// @param rightDesc   appearance description of the second character; null/empty for a solo scene
	/// @param action      what the characters are doing
	/// @param setting     environment description
	/// @param mood        atmosphere/lighting; may be null
	/// @param classicPrompt   pre-assembled prompt used by CLASSIC (which does not compose its own)
	/// @param classicNegative negative prompt for CLASSIC
	/// @param leftBytes   first character portrait bytes; may be null
	/// @param rightBytes  second character portrait bytes; may be null
	/// @param landscapeBytes setting reference bytes; may be null
	/// @param creativity  initImageCreativity for CLASSIC's img2img pass
	/// @param sdConfig    the config driving model/params/style
	public static SWTxt2Img buildSceneRequest(String mode, String leftDesc, String rightDesc,
			String action, String setting, String mood, String classicPrompt, String classicNegative,
			byte[] leftBytes, byte[] rightBytes, byte[] landscapeBytes, double creativity,
			BaseRecord sdConfig) {

		if (MODE_FLUX2.equals(mode)) {
			Integer refSizeV = null;
			Boolean includeLandscapeV = null;
			if (sdConfig != null) {
				try { refSizeV = sdConfig.get("flux2ReferenceSize"); } catch (Exception e) { /* ignore */ }
				try { includeLandscapeV = sdConfig.get("flux2IncludeLandscapeRef"); } catch (Exception e) { /* ignore */ }
			}
			int refSize = (refSizeV != null && refSizeV > 0) ? refSizeV.intValue() : Flux2Defaults.referenceSize();
			/// Optional, ON by default. Every reference is encoded into FLUX.2's context, so the third
			/// one is real compute — MEASURED ~40s per reference at 1024px/4 steps on the local Strix
			/// Halo iGPU (2 refs ~80s, 3 refs ~120s), i.e. LINEAR in reference count at this scale. An
			/// earlier version of this comment asserted superlinear growth from theory; the measurement
			/// disproved it. Step count dominates: the same 3-reference request took 706s at 24 steps.
			/// Memory is NOT the constraint (96GB assigned to VRAM holds the 9B model easily) — it is
			/// iGPU compute. The setting still reaches the model as prompt text when this is off.
			/// Config override, else the editable resource. This ignored the resource entirely and
			/// hardcoded true as the fallback, so flux2Defaults.json's includeLandscapeRef was dead.
			boolean includeLandscape = (includeLandscapeV != null)
				? includeLandscapeV.booleanValue() : Flux2Defaults.includeLandscapeRef();
			byte[] settingRef = includeLandscape ? landscapeBytes : null;
			if (!includeLandscape && landscapeBytes != null) {
				logger.info("Scene composite [flux2]: landscape reference SUPPRESSED by "
					+ "flux2IncludeLandscapeRef=false - setting is carried by prompt text only");
			}
			List<String> refs = SDUtil.buildFlux2References(refSize, leftBytes, rightBytes, settingRef);
			SWTxt2Img s2i = SWUtil.newFlux2SceneTxt2Img(leftDesc, rightDesc, action, setting, mood,
					sdConfig, refs.size());
			if (!refs.isEmpty()) {
				s2i.setPromptImages(refs);
			}
			logger.info("Scene composite [flux2]: model=" + s2i.getModel() + " refs=" + refs.size()
				+ " cfg=" + s2i.getCfgScale() + " steps=" + s2i.getSteps()
				+ " " + s2i.getWidth() + "x" + s2i.getHeight());
			return s2i;
		}

		if (MODE_KONTEXT.equals(mode)) {
			// Kontext wants every panel filled; the original call sites substituted the landscape for
			// a missing portrait rather than shipping a two-panel strip.
			byte[] stitchLeft = (leftBytes != null) ? leftBytes : landscapeBytes;
			byte[] stitchCenter = (rightBytes != null) ? rightBytes : landscapeBytes;
			byte[] stitched = SDUtil.stitchSceneImages(stitchLeft, stitchCenter, landscapeBytes, KONTEXT_PANEL_SIZE);
			SWTxt2Img s2i = SWUtil.newKontextSceneTxt2Img(leftDesc, rightDesc, action, setting, sdConfig);
			if (stitched != null) {
				s2i.setPromptImages(List.of("data:image/png;base64," + Base64.getEncoder().encodeToString(stitched)));
			}
			logger.info("Scene composite [kontext]: model=" + s2i.getModel()
				+ " stitched=" + (stitched != null ? stitched.length + " bytes" : "none"));
			return s2i;
		}

		if (MODE_CLASSIC.equals(mode)) {
			SWTxt2Img s2i = SWUtil.newSceneTxt2Img(classicPrompt, classicNegative, sdConfig);
			byte[] composite = SDUtil.compositeSceneCanvas(landscapeBytes, leftBytes, rightBytes,
					s2i.getWidth(), s2i.getHeight());
			if (composite != null) {
				s2i.setInitImage("data:image/png;base64," + Base64.getEncoder().encodeToString(composite));
				s2i.setInitImageCreativity(creativity);
			}
			logger.info("Scene composite [classic]: composite="
				+ (composite != null ? composite.length + " bytes" : "none") + " creativity=" + creativity);
			return s2i;
		}

		logger.error("buildSceneRequest: unknown composite mode '" + mode + "'");
		return null;
	}

	/// Default img2img creativity per mode, preserving the values both original call sites used.
	/// Only CLASSIC actually consumes it; the reference-based modes don't run an init-image pass.
	public static double defaultCreativity(String mode) {
		return MODE_CLASSIC.equals(mode) ? 0.85 : 0.65;
	}
}
