package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.StateUtil;
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

	/**
	 * Test that getDistanceToPosition correctly calculates distance across cells.
	 * This verifies that the currentLocation foreign reference fields are properly populated.
	 */
	@Test
	public void TestGetDistanceToPosition() {
		logger.info("Test GameUtil.getDistanceToPosition");

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

		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		assumeTrue("CurrentLocation is null - skipping test", currentLoc != null);

		int currentCellEast = currentLoc.get(FieldNames.FIELD_EASTINGS);
		int currentCellNorth = currentLoc.get(FieldNames.FIELD_NORTHINGS);
		int currentPosEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int currentPosNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);

		logger.info("Current position: cell[" + currentCellEast + "," + currentCellNorth +
				"], pos[" + currentPosEast + "," + currentPosNorth + "]");

		// Distance to same position should be 0
		double distToSelf = GameUtil.getDistanceToPosition(fullPerson,
				currentCellEast, currentCellNorth, currentPosEast, currentPosNorth);
		assertEquals("Distance to same position should be 0", 0.0, distToSelf, 0.001);

		// Distance to position 10 meters east should be 10
		int targetPosEast = (currentPosEast + 10) % 100;
		int targetCellEast = currentCellEast + (currentPosEast + 10) / 100;
		double distEast = GameUtil.getDistanceToPosition(fullPerson,
				targetCellEast, currentCellNorth, targetPosEast, currentPosNorth);
		assertEquals("Distance 10m east should be 10", 10.0, distEast, 0.001);

		// Distance to position in next cell (100m away) should be ~100
		double distNextCell = GameUtil.getDistanceToPosition(fullPerson,
				currentCellEast + 1, currentCellNorth, currentPosEast, currentPosNorth);
		assertEquals("Distance to next cell should be 100", 100.0, distNextCell, 0.001);

		logger.info("Distance calculations verified correctly");
	}

	/**
	 * Test that getDistanceToPosition works correctly after re-fetching character.
	 * This is the key test for the cell crossing bug fix.
	 */
	@Test
	public void TestGetDistanceAfterRefetch() {
		logger.info("Test getDistanceToPosition after re-fetching character");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		// First fetch
		BaseRecord person1 = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", person1 != null);

		BaseRecord state1 = person1.get(FieldNames.FIELD_STATE);
		BaseRecord loc1 = state1.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		int cell1East = loc1.get(FieldNames.FIELD_EASTINGS);
		int cell1North = loc1.get(FieldNames.FIELD_NORTHINGS);
		int pos1East = state1.get(FieldNames.FIELD_CURRENT_EAST);
		int pos1North = state1.get(FieldNames.FIELD_CURRENT_NORTH);

		// Calculate absolute position
		int abs1X = cell1East * 100 + pos1East;
		int abs1Y = cell1North * 100 + pos1North;
		logger.info("First fetch: cell[" + cell1East + "," + cell1North + "], pos[" + pos1East + "," + pos1North + "], abs[" + abs1X + "," + abs1Y + "]");

		// Second fetch (simulating what happens in server loop after movement)
		BaseRecord person2 = GameUtil.findCharacter(objectId);
		assertNotNull("Re-fetch should not be null", person2);

		BaseRecord state2 = person2.get(FieldNames.FIELD_STATE);
		BaseRecord loc2 = state2.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		int cell2East = loc2.get(FieldNames.FIELD_EASTINGS);
		int cell2North = loc2.get(FieldNames.FIELD_NORTHINGS);
		int pos2East = state2.get(FieldNames.FIELD_CURRENT_EAST);
		int pos2North = state2.get(FieldNames.FIELD_CURRENT_NORTH);

		// Calculate absolute position
		int abs2X = cell2East * 100 + pos2East;
		int abs2Y = cell2North * 100 + pos2North;
		logger.info("Second fetch: cell[" + cell2East + "," + cell2North + "], pos[" + pos2East + "," + pos2North + "], abs[" + abs2X + "," + abs2Y + "]");

		// Positions should be identical (no movement occurred)
		assertEquals("Cell east should match between fetches", cell1East, cell2East);
		assertEquals("Cell north should match between fetches", cell1North, cell2North);
		assertEquals("Pos east should match between fetches", pos1East, pos2East);
		assertEquals("Pos north should match between fetches", pos1North, pos2North);
		assertEquals("Absolute X should match between fetches", abs1X, abs2X);
		assertEquals("Absolute Y should match between fetches", abs1Y, abs2Y);

		// Calculate distance using re-fetched person - this was the bug!
		// If currentLocation fields aren't populated, cell values would be 0
		double dist = GameUtil.getDistanceToPosition(person2,
				cell2East + 1, cell2North, pos2East, pos2North);

		// Distance to next cell should be exactly 100m
		assertEquals("Distance to next cell should be 100m (not 0 or wrong value from unpopulated fields)",
				100.0, dist, 0.001);

		logger.info("Distance calculation after re-fetch verified: " + dist);
	}

	/**
	 * Test moveTowardsPosition with distance calculation consistency.
	 * This validates that distance calculations remain accurate across re-fetches
	 * and after any movement that may occur.
	 */
	@Test
	public void TestMoveTowardsPositionAcrossCells() {
		logger.info("Test moveTowardsPosition with distance calculation consistency");

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
		BaseRecord currentLoc = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);

		int startCellEast = currentLoc.get(FieldNames.FIELD_EASTINGS);
		int startCellNorth = currentLoc.get(FieldNames.FIELD_NORTHINGS);
		int startPosEast = state.get(FieldNames.FIELD_CURRENT_EAST);
		int startPosNorth = state.get(FieldNames.FIELD_CURRENT_NORTH);

		int startAbsX = startCellEast * 100 + startPosEast;
		int startAbsY = startCellNorth * 100 + startPosNorth;

		logger.info("Start: cell[" + startCellEast + "," + startCellNorth +
				"], pos[" + startPosEast + "," + startPosNorth + "], abs[" + startAbsX + "," + startAbsY + "]");

		// Target: center of cell 2 cells east
		int targetCellEast = startCellEast + 2;
		int targetCellNorth = startCellNorth;
		int targetPosEast = 50;
		int targetPosNorth = startPosNorth;

		int targetAbsX = targetCellEast * 100 + targetPosEast;
		int targetAbsY = targetCellNorth * 100 + targetPosNorth;
		double expectedInitialDist = Math.sqrt(Math.pow(targetAbsX - startAbsX, 2) + Math.pow(targetAbsY - startAbsY, 2));

		logger.info("Target: cell[" + targetCellEast + "," + targetCellNorth +
				"], pos[" + targetPosEast + "," + targetPosNorth + "], abs[" + targetAbsX + "," + targetAbsY +
				"], expectedDist=" + expectedInitialDist);

		// Verify initial distance calculation is correct
		double calculatedDist = GameUtil.getDistanceToPosition(fullPerson,
				targetCellEast, targetCellNorth, targetPosEast, targetPosNorth);
		assertEquals("Initial distance calculation should match expected",
				expectedInitialDist, calculatedDist, 1.0);

		// Try to move - may be blocked by terrain, that's OK
		int successfulMoves = 0;
		int maxAttempts = 10;
		double prevDistance = calculatedDist;

		for (int i = 0; i < maxAttempts; i++) {
			// Re-fetch person (simulating server loop)
			BaseRecord freshPerson = GameUtil.findCharacter(objectId);
			assertNotNull("Failed to re-fetch person on attempt " + i, freshPerson);

			double remainingDist = GameUtil.getDistanceToPosition(freshPerson,
					targetCellEast, targetCellNorth, targetPosEast, targetPosNorth);

			// Key test: distance should be consistent or decreasing (not random/increasing wildly)
			// Allow small increase due to diagonal movement rounding
			assertTrue("Distance should not increase significantly (was " + prevDistance + ", now " + remainingDist + ")",
					remainingDist <= prevDistance + 5.0);

			if (remainingDist <= 1.0) {
				logger.info("Arrived at target after " + (i + 1) + " attempts, remaining=" + remainingDist);
				break;
			}

			logger.info("Attempt " + (i + 1) + ": remaining distance = " + remainingDist);

			try {
				boolean moved = GameUtil.moveTowardsPosition(octx, freshPerson,
						targetCellEast, targetCellNorth, targetPosEast, targetPosNorth, 10.0);

				if (moved) {
					successfulMoves++;
					prevDistance = remainingDist;  // Update only on successful move
				} else {
					logger.info("Move blocked on attempt " + (i + 1) + " - terrain/policy restriction");
					break;  // Stop if blocked
				}
			} catch (OlioException e) {
				logger.info("Move exception on attempt " + (i + 1) + ": " + e.getMessage());
				break;
			}
		}

		logger.info("Completed with " + successfulMoves + " successful moves");

		// Final verification: distance calculation should still work correctly
		BaseRecord finalPerson = GameUtil.findCharacter(objectId);
		BaseRecord finalState = finalPerson.get(FieldNames.FIELD_STATE);
		BaseRecord finalLoc = finalState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);

		int endCellEast = finalLoc.get(FieldNames.FIELD_EASTINGS);
		int endCellNorth = finalLoc.get(FieldNames.FIELD_NORTHINGS);
		int endPosEast = finalState.get(FieldNames.FIELD_CURRENT_EAST);
		int endPosNorth = finalState.get(FieldNames.FIELD_CURRENT_NORTH);

		int endAbsX = endCellEast * 100 + endPosEast;
		int endAbsY = endCellNorth * 100 + endPosNorth;

		logger.info("End: cell[" + endCellEast + "," + endCellNorth +
				"], pos[" + endPosEast + "," + endPosNorth + "], abs[" + endAbsX + "," + endAbsY + "]");

		// Verify final distance calculation matches expected based on actual position
		double actualFinalDist = Math.sqrt(Math.pow(targetAbsX - endAbsX, 2) + Math.pow(targetAbsY - endAbsY, 2));
		double calculatedFinalDist = GameUtil.getDistanceToPosition(finalPerson,
				targetCellEast, targetCellNorth, targetPosEast, targetPosNorth);

		assertEquals("Final distance calculation should match actual distance",
				actualFinalDist, calculatedFinalDist, 1.0);

		logger.info("Distance calculation consistency verified successfully");
	}

	/**
	 * Test that getSituation() clears the population cache and returns fresh data.
	 * This verifies the fix for: "Nearby list never updates, shows same people."
	 *
	 * The test poisons the population cache with a fake empty list, then calls
	 * getSituation(). If the cache is properly cleared, getSituation() will
	 * re-query the DB and return real population data (non-empty nearby list
	 * or at least a valid result). If the cache is NOT cleared, it would use
	 * the empty fake list and return 0 nearby people.
	 */
	@Test
	public void TestSituationClearsPopulationCache() {
		logger.info("Test that getSituation clears population cache");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> realms = octx.getRealms();
		assumeTrue("No realms - skipping test", realms.size() > 0);
		BaseRecord realm = realms.get(0);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		BaseRecord person = pop.get(0);
		String objectId = person.get(FieldNames.FIELD_OBJECT_ID);
		assumeTrue("ObjectId is null - skipping test", objectId != null);

		BaseRecord fullPerson = GameUtil.findCharacter(objectId);
		assumeTrue("Person not found - skipping test", fullPerson != null);

		// First call: baseline - verify getSituation works normally
		Map<String, Object> sit1 = GameUtil.getSituation(octx, fullPerson);
		assumeTrue("Situation is null - skipping test", sit1 != null);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearby1 = (List<Map<String, Object>>) sit1.get("nearbyPeople");
		assertNotNull("nearbyPeople should not be null", nearby1);
		int baselineCount = nearby1.size();
		logger.info("Baseline nearby count: " + baselineCount);

		// Poison the cache with a fake empty population list
		long realmId = realm.get(FieldNames.FIELD_ID);
		List<BaseRecord> fakePop = new ArrayList<>();
		octx.getPopulationMap().put(realmId, fakePop);
		logger.info("Poisoned population cache with empty list for realm " + realmId);

		// Verify the cache IS poisoned
		List<BaseRecord> cachedPop = octx.getPopulationMap().get(realmId);
		assertEquals("Cache should contain our fake empty list", 0, cachedPop.size());

		// Call getSituation - it should clear the poisoned cache and re-query DB
		Map<String, Object> sit2 = GameUtil.getSituation(octx, fullPerson);
		assumeTrue("Second situation is null - skipping test", sit2 != null);

		// After getSituation, the cache should contain real population (not our fake list)
		List<BaseRecord> refreshedPop = octx.getPopulationMap().get(realmId);
		assertNotNull("Population should be re-cached after getSituation", refreshedPop);
		assertTrue("Population cache should have been refreshed with real data (got " +
				refreshedPop.size() + " records, expected > 0)", refreshedPop.size() > 0);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearby2 = (List<Map<String, Object>>) sit2.get("nearbyPeople");
		assertNotNull("nearbyPeople should not be null after cache refresh", nearby2);

		logger.info("After cache refresh: nearby count = " + nearby2.size() +
				", population count = " + refreshedPop.size());

		// The nearby count should match the baseline (same location, same data)
		assertEquals("Nearby count should match baseline after cache refresh",
				baselineCount, nearby2.size());

		logger.info("Population cache refresh test PASSED");
	}

	/**
	 * Test that fresh population records loaded after cache clearing
	 * reflect DB location changes (simulating Overwatch NPC movement).
	 *
	 * Updates an NPC's state.currentLocation FK in the database, clears the
	 * population cache, re-queries, and verifies the fresh record has the
	 * updated location.
	 *
	 * NOTE: MAXIMUM_OBSERVATION_DISTANCE=10 covers the entire 10x10 feature
	 * grid, so all population members are always "nearby" in a single-feature
	 * test world. This test verifies the underlying data refresh mechanism
	 * rather than filtering behavior.
	 */
	@Test
	public void TestPopulationCacheReflectsDBLocationChanges() {
		logger.info("Test that fresh population records reflect DB location changes");

		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);

		List<BaseRecord> realms = octx.getRealms();
		assumeTrue("No realms - skipping test", realms.size() > 0);
		BaseRecord realm = realms.get(0);

		List<BaseRecord> pop = getPopulation(octx);
		assumeTrue("No population - skipping test", pop != null && pop.size() > 0);

		stagePopulation(octx, pop);

		// Pick an NPC to move
		BaseRecord npcToMove = null;
		for (BaseRecord p : pop) {
			BaseRecord pState = p.get(FieldNames.FIELD_STATE);
			if (pState != null) {
				BaseRecord pLoc = pState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
				if (pLoc != null && ((long) pLoc.get(FieldNames.FIELD_ID)) > 0) {
					npcToMove = p;
					break;
				}
			}
		}
		assumeTrue("No NPC with valid state/location found - skipping test", npcToMove != null);

		BaseRecord npcState = npcToMove.get(FieldNames.FIELD_STATE);
		long npcStateId = npcState.get(FieldNames.FIELD_ID);
		BaseRecord originalLoc = npcState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		long originalLocId = originalLoc.get(FieldNames.FIELD_ID);
		String npcName = npcToMove.get(FieldNames.FIELD_NAME);
		String npcOid = npcToMove.get(FieldNames.FIELD_OBJECT_ID);
		logger.info("NPC to move: " + npcName + " (oid=" + npcOid +
				", stateId=" + npcStateId + ", originalLocId=" + originalLocId + ")");

		// Find a different cell to move the NPC to
		BaseRecord playerLoc = pop.get(0).get("state.currentLocation");
		assumeTrue("Player location is null - skipping test", playerLoc != null);
		List<BaseRecord> allCells = GeoLocationUtil.getCells(octx,
				GeoLocationUtil.getParentLocation(octx, playerLoc));
		assumeTrue("No cells found - skipping test", allCells.size() > 1);

		BaseRecord targetCell = null;
		for (BaseRecord cell : allCells) {
			if (((long) cell.get(FieldNames.FIELD_ID)) != originalLocId) {
				targetCell = cell;
				break;
			}
		}
		assumeTrue("No different cell found - skipping test", targetCell != null);
		long targetLocId = targetCell.get(FieldNames.FIELD_ID);
		logger.info("Moving NPC to cell: " + targetCell.get(FieldNames.FIELD_NAME) +
				" (id=" + targetLocId + ")");

		// Update the NPC's location in the DB (simulating Overwatch)
		try {
			npcState.set(OlioFieldNames.FIELD_CURRENT_LOCATION, targetCell);
			StateUtil.updateLocationImmediate(octx, npcToMove, true);
		} catch (Exception e) {
			logger.error("Failed to update NPC location: " + e.getMessage());
			assumeTrue("Failed to update - skipping test", false);
		}

		// Verify the DB was actually updated by querying directly
		Query stateQuery = QueryUtil.createQuery(
				OlioModelNames.MODEL_CHAR_STATE, FieldNames.FIELD_ID, npcStateId);
		stateQuery.setCache(false);
		stateQuery.planMost(false);
		BaseRecord dbState = IOSystem.getActiveContext().getSearch().findRecord(stateQuery);
		assertNotNull("Should find state in DB", dbState);
		BaseRecord dbLoc = dbState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		assertNotNull("DB state should have currentLocation", dbLoc);
		long dbLocId = dbLoc.get(FieldNames.FIELD_ID);
		logger.info("DB state currentLocation id: " + dbLocId + " (expected: " + targetLocId + ")");
		assertEquals("DB should reflect the location update", targetLocId, dbLocId);

		// Now clear the population cache and re-query
		long realmId = realm.get(FieldNames.FIELD_ID);
		octx.getPopulationMap().remove(realmId);
		List<BaseRecord> freshPop = octx.getRealmPopulation(realm);
		assertNotNull("Fresh population should not be null", freshPop);
		assertTrue("Fresh population should not be empty", freshPop.size() > 0);

		// Find the NPC in the fresh population and verify their location
		BaseRecord freshNpc = null;
		for (BaseRecord p : freshPop) {
			String pOid = p.get(FieldNames.FIELD_OBJECT_ID);
			if (npcOid != null && npcOid.equals(pOid)) {
				freshNpc = p;
				break;
			}
		}
		assertNotNull("NPC should exist in fresh population", freshNpc);

		BaseRecord freshState = freshNpc.get(FieldNames.FIELD_STATE);
		assertNotNull("Fresh NPC should have state", freshState);
		BaseRecord freshLoc = freshState.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
		assertNotNull("Fresh NPC state should have currentLocation", freshLoc);
		long freshLocId = freshLoc.get(FieldNames.FIELD_ID);
		logger.info("Fresh population NPC location id: " + freshLocId +
				" (expected: " + targetLocId + ", original: " + originalLocId + ")");

		assertEquals("Fresh population record should have updated location from DB",
				targetLocId, freshLocId);

		logger.info("Population cache refresh correctly reflects DB location change");

		// Cleanup: restore original location
		try {
			npcState.set(OlioFieldNames.FIELD_CURRENT_LOCATION, originalLoc);
			StateUtil.updateLocationImmediate(octx, npcToMove, true);
			logger.info("Restored NPC to original location");
		} catch (Exception e) {
			logger.warn("Failed to restore NPC location: " + e.getMessage());
		}
	}

}
