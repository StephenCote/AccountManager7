package org.cote.accountmanager.mcp.server;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.VectorUtil;

/**
 * AM7 implementation of IToolProvider.
 * Exposes vector search, document operations, and chat history as MCP tools.
 */
public class Am7ToolProvider implements IToolProvider {

	private static final Logger logger = LogManager.getLogger(Am7ToolProvider.class);

	@Override
	public List<McpJsonRpc.Tool> listTools(McpSession session) {
		List<McpJsonRpc.Tool> tools = new ArrayList<>();

		tools.add(new McpJsonRpc.Tool(
			"am7_vector_search",
			"Semantic and keyword hybrid search across vectorized documents in AccountManager7",
			buildVectorSearchSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_document_list",
			"List documents in a group path within AccountManager7",
			buildDocumentListSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_document_read",
			"Read a document's content from AccountManager7 by object ID",
			buildDocumentReadSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_chat_history",
			"Retrieve formatted chat history from an AccountManager7 chat session",
			buildChatHistorySchema()
		));

		return tools;
	}

	@Override
	public McpJsonRpc.ToolResult callTool(McpSession session, String toolName, Map<String, Object> arguments) {
		try {
			switch (toolName) {
				case "am7_vector_search":
					return vectorSearch(session, arguments);
				case "am7_document_list":
					return documentList(session, arguments);
				case "am7_document_read":
					return documentRead(session, arguments);
				case "am7_chat_history":
					return chatHistory(session, arguments);
				default:
					return McpJsonRpc.ToolResult.error("Unknown tool: " + toolName);
			}
		}
		catch (Exception e) {
			logger.error("Error calling tool: " + toolName, e);
			return McpJsonRpc.ToolResult.error("Tool execution failed: " + e.getMessage());
		}
	}

	// =========================================================================
	// Tool implementations
	// =========================================================================

	private McpJsonRpc.ToolResult vectorSearch(McpSession session, Map<String, Object> arguments) {
		String query = (String) arguments.get("query");
		if (query == null || query.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'query' parameter is required");
		}

		int limit = getIntArg(arguments, "limit", 10);
		double distance = getDoubleArg(arguments, "distance", 0.6);
		String type = (String) arguments.get("type");
		String objectId = (String) arguments.get("objectId");

		BaseRecord user = session.getUser();
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();

		BaseRecord refRecord = null;
		if (type != null && objectId != null) {
			try {
				Query rq = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
				rq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
				rq.planMost(true);
				refRecord = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
			}
			catch (Exception e) {
				logger.warn("Failed to resolve reference record: " + type + "/" + objectId, e);
			}
		}

		String searchType = (type != null) ? type : ModelNames.MODEL_DATA;
		List<BaseRecord> results = vu.find(refRecord, searchType, query, limit, distance, false);
		List<BaseRecord> sorted = vu.sortAndLimit(results, limit);

		StringBuilder sb = new StringBuilder();
		sb.append("Found ").append(sorted.size()).append(" results for: ").append(query).append("\n\n");

		for (int i = 0; i < sorted.size(); i++) {
			BaseRecord rec = sorted.get(i);
			String content = rec.get("content");
			String refType = rec.get(FieldNames.FIELD_VECTOR_REFERENCE_TYPE);
			int chunk = rec.get("chunk");
			double score = rec.get("score");

			sb.append("--- Result ").append(i + 1).append(" ---\n");
			sb.append("Type: ").append(refType != null ? refType : "unknown").append("\n");
			sb.append("Chunk: ").append(chunk).append("\n");
			sb.append("Score: ").append(String.format("%.4f", score)).append("\n");
			sb.append("Content: ").append(content != null ? content : "").append("\n\n");
		}

		return McpJsonRpc.ToolResult.success(sb.toString());
	}

