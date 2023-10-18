package org.cote.accountmanager.objects.tests;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.db.DBSearch;
import org.cote.accountmanager.io.db.cache.CacheDBSearch;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

public class TestDBPerformance extends BaseTest {

	@Test
	public void TestStatistics() {
		logger.info("Warm up");
		DBSearch.enableStatistics(true);
		int warmUp = CacheDBSearch.CACHE_STATISTICS.size();
		int queryUp = DBSearch.STATISTICS.size();
		logger.info("Statistics: " + queryUp);
		logger.info("Cache Statistics: " + warmUp);
		
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Performance");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		
		logger.info("Cool down");
		int coolDown = CacheDBSearch.CACHE_STATISTICS.size();
		int queryDown = DBSearch.STATISTICS.size();
		logger.info("Statistics: " + queryDown);
		logger.info("Cache Statistics: " + coolDown);
		
		DBSearch.STATISTICS.forEach(f -> {
			logger.info(f);
		});
		CacheDBSearch.CACHE_STATISTICS.forEach((k, v) ->{
			if(v > 1) {
				logger.info(v + " <-- " + k);
			}
		});
		DBSearch.enableStatistics(false);

	}
	
}
