package org.cote.accountmanager.io;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;

public class QueryResult extends LooseRecord{
	
	public static final Logger logger = LogManager.getLogger(QueryResult.class);
	
	public QueryResult() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_QUERY_RESULT, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public QueryResult(BaseRecord queryResult) {
		this();
		this.setFields(queryResult.getFields());
	}
	public QueryResult(Query query) {
		this();
		setResponse(OperationResponseEnumType.SUCCEEDED, null);
		if(query != null) {
			try {
				set(FieldNames.FIELD_QUERY_KEY, query.key());
				set(FieldNames.FIELD_QUERY_HASH, query.hash());
				set(FieldNames.FIELD_TYPE, query.get(FieldNames.FIELD_TYPE));
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
				
			}
			
			/*
			boolean ikey = query.get(FieldNames.FIELD_QUERY_KEY);
			boolean ihash = query.get(FieldNames.FIELD_QUERY_HASH);
			if(ikey || ihash) {
				String key = QueryUtil.key(query);
				
				try {
					if(ikey) {
						set(FieldNames.FIELD_QUERY_KEY, key);
					}
					if(ihash) {
						set(FieldNames.FIELD_QUERY_HASH, QueryUtil.hash(key));
					}
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
					
				}
			}
			*/
		}
	}
	public QueryResult(Query query, BaseRecord[] records) {
		this(query);
		try {
			set(FieldNames.FIELD_COUNT, records.length);
			set(FieldNames.FIELD_RESULTS, Arrays.asList(records));
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}		
	}
	public int getCount() {
		return get(FieldNames.FIELD_COUNT);
	}
	public long getTotalCount() {
		return get(FieldNames.FIELD_TOTAL_COUNT);
	}
	public void setTotalCount(long count) {
		try {
			set(FieldNames.FIELD_TOTAL_COUNT, count);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	public BaseRecord[] getResults() {
		List<BaseRecord> res = get(FieldNames.FIELD_RESULTS);
		return res.toArray(new BaseRecord[0]);
	}
	public OperationResponseEnumType getResponse() {
		return OperationResponseEnumType.valueOf(get(FieldNames.FIELD_RESPONSE));
	}
	public void setResponse(OperationResponseEnumType responseType, String message) {
		try {
			set(FieldNames.FIELD_RESPONSE, responseType.toString());
			set(FieldNames.FIELD_MESSAGE, message);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}		
	}
}
