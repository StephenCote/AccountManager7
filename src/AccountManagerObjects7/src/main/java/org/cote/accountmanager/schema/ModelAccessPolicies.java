package org.cote.accountmanager.schema;

import java.util.Arrays;

import org.cote.accountmanager.policy.PolicyUtil;

public class ModelAccessPolicies extends ModelAccessRoles {
	private ModelAccessPolicyBind bind = null;
	
	public ModelAccessPolicies() {
		setCreate(Arrays.asList(PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT));
		setUpdate(Arrays.asList(PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT));
		setDelete(Arrays.asList(PolicyUtil.POLICY_SYSTEM_DELETE_OBJECT));
		setRead(Arrays.asList(PolicyUtil.POLICY_SYSTEM_READ_OBJECT));
		setExecute(Arrays.asList(PolicyUtil.POLICY_SYSTEM_EXECUTE_OBJECT));
	}

	public ModelAccessPolicyBind getBind() {
		return bind;
	}

	public void setBind(ModelAccessPolicyBind bind) {
		this.bind = bind;
	}
	
	
	
}
