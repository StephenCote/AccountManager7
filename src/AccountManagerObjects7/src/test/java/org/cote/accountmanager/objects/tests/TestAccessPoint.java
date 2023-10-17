package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

public class TestAccessPoint extends BaseTest {

	private String getAccessToken(BaseRecord user) {
		String token = null;
		OrganizationContext orgC = IOSystem.getActiveContext().getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if(orgC != null && orgC.isInitialized()) {
			try {
				token = TokenService.createAuthorizationToken(orgC.getOpsUser(), user, null, null, "Demo token", 120);
			}
			catch(ReaderException | IndexException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		//TokenService.createJWTToken(user, user, organizationPath, 0)
		return token;
	}
	
	private BaseRecord getCreateCredential(BaseRecord user, String pwd) {
		BaseRecord cred = CredentialUtil.getLatestCredential(user);
		if(cred == null) {
			logger.info("Creating new credential");
			cred = CredentialUtil.newPasswordCredential(user, pwd);
			logger.info("Checking create new object policy authorization");
			PolicyResponseType prr = ioContext.getAuthorizationUtil().canCreate(user, user, cred);
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				logger.info("Creating credential object");
				cred = ioContext.getAccessPoint().create(user, cred);
			}
			else {
				logger.info("Not authorized to create new credential");
			}
		}
		else {
			logger.info("Returning latest credential");
		}
		return cred;
	}
	
	@Test
	public void TestPolicyExpansion() {
		logger.info("Test Policy Expansion");
		/// At the moment, the system resource policy will expand to include model level role assignments to accomodate situations where
		/// a data element is not compartmentalized within a parent or group that authorizes access
		/// E.G.: To be able to create an AccessRequest object, the model defines a role necessary to perform the create
		/// Afterwards, the ownership policy will pickup for the owner, or the other roles will be picked up for the specific type of operation
		/// Next, the policy needs to expand for foreign references, such that when reading or modifying a foreign field, the actor has authorization to read the foreign field
		/// This means the policy needs to optionally expand with an adjacent AND rule for each not-empty foreign field

		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String dataName = "Demo data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Authorize/Create");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		
		BaseRecord usersRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS, null, testOrgContext.getOrganizationId());
		
		PolicyResponseType canRead = ioContext.getAuthorizationUtil().canRead(testUser1, testUser1, testUser1.get(FieldNames.FIELD_HOME_DIRECTORY));
		logger.info("In Role? " + ioContext.getMemberUtil().isMember(testUser1, usersRole, null));
		logger.info("Can Read? " + (canRead.getType() == PolicyResponseEnumType.PERMIT));
		PolicyResponseType prr = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			assertNotNull("Data is null", data);

			PolicyType pol = ioContext.getPolicyUtil().getResourcePolicy(PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, testUser1, null, testUser1).toConcrete();
			
