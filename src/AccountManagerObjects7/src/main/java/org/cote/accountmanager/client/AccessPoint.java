package org.cote.accountmanager.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
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
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

public class AccessPoint {
	public static final Logger logger = LogManager.getLogger(AccessPoint.class);
	private boolean permitBulkContainerApproval = false;
	private int maximumBatchSize = 2000;
	
	/// If no authorization policies are applied, then is the decision to reject or accept.
	/// This is obviously an issue to resolve.
	private boolean itsAllGood = true;
	
	private IOContext context = null;
	
	public AccessPoint(IOContext context) {
		this.context = context; 
	}
	
	
	
	public boolean isPermitBulkContainerApproval() {
		return permitBulkContainerApproval;
	}



	public void setPermitBulkContainerApproval(boolean permitBulkContainerApproval) {
		this.permitBulkContainerApproval = permitBulkContainerApproval;
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
		q.planCommon(false);
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
		return member(user, object, null, actor, effect, enable);
	}
	public boolean member(BaseRecord user, BaseRecord object, String fieldName, BaseRecord actor, BaseRecord effect, boolean enable) {
		ActionEnumType aet = ActionEnumType.MODIFY;
		BaseRecord audit = AuditUtil.startAudit(user, aet, user, object);
		boolean outBool = false;
		PolicyResponseType prr2 = IOSystem.getActiveContext().getAuthorizationUtil().canRead(user, user, actor);
		if(prr2.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prr2, "Not authorized to read actor");
		}
		else {
			PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, object);
			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				outBool = IOSystem.getActiveContext().getMemberUtil().member(user, object, fieldName, actor, effect, enable);
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
	public int create(BaseRecord contextUser, BaseRecord[] objects, boolean permitBulkApproval) {
		return update(contextUser, objects, permitBulkApproval);
	}
	
	public int update(BaseRecord contextUser, BaseRecord[] objects) {
		return update(contextUser, objects, permitBulkContainerApproval);
	}
	
