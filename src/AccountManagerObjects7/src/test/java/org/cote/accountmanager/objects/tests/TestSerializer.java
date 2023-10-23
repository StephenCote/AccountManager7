package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.MemoryWriter;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.file.FileIndexer;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.junit.Test;

public class TestSerializer extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestSerializer.class);
	
	
	/*
	 * NOTE: Many of these tests are currently invalid because the storage base doesn't change.
	 * FileIndexes wind up pointing to mismatched objects and a deserialization error is thrown
	 * Or the keyStores are invalid with randomized fileStores (zip)
	 * So all of these tests need to be refactored to move the storage base to a randomized sub-directory to avoid conflicts
	 */
	@Test
	public void TestPaths() {
		resetIO("test-" + UUID.randomUUID().toString() + "7.z");
		Factory mf = ioContext.getFactory();
		/// Note: When using the memory writer directly, any provider depending on the ability to read other data (parent, group, etc) will log an error
		BaseRecord record = null;
		BaseRecord record2 = null;
		MemoryReader memr = new MemoryReader();
		MemoryWriter memw = new MemoryWriter();
		FileIndexer fix = ioContext.getIndexManager().getInstance(ModelNames.MODEL_ORGANIZATION);
		boolean error = false;
		try {
			record = mf.newInstance(ModelNames.MODEL_ORGANIZATION);
			record.set(FieldNames.FIELD_NAME,  "Test Serializer - " + UUID.randomUUID().toString());
			record2 = mf.newInstance(ModelNames.MODEL_ORGANIZATION);
			record2.set(FieldNames.FIELD_NAME,  "Test Child");
			memr.read(record);
			memr.read(record2);
			memw.write(record);
			memw.write(record2);
		} catch (WriterException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException e1) {
			logger.error(e1.getMessage());
			error = true;
			e1.printStackTrace();
		}
		assertFalse("An error was encountered", error);
		
		try {
			/// file-based ids only set when using the FileWriter
			record.set("id",  fix.nextId());
			record2.set("id",  fix.nextId());
			record2.set("parentId", record.get("id"));
			
			// logger.info("Adding " + JSONUtil.exportObject(record, RecordSerializerConfig.getUnfilteredModule()));
			// logger.info("Adding " + JSONUtil.exportObject(record2, RecordSerializerConfig.getUnfilteredModule()));

			fix.addIndexEntry(record);
			fix.addIndexEntry(record2);
			fix.flushIndex();
		} catch (IndexException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
			error = true;
		}
		
		assertFalse("Didn't expect an error", error);
		fix.unloadIndex();
		
		logger.info("** BEGIN EXPECTED ERROR");
		try {
			fix.addIndexEntry(record);
		} catch (IndexException e) {
			logger.error(e);
			error = true;
		}
		assertTrue("Expected an error", error);
		logger.info("** END EXPECTED ERROR");
		error = false;
		try {
			record2.set("parentId", record.get("id"));
			fix.updateIndexEntry(record2);
			fix.flushIndex();
		} catch (IndexException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			error = true;
		}
	}
	
	/*
	@Test
	public void TestRawFile() {
		String indexName = "am6.raw." + UUID.randomUUID().toString() + ".index.json";
		resetIO(null, false, indexName);
		BaseRecord record = newOrganization("Test Serializer - " + UUID.randomUUID().toString());
		BaseRecord record2 = newOrganization("Test Child 1");

		try {
			recordUtil.createRecord(record);
			recordUtil.createRecord(record2);

			BaseRecord[] records = getSearch().findByName(ModelNames.MODEL_ORGANIZATION, "Test Child 1");
			assertTrue("Expected 1 record, received " + records.length, records.length == 1);
		
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
	}
	*/
	
	
	/*
	@Test
	public void TestFileStoreWithMemWriter() {
		resetIO(null, false);
		FileStore fs = new FileStore("./am6");
		BaseRecord record = newOrganization("Test Serializer - " + UUID.randomUUID().toString());
		BaseRecord record2 = newOrganization("Test Child 1");
		//FileIndexer fix = FileIndexer.newInstance("./am6");
		MemoryWriter memw = new MemoryWriter();
		try {
			memw.write(record);
			memw.write(record2);

			fs.initialize();
			FileIndexer fix = fs.getIndexer();
			record.set("id",  fix.nextId());
			record2.set("id",  fix.nextId());
			record2.set("parentId", record.get("id"));
			fix.addIndexEntry(record);
			fix.addIndexEntry(record2);
			fs.add(new BaseRecord[] {record, record2});
			fix.flushIndex();
		} catch (FieldException | ValueException | ModelNotFoundException | IndexException | IOException | WriterException e) {
			logger.error(e);
		}
		String objectId = record.get("objectId");
		String objectId2 = record2.get("objectId");
		byte[] data = fs.get("blah");
		assertNull("Expected data to be empty", data);
		data = fs.get(objectId);
		assertNotNull("Data was null", data);
		assertTrue("Expected data to be present", data.length > 0);
		
		logger.info("Remove " + objectId2);
		boolean rem = fs.remove(fs.getStoreName(record2));
		assertTrue("Expected to have removed " + objectId2, rem);
		
	}
	*/

	@Test
	public void TestStoreVolume() {
		
		String orgName = "Test Org - " + UUID.randomUUID().toString();
		String groupName = "Test Group - " + UUID.randomUUID().toString();
		String storeName = "testStore1-" + UUID.randomUUID().toString() + ".7z";

		long prelim = System.currentTimeMillis();
		resetIO(storeName);
		Factory mf = ioContext.getFactory();
		
		OrganizationContext orgContext = ioContext.getOrganizationContext("/" + orgName, OrganizationEnumType.DEVELOPMENT);
		if(!orgContext.isInitialized()) {
			try {
				orgContext.createOrganization();
			} catch (SystemException e) {
				logger.error(e);
			}
		}
		assertTrue("Expected org to be initialized", orgContext.isInitialized());

		BaseRecord testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupName, "DATA", orgContext.getOrganizationId());
		
		String checkDataName = null;
		String checkDataOid = null;
		BaseRecord checkData = null;
		
		long start = System.currentTimeMillis();
		for(int i = 0; i < 10; i++) {
			String dataName = "Test Data - " + UUID.randomUUID().toString();
			String demoData = "This is the demo data that we're going to use to test with";

			BaseRecord dat = newData(testUser1, dataName, "text/plain", demoData.getBytes(), group.get("path"), orgContext.getOrganizationId());
			ioContext.getRecordUtil().createRecord(dat, false);
			if(i == 5) {
				checkData = dat;
				checkDataName = dataName;
				checkDataOid = dat.get(FieldNames.FIELD_OBJECT_ID);
			}
		}
		long mark1 = System.currentTimeMillis();
		ioContext.getRecordUtil().flush();

		long mark2 = System.currentTimeMillis();

		assertNotNull("Expected a check data name", checkDataName);

		/// Reload the store

		resetIO(storeName);
		
		long mark3 = System.currentTimeMillis();
		/// Note: getRecord uses the contextUser's org id, so if using a custom org while testing, make sure to specify the correct org id
		/// 
		//BaseRecord data = ioContext.getRecordUtil().getRecord(testUser1, "data", checkDataName, 0L, group.get("path"));
		BaseRecord data = ioContext.getRecordUtil().getRecord(testUser1, "data", checkDataName, 0L, group.get("id"), orgContext.getOrganizationId());

		long mark4 = System.currentTimeMillis();
		assertNotNull("Data " + checkDataName + "(" + checkDataOid + ") in " + group.get("path") + " in " + storeName + " was null", data);		
		logger.info("Preliminary Setup: " + (start - prelim) + "ms");
		logger.info("10 Create Time: " + (mark1 - start) + "ms");
		logger.info("Flush Time: " + (mark2 - mark1) + "ms");
		logger.info("Load Store: " + (mark3 - mark2) + "ms");
		logger.info("Retrieve record: " + (mark4 - mark3) + "ms");
		/// Get the middle record out

	}

	/*
	@Test
	public void TestFileStoreWithFileWriter() {

		String record1Name = "Test Serializer - " + UUID.randomUUID().toString();
		BaseRecord record = newOrganization(record1Name);
		BaseRecord record2 = newOrganization("Test Child 1");
		String storeName = "testStore1-" + UUID.randomUUID().toString() + ".7z";

		resetIO(storeName, false);
		FileWriter writer = getWriter();
		FileReader reader = getReader();
		FileSearch search = new FileSearch(reader);
		
		BaseRecord search1 = null;
		BaseRecord search2 = null;
		try {
			writer.write(record);
			
			BaseRecord[] results = search.findByName(ModelNames.MODEL_ORGANIZATION, record1Name);
			assertTrue("Expected 1 result", results.length == 1);
			search1 = results[0];
			long id = search1.get("id");
			
			record2.set("parentId", record.get("id"));
			writer.write(record2);
			writer.flush();
			
			results = search.findByNameInParent(ModelNames.MODEL_ORGANIZATION, id, "Test Child 1");
			assertTrue("Expected 1 result, and found " + results.length, results.length == 1);
			search2 = results[0];
			
			
			
		} catch (ClassCastException | NullPointerException | IndexException | FieldException | ValueException | ModelNotFoundException | ReaderException | WriterException e) {
			logger.error(e);
			
		}
	}
	
	
	@Test
	public void TestSerialize() {
		resetIO(null, false);
		BaseRecord record = newOrganization("Test Serializer");
		getWriter().translate(RecordOperation.READ, record);
		String ser = JSONUtil.exportObject(record, RecordSerializerConfig.getUnfilteredModule());
		assertNotNull("Failed to serialize record", ser);
		BaseRecord irecord = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Failed to import module", irecord);

	}
	

	@Test
	public void TestJsonReader() {
		resetIO(null, false);
		BaseRecord record = newOrganization("Test Json Reader");
		getWriter().translate(RecordOperation.READ, record);
		String ser = JSONUtil.exportObject(record, RecordSerializerConfig.getUnfilteredModule());
		
		JsonReader reader = new JsonReader();
		BaseRecord irecord = null;
		try {
			irecord = reader.read(ser);
		} catch (ReaderException e) {
			logger.error(e);
			
		}
		
		String path = irecord.get("path");
		assertNotNull("Expected the path to be set", path);

	}
	
	@Test
	public void TestJsonWriter() {
		resetIO(null, false);
		BaseRecord record = newOrganization("Test Json Writer");
		getWriter().translate(RecordOperation.READ, record);
		JsonWriter writer = new JsonWriter();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			writer.write(record, baos);
		} catch (WriterException e) {
			logger.error(e.getMessage());
		}
		String ser = new String(baos.toByteArray());
		String objectId = record.get("objectId");
		assertNotNull("Expected the object id to be set", objectId);

	}
	
	*/
	
}
