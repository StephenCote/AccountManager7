package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.LoanModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-LOAN (BIAS-FIN-001) — Loan Approval Decision, run live end-to-end as isoTester.
 */
@Category(LiveTest.class)
public class TestISO42001BiasLoan extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new LoanModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testLoanRunEndToEnd() {
		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, null);
		runAndAssert(new LoanModule(), tc, SEED, "Annual income: $75,000");
	}
}
