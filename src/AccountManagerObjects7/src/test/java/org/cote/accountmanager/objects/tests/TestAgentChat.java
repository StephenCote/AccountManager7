package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.ScriptUtil;
import org.graalvm.polyglot.Value;
import org.junit.Test;

public class TestAgentChat extends BaseTest {
	
	/*
	@Test
	public void TestAgentScript() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String script = ResourceUtil.getInstance().getResource("./agentCRUD.js");
		assertNotNull("Script is null");
		Map<String, Object> params = new HashMap<>();
		//params.put("test", model);
		String request = "{\"model\": \"olio.charPerson\", \"fields\": [\"firstName\", \"lastName\", \"description\", \"traits\", \"attributes\"]}";
		
		String header = ScriptUtil.mapAndConvertParameters(new BaseRecord[] {});
		logger.info(header);
		Value val = null;
		try {
			val = ScriptUtil.run(header + script, params);
		} catch (ScriptException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertNotNull("Script return value is null", val);
		
	}
	*/
	
	@Test
	public void TestSchemaPrompt() {
		/// WORK IN PROGRESS
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String dataPath = testProperties.getProperty("test.datagen.path");

		
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
		q.planMost(false);
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_CHAR_PERSON);
		//logger.info(JSONUtil.exportObject(ms));
		//logger.info(getModels(false));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.field(FieldNames.FIELD_FIRST_NAME, "Bob");
		logger.info(q.toFullString());
		OlioContext ctx = OlioTestUtil.getContext(orgContext, dataPath);
		String agenticQuestion = "Who is Laurel? Describe her in detail.";
		
		
		String prompt = "Answer all questions by identifying an operation (CREATE, READ, UPDATE, DELETE, LIST, MEMBER) and selecting the most appropriate models from the following list:" + System.lineSeparator() + SchemaUtil.getModelDescriptions(false) + System.lineSeparator() + "Format all responses in JSON using the following example syntax: {\"operation\": \"READ\", \"models\": [\"data.data\"]}"  + System.lineSeparator() + "Example:" + System.lineSeparator() + "(user)Tell me about the character Bob?"+ System.lineSeparator() + "(assistant) {\"operation\": \"READ\", \"models\": [\"olio.charPerson\"]}";
		String response = getChatResponse(testUser1, "Agentic.prompt", "Open AI.chat", prompt, agenticQuestion);
		logger.info("RESPONSE 1: '" + response + "'");
		
		//String prompt2 = "Identify which of the following model fields should be used to effectively answer the question:" + System.lineSeparator() + getModelDescription(ms) + System.lineSeparator() + "Format all responses in JSON using the following example syntax: {\"model\": \"data.data\", \"fields\": [\"name\", \"description\"]}"  + System.lineSeparator() + "Example:" + System.lineSeparator() + "(user)Tell me about the character Bob?"+ System.lineSeparator() + "(assistant) {\"model\": \"olio.charPerson\", \"fields\": [\"firstName\", \"lastName\", \"eyeColor\", \"hairColor\"]}";
		String contextInfo = "Scope Information:" + System.lineSeparator() + "organizationId=" + testUser1.get(FieldNames.FIELD_ORGANIZATION_ID) + System.lineSeparator(); 
		String queryPrompt = "Compose a query using the following query schema definitions for a Query, a QueryField, and a QueryPlan (used for nested queries):" + System.lineSeparator() + SchemaUtil.getModelDescription(RecordFactory.getSchema(ModelNames.MODEL_QUERY)) + System.lineSeparator() + SchemaUtil.getModelDescription(RecordFactory.getSchema(ModelNames.MODEL_QUERY_FIELD)) + System.lineSeparator() + SchemaUtil.getModelDescription(RecordFactory.getSchema(ModelNames.MODEL_QUERY_PLAN));			
		String queryExample = """
		Example Query Output: 
		{
			  "schema" : "io.query",
			  "comparator" : "group_and",
			  "fields" : [ {
			    "comparator" : "equals",
			    "name" : "organizationId",
			    "value" : 4
			  }, {
			    "comparator" : "equals",
			    "name" : "firstName",
			    "value" : "Bob"
			  } ],
			  "order" : "ascending",
			  "request" : [ "eyeColor", "hairColor", "hairStyle", "trades", "traits", "store", "statistics", "instinct", "narrative", "state", "race", "ethnicity", "otherEthnicity", "firstName", "middleName", "lastName", "title", "suffix", "birthDate", "age", "gender", "alias", "prefix", "users", "accounts", "partners", "dependents", "siblings", "socialRing", "notes", "personality", "behavior", "profile", "groupId", "groupPath", "name", "ownerId", "objectId", "organizationPath", "organizationId", "id", "urn", "description", "contactInformation", "alignment" ],
			  "type" : "olio.charPerson"
			}
""";
		String prompt2 = "Compose a query to find data related to the question." + System.lineSeparator() + "Use the following schema definition to identify which fields to request and search for:" + System.lineSeparator() + contextInfo + System.lineSeparator() + queryPrompt + System.lineSeparator() + queryExample;
		String response2 = getChatResponse(testUser1, "Agentic.prompt 2", "Open AI.chat", prompt2, agenticQuestion);
		logger.info("RESPONSE 2: '" + response2 + "'");		

		
		
