package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Test;

public class TestLandscape extends BaseTest {

	/// Test NarrativeUtil.getLandscapePrompt() generates valid prompts for different terrain types
	@Test
	public void TestLandscapePromptGeneration() {
		logger.info("Test Landscape Prompt Generation");

		try {
			// Create a mock location record with terrain
			BaseRecord location = RecordFactory.newInstance(OlioModelNames.MODEL_GEO_LOCATION);
			location.set(FieldNames.FIELD_NAME, "Test Valley");
			location.set(FieldNames.FIELD_TERRAIN_TYPE, TerrainEnumType.FOREST.toString());

			// Test with no adjacent terrains
			String prompt = NarrativeUtil.getLandscapePrompt(location, null);
			assertNotNull("Landscape prompt should not be null", prompt);
			assertTrue("Prompt should contain quality tags", prompt.contains("8k highly detailed"));
			assertTrue("Prompt should mention forest", prompt.toLowerCase().contains("forest"));
			logger.info("Forest prompt: " + prompt.substring(0, Math.min(200, prompt.length())) + "...");

			// Test with adjacent terrains
			Set<String> adjacent = new HashSet<>();
			adjacent.add(TerrainEnumType.RIVER.toString());
			adjacent.add(TerrainEnumType.HILL.toString());

			String promptWithAdjacent = NarrativeUtil.getLandscapePrompt(location, adjacent);
			assertNotNull("Landscape prompt with adjacent should not be null", promptWithAdjacent);
			assertTrue("Prompt should be longer with adjacent terrains",
				promptWithAdjacent.length() >= prompt.length());
			logger.info("Forest+adjacent prompt: " + promptWithAdjacent.substring(0, Math.min(250, promptWithAdjacent.length())) + "...");

			// Test different terrain types
			String[] terrains = {"DESERT", "MOUNTAIN", "OCEAN", "JUNGLE", "TUNDRA", "SAVANNA"};
			for (String terrain : terrains) {
				location.set(FieldNames.FIELD_TERRAIN_TYPE, terrain);
				String terrainPrompt = NarrativeUtil.getLandscapePrompt(location, null);
				assertNotNull("Prompt for " + terrain + " should not be null", terrainPrompt);
				assertFalse("Prompt for " + terrain + " should not be empty", terrainPrompt.isEmpty());
				logger.info(terrain + " prompt length: " + terrainPrompt.length());
			}

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error("Error creating test location", e);
			throw new RuntimeException(e);
		}
	}

	/// Test NarrativeUtil.getLandscapeNegativePrompt() returns valid negative prompt
	@Test
	public void TestLandscapeNegativePrompt() {
		logger.info("Test Landscape Negative Prompt");

		String negPrompt = NarrativeUtil.getLandscapeNegativePrompt();
		assertNotNull("Negative prompt should not be null", negPrompt);
		assertFalse("Negative prompt should not be empty", negPrompt.isEmpty());
		assertTrue("Negative prompt should exclude people", negPrompt.toLowerCase().contains("people"));
		assertTrue("Negative prompt should exclude animals", negPrompt.toLowerCase().contains("animals"));
		logger.info("Negative prompt: " + negPrompt);
	}

	/// Test NarrativeUtil.getLandscapeSettingDescription() for use in character/animal prompts
	@Test
	public void TestLandscapeSettingDescription() {
		logger.info("Test Landscape Setting Description");

		try {
			BaseRecord location = RecordFactory.newInstance(OlioModelNames.MODEL_GEO_LOCATION);
			location.set(FieldNames.FIELD_NAME, "Emerald Valley");
			location.set(FieldNames.FIELD_TERRAIN_TYPE, TerrainEnumType.VALLEY.toString());

			// Test without adjacent
			String setting = NarrativeUtil.getLandscapeSettingDescription(location, null);
			assertNotNull("Setting description should not be null", setting);
			assertTrue("Setting should mention feature name", setting.contains("Emerald Valley"));
			logger.info("Setting (no adjacent): " + setting);

			// Test with adjacent
			Set<String> adjacent = new HashSet<>();
			adjacent.add(TerrainEnumType.STREAM.toString());

			String settingWithAdjacent = NarrativeUtil.getLandscapeSettingDescription(location, adjacent);
			assertNotNull("Setting with adjacent should not be null", settingWithAdjacent);
			assertTrue("Setting should be descriptive", settingWithAdjacent.length() > 20);
			logger.info("Setting (with adjacent): " + settingWithAdjacent);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error("Error creating test location", e);
			throw new RuntimeException(e);
		}
	}

