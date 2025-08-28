package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
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
			//logger.info(strAtr.toFullString());
			assertTrue("Failed to create test attribute with string", IOSystem.getActiveContext().getRecordUtil().createRecord(strAtr));

			int intVal = 123;
			BaseRecord intAtr = AttributeUtil.addAttribute(data, "test int", intVal);
			assertTrue("Failed to create test attribute with int", IOSystem.getActiveContext().getRecordUtil().createRecord(intAtr));
			logger.info("Created: " + intAtr.toFullString());
			
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
			
			int chkIntVal= chkIntItr.get(FieldNames.FIELD_VALUE);
			assertTrue("Integer attribute value incorrect", chkIntVal == intVal);
			
			CacheUtil.clearCache();
			
			//data = this.getCreateData(testUser1, dataName, "~/Data", "Test data");
			Query q = new Query(ModelNames.MODEL_DATA);
			q.field(FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
			q.planMost(true);
			q.getRequest().add(FieldNames.FIELD_ATTRIBUTES);
			
			data = IOSystem.getActiveContext().getSearch().findRecord(q);
			assertNotNull("Data is null", data);
			//IOSystem.getActiveContext().getReader().populate(data, new String[] {FieldNames.FIELD_ATTRIBUTES});

			BaseRecord intAttr = AttributeUtil.getAttribute(data, "test int");
			assertNotNull("Integer attribute was null", intAttr);
			
			int intAttrVal = AttributeUtil.getAttributeValue(data, "test int", 0);
			assertTrue("Integer attribute value incorrect", intAttrVal == intVal);
			
			
			Query dq = QueryUtil.createQuery(intAttr.getSchema(), FieldNames.FIELD_OBJECT_ID, intAttr.get(FieldNames.FIELD_OBJECT_ID));
			ModelSchema ms = RecordFactory.getSchema(intAttr.getSchema());
			/*
			String[] pfields = new String[] {
					FieldNames.FIELD_ID,
					FieldNames.FIELD_OWNER_ID,
					FieldNames.FIELD_PARENT_ID,
					FieldNames.FIELD_GROUP_ID,
					FieldNames.FIELD_OBJECT_ID,
					FieldNames.FIELD_URN,
					FieldNames.FIELD_ORGANIZATION_ID
			};
			List<String> fields = new ArrayList<>();
			for(String pf: pfields) {
				if(ms.hasField(pf)) {
					fields.add(pf);
				}
			}
			dq.setRequest(fields.toArray(new String[0]));
			*/
			IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
			logger.info("Query: " + dq.toString());
			logger.info(Arrays.asList(RecordUtil.getCommonFields(ModelNames.MODEL_ATTRIBUTE)).stream().collect(Collectors.joining(", ")));
			BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(testUser1, dq);
			assertNotNull("Failed to retrieve attribute by query", rec);
			logger.info("Delete Attribute: " + rec.toFullString());
			//boolean del = IOSystem.getActiveContext().getAccessPoint().delete(testUser1, intAttr);
			boolean del = IOSystem.getActiveContext().getAccessPoint().delete(testUser1, rec);
			assertTrue("Failed to delete integer attribute", del);
			IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
		
		} catch (ClassCastException | ModelException | FieldException | ModelNotFoundException | ValueException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
