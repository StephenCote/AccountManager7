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
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;

public class AuditFactory extends FactoryBase {

	public AuditFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		BaseRecord subject = null;
		BaseRecord resource = null;

		if(arguments.length > 0) {
			if(arguments.length > 1) {
				subject = arguments[0];
				resource = arguments[1];
			}
			else {
				subject = contextUser;
				resource = arguments[0];
			}
		}
		ActionEnumType aet = ActionEnumType.UNKNOWN;
		ResponseEnumType ret = ResponseEnumType.UNKNOWN;
		if(parameterList != null) {
			String actionStr = parameterList.getParameter(FieldNames.FIELD_ACTION, String.class, null);
			String responseStr = parameterList.getParameter(FieldNames.FIELD_RESPONSE, String.class, null);
			if(actionStr != null) {
				aet = ActionEnumType.valueOf(actionStr);
			}
			if(responseStr != null) {
				ret = ResponseEnumType.valueOf(responseStr);
			}
		}
		BaseRecord audit = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		
		try {
			if(contextUser != null) {
				audit.set(FieldNames.FIELD_CONTEXT_USER, contextUser);
				audit.set(FieldNames.FIELD_ORGANIZATION_ID, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			}
			if(subject != null) {
				audit.set(FieldNames.FIELD_SUBJECT, subject);
				audit.set(FieldNames.FIELD_SUBJECT_TYPE, subject.getSchema());
			}
			if(resource != null) {
				audit.set(FieldNames.FIELD_RESOURCE, resource);
				audit.set(FieldNames.FIELD_RESOURCE_TYPE, resource.getSchema());
			}
			
			audit.set(FieldNames.FIELD_ACTION, aet);
			audit.set(FieldNames.FIELD_RESPONSE, ret);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return audit;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
	    GregorianCalendar cal = new GregorianCalendar();
	    cal.setTime(new Date());
	    cal.add(GregorianCalendar.YEAR, 1);
		try {
			newRecord.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		return newRecord;
	}
	
	
}
