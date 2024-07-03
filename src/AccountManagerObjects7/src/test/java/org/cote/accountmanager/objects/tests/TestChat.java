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
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaExchange;
import org.cote.accountmanager.olio.llm.OllamaMessage;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.OllamaResponse;
import org.cote.accountmanager.olio.llm.OllamaUtil;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
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
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, "Don't Exist");
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.setValue(FieldNames.FIELD_LIMIT_FIELDS, false);
		DBStatementMeta meta = null;
		try {
			meta = StatementUtil.getSelectTemplate(q);
		} catch (ModelException | FieldException e) {
			logger.error(e);
		}
		assertNotNull("Meta is null", meta);
		ParameterList clist = ParameterList.newParameterList("path", "~/Chat");
		clist.parameter("name", "Chat Config - " + UUID.randomUUID().toString());

		ParameterList plist = ParameterList.newParameterList("path", "~/Chat");
		plist.parameter("name", "Prompt Config - " + UUID.randomUUID().toString());

		
		BaseRecord cfg = null;
		BaseRecord pcfg = null;
		try {
			cfg = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAT_CONFIG, testUser1, null, clist);
			pcfg = ioContext.getFactory().newInstance(ModelNames.MODEL_PROMPT_CONFIG, testUser1, null, plist);
		}
		catch(NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull(cfg);
		assertNotNull(pcfg);
		
		BaseRecord ipcfg = JSONUtil.importObject(ResourceUtil.getResource("olio/llm/prompt.config.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
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
			cfg.set("llmModel", "dolphin-llama3");
			
			
			BaseRecord cfg2 = ChatUtil.getCreateChatConfig(testUser1, cfg.get(FieldNames.FIELD_NAME));
			assertNotNull("Config is null", cfg2);

			String stempl = PromptUtil.getSystemChatPromptTemplate(pcfg, cfg2);
			String utempl = PromptUtil.getUserChatPromptTemplate(pcfg, cfg2);
			String atempl = PromptUtil.getAssistChatPromptTemplate(pcfg, cfg2);
			logger.info(stempl);
			logger.info(utempl);
			logger.info(atempl);
			
			String sessionName = "Demo Session";
			Chat chat = new Chat(testUser1, cfg, pcfg);
			chat.setSessionName(sessionName);
			OllamaRequest req = chat.getChatPrompt();
			String msn = ChatUtil.getSessionName(testUser1, cfg, pcfg, sessionName);
			assertTrue("Failed to save session " + sessionName, ChatUtil.saveSession(testUser1, req, sessionName));
			OllamaRequest sreq = ChatUtil.getSession(testUser1, sessionName);
			assertNotNull("Failed to retrieve session " + sessionName, sreq);
			/*
			Chat chat = new Chat(testUser1, cfg, pcfg);
			OllamaRequest req = chat.getChatPrompt();
			chat.chatConsole(req);
			*/
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		

	}
	private BaseRecord getPromptConfig(BaseRecord user) {
		ParameterList plist = ParameterList.newParameterList("path", "~/Chat");
		plist.parameter("name", "Prompt Config - " + UUID.randomUUID().toString());

		BaseRecord pcfg = null;
		BaseRecord opcfg = null;
		BaseRecord ipcfg = JSONUtil.importObject(ResourceUtil.getResource("olio/llm/prompt.config.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());

		try {
			pcfg = ioContext.getFactory().newInstance(ModelNames.MODEL_PROMPT_CONFIG, user, ipcfg, plist);
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
	
		OlioContext ctx = getContext(user);
		assertNotNull("Context is null", ctx);
		BaseRecord[] realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		BaseRecord popGrp = realms[0].get("population");
		assertNotNull("Expected a population group", popGrp);
		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(ctx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);

		BaseRecord per1 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord per2 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord inter = null;
		for(int i = 0; i < 10; i++) {
			inter = InteractionUtil.randomInteraction(ctx, per1, per2);
			if(inter != null) {
				break;
			}
		}
		
		BaseRecord[] locs = ctx.getLocations();
		BaseRecord levt = null;
		for(BaseRecord lrec : locs) {
			levt = ctx.startOrContinueLocationEpoch(lrec);
		}
		
		ParameterList clist = ParameterList.newParameterList("path", "~/Chat");
		clist.parameter("name", "Chat Config - " + UUID.randomUUID().toString());

		BaseRecord cfg = null;
		BaseRecord ocfg = null;
		String setting = NarrativeUtil.getRandomSetting();
		try {
			cfg = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAT_CONFIG, user, null, clist);
			cfg.set("rating", ESRBEnumType.AO);
			cfg.set("alignment", levt.get("alignment"));
			cfg.set("systemCharacter", per1);
			cfg.set("userCharacter", per2);
			cfg.set("interactions", Arrays.asList(new BaseRecord[] {inter}));
			cfg.set("assist", true);
			cfg.set("useNLP", false);
			cfg.set("prune", true);
			cfg.set("setting", null);
			cfg.set("includeScene", true);
			cfg.set("event", ctx.getCurrentIncrement());
			cfg.set("terrain", NarrativeUtil.getTerrain(ctx, per2));
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
	
	
	private OlioContext getContext(BaseRecord user) {
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		AuditUtil.setLogToConsole(false);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			testProperties.getProperty("test.datagen.path"),
			universeName,
			worldName,
			new String[] {},
			2,
			50,
			false,
			false
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new GridSquareLocationInitializationRule(),
			new LocationPlannerRule(),
			new GenericItemDataLoadRule()
		}));
		
		// Increment24HourRule incRule = new Increment24HourRule();
		// incRule.setIncrementType(TimeEnumType.HOUR);
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
			new Increment24HourRule(),
			new HierarchicalNeedsRule()
		}));

		OlioContext octx = new OlioContext(cfg);
		octx.initialize();
		assertNotNull("Root location is null", octx.getRootLocation());
		
		BaseRecord evt = octx.startOrContinueEpoch();
		BaseRecord levt = null;
		BaseRecord cevt = null;
		assertNotNull("Epoch is null", evt);
		BaseRecord[] locs = octx.getLocations();
		for(BaseRecord lrec : locs) {
			levt = octx.startOrContinueLocationEpoch(lrec);
			assertNotNull("Location epoch is null", levt);
			cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();

			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(octx, null, octx.getPopulation(lrec));
			ItemUtil.showerWithMoney(octx, octx.getPopulation(lrec));
			octx.processQueue();
		
		}

		BaseRecord[] realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		AuditUtil.setLogToConsole(true);
		return octx;
	}
	
	private boolean TestOllamaTags() {

		logger.info("Test Ollama");
		logger.warn("Note: Currently relies on ollama container running:");
		logger.warn("docker exec -it ollama ollama run dolphin-mistral");
		logger.warn("docker run -d -v ollama:/root/.ollama -p 11434:11434 --name ollama ollama/ollama");
		
		boolean obool = false;
		Response rep = ClientUtil.getResponse("http://localhost:11434/api/tags");
		if(rep.getStatus() == 200) {
			// JSONObject obj = rep.readEntity(JSONObject.class);
		     //StringReader stringReader = new StringReader(rep.readEntity(String.class)));
		     JSONObject obj = new JSONObject(rep.readEntity(String.class));
			//String objStr = rep.readEntity(String.class);
			if(obj != null) {
				obool = true;
			}
		}
		return obool;
	}
	
	
	private boolean TestOllamaGenerate() {
		boolean obool = false;
		String msg = "What is the largest mammal?";
		logger.info("Test Ollama Generate");
		OllamaRequest req = new OllamaRequest();
		req.setModel("dolphin-mistral");
		req.setPrompt(msg);
		req.setStream(false);
		OllamaResponse rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/generate"), req, MediaType.APPLICATION_JSON_TYPE);
		if(rep != null) {
			logger.info(rep.getResponse());
			req.setContext(rep.getContext());
			req.setPrompt("What is the smallest?");
			rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/generate"), req, MediaType.APPLICATION_JSON_TYPE);
			if(rep != null) {
				logger.info(rep.getResponse());
			}
			else {
				logger.error("Null response");
			}
		}
		return obool;
	}

	
	@Test
	public void TestChat() {
		logger.info("Test Chat Console");

	}
	
	private boolean TestOllamaChat() {
		logger.info("Test Ollama Chat");
		boolean obool = false;
		OllamaRequest req = new OllamaRequest();
		req.setModel("dolphin-mistral");
		req.setStream(false);
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent("My name is Silas McGee.  How are you today?");
		req.getMessages().add(msg);
		OllamaResponse rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/chat"), req, MediaType.APPLICATION_JSON_TYPE);
		if(rep != null) {
			logger.info(rep.getMessage().getContent());
			req.getMessages().add(rep.getMessage());
			OllamaMessage msg2 = new OllamaMessage();
			msg2.setRole("user");
			msg2.setContent("I shall call you Bubbles.  What would be a good last name for you, Bubbles?");
			req.getMessages().add(msg2);
			rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/chat"), req, MediaType.APPLICATION_JSON_TYPE);
			if(rep != null) {
				logger.info(rep.getMessage().getContent());
			}
			else {
				logger.error("Null response");
			}
		}
		else {
			logger.error("Null response");
		}
		return obool;
	}
	private String frontMatter = """

	""";

	private boolean TestPromptEng() {
		OllamaUtil ou = new OllamaUtil();
		OllamaExchange ex = ou.chat(frontMatter);
		if(ex.getResponse() != null) {
			logger.info(ex.getResponse().getMessage().getContent());
		}
		else {
			logger.error("Null response");
		}
		return false;
	}
}
