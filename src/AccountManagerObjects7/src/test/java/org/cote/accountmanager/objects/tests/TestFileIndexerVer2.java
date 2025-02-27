package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.file.FileIndexer;
import org.cote.accountmanager.io.file.Index;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.junit.Test;

public class TestFileIndexerVer2 extends BaseTest {

		public static final Logger logger = LogManager.getLogger(TestFileIndexerVer2.class);

		
		@Test
		public void TestIOSystemStore() {
			//IOContext ctx = IOSystem.open(RecordIO.FILE, "testFileIndexerVer2.7z", null);
			resetIO("testFileIndexerVer2.7z");
			OrganizationContext octx = ioContext.getOrganizationContext("/Development", OrganizationEnumType.DEVELOPMENT);
			assertNotNull("Context was null", octx);
			if(!octx.isInitialized()) {
				logger.info("Creating new organization");
				try {
					octx.createOrganization();
				} catch (NullPointerException | SystemException e) {
					logger.error(e);
					
				}
			}
			else {
				logger.info("Working with existing organization");
			}
			assertTrue("Expected org to be initialized", octx.isInitialized());
			
		}
		
		@Test
		public void TestVolumeIndex() {
			
			resetIO(null);
			logger.info("Test Volume Index");
			
			String dataPref = "Data test - ";
			String uuid = null;
			
			BaseRecord volumeUser = null;

			OrganizationContext testOrgContext = getTestOrganization("/Development/Volume");
			Factory mf = ioContext.getFactory();
			try 
			{
				volumeUser = mf.getCreateUser(testOrgContext.getAdminUser(), "volumeUser", testOrgContext.getOrganizationId());
			}
			catch(Exception e) {
				logger.error(e);
				
			}
			FileIndexer fix = ioContext.getIndexManager().getInstance(ModelNames.MODEL_JOURNAL);
			
			BaseRecord group = ioContext.getPathUtil().makePath(volumeUser, ModelNames.MODEL_GROUP, "/Volume Data", "DATA", testOrgContext.getOrganizationId());
			for(int i = 0; i < 2; i++) {
				uuid = UUID.randomUUID().toString();
				try {
					BaseRecord data = RecordFactory.model("journalObject").newInstance();
					data.set(FieldNames.FIELD_NAME, dataPref + uuid);
					data.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
					data.set(FieldNames.FIELD_BYTE_STORE, "The data to patch".getBytes());
					data.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
					data.set(FieldNames.FIELD_OWNER_ID, volumeUser.get(FieldNames.FIELD_ID));
					data.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
					// fix.setTrace(true);
					ioContext.getRecordUtil().createRecord(data, true);
					// fix.setTrace(false);
					BaseRecord checkData = ioContext.getRecordUtil().getRecordById(volumeUser, data.getSchema(), data.get(FieldNames.FIELD_ID));
					assertNotNull("Check data was null", checkData);

				}
				catch(ModelNotFoundException | FieldException | ValueException e) {
					logger.error(e);
				}
			}

		}
		
		@Test
		public void TestStoreCustomIndexEntry() {
			ioContext.loadModel("journalObject");
			logger.info("Test Store Custom Index Entry");
			resetIO("volume-journal.7z");
			
			
			FileIndexer fix = ioContext.getIndexManager().getInstance("journalObject");
			//fix.setTrace(true);
			/// invoke getIndex to create the file index if it doesn't exist
			Index idx = null;
			try {
				idx = fix.getIndex();
				fix.flushIndex();
			} catch (IndexException e) {
				logger.error(e);
			}
			logger.info("Entry size: " + idx.getEntries().size());
			assertNotNull("Index is null", idx);
			IndexEntry ent = fix.getIdxEntry();
			
			assertNotNull("Entry is null", ent);
			
			OrganizationContext testOrgContext = getTestOrganization("/Development/Volume");
			Factory mf = ioContext.getFactory();
			BaseRecord volumeUser = null;
			try 
			{
				volumeUser = mf.getCreateUser(testOrgContext.getAdminUser(), "volumeUser", testOrgContext.getOrganizationId());
			}
			catch(Exception e) {
				logger.error(e);
				
			}
			String dataPref = "Index Data Test - " + UUID.randomUUID().toString();
			BaseRecord group = ioContext.getPathUtil().makePath(volumeUser, ModelNames.MODEL_GROUP, "/Volume Data", "DATA", testOrgContext.getOrganizationId());
			BaseRecord data = null;
			try{
				data = RecordFactory.newInstance("journalObject");
				data.set(FieldNames.FIELD_NAME, dataPref);
				data.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
				data.set(FieldNames.FIELD_BYTE_STORE, "The data to patch".getBytes());
				data.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
				data.set(FieldNames.FIELD_OWNER_ID, volumeUser.get(FieldNames.FIELD_ID));
				data.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
				boolean created = ioContext.getRecordUtil().createRecord(data, true);
				assertTrue("Failed to create new record", created);
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
				
			}

			BaseRecord checkData = ioContext.getRecordUtil().getRecordById(volumeUser, "journalObject", data.get(FieldNames.FIELD_ID));
			//fix.setTrace(false);
			assertNotNull("Check data was null", checkData);

		}
		
		@Test
		public void TestStoreVolumeIndex() {
			
			logger.info("Test Store Volume Index");
			
			
			resetIO("volume-journal.7z");

			ioContext.loadModel("journalObject");
			String dataPref = "Data test - ";
			String uuid = null;
			BaseRecord volumeUser = null;

			OrganizationContext testOrgContext = getTestOrganization("/Development/Volume");
			Factory mf = ioContext.getFactory();
			try 
			{
				volumeUser = mf.getCreateUser(testOrgContext.getAdminUser(), "volumeUser", testOrgContext.getOrganizationId());
			}
			catch(Exception e) {
				logger.error(e);
				
			}
			
			BaseRecord group = ioContext.getPathUtil().makePath(volumeUser, ModelNames.MODEL_GROUP, "/Volume Data", "DATA", testOrgContext.getOrganizationId());
			for(int i = 0; i < 2; i++) {
				uuid = UUID.randomUUID().toString();
				try {
					BaseRecord data = RecordFactory.newInstance("journalObject");
					data.set(FieldNames.FIELD_NAME, dataPref + uuid);
					data.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
					data.set(FieldNames.FIELD_BYTE_STORE, "The data to patch".getBytes());
					data.set(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
					data.set(FieldNames.FIELD_OWNER_ID, volumeUser.get(FieldNames.FIELD_ID));
					data.set(FieldNames.FIELD_ORGANIZATION_ID, testOrgContext.getOrganizationId());
					ioContext.getRecordUtil().createRecord(data, true);
					BaseRecord checkData = ioContext.getRecordUtil().getRecordById(volumeUser, "journalObject", data.get(FieldNames.FIELD_ID));
					assertNotNull("Check data was null", checkData);
				}
				catch(ModelNotFoundException | FieldException | ValueException e) {
					logger.error(e);
				}
			}
		}
		
		
		@Test
		public void TestIOSystem() {
			
			OrganizationContext octx = ioContext.getOrganizationContext("/Development", OrganizationEnumType.DEVELOPMENT);
			assertNotNull("Context was null", octx);
			if(!octx.isInitialized()) {
				try {
					octx.createOrganization();
				} catch (NullPointerException | SystemException e) {
					logger.error(e);
					
				}
			}
			assertTrue("Expected org to be initialized", octx.isInitialized());
		}
		
		
}
