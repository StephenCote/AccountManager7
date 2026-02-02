package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.McpContextFilter;
import org.cote.accountmanager.mcp.McpFilterResult;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.junit.Test;

public class TestMcpContextFilter extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpContextFilter.class);

	// --- EPHEMERAL FILTERING ---

	@Test
	public void TestRemoveEphemeralByDefault() {
		logger.info("TestRemoveEphemeralByDefault");

		String content = "Hello <mcp:context type=\"resource\" uri=\"am7://x\" ephemeral=\"true\">data</mcp:context> World";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertTrue("Ephemeral content should be removed", !result.getContent().contains("data</mcp:context>"));
		assertTrue("Surrounding text preserved", result.getContent().contains("Hello"));
		assertTrue("Surrounding text preserved", result.getContent().contains("World"));
	}

	@Test
	public void TestShowEphemeralWhenConfigured() {
		logger.info("TestShowEphemeralWhenConfigured");

		String content = "Hello <mcp:context type=\"resource\" uri=\"am7://x\" ephemeral=\"true\">data</mcp:context> World";
		McpContextFilter filter = new McpContextFilter(true, false);

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertTrue("Ephemeral content should be preserved", result.getContent().contains("<mcp:context"));
	}

	// --- CATEGORIZATION ---

	@Test
	public void TestCategorizeCitations() {
		logger.info("TestCategorizeCitations");

		String content = "<mcp:context type=\"resource\" uri=\"am7://vector/citations/123\">citation data</mcp:context>";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 citation", 1, result.getCitations().size());
		assertEquals("Should have 0 reminders", 0, result.getReminders().size());
	}

	@Test
	public void TestCategorizeReminders() {
		logger.info("TestCategorizeReminders");

		String content = "<mcp:context type=\"resource\" uri=\"am7://reminder/user-1\" ephemeral=\"true\">reminder data</mcp:context>";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 reminder", 1, result.getReminders().size());
	}

	@Test
	public void TestCategorizeKeyframes() {
		logger.info("TestCategorizeKeyframes");

		String content = "<mcp:context type=\"resource\" uri=\"am7://keyframe/scene-1\" ephemeral=\"true\">keyframe data</mcp:context>";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 keyframe", 1, result.getKeyframes().size());
	}

	@Test
	public void TestCategorizeMetrics() {
		logger.info("TestCategorizeMetrics");

		String content = "<mcp:context type=\"resource\" uri=\"am7://metrics/biometric\" ephemeral=\"true\">metrics data</mcp:context>";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 metrics", 1, result.getMetrics().size());
	}

	@Test
	public void TestCategorizeReasoning() {
		logger.info("TestCategorizeReasoning");

		String content = "<mcp:context type=\"reasoning\" ephemeral=\"true\">{\"steps\":[\"think\"]}</mcp:context>";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 reasoning", 1, result.getReasoning().size());
	}

	@Test
	public void TestCategorizeMedia() {
		logger.info("TestCategorizeMedia");

		String content = "Image: <mcp:resource uri=\"am7://media/img-1\" tags=\"photo\" />";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 media", 1, result.getMedia().size());
	}

	// --- MIXED CONTENT ---

	@Test
	public void TestFilterMixedContent() {
		logger.info("TestFilterMixedContent");

		String content = "Start "
			+ "<mcp:context type=\"resource\" uri=\"am7://vector/citations/1\" ephemeral=\"true\">cite</mcp:context>"
			+ " Middle "
			+ "<mcp:context type=\"resource\" uri=\"am7://reminder/u1\" ephemeral=\"true\">remind</mcp:context>"
			+ " End "
			+ "<mcp:context type=\"reasoning\" ephemeral=\"true\">{\"steps\":[]}</mcp:context>";

		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 citation", 1, result.getCitations().size());
		assertEquals("Should have 1 reminder", 1, result.getReminders().size());
		assertEquals("Should have 1 reasoning", 1, result.getReasoning().size());

		assertTrue("Should preserve surrounding text", result.getContent().contains("Start"));
		assertTrue("Should preserve surrounding text", result.getContent().contains("Middle"));
		assertTrue("Should preserve surrounding text", result.getContent().contains("End"));
	}

	// --- RENDER RESOURCES ---

	@Test
	public void TestRenderInlineImages() {
		logger.info("TestRenderInlineImages");

		String content = "Image: <mcp:resource uri=\"am7://media/img-1\" tags=\"photo\" />";
		McpContextFilter filter = new McpContextFilter(false, true);

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertTrue("Should contain img tag", result.getContent().contains("<img"));
		assertTrue("Should contain data-mcp-uri attribute", result.getContent().contains("data-mcp-uri"));
	}

	@Test
	public void TestDontRenderWhenDisabled() {
		logger.info("TestDontRenderWhenDisabled");

		String content = "Image: <mcp:resource uri=\"am7://media/img-1\" tags=\"photo\" />";
		McpContextFilter filter = new McpContextFilter(false, false);

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertTrue("Should not contain img tag", !result.getContent().contains("<img"));
	}

	// --- EDGE CASES ---

	@Test
	public void TestFilterNullContent() {
		logger.info("TestFilterNullContent");

		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(null);

		assertNotNull("Result should not be null", result);
		assertEquals("Content should be empty string", "", result.getContent());
	}

	@Test
	public void TestFilterEmptyContent() {
		logger.info("TestFilterEmptyContent");

		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter("");

		assertNotNull("Result should not be null", result);
		assertEquals("Content should be empty string", "", result.getContent());
	}

	@Test
	public void TestFilterPlainText() {
		logger.info("TestFilterPlainText");

		String content = "Just plain text without any MCP contexts.";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertEquals("Content should be unchanged", content, result.getContent());
		assertTrue("No citations", result.getCitations().isEmpty());
		assertTrue("No reminders", result.getReminders().isEmpty());
		assertTrue("No keyframes", result.getKeyframes().isEmpty());
		assertTrue("No metrics", result.getMetrics().isEmpty());
		assertTrue("No reasoning", result.getReasoning().isEmpty());
	}

	@Test
	public void TestPreservesNonContextText() {
		logger.info("TestPreservesNonContextText");

		String content = "Start <mcp:context type=\"x\" ephemeral=\"true\">hidden</mcp:context> End";
		McpContextFilter filter = new McpContextFilter();

		McpFilterResult result = filter.filter(content);

		assertNotNull("Result should not be null", result);
		assertTrue("Should contain Start", result.getContent().contains("Start"));
		assertTrue("Should contain End", result.getContent().contains("End"));
	}

	// --- FULL CHAT MESSAGE SIMULATION ---

	@Test
	public void TestFilterFullChatResponse() {
		logger.info("TestFilterFullChatResponse");

		// Simulate a full chat response with all MCP block types
		StringBuilder sb = new StringBuilder();
		sb.append("<mcp:context type=\"resource\" uri=\"am7://vector/citations/chat-001\" ephemeral=\"true\">\n");
		sb.append("{\"schema\":\"urn:am7:vector:search-result\",\"data\":{\"results\":[]}}\n");
		sb.append("</mcp:context>\n");
		sb.append("<mcp:context type=\"resource\" uri=\"am7://reminder/user-42\" ephemeral=\"true\">\n");
		sb.append("{\"schema\":\"urn:am7:narrative:reminder\",\"data\":{\"items\":[]}}\n");
		sb.append("</mcp:context>\n");
		sb.append("Hello! Based on our previous conversation, I can help you with that.\n");
		sb.append("Here is an image: <mcp:resource uri=\"am7://media/data.data/img-456\" tags=\"diagram\" />\n");
		sb.append("<mcp:context type=\"reasoning\" ephemeral=\"true\">{\"steps\":[\"analyze\",\"respond\"]}</mcp:context>");

		McpContextFilter filter = new McpContextFilter();
		McpFilterResult result = filter.filter(sb.toString());

		assertNotNull("Result should not be null", result);
		assertEquals("Should have 1 citation", 1, result.getCitations().size());
		assertEquals("Should have 1 reminder", 1, result.getReminders().size());
		assertEquals("Should have 1 reasoning", 1, result.getReasoning().size());
		assertEquals("Should have 1 media", 1, result.getMedia().size());

		assertTrue("User-facing text should be preserved", result.getContent().contains("Hello! Based on our previous conversation"));
	}
}
