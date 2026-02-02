package org.cote.accountmanager.objects.tests.scim;

import org.cote.accountmanager.objects.tests.BaseTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.cote.accountmanager.scim.ScimGroupAdapter;
import org.cote.accountmanager.scim.ScimSchemaGenerator;
import org.cote.accountmanager.scim.ScimUserAdapter;
import org.junit.Test;

public class TestScimSchemaDiscovery extends BaseTest {

	@Test
	public void testServiceProviderConfig() {
		logger.info("Testing SCIM Schema Discovery - ServiceProviderConfig");
		Map<String, Object> config = ScimSchemaGenerator.generateServiceProviderConfig();
		assertNotNull("Config should not be null", config);

		@SuppressWarnings("unchecked")
		List<String> schemas = (List<String>) config.get("schemas");
		assertNotNull("schemas should be present", schemas);
		assertTrue("Should contain ServiceProviderConfig schema",
			schemas.contains("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));

		@SuppressWarnings("unchecked")
		Map<String, Object> patch = (Map<String, Object>) config.get("patch");
		assertNotNull("patch should be present", patch);
		assertEquals("patch.supported should be true", true, patch.get("supported"));

		@SuppressWarnings("unchecked")
		Map<String, Object> bulk = (Map<String, Object>) config.get("bulk");
		assertNotNull("bulk should be present", bulk);
		assertEquals("bulk.supported should be true", true, bulk.get("supported"));
		assertEquals("bulk.maxOperations should be 1000", 1000, bulk.get("maxOperations"));

		@SuppressWarnings("unchecked")
		Map<String, Object> filter = (Map<String, Object>) config.get("filter");
		assertNotNull("filter should be present", filter);
		assertEquals("filter.supported should be true", true, filter.get("supported"));
		assertEquals("filter.maxResults should be 200", 200, filter.get("maxResults"));

		@SuppressWarnings("unchecked")
		Map<String, Object> sort = (Map<String, Object>) config.get("sort");
		assertNotNull("sort should be present", sort);
		assertEquals("sort.supported should be true", true, sort.get("supported"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> authSchemes = (List<Map<String, Object>>) config.get("authenticationSchemes");
		assertNotNull("authenticationSchemes should be present", authSchemes);
		assertTrue("Should have at least one auth scheme", authSchemes.size() > 0);
		assertEquals("Auth scheme type should be oauthbearertoken", "oauthbearertoken", authSchemes.get(0).get("type"));
	}

	@Test
	public void testResourceTypes() {
		logger.info("Testing SCIM Schema Discovery - ResourceTypes");
		Map<String, Object> response = ScimSchemaGenerator.generateResourceTypes();
		assertNotNull("Response should not be null", response);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> resources = (List<Map<String, Object>>) response.get("Resources");
		assertNotNull("Resources should be present", resources);
		assertEquals("Should have 2 resource types", 2, resources.size());

		Map<String, Object> userType = resources.get(0);
		assertEquals("First resource should be User", "User", userType.get("id"));
		assertEquals("User endpoint should be /Users", "/Users", userType.get("endpoint"));
		assertEquals("User schema should match", ScimUserAdapter.SCIM_USER_SCHEMA, userType.get("schema"));

		Map<String, Object> groupType = resources.get(1);
		assertEquals("Second resource should be Group", "Group", groupType.get("id"));
		assertEquals("Group endpoint should be /Groups", "/Groups", groupType.get("endpoint"));
		assertEquals("Group schema should match", ScimGroupAdapter.SCIM_GROUP_SCHEMA, groupType.get("schema"));
	}

	@Test
	public void testUserSchema() {
		logger.info("Testing SCIM Schema Discovery - User schema");
		Map<String, Object> schema = ScimSchemaGenerator.generateUserSchema();
		assertNotNull("Schema should not be null", schema);

		assertEquals("Schema id should match", ScimUserAdapter.SCIM_USER_SCHEMA, schema.get("id"));
		assertEquals("Schema name should be User", "User", schema.get("name"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> attributes = (List<Map<String, Object>>) schema.get("attributes");
		assertNotNull("attributes should be present", attributes);
		assertTrue("Should have attributes", attributes.size() > 0);

		boolean hasUserName = attributes.stream().anyMatch(a -> "userName".equals(a.get("name")));
		assertTrue("Should have userName attribute", hasUserName);

		boolean hasActive = attributes.stream().anyMatch(a -> "active".equals(a.get("name")));
		assertTrue("Should have active attribute", hasActive);

		boolean hasName = attributes.stream().anyMatch(a -> "name".equals(a.get("name")));
		assertTrue("Should have name attribute", hasName);

		boolean hasEmails = attributes.stream().anyMatch(a -> "emails".equals(a.get("name")));
		assertTrue("Should have emails attribute", hasEmails);

		boolean hasGroups = attributes.stream().anyMatch(a -> "groups".equals(a.get("name")));
		assertTrue("Should have groups attribute", hasGroups);

		Map<String, Object> userNameAttr = attributes.stream()
			.filter(a -> "userName".equals(a.get("name")))
			.findFirst().orElse(null);
		assertNotNull("userName attribute should exist", userNameAttr);
		assertEquals("userName should be required", true, userNameAttr.get("required"));
		assertEquals("userName type should be string", "string", userNameAttr.get("type"));

		Map<String, Object> nameAttr = attributes.stream()
			.filter(a -> "name".equals(a.get("name")))
			.findFirst().orElse(null);
		assertNotNull("name attribute should exist", nameAttr);
		assertEquals("name type should be complex", "complex", nameAttr.get("type"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nameSubs = (List<Map<String, Object>>) nameAttr.get("subAttributes");
		assertNotNull("name should have subAttributes", nameSubs);
		assertTrue("name should have sub-attributes", nameSubs.size() > 0);
	}

	@Test
	public void testGroupSchema() {
		logger.info("Testing SCIM Schema Discovery - Group schema");
		Map<String, Object> schema = ScimSchemaGenerator.generateGroupSchema();
		assertNotNull("Schema should not be null", schema);

		assertEquals("Schema id should match", ScimGroupAdapter.SCIM_GROUP_SCHEMA, schema.get("id"));
		assertEquals("Schema name should be Group", "Group", schema.get("name"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> attributes = (List<Map<String, Object>>) schema.get("attributes");
		assertNotNull("attributes should be present", attributes);

		boolean hasDisplayName = attributes.stream().anyMatch(a -> "displayName".equals(a.get("name")));
		assertTrue("Should have displayName attribute", hasDisplayName);

		boolean hasMembers = attributes.stream().anyMatch(a -> "members".equals(a.get("name")));
		assertTrue("Should have members attribute", hasMembers);
	}

	@Test
	public void testSchemasListEndpoint() {
		logger.info("Testing SCIM Schema Discovery - Schemas list");
		Map<String, Object> response = ScimSchemaGenerator.generateSchemas();
		assertNotNull("Response should not be null", response);

		@SuppressWarnings("unchecked")
		List<String> schemas = (List<String>) response.get("schemas");
		assertTrue("Should contain ListResponse schema",
			schemas.contains("urn:ietf:params:scim:api:messages:2.0:ListResponse"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> resources = (List<Map<String, Object>>) response.get("Resources");
		assertNotNull("Resources should be present", resources);
		assertEquals("Should have 2 schemas", 2, resources.size());

		assertEquals("totalResults should be 2", 2, response.get("totalResults"));
	}
}
