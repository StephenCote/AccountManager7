package org.cote.accountmanager.schema;
import java.util.ArrayList;
import java.util.List;

public class ModelValidation {
	private List<ModelRule> rules = new ArrayList<>();

	public ModelValidation() {
		
	}

	public List<ModelRule> getRules() {
		return rules;
	}

	public void setRules(List<ModelRule> rules) {
		this.rules = rules;
	}
	
	
}
