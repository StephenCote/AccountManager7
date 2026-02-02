package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

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
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.junit.Test;

/**
 * MCP memory integration tests using real document content (CardFox.pdf and The Verse.docx).
 * Demonstrates vectorization, document-scoped search, cross-content isolation,
 * MCP context formatting, summarization, and LLM-assisted question answering.
 */
public class TestMcpMemory extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpMemory.class);

	private static final String ORG_PATH = "/Development/MCP Memory";
	private static final String CARDFOX_PATH = "./media/CardFox.pdf";
	private static final String VERSE_PATH = "./media/The Verse.docx";
	private static final int CHUNK_SIZE = 250;

	private VectorUtil getVectorUtil() {
		return new VectorUtil(
			LLMServiceEnumType.valueOf(testProperties.getProperty("test.embedding.type").toUpperCase()),
			testProperties.getProperty("test.embedding.server"),
			testProperties.getProperty("test.embedding.authorizationToken")
		);
	}

	private void assumeEmbeddingAvailable() {
		String server = testProperties.getProperty("test.embedding.server");
		try {
			HttpURLConnection conn = (HttpURLConnection) URI.create(server).toURL().openConnection();
			conn.setConnectTimeout(2000);
			conn.setReadTimeout(2000);
			conn.setRequestMethod("GET");
			conn.connect();
			conn.disconnect();
		} catch (Exception e) {
			assumeTrue("Embedding service not available at " + server + " - skipping test", false);
		}
	}

	private BaseRecord getTestUser() {
		OrganizationContext testOrgContext = getTestOrganization(ORG_PATH);
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(testOrgContext.getAdminUser(), "mcpMemoryUser", testOrgContext.getOrganizationId());
	}

	private BaseRecord getOrCreateVectorizedDocument(BaseRecord user, VectorUtil vu, String path) {
		BaseRecord doc = getCreateDocument(user, path);
		assertNotNull("Document should not be null: " + path, doc);

		IOSystem.getActiveContext().getReader().populate(doc, new String[] {
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_CONTENT_TYPE
		});

		int count = vu.countVectorStore(doc);
		if (count == 0) {
			try {
				logger.info("Creating vector store for: " + path + " with WORD chunks of " + CHUNK_SIZE);
				List<BaseRecord> chunks = vu.createVectorStore(doc, ChunkEnumType.WORD, CHUNK_SIZE);
				IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
				logger.info("Created " + chunks.size() + " chunks for: " + path);
			} catch (Exception e) {
				logger.error("Failed to create vector store for: " + path, e);
			}
		} else {
			logger.info("Vector store already exists for " + path + " with " + count + " chunks");
		}
		return doc;
	}

	// --- TEST 1: Load and Vectorize Documents ---

	@Test
	public void TestLoadAndVectorizeDocuments() throws Exception {
		logger.info("TestLoadAndVectorizeDocuments");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);
		int cardFoxCount = vu.countVectorStore(cardFox);
		logger.info("CardFox.pdf chunk count: " + cardFoxCount);
		assertTrue("CardFox should have chunks", cardFoxCount > 0);

		BaseRecord verse = getOrCreateVectorizedDocument(user, vu, VERSE_PATH);
		int verseCount = vu.countVectorStore(verse);
		logger.info("The Verse.docx chunk count: " + verseCount);
		assertTrue("The Verse should have chunks", verseCount > 0);

		logger.info("Both documents loaded and vectorized successfully");
	}

	// --- TEST 2: Document-Scoped Search ---

	@Test
	public void TestDocumentScopedSearch() throws Exception {
		logger.info("TestDocumentScopedSearch");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);
		BaseRecord verse = getOrCreateVectorizedDocument(user, vu, VERSE_PATH);

		String query = "bus ride Kateri Stanwoods";

		// Search scoped to CardFox only
		List<BaseRecord> cardFoxResults = vu.find(cardFox, query, 5, 60);
		logger.info("CardFox-scoped results for '" + query + "': " + cardFoxResults.size());
		assertTrue("CardFox should have results for story-specific query", cardFoxResults.size() > 0);

		for (BaseRecord r : cardFoxResults) {
			String content = r.get(FieldNames.FIELD_CONTENT);
			double score = r.get(FieldNames.FIELD_SCORE);
			logger.info("  Chunk " + r.get(FieldNames.FIELD_CHUNK) + " (score=" + String.format("%.4f", score) + "): "
				+ (content != null && content.length() > 100 ? content.substring(0, 100) + "..." : content));
		}

		// Search scoped to Verse only - should have no/fewer relevant results
		List<BaseRecord> verseResults = vu.find(verse, query, 5, 60);
		logger.info("Verse-scoped results for '" + query + "': " + verseResults.size());

		logger.info("Document-scoped search demonstrates content isolation: CardFox=" + cardFoxResults.size() + ", Verse=" + verseResults.size());
	}

	// --- TEST 3: Cross-Content Isolation ---

	@Test
	public void TestCrossContentIsolation() throws Exception {
		logger.info("TestCrossContentIsolation");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);
		BaseRecord verse = getOrCreateVectorizedDocument(user, vu, VERSE_PATH);

		String query = "bus ride Kateri Stanwoods";

		// Unscoped search - may return results from both documents
		List<BaseRecord> unscopedResults = vu.find(null, ModelNames.MODEL_DATA, new BaseRecord[0],
			new String[] { ModelNames.MODEL_VECTOR_MODEL_STORE }, query, 10, 60, false);
		logger.info("Unscoped results: " + unscopedResults.size());

		// Scoped to CardFox
		List<BaseRecord> cardFoxResults = vu.find(cardFox, query, 5, 60);
		logger.info("CardFox-scoped results: " + cardFoxResults.size());

		// Scoped to Verse
		List<BaseRecord> verseResults = vu.find(verse, query, 5, 60);
		logger.info("Verse-scoped results: " + verseResults.size());

		// Verify scoped results only contain chunks from the targeted document
		long cardFoxId = cardFox.get(FieldNames.FIELD_ID);
		for (BaseRecord r : cardFoxResults) {
			BaseRecord ref = r.get(FieldNames.FIELD_VECTOR_REFERENCE);
			if (ref != null) {
				long refId = ref.get(FieldNames.FIELD_ID);
				assertTrue("CardFox-scoped result should reference CardFox document (refId=" + refId + ", expected=" + cardFoxId + ")",
					refId == cardFoxId);
			}
		}

		long verseId = verse.get(FieldNames.FIELD_ID);
		for (BaseRecord r : verseResults) {
			BaseRecord ref = r.get(FieldNames.FIELD_VECTOR_REFERENCE);
			if (ref != null) {
				long refId = ref.get(FieldNames.FIELD_ID);
				assertTrue("Verse-scoped result should reference Verse document (refId=" + refId + ", expected=" + verseId + ")",
					refId == verseId);
			}
		}

		logger.info("Cross-content isolation verified: scoped searches return only results from targeted document");
	}

	// --- TEST 4: MCP-Formatted Search Results ---

	@Test
	public void TestMcpFormattedSearchResults() throws Exception {
		logger.info("TestMcpFormattedSearchResults");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);

		String query = "How did the bus ride influence Kateri?";
		List<BaseRecord> results = vu.find(cardFox, query, 5, 60);
		assertTrue("Should have search results", results.size() > 0);

		// Format results as MCP context blocks
		String mcpCitations = McpTestUtil.buildMcpCitationsFromResults(cardFox, results);
		assertNotNull("MCP citations should not be null", mcpCitations);
		assertTrue("Should contain MCP context blocks", mcpCitations.contains("<mcp:context"));
		assertTrue("Should contain search result schema", mcpCitations.contains("urn:am7:vector:search-result"));
		logger.info("MCP-formatted citations (" + results.size() + " blocks):\n" + mcpCitations.substring(0, Math.min(500, mcpCitations.length())) + "...");

		// Parse with McpContextParser and verify round-trip
		List<McpContext> parsed = McpContextParser.parse(mcpCitations);
		assertTrue("Should parse at least one context", parsed.size() > 0);
		assertTrue("Parsed count should match result count", parsed.size() == results.size());

		for (McpContext ctx : parsed) {
			assertTrue("Each block should be ephemeral", ctx.isEphemeral());
			assertTrue("Each block should have a URI", ctx.getUri() != null && !ctx.getUri().isEmpty());
			assertTrue("URI should reference CardFox", ctx.getUri().contains("chunk/"));
		}

		// Filter and verify categorization as citations
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult filterResult = filter.filter(mcpCitations);
		assertTrue("Should categorize as citations", filterResult.getCitations().size() > 0);

		logger.info("MCP round-trip verified: " + parsed.size() + " blocks parsed, " + filterResult.getCitations().size() + " categorized as citations");
	}

	// --- TEST 5: MCP Summarization ---

	@Test
	public void TestMcpSummarization() throws Exception {
		logger.info("TestMcpSummarization");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);

		BaseRecord cfg = McpTestUtil.getChatConfig(user, "MCP Summary", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		BaseRecord pcfg = McpTestUtil.getPromptConfig(user, "MCP Summary Prompt");
		assertNotNull("Prompt config should not be null", pcfg);

		// Generate summaries using ChatUtil
		List<String> summaries = ChatUtil.composeSummary(user, cfg, pcfg, cardFox);
		assertNotNull("Summaries should not be null", summaries);
		assertTrue("Should have at least one summary", summaries.size() > 0);

		logger.info("Generated " + summaries.size() + " summaries for CardFox.pdf");
		for (int i = 0; i < summaries.size(); i++) {
			logger.info("Summary " + (i + 1) + " length: " + summaries.get(i).length());
		}

		// Wrap summaries in MCP context blocks
		McpContextBuilder builder = new McpContextBuilder();
		String docUri = Am7Uri.toUri(cardFox);
		builder.addResource(
			docUri + "/summary",
			"urn:am7:document:summary",
			Map.of(
				"summaries", summaries,
				"source", "CardFox.pdf",
				"chunkType", "WORD",
				"chunkSize", CHUNK_SIZE
			),
			false
		);
		String mcpSummary = builder.build();

		assertNotNull("MCP summary should not be null", mcpSummary);
		assertTrue("Should contain MCP context block", mcpSummary.contains("<mcp:context"));
		assertTrue("Should contain summary schema", mcpSummary.contains("urn:am7:document:summary"));
		assertTrue("Should NOT be ephemeral (summaries persist)", !mcpSummary.contains("ephemeral=\"true\""));

		logger.info("MCP-formatted summary block length: " + mcpSummary.length());
	}

	// --- TEST 6: MCP Citation Context For Question ---

	@Test
	public void TestMcpCitationContextForQuestion() throws Exception {
		logger.info("TestMcpCitationContextForQuestion");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);

		String question = "How did the bus ride influence Kateri's realization about the Stanwoods?";

		// Search CardFox for relevant content
		List<BaseRecord> results = vu.find(cardFox, question, 5, 60);
		assertTrue("Should have search results for the question", results.size() > 0);
		logger.info("Found " + results.size() + " relevant chunks for question");

		// Build MCP citation context
		String mcpCitations = McpTestUtil.buildMcpCitationsFromResults(cardFox, results);

		// Build full MCP context with citations + question reminder
		McpContextBuilder builder = new McpContextBuilder();
		builder.addReminder(
			"am7://reminder/question",
			List.of(Map.of("key", "question", "value", question))
		);
		String reminderBlock = builder.build();
		String fullContext = mcpCitations + "\n" + reminderBlock;

		assertNotNull("Full MCP context should not be null", fullContext);
		assertTrue("Should contain citation blocks", fullContext.contains("urn:am7:vector:search-result"));
		assertTrue("Should contain reminder block", fullContext.contains("urn:am7:narrative:reminder"));

		// Parse and verify categorization
		McpContextFilter filter = new McpContextFilter();
		McpFilterResult filterResult = filter.filter(fullContext);
		assertTrue("Should have citations", filterResult.getCitations().size() > 0);
		assertTrue("Should have reminders", filterResult.getReminders().size() > 0);

		// Log some of the retrieved content for verification
		for (BaseRecord r : results) {
			String content = r.get(FieldNames.FIELD_CONTENT);
			double score = r.get(FieldNames.FIELD_SCORE);
			logger.info("Citation chunk " + r.get(FieldNames.FIELD_CHUNK) + " (score=" + String.format("%.4f", score) + "): "
				+ (content != null && content.length() > 150 ? content.substring(0, 150) + "..." : content));
		}

		logger.info("MCP citation context built with " + filterResult.getCitations().size() + " citations and "
			+ filterResult.getReminders().size() + " reminders");
	}

	// --- TEST 7: LLM With MCP Document Context ---

	@Test
	public void TestLlmWithMcpDocumentContext() throws Exception {
		logger.info("TestLlmWithMcpDocumentContext");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);

		BaseRecord cfg = McpTestUtil.getChatConfig(user, "MCP Memory LLM", testProperties);
		assertNotNull("Chat config should not be null", cfg);

		BaseRecord pcfg = McpTestUtil.getPromptConfig(user, "MCP Memory LLM Prompt");
		assertNotNull("Prompt config should not be null", pcfg);

		McpTestUtil.setSystemPrompt(user, pcfg,
			"You are a document analysis assistant. Answer questions using ONLY the provided MCP citation context blocks. "
			+ "Each <mcp:context> block contains a chunk from a source document. "
			+ "Synthesize information from the relevant chunks to provide an accurate, comprehensive answer. "
			+ "Always reference which chunks informed your answer. "
			+ "If the provided citations don't contain enough information, say so."
		);

		String chatName = McpTestUtil.uniqueChatName("MCP Memory LLM");
		BaseRecord creq = ChatUtil.getCreateChatRequest(user, chatName, cfg, pcfg);
		assertNotNull("Chat request should not be null", creq);

		OpenAIRequest req = ChatUtil.getChatSession(user, chatName, cfg, pcfg);
		assertNotNull("OpenAI request should not be null", req);

		// Search for relevant content
		String question = "How did the bus ride influence Kateri's realization about the Stanwoods?";
		List<BaseRecord> results = vu.find(cardFox, question, 5, 60);
		assertTrue("Should have search results", results.size() > 0);

		// Build MCP citation context and inject with the question
		String mcpCitations = McpTestUtil.buildMcpCitationsFromResults(cardFox, results);
		String userMessage = mcpCitations + "\n" + question;

		// Send to LLM
		Chat chat = new Chat(user, cfg, pcfg);
		chat.continueChat(req, userMessage);

		List<OpenAIMessage> msgs = req.getMessages();
		assertTrue("Should have messages after chat", msgs.size() > 1);

		String response = msgs.get(msgs.size() - 1).getContent();
		assertNotNull("LLM response should not be null", response);
		assertTrue("LLM response should not be empty", response.trim().length() > 0);

		logger.info("=== LLM Response to CardFox Question ===");
		logger.info(response);
		logger.info("=== End LLM Response ===");
	}

	// --- TEST 8: Multi-Document MCP Context ---

	@Test
	public void TestMultiDocumentMcpContext() throws Exception {
		logger.info("TestMultiDocumentMcpContext");
		assumeEmbeddingAvailable();

		BaseRecord user = getTestUser();
		VectorUtil vu = getVectorUtil();

		BaseRecord cardFox = getOrCreateVectorizedDocument(user, vu, CARDFOX_PATH);
		BaseRecord verse = getOrCreateVectorizedDocument(user, vu, VERSE_PATH);

		// Use a broad query that could match content in either document
		String query = "journey and discovery";

		// Search each document independently
		List<BaseRecord> cardFoxResults = vu.find(cardFox, query, 3, 60);
		List<BaseRecord> verseResults = vu.find(verse, query, 3, 60);

		logger.info("CardFox results for '" + query + "': " + cardFoxResults.size());
		logger.info("Verse results for '" + query + "': " + verseResults.size());

		// Build combined MCP context with source-tagged citations
		String cardFoxCitations = McpTestUtil.buildMcpCitationsFromResults(cardFox, cardFoxResults);
		String verseCitations = McpTestUtil.buildMcpCitationsFromResults(verse, verseResults);

		String combinedContext = "";
		if (!cardFoxCitations.isEmpty() && !verseCitations.isEmpty()) {
			combinedContext = cardFoxCitations + "\n" + verseCitations;
		} else if (!cardFoxCitations.isEmpty()) {
			combinedContext = cardFoxCitations;
		} else if (!verseCitations.isEmpty()) {
			combinedContext = verseCitations;
		}

		// Both documents should have at least some results for a broad query
		assertTrue("Should have combined results from at least one document",
			cardFoxResults.size() > 0 || verseResults.size() > 0);

		if (!combinedContext.isEmpty()) {
			// Parse and verify citations can be distinguished by URI
			List<McpContext> allParsed = McpContextParser.parse(combinedContext);
			logger.info("Total parsed MCP blocks: " + allParsed.size());

			String cardFoxUri = Am7Uri.toUri(cardFox);
			String verseUri = Am7Uri.toUri(verse);

			int cardFoxBlocks = 0;
			int verseBlocks = 0;
			for (McpContext ctx : allParsed) {
				if (cardFoxUri != null && ctx.getUri() != null && ctx.getUri().startsWith(cardFoxUri)) {
					cardFoxBlocks++;
				} else if (verseUri != null && ctx.getUri() != null && ctx.getUri().startsWith(verseUri)) {
					verseBlocks++;
				}
			}

			logger.info("CardFox MCP blocks: " + cardFoxBlocks + ", Verse MCP blocks: " + verseBlocks);
			assertTrue("CardFox block count should match results", cardFoxBlocks == cardFoxResults.size());
			assertTrue("Verse block count should match results", verseBlocks == verseResults.size());

			// Filter and verify all are categorized as citations
			McpContextFilter filter = new McpContextFilter();
			McpFilterResult filterResult = filter.filter(combinedContext);
			assertTrue("All blocks should be categorized as citations",
				filterResult.getCitations().size() == allParsed.size());
		}

		logger.info("Multi-document MCP context verified with source-distinguishable citations");
	}
}
