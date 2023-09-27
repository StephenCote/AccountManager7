package org.cote.accountmanager.model.field.value;

import java.time.ZonedDateTime;
import java.util.Date;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public class ZoneTimeValueType extends ValueType{
	@JsonSerialize(as = ZonedDateTime.class)
	// String iso8601String = zonedDateTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME);

	/*@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy hh:mm:ss")*/
	private ZonedDateTime value = null;
	//private String guid = UUID.randomUUID().toString();
	public ZoneTimeValueType(ZonedDateTime inValue) {
		super(FieldEnumType.ZONETIME);
		this.value = inValue;
	}
	
	@SuppressWarnings("unchecked")
	public <T> T getValue() {
		return (T)value;
	}

	public <T> void setValue(T value) {
		this.value = (ZonedDateTime)value;
	}
	
	public <T> T getValue(BaseRecord model) {
		return getValue();
	}

	public <T> void setValue(BaseRecord model, T value) throws ValueException {
		setValue(value);
	}

}

