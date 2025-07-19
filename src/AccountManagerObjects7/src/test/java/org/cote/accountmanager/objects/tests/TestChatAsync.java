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
import org.cote.accountmanager.objects.tests.olio.MockWebSocket;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestChatAsync extends BaseTest {

	
	@Test
	public void TestAsynchronousChat() {
		logger.info("Test Asynchronous Chat");
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
		
		//OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);
		//req.setValue("stream", true);
		//addMessage(req, "user", "Hello Gruffy!");
		/*
		String flds = req.getFields().stream().map(f -> f.getName()).collect(Collectors.joining(", "));
		logger.info(flds);
		logger.info(JSONUtil.exportObject(ChatUtil.getPrunedRequest(req), RecordSerializerConfig.getHiddenForeignUnfilteredModule()));
	    */
		//Chat chat = new Chat(testUser1, cfg, pcfg);
		MockWebSocket mockWebSocket = new MockWebSocket(testUser1);
		creq.setValue("uid", UUID.randomUUID().toString());
		creq.setValue("message",  "Hi, Gruffy");
		mockWebSocket.sendMessageToServer(new ChatRequest(creq));
		//chat.setListener(mockWebSocket);
		//chat.continueChat(req, "Hello Gruffy! How are you today?");
	}
	

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
	
	
}
