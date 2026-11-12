package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/// Phase 10 P10-4 (PictureBook V3, design §37.5): the six legacy FLUX Kontext fields - useKontext,
/// kontextModel, kontextSteps, kontextCfg, kontextSampler, kontextScheduler - left olio.sd.config.
///
/// Two things are pinned, both read-only against the database this JVM opened (am7db by default;
/// -Dtest.db.url / -Dtest.db.user / -Dtest.db.password point it elsewhere, e.g. the Docker stack DB):
///
/// 1. The model no longer declares any of the six, and compositeMode (the one switch that remains)
///    still defaults to flux2. A client that posts the removed keys has them dropped by
///    RecordDeserializer ("Invalid field" ERROR, not thrown) - pinned at the transport by
///    TestChatSceneCompositeModeGate.TestRemovedKontextKeysOnTheWireAreDroppedNotRefused.
///
/// 2. Removing a field from the model JSON leaves its column on the table. Nothing in this test or
///    any other drops it: that happens only at boot, for EVERY registered model, when
///    IOProperties.isDropColumns() (database.dropColumns / db.schema.dropColumns, default false) is on
///    (IOSystem.open, "Schema drop: ..."), and Stephen flips that himself. So this test enumerates and
///    logs the exact statement list that flip would issue on this database - the hand-back quotes it -
///    and asserts that olio.sd.config's orphan set contains the six (when the table predates P10-4)
///    and nothing else. The enumeration is bounded to the models this JVM registers (core + Olio);
///    ISO 42001 models register in their own module and Service7 logs their drops at boot.
public class TestSdConfigOrphanColumns extends BaseTest {

	private static final List<String> REMOVED = Arrays.asList(
		"useKontext", "kontextModel", "kontextSteps", "kontextCfg", "kontextSampler", "kontextScheduler"
	);

	/// Same precedence BaseTest.dbProperty uses (a -Dtest.db.url on the command line wins), so the log
	/// names the database the enumeration actually ran against.
	private static String dbUrl() {
		String sys = System.getProperty("test.db.url");
		return (sys != null && !sys.isBlank()) ? sys : testProperties.getProperty("test.db.url");
	}

	@Test
	public void removedFieldsAreNotDeclaredAndCompositeModeStillDefaultsToFlux2() {
		OlioModelNames.use();
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		assertNotNull("olio.sd.config schema must load", ms);
		List<String> declared = ms.getFields().stream().map(FieldSchema::getName).collect(Collectors.toList());
		for(String removed : REMOVED) {
			assertFalse("P10-4 removed " + removed + " from olio.sd.config, but it is still declared", declared.contains(removed));
		}
		assertTrue("compositeMode remains the one pipeline switch", declared.contains("compositeMode"));
		FieldSchema mode = ms.getFieldSchema("compositeMode");
		assertNotNull(mode);
		assertEquals("compositeMode still defaults to flux2", "flux2", mode.getDefaultValue());
		/// A freshly built record carries no trace of the six either. Measured: BaseRecord.get of an
		/// undeclared field is NOT a thrown FieldException - checkField -> RecordFactory.newFieldInstance
		/// throws, get() catches it, logs "get(useKontext): ... was not found on model" at ERROR and
		/// returns null (BaseRecord.java:285-290). So the old "default true" trap cannot come back
		/// through a read: the value is null, never a materialised default.
		try {
			org.cote.accountmanager.record.BaseRecord fresh = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			for(String removed : REMOVED) {
				assertFalse(removed + " must not be a field on a new olio.sd.config", fresh.hasField(removed));
				Object val = fresh.get(removed);
				assertTrue(removed + " reads null (ERROR-logged), not a default: " + val, val == null);
				assertFalse("the failed read must not have materialised " + removed, fresh.hasField(removed));
			}
		}
		catch(Exception e) {
			org.junit.Assert.fail("Building a bare olio.sd.config must not throw: " + e.getMessage());
		}
	}

	/// Read-only. Logs every ALTER TABLE ... DROP COLUMN IF EXISTS statement the dropColumns boot flag
	/// would issue against this database, across every model this JVM registers, and pins the
	/// olio.sd.config entry to exactly the six P10-4 columns (or none, if the table was created after
	/// P10-4 and never had them).
	@Test
	public void enumerateTheDropStatementsTheBootFlagWouldIssue() {
		OlioModelNames.use();
		DBUtil dbUtil = ioContext.getDbUtil();
		assertNotNull(dbUtil);
		assertFalse("This test never flips the drop flag; it only enumerates", ioContext.isDropColumns());

		List<String> all = new ArrayList<>();
		int models = 0;
		int tables = 0;
		for(String m : ModelNames.MODELS) {
			ModelSchema schema = RecordFactory.getSchema(m);
			if(schema == null || !RecordUtil.isIdentityModel(schema) || dbUtil.isConstrained(schema)) {
				continue;
			}
			models++;
			if(!dbUtil.haveTable(schema.getName())) {
				continue;
			}
			tables++;
			List<String> drops = dbUtil.generateDropColumnSchema(schema);
			for(String drop : drops) {
				logger.warn("Would drop (" + m + "): " + drop);
				all.add(drop);
			}
		}
		logger.warn("dropColumns enumeration: " + all.size() + " statement(s) across " + tables + " table(s) of " + models
			+ " persisted model(s) on " + dbUrl());

		ModelSchema sd = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		assertTrue("olio.sd.config must have a table on this database for the pin to mean anything", dbUtil.haveTable(sd.getName()));
		List<String> orphans = dbUtil.getOrphanedColumns(sd).stream().map(String::toLowerCase).collect(Collectors.toList());
		logger.warn("olio.sd.config orphaned columns: " + orphans);
		List<String> removedLower = REMOVED.stream().map(String::toLowerCase).collect(Collectors.toList());
		for(String o : orphans) {
			assertTrue("olio.sd.config carries an orphan that P10-4 did not create: " + o, removedLower.contains(o));
		}
		assertTrue("olio.sd.config orphans are either all six or none (a table created after P10-4): " + orphans,
			orphans.isEmpty() || orphans.size() == REMOVED.size());

		String table = dbUtil.getTableName(sd.getName());
		for(String stmt : all) {
			if(stmt.contains(table)) {
				assertTrue("every olio.sd.config drop names one of the six: " + stmt,
					removedLower.stream().anyMatch(r -> stmt.toLowerCase().contains(" " + r + ";")));
			}
		}
	}
}
