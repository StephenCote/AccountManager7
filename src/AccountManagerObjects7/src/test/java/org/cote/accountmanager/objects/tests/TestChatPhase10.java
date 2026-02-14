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
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/**
 * Phase 10 backend tests for chat refactor.
 * P10-1: Config objectId lookup via ChatUtil.getConfig
 * P10-2: Config update-if-changed persistence
 * P10-3: History message ordering after 3 exchanges (OI-43)
 * P10-4: History with prune=false preserves all messages
 * P10c-1: chatRequest contextType/context field persistence (Phase 10c)
 * P10c-2: Context attach/detach round-trip (Phase 10c)
 * P10c-3: Context field survives re-fetch (Phase 10c)
 * P10c-4: MCP tool provider lists session context tools (Phase 10c)
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

	/**
	 * P10c-1: Verify chatRequest contextType/context fields can be set and persisted.
	 * The chatRequestModel now has contextType (string) and context ($flex foreign).
	 * No LLM call needed — this is a data-layer test.
	 */
	@Test
	public void TestContextFieldPersistence() {
		logger.info("P10c-1: Context field persistence");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10cuser1", testOrgContext.getOrganizationId());

		String chatCfgName = "P10c CtxTest - " + UUID.randomUUID().toString();
		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				chatCfgName, testProperties);
		assertNotNull("Chat config should be created", chatCfg);

		BaseRecord promptCfg = OlioTestUtil.getPromptConfig(testUser, "P10c Ctx Prompt");
		assertNotNull("Prompt config should be created", promptCfg);

		String requestName = "P10c CtxField - " + UUID.randomUUID().toString();
		BaseRecord req = ChatUtil.getCreateChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Chat request should be created", req);

		/// Verify contextType field exists and is initially null
		BaseRecord fetched = ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Chat request should be fetchable", fetched);
		String ctxType = fetched.get("contextType");
		assertTrue("contextType should be null or empty initially", ctxType == null || ctxType.isEmpty());

		/// Set contextType and persist
		try {
			fetched.set("contextType", "data.data");
			IOSystem.getActiveContext().getAccessPoint().update(testUser, fetched);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		/// Re-fetch and verify
		BaseRecord refetched = ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Chat request should be re-fetchable", refetched);
		String savedCtxType = refetched.get("contextType");
		assertEquals("contextType should be persisted", "data.data", savedCtxType);

		logger.info("P10c-1: PASS");
	}

	/**
	 * P10c-2: Context attach/detach round-trip.
	 * Create a data.data object, attach it as context to a chatRequest via set(),
	 * verify it persists, then detach (set null) and verify removal.
	 */
	@Test
	public void TestContextAttachDetach() {
		logger.info("P10c-2: Context attach/detach round-trip");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10cuser2", testOrgContext.getOrganizationId());

		String chatCfgName = "P10c AttachTest - " + UUID.randomUUID().toString();
		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				chatCfgName, testProperties);
		assertNotNull("Chat config should be created", chatCfg);

		BaseRecord promptCfg = OlioTestUtil.getPromptConfig(testUser, "P10c Attach Prompt");
		assertNotNull("Prompt config should be created", promptCfg);

		/// Create a data object to use as context
		Factory mf = ioContext.getFactory();
		String dataName = "P10c Context Data - " + UUID.randomUUID().toString();
		BaseRecord dataObj = null;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/P10cData");
			plist.parameter(FieldNames.FIELD_NAME, dataName);
			dataObj = mf.newInstance(ModelNames.MODEL_DATA, testUser, null, plist);
			dataObj.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
		} catch (Exception e) {
			logger.error(e);
		}
		assertNotNull("Data object should be created", dataObj);
		dataObj = IOSystem.getActiveContext().getAccessPoint().create(testUser, dataObj);
		assertNotNull("Data object should be persisted", dataObj);
		String dataObjId = dataObj.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Data objectId should not be null", dataObjId);

		/// Create chat request
		String requestName = "P10c AttachDetach - " + UUID.randomUUID().toString();
		ChatUtil.getCreateChatRequest(testUser, requestName, chatCfg, promptCfg);
		BaseRecord chatReq = ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Chat request should be created", chatReq);

		/// Attach: set contextType and context
		try {
			chatReq.set("contextType", ModelNames.MODEL_DATA);
			chatReq.set("context", dataObj);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatReq);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		/// Re-fetch and verify attachment
		BaseRecord verified = ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Verified chat request should not be null", verified);
		String verCtxType = verified.get("contextType");
		assertEquals("contextType should be data.data", ModelNames.MODEL_DATA, verCtxType);

		/// Detach: set contextType and context to null
		try {
			verified.set("contextType", null);
			verified.set("context", null);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, verified);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		/// Verify detachment
		BaseRecord detached = ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg);
		assertNotNull("Detached chat request should not be null", detached);
		String detCtxType = detached.get("contextType");
		assertTrue("contextType should be null/empty after detach", detCtxType == null || detCtxType.isEmpty());

		logger.info("P10c-2: PASS");
	}

	/**
	 * P10c-3: Switching chatConfig on a chatRequest via set() persists correctly.
	 * This validates the "attachChatConfig" MCP tool pattern at the data layer.
	 */
	@Test
	public void TestConfigSwitch() {
		logger.info("P10c-3: Config switch on chatRequest");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "p10cuser3", testOrgContext.getOrganizationId());

		/// Create two distinct chat configs
		String cfgName1 = "P10c CfgA - " + UUID.randomUUID().toString();
		BaseRecord chatCfgA = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				cfgName1, testProperties);
		assertNotNull("Chat config A should be created", chatCfgA);

		String cfgName2 = "P10c CfgB - " + UUID.randomUUID().toString();
		BaseRecord chatCfgB = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				cfgName2, testProperties);
		assertNotNull("Chat config B should be created", chatCfgB);

		BaseRecord promptCfg = OlioTestUtil.getPromptConfig(testUser, "P10c Switch Prompt");
		assertNotNull("Prompt config should be created", promptCfg);

		/// Create chatRequest with config A
		String requestName = "P10c SwitchTest - " + UUID.randomUUID().toString();
		ChatUtil.getCreateChatRequest(testUser, requestName, chatCfgA, promptCfg);
		BaseRecord chatReq = ChatUtil.getChatRequest(testUser, requestName, chatCfgA, promptCfg);
		assertNotNull("Chat request should be created", chatReq);

		String origCfgId = ((BaseRecord) chatReq.get("chatConfig")).get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("Initial chatConfig should be A", chatCfgA.get(FieldNames.FIELD_OBJECT_ID), origCfgId);

		/// Switch to config B
		try {
			chatReq.set("chatConfig", chatCfgB);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatReq);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

		/// Re-fetch by name with config B and verify
		BaseRecord switched = ChatUtil.getChatRequest(testUser, requestName, chatCfgB, promptCfg);
		assertNotNull("Switched chat request should not be null", switched);

		BaseRecord switchedCfg = switched.get("chatConfig");
		assertNotNull("Switched chatConfig should not be null", switchedCfg);
		String switchedCfgId = switchedCfg.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("chatConfig should now be B", chatCfgB.get(FieldNames.FIELD_OBJECT_ID), switchedCfgId);

		logger.info("P10c-3: PASS");
	}

	/**
	 * P10c-4: MCP Am7ToolProvider lists the new session context tools.
	 * Verifies the tool provider advertises am7_session_attach, am7_session_detach, am7_session_context.
	 */
	@Test
	public void TestMcpToolProviderListsContextTools() {
		logger.info("P10c-4: MCP tool provider lists context tools");

		org.cote.accountmanager.mcp.server.Am7ToolProvider provider = new org.cote.accountmanager.mcp.server.Am7ToolProvider();
		java.util.List<org.cote.accountmanager.mcp.server.McpJsonRpc.Tool> tools = provider.listTools(null);
		assertNotNull("Tool list should not be null", tools);
		assertTrue("Should have at least 7 tools (4 original + 3 context)", tools.size() >= 7);

		boolean hasAttach = false, hasDetach = false, hasContext = false;
		for (org.cote.accountmanager.mcp.server.McpJsonRpc.Tool t : tools) {
			if ("am7_session_attach".equals(t.getName())) hasAttach = true;
			if ("am7_session_detach".equals(t.getName())) hasDetach = true;
			if ("am7_session_context".equals(t.getName())) hasContext = true;
		}
		assertTrue("Should list am7_session_attach tool", hasAttach);
		assertTrue("Should list am7_session_detach tool", hasDetach);
		assertTrue("Should list am7_session_context tool", hasContext);

		logger.info("P10c-4: PASS");
	}
}
