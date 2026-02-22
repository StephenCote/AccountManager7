package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.llm.InteractionExtractor;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 3 interaction extraction tests (MemoryRefactor2.md).
 *
 * Verifies interaction field on memory model, interaction record creation,
 * prompt loading, memory-interaction linking, chirp output, NONE handling,
 * canonical ordering, and persistence roundtrip.
 */
public class TestInteractionExtractor extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupInteractionExtractor() {
		testOrgContext = getTestOrganization("/Development/InteractionExtractor");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "interactionExtractUser", testOrgContext.getOrganizationId());
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

	/// Verify the interaction foreign reference field exists on tool.memory.
	@Test
	public void testInteractionFieldExists() {
		try {
			BaseRecord memory = RecordFactory.newInstance(ModelNames.MODEL_MEMORY);
			assertNotNull("Memory record should be created", memory);

			// interactionModel field should exist and be null by default
			String interModel = memory.get("interactionModel");
			assertNull("interactionModel should default to null", interModel);

			// interaction field should exist
			BaseRecord interRef = memory.get("interaction");
			assertNull("interaction should default to null", interRef);

			logger.info("testInteractionFieldExists passed");
		} catch (Exception e) {
			logger.error("testInteractionFieldExists failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Create an olio.interaction record at ~/Interactions using the two-phase pattern.
	@Test
	public void testInteractionRecordCreation() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
			String name = "SOCIALIZE " + UUID.randomUUID().toString().substring(0, 8);
			plist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord inter = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_INTERACTION, testUser, null, plist);
			assertNotNull("Interaction record should be created", inter);

			inter.set(FieldNames.FIELD_TYPE, InteractionEnumType.SOCIALIZE);
			inter.set("state", ActionResultEnumType.COMPLETE);
			inter.set(FieldNames.FIELD_DESCRIPTION, "Test interaction");
			inter.set("actorOutcome", OutcomeEnumType.FAVORABLE);
			inter.set("interactorOutcome", OutcomeEnumType.EQUILIBRIUM);

			// Phase 1: Persist without foreign refs
			inter = IOSystem.getActiveContext().getAccessPoint().create(testUser, inter);
			assertNotNull("Interaction should persist", inter);

			String objectId = inter.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("objectId should be set", objectId);

			// Phase 2: Set actor/interactor and update using proper person records
			BaseRecord p1 = createTestPerson("actor");
			BaseRecord p2 = createTestPerson("interactor");

			inter.set(OlioFieldNames.FIELD_ACTOR_TYPE, "olio.charPerson");
			inter.set(OlioFieldNames.FIELD_ACTOR, p1);
			inter.set("interactorType", "olio.charPerson");
			inter.set(OlioFieldNames.FIELD_INTERACTOR, p2);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(inter);

			logger.info("testInteractionRecordCreation passed — objectId=" + objectId);
		} catch (Exception e) {
			logger.error("testInteractionRecordCreation failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Load interactionExtraction.json as promptTemplate, compose, verify content.
	@Test
	public void testInteractionPromptLoads() {
		String json = org.cote.accountmanager.util.ResourceUtil.getInstance().getResource(
			PromptResourceUtil.getPrefix() + "interactionExtraction.json");
		assertNotNull("Interaction prompt JSON should load from resources", json);
		BaseRecord templateRec = null;
		try {
			templateRec = RecordFactory.importRecord(json);
		} catch (Exception e) {
			fail("Failed to import interaction prompt as record: " + e.getMessage());
		}
		assertNotNull("Interaction prompt should load as record", templateRec);
		assertEquals("Should be a promptTemplate",
			"olio.llm.promptTemplate", templateRec.getSchema());

		String composed = PromptTemplateComposer.composeSystem(templateRec, null, null);
		assertNotNull("Composed prompt should not be null", composed);
		assertTrue("Should contain SOCIALIZE type",
			composed.contains("SOCIALIZE"));
		assertTrue("Should contain CONFLICT type",
			composed.contains("CONFLICT"));
		assertTrue("Should contain FAVORABLE outcome",
			composed.contains("FAVORABLE"));
		assertTrue("Should contain JSON format instruction",
			composed.contains("JSON"));
		assertTrue("Should contain systemCharName token",
			composed.contains("${systemCharName}"));

		logger.info("testInteractionPromptLoads passed");
	}

	/// Create memories and an interaction, link them, verify foreign ref is set.
	@Test
	public void testLinkMemoriesToInteraction() {
		try {
			String convId = "link-test-" + UUID.randomUUID().toString().substring(0, 8);

			// Create 2 memories
			List<BaseRecord> memories = new ArrayList<>();
			memories.add(MemoryUtil.createMemory(testUser, "Link test fact", "fact link",
				MemoryTypeEnumType.FACT, 5, "am7://test/link", convId));
			memories.add(MemoryUtil.createMemory(testUser, "Link test decision", "decision link",
				MemoryTypeEnumType.DECISION, 6, "am7://test/link", convId));
			assertEquals("Should have 2 memories", 2, memories.size());

			// Create an interaction
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
			plist.parameter(FieldNames.FIELD_NAME, "HELP " + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord inter = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_INTERACTION, testUser, null, plist);
			inter.set(FieldNames.FIELD_TYPE, InteractionEnumType.HELP);
			inter.set("state", ActionResultEnumType.COMPLETE);
			inter.set("actorOutcome", OutcomeEnumType.FAVORABLE);
			inter.set("interactorOutcome", OutcomeEnumType.FAVORABLE);
			inter = IOSystem.getActiveContext().getAccessPoint().create(testUser, inter);
			assertNotNull("Interaction should persist", inter);

			String interOid = inter.get(FieldNames.FIELD_OBJECT_ID);

			// Link memories to interaction
			InteractionExtractor.linkMemoriesToInteraction(memories, inter);

			// Verify each memory now has the interaction foreign ref
			for (BaseRecord mem : memories) {
				String model = mem.get("interactionModel");
				assertEquals("interactionModel should be set", OlioModelNames.MODEL_INTERACTION, model);
			}

			logger.info("testLinkMemoriesToInteraction passed — interactionOid=" + interOid);
		} catch (Exception e) {
			logger.error("testLinkMemoriesToInteraction failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Create an interaction with known values, build chirp, verify JSON format.
	@Test
	public void testBuildInteractionChirp() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
			plist.parameter(FieldNames.FIELD_NAME, "MENTOR " + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord inter = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_INTERACTION, testUser, null, plist);
			inter.set(FieldNames.FIELD_TYPE, InteractionEnumType.MENTOR);
			inter.set("state", ActionResultEnumType.COMPLETE);
			inter.set(FieldNames.FIELD_DESCRIPTION, "Teaching basic skills");
			inter.set("actorOutcome", OutcomeEnumType.FAVORABLE);
			inter.set("interactorOutcome", OutcomeEnumType.FAVORABLE);
			inter = IOSystem.getActiveContext().getAccessPoint().create(testUser, inter);
			assertNotNull("Interaction should persist", inter);

			String chirp = InteractionExtractor.buildInteractionChirp(inter);
			assertNotNull("Chirp should not be null", chirp);
			assertTrue("Chirp should contain interactionType", chirp.contains("\"interactionType\""));
			assertTrue("Chirp should contain MENTOR", chirp.contains("MENTOR"));
			assertTrue("Chirp should contain actorOutcome", chirp.contains("\"actorOutcome\""));
			assertTrue("Chirp should contain targetOutcome", chirp.contains("\"targetOutcome\""));
			assertTrue("Chirp should contain summary", chirp.contains("\"summary\""));
			assertTrue("Chirp should contain relationship direction", chirp.contains("\"relationshipDirection\""));
			// MENTOR is a positive interaction
			assertTrue("Chirp should indicate POSITIVE outcome", chirp.contains("POSITIVE"));
			assertTrue("Chirp should indicate IMPROVING direction", chirp.contains("IMPROVING"));

			logger.info("testBuildInteractionChirp passed — chirp=" + chirp);
		} catch (Exception e) {
			logger.error("testBuildInteractionChirp failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that a type=NONE JSON response produces null (no record created).
	@Test
	public void testInteractionTypeNoneReturnsNull() {
		try {
			String noneJson = "{\"type\": \"NONE\"}";
			BaseRecord p1 = createTestPerson("none-p1");
			BaseRecord p2 = createTestPerson("none-p2");

			BaseRecord result = InteractionExtractor.parseAndPersist(
				testUser, null, p1, p2, noneJson, "none-test");
			assertNull("NONE type should return null", result);

			logger.info("testInteractionTypeNoneReturnsNull passed");
		} catch (Exception e) {
			logger.error("testInteractionTypeNoneReturnsNull failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify canonical person ordering in interaction creation.
	@Test
	public void testCanonicalPersonOrderInInteraction() {
		try {
			// Create two proper person records — canonical ordering puts lower ID first
			BaseRecord p1 = createTestPerson("canon-high");
			BaseRecord p2 = createTestPerson("canon-low");
			long id1 = p1.get(FieldNames.FIELD_ID);
			long id2 = p2.get(FieldNames.FIELD_ID);

			// Canonical ordering should put lower ID first
			BaseRecord[] canon = MemoryUtil.canonicalPersonOrder(p1, p2);
			long firstId = canon[0].get(FieldNames.FIELD_ID);
			long secondId = canon[1].get(FieldNames.FIELD_ID);
			assertTrue("First canonical person should have lower ID", firstId <= secondId);
			assertEquals("First canonical ID should be min of the two", Math.min(id1, id2), firstId);
			assertEquals("Second canonical ID should be max of the two", Math.max(id1, id2), secondId);

			logger.info("testCanonicalPersonOrderInInteraction passed");
		} catch (Exception e) {
			logger.error("testCanonicalPersonOrderInInteraction failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Create an interaction, query it back, verify all fields match.
	@Test
	public void testInteractionPersistenceRoundtrip() {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
			String name = "CONFLICT " + UUID.randomUUID().toString().substring(0, 8);
			plist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord inter = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_INTERACTION, testUser, null, plist);
			inter.set(FieldNames.FIELD_TYPE, InteractionEnumType.CONFLICT);
			inter.set("state", ActionResultEnumType.COMPLETE);
			inter.set(FieldNames.FIELD_DESCRIPTION, "Heated argument");
			inter.set("actorOutcome", OutcomeEnumType.UNFAVORABLE);
			inter.set("interactorOutcome", OutcomeEnumType.FAVORABLE);

			inter = IOSystem.getActiveContext().getAccessPoint().create(testUser, inter);
			assertNotNull("Interaction should persist", inter);

			// Query back by objectId
			String objectId = inter.get(FieldNames.FIELD_OBJECT_ID);
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_INTERACTION, FieldNames.FIELD_OBJECT_ID, objectId);
			q.planMost(false);
			BaseRecord[] results = IOSystem.getActiveContext().getSearch().findRecords(q);
			assertNotNull("Query results should not be null", results);
			assertTrue("Should find at least 1 result", results.length > 0);

			BaseRecord found = results[0];
			assertEquals("Type should match", "CONFLICT", found.get(FieldNames.FIELD_TYPE).toString());
			assertEquals("State should match", "COMPLETE", found.get("state").toString());
			assertEquals("Description should match", "Heated argument", found.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("actorOutcome should match", "UNFAVORABLE", found.get("actorOutcome").toString());
			assertEquals("interactorOutcome should match", "FAVORABLE", found.get("interactorOutcome").toString());

			logger.info("testInteractionPersistenceRoundtrip passed — objectId=" + objectId);
		} catch (Exception e) {
			logger.error("testInteractionPersistenceRoundtrip failed", e);
			fail("Exception: " + e.getMessage());
		}
	}
}
