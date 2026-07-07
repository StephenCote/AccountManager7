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
import org.cote.accountmanager.olio.llm.ChatUtil;
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

		PINode root = buildTree(model, content);

		/// Resolve a connection-configured chatConfig once (same library "contentAnalysis" config ChatUtil
		/// uses for summarization); the Chat client resolves the connection (OpenAI or local) from it.
		BaseRecord chatConfig = resolveChatConfig(user);

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

	private static PINode buildTree(BaseRecord model, String content) throws FieldException {
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
			/// No markdown structure: attach leaf chunks directly to the root.
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

	/// One-shot, non-streaming summary via the Chat client (buffer mode), mirroring
	/// ChatUtil.summarizeChunk's use of the connection-configured Chat. Returns null on any error or empty
	/// response so the caller can fall back to a deterministic excerpt.
	private static String summarizeViaChat(BaseRecord user, BaseRecord chatConfig, String content) {
		if(user == null || chatConfig == null) {
			return null;
		}
		try {
			Chat chat = new Chat(user, chatConfig, null);
			chat.setLlmSystemPrompt(SUMMARY_SYSTEM_PROMPT);
			OpenAIRequest req = chat.newRequest(chat.getModel());
			req.setStream(false);
			String cmd = SUMMARY_USER_PROMPT + content + SUMMARY_USER_SUFFIX;
			chat.newMessage(req, cmd, Chat.userRole);
			OpenAIResponse resp = chat.chat(req);
			if(resp != null && resp.getMessage() != null) {
				return resp.getMessage().getContent();
			}
		}
		catch(Exception e) {
			logger.warn("Chat summarization failed, falling back to excerpt: " + e.getMessage());
		}
		return null;
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
		if(orgId > 0L) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		}
		q.field(FieldNames.FIELD_NODE_TYPE, PageIndexNodeEnumType.ROOT.toString());
		requestNodeFields(q);
		return runQuery(q);
	}

	private static List<BaseRecord> loadChildren(long parentId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_PARENT_ID, parentId);
		if(orgId > 0L) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		}
		requestNodeFields(q);
		return runQuery(q);
	}

	private static void requestNodeFields(Query q) {
		/// Defect #2: the standard Query/ISearch reader cannot project the pgvector VECTOR type
		/// (ReaderException: "Unhandled type: VECTOR") — VectorUtil only ever reads embeddings via
		/// raw pgvector SQL, never through a field projection. Projecting FIELD_EMBEDDING here made
		/// every root/child query throw, so retrieve() always returned 0. Omit the embedding from
		/// the projection; nodeEmbedding() re-derives it from title+summary when absent.
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_NODE_TYPE, FieldNames.FIELD_TITLE, FieldNames.FIELD_SUMMARY, FieldNames.FIELD_CONTENT,
			FieldNames.FIELD_LEVEL, FieldNames.FIELD_ORDINAL,
			FieldNames.FIELD_START_OFFSET, FieldNames.FIELD_END_OFFSET
		});
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

	/* ------------------------------------------------------------------ count / delete ------------------------------------------------------------------ */

	public static int countPageIndex(BaseRecord model) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
		q.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		return IOSystem.getActiveContext().getSearch().count(q);
	}

	public static int deletePageIndex(BaseRecord model) {
		int del = 0;
		try {
			Query nodeQ = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
			nodeQ.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
			nodeQ.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
			del = IOSystem.getActiveContext().getWriter().delete(nodeQ);

			Query contQ = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX, FieldNames.FIELD_SOURCE_REFERENCE, model.copyRecord(new String[] {FieldNames.FIELD_ID}));
			contQ.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, model.getSchema());
			contQ.field(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getWriter().delete(contQ);
		}
		catch(WriterException e) {
			logger.error(e);
		}
		return del;
	}

}
