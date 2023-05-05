package org.cote.accountmanager.factory;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.SpoolBucketEnumType;
import org.cote.accountmanager.schema.type.SpoolNameEnumType;
import org.cote.accountmanager.schema.type.ValueEnumType;
import org.cote.accountmanager.util.ParameterUtil;

public class TokenFactory extends FactoryBase {
	public static int DEFAULT_TOKEN_EXPIRY_SECONDS = 30;
	public static int DEFAULT_TOKEN_EXPIRY_HOURS = 6;
	
	public TokenFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{

		long organizationId = 0L;
		long ownerId = 0L;
		int expiry = 0;
		String name = null;
		if(contextUser != null) {
			organizationId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
			ownerId = contextUser.get(FieldNames.FIELD_ID);
		}
		if(parameterList != null) {
			expiry = ParameterUtil.getParameter(parameterList, "expirySeconds", Integer.class, DEFAULT_TOKEN_EXPIRY_SECONDS);
			name = ParameterUtil.getParameter(parameterList, "name", String.class, UUID.randomUUID().toString());
		}

		BaseRecord newToken = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		try {
			newToken.set(FieldNames.FIELD_SPOOL_BUCKET_TYPE, SpoolBucketEnumType.SECURITY_TOKEN.toString());
			newToken.set(FieldNames.FIELD_SPOOL_BUCKET_NAME, SpoolNameEnumType.GENERAL.toString());
			newToken.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			newToken.set(FieldNames.FIELD_OWNER_ID, ownerId);
			newToken.set(FieldNames.FIELD_NAME, name);
			newToken.set(FieldNames.FIELD_VALUE_TYPE, ValueEnumType.STRING.toString());
			newToken.set(FieldNames.FIELD_EXPIRES, true);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		return newToken;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		Date now = new Date();
		GregorianCalendar cal = new GregorianCalendar();
	    cal.setTime(now);
		cal.add(GregorianCalendar.HOUR, DEFAULT_TOKEN_EXPIRY_HOURS);
		try {
			newRecord.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		return newRecord;
	}
}
