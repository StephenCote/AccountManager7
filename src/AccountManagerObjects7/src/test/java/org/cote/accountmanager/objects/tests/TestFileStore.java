package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

public class TestFileStore extends BaseTest {
	
	
	@Test
	public void TestFileStore7z() {
		/// use file store, and don't follow foreign keys
		String storeName = "testStore-A01.7z";
		resetIO(storeName);
		OrganizationContext testOrgContext = getTestOrganization("/Development/FileStore");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = null;
		boolean member = false;
		try 
		{
			testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		try {
			
			BaseRecord role1 = ioContext.getPathUtil().findPath(testOrgContext.getAdminUser(), ModelNames.MODEL_ROLE, "/" +  AccessSchema.ROLE_ACCOUNT_USERS, "USER",testOrgContext.getOrganizationId());
			member = ioContext.getMemberUtil().isMember(testUser1, role1, null);
			if(!member) {
				member = ioContext.getMemberUtil().member(testUser1, role1, testUser1, null, true);
			}
		}
		catch(Exception e) {
			
		}
		assertTrue("Expected membership", member);
	}
	
	@Test
	public void TestIndexCheck() {
		try {
			String storeName = "testStore-A01.7z";
			resetIO(storeName);
		}
		catch(Exception e) {
			
		}
		assertNotNull("IO Context is null", ioContext);
		assertTrue("Context not initialized", ioContext.isInitialized());
	}
	
	@Test
	public void TestPartitionStore() {
		/// use loose store, and don't follow foreign keys
		resetIO(null);
		OrganizationContext testOrgContext = getTestOrganization("/Development/FileStore");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = null;
		boolean member = false;
		try 
		{
			testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		try {
			
			BaseRecord role1 = ioContext.getPathUtil().findPath(testOrgContext.getAdminUser(), ModelNames.MODEL_ROLE, "/" +  AccessSchema.ROLE_ACCOUNT_USERS, "USER", testOrgContext.getOrganizationId());
			assertNotNull("Role is null", role1);
		}
		catch(Exception e) {
			
		}
	}
	
	/*
	@Test
	public void TestFileStructure() {
		/// use raw file system, and don't follow foreign keys
		///
		resetIO(null);
		BaseRecord role1 = pathUtil.makePath(testUser1, ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_ACCOUNT_USERS, "USER", devOrganization.get("id"));
		BaseRecord perm1 = pathUtil.makePath(testUser1, ModelNames.MODEL_PERMISSION, "/Read", "USER", devOrganization.get("id"));
		try {
			BaseRecord part1 = RecordFactory.model("participation").newInstance();
			part1.set("participationId", role1.get("id"));
			part1.set("participationModel", ModelNames.MODEL_ROLE);
			part1.set("participantId", testUser1.get("id"));
			part1.set("participantModel", testUser1.getModel());
			part1.set("enabled",  true);
			part1.set("organizationId", testUser1.get("organizationId"));
			recordUtil.createRecord(part1);
		} catch (MissingFormatArgumentException | NullPointerException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			
		}
		assertNotNull("Permission is null", perm1);
		logger.info("Emitting default org values");
		emitPermissions(devOrganization, adminUser);
		emitRoles(devOrganization, adminUser);
		logger.info("Setting up memberships");
	}
	*/
	
	
}
