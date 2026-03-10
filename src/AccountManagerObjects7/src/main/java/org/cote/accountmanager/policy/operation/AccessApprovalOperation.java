package org.cote.accountmanager.policy.operation;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.SpoolStatusEnumType;
import org.cote.accountmanager.policy.FactUtil;

/**
 * AccessApprovalOperation checks the access request's messages spool for an approval response
 * from the pattern's target approver.
 *
 * The sourceFact carries the access request (via factData pointing to the request id, or sourceUrn).
 * The referenceFact (matchFact) identifies the approver target.
 *
 * If an approval response is found:
 *   - RESPONDED status with "APPROVE" in name → SUCCEEDED
 *   - RESPONDED status with "DENY" in name → FAILED
 * If no response is found:
 *   - Creates a notification spool entry targeting the approver
 *   - Returns PENDING
 */
public class AccessApprovalOperation extends Operation {

	public AccessApprovalOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {
		OperationResponseEnumType ort = OperationResponseEnumType.UNKNOWN;

		try {
			/// Resolve the access request from the source fact
			BaseRecord accessRequest = resolveAccessRequest(sourceFact);
			if(accessRequest == null) {
				logger.error("Failed to resolve access request from source fact");
				return OperationResponseEnumType.ERROR;
			}

			/// Resolve the approver target from the reference fact (match fact)
			long approverId = resolveApproverId(referenceFact);
			String approverType = referenceFact.get(FieldNames.FIELD_MODEL_TYPE);
			if(approverId == 0L) {
				logger.error("Failed to resolve approver from reference fact");
				return OperationResponseEnumType.ERROR;
			}

			/// Get the access request's objectId for correlating spool messages
			String requestObjectId = accessRequest.get(FieldNames.FIELD_OBJECT_ID);
			if(requestObjectId == null) {
				logger.error("Access request objectId is null");
				return OperationResponseEnumType.ERROR;
			}

			/// Check existing spool messages for a response from this approver
			ort = checkApprovalResponse(requestObjectId, approverId, approverType);
			if(ort != OperationResponseEnumType.UNKNOWN) {
				return ort;
			}

			/// No response found — check if we already sent a notification
			if(hasExistingNotification(requestObjectId, approverId, approverType)) {
				/// Already notified, still waiting
				return OperationResponseEnumType.PENDING;
			}

			/// Create a notification spool entry for the approver
			createApprovalNotification(accessRequest, approverId, approverType, pattern);
			ort = OperationResponseEnumType.PENDING;

		} catch (Exception e) {
			logger.error(e);
			ort = OperationResponseEnumType.ERROR;
		}

		return ort;
	}

	/**
	 * Resolve the access request record from the source fact.
	 * The fact's factData or sourceUrn points to the access request.
	 */
	protected BaseRecord resolveAccessRequest(BaseRecord sourceFact) throws ReaderException {
		String sdat = sourceFact.get(FieldNames.FIELD_FACT_DATA);
		String surn = sourceFact.get(FieldNames.FIELD_SOURCE_URN);
		String stype = sourceFact.get(FieldNames.FIELD_FACT_DATA_TYPE);

		BaseRecord rec = null;
		if(sdat != null && FactUtil.idPattern.matcher(sdat).matches()) {
			long rid = Long.parseLong(sdat);
			if(rid > 0L) {
				String modelType = (stype != null) ? stype : ModelNames.MODEL_ACCESS_REQUEST;
				rec = reader.read(modelType, rid);
			}
		}
		else if(surn != null) {
			BaseRecord[] recs = search.findByUrn(ModelNames.MODEL_ACCESS_REQUEST, surn);
			if(recs.length > 0) {
				rec = recs[0];
			}
		}
		if(rec != null) {
			reader.populate(rec);
		}
		return rec;
	}

