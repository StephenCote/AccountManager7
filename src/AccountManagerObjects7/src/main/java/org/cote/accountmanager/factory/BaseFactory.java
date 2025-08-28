package org.cote.accountmanager.factory;

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

public class BaseFactory extends FactoryBase {
	public BaseFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		if(contextUser != null) {
			try {
				IOSystem.getActiveContext().getRecordUtil().applyOwnership(contextUser, newRecord, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				throw new FactoryException(e);
			}
		}

		return newRecord;
	}
}
