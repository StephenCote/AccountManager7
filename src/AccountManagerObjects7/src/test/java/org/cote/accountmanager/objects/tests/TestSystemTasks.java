package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.SystemTaskEnumType;
import org.cote.accountmanager.util.SystemTaskUtil;
import org.junit.Test;

public class TestSystemTasks extends BaseTest {

	@Test
	public void TestSystemChatTask() {
		logger.info("Testing System Task - Chat");
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

		BaseRecord pcfg = OlioTestUtil.getPromptConfig(testUser1);
		Chat chat = new Chat(testUser1, cfg, pcfg);

		OpenAIRequest req = chat.getChatPrompt();
		
		BaseRecord task = OlioTaskAgent.createTaskRequest(req, cfg.copyRecord(new String[]{"apiVersion", "serviceType", "serverUrl", "apiKey", "model"}));
		String ser = task.toFullString();

		BaseRecord itask = BaseRecord.importRecord(ser);
		assertNotNull("Imported task was null", itask);
		/*
		
		BaseRecord resp = OlioTaskAgent.evaluateTaskResponse(task);
		assertNotNull("Response is null", resp);
		assertTrue("Expected the response to be INFO", resp.getEnum("responseType") == ResponseEnumType.INFO);
		logger.info(resp.toFullString());
		*/
		/// 1) Make sure queue is set to local
		ioContext.getTaskQueue().setRemotePoll(false);
		/// 2) Add the task request to the queue
		assertTrue("Expected the task to be pended", SystemTaskUtil.pendTask(task));
		/// 
		BaseRecord resp = null;
		long maxTimeMS = 300000;
		long waited = 0L;
		long delay = 3000;
		while(resp == null) {
			if(waited >= maxTimeMS) {
				logger.error("Exceeded maximum wait threshhold");
				break;
			}
			resp = SystemTaskUtil.getResponse(task.get("id"));
			//logger.info("Checking status of " + task.get("id"));
			//SystemTaskUtil.dumpTasks();
			if(resp == null) {
				try{
					Thread.sleep(delay);
					waited += delay;
				}
				catch (InterruptedException ex){
					/* ... */
				}				
			}
		}
		assertNotNull("Response was null", resp);
		logger.info(resp.toFullString());
	}
	

	
}
