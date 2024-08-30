package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.EffectEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;

public class QueryUtil {
	public static final Logger logger = LogManager.getLogger(QueryUtil.class);
	
	/// Restrict the query key to the following fields to prevent gratuitous message size and/or leaking sensitive or encrypted values through the audit log
	///
	private static String[] keyFields = new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OBJECT_ID};
	
	public static String getComparatorToken(ComparatorEnumType comp) {
		String token = comp.toString();
		switch(comp) {
			case LESS_THAN:
				token = "<";
				break;
			case LESS_THAN_OR_EQUALS:
				token = "<=";
				break;
			case EQUALS:
				token = "=";
				break;
			case GREATER_THAN:
				token = ">";
				break;
			case GREATER_THAN_OR_EQUALS:
				token = ">=";
				break;
			case GROUP_AND:
				token = "&&";
				break;
			case GROUP_OR:
				token = "||";
				break;
			case ANY:
			case BETWEEN:
			case IN:
			case IS_NULL:
			case IS_NULL_EQUALS:
			case IS_NULL_NOT_EQUALS:
			case LIKE:
			case NOT_ANY:
			case NOT_EQUALS:
			case NOT_IN:
			case NOT_NULL:
			case UNKNOWN:
			default:
				break;
		
		}
		return token;
	}

	public synchronized static String key(BaseRecord query) {
		String type = query.get(FieldNames.FIELD_TYPE);
		String order = query.get(FieldNames.FIELD_ORDER);

		String sortBy = query.get(FieldNames.FIELD_SORT_FIELD);
		if(sortBy == null) {
			sortBy = FieldNames.FIELD_ID;
		}
		String actorId = query.get(FieldNames.FIELD_CONTEXT_USER_OBJECT_ID);

		if(actorId == null) {
			actorId = "000";
		}
		List<String> fields = query.get(FieldNames.FIELD_REQUEST);
		String reqF = fields.stream().collect(Collectors.joining(", "));
		if(reqF.length() == 0) {
			reqF = "*";
		}
		int count = query.get(FieldNames.FIELD_RECORD_COUNT);
		long startIndex = query.get(FieldNames.FIELD_START_RECORD);
		
		List<BaseRecord> joins = query.get(FieldNames.FIELD_JOINS);
		List<String> jkey = new ArrayList<>();
		for(BaseRecord j : joins) {
			Query jq = new Query(j);
			jkey.add(jq.key());
		}
		String jF = jkey.stream().collect(Collectors.joining(" || "));
		if(jF.length() == 0) {
			jF = "*";
		}
		
		return (actorId + "-" + type + "-" + "-" + sortBy + "-" + order.toLowerCase().substring(0, 3) + "-" + startIndex + "-" + count + " [" + jF + "] [" + reqF + "] " + fieldKey(query));
	}

	private static String fieldKey(BaseRecord field) {
		StringBuilder buff = new StringBuilder();
		ComparatorEnumType pcomp = ComparatorEnumType.valueOf(field.get(FieldNames.FIELD_COMPARATOR));
		List<BaseRecord> fields = field.get(FieldNames.FIELD_FIELDS);
		if(fields.size() > 0) {
			buff.append("(");
			for(int i = 0; i < fields.size(); i++) {
				if(i > 0 && (pcomp.equals(ComparatorEnumType.GROUP_AND) || pcomp.equals(ComparatorEnumType.GROUP_OR))){
					//buff.append(" " + pcomp.substring(pcomp.indexOf("_") + 1) + " ");
					buff.append(" " + getComparatorToken(pcomp) + " ");
				}
				BaseRecord f = fields.get(i);
				ComparatorEnumType comp = ComparatorEnumType.valueOf(f.get(FieldNames.FIELD_COMPARATOR));
				String name = f.get(FieldNames.FIELD_NAME);
				Object value = null;
				if(f.hasField(FieldNames.FIELD_VALUE)) {
					value = f.get(FieldNames.FIELD_VALUE);
				}
				if(name != null) {
					buff.append(name);
				}
				if(comp.equals(ComparatorEnumType.GROUP_AND) || comp.equals(ComparatorEnumType.GROUP_OR)){
					//buff.append(" " + comp.substring(comp.indexOf("_") + 1) + " ");
				}
				else {
					buff.append(" " + getComparatorToken(comp));
				}
				buff.append(fieldKey(f));
				if(name != null) {
					String quote = "";
					if(f.getField(FieldNames.FIELD_VALUE).getValueType().equals(FieldEnumType.STRING)) {
						quote = "\"";
					}
					else if(f.getField(FieldNames.FIELD_VALUE).getValueType().equals(FieldEnumType.MODEL)) {
						BaseRecord mod = (BaseRecord)value;
						value = mod.copyRecord(RecordUtil.getPossibleFields(mod.getModel(), keyFields));
					}
					buff.append(" " + quote + value + quote);
				}
				
			}
			buff.append(")");
		}

		return buff.toString();
	}
	
	public static Query createQuery(String model) {
		ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, model);
		Query query = null;
		try {
			query = new Query(IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_QUERY, null, null, plist));
			
		} catch (FactoryException e) {
			logger.error(e);
		}
		return query;
	}
	
	public static Query createQuery(String model, String statement) {
		return createQuery(IOSystem.getActiveContext().getFactory().template(model, statement));
	}
	public static Query createQuery(BaseRecord rec) {
		return createQuery(new BaseRecord[] {rec});
	}
	public static Query createQuery(BaseRecord... records) {
		if(records.length == 0){
			logger.error("Invalid arguments.  Expected at least one record");
			return null;
		}
		Query query = null;
		try {
			query = new Query(IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_QUERY));
			query.set(FieldNames.FIELD_TYPE, records[0].getModel());
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
			createQueryGroup(query, query, records);
			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return query;
		
	}
	
	public static void createQueryGroup(Query query, BaseRecord parent, BaseRecord... records) throws FieldException, ModelNotFoundException, ValueException {
		boolean plex = records.length > 1;
		parent.set(FieldNames.FIELD_COMPARATOR, (plex ? ComparatorEnumType.GROUP_OR : ComparatorEnumType.GROUP_AND));

		for(BaseRecord r: records) {
			BaseRecord useParent = parent;
			if(plex) {
				useParent = query.field(null, ComparatorEnumType.GROUP_AND, null, parent);
				
			}
			if(r != null) {
				importQueryFields(ComparatorEnumType.EQUALS, query, useParent, r);
			}
		}
	}
	
	public static void filterParticipant(Query query, String model, String fieldName, BaseRecord actor, BaseRecord effect) {
		filterParticipant(query, model, new String[] {fieldName}, actor, effect);
	}
	public static void filterParticipant(Query query, String model, String[] fieldNames, BaseRecord actor, BaseRecord effect) {
		Query part = new Query(ModelNames.MODEL_PARTICIPATION);

		QueryField or = part.field(null, ComparatorEnumType.GROUP_OR, null);
		for(String f : fieldNames) {
			String actorType = ParticipationFactory.getParticipantModel(model, f, actor.getModel());
			part.field(FieldNames.FIELD_PARTICIPANT_MODEL, ComparatorEnumType.EQUALS, actorType, or);
		}
		/*
 			String actorType = ParticipationFactory.getParticipantModel(model, fieldName, actor.getModel());
			part.field(FieldNames.FIELD_PARTICIPANT_MODEL, actorType);
		 */
		part.field(FieldNames.FIELD_PARTICIPATION_MODEL, ComparatorEnumType.EQUALS, model);
		
		part.field(FieldNames.FIELD_PARTICIPANT_ID, ComparatorEnumType.EQUALS, actor.get(FieldNames.FIELD_ID));
		try {
			part.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPATION_ID);
			query.field(FieldNames.FIELD_ORGANIZATION_ID, actor.get(FieldNames.FIELD_ORGANIZATION_ID));
			query.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		List<BaseRecord> joins = query.get(FieldNames.FIELD_JOINS);
		joins.add(part);
	}
	
	public static void filterParticipation(Query query, BaseRecord object, String fieldName, String actorType, BaseRecord effect) {
		Query part = createParticipationQuery(null, object, fieldName, null, effect);
		actorType = ParticipationFactory.getParticipantModel(object.getModel(), fieldName, actorType);

		part.field(FieldNames.FIELD_PARTICIPANT_MODEL, actorType);
		try {
			part.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPANT_ID);
			query.field(FieldNames.FIELD_ORGANIZATION_ID, object.get(FieldNames.FIELD_ORGANIZATION_ID));
			query.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		List<BaseRecord> joins = query.get(FieldNames.FIELD_JOINS);
		joins.add(part);
	}
	
	public static Query createParticipationQuery(BaseRecord contextUser, BaseRecord object, String fieldName, BaseRecord actor, BaseRecord effect) {
		Query q = new Query(contextUser, ModelNames.MODEL_PARTICIPATION);
		String participantModel = ParticipationFactory.getParticipantModel(object, fieldName, actor);

		if(object != null) {
			q.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.EQUALS, object.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_PARTICIPATION_MODEL, ComparatorEnumType.EQUALS, object.getModel());
		}
		if(actor != null) {
			q.field(FieldNames.FIELD_PARTICIPANT_ID, ComparatorEnumType.EQUALS, actor.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_PARTICIPANT_MODEL, ComparatorEnumType.EQUALS, participantModel);
		}
		if(effect != null) {
			q.field(FieldNames.FIELD_PERMISSION_ID, ComparatorEnumType.EQUALS, effect.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_EFFECT_TYPE, ComparatorEnumType.EQUALS, EffectEnumType.GRANT_PERMISSION.toString());
		}

		return q;
	}

	public static void importQueryFields(ComparatorEnumType comp, Query query, BaseRecord parent, BaseRecord rec) throws FieldException, ModelNotFoundException {
		for(FieldType f: rec.getFields()) {
			query.field(f.getName(), comp, rec.get(f.getName()), parent);
		}
	}
	
	public static Query getGroupQuery(String model, String groupType, long groupId, long organizationId) {
		String fieldName = FieldNames.FIELD_GROUP_ID;
		if(model.equals(ModelNames.MODEL_GROUP) || model.equals(ModelNames.MODEL_PERMISSION) || model.equals(ModelNames.MODEL_ROLE)) {
			fieldName = FieldNames.FIELD_PARENT_ID;
		}
		Query lq = QueryUtil.createQuery(model, fieldName, groupId);
		if(organizationId > 0L) {
			lq.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		}
		if(groupType != null && groupType.length() > 0 && !groupType.equalsIgnoreCase("unknown")) {
			lq.field(FieldNames.FIELD_TYPE, groupType);
		}
		
		return lq;
	}
	
	public static QueryPlan createQueryPlan(String modelName, String fieldName, String[] fields) {
		return new QueryPlan(modelName, fieldName, fields);
	}
	
	public static <T> Query createQuery(String modelName, String fieldName, T val) {
		return createQuery(modelName, fieldName, val, 0L);
	}
	public static <T> Query createQuery(String modelName, String fieldName, T val, long organizationId) {
		Query query = null;
		try {
			query = new Query(IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_QUERY, null, null, ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, modelName)));
			if(fieldName != null) {
				query.field(fieldName, ComparatorEnumType.EQUALS, val);
			}
			if(organizationId > 0L) {
				query.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			}
			
			if(IOSystem.isOpen() && !ModelNames.MODEL_MODEL_SCHEMA.equals(modelName)) {
				query.requestCommonFields();
			}
			
			// NullPointerException | Factory
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			
		}
		return query;
	}
	
	public static <T> T findFieldValue(BaseRecord query, String fieldName, T defVal) {
		T outVal = defVal;
		
		List<BaseRecord> fields = query.get(FieldNames.FIELD_FIELDS);
		for(BaseRecord fld : fields) {
			
			ComparatorEnumType cet = ComparatorEnumType.valueOf(fld.get(FieldNames.FIELD_COMPARATOR));
			if(cet != ComparatorEnumType.ANY && cet != ComparatorEnumType.IN && fieldName.equals(fld.get(FieldNames.FIELD_NAME))){
				outVal = fld.get(FieldNames.FIELD_VALUE);
				break;
			}
			outVal = findFieldValue(fld, fieldName, outVal);
		}
		return outVal;
	}
	public static BaseRecord findField(BaseRecord query, String fieldName) {
		BaseRecord qf = null;
		List<BaseRecord> queries = query.get(FieldNames.FIELD_FIELDS);
		Optional<BaseRecord> oq = queries.stream().filter(q -> fieldName.equals(q.get(FieldNames.FIELD_NAME))).findFirst();
		if(oq.isPresent()) {
			qf = oq.get();
		}
		return qf;
	}
	public static <T> List<T> findFieldValues(BaseRecord query, String fieldName, T defVal) {
		return findFieldValues(query, fieldName, defVal, new ArrayList<T>());
	}
	public static <T> List<T> findFieldValues(BaseRecord query, String fieldName, T defVal, List<T> lst) {
		List<BaseRecord> fields = query.get(FieldNames.FIELD_FIELDS);
		for(BaseRecord fld : fields) {
			
			ComparatorEnumType cet = ComparatorEnumType.valueOf(fld.get(FieldNames.FIELD_COMPARATOR));
			if(cet != ComparatorEnumType.ANY && cet != ComparatorEnumType.IN && fieldName.equals(fld.get(FieldNames.FIELD_NAME))){
				lst.add(fld.get(FieldNames.FIELD_VALUE));
				break;
			}
			lst = findFieldValues(fld, fieldName, defVal, lst);
		}
		return lst;
	}
	
	public static Query buildQuery(BaseRecord user, String type, String containerId, String name, long startIndex, int recordCount) {
		String clusterField = null;
		String clusterType = null;
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			logger.error("Invalid model: '" + type + "'");
			return null;
		}
		boolean isGroup = RecordUtil.inherits(ms, ModelNames.MODEL_DIRECTORY);
		boolean isParent = RecordUtil.inherits(ms, ModelNames.MODEL_PARENT);
		boolean isOrg = RecordUtil.inherits(ms, ModelNames.MODEL_ORGANIZATION_EXT);

		Query q = null;
		if(isGroup || isParent) {
			if(isGroup) {
				clusterField = FieldNames.FIELD_GROUP_ID;
				clusterType = ModelNames.MODEL_GROUP;
			}
			else if(isParent) {
				clusterField = FieldNames.FIELD_PARENT_ID;
				clusterType = type;
			}
			BaseRecord pargrp = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, clusterType, containerId);
			if(pargrp != null) {
				q = QueryUtil.createQuery(type, clusterField, pargrp.get(FieldNames.FIELD_ID));
			}
			else {
				logger.error("***** Failed to find " + clusterType + " with objectId " + containerId);
			}

		}
		else {
			q = QueryUtil.createQuery(type);
		}
		if(q != null) {
			q.setRequestRange(startIndex, recordCount);
			if(isOrg) {
				q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			}
			if(name != null) {
				q.field(FieldNames.FIELD_NAME, name);
			}
		}
		return q;
	}


}
