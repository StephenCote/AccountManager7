package org.cote.accountmanager.model.field.value;

import java.util.Calendar;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;

import com.fasterxml.jackson.annotation.JsonFormat;

public class CalendarValueType extends ValueType {

	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy hh:mm:ss z")
	private Calendar value = null;

	public CalendarValueType(Calendar inValue) {
		super(FieldEnumType.CALENDAR);
		this.value = inValue;
	}
	
	//dd-MM-yyyy hh:mm:ss
	/*@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm'Z'")*/
	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		//System.out.println(guid + " - Get DVT: " + value);
		return (T)value;
	}

	/*@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm'Z'")*/
	public <T> void setValue(T value) {
		//System.out.println(guid + " - Set DVT: " + value);
		this.value = (Calendar)value;
	}
	
	public <T> T getValue(BaseRecord model) {
		return getValue();
	}

	public <T> void setValue(BaseRecord model, T value) throws ValueException {
		setValue(value);
	}
}
