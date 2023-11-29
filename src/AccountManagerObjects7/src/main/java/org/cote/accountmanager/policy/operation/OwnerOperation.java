
	package org.cote.accountmanager.policy.operation;

	import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.policy.FactUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.RecordUtil;


	public class OwnerOperation extends Operation {
		
		public OwnerOperation(IReader reader, ISearch search) {
			super(reader, search);
		}
		
		@Override
		public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
			// TODO Auto-generated method stub
			return null;
		}
		@Override
		public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {

			OperationResponseEnumType ort = OperationResponseEnumType.UNKNOWN;
			String murn = referenceFact.get(FieldNames.FIELD_SOURCE_URN);
			String mtype = referenceFact.get(FieldNames.FIELD_MODEL_TYPE);
			String surn = sourceFact.get(FieldNames.FIELD_SOURCE_URN);
			String stype = sourceFact.get(FieldNames.FIELD_MODEL_TYPE);
			String sdat = sourceFact.get(FieldNames.FIELD_FACT_DATA);
			
			/*
			if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
				logger.info(sourceFact.toFullString());
				logger.info(referenceFact.toFullString());
			}
			*/
			
			if(stype == null || !ModelNames.MODEL_USER.equals(stype)) {
				logger.error("Source type must refer to a user model: " + stype + " != " + ModelNames.MODEL_USER);
				return OperationResponseEnumType.ERROR;
				
			}
			if(murn == null || mtype == null) {
				logger.error("Reference model urn (" + murn + ") or type (" + mtype + ") was not defined");
				return OperationResponseEnumType.ERROR;
			}

			long ownerId = 0L;
			long contextId = 0L;
			BaseRecord srec = null;
			BaseRecord mrec = null;
			
			if(sdat != null && FactUtil.idPattern.matcher(sdat).matches()) {
				try {
					long rid = Long.parseLong(sdat);
					if(rid > 0L) {
						srec = reader.read(sourceFact.get(FieldNames.FIELD_FACT_DATA_TYPE), rid);
					}
					else {
						logger.info("Skip invalid id");
					}
				}
				catch(NumberFormatException | ReaderException e) {
					logger.error(e);
					return OperationResponseEnumType.ERROR;
				}
			}
			else if(surn != null) {
				try {
					BaseRecord[] recs = search.findByUrn(stype, surn);
					if(recs.length > 0) {
						srec = recs[0];
					}
					else {
						logger.warn("Failed to find urn: " + surn);
					}
				}
				catch(IndexException | ReaderException e0) {
					logger.error(e0);
					return OperationResponseEnumType.ERROR;
				}
			}
			else {
				logger.error("Source urn or data was not defined");
				return OperationResponseEnumType.ERROR;

			}
			if(srec != null) {
				if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
					logger.info(srec.toFullString());
				}
				ownerId = srec.get(FieldNames.FIELD_ID);
			}
			
			
			if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
				logger.info(murn);
			}
			if(murn != null && murn.length() > 0) {
				try {
					String[] flds = RecordUtil.getCommonFields(mtype);
					if(flds.length == 0) {
						flds = new String[] {FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ID};
					}

					boolean bId = FactUtil.idPattern.matcher(murn).matches();
					Query q = QueryUtil.createQuery(mtype, (bId ? FieldNames.FIELD_ID : FieldNames.FIELD_URN), (bId ? Long.parseLong(murn) : murn));
					q.setRequest(flds);
					q.set(FieldNames.FIELD_INSPECT, true);
					
					if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
						logger.info(q.toFullString());
					}
					
					BaseRecord[] recs = search.find(q).getResults();
					if(recs.length > 0) {
						mrec = recs[0];
						if(reader.getRecordIo().equals(RecordIO.FILE) && ((boolean)mrec.get(FieldNames.FIELD_POPULATED)) == false) {
							reader.populate(mrec);
						}
						if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
							logger.info(mrec.toFullString());
						}
					}
					else {
						logger.error("Urn could not be found: " + murn);
					}
				}
				catch(IndexException | ReaderException | FieldException | ValueException | ModelNotFoundException e0) {
					logger.error(e0);
					return OperationResponseEnumType.ERROR;
				}
			}
			
			if(mrec != null) {
				contextId = mrec.get(FieldNames.FIELD_OWNER_ID);
			}
			else {
				// logger.error("Record could not be found");
			}
			if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
				logger.info(ownerId + " == " + contextId + " = " + (ownerId == contextId));
			}
			if(ownerId > 0L && contextId > 0L) {
				if(ownerId == contextId) {
					ort = OperationResponseEnumType.SUCCEEDED;
				}
				else {
					// logger.warn("OwnerId " + ownerId + " does not match ContextId " + contextId);
					ort = OperationResponseEnumType.FAILED;
				}
			}
			else {
				// logger.error("ownerId or contextId were not defined: " + ownerId + ":" + contextId);
				ort = OperationResponseEnumType.ERROR;
			}

			return ort;
		}
	}
