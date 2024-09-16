package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
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
	public void TestFieldParticipationModel() {
		logger.info("Testing field level participation support");
		// logger.info(ioContext.getDbUtil().generateSchema(RecordFactory.getSchema(ModelNames.MODEL_GROUP)));
		if(ioContext.getIoType() == RecordIO.FILE) {
			logger.error("****** TODO: The file system support for foreign lists needs to be updated to link the encoded foreign keyed lists to the participation table");
			logger.error("****** TODO: The database approach only uses the participation table, while the file-based system used a foreign key list on the persisted object");
			return;
		}

		String[] fields = RecordUtil.getPossibleFields(ModelNames.MODEL_CREDENTIAL, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_TYPE, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID});
		logger.info("Credential possible: " + String.join(", ", fields));
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Revised Population");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String testPerson1 = "Demo Person 1 " + UUID.randomUUID().toString();
		String testPerson2 = "Demo Person 2 " + UUID.randomUUID().toString();
		String testPerson3 = "Demo Person 3 " + UUID.randomUUID().toString();
		BaseRecord per1 = mf.getCreateDirectoryModel(testUser1, ModelNames.MODEL_PERSON, testPerson1, "~/Persons", testOrgContext.getOrganizationId());
		// per1.set(FieldNames.FIELD_DESCRIPTION, "Example description");
		
		
		BaseRecord per2 = mf.getCreateDirectoryModel(testUser1, ModelNames.MODEL_PERSON, testPerson2, "~/Persons", testOrgContext.getOrganizationId());
		
		
		/*
		List<BaseRecord> p1d = per1.get(FieldNames.FIELD_DEPENDENTS);
		p1d.add(per2);
		ioContext.getAccessPoint().update(testUser1, per1);
		*/
		
		BaseRecord per3 = mf.getCreateDirectoryModel(testUser1, ModelNames.MODEL_PERSON, testPerson3, "~/Persons", testOrgContext.getOrganizationId());
		
		BaseRecord vper3 = ioContext.getRecordUtil().findByRecord(null, per3, new String[] {FieldNames.FIELD_NAME});
		assertNotNull("VPer3 is null", vper3);
		assertTrue("Expected only one field - name - Received " + vper3.getFields().size(), vper3.getFields().size() == 1);
		
		ioContext.getMemberUtil().member(testUser1, per1, FieldNames.FIELD_PARTNERS, per3, null, true);
		boolean enable = ioContext.getMemberUtil().member(testUser1, per1, FieldNames.FIELD_DEPENDENTS, per2, null, true);
		assertTrue("Expected to add dependent", enable);
		boolean ismem = ioContext.getMemberUtil().isMember(per2, per1, FieldNames.FIELD_DEPENDENTS);
		assertTrue("Expected to be dependent", ismem);

		/// If not specifying which fields to request, only the 'common query' fields will be returned
		///
		
		BaseRecord xper1 = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_PERSON, per1.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("Person is null", xper1);

		logger.info(xper1.toFullString());

		
		/// Get everything
		/*
		Query pq1 = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_OBJECT_ID, xper1.get(FieldNames.FIELD_OBJECT_ID));
		pq1.setRequest(RecordUtil.getFieldNames(ModelNames.MODEL_PERSON));
		BaseRecord xper2 = ioContext.getAccessPoint().find(testUser1, pq1);
		assertNotNull("Person is null", xper2);
		logger.info(xper2.toFullString());
		*/
		CacheUtil.clearCache();
		ioContext.getRecordUtil().populate(xper1, new String[] {FieldNames.FIELD_DEPENDENTS});
		List<BaseRecord> depends = xper1.get(FieldNames.FIELD_DEPENDENTS);
		assertTrue("Expected one dependent", depends.size() > 0);
		logger.info(xper1.toFullString());


	}

	@Test
	public void TestRevisedPopulation() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Revised Population");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String groupPath = "~/Random Group " + UUID.randomUUID().toString();
		//BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, "DATA", testOrgContext.getOrganizationId());
		String dataName = "Random data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, dataName);
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
			
			//logger.info(data.toFullString());
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
			data.set(FieldNames.FIELD_NAME, dataName);
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
			Query pq1 = QueryUtil.createQuery("revisedField", FieldNames.FIELD_OBJECT_ID, ndata.get(FieldNames.FIELD_OBJECT_ID));
			// pq1.setRequest(RecordUtil.getCommonFields("revisedField"));
			odata = ioContext.getAccessPoint().find(testUser1, pq1);
			
			assertNotNull("Data is null", odata);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException  e) {
			logger.error(e);
			error = true;
		}
		assertFalse("An error occurred", error);
		

	}
}
