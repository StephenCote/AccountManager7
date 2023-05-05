package org.cote.accountmanager.objects.tests;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class TestContext {
	private BaseRecord organization = null;
	private BaseRecord adminUser = null;
	private BaseRecord user = null;
	private BaseRecord userGroup = null;
	private long organizationId = 0L;
	
	public TestContext(BaseRecord org, BaseRecord admin, BaseRecord user, BaseRecord group) {
		this.organization = org;
		organizationId = organization.get(FieldNames.FIELD_ID);
		this.adminUser = admin;
		this.user = user;
		this.userGroup = group;
	}

	public BaseRecord getOrganization() {
		return organization;
	}

	public BaseRecord getAdminUser() {
		return adminUser;
	}

	public BaseRecord getUser() {
		return user;
	}

	public BaseRecord getUserGroup() {
		return userGroup;
	}
	
	public long getOrganizationId() {
		return organizationId;
	}
	
	
}
