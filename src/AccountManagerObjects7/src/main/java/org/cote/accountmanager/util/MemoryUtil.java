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
		return createMemory(user, content, summary, type, importance, sourceUri, conversationId, 0L, 0L, null);
	}

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId,
			long personId1, long personId2) {
		return createMemory(user, content, summary, type, importance, sourceUri, conversationId, personId1, personId2, null);
	}

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId,
			long personId1, long personId2, String personModel) {

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
			if (personModel != null && !personModel.isEmpty()) {
				memory.set("personModel", personModel);
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

	/// Phase 3 (chatRefactor2): Search memories by person AND semantic query.
	/// Combines person-filtered retrieval with vector similarity matching.
	/// Returns memories where personId matches AND vector similarity > threshold.
	public static List<BaseRecord> searchMemoriesByPersonAndQuery(BaseRecord user, String queryText, long personId, int limit, double threshold) {
		List<BaseRecord> results = new ArrayList<>();
		if (queryText == null || queryText.isEmpty()) {
			return results;
		}
		try {
			/// First get vector-similar memories
			List<BaseRecord> vectorResults = searchMemories(user, queryText, limit * 3, threshold);
			if (vectorResults.isEmpty()) {
				return results;
			}

			/// Filter to only those involving the specified person
			for (BaseRecord mem : vectorResults) {
				long pid1 = mem.get("personId1");
				long pid2 = mem.get("personId2");
				if (pid1 == personId || pid2 == personId) {
					results.add(mem);
					if (results.size() >= limit) break;
				}
			}
		} catch (Exception e) {
			logger.error("Error in searchMemoriesByPersonAndQuery: " + e.getMessage());
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
		return extractMemoriesFromResponse(user, llmResponse, sourceUri, conversationId, 0L, 0L, null);
	}

	/// OI-3: Overload with person pair IDs and person model so LLM-extracted memories
	/// are tagged with the character pair for cross-conversation retrieval.
	/// Phase 14d: Includes deduplication — checks for semantically similar existing
	/// memories and merges instead of creating duplicates.
	public static List<BaseRecord> extractMemoriesFromResponse(BaseRecord user, String llmResponse,
			String sourceUri, String conversationId, long personId1, long personId2, String personModel) {

		List<BaseRecord> memories = new ArrayList<>();
		try {
			// Strip markdown code fences if the LLM wrapped the JSON in them
			String cleaned = llmResponse.trim();
			if (cleaned.startsWith("```")) {
				int firstNewline = cleaned.indexOf('\n');
				if (firstNewline > 0) cleaned = cleaned.substring(firstNewline + 1);
				if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
				cleaned = cleaned.trim();
			}

			/// Phase 14d: Pre-fetch existing memories for the person pair to enable dedup
			List<BaseRecord> existingMemories = null;
			if (personId1 > 0L && personId2 > 0L) {
				existingMemories = searchMemoriesByPersonPair(user, personId1, personId2, 50);
			}

			// Try JSON array parsing first
			int jsonStart = cleaned.indexOf("[");
			int jsonEnd = cleaned.lastIndexOf("]");
			if (jsonStart >= 0 && jsonEnd > jsonStart) {
				try {
					String jsonStr = cleaned.substring(jsonStart, jsonEnd + 1);
					org.json.JSONArray arr = new org.json.JSONArray(jsonStr);
					for (int i = 0; i < arr.length(); i++) {
						org.json.JSONObject obj = arr.getJSONObject(i);
						String content = obj.optString("content", null);
						String summary = obj.optString("summary", null);
						String typeStr = obj.optString("memoryType", "NOTE");
						int importance = obj.optInt("importance", 5);
						if (content == null || content.trim().isEmpty()) continue;
						addOrMergeMemory(user, memories, existingMemories, content, summary, typeStr,
							importance, sourceUri, conversationId, personId1, personId2, personModel);
					}
					return memories;
				} catch (org.json.JSONException je) {
					logger.info("JSON parse failed, falling back to text parser: " + je.getMessage());
				}
			}

			/// Text fallback: parse "TYPE: content" lines produced by models that ignore
			/// the JSON format instruction. Collects continuation lines (starting with -)
			/// into the content of the preceding memory entry.
			logger.info("Attempting text-based extraction from " + cleaned.length() + " chars");
			java.util.regex.Pattern typePattern = java.util.regex.Pattern.compile(
				"^(FACT|RELATIONSHIP|EMOTION|DECISION|DISCOVERY|NOTE|INSIGHT|OUTCOME|BEHAVIOR|ERROR_LESSON)\\s*[:—–-]\\s*(.+)",
				java.util.regex.Pattern.CASE_INSENSITIVE
			);
			String[] lines = cleaned.split("\\r?\\n");
			String currentType = null;
			StringBuilder currentContent = null;
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) continue;
				java.util.regex.Matcher mat = typePattern.matcher(trimmed);
				if (mat.matches()) {
					/// Flush previous entry
					if (currentType != null && currentContent != null && currentContent.length() > 0) {
						String content = currentContent.toString().trim();
						String summary = content.length() > 100 ? content.substring(0, 97) + "..." : content;
						addOrMergeMemory(user, memories, existingMemories, content, summary, currentType,
							5, sourceUri, conversationId, personId1, personId2, personModel);
					}
					currentType = mat.group(1).toUpperCase();
					currentContent = new StringBuilder(mat.group(2).trim());
				} else if (currentContent != null && (trimmed.startsWith("-") || trimmed.startsWith("*"))) {
					/// Continuation line — append to current entry
					currentContent.append(" ").append(trimmed.substring(1).trim());
				}
			}
			/// Flush final entry
			if (currentType != null && currentContent != null && currentContent.length() > 0) {
				String content = currentContent.toString().trim();
				String summary = content.length() > 100 ? content.substring(0, 97) + "..." : content;
				addOrMergeMemory(user, memories, existingMemories, content, summary, currentType,
					5, sourceUri, conversationId, personId1, personId2, personModel);
			}
			if (!memories.isEmpty()) {
				logger.info("Text fallback extracted " + memories.size() + " memories");
			} else {
				logger.warn("No memories extracted from response: " + cleaned.substring(0, Math.min(200, cleaned.length())));
			}
		} catch (Exception e) {
			logger.error("Error parsing memory extraction response: " + e.getMessage());
		}
		return memories;
	}

	/// Shared helper for extractMemoriesFromResponse: dedup-check, then create or merge.
	private static void addOrMergeMemory(BaseRecord user, List<BaseRecord> memories,
			List<BaseRecord> existingMemories, String content, String summary, String typeStr,
			int importance, String sourceUri, String conversationId,
			long personId1, long personId2, String personModel) {
		MemoryTypeEnumType type;
		try {
			type = MemoryTypeEnumType.fromValue(typeStr);
		} catch (IllegalArgumentException e) {
			type = MemoryTypeEnumType.NOTE;
		}

		BaseRecord duplicate = findSemanticDuplicate(user, content, existingMemories);
		if (duplicate != null) {
			BaseRecord merged = mergeMemory(user, duplicate, content, summary, type, importance, sourceUri);
			if (merged != null) {
				memories.add(merged);
				logger.info("Merged duplicate memory (id=" + duplicate.get(FieldNames.FIELD_ID) + ")");
			}
			return;
		}

		BaseRecord mem = createMemory(user, content, summary, type, importance, sourceUri, conversationId,
			personId1, personId2, personModel);
		if (mem != null) {
			memories.add(mem);
			if (existingMemories != null) {
				existingMemories.add(mem);
			}
		}
	}

	/// Phase 14d: Find a semantically duplicate memory from the existing list.
	/// Uses vector similarity search if available, falls back to text-based Jaccard similarity.
	/// Threshold: 0.92 cosine similarity (vector) or 0.85 Jaccard (text fallback).
	public static BaseRecord findSemanticDuplicate(BaseRecord user, String newContent, List<BaseRecord> existingMemories) {
		if (existingMemories == null || existingMemories.isEmpty() || newContent == null) {
			return null;
		}

		/// Try vector-based similarity first
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu != null && VectorUtil.isVectorSupported()) {
			try {
				List<BaseRecord> similar = vu.find(user, "tool.vectorMemory", newContent, 1, 0.92, true);
				if (similar != null && !similar.isEmpty()) {
					/// Verify the match is in our existing memory set (same person pair)
					BaseRecord match = similar.get(0);
					BaseRecord vectorRef = match.hasField(FieldNames.FIELD_VECTOR_REFERENCE) ? match.get(FieldNames.FIELD_VECTOR_REFERENCE) : null;
					if (vectorRef != null) {
						long refId = vectorRef.get(FieldNames.FIELD_ID);
						for (BaseRecord existing : existingMemories) {
							if ((long) existing.get(FieldNames.FIELD_ID) == refId) {
								return existing;
							}
						}
					}
				}
			} catch (Exception e) {
				logger.debug("Vector dedup check failed, falling back to text: " + e.getMessage());
			}
		}

		/// Fallback: text-based Jaccard similarity on word sets
		java.util.Set<String> newWords = tokenize(newContent);
		for (BaseRecord existing : existingMemories) {
			String existingContent = existing.get("content");
			if (existingContent == null) continue;
			java.util.Set<String> existingWords = tokenize(existingContent);
			double jaccard = jaccardSimilarity(newWords, existingWords);
			if (jaccard >= 0.85) {
				return existing;
			}
		}

		return null;
	}

	/// Phase 14d: Merge a new memory into an existing duplicate.
	/// Keeps the higher importance value and appends the new sourceUri.
	private static BaseRecord mergeMemory(BaseRecord user, BaseRecord existing, String newContent,
			String newSummary, MemoryTypeEnumType newType, int newImportance, String newSourceUri) {
		try {
			int existingImportance = existing.get("importance");
			if (newImportance > existingImportance) {
				existing.set("importance", newImportance);
			}

			/// Combine sourceUris (append new if different)
			String existingUri = existing.get("sourceUri");
			if (newSourceUri != null && (existingUri == null || !existingUri.contains(newSourceUri))) {
				String combined = (existingUri != null ? existingUri + "; " : "") + newSourceUri;
				/// Cap at 512 chars to avoid field overflow
				if (combined.length() > 512) {
					combined = combined.substring(0, 512);
				}
				existing.set("sourceUri", combined);
			}

			/// If the new summary is longer/more specific, use it
			String existingSummary = existing.get("summary");
			if (newSummary != null && (existingSummary == null || newSummary.length() > existingSummary.length())) {
				existing.set("summary", newSummary);
			}

			IOSystem.getActiveContext().getAccessPoint().update(user, existing);
			return existing;
		} catch (Exception e) {
			logger.error("Error merging memory: " + e.getMessage());
			return null;
		}
	}

	/// Tokenize text into a set of lowercased words for Jaccard similarity.
	public static java.util.Set<String> tokenize(String text) {
		java.util.Set<String> words = new java.util.HashSet<>();
		if (text == null) return words;
		for (String w : text.toLowerCase().split("\\W+")) {
			if (w.length() > 2) {
				words.add(w);
			}
		}
		return words;
	}

	/// Jaccard similarity between two word sets: |intersection| / |union|.
	public static double jaccardSimilarity(java.util.Set<String> a, java.util.Set<String> b) {
		if (a.isEmpty() && b.isEmpty()) return 1.0;
		if (a.isEmpty() || b.isEmpty()) return 0.0;
		java.util.Set<String> intersection = new java.util.HashSet<>(a);
		intersection.retainAll(b);
		java.util.Set<String> union = new java.util.HashSet<>(a);
		union.addAll(b);
		return (double) intersection.size() / union.size();
	}
}
