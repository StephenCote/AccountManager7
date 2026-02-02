package org.cote.accountmanager.objects.tests.scim;

import org.cote.accountmanager.objects.tests.BaseTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.UserStatusEnumType;
import org.cote.accountmanager.scim.ScimPatchHandler;
import org.junit.Test;

public class TestScimPatchHandler extends BaseTest {

	@Test
	public void testReplaceSingleValue() {
		logger.info("Testing SCIM Patch Handler - replace single value");
		BaseRecord adminUser = orgContext.getAdminUser();
		BaseRecord testUser = getCreateUser("scimPatchUser1");
		assertNotNull("Test user should not be null", testUser);

		List<Map<String, Object>> ops = new ArrayList<>();
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("op", "replace");
		op.put("path", "active");
		op.put("value", false);
		ops.add(op);

		List<String> errors = ScimPatchHandler.applyPatch(testUser, null, ops);
		assertTrue("Should have no errors", errors.isEmpty());

		String status = testUser.get(FieldNames.FIELD_STATUS);
		assertEquals("Status should be DISABLED", UserStatusEnumType.DISABLED.toString(), status);
	}

	@Test
	public void testReplaceNameFields() {
		logger.info("Testing SCIM Patch Handler - replace name fields");
		BaseRecord adminUser = orgContext.getAdminUser();
		BaseRecord testUser = getCreateUser("scimPatchUser2");
		assertNotNull("Test user should not be null", testUser);

		try {
			BaseRecord person = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_PERSON, adminUser, null, null
			);
			person.set(FieldNames.FIELD_NAME, "Patch Person 2");
			person.set(FieldNames.FIELD_FIRST_NAME, "Original");
			person.set(FieldNames.FIELD_LAST_NAME, "Name");
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(adminUser, person);
			assertNotNull("Person should be created", created);

			List<Map<String, Object>> ops = new ArrayList<>();
			Map<String, Object> op = new LinkedHashMap<>();
			op.put("op", "replace");
			op.put("path", "name.givenName");
			op.put("value", "NewFirst");
			ops.add(op);

			Map<String, Object> op2 = new LinkedHashMap<>();
			op2.put("op", "replace");
			op2.put("path", "name.familyName");
			op2.put("value", "NewLast");
			ops.add(op2);

			List<String> errors = ScimPatchHandler.applyPatch(testUser, created, ops);
			assertTrue("Should have no errors", errors.isEmpty());

			assertEquals("First name should be updated", "NewFirst", created.get(FieldNames.FIELD_FIRST_NAME));
			assertEquals("Last name should be updated", "NewLast", created.get(FieldNames.FIELD_LAST_NAME));

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}

	@Test
	public void testReplaceNameObject() {
		logger.info("Testing SCIM Patch Handler - replace name object");
		BaseRecord adminUser = orgContext.getAdminUser();
		BaseRecord testUser = getCreateUser("scimPatchUser3");
		assertNotNull("Test user should not be null", testUser);

		try {
			BaseRecord person = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_PERSON, adminUser, null, null
			);
			person.set(FieldNames.FIELD_NAME, "Patch Person 3");
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(adminUser, person);
			assertNotNull("Person should be created", created);

			List<Map<String, Object>> ops = new ArrayList<>();
			Map<String, Object> op = new LinkedHashMap<>();
			op.put("op", "replace");
			op.put("path", "name");
			Map<String, Object> nameValue = new LinkedHashMap<>();
			nameValue.put("givenName", "First");
			nameValue.put("middleName", "Mid");
			nameValue.put("familyName", "Last");
			op.put("value", nameValue);
			ops.add(op);

			List<String> errors = ScimPatchHandler.applyPatch(testUser, created, ops);
			assertTrue("Should have no errors", errors.isEmpty());

			assertEquals("First name should be set", "First", created.get(FieldNames.FIELD_FIRST_NAME));
			assertEquals("Middle name should be set", "Mid", created.get(FieldNames.FIELD_MIDDLE_NAME));
			assertEquals("Last name should be set", "Last", created.get(FieldNames.FIELD_LAST_NAME));

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}

