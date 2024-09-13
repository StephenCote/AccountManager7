package org.cote.accountmanager.record;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldFactory;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.model.field.value.EnumValueType;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ErrorUtil;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.RecordUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;

public abstract class BaseRecord {
	public static final Logger logger = LogManager.getLogger(BaseRecord.class);
	private String model = null;
	private boolean prototype = false;
	private boolean ephemeral = false;
	private boolean internal = false;
	
	private Map<String, FieldType> fieldMap = new ConcurrentHashMap<>();
	private List<FieldType> fields = new CopyOnWriteArrayList<>();

	public BaseRecord(String name, FieldType[] inFields) {
		model = name;
		setFields(Arrays.asList(inFields));
	}
	
	@JsonIgnore
	public String hash() {
		return RecordUtil.hash(this);
	}
	
	@JsonIgnore
	public String hash(String[] fieldNames) {
		return RecordUtil.hash(this, fieldNames);
	}
	
	@JsonIgnore
	public String toString() {
		return RecordUtil.toJSONString(this, true, true);
	}
	
	@JsonIgnore
	public String toFilteredString() {
		return RecordUtil.toFilteredJSONString(this);
	}
	
	@JsonIgnore
	public String toFullString() {
		return RecordUtil.toFullJSONString(this);
	}
	
	public <T> T toConcrete() {
		String useName = model.substring(model.lastIndexOf(".") + 1);
		String clsName = RecordFactory.GENERATED_PACKAGE_NAME + "." + useName.substring(0, 1).toUpperCase() + useName.substring(1) + "Type";

		@SuppressWarnings("unchecked")
		Class<T> cls = (Class<T>)RecordFactory.getClass(clsName);
		return toConcrete(cls);
	}
	
	@SuppressWarnings("unchecked")
	public <T> T toConcrete(Class<?> cls) {
		T obj = (T)RecordFactory.toConcrete(this, cls);
		return obj;
	}
	
