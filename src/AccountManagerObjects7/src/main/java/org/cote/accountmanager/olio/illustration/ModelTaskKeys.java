package org.cote.accountmanager.olio.illustration;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// The olio.pb.style.taskModels key vocabulary (PictureBook V3 design §16): a JSON object keyed by
/// the lower-camel spelling of PbModelTaskEnumType (portrait, location, sceneDirect, inpaint,
/// harmonize, upscale) whose values are checkpoint names. Pure: no DB, no SD.
///
/// Reads tolerate the enum spelling ("SCENE_DIRECT") case-insensitively; the canonical write form is
/// the lower-camel key and P2-3 canonicalizes on write.
public final class ModelTaskKeys {

	private static final Logger logger = LogManager.getLogger(ModelTaskKeys.class);

	private ModelTaskKeys() { }

	/// The canonical taskModels key for a task, or null for UNKNOWN / null.
	public static String key(PbModelTaskEnumType task) {
		if(task == null || task == PbModelTaskEnumType.UNKNOWN) {
			return null;
		}
		String[] parts = task.name().toLowerCase().split("_");
		StringBuilder sb = new StringBuilder(parts[0]);
		for(int i = 1; i < parts.length; i++) {
			if(parts[i].isEmpty()) {
				continue;
			}
			sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
		}
		return sb.toString();
	}

	/// The task a key names: canonical lower-camel first, then the enum name, both case-insensitive;
	/// anything else (including null, blank and "unknown") is UNKNOWN.
	public static PbModelTaskEnumType task(String key) {
		if(key == null || key.isBlank()) {
			return PbModelTaskEnumType.UNKNOWN;
		}
		String k = key.trim();
		for(PbModelTaskEnumType t : PbModelTaskEnumType.values()) {
			if(t == PbModelTaskEnumType.UNKNOWN) {
				continue;
			}
			if(k.equalsIgnoreCase(key(t))) {
				return t;
			}
		}
		for(PbModelTaskEnumType t : PbModelTaskEnumType.values()) {
			if(t == PbModelTaskEnumType.UNKNOWN) {
				continue;
			}
			if(k.equalsIgnoreCase(t.name())) {
				return t;
			}
		}
		return PbModelTaskEnumType.UNKNOWN;
	}

	/// Parse a style's taskModels JSON into task -> checkpoint. Unknown keys are logged and skipped;
	/// values that are not meaningful strings (null, blank, "null", "n/a", "none", "unknown",
	/// "unspecified" - NarrativeUtil.isMeaningful) are dropped; null, blank or malformed JSON is an
	/// empty map. Never throws; never returns null.
	public static Map<PbModelTaskEnumType, String> parseTaskModels(String json) {
		if(json == null || json.isBlank()) {
			return Collections.emptyMap();
		}
		Map<String, Object> raw = null;
		try {
			raw = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.warn("taskModels is not a JSON object and was ignored: " + e.getMessage());
		}
		if(raw == null || raw.isEmpty()) {
			if(raw == null) {
				logger.warn("taskModels is not a JSON object and was ignored");
			}
			return Collections.emptyMap();
		}
		Map<PbModelTaskEnumType, String> out = new EnumMap<>(PbModelTaskEnumType.class);
		for(Map.Entry<String, Object> e : raw.entrySet()) {
			PbModelTaskEnumType t = task(e.getKey());
			if(t == PbModelTaskEnumType.UNKNOWN) {
				logger.warn("taskModels key '" + e.getKey() + "' is not a model task and was ignored");
				continue;
			}
			Object v = e.getValue();
			if(!(v instanceof String) || !NarrativeUtil.isMeaningful((String) v)) {
				continue;
			}
			out.put(t, ((String) v).trim());
		}
		return (out.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(out));
	}
}
