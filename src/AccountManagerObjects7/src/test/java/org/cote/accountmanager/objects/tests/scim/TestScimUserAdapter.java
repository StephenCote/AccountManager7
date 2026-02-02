package org.cote.accountmanager.objects.tests.scim;

import org.cote.accountmanager.objects.tests.BaseTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.UserStatusEnumType;
import org.cote.accountmanager.scim.ScimUserAdapter;
import org.junit.Test;

public class TestScimUserAdapter extends BaseTest {

	private static final String BASE_URL = "http://localhost:8080/am7/scim";

	@Test
	public void testToScimBasicFields() {
		logger.info("Testing SCIM User Adapter - basic fields");
		BaseRecord adminUser = orgContext.getAdminUser();
		BaseRecord testUser = getCreateUser("scimTestUser1");
		assertNotNull("Test user should not be null", testUser);

		Map<String, Object> scim = ScimUserAdapter.toScim(testUser, null, BASE_URL);
		assertNotNull("SCIM output should not be null", scim);
		assertEquals("id should map to objectId", testUser.get(FieldNames.FIELD_OBJECT_ID), scim.get("id"));
		assertEquals("userName should map to name", testUser.get(FieldNames.FIELD_NAME), scim.get("userName"));
		assertNotNull("schemas should be present", scim.get("schemas"));

		@SuppressWarnings("unchecked")
		List<String> schemas = (List<String>) scim.get("schemas");
		assertTrue("Should contain User schema", schemas.contains(ScimUserAdapter.SCIM_USER_SCHEMA));
	}

	@Test
	public void testToScimNullPerson() {
		logger.info("Testing SCIM User Adapter - null person");
		BaseRecord testUser = getCreateUser("scimTestUser2");
		assertNotNull("Test user should not be null", testUser);

		Map<String, Object> scim = ScimUserAdapter.toScim(testUser, null, BASE_URL);
		assertNotNull("SCIM output should not be null even without person", scim);
		assertNotNull("userName should still be present", scim.get("userName"));
		assertNotNull("id should still be present", scim.get("id"));
	}

	@Test
	public void testToScimWithPerson() {
		logger.info("Testing SCIM User Adapter - with person");
		BaseRecord adminUser = orgContext.getAdminUser();
		BaseRecord testUser = getCreateUser("scimTestUser3");
		assertNotNull("Test user should not be null", testUser);

		try {
			BaseRecord person = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_PERSON, adminUser, null, null
			);
			person.set(FieldNames.FIELD_NAME, "Test Person 3");
			person.set(FieldNames.FIELD_FIRST_NAME, "John");
			person.set(FieldNames.FIELD_MIDDLE_NAME, "Q");
			person.set(FieldNames.FIELD_LAST_NAME, "Smith");

			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(adminUser, person);
			assertNotNull("Person should be created", created);

			IOSystem.getActiveContext().getAccessPoint().member(adminUser, created, "users", testUser, null, true);

			Query q = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_OBJECT_ID, (String) created.get(FieldNames.FIELD_OBJECT_ID));
			q.planMost(true);
			BaseRecord fullPerson = IOSystem.getActiveContext().getAccessPoint().find(adminUser, q);

			Map<String, Object> scim = ScimUserAdapter.toScim(testUser, fullPerson, BASE_URL);
			assertNotNull("SCIM output should not be null", scim);
			assertNotNull("name should be present", scim.get("name"));

			@SuppressWarnings("unchecked")
			Map<String, Object> name = (Map<String, Object>) scim.get("name");
			assertEquals("givenName should be John", "John", name.get("givenName"));
			assertEquals("middleName should be Q", "Q", name.get("middleName"));
			assertEquals("familyName should be Smith", "Smith", name.get("familyName"));
			assertNotNull("formatted name should be present", name.get("formatted"));
			assertNotNull("displayName should be present", scim.get("displayName"));

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}

	@Test
	public void testToScimMeta() {
		logger.info("Testing SCIM User Adapter - meta");
		BaseRecord testUser = getCreateUser("scimTestUser4");
		assertNotNull("Test user should not be null", testUser);

		Map<String, Object> scim = ScimUserAdapter.toScim(testUser, null, BASE_URL);
		assertNotNull("meta should be present", scim.get("meta"));

		@SuppressWarnings("unchecked")
		Map<String, Object> meta = (Map<String, Object>) scim.get("meta");
		assertEquals("resourceType should be User", "User", meta.get("resourceType"));
		assertNotNull("location should be present", meta.get("location"));
		String location = (String) meta.get("location");
		assertTrue("location should contain /v2/Users/", location.contains("/v2/Users/"));
	}

	@Test
	public void testStatusMapping() {
		logger.info("Testing SCIM User Adapter - status mapping");
		assertTrue("NORMAL should map to active=true", ScimUserAdapter.mapStatusToActive(UserStatusEnumType.NORMAL.toString()));
		assertTrue("REGISTERED should map to active=true", ScimUserAdapter.mapStatusToActive(UserStatusEnumType.REGISTERED.toString()));
		assertFalse("DISABLED should map to active=false", ScimUserAdapter.mapStatusToActive(UserStatusEnumType.DISABLED.toString()));
		assertFalse("RESTRICTED should map to active=false", ScimUserAdapter.mapStatusToActive(UserStatusEnumType.RESTRICTED.toString()));
		assertTrue("null status should default to true", ScimUserAdapter.mapStatusToActive(null));

		assertEquals("active=true should map to NORMAL", UserStatusEnumType.NORMAL.toString(), ScimUserAdapter.mapActiveToStatus(true));
		assertEquals("active=false should map to DISABLED", UserStatusEnumType.DISABLED.toString(), ScimUserAdapter.mapActiveToStatus(false));
	}

	@Test
	public void testRoundTripFidelity() {
		logger.info("Testing SCIM User Adapter - round trip");
		BaseRecord testUser = getCreateUser("scimTestUser6");
		assertNotNull("Test user should not be null", testUser);

		Map<String, Object> scim1 = ScimUserAdapter.toScim(testUser, null, BASE_URL);
		assertNotNull("First SCIM output should not be null", scim1);

		Map<String, Object> scim2 = ScimUserAdapter.toScim(testUser, null, BASE_URL);
		assertNotNull("Second SCIM output should not be null", scim2);

		assertEquals("id should be consistent", scim1.get("id"), scim2.get("id"));
		assertEquals("userName should be consistent", scim1.get("userName"), scim2.get("userName"));
		assertEquals("active should be consistent", scim1.get("active"), scim2.get("active"));
	}
}
