package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.dbcp.cpdsadapter.DriverAdapterCPDS;
import org.apache.commons.dbcp.datasources.SharedPoolDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.util.JSONUtil;

public class DBUtil {
	public static final Logger logger = LogManager.getLogger(DBUtil.class);
	
	private List<String> reservedWords = new ArrayList<>(Arrays.asList("ALL", "AND", "ANY", "ARRAY", "AS", "ASYMMETRIC", "AUTHORIZATION", "BETWEEN", "BOTH", "CASE", "CAST", "CHECK", "CONSTRAINT", "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_PATH", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DAY", "DEFAULT", "DISTINCT", "ELSE", "END", "EXCEPT", "EXISTS", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "FULL", "GROUP", "GROUPS", "HAVING", "HOUR", "IF", "ILIKE", "IN", "INNER", "INTERSECT", "INTERVAL", "IS", "JOIN", "KEY", "LEADING", "LEFT", "LIKE", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "MINUS", "MINUTE", "MONTH", "NATURAL", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "OVER", "PARTITION", "PRIMARY", "QUALIFY", "RANGE", "REGEXP", "RIGHT", "ROW", "ROWNUM", "ROWS", "SECOND", "SELECT", "SESSION_USER", "SET", "SOME", "SYMMETRIC", "SYSTEM_USER", "TABLE", "TO", "TOP", "", "TRAILING", "TRUE", "UESCAPE", "UNION", "UNIQUE", "UNKNOWN", "USER", "USING", "VALUE", "VALUES", "WHEN", "WHERE", "WINDOW", "WITH", "YEAR", "_ROWID_"));
	private String dataPrefix = "A7";
	
	private DataSource dataSource = null;
	private String dataSourceUrl = "jdbc:h2:./am7/h2";
	private String dataSourceUser = "sa";
	private String dataSourcePassword = "1234";
	private ConnectionEnumType connectionType = ConnectionEnumType.UNKNOWN;
	public DBUtil() {
		
	}
	
	public DBUtil(IOProperties props) {
		this(props.getDataSourceUrl(), props.getDataSourceUserName(), props.getDataSourcePassword());
	}
	
	public DBUtil(String url, String user, String pwd) {
		this.dataSourceUrl = url;
		this.dataSourceUser = user;
		this.dataSourcePassword = pwd;
		applyDataSource();
	}
	public void setDataSource(DataSource ds) {
		this.dataSource = ds;
	}
	
	public ConnectionEnumType getConnectionType() {
		return connectionType;
	}

	protected void applyDataSource() {
		if(dataSourceUrl != null && dataSourceUrl.startsWith("jdbc:h2:")) {
			dataSource = getH2DataSource();
			connectionType = ConnectionEnumType.H2;
		}
	}
	public DataSource getDataSource() {
		return dataSource;
	}
	private DataSource getH2DataSource() {
		DataSource ds = null;
		try {
			DriverAdapterCPDS driver = new DriverAdapterCPDS();

			driver.setDriver("org.h2.Driver");
			driver.setUrl(dataSourceUrl);
			driver.setUser(dataSourceUser);
			driver.setPassword(dataSourcePassword);

			SharedPoolDataSource sharedPoolDS = new SharedPoolDataSource();
			sharedPoolDS.setConnectionPoolDataSource(driver);
			sharedPoolDS.setMaxActive(10);
			sharedPoolDS.setMaxWait(50);
			sharedPoolDS.setTestOnBorrow(true);
			sharedPoolDS.setValidationQuery("SELECT 1");
			sharedPoolDS.setTestWhileIdle(true);
			ds = sharedPoolDS;
		} catch (ClassNotFoundException cnfe) {
			logger.error(cnfe);
		}
		
		return ds;
	}
	
	private Map<String, String> sequenceNames = new HashMap<>();
	private String getSequenceName(String modelName) {
		if(!sequenceNames.containsKey(modelName)) {
			ModelSchema schema = RecordFactory.getSchema(modelName);
			List<FieldSchema> fschemas = schema.getFields().stream().filter(o -> o.isSequence()).collect(Collectors.toList());
			if(fschemas.size() > 0) {
				String sequenceName = dataPrefix + "_" + modelName + "_" + fschemas.get(0).getName() + "_seq";
				sequenceNames.put(modelName, sequenceName);
			}
		}
		return sequenceNames.get(modelName);
	}
	protected long getNextId(String modelName) throws DatabaseException {
		List<Long> ids = getNextIds(modelName, 1);
		if(ids.size() > 0) {
			return ids.get(0);
		}
		return 0L;
	}
	protected List<Long> getNextIds(String modelName, int count) throws DatabaseException {
		List<Long> ids = new ArrayList<>();
		String sequenceName = getSequenceName(modelName);
		
		if(sequenceName == null || sequenceName.length() == 0) {
			throw new DatabaseException("Sequence name for " + modelName + " is null");
		}
		String query = String.format("SELECT nextval('%s') FROM generate_series(1,%s)", sequenceName, count);
	    try (Connection con = dataSource.getConnection()){
			ResultSet rset = null;
			try(Statement statement = con.createStatement()){
				rset = statement.executeQuery(query);
				while(rset.next()){
					ids.add(rset.getLong(1));
				}
				rset.close();
			}

		} catch (SQLException e) {
			logger.error(e);
			throw new DatabaseException(e);
		}
		return ids;
	}
	
	public boolean isConstrained(ModelSchema schema) {
		List<String> constrained = schema.getIoConstraints().stream().filter(o -> o.toUpperCase().equals(RecordIO.DATABASE.toString())).collect(Collectors.toList());
		return (schema.getIoConstraints().size() > 0 && constrained.size() == 0);
	}
	
	public String getTableName(String modelName) {
		return dataPrefix + "_" + modelName;
	}
	public String generateNewSchemaOnly(ModelSchema schema) {
		if(isConstrained(schema)) {
			logger.warn("Schema " + schema.getName() + " is constrained from using a database schema");
			return null;
		}
		if(haveTable(schema.getName())) {
			logger.warn("Schema " + schema.getName() + " already exists");
			return null;
		}
		return generateSchema(schema);
	}
	public String generateSchema(ModelSchema schema) {
		StringBuilder buff = new StringBuilder();
		FieldSchema primary = null;
		List<FieldSchema> idents = new ArrayList<>();
		List<FieldSchema> flds = new ArrayList<>();

		for(FieldSchema f : schema.getFields()) {
			if(f.isVirtual() || f.isEphemeral()) {
				continue;
			}
			if(f.isSequence()) {
				String sequenceName = dataPrefix + "_" + schema.getName() + "_" + f.getName() + "_seq";
				buff.append("CREATE SEQUENCE " + sequenceName + ";\n");
			}
			if(f.isPrimaryKey()) {
				primary = f;
			}

			else if(f.isIdentity()) {
				idents.add(f);
			}
			else {
				flds.add(f);
			}
		};
		if(primary == null && idents.size() == 0) {
			logger.warn(schema.getName() + " does not define an identity.  Skipping");
			return null;
		}
		String tableName = getTableName(schema.getName());
		buff.append("CREATE OR REPLACE TABLE " + tableName + "(\n");
		List<String> schemaLines = new ArrayList<>();
		
		if(primary != null) {
			String line = generateSchemaLine(schema, primary);
			if(line != null) {
				schemaLines.add(line);
			}
				
		}
		for(FieldSchema f : idents) {
			String line = generateSchemaLine(schema, f);
			if(line != null) {
				schemaLines.add(line);
			}

		}
		for(FieldSchema f : flds) {
			String line = generateSchemaLine(schema, f);
			if(line != null) {
				schemaLines.add(line);
			}
		}

		if(primary != null) {
			schemaLines.add("primary key(" + primary.getName() + ")");
		}
		String schemaBlock = schemaLines.stream().collect(Collectors.joining(",\n"));
		buff.append(schemaBlock + "\n");
		buff.append(");\n");

		buff.append(generateIndices(schema));
	
		return buff.toString();
	}
	private String generateIndex(ModelSchema schema, String cols, boolean unique) {
		String tableName = getTableName(schema.getName());
		
		List<String> coll = Arrays.asList(cols.replaceAll(" ",  "").split(","));
		List<String> col2 = new ArrayList<>();
		boolean notIndexable = false;
		for(String s : coll) {
			FieldSchema fs = schema.getFieldSchema(s);
			if(fs == null) {
				logger.error("Column does not exist: '" + s + "'");
				notIndexable = true;
			}
			else {
				FieldEnumType fet = fs.getFieldType();
				if(
					(( (fet != FieldEnumType.ENUM && fet != FieldEnumType.STRING) || fs.getMaxLength() == 0) && fet != FieldEnumType.INT && fet != FieldEnumType.LONG && fet != FieldEnumType.TIMESTAMP && fet != FieldEnumType.DOUBLE && fet != FieldEnumType.BOOLEAN)
				) {
					logger.warn("Model '" + schema.getName() + "' field '" + s + "' cannot be indexed in the database");
					notIndexable = true;
				}
			}
			if(notIndexable) {
				break;
			}
			col2.add(getColumnName(fs.getName()));
			
		}
		if(notIndexable) {
			return null;
		}

		String cname = col2.stream().collect(Collectors.joining("_"));
		String cols2 = col2.stream().collect(Collectors.joining(","));
		String idxName = dataPrefix + "_" + schema.getName() + "_" + cname.replaceAll("\"", "") + "_idx on " + tableName + "(" + cols2 + ")";
		return "CREATE" + (unique ? " UNIQUE" : "") + " INDEX " + idxName + ";";
	}
	
	private String generateIndices(ModelSchema schema) {
		
		StringBuilder buff = new StringBuilder();
		Set<String> idxSet = new HashSet<>();
		for(FieldSchema f : schema.getFields()) {
			if(!f.isIndex()) {
				continue;
			}
			if(idxSet.contains(f.getName())) {
				logger.error("Index collision: (" + f.getName() + ")");
				continue;
			}
			String idx = generateIndex(schema, f.getName(), f.isIdentity());
			if(idx != null) {
				idxSet.add(f.getName());
				buff.append(idx + "\n");
			}
			
		}
		for(String is : schema.getInherits()) {
			ModelSchema ischema = RecordFactory.getSchema(is);
			for(String ic : ischema.getConstraints()) {
				if(idxSet.contains(ic)) {
					logger.error("Index collision: (" + ic + ")");
					continue;
				}
				String idx = generateIndex(schema, ic, true);
				if(idx != null) {
					idxSet.add(ic);
					buff.append(idx + "\n");
				}
			}
			for(String ic : ischema.getHints()) {
				if(idxSet.contains(ic)) {
					logger.error("Index collision: (" + ic + ")");
					continue;
				}
				String idx = generateIndex(schema, ic, false);
				if(idx != null) {
					idxSet.add(ic);
					buff.append(idx + "\n");
				}

			}
		}
		return buff.toString();
	}
	
	
	public String getDataType(FieldSchema schema, FieldEnumType fet) {
		String outType = null;
		String baseModel = schema.getBaseModel();
		switch(fet) {
			case BLOB:
				outType = "bytea";
				break;
			case BOOLEAN:
				outType = "boolean";
				break;
			case TIMESTAMP:
				outType = "timestamp";
				break;
			case DOUBLE:
				outType = "double precision";
				break;
			case ENUM:
				if(schema.getMaxLength() > 0) {
					outType = "varchar(" + schema.getMaxLength() + ")";
				}
				else {
					logger.warn("Enum " + schema.getName() + " should define a maxLength");
					outType = "varchar";
				}
				break;
			case STRING:
				if(schema.getMaxLength() > 0) {
					outType = "varchar(" + schema.getMaxLength() + ")";
				}
				else {
					outType = "text";
				}
				break;
			case FLEX:
				outType = "text";
				break;
			case INT:
				outType = "int";
				break;
			case LIST:
				
				if(baseModel != null && schema.isReferenced()) {
					ModelSchema fmschema = RecordFactory.getSchema(baseModel);
					if(fmschema.getInherits().contains(ModelNames.MODEL_REFERENCE)) {
						logger.error("Model " + schema.getName() + " list will be persisted via external reference - should not fall into this statement");
						outType = null;
					}
				}
				else if(schema.isForeign()){
					logger.debug("Unreferenced list '" + schema.getName() + "' will be handled as participations");
					outType = null;
				}
				else {
					logger.debug("List '" + schema.getName() + "' will be handled as serialized text");
					outType = "text";
				}
				
				break;
			case LONG:
				outType = "bigint";
				break;
			case MODEL:
				if(!schema.isForeign()) {
					logger.info("Model " + schema.getName() + " will be persisted as a JSON string");
					logger.info(JSONUtil.exportObject(schema));
					outType = "text";
				}
				else {
					outType = "bigint";
					String foreignField = schema.getForeignField();
					if(foreignField == null) {
						foreignField = FieldNames.FIELD_ID;
					}
					if(schema.getBaseModel() != null) {
						if(!schema.getBaseModel().equals(ModelNames.MODEL_FLEX)) {
							ModelSchema fmschema = RecordFactory.getSchema(schema.getBaseModel());
							if(fmschema != null) {
								FieldSchema fschema = fmschema.getFieldSchema(foreignField);
								if(fschema != null) {
									FieldEnumType ffet = fschema.getFieldType();
									outType = getDataType(fschema, ffet);
								}
								else {
									logger.warn("Failed to load field " + schema.getName() + " -> " + fmschema.getName() + "." + foreignField);
								}
							}
							else {
								logger.warn("Failed to load model " + schema.getName() + " -> " + schema.getBaseModel());
							}
						}
						else if(baseModel.equals(ModelNames.MODEL_FLEX) && schema.getForeignType() != null) {
							logger.debug("Flexible foreign field " + schema.getName() + " -> " + outType);
						}
						else {
							logger.error("Flex model " + schema.getName() + " cannot use default without a foreignType because no global search without a type reference is available");
							
						}
					}
					else {
						logger.warn("Base model is not defined for " + schema.getName());
					}
				}
				break;
			default:
				logger.error("Unhandled: " + fet.toString());
		}
		return outType;
	}
	
	public String getColumnName(String name) {
		String outName = name;
		if(reservedWords.contains(outName.toUpperCase())) {
			outName = "\"" + name + "\"";
		}
		return outName;
	}
	protected String generateSchemaLine(ModelSchema schema, FieldSchema fschema) {
		StringBuilder buff = new StringBuilder();
		boolean allowNull = false;
		if(fschema.getType() == null) {
			logger.error("No type defined: " + fschema.getName());
			return null;
		}
		if(fschema.isReferenced()) {
			return null;
		}
		FieldEnumType fet = FieldEnumType.valueOf(fschema.getType().toUpperCase());
		String dataType = getDataType(fschema, fet);
		if(dataType == null) {
			return null;
		}
		if(!fschema.isIdentity() && (fschema.isAllowNull() || fet == FieldEnumType.BLOB || fet == FieldEnumType.STRING)) {
			allowNull = true;
		}
		String defStr = null;
		String colName = getColumnName(fschema.getName());
		if(fschema.isSequence()) {
			/// TODO - fix this typo
			defStr = "nextval('" + getSequenceName(schema.getName()) + "_seq')";
		}
		else if(fet == FieldEnumType.INT || fet == FieldEnumType.DOUBLE || fet == FieldEnumType.LONG) {
			defStr = "0";
		}
		else if(fet == FieldEnumType.BOOLEAN) {
			defStr = "false";
		}
		else if(fet == FieldEnumType.TIMESTAMP) {
			defStr = "now()";
		}
		buff.append(colName + " " + dataType + (allowNull ? "" : " not null") + (defStr != null ? " default " + defStr : ""));
		
		return buff.toString();
		
	}
	
	public boolean haveTable(String table) {
    	int count = 0;
	    try (Connection con = dataSource.getConnection()){
	    	try(PreparedStatement st = con.prepareStatement("select count(*) from information_schema.tables where table_name = ?;")){
		    	st.setString(1, getTableName(table).toUpperCase());
		    	ResultSet rset = st.executeQuery();
	
		    	if(rset.next()) {
		    		count = rset.getInt(1);
		    	}
		    	rset.close();
	    	}
		} catch (SQLException e) {
           logger.error(e);
	    }
	    return (count > 0);
	}
	
	public boolean execute(String sql) {
		boolean exec = false;
		try (Connection con = dataSource.getConnection()){
			try(Statement statement = con.createStatement()){
				exec = statement.execute(sql);
			}

		} catch (SQLException e) {
			logger.error(e);
		}
		return exec;
	}
	
	public List<String> getTables() {
		logger.info("Print tables");
		List<String> tables = new ArrayList<>();
	    try (Connection con = dataSource.getConnection()){
	    	try(Statement st = con.createStatement()){
		    	ResultSet rset = st.executeQuery("select * from information_schema.tables;");
		    	while(rset.next()) {
		    		//logger.info(rset.getString("table_name"));
		    		tables.add(rset.getString("table_name"));
		    	}
		    	rset.close();
	    	}
		} catch (SQLException e) {
			logger.error(e);
	    }
	    return tables;

	}
	
}
