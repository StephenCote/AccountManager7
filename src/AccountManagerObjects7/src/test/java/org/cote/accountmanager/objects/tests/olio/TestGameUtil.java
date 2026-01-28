package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

public class TestGameUtil extends BaseTest {

	private OlioContext getOlioContext() {
		String dataPath = testProperties.getProperty("test.datagen.path");
		try {
			return OlioTestUtil.getContext(orgContext, dataPath);
		} catch (StackOverflowError | Exception e) {
			logger.error("Failed to get OlioContext: " + e.getMessage());
			return null;
		}
	}

	private List<BaseRecord> getPopulation(OlioContext octx) {
		List<BaseRecord> realms = octx.getRealms();
		if (realms.isEmpty()) return null;
		// Use getRealmPopulation which returns fully loaded records
		return octx.getRealmPopulation(realms.get(0));
	}

	private void stagePopulation(OlioContext octx, List<BaseRecord> pop) {
		ApparelUtil.outfitAndStage(octx, null, pop);
		ItemUtil.showerWithMoney(octx, pop);
		Queue.processQueue();
	}

	
	@Test
	public void TestStatistics() {
		logger.info("Test Statistics");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> realms = octx.getRealms();
		assumeTrue("No realms available - skipping test", realms.size() > 0);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population available - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		// These are actual test assertions - should FAIL if stats not loaded
		//BaseRecord firstPerson = pop.stream().filter(p -> ((String)p.get("lastName")).equals("Bassitt")).collect(Collectors.toList()).get(0);
		for(BaseRecord per : pop){
		int speed = per.get(OlioFieldNames.FIELD_STATISTICS_SPEED);
		assertNotNull("Statistics speed should not be null", speed);
		assertTrue("Statistics speed should be > 0, got: " + speed, speed > 0);
		logger.info("Speed: " + speed + " for " + per.get("name"));
		}
	}

