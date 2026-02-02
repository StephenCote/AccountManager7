package org.cote.accountmanager.objects.tests.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.junit.Test;

public class TestMcpContextBuilder extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestMcpContextBuilder.class);

	@Test
	public void TestBuildCitationContext() {
		logger.info("TestBuildCitationContext");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			"am7://vector/citations/chat-123",
			"urn:am7:vector:search-result",
			Map.of(
				"query", "test query",
				"results", List.of(
					Map.of("uri", "am7://chat/msg-1", "content", "Result 1", "score", 0.9)
				)
			),
			true
		);

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain resource type", output.contains("<mcp:context type=\"resource\""));
		assertTrue("Should contain URI", output.contains("uri=\"am7://vector/citations/chat-123\""));
		assertTrue("Should contain ephemeral flag", output.contains("ephemeral=\"true\""));
		assertTrue("Should contain query", output.contains("query"));
		assertTrue("Should contain closing tag", output.contains("</mcp:context>"));
	}

	@Test
	public void TestBuildReasoningContext() {
		logger.info("TestBuildReasoningContext");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addReasoning(List.of(
			"Step 1: Analyze the request",
			"Step 2: Gather relevant context"
		));

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain reasoning type", output.contains("type=\"reasoning\""));
		assertTrue("Should contain step 1", output.contains("Step 1"));
		assertTrue("Should contain step 2", output.contains("Step 2"));
	}

	@Test
	public void TestBuildMultipleContexts() {
		logger.info("TestBuildMultipleContexts");

		McpContextBuilder builder = new McpContextBuilder();

		builder.addResource("am7://reminder/user-1", "urn:am7:narrative:reminder",
			Map.of("items", List.of("Remember this")), true);

		builder.addResource("am7://keyframe/scene-1", "urn:am7:narrative:keyframe",
			Map.of("state", Map.of("location", "office")), true);

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		int contextCount = output.split("<mcp:context").length - 1;
		assertEquals("Should contain 2 context blocks", 2, contextCount);
	}

	@Test
	public void TestBuildEmptyReturnsEmpty() {
		logger.info("TestBuildEmptyReturnsEmpty");

		McpContextBuilder builder = new McpContextBuilder();
		String output = builder.build();
		assertEquals("Empty builder should return empty string", "", output);
	}

	@Test
	public void TestBuildReminderContext() {
		logger.info("TestBuildReminderContext");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addReminder("am7://reminder/user-123", List.of(
			Map.of("key", "user_preference", "value", "prefers formal tone"),
			Map.of("key", "ongoing_task", "value", "debugging auth flow")
		));

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain reminder URI", output.contains("am7://reminder/user-123"));
		assertTrue("Should contain reminder schema", output.contains("urn:am7:narrative:reminder"));
		assertTrue("Should be ephemeral", output.contains("ephemeral=\"true\""));
	}

	@Test
	public void TestBuildKeyframeContext() {
		logger.info("TestBuildKeyframeContext");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addKeyframe("am7://keyframe/scene-456", Map.of(
			"location", "office",
			"characters", List.of("alice", "bob"),
			"mood", "tense"
		));

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain keyframe URI", output.contains("am7://keyframe/scene-456"));
		assertTrue("Should contain keyframe schema", output.contains("urn:am7:narrative:keyframe"));
	}

	@Test
	public void TestBuildMetricsContext() {
		logger.info("TestBuildMetricsContext");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addMetrics("am7://metrics/biometric", Map.of(
			"dominant_emotion", "happy",
			"emotion_scores", Map.of("happy", 0.85, "neutral", 0.10)
		));

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain metrics URI", output.contains("am7://metrics/biometric"));
		assertTrue("Should be ephemeral", output.contains("ephemeral=\"true\""));
	}

	@Test
	public void TestBuildMediaResource() {
		logger.info("TestBuildMediaResource");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addMediaResource("am7://media/data.data/12345", "portrait, character, fantasy");

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain resource tag", output.contains("<mcp:resource"));
		assertTrue("Should contain URI", output.contains("am7://media/data.data/12345"));
		assertTrue("Should contain tags", output.contains("portrait, character, fantasy"));
		assertTrue("Should be self-closing", output.contains("/>"));
	}

	@Test
	public void TestBuilderSize() {
		logger.info("TestBuilderSize");

		McpContextBuilder builder = new McpContextBuilder();
		assertEquals("Empty builder size is 0", 0, builder.size());

		builder.addReasoning(List.of("step 1"));
		assertEquals("Size after one add is 1", 1, builder.size());

		builder.addReminder("am7://reminder/x", List.of(Map.of("k", "v")));
		assertEquals("Size after two adds is 2", 2, builder.size());
	}

	@Test
	public void TestBuilderClear() {
		logger.info("TestBuilderClear");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addReasoning(List.of("step 1"));
		assertEquals("Size should be 1", 1, builder.size());

		builder.clear();
		assertEquals("Size after clear should be 0", 0, builder.size());
		assertEquals("Build after clear should return empty", "", builder.build());
	}

	@Test
	public void TestBuildNonEphemeralResource() {
		logger.info("TestBuildNonEphemeralResource");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource("am7://data/doc-1", "urn:am7:data:document",
			Map.of("title", "Test Document"), false);

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		assertTrue("Should contain resource type", output.contains("type=\"resource\""));
		assertTrue("Should not contain ephemeral", !output.contains("ephemeral"));
	}

	@Test
	public void TestBuildXmlEscaping() {
		logger.info("TestBuildXmlEscaping");

		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource("am7://test/special&chars", "urn:test",
			Map.of("data", "value"), false);

		String output = builder.build();
		assertNotNull("Output should not be null", output);
		assertTrue("Ampersand should be escaped", output.contains("special&amp;chars"));
	}

	@Test
	public void TestBuildFullChatInjection() {
		logger.info("TestBuildFullChatInjection");

		McpContextBuilder builder = new McpContextBuilder();

		// Simulate full chat context injection with citations, reminder, and keyframe
		builder.addResource(
			"am7://vector/citations/chat-session-001",
			"urn:am7:vector:search-result",
			Map.of(
				"query", "What happened last time?",
				"results", List.of(
					Map.of("uri", "am7://chat/session-001/message-5", "content", "Previous conversation...", "score", 0.89),
					Map.of("uri", "am7://chat/session-001/message-12", "content", "Follow-up discussion...", "score", 0.75)
				)
			),
			true
		);

		builder.addReminder("am7://reminder/user-42", List.of(
			Map.of("key", "tone", "value", "formal")
		));

		builder.addKeyframe("am7://keyframe/scene-current", Map.of(
			"location", "conference room",
			"participants", List.of("user", "assistant")
		));

		String output = builder.build();
		assertNotNull("Output should not be null", output);

		int contextCount = output.split("<mcp:context").length - 1;
		assertEquals("Should have 3 context blocks", 3, contextCount);
		assertTrue("Should contain citations", output.contains("citations"));
		assertTrue("Should contain reminder", output.contains("reminder"));
		assertTrue("Should contain keyframe", output.contains("keyframe"));
	}
}
