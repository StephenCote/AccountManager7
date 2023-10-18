package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.util.Strings;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.FieldLockUtil;
import org.junit.Test;

public class TestFieldLock extends BaseTest {

	@Test
	public void TestFieldLock() {
		logger.info("Test Field Lock");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Field Lock");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testUser2 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		
		logger.info("Cleaning up existing locks");
		FieldLockUtil.clearFieldLocks(testUser1, ModelNames.MODEL_DATA, 0L, null);
		
		String groupPath = "~/Locks/Data";
		String dataName = "Lock Data Test - " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", dataName);
		BaseRecord data = null;
		try {
			logger.info("Create new data instance");
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			data = ioContext.getAccessPoint().create(testUser1, data);
		}
		catch(FactoryException e) {
			logger.error(e);
		}
		assertNotNull("Data is null", data);

		logger.info("Create lock on data.name");
		boolean locked = FieldLockUtil.lockField(testUser1, data, FieldNames.FIELD_NAME);
		FieldLockUtil.lockField(testUser1, data, FieldNames.FIELD_DESCRIPTION);
		assertTrue("Expected field to be locked", locked);
		logger.info("Check if data.name is locked");
		assertTrue("Expected the utility to indicate the field is locked", FieldLockUtil.isFieldLocked(testUser1, data, FieldNames.FIELD_NAME));
		assertTrue("Expected the utility to indicate the field is locked for a different user", FieldLockUtil.isFieldLocked(testUser2, data, FieldNames.FIELD_NAME));

		List<String> locks = FieldLockUtil.getFieldLocks(testUser2, data);
		assertTrue("Different user should be able to see field locks", locks.size() >= 2);
		assertTrue("Expected locks to contain the locked field", locks.contains(FieldNames.FIELD_NAME));
		logger.info("Locks: " + Strings.join(locks, ','));
		
		
		BaseRecord patchData = data.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_BYTE_STORE});
		try {
			patchData.set(FieldNames.FIELD_BYTE_STORE, "Example data".getBytes());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		BaseRecord checkData = ioContext.getAccessPoint().update(testUser1, patchData);
		assertNotNull("Expected to be able to patche an unlocked field", checkData);
		
		logger.info(checkData.toFullString());
		
		boolean unlocked = FieldLockUtil.unlockField(testUser2, data, FieldNames.FIELD_NAME);
		assertFalse("Expected different user to fail unlocking the field", unlocked);
		unlocked = FieldLockUtil.unlockField(testUser1, data, FieldNames.FIELD_NAME);
		assertTrue("Expected field to be unlocked", unlocked);
	}
	
}
