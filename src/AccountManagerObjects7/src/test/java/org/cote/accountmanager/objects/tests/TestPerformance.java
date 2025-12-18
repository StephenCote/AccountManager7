package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

public class TestPerformance extends BaseTest {
	
	
	private int dataLoadSize = 100;
	private boolean cleanup = true;
	
	private void cleanup(BaseRecord user) {
		BaseRecord group = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Data/Pictures", GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Group is null", group);
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		Query q2 = QueryUtil.createQuery(ModelNames.MODEL_THUMBNAIL, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));	
		int count = ioContext.getSearch().count(q);
		if(count > 0) {
			logger.info("Cleaning up " + count + " items");
			try {
				ioContext.getWriter().delete(q);
				ioContext.getWriter().delete(q2);
			} catch (WriterException e) {
				logger.error(e);
			}
		}
		
	}
	
	private void loadTestData(BaseRecord user) {
		String[] sampleData = new String[] {"airship.jpg", "anaconda.jpg", "antikythera.jpg", "railplane.png", "steampunk.png", "sunflower.png"};
		BaseRecord group = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Data/Pictures", GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Group is null", group);
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		int count = ioContext.getSearch().count(q);
		if (count > 0) {
			if(!cleanup) {
				logger.info("Found " + count + " data records in group " + group.get(FieldNames.FIELD_NAME));
				return;
			}
		}
		
		try {
			if(cleanup) {
				logger.info("Cleaning up " + count + " items");
				ioContext.getWriter().delete(q);
			}

			for(int i = 0; i < dataLoadSize; i++) {
				BaseRecord data = getCreateFileData(user, "~/Data/Pictures", "./media/" + sampleData[0], "Picture " + (i + 1) + ".jpg");
				assertNotNull("Data is null", data);
			}
		}
		catch(ValueException | FieldException | ModelNotFoundException | FactoryException | IndexException | ReaderException | IOException | WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}

	}
	
	@Test
	public void TestMultiThreadedConcurrency() {
		logger.info("Test multi-threaded concurrency");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Concurrency - " + UUID.randomUUID().toString());

		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		testOrgContext.getVault();
		CacheUtil.clearCache();
		assertNotNull("Vault is null", testOrgContext.getVault());
		//loadTestData(testUser1);
		
		cleanup(testUser1);
		
		List<TestRunnable> runs = new ArrayList<>();

		for(int i = 0; i < 25; i++) {
			runs.add(new TestRunnable(testUser1, (i * 4), 4));
		}
		CountDownLatch latch = new CountDownLatch(runs.size());
		List<Thread> threads = new ArrayList<>();
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
		
	}
	private Set<String> nameSet = Collections.synchronizedSet(new HashSet<String>());
	class TestRunnable implements Runnable{
		private CountDownLatch latch = null;
		private BaseRecord user = null;
		private long startIndex = 0L;
		private int recordCount = 0;
		
		private Random rand = new Random();
		private String[] sampleData = new String[] {"airship.jpg", "anaconda.jpg", "antikythera.jpg", "railplane.png", "steampunk.png", "sunflower.jpg"};
		
		public TestRunnable(BaseRecord user, int startIndex, int recordCount) {
			this.startIndex = startIndex;
			this.recordCount = recordCount;
			this.user = user;
		}

		
		
		public void setLatch(CountDownLatch latch) {
			this.latch = latch;
		}



		@Override
		public void run() {
			// TODO Auto-generated method stub
			
			int failCount = 0;
			int createCount = 0;
			int iter = 0;
			int errorCount = 0;
		    for(int i= 0; i < recordCount; i++) {
			    	String name = "Picture " + (startIndex + i + 1) + ".jpg";
			    	assertFalse("Name already exists: " + name, nameSet.contains(name));
			    	nameSet.add(name);
			    	logger.info((i + 1) + ") " + name);
		        BaseRecord group = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Data/Pictures", GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
				assertNotNull("Group is null for " + "~/Data/Pictures", group);

				BaseRecord data = null;
				BaseRecord trec = null;
				try {
					data = getCreateFileData(user, "~/Data/Pictures", "./media/" + sampleData[rand.nextInt(sampleData.length)], name);
					if(data != null) {
						trec = ThumbnailUtil.getCreateThumbnail(data, 250, 250);
					}
				} catch (FieldException | ValueException | ModelNotFoundException | FactoryException | IndexException
						| ReaderException | IOException e) {
					logger.error("Error creating data record: " + e.getMessage(), e);
				}
				assertNotNull("Data is null for " + name, data);
				assertNotNull("Thumbnail 250x250 is null for " + name, trec);
				
			}
			latch.countDown();
		}
		
	}
}


