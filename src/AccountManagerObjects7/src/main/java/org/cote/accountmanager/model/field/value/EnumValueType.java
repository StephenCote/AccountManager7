package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.RecordFactory;

public class EnumValueType extends ValueType {
	private String value = null;
	
	public EnumValueType(String value) {
		super(FieldEnumType.ENUM);
		this.value = value;
	}

	/// TODO: Return the value as an Enum when requested as an Enum vs. a string
	///
	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		if(value == null) return (T)"UNKNOWN";
		return (T)value;
	}

	@SuppressWarnings("unchecked")
	public <T> void setValue(T value) throws ValueException {
		//logger.info("Set enum value: " + value + " / " + this.getBaseClass());
		String comp = null;
		if(value == null) {
			value = (T)"UNKNOWN";
		}
		if(value instanceof Enum) {
			comp = value.toString();
		}
		else {
			comp = (String)value;
		}
		if(this.getBaseClass() == null || RecordFactory.isEnumValue(this.getBaseClass(), comp)) {
			this.value = comp;
		}
		else {
			throw new ValueException(String.format(ValueException.ENUM_VALUE_EXCEPTION, comp, this.getBaseClass()));
			//logger.error("Value " + value + " is not a valid enumeration constant in " + this.getBaseClass());
		}
	}

}
