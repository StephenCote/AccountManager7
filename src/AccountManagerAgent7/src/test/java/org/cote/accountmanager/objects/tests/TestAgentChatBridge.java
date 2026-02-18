package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.agent.AgentToolManager;
import org.cote.accountmanager.agent.AM7AgentTool;
import org.cote.accountmanager.agent.ChainExecutor;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/// Phase 2a (chatRefactor2): Tests for the agentic chat bridge — AgentToolManager + ChainExecutor
/// wired through Chat.detectAndRouteAgentic().
public class TestAgentChatBridge extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;
	private BaseRecord chatConfig;

	@Before
	public void setupBridge() {
		testOrgContext = getTestOrganization("/Development/AgentBridge");
		testUser = ioContext.getFactory().getCreateUser(
			testOrgContext.getAdminUser(), "agentBridgeUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user is null", testUser);
		chatConfig = TestAgent.getChatConfig(testUser, "AgentBridge Chat");
	}

	@Test
	public void testAgentToolManagerCreation() {
		logger.info("testAgentToolManagerCreation");
		if (chatConfig == null) {
			logger.warn("Chat config not available — skipping");
			return;
		}

		AM7AgentTool agentTool = new AM7AgentTool(testUser);
		assertNotNull("agentTool is null", agentTool);

		AgentToolManager toolMgr = new AgentToolManager(testUser, chatConfig, agentTool);
		assertNotNull("toolManager is null", toolMgr);

		ChainExecutor executor = toolMgr.getChainExecutor();
		assertNotNull("chainExecutor is null", executor);
	}

	@Test
	public void testCreateChainPlan() {
		logger.info("testCreateChainPlan");
		if (chatConfig == null) {
			logger.warn("Chat config not available — skipping");
			return;
		}

		AM7AgentTool agentTool = new AM7AgentTool(testUser);
		AgentToolManager toolMgr = new AgentToolManager(testUser, chatConfig, agentTool);

		String query = "Find all persons named Alice";
		BaseRecord plan = toolMgr.createChainPlan(query);
		assertNotNull("Plan is null", plan);

		String planName = plan.get(FieldNames.FIELD_NAME);
		assertNotNull("Plan name is null", planName);
		logger.info("Created chain plan: " + planName);
	}

	@Test
	public void testChainExecution() {
		logger.info("testChainExecution");
		if (chatConfig == null) {
			logger.warn("Chat config not available — skipping");
			return;
		}

		AM7AgentTool agentTool = new AM7AgentTool(testUser);
		AgentToolManager toolMgr = new AgentToolManager(testUser, chatConfig, agentTool);

		String query = "Describe the available data models";
		BaseRecord plan = toolMgr.createChainPlan(query);
		if (plan == null) {
			logger.warn("Plan creation returned null — LLM may be unavailable");
			return;
		}

		ChainExecutor executor = toolMgr.getChainExecutor();
		try {
			executor.executeChain(plan);
			java.util.Map<String, Object> ctx = executor.getChainContext();
			assertNotNull("Chain context is null", ctx);
			logger.info("Chain execution completed with " + ctx.size() + " context entries");
		} catch (Exception e) {
			logger.warn("Chain execution failed (LLM/DB may be unavailable): " + e.getMessage());
		}
	}

	@Test
	public void testDetectAndRouteAgentic() {
		logger.info("testDetectAndRouteAgentic");
		if (chatConfig == null) {
			logger.warn("Chat config not available — skipping");
			return;
		}

		BaseRecord promptConfig = TestAgent.getChatConfig(testUser, "AgentBridge Prompt");
		if (promptConfig == null) {
			logger.warn("Prompt config not available — skipping");
			return;
		}

		Chat chat = new Chat(testUser, chatConfig, promptConfig);

		/// Test with agentEnabled=false (or missing) — should return empty
		String result = chat.detectAndRouteAgentic(testUser, chatConfig, "Hello, describe the models");
		assertTrue("Non-agentic config should return empty", result.isEmpty());

		/// Test with null message
		result = chat.detectAndRouteAgentic(testUser, chatConfig, null);
		assertTrue("Null message should return empty", result.isEmpty());

		/// Test with null config
		result = chat.detectAndRouteAgentic(testUser, null, "Hello");
		assertTrue("Null config should return empty", result.isEmpty());
	}

	@Test
	public void testChainEventListener() {
		logger.info("testChainEventListener");
		if (chatConfig == null) {
			logger.warn("Chat config not available — skipping");
			return;
		}

		AM7AgentTool agentTool = new AM7AgentTool(testUser);
		AgentToolManager toolMgr = new AgentToolManager(testUser, chatConfig, agentTool);
		ChainExecutor executor = toolMgr.getChainExecutor();

		final java.util.List<String> events = new java.util.ArrayList<>();
		executor.setListener((user, chainEvent) -> {
			String eventType = chainEvent.get("eventType");
			events.add(eventType);
			logger.info("Chain event: " + eventType);
		});

		String query = "List all available models";
		BaseRecord plan = toolMgr.createChainPlan(query);
		if (plan == null) {
			logger.warn("Plan creation returned null — skipping");
			return;
		}

		try {
			executor.executeChain(plan);
			logger.info("Received " + events.size() + " chain events");
			assertTrue("Should receive at least one event", events.size() > 0);
		} catch (Exception e) {
			logger.warn("Chain execution failed: " + e.getMessage());
		}
	}
}
