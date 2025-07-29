package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

public class TestChat extends BaseTest {
	private String universeName = "Universe 3";
	private String worldName = "World 3";
	private String worldPath = "~/Worlds";
	
	@Test
	public void TestChatConfigModels() {
		logger.info("Test LLM Chat Config Models");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Chat", "DATA", testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, "Don't Exist");
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.planMost(true);
		DBStatementMeta meta = null;
		try {
			meta = StatementUtil.getSelectTemplate(q);
		} catch (ModelException | FieldException e) {
			logger.error(e);
		}
		assertNotNull("Meta is null", meta);
		ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		clist.parameter(FieldNames.FIELD_NAME, "Chat Config - " + UUID.randomUUID().toString());

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "Prompt Config - " + UUID.randomUUID().toString());

		
		BaseRecord cfg = null;
		BaseRecord pcfg = null;
		try {
			cfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, testUser1, null, clist);
			pcfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, testUser1, null, plist);
		}
		catch(NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull(cfg);
		assertNotNull(pcfg);
		
		BaseRecord ipcfg = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/llm/prompt.config.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Imported object was null", ipcfg);
		
	}
	
	private List<BaseRecord> getEpisodes() throws FieldException, ModelNotFoundException, ValueException{
		List<BaseRecord> episodes = new ArrayList<BaseRecord>();
		BaseRecord ep1 = RecordFactory.newInstance(OlioModelNames.MODEL_EPISODE);
		ep1.set(FieldNames.FIELD_NAME, "Episode 1");
		ep1.set("theme", "${user.firstName} is from outer space");
		ep1.set("duration", 60);
		ep1.set("number", 1);
		List<String> stages = ep1.get("stages");
		stages.add("${system.firstName} makes a candid remark that ${user.firstName} looks out of this world.");
		stages.add("${system.firstName} notices little differences about ${user.firstName}.");
		stages.add("${system.firstName} wonders aloud if space aliens exist.");
		stages.add("${system.firstName} asks ${user.firstName} if ${user.pro} is an alien.");
		episodes.add(ep1);
		return episodes;
	}
	
	@Test
	public void TestRandomChatConfig() {
		logger.info("Test LLM Chat Config");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
	
		BaseRecord pcfg = OlioTestUtil.getPromptConfig(testUser1, "Test Prompt - " + UUID.randomUUID().toString());
		assertNotNull("Prompt config is null", pcfg);
		BaseRecord cfg = OlioTestUtil.getRandomChatConfig(testUser1, testProperties.getProperty("test.datagen.path"));
		assertNotNull("Chat config is null", cfg);
		
		try {
			cfg.set("serviceType", LLMServiceEnumType.OPENAI);
			cfg.set("apiVersion", testProperties.getProperty("test.llm.openai.version"));
			cfg.set("serverUrl", testProperties.getProperty("test.llm.openai.server"));
			cfg.set("model", "gpt-4o");
			cfg.set("apiKey", testProperties.getProperty("test.llm.openai.authorizationToken"));
			cfg.set("useNLP", true);
			cfg.set("nlpCommand", "Convince ${user.firstName} ${user.pro} is from outer space.");
			cfg.set("rating",  ESRBEnumType.RC);
			cfg.set("episodes", getEpisodes());
			IOSystem.getActiveContext().getRecordUtil().updateRecord(cfg);			
			
			BaseRecord cfg2 = ChatUtil.getCreateChatConfig(testUser1, cfg.get(FieldNames.FIELD_NAME));
			assertNotNull("Config is null", cfg2);

			String stempl = PromptUtil.getSystemChatPromptTemplate(pcfg, cfg2);
			String utempl = PromptUtil.getUserChatPromptTemplate(pcfg, cfg2);
			String atempl = PromptUtil.getAssistChatPromptTemplate(pcfg, cfg2);
			
			logger.info(stempl);
			logger.info(atempl);
			logger.info(utempl);
			
			/*
			Chat chat = new Chat(testUser1, cfg, pcfg);
			OpenAIRequest req = chat.getChatPrompt();

			chat.continueChat(req, "Hi");
			
			logger.info(JSONUtil.exportObject(req));
			*/
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		

	}



	
	

	

}
