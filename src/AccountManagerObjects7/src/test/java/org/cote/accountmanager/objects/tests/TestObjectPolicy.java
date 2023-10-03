package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestObjectPolicy extends BaseTest {
	
	@Test
	public void TestCrossTypePathAuthorization() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		//BaseRecord rec = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, "~/Data", GroupEnumType.DATA.toString(), testOrgC)

		String userRolePath = "~/User Roles/User - " + UUID.randomUUID().toString();
		String accountRolePath = "~/Account Roles/Account - " + UUID.randomUUID().toString();

		String userPermissionPath = "~/User Permissions/User - " + UUID.randomUUID().toString();
		String accountPermissionPath = "~/Account Permissions/Account - " + UUID.randomUUID().toString();
		String dataGroupPath = "~/Data/Group - " + UUID.randomUUID().toString();
		String randomParentGroup = "/Parent - " + UUID.randomUUID().toString();
		String accountGroupPath = "~/Account" + randomParentGroup + "/Group - " + UUID.randomUUID().toString();
		// ioContext.getPolicyUtil().setTrace(true);
		BaseRecord dir1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, dataGroupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		//ioContext.getPolicyUtil().setTrace(false);
		assertNotNull("Data group is null", dir1);
		
		BaseRecord dir2 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, accountGroupPath, GroupEnumType.ACCOUNT.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Account group is null", dir2);

		
		BaseRecord homePerm = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_PERMISSION, "~/", PermissionEnumType.USER.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Can't read home permission", homePerm);
		
		try {
			BaseRecord chkPerm = mf.newInstance(ModelNames.MODEL_PERMISSION, testUser1);
			chkPerm.set(FieldNames.FIELD_NAME, "Test Perm - " + UUID.randomUUID().toString());
			chkPerm.set(FieldNames.FIELD_PARENT_ID, homePerm.get(FieldNames.FIELD_ID));
			chkPerm.set(FieldNames.FIELD_TYPE, PermissionEnumType.USER);
			
			// ioContext.getPolicyUtil().setTrace(true);
			PolicyResponseType prr = ioContext.getAuthorizationUtil().canCreate(testUser1, testUser1, chkPerm);
			// ioContext.getPolicyUtil().setTrace(false);
			//logger.info(prr.toFullString());
			assertTrue("Expected a create permit", prr.getType() == PolicyResponseEnumType.PERMIT);
			
			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		

		// ioContext.getPolicyUtil().setTrace(true);
		BaseRecord perm1 = ioContext.getPathUtil().makePath(testUser1,  ModelNames.MODEL_PERMISSION, userPermissionPath, PermissionEnumType.USER.toString(), testOrgContext.getOrganizationId());
		//ioContext.getPolicyUtil().setTrace(false);
		assertNotNull("User permission is null", perm1);

		
		BaseRecord perm2 = ioContext.getPathUtil().makePath(testUser1,  ModelNames.MODEL_PERMISSION, accountPermissionPath, PermissionEnumType.ACCOUNT.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Account permission is null", perm2);
		
		// ioContext.getPolicyUtil().setTrace(true);
		BaseRecord role1 = ioContext.getPathUtil().makePath(testUser1,  ModelNames.MODEL_ROLE, userRolePath, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		// ioContext.getPolicyUtil().setTrace(false);
		assertNotNull("User role is null", role1);

		BaseRecord role2 = ioContext.getPathUtil().makePath(testUser1,  ModelNames.MODEL_ROLE, accountRolePath, RoleEnumType.ACCOUNT.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Account role is null", role2);

	}
	


	@Test
	public void TestDeserializeInferredModel() {
		logger.info("Test deserialize inferred model");
		logger.info("Create test organization");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = null;
		try 
		{
			logger.info("Create test user");
			testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		logger.info("Load inferred owner policy function policy");
		BaseRecord rec1 = IOSystem.getActiveContext().getPolicyUtil().getInferredOwnerPolicyFunction();
		logger.info("Create test data");
		BaseRecord dat = getCreateData(testUser1, "Test Policy Data 2", "text/plain", "This is the demo data".getBytes(), "~/Data/Policy Test", testOrgContext.getOrganizationId());
		// logger.info(JSONUtil.exportObject(dat, RecordSerializerConfig.getUnfilteredModule()));
		PolicyResponseType prr = null;
		PolicyType rec = rec1.toConcrete();

		boolean error = false;
		try {
			PolicyDefinitionType pdef = ioContext.getPolicyDefinitionUtil().generatePolicyDefinition(rec).toConcrete();
			assertNotNull("Definition is null", pdef);
			assertTrue("Expected one parameter and received " + pdef.getParameters().size(), pdef.getParameters().size() == 1);
			PolicyRequestType preq = new PolicyRequestType(ioContext.getPolicyDefinitionUtil().generatePolicyRequest(pdef));
			assertNotNull("Policy request is null", preq);

			preq.setContextUser(testUser1);
			assertTrue("Expected one parameter and received " + preq.getFacts().size(), preq.getFacts().size() == 1);
			preq.getFacts().get(0).setSourceUrn(dat.get("urn"));
			preq.getFacts().get(0).setModelType(dat.getModel());
			logger.info("Check: " + dat.get("urn") + "==" + preq.getFacts().get(0).getSourceUrn());
			prr = ioContext.getPolicyEvaluator().evaluatePolicyRequest(preq, rec).toConcrete();
			
		} catch (ModelException | FieldException | ValueException | ModelNotFoundException | ScriptException | IndexException | ReaderException e) {
			error = true;
			logger.error(e);
			
		}
		assertFalse("Error encountered", error);
		assertTrue("Expected a permit response", prr.getType() == PolicyResponseEnumType.PERMIT);

	}
	

	@Test
	public void TestOwnerAccessPolicy() {
		logger.info("Test owner access policy");
		logger.info("Create test org");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = null;
		try 
		{
			logger.info("Create test user");
			testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		logger.info("Create test data");
		BaseRecord dat = getCreateData(testUser1, "Test Policy Data 1", "text/plain", "This is the demo data".getBytes(), "~/Data/Policy Test", testOrgContext.getOrganizationId());
		
		PolicyType rec = null;
		PolicyRequestType preq = null;
		PolicyResponseType prr = null;
		PolicyDefinitionType pdef = null;

		try {
			logger.info("Load owner resource policy");
			rec = ioContext.getPolicyUtil().getResourcePolicy("owner", testUser1, null, dat).toConcrete();
			
			rec.getRules().get(0).getPatterns().get(0).getMatch().setModelType(dat.getModel());
			rec.getRules().get(0).getPatterns().get(0).getMatch().setSourceUrn(dat.get(FieldNames.FIELD_URN));
			//rec.getRules().get(0).getPatterns().get(0).getMatch().setSourceUrn(dat.get(FieldNames.FIELD_URN));
			
			pdef = new PolicyDefinitionType(ioContext.getPolicyDefinitionUtil().generatePolicyDefinition(rec));
			preq = new PolicyRequestType(ioContext.getPolicyDefinitionUtil().generatePolicyRequest(pdef));
			preq.setContextUser(testUser1);
			assertTrue("Expected one parameter", preq.getFacts().size() == 1);
			preq.getFacts().get(0).setSourceUrn(testUser1.get(FieldNames.FIELD_URN));
			// preq.getFacts().get(0).set(FieldNames.FIELD_FACT_DATA_TYPE, dat.getModel());
			//preq.getFacts().get(0).setModelType(dat.getModel());
			
			prr = ioContext.getPolicyEvaluator().evaluatePolicyRequest(preq, rec).toConcrete();
		}
		catch(Exception e) {
			logger.error(e);
		}

		assertNotNull("Policy is null", rec);
		assertNotNull("Definition is null", pdef);
		assertNotNull("Request is null", preq);
		assertNotNull("Response is null", prr);
		assertTrue("Expected a permit response", prr.getType() == PolicyResponseEnumType.PERMIT);
	}
	

	@Test
	public void TestLinkedAuthorization() {
		BaseRecord testUser1 = null;
		Factory mf = ioContext.getFactory();
		try 
		{
			testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		assertNotNull("Test user is null", testUser1);
		BaseRecord dat = getCreateData(testUser1, "Test Membership Data 1", "text/plain", "This is the demo data".getBytes(), "~/Data/Membership Test", orgContext.getOrganizationId());
		BaseRecord urole1 = AccessSchema.userRole(testUser1);
		BaseRecord uperm1 = AccessSchema.userPermission(testUser1);

		assertNotNull("User role is null", urole1);
		assertNotNull("User permission is null", uperm1);
		
		BaseRecord sperm1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_PERMISSION, "/Read", "DATA", orgContext.getOrganizationId());
		BaseRecord cperm1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_PERMISSION, "~/MemberRights/Person/Read", "ACCOUNT", orgContext.getOrganizationId());
		BaseRecord crole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Person/Reader", "ACCOUNT", orgContext.getOrganizationId());
		BaseRecord arole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR, "USER", orgContext.getOrganizationId());
		
		BaseRecord per1 = mf.getCreateDirectoryModel(testUser1, ModelNames.MODEL_PERSON, "Demo Person 1", "~/Persons", orgContext.getOrganizationId());
		BaseRecord acct1 = mf.getCreateDirectoryModel(testUser1, ModelNames.MODEL_ACCOUNT, "Demo Person 1", "~/Persons", orgContext.getOrganizationId());
		assertNotNull("Person is null", per1);
		assertNotNull("Account is null", acct1);
		List<BaseRecord> accounts = per1.get(FieldNames.FIELD_ACCOUNTS);
		if(accounts.size() == 0) {
			accounts.add(acct1);
			ioContext.getRecordUtil().updateRecord(per1);
		}
		assertNotNull("Custom permission is null", cperm1);

		ioContext.getMemberUtil().member(testUser1, dat, crole1, cperm1, false);
		ioContext.getMemberUtil().member(testUser1, dat, crole1, cperm1, true);
		ioContext.getMemberUtil().member(testUser1, dat, crole1, sperm1, false);
		ioContext.getMemberUtil().member(testUser1, dat, crole1, sperm1, true);
		ioContext.getMemberUtil().member(testUser1, crole1, acct1, null, false);
		ioContext.getMemberUtil().member(testUser1, crole1, acct1, null, true);
		
		boolean check = ioContext.getAuthorizationUtil().checkEntitlement(per1, cperm1, dat);
		logger.info("Linked entitlement check: " + check);
		check = ioContext.getAuthorizationUtil().checkEntitlement(per1, sperm1, dat);
		logger.info("Linked entitlement check: " + check);
		
		// logger.info(JSONUtil.exportObject(cperm1, RecordSerializerConfig.getUnfilteredModule()));
		
		PolicyType rec = null;
		PolicyRequestType preq = null;
		PolicyResponseType prr = null;
		PolicyDefinitionType pdef = null;

		PolicyEvaluator pe = ioContext.getPolicyEvaluator();
		try {
			rec = ioContext.getPolicyUtil().getReadPolicy(dat.get(FieldNames.FIELD_URN)).toConcrete();
			BaseRecord fact = rec.getRules().get(0).getPatterns().get(0).getFact();
			fact.set(FieldNames.FIELD_MODEL_TYPE, per1.getModel());
			
			BaseRecord match = rec.getRules().get(0).getPatterns().get(0).getMatch();
			match.set(FieldNames.FIELD_MODEL_TYPE, dat.getModel());
			match.set(FieldNames.FIELD_SOURCE_URN, dat.get(FieldNames.FIELD_URN));

			preq = ioContext.getPolicyUtil().getPolicyRequest(rec, testUser1, per1);

			assertTrue("Expected one parameter", preq.getFacts().size() == 1);
			preq.getFacts().get(0).setFactData(per1.get(FieldNames.FIELD_URN));
			//preq.getFacts().get(0).setModelType(dat.getModel());
			
			prr = pe.evaluatePolicyRequest(preq, rec).toConcrete();
		}
		catch(Exception e) {
			
		}

		assertNotNull("Policy is null", rec);
		assertNotNull("Request is null", preq);
		assertNotNull("Response is null", prr);
		logger.info("Policy Response: " + prr.getType());
		//logger.info(JSONUtil.exportObject(prr, RecordSerializerConfig.getUnfilteredModule()));
		//assertTrue("Expected a permit response", prr.getType() == PolicyResponseEnumType.PERMIT);
		 
		logger.info("Test admin role policy");
		
		try {
			rec = ioContext.getPolicyUtil().getAdminPolicy(arole1.get(FieldNames.FIELD_URN)).toConcrete();

			preq = ioContext.getPolicyUtil().getPolicyRequest(rec, testUser1, orgContext.getAdminUser());

			assertTrue("Expected one parameter", preq.getFacts().size() == 1);
			preq.getFacts().get(0).setFactData(per1.get(FieldNames.FIELD_URN));
			//preq.getFacts().get(0).setModelType(dat.getModel());
			
			prr = pe.evaluatePolicyRequest(preq, rec).toConcrete();
		}
		catch(Exception e) {
			
		}
		
	}
	
	@Test
	public void TestSystemResourceCreatePolicy() {
	logger.info("Test System Resource Create Policy");
		
		BaseRecord testUser1 = null;
		Factory mf = ioContext.getFactory();
		BaseRecord newDat = null;
		try 
		{
			logger.info("Setup test users");
			testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
			
			ParameterList plist = ParameterList.newParameterList("path", "~/Temp QA");
			plist.parameter("name", "Demo data");
			newDat = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		logger.info("Evaluate policies");

		evaluatePolicy(testUser1, "systemCreateObject", testUser1, newDat, PolicyResponseEnumType.PERMIT);
	}
	

	@Test
	public void TestSystemResourceAccessPolicy() {
		
		logger.info("Test System Resource Access Policy");
		
		BaseRecord testUser1 = null;
		BaseRecord testUser2 = null;
		BaseRecord testUser3 = null;
		Factory mf = ioContext.getFactory();
		BaseRecord newDat = null;
		try 
		{
			logger.info("Setup test users");
			testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
			testUser2 = mf.getCreateUser(orgContext.getAdminUser(), "testUser2", orgContext.getOrganizationId());
			testUser3 = mf.getCreateUser(orgContext.getAdminUser(), "testUser3", orgContext.getOrganizationId());
			
			ParameterList plist = ParameterList.newParameterList("path", "~/Temp QA");
			plist.parameter("name", "Demo data");
			newDat = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		
		logger.info("Setup test data");
		
		BaseRecord dat = getCreateData(testUser1, "Test Membership Data 1", "text/plain", "This is the demo data".getBytes(), "~/Data/Membership Test", orgContext.getOrganizationId());
		BaseRecord urole1 = AccessSchema.userRole(testUser1);
		BaseRecord uperm1 = AccessSchema.userPermission(testUser1);

		logger.info("Setup test rights");
		assertNotNull("User role is null", urole1);
		assertNotNull("User permission is null", uperm1);
		
		BaseRecord sperm1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_PERMISSION, "/Read", "DATA", orgContext.getOrganizationId());
		BaseRecord trole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Person/Reader", "USER", orgContext.getOrganizationId());
		BaseRecord arole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR, "USER", orgContext.getOrganizationId());

		logger.info("Setup test memberships");
		ioContext.getMemberUtil().member(testUser1, dat, trole1, sperm1, false);
		ioContext.getMemberUtil().member(testUser1, dat, trole1, sperm1, true);
		ioContext.getMemberUtil().member(testUser1, trole1, testUser2, null, false);
		ioContext.getMemberUtil().member(testUser1, trole1, testUser2, null, true);
		
		logger.info("Evaluate policies");
		evaluatePolicy(testUser1, "systemReadObject", testUser1, dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemReadObject", testUser2, dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemReadObject", orgContext.getAdminUser(), dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemReadObject", testUser3, dat, PolicyResponseEnumType.DENY);

		evaluatePolicy(testUser1, "systemCreateObject", testUser1, newDat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemCreateObject", testUser2, newDat, PolicyResponseEnumType.DENY);
		evaluatePolicy(testUser1, "systemCreateObject", orgContext.getAdminUser(), newDat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemCreateObject", testUser3, newDat, PolicyResponseEnumType.DENY);
		
		evaluatePolicy(testUser1, "systemUpdateObject", testUser1, dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemUpdateObject", testUser2, dat, PolicyResponseEnumType.DENY);
		evaluatePolicy(testUser1, "systemUpdateObject", orgContext.getAdminUser(), dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemUpdateObject", testUser3, dat, PolicyResponseEnumType.DENY);
		
		evaluatePolicy(testUser1, "systemDeleteObject", testUser1, dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemDeleteObject", testUser2, dat, PolicyResponseEnumType.DENY);
		evaluatePolicy(testUser1, "systemDeleteObject", orgContext.getAdminUser(), dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemDeleteObject", testUser3, dat, PolicyResponseEnumType.DENY);
		
		evaluatePolicy(testUser1, "systemExecuteObject", testUser1, dat, PolicyResponseEnumType.PERMIT);
		evaluatePolicy(testUser1, "systemExecuteObject", testUser2, dat, PolicyResponseEnumType.DENY);
		evaluatePolicy(testUser1, "systemExecuteObject", orgContext.getAdminUser(), dat, PolicyResponseEnumType.PERMIT);
		PolicyResponseType pr3 = evaluatePolicy(testUser1, "systemExecuteObject", testUser3, dat, PolicyResponseEnumType.DENY);
		
		BaseRecord policy = null;
		try {
			policy = ioContext.getPolicyUtil().getResourcePolicy("systemCreateObject", testUser2, null, dat);
		} catch (ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Policy is null", policy);
		//logger.info(JSONUtil.exportObject(policy, RecordSerializerConfig.getUnfilteredModule()));
		//logger.info(JSONUtil.exportObject(pr3, RecordSerializerConfig.getUnfilteredModule()));
		
		
	}
	
	@Test
	public void TestSystemResourceAccessPolicyLight() {
		BaseRecord testUser1 = null;
		BaseRecord testUser2 = null;
		BaseRecord testUser3 = null;
		Factory mf = ioContext.getFactory();
		try 
		{
			testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
			testUser2 = mf.getCreateUser(orgContext.getAdminUser(), "testUser2", orgContext.getOrganizationId());
			testUser3 = mf.getCreateUser(orgContext.getAdminUser(), "testUser3", orgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		BaseRecord dat = getCreateData(testUser1, "Test Membership Data 1", "text/plain", "This is the demo data".getBytes(), "~/Data/Membership Test", (long)orgContext.getOrganizationId());
		BaseRecord urole1 = AccessSchema.userRole(testUser1);

		assertNotNull("User role is null", urole1);

		BaseRecord sperm1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_PERMISSION, "/Read", "DATA", orgContext.getOrganizationId());
		BaseRecord trole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/MemberRoles/Data/Reader", "USER", orgContext.getOrganizationId());

		ioContext.getMemberUtil().member(testUser1, dat, trole1, sperm1, false);
		ioContext.getMemberUtil().member(testUser1, dat, trole1, sperm1, true);
		ioContext.getMemberUtil().member(testUser1, trole1, testUser2, null, false);
		ioContext.getMemberUtil().member(testUser1, trole1, testUser2, null, true);
		try {
		evaluatePolicyByUrn(testUser1, "systemReadObject", testUser1.getModel(), testUser1.get(FieldNames.FIELD_URN), dat.getModel(), dat.get(FieldNames.FIELD_URN), PolicyResponseEnumType.PERMIT);
		evaluatePolicyByUrn(testUser1, "systemReadObject", testUser2.getModel(), testUser2.get(FieldNames.FIELD_URN), dat.getModel(), dat.get(FieldNames.FIELD_URN), PolicyResponseEnumType.PERMIT);

		evaluatePolicyByUrn(testUser1, "systemCreateObject", testUser1.getModel(), testUser1.get(FieldNames.FIELD_URN), dat.getModel(), dat.get(FieldNames.FIELD_URN), PolicyResponseEnumType.PERMIT);
		evaluatePolicyByUrn(testUser1, "systemCreateObject", testUser2.getModel(), testUser2.get(FieldNames.FIELD_URN), dat.getModel(), dat.get(FieldNames.FIELD_URN), PolicyResponseEnumType.DENY);

		evaluatePolicyByUrn(testUser1, "systemCreateObject", testUser1.getModel(), testUser1.get(FieldNames.FIELD_URN), dat.getModel(), dat.get(FieldNames.FIELD_URN), PolicyResponseEnumType.PERMIT);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	private PolicyResponseType evaluatePolicyByUrn(BaseRecord contextUser, String policyName, String actorType, String actorUrn, String resourceType, String resourceUrn, PolicyResponseEnumType expectedResponse) {
		PolicyResponseType pres = ioContext.getPolicyUtil().evaluateResourcePolicy(contextUser, policyName, actorType, actorUrn, null, resourceType, resourceUrn);
		assertNotNull("Expected a response", pres);
		if(expectedResponse != pres.getType()) {
			logger.error("Expected response " + expectedResponse.toString() + " does not match response " + pres.getType().toString());
			logger.error(JSONUtil.exportObject(pres, RecordSerializerConfig.getUnfilteredModule()));
		}
		assertTrue("Expected the policy to " + expectedResponse.toString(), expectedResponse == pres.getType());
		return pres;
	}
	
	private PolicyResponseType evaluatePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, BaseRecord resource, PolicyResponseEnumType expectedResponse) {
		PolicyResponseType pres = ioContext.getPolicyUtil().evaluateResourcePolicy(contextUser, policyName, actor, resource);
		assertNotNull("Expected a response", pres);
		if(expectedResponse != pres.getType()) {
			logger.error("Expected response " + expectedResponse.toString() + " does not match response " + pres.getType().toString());
			logger.error(JSONUtil.exportObject(actor, RecordSerializerConfig.getUnfilteredModule()));
			logger.error(JSONUtil.exportObject(resource, RecordSerializerConfig.getUnfilteredModule()));
			logger.error(JSONUtil.exportObject(pres, RecordSerializerConfig.getUnfilteredModule()));
		}
		assertTrue("Expected the policy to " + expectedResponse.toString(), expectedResponse == pres.getType());
		return pres;
	}

	
}
