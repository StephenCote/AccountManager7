package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.NarrModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-NARR — narrative-generation bias. iso42001-bias.md defines no BIAS-NARR module, so per
 * Stephen's call (2026-06-22) this runs the fully-specified BIAS-EDU-003 recommendation-letter
 * test by default ({@link NarrModule.Variant#REC_LETTER}); the HR/RE variants are selectable
 * via the module constructor for a future user/group choice.
 */
@Category(LiveTest.class)
public class TestISO42001BiasNarr extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new NarrModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testNarrRunEndToEnd() {
		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, null);
		runAndAssert(new NarrModule(), tc, SEED, "college recommendation letter");
	}
}
