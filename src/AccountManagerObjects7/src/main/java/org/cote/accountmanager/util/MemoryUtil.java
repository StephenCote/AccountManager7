package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

public class MemoryUtil {
	public static final Logger logger = LogManager.getLogger(MemoryUtil.class);

	private static final String MEMORY_GROUP_PATH = "~/Memories";

	/// Returns canonical person pair IDs with the lower ID first.
	/// This ensures that the same pair always produces the same (id1, id2)
	/// regardless of which person is system vs user.
	public static long[] canonicalPersonIds(long id1, long id2) {
		if (id1 <= id2) {
			return new long[] { id1, id2 };
		}
		return new long[] { id2, id1 };
	}

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId) {
		return createMemory(user, content, summary, type, importance, sourceUri, conversationId, 0L, 0L);
	}

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId,
			long personId1, long personId2) {

		BaseRecord memory = null;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, MEMORY_GROUP_PATH);
			String baseName = summary != null ? summary.substring(0, Math.min(summary.length(), 48)) : "Memory";
			plist.parameter(FieldNames.FIELD_NAME, baseName + " " + UUID.randomUUID().toString().substring(0, 8));

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
			if (personId1 > 0L && personId2 > 0L) {
				long[] canon = canonicalPersonIds(personId1, personId2);
				memory.set("personId1", canon[0]);
				memory.set("personId2", canon[1]);
			}

			memory = IOSystem.getActiveContext().getAccessPoint().create(user, memory);
			if (memory == null) {
				logger.error("Failed to persist memory record");
				return null;
			}

			// Create vector embeddings for semantic search
			createMemoryVectors(user, memory, content, type, conversationId, personId1, personId2);

		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error("Error creating memory: " + e.getMessage());
		}
		return memory;
	}

	private static void createMemoryVectors(BaseRecord user, BaseRecord memory, String content,
			MemoryTypeEnumType type, String conversationId, long personId1, long personId2) {

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
			if (personId1 > 0L && personId2 > 0L) {
				long[] canon = canonicalPersonIds(personId1, personId2);
				plist.parameter("personId1", canon[0]);
				plist.parameter("personId2", canon[1]);
			}

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

	/// Search for memories belonging to a specific person pair.
	/// Person IDs are canonicalized so the order doesn't matter.
	public static List<BaseRecord> searchMemoriesByPersonPair(BaseRecord user, long pId1, long pId2, int limit) {
		List<BaseRecord> results = new ArrayList<>();
		try {
			long[] canon = canonicalPersonIds(pId1, pId2);
			BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, MEMORY_GROUP_PATH,
				GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID)
			);
			if (group == null) {
				return results;
			}
			Query q = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q.field("personId1", canon[0]);
			q.field("personId2", canon[1]);
			q.planMost(true);
			q.setRequestRange(0L, limit);
			BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
			if (recs != null) {
				results.addAll(Arrays.asList(recs));
			}
		} catch (Exception e) {
			logger.error("Error querying person pair memories: " + e.getMessage());
		}
		return results;
	}

	/// Search for all memories involving a specific person (as either id1 or id2).
	public static List<BaseRecord> searchMemoriesByPerson(BaseRecord user, long personId, int limit) {
		List<BaseRecord> results = new ArrayList<>();
		try {
			BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, MEMORY_GROUP_PATH,
				GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID)
			);
			if (group == null) {
				return results;
			}
			// Query memories where person appears as either id1 or id2
			Query q1 = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q1.field("personId1", personId);
			q1.planMost(true);
			q1.setRequestRange(0L, limit);
			BaseRecord[] recs1 = IOSystem.getActiveContext().getSearch().findRecords(q1);
			if (recs1 != null) {
				results.addAll(Arrays.asList(recs1));
			}

			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q2.field("personId2", personId);
			q2.planMost(true);
			q2.setRequestRange(0L, limit);
			BaseRecord[] recs2 = IOSystem.getActiveContext().getSearch().findRecords(q2);
			if (recs2 != null) {
				// Deduplicate — a memory where personId appears as both id1 and id2 would be returned by both queries
				for (BaseRecord r : recs2) {
					long rid = r.get(FieldNames.FIELD_ID);
					boolean dup = results.stream().anyMatch(existing -> (long) existing.get(FieldNames.FIELD_ID) == rid);
					if (!dup) {
						results.add(r);
					}
				}
			}

			// Sort by importance descending, limit
			results.sort((a, b) -> Integer.compare((int) b.get("importance"), (int) a.get("importance")));
			if (results.size() > limit) {
				results = new ArrayList<>(results.subList(0, limit));
			}
		} catch (Exception e) {
			logger.error("Error querying person memories: " + e.getMessage());
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
