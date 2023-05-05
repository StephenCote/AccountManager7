package org.cote.accountmanager.model.field.value;

import org.cote.accountmanager.model.field.FieldEnumType;

public class LongValueType extends ValueType {
	private Long value = 0L;
	
	public LongValueType(Long value) {
		super(FieldEnumType.LONG);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		/*
		if(value == null) {
			logger.warn("Null value");
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
		}
		*/
		this.value = (Long)value;
	}

}
