package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Test;

public class TestH2Database extends BaseTest {

	@Test
	public void TestQuerySetup() {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_KEY);
		logger.info(q.toSelect());
	}
	
	@Test
	public void TestData() {
		logger.info("Test data");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataName = "Test Data - " + UUID.randomUUID().toString();
		String dataData = "This is the data";
		String groupPath = "~/Data";
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		BaseRecord data = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			ByteModelUtil.setValueString(data, dataData);
			data = ioContext.getAccessPoint().create(testUser1, data);
			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	@Test
	public void TestKeySet() {
		try {
		OrganizationContext octx = this.getTestOrganization("/QA/TestOrg");
		assertNotNull("Context is null", octx);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	/*
	@Test
	public void TestModelConstraint() {
		ModelSchema ms1 = RecordFactory.getSchema(ModelNames.MODEL_INDEX2);
		ModelSchema ms2 = RecordFactory.getSchema(ModelNames.MODEL_PARTICIPATION);
		assertTrue("Expected model to be constrained", ioContext.getDbUtil().isConstrained(ms1));
		assertFalse("Expected model to not be constrained", ioContext.getDbUtil().isConstrained(ms2));

	}

	@Test
	public void TestOrganizationContext() {
		String orgName = "New Org - " + UUID.randomUUID().toString();
		OrganizationContext org = ioContext.getOrganizationContext("/QA/" + orgName, OrganizationEnumType.DEVELOPMENT);
		if(!org.isInitialized()) {
			logger.info("Creating organization " + organizationPath);
			try {
				org.createOrganization();
			} catch (NullPointerException | SystemException e) {
				logger.error(e);
				
			}
			logger.info("Org Init: " + org.isInitialized());
			//assertTrue("Expected org to be initialized", org.isInitialized());
		}
	}

	@Test
	public void TestPathConstruct() {
		ioContext.setEnforceAuthorization(false);			
		BaseRecord org = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_ORGANIZATION, "/QA", OrganizationEnumType.DEVELOPMENT.toString(), 0);
		assertNotNull("Org is null", org);
		assertNotNull("Expected a path to be set", org.get(FieldNames.FIELD_PATH));
		long orgId = org.get(FieldNames.FIELD_ID);

		//BaseRecord testUser1 = ioContext.
		String rname = "Role " + UUID.randomUUID().toString();
		String gname = "Group " + UUID.randomUUID().toString();



		try {
			BaseRecord role = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_ROLE, "/Roles/" + rname, RoleEnumType.PERSON.toString(), orgId);
			assertNotNull("Role is null", role);
			assertNotNull("Role does not have an objectId", role.get(FieldNames.FIELD_OBJECT_ID));
			logger.info("ROLE ***");
			logger.info(role.toString());
			
			
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		ioContext.setEnforceAuthorization(true);

	}
	

	@Test
	public void TestMembership() {
		
		String rname = "Role " + UUID.randomUUID().toString();
		String pname = "Person " + UUID.randomUUID().toString();
		String pername = "Permission " + UUID.randomUUID().toString();
		String uname = "User " + UUID.randomUUID().toString();
		ioContext.setEnforceAuthorization(false);
		BaseRecord org = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_ORGANIZATION, "/QA", OrganizationEnumType.DEVELOPMENT.toString(), 0);
		assertNotNull("Org is null", org);
		long orgId = org.get(FieldNames.FIELD_ID);
		//logger.info(org.toString());
		assertNotNull("Expected a path to be set", org.get(FieldNames.FIELD_PATH));
		try {
			BaseRecord person = RecordFactory.newInstance(ModelNames.MODEL_PERSON);

			BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
			BaseRecord role = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_ROLE, "/Roles/" + rname, RoleEnumType.PERSON.toString(), orgId);
			assertNotNull("Role is null", role);
			logger.info("ROLE ***");
			logger.info(role.toString());

			user.set(FieldNames.FIELD_NAME, uname);
			user.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			person.set(FieldNames.FIELD_NAME, pname);
			person.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			assertTrue("Expected to create record", ioContext.getRecordUtil().createRecord(person));
			BaseRecord iperson = ioContext.getRecordUtil().getRecordById(null, ModelNames.MODEL_PERSON, person.get(FieldNames.FIELD_ID));
			assertNotNull("Expected person to be found", iperson);
			assertTrue("Expected to create record", ioContext.getRecordUtil().createRecord(user));
			
			boolean member = ioContext.getMemberUtil().member(user, role, iperson, null, true);
			assertTrue("Expected to establish membership", member);
			
			List<BaseRecord> members = ioContext.getMemberUtil().getMembers(role, null, ModelNames.MODEL_PERSON);
			assertTrue("Expected member count to be 1, instead received " + members.size(), members.size() == 1);
			
			boolean isMember = ioContext.getMemberUtil().isMember(iperson, role, null);
			assertTrue("Expected person to be in the role", isMember);
			
			BaseRecord permission = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_PERMISSION, "/Permissions/" + pername + "/Read", PermissionEnumType.DATA.toString(), orgId);
			assertNotNull("Permission is null", permission);
			
			BaseRecord role2 = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_ROLE, "/Roles/" + rname + "/Read", RoleEnumType.PERSON.toString(), orgId);
			assertNotNull("Role is null", role2);

			ioContext.getMemberUtil().member(user, user, person, permission, true);
			
			List<BaseRecord> members2 = ioContext.getMemberUtil().findMembers(user, FieldNames.FIELD_ID, ModelNames.MODEL_PERSON, permission.get(FieldNames.FIELD_ID));
			logger.info("Count: " + members2.size());
			
			boolean authorized = ioContext.getAuthorizationUtil().checkEntitlement(person, permission, user);
			logger.info("Authorized: " + authorized);
			
			CacheUtil.clearCache(iperson);
			
		} catch (FieldException | ModelNotFoundException | ValueException | IndexException | ReaderException  e) {
			logger.error(e);
			e.printStackTrace();
		}
		ioContext.setEnforceAuthorization(true);
	

	}
	


	@Test
	public void TestForeignSchema() {
		String name = "User " + UUID.randomUUID().toString();
		String pname = "Person " + UUID.randomUUID().toString();
		try {
			BaseRecord person = RecordFactory.newInstance(ModelNames.MODEL_PERSON);
			BaseRecord user = RecordFactory.newInstance(ModelNames.MODEL_USER);
			user.set(FieldNames.FIELD_NAME, name);
			
			person.set(FieldNames.FIELD_NAME, pname);
			assertTrue("Expected to create record", ioContext.getRecordUtil().createRecord(user));
			assertTrue("Expected to create record", ioContext.getRecordUtil().createRecord(person));
			BaseRecord iuser = ioContext.getRecordUtil().getRecordById(null, ModelNames.MODEL_USER, user.get(FieldNames.FIELD_ID));
			assertNotNull("User is null", iuser);
			BaseRecord iperson = ioContext.getRecordUtil().getRecordById(null, ModelNames.MODEL_PERSON, person.get(FieldNames.FIELD_ID));
			assertNotNull("Person is null", iperson);

		
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	

	@Test
	public void TestPath() {
		logger.info("Testing pathing");
		
		String groupName = "Group Test - " + UUID.randomUUID().toString();
		ioContext.setEnforceAuthorization(false);
		BaseRecord group = ioContext.getPathUtil().makePath(null, ModelNames.MODEL_GROUP, "/Demo/Group/" + groupName, "DATA", 0L);
		assertNotNull("Group is null", group);
		ioContext.setEnforceAuthorization(true);
	}
	
	@Test
	public void TestSchemaSetup() {
		logger.info("Testing database schema setup");
		
		BaseRecord rec = null;
		String name = "Demo Data - " + UUID.randomUUID().toString();
		try{
			rec = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			rec.set(FieldNames.FIELD_NAME, name);
			rec.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			rec.set(FieldNames.FIELD_BYTE_STORE, "Example data is here".getBytes());
			
			boolean created = ioContext.getRecordUtil().createRecord(rec);
			assertTrue("Expected data to be created", created);
			logger.info("Created: " + created);
			
			//BaseRecord irec = ioContext.getRecordUtil().getRecordById(null, ModelNames.MODEL_DATA, rec.get(FieldNames.FIELD_ID));
			Query query = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, rec.get(FieldNames.FIELD_ID));
			query.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_BYTE_STORE});
			
			BaseRecord irec = ioContext.getSearch().findRecord(query);
			assertNotNull("Expected a result", irec);
			
			query = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, rec.get(FieldNames.FIELD_ID));
			
			irec = ioContext.getSearch().findRecord(query);
			assertNotNull("Expected a result", irec);

			BaseRecord urec = irec.copyRecord(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_DESCRIPTION});
			urec.set(FieldNames.FIELD_DESCRIPTION, "Example description");
			boolean updated = ioContext.getRecordUtil().updateRecord(urec);
			logger.info("Updated: " + updated);

			
		}
		catch(NullPointerException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}

	}

	@Test
	public void TestSchemaWithFlexList() {
		logger.info("Testing database schema setup");
		
		BaseRecord rec = null;
		String name = "Demo Data with Attributes - " + UUID.randomUUID().toString();
		try{
			rec = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			rec.set(FieldNames.FIELD_NAME, name);
			rec.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			rec.set(FieldNames.FIELD_DESCRIPTION, "This is the example data with some attributes");
			rec.set(FieldNames.FIELD_BYTE_STORE, "Example data is here".getBytes());
			
			rec.addAttribute("Attribute 1", true);
			rec.addAttribute("Attribute 2", "Test");
			
		
			boolean created = ioContext.getRecordUtil().createRecord(rec);
			assertTrue("Expected data to be created", created);
			logger.info("Created: " + created);
			
			Query query = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, rec.get(FieldNames.FIELD_ID));
			
			BaseRecord irec = ioContext.getSearch().findRecord(query);
			assertNotNull("Expected a result", irec);
		}
		catch(NullPointerException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		

	}
	*/
	
}
