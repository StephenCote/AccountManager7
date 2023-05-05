package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.model.field.FieldEnumType;

public class StringValueType extends ValueType {
	private String value = null;
	
	public StringValueType(String value) {
		super(FieldEnumType.STRING);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		//logger.info("Set string value " + value);
		this.value = (String)value;
	}

}
