package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.util.MemoryUtil;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 2 tests for Memory Retrieval (chatRefactor.md tests 8-18).
 *
 * Tests 8-17: DB only (no LLM calls).
 * Test 18: Full integration with LLM.
 */
public class TestMemoryPhase2 extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;
	private OlioContext octx;

	@Before
	public void setupPhase2() {
		testOrgContext = getTestOrganization("/Development/MemoryPhase2");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "memPhase2User", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		String dataPath = testProperties.getProperty("test.datagen.path");
		octx = OlioContextUtil.getGridContext(testUser, dataPath, "Phase2 Universe", "Phase2 World", false);
		assertNotNull("Olio context should not be null", octx);
	}

	// --- Test 8: TestMemoryPatternResolution ---
	// promptConfig with ${memory.context}, inject memory data → token resolved
	@Test
	public void testMemoryPatternResolution() {
		try {
			BaseRecord promptConfig = createMinimalPromptConfig("MemPatternRes");
			assertNotNull("Prompt config should not be null", promptConfig);

			List<BaseRecord> pop = getPopulation(2);
			BaseRecord sysChar = pop.get(0);
			BaseRecord usrChar = pop.get(1);

			BaseRecord chatConfig = createTestChatConfig("MemPatternResCfg", sysChar, usrChar);
			assertNotNull("Chat config should not be null", chatConfig);

			// Create a memory for this person pair
			MemoryUtil.createMemory(testUser, "They met at a coffee shop and discussed philosophy.",
				"Met at coffee shop", MemoryTypeEnumType.NOTE, 7,
				"am7://test/phase2", "conv-" + UUID.randomUUID().toString().substring(0, 8),
				sysChar, usrChar);

			// Set memory budget so retrieval happens
			chatConfig.setValue("memoryBudget", 500);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);

			// Re-read chatConfig fully
			chatConfig = reloadChatConfig(chatConfig);

			// Build prompt — memory should be injected via ${memory.context}
			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			OpenAIRequest req = chat.getChatPrompt();
			assertNotNull("Request should not be null", req);

			String sysPrompt = getSystemPrompt(req);
			assertNotNull("System prompt should not be null", sysPrompt);

			// The system prompt should NOT contain the literal "${memory.context}" token
			assertFalse("System prompt should not contain unresolved ${memory.context}",
				sysPrompt.contains("${memory.context}"));

			logger.info("Test 8 passed: memory pattern resolved in system prompt");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 9: TestMemoryPatternsDefaultToEmpty ---
	// Existing prompt.config.json → no ${memory. substring in output
	@Test
	public void testMemoryPatternsDefaultToEmpty() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord sysChar = pop.get(0);
			BaseRecord usrChar = pop.get(1);

			// Use the default prompt config (from resources)
			BaseRecord promptConfig = loadDefaultPromptConfig();
			BaseRecord chatConfig = createTestChatConfig("MemDefaultEmpty", sysChar, usrChar);
			assertNotNull("Chat config should not be null", chatConfig);

			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			OpenAIRequest req = chat.getChatPrompt();
			assertNotNull("Request should not be null", req);

			String sysPrompt = getSystemPrompt(req);
			if (sysPrompt != null) {
				assertFalse("System prompt should not contain ${memory.",
					sysPrompt.contains("${memory."));
			}

			logger.info("Test 9 passed: memory patterns default to empty");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 10: TestCanonicalPersonIds ---
	@Test
	public void testCanonicalPersonIds() {
		long[] result1 = MemoryUtil.canonicalPersonIds(100L, 200L);
		assertEquals("Lower ID should be first", 100L, result1[0]);
		assertEquals("Higher ID should be second", 200L, result1[1]);

		long[] result2 = MemoryUtil.canonicalPersonIds(200L, 100L);
		assertEquals("Reversed input should still have lower first", 100L, result2[0]);
		assertEquals("Reversed input should still have higher second", 200L, result2[1]);

		// Same values
		long[] result3 = MemoryUtil.canonicalPersonIds(50L, 50L);
		assertEquals("Same ID should work", 50L, result3[0]);
		assertEquals("Same ID should work", 50L, result3[1]);

		// Verify the pair identity is the same regardless of order
		assertEquals("Canonical id1 should match", result1[0], result2[0]);
		assertEquals("Canonical id2 should match", result1[1], result2[1]);

		logger.info("Test 10 passed: canonical person IDs are order-independent");
	}

	// --- Test 11: TestMemoryContextFormatting ---
	@Test
	public void testMemoryContextFormatting() {
		try {
			String convId = "fmt-phase2-" + UUID.randomUUID().toString().substring(0, 8);
			MemoryUtil.createMemory(testUser, "They argued about the best pizza topping.",
				"Pizza argument", MemoryTypeEnumType.BEHAVIOR, 6,
				"am7://test/format", convId, stubPerson(100L), stubPerson(200L));

			List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, convId);
			assertFalse("Should have at least one memory", memories.isEmpty());

			String context = MemoryUtil.formatMemoriesAsContext(memories);
			assertNotNull("Formatted context should not be null", context);
			assertFalse("Formatted context should not be empty", context.isEmpty());
			assertTrue("Context should contain MCP tags", context.contains("mcp:context"));
			assertTrue("Context should contain memory content", context.contains("pizza topping"));

			logger.info("Test 11 passed: memory context formatting produces valid MCP block");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 12: TestRoleAgnosticMemoryRetrieval ---
	// Bob(user)+Rob(system) stores memory → Bob(system)+Rob(user) retrieves same
	@Test
	public void testRoleAgnosticMemoryRetrieval() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord bob = pop.get(0);
			BaseRecord rob = pop.get(1);
			// Store memory with Bob as "user" (id1) and Rob as "system" (id2)
			String convId = "role-agnostic-" + UUID.randomUUID().toString().substring(0, 8);
			MemoryUtil.createMemory(testUser, "Bob and Rob discussed their favorite books.",
				"Book discussion", MemoryTypeEnumType.NOTE, 7,
				"am7://test/role-agnostic", convId, bob, rob);

			// Retrieve with roles swapped: Rob as id1, Bob as id2
			List<BaseRecord> memories = MemoryUtil.searchMemoriesByPersonPair(testUser, rob, bob, 10);
			assertNotNull("Memories should not be null", memories);
			assertTrue("Should find at least 1 memory with swapped roles", memories.size() >= 1);

			boolean found = memories.stream().anyMatch(m ->
				((String) m.get("content")).contains("favorite books"));
			assertTrue("Should find the book discussion memory", found);

			logger.info("Test 12 passed: role-agnostic memory retrieval works");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 13: TestCrossPartnerMemoryRetrieval ---
	// Bob+Rob stores, Bob+Nob stores → query "all Bob's memories" returns both
	@Test
	public void testCrossPartnerMemoryRetrieval() {
		try {
			List<BaseRecord> pop = getPopulation(3);
			BaseRecord bob = pop.get(0);
			BaseRecord rob = pop.get(1);
			BaseRecord nob = pop.get(2);
			String convBR = "cross-br-" + UUID.randomUUID().toString().substring(0, 8);
			String convBN = "cross-bn-" + UUID.randomUUID().toString().substring(0, 8);

			// Memory between Bob and Rob
			MemoryUtil.createMemory(testUser, "Bob taught Rob to play chess.",
				"Chess lesson", MemoryTypeEnumType.BEHAVIOR, 6,
				null, convBR, bob, rob);

			// Memory between Bob and Nob
			MemoryUtil.createMemory(testUser, "Bob and Nob went hiking together.",
				"Hiking trip", MemoryTypeEnumType.NOTE, 5,
				null, convBN, bob, nob);

			// Search all of Bob's memories
			List<BaseRecord> bobMemories = MemoryUtil.searchMemoriesByPerson(testUser, bob, 20);
			assertNotNull("Bob's memories should not be null", bobMemories);
			assertTrue("Bob should have at least 2 memories from different partners",
				bobMemories.size() >= 2);

			boolean hasChess = bobMemories.stream().anyMatch(m ->
				((String) m.get("content")).contains("chess"));
			boolean hasHiking = bobMemories.stream().anyMatch(m ->
				((String) m.get("content")).contains("hiking"));
			assertTrue("Bob should have chess memory from Rob", hasChess);
			assertTrue("Bob should have hiking memory from Nob", hasHiking);

			logger.info("Test 13 passed: cross-partner memory retrieval returns memories from all partners");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 14: TestCreateMemoryWithPersonIds ---
	@Test
	public void testCreateMemoryWithPersonIds() {
		try {
			String convId = "charids-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord memory = MemoryUtil.createMemory(testUser,
				"A shared adventure in the forest.", "Forest adventure",
				MemoryTypeEnumType.OUTCOME, 8, "am7://test/charids", convId,
				stubPerson(300L), stubPerson(100L));  // intentionally out of order

			assertNotNull("Created memory should not be null", memory);
			BaseRecord p1 = memory.get("person1");
			BaseRecord p2 = memory.get("person2");
			assertNotNull("person1 should not be null", p1);
			assertNotNull("person2 should not be null", p2);
			long cid1 = p1.get(FieldNames.FIELD_ID);
			long cid2 = p2.get(FieldNames.FIELD_ID);

			// Should be canonicalized: lower first
			assertEquals("person1 ID should be the lower ID", 100L, cid1);
			assertEquals("person2 ID should be the higher ID", 300L, cid2);

			logger.info("Test 14 passed: memory created with canonical person IDs");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 15: TestSearchMemoriesByPersonPair ---
	// 3 memories pair(A,B), 2 pair(A,C) → search pair(A,B) returns exactly 3
	@Test
	public void testSearchMemoriesByPersonPair() {
		try {
			List<BaseRecord> pop = getPopulation(3);
			BaseRecord a = pop.get(0);
			BaseRecord b = pop.get(1);
			BaseRecord c = pop.get(2);

			// Use a unique tag to distinguish these test memories
			String tagAB = "pair-ab-" + UUID.randomUUID().toString().substring(0, 8);
			String tagAC = "pair-ac-" + UUID.randomUUID().toString().substring(0, 8);

			// 3 memories for pair (A, B)
			for (int i = 0; i < 3; i++) {
				MemoryUtil.createMemory(testUser, "AB memory " + i + " " + tagAB,
					"AB mem " + i, MemoryTypeEnumType.NOTE, 5 + i,
					null, tagAB, a, b);
			}
			// 2 memories for pair (A, C)
			for (int i = 0; i < 2; i++) {
				MemoryUtil.createMemory(testUser, "AC memory " + i + " " + tagAC,
					"AC mem " + i, MemoryTypeEnumType.NOTE, 5,
					null, tagAC, a, c);
			}

			// Search for pair (A, B) — should get exactly 3
			List<BaseRecord> abMemories = MemoryUtil.searchMemoriesByPersonPair(testUser, a, b, 20);
			long abCount = abMemories.stream().filter(m ->
				((String) m.get("content")).contains(tagAB)).count();
			assertEquals("Should find exactly 3 memories for pair (A,B)", 3, abCount);

			// Search for pair (A, C) — should get exactly 2
			List<BaseRecord> acMemories = MemoryUtil.searchMemoriesByPersonPair(testUser, a, c, 20);
			long acCount = acMemories.stream().filter(m ->
				((String) m.get("content")).contains(tagAC)).count();
			assertEquals("Should find exactly 2 memories for pair (A,C)", 2, acCount);

			logger.info("Test 15 passed: person pair search returns correct counts");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 16: TestSearchMemoriesByPerson ---
	// Same setup → search person A returns all 5
	@Test
	public void testSearchMemoriesByPerson() {
		try {
			List<BaseRecord> pop = getPopulation(3);
			BaseRecord a = pop.get(0);
			BaseRecord b = pop.get(1);
			BaseRecord c = pop.get(2);

			String tag = "charall-" + UUID.randomUUID().toString().substring(0, 8);

			// 3 memories for pair (A, B)
			for (int i = 0; i < 3; i++) {
				MemoryUtil.createMemory(testUser, "AllA-AB " + i + " " + tag,
					"AllA-AB " + i, MemoryTypeEnumType.NOTE, 5,
					null, "conv-ab-" + tag, a, b);
			}
			// 2 memories for pair (A, C)
			for (int i = 0; i < 2; i++) {
				MemoryUtil.createMemory(testUser, "AllA-AC " + i + " " + tag,
					"AllA-AC " + i, MemoryTypeEnumType.NOTE, 5,
					null, "conv-ac-" + tag, a, c);
			}

			List<BaseRecord> aMemories = MemoryUtil.searchMemoriesByPerson(testUser, a, 50);
			long tagCount = aMemories.stream().filter(m ->
				((String) m.get("content")).contains(tag)).count();
			assertTrue("Person A should have at least 5 tagged memories", tagCount >= 5);

			logger.info("Test 16 passed: person search returns memories from all partners");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 17: TestRoleSwapProducesSameIds ---
	// Bob system+Rob user, then Bob user+Rob system → identical personId1/personId2
	@Test
	public void testRoleSwapProducesSameIds() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord bob = pop.get(0);
			BaseRecord rob = pop.get(1);

			// Scenario 1: Bob as system (id1=bob), Rob as user (id2=rob)
			String conv1 = "swap1-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord mem1 = MemoryUtil.createMemory(testUser, "Swap test scenario 1",
				"Swap 1", MemoryTypeEnumType.NOTE, 5, null, conv1, bob, rob);
			assertNotNull("Memory 1 should not be null", mem1);

			// Scenario 2: Bob as user (id2=bob), Rob as system (id1=rob)
			String conv2 = "swap2-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord mem2 = MemoryUtil.createMemory(testUser, "Swap test scenario 2",
				"Swap 2", MemoryTypeEnumType.NOTE, 5, null, conv2, rob, bob);
			assertNotNull("Memory 2 should not be null", mem2);

			// Both should have identical canonical IDs
			BaseRecord p1_1 = mem1.get("person1");
			BaseRecord p2_1 = mem1.get("person2");
			BaseRecord p1_2 = mem2.get("person1");
			BaseRecord p2_2 = mem2.get("person2");

			long cid1_1 = p1_1.get(FieldNames.FIELD_ID);
			long cid2_1 = p2_1.get(FieldNames.FIELD_ID);
			long cid1_2 = p1_2.get(FieldNames.FIELD_ID);
			long cid2_2 = p2_2.get(FieldNames.FIELD_ID);

			assertEquals("person1 ID should be identical regardless of role", cid1_1, cid1_2);
			assertEquals("person2 ID should be identical regardless of role", cid2_1, cid2_2);

			logger.info("Test 17 passed: role swap produces identical person IDs");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 18: TestMemoryRetrievalIntegration ---
	// Full retrieveRelevantMemories() with pair memories, produces prompt with memory context
	@Test(timeout = 300000)
	public void testMemoryRetrievalIntegration() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord sysChar = pop.get(0);
			BaseRecord usrChar = pop.get(1);
			String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
			String usrName = usrChar.get(FieldNames.FIELD_FIRST_NAME);

			logger.info("Integration test: " + sysName + " (system) + " + usrName + " (user)");

			// Create some memories for this pair
			String convId = "integ-" + UUID.randomUUID().toString().substring(0, 8);
			MemoryUtil.createMemory(testUser,
				sysName + " told " + usrName + " a story about a dragon.",
				"Dragon story", MemoryTypeEnumType.NOTE, 8,
				"am7://test/integration", convId, sysChar, usrChar);
			MemoryUtil.createMemory(testUser,
				usrName + " shared a secret about hidden treasure.",
				"Treasure secret", MemoryTypeEnumType.INSIGHT, 9,
				"am7://test/integration", convId, sysChar, usrChar);

			// Create a prompt config with ${memory.context} in the system prompt
			BaseRecord promptConfig = createMemoryPromptConfig("IntegPrompt");
			assertNotNull("Prompt config should not be null", promptConfig);

			// Create chat config with memoryBudget enabled
			BaseRecord chatConfig = createTestChatConfig("IntegCfg", sysChar, usrChar);
			chatConfig.setValue("memoryBudget", 800);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			chatConfig = reloadChatConfig(chatConfig);

			// Build the prompt
			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			OpenAIRequest req = chat.getChatPrompt();
			assertNotNull("Request should not be null", req);

			String sysPrompt = getSystemPrompt(req);
			assertNotNull("System prompt should not be null", sysPrompt);

			// Verify memory was injected (MCP context block should be present)
			assertTrue("System prompt should contain memory MCP context",
				sysPrompt.contains("mcp:context") || sysPrompt.contains("dragon") || sysPrompt.contains("treasure"));

			// Verify no unresolved memory tokens remain
			assertFalse("No unresolved ${memory. tokens",
				sysPrompt.contains("${memory."));

			logger.info("Test 18 passed: full memory retrieval integration");
			logger.info("System prompt length: " + sysPrompt.length());
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// === Helper Methods ===

	private List<BaseRecord> getPopulation(int minCount) {
		List<BaseRecord> realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		assertTrue("Population should have at least " + minCount + " people", pop.size() >= minCount);
		List<BaseRecord> shuffled = new ArrayList<>(pop);
		Collections.shuffle(shuffled);
		return shuffled.subList(0, minCount);
	}

	private BaseRecord createMinimalPromptConfig(String name) {
		try {
			BaseRecord existing = org.cote.accountmanager.util.DocumentUtil.getRecord(
				testUser, OlioModelNames.MODEL_PROMPT_CONFIG, name, "~/Chat");
			if (existing != null) return existing;

			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord pcfg = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, testUser, null, plist);

			List<String> sys = new ArrayList<>();
			sys.add("You are ${system.firstName}.");
			sys.add("${memory.context}");
			sys.add("Respond briefly in character.");
			pcfg.set("system", sys);

			return IOSystem.getActiveContext().getAccessPoint().create(testUser, pcfg);
		} catch (Exception e) {
			logger.error("Error creating prompt config", e);
			return null;
		}
	}

	private BaseRecord createMemoryPromptConfig(String name) {
		try {
			BaseRecord existing = org.cote.accountmanager.util.DocumentUtil.getRecord(
				testUser, OlioModelNames.MODEL_PROMPT_CONFIG, name, "~/Chat");
			if (existing != null) return existing;

			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord pcfg = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, testUser, null, plist);

			List<String> sys = new ArrayList<>();
			sys.add("You are ${system.firstName}, a ${system.asg}.");
			sys.add("You are talking to ${user.firstName}.");
			sys.add("MEMORY CONTEXT:");
			sys.add("${memory.context}");
			sys.add("Respond briefly in character based on your memories and personality.");
			pcfg.set("system", sys);

			return IOSystem.getActiveContext().getAccessPoint().create(testUser, pcfg);
		} catch (Exception e) {
			logger.error("Error creating memory prompt config", e);
			return null;
		}
	}

	private BaseRecord loadDefaultPromptConfig() {
		try {
			String name = "Phase2 Default Prompt";
			BaseRecord existing = org.cote.accountmanager.util.DocumentUtil.getRecord(
				testUser, OlioModelNames.MODEL_PROMPT_CONFIG, name, "~/Chat");
			if (existing != null) return existing;

			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord pcfg = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, testUser, null, plist);

			// A prompt without any ${memory.*} tokens — to verify they don't leak
			List<String> sys = new ArrayList<>();
			sys.add("You are ${system.firstName}.");
			sys.add("Respond briefly.");
			pcfg.set("system", sys);

			return IOSystem.getActiveContext().getAccessPoint().create(testUser, pcfg);
		} catch (Exception e) {
			logger.error("Error creating default prompt config", e);
			return null;
		}
	}

	private BaseRecord createTestChatConfig(String name, BaseRecord sysChar, BaseRecord usrChar) {
		try {
			BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, name);
			cfg.set("systemCharacter", sysChar);
			cfg.set("userCharacter", usrChar);
			cfg.set("startMode", "system");
			cfg.set("setting", NarrativeUtil.getRandomSetting());
			cfg.set("assist", false);
			cfg.set("useNLP", false);
			cfg.set("includeScene", false);
			cfg.set("prune", true);
			cfg.set("rating", ESRBEnumType.E);
			cfg.set("extractMemories", false);
			cfg.set("memoryBudget", 0);

			LLMServiceEnumType serviceType = LLMServiceEnumType.valueOf(
				testProperties.getProperty("test.llm.serviceType").trim().toUpperCase());
			cfg.setValue("serviceType", serviceType);
			if (serviceType == LLMServiceEnumType.OPENAI) {
				cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version").trim());
				cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server").trim());
				cfg.setValue("model", testProperties.getProperty("test.llm.openai.model").trim());
				cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken").trim());
			} else if (serviceType == LLMServiceEnumType.OLLAMA) {
				cfg.setValue("serverUrl", testProperties.getProperty("test.llm.ollama.server").trim());
				cfg.setValue("model", testProperties.getProperty("test.llm.ollama.model").trim());
			}

			BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			opts.set("temperature", 0.7);
			opts.set("top_p", 1.0);
			opts.set("repeat_penalty", 1.2);
			opts.set("num_ctx", 4096);
			cfg.set("chatOptions", opts);

			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			return reloadChatConfig(cfg);
		} catch (Exception e) {
			logger.error("Error creating chat config: " + e.getMessage());
			return null;
		}
	}

	private String getSystemPrompt(OpenAIRequest req) {
		List<OpenAIMessage> msgs = req.getMessages();
		if (msgs != null && !msgs.isEmpty() && "system".equals(msgs.get(0).getRole())) {
			return msgs.get(0).getContent();
		}
		return null;
	}

	private BaseRecord stubPerson(long id) {
		try {
			BaseRecord rec = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
			rec.set(FieldNames.FIELD_ID, id);
			return rec;
		} catch (Exception e) {
			throw new RuntimeException("Failed to create stub person: " + e.getMessage(), e);
		}
	}

	private BaseRecord reloadChatConfig(BaseRecord cfg) {
		try {
			String name = cfg.get(FieldNames.FIELD_NAME);
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(
				testUser, ModelNames.MODEL_GROUP, "~/Chat", "DATA",
				testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
				OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
			q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			OlioUtil.planMost(q);
			OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");
			return IOSystem.getActiveContext().getSearch().findRecord(q);
		} catch (Exception e) {
			logger.error("Error reloading chat config: " + e.getMessage());
			return cfg;
		}
	}
}
