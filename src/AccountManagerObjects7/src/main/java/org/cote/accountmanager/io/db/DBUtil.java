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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	
	private boolean enableVectorExtension = false;
	private static final String pg_vector_extension = """
CREATE EXTENSION if not exists vector;		
""";
	private static final String pg_extension = """
""";

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
	/// PostgreSQL NAMEDATALEN-1: identifiers longer than this are truncated, not rejected
	private static final int MAX_PG_IDENTIFIER_LENGTH = 63;
	/// Matches the leading clause of an index statement emitted by generateIndex, capturing the index name
	private static final Pattern indexNamePattern = Pattern.compile("^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+(\\S+)\\s", Pattern.CASE_INSENSITIVE);
	
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
	
	public void vacuum() {
		try (Connection con = dataSource.getConnection();
			   	Statement st = con.createStatement();
		){
			if(connectionType == ConnectionEnumType.POSTGRE) {
				logger.info("Vacuuming ...");
				st.execute("vacuum(full, analyze, verbose);");
				logger.info("... Vacuumed");
			}
		}
		catch (SQLException e) {
			logger.error(e);
	    }
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
		if(connectionType == ConnectionEnumType.POSTGRE) {
			enableVectorExtension = true;
		}
	}


	public boolean isEnableVectorExtension() {
		return enableVectorExtension;
	}

	public void setEnableVectorExtension(boolean enableVectorExtension) {
		this.enableVectorExtension = enableVectorExtension;
	}

	public void createExtensions() {
		String ext = null;
		if(connectionType == ConnectionEnumType.H2) {
			logger.info("Creating H2 Extensions");
			ext = h2_extension;
		}
		else if(connectionType == ConnectionEnumType.POSTGRE) {
			logger.info("Creating PG Extensions");
			ext = pg_extension + (enableVectorExtension ? pg_vector_extension : "");
		}
		if(ext != null && ext.trim().length() > 0) {
		    try (Connection con = dataSource.getConnection(); Statement statement = con.createStatement();){
				statement.executeUpdate(ext);
		    }
		    catch(SQLException e) {
		    	logger.error(e);
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
		if(record.getSchema().equals(ModelNames.MODEL_PARTICIPATION) && record.hasField(FieldNames.FIELD_PARTICIPATION_MODEL)) {
			schema = RecordFactory.getSchema(record.get(FieldNames.FIELD_PARTICIPATION_MODEL));
		}
		return getNextId(schema, record.getSchema());
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
		if(record != null && record.getSchema().equals(ModelNames.MODEL_PARTICIPATION) && record.hasField(FieldNames.FIELD_PARTICIPATION_MODEL)) {
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
				logger.error(schema.getName() + " Column does not exist: '" + s + "'");
				notIndexable = true;
			}
			else {
				/// Indexability is a property of the column the generator actually emits, not of the
				/// model-level FieldEnumType.  A foreign 'model' field is emitted as a plain scalar
				/// (bigint, or whatever the referenced foreign field resolves to) and indexes fine,
				/// while a field that emits no column at all - or emits an unbounded text/binary/vector
				/// column - does not.
				String reason = getIndexRejectionReason(fs);
				if(reason != null) {
					logger.warn("Model '" + schema.getName() + "' field '" + s + "' cannot be indexed in the database: " + reason);
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
		/// IF NOT EXISTS keeps the statement replayable: the same DDL is now emitted both at CREATE TABLE
		/// time and on the schema-patch path, and both PostgreSQL (>= 9.5) and H2 support the clause.
		return "CREATE" + (unique ? " UNIQUE" : "") + " INDEX IF NOT EXISTS " + idxName + ";";
	}

	/// Returns null when the field can be indexed, otherwise a human-readable reason why it cannot.
	/// Keyed off the emitted SQL column type rather than the FieldEnumType so that foreign 'model'
	/// fields (persisted as bigint) are indexable, while unbounded text, binary and vector columns -
	/// and fields that produce no column at all - are not.
	///
	private String getIndexRejectionReason(FieldSchema fs) {
		if(fs.getType() == null) {
			return "no type is defined";
		}
		if(fs.isVirtual() || fs.isEphemeral()) {
			return "field is not persisted (virtual or ephemeral)";
		}
		if(fs.isReferenced()) {
			return "field is persisted through a separate reference table";
		}
		String dataType = getDataType(fs, fs.getFieldType());
		if(dataType == null || dataType.trim().length() == 0) {
			return "field does not emit a database column (" + fs.getType() + ")";
		}
		String dt = dataType.trim().toLowerCase();
		if(dt.equals("text")) {
			return "unbounded text column";
		}
		if(dt.equals("varchar")) {
			return "unbounded varchar column - define a maxLength";
		}
		if(dt.equals("bytea") || dt.equals("blob")) {
			return "binary column";
		}
		if(dt.startsWith("vector")) {
			return "vector column";
		}
		return null;
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
				logger.error(schema.getName() + " Index collision: (" + ic + ")");
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
					// logger.info("Linked model " + schema.getName() + " will be persisted as a JSON string");
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
			case VECTOR:
				if(connectionType != ConnectionEnumType.POSTGRE) {
					logger.warn("Vector type is not supported by this database");
				}
				if(!enableVectorExtension) {
					logger.warn("Vector extension is not enabled");
				}
				outType = "vector" + (schema.getMaxLength() > 0 ? "(" + schema.getMaxLength() + ")" : "");
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
	public String generateSchemaLine(ModelSchema baseSchema, ModelSchema schema, FieldSchema fschema) {
		StringBuilder buff = new StringBuilder();
		boolean allowNull = false;
		if(fschema.getType() == null) {
			logger.error("No type defined: " + fschema.getName());
			return null;
		}
		if(fschema.isReferenced()) {
			return null;
		}
		FieldEnumType fet = FieldEnumType.valueOf(fschema.getType().intern().toUpperCase());
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

	/// Get existing column names for a table from information_schema
	///
	public List<String> getTableColumns(String tableName) {
		List<String> columns = new ArrayList<>();
		String useName = tableName;
		if(this.connectionType == ConnectionEnumType.H2) {
			useName = useName.toUpperCase();
		}
		else if(this.connectionType == ConnectionEnumType.POSTGRE) {
			useName = useName.toLowerCase();
		}
		try (
			Connection con = dataSource.getConnection();
			PreparedStatement st = con.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_name = ?;");
		){
			st.setString(1, useName);
			ResultSet rset = st.executeQuery();
			while(rset.next()) {
				columns.add(rset.getString("column_name").toLowerCase());
			}
			rset.close();
		} catch (SQLException e) {
			logger.error(e);
		}
		return columns;
	}

	/// Compare model schema fields against existing database columns.
	/// Returns list of FieldSchema objects that exist in the model but not in the database.
	///
	public List<FieldSchema> getMissingColumns(ModelSchema schema) {
		return getMissingColumns(null, schema);
	}

	/// As getMissingColumns(ModelSchema), but resolves the table the way generateSchema does: when
	/// baseSchema is a model declaring dedicatedParticipation and schema is system.participation, the
	/// table compared against is that model's dedicated participation table rather than the shared one.
	///
	public List<FieldSchema> getMissingColumns(ModelSchema baseSchema, ModelSchema schema) {
		String tableName = getTableName(baseSchema, schema.getName());
		if(!haveTable(baseSchema, schema.getName())) {
			return new ArrayList<>();
		}

		List<String> existingColumns = getTableColumns(tableName);
		List<FieldSchema> missing = new ArrayList<>();

		for(FieldSchema field : schema.getFields()) {
			if(field.isVirtual() || field.isEphemeral() || field.isReferenced()) {
				continue;
			}
			String dataType = getDataType(field, field.getFieldType());
			if(dataType == null) {
				continue;
			}
			String colName = getColumnName(field.getName()).replace("\"", "").toLowerCase();
			if(!existingColumns.contains(colName)) {
				missing.add(field);
			}
		}
		return missing;
	}

	/// Generate ALTER TABLE ADD COLUMN statements for missing fields.
	/// Returns empty list if table doesn't exist or no columns are missing.
	/// A model declaring dedicatedParticipation also has its dedicated participation table checked,
	/// since that table is otherwise only ever emitted on the CREATE TABLE path.  Column drops are
	/// deliberately NOT extended to participation tables - see generateDropColumnSchema.
	///
	public List<String> generatePatchSchema(ModelSchema schema) {
		List<String> statements = new ArrayList<>();
		if(schema == null) {
			return statements;
		}
		addPatchColumns(statements, null, schema);
		if(schema.isDedicatedParticipation()) {
			addPatchColumns(statements, schema, RecordFactory.getSchema(ModelNames.MODEL_PARTICIPATION));
		}
		return statements;
	}

	private void addPatchColumns(List<String> statements, ModelSchema baseSchema, ModelSchema schema) {
		List<FieldSchema> missing = getMissingColumns(baseSchema, schema);
		if(missing.isEmpty()) {
			return;
		}
		String tableName = getTableName(baseSchema, schema.getName());
		for(FieldSchema field : missing) {
			String colDef = generateSchemaLine(baseSchema, schema, field);
			if(colDef != null) {
				statements.add("ALTER TABLE " + tableName + " ADD COLUMN " + colDef + ";");
			}
		}
	}

	/// Compute the set of column names the generator would emit for this model's table.
	/// Mirrors generateSchema / getMissingColumns column emission exactly: skips
	/// virtual / ephemeral / referenced fields and any field with no SQL data type
	/// (unreferenced foreign lists, etc.), includes FK columns and inherited fields,
	/// and applies the same name normalization used by getMissingColumns.
	///
	private List<String> getExpectedColumnNames(ModelSchema schema) {
		List<String> expected = new ArrayList<>();
		for(FieldSchema field : schema.getFields()) {
			if(field.isVirtual() || field.isEphemeral() || field.isReferenced()) {
				continue;
			}
			String dataType = getDataType(field, field.getFieldType());
			if(dataType == null) {
				continue;
			}
			expected.add(getColumnName(field.getName()).replace("\"", "").toLowerCase());
		}
		return expected;
	}

	/// Compare existing database columns against the model schema.
	/// Returns live column names that no longer match any persisted model field
	/// (the inverse of getMissingColumns). Returns empty list if the table doesn't exist.
	///
	public List<String> getOrphanedColumns(ModelSchema schema) {
		if(!haveTable(schema.getName())) {
			return new ArrayList<>();
		}
		String tableName = getTableName(schema.getName());
		List<String> existingColumns = getTableColumns(tableName);
		List<String> expected = getExpectedColumnNames(schema);
		List<String> orphaned = new ArrayList<>();
		for(String col : existingColumns) {
			if(!expected.contains(col)) {
				orphaned.add(col);
			}
		}
		return orphaned;
	}

	/// Generate ALTER TABLE DROP COLUMN IF EXISTS statements for orphaned columns.
	/// Returns empty list if the table doesn't exist or there are no orphaned columns.
	/// Gated by IOProperties.isDropColumns() at the call site (IOSystem); never resets / drops tables.
	/// Deliberately covers the model's own table only: dedicated participation tables are patched
	/// additively (generatePatchSchema / generatePatchIndices) but are never dropped from.
	///
	public List<String> generateDropColumnSchema(ModelSchema schema) {
		List<String> orphaned = getOrphanedColumns(schema);
		List<String> statements = new ArrayList<>();
		if(orphaned.isEmpty()) {
			return statements;
		}
		String tableName = getTableName(schema.getName());
		for(String col : orphaned) {
			statements.add("ALTER TABLE " + tableName + " DROP COLUMN IF EXISTS " + getColumnName(col) + ";");
		}
		return statements;
	}

	/// Generate the index DDL for a model as individual statements, one per constraint / hint, for the
	/// schema-patch path.  Indices used to be emitted only by generateSchema (the CREATE TABLE path), so
	/// a constraint or hint added to an already-created model was silently never applied.  Every
	/// statement carries IF NOT EXISTS and is therefore safe to replay.
	/// This deliberately reuses generateIndices so the constraint / hint / collision logic exists once.
	/// A model declaring dedicatedParticipation gets its dedicated participation table's indices here
	/// as well: that table is generated only on the CREATE TABLE path, so the hints declared on
	/// system.participation - including (participantId, participantModel), which every role entitlement
	/// check seeks on - were never patched onto a table created before those hints existed.
	///
	public List<String> generatePatchIndices(ModelSchema schema) {
		List<String> statements = new ArrayList<>();
		if(schema == null || schema.isEphemeral() || isConstrained(schema)) {
			return statements;
		}
		addIndexStatements(statements, generateIndices(null, schema));
		if(schema.isDedicatedParticipation()) {
			addIndexStatements(statements, generateDedicatedParticipationIndices(schema));
		}
		return statements;
	}

	/// Index DDL for the dedicated participation table of a model declaring dedicatedParticipation,
	/// generated exactly the way generateSchema does it: the system.participation schema is the target
	/// and the owning model is the baseSchema, which is what supplies the table name and index name
	/// prefix (see generateIndex).
	/// The table existing is a precondition - it is only ever created alongside the owning model's own
	/// table - so it is checked here rather than left to fail as an opaque 'relation does not exist'
	/// from the per-statement catch on the startup path.
	///
	private String generateDedicatedParticipationIndices(ModelSchema schema) {
		if(!haveTable(schema, ModelNames.MODEL_PARTICIPATION)) {
			logger.warn("Model " + schema.getName() + " declares dedicatedParticipation, but its participation table "
				+ getTableName(schema, ModelNames.MODEL_PARTICIPATION)
				+ " does not exist, so its indices cannot be patched.  That table is created only with the model's own table.");
			return null;
		}
		return generateIndices(schema, RecordFactory.getSchema(ModelNames.MODEL_PARTICIPATION));
	}

	/// Split a generateIndices block into individual, trimmed statements.
	///
	private void addIndexStatements(List<String> statements, String block) {
		if(block == null) {
			return;
		}
		for(String line : block.split("\n")) {
			String stmt = line.trim();
			if(stmt.length() > 0) {
				statements.add(stmt);
			}
		}
	}

	/// Extract the index name from a statement produced by generateIndex, or null if the statement
	/// isn't recognized.  Used to skip DDL for indices that already exist rather than issuing a
	/// no-op CREATE INDEX IF NOT EXISTS for every model on every startup.
	///
	public static String getIndexStatementName(String sql) {
		if(sql == null) {
			return null;
		}
		Matcher m = indexNamePattern.matcher(sql.trim());
		if(m.find()) {
			return m.group(1);
		}
		return null;
	}

	/// PostgreSQL identifiers are limited to NAMEDATALEN-1 (63 by default) and are silently truncated,
	/// so an emitted index name longer than that never equals the name the database actually stores.
	/// Without this normalization the 'already exists' check misses and the statement is re-issued on
	/// every startup - a no-op, since the database truncates the requested name the same way, but a
	/// misleading one.  Dedicated participation index names are the long ones: they carry both the
	/// owning model name and 'system_participation'.
	/// Truncation is the database's own rule, so two names that normalize alike would also collide
	/// inside PostgreSQL; comparing this way does not skip anything the database would have created.
	///
	public String normalizeIndexName(String name) {
		if(name == null) {
			return null;
		}
		String norm = name.toLowerCase();
		if(connectionType == ConnectionEnumType.POSTGRE && norm.length() > MAX_PG_IDENTIFIER_LENGTH) {
			norm = norm.substring(0, MAX_PG_IDENTIFIER_LENGTH);
		}
		return norm;
	}

	/// List the index names defined in the current database, lower-cased.  Returns an empty list on
	/// error or for an unrecognized connection type, in which case callers simply fall back to issuing
	/// the CREATE INDEX IF NOT EXISTS statements.
	///
	public List<String> getIndexNames() {
		List<String> names = new ArrayList<>();
		String sql = null;
		if(connectionType == ConnectionEnumType.POSTGRE) {
			sql = "SELECT indexname AS index_name FROM pg_indexes;";
		}
		else if(connectionType == ConnectionEnumType.H2) {
			sql = "SELECT index_name FROM information_schema.indexes;";
		}
		if(sql == null) {
			return names;
		}
		try (
			Connection con = dataSource.getConnection();
			Statement st = con.createStatement();
		){
			ResultSet rset = st.executeQuery(sql);
			while(rset.next()) {
				String name = rset.getString(1);
				if(name != null) {
					names.add(name.toLowerCase());
				}
			}
			rset.close();
		} catch (SQLException e) {
			logger.error("Failed to enumerate database indices: " + e.getMessage());
		}
		return names;
	}

	/// Execute a statement and surface any SQLException to the caller instead of logging and swallowing
	/// it.  execute(String) cannot report failure (DDL always returns false), and the index patch path
	/// must be able to report - loudly, per statement - which index could not be created.
	///
	public void executeWithException(String sql) throws SQLException {
		try (
			Connection con = dataSource.getConnection();
			Statement statement = con.createStatement();
		){
			statement.execute(sql);
		}
	}

	/// Get existing column names and data types for a table from information_schema.
	/// Returns a map of lowercased column name to lowercased data_type.
	/// Mirrors getTableColumns casing logic for H2 vs PostgreSQL.
	/// Returns empty map on SQL error or missing table.
	///
	private Map<String, String> getColumnDataTypes(String tableName) {
		Map<String, String> types = new HashMap<>();
		String useName = tableName;
		if(this.connectionType == ConnectionEnumType.H2) {
			useName = useName.toUpperCase();
		}
		else if(this.connectionType == ConnectionEnumType.POSTGRE) {
			useName = useName.toLowerCase();
		}
		try (
			Connection con = dataSource.getConnection();
			PreparedStatement st = con.prepareStatement("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?;");
		){
			st.setString(1, useName);
			ResultSet rset = st.executeQuery();
			while(rset.next()) {
				types.put(rset.getString("column_name").toLowerCase(), rset.getString("data_type").toLowerCase());
			}
			rset.close();
		} catch (SQLException e) {
			logger.error(e);
		}
		return types;
	}

	/// Normalize a SQL data type string to a common comparison token.
	/// Handles both getDataType() output and information_schema.data_type values so that
	/// equivalent types (e.g. "int" and "integer", "varchar(N)" and "character varying") compare equal.
	/// Returns null for null input.  Returns "vector" for vector-like types so callers can skip them.
	///
	private static String normalizeForTypeComparison(String dt) {
		if(dt == null) {
			return null;
		}
		String dtl = dt.toLowerCase();
		if(dtl.startsWith("varchar") || dtl.equals("character varying")) {
			return "varchar";
		}
		if(dtl.equals("integer") || dtl.equals("int")) {
			return "int";
		}
		if(dtl.startsWith("timestamp")) {
			return "timestamp";
		}
		if(dtl.startsWith("vector") || dtl.equals("user-defined")) {
			return "vector";
		}
		return dtl;
	}

	/// Compare model schema field types against actual database column types.
	/// Returns a list of FieldSchema objects whose persisted column type differs from the
	/// schema-declared type.  Mirrors getMissingColumns field-skip guards.
	/// Returns empty list if the table does not exist.
	///
	public List<FieldSchema> getMismatchedColumns(ModelSchema schema) {
		List<FieldSchema> mismatched = new ArrayList<>();
		if(!haveTable(schema.getName())) {
			return mismatched;
		}
		String tableName = getTableName(schema.getName());
		Map<String, String> actualTypes = getColumnDataTypes(tableName);

		for(FieldSchema field : schema.getFields()) {
			if(field.isVirtual() || field.isEphemeral() || field.isReferenced()) {
				continue;
			}
			String expectedRaw = getDataType(field, field.getFieldType());
			if(expectedRaw == null) {
				continue;
			}
			String expectedNorm = normalizeForTypeComparison(expectedRaw);
			if("vector".equals(expectedNorm)) {
				continue;
			}
			String colName = getColumnName(field.getName()).replace("\"", "").toLowerCase();
			String actualRaw = actualTypes.get(colName);
			if(actualRaw == null) {
				// Column is absent — let getMissingColumns handle it
				continue;
			}
			String actualNorm = normalizeForTypeComparison(actualRaw);
			if("vector".equals(actualNorm)) {
				continue;
			}
			if(expectedNorm != null && !expectedNorm.equals(actualNorm)) {
				logger.warn("Column type mismatch for " + schema.getName() + "." + field.getName()
					+ ": expected " + expectedNorm + " (" + expectedRaw + "), found " + actualNorm + " (" + actualRaw + ")");
				mismatched.add(field);
			}
		}
		return mismatched;
	}

	/// Generate ALTER TABLE ... ALTER COLUMN ... TYPE ... USING ... statements to repair mismatched
	/// column types.  PostgreSQL-only: H2 does not support the USING clause.
	/// Returns empty list if not PostgreSQL, table does not exist, or no mismatches found.
	///
	public List<String> generateAlterColumnTypeSchema(ModelSchema schema) {
		List<String> statements = new ArrayList<>();
		if(connectionType != ConnectionEnumType.POSTGRE) {
			return statements;
		}
		List<FieldSchema> mismatched = getMismatchedColumns(schema);
		if(mismatched.isEmpty()) {
			return statements;
		}
		String tableName = getTableName(schema.getName());
		for(FieldSchema field : mismatched) {
			String targetType = getDataType(field, field.getFieldType());
			String colName = getColumnName(field.getName());
			statements.add("ALTER TABLE " + tableName + " ALTER COLUMN " + colName
				+ " TYPE " + targetType + " USING " + colName + "::" + targetType + ";");
		}
		return statements;
	}

}
