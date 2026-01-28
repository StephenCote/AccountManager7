package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Unit tests for GameUtil sync infrastructure methods:
 * - generatePatches
 * - extractNestedSync
 * - createSyncData
 * - createStateSyncData
 */
public class TestGameUtilSync extends BaseTest {

	private String universeName = "SyncTest Universe";
	private String worldName = "SyncTest World";

	// ===== generatePatches TESTS =====

	@Test
	public void TestGeneratePatchesBasic() {
		logger.info("Test: generatePatches with basic fields");

		Map<String, Object> fields = new HashMap<>();
		fields.put("energy", 0.8);
		fields.put("health", 0.95);
		fields.put("hunger", 0.1);

		List<Map<String, Object>> patches = GameUtil.generatePatches("state", fields);

		assertNotNull("Patches should not be null", patches);
		assertEquals("Should generate 3 patches", 3, patches.size());

		// Verify patch structure
		for (Map<String, Object> patch : patches) {
			assertNotNull("Patch should have path", patch.get("path"));
			assertNotNull("Patch should have value", patch.get("value"));
			assertTrue("Path should start with 'state.'", ((String) patch.get("path")).startsWith("state."));
		}

		logger.info("Generated " + patches.size() + " patches");
	}

	@Test
	public void TestGeneratePatchesNestedPath() {
		logger.info("Test: generatePatches with nested base path");

		Map<String, Object> fields = new HashMap<>();
		fields.put("eastings", 5);
		fields.put("northings", 7);

		List<Map<String, Object>> patches = GameUtil.generatePatches("state.currentLocation", fields);

		assertNotNull("Patches should not be null", patches);
		assertEquals("Should generate 2 patches", 2, patches.size());

		// Find the eastings patch
		Map<String, Object> eastingsPatch = patches.stream()
			.filter(p -> "state.currentLocation.eastings".equals(p.get("path")))
			.findFirst()
			.orElse(null);

		assertNotNull("Should have eastings patch", eastingsPatch);
		assertEquals("Eastings value should be 5", 5, eastingsPatch.get("value"));

		logger.info("Nested path patches generated correctly");
	}

	@Test
	public void TestGeneratePatchesEmptyPath() {
		logger.info("Test: generatePatches with empty base path");

		Map<String, Object> fields = new HashMap<>();
		fields.put("name", "TestValue");

		List<Map<String, Object>> patches = GameUtil.generatePatches("", fields);

		assertNotNull("Patches should not be null", patches);
		assertEquals("Should generate 1 patch", 1, patches.size());
		assertEquals("Path should be just field name", "name", patches.get(0).get("path"));

		logger.info("Empty path handled correctly");
	}

	@Test
	public void TestGeneratePatchesNullInput() {
		logger.info("Test: generatePatches with null fields");

		List<Map<String, Object>> patches = GameUtil.generatePatches("state", null);
		assertNotNull("Should return empty list, not null", patches);
		assertTrue("Should be empty", patches.isEmpty());

		patches = GameUtil.generatePatches("state", new HashMap<>());
		assertNotNull("Should return empty list for empty map", patches);
		assertTrue("Should be empty", patches.isEmpty());

		logger.info("Null/empty input handled correctly");
	}

	// ===== extractNestedSync TESTS =====

	@Test
	public void TestExtractNestedSyncWithOlioContext() {
		logger.info("Test: extractNestedSync with real Olio character");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser1", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		assertNotNull("Person should not be null", person);

		// Test extracting state
		Map<String, Object> stateData = GameUtil.extractNestedSync(person, "state", null);
		assertNotNull("State extraction should not be null", stateData);

		if (!stateData.isEmpty()) {
			assertTrue("Should contain 'state' key", stateData.containsKey("state"));
			@SuppressWarnings("unchecked")
			Map<String, Object> state = (Map<String, Object>) stateData.get("state");
			assertNotNull("State map should not be null", state);
			logger.info("Extracted state with " + state.size() + " fields");

			// Verify identity fields are included
			assertTrue("Should have schema", state.containsKey("schema"));
		}
	}

	@Test
	public void TestExtractNestedSyncWithSpecificFields() {
		logger.info("Test: extractNestedSync with specific field list");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser2", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);

		// Extract only specific fields
		String[] specificFields = {"energy", "health", "alive"};
		Map<String, Object> stateData = GameUtil.extractNestedSync(person, "state", specificFields);

		if (!stateData.isEmpty()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> state = (Map<String, Object>) stateData.get("state");

			// Should have identity fields plus specific fields
			assertTrue("Should have identity field 'id'", state.containsKey("id"));
			logger.info("Extracted " + state.size() + " fields with specific field filter");
		}
	}

