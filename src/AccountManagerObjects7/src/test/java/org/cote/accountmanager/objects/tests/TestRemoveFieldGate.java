package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Test;

/// RecordFactory.removeFieldFromSchema issues ALTER TABLE ... DROP COLUMN. That is destructive and irreversible, so it must be
/// gated on the same off-by-default property that gates the boot-time orphan-column cleanup (IOProperties.isDropColumns(),
/// mirrored onto IOContext.isDropColumns() by IOSystem.open()). The test resource.properties sets db.schema.dropColumns=false,
/// so the default path here is the refusal path. Everything runs against a throwaway custom model; no system model is touched.
public class TestRemoveFieldGate extends BaseTest {

	private static final String MODEL_NAME = "custom.dropGateTest";
	private static final String KEEP_FIELD = "keepField";
	private static final String DROP_FIELD = "removeMe";

	private static final String SCHEMA_JSON = "{"
		+ "\"name\": \"" + MODEL_NAME + "\","
		+ "\"inherits\": [\"data.directory\"],"
		+ "\"group\": \"DropGateTest\","
		+ "\"version\": \"1.0\","
		+ "\"fields\": ["
		+ "  {\"name\": \"" + KEEP_FIELD + "\", \"type\": \"string\"},"
		+ "  {\"name\": \"" + DROP_FIELD + "\", \"type\": \"string\"}"
		+ "]"
		+ "}";

	private ModelSchema createThrowawayModel() {
		/// Clean up from any previous run. Custom model only - never a system model (releaseCustomSchema is DROP TABLE CASCADE).
		RecordFactory.releaseCustomSchema(MODEL_NAME);
		ModelSchema created = RecordFactory.importSchemaFromUser(MODEL_NAME, SCHEMA_JSON);
		assertNotNull("Throwaway custom schema was not created", created);
		assertFalse("Throwaway custom schema must be non-system", created.isSystem());
		return created;
	}

	private List<String> columnsOf(String modelName) {
		DBUtil dbUtil = ioContext.getDbUtil();
		String tableName = dbUtil.getTableName(modelName);
		return dbUtil.getTableColumns(tableName);
	}

	@Test
	public void TestRemoveFieldRefusedWhenDropColumnsOff() {
		assertFalse("Test configuration must have db.schema.dropColumns off for this test", ioContext.isDropColumns());

		ModelSchema created = createThrowawayModel();
		try {
			List<String> before = columnsOf(MODEL_NAME);
			assertTrue("Column for " + DROP_FIELD + " must exist before the attempt, found: " + before, before.contains(DROP_FIELD.toLowerCase()));

			boolean removed = RecordFactory.removeFieldFromSchema(created, DROP_FIELD);
			assertFalse("removeFieldFromSchema must refuse when column drops are disabled", removed);

			/// The column must still be there
			List<String> after = columnsOf(MODEL_NAME);
			assertTrue("Column for " + DROP_FIELD + " must survive a refused drop, found: " + after, after.contains(DROP_FIELD.toLowerCase()));

			/// The in-memory schema handed in must not have been mutated
			assertNotNull("Refused drop must not strip the field from the supplied schema", created.getFieldSchema(DROP_FIELD));

			/// The persisted schema definition must not have been touched either (keep table and definition consistent)
			RecordFactory.clearCache(MODEL_NAME);
			ModelSchema reloaded = RecordFactory.getSchema(MODEL_NAME);
			assertNotNull("Reloaded schema is null", reloaded);
			assertNotNull("Refused drop must leave the persisted schema definition intact", reloaded.getFieldSchema(DROP_FIELD));
			assertNotNull("Kept field must still exist", reloaded.getFieldSchema(KEEP_FIELD));
		}
		finally {
			RecordFactory.releaseCustomSchema(MODEL_NAME);
		}
	}

	@Test
	public void TestRemoveFieldAllowedWhenDropColumnsOn() {
		assertFalse("Test configuration must have db.schema.dropColumns off for this test", ioContext.isDropColumns());

		ModelSchema created = createThrowawayModel();
		try {
			/// Opt in for exactly this call, then restore the default so no later test inherits an open gate.
			ioContext.setDropColumns(true);
			boolean removed;
			try {
				removed = RecordFactory.removeFieldFromSchema(created, DROP_FIELD);
			}
			finally {
				ioContext.setDropColumns(false);
			}
			assertTrue("removeFieldFromSchema must succeed when column drops are enabled", removed);

			List<String> after = columnsOf(MODEL_NAME);
			assertFalse("Column for " + DROP_FIELD + " must be gone after an allowed drop, found: " + after, after.contains(DROP_FIELD.toLowerCase()));
			assertTrue("Column for " + KEEP_FIELD + " must survive, found: " + after, after.contains(KEEP_FIELD.toLowerCase()));

			RecordFactory.clearCache(MODEL_NAME);
			ModelSchema reloaded = RecordFactory.getSchema(MODEL_NAME);
			assertNotNull("Reloaded schema is null", reloaded);
			assertNull("Removed field must be gone from the persisted schema definition", reloaded.getFieldSchema(DROP_FIELD));
			assertNotNull("Kept field must still exist", reloaded.getFieldSchema(KEEP_FIELD));
		}
		finally {
			ioContext.setDropColumns(false);
			RecordFactory.releaseCustomSchema(MODEL_NAME);
		}
	}

	@Test
	public void TestRemoveUnknownFieldIsRejectedRegardlessOfGate() {
		ModelSchema created = createThrowawayModel();
		try {
			assertFalse("Unknown field must be rejected with the gate closed", RecordFactory.removeFieldFromSchema(created, "noSuchField"));
			ioContext.setDropColumns(true);
			try {
				assertFalse("Unknown field must be rejected with the gate open", RecordFactory.removeFieldFromSchema(created, "noSuchField"));
			}
			finally {
				ioContext.setDropColumns(false);
			}
			assertFalse("Null schema must be rejected", RecordFactory.removeFieldFromSchema(null, DROP_FIELD));
			assertFalse("Null field name must be rejected", RecordFactory.removeFieldFromSchema(created, null));
		}
		finally {
			ioContext.setDropColumns(false);
			RecordFactory.releaseCustomSchema(MODEL_NAME);
		}
	}
}
