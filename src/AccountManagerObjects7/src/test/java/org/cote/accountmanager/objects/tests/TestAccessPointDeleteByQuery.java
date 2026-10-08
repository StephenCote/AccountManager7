package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.FieldLockUtil;
import org.junit.Test;

/**
 * {@code AccessPoint.delete(contextUser, Query)} (the former "Not implemented" / "TODO: Check locks based
 * on query" stub) and the delete-side lock semantics. Live DB, two non-admin users in one org; nothing here
 * uses the org admin for anything but creating the users.
 *
 * <ul>
 *   <li>owner deletes everything a query matches -> true, count 0;</li>
 *   <li>a different user's delete-by-query over the owner's group -> false and NOTHING is deleted;</li>
 *   <li>one enabled field lock on any matched record denies the WHOLE batch (all-or-nothing); unlocking
 *       lets the same query through;</li>
 *   <li>a query matching nothing -> false (INVALID, not a silent true);</li>
 *   <li>by-record delete of an identity-only record is denied while any lock exists on it (REST DELETE
 *       projects identity fields only, none of which can be locked, so the present-fields check alone
 *       would never fire).</li>
 * </ul>
 */
public class TestAccessPointDeleteByQuery extends BaseTest {

	private BaseRecord newData(BaseRecord owner, String groupPath, String name) throws FactoryException {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);
		BaseRecord created = ioContext.getAccessPoint().create(owner, data);
		assertNotNull("Data was not created: " + name, created);
		return created;
	}

	private Query groupQuery(BaseRecord user, long groupId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setCache(false);
		return q;
	}

	private int countInGroup(BaseRecord user, long groupId) {
		return ioContext.getAccessPoint().count(user, groupQuery(user, groupId));
	}

	@Test
	public void TestDeleteByQuery() throws Exception {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Delete By Query");
		Factory mf = ioContext.getFactory();
		BaseRecord owner = mf.getCreateUser(testOrgContext.getAdminUser(), "dbqOwner", testOrgContext.getOrganizationId());
		BaseRecord other = mf.getCreateUser(testOrgContext.getAdminUser(), "dbqOther", testOrgContext.getOrganizationId());
		assertNotNull(owner);
		assertNotNull(other);

		String groupPath = "~/DeleteByQuery/" + UUID.randomUUID().toString();
		BaseRecord group = ioContext.getAccessPoint().make(owner, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString());
		assertNotNull("Group is null", group);
		long groupId = group.get(FieldNames.FIELD_ID);

		for (int i = 0; i < 3; i++) {
			newData(owner, groupPath, "dbq-" + i + "-" + UUID.randomUUID());
		}
		assertEquals(3, countInGroup(owner, groupId));

		/// Another user in the same org cannot delete by query what they cannot delete by record.
		boolean otherDeleted = ioContext.getAccessPoint().delete(other, groupQuery(other, groupId));
		assertFalse("Non-owner delete-by-query must be refused", otherDeleted);
		assertEquals("Non-owner delete-by-query must delete nothing", 3, countInGroup(owner, groupId));

		/// The owner deletes all three.
		boolean deleted = ioContext.getAccessPoint().delete(owner, groupQuery(owner, groupId));
		assertTrue("Owner delete-by-query should succeed", deleted);
		assertEquals(ResponseEnumType.PERMIT, AuditUtil.getLastAuditResponse());
		assertEquals(0, countInGroup(owner, groupId));

		/// Nothing matches now: not a silent success.
		boolean empty = ioContext.getAccessPoint().delete(owner, groupQuery(owner, groupId));
		assertFalse("Delete-by-query over zero matches must report false", empty);
		assertEquals(ResponseEnumType.INVALID, AuditUtil.getLastAuditResponse());
	}

	@Test
	public void TestLockedRecordDeniesWholeBatch() throws Exception {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Delete By Query");
		Factory mf = ioContext.getFactory();
		BaseRecord owner = mf.getCreateUser(testOrgContext.getAdminUser(), "dbqOwner", testOrgContext.getOrganizationId());
		assertNotNull(owner);

		String groupPath = "~/DeleteByQuery/Locked " + UUID.randomUUID().toString();
		BaseRecord group = ioContext.getAccessPoint().make(owner, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString());
		long groupId = group.get(FieldNames.FIELD_ID);

		BaseRecord free = newData(owner, groupPath, "dbq-free-" + UUID.randomUUID());
		BaseRecord locked = newData(owner, groupPath, "dbq-locked-" + UUID.randomUUID());
		assertTrue("Lock on description", FieldLockUtil.lockField(owner, locked, FieldNames.FIELD_DESCRIPTION));
		assertEquals(2, countInGroup(owner, groupId));

		boolean deleted = ioContext.getAccessPoint().delete(owner, groupQuery(owner, groupId));
		assertFalse("A locked match must deny the whole delete-by-query", deleted);
		assertEquals(ResponseEnumType.DENY, AuditUtil.getLastAuditResponse());
		assertEquals("All-or-nothing: the unlocked record must survive too", 2, countInGroup(owner, groupId));

		/// By-record delete of an identity-only projection is denied while the lock exists, even though
		/// the projection does not carry the locked field.
		BaseRecord identityOnly = locked.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		assertFalse("Identity-only delete must be denied while a lock exists", ioContext.getAccessPoint().delete(owner, identityOnly));
		assertEquals(2, countInGroup(owner, groupId));

		assertTrue("Unlock", FieldLockUtil.unlockField(owner, locked, FieldNames.FIELD_DESCRIPTION));
		boolean deletedAfterUnlock = ioContext.getAccessPoint().delete(owner, groupQuery(owner, groupId));
		assertTrue("Same query succeeds once the lock is cleared", deletedAfterUnlock);
		assertEquals(0, countInGroup(owner, groupId));
		assertNotNull(free);
	}
}
