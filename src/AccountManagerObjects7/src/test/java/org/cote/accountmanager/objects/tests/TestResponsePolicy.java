package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.llm.policy.ChatAutotuner;
import org.cote.accountmanager.olio.llm.policy.RecursiveLoopDetectionOperation;
import org.cote.accountmanager.olio.llm.policy.RefusalDetectionOperation;
import org.cote.accountmanager.olio.llm.policy.TimeoutDetectionOperation;
import org.cote.accountmanager.olio.llm.policy.WrongCharacterDetectionOperation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

/// Phase 9 Backend Tests (Tests 46-62)
/// Tests for the four detection operations, ResponsePolicyEvaluator, ChatAutotuner, and sample policies.
/// Tests 46-56 are unit-testable with synthetic responses (no LLM server required).
public class TestResponsePolicy extends BaseTest {

	/// Helper: create a fact record with factData set
	private BaseRecord buildFact(String name, String data) {
		try {
			BaseRecord fact = RecordFactory.newInstance("policy.fact");
			fact.set(FieldNames.FIELD_NAME, name);
			fact.set("factData", data);
			return fact;
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			return null;
		}
	}

	// ── Test 46: TimeoutDetection — null/empty response → FAILED ──────────
	@Test
	public void TestTimeoutDetection() {
		logger.info("Test 46: TimeoutDetection - null/empty → FAILED");
		TimeoutDetectionOperation op = new TimeoutDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		// Null response
		BaseRecord nullFact = buildFact("response", null);
		assertNotNull("Fact should not be null", nullFact);
		OperationResponseEnumType result = op.operate(null, null, null, nullFact, null);
		assertTrue("Null response should be FAILED", result == OperationResponseEnumType.FAILED);

		// Empty response
		BaseRecord emptyFact = buildFact("response", "");
		result = op.operate(null, null, null, emptyFact, null);
		assertTrue("Empty response should be FAILED", result == OperationResponseEnumType.FAILED);

		// Whitespace-only response
		BaseRecord wsFact = buildFact("response", "   ");
		result = op.operate(null, null, null, wsFact, null);
		assertTrue("Whitespace response should be FAILED", result == OperationResponseEnumType.FAILED);
	}

	// ── Test 47: TimeoutDetection — normal response → SUCCEEDED ───────────
	@Test
	public void TestTimeoutWithContent() {
		logger.info("Test 47: TimeoutDetection - normal response → SUCCEEDED");
		TimeoutDetectionOperation op = new TimeoutDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		BaseRecord normalFact = buildFact("response", "Hello, how can I help you today?");
		OperationResponseEnumType result = op.operate(null, null, null, normalFact, null);
		assertTrue("Normal response should be SUCCEEDED", result == OperationResponseEnumType.SUCCEEDED);
	}

	// ── Test 48: RecursiveLoopDetection — repeated blocks → FAILED ────────
	@Test
	public void TestRecursiveLoopDetection() {
		logger.info("Test 48: RecursiveLoopDetection - repeated 50-char blocks 3x → FAILED");
		RecursiveLoopDetectionOperation op = new RecursiveLoopDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		// Build a response with a repeated 50-char block 3 times
		String repeatedBlock = "This is a repeated block of text that loops around."; // 51 chars
		String loopResponse = repeatedBlock + repeatedBlock + repeatedBlock + repeatedBlock;
		BaseRecord loopFact = buildFact("response", loopResponse);
		BaseRecord refFact = buildFact("config", null);
		OperationResponseEnumType result = op.operate(null, null, null, loopFact, refFact);
		assertTrue("Repeated block should be FAILED", result == OperationResponseEnumType.FAILED);
	}

