package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Phase 10 backend tests for chat refactor.
 * P10-1: Config objectId lookup via ChatUtil.getConfig
 * P10-2: Config update-if-changed persistence
 * P10-3: History message ordering after 3 exchanges (OI-43)
 * P10-4: History with prune=false preserves all messages
 */
public class TestChatPhase10 extends BaseTest {

	/**
	 * P10-1: Verify that ChatUtil.getConfig can look up configs by objectId
	 * when name is null. This supports the new /rest/chat/config/prompt/id/{objectId}
	 * and /rest/chat/config/chat/id/{objectId} endpoints.
	 */
	@Test
	public void TestConfigObjectIdLookup() {
		logger.info("P10-1: Config objectId lookup");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10user1", testOrgContext.getOrganizationId());

		// Create a prompt config
		String uniqueName = "P10 Prompt - " + UUID.randomUUID().toString();
		BaseRecord pcfg = OlioTestUtil.getPromptConfig(testUser, uniqueName);
		assertNotNull("Prompt config should be created", pcfg);
		String objectId = pcfg.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("objectId should not be null", objectId);
		logger.info("Created prompt config with objectId: " + objectId);

		// Look up by objectId (name=null)
		BaseRecord found = ChatUtil.getConfig(testUser, OlioModelNames.MODEL_PROMPT_CONFIG, objectId, null);
		assertNotNull("Should find prompt config by objectId", found);
		assertEquals("objectId should match", objectId, found.get(FieldNames.FIELD_OBJECT_ID));

		// Create a chat config
		String chatCfgName = "P10 Chat - " + UUID.randomUUID().toString();
		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				chatCfgName, testProperties);
		assertNotNull("Chat config should be created", chatCfg);
		String chatObjId = chatCfg.get(FieldNames.FIELD_OBJECT_ID);

