package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.McpContext;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.mcp.McpContextFilter;
import org.cote.accountmanager.mcp.McpContextParser;
import org.cote.accountmanager.mcp.McpFilterResult;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/**
 * Tests for MCP integration with the chat pipeline.
 * Verifies that citations, reminders, and keyframes produce MCP context blocks
 * instead of the old <citation>, (Reminder:...), (KeyFrame:...) formats.
 * Also tests backward compatibility with old format markers.
 */
public class TestMcpChatIntegration extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpChatIntegration.class);

	// =========================================================================
	// ChatUtil.getCitationText() tests
	// =========================================================================

	@Test
	public void TestCitationTextMcpFormat() {
		logger.info("TestCitationTextMcpFormat");

		BaseRecord chunk = createTestVectorChunk("Test chunk content about cardinals.", 3, "Chapter One", "CardFox.pdf");

		String result = ChatUtil.getCitationText(chunk, "chunk", true);
		assertNotNull("Citation text should not be null", result);
		assertTrue("Should contain mcp:context tag", result.contains("<mcp:context"));
		assertTrue("Should contain resource type", result.contains("type=\"resource\""));
		assertTrue("Should be ephemeral", result.contains("ephemeral=\"true\""));
		assertTrue("Should contain search-result schema", result.contains("urn:am7:vector:search-result"));
		assertTrue("Should contain content data", result.contains("Test chunk content about cardinals."));
		assertTrue("Should contain closing tag", result.contains("</mcp:context>"));
		assertFalse("Should NOT contain old citation format", result.contains("<citation"));
	}

	@Test
	public void TestCitationTextRawContent() {
		logger.info("TestCitationTextRawContent");

		BaseRecord chunk = createTestVectorChunk("Raw content text.", 1, "", "doc.pdf");

		String result = ChatUtil.getCitationText(chunk, "chunk", false);
		assertNotNull("Raw content should not be null", result);
		assertTrue("Should contain raw content", result.contains("Raw content text."));
		assertFalse("Should NOT contain mcp:context", result.contains("<mcp:context"));
		assertFalse("Should NOT contain citation", result.contains("<citation"));
	}

	@Test
	public void TestCitationTextNullContent() {
		logger.info("TestCitationTextNullContent");

		BaseRecord chunk = createTestVectorChunk(null, 0, "", "doc.pdf");

		String result = ChatUtil.getCitationText(chunk, "chunk", true);
		assertNull("Should return null for null content", result);
	}

	@Test
	public void TestCitationTextEmptyContent() {
		logger.info("TestCitationTextEmptyContent");

		BaseRecord chunk = createTestVectorChunk("", 0, "", "doc.pdf");

		String result = ChatUtil.getCitationText(chunk, "chunk", true);
		assertNull("Should return null for empty content", result);
	}

	@Test
	public void TestCitationTextRoundTrip() {
		logger.info("TestCitationTextRoundTrip");

		BaseRecord chunk = createTestVectorChunk("Round trip content.", 5, "Ch2", "verse.docx");

		String mcpBlock = ChatUtil.getCitationText(chunk, "chunk", true);
		assertNotNull(mcpBlock);

		/// Parse the MCP block and verify structure
		List<McpContext> contexts = McpContextParser.parse(mcpBlock);
		assertEquals("Should parse exactly one context", 1, contexts.size());

		McpContext ctx = contexts.get(0);
		assertEquals("Type should be resource", "resource", ctx.getType());
		assertTrue("Should be ephemeral", ctx.isEphemeral());
		assertNotNull("URI should not be null", ctx.getUri());
		assertTrue("Body should contain content", ctx.getBody().contains("Round trip content."));

		/// Filter and verify categorization as citation
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult filterResult = filter.filter(mcpBlock);
		assertTrue("Should have at least one citation", filterResult.getCitations().size() >= 1);
	}

	// =========================================================================
	// ChatUtil.getFilteredCitationText() deduplication tests
	// =========================================================================

	@Test
	public void TestFilteredCitationTextNoDuplicate() {
		logger.info("TestFilteredCitationTextNoDuplicate");

		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");
		addMessage(req, "user", "Hello");

		BaseRecord chunk = createTestVectorChunk("Unique content not in messages.", 1, "", "doc.pdf");

		String result = ChatUtil.getFilteredCitationText(req, chunk, "chunk");
		assertNotNull("Should return citation for non-duplicate", result);
		assertTrue("Should be MCP format", result.contains("<mcp:context"));
	}

	@Test
	public void TestFilteredCitationTextDuplicate() {
		logger.info("TestFilteredCitationTextDuplicate");

		String duplicateContent = "This content already exists in chat.";
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");
		addMessage(req, "user", "Message with " + duplicateContent);

		BaseRecord chunk = createTestVectorChunk(duplicateContent, 1, "", "doc.pdf");

		String result = ChatUtil.getFilteredCitationText(req, chunk, "chunk");
		assertNull("Should return null for duplicate content", result);
	}

	// =========================================================================
	// ChatUtil.getFormattedChatHistory() tests
	// =========================================================================

	@Test
	public void TestFormattedHistorySkipsMcpKeyframe() {
		logger.info("TestFormattedHistorySkipsMcpKeyframe");

		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");
		addMessage(req, "user", "First message");

		/// Add an MCP keyframe message
		McpContextBuilder kfBuilder = new McpContextBuilder();
		kfBuilder.addKeyframe("am7://keyframe/test-123", Map.of("summary", "Test summary", "analysis", "Test analysis"));
		addMessage(req, "system", kfBuilder.build());

		addMessage(req, "user", "Second message");

		List<String> history = ChatUtil.getFormattedChatHistory(req, null, -1, true);
		for(String line : history) {
			assertFalse("History should not contain MCP keyframe", line.contains("<mcp:context") && line.contains("/keyframe/"));
		}
		/// Should still contain the actual user messages
		boolean hasFirst = history.stream().anyMatch(h -> h.contains("First message"));
		boolean hasSecond = history.stream().anyMatch(h -> h.contains("Second message"));
		assertTrue("Should contain first message", hasFirst);
		assertTrue("Should contain second message", hasSecond);
	}

	@Test
	public void TestFormattedHistorySkipsOldKeyframe() {
		logger.info("TestFormattedHistorySkipsOldKeyframe");

		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");
		addMessage(req, "user", "Message A");
		addMessage(req, "system", "(KeyFrame: (Summary of Alice and Bob with M-rated content) analysis text)");
		addMessage(req, "user", "Message B");

		List<String> history = ChatUtil.getFormattedChatHistory(req, null, -1, true);
		for(String line : history) {
			assertFalse("History should not contain old KeyFrame", line.contains("(KeyFrame:"));
		}
	}

	@Test
	public void TestFormattedHistoryStripsMcpReminder() {
		logger.info("TestFormattedHistoryStripsMcpReminder");

		McpContextBuilder remBuilder = new McpContextBuilder();
		remBuilder.addReminder("am7://reminder/test", List.of(Map.of("key", "test", "value", "remember this")));
		String mcpReminder = remBuilder.build();

		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");
		addMessage(req, "user", "User question" + mcpReminder);

		List<String> history = ChatUtil.getFormattedChatHistory(req, null, -1, true);
		boolean hasUserQuestion = history.stream().anyMatch(h -> h.contains("User question"));
		assertTrue("Should contain user question text", hasUserQuestion);

		for(String line : history) {
			assertFalse("History should not contain MCP reminder blocks", line.contains("<mcp:context"));
		}
	}

	@Test
	public void TestFormattedHistoryStripsOldReminder() {
		logger.info("TestFormattedHistoryStripsOldReminder");

		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");
		addMessage(req, "user", "My question text(Reminder: Stay in character)");

		List<String> history = ChatUtil.getFormattedChatHistory(req, null, -1, true);
		boolean hasQuestion = history.stream().anyMatch(h -> h.contains("My question text"));
		assertTrue("Should contain question text", hasQuestion);
		for(String line : history) {
			assertFalse("History should not contain old Reminder", line.contains("(Reminder"));
		}
	}

	// =========================================================================
	// MCP format verification for keyframes and reminders
	// =========================================================================

	@Test
	public void TestKeyframeMcpBlockStructure() {
		logger.info("TestKeyframeMcpBlockStructure");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addKeyframe("am7://keyframe/cfg-001", Map.of(
			"summary", "Summary of Alice and Bob with M/PG-13-rated content",
			"analysis", "Alice greeted Bob warmly.",
			"rating", "M",
			"ratingMpa", "PG-13",
			"characters", "Alice and Bob"
		));
		String block = builder.build();

		assertNotNull(block);
		assertTrue("Should contain mcp:context", block.contains("<mcp:context"));
		assertTrue("Should contain keyframe URI", block.contains("am7://keyframe/cfg-001"));
		assertTrue("Should contain keyframe schema", block.contains("urn:am7:narrative:keyframe"));
		assertTrue("Should contain analysis text", block.contains("Alice greeted Bob warmly."));

		/// Verify it parses and categorizes correctly
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(block);
		assertEquals("Should have one keyframe", 1, result.getKeyframes().size());
	}

	@Test
	public void TestReminderMcpBlockStructure() {
		logger.info("TestReminderMcpBlockStructure");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addReminder("am7://reminder/cfg-001", List.of(
			Map.of("key", "user-reminder", "value", "Stay in character and maintain tone.")
		));
		String block = builder.build();

		assertNotNull(block);
		assertTrue("Should contain mcp:context", block.contains("<mcp:context"));
		assertTrue("Should contain reminder URI", block.contains("am7://reminder/cfg-001"));
		assertTrue("Should contain reminder schema", block.contains("urn:am7:narrative:reminder"));

		/// Verify it parses and categorizes correctly
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(block);
		assertEquals("Should have one reminder", 1, result.getReminders().size());
	}

	@Test
	public void TestEpisodeReminderMcpFormat() {
		logger.info("TestEpisodeReminderMcpFormat");

		/// Simulate what PromptUtil now produces for episode reminders
		McpContextBuilder builder = new McpContextBuilder();
		builder.addReminder("am7://reminder/episode", List.of(
			Map.of("key", "episode-stages", "value", "Follow Episode Stages: \n* Theme: Adventure\n* Stage: Introduction")
		));
		String block = builder.build();

		assertNotNull(block);
		assertTrue("Should be MCP format", block.contains("<mcp:context"));
		assertTrue("Should contain episode URI", block.contains("am7://reminder/episode"));
		assertFalse("Should NOT contain old format", block.contains("(Reminder:"));

		List<McpContext> parsed = McpContextParser.parse(block);
		assertEquals("Should parse one context", 1, parsed.size());
	}

	@Test
	public void TestNlpReminderMcpFormat() {
		logger.info("TestNlpReminderMcpFormat");

		/// Simulate what PromptUtil now produces for NLP reminders
		McpContextBuilder builder = new McpContextBuilder();
		builder.addReminder("am7://reminder/nlp", List.of(
			Map.of("key", "nlp-command", "value", "move north")
		));
		String block = builder.build();

		assertNotNull(block);
		assertTrue("Should be MCP format", block.contains("<mcp:context"));
		assertTrue("Should contain nlp URI", block.contains("am7://reminder/nlp"));
		assertTrue("Should contain command value", block.contains("move north"));
		assertFalse("Should NOT contain old format", block.contains("(Reminder:"));
	}

	// =========================================================================
	// Dual-detection / backward compatibility tests
	// =========================================================================

	@Test
	public void TestMcpEquivalentPatternDetection() {
		logger.info("TestMcpEquivalentPatternDetection");

		/// Build an MCP reminder block
		McpContextBuilder remBuilder = new McpContextBuilder();
		remBuilder.addReminder("am7://reminder/test", List.of(Map.of("key", "test", "value", "value")));
		String mcpReminder = remBuilder.build();

		/// Build an MCP keyframe block
		McpContextBuilder kfBuilder = new McpContextBuilder();
		kfBuilder.addKeyframe("am7://keyframe/test", Map.of("summary", "test", "analysis", "test"));
		String mcpKeyframe = kfBuilder.build();

		/// Verify MCP blocks contain the URI patterns used for detection
		assertTrue("MCP reminder should contain /reminder/", mcpReminder.contains("/reminder/"));
		assertTrue("MCP keyframe should contain /keyframe/", mcpKeyframe.contains("/keyframe/"));
		assertTrue("Both should contain mcp:context", mcpReminder.contains("<mcp:context") && mcpKeyframe.contains("<mcp:context"));
	}

	@Test
	public void TestMixedFormatHistoryHandling() {
		logger.info("TestMixedFormatHistoryHandling");

		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "System prompt");

		/// Old-format message
		addMessage(req, "user", "Old message(Reminder: old reminder text)");

		/// MCP-format message
		McpContextBuilder remBuilder = new McpContextBuilder();
		remBuilder.addReminder("am7://reminder/test", List.of(Map.of("key", "test", "value", "new reminder")));
		addMessage(req, "user", "New message" + remBuilder.build());

		/// Old keyframe
		addMessage(req, "system", "(KeyFrame: old keyframe content)");

		/// MCP keyframe
		McpContextBuilder kfBuilder = new McpContextBuilder();
		kfBuilder.addKeyframe("am7://keyframe/test", Map.of("summary", "new kf"));
		addMessage(req, "system", kfBuilder.build());

		addMessage(req, "user", "Final message");

		List<String> history = ChatUtil.getFormattedChatHistory(req, null, -1, true);

		/// Should have stripped both old and new format reminders
		for(String line : history) {
			assertFalse("Should not contain old Reminder", line.contains("(Reminder"));
			assertFalse("Should not contain MCP blocks", line.contains("<mcp:context"));
			assertFalse("Should not contain old KeyFrame", line.contains("(KeyFrame"));
		}

		/// Should still contain the actual message text
		boolean hasOld = history.stream().anyMatch(h -> h.contains("Old message"));
		boolean hasNew = history.stream().anyMatch(h -> h.contains("New message"));
		boolean hasFinal = history.stream().anyMatch(h -> h.contains("Final message"));
		assertTrue("Should contain old message text", hasOld);
		assertTrue("Should contain new message text", hasNew);
		assertTrue("Should contain final message text", hasFinal);
	}

	// =========================================================================
	// Response-side citation extraction tests
	// =========================================================================

	@Test
	public void TestResponseCitationExtraction() {
		logger.info("TestResponseCitationExtraction");

		/// Build an MCP citation block like getCitationText would produce
		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			"am7://default/data.data/doc1/citations/chunk/3",
			"urn:am7:vector:search-result",
			Map.of("content", "Cardinal migration patterns.", "chunk", 3, "name", "CardFox.pdf", "source", "CardFox.pdf"),
			true
		);
		String citationBlock = builder.build();

		/// Create a request simulating a chat with citations
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "You are a helpful assistant.");

		/// User message with MCP citations
		addMessage(req, "user", citationBlock + "\nWhat do you know about cardinals?");

		/// Assistant response
		addMessage(req, "assistant", "Cardinals are known for their migration patterns.");

		/// Apply the filter (simulating what getChatResponse does)
		McpContextFilter filter = new McpContextFilter();
		OpenAIMessage userMsg = req.getMessages().get(1);
		McpFilterResult filterResult = filter.filter(userMsg.getContent());

		/// Verify citations were extracted
		assertFalse("Should have extracted citations", filterResult.getCitations().isEmpty());
		assertEquals("Should have one citation", 1, filterResult.getCitations().size());

		/// Verify filtered content has citation blocks removed
		String filtered = filterResult.getContent();
		assertFalse("Filtered content should not have MCP blocks", filtered.contains("<mcp:context"));
		assertTrue("Filtered content should still have user question", filtered.contains("What do you know about cardinals?"));
	}

	@Test
	public void TestResponseNoCitations() {
		logger.info("TestResponseNoCitations");

		/// Message without any MCP blocks
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test");
		addMessage(req, "system", "You are a helpful assistant.");
		addMessage(req, "user", "Hello, how are you?");
		addMessage(req, "assistant", "I'm doing well, thanks!");

		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(req.getMessages().get(1).getContent());

		assertTrue("Should have no citations", result.getCitations().isEmpty());
		assertEquals("Content should be unchanged", "Hello, how are you?", result.getContent().trim());
	}

	@Test
	public void TestMultipleCitationsExtraction() {
		logger.info("TestMultipleCitationsExtraction");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			"am7://default/data.data/doc1/citations/chunk/1",
			"urn:am7:vector:search-result",
			Map.of("content", "First chunk content.", "chunk", 1, "source", "doc1.pdf"),
			true
		);
		builder.addResource(
			"am7://default/data.data/doc2/citations/chunk/5",
			"urn:am7:vector:search-result",
			Map.of("content", "Second chunk content.", "chunk", 5, "source", "doc2.pdf"),
			true
		);
		String citationBlocks = builder.build();

		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(citationBlocks + "\nUser question here");

		assertEquals("Should extract two citations", 2, result.getCitations().size());
		assertTrue("Filtered content should contain user question", result.getContent().contains("User question here"));
		assertFalse("Filtered content should not contain MCP blocks", result.getContent().contains("<mcp:context"));
	}

	// =========================================================================
	// ChatService/ChatListener citation wrapper tests
	// =========================================================================

	@Test
	public void TestCitationWrapperIsMcpDirect() {
		logger.info("TestCitationWrapperIsMcpDirect");

		/// Simulate what ChatService now does: MCP blocks passed directly, no wrapper
		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			"am7://default/vector/chunk/1",
			"urn:am7:vector:search-result",
			Map.of("content", "Citation content.", "chunk", 1),
			true
		);
		String citRef = System.lineSeparator() + builder.build();

		/// Simulate the fallback branch: MCP blocks used directly
		String citDesc = citRef + System.lineSeparator();
		String userMessage = "What about this?";
		String fullMessage = citDesc + userMessage;

		assertTrue("Full message should contain MCP block", fullMessage.contains("<mcp:context"));
		assertTrue("Full message should contain user question", fullMessage.contains("What about this?"));
		assertFalse("Should NOT contain old citation wrapper", fullMessage.contains("--- CITATION INSTRUCTIONS ---"));
		assertFalse("Should NOT contain old delimiter", fullMessage.contains("--- BEGIN CITATIONS ---"));
	}

	// =========================================================================
	// Helper methods
	// =========================================================================

	private BaseRecord createTestVectorChunk(String content, int chunkNum, String chapterTitle, String name) {
		try {
			BaseRecord store = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
			store.set(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, "data.data");
			store.set("content", content);
			store.set("chunk", chunkNum);
			return store;
		}
		catch(Exception e) {
			logger.error("Error creating test vector chunk", e);
			return null;
		}
	}

	private void addMessage(OpenAIRequest req, String role, String content) {
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole(role);
		msg.setContent(content);
		req.addMessage(msg);
	}
}
