package org.cote.accountmanager.scim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class ScimGroupAdapter {

	private static final Logger logger = LogManager.getLogger(ScimGroupAdapter.class);
	public static final String SCIM_GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

	public static Map<String, Object> toScim(BaseRecord group, BaseRecord contextUser, String baseUrl) {
		Map<String, Object> scim = new LinkedHashMap<>();
		scim.put("schemas", List.of(SCIM_GROUP_SCHEMA));

		String objectId = group.get(FieldNames.FIELD_OBJECT_ID);
		scim.put("id", objectId);

		String urn = group.get(FieldNames.FIELD_URN);
		if (urn != null) {
			scim.put("externalId", urn);
		}

		scim.put("displayName", group.get(FieldNames.FIELD_NAME));

		scim.put("members", mapMembers(group, contextUser, baseUrl));

		scim.put("meta", ScimUserAdapter.buildMeta(group, "Group", baseUrl));

		return scim;
	}

	private static List<Map<String, Object>> mapMembers(BaseRecord group, BaseRecord contextUser, String baseUrl) {
		List<Map<String, Object>> members = new ArrayList<>();
		try {
			List<BaseRecord> userMembers = IOSystem.getActiveContext().getAccessPoint()
				.listMembers(contextUser, group, ModelNames.MODEL_USER, null, 0L, 0);
			for (BaseRecord user : userMembers) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("value", user.get(FieldNames.FIELD_OBJECT_ID));
				entry.put("display", user.get(FieldNames.FIELD_NAME));
				entry.put("$ref", baseUrl + "/v2/Users/" + user.get(FieldNames.FIELD_OBJECT_ID));
				entry.put("type", "User");
				members.add(entry);
			}

			List<BaseRecord> groupMembers = IOSystem.getActiveContext().getAccessPoint()
				.listMembers(contextUser, group, ModelNames.MODEL_GROUP, null, 0L, 0);
			for (BaseRecord subGroup : groupMembers) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("value", subGroup.get(FieldNames.FIELD_OBJECT_ID));
				entry.put("display", subGroup.get(FieldNames.FIELD_NAME));
				entry.put("$ref", baseUrl + "/v2/Groups/" + subGroup.get(FieldNames.FIELD_OBJECT_ID));
				entry.put("type", "Group");
				members.add(entry);
			}
		} catch (Exception e) {
			logger.warn("Error mapping group members: " + e.getMessage());
		}
		return members;
	}
}
