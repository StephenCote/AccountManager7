package org.cote.accountmanager.policy.operation;

import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.policy.FactUtil;

/**
 * LookupApproverOperation resolves an approver from the access request's
 * approver/delegate fields and writes the approver's identity into the referenceFact
 * so that subsequent APPROVAL patterns can use it as the approver target.
 *
 * The sourceFact carries the access request (via factData id or sourceUrn).
 * The referenceFact's factData may contain a preference indicator:
 *   - "delegate" → resolve the delegate field from the access request
 *   - anything else or null → resolve the approver field from the access request
 *
 * On success, the referenceFact's factData is set to the resolved approver's id
 * and modelType is set to the approver's model type.
 *
 * Returns SUCCEEDED if an approver was resolved, FAILED if the field is empty, ERROR on exception.
 */
public class LookupApproverOperation extends Operation {

	public LookupApproverOperation(IReader reader, ISearch search) {
		super(reader, search);
	}

	@Override
	public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
		return null;
	}

	@Override
	public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {
		try {
			/// Resolve the access request from the source fact
			BaseRecord accessRequest = resolveRecord(sourceFact);
			if(accessRequest == null) {
				logger.error("Failed to resolve access request from source fact");
				return OperationResponseEnumType.ERROR;
			}

			/// Determine whether to look up the approver or delegate
			String lookupType = referenceFact.get(FieldNames.FIELD_FACT_DATA);
			boolean isDelegate = (lookupType != null && lookupType.equalsIgnoreCase("delegate"));

			String approverField = isDelegate ? "delegate" : "approver";
			String typeField = isDelegate ? "delegateType" : "approverType";

			BaseRecord approverRef = accessRequest.get(approverField);
			String approverType = accessRequest.get(typeField);

			if(approverRef == null) {
				if(logger.isDebugEnabled()) {
					logger.debug("No " + approverField + " set on access request");
				}
				return OperationResponseEnumType.FAILED;
			}

			long approverId = approverRef.get(FieldNames.FIELD_ID);
			if(approverId <= 0L) {
				logger.error(approverField + " has no valid id");
				return OperationResponseEnumType.FAILED;
			}

			/// Write the approver info into the referenceFact for downstream use
			referenceFact.set(FieldNames.FIELD_FACT_DATA, Long.toString(approverId));
			referenceFact.set(FieldNames.FIELD_MODEL_TYPE, approverType != null ? approverType : ModelNames.MODEL_USER);
			referenceFact.set(FieldNames.FIELD_SOURCE_URN, null);

			if(logger.isDebugEnabled()) {
				logger.debug("Resolved " + approverField + " " + approverId + " (" + approverType + ")");
			}

			return OperationResponseEnumType.SUCCEEDED;

		} catch (Exception e) {
			logger.error(e);
			return OperationResponseEnumType.ERROR;
		}
	}

	/**
	 * Resolve a record from a fact's factData (id) or sourceUrn.
	 */
	private BaseRecord resolveRecord(BaseRecord fact) throws ReaderException {
		String sdat = fact.get(FieldNames.FIELD_FACT_DATA);
		String surn = fact.get(FieldNames.FIELD_SOURCE_URN);
		String stype = fact.get(FieldNames.FIELD_FACT_DATA_TYPE);

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
}
