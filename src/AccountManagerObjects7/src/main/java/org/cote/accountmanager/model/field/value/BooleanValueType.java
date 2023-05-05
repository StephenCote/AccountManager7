package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.model.field.FieldEnumType;

public class BooleanValueType extends ValueType {
	private Boolean value = false;
	public BooleanValueType(Boolean value) {
		super(FieldEnumType.BOOLEAN);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		this.value = (Boolean)value;
	}


	
}
