package org.cote.accountmanager.model.field;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.value.ValueType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.FieldUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class FieldType {
	public static final Logger logger = LogManager.getLogger(FieldType.class);
	
	private String name = null;
	private ValueType value = null;
	private boolean identity = false;
	private boolean immutable = false;
		
	public FieldType(String inName, ValueType inValue) throws ModelException {
		if(inName == null || inName.equals(RecordFactory.JSON_MODEL_KEY)) {
			throw new ModelException(String.format(ModelException.PROTECTED_NAME_EXCEPTION,  RecordFactory.JSON_MODEL_KEY));
		}
		this.name = inName;
		this.value = inValue;
	}
	
	@JsonIgnore
	public boolean isNullOrEmpty(String model) {
		return FieldUtil.isNullOrEmpty(model, this);
	}

	@JsonIgnore
	public int compareTo(FieldType f) {
		return FieldUtil.compareTo(this, f);
	}
	
	@JsonIgnore
	public boolean isEquals(FieldType f) {
		return FieldUtil.equals(this, f);
	}

	@JsonIgnore
	public boolean isDefault(String model) {
		return FieldUtil.isDefault(model, this);
	}
	
	@JsonIgnore
	public ValueType getFieldValueType() {
		return value;
	}
	@JsonIgnore
	public FieldEnumType getValueType() {
		return this.value.getValueType();
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	
	public <T> void setValue(T value) throws ValueException {
		this.value.setValue(value);
	}
	
	public <T> T getValue() {
		return this.value.getValue();
	}
	public <T> void setValue(BaseRecord model, T value) throws ValueException {
		this.value.setValue(model, this, value);
	}
	
	public <T> T getValue(BaseRecord model) {
		return this.value.getValue(model, this);
	}
	
	@JsonIgnore
	public boolean isImmutable() {
		return immutable;
	}
	public void setImmutable(boolean immutable) {
		this.immutable = immutable;
	}
	
	@JsonIgnore
	public boolean isIdentity() {
		return identity;
	}

	public void setIdentity(boolean identity) {
		this.identity = identity;
	}
	
}
