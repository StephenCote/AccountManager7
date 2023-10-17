package org.cote.accountmanager.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.FieldLockUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;

public class AccessPoint {
	public static final Logger logger = LogManager.getLogger(AccessPoint.class);
	
	private IOContext context = null;
	public AccessPoint(IOContext context) {
		this.context = context; 
	}
	
	public int countMembers(BaseRecord user, BaseRecord container, String model, BaseRecord effect) {
		Query q = QueryUtil.createQuery(model);
		q.filterParticipation(container, null, model, effect);
		return count(user, q);
	}
	
	public List<BaseRecord> listMembers(BaseRecord user, BaseRecord container, String model, BaseRecord effect, long startIndex, int recordCount) {
		Query q = QueryUtil.createQuery(model);
		q.filterParticipation(container, null, model, effect);
		q.setRequestRange(startIndex, recordCount);
		QueryResult qr = list(user, q);
		if(qr == null) {
			return new ArrayList<>();
		}
		return Arrays.asList(qr.getResults());
	}
	
	public boolean isMember(BaseRecord user, BaseRecord object, BaseRecord actor) {
		return isMember(user, object, actor, false);
	}
	public boolean isMember(BaseRecord user, BaseRecord object, BaseRecord actor, boolean browseHierarchy) {
		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(user, aet, user, object);
		boolean outBool = false;
		PolicyResponseType prr2 = IOSystem.getActiveContext().getAuthorizationUtil().canRead(user, user, actor);
		if(prr2.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prr2, "Not authorized to read actor");
			// logger.error(prr2.toFullString());
		}
		else {
			PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canRead(user, user, object);
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				outBool = IOSystem.getActiveContext().getMemberUtil().isMember(actor, object, null, browseHierarchy);
				if(outBool) {
					AuditUtil.closeAudit(audit, prr, null);	
				}
			}
			else {
				AuditUtil.closeAudit(audit, prr2, "Not authorized to read object");	
			}
		}
		
		return outBool;
	}
	
	public boolean member(BaseRecord user, BaseRecord object, BaseRecord actor, BaseRecord effect, boolean enable) {
		ActionEnumType aet = ActionEnumType.MODIFY;
		BaseRecord audit = AuditUtil.startAudit(user, aet, user, object);
		boolean outBool = false;
		PolicyResponseType prr2 = IOSystem.getActiveContext().getAuthorizationUtil().canRead(user, user, actor);
		if(prr2.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prr2, "Not authorized to read actor");
			// logger.error(prr2.toFullString());
		}
		else {
			PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, object);
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				outBool = IOSystem.getActiveContext().getMemberUtil().member(user, object, actor, effect, enable);
				if(outBool) {
					AuditUtil.closeAudit(audit, prr, null);	
				}
				else {
					AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "No change made");
				}
			}
			else {
				AuditUtil.closeAudit(audit, prr2, "Not authorized to update object");	
			}
		}
		
		return outBool;
	}

	public boolean isLocked(BaseRecord contextUser, BaseRecord object) {
		List<String> fields = FieldLockUtil.getFieldLocks(contextUser, object);
		List<FieldType> locked = object.getFields().stream().filter(f -> fields.contains(f.getName())).collect(Collectors.toList());
		return (locked.size() > 0);
	}
	public int create(BaseRecord contextUser, BaseRecord[] objects) {
		return update(contextUser, objects);
	}
	public int update(BaseRecord contextUser, BaseRecord[] objects) {
		BaseRecord outObj = null;
		if(objects.length == 0) {
			return 0;
		}
		BaseRecord firstObject = objects[0];
		ActionEnumType aet = ActionEnumType.ADD;
		if(RecordUtil.isIdentityRecord(firstObject)) {
			aet = ActionEnumType.MODIFY;
		}


		List<BaseRecord> records = new ArrayList<>();
		
		for(BaseRecord obj : objects) {
			BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, obj);

			PolicyResponseType prr = null;
			if(aet == ActionEnumType.MODIFY) {
				if(isLocked(contextUser, obj)) {
					AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "One or more fields are locked");
					continue;
				}
				prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(contextUser, contextUser, obj);
			}
			else {
				prr = IOSystem.getActiveContext().getAuthorizationUtil().canCreate(contextUser, contextUser, obj);
			}
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				records.add(obj.copyRecord());
				AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, null);
			}
			else {
				AuditUtil.closeAudit(audit, prr, null);
			}
		}
		
		int writeCount = context.getRecordUtil().updateRecords(records.toArray(new BaseRecord[0]));
		
		return writeCount;
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
			if(isLocked(contextUser, object)) {
				AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "One or more fields are locked");
				return outObj;
			}

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
		if(contextUser == null || object == null) {
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "One or more required parameters was null");
			return outBool;
			
		}
		if(isLocked(contextUser, object)) {
			AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "One or more fields are locked");
			return outBool;
		}

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
			// failAudit(aet, ResponseEnumType.INVALID, contextUser, contextUser, null, "No results");
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "No results");
		}

		return rec;
	}

	public BaseRecord find(BaseRecord contextUser, Query query) {
		BaseRecord rec = null;
		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		PolicyResponseType prr = null;
		
		
		if(query.getRequest().size() == 0) {
			query.setRequest(RecordUtil.getCommonFields(query.getType()));
		}
		// logger.info(query.key());
		
		if((prr = authorizeQuery(contextUser, query)) == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			logger.error("One or more query fields were not or could not be authorized");
			// logger.error(query.toFullString());
			if(prr != null) {
				logger.error(prr.toFullString());
			}
			
			AuditUtil.closeAudit(audit, prr, "One or more query fields were not or could not be authorized: " + query.key());
			return rec;
		}
		QueryResult qr = search(contextUser, query);
		AuditUtil.query(audit, query.key());
		if(qr != null && qr.getCount() > 0) {
			BaseRecord chkRec = qr.getResults()[0];
			//logger.info(qr.toFullString());
			AuditUtil.auditResource(audit, rec);
			/// Evaluating authorization on the object will 
			prr = IOSystem.getActiveContext().getAuthorizationUtil().canRead(contextUser, contextUser, chkRec);
			//logger.info(chkRec.toFullString());
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				rec = chkRec;
			}
			AuditUtil.closeAudit(audit, prr, null);
		}
		else {
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "No results");
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
		Query groupq = getIdQuery(contextUser, ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, groupObjectId);
		/// Read the group without checking access because the user may have access the object, while not having access to the parent group
		///
		//BaseRecord group = find(contextUser, groupq);
		BaseRecord group = null;
		QueryResult qr = search(contextUser, groupq);
		if(qr != null && qr.getResults().length > 0) {
			group = qr.getResults()[0];
		}
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
		/// logger.info(query.toFullString());
		return find(contextUser, query);
	}
	


	
	private QueryResult search(BaseRecord contextUser, Query query) {
		QueryResult qr = null;
		try {
			query.setContextUser(contextUser);
			if(!query.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
				query.field(FieldNames.FIELD_ORGANIZATION_ID, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			}
			qr = IOSystem.getActiveContext().getSearch().find(query);
		} catch (ReaderException | IndexException e) {
			logger.error(e);
		}
		return qr;
	}
	
	
	/// COUNT and LIST - 
	///		The AccountManager 5 security model was to restrict lists to the parent or group, or (in 6) use a very elaborate dynamic sql statement to evaluate the authorization rules
	///		A special set of functions are written for PostgreSQL to handle this.  However, while testing directly against a file-based or H2-based persistence layer, I want to avoid the tight coupling before
	///		the general approach is nailed down.  Because I wanted AM 7 to be primarily PBAC at the core, authorization decisions should follow the policy
	///		At the moment, the count and list methods inspect the query and dynamically assemble a policy based on what is being requested, so that at least one policy check becomes required to perform the action
	///		When trying to view models and including the foreign column reference in a single result, the policy will expand to include that reference, so the context user must have entitlement to that object
	///		The model can explicitly define roles by system permission (create, read, update, delete, execute) at the model and field level.  For example, to allow a user in the AccountUserReadersRole to read another user, the user model defines that access at the model level, as well as on the two foreign key properties so that the user is able to read them.
	///
	public int count(BaseRecord contextUser, Query query) {

		ActionEnumType aet = ActionEnumType.READ;
		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		//query.field(FieldNames.FIELD_ORGANIZATION_ID, null)
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
			audit.set(FieldNames.FIELD_ORGANIZATION_PATH, contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH));
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
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

		for(PolicyResponseType pr : prrs) {
			prr = pr;
			if(pr.getType() == PolicyResponseEnumType.PERMIT) {
				break;
			}
		}
		return prr;
	}
	
	
}
