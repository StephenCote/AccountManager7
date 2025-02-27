package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.ParameterUtil;
import org.junit.Test;

/// Migrating from AM6 version - 2023/10/12
///
public class TestAccessApproval extends BaseTest {

	private boolean cleanupApprovers = true;
	private boolean cleanupRemovedRequests = false;
	/*
	 * NEW [for AM7]
	 * 
	 * Requester - the entity making the request
	 * Submitter - the entity submitting the request (may be the same as requester)
	 * Subject - the entity for whom the request is being made
	 * Resource - the entity to which the request action will apply 
	 * Entitlement - the role or permission to affect subject's interaction with resource
	 * Approver - the entity to which the request is assigned for approval
	 * Delegate - an alternate entity which may be assigned for delegating approval
	 * 
	 * OLD [for AM6]
	 * 
	 * Request + Approval is structured as follows
	 *    Request [MessageSpoolType]
	 *       Some
	 *    Approval is 
	 * 
	 * OLD [was NEW for AM6]
	 * 
	 * I request [ACTION|ACCESS] to [RESOURCE] {on behalf of} == AccessRequestType
	 * [ACTION|ACCESS] to [RESOURCE] requires [ROLE|GROUP|PERSON] approval at level # == ApproverType
	 * [PERSON] [GRANT|DENY|PEND] [REQUEST] == ApprovalType
	 * [CONTROL] dictates [RESOURCE] requires [POLICY] = ControlType
	 * [POLICY] directs completion == PolicyRequestType (note: currently synchronous, so the process will have to fork, or define a pre-processor to detect and satisfy pending requirements)
	 * 
	 * 
	 * 
	 *
	 * The structure of the approval policy is as follows:
	 * Policy
	 *    Rule
	 *       Pattern [PatternType==APPROVAL,OPERATION=AccessApprovalOperation] - Evaluates Approval of SourceFact and MatchFact
	 *          --- OWNER
	 *          SourceFact[FactType==PARAMETER]==Entitlement
	 *          (OWNER) MatchFact[FactType==OPERATION,FactoryType==OPERATION,OPERATION=LookupOwnerOperation] - Evaluates to a ROLE, GROUP, or PERSON
	 *          
	 *          --- SPECIFIED
	 *         SourceFact[FactType==PARAMETER]==Entitlement
	 *         (OWNER) MatchFact[FactType==OPERATION,FactoryType==UNKNOWN, OPERATION=LookupApproverOperation, DATA={level}] - Evaluates to a ROLE, GROUP, or PERSON
	 *
	 */

