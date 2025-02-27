package org.cote.accountmanager.util;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class AttributeUtil {
	public static final Logger logger = LogManager.getLogger(AttributeUtil.class);
	
	public static <T> BaseRecord addAttribute(BaseRecord record, String name, T val) throws ModelException, FieldException, ModelNotFoundException, ValueException {
		if(!record.inherits(ModelNames.MODEL_ATTRIBUTE_LIST)) {
			throw new ModelException("Model does not inherit from attribute");
		}
		List<BaseRecord> attrs = record.get(FieldNames.FIELD_ATTRIBUTES);
		BaseRecord attr = newAttribute(record, name, val);
		attrs.add(attr);
		return attr;
	}
	public static <T> BaseRecord newAttribute(BaseRecord record, String name, T val) throws ModelException, FieldException, ModelNotFoundException, ValueException {
		if(!record.inherits(ModelNames.MODEL_ATTRIBUTE_LIST)) {
			throw new ModelException("Model does not inherit from attribute");
		}
		BaseRecord attr = RecordFactory.newInstance(ModelNames.MODEL_ATTRIBUTE);
		attr.set(FieldNames.FIELD_NAME, name);
		attr.setFlex(FieldNames.FIELD_VALUE, val);
		if(record.hasField(FieldNames.FIELD_ID)) {
			attr.set(FieldNames.FIELD_REFERENCE_ID, record.get(FieldNames.FIELD_ID));
		}
		if(record.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
			attr.set(FieldNames.FIELD_ORGANIZATION_ID, record.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		attr.set(FieldNames.FIELD_REFERENCE_TYPE, record.getSchema());
		return attr;
	}
	public static BaseRecord getAttribute(BaseRecord record, String name) {
		BaseRecord attr = null;
		List<BaseRecord> attrs = record.get(FieldNames.FIELD_ATTRIBUTES);
		List<BaseRecord> mattrs = attrs.stream().filter(o ->{
			String attrName = o.get(FieldNames.FIELD_NAME);
			return name.equals(attrName);
		}).collect(Collectors.toList());
		if(mattrs.size() > 0) {
			attr = mattrs.get(0);
		}

		return attr;
	}
	public static <T> T getAttributeValue(BaseRecord record, String name) throws ModelException {
		return getAttributeValue(record, name, null);
	}
	public static <T> T getAttributeValue(BaseRecord record, String name, T defVal) throws ModelException {
		if(!record.inherits(ModelNames.MODEL_ATTRIBUTE_LIST)) {
			throw new ModelException("Model does not inherit from attribute");
		}
		T outT = defVal;

		try {
			BaseRecord attr = getAttribute(record, name);
			
			if(attr != null) {
				outT = attr.get(FieldNames.FIELD_VALUE);
			}
		}
		catch(Exception e) {
			logger.error(e);
			logger.error(record.getSchema() + ".attribute." + name);
		}

		return outT;
	}
	
}
