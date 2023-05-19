package org.cote.accountmanager.schema;
import java.util.ArrayList;
import java.util.List;

public class ModelRule {
	private List<String> fields = new ArrayList<>();
	private List<String> rules = new ArrayList<>();
	
	public ModelRule() {
		
	}

	public List<String> getFields() {
		return fields;
	}

	public void setFields(List<String> fields) {
		this.fields = fields;
	}

	public List<String> getRules() {
		return rules;
	}

	public void setRules(List<String> rules) {
		this.rules = rules;
	}
	
	
}
