package org.cote.accountmanager.objects.tests;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

public class TestQueryPlan extends BaseTest {

	private boolean debugBreak = true;
	
	@Test
	public void TestQueryPlan() {
		
		logger.info("Test Query Plan");

		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");;
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		q.planMost(true, OlioUtil.FULL_PLAN_FILTER);
		q.filterPlan(OlioModelNames.MODEL_INVENTORY_ENTRY, OlioFieldNames.FIELD_APPAREL);
		q.filterPlan(OlioModelNames.MODEL_STORE, FieldNames.FIELD_LOCATIONS);

		OlioUtil.prunePlan(q.plan());

		
		//QueryPlan qp = q.getPlan("apparel.wearables");
		//logger.info(qp.toFilteredString());
		// logger.info(q.toSelect());
		logger.info(q.plan().toFilteredString());
		//assertNotNull("Expected to find the query plan", qp);
		//logger.info(qp.toFullString());
		//logger.info(q.toSelect());


	}


}
