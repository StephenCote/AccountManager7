package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.RecordUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ModelSchema {
	public static final Logger logger = LogManager.getLogger(ModelSchema.class);
	private String name = null;
	private String shortName = null;
	
	@JsonProperty("absolute")	
	private boolean abs = false;
	
	private boolean ephemeral = false;
	private boolean followReference = true;
	private boolean emitModel = false;
	private String group = null;
	private String provider = null;

	private List<FieldSchema> fields = new ArrayList<>();
	private List<String> inherits = new ArrayList<>();
	private List<String> likeInherits = new ArrayList<>();
	private List<String> implist = new ArrayList<>();
	private List<String> constraints = new ArrayList<>();
	private List<String> ioConstraints = new ArrayList<>();
	private List<String> query = new ArrayList<>();
	private List<String> hints = new ArrayList<>();
	
	private String factory = null;
	
	private ModelAccess access = null;
	private ModelIO io = null;
	private ModelValidation validation = null;
	
	private String description = null;
	private String label = null;
	private String icon = null;
	private List<String> categories = new ArrayList<>();
	
	/// Dedicated participation is used to indicate that any participations for this model are to be made in a dedicated table
	/// Otherwise, participations will be stored in the common participation table
	/// This is to allow for models with large participation datasets use their own tablespace
	/// This value only affects database IO
	private boolean dedicatedParticipation = false;
	
	private boolean autoCreateForeignReference = true;
	
	private String version = null;
	
	public ModelSchema() {
		
	}
	
	public String getShortName() {
		return shortName;
	}

	public void setShortName(String shortName) {
		this.shortName = shortName;
	}

	public List<String> getLikeInherits() {
		return likeInherits;
	}

	public void setLikeInherits(List<String> likeInherits) {
		this.likeInherits = likeInherits;
	}

	public boolean isAutoCreateForeignReference() {
		return autoCreateForeignReference;
	}

	public void setAutoCreateForeignReference(boolean autoCreateForeignReference) {
		this.autoCreateForeignReference = autoCreateForeignReference;
	}

	public boolean isDedicatedParticipation() {
		return dedicatedParticipation;
	}

	public void setDedicatedParticipation(boolean dedicatedParticipation) {
		this.dedicatedParticipation = dedicatedParticipation;
	}

	public String getVersion() {
		if(version == null) {
			return "0.1";
		}
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
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

	public List<String> getCategories() {
		return categories;
	}

	public void setCategories(List<String> categories) {
		this.categories = categories;
	}

	public ModelValidation getValidation() {
		return validation;
	}

	public void setValidation(ModelValidation validation) {
		this.validation = validation;
	}

	public ModelIO getIo() {
		return io;
	}

	public void setIo(ModelIO io) {
		this.io = io;
	}

	public List<String> getQuery() {
		return query;
	}

	public void setQuery(List<String> query) {
		this.query = query;
	}

	public ModelAccess getAccess() {
		return access;
	}

	public void setAccess(ModelAccess access) {
		this.access = access;
	}

	public List<String> getIoConstraints() {
		return ioConstraints;
	}

	public void setIoConstraints(List<String> ioConstraints) {
		this.ioConstraints = ioConstraints;
	}

	public List<String> getConstraints() {
		return constraints;
	}

	public boolean isFollowReference() {
		return followReference;
	}

	public void setFollowReference(boolean followReference) {
		this.followReference = followReference;
	}

	public void setConstraints(List<String> constraints) {
		this.constraints = constraints;
	}	

	public List<String> getHints() {
		return hints;
	}

	public void setHints(List<String> hints) {
		this.hints = hints;
	}

	public boolean isEphemeral() {
		return ephemeral;
	}

	public void setEphemeral(boolean ephemeral) {
		this.ephemeral = ephemeral;
	}

	public String getFactory() {
		return factory;
	}

	public void setFactory(String factory) {
		this.factory = factory;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getGroup() {
		return group;
	}

	public void setGroup(String group) {
		this.group = group;
	}

	public void setImplements(List<String> list) {
		implist = list;
	}
	
	public List<String> getImplements() {
		return implist;
	}

	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}

	@JsonProperty("abstract")
	public boolean isAbs() {
		return abs;
	}
	
	@JsonProperty("abstract")
	public void setAbs(boolean abs) {
		this.abs = abs;
	}
	
	@JsonIgnore
	public boolean hasField(String name) {
		return (getFieldSchema(name) != null);
	}
	
	@JsonIgnore
	public FieldSchema getFieldSchema(String name) {
		Optional<FieldSchema> olft = fields.stream().filter(o -> o.getName().equals(name)).findFirst();
		if(olft.isPresent()) {
			return olft.get();
		}
		return null;
	}
	
	public boolean isEmitModel() {
		return emitModel;
	}

	public void setEmitModel(boolean emitModel) {
		this.emitModel = emitModel;
	}

	public List<FieldSchema> getFields() {
		return fields;
	}
	public void setFields(List<FieldSchema> fields) {
		this.fields = fields;
	}
	
	@JsonIgnore
	public boolean inherits(String name){
		return RecordUtil.inherits(this, name);
	}
	
	public List<String> getInherits() {
		return inherits;
	}
	public void setInherits(List<String> inherits) {
		this.inherits = inherits;
	}

}
