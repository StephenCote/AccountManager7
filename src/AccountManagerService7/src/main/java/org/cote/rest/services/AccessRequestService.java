package org.cote.rest.services;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;
import org.cote.sockets.WebSocketService;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin", "user"})
@Path("/access")
public class AccessRequestService {
	private static final Logger logger = LogManager.getLogger(AccessRequestService.class);

	/**
	 * List access requests with optional filtering.
	 *
	 * @param view "mine" (my requests), "pending" (pending my approval), "all" (admin view)
	 * @param status filter by approval status (e.g. REQUEST, PENDING, APPROVE, DENY)
	 * @param startIndex pagination start
	 * @param count pagination count
	 */
	@RolesAllowed({"user"})
	@GET
	@Path("/requests")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listRequests(
		@QueryParam("view") String view,
		@QueryParam("status") String status,
		@QueryParam("startIndex") int startIndex,
		@QueryParam("count") int count,
		@Context HttpServletRequest request
	) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return Response.status(401).entity(null).build();
		}

		try {
			if(count <= 0) count = 25;
			if(count > 200) count = 200;

			Query q = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST);
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING.toString());
			q.setRequestRange(startIndex, count);

			/// Filter by approval status if provided
			if(status != null && status.length() > 0) {
				q.field(FieldNames.FIELD_APPROVAL_STATUS, status.toUpperCase());
			}
			else {
				/// Default: show open requests (REQUEST status)
				q.field(FieldNames.FIELD_APPROVAL_STATUS, ApprovalResponseEnumType.REQUEST.toString());
			}

			if("pending".equalsIgnoreCase(view)) {
				/// Pending my approval — find requests where I am the approver
				q.field("approverType", ModelNames.MODEL_USER);
				q.field("approver", user.copyRecord(new String[] {FieldNames.FIELD_ID}));
			}
			else if("all".equalsIgnoreCase(view)) {
				/// Admin view — no additional filtering (authorization handles access)
			}
			else {
				/// Default: my requests
				q.field(FieldNames.FIELD_REQUESTER_TYPE, ModelNames.MODEL_USER);
				q.field(FieldNames.FIELD_REQUESTER, user.copyRecord(new String[] {FieldNames.FIELD_ID}));
			}

			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
			if(qr == null) {
				return Response.status(200).entity("[]").build();
			}

			return Response.status(200).entity(JSONUtil.exportObject(qr, RecordSerializerConfig.getForeignUnfilteredModule())).build();

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity(null).build();
		}
	}

	/**
	 * Submit a new access request.
	 *
	 * Expected JSON body:
	 * {
	 *   "action": "ADD|GRANT",
	 *   "subject": { "id": ... },
	 *   "subjectType": "system.user",
	 *   "entitlement": { "id": ... },
	 *   "entitlementType": "auth.role|auth.group|auth.permission",
	 *   "resource": { "id": ... },  (optional)
	 *   "resourceType": "...",       (optional)
	 *   "description": "..."         (optional)
	 * }
	 */
	@RolesAllowed({"user"})
	@POST
	@Path("/requests")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response submitRequest(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return Response.status(401).entity(null).build();
		}

		try {
			BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
			if(imp == null) {
				return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
			}

			/// Extract fields from the submitted JSON
			String actionStr = imp.get(FieldNames.FIELD_ACTION);
			ActionEnumType action = ActionEnumType.UNKNOWN;
			if(actionStr != null) {
				action = ActionEnumType.valueOf(actionStr.toUpperCase());
			}
			if(action == ActionEnumType.UNKNOWN) {
				action = ActionEnumType.ADD;
			}

			BaseRecord subject = imp.get(FieldNames.FIELD_SUBJECT);
			BaseRecord entitlement = imp.get(FieldNames.FIELD_ENTITLEMENT);
			BaseRecord resource = imp.get(FieldNames.FIELD_RESOURCE);

			if(entitlement == null) {
				return Response.status(400).entity("{\"error\":\"Entitlement is required\"}").build();
			}

			/// If no subject specified, default to the requesting user
			if(subject == null) {
				subject = user;
			}

			/// Build the access request via the factory
			ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_ACTION, action);
			plist.parameter(FieldNames.FIELD_ENTITLEMENT, entitlement);
			plist.parameter(FieldNames.FIELD_RESOURCE, resource);
			plist.parameter(FieldNames.FIELD_SUBMITTER, user);
			plist.parameter(FieldNames.FIELD_REQUESTER, user);
			plist.parameter(FieldNames.FIELD_SUBJECT, subject);
			plist.parameter(FieldNames.FIELD_RESPONSE, ApprovalResponseEnumType.REQUEST.toString());

			BaseRecord accessReq = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ACCESS_REQUEST, user, null, plist);
			if(accessReq == null) {
				return Response.status(500).entity("{\"error\":\"Failed to create access request\"}").build();
			}

			/// Copy description if provided
			String description = imp.get(FieldNames.FIELD_DESCRIPTION);
			if(description != null) {
				accessReq.set(FieldNames.FIELD_DESCRIPTION, description);
			}

			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, accessReq);
			if(created == null) {
				return Response.status(403).entity("{\"error\":\"Not authorized to create access request\"}").build();
			}

			return Response.status(201).entity(created.copyRecord(new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME
			}).toFullString()).build();

		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/// Projection used when loading an access request for approve/deny/update. The $flex party
	/// references (requester, submitter, subject, approver, delegate, entitlement) are only
	/// materialised when explicitly requested; QueryUtil.createQuery alone returns common fields and
	/// left every party null, which silently disabled auto-provisioning and made notification
	/// impossible.
	public static final String[] UPDATE_REQUEST_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_OWNER_ID,
		FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_APPROVAL_STATUS, FieldNames.FIELD_ACTION,
		FieldNames.FIELD_REQUESTER, FieldNames.FIELD_REQUESTER_TYPE,
		FieldNames.FIELD_SUBMITTER, "submitterType",
		FieldNames.FIELD_SUBJECT, FieldNames.FIELD_SUBJECT_TYPE,
		FieldNames.FIELD_ENTITLEMENT, FieldNames.FIELD_ENTITLEMENT_TYPE,
		"approver", "approverType", "delegate", "delegateType"
	};

	/// Chirp name consumed by Ux752 pageClient.js (page.accessRequestListeners).
	public static final String CHIRP_ACCESS_REQUEST_UPDATE = "accessRequestUpdate";

	/**
	 * Approve, deny, or update an access request.
	 *
	 * Expected JSON body:
	 * {
	 *   "schema": "access.accessRequest",
	 *   "approvalStatus": "APPROVE|DENY|REMOVE",
	 *   "description": "optional comment"
	 * }
	 *
	 * Order of operations: the PBAC-checked {@code AccessPoint.update} runs FIRST; the approval spool
	 * entry, entitlement provisioning and the WebSocket notification happen only after it succeeded.
	 * Provisioning itself goes through {@code AccessPoint.member} (the approver must be permitted to
	 * read the subject and update the entitlement) — nothing here bypasses PBAC.
	 *
	 * Responses: 200 {@code {"updated":true,"approvalStatus":..,"provisioned":..,"notified":n}};
	 * 400 bad body/status; 403 the update (or the read of the request) was denied;
	 * 404 no such request; 422 the update was not applied for a non-policy reason (audit message included).
	 */
	@RolesAllowed({"user"})
	@PATCH
	@Path("/requests/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response updateRequest(
		@PathParam("objectId") String objectId,
		String json,
		@Context HttpServletRequest request
	) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return Response.status(401).entity(null).build();
		}

		try {
			BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
			if(imp == null) {
				return Response.status(400).entity(errorBody("Invalid request body")).build();
			}

			/// Validate the requested status before touching anything. Read it from the RAW body: the
			/// deserializer rejects an unknown enum constant (ValueException, logged) but leaves the
			/// field on the record at its default (UNKNOWN), so validating the deserialized value would
			/// turn a typo into a silent status reset.
			String statusStr = rawApprovalStatus(json);
			ApprovalResponseEnumType newStatus = null;
			if(statusStr != null) {
				try {
					newStatus = ApprovalResponseEnumType.valueOf(statusStr.toUpperCase());
				}
				catch(IllegalArgumentException e) {
					return Response.status(400).entity(errorBody("Unknown approvalStatus: " + statusStr)).build();
				}
			}
			String description = imp.hasField(FieldNames.FIELD_DESCRIPTION) ? imp.get(FieldNames.FIELD_DESCRIPTION) : null;
			if(newStatus == null && description == null) {
				return Response.status(400).entity(errorBody("Nothing to update: supply approvalStatus and/or description")).build();
			}

			/// Find the existing access request with its party references. cache(false): an approval
			/// decision must be made against the stored status, not a cached list projection.
			Query q = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
			q.setRequest(UPDATE_REQUEST_FIELDS);
			q.setCache(false);
			AuditUtil.clearLastAudit();
			BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(existing == null) {
				if(AuditUtil.getLastAuditResponse() == ResponseEnumType.DENY) {
					return Response.status(403).entity(errorBody("Not authorized to read access request")).build();
				}
				return Response.status(404).entity(errorBody("Access request not found")).build();
			}

			/// Build a patch with identity + validated name + changed fields only.
			BaseRecord patch = existing.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME});
			if(newStatus != null) {
				patch.set(FieldNames.FIELD_APPROVAL_STATUS, newStatus);
			}
			if(description != null) {
				patch.set(FieldNames.FIELD_DESCRIPTION, description);
			}

			/// PBAC-checked update. Nothing below runs unless this succeeded.
			AuditUtil.clearLastAudit();
			BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, patch);
			if(updated == null) {
				ResponseEnumType ret = AuditUtil.getLastAuditResponse();
				String msg = AuditUtil.getLastAuditMessage();
				if(ret == ResponseEnumType.DENY) {
					return Response.status(403).entity(errorBody("Not authorized to update access request" + (msg != null ? ": " + msg : ""))).build();
				}
				return Response.status(422).entity(errorBody("Access request was not updated" + (msg != null ? ": " + msg : "") + " (" + ret + ")")).build();
			}

			Boolean provisioned = null;
			if(newStatus == ApprovalResponseEnumType.APPROVE || newStatus == ApprovalResponseEnumType.DENY) {
				/// Response spool entry for the policy evaluator (AccessApprovalOperation).
				createApprovalResponse(user, existing, newStatus);
			}
			if(newStatus == ApprovalResponseEnumType.APPROVE) {
				provisioned = autoProvision(user, existing);
			}

			/// Push the change to the other parties' live WebSocket sessions.
			int notified = notifyParties(user, existing, newStatus, provisioned);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("updated", true);
			result.put(FieldNames.FIELD_OBJECT_ID, objectId);
			result.put(FieldNames.FIELD_APPROVAL_STATUS, newStatus != null ? newStatus.toString() : existing.get(FieldNames.FIELD_APPROVAL_STATUS));
			if(provisioned != null) {
				result.put("provisioned", provisioned);
			}
			result.put("notified", notified);
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity(errorBody(e.getMessage())).build();
		}
	}

	private static String errorBody(String msg) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("error", msg);
		return JSONUtil.exportObject(m);
	}

	/// The approvalStatus string exactly as the client sent it (null when absent or not a string).
	public static String rawApprovalStatus(String json) {
		if(json == null || json.isBlank()) {
			return null;
		}
		Map<String, Object> raw = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		if(raw == null) {
			return null;
		}
		Object v = raw.get(FieldNames.FIELD_APPROVAL_STATUS);
		return (v instanceof String && !((String) v).isBlank()) ? (String) v : null;
	}

	/**
	 * Resolve the user parties of an access request (requester, submitter, subject, approver, delegate)
	 * as user ids, excluding {@code actor}. Only {@code system.user} typed parties qualify; a party
	 * whose type is a group/role/person is not a WebSocket recipient.
	 */
	public static List<Long> notificationRecipientIds(BaseRecord accessRequest, BaseRecord actor) {
		List<Long> ids = new ArrayList<>();
		long actorId = actor != null && actor.hasField(FieldNames.FIELD_ID) ? (long) actor.get(FieldNames.FIELD_ID) : 0L;
		String[][] parties = new String[][] {
			{FieldNames.FIELD_REQUESTER, FieldNames.FIELD_REQUESTER_TYPE},
			{FieldNames.FIELD_SUBMITTER, "submitterType"},
			{FieldNames.FIELD_SUBJECT, FieldNames.FIELD_SUBJECT_TYPE},
			{"approver", "approverType"},
			{"delegate", "delegateType"}
		};
		for(String[] party : parties) {
			if(!accessRequest.hasField(party[0])) {
				continue;
			}
			BaseRecord ref = accessRequest.get(party[0]);
			if(ref == null || !ref.hasField(FieldNames.FIELD_ID)) {
				continue;
			}
			String type = accessRequest.hasField(party[1]) ? accessRequest.get(party[1]) : null;
			if(type == null && ref.getSchema() != null) {
				type = ref.getSchema();
			}
			if(type != null && !ModelNames.MODEL_USER.equals(type)) {
				continue;
			}
			long id = ref.get(FieldNames.FIELD_ID);
			if(id <= 0L || id == actorId || ids.contains(id)) {
				continue;
			}
			ids.add(id);
		}
		return ids;
	}

	/**
	 * Build the {@code accessRequestUpdate} chirp payload (chirps[1]) consumed by Ux752
	 * {@code pageClient.js}: {@code {objectId, approvalStatus, requesterId, approverId, subjectId,
	 * entitlementId, entitlementType, provisioned}}. Ids are 0 when the party is absent.
	 */
	public static String notificationPayload(BaseRecord accessRequest, BaseRecord actor, ApprovalResponseEnumType status, Boolean provisioned) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put(FieldNames.FIELD_OBJECT_ID, (String) accessRequest.get(FieldNames.FIELD_OBJECT_ID));
		payload.put(FieldNames.FIELD_APPROVAL_STATUS, status != null ? status.toString() : (String) accessRequest.get(FieldNames.FIELD_APPROVAL_STATUS));
		payload.put("requesterId", refId(accessRequest, FieldNames.FIELD_REQUESTER));
		payload.put("approverId", actor != null && actor.hasField(FieldNames.FIELD_ID) ? (long) actor.get(FieldNames.FIELD_ID) : 0L);
		payload.put("subjectId", refId(accessRequest, FieldNames.FIELD_SUBJECT));
		payload.put("entitlementId", refId(accessRequest, FieldNames.FIELD_ENTITLEMENT));
		payload.put(FieldNames.FIELD_ENTITLEMENT_TYPE, accessRequest.hasField(FieldNames.FIELD_ENTITLEMENT_TYPE) ? (String) accessRequest.get(FieldNames.FIELD_ENTITLEMENT_TYPE) : null);
		if(provisioned != null) {
			payload.put("provisioned", provisioned);
		}
		return JSONUtil.exportObject(payload);
	}

	private static long refId(BaseRecord rec, String field) {
		if(!rec.hasField(field)) {
			return 0L;
		}
		BaseRecord ref = rec.get(field);
		if(ref == null || !ref.hasField(FieldNames.FIELD_ID)) {
			return 0L;
		}
		return ref.get(FieldNames.FIELD_ID);
	}

	/**
	 * Chirp {@code accessRequestUpdate} to every party of the request (other than the actor) that
	 * currently holds a WebSocket session. Recipients are matched by id against the sessions'
	 * in-memory user records ({@code WebSocketService.activeUsers()}), so no DB read and no
	 * authorization bypass is involved; a party without a live session is simply skipped.
	 *
	 * @return the number of sessions chirped
	 */
	public static int notifyParties(BaseRecord actor, BaseRecord accessRequest, ApprovalResponseEnumType status, Boolean provisioned) {
		List<Long> recipients = notificationRecipientIds(accessRequest, actor);
		if(recipients.isEmpty()) {
			return 0;
		}
		String[] chirps = new String[] {CHIRP_ACCESS_REQUEST_UPDATE, notificationPayload(accessRequest, actor, status, provisioned)};
		int sent = 0;
		for(BaseRecord active : WebSocketService.activeUsers()) {
			if(active == null || !active.hasField(FieldNames.FIELD_ID)) {
				continue;
			}
			long aid = active.get(FieldNames.FIELD_ID);
			if(recipients.contains(aid) && WebSocketService.chirpUser(active, chirps)) {
				sent++;
			}
		}
		return sent;
	}

	/**
	 * List requestable resources (roles, groups, permissions).
	 */
	@RolesAllowed({"user"})
	@GET
	@Path("/requestable")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listRequestable(
		@QueryParam("type") String type,
		@QueryParam("startIndex") int startIndex,
		@QueryParam("count") int count,
		@Context HttpServletRequest request
	) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return Response.status(401).entity(null).build();
		}

		try {
			if(count <= 0) count = 25;
			if(count > 200) count = 200;

			String modelType;
			if("group".equalsIgnoreCase(type)) {
				modelType = ModelNames.MODEL_GROUP;
			}
			else if("permission".equalsIgnoreCase(type)) {
				modelType = ModelNames.MODEL_PERMISSION;
			}
			else {
				/// Default to roles
				modelType = ModelNames.MODEL_ROLE;
			}

			Query q = QueryUtil.createQuery(modelType);
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
			q.setRequestRange(startIndex, count);

			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
			if(qr == null) {
				return Response.status(200).entity("[]").build();
			}

			return Response.status(200).entity(JSONUtil.exportObject(qr, RecordSerializerConfig.getForeignUnfilteredModule())).build();

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity(null).build();
		}
	}

	/**
	 * Send a notification/reminder for an access request.
	 */
	@RolesAllowed({"user"})
	@POST
	@Path("/requests/{objectId:[0-9A-Za-z\\-]+}/notify")
	@Produces(MediaType.APPLICATION_JSON)
	public Response notifyRequest(
		@PathParam("objectId") String objectId,
		@Context HttpServletRequest request
	) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if(user == null) {
			return Response.status(401).entity(null).build();
		}

		try {
			/// Find the access request
			Query q = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
			q.planMost(false);
			BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(existing == null) {
				return Response.status(404).entity("{\"error\":\"Access request not found\"}").build();
			}

			/// Get the approver from the request
			BaseRecord approver = existing.get("approver");
			String approverType = existing.get("approverType");
			if(approver == null) {
				return Response.status(400).entity("{\"error\":\"No approver assigned to this request\"}").build();
			}

			long approverId = approver.get(FieldNames.FIELD_ID);

			/// Create a reminder spool entry
			BaseRecord msg = org.cote.accountmanager.record.RecordFactory.newInstance(ModelNames.MODEL_SPOOL);
			msg.set(FieldNames.FIELD_NAME, "Approval Reminder");
			msg.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.APPROVAL.toString());
			msg.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.ACCESS.toString());
			msg.set(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.SPOOLED.toString());
			msg.set("parentObjectId", objectId);
			msg.set("recipientId", approverId);
			msg.set("recipientType", approverType != null ? approverType : ModelNames.MODEL_USER);
			msg.set(FieldNames.FIELD_OBJECT_ID, java.util.UUID.randomUUID().toString());
			msg.set("senderId", (long) user.get(FieldNames.FIELD_ID));
			msg.set("senderType", ModelNames.MODEL_USER);
			msg.set(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
			msg.set(FieldNames.FIELD_DATA, ("Reminder: " + objectId).getBytes());

			boolean created = IOSystem.getActiveContext().getRecordUtil().createRecord(msg);
			return Response.status(200).entity(created).build();

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
	}

	/**
	 * Create an approval response spool entry so the policy evaluator can detect it.
	 */
	private void createApprovalResponse(BaseRecord user, BaseRecord accessRequest, ApprovalResponseEnumType responseType) {
		try {
			BaseRecord msg = org.cote.accountmanager.record.RecordFactory.newInstance(ModelNames.MODEL_SPOOL);
			msg.set(FieldNames.FIELD_NAME, responseType.toString());
			msg.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.APPROVAL.toString());
			msg.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.ACCESS.toString());
			msg.set(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.RESPONDED.toString());
			msg.set("parentObjectId", (String) accessRequest.get(FieldNames.FIELD_OBJECT_ID));
			msg.set("senderId", (long) user.get(FieldNames.FIELD_ID));
			msg.set("senderType", ModelNames.MODEL_USER);
			msg.set(FieldNames.FIELD_OBJECT_ID, java.util.UUID.randomUUID().toString());
			msg.set(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
			msg.set(FieldNames.FIELD_DATA, (responseType.toString() + ":" + accessRequest.get(FieldNames.FIELD_OBJECT_ID)).getBytes());

			IOSystem.getActiveContext().getRecordUtil().createRecord(msg);

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Error creating approval response: " + e.getMessage());
		}
	}

	/**
	 * Provision the entitlement when an access request is approved: add the subject as a member of
	 * the entitlement (role/group/permission). Called only after the approver's status change passed
	 * {@code AccessPoint.update}. Membership is written through {@code AccessPoint.member}, so the
	 * approver must be permitted to read the subject and to update the entitlement; an approver who
	 * is not (e.g. a system role they do not administer) leaves the request APPROVED but
	 * unprovisioned, which is reported back as {@code provisioned:false} rather than performed
	 * without authorization.
	 *
	 * @return true when the membership now exists, false when it could not be established
	 */
	private boolean autoProvision(BaseRecord approver, BaseRecord accessRequest) {
		try {
			BaseRecord subject = accessRequest.hasField(FieldNames.FIELD_SUBJECT) ? accessRequest.get(FieldNames.FIELD_SUBJECT) : null;
			BaseRecord entitlement = accessRequest.hasField(FieldNames.FIELD_ENTITLEMENT) ? accessRequest.get(FieldNames.FIELD_ENTITLEMENT) : null;
			String entitlementType = accessRequest.hasField(FieldNames.FIELD_ENTITLEMENT_TYPE) ? accessRequest.get(FieldNames.FIELD_ENTITLEMENT_TYPE) : null;

			if(subject == null || entitlement == null || entitlementType == null) {
				logger.warn("Cannot auto-provision " + accessRequest.get(FieldNames.FIELD_OBJECT_ID) + ": subject, entitlement, or entitlementType is null");
				return false;
			}

			long subjectId = subject.get(FieldNames.FIELD_ID);
			long entitlementId = entitlement.get(FieldNames.FIELD_ID);
			if(subjectId <= 0L || entitlementId <= 0L) {
				logger.warn("Cannot auto-provision " + accessRequest.get(FieldNames.FIELD_OBJECT_ID) + ": subject or entitlement ID is invalid");
				return false;
			}

			String subjectType = accessRequest.hasField(FieldNames.FIELD_SUBJECT_TYPE) ? accessRequest.get(FieldNames.FIELD_SUBJECT_TYPE) : null;
			if(subjectType == null) subjectType = ModelNames.MODEL_USER;

			/// Authorized reads of the subject and the entitlement as the approver.
			BaseRecord fullSubject = IOSystem.getActiveContext().getAccessPoint().findById(approver, subjectType, subjectId);
			BaseRecord fullEntitlement = IOSystem.getActiveContext().getAccessPoint().findById(approver, entitlementType, entitlementId);
			if(fullSubject == null || fullEntitlement == null) {
				logger.warn("Cannot auto-provision " + accessRequest.get(FieldNames.FIELD_OBJECT_ID) + ": approver " + approver.get(FieldNames.FIELD_NAME) + " cannot read subject or entitlement");
				return false;
			}

			if(IOSystem.getActiveContext().getMemberUtil().isMember(fullSubject, fullEntitlement, null)) {
				logger.info("Auto-provision: " + fullSubject.get(FieldNames.FIELD_NAME) + " is already a member of " + fullEntitlement.get(FieldNames.FIELD_NAME));
				return true;
			}

			/// PBAC-checked membership write (approver needs update on the entitlement).
			boolean added = IOSystem.getActiveContext().getAccessPoint().member(approver, fullEntitlement, fullSubject, null, true);
			if(added) {
				logger.info("Auto-provisioned: added " + fullSubject.get(FieldNames.FIELD_NAME) + " to " + fullEntitlement.get(FieldNames.FIELD_NAME));
				if(ModelNames.MODEL_USER.equals(fullSubject.getSchema())) {
					PrincipalService.evictProfile(fullSubject);
				}
			}
			else {
				logger.warn("Auto-provision of " + accessRequest.get(FieldNames.FIELD_OBJECT_ID) + " was not applied: approver " + approver.get(FieldNames.FIELD_NAME) + " is not authorized to update " + entitlementType + " " + fullEntitlement.get(FieldNames.FIELD_NAME));
			}
			return added;

		} catch (Exception e) {
			logger.error("Error during auto-provisioning: " + e.getMessage());
			return false;
		}
	}
}
