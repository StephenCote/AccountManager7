package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;

/// TODO: Deprecate - just using as a placeholder for deserialization testing
public class FlexValueType extends ValueType {
	
	public FlexValueType(FieldType value) {
		super(FieldEnumType.FLEX);
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return null;
	}

	public <T> void setValue(T value) {

	}


	
}