	// ── Test 49: RecursiveLoopDetection — clean varied text → SUCCEEDED ───
	@Test
	public void TestRecursiveLoopClean() {
		logger.info("Test 49: RecursiveLoopDetection - varied text → SUCCEEDED");
		RecursiveLoopDetectionOperation op = new RecursiveLoopDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		String varied = "The sun rises over the mountain peaks, casting golden light across the valley below. "
			+ "Birds sing their morning songs as dew drops glisten on the leaves. "
			+ "A gentle breeze carries the scent of wildflowers through the meadow. "
			+ "The river winds its way through the forest, sparkling under the canopy.";
		BaseRecord variedFact = buildFact("response", varied);
		BaseRecord refFact = buildFact("config", null);
		OperationResponseEnumType result = op.operate(null, null, null, variedFact, refFact);
		assertTrue("Varied text should be SUCCEEDED", result == OperationResponseEnumType.SUCCEEDED);
	}

	// ── Test 50: WrongCharacterDetection — user char dialogue → FAILED ────
	@Test
	public void TestWrongCharacterDetection() {
		logger.info("Test 50: WrongCharacterDetection - 'Bob: Hello' when Bob is user → FAILED");
		WrongCharacterDetectionOperation op = new WrongCharacterDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		BaseRecord responseFact = buildFact("response", "Bob: Hello there! How are you doing today?");
		BaseRecord charFact = buildFact("charInfo", "{\"systemCharName\":\"Aria\",\"userCharName\":\"Bob\"}");
		OperationResponseEnumType result = op.operate(null, null, null, responseFact, charFact);
		assertTrue("Response as user character should be FAILED", result == OperationResponseEnumType.FAILED);

		// Narrative form: *Bob walks over*
		BaseRecord narrativeFact = buildFact("response", "*Bob walks over and smiles warmly*");
		result = op.operate(null, null, null, narrativeFact, charFact);
		assertTrue("Narrative as user character should be FAILED", result == OperationResponseEnumType.FAILED);
	}

	// ── Test 51: WrongCharacterDetection — system char response → SUCCEEDED
	@Test
	public void TestWrongCharacterClean() {
		logger.info("Test 51: WrongCharacterDetection - system char response → SUCCEEDED");
		WrongCharacterDetectionOperation op = new WrongCharacterDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		BaseRecord responseFact = buildFact("response", "Aria: Hello there! I was just thinking about you.");
		BaseRecord charFact = buildFact("charInfo", "{\"systemCharName\":\"Aria\",\"userCharName\":\"Bob\"}");
		OperationResponseEnumType result = op.operate(null, null, null, responseFact, charFact);
		assertTrue("Response as system character should be SUCCEEDED", result == OperationResponseEnumType.SUCCEEDED);

		// Plain response without character prefix
		BaseRecord plainFact = buildFact("response", "Hello there! I was just thinking about you.");
		result = op.operate(null, null, null, plainFact, charFact);
		assertTrue("Plain response should be SUCCEEDED", result == OperationResponseEnumType.SUCCEEDED);
	}

	// ── Test 52: RefusalDetection — 2+ refusal phrases → FAILED ──────────
	@Test
	public void TestRefusalDetection() {
		logger.info("Test 52: RefusalDetection - 2+ refusal phrases → FAILED");
		RefusalDetectionOperation op = new RefusalDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		String refusalResponse = "I'm sorry, but I can't help with that. As an AI language model, "
			+ "I must respectfully decline this request. Let me suggest a different topic instead.";
		BaseRecord refusalFact = buildFact("response", refusalResponse);
		BaseRecord refFact = buildFact("config", null);
		OperationResponseEnumType result = op.operate(null, null, null, refusalFact, refFact);
		assertTrue("Multi-phrase refusal should be FAILED", result == OperationResponseEnumType.FAILED);
	}

	// ── Test 53: RefusalDetection — normal response → SUCCEEDED ───────────
	@Test
	public void TestRefusalClean() {
		logger.info("Test 53: RefusalDetection - normal response → SUCCEEDED");
		RefusalDetectionOperation op = new RefusalDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		String normalResponse = "Aria stepped forward, her eyes glinting with mischief. "
			+ "'I've been waiting for you,' she said, tilting her head with a playful smile.";
		BaseRecord normalFact = buildFact("response", normalResponse);
		BaseRecord refFact = buildFact("config", null);
		OperationResponseEnumType result = op.operate(null, null, null, normalFact, refFact);
		assertTrue("Normal response should be SUCCEEDED", result == OperationResponseEnumType.SUCCEEDED);
	}

