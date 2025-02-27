package org.cote.accountmanager.record;

public class RawSchema {

	private String name = null;
	private String schema = null;
	public RawSchema() {
		
	}
	
	public RawSchema(String inName, String inModel) {
		name = inName;
		schema = inModel;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getSchema() {
		return schema;
	}
	
	public void setSchema(String schema) {
		this.schema = schema;
	}
}
