package org.cote.accountmanager.schema;

public class ModelAccessPolicyBind {

	private String objectId = null;
	private String objectModel = null;
	private String model = null;
	private String description = null;
	
	public ModelAccessPolicyBind() {
		
	}

	public String getObjectModel() {
		return objectModel;
	}

	public void setObjectModel(String objectModel) {
		this.objectModel = objectModel;
	}

	public String getObjectId() {
		return objectId;
	}

	public void setObjectId(String objectId) {
		this.objectId = objectId;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}
	
	
}
