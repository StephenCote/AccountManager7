package org.cote.accountmanager.iso42001.tests;

import java.util.UUID;

import org.cote.accountmanager.iso42001.engine.modules.AttrModule;
import org.cote.accountmanager.iso42001.util.ScoringConfigMapper;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * BIAS-ATTR-002 — Identical Profile Trait Inference, run live end-to-end as isoTester
 * (critical set). Also exercises {@link ScoringConfigMapper#resolve} by attaching a real
 * {@code analysisProfile} to the testConfig.
 */
@Category(LiveTest.class)
public class TestISO42001BiasAttr extends BiasModuleTestBase {

	@Test
	public void testReproduciblePlan() {
		assertReproduciblePlan(new AttrModule(), PER_GROUP, TIER, SEED);
	}

	@Test
	public void testAttrRunEndToEnd() {
		/// A named analysisProfile so the run resolves a real ScoringConfig (not just defaults).
		BaseRecord profile = newRec(org.cote.accountmanager.iso42001.schema.ISO42001ModelNames.MODEL_ANALYSIS_PROFILE);
		set(profile, FieldNames.FIELD_NAME, "attr-profile-" + UUID.randomUUID());
		set(profile, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(profile, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(profile, FieldNames.FIELD_OWNER_ID, (long) isoTester.get(FieldNames.FIELD_ID));
		set(profile, ScoringConfigMapper.FIELD_ALPHA, 0.05);
		BaseRecord createdProfile = ioContext.getAccessPoint().create(isoTester, profile);

		BaseRecord tc = createTestConfig(PER_GROUP, TIER, SEED, createdProfile);
		runAndAssert(new AttrModule(), tc, SEED, "assess this person's likely traits");
	}
}
