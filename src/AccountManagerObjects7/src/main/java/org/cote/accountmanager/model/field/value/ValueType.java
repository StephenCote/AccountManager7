package org.cote.accountmanager.model.field.value;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

public abstract class ValueType implements IValueType {
	public static final Logger logger = LogManager.getLogger(ValueType.class);
	private FieldEnumType valueType = FieldEnumType.UNKNOWN;
	private String baseClass = null;
	private String baseModel = null;
	private String baseType = null;
	
	public ValueType() {
		
	}
	
	@JsonInclude(Include.NON_NULL)
	public abstract <T> T getValue();
	public abstract <T> void setValue(T value) throws ValueException;

	public <T> T getValue(BaseRecord model, FieldType field) {
		return getValue();
	}

	public <T> void setValue(BaseRecord model, FieldType field, T value) throws ValueException {
		setValue(value);
	}
	
	public ValueType(FieldEnumType valueType) {
		this.valueType = valueType;
	}
	public FieldEnumType getValueType() {
		return valueType;
	}
	public void setValueType(FieldEnumType valueType) {
		this.valueType = valueType;
	}
	
	@JsonIgnore
	public String getBaseClass() {
		return baseClass;
	}
	public void setBaseClass(String baseClass) {
		this.baseClass = baseClass;
	}
	
	@JsonIgnore
	public String getBaseModel() {
		return baseModel;
	}
	public void setBaseModel(String baseModel) {
		this.baseModel = baseModel;
	}
	
	@JsonIgnore
	public String getBaseType() {
		return baseType;
	}
	public void setBaseType(String baseType) {
		this.baseType = baseType;
	}
	

	
	
	
}
