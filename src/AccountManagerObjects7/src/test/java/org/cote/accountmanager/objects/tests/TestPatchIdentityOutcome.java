package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/**
 * Core REST audit item 3-2 (2026-10-07, ISO lane "B-PATCH-ID"): {@code PATCH /rest/model} with
 * {@code schema} + {@code objectId} (no numeric {@code id}) was reported to answer {@code 200 false}
 * silently. This pins down the Objects7 side:
 * <ul>
 *   <li>an objectId-only patch IS an identity record and IS applied by {@code AccessPoint.update};</li>
 *   <li>when an update is not applied, {@code AuditUtil.getLastAudit*()} exposes the audit outcome
 *       (DENY vs. INVALID plus message) so the transport layer can answer 403/422 with a reason
 *       instead of a bare {@code false}.</li>
 * </ul>
 * Runs as NON-admin users; every verifying read is {@code cache(false)}.
 */
public class TestPatchIdentityOutcome extends BaseTest {

	private BaseRecord createNote(BaseRecord user, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/PatchOutcome");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord n = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		n.set("text", text);
		BaseRecord created = ioContext.getAccessPoint().create(user, n);
		assertNotNull("note CREATE returned null", created);
		return created;
	}

	private BaseRecord reread(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		q.setCache(false);
		return ioContext.getAccessPoint().find(user, q);
	}

	@Test
	public void testObjectIdOnlyPatchIsAppliedAndAuditsPermit() throws Exception {
		BaseRecord user = getCreateUser("patchOutcomeUser1");
		String name = "po-note-" + UUID.randomUUID();
		BaseRecord created = createNote(user, name, "before");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		/// Exactly what the REST body carries: schema + objectId + changed field (+ validated name).
		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_NOTE,
				new String[] { FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, name);
		patch.set("text", "after-objectId-only");
		assertTrue("objectId alone must count as identity", RecordUtil.isIdentityRecord(patch));

		AuditUtil.clearLastAudit();
		BaseRecord updated = ioContext.getAccessPoint().update(user, patch);
		assertNotNull("objectId-only PATCH must be applied", updated);
		assertEquals(ResponseEnumType.PERMIT, AuditUtil.getLastAuditResponse());

		BaseRecord fresh = reread(user, oid);
		assertNotNull(fresh);
		assertEquals("after-objectId-only", fresh.get("text"));
	}

	@Test
	public void testOtherUsersPatchIsDeniedWithAuditReason() throws Exception {
		BaseRecord owner = getCreateUser("patchOutcomeOwner2");
		BaseRecord other = getCreateUser("patchOutcomeOther2");
		String name = "po-note-" + UUID.randomUUID();
		BaseRecord created = createNote(owner, name, "owned");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_NOTE,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		patch.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, name);
		patch.set("text", "hijacked");

		AuditUtil.clearLastAudit();
		BaseRecord updated = ioContext.getAccessPoint().update(other, patch);
		assertNull("another user must not be able to patch the note", updated);
		assertEquals("a policy failure must surface as DENY", ResponseEnumType.DENY, AuditUtil.getLastAuditResponse());

		BaseRecord fresh = reread(owner, oid);
		assertNotNull(fresh);
		assertEquals("owned", fresh.get("text"));
	}

	@Test
	public void testValidationFailureIsInvalidWithAuditReason() throws Exception {
		BaseRecord user = getCreateUser("patchOutcomeUser3");
		String name = "po-note-" + UUID.randomUUID();
		BaseRecord created = createNote(user, name, "valid");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		/// Blank name violates common.name's not-empty rule; the writer validates the patch itself.
		BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_NOTE,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		patch.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid);
		patch.set(FieldNames.FIELD_NAME, "");
		patch.set("text", "should-not-land");

		AuditUtil.clearLastAudit();
		BaseRecord updated = ioContext.getAccessPoint().update(user, patch);
		assertNull("blank-name PATCH must be rejected", updated);
		assertEquals("a writer/validation rejection must surface as INVALID", ResponseEnumType.INVALID, AuditUtil.getLastAuditResponse());
		String msg = AuditUtil.getLastAuditMessage();
		assertNotNull("audit must carry a reason", msg);
		assertTrue("reason should name the failed modify: " + msg, msg.toLowerCase().contains("modify"));

		BaseRecord fresh = reread(user, oid);
		assertNotNull(fresh);
		assertEquals("valid", fresh.get("text"));
	}
}
