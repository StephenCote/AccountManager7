package org.cote.accountmanager.io;

import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.CryptoUtil;


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

	public String getType() {
		return get(FieldNames.FIELD_TYPE);
	}
	
	public List<String> getRequest(){
		return get(FieldNames.FIELD_REQUEST);
	}
	
	public void setRequest(String[] requestFields) {
		try {
			set(FieldNames.FIELD_REQUEST, Arrays.asList(requestFields));
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
				logger.error(this.toFullString());
				e.printStackTrace();
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
	
	public boolean hasQueryField(String name) {
		List<?> vals = QueryUtil.findFieldValues(this, name, null);
		return vals.size() > 0;
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
			logger.warn("Query field '" + fieldName + "' defined more than once");
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
				logger.error("**** Null itype for " + getIRecord().getModel() + "." + fieldName);
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
}
