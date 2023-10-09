package org.cote.accountmanager.factory;


import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ParameterUtil;

public class KeyFactory  extends FactoryBase {
	
	public KeyFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{

		BaseRecord keySet = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		return keySet;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		CryptoBean keySet = new CryptoBean(rec);
		boolean isKeyPair = false;
		boolean isSecretKey = false;
		boolean isSalt = false;
		if(parameterList != null) {
			isKeyPair = ParameterUtil.getParameter(parameterList, "keyPair", Boolean.class, false);
			isSecretKey = ParameterUtil.getParameter(parameterList, "secretKey", Boolean.class, false);
			isSalt = ParameterUtil.getParameter(parameterList, "salt", Boolean.class, false);
		}
		
		try {
			if(isKeyPair) {
				CryptoFactory.getInstance().generateKeyPair(keySet);
				if(contextUser != null) {
					IOSystem.getActiveContext().getRecordUtil().applyOwnership(contextUser, keySet.get(FieldNames.FIELD_PUBLIC), contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
					IOSystem.getActiveContext().getRecordUtil().applyOwnership(contextUser, keySet.get(FieldNames.FIELD_PRIVATE), contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				}
			}
			if(isSecretKey) {
				CryptoFactory.getInstance().generateSecretKey(keySet);
				if(contextUser != null) {
					IOSystem.getActiveContext().getRecordUtil().applyOwnership(contextUser, keySet.get(FieldNames.FIELD_CIPHER), contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				}
			}
			if(isSalt) {
				CryptoFactory.getInstance().setSalt(keySet);
				if(contextUser != null) {
					IOSystem.getActiveContext().getRecordUtil().applyOwnership(contextUser, keySet.get(FieldNames.FIELD_HASH), contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				}
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return keySet;

	}
}