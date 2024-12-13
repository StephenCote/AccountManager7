package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;

public class VectorValueType  extends ValueType {
	private float[] value = new float[0];

	public VectorValueType(float[] value) {
		super(FieldEnumType.VECTOR);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		this.value = (float[])value;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getValue(BaseRecord model, FieldType field) {
		T ret = getValue();

		return ret;
	}

	@Override
	public <T> void setValue(BaseRecord model, FieldType field, T value) throws ValueException {
		setValue(value);
	}

}
