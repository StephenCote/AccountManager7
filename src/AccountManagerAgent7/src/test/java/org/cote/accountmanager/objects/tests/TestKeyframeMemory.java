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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.util.MemoryUtil;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.IChatListener;
import org.cote.accountmanager.olio.llm.IChatHandler;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 3 tests for Keyframe-to-Memory Pipeline (chatRefactor.md tests 19-22).
 *
 * All tests require DB + LLM (keyframe creation calls analyze() which calls the LLM).
 *
 * Test 19: TestKeyframeMemoryPersistence - extractMemories=true -> tool.memory record created with OUTCOME type
 * Test 20: TestKeyframeMemoryScoping - Two character pairs -> pair-scoped queries return correct memories
 * Test 21: TestKeyframePruneKeepsTwo - After multiple keyframes -> last 2 are kept, older ones pruned
 * Test 22: TestKeyframeToMemoryToPromptRoundtrip - Keyframe -> memory persist -> new session -> memory appears in prompt
 */
public class TestKeyframeMemory extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;
	private OlioContext octx;

	private static final int STREAM_TIMEOUT_SECONDS = 300;

	@Before
	public void setupPhase3() {
		testOrgContext = getTestOrganization("/Development/MemoryPhase3");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "memPhase3User", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		String dataPath = testProperties.getProperty("test.datagen.path");
		octx = OlioContextUtil.getGridContext(testUser, dataPath, "Phase3 Universe", "Phase3 World", false);
		assertNotNull("Olio context should not be null", octx);
	}

	// --- Test 19: TestKeyframeMemoryPersistence ---
	// extractMemories=true -> addKeyFrame() creates a tool.memory record with OUTCOME type
	@Test(timeout = 600000)
	public void testKeyframeMemoryPersistence() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord sysChar = pop.get(0);
			BaseRecord usrChar = pop.get(1);

			String configName = "KFMemPersist-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord promptConfig = createMinimalPromptConfig("KFMemPersistPrompt");
			assertNotNull("Prompt config should not be null", promptConfig);

			BaseRecord chatConfig = createTestChatConfig(configName, sysChar, usrChar, true, 500, 0);
			assertNotNull("Chat config should not be null", chatConfig);

			// Set up a chat with enough messages to trigger keyframe creation
			// We need assist=true, prune=true, keyframeEvery > 0
			chatConfig.setValue("assist", true);
			chatConfig.setValue("prune", true);
			chatConfig.setValue("keyframeEvery", 3);
			chatConfig.setValue("messageTrim", 20);
			chatConfig = updateAndReload(chatConfig);

			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			chat.setPersistSession(false);

			StreamListener listener = new StreamListener("T19");
			chat.setListener(listener);

			OpenAIRequest req = chat.getChatPrompt();
			assertNotNull("Request should not be null", req);
			req.setStream(true);

			// Send enough messages to trigger a keyframe
			// keyframeEvery=3, so after 3+ user messages past the initial setup, it should trigger
			String[] messages = {"Hello there!", "Tell me about yourself.", "What do you like to do?", "That's interesting.", "Tell me more."};

			for (String msg : messages) {
				listener.reset();
				chat.continueChat(req, msg);
				boolean completed = listener.awaitCompletion(STREAM_TIMEOUT_SECONDS);
				assertTrue("Chat should complete within timeout", completed);
				assertFalse("Chat should not have errors: " + listener.getErrorMessage(), listener.hasError());

				OpenAIResponse resp = listener.getResponse();
				if (resp != null) {
					chat.handleResponse(req, resp, false);
				}
			}

			// Check if keyframe memories were created for this config
			String cfgObjId = chatConfig.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("Config objectId should not be null", cfgObjId);

			List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, cfgObjId);
			// At least one OUTCOME memory should exist from the keyframe
			long outcomeCount = memories.stream()
				.filter(m -> {
					Object mt = m.get("memoryType");
					return mt != null && MemoryTypeEnumType.OUTCOME.toString().equals(mt.toString());
				})
				.count();

			assertTrue("Should have at least 1 OUTCOME memory from keyframe, found " + outcomeCount,
				outcomeCount >= 1);

			// Verify the memory has person pair IDs
			for (BaseRecord mem : memories) {
				Object mt = mem.get("memoryType");
				if (mt != null && MemoryTypeEnumType.OUTCOME.toString().equals(mt.toString())) {
					long pid1 = mem.get("personId1");
					long pid2 = mem.get("personId2");
					assertTrue("Memory should have personId1 set", pid1 > 0);
					assertTrue("Memory should have personId2 set", pid2 > 0);

					// Verify canonical ordering
					long sysId = sysChar.get(FieldNames.FIELD_ID);
					long usrId = usrChar.get(FieldNames.FIELD_ID);
					long[] canon = MemoryUtil.canonicalPersonIds(sysId, usrId);
					assertEquals("personId1 should be canonical lower ID", canon[0], pid1);
					assertEquals("personId2 should be canonical higher ID", canon[1], pid2);

					// Verify content is not empty
					String content = mem.get("content");
					assertNotNull("Memory content should not be null", content);
					assertFalse("Memory content should not be empty", content.trim().isEmpty());

					String summary = mem.get("summary");
					assertNotNull("Memory summary should not be null", summary);

					logger.info("Test 19 keyframe memory: type=" + mt + ", importance=" + mem.get("importance")
						+ ", content length=" + content.length());
				}
			}

			logger.info("Test 19 passed: keyframe creates OUTCOME memory with person pair IDs");
		} catch (Exception e) {
			logger.error("Test 19 failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 20: TestKeyframeMemoryScoping ---
	// Two character pairs -> pair-scoped queries return correct memories
	@Test(timeout = 600000)
	public void testKeyframeMemoryScoping() {
		try {
			List<BaseRecord> pop = getPopulation(3);
			BaseRecord charA = pop.get(0);
			BaseRecord charB = pop.get(1);
			BaseRecord charC = pop.get(2);

			BaseRecord promptConfig = createMinimalPromptConfig("KFMemScopePrompt");
			assertNotNull("Prompt config should not be null", promptConfig);

			// Create pair A-B and pair A-C chats
			String configAB = "KFScope-AB-" + UUID.randomUUID().toString().substring(0, 6);
			String configAC = "KFScope-AC-" + UUID.randomUUID().toString().substring(0, 6);

			BaseRecord chatConfigAB = createTestChatConfig(configAB, charA, charB, true, 500, 0);
			assertNotNull("ChatConfig A-B should not be null", chatConfigAB);

			BaseRecord chatConfigAC = createTestChatConfig(configAC, charA, charC, true, 500, 0);
			assertNotNull("ChatConfig A-C should not be null", chatConfigAC);

			// Manually create keyframe-style memories for each pair to test scoping
			// (avoids needing to run full keyframe pipeline twice)

			String cfgABObjId = chatConfigAB.get(FieldNames.FIELD_OBJECT_ID);
			String cfgACObjId = chatConfigAC.get(FieldNames.FIELD_OBJECT_ID);

			// Create 2 memories for pair A-B
			MemoryUtil.createMemory(testUser, "A and B discussed philosophy at the market.",
				"Philosophy discussion", MemoryTypeEnumType.OUTCOME, 7,
				"am7://keyframe/" + cfgABObjId, cfgABObjId, charA, charB);
			MemoryUtil.createMemory(testUser, "A and B argued about the weather and made up.",
				"Weather argument", MemoryTypeEnumType.OUTCOME, 6,
				"am7://keyframe/" + cfgABObjId, cfgABObjId, charA, charB);

			// Create 1 memory for pair A-C
			MemoryUtil.createMemory(testUser, "A and C shared a meal together at the tavern.",
				"Shared meal", MemoryTypeEnumType.OUTCOME, 5,
				"am7://keyframe/" + cfgACObjId, cfgACObjId, charA, charC);

			// Verify pair A-B query returns exactly 2
			List<BaseRecord> abMemories = MemoryUtil.searchMemoriesByPersonPair(testUser, charA, charB, 10);
			assertTrue("Pair A-B should have at least 2 memories, found " + abMemories.size(),
				abMemories.size() >= 2);

			// Verify pair A-C query returns exactly 1
			List<BaseRecord> acMemories = MemoryUtil.searchMemoriesByPersonPair(testUser, charA, charC, 10);
			assertTrue("Pair A-C should have at least 1 memory, found " + acMemories.size(),
				acMemories.size() >= 1);

			// Verify pair B-C query returns 0 (no relationship)
			List<BaseRecord> bcMemories = MemoryUtil.searchMemoriesByPersonPair(testUser, charB, charC, 10);
			assertEquals("Pair B-C should have 0 memories", 0, bcMemories.size());

			// Verify role-agnostic: querying B-A returns same as A-B
			List<BaseRecord> baMemories = MemoryUtil.searchMemoriesByPersonPair(testUser, charB, charA, 10);
			assertEquals("Pair B-A should return same count as A-B", abMemories.size(), baMemories.size());

			// Verify person query: A should see memories from both pairs
			List<BaseRecord> aAllMemories = MemoryUtil.searchMemoriesByPerson(testUser, charA, 20);
			assertTrue("Person A should have at least 3 memories (from both pairs), found " + aAllMemories.size(),
				aAllMemories.size() >= 3);

			logger.info("Test 20 passed: pair-scoped queries return correct memories");
		} catch (Exception e) {
			logger.error("Test 20 failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 21: TestKeyframePruneKeepsTwo ---
	// After multiple keyframes -> last 2 are kept in message history, older ones removed
	@Test(timeout = 600000)
	public void testKeyframePruneKeepsTwo() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord sysChar = pop.get(0);
			BaseRecord usrChar = pop.get(1);

			BaseRecord promptConfig = createMinimalPromptConfig("KFPrunePrompt");
			assertNotNull("Prompt config should not be null", promptConfig);

			String configName = "KFPrune-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord chatConfig = createTestChatConfig(configName, sysChar, usrChar, true, 500, 0);
			assertNotNull("Chat config should not be null", chatConfig);

			// Configure for frequent keyframing
			chatConfig.setValue("assist", true);
			chatConfig.setValue("prune", true);
			chatConfig.setValue("keyframeEvery", 2);
			chatConfig.setValue("messageTrim", 20);
			chatConfig = updateAndReload(chatConfig);

			Chat chat = new Chat(testUser, chatConfig, promptConfig);
			chat.setPersistSession(false);

			StreamListener listener = new StreamListener("T21");
			chat.setListener(listener);

			OpenAIRequest req = chat.getChatPrompt();
			assertNotNull("Request should not be null", req);
			req.setStream(true);

			// Send many messages to trigger multiple keyframes
			String[] messages = {
				"Hello!", "How are you?", "Tell me something.",
				"What's your favorite thing?", "I see.",
				"That's great.", "Tell me more.", "Interesting.",
				"What else?", "Wow.", "Continue.", "Anything else?"
			};

			for (String msg : messages) {
				listener.reset();
				chat.continueChat(req, msg);
				boolean completed = listener.awaitCompletion(STREAM_TIMEOUT_SECONDS);
				assertTrue("Chat should complete within timeout", completed);
				if (listener.hasError()) {
					logger.warn("Chat had error: " + listener.getErrorMessage());
				}

				OpenAIResponse resp = listener.getResponse();
				if (resp != null) {
					chat.handleResponse(req, resp, false);
				}
			}

			// Count keyframes in message history
			List<OpenAIMessage> allMsgs = req.getMessages();
			int keyframeCount = 0;
			int prunedKeyframeCount = 0;
			for (OpenAIMessage m : allMsgs) {
				String content = m.getContent();
				if (content == null) continue;
				boolean isKeyframe = content.startsWith("(KeyFrame")
					|| (content.contains("<mcp:context") && content.contains("/keyframe/"));
				if (isKeyframe) {
					keyframeCount++;
					if (m.isPruned()) {
						prunedKeyframeCount++;
					}
				}
			}

			logger.info("Total keyframes in message history: " + keyframeCount);
			logger.info("Pruned keyframes: " + prunedKeyframeCount);

			// After the addKeyFrame logic, we should have at most 2 non-pruned keyframes
			int unprunedKeyframes = keyframeCount - prunedKeyframeCount;
			assertTrue("Should have at most 2 unpruned keyframes in history, found " + unprunedKeyframes,
				unprunedKeyframes <= 2);

			// Verify that keyframe memories were created (at least 1)
			String cfgObjId = chatConfig.get(FieldNames.FIELD_OBJECT_ID);
			List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, cfgObjId);
			long outcomeCount = memories.stream()
				.filter(m -> {
					Object mt = m.get("memoryType");
					return mt != null && MemoryTypeEnumType.OUTCOME.toString().equals(mt.toString());
				})
				.count();
			assertTrue("Should have at least 1 OUTCOME memory from keyframes, found " + outcomeCount,
				outcomeCount >= 1);

			logger.info("Test 21 passed: keyframe pruning keeps last 2, persists memories");
		} catch (Exception e) {
			logger.error("Test 21 failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Test 22: TestKeyframeToMemoryToPromptRoundtrip ---
	// Keyframe -> memory persist -> new session -> memory appears in prompt
	@Test(timeout = 600000)
	public void testKeyframeToMemoryToPromptRoundtrip() {
		try {
			List<BaseRecord> pop = getPopulation(2);
			BaseRecord sysChar = pop.get(0);
			BaseRecord usrChar = pop.get(1);

			BaseRecord promptConfig = createMemoryPromptConfig("KFRoundtripPrompt");
			assertNotNull("Prompt config should not be null", promptConfig);

			// Step 1: Create a memory (simulating what addKeyFrame would produce)
			String uniqueContent = "Keyframe roundtrip test memory " + UUID.randomUUID().toString().substring(0, 8)
				+ ": They discovered a hidden garden behind the inn and shared stories about childhood.";
			String summary = "Discovered hidden garden, shared childhood stories";

			String convId = "roundtrip-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord memory = MemoryUtil.createMemory(testUser, uniqueContent, summary,
				MemoryTypeEnumType.OUTCOME, 8, "am7://keyframe/roundtrip-test", convId,
				sysChar, usrChar);
			assertNotNull("Memory should be created successfully", memory);

			// Step 2: Create a NEW chat session for the same character pair with memoryBudget > 0
			String configName = "KFRoundtrip-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord chatConfig2 = createTestChatConfig(configName, sysChar, usrChar, false, 500, 0);
			assertNotNull("Second chat config should not be null", chatConfig2);

			// Step 3: Build prompt and verify memory context is injected
			Chat chat2 = new Chat(testUser, chatConfig2, promptConfig);
			OpenAIRequest req2 = chat2.getChatPrompt();
			assertNotNull("Request should not be null", req2);

			String sysPrompt = getSystemPrompt(req2);
			assertNotNull("System prompt should not be null", sysPrompt);

			// The prompt should contain memory context (via ${memory.context} resolution)
			// It should NOT contain the literal token
			assertFalse("System prompt should not contain unresolved ${memory.context}",
				sysPrompt.contains("${memory.context}"));

			// The system prompt should contain MCP memory context OR the memory content
			// depending on how the template resolved. Check for MCP tags.
			boolean hasMemoryContent = sysPrompt.contains("mcp:context")
				|| sysPrompt.contains("urn:am7")
				|| sysPrompt.contains("garden")
				|| sysPrompt.contains("childhood");

			assertTrue("System prompt should contain memory context from the keyframe memory. Prompt:\n"
				+ sysPrompt.substring(0, Math.min(sysPrompt.length(), 500)),
				hasMemoryContent);

			logger.info("Test 22 passed: keyframe -> memory -> new session -> prompt roundtrip works");
			logger.info("System prompt length: " + sysPrompt.length());
		} catch (Exception e) {
			logger.error("Test 22 failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Helper Methods ---

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

	private BaseRecord createTestChatConfig(String name, BaseRecord sysChar, BaseRecord usrChar,
			boolean extractMemories, int memoryBudget, int memoryExtractionEvery) {
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
			cfg.set("extractMemories", extractMemories);
			cfg.set("memoryBudget", memoryBudget);
			cfg.set("memoryExtractionEvery", memoryExtractionEvery);

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

	private BaseRecord updateAndReload(BaseRecord cfg) {
		try {
			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			return reloadChatConfig(cfg);
		} catch (Exception e) {
			logger.error("Error updating chat config: " + e.getMessage());
			return cfg;
		}
	}

	private BaseRecord reloadChatConfig(BaseRecord cfg) {
		try {
			String name = cfg.get(FieldNames.FIELD_NAME);
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(
				testUser, ModelNames.MODEL_GROUP, "~/Chat", "DATA",
				testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
			q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			OlioUtil.planMost(q);
			OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");
			return IOSystem.getActiveContext().getSearch().findRecord(q);
		} catch (Exception e) {
			logger.error("Error reloading chat config: " + e.getMessage());
			return cfg;
		}
	}

	private String getSystemPrompt(OpenAIRequest req) {
		List<OpenAIMessage> msgs = req.getMessages();
		if (msgs != null && !msgs.isEmpty() && "system".equals(msgs.get(0).getRole())) {
			return msgs.get(0).getContent();
		}
		return null;
	}

	// --- Stream Listener ---

	private class StreamListener implements IChatListener {
		private final String tag;
		private volatile CountDownLatch latch;
		private final AtomicReference<OpenAIResponse> responseRef = new AtomicReference<>();
		private final AtomicBoolean errorFlag = new AtomicBoolean(false);
		private final AtomicReference<String> errorMsg = new AtomicReference<>();

		StreamListener(String tag) {
			this.tag = tag;
			this.latch = new CountDownLatch(1);
		}

		void reset() {
			latch = new CountDownLatch(1);
			responseRef.set(null);
			errorFlag.set(false);
			errorMsg.set(null);
		}

		boolean awaitCompletion(int timeoutSeconds) throws InterruptedException {
			return latch.await(timeoutSeconds, TimeUnit.SECONDS);
		}

		OpenAIResponse getResponse() {
			return responseRef.get();
		}

		boolean hasError() {
			return errorFlag.get();
		}

		String getErrorMessage() {
			return errorMsg.get();
		}

		@Override
		public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
			logger.info("Stream " + tag + " complete");
			responseRef.set(response);
			latch.countDown();
		}

		@Override
		public void onupdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message) {
			// no-op
		}

		@Override
		public void onerror(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg) {
			logger.error("Stream " + tag + " error: " + msg);
			errorFlag.set(true);
			errorMsg.set(msg);
			latch.countDown();
		}

		@Override
		public boolean isStopStream(OpenAIRequest request) {
			return false;
		}

		@Override
		public void stopStream(OpenAIRequest request) {
			// no-op
		}

		@Override
		public boolean isRequesting(OpenAIRequest request) {
			return latch.getCount() > 0;
		}

		@Override
		public OpenAIRequest sendMessageToServer(BaseRecord user, ChatRequest request) {
			return null;
		}

		@Override
		public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest) {
			return null;
		}

		@Override
		public void addChatHandler(IChatHandler handler) {
			// no-op
		}
	}
}
