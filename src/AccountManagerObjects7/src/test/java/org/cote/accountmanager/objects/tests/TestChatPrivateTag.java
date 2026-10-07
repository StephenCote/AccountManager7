package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

/**
 * Character internal dialog in <private>...</private> must be hidden from displayContent and flagged
 * via hasThoughts, exactly like <think>/<thought>. *Asterisk* emotes are meant to stay visible.
 */
public class TestChatPrivateTag extends BaseTest {

	@Test
	public void TestPrivateTagHiddenFromDisplayContent() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		BaseRecord testUser = ioContext.getFactory().getCreateUser(testOrgContext.getAdminUser(), "privtaguser1", testOrgContext.getOrganizationId());

		BaseRecord chatCfg = OlioTestUtil.getChatConfig(testUser,
				LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase()),
				"Private Tag Chat - " + UUID.randomUUID().toString(), testProperties);
		assertNotNull("Chat config should be created", chatCfg);
		BaseRecord promptCfg = OlioTestUtil.getPromptConfig(testUser, "Private Tag Prompt");
		assertNotNull("Prompt config should be created", promptCfg);

		String requestName = "Private Tag Request - " + UUID.randomUUID().toString();
		assertNotNull(ChatUtil.getCreateChatRequest(testUser, requestName, chatCfg, promptCfg));
		ChatRequest chatReq = new ChatRequest(ChatUtil.getChatRequest(testUser, requestName, chatCfg, promptCfg));

		/// Build the history directly (no LLM call): template messages, then one real exchange.
		/// The assertions target the LAST message, so they hold whichever startIndex getChatResponse picks.
		OpenAIRequest oreq = new OpenAIRequest();
		oreq.addMessage(msg(Chat.systemRole, "system prompt"));
		oreq.addMessage(msg(Chat.userRole, "user intro template"));
		oreq.addMessage(msg(Chat.assistantRole, "assistant intro template"));
		oreq.addMessage(msg(Chat.userRole, "Hello Rebecca."));
		String reply = "\"Hi Stephen!\" <private>I'm assessing his\nsocial skills</private> *smiles warmly* \"Nice to meet you.\" <PRIVATE>second note</PRIVATE>";
		oreq.addMessage(msg(Chat.assistantRole, reply));

		ChatResponse rep = ChatUtil.getChatResponse(testUser, oreq, chatReq);
		assertNotNull("Chat response should not be null", rep);
		List<BaseRecord> msgs = rep.getMessages();
		assertTrue("Response should contain messages", msgs.size() > 0);
		BaseRecord last = msgs.get(msgs.size() - 1);

		assertEquals("Raw content is preserved for the show-thoughts toggle", reply, last.get("content"));
		assertTrue("hasThoughts must be set for <private>", (boolean) last.get("hasThoughts"));
		String display = last.get("displayContent");
		assertNotNull("displayContent should be populated", display);
		logger.info("displayContent: " + display);
		assertFalse("private text (multiline) must be hidden", display.contains("assessing"));
		assertFalse("upper-case tag must be hidden too", display.contains("second note"));
		assertFalse("no tag remnants", display.toLowerCase().contains("private>"));
		assertTrue("*emotes* must stay visible", display.contains("*smiles warmly*"));
		assertTrue("speech must stay visible", display.contains("\"Hi Stephen!\"") && display.contains("\"Nice to meet you.\""));

		/// Control: a message with only an emote must not be flagged
		OpenAIMessage plainUser = msg(Chat.userRole, "How are you?");
		OpenAIMessage plain = msg(Chat.assistantRole, "*waves* \"Fine, thanks.\"");
		oreq.addMessage(plainUser);
		oreq.addMessage(plain);
		ChatResponse rep2 = ChatUtil.getChatResponse(testUser, oreq, chatReq);
		List<BaseRecord> msgs2 = rep2.getMessages();
		BaseRecord last2 = msgs2.get(msgs2.size() - 1);
		assertFalse("emote-only message must not be flagged as having thoughts", (boolean) last2.get("hasThoughts"));
		assertEquals("*waves* \"Fine, thanks.\"", last2.get("displayContent"));
	}

	private static OpenAIMessage msg(String role, String content) {
		OpenAIMessage m = new OpenAIMessage();
		m.setRole(role);
		m.setContent(content);
		return m;
	}
}
