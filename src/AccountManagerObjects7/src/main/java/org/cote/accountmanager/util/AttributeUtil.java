package org.cote.accountmanager.util;

import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class AttributeUtil {

	public static <T> void addAttribute(BaseRecord record, String name, T val) throws ModelException, FieldException, ModelNotFoundException, ValueException {
		if(!record.inherits(ModelNames.MODEL_ATTRIBUTE_LIST)) {
			throw new ModelException("Model does not inherit from attribute");
		}
		List<BaseRecord> attrs = record.get(FieldNames.FIELD_ATTRIBUTES);
		BaseRecord attr = RecordFactory.newInstance(ModelNames.MODEL_ATTRIBUTE);
		attr.set(FieldNames.FIELD_NAME, name);
		attr.setFlex(FieldNames.FIELD_VALUE, val);
		attrs.add(attr);
	}

	
	public static <T> T getAttributeValue(BaseRecord record, String name) throws ModelException {
		if(!record.inherits(ModelNames.MODEL_ATTRIBUTE_LIST)) {
			throw new ModelException("Model does not inherit from attribute");
		}
		List<BaseRecord> attrs = record.get(FieldNames.FIELD_ATTRIBUTES);
		List<BaseRecord> mattrs = attrs.stream().filter(o ->{
			String attrName = o.get(FieldNames.FIELD_NAME);
			return name.equals(attrName);
		}).collect(Collectors.toList());
		T outT = null;
		
		if(mattrs.size() > 0) {
			outT = mattrs.get(0).get(FieldNames.FIELD_VALUE);
		}
		return outT;
	}
	
}
