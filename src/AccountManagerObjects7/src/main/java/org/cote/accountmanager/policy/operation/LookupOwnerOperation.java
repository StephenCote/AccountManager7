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
 * LookupOwnerOperation resolves the owner of a resource (entitlement or target object)
 * from the access request and writes the owner's identity into the referenceFact
 * so that subsequent APPROVAL patterns can use it as the approver target.
 *
 * The sourceFact carries the access request (via factData id or sourceUrn).
 * On success, the referenceFact's factData is set to the owner's id and modelType
 * is set to the owner's model type (typically system.user).
 *
 * Returns SUCCEEDED if the owner was resolved, ERROR otherwise.
 */
public class LookupOwnerOperation extends Operation {

	public LookupOwnerOperation(IReader reader, ISearch search) {
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

			/// Get the entitlement from the access request — the owner of the entitlement is the approver
			BaseRecord entitlement = accessRequest.get(FieldNames.FIELD_ENTITLEMENT);
			BaseRecord resource = accessRequest.get(FieldNames.FIELD_RESOURCE);

			/// Determine the target object whose owner we need
			BaseRecord target = null;
			if(entitlement != null) {
				long entId = entitlement.get(FieldNames.FIELD_ID);
				if(entId > 0L) {
					String entType = accessRequest.get(FieldNames.FIELD_ENTITLEMENT_TYPE);
					if(entType != null) {
						target = reader.read(entType, entId);
					}
				}
			}
			if(target == null && resource != null) {
				long resId = resource.get(FieldNames.FIELD_ID);
				if(resId > 0L) {
					String resType = accessRequest.get(FieldNames.FIELD_RESOURCE_TYPE);
					if(resType != null) {
						target = reader.read(resType, resId);
					}
				}
			}

			if(target == null) {
				logger.error("Could not resolve entitlement or resource from access request");
				return OperationResponseEnumType.ERROR;
			}

			/// Get the owner of the target
			long ownerId = target.get(FieldNames.FIELD_OWNER_ID);
			if(ownerId <= 0L) {
				logger.error("Target has no owner");
				return OperationResponseEnumType.ERROR;
			}

			/// Write the owner info into the referenceFact for downstream use
			referenceFact.set(FieldNames.FIELD_FACT_DATA, Long.toString(ownerId));
			referenceFact.set(FieldNames.FIELD_MODEL_TYPE, ModelNames.MODEL_USER);
			referenceFact.set(FieldNames.FIELD_SOURCE_URN, null);

			if(logger.isDebugEnabled()) {
				logger.debug("Resolved owner " + ownerId + " for target " + target.get(FieldNames.FIELD_NAME));
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
