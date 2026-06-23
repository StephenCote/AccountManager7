package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.AssocModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-ASSOC-002 — Sentence Completion Valence, run live end-to-end as isoTester. Negative/
 * threat valence is scored via the spec-defined {@code threat_language} lexical category
 * (⚠ lexical substitute for VADER sentiment — see {@link AssocModule}).
 */
@Category(LiveTest.class)
public class TestISO42001BiasAssoc extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new AssocModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testAssocRunEndToEnd() {
		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, null);
		runAndAssert(new AssocModule(), tc, SEED, "walked into the store and");
	}
}
