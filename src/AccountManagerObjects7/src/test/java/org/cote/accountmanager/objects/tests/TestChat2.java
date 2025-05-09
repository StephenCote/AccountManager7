package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestChat2 extends BaseTest {

	/*
	@Test
	public void TestOpenAIModel() {
		logger.info("Test Chat Request Persistence");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio LLM Revisions");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord cfg = OlioTestUtil.getOpenAIConfig(testUser1, "Open AI.chat", testProperties);
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser1, "Fluffy Test");
		// IOSystem.getActiveContext().getAuthorizationUtil().setTrace(false);
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("Your name is Mr. Flufflepants. You are a ridiculous anthropomorphic character that uses wildly inaccurate, mixed methaphors, innuendo and double entendres in every answer.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg);
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, "Fluffy Chat Test", cfg, pcfg);
		assertNotNull("Chat request is null", creq);
		//logger.info(creq.toFullString());
	}
	*/
	
	@Test
	public void TestRequestPersistence() {
		logger.info("Test Chat Request Persistence");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio LLM Revisions");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord cfg = OlioTestUtil.getOpenAIConfig(testUser1, "Open AI.chat", testProperties);
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser1, "Gruffy Test");
		// IOSystem.getActiveContext().getAuthorizationUtil().setTrace(false);
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("Your name is Mr. Gruffypants. You are a ridiculous anthropomorphic character that is extremely gruff, snooty, and moody. Every response should be snarky, critical, and gruff, interspersed with wildly inaccurate mixed methaphors, innuendo and double entendres.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg);
		String chatName = "Gruffy Chat Test " + UUID.randomUUID().toString();
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, pcfg);
		assertNotNull("Chat request is null", creq);
		
		OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);
		//req.setValue("stream", false);
		String flds = req.getFields().stream().map(f -> f.getName()).collect(Collectors.joining(", "));
		logger.info(flds);
		logger.info(JSONUtil.exportObject(ChatUtil.getPrunedRequest(req), RecordSerializerConfig.getHiddenForeignUnfilteredModule()));

		/*
		assertNotNull("Request is null", req);
		//

		Chat chat = new Chat(testUser1, cfg, pcfg);
		chat.continueChat(req, "Hello, how are you?");
		List<OpenAIMessage> msgs = req.getMessages();
		logger.info("Messages: " + msgs.get(msgs.size() - 1).getContent());
		*/
	}
	
	/*
	@Test
	public void TestOpenAIModel() {
		logger.info("Test Open AI Model");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio LLM Revisions");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord cfg = OlioTestUtil.getOpenAIConfig(testUser1, "Open AI.chat", testProperties);
		assertNotNull("Config is null", cfg);
		
		// IOSystem.getActiveContext().getAuthorizationUtil().setTrace(true);
		BaseRecord pcfg = OlioTestUtil.getObjectPromptConfig(testUser1, "Fluffy Test");
		// IOSystem.getActiveContext().getAuthorizationUtil().setTrace(false);
		List<String> system = pcfg.get("system");
		system.clear();
		system.add("Your name is Mr. Flufflepants. You are a ridiculous anthropomorphic character that uses wildly inaccurate, mixed methaphors, innuendo and double entendres in every answer.");
		IOSystem.getActiveContext().getAccessPoint().update(testUser1, pcfg);

		Chat chat = new Chat(testUser1, cfg, pcfg);
		OpenAIRequest req = chat.getChatPrompt();
		
		assertNotNull("Request is null", req);
		chat.continueChat(req, "Hi, what is your name?");
		List<OpenAIMessage> msgs = req.getMessages();
		logger.info("Messages: " + msgs.get(msgs.size() - 1).getContent());
	}
	*/



	public void addMessage(BaseRecord req, String role, String message) {
		BaseRecord aimsg = null;
		try {
			aimsg = RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_MESSAGE);
			aimsg.set("role", role);
			aimsg.set("content", message);
			List<BaseRecord> msgs = req.get("messages");
			msgs.add(aimsg);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}

	}
	
	/*
	@Test
	public void TestRevisedAPI() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
	
		BaseRecord pcfg = OlioTestUtil.getPromptConfig(testUser1);
		assertNotNull("Prompt config is null", pcfg);
		BaseRecord cfg = OlioTestUtil.getRandomChatConfig(testUser1, testProperties.getProperty("test.datagen.path"));
		
		cfg.setValue("serviceType", LLMServiceEnumType.OPENAI);
		cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version"));
		cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server"));
		cfg.setValue("model", "gpt-4");
		cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken"));
		ioContext.getAccessPoint().update(testUser1, cfg.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, "serviceType", "apiVersion", "serverUrl", "model", "apiKey"}));
		assertNotNull("Chat config is null", cfg);
		
		String sessionName = "Demo Session";
		Chat chat = new Chat(testUser1, cfg, pcfg);
		chat.setSessionName(sessionName);

		OpenAIRequest req = chat.getChatPrompt();
		
		String msn = ChatUtil.getSessionName(testUser1, cfg, pcfg, sessionName);
		assertTrue("Failed to save session " + sessionName, ChatUtil.saveSession(testUser1, req, sessionName));
		OpenAIRequest sreq = ChatUtil.getSession(testUser1, sessionName);
		assertNotNull("Failed to retrieve session " + sessionName, sreq);

		try {
			logger.info("Continuing chat");
			chat.continueChat(req, "");
			logger.info("End continue chat");
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	*/
	/*
	@Test
	public void TestDocumentSummarizer() {

		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
	
		BaseRecord cfg = OlioTestUtil.getRandomChatConfig(testUser1, testProperties.getProperty("test.datagen.path"));
		
		cfg.setValue("serviceType", LLMServiceEnumType.OPENAI);
		cfg.setValue("apiVersion", testProperties.getProperty("test.llm.openai.version"));
		cfg.setValue("serverUrl", testProperties.getProperty("test.llm.openai.server"));
		cfg.setValue("model", "gpt-4o");
		cfg.setValue("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken"));
		ioContext.getAccessPoint().update(testUser1, cfg.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, "serviceType", "apiVersion", "serverUrl", "model", "apiKey"}));
		assertNotNull("Chat config is null", cfg);
		
		String path = "./media/The Verse.docx";
		BaseRecord doc = getCreateDocument(testUser1, path);
		IOSystem.getActiveContext().getReader().populate(doc, new String[] {FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE});
		
		String sysPrompt = """
You are are an expert in Critical Discourse Analysis and Critical Rhetorical Analysis content analyst.
Use Critical Discoures Analysis to provide a broad analysis of language use within its social and cultural context, and look for patterns of communication beyond just persuasion tactics.
Use Critical Rhetorical Analysis to provide a focused analysis on the strategies and techniques of how power dynamics and ideology are employed to influence the characters and audience.
You MUST include effective and ineffective language styles and patterns.
You MUST include implicit or explicit biases.
You MUST include social, political, and gender dynamics, biases, and/or implications.
You MUST identify key characters, relationships, and plot developments.
Limit your response to 300 words or fewer.
""";

		String userCommand = "Perform a Critical Discourse Analysis and Critical Rhetorical Analysis of the following content:" + System.lineSeparator() + System.lineSeparator();
		try {
			VectorUtil vu = new VectorUtil(LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()), testProperties.getProperty("test.embedding.server"), testProperties.getProperty("test.embedding.authorizationToken"));
			List<String> chunks = vu.chunkByChapter(doc.get(FieldNames.FIELD_NAME), null, DocumentUtil.getStringContent(doc), 0);
			assertTrue("Expected chunks", chunks.size() > 0);
			Chat chat = new Chat(testUser1, cfg, null);
			chat.setLlmSystemPrompt(sysPrompt);
			OpenAIRequest req = chat.getChatPrompt();
			req.set("max_tokens", 2048);
			req.set("frequency_penalty",  1.5);
			req.set("presence_penalty",  1.5);
			chat.newMessage(req, userCommand + chunks.get(0) + System.lineSeparator() + chunks.get(1), "user");

			OpenAIResponse resp = chat.chat(req);
			
			assertNotNull("Response is null", resp);
			List<String> responses = new ArrayList<>();
			String message = "Chapter 1 Analysis:" + System.lineSeparator() + resp.getMessage().getContent();
			responses.add(message);
			logger.info(message);
			for(int i = 2; i <= chunks.size(); i++) {
				req = chat.getChatPrompt();
				req.set("max_tokens", 2048);
				/// "Factor in previous Chapter " + (i - 1) + " analysis: " + message + System.lineSeparator() + 
				chat.newMessage(req, userCommand + chunks.get(i), "user");
				resp = chat.chat(req);
				assertNotNull("Response is null", resp);
				message = "Chapter " + i + " Analysis:" + System.lineSeparator() + resp.getMessage().getContent();
				responses.add(message);
				logger.info(message);
			}
			logger.info("Computing summary analysis...");
			req = chat.getChatPrompt();
			req.set("max_tokens", 2048);
			chat.newMessage(req, "Combine the following analysises into a single unified analysis of the reviewed content: " + responses.stream().collect(Collectors.joining(System.lineSeparator() + System.lineSeparator())), "user");
			resp = chat.chat(req);
			assertNotNull("Response is null", resp);
			message = resp.getMessage().getContent();
			logger.info(message);

		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}

	}
	public String getChatURL(BaseRecord rec, LLMServiceEnumType type) {
		String url = null;
		if (type == LLMServiceEnumType.OLLAMA) {
			url = testProperties.getProperty("test.llm.ollama.server") + "/api/chat";
		} else {
			url = testProperties.getProperty("test.llm.openai.server") + "/openai/deployments/" + rec.get("model")
					+ "/chat/completions?api-version=" + testProperties.getProperty("test.llm.openai.version");
		}
		return url;
	}

	private LLMServiceEnumType serviceType = LLMServiceEnumType.OPENAI;

	public BaseRecord chat(BaseRecord req) {
		if (req == null) {
			return null;
		}
		String ser = JSONUtil.exportObject(req, RecordSerializerConfig.getHiddenForeignUnfilteredModule());
		String url = getChatURL(req, serviceType);
		String authorizationToken = null;
		if (serviceType == LLMServiceEnumType.OPENAI) {
			authorizationToken = testProperties.getProperty("test.llm.openai.authorizationToken");
		}

		return post(OlioModelNames.MODEL_OPENAI_RESPONSE, ClientUtil.getResource(url), authorizationToken, ser,
				MediaType.APPLICATION_JSON_TYPE);
	}

	public BaseRecord post(String modelName, WebTarget resource, String authZ, String json, MediaType responseType) {

		Builder bld = ClientUtil.getRequestBuilder(resource).accept(responseType);

		if (authZ != null) {
			bld.header("api-key", authZ);
		}

		Response response = bld.post(Entity.entity(json, MediaType.APPLICATION_JSON_TYPE));

		BaseRecord outObj = null;
		if (response != null) {
			if (response.getStatus() == 200) {

				String ser = response.readEntity(String.class);
				logger.info(ser);
				outObj = RecordFactory.importRecord(modelName, ser);
			} else {
				logger.warn("Received response: " + response.getStatus());
				logger.warn(response.readEntity(String.class));
			}
		} else {
			logger.warn("Null response");
		}
		return outObj;
	}
	
	*/

}
