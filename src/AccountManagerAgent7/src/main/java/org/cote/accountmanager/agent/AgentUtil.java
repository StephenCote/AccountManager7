package org.cote.accountmanager.agent;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.ParameterUtil;

public class AgentUtil {
	public static final Logger logger = LogManager.getLogger(AgentUtil.class);
	
	 public static List<BaseRecord> findObjects(BaseRecord user, String modelName, List<BaseRecord> queryFields){
	      Query query = QueryUtil.createQuery(modelName, FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
	      if(queryFields != null){
	          for(BaseRecord field : queryFields){
                 query.field(
                     field.get("name"),
                     field.getEnum("comparator"),
                     field.get("value")
                 );
	          }
	      }
	      query.planMost(true);
	      return Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(query));
	 }
	
	public static BaseRecord login(String orgPath, String userName, String password) {
		boolean outBool = false;
		BaseRecord orgType = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, orgPath, null, 0);
        BaseRecord usr = null;
        IOContext ioContext = IOSystem.getActiveContext();
        if(orgType != null) {
        	OrganizationContext orgContext = ioContext.getOrganizationContext(orgPath, OrganizationEnumType.UNKNOWN);
        	if(orgContext == null) {
        		logger.error("Could not establish organization context");
        		return null;
        	}
        	usr = IOSystem.getActiveContext().getRecordUtil().getRecord(null, ModelNames.MODEL_USER, userName, 0L, 0L, orgType.get(FieldNames.FIELD_ID));
        	if(usr != null) {
        		VerificationEnumType vet = VerificationEnumType.UNKNOWN;
        		BaseRecord cred = CredentialUtil.getLatestCredential(usr);
        		if(cred != null) {
	        		try {
						vet = ioContext.getFactory().verify(usr, cred, ParameterUtil.newParameterList("password", password));
					} catch (FactoryException e) {
						logger.error(e);
					}
        		}
        		else {
        			logger.warn("Null credential");
        		}
        		
        		if(vet != VerificationEnumType.VERIFIED) {
        			logger.warn("Failed to verify credential: " + vet.toString());
        			usr = null;
        		}
        	}
        	else {
        		logger.warn("Failed to find user " + userName);
        	}
        }
		return usr;
	}
}
