package org.cote.accountmanager.factory;

import java.util.Date;
import java.util.GregorianCalendar;
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

public class FieldLockFactory extends FactoryBase {
	
	public FieldLockFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		return super.newInstance(contextUser, recordTemplate, parameterList, arguments);
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		BaseRecord lock = null;
		GregorianCalendar cal = new GregorianCalendar();
		if(parameterList != null) {
			
			String fieldName = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_FIELD_NAME, String.class, null);
			long refId = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_REFERENCE_ID, Long.class, 0L);
			String refType = ParameterUtil.getParameter(parameterList, FieldNames.FIELD_REFERENCE_TYPE, String.class, null);
			if(fieldName != null && refType != null && refId > 0L) {
				try {
					lock = rec;
					IOSystem.getActiveContext().getRecordUtil().applyOwnership(contextUser, lock, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
					lock.set(FieldNames.FIELD_REFERENCE_ID, refId);
					lock.set(FieldNames.FIELD_REFERENCE_TYPE, refType);
					lock.set(FieldNames.FIELD_FIELD_NAME, fieldName);
					lock.set(FieldNames.FIELD_ENABLED, true);

					cal.setTime(new Date());
				    cal.add(GregorianCalendar.MINUTE, 10);
					lock.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					throw new FactoryException(e);
				}
			
			}

		}
		return lock;

	}

}
