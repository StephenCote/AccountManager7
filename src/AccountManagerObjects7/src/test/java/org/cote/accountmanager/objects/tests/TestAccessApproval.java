package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.OperationType;
import org.cote.accountmanager.objects.generated.PatternType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.objects.generated.RuleType;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.cote.accountmanager.schema.type.ConditionEnumType;
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OperationEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.schema.type.RuleEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
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
	
	/**
	 * Full approval flow integration test:
	 * 1. Build an approval policy with APPROVAL patterns using AccessApprovalOperation
	 * 2. Submit an access request
	 * 3. Evaluate policy → should return PENDING_OPERATION (no approval response yet)
	 * 4. Simulate approver response (create RESPONDED spool entry with "APPROVE")
	 * 5. Re-evaluate policy → should return PERMIT (approval found)
	 */
	@Test
	public void TestApprovalFlow() {
		if(ioContext.getIoType() == RecordIO.FILE) {
			logger.error("Skipping — file IO does not support flex foreign keys");
			return;
		}

		logger.info("=== TestApprovalFlow: Full approval policy evaluation ===");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Approval Flow");
		Factory mf = ioContext.getFactory();
		long orgId = testOrgContext.getOrganizationId();
		BaseRecord adminUser = testOrgContext.getAdminUser();

		/// Setup test users
		BaseRecord requester = mf.getCreateUser(adminUser, "approvalRequester", orgId);
		BaseRecord approver = mf.getCreateUser(adminUser, "approvalApprover", orgId);
		assertNotNull("Requester is null", requester);
		assertNotNull("Approver is null", approver);

		/// Assign system roles so users can create/read/update access requests
		BaseRecord requesterRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUESTERS, RoleEnumType.USER.toString(), orgId);
		BaseRecord requestReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUEST_READERS, RoleEnumType.USER.toString(), orgId);
		BaseRecord requestUpdaterRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUEST_UPDATERS, RoleEnumType.USER.toString(), orgId);
		BaseRecord accountReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS, RoleEnumType.USER.toString(), orgId);
		BaseRecord roleReaderRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ROLE_READERS, RoleEnumType.USER.toString(), orgId);

		if(!ioContext.getMemberUtil().isMember(requester, requesterRole, null)) {
			ioContext.getMemberUtil().member(adminUser, requesterRole, requester, null, true);
			ioContext.getMemberUtil().member(adminUser, accountReaderRole, requester, null, true);
			ioContext.getMemberUtil().member(adminUser, roleReaderRole, requester, null, true);
		}
		if(!ioContext.getMemberUtil().isMember(approver, requestReaderRole, null)) {
			ioContext.getMemberUtil().member(adminUser, requestReaderRole, approver, null, true);
			ioContext.getMemberUtil().member(adminUser, requestUpdaterRole, approver, null, true);
			ioContext.getMemberUtil().member(adminUser, accountReaderRole, approver, null, true);
			ioContext.getMemberUtil().member(adminUser, roleReaderRole, approver, null, true);
		}

		/// Cleanup any prior requests from this requester
		cleanupRequestsForUser(approver, requester);

		/// Create a target role that the requester wants access to
		BaseRecord targetRole = ioContext.getPathUtil().makePath(requester, ModelNames.MODEL_ROLE, "~/Approval Roles/Target Role", RoleEnumType.USER.toString(), orgId);
		assertNotNull("Target role is null", targetRole);

		boolean error = false;
		try {
			/// ──────────────────────────────────────────────────────
			/// Step 1: Create the approval policy structure
			/// Policy (ALL) → Rule (ALL) → Pattern (APPROVAL, AccessApprovalOperation)
			///   SourceFact (PARAMETER) = access request id
			///   MatchFact (OPERATION, LookupOwnerOperation) = resolves owner as approver
			/// ──────────────────────────────────────────────────────

			logger.info("Step 1: Building approval policy");

			/// Create the operation record for AccessApprovalOperation
			OperationType approvalOp = getOrCreateOperation(adminUser, "Test Approval Operation",
				"org.cote.accountmanager.policy.operation.AccessApprovalOperation", orgId);
			assertNotNull("Approval operation is null", approvalOp);

			/// Create the operation record for LookupOwnerOperation
			OperationType lookupOwnerOp = getOrCreateOperation(adminUser, "Test Lookup Owner Operation",
				"org.cote.accountmanager.policy.operation.LookupOwnerOperation", orgId);
			assertNotNull("Lookup owner operation is null", lookupOwnerOp);

			/// Create source fact (PARAMETER — carries the access request reference)
			FactType sourceFact = getOrCreateFact(adminUser, "Test Approval Source Fact", orgId);
			sourceFact.setType(FactEnumType.PARAMETER);
			sourceFact.set(FieldNames.FIELD_FACT_DATA_TYPE, ModelNames.MODEL_ACCESS_REQUEST);
			Queue.queue(sourceFact);

			/// Create match fact (OPERATION — runs LookupOwnerOperation to resolve approver)
			FactType matchFact = getOrCreateFact(adminUser, "Test Approval Match Fact", orgId);
			matchFact.setType(FactEnumType.OPERATION);
			matchFact.set(FieldNames.FIELD_MODEL_TYPE, ModelNames.MODEL_USER);
			Queue.queue(matchFact);

			Queue.processQueue(adminUser);

			/// Create the APPROVAL pattern — this dispatches to AccessApprovalOperation
			PatternType approvalPattern = getOrCreatePattern(adminUser, "Test Approval Pattern", approvalOp, orgId);
			approvalPattern.setType(PatternEnumType.APPROVAL);
			approvalPattern.setFact(sourceFact);
			approvalPattern.setMatch(matchFact);
			approvalPattern.set(FieldNames.FIELD_OPERATION, approvalOp);
			Queue.queue(approvalPattern);

			Queue.processQueue(adminUser);

			/// Create the rule (ALL condition)
			RuleType approvalRule = getOrCreateRule(adminUser, "Test Approval Rule", orgId);
			IOSystem.getActiveContext().getMemberUtil().member(adminUser, approvalRule, approvalPattern, null, true);

			/// Create the policy (ALL condition, enabled)
			PolicyType approvalPolicy = getOrCreatePolicy(adminUser, "Test Approval Policy", orgId);
			IOSystem.getActiveContext().getMemberUtil().member(adminUser, approvalPolicy, approvalRule, null, true);

			/// Re-read the fully populated policy
			Query polQuery = QueryUtil.createQuery(ModelNames.MODEL_POLICY, FieldNames.FIELD_OBJECT_ID, approvalPolicy.get(FieldNames.FIELD_OBJECT_ID));
			polQuery.planMost(true);
			QueryResult polQr = IOSystem.getActiveContext().getSearch().find(polQuery);
			assertTrue("Expected to find the approval policy", polQr != null && polQr.getResults().length > 0);
			PolicyType fullPolicy = new PolicyType(polQr.getResults()[0]);

			/// ──────────────────────────────────────────────────────
			/// Step 2: Create an access request
			/// ──────────────────────────────────────────────────────

			logger.info("Step 2: Submitting access request");

			BaseRecord accessReq = newAccessRequest(requester, requester, requester, ActionEnumType.ADD, requester, null, targetRole, 0L);
			assertNotNull("Access request template is null", accessReq);
			BaseRecord createdReq = ioContext.getAccessPoint().create(requester, accessReq);
			assertNotNull("Created access request is null", createdReq);

			/// Read the full request to get its id
			long reqId = createdReq.get(FieldNames.FIELD_ID);
			String reqObjectId = createdReq.get(FieldNames.FIELD_OBJECT_ID);
			assertTrue("Request id should be > 0", reqId > 0L);
			logger.info("Created access request id=" + reqId + " objectId=" + reqObjectId);

			/// ──────────────────────────────────────────────────────
			/// Step 3: Evaluate policy — should return PENDING_OPERATION
			/// ──────────────────────────────────────────────────────

			logger.info("Step 3: Evaluating policy (expecting PENDING_OPERATION)");

			/// Build a policy request with the access request id in the source fact
			PolicyRequestType prt = new PolicyRequestType(RecordFactory.model(ModelNames.MODEL_POLICY_REQUEST).newInstance());
			prt.set(FieldNames.FIELD_URN, fullPolicy.get(FieldNames.FIELD_URN));
			prt.set(FieldNames.FIELD_ORGANIZATION_PATH, testOrgContext.getOrganizationPath());
			prt.set(FieldNames.FIELD_CONTEXT_USER, adminUser);

			/// Add the source fact parameter with the access request id
			FactType reqFact = new FactType(RecordFactory.newInstance(ModelNames.MODEL_FACT));
			reqFact.setType(FactEnumType.PARAMETER);
			reqFact.set(FieldNames.FIELD_URN, sourceFact.get(FieldNames.FIELD_URN));
			reqFact.set(FieldNames.FIELD_NAME, sourceFact.get(FieldNames.FIELD_NAME));
			reqFact.set(FieldNames.FIELD_FACT_DATA, Long.toString(reqId));
			reqFact.set(FieldNames.FIELD_FACT_DATA_TYPE, ModelNames.MODEL_ACCESS_REQUEST);
			List<BaseRecord> facts = prt.get(FieldNames.FIELD_FACTS);
			facts.add(reqFact);

			PolicyEvaluator pe = ioContext.getPolicyEvaluator();
			pe.setTrace(true);

			PolicyResponseType prr = new PolicyResponseType(pe.evaluatePolicyRequest(prt, fullPolicy));
			String responseType = prr.get(FieldNames.FIELD_TYPE);
			logger.info("Policy response: " + responseType);
			assertTrue("Expected PENDING_OPERATION, got " + responseType,
				PolicyResponseEnumType.PENDING_OPERATION.toString().equals(responseType));

			/// ──────────────────────────────────────────────────────
			/// Step 4: Simulate approver response
			/// Create a RESPONDED spool entry with "APPROVE" in the name
			/// ──────────────────────────────────────────────────────

			logger.info("Step 4: Simulating approver response (APPROVE)");

			/// The approver is the owner of targetRole — which is the requester in this test
			long ownerId = targetRole.get(FieldNames.FIELD_OWNER_ID);
			logger.info("Target role owner id: " + ownerId);

			BaseRecord spoolMsg = RecordFactory.newInstance(ModelNames.MODEL_SPOOL);
			spoolMsg.set(FieldNames.FIELD_NAME, "APPROVE");
			spoolMsg.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.APPROVAL.toString());
			spoolMsg.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.ACCESS.toString());
			spoolMsg.set(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.RESPONDED.toString());
			spoolMsg.set("parentObjectId", reqObjectId);
			spoolMsg.set("senderId", ownerId);
			spoolMsg.set("senderType", ModelNames.MODEL_USER);
			spoolMsg.set(FieldNames.FIELD_OBJECT_ID, UUID.randomUUID().toString());
			spoolMsg.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			spoolMsg.set(FieldNames.FIELD_DATA, ("APPROVE:" + reqObjectId).getBytes());

			boolean spoolCreated = ioContext.getRecordUtil().createRecord(spoolMsg);
			assertTrue("Failed to create approval spool entry", spoolCreated);
			logger.info("Created APPROVE spool entry for request " + reqObjectId);

			/// ──────────────────────────────────────────────────────
			/// Step 5: Re-evaluate policy — should return PERMIT
			/// ──────────────────────────────────────────────────────

			logger.info("Step 5: Re-evaluating policy (expecting PERMIT)");

			/// Rebuild the policy request
			PolicyRequestType prt2 = new PolicyRequestType(RecordFactory.model(ModelNames.MODEL_POLICY_REQUEST).newInstance());
			prt2.set(FieldNames.FIELD_URN, fullPolicy.get(FieldNames.FIELD_URN));
			prt2.set(FieldNames.FIELD_ORGANIZATION_PATH, testOrgContext.getOrganizationPath());
			prt2.set(FieldNames.FIELD_CONTEXT_USER, adminUser);

			FactType reqFact2 = new FactType(RecordFactory.newInstance(ModelNames.MODEL_FACT));
			reqFact2.setType(FactEnumType.PARAMETER);
			reqFact2.set(FieldNames.FIELD_URN, sourceFact.get(FieldNames.FIELD_URN));
			reqFact2.set(FieldNames.FIELD_NAME, sourceFact.get(FieldNames.FIELD_NAME));
			reqFact2.set(FieldNames.FIELD_FACT_DATA, Long.toString(reqId));
			reqFact2.set(FieldNames.FIELD_FACT_DATA_TYPE, ModelNames.MODEL_ACCESS_REQUEST);
			List<BaseRecord> facts2 = prt2.get(FieldNames.FIELD_FACTS);
			facts2.add(reqFact2);

			PolicyResponseType prr2 = new PolicyResponseType(pe.evaluatePolicyRequest(prt2, fullPolicy));
			String responseType2 = prr2.get(FieldNames.FIELD_TYPE);
			logger.info("Policy response after approval: " + responseType2);
			assertTrue("Expected PERMIT after approval, got " + responseType2,
				PolicyResponseEnumType.PERMIT.toString().equals(responseType2));

			pe.setTrace(false);

			/// ──────────────────────────────────────────────────────
			/// Step 6: Verify auto-provisioning can be triggered
			/// ──────────────────────────────────────────────────────

			logger.info("Step 6: Verifying auto-provisioning");

			/// Read the full access request with subject and entitlement
			Query arq = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST, FieldNames.FIELD_OBJECT_ID, reqObjectId);
			arq.planMost(false);
			BaseRecord fullReq = ioContext.getAccessPoint().find(adminUser, arq);
			assertNotNull("Full access request is null", fullReq);

			BaseRecord subject = fullReq.get(FieldNames.FIELD_SUBJECT);
			BaseRecord entitlement = fullReq.get(FieldNames.FIELD_ENTITLEMENT);
			String entType = fullReq.get(FieldNames.FIELD_ENTITLEMENT_TYPE);
			assertNotNull("Subject is null", subject);
			assertNotNull("Entitlement is null", entitlement);

			long subjectId = subject.get(FieldNames.FIELD_ID);
			long entId = entitlement.get(FieldNames.FIELD_ID);
			assertTrue("Subject id should be > 0", subjectId > 0L);
			assertTrue("Entitlement id should be > 0", entId > 0L);

			/// Read the full records for provisioning
			String subjectType = fullReq.get(FieldNames.FIELD_SUBJECT_TYPE);
			if(subjectType == null) subjectType = ModelNames.MODEL_USER;
			BaseRecord fullSubject = ioContext.getReader().read(subjectType, subjectId);
			BaseRecord fullEntitlement = ioContext.getReader().read(entType, entId);
			assertNotNull("Full subject is null", fullSubject);
			assertNotNull("Full entitlement is null", fullEntitlement);

			/// Auto-provision: add subject as member of entitlement
			boolean provisioned = ioContext.getMemberUtil().member(adminUser, fullEntitlement, fullSubject, null, true);
			assertTrue("Auto-provisioning should succeed", provisioned);

			/// Verify membership
			boolean isMember = ioContext.getMemberUtil().isMember(fullSubject, fullEntitlement, null);
			assertTrue("Subject should now be a member of the entitlement", isMember);

			logger.info("=== TestApprovalFlow PASSED ===");

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertTrue("Error encountered during approval flow test", !error);
	}

	/// ── Helper methods for policy structure creation ──

	private OperationType getOrCreateOperation(BaseRecord user, String name, String className, long orgId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Operations", "DATA", orgId);
		OperationType ope = null;
		try {
			BaseRecord[] existing = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_OPERATION, dir.get(FieldNames.FIELD_ID), name);
			if(existing.length > 0) {
				return new OperationType(existing[0]);
			}
			ope = new OperationType();
			ope.setType(OperationEnumType.INTERNAL);
			ope.setOperation(className);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, ope, name, "~/Operations", orgId);
			IOSystem.getActiveContext().getRecordUtil().createRecord(ope);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return ope;
	}

	private FactType getOrCreateFact(BaseRecord user, String name, long orgId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Facts", "DATA", orgId);
		FactType fac = null;
		try {
			BaseRecord[] existing = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_FACT, dir.get(FieldNames.FIELD_ID), name);
			if(existing.length > 0) {
				return new FactType(existing[0]);
			}
			fac = new FactType();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, fac, name, "~/Facts", orgId);
			IOSystem.getActiveContext().getRecordUtil().createRecord(fac);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return fac;
	}

	private PatternType getOrCreatePattern(BaseRecord user, String name, OperationType op, long orgId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Patterns", "DATA", orgId);
		PatternType pat = null;
		try {
			BaseRecord[] existing = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_PATTERN, dir.get(FieldNames.FIELD_ID), name);
			if(existing.length > 0) {
				return new PatternType(existing[0]);
			}
			pat = new PatternType();
			pat.setType(PatternEnumType.APPROVAL);
			pat.set(FieldNames.FIELD_OPERATION, op);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, pat, name, "~/Patterns", orgId);
			IOSystem.getActiveContext().getRecordUtil().createRecord(pat);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return pat;
	}

	private RuleType getOrCreateRule(BaseRecord user, String name, long orgId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Rules", "DATA", orgId);
		RuleType rul = null;
		try {
			BaseRecord[] existing = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_RULE, dir.get(FieldNames.FIELD_ID), name);
			if(existing.length > 0) {
				return new RuleType(existing[0]);
			}
			rul = new RuleType();
			rul.setType(RuleEnumType.PERMIT);
			rul.setCondition(ConditionEnumType.ALL);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, rul, name, "~/Rules", orgId);
			IOSystem.getActiveContext().getRecordUtil().createRecord(rul);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return rul;
	}

	private PolicyType getOrCreatePolicy(BaseRecord user, String name, long orgId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Policies", "DATA", orgId);
		PolicyType pol = null;
		try {
			BaseRecord[] existing = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_POLICY, dir.get(FieldNames.FIELD_ID), name);
			if(existing.length > 0) {
				return new PolicyType(existing[0]);
			}
			pol = new PolicyType();
			pol.setEnabled(true);
			pol.setCondition(ConditionEnumType.ALL);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, pol, name, "~/Policies", orgId);
			IOSystem.getActiveContext().getRecordUtil().createRecord(pol);
		} catch (ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return pol;
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
