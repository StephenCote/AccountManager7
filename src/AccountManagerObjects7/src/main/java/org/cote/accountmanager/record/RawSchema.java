package org.cote.accountmanager.record;

public class RawSchema {

	private String name = null;
	private String model = null;
	public RawSchema() {
		
	}
	public RawSchema(String inName, String inModel) {
		name = inName;
		model = inModel;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getModel() {
		return model;
	}
	public void setModel(String model) {
		this.model = model;
	}
}
