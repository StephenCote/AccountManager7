package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.RecordUtil;


public class Query extends LooseRecord{
	
	private BaseRecord irecord = null;
	private String keyVal = null;
	private String hashVal = null;
	public Query() {
		//super(ModelNames.MODEL_QUERY, new FieldType[0]);
		try {
			RecordFactory.newInstance(ModelNames.MODEL_QUERY, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public Query(String model) {
		this();
		try {
			set(FieldNames.FIELD_TYPE, model);
			set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
			set(FieldNames.FIELD_COMPARATOR, ComparatorEnumType.GROUP_AND.toString());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public Query(BaseRecord actor, String model) {
		this(model);
		setContextUser(actor);
	}
	
	public Query(BaseRecord query) {
		this();
		this.setFields(query.getFields());
		// logger.warn("Query Check: " + query.get(FieldNames.FIELD_TYPE));
		// logger.warn(JSONUtil.exportObject(query, RecordSerializerConfig.getUnfilteredModule()));
	}
	
	public void setComparator(ComparatorEnumType comp) {
		try {
			set(FieldNames.FIELD_COMPARATOR, comp.toString());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public ComparatorEnumType getComparator() {
		return ComparatorEnumType.valueOf(get(FieldNames.FIELD_COMPARATOR));
	}
	
	public void setContextUser(BaseRecord rec) {
		try {
			set(FieldNames.FIELD_CONTEXT_USER, rec);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public QueryPlan getPlan(String fieldName) {
		QueryPlan fqp = null;
		BaseRecord plan = get("plan");
		if(plan != null) {
			QueryPlan qp = new QueryPlan(plan);
			fqp = qp.getSubPlan(fieldName);
		}
		else {
			// logger.warn(fieldName + " was null");
		}
		return fqp;
	}
	public void filterPlan(String modelName, String fieldName) {
		BaseRecord plan = get("plan");
		if(plan != null) {
			QueryPlan.filterPlan(plan, modelName, fieldName);
			setRequest((List<String>)plan.get(FieldNames.FIELD_FIELDS));
		}
		else {
			logger.warn("Query does not define a query plan");
		}
	}
	
	public List<BaseRecord> findPlans(String modelName, String fieldName){
		List<BaseRecord> cplans = new ArrayList<>();
		BaseRecord plan = get("plan");
		if(plan != null) {
			cplans = QueryPlan.findPlans(plan, modelName, fieldName);
		}
		else {
			logger.warn("Query does not define a query plan");
		}
		return cplans;
	}
	
	public QueryPlan plan(BaseRecord plan) {
		QueryPlan qp = new QueryPlan(plan);
		setRequest(qp.getPlanFields());
		return qp;
	}
	
	public QueryPlan planField(String fieldName) {
		return planField(fieldName, new String[0]);
	}
	public QueryPlan planField(String fieldName, String[] subFields) {
		return planField(fieldName, subFields, true);
	}
	public QueryPlan planField(String fieldName, String[] subFields, boolean includeCommon) {
		QueryPlan qpf = null;
		if(!getRequest().contains(fieldName)) {
			QueryPlan qp = plan();
			qpf = qp.plan(fieldName, subFields);
			if(qpf != null && subFields.length == 0 && includeCommon) {
				qpf.planForCommonFields();
			}
			else if(qpf == null) {
				qp.getPlanFields().add(fieldName);
			}
			getRequest().add(fieldName);
		}
		return qpf;
	}
	
	public QueryPlan planCommon(boolean recurse) {
		// requestCommonFields();
		QueryPlan qp = plan();
		qp.planForCommonFields(recurse);
		setRequest(qp.getPlanFields());
		return qp;
	}
	
	public QueryPlan planMost(boolean recurse) {
		return planMost(recurse, new ArrayList<>());
	}
	public QueryPlan planMost(boolean recurse, List<String> filter) {
		// requestMostFields();
		QueryPlan qp = plan();
		qp.planForMostFields(recurse, filter);
		setRequest(qp.getPlanFields());
		return qp;
	}

	public QueryPlan plan() {
		return plan(true);
	}
	public QueryPlan plan(boolean includeCommon) {
		BaseRecord p = get("plan");
		if(p != null) {
			return new QueryPlan(p);
		}
		List<String> fields = getRequest();
		if(includeCommon && fields.size() == 0) {
			requestCommonFields();
			fields = getRequest();
		}
		QueryPlan qp = new QueryPlan((String)get(FieldNames.FIELD_TYPE), null, fields);
		setValue("plan", qp);
		return qp;
	}
	/*
	public void requestAllFields() {
		setRequest(RecordUtil.getRequestFields((String)get(FieldNames.FIELD_TYPE)));	
	}
	*/
	public void requestCommonFields() {
		setRequest(RecordUtil.getCommonFields((String)get(FieldNames.FIELD_TYPE)));
	}
	public void requestMostFields() {
		setRequest(RecordUtil.getMostRequestFields((String)get(FieldNames.FIELD_TYPE)));	
	}
	
	public void setRequestRange(long startRecord, int recordCount) {
		try {
			if(startRecord >= 0L) {
				set(FieldNames.FIELD_START_RECORD, startRecord);
			}
			if(recordCount >= 0) {
				set(FieldNames.FIELD_RECORD_COUNT, recordCount);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}		
	}
	
	public void setRequest(String[] requestFields, long startRecord, int recordCount) {
		if(requestFields != null) {
			setRequest(requestFields);
		}
		setRequestRange(startRecord, recordCount);
	}

	public void setCache(boolean cache) {
		try {
			set(FieldNames.FIELD_CACHE, cache);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public boolean isCache() {
		return get(FieldNames.FIELD_CACHE);
	}
	public String getType() {
		return get(FieldNames.FIELD_TYPE);
	}
	
	public List<String> getRequest(){
		return get(FieldNames.FIELD_REQUEST);
	}
	
	public void setRequest(List<String> requestFields) {
		setValue(FieldNames.FIELD_REQUEST, requestFields);
	}
	public void setRequest(String[] requestFields) {
		try {
			set(FieldNames.FIELD_REQUEST, new ArrayList<String>(Arrays.asList(requestFields)));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	private BaseRecord getIRecord() {
		if(irecord != null) {
			return irecord;
		}
		try {
			irecord = RecordFactory.newInstance(this.get(FieldNames.FIELD_TYPE));
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return irecord;
	}
	
	public synchronized String hash() {
		if(hashVal == null) {
			String key = key();
			if(key == null) {
				logger.error("Null key error");
			}
			try {
				hashVal = CryptoUtil.getDigestAsString(key);
			}
			catch(Exception e) {
				logger.error(e);
			}
		}
		return hashVal;
	}
	
	public synchronized String key() {
		if(keyVal == null) {
			keyVal = QueryUtil.key(this);
		}
		return keyVal;
	}

	public synchronized void releaseKey() {
		keyVal = null;
		hashVal = null;
	}
	
	@Override
	public synchronized <T> void set(String fieldName, T val) throws FieldException, ValueException, ModelNotFoundException {
		super.set(fieldName, val);
		releaseKey();
	}
	
	public List<BaseRecord> getQueryFields(){
		return get(FieldNames.FIELD_FIELDS);
	}
	public List<BaseRecord> getJoins(){
		return get(FieldNames.FIELD_JOINS);
	}
	public BaseRecord getQueryField(String fieldName) {
		return QueryUtil.findField(this,  fieldName);
	}
	public boolean hasQueryField(String name) {
		if(name == null) {
			return false;
		}
		List<?> vals = QueryUtil.findFieldValues(this, name, null);
		return vals.size() > 0;
	}
	
	public void filterParticipant(String model, String fieldName, BaseRecord actor, BaseRecord effect) {
		QueryUtil.filterParticipant(this, model, fieldName, actor, effect);
	}
	
	public void filterParticipation(BaseRecord object, String fieldName, String actorType, BaseRecord effect) {
		QueryUtil.filterParticipation(this, object, fieldName, actorType, effect);
	}
	
	/// Note: The flexible type of value is pinned here to the field type 
	/// Also, not every type was included at this time
	///
	public <T> QueryField field(String fieldName, T value) {
		return field(fieldName, ComparatorEnumType.EQUALS, value);
	}
	public <T> QueryField field(String fieldName, ComparatorEnumType comparator, T value) {
		return field(fieldName, comparator, value, this);
	}
	public <T> QueryField field(String fieldName, ComparatorEnumType comparator, T value, BaseRecord parent) {
		if(hasQueryField(fieldName)) {
			logger.debug("Query field '" + fieldName + "' defined more than once.  This is expected for compound queries");
		}
		QueryField qfield = null;
		FieldType itype = getIRecord().getField(fieldName);
		try {
			qfield = new QueryField();
			qfield.set(FieldNames.FIELD_NAME, fieldName);
			qfield.set(FieldNames.FIELD_COMPARATOR, comparator.toString());

			/// a null field name is permitted for groups
			if(itype != null) {
				if(comparator == ComparatorEnumType.IN || comparator == ComparatorEnumType.NOT_IN)
				{
					qfield.setString(FieldNames.FIELD_VALUE, value);
				}
				else {
					qfield.setFlex(FieldNames.FIELD_VALUE, itype.getValueType(), value);
				}

			}
			else {
				qfield.setString(FieldNames.FIELD_VALUE, null);
			}
		} catch (FieldException | ModelNotFoundException | ValueException | ModelException e) {
			logger.error(e);
		}
		List<BaseRecord> qqueries = parent.get(FieldNames.FIELD_FIELDS);

		releaseKey();
		
		qqueries.add(qfield);

		return qfield;
	}
	
	public String toSelect() {
		String sql = null;
		DBStatementMeta meta = null;
		try {
			meta = StatementUtil.getSelectTemplate(this);
			sql = meta.getSql();
		} catch (ModelException | FieldException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		return sql;
	}
}
