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
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.cote.accountmanager.util.RecordUtil;

public class AccessRequestFactory extends FactoryBase {
	
	public AccessRequestFactory(Factory modelFactory, ModelSchema schema) {
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
		ApprovalResponseEnumType apet = ApprovalResponseEnumType.UNKNOWN;
		if(parameterList != null) {
			String actionStr = parameterList.getParameter(FieldNames.FIELD_ACTION, String.class, null);
			String responseStr = parameterList.getParameter(FieldNames.FIELD_RESPONSE, String.class, null);
			if(actionStr != null) {
				aet = ActionEnumType.valueOf(actionStr);
			}
			if(responseStr != null) {
				apet = ApprovalResponseEnumType.valueOf(responseStr);
			}
		}
		BaseRecord access = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		
		try {
			if(contextUser != null) {
				access.set(FieldNames.FIELD_ORGANIZATION_ID, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			}

			
			if(subject != null) {
				access.set(FieldNames.FIELD_SUBJECT, subject);
				access.set(FieldNames.FIELD_SUBJECT_TYPE, subject.getModel());
			}
			if(resource != null) {
				if(RecordUtil.isIdentityRecord(resource)) {
					access.set(FieldNames.FIELD_RESOURCE, resource);
				}
				else {
					access.set(FieldNames.FIELD_RESOURCE_DATA, resource);
				}
				access.set(FieldNames.FIELD_RESOURCE_TYPE, resource.getModel());
			}
			access.set(FieldNames.FIELD_ACTION, aet);
			access.set(FieldNames.FIELD_APPROVAL_STATUS, apet);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return access;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
	    GregorianCalendar cal = new GregorianCalendar();
	    cal.setTime(new Date());
	    cal.add(GregorianCalendar.DAY_OF_MONTH, 1);
		try {
			newRecord.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		return newRecord;
	}
	
}
