package org.cote.accountmanager.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.ParameterUtil;

public class AgentUtil {
	public static final Logger logger = LogManager.getLogger(AgentUtil.class);
	
	 public static List<BaseRecord> findObjects(BaseRecord user, String modelName, List<BaseRecord> queryFields, String[] requestFields){
	      Query query = QueryUtil.createQuery(modelName, FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
	      query.planMost(false);
	      query.setRequestRange(0, 10);
	      query.setRequest(requestFields);
	      /*
	      if(queryFields != null){
		      List<String> requestFieldsList = query.get(FieldNames.FIELD_REQUEST);

	    	  for(BaseRecord field : queryFields){
	    		 String qname = field.get(FieldNames.FIELD_NAME);
                 query.field(
                     qname,
                     field.getEnum("comparator"),
                     field.get("value")
                 );
                 if(!requestFieldsList.contains(qname)) {
                	 requestFieldsList.add(qname);
                 }
	          }
	      }
	      */
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
	
    public static <T> T sanitizeFieldValue(BaseRecord field) {
    	T value = field.get(FieldNames.FIELD_VALUE);
    	if(field.getEnum(FieldNames.FIELD_VALUE_TYPE) == FieldEnumType.STRING) {
			String strVal = field.get(FieldNames.FIELD_VALUE);
			if(strVal != null && !strVal.isEmpty()) {
				strVal = strVal.trim();
				if(strVal.startsWith("'") && strVal.endsWith("'")) {
					strVal = strVal.substring(1, strVal.length() - 1);
				}
				value = (T)strVal;
			}
		}
		return value;
	}
    
    public static List<BaseRecord> sanitizeQueryFields(BaseRecord user, String model, List<BaseRecord> queryFields){
    	List<BaseRecord> sanitizedFields = new ArrayList<>();
    	ModelSchema ms = RecordFactory.getSchema(model);
    	for(BaseRecord field : queryFields) {
    		BaseRecord sanitizedField = field;
    		FieldSchema fs = ms.getFieldSchema(field.get(FieldNames.FIELD_NAME));
    		if(fs.getFieldType() != field.getEnum("valueType")) {
    			logger.warn("Aligning value type from " + field.get("valueType") + " to " + fs.getFieldType().toString());
    			if(fs.isForeign() && fs.getFieldType() == FieldEnumType.MODEL && fs.getBaseModel() != null && field.getEnum("valueType") == FieldEnumType.STRING) {
					logger.info("Try to find a foreign reference to " + fs.getBaseModel() + " with case insensitive fuzzy search for '" + field.get(FieldNames.FIELD_VALUE) + "'");
					
					Query fqu = QueryUtil.createQuery(fs.getBaseModel(), FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
					ComparatorEnumType comp = field.getEnum(FieldNames.FIELD_COMPARATOR);
					if(comp == ComparatorEnumType.EQUALS) {
						comp = ComparatorEnumType.ILIKE;
					}
					String value = sanitizeFieldValue(field);
					fqu.field(FieldNames.FIELD_NAME, comp, value);
					fqu.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ORGANIZATION_ID});
					logger.info(fqu.toFullString());
					BaseRecord[] refs = IOSystem.getActiveContext().getSearch().findRecords(fqu);
					String refIds = Arrays.stream(refs).map(r -> Long.toString(r.get(FieldNames.FIELD_ID))).collect(Collectors.joining(","));
					//logger.info("Ref Ids: '" + refIds + "' from " + refs.length);
					if(refIds.isEmpty()) {
						logger.warn("No references found for " + field.get(FieldNames.FIELD_NAME) + " with value " + field.get(FieldNames.FIELD_VALUE));
					}
					else {
						logger.info("Sanitized " + fs.getName() + " to use " + refs.length + " possible references");
					}

					sanitizedField = new QueryField();
					
					try {
						sanitizedField.set(FieldNames.FIELD_NAME, field.get(FieldNames.FIELD_NAME));
						sanitizedField.setString(FieldNames.FIELD_VALUE, refIds);
						sanitizedField.set(FieldNames.FIELD_COMPARATOR, ComparatorEnumType.IN);
					} catch (ValueException | ModelException | FieldException | ModelNotFoundException e) {
						logger.error(e);
						return new ArrayList<>();
					}
					

				} else {
					field.setValue(FieldNames.FIELD_VALUE_TYPE, fs.getFieldType());
				}
    		}
    		sanitizedFields.add(sanitizedField);
    		//if(fs.getFieldType() == FieldEnumType.MODEL)
    	}
    	return sanitizedFields;
    }
}
