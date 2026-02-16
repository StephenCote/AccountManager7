package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.llm.TemplatePatternEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/// Phase 14 test suite covering:
/// - New MemoryTypeEnumType values (FACT, RELATIONSHIP, EMOTION)
/// - Memory extraction prompt loading
/// - Memory deduplication (Jaccard similarity, merge logic)
/// - Enhanced reconstitution template variables
/// - chatConfigModel new fields (analyzeTimeout, memoryExtractionPrompt)
public class TestPhase14Memory extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupMemory() {
		testOrgContext = getTestOrganization("/Development/Phase14Memory");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "phase14TestUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	private BaseRecord createTestPerson(String name) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Persons");
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord person = ioContext.getFactory().newInstance(
				OlioModelNames.MODEL_CHAR_PERSON, testUser, null, plist);
			assertNotNull("Person record should not be null for " + name, person);
			ioContext.getRecordUtil().createRecord(person);
			long id = person.get(FieldNames.FIELD_ID);
			assertTrue("Person should have a valid ID", id > 0);
			return person;
		} catch (Exception e) {
			fail("Failed to create test person " + name + ": " + e.getMessage());
			return null;
		}
	}

	private long getPersonId(BaseRecord person) {
		return person.get(FieldNames.FIELD_ID);
	}

	// --- Group A: New MemoryTypeEnumType values ---

	@Test
	public void testNewMemoryTypeEnumValues() {
		assertNotNull("FACT enum should exist", MemoryTypeEnumType.FACT);
		assertNotNull("RELATIONSHIP enum should exist", MemoryTypeEnumType.RELATIONSHIP);
		assertNotNull("EMOTION enum should exist", MemoryTypeEnumType.EMOTION);

		assertEquals("FACT should roundtrip", MemoryTypeEnumType.FACT, MemoryTypeEnumType.fromValue("FACT"));
		assertEquals("RELATIONSHIP should roundtrip", MemoryTypeEnumType.RELATIONSHIP, MemoryTypeEnumType.fromValue("RELATIONSHIP"));
		assertEquals("EMOTION should roundtrip", MemoryTypeEnumType.EMOTION, MemoryTypeEnumType.fromValue("EMOTION"));

		assertNotNull(MemoryTypeEnumType.DISCOVERY);
		assertNotNull(MemoryTypeEnumType.DECISION);
		assertNotNull(MemoryTypeEnumType.OUTCOME);
		assertNotNull(MemoryTypeEnumType.NOTE);
	}

	@Test
	public void testCreateMemoryWithNewTypes() {
		try {
			String convId = "p14-type-" + UUID.randomUUID().toString();

			BaseRecord factMem = MemoryUtil.createMemory(testUser, "Elena is afraid of water.",
				"Elena fears water", MemoryTypeEnumType.FACT, 8,
				"am7://test/phase14", convId);
			assertNotNull("FACT memory should be created", factMem);
			assertEquals("FACT", factMem.get("memoryType").toString());

			BaseRecord relMem = MemoryUtil.createMemory(testUser, "Trust between Elena and Marcus deepened after the rescue.",
				"Elena trusts Marcus more", MemoryTypeEnumType.RELATIONSHIP, 7,
				"am7://test/phase14", convId);
			assertNotNull("RELATIONSHIP memory should be created", relMem);

			BaseRecord emoMem = MemoryUtil.createMemory(testUser, "Elena broke down crying when she remembered her father.",
				"Elena cried about father", MemoryTypeEnumType.EMOTION, 6,
				"am7://test/phase14", convId);
			assertNotNull("EMOTION memory should be created", emoMem);

			logger.info("New memory type creation tests passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group B: Memory Extraction Prompt Loading ---

	@Test
	public void testMemoryExtractionPromptLoads() {
		String systemPrompt = PromptResourceUtil.getLines("memoryExtraction", "system");
		assertNotNull("Memory extraction prompt should load", systemPrompt);
		assertTrue("Prompt should contain FACT category", systemPrompt.contains("FACT"));
		assertTrue("Prompt should contain RELATIONSHIP category", systemPrompt.contains("RELATIONSHIP"));
		assertTrue("Prompt should contain EMOTION category", systemPrompt.contains("EMOTION"));
		assertTrue("Prompt should contain ${systemCharName} token", systemPrompt.contains("${systemCharName}"));
		assertTrue("Prompt should contain ${userCharName} token", systemPrompt.contains("${userCharName}"));
		assertTrue("Prompt should contain ${setting} token", systemPrompt.contains("${setting}"));
	}

	@Test
	public void testMemoryExtractionPromptTokenReplacement() {
		String systemPrompt = PromptResourceUtil.getLines("memoryExtraction", "system");
		assertNotNull(systemPrompt);

		systemPrompt = PromptResourceUtil.replaceToken(systemPrompt, "systemCharName", "Elena");
		systemPrompt = PromptResourceUtil.replaceToken(systemPrompt, "userCharName", "Marcus");
		systemPrompt = PromptResourceUtil.replaceToken(systemPrompt, "setting", "medieval fantasy");

		assertTrue("Prompt should contain Elena", systemPrompt.contains("Elena"));
		assertTrue("Prompt should contain Marcus", systemPrompt.contains("Marcus"));
		assertTrue("Prompt should contain medieval fantasy", systemPrompt.contains("medieval fantasy"));
		assertFalse("No unreplaced tokens should remain for systemCharName", systemPrompt.contains("${systemCharName}"));
	}

	// --- Group C: Memory Deduplication ---

	@Test
	public void testJaccardSimilarityIdentical() {
		Set<String> a = MemoryUtil.tokenize("Elena is afraid of water and cannot swim");
		Set<String> b = MemoryUtil.tokenize("Elena is afraid of water and cannot swim");
		double sim = MemoryUtil.jaccardSimilarity(a, b);
		assertEquals("Identical texts should have similarity 1.0", 1.0, sim, 0.001);
	}

	@Test
	public void testJaccardSimilarityHighOverlap() {
		Set<String> a = MemoryUtil.tokenize("Elena is afraid of water and cannot swim in rivers");
		Set<String> b = MemoryUtil.tokenize("Elena is afraid of water and she cannot swim well");
		double sim = MemoryUtil.jaccardSimilarity(a, b);
		assertTrue("High overlap texts should have similarity > 0.6", sim > 0.6);
	}

	@Test
	public void testJaccardSimilarityLowOverlap() {
		Set<String> a = MemoryUtil.tokenize("Elena is afraid of water");
		Set<String> b = MemoryUtil.tokenize("Marcus decided to attack the fortress at dawn");
		double sim = MemoryUtil.jaccardSimilarity(a, b);
		assertTrue("Different texts should have low similarity", sim < 0.3);
	}

	@Test
	public void testJaccardSimilarityEmpty() {
		Set<String> empty = MemoryUtil.tokenize("");
		Set<String> words = MemoryUtil.tokenize("some words here");
		assertEquals("Empty vs empty should be 1.0", 1.0, MemoryUtil.jaccardSimilarity(empty, empty), 0.001);
		assertEquals("Empty vs non-empty should be 0.0", 0.0, MemoryUtil.jaccardSimilarity(empty, words), 0.001);
	}

	@Test
	public void testTokenize() {
		Set<String> tokens = MemoryUtil.tokenize("Elena is afraid of water! She cannot swim.");
		assertTrue("Should contain 'elena'", tokens.contains("elena"));
		assertTrue("Should contain 'afraid'", tokens.contains("afraid"));
		assertTrue("Should contain 'water'", tokens.contains("water"));
		assertTrue("Should contain 'cannot'", tokens.contains("cannot"));
		assertTrue("Should contain 'swim'", tokens.contains("swim"));
		assertFalse("Should not contain short words like 'is'", tokens.contains("is"));
		assertFalse("Should not contain short words like 'of'", tokens.contains("of"));
	}

	@Test
	public void testFindSemanticDuplicateNoDuplicate() {
		try {
			String convId = "p14-dedup-" + UUID.randomUUID().toString();
			BaseRecord person1 = createTestPerson("DedupNoDup1-" + UUID.randomUUID());
			BaseRecord person2 = createTestPerson("DedupNoDup2-" + UUID.randomUUID());
			long pid1 = getPersonId(person1);
			long pid2 = getPersonId(person2);

			BaseRecord mem1 = MemoryUtil.createMemory(testUser, "Elena is afraid of water.",
				"Elena fears water", MemoryTypeEnumType.FACT, 8,
				"am7://test/dedup", convId, pid1, pid2, null);
			assertNotNull(mem1);

			List<BaseRecord> existing = List.of(mem1);
			BaseRecord dup = MemoryUtil.findSemanticDuplicate(testUser,
				"Marcus decided to attack the fortress at dawn.", existing);
			assertNull("Unrelated content should not be a duplicate", dup);
		} catch (Exception e) {
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testFindSemanticDuplicateWithDuplicate() {
		try {
			String convId = "p14-dedup2-" + UUID.randomUUID().toString();
			BaseRecord person1 = createTestPerson("DedupDup1-" + UUID.randomUUID());
			BaseRecord person2 = createTestPerson("DedupDup2-" + UUID.randomUUID());
			long pid1 = getPersonId(person1);
			long pid2 = getPersonId(person2);

			BaseRecord mem1 = MemoryUtil.createMemory(testUser, "Elena is afraid of water and cannot swim in rivers.",
				"Elena fears water", MemoryTypeEnumType.FACT, 8,
				"am7://test/dedup2", convId, pid1, pid2, null);
			assertNotNull(mem1);

			List<BaseRecord> existing = new java.util.ArrayList<>();
			existing.add(mem1);
			BaseRecord dup = MemoryUtil.findSemanticDuplicate(testUser,
				"Elena is afraid of water and cannot swim in the rivers.", existing);
			assertNotNull("Similar content should be detected as duplicate", dup);
			assertEquals("Duplicate should match the original", (long) mem1.get(FieldNames.FIELD_ID), (long) dup.get(FieldNames.FIELD_ID));
		} catch (Exception e) {
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testExtractMemoriesWithDedup() {
		try {
			String convId = "p14-extdedup-" + UUID.randomUUID().toString();
			BaseRecord person1 = createTestPerson("ExtDedup1-" + UUID.randomUUID());
			BaseRecord person2 = createTestPerson("ExtDedup2-" + UUID.randomUUID());
			long pid1 = getPersonId(person1);
			long pid2 = getPersonId(person2);

			// Create an existing memory
			MemoryUtil.createMemory(testUser, "Elena is afraid of water and cannot swim.",
				"Elena fears water", MemoryTypeEnumType.FACT, 7,
				"am7://test/extdedup", convId, pid1, pid2, null);

			// Extract with a duplicate and a new memory
			String llmResponse = "[\n" +
				"  {\"content\": \"Elena is afraid of water and she cannot swim well.\", \"summary\": \"Elena water fear\", \"memoryType\": \"FACT\", \"importance\": 9},\n" +
				"  {\"content\": \"Marcus decided to lead the expedition north.\", \"summary\": \"Marcus leads north\", \"memoryType\": \"DECISION\", \"importance\": 7}\n" +
				"]";

			List<BaseRecord> extracted = MemoryUtil.extractMemoriesFromResponse(
				testUser, llmResponse, "am7://test/extdedup2", convId, pid1, pid2, null);

			assertNotNull(extracted);
			assertEquals("Should process 2 memories (1 merged + 1 new)", 2, extracted.size());

			// The first one should have been merged (importance bumped to 9)
			boolean foundHighImportance = false;
			for (BaseRecord mem : extracted) {
				int imp = mem.get("importance");
				if (imp == 9) foundHighImportance = true;
			}
			assertTrue("Merged memory should have higher importance", foundHighImportance);

			logger.info("Dedup extraction test passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group D: Template Pattern Enum Extensions ---

	@Test
	public void testNewTemplatePatterns() {
		assertNotNull("MEMORY_DECISIONS should exist", TemplatePatternEnumType.MEMORY_DECISIONS);
		assertNotNull("MEMORY_EMOTIONS should exist", TemplatePatternEnumType.MEMORY_EMOTIONS);

		assertEquals("memory.decisions", TemplatePatternEnumType.MEMORY_DECISIONS.getKey());
		assertEquals("memory.emotions", TemplatePatternEnumType.MEMORY_EMOTIONS.getKey());

		// Test replacement works
		String template = "Decisions: ${memory.decisions} | Emotions: ${memory.emotions}";
		String replaced = TemplatePatternEnumType.MEMORY_DECISIONS.replace(template, "go north");
		assertTrue("Should replace decisions token", replaced.contains("go north"));
		assertTrue("Should still have emotions token", replaced.contains("${memory.emotions}"));

		replaced = TemplatePatternEnumType.MEMORY_EMOTIONS.replace(replaced, "joy and fear");
		assertTrue("Should replace emotions token", replaced.contains("joy and fear"));
		assertFalse("No unreplaced tokens should remain", replaced.contains("${memory."));
	}

	// --- Group E: PromptUtil thread-local setters ---

	@Test
	public void testPromptUtilMemoryDecisionsThreadLocal() {
		PromptUtil.setMemoryDecisions("Decided to go north; Chose diplomacy over combat");
	}

	@Test
	public void testPromptUtilMemoryEmotionsThreadLocal() {
		PromptUtil.setMemoryEmotions("Grief over father's death; Joy at reunion");
	}

	// --- Group F: New chatConfig fields ---

	@Test
	public void testChatConfigAnalyzeTimeoutField() {
		try {
			BaseRecord cfg = RecordFactory.newInstance("olio.llm.chatConfig");
			assertNotNull("chatConfig should instantiate", cfg);
			assertTrue("chatConfig should have analyzeTimeout field", cfg.hasField("analyzeTimeout"));

			int defaultVal = cfg.get("analyzeTimeout");
			assertEquals("Default analyzeTimeout should be 120", 120, defaultVal);

			cfg.set("analyzeTimeout", 300);
			assertEquals("Should accept new value", 300, (int) cfg.get("analyzeTimeout"));
		} catch (Exception e) {
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testChatConfigMemoryExtractionPromptField() {
		try {
			BaseRecord cfg = RecordFactory.newInstance("olio.llm.chatConfig");
			assertNotNull("chatConfig should instantiate", cfg);
			assertTrue("chatConfig should have memoryExtractionPrompt field", cfg.hasField("memoryExtractionPrompt"));

			String defaultVal = cfg.get("memoryExtractionPrompt");
			assertNull("Default memoryExtractionPrompt should be null", defaultVal);

			cfg.set("memoryExtractionPrompt", "customExtraction");
			assertEquals("Should accept new value", "customExtraction", cfg.get("memoryExtractionPrompt"));
		} catch (Exception e) {
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group G: Extraction with new memory types ---

	@Test
	public void testExtractMemoriesWithNewTypes() {
		try {
			String convId = "p14-newtypes-" + UUID.randomUUID().toString();
			String llmResponse = "[\n" +
				"  {\"content\": \"Elena revealed she is afraid of water.\", \"summary\": \"Elena fears water\", \"memoryType\": \"FACT\", \"importance\": 8},\n" +
				"  {\"content\": \"Trust between Elena and Marcus deepened.\", \"summary\": \"Deepened trust\", \"memoryType\": \"RELATIONSHIP\", \"importance\": 7},\n" +
				"  {\"content\": \"Elena broke down crying about her father.\", \"summary\": \"Elena cried about father\", \"memoryType\": \"EMOTION\", \"importance\": 6},\n" +
				"  {\"content\": \"Marcus decided to lead the expedition.\", \"summary\": \"Marcus leads expedition\", \"memoryType\": \"DECISION\", \"importance\": 7},\n" +
				"  {\"content\": \"They discovered a hidden cave.\", \"summary\": \"Hidden cave found\", \"memoryType\": \"DISCOVERY\", \"importance\": 5}\n" +
				"]";

			List<BaseRecord> extracted = MemoryUtil.extractMemoriesFromResponse(
				testUser, llmResponse, "am7://test/newtypes", convId);
			assertNotNull(extracted);
			assertEquals("Should extract 5 memories", 5, extracted.size());

			boolean hasFact = false, hasRelationship = false, hasEmotion = false;
			boolean hasDecision = false, hasDiscovery = false;
			for (BaseRecord mem : extracted) {
				String type = mem.get("memoryType").toString();
				switch (type) {
					case "FACT": hasFact = true; break;
					case "RELATIONSHIP": hasRelationship = true; break;
					case "EMOTION": hasEmotion = true; break;
					case "DECISION": hasDecision = true; break;
					case "DISCOVERY": hasDiscovery = true; break;
				}
			}
			assertTrue("Should have FACT memory", hasFact);
			assertTrue("Should have RELATIONSHIP memory", hasRelationship);
			assertTrue("Should have EMOTION memory", hasEmotion);
			assertTrue("Should have DECISION memory", hasDecision);
			assertTrue("Should have DISCOVERY memory", hasDiscovery);

			logger.info("New type extraction test passed: all 5 types extracted");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group H: Person pair search ---

	@Test
	public void testSearchMemoriesByPersonPairWithNewTypes() {
		try {
			String convId = "p14-pairsearch-" + UUID.randomUUID().toString();
			BaseRecord person1 = createTestPerson("PairSearch1-" + UUID.randomUUID());
			BaseRecord person2 = createTestPerson("PairSearch2-" + UUID.randomUUID());
			long pid1 = getPersonId(person1);
			long pid2 = getPersonId(person2);

			MemoryUtil.createMemory(testUser, "Fact about the pair.",
				"Pair fact", MemoryTypeEnumType.FACT, 8,
				null, convId, pid1, pid2, null);
			MemoryUtil.createMemory(testUser, "Relationship development.",
				"Pair rel", MemoryTypeEnumType.RELATIONSHIP, 7,
				null, convId, pid1, pid2, null);
			MemoryUtil.createMemory(testUser, "Emotional moment.",
				"Pair emo", MemoryTypeEnumType.EMOTION, 6,
				null, convId, pid1, pid2, null);

			// Search with reversed IDs — should still find due to canonicalization
			List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonPair(testUser, pid2, pid1, 10);
			assertNotNull(results);
			assertEquals("Should find 3 memories for pair (reversed)", 3, results.size());

			logger.info("Person pair search with new types passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testSearchMemoriesByPersonAcrossPairs() {
		try {
			String convId = "p14-personsearch-" + UUID.randomUUID().toString();
			BaseRecord personA = createTestPerson("CrossPairA-" + UUID.randomUUID());
			BaseRecord personB = createTestPerson("CrossPairB-" + UUID.randomUUID());
			BaseRecord personC = createTestPerson("CrossPairC-" + UUID.randomUUID());
			long idA = getPersonId(personA);
			long idB = getPersonId(personB);
			long idC = getPersonId(personC);

			// personA with personB
			MemoryUtil.createMemory(testUser, "Memory of A and B interaction.",
				"A-B interaction", MemoryTypeEnumType.FACT, 8,
				null, convId, idA, idB, null);

			// personA with personC
			MemoryUtil.createMemory(testUser, "Memory of A and C interaction.",
				"A-C interaction", MemoryTypeEnumType.RELATIONSHIP, 7,
				null, convId, idA, idC, null);

			// Search for all personA memories
			List<BaseRecord> results = MemoryUtil.searchMemoriesByPerson(testUser, idA, 10);
			assertNotNull(results);
			assertTrue("Should find at least 2 memories for personA", results.size() >= 2);

			logger.info("Cross-pair person search passed: " + results.size() + " results");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}
}
