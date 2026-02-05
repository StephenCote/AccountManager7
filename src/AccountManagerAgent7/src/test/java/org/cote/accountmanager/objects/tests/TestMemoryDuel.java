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

import org.cote.accountmanager.agent.MemoryUtil;
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
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
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
 * 1) Initializes an Olio population and picks 5 random characters.
 * 2) Creates 5 duel pairs where each character is the system in one pair,
 *    with a randomly selected different character as user.
 * 3) Uses alg.prompt.json as the RPG prompt template.
 * 4) Runs a 6-turn round-robin for each pair (A starts as system, B swaps roles).
 * 5) Creates memories from each conversation and verifies that a randomly
 *    picked character has memories from both system and user conversations.
 */
public class TestMemoryDuel extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;
	private OlioContext octx;

	private static final int DUEL_TURNS = 3;  // 3 loop iterations = 6 messages (3 from A, 3 from B)
	private static final int NUM_PAIRS = 5;

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

	@Test
	public void testChatDuelWithMemories() {
		try {
			// --- Step 1: Get population and pick 5 random characters ---
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

			// --- Step 3: Generate a fixed setting (not random default) ---
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

			// --- Step 4: Create 5 duel pairs and run round-robin ---
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

				// Config A: sysChar=system, usrChar=user → sysChar has a system conv, usrChar has a user conv
				systemConvs.get(sysOid).add(convIdA);
				userConvs.get(usrOid).add(convIdA);
				// Config B: roles swapped → usrChar has a system conv, sysChar has a user conv
				systemConvs.get(usrOid).add(convIdB);
				userConvs.get(sysOid).add(convIdB);

				// Create chatConfig A: sysChar as system, usrChar as user
				BaseRecord cfgA = createDuelChatConfig(
						"Duel " + pairIdx + " A " + UUID.randomUUID().toString().substring(0, 6),
						sysChar, usrChar, setting, "system");
				assertNotNull("ChatConfig A should not be null", cfgA);

				// Create chatConfig B independently (avoid copyRecord StackOverflow on nested models)
				BaseRecord cfgB = createDuelChatConfig(
						"Duel " + pairIdx + " B " + UUID.randomUUID().toString().substring(0, 6),
						usrChar, sysChar, setting, "user");

				Chat chatA = new Chat(testUser, cfgA, promptConfig);
				Chat chatB = new Chat(testUser, cfgB, promptConfig);
				chatA.setPersistSession(false);
				chatB.setPersistSession(false);

				OpenAIRequest reqA = chatA.getChatPrompt();
				OpenAIRequest reqB = chatB.getChatPrompt();
				assertNotNull("Request A should not be null", reqA);
				assertNotNull("Request B should not be null", reqB);

				// Round-robin
				String messageForA = null;
				String messageForB = null;

				for (int turn = 0; turn < DUEL_TURNS; turn++) {
					logger.info("--- Pair " + (pairIdx + 1) + " Turn " + (turn + 1) + "/" + DUEL_TURNS + " ---");

					// A speaks (first turn: null = system initiates)
					chatA.continueChat(reqA, messageForA);
					List<OpenAIMessage> msgsA = reqA.getMessages();
					assertTrue("Request A should have messages after turn", msgsA.size() > 0);
					messageForB = msgsA.get(msgsA.size() - 1).getContent();
					assertNotNull("Response from A should not be null", messageForB);
					logger.info(sysName + ": " + truncate(messageForB, 150));

					// B responds with A's output as user input
					chatB.continueChat(reqB, messageForB);
					List<OpenAIMessage> msgsB = reqB.getMessages();
					assertTrue("Request B should have messages after turn", msgsB.size() > 0);
					messageForA = msgsB.get(msgsB.size() - 1).getContent();
					assertNotNull("Response from B should not be null", messageForA);
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

			// Each character is system in exactly one pair (pairIdx == their index)
			// and may be user in 0 or more pairs (randomly selected)
			// But because of the B-swap, every character is also in a user conv for their own pair
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

	// --- Helper Methods ---

	/**
	 * Loads alg.prompt.json from classpath resources and creates a prompt config record.
	 */
	private BaseRecord loadAlgPromptConfig(BaseRecord user) {
		BaseRecord opcfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_PROMPT_CONFIG,
				"Memory Duel Prompt", "~/Chat");
		if (opcfg != null) {
			return opcfg;
		}

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "Memory Duel Prompt");

		BaseRecord ipcfg = JSONUtil.importObject(
				ResourceUtil.getInstance().getResource("olio/llm/alg.prompt.json"),
				LooseRecord.class,
				RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Imported alg.prompt.json should not be null", ipcfg);

		try {
			BaseRecord pcfg = IOSystem.getActiveContext().getFactory()
					.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, ipcfg, plist);
			opcfg = IOSystem.getActiveContext().getAccessPoint().create(user, pcfg);
		} catch (FactoryException e) {
			logger.error("Error creating prompt config", e);
		}
		return opcfg;
	}

	/**
	 * Creates and persists a chatConfig for a duel pair with LLM service configuration.
	 */
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
				cfg.setValue("model", testProperties.getProperty("test.llm.ollama.model").trim());
			}

			BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			opts.set("temperature", 0.7);
			opts.set("top_p", 1.0);
			opts.set("repeat_penalty", 1.2);
			opts.set("num_ctx", 8192);
			cfg.set("chatOptions", opts);

			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			if (cfg != null) {
				// Re-read with controlled depth to avoid StackOverflow on deeply nested models
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

	/**
	 * Creates memory records from a completed conversation.
	 * Generates one summary memory plus one memory per assistant response.
	 * Returns the total number of memories created.
	 */
	private int createMemoriesFromConversation(OpenAIRequest req, String conversationId,
			String systemName, String userName) {

		List<OpenAIMessage> msgs = req.getMessages();
		int memoryCount = 0;

		// Build a conversation summary from all non-system messages
		StringBuilder convoText = new StringBuilder();
		for (int i = 1; i < msgs.size(); i++) {
			OpenAIMessage msg = msgs.get(i);
			String content = msg.getContent();
			if (content == null || content.trim().isEmpty()) continue;

			String speaker = "assistant".equals(msg.getRole()) ? systemName : userName;
			convoText.append(speaker).append(": ").append(truncate(content, 300)).append("\n");
		}

		if (convoText.length() == 0) return 0;

		// Create one summary memory for the whole conversation
		BaseRecord summaryMem = MemoryUtil.createMemory(
				testUser,
				convoText.toString(),
				systemName + " and " + userName + " duel",
				MemoryTypeEnumType.NOTE, 6,
				"am7://duel/" + conversationId, conversationId);
		if (summaryMem != null) memoryCount++;

		// Create individual memories from each assistant response
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
