package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.AccessRequestService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// Core REST audit item 3-3 (2026-10-07): PATCH /rest/access/requests/{objectId} carried a WebSocket
/// notification stub, returned a silent `200 false` when PBAC refused the update, ran its approval
/// side effects (response spool + entitlement provisioning) BEFORE the PBAC-checked update, and
/// loaded the request with common fields only so every $flex party reference was null (which is
/// why provisioning was a silent no-op and no recipient could ever have been resolved).
///
/// Exercised through the HttpServletRequestMock + UserPrincipal pattern of TestFeatureConfigService:
/// the real service methods, the real AccessPoint/PBAC, the real database, no HTTP transport. The
/// container's @RolesAllowed and the actual WebSocket delivery are verified against the live Docker
/// stack separately (see the report for item 3-3); what this file proves is the ordering, the status
/// codes, the party projection, the PBAC-checked provisioning and the recipient/payload resolution.
///
/// USERS. Three non-admin users in /Development; the org admin appears only as the setup actor that
/// creates them and grants the system roles (inherently admin acts) and is never an assertion subject.
public class TestAccessRequestService extends BaseTest {

	private static final String REQUESTER_NAME = "arqRequester";
	private static final String APPROVER_NAME = "arqApprover";
	private static final String READER_NAME = "arqReader";

	private final AccessRequestService service = new AccessRequestService();

	private BaseRecord requester;
	private BaseRecord approver;
	private BaseRecord reader;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		BaseRecord admin = orgContext.getAdminUser();

		requester = getCreateUser(REQUESTER_NAME);
		approver = getCreateUser(APPROVER_NAME);
		reader = getCreateUser(READER_NAME);
		assertNotNull("Requester is null", requester);
		assertNotNull("Approver is null", approver);
		assertNotNull("Reader is null", reader);

