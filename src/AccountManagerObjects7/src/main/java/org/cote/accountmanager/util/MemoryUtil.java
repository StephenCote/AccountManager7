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
import org.cote.accountmanager.record.RecordFactory;
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

	/// Returns two person records in canonical order (lower ID first).
	public static BaseRecord[] canonicalPersonOrder(BaseRecord p1, BaseRecord p2) {
		long id1 = p1.get(FieldNames.FIELD_ID);
		long id2 = p2.get(FieldNames.FIELD_ID);
		if (id1 <= id2) {
			return new BaseRecord[] { p1, p2 };
		}
		return new BaseRecord[] { p2, p1 };
	}

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId) {
		return createMemory(user, content, summary, type, importance, sourceUri, conversationId, (BaseRecord) null, (BaseRecord) null);
	}

	/// Backward-compatible overload accepting long person IDs.
	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId,
			long personId1, long personId2) {
		return createMemory(user, content, summary, type, importance, sourceUri, conversationId, personId1, personId2, null);
	}

	/// Backward-compatible overload accepting long person IDs with explicit model type.
	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId,
			long personId1, long personId2, String personModel) {
		if (personId1 > 0L && personId2 > 0L) {
			BaseRecord p1 = stubPersonRecord(personId1, personModel);
			BaseRecord p2 = stubPersonRecord(personId2, personModel);
			if (p1 != null && p2 != null) {
				return createMemory(user, content, summary, type, importance, sourceUri, conversationId, p1, p2);
			}
		}
		return createMemory(user, content, summary, type, importance, sourceUri, conversationId);
	}

	public static BaseRecord createMemory(BaseRecord user, String content, String summary,
			MemoryTypeEnumType type, int importance, String sourceUri, String conversationId,
			BaseRecord person1, BaseRecord person2) {

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

			/// Phase 1: Create the memory WITHOUT person fields to avoid policy
			/// evaluation on foreign refs (stubs without group/org will fail policy checks).
			memory = IOSystem.getActiveContext().getAccessPoint().create(user, memory);
			if (memory == null) {
				logger.error("Failed to persist memory record");
				return null;
			}

			/// Phase 2: Set person fields and persist via RecordUtil (bypasses policy).
			long pid1 = 0L, pid2 = 0L;
			if (person1 != null && person2 != null) {
				BaseRecord[] canon = canonicalPersonOrder(person1, person2);
				memory.set("person1Model", canon[0].getSchema());
				memory.set("person1", canon[0]);
				memory.set("person2Model", canon[1].getSchema());
				memory.set("person2", canon[1]);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(memory);

				long[] canonIds = canonicalPersonIds(
					person1.get(FieldNames.FIELD_ID),
					person2.get(FieldNames.FIELD_ID)
				);
				pid1 = canonIds[0];
				pid2 = canonIds[1];
			}

			// Create vector embeddings for semantic search
			createMemoryVectors(user, memory, content, type, conversationId, pid1, pid2);

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
				plist.parameter("personId1", personId1);
				plist.parameter("personId2", personId2);
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
	/// Person records are canonicalized so the order doesn't matter.
	public static List<BaseRecord> searchMemoriesByPersonPair(BaseRecord user, BaseRecord person1, BaseRecord person2, int limit) {
		List<BaseRecord> results = new ArrayList<>();
		try {
			BaseRecord[] canon = canonicalPersonOrder(person1, person2);
			BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, MEMORY_GROUP_PATH,
				GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID)
			);
			if (group == null) {
				return results;
			}
			Query q = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q.field("person1", canon[0]);
			q.field("person2", canon[1]);
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

	/// Search for all memories involving a specific person (as either person1 or person2).
	public static List<BaseRecord> searchMemoriesByPerson(BaseRecord user, BaseRecord person, int limit) {
		List<BaseRecord> results = new ArrayList<>();
		try {
			BaseRecord group = IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, MEMORY_GROUP_PATH,
				GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID)
			);
			if (group == null) {
				return results;
			}
			// Query memories where person appears as either person1 or person2
			Query q1 = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q1.field("person1", person);
			q1.planMost(true);
			q1.setRequestRange(0L, limit);
			BaseRecord[] recs1 = IOSystem.getActiveContext().getSearch().findRecords(q1);
			if (recs1 != null) {
				results.addAll(Arrays.asList(recs1));
			}

			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			q2.field("person2", person);
			q2.planMost(true);
			q2.setRequestRange(0L, limit);
			BaseRecord[] recs2 = IOSystem.getActiveContext().getSearch().findRecords(q2);
			if (recs2 != null) {
				// Deduplicate
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
	/// Returns memories where person matches AND vector similarity > threshold.
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
				long pid1 = getPersonId(mem, "person1");
				long pid2 = getPersonId(mem, "person2");
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

	/// Extract the database ID from a foreign model field on a memory record.
	private static long getPersonId(BaseRecord memory, String fieldName) {
		try {
			BaseRecord person = memory.get(fieldName);
			if (person != null) {
				return person.get(FieldNames.FIELD_ID);
			}
		} catch (Exception e) {
			// field might not be populated
		}
		return 0L;
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
		return extractMemoriesFromResponse(user, llmResponse, sourceUri, conversationId, null, null);
	}

	/// OI-3: Overload with person pair records so LLM-extracted memories
	/// are tagged with the character pair for cross-conversation retrieval.
	/// Phase 14d: Includes deduplication — checks for semantically similar existing
	/// memories and merges instead of creating duplicates.
	public static List<BaseRecord> extractMemoriesFromResponse(BaseRecord user, String llmResponse,
			String sourceUri, String conversationId, BaseRecord person1, BaseRecord person2) {

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
			if (person1 != null && person2 != null) {
				existingMemories = searchMemoriesByPersonPair(user, person1, person2, 50);
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
							importance, sourceUri, conversationId, person1, person2);
					}
					return memories;
				} catch (org.json.JSONException je) {
					logger.info("JSON parse failed, falling back to text parser: " + je.getMessage());
				}
			}

			/// Text fallback: parse lines produced by models that ignore the JSON format.
			/// The LLM may return numbered entries with bulleted sub-fields:
			///   1. Brief description
			///      - content: "detailed text"
			///      - summary: short summary
			///      - memoryType: RELATIONSHIP
			///      - importance: 7
			/// Or flat TYPE: content lines, or simple bullet lists.
			/// Strategy: strip bullet/number prefix first, then check metadata vs content.
			logger.info("Attempting text-based extraction from " + cleaned.length() + " chars");

			java.util.regex.Pattern typePattern = java.util.regex.Pattern.compile(
				"^(FACT|RELATIONSHIP|EMOTION|DECISION|DISCOVERY|NOTE|INSIGHT|OUTCOME|BEHAVIOR|ERROR_LESSON)\\s*[:—–-]\\s*(.+)",
				java.util.regex.Pattern.CASE_INSENSITIVE
			);
			java.util.regex.Pattern numberedPrefix = java.util.regex.Pattern.compile(
				"^(\\d+)[.)]\\s*(.*)"
			);
			java.util.regex.Pattern bulletPrefix = java.util.regex.Pattern.compile(
				"^[-*•]\\s*(.*)"
			);
			/// Metadata patterns: enrich the current entry
			java.util.regex.Pattern summaryPattern = java.util.regex.Pattern.compile(
				"^summary\\s*[:—–-]\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE
			);
			java.util.regex.Pattern memTypePattern = java.util.regex.Pattern.compile(
				"^(?:memoryType|memory_type|type)\\s*[:—–-]\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE
			);
			java.util.regex.Pattern importancePattern = java.util.regex.Pattern.compile(
				"^importance\\s*[:—–-]\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE
			);
			java.util.regex.Pattern contentPattern = java.util.regex.Pattern.compile(
				"^content\\s*[:—–-]\\s*(.+)", java.util.regex.Pattern.CASE_INSENSITIVE
			);
			java.util.regex.Pattern headerPattern = java.util.regex.Pattern.compile(
				"^(?:Memor(?:ies|y)|Key|Notes|Extracted|Fragments|Here are|The following|Below are).*[:.]$",
				java.util.regex.Pattern.CASE_INSENSITIVE
			);

			String[] lines = cleaned.split("\\r?\\n");
			String currentType = null;
			StringBuilder currentContent = null;
			String currentSummary = null;
			int currentImportance = 5;

			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) continue;
				if (headerPattern.matcher(trimmed).matches()) continue;

				/// Step 1: Strip bullet/number prefix to get inner text
				String inner = trimmed;
				boolean isNumbered = false;
				boolean hasBullet = false;
				java.util.regex.Matcher nm = numberedPrefix.matcher(trimmed);
				if (nm.matches()) {
					inner = nm.group(2).trim();
					isNumbered = true;
					hasBullet = true;
				} else {
					java.util.regex.Matcher bm = bulletPrefix.matcher(trimmed);
					if (bm.matches()) {
						inner = bm.group(1).trim();
						hasBullet = true;
					}
				}

				/// Step 2: Check metadata labels against inner text (bullet prefix stripped)
				java.util.regex.Matcher sm = summaryPattern.matcher(inner);
				if (sm.matches()) {
					if (currentContent != null) currentSummary = sm.group(1).trim();
					continue;
				}
				java.util.regex.Matcher mm = memTypePattern.matcher(inner);
				if (mm.matches()) {
					if (currentContent != null) currentType = mm.group(1).trim().toUpperCase();
					continue;
				}
				java.util.regex.Matcher im = importancePattern.matcher(inner);
				if (im.matches()) {
					if (currentContent != null) {
						try { currentImportance = Integer.parseInt(im.group(1).trim()); } catch (NumberFormatException e) { /* keep default */ }
					}
					continue;
				}
				java.util.regex.Matcher cm = contentPattern.matcher(inner);
				if (cm.matches()) {
					/// content: field replaces current content (it's the LLM's intended memory text,
					/// the numbered-item text was just a brief header)
					String val = cm.group(1).trim();
					if (val.startsWith("\"")) val = val.substring(1);
					if (val.endsWith("\"")) val = val.substring(0, val.length() - 1);
					if (currentContent != null) {
						currentContent = new StringBuilder(val);
					} else {
						currentType = "NOTE";
						currentContent = new StringBuilder(val);
					}
					continue;
				}

				/// Step 3: Check TYPE: prefix against inner text
				java.util.regex.Matcher mat = typePattern.matcher(inner);
				if (mat.matches()) {
					/// Flush previous entry
					if (currentType != null && currentContent != null && currentContent.length() > 0) {
						String content = currentContent.toString().trim();
						String summary = currentSummary != null ? currentSummary : (content.length() > 100 ? content.substring(0, 97) + "..." : content);
						addOrMergeMemory(user, memories, existingMemories, content, summary, currentType,
							currentImportance, sourceUri, conversationId, person1, person2);
					}
					currentType = mat.group(1).toUpperCase();
					currentContent = new StringBuilder(mat.group(2).trim());
					currentSummary = null;
					currentImportance = 5;
					continue;
				}

				/// Step 4: Numbered items start new entries
				if (isNumbered) {
					/// Flush previous entry
					if (currentType != null && currentContent != null && currentContent.length() > 0) {
						String content = currentContent.toString().trim();
						String summary = currentSummary != null ? currentSummary : (content.length() > 100 ? content.substring(0, 97) + "..." : content);
						addOrMergeMemory(user, memories, existingMemories, content, summary, currentType,
							currentImportance, sourceUri, conversationId, person1, person2);
					}
					currentType = "NOTE";
					currentContent = new StringBuilder(inner);
					currentSummary = null;
					currentImportance = 5;
					continue;
				}

				/// Step 5: Plain bullets with non-metadata content — continuation of current entry
				/// (sub-bullets under a numbered item are typically extra detail, not new entries)
				if (hasBullet && currentContent != null && inner.length() > 0) {
					currentContent.append(" ").append(inner);
					continue;
				}

				/// Step 6: Continuation or fallback
				if (currentContent != null) {
					currentContent.append(" ").append(trimmed);
				} else if (trimmed.length() > 10) {
					currentType = "NOTE";
					currentContent = new StringBuilder(trimmed);
					currentSummary = null;
					currentImportance = 5;
				}
			}
			/// Flush final entry
			if (currentType != null && currentContent != null && currentContent.length() > 0) {
				String content = currentContent.toString().trim();
				String summary = currentSummary != null ? currentSummary : (content.length() > 100 ? content.substring(0, 97) + "..." : content);
				addOrMergeMemory(user, memories, existingMemories, content, summary, currentType,
					currentImportance, sourceUri, conversationId, person1, person2);
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
			BaseRecord person1, BaseRecord person2) {
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
			person1, person2);
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

	/// Backward-compatible overload accepting long person IDs.
	public static List<BaseRecord> extractMemoriesFromResponse(BaseRecord user, String llmResponse,
			String sourceUri, String conversationId, long personId1, long personId2) {
		BaseRecord p1 = stubPersonRecord(personId1, null);
		BaseRecord p2 = stubPersonRecord(personId2, null);
		return extractMemoriesFromResponse(user, llmResponse, sourceUri, conversationId, p1, p2);
	}

	/// Backward-compatible overload accepting long person IDs.
	public static List<BaseRecord> searchMemoriesByPersonPair(BaseRecord user, long person1Id, long person2Id, int limit) {
		BaseRecord p1 = stubPersonRecord(person1Id, null);
		BaseRecord p2 = stubPersonRecord(person2Id, null);
		if (p1 != null && p2 != null) {
			return searchMemoriesByPersonPair(user, p1, p2, limit);
		}
		return new ArrayList<>();
	}

	/// Backward-compatible overload accepting a long person ID.
	public static List<BaseRecord> searchMemoriesByPerson(BaseRecord user, long personId, int limit) {
		BaseRecord p = stubPersonRecord(personId, null);
		if (p != null) {
			return searchMemoriesByPerson(user, p, limit);
		}
		return new ArrayList<>();
	}

	/// Create a minimal stub record with just an ID for use with foreign key queries.
	private static BaseRecord stubPersonRecord(long id, String personModel) {
		try {
			String model = (personModel != null && !personModel.isEmpty()) ? personModel : "olio.charPerson";
			BaseRecord rec = RecordFactory.newInstance(model);
			rec.set(FieldNames.FIELD_ID, id);
			return rec;
		} catch (Exception e) {
			logger.error("Error creating stub person record: " + e.getMessage());
			return null;
		}
	}
}
