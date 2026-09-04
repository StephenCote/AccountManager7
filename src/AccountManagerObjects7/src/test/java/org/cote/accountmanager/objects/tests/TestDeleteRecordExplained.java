package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/**
 * FIX 1 coverage for {@link PictureBookUtil#deleteRecordExplained(BaseRecord, BaseRecord)} — the
 * shared helper that ChapBook delete, {@code deleteIncompleteBookAsOlio}, and {@code reset} all route
 * their terminal deletes through so a failure carries a concrete, PBAC-derived reason instead of a bare
 * "Failed to delete".
 * <p>
 * The contract under test:
 * <ul>
 *   <li><b>PERMIT</b> (the owner deletes their own group-scoped record): the helper returns
 *       {@code deleted=true}, {@code authorized=true}, {@code reason=null}, and the row is actually gone.</li>
 *   <li><b>DENY</b> (a different user with no grants names the same record): the helper returns
 *       {@code deleted=false}, {@code authorized=false}, and a non-empty {@code reason} that identifies
 *       it as a PBAC denial — and the row SURVIVES (no privilege escalation, no silent no-op).</li>
 * </ul>
 * Two ordinary non-admin users in one organization; a group-scoped {@code data.data} owned by the first
 * carries a {@code groupId} the second is not a member of, so the dynamic authorization checker denies
 * the second user's delete. No admin actor, no schema reset, no LLM/SD — pure DB.
 */
public class TestDeleteRecordExplained extends BaseTest {

	/** Reuse an already-initialized org so no multi-minute universe seed runs. */
	private static final String ORG = "/Development/DeleteExplained";

	private OrganizationContext org() {
		return getTestOrganization(ORG);
	}

	/** Direct, uncached, PBAC-bypassing existence probe (verification only — never the code under test). */
	private BaseRecord probe(String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/**
	 * PERMIT: the owner of a group-scoped record can delete it, and the helper reports a clean success.
	 */
	@Test
	public void deleteRecordExplained_ownerPermit_deletesAndReportsSuccess() throws Exception {
		OrganizationContext o = org();
		BaseRecord owner = getCreateUser("delExplainedOwner", o);
		assertNotNull("Owner test user must resolve", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		String name = "delExplainedPermit-" + System.currentTimeMillis();
		BaseRecord rec = getCreateData(owner, name, "~/Data", "permit-case-body");
		assertNotNull("Deletable record must be created", rec);
		String oid = rec.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Record must have an objectId", oid);
		assertNotNull("Record must exist before delete", probe(oid, orgId));

		PictureBookUtil.DeleteResult result = PictureBookUtil.deleteRecordExplained(owner, rec);
		assertNotNull("deleteRecordExplained must return a DeleteResult", result);
		assertTrue("Owner delete must be authorized (PERMIT)", result.authorized);
		assertTrue("Owner delete must report deleted=true; reason=" + result.reason, result.deleted);
		assertNull("A successful delete must carry no reason", result.reason);

		assertNull("The record must actually be gone after a PERMIT delete", probe(oid, orgId));

		logger.info("deleteRecordExplained_ownerPermit PASSED: deleted={} authorized={} reason={}",
			result.deleted, result.authorized, result.reason);
	}

	/**
	 * DENY: a second user with no grants on the owner's group cannot delete the owner's record. The
	 * helper must report deleted=false, authorized=false, a non-empty PBAC reason, and leave the row.
	 */
	@Test
	public void deleteRecordExplained_nonOwnerDenied_reportsReasonAndKeepsRow() throws Exception {
		OrganizationContext o = org();
		BaseRecord owner = getCreateUser("delExplainedOwner", o);
		BaseRecord stranger = getCreateUser("delExplainedStranger", o);
		assertNotNull("Owner test user must resolve", owner);
		assertNotNull("Stranger test user must resolve", stranger);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		String name = "delExplainedDeny-" + System.currentTimeMillis();
		BaseRecord rec = getCreateData(owner, name, "~/Data", "deny-case-body");
		assertNotNull("Owner's record must be created", rec);
		String oid = rec.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Record must have an objectId", oid);
		assertNotNull("Record must exist before the denied delete", probe(oid, orgId));

		PictureBookUtil.DeleteResult result = PictureBookUtil.deleteRecordExplained(stranger, rec);
		assertNotNull("deleteRecordExplained must return a DeleteResult", result);
		assertFalse("A non-owner with no grants must be denied (authorized=false)", result.authorized);
		assertFalse("A denied delete must report deleted=false", result.deleted);
		assertNotNull("A denied delete must carry a concrete reason", result.reason);
		assertFalse("The reason must not be blank", result.reason.trim().isEmpty());
		assertTrue("The reason must identify the PBAC denial; got: " + result.reason,
			result.reason.startsWith("PBAC denied delete of"));

		assertNotNull("The record must SURVIVE a denied delete (no escalation, no silent no-op)",
			probe(oid, orgId));

		logger.info("deleteRecordExplained_nonOwnerDenied PASSED: deleted={} authorized={} reason={}",
			result.deleted, result.authorized, result.reason);
	}
}
