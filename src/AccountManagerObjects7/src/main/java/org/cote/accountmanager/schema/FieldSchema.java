package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class FieldSchema {
	public static final Logger logger = LogManager.getLogger(FieldSchema.class);
	
	private String name = null;
	private String type = null;
	private String baseClass = null;
	private String baseModel = null;
	private String baseType = null;
	private String baseProperty = null;
	private boolean required = false;
	private boolean readOnly = false;
	private boolean identity = false;
	private boolean virtual = false;
	private boolean foreign = false;
	private String foreignType = null;
	private String foreignField = null;
	private boolean ephemeral = false;
	private boolean inherited = false;
	private String provider = null;
	private boolean index = false;
	private boolean sequence = false;
	private boolean primaryKey = false;
	private boolean recursive = false;
	private boolean allowNull = true;
	private int minLength = 0;
	private int maxLength = 0;
	private String valueType = null;
	private boolean referenced = false;
	private boolean followReference = true;
	private boolean restricted = false;
	//private FieldType defaultValue = null;
	private Object defaultValue = null;
	private ModelAccess access = null;
	private String description = null;
	private List<String> rules = new ArrayList<>();
	
	@JsonProperty("private")
	private boolean priv = false;
	
	public FieldSchema() {
		
	}
	
	
	
	public List<String> getRules() {
		return rules;
	}



	public void setRules(List<String> rules) {
		this.rules = rules;
	}



	public ModelAccess getAccess() {
		return access;
	}



	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public void setAccess(ModelAccess access) {
		this.access = access;
	}
	
	public boolean isRecursive() {
		return recursive;
	}



	public void setRecursive(boolean recursive) {
		this.recursive = recursive;
	}



	public boolean isRestricted() {
		return restricted;
	}



	public void setRestricted(boolean restricted) {
		this.restricted = restricted;
	}



	public boolean isFollowReference() {
		return followReference;
	}




	public void setFollowReference(boolean followReference) {
		this.followReference = followReference;
	}




	public String getValueType() {
		return valueType;
	}




	public void setValueType(String valueType) {
		this.valueType = valueType;
	}




	public boolean isReferenced() {
		return referenced;
	}




	public void setReferenced(boolean referenced) {
		this.referenced = referenced;
	}




	public String getForeignType() {
		return foreignType;
	}




	public void setForeignType(String foreignType) {
		this.foreignType = foreignType;
	}




	public int getMinLength() {
		return minLength;
	}




	public void setMinLength(int minLength) {
		this.minLength = minLength;
	}




	public int getMaxLength() {
		return maxLength;
	}




	public void setMaxLength(int maxLength) {
		this.maxLength = maxLength;
	}




	public boolean isAllowNull() {
		return allowNull;
	}




	public void setAllowNull(boolean allowNull) {
		this.allowNull = allowNull;
	}




	public boolean isPrimaryKey() {
		return primaryKey;
	}




	public void setPrimaryKey(boolean primaryKey) {
		this.primaryKey = primaryKey;
	}




	public boolean isSequence() {
		return sequence;
	}




	public void setSequence(boolean sequence) {
		this.sequence = sequence;
	}




	public boolean isIndex() {
		return index;
	}




	public void setIndex(boolean index) {
		this.index = index;
	}




	public boolean isInherited() {
		return inherited;
	}




	public void setInherited(boolean inherited) {
		this.inherited = inherited;
	}




	public String getBaseProperty() {
		return baseProperty;
	}




	public void setBaseProperty(String baseProperty) {
		this.baseProperty = baseProperty;
	}




	public boolean isEphemeral() {
		return ephemeral;
	}


	public void setEphemeral(boolean ephemeral) {
		this.ephemeral = ephemeral;
	}




	public String getForeignField() {
		if(foreignField == null) {
			return "id";
		}
		return foreignField;
	}




	public void setForeignField(String foreignField) {
		this.foreignField = foreignField;
	}




	public boolean isForeign() {
		return foreign;
	}




	public void setForeign(boolean foreign) {
		this.foreign = foreign;
	}


	@JsonProperty("private")
	public boolean isPriv() {
		return priv;
	}
	
	@JsonProperty("private")
	public void setPriv(boolean priv) {
		this.priv = priv;
	}

	
	@JsonProperty("default")
	public void setDefaultValue(Object val) {
		this.defaultValue = val;
	}
	
	@JsonProperty("default")
	public Object getDefaultValue() {
		return this.defaultValue;
	}
	
	/*
	@JsonProperty("value")
	public void setValue(boolean val) {
		logger.warn("Flex value assign: boolean " + name + " = " + val);
		try {
			defaultValue = FieldFactory.booleanFieldType(name);
			defaultValue.setValue(val);
		} catch (ModelException | ValueException e) {
			logger.error(e.getMessage());
		}
	}
	*/

	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getType() {
		return type;
	}
	
	@JsonIgnore
	public FieldEnumType getFieldType() {
		return FieldEnumType.valueOf(type.toUpperCase());
	}
	
	public void setType(String type) {
		this.type = type;
	}

	public String getBaseClass() {
		return baseClass;
	}

	public void setBaseClass(String baseClass) {
		this.baseClass = baseClass;
	}

	public String getBaseModel() {
		return baseModel;
	}

	public void setBaseModel(String baseModel) {
		this.baseModel = baseModel;
	}

	public String getBaseType() {
		return baseType;
	}

	public void setBaseType(String baseType) {
		this.baseType = baseType;
	}

	public boolean isRequired() {
		return required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly(boolean readOnly) {
		this.readOnly = readOnly;
	}

	public boolean isIdentity() {
		return identity;
	}

	public void setIdentity(boolean identity) {
		this.identity = identity;
	}

	public boolean isVirtual() {
		return virtual;
	}

	public void setVirtual(boolean virtual) {
		this.virtual = virtual;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	

	
}
