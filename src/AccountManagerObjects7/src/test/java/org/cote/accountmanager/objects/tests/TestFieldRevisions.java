package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

public class TestFieldRevisions extends BaseTest {

	@Test
	public void TestRevisedPopulation() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Revised Population");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String groupPath = "~/Random Group " + UUID.randomUUID().toString();
		//BaseRecord group = ioContext.getPathUtil().makePath(testUser1, "group", groupPath, "DATA", testOrgContext.getOrganizationId());
		String dataName = "Random data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", dataName);
		BaseRecord data = null;
		boolean error = false;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			ByteModelUtil.setValue(data, "This is the example data that I want to store".getBytes());
			data = ioContext.getAccessPoint().create(testUser1, data);
			assertNotNull("Data is null");
			data = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_DATA, data.get(FieldNames.FIELD_OBJECT_ID));
			assertNotNull("Data is null");
			//String[] qfs = RecordUtil.getCommonFields(data.getModel());
			//logger.info(String.join(", ", qfs));
			String[] qfs = RecordUtil.getPossibleFields(ModelNames.MODEL_DATA, new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_ACCOUNTS});
			logger.info(String.join(", ", qfs));
			
			logger.info(data.toFullString());
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("An error occurred", error);
	}
	
	@Test
	public void TestZonedDateTime() {
		
		logger.info("Test Zoned Date Time");
		
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("revisedField", "revisedField");
		assertNotNull("Schema is null", ms);
		ModelNames.loadCustomModels();
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Zoned Date Time");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String dataName = "Test Revised Fields " + UUID.randomUUID().toString();
		BaseRecord data = null;
		BaseRecord ndata = null;
		BaseRecord odata = null;
		String textToEncrypt = "Encrypt this!";
		boolean error = false;
		try {
			data = ioContext.getFactory().newInstance("revisedField", testUser1, null, null);
			data.set("name", dataName);
			assertNotNull("Data is null", data);
			data.set("testDate", ZonedDateTime.now());
			data.set("testText", textToEncrypt);
			
			String ser = data.toFullString();
			
			BaseRecord tdata = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			assertNotNull("Imported data is null", tdata);
			int comp = FieldUtil.compareTo(data.getField("testDate"), tdata.getField("testDate"));
			assertTrue("Expected the dates to match", comp == 0);
			ndata = ioContext.getAccessPoint().create(testUser1, data);
			
			assertNotNull("New Data is null", ndata);
			
			odata = ioContext.getAccessPoint().find(testUser1, QueryUtil.createQuery("revisedField", FieldNames.FIELD_OBJECT_ID, ndata.get(FieldNames.FIELD_OBJECT_ID)));
			
			assertNotNull("Data is null", odata);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException  e) {
			logger.error(e);
			error = true;
		}
		assertFalse("An error occurred", error);
		

	}
}
