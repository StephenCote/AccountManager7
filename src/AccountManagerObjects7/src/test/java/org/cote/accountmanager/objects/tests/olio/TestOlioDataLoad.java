package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Assume;
import org.junit.Test;

/**
 * Exercises the shared Olio corpus-provisioning backend ({@link WorldUtil#isOlioDataPresent(String)}
 * and {@link WorldUtil#loadOlioData(org.cote.accountmanager.record.BaseRecord, String, boolean)})
 * against the live corpus staged at {@code test.datagen.path}.
 * <p>
 * The load test is gated on the probe: if the corpus is not present it {@code Assume}-skips cleanly
 * rather than failing. It uses a NON-admin test user (the loader uses that user only to resolve the
 * organization; the actual writes are performed as the org's Olio principal) and never resets the DB
 * schema.
 */
public class TestOlioDataLoad extends BaseTest {

	/**
	 * Base corpus keys that {@code loadOlioData} must return with a positive count. {@code colors} is
	 * loaded from an embedded resource rather than the datagen corpus, but it is still populated into the
	 * universe so it is asserted here too. {@code locations} is intentionally absent — it is optional and
	 * gated behind {@code includeLocations}.
	 */
	private static final String[] REQUIRED_KEYS = new String[] {
		"dictionary", "occupations", "names", "surnames", "traits", "colors", "patterns"
	};

	@Test
	public void TestOlioDataPresentProbe() {
		String dataPath = testProperties.getProperty("test.datagen.path");

		/// Negative cases must hold regardless of whether the corpus is staged.
		assertFalse("null path should not probe present", WorldUtil.isOlioDataPresent(null));
		assertFalse("blank path should not probe present", WorldUtil.isOlioDataPresent("   "));
		assertFalse("bogus path should not probe present", WorldUtil.isOlioDataPresent("c:/no/such/olio/corpus/path"));

		Assume.assumeTrue("Skipping: Olio corpus not present at test.datagen.path=" + dataPath,
			WorldUtil.isOlioDataPresent(dataPath));
		assertTrue("configured corpus path should probe present", WorldUtil.isOlioDataPresent(dataPath));
	}

	@Test
	public void TestLoadOlioCorpus() {
		String dataPath = testProperties.getProperty("test.datagen.path");
		Assume.assumeTrue("Skipping: Olio corpus not present at test.datagen.path=" + dataPath,
			WorldUtil.isOlioDataPresent(dataPath));

		/// Non-admin test user. loadOlioData uses it only to resolve the organization context; the
		/// universe/world writes are performed as the org's Olio principal.
		BaseRecord user = getCreateUser("olioDataLoadTestUser");
		assertNotNull("Test user is null", user);

		/// includeLocations=false — base corpus only (location data is large and feature-gated).
		Map<String, Integer> counts = WorldUtil.loadOlioData(user, dataPath, false);
		assertNotNull("Counts map is null", counts);
		assertFalse("Counts map is empty — org/principal resolution or universe creation failed", counts.isEmpty());

		for(String key : REQUIRED_KEYS) {
			assertTrue("Missing corpus count for '" + key + "' in " + counts, counts.containsKey(key));
			Integer c = counts.get(key);
			assertNotNull("'" + key + "' count is null", c);
			assertTrue("'" + key + "' count should be > 0 but was " + c, c.intValue() > 0);
		}

		/// With includeLocations=false, loadLocations must not have been invoked.
		assertFalse("locations should not be present when includeLocations=false", counts.containsKey("locations"));

		logger.info("Olio corpus load counts: " + counts);
	}
}
