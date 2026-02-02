package org.cote.accountmanager.objects.tests.mcp;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.mcp.Am7Uri;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * Shared utility methods for MCP test classes.
 * Follows the pattern established by OlioTestUtil.
 */
public class McpTestUtil {

	public static final Logger logger = LogManager.getLogger(McpTestUtil.class);

	/**
	 * Get or create a chat configuration using the configured LLM service type.
	 * Mirrors the pattern from OlioTestUtil.getChatConfig.
	 */
	public static BaseRecord getChatConfig(BaseRecord user, String name, Properties testProperties) {
		LLMServiceEnumType serviceType = LLMServiceEnumType.valueOf(
			testProperties.getProperty("test.llm.type").toUpperCase()
		);
		return OlioTestUtil.getChatConfig(user, serviceType, serviceType.toString() + " " + name, testProperties);
	}

	/**
	 * Get or create a prompt configuration.
	 */
	public static BaseRecord getPromptConfig(BaseRecord user, String name) {
		return OlioTestUtil.getObjectPromptConfig(user, name);
	}

	/**
	 * Create a chat request and session, returning the OpenAIRequest.
	 */
	public static OpenAIRequest createChatSession(BaseRecord user, String chatName, BaseRecord cfg, BaseRecord pcfg) {
		BaseRecord creq = ChatUtil.getCreateChatRequest(user, chatName, cfg, pcfg);
		if (creq == null) {
			logger.error("Failed to create chat request");
			return null;
		}
		return ChatUtil.getChatSession(user, chatName, cfg, pcfg);
	}

	/**
	 * Build MCP citation context for a chat session.
	 */
	public static String buildCitationContext(String chatId, String query, List<Map<String, Object>> results) {
		McpContextBuilder builder = new McpContextBuilder();
		builder.addResource(
			Am7Uri.builder()
				.organization("default")
				.type("vector")
				.id("citations/" + chatId)
				.build(),
			"urn:am7:vector:search-result",
			Map.of(
				"query", query,
				"results", results
			),
			true
		);
		return builder.build();
	}

	/**
	 * Build a full MCP context injection string with citations, reminders, and keyframes.
	 */
	public static String buildFullMcpContext(String chatId, String query, String userId) {
		McpContextBuilder builder = new McpContextBuilder();

		// Add citation context
		builder.addResource(
			"am7://default/vector/citations/" + chatId,
			"urn:am7:vector:search-result",
			Map.of(
				"query", query,
				"results", List.of(
					Map.of("uri", "am7://default/data.data/doc-1", "content", "Relevant context...", "score", 0.85)
				)
			),
			true
		);

		// Add reminder context
		builder.addReminder(
			"am7://reminder/" + userId,
			List.of(Map.of("key", "context", "value", "MCP integration test"))
		);

		// Add keyframe
		builder.addKeyframe(
			"am7://keyframe/scene-" + chatId,
			Map.of("location", "test", "mode", "mcp")
		);

		return builder.build();
	}

	/**
	 * Generate a unique chat name for testing.
	 */
	public static String uniqueChatName(String prefix) {
		return prefix + " " + UUID.randomUUID().toString();
	}

	/**
	 * Build an am7:// URI for a BaseRecord.
	 * Returns null if the record lacks required fields.
	 */
	public static String toMcpUri(BaseRecord record) {
		return Am7Uri.toUri(record);
	}

	/**
	 * Set system prompt on a prompt config and persist the update.
	 */
	public static void setSystemPrompt(BaseRecord user, BaseRecord pcfg, String prompt) {
		List<String> system = pcfg.get("system");
		system.clear();
		system.add(prompt);
		IOSystem.getActiveContext().getAccessPoint().update(user, pcfg);
	}

	/**
	 * Build MCP citation context blocks from vector search results.
	 * Each result becomes a separate MCP resource block tagged with the source document URI.
	 */
	public static String buildMcpCitationsFromResults(BaseRecord sourceDoc, List<BaseRecord> results) {
		McpContextBuilder builder = new McpContextBuilder();
		String docUri = Am7Uri.toUri(sourceDoc);
		String docName = sourceDoc.get(FieldNames.FIELD_NAME);
		for (BaseRecord chunk : results) {
			int chunkNum = chunk.get(FieldNames.FIELD_CHUNK);
			String content = chunk.get(FieldNames.FIELD_CONTENT);
			double score = chunk.get(FieldNames.FIELD_SCORE);
			builder.addResource(
				docUri + "/chunk/" + chunkNum,
				"urn:am7:vector:search-result",
				Map.of(
					"content", content != null ? content : "",
					"chunk", chunkNum,
					"score", score,
					"source", docName != null ? docName : "unknown"
				),
				true
			);
		}
		return builder.build();
	}
}