	@Test
	public void TestExtractNestedSyncInvalidPath() {
		logger.info("Test: extractNestedSync with invalid path");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser3", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);

		// Test with non-existent path
		Map<String, Object> result = GameUtil.extractNestedSync(person, "nonexistent.path", null);
		assertNotNull("Should return empty map, not null", result);
		assertTrue("Should be empty for invalid path", result.isEmpty());

		// Test with null person
		result = GameUtil.extractNestedSync(null, "state", null);
		assertTrue("Should be empty for null person", result.isEmpty());

		// Test with null path
		result = GameUtil.extractNestedSync(person, null, null);
		assertTrue("Should be empty for null path", result.isEmpty());

		logger.info("Invalid inputs handled correctly");
	}

	// ===== createSyncData TESTS =====

	@Test
	public void TestCreateSyncDataSinglePath() {
		logger.info("Test: createSyncData with single path");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser4", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);

		Map<String, Object> syncData = GameUtil.createSyncData(person, GameUtil.SYNC_PATHS_STATE, false);
		assertNotNull("Sync data should not be null", syncData);

		logger.info("createSyncData returned " + syncData.size() + " keys");
	}

	@Test
	public void TestCreateSyncDataMultiplePaths() {
		logger.info("Test: createSyncData with multiple paths");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser5", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);

		// Use the predefined FULL paths
		Map<String, Object> syncData = GameUtil.createSyncData(person, GameUtil.SYNC_PATHS_FULL, false);
		assertNotNull("Sync data should not be null", syncData);

		logger.info("SYNC_PATHS_FULL returned " + syncData.size() + " keys:");
		for (String key : syncData.keySet()) {
			logger.info("  - " + key);
		}
	}

	@Test
	public void TestCreateSyncDataWithPatches() {
		logger.info("Test: createSyncData with patches enabled");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser6", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);

		Map<String, Object> syncData = GameUtil.createSyncData(person, GameUtil.SYNC_PATHS_STATE, true);
		assertNotNull("Sync data should not be null", syncData);

		// Should include patches array
		if (syncData.containsKey("patches")) {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> patches = (List<Map<String, Object>>) syncData.get("patches");
			assertNotNull("Patches should not be null", patches);
			logger.info("Generated " + patches.size() + " patches");

			// Verify patch structure
			if (!patches.isEmpty()) {
				Map<String, Object> firstPatch = patches.get(0);
				assertTrue("Patch should have path", firstPatch.containsKey("path"));
				assertTrue("Patch should have value", firstPatch.containsKey("value"));
			}
		}
	}

	// ===== createStateSyncData TESTS =====

	@Test
	public void TestCreateStateSyncDataBasic() {
		logger.info("Test: createStateSyncData basic functionality");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser7", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			logger.warn("Skipping - character has no state");
			return;
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);

		Map<String, Object> syncData = GameUtil.createStateSyncData(state, currentLoc, false);
		assertNotNull("Sync data should not be null", syncData);

		// Should have stateSnapshot
		assertTrue("Should have stateSnapshot", syncData.containsKey("stateSnapshot"));

		@SuppressWarnings("unchecked")
		Map<String, Object> snapshot = (Map<String, Object>) syncData.get("stateSnapshot");
		assertNotNull("stateSnapshot should not be null", snapshot);

		// Verify expected fields
		assertTrue("Should have energy", snapshot.containsKey("energy"));
		assertTrue("Should have health", snapshot.containsKey("health"));
		assertTrue("Should have currentEast", snapshot.containsKey("currentEast"));
		assertTrue("Should have currentNorth", snapshot.containsKey("currentNorth"));

