package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;

import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.factory.UserWriter;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.UserStatusEnumType;
import org.junit.Test;

/// Pins the UserWriter contract: system.user's "io" block routes RecordUtil.createRecord / updateRecord
/// through it. New users go to Factory.getCreateUser; everything else must reach the system writer, and
/// the operations that never arrive here (stream writes, deletes) must say so instead of returning false.
public class TestUserWriterIO extends BaseTest {

	private static BaseRecord readStatus(long userId, long organizationId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_ID, userId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ComparatorEnumType.EQUALS, organizationId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_STATUS });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	@Test
	public void TestUpdateDispatchedThroughUserWriterReachesTheSystemWriter() throws Exception {
		OrganizationContext oc = getTestOrganization("/Development/UserWriter");
		Factory mf = ioContext.getFactory();
		BaseRecord user = mf.getCreateUser(oc.getAdminUser(), "uwioUser1", oc.getOrganizationId());
		assertNotNull(user);
		long userId = user.get(FieldNames.FIELD_ID);
		long orgId = oc.getOrganizationId();

		BaseRecord before = readStatus(userId, orgId);
		assertNotNull(before);
		Object originalStatus = before.get(FieldNames.FIELD_STATUS);
		String original = (originalStatus == null ? UserStatusEnumType.UNKNOWN.toString() : originalStatus.toString());
		UserStatusEnumType target = (UserStatusEnumType.RESTRICTED.toString().equals(original)
			? UserStatusEnumType.NORMAL : UserStatusEnumType.RESTRICTED);

		/// updateRecord -> createRecord(rec, flush=true) -> UserWriter (custom io) -> identity record ->
		/// system writer. The patch carries identity + validated name + the changed field only.
		BaseRecord patch = before.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_STATUS });
		patch.set(FieldNames.FIELD_STATUS, target.toString());
		assertTrue("The dispatched update must succeed", ioContext.getRecordUtil().updateRecord(patch));

		/// readStatus filters on organizationId: a null here means the patch was written back with
		/// organizationId = 0 (the pre-2026-10-07 UserWriter resolved the org context by get(), which
		/// materialized the missing field at its default and the system writer persisted it).
		BaseRecord after = readStatus(userId, orgId);
		assertNotNull("The user must still belong to its organization after a patch that omitted organizationId", after);
		assertEquals("The status change must have been persisted by the system writer",
			target.toString(), String.valueOf((Object) after.get(FieldNames.FIELD_STATUS)));

		/// Put it back the same way.
		BaseRecord restore = after.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_STATUS });
		restore.set(FieldNames.FIELD_STATUS, original);
		assertTrue(ioContext.getRecordUtil().updateRecord(restore));
		assertEquals(original, String.valueOf((Object) readStatus(userId, orgId).get(FieldNames.FIELD_STATUS)));
	}

	@Test
	public void TestUserWriterContract() throws Exception {
		UserWriter writer = new UserWriter();
		assertEquals("The user writer stores nothing itself; it reports the system writer's IO",
			ioContext.getWriter().getRecordIo(), writer.getRecordIo());

		OrganizationContext oc = getTestOrganization("/Development/UserWriter");
		BaseRecord user = ioContext.getFactory().getCreateUser(oc.getAdminUser(), "uwioUser2", oc.getOrganizationId());
		assertNotNull(user);

		assertThrowsWriter(() -> writer.write(new BaseRecord[] { user }));
		assertThrowsWriter(() -> writer.write(user, new ByteArrayOutputStream()));
		assertThrowsWriter(() -> writer.delete(user));
		assertThrowsWriter(() -> writer.delete(user, new ByteArrayOutputStream()));
		assertThrowsWriter(() -> writer.delete(QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_NAME, "uwioUser2")));

		/// Translate and flush delegate; close must not touch the context writer. None of them throw.
		writer.translate(org.cote.accountmanager.record.RecordOperation.INSPECT, user);
		writer.flush();
		writer.close();
		assertNotNull("The context writer must survive a UserWriter.close()", ioContext.getWriter());
	}

	@FunctionalInterface
	private interface WriterCall {
		void call() throws WriterException;
	}

	private static void assertThrowsWriter(WriterCall call) {
		try {
			call.call();
		} catch (WriterException e) {
			assertNotNull("The refusal must say why", e.getMessage());
			return;
		}
		throw new AssertionError("Expected a WriterException naming why the operation is not supported");
	}
}