	/**
	 * Resolve the approver's ID from the reference fact.
	 */
	protected long resolveApproverId(BaseRecord referenceFact) throws ReaderException {
		String mdat = referenceFact.get(FieldNames.FIELD_FACT_DATA);
		String murn = referenceFact.get(FieldNames.FIELD_SOURCE_URN);
		String mtype = referenceFact.get(FieldNames.FIELD_MODEL_TYPE);

		if(mdat != null && FactUtil.idPattern.matcher(mdat).matches()) {
			return Long.parseLong(mdat);
		}
		if(murn != null && mtype != null) {
			BaseRecord[] recs = search.findByUrn(mtype, murn);
			if(recs.length > 0) {
				return recs[0].get(FieldNames.FIELD_ID);
			}
		}
		return 0L;
	}

	/**
	 * Check existing spool messages for an approval response from the specified approver.
	 * Returns SUCCEEDED if approved, FAILED if denied, UNKNOWN if no response found.
	 */
	protected OperationResponseEnumType checkApprovalResponse(String requestObjectId, long approverId, String approverType) {
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_SPOOL, FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.APPROVAL.toString());
			q.field("parentObjectId", requestObjectId);
			q.field("senderId", approverId);
			q.field(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.RESPONDED.toString());
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_SPOOL_STATUS, "senderId", "parentObjectId"});

			BaseRecord[] results = search.find(q).getResults();
			if(results.length > 0) {
				String name = results[0].get(FieldNames.FIELD_NAME);
				if(name != null) {
					if(name.contains("APPROVE")) {
						return OperationResponseEnumType.SUCCEEDED;
					}
					else if(name.contains("DENY")) {
						return OperationResponseEnumType.FAILED;
					}
				}
			}
		} catch (Exception e) {
			logger.error("Error checking approval response: " + e.getMessage());
		}
		return OperationResponseEnumType.UNKNOWN;
	}

	/**
	 * Check if a notification has already been sent to this approver for this request.
	 */
	protected boolean hasExistingNotification(String requestObjectId, long approverId, String approverType) {
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_SPOOL, FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.APPROVAL.toString());
			q.field("parentObjectId", requestObjectId);
			q.field("recipientId", approverId);
			q.field(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.ACCESS.toString());
			q.setRequest(new String[] {FieldNames.FIELD_ID});

			BaseRecord[] results = search.find(q).getResults();
			return results.length > 0;
		} catch (Exception e) {
			logger.error("Error checking existing notification: " + e.getMessage());
		}
		return false;
	}

	/**
	 * Create a notification spool message targeting the approver for this access request.
	 */
	protected void createApprovalNotification(BaseRecord accessRequest, long approverId, String approverType, BaseRecord pattern) throws FieldException, ValueException, ModelNotFoundException {
		BaseRecord msg = RecordFactory.newInstance(ModelNames.MODEL_SPOOL);
		msg.set(FieldNames.FIELD_NAME, "Approval Request");
		msg.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.APPROVAL.toString());
		msg.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.ACCESS.toString());
		msg.set(FieldNames.FIELD_SPOOL_STATUS, SpoolStatusEnumType.SPOOLED.toString());
		msg.set("parentObjectId", accessRequest.get(FieldNames.FIELD_OBJECT_ID));
		msg.set("recipientId", approverId);
		msg.set("recipientType", approverType != null ? approverType : ModelNames.MODEL_USER);
		msg.set(FieldNames.FIELD_OBJECT_ID, UUID.randomUUID().toString());

		/// Set the sender as the requester from the access request
		long requesterId = 0L;
		BaseRecord requester = accessRequest.get(FieldNames.FIELD_REQUESTER);
		if(requester != null) {
			requesterId = requester.get(FieldNames.FIELD_ID);
		}
		msg.set("senderId", requesterId);
		msg.set("senderType", accessRequest.get(FieldNames.FIELD_REQUESTER_TYPE));

		long orgId = accessRequest.get(FieldNames.FIELD_ORGANIZATION_ID);
		msg.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);

		/// Store notification data: the access request objectId for correlation
		String notifData = "accessRequest:" + accessRequest.get(FieldNames.FIELD_OBJECT_ID);
		msg.set(FieldNames.FIELD_DATA, notifData.getBytes());

		IOSystem.getActiveContext().getRecordUtil().createRecord(msg);

		if(logger.isDebugEnabled()) {
			logger.debug("Created approval notification for approver " + approverId + " on request " + accessRequest.get(FieldNames.FIELD_OBJECT_ID));
		}
	}
}
