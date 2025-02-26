package org.cote.accountmanager.io.file;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.TypeUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
//  JsonInclude.Include.NON_NULL
public class IndexEntry extends LooseRecord {
	
	@JsonIgnore
	private IndexEntry parent = null;
	private IndexEntry container = null;
	@JsonIgnore
	private List<IndexEntry> children = new ArrayList<>();
	@JsonIgnore
	private List<IndexEntry> contains = new ArrayList<>();
	
	
	public IndexEntry() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_INDEX_ENTRY2, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			/// ignore
		}
	}
	
	public IndexEntry(BaseRecord rec){
		this.setAMModel(rec.getAMModel());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}


	public IndexEntry getParent() {
		return parent;
	}


	public void setParent(IndexEntry parent) {
		this.parent = parent;
	}


	public IndexEntry getContainer() {
		return container;
	}


	public void setContainer(IndexEntry container) {
		this.container = container;
	}


	public List<IndexEntry> getChildren() {
		return children;
	}


	public void setChildren(List<IndexEntry> children) {
		this.children = children;
	}


	public List<IndexEntry> getContains() {
		return contains;
	}


	public void setContains(List<IndexEntry> contains) {
		this.contains = contains;
	}
	
	public List<IndexEntryValue> getEntries() {
		return TypeUtil.convertRecordList(get(FieldNames.FIELD_VALUES), IndexEntryValue.class);
	}
	public void setEntries(List<IndexEntryValue> entries) {
		try {
			set(FieldNames.FIELD_VALUES, entries);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public <T> T getValue(FieldEnumType eType, String name, T defVal) {
		List<BaseRecord> vals = get(FieldNames.FIELD_VALUES);
		List<BaseRecord> values = vals.stream().filter(o -> {
			String fname = o.get(FieldNames.FIELD_NAME);
			return fname.equals(name);
		}).collect(Collectors.toList());
		if(values.size() == 1 && values.get(0).hasField(FieldNames.FIELD_VALUE) && values.get(0).get(FieldNames.FIELD_VALUE) != null) {
			FieldEnumType fet = values.get(0).getField(FieldNames.FIELD_VALUE).getValueType();
			if(eType == FieldEnumType.LONG && fet == FieldEnumType.INT) {
				//logger.warn("**** FIX INT TO LONG");
				Integer val = getValue(name, 0);
				Long lval = (long)defVal;
				if(val != null) {
					lval = (long)val;
				}
				return (T)lval;
			}
		}
		return getValue(name, defVal);

	
	}
	public <T> T getValue(String name, T defVal) {
		T obj = defVal;
		List<BaseRecord> values = get(FieldNames.FIELD_VALUES);
		//values.forEach(r -> {
		for(BaseRecord r : values) {
			String fname = r.get(FieldNames.FIELD_NAME);
			if(name.equals(fname) && r.hasField(FieldNames.FIELD_VALUE)) {
				obj = r.get(FieldNames.FIELD_VALUE);
				break;
			}
		};
		return obj;
	}
	
	public String getObjectId() {
		return getValue(FieldNames.FIELD_OBJECT_ID, null);
	}
	public String getUrn() {
		return getValue(FieldNames.FIELD_URN, null);
	}
	public long getId() {
		return getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L);
	}
	public long getParentId() {
		return getValue(FieldEnumType.LONG, FieldNames.FIELD_PARENT_ID, 0L);
	}
	public long getGroupId() {
		return getValue(FieldEnumType.LONG, FieldNames.FIELD_GROUP_ID, 0L);
	}
	public String getIndexType() {
		return get(FieldNames.FIELD_TYPE);
	}
	public String getType() {
		return getValue(FieldNames.FIELD_TYPE, null);
	}
	public long getOrganizationId() {
		return getValue(FieldEnumType.LONG, FieldNames.FIELD_ORGANIZATION_ID, 0L);
	}
	public String getName() {
		return getValue(FieldNames.FIELD_NAME, null);
	}


	
	
}
