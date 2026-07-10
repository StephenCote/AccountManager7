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
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PageIndexNodeEnumType;
import org.cote.accountmanager.util.PageIndexUtil;
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

		tools.add(new McpJsonRpc.Tool(
			"am7_session_attach",
			"Attach a context object (character, document, config) to a chat session",
			buildSessionAttachSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_session_detach",
			"Detach the context object from a chat session",
			buildSessionDetachSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_session_context",
			"List current context bindings for a chat session (chatConfig, promptConfig, characters, context object)",
			buildSessionContextSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_pageindex_search",
			"Ranked search over a document's PageIndex (hierarchical table-of-contents index) — returns the most relevant leaf passages for a query",
			buildPageIndexSearchSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_pageindex_structure",
			"Read a document's PageIndex structure — the full ROOT/SECTION/CHUNK outline with titles and summaries, for reasoning over document organization before drilling into content",
			buildPageIndexStructureSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_pageindex_section",
			"List the child nodes of a PageIndex node (navigate one level down the outline)",
			buildPageIndexSectionSchema()
		));

		tools.add(new McpJsonRpc.Tool(
			"am7_pageindex_content",
			"Read the full text content of a PageIndex leaf (CHUNK) node",
			buildPageIndexContentSchema()
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
				case "am7_session_attach":
					return sessionAttach(session, arguments);
				case "am7_session_detach":
					return sessionDetach(session, arguments);
				case "am7_session_context":
					return sessionContext(session, arguments);
				case "am7_pageindex_search":
					return pageIndexSearch(session, arguments);
				case "am7_pageindex_structure":
					return pageIndexStructure(session, arguments);
				case "am7_pageindex_section":
					return pageIndexSection(session, arguments);
				case "am7_pageindex_content":
					return pageIndexContent(session, arguments);
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

	private McpJsonRpc.ToolResult sessionAttach(McpSession session, Map<String, Object> arguments) {
		String sessionId = (String) arguments.get("sessionId");
		String attachType = (String) arguments.get("attachType");
		String objectId = (String) arguments.get("objectId");

		if (sessionId == null || sessionId.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'sessionId' parameter is required");
		}
		if (attachType == null || attachType.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'attachType' parameter is required (chatConfig, promptConfig, systemCharacter, userCharacter, context)");
		}
		if (objectId == null || objectId.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'objectId' parameter is required");
		}

		BaseRecord user = session.getUser();

		try {
			/// Find the chat request (session)
			Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, sessionId);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			sq.planMost(true);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return McpJsonRpc.ToolResult.error("Chat session not found: " + sessionId);
			}

			switch (attachType) {
				case "chatConfig": {
					BaseRecord cfg = findByObjectId(user, OlioModelNames.MODEL_CHAT_CONFIG, objectId);
					if (cfg == null) return McpJsonRpc.ToolResult.error("Chat config not found: " + objectId);
					chatReq.set("chatConfig", cfg);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					return McpJsonRpc.ToolResult.success("Attached chatConfig '" + cfg.get(FieldNames.FIELD_NAME) + "' to session");
				}
				case "promptConfig": {
					BaseRecord cfg = findByObjectId(user, OlioModelNames.MODEL_PROMPT_CONFIG, objectId);
					if (cfg == null) return McpJsonRpc.ToolResult.error("Prompt config not found: " + objectId);
					chatReq.set("promptConfig", cfg);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					return McpJsonRpc.ToolResult.success("Attached promptConfig '" + cfg.get(FieldNames.FIELD_NAME) + "' to session");
				}
				case "systemCharacter":
				case "userCharacter": {
					/// Characters are on the chatConfig, not the chatRequest
					BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
					if (chatConfig == null) return McpJsonRpc.ToolResult.error("Session has no chatConfig");
					BaseRecord character = findByObjectId(user, OlioModelNames.MODEL_CHAR_PERSON, objectId);
					if (character == null) return McpJsonRpc.ToolResult.error("Character not found: " + objectId);
					chatConfig.set(attachType, character);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig);
					return McpJsonRpc.ToolResult.success("Attached " + attachType + " '" + character.get(FieldNames.FIELD_NAME) + "' to chatConfig");
				}
				case "context": {
					/// Generic context: resolve objectType to find the object, then set contextType + context
					String objectType = (String) arguments.get("objectType");
					if (objectType == null || objectType.isEmpty()) {
						return McpJsonRpc.ToolResult.error("'objectType' is required when attachType is 'context' (e.g. 'data.data', 'olio.charPerson')");
					}
					BaseRecord contextObj = findByObjectId(user, objectType, objectId);
					if (contextObj == null) return McpJsonRpc.ToolResult.error(objectType + " not found: " + objectId);
					chatReq.set("contextType", objectType);
					chatReq.set("context", contextObj);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					return McpJsonRpc.ToolResult.success("Attached context " + objectType + " '" + contextObj.get(FieldNames.FIELD_NAME) + "' to session");
				}
				default:
					return McpJsonRpc.ToolResult.error("Unknown attachType: " + attachType + ". Valid: chatConfig, promptConfig, systemCharacter, userCharacter, context");
			}
		}
		catch (Exception e) {
			logger.error("Error attaching to session: " + sessionId, e);
			return McpJsonRpc.ToolResult.error("Failed to attach: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult sessionDetach(McpSession session, Map<String, Object> arguments) {
		String sessionId = (String) arguments.get("sessionId");
		String detachType = (String) arguments.get("detachType");

		if (sessionId == null || sessionId.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'sessionId' parameter is required");
		}
		if (detachType == null || detachType.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'detachType' parameter is required (systemCharacter, userCharacter, context)");
		}

		BaseRecord user = session.getUser();

		try {
			Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, sessionId);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			sq.planMost(true);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return McpJsonRpc.ToolResult.error("Chat session not found: " + sessionId);
			}

			switch (detachType) {
				case "systemCharacter":
				case "userCharacter": {
					BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
					if (chatConfig == null) return McpJsonRpc.ToolResult.error("Session has no chatConfig");
					chatConfig.set(detachType, null);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig);
					return McpJsonRpc.ToolResult.success("Detached " + detachType + " from chatConfig");
				}
				case "context": {
					chatReq.set("contextType", null);
					chatReq.set("context", null);
					IOSystem.getActiveContext().getAccessPoint().update(user, chatReq);
					return McpJsonRpc.ToolResult.success("Detached context from session");
				}
				default:
					return McpJsonRpc.ToolResult.error("Cannot detach " + detachType + ". Only systemCharacter, userCharacter, and context can be detached.");
			}
		}
		catch (Exception e) {
			logger.error("Error detaching from session: " + sessionId, e);
			return McpJsonRpc.ToolResult.error("Failed to detach: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult sessionContext(McpSession session, Map<String, Object> arguments) {
		String sessionId = (String) arguments.get("sessionId");
		if (sessionId == null || sessionId.isEmpty()) {
			return McpJsonRpc.ToolResult.error("'sessionId' parameter is required");
		}

		BaseRecord user = session.getUser();

		try {
			Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, sessionId);
			sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			sq.planMost(true);
			BaseRecord chatReq = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
			if (chatReq == null) {
				return McpJsonRpc.ToolResult.error("Chat session not found: " + sessionId);
			}

			StringBuilder sb = new StringBuilder();
			sb.append("Session: ").append((String) chatReq.get(FieldNames.FIELD_NAME)).append("\n");
			sb.append("ObjectId: ").append(sessionId).append("\n\n");

			/// ChatConfig
			BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.get("chatConfig"));
			if (chatConfig != null) {
				sb.append("ChatConfig: ").append((String) chatConfig.get(FieldNames.FIELD_NAME));
				sb.append(" (").append((String) chatConfig.get(FieldNames.FIELD_OBJECT_ID)).append(")\n");
				sb.append("  Model: ").append((String) chatConfig.get("model")).append("\n");
				sb.append("  Stream: ").append(String.valueOf(chatConfig.get("stream"))).append("\n");
				sb.append("  Prune: ").append(String.valueOf(chatConfig.get("prune"))).append("\n");

				BaseRecord sysCh = chatConfig.get("systemCharacter");
				if (sysCh != null) {
					sysCh = OlioUtil.getFullRecord(sysCh);
					if (sysCh != null) {
						sb.append("  SystemCharacter: ").append((String) sysCh.get(FieldNames.FIELD_NAME));
						sb.append(" (").append((String) sysCh.get(FieldNames.FIELD_OBJECT_ID)).append(")\n");
					}
				}
				BaseRecord usrCh = chatConfig.get("userCharacter");
				if (usrCh != null) {
					usrCh = OlioUtil.getFullRecord(usrCh);
					if (usrCh != null) {
						sb.append("  UserCharacter: ").append((String) usrCh.get(FieldNames.FIELD_NAME));
						sb.append(" (").append((String) usrCh.get(FieldNames.FIELD_OBJECT_ID)).append(")\n");
					}
				}
			} else {
				sb.append("ChatConfig: (none)\n");
			}

			/// PromptConfig
			BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.get("promptConfig"));
			if (promptConfig != null) {
				sb.append("PromptConfig: ").append((String) promptConfig.get(FieldNames.FIELD_NAME));
				sb.append(" (").append((String) promptConfig.get(FieldNames.FIELD_OBJECT_ID)).append(")\n");
			} else {
				sb.append("PromptConfig: (none)\n");
			}

			/// Generic context
			String contextType = chatReq.get("contextType");
			if (contextType != null && !contextType.isEmpty()) {
				BaseRecord contextObj = OlioUtil.getFullRecord(chatReq.get("context"));
				if (contextObj != null) {
					sb.append("Context: ").append(contextType).append(" '").append((String) contextObj.get(FieldNames.FIELD_NAME));
					sb.append("' (").append((String) contextObj.get(FieldNames.FIELD_OBJECT_ID)).append(")\n");
				} else {
					sb.append("Context: ").append(contextType).append(" (reference not resolved)\n");
				}
			} else {
				sb.append("Context: (none)\n");
			}

			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error reading session context: " + sessionId, e);
			return McpJsonRpc.ToolResult.error("Failed to read session context: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult pageIndexSearch(McpSession session, Map<String, Object> arguments) {
		String type = (String) arguments.get("type");
		String objectId = (String) arguments.get("objectId");
		String query = (String) arguments.get("query");
		if (type == null || type.isEmpty()) return McpJsonRpc.ToolResult.error("'type' parameter is required");
		if (objectId == null || objectId.isEmpty()) return McpJsonRpc.ToolResult.error("'objectId' parameter is required");
		if (query == null || query.isEmpty()) return McpJsonRpc.ToolResult.error("'query' parameter is required");

		int limit = getIntArg(arguments, "limit", 5);
		BaseRecord user = session.getUser();

		try {
			/// PBAC-gated: resolves + authorizes (canRead) the source doc before touching PageIndexUtil.retrieve.
			List<BaseRecord> hits = IOSystem.getActiveContext().getAccessPoint().pageIndexRetrieve(user, type, objectId, query, limit);

			StringBuilder sb = new StringBuilder();
			sb.append("Found ").append(hits.size()).append(" PageIndex result(s) for: ").append(query).append("\n\n");
			for (int i = 0; i < hits.size(); i++) {
				BaseRecord node = hits.get(i);
				Object score = node.get(FieldNames.FIELD_SCORE);
				String content = node.get(FieldNames.FIELD_CONTENT);
				String nodeId = node.get(FieldNames.FIELD_OBJECT_ID);

				sb.append("--- Result ").append(i + 1).append(" ---\n");
				sb.append("nodeId: ").append(nodeId).append("\n");
				sb.append("Score: ").append(score != null ? String.format("%.4f", (double) score) : "n/a").append("\n");
				sb.append("Content: ").append(content != null ? content : "").append("\n\n");
			}
			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error searching PageIndex: " + type + "/" + objectId, e);
			return McpJsonRpc.ToolResult.error("Failed to search PageIndex: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult pageIndexStructure(McpSession session, Map<String, Object> arguments) {
		String type = (String) arguments.get("type");
		String objectId = (String) arguments.get("objectId");
		if (type == null || type.isEmpty()) return McpJsonRpc.ToolResult.error("'type' parameter is required");
		if (objectId == null || objectId.isEmpty()) return McpJsonRpc.ToolResult.error("'objectId' parameter is required");

		BaseRecord user = session.getUser();
		try {
			/// PBAC-gated: resolves + authorizes (canRead) the source doc before touching PageIndexUtil.getTree.
			List<BaseRecord> nodes = IOSystem.getActiveContext().getAccessPoint().pageIndexTree(user, type, objectId);
			if (nodes.isEmpty()) {
				return McpJsonRpc.ToolResult.success("No PageIndex found for " + type + "/" + objectId + " (not built yet, or not accessible).");
			}

			StringBuilder sb = new StringBuilder();
			sb.append("PageIndex structure for ").append(type).append("/").append(objectId).append(" (").append(nodes.size()).append(" node(s)):\n\n");
			for (BaseRecord node : nodes) {
				appendStructureLine(sb, node);
			}
			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error reading PageIndex structure: " + type + "/" + objectId, e);
			return McpJsonRpc.ToolResult.error("Failed to read PageIndex structure: " + e.getMessage());
		}
	}

	private void appendStructureLine(StringBuilder sb, BaseRecord node) {
		PageIndexNodeEnumType nodeType = node.getEnum(FieldNames.FIELD_NODE_TYPE);
		int level = node.get(FieldNames.FIELD_LEVEL);
		String title = node.get(FieldNames.FIELD_TITLE);
		String summary = node.get(FieldNames.FIELD_SUMMARY);
		String nodeId = node.get(FieldNames.FIELD_OBJECT_ID);

		for (int i = 0; i < level; i++) sb.append("  ");
		sb.append("[").append(nodeType).append("] ");
		if (title != null && !title.isEmpty()) sb.append(title).append(" ");
		sb.append("(nodeId=").append(nodeId).append(")\n");
		if (summary != null && !summary.isEmpty()) {
			for (int i = 0; i < level; i++) sb.append("  ");
			sb.append("  ").append(snippet(summary, 200)).append("\n");
		}
	}

	private McpJsonRpc.ToolResult pageIndexSection(McpSession session, Map<String, Object> arguments) {
		String nodeId = (String) arguments.get("nodeId");
		if (nodeId == null || nodeId.isEmpty()) return McpJsonRpc.ToolResult.error("'nodeId' parameter is required");

		BaseRecord user = session.getUser();
		try {
			BaseRecord node = resolvePageIndexNode(user, nodeId);
			if (node == null) return McpJsonRpc.ToolResult.error("PageIndex node not found or not accessible: " + nodeId);

			long parentId = node.get(FieldNames.FIELD_ID);
			long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
			Query cq = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_PARENT_ID, parentId);
			cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			cq.setRequest(PageIndexUtil.safeNodeRequestFields());
			cq.setCache(false);
			QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, cq);

			StringBuilder sb = new StringBuilder();
			BaseRecord[] children = (qr != null ? qr.getResults() : new BaseRecord[0]);
			sb.append("Children of nodeId=").append(nodeId).append(" (").append(children.length).append("):\n\n");
			for (BaseRecord child : children) {
				appendStructureLine(sb, child);
			}
			return McpJsonRpc.ToolResult.success(sb.toString());
		}
		catch (Exception e) {
			logger.error("Error reading PageIndex section: " + nodeId, e);
			return McpJsonRpc.ToolResult.error("Failed to read PageIndex section: " + e.getMessage());
		}
	}

	private McpJsonRpc.ToolResult pageIndexContent(McpSession session, Map<String, Object> arguments) {
		String nodeId = (String) arguments.get("nodeId");
		if (nodeId == null || nodeId.isEmpty()) return McpJsonRpc.ToolResult.error("'nodeId' parameter is required");

		BaseRecord user = session.getUser();
		try {
			BaseRecord node = resolvePageIndexNode(user, nodeId);
			if (node == null) return McpJsonRpc.ToolResult.error("PageIndex node not found or not accessible: " + nodeId);

			PageIndexNodeEnumType nodeType = node.getEnum(FieldNames.FIELD_NODE_TYPE);
			if (nodeType != PageIndexNodeEnumType.CHUNK) {
				return McpJsonRpc.ToolResult.error("nodeId " + nodeId + " is a " + nodeType + ", not a CHUNK leaf — use am7_pageindex_section to navigate to its children first");
			}
			String content = node.get(FieldNames.FIELD_CONTENT);
			return McpJsonRpc.ToolResult.success(content != null ? content : "");
		}
		catch (Exception e) {
			logger.error("Error reading PageIndex content: " + nodeId, e);
			return McpJsonRpc.ToolResult.error("Failed to read PageIndex content: " + e.getMessage());
		}
	}

	/// Resolve a single data.pageIndexNode by objectId with the safe (non-embedding) field projection,
	/// authorized via AccessPoint.find (canRead, using the node's groupId shortcut) — mirrors
	/// PageIndexService.findSafeNode in Service7 (kept separate per module: MCP is Objects7-side transport).
	private BaseRecord resolvePageIndexNode(BaseRecord user, String nodeObjectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_OBJECT_ID, nodeObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(PageIndexUtil.safeNodeRequestFields());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private String snippet(String s, int maxLen) {
		if (s == null) return "";
		String trimmed = s.replaceAll("\\s+", " ").trim();
		return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) + "..." : trimmed;
	}

	/// Find a record by objectId within the user's organization.
	private BaseRecord findByObjectId(BaseRecord user, String modelName, String objectId) {
		try {
			Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(true);
			return IOSystem.getActiveContext().getAccessPoint().find(user, q);
		}
		catch (Exception e) {
			logger.warn("Failed to find " + modelName + " by objectId: " + objectId, e);
			return null;
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

	private Map<String, Object> buildSessionAttachSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("sessionId", Map.of("type", "string", "description", "objectId of the chat session (chatRequest)"));
		props.put("attachType", Map.of("type", "string", "description", "What to attach: chatConfig, promptConfig, systemCharacter, userCharacter, or context"));
		props.put("objectId", Map.of("type", "string", "description", "objectId of the object to attach"));
		props.put("objectType", Map.of("type", "string", "description", "Model type of the context object (required when attachType is 'context', e.g. 'data.data', 'olio.charPerson')"));

		schema.put("properties", props);
		schema.put("required", List.of("sessionId", "attachType", "objectId"));
		return schema;
	}

	private Map<String, Object> buildSessionDetachSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("sessionId", Map.of("type", "string", "description", "objectId of the chat session (chatRequest)"));
		props.put("detachType", Map.of("type", "string", "description", "What to detach: systemCharacter, userCharacter, or context"));

		schema.put("properties", props);
		schema.put("required", List.of("sessionId", "detachType"));
		return schema;
	}

	private Map<String, Object> buildSessionContextSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("sessionId", Map.of("type", "string", "description", "objectId of the chat session (chatRequest)"));

		schema.put("properties", props);
		schema.put("required", List.of("sessionId"));
		return schema;
	}

	private Map<String, Object> buildPageIndexSearchSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("type", Map.of("type", "string", "description", "Model type of the source document (e.g. 'data.data')"));
		props.put("objectId", Map.of("type", "string", "description", "objectId of the source document"));
		props.put("query", Map.of("type", "string", "description", "Search query text"));
		props.put("limit", Map.of("type", "integer", "description", "Maximum number of leaf results (default: 5)", "default", 5));

		schema.put("properties", props);
		schema.put("required", List.of("type", "objectId", "query"));
		return schema;
	}

	private Map<String, Object> buildPageIndexStructureSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("type", Map.of("type", "string", "description", "Model type of the source document (e.g. 'data.data')"));
		props.put("objectId", Map.of("type", "string", "description", "objectId of the source document"));

		schema.put("properties", props);
		schema.put("required", List.of("type", "objectId"));
		return schema;
	}

	private Map<String, Object> buildPageIndexSectionSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("nodeId", Map.of("type", "string", "description", "objectId of the PageIndex node whose children to list (from am7_pageindex_structure or a prior am7_pageindex_section call)"));

		schema.put("properties", props);
		schema.put("required", List.of("nodeId"));
		return schema;
	}

	private Map<String, Object> buildPageIndexContentSchema() {
		Map<String, Object> schema = new HashMap<>();
		schema.put("type", "object");

		Map<String, Object> props = new HashMap<>();
		props.put("nodeId", Map.of("type", "string", "description", "objectId of a PageIndex CHUNK (leaf) node whose text content to read"));

		schema.put("properties", props);
		schema.put("required", List.of("nodeId"));
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
