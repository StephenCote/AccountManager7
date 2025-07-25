package org.cote.accountmanager.record;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldFactory;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ErrorUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class RecordFactory {
	public static final Logger logger = LogManager.getLogger(RecordFactory.class);
	
	public static String GENERATED_PACKAGE_NAME = "org.cote.accountmanager.objects.generated";
	public static final String JSON_MODEL_KEY = "schema";
	public static final String JSON_MODEL_SHORT_KEY = "m";

	private static Map<String, String> looseImports = new ConcurrentHashMap<>();
	private static Map<String, BaseRecord> looseBaseModels = new ConcurrentHashMap<>();
	private static Map<String, ModelSchema> schemas = new ConcurrentHashMap<>();
	private static Map<String, Class<?>> classMap = new ConcurrentHashMap<>();
	private static Map<String, Object> instMap = new ConcurrentHashMap<>();
	private static Map<String, String> rawModels = new ConcurrentHashMap<>();
	
	public static boolean isEnumValue(String name, String value) {
		return (getEnumValue(name, value) != null);
	}
	
	public static <T> T toConcrete(BaseRecord rec, Class<T> cls) {
		T inst = null;
		try {

			inst = cls.getDeclaredConstructor(BaseRecord.class).newInstance(rec);
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			logger.error(e);
		}
		return inst;
	}

	@SuppressWarnings({ "unchecked" })
	public static Object getEnumValue(String name, String value) {
		Class<?> cls = getClass(name);
		if(cls == null || value == null) {
			return null;
		}
		
		Object enumValue = null;
		try{
			enumValue = Enum.valueOf((Class)cls, value.toUpperCase());
		}
		catch(NullPointerException | IllegalArgumentException iae) {
			logger.error(String.format("Error looking up enum value %s for %s", value, name));
			logger.error(iae);
		}
		return enumValue;
	}
	
	public static void addRawModels(Map<String, String> models) {
		rawModels.putAll(models);
	}
	public static void addRawModel(String name, String data) {
		rawModels.put(name,  data);
	}
	
	public static Map<String, String> getRawModels(){
		return rawModels;
	}
	
	@SuppressWarnings("unchecked")
	public static <T> T getClassInstance(String cls) {
		if(!instMap.containsKey(cls)) {
			Class<?> ccls = getClass(cls);
			if(ccls != null) {
				Object inst = getClassInstance(ccls);
				if(inst != null) {
					instMap.put(cls, inst);
				}
			}
			else {
				return null;
			}
		}
		return (T)instMap.get(cls);
	}

	public static <T> T getClassInstance(Class<T> cls) {
		
		T inst = null;
		try {
			inst = cls.getDeclaredConstructor().newInstance();
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			logger.error(e);
		}
		return inst;
	}
	
	public static <T> Class<?> getClass(String name){
		if(classMap.containsKey(name)) {
			return classMap.get(name);
		}
		Class<?> cls = null;
		try {
			cls = Class.forName(name);
		} catch (ClassNotFoundException e) {
			logger.error(e);
		}
		if(cls != null) {
			classMap.put(name, cls);
		}
		return cls;
	}
	public static void clearCache(String name) {
		unloadSchema(name);
	}
	public static void clearCache() {
		looseBaseModels.clear();
		schemas.clear();
		classMap.clear();
		instMap.clear();
		looseImports.clear();
		rawModels.clear();
	}

	public static BaseRecord model(String name) {
		if(looseBaseModels.containsKey(name)) {
			return looseBaseModels.get(name);
		}
		return importSchema(name);
	}

	
	public static BaseRecord newInstance(String name) throws FieldException, ModelNotFoundException {
		return newInstance(name, null);
	}
	public static BaseRecord newInstance(String name, String[] fieldNames) throws FieldException, ModelNotFoundException {
		LooseRecord lbm = new LooseRecord();
		if(!looseBaseModels.containsKey(name)) {
			throw new ModelNotFoundException(String.format(ModelNotFoundException.NOT_FOUND, name));
		}
		return newInstance(name, lbm, fieldNames);
	}
	public static BaseRecord newInstance(String name, BaseRecord lbm, String[] fieldNames) throws FieldException, ModelNotFoundException {
		List<FieldType> fields = new CopyOnWriteArrayList<>();
		List<FieldType> instFields = new CopyOnWriteArrayList<>();
		lbm.setSchema(name);
		BaseRecord lbmb = looseBaseModels.get(name);
		
		if(fieldNames != null && fieldNames.length > 0) {
			for(String s : fieldNames) {
				FieldType ft = lbmb.getField(s);
				if(ft == null) {
					throw new FieldException("newInstance: Field " + s + " was not found on model " + name);
				}
				else {
					instFields.add(ft);
				}
			}
		}
		else {
			instFields = lbmb.getFields();
		}
		for(FieldType ft : instFields) {
			fields.add(newFieldInstance(ft));
		}
		lbm.setFields(fields);
		return lbm;
		
	}
	
	
	public static FieldType newFieldInstance(String model, String field) throws ModelNotFoundException, FieldException {
		if(!looseBaseModels.containsKey(model)) {
			throw new ModelNotFoundException(String.format(ModelNotFoundException.NOT_FOUND, model));
		}
		BaseRecord lbmb = looseBaseModels.get(model);
		FieldType ft = lbmb.getField(field);
		if(ft == null) {
			ErrorUtil.printStackTrace();
			throw new FieldException("newFieldInstance: Field " + field + " was not found on model " + model);
		}
		return newFieldInstance(ft);
	}

	public static FieldType newFieldInstance(FieldType field) {
		FieldType nft = FieldFactory.fieldByType(field.getValueType(), field.getName().intern());
		nft.getFieldValueType().setBaseClass(field.getFieldValueType().getBaseClass());
		nft.getFieldValueType().setBaseModel(field.getFieldValueType().getBaseModel());
		nft.getFieldValueType().setBaseType(field.getFieldValueType().getBaseType());
		try {
			nft.setValue(field.getValue());
		} catch (ValueException e) {
			logger.error(e);
			
		}
		return nft;
	}
	
	
	
	private static String getResource(String name) {
		if(looseImports.containsKey(name)) return looseImports.get(name);
		
		if(rawModels.containsKey(name)) {
			looseImports.put(name, rawModels.get(name));
			return rawModels.get(name);
		}
		String file = ResourceUtil.getInstance().getModelResource(name);
		if(file != null) {
			looseImports.put(name, file);
		}
		return file;
	}
	
	private static LooseRecord getBaseModel(ModelSchema lmod) {
		LooseRecord mod = new LooseRecord();
		mod.setSchema(lmod.getName());
		List<FieldType> fields = new CopyOnWriteArrayList<>();
		int errors = 0;
		for(FieldSchema lft : lmod.getFields()) {
			String typev = lft.getType().intern().toUpperCase();
			if(EnumUtils.isValidEnum(FieldEnumType.class, typev)) {
				FieldEnumType type = FieldEnumType.valueOf(typev);
				FieldType f = FieldFactory.fieldByType(type, lft.getName().intern());
				if(f != null) {
					if(lft.getDefaultValue() != null) {
						try {
							if(lft.getType().equals("long") && lft.getDefaultValue() instanceof Integer) {
								f.setValue(Long.valueOf((Integer)lft.getDefaultValue()));
							}
							else {
								f.setValue(lft.getDefaultValue());
							}
						} catch (Exception e) {
							logger.error(e);
						}
					}
					f.getFieldValueType().setBaseClass(lft.getBaseClass());
					f.getFieldValueType().setBaseModel(lft.getBaseModel() != null ? lft.getBaseModel().intern() : null);
					f.getFieldValueType().setBaseType(lft.getBaseType() != null ? lft.getBaseType().intern() : null);
					fields.add(f);
				}
				else {
					logger.error("Failed to map " + lft.getName() + " type " + type + " to field");
					errors++;
				}

			}
			else {
				logger.error("Invalid data type: '" + typev + "' for " + lft.getName());
				errors++;
			}
		}
		if(errors > 0) {
			return null;
		}
		mod.setFields(fields);
		mod.setPrototype(true);
		return mod;
	}
	
	public static Query getSchemaQuery(String modelName) {
		OrganizationContext sysOrg = IOSystem.getActiveContext().getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, null);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_MODEL_SCHEMA, FieldNames.FIELD_NAME, modelName);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, sysOrg.getOrganizationId());
		q.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_SCHEMA});
		return q;
	}
	
	private static ModelSchema getIOSchema(String modelName) {
		ModelSchema ms = null;
		if(IOSystem.isInitialized()) {
			Query q = getSchemaQuery(modelName);
			BaseRecord modelRec = IOSystem.getActiveContext().getSearch().findRecord(q);
			if(modelRec != null) {
				if(!modelRec.hasField(FieldNames.FIELD_SCHEMA)) {
					logger.error("***** Error loading schema");
					return null;
				}
				byte[] data = modelRec.get(FieldNames.FIELD_SCHEMA);
				if(data.length > 0) {
					ms = JSONUtil.importObject(new String(data), ModelSchema.class);
				}
			}
		}
		return ms;
	}
	
	private static ModelSchema createIOSchema(String name, ModelSchema ims) {
		
		if(!IOSystem.isInitialized()) {
			logger.error("Active context is not available or initialized");
			return null;
		}
		if(ims == null) {
			logger.error("Null schema for: " + name);
			return null;
		}

		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext sysOrg = ioContext.getOrganizationContext("/System", null);
		if(sysOrg == null || sysOrg.getOpsUser() == null) {
			logger.error("System organization is not configured");
			return null;
		}
		ModelSchema ms = null;
		
		try {

			ims.setName(name);
			BaseRecord rec = newInstance(ModelNames.MODEL_MODEL_SCHEMA);
			rec.set(FieldNames.FIELD_OWNER_ID, sysOrg.getOpsUser().get(FieldNames.FIELD_ID));
			rec.set(FieldNames.FIELD_ORGANIZATION_ID, sysOrg.getOrganizationId());
			rec.set(FieldNames.FIELD_NAME, name);

			/// Re-serialize for name change
			///
			rec.set(FieldNames.FIELD_SCHEMA, JSONUtil.exportObject(ims).getBytes());
			if(ioContext.getRecordUtil().createRecord(rec)) {
				ms = getIOSchema(name);
				if(ms != null) {
					if(ioContext.getIoType() == RecordIO.DATABASE) {
						/// this is superfluous with generateNewSchema, which does the same thing
						if(!ioContext.getDbUtil().isConstrained(ms) && !ioContext.getDbUtil().haveTable(name)) {
							String dbSchema = ioContext.getDbUtil().generateNewSchemaOnly(ms);
							if(dbSchema != null) {
								logger.info("Generating schema:");
								// logger.info(dbSchema);
								ioContext.getDbUtil().execute(dbSchema);
								ModelNames.releaseCustomModelNames();
							}
							else {
								logger.debug("**** Schema not defined for " + name);
							}
						}
						else {
							logger.debug("Schema is constrained or already exists");
						}
					}
					else {
						logger.error("Unhandled IO Type: " + ioContext.getIoType().toString());
					}
				}
				else {
					logger.error("Failed to retrieve schema record " + name);
				}
			}
			else {
				logger.error("Failed to create schema record, " + name);
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}

		return ms;
	}
	
	public static ModelSchema getSchema(String name) {
		if(name == null) {
			return null;
		}
		if(schemas.containsKey(name)) {
			return schemas.get(name);
		}
		
		ModelSchema mod = getIOSchema(name);
		if(mod == null) {
			mod = importSchemaFromResource(name);
		}
		if(mod == null) {
			logger.debug("Failed to import loose model for " + name);
			return null;
		}
		Set<String> shortMap = new HashSet<>();
		for(FieldSchema fs: mod.getFields()) {
			if(fs.getShortName() != null) {
				if(shortMap.contains(fs.getShortName())) {
					logger.error(name + " " + fs.getName() + " short name " + fs.getShortName() + " defined more than once");
				}
				else if(mod.getFieldSchema(fs.getShortName()) != null) {
					logger.error(name + " " + fs.getName() + " short name is already a field name");
				}
				else {
					shortMap.add(fs.getShortName());
				}
			}
		}
		schemas.put(name,  mod);
		return mod;
	}
	
	public static void unloadSchema(String name) {
		looseImports.remove(name);
		looseBaseModels.remove(name);
		schemas.remove(name);
		rawModels.remove(name);
		
		ResourceUtil.getInstance().releaseModelResource(name);
	}
	
	public static void deleteOrganization(long orgId) {
		if(IOSystem.getActiveContext().getIoType() == RecordIO.DATABASE) {
			String sql = StatementUtil.getDeleteOrganizationTemplate(orgId);
			if(sql != null && sql.length() > 0) {
				long start = System.currentTimeMillis();
				try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); Statement st = con.createStatement();){
					st.executeUpdate(sql);
					CacheUtil.clearCache();
				}
				catch (SQLException e) {
					logger.error(e);
			    }
				long stop = System.currentTimeMillis();
				logger.info("Cleaned up organization in " + (stop - start) + "ms");
			}
		}
		else {
			logger.info("Organization cleanup not supported on " + IOSystem.getActiveContext().getIoType().toString());
		}
	}
	
	public static void cleanupOrphans(String model) {
		if(IOSystem.getActiveContext().getIoType() == RecordIO.DATABASE) {
			
			String sql = StatementUtil.getDeleteOrphanTemplate(model);
			if(sql != null && sql.length() > 0) {
				long start = System.currentTimeMillis();

				try (Connection con = IOSystem.getActiveContext().getDbUtil().getDataSource().getConnection(); Statement st = con.createStatement();){
					st.executeUpdate(sql);
					CacheUtil.clearCache();
				}
				catch (SQLException e) {
					logger.error(e);
			    }
				long stop = System.currentTimeMillis();
				logger.info("Cleaned up orphans in " + (stop - start) + "ms");
			}
		}
		else {
			logger.info("Orphan cleanup not supported on " + IOSystem.getActiveContext().getIoType().toString());
		}
	}
	public static boolean releaseCustomSchema(String name) {
		IOContext ctx = IOSystem.getActiveContext();
		
		
		if(ModelNames.MODELS.contains(name)) {
			logger.warn("Releasing model " + name + " defined at the system level");
			// return false;
		}
		
		ModelSchema ms = RecordFactory.getSchema(name);
		if(ms == null) {
			logger.warn("Unknown schema: " + name);
			return false;
		}
		
		OrganizationContext sysOrg = ctx.getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, null);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_MODEL_SCHEMA, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, sysOrg.getOrganizationId());
		BaseRecord rec = ctx.getAccessPoint().find(sysOrg.getAdminUser(), q);

		if(rec == null) {
			logger.warn("Model schema entry for " + name + " was not found");
			// return false;
		}
		else if(!ctx.getAccessPoint().delete(sysOrg.getAdminUser(), rec)) {
			logger.warn("Failed to delete schema entry for " + name);
			return false;
		}
		boolean released = false;
		logger.warn("**** RELEASING SCHEMA: " + name);
		if(IOSystem.getActiveContext().getIoType() == RecordIO.DATABASE) {
			StringBuilder sql = new StringBuilder();
			
			sql.append("DROP TABLE IF EXISTS " + ctx.getDbUtil().getTableName(name) + " CASCADE;\n");
			if(ms.isDedicatedParticipation()) {
				sql.append("DROP TABLE IF EXISTS " + ctx.getDbUtil().getTableName(ms, ModelNames.MODEL_PARTICIPATION) + " CASCADE;\n");
			}
			if(rec != null) {
			    try (Connection con = ctx.getDbUtil().getDataSource().getConnection(); PreparedStatement st = con.prepareStatement(sql.toString());){
			    	st.execute();
			    	released = true;
			    	logger.warn("**** RELEASED SCHEMA: " + name);
			    }
			    catch(SQLException e) {
			    	logger.error(e);
			    }
			}
		}
		else if(IOSystem.getActiveContext().getIoType() == RecordIO.FILE) {
			logger.warn("TODO: Cleanup file-based schema storage");
			released = true;
		}
		
		if(released) {
			unloadSchema(name);
			CacheUtil.clearCache();
		}
		
		return released;

	}
	
	public static ModelSchema getCustomSchemaFromResource(String name, String resourceName) {
		ModelSchema ms = getIOSchema(name);
		if(ms == null) {
			String schema = ResourceUtil.getInstance().getModelResource(resourceName);
			if(schema != null) {
				ms = importSchemaFromUser(name, schema);
			}
		}
		if(ms != null) {
			/// Invoke model to force dependency lookups, otherwise, new models won't be discoverable until the next IOSystem recycle
			model(name);
		}
		return ms;
	}

	

	public static BaseRecord importSchema(String name) {
		if(looseBaseModels.containsKey(name)) {
			logger.warn("Model " + name + " already imported");
			return looseBaseModels.get(name);
		}
		ModelSchema mod = getSchema(name);
		if(mod == null) {
			logger.error("Failed to import loose model for " + name);
			return null;
		}
		if(mod.isAbs()) {
			logger.warn("Abstract model '" + name + "' cannot be loaded directly");
			return null;
		}

		LooseRecord lbm = getBaseModel(mod);
		if(lbm == null) {
			logger.warn("Failed to construct loose base model for " + name);
			return null;
		}

		looseBaseModels.put(name,  lbm);
		return lbm;
		
	}
	
	public static ModelSchema importSchemaFromUser(String name, String schema) {
		ModelSchema mod = getIOSchema(name);
		if(mod != null) {
			logger.error("Model schema already exists: " + name);
			return null;
		}
		Set<String> impSet =  new HashSet<String>();
		mod = importSchemaFromContents(name, schema, impSet);
		if(mod == null) {
			logger.error("Failed to load schema from contents '" + name + "'");
			return null;
		}
		configureSchema(mod, impSet);
		if(createIOSchema(name, mod) == null) {
			logger.info("Failed to create schema");
			return null;
		}
		return getSchema(name);
	}
	private static void configureSchema(ModelSchema mod, Set<String> impSet) {
		mod.setImplements(new ArrayList<String>(impSet));
		mod.getFields().forEach(f -> {
			if(ModelNames.MODEL_SELF.equals(f.getBaseModel())) {
				f.setBaseModel(mod.getName());
			}
		});
	
	}
	
	private static ModelSchema importSchemaFromResource(String name) {
		Set<String> impSet =  new HashSet<String>();
		ModelSchema mod = importSchemaFromResource(name, impSet);
		if(mod == null) {
			logger.error("Failed to load schema from resource '" + name + "'");
			return null;
		}
		configureSchema(mod, impSet);
		return mod;
	}
	
	private static ModelSchema importSchemaFromIO(String name, Set<String> impSet) {
		ModelSchema ms = getIOSchema(name);
		if(ms != null) {
			importSchema(ms, name, impSet);
		}
		else {
			ms = importSchemaFromResource(name, impSet);
		}
		return ms;
	}
	
	private static ModelSchema importSchemaFromResource(String name, Set<String> impSet) {
		return importSchemaFromContents(name, getResource(name), impSet);
	}
	
	private static ModelSchema importSchemaFromContents(String name, String contents, Set<String> impSet) {
		ModelSchema mod = null;
		
		//Set<String> impSet = new HashSet<>();
		// impSet.add(name);
		int errors = 0;
		if(contents != null) {
			mod = JSONUtil.importObject(contents, ModelSchema.class);
			if(mod != null) {
				mod = importSchema(mod, name, impSet);
			}
			else {
				logger.error("Failed to deserialize " + name);
				errors++;
			}
		}
		else {
			mod = null;
			logger.error("Failed to load " + name + " from resources");
			errors++;
			
		}
		if(errors > 0) {
			return null;
		}
		return mod;
	}
	private static ModelSchema importSchema(final ModelSchema mod, String name, Set<String> impSet) {
		impSet.add(name);
		int errors = 0;
		if(mod != null) {
			for(String imp : mod.getLikeInherits()) {
				if(!impSet.contains(imp)) {
					impSet.add(imp);
				}
			}
			for(String imp : mod.getInherits()) {
				if(!impSet.contains(imp)) {
					impSet.add(imp);
					ModelSchema impMod = importSchemaFromIO(imp, impSet);
					if(impMod != null) {
						impMod.getFields().forEach(f -> {
							f.setInherited(true);
							mod.getFields().add(f);
						});
					}
					else {
						logger.error("Failed to import " + name);
						errors++;
					}
				}
				else {
					/// prevent recursive imports
					logger.debug(imp + " already imported");
				}
			}
		}
		else {
			logger.error("Failed to deserialize " + name);
			errors++;
		}
		if(errors > 0) {
			return null;
		}
		return mod;
	}

	
	public static BaseRecord importRecord(String modelName, String contents) {
		if(contents == null || contents.length() == 0) {
			logger.error("Invalid contents");
			return null;
		}
		contents = contents.trim();
		if(!contents.startsWith("{")) {
			logger.error("Expected string to start with a JSON construct");
			return null;
		}
		if(!contents.contains("\"" + JSON_MODEL_KEY + "\"")) {
			contents = contents.replaceFirst("\\{", "{\"" + JSON_MODEL_KEY + "\": \"" + modelName + "\", ");
		}
		return importRecord(contents);
	}

	public static BaseRecord importRecord(String contents) {
		return JSONUtil.importObject(contents.trim(), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}
}