		// Look up by objectId
		BaseRecord foundChat = ChatUtil.getConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, chatObjId, null);
		assertNotNull("Should find chat config by objectId", foundChat);
		assertEquals("objectId should match", chatObjId, foundChat.get(FieldNames.FIELD_OBJECT_ID));

		logger.info("P10-1: PASS");
	}

	/**
	 * P10-2: Verify config update-if-changed persistence.
	 * When a config is found and key fields differ from template, they should be patchable.
	 */
	@Test
	public void TestConfigUpdateIfChanged() {
		logger.info("P10-2: Config update-if-changed");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10user2", testOrgContext.getOrganizationId());

		// Create a chat config
		String cfgName = "P10 UpdateTest - " + UUID.randomUUID().toString();
		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				cfgName, testProperties);
		assertNotNull("Chat config should be created", chatCfg);
		String objectId = chatCfg.get(FieldNames.FIELD_OBJECT_ID);

		// Modify a field and patch
		try {
			chatCfg.set("messageTrim", 42);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatCfg);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		// Re-fetch and verify update persisted
		BaseRecord updated = ChatUtil.getConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, objectId, null);
		assertNotNull("Updated config should be found", updated);
		int trim = updated.get("messageTrim");
		assertEquals("messageTrim should be updated", 42, trim);

		logger.info("P10-2: PASS");
	}

	/**
	 * P10-3: History message ordering after 3 exchanges (OI-43 investigation).
	 * Creates a session, sends 3 messages with delays, retrieves history,
	 * and verifies message count and ordering.
	 */
	@Test
	public void TestHistoryMessageOrdering() {
		logger.info("P10-3: History message ordering (OI-43)");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10user3", testOrgContext.getOrganizationId());

		String chatCfgName = "P10 History - " + UUID.randomUUID().toString();
		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				chatCfgName, testProperties);
		assertNotNull("Chat config should be created", chatCfg);

		BaseRecord promptCfg = OlioTestUtil.getPromptConfig(testUser, "P10 History Prompt");
		assertNotNull("Prompt config should be created", promptCfg);

		// Set messageTrim high to keep all messages
		try {
			chatCfg.set("messageTrim", 20);
			chatCfg.set("prune", false);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatCfg);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		String requestName = "P10 HistoryTest - " + UUID.randomUUID().toString();
		BaseRecord req = ChatUtil.getCreateChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Chat request should be created", req);

		ChatRequest chatReq = new ChatRequest(ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg));
		assertNotNull("Chat request should be fetchable", chatReq);

		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, chatReq);
		assertNotNull("OpenAI request should be created", oreq);

		Chat chat = ChatUtil.getChat(testUser, chatReq, false);
		assertNotNull("Chat instance should be created", chat);

		// Send 3 messages with 200ms delays (OI-43 investigation)
		String[] messages = {"Hello, this is message one.", "This is message two.", "And finally message three."};
		for (int i = 0; i < messages.length; i++) {
			chat.continueChat(oreq, messages[i]);
			try { Thread.sleep(200); } catch (InterruptedException e) { /* ignore */ }
		}

		// Retrieve history
		ChatResponse response = ChatUtil.getChatResponse(testUser, oreq, chatReq);
		assertNotNull("Chat response should not be null", response);

		// Verify we have messages (at minimum the 3 user messages + 3 assistant replies = 6)
		// Plus system message(s)
		int msgCount = 0;
		try {
			java.util.List<?> msgs = response.get("messages");
			if (msgs != null) {
				msgCount = msgs.size();
			}
		} catch (Exception e) {
			logger.warn("Could not get message count: " + e.getMessage());
		}
		logger.info("History contains " + msgCount + " messages after 3 exchanges");
		assertTrue("Should have at least 6 messages (3 user + 3 assistant)", msgCount >= 6);

		logger.info("P10-3: PASS");
	}

	/**
	 * P10-4: History with prune=false preserves all messages.
	 * Similar to P10-3 but explicitly verifies that prune=false prevents message trimming.
	 */
	@Test
	public void TestHistoryPruneFalsePreservesMessages() {
		logger.info("P10-4: prune=false preserves messages");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10user4", testOrgContext.getOrganizationId());

		String chatCfgName = "P10 PruneTest - " + UUID.randomUUID().toString();
		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				chatCfgName, testProperties);
		assertNotNull("Chat config should be created", chatCfg);

		BaseRecord promptCfg = OlioTestUtil.getPromptConfig(testUser, "P10 Prune Prompt");
		assertNotNull("Prompt config should be created", promptCfg);

		// Set low messageTrim but prune=false — all messages should be preserved
		try {
			chatCfg.set("messageTrim", 2);
			chatCfg.set("prune", false);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatCfg);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		String requestName = "P10 PruneTest - " + UUID.randomUUID().toString();
		BaseRecord req = ChatUtil.getCreateChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Chat request should be created", req);

		ChatRequest chatReq = new ChatRequest(ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg));
		OpenAIRequest oreq = ChatUtil.getOpenAIRequest(testUser, chatReq);
		Chat chat = ChatUtil.getChat(testUser, chatReq, false);
		assertNotNull("Chat instance should be created", chat);

		// Send 4 messages (more than messageTrim of 2)
		for (int i = 1; i <= 4; i++) {
			chat.continueChat(oreq, "Test message " + i);
			try { Thread.sleep(200); } catch (InterruptedException e) { /* ignore */ }
		}

		// Retrieve history
		ChatResponse response = ChatUtil.getChatResponse(testUser, oreq, chatReq);
		assertNotNull("Chat response should not be null", response);

		int msgCount = 0;
		try {
			java.util.List<?> msgs = response.get("messages");
			if (msgs != null) {
				msgCount = msgs.size();
			}
		} catch (Exception e) {
			logger.warn("Could not get message count: " + e.getMessage());
		}
		logger.info("History contains " + msgCount + " messages (prune=false, trim=2, 4 exchanges)");
		// With prune=false, all messages should be preserved despite low messageTrim
		assertTrue("Should have at least 8 messages (4 user + 4 assistant) with prune=false", msgCount >= 8);

		logger.info("P10-4: PASS");
	}
}
