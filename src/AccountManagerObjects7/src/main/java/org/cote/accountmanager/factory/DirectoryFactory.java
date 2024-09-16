package org.cote.accountmanager.factory;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.ParameterUtil;

public class DirectoryFactory extends FactoryBase {
	public DirectoryFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		
		// logger.error("*** New directory - this shouldn't be hit for an abstract model");
		BaseRecord dir = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		return dir;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		if(parameterList != null) {
			
			String name = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_NAME, String.class, null);
			if(name == null && !FieldUtil.isNullOrEmpty(newRecord.getModel(), newRecord.getField(FieldNames.FIELD_NAME))) {
				name = newRecord.get(FieldNames.FIELD_NAME);
			}
			String path = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_PATH, String.class, null);
			if(name == null && newRecord.inherits(ModelNames.MODEL_NAME)) {
				name = UUID.randomUUID().toString();
			}
			if(path != null && contextUser != null) {
				try {
					IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(contextUser, newRecord, name, path, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					throw new FactoryException(e);
				}
			
			}
			else {
				// logger.warn(newRecord.getModel() + " factory warning: Cannot initialize ownership");
			}

		}
		return newRecord;
	}
}
