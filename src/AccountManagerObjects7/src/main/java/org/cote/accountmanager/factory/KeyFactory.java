package org.cote.accountmanager.factory;


import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryWriter;
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
		/*
		String name = null;
		String path = null;
		long groupId = 0L;
		if(parameterList != null) {
			name = ParameterUtil.getParameter(parameterList, "name", String.class, null);
			path = ParameterUtil.getParameter(parameterList, "path", String.class, null);
			if(name == null) {
				name = UUID.randomUUID().toString();
			}
			if(path != null && contextUser != null) {
				BaseRecord group1 = IOSystem.getActiveContext().getPathUtil().makePath(contextUser, ModelNames.MODEL_GROUP, path, "DATA", contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				if(group1 != null) {
					groupId = group1.get(FieldNames.FIELD_ID);
				}
			}
		}
		try {
			keySet.set(FieldNames.FIELD_NAME, name);
			keySet.set(FieldNames.FIELD_GROUP_ID, groupId);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		*/
		/*
		CryptoFactory.getInstance().generateKeyPair(keySet);
		CryptoFactory.getInstance().generateSecretKey(keySet);
		CryptoFactory.getInstance().setSalt(keySet);
		*/
		/*
		sec.setOrganizationKey(organizationKey);
		sec.setGlobalKey(globalKey);
		sec.setPrimaryKey(primaryKey);
		SecurityType lastPrimary = null;
		if(primaryKey){
			if(owner !=null) lastPrimary = getPrimaryAsymmetricKey(owner);
			else if(organizationKey) lastPrimary = getPrimaryAsymmetricKey(organizationId);
			if(lastPrimary != null) sec.setPreviousKeyId(lastPrimary.getId());
		}
		
		if(symmetricKey != null){
			if(symmetricKey.getPublicKey() == null) throw new ArgumentException("Secret key was specified but is null");
			sec.setSecretKey(symmetricKey.getSecretKey());
			sec.setEncryptCipherKey(true);
			sec.setSymmetricKeyId(symmetricKey.getId());
		}
		
		sec.setOrganizationId(organizationId);
		sec.setOwnerId((owner != null ? owner.getId() : 0L));
		sec.setNameType(NameEnumType.SECURITY);
	
		try{
			if(
				SecurityFactory.getSecurityFactory().generateKeyPair(sec)
			){
				if(bulkSessionId != null){
					BulkFactories.getBulkFactory().createBulkEntry(bulkSessionId, FactoryEnumType.ASYMMETRICKEY, sec);
				}
				else if(((AsymmetricKeyFactory)Factories.getFactory(FactoryEnumType.ASYMMETRICKEY)).add(sec)){
					SecurityType secm = ((AsymmetricKeyFactory)Factories.getFactory(FactoryEnumType.ASYMMETRICKEY)).getByObjectId(sec.getObjectId(), sec.getOrganizationId());
					if(secm != null) sec.setId(secm.getId());
					else{
						logger.error("Failed to retrieve key");
						sec = null;
					}
				}
				else{
					logger.error("Failed to persist key");
				}
				if(lastPrimary != null){
					lastPrimary.setPrimaryKey(false);
					if(bulkSessionId != null) ((INameIdFactory)Factories.getBulkFactory(FactoryEnumType.ASYMMETRICKEY)).update(lastPrimary);
					else ((AsymmetricKeyFactory)Factories.getFactory(FactoryEnumType.ASYMMETRICKEY)).update(lastPrimary);
				}
			}
		}
		catch(FactoryException | ArgumentException e) {
			logger.error(e.getMessage());
			logger.errkor(FactoryException.LOGICAL_EXCEPTION,e);
		} 
		*/
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