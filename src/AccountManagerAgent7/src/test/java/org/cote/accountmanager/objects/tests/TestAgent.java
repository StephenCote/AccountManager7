package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.agent.AM7AgentTool;
import org.cote.accountmanager.agent.AgentToolManager;
import org.cote.accountmanager.agent.PlanExecutionError;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestAgent extends BaseTest {
	
	private String testPlanName = "Test Plan - " + UUID.randomUUID().toString();
	private String testPlanPromptName = "Demo Plan Prompt 10";
	private String testPlanChatName = "Demo Plan Chat - " + UUID.randomUUID().toString();
	private String testPlanFile = "./plan.json";
	//private String testChatConfig = "AM7 AgentTool Openai 5.chat";
	private String testChatConfigPrefix = "AM7 AgentTool";
	private String testQuery = "Who has red hair?";
	@Test
	public void TestAgent1() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Agentic");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		/*
		String dataPath = testProperties.getProperty("test.datagen.path");
		OlioContext ctx = OlioTestUtil.getContext(testOrgContext, dataPath);
		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected some realms", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		BaseRecord popGroup = realm.get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Pop group is null", popGroup);
		List<BaseRecord> pop = ctx.getRealmPopulation(realm);
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(ctx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(ctx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);		
		*/
		
		String testChatConfig = testChatConfigPrefix + " " + testProperties.getProperty("test.llm.serviceType").trim().toUpperCase() + " 10.chat";
		BaseRecord cfg = getChatConfig(testUser1, testChatConfig);

		BaseRecord plan = null;
		String planStr = FileUtil.getFileAsString(testPlanFile);
		if(planStr != null && planStr.length() > 0) {
			plan = JSONUtil.importObject(planStr, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			assertNotNull("Imported plan was null", plan);
		}
		
		AM7AgentTool agentTool = new AM7AgentTool(testUser1);
		AgentToolManager toolManager = new AgentToolManager(testUser1, cfg, agentTool);
		//logger.info(toolManager.getPlanPromptConfig(testPlanPromptName).toFullString());

		if(plan == null) {
			try {
				logger.info("Creating plan outline ...");
				plan = toolManager.createPlan(testPlanName, testPlanPromptName, testPlanChatName, testQuery);
				assertNotNull(plan);
				List<BaseRecord> steps = plan.get("steps");
				assertTrue("Expected steps", steps.size() > 0);
				int err = 0;
				for(BaseRecord step: steps) {
				
					BaseRecord rstep = toolManager.refineStep(plan,  step, testPlanChatName);
					if(rstep == null) {
						logger.error("Failed to refine step " + step.get("step") + " " + step.get("toolName"));
						err++;
						break;
					}
				}
				assertTrue("Refined steps", err == 0);

				FileUtil.emitFile(testPlanFile, plan.toFullString());
			}
			catch(Exception e) {
				logger.error("Error creating plan outline", e);
				e.printStackTrace();
			}
		}
		
		if((boolean)plan.get("executed") == false) {
			try {
				toolManager.getPlanExecutor().executePlan(plan);
				FileUtil.emitFile(testPlanFile, plan.toFullString());
			}
			catch(PlanExecutionError e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		if(plan.get("output") == null) {
			try {
			BaseRecord output = toolManager.getPlanExecutor().evaluateResult(plan);
			assertNotNull("Plan output was null", output);
			logger.info("Plan output: " + output.toFullString());
			}
			catch(Exception e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		
		assertNotNull("Plan was null", plan);


		 
	}
	

	
	public static BaseRecord getChatConfig(BaseRecord user, String name) {
		
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		OlioUtil.planMost(q);
		OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");
		BaseRecord cfg = IOSystem.getActiveContext().getSearch().findRecord(q);

		if(cfg != null) {
			return cfg;
		}
		
		cfg = ChatUtil.getCreateChatConfig(user, name);
		
		try {
			cfg.set("startMode", "user");
			cfg.set("assist", false);
			cfg.set("useNLP", false);
			cfg.set("setting", null);
			cfg.set("includeScene", false);
			cfg.set("prune", true);
			cfg.set("rating", ESRBEnumType.E);
			LLMServiceEnumType serviceType = LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.serviceType").trim().toUpperCase());
			cfg.setValue("serviceType", serviceType);
			if(serviceType == LLMServiceEnumType.OPENAI) {
				cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version").trim());
				cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server").trim());
				cfg.setValue("model", testProperties.getProperty("test.llm.openai.model").trim());
				cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken").trim());
			}
			else if(serviceType == LLMServiceEnumType.OLLAMA) {
				logger.info("** " + testProperties.getProperty("test.llm.ollama.server"));
				cfg.setValue("serverUrl", testProperties.getProperty("test.llm.ollama.server").trim());
				cfg.setValue("model", testProperties.getProperty("test.llm.ollama.model").trim());
				
			}
			BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			opts.set("temperature", 0.4);
			opts.set("top_p", 1.0);
			opts.set("repeat_penalty", 1.2);
			opts.set("typical_p", 0.85);
			opts.set("num_ctx", 8192*2);
			cfg.set("chatOptions",  opts);
			
			cfg = IOSystem.getActiveContext().getAccessPoint().update(user, cfg);
			
			if(cfg != null) {
				/// Read again because the key value will have been encrypted after writing, and the model needs to be 'read' again
				///
				///
				MemoryReader memReader = new MemoryReader();
				cfg = memReader.read(cfg);
				//cfg = IOSystem.getActiveContext().getSearch().findRecord(q);
			}
			
		}
		catch(StackOverflowError | ModelNotFoundException | FieldException | ValueException | ReaderException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return cfg;
	}
	
	
}