	/// Test NarrativeUtil.getAnimalPrompt() generates valid prompts
	@Test
	public void TestAnimalPromptGeneration() {
		logger.info("Test Animal Prompt Generation");

		try {
			// Create a mock animal record
			// Note: Animal model uses 'type' for animal type and 'groupName' for group type
			BaseRecord animal = RecordFactory.newInstance(OlioModelNames.MODEL_ANIMAL);
			animal.set(FieldNames.FIELD_NAME, "Shadow");
			animal.set(FieldNames.FIELD_TYPE, "wolf");
			animal.set("groupName", "PREDATOR");

			// Test without landscape
			String prompt = NarrativeUtil.getAnimalPrompt(animal, null, null);
			assertNotNull("Animal prompt should not be null", prompt);
			assertTrue("Prompt should contain quality tags", prompt.contains("8k highly detailed"));
			assertTrue("Prompt should mention wolf", prompt.toLowerCase().contains("wolf"));
			assertTrue("Prompt should mention predatory behavior",
				prompt.toLowerCase().contains("alert") || prompt.toLowerCase().contains("predator"));
			logger.info("Wolf prompt: " + prompt.substring(0, Math.min(200, prompt.length())) + "...");

			// Test with landscape setting
			String landscapeSetting = "temperate forest with dappled sunlight, near a babbling stream";
			String promptWithLandscape = NarrativeUtil.getAnimalPrompt(animal, landscapeSetting, null);
			assertNotNull("Animal prompt with landscape should not be null", promptWithLandscape);
			assertTrue("Prompt should include landscape setting",
				promptWithLandscape.toLowerCase().contains("forest"));
			logger.info("Wolf+landscape prompt length: " + promptWithLandscape.length());

			// Test different animal types
			animal.set(FieldNames.FIELD_TYPE, "deer");
			animal.set("groupName", "PREY");
			String deerPrompt = NarrativeUtil.getAnimalPrompt(animal, null, null);
			assertTrue("Deer prompt should mention cautious behavior",
				deerPrompt.toLowerCase().contains("cautious") || deerPrompt.toLowerCase().contains("grazing"));

			animal.set(FieldNames.FIELD_TYPE, "dog");
			animal.set("groupName", "DOMESTIC");
			String dogPrompt = NarrativeUtil.getAnimalPrompt(animal, null, null);
			assertTrue("Dog prompt should mention calm behavior",
				dogPrompt.toLowerCase().contains("calm") || dogPrompt.toLowerCase().contains("friendly"));

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error("Error creating test animal", e);
			throw new RuntimeException(e);
		}
	}

	/// Test NarrativeUtil.getAnimalNegativePrompt() returns valid negative prompt
	@Test
	public void TestAnimalNegativePrompt() {
		logger.info("Test Animal Negative Prompt");

		String negPrompt = NarrativeUtil.getAnimalNegativePrompt();
		assertNotNull("Negative prompt should not be null", negPrompt);
		assertFalse("Negative prompt should not be empty", negPrompt.isEmpty());
		assertTrue("Negative prompt should exclude humans", negPrompt.toLowerCase().contains("human"));
		logger.info("Animal negative prompt: " + negPrompt);
	}

