package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.provider.ComputeEnumType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class FieldSchema {
	public static final Logger logger = LogManager.getLogger(FieldSchema.class);
	
	private String name = null;
	private String shortName = null;
	private String type = null;
	private String baseClass = null;
	private String baseModel = null;
	private String baseType = null;
	private String baseProperty = null;
	private boolean internal = false;
	private boolean required = false;
	private boolean readOnly = false;
	private boolean identity = false;
	private boolean deprecated = false;
	
	/// Virtual is used to indicate the field contains a non-persistent generated value
	/// For example, path is a virtual field in that it is computed when the field is requested
	/// TODO: This field may be renamed to 'computed' and include computation qualifiers, such as dependent fields
	/// At the moment, the designated provider is responsible for checking and/or populating required fields
	///
	private boolean virtual = false;
	private boolean foreign = false;
	private String participantModel = null;
	
	/// Participation indicates that the field base model drives the participation location, vs the object's model
	/// E.G.: For a person, accounts and siblings would participate on the object's model.  Roles, Groups, and Tags would participate on the Tag's model.
	/// At the moment, only Tags are being treated as a direct property
	///
	private boolean participation = false;
	private String foreignType = null;
	private String foreignField = null;
	
	/// Ephemeral is used to indicate the field is not persisted and is not guaranteed to exist between operations
	/// For example, these fields will not be persisted, journaled, or hashed
	private boolean ephemeral = false;
	private boolean inherited = false;
	private String provider = null;
	private int priority = 0;
	private boolean index = false;
	private boolean sequence = false;
	private boolean primaryKey = false;
	private boolean recursive = false;
	private boolean allowNull = true;
	private int minLength = 0;
	private int maxLength = 0;
	private boolean validateRange = false;
	private double minValue = 0;
	private double maxValue = 0;
	private String valueType = null;
	private boolean referenced = false;
	private boolean followReference = true;
	private boolean dynamicPolicy = true;

	/// Restricted is used to avoid inclusion in operations such as field hashing
	///
	private boolean restricted = false;
	
	private Object defaultValue = null;
	private ModelAccess access = null;
	private String description = null;
	private String label = null;
	private String icon = null;

	private List<String> rules = new ArrayList<>();
	private List<Object> limit = new ArrayList<>();
	private List<String> fields = new ArrayList<>();
	private ComputeEnumType compute = ComputeEnumType.UNKNOWN;
	
	private boolean encrypt = false;
	
	@JsonProperty("private")
	private boolean priv = false;
	
	public FieldSchema() {
		
	}

	public boolean isDeprecated() {
		return deprecated;
	}

	public void setDeprecated(boolean deprecated) {
		this.deprecated = deprecated;
	}

	public boolean isParticipation() {
		return participation;
	}

	public void setParticipation(boolean participation) {
		this.participation = participation;
	}

	public List<String> getFields() {
		return fields;
	}

	public void setFields(List<String> fields) {
		this.fields = fields;
	}

	public ComputeEnumType getCompute() {
		return compute;
	}

	public void setCompute(ComputeEnumType compute) {
		this.compute = compute;
	}

	public String getShortName() {
		return shortName;
	}

	public void setShortName(String shortName) {
		this.shortName = shortName;
	}

	public boolean isValidateRange() {
		return validateRange;
	}

	public void setValidateRange(boolean validateRange) {
		this.validateRange = validateRange;
	}

	public double getMinValue() {
		return minValue;
	}

	public void setMinValue(double minValue) {
		this.minValue = minValue;
	}

	public double getMaxValue() {
		return maxValue;
	}

	public void setMaxValue(double maxValue) {
		this.maxValue = maxValue;
	}

	public boolean isDynamicPolicy() {
		return dynamicPolicy;
	}

	public void setDynamicPolicy(boolean dynamicPolicy) {
		this.dynamicPolicy = dynamicPolicy;
	}

	public String getParticipantModel() {
		return participantModel;
	}

	public void setParticipantModel(String participantModel) {
		this.participantModel = participantModel;
	}

	public int getPriority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	public boolean isEncrypt() {
		return encrypt;
	}

	public void setEncrypt(boolean encrypt) {
		this.encrypt = encrypt;
	}

	public List<Object> getLimit() {
		return limit;
	}

	public void setLimit(List<Object> limit) {
		this.limit = limit;
	}

	public boolean isInternal() {
		return internal;
	}

	public void setInternal(boolean internal) {
		this.internal = internal;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
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
			return FieldNames.FIELD_ID;
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
