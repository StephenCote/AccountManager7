package org.cote.accountmanager.io.db;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
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
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.json.JSONArray;
import org.json.JSONException;

public class StatementUtil {
	
	public static final Logger logger = LogManager.getLogger(StatementUtil.class);
	
	public static DBStatementMeta getInsertTemplate(BaseRecord record) {
		StringBuilder buff = new StringBuilder();
		buff.append("INSERT INTO " + IOSystem.getActiveContext().getDbUtil().getTableName(record.getModel()) + " (");
		List<String> names = new ArrayList<>();
		List<String> columns = new ArrayList<>();
		List<String> tokens = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		for(FieldType f: record.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs.isEphemeral() || fs.isVirtual() || fs.isReferenced()) {
				// logger.info("Skip " + f.getName());
				continue;
			}
			if(fs.isForeign() && f.getValueType() == FieldEnumType.LIST) {
				logger.info("Skip " + f.getName() + " because it's a foreign list");
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
	
	public static DBStatementMeta getDeleteTemplate(BaseRecord record) {
		StringBuilder buff = new StringBuilder();
		buff.append("DELETE FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(record.getModel()));
		List<String> names = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
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
		buff.append("UPDATE " + IOSystem.getActiveContext().getDbUtil().getTableName(record.getModel()) + " SET ");
		List<String> names = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		int iter = 0;
		FieldType ident = null;
		for(int i = 0; i < record.getFields().size(); i++) {
			FieldType f = record.getFields().get(i);
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs.isIdentity()) {
				if(!FieldUtil.isNullOrEmpty(record.getModel(), f)) {
					ident = f;
				}
				continue;
			}
			else if(fs.isEphemeral() || fs.isVirtual() || fs.isReferenced() || fs.isReadOnly()) {
				continue;
			}
			if(fs.isForeign() && f.getValueType() == FieldEnumType.LIST) {
				logger.info("Skip " + f.getName() + " because it's a foreign list");
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
		return getInnerReferenceSelectTemplate(query, schema, true);
	}
	public static String getInnerReferenceSelectTemplate(Query query, FieldSchema schema, boolean followRef) throws FieldException {
		StringBuilder buff = new StringBuilder();
		if(!schema.isReferenced() && !schema.isForeign()) {
			throw new FieldException("Field schema is not a referenced or foreign field");
		}
		if(schema.getBaseModel() == null) {
			throw new FieldException("Field schema does not define a base model");
		}
		ModelSchema msschema = RecordFactory.getSchema(schema.getBaseModel());
		//logger.info("Composing inner select for " + schema.getBaseModel());
		StringBuilder ibuff = new StringBuilder();
		List<String> fields = new ArrayList<>();
		List<String> cols = new ArrayList<>();
		String model = query.get(FieldNames.FIELD_TYPE);
		String subModel = schema.getBaseModel();
		Query subQuery = new Query(subModel);
		try {
			subQuery.set(FieldNames.FIELD_COUNT, query.get(FieldNames.FIELD_COUNT));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new FieldException(e);
		} 
		String alias = getAlias(query);

		String salias = getAlias(subQuery);
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		for(FieldSchema fs : msschema.getFields() ) {
			if(fs.isVirtual() || fs.isEphemeral() || (fs.isReferenced() && !followRef)) {
				// logger.warn("Skip " + fs.getName());
				continue;
			}
			if((followRef && fs.isFollowReference()) || fs.isIdentity() || (schema.isReferenced() && isReferenceField(fs))) {
				if(fs.isReferenced()) {
					logger.warn("**** Handle deep reference for " + model + "." + fs.getName());
					cols.add(getInnerReferenceSelectTemplate(subQuery, fs));
					fields.add(fs.getName());
				}
				else {
					String colName = salias + "." + util.getColumnName(fs.getName());
					cols.add(colName);
					fields.add(fs.getName());
				}
			}
			else {
				// logger.warn("Missed " + fs.getName());
			}
		}
		if(schema.isForeign()) {
			throw new FieldException("**** TODO: Add in foreign lookup");
		}
		else if(schema.isReferenced()) {
			if(util.getConnectionType() == ConnectionEnumType.H2) {
				buff.append("JSON_ARRAY(SELECT JSON_ARRAY(" + cols.stream().collect(Collectors.joining(", ")) + ") FROM " + util.getTableName(subModel) + " " + salias + " WHERE " + salias + ".referenceType = '" + model + "' AND " + salias + ".referenceId = " + alias + ".id) as " + util.getColumnName(schema.getName()));
			}
			
			else if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
				StringBuilder ajoin = new StringBuilder();
				//for(String s : cols) {
				for(int i = 0; i < cols.size(); i++) {
					String s = cols.get(i);
					String f = fields.get(i);
					if(ajoin.length() > 0) {
						ajoin.append(", ");
					}
					ajoin.append("'" + f + "', " + s);
				}
				buff.append("(SELECT JSON_AGG(JSON_BUILD_OBJECT(" + ajoin.toString() + ", 'model', '" + subModel + "')) FROM " + util.getTableName(subModel) + " " + salias + " WHERE " + salias + ".referenceType = '" + model + "' AND " + salias + ".referenceId = " + alias + ".id) as " + util.getColumnName(schema.getName()));
			}
			
		}
		
		if(buff.length() == 0) {
			throw new FieldException("**** Unhandled inner query: " + util.getConnectionType().toString());
		}
		
		subQuery.setRequest(fields.toArray(new String[0]));
		List<BaseRecord> queries = query.get(FieldNames.FIELD_QUERIES);
		queries.add(subQuery);
		
		return buff.toString();
	}
	public static boolean isReferenceField(FieldSchema field) {
		return (field.getName().equals(FieldNames.FIELD_REFERENCE_ID) || field.getName().equals(FieldNames.FIELD_REFERENCE_TYPE));
	}
	
	public static String getAlias(BaseRecord query) {
		return getAlias(query, query.get(FieldNames.FIELD_TYPE));
	}
	
	public static String getAlias(BaseRecord query, String model) {
		String alias = query.get(FieldNames.FIELD_ALIAS);
		if(alias == null) {
			// alias = RandomStringUtils.randomAlphabetic(3).toUpperCase();
			try {
				int count = query.get(FieldNames.FIELD_COUNT);
				count++;
				query.set(FieldNames.FIELD_COUNT, count);
				alias = model.substring(0, 1).toUpperCase() + Integer.toString(count);
				query.set(FieldNames.FIELD_ALIAS, alias);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		return alias;
	}
	
	public static DBStatementMeta getCountTemplate(Query query) throws ModelException, FieldException {
		DBStatementMeta meta = new DBStatementMeta(query);

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
			for(FieldSchema fs : schema.getFields()) {
				if(fs.isSequence() || fs.isIdentity()) {
					requestFields.add(fs.getName());
					break;
				}
			}
		}
		if(requestFields.size() == 0) {
			throw new FieldException("Could not find an identity column to count");
		}
		cols.add("count(" + requestFields.get(0) + ") as A7Count");

		String alias = getAlias(query);
		String sql = "SELECT " + cols.stream().collect(Collectors.joining(", ")) + " FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(model) + " " + alias;
		buff.append(getQueryString(sql, query, meta));
		meta.setSql(buff.toString());
		meta.setColumns(useFields);
		return meta;
	}
	
	
	public static DBStatementMeta getSelectTemplate(Query query) throws ModelException, FieldException {
		DBStatementMeta meta = new DBStatementMeta(query);

		StringBuilder buff = new StringBuilder();
		//logger.info(query.toString());
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
			//logger.info("Query for all model fields");
			schema.getFields().forEach(f -> {
				/// && !f.isReferenced()
				requestFields.add(f.getName());
			});
		}
		//useFields.addAll(requestFields);
		for(String s: requestFields) {
			FieldSchema fs = schema.getFieldSchema(s);
			if(fs == null) {
				throw new FieldException("Field '" + s + "' was not found on model " + model);
			}
			
			if(!fs.isEphemeral() && !fs.isVirtual()) {
				if(fs.isForeign() && fs.getType().toUpperCase().equals(FieldEnumType.LIST.toString())) {
					logger.info("Skip " + fs.getName() + " because it's a foreign list");
					continue;
				}

				
				useFields.add(fs.getName());
				if(fs.isReferenced()) {
					try {
						cols.add(getInnerReferenceSelectTemplate(query, fs));
					} catch (FieldException e) {
						logger.error(e);
					}
				}
				else {
					cols.add(IOSystem.getActiveContext().getDbUtil().getColumnName(fs.getName()));
				}
			}

		}

		if(cols.size() == 0) {
			logger.error(query.toString());
			throw new ModelException("No columns were specified in the query");
		}
		// query.setRequest(useFields.toArray(new String[0]));
		String alias = getAlias(query);
		String sql = "SELECT " + cols.stream().collect(Collectors.joining(", ")) + " FROM " + IOSystem.getActiveContext().getDbUtil().getTableName(model) + " " + alias;
		buff.append(getQueryString(sql, query, meta));
		meta.setSql(buff.toString());
		meta.setColumns(useFields);
		return meta;
	}
	
	public static String getQueryString(String selectString, Query query, DBStatementMeta meta){
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String pagePrefix = StatementUtil.getPaginationPrefix(query);
		String pageSuffix = StatementUtil.getPaginationSuffix(query);
		// String pageField = getPaginationField(query);
		String queryClause = StatementUtil.getQueryClause(query, meta);
		String groupClause = query.get(FieldNames.FIELD_GROUP_CLAUSE);
		String havingClause = query.get(FieldNames.FIELD_HAVING_CLAUSE);
		int topCount = query.get(FieldNames.FIELD_TOP_COUNT);
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
		return pagePrefix + modSelectString + " WHERE " + queryClause
			+ (organizationId > 0L ? queryClauseCond + alias + "." + dbUtil.getColumnName(FieldNames.FIELD_ORGANIZATION_ID) + "=" + organizationId : "")
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
		//DBStatementMeta meta = new DBStatementMeta();
		String paramToken = "?";
		StringBuilder matchBuff = new StringBuilder();
		List<BaseRecord> queries = query.get(FieldNames.FIELD_FIELDS);
		String alias = getAlias(query);
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		for (int i = 0; i < queries.size(); i++)
		{
			QueryField qf = new QueryField(queries.get(i));
			boolean incField = true;
			String fieldNameOrig = qf.get(FieldNames.FIELD_NAME);
			String fieldName = alias + "." + IOSystem.getActiveContext().getDbUtil().getColumnName(fieldNameOrig);
			ComparatorEnumType fieldComp = ComparatorEnumType.valueOf(qf.get(FieldNames.FIELD_COMPARATOR));
			if (i > 0) matchBuff.append(" " + joinType + " ");
			if(fieldComp == ComparatorEnumType.GROUP_AND || fieldComp == ComparatorEnumType.GROUP_OR){
				String useType = (fieldComp == ComparatorEnumType.GROUP_AND ? "AND" : "OR");
				List<BaseRecord> queries2 = query.get(FieldNames.FIELD_FIELDS);
				if(!queries2.isEmpty()){
					matchBuff.append("(" + getQueryClause(qf, meta, useType) + ")");
				}
			}
			else if (fieldComp == ComparatorEnumType.EQUALS)
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
	public static String getPaginationSuffix(Query query)
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
		String sort = query.get(FieldNames.FIELD_SORT_FIELD);
		String orderClause = (query != null && order != null && sort != null ? " ORDER BY " + sort + " " + order : "");
		if (!isInstructionReadyForPagination(query)) {
			return orderClause;
		}
		
		return orderClause + " LIMIT " + recordCount + " OFFSET " + startRecord;
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
		List<BaseRecord> fields = query.get(FieldNames.FIELD_FIELDS);
		int len = fields.size();
		int paramMarker = startMarker;
		String model = query.get(FieldNames.FIELD_TYPE);
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
				paramMarker = setStatementParameters(field, paramMarker, statement);
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
					e.printStackTrace();
					throw new DatabaseException(e.getMessage());
				}
			}
		}
		return paramMarker;
	}
	
	private static <T> void setStatementParameter(PreparedStatement statement, String model, String fieldName, ComparatorEnumType comp, FieldEnumType dataType, T value, int index) throws DatabaseException{
		ModelSchema ms = RecordFactory.getSchema(model);
		FieldSchema fs = ms.getFieldSchema(fieldName);
		if(index <= 0){
			throw new DatabaseException("Prepared Statement index is 1-based, not 0-based, and the index must be greater than or equal to 1");
		}

		try{
			switch(dataType){
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
				case TIMESTAMP:

					statement.setTimestamp(index, new Timestamp(((Date)value).getTime()));
					break;
				case MODEL:
					
					if(fs.isForeign()) {
						String colType = IOSystem.getActiveContext().getDbUtil().getDataType(fs, dataType);
						/// TODO: Need to handle the foreignField config
						///
						if(colType != null) {
							BaseRecord crec = (BaseRecord)value;
							if(colType.equals("bigint")) {
								long id = 0L;
								if(crec != null) {
									id = crec.get(FieldNames.FIELD_ID);
								}
								statement.setLong(index, id);
							}
							else if(colType.equals("text") || colType.startsWith("varchar")) {
								String id = null;
								if(crec != null) {
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
					throw new DatabaseException("Unhandled data type:" + dataType + " for index " + index);
			}
		}
		catch (SQLException e) {
			logger.error(e.getMessage());
			logger.error(e);
			throw new DatabaseException(e.getMessage());
		}
	}

	protected static void populateRecord(DBStatementMeta meta, ResultSet rset, BaseRecord record) throws FieldException, ValueException, ModelNotFoundException, SQLException {
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		ModelSchema ms = RecordFactory.getSchema(record.getModel());
		int subCount = 0;
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
				case TIMESTAMP:
					record.set(col, rset.getTimestamp(colName));
					break;
				case BLOB:
					record.set(col, rset.getBytes(colName));
					break;
				case LIST:
					if(fs.isReferenced()) {

						List<BaseRecord> queries = meta.getQuery().get(FieldNames.FIELD_QUERIES);
						if(queries.size() <= subCount) {
							throw new FieldException("Expected a sub query at index " + subCount);
						}

						if(util.getConnectionType() == ConnectionEnumType.H2) {
							applyH2JSONArrayToList(new Query(queries.get(subCount)), rset.getArray(colName), record, fs, f);
							subCount++;
						}
						else if(util.getConnectionType() == ConnectionEnumType.POSTGRE) {
							//String json = rset.getString(colName);
							String ser = rset.getString(colName);
							if(ser != null) {
								List<?> lst = JSONUtil.getList(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
								record.set(col, lst);
							}
							// logger.info("***** JSON " + ser);
							subCount++;
						}
						else {
							throw new FieldException("Unhandled connection type: " + util.getConnectionType().toString());	
						}
						// logger.info("Handle: " + col);
					}
					else if(!fs.isForeign()) {
						List<?> lst = new ArrayList<>();
						if(ModelNames.MODEL_MODEL.equals(fs.getBaseType())) {
							lst = JSONUtil.getList(rset.getString(colName), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
						}
						else {
							lst = JSONUtil.importObject(rset.getString(colName), ArrayList.class);
						}
						record.set(col, lst);
					}
					else {
						throw new FieldException("Unhandled list type: " + record.getModel() + "." + col);
					}
					break;
				case MODEL:
					// logger.warn("TODO: Implement foreign key resolution");
					if(fs.getBaseModel() != null) {
						BaseRecord crec = RecordFactory.newInstance(fs.getBaseModel());
						ModelSchema cms = RecordFactory.getSchema(fs.getBaseModel());
						FieldSchema fcs = cms.getFieldSchema(FieldNames.FIELD_ID);
						boolean haveId = false;
						if(fs.getForeignField() != null) {
							fcs = cms.getFieldSchema(fs.getForeignField());
						}
						FieldType fld = crec.getField(fcs.getName());
						if(fld.getValueType() == FieldEnumType.STRING) {
							String id = rset.getString(colName);
							if(id != null) {
								haveId = true;
								crec.set(fcs.getName(), id);
							}
						}
						else if(fld.getValueType() == FieldEnumType.LONG) {
							long lid = rset.getLong(colName);
							if(lid > 0L) {
								haveId = true;
								crec.set(fcs.getName(), lid);
							}
						}
						else {
							logger.error("Unhandled type: " + fld.getValueType().toString());
						}
						if(haveId) {
							record.set(col, crec);
						}
						break;
						
					}
				default:
					throw new FieldException("Unhandled type: " + f.getValueType().toString());
			}
		}
	}
	private static <T> void setValueByJSONArray(BaseRecord record, JSONArray jsarr, int index, FieldSchema fs, FieldEnumType fet, String name) throws JSONException, ValueException, ModelException, FieldException, ModelNotFoundException {
		switch(fet) {
			case ENUM:
			case STRING:
				record.set(name, jsarr.getString(index));
				break;
			case TIMESTAMP:
				record.set(name, new Date(jsarr.getLong(index)));
				break;
			case LONG:
				record.set(name, jsarr.getLong(index));
				break;
			case INT:
				record.set(name, jsarr.getInt(index));
				break;
			case DOUBLE:
				record.set(name, jsarr.getDouble(index));
				break;
			case BLOB:
				record.set(name, BinaryUtil.fromBase64Str(jsarr.getString(index)));
				break;
			case BOOLEAN:
				record.set(name, jsarr.getBoolean(index));
				break;
			case LIST:
			case FLEX:
			case MODEL:
			default:
				logger.error("Unhandled type in setValueByJSONArray: " + fet.toString() + " " + name);
				break;
		}
	}

	private static <T> void applyH2JSONArrayToList(Query query, Array ar, BaseRecord record, FieldSchema fs, FieldType f) throws SQLException {
		ResultSet arset = ar.getResultSet();
		//String model = query.get(FieldNames.FIELD_TYPE);
		ModelSchema schema = RecordFactory.getSchema(fs.getBaseModel());
		try {
			while(arset.next()) {
				String jsonArray = arset.getString(2);
				JSONArray jsarr = new JSONArray(jsonArray);
				for(int i = 0; i < jsarr.length(); i++) {
					JSONArray jsarr2 = jsarr.getJSONArray(i);
					if(fs.getBaseType().equals(ModelNames.MODEL_MODEL)) {
						List<BaseRecord> recs = record.get(f.getName());
						BaseRecord newRec = RecordFactory.newInstance(fs.getBaseModel());
						List<String> reqFields = query.get(FieldNames.FIELD_REQUEST);
						Map<String, String> flexFields = new HashMap<>();
						//Set<String> flexFields = new HashSet<>();
						//for(String fn : reqFields) {
						for(int i2 = 0; i2 < reqFields.size(); i2++) {
							String fn = reqFields.get(i2);
							FieldSchema afs = schema.getFieldSchema(fn);
							FieldEnumType fet = FieldEnumType.valueOf(afs.getType().toUpperCase());
							if(fet == FieldEnumType.FLEX) {
								if(afs.getValueType() == null) {
									/// field will be read in and treated as a string
									logger.warn("Flex value type not provided, so it will be treated as a string");
									fet = FieldEnumType.STRING;
								}
								else {
									/// mark the field to apply the correct type
									flexFields.put(fn, jsarr2.getString(i2));
									continue;
								}
							}
							else if(fet == FieldEnumType.MODEL || fet == FieldEnumType.LIST) {
								logger.error("***** TODO: Handled embedded list/model");
								continue;
							}
							setValueByJSONArray(newRec, jsarr2, i2, afs, fet, fn);
							//logger.info("Digest: " + fs.getBaseModel() + "." + fn + " " + afs.getType());
						}
						flexFields.forEach((k, v) -> {
							FieldSchema afs = schema.getFieldSchema(k);
							String valType = newRec.get(afs.getValueType());
							if(valType != null) {
								FieldEnumType fet = FieldEnumType.valueOf(valType.toUpperCase());
								try {
									logger.info("Apply flexFromString: " + k);
									FieldUtil.setFlexFromString(newRec, fet, k, v);
								} catch (NumberFormatException | ValueException | ModelException | FieldException
										| ModelNotFoundException e) {
									logger.error(e);
								}
							}
							else {
								logger.error("Failed to find valueType: " + afs.getValueType());
							}
						});
						recs.add(newRec);
					}
					else {
						logger.error("**** Unhandled list type: " + fs.getBaseType());
					}
					/*
					long id = jsarr2.getLong(0);
					String name2 = jsarr2.getString(1);
					String value = jsarr2.getString(2);
					logger.info("#" + id + " " + name2 + " = " + value);
					*/
				}
			}
		}
		catch(ModelNotFoundException | FieldException | JSONException | ValueException | ModelException e) {
			logger.error(e);
		}
		arset.close();
	}
	
}
