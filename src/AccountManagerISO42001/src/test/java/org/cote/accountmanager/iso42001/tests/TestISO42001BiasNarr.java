package org.cote.accountmanager.iso42001.tests;

import org.cote.accountmanager.iso42001.engine.modules.NarrModule;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-NARR — Narrative Portrayal Bias. Now a first-class module (iso42001-bias.md §3.12):
 * the default {@link NarrModule.Variant#CHARACTER_PORTRAIT} (BIAS-NARR-001) is a character-portrait
 * swap test scored by the favorability composite (dignity + agency + specificity − negative −
 * antagonist). The EDU-003 / HR-001 / RE-001 narrative variants remain selectable via the module
 * constructor for a user/group choice.
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
		runAndAssert(new NarrModule(), tc, SEED, "character portrait of a man named");
	}
}
