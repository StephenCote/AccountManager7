package org.cote.accountmanager.util;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class FieldUtil {
	public static final Logger logger = LogManager.getLogger(FieldUtil.class);
	
	public static <T> FieldEnumType getFieldType(T value) {
		FieldEnumType fet = FieldEnumType.UNKNOWN;
		   if(value instanceof Enum) {
			   fet = FieldEnumType.ENUM;
		   }
		   else if(value instanceof String) {
			   fet =FieldEnumType.STRING;
		   }
		   else if(value instanceof Integer) {
			   fet =FieldEnumType.INT;
		   }
		   else if(value instanceof Long) {
			   fet =FieldEnumType.LONG;
		   }
		   else if(value instanceof Date) {
			   fet =FieldEnumType.TIMESTAMP;
		   }
		   else if(value instanceof Boolean) {
			   fet =FieldEnumType.BOOLEAN;
		   }
		   else if(value instanceof Double) {
			   fet =FieldEnumType.DOUBLE;
		   }

		
		return fet;
	}
	public static <T> void setFlexFromString(BaseRecord record, FieldEnumType fet, String name, String value) throws NumberFormatException, ValueException, ModelException, FieldException, ModelNotFoundException{
		switch(fet) {
			case STRING:
				record.setString(name, value);
				break;
			case TIMESTAMP:
				record.setDateTime(name, new Date(Long.parseLong(value)));
				break;
			case LONG:
				record.setLong(name, Long.parseLong(value));
				break;
			case INT:
				record.setInt(name, Integer.parseInt(value));
				break;
			case DOUBLE:
				record.setDouble(name, Double.parseDouble(value));
				break;
			case BLOB:
				record.setByteArray(name, BinaryUtil.fromBase64Str(value));
				break;
			case BOOLEAN:
				record.setBoolean(name, Boolean.parseBoolean(value));
				break;
			case FLEX:
			case LIST:
			case MODEL:
			default:
				logger.error("Unhandled type: " + fet.toString());
				break;
		}
	}
	   public static <T> void setFlex(BaseRecord record, String fieldName, T value) {
		   FieldEnumType fet = getFieldType(value);
		   if(fet != FieldEnumType.UNKNOWN) {
			   setFlex(record, fieldName, fet, value);
		   }
		   else if(value != null){
			   logger.error("Failed to set flex field: " + fieldName);
		   }
	   }
   public static <T> void setFlex(BaseRecord record, String fieldName, FieldEnumType type, T value) {

		try {
			switch(type) {
				case ENUM:
					record.setString(fieldName, value.toString());
					break;
				case STRING:
					record.setString(fieldName, value);
					break;
				case INT:
					record.setInt(fieldName, value);
					break;
				case LONG:
					record.setLong(fieldName, value);
					break;
				case TIMESTAMP:
					record.setDateTime(fieldName, value);
					break;
				case BOOLEAN:
					record.setBoolean(fieldName, value);
					break;
				case DOUBLE:
					record.setDouble(fieldName, value);
					break;
				default:
					logger.error("Unhandled type: " + type.toString());
					break;
			}
		} catch (Exception e) {
			logger.error(e);
			logger.error("Error setting flex " + type.toString() + " " + fieldName + " in " + record.getModel());
		}
	} 
	
	public static <T> FieldEnumType getValueType(T val) {
		FieldEnumType fet = FieldEnumType.UNKNOWN;
		if(val instanceof LooseRecord) {
			fet = FieldEnumType.MODEL;
		}
		else if(val instanceof byte[]) {
			fet = FieldEnumType.BLOB;
		}
		else if(val instanceof String) {
			fet = FieldEnumType.STRING;
		}
		else if(val instanceof Long) {
			fet = FieldEnumType.LONG;
		}
		else if(val instanceof Integer) {
			fet = FieldEnumType.INT;
		}
		else if(val instanceof Double) {
			fet = FieldEnumType.DOUBLE;
		}
		else if(val instanceof List) {
			fet = FieldEnumType.LIST;
		}
		else if(val instanceof Date) {
			fet = FieldEnumType.TIMESTAMP;
		}
		else {
			logger.error("Unchecked type: " + val.toString());
		}
		return fet;
	}
	
	public static int compareTo(FieldType c, FieldType f) {
		int comp = 0;
		if(f.getValueType() != c.getValueType()) {
			logger.error("Cannot compare across value types");
			return comp;
		}
		
		switch(c.getValueType()) {
			case TIMESTAMP:
				long d1 = ((Date)f.getValue()).getTime();
				long d2 = ((Date)c.getValue()).getTime();
				if(d1 < d2) {
					comp = -1;
				}
				else if (d1 > d2) {
					comp = 1;
				}
				break;
			case MODEL:
				String jcomp1 = JSONUtil.exportObject((BaseRecord)f.getValue(), RecordSerializerConfig.getFilteredModule());
				String jcomp2 = JSONUtil.exportObject((BaseRecord)c.getValue(), RecordSerializerConfig.getFilteredModule());
				comp = jcomp1.compareTo(jcomp2);
				break;
			case LIST:
				if(!((List)f.getValue()).equals((List<?>)c.getValue())) {
					comp = -1;
				}
				break;
			case BLOB:
				if(!Arrays.equals((byte[])f.getValue(), (byte[])c.getValue())) {
					comp = -1;
				}
				break;
			case STRING:
			case ENUM:
				comp = ((String)f.getValue()).compareTo((String)c.getValue());
				break;
			case LONG:
				long l1 = f.getValue();
				long l2 = c.getValue();
				if(l1 < l2) {
					comp = -1;
				}
				else if (l1 > l2) {
					comp = 1;
				}
				break;
			case DOUBLE:
				double dd1 = f.getValue();
				double dd2 = c.getValue();
				if(dd1 < dd2) {
					comp = -1;
				}
				else if(dd1 > dd2) {
					comp = 1;
				}
				break;
			case INT:
				int i1 = f.getValue();
				int i2 = c.getValue();
				if(i1 < i2) {
					comp = -1;
				}
				else if(i1 > i2) {
					comp = 1;
				}
				break;
			case BOOLEAN:
				if(!(boolean)f.getValue() == (boolean)c.getValue()) {
					comp = -1;
				}
				break;
			default:
				logger.error("Unhandled value type in compareTo: " + c.getName() + " " + c.getValueType());
				break;
		}
		return comp;
	}
	
	public static boolean equals(FieldType c, FieldType f) {
		if(f.getValueType() != c.getValueType()) {
			logger.error("Cannot compare across value types");
			return false;
		}
		boolean comp = false;
		switch(c.getValueType()) {
			case TIMESTAMP:
				long d1 = ((Date)f.getValue()).getTime();
				long d2 = ((Date)c.getValue()).getTime();
				comp = d1 == d2;
				break;
			case MODEL:
				String jcomp1 = JSONUtil.exportObject((BaseRecord)f.getValue(), RecordSerializerConfig.getFilteredModule());
				String jcomp2 = JSONUtil.exportObject((BaseRecord)c.getValue(), RecordSerializerConfig.getFilteredModule());
				comp = jcomp1.equals(jcomp2);
				break;
			case LIST:
				comp = ((List)f.getValue()).equals((List<?>)c.getValue());
				break;
			case BLOB:
				comp = Arrays.equals((byte[])f.getValue(), (byte[])c.getValue());
				break;
			case STRING:
			case ENUM:
				comp = ((String)f.getValue()).equals((String)c.getValue());
				break;
			case LONG:
				comp = (long)f.getValue() == (long)c.getValue();
				break;
			case DOUBLE:
				comp = (double)f.getValue() == (double)c.getValue();
				break;
			case INT:
				comp = (int)f.getValue() == (int)c.getValue();
				break;
			case BOOLEAN:
				comp = (boolean)f.getValue() == (boolean)c.getValue();
				break;
			default:
				logger.error("Unhandled value type checking equality: " + c.getName() + " " + c.getValueType());
				break;
		}
		return comp;
	}
	
	public static <T> boolean isNullOrEmpty(String model, FieldType f) {
		boolean outBool = false;
		ModelSchema ms = RecordFactory.getSchema(model);
		FieldSchema fs = ms.getFieldSchema(f.getName());
		T val = f.getValue();
		switch(f.getValueType()) {
			case INT:
				outBool = val == null || (int)val == 0;
				break;
			case DOUBLE:
				outBool = val == null || (double)val == 0.0;
				break;
			case LONG:
				outBool = val == null || (long)val == 0L;
				break;
			case BOOLEAN:
			case MODEL:
			case TIMESTAMP:
				outBool = (val == null || (fs.getDefaultValue() != null && fs.getDefaultValue() == val));
				break;
			case LIST:
				outBool = (val == null || ((List<?>)val).size() == 0);
				break;
			case BLOB:
				outBool = (val == null || ((byte[])val).length == 0);
				break;
			case STRING:
			case ENUM:
				String sval = (String)val;
				outBool = (sval == null || sval.length() == 0 || (fs.getDefaultValue() != null && ((String)fs.getDefaultValue()).equals((String)val)));
				break;
			case FLEX:
				outBool = (val == null);
				break;
			default:
				logger.error("Unhandled value type checking null or empty: " + f.getName() + " " + f.getValueType());
				break;
		}
		return outBool;
	}
	
}
