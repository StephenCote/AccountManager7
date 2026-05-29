package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Phase 3 (ConversationQualityPlan) pure parser for multi-aspect
/// memory-extraction LLM output. Decoupled from Chat so it can be unit
/// tested without a DB or LLM. Chat.parseMultiAspectAndCreateMemories
/// uses this to get the parsed drafts, then calls MemoryUtil.createMemory
/// for each.
///
/// Input format (from memoryExtractionMultiAspect.json):
///   {"new_fact": {...}|null,
///    "decision": {...}|null,
///    "tension": {...}|null,
///    "relationship_change": {...}|null}
///
/// Each non-null aspect carries {"content": "...", "importance": 1-10}.
/// The parser tolerates code fences, missing fields, and bad numbers.
public final class MultiAspectMemoryParser {

	/// Maps JSON key to MemoryTypeEnumType name.
	public static final String[][] ASPECT_TO_TYPE = new String[][]{
		{ "new_fact",            "FACT"         },
		{ "decision",            "DECISION"     },
		{ "tension",             "INSIGHT"      },
		{ "relationship_change", "RELATIONSHIP" }
	};

	public static final int DEFAULT_IMPORTANCE = 5;

	private MultiAspectMemoryParser() { /* no instances */ }

	/// One parsed aspect ready for memory creation.
	public static final class AspectDraft {
		public final String aspectKey;     // e.g. "tension"
		public final String memoryTypeName; // e.g. "INSIGHT"
		public final String content;
		public final int importance;
		public AspectDraft(String aspectKey, String memoryTypeName, String content, int importance) {
			this.aspectKey = aspectKey;
			this.memoryTypeName = memoryTypeName;
			this.content = content;
			this.importance = importance;
		}
	}

	/// Strip markdown code fences from the start/end of a response body
	/// if present. Tolerates ```json, ```, or no fence.
	public static String stripCodeFences(String body) {
		if (body == null) return "";
		String s = body.trim();
		if (!s.startsWith("```")) return s;
		int firstNl = s.indexOf('\n');
		if (firstNl < 0) return s;
		s = s.substring(firstNl + 1);
		int fenceEnd = s.lastIndexOf("```");
		if (fenceEnd > 0) s = s.substring(0, fenceEnd);
		return s.trim();
	}

	/// Parse a multi-aspect response body into a list of typed drafts.
	/// Returns an empty list on:
	///   - null / empty / whitespace input
	///   - JSON parse failure
	///   - non-object root
	/// Aspects that are null, missing, malformed, or content-empty are
	/// skipped individually (other valid aspects still get returned).
	public static List<AspectDraft> parse(String responseBody) {
		List<AspectDraft> out = new ArrayList<>();
		if (responseBody == null) return out;
		String cleaned = stripCodeFences(responseBody);
		if (cleaned.isEmpty()) return out;

		JsonNode root;
		try {
			root = new ObjectMapper().readTree(cleaned);
		} catch (Exception e) {
			return out;
		}
		if (root == null || !root.isObject()) return out;

		for (String[] pair : ASPECT_TO_TYPE) {
			String key = pair[0];
			String typeName = pair[1];
			JsonNode aspect = root.get(key);
			if (aspect == null || aspect.isNull() || !aspect.isObject()) continue;

			JsonNode contentNode = aspect.get("content");
			if (contentNode == null || !contentNode.isTextual()) continue;
			String content = contentNode.asText();
			if (content == null || content.trim().isEmpty()) continue;

			int importance = DEFAULT_IMPORTANCE;
			JsonNode impNode = aspect.get("importance");
			if (impNode != null && impNode.isNumber()) {
				importance = Math.max(1, Math.min(10, impNode.asInt()));
			}

			out.add(new AspectDraft(key, typeName, content, importance));
		}
		return out;
	}
}
