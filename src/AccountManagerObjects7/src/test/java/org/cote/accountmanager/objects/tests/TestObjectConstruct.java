package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestObjectConstruct extends BaseTest {

	@Test
	public void TestObjectTemplate() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		
		// logger.info(testUser2.toString());
		BaseRecord dataTemplate = null;
		BaseRecord newData = null;
		BaseRecord homeDir = testUser1.get(FieldNames.FIELD_HOME_DIRECTORY);
		/*
		ioContext.getRecordUtil().populate(homeDir);
		BaseRecord permission = ioContext.getPathUtil().findPath(null, ModelNames.MODEL_PERMISSION, "/Update", "DATA", testOrgContext.getOrganizationId());
		boolean authZ = ioContext.getAuthorizationUtil().checkEntitlement(testUser2, permission, homeDir);
		logger.info("Auth: " + authZ);
		*/
		
		/*
		PolicyResponseType prr0 = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(testUser2, PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, testUser2, homeDir);
		logger.info(prr0.toString());
		*/
		/*
		prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(testUser1, PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, testUser2, homeDir);
		logger.info(JSONUtil.exportObject(prr, RecordSerializerConfig.getUnfilteredModule()));
		*/
		
		
		try {

			logger.info("Create data template");
			dataTemplate = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			dataTemplate.set(FieldNames.FIELD_NAME, "Test Data");
			String upath = "~/Demo Data - " + UUID.randomUUID().toString();
			dataTemplate.set(FieldNames.FIELD_GROUP_PATH, upath);
			
			logger.info("Create new data #1");
			newData = mf.newInstance(ModelNames.MODEL_DATA, testUser1, dataTemplate, null);
			// logger.info(newData.toString());

			BaseRecord group = ioContext.getPathUtil().findPath(testUser1, ModelNames.MODEL_GROUP, upath, GroupEnumType.DATA.toString(), testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
			assertNotNull("Group is null", group);
			
			logger.info("Check create policy on data #1");
			PolicyResponseType prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(testUser1, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, testUser1, newData);
			logger.info(prr.toString());

			/// Try to put data for testUser2 in testUser1's directory
			
			/*
			logger.info("Create new data #2");
			dataTemplate.set(FieldNames.FIELD_GROUP_PATH, "/home/testUser1/Demo Data - " + UUID.randomUUID().toString());
			newData = mf.newInstance(ModelNames.MODEL_DATA, testUser2, dataTemplate, null);
			logger.info(newData.toString());
			logger.info("Check create policy on data #2");
			prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(testUser2, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, testUser2, newData);
			logger.info(prr.toString());
			*/
			
		} catch (FieldException | ModelNotFoundException | FactoryException | ValueException e) {
			logger.error(e);
			
		}
		
	}
	
}
