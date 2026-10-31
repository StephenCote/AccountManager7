package org.cote.accountmanager.olio.illustration;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// Quality presets (PictureBook V3 design §18), loaded from olio/illustration/qualityPresets.json on
/// the Flux2Defaults pattern: the resource is the source of truth, the FALLBACK constants are the
/// shipped values and are reached only when the resource is missing or malformed.
///
/// Pure: no DB, no SD, nothing from olio.picturebook. PbQualityPresetEnumType is the key type of the
/// style's column; a null or UNKNOWN key resolves to the resource's defaultPreset.
public final class QualityPresets {

	private static final Logger logger = LogManager.getLogger(QualityPresets.class);
	private static final String RESOURCE = "olio/illustration/qualityPresets.json";

	public static final String QA_OFF = "OFF";
	public static final String QA_CANONICAL = "CANONICAL";
	public static final String QA_ALL = "ALL";

	public static final String STEPS_LOW = "low";
	public static final String STEPS_DEFAULT = "default";
	public static final String STEPS_HIGH = "high";

	private static final PbQualityPresetEnumType FALLBACK_DEFAULT = PbQualityPresetEnumType.DRAFT;
	/// The shipped qaModel (ledger §6.C 220 e); the resource's top-level "qaModel" wins when present.
	static final String FALLBACK_QA_MODEL = "valkyriesys/eudaimonia-dryad3-vision";
	private static final Map<PbQualityPresetEnumType, Preset> FALLBACK;
	static {
		Map<PbQualityPresetEnumType, Preset> m = new LinkedHashMap<>();
		m.put(PbQualityPresetEnumType.DRAFT, new Preset(PbQualityPresetEnumType.DRAFT, 0.5, STEPS_LOW, 1, false, QA_OFF));
		m.put(PbQualityPresetEnumType.STANDARD, new Preset(PbQualityPresetEnumType.STANDARD, 1.0, STEPS_DEFAULT, 2, false, QA_CANONICAL));
		m.put(PbQualityPresetEnumType.FINAL, new Preset(PbQualityPresetEnumType.FINAL, 1.0, STEPS_HIGH, 4, true, QA_ALL));
		FALLBACK = Collections.unmodifiableMap(m);
	}

	private static volatile Map<String, Object> cache = null;
	private static volatile boolean loadFailed = false;

	private QualityPresets() { }

	/// One resolved preset. Immutable.
	public static final class Preset {
		private final PbQualityPresetEnumType name;
		private final double resolutionScale;
		private final String stepsMode;
		private final int variants;
		private final boolean upscale;
		private final String qa;

		Preset(PbQualityPresetEnumType name, double resolutionScale, String stepsMode, int variants, boolean upscale, String qa) {
			this.name = name;
			this.resolutionScale = resolutionScale;
			this.stepsMode = stepsMode;
			this.variants = variants;
			this.upscale = upscale;
			this.qa = qa;
		}

		public PbQualityPresetEnumType getName() { return name; }
		public double getResolutionScale() { return resolutionScale; }
		/// "low" | "default" | "high" - resolved against RendererCapabilities.recommendedSteps by the caller.
		public String getStepsMode() { return stepsMode; }
		public int getVariants() { return variants; }
		public boolean isUpscale() { return upscale; }
		/// OFF | CANONICAL | ALL
		public String getQa() { return qa; }

		@Override
		public String toString() {
			return name + "{resolutionScale=" + resolutionScale + ", stepsMode=" + stepsMode + ", variants=" + variants
				+ ", upscale=" + upscale + ", qa=" + qa + "}";
		}
	}

	/// Reload on the next access. Test-only: ResourceUtil.clearCache() wipes the WHOLE resource cache,
	/// not just this file.
	public static void clearCache() {
		cache = null;
		loadFailed = false;
		ResourceUtil.clearCache();
	}

	/// The preset a null/UNKNOWN qualityPreset resolves to.
	public static PbQualityPresetEnumType defaultPreset() {
		Map<String, Object> c = load();
		if(c != null) {
			Object v = c.get("defaultPreset");
			if(v instanceof String) {
				PbQualityPresetEnumType p = parse((String) v);
				if(p != null) {
					return p;
				}
				logger.error("qualityPresets.json defaultPreset '" + v + "' is not a preset; using " + FALLBACK_DEFAULT);
			}
		}
		return FALLBACK_DEFAULT;
	}

