package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.agent.AM7AgentTool;
import org.cote.accountmanager.agent.AgentToolManager;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
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
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestAgent extends BaseTest {
	
	@Test
	public void TestAgent1() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		BaseRecord cfg = getChatConfig(testUser1, "AM7 AgentTool OpenAI.chat 7");
		ioContext.getAccessPoint().update(testUser1, cfg.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, "serviceType", "apiVersion", "serverUrl", "model", "apiKey", "keyId", "vaulted", "vaultedFields"}));

		AM7AgentTool agentTool = new AM7AgentTool(testUser1);
		AgentToolManager toolManager = new AgentToolManager(testUser1, cfg, agentTool);
		BaseRecord prompt = toolManager.getPlanPromptConfig("Demo Prompt");
		assertNotNull("Prompt config is null", prompt);
		// logger.info(prompt.toFullString());
		
		Chat chat = new Chat(testUser1, cfg, prompt);
		// OpenAIRequest req = new OpenAIRequest(ChatUtil.getCreateChatRequest(testUser1, "Rando Question 1", cfg, prompt));
		String chatName = "Rando Chat - " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, prompt);
		if(creq != null) {
			/// Fetch again since the create will only return the identifiers. 
			creq = ChatUtil.getChatRequest(testUser1, chatName, cfg, prompt);
		}
		OpenAIRequest req = ChatUtil.getOpenAIRequest(testUser1, new ChatRequest(creq));
		//logger.info(cfg.toFullString());
		
		//OpenAIRequest req = chat.getChatPrompt();
		
		chat.continueChat(req, "Who has red hair?");
		List<OpenAIMessage> msgs = req.getMessages();
		assertTrue("Expected at least three messages", msgs.size() >= 3);
		
		logger.info("Response: " + msgs.get(msgs.size() - 1).getContent());
		
		List<BaseRecord> steps = extractJSON(msgs.get(msgs.size() - 1).getContent());
		assertNotNull("Steps are null", steps);
		assertTrue("Expected at least one step", steps.size() > 0);
		logger.info(steps.get(0).toFullString());
		//logger.info(agentTool.summarizeModels().stream().collect(Collectors.joining(System.lineSeparator())));
		//logger.info(getModelDescriptions(false));
		//logger.info(SchemaUtil.getModelDescription(RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON)));
		///
		 
	}
	
	public static List<BaseRecord> extractJSON(final String contents){

		int fbai = contents.indexOf("{");
		String uconts = contents.substring(0, fbai + 1) + "\"schema\":\"tool.planStep\"," + contents.substring(fbai + 1, contents.length());
		int fai = uconts.indexOf("[");
		fbai = uconts.indexOf("{");
		int lai = uconts.lastIndexOf("]");
		int lbai = uconts.lastIndexOf("}");


		if(fai == -1 || fai > fbai) {
			uconts = "[" + uconts.substring(fbai, lbai + 1) + "]";
		}
		else {
			uconts = uconts.substring(fai, lai + 1);
		}
		logger.info("Extract JSON From: " + uconts);
		return JSONUtil.getList(uconts, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
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

			cfg.setValue("serviceType", LLMServiceEnumType.OPENAI);
			cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version"));
			cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server"));
			cfg.setValue("model", "gpt-4o");
			cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken"));
			
			cfg = IOSystem.getActiveContext().getAccessPoint().update(user, cfg);
		}
		catch(StackOverflowError | ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return cfg;
	}
	
	
}
