package org.cote.accountmanager.util;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.IChatHandler;
import org.cote.accountmanager.olio.llm.IChatListener;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PageIndexNodeEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.validator.HierarchyValidator;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PageIndex build + retrieval utility, the companion to {@link VectorUtil}. Builds a hierarchical
 * table-of-contents tree (ROOT -&gt; SECTION -&gt; CHUNK) over a source document's extracted content and
 * provides an embedding-cosine tree-walk retrieval API.
 *
 * <p>The structural splitter ports the deterministic markdown/header builder from the PageIndex reference
 * (<code>pageindex/page_index_md.py</code>): ATX headers (<code>^#{1,6}\\s+</code>) delimit sections while
 * skipping fenced code blocks; sections nest by header level via a stack; leaf CHUNK text is produced with
 * {@link VectorUtil#chunkBySentence(String, int)}. Non-markdown content falls back to blank-line paragraph
 * grouping then sentence grouping. Interior-node summaries are generated through the connection-configured
 * {@link Chat} / {@code LLMConnectionManager} client (provider-agnostic — OpenAI or local per the resolved
 * connection), with a deterministic truncated-excerpt fallback; the PDF TOC pipeline remains a future
 * pluggable Python seam (see design doc §8).
 *
 * <p>Unlike flat vectors, tree persistence requires generated parent ids before children can be linked, so
 * {@link #createPageIndex(BaseRecord, BaseRecord)} performs the level-order batch persist itself (via
 * <code>getWriter().write(BaseRecord[])</code>) and returns the persisted nodes; {@code AccessPoint.pageIndex}
 * wraps this in the PBAC/audit flow, mirroring {@code vectorize()}.
 */
public class PageIndexUtil {
	public static final Logger logger = LogManager.getLogger(PageIndexUtil.class);

	/// Number of sentences grouped into a single leaf CHUNK (mirrors VectorUtil sentence chunking).
	public static final int DEFAULT_LEAF_CHUNK_SIZE = 5;

	/// Below this character count an interior node stores its aggregated text directly as the summary
	/// (mirrors the reference's summary_token_threshold behavior); above it the Chat client summarizer runs.
	public static final int SUMMARY_CHAR_THRESHOLD = 800;

	/// LLM-TOC group sizing (prose path). When extracted prose has no markdown headers the text is split
	/// into ordered char-length groups (with a small overlap so a section straddling a boundary is still
	/// seen whole by one group) and each group is sent to the Chat client for TOC extraction. Char-based
	/// analogue of the reference's ~20k-token page groups (page_index.py page_list_to_group_text). For v1 a
	/// doc that fits one group is a single call; multi-group processing is option (a) below.
	public static final int PAGEINDEX_TOC_GROUP_CHARS = 16000;
	/// Overlap (chars) carried from the end of one TOC group into the start of the next.
	public static final int PAGEINDEX_TOC_GROUP_OVERLAP = 500;

	private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
	private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^```");

	private PageIndexUtil() {}

	/// In-memory tree node: the persistable record plus its ordered children and raw local text.
	private static class PINode {
		private BaseRecord record;
		private final List<PINode> children = new ArrayList<>();
		private String text;
		private int level;
		private PageIndexNodeEnumType nodeType;
	}

	private static EmbeddingUtil getEmbedUtil() {
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		return (vu != null ? vu.getEmbedUtil() : null);
	}

	/* ---------------------------------------------------------------------- build ---------------------------------------------------------------------- */

	/// Serializes createPageIndex per SOURCE record (schema + id), keyed lock registry. Root cause of a
	/// live "duplicate key value violates unique constraint ..._ne_gd_od_2_idx" crash reproduced against
	/// catatone.docx (never from this investigation's own JUnit runs - those talk to the DB directly,
	/// never through Tomcat): two concurrent createPageIndex calls for the SAME source each start their
	/// own AtomicInteger seq at 0 (see buildTree), so BOTH generate identically-named nodes
	/// ("pin-<sourceId>-0", "pin-<sourceId>-1", ...); whichever's batch INSERT lands second collides with
	/// the first's already-committed row on the (name, groupId, organizationId) unique constraint. The
	/// count-check/delete/rebuild sequence has no other serialization point (DBWriter's write/delete
	/// don't take any application-level lock), so two overlapping rebuild requests for the same source
	/// (a double-submitted UI click, a retried REST call, two independent processes targeting the same
	/// document) can race straight through it. A per-source lock makes the second caller wait for the
	/// first to fully finish (see/replace its committed state) rather than interleave with it; distinct
	/// sources still build fully concurrently (the lock is keyed, not global).
	private static final java.util.concurrent.ConcurrentHashMap<String, Object> BUILD_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

	private static Object buildLockFor(BaseRecord model) {
		String key = model.getSchema() + ":" + (model.hasField(FieldNames.FIELD_ID) ? (Object) model.get(FieldNames.FIELD_ID) : model.get(FieldNames.FIELD_OBJECT_ID));
		return BUILD_LOCKS.computeIfAbsent(key, k -> new Object());
	}

	/**
	 * Build and persist a PageIndex tree for the given source model. Replaces any existing index. Returns the
	 * full list of persisted nodes (root first), each carrying its generated id.
	 */
	public static List<BaseRecord> createPageIndex(BaseRecord user, BaseRecord model) throws FieldException {
		if(model == null) {
			throw new FieldException("Model is null");
		}
		if(!RecordUtil.isIdentityRecord(model)) {
			throw new FieldException("Model is missing identity");
		}

		/// See buildLockFor's javadoc: serializes concurrent rebuilds of the SAME source so their
		/// count-check/delete/insert sequences can't interleave and collide.
		synchronized(buildLockFor(model)) {
			if(countPageIndex(model) > 0) {
				logger.info("Replacing page index for " + model.getSchema());
				if(deletePageIndex(model) == 0) {
					throw new FieldException("Page index already exists and was not deleted.");
				}
			}

			String content = extractContent(model);
			if(content == null || content.length() == 0) {
				throw new FieldException("Content is null or empty");
			}

			/// Resolve a connection-configured chatConfig once (same library "contentAnalysis" config ChatUtil
			/// uses for summarization); the Chat client resolves the connection (OpenAI or local) from it. Resolved
			/// BEFORE the split because the prose (no-markdown) split strategy also drives the Chat client to build
			/// an LLM table of contents; if it can't be resolved that strategy is simply unavailable and the split
			/// degrades to the flat fallback.
			BaseRecord chatConfig = resolveChatConfig(user);

			PINode root = buildTree(model, content, user, chatConfig);

			/// Bottom-up summarization of interior nodes (leaf -> section -> root) via the Chat client.
			summarize(root, user, chatConfig);

			/// Optionally embed each node's title+summary/content for the cosine shortlist.
			EmbeddingUtil eu = getEmbedUtil();
			if(eu != null) {
				embed(root, eu);
			}

			List<BaseRecord> persisted = persist(root);

			/// Optional per-document container/root descriptor (data.pageIndex).
			try {
				writeContainer(model, root.record, persisted.size(), eu);
			}
			catch(Exception e) {
				logger.warn("Failed to write page index container: " + e.getMessage());
			}

			return persisted;
		}
	}

	/// Extract the content to index. If the model opts in with a (possibly custom-subclassed) pageIndex
	/// provider, use its describe(); otherwise fall back to the shared DocumentUtil extraction dispatcher.
	private static String extractContent(BaseRecord model) {
		ModelSchema ms = RecordFactory.getSchema(model.getSchema());
		if(ms != null && ms.getPageIndex() != null) {
			IProvider prov = ProviderUtil.getProviderInstance(ms.getPageIndex());
			if(prov != null) {
				return prov.describe(ms, model);
			}
			logger.error("Page index provider could not be instantiated: " + ms.getPageIndex());
		}
		return DocumentUtil.getStringContent(model);
	}

	/**
	 * Structural split strategy. Chooses, in order:
	 * <ol>
	 *   <li><b>Markdown headers present</b> — the deterministic, free/fast ATX-header split (unchanged).</li>
	 *   <li><b>No markdown headers (prose)</b> — the LLM-TOC builder ({@link #buildLlmTocTree}) drives the
	 *       Chat client to produce a hierarchical table of contents and nests real SECTION nodes.</li>
	 *   <li><b>LLM-TOC unavailable / fails</b> (no chatConfig, chat error, unparseable output, no locatable
	 *       markers) — the flat ROOT + sentence-chunked-leaves fallback. A build NEVER hard-fails on LLM issues.</li>
	 * </ol>
	 */
	private static PINode buildTree(BaseRecord model, String content, BaseRecord user, BaseRecord chatConfig) throws FieldException {
		long orgId = (model.hasField(FieldNames.FIELD_ORGANIZATION_ID) ? (long)model.get(FieldNames.FIELD_ORGANIZATION_ID) : 0L);
		long groupId = (model.hasField(FieldNames.FIELD_GROUP_ID) ? (long)model.get(FieldNames.FIELD_GROUP_ID) : 0L);
		long ownerId = (model.hasField(FieldNames.FIELD_OWNER_ID) ? (long)model.get(FieldNames.FIELD_OWNER_ID) : 0L);
		String rootTitle = (model.hasField(FieldNames.FIELD_NAME) && model.get(FieldNames.FIELD_NAME) != null) ? model.get(FieldNames.FIELD_NAME) : model.getSchema();

		/// Per-document sequence guaranteeing a unique node name; data.pageIndexNode inherits the
		/// data.directory "name, groupId, organizationId" unique constraint (constraints are merged up the
		/// inheritance chain), so names must be unique within the source record's group.
		AtomicInteger seq = new AtomicInteger(0);

		PINode root = new PINode();
		root.nodeType = PageIndexNodeEnumType.ROOT;
		root.level = 0;
		root.text = null;
		root.record = newNode(model, orgId, groupId, ownerId, PageIndexNodeEnumType.ROOT, rootTitle, null, 0, content.length(), 0, 0, seq);

		List<int[]> headers = extractHeaders(content); // {charStart, level}
		List<String> headerTitles = extractHeaderTitles(content); // parallel titles

		if(headers.isEmpty()) {
			/// No markdown structure (prose). Strategy 2: try the LLM-TOC builder to synthesize a real
			/// section hierarchy. Strategy 3: if that is unavailable or fails, degrade to the flat
			/// ROOT + sentence-chunked leaves. A build must NEVER hard-fail on LLM issues.
			if(buildLlmTocTree(root, model, orgId, groupId, ownerId, content, user, chatConfig, seq)) {
				return root;
			}
			logger.info("LLM-TOC unavailable/failed for " + model.getSchema() + "; using flat ROOT->CHUNK fallback");
			int[] ord = new int[] {0};
			addLeafChunks(root, model, orgId, groupId, ownerId, content, 0, ord, seq);
			return root;
		}

		/// Preamble (content before the first header) becomes leaf chunks on the root.
		int[] ord = new int[] {0};
		if(headers.get(0)[0] > 0) {
			String preamble = content.substring(0, headers.get(0)[0]);
			if(preamble.trim().length() > 0) {
				addLeafChunks(root, model, orgId, groupId, ownerId, preamble, 0, ord, seq);
			}
		}

		/// Build the section tree via a level stack (ports build_tree_from_nodes).
		List<PINode> stack = new ArrayList<>();
		stack.add(root);
		for(int i = 0; i < headers.size(); i++) {
			int start = headers.get(i)[0];
			int level = headers.get(i)[1];
			int end = (i + 1 < headers.size() ? headers.get(i + 1)[0] : content.length());
			String localText = content.substring(start, end);
			String title = headerTitles.get(i);

			PINode section = new PINode();
			section.nodeType = PageIndexNodeEnumType.SECTION;
			section.level = level;
			section.text = localText;

			/// Pop the stack to the parent whose level is shallower than this header.
			while(stack.size() > 1 && stack.get(stack.size() - 1).level >= level) {
				stack.remove(stack.size() - 1);
			}
			PINode parent = stack.get(stack.size() - 1);
			int ordinal = parent.children.size();
			section.record = newNode(model, orgId, groupId, ownerId, PageIndexNodeEnumType.SECTION, title, null, start, end, level, ordinal, seq);
			parent.children.add(section);
			stack.add(section);

			/// The section's own local body (header line + text up to the next header) becomes leaf chunks.
			int bodyStart = start;
			int nl = localText.indexOf('\n');
			String body = (nl >= 0 ? localText.substring(nl + 1) : "");
			if(nl >= 0) {
				bodyStart = start + nl + 1;
			}
			if(body.trim().length() > 0) {
				int[] leafOrd = new int[] {0};
				addLeafChunks(section, model, orgId, groupId, ownerId, body, bodyStart, leafOrd, seq);
			}
		}

		return root;
	}

	/// Split a block of text into leaf CHUNK nodes (sentence grouping; blank-line paragraph pre-split for
	/// non-markdown robustness) and attach them to the parent, tracking char offsets and ordinal.
	private static void addLeafChunks(PINode parent, BaseRecord model, long orgId, long groupId, long ownerId, String block, int baseOffset, int[] ordinal, AtomicInteger seq) throws FieldException {
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		List<String> chunks;
		if(vu != null) {
			chunks = vu.chunkBySentence(block, DEFAULT_LEAF_CHUNK_SIZE);
		}
		else {
			chunks = fallbackChunk(block);
		}
		int searchFrom = 0;
		for(String chunk : chunks) {
			if(chunk == null || chunk.trim().length() == 0) {
				continue;
			}
			int rel = block.indexOf(chunk, searchFrom);
			int startOffset;
			int endOffset;
			if(rel >= 0) {
				startOffset = baseOffset + rel;
				endOffset = startOffset + chunk.length();
				searchFrom = rel + chunk.length();
			}
			else {
				startOffset = baseOffset + searchFrom;
				endOffset = startOffset + chunk.length();
			}
			PINode leaf = new PINode();
			leaf.nodeType = PageIndexNodeEnumType.CHUNK;
			leaf.level = parent.level + 1;
			leaf.text = chunk;
			int ord = ordinal[0]++;
			leaf.record = newNode(model, orgId, groupId, ownerId, PageIndexNodeEnumType.CHUNK, null, chunk, startOffset, endOffset, leaf.level, ord, seq);
			parent.children.add(leaf);
		}
	}

	/// Deterministic fallback when the light-service/VectorUtil is unavailable: blank-line paragraphs.
	private static List<String> fallbackChunk(String block) {
		List<String> out = new ArrayList<>();
		for(String p : block.split("\\r?\\n\\s*\\r?\\n")) {
			if(p.trim().length() > 0) {
				out.add(p.trim());
			}
		}
		if(out.isEmpty() && block.trim().length() > 0) {
			out.add(block.trim());
		}
		return out;
	}

	/// Extract {charStart, level} for each ATX header, skipping fenced code blocks (ports extract_nodes_from_markdown).
	private static List<int[]> extractHeaders(String content) {
		List<int[]> headers = new ArrayList<>();
		boolean inCode = false;
		int offset = 0;
		String[] lines = content.split("\n", -1);
		for(String line : lines) {
			String stripped = line.trim();
			if(CODE_FENCE_PATTERN.matcher(stripped).find()) {
				inCode = !inCode;
			}
			else if(!inCode) {
				Matcher m = HEADER_PATTERN.matcher(stripped);
				if(m.matches()) {
					headers.add(new int[] {offset, m.group(1).length()});
				}
			}
			offset += line.length() + 1; // + newline
		}
		return headers;
	}

	private static List<String> extractHeaderTitles(String content) {
		List<String> titles = new ArrayList<>();
		boolean inCode = false;
		String[] lines = content.split("\n", -1);
		for(String line : lines) {
			String stripped = line.trim();
			if(CODE_FENCE_PATTERN.matcher(stripped).find()) {
				inCode = !inCode;
			}
			else if(!inCode) {
				Matcher m = HEADER_PATTERN.matcher(stripped);
				if(m.matches()) {
					titles.add(m.group(2).trim());
				}
			}
		}
		return titles;
	}

	/* --------------------------------------------------------------- LLM table-of-contents (prose split) --------------------------------------------------------------- */

	/// A parsed LLM-TOC entry: an invented section title, a nesting level (1 = top), and a VERBATIM
	/// startMarker copied from the source text used to locate the section's char offset.
	private static final class TocEntry {
		private final String title;
		private final int level;
		private final String startMarker;
		private int startOffset = -1;
		private int endOffset = -1;
		private TocEntry(String title, int level, String startMarker) {
			this.title = title;
			this.level = level;
			this.startMarker = startMarker;
		}
	}

	/**
	 * Prose split strategy: ask the Chat client for a hierarchical table of contents, map each entry to a
	 * char span via its verbatim startMarker, and nest SECTION nodes by level (mirroring the markdown stack
	 * builder). Returns true on success (real hierarchy attached to root); false when the LLM path is
	 * unavailable or unusable so the caller degrades to the flat fallback. Never throws on LLM issues.
	 *
	 * <p><b>Offset mapping:</b> section titles are LLM-invented and do not appear verbatim, so each section
	 * is located by its startMarker via {@link String#indexOf(String,int)} searching forward from the
	 * previous section's start. Markers that cannot be located are dropped (never fabricated). endOffset is
	 * the next located section's start (flat order), or end-of-text for the last.
	 *
	 * <p><b>Multi-group:</b> option (a) — groups are processed independently and their entries appended in
	 * document order under ROOT. The forward-only marker search naturally de-duplicates the overlap region
	 * and preserves order. Levels are per-group (a later group's level-1 becomes a new top-level section);
	 * true cross-group outline continuation (reference generate_toc_continue, option (b)) is deferred.
	 */
	private static boolean buildLlmTocTree(PINode root, BaseRecord model, long orgId, long groupId, long ownerId, String content, BaseRecord user, BaseRecord chatConfig, AtomicInteger seq) throws FieldException {
		if(user == null || chatConfig == null) {
			return false;
		}
		List<TocEntry> entries = generateToc(content, user, chatConfig);
		if(entries == null || entries.isEmpty()) {
			return false;
		}

		/// Locate each entry's verbatim startMarker, searching forward from the previous located start.
		/// locateMarker() tries an exact match first, then a tolerant fallback (see its javadoc) before
		/// giving up - so a marker is only dropped when it genuinely cannot be found even loosely, not
		/// merely because the LLM's "verbatim" echo normalized a curly quote/dash or a whitespace run.
		int searchFrom = 0;
		List<TocEntry> located = new ArrayList<>();
		for(TocEntry e : entries) {
			int[] hit = locateMarker(content, e.startMarker, searchFrom);
			if(hit == null) {
				logger.warn("Dropping TOC entry; startMarker not located: \"" + snippet(e.startMarker) + "\"");
				continue;
			}
			e.startOffset = hit[0];
			searchFrom = hit[0] + hit[1];
			located.add(e);
		}
		if(located.isEmpty()) {
			return false;
		}

		/// endOffset = next located entry's start (flat document order), or end-of-text for the last.
		for(int i = 0; i < located.size(); i++) {
			located.get(i).endOffset = (i + 1 < located.size() ? located.get(i + 1).startOffset : content.length());
		}

		/// Preamble before the first section becomes leaf chunks on the root (parallels the markdown builder).
		if(located.get(0).startOffset > 0) {
			String preamble = content.substring(0, located.get(0).startOffset);
			if(preamble.trim().length() > 0) {
				int[] ord = new int[] {0};
				addLeafChunks(root, model, orgId, groupId, ownerId, preamble, 0, ord, seq);
			}
		}

		/// Nest SECTION nodes by level using the same stack algorithm as the markdown builder.
		List<PINode> stack = new ArrayList<>();
		stack.add(root);
		for(TocEntry e : located) {
			int level = Math.max(1, e.level);
			String body = content.substring(e.startOffset, e.endOffset);

			PINode section = new PINode();
			section.nodeType = PageIndexNodeEnumType.SECTION;
			section.level = level;
			section.text = body;

			while(stack.size() > 1 && stack.get(stack.size() - 1).level >= level) {
				stack.remove(stack.size() - 1);
			}
			PINode parent = stack.get(stack.size() - 1);
			int ordinal = parent.children.size();
			section.record = newNode(model, orgId, groupId, ownerId, PageIndexNodeEnumType.SECTION, e.title, null, e.startOffset, e.endOffset, level, ordinal, seq);
			parent.children.add(section);
			stack.add(section);

			/// The section's own local body (its span up to the next flat entry) becomes leaf chunks. Unlike
			/// markdown there is no header line to skip — the marker is prose, so the body starts at startOffset.
			if(body.trim().length() > 0) {
				int[] leafOrd = new int[] {0};
				addLeafChunks(section, model, orgId, groupId, ownerId, body, e.startOffset, leafOrd, seq);
			}
		}
		return true;
	}

	/// Locate `marker` in `content` starting at `searchFrom`. Tries an exact substring match first (the
	/// fast path that handles the overwhelming majority of cases); if that fails, falls back to a
	/// tolerant regex match that treats common typographic variants (apostrophe/quote style, dash style)
	/// as equivalent and collapses whitespace runs to a single boundary, so a marker that differs from
	/// the source only in "cosmetic" punctuation/whitespace is still located rather than dropped. This
	/// matters because docx/PDF extraction and LLM "verbatim" echoing don't reliably agree on curly vs.
	/// straight quotes, en/em dash vs. hyphen, or double-vs-single internal spacing, even though
	/// DocumentUtil.replaceSmartQuotes now normalizes the most common of these at extraction time (see
	/// its javadoc) - this is defense in depth for whatever it doesn't cover (e.g. an LLM that emits an
	/// en dash where the source has a plain hyphen, the reverse direction). Never fabricates a location:
	/// if no tolerant match exists either, returns null - callers still drop the entry, they just drop
	/// fewer of them. Returns {startOffset, matchedLength}; callers must advance their forward search
	/// cursor by matchedLength (not marker.length()), since the tolerant path's actual match span can
	/// differ in length from the marker (e.g. a collapsed double-space in the source vs. a single space
	/// in the marker).
	private static int[] locateMarker(String content, String marker, int searchFrom) {
		if(marker == null || marker.length() == 0 || content == null) {
			return null;
		}
		int idx = content.indexOf(marker, searchFrom);
		if(idx >= 0) {
			return new int[] {idx, marker.length()};
		}
		Pattern tolerant = buildTolerantMarkerPattern(marker);
		if(tolerant == null) {
			return null;
		}
		try {
			Matcher m = tolerant.matcher(content);
			if(m.find(searchFrom)) {
				return new int[] {m.start(), m.end() - m.start()};
			}
		}
		catch(Exception e) {
			logger.warn("Tolerant marker match failed: " + e.getMessage());
		}
		return null;
	}

	/// Builds a regex that matches `marker` literally except: any run of whitespace becomes `\s+`
	/// (tolerates single-vs-double space, straight vs. wrapped lines), and apostrophe/quote/dash
	/// characters are widened to a character class covering their common Unicode look-alikes. Every
	/// other character is matched literally via Pattern.quote. Returns null if the marker fails to
	/// compile as a pattern for any reason (defensive - this must never throw into the caller).
	private static Pattern buildTolerantMarkerPattern(String marker) {
		StringBuilder re = new StringBuilder();
		boolean lastWasSpace = false;
		for(int i = 0; i < marker.length(); i++) {
			char c = marker.charAt(i);
			if(Character.isWhitespace(c)) {
				if(!lastWasSpace) {
					re.append("\\s+");
					lastWasSpace = true;
				}
				continue;
			}
			lastWasSpace = false;
			if(c == '\'' || c == '‘' || c == '’' || c == '`' || c == '´') {
				re.append("['‘’`´]");
			}
			else if(c == '"' || c == '“' || c == '”') {
				re.append("[\"“”]");
			}
			else if(c == '-' || c == '‐' || c == '‑' || c == '‒' || c == '–' || c == '—') {
				re.append("[-‐‑‒–—]");
			}
			else {
				re.append(Pattern.quote(String.valueOf(c)));
			}
		}
		try {
			return Pattern.compile(re.toString());
		}
		catch(Exception e) {
			logger.warn("Could not build tolerant marker pattern: " + e.getMessage());
			return null;
		}
	}

	/// Split prose into ordered char-length groups (with overlap) and call the Chat client per group for a
	/// TOC. Entries are concatenated in document order (multi-group option (a)). Returns null/empty if no
	/// group produced usable structure so the caller falls back.
	private static List<TocEntry> generateToc(String content, BaseRecord user, BaseRecord chatConfig) {
		List<String> groups = splitIntoGroups(content);
		List<TocEntry> all = new ArrayList<>();
		for(String group : groups) {
			String raw = callChat(user, chatConfig, TOC_SYSTEM_PROMPT, TOC_USER_PROMPT + group + TOC_USER_SUFFIX);
			if(raw == null || raw.trim().length() == 0) {
				logger.warn("LLM-TOC: chat returned no text for a group; skipping it");
				continue;
			}
			List<TocEntry> entries = parseToc(raw);
			if(entries != null) {
				all.addAll(entries);
			}
		}
		return all;
	}

	/// Char-based grouping analogue of the reference page_list_to_group_text: single group if it fits,
	/// otherwise fixed-size windows with PAGEINDEX_TOC_GROUP_OVERLAP carried into the next window.
	private static List<String> splitIntoGroups(String content) {
		List<String> groups = new ArrayList<>();
		if(content.length() <= PAGEINDEX_TOC_GROUP_CHARS) {
			groups.add(content);
			return groups;
		}
		int pos = 0;
		while(pos < content.length()) {
			int end = Math.min(pos + PAGEINDEX_TOC_GROUP_CHARS, content.length());
			groups.add(content.substring(pos, end));
			if(end >= content.length()) {
				break;
			}
			int nextPos = end - PAGEINDEX_TOC_GROUP_OVERLAP;
			pos = (nextPos > pos ? nextPos : end);
		}
		return groups;
	}

	/// Parse the LLM's TOC JSON into ordered entries. Robust to ```json fences (ports utils.py extract_json)
	/// and to a wrapping object ({"table_of_contents":[...]}). Returns null on unparseable/empty output so
	/// the caller falls back. Entries without a usable startMarker are skipped.
	private static List<TocEntry> parseToc(String raw) {
		String json = stripJsonFences(raw);
		JsonNode node = readJsonLenient(json);
		if(node == null) {
			logger.warn("LLM-TOC: could not parse JSON from chat output. rawLen=" + (raw != null ? raw.length() : -1)
				+ " rawHead=" + debugHead(raw) + " rawTail=" + debugTail(raw) + " strippedHead=" + debugHead(json));
			return null;
		}
		JsonNode arr = node;
		if(node.isObject()) {
			/// Tolerate a wrapping object: use the first array-valued field (e.g. table_of_contents).
			arr = null;
			java.util.Iterator<JsonNode> it = node.elements();
			while(it.hasNext()) {
				JsonNode v = it.next();
				if(v.isArray()) {
					arr = v;
					break;
				}
			}
		}
		if(arr == null || !arr.isArray()) {
			logger.warn("LLM-TOC: parsed JSON is not an array");
			return null;
		}
		List<TocEntry> entries = new ArrayList<>();
		for(JsonNode e : arr) {
			String marker = textOrNull(e, "startMarker");
			if(marker == null || marker.trim().length() == 0) {
				continue;
			}
			String title = textOrNull(e, "title");
			int level = (e.has("level") && e.get("level").canConvertToInt() ? e.get("level").asInt() : 1);
			if(title == null || title.trim().length() == 0) {
				title = "Section";
			}
			entries.add(new TocEntry(title.trim(), level, marker.trim()));
		}
		/// A syntactically valid but EMPTY (or all-unusable-marker) array is a real, silent failure mode
		/// distinct from unparseable JSON: the model responded, produced valid JSON, but that group
		/// contributed zero usable structure. Previously this fell through with no log at all, making a
		/// whole group's content silently collapse into the flat preamble with no diagnostic trail (see
		/// the catatone.docx investigation notes on buildLlmTocTree). Log it so a future large-document
		/// "most content ended up in one section" report is diagnosable from logs alone.
		if(entries.isEmpty()) {
			logger.warn("LLM-TOC: parsed JSON array had zero usable entries (all missing startMarker, or "
				+ "a genuinely empty array) for this group. rawLen=" + (raw != null ? raw.length() : -1)
				+ " rawHead=" + debugHead(raw));
			return null;
		}
		return entries;
	}

	private static String textOrNull(JsonNode e, String field) {
		JsonNode v = e.get(field);
		return (v != null && !v.isNull() ? v.asText(null) : null);
	}

	/// Lenient JSON read: try as-is, then retry after stripping trailing commas (ports the extract_json
	/// second-chance cleanup). Returns null if still unparseable.
	private static JsonNode readJsonLenient(String json) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
		try {
			return mapper.readTree(json);
		}
		catch(Exception e1) {
			try {
				String cleaned = json.replace(",]", "]").replace(",}", "}");
				return mapper.readTree(cleaned);
			}
			catch(Exception e2) {
				return null;
			}
		}
	}

	/// Strip ```json ... ``` (or plain ``` ... ```) fences the way the reference get_json_content does:
	/// content after the opening fence, up to the last closing fence.
	private static String stripJsonFences(String content) {
		if(content == null) {
			return "";
		}
		String s = content.trim();
		int start = s.indexOf("```json");
		if(start != -1) {
			s = s.substring(start + 7);
		}
		else {
			int genericStart = s.indexOf("```");
			if(genericStart != -1) {
				s = s.substring(genericStart + 3);
			}
		}
		int end = s.lastIndexOf("```");
		if(end != -1) {
			s = s.substring(0, end);
		}
		return s.trim();
	}

	/// Wider head/tail previews (vs snippet()'s 60 chars) for the LLM-TOC parse-failure warning, so a
	/// genuine future failure (bad fences, truncation, unexpected model output) is diagnosable from logs
	/// alone instead of requiring a live re-run with ad hoc logging.
	private static String debugHead(String s) {
		if(s == null) {
			return "(null)";
		}
		return s.substring(0, Math.min(400, s.length())).replaceAll("\\s+", " ");
	}

	private static String debugTail(String s) {
		if(s == null) {
			return "(null)";
		}
		return s.substring(Math.max(0, s.length() - 200)).replaceAll("\\s+", " ");
	}

	private static String snippet(String s) {
		if(s == null) {
			return "";
		}
		return s.length() > 60 ? s.substring(0, 60) + "..." : s;
	}

	private static BaseRecord newNode(BaseRecord model, long orgId, long groupId, long ownerId, PageIndexNodeEnumType nodeType, String title, String content, int startOffset, int endOffset, int level, int ordinal, AtomicInteger seq) throws FieldException {
		try {
			BaseRecord node = RecordFactory.newInstance(ModelNames.MODEL_PAGE_INDEX_NODE);
			node.setValue(FieldNames.FIELD_NODE_TYPE, nodeType.toString());
			if(title != null) {
				node.setValue(FieldNames.FIELD_TITLE, title);
			}
			if(content != null) {
				node.setValue(FieldNames.FIELD_CONTENT, content);
			}
			node.setValue(FieldNames.FIELD_START_OFFSET, startOffset);
			node.setValue(FieldNames.FIELD_END_OFFSET, endOffset);
			node.setValue(FieldNames.FIELD_LEVEL, level);
			node.setValue(FieldNames.FIELD_ORDINAL, ordinal);
			node.setValue(FieldNames.FIELD_SOURCE_REFERENCE, model);
			node.setValue(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
			if(orgId > 0L) {
				node.setValue(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			}
			if(groupId > 0L) {
				node.setValue(FieldNames.FIELD_GROUP_ID, groupId);
			}
			if(ownerId > 0L) {
				node.setValue(FieldNames.FIELD_OWNER_ID, ownerId);
			}
			/// Unique per source document (and thus per group): sourceId + node sequence. Titles are kept in
			/// the title field; the name must satisfy the inherited data.directory uniqueness constraint.
			long sourceId = (model.hasField(FieldNames.FIELD_ID) ? (long)model.get(FieldNames.FIELD_ID) : 0L);
			node.setValue(FieldNames.FIELD_NAME, "pin-" + sourceId + "-" + seq.getAndIncrement());
			return node;
		}
		catch(ModelNotFoundException e) {
			throw new FieldException(e.getMessage());
		}
	}

	/// Reference summary prompt (pageindex/utils.py generate_node_summary), portable verbatim.
	private static final String SUMMARY_SYSTEM_PROMPT =
		"You are a document analysis assistant. Generate a concise description of the main points covered in "
		+ "the given partial document. Return only the description, with no other text.";
	private static final String SUMMARY_USER_PROMPT =
		"You are given a part of a document, your task is to generate a description of the partial document "
		+ "about what are main points covered in the partial document." + System.lineSeparator()
		+ System.lineSeparator() + "Partial Document Text:" + System.lineSeparator();
	private static final String SUMMARY_USER_SUFFIX =
		System.lineSeparator() + System.lineSeparator() + "Directly return the description, do not include any other text.";

	/// NEW LLM prompt path (prose TOC extraction). Plain structural instruction only — no policy/bias
	/// content. Mirrors the intent of the reference's no-TOC generate path (page_index.py generate_toc_init)
	/// but asks for strict JSON with a verbatim startMarker so offsets can be located in the extracted text.
	private static final String TOC_SYSTEM_PROMPT =
		"You are a document structure analysis assistant. You extract a hierarchical table of contents from "
		+ "prose text and return it as strict JSON. Return only the JSON, with no other text.";
	private static final String TOC_USER_PROMPT =
		"You are given the text of a document that has no explicit headings. Identify the natural hierarchical "
		+ "section structure of the document and return it as a table of contents." + System.lineSeparator()
		+ System.lineSeparator()
		+ "Return a STRICT JSON array. Each entry must be an object with exactly these keys:" + System.lineSeparator()
		+ "  \"title\": a short descriptive section title you compose (the document has no headings, so you "
		+ "invent a concise title)." + System.lineSeparator()
		+ "  \"level\": an integer nesting depth, where 1 is a top-level section, 2 is a subsection of the "
		+ "preceding level-1 section, and so on." + System.lineSeparator()
		+ "  \"startMarker\": a VERBATIM snippet of 5 to 12 words copied EXACTLY from the document text marking "
		+ "where this section begins. Copy the words exactly as they appear, including punctuation and casing. "
		+ "Do not paraphrase. The snippet MUST be findable by an exact substring search of the document." + System.lineSeparator()
		+ System.lineSeparator()
		+ "Order the entries in the order the sections appear in the document. Produce a genuine hierarchy with "
		+ "subsections where the content supports it, not a flat list." + System.lineSeparator()
		+ System.lineSeparator() + "Document text:" + System.lineSeparator();
	private static final String TOC_USER_SUFFIX =
		System.lineSeparator() + System.lineSeparator() + "Return only the JSON array, do not include any other text.";

	/// Resolve a connection-configured chatConfig the same way ChatUtil does for summarization: the shared
	/// library "contentAnalysis" chat config. The Chat client resolves the actual connection (OpenAI or
	/// local) from it, so summarization is provider-agnostic. Returns null if it can't be resolved.
	private static BaseRecord resolveChatConfig(BaseRecord user) {
		if(user == null) {
			return null;
		}
		try {
			return ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, "contentAnalysis");
		}
		catch(Exception e) {
			logger.warn("Could not resolve summarization chatConfig: " + e.getMessage());
			return null;
		}
	}

	/// Bottom-up: leaves keep their content; interior nodes (SECTION/ROOT) get a Chat-generated summary,
	/// degrading to a deterministic truncated leading excerpt if the Chat call errors or returns empty.
	private static String summarize(PINode node, BaseRecord user, BaseRecord chatConfig) {
		if(node.nodeType == PageIndexNodeEnumType.CHUNK) {
			return node.text != null ? node.text : "";
		}
		StringBuilder agg = new StringBuilder();
		String title = node.record.get(FieldNames.FIELD_TITLE);
		if(title != null) {
			agg.append(title).append(System.lineSeparator());
		}
		for(PINode child : node.children) {
			String childSummary = summarize(child, user, chatConfig);
			if(childSummary != null && childSummary.trim().length() > 0) {
				agg.append(childSummary).append(System.lineSeparator());
			}
		}
		String aggregated = agg.toString().trim();
		String summary = aggregated;
		if(aggregated.length() >= SUMMARY_CHAR_THRESHOLD) {
			/// Summarize via the connection-configured Chat / LLMConnectionManager client (NOT the local-only
			/// EmbeddingUtil.getSummary()); fall back to a truncated leading excerpt so a summary is never null.
			String svc = summarizeViaChat(user, chatConfig, aggregated);
			if(svc != null && svc.trim().length() > 0) {
				summary = svc.trim();
			}
			else {
				summary = aggregated.substring(0, SUMMARY_CHAR_THRESHOLD);
			}
		}
		node.record.setValue(FieldNames.FIELD_SUMMARY, summary);
		return summary;
	}

	/// Summary via the Chat client's STREAMING path, mirroring how ChatUtil.summarizeChunk invokes the
	/// connection-configured Chat (setLlmSystemPrompt -> newRequest -> newMessage -> chat) but consuming
	/// the SSE stream instead of buffer mode. Buffer/non-streaming mode returns empty content against
	/// Azure gpt-5.4 (the SSE reader treats the first empty delta as completion), so summaries silently
	/// fell back to deterministic excerpts. In streaming mode Chat.chat() returns null and forwards chunks
	/// to the listener; SummaryStreamListener accumulates them and yields the completed text synchronously
	/// via a latch. Returns null on any error or empty response so the caller can fall back to an excerpt.
	private static String summarizeViaChat(BaseRecord user, BaseRecord chatConfig, String content) {
		return callChat(user, chatConfig, SUMMARY_SYSTEM_PROMPT, SUMMARY_USER_PROMPT + content + SUMMARY_USER_SUFFIX);
	}

	/// Shared connection-configured Chat invocation used by BOTH summarization and LLM-TOC extraction.
	/// Mirrors ChatUtil's pattern (setLlmSystemPrompt -> newRequest -> newMessage -> chat), honoring the
	/// config-driven stream flag as the single source of truth (do NOT hardcode stream): in streaming mode
	/// chat() returns null and drives the listener asynchronously (accumulate via latch); in buffer mode it
	/// returns the completed response synchronously. Returns null on any error or empty response so callers
	/// can fall back (excerpt for summaries, flat tree for TOC). Never throws.
	private static String callChat(BaseRecord user, BaseRecord chatConfig, String systemPrompt, String userMessage) {
		if(user == null || chatConfig == null) {
			return null;
		}
		try {
			Chat chat = new Chat(user, chatConfig, null);
			chat.setLlmSystemPrompt(systemPrompt);
			SummaryStreamListener sl = new SummaryStreamListener();
			chat.setListener(sl);
			OpenAIRequest req = chat.newRequest(chat.getModel());
			/// Force reasoning off on hybrid-thinking Ollama models (e.g. qwen3), which otherwise default
			/// to thinking-on and prefix the response with unstructured chain-of-thought prose — breaking
			/// strict-JSON parsing (LLM-TOC) and polluting stored summaries. Explicitly populating "think"
			/// (vs leaving it untouched) is what lets Chat.chatInternal forward false onto the wire instead
			/// of pruning it; non-OLLAMA services ignore the field entirely.
			if(chatConfig.getEnum("serviceType") == LLMServiceEnumType.OLLAMA) {
				req.set("think", false);
			}
			chat.newMessage(req, userMessage, Chat.userRole);
			OpenAIResponse resp = chat.chat(req);
			if(req.isStream()) {
				String streamed = stripThinking(sl.await(chat.getRequestTimeout()));
				if(streamed != null && streamed.trim().length() > 0) {
					return streamed;
				}
				logger.warn("LLM chat call (streaming) produced no usable text (timeout/empty stream) for model "
					+ chat.getModel());
			}
			else {
				if(resp != null && resp.getMessage() != null) {
					String buffered = stripThinking(resp.getMessage().get(FieldNames.FIELD_CONTENT));
					if(buffered != null && buffered.trim().length() > 0) {
						return buffered;
					}
				}
				/// Previously fell through here with NO log at all on a null resp/message or empty content
				/// (e.g. a request timeout that the Chat client swallows without throwing) — a whole
				/// LLM-TOC group (or a summarization call) would silently contribute nothing, with zero
				/// trace in the logs. Log it so that failure mode is diagnosable without re-instrumenting.
				logger.warn("LLM chat call (buffered) produced no usable text (null response/message, or empty "
					+ "content) for model " + chat.getModel());
			}
		}
		catch(Exception e) {
			logger.warn("Chat call failed: " + e.getMessage());
		}
		return null;
	}

	private static final Pattern THINK_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern THOUGHT_PATTERN = Pattern.compile("<thought>.*?</thought>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/// Reasoning models (e.g. Ollama qwen3) inline their chain-of-thought as literal <think>...</think>
	/// markers in the response content (Chat.chatInternal wraps thinking deltas this way for the UI to
	/// strip on display, per ChatUtil's THINK_PATTERN convention). Callers here consume raw chat text
	/// programmatically (JSON parsing for LLM-TOC, stored text for summaries), so strip it the same way
	/// before use — an un-stripped thinking preamble breaks strict-JSON parsing and pollutes summaries.
	private static String stripThinking(String content) {
		if(content == null) {
			return null;
		}
		String s = THINK_PATTERN.matcher(content).replaceAll("");
		s = THOUGHT_PATTERN.matcher(s).replaceAll("");
		return s.trim();
	}

	/// Minimal IChatListener that accumulates a streamed summary and exposes the completed text
	/// synchronously. Chunks arrive on onupdate(); the final accumulated message arrives on oncomplete().
	/// A CountDownLatch lets summarizeViaChat block until the stream finishes (or errors/times out).
	private static final class SummaryStreamListener implements IChatListener {
		private final StringBuilder sb = new StringBuilder();
		private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		private volatile String finalContent = null;
		private volatile String error = null;

		@Override
		public OpenAIRequest sendMessageToServer(BaseRecord user, ChatRequest request) { return null; }
		@Override
		public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest) { return null; }
		@Override
		public void onupdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message) {
			if(message != null) {
				sb.append(message);
			}
		}
		@Override
		public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
			try {
				BaseRecord msg = (response != null ? response.getMessage() : null);
				if(msg != null) {
					String c = msg.get(FieldNames.FIELD_CONTENT);
					if(c != null && c.length() > 0) {
						finalContent = c;
					}
				}
			}
			catch(Exception e) {
				logger.warn("SummaryStreamListener.oncomplete: " + e.getMessage());
			}
			finally {
				latch.countDown();
			}
		}
		@Override
		public void onerror(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg) {
			error = msg;
			latch.countDown();
		}
		@Override
		public boolean isStopStream(OpenAIRequest request) { return false; }
		@Override
		public void stopStream(OpenAIRequest request) { /* no-op */ }
		@Override
		public boolean isRequesting(OpenAIRequest request) { return false; }
		@Override
		public void addChatHandler(IChatHandler handler) { /* no-op */ }

		/// Block until the stream completes, errors, or times out; return the completed text
		/// (preferring the final accumulated message, else the chunk buffer), or null.
		String await(int timeoutSeconds) {
			try {
				int wait = timeoutSeconds > 0 ? timeoutSeconds + 5 : 300;
				if(!latch.await(wait, java.util.concurrent.TimeUnit.SECONDS)) {
					logger.warn("Summary stream timed out after " + wait + "s");
					return sb.length() > 0 ? sb.toString() : null;
				}
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			if(error != null) {
				logger.warn("Summary stream error: " + error);
			}
			if(finalContent != null && finalContent.trim().length() > 0) {
				return finalContent;
			}
			return sb.length() > 0 ? sb.toString() : null;
		}
	}

	private static void embed(PINode node, EmbeddingUtil eu) {
		StringBuilder sb = new StringBuilder();
		String title = node.record.get(FieldNames.FIELD_TITLE);
		String summary = node.record.get(FieldNames.FIELD_SUMMARY);
		String content = node.record.get(FieldNames.FIELD_CONTENT);
		if(title != null) {
			sb.append(title).append(" ");
		}
		if(summary != null) {
			sb.append(summary);
		}
		else if(content != null) {
			sb.append(content);
		}
		String text = sb.toString().trim();
		if(text.length() > 0) {
			float[] emb = eu.getEmbedding(text);
			if(emb != null && emb.length > 0) {
				node.record.setValue(FieldNames.FIELD_EMBEDDING, emb);
			}
		}
		for(PINode child : node.children) {
			embed(child, eu);
		}
	}

	/// Level-order persist: write a level as a batch (which populates generated ids), then link the next
	/// level's parentId to the freshly generated ids. HierarchyValidator guards against cycles.
	private static List<BaseRecord> persist(PINode root) {
		List<BaseRecord> all = new ArrayList<>();
		List<PINode> level = Collections.singletonList(root);
		try {
			while(!level.isEmpty()) {
				List<BaseRecord> recs = new ArrayList<>();
				for(PINode n : level) {
					if(!HierarchyValidator.checkHierarchy(n.record, FieldNames.FIELD_PARENT_ID)) {
						logger.error("Hierarchy check failed for page index node; skipping");
						continue;
					}
					recs.add(n.record);
				}
				if(!recs.isEmpty()) {
					IOSystem.getActiveContext().getWriter().write(recs.toArray(new BaseRecord[0]));
					all.addAll(recs);
				}
				List<PINode> next = new ArrayList<>();
				for(PINode n : level) {
					long pid = n.record.get(FieldNames.FIELD_ID);
					for(PINode c : n.children) {
						c.record.setValue(FieldNames.FIELD_PARENT_ID, pid);
						next.add(c);
					}
				}
				level = next;
			}
		}
		catch(WriterException e) {
			logger.error(e);
		}
		return all;
	}

	private static void writeContainer(BaseRecord model, BaseRecord rootNode, int nodeCount, EmbeddingUtil eu) throws FieldException, ModelNotFoundException, WriterException {
		BaseRecord container = RecordFactory.newInstance(ModelNames.MODEL_PAGE_INDEX);
		container.setValue(FieldNames.FIELD_SOURCE_REFERENCE, model);
		container.setValue(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
		container.setValue(FieldNames.FIELD_ROOT_NODE, rootNode);
		container.setValue(FieldNames.FIELD_NODE_COUNT, nodeCount);
		if(model.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
			container.setValue(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		if(model.hasField(FieldNames.FIELD_GROUP_ID)) {
			container.setValue(FieldNames.FIELD_GROUP_ID, model.get(FieldNames.FIELD_GROUP_ID));
		}
		if(model.hasField(FieldNames.FIELD_OWNER_ID)) {
			container.setValue(FieldNames.FIELD_OWNER_ID, model.get(FieldNames.FIELD_OWNER_ID));
		}
		long sourceId = (model.hasField(FieldNames.FIELD_ID) ? (long)model.get(FieldNames.FIELD_ID) : 0L);
		container.setValue(FieldNames.FIELD_NAME, "pindex-" + sourceId);
		container.setValue(FieldNames.FIELD_GENERATED_DATE, ZonedDateTime.now());
		if(eu != null) {
			container.setValue(FieldNames.FIELD_GENERATION_MODEL, eu.getServiceType().toString());
		}
		IOSystem.getActiveContext().getWriter().write(container);
	}

	/* -------------------------------------------------------------------- retrieve -------------------------------------------------------------------- */

	/**
	 * Reasoning-descent retrieval (embedding-cosine default). Loads the root node(s) for the model, embeds the
	 * query, then descends by scoring child title+summary embeddings (cosine) against the query, collecting up
	 * to <code>limit</code> relevant leaf CHUNK nodes.
	 */
	public static List<BaseRecord> retrieve(BaseRecord model, String query, int limit) {
		List<BaseRecord> results = new ArrayList<>();
		if(model == null || query == null || query.length() == 0) {
			return results;
		}
		EmbeddingUtil eu = getEmbedUtil();
		if(eu == null) {
			logger.error("Embedding utility is not initialized.");
			return results;
		}
		float[] queryEmbedding = eu.getEmbedding(query);
		if(queryEmbedding == null || queryEmbedding.length == 0) {
			logger.error("Query embedding is null or empty");
			return results;
		}

		long orgId = (model.hasField(FieldNames.FIELD_ORGANIZATION_ID) ? (long)model.get(FieldNames.FIELD_ORGANIZATION_ID) : 0L);
		List<BaseRecord> roots = loadRoots(model, orgId);
		for(BaseRecord root : roots) {
			descend(root, orgId, queryEmbedding, eu, limit, results);
		}
		results.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
		return results.size() > limit ? new ArrayList<>(results.subList(0, limit)) : results;
	}

	private static double scoreOf(BaseRecord node) {
		Object s = node.get(FieldNames.FIELD_SCORE);
		return (s instanceof Double ? (Double)s : 0.0);
	}

	private static List<BaseRecord> loadRoots(BaseRecord model, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_NODE_TYPE, PageIndexNodeEnumType.ROOT.toString());
		q.setCache(false);
		requestNodeFields(q);
		return runQuery(q);
	}

	private static List<BaseRecord> loadChildren(long parentId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_PARENT_ID, parentId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		requestNodeFields(q);
		return runQuery(q);
	}

	/// Safe, non-embedding node fields (Defect #2: the standard Query/ISearch reader cannot project the
	/// pgvector VECTOR type — "Unhandled type: VECTOR" — VectorUtil only ever reads embeddings via raw
	/// pgvector SQL, never through a field projection; nodeEmbedding() re-derives it from title+summary
	/// when absent). Public so Service7's bespoke node-read endpoints can request the identical safe
	/// projection through AccessPoint.find/list — they must NEVER project FIELD_EMBEDDING either.
	public static String[] safeNodeRequestFields() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NODE_TYPE, FieldNames.FIELD_TITLE, FieldNames.FIELD_SUMMARY,
			FieldNames.FIELD_CONTENT, FieldNames.FIELD_LEVEL, FieldNames.FIELD_ORDINAL,
			FieldNames.FIELD_START_OFFSET, FieldNames.FIELD_END_OFFSET
		};
	}

	private static void requestNodeFields(Query q) {
		q.setRequest(safeNodeRequestFields());
	}

	private static List<BaseRecord> runQuery(Query q) {
		try {
			return new ArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().find(q).getResults()));
		}
		catch(ReaderException e) {
			logger.error(e);
			return new ArrayList<>();
		}
	}

	/// Score children by cosine, descend into the top-k relevant ones, collect leaf CHUNK nodes.
	private static void descend(BaseRecord node, long orgId, float[] queryEmbedding, EmbeddingUtil eu, int limit, List<BaseRecord> collected) {
		PageIndexNodeEnumType nt = node.getEnum(FieldNames.FIELD_NODE_TYPE);
		if(nt == PageIndexNodeEnumType.CHUNK) {
			node.setValue(FieldNames.FIELD_SCORE, cosine(nodeEmbedding(node, eu), queryEmbedding));
			collected.add(node);
			return;
		}
		long id = node.get(FieldNames.FIELD_ID);
		List<BaseRecord> children = loadChildren(id, orgId);
		if(children.isEmpty()) {
			return;
		}
		/// Score each child by cosine of its stored (or re-derived) embedding vs the query.
		for(BaseRecord child : children) {
			child.setValue(FieldNames.FIELD_SCORE, cosine(nodeEmbedding(child, eu), queryEmbedding));
		}
		children.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
		/// Descend into the top-k relevant children (k bounded by the requested limit).
		int topK = Math.min(children.size(), Math.max(1, limit));
		for(int i = 0; i < topK; i++) {
			descend(children.get(i), orgId, queryEmbedding, eu, limit, collected);
		}
	}

	/// Prefer the persisted embedding; if absent, derive one from the node's title+summary/content.
	private static float[] nodeEmbedding(BaseRecord node, EmbeddingUtil eu) {
		float[] emb = node.get(FieldNames.FIELD_EMBEDDING);
		if(emb != null && emb.length > 0) {
			return emb;
		}
		StringBuilder sb = new StringBuilder();
		String title = node.get(FieldNames.FIELD_TITLE);
		String summary = node.get(FieldNames.FIELD_SUMMARY);
		String content = node.get(FieldNames.FIELD_CONTENT);
		if(title != null) {
			sb.append(title).append(" ");
		}
		if(summary != null) {
			sb.append(summary);
		}
		else if(content != null) {
			sb.append(content);
		}
		String text = sb.toString().trim();
		return (text.length() > 0 ? eu.getEmbedding(text) : new float[0]);
	}

	private static double cosine(float[] a, float[] b) {
		if(a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
			return 0.0;
		}
		double dot = 0.0;
		double na = 0.0;
		double nb = 0.0;
		for(int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		if(na == 0.0 || nb == 0.0) {
			return 0.0;
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}

	/**
	 * Returns every node for the given source as a flat, safely-projected list (never the VECTOR
	 * embedding field, via {@link #requestNodeFields(Query)}), ordered by level then ordinal so a
	 * caller can rebuild the ROOT -&gt; SECTION -&gt; CHUNK tree by walking {@code parentId} without a
	 * nested response shape. Callers (e.g. {@code AccessPoint.pageIndexTree}) are responsible for PBAC.
	 */
	public static List<BaseRecord> getTree(BaseRecord model) {
		long orgId = (model.hasField(FieldNames.FIELD_ORGANIZATION_ID) ? (long)model.get(FieldNames.FIELD_ORGANIZATION_ID) : 0L);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		q.setRequestRange(0, 20000);
		requestNodeFields(q);
		List<BaseRecord> nodes = runQuery(q);
		nodes.sort((a, b) -> {
			int la = a.get(FieldNames.FIELD_LEVEL);
			int lb = b.get(FieldNames.FIELD_LEVEL);
			if(la != lb) {
				return Integer.compare(la, lb);
			}
			int oa = a.get(FieldNames.FIELD_ORDINAL);
			int ob = b.get(FieldNames.FIELD_ORDINAL);
			return Integer.compare(oa, ob);
		});
		return nodes;
	}

	/* ------------------------------------------------------------------ count / delete ------------------------------------------------------------------ */

	public static int countPageIndex(BaseRecord model) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	public static int deletePageIndex(BaseRecord model) {
		int del = 0;
		try {
			Query nodeQ = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
			nodeQ.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
			nodeQ.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
			nodeQ.setCache(false);
			del = IOSystem.getActiveContext().getWriter().delete(nodeQ);

			Query contQ = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
			contQ.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
			contQ.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
			contQ.setCache(false);
			IOSystem.getActiveContext().getWriter().delete(contQ);
		}
		catch(WriterException e) {
			logger.error(e);
		}
		return del;
	}

}