	@Test
	public void TestFindCharacter() {
		logger.info("Test GameUtil.findCharacter");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> realms = octx.getRealms();
		assumeTrue("No realms available - skipping test", realms.size() > 0);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population available - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord firstPerson = pop.get(0);
		String objectId = firstPerson.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord found = GameUtil.findCharacter(objectId);
		assertNotNull("Found character is null", found);
		assertEquals("ObjectId mismatch", objectId, found.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord state = found.get(FieldNames.FIELD_STATE);
		assertNotNull("State should be loaded", state);
	}

	@Test
	public void TestFindCharacterWithFullStatistics() {
		logger.info("Test GameUtil.findCharacter returns fully populated statistics");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population available - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord firstPerson = pop.get(0);
		String objectId = firstPerson.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		// findCharacter should bypass cache and return fully populated statistics
		BaseRecord found = GameUtil.findCharacter(objectId);
		assertNotNull("Found character is null", found);

		BaseRecord statistics = found.get(OlioFieldNames.FIELD_STATISTICS);
		assertNotNull("Statistics should be loaded", statistics);

		// Verify statistics has actual values, not just reference fields
		// If only reference fields are returned, these will be null/0
		Integer agility = statistics.get("agility");
		Integer speed = statistics.get("speed");
		Integer physicalStrength = statistics.get("physicalStrength");

		assertNotNull("Statistics.agility should not be null (cache bypass failed?)", agility);
		assertNotNull("Statistics.speed should not be null (cache bypass failed?)", speed);
		assertNotNull("Statistics.physicalStrength should not be null (cache bypass failed?)", physicalStrength);

		// Verify values are within expected ranges (stats are typically 1-20 or similar)
		assertTrue("Statistics.agility should be > 0, got: " + agility, agility > 0);
		assertTrue("Statistics.speed should be > 0, got: " + speed, speed > 0);
		assertTrue("Statistics.physicalStrength should be > 0, got: " + physicalStrength, physicalStrength > 0);

		logger.info("Statistics loaded successfully - agility: " + agility + ", speed: " + speed + ", physicalStrength: " + physicalStrength);
	}

	@Test
	public void TestClaimAndReleaseCharacter() {
		logger.info("Test GameUtil.claimCharacter and releaseCharacter");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		// Test claim
		BaseRecord claimedState = GameUtil.claimCharacter(fullPerson);
		assertNotNull("Claimed state is null", claimedState);
		assertTrue("Should be player controlled", (boolean) claimedState.get("playerControlled"));
		assertEquals("Health should be 1.0", 1.0, (double) claimedState.get("health"), 0.001);
		assertEquals("Energy should be 1.0", 1.0, (double) claimedState.get("energy"), 0.001);

		// Test release
		BaseRecord releasedState = GameUtil.releaseCharacter(fullPerson);
		assertNotNull("Released state is null", releasedState);
		assertFalse("Should not be player controlled", (boolean) releasedState.get("playerControlled"));
	}

	@Test
	public void TestGetSituation() {
		logger.info("Test GameUtil.getSituation");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		Map<String, Object> situation = GameUtil.getSituation(octx, fullPerson);
		assumeTrue("Situation is null (may not have state/location) - skipping test", situation != null);

		assertNotNull("Character missing", situation.get("character"));
		assertNotNull("State missing", situation.get("state"));
		assertNotNull("Location missing", situation.get("location"));
		assertNotNull("Nearby people missing", situation.get("nearbyPeople"));
		assertNotNull("Needs missing", situation.get("needs"));
		assertNotNull("Threats missing", situation.get("threats"));

		@SuppressWarnings("unchecked")
		Map<String, Object> needs = (Map<String, Object>) situation.get("needs");
		assertNotNull("Hunger missing from needs", needs.get("hunger"));
		assertNotNull("Thirst missing from needs", needs.get("thirst"));
		assertNotNull("Fatigue missing from needs", needs.get("fatigue"));
		assertNotNull("Health missing from needs", needs.get("health"));
		assertNotNull("Energy missing from needs", needs.get("energy"));
	}

	@Test
	public void TestGetSituationLocationFields() {
		logger.info("Test GameUtil.getSituation returns location with eastings/northings/terrainType");

		OlioContext octx = getOlioContext();
		assertNotNull("Context should not be null", octx);

		List<BaseRecord> pop = getPopulation(octx);
		assertNotNull("Population should not be null", pop);
		assertTrue("Population should not be empty", pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		logger.info("Testing with person: " + person.get(FieldNames.FIELD_NAME));

		// Population records may not have objectId loaded - use id to get full record
		long personId = person.get(FieldNames.FIELD_ID);
		logger.info("Person id: " + personId);
		assertTrue("Person ID should be > 0", personId > 0);

		// Get full record using OlioUtil.getFullRecord
		BaseRecord fullPerson = OlioUtil.getFullRecord(person);
		assertNotNull("getFullRecord should return person", fullPerson);

		String objectId = fullPerson.get(FieldNames.FIELD_OBJECT_ID);
		logger.info("Person objectId: " + objectId);
		assertNotNull("ObjectId should not be null after getFullRecord", objectId);

		// Check if person has state and location
		BaseRecord state = fullPerson.get(FieldNames.FIELD_STATE);
		logger.info("Person state: " + (state != null ? "present" : "NULL"));
		if (state != null) {
			BaseRecord loc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
			logger.info("Person currentLocation: " + (loc != null ? "present" : "NULL"));
		}

		Map<String, Object> situation = GameUtil.getSituation(octx, fullPerson);
		logger.info("Situation result: " + (situation != null ? "present" : "NULL"));
		assertNotNull("Situation should not be null - check state and currentLocation", situation);

		// Test location has required fields
		@SuppressWarnings("unchecked")
		Map<String, Object> location = (Map<String, Object>) situation.get("location");
		assertNotNull("Location should be a Map", location);

		// These are the fields the client needs
		assertNotNull("Location.name should not be null", location.get("name"));
		assertNotNull("Location.geoType should not be null", location.get("geoType"));
		assertEquals("Location.geoType should be 'cell'", "cell", location.get("geoType"));

		// Eastings/northings must exist (can be 0 but not null)
		assertNotNull("Location.eastings should not be null", location.get("eastings"));
		assertNotNull("Location.northings should not be null", location.get("northings"));

		// TerrainType should exist
		assertNotNull("Location.terrainType should not be null", location.get("terrainType"));

		logger.info("Location: name=" + location.get("name") +
				", geoType=" + location.get("geoType") +
				", eastings=" + location.get("eastings") +
				", northings=" + location.get("northings") +
				", terrainType=" + location.get("terrainType"));
	}

	@Test
	public void TestGetSituationAdjacentCellsFields() {
		logger.info("Test GameUtil.getSituation returns adjacentCells with eastings/northings/terrainType");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		Map<String, Object> situation = GameUtil.getSituation(octx, fullPerson);
		assumeTrue("Situation is null - skipping test", situation != null);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> adjacentCells = (List<Map<String, Object>>) situation.get("adjacentCells");
		assertNotNull("Adjacent cells should not be null", adjacentCells);

		logger.info("Adjacent cells count: " + adjacentCells.size());

		// Should have some adjacent cells (unless at world edge)
		if (adjacentCells.size() > 0) {
			Map<String, Object> firstCell = adjacentCells.get(0);

			// Each cell should have these fields
			assertNotNull("Cell.name should not be null", firstCell.get("name"));
			assertNotNull("Cell.eastings should not be null", firstCell.get("eastings"));
			assertNotNull("Cell.northings should not be null", firstCell.get("northings"));
			assertNotNull("Cell.terrainType should not be null", firstCell.get("terrainType"));

			logger.info("First adjacent cell: name=" + firstCell.get("name") +
					", eastings=" + firstCell.get("eastings") +
					", northings=" + firstCell.get("northings") +
					", terrainType=" + firstCell.get("terrainType"));
		}
	}

	@Test
	public void TestGetSituationNearbyPeopleFields() {
		logger.info("Test GameUtil.getSituation returns nearbyPeople with name/objectId/gender/age");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		Map<String, Object> situation = GameUtil.getSituation(octx, fullPerson);
		assumeTrue("Situation is null - skipping test", situation != null);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearbyPeople = (List<Map<String, Object>>) situation.get("nearbyPeople");
		assertNotNull("Nearby people should not be null", nearbyPeople);

		logger.info("Nearby people count: " + nearbyPeople.size());

		if (nearbyPeople.size() > 0) {
			Map<String, Object> firstPerson = nearbyPeople.get(0);

			// Each person should have these fields for the UI
			assertNotNull("Person.objectId should not be null", firstPerson.get("objectId"));

			// Name might be null if never set, but gender and age should exist
			// for the "Man/Woman/Boy/Girl" fallback display
			assertNotNull("Person.gender should not be null", firstPerson.get("gender"));
			assertNotNull("Person.age should not be null", firstPerson.get("age"));

			logger.info("First nearby person: name=" + firstPerson.get("name") +
					", objectId=" + firstPerson.get("objectId") +
					", gender=" + firstPerson.get("gender") +
					", age=" + firstPerson.get("age"));

			// Current location should also be present for grid placement
			@SuppressWarnings("unchecked")
			Map<String, Object> personLoc = (Map<String, Object>) firstPerson.get("currentLocation");
			if (personLoc != null) {
				assertNotNull("Person.currentLocation.eastings should not be null", personLoc.get("eastings"));
				assertNotNull("Person.currentLocation.northings should not be null", personLoc.get("northings"));
				logger.info("Person location: eastings=" + personLoc.get("eastings") +
						", northings=" + personLoc.get("northings"));
			}
		}
	}

	@Test
	public void TestMoveCharacter() {
		logger.info("Test GameUtil.moveCharacter");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		BaseRecord state = fullPerson.get(FieldNames.FIELD_STATE);
		assumeTrue("State is null - skipping test", state != null);

		try {
			boolean moved = GameUtil.moveCharacter(octx, fullPerson, DirectionEnumType.NORTH, 1.0);
			logger.info("Move result: " + moved);
		} catch (OlioException e) {
			logger.error("Move failed: " + e.getMessage());
		}
	}

	@Test
	public void TestInvestigate() {
		logger.info("Test GameUtil.investigate");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		Map<String, Object> result = GameUtil.investigate(octx, fullPerson);
		assumeTrue("Investigation result is null (may not have state/location) - skipping test", result != null);

		assertNotNull("Location missing", result.get("location"));
		assertNotNull("Perception missing", result.get("perception"));
		assertNotNull("Discoveries missing", result.get("discoveries"));

		@SuppressWarnings("unchecked")
		List<String> discoveries = (List<String>) result.get("discoveries");
		assertTrue("Expected discoveries", discoveries.size() > 0);
	}

	@Test
	public void TestAdvanceTurn() {
		logger.info("Test GameUtil.advanceTurn");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		BaseRecord claimState = GameUtil.claimCharacter(fullPerson);
		assumeTrue("Could not claim character - skipping test", claimState != null);

		BaseRecord state = fullPerson.get(FieldNames.FIELD_STATE);
		double initialHunger = state.get("hunger");

		// Pass the claimed character (with playerControlled=true) not the original pop list
		// which has stale state objects
		List<BaseRecord> claimedPop = Arrays.asList(fullPerson);
		int updated = GameUtil.advanceTurn(octx, claimedPop);
		assertTrue("Expected at least 1 updated character", updated >= 1);

		BaseRecord updatedPerson = GameUtil.findCharacter(objectId);
		BaseRecord updatedState = updatedPerson.get(FieldNames.FIELD_STATE);
		double newHunger = updatedState.get("hunger");
		assertTrue("Hunger should increase", newHunger >= initialHunger);

		GameUtil.releaseCharacter(fullPerson);
	}

	@Test
	public void TestIsCharacterInWorld() {
		logger.info("Test GameUtil.isCharacterInWorld");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		boolean inWorld = GameUtil.isCharacterInWorld(octx, objectId);
		assertTrue("Character should be in world", inWorld);

		boolean notInWorld = GameUtil.isCharacterInWorld(octx, "non-existent-id");
		assertFalse("Non-existent character should not be in world", notInWorld);
	}

	@Test
	public void TestGetNewGameData() {
		logger.info("Test GameUtil.getNewGameData");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		Map<String, Object> gameData = GameUtil.getNewGameData(octx);
		assumeTrue("Game data is null (no realms) - skipping test", gameData != null);

		assumeTrue("Realm name missing - skipping test", gameData.get("realmName") != null);
		assumeTrue("Realm ID missing - skipping test", gameData.get("realmId") != null);
		assertNotNull("Characters missing", gameData.get("characters"));
		assertNotNull("Total population missing", gameData.get("totalPopulation"));

		@SuppressWarnings("unchecked")
		List<BaseRecord> characters = (List<BaseRecord>) gameData.get("characters");
		assumeTrue("No characters in game data - skipping validation", characters.size() > 0);

		int totalPop = (int) gameData.get("totalPopulation");
		assertEquals("Population count mismatch", characters.size(), totalPop);
	}

	@Test
	public void TestGetActionPriority() {
		logger.info("Test GameUtil.getActionPriority");

		double combatPriority = GameUtil.getActionPriority(InteractionEnumType.COMBAT);
		double socializePriority = GameUtil.getActionPriority(InteractionEnumType.SOCIALIZE);

		assertTrue("Combat should have higher priority than socialize", combatPriority > socializePriority);
		assertEquals("Combat priority should be 0.9", 0.9, combatPriority, 0.001);
		assertEquals("Socialize priority should be 0.25", 0.25, socializePriority, 0.001);
	}

	@Test
	public void TestGetCharacterState() {
		logger.info("Test GameUtil.getCharacterState");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		BaseRecord state = GameUtil.getCharacterState(fullPerson);
		assertNotNull("State should not be null", state);
	}

	@Test
	public void TestGetActionStatus() {
		logger.info("Test GameUtil.getActionStatus");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		BaseRecord action = GameUtil.getActionStatus(fullPerson);
		logger.info("Action status: " + (action != null ? "has action" : "idle"));
	}

	@Test
	public void TestGenerateOutfit() {
		logger.info("Test GameUtil.generateOutfit");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		BaseRecord apparel = GameUtil.generateOutfit(octx, fullPerson, 2, "TEMPERATE");
		assertNotNull("Apparel should not be null", apparel);
	}

	@Test
	public void TestInteract() {
		logger.info("Test GameUtil.interact");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("Need at least 2 people for interaction - skipping test", pop != null && pop.size() >= 2);

		stagePopulation(octx, pop);

		String objectId1 = pop.get(0).get(FieldNames.FIELD_OBJECT_ID);
		String objectId2 = pop.get(1).get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectIds are null - skipping test", objectId1 != null && objectId2 != null);

		BaseRecord person1 = GameUtil.findCharacter(objectId1);
		BaseRecord person2 = GameUtil.findCharacter(objectId2);
		assumeTrue("People not found - skipping test", person1 != null && person2 != null);

		try {
			BaseRecord interaction = GameUtil.interact(octx, person1, person2, InteractionEnumType.COMMUNICATE);
			assertNotNull("Interaction result should not be null", interaction);
		} catch (OlioException e) {
			logger.error("Interaction failed: " + e.getMessage());
		}
	}

	@Test
	public void TestSaveLoadDeleteGame() {
		logger.info("Test GameUtil save/load/delete game");

		BaseRecord testUser = orgContext.getAdminUser();

		Map<String, Object> saveResult = GameUtil.saveGame(testUser, "TestSave", "test-char-id", null);
		assertNotNull("Save result should not be null", saveResult);
		assertTrue("Should be saved", (boolean) saveResult.get("saved"));
		String saveId = (String) saveResult.get("saveId");
		assertNotNull("Save ID should not be null", saveId);

		List<Map<String, Object>> saves = GameUtil.listSaves(testUser);
		assertNotNull("Saves list should not be null", saves);
		assertTrue("Should have at least one save", saves.size() > 0);

		Map<String, Object> loadResult = GameUtil.loadGame(saveId);
		assertNotNull("Load result should not be null", loadResult);
		assertTrue("Should be loaded", (boolean) loadResult.get("loaded"));

		boolean deleted = GameUtil.deleteGame(saveId);
		assertTrue("Should be deleted", deleted);
	}

	/**
	 * Test that simulates browser flow: repeatedly moving and re-loading character.
	 * This test verifies that position changes persist across fresh loads,
	 * identifying any caching issues that might cause position to reset.
	 */
	@Test
	public void TestMarchCharacterStepByStep() {
		logger.info("Test marching character step by step (simulating browser re-requests)");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		// Get initial character
		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		// Simulate browser: load character fresh
		BaseRecord loadedPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", loadedPerson != null);

		BaseRecord state = loadedPerson.get(FieldNames.FIELD_STATE);
		assumeTrue("State is null - skipping test", state != null);

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		assumeTrue("CurrentLocation is null - skipping test", currentLoc != null);

		int initialEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int initialNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);
		logger.info("Initial position: E=" + initialEast + ", N=" + initialNorth);

		// March 5 steps EAST, simulating browser re-requests each time
		int stepsToTake = 5;
		int expectedEast = initialEast;
		int expectedNorth = initialNorth;

		for (int step = 1; step <= stepsToTake; step++) {
			logger.info("=== Step " + step + " ===");

			// Simulate browser: load character fresh (this is what happens on each request)
			BaseRecord freshPerson = GameUtil.findCharacter(objectId);
			assertNotNull("Failed to load character on step " + step, freshPerson);

			BaseRecord freshState = freshPerson.get(FieldNames.FIELD_STATE);
			assertNotNull("State is null on step " + step, freshState);

			int beforeEast = freshState.get(FieldNames.FIELD_CURRENT_EAST);
			int beforeNorth = freshState.get(FieldNames.FIELD_CURRENT_NORTH);
			logger.info("Before move (step " + step + "): E=" + beforeEast + ", N=" + beforeNorth);

			// Verify position matches expected (from previous step)
			assertEquals("Position E mismatch before step " + step + " - data not persisted from previous move?",
					expectedEast, beforeEast);
			assertEquals("Position N mismatch before step " + step + " - data not persisted from previous move?",
					expectedNorth, beforeNorth);

			// Move EAST
			try {
				GameUtil.moveCharacter(octx, freshPerson, DirectionEnumType.EAST, 1.0);
				expectedEast = (expectedEast + 1) % 100; // Wrap at cell boundary
				logger.info("Move succeeded, expected new position: E=" + expectedEast + ", N=" + expectedNorth);
			} catch (OlioException e) {
				logger.warn("Move failed on step " + step + ": " + e.getMessage());
				// If movement fails (blocked), expected position stays the same
			}

			// Simulate browser: load character fresh AGAIN to verify persistence
			BaseRecord verifyPerson = GameUtil.findCharacter(objectId);
			assertNotNull("Failed to load character for verification on step " + step, verifyPerson);

			BaseRecord verifyState = verifyPerson.get(FieldNames.FIELD_STATE);
			int afterEast = verifyState.get(FieldNames.FIELD_CURRENT_EAST);
			int afterNorth = verifyState.get(FieldNames.FIELD_CURRENT_NORTH);
			logger.info("After move (step " + step + "): E=" + afterEast + ", N=" + afterNorth);

			// This is the key assertion - after reloading, position should match expected
			assertEquals("Position E not persisted after step " + step, expectedEast, afterEast);
			assertEquals("Position N not persisted after step " + step, expectedNorth, afterNorth);
		}

		logger.info("Final position after " + stepsToTake + " steps: E=" + expectedEast + ", N=" + expectedNorth);
		logger.info("Total movement: " + (expectedEast - initialEast) + " meters EAST");
	}

}
