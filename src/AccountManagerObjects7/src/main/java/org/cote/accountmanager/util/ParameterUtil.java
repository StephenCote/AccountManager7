package org.cote.accountmanager.util;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class ParameterUtil {
	public static final Logger logger = LogManager.getLogger(ParameterUtil.class);
	
	public static ParameterList newParameterList() {
		ParameterList rec = null;
		try {
			rec = new ParameterList(RecordFactory.newInstance(ModelNames.MODEL_PARAMETER_LIST));
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public static <T> ParameterList newParameterList(String name, T val) {
		ParameterList rec = newParameterList();
		if(rec != null) {
			newParameter(rec, name, val);
		}
		return rec;
	}
	
	public static <T> BaseRecord newParameter(ParameterList list, String name, T val) {
		List<BaseRecord> parms = list.get(FieldNames.FIELD_PARAMETERS);
		BaseRecord parm = newParameter(name, val);
		parms.add(parm);
		return parm;
	}
	
	public static <T> BaseRecord newParameter(String name, T val) {
		BaseRecord rec = null;
		try {
			rec = RecordFactory.newInstance(ModelNames.MODEL_PARAMETER);
			rec.set(FieldNames.FIELD_NAME, name);
			rec.setFlex(FieldNames.FIELD_VALUE, val);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		return rec;
	}
	
	public static ParameterList parameters(String parameterConstruct) {
		
		if(!parameterConstruct.contains("\"parameters\"")) {
			parameterConstruct = "{\"parameters\": [" + parameterConstruct.trim() + "]}";
		}
		
		return new ParameterList(RecordFactory.importRecord("parameterList", parameterConstruct));
	}
	
	public static <T> T getParameter(ParameterList record, String name, Class<T> clazz, T defVal) throws FactoryException {
		if(record == null || name == null) {
			throw new FactoryException("Invalid arguments");
		}

		if(!record.inherits(ModelNames.MODEL_PARAMETER_LIST)) {
			logger.error(record.toString());
			throw new FactoryException("Expected parameter list");
		}
		
		List<BaseRecord> parms = record.get(FieldNames.FIELD_PARAMETERS);
		List<BaseRecord> filt = parms.stream().filter(o -> {
			String mname = o.get(FieldNames.FIELD_NAME);
			return name.equals(mname);
		}).collect(Collectors.toList());
		if(filt.size() > 0) {
			BaseRecord filto = filt.get(0);
			if(filto.hasField(FieldNames.FIELD_VALUE)) {
				/// logger.info("Returning flex value for " + name + " :: " + (filto == null));
				return filt.get(0).get(FieldNames.FIELD_VALUE);
			}
		}
		return defVal;
	}
}
