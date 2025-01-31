package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.security.AuthorizationUtil;
import org.junit.Test;

public class TestAuthorization extends BaseTest{

	@Test
	public void TestAuthorizationSchema() {
		
		logger.info("Test authorization schema");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Authorization");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		EntitlementTest et = new EntitlementTest(ioContext, testUser1);
		
		assertNotNull("Person is null", et.getPerson());
		
		AuthorizationUtil autil = ioContext.getAuthorizationUtil();
		autil.createAuthorizationSchema();
	}
}

class EntitlementTest{
	private BaseRecord application = null;
	private BaseRecord readPermission = null;
	private BaseRecord writePermission = null;
	private BaseRecord departmentRole = null;
	private BaseRecord businessRole = null;
	private BaseRecord applicationRole1 = null;
	private BaseRecord applicationRole2 = null;
	private BaseRecord person = null;
	private BaseRecord account = null;
	
	public EntitlementTest(IOContext ctx, BaseRecord user) {
		configureTestBase(ctx, user);
	}
	
	private void configureTestBase(IOContext ctx, BaseRecord user) {
		
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		
		BaseRecord bperm = ctx.getPathUtil().makePath(user, ModelNames.MODEL_PERMISSION, "~/Entitlements", PermissionEnumType.APPLICATION.toString(), orgId);
		Query pmq = QueryUtil.createQuery(ModelNames.MODEL_PERMISSION, FieldNames.FIELD_PARENT_ID, bperm.get(FieldNames.FIELD_ID));
		int pmcount = ctx.getAccessPoint().count(user, pmq);
		String permissionName = "Test Permission " + (pmcount + 1);
		readPermission = ctx.getPathUtil().makePath(user, ModelNames.MODEL_PERMISSION, "~/Entitlements/" + permissionName + "/Read", PermissionEnumType.APPLICATION.toString(), orgId);
		writePermission = ctx.getPathUtil().makePath(user, ModelNames.MODEL_PERMISSION, "~/Entitlements/" + permissionName + "/Write", PermissionEnumType.APPLICATION.toString(), orgId);
		
		BaseRecord brole = ctx.getPathUtil().makePath(user, ModelNames.MODEL_ROLE, "~/Roles", RoleEnumType.PERSON.toString(), orgId);
		Query rq = QueryUtil.createQuery(ModelNames.MODEL_ROLE, FieldNames.FIELD_PARENT_ID, brole.get(FieldNames.FIELD_ID));
		int rcount = ctx.getAccessPoint().count(user, rq);
		String roleName = "Test Role " + (rcount + 1);
		
		departmentRole = ctx.getPathUtil().makePath(user, ModelNames.MODEL_ROLE, "~/Roles/" + roleName + "/Department", RoleEnumType.PERSON.toString(), orgId);
		businessRole = ctx.getPathUtil().makePath(user, ModelNames.MODEL_ROLE, "~/Roles/" + roleName + "/Department/Business", RoleEnumType.PERSON.toString(), orgId);
		applicationRole1 = ctx.getPathUtil().makePath(user, ModelNames.MODEL_ROLE, "~/Roles/" + roleName + "/Department/Business/Application 1", RoleEnumType.PERSON.toString(), orgId);
		applicationRole2 = ctx.getPathUtil().makePath(user, ModelNames.MODEL_ROLE, "~/Roles/" + roleName + "/Department/Business/Application 2", RoleEnumType.PERSON.toString(), orgId);

		BaseRecord dir = ctx.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Persons", GroupEnumType.DATA.toString(), orgId);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		int pcount = ctx.getAccessPoint().count(user, pq);
		String personName = "Test Person " + (pcount + 1);
		person = ctx.getFactory().getCreateDirectoryModel(user, ModelNames.MODEL_PERSON, personName, "~/Persons", orgId);
		
		BaseRecord adir = ctx.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Accounts", GroupEnumType.DATA.toString(), orgId);
		Query aq = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_GROUP_ID, adir.get(FieldNames.FIELD_ID));
		int acount = ctx.getAccessPoint().count(user, aq);
		String accountName = "Test Account " + (acount + 1);
		account = ctx.getFactory().getCreateDirectoryModel(user, ModelNames.MODEL_ACCOUNT, accountName, "~/Accounts", orgId);


		BaseRecord apdir = ctx.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Applications", GroupEnumType.DATA.toString(), orgId);
		Query apq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, apdir.get(FieldNames.FIELD_ID));
		int apcount = ctx.getAccessPoint().count(user, apq);
		String appName = "Test Application " + (apcount + 1);
		application = ctx.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Applications/" + appName, GroupEnumType.DATA.toString(), orgId);

		
		List<BaseRecord> accounts = person.get(FieldNames.FIELD_ACCOUNTS);
		accounts.add(account);
		ctx.getRecordUtil().updateRecord(person);
		
		ctx.getMemberUtil().member(user, businessRole, person, null, true);
		ctx.getMemberUtil().member(user, applicationRole1, account, null, true);
		ctx.getMemberUtil().member(user, application, applicationRole1, readPermission, true);
		
	}

	
	
	public BaseRecord getAccount() {
		return account;
	}

	public BaseRecord getReadPermission() {
		return readPermission;
	}

	public BaseRecord getWritePermission() {
		return writePermission;
	}

	public BaseRecord getDepartmentRole() {
		return departmentRole;
	}

	public BaseRecord getBusinessRole() {
		return businessRole;
	}

	public BaseRecord getApplicationRole1() {
		return applicationRole1;
	}

	public BaseRecord getApplicationRole2() {
		return applicationRole2;
	}

	public BaseRecord getPerson() {
		return person;
	}
	
	
	
}
