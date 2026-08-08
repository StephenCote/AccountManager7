package org.cote.accountmanager.olio.sd;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// Externalized FLUX.2 generation defaults, loaded from olio/sd/flux2Defaults.json.
///
/// These were `static final` constants in SWUtil, which meant tuning steps/cfg/size required a code
/// change, and a UI exposing FLUX.2 settings would have had no source for its defaults except
/// hardcoded Java. Now the resource is the single source of truth and the Java constants are gone.
///
/// Precedence for any one value:
///   1. the per-book olio.sd.config override (flux2Cfg, flux2Steps, flux2Width, ...)
///   2. this resource file
///   3. the hardcoded emergency fallback below, used only if the resource is missing or malformed
///
/// The MODEL is deliberately absent. Checkpoint names are per-deployment - the local Swarm and the
/// DGX Spark carry disjoint checkpoint lists - so the model stays in configuration
/// (olio.sd.config.flux2Model, then sd.default.model, then the schema default) exactly as before.
public class Flux2Defaults {

	private static final Logger logger = LogManager.getLogger(Flux2Defaults.class);
	private static final String RESOURCE = "olio/sd/flux2Defaults.json";

	/// Emergency fallbacks. Reached only when the resource cannot be read - a broken deployment, not
	/// a tuning path. Kept identical to the values the resource ships with so behavior is unchanged.
	private static final double FALLBACK_CFG = 2.5;
	private static final int FALLBACK_STEPS = 24;
	private static final int FALLBACK_WIDTH = 1024;
	private static final int FALLBACK_HEIGHT = 768;
	private static final int FALLBACK_REF_SIZE = 1024;
	private static final String FALLBACK_SAMPLER = "euler";
	private static final String FALLBACK_SCHEDULER = "simple";
	private static final String FALLBACK_NEGATIVE =
		"blurry faces, deformed, extra people, mismatched lighting, low quality";

	private static volatile Map<String, Object> cache = null;
	private static volatile boolean loadFailed = false;

	private Flux2Defaults() { }

	/// Reload on the next access. For tests and for anyone editing the resource in a running process.
	public static void clearCache() {
		cache = null;
		loadFailed = false;
		ResourceUtil.clearCache();
	}

	private static Map<String, Object> load() {
		Map<String, Object> c = cache;
		if(c != null) return c;
		if(loadFailed) return null;
		String json = ResourceUtil.getInstance().getResource(RESOURCE);
		if(json == null) {
			logger.error("FLUX.2 defaults resource " + RESOURCE + " not found - falling back to built-in "
				+ "values. Tuning via that file will have NO effect until it is restored.");
			loadFailed = true;
			return null;
		}
		Map<String, Object> parsed = null;
		try {
			parsed = JSONUtil.getMap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), String.class, Object.class);
		} catch (Exception e) {
			logger.error("FLUX.2 defaults resource " + RESOURCE + " failed to parse: " + e.getMessage());
		}
		if(parsed == null) {
			logger.error("FLUX.2 defaults resource " + RESOURCE + " is missing or malformed - falling back "
				+ "to built-in values. Fix the file to restore tuning.");
			loadFailed = true;
			return null;
		}
		cache = parsed;
		return parsed;
	}

	private static double getDouble(String key, double fallback) {
		Map<String, Object> c = load();
		if(c == null) return fallback;
		Object v = c.get(key);
		if(v instanceof Number) return ((Number) v).doubleValue();
		return fallback;
	}

	private static int getInt(String key, int fallback) {
		Map<String, Object> c = load();
		if(c == null) return fallback;
		Object v = c.get(key);
		if(v instanceof Number) return ((Number) v).intValue();
		return fallback;
	}

	private static String getString(String key, String fallback) {
		Map<String, Object> c = load();
		if(c == null) return fallback;
		Object v = c.get(key);
		if(v instanceof String && !((String) v).isBlank()) return (String) v;
		return fallback;
	}

	private static boolean getBoolean(String key, boolean fallback) {
		Map<String, Object> c = load();
		if(c == null) return fallback;
		Object v = c.get(key);
		if(v instanceof Boolean) return ((Boolean) v).booleanValue();
		return fallback;
	}

	public static double cfgScale()          { return getDouble("cfgScale", FALLBACK_CFG); }
	public static int steps()                { return getInt("steps", FALLBACK_STEPS); }
	public static int width()                { return getInt("width", FALLBACK_WIDTH); }
	public static int height()               { return getInt("height", FALLBACK_HEIGHT); }
	public static int referenceSize()        { return getInt("referenceSize", FALLBACK_REF_SIZE); }
	public static String sampler()           { return getString("sampler", FALLBACK_SAMPLER); }
	public static String scheduler()         { return getString("scheduler", FALLBACK_SCHEDULER); }
	public static String negativePrompt()    { return getString("negativePrompt", FALLBACK_NEGATIVE); }
	public static boolean includeLandscapeRef() { return getBoolean("includeLandscapeRef", true); }

	/// Inert in multi-reference mode (no init image is attached). See the resource's own note.
	public static double initImageCreativity() { return getDouble("initImageCreativity", 0.6); }
}