	/// Integration test for SDUtil.generateLandscapeImage()
	/// This test requires a running Swarm SD server
	@Test
	public void TestGenerateLandscapeImage() {
		logger.info("Test Generate Landscape Image (Integration)");

		String swarmServer = testProperties.getProperty("test.swarm.server");
		if (swarmServer == null || swarmServer.isEmpty()) {
			logger.warn("Skipping integration test - test.swarm.server not configured");
			return;
		}

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataPath = testProperties.getProperty("test.datagen.path");
		OlioContext ctx = OlioTestUtil.getContext(testOrgContext, dataPath);
		assertNotNull("Context should not be null", ctx);

		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		BaseRecord origin = realm.get(OlioFieldNames.FIELD_ORIGIN);
		assertNotNull("Realm origin should not be null", origin);

		// Get adjacent cells for terrain variety
		List<BaseRecord> adjacentCells = GeoLocationUtil.getAdjacentCells(ctx, origin, 2);
		Set<String> adjacentTerrains = new HashSet<>();
		for (BaseRecord cell : adjacentCells) {
			String terrain = cell.get(FieldNames.FIELD_TERRAIN_TYPE);
			if (terrain != null) {
				adjacentTerrains.add(terrain);
			}
		}

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		BaseRecord sdConfig = getSwarmConfig();

		String groupPath = "~/Gallery/Landscapes/Test-" + UUID.randomUUID().toString();

		logger.info("Generating landscape image for location: " + origin.get(FieldNames.FIELD_NAME));
		BaseRecord image = sdu.generateLandscapeImage(testUser1, groupPath, origin, adjacentTerrains, sdConfig, false, -1);

		assertNotNull("Landscape image should be generated", image);
		byte[] imageData = image.get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("Image data should not be null", imageData);
		assertTrue("Image data should have content", imageData.length > 0);

		// Save to file for visual inspection
		String filename = "./landscape-" + origin.get(FieldNames.FIELD_TERRAIN_TYPE) + ".png";
		FileUtil.emitFile(filename, imageData);
		logger.info("Saved landscape image to: " + filename + " (" + imageData.length + " bytes)");
	}

	/// Integration test for SDUtil.createAnimalImage()
	/// This test requires a running Swarm SD server
	@Test
	public void TestCreateAnimalImage() {
		logger.info("Test Create Animal Image (Integration)");

		String swarmServer = testProperties.getProperty("test.swarm.server");
		if (swarmServer == null || swarmServer.isEmpty()) {
			logger.warn("Skipping integration test - test.swarm.server not configured");
			return;
		}

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataPath = testProperties.getProperty("test.datagen.path");
		OlioContext ctx = OlioTestUtil.getContext(testOrgContext, dataPath);
		assertNotNull("Context should not be null", ctx);

		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		BaseRecord origin = realm.get(OlioFieldNames.FIELD_ORIGIN);

		// Create a test animal
		BaseRecord animal = null;
		try {
			animal = RecordFactory.newInstance(OlioModelNames.MODEL_ANIMAL);
			animal.set(FieldNames.FIELD_NAME, "TestWolf-" + UUID.randomUUID().toString().substring(0, 8));
			animal.set(FieldNames.FIELD_TYPE, "wolf");
			animal.set("groupName", "PREDATOR");
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error("Error creating test animal", e);
			throw new RuntimeException(e);
		}

		// Get terrain info
		Set<String> adjacentTerrains = new HashSet<>();
		String terrain = origin.get(FieldNames.FIELD_TERRAIN_TYPE);
		if (terrain != null) {
			adjacentTerrains.add(terrain);
		}

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		BaseRecord sdConfig = getSwarmConfig();

		String groupPath = "~/Gallery/Animals/Test-" + UUID.randomUUID().toString();

		logger.info("Generating animal image for: " + animal.get(FieldNames.FIELD_TYPE));
		List<BaseRecord> images = sdu.createAnimalImage(testUser1, animal, groupPath, origin, adjacentTerrains, null, sdConfig, false, -1);

		assertNotNull("Animal images list should not be null", images);
		assertTrue("Should have at least one image", images.size() > 0);

		BaseRecord image = images.get(0);
		byte[] imageData = image.get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("Image data should not be null", imageData);
		assertTrue("Image data should have content", imageData.length > 0);

		// Save to file for visual inspection
		String filename = "./animal-" + animal.get(FieldNames.FIELD_TYPE) + ".png";
		FileUtil.emitFile(filename, imageData);
		logger.info("Saved animal image to: " + filename + " (" + imageData.length + " bytes)");
	}

