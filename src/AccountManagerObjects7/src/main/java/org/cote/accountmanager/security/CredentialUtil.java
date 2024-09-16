package org.cote.accountmanager.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.util.ParameterUtil;

public class CredentialUtil {
	public static final Logger logger = LogManager.getLogger(CredentialUtil.class);
	
	public static BaseRecord newCredential(BaseRecord user, BaseRecord icred) {
		return newPasswordCredential(user, new String((byte[])icred.get(FieldNames.FIELD_CREDENTIAL)));
	}
	
	public static BaseRecord newPasswordCredential(BaseRecord user, String credStr) {
		BaseRecord newCred = null;
		try {
			ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_PASSWORD, credStr);
			plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			newCred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, user, null, plist);
			// IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
		}
		catch(FactoryException e) {
			logger.error(e);
		}
		return newCred;
	}
	
    public static BaseRecord getLatestCredential(BaseRecord user) {
    	Query q = IOSystem.getActiveContext().getRecordUtil().getLatestReferenceQuery(user, ModelNames.MODEL_CREDENTIAL);
    	q.planMost(false);
    	return IOSystem.getActiveContext().getRecordUtil().getRecordByQuery(q);
    }
	
}
