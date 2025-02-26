package org.cote.accountmanager.factory;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
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
		/*
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
		*/
		BaseRecord subject = null;
		BaseRecord submitter = null;
		BaseRecord resource = null;
		BaseRecord entitlement = null;
		BaseRecord requester = null;
		long parentId = 0L;
		
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
			entitlement = parameterList.getParameter(FieldNames.FIELD_ENTITLEMENT, BaseRecord.class, null);
			subject = parameterList.getParameter(FieldNames.FIELD_SUBJECT, BaseRecord.class, null);
			submitter = parameterList.getParameter(FieldNames.FIELD_SUBMITTER, BaseRecord.class, null);
			requester = parameterList.getParameter(FieldNames.FIELD_REQUESTER, BaseRecord.class, null);
			resource = parameterList.getParameter(FieldNames.FIELD_RESOURCE, BaseRecord.class, null);
			// parentId = parameterList.getParameter(FieldNames.FIELD_PARENT_ID, Long.class, 0L);
		}
		BaseRecord access = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		
		
		try {
			List<BaseRecord> msgs = access.get(FieldNames.FIELD_MESSAGES);
			BaseRecord msg = RecordFactory.newInstance(ModelNames.MODEL_SPOOL);
			msg.set(FieldNames.FIELD_DATA, ("AccessRequestFactory: " + UUID.randomUUID()).getBytes());
			msgs.add(msg);
			access.set(FieldNames.FIELD_NAME, "Access Request - " + UUID.randomUUID().toString());
			
			if(contextUser != null) {
				access.set(FieldNames.FIELD_ORGANIZATION_ID, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			}
			if(requester != null) {
				access.set(FieldNames.FIELD_REQUESTER, requester.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID}));
				access.set(FieldNames.FIELD_REQUESTER_TYPE, requester.getAMModel());
			}			
			if(subject != null) {
				access.set(FieldNames.FIELD_SUBJECT, subject.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID}));
				access.set(FieldNames.FIELD_SUBJECT_TYPE, subject.getAMModel());
			}
			if(submitter != null) {
				access.set(FieldNames.FIELD_SUBMITTER, submitter.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID}));
				access.set(FieldNames.FIELD_SUBMITTER_TYPE, submitter.getAMModel());
			}
			if(entitlement != null) {
				access.set(FieldNames.FIELD_ENTITLEMENT, entitlement.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID}));
				access.set(FieldNames.FIELD_ENTITLEMENT_TYPE, entitlement.getAMModel());
			}
			/*
			if(parentId > 0L) {
				access.set(FieldNames.FIELD_PARENT_ID, parentId);
			}
			*/
			if(resource != null) {
				if(RecordUtil.isIdentityRecord(resource)) {
					access.set(FieldNames.FIELD_RESOURCE, resource.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID}));
				}
				else {
					access.set(FieldNames.FIELD_RESOURCE_DATA, resource);
				}
				access.set(FieldNames.FIELD_RESOURCE_TYPE, resource.getAMModel());
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
