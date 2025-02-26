package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;

public class ModelValueType extends ValueType {
	private BaseRecord value = null;
	
	public ModelValueType(BaseRecord value) {
		super(FieldEnumType.MODEL);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T inValue) throws ValueException {
		
		if(inValue != null && (this.getBaseModel() == null || !this.getBaseModel().equals(ModelNames.MODEL_FLEX))) {
			BaseRecord m = (BaseRecord)inValue;
			this.setBaseModel(m.getAMModel());
			if(this.getBaseModel() == null || RecordFactory.getSchema(this.getBaseModel()) == null) {
				throw new ValueException(String.format(ValueException.MODEL_VALUE_EXCEPTION, this.getBaseModel()));
			}
			else if(!this.getBaseModel().equals(m.getAMModel())) {
				throw new ValueException(String.format(ValueException.INVALID_MODEL_EXCEPTION, m.getAMModel(), this.getBaseModel()));
			}
			
		}
		this.value = (BaseRecord)inValue;
	}

}
