package org.cote.accountmanager.olio.illustration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// Renderer capabilities per model family (PictureBook V3 design §15), loaded from
/// olio/illustration/capabilities.json on the Flux2Defaults pattern. Pure: no DB, no SD, nothing
/// from olio.picturebook.
///
/// forModel(name) tries the families in resource order and returns the first whose glob matches,
/// or null when none does - a null is a real answer ("unknown family") that the caller must degrade
/// on (§19), never a reason to guess. Globs are case-insensitive and are tried against the full
/// checkpoint name and then its file basename (after the last '/').
public final class RendererCapabilities {

	private static final Logger logger = LogManager.getLogger(RendererCapabilities.class);
	private static final String RESOURCE = "olio/illustration/capabilities.json";

	public static final String PATTERN_FLUX2 = "flux2*";
	public static final String PATTERN_SDXL = "*xl*";
	public static final String PATTERN_KONTEXT = "*kontext*";

	private static final List<RendererCapabilities> FALLBACK;
	static {
		List<RendererCapabilities> l = new ArrayList<>();
		l.add(new RendererCapabilities(PATTERN_FLUX2, true, 3, true, true, true, false, false, true, true, true,
			4, 4, 24, 1.0, 3.5, Collections.unmodifiableList(List.of(new int[] {1024, 768}, new int[] {768, 1024}, new int[] {1024, 1024})), null));
		l.add(new RendererCapabilities(PATTERN_SDXL, true, 0, false, false, true, true, false, true, true, true,
			12, 20, 30, 5.0, 7.0, Collections.unmodifiableList(List.of(new int[] {1024, 1024}, new int[] {1024, 768}, new int[] {768, 1024})), null));
		l.add(new RendererCapabilities(PATTERN_KONTEXT, false, 1, true, false, true, false, false, true, true, false,
			20, 28, 28, 1.0, 1.0, Collections.unmodifiableList(List.of(new int[] {1024, 1024})), null));
		FALLBACK = Collections.unmodifiableList(l);
	}

	private static volatile List<RendererCapabilities> cache = null;
	private static volatile boolean loadFailed = false;

	private final String pattern;
	private final Pattern regex;
	private final boolean enabled;
	private final int maxReferences;
	private final boolean supportsReferences;
	private final boolean supportsStyleReferences;
	private final boolean supportsInitImage;
	private final boolean supportsMasks;
	private final boolean supportsRegionalPrompt;
	private final boolean supportsSeedReplay;
	private final boolean supportsCancel;
	private final boolean supportsBatch;
	private final int recommendedStepsLow;
	private final int recommendedStepsDefault;
	private final int recommendedStepsHigh;
	private final double cfgMin;
	private final double cfgMax;
	private final List<int[]> nativeResolutions;
	private final Double secondsPerStepPerReference;

	private RendererCapabilities(String pattern, boolean enabled, int maxReferences, boolean supportsReferences,
			boolean supportsStyleReferences, boolean supportsInitImage, boolean supportsMasks, boolean supportsRegionalPrompt,
			boolean supportsSeedReplay, boolean supportsCancel, boolean supportsBatch, int stepsLow, int stepsDefault,
			int stepsHigh, double cfgMin, double cfgMax, List<int[]> nativeResolutions, Double secondsPerStepPerReference) {
		this.pattern = pattern;
		this.regex = globToRegex(pattern);
		this.enabled = enabled;
		this.maxReferences = maxReferences;
		this.supportsReferences = supportsReferences;
		this.supportsStyleReferences = supportsStyleReferences;
		this.supportsInitImage = supportsInitImage;
		this.supportsMasks = supportsMasks;
		this.supportsRegionalPrompt = supportsRegionalPrompt;
		this.supportsSeedReplay = supportsSeedReplay;
		this.supportsCancel = supportsCancel;
		this.supportsBatch = supportsBatch;
		this.recommendedStepsLow = stepsLow;
		this.recommendedStepsDefault = stepsDefault;
		this.recommendedStepsHigh = stepsHigh;
		this.cfgMin = cfgMin;
		this.cfgMax = cfgMax;
		this.nativeResolutions = nativeResolutions;
		this.secondsPerStepPerReference = secondsPerStepPerReference;
	}

