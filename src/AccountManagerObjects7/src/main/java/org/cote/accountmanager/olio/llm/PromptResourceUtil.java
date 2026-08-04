package org.cote.accountmanager.olio.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// Centralized loader for externalized prompt templates.
/// Loads JSON prompt resources from olio/llm/prompts/ via ResourceUtil (classpath with caching).
/// All prompt text that was previously hard-coded in Java belongs in these resource files.
public class PromptResourceUtil {

	public static final Logger logger = LogManager.getLogger(PromptResourceUtil.class);
	private static final String PREFIX = "olio/llm/prompts/";

	// Parsed-resource cache. Prompt resources are immutable at runtime (ResourceUtil already caches
	// the raw text), so each one is parsed at most once. Critically, FAILURES are cached too: a
	// malformed or missing resource logs a single error and every subsequent load() short-circuits,
	// instead of re-parsing the same bad JSON and re-throwing/re-logging the identical
	// JsonParseException once per caller in a loop (e.g. resolveScenePrompt runs per scene, so one
	// broken resource previously produced the same stack trace ~2x per scene across the whole book).
	private static final Map<String, Map<String, Object>> CACHE = new ConcurrentHashMap<>();
	// Distinct, identity-compared sentinel meaning "already attempted, unavailable/malformed" so a
	// negative result can live in the cache; callers still observe the same null they did before.
	private static final Map<String, Object> FAILED = new HashMap<>();

	/// Load a prompt resource and return as a parsed JSON map. Cached (including failures, as null).
	public static Map<String, Object> load(String name) {
		Map<String, Object> cached = CACHE.get(name);
		if (cached != null) return cached == FAILED ? null : cached;

		String json = ResourceUtil.getInstance().getResource(PREFIX + name + ".json");
		if (json == null) {
			logger.warn("Prompt resource not found (caching miss to avoid repeat lookups): " + name);
			CACHE.put(name, FAILED);
			return null;
		}
		Map<String, Object> parsed;
		try {
			parsed = JSONUtil.getMap(json.getBytes(), String.class, Object.class);
		} catch (Exception e) {
			logger.error("Failed to parse prompt resource " + name + ": " + e.getMessage());
			parsed = null;
		}
		if (parsed == null) {
			// JSONUtil.getMap logs + returns null on malformed JSON (it does not throw), so guard on
			// null here rather than only in the catch. Cache the failure so one bad resource can't
			// re-parse/re-log on every call.
			logger.error("Prompt resource '" + name + "' is missing or malformed JSON; caching the failure "
					+ "so it is not re-parsed and re-logged on every call — fix the resource file to restore it.");
			CACHE.put(name, FAILED);
			return null;
		}
		CACHE.put(name, parsed);
		return parsed;
	}

	/// Get a string field from a prompt resource.
	public static String getString(String name, String field) {
		Map<String, Object> res = load(name);
		if (res == null) return null;
		Object val = res.get(field);
		if (val instanceof String) return (String) val;
		return null;
	}

	/// Get a string list field, joined with system line separators.
	@SuppressWarnings("unchecked")
	public static String getLines(String name, String field) {
		Map<String, Object> res = load(name);
		if (res == null) return null;
		Object val = res.get(field);
		if (val instanceof List) {
			return String.join(System.lineSeparator(), (List<String>) val);
		}
		if (val instanceof String) return (String) val;
		return null;
	}

	/// Get a nested map entry's string field.
	/// E.g., getEntry("ageGuidance", "child_0_5", "text") returns the text field of the child_0_5 object.
	@SuppressWarnings("unchecked")
	public static String getEntry(String name, String key, String field) {
		Map<String, Object> res = load(name);
		if (res == null) return null;
		Object entry = res.get(key);
		if (entry instanceof Map) {
			Object val = ((Map<String, Object>) entry).get(field);
			if (val instanceof String) return (String) val;
		}
		return null;
	}

	/// Get a flat string value from a nested map.
	/// E.g., getMapValue("compliance", "checks", "CHARACTER_IDENTITY") returns the check description.
	@SuppressWarnings("unchecked")
	public static String getMapValue(String name, String mapField, String key) {
		Map<String, Object> res = load(name);
		if (res == null) return null;
		Object mapObj = res.get(mapField);
		if (mapObj instanceof Map) {
			Object val = ((Map<String, Object>) mapObj).get(key);
			if (val instanceof String) return (String) val;
		}
		return null;
	}

	/// Get the resource path prefix for prompt files.
	public static String getPrefix() {
		return PREFIX;
	}

	/// Simple token replacement for prompt templates.
	/// Replaces ${key} with the provided value.
	public static String replaceToken(String template, String key, String value) {
		if (template == null || key == null) return template;
		return template.replace("${" + key + "}", value != null ? value : "");
	}
}
