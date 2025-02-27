package org.cote.accountmanager.schema;

public class ModelAccessPolicyBind {

	private String objectId = null;
	private String objectSchema = null;
	private String schema = null;
	private String description = null;
	
	public ModelAccessPolicyBind() {
		
	}

	public String getObjectSchema() {
		return objectSchema;
	}

	public void setObjectSchema(String objectSchema) {
		this.objectSchema = objectSchema;
	}

	public String getObjectId() {
		return objectId;
	}

	public void setObjectId(String objectId) {
		this.objectId = objectId;
	}

	public String getSchema() {
		return schema;
	}

	public void setSchema(String schema) {
		this.schema = schema;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}
	
	
}