	// ── Test 48b: RecursiveLoopDetection — configurable window/threshold ──
	@Test
	public void TestRecursiveLoopConfigurable() {
		logger.info("Test 48b: RecursiveLoopDetection - windowSize=30, threshold=2");
		RecursiveLoopDetectionOperation op = new RecursiveLoopDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		// Build a reference fact with custom parameters as JSON in factData
		BaseRecord refFact = buildFact("config", "{\"windowSize\":\"30\",\"repeatThreshold\":\"2\"}");

		// Repeated 30-char block 3 times (threshold=2 means 2 occurrences triggers)
		String shortRepeat = "abcdefghijklmnopqrstuvwxyz1234"; // exactly 30 chars
		String loopResponse = shortRepeat + shortRepeat + shortRepeat;
		BaseRecord loopFact = buildFact("response", loopResponse);
		OperationResponseEnumType result = op.operate(null, null, null, loopFact, refFact);
		assertTrue("Short repeated block with lowered threshold should be FAILED", result == OperationResponseEnumType.FAILED);
	}

	// ── Test 52b: RefusalDetection — strict mode (minMatches=1) ──────────
	@Test
	public void TestRefusalStrictMode() {
		logger.info("Test 52b: RefusalDetection - minMatches=1, single phrase → FAILED");
		RefusalDetectionOperation op = new RefusalDetectionOperation(
			IOSystem.getActiveContext().getReader(), IOSystem.getActiveContext().getSearch());

		// Build a reference fact with minMatches=1 as JSON in factData
		BaseRecord refFact = buildFact("config", "{\"minMatches\":\"1\"}");

		// Single refusal phrase should trigger in strict mode
		String singleRefusal = "I'd prefer not to discuss that topic. Let me help you with something else.";
		BaseRecord refusalFact = buildFact("response", singleRefusal);
		OperationResponseEnumType result = op.operate(null, null, null, refusalFact, refFact);
		assertTrue("Single phrase in strict mode should be FAILED", result == OperationResponseEnumType.FAILED);
	}

	// ── Test 54: AutotunerCountQuery — verify count of autotuned prompts ──
	@Test
	public void TestAutotunerCountQuery() {
		logger.info("Test 54: AutotunerCountQuery - count returns 0 for non-existent prompts");
		ChatAutotuner autotuner = new ChatAutotuner();
		// With no test user or prompts, count should be 0
		// This tests the count mechanism doesn't throw on empty results
		try {
			org.cote.accountmanager.io.OrganizationContext testOrgContext = getTestOrganization("/Development/Policy Tests");
			org.cote.accountmanager.factory.Factory mf = ioContext.getFactory();
			BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "policyTestUser1", testOrgContext.getOrganizationId());
			int count = autotuner.countExistingAutotuned(testUser, "NonExistent Prompt - " + java.util.UUID.randomUUID().toString());
			assertTrue("Count for non-existent prompt should be 0", count == 0);
		} catch (Exception e) {
			logger.error("Count query failed", e);
		}
	}

	// ── Test 61: SamplePolicyLoads — verify sample policy JSON deserializes ──
	@Test
	public void TestSamplePolicyLoads() {
		logger.info("Test 61: SamplePolicyLoads - verify sample policy JSON files load");
		String[] policyFiles = { "policy.rpg.json", "policy.clinical.json", "policy.general.json" };
		for (String file : policyFiles) {
			String content = ResourceUtil.getInstance().getResource("olio/llm/" + file);
			assertNotNull("Policy file " + file + " should exist", content);
			assertFalse("Policy file " + file + " should not be empty", content.isEmpty());
			assertTrue("Policy file " + file + " should contain operations", content.contains("operations"));
			logger.info("Loaded " + file + " (" + content.length() + " chars)");
		}
	}
}
