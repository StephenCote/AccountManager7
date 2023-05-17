package org.cote.accountmanager.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;

public class AccessPoint {
	public static final Logger logger = LogManager.getLogger(AccessPoint.class);
	
	private IOContext context = null;
	public AccessPoint(IOContext context) {
		this.context = context; 
	}

	public BaseRecord update(BaseRecord contextUser, BaseRecord object) {
		BaseRecord outObj = null;
		BaseRecord cobj = object.copyRecord();
		
		ActionEnumType aet = ActionEnumType.ADD;
		if(RecordUtil.isIdentityRecord(cobj)) {
			aet = ActionEnumType.MODIFY;
		}

		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, cobj);
		PolicyResponseType prr = null;
		if(aet == ActionEnumType.MODIFY) {
			prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(contextUser, contextUser, cobj);
		}
		else {
			prr = IOSystem.getActiveContext().getAuthorizationUtil().canCreate(contextUser, contextUser, cobj);
		}
		if(prr.getType() == PolicyResponseEnumType.PERMIT) {
			if(
				(aet == ActionEnumType.MODIFY && context.getRecordUtil().updateRecord(cobj))
				||
				(aet == ActionEnumType.ADD && context.getRecordUtil().createRecord(cobj))
			) {
				AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, null);
				outObj = cobj;
			}
			else {
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Failed to create record");
			}
		}
		else {
			AuditUtil.closeAudit(audit, prr, null);
		}
		return outObj;
	}
	
	public BaseRecord create(BaseRecord contextUser, BaseRecord object) {
		return update(contextUser, object);
	}
	
	public boolean delete(BaseRecord contextUser, BaseRecord object) {
		boolean outBool = false;

		ActionEnumType aet = ActionEnumType.DELETE;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, object);
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canDelete(contextUser, contextUser, object);
		if(prr.getType() == PolicyResponseEnumType.PERMIT) {
			if(context.getRecordUtil().deleteRecord(object)) {
				AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, null);
				outBool = true;
			}
			else {
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Failed to delete record");
			}
		}
		else {
			AuditUtil.closeAudit(audit, prr, null);
		}
		return outBool;
		
	}
	
	public BaseRecord findByObjectId(BaseRecord contextUser, String model, String objectId) {
		if(model == null || objectId == null) {
			failAudit(ActionEnumType.READ, ResponseEnumType.INVALID, contextUser, null, null, "Invalid model or objectId");
			return null;
		}
		return find(contextUser, QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId));
	}
	
	public BaseRecord findById(BaseRecord contextUser, String model, long id) {
		if(model == null || id <= 0L) {
			failAudit(ActionEnumType.READ, ResponseEnumType.INVALID, contextUser, null, null, "Invalid model or id");
			return null;
		}
		return find(contextUser, QueryUtil.createQuery(model, FieldNames.FIELD_ID, id));
	}

	public BaseRecord findByUrn(BaseRecord contextUser, String model, String urn) {
		if(model == null || urn == null) {
			failAudit(ActionEnumType.READ, ResponseEnumType.INVALID, contextUser, contextUser, null, "Invalid model or urn");
			return null;
		}
		return find(contextUser, QueryUtil.createQuery(model, FieldNames.FIELD_URN, urn));
	}
	
	public BaseRecord find(BaseRecord contextUser, String model, String path, String type) {
		return makeFind(contextUser, model, path, type, false);
	}
	
	public BaseRecord make(BaseRecord contextUser, String model, String path, String type) {
		return makeFind(contextUser, model, path, type, true);
	}
	
	protected BaseRecord makeFind(BaseRecord contextUser, String model, String path, String type, boolean make) {

		BaseRecord rec = null;
		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		AuditUtil.query(audit, model + " " + path + " " + type);
		BaseRecord chkRec = null;
		if(!make) {
			chkRec = IOSystem.getActiveContext().getPathUtil().findPath(contextUser, model, path, type, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		else {
			chkRec = IOSystem.getActiveContext().getPathUtil().makePath(contextUser, model, path, type, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		if(chkRec != null) {
			
			PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canRead(contextUser, contextUser, chkRec);
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				AuditUtil.auditResource(audit, chkRec);
				rec = chkRec;
			}
			AuditUtil.closeAudit(audit, prr, null);
		}
		else {
			failAudit(aet, ResponseEnumType.INVALID, contextUser, contextUser, null, "No results");
		}

		return rec;
	}

	public BaseRecord find(BaseRecord contextUser, Query query) {
		BaseRecord rec = null;
		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		PolicyResponseType prr = null;
		if((prr = authorizeQuery(contextUser, query)) == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prr, "One or more query fields were not or could not be authorized: " + query.key());
			// logger.info(query.toFullString());
			if(prr != null) {
				logger.error(prr.toFullString());
			}
			/*
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
			*/
			return rec;
		}
		QueryResult qr = search(contextUser, query);
		AuditUtil.query(audit, query.key());
		if(qr != null && qr.getCount() > 0) {
			BaseRecord chkRec = qr.getResults()[0];
			AuditUtil.auditResource(audit, rec);
			prr = IOSystem.getActiveContext().getAuthorizationUtil().canRead(contextUser, contextUser, chkRec);
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				rec = chkRec;
			}
			AuditUtil.closeAudit(audit, prr, null);
		}
		else {
			// logger.info(query.toFullString());
			failAudit(aet, ResponseEnumType.INVALID, contextUser, contextUser, null, "No results");
		}

		return rec;
	}

	protected <T> Query getIdQuery(BaseRecord contextUser, String model, String identityField, T identityValue) {
		Query q = QueryUtil.createQuery(model, identityField, identityValue);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_URN, FieldNames.FIELD_ORGANIZATION_ID});
		return q;
	}
	public BaseRecord findByNameInGroup(BaseRecord contextUser, String model, long groupId, String name) {
		return findByNameInHierarchy(contextUser, FieldNames.FIELD_GROUP_ID, groupId, model, name, null);
	}
	public BaseRecord findByNameInParent(BaseRecord contextUser, String model, long parentId, String name, String type) {
		return findByNameInHierarchy(contextUser, FieldNames.FIELD_PARENT_ID, parentId, model, name, type);
	}
	
	public BaseRecord findByNameInGroup(BaseRecord contextUser, String model, String groupObjectId, String name) {
		// BaseRecord group = findByObjectId(contextUser, ModelNames.MODEL_GROUP, groupObjectId);
		Query groupq = getIdQuery(contextUser, ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, groupObjectId);
		// CacheUtil.clearCache();
		BaseRecord group = find(contextUser, groupq);
		BaseRecord rec = null;
		if(group != null) {
			rec = findByNameInHierarchy(contextUser, group, FieldNames.FIELD_GROUP_ID, model, name, null);
		}
		else {
			logger.error("Failed to find group: " + groupObjectId);
		}
		return rec;
	}
	
	public BaseRecord findByNameInParent(BaseRecord contextUser, String model, String parentObjectId, String name, String type) {
		BaseRecord parent = findByObjectId(contextUser, model, parentObjectId);
		BaseRecord rec = null;
		if(parent != null) {
			rec = findByNameInHierarchy(contextUser, parent, FieldNames.FIELD_PARENT_ID, model, name, type);
		}
		else {
			logger.error("Failed to find parent: " + parentObjectId);
		}
		return rec;
	}
	
	private BaseRecord findByNameInHierarchy(BaseRecord contextUser, BaseRecord parent, String hierarchyFieldName, String model, String name, String type) {
		return findByNameInHierarchy(contextUser, hierarchyFieldName, (long)parent.get(FieldNames.FIELD_ID), model, name, type);
	}
	private <T> BaseRecord findByNameInHierarchy(BaseRecord contextUser, String hierarchyFieldName, T hierarchyFieldValue, String model, String name, String type) {
		Query query = QueryUtil.createQuery(model, hierarchyFieldName, hierarchyFieldValue);
		query.field(FieldNames.FIELD_NAME, name);
		if(type != null) {
			query.field(FieldNames.FIELD_TYPE, type);
		}
		// logger.info(hierarchyFieldName + " = " + hierarchyFieldValue);
		logger.info(query.toFullString());
		return find(contextUser, query);
	}
	


	
	private QueryResult search(BaseRecord contextUser, Query query) {
		QueryResult qr = null;
		try {
			query.setContextUser(contextUser);
			query.field(FieldNames.FIELD_ORGANIZATION_ID, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			qr = IOSystem.getActiveContext().getSearch().find(query);
		} catch (ReaderException | IndexException e) {
			logger.error(e);
		}
		return qr;
	}
	
	
	/// LIST - 
	///		The AccountManager 5 security model was to restrict lists to the parent or group, or (in 6) use a very elaborate dynamic sql statement to evaluate the authorization rules
	///		I special set of functions are written for PostgreSQL to handle this.  However, while testing directly against a file-based or H2-based persistence layer, I want to avoid the tight coupling before
	///		the general approach is nailed down.  Because I wanted AM 7 to be primarily PBAC at the core, then authorization decisions should follow the policy
	///		In other words - I need to write a PolicyEvaluator than can do so with direct database calls
	///		In the meantime, the count isn't checked, and the list/search by query is restricted to group/parent based
	///
	
	public int count(BaseRecord contextUser, Query query) {

		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		AuditUtil.query(audit, query.key());
		PolicyResponseType prr = null;
		if((prr = authorizeQuery(contextUser, query)) == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prr, "One or more query fields were not or could not be authorized: " + query.key());
			return 0;
		}
		int count = IOSystem.getActiveContext().getSearch().count(query);
		AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, null);

		return count;
	}	
	public QueryResult list(BaseRecord contextUser, Query query) {
		QueryResult qr = null;
		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		AuditUtil.query(audit, query.key());
		PolicyResponseType prr = null;
		if((prr = authorizeQuery(contextUser, query)) == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prr, "One or more query fields were not or could not be authorized: " + query.key());
			return getFailedResponse(query, "Query not authorized");
		}
		qr = search(contextUser, query);
		AuditUtil.closeAudit(audit, prr, null);
		return qr;
	}
	
	private QueryResult getFailedResponse(Query query, String message) {
		QueryResult qr = new QueryResult(query);
		qr.setResponse(OperationResponseEnumType.FAILED, message);
		return qr;
	}
	
	private void failAudit(ActionEnumType act, ResponseEnumType ret, BaseRecord contextUser, BaseRecord actor, BaseRecord resource, String msg) {
		BaseRecord audit = null;
		try {
			audit = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_AUDIT, contextUser, null, ParameterUtil.newParameterList(FieldNames.FIELD_ACTION, act), actor, resource);
		} catch (FactoryException e) {
			logger.error(e);
		}
		AuditUtil.closeAudit(audit, ret, msg);
	}
	
	
	private PolicyResponseType authorizeQuery(BaseRecord contextUser, Query query) {
		
		if(query == null) {
			logger.error("Null query or user");
			return null;
		}
		PolicyResponseType[] prrs = context.getPolicyUtil().evaluateQueryToReadPolicyResponses(contextUser, query);
		PolicyResponseType prr = null;
		PolicyResponseType vprr = null;
		for(PolicyResponseType pr : prrs) {
			if(vprr == null && pr.getType() == PolicyResponseEnumType.PERMIT) {
				vprr = pr;
			}
			if(pr.getType() != PolicyResponseEnumType.PERMIT) {
				prr = pr;
				break;
			}
		}
		return (prr != null ? prr : vprr);
	}
	
	
}
