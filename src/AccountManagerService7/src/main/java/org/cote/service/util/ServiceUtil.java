package org.cote.service.util;

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;



public class ServiceUtil {
	public static final Logger logger = LogManager.getLogger(ServiceUtil.class);
	private static Map<UserPrincipal, BaseRecord> principalCache = Collections.synchronizedMap(new HashMap<>());
	
	public static BaseRecord getPrincipalUser(HttpServletRequest request){
		Principal principal = request.getUserPrincipal();
		BaseRecord outUser = null;
		if(principal != null && principal instanceof UserPrincipal){
			UserPrincipal userp = (UserPrincipal)principal;
			if(principalCache.containsKey(userp)) {
				return principalCache.get(userp);
			}
			

			BaseRecord org = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, userp.get(FieldNames.FIELD_ORGANIZATION_PATH), null, 0L);
			BaseRecord user = IOSystem.getActiveContext().getRecordUtil().getRecord(null, ModelNames.MODEL_USER, userp.get(FieldNames.FIELD_NAME), 0L, 0L, org.get(FieldNames.FIELD_ID));
			if(user != null){
				outUser = user;
				principalCache.put(userp, user);
			}
			else {
				logger.warn("User is null for " + userp.get(FieldNames.FIELD_NAME) + " in " + org.get(FieldNames.FIELD_ID));
			}

		}
		else{
			logger.debug("Don't know what: " + (principal == null ? "Null" : "Uknown") + " principal");
		}
		return outUser;
	}
}
