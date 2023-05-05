package org.cote.accountmanager.factory;

import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class ParameterListFactory extends FactoryBase {

	public ParameterListFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}

	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		BaseRecord list = null;
		try {
			list = RecordFactory.newInstance(ModelNames.MODEL_PARAMETER_LIST);
			List<BaseRecord> plist = list.get(FieldNames.FIELD_PARAMETERS);
			for(BaseRecord par : arguments) {
				if(par.inherits(ModelNames.MODEL_PARAMETER)) {
					plist.add(par);
				}
			}
		} catch (FieldException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		return list;
	}
	
}
