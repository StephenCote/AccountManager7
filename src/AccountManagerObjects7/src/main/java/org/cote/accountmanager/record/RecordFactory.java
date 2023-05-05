package org.cote.accountmanager.record;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldFactory;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class RecordFactory {
	public static final Logger logger = LogManager.getLogger(RecordFactory.class);
	
	public static String GENERATED_PACKAGE_NAME = "org.cote.accountmanager.objects.generated";
	public static final String JSON_MODEL_KEY = "model";

	private static Map<String, String> looseImports = new HashMap<>();
	private static Map<String, BaseRecord> looseBaseModels = new HashMap<>();
	private static Map<String, ModelSchema> schemas = new HashMap<>();
	private static Map<String, Class<?>> classMap = new HashMap<>();
	private static Map<String, String> rawModels = new HashMap<>();
	
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
		if(cls == null) return null;
		Object enumValue = null;
		try{
			enumValue = Enum.valueOf((Class)cls, value);
		}
		catch(IllegalArgumentException iae) {
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
		classMap.put(name, cls);
		return cls;
	}
	
	public static void clearModels() {
		looseBaseModels.clear();
		schemas.clear();
		classMap.clear();
		looseImports.clear();
		rawModels.clear();
	}
	/*
	public static void clearImports() {
		for(String key : looseImports.keySet()) {
			Catalog.clearCatalog(key);
		}
		//looseImports.clear();
		looseBaseModels.clear();
	}
	*/
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
		List<FieldType> fields = new ArrayList<>();
		List<FieldType> instFields = new ArrayList<>();
		lbm.setModel(name);
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
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
			throw new FieldException("newFieldInstance: Field " + field + " was not found on model " + model);
		}
		return newFieldInstance(ft);
		
		
	}
	public static FieldType newFieldInstance(FieldType field) {
		FieldType nft = FieldFactory.fieldByType(field.getValueType(), field.getName());
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
			//logger.info(rawModels.get(name));
			return rawModels.get(name);
		}
		/*
		InputStream srs = ClassLoader.getSystemResourceAsStream("./models/" + name + "Model.json");
		if(srs == null) {
			logger.error("Null stream for " + name + "Model.json");
			return null;
		}
		BufferedInputStream is = new BufferedInputStream(srs);
		String file = null;
		try {
			file = StreamUtil.streamToString(is);
		} catch (IOException e) {
			logger.error("IOException: " + e.getMessage());
			
		}
		finally {
			try {
				is.close();
			} catch (IOException e) {
				//logger.error(e);
			}
		}
		*/
		String file = ResourceUtil.getModelResource(name);
		if(file != null) {
			looseImports.put(name, file);
		}
		return file;
	}
	
	private static LooseRecord getBaseModel(ModelSchema lmod) {
		LooseRecord mod = new LooseRecord();
		mod.setModel(lmod.getName());
		List<FieldType> fields = new ArrayList<>();
		int errors = 0;
		for(FieldSchema lft : lmod.getFields()) {
			String typev = lft.getType().toUpperCase();
			if(EnumUtils.isValidEnum(FieldEnumType.class, typev)) {
				FieldEnumType type = FieldEnumType.valueOf(typev);
				//boolean ftype = type.equals(FieldEnumType.FIELD);
				//FieldType f = null;
				//logger.info(typev + " -> " + ftype);
				//if(!ftype) {
					FieldType f = FieldFactory.fieldByType(type, lft.getName());
					if(f != null) {
						if(lft.getDefaultValue() != null) {
							try {
								if(lft.getType().equals("long") && lft.getDefaultValue() instanceof Integer) {
									// logger.info("Setting default value for " + lft.getName() + " to " + lft.getDefaultValue());
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
						f.getFieldValueType().setBaseModel(lft.getBaseModel());
						f.getFieldValueType().setBaseType(lft.getBaseType());
						fields.add(f);
					}
					else {
						logger.error("Failed to map " + lft.getName() + " type " + type + " to field");
						errors++;
					}
				/*
				}
				else {
					logger.info("Handle flex field for " + lft.getName());
				}
				*/
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
	
	public static ModelSchema getSchema(String name) {
		if(schemas.containsKey(name)) {
			return schemas.get(name);
		}
		ModelSchema mod = importSchemaFromResource(name);
		if(mod == null) {
			logger.error("Failed to import loose model for " + name);
			return null;
		}
		schemas.put(name,  mod);
		return mod;
	}

	public static BaseRecord importSchema(String name) {
		if(looseBaseModels.containsKey(name)) {
			logger.warn("Model " + name + " already imported");
			return looseBaseModels.get(name);
		}
		ModelSchema mod = getSchema(name);
		if(mod == null) {
			// logger.error("Failed to import loose model for " + name);
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
		Catalog.register(name, lbm.getFields().toArray(new FieldType[0]));
		looseBaseModels.put(name,  lbm);
		return lbm;
		
	}
	
	private static ModelSchema importSchemaFromResource(String name) {
		Set<String> impSet =  new HashSet<String>();
		ModelSchema mod = importSchemaFromResource(name, impSet);
		if(mod == null) {
			logger.error("Failed to load schema from resource '" + name + "'");
			return null;
		}
		mod.setImplements(new ArrayList<String>(impSet));
		mod.getFields().forEach(f -> {
			/// f.isForeign() && 
			if(ModelNames.MODEL_SELF.equals(f.getBaseModel())) {
				f.setBaseModel(mod.getName());
			}
		});
		return mod;
	}
	private static ModelSchema importSchemaFromResource(String name, Set<String> impSet) {
		final ModelSchema mod;
		String file = getResource(name);
		//Set<String> impSet = new HashSet<>();
		impSet.add(name);
		int errors = 0;
		if(file != null) {
			mod = JSONUtil.importObject(file, ModelSchema.class);
			if(mod != null) {
				for(String imp : mod.getInherits()) {
					if(!impSet.contains(imp)) {
						impSet.add(imp);
						ModelSchema impMod = importSchemaFromResource(imp, impSet);
						if(impMod != null) {
							//mod.getFields().addAll(impMod.getFields());
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
		if(!contents.contains("\"model\"")) {
			contents = contents.replaceFirst("\\{", "{\"model\": \"" + modelName + "\", ");
		}
		return importRecord(contents);
	}

	public static BaseRecord importRecord(String contents) {
		return JSONUtil.importObject(contents.trim(), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}
}
