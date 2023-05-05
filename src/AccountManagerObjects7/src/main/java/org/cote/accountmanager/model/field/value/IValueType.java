package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;

public interface IValueType {
	public <T> T getValue();
	public <T> T getValue(BaseRecord model, FieldType field);
	public <T> void setValue(T value) throws ValueException;
	public <T> void setValue(BaseRecord model, FieldType field, T value) throws ValueException;
}
