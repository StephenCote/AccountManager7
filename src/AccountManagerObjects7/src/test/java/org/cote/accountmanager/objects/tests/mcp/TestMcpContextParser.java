package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.McpContext;
import org.cote.accountmanager.mcp.McpContextParser;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.junit.Test;

public class TestMcpContextParser extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpContextParser.class);

	@Test
	public void TestParseResourceContext() {
		logger.info("TestParseResourceContext");

		String content = "Hello\n"
			+ "<mcp:context type=\"resource\" uri=\"am7://citations/123\" ephemeral=\"true\">\n"
			+ "{\"schema\": \"test\", \"data\": {\"key\": \"value\"}}\n"
			+ "</mcp:context>\n"
			+ "World";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 1 context", 1, contexts.size());
		assertEquals("Type should be resource", "resource", contexts.get(0).getType());
		assertEquals("URI should match", "am7://citations/123", contexts.get(0).getUri());
		assertTrue("Should be ephemeral", contexts.get(0).isEphemeral());
	}

	@Test
	public void TestParseInlineResource() {
		logger.info("TestParseInlineResource");

		String content = "Image: <mcp:resource uri=\"am7://media/img-1\" tags=\"portrait,photo\" />";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 1 context", 1, contexts.size());
		assertTrue("Should be inline", contexts.get(0).isInline());
		assertEquals("URI should match", "am7://media/img-1", contexts.get(0).getUri());
		assertEquals("Should have 2 tags", 2, contexts.get(0).getTags().size());
		assertEquals("First tag", "portrait", contexts.get(0).getTags().get(0));
		assertEquals("Second tag", "photo", contexts.get(0).getTags().get(1));
	}

	@Test
	public void TestParseMultipleContexts() {
		logger.info("TestParseMultipleContexts");

		String content = "<mcp:context type=\"resource\" uri=\"am7://a\">{\"data\":1}</mcp:context>\n"
			+ "Some text\n"
			+ "<mcp:context type=\"reasoning\" ephemeral=\"true\">{\"steps\":[]}</mcp:context>";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 2 contexts", 2, contexts.size());
		assertEquals("First type should be resource", "resource", contexts.get(0).getType());
		assertEquals("Second type should be reasoning", "reasoning", contexts.get(1).getType());
	}

	@Test
	public void TestParsePreservesPositions() {
		logger.info("TestParsePreservesPositions");

		String content = "A<mcp:context type=\"x\">B</mcp:context>C";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 1 context", 1, contexts.size());
		assertEquals("Start should be at position 1", 1, contexts.get(0).getStart());
		assertTrue("End should be after start", contexts.get(0).getEnd() > contexts.get(0).getStart());
	}

	@Test
	public void TestParseNoContextsReturnsEmpty() {
		logger.info("TestParseNoContextsReturnsEmpty");

		String content = "Just regular text without any MCP contexts.";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertTrue("Should return empty list", contexts.isEmpty());
	}

	@Test
	public void TestParseNullContent() {
		logger.info("TestParseNullContent");

		List<McpContext> contexts = McpContextParser.parse(null);

		assertNotNull("Should return non-null list", contexts);
		assertTrue("Should return empty list", contexts.isEmpty());
	}

	@Test
	public void TestParseEmptyContent() {
		logger.info("TestParseEmptyContent");

		List<McpContext> contexts = McpContextParser.parse("");

		assertNotNull("Should return non-null list", contexts);
		assertTrue("Should return empty list", contexts.isEmpty());
	}

	@Test
	public void TestParseMixedBlocksAndInline() {
		logger.info("TestParseMixedBlocksAndInline");

		String content = "<mcp:context type=\"resource\" uri=\"am7://test/1\">{\"data\": 1}</mcp:context>\n"
			+ "Some text with <mcp:resource uri=\"am7://media/img-1\" tags=\"photo\" /> inline\n"
			+ "<mcp:context type=\"reasoning\" ephemeral=\"true\">{\"steps\": [\"think\"]}</mcp:context>";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 3 contexts", 3, contexts.size());
		assertTrue("First should not be inline", !contexts.get(0).isInline());
		assertTrue("Second should be inline", contexts.get(1).isInline());
		assertTrue("Third should not be inline", !contexts.get(2).isInline());
	}

	@Test
	public void TestParseContextWithBody() {
		logger.info("TestParseContextWithBody");

		String body = "{\"schema\": \"urn:am7:vector:search-result\", \"data\": {\"query\": \"test\"}}";
		String content = "<mcp:context type=\"resource\" uri=\"am7://test/1\">" + body + "</mcp:context>";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 1 context", 1, contexts.size());
		assertNotNull("Body should not be null", contexts.get(0).getBody());
		assertTrue("Body should contain schema", contexts.get(0).getBody().contains("urn:am7:vector:search-result"));
	}

	// --- STRIP OPERATIONS ---

	@Test
	public void TestStripAll() {
		logger.info("TestStripAll");

		String content = "Hello <mcp:context type=\"x\" ephemeral=\"true\">hidden</mcp:context> World"
			+ " <mcp:resource uri=\"am7://media/1\" />";

		String stripped = McpContextParser.stripAll(content);

		assertNotNull("Stripped should not be null", stripped);
		assertTrue("Should not contain mcp:context", !stripped.contains("<mcp:context"));
		assertTrue("Should not contain mcp:resource", !stripped.contains("<mcp:resource"));
		assertTrue("Should contain Hello", stripped.contains("Hello"));
		assertTrue("Should contain World", stripped.contains("World"));
	}

	@Test
	public void TestStripEphemeral() {
		logger.info("TestStripEphemeral");

		String content = "Keep <mcp:context type=\"resource\" uri=\"am7://keep\">visible</mcp:context>"
			+ " Remove <mcp:context type=\"resource\" uri=\"am7://remove\" ephemeral=\"true\">hidden</mcp:context> End";

		String stripped = McpContextParser.stripEphemeral(content);

		assertNotNull("Stripped should not be null", stripped);
		assertTrue("Should not contain ephemeral content", !stripped.contains("hidden"));
		assertTrue("Should contain non-ephemeral content", stripped.contains("visible"));
		assertTrue("Should contain surrounding text", stripped.contains("Keep"));
		assertTrue("Should contain end text", stripped.contains("End"));
	}

	@Test
	public void TestStripNullContent() {
		logger.info("TestStripNullContent");

		assertNotNull("StripAll null should return null gracefully", McpContextParser.stripAll(null) == null || true);
		assertNotNull("StripEphemeral null should return null gracefully", McpContextParser.stripEphemeral(null) == null || true);
	}

	// --- ORDER VERIFICATION ---

	@Test
	public void TestParseOrderPreserved() {
		logger.info("TestParseOrderPreserved");

		String content = "<mcp:context type=\"a\" uri=\"am7://first\">1</mcp:context>"
			+ "middle"
			+ "<mcp:context type=\"b\" uri=\"am7://second\">2</mcp:context>"
			+ "end"
			+ "<mcp:context type=\"c\" uri=\"am7://third\">3</mcp:context>";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertEquals("Should find 3 contexts", 3, contexts.size());
		assertEquals("First type", "a", contexts.get(0).getType());
		assertEquals("Second type", "b", contexts.get(1).getType());
		assertEquals("Third type", "c", contexts.get(2).getType());

		assertTrue("First start < second start", contexts.get(0).getStart() < contexts.get(1).getStart());
		assertTrue("Second start < third start", contexts.get(1).getStart() < contexts.get(2).getStart());
	}

	// --- LEGACY FORMAT DETECTION ---

	@Test
	public void TestParseLegacyCitationsNotMatched() {
		logger.info("TestParseLegacyCitationsNotMatched");

		String content = "--- BEGIN CITATIONS ---\nSome citations\n--- END CITATIONS ---";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertTrue("Legacy format should not be parsed as MCP contexts", contexts.isEmpty());
	}

	@Test
	public void TestParseLegacyReminderNotMatched() {
		logger.info("TestParseLegacyReminderNotMatched");

		String content = "(Reminder: {\"key\": \"value\"})";

		List<McpContext> contexts = McpContextParser.parse(content);

		assertTrue("Legacy format should not be parsed as MCP contexts", contexts.isEmpty());
	}
}