	public String getPattern() { return pattern; }
	public boolean isEnabled() { return enabled; }
	public int getMaxReferences() { return maxReferences; }
	public boolean isSupportsReferences() { return supportsReferences; }
	public boolean isSupportsStyleReferences() { return supportsStyleReferences; }
	public boolean isSupportsInitImage() { return supportsInitImage; }
	public boolean isSupportsMasks() { return supportsMasks; }
	public boolean isSupportsRegionalPrompt() { return supportsRegionalPrompt; }
	public boolean isSupportsSeedReplay() { return supportsSeedReplay; }
	public boolean isSupportsCancel() { return supportsCancel; }
	public boolean isSupportsBatch() { return supportsBatch; }
	public int getRecommendedStepsLow() { return recommendedStepsLow; }
	public int getRecommendedStepsDefault() { return recommendedStepsDefault; }
	public int getRecommendedStepsHigh() { return recommendedStepsHigh; }
	public double getCfgMin() { return cfgMin; }
	public double getCfgMax() { return cfgMax; }
	/// Each entry is {width, height}; the list is unmodifiable.
	public List<int[]> getNativeResolutions() { return nativeResolutions; }
	/// null until measured on the target server (§15/§18) - an estimate with no figure must say so.
	public Double getSecondsPerStepPerReference() { return secondsPerStepPerReference; }

	/// The step count for a preset's stepsMode ("low" | "default" | "high"); anything else is default.
	public int recommendedSteps(String stepsMode) {
		if(QualityPresets.STEPS_LOW.equalsIgnoreCase(stepsMode)) return recommendedStepsLow;
		if(QualityPresets.STEPS_HIGH.equalsIgnoreCase(stepsMode)) return recommendedStepsHigh;
		return recommendedStepsDefault;
	}

	public boolean matches(String checkpointName) {
		if(checkpointName == null || checkpointName.isBlank()) {
			return false;
		}
		String name = checkpointName.trim();
		if(regex.matcher(name).matches()) {
			return true;
		}
		int slash = name.lastIndexOf('/');
		if(slash >= 0 && slash < name.length() - 1) {
			return regex.matcher(name.substring(slash + 1)).matches();
		}
		return false;
	}

	@Override
	public String toString() {
		return "RendererCapabilities{" + pattern + ", enabled=" + enabled + ", maxReferences=" + maxReferences
			+ ", steps=" + recommendedStepsLow + "/" + recommendedStepsDefault + "/" + recommendedStepsHigh
			+ ", cfg=[" + cfgMin + "," + cfgMax + "], secondsPerStepPerReference=" + secondsPerStepPerReference + "}";
	}

	// ─────────────────────────────── static access ───────────────────────────────

	/// The first family whose glob matches, or null when none does.
	public static RendererCapabilities forModel(String checkpointName) {
		for(RendererCapabilities rc : families()) {
			if(rc.matches(checkpointName)) {
				return rc;
			}
		}
		return null;
	}

	/// Every declared family in resource order (unmodifiable).
	public static List<RendererCapabilities> families() {
		List<RendererCapabilities> c = load();
		return (c != null ? c : FALLBACK);
	}

	/// Reload on the next access. Test-only: ResourceUtil.clearCache() wipes the WHOLE resource cache.
	public static void clearCache() {
		cache = null;
		loadFailed = false;
		ResourceUtil.clearCache();
	}

