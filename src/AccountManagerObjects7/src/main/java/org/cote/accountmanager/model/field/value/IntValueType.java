package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.model.field.FieldEnumType;

public class IntValueType extends ValueType {
	private Integer value = 0;
	
	public IntValueType(Integer value) {
		super(FieldEnumType.INT);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		this.value = (Integer)value;
	}

}