	public int update(BaseRecord contextUser, BaseRecord[] objects, boolean permitBulkApproval) {
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

		/// permitBulkContainerApproval will stash the first approval for a model type at the container level, and skip checking individual authorizations for remaining items
		/// this will improve performance on large data loads, but at the risk of unintentionally allowing or denying bulk operations that might otherwise evaluate differently
		///
		Map<String, PolicyResponseType> containerSet = new HashMap<>();
		int writeCount = 0;
		for(BaseRecord obj : objects) {
			BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, obj);

			PolicyResponseType prr = null;
			
			String setKey = null;
			
			boolean hideClose = false;
			if(permitBulkApproval) {
				
				long setId = 0L;
				boolean useParent = false;
				if(obj.inherits(ModelNames.MODEL_PARENT) && !FieldUtil.isNullOrEmpty(obj.getSchema(), obj.getField(FieldNames.FIELD_PARENT_ID))) {
					setId = obj.get(FieldNames.FIELD_PARENT_ID);
					setKey = obj.getSchema() + "-parent-" + aet.toString() + "-" + Long.toString(setId);
					useParent = true;
					
				}
				else if(obj.inherits(ModelNames.MODEL_DIRECTORY) && !FieldUtil.isNullOrEmpty(obj.getSchema(), obj.getField(FieldNames.FIELD_GROUP_ID))) {
					setId = obj.get(FieldNames.FIELD_GROUP_ID);
					setKey = obj.getSchema() + "-group-" + aet.toString() + "-" + Long.toString(setId);
					
				}
				if(setKey != null) {
					if(!containerSet.containsKey(setKey)) {
						if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
							logger.warn("Find - " + (useParent ? obj.getSchema() : ModelNames.MODEL_GROUP) + " " + setId);
						}
						BaseRecord cont = findById(contextUser, (useParent ? obj.getSchema() : ModelNames.MODEL_GROUP), setId);
						if(cont != null) {
							prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(contextUser, contextUser, cont);
							containerSet.put(setKey, prr);
							if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
								logger.warn("Container-level bulk approval will be used for " + setKey);
							}
						}
						else {
							logger.warn("Failed to read container: falling out of bulk approval");
							permitBulkApproval = false;
						}
					}
					else {
						prr = containerSet.get(setKey);
						hideClose = true;
					}
				}
				else {
					logger.warn("Model " + obj.getSchema() + " not able to use bulk approvals");
					// permitBulkApproval = false;
				}
			}

			if(aet == ActionEnumType.MODIFY) {
				if(isLocked(contextUser, obj)) {
					AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "One or more fields are locked");
					continue;
				}
				if(prr == null) {
					prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(contextUser, contextUser, obj);
				}
			}
			else if(prr == null){
				prr = IOSystem.getActiveContext().getAuthorizationUtil().canCreate(contextUser, contextUser, obj);
			}

			if(prr.getType() == PolicyResponseEnumType.PERMIT) {
				// records.add(obj.copyRecord());
				records.add(obj);
				if(!hideClose) {
					AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, null);
				}
			}
			else {
				if(!hideClose) {
					AuditUtil.closeAudit(audit, prr, null);
				}
			}
			if(records.size() >= maximumBatchSize) {
				writeCount += context.getRecordUtil().updateRecords(records.toArray(new BaseRecord[0]));
				// logger.info("Send batch: " + records.size() + "/" + writeCount +" of " + objects.length);
				records.clear();
			}
		}
		if(records.size() > 0) {
			writeCount += context.getRecordUtil().updateRecords(records.toArray(new BaseRecord[0]));
			// logger.info("Send batch: " + records.size() + "/" + writeCount +" of " + objects.length);
			records.clear();
		}

		return writeCount;
	}
	
	
	public BaseRecord update(BaseRecord contextUser, BaseRecord object) {
		BaseRecord outObj = null;
		BaseRecord cobj = object;
		
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
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Failed to " + (aet.toString().toLowerCase()) + " record");
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
	
	public boolean delete(BaseRecord contextUser, Query query) {
		boolean outBool = false;

		ActionEnumType aet = ActionEnumType.DELETE;

		BaseRecord audit = AuditUtil.startAudit(contextUser, aet, contextUser, null);
		PolicyResponseType prr = null;

		if(contextUser == null || query == null) {
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "One or more required parameters was null");
			return outBool;
		}

		if((prr = authorizeQuery(contextUser, query)) == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			String emsg = "One or more query fields were not or could not be authorized: " + query.key();
			logger.error(emsg);
			if(prr != null) {
				logger.error(prr.toFullString());
			}
			
			AuditUtil.closeAudit(audit, prr, emsg);
			return false;
		}
		
		logger.warn("TODO: Check locks based on query");
		
		/// Need to add in the policy check for a variable delete request
		///
		AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Not implemented");
		return false;
		/*
		if(isLocked(contextUser, object)) {
			AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "One or more fields are locked");
			return outBool;
		}
		*/
		/*
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
		*/
		
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
		AuditUtil.query(audit, model + " " + path + " " + type + " in " + contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
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
		
		if((prr = authorizeQuery(contextUser, query)) == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			String emsg = "One or more query fields were not or could not be authorized: " + query.key();
			logger.error(emsg);
			// logger.error(query.toFullString());
			if(prr != null) {
				logger.error(prr.toFullString());
			}
			
			AuditUtil.closeAudit(audit, prr, emsg);
			return rec;
		}
		QueryResult qr = search(contextUser, query);
		AuditUtil.query(audit, query.key());
		if(qr != null && qr.getCount() > 0) {
			BaseRecord chkRec = qr.getResults()[0];
			AuditUtil.auditResource(audit, rec);
			/// Evaluating authorization on the object will
			if(IOSystem.getActiveContext().getPolicyUtil().isTrace()) {
				logger.warn(chkRec.toFullString());
			}
			prr = IOSystem.getActiveContext().getAuthorizationUtil().canRead(contextUser, contextUser, chkRec);
			
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
		} catch (ReaderException e) {
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
	
	private PolicyResponseType getNewResponseType(PolicyResponseEnumType type, String message) {
		PolicyResponseType prr = new PolicyResponseType();
		prr.setType(type);
		return prr;
	}
	
	private PolicyResponseType authorizeQuery(BaseRecord contextUser, Query query) {
		
		if(query == null) {
			logger.error("Null query or user");
			return null;
		}
		PolicyResponseType[] prrs = context.getPolicyUtil().evaluateQueryToReadPolicyResponses(contextUser, query);
		PolicyResponseType prr = null;
		if (prrs.length == 0) {
			logger.warn("No policy responses");
			if(itsAllGood) {
				return getNewResponseType(PolicyResponseEnumType.PERMIT, "No policy responses");
            }
		}
		for(PolicyResponseType pr : prrs) {
			prr = pr;
			if(pr.getType() == PolicyResponseEnumType.PERMIT) {
				break;
			}
			logger.error(pr.toFullString());
		}
		return prr;
	}
	public boolean vectorize(BaseRecord user, String model, String objectId, ChunkEnumType chunkType, int chunkSize) {

		logger.info("Request to vectorize " + model + " " + objectId);
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if(vu == null) {
			logger.error("Vector utility is not initialized.");
			return false;
		}
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);

		/// TODO: There is an authorization gap where older style reference models like contactInformation are not correctly using the model level access binding
		/// This results in an access failure in the dynamic policy for nested queries
		///
		q.planMost(false, Arrays.asList(new String[] {FieldNames.FIELD_CONTACT_INFORMATION}));
		BaseRecord rec = find(user, q);
		
		//BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, model, objectId);
		ActionEnumType aet = ActionEnumType.VECTORIZE;
		BaseRecord audit = AuditUtil.startAudit(user, aet, user, rec);
		AuditUtil.query(audit, q.key());
		if(rec == null) {
			audit.setValue(FieldNames.FIELD_RESOURCE_TYPE, model);
			AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Object " + objectId + " could not be read");
			return false;
		}

		PolicyResponseType prt = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, rec);
		if(prt.getType() != PolicyResponseEnumType.PERMIT) {
			AuditUtil.closeAudit(audit, prt, "Not authorized to modify object");
			return false;
		}
		
		boolean outBool = false;
		try {
			logger.info("Creating vector store for " + objectId);
			List<BaseRecord> chunks = vu.createVectorStore(rec, chunkType, chunkSize);
			if(chunks.size() > 0) {
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
				AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, "Created vector store");
				outBool = true;
			}
			else {
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Failed to create vector store");
			}
		} catch (FieldException | WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
	
		return outBool;
	}
	
	
}
