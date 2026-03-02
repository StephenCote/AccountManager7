package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.junit.Test;

/// Diagnostic test for summarization buffer-mode streaming.
/// Exercises the exact code path used by ChatUtil.summarizeChunk() to
/// identify why thinking-model responses produce contentLength=null.
public class TestSummarizeStream extends BaseTest {

	private String loadCardFoxChunk() {
		Path txtPath = Path.of("./media/CardFox.txt");
		if (!Files.exists(txtPath)) {
			txtPath = Path.of("../AccountManagerObjects7/media/CardFox.txt");
		}
		if (!Files.exists(txtPath)) {
			return null;
		}
		try {
			String full = Files.readString(txtPath);
			/// Take first ~500 words as a realistic chunk
			String[] words = full.split("\\s+");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(500, words.length); i++) {
				if (i > 0) sb.append(" ");
				sb.append(words[i]);
			}
			return sb.toString();
		} catch (IOException e) {
			return null;
		}
	}

	/// Test 40: Reproduce summarizeChunk buffer-mode flow with real content.
	/// Uses the new simplified system prompt (no getChatPrompt).
	@Test
	public void TestSummarizeBufferDiag() {
		logger.warn("[LLM-LIVE] TestSummarizeBufferDiag: Requires reachable LLM server");
		logger.info("Test 40: Summarize Buffer Diagnostic - simplified prompt with CardFox content");

		BaseRecord testUser = getCreateUser("testSummarizeDiag");
		assertNotNull("Test user is null", testUser);

		LLMServiceEnumType llmType = LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase());
		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, llmType, "SummarizeDiag Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "SummarizeDiag Prompt");
		assertNotNull("Chat config is null", chatConfig);
		assertNotNull("Prompt config is null", promptConfig);

		String chunkContent = loadCardFoxChunk();
		if (chunkContent == null) {
			logger.warn("CardFox.txt not found - skipping");
			return;
		}
		logger.info("Chunk length: " + chunkContent.length() + " chars");

		/// Match what the FIXED summarizeChunk does: simple system prompt + newRequest
		Chat chat = new Chat(testUser, chatConfig, promptConfig);
		chat.setLlmSystemPrompt("You are a text summarization assistant. Produce concise, accurate summaries.");
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);

		String cmd = "Create a summary for the following using 300 words or less:\n" + chunkContent;
		chat.newMessage(req, cmd, Chat.userRole);

		logger.info("Sending simplified summarize request...");
		OpenAIResponse resp = chat.chat(req);

		logResponse("Simplified", resp);
		assertNotNull("Response should not be null in buffer mode", resp);
		assertNotNull("Response message should not be null", resp.getMessage());
		String content = resp.getMessage().getContent();
		assertNotNull("Content should not be null", content);
		assertTrue("Content should not be empty", content.length() > 0);
		logger.info("Test 40 PASSED: content length=" + content.length());
	}

	/// Test 41: Same content but using getChatPrompt() — the OLD code path.
	/// This is what the production server was using and may still be using.
	@Test
	public void TestSummarizeBufferOldPath() {
		logger.warn("[LLM-LIVE] TestSummarizeBufferOldPath: Requires reachable LLM server");
		logger.info("Test 41: Summarize Buffer OLD PATH - getChatPrompt() with CardFox content");

		BaseRecord testUser = getCreateUser("testSummarizeOldPath");
		assertNotNull("Test user is null", testUser);

		LLMServiceEnumType llmType = LLMServiceEnumType.valueOf(testProperties.getProperty("test.llm.type").toUpperCase());
		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, llmType, "SummarizeOldPath Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "SummarizeOldPath Prompt");
		assertNotNull("Chat config is null", chatConfig);
		assertNotNull("Prompt config is null", promptConfig);

		String chunkContent = loadCardFoxChunk();
		if (chunkContent == null) {
			logger.warn("CardFox.txt not found - skipping");
			return;
		}
		logger.info("Chunk length: " + chunkContent.length() + " chars");

		/// Reproduce OLD summarizeChunk: getChatPrompt() which loads full templates
		Chat chat = new Chat(testUser, chatConfig, promptConfig);
		OpenAIRequest req = chat.getChatPrompt();
		req.setStream(false);

		String cmd = "Create a summary for the following using 300 words or less:\n" + chunkContent;
		chat.newMessage(req, cmd, Chat.userRole);

		/// Log the serialized request to see what getChatPrompt produces
		String ser = JSONUtil.exportObject(req, RecordSerializerConfig.getHiddenForeignUnfilteredModule());
		int msgCount = ((java.util.List<?>) req.get("messages")).size();
		logger.info("OLD PATH wire request: " + msgCount + " messages, total JSON length=" + ser.length());

		logger.info("Sending OLD PATH summarize request...");
		OpenAIResponse resp = chat.chat(req);

		logResponse("OldPath", resp);
		if (resp == null || resp.getMessage() == null) {
			logger.error("Test 41 FAILED: resp=" + (resp != null ? "present" : "NULL")
				+ " message=" + (resp != null ? (resp.getMessage() != null ? "present" : "NULL") : "N/A"));
			logger.error("This confirms the OLD getChatPrompt() path is the problem");
		} else {
			String content = resp.getMessage().getContent();
			logger.info("Test 41 result: content=" + (content != null ? "len=" + content.length() : "NULL"));
		}
	}

	private void logResponse(String label, OpenAIResponse resp) {
		logger.info(label + " response: " + (resp != null ? "present" : "NULL"));
		if (resp != null) {
			BaseRecord msg = resp.get("message");
			logger.info(label + " message: " + (msg != null ? "present" : "NULL"));
			if (msg != null) {
				String content = msg.get("content");
				String thinking = msg.hasField("thinking") ? msg.get("thinking") : null;
				logger.info(label + " content: " + (content != null && !content.isEmpty()
					? "len=" + content.length() + " [" + content.substring(0, Math.min(200, content.length())) + "]"
					: (content != null ? "EMPTY STRING" : "NULL")));
				logger.info(label + " thinking: " + (thinking != null && !thinking.isEmpty()
					? "len=" + thinking.length() + " [" + thinking.substring(0, Math.min(200, thinking.length())) + "]"
					: "NULL/empty"));
			}
		}
	}
}
