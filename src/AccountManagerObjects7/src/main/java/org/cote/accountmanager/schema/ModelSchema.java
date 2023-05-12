package org.cote.accountmanager.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ModelSchema {
	public static final Logger logger = LogManager.getLogger(ModelSchema.class);
	private String name = null;
	
	@JsonProperty("absolute")	
	private boolean abs = false;
	
	private boolean ephemeral = false;
	private boolean followReference = true;
	private boolean emitModel = false;
	
	private String group = null;
	private String provider = null;

	//private String baseClass = null;
	private List<FieldSchema> fields = new ArrayList<>();
	private List<String> inherits = new ArrayList<>();
	private List<String> implist = new ArrayList<>();
	// private List<String> values = new ArrayList<>();
	private List<String> constraints = new ArrayList<>();
	private List<String> ioConstraints = new ArrayList<>();
	private List<String> query = new ArrayList<>();
	private List<String> hints = new ArrayList<>();
	private String factory = null;
	
	private ModelAccess access = null;
	
	public ModelSchema() {
		
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
	public List<String> getInherits() {
		return inherits;
	}
	public void setInherits(List<String> inherits) {
		this.inherits = inherits;
	}
	/*
	public String getBaseClass() {
		return baseClass;
	}
	public void setBaseClass(String baseClass) {
		this.baseClass = baseClass;
	}
	*/
	/*
	public List<String> getValues() {
		return values;
	}
	public void setValues(List<String> values) {
		this.values = values;
	}
	*/
	
	
}
