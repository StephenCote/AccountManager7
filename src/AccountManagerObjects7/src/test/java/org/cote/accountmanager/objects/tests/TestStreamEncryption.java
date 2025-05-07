package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.junit.Test;

public class TestStreamEncryption extends BaseTest {

	@Test
	public void TestStreamIntegrity() {
		logger.info("Test Stream Integrity");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString() + ".mp4";
		
		BaseRecord group = ioContext.getPathUtil().makePath(testUser5, ModelNames.MODEL_GROUP, "~/Data/StreamUtil", "DATA", testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/StreamUtil");
		plist.parameter(FieldNames.FIELD_NAME, dataName);

		/// cut at 1k
		StreamUtil.setStreamCutoff(1024);
		String[] sampleData = new String[] {"./media/YoureFired.mp4"};
		try(FileInputStream fos = new FileInputStream(sampleData[0])){
			boolean created = StreamUtil.streamToData(testUser5, dataName, "Test stream utility", "~/Data/StreamUtil", 0L, fos);
			assertTrue("Failed to stream into data", created);
			
		} catch (IOException | FieldException | ModelNotFoundException | ValueException | FactoryException | IndexException | ReaderException | ModelException e) {
			logger.error(e);
		}

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, dataName);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.planMost(true);
		BaseRecord data = ioContext.getSearch().findRecord(q);
		assertNotNull("Data is null");
		
		byte[] dat = new byte[0];
		try {
			dat = ByteModelUtil.getValue(data);
		} catch (ValueException | FieldException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		logger.info("Data length: " + dat.length);
		FileUtil.emitFile("./tmp.mp4", dat);
	}
	
	@Test
	public void TestStreamEncrypt() {
		logger.info("Test Streaming Encryption");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString() + ".jpg";
		BaseRecord group = ioContext.getPathUtil().makePath(testUser5, ModelNames.MODEL_GROUP, "~/Data/StreamUtil", "DATA", testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/StreamUtil");
		plist.parameter(FieldNames.FIELD_NAME, dataName);

		/// cut at 1mb
		StreamUtil.setStreamCutoff(1048576);
		String[] sampleData = new String[] {"sunflower.jpg"};
		try(FileInputStream fos = new FileInputStream("./media/" + sampleData[0])){
			boolean created = StreamUtil.streamToData(testUser5, dataName, "Test stream utility", "~/Data/StreamUtil", 0L, fos);
			assertTrue("Failed to stream into data", created);
			
		} catch (IOException | FieldException | ModelNotFoundException | ValueException | FactoryException | IndexException | ReaderException | ModelException e) {
			logger.error(e);
		}

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, dataName);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.planMost(true);
		BaseRecord data = ioContext.getSearch().findRecord(q);
		assertNotNull("Data is null");
		
		Query sq = QueryUtil.createQuery(ModelNames.MODEL_STREAM, FieldNames.FIELD_ID, data.get("stream.id"));
		sq.planMost(true);
		BaseRecord stream = ioContext.getSearch().findRecord(sq);
		assertNotNull("Stream is null");
		
		boolean boxed = false;
		try {
			boxed = StreamUtil.boxStream(stream, false);
			StreamUtil.clearUnboxedStream(stream);
			StreamUtil.unboxStream(stream, false);
		} catch (ModelException e) {
			logger.error(e);
		}
		assertTrue("Expected stream to be boxed", boxed);

	}

	
}
