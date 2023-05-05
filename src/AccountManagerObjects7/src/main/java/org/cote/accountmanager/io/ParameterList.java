package org.cote.accountmanager.io;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ParameterUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class ParameterList extends LooseRecord {
	public ParameterList() {
		//super(ModelNames.MODEL_QUERY, new FieldType[0]);
		try {
			RecordFactory.newInstance(ModelNames.MODEL_PARAMETER_LIST, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public ParameterList(BaseRecord plist) {
		this();
		this.setFields(plist.getFields());
	}

	public <T> BaseRecord parameter(String name, T val) {
		return ParameterUtil.newParameter(this, name, val);
	}
	
	public static ParameterList newParameterList() {
		return ParameterUtil.newParameterList();
	}
	public static <T> ParameterList newParameterList(String name, T val) {
		return ParameterUtil.newParameterList(name, val);
	}
	
	@JsonIgnore
	public <T> T getParameter(String name, Class<T> clazz, T defVal) throws FactoryException {
		return ParameterUtil.getParameter(this, name, clazz, defVal);
	}

}
