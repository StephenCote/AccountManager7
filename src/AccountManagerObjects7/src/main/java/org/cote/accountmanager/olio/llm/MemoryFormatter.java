package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Phase 4 (ConversationQualityPlan) memory-context formatter.
///
/// Two formats are supported:
///   - asMcpBlock-style (handled by McpContextBuilder; not in this util)
///   - systemSection: a markdown block grouped by memory type with
///     human-readable headers and a closing "# How to respond" directive,
///     designed to land inside the LLM's system prompt where it can act
///     on the content directly rather than treating it as separate
///     metadata.
///
/// Pure function; no DB / no LLM. Caller converts BaseRecord to MemoryDraft.
public final class MemoryFormatter {

	private MemoryFormatter() { /* no instances */ }

	/// Minimal pure-data shape for a memory ready to be formatted.
	public static final class MemoryDraft {
		public final String memoryType;   // e.g. FACT, RELATIONSHIP, DECISION, INSIGHT, DISCOVERY, EMOTION, OUTCOME, NOTE
		public final String summary;      // preferred display text
		public final String content;      // fallback display text (used if summary is null/empty)
		public final int importance;      // 1-10

		public MemoryDraft(String memoryType, String summary, String content, int importance) {
			this.memoryType = memoryType == null ? "NOTE" : memoryType;
			this.summary = summary;
			this.content = content;
			this.importance = importance;
		}

		/// Preferred display text — summary if present, else content, else "".
		public String displayText() {
			if (summary != null && !summary.trim().isEmpty()) return summary;
			if (content != null && !content.trim().isEmpty()) return content;
			return "";
		}
	}

	/// Heading shown for each memoryType in the system section.
	/// Types not in this map are bucketed under "## Other".
	private static final Map<String, String> TYPE_HEADERS = new LinkedHashMap<>();
	static {
		TYPE_HEADERS.put("FACT",         "## Facts");
		TYPE_HEADERS.put("RELATIONSHIP", "## Relationships");
		TYPE_HEADERS.put("DECISION",     "## Recent decisions");
		TYPE_HEADERS.put("DISCOVERY",    "## Discoveries");
		TYPE_HEADERS.put("INSIGHT",      "## Unresolved");
		TYPE_HEADERS.put("EMOTION",      "## Emotional context");
		TYPE_HEADERS.put("OUTCOME",      "## Recent events");
		TYPE_HEADERS.put("EVENT",        "## Recent events");
		TYPE_HEADERS.put("NOTE",         "## Notes");
	}

	private static final String DEFAULT_HEADER = "## Other";

	/// Closing directive that lands after the grouped memory list. Kept
	/// brief and flavor-neutral; the goal is to nudge the LLM to ACT on
	/// the memories rather than just acknowledging them.
	public static final String DEFAULT_GUIDANCE =
		"# How to respond\n"
		+ "Use what you know when it fits. Build on it — don't restate it. "
		+ "Avoid repeating your previous responses.";

	/// Group `drafts` by memoryType (preserving the type-header order),
	/// render as markdown with section headers, and append the closing
	/// guidance block.
	///
	/// Empty / null input → empty string (callers can then skip emitting
	/// the whole section).
	/// Drafts whose displayText() is empty are silently skipped.
	public static String asSystemSection(List<MemoryDraft> drafts) {
		return asSystemSection(drafts, DEFAULT_GUIDANCE);
	}

	public static String asSystemSection(List<MemoryDraft> drafts, String guidance) {
		if (drafts == null || drafts.isEmpty()) return "";

		/// Bucket in declared header order; preserve input order within each bucket.
		Map<String, List<String>> buckets = new LinkedHashMap<>();
		for (String header : TYPE_HEADERS.values()) {
			buckets.put(header, new ArrayList<>());
		}
		buckets.put(DEFAULT_HEADER, new ArrayList<>());

		for (MemoryDraft d : drafts) {
			if (d == null) continue;
			String text = d.displayText();
			if (text.isEmpty()) continue;
			String header = TYPE_HEADERS.getOrDefault(d.memoryType, DEFAULT_HEADER);
			buckets.get(header).add(text.trim());
		}

		boolean any = false;
		for (List<String> items : buckets.values()) {
			if (!items.isEmpty()) { any = true; break; }
		}
		if (!any) return "";

		StringBuilder sb = new StringBuilder();
		sb.append("# What you know\n");
		for (Map.Entry<String, List<String>> e : buckets.entrySet()) {
			List<String> items = e.getValue();
			if (items.isEmpty()) continue;
			sb.append('\n').append(e.getKey()).append('\n');
			for (String item : items) {
				sb.append("- ").append(item).append('\n');
			}
		}
		if (guidance != null && !guidance.isEmpty()) {
			sb.append('\n').append(guidance).append('\n');
		}
		return sb.toString();
	}
}
