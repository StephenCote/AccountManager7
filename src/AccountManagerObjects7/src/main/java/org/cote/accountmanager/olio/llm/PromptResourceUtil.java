package org.cote.accountmanager.olio.llm;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// Centralized loader for externalized prompt templates.
/// Loads JSON prompt resources from olio/llm/prompts/ via ResourceUtil (classpath with caching).
/// All prompt text that was previously hard-coded in Java belongs in these resource files.
public class PromptResourceUtil {

	public static final Logger logger = LogManager.getLogger(PromptResourceUtil.class);
	private static final String PREFIX = "olio/llm/prompts/";

	/// Load a prompt resource and return as a parsed JSON map.
	public static Map<String, Object> load(String name) {
		String json = ResourceUtil.getInstance().getResource(PREFIX + name + ".json");
		if (json == null) {
			logger.warn("Prompt resource not found: " + name);
			return null;
		}
		try {
			return JSONUtil.getMap(json.getBytes(), String.class, Object.class);
		} catch (Exception e) {
			logger.error("Failed to parse prompt resource " + name + ": " + e.getMessage());
			return null;
		}
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

	/// Phase 2 (MemoryRefactor2): Load a prompt resource as a BaseRecord.
	/// Uses RecordFactory.importRecord to deserialize the JSON into a typed record
	/// (e.g., olio.llm.promptTemplate). Returns null if the resource is not found or parse fails.
	public static BaseRecord loadAsRecord(String name) {
		String json = ResourceUtil.getInstance().getResource(PREFIX + name + ".json");
		if (json == null) {
			logger.warn("Prompt resource not found: " + name);
			return null;
		}
		try {
			return RecordFactory.importRecord(json);
		} catch (Exception e) {
			logger.error("Failed to import prompt resource as record " + name + ": " + e.getMessage());
			return null;
		}
	}

	/// Simple token replacement for prompt templates.
	/// Replaces ${key} with the provided value.
	public static String replaceToken(String template, String key, String value) {
		if (template == null || key == null) return template;
		return template.replace("${" + key + "}", value != null ? value : "");
	}
}
