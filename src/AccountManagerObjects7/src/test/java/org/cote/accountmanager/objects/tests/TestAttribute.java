package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestAttribute extends BaseTest {
	
	@Test
	public void TestAttribute() {
		logger.info("Test Attribute Create");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataName = "Test Attribute Data - " + UUID.randomUUID().toString();
		BaseRecord data = this.getCreateData(testUser1, dataName, "~/Data", "Test data");
		assertNotNull("Data is null", data);
		try {
			BaseRecord strAtr = AttributeUtil.addAttribute(data, "test string", "string value");
			logger.info(strAtr.toFullString());
			assertTrue("Failed to create test attribute with string", IOSystem.getActiveContext().getRecordUtil().createRecord(strAtr));

			int intVal = 123;
			BaseRecord intAtr = AttributeUtil.addAttribute(data, "test int", intVal);
			assertTrue("Failed to create test attribute with int", IOSystem.getActiveContext().getRecordUtil().createRecord(intAtr));
			
			
			String intValStr = """
			 {
				"schema": "common.attribute",
			    "id" : 35,
			    "name" : "test int",
			    "objectId" : "e3362e0b-6375-4819-b37a-e6b0fdd75dcc",
			    "organizationId" : 4,
			    "organizationPath" : "/Development/Data",
			    "valueType" : "int",
			    "value" : "123"
			  }		
			""";
			BaseRecord chkIntItr = JSONUtil.importObject(intValStr, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			logger.info("Imported: " + chkIntItr.toFullString());
			
			/*
			CacheUtil.clearCache();
			
			
			
			//data = this.getCreateData(testUser1, dataName, "~/Data", "Test data");
			Query q = new Query(ModelNames.MODEL_DATA);
			q.field(FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
			q.planMost(true);
			q.getRequest().add(FieldNames.FIELD_ATTRIBUTES);
			
			logger.info("RETRIEVE DATA WITH ATTRIBUTES");
			logger.info(q.toSelect());
			data = IOSystem.getActiveContext().getSearch().findRecord(q);
			assertNotNull("Data is null", data);
			//IOSystem.getActiveContext().getReader().populate(data, new String[] {FieldNames.FIELD_ATTRIBUTES});
			logger.info(data.toFullString());
			*/

		
		} catch (ClassCastException | ModelException | FieldException | ModelNotFoundException | ValueException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