			prr = ioContext.getPolicyUtil().evaluateResourcePolicy(testUser1, PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, testUser1, testUser1);
		} catch (NullPointerException | FactoryException | ReaderException   e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		assertNotNull("PRR is null", prr);
		assertTrue("Expected a permit", prr.getType() == PolicyResponseEnumType.PERMIT);
		
		
	}
	
	@Test
	public void TestMemberEntitlement() {
		logger.info("*** Test Create Data");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		BaseRecord testUser3 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser3", testOrgContext.getOrganizationId());

		
		String dataName = "Demo data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Authorize/Create");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		
		BaseRecord cred = getCreateCredential(testUser1, "password");
		assertNotNull("Credential is null", cred);
		// logger.info(cred.toString());
		
		String token = getAccessToken(testUser1);
		logger.info("Token: " + token);
		try {
			PolicyType pol = ioContext.getPolicyUtil().getResourcePolicy(PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, testUser1, null, cred).toConcrete();
			// logger.info(pol.toString());
		} catch (ReaderException e) {
			logger.error(e);
			e.printStackTrace();
		}
		BaseRecord req = newAccessRequest(testUser1, testUser1, ActionEnumType.ADD, cred);
		PolicyResponseType prr = ioContext.getAuthorizationUtil().canCreate(testUser1, testUser1, req);
		// logger.info(prr.toString());
		// logger.info(req.toString());
		
		// ioContext.getPolicyUtil().getModelAccessPatternList(testUser1, SystemPermissionEnumType.CREATE, req);
	}

	
	/// For cases where a person wants to perform on operation on an object that is not covered by an existing policy,
	/// Create an access request that can be reviewed, approved, and signed, and then issue a token for that request which can be evaluated
	/// by a token access policy.
	///
	private BaseRecord newAccessRequest(BaseRecord owner, BaseRecord requester, ActionEnumType action, BaseRecord object) {
		OrganizationContext orgC = IOSystem.getActiveContext().getOrganizationContext(requester.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		Factory mf = ioContext.getFactory();
		BaseRecord req = null;
		try {
			req = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACCESS_REQUEST, owner, null, ParameterUtil.newParameterList(FieldNames.FIELD_ACTION, action), requester, object);
		} catch (FactoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return req;
	}
	
	@Test
	public void TestUserFactoryCRUD() {
		logger.info("*** Test User Factory Op");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();

		BaseRecord rec = null;
		try {
			rec = mf.newInstance(ModelNames.MODEL_USER, testOrgContext.getAdminUser());
			rec.set(FieldNames.FIELD_NAME, "Test User - " + UUID.randomUUID().toString());
			BaseRecord crec = ioContext.getAccessPoint().create(testOrgContext.getAdminUser(), rec);
			// logger.info("***** CHECK USER CRUD");
			assertNotNull("User rec is null", crec);
			crec = ioContext.getAccessPoint().find(testOrgContext.getAdminUser(), QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_NAME, crec.get(FieldNames.FIELD_NAME)));
			assertNotNull("Failed to retrieve user", crec);
			//logger.info(crec.toFullString());
			
			Query q = QueryUtil.buildQuery(testOrgContext.getAdminUser(), ModelNames.MODEL_USER, null, null, 0L, 0);
			BaseRecord chkMod = mf.newInstance(ModelNames.MODEL_USER);
			boolean checkRead = ioContext.getPolicyUtil().readPermitted(testOrgContext.getAdminUser(), testOrgContext.getAdminUser(), null, chkMod);
			assertTrue("Expected admin to be able to read any of user model type", checkRead);
			//checkRead = ioContext.getPolicyUtil().readPermitted(testOrgContext.getAdminUser(),rec, null, chkMod);
			PolicyResponseType[] prrs = ioContext.getPolicyUtil().evaluateQueryToReadPolicyResponses(testOrgContext.getAdminUser(), q);
			assertTrue("Expected at least one policy response", prrs.length > 0);
			logger.info(q.toFullString());
			int count = ioContext.getAccessPoint().count(testOrgContext.getAdminUser(), q);
			logger.info("Count: " + count);
			
			QueryResult qr = ioContext.getAccessPoint().list(testOrgContext.getAdminUser(), q);
			assertTrue("Expected to retrieve list of users", qr.getResults().length > 0);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
	}

	@Test
	public void TestObjectCRUD() {
		logger.info("*** Test Create Data");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser2);
		
		String dataName = "Demo data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Authorize/Create");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			assertNotNull("Data is null", data);
			logger.info("Attempting to create data with an unauthorized user");
			odata = ioContext.getAccessPoint().create(testUser2, data);
			assertNull("Expected attempt to add data to fail", odata);
			
			logger.info("Attempting to create data with an authorized user");
			odata = ioContext.getAccessPoint().create(testUser1, data);
			logger.info("Ident: " + RecordUtil.isIdentityRecord(odata));
			
			BaseRecord group = ioContext.getAccessPoint().findById(testUser1, ModelNames.MODEL_GROUP, odata.get(FieldNames.FIELD_GROUP_ID));
			assertNotNull("Group was null", group);
			BaseRecord ogroup = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_GROUP, group.get(FieldNames.FIELD_OBJECT_ID));
			assertNotNull("Group was null", ogroup);
			// logger.info(ogroup.toString());
			
			BaseRecord badObj = ioContext.getAccessPoint().findByNameInGroup(testUser2, ModelNames.MODEL_DATA, (long)odata.get(FieldNames.FIELD_GROUP_ID), dataName);
			BaseRecord goodObj = ioContext.getAccessPoint().findByNameInGroup(testUser1, ModelNames.MODEL_DATA, (long)odata.get(FieldNames.FIELD_GROUP_ID), dataName);
			
			assertNull("Expected obj to be null", badObj);
			assertNotNull("Expected obj", goodObj);
			
			BaseRecord badGroup = ioContext.getAccessPoint().findById(testUser2, ModelNames.MODEL_GROUP, (long)odata.get(FieldNames.FIELD_GROUP_ID));
			BaseRecord goodGroup = ioContext.getAccessPoint().findById(testUser1, ModelNames.MODEL_GROUP, (long)odata.get(FieldNames.FIELD_GROUP_ID));
			
			assertNull("Expected group to be null", badGroup);
			assertNotNull("Expected group", goodGroup);
			
			BaseRecord badObj2 = ioContext.getAccessPoint().findByNameInGroup(testUser2, ModelNames.MODEL_DATA, (String)goodGroup.get(FieldNames.FIELD_OBJECT_ID), dataName);
			BaseRecord goodObj2 = ioContext.getAccessPoint().findByNameInGroup(testUser1, ModelNames.MODEL_DATA, (String)goodGroup.get(FieldNames.FIELD_OBJECT_ID), dataName);
			
			assertNull("Expected obj to be null", badObj2);
			assertNotNull("Expected obj", goodObj2);

			
			odata.set(FieldNames.FIELD_DESCRIPTION, "Example");
			
			boolean badUp = ioContext.getAccessPoint().update(testUser2, odata) != null;
			assertFalse("Expected update to fail", badUp);
			
			boolean goodUp = ioContext.getAccessPoint().update(testUser1, odata) != null;
			assertTrue("Expected update to pass", goodUp);
			
			boolean deleted = ioContext.getAccessPoint().delete(testUser1, odata);
			logger.info("Deleted: " + deleted);
			
		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("Data was not created", odata);
	}
	
	@Test
	public void TestUnAuthorizedQuery() {
		logger.info("*** Test Unauthorized Query");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);

		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser2);

		
		BaseRecord group = ioContext.getAccessPoint().make(testUser1, ModelNames.MODEL_GROUP, "~/Query/Authorization", GroupEnumType.DATA.toString());
		assertNotNull("Group is null", group);
		String dataName = "Example Data - " + UUID.randomUUID().toString();
		BaseRecord data = getCreateData(testUser1, dataName, "~/Query/Authorization", "This is the example data");

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, data.get(FieldNames.FIELD_NAME));
		BaseRecord rec = ioContext.getAccessPoint().find(testUser2, q);
		assertNull("Expected record to be null", rec);
		
		Query q2 = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		int count = ioContext.getAccessPoint().count(testUser2, q2);
		logger.info("Count: " + count);

		QueryResult qr2 = ioContext.getAccessPoint().list(testUser2, q2);
		// logger.info(qr2.toString());
	}
	
	@Test
	public void TestAuthorizeQuery() {
		logger.info("*** Test Authorized Query");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);

		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser2);

		
		BaseRecord group = ioContext.getAccessPoint().make(testUser1, ModelNames.MODEL_GROUP, "~/Query/Authorization", GroupEnumType.DATA.toString());
		assertNotNull("Group is null", group);
		String dataName = "Example Data - " + UUID.randomUUID().toString();
		BaseRecord data = getCreateData(testUser1, dataName, "~/Query/Authorization", "This is the example data");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		try {
			q.set(FieldNames.FIELD_URN, "query." + UUID.randomUUID().toString());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		try {
			PolicyType pol = ioContext.getPolicyUtil().getResourcePolicy(PolicyUtil.POLICY_SYSTEM_READ_OBJECT, testUser1, null, q).toConcrete();
			// logger.info(pol.toString());
		} catch (ReaderException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		PolicyResponseType[] prrs1 = ioContext.getPolicyUtil().evaluateQueryToReadPolicyResponses(testUser1, q);
		logger.info("Prr 1: " + prrs1.length);
		assertTrue("Expected query to be permitted", ioContext.getPolicyUtil().evaluateQueryToReadPolicy(testUser1, q));
		Query q2 = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
		PolicyResponseType[] prrs2 = ioContext.getPolicyUtil().evaluateQueryToReadPolicyResponses(testUser1, q2);
		logger.info("Prr 2: " + prrs2.length);
		assertTrue("Expected query to be permitted", ioContext.getPolicyUtil().evaluateQueryToReadPolicy(testUser1, q2));
		
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		lq.setRequestRange(0, 3);
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, lq);
		assertNotNull("Null response", qr);
		assertTrue("Expected some results", qr.getCount() > 0);
		String ser = qr.toString();
		
		BaseRecord iqrb = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Could not import query result model", iqrb);
		QueryResult iqr = new QueryResult(iqrb);
	}
	
	@Test
	public void TestCountData() {
		logger.info("*** Test Count Data");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);

		logger.info("*** MAKE/FIND GROUP");
		BaseRecord group = ioContext.getAccessPoint().make(testUser1, ModelNames.MODEL_GROUP, "~/A/Place/For/My/Stuff", GroupEnumType.DATA.toString());
		assertNotNull("Group is null", group);
	}
	
}
