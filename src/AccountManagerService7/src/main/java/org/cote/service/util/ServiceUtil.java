package org.cote.service.util;

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.OrderEnumType;



public class ServiceUtil {
	public static final Logger logger = LogManager.getLogger(ServiceUtil.class);
	private static Map<UserPrincipal, BaseRecord> principalCache = Collections.synchronizedMap(new HashMap<>());
	
	public static void clearCache() {
		principalCache.clear();
	}
	
	public static QueryResult generateListQueryResponse(String type, String objectId, String name, long startIndex, int recordCount, HttpServletRequest request) {
		return generateListQueryResponse(type, objectId, name, new String[0], startIndex, recordCount, request);
	}
	public static QueryResult generateListQueryResponse(String type, String objectId, String name, String[] fields, long startIndex, int recordCount, HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.buildQuery(user, type, objectId, name, startIndex, recordCount);
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms.hasField(FieldNames.FIELD_NAME)) {
			try {
				q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
				q.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		if(q == null) {
			logger.error("Invalid query object for " + type + " " + objectId);
			return null;	
		}
		q.setRequest(fields);
		return IOSystem.getActiveContext().getAccessPoint().list(user, q);
	}
	public static BaseRecord generateRecordQueryResponse(String type, String objectId, String name, HttpServletRequest request) {
		return generateRecordQueryResponse(type, objectId, name, new String[0], request);
	}
	public static BaseRecord generateRecordQueryResponse(String type, String objectId, String name, String[] fields, HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.buildQuery(user, type, objectId, name, 0L, 0);
		if(q == null) {
			logger.error("Invalid query object for " + type + " " + objectId);
			return null;	
		}
		q.setRequest(fields);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}
	
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
