package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/// Direct, DB-free contract tests for RecordUtil.matchIdentityRecords(BaseRecord, BaseRecord).
///
/// matchIdentityRecords was null-guarded to (a) stop NPEing when an identity field is
/// present-but-null (the id-only PATCH record case that broke the portrait link) and
/// (b) stop false-matching two unpersisted records that both carry id==0. It must mirror
/// matchIdentityRecordsByIdx:
///   - id branch matches only when BOTH ids are non-null AND > 0
///   - objectId / urn branches null-check BOTH sides before .equals
///
/// auth.group inherits id (system.primaryKey), objectId (common.objectId) and urn
/// (common.urn) — all flagged identity:true — so it exercises every branch. This test
/// deliberately does NOT extend BaseTest: matchIdentityRecords is pure record comparison
/// and RecordFactory loads model schemas from the classpath without any I/O context
/// (see TestValueType for the same pattern).
public class TestRecordUtil {

	public static final Logger logger = LogManager.getLogger(TestRecordUtil.class);
	private static final String MODEL = "auth.group";

	/// Fresh record with all three identity fields present-but-null. This mirrors a
	/// deserialized partial/PATCH record: the fields exist on the schema but hold no value.
	private BaseRecord newGroup() throws FieldException, ValueException, ModelNotFoundException {
		BaseRecord rec = RecordFactory.model(MODEL).newInstance();
		assertNotNull("Record was null", rec);
		rec.set(FieldNames.FIELD_ID, null);
		rec.set(FieldNames.FIELD_OBJECT_ID, null);
		rec.set(FieldNames.FIELD_URN, null);
		return rec;
	}

	/// Sanity: confirm the helper really produces a present-but-null id (not the default 0L),
	/// so the null-path tests below are genuinely exercising the null branch.
	@Test
	public void helper_idIsPresentButNull() throws Exception {
		BaseRecord a = newGroup();
		assertTrue("id field must be present on auth.group", a.hasField(FieldNames.FIELD_ID));
		assertNull("id must be present-but-null for the PATCH-shape tests", a.get(FieldNames.FIELD_ID));
	}

	/// Case 1: id-only match. Two records with the same positive id, objectId/urn null.
	/// Must return TRUE and NOT throw.
	@Test
	public void matchByIdOnly_samePositiveId_true() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_ID, 5L);
		b.set(FieldNames.FIELD_ID, 5L);
		assertTrue("Records with the same positive id should match", RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 2: id-only, different positive ids -> FALSE.
	@Test
	public void matchByIdOnly_differentIds_false() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_ID, 5L);
		b.set(FieldNames.FIELD_ID, 6L);
		assertFalse("Records with different ids must not match", RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 1b (the real portrait regression): id present-but-null on one side, a real id on the
	/// other, both carrying a matching objectId. The pre-fix code did (long)rec.get(id) which
	/// unboxed null -> NullPointerException. The fix must fall through to objectId: TRUE, no throw.
	@Test
	public void matchIdPresentButNull_fallsThroughToObjectId_noNpe() throws Exception {
		BaseRecord a = newGroup();          // id present-but-null (PATCH shape)
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_OBJECT_ID, "obj-shared");
		b.set(FieldNames.FIELD_ID, 8L);      // real id on the other side
		b.set(FieldNames.FIELD_OBJECT_ID, "obj-shared");
		assertTrue("A present-but-null id must fall through to a matching objectId (no NPE)",
			RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 3: two unpersisted records (id==0, all identity fields null) -> FALSE, no throw.
	/// Old code false-matched two id-bearing records because 0 == 0.
	@Test
	public void matchTwoUnpersisted_idZero_false() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_ID, 0L);
		b.set(FieldNames.FIELD_ID, 0L);
		assertFalse("Two unpersisted (id==0) records must not match", RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 4: objectId match with id unusable (null) -> TRUE via objectId fall-through.
	@Test
	public void matchByObjectId_sameObjectId_true() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_OBJECT_ID, "obj-shared-123");
		b.set(FieldNames.FIELD_OBJECT_ID, "obj-shared-123");
		assertTrue("Records with the same objectId (and no usable id) should match",
			RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 5: objectId differs, id unusable -> FALSE.
	@Test
	public void matchByObjectId_differentObjectId_false() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_OBJECT_ID, "obj-A");
		b.set(FieldNames.FIELD_OBJECT_ID, "obj-B");
		assertFalse("Records with different objectIds must not match", RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 6: null-safety. One record has objectId present-but-null (identity supplied via urn),
	/// the other has a real objectId (urn null). Must return FALSE and NOT throw — the exact
	/// unguarded (String)null .equals(...) crash the fix removed.
	@Test
	public void matchNullSafe_objectIdPresentButNull_false() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_URN, "urn-A");           // a is an identity record via urn
		a.set(FieldNames.FIELD_OBJECT_ID, null);         // ...but objectId is present-but-null
		b.set(FieldNames.FIELD_OBJECT_ID, "obj-real");   // b has a real objectId, urn null
		assertFalse("A null objectId on one side must not match (and must not throw)",
			RecordUtil.matchIdentityRecords(a, b));
	}

	/// Case 7: urn match with id/objectId unusable -> TRUE via urn fall-through.
	@Test
	public void matchByUrn_sameUrn_true() throws Exception {
		BaseRecord a = newGroup();
		BaseRecord b = newGroup();
		a.set(FieldNames.FIELD_URN, "urn-shared-xyz");
		b.set(FieldNames.FIELD_URN, "urn-shared-xyz");
		assertTrue("Records with the same urn (no usable id/objectId) should match",
			RecordUtil.matchIdentityRecords(a, b));
	}
}
