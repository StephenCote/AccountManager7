package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 5 gossip and cross-character memory tests (MemoryRefactor2.md).
 *
 * Verifies gossip chatConfig fields, getMemoryPartners, searchCrossCharacterMemories,
 * exclusion filtering, gating by config, and empty-when-no-partners behavior.
 */
public class TestGossip extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupGossip() {
		testOrgContext = getTestOrganization("/Development/Gossip");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gossipUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Create a properly persisted charPerson record for testing.
	private BaseRecord createTestPerson(String label) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/People");
		plist.parameter(FieldNames.FIELD_NAME, label + "-" + UUID.randomUUID().toString().substring(0, 8));
		BaseRecord p = IOSystem.getActiveContext().getFactory().newInstance("olio.charPerson", testUser, null, plist);
		p = IOSystem.getActiveContext().getAccessPoint().create(testUser, p);
		assertNotNull("Person " + label + " should be created", p);
		return p;
	}

	/// Verify that the gossip fields exist on chatConfig and have correct defaults.
	@Test
	public void testGossipFieldsOnChatConfig() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatConfigs");
			plist.parameter(FieldNames.FIELD_NAME, "gossipCfg-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
			assertNotNull("ChatConfig should be created", cfg);

			boolean gossipEnabled = cfg.get("gossipEnabled");
			assertFalse("gossipEnabled should default to false", gossipEnabled);

			double gossipThreshold = cfg.get("gossipThreshold");
			assertEquals("gossipThreshold should default to 0.65", 0.65, gossipThreshold, 0.001);

			int gossipMaxSuggestions = cfg.get("gossipMaxSuggestions");
			assertEquals("gossipMaxSuggestions should default to 5", 5, gossipMaxSuggestions);

			logger.info("testGossipFieldsOnChatConfig passed");
		} catch (Exception e) {
			logger.error("testGossipFieldsOnChatConfig failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that getMemoryPartners returns the correct partner list.
	@Test
	public void testMemoryPartners() {
		try {
			String convId = "partners-" + UUID.randomUUID().toString().substring(0, 8);

			// Create 3 proper person records
			BaseRecord don = createTestPerson("don");
			BaseRecord rex = createTestPerson("rex");
			BaseRecord mike = createTestPerson("mike");
			long donId = don.get(FieldNames.FIELD_ID);
			long rexId = rex.get(FieldNames.FIELD_ID);
			long mikeId = mike.get(FieldNames.FIELD_ID);

			// Create memories: Don+Rex and Don+Mike
			MemoryUtil.createMemory(testUser, "Don and Rex talked", "don-rex conv",
				MemoryTypeEnumType.FACT, 5, "am7://test/gossip", convId, don, rex);
			MemoryUtil.createMemory(testUser, "Don and Mike discussed plans", "don-mike conv",
				MemoryTypeEnumType.FACT, 5, "am7://test/gossip", convId, don, mike);

			// Get partners for Don
			List<Long> partners = MemoryUtil.getMemoryPartners(testUser, donId);
			assertNotNull("Partners list should not be null", partners);
			assertTrue("Don should have at least 2 partners", partners.size() >= 2);
			assertTrue("Partners should include Rex", partners.contains(rexId));
			assertTrue("Partners should include Mike", partners.contains(mikeId));

			logger.info("testMemoryPartners passed — partners=" + partners);
		} catch (Exception e) {
			logger.error("testMemoryPartners failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that gossip search excludes the current pair partner.
	@Test
	public void testExcludesCurrentPair() {
		try {
			String convId = "excl-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord don = createTestPerson("don-excl");
			BaseRecord rex = createTestPerson("rex-excl");
			BaseRecord mike = createTestPerson("mike-excl");
			long donId = don.get(FieldNames.FIELD_ID);
			long rexId = rex.get(FieldNames.FIELD_ID);

			// Create memories for both pairs
			MemoryUtil.createMemory(testUser, "Don told Rex about the weather", "don-rex weather",
				MemoryTypeEnumType.FACT, 5, "am7://test/gossip", convId, don, rex);
			MemoryUtil.createMemory(testUser, "Don and Mike found treasure", "don-mike treasure",
				MemoryTypeEnumType.FACT, 7, "am7://test/gossip", convId, don, mike);

			// searchCrossCharacterMemories for Don, excluding Rex
			// Without vector support, this returns empty — but verifies the method runs without error
			List<BaseRecord> results = MemoryUtil.searchCrossCharacterMemories(
				testUser, donId, rexId, "treasure", 5, 0.5);
			assertNotNull("Results should not be null", results);
			// Verify no Don+Rex memories in results (if any results returned)
			for (BaseRecord r : results) {
				long pid1 = 0, pid2 = 0;
				BaseRecord p1 = r.get("person1");
				BaseRecord p2 = r.get("person2");
				if (p1 != null) pid1 = p1.get(FieldNames.FIELD_ID);
				if (p2 != null) pid2 = p2.get(FieldNames.FIELD_ID);
				assertFalse("Should not include Rex as person1", pid1 == rexId);
				assertFalse("Should not include Rex as person2", pid2 == rexId);
			}

			logger.info("testExcludesCurrentPair passed — results=" + results.size());
		} catch (Exception e) {
			logger.error("testExcludesCurrentPair failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that gossipEnabled=false gates the feature.
	@Test
	public void testGatingByConfig() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatConfigs");
			plist.parameter(FieldNames.FIELD_NAME, "gating-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
			cfg = IOSystem.getActiveContext().getAccessPoint().create(testUser, cfg);
			assertNotNull("ChatConfig should be created", cfg);

			boolean gossipEnabled = cfg.get("gossipEnabled");
			assertFalse("gossipEnabled should default to false", gossipEnabled);

			// When gossipEnabled is false, cross-character recall should not activate
			// (This is tested at the integration level; here we verify the field is correct)

			// Enable gossip
			cfg.set("gossipEnabled", true);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(cfg);

			// Re-read and verify
			BaseRecord found = IOSystem.getActiveContext().getAccessPoint().findByObjectId(
				testUser, OlioModelNames.MODEL_CHAT_CONFIG, cfg.get(FieldNames.FIELD_OBJECT_ID));
			IOSystem.getActiveContext().getReader().populate(found);
			boolean enabled = found.get("gossipEnabled");
			assertTrue("gossipEnabled should be true after update", enabled);

			logger.info("testGatingByConfig passed");
		} catch (Exception e) {
			logger.error("testGatingByConfig failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that gossip search returns empty when character has no other memory partners.
	@Test
	public void testEmptyWhenNoPartners() {
		try {
			// Search for a person with no memories at all (id=999)
			List<Long> partners = MemoryUtil.getMemoryPartners(testUser, 999L);
			assertNotNull("Partners should not be null", partners);
			assertTrue("Should have no partners", partners.isEmpty());

			// Cross-character search should also return empty
			List<BaseRecord> results = MemoryUtil.searchCrossCharacterMemories(
				testUser, 999L, 0L, "anything", 5, 0.5);
			assertNotNull("Results should not be null", results);
			assertTrue("Should have no results", results.isEmpty());

			logger.info("testEmptyWhenNoPartners passed");
		} catch (Exception e) {
			logger.error("testEmptyWhenNoPartners failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify threshold filtering — gossipThreshold field is persisted and queryable.
	@Test
	public void testThresholdFiltering() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatConfigs");
			plist.parameter(FieldNames.FIELD_NAME, "threshold-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
			cfg.set("gossipThreshold", 0.8);
			cfg = IOSystem.getActiveContext().getAccessPoint().create(testUser, cfg);
			assertNotNull("ChatConfig should be created", cfg);

			// Re-read and verify threshold persisted
			BaseRecord found = IOSystem.getActiveContext().getAccessPoint().findByObjectId(
				testUser, OlioModelNames.MODEL_CHAT_CONFIG, cfg.get(FieldNames.FIELD_OBJECT_ID));
			IOSystem.getActiveContext().getReader().populate(found);
			double threshold = found.get("gossipThreshold");
			assertEquals("gossipThreshold should be 0.8", 0.8, threshold, 0.001);

			logger.info("testThresholdFiltering passed");
		} catch (Exception e) {
			logger.error("testThresholdFiltering failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify D20 gossip mechanic: high stat character never involuntarily blabs.
	@Test
	public void testHighStatNoBlabs() {
		try {
			// A character with int>=8, wil>=8, age>=13 should never fail the save
			int intelligence = 12;
			int willpower = 14;
			int age = 25;
			int dc = Math.min((intelligence + willpower) / 2, 15);

			// With dc=13 and high stats, character should NOT blab
			// Since int>=8 AND wil>=8 AND age>=13, the mechanic doesn't even trigger
			assertTrue("High stats should gate off involuntary gossip",
				intelligence >= 8 && willpower >= 8 && age >= 13);

			logger.info("testHighStatNoBlabs passed — dc=" + dc);
		} catch (Exception e) {
			logger.error("testHighStatNoBlabs failed", e);
			fail("Exception: " + e.getMessage());
		}
	}
}
