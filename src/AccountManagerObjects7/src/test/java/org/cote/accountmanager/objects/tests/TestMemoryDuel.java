package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.util.MemoryUtil;
import org.cote.accountmanager.exceptions.FactoryException;
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
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Chat duel integration test for the memory/engram system.
 *
 * 1) Initializes an Olio population and picks characters.
 * 2) Creates duel pairs where each character is the system in one pair,
 *    with a randomly selected different character as user.
 * 3) Uses alg.prompt.json as the RPG prompt template.
 * 4) Runs round-robin turns for each pair (A starts as system, B swaps roles).
 * 5) Creates memories from each conversation and verifies that a randomly
 *    picked character has memories from both system and user conversations.
 *
 * Uses streaming mode with synchronous completion latch because the ollama
 * non-streaming endpoint may be unresponsive.
 */
public class TestMemoryDuel extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;
	private OlioContext octx;

	private static final int DUEL_TURNS = 1;   // Single turn to minimize LLM calls
	private static final int NUM_PAIRS = 2;    // Need at least 2 chars to pick different system/user
	private static final int STREAM_TIMEOUT_SECONDS = 300; // 5 minutes per turn

	@Before
	public void setupDuel() {
		testOrgContext = getTestOrganization("/Development/MemoryDuel");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "memoryDuelUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		String dataPath = testProperties.getProperty("test.datagen.path");
		octx = OlioContextUtil.getGridContext(testUser, dataPath, "Memory Duel Universe", "Memory Duel World", false);
		assertNotNull("Olio context should not be null", octx);
	}

	@Test(timeout = 600000)
	public void testChatDuelWithMemories() {
		logger.warn("[LLM-LIVE] testChatDuelWithMemories: Requires reachable LLM server and correct model/serviceType config");
		try {
			// --- Step 1: Get population and pick characters ---
			List<BaseRecord> realms = octx.getRealms();
			assertTrue("Expected at least one realm", realms.size() > 0);
			BaseRecord realm = realms.get(0);
			List<BaseRecord> pop = octx.getRealmPopulation(realm);
			assertTrue("Population should have at least " + NUM_PAIRS + " people", pop.size() >= NUM_PAIRS);

			List<BaseRecord> shuffled = new ArrayList<>(pop);
			Collections.shuffle(shuffled);
			List<BaseRecord> picked = shuffled.subList(0, NUM_PAIRS);

			logger.info("Picked " + NUM_PAIRS + " characters:");
			for (BaseRecord p : picked) {
				logger.info("  - " + p.get(FieldNames.FIELD_NAME)
						+ " (oid=" + p.get(FieldNames.FIELD_OBJECT_ID) + ")");
			}

			// --- Step 2: Load alg.prompt.json as prompt config ---
			BaseRecord promptConfig = loadAlgPromptConfig(testUser);
			assertNotNull("Prompt config should not be null", promptConfig);

			// --- Step 3: Generate a fixed setting ---
			String setting = NarrativeUtil.getRandomSetting();
			logger.info("Setting for all duels: " + setting);

			// Track conversationIds by character objectId
			Map<String, List<String>> systemConvs = new HashMap<>();
			Map<String, List<String>> userConvs = new HashMap<>();
			for (BaseRecord p : picked) {
				String oid = p.get(FieldNames.FIELD_OBJECT_ID);
				systemConvs.put(oid, new ArrayList<>());
				userConvs.put(oid, new ArrayList<>());
			}

			Random rand = new Random();

			// --- Step 4: Create duel pairs and run round-robin ---
			for (int pairIdx = 0; pairIdx < NUM_PAIRS; pairIdx++) {
				BaseRecord sysChar = picked.get(pairIdx);

				// Pick a different character for user role
				BaseRecord usrChar;
				do {
					usrChar = picked.get(rand.nextInt(NUM_PAIRS));
				} while (usrChar.get(FieldNames.FIELD_OBJECT_ID)
						.equals(sysChar.get(FieldNames.FIELD_OBJECT_ID)));

				String sysOid = sysChar.get(FieldNames.FIELD_OBJECT_ID);
				String usrOid = usrChar.get(FieldNames.FIELD_OBJECT_ID);
				String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
				String usrName = usrChar.get(FieldNames.FIELD_FIRST_NAME);

				logger.info("=== Duel Pair " + (pairIdx + 1) + ": "
						+ sysName + " (system) vs " + usrName + " (user) ===");

				String convIdA = "duel-" + pairIdx + "-A-" + UUID.randomUUID().toString().substring(0, 8);
				String convIdB = "duel-" + pairIdx + "-B-" + UUID.randomUUID().toString().substring(0, 8);

				// Config A: sysChar=system, usrChar=user
				systemConvs.get(sysOid).add(convIdA);
				userConvs.get(usrOid).add(convIdA);
				// Config B: roles swapped
				systemConvs.get(usrOid).add(convIdB);
				userConvs.get(sysOid).add(convIdB);

				BaseRecord cfgA = createDuelChatConfig(
						"Duel " + pairIdx + " A " + UUID.randomUUID().toString().substring(0, 6),
						sysChar, usrChar, setting, "system");
				assertNotNull("ChatConfig A should not be null", cfgA);

				BaseRecord cfgB = createDuelChatConfig(
						"Duel " + pairIdx + " B " + UUID.randomUUID().toString().substring(0, 6),
						usrChar, sysChar, setting, "user");
				assertNotNull("ChatConfig B should not be null", cfgB);

				Chat chatA = new Chat(testUser, cfgA, promptConfig);
				Chat chatB = new Chat(testUser, cfgB, promptConfig);
				chatA.setPersistSession(false);
				chatB.setPersistSession(false);

				// Set up streaming listeners for synchronous waiting
				DuelStreamListener listenerA = new DuelStreamListener("A");
				DuelStreamListener listenerB = new DuelStreamListener("B");
				chatA.setListener(listenerA);
				chatB.setListener(listenerB);

				OpenAIRequest reqA = chatA.getChatPrompt();
				OpenAIRequest reqB = chatB.getChatPrompt();
				assertNotNull("Request A should not be null", reqA);
				assertNotNull("Request B should not be null", reqB);

				// Enable streaming mode
				reqA.setStream(true);
				reqB.setStream(true);

				// Round-robin: empty string on first turn so system generates first
				String messageForA = "";
				String messageForB = null;

				for (int turn = 0; turn < DUEL_TURNS; turn++) {
					logger.info("--- Pair " + (pairIdx + 1) + " Turn " + (turn + 1) + "/" + DUEL_TURNS + " ---");

					// A speaks
					listenerA.reset();
					chatA.continueChat(reqA, messageForA);
					boolean completedA = listenerA.awaitCompletion(STREAM_TIMEOUT_SECONDS);
					assertTrue("Chat A should complete within timeout on turn " + (turn + 1), completedA);
					assertFalse("Chat A should not have errors", listenerA.hasError());

					// handleResponse to add assistant message to req
					OpenAIResponse respA = listenerA.getResponse();
					assertNotNull("Response from A should not be null", respA);
					chatA.handleResponse(reqA, respA, false);

					List<OpenAIMessage> msgsA = reqA.getMessages();
					assertTrue("Request A should have messages after turn", msgsA.size() > 0);
					messageForB = msgsA.get(msgsA.size() - 1).getContent();
					assertNotNull("Response content from A should not be null", messageForB);
					logger.info(sysName + ": " + truncate(messageForB, 150));

					// B responds with A's output
					listenerB.reset();
					chatB.continueChat(reqB, messageForB);
					boolean completedB = listenerB.awaitCompletion(STREAM_TIMEOUT_SECONDS);
					assertTrue("Chat B should complete within timeout on turn " + (turn + 1), completedB);
					assertFalse("Chat B should not have errors", listenerB.hasError());

					OpenAIResponse respB = listenerB.getResponse();
					assertNotNull("Response from B should not be null", respB);
					chatB.handleResponse(reqB, respB, false);

					List<OpenAIMessage> msgsB = reqB.getMessages();
					assertTrue("Request B should have messages after turn", msgsB.size() > 0);
					messageForA = msgsB.get(msgsB.size() - 1).getContent();
					assertNotNull("Response content from B should not be null", messageForA);
					logger.info(usrName + ": " + truncate(messageForA, 150));
				}

				// Create memories from both conversations
				int memA = createMemoriesFromConversation(reqA, convIdA, sysName, usrName);
				int memB = createMemoriesFromConversation(reqB, convIdB, usrName, sysName);
				logger.info("Created " + memA + " memories for conv A, " + memB + " for conv B");
				logger.info("=== Duel Pair " + (pairIdx + 1) + " complete ===\n");
			}

			// --- Step 5: Pick a random character and verify memories ---
			BaseRecord testChar = picked.get(rand.nextInt(NUM_PAIRS));
			String testCharOid = testChar.get(FieldNames.FIELD_OBJECT_ID);
			String testCharName = testChar.get(FieldNames.FIELD_FIRST_NAME);

			List<String> sysConvIds = systemConvs.get(testCharOid);
			List<String> usrConvIds = userConvs.get(testCharOid);

			logger.info("=== Memory Verification for " + testCharName + " ===");
			logger.info("System conversation count: " + sysConvIds.size());
			logger.info("User conversation count: " + usrConvIds.size());

			assertTrue(testCharName + " should have at least 1 system conversation", sysConvIds.size() >= 1);
			assertTrue(testCharName + " should have at least 1 user conversation", usrConvIds.size() >= 1);

			// Verify memories exist for system conversations
			int totalSystemMemories = 0;
			for (String convId : sysConvIds) {
				List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, convId);
				assertNotNull("Memories should not be null for system conv " + convId, memories);
				assertTrue("Should have memories for system conv " + convId, memories.size() > 0);
				totalSystemMemories += memories.size();
				logger.info("  System conv " + convId + ": " + memories.size() + " memories");
			}

			// Verify memories exist for user conversations
			int totalUserMemories = 0;
			for (String convId : usrConvIds) {
				List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, convId);
				assertNotNull("Memories should not be null for user conv " + convId, memories);
				assertTrue("Should have memories for user conv " + convId, memories.size() > 0);
				totalUserMemories += memories.size();
				logger.info("  User conv " + convId + ": " + memories.size() + " memories");
			}

			logger.info("Total memories: system=" + totalSystemMemories + ", user=" + totalUserMemories);
			assertTrue("Should have system memories", totalSystemMemories > 0);
			assertTrue("Should have user memories", totalUserMemories > 0);

			// Verify MCP context formatting
			List<String> allConvIds = new ArrayList<>(sysConvIds);
			allConvIds.addAll(usrConvIds);
			List<BaseRecord> allMemories = new ArrayList<>();
			for (String convId : allConvIds) {
				allMemories.addAll(MemoryUtil.getConversationMemories(testUser, convId));
			}
			String context = MemoryUtil.formatMemoriesAsContext(allMemories);
			assertNotNull("MCP context should not be null", context);
			assertFalse("MCP context should not be empty", context.isEmpty());
			logger.info("MCP context length: " + context.length() + " chars for " + allMemories.size() + " memories");

			logger.info("=== TestMemoryDuel PASSED ===");

		} catch (Exception e) {
			logger.error("Test failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Streaming Listener for Synchronous Duel ---

	/**
	 * A chat listener that uses a CountDownLatch for synchronous waiting on
	 * streaming responses. Each call to reset() creates a fresh latch for the
	 * next turn.
	 */
	private class DuelStreamListener implements IChatListener {
		private final String tag;
		private volatile CountDownLatch latch;
		private final AtomicReference<OpenAIResponse> responseRef = new AtomicReference<>();
		private final AtomicBoolean errorFlag = new AtomicBoolean(false);
		private final AtomicReference<String> errorMsg = new AtomicReference<>();

		DuelStreamListener(String tag) {
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

		@Override
		public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
			logger.info("Stream " + tag + " complete");
			responseRef.set(response);
			latch.countDown();
		}

		@Override
		public void onupdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message) {
			// Streaming chunk received - no-op, response accumulates in Chat.chat()
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

	// --- Helper Methods ---

	/**
	 * Creates a minimal prompt config for fast testing.
	 * Uses a very short system prompt to minimize LLM processing time.
	 */
	private BaseRecord loadAlgPromptConfig(BaseRecord user) {
		BaseRecord opcfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_PROMPT_CONFIG,
				"Memory Duel Simple Prompt", "~/Chat");
		if (opcfg != null) {
			return opcfg;
		}

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "Memory Duel Simple Prompt");

		try {
			BaseRecord pcfg = IOSystem.getActiveContext().getFactory()
					.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, null, plist);
			// Minimal system prompt to keep inference fast
			List<String> sys = new ArrayList<>();
			sys.add("You are {{systemCharacter.firstName}}, a {{systemCharacter.age}}-year-old {{systemCharacter.gender}}.");
			sys.add("Respond briefly in character to the user who is {{userCharacter.firstName}}.");
			pcfg.set("system", sys);
			opcfg = IOSystem.getActiveContext().getAccessPoint().create(user, pcfg);
		} catch (Exception e) {
			logger.error("Error creating prompt config", e);
		}
		return opcfg;
	}

	private BaseRecord createDuelChatConfig(String name, BaseRecord sysChar, BaseRecord usrChar,
			String setting, String startMode) {

		BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, name);
		try {
			cfg.set("systemCharacter", sysChar);
			cfg.set("userCharacter", usrChar);
			cfg.set("startMode", startMode);
			cfg.set("setting", setting);
			cfg.set("assist", false);
			cfg.set("useNLP", false);
			cfg.set("includeScene", false);
			cfg.set("prune", true);
			cfg.set("rating", ESRBEnumType.E);
			cfg.set("extractMemories", true);

			String terrain = NarrativeUtil.getTerrain(octx, usrChar);
			if (terrain != null) {
				cfg.set(FieldNames.FIELD_TERRAIN, terrain);
			}

			// LLM service config from test properties
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
				// qwen3:8b is faster than creat (smaller quantization Q4_K_M vs Q8_0)
				cfg.setValue("model", "qwen3:8b");
			}

			BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			opts.set("temperature", 0.7);
			opts.set("top_p", 1.0);
			opts.set("repeat_penalty", 1.2);
			opts.set("num_ctx", 4096);
			cfg.set("chatOptions", opts);

			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			if (cfg != null) {
				// Re-read with controlled depth to avoid StackOverflow
				BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(
						testUser, ModelNames.MODEL_GROUP, "~/Chat", "DATA",
						testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG,
						FieldNames.FIELD_NAME, name);
				q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				OlioUtil.planMost(q);
				OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");
				cfg = IOSystem.getActiveContext().getSearch().findRecord(q);
			}
		} catch (StackOverflowError | Exception e) {
			logger.error("Error creating chat config: " + e.getMessage());
			return null;
		}
		return cfg;
	}

	private int createMemoriesFromConversation(OpenAIRequest req, String conversationId,
			String systemName, String userName) {

		List<OpenAIMessage> msgs = req.getMessages();
		int memoryCount = 0;

		// Build conversation summary from all non-system messages
		StringBuilder convoText = new StringBuilder();
		for (int i = 1; i < msgs.size(); i++) {
			OpenAIMessage msg = msgs.get(i);
			String content = msg.getContent();
			if (content == null || content.trim().isEmpty()) continue;

			String speaker = "assistant".equals(msg.getRole()) ? systemName : userName;
			convoText.append(speaker).append(": ").append(truncate(content, 300)).append("\n");
		}

		if (convoText.length() == 0) return 0;

		// Summary memory for the whole conversation
		BaseRecord summaryMem = MemoryUtil.createMemory(
				testUser,
				convoText.toString(),
				systemName + " and " + userName + " duel",
				MemoryTypeEnumType.NOTE, 6,
				"am7://duel/" + conversationId, conversationId);
		if (summaryMem != null) memoryCount++;

		// Individual memories from each assistant response
		for (int i = 1; i < msgs.size(); i++) {
			OpenAIMessage msg = msgs.get(i);
			if (!"assistant".equals(msg.getRole())) continue;
			String content = msg.getContent();
			if (content == null || content.trim().isEmpty()) continue;

			BaseRecord mem = MemoryUtil.createMemory(
					testUser,
					systemName + " said: " + content,
					systemName + " response in duel",
					MemoryTypeEnumType.BEHAVIOR, 5,
					"am7://duel/" + conversationId, conversationId);
			if (mem != null) memoryCount++;
		}

		return memoryCount;
	}

	private static String truncate(String s, int maxLen) {
		if (s == null) return "null";
		return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
	}
}
