package org.cote.accountmanager.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class ScimPatchHandler {

	private static final Logger logger = LogManager.getLogger(ScimPatchHandler.class);
	public static final String PATCH_OP_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

	@SuppressWarnings("unchecked")
	public static List<String> applyPatch(BaseRecord userRecord, BaseRecord personRecord, List<Map<String, Object>> operations) {
		List<String> errors = new ArrayList<>();

		for (Map<String, Object> op : operations) {
			String operation = ((String) op.get("op")).toLowerCase();
			String path = (String) op.get("path");
			Object value = op.get("value");

			try {
				switch (operation) {
					case "add":
					case "replace":
						handleAddReplace(userRecord, personRecord, path, value, errors);
						break;
					case "remove":
						handleRemove(userRecord, personRecord, path, errors);
						break;
					default:
						errors.add("Unsupported operation: " + operation);
						break;
				}
			} catch (Exception e) {
				logger.error("Error applying patch operation: " + e.getMessage());
				errors.add("Failed to apply " + operation + " on " + path + ": " + e.getMessage());
			}
		}

		return errors;
	}

	@SuppressWarnings("unchecked")
	private static void handleAddReplace(BaseRecord userRecord, BaseRecord personRecord, String path, Object value, List<String> errors) throws FieldException, ValueException, ModelNotFoundException {
		if (path == null && value instanceof Map) {
			Map<String, Object> vals = (Map<String, Object>) value;
			for (Map.Entry<String, Object> entry : vals.entrySet()) {
				handleAddReplace(userRecord, personRecord, entry.getKey(), entry.getValue(), errors);
			}
			return;
		}

		if (path == null) {
			errors.add("Path is required for add/replace when value is not an object");
			return;
		}

		switch (path) {
			case "userName":
				if (value instanceof String) {
					userRecord.set(FieldNames.FIELD_NAME, value);
				}
				break;
			case "active":
				if (value instanceof Boolean) {
					userRecord.set(FieldNames.FIELD_STATUS, ScimUserAdapter.mapActiveToStatus((Boolean) value));
				}
				break;
			case "externalId":
				if (value instanceof String) {
					userRecord.set(FieldNames.FIELD_URN, value);
				}
				break;
			case "name.givenName":
				if (personRecord != null && value instanceof String) {
					personRecord.set(FieldNames.FIELD_FIRST_NAME, value);
				}
				break;
			case "name.middleName":
				if (personRecord != null && value instanceof String) {
					personRecord.set(FieldNames.FIELD_MIDDLE_NAME, value);
				}
				break;
			case "name.familyName":
				if (personRecord != null && value instanceof String) {
					personRecord.set(FieldNames.FIELD_LAST_NAME, value);
				}
				break;
			case "displayName":
				// displayName is computed from person first/last name
				break;
			case "name":
				if (personRecord != null && value instanceof Map) {
					Map<String, Object> nameMap = (Map<String, Object>) value;
					if (nameMap.containsKey("givenName")) {
						personRecord.set(FieldNames.FIELD_FIRST_NAME, nameMap.get("givenName"));
					}
					if (nameMap.containsKey("middleName")) {
						personRecord.set(FieldNames.FIELD_MIDDLE_NAME, nameMap.get("middleName"));
					}
					if (nameMap.containsKey("familyName")) {
						personRecord.set(FieldNames.FIELD_LAST_NAME, nameMap.get("familyName"));
					}
				}
				break;
			default:
				logger.debug("Unhandled patch path: " + path);
				break;
		}
	}

	private static void handleRemove(BaseRecord userRecord, BaseRecord personRecord, String path, List<String> errors) throws FieldException, ValueException, ModelNotFoundException {
		if (path == null) {
			errors.add("Path is required for remove operations");
			return;
		}

		if ("userName".equals(path)) {
			errors.add("Cannot remove required field: userName");
			return;
		}

		switch (path) {
			case "externalId":
				userRecord.set(FieldNames.FIELD_URN, null);
				break;
			case "name.givenName":
				if (personRecord != null) personRecord.set(FieldNames.FIELD_FIRST_NAME, null);
				break;
			case "name.middleName":
				if (personRecord != null) personRecord.set(FieldNames.FIELD_MIDDLE_NAME, null);
				break;
			case "name.familyName":
				if (personRecord != null) personRecord.set(FieldNames.FIELD_LAST_NAME, null);
				break;
			default:
				logger.debug("Unhandled remove path: " + path);
				break;
		}
	}
}
