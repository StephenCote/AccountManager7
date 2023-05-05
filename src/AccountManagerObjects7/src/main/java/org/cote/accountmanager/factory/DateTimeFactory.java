package org.cote.accountmanager.factory;

import java.util.Date;
import java.util.GregorianCalendar;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;

public class DateTimeFactory extends FactoryBase {
	public DateTimeFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
	    GregorianCalendar cal = new GregorianCalendar();
	    cal.setTime(new Date());
	    
		try {
			newRecord.set(FieldNames.FIELD_CREATED_DATE, cal.getTime());
			newRecord.set(FieldNames.FIELD_MODIFIED_DATE, cal.getTime());
			newRecord.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}

		return newRecord;
	}
}
