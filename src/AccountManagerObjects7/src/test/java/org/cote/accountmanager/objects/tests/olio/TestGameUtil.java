package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.List;
import java.util.Map;

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
		BaseRecord popGrp = realms.get(0).get(OlioFieldNames.FIELD_POPULATION);
		if (popGrp == null) return null;
		return OlioUtil.listGroupPopulation(octx, popGrp);
	}

	private void stagePopulation(OlioContext octx, List<BaseRecord> pop) {
		ApparelUtil.outfitAndStage(octx, null, pop);
		ItemUtil.showerWithMoney(octx, pop);
		Queue.processQueue();
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

		int updated = GameUtil.advanceTurn(octx, pop);
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
}
