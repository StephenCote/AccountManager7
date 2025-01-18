package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.DirectoryUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

public class TestStream extends BaseTest {

	private BaseRecord newSegment(BaseRecord stream, byte[] data) {
		List<BaseRecord> segs = stream.get(FieldNames.FIELD_SEGMENTS);
		BaseRecord seg = null;
		try {
			seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, data);
			seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
			segs.add(seg);
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return seg;
	}

	@Test
	public void TestEntitlements() {
		// RecordFactory.deleteOrganization(11);
		try {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		ioContext.getPolicyUtil().setTrace(true);
		ioContext.getPathUtil().setTrace(true);
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamList", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ioContext.getPolicyUtil().setTrace(false);
		ioContext.getPathUtil().setTrace(false);
		assertNotNull("Group is null", group);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	@Test
	public void TestAuthorizeStreamSegment() {
		logger.info("Test Stream Authorization");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		BaseRecord testUser6 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser6", testOrgContext.getOrganizationId());
		String dataName = "Auth stream " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/Streams");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		StreamSegmentUtil ssu = new StreamSegmentUtil();
		
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, testUser5, null, plist);
			data.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			//newSegment(data, "1) This is some example data".getBytes());
			data = ioContext.getAccessPoint().create(testUser5, data);
			
			BaseRecord seg1 = ssu.newSegment(data.get(FieldNames.FIELD_OBJECT_ID));
			
			PolicyResponseType prr = ioContext.getAuthorizationUtil().canCreate(testUser5, testUser5, data);
			assertTrue("Expected a permit", prr.getType() == PolicyResponseEnumType.PERMIT);
			
			PolicyResponseType prr2 = ioContext.getAuthorizationUtil().canCreate(testUser5, testUser5, seg1);
			//logger.info(prr2.toFullString());
			
			logger.info("Can testUser6 access testUser5's data (no rights)");
			PolicyResponseType prr3 = ioContext.getAuthorizationUtil().canRead(testUser6, testUser6, data);
			logger.info(prr3.toFullString());
			
			logger.info("Can testUser6 access testUser5's data (w/ access token)");
			String token = TokenService.createAuthorizationToken(testUser5, testUser6, data, new String[] {"/Read"}, UUID.randomUUID().toString(), 5);
			PolicyResponseType prr4 = ioContext.getAuthorizationUtil().canRead(testUser6, testUser6, token, data);
			logger.info(prr4.toFullString());
			
			
		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException | IndexException  e) {
			logger.error(e);
			e.printStackTrace();
		}
		
	}

	
	@Test
	public void TestListLargeData() {
		logger.info("**** TEST LIST LARGE DATA");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamList", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		int count = ioContext.getAccessPoint().count(testUser1, q);
		int maxCount = 650;
		int iter = 0;
		int errorCount = 0;
		int failCount = 0;
		int createCount = 0;
		//if(count == 0) {
			logger.info("Test data path: " + testDataPath);
			DirectoryUtil du = new DirectoryUtil(testDataPath);
			List<File> files = du.dir();
			for(File f: files) {
				if(iter >= maxCount) {
					break;
				}
				if(f.isDirectory()) {
					continue;
				}
				try(FileInputStream fos = new FileInputStream(f)){
					
					boolean created = StreamUtil.streamToData(testUser1, f.getName(), f.getAbsolutePath(), "~/Data/StreamList", 0L, fos);
					//assertTrue("Failed to stream into data", created);
					if(!created) {
						failCount++;
					}
					else {
						createCount++;
					}
					iter++;
					
				} catch (IOException | FieldException | ModelNotFoundException | ValueException | FactoryException | IndexException | ReaderException e) {
					logger.error(e);
					errorCount++;
				}

			}
			//logger.info("File count: " + files.size());
		//}
		logger.info("File Count: " + count);
		logger.info("Create Count: " + createCount);
		logger.info("Fail Count: " + failCount);
		logger.info("Error Count: " + errorCount);
		int pages = 6;
		long startIndex = 0L;
		int recordCount = 10;
		for(int i = 0; i < pages; i++) {
			logger.info("PAGE #" + (i + 1));
			Query pq = QueryUtil.buildQuery(testUser1, ModelNames.MODEL_DATA, group.get(FieldNames.FIELD_OBJECT_ID), null, startIndex, recordCount);
			assertNotNull("Query is null", pq);
			pq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OBJECT_ID});
			logger.info("Retrieve data list");
			QueryResult qr = ioContext.getAccessPoint().list(testUser1, pq);
			assertNotNull("Result is null", qr);
			int thumbWidth = 48;
			int thumbHeight = 48;
			//logger.info(qr.toFullString());
			try {
				logger.info("Scanning page for thumbnails");
				for(BaseRecord rec : qr.getResults()) {
					String thumbName = rec.get(FieldNames.FIELD_NAME) + " " + thumbWidth + "x" + thumbHeight;
					BaseRecord thumb = ioContext.getAccessPoint().findByNameInGroup(testUser1, ModelNames.MODEL_THUMBNAIL, group.get(FieldNames.FIELD_OBJECT_ID), thumbName);
					if(thumb == null) {
						logger.info("Creating thumbnail " + thumbName);
						try {
							thumb = ThumbnailUtil.getCreateThumbnail(rec, thumbWidth, thumbHeight);
							if(thumb == null) {
								logger.error("Failed to create thumbnail for " + rec.get(FieldNames.FIELD_URN));
							}
						}
						catch(NullPointerException e) {
							logger.error(e);
							logger.error(rec.toFullString());
						}
					}
					else {
						logger.info("Thumbnail " + thumbName + " already exists");
					}
				}
			}
			catch(ModelNotFoundException | IndexException | ReaderException | FactoryException | IOException | FieldException | ValueException e) {
				logger.error(e);
			}
			startIndex += recordCount;
		}
	}

	@Test
	public void TestStreamUtil() {
		logger.info("Test Streaming");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/StreamUtil");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		
		String[] sampleData = new String[] {"airship.jpg", "anaconda.jpg", "antikythera.jpg", "railplane.png", "shark.webp", "steampunk.png", "sunflower.jpg"};
		try(FileInputStream fos = new FileInputStream("./media/" + sampleData[6])){
			
			boolean created = StreamUtil.streamToData(testUser5, dataName, "Test stream utility", "~/Data/StreamUtil", 0L, fos);
			assertTrue("Failed to stream into data", created);
			
		} catch (IOException | FieldException | ModelNotFoundException | ValueException | FactoryException | IndexException | ReaderException e) {
			logger.error(e);
		}
		

	}


	@Test
	public void TestCreateStream() {
		logger.info("Test Streaming");
		//RecordFactory.deleteOrganization(47);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/Streams");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		
		try {
			logger.info("Create a new stream and include a segment");
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, testUser5, null, plist);
			data.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			newSegment(data, "1) This is some example data".getBytes());
			assertNotNull("Data is null", data);
			
			/// Corner-case issue when setting a field to be encrypted
			/// And that field may receive a default value via a model-level provider
			/// Then the field won't be encrypted.  At the moment, a FieldException will be thrown, caught, and sunk in RecordTranslator
			/// This needs to be fixed so a record with incomplete field translation isn't persisted
			/// Either way, though, it's necessary to set any default value at a field level provider vs. model level provider,
			/// Or at the time the record is created instead of at the model level provider
			///
			StreamSegmentUtil ssu = new StreamSegmentUtil();
			ssu.getFileStreamPath(data);
			
			/// When a segment is added to a new stream to be auto created, and the stream source field is encrypted
			/// Then the field is already encrypted at the time the segment is created
			///
			data = ioContext.getAccessPoint().create(testUser5, data);
			
			//BaseRecord idata = ioContext.getAccessPoint().findById(testUser5, ModelNames.MODEL_STREAM, data.get(FieldNames.FIELD_ID));
			Query qdata = QueryUtil.createQuery(ModelNames.MODEL_STREAM, FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			qdata.planCommon(false);
			
			BaseRecord idata = ioContext.getAccessPoint().find(testUser5, qdata);
			assertNotNull("Data is null", idata);
			long size = data.get(FieldNames.FIELD_SIZE);
			assertTrue("Expected a positive size", size > 0L);
			
			logger.info("Writing a direct stream segment");
			
			/// Write a direct segment
			BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, "\n2) This is some more data to add".getBytes());
			seg.set(FieldNames.FIELD_STREAM_ID, idata.get(FieldNames.FIELD_OBJECT_ID));

			

			boolean created = ioContext.getRecordUtil().createRecord(seg);
			
			assertTrue("Expected to create the segment", created);
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_STREAM_SEGMENT, FieldNames.FIELD_STREAM_ID, idata.get(FieldNames.FIELD_OBJECT_ID));
			q.field(FieldNames.FIELD_START_POSITION, 0L);
			q.field(FieldNames.FIELD_LENGTH, 10L);
			
			QueryResult qr = ioContext.getSearch().find(q);
			assertNotNull("Result is null", qr);

			assertTrue("Expected 1 result", qr.getTotalCount() == 1);
			BaseRecord seg1 = qr.getResults()[0];
			String txt = new String((byte[])seg1.get(FieldNames.FIELD_STREAM));
			logger.info("Streamed back: " + txt);
			
			
			
			byte[] allBytes = ssu.streamToEnd(idata.get(FieldNames.FIELD_OBJECT_ID), 0L, 10L);
			logger.info(new String(allBytes));
			
			
			byte[] overRead = ssu.streamToEnd(idata.get(FieldNames.FIELD_OBJECT_ID), 0L, 100L);
			assertTrue("Expected byte length to match", overRead.length == allBytes.length);
			// logger.info(data.toFullString());

		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException    e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		
	}
	/*
	@Test
	public void TestStreamInPlace() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		BaseRecord odata = null;
		// String dataName = "Demo stream " + UUID.randomUUID().toString();
		String dataName = "Demo stream in place #1";
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamInPlace", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/StreamInPlace");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_STREAM, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, dataName);
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		BaseRecord data = ioContext.getAccessPoint().find(testUser1, q);

		
		IOFactory.addPermittedPath("./media");
		StreamSegmentUtil ssUtil = new StreamSegmentUtil();
		
		try {
			if(data != null) {
				boolean del = ioContext.getAccessPoint().delete(testUser1, data);
				assertTrue("Expected existing data to be deleted", del);
			}
			logger.info("Create a new stream and include a segment");
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, testUser1, null, plist);


			String path = "./media/airship.jpg";
			assertFalse("Stream path is restricted", ssUtil.isRestrictedPath(path));

			data.set(FieldNames.FIELD_STREAM_SOURCE, path);
			data.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(path));
			data.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			ssUtil.updateStreamSize(data);
			// logger.info(data.toFullString());
			odata = ioContext.getAccessPoint().create(testUser1, data);
			assertNotNull("Failed to create data", odata);
			logger.info(odata.toFullString());
			assertNotNull("Failed to create stream " + dataName, odata);

			byte[] dataB = ssUtil.streamToEnd(odata.get(FieldNames.FIELD_OBJECT_ID), 0, 0);
			logger.info("Byte length: " + dataB.length);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	@Test
	public void TestStreamInPlaceUtility() {
		logger.info("Test stream file in place");
		
		IOFactory.addPermittedPath("./media");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser2 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		
		BaseRecord odata = null;
		String filePath = "./media/anaconda.jpg";
		String name = Paths.get(filePath).getFileName().toString();

		String groupPath = "~/Data/StreamInPlace";
		logger.info(testUser2.toFullString());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser2, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertTrue("Failed to cleanup stream", cleanupInGroup(testUser2, ModelNames.MODEL_STREAM, name, group.get(FieldNames.FIELD_ID)));
		assertTrue("Failed to cleanup data", cleanupInGroup(testUser2, ModelNames.MODEL_DATA, name, group.get(FieldNames.FIELD_ID)));
		
		boolean streamed = StreamUtil.streamInPlaceToData(testUser2, filePath, groupPath);
		assertTrue("Failed to stream in place", streamed);
	}
	@Test
	public void TestStreamDirectoryInPlace() {
		String path = "c:/tmp/xpic";
		AuditUtil.setLogToConsole(false);

		IOFactory.addPermittedPath(path);
		DirectoryUtil du = new DirectoryUtil(path);
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser3 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser3", testOrgContext.getOrganizationId());

		String groupPath = "~/Data/StreamDirectoryInPlace";
		BaseRecord group = ioContext.getPathUtil().makePath(testUser3, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());

		for(File f : du.dir()) {
			if(f.isDirectory()) {
				continue;
			}
			String name = f.getName();
			String filePath = f.getAbsolutePath();
			
			assertTrue("Failed to cleanup stream", cleanupInGroup(testUser3, ModelNames.MODEL_STREAM, name, group.get(FieldNames.FIELD_ID)));
			assertTrue("Failed to cleanup data", cleanupInGroup(testUser3, ModelNames.MODEL_DATA, name, group.get(FieldNames.FIELD_ID)));

			
			boolean streamed = StreamUtil.streamInPlaceToData(testUser3, filePath, groupPath);
			assertTrue("Expected file " + filePath + " to be streamed", streamed);
		}

	}
	*/
	private boolean cleanupInGroup(BaseRecord user, String model, String name, long groupId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_NAME, name);
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		BaseRecord data = ioContext.getAccessPoint().find(user, q);
		boolean outBool = true;
		if(data != null) {
			// logger.info("Cleanup " + model + " " + name + " in group #" + groupId);
			outBool = ioContext.getAccessPoint().delete(user, data);
		}
		else {
			logger.warn(model + " " + name +" in group #" + groupId + " doesn't exist");
		}
		return outBool;
	}
	
}
