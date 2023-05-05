package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.model.field.FieldEnumType;

public class DoubleValueType extends ValueType {
	private Double value = 0.0;
	
	public DoubleValueType(Double value) {
		super(FieldEnumType.DOUBLE);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		this.value = (Double)value;
	}
	
	

}
