package org.cote.accountmanager.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

public class MemoryUtil {
	public static final Logger logger = LogManager.getLogger(MemoryUtil.class);

	private static final String MEMORY_GROUP_PATH = "~/Memories";

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId) {

		BaseRecord memory = null;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, MEMORY_GROUP_PATH);
			plist.parameter(FieldNames.FIELD_NAME, summary != null ? summary.substring(0, Math.min(summary.length(), 64)) : "Memory");

			memory = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_MEMORY, user, null, plist);
			memory.set("content", content);
			memory.set("summary", summary);
			memory.set("memoryType", type);
			memory.set("importance", importance);
			if (sourceUri != null) {
				memory.set("sourceUri", sourceUri);
			}
			if (conversationId != null) {
				memory.set("conversationId", conversationId);
			}

			memory = IOSystem.getActiveContext().getAccessPoint().create(user, memory);
			if (memory == null) {
				logger.error("Failed to persist memory record");
				return null;
			}

			// Create vector embeddings for semantic search
			createMemoryVectors(user, memory, content, type, conversationId);

		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error("Error creating memory: " + e.getMessage());
		}
		return memory;
	}

	private static void createMemoryVectors(BaseRecord user, BaseRecord memory, String content,
			MemoryTypeEnumType type, String conversationId) {

		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available, skipping memory vectorization");
			return;
		}

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_VECTOR_REFERENCE, memory);
			plist.parameter(FieldNames.FIELD_CHUNK, ChunkEnumType.WORD);
			plist.parameter(FieldNames.FIELD_CHUNK_COUNT, 500);
			plist.parameter("content", content);
			plist.parameter("memoryType", type != null ? type.value() : "NOTE");
			plist.parameter("conversationId", conversationId);

			BaseRecord vlist = IOSystem.getActiveContext().getFactory()
				.newInstance(ModelNames.MODEL_VECTOR_MEMORY_LIST, user, null, plist);

			List<BaseRecord> vects = vlist.get(FieldNames.FIELD_VECTORS);
			if (vects != null && !vects.isEmpty()) {
				IOSystem.getActiveContext().getWriter().write(vects.toArray(new BaseRecord[0]));
				logger.info("Created " + vects.size() + " vector chunks for memory");
			}
		} catch (Exception e) {
			logger.error("Error creating memory vectors: " + e.getMessage());
		}
	}

	public static List<BaseRecord> searchMemories(BaseRecord user, String query, int limit, double threshold) {
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available for memory search");
			return new ArrayList<>();
		}
		return vu.find(user, ModelNames.MODEL_VECTOR_MEMORY, query, limit, threshold, true);
	}

	public static List<BaseRecord> searchMemoriesByTag(BaseRecord user, BaseRecord[] tags, int limit) {
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			return new ArrayList<>();
		}
		List<BaseRecord> results = vu.findByTag(ModelNames.MODEL_VECTOR_MEMORY, tags);
		if (results.size() > limit) {
			return results.subList(0, limit);
		}
		return results;
	}

	public static List<BaseRecord> getConversationMemories(BaseRecord user, String conversationId) {
		List<BaseRecord> results = new ArrayList<>();
		try {
			BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, MEMORY_GROUP_PATH,
				GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID)
			);
			if (group == null) {
				return results;
			}
			Query q = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q.field("conversationId", conversationId);
			q.planMost(true);
			BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
			if (recs != null) {
				results.addAll(Arrays.asList(recs));
			}
		} catch (Exception e) {
			logger.error("Error querying conversation memories: " + e.getMessage());
		}
		return results;
	}

	public static String formatMemoriesAsContext(List<BaseRecord> memories) {
		if (memories == null || memories.isEmpty()) {
			return "";
		}

		McpContextBuilder ctxBuilder = new McpContextBuilder();
		for (BaseRecord memory : memories) {
			String content = memory.get("content");
			if (content == null) continue;

			String summary = memory.get("summary");
			String memoryType = "NOTE";
			if (memory.hasField("memoryType")) {
				Object mt = memory.get("memoryType");
				if (mt != null) memoryType = mt.toString();
			}

			String uri = "am7://memory/" + (memory.hasField(FieldNames.FIELD_OBJECT_ID)
				? memory.get(FieldNames.FIELD_OBJECT_ID) : "unknown");

			ctxBuilder.addResource(
				uri,
				"urn:am7:tool:memory",
				Map.of(
					"content", content,
					"summary", summary != null ? summary : "",
					"memoryType", memoryType,
					"importance", memory.hasField("importance") ? memory.get("importance") : 5
				),
				true
			);
		}
		return ctxBuilder.build();
	}

	public static List<BaseRecord> extractMemoriesFromResponse(BaseRecord user, String llmResponse,
			String sourceUri, String conversationId) {

		List<BaseRecord> memories = new ArrayList<>();
		try {
			// Parse JSON array from LLM response
			int jsonStart = llmResponse.indexOf("[");
			int jsonEnd = llmResponse.lastIndexOf("]");
			if (jsonStart < 0 || jsonEnd < 0 || jsonEnd <= jsonStart) {
				logger.warn("No valid JSON array in extraction response");
				return memories;
			}

			String jsonStr = llmResponse.substring(jsonStart, jsonEnd + 1);
			org.json.JSONArray arr = new org.json.JSONArray(jsonStr);

			for (int i = 0; i < arr.length(); i++) {
				org.json.JSONObject obj = arr.getJSONObject(i);
				String content = obj.optString("content", null);
				String summary = obj.optString("summary", null);
				String typeStr = obj.optString("memoryType", "NOTE");
				int importance = obj.optInt("importance", 5);

				if (content == null || content.trim().isEmpty()) continue;

				MemoryTypeEnumType type;
				try {
					type = MemoryTypeEnumType.fromValue(typeStr);
				} catch (IllegalArgumentException e) {
					type = MemoryTypeEnumType.NOTE;
				}

				BaseRecord mem = createMemory(user, content, summary, type, importance, sourceUri, conversationId);
				if (mem != null) {
					memories.add(mem);
				}
			}
		} catch (Exception e) {
			logger.error("Error parsing memory extraction response: " + e.getMessage());
		}
		return memories;
	}
}