	@SuppressWarnings("unchecked")
	public BaseRecord copyDeidentifiedRecord() {
		List<String> fields = RecordFactory.getSchema(model).getFields().stream()
			.filter(f -> (!f.isIdentity() && !f.isVirtual() && !f.isEphemeral()))
			.map(f -> f.getName())
			.collect(Collectors.toList())
		;
		ModelSchema ms = RecordFactory.getSchema(model);
		BaseRecord copy = copyRecord(fields.toArray(new String[0]));
		for(FieldType f: copy.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			try {
				if(f.getValueType() == FieldEnumType.MODEL && f.getValue() != null) {
					f.setValue(((BaseRecord)f.getValue()).copyDeidentifiedRecord());
				}
				else if(f.getValueType() == FieldEnumType.LIST && ModelNames.MODEL_MODEL.equals(fs.getBaseType())) {
					f.setValue(((List<BaseRecord>)f.getValue()).stream().map(f1 -> f1.copyDeidentifiedRecord()).collect(Collectors.toList()));
				}
			} catch (ValueException e) {
				logger.error(e);
			}
		}
		return copy;
	}
	public BaseRecord copyRecord() {
		return copyRecord(new String[0]);
	}
	public BaseRecord copyRecord(String[] outFieldNames) {
		BaseRecord copy = null;
		try {
			copy = RecordFactory.newInstance(getModel(), outFieldNames);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		copyIntoRecord(copy, outFieldNames);
		return copy;
	}
	public BaseRecord copyIntoRecord(BaseRecord copy, String[] outFieldNames) {

		List<FieldType> lflds = getFields();
		Set<String> fset = new HashSet<>(Arrays.asList(outFieldNames));
		List<FieldType> ofields = new CopyOnWriteArrayList<>();
		lflds.forEach(f -> {
			if(outFieldNames.length == 0 ||  fset.contains(f.getName())) {
				ofields.add(RecordFactory.newFieldInstance(f));
			}
		});
		copy.setFields(ofields);

		return copy;
	}
	
	@JsonIgnore
	public boolean isEphemeral() {
		return ephemeral;
	}

	public void setEphemeral(boolean ephemeral) {
		this.ephemeral = ephemeral;
	}


	@JsonIgnore
	public boolean isInternal() {
		return internal;
	}

	public void setInternal(boolean internal) {
		this.internal = internal;
	}

	@JsonIgnore
	public boolean hasField(String name) {
		boolean has = false;
		if(name.contains(".")) {
			String[] embedded = name.split("\\.");
			
			ModelSchema ms = RecordFactory.getSchema(model);
			FieldSchema fs = ms.getFieldSchema(embedded[0]);
			if(fs == null) {
				ErrorUtil.printStackTrace();
				return false;
			}
			if(fs.getBaseModel() == null) {
				logger.error("Field " + embedded[0] + " is not a foreign model");
				return false;
			}
			
			BaseRecord valRec = get(embedded[0]);
			if(valRec != null) {
				String[] newKey = new String[embedded.length - 1];
				System.arraycopy(embedded, 1, newKey, 0, newKey.length);
				String outKey = Arrays.stream(newKey).collect(Collectors.joining("."));
				has = valRec.hasField(outKey);
			}
			
		}
		else {
			has = fieldMap.containsKey(name);
		}
		return has;
	}
	
	@JsonIgnore
	public boolean inherits(String name) {
		ModelSchema mod = RecordFactory.getSchema(model);
		boolean outBool = false;
		if(name.equals(model)) {
			return true;
		}
		if(mod != null) {
			if(IOSystem.isOpen() && !this.model.equals(ModelNames.MODEL_MODEL)) {
				outBool = RecordUtil.inherits(mod, name);
			}
			else {
				outBool = mod.getImplements().contains(name);
			}
		}
		return outBool;
	}
	
	@JsonIgnore
	public List<String> getRequestFields(){
		return RecordFactory.getSchema(model).getFields().stream()
			.map(f -> f.getName())
			.collect(Collectors.toList())
		;
	}

	@JsonIgnore
	public List<String> getIdentityFields(){
		return RecordFactory.getSchema(model).getFields().stream()
			.filter(f -> f.isSequence() || f.isIdentity())
			.map(f -> f.getName())
			.collect(Collectors.toList())
		;
	}

	
	private <T> T getEmbedded(String fieldName) {
		
		String[] embedded = fieldName.split("\\.");
		ModelSchema ms = RecordFactory.getSchema(model);
		FieldSchema fs = ms.getFieldSchema(embedded[0]);
		if(fs == null) {
			logger.error("Schema is null for " + embedded[0]);
			return null;
		}
		if(fs.getBaseModel() == null) {
			logger.error("Field " + embedded[0] + " is not a foreign model");
			return null;
		}
		
		BaseRecord valRec = get(embedded[0]);
		if(valRec != null) {
			String[] newKey = new String[embedded.length - 1];
			System.arraycopy(embedded, 1, newKey, 0, newKey.length);
			String outKey = Arrays.stream(newKey).collect(Collectors.joining("."));
			return valRec.get(outKey);
		}
		return null;
		
	}
	
	@JsonIgnore
	public <T> T getP(String name) {
		if(IOSystem.isInitialized() && !hasField(name)) {
			IOSystem.getActiveContext().getReader().populate(this, new String[] {name});
		}
		return get(name);
	}
	
	@JsonIgnore
	public <T> T get(String name) {
		if(name.contains(".")) {
			return getEmbedded(name);
		}
		try {
			checkField(name);
		} catch (ModelNotFoundException | FieldException e) {
			logger.error(e);
			e.printStackTrace();
			return null;
		}
		
		if(fieldMap.get(name).getValueType().equals(FieldEnumType.FLEX)) {
			logger.debug(String.format(FieldException.ABSTRACT_FIELD, model, name));
			return null;
		}
		return fieldMap.get(name).getValue(this);
	}
	
	@JsonIgnore
	public <T> T getEnum(String name) {
		try {
			checkField(name);
		} catch (ModelNotFoundException | FieldException e) {
			logger.error(e);
			e.printStackTrace();
			return null;
		}
		if(!fieldMap.get(name).getValueType().equals(FieldEnumType.ENUM) || fieldMap.get(name).getFieldValueType() == null) {
			return null;
		}
		return ((EnumValueType)fieldMap.get(name).getFieldValueType()).getEnumValue();
	}
	
	@JsonIgnore
   public <T> T get(String fieldName, T defVal) {
	   if(hasField(fieldName)) {
		   return get(fieldName);
	   }
	   return defVal;
   }
	public BaseRecord newInstance() throws FieldException, ModelNotFoundException {
		return newInstance(null);
	}
	public BaseRecord newInstance(String[] fields) throws FieldException, ModelNotFoundException {
		if(!prototype) {
			logger.error("Can only make a new instance from the prototype");
			return null;
		}
		if(model == null) {
			logger.error("Model name is not defined");
			return null;
		}
		return RecordFactory.newInstance(model, fields);
		
	}
	public void setPrototype(boolean prototype) {
		this.prototype = prototype;
	}
	
	@JsonIgnore
	public boolean isPrototype() {
		return prototype;
	}
	
	private <T> void setEmbedded(String fieldName, T val) throws FieldException, ValueException, ModelNotFoundException {
		String[] embedded = fieldName.split("\\.");
		ModelSchema ms = RecordFactory.getSchema(model);
		FieldSchema fs = ms.getFieldSchema(embedded[0]);
		if(fs == null) {
			throw new FieldException("Schema is null for " + embedded[0] + " in " + model);
		}
		if(fs.getBaseModel() == null) {
			throw new ValueException("Field " + embedded[0] + " is not a foreign model");
		}
		boolean hasField = hasField(embedded[0]);
		if(!hasField) {
			setField(RecordFactory.newFieldInstance(model, embedded[0]));
		}
		FieldType fld = getField(embedded[0]);
		if(fld.getValue() == null) {
			fld.setValue(
				RecordFactory.model(fs.getBaseModel()).newInstance(new String[] { embedded[1] })
			);
		}
		BaseRecord rec = fld.getValue();
		String[] newKey = new String[embedded.length - 1];
		System.arraycopy(embedded, 1, newKey, 0, newKey.length);
		String outKey = Arrays.stream(newKey).collect(Collectors.joining("."));
		if(val instanceof BaseRecord) {
			BaseRecord valRec = (BaseRecord) val;
			rec.set(outKey, valRec.get(embedded[1]));
		}
		else {
			rec.set(outKey, val);
		}
		
	}
	
	public <T> void setQValue(String fieldName, T val)  {
		setValue(fieldName, val, true);
	}
	public <T> void setQ(String fieldName, T val) throws FieldException, ValueException, ModelNotFoundException {
		set(fieldName, val);
		if(RecordUtil.isIdentityRecord(this)) {
			Queue.queueUpdate(this, new String[] {fieldName});
		}
	}
	
	public <T> void setValue(String fieldName, T val) {
		setValue(fieldName, val, false);
	}
	
	private <T> void setValue(String fieldName, T val, boolean queue) {
		try {
			set(fieldName, val);
			if(queue && RecordUtil.isIdentityRecord(this)) {
				Queue.queueUpdate(this, new String[] {fieldName});
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
   public synchronized <T> void set(String fieldName, T val) throws FieldException, ValueException, ModelNotFoundException {
		if(prototype) {
			throw new ValueException(String.format(ValueException.PROTOTYPE_READONLY_EXCEPTION, fieldName, model));
		}
		if(fieldName.contains(".")) {
			setEmbedded(fieldName, val);
			return;
		}

		checkField(fieldName);

		FieldType f = getField(fieldName);
		if(f != null) {
			f.setValue(this, val);
		}
		else {
			throw new FieldException(String.format(FieldException.FIELD_NOT_FOUND, model, fieldName));
		}
   }
   private void checkField(String fieldName) throws ModelNotFoundException, FieldException {
		boolean hasField = hasField(fieldName);
		boolean absError = false;
		if(hasField && fieldMap.get(fieldName).getValueType().equals(FieldEnumType.FLEX)) {
			absError = true;
		}

	   if(!hasField) {
		   FieldType ft = RecordFactory.newFieldInstance(model, fieldName);
		   if(ft.getValueType().equals(FieldEnumType.FLEX)) {
			   absError = true;
		   }
		   else {
			   setField(ft);
		   }
		   
	   }
	   if(absError) {
		   //throw new FieldException(String.format(FieldException.ABSTRACT_FIELD, model, fieldName));
		   //logger.warn(String.format(FieldException.ABSTRACT_FIELD, model, fieldName));
	   }
   }
   public <T> void setFlex(String fieldName, T value) {
	   FieldUtil.setFlex(this, fieldName, value);
   }

   public <T> void setFlex(String fieldName, FieldEnumType type, T value) {
	   FieldUtil.setFlex(this, fieldName, type, value);
	} 
	
   public <T> void setInt(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.intFieldType(name);
	   updateField(f, val);
   }
   public <T> void setLong(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.longFieldType(name);
	   if(val instanceof Integer) {
		   updateField(f, ((Integer)val).longValue());
	   }
	   else {
		   updateField(f, val);
	   }
   }
   public <T> void setBoolean(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.booleanFieldType(name);
	   updateField(f, val);
   }
   public <T> void setDouble(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.doubleFieldType(name);
	   updateField(f, val);
   }
   public <T> void setString(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.stringFieldType(name);
	   updateField(f, val);
   }
   public <T> void setModel(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.modelFieldType(name);
	   updateField(f, val);
   }
   public <T> void setByteArray(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.byteArrayFieldType(name);
	   updateField(f, val);
   }
   public <T> void setDateTime(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.dateTimeFieldType(name);
	   updateField(f, val);
   }
   public <T> void setZoneTime(String name, T val) throws ValueException, ModelException, FieldException, ModelNotFoundException {
	   FieldType f = FieldFactory.zoneTimeFieldType(name);
	   updateField(f, val);
   }
   public void convertField(String name, FieldEnumType type) throws FieldException, ValueException, ModelNotFoundException {
	   if(!hasField(name)) {
		   throw new FieldException(String.format(FieldException.FIELD_NOT_FOUND, model, name));
	   }
	   FieldType curField = fieldMap.get(name);
	   FieldEnumType curType = curField.getValueType();
	   String cverr = String.format(FieldException.FIELD_NOT_CONVERTIBLE, model, name, curType.toString(), type.toString());
	   if(curType.equals(type)) {
		   throw new FieldException(cverr);
	   }
	   FieldType newField = FieldFactory.fieldByType(type, name);

	   switch(type) {
	   		case BLOB:
	   			if(!curType.equals(FieldEnumType.STRING)) {
	   				throw new FieldException(cverr);		
	   			}
	   			newField.setValue(Base64.getDecoder().decode((String)curField.getValue()));
	   			break;
	   		case ZONETIME:
	   			if(!curType.equals(FieldEnumType.STRING)) {
	   				throw new FieldException(cverr);		
	   			}
	   			newField.setValue(ZonedDateTime.parse((String)curField.getValue(), DateTimeFormatter.ISO_ZONED_DATE_TIME));
	   			break;
	   		case TIMESTAMP:
	   			if(!curType.equals(FieldEnumType.LONG) && !curType.equals(FieldEnumType.STRING)) {
	   				throw new FieldException(cverr);		
	   			}
	   			newField.setValue(new Date((Long)curField.getValue()));
	   			break;
	   		default:
	   			throw new FieldException(cverr);
	   }

	   updateField(newField, null, true);
   }
   private <T> void updateField(FieldType f, T val) throws ValueException, FieldException, ModelNotFoundException {
	   updateField(f, val, false);
   }
   private <T> void updateField(FieldType f, T val, boolean converted) throws ValueException, FieldException, ModelNotFoundException {
		if(prototype) {
			throw new ValueException(String.format(ValueException.PROTOTYPE_READONLY_EXCEPTION, f.getName(), model));
		}
	   if(!converted) f.setValue(val);

	   if(hasField(f.getName())) {
		   if(!converted && !fieldMap.get(f.getName()).getValueType().equals(FieldEnumType.FLEX)) {
			   throw new FieldException(String.format(FieldException.NOT_ABSTRACT_FIELD, model, f.getName()));
		   }
		   fieldMap.put(f.getName(), f);
		   this.fields = fields.stream().filter(o -> !o.getName().equals(f.getName())).collect(Collectors.toList());
		   this.fields.add(f);
		   ModelSchema ms = RecordFactory.getSchema(model);
		   FieldSchema fs = ms.getFieldSchema(f.getName());
		   if(fs.getValueType() != null) {
			   set(fs.getValueType(), f.getFieldValueType().getValueType());
		   }
	   }
	   else {
		   throw new FieldException("Field " + f.getName() + " not found on model " + model);
	   }
   }
   public void setField(FieldType f) {
	   if(!fieldMap.containsKey(f.getName())) {
		   fields.add(f);
		   fieldMap.put(f.getName(), f);
	   }
   }
	
	public FieldType getField(String name) {
		if(name == null) {
			return null;
		}
		return fieldMap.get(name);
	}
	
	@JsonIgnore
	public Map<String, FieldType> getFieldMap(){
		return fieldMap;
	}
	public void setFieldMap(Map<String, FieldType> map){
		fieldMap = map;
	}
	public void setFieldList(List<FieldType> fields) {
		this.fields = fields;
	}
	
	@JsonIgnore
	public List<FieldType> getFields(){
		return fields;
	}
	public void setFields(List<FieldType> inFields){
		fields = new CopyOnWriteArrayList<FieldType>(inFields);
		fieldMap.clear();
		for(FieldType f : inFields) {
			if(fieldMap.containsKey(f.getName())) {
				logger.error(model + " field '" + f.getName() + "' is already specified");
			}
			else {
				fieldMap.put(f.getName(), f);
			}
		}
	}
	public String getModel() {
		return model;
	}
	public void setModel(String m) {
		model = m;
	}
	
	public <T> void addAttribute(String name, T val) throws FieldException {
		try {
			AttributeUtil.addAttribute(this, name, val);
		} catch (ModelException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(toString());
			throw new FieldException(e);
		}
	}
	
}
