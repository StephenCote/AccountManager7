package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.json.JSONObject;
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
	
	@Test
	public void TestRandomChatConfig() {
		logger.info("Test LLM Chat Config");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
	
		BaseRecord pcfg = getPromptConfig(testUser1);
		assertNotNull("Prompt config is null", pcfg);
		BaseRecord cfg = getRandomChatConfig(testUser1);
		assertNotNull("Chat config is null", cfg);
		
		try {
			cfg.set("llmModel", "fim-local");
			
			
			BaseRecord cfg2 = ChatUtil.getCreateChatConfig(testUser1, cfg.get(FieldNames.FIELD_NAME));
			assertNotNull("Config is null", cfg2);

			String stempl = PromptUtil.getSystemChatPromptTemplate(pcfg, cfg2);
			String utempl = PromptUtil.getUserChatPromptTemplate(pcfg, cfg2);
			String atempl = PromptUtil.getAssistChatPromptTemplate(pcfg, cfg2);
			
			String sessionName = "Demo Session";
			Chat chat = new Chat(testUser1, cfg, pcfg);
			chat.setSessionName(sessionName);
			OllamaRequest req = chat.getChatPrompt();

			String msn = ChatUtil.getSessionName(testUser1, cfg, pcfg, sessionName);
			assertTrue("Failed to save session " + sessionName, ChatUtil.saveSession(testUser1, req, sessionName));
			OllamaRequest sreq = ChatUtil.getSession(testUser1, sessionName);
			assertNotNull("Failed to retrieve session " + sessionName, sreq);
			
			chat.continueChat(req, "Hi");
			
			logger.info(JSONUtil.exportObject(req));
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		

	}
	private BaseRecord getPromptConfig(BaseRecord user) {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, "Prompt Config - " + UUID.randomUUID().toString());

		BaseRecord pcfg = null;
		BaseRecord opcfg = null;
		BaseRecord ipcfg = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/llm/prompt.config.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());

		try {
			pcfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, ipcfg, plist);
			opcfg = ioContext.getAccessPoint().create(user, pcfg);
		}
		catch(NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return opcfg;
	}

	public BaseRecord getRandomChatConfig(BaseRecord user) {
		logger.info("Test LLM Chat Config");
	
		OlioContext ctx = OlioContextUtil.getGridContext(user, testProperties.getProperty("test.datagen.path"), universeName, worldName, false);
		assertNotNull("Context is null", ctx);
		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord popGrp = realms.get(0).get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Expected a population group", popGrp);
		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(ctx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);
		
		List<BaseRecord> rlms = ctx.getRealms();
		for(BaseRecord r : rlms) {

			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(ctx, null, pop);
			ItemUtil.showerWithMoney(ctx, pop);
			Queue.processQueue();
			
		}

		BaseRecord per1 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord per2 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord inter = null;
		for(int i = 0; i < 10; i++) {
			inter = InteractionUtil.randomInteraction(ctx, per1, per2);
			if(inter != null) {
				break;
			}
		}

		BaseRecord levt = ctx.realmClock(ctx.getRealms().get(0)).getIncrement();
		ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		clist.parameter(FieldNames.FIELD_NAME, "Chat Config - " + UUID.randomUUID().toString());

		BaseRecord cfg = null;
		BaseRecord ocfg = null;
		String setting = NarrativeUtil.getRandomSetting();
		try {
			cfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, clist);
			cfg.set("rating", ESRBEnumType.E);
			cfg.set("alignment", levt.get("alignment"));
			cfg.set("systemCharacter", per1);
			cfg.set("userCharacter", per2);
			cfg.set(OlioFieldNames.FIELD_INTERACTIONS, Arrays.asList(new BaseRecord[] {inter}));
			cfg.set("assist", true);
			cfg.set("useNLP", false);
			cfg.set("prune", true);
			cfg.set("setting", null);
			cfg.set("includeScene", true);
			cfg.set("event", ctx.clock().getIncrement());
			cfg.set(FieldNames.FIELD_TERRAIN, NarrativeUtil.getTerrain(ctx, per2));
			cfg.set("systemNarrative", NarrativeUtil.getNarrative(ctx, per1, setting));
			cfg.set("userNarrative", NarrativeUtil.getNarrative(ctx, per2, setting));
			NarrativeUtil.describePopulation(ctx, cfg);
			ocfg = ioContext.getAccessPoint().create(user, cfg);
			
		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("CFG was null", ocfg);
		
		return ocfg;
	
	}
	
	

	

}
