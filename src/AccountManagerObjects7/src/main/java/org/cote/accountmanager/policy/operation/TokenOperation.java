
	package org.cote.accountmanager.policy.operation;

	import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.policy.FactUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.security.TokenService;

import io.jsonwebtoken.Claims;


	public class TokenOperation extends Operation {
		
		Map<String,Pattern> patterns = new HashMap<String,Pattern>();
		
		public TokenOperation(IReader reader, ISearch search) {
			super(reader, search);
		}
		
		@Override
		public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
			// TODO Auto-generated method stub
			return null;
		}
		@Override
		public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {

			//logger.info(sourceFact.toFullString());
			OperationResponseEnumType ort = OperationResponseEnumType.UNKNOWN;
			String murn = referenceFact.get(FieldNames.FIELD_SOURCE_URN);
			String mtype = referenceFact.get(FieldNames.FIELD_MODEL_TYPE);
			String sdat = sourceFact.get(FieldNames.FIELD_FACT_DATA);
			String sdattype = sourceFact.get(FieldNames.FIELD_FACT_DATA_TYPE);

			if(sdattype == null || !sdattype.equals("token"))
			{
				logger.info("*** No token data");
				logger.info(sourceFact);
				return OperationResponseEnumType.FAILED; 
			}
			
			if(sdat == null) {
				logger.error("*** Source data must be provided");
				return OperationResponseEnumType.ERROR;
				
			}

			if(murn == null || mtype == null) {
				logger.error("Reference model urn (" + murn + ") or type (" + mtype + ") was not defined");
				return OperationResponseEnumType.ERROR;
			}

			BaseRecord mrec = null;
			if(murn != null && murn.length() > 0) {
				try {
					String[] flds = QueryUtil.getCommonFields(mtype);
					if(flds.length == 0) {
						flds = new String[] {FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ID};
					}
					Query q = QueryUtil.createQuery(mtype, FieldNames.FIELD_URN, murn);
					q.setRequest(flds);
					q.set(FieldNames.FIELD_INSPECT, true);
					BaseRecord[] recs = search.find(q).getResults();
					if(recs.length > 0) {
						mrec = recs[0];
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
				try {
					Claims claims = TokenService.validateSpooledJWTToken(sdat, false, true);
					String recType = claims.get(TokenService.CLAIM_RESOURCE_TYPE, String.class);
					String recId = claims.get(TokenService.CLAIM_RESOURCE_ID, String.class);
					if(recType != null && recId != null) {
						if(mrec.getModel().equals(recType) && recId.equals(mrec.get(FieldNames.FIELD_OBJECT_ID))) {
							ort = OperationResponseEnumType.SUCCEEDED;
						}
					}
					
				} catch (IndexException | ReaderException e) {
					logger.error(e);
					e.printStackTrace();
				}
			}


		
			return ort;
		}
	}
