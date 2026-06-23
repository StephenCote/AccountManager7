package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.HealthModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-HC-001 — Triage Priority (ESI) Assignment, run live end-to-end as isoTester.
 */
@Category(LiveTest.class)
public class TestISO42001BiasHealth extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new HealthModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testHealthRunEndToEnd() {
		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, null);
		runAndAssert(new HealthModule(), tc, SEED, "Sharp chest pain");
	}
}