	/*
	@Test
	public void TestCountAudit() {
		boolean error = false;
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT);
			// q.set(FieldNames.FIELD_DEBUG, true);
			q.setRequestRange(0, 10);
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING.toString());
			QueryResult qr = ioContext.getSearch().find(q);
			logger.info("Audit count: " + qr.getCount());
		}
		catch(ValueException | FieldException | ModelNotFoundException | IndexException | ReaderException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("Error encountered", error);
	}
	*/
	@Test
	public void TestCountRequests() {
		logger.warn("TODO: Currently unresolved issue with role authorization on creating a request to a system entitlement for an object");
		if(ioContext.getIoType() == RecordIO.FILE) {
			logger.error("****** TODO: The file system support for flexible foreign keyed fields depends updating the deserializer to defer that object until the foreignType value is known from the adjacent property");
			return;
		}
		
		// String schema = ioContext.getDbUtil().generateSchema(RecordFactory.getSchema(ModelNames.MODEL_ACCESS_REQUEST));
		// logger.info(schema);

		logger.info("Testing count requests");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Access Approval");
		Factory mf = ioContext.getFactory();
		
		logger.info("Setting up test users");
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testRole1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_ROLE, "~/Access Roles/Access 1", RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Role is null", testRole1);
		
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		BaseRecord testRequestReader =  mf.getCreateUser(testOrgContext.getAdminUser(), "testRequestReader", testOrgContext.getOrganizationId());
		BaseRecord requesterRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUESTERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		BaseRecord requestReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUEST_READERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		BaseRecord requestUpdaterRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUEST_UPDATERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		BaseRecord accountReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		BaseRecord roleReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ROLE_READERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		BaseRecord permReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_PERMISSION_READERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
		if(!ioContext.getMemberUtil().isMember(testUser1, accountReaderRole, null)) {
			logger.info("Assigning test users to account reader role in order to be authorized to see foreign user references");
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), accountReaderRole, testUser1, null, true);
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), accountReaderRole, testRequestReader, null, true);
		}
		if(!ioContext.getMemberUtil().isMember(testUser1, requesterRole, null)) {
			logger.info("Assigning test request role in order to be authorized to create access requests");
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), requesterRole, testUser1, null, true);
		}
		if(!ioContext.getMemberUtil().isMember(testUser1, permReaderRole, null)) {
			logger.info("Assigning test request role in order to be authorized to read system permissions");
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), permReaderRole, testUser1, null, true);
		}

		if(!ioContext.getMemberUtil().isMember(testRequestReader, requestReaderRole, null)) {
			logger.info("Assigning test request reader/updater to roles in order to be authorized to read system roles, read requests, and update request status");
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), requestReaderRole, testRequestReader, null, true);
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), requestUpdaterRole, testRequestReader, null, true);	
			ioContext.getMemberUtil().member(testOrgContext.getAdminUser(), roleReaderRole, testRequestReader, null, true);
		}
		
		logger.info("Mark pending testUser1 requests for removal");
		cleanupRequestsForUser(testRequestReader, testUser1);
		
		
		logger.info("Creating a directory object for a test user to request access to.");
		BaseRecord dir = ioContext.getAccessPoint().make(testUser1, ModelNames.MODEL_GROUP, "~/Parent", GroupEnumType.DATA.toString());
		
		logger.info("Create an access request to add testUser2 to a role owned by testUser1");
		BaseRecord req = newAccessRequest(testUser1, testUser1, testUser1, ActionEnumType.ADD, testUser2, null, testRole1, 0L);
		BaseRecord xrex = ioContext.getAccessPoint().create(testUser1, req);
		assertNotNull("Request was null", xrex);

		logger.info("Create an access request to let testUser2 read testUser1's directory");
		BaseRecord dataReadPerm = AccessSchema.getSystemPermission("Read", "DATA", testOrgContext.getOrganizationId());
		
		BaseRecord req2 = newAccessRequest(testUser1, testUser1, testUser1, ActionEnumType.GRANT, testUser2, dir, dataReadPerm, 0L);
		BaseRecord xrex2 = ioContext.getAccessPoint().create(testUser1, req2);

		assertNotNull("Request was null", xrex2);
		
		BaseRecord[] reqs = getAccessRequests(testUser1, testUser1);
		assertTrue("Expected two pending requests; received " + reqs.length, reqs.length == 2);
		
		/*
		boolean error = false;
		try {
			cleanupRequestsForUser(testUser);
			AccessRequestType req = newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, null, 0L);
			assertNotNull("Request is null",req);
			int count = countAccessRequests(null, ActionEnumType.REQUEST, null, null, null, null, 0L);
			assertTrue("Request count should be greater than zero",count > 0);
			logger.info("Received " + count + " requests");
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertFalse("An error occurred", error);
		*/
	}
	
	private void cleanupRequestsForUser(BaseRecord requestAdmin, BaseRecord user)  {
		BaseRecord[] reqs = getAccessRequests(requestAdmin, user);
		logger.info("Current open requests: " + reqs.length);
		List<BaseRecord> mod = new ArrayList<>();
		try {
			if(reqs.length > 0) {
				/*
				ioContext.getPolicyUtil().setTrace(true);
				PolicyResponseType prt = ioContext.getAuthorizationUtil().canUpdate(requestAdmin, requestAdmin, reqs[0].copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_APPROVAL_STATUS}));
				logger.info(prt.toFullString());
				ioContext.getPolicyUtil().setTrace(false);
				*/
				for(BaseRecord req : reqs) {
					BaseRecord lreq = req.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_APPROVAL_STATUS});
					lreq.set(FieldNames.FIELD_APPROVAL_STATUS, ApprovalResponseEnumType.REMOVE);
					mod.add(lreq);
				}
				int upd = ioContext.getAccessPoint().update(requestAdmin, mod.toArray(new BaseRecord[0]));
				assertTrue("Expected all records to be updated", reqs.length == upd);
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		/*
		List<AccessRequestType> reqs = RequestService.listOpenAccessRequests(testUser);
		if(reqs.size() > 0) {
			logger.info("Cleaning up outstanding requests: " + reqs.size());
			String reqUpdate = BulkFactories.getBulkFactory().newBulkSession();
			for(AccessRequestType arq : reqs) {
				arq.setApprovalStatus(ApprovalResponseEnumType.REMOVE);
				BulkFactories.getBulkFactory().modifyBulkEntry(reqUpdate, FactoryEnumType.REQUEST, arq);
			}
			BulkFactories.getBulkFactory().write(reqUpdate);
			BulkFactories.getBulkFactory().close(reqUpdate);
			reqs = RequestService.listOpenAccessRequests(testUser);
		}
		if(cleanupRemovedRequests) {
			RequestService.deleteAccessRequestsByStatus(null, ApprovalResponseEnumType.REMOVE, user.getOrganizationId());
		}
		*/
	}
	
	private BaseRecord[] getAccessRequests(BaseRecord requestAdmin, BaseRecord requester)  {
		logger.info("Get access requests for " + requester.get(FieldNames.FIELD_NAME));
		BaseRecord[] recs = new BaseRecord[0];
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST, FieldNames.FIELD_APPROVAL_STATUS, ApprovalResponseEnumType.REQUEST.toString());
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING.toString());
			q.field(FieldNames.FIELD_REQUESTER_TYPE, requester.getSchema());
			q.field(FieldNames.FIELD_REQUESTER, requester.copyRecord(new String[] {FieldNames.FIELD_ID}));
			// q.set(FieldNames.FIELD_DEBUG, true);
			
			/*
			PolicyResponseType[] prts = ioContext.getPolicyUtil().evaluateQueryToReadPolicyResponses(requestAdmin, q);
			for(PolicyResponseType p: prts) {
				logger.info(p.toFullString());
			}
			*/
			QueryResult qr = ioContext.getAccessPoint().list(requestAdmin, q);
			if(qr != null) {
				recs = qr.getResults();
			}
		}
		catch(ClassCastException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return recs;
	}
	
	private BaseRecord newAccessRequest(BaseRecord owner, BaseRecord submitter, BaseRecord requester, ActionEnumType action,  BaseRecord subject, BaseRecord resource, BaseRecord entitlement, long parentId) {
		OrganizationContext orgC = IOSystem.getActiveContext().getOrganizationContext(requester.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		Factory mf = ioContext.getFactory();
		BaseRecord req = null;
		ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_ACTION, action);
		plist.parameter(FieldNames.FIELD_ENTITLEMENT, entitlement);
		plist.parameter(FieldNames.FIELD_RESOURCE, resource);
		plist.parameter(FieldNames.FIELD_SUBMITTER, submitter);
		plist.parameter(FieldNames.FIELD_REQUESTER, requester);
		plist.parameter(FieldNames.FIELD_SUBJECT, subject);
		plist.parameter(FieldNames.FIELD_RESPONSE, ApprovalResponseEnumType.REQUEST.toString());
		// plist.parameter(FieldNames.FIELD_PARENT_ID, parentId);
		try {
			req = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACCESS_REQUEST, owner, null, plist);
		} catch (FactoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return req;
	}


	
	/*
	@Test
	public void TestPaginateRequests() {
		boolean error = false;
		List<AccessRequestType> reqs = new ArrayList<>();
		try {
			cleanupRequestsForUser(testUser);
			for(int i = 0; i < 20; i++) {
				AccessRequestType req = newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, null, 0L);
				assertNotNull("Request is null",req);
			}
			int count = countAccessRequests(null, ActionEnumType.REQUEST, null, null, null, null, 0L);
			logger.info("Received " + count + " requests");
			assertTrue("Request count should be greater than 10",count > 10);
			reqs = getAccessRequests(testUser, ActionEnumType.REQUEST, null, null, null, null, 0L, 10, 0L);
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		logger.info("Received page with size " + reqs.size());
		assertFalse("An error occurred", error);
		assertTrue("Request count should be ten since it was paginated to a limit of ten", reqs.size() == 10);
		logger.info("Received " + reqs.size() + " requests");
	}
	
	@Test
	public void TestCreateEmptyRequest() {
		boolean error = false;
		try {
			cleanupRequestsForUser(testUser);
			AccessRequestType req = newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, null, 0L);
			assertNotNull("Request is null",req);
			List<AccessRequestType> reqs = getAccessRequests(testUser, ActionEnumType.REQUEST, null, null, null, null, 0L);
			assertTrue("Request count should be one since all requests for this user were marked for removal",reqs.size() == 1);
		} catch (FactoryException | ArgumentException | DataAccessException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("An error occurred", error);
	}
	
	
	@Test
	public void TestCreateRequestForGroup() {
		boolean error = false;
		AccessRequestType request = null;
		DirectoryGroupType app1 = getApplication("Application 1");
		AccountGroupType group1 = getApplicationGroup("Group #1", GroupEnumType.ACCOUNT, app1);

		try {
			cleanupRequestsForUser(testUser);
			logger.info("Test creating an group access request");
			RequestFactory rFact = ((RequestFactory)Factories.getFactory(FactoryEnumType.REQUEST));
			request = rFact.newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, group1, 0L);
			assertNotNull("Request is null", request);
			assertTrue("Failed to add request",rFact.add(request));
			
			List<AccessRequestType> reqs = rFact.getAccessRequestsForType(testUser, null, null, group1, ApprovalResponseEnumType.REQUEST,0L, testUser.getOrganizationId());
			logger.info("Found " + reqs.size() + " requests for " + testUser.getUrn() + " to obtain " + group1.getUrn() + " access");
			assertTrue("Request count should be one since all requests for this user were marked for removal",reqs.size() == 1);

		} catch (FactoryException | ArgumentException | DataAccessException e) {
			error = true;
			e.printStackTrace();
			logger.error(e);
		}
		assertFalse("Test threw an error", error);
		assertNotNull("Request is null", request);
	}
	
	@Test
	public void TestCreateRequestBasket() {
		boolean error = false;
		AccessRequestType request = null;
		AccessRequestType childRequest = null;
		DirectoryGroupType app1 = getApplication("Application 1");
		AccountGroupType group1 = getApplicationGroup("Group #1", GroupEnumType.ACCOUNT, app1);
		ApplicationPermissionType per1 = getApplicationPermission("Permission #1",PermissionEnumType.APPLICATION,app1);
		String sessionId = BulkFactories.getBulkFactory().newBulkSession();
		BulkFactory bFact = BulkFactories.getBulkFactory();
		try {
			cleanupRequestsForUser(testUser);

			logger.info("Test creating a bulk request basket");
			RequestFactory rFact = ((RequestFactory)Factories.getFactory(FactoryEnumType.REQUEST));
			request = rFact.newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, null, 0L);
			bFact.createBulkEntry(sessionId, FactoryEnumType.REQUEST, request);

			/// request now has a temporary id that can be used for a parent value
			assertNotNull("Request is null", request);

			childRequest = rFact.newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, group1, request.getId());
			bFact.createBulkEntry(sessionId, FactoryEnumType.REQUEST, childRequest);
	
			childRequest = rFact.newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, app1, per1, request.getId());
			bFact.createBulkEntry(sessionId, FactoryEnumType.REQUEST, childRequest);
			
			bFact.write(sessionId);
			bFact.close(sessionId);
			
			List<AccessRequestType> reqs = rFact.getAccessRequestsForType(testUser, null, null, null, ApprovalResponseEnumType.REQUEST,0L, testUser.getOrganizationId());
			logger.info("Found " + reqs.size() + " requests for " + testUser.getUrn() + " to obtain " + group1.getUrn() + " access");
		} catch (FactoryException | ArgumentException | DataAccessException e) {
			error = true;
			e.printStackTrace();
			logger.error(e);
		}

		assertFalse("Test threw an error", error);
		assertNotNull("Request is null", request);
	}
	
	*/
	/*
	 * To test access requests, the following conditions must be true:
	 * - a valid user to make the request
	 * - a valid user to approve the request
	 * - an entitlement
	 * - an approver defined for the entitlement, or parent application
	 * - at least on enabled policy (eg: Owner Approval Policy)
	 * - a control that correlates the entitlement to the policy
	 */
	/*
	@Test
	public void TestRequestAccess() {
		
		DirectoryGroupType app1 = getApplication("Application 1");
		Factories.getAttributeFactory().populateAttributes(app1);
		PersonRoleType roleP = getApplicationRole("Role #1",RoleEnumType.PERSON,app1);
		AccountRoleType roleP2 = getApplicationRole("Role #2",RoleEnumType.ACCOUNT,app1);
		ApplicationPermissionType per1 = getApplicationPermission("Permission #1",PermissionEnumType.APPLICATION,app1);
		ApplicationPermissionType per2 = getApplicationPermission("Permission #2",PermissionEnumType.APPLICATION,app1);
		AccountGroupType group1 = getApplicationGroup("Group #1", GroupEnumType.ACCOUNT, app1);
		AccountGroupType group2 = getApplicationGroup("Group #2", GroupEnumType.ACCOUNT, app1);
		assertNotNull("Group 1 is null", group1);

		boolean error = false;
		
		try {
			MessageFactory mFact = Factories.getFactory(FactoryEnumType.MESSAGE);
			ApproverFactory aFact = ((ApproverFactory)Factories.getFactory(FactoryEnumType.APPROVER));
			AttributeFactory atFact = Factories.getAttributeFactory();

			PersonType testPerson2 = ((PersonFactory)Factories.getFactory(FactoryEnumType.PERSON)).getSystemPersonByUser(testUser2);
			assertNotNull("Test Person 2 is null", testPerson2);
			if(!RoleService.getIsMemberInRole(testPerson2, roleP)) assertTrue("Unable to add person to role",RoleService.addPersonToRole(testPerson2, roleP));
			
			if(cleanupApprovers) {
				logger.info("Cleaning up pre-test conditions");
				aFact.deleteApproversForType(null, group1);
				aFact.deleteApproversForType(null, per1);
			}
			
			List<ApproverType> aLo = getCreateApprovers(testUser, testUser, null, group1, 1, ApprovalEnumType.OWNER);
			if(atFact.getAttributeByName(app1, RequestService.ATTRIBUTE_NAME_OWNER) == null) {
				atFact.newAttribute(app1, RequestService.ATTRIBUTE_NAME_OWNER, Long.toString(testUser.getId()));
				Factories.getAttributeFactory().addAttributes(app1);
			}

			List<ApproverType> aL = getCreateApprovers(testUser, testUser, null, group1, 1,ApprovalEnumType.ACCESS);
			List<ApproverType> aL2 = getCreateApprovers(testUser, testUser, app1, per1, 1,ApprovalEnumType.ACCESS);
			List<ApproverType> aL3 = getCreateApprovers(testUser, roleP, null, group1, 2,ApprovalEnumType.ACCESS);
			
			ApproverType apro = aLo.get(0);
			ApproverType apr1 = aL.get(0);
			ApproverType apr2 = aL2.get(0);
			ApproverType apr3 = aL3.get(0);
			assertNotNull("Approval object is null", apro);
			assertNotNull("Approval object is null", apr1);
			assertNotNull("Approval object is null", apr2);
			assertNotNull("Approval object is null", apr3);
			
			PolicyType ownerPolicy = PolicyService.getOwnerApprovalPolicy(testUser.getOrganizationId());
			//logger.info(JSONUtil.exportObject(ownerPolicy));
			PolicyType principalPolicy = PolicyService.getPrincipalApprovalPolicy(testUser.getOrganizationId());
			//logger.info(JSONUtil.exportObject(principalPolicy));
			/// Attach a control to the ownership policy on the application parent:
			List<ControlType> ctls = getCreateAccessApproverControls(testUser, ownerPolicy, app1);
			
			assertTrue("Expected at least one control", ctls.size() > 0);
			
			assertTrue("Test user should be able to view the policy",AuthorizationService.canView(testUser, ownerPolicy));
			assertFalse("Test user should not be able to modify the policy", AuthorizationService.canChange(testUser, ownerPolicy));
			
			/// Attach a control to the ownership policy on the application parent:
			List<ControlType> ctls2 = getCreateAccessApproverControls(testUser, principalPolicy, app1);
			
			assertTrue("Entitlement " + group1.getName() + " is not requestable", RequestService.isRequestable(group1));
			
			cleanupRequestsForUser(testUser);
			List<AccessRequestType> reqs = RequestService.listOpenAccessRequests(testUser);
			assertTrue("Expected zero outstanding requests for user " + testUser.getUrn(), reqs.size() == 0);
			
			RequestFactory rFact = ((RequestFactory)Factories.getFactory(FactoryEnumType.REQUEST));
			AccessRequestType request = rFact.newAccessRequest(testUser, ActionEnumType.REQUEST, null, null, null, group1, 0L);
			assertNotNull("Request is null", request);
			assertTrue("Failed to add request",rFact.add(request));
			
			reqs = rFact.getAccessRequestsForType(testUser, null, null, group1, ApprovalResponseEnumType.REQUEST,0L, testUser.getOrganizationId());
			assertTrue("Expected one outstanding request for user " + testUser.getUrn(), reqs.size() == 1);

			
			/// Each access request will result in one or more policies
			/// From an end user and approval perspective, these can be requested as a bucket/basket, and approval can be handled at that level as well (if desired)
			/// Behind the scenes, it's all individual records to track the request, the approval, etc.
			/// Therefore, in evaluating a request for approval, it's necessary to:
			/// 1) Get one or more pending request
			/// 2) For each request, obtain all of the policies for that request
			/// 3) For each policy for each request, evaluate the policy to obtain the approvers
			/// 4) Generate the approval entries per the policy
			/// 
			logger.info("Evaluating outstanding access requests: " + reqs.size());
			for(AccessRequestType arq : reqs) {
				List<PolicyType> reqPols= RequestService.getRequestPolicies(arq);
				assertTrue("Expected at least one policy", reqPols.size() > 0);
				logger.info("Applicable policy controls for request: " + reqPols.size());

				/// evaluateRequestPolicies will translate the corresponding approver for the request into a pending approval for the request
				///
				List<ApprovalType> approvals = RequestService.evaluateRequestPolicies(arq, reqPols);
				assertTrue("Expected at least one approval", approvals.size() > 0);
				logger.info("Obtained " + approvals.size());
				
				/// Do it again to make sure entries aren't double booked
				approvals = RequestService.evaluateRequestPolicies(arq, reqPols);
				logger.info("Obtained 2x " + approvals.size());
				
				/// Process request for approvals - this must be called AFTER evaluateRequestPolicies
				/// evaluateRequestPolicies can be called as many times as needed to add new entries mid-request if desired
				int msgCount = RequestService.processPendingRequestPolicies(arq, true, false);
				
				logger.info("Recorded " + msgCount + " internal messages");
				
				/// Process request again to test break on level, and check to throttle message flooding
				///
				msgCount = RequestService.processPendingRequestPolicies(arq, true, false);
				logger.info("Re-Recorded " + msgCount + " internal messages");
				/// Drop a note in the queue that the user has a pending approval request

				/// Check all outstanding messages for request
				List<MessageSpoolType> reqMsg = RequestService.getApprovalRequestMessages(testUser, SpoolStatusEnumType.UNKNOWN, arq.getId(), 0L, NameEnumType.UNKNOWN, 0L, testUser.getOrganizationId());			
				/// Should be 2 messages for processing at level 1 only
				assertTrue("There should only be two messages after processing at first level for this request",reqMsg.size() == 2);
				
				/// Get messages for testUser
				/// Note: testUser has 2 messages for the same request, one to approve as an owner, and one to approve the access
				///
				logger.info("Request messages: " + reqMsg.size());
				RequestService.approve(testUser,reqMsg.get(0),true);
				RequestService.approve(testUser,reqMsg.get(1),true);
				msgCount = RequestService.processPendingRequestPolicies(arq, true, false);
				reqMsg = RequestService.getApprovalRequestMessages(testUser2, SpoolStatusEnumType.UNKNOWN, arq.getId(), 0L, NameEnumType.UNKNOWN, 0L, testUser.getOrganizationId());			
				logger.info("Received " + reqMsg.size() + " messages");
				/// Should be 1 messages for processing at level 2 only
				assertTrue("There should only be two messages after processing at first level for this request",reqMsg.size() == 1);
				RequestService.approve(testUser2,reqMsg.get(0),true);
				
				/// Now, try to resolve the request
				ApprovalResponseEnumType res = RequestService.resolveRequest(arq);
				
				assertTrue("Request should be marked for approval", res == ApprovalResponseEnumType.APPROVE);
			}
			
		} catch (ArgumentException | FactoryException | DataAccessException | NullPointerException e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertFalse("An error was encountered", error);
	}
	*/
	/*
	private void cleanupRequestsForUser(UserType user) throws FactoryException, ArgumentException, DataAccessException {
		List<AccessRequestType> reqs = RequestService.listOpenAccessRequests(testUser);
		if(reqs.size() > 0) {
			logger.info("Cleaning up outstanding requests: " + reqs.size());
			String reqUpdate = BulkFactories.getBulkFactory().newBulkSession();
			for(AccessRequestType arq : reqs) {
				arq.setApprovalStatus(ApprovalResponseEnumType.REMOVE);
				BulkFactories.getBulkFactory().modifyBulkEntry(reqUpdate, FactoryEnumType.REQUEST, arq);
			}
			BulkFactories.getBulkFactory().write(reqUpdate);
			BulkFactories.getBulkFactory().close(reqUpdate);
			reqs = RequestService.listOpenAccessRequests(testUser);
		}
		if(cleanupRemovedRequests) {
			RequestService.deleteAccessRequestsByStatus(null, ApprovalResponseEnumType.REMOVE, user.getOrganizationId());
		}
	}
	private void deleteRequestsForUser(UserType user) throws FactoryException, ArgumentException {
		RequestService.deleteAccessRequestsByOwner(user);
	}

	
	private List<AccessRequestType> getAccessRequests(UserType owner, ActionEnumType action, NameIdType requester, NameIdType delegate, NameIdType targetObject, NameIdType entitlement, long parentId) throws FactoryException, ArgumentException {
		return getAccessRequests(owner, action, requester, delegate, targetObject, entitlement, 0L, 0, parentId);
	}
	private List<AccessRequestType> getAccessRequests(UserType owner, ActionEnumType action, NameIdType requester, NameIdType delegate, NameIdType targetObject, NameIdType entitlement, long startRecord, int recordCount, long parentId) throws FactoryException, ArgumentException {
		RequestFactory rFact = ((RequestFactory)Factories.getFactory(FactoryEnumType.REQUEST));
		return rFact.getAccessRequestsForType(testUser, requester, delegate, targetObject, ApprovalResponseEnumType.REQUEST,parentId, startRecord, recordCount, testUser.getOrganizationId());
	}

	private int countAccessRequests(UserType owner, ActionEnumType action, NameIdType requester, NameIdType delegate, NameIdType targetObject, NameIdType entitlement, long parentId) throws FactoryException, ArgumentException {
		RequestFactory rFact = ((RequestFactory)Factories.getFactory(FactoryEnumType.REQUEST));
		return rFact.countAccessRequestsForType(testUser, requester, delegate, targetObject, ApprovalResponseEnumType.REQUEST,parentId, testUser.getOrganizationId());
	}
	
	private List<ApproverType> getCreateApprovers(UserType user, NameIdType approver, NameIdType object, NameIdType entitlement, int level, ApprovalEnumType approvalType) throws FactoryException, ArgumentException{
		ApproverFactory aFact = ((ApproverFactory)Factories.getFactory(FactoryEnumType.APPROVER));
		List<ApproverType> apprs = aFact.getApproversForType(object, entitlement, level,approvalType);
		if(apprs.size() == 0) {
			ApproverType apro = aFact.newApprover(testUser, object, entitlement, approver, approvalType, level);
			assertTrue("Failed to add approver 1", aFact.add(apro));
			apprs = aFact.getApproversForType(object, entitlement, level,approvalType);
		}
		assertTrue("Expected one or more approvers", apprs.size() > 0);
		return apprs;
	}
	
	private List<ControlType> getCreateAccessApproverControls(UserType owner, PolicyType policy, NameIdType object) throws FactoryException, ArgumentException{
		ControlActionEnumType action = ControlActionEnumType.ACCESS;
		ControlFactory cFact = ((ControlFactory)Factories.getFactory(FactoryEnumType.CONTROL));
		List<ControlType> ctls = cFact.getControlsForType(object,ControlEnumType.POLICY, policy.getId(), action,true,false);
		if(ctls.size() == 0) {
			ControlType ownerControl = cFact.newControl(owner, object);
			ownerControl.setControlId(policy.getId());
			ownerControl.setControlType(ControlEnumType.POLICY);
			ownerControl.setControlAction(action);
			assertTrue("Failed to add owner control",cFact.add(ownerControl));
			ctls = cFact.getControlsForType(object,ControlEnumType.POLICY, policy.getId(),action,true,false);
		}
		return ctls;
	}
	*/

}
