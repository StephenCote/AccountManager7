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
import org.cote.accountmanager.mcp.Am7Uri;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

/**
 * AM7 implementation of IResourceProvider.
 * Exposes AM7 documents as MCP resources via AccessPoint with PBAC enforcement.
 */
public class Am7ResourceProvider implements IResourceProvider {

	private static final Logger logger = LogManager.getLogger(Am7ResourceProvider.class);
	private static final int DEFAULT_PAGE_SIZE = 50;

	@Override
	public Map<String, Object> listResources(McpSession session, String cursor) {
		Map<String, Object> result = new HashMap<>();
		List<McpJsonRpc.Resource> resources = new ArrayList<>();

		try {
			BaseRecord user = session.getUser();
			long startRecord = 0;
			if (cursor != null && !cursor.isEmpty()) {
				startRecord = Long.parseLong(cursor);
			}

			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.setRequestRange(startRecord, DEFAULT_PAGE_SIZE);
			q.setRequest(new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_DESCRIPTION,
				FieldNames.FIELD_ORGANIZATION_PATH, FieldNames.FIELD_GROUP_PATH
			});

			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			BaseRecord[] records = qr.getResults();

			for (BaseRecord rec : records) {
				String uri = Am7Uri.toUri(rec);
				String name = rec.get(FieldNames.FIELD_NAME);
				String desc = rec.get(FieldNames.FIELD_DESCRIPTION);
				String contentType = rec.get(FieldNames.FIELD_CONTENT_TYPE);

				resources.add(new McpJsonRpc.Resource(uri, name, desc, contentType));
			}

			result.put("resources", resources);

			if (records.length >= DEFAULT_PAGE_SIZE) {
				result.put("nextCursor", String.valueOf(startRecord + DEFAULT_PAGE_SIZE));
			}
		}
		catch (Exception e) {
			logger.error("Error listing resources", e);
			result.put("resources", resources);
		}

		return result;
	}

	@Override
	public List<McpJsonRpc.ResourceContent> readResource(McpSession session, String uri) {
		List<McpJsonRpc.ResourceContent> contents = new ArrayList<>();

		try {
			BaseRecord user = session.getUser();
			Am7Uri parsed = Am7Uri.parse(uri);
			if (parsed == null) {
				logger.warn("Failed to parse URI: " + uri);
				return contents;
			}

			String type = parsed.getType();
			String objectId = parsed.getId();

			if (type == null || objectId == null) {
				logger.warn("URI missing type or id: " + uri);
				return contents;
			}

			Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.planMost(false);

			BaseRecord record = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if (record == null) {
				logger.warn("Resource not found: " + uri);
				return contents;
			}

			String contentType = record.get(FieldNames.FIELD_CONTENT_TYPE);
			if (contentType == null) {
				contentType = "application/octet-stream";
			}

			if (isTextContent(contentType)) {
				byte[] bytes = record.get(FieldNames.FIELD_BYTE_STORE);
				String text = (bytes != null) ? new String(bytes, "UTF-8") : "";
				contents.add(McpJsonRpc.ResourceContent.text(uri, contentType, text));
			}
			else {
				byte[] bytes = record.get(FieldNames.FIELD_BYTE_STORE);
				if (bytes != null) {
					String base64 = Base64.getEncoder().encodeToString(bytes);
					contents.add(McpJsonRpc.ResourceContent.blob(uri, contentType, base64));
				}
			}
		}
		catch (Exception e) {
			logger.error("Error reading resource: " + uri, e);
		}

		return contents;
	}

	@Override
	public List<McpJsonRpc.ResourceTemplate> listTemplates(McpSession session) {
		List<McpJsonRpc.ResourceTemplate> templates = new ArrayList<>();

		templates.add(new McpJsonRpc.ResourceTemplate(
			"am7://{organization}/data.data/{objectId}",
			"AM7 Document",
			"Read a document by organization and object ID",
			"text/plain"
		));

		templates.add(new McpJsonRpc.ResourceTemplate(
			"am7://{organization}/vector/search?q={query}",
			"AM7 Vector Search",
			"Semantic search across vectorized documents",
			"application/json"
		));

		templates.add(new McpJsonRpc.ResourceTemplate(
			"am7://{organization}/olio.llm.chatRequest/{objectId}",
			"AM7 Chat Session",
			"Read a chat session's message history",
			"application/json"
		));

		return templates;
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
