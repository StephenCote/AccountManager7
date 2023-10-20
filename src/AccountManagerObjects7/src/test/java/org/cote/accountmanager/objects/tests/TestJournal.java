package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.AttributeUtil;
import org.junit.Test;

public class TestJournal extends BaseTest {
	private String testJournalName = "Demo Journaled Object - " + UUID.randomUUID().toString();
	
	@Test
	public void TestDynamicModel() {
		logger.info("Test dynamic model");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataName = "Demo data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Authorize/Create");
		plist.parameter("name", dataName);
		
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("testJournal8", "journalObject");
		assertNotNull("Schema is null", ms);
		ModelNames.loadCustomModels();
		

		
		try {
			BaseRecord jrec = mf.newInstance("testJournal8", testUser1, null, plist);
			BaseRecord jrecc = ioContext.getAccessPoint().create(testUser1, jrec);
			
			BaseRecord jour1 = jrecc.get(FieldNames.FIELD_JOURNAL);
			
		} catch (FactoryException e) {
			logger.error(e);
		}
		
	}
	

	@Test
	public void TestPatch() {
		
		logger.info("Test Patch Journal");
		
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("journalObject", "journalObject");
		assertNotNull("Schema is null", ms);
		ModelNames.loadCustomModels();
		
		String dataName = "Patch Dataset - " + UUID.randomUUID().toString();
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Journal");
		Factory mf = ioContext.getFactory();
		BaseRecord journalUser = null;
		try 
		{
			journalUser = mf.getCreateUser(testOrgContext.getAdminUser(), "journalUser", testOrgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}

		assertNotNull("Organization is null", testOrgContext);
		assertNotNull("Journal user is null", journalUser);
		BaseRecord group = ioContext.getPathUtil().makePath(journalUser, ModelNames.MODEL_GROUP, "~/JournalTest", "DATA", testOrgContext.getOrganizationId());
		BaseRecord data = null;
		
		try {
			data = RecordFactory.model("journalObject").newInstance();
			ioContext.getRecordUtil().applyNameGroupOwnership(journalUser, data, dataName, "~/JournalTest", testOrgContext.getOrganizationId());
			// data.set(FieldNames.FIELD_NAME, dataName);
			data.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
			data.set(FieldNames.FIELD_BYTE_STORE, "The data to patch".getBytes());
			// data.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			// data.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
			// data.set(FieldNames.FIELD_OWNER_ID, journalUser.get(FieldNames.FIELD_ID));
			AttributeUtil.addAttribute(data, "Demo attribute", true);
			ioContext.getRecordUtil().createRecord(data, true);

			BaseRecord dataPatch = ioContext.getRecordUtil().getRecord(journalUser, "journalObject", data.get(FieldNames.FIELD_NAME), -1, "~/JournalTest");
			assertNotNull("Patch data is null");
			dataPatch.set(FieldNames.FIELD_DESCRIPTION, UUID.randomUUID().toString() + " description!");
			boolean updated = ioContext.getRecordUtil().updateRecord(dataPatch);
			assertTrue("Expected the record to be updated", updated);
			Query q = QueryUtil.createQuery(ModelNames.MODEL_JOURNAL, FieldNames.FIELD_ID, data.get(FieldNames.FIELD_JOURNAL_FIELD_ID));
			BaseRecord jour1 = ioContext.getSearch().findRecord(q);
			logger.info(jour1.toString());
			
			Query q2 = QueryUtil.createQuery("journalObject", FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			BaseRecord idata = ioContext.getSearch().findRecord(q2);
			
		} catch (ClassCastException | FieldException | ModelNotFoundException | ValueException | ModelException e1) {
			logger.error(e1);
			e1.printStackTrace();
		}
	}

	@Test
	public void TestAltOrgJournal() {
		
		logger.info("Test Alt Org Joural");

		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("journalObject", "journalObject");
		assertNotNull("Schema is null", ms);
		ModelNames.loadCustomModels();

		// RecordFactory.importSchema("journalObject");
		
		logger.info("Test journal in an alternate organization");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Journal");
		BaseRecord journalUser = null;
		Factory mf = ioContext.getFactory();
		try 
		{
			journalUser = mf.getCreateUser(testOrgContext.getAdminUser(), "journalUser", testOrgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		assertNotNull("Journal user is null", journalUser);
		BaseRecord group = ioContext.getPathUtil().makePath(journalUser, ModelNames.MODEL_GROUP, "~/JournalTest", "DATA", testOrgContext.getOrganizationId());
		assertNotNull("Group is null", group);
		BaseRecord job1 = null;
		BaseRecord jour1 = null;
		try {
			job1 = ioContext.getRecordUtil().getRecord(journalUser, "journalObject", testJournalName, 0L, group.get(FieldNames.FIELD_ID), testOrgContext.getOrganizationId());
			if(job1 == null) {
				job1 = RecordFactory.model("journalObject").newInstance();
				
				ioContext.getRecordUtil().applyNameGroupOwnership(journalUser, job1, testJournalName, "~/JournalTest", testOrgContext.getOrganizationId());
				
				// job1.set(FieldNames.FIELD_NAME, testJournalName);
				// job1.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
				// job1.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());

				assertTrue("Failed to create record", ioContext.getRecordUtil().createRecord(job1, true));
				job1 = ioContext.getRecordUtil().getRecord(journalUser, "journalObject", testJournalName, 0L, group.get(FieldNames.FIELD_ID), testOrgContext.getOrganizationId());
			}
			assertNotNull("JOB1 is null", job1);
			
		} catch (ClassCastException | IndexOutOfBoundsException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	
}
