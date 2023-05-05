package org.cote.accountmanager.schema;

public class ModelAccess {
	private ModelAccessPolicies policies = null;
	private ModelAccessRoles roles = null;
	
	public ModelAccess() {
		
	}

	public ModelAccessPolicies getPolicies() {
		return policies;
	}

	public void setPolicies(ModelAccessPolicies policies) {
		this.policies = policies;
	}

	public ModelAccessRoles getRoles() {
		return roles;
	}

	public void setRoles(ModelAccessRoles roles) {
		this.roles = roles;
	}
	
	
	
}
