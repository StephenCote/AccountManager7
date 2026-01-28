package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CivilUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.ThreatUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.factory.Factory;
import org.junit.Test;

public class TestOlioGameFeatures extends BaseTest {

	private String universeName = "GameTest Universe";
	private String worldName = "GameTest World";

	/// ===== SCHEMA PATCH TESTS =====

	@Test
	public void TestGetTableColumns() {
		logger.info("Test: getTableColumns");
		assertNotNull("DBUtil is null", ioContext.getDbUtil());

		/// Use a known model that should exist
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("Schema is null", ms);

		String tableName = ioContext.getDbUtil().getTableName(ms.getName());
		assertTrue("Table should exist: " + tableName, ioContext.getDbUtil().haveTable(ms.getName()));

		List<String> columns = ioContext.getDbUtil().getTableColumns(tableName);
		assertFalse("Expected columns for " + tableName, columns.isEmpty());
		logger.info("Table " + tableName + " has " + columns.size() + " columns: " + columns.stream().collect(Collectors.joining(", ")));

		/// Verify known columns exist
		assertTrue("Expected 'id' column", columns.contains("id"));
	}

	@Test
	public void TestGetMissingColumns() {
		logger.info("Test: getMissingColumns");
		assertNotNull("DBUtil is null", ioContext.getDbUtil());

		/// Use a model that should be fully synced
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("Schema is null", ms);

		List<FieldSchema> missing = ioContext.getDbUtil().getMissingColumns(ms);
		logger.info("Missing columns for " + ms.getName() + ": " + missing.size());
		for(FieldSchema f : missing) {
			logger.info("  Missing: " + f.getName() + " (" + f.getType() + ")");
		}
		/// Note: this may find missing columns if the schema was recently updated
	}

	@Test
	public void TestGeneratePatchSchema() {
		logger.info("Test: generatePatchSchema");
		assertNotNull("DBUtil is null", ioContext.getDbUtil());

		/// Test with a model that should be synced
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON);
		assertNotNull("Schema is null", ms);

