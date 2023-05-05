package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.junit.Test;

public class TestJournal extends BaseTest {
	private String testJournalName = "Demo Journaled Object - " + UUID.randomUUID().toString();

	@Test
	public void TestPatch() {
		
		logger.info("Test Patch Journal");
		
		String dataName = "Patch Dataset - " + UUID.randomUUID().toString();
		
		resetIO(null);
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
		BaseRecord group = ioContext.getPathUtil().makePath(journalUser, "group", "~/JournalTest", "DATA", testOrgContext.getOrganizationId());
		//BaseRecord data = this.getCreateData(journalUser, "Patch Dataset", "text/plain", "The data to patch".getBytes(), "~/JournalTest", journalOrganization.get(FieldNames.FIELD_ID));
		BaseRecord data = null;
		
		try {
			data = RecordFactory.model("journalObject").newInstance();
			data.set(FieldNames.FIELD_NAME, dataName);
			data.set(FieldNames.FIELD_MIME_TYPE,  "text/plain");
			data.set(FieldNames.FIELD_BYTE_STORE, "The data to patch".getBytes());
			data.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			data.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
			ioContext.getRecordUtil().createRecord(data, true);

			
			BaseRecord jour1 = data.get(FieldNames.FIELD_JOURNAL);
			long jid = jour1.get(FieldNames.FIELD_ID);
			logger.info("Looking for journal #" + jid);
			//IndexEntry[] entries = IOSystem.getActiveContext().getIndexManager().getInstance(ModelNames.MODEL_JOURNAL).findIndexEntriesDEPRECATE(jid, 0L, 0L, 0L, null, null, null, null);
			Query q = QueryUtil.createQuery(ModelNames.MODEL_JOURNAL, FieldNames.FIELD_ID, jid);
			IndexEntry[] entries = IOSystem.getActiveContext().getIndexManager().getInstance(ModelNames.MODEL_JOURNAL).findIndexEntries(q);
			logger.info("Index count: " + entries.length);
			// logger.info(JSONUtil.exportObject(jour1, RecordSerializerConfig.getUnfilteredModule()));		
			
			BaseRecord dataPatch = ioContext.getRecordUtil().getRecord(journalUser, "journalObject", data.get(FieldNames.FIELD_NAME), -1, "~/JournalTest");
			assertNotNull("Patch data is null");
//			logger.info(JSONUtil.exportObject(dataPatch, RecordSerializerConfig.getUnfilteredModule()));
			
			
//			BaseRecord dataPatch = data.copyRecord(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_DESCRIPTION});
			try {
				dataPatch.set(FieldNames.FIELD_DESCRIPTION, UUID.randomUUID().toString() + " description!");
				// logger.info(JSONUtil.exportObject(dataPatch, RecordSerializerConfig.getUnfilteredModule()));
				boolean updated = ioContext.getRecordUtil().updateRecord(dataPatch);
				assertTrue("Expected the record to be updated", updated);
			} catch (Exception e) {
				logger.error(e);
				
			}
			//logger.info(JSONUtil.exportObject(journalUser, RecordSerializerConfig.getUnfilteredModule()));

			
		} catch (FieldException | ModelNotFoundException | ValueException | IndexException e1) {
			logger.error(e1);
			e1.printStackTrace();
		}
	}
	
	
	@Test
	public void TestAltOrgJournal() {
		
		logger.info("Test Alt Org Joural");
		
		resetIO(null);
		
		RecordFactory.importSchema("journalObject");
		
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
		BaseRecord group = ioContext.getPathUtil().makePath(journalUser, "group", "~/JournalTest", "DATA", testOrgContext.getOrganizationId());
		assertNotNull("Group is null", group);
		BaseRecord job1 = null;
		BaseRecord jour1 = null;
		try {
			job1 = ioContext.getRecordUtil().getRecord(journalUser, "journalObject", testJournalName, 0L, group.get(FieldNames.FIELD_ID), testOrgContext.getOrganizationId());
			if(job1 == null) {
				job1 = RecordFactory.model("journalObject").newInstance();
				job1.set(FieldNames.FIELD_NAME, testJournalName);
				job1.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
				job1.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());

				assertTrue("Failed to create record", ioContext.getRecordUtil().createRecord(job1, true));
				job1 = ioContext.getRecordUtil().getRecord(journalUser, "journalObject", testJournalName, 0L, group.get(FieldNames.FIELD_ID), testOrgContext.getOrganizationId());
			}
			assertNotNull("JOB1 is null", job1);
			
		} catch (IndexOutOfBoundsException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	
	

	
	

	
	
}
