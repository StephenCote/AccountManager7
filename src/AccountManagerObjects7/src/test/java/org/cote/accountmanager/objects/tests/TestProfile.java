package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javax.sql.DataSource;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ApplicationUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

public class TestProfile extends BaseTest {

	/*
	@Test
	public void TestConstraints() {
		logger.info("Test Constraints");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Profile");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

	}
	*/
	/*
	@Test
	public void TestPath() {
		String encPath = "B64-L2hvbWUvc3RldmUvRGF0YQ%3D%3D";
		String path = encPath;
		if(path.startsWith("B64-")) {
			path = BinaryUtil.fromBase64Str(path.substring(4,path.length()));
		}
		logger.info(encPath + " -> " + path);
	}
	*/
	
	@Test
	public void TestDynamicLoadAudit() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Audit");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String objStr = """
				{"attributes":[],"organizationId":0,"ownerId":0,"controls":[],FieldNames.FIELD_TAGS:[],FieldNames.FIELD_NAME:"aouaoeu","parentId":28,FieldNames.FIELD_TYPE:"data","model":"auth.group",FieldNames.FIELD_PATH:"~/Groups","organizationPath":"/Development/Audit"}"
		""";
		logger.info("Test creating imported object with mismatched identifiers and virtual (denormalized) paths");
		BaseRecord newGroup = JSONUtil.importObject(objStr, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		BaseRecord cleanObj = null;
		assertNotNull("Group is null", newGroup);
		try {
			newGroup.set(FieldNames.FIELD_PARENT_ID, testUser1.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_ID));
			newGroup.set(FieldNames.FIELD_NAME, "New Group - " + UUID.randomUUID().toString());
			cleanObj = mf.newInstance(newGroup.getModel(), testUser1, newGroup, null);
		}
		catch(ModelNotFoundException | FieldException | ValueException | FactoryException e) {
			logger.error(e);
		}
		logger.info(cleanObj.toFullString());
		BaseRecord newObj = ioContext.getAccessPoint().create(testUser1, cleanObj);
		assertNotNull("Object is null", newObj);
		logger.info(newObj.toFullString());
	}
	/*
	@Test
	public void TestThreadLoad() {
		
		logger.info("Test Thread Load");
		
		logger.info("Preload org, user, and group while testing cache concurrency");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Threads");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamList", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		
		DirectoryUtil du = new DirectoryUtil(testDataPath);
		List<File> files = du.dir();
		List<String> fileL = new ArrayList<>();
		List<TestRunnable> runs = new ArrayList<>();
		int max = 100;
		int iter = 1;
		int threadFactor = 10;
		for(int i = 0; i < files.size() && i < max; i++) {
			if(i > 0 && i % threadFactor == 0) {
				// logger.info("Break: " + (i + 1) + " " + fileL.size());
				//threads.add(new Thread(getRunnable(fileL.toArray(new String[0])), "Thread " + (i + 1)));
				runs.add(new TestRunnable(iter++, fileL.toArray(new String[0])));
				fileL.clear();
			}
			fileL.add(files.get(i).getAbsolutePath());
		}
		if(fileL.size() > 0) {
			runs.add(new TestRunnable(iter++, fileL.toArray(new String[0])));
		}
		CountDownLatch latch = new CountDownLatch(runs.size());
		List<Thread> threads = new ArrayList<>();
		cleanupDataset();
		logger.info("Starting up " + runs.size() + " threads");
		for(TestRunnable t : runs) {
			t.setLatch(latch);
			Thread tr = new Thread(t);
			threads.add(tr);
			tr.start();
		}
		logger.info("Waiting for latch countdown");
		try {
			latch.await();
		} catch (InterruptedException e) {
			logger.error(e);
		}
		
		logger.info("All threads finished");
	}
	*/
	
	@Test
	public void TestAppProfile() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Profile");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(testOrgContext.getAdminUser(), ModelNames.MODEL_GROUP, "/Persons", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		//assertNotNull("Persons dir is null", dir);
		BaseRecord app = ApplicationUtil.getApplicationProfile(testUser1);
	
		//ioContext.getPolicyUtil().setTrace(true);
		Query query = QueryUtil.createQuery(ModelNames.MODEL_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		query.field(FieldNames.FIELD_NAME, "testUser1");
		PolicyResponseType[] prr = ioContext.getPolicyUtil().evaluateQueryToReadPolicyResponses(testUser1, query);
		// logger.info("Prrs: " + prr.length);
		//ioContext.getPolicyUtil().setTrace(false);
		
		assertNotNull("Profile is null", app);
		assertNotNull("Person object is null", app.get(FieldNames.FIELD_PERSON));
		List<BaseRecord> sysRoles = app.get(FieldNames.FIELD_SYSTEM_ROLES);
		logger.info("Role count: " + sysRoles.size());
	}
	

	
	/*
	@Test
	public void TestThreadedRequests() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Threads");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamList", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		
		DataSource dataSource = ioContext.getDbUtil().getDataSource();
		try (Connection con = dataSource.getConnection()){
			try(Statement statement = con.createStatement()){
				String delData = "DELETE FROM " + ioContext.getDbUtil().getTableName(ModelNames.MODEL_THUMBNAIL) + " WHERE groupId = " + group.get(FieldNames.FIELD_ID);
				statement.executeUpdate(delData);
			}

		} catch (SQLException e) {
			logger.error(e);
		}
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		int count = ioContext.getAccessPoint().count(testUser1, q);
		int maxCount = 650;
		int iter = 0;
		int errorCount = 0;
		int failCount = 0;
		int createCount = 0;
		if(count == 0) {
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
		}
		logger.info("Record Count: " + count);
		logger.info("Create Count: " + createCount);
		logger.info("Fail Count: " + failCount);
		logger.info("Error Count: " + errorCount);
		int pages = 1;
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
	*/
	
	class TestRunnable implements Runnable{
		private String[] fileList = new String[0];
		private CountDownLatch latch = null;
		private int id = 0;
		public TestRunnable(int id, String[] list) {
			this.fileList = list;
			this.id = id;
		}

		
		
		public void setLatch(CountDownLatch latch) {
			this.latch = latch;
		}



		@Override
		public void run() {
			// TODO Auto-generated method stub
			OrganizationContext testOrgContext = getTestOrganization("/Development/Threads");
			Factory mf = ioContext.getFactory();
			BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
			BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamList", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
			int failCount = 0;
			int createCount = 0;
			int iter = 0;
			int errorCount = 0;
		    for(int i= 0; i < fileList.length; i++) {
		        System.out.println(id + " - " + (i + 1) + ") " + fileList[i]);
				File f = new File(fileList[i]);
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
				
				int thumbWidth = 48;
				int thumbHeight = 48;
				String thumbName = f.getName() + " " + thumbWidth + "x" + thumbHeight;
				String groupId = group.get(FieldNames.FIELD_OBJECT_ID);
				if(groupId == null) {
					logger.error("***** NULL GROUP OBJECT ID");
					errorCount++;
					continue;
				}
				Query q = QueryUtil.buildQuery(testUser1, ModelNames.MODEL_THUMBNAIL, groupId, thumbName, 0, 0);
				if(q == null) {
					logger.error("Null query: " + groupId);
					logger.error(group.toFullString());
					errorCount++;
					continue;
				}
				BaseRecord thumb = ioContext.getAccessPoint().find(testUser1, q);
				if(thumb == null) {
					logger.info("Creating thumbnail " + thumbName);
					try {
						Query rq = QueryUtil.buildQuery(testUser1, ModelNames.MODEL_DATA, groupId, f.getName(), 0, 0);
						BaseRecord rec = ioContext.getAccessPoint().find(testUser1, rq);
						if(rec == null) {
							logger.error("Failed to find " + f.getName() + " in " + groupId);
						}
						else {
							thumb = ThumbnailUtil.getCreateThumbnail(rec, thumbWidth, thumbHeight);
							if(thumb == null) {
								logger.error("Failed to create thumbnail for " + rec.get(FieldNames.FIELD_URN));
							}
						}
					}
					catch(NullPointerException | IndexException | ReaderException | FactoryException | IOException | FieldException | ValueException | ModelNotFoundException e) {
						logger.error(e);
					}
				}
				else {
					logger.info("Thumbnail " + thumbName + " already exists");
				}
				
		        
		    }
			latch.countDown();
		}
		
	}

	
	private void cleanupDataset() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Threads");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data/StreamList", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		
		DataSource dataSource = ioContext.getDbUtil().getDataSource();
		try (Connection con = dataSource.getConnection()){
			try(Statement statement = con.createStatement()){
				String delData = "DELETE FROM " + ioContext.getDbUtil().getTableName(ModelNames.MODEL_THUMBNAIL) + " WHERE groupId = " + group.get(FieldNames.FIELD_ID);
				statement.executeUpdate(delData);
				delData = "DELETE FROM " + ioContext.getDbUtil().getTableName(ModelNames.MODEL_STREAM) + " WHERE groupId = " + group.get(FieldNames.FIELD_ID);
				statement.executeUpdate(delData);
				delData = "DELETE FROM " + ioContext.getDbUtil().getTableName(ModelNames.MODEL_DATA) + " WHERE groupId = " + group.get(FieldNames.FIELD_ID);
				statement.executeUpdate(delData);

				delData = "DELETE FROM " + ioContext.getDbUtil().getTableName(ModelNames.MODEL_GROUP) + " WHERE id = " + group.get(FieldNames.FIELD_ID);
				statement.executeUpdate(delData);
			
			}

		} catch (SQLException e) {
			logger.error(e);
		}
		CacheUtil.clearCache();

	}
	
}
