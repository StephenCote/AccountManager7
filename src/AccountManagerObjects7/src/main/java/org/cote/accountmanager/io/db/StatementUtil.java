package org.cote.accountmanager.io.db;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.SqlDataEnumType;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;

import com.pgvector.PGvector;

public class StatementUtil {
	
	public static final Logger logger = LogManager.getLogger(StatementUtil.class);
	
	/// When dereferencing foreign keys, populate all or common fields of the foreign model reference
	/// otherwise, only the reference id will be included
	///
	private static boolean modelMode = false;
	
	
	
	public static boolean isModelMode() {
		return modelMode;
	}
	public static void setModelMode(boolean modelMode) {
		StatementUtil.modelMode = modelMode;
	}
	
	public static String getForeignDeleteTemplate(BaseRecord[] recs) {
		List<String> sqls = new ArrayList<>();
		for(BaseRecord rec : recs) {
			sqls.add(getForeignDeleteTemplate(rec));
		}
		return sqls.stream().collect(Collectors.joining("\n"));
	}
	public static String getForeignDeleteTemplate(BaseRecord rec) {
		if(!rec.hasField(FieldNames.FIELD_ID)) {
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(rec, new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID});
		long id = rec.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			return null;
		}
		return getForeignDeleteTemplate(rec.getModel(), rec.get(FieldNames.FIELD_ID), rec.get(FieldNames.FIELD_ORGANIZATION_ID));
	}
	public static String getForeignDeleteTemplate(String rmodel, long id, long organizationId) {
		List<String> names = ModelNames.getCustomModelNames();
		List<String> sqls = new ArrayList<>();

		for(String model : names) {
			ModelSchema ms = RecordFactory.getSchema(model);
			for(FieldSchema fs : ms.getFields()) {
				if(fs.isForeign() && fs.getBaseModel() != null) {
					/// Direct foreign key - this value would be deleted along with the record
					if(model.equals(rmodel) && fs.getType().toUpperCase().equals(FieldEnumType.MODEL.toString())) {
						// Note: there's the possibility that orphans would be left
					}
					/// Foreign key references from other record models
					///
					else if(rmodel.equals(fs.getBaseModel()) && fs.getType().toUpperCase().equals(FieldEnumType.MODEL.toString())) {
						sqls.add("UPDATE " + IOSystem.getActiveContext().getDbUtil().getTableName(model) + " SET " + fs.getName() + " = 0 WHERE " + fs.getName() + " = " + id + " AND organizationId = " + organizationId + ";");
					}
					/// Foreign key references to this record model
					///
					else if(fs.getBaseModel().equals(rmodel) && fs.getType().toUpperCase().equals(FieldEnumType.LIST.toString())) {
						String partModel = rmodel;
						if(fs.getParticipantModel() != null) {
							partModel = fs.getParticipantModel();
						}
						sqls.add("DELETE FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(ms, ModelNames.MODEL_PARTICIPATION) + " WHERE participantmodel = '" + partModel + "' AND participantid = " + id + " AND organizationId = " + organizationId + ";");
					}
					else {
						// Not applicable
					}
				}
				/// Foreign non-dynamic variable model reference from other record models
				///
				else if(model.equals(rmodel) && fs.isReferenced() && fs.getBaseModel() != null) {
					sqls.add("DELETE FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(fs.getBaseModel()) + " WHERE referenceModel = '" + model + "' AND referenceId = " + id + " AND organizationId = " + organizationId + ";");					
				}
			}
		}
		return sqls.stream().collect(Collectors.joining("\n"));
	}
	
	public static String getDeleteOrphanTemplate(String model) {
		List<String> sqls = new ArrayList<>();
		List<String> names = ModelNames.getCustomModelNames();
		for(String smodel : names) {
			if(smodel.equals(ModelNames.MODEL_MODEL) || smodel.equals(ModelNames.MODEL_PARTICIPATION)) {
				continue;
			}
			if(model == null || smodel.equals(model)) {
				ModelSchema ms = RecordFactory.getSchema(smodel);
				if(ms.isEphemeral() || IOSystem.getActiveContext().getDbUtil().isConstrained(ms) ) {
					continue;
				}
				if(!ms.hasField(FieldNames.FIELD_ID)) {
					continue;
				}
				
				/// TODO: Need to cleanup orphaned participants using a custom participantModel definition
				/*
				List<String> pmods = new ArrayList<>();
				pmods.add(smodel);
				for(FieldSchema f: ms.getFields()) {
					if(f.getParticipantModel() != null) {
						
					}
				}
				*/
				
				String table = IOSystem.getActiveContext().getDbUtil().getTableName(smodel);
				String partTable = IOSystem.getActiveContext().getDbUtil().getTableName(ms, ModelNames.MODEL_PARTICIPATION);
				StringBuilder sql = new StringBuilder();
				sql.append("DELETE FROM " + partTable + " WHERE id IN (SELECT P1.id FROM " + partTable + " P1 ");
				sql.append("LEFT JOIN " + table + " A1 ON A1.id = P1.participationid ");
				sql.append("WHERE P1.participationmodel = '" + smodel + "' AND A1.id IS NULL);");
				sqls.add(sql.toString());
				
				if(RecordUtil.inherits(ms, ModelNames.MODEL_PARENT)) {
					sql = new StringBuilder();
					sql.append("DELETE FROM " + table + " WHERE id IN (SELECT P1.id FROM " + table + " P1 ");
					sql.append("LEFT JOIN " + table + " A1 ON A1.id = P1.parentId ");
					sql.append("WHERE P1.parentId > 0 AND A1.id IS NULL);");
					sqls.add(sql.toString());
				}
				
				if(RecordUtil.inherits(ms, ModelNames.MODEL_DIRECTORY)) {
					sql = new StringBuilder();
					sql.append("DELETE FROM " + table + " WHERE id IN (SELECT P1.id FROM " + table + " P1 ");
					sql.append("LEFT JOIN " + IOSystem.getActiveContext().getDbUtil().getTableName(ModelNames.MODEL_GROUP) + " A1 ON A1.id = P1.groupId ");
					sql.append("WHERE P1.groupId > 0 AND A1.id IS NULL);");
					sqls.add(sql.toString());
				}
				
				if(RecordUtil.inherits(ms, ModelNames.MODEL_USER) || RecordUtil.inherits(ms, ModelNames.MODEL_ACCOUNT) || RecordUtil.inherits(ms, ModelNames.MODEL_PERSON)) {
					String groupPartTable = IOSystem.getActiveContext().getDbUtil().getTableName(RecordFactory.getSchema(ModelNames.MODEL_GROUP), ModelNames.MODEL_PARTICIPATION);
					sql = new StringBuilder();
					sql.append("DELETE FROM " + groupPartTable + " WHERE id IN (SELECT P1.id FROM " + groupPartTable + " P1 ");
					sql.append("LEFT JOIN " + table + " A1 ON A1.id = P1.participantid ");
					sql.append("WHERE P1.participantmodel = '" + smodel + "' AND A1.id IS NULL);");
					sqls.add(sql.toString());

					String rolePartTable = IOSystem.getActiveContext().getDbUtil().getTableName(RecordFactory.getSchema(ModelNames.MODEL_ROLE), ModelNames.MODEL_PARTICIPATION);
					sql = new StringBuilder();
					sql.append("DELETE FROM " + rolePartTable + " WHERE id IN (SELECT P1.id FROM " + rolePartTable + " P1 ");
					sql.append("LEFT JOIN " + table + " A1 ON A1.id = P1.participantid ");
					sql.append("WHERE P1.participantmodel = '" + smodel + "' AND A1.id IS NULL);");
					sqls.add(sql.toString());
					
				}

				for(FieldSchema fs : ms.getFields()) {
					if(fs.isReferenced() && fs.getBaseModel() != null) {
						String refTable = IOSystem.getActiveContext().getDbUtil().getTableName(fs.getBaseModel());
						sql = new StringBuilder();
						sql.append("DELETE FROM " + refTable + " WHERE id IN (SELECT P1.id FROM " + refTable + " P1 ");
						sql.append("LEFT JOIN " + table + " A1 ON A1.id = P1.referenceId ");
						sql.append("WHERE P1.referencemodel = '" + smodel + "' AND A1.id IS NULL);");
						sqls.add(sql.toString());
					}
				}
			}
		}
		return sqls.stream().collect(Collectors.joining("\n"));
	}
	
	public static String getDeleteOrganizationTemplate(long organizationId) {
		List<String> sqls = new ArrayList<>();
		List<String> names = ModelNames.getCustomModelNames();

		for(String smodel : names) {
			if(smodel.equals(ModelNames.MODEL_MODEL)) {
				continue;
			}
			ModelSchema ms = RecordFactory.getSchema(smodel);
			if(ms.isEphemeral() || IOSystem.getActiveContext().getDbUtil().isConstrained(ms) ) {
				continue;
			}
			if(!ms.hasField(FieldNames.FIELD_ID) || !ms.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
				continue;
			}
			String table = IOSystem.getActiveContext().getDbUtil().getTableName(smodel);
			String matchId = "organizationId";
			if(smodel.equals(ModelNames.MODEL_ORGANIZATION)) {
				matchId = FieldNames.FIELD_ID;
			}
			sqls.add("DELETE FROM " + table + " WHERE " + matchId + " = " + organizationId + ";");
			if(!smodel.equals(ModelNames.MODEL_PARTICIPATION) && ms.isDedicatedParticipation()) {
				String partTable = IOSystem.getActiveContext().getDbUtil().getTableName(ms, ModelNames.MODEL_PARTICIPATION);
				sqls.add("DELETE FROM " + partTable + " WHERE " + matchId + " = " + organizationId + ";");
			}
			

		}
		return sqls.stream().collect(Collectors.joining("\n"));
	}
	
	
	public static List<BaseRecord> getForeignParticipations(BaseRecord record) {
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		List<BaseRecord> parts = new ArrayList<>();
		for(FieldType f: record.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs.isEphemeral() || fs.isVirtual() || fs.isReferenced()) {
				continue;
			}
			
			
			if(fs.isForeign()) {
				List<BaseRecord> frecs = new ArrayList<>();
				if(f.getValueType() == FieldEnumType.LIST) {
					frecs = record.get(f.getName());	
				}
				else {
					continue;
				}
				for(BaseRecord rec : frecs) {
					if(!RecordUtil.isIdentityRecord(rec)) {
						logger.error("Record does not have an identity therefore a reference cannot be made");
						continue;
					}
					BaseRecord owner = null;
					if(rec.getModel().equals(ModelNames.MODEL_USER)) {
						owner = rec;
					}
					else {
						IOSystem.getActiveContext().getReader().conditionalPopulate(rec, new String[] {FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_OBJECT_ID});
						long oid = rec.get(FieldNames.FIELD_OWNER_ID);
						if(oid > 0L) {
							owner = IOSystem.getActiveContext().getRecordUtil().getRecordById(null, ModelNames.MODEL_USER, oid);
						}
					}
					if(owner == null) {
						logger.error("Record " + rec.getModel() + " does not have an owner, therefore a reference cannot be made");
						continue;
					}
					parts.add(ParticipationFactory.newParticipation(owner, record, fs.getName(), rec));
				}
			}
		}
		return parts;
	}
	
	public static void updateForeignParticipations(BaseRecord record) {
		List<BaseRecord> parts = getForeignParticipations(record);
		IOSystem.getActiveContext().getRecordUtil().createRecords(parts.toArray(new BaseRecord[0]));
	}
	
	public static DBStatementMeta getInsertTemplate(BaseRecord record) {
		StringBuilder buff = new StringBuilder();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		buff.append("INSERT INTO " + IOSystem.getActiveContext().getDbUtil().getTableNameByRecord(record, record.getModel()) + " (");
		List<String> names = new ArrayList<>();
		List<String> columns = new ArrayList<>();
		List<String> tokens = new ArrayList<>();
		
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		for(FieldType f: record.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(
				fs.isEphemeral()
				|| fs.isVirtual()
				|| fs.isReferenced()
				|| (fs.isForeign() && f.getValueType() == FieldEnumType.LIST)
			){
				// Referenced and foreign lists are handled separately from a direct column update
				//
				continue;
			}
			names.add(f.getName());
			columns.add(util.getColumnName(f.getName()));
			tokens.add("?");
		}
		buff.append(columns.stream().collect(Collectors.joining(", ")));
		buff.append(") VALUES (");
		buff.append(tokens.stream().collect(Collectors.joining(", ")));
		buff.append(")");
		DBStatementMeta meta = new DBStatementMeta();
		meta.setSql(buff.toString());
		meta.setFields(names);
		meta.setColumns(columns);
		return meta;
	}
	
	public static DBStatementMeta getDeleteTemplate(Query query) throws ModelException, FieldException {
		DBStatementMeta meta = new DBStatementMeta(query);
		meta.setStatementType(DBStatementEnumType.DELETE);
		StringBuilder buff = new StringBuilder();
		String model = query.get(FieldNames.FIELD_TYPE);
		ModelSchema schema = RecordFactory.getSchema(model);
		if(schema == null) {
			throw new ModelException("Model '" + model + "' not found");
		}
		ModelSchema mschema = null;
		if(schema.getName().equals(ModelNames.MODEL_PARTICIPATION)) {
			String pmodel = QueryUtil.findFieldValue(query, FieldNames.FIELD_PARTICIPATION_MODEL, null);
			if(pmodel != null) {
				mschema = RecordFactory.getSchema(pmodel);
			}
		}

		if(!query.hasQueryField(FieldNames.FIELD_ORGANIZATION_ID)) {
			throw new FieldException("An organization id is required to delete based on a query");
		}
		
		String alias = getAlias(query);
		String joinClause = getJoinStatement(meta, query);
		String sql = "DELETE FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(mschema, model) + " " + alias + joinClause;
		String queryClause = getQueryString(sql, query, meta);
		
		buff.append(queryClause);
		meta.setSql(buff.toString());

		return meta;
	}
	
	public static DBStatementMeta getDeleteTemplate(BaseRecord record) {
		StringBuilder buff = new StringBuilder();
		buff.append("DELETE FROM " + IOSystem.getActiveContext().getDbUtil().getTableNameByRecord(record, record.getModel()));
		List<String> names = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		FieldType ident = null;
		for(int i = 0; i < record.getFields().size(); i++) {
			FieldType f = record.getFields().get(i);
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs.isIdentity()) {
				if(!FieldUtil.isNullOrEmpty(record.getModel(), f)) {
					ident = f;
					break;
				}
			}
		}

		if(ident == null) {
			logger.error("No identity field provided");
			return null;
		}
		DBStatementMeta meta = new DBStatementMeta();
		buff.append(" WHERE " + ident.getName() + " = ?");
		names.add(ident.getName());
		meta.setSql(buff.toString());
		meta.setFields(names);
		return meta;
	}
	
	public static DBStatementMeta getUpdateTemplate(BaseRecord record) {
		StringBuilder buff = new StringBuilder();
		buff.append("UPDATE " + IOSystem.getActiveContext().getDbUtil().getTableNameByRecord(record, record.getModel()) + " SET ");
		List<String> names = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		int iter = 0;
		FieldType ident = null;
		for(int i = 0; i < record.getFields().size(); i++) {
			FieldType f = record.getFields().get(i);
			FieldSchema fs = ms.getFieldSchema(f.getName());
			/// BUG NOTE: Urn is marked as an identity field.  However, it is constructed from name values.  If the model name or container changes, the Urn will change, and therefore the model can't be updated using the Urn as the identity
			if(fs.isIdentity()) {
				if(ident == null && !FieldUtil.isNullOrEmpty(record.getModel(), f)) {
					ident = f;
				}
				continue;
			}
			else if(
				fs.isEphemeral()
				|| fs.isVirtual()
				|| fs.isReferenced()
				|| fs.isReadOnly()
				|| (fs.isForeign() && f.getValueType() == FieldEnumType.LIST)
			) {
				continue;
			}
			if(iter > 0) {
				buff.append(", ");
			}
			buff.append(util.getColumnName(f.getName()) + " = ?");

			iter++;
			names.add(f.getName());
		}
		DBStatementMeta meta = new DBStatementMeta();
		if(ident == null) {
			logger.error("No identity field provided");
			// logger.error(record.toFullString());
		}
		else {
			buff.append(" WHERE " + ident.getName() + " = ?");
			names.add(ident.getName());
			meta.setSql(buff.toString());
		}
		
		meta.setFields(names);
		return meta;
	}
	
	public static String getInnerReferenceSelectTemplate(Query query, FieldSchema schema) throws FieldException {
		return getInnerSelectTemplate(query, schema, true, false);
	}
	public static String getParticipationSelectTemplate(Query query, FieldSchema schema) throws FieldException {
		return getInnerSelectTemplate(query, schema, true, false);
	}
	
	/// Different instances of the same model may use different properties by specifying a 'participantModel' on the field.
	/// For example: 'person.dependent' and 'person.partner'
	///
	
	private static String getInnerSelectTemplate(Query query, FieldSchema schema, boolean followRef, boolean embedded) throws FieldException {
		if(
			!schema.isReferenced()
			&& !schema.isForeign()
			&& schema.getFieldType() != FieldEnumType.LIST
		) {
			throw new FieldException("Field schema " + schema.getName() + " is not a foreign reference or foreign list");
		}
		
		if(schema.getBaseModel() == null) {
			throw new FieldException("Field schema does not define a base model");
		}
		String model = query.get(FieldNames.FIELD_TYPE);
		if(schema.getBaseModel().equals(ModelNames.MODEL_FLEX)) {
			logger.warn(model + "." + schema.getName());
		}
		ModelSchema mschema = RecordFactory.getSchema(model);
		ModelSchema msschema = RecordFactory.getSchema(schema.getBaseModel());
		List<String> fields = new ArrayList<>();
		List<String> cols = new ArrayList<>();
		
		String subModel = schema.getBaseModel();
		String participantModel = subModel;
		if(schema.getParticipantModel() != null) {
			participantModel = schema.getParticipantModel();
		}
		
		Query subQuery = new Query(subModel);
		try {
			subQuery.set(FieldNames.FIELD_COUNT, query.get(FieldNames.FIELD_COUNT));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FieldException(e);
		} 

		StringBuilder buff = new StringBuilder();
		String alias = getAlias(query);
		String salias = getAlias(subQuery);
		String palias = "P" + salias;
		

		DBUtil util = IOSystem.getActiveContext().getDbUtil();

		List<FieldSchema> msfields = msschema.getFields();
		
		/// Skip restricting to common fields for modelSchema and until the persistence layer is in the 'open' state
		/// This is to stop recursive queries when first starting
		///
		
		if(IOSystem.isOpen()) {
			
			if(!model.equals(ModelNames.MODEL_MODEL_SCHEMA)){
				
				QueryPlan qp = query.getPlan(schema.getName());
				if(qp != null && qp.getPlanFields().size() > 0) {
					subQuery.setRequest(qp.getPlanFields());
				}
				else {
					subQuery.setRequest(Arrays.asList(RecordUtil.getCommonFields(schema.getBaseModel())));
				}
				subQuery.setValue("plan", qp);
				
				List<String> xfields = subQuery.get(FieldNames.FIELD_REQUEST);
				List<String> yfields = new ArrayList<>();
				if(xfields.size() == 0) {
					yfields = RecordUtil.getMostRequestFields(model);
				}
				List<String> rfields = (yfields.size() > 0 ? yfields : xfields);
				msfields = msschema.getFields().stream().filter(f -> {
					FieldSchema fs = msschema.getFieldSchema(f.getName());
					return (
						(rfields.size() == 0 || rfields.contains(f.getName()))
						&&
						(!fs.isForeign() || (!model.equals(fs.getBaseModel()) && !ModelNames.MODEL_SELF.equals(fs.getBaseModel())) && !ModelNames.MODEL_FLEX.equals(fs.getBaseModel()))
						&&
						!fs.getType().toUpperCase().equals(FieldEnumType.BLOB.toString())
						
					);
				}
				).collect(Collectors.toList());

			}

		}
		
		if(msfields.size() == 0) {
			throw new FieldException("No fields identified for " + model + " -> " + subModel);
		}
		for(FieldSchema fs : msfields ) {
			if(fs.isVirtual() || fs.isEphemeral() || (fs.isReferenced() && !followRef) || (fs.isForeign() && !followRef)) {
				continue;
			}
			
			if((followRef && fs.isFollowReference()) || fs.isIdentity() || (schema.isReferenced() && isReferenceField(fs))) {
				if(fs.isReferenced()) {
					logger.warn("**** Handle deep reference for " + model + "." + fs.getName());
					cols.add(getInnerReferenceSelectTemplate(subQuery, fs));
					fields.add(fs.getName());
				}
				else if(fs.isForeign() && fs.getType().toUpperCase().equals(FieldEnumType.LIST.toString())) {
					cols.add(getInnerSelectTemplate(subQuery, fs, schema.isFollowReference(), true));
					fields.add(fs.getName());
				}
				else if(fs.getType().toUpperCase().equals(FieldEnumType.LIST.toString())) {
					if(util.getConnectionType() == ConnectionEnumType.H2) {
						cols.add(salias + "." + util.getColumnName(fs.getName()) + " format json");
					}
					else if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
						cols.add(salias + "." + util.getColumnName(fs.getName()) + "::json");
					}
					else {
						logger.error("Itsa gonna break ...");
					}
					fields.add(fs.getName());
				}
				
				else if(fs.getType().toUpperCase().equals(FieldEnumType.MODEL.toString())){
					cols.add(getInnerSelectTemplate(subQuery, fs, true, true));
					fields.add(fs.getName());
				}
				
				else {
					
					String colName = salias + "." + util.getColumnName(fs.getName());
					if(fs.getType().toUpperCase().equals(FieldEnumType.BLOB.toString())) {
						if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
							colName = "encode(" + colName + ",'base64')";
						}
						else if(util.getConnectionType() == ConnectionEnumType.H2) {
							//logger.warn("TODO: Add bytea encode as needed");
							colName = "UTL_ENCODE.BASE64_ENCODE_STR(" + colName + ")";
						}
					}
					cols.add(colName);
					fields.add(fs.getName());
				}
			}

		}
		String orderClause = getDefaultOrderClause(msschema.getName(), salias);
		if(util.getConnectionType() == ConnectionEnumType.H2) {
			StringBuilder ajoin = new StringBuilder();
			for(int i = 0; i < cols.size(); i++) {
				String s = cols.get(i);
				String f = fields.get(i);
				if(ajoin.length() > 0) {
					ajoin.append(", ");
				}
				ajoin.append("'" + f + "': " + s);
			}
			if(schema.isReferenced()) {
				buff.append("JSON_ARRAY(SELECT JSON_OBJECT(" + ajoin.toString() + ", 'model': '" + subModel + "') FROM " + util.getTableName(mschema, subModel) + " " + salias + " WHERE " + salias + ".referenceModel = '" + model + "' AND " + salias + ".referenceId = " + alias + ".id)" + (!embedded ? " as " + util.getColumnName(schema.getName()) : ""));
			}
			else if(schema.isForeign()) {
				if(schema.getType().equals("list")) {
					buff.append("JSON_ARRAY(SELECT JSON_OBJECT(" + ajoin.toString()  + ", 'model': '" + subModel +  "') FROM " + util.getTableName(mschema, subModel) + " " + salias + " INNER JOIN " + util.getTableName(mschema, ModelNames.MODEL_PARTICIPATION) + " " + palias + " ON " + palias + ".participationModel = '" + model + "' AND " + palias + ".participantId = " + salias + ".id AND " + palias + ".participantModel = '" + participantModel + "' AND " + palias + ".participationId = " + alias + ".id)" + (!embedded ? " as " + util.getColumnName(schema.getName()) : ""));
				}
				else {
					buff.append("(SELECT JSON_OBJECT(" + ajoin.toString() + ", 'model': '" + subModel +  "') FROM " + util.getTableName(mschema, subModel) + " " + salias + " WHERE " + alias + ".id > 0 AND " + alias + "." + util.getColumnName(schema.getName()) + " = " + salias + ".id)" + (!embedded ? " as " + util.getColumnName(schema.getName()) : ""));
				}
			}
		}
		else if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
			StringBuilder ajoin = new StringBuilder();
			for(int i = 0; i < cols.size(); i++) {
				String s = cols.get(i);
				String f = fields.get(i);
				if(ajoin.length() > 0) {
					ajoin.append(", ");
				}
				ajoin.append("'" + f + "', " + s);
			}
			if(schema.isReferenced()) {
				buff.append("(SELECT JSON_AGG(JSON_BUILD_OBJECT(" + ajoin.toString() + ", 'model', '" + subModel + "')" + orderClause + ") FROM " + util.getTableName(mschema, subModel) + " " + salias + " WHERE " + salias + ".referenceModel = '" + model + "' AND " + salias + ".referenceId = " + alias + ".id)" + (!embedded ? " as " + util.getColumnName(schema.getName()) : ""));
			}
			else if(schema.isForeign()) {
				if(schema.getType().equals("list")) {
					buff.append("(SELECT JSON_AGG(JSON_BUILD_OBJECT(" + ajoin.toString() + ", 'model', '" + subModel + "')" + orderClause + ") FROM " + util.getTableName(mschema, subModel) + " " + salias + " INNER JOIN " + util.getTableName(mschema, ModelNames.MODEL_PARTICIPATION) + " " + palias + " ON " + palias + ".participationModel = '" + model + "' AND " + palias + ".participantId = " + salias + ".id AND " + palias + ".participantModel = '" + participantModel + "' AND " + palias + ".participationId = " + alias + ".id)" + (!embedded ? " as " + util.getColumnName(schema.getName()) : ""));
				}
				else {
					buff.append("(SELECT JSON_BUILD_OBJECT(" + ajoin.toString() + ", 'model', '" + subModel + "') FROM " + util.getTableName(mschema, subModel) + " " + salias + " WHERE " + alias + ".id > 0 AND " + alias + "." + util.getColumnName(schema.getName()) + " = " + salias + ".id)" + (!embedded ? " as " + util.getColumnName(schema.getName()) : ""));
				}
				
			}
		}

		if(buff.length() == 0) {
			throw new FieldException("**** Unhandled inner query: " + util.getConnectionType().toString());
		}

		subQuery.setRequest(msfields.stream().map(f -> f.getName()).collect(Collectors.toList()).toArray(new String[0]));
		List<BaseRecord> queries = query.get(FieldNames.FIELD_QUERIES);
		queries.add(subQuery);

		return buff.toString();
	}
	
	public static boolean isReferenceField(FieldSchema field) {
		return (field.getName().equals(FieldNames.FIELD_REFERENCE_ID) || field.getName().equals(FieldNames.FIELD_REFERENCE_TYPE));
	}
	
	public static String getAlias(BaseRecord query) {
		if(!query.inherits(ModelNames.MODEL_QUERY)) {
			return null;
		}
		return getAlias(query, query.get(FieldNames.FIELD_TYPE));
	}
	
	public static String getAlias(BaseRecord query, String model) {
		if(!query.inherits(ModelNames.MODEL_QUERY)) {
			return null;
		}
		String alias = query.get(FieldNames.FIELD_ALIAS);
		if(alias == null) {
			try {
				int count = query.get(FieldNames.FIELD_COUNT);
				count++;
				query.set(FieldNames.FIELD_COUNT, count);
				String aliasP = Arrays.asList(model.split("\\.")).stream().map(f -> f.substring(0,1) + f.substring(f.length()-1)).collect(Collectors.joining(""));
				alias = aliasP + Integer.toString(count);
				query.set(FieldNames.FIELD_ALIAS, alias);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return alias;
	}
	
	public static DBStatementMeta getCountTemplate(Query query) throws ModelException, FieldException {
		DBStatementMeta meta = new DBStatementMeta(query);
		meta.setStatementType(DBStatementEnumType.COUNT);
		StringBuilder buff = new StringBuilder();
		String model = query.get(FieldNames.FIELD_TYPE);
		ModelSchema schema = RecordFactory.getSchema(model);
		if(schema == null) {
			throw new ModelException("Model '" + model + "' not found");
		}
		List<String> oRequestFields = query.get(FieldNames.FIELD_REQUEST);
		List<String> requestFields = new ArrayList<>(oRequestFields);
		List<String> useFields = new ArrayList<>();
		List<String> cols = new ArrayList<>();
		
		if(requestFields.size() == 0) {
			requestFields = schema.getFields().stream()
				.filter(f -> f.isSequence() || f.isIdentity())
				.map(f -> f.getName())
				.collect(Collectors.toList())
			;
		}
		if(requestFields.size() == 0) {
			throw new FieldException("Could not find an identity column to count");
		}
		String alias = getAlias(query);
		cols.add("count(" + alias + "." + requestFields.get(0) + ") as A7Count");


		
		String joinClause = getJoinStatement(meta, query);
		
		String sql = "SELECT " + cols.stream().collect(Collectors.joining(", ")) + " FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(schema, model) + " " + alias + joinClause;
		buff.append(getQueryString(sql, query, meta));
		meta.setSql(buff.toString());
		meta.setColumns(useFields);
		return meta;
	}
	

	public static DBStatementMeta getSelectTemplate(Query query) throws ModelException, FieldException {
		DBStatementMeta meta = new DBStatementMeta(query);
		meta.setStatementType(DBStatementEnumType.SELECT);
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		
		StringBuilder buff = new StringBuilder();
		String model = query.get(FieldNames.FIELD_TYPE);
		ModelSchema schema = RecordFactory.getSchema(model);
		if(schema == null) {
			throw new ModelException("Model '" + model + "' not found");
		}
		ModelSchema mschema = null;
		if(schema.getName().equals(ModelNames.MODEL_PARTICIPATION)) {
			String pmodel = QueryUtil.findFieldValue(query, FieldNames.FIELD_PARTICIPATION_MODEL, null);
			if(pmodel != null) {
				mschema = RecordFactory.getSchema(pmodel);
			}
		}

		List<String> oRequestFields = query.get(FieldNames.FIELD_REQUEST);
		List<String> requestFields = new ArrayList<>(oRequestFields);
		List<String> useFields = new ArrayList<>();
		
		List<String> cols = new ArrayList<>();
		
		if(requestFields.size() == 0) {
			schema.getFields().forEach(f -> {
				requestFields.add(f.getName());
			});
		}

		String alias = getAlias(query);
		for(String s: requestFields) {
			FieldSchema fs = schema.getFieldSchema(s);
			if(fs == null) {
				throw new FieldException("Field '" + s + "' was not found on model " + model);
			}
			
			if(!fs.isEphemeral() && !fs.isVirtual()) {

				useFields.add(fs.getName());
				
				if(fs.isReferenced()) {
					try {
						cols.add(getInnerReferenceSelectTemplate(query, fs));
					} catch (FieldException e) {
						logger.error(e);
					}
				}
				else if(modelMode && fs.isForeign() && fs.getBaseModel() != null && fs.getBaseModel().equals(ModelNames.MODEL_FLEX)) {
					StringBuilder ajoin = new StringBuilder();
					if(util.getConnectionType() == ConnectionEnumType.H2) {
						ajoin.append("'id': " + alias + "." + util.getColumnName(fs.getName()));
						if(fs.getForeignType() != null) {
							FieldSchema fss = schema.getFieldSchema(fs.getForeignType());
							ajoin.append(",'model': " + alias + "." + util.getColumnName(fss.getName()));	
						}
						cols.add("JSON_OBJECT(" + ajoin.toString() + ") as " + util.getColumnName(fs.getName()));

					}
					else if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
						ajoin.append("'id', " + alias + "." + util.getColumnName(fs.getName()));
						if(fs.getForeignType() != null) {
							FieldSchema fss = schema.getFieldSchema(fs.getForeignType());
							ajoin.append(",'model', " + alias + "." + util.getColumnName(fss.getName()));	
						}
						cols.add("JSON_BUILD_OBJECT(" + ajoin.toString() + ") as " + util.getColumnName(fs.getName()));
					}
					
				}
				else if(fs.isForeign()
						&&
						(
							fs.getType().toUpperCase().equals(FieldEnumType.LIST.toString())
							||
							(modelMode && fs.getType().toUpperCase().equals(FieldEnumType.MODEL.toString()))
						)
				) {
					try {
						cols.add(getParticipationSelectTemplate(query, fs));
					} catch (FieldException e) {
						logger.error(e);
					}
				}
				else {
					cols.add(alias + "." + IOSystem.getActiveContext().getDbUtil().getColumnName(fs.getName()));
				}
			}

		}

		if(cols.size() == 0) {
			logger.error(query.toString());
			throw new ModelException("No columns were specified in the query");
		}

		String joinClause = getJoinStatement(meta, query);
		String sql = "SELECT " + cols.stream().collect(Collectors.joining(", ")) + " FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(mschema, model) + " " + alias + joinClause;
		String queryClause = getQueryString(sql, query, meta);
		
		buff.append(queryClause);
		meta.setSql(buff.toString());
		meta.setColumns(useFields);
		return meta;
	}
	
	private static String getJoinStatement(DBStatementMeta meta, BaseRecord query) throws FieldException {
		String alias = getAlias(query);
		List<BaseRecord> joins = query.get(FieldNames.FIELD_JOINS);
		StringBuffer joinBuff = new StringBuffer();
		for(BaseRecord j : joins) {
			String jmodel = j.get(FieldNames.FIELD_TYPE);
			ModelSchema jschema = RecordFactory.getSchema(jmodel);
			String jalias = getAlias(j, jmodel);
			String clause = getQueryClause(j, meta);
			String joinCol = j.get(FieldNames.FIELD_JOIN_KEY);
			if(jschema.getFieldSchema(joinCol) == null) {
				throw new FieldException("Invalid join column " + joinCol + " for " + jmodel);
			}
			ModelSchema mschema = null;
			if(jmodel.equals(ModelNames.MODEL_PARTICIPATION)) {
				String ptype = QueryUtil.findFieldValue(j, FieldNames.FIELD_PARTICIPATION_MODEL, null);
				if(ptype != null) {
					mschema = RecordFactory.getSchema(ptype);
				}
			}
			String joinKey = jalias + "." + joinCol + " = " + alias + "." + FieldNames.FIELD_ID;
			joinBuff.append(" INNER JOIN " + IOSystem.getActiveContext().getDbUtil().getTableName(mschema, jmodel) + " " + jalias + " ON " + joinKey);
			if(clause.length() > 0) {
				joinBuff.append(" AND " + clause);
			}
		}
		
		return joinBuff.toString();
	}
	
	public static String getQueryString(String selectString, Query query, DBStatementMeta meta){
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String pagePrefix = StatementUtil.getPaginationPrefix(query);
		String pageSuffix = StatementUtil.getPaginationSuffix(meta, query);
		// String pageField = getPaginationField(query);
		String queryClause = getQueryClause(query, meta);
		String groupClause = query.get(FieldNames.FIELD_GROUP_CLAUSE);
		String havingClause = query.get(FieldNames.FIELD_HAVING_CLAUSE);
		/// int topCount = query.get(FieldNames.FIELD_TOP_COUNT);
		long organizationId = query.get(FieldNames.FIELD_ORGANIZATION_ID);
		String alias = getAlias(query);
		if(havingClause != null || groupClause != null) {
			logger.error("**** TODO: Fix SQL Injection point");
		}
		
		String modSelectString = selectString;
		
		/*
				.replaceAll("#TOP#", (topCount > 0 ? "TOP " + topCount : ""))
				.replaceAll("#PAGE#", pageField)
		;
		*/

		String queryClauseCond = (queryClause.length() == 0 ? " " : " AND ");
		String clause = queryClause
			+ (organizationId > 0L ? queryClauseCond + alias + "." + dbUtil.getColumnName(FieldNames.FIELD_ORGANIZATION_ID) + "=" + organizationId : "")
		;
		return pagePrefix + modSelectString + " " + (clause .length() > 1 ? "WHERE " + clause : "")
			+ (groupClause != null ? " GROUP BY " + groupClause : "")
			+ (havingClause != null ? " HAVING " + havingClause : "")
			+ pageSuffix
		;

	}
	public static String getQueryClause(BaseRecord query, DBStatementMeta meta)
	{
		return getQueryClause(query, meta, "AND");
	}
	public static String getQueryClause(BaseRecord query, DBStatementMeta meta, String joinType)
	{
		return getQueryClause(query, null, meta, joinType);
	}
	private static String getQueryClause(BaseRecord query, BaseRecord baseQuery, DBStatementMeta meta, String joinType)
	{
		//DBStatementMeta meta = new DBStatementMeta();
		String paramToken = "?";
		StringBuilder matchBuff = new StringBuilder();
		List<BaseRecord> queries = query.get(FieldNames.FIELD_FIELDS);
		String alias = getAlias(query);
		if(alias == null && baseQuery != null) {
			alias = getAlias(baseQuery);
		}
		for (int i = 0; i < queries.size(); i++)
		{
			QueryField qf = new QueryField(queries.get(i));
			boolean incField = true;
			String fieldNameOrig = qf.get(FieldNames.FIELD_NAME);
			
			ComparatorEnumType fieldComp = ComparatorEnumType.valueOf(qf.get(FieldNames.FIELD_COMPARATOR));
			if (i > 0) matchBuff.append(" " + joinType + " ");
			if(fieldComp == ComparatorEnumType.GROUP_AND || fieldComp == ComparatorEnumType.GROUP_OR){
				String useType = (fieldComp == ComparatorEnumType.GROUP_AND ? "AND" : "OR");
				List<BaseRecord> queries2 = query.get(FieldNames.FIELD_FIELDS);
				if(!queries2.isEmpty()){
					matchBuff.append("(" + getQueryClause(qf, (baseQuery != null ? baseQuery : query), meta, useType) + ")");
				}
			}
			else {
				String fieldName = alias + "." + IOSystem.getActiveContext().getDbUtil().getColumnName(fieldNameOrig);
				if (fieldComp == ComparatorEnumType.EQUALS)
				{
					matchBuff.append(fieldName + " = " + paramToken);
				}
				else if (fieldComp == ComparatorEnumType.NOT_EQUALS)
				{
					matchBuff.append(String.format("NOT %s = %s", fieldName, paramToken));
				}
				else if (fieldComp == ComparatorEnumType.LIKE)
				{
					matchBuff.append(String.format("%s LIKE %s", fieldName, paramToken));
				}
				else if (fieldComp == ComparatorEnumType.IN || fieldComp == ComparatorEnumType.NOT_IN)
				{
					String notStr = (fieldComp == ComparatorEnumType.NOT_IN ? " NOT " : "");
					matchBuff.append(String.format("%s %s = ANY(%s)", fieldName, notStr, paramToken));
					/*
					if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
						matchBuff.append(String.format("%s %s IN (%s)", fieldName, notStr, paramToken));
					}
					else if(util.getConnectionType() == ConnectionEnumType.H2) {
						matchBuff.append(String.format("%s %s = ANY(%s)", fieldName, notStr, paramToken));
					}
					*/
					
				}
				else if (fieldComp == ComparatorEnumType.ANY || fieldComp == ComparatorEnumType.NOT_ANY)
				{
					String notStr = (fieldComp == ComparatorEnumType.NOT_ANY ? " NOT " : "");
					matchBuff.append(String.format("%s %s = ANY(%s)", notStr, fieldName, paramToken));
				}
				else if (fieldComp == ComparatorEnumType.GREATER_THAN || fieldComp == ComparatorEnumType.GREATER_THAN_OR_EQUALS)
				{
					matchBuff.append(fieldName + " >" + (fieldComp == ComparatorEnumType.GREATER_THAN_OR_EQUALS ? "=" : "") + " " + paramToken);
				}
				else if (fieldComp == ComparatorEnumType.LESS_THAN || fieldComp == ComparatorEnumType.LESS_THAN_OR_EQUALS)
				{
					matchBuff.append(fieldName + " <" + (fieldComp == ComparatorEnumType.LESS_THAN_OR_EQUALS ? "=" : "") + " " + paramToken);
				}
				else{
					logger.error("Unhandled Comparator: " + fieldComp);
					incField = false;
				}
				if(incField) {
					meta.getFields().add(fieldNameOrig);
				}
			}

		}
		return matchBuff.toString();
	}
	
	public static boolean isInstructionReadyForPagination(Query query)
	{
		long startRecord = query.get(FieldNames.FIELD_START_RECORD);
		int recordCount = query.get(FieldNames.FIELD_RECORD_COUNT);

		return (
			query != null
			&& query.get(FieldNames.FIELD_ORDER) != null
			&& startRecord >= 0L
			&& recordCount > 0
		);
	}
	public static String getPaginationPrefix(Query query)
	{
		return "";
	}
	public static String getPaginationField(Query query)
	{
		/*
		if (!isInstructionReadyForPagination(query)) {
			return "";
		}
		*/
		return "";
	}
	
	private static List<String> allowedSortFields = Arrays.asList(new String[] {
		"random()"
	});
	public static String getPaginationSuffix(DBStatementMeta meta, Query query)
	{
		long startRecord = query.get(FieldNames.FIELD_START_RECORD);
		int recordCount = query.get(FieldNames.FIELD_RECORD_COUNT);
		String order = query.get(FieldNames.FIELD_ORDER);
		if(order == null || order.equals(OrderEnumType.ASCENDING.toString()) || order.equals(OrderEnumType.UNKNOWN.toString())) {
			order = "ASC";
		}
		else {
			 order = "DESC";
		}
		String alias = getAlias(query);
		String sort = query.get(FieldNames.FIELD_SORT_FIELD);
		if(sort != null && !allowedSortFields.contains(sort)) {

			if(RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE)).hasField(sort)) {
				sort = alias + "." + sort;
			}
			else {
				logger.error("Sort field is not defined on model - " + sort);
				sort = null;
			}
		}
		String orderClause = (query != null && order != null && sort != null ? " ORDER BY " + sort + " " + order : "");
		
		/// Only use the default order clause on select statements
		if(orderClause.length() == 0 && meta.getStatementType() == DBStatementEnumType.SELECT) {
			orderClause = getDefaultOrderClause(query.getType(), alias);
		}
		if (!isInstructionReadyForPagination(query)) {
			return orderClause;
		}
		return orderClause + " LIMIT " + recordCount + " OFFSET " + startRecord;
	}
	protected static ModelSchema getModelOrder(String model, Set<String> iset) {
		if(iset.contains(model)) {
			return null;
		}
		iset.add(model);

		ModelSchema oms = null;
		ModelSchema ms = RecordFactory.getSchema(model);
		if(ms.getSortOrder() != OrderEnumType.UNKNOWN && ms.getSortField() != null) {
			return ms;
		}
		/// TODO: Fix recursion issue when first loading
		/*
		for(String s: ms.getInherits()) {
			oms = getModelOrder(s, iset);
			if(oms != null) {
				break;
			}
		}
		*/
		return oms;
	}
	
	private static Map<String, String> orderMap = new ConcurrentHashMap<>();
	protected static String getDefaultOrderClause(String model, String alias) {
		return getDefaultOrderClause(model, alias, orderMap);
	}
	private static String getDefaultOrderClause(String model, String alias, Map<String, String> omap) {
		if(!IOSystem.isInitialized()) {
			return "";
		}
		String key = model + "." + alias;
		if(omap.containsKey(key)) {
			return omap.get(key);
		}
		// logger.info(model + " / " + omap.containsKey(model));
		String orderClause = "";
		ModelSchema ms = getModelOrder(model, ConcurrentHashMap.newKeySet());
		if(ms != null) {
			orderClause = " ORDER BY " + alias + "." + ms.getSortField() + " " + (ms.getSortOrder() == OrderEnumType.ASCENDING ? "ASC" : "DESC");
		}
		omap.put(key, orderClause);
		return orderClause;
	}
	
	public static <T> void applyPreparedStatement(BaseRecord record, DBStatementMeta meta, PreparedStatement statement) throws DatabaseException {
		for(int i = 0; i < meta.getFields().size(); i++) {
			String f = meta.getFields().get(i);
			FieldType ft = record.getField(f);
			if(ft == null) {
				logger.error("Null field for " + f + " in " + record.getModel());
				logger.error(record.toString());
				continue;
			}
			T val = ft.getValue();
			setStatementParameter(statement, record.getModel(), ft.getName(), ComparatorEnumType.UNKNOWN, ft.getValueType(), val, (i + 1));
		}
	}
	
	public static void setStatementParameters(BaseRecord query, PreparedStatement statement) throws DatabaseException {
		setStatementParameters(query, 1, statement);
	}
	public static int setStatementParameters(BaseRecord query, int startMarker, PreparedStatement statement) throws DatabaseException{
		return setStatementParameters(query, null, startMarker, statement);
	}
	public static int setStatementParameters(BaseRecord query, BaseRecord baseQuery, int startMarker, PreparedStatement statement) throws DatabaseException{
		List<BaseRecord> fields = query.get(FieldNames.FIELD_FIELDS);
		
		List<BaseRecord> joins = new ArrayList<>();
		String model = null;
		if(query.inherits(ModelNames.MODEL_QUERY)) {
			joins = query.get(FieldNames.FIELD_JOINS);
			model = query.get(FieldNames.FIELD_TYPE);
		}
		else if(baseQuery != null) {
			joins = baseQuery.get(FieldNames.FIELD_JOINS);
			model = baseQuery.get(FieldNames.FIELD_TYPE);
		}
		int paramMarker = startMarker;
		for(BaseRecord j : joins) {
			paramMarker = setStatementParameters(j, baseQuery, paramMarker, statement);
		}
		/*
		if(joins.size() > 0) {
			logger.info("Marker adjustment: " + startMarker + " to " + paramMarker);
		}
		*/
		int len = fields.size();

		
		ModelSchema schema = RecordFactory.getSchema(model);
		for(int i = 0; i < len; i++){
			BaseRecord field = fields.get(i);
			if(field == null) {
				continue;
			}
			ComparatorEnumType comp = ComparatorEnumType.valueOf(field.get(FieldNames.FIELD_COMPARATOR));
			// FieldEnumType fet = FieldEnumType.valueOf(field.get(FieldNames.FIELD_VALUE_TYPE));
			FieldEnumType fet = field.getField(FieldNames.FIELD_VALUE).getValueType();
			if(fet == FieldEnumType.UNKNOWN) {
				throw new DatabaseException("Unexpected field type for " + field.get(FieldNames.FIELD_NAME));
			}
			if(comp == ComparatorEnumType.GROUP_AND || comp == ComparatorEnumType.GROUP_OR){
				paramMarker = setStatementParameters(field, (baseQuery != null ? baseQuery : query), paramMarker, statement);
			}
			else if (
				comp == ComparatorEnumType.EQUALS
				|| comp == ComparatorEnumType.NOT_EQUALS	
				|| comp == ComparatorEnumType.GREATER_THAN
				|| comp == ComparatorEnumType.GREATER_THAN_OR_EQUALS	
				|| comp == ComparatorEnumType.LESS_THAN
				|| comp == ComparatorEnumType.LESS_THAN_OR_EQUALS
				|| comp == ComparatorEnumType.LIKE
			){
				setStatementParameter(statement, model, field.get(FieldNames.FIELD_NAME), comp, fet, field.get(FieldNames.FIELD_VALUE), paramMarker++);
			}
			else if(
					comp == ComparatorEnumType.IN
					|| comp == ComparatorEnumType.NOT_IN
					|| comp == ComparatorEnumType.ANY
					|| comp == ComparatorEnumType.NOT_ANY
			) {
				try {
					String[] packVal = ((String)field.get(FieldNames.FIELD_VALUE)).split(",");
					Object[] array = packVal;
					FieldSchema fs = schema.getFieldSchema(field.get(FieldNames.FIELD_NAME));
					FieldEnumType ffet = FieldEnumType.valueOf(fs.getType().toUpperCase());
					switch(ffet) {
						case ENUM:
							//List<String> useValStr = Stream.of(packVal).map(s -> "'" + s + "'").collect(Collectors.toList());
							//array = useValStr.toArray(new String[0]);
							break;
						case MODEL:
						case LONG:
							List<Long> useValLong = Stream.of(packVal).map(Long::valueOf).collect(Collectors.toList());
							array = useValLong.toArray(new Long[0]);
							break;
						case INT:
							List<Integer> useValInt = Stream.of(packVal).map(Integer::valueOf).collect(Collectors.toList());
							array = useValInt.toArray(new Long[0]);
							break;
						default:
							/// leave it as varchar
							break;
					}
					SqlDataEnumType sdet = SqlTypeUtil.toSqlType(ffet);
					Array arr = statement.getConnection().createArrayOf(sdet.toString(), array);
					statement.setArray(paramMarker++, arr);
				} catch (SQLException e) {
					logger.error(e.getMessage());
					throw new DatabaseException(e.getMessage());
				}
			}
		}
		return paramMarker;
	}
	
	private static <T> void setStatementParameter(PreparedStatement statement, String model, String fieldName, ComparatorEnumType comp, FieldEnumType dataType, T value, int index) throws DatabaseException{
		ModelSchema ms = RecordFactory.getSchema(model);
		FieldSchema fs = ms.getFieldSchema(fieldName);
		String colType = null;
		if(index <= 0){
			throw new DatabaseException("Prepared Statement index is 1-based, not 0-based, and the index must be greater than or equal to 1");
		}

		try{
			switch(dataType){
				case VECTOR:
					DBUtil dbu = IOSystem.getActiveContext().getDbUtil();
					if(dbu.isEnableVectorExtension()) {
						if(dbu.getConnectionType() == ConnectionEnumType.POSTGRE) {
							statement.setObject(index, new PGvector((float[])value));
						}
						else {
							throw new DatabaseException("Vector extension is not supported");
						}
					}
					else {
						throw new DatabaseException("Vector extension is not enabled");
					}
					break;
				case BLOB:
					statement.setBytes(index, (byte[])value);
					break;
				case ENUM:
				case STRING:
					if(value == null) {
						statement.setNull(index, Types.VARCHAR);
					}
					else if(!fs.isForeign() && fs.getType().toUpperCase().equals(FieldEnumType.MODEL.toString())) {
						logger.warn("Persisting model " + fieldName + " as JSON String");
						statement.setString(index, ((BaseRecord)value).toString());
					}
					else {
						String sval = (String)value;
						if(comp == ComparatorEnumType.LIKE && sval != null && sval.indexOf("%") == -1) {
							sval = "%" + sval + "%";
						}
						statement.setString(index,  sval);
					}
					break;
				case INT:
					if(value != null)
						statement.setInt(index,  ((Integer)value).intValue());
					else{
						logger.warn("Null int detected.  If this is for an id field, the probable cause is that a bulk insert session includes both bulk and dirty writes of the same factory type");
						statement.setNull(index, Types.BIGINT);
					}
					break;
				case LONG:
					
					if(value != null)
						statement.setLong(index,  ((Long)value).longValue());
					else{
						
						logger.warn("Null bigint detected.  If this is for an id field, the probable cause is that a bulk insert session includes both bulk and dirty writes of the same factory type");
						statement.setNull(index, Types.BIGINT);
					}
					break;
				case DOUBLE:
					statement.setDouble(index,  ((Double)value).doubleValue());
					break;
				case BOOLEAN:
					statement.setBoolean(index, ((Boolean)value).booleanValue());
					break;
				case ZONETIME:
					ZonedDateTime zone = (ZonedDateTime)value;
					statement.setTimestamp(index, Timestamp.from(zone.toInstant()));
					break;
				case TIMESTAMP:
					statement.setTimestamp(index, new Timestamp(((Date)value).getTime()));
					break;
				case FLEX:
					if(!ModelNames.MODEL_MODEL.equals(fs.getType())){
						throw new DatabaseException("Unhandled flex type:" + dataType + " for " + model + "." + fieldName + " at index " + index + " with base " + fs.getBaseModel() + " / " + fs.getValueType() + " and value " + value);
					}
					else {
						colType = IOSystem.getActiveContext().getDbUtil().getDataType(fs, FieldEnumType.valueOf(fs.getType().toUpperCase()));
					}
					
				case MODEL:
					
					if(fs.isForeign()) {
						if(colType == null) {
							colType = IOSystem.getActiveContext().getDbUtil().getDataType(fs, dataType);
						}
						/// TODO: Need to handle the foreignField config
						///
						/// logger.info("Col type: " + colType);
						if(colType != null) {
							BaseRecord crec = (BaseRecord)value;
							if(colType.equals("bigint")) {
								long id = 0L;
								if(crec != null && crec.hasField(FieldNames.FIELD_ID)) {
									id = crec.get(FieldNames.FIELD_ID);
								}
								statement.setLong(index, id);
							}
							else if(colType.equals("text") || colType.startsWith("varchar")) {
								String id = null;
								if(crec != null && crec.hasField(FieldNames.FIELD_OBJECT_ID)) {
									id = crec.get(FieldNames.FIELD_OBJECT_ID);
								}
								statement.setString(index, id);
							}
							else {
								logger.error("**** NEED TO HANDLE COLTYPE " + colType);	
							}
						}
						else {
							logger.error("**** NEED TO HANDLE NULL COLTYPE");
						}
					}
					else {
						// logger.error("**** NEED TO HANDLE MODEL TO JSON");
						BaseRecord valRec = (BaseRecord)value;
						statement.setString(index, (valRec != null ? valRec.toString() : null));
					}
					break;
				case LIST:
					if(!fs.isForeign()) {
						List<?> list = (List<?>)value;
						String ser = JSONUtil.exportObject(list, RecordSerializerConfig.getUnfilteredModule());
						//logger.info(ser);
						statement.setString(index, ser);
						break;
					}
				default:
					
					throw new DatabaseException("Unhandled data type:" + dataType + " for " + model + "." + fieldName + " at index " + index);
			}
		}
		catch (SQLException e) {
			logger.error("Error setting " + index + " for " + fieldName + " with " + value);
			logger.error(e);
			throw new DatabaseException(e);
		}
	}

	protected static void populateRecord(DBStatementMeta meta, ResultSet rset, BaseRecord record) throws FieldException, ValueException, ModelNotFoundException, SQLException, ReaderException {
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		int subCount = 0;
		Map<String, FieldType> flexCols = new HashMap<>();
		Map<String, FieldType> modelCols = new HashMap<>();

		for(String col : meta.getColumns()) {
			String colName = col;
			FieldType f = record.getField(col);
			FieldSchema fs = ms.getFieldSchema(col);
			if(f == null) {
				throw new FieldException("Field " + record.getModel() + "." + col + " not found");
			}
			switch(f.getValueType()) {
				case ENUM:
				case STRING:
					record.set(col, rset.getString(colName));
					break;
				case BOOLEAN:
					record.set(col, rset.getBoolean(colName));
					break;
				case LONG:
					record.set(col, rset.getLong(colName));
					break;
				case DOUBLE:
					record.set(col, rset.getDouble(colName));
					break;
				case INT:
					record.set(col, rset.getInt(colName));
					break;
				case ZONETIME:
					record.set(col, ZonedDateTime.ofInstant(rset.getTimestamp(colName).toInstant(), ZoneOffset.UTC));
					break;
				case TIMESTAMP:
					record.set(col, rset.getTimestamp(colName));
					break;
				case BLOB:
					record.getField(colName).setValue(rset.getBytes(colName));
					break;
				case LIST:
					String ser = rset.getString(colName);
					if(ser == null || ser.length() == 0) {
						continue;
					}
					//if(fs.isReferenced() || fs.isForeign()) {
					if(!modelMode && (fs.isReferenced() || fs.isForeign())) {

						List<BaseRecord> queries = meta.getQuery().get(FieldNames.FIELD_QUERIES);
						if(queries.size() <= subCount) {
							throw new FieldException("Expected a sub query at index " + subCount);
						}
						if(ser != null) {

							List<?> lst = JSONUtil.getList(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
							if(lst != null) {
								record.set(col, lst);
							}
							else {
								logger.error("Null list for " + col);
							}
						}

						subCount++;
					}
					else if(!fs.isForeign() || modelMode) {
						List<?> lst = new ArrayList<>();

						if(ModelNames.MODEL_MODEL.equals(fs.getBaseType())) {
							lst = JSONUtil.getList(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
						}
						else {
							lst = JSONUtil.importObject(ser, ArrayList.class);
						}
						record.set(col, lst);
					}
					else {
						throw new FieldException("Unhandled list type: " + record.getModel() + "." + col);
					}
					break;
				case MODEL:
					if(fs.getBaseModel() != null) {
						modelCols.put(col, f);
						break;
					}

				case FLEX:
					if(fs.getValueType() != null) {
						flexCols.put(col, f);
						break;
					}
				default:
					throw new FieldException("Unhandled type: " + f.getValueType().toString());
			}

		}

		for(String col : modelCols.keySet()) {
			FieldType f = modelCols.get(col);
			FieldSchema fs = ms.getFieldSchema(f.getName());
			String modelName = fs.getBaseModel();
			if(fs.isForeign() && ModelNames.MODEL_FLEX.equals(modelName) && fs.getForeignType() != null) {
				modelName = record.get(fs.getForeignType());
			}
			if(modelName == null || ModelNames.MODEL_FLEX.equals(modelName) || ModelNames.MODEL_UNKNOWN.equals(modelName)) {
				/// This isn't necessarily an error because empty flex foreign models won't have a valid type for optional fields 
				continue;
			}

			BaseRecord crec = RecordFactory.newInstance(modelName);
			ModelSchema cms = RecordFactory.getSchema(modelName);
			boolean haveId = false;
			
			if(!modelMode && fs.isForeign()) {
				FieldSchema fcs = cms.getFieldSchema(FieldNames.FIELD_ID);

				if(fs.getForeignField() != null) {
					fcs = cms.getFieldSchema(fs.getForeignField());
				}
				FieldType fld = crec.getField(fcs.getName());
				if(fld.getValueType() == FieldEnumType.STRING) {
					String id = rset.getString(col);
					if(id != null) {
						haveId = true;
						crec.set(fcs.getName(), id);
					}
				}
				else if(fld.getValueType() == FieldEnumType.LONG) {
					long lid = rset.getLong(col);
					if(lid > 0L) {
						haveId = true;
						crec.set(fcs.getName(), lid);
					}
				}
				else {
					logger.error("Unhandled type: " + fld.getValueType().toString());
				}
			}
			else {
			
				String colStr = rset.getString(col);
				if(colStr != null) {
					crec = JSONUtil.importObject(colStr, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
					
					if(crec != null) {
						(new MemoryReader()).read(crec);
					}
					
					haveId = true;
				}
				else {
					logger.debug("Null value for " + col);
				}
			}
			if(haveId) {
				record.set(col, crec);
			}
		}

		for(String col : flexCols.keySet()) {
			FieldType f = flexCols.get(col);
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs.getValueType() != null && record.get(fs.getValueType()) != null) {
				FieldEnumType fvet = FieldEnumType.valueOf(record.get(fs.getValueType()));
				switch(fvet) {
					case ENUM:
					case STRING:
						record.set(f.getName(), rset.getString(col));
						break;
					case BOOLEAN:
						record.set(f.getName(), rset.getBoolean(col));
						break;
					case LONG:
						record.set(f.getName(), rset.getLong(col));
						break;
					case DOUBLE:
						record.set(f.getName(), rset.getDouble(col));
						break;
					case INT:
						record.set(f.getName(), rset.getInt(col));
						break;
					case ZONETIME:
						record.set(f.getName(), ZonedDateTime.ofInstant(rset.getTimestamp(col).toInstant(), ZoneOffset.UTC));
						break;
					case TIMESTAMP:
						record.set(f.getName(), rset.getTimestamp(col));
						break;
					case UNKNOWN:
						break;
					default:
						logger.error("Unhandled flex value type: " + fvet.toString());
						break;
				}
			}
			else {
				logger.error("Flex value type is null");
			}
		}
		
	}

	
}
