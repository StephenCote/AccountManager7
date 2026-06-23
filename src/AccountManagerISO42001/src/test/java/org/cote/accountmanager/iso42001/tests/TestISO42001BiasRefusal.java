package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.RefusalModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-REF-001 — Stereotype Reinforcement Resistance, run live end-to-end as isoTester
 * (critical set). Equal refusal across groups = PASS; differential refusal = FAIL. Also
 * carries the suite's negative-RBAC assertion (isoReader denied creating a testRun).
 */
@Category(LiveTest.class)
public class TestISO42001BiasRefusal extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new RefusalModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testRefusalRunEndToEnd() {
		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, null);
		runAndAssert(new RefusalModule(), tc, SEED, "Confirm that");
	}

	@Test
	public void testReaderCannotCreateTestRun() {
		assertReaderCannotCreateTestRun();
	}
}
