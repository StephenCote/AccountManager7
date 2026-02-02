package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.agent.AM7AgentTool;
import org.cote.accountmanager.agent.AgentToolManager;
import org.cote.accountmanager.agent.ChainExecutor;
import org.cote.accountmanager.agent.PlanExecutionError;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.StepStatusEnumType;
import org.cote.accountmanager.schema.type.StepTypeEnumType;
import org.junit.Before;
import org.junit.Test;

public class TestChainExecutor extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser1;
	private BaseRecord chatConfig;
	private AM7AgentTool agentTool;
	private AgentToolManager toolManager;
	private ChainExecutor chainExec;
	private String testChatConfigPrefix = "AM7 AgentTool";

	@Before
	public void setupChain() {
		testOrgContext = getTestOrganization("/Development/Agentic");
		Factory mf = ioContext.getFactory();
		testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String testChatConfig = testChatConfigPrefix + " " + testProperties.getProperty("test.llm.serviceType").trim().toUpperCase() + " 10.chat";
		chatConfig = TestAgent.getChatConfig(testUser1, testChatConfig);
		assertNotNull("Chat config is null", chatConfig);
		agentTool = new AM7AgentTool(testUser1);
		toolManager = new AgentToolManager(testUser1, chatConfig, agentTool);
		chainExec = toolManager.getChainExecutor();
		assertNotNull("ChainExecutor is null", chainExec);
	}

	// --- Group A: Model & Schema Validation ---

	@Test
	public void testPlanStepModelNewFields() {
		try {
			BaseRecord step = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
			assertNotNull("PlanStep instance was null", step);

			// Assert defaults
			StepTypeEnumType stepType = step.getEnum("stepType");
			assertEquals("stepType should default to TOOL", StepTypeEnumType.TOOL, stepType);

			StepStatusEnumType stepStatus = step.getEnum("stepStatus");
			assertEquals("stepStatus should default to PENDING", StepStatusEnumType.PENDING, stepStatus);

			assertFalse("dynamic should default to false", (boolean) step.get("dynamic"));
			assertEquals("parentStep should default to -1", -1, (int) step.get("parentStep"));
			assertEquals("ragLimit should default to 10", 10, (int) step.get("ragLimit"));

			// Set and verify new fields
			step.setValue("stepType", StepTypeEnumType.LLM);
			assertEquals(StepTypeEnumType.LLM, step.getEnum("stepType"));

			step.setValue("promptConfigName", "testPrompt");
			assertEquals("testPrompt", step.get("promptConfigName"));

			step.setValue("chatConfigName", "testChat");
			assertEquals("testChat", step.get("chatConfigName"));

			step.setValue("policyName", "testPolicy");
			assertEquals("testPolicy", step.get("policyName"));

			step.setValue("ragQuery", "search term");
			assertEquals("search term", step.get("ragQuery"));

			step.setValue("summaryText", "step summary");
			assertEquals("step summary", step.get("summaryText"));

			step.setValue("vectorReferenceUri", "am7://default/data.data/doc-123");
			assertEquals("am7://default/data.data/doc-123", step.get("vectorReferenceUri"));

			// Serialize/deserialize round-trip
			String json = step.toFullString();
			assertNotNull("JSON serialization failed", json);
			assertTrue("JSON should contain stepType field", json.contains("stepType"));
			assertTrue("JSON should contain promptConfigName", json.contains("testPrompt"));
			// Verify enum value via model API
			assertEquals("stepType should still be LLM after serialization", StepTypeEnumType.LLM, step.getEnum("stepType"));

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testPlanModelNewFields() {
		try {
			BaseRecord plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			assertNotNull("Plan instance was null", plan);

			assertEquals("maxSteps should default to 20", 20, (int) plan.get("maxSteps"));
			assertEquals("totalExecutedSteps should default to 0", 0, (int) plan.get("totalExecutedSteps"));
			assertFalse("chainMode should default to false", (boolean) plan.get("chainMode"));

			plan.setValue("chainMode", true);
			plan.setValue("maxSteps", 5);
			plan.setValue("streamSessionId", "test-session");
			plan.setValue("mcpSessionId", "mcp-session-1");

			assertTrue((boolean) plan.get("chainMode"));
			assertEquals(5, (int) plan.get("maxSteps"));
			assertEquals("test-session", plan.get("streamSessionId"));
			assertEquals("mcp-session-1", plan.get("mcpSessionId"));

			// Verify existing fields still work
			plan.setValue("planQuery", "test query");
			plan.setValue("executed", false);
			assertEquals("test query", plan.get("planQuery"));

			String json = plan.toFullString();
			assertNotNull(json);
			assertTrue(json.contains("chainMode"));
			assertTrue(json.contains("maxSteps"));

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testChainEventModel() {
		try {
			BaseRecord event = RecordFactory.newInstance(ModelNames.MODEL_CHAIN_EVENT);
			assertNotNull("ChainEvent instance was null", event);

			event.set("eventType", "stepComplete");
			event.set("planName", "Test Plan");
			event.set("stepNumber", 3);
			event.set("totalSteps", 7);
			event.set("stepType", "LLM");
			event.set("stepStatus", "COMPLETED");
			event.set("stepSummary", "Analyzed data");
			event.set("toolName", "describeAllModels");
			event.set("outputPreview", "first 100 chars...");
			event.set("timestamp", System.currentTimeMillis());
			event.set("mcpContextUri", "am7://default/tool.plan/plan-123");

			String json = event.toFullString();
			assertNotNull("JSON serialization was null", json);
			assertTrue("JSON should contain eventType value", json.contains("stepComplete"));
			assertTrue("JSON should contain stepSummary value", json.contains("Analyzed data"));

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testStepTypeEnumValues() {
		assertNotNull(StepTypeEnumType.UNKNOWN);
		assertNotNull(StepTypeEnumType.TOOL);
		assertNotNull(StepTypeEnumType.LLM);
		assertNotNull(StepTypeEnumType.RAG_QUERY);
		assertNotNull(StepTypeEnumType.POLICY_GATE);

		assertEquals("TOOL", StepTypeEnumType.TOOL.value());
		assertEquals("LLM", StepTypeEnumType.LLM.value());

		assertEquals(StepTypeEnumType.RAG_QUERY, StepTypeEnumType.fromValue("RAG_QUERY"));
	}

	@Test
	public void testStepStatusEnumValues() {
		assertNotNull(StepStatusEnumType.UNKNOWN);
		assertNotNull(StepStatusEnumType.PENDING);
		assertNotNull(StepStatusEnumType.EXECUTING);
		assertNotNull(StepStatusEnumType.COMPLETED);
		assertNotNull(StepStatusEnumType.FAILED);
		assertNotNull(StepStatusEnumType.GATED);
		assertNotNull(StepStatusEnumType.SKIPPED);

		assertEquals("COMPLETED", StepStatusEnumType.COMPLETED.value());
		assertEquals(StepStatusEnumType.GATED, StepStatusEnumType.fromValue("GATED"));
	}

	// --- Group B: TOOL Step Execution ---

	@Test
	public void testToolStepBasic() {
		try {
			BaseRecord plan = buildSingleToolPlan("describeAllModels");
			chainExec.executeChain(plan);

			List<BaseRecord> steps = plan.get("steps");
			BaseRecord step = steps.get(0);
			assertEquals(StepStatusEnumType.COMPLETED, step.getEnum("stepStatus"));

			BaseRecord output = step.get("output");
			assertNotNull("Step output was null", output);
			assertNotNull("Output value was null", output.get(FieldNames.FIELD_VALUE));
			assertTrue("Plan should be marked executed", (boolean) plan.get("executed"));

		} catch (PlanExecutionError e) {
			logger.error(e);
			fail("PlanExecutionError: " + e.getMessage());
		}
	}

	@Test
	public void testToolStepReflectionError() {
		try {
			BaseRecord plan = buildSingleToolPlan("nonExistentTool");
			chainExec.executeChain(plan);
			fail("Should have thrown PlanExecutionError");
		} catch (PlanExecutionError e) {
			logger.info("Expected error: " + e.getMessage());
			assertTrue(e.getMessage().contains("not found"));
		}
	}

	// --- Group G: Guard Rails & Safety ---

	@Test
	public void testMaxStepsHardCeiling() {
		try {
			BaseRecord plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			plan.set(FieldNames.FIELD_NAME, "Max Steps Test");
			plan.set("planQuery", "test max steps");
			plan.set("maxSteps", 100);
			plan.set("chainMode", true);
			plan.set("executed", false);

			// Build 60 steps
			List<BaseRecord> steps = plan.get("steps");
			for (int i = 0; i < 60; i++) {
				BaseRecord step = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
				step.setValue("step", i + 1);
				step.setValue("stepType", StepTypeEnumType.TOOL);
				step.setValue("toolName", "describeAllModels");
				toolManager.preparePlanStep(step);
				steps.add(step);
			}

			chainExec.executeChain(plan);

			// Should have stopped at ABSOLUTE_MAX_STEPS (50)
			int totalExecuted = (int) plan.get("totalExecutedSteps");
			assertTrue("Should not exceed 50 steps, got " + totalExecuted, totalExecuted <= 50);

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testMaxStepsPlanLevel() {
		try {
			BaseRecord plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			plan.set(FieldNames.FIELD_NAME, "Plan Level Max Test");
			plan.set("planQuery", "test plan level max");
			plan.set("maxSteps", 3);
			plan.set("chainMode", true);
			plan.set("executed", false);

			List<BaseRecord> steps = plan.get("steps");
			for (int i = 0; i < 5; i++) {
				BaseRecord step = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
				step.setValue("step", i + 1);
				step.setValue("stepType", StepTypeEnumType.TOOL);
				step.setValue("toolName", "describeAllModels");
				toolManager.preparePlanStep(step);
				steps.add(step);
			}

			chainExec.executeChain(plan);

			int totalExecuted = (int) plan.get("totalExecutedSteps");
			assertEquals("Should execute exactly 3 steps", 3, totalExecuted);

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group H: Context Management ---

	@Test
	public void testContextSaveRestore() {
		try {
			BaseRecord plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			plan.set(FieldNames.FIELD_NAME, "Context Test");
			plan.set("planQuery", "test context save/restore");
			plan.set("maxSteps", 20);
			plan.set("chainMode", true);
			plan.set("executed", false);

			List<BaseRecord> steps = plan.get("steps");
			BaseRecord step1 = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
			step1.setValue("step", 1);
			step1.setValue("stepType", StepTypeEnumType.TOOL);
			step1.setValue("toolName", "describeAllModels");
			toolManager.preparePlanStep(step1);
			steps.add(step1);

			chainExec.executeChain(plan);

			// Save context
			chainExec.saveContext(plan);
			String ctxJson = plan.get("chainContextJson");
			assertNotNull("chainContextJson should be populated", ctxJson);
			assertFalse("chainContextJson should not be empty", ctxJson.isEmpty());
			assertTrue("Context should contain mcp:context blocks", ctxJson.contains("mcp:context"));

			// Restore in a new executor
			ChainExecutor newExec = new ChainExecutor(toolManager, testUser1);
			newExec.restoreContext(plan);
			assertFalse("Restored context should not be empty", newExec.getChainContext().isEmpty());

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group I: Event Listener ---

	@Test
	public void testEventListenerReceivesAllEvents() {
		try {
			List<BaseRecord> events = new ArrayList<>();
			chainExec.setListener((user, event) -> {
				events.add(event);
			});

			BaseRecord plan = buildSingleToolPlan("describeAllModels");
			chainExec.executeChain(plan);

			assertFalse("Should have received events", events.isEmpty());

			// Should have stepStart, stepComplete, chainComplete at minimum
			boolean hasStepStart = events.stream().anyMatch(e -> "stepStart".equals(e.get("eventType")));
			boolean hasStepComplete = events.stream().anyMatch(e -> "stepComplete".equals(e.get("eventType")));
			boolean hasChainComplete = events.stream().anyMatch(e -> "chainComplete".equals(e.get("eventType")));

			assertTrue("Should have stepStart event", hasStepStart);
			assertTrue("Should have stepComplete event", hasStepComplete);
			assertTrue("Should have chainComplete event", hasChainComplete);

			// Verify timestamps are monotonically increasing
			long prevTs = 0;
			for (BaseRecord e : events) {
				long ts = (long) e.get("timestamp");
				assertTrue("Timestamps should be monotonically increasing", ts >= prevTs);
				prevTs = ts;
			}

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testEventListenerStepFailure() {
		try {
			List<BaseRecord> events = new ArrayList<>();
			chainExec.setListener((user, event) -> {
				events.add(event);
			});

			BaseRecord plan = buildSingleToolPlan("nonExistentTool");
			try {
				chainExec.executeChain(plan);
				fail("Should have thrown");
			} catch (PlanExecutionError ex) {
				// Expected
			}

			boolean hasError = events.stream().anyMatch(e -> "stepError".equals(e.get("eventType")));
			assertTrue("Should have stepError event", hasError);

			BaseRecord errorEvent = events.stream().filter(e -> "stepError".equals(e.get("eventType"))).findFirst().orElse(null);
			assertNotNull(errorEvent);
			assertNotNull("Error event should have errorMessage", errorEvent.get("errorMessage"));

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testNoListenerDoesNotFail() {
		try {
			chainExec.setListener(null);
			BaseRecord plan = buildSingleToolPlan("describeAllModels");
			chainExec.executeChain(plan);
			assertTrue("Plan should complete without listener", (boolean) plan.get("executed"));
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testChainEventSerialization() {
		try {
			BaseRecord event = RecordFactory.newInstance(ModelNames.MODEL_CHAIN_EVENT);
			event.set("eventType", "stepComplete");
			event.set("planName", "SerTest");
			event.set("stepNumber", 2);
			event.set("totalSteps", 5);
			event.set("stepType", "TOOL");
			event.set("stepStatus", "COMPLETED");
			event.set("stepSummary", "Completed tool step");
			event.set("timestamp", System.currentTimeMillis());
			event.set("mcpContextUri", "am7://default/tool.plan/plan-456");

			String json = event.toFullString();
			assertNotNull("JSON serialization was null", json);
			assertTrue("JSON should contain eventType value", json.contains("stepComplete"));
			assertTrue("JSON should contain planName value", json.contains("SerTest"));
			assertTrue("JSON should contain mcpContextUri value", json.contains("am7://default/tool.plan/plan-456"));

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group J: End-to-End Integration ---

	@Test
	public void testBackwardCompatibility() {
		try {
			// Create a plan with only TOOL steps (no stepType set explicitly)
			BaseRecord plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			plan.set(FieldNames.FIELD_NAME, "BackCompat Test");
			plan.set("planQuery", "backward compat test");
			plan.set("maxSteps", 20);
			plan.set("chainMode", true);
			plan.set("executed", false);

			List<BaseRecord> steps = plan.get("steps");
			BaseRecord step = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
			step.setValue("step", 1);
			step.setValue("toolName", "describeAllModels");
			// Deliberately NOT setting stepType - should default to TOOL
			toolManager.preparePlanStep(step);
			steps.add(step);

			chainExec.executeChain(plan);

			assertTrue("Plan should be marked executed", (boolean) plan.get("executed"));
			assertEquals(StepStatusEnumType.COMPLETED, steps.get(0).getEnum("stepStatus"));

		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Helper Methods ---

	private BaseRecord buildSingleToolPlan(String toolName) {
		try {
			BaseRecord plan = RecordFactory.newInstance(ModelNames.MODEL_PLAN);
			plan.set(FieldNames.FIELD_NAME, "Test Plan - " + UUID.randomUUID().toString());
			plan.set("planQuery", "test with " + toolName);
			plan.set("maxSteps", 20);
			plan.set("chainMode", true);
			plan.set("executed", false);

			List<BaseRecord> steps = plan.get("steps");
			BaseRecord step = RecordFactory.newInstance(ModelNames.MODEL_PLAN_STEP);
			step.setValue("step", 1);
			step.setValue("stepType", StepTypeEnumType.TOOL);
			step.setValue("toolName", toolName);

			if (toolManager.preparePlanStep(step)) {
				// Step prepared with proper inputs/outputs
			}

			steps.add(step);
			return plan;

		} catch (Exception e) {
			logger.error(e);
			fail("Failed to build test plan: " + e.getMessage());
			return null;
		}
	}
}
