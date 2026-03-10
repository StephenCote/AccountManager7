package org.cote.rest.services;

import java.util.List;

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
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;

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

	/**
	 * Approve, deny, or update an access request.
	 *
	 * Expected JSON body:
	 * {
	 *   "approvalStatus": "APPROVE|DENY|REMOVE",
	 *   "description": "optional comment"
	 * }
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
				return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
			}

			/// Find the existing access request
			Query q = QueryUtil.createQuery(ModelNames.MODEL_ACCESS_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
			BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if(existing == null) {
				return Response.status(404).entity("{\"error\":\"Access request not found\"}").build();
			}

			/// Build a patch with only identity + changed fields
			BaseRecord patch = existing.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});

			/// Update approval status if provided
			String statusStr = imp.get(FieldNames.FIELD_APPROVAL_STATUS);
			if(statusStr != null) {
				ApprovalResponseEnumType newStatus = ApprovalResponseEnumType.valueOf(statusStr.toUpperCase());
				patch.set(FieldNames.FIELD_APPROVAL_STATUS, newStatus);

				/// If approving, create a response spool entry for the policy evaluator
				if(newStatus == ApprovalResponseEnumType.APPROVE || newStatus == ApprovalResponseEnumType.DENY) {
					createApprovalResponse(user, existing, newStatus);
				}

				/// If approved, auto-provision the entitlement
				if(newStatus == ApprovalResponseEnumType.APPROVE) {
					autoProvision(user, existing);
				}
			}

			/// Update description if provided
			String description = imp.get(FieldNames.FIELD_DESCRIPTION);
			if(description != null) {
				patch.set(FieldNames.FIELD_DESCRIPTION, description);
			}

			BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, patch);
			return Response.status(200).entity(updated != null).build();

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
		}
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
	 * Auto-provision the entitlement when an access request is approved.
	 * Adds the subject to the entitlement (role/group/permission).
	 */
	private void autoProvision(BaseRecord approver, BaseRecord accessRequest) {
		try {
			BaseRecord subject = accessRequest.get(FieldNames.FIELD_SUBJECT);
			BaseRecord entitlement = accessRequest.get(FieldNames.FIELD_ENTITLEMENT);
			String entitlementType = accessRequest.get(FieldNames.FIELD_ENTITLEMENT_TYPE);
			String actionStr = accessRequest.get(FieldNames.FIELD_ACTION);

			if(subject == null || entitlement == null || entitlementType == null) {
				logger.warn("Cannot auto-provision — subject, entitlement, or entitlementType is null");
				return;
			}

			long subjectId = subject.get(FieldNames.FIELD_ID);
			long entitlementId = entitlement.get(FieldNames.FIELD_ID);
			if(subjectId <= 0L || entitlementId <= 0L) {
				logger.warn("Cannot auto-provision — subject or entitlement ID is invalid");
				return;
			}

			/// Read full subject and entitlement records
			String subjectType = accessRequest.get(FieldNames.FIELD_SUBJECT_TYPE);
			if(subjectType == null) subjectType = ModelNames.MODEL_USER;

			BaseRecord fullSubject = IOSystem.getActiveContext().getReader().read(subjectType, subjectId);
			BaseRecord fullEntitlement = IOSystem.getActiveContext().getReader().read(entitlementType, entitlementId);

			if(fullSubject == null || fullEntitlement == null) {
				logger.warn("Cannot auto-provision — failed to read subject or entitlement");
				return;
			}

			/// Add the subject as a member of the entitlement
			boolean added = IOSystem.getActiveContext().getMemberUtil().member(approver, fullEntitlement, fullSubject, null, true);
			if(added) {
				logger.info("Auto-provisioned: added " + fullSubject.get(FieldNames.FIELD_NAME) + " to " + fullEntitlement.get(FieldNames.FIELD_NAME));
			}
			else {
				logger.warn("Auto-provision failed or member already exists");
			}

		} catch (Exception e) {
			logger.error("Error during auto-provisioning: " + e.getMessage());
		}
	}
}
