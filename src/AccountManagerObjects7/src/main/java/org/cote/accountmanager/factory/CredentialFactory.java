package org.cote.accountmanager.factory;

import java.util.Date;
import java.util.GregorianCalendar;

import org.bouncycastle.util.Arrays;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.ParameterUtil;

public class CredentialFactory extends FactoryBase {
	
	public CredentialFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}

	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		logger.info("Credential Factory: newInstance");

		BaseRecord owner = contextUser;//arguments[0];
		BaseRecord targetObject = contextUser;//arguments[1];
		if(arguments.length > 0 && arguments[0] != null) {
			targetObject = arguments[0];
		}
		BaseRecord cred = null;
		try {
			if (owner == null || targetObject == null) {
				// logger.info(JSONUtil.exportObject(owner, RecordSerializerConfig.getUnfilteredModule()));
				// logger.info(JSONUtil.exportObject(targetObject, RecordSerializerConfig.getUnfilteredModule()));
				throw new ModelException("Null arguments");
			}
			cred = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
			long oid = owner.get(FieldNames.FIELD_ID);
			long tid = targetObject.get(FieldNames.FIELD_ID);
			if(tid == 0L || oid == 0L) throw new ModelException("Invalid identifiers");
			
			//cred = RecordFactory.newInstance(ModelNames.MODEL_CREDENTIAL);
			cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.UNKNOWN.toString());
			cred.set(FieldNames.FIELD_REFERENCE_TYPE, targetObject.getModel());
			cred.set(FieldNames.FIELD_REFERENCE_ID, tid);
			if(parameterList != null) {
				String type = ParameterUtil.getParameter(parameterList, "type", String.class, null);
				String pwd = ParameterUtil.getParameter(parameterList, "password", String.class, null);
				if(pwd != null && type != null && type.toUpperCase().equals(CredentialEnumType.HASHED_PASSWORD.toString())) {
					cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD);
					cred.set(FieldNames.FIELD_HASH, modelFactory.newInstance(ModelNames.MODEL_HASH, contextUser));
					CryptoFactory.getInstance().setSalt(cred);
					byte[] salt = cred.get(FieldNames.FIELD_HASH_FIELD_SALT);
					cred.set(FieldNames.FIELD_CREDENTIAL, CryptoUtil.getDigest(pwd.getBytes(), cred.get(FieldNames.FIELD_HASH_FIELD_SALT)));
				}
			}
			//cred.set(FieldNames.FIELD_HASH, IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_HASH));
			//CryptoFactory.getInstance().setSalt(cred);

			GregorianCalendar cal = new GregorianCalendar();
		    cal.setTime(new Date());
		    
		}
		catch(ModelException | FieldException | ValueException | ModelNotFoundException e) {
			throw new FactoryException(e);
		}
		return cred;
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
	
	@Override
	public VerificationEnumType verify(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		VerificationEnumType vet = VerificationEnumType.NOT_VERIFIED;
		if(rec != null && parameterList != null) {
			CredentialEnumType cet = CredentialEnumType.valueOf(rec.get(FieldNames.FIELD_TYPE));
			if(cet == CredentialEnumType.HASHED_PASSWORD) {
				String pwd = ParameterUtil.getParameter(parameterList, "password", String.class, null);
				if(pwd == null) {
					logger.error("Missing parameter: password");
					vet = VerificationEnumType.ERROR;
				}
				else {
					BaseRecord hash = rec.get(FieldNames.FIELD_HASH);
					if(hash != null) {
						IOSystem.getActiveContext().getReader().populate(hash);
					}
					byte[] credHash = rec.get(FieldNames.FIELD_CREDENTIAL);
					byte[] checkHash = CryptoUtil.getDigest(pwd.getBytes(), rec.get(FieldNames.FIELD_HASH_FIELD_SALT));
					if(Arrays.areEqual(credHash, checkHash)) {
						logger.info("Password hash matches");
						vet = VerificationEnumType.VERIFIED;
					}
					else {
						logger.warn("Password hash does not match");
						logger.info(BinaryUtil.toBase64Str(credHash));
						logger.info(BinaryUtil.toBase64Str(checkHash));
						vet = VerificationEnumType.NOT_VERIFIED;
					}
				}
			}
			else {
				logger.warn("Unhandled credential type: " + cet);
			}
		}
		else {
			logger.warn("Record or parameterList was null");
		}
		return vet;
	}
	
}
