package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Test;

public class TestSchemaService extends BaseTest {

	@Test
	public void TestIsSystemFlag() {
		/// System models should be marked as system
		ModelSchema userSchema = RecordFactory.getSchema("system.user");
		assertNotNull("User schema is null", userSchema);
		assertTrue("System model should have isSystem=true", userSchema.isSystem());

		/// System fields should be marked as system
		FieldSchema nameField = userSchema.getFieldSchema("name");
		assertNotNull("Name field is null", nameField);
		assertTrue("System field should have isSystem=true", nameField.isSystem());
	}

	@Test
	public void TestCreateUserDefinedModel() {
		/// Clean up from any previous test run
		RecordFactory.releaseCustomSchema("custom.testModel");

		String schemaJson = "{"
			+ "\"name\": \"custom.testModel\","
			+ "\"inherits\": [\"data.directory\"],"
			+ "\"group\": \"CustomTest\","
			+ "\"version\": \"1.0\","
			+ "\"fields\": ["
			+ "  {\"name\": \"customField1\", \"type\": \"string\"},"
			+ "  {\"name\": \"customField2\", \"type\": \"int\", \"default\": 0}"
			+ "]"
			+ "}";

		ModelSchema created = RecordFactory.importSchemaFromUser("custom.testModel", schemaJson);
		assertNotNull("Created schema is null", created);
		assertEquals("custom.testModel", created.getName());

		/// User-defined model should be non-system
		assertFalse("User-defined model should have isSystem=false", created.isSystem());

		/// User-defined fields should be non-system
		FieldSchema cf1 = created.getFieldSchema("customField1");
		assertNotNull("customField1 is null", cf1);
		assertFalse("User-defined field should have isSystem=false", cf1.isSystem());

		/// Inherited fields should remain system
		FieldSchema idField = created.getFieldSchema("id");
		assertNotNull("id field is null", idField);
		assertTrue("Inherited field should have isSystem=true", idField.isSystem());

		/// Cleanup
		RecordFactory.releaseCustomSchema("custom.testModel");
	}

	@Test
	public void TestAddFieldToSchema() {
		/// Clean up from any previous test run
		RecordFactory.releaseCustomSchema("custom.addFieldTest");

		String schemaJson = "{"
			+ "\"name\": \"custom.addFieldTest\","
			+ "\"inherits\": [\"data.directory\"],"
			+ "\"group\": \"AddFieldTest\","
			+ "\"version\": \"1.0\","
			+ "\"fields\": ["
			+ "  {\"name\": \"originalField\", \"type\": \"string\"}"
			+ "]"
			+ "}";

		ModelSchema created = RecordFactory.importSchemaFromUser("custom.addFieldTest", schemaJson);
		assertNotNull("Created schema is null", created);

		/// Add a new field
		FieldSchema newField = new FieldSchema();
		newField.setName("addedField");
		newField.setType("boolean");
		newField.setDefaultValue(false);
		newField.setSystem(false);

		boolean added = RecordFactory.addFieldToSchema(created, newField);
		assertTrue("Expected field to be added", added);

		/// Reload and verify
		ModelSchema reloaded = RecordFactory.getSchema("custom.addFieldTest");
		assertNotNull("Reloaded schema is null", reloaded);
		FieldSchema af = reloaded.getFieldSchema("addedField");
		assertNotNull("Added field not found after reload", af);
		assertEquals("boolean", af.getType());

		/// Cleanup
		RecordFactory.releaseCustomSchema("custom.addFieldTest");
	}

	@Test
	public void TestRemoveFieldFromSchema() {
		/// Clean up from any previous test run
		RecordFactory.releaseCustomSchema("custom.removeFieldTest");

		String schemaJson = "{"
			+ "\"name\": \"custom.removeFieldTest\","
			+ "\"inherits\": [\"data.directory\"],"
			+ "\"group\": \"RemoveFieldTest\","
			+ "\"version\": \"1.0\","
			+ "\"fields\": ["
			+ "  {\"name\": \"keepField\", \"type\": \"string\"},"
			+ "  {\"name\": \"removeMe\", \"type\": \"string\"}"
			+ "]"
			+ "}";

		ModelSchema created = RecordFactory.importSchemaFromUser("custom.removeFieldTest", schemaJson);
		assertNotNull("Created schema is null", created);

		/// Remove a field
		boolean removed = RecordFactory.removeFieldFromSchema(created, "removeMe");
		assertTrue("Expected field to be removed", removed);

		/// Reload and verify
		ModelSchema reloaded = RecordFactory.getSchema("custom.removeFieldTest");
		assertNotNull("Reloaded schema is null", reloaded);
		assertNull("Removed field should not exist", reloaded.getFieldSchema("removeMe"));
		assertNotNull("Kept field should still exist", reloaded.getFieldSchema("keepField"));

		/// Cleanup
		RecordFactory.releaseCustomSchema("custom.removeFieldTest");
	}

	@Test
	public void TestDeleteUserDefinedModel() {
		/// Clean up from any previous test run
		RecordFactory.releaseCustomSchema("custom.deleteTest");

		String schemaJson = "{"
			+ "\"name\": \"custom.deleteTest\","
			+ "\"inherits\": [\"data.directory\"],"
			+ "\"group\": \"DeleteTest\","
			+ "\"version\": \"1.0\","
			+ "\"fields\": ["
			+ "  {\"name\": \"testField\", \"type\": \"string\"}"
			+ "]"
			+ "}";

		ModelSchema created = RecordFactory.importSchemaFromUser("custom.deleteTest", schemaJson);
		assertNotNull("Created schema is null", created);
		assertFalse("Should be non-system", created.isSystem());

		/// Delete user-defined model
		boolean released = RecordFactory.releaseCustomSchema("custom.deleteTest");
		assertTrue("Expected model to be deleted", released);

		/// Verify it's gone (clear cache first)
		RecordFactory.clearCache("custom.deleteTest");
		ModelSchema deleted = RecordFactory.getSchema("custom.deleteTest");
		assertNull("Deleted model should not exist", deleted);
	}

	@Test
	public void TestSystemModelProtection() {
		/// System models should be marked as system
		ModelSchema systemSchema = RecordFactory.getSchema("system.user");
		assertNotNull("System schema is null", systemSchema);
		assertTrue("system.user should be a system model", systemSchema.isSystem());
	}

	@Test
	public void TestSchemaUpdatePersists() {
		/// Clean up from any previous test run
		RecordFactory.releaseCustomSchema("custom.persistTest");

		String schemaJson = "{"
			+ "\"name\": \"custom.persistTest\","
			+ "\"inherits\": [\"data.directory\"],"
			+ "\"group\": \"PersistTest\","
			+ "\"version\": \"1.0\","
			+ "\"fields\": ["
			+ "  {\"name\": \"field1\", \"type\": \"string\"}"
			+ "]"
			+ "}";

		ModelSchema created = RecordFactory.importSchemaFromUser("custom.persistTest", schemaJson);
		assertNotNull("Created schema is null", created);

		/// Update description via updateSchemaDefinition
		created.setDescription("Updated description");
		boolean updated = RecordFactory.updateSchemaDefinition(created);
		assertTrue("Expected schema to be updated", updated);

		/// Reload and verify
		ModelSchema reloaded = RecordFactory.getSchema("custom.persistTest");
		assertNotNull("Reloaded schema is null", reloaded);
		assertEquals("Updated description", reloaded.getDescription());

		/// Cleanup
		RecordFactory.releaseCustomSchema("custom.persistTest");
	}
}