		/// Same role matrix as Objects7 TestAccessApproval: requesters create; readers read requests and
		/// the user/role references on them; updaters may transition approvalStatus.
		BaseRecord requesters = systemRole(AccessSchema.ROLE_REQUESTERS);
		BaseRecord requestReaders = systemRole(AccessSchema.ROLE_REQUEST_READERS);
		BaseRecord requestUpdaters = systemRole(AccessSchema.ROLE_REQUEST_UPDATERS);
		BaseRecord accountUsersReaders = systemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS);
		BaseRecord roleReaders = systemRole(AccessSchema.ROLE_ROLE_READERS);

		grant(admin, requesters, requester);
		grant(admin, accountUsersReaders, requester);
		grant(admin, roleReaders, requester);

		grant(admin, requestReaders, approver);
		grant(admin, requestUpdaters, approver);
		grant(admin, accountUsersReaders, approver);
		grant(admin, roleReaders, approver);

		grant(admin, requestReaders, reader);
		grant(admin, accountUsersReaders, reader);
		grant(admin, roleReaders, reader);
	}

	private BaseRecord systemRole(String name) {
		BaseRecord r = AccessSchema.getSystemRole(name, RoleEnumType.USER.toString(), orgId);
		assertNotNull("System role " + name + " is null", r);
		return r;
	}

	private void grant(BaseRecord admin, BaseRecord role, BaseRecord user) {
		if(!ioContext.getMemberUtil().isMember(user, role, null)) {
			assertTrue("Failed to grant " + role.get(FieldNames.FIELD_NAME) + " to " + user.get(FieldNames.FIELD_NAME),
				ioContext.getMemberUtil().member(admin, role, user, null, true));
		}
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(Response r) {
		assertNotNull("Response is null", r);
		Object entity = r.getEntity();
		assertNotNull("Response entity is null", entity);
		Map<String, Object> m = JSONUtil.importObject(entity.toString(), LinkedHashMap.class);
		assertNotNull("Response body did not parse as JSON: " + entity, m);
		return m;
	}

	/// A role the given user owns (so that user, and only that user, can update its membership).
	private BaseRecord ownedRole(BaseRecord owner, String label) {
		BaseRecord role = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_ROLE,
			"~/ARQ Roles/" + label + " " + UUID.randomUUID(), RoleEnumType.USER.toString(), orgId);
		assertNotNull("Target role is null", role);
		return role;
	}

	/// Submit as the requester through the service. The nested entitlement MUST carry its own
	/// schema: `entitlement` is a $flex foreign field and RecordDeserializer (deserialize :200-205)
	/// drops a nested object it cannot type ("Unresolved $flex reference"), after which
	/// submitRequest answers 400 "Entitlement is required". (Ux752 accessRequests.js:166 currently
	/// sends `entitlement: { id }` without the schema — see the report.)
	private String submit(BaseRecord role) {
		Map<String, Object> ent = new LinkedHashMap<>();
		ent.put("schema", ModelNames.MODEL_ROLE);
		ent.put(FieldNames.FIELD_ID, (long) role.get(FieldNames.FIELD_ID));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", ModelNames.MODEL_ACCESS_REQUEST);
		m.put(FieldNames.FIELD_ACTION, "add");
		m.put(FieldNames.FIELD_ENTITLEMENT, ent);
		m.put(FieldNames.FIELD_ENTITLEMENT_TYPE, ModelNames.MODEL_ROLE);
		m.put(FieldNames.FIELD_DESCRIPTION, "TestAccessRequestService");
		Response r = service.submitRequest(JSONUtil.exportObject(m), requestAs(requester));
		assertEquals("submit should be 201, body: " + r.getEntity(), 201, r.getStatus());
		String oid = (String) body(r).get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("submit returned no objectId", oid);
		return oid;
	}

	private String patchJson(String status, String description) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", ModelNames.MODEL_ACCESS_REQUEST);
		if(status != null) {
			m.put(FieldNames.FIELD_APPROVAL_STATUS, status);
		}
		if(description != null) {
			m.put(FieldNames.FIELD_DESCRIPTION, description);
		}
		return JSONUtil.exportObject(m);
	}

	/// Fresh (cache=false) read with the service's own projection, as the approver.
	private BaseRecord reread(String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setRequest(AccessRequestService.UPDATE_REQUEST_FIELDS);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(approver, q);
	}

	private String statusOf(BaseRecord req) {
		assertNotNull("Request is null", req);
		return req.get(FieldNames.FIELD_APPROVAL_STATUS);
	}

	/// ------------------------------------------------------------------------------------------

	/// The projection the service now uses must actually populate the $flex party references; this
	/// is the precondition for both provisioning and notification.
	@Test
	public void TestProjectionPopulatesParties() {
		BaseRecord role = ownedRole(approver, "Projection");
		String oid = submit(role);

		BaseRecord req = reread(oid);
		assertEquals(ApprovalResponseEnumType.REQUEST.toString(), statusOf(req));
		BaseRecord reqRequester = req.get(FieldNames.FIELD_REQUESTER);
		BaseRecord reqSubject = req.get(FieldNames.FIELD_SUBJECT);
		BaseRecord reqEntitlement = req.get(FieldNames.FIELD_ENTITLEMENT);
		assertNotNull("requester must be projected", reqRequester);
		assertNotNull("subject must be projected", reqSubject);
		assertNotNull("entitlement must be projected", reqEntitlement);
		assertEquals((long) requester.get(FieldNames.FIELD_ID), (long) reqRequester.get(FieldNames.FIELD_ID));
		assertEquals((long) requester.get(FieldNames.FIELD_ID), (long) reqSubject.get(FieldNames.FIELD_ID));
		assertEquals((long) role.get(FieldNames.FIELD_ID), (long) reqEntitlement.get(FieldNames.FIELD_ID));
		assertEquals(ModelNames.MODEL_ROLE, req.get(FieldNames.FIELD_ENTITLEMENT_TYPE));

		/// Recipient resolution: the requester (also the subject) once, never the actor.
		List<Long> recipients = AccessRequestService.notificationRecipientIds(req, approver);
		assertEquals("requester/subject collapse to one recipient: " + recipients, 1, recipients.size());
		assertEquals((long) requester.get(FieldNames.FIELD_ID), (long) recipients.get(0));
		assertFalse("the actor must not be a recipient", recipients.contains(approver.get(FieldNames.FIELD_ID)));
		/// ...and when the requester is the actor (e.g. withdrawing their own request) nobody is left.
		assertTrue(AccessRequestService.notificationRecipientIds(req, requester).isEmpty());

		/// Payload shape consumed by Ux752 pageClient.js.
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = JSONUtil.importObject(
			AccessRequestService.notificationPayload(req, approver, ApprovalResponseEnumType.APPROVE, Boolean.TRUE), LinkedHashMap.class);
		assertEquals(oid, payload.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("APPROVE", payload.get(FieldNames.FIELD_APPROVAL_STATUS));
		assertEquals(((Number) payload.get("requesterId")).longValue(), (long) requester.get(FieldNames.FIELD_ID));
		assertEquals(((Number) payload.get("approverId")).longValue(), (long) approver.get(FieldNames.FIELD_ID));
		assertEquals(((Number) payload.get("entitlementId")).longValue(), (long) role.get(FieldNames.FIELD_ID));
		assertEquals(Boolean.TRUE, payload.get("provisioned"));
	}

	/// Approve as an authorized approver who owns the entitlement: status flips, the subject is
	/// provisioned through PBAC, and the response reports it. No WebSocket session exists in-process,
	/// so notified is 0 (delivery is covered by the live-stack check).
	@Test
	public void TestApproveProvisionsThroughPbac() {
		BaseRecord role = ownedRole(approver, "Approve");
		String oid = submit(role);
		assertFalse("precondition: requester must not already hold the role",
			ioContext.getMemberUtil().isMember(requester, role, null));

		Response r = service.updateRequest(oid, patchJson("APPROVE", null), requestAs(approver));
		assertEquals("approve should be 200, body: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals(Boolean.TRUE, b.get("updated"));
		assertEquals("APPROVE", b.get(FieldNames.FIELD_APPROVAL_STATUS));
		assertEquals("approver owns the role, so provisioning must succeed", Boolean.TRUE, b.get("provisioned"));
		assertEquals(0, ((Number) b.get("notified")).intValue());

		assertEquals(ApprovalResponseEnumType.APPROVE.toString(), statusOf(reread(oid)));
		assertTrue("requester must now be a member of the approved role",
			ioContext.getMemberUtil().isMember(requester, role, null));
	}

	/// A reader (RequestReaders, no RequestUpdaters/Approvers) can read the request but PBAC must
	/// refuse the status change: 403 with a reason, status unchanged, and - the ordering fix - nothing
	/// provisioned.
	@Test
	public void TestReaderIsDeniedAndNothingIsProvisioned() {
		BaseRecord role = ownedRole(approver, "Deny Reader");
		String oid = submit(role);

		Response r = service.updateRequest(oid, patchJson("APPROVE", null), requestAs(reader));
		assertEquals("reader approve must be 403, body: " + r.getEntity(), 403, r.getStatus());
		assertNotNull("403 must carry an error reason", body(r).get("error"));

		assertEquals("status must be unchanged after a denied update",
			ApprovalResponseEnumType.REQUEST.toString(), statusOf(reread(oid)));
		assertFalse("a denied approval must not provision the entitlement",
			ioContext.getMemberUtil().isMember(requester, role, null));
	}

	/// Deny with a comment: status + description change, no provisioning, no 'provisioned' key.
	@Test
	public void TestDenyDoesNotProvision() {
		BaseRecord role = ownedRole(approver, "Deny");
		String oid = submit(role);

		Response r = service.updateRequest(oid, patchJson("deny", "not this quarter"), requestAs(approver));
		assertEquals("deny should be 200, body: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals("DENY", b.get(FieldNames.FIELD_APPROVAL_STATUS));
		assertNull("deny must not report a provisioning outcome", b.get("provisioned"));

		BaseRecord req = reread(oid);
		assertEquals(ApprovalResponseEnumType.DENY.toString(), statusOf(req));
		assertFalse(ioContext.getMemberUtil().isMember(requester, role, null));
	}

	/// The approver may transition the request but has no update right on an entitlement someone
	/// else owns: the request is APPROVED, provisioning is refused by PBAC (not performed as a
	/// bypass), and the response says so.
	@Test
	public void TestApproveWithoutEntitlementRightsReportsUnprovisioned() {
		BaseRecord role = ownedRole(requester, "Foreign Entitlement");
		String oid = submit(role);

		Response r = service.updateRequest(oid, patchJson("APPROVE", null), requestAs(approver));
		assertEquals("approve should be 200, body: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals("APPROVE", b.get(FieldNames.FIELD_APPROVAL_STATUS));
		assertEquals("approver does not own the role, so PBAC must refuse provisioning", Boolean.FALSE, b.get("provisioned"));

		assertEquals(ApprovalResponseEnumType.APPROVE.toString(), statusOf(reread(oid)));
		assertFalse("no membership may be written without authorization on the entitlement",
			ioContext.getMemberUtil().isMember(requester, role, null));
	}

	/// The requester owns the request record but holds neither RequestUpdaters nor Approvers, so
	/// ownership alone must not let them approve their own request: 403, status unchanged, and the
	/// entitlement (which the requester also owns, so provisioning WOULD succeed if reached) not granted.
	@Test
	public void TestRequesterCannotApproveOwnRequest() {
		BaseRecord role = ownedRole(requester, "Self Approve");
		String oid = submit(role);
		assertFalse("precondition: requester must not already hold the role",
			ioContext.getMemberUtil().isMember(requester, role, null));

		Response r = service.updateRequest(oid, patchJson("APPROVE", null), requestAs(requester));
		assertEquals("self-approval must be 403, body: " + r.getEntity(), 403, r.getStatus());
		assertNotNull("403 must carry an error reason", body(r).get("error"));

		assertEquals("status must be unchanged after a refused self-approval",
			ApprovalResponseEnumType.REQUEST.toString(), statusOf(reread(oid)));
		assertFalse("a refused self-approval must not provision the entitlement",
			ioContext.getMemberUtil().isMember(requester, role, null));
	}

	/// Input validation and not-found paths answer 4xx with a JSON reason instead of `200 false`.
	@Test
	public void TestBadInputAndNotFound() {
		BaseRecord role = ownedRole(approver, "Bad Input");
		String oid = submit(role);

		Response bogus = service.updateRequest(oid, patchJson("BOGUS", null), requestAs(approver));
		assertEquals("unknown status must be 400", 400, bogus.getStatus());
		assertNotNull(body(bogus).get("error"));

		Response empty = service.updateRequest(oid, patchJson(null, null), requestAs(approver));
		assertEquals("empty patch must be 400", 400, empty.getStatus());

		Response missing = service.updateRequest(UUID.randomUUID().toString(), patchJson("APPROVE", null), requestAs(approver));
		assertEquals("unknown objectId must be 404", 404, missing.getStatus());
		assertNotNull(body(missing).get("error"));

		assertEquals("no principal must be 401", 401,
			service.updateRequest(oid, patchJson("APPROVE", null), new HttpServletRequestMock()).getStatus());

		assertEquals("none of the rejected calls may have changed the request",
			ApprovalResponseEnumType.REQUEST.toString(), statusOf(reread(oid)));
	}
}