	private McpJsonRpc.ToolResult documentList(McpSession session, Map<String, Object> arguments) {
		String path = (String) arguments.get("path");
		if (path == null || path.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'path' parameter is required");
		}

		String type = (String) arguments.getOrDefault("type", ModelNames.MODEL_DATA);
		int startRecord = getIntArg(arguments, "startRecord", 0);
		int recordCount = getIntArg(arguments, "recordCount", 25);

		BaseRecord user = session.getUser();

		try {
			/// Find the group by path
			Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PATH, path);
			gq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			BaseRecord group = IOSystem.getActiveContext().getAccessPoint().find(user, gq);

			if (group == null) {
				return McpJsonRpc.ToolResult.error("Group not found: " + path);
			}

			long groupId = group.get(FieldNames.FIELD_ID);

			Query q = QueryUtil.createQuery(type, FieldNames.FIELD_GROUP_ID, groupId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.setRequestRange(startRecord, recordCount);
			q.setRequest(new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_DESCRIPTION
			});

			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			BaseRecord[] records = qr.getResults();

			StringBuilder sb = new StringBuilder();
			sb.append("Documents in ").append(path).append(" (").append(records.length).append(" results):\n\n");

			for (BaseRecord rec : records) {
				String name = rec.get(FieldNames.FIELD_NAME);
				String objId = rec.get(FieldNames.FIELD_OBJECT_ID);
				String contentType = rec.get(FieldNames.FIELD_CONTENT_TYPE);
				String desc = rec.get(FieldNames.FIELD_DESCRIPTION);

				sb.append("- ").append(name);
				if (contentType != null) sb.append(" [").append(contentType).append("]");
				sb.append("\n  objectId: ").append(objId);
				if (desc != null && !desc.isEmpty()) sb.append("\n  description: ").append(desc);
				sb.append("\n");
			}

			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error listing documents at path: " + path, e);
			return McpJsonRpc.ToolResult.error("Failed to list documents: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult documentRead(McpSession session, Map<String, Object> arguments) {
		String objectId = (String) arguments.get("objectId");
		if (objectId == null || objectId.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'objectId' parameter is required");
		}

		String type = (String) arguments.getOrDefault("type", ModelNames.MODEL_DATA);
		BaseRecord user = session.getUser();

		try {
			Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(false);

			BaseRecord record = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if (record == null) {
				return McpJsonRpc.ToolResult.error("Document not found: " + objectId);
			}

			String name = record.get(FieldNames.FIELD_NAME);
			String contentType = record.get(FieldNames.FIELD_CONTENT_TYPE);
			byte[] bytes = record.get(FieldNames.FIELD_BYTE_STORE);

			StringBuilder sb = new StringBuilder();
			sb.append("Document: ").append(name).append("\n");
			sb.append("Content-Type: ").append(contentType != null ? contentType : "unknown").append("\n");
			sb.append("Object ID: ").append(objectId).append("\n\n");

			if (bytes != null && bytes.length > 0) {
				if (isTextContent(contentType)) {
					sb.append(new String(bytes, "UTF-8"));
				}
				else {
					sb.append("[Binary content, ").append(bytes.length).append(" bytes, base64: ");
					sb.append(Base64.getEncoder().encodeToString(bytes));
					sb.append("]");
				}
			}
			else {
				sb.append("[No content]");
			}

			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error reading document: " + objectId, e);
			return McpJsonRpc.ToolResult.error("Failed to read document: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult chatHistory(McpSession session, Map<String, Object> arguments) {
		String chatName = (String) arguments.get("chatName");
		if (chatName == null || chatName.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'chatName' parameter is required");
		}

		BaseRecord user = session.getUser();

		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_NAME, chatName);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);

			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if (chatReq == null) {
				return McpJsonRpc.ToolResult.error("Chat session not found: " + chatName);
			}

			BaseRecord sessionRec = chatReq.get("session");
			if (sessionRec == null) {
				return McpJsonRpc.ToolResult.error("Chat session has no message history");
			}

			OpenAIRequest oreq = new OpenAIRequest(sessionRec);
			List<String> history = ChatUtil.getFormattedChatHistory(oreq, null, -1, true);

			StringBuilder sb = new StringBuilder();
			sb.append("Chat: ").append(chatName).append("\n");
			sb.append("Messages: ").append(history.size()).append("\n\n");

			for (String line : history) {
				sb.append(line).append("\n");
			}

			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error reading chat history: " + chatName, e);
			return McpJsonRpc.ToolResult.error("Failed to read chat history: " + e.getMessage());
		}
	}

	// =========================================================================
	// JSON Schema builders for tool inputSchema
	// =========================================================================

	private Map<String, Object> buildVectorSearchSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("query", Map.of("type", "string", "description", "Search query text"));
		props.put("limit", Map.of("type", "integer", "description", "Maximum number of results (default: 10)", "default", 10));
		props.put("distance", Map.of("type", "number", "description", "Minimum similarity threshold (default: 0.6)", "default", 0.6));
		props.put("type", Map.of("type", "string", "description", "Optional model type filter (e.g., 'data.data')"));
		props.put("objectId", Map.of("type", "string", "description", "Optional reference document object ID to scope search"));

		schema.put("properties", props);
		schema.put("required", List.of("query"));
		return schema;
	}

	private Map<String, Object> buildDocumentListSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("path", Map.of("type", "string", "description", "Group path (e.g., '~/Documents')"));
		props.put("type", Map.of("type", "string", "description", "Model type (default: 'data.data')", "default", "data.data"));
		props.put("startRecord", Map.of("type", "integer", "description", "Pagination start index (default: 0)", "default", 0));
		props.put("recordCount", Map.of("type", "integer", "description", "Number of records to return (default: 25)", "default", 25));

		schema.put("properties", props);
		schema.put("required", List.of("path"));
		return schema;
	}

	private Map<String, Object> buildDocumentReadSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("objectId", Map.of("type", "string", "description", "The document's objectId"));
		props.put("type", Map.of("type", "string", "description", "Model type (default: 'data.data')", "default", "data.data"));

		schema.put("properties", props);
		schema.put("required", List.of("objectId"));
		return schema;
	}

	private Map<String, Object> buildChatHistorySchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("chatName", Map.of("type", "string", "description", "Name of the chat session to retrieve"));

		schema.put("properties", props);
		schema.put("required", List.of("chatName"));
		return schema;
	}

	// =========================================================================
	// Utility methods
	// =========================================================================

	private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
		Object val = args.get(key);
		if (val instanceof Number) return ((Number) val).intValue();
		if (val instanceof String) {
			try { return Integer.parseInt((String) val); }
			catch (NumberFormatException e) { return defaultValue; }
		}
		return defaultValue;
	}

	private double getDoubleArg(Map<String, Object> args, String key, double defaultValue) {
		Object val = args.get(key);
		if (val instanceof Number) return ((Number) val).doubleValue();
		if (val instanceof String) {
			try { return Double.parseDouble((String) val); }
			catch (NumberFormatException e) { return defaultValue; }
		}
		return defaultValue;
	}

	private boolean isTextContent(String contentType) {
		if (contentType == null) return false;
		return contentType.startsWith("text/")
			|| contentType.contains("json")
			|| contentType.contains("xml")
			|| contentType.contains("javascript")
			|| contentType.contains("csv");
	}
}
