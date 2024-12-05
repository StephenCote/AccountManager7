package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.apache.commons.dbcp.cpdsadapter.DriverAdapterCPDS;
import org.apache.commons.dbcp.datasources.SharedPoolDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ConnectionEnumType;
import org.cote.accountmanager.util.RecordUtil;

public class DBUtil {
	public static final Logger logger = LogManager.getLogger(DBUtil.class);
	
	/// H2 Base64 Extension from https://github.com/h2database/h2database/issues/2422
	///
	
	private static final String h2_extension = """
DROP SCHEMA IF EXISTS UTL_ENCODE CASCADE;
CREATE SCHEMA UTL_ENCODE;
CREATE ALIAS UTL_ENCODE.BASE64_ENCODE AS $$
byte[] encode(byte[] source) {
    return Base64.getEncoder().encode(source);
}
$$;
CREATE ALIAS UTL_ENCODE.BASE64_ENCODE_STR AS $$
String encodeStr(byte[] source) {
    return Base64.getEncoder().encodeToString(source);
}
$$;
CREATE ALIAS UTL_ENCODE.BASE64_DECODE AS $$
byte[] decode(byte[] source) {
    return Base64.getDecoder().decode(source);
}
$$;""";
	
	private List<String> reservedWords = new ArrayList<>(Arrays.asList("ALL", "AND", "ANY", "ARRAY", "AS", "ASYMMETRIC", "AUTHORIZATION", "BETWEEN", "BOTH", "CASE", "CAST", "CHECK", "CONSTRAINT", "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_PATH", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DAY", "DEFAULT", "DISTINCT", "ELSE", "END", "EXCEPT", "EXISTS", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "FULL", "GROUP", "GROUPS", "HAVING", "HOUR", "IF", "ILIKE", "IN", "INNER", "INTERSECT", "INTERVAL", "IS", "JOIN", "KEY", "LEADING", "LEFT", "LIKE", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "MINUS", "MINUTE", "MONTH", "NATURAL", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "OVER", "PARTITION", "PRIMARY", "QUALIFY", "RANGE", "REGEXP", "RIGHT", "ROW", "ROWNUM", "ROWS", "SECOND", "SELECT", "SESSION_USER", "SET", "SOME", "SYMMETRIC", "SYSTEM_USER", "TABLE", "TO", "TOP", "", "TRAILING", "TRUE", "UESCAPE", "UNION", "UNIQUE", "UNKNOWN", "USER", "USING", "VALUE", "VALUES", "WHEN", "WHERE", "WINDOW", "WITH", "YEAR", "_ROWID_"));
	private String dataPrefix = "A7";
	
	private DataSource dataSource = null;
	private String dataSourceUrl = "jdbc:h2:./am7/h2";
	private String dataSourceUser = "sa";
	private String dataSourcePassword = "1234";
	private String jndiName = null;
	private ConnectionEnumType connectionType = ConnectionEnumType.UNKNOWN;
	
	private String h2Driver = "org.h2.Driver";
	private String pgDriver = "org.postgresql.Driver";
	
	private static DBUtil instance = null;
	
	/// Field index guidance is a holdover from the initial file-based system used while testing the serializer/deserializer core
	/// Use 'hints' at the model level vs. the index boolean at the field level
	///
	private static boolean useFieldIndexGuidance = false;
	
	public static DBUtil getInstance(IOProperties props) {
		if(instance == null) {
			instance = new DBUtil(props);
		}
		return instance;
	}
	
	public DBUtil() {

	}
	
	public void createExtensions() {
		if(connectionType == ConnectionEnumType.H2) {
			logger.info("Creating H2 Extensions");
		    try (Connection con = dataSource.getConnection(); Statement statement = con.createStatement();){
				statement.executeUpdate(h2_extension);
		    }
		    catch(SQLException e) {
		    	logger.error(e);
		    }
		}
	}
	
	/*
	public void release() {
		dataSource = null;
	}
	*/
	public DBUtil(IOProperties props) {
		this(props.getDataSourceUrl(), props.getDataSourceUserName(), props.getDataSourcePassword(), props.getJndiName());
	}
	
	public DBUtil(String url, String user, String pwd, String jndiName) {
		this.dataSourceUrl = url;
		this.dataSourceUser = user;
		this.dataSourcePassword = pwd;
		this.jndiName = jndiName;
		applyDataSource();
	}
	public void setDataSource(DataSource ds) {
		this.dataSource = ds;
	}
	
	public ConnectionEnumType getConnectionType() {
		return connectionType;
	}

	protected void applyDataSource() {
		StatementUtil.setModelMode(true);
		if(dataSourceUrl != null) {
			if(dataSourceUrl.startsWith("jdbc:h2:")) {
				dataSource = getH2DataSource();
				connectionType = ConnectionEnumType.H2;
			}
			else if(dataSourceUrl.startsWith("jdbc:postgresql:")) {
				dataSource = getPGDataSource();
				connectionType = ConnectionEnumType.POSTGRE;
				// StatementUtil.setModelMode(true);
			}
		}
		else if(jndiName != null) {
			String driver = null;
			if(jndiName.endsWith("postgresDS")) {
				driver = pgDriver;
				connectionType = ConnectionEnumType.POSTGRE;
				// StatementUtil.setModelMode(true);
			}
			else if(jndiName.endsWith("h2DS")) {
				driver = h2Driver;
				connectionType = ConnectionEnumType.H2;
			}
			if(driver != null) {
				dataSource = getJNDIDataSource(driver);
			}
		}
	}
	
	public DataSource getJNDIDataSource(String driverClass) {
		DataSource ds = null;

	    String dsFile = "java:/" + jndiName;   
	    
		try {
			InitialContext ctx = new InitialContext();
			ds = (DataSource) ctx.lookup(dsFile);
		}
		catch (NamingException e) {
			logger.error(e);
		}

		if(ds == null){
			logger.error("DataSource is null.  Check that the database server is started and accessible.");
			return null;
		}
		return ds;
	}
	
	public DataSource getDataSource() {
		return dataSource;
	}
	
	private DataSource getDataSource(String driverClass) {
		DataSource ds = null;
		try {
			DriverAdapterCPDS driver = new DriverAdapterCPDS();

			driver.setDriver(driverClass);
			driver.setUrl(dataSourceUrl);
			driver.setUser(dataSourceUser);
			driver.setPassword(dataSourcePassword);
			SharedPoolDataSource sharedPoolDS = new SharedPoolDataSource();
			sharedPoolDS.setConnectionPoolDataSource(driver);
			sharedPoolDS.setMaxIdle(3);
			sharedPoolDS.setMaxActive(30);
			sharedPoolDS.setMaxWait(50000);
			sharedPoolDS.setTestOnBorrow(true);
			sharedPoolDS.setValidationQuery("SELECT 1");
			sharedPoolDS.setTestWhileIdle(true);
			ds = sharedPoolDS;
		} catch (ClassNotFoundException cnfe) {
			logger.error(cnfe);
		}
		
		return ds;
	}

	
	private DataSource getH2DataSource() {
		return getDataSource(h2Driver);
	}
	
	private DataSource getPGDataSource() {
		return getDataSource(pgDriver);
	}
	
	private Map<String, String> sequenceNames = new ConcurrentHashMap<>();
	/*
	private String getSequenceName(String modelName) {
		return getSequenceName(null, modelName);
	}
	*/
	private String getSequenceName(ModelSchema baseSchema, String modelName) {
		String keyName = modelName.replace('.', '_');
		if(modelName.equals(ModelNames.MODEL_PARTICIPATION) && baseSchema != null && baseSchema.isDedicatedParticipation()) {
			keyName = (baseSchema.getName() + "_" + modelName).replace('.', '_');
		}
		if(!sequenceNames.containsKey(keyName)) {
			ModelSchema schema = RecordFactory.getSchema(modelName);
			String ver = schema.getVersion().replace(".", "_");
			List<FieldSchema> fschemas = schema.getFields().stream().filter(o -> o.isSequence()).collect(Collectors.toList());
			if(fschemas.size() > 0) {
				String sequenceName = dataPrefix + "_" + keyName + "_" + ver + "_" + fschemas.get(0).getName() + "_seq";
				sequenceNames.put(keyName, sequenceName);
			}
		}
		return sequenceNames.get(keyName);
	}
	protected long getNextIdForRecord(BaseRecord record) throws DatabaseException {
		ModelSchema schema = null;
		if(record.getModel().equals(ModelNames.MODEL_PARTICIPATION) && record.hasField(FieldNames.FIELD_PARTICIPATION_MODEL)) {
			schema = RecordFactory.getSchema(record.get(FieldNames.FIELD_PARTICIPATION_MODEL));
		}
		return getNextId(schema, record.getModel());
	}
	protected long getNextId(String modelName) throws DatabaseException {
		return getNextId(null, modelName);
	}
	protected long getNextId(ModelSchema baseSchema, String modelName) throws DatabaseException {
		List<Long> ids = getNextIds(baseSchema, modelName, 1);
		if(ids.size() > 0) {
			return ids.get(0);
		}
		return 0L;
	}
	protected List<Long> getNextIds(String modelName, int count) throws DatabaseException {
		return getNextIds(null, modelName, count);
	}
	protected List<Long> getNextIds(ModelSchema baseSchema, String modelName, int count) throws DatabaseException {
		List<Long> ids = new ArrayList<>();
		String sequenceName = getSequenceName(baseSchema, modelName);
		
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
		return getTableName(null, modelName);
	}

	public String getTableNameByRecord(BaseRecord record, String modelName) {
		ModelSchema schema = null;
		if(record != null && record.getModel().equals(ModelNames.MODEL_PARTICIPATION) && record.hasField(FieldNames.FIELD_PARTICIPATION_MODEL)) {
			String ppType = record.get(FieldNames.FIELD_PARTICIPATION_MODEL);
			if(ppType != null) {
				schema = RecordFactory.getSchema(ppType);
			}
		}
		return getTableName(schema, modelName);
	}
	
	public String getTableName(ModelSchema schema, String modelName) {
		ModelSchema ms = RecordFactory.getSchema(modelName);
		String ver = ms.getVersion().replace(".", "_");
		String useName = modelName.replace('.', '_');
		if(ModelNames.MODEL_PARTICIPATION.equals(modelName) && schema != null && schema.isDedicatedParticipation()) {
			useName = schema.getName().replace('.', '_') + "_" + useName;
		}
		return dataPrefix + "_" + useName + "_" + ver;
	}
	public boolean dropSchema(ModelSchema schema) {
		String dropSql = generateDropSchema(schema);
		execute(dropSql);
		CacheUtil.clearCache();
		return true;
	}
	public String generateDropSchema(ModelSchema schema) {
		StringBuilder buff = new StringBuilder();
		String tableName = getTableName(schema.getName());
		buff.append("DROP TABLE IF EXISTS " + tableName + " CASCADE;\n");
		if(schema.isDedicatedParticipation()) {
			String ptableName = getTableName(schema, ModelNames.MODEL_PARTICIPATION);
			buff.append("DROP TABLE IF EXISTS " + ptableName + " CASCADE;\n");
		}
		buff.append("DELETE FROM " + getTableName(ModelNames.MODEL_MODEL_SCHEMA) + " WHERE name = '" + schema.getName() + "';\n");
		return buff.toString();
	}
	
	public String generateNewSchemaOnly(ModelSchema schema) {
		return generateNewSchemaOnly(null, schema);
	}
	public String generateNewSchemaOnly(ModelSchema baseSchema, ModelSchema schema) {
		if(schema.isEphemeral()) {
			logger.warn("Schema " + schema.getName() + " is ephemeral");
			return null;
		}
		if(isConstrained(schema)) {
			logger.warn("Schema " + schema.getName() + " is constrained from using a database schema");
			return null;
		}
		if(haveTable(baseSchema, schema.getName())) {
			logger.warn("Schema " + schema.getName() + " already exists");
			return null;
		}
		return generateSchema(baseSchema, schema);
	}
	public String generateSchema(ModelSchema schema) {
		return generateSchema(null, schema);
	}
	public String generateSchema(ModelSchema baseSchema, ModelSchema schema) {
		StringBuilder buff = new StringBuilder();
		FieldSchema primary = null;
		List<FieldSchema> idents = new ArrayList<>();
		List<FieldSchema> flds = new ArrayList<>();
		
		if(schema.isEphemeral()) {
			logger.warn("Schema " + schema.getName() + " is ephemeral");
			return null;
		}
		
		
		/*
		String ver = schema.getVersion().replace(".", "_");
		
		String seqPref = "";
		if(schema.getName().equals(ModelNames.MODEL_PARTICIPATION) && baseSchema != null && baseSchema.isDedicatedParticipation()) {
			seqPref = baseSchema.getName() + "_";
		}
		*/
		String tableName = getTableName(baseSchema, schema.getName());
		buff.append("DROP TABLE IF EXISTS " + tableName + " CASCADE;\n");
		RecordUtil.sortFields(schema);
		
		for(FieldSchema f : schema.getFields()) {
			if(f.isVirtual() || f.isEphemeral()) {
				continue;
			}
			if(f.isSequence()) {
				String sequenceName = getSequenceName(baseSchema, schema.getName());
				// String sequenceName = dataPrefix + "_" + seqPref + schema.getName() + "_" + ver + "_" + f.getName() + "_seq";
				buff.append("DROP SEQUENCE IF EXISTS " + sequenceName + ";\n");
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

		buff.append("CREATE TABLE " + tableName + "(\n");
		// buff.append("CREATE OR REPLACE TABLE " + tableName + "(\n");
		List<String> schemaLines = new ArrayList<>();
		
		if(primary != null) {
			String line = generateSchemaLine(baseSchema, schema, primary);
			if(line != null) {
				schemaLines.add(line);
			}
				
		}
		for(FieldSchema f : idents) {
			String line = generateSchemaLine(baseSchema, schema, f);
			if(line != null) {
				schemaLines.add(line);
			}
		}

		for(FieldSchema f : flds) {
			String line = generateSchemaLine(baseSchema, schema, f);
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

		buff.append(generateIndices(baseSchema, schema));
		
		if(schema.isDedicatedParticipation()) {
			buff.append(generateSchema(schema, RecordFactory.getSchema(ModelNames.MODEL_PARTICIPATION)));
		}
		
		// logger.info(buff.toString());
		
		return buff.toString();
	}
	/*
	private String generateIndex(ModelSchema schema, String cols, boolean unique) {
		return generateIndex(null, schema, cols, unique);
	}
	*/
	private String generateIndex(ModelSchema baseSchema, ModelSchema schema, String cols, boolean unique, int idxCounter) {
		String tableName = getTableName(baseSchema, schema.getName());
		
		List<String> coll = Arrays.asList(cols.replaceAll(" ",  "").split(","));

		List<String> col2 = new ArrayList<>();
		List<String> col3 = new ArrayList<>();
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
					( 
						(fet != FieldEnumType.ENUM && fet != FieldEnumType.STRING)
						|| fs.getMaxLength() == 0
					)
					&& fet != FieldEnumType.INT
					&& fet != FieldEnumType.LONG
					&& fet != FieldEnumType.TIMESTAMP
					&& fet != FieldEnumType.ZONETIME
					&& fet != FieldEnumType.DOUBLE
					&& fet != FieldEnumType.BOOLEAN
				) {
					logger.warn("Model '" + schema.getName() + "' field '" + s + "' cannot be indexed in the database");
					notIndexable = true;
				}
			}
			if(notIndexable) {
				break;
			}
			col2.add(getColumnName(fs.getName()));
			col3.add(fs.getName().substring(0,1) + fs.getName().substring(fs.getName().length()-1));
		}
		if(notIndexable) {
			return null;
		}

		// String cname = col2.stream().collect(Collectors.joining("_"));
		String cname = col3.stream().collect(Collectors.joining("_")) + "_" + idxCounter;
		String cols2 = col2.stream().collect(Collectors.joining(","));

		String ver = schema.getVersion().replace(".", "_");
		String schemaPref = "";
		if(baseSchema != null && baseSchema.isDedicatedParticipation() && schema.getName().equals(ModelNames.MODEL_PARTICIPATION)) {
			schemaPref = baseSchema.getName().replace('.', '_') + "_";
		}
		String idxName = dataPrefix + "_" + schemaPref + schema.getName().replace('.', '_') + "_" + ver + "_" + cname.replaceAll("\"", "") + "_idx on " + tableName + "(" + cols2 + ")";
		return "CREATE" + (unique ? " UNIQUE" : "") + " INDEX " + idxName + ";";
	}
	/*
	private String generateIndices(ModelSchema schema) {
		return generateIndices(null, schema);
	}
	*/
	private String generateIndices(ModelSchema baseSchema, ModelSchema schema) {
		
		StringBuilder buff = new StringBuilder();
		Set<String> idxSet = new HashSet<>();
		int idxCounter = 1;
		/// Note: The original reason of marking certain fields to be indexed was primarily for the initial file-based index system
		/// However, for a database, it makes more sense to use the hints and constraints
		if(useFieldIndexGuidance) {
			for(FieldSchema f : schema.getFields()) {
				if(!f.isIndex()) {
					continue;
				}
				if(idxSet.contains(f.getName())) {
					logger.warn(schema.getName() + " indexible field duplication: (" + f.getName() + ")");
					continue;
				}
				String idx = generateIndex(baseSchema, schema, f.getName(), f.isIdentity(), idxCounter++);
				if(idx != null) {
					idxSet.add(f.getName());
					buff.append(idx + "\n");
				}
				
			}
		}
		List<String> constraints = RecordUtil.getConstraints(schema);
		for(String ic : constraints) {
			if(idxSet.contains(ic)) {
				logger.error(schema.getName() + " Index collision: (" + ic + ")");
				continue;
			}
			String idx = generateIndex(baseSchema, schema, ic, true, idxCounter++);
			if(idx != null) {
				idxSet.add(ic);
				buff.append(idx + "\n");
			}
		}

		List<String> hints = RecordUtil.getHints(schema);
		for(String ic : hints) {
			if(idxSet.contains(ic)) {
				logger.error("Index collision: (" + ic + ")");
				continue;
			}
			String idx = generateIndex(baseSchema, schema, ic, false, idxCounter++);
			if(idx != null) {
				idxSet.add(ic);
				buff.append(idx + "\n");
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
			case ZONETIME:
				//outType = "timestamp with timezone";
				//break;
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
			case FLEX:
				outType = "text";
				break;
			case MODEL:
				if(!schema.isForeign()) {
					logger.info("Linked model " + schema.getName() + " will be persisted as a JSON string");
					// logger.info(JSONUtil.exportObject(schema));
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
	protected String generateSchemaLine(ModelSchema baseSchema, ModelSchema schema, FieldSchema fschema) {
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
		/// || fet == FieldEnumType.STRING
		if(!fschema.isIdentity() && (fschema.isAllowNull() || fet == FieldEnumType.BLOB)) {
			allowNull = true;
		}
		String defStr = null;
		String colName = getColumnName(fschema.getName());
		if(fschema.isSequence()) {
			/// TODO - fix this typo
			defStr = "nextval('" + getSequenceName(baseSchema, schema.getName()) + "')";
		}
		else if(fet == FieldEnumType.INT || fet == FieldEnumType.DOUBLE || fet == FieldEnumType.LONG) {
			defStr = "0";
		}
		else if(fet == FieldEnumType.BOOLEAN) {
			defStr = "false";
		}
		else if(fet == FieldEnumType.ZONETIME || fet == FieldEnumType.TIMESTAMP) {
			defStr = "now()";
		}
		buff.append(colName + " " + dataType + (allowNull ? "" : " not null") + (defStr != null ? " default " + defStr : ""));
		
		return buff.toString();
		
	}
	
	public boolean testConnection() {
		boolean tested = false;
		try (Connection con = dataSource.getConnection();){
			tested = true;
		}
		catch (SQLException e) {
			logger.error(e);
		}
		return tested;
	}
	
	public boolean haveTable(String modelName) {
		return haveTable(null, modelName);
	}
	public boolean haveTable(ModelSchema schema, String modelName) {
    	int count = 0;
    	String useName = getTableName(schema, modelName);
    	
    	if(this.connectionType == ConnectionEnumType.H2) {
    		useName = useName.toUpperCase();
    	}
    	else if(this.connectionType == ConnectionEnumType.POSTGRE) {
    		useName = useName.toLowerCase();
    	}
	    
    	try (
    			Connection con = dataSource.getConnection();
    			PreparedStatement st = con.prepareStatement("select count(*) from information_schema.tables where table_name = ?;");
    	){
	    	//logger.info("***** Check Table: " + useName);
	    	st.setString(1, useName);
	    	ResultSet rset = st.executeQuery();

	    	if(rset.next()) {
	    		count = rset.getInt(1);
	    	}
	    	rset.close();
		} catch (SQLException e) {
           logger.error(e);
	    }
	    return (count > 0);
	}
	
	public boolean execute(String sql) {
		boolean exec = false;
		try (
			Connection con = dataSource.getConnection();
			Statement statement = con.createStatement();
		){
			exec = statement.execute(sql);
		} catch (SQLException e) {
			logger.error(e);
		}
		return exec;
	}
	
	public List<String> getTables() {
		logger.info("Print tables");
		List<String> tables = new ArrayList<>();
	    try (
	    	Connection con = dataSource.getConnection();
	    	Statement st = con.createStatement();
	    ){
	    	ResultSet rset = st.executeQuery("select * from information_schema.tables;");
	    	while(rset.next()) {
	    		//logger.info(rset.getString("table_name"));
	    		tables.add(rset.getString("table_name"));
	    	}
	    	rset.close();
		} catch (SQLException e) {
			logger.error(e);
	    }
	    return tables;

	}
	
}