	/// The vision model visual QA asks the run's chat route for (design §17; §6.C 220 e): the resource's
	/// top-level "qaModel", else the shipped value. Never the chat config's own (text) model. Never blank.
	public static String qaModel() {
		Map<String, Object> c = load();
		if(c != null) {
			Object v = c.get("qaModel");
			if(v instanceof String && !((String) v).isBlank()) {
				return ((String) v).trim();
			}
			if(v != null) {
				logger.error("qualityPresets.json qaModel '" + v + "' is not a model name; using " + FALLBACK_QA_MODEL);
			}
		}
		return FALLBACK_QA_MODEL;
	}

	/// Resolve a style's qualityPreset. null and UNKNOWN mean "unset" and resolve to defaultPreset().
	/// Never returns null: a preset the resource omits falls back to the shipped value for that name.
	public static Preset resolve(PbQualityPresetEnumType preset) {
		PbQualityPresetEnumType key = preset;
		if(key == null || key == PbQualityPresetEnumType.UNKNOWN) {
			key = defaultPreset();
		}
		Preset p = fromResource(key);
		if(p != null) {
			return p;
		}
		Preset fb = FALLBACK.get(key);
		if(fb == null) {
			/// Only reachable if the enum grows a value the fallback table does not know.
			fb = FALLBACK.get(FALLBACK_DEFAULT);
		}
		return fb;
	}

	private static PbQualityPresetEnumType parse(String name) {
		if(name == null) {
			return null;
		}
		for(PbQualityPresetEnumType p : PbQualityPresetEnumType.values()) {
			if(p != PbQualityPresetEnumType.UNKNOWN && p.name().equalsIgnoreCase(name.trim())) {
				return p;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Preset fromResource(PbQualityPresetEnumType key) {
		Map<String, Object> c = load();
		if(c == null) {
			return null;
		}
		Object presets = c.get("presets");
		if(!(presets instanceof Map)) {
			return null;
		}
		Object entry = ((Map<String, Object>) presets).get(key.name());
		if(!(entry instanceof Map)) {
			return null;
		}
		Map<String, Object> e = (Map<String, Object>) entry;
		Preset fb = FALLBACK.get(key);
		double scale = (e.get("resolutionScale") instanceof Number) ? ((Number) e.get("resolutionScale")).doubleValue() : (fb != null ? fb.resolutionScale : 1.0);
		String stepsMode = (e.get("stepsMode") instanceof String && !((String) e.get("stepsMode")).isBlank()) ? (String) e.get("stepsMode") : (fb != null ? fb.stepsMode : STEPS_DEFAULT);
		int variants = (e.get("variants") instanceof Number) ? ((Number) e.get("variants")).intValue() : (fb != null ? fb.variants : 1);
		boolean upscale = (e.get("upscale") instanceof Boolean) ? ((Boolean) e.get("upscale")).booleanValue() : (fb != null && fb.upscale);
		String qa = (e.get("qa") instanceof String && !((String) e.get("qa")).isBlank()) ? ((String) e.get("qa")).toUpperCase() : (fb != null ? fb.qa : QA_OFF);
		return new Preset(key, scale, stepsMode, variants, upscale, qa);
	}

	private static Map<String, Object> load() {
		Map<String, Object> c = cache;
		if(c != null) return c;
		if(loadFailed) return null;
		String json = ResourceUtil.getInstance().getResource(RESOURCE);
		if(json == null) {
			logger.error("Quality presets resource " + RESOURCE + " not found - falling back to built-in values. "
				+ "Tuning via that file will have NO effect until it is restored.");
			loadFailed = true;
			return null;
		}
		Map<String, Object> parsed = null;
		try {
			parsed = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		} catch (Exception e) {
			logger.error("Quality presets resource " + RESOURCE + " failed to parse: " + e.getMessage());
		}
		if(parsed == null) {
			logger.error("Quality presets resource " + RESOURCE + " is missing or malformed - falling back to built-in values.");
			loadFailed = true;
			return null;
		}
		cache = parsed;
		return parsed;
	}
}
