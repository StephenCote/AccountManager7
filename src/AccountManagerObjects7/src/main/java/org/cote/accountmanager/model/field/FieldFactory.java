package org.cote.accountmanager.model.field;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.value.BooleanValueType;
import org.cote.accountmanager.model.field.value.ByteArrayValueType;
import org.cote.accountmanager.model.field.value.DateValueType;
import org.cote.accountmanager.model.field.value.DoubleValueType;
import org.cote.accountmanager.model.field.value.EnumValueType;
import org.cote.accountmanager.model.field.value.FlexValueType;
import org.cote.accountmanager.model.field.value.IntValueType;
import org.cote.accountmanager.model.field.value.ListValueType;
import org.cote.accountmanager.model.field.value.LongValueType;
import org.cote.accountmanager.model.field.value.ModelValueType;
import org.cote.accountmanager.model.field.value.StringValueType;
import org.cote.accountmanager.model.field.value.ZoneTimeValueType;

public class FieldFactory {
	public static final Logger logger = LogManager.getLogger(FieldFactory.class);
	public static FieldType modelFieldType(String name) throws ModelException {
		return new FieldType(name, new ModelValueType(null));
	}
	public static <T> FieldType listFieldType(String name) throws ModelException {
		return new FieldType(name, new ListValueType(new ArrayList<T>()));
	}
	public static FieldType dateTimeFieldType(String name) throws ModelException {
		return new FieldType(name, new DateValueType(new Date(0)));
	}
	public static FieldType zoneTimeFieldType(String name) throws ModelException {
		return new FieldType(name, new ZoneTimeValueType(ZonedDateTime.ofInstant(Instant.ofEpochSecond(0), ZoneOffset.UTC)));
	}
	public static FieldType doubleFieldType(String name) throws ModelException {
		return new FieldType(name, new DoubleValueType(0.0));
	}
	public static FieldType byteArrayFieldType(String name) throws ModelException {
		return new FieldType(name, new ByteArrayValueType(new byte[0]));
	}
	public static FieldType longFieldType(String name) throws ModelException {
		return new FieldType(name, new LongValueType(0L));
	}
	public static FieldType intFieldType(String name) throws ModelException {
		return new FieldType(name, new IntValueType(0));
	}
	public static FieldType longFieldType(String name, boolean identity) throws ModelException {
		FieldType f = new FieldType(name, new LongValueType(0L));
		f.setIdentity(identity);
		return f;
	}

	public static FieldType booleanFieldType(String name) throws ModelException {
		return new FieldType(name, new BooleanValueType(false));
	}
	public static FieldType stringFieldType(String name) throws ModelException {
		return new FieldType(name, new StringValueType(null));
	}
	public static FieldType flexFieldType(String name) throws ModelException {
		return new FieldType(name, new FlexValueType(null));
	}
	public static FieldType enumFieldType(String name) throws ModelException {
		return new FieldType(name, new EnumValueType(null));
	}
	public static FieldType fieldByType(FieldEnumType typen, String name) {
		return fieldByType(typen, name, null);
	}
	public static <T> FieldType fieldByType(FieldEnumType typen, String name, T value) {
		FieldType type = null;
		try {
			switch(typen) {
				case ZONETIME:
					type = zoneTimeFieldType(name);
				break;
				case TIMESTAMP:
					type = dateTimeFieldType(name);
					break;
				case INT:
					type = intFieldType(name);
					break;
				case LONG:
					type = longFieldType(name);
					break;
				case BOOLEAN:
					type = booleanFieldType(name);
					break;
				case ENUM:
					type = enumFieldType(name);
					break;
				case FLEX:
					type = flexFieldType(name);
					break;
				case STRING:
					type = stringFieldType(name);
					break;
				case DOUBLE:
					type = doubleFieldType(name);
					break;
				case BLOB:
					type = byteArrayFieldType(name);
					break;
				case MODEL:
					type = modelFieldType(name);
					break;
				case LIST:
					type = listFieldType(name);
					break;
				default:
					logger.error("Unhandled type: " + typen.toString());
					break;
			}
			if(type != null && value != null) {
				type.setValue(value);
			}
		} catch (Exception e) {
			logger.error("Error with " + typen.toString() + " " + name);
			logger.error(e);
		}
		return type;
	}

}
