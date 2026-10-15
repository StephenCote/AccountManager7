package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

/// An sd.config record as the RenderPlan.config snapshot (PictureBook V3 design §12): every declared,
/// column-backed value the record carries, in schema order, as JSON-native values so the plan
/// round-trips through RenderPlan.toJson/fromJson unchanged. The schema is the record's own, so this
/// knows no model name; List values are copied so the snapshot never aliases the record.
public final class ConfigSnapshot {

	/// Presentation-only sd.config fields: never part of a render decision, excluded from the
	/// snapshot and from any hash of the config.
	public static final List<String> HASH_EXCLUDE = Collections.unmodifiableList(Arrays.asList(
		"imagePath", "imageName", FieldNames.FIELD_DESCRIPTION, "shared"
	));

	/// Ownership fields the config inherits from data.directory; never part of a render decision.
	private static final Set<String> OWNERSHIP_EXCLUDE = new HashSet<>(Arrays.asList(
		FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID
	));

	private ConfigSnapshot() {
	}

	public static Map<String, Object> of(BaseRecord cfg) {
		Map<String, Object> out = new LinkedHashMap<>();
		if(cfg == null) {
			return out;
		}
		ModelSchema ms = RecordFactory.getSchema(cfg.getSchema());
		for(FieldSchema fs : ms.getFields()) {
			String n = fs.getName();
			if(HASH_EXCLUDE.contains(n) || OWNERSHIP_EXCLUDE.contains(n)) {
				continue;
			}
			if(fs.isVirtual() || fs.isEphemeral() || fs.isIdentity() || fs.isReferenced() || fs.isForeign()) {
				continue;
			}
			if(!cfg.hasField(n)) {
				continue;
			}
			Object v = cfg.get(n);
			if(v == null) {
				continue;
			}
			if(v instanceof List) {
				out.put(n, new ArrayList<>((List<?>) v));
			}
			else if(v instanceof String || v instanceof Number || v instanceof Boolean) {
				out.put(n, v);
			}
			else {
				out.put(n, v.toString());
			}
		}
		return out;
	}
}
