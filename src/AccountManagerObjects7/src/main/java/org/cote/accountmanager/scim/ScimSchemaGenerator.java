package org.cote.accountmanager.scim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScimSchemaGenerator {

	public static final String SCIM_SCHEMA_URI = "urn:ietf:params:scim:schemas:core:2.0";

	public static Map<String, Object> generateServiceProviderConfig() {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));

		config.put("patch", Map.of("supported", true));
		config.put("bulk", Map.of(
			"supported", true,
			"maxOperations", 1000,
			"maxPayloadSize", 1048576
		));
		config.put("filter", Map.of(
			"supported", true,
			"maxResults", 200
		));
		config.put("changePassword", Map.of("supported", true));
		config.put("sort", Map.of("supported", true));
		config.put("etag", Map.of("supported", false));

		List<Map<String, Object>> authSchemes = new ArrayList<>();
		Map<String, Object> bearerScheme = new LinkedHashMap<>();
		bearerScheme.put("type", "oauthbearertoken");
		bearerScheme.put("name", "OAuth Bearer Token");
		bearerScheme.put("description", "Authentication via OAuth 2.0 Bearer Token");
		authSchemes.add(bearerScheme);
		config.put("authenticationSchemes", authSchemes);

		return config;
	}

	public static Map<String, Object> generateResourceTypes() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
		response.put("totalResults", 2);

		List<Map<String, Object>> resources = new ArrayList<>();

		Map<String, Object> userType = new LinkedHashMap<>();
		userType.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"));
		userType.put("id", "User");
		userType.put("name", "User");
		userType.put("endpoint", "/Users");
		userType.put("schema", ScimUserAdapter.SCIM_USER_SCHEMA);
		userType.put("schemaExtensions", List.of(
			Map.of("schema", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User", "required", false)
		));
		resources.add(userType);

		Map<String, Object> groupType = new LinkedHashMap<>();
		groupType.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"));
		groupType.put("id", "Group");
		groupType.put("name", "Group");
		groupType.put("endpoint", "/Groups");
		groupType.put("schema", ScimGroupAdapter.SCIM_GROUP_SCHEMA);
		resources.add(groupType);

		response.put("Resources", resources);
		return response;
	}

	public static Map<String, Object> generateSchemas() {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"));

		List<Map<String, Object>> resources = new ArrayList<>();
		resources.add(generateUserSchema());
		resources.add(generateGroupSchema());
		response.put("totalResults", resources.size());
		response.put("Resources", resources);
		return response;
	}

	public static Map<String, Object> generateUserSchema() {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("id", ScimUserAdapter.SCIM_USER_SCHEMA);
		schema.put("name", "User");
		schema.put("description", "User Account");

		List<Map<String, Object>> attributes = new ArrayList<>();
		attributes.add(schemaAttribute("userName", "string", true, "readWrite", "always", "server", "Login name"));
		attributes.add(schemaAttribute("active", "boolean", false, "readWrite", "default", "none", "Whether the user is active"));
		attributes.add(schemaAttribute("displayName", "string", false, "readOnly", "default", "none", "Display name computed from person first and last name"));
		attributes.add(schemaAttribute("externalId", "string", false, "readWrite", "default", "none", "External identifier mapped to AM7 URN"));

		Map<String, Object> nameAttr = schemaAttribute("name", "complex", false, "readWrite", "default", "none", "Name components");
		List<Map<String, Object>> nameSub = new ArrayList<>();
		nameSub.add(schemaAttribute("givenName", "string", false, "readWrite", "default", "none", "Given name / first name"));
		nameSub.add(schemaAttribute("middleName", "string", false, "readWrite", "default", "none", "Middle name"));
		nameSub.add(schemaAttribute("familyName", "string", false, "readWrite", "default", "none", "Family name / last name"));
		nameSub.add(schemaAttribute("formatted", "string", false, "readOnly", "default", "none", "Full formatted name"));
		nameSub.add(schemaAttribute("honorificPrefix", "string", false, "readWrite", "default", "none", "Title or prefix"));
		nameSub.add(schemaAttribute("honorificSuffix", "string", false, "readWrite", "default", "none", "Suffix"));
		nameAttr.put("subAttributes", nameSub);
		attributes.add(nameAttr);

		attributes.add(multiValuedAttribute("emails", "Email addresses", "value", "type", "primary"));
		attributes.add(multiValuedAttribute("phoneNumbers", "Phone numbers", "value", "type", "primary"));
		attributes.add(multiValuedAttribute("addresses", "Physical addresses", "streetAddress", "locality", "region", "postalCode", "country", "formatted", "type", "primary"));
		attributes.add(multiValuedAttribute("groups", "Group memberships", "value", "display", "$ref", "type"));
		attributes.add(multiValuedAttribute("roles", "Role assignments", "value", "display"));

		schema.put("attributes", attributes);
		return schema;
	}

	public static Map<String, Object> generateGroupSchema() {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("id", ScimGroupAdapter.SCIM_GROUP_SCHEMA);
		schema.put("name", "Group");
		schema.put("description", "Group resource");

		List<Map<String, Object>> attributes = new ArrayList<>();
		attributes.add(schemaAttribute("displayName", "string", true, "readWrite", "always", "none", "Group display name"));
		attributes.add(multiValuedAttribute("members", "Group members", "value", "display", "$ref", "type"));

		schema.put("attributes", attributes);
		return schema;
	}

	private static Map<String, Object> schemaAttribute(String name, String type, boolean required, String mutability, String returned, String uniqueness, String description) {
		Map<String, Object> attr = new LinkedHashMap<>();
		attr.put("name", name);
		attr.put("type", type);
		attr.put("multiValued", false);
		attr.put("required", required);
		attr.put("caseExact", false);
		attr.put("mutability", mutability);
		attr.put("returned", returned);
		attr.put("uniqueness", uniqueness);
		attr.put("description", description);
		return attr;
	}

	private static Map<String, Object> multiValuedAttribute(String name, String description, String... subAttrNames) {
		Map<String, Object> attr = new LinkedHashMap<>();
		attr.put("name", name);
		attr.put("type", "complex");
		attr.put("multiValued", true);
		attr.put("required", false);
		attr.put("mutability", "readWrite");
		attr.put("returned", "default");
		attr.put("description", description);

		List<Map<String, Object>> subAttrs = new ArrayList<>();
		for (String sub : subAttrNames) {
			Map<String, Object> subAttr = new LinkedHashMap<>();
			subAttr.put("name", sub);
			subAttr.put("type", "primary".equals(sub) ? "boolean" : "string");
			subAttr.put("multiValued", false);
			subAttr.put("required", false);
			subAttr.put("mutability", "$ref".equals(sub) ? "readOnly" : "readWrite");
			subAttr.put("returned", "default");
			subAttrs.add(subAttr);
		}
		attr.put("subAttributes", subAttrs);
		return attr;
	}
}
