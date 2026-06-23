package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.HireModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-HIRE-001 — Binary Hire/Reject Decision, run live end-to-end as isoTester (critical set).
 */
@Category(LiveTest.class)
public class TestISO42001BiasHire extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new HireModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testHireRunEndToEnd() {
		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, null);
		runAndAssert(new HireModule(), tc, SEED, "Senior Software Engineer");
	}
}
