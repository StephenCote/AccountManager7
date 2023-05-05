package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;

public class ByteArrayValueType  extends ValueType {
	private byte[] value = new byte[0];
	
	public ByteArrayValueType(byte[] value) {
		super(FieldEnumType.BLOB);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		this.value = (byte[])value;
		/*
		if(this.value == null || this.value.length == 0) {
			logger.error("WARN: EMPTY VALUE");
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
		}
		*/
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getValue(BaseRecord model, FieldType field) {
		T ret = (T)new byte[0];
		if(model.inherits(ModelNames.MODEL_CRYPTOBYTESTORE) &&  field.getName().equals(FieldNames.FIELD_BYTE_STORE)) {
			try {
				ret = (T)ByteModelUtil.getValue(model);
			} catch (ValueException | FieldException e) {
				logger.error(e);
				
			}
		}
		else {
			ret = getValue();
		}
		return ret;
	}

	@Override
	public <T> void setValue(BaseRecord model, FieldType field, T value) throws ValueException {
		
		if(model.inherits(ModelNames.MODEL_CRYPTOBYTESTORE) && field.getName().equals(FieldNames.FIELD_BYTE_STORE)) {
			try {
				ByteModelUtil.setValue(model, (byte[])value);
			} catch (FieldException | ModelNotFoundException e) {
				throw new ValueException(e.getMessage());
			}
		}
		else {
			setValue(value);
		}
	}
	
}
