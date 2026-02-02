package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.mcp.Am7Uri;
import org.cote.accountmanager.mcp.McpContext;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.mcp.McpContextFilter;
import org.cote.accountmanager.mcp.McpContextParser;
import org.cote.accountmanager.mcp.McpFilterResult;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * LLM integration tests for MCP context injection and filtering.
 * Follows the patterns established by TestChat, TestAgentChat, and TestChatAsync.
 */
public class TestMcpLlm extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpLlm.class);

	// --- CHAT CONFIG + MCP URI GENERATION ---

	@Test
	public void TestChatConfigToMcpUri() {
		logger.info("TestChatConfigToMcpUri");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		BaseRecord cfg = McpTestUtil.getChatConfig(testUser1, "MCP Config Test", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		String uri = Am7Uri.toUri(cfg);
		assertNotNull("URI should not be null", uri);
		assertTrue("URI should start with am7://", uri.startsWith("am7://"));
		assertTrue("URI should contain chatConfig type", uri.contains("chatConfig"));
		logger.info("Chat config URI: " + uri);

		// Round-trip parse
		Am7Uri parsed = Am7Uri.parse(uri);
		assertNotNull("Parsed URI should not be null", parsed);
		assertEquals("Type should match schema", cfg.getSchema(), parsed.getType());
	}

	@Test
	public void TestPromptConfigToMcpUri() {
		logger.info("TestPromptConfigToMcpUri");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		BaseRecord pcfg = McpTestUtil.getPromptConfig(testUser1, "MCP Prompt Test");
		assertNotNull("Prompt config should not be null", pcfg);

		String uri = Am7Uri.toUri(pcfg);
		assertNotNull("URI should not be null", uri);
		assertTrue("URI should contain promptConfig type", uri.contains("promptConfig"));
		logger.info("Prompt config URI: " + uri);
	}

	// --- MCP CONTEXT INJECTION WITH CHAT ---

	@Test
	public void TestMcpContextInjectionBuild() {
		logger.info("TestMcpContextInjectionBuild");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		// Build MCP context like what would be injected into a chat message
		String chatId = UUID.randomUUID().toString();
		String mcpContext = McpTestUtil.buildFullMcpContext(chatId, "test query", "user-42");

		assertNotNull("MCP context should not be null", mcpContext);
		assertTrue("Should contain citation context", mcpContext.contains("citations"));
		assertTrue("Should contain reminder context", mcpContext.contains("reminder"));
		assertTrue("Should contain keyframe context", mcpContext.contains("keyframe"));

		// Verify it can be parsed
		List<McpContext> parsed = McpContextParser.parse(mcpContext);
		assertEquals("Should have 3 context blocks", 3, parsed.size());

		// Verify categories
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(mcpContext);
		assertEquals("Should have 1 citation", 1, result.getCitations().size());
		assertEquals("Should have 1 reminder", 1, result.getReminders().size());
		assertEquals("Should have 1 keyframe", 1, result.getKeyframes().size());
	}

	@Test
	public void TestMcpContextWithUserMessage() {
		logger.info("TestMcpContextWithUserMessage");

		// Simulate injecting MCP context before a user message (like ChatService does)
		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			"am7://default/vector/citations/test-session",
			"urn:am7:vector:search-result",
			Map.of(
				"query", "What is MCP?",
				"instruction", "Use the following citations to generate a response",
				"results", List.of(
					Map.of("uri", "am7://default/data.data/doc-mcp", "content", "MCP is the Model Context Protocol", "score", 0.92)
				)
			),
			true
		);

		String injectedContent = builder.build() + "\nWhat is MCP?";

		// Verify the full content can be parsed
		List<McpContext> contexts = McpContextParser.parse(injectedContent);
		assertEquals("Should find 1 context", 1, contexts.size());

		// Verify the user message is preserved after stripping
		String stripped = McpContextParser.stripAll(injectedContent);
		assertTrue("User message should be preserved", stripped.contains("What is MCP?"));
	}

	// --- CHAT SESSION CREATION WITH MCP ---

	@Test
	public void TestCreateMcpChatSession() {
		logger.info("TestCreateMcpChatSession");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		BaseRecord cfg = McpTestUtil.getChatConfig(testUser1, "MCP Session Test", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		BaseRecord pcfg = McpTestUtil.getPromptConfig(testUser1, "MCP Session Prompt");
		assertNotNull("Prompt config should not be null", pcfg);

		// Set an MCP-aware system prompt
		McpTestUtil.setSystemPrompt(testUser1, pcfg,
			"You are an MCP-aware assistant. Parse context blocks for relevant information.");

		// Create chat request and session
		String chatName = McpTestUtil.uniqueChatName("MCP Session");
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, pcfg);
		assertNotNull("Chat request should not be null", creq);

		OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);
		assertNotNull("OpenAI request should not be null", req);

		// Verify the session has the system prompt
		List<OpenAIMessage> msgs = req.getMessages();
		assertNotNull("Messages should not be null", msgs);
		assertTrue("Should have at least 1 message (system)", msgs.size() >= 1);
	}

	// --- MCP CONTEXT ROUND-TRIP ---

	@Test
	public void TestMcpContextRoundTrip() {
		logger.info("TestMcpContextRoundTrip");

		// Build context
		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			"am7://default/vector/citations/round-trip-test",
			"urn:am7:vector:search-result",
			Map.of("query", "round trip test", "results", List.of()),
			true
		);
		builder.addReasoning(List.of("Step 1: Parse", "Step 2: Respond"));
		builder.addReminder("am7://reminder/test-user", List.of(
			Map.of("key", "mode", "value", "testing")
		));

		String built = builder.build();
		assertNotNull("Built context should not be null", built);

		// Parse it back
		List<McpContext> parsed = McpContextParser.parse(built);
		assertEquals("Should parse 3 contexts", 3, parsed.size());

		// Filter it
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(built);

		assertEquals("Should have 1 citation", 1, result.getCitations().size());
		assertEquals("Should have 1 reasoning", 1, result.getReasoning().size());
		assertEquals("Should have 1 reminder", 1, result.getReminders().size());

		// Verify ephemeral blocks are stripped from display
		String displayContent = result.getContent().trim();
		assertTrue("Display content should be empty or whitespace after stripping ephemeral",
			displayContent.isEmpty() || displayContent.isBlank());
	}

	// --- PROMPT TEMPLATE WITH MCP ---

	@Test
	public void TestPromptTemplateWithMcpContext() {
		logger.info("TestPromptTemplateWithMcpContext");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		BaseRecord pcfg = OlioTestUtil.getPromptConfig(testUser1, "MCP Template Test - " + UUID.randomUUID().toString());
		assertNotNull("Prompt config should not be null", pcfg);

		BaseRecord cfg = McpTestUtil.getChatConfig(testUser1, "MCP Template Chat", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		// Generate prompt templates and verify they produce non-null output
		String stempl = PromptUtil.getSystemChatPromptTemplate(pcfg, cfg);
		assertNotNull("System template should not be null", stempl);
		logger.info("System template length: " + stempl.length());

		String utempl = PromptUtil.getUserChatPromptTemplate(pcfg, cfg);
		assertNotNull("User template should not be null", utempl);

		// Build MCP context to prepend to user message
		McpContextBuilder ctxBuilder = new McpContextBuilder();
		ctxBuilder.addResource(
			"am7://default/vector/citations/template-test",
			"urn:am7:vector:search-result",
			Map.of("query", "template test", "results", List.of()),
			true
		);

		String mcpInjection = ctxBuilder.build();
		String fullUserMessage = mcpInjection + "\n" + utempl;

		assertTrue("Full message should contain MCP context", fullUserMessage.contains("<mcp:context"));
	}

	// --- CHAT CONFIG URI REFERENCES ---

	@Test
	public void TestChatRequestUriReferences() {
		logger.info("TestChatRequestUriReferences");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		BaseRecord cfg = McpTestUtil.getChatConfig(testUser1, "MCP URI Ref Chat", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		BaseRecord pcfg = McpTestUtil.getPromptConfig(testUser1, "MCP URI Ref Prompt");
		assertNotNull("Prompt config should not be null", pcfg);

		// Generate URIs for both configs
		String cfgUri = Am7Uri.toUri(cfg);
		String pcfgUri = Am7Uri.toUri(pcfg);

		assertNotNull("Chat config URI should not be null", cfgUri);
		assertNotNull("Prompt config URI should not be null", pcfgUri);

		// Verify URIs are properly formatted
		Am7Uri parsedCfg = Am7Uri.parse(cfgUri);
		Am7Uri parsedPcfg = Am7Uri.parse(pcfgUri);

		assertEquals("Chat config org should match", "Development",
			parsedCfg.getOrganization().contains("Development") ? "Development" : parsedCfg.getOrganization());
		assertNotNull("Chat config object ID should not be null", parsedCfg.getId());
		assertNotNull("Prompt config object ID should not be null", parsedPcfg.getId());

		logger.info("Chat config URI: " + cfgUri);
		logger.info("Prompt config URI: " + pcfgUri);
	}

	// --- LLM CHAT WITH MCP CONTEXT ---

	@Test
	public void TestLlmChatWithMcpInjection() {
		logger.info("TestLlmChatWithMcpInjection");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		BaseRecord cfg = McpTestUtil.getChatConfig(testUser1, "MCP LLM Chat", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		BaseRecord pcfg = McpTestUtil.getPromptConfig(testUser1, "MCP LLM Prompt");
		assertNotNull("Prompt config should not be null", pcfg);

		McpTestUtil.setSystemPrompt(testUser1, pcfg,
			"You are a test assistant. When you see MCP context blocks, acknowledge them.");

		String chatName = McpTestUtil.uniqueChatName("MCP LLM Chat");
		BaseRecord creq = ChatUtil.getCreateChatRequest(testUser1, chatName, cfg, pcfg);
		assertNotNull("Chat request should not be null", creq);

		OpenAIRequest req = ChatUtil.getChatSession(testUser1, chatName, cfg, pcfg);
		assertNotNull("OpenAI request should not be null", req);

		// Build MCP context to inject with user message
		McpContextBuilder ctxBuilder = new McpContextBuilder();
		ctxBuilder.addResource(
			"am7://default/vector/citations/" + chatName,
			"urn:am7:vector:search-result",
			Map.of(
				"query", "hello",
				"results", List.of(
					Map.of("uri", "am7://default/data.data/test-doc", "content", "Test citation content", "score", 0.88)
				)
			),
			true
		);

		String mcpContext = ctxBuilder.build();
		String userMessage = mcpContext + "\nHello, this is an MCP integration test.";

		// Send message through chat
		Chat chat = new Chat(testUser1, cfg, pcfg);
		chat.continueChat(req, userMessage);

		List<OpenAIMessage> msgs = req.getMessages();
		assertTrue("Should have messages after chat", msgs.size() > 1);

		String lastMessage = msgs.get(msgs.size() - 1).getContent();
		assertNotNull("Response should not be null", lastMessage);
		logger.info("LLM Response: " + lastMessage);

		// Filter the response through MCP filter
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult filterResult = filter.filter(lastMessage);
		assertNotNull("Filter result should not be null", filterResult);
		logger.info("Filtered content: " + filterResult.getContent());
	}

	// --- MCP URI BUILDER FOR VECTOR SEARCH ---

	@Test
	public void TestVectorSearchUriBuilder() {
		logger.info("TestVectorSearchUriBuilder");

		// Build a vector search URI as would be used in MCP tool calls
		String searchUri = Am7Uri.builder()
			.organization("default")
			.vectorSearch()
			.queryParam("q", "test search query")
			.queryParam("limit", "10")
			.queryParam("threshold", "0.6")
			.build();

		assertNotNull("Search URI should not be null", searchUri);
		assertTrue("Should start with am7://", searchUri.startsWith("am7://"));
		assertTrue("Should contain vector/search", searchUri.contains("vector/search"));
		assertTrue("Should contain query param", searchUri.contains("q=test+search+query"));
		assertTrue("Should contain limit param", searchUri.contains("limit=10"));
		assertTrue("Should contain threshold param", searchUri.contains("threshold=0.6"));
		logger.info("Vector search URI: " + searchUri);
	}

	// --- MCP CITATION CONTEXT FOR CHAT ---

	@Test
	public void TestBuildCitationContextForChat() {
		logger.info("TestBuildCitationContextForChat");

		String chatId = UUID.randomUUID().toString();
		String citationContext = McpTestUtil.buildCitationContext(
			chatId,
			"Tell me about MCP",
			List.of(
				Map.of("uri", "am7://default/data.data/mcp-spec", "content", "MCP specification document", "score", 0.95),
				Map.of("uri", "am7://default/data.data/mcp-impl", "content", "MCP implementation guide", "score", 0.87)
			)
		);

		assertNotNull("Citation context should not be null", citationContext);
		assertTrue("Should contain citation block", citationContext.contains("<mcp:context"));
		assertTrue("Should contain search result schema", citationContext.contains("urn:am7:vector:search-result"));
		assertTrue("Should be ephemeral", citationContext.contains("ephemeral=\"true\""));

		// Parse and verify
		List<McpContext> parsed = McpContextParser.parse(citationContext);
		assertEquals("Should have 1 context block", 1, parsed.size());
		assertTrue("Should be ephemeral", parsed.get(0).isEphemeral());
		assertEquals("Type should be resource", "resource", parsed.get(0).getType());
	}

	// --- MCP MEDIA RESOURCE IN CHAT ---

	@Test
	public void TestMediaResourceInChatResponse() {
		logger.info("TestMediaResourceInChatResponse");

		// Simulate a chat response that includes an inline media resource
		String chatResponse = "Here is the diagram you requested: "
			+ "<mcp:resource uri=\"am7://default/media/data.data/diagram-123\" tags=\"diagram,architecture\" />"
			+ " Let me explain what it shows.";

		// Parse
		List<McpContext> contexts = McpContextParser.parse(chatResponse);
		assertEquals("Should find 1 inline resource", 1, contexts.size());
		assertTrue("Should be inline", contexts.get(0).isInline());
		assertEquals("URI should match", "am7://default/media/data.data/diagram-123", contexts.get(0).getUri());
		assertEquals("Should have 2 tags", 2, contexts.get(0).getTags().size());

		// Filter with rendering enabled
		McpContextFilter filter = new McpContextFilter(false, true);
		McpFilterResult result = filter.filter(chatResponse);

		assertTrue("Should render as img tag", result.getContent().contains("<img"));
		assertTrue("Should contain data-mcp-uri", result.getContent().contains("data-mcp-uri"));
		assertTrue("Should preserve surrounding text", result.getContent().contains("Here is the diagram"));
		assertTrue("Should preserve surrounding text", result.getContent().contains("Let me explain"));
		assertEquals("Should categorize as media", 1, result.getMedia().size());
	}

	// --- MULTIPLE CONFIG URIS ---

	@Test
	public void TestMultipleConfigUris() {
		logger.info("TestMultipleConfigUris");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpTestUser1", testOrgContext.getOrganizationId());

		// Create multiple chat configs and verify unique URIs
		BaseRecord cfg1 = McpTestUtil.getChatConfig(testUser1, "MCP Multi Config 1", testProperties);
		BaseRecord cfg2 = McpTestUtil.getChatConfig(testUser1, "MCP Multi Config 2", testProperties);

		assertNotNull("Config 1 should not be null", cfg1);
		assertNotNull("Config 2 should not be null", cfg2);

		String uri1 = Am7Uri.toUri(cfg1);
		String uri2 = Am7Uri.toUri(cfg2);

		assertNotNull("URI 1 should not be null", uri1);
		assertNotNull("URI 2 should not be null", uri2);

		// URIs should be different (different objectIds)
		assertTrue("URIs should be different for different configs",
			!uri1.equals(uri2));

		logger.info("Config 1 URI: " + uri1);
		logger.info("Config 2 URI: " + uri2);
	}

	// --- USER RECORD URI ---

	@Test
	public void TestUserRecordMcpUri() {
		logger.info("TestUserRecordMcpUri");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MCP");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "mcpUriTestUser", testOrgContext.getOrganizationId());

		String uri = Am7Uri.toUri(testUser1);
		assertNotNull("User URI should not be null", uri);
		assertTrue("Should contain system.user", uri.contains("system.user"));

		Am7Uri parsed = Am7Uri.parse(uri);
		assertNotNull("Parsed URI should not be null", parsed);
		assertEquals("system.user", parsed.getType());

		logger.info("User URI: " + uri);
	}
}
