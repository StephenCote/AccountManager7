package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Covers the schema-patch index path: index DDL used to be emitted only by generateSchema (the
/// CREATE TABLE path), so a constraint or hint added to an already-created model was silently never
/// applied.  Also covers the indexability gate, which rejected foreign 'model' fields even though
/// they are persisted as plain scalar columns.
///
/// NOTE: generateSchema output is deliberately never executed here - it leads with
/// DROP TABLE IF EXISTS ... CASCADE.
///
public class TestSchemaIndexPatch extends BaseTest {

	private static final String MODEL_GROUP_EXPORT = "data.groupExport";
	/// auth.role is the meaningful dedicatedParticipation model: every entitlement check seeks the role
	/// participation table on (participantId, participantModel).
	private static final String MODEL_ROLE = "auth.role";
	/// A dedicatedParticipation model whose participation index names exceed the 63 byte PostgreSQL
	/// identifier limit
	private static final String MODEL_CONTACT_INFORMATION = "identity.contactInformation";

	/// CREATE [UNIQUE] INDEX IF NOT EXISTS <name> on <table>(<cols>);
	private static final Pattern indexStatement = Pattern.compile(
		"^CREATE (UNIQUE )?INDEX IF NOT EXISTS (\\S+) on (\\S+)\\(([^)]+)\\);$"
	);

	@Test
	public void TestIndexStatementForm() {
		DBUtil dbUtil = ioContext.getDbUtil();
		assertNotNull("Expected a dbUtil", dbUtil);
		ModelSchema ms = RecordFactory.getSchema(MODEL_GROUP_EXPORT);
		assertNotNull("Expected the " + MODEL_GROUP_EXPORT + " schema", ms);

		List<String> stmts = dbUtil.generatePatchIndices(ms);
		logger.info(MODEL_GROUP_EXPORT + " index statements:\n" + stmts.stream().collect(Collectors.joining("\n")));
		assertFalse("Expected at least one index statement", stmts.isEmpty());

		String tableName = dbUtil.getTableName(MODEL_GROUP_EXPORT);
		boolean foundUnique = false;
		boolean foundNonUnique = false;
		for(String stmt : stmts) {
			assertTrue("Statement is not idempotent: " + stmt, stmt.contains("IF NOT EXISTS"));
			Matcher m = indexStatement.matcher(stmt);
			assertTrue("Malformed index statement: " + stmt, m.matches());
			assertEquals("Wrong table in: " + stmt, tableName, m.group(3));
			assertTrue("Empty column list in: " + stmt, m.group(4).trim().length() > 0);
			/// The name must be parseable back out, it is what the startup path uses to skip existing indices
			assertEquals("Index name mismatch: " + stmt, m.group(2), DBUtil.getIndexStatementName(stmt));
			if(m.group(1) != null) {
				foundUnique = true;
			}
			else {
				foundNonUnique = true;
			}
		}
		/// data.groupExport declares constraints (unique) and hints (non-unique)
		assertTrue("Expected a UNIQUE index from a constraint", foundUnique);
		assertTrue("Expected a non-unique index from a hint", foundNonUnique);
	}

	/// Change 4: a foreign 'model' field is emitted as a scalar column (bigint) and must be indexable.
	/// Prior behavior rejected the whole index - including its other columns - as 'cannot be indexed'.
	///
	@Test
	public void TestForeignModelFieldIsIndexed() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema ms = RecordFactory.getSchema(MODEL_GROUP_EXPORT);
		assertEquals("Expected sourceGroup to be a foreign model field", "model", ms.getFieldSchema("sourceGroup").getType());
		assertTrue("Expected sourceGroup to be foreign", ms.getFieldSchema("sourceGroup").isForeign());

		List<String> stmts = dbUtil.generatePatchIndices(ms);
		List<String> overSourceGroup = stmts.stream().filter(s -> columnsOf(s).contains("sourceGroup")).collect(Collectors.toList());
		assertFalse("Expected at least one index over sourceGroup, got:\n" + stmts.stream().collect(Collectors.joining("\n")), overSourceGroup.isEmpty());

		/// The declared constraint "sourceGroup, organizationId" - one export per source group per organization
		List<String> unique = overSourceGroup.stream()
			.filter(s -> s.startsWith("CREATE UNIQUE INDEX"))
			.filter(s -> columnsOf(s).contains("organizationId"))
			.collect(Collectors.toList());
		assertEquals("Expected exactly one unique index over (sourceGroup, organizationId)", 1, unique.size());

		/// The declared hint "sourceGroup"
		List<String> hinted = overSourceGroup.stream()
			.filter(s -> s.startsWith("CREATE INDEX"))
			.filter(s -> columnsOf(s).size() == 1)
			.collect(Collectors.toList());
		assertEquals("Expected exactly one non-unique index over (sourceGroup)", 1, hinted.size());
	}

	/// Fields that emit no column, or an unbounded text column, must still be rejected.
	///
	@Test
	public void TestUnindexableFieldsRejected() {
		DBUtil dbUtil = ioContext.getDbUtil();

		/// A foreign list is persisted through participations - no column at all
		ModelSchema listCopy = isolatedCopy(MODEL_GROUP_EXPORT);
		assertEquals("Expected controls to be a list", "list", listCopy.getFieldSchema("controls").getType());
		assertTrue("Expected controls to be foreign", listCopy.getFieldSchema("controls").isForeign());
		listCopy.setConstraints(new ArrayList<>(Arrays.asList("controls")));
		listCopy.setHints(new ArrayList<>(Arrays.asList("controls")));
		List<String> listStmts = dbUtil.generatePatchIndices(listCopy);
		assertTrue("A foreign list must not be indexed, got: " + listStmts, listStmts.isEmpty());

		/// A string with no maxLength is emitted as unbounded text
		ModelSchema textCopy = isolatedCopy(MODEL_GROUP_EXPORT);
		textCopy.getFieldSchema("name").setMaxLength(0);
		assertEquals("Expected an unbounded text column", "text", dbUtil.getDataType(textCopy.getFieldSchema("name"), textCopy.getFieldSchema("name").getFieldType()));
		textCopy.setConstraints(new ArrayList<>(Arrays.asList("name, groupId")));
		textCopy.setHints(new ArrayList<>(Arrays.asList("name")));
		List<String> textStmts = dbUtil.generatePatchIndices(textCopy);
		assertTrue("An unbounded text column must not be indexed, got: " + textStmts, textStmts.isEmpty());

		/// Sanity: with the maxLength restored, the same constraint and hint do produce indices
		ModelSchema okCopy = isolatedCopy(MODEL_GROUP_EXPORT);
		okCopy.setConstraints(new ArrayList<>(Arrays.asList("name, groupId")));
		okCopy.setHints(new ArrayList<>(Arrays.asList("name")));
		assertEquals("Expected a constraint index and a hint index", 2, dbUtil.generatePatchIndices(okCopy).size());
	}

	/// One statement per constraint and per hint, across the whole inheritance chain.
	///
	@Test
	public void TestPatchIndicesPerConstraintAndHint() {
		DBUtil dbUtil = ioContext.getDbUtil();

		/// Controlled case: inheritance detached so the declared set is exactly what is counted
		ModelSchema copy = isolatedCopy(MODEL_GROUP_EXPORT);
		copy.setConstraints(new ArrayList<>(Arrays.asList("sourceGroup, organizationId", "name, groupId, organizationId")));
		copy.setHints(new ArrayList<>(Arrays.asList("sourceGroup", "objectId", "urn")));
		List<String> stmts = dbUtil.generatePatchIndices(copy);
		assertEquals("Expected one statement per constraint + hint: " + stmts, 5, stmts.size());
		assertEquals("Expected 2 unique indices", 2, stmts.stream().filter(s -> s.startsWith("CREATE UNIQUE INDEX")).count());
		assertEquals("Expected distinct index names", 5, stmts.stream().map(DBUtil::getIndexStatementName).distinct().count());

		/// Live case: the real model, whose inherited constraints and hints are included
		ModelSchema ms = RecordFactory.getSchema(MODEL_GROUP_EXPORT);
		List<String> live = dbUtil.generatePatchIndices(ms);
		assertTrue("Expected the inherited constraints and hints to be included: " + live, live.size() >= 5);
	}

	/// The same statement must succeed twice - it is now replayed on every startup.
	///
	@Test
	public void TestIndexIdempotencyAgainstDatabase() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema ms = RecordFactory.getSchema(MODEL_GROUP_EXPORT);
		assertTrue("Expected the " + MODEL_GROUP_EXPORT + " table to exist", dbUtil.haveTable(MODEL_GROUP_EXPORT));

		/// Use the non-unique hint index - a unique index can legitimately fail on pre-existing data,
		/// which would make this test about the data rather than about idempotency.
		List<String> stmts = dbUtil.generatePatchIndices(ms).stream()
			.filter(s -> s.startsWith("CREATE INDEX"))
			.filter(s -> columnsOf(s).equals(Arrays.asList("sourceGroup")))
			.collect(Collectors.toList());
		assertEquals("Expected the sourceGroup hint index", 1, stmts.size());
		String stmt = stmts.get(0);

		try {
			dbUtil.executeWithException(stmt);
			dbUtil.executeWithException(stmt);
		}
		catch(SQLException e) {
			fail("Index statement is not replayable: " + stmt + " - " + e.getMessage());
		}

		String idxName = DBUtil.getIndexStatementName(stmt);
		assertNotNull("Expected a parseable index name", idxName);
		assertTrue("Expected " + idxName + " to exist after execution", dbUtil.getIndexNames().contains(idxName.toLowerCase()));
	}

	/// A model declaring dedicatedParticipation owns a separate participation table which, like the
	/// model's own table, is emitted only by generateSchema.  Its indices - the three system.participation
	/// hints - must therefore come back from the same patch entry point.
	///
	@Test
	public void TestDedicatedParticipationIndicesPatched() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema ms = RecordFactory.getSchema(MODEL_ROLE);
		assertNotNull("Expected the " + MODEL_ROLE + " schema", ms);
		assertTrue("Expected " + MODEL_ROLE + " to declare dedicatedParticipation", ms.isDedicatedParticipation());

		/// Precondition: the dedicated participation table must exist for its indices to be patched
		assertTrue("Expected the dedicated participation table for " + MODEL_ROLE + " to exist",
			dbUtil.haveTable(ms, ModelNames.MODEL_PARTICIPATION));

		String ownTable = dbUtil.getTableName(MODEL_ROLE);
		String partTable = dbUtil.getTableName(ms, ModelNames.MODEL_PARTICIPATION);
		String ver = RecordFactory.getSchema(ModelNames.MODEL_PARTICIPATION).getVersion().replace(".", "_");
		/// The dedicated table is the participation model's table, prefixed with the owning model name
		assertEquals("Unexpected dedicated participation table name", "A7_auth_role_system_participation_" + ver, partTable);
		assertFalse("The participation table must not be the model's own table", partTable.equals(ownTable));

		List<String> stmts = dbUtil.generatePatchIndices(ms);
		logger.info(MODEL_ROLE + " index statements:\n" + stmts.stream().collect(Collectors.joining("\n")));

		List<String> partStmts = stmts.stream().filter(s -> partTable.equals(tableOf(s))).collect(Collectors.toList());
		List<String> ownStmts = stmts.stream().filter(s -> ownTable.equals(tableOf(s))).collect(Collectors.toList());
		assertFalse("Expected index statements for the dedicated participation table", partStmts.isEmpty());
		assertFalse("Expected the model's own index statements to still be present", ownStmts.isEmpty());
		assertEquals("Every statement must target either the model table or its participation table",
			stmts.size(), partStmts.size() + ownStmts.size());
		assertEquals("Expected distinct index names across both tables", stmts.size(),
			stmts.stream().map(DBUtil::getIndexStatementName).distinct().count());

		/// Names are prefixed with the owning model so they do not collide with the shared participation table
		for(String stmt : partStmts) {
			assertTrue("Statement is not idempotent: " + stmt, stmt.contains("IF NOT EXISTS"));
			String idxName = DBUtil.getIndexStatementName(stmt);
			assertNotNull("Expected a parseable index name: " + stmt, idxName);
			assertTrue("Expected the owning model prefix on: " + idxName,
				idxName.startsWith("A7_auth_role_system_participation_" + ver + "_"));
		}

		/// All three hints declared on system.participation
		List<List<String>> partCols = partStmts.stream().map(this::columnsOf).collect(Collectors.toList());
		assertTrue("Missing the (participationId, participationModel) hint: " + partCols,
			partCols.contains(Arrays.asList("participationId", "participationModel")));
		assertTrue("Missing the (participantId, participantModel) hint: " + partCols,
			partCols.contains(Arrays.asList("participantId", "participantModel")));
		assertTrue("Missing the (participationId, participationModel, participantId, participantModel) hint: " + partCols,
			partCols.contains(Arrays.asList("participationId", "participationModel", "participantId", "participantModel")));
	}

	/// After startup every declared dedicated participation index must be recognized as already
	/// existing, otherwise the startup path re-issues it on every open.  The dedicated participation
	/// index names are the ones that exceed the PostgreSQL 63 byte identifier limit - the database
	/// stores them truncated, so the existence check has to compare the normalized form.
	///
	@Test
	public void TestLongParticipationIndexNamesResolveToExistingIndices() {
		DBUtil dbUtil = ioContext.getDbUtil();
		List<String> existing = dbUtil.getIndexNames();
		boolean sawTruncated = false;

		for(String modelName : Arrays.asList(MODEL_ROLE, MODEL_CONTACT_INFORMATION)) {
			ModelSchema ms = RecordFactory.getSchema(modelName);
			assertTrue("Expected " + modelName + " to declare dedicatedParticipation", ms.isDedicatedParticipation());
			assertTrue("Expected the dedicated participation table for " + modelName + " to exist",
				dbUtil.haveTable(ms, ModelNames.MODEL_PARTICIPATION));
			String partTable = dbUtil.getTableName(ms, ModelNames.MODEL_PARTICIPATION);
			List<String> partStmts = dbUtil.generatePatchIndices(ms).stream()
				.filter(s -> partTable.equals(tableOf(s)))
				.collect(Collectors.toList());
			/// The three hints declared on system.participation, plus whatever its inheritance adds
			assertTrue("Expected at least the three system.participation hints for " + modelName + ": " + partStmts,
				partStmts.size() >= 3);
			for(String stmt : partStmts) {
				String raw = DBUtil.getIndexStatementName(stmt);
				assertNotNull("Expected a parseable index name: " + stmt, raw);
				String norm = dbUtil.normalizeIndexName(raw);
				if(raw.length() > 63) {
					sawTruncated = true;
					assertTrue("Expected the name to be normalized to the identifier limit: " + raw, norm.length() == 63);
				}
				assertTrue("Index " + norm + " is not present in the database, so the startup path would re-issue "
					+ stmt, existing.contains(norm));
			}
		}
		assertTrue("Expected at least one participation index name past the 63 byte identifier limit", sawTruncated);
	}

	/// A model that does not declare dedicatedParticipation must not emit anything against a
	/// participation table - it uses the shared one, which is patched under its own model name.
	///
	@Test
	public void TestNonDedicatedModelEmitsNoParticipationIndices() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema ms = RecordFactory.getSchema(MODEL_GROUP_EXPORT);
		assertFalse("Expected " + MODEL_GROUP_EXPORT + " not to declare dedicatedParticipation", ms.isDedicatedParticipation());

		String ownTable = dbUtil.getTableName(MODEL_GROUP_EXPORT);
		List<String> stmts = dbUtil.generatePatchIndices(ms);
		assertFalse("Expected at least one index statement", stmts.isEmpty());
		for(String stmt : stmts) {
			assertEquals("A non-dedicated model must only index its own table: " + stmt, ownTable, tableOf(stmt));
			assertFalse("A non-dedicated model must not emit participation indices: " + stmt,
				stmt.contains(ModelNames.MODEL_PARTICIPATION.replace('.', '_')));
		}
	}

	/// The dedicated participation table existing is a precondition: when the model declares
	/// dedicatedParticipation but the table was never created, the participation statements are skipped
	/// (with a warning) rather than issued to fail against a nonexistent relation.
	///
	@Test
	public void TestMissingParticipationTableIsSkipped() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema copy = isolatedCopy(MODEL_GROUP_EXPORT);
		copy.setConstraints(new ArrayList<>(Arrays.asList("name, groupId")));
		copy.setHints(new ArrayList<>(Arrays.asList("sourceGroup")));
		List<String> before = dbUtil.generatePatchIndices(copy);
		assertEquals("Expected the constraint and hint indices", 2, before.size());

		copy.setDedicatedParticipation(true);
		assertFalse("This test requires a model with no dedicated participation table",
			dbUtil.haveTable(copy, ModelNames.MODEL_PARTICIPATION));
		List<String> after = dbUtil.generatePatchIndices(copy);
		assertEquals("A missing participation table must add no statements: " + after, before, after);
	}

	/// A deep copy of a registered model with its inheritance detached, so constraints and hints can be
	/// set exactly.  Fields are already resolved on the schema returned by RecordFactory, so field
	/// lookup still works; the model name stays real so table naming resolves.
	///
	private ModelSchema isolatedCopy(String modelName) {
		ModelSchema src = RecordFactory.getSchema(modelName);
		assertNotNull("Expected the " + modelName + " schema", src);
		ModelSchema copy = JSONUtil.importObject(JSONUtil.exportObject(src), ModelSchema.class);
		assertNotNull("Failed to copy the schema", copy);
		copy.setInherits(new ArrayList<>());
		copy.setConstraints(new ArrayList<>());
		copy.setHints(new ArrayList<>());
		return copy;
	}

	private String tableOf(String stmt) {
		Matcher m = indexStatement.matcher(stmt);
		if(!m.matches()) {
			return null;
		}
		return m.group(3);
	}

	private List<String> columnsOf(String stmt) {
		Matcher m = indexStatement.matcher(stmt);
		if(!m.matches()) {
			return new ArrayList<>();
		}
		return Arrays.asList(m.group(4).replaceAll("[\" ]", "").split(",")).stream().collect(Collectors.toList());
	}

}
