package org.cote.accountmanager.model.field.value;

import java.util.Date;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

public class DateValueType extends ValueType{
	@JsonSerialize(as = Date.class)
	/*@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy hh:mm:ss")*/
	private Date value = null;
	//private String guid = UUID.randomUUID().toString();
	public DateValueType(Date inValue) {
		super(FieldEnumType.TIMESTAMP);
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
		this.value = (Date)value;
	}
	
	public <T> T getValue(BaseRecord model) {
		return getValue();
	}

	public <T> void setValue(BaseRecord model, T value) throws ValueException {
		setValue(value);
	}

}