	@Test
	public void testRemoveRequiredField() {
		logger.info("Testing SCIM Patch Handler - remove required field");
		BaseRecord testUser = getCreateUser("scimPatchUser4");
		assertNotNull("Test user should not be null", testUser);

		List<Map<String, Object>> ops = new ArrayList<>();
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("op", "remove");
		op.put("path", "userName");
		ops.add(op);

		List<String> errors = ScimPatchHandler.applyPatch(testUser, null, ops);
		assertNotNull("Errors should not be null", errors);
		assertTrue("Should have error for removing required field", errors.size() > 0);
		assertTrue("Error should mention userName", errors.get(0).contains("userName"));
	}

	@Test
	public void testInvalidOperation() {
		logger.info("Testing SCIM Patch Handler - invalid operation");
		BaseRecord testUser = getCreateUser("scimPatchUser5");
		assertNotNull("Test user should not be null", testUser);

		List<Map<String, Object>> ops = new ArrayList<>();
		Map<String, Object> op = new LinkedHashMap<>();
		op.put("op", "move");
		op.put("path", "userName");
		op.put("value", "newName");
		ops.add(op);

		List<String> errors = ScimPatchHandler.applyPatch(testUser, null, ops);
		assertNotNull("Errors should not be null", errors);
		assertTrue("Should have error for unsupported operation", errors.size() > 0);
	}

	@Test
	public void testEmptyOperations() {
		logger.info("Testing SCIM Patch Handler - empty operations");
		BaseRecord testUser = getCreateUser("scimPatchUser6");
		assertNotNull("Test user should not be null", testUser);

		List<Map<String, Object>> ops = new ArrayList<>();
		List<String> errors = ScimPatchHandler.applyPatch(testUser, null, ops);
		assertTrue("Empty operations should produce no errors", errors.isEmpty());
	}

	@Test
	public void testMultipleOperations() {
		logger.info("Testing SCIM Patch Handler - multiple operations");
		BaseRecord testUser = getCreateUser("scimPatchUser7");
		assertNotNull("Test user should not be null", testUser);

		List<Map<String, Object>> ops = new ArrayList<>();

		Map<String, Object> op1 = new LinkedHashMap<>();
		op1.put("op", "replace");
		op1.put("path", "active");
		op1.put("value", false);
		ops.add(op1);

		Map<String, Object> op2 = new LinkedHashMap<>();
		op2.put("op", "replace");
		op2.put("path", "externalId");
		op2.put("value", "ext-12345");
		ops.add(op2);

		List<String> errors = ScimPatchHandler.applyPatch(testUser, null, ops);
		assertTrue("Should have no errors", errors.isEmpty());

		assertEquals("Status should be DISABLED", UserStatusEnumType.DISABLED.toString(), testUser.get(FieldNames.FIELD_STATUS));
	}

	@Test
	public void testRemoveOptionalField() {
		logger.info("Testing SCIM Patch Handler - remove optional field");
		BaseRecord adminUser = orgContext.getAdminUser();
		BaseRecord testUser = getCreateUser("scimPatchUser8");
		assertNotNull("Test user should not be null", testUser);

		try {
			BaseRecord person = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_PERSON, adminUser, null, null
			);
			person.set(FieldNames.FIELD_NAME, "Patch Person 8");
			person.set(FieldNames.FIELD_MIDDLE_NAME, "MiddleToRemove");
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(adminUser, person);
			assertNotNull("Person should be created", created);

			List<Map<String, Object>> ops = new ArrayList<>();
			Map<String, Object> op = new LinkedHashMap<>();
			op.put("op", "remove");
			op.put("path", "name.middleName");
			ops.add(op);

			List<String> errors = ScimPatchHandler.applyPatch(testUser, created, ops);
			assertTrue("Should have no errors", errors.isEmpty());

		} catch (Exception e) {
			logger.error(e);
			assertTrue("Should not throw exception: " + e.getMessage(), false);
		}
	}
}
