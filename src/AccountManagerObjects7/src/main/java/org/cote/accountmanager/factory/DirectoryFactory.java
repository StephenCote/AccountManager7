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
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ParameterUtil;

public class DirectoryFactory extends FactoryBase {
	public DirectoryFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		
		logger.error("*** New directory - this shouldn't be hit for an abstract model");
		BaseRecord dir = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		return dir;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		if(parameterList != null) {
			
			String name = ParameterUtil.getParameter(parameterList, "name", String.class, null);
			String path = ParameterUtil.getParameter(parameterList, "path", String.class, null);
			if(name == null) {
				name = UUID.randomUUID().toString();
			}
			if(name != null && path != null && contextUser != null) {
				try {
					IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(contextUser, newRecord, name, path, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					throw new FactoryException(e);
				}
			
			}

		}
		return newRecord;
	}
}
