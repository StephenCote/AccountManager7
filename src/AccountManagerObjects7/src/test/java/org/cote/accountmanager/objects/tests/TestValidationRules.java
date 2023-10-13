package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.util.Strings;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordValidator;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.security.AuthorizationUtil;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.ValidationUtil;
import org.cote.accountmanager.validator.HierarchyValidator;
import org.junit.Test;

public class TestValidationRules extends BaseTest {
	
	@Test
	public void TestFunctionRule() {
		logger.info("Test function rule");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Validation Rules");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		try {
			BaseRecord parentDir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Parent", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
			BaseRecord childDir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Parent/Child", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
			BaseRecord grandDir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Parent/Child/GrandChild", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
	
			grandDir.set(FieldNames.FIELD_DESCRIPTION, "New description - " + UUID.randomUUID().toString());
			
			//ModelSchema schema = RecordFactory.getSchema(ModelNames.MODEL_GROUP);
			// logger.info(Strings.join(schema.getImplements(), ','));

			ioContext.getAccessPoint().update(testUser1, grandDir);
			
			parentDir.set(FieldNames.FIELD_PARENT_ID, grandDir.get(FieldNames.FIELD_ID));
			logger.info("In parent: " + HierarchyValidator.checkHierarchy(parentDir, FieldNames.FIELD_PARENT_ID));
			
		}
		catch(StackOverflowError | Exception e) {
			e.printStackTrace();
		}
		
	}

	
	@Test
	public void TestFieldValidation() {
		logger.info("Testing field validation.  Validation errors in the log are expected in this test.");
		BaseRecord data = null;
		BaseRecord user = null;
		try {
			data = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			data.set(FieldNames.FIELD_NAME, "   This   ");
			boolean valid = RecordValidator.validate(data);
			assertTrue("Expected data to be valid", valid);
			assertTrue("Expected name value to be trimmed", "This".equals(data.get(FieldNames.FIELD_NAME)));
			
			
			user = RecordFactory.newInstance(ModelNames.MODEL_USER);
			user.set(FieldNames.FIELD_NAME, "   This   ");

			valid = RecordValidator.validate(user);
			assertFalse("Expected user to be invalid", valid);
			assertTrue("Expected name value to be trimmed", "This".equals(user.get(FieldNames.FIELD_NAME)));
			
			user.set(FieldNames.FIELD_NAME, "   This2  ");
			valid = RecordValidator.validate(user);
			assertTrue("Expected user to be valid", valid);
			assertTrue("Expected name value to be trimmed", "This2".equals(user.get(FieldNames.FIELD_NAME)));

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	
	@Test
	public void TestSystemRules() {
		logger.info("Test system rules");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Validation Rules");
		Factory mf = ioContext.getFactory();
		IPath pu = ioContext.getPathUtil();
		MemberUtil mu = ioContext.getMemberUtil();
		AuthorizationUtil au = ioContext.getAuthorizationUtil();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord ops = testOrgContext.getOpsUser();
		BaseRecord admin = testOrgContext.getAdminUser();
		BaseRecord dir = ioContext.getPathUtil().makePath(admin, ModelNames.MODEL_GROUP, "/Validation Rules", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		if(ioContext.getAuthorizationUtil().canRead(admin, ops, dir).getType() != PolicyResponseEnumType.PERMIT) {
			BaseRecord usersRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS, RoleEnumType.USER.toString(), testOrgContext.getOrganizationId());
			BaseRecord rperm1 = pu.findPath(admin, ModelNames.MODEL_PERMISSION, "/Read", "DATA", testOrgContext.getOrganizationId());
			BaseRecord cperm1 = pu.findPath(admin, ModelNames.MODEL_PERMISSION, "/Create", "DATA", testOrgContext.getOrganizationId());
			BaseRecord uperm1 = pu.findPath(admin, ModelNames.MODEL_PERMISSION, "/Update", "DATA", testOrgContext.getOrganizationId());
			BaseRecord dperm1 = pu.findPath(admin, ModelNames.MODEL_PERMISSION, "/Delete", "DATA", testOrgContext.getOrganizationId());
			mu.member(admin, dir, ops, rperm1, true);
			mu.member(admin, dir, ops, cperm1, true);
			mu.member(admin, dir, ops, uperm1, true);
			mu.member(admin, dir, ops, dperm1, true);
			mu.member(admin, dir, usersRole, rperm1, true);
		}
		
		assertTrue("Ops user is not authorized to create", au.canUpdate(ops, ops, dir).getType() == PolicyResponseEnumType.PERMIT);
			
		Query query = QueryUtil.createQuery(ModelNames.MODEL_VALIDATION_RULE, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		QueryResult rec = ioContext.getAccessPoint().list(ops, query);
		logger.info("Cleaning up " + rec.getResults().length);
		for(BaseRecord rc : rec.getResults()) {
			ioContext.getAccessPoint().delete(ops, rc);
		}
		
		BaseRecord rule = ValidationUtil.getRule("$notEmpty").copyRecord();
		try {
			BaseRecord crule = recursiveCreate(ops, dir, rule);
			assertNotNull("Created rule is null", crule);
			BaseRecord lrule = ioContext.getAccessPoint().findByObjectId(ops, ModelNames.MODEL_VALIDATION_RULE, crule.get(FieldNames.FIELD_OBJECT_ID));
			assertNotNull("Rule is null", lrule);
		} catch (NullPointerException | ClassCastException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 

	}
	
	private BaseRecord recursiveCreate(BaseRecord owner, BaseRecord dir, BaseRecord obj) {

		applyGroupOwnership(owner, dir, obj);
		ModelSchema schema = RecordFactory.getSchema(obj.getModel());
		for(FieldType f : obj.getFields()) {
			FieldSchema fs = schema.getFieldSchema(f.getName());
			//List<BaseRecord> cobjs = new ArrayList<>();
			if(fs.isForeign()) {
				if(f.getValueType() == FieldEnumType.MODEL && f.getValue() != null) {
					try {
						obj.set(f.getName(), recursiveCreate(owner, dir, obj.get(f.getName())));
					} catch (FieldException | ValueException | ModelNotFoundException e) {
						logger.error(e);
					}
					//cobjs.add(obj.get(f.getName()));
				}
				else if(f.getValueType() == FieldEnumType.LIST) {
					List<BaseRecord> lst = obj.get(f.getName());
					List<BaseRecord> nlst = new ArrayList<>();
					for(BaseRecord o : lst) {
						nlst.add(recursiveCreate(owner, dir, o));
					}
					lst.clear();
					lst.addAll(nlst);
					
				}
			}
		}
		 return ioContext.getAccessPoint().create(owner, obj);

	}
	
	private void applyGroupOwnership(BaseRecord owner, BaseRecord dir, BaseRecord obj) {
		try {
			ioContext.getRecordUtil().applyOwnership(owner, obj, owner.get(FieldNames.FIELD_ORGANIZATION_ID));
			obj.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			obj.set(FieldNames.FIELD_GROUP_PATH, dir.get(FieldNames.FIELD_PATH));
			ModelSchema schema = RecordFactory.getSchema(obj.getModel());
			for(FieldType f : obj.getFields()) {
				FieldSchema fs = schema.getFieldSchema(f.getName());
				if(fs.isForeign()) {
					List<BaseRecord> objects = new ArrayList<>();
					if(fs.getFieldType() == FieldEnumType.LIST) {
						objects = obj.get(f.getName());
					}
					else if(fs.getFieldType() == FieldEnumType.MODEL) {
						objects.add(obj.get(f.getName()));
					}
					else {
						logger.error("Unhandled field type: " + fs.getFieldType().toString());
					}
					for(BaseRecord linkedObj : objects) {
						applyGroupOwnership(owner, dir, linkedObj);
					}

				}
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
}