		//logger.info(getModelDescription(ms));
	}
	
	
	private String getChatResponse(BaseRecord user, String promptName, String configName, String prompt, String message) {
		BaseRecord cfg = OlioTestUtil.getOpenAIConfig(user, configName, testProperties);
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(user, promptName);
		if(prompt != null) {
			List<String> system = pcfg.get("system");
			system.clear();
			system.add(prompt);
		}
		IOSystem.getActiveContext().getAccessPoint().update(user, pcfg);
		String chatName = "Gruffy Chat Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(user, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);
		
		OpenAIRequest req = ChatUtil.getChatSession(user, chatName, cfg, pcfg);
		//String flds = req.getFields().stream().map(f -> f.getName()).collect(Collectors.joining(", "));
		// logger.info(flds);
		// logger.info(JSONUtil.exportObject(ChatUtil.getPrunedRequest(req), RecordSerializerConfig.getHiddenForeignUnfilteredModule()));
		
		assertNotNull("Request is null", req);
		
	
		Chat chat = new Chat(user, cfg, pcfg);
		chat.continueChat(req, message);
		List<OpenAIMessage> msgs = req.getMessages();
		String msg = null;
		if (msgs.size() > 0) {
			msg = msgs.get(msgs.size() - 1).getContent();
		}
		return msg;
	}
	
	
	/*
	/// Copied from TestChat2 to use/refactor into a utility
	@Test
	public void TestRequestPersistence() {
		logger.info("Test Chat Request Persistence");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio LLM Revisions");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord cfg = OlioTestUtil.getOpenAIConfig(testUser1, "Open AI.chat", testProperties);
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser1, "Gruffy Test");
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("Your name is Mr. Gruffypants. You are a ridiculous anthropomorphic character that is extremely gruff, snooty, and moody. Every response should be snarky, critical, and gruff, interspersed with wildly inaccurate mixed methaphors, innuendo and double entendres.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg);
		String chatName = "Gruffy Chat Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);
		
		OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);
		//String flds = req.getFields().stream().map(f -> f.getName()).collect(Collectors.joining(", "));
		// logger.info(flds);
		// logger.info(JSONUtil.exportObject(ChatUtil.getPrunedRequest(req), RecordSerializerConfig.getHiddenForeignUnfilteredModule()));
		
		assertNotNull("Request is null", req);
		

		Chat chat = new Chat(testUser1, cfg, pcfg);
		chat.continueChat(req, "Hello, how are you?");
		List<OpenAIMessage> msgs = req.getMessages();
		logger.info("Messages: " + msgs.get(msgs.size() - 1).getContent());
	}
	*/
}