	static Pattern globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for(char ch : glob.toCharArray()) {
			switch(ch) {
				case '*': sb.append(".*"); break;
				case '?': sb.append('.'); break;
				default: sb.append(Pattern.quote(String.valueOf(ch)));
			}
		}
		return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
	}

	@SuppressWarnings("unchecked")
	private static List<RendererCapabilities> load() {
		List<RendererCapabilities> c = cache;
		if(c != null) return c;
		if(loadFailed) return null;
		String json = ResourceUtil.getInstance().getResource(RESOURCE);
		if(json == null) {
			logger.error("Capabilities resource " + RESOURCE + " not found - falling back to built-in values. "
				+ "Tuning via that file will have NO effect until it is restored.");
			loadFailed = true;
			return null;
		}
		Map<String, Object> parsed = null;
		try {
			parsed = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		} catch (Exception e) {
			logger.error("Capabilities resource " + RESOURCE + " failed to parse: " + e.getMessage());
		}
		Object fams = (parsed != null ? parsed.get("families") : null);
		if(!(fams instanceof List) || ((List<Object>) fams).isEmpty()) {
			logger.error("Capabilities resource " + RESOURCE + " is missing or malformed (no families) - falling back to built-in values.");
			loadFailed = true;
			return null;
		}
		List<RendererCapabilities> out = new ArrayList<>();
		for(Object o : (List<Object>) fams) {
			if(!(o instanceof Map)) continue;
			RendererCapabilities rc = fromMap((Map<String, Object>) o);
			if(rc != null) out.add(rc);
		}
		if(out.isEmpty()) {
			logger.error("Capabilities resource " + RESOURCE + " declares no usable family - falling back to built-in values.");
			loadFailed = true;
			return null;
		}
		List<RendererCapabilities> ro = Collections.unmodifiableList(out);
		cache = ro;
		return ro;
	}

	@SuppressWarnings("unchecked")
	private static RendererCapabilities fromMap(Map<String, Object> m) {
		Object pat = m.get("pattern");
		if(!(pat instanceof String) || ((String) pat).isBlank()) {
			logger.error("Capabilities family without a pattern skipped: " + m);
			return null;
		}
		Map<String, Object> steps = (m.get("recommendedSteps") instanceof Map) ? (Map<String, Object>) m.get("recommendedSteps") : Collections.emptyMap();
		List<Object> cfg = (m.get("cfgRange") instanceof List) ? (List<Object>) m.get("cfgRange") : Collections.emptyList();
		List<int[]> res = new ArrayList<>();
		if(m.get("nativeResolutions") instanceof List) {
			for(Object r : (List<Object>) m.get("nativeResolutions")) {
				if(r instanceof List && ((List<Object>) r).size() == 2) {
					List<Object> wh = (List<Object>) r;
					if(wh.get(0) instanceof Number && wh.get(1) instanceof Number) {
						res.add(new int[] {((Number) wh.get(0)).intValue(), ((Number) wh.get(1)).intValue()});
					}
				}
			}
		}
		Object spr = m.get("secondsPerStepPerReference");
		Double seconds = (spr instanceof Number) ? Double.valueOf(((Number) spr).doubleValue()) : null;
		int stepsDefault = num(steps.get("default"), 20);
		return new RendererCapabilities(
			(String) pat,
			bool(m.get("enabled"), true),
			num(m.get("maxReferences"), 0),
			bool(m.get("supportsReferences"), false),
			bool(m.get("supportsStyleReferences"), false),
			bool(m.get("supportsInitImage"), false),
			bool(m.get("supportsMasks"), false),
			bool(m.get("supportsRegionalPrompt"), false),
			bool(m.get("supportsSeedReplay"), false),
			bool(m.get("supportsCancel"), false),
			bool(m.get("supportsBatch"), false),
			num(steps.get("low"), stepsDefault),
			stepsDefault,
			num(steps.get("high"), stepsDefault),
			(cfg.size() == 2 && cfg.get(0) instanceof Number) ? ((Number) cfg.get(0)).doubleValue() : 1.0,
			(cfg.size() == 2 && cfg.get(1) instanceof Number) ? ((Number) cfg.get(1)).doubleValue() : 1.0,
			Collections.unmodifiableList(res),
			seconds
		);
	}

	private static int num(Object v, int fallback) {
		return (v instanceof Number) ? ((Number) v).intValue() : fallback;
	}

	private static boolean bool(Object v, boolean fallback) {
		return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : fallback;
	}
}
