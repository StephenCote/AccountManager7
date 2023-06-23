package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
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
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;

public class QueryUtil {
	public static final Logger logger = LogManager.getLogger(QueryUtil.class);
	
	public static String[] getCommonFields(String model) {
		// List<String> flds = new ArrayList<>();
		Set<String> flds = new HashSet<>();
		ModelSchema ms = RecordFactory.getSchema(model);
		flds.addAll(ms.getQuery());
		for(String s : ms.getImplements()) {
			if(!s.equals(model)) {
				ModelSchema mis = RecordFactory.getSchema(s);
				flds.addAll(mis.getQuery());
			}
		}
		return flds.toArray(new String[0]);
	}
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
	public static String hash(BaseRecord query) {
		return hash(key(query));
	}
	public static String hash(String key) {
		return CryptoUtil.getDigestAsString(key);
	}
	public static String key(BaseRecord query) {
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
		
		return (actorId + "-" + type + "-" + sortBy + "-" + order.toLowerCase().substring(0, 3) + "-" + startIndex + "-" + count + " [" + jF + "] [" + reqF + "] " + fieldKey(query));
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
					buff.append(" " + quote + value + quote);
				}
				
			}
			buff.append(")");
		}


		return buff.toString();
	}
	
	public static Query createQuery(String model) {
		ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, model);
		// logger.info(JSONUtil.exportObject(plist, RecordSerializerConfig.getUnfilteredModule()));
		// ParameterUtil.newParameter(plist, FieldNames.FIELD_GROUP_ID, testData.get(FieldNames.FIELD_GROUP_ID));
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
	
	
	/// This constructs a subQuery added to the 'joins' list
	public static void filterParticipation(Query query, BaseRecord object, String actorType, BaseRecord effect) {
		Query part = createParticipationQuery(null, object, null, effect);
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
	
	public static Query createParticipationQuery(BaseRecord contextUser, BaseRecord object, BaseRecord actor, BaseRecord effect) {
		Query q = new Query(contextUser, ModelNames.MODEL_PARTICIPATION);
		if(object != null) {
			q.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.EQUALS, object.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_PARTICIPATION_MODEL, ComparatorEnumType.EQUALS, object.getModel());
		}
		if(actor != null) {
			q.field(FieldNames.FIELD_PARTICIPANT_ID, ComparatorEnumType.EQUALS, actor.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_PARTICIPANT_MODEL, ComparatorEnumType.EQUALS, actor.getModel());
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
	
	public static <T> Query createQuery(String modelName, String fieldName, T val) {
		return createQuery(modelName, fieldName, val, 0L);
	}
	public static <T> Query createQuery(String modelName, String fieldName, T val, long organizationId) {
		Query query = null;
		// logger.info("Query identity: " + modelName + "." + fieldName + " :: " + val);
		try {
			query = new Query(IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_QUERY, null, null, ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, modelName)));
			query.field(fieldName, ComparatorEnumType.EQUALS, val);
			if(organizationId > 0L) {
				query.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			}
		} catch (NullPointerException | FactoryException e) {
			logger.error(e);
			
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

		}
		else {
			//logger.error("TODO: List for system");
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
