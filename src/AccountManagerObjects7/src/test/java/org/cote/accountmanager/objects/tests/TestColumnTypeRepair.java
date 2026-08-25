package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Test;

public class TestColumnTypeRepair extends BaseTest {

	private static final String MODEL_NAME = "test.coltyperepair";
	private static final String RESOURCE_NAME = "coltyperepair";

	@Test
	public void testRepairMismatchedColumn() throws SQLException {
		// Clean up any leftover state from a previous run
		try {
			RecordFactory.releaseCustomSchema(MODEL_NAME);
		}
		catch(Exception e) {
			logger.warn("Pre-cleanup releaseCustomSchema skipped: " + e.getMessage());
		}

		// 1. Load the custom test model schema
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource(MODEL_NAME, RESOURCE_NAME);
		assertNotNull("Model schema must be loaded", ms);

		DBUtil dbUtil = ioContext.getDbUtil();
		String tableName = dbUtil.getTableName(ms.getName());
		logger.info("Test table name: " + tableName);

		// 2 + 3. generateSchema includes DROP TABLE IF EXISTS, so this creates a fresh table
		String createSql = dbUtil.generateSchema(ms);
		assertNotNull("Schema DDL must not be null", createSql);
		dbUtil.execute(createSql);
		assertTrue("Table must exist after creation", dbUtil.haveTable(ms.getName()));

		// 4. Freshly-created table should have no type mismatches
		List<FieldSchema> mismatched = dbUtil.getMismatchedColumns(ms);
		assertTrue("Expected no mismatches on a freshly-created table, found: "
			+ mismatched.stream().map(FieldSchema::getName).collect(Collectors.joining(", ")),
			mismatched.isEmpty());

		// 5. Deliberately corrupt the fkField column type from bigint to text
		try (Connection con = dbUtil.getDataSource().getConnection();
			 Statement st = con.createStatement()) {
			st.executeUpdate("ALTER TABLE " + tableName + " ALTER COLUMN fkfield TYPE text USING fkfield::text");
		}

		// 6. Mismatch should now be detected, and fkField should be in the list
		List<FieldSchema> mismatchedAfterCorrupt = dbUtil.getMismatchedColumns(ms);
		assertFalse("Expected at least one mismatch after corruption", mismatchedAfterCorrupt.isEmpty());
		List<String> mismatchedNames = mismatchedAfterCorrupt.stream()
			.map(FieldSchema::getName)
			.collect(Collectors.toList());
		assertTrue("Expected fkField to be in mismatched list, found: " + mismatchedNames,
			mismatchedNames.contains("fkField"));

		// 7. Execute the generated repair statements
		List<String> repairs = dbUtil.generateAlterColumnTypeSchema(ms);
		assertFalse("Expected repair statements to be generated", repairs.isEmpty());
		for(String repair : repairs) {
			logger.info("Executing repair: " + repair);
			dbUtil.executeWithException(repair);
		}

		// 8. After repair, there should be no mismatches
		List<FieldSchema> mismatchedAfterRepair = dbUtil.getMismatchedColumns(ms);
		assertTrue("Expected no mismatches after repair, found: "
			+ mismatchedAfterRepair.stream().map(FieldSchema::getName).collect(Collectors.joining(", ")),
			mismatchedAfterRepair.isEmpty());

		// 9. Confirm the column data type is now bigint via getColumnDataTypes
		// getColumnDataTypes is private — we access it indirectly through getMismatchedColumns above,
		// but we can also verify by checking that getMismatchedColumns reports clean for the full schema.
		// For a direct type assertion we re-read via a raw query (mirrors the test in TestSchemaModification).
		final String expectedType = "bigint";
		String actualType = null;
		try (Connection con = dbUtil.getDataSource().getConnection();
			 java.sql.PreparedStatement st = con.prepareStatement(
				"SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = 'fkfield'")) {
			String useTableName = tableName.toLowerCase(); // PostgreSQL lower-cases unquoted identifiers
			st.setString(1, useTableName);
			java.sql.ResultSet rset = st.executeQuery();
			if(rset.next()) {
				actualType = rset.getString(1);
			}
			rset.close();
		}
		assertNotNull("Column fkfield must exist in information_schema after repair", actualType);
		assertEquals("Column fkfield must be bigint after repair", expectedType, actualType.toLowerCase());

		// 10. Cleanup: releaseCustomSchema drops the table and removes the schema entry
		boolean released = RecordFactory.releaseCustomSchema(MODEL_NAME);
		assertTrue("Schema release must succeed", released);
		// After release the schema is unloaded; haveTable(modelName) would NPE because it calls
		// RecordFactory.getSchema internally.  Use getTableColumns(tableName) instead: it queries
		// information_schema directly with the literal table name and returns empty for a dropped table.
		assertTrue("Table must not exist after release (getTableColumns should be empty)",
			dbUtil.getTableColumns(tableName).isEmpty());
	}
}