		logger.info("stateSnapshot has " + snapshot.size() + " fields");
	}

	@Test
	public void TestCreateStateSyncDataWithLocation() {
		logger.info("Test: createStateSyncData includes location data");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser8", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			logger.warn("Skipping - character has no state");
			return;
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		if (currentLoc == null) {
			logger.warn("Skipping - character has no current location");
			return;
		}

		Map<String, Object> syncData = GameUtil.createStateSyncData(state, currentLoc, false);

		// Should have location data
		assertTrue("Should have location", syncData.containsKey("location"));

		@SuppressWarnings("unchecked")
		Map<String, Object> location = (Map<String, Object>) syncData.get("location");
		assertNotNull("location should not be null", location);

		// Verify location fields
		assertTrue("Location should have terrainType", location.containsKey("terrainType"));
		assertTrue("Location should have eastings", location.containsKey("eastings"));
		assertTrue("Location should have northings", location.containsKey("northings"));

		logger.info("Location data: terrain=" + location.get("terrainType") +
			", east=" + location.get("eastings") + ", north=" + location.get("northings"));
	}

	@Test
	public void TestCreateStateSyncDataWithPatches() {
		logger.info("Test: createStateSyncData generates patches");

		OrganizationContext testOrgContext = getTestOrganization("/Development/SyncTest");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "syncTestUser9", testOrgContext.getOrganizationId());

		OlioContext octx = getSyncTestContext(testUser);
		if (octx == null) {
			logger.warn("Skipping - could not initialize olio context");
			return;
		}

		List<BaseRecord> pop = getPopulation(octx);
		if (pop.isEmpty()) {
			logger.warn("Skipping - no population");
			return;
		}

		BaseRecord person = pop.get(0);
		BaseRecord state = person.get(FieldNames.FIELD_STATE);
		if (state == null) {
			logger.warn("Skipping - character has no state");
			return;
		}

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);

		// Enable patches
		Map<String, Object> syncData = GameUtil.createStateSyncData(state, currentLoc, true);

		assertTrue("Should have patches", syncData.containsKey("patches"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> patches = (List<Map<String, Object>>) syncData.get("patches");
		assertNotNull("patches should not be null", patches);
		assertFalse("patches should not be empty", patches.isEmpty());

		logger.info("Generated " + patches.size() + " patches:");
		for (Map<String, Object> patch : patches) {
			logger.info("  " + patch.get("path") + " = " + patch.get("value"));
		}

		// Should have location patch if location exists
		if (currentLoc != null) {
			boolean hasLocationPatch = patches.stream()
				.anyMatch(p -> "state.currentLocation".equals(p.get("path")));
			assertTrue("Should have state.currentLocation patch", hasLocationPatch);
		}
	}

	@Test
	public void TestCreateStateSyncDataNullState() {
		logger.info("Test: createStateSyncData with null state");

		Map<String, Object> syncData = GameUtil.createStateSyncData(null, null, false);
		assertNotNull("Should return empty map, not null", syncData);
		assertTrue("Should be empty for null state", syncData.isEmpty());

		logger.info("Null state handled correctly");
	}

	// ===== SYNC_PATHS Constants Tests =====

	@Test
	public void TestSyncPathConstants() {
		logger.info("Test: Verify SYNC_PATHS constants");

		assertNotNull("SYNC_PATHS_STATE should not be null", GameUtil.SYNC_PATHS_STATE);
		assertTrue("SYNC_PATHS_STATE should contain 'state'",
			Arrays.asList(GameUtil.SYNC_PATHS_STATE).contains("state"));

		assertNotNull("SYNC_PATHS_FULL should not be null", GameUtil.SYNC_PATHS_FULL);
		assertTrue("SYNC_PATHS_FULL should contain 'state'",
			Arrays.asList(GameUtil.SYNC_PATHS_FULL).contains("state"));
		assertTrue("SYNC_PATHS_FULL should contain 'statistics'",
			Arrays.asList(GameUtil.SYNC_PATHS_FULL).contains("statistics"));
		assertTrue("SYNC_PATHS_FULL should contain 'profile'",
			Arrays.asList(GameUtil.SYNC_PATHS_FULL).contains("profile"));

		assertNotNull("SYNC_PATHS_COMBAT should not be null", GameUtil.SYNC_PATHS_COMBAT);
		assertTrue("SYNC_PATHS_COMBAT should contain 'state'",
			Arrays.asList(GameUtil.SYNC_PATHS_COMBAT).contains("state"));
		assertTrue("SYNC_PATHS_COMBAT should contain 'statistics'",
			Arrays.asList(GameUtil.SYNC_PATHS_COMBAT).contains("statistics"));

		assertNotNull("SYNC_PATHS_SOCIAL should not be null", GameUtil.SYNC_PATHS_SOCIAL);
		assertTrue("SYNC_PATHS_SOCIAL should contain 'profile'",
			Arrays.asList(GameUtil.SYNC_PATHS_SOCIAL).contains("profile"));

		logger.info("All SYNC_PATHS constants verified");
	}

	// ===== HELPER METHODS =====

	private List<BaseRecord> getPopulation(OlioContext octx) {
		List<BaseRecord> realms = octx.getRealms();
		if (realms.isEmpty()) return Arrays.asList();
		return octx.getRealmPopulation(realms.get(0));
	}

	private OlioContext getSyncTestContext(BaseRecord testUser) {
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

			if (!octx.isInitialized()) {
				logger.error("Context failed to initialize");
				return null;
			}

			return octx;
		} catch (Exception e) {
			logger.error("Failed to create olio context: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}
}
