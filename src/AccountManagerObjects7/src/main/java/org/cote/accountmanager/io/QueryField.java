package org.cote.accountmanager.io;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class QueryField extends LooseRecord {
	
	
	public QueryField() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_QUERY_FIELD, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public QueryField(FieldType field) {
		this(field, ComparatorEnumType.EQUALS);
	}
	public QueryField(FieldType field, ComparatorEnumType comparator) {
		this();
		try {
			this.set(FieldNames.FIELD_NAME, field.getName());
			this.set(FieldNames.FIELD_VALUE, field.getValue());
			// this.set(FieldNames.FIELD_VALUE_TYPE, field.getFieldValueType().getValueType());
			this.set(FieldNames.FIELD_COMPARATOR, comparator);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.info(e);
		}
	}
	
	public QueryField(BaseRecord field) {
		this();
		this.setFields(field.getFields());
	}
	
	/*
	public List<QueryField> getQueries(){
		List<QueryField> qs = this.get(FieldNames.FIELD_QUERIES);
		List<QueryField> queries = new ArrayList<>();
		qs.forEach(f -> {
			queries.add(new QueryField(f));
		});
		return queries;
	}
	*/

	/*
	public static final Logger logger = LogManager.getLogger(QueryField.class);
	private String model = null;
	private ModelSchema schema = null;
	private FieldSchema fieldSchema = null;
	private List<QueryField> queries = new ArrayList<>();
	private FieldType queryField = null;
	private ComparatorEnumType comparator = ComparatorEnumType.EQUALS;
	
	public QueryField(String model, FieldType field) {
		this.model = model;
		this.queryField = field;
		schema = RecordFactory.getSchema(model);
	}
	public QueryField(String model, FieldType field, ComparatorEnumType comparator) {
		this(model, field);
		this.comparator = comparator;
	}
	*/
}