	/// Test animal image generation with landscape reference (img2img)
	@Test
	public void TestAnimalImageWithLandscapeReference() {
		logger.info("Test Animal Image with Landscape Reference (Integration)");

		String swarmServer = testProperties.getProperty("test.swarm.server");
		if (swarmServer == null || swarmServer.isEmpty()) {
			logger.warn("Skipping integration test - test.swarm.server not configured");
			return;
		}

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataPath = testProperties.getProperty("test.datagen.path");
		OlioContext ctx = OlioTestUtil.getContext(testOrgContext, dataPath);
		assertNotNull("Context should not be null", ctx);

		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		BaseRecord origin = realm.get(OlioFieldNames.FIELD_ORIGIN);

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		BaseRecord sdConfig = getSwarmConfig();

		// First generate a landscape
		String landscapePath = "~/Gallery/Landscapes/RefTest-" + UUID.randomUUID().toString();
		Set<String> terrains = new HashSet<>();
		terrains.add(origin.get(FieldNames.FIELD_TERRAIN_TYPE));

		logger.info("Step 1: Generating landscape image as reference...");
		BaseRecord landscape = sdu.generateLandscapeImage(testUser1, landscapePath, origin, terrains, sdConfig, false, -1);
		assertNotNull("Landscape should be generated", landscape);

		String landscapeId = landscape.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Landscape should have objectId", landscapeId);
		logger.info("Landscape generated with ID: " + landscapeId);

		// Now create animal using the landscape as reference
		BaseRecord animal = null;
		try {
			animal = RecordFactory.newInstance(OlioModelNames.MODEL_ANIMAL);
			animal.set(FieldNames.FIELD_NAME, "TestDeer-" + UUID.randomUUID().toString().substring(0, 8));
			animal.set(FieldNames.FIELD_TYPE, "deer");
			animal.set("groupName", "PREY");
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error("Error creating test animal", e);
			throw new RuntimeException(e);
		}

		String animalPath = "~/Gallery/Animals/RefTest-" + UUID.randomUUID().toString();

		logger.info("Step 2: Generating animal image with landscape reference...");
		List<BaseRecord> images = sdu.createAnimalImage(testUser1, animal, animalPath, origin, terrains, landscapeId, sdConfig, false, -1);

		assertNotNull("Animal images list should not be null", images);
		assertTrue("Should have at least one image", images.size() > 0);

		BaseRecord image = images.get(0);
		byte[] imageData = image.get(FieldNames.FIELD_BYTE_STORE);
		assertNotNull("Image data should not be null", imageData);
		assertTrue("Image data should have content", imageData.length > 0);

		// Save both for visual comparison
		FileUtil.emitFile("./landscape-reference.png", (byte[])landscape.get(FieldNames.FIELD_BYTE_STORE));
		FileUtil.emitFile("./animal-with-landscape.png", imageData);
		logger.info("Saved images for visual comparison");
	}

	private BaseRecord getSwarmConfig() {
		BaseRecord sdConfig = SDUtil.randomSDConfig();
		sdConfig.setValue("model", testProperties.getProperty("test.swarm.model"));
		sdConfig.setValue("refinerModel", testProperties.getProperty("test.swarm.refinerModel"));
		sdConfig.setValue("scheduler", "Karras");
		sdConfig.setValue("sampler", "dpm_2");
		sdConfig.setValue("hires", false);
		sdConfig.setValue("steps", 25);
		return sdConfig;
	}
}