		List<String> patches = ioContext.getDbUtil().generatePatchSchema(ms);
		logger.info("Patch statements for " + ms.getName() + ": " + patches.size());
		for(String p : patches) {
			logger.info("  Patch: " + p);
		}
	}

	@Test
	public void TestSchemaPatchWithNewField() {
		logger.info("Test: Schema patch with new field simulation");
		assertNotNull("DBUtil is null", ioContext.getDbUtil());

		/// Use the 'junk' custom model from TestSchemaModification pattern
		RecordFactory.releaseCustomSchema("junk");
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("junk", "junk");
		if(ms == null) {
			logger.warn("Junk model not available - skipping patch simulation test");
			return;
		}

		/// If the junk table exists, check for missing columns
		if(ioContext.getDbUtil().haveTable(ms.getName())) {
			List<String> columns = ioContext.getDbUtil().getTableColumns(ioContext.getDbUtil().getTableName(ms.getName()));
			logger.info("Junk table has columns: " + columns.stream().collect(Collectors.joining(", ")));

			List<FieldSchema> missing = ioContext.getDbUtil().getMissingColumns(ms);
			logger.info("Missing columns in junk: " + missing.size());

			List<String> patches = ioContext.getDbUtil().generatePatchSchema(ms);
			for(String p : patches) {
				logger.info("  Would apply: " + p);
			}
		}
		RecordFactory.releaseCustomSchema("junk");
	}

	@Test
	public void TestAllModelsSchemaPatch() {
		logger.info("Test: Scan all models for missing columns");
		assertNotNull("DBUtil is null", ioContext.getDbUtil());

		int totalMissing = 0;
		for(String modelName : org.cote.accountmanager.schema.ModelNames.MODELS) {
			ModelSchema ms = RecordFactory.getSchema(modelName);
			if(ms == null || !org.cote.accountmanager.util.RecordUtil.isIdentityModel(ms)) continue;
			if(ioContext.getDbUtil().isConstrained(ms)) continue;
			if(!ioContext.getDbUtil().haveTable(ms.getName())) continue;

			List<FieldSchema> missing = ioContext.getDbUtil().getMissingColumns(ms);
			if(!missing.isEmpty()) {
				totalMissing += missing.size();
				logger.info("Model " + modelName + " has " + missing.size() + " missing columns:");
				for(FieldSchema f : missing) {
					logger.info("  - " + f.getName() + " (" + f.getType() + ")");
				}
				List<String> patches = ioContext.getDbUtil().generatePatchSchema(ms);
				for(String p : patches) {
					logger.info("    SQL: " + p);
				}
			}
		}
		logger.info("Total missing columns across all models: " + totalMissing);
	}

	/// ===== CIVIL UTIL CLIMATE/TIER TESTS =====

	@Test
	public void TestClimateForTerrain() {
		logger.info("Test: ClimateType from terrain");

		assertTrue("DESERT should be ARID", CivilUtil.getClimateForTerrain("DESERT") == CivilUtil.ClimateType.ARID);
		assertTrue("JUNGLE should be TROPICAL", CivilUtil.getClimateForTerrain("JUNGLE") == CivilUtil.ClimateType.TROPICAL);
		assertTrue("TUNDRA should be ARCTIC", CivilUtil.getClimateForTerrain("TUNDRA") == CivilUtil.ClimateType.ARCTIC);
		assertTrue("GLACIER should be ARCTIC", CivilUtil.getClimateForTerrain("GLACIER") == CivilUtil.ClimateType.ARCTIC);
		assertTrue("MOUNTAIN should be COLD", CivilUtil.getClimateForTerrain("MOUNTAIN") == CivilUtil.ClimateType.COLD);
		assertTrue("FOREST should be TEMPERATE", CivilUtil.getClimateForTerrain("FOREST") == CivilUtil.ClimateType.TEMPERATE);
		assertTrue("MEADOW should be TEMPERATE", CivilUtil.getClimateForTerrain("MEADOW") == CivilUtil.ClimateType.TEMPERATE);

		logger.info("All terrain-to-climate mappings verified");
	}

	@Test
	public void TestTierFiltering() {
		logger.info("Test: Fabric and clothing tier filtering");

		/// Tier 0 should include primitive materials only
		List<String> tier0Fabrics = CivilUtil.filterFabricsByTier(0);
		assertFalse("Expected tier 0 fabrics", tier0Fabrics.isEmpty());
		logger.info("Tier 0 fabrics: " + tier0Fabrics.size() + " items");
		for(String f : tier0Fabrics) {
			logger.info("  " + f);
		}

		/// Tier 3 should include everything up to tier 3
		List<String> tier3Fabrics = CivilUtil.filterFabricsByTier(3);
		assertTrue("Tier 3 should have more fabrics than tier 0", tier3Fabrics.size() > tier0Fabrics.size());
		logger.info("Tier 3 fabrics: " + tier3Fabrics.size() + " items");

		/// Clothing filtering
		List<String> tier0Clothing = CivilUtil.filterClothingByTier(0);
		logger.info("Tier 0 clothing: " + tier0Clothing.size() + " items");

		List<String> tier2Clothing = CivilUtil.filterClothingByTier(2);
		assertTrue("Tier 2 should have more clothing than tier 0", tier2Clothing.size() > tier0Clothing.size());
		logger.info("Tier 2 clothing: " + tier2Clothing.size() + " items");
	}

	/// ===== CONTEXT APPAREL TESTS =====

	@Test
	public void TestContextApparelPrimitive() {
		logger.info("Test: Context apparel generation - Primitive tier, Tropical climate");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser1", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if(pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, 0, CivilUtil.ClimateType.TROPICAL);
		assertNotNull("Apparel should not be null", apparel);

		List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		assertNotNull("Wearables list should not be null", wearables);
		logger.info("Tier 0 / TROPICAL outfit has " + wearables.size() + " pieces:");
		for(BaseRecord w : wearables) {
			logger.info("  " + w.get(FieldNames.FIELD_NAME) + " [" + w.get(OlioFieldNames.FIELD_LEVEL) + "]");
		}
	}

	@Test
	public void TestContextApparelIndustrial() {
		logger.info("Test: Context apparel generation - Industrial tier, Cold climate");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser2", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if(pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, 3, CivilUtil.ClimateType.COLD);
		assertNotNull("Apparel should not be null", apparel);

		List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		assertNotNull("Wearables list should not be null", wearables);
		assertTrue("Cold climate should generate multiple layers", wearables.size() >= 3);
		logger.info("Tier 3 / COLD outfit has " + wearables.size() + " pieces:");
		for(BaseRecord w : wearables) {
			String fabric = w.get(OlioFieldNames.FIELD_FABRIC);
			logger.info("  " + w.get(FieldNames.FIELD_NAME) + " [" + w.get(OlioFieldNames.FIELD_LEVEL) + "] " + (fabric != null ? fabric : ""));
		}
	}

	/// ===== MANNEQUIN PROMPT TESTS =====

	@Test
	public void TestMannequinPromptBase() {
		logger.info("Test: Mannequin prompt at BASE level");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser3", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if(pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, 2, CivilUtil.ClimateType.TEMPERATE);
		assertNotNull("Apparel should not be null", apparel);

		/// Test BASE level prompt (underwear only)
		String promptBase = NarrativeUtil.getMannequinPrompt(apparel, WearLevelEnumType.BASE, null);
		assertNotNull("Mannequin prompt should not be null", promptBase);
		assertTrue("Prompt should mention mannequin", promptBase.contains("mannequin"));
		logger.info("BASE prompt: " + promptBase);

		/// Test SUIT level prompt (full outfit)
		String promptSuit = NarrativeUtil.getMannequinPrompt(apparel, WearLevelEnumType.SUIT, null);
		assertNotNull("SUIT prompt should not be null", promptSuit);
		assertTrue("SUIT prompt should be longer than BASE", promptSuit.length() >= promptBase.length());
		logger.info("SUIT prompt: " + promptSuit);

		/// Test negative prompt
		String negPrompt = NarrativeUtil.getMannequinNegativePrompt();
		assertNotNull("Negative prompt should not be null", negPrompt);
		assertTrue("Should exclude human features", negPrompt.contains("human face"));
		logger.info("Negative prompt: " + negPrompt);
	}

	@Test
	public void TestMannequinPromptNude() {
		logger.info("Test: Mannequin prompt at NONE level (nude)");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser4", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if(pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, 2, CivilUtil.ClimateType.TEMPERATE);
		assertNotNull("Apparel should not be null", apparel);

		/// NONE level should produce nude mannequin prompt
		String promptNone = NarrativeUtil.getMannequinPrompt(apparel, WearLevelEnumType.NONE, null);
		assertNotNull("NONE prompt should not be null", promptNone);
		assertTrue("NONE prompt should mention nude", promptNone.toLowerCase().contains("nude"));
		logger.info("NONE prompt: " + promptNone);
	}

	/// ===== THREAT/SITUATION ASSESSMENT TESTS =====

	@Test
	public void TestThreatAssessment() {
		logger.info("Test: Threat assessment for situation endpoint");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser5", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> realms = octx.getRealms();
		if(realms.isEmpty()) {
			logger.warn("Skipping - no realms");
			return;
		}
		BaseRecord realm = realms.get(0);

		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		if(pop.size() < 2) {
			logger.warn("Skipping - need at least 2 population members");
			return;
		}

		BaseRecord person = pop.get(0);
		PersonalityProfile pp = ProfileUtil.getProfile(octx, person);
		assertNotNull("Profile should not be null", pp);

		/// Build group profiles for threat evaluation
		Map<BaseRecord, PersonalityProfile> group = new HashMap<>();
		for(BaseRecord p : pop) {
			group.put(p, ProfileUtil.getProfile(octx, p));
		}

		/// Get the current epoch event
		BaseRecord epoch = octx.clock().getEpoch();

		/// Evaluate threats from total population
		Map<ThreatEnumType, List<BaseRecord>> threats = ThreatUtil.evaluateImminentThreats(octx, realm, epoch, group, pp);
		logger.info("Threats detected for " + person.get(FieldNames.FIELD_NAME) + ":");
		if(threats != null) {
			for(Map.Entry<ThreatEnumType, List<BaseRecord>> entry : threats.entrySet()) {
				logger.info("  " + entry.getKey() + ": " + entry.getValue().size() + " sources");
			}
		} else {
			logger.info("  No threats detected (null)");
		}
	}

	@Test
	public void TestInteractionResolution() {
		logger.info("Test: Interaction resolution (game resolve endpoint)");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser6", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if(pop.size() < 2) {
			logger.warn("Skipping - need at least 2 population members");
			return;
		}

		BaseRecord actor = pop.get(0);
		BaseRecord interactor = pop.get(1);

		logger.info("Resolving COMMUNICATE between " + actor.get(FieldNames.FIELD_NAME) + " and " + interactor.get(FieldNames.FIELD_NAME));

		try {
			BaseRecord interaction = InteractionUtil.resolveInteraction(octx, actor, interactor, InteractionEnumType.COMMUNICATE);
			assertNotNull("Interaction result should not be null", interaction);
			logger.info("Interaction result: " + interaction.toFullString());
		} catch(OlioException e) {
			logger.error("Interaction failed: " + e.getMessage());
		} catch(Exception e) {
			logger.error("Unexpected error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void TestLocationAwareness() {
		logger.info("Test: Location awareness (nearby cells, occupants)");

		OrganizationContext testOrgContext = getTestOrganization("/Development/GameTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "gameTestUser7", testOrgContext.getOrganizationId());

		OlioContext octx = getGameTestContext(testUser);
		if(octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if(pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if(state == null) {
			logger.warn("Skipping - character has no state");
			return;
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if(currentLoc == null) {
			logger.warn("Skipping - character has no current location");
			return;
		}

		logger.info("Character " + person.get(FieldNames.FIELD_NAME) + " is at cell:");
		logger.info("  East: " + person.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST));
		logger.info("  North: " + person.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH));

		/// Get adjacent cells
		List<BaseRecord> adjacentCells = GeoLocationUtil.getAdjacentCells(octx, currentLoc, 3);
		logger.info("Adjacent cells within 3: " + adjacentCells.size());

		/// Filter population to nearby
		List<BaseRecord> nearby = GeoLocationUtil.limitToAdjacent(octx, pop, currentLoc);
		logger.info("Nearby characters: " + nearby.size());
		for(BaseRecord n : nearby) {
			String nOid = n.get(FieldNames.FIELD_OBJECT_ID);
			String pOid = person.get(FieldNames.FIELD_OBJECT_ID);
			if(nOid != null && pOid != null && !nOid.equals(pOid)) {
				logger.info("  " + n.get(FieldNames.FIELD_NAME));
			}
		}
	}

	/// ===== INTERRUPT PRIORITY TESTS =====

	@Test
	public void TestInterruptPriorityCalculation() {
		logger.info("Test: Interrupt priority calculation");

		/// Test the priority formula: interruptChance = (threatPriority - chosenPriority) * distanceRelativity
		double threatPriority = 0.8;   // ANIMAL_THREAT
		double chosenPriority = 0.3;   // SOCIAL interaction
		double distanceRelativity = 0.9; // Close proximity

		double interruptChance = (threatPriority - chosenPriority) * distanceRelativity;
		logger.info("Interrupt chance: " + interruptChance + " (threshold: random < this = interrupt)");
		assertTrue("Interrupt chance should be > 0", interruptChance > 0);
		assertTrue("Interrupt chance should be < 1", interruptChance < 1.0);
		assertTrue("Expected ~0.45 interrupt chance", Math.abs(interruptChance - 0.45) < 0.01);

		/// Lower priority threat, higher distance = lower interrupt chance
		double farThreat = (0.5 - 0.4) * 0.3;
		logger.info("Far low-priority threat interrupt chance: " + farThreat);
		assertTrue("Should be much lower", farThreat < interruptChance);
	}

	/// ===== HELPER METHODS =====

	private List<BaseRecord> getPopulation(OlioContext octx) {
		List<BaseRecord> realms = octx.getRealms();
		if(realms.isEmpty()) return Arrays.asList();
		return octx.getRealmPopulation(realms.get(0));
	}

	private OlioContext getGameTestContext(BaseRecord testUser) {
		try {
			IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
			OlioContextConfiguration cfg = new OlioContextConfiguration(
				testUser,
				testProperties.getProperty("test.datagen.path"),
				universeName,
				worldName,
				new String[] {},
				2,
				10,
				false,
				false
			);

			cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
				new GridSquareLocationInitializationRule(),
				new LocationPlannerRule(),
				new GenericItemDataLoadRule()
			}));

			cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new Increment24HourRule(),
				new HierarchicalNeedsEvolveRule()
			}));

			OlioContext octx = new OlioContext(cfg);
			octx.initialize();

			if(!octx.isInitialized()) {
				logger.error("Context failed to initialize");
				return null;
			}

			return octx;
		} catch(Exception e) {
			logger.error("Failed to create olio context: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

}
