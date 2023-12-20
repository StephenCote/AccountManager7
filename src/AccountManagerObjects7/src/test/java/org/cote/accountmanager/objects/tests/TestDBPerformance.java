package org.cote.accountmanager.objects.tests;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

public class TestDBPerformance extends BaseTest {

	@Test
	public void TestStatistics() {
		logger.info("Warm up");
		ioContext.getSearch().enableStatistics(true);
		int warmUp = ioContext.getSearch().getStatistics().cacheSize();
		int queryUp = ioContext.getSearch().getStatistics().size();
		logger.info("Statistics: " + queryUp);
		logger.info("Cache Statistics: " + warmUp);
		
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Performance");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		
		logger.info("Cool down");
		int coolDown = ioContext.getSearch().getStatistics().cacheSize();
		int queryDown = ioContext.getSearch().getStatistics().size();
		logger.info("Statistics: " + queryDown);
		logger.info("Cache Statistics: " + coolDown);
		

		ioContext.getSearch().enableStatistics(false);

	}
	
}
