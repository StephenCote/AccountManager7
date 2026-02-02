package org.cote.accountmanager.objects.tests.scim;

import org.cote.accountmanager.objects.tests.BaseTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.scim.ScimGroupAdapter;
import org.junit.Test;

public class TestScimGroupAdapter extends BaseTest {

	private static final String BASE_URL = "http://localhost:8080/am7/scim";

	@Test
	public void testToScimBasicGroup() {
		logger.info("Testing SCIM Group Adapter - basic group");
		BaseRecord adminUser = orgContext.getAdminUser();

		try {
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().make(
				adminUser, ModelNames.MODEL_GROUP, "~/ScimTestGroup1", GroupEnumType.DATA.toString());
			assertNotNull("Group should be created", created);

			Map<String, Object> scim = ScimGroupAdapter.toScim(created, adminUser, BASE_URL);
			assertNotNull("SCIM output should not be null", scim);
			assertEquals("id should map to objectId", created.get(FieldNames.FIELD_OBJECT_ID), scim.get("id"));
			assertEquals("displayName should map to name", "ScimTestGroup1", scim.get("displayName"));

			@SuppressWarnings("unchecked")
			List<String> schemas = (List<String>) scim.get("schemas");
			assertTrue("Should contain Group schema", schemas.contains(ScimGroupAdapter.SCIM_GROUP_SCHEMA));

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}

	@Test
	public void testToScimEmptyGroup() {
		logger.info("Testing SCIM Group Adapter - empty group");
		BaseRecord adminUser = orgContext.getAdminUser();

		try {
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().make(
				adminUser, ModelNames.MODEL_GROUP, "~/ScimEmptyGroup1", GroupEnumType.DATA.toString());
			assertNotNull("Group should be created", created);

			Map<String, Object> scim = ScimGroupAdapter.toScim(created, adminUser, BASE_URL);
			assertNotNull("SCIM output should not be null", scim);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> members = (List<Map<String, Object>>) scim.get("members");
			assertNotNull("members should not be null", members);
			assertEquals("members should be empty list", 0, members.size());

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}

	@Test
	public void testToScimGroupWithMembers() {
		logger.info("Testing SCIM Group Adapter - group with members");
		BaseRecord adminUser = orgContext.getAdminUser();

		try {
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().make(
				adminUser, ModelNames.MODEL_GROUP, "~/ScimMemberGroup1", GroupEnumType.DATA.toString());
			assertNotNull("Group should be created", created);

			BaseRecord testUser = getCreateUser("scimGroupUser1");
			assertNotNull("Test user should not be null", testUser);

			// member() returns false if the user is already a member from a prior run
			IOSystem.getActiveContext().getAccessPoint().member(adminUser, created, testUser, null, true);

			Map<String, Object> scim = ScimGroupAdapter.toScim(created, adminUser, BASE_URL);
			assertNotNull("SCIM output should not be null", scim);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> members = (List<Map<String, Object>>) scim.get("members");
			assertNotNull("members should not be null", members);

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}

	@Test
	public void testToScimGroupMeta() {
		logger.info("Testing SCIM Group Adapter - meta");
		BaseRecord adminUser = orgContext.getAdminUser();

		try {
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().make(
				adminUser, ModelNames.MODEL_GROUP, "~/ScimMetaGroup1", GroupEnumType.DATA.toString());
			assertNotNull("Group should be created", created);

			Map<String, Object> scim = ScimGroupAdapter.toScim(created, adminUser, BASE_URL);

			@SuppressWarnings("unchecked")
			Map<String, Object> meta = (Map<String, Object>) scim.get("meta");
			assertNotNull("meta should be present", meta);
			assertEquals("resourceType should be Group", "Group", meta.get("resourceType"));
			assertNotNull("location should be present", meta.get("location"));
			String location = (String) meta.get("location");
			assertTrue("location should contain /v2/Groups/", location.contains("/v2/Groups/"));

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}
}
