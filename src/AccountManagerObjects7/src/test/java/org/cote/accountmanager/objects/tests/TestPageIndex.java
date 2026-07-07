package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PageIndexNodeEnumType;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.PageIndexUtil;
import org.junit.Test;

/// Real end-to-end integration test for the PageIndex capability (AccountManagerObjects7).
///
/// Exercises the ACTUAL pipeline against the live backend:
///   - live PostgreSQL/pgvector (new additive tables A7_data_pageIndexNode_0 / A7_data_pageIndex_0)
///   - live Azure OpenAI chat (summarization) and Azure OpenAI embeddings (cosine retrieval)
///
/// Never uses the admin user as the acting user: a non-admin "testUser1" is created (via the admin
/// user, as every other Objects7 test does) and is the actor for pageIndex/retrieve. The admin user is
/// only used for shared-library fixture seeding (as production seeding does).
///
/// Gated behind the PAGEINDEX_LLM env flag so it never fires in the default parallel suite; run
/// single-threaded (it makes live LLM calls). Enable with PAGEINDEX_LLM=1.
public class TestPageIndex extends BaseTest {

	private static final String LIB_CONTENT_ANALYSIS = "contentAnalysis";

	/// Set by ensureAzureContentAnalysis: whether the LIVE Azure summarization call actually returned
	/// text. When false, PageIndexUtil summaries come from the deterministic excerpt/aggregate fallback
	/// (NOT the LLM) — we must not claim LLM summarization was exercised.
	private boolean summarizationLive = false;
	private String summarizationFinding = "(not evaluated)";

	private boolean llmEnabled() {
		return System.getenv("PAGEINDEX_LLM") != null;
	}

	/* ------------------------------------------------------------------ STEP 0 ------------------------------------------------------------------ */

	/// Report + verify what ChatUtil.getLibraryConfig(user, MODEL_CHAT_CONFIG, "contentAnalysis")
	/// resolves to, and repoint it at the working Azure OpenAI chat connection (mirroring
	/// OlioTestUtil.getOpenAIConfig) so PageIndexUtil's summarization path genuinely hits Azure.
	/// Then instantiate the exact Chat client PageIndexUtil uses and make one real Azure call.
	private BaseRecord ensureAzureContentAnalysis(OrganizationContext octx, BaseRecord testUser) {
		BaseRecord adminUser = octx.getAdminUser();
		String server = testProperties.getProperty("test.llm.openai.server");
		String model = testProperties.getProperty("test.llm.openai.model");
		String version = testProperties.getProperty("test.llm.openai.version");
		String token = testProperties.getProperty("test.llm.openai.authorizationToken");

		/// --- STEP 0 report: what does contentAnalysis resolve to BEFORE any fixture change? ---
		BaseRecord before = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, LIB_CONTENT_ANALYSIS);
		if(before == null) {
			logger.info("[STEP0] Library 'contentAnalysis' chat config does NOT exist in this test DB (would fall back to excerpt).");
		}
		else {
			Object st = before.getEnum("serviceType");
			BaseRecord conn = before.get("connection");
			String curl = describeConnectionUrl(testUser, conn);
			logger.info("[STEP0] Existing 'contentAnalysis' config: serviceType=" + st + " model=" + before.get("model")
				+ " connectionUrl=" + curl);
		}

		/// --- Ensure shared libraries exist, then repoint contentAnalysis at Azure ---
		ChatLibraryUtil.getCreateChatConfigLibrary(adminUser);
		ChatLibraryUtil.getCreateConnectionLibrary(adminUser);

		/// Azure connection owned by the test user (so the non-admin actor can read/decrypt apiKey),
		/// mirroring OlioTestUtil.getOpenAIConfig / getCreateConnection. getCreateConnection is idempotent
		/// and vault-encrypts apiKey on create; do NOT update it again (that would re-vault and corrupt it).
		BaseRecord azureConn = OlioTestUtil.getCreateConnection(testUser, "Azure PageIndex Conn", server, token, 120);
		assertNotNull("Azure connection could not be created", azureConn);

		BaseRecord cfg = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, LIB_CONTENT_ANALYSIS);
		try {
			if(cfg == null) {
				BaseRecord chatLibDir = ChatLibraryUtil.findLibraryDir(adminUser, ChatLibraryUtil.LIBRARY_CHAT_CONFIGS);
				assertNotNull("Chat config library dir is null", chatLibDir);
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, chatLibDir.get("path"));
				plist.parameter(FieldNames.FIELD_NAME, LIB_CONTENT_ANALYSIS);
				cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, adminUser, null, plist);
				ChatUtil.applyChatConfigTemplate(cfg, LIB_CONTENT_ANALYSIS);
				cfg.set("connection", azureConn);
				cfg.set("serviceType", LLMServiceEnumType.OPENAI);
				cfg.set("apiVersion", version);
				cfg.set("model", model);
				cfg = IOSystem.getActiveContext().getAccessPoint().create(adminUser, cfg);
				logger.info("[STEP0] Created 'contentAnalysis' library config pointed at Azure.");
			}
			else {
				cfg.set("connection", azureConn);
				cfg.set("serviceType", LLMServiceEnumType.OPENAI);
				cfg.set("apiVersion", version);
				cfg.set("model", model);
				cfg = IOSystem.getActiveContext().getAccessPoint().update(adminUser, cfg);
				logger.info("[STEP0] Repointed existing 'contentAnalysis' library config at Azure.");
			}
		}
		catch(Exception e) {
			logger.error("[STEP0] Failed to configure contentAnalysis at Azure", e);
		}

		/// Re-fetch as the test user (the exact resolution PageIndexUtil.resolveChatConfig performs).
		BaseRecord resolved = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, LIB_CONTENT_ANALYSIS);
		assertNotNull("contentAnalysis config did not resolve after setup", resolved);
		logger.info("[STEP0] Resolved 'contentAnalysis' (as testUser): serviceType=" + resolved.getEnum("serviceType")
			+ " model=" + resolved.get("model") + " connectionUrl=" + describeConnectionUrl(testUser, resolved.get("connection")));

		/// --- Independent live proof: use the EXACT Chat client PageIndexUtil uses, hit Azure once. ---
		String live = liveSummaryCall(testUser, resolved,
			"The station's electricity is produced by a geothermal turbine drawing heat from a deep magma vent.");
		summarizationLive = (live != null && live.trim().length() > 0);
		if(summarizationLive) {
			summarizationFinding = "LLM summarization LIVE against Azure — sample: \"" + live.trim() + "\"";
			logger.info("[STEP0] " + summarizationFinding);
		}
		else {
			summarizationFinding = "LLM summarization NOT exercised: the live Azure Chat call returned no text (see HTTP "
				+ "status/error above). PageIndexUtil summaries therefore come from the deterministic excerpt/aggregate "
				+ "fallback, NOT the LLM. Do NOT claim LLM summarization was tested.";
			logger.error("[STEP0][FINDING] " + summarizationFinding);
		}

		return resolved;
	}

	private String describeConnectionUrl(BaseRecord user, BaseRecord conn) {
		if(conn == null) {
			return "(no connection FK)";
		}
		try {
			long cid = conn.get(FieldNames.FIELD_ID);
			Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, cid);
			cq.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
			cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "serverUrl", "requestTimeout" });
			BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
			return (full != null ? String.valueOf(full.get("serverUrl")) : "(connection id=" + cid + " not readable)");
		}
		catch(Exception e) {
			return "(error reading connection: " + e.getMessage() + ")";
		}
	}

	/// Replicates PageIndexUtil.summarizeViaChat exactly (that method is private) so STEP 0 proves the
	/// same client/config path is live. Returns null on any error/empty, matching the util's fallback trigger.
	private String liveSummaryCall(BaseRecord user, BaseRecord chatConfig, String content) {
		if(user == null || chatConfig == null) {
			return null;
		}
		try {
			Chat chat = new Chat(user, chatConfig, null);
			chat.setLlmSystemPrompt("You are a document analysis assistant. Generate a concise description of the main "
				+ "points covered in the given partial document. Return only the description, with no other text.");
			OpenAIRequest req = chat.newRequest(chat.getModel());
			req.setStream(false);
			chat.newMessage(req, "You are given a part of a document, your task is to generate a description of the "
				+ "partial document about what are main points covered.\n\nPartial Document Text:\n" + content
				+ "\n\nDirectly return the description, do not include any other text.", Chat.userRole);
			OpenAIResponse resp = chat.chat(req);
			if(resp != null && resp.getMessage() != null) {
				return resp.getMessage().getContent();
			}
		}
		catch(Exception e) {
			logger.warn("[STEP0] Live summary call failed: " + e.getMessage());
		}
		return null;
	}

	/* --------------------------------------------------------------- structured MD --------------------------------------------------------------- */

	@Test
	public void TestPageIndexMarkdownHierarchy() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== TestPageIndexMarkdownHierarchy (structured markdown; real hierarchy + LLM summarization) ===");

		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");
		BaseRecord testUser = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull("Test user is null", testUser);

		BaseRecord cfg = ensureAzureContentAnalysis(octx, testUser);
		assertNotNull("contentAnalysis not resolved", cfg);

		String md = buildMarkdownDoc();
		assertTrue("Authored markdown too short to force LLM summarization", md.length() > 3000);
		BaseRecord doc = getCreateData(testUser, "Kestrel-Station-Overview.md", "text/plain", md.getBytes(), "~/PageIndex", octx.getOrganizationId());
		assertNotNull("Source doc is null", doc);

		PageIndexStats stats = buildAndInspect(testUser, doc, "MARKDOWN kestrel-station");

		/// Structural expectations UNIQUE to markdown: a real multi-level tree.
		assertTrue("Expected a ROOT node", stats.roots == 1);
		assertTrue("Expected >=1 SECTION node for structured markdown (got " + stats.sections + ")", stats.sections >= 1);
		assertTrue("Expected >=1 CHUNK leaf (got " + stats.chunks + ")", stats.chunks >= 1);
		assertTrue("Expected tree depth > 1 (root->section->chunk), got maxLevel=" + stats.maxLevel, stats.maxLevel >= 2);

		/// Retrieval targeting one specific section (Power Systems / geothermal).
		List<BaseRecord> hits = PageIndexUtil.retrieve(doc, "How is the station's electricity generated?", 5);
		reportRetrieval(hits, "How is the station's electricity generated?");
		for(BaseRecord h : hits) {
			assertTrue("retrieve returned a non-CHUNK node", h.getEnum(FieldNames.FIELD_NODE_TYPE) == PageIndexNodeEnumType.CHUNK);
		}
		boolean relevant = hits.stream().anyMatch(h -> {
			String c = h.get(FieldNames.FIELD_CONTENT);
			return c != null && (c.toLowerCase().contains("geothermal") || c.toLowerCase().contains("power") || c.toLowerCase().contains("turbine"));
		});
		logger.info("[RETRIEVE][MARKDOWN] top leaf mentions the Power Systems topic (geothermal/power/turbine)? " + relevant);
		assertTrue("retrieve returned no leaves. DEFECT: PageIndexUtil.loadRoots/loadChildren project the "
			+ "'embedding' VECTOR field (requestNodeFields), and reading that projection throws "
			+ "ReaderException: 'Unhandled type: VECTOR' (PageIndexUtil.java:599) — so every query returns "
			+ "empty and retrieve() always yields 0. The persisted tree itself is correct (see [DIAG] BFS "
			+ "which reaches all nodes projecting only id+nodeType). Fix: drop FIELD_EMBEDDING from the "
			+ "retrieval projection (nodeEmbedding() already re-derives it) or read vectors via the VectorUtil path.",
			!hits.isEmpty());
	}

	/* ------------------------------------------------------------------ media docs ------------------------------------------------------------------ */

	@Test
	public void TestPageIndexShortDocx() {
		runMediaDoc("./media/Vore.docx", "SHORT DOCX", "What happens in the story?");
	}

	@Test
	public void TestPageIndexMediumPdf() {
		runMediaDoc("./media/AIME.pdf", "MEDIUM PDF", "What is the main subject of this document?");
	}

	@Test
	public void TestPageIndexLongDocx() {
		runMediaDoc("./media/The Verse.docx", "LONG DOCX", "Who is Mark Lucean?");
	}

	private void runMediaDoc(String path, String label, String question) {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== TestPageIndex media: " + label + " (" + path + ") ===");

		File f = new File(path);
		assumeTrue("Media file not present: " + path, f.exists());

		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");
		BaseRecord testUser = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull("Test user is null", testUser);

		ensureAzureContentAnalysis(octx, testUser);

		/// Honesty check: report extraction result up front.
		String extracted = path.endsWith(".pdf") ? DocumentUtil.readPDF(path) : DocumentUtil.readDocument(path);
		long extractedLen = (extracted != null ? extracted.length() : 0);
		logger.info("[MEDIA][" + label + "] extracted text length=" + extractedLen);
		if(extracted == null || extracted.trim().length() == 0) {
			logger.error("[MEDIA][" + label + "] FINDING: DocumentUtil failed to extract any text from " + path);
		}
		assertTrue("[MEDIA][" + label + "] no text extracted from " + path + " — genuine extraction failure", extractedLen > 0);

		/// getCreateDocument extracts + stores as text/plain data (no markdown headers survive).
		BaseRecord doc = getCreateDocument(testUser, path);
		assertNotNull("Source doc is null for " + path, doc);

		long t0 = System.currentTimeMillis();
		PageIndexStats stats = buildAndInspect(testUser, doc, label + " " + f.getName());
		long buildMs = System.currentTimeMillis() - t0;
		logger.info("[MEDIA][" + label + "] build+persist+embed elapsed=" + buildMs + "ms");

		/// Build must succeed with a valid (possibly flat) tree.
		assertTrue("[MEDIA][" + label + "] expected exactly one ROOT (got " + stats.roots + ")", stats.roots == 1);
		assertTrue("[MEDIA][" + label + "] expected >=1 CHUNK leaf (got " + stats.chunks + ")", stats.chunks >= 1);
		logger.info("[MEDIA][" + label + "] tree shape: ROOT=" + stats.roots + " SECTION=" + stats.sections
			+ " CHUNK=" + stats.chunks + " maxLevel=" + stats.maxLevel
			+ (stats.sections == 0 ? "  => FLAT ROOT->CHUNK (no ATX headers; expected for extracted PDF/DOCX prose)"
				: "  => hierarchical (extracted text contained '#' header-like lines)"));

		long r0 = System.currentTimeMillis();
		List<BaseRecord> hits = PageIndexUtil.retrieve(doc, question, 5);
		logger.info("[MEDIA][" + label + "] retrieve elapsed=" + (System.currentTimeMillis() - r0) + "ms");
		reportRetrieval(hits, question);
		for(BaseRecord h : hits) {
			assertTrue("[MEDIA][" + label + "] retrieve returned a non-CHUNK node",
				h.getEnum(FieldNames.FIELD_NODE_TYPE) == PageIndexNodeEnumType.CHUNK);
		}
		assertTrue("[MEDIA][" + label + "] retrieve returned no leaves. DEFECT: PageIndexUtil retrieval "
			+ "projects the 'embedding' VECTOR field which throws 'Unhandled type: VECTOR' on read "
			+ "(PageIndexUtil.java:599); retrieve() always yields 0. Build/persist is correct (see [DIAG]).",
			!hits.isEmpty());
	}

	/* ------------------------------------------------------------------ shared core ------------------------------------------------------------------ */

	/// Build the index via the PBAC-gated AccessPoint.pageIndex, then query the persisted tree and assert
	/// structural invariants shared by ALL documents (identity, parentId resolution, leaf offsets/content,
	/// interior summaries). Returns the shape stats for per-test expectations.
	private PageIndexStats buildAndInspect(BaseRecord testUser, BaseRecord doc, String label) {
		long orgId = doc.get(FieldNames.FIELD_ORGANIZATION_ID);
		String objectId = doc.get(FieldNames.FIELD_OBJECT_ID);

		boolean built = IOSystem.getActiveContext().getAccessPoint().pageIndex(testUser, "data.data", objectId);
		assertTrue("[" + label + "] AccessPoint.pageIndex returned false", built);

		int count = PageIndexUtil.countPageIndex(doc);
		logger.info("[" + label + "] countPageIndex=" + count);
		assertTrue("[" + label + "] countPageIndex not > 0", count > 0);

		/// Load every node for this source (explicit numeric organizationId — data.directory PBAC requires it).
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE,
			doc.copyRecord(new String[] { FieldNames.FIELD_ID }));
		q.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, doc.getSchema());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_NODE_TYPE, FieldNames.FIELD_TITLE, FieldNames.FIELD_SUMMARY, FieldNames.FIELD_CONTENT,
			FieldNames.FIELD_LEVEL, FieldNames.FIELD_ORDINAL, FieldNames.FIELD_START_OFFSET, FieldNames.FIELD_END_OFFSET
		});
		q.setRequestRange(0, 20000);

		List<BaseRecord> nodes = new ArrayList<>();
		try {
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			for(BaseRecord r : qr.getResults()) {
				nodes.add(r);
			}
		}
		catch(Exception e) {
			logger.error("[" + label + "] node query failed", e);
		}
		assertTrue("[" + label + "] no nodes queried back", !nodes.isEmpty());

		PageIndexStats stats = new PageIndexStats();
		Set<Long> ids = new HashSet<>();
		Map<Long, PageIndexNodeEnumType> typeById = new HashMap<>();
		for(BaseRecord n : nodes) {
			long id = n.get(FieldNames.FIELD_ID);
			ids.add(id);
			PageIndexNodeEnumType nt = n.getEnum(FieldNames.FIELD_NODE_TYPE);
			typeById.put(id, nt);
			int level = n.get(FieldNames.FIELD_LEVEL);
			if(level > stats.maxLevel) {
				stats.maxLevel = level;
			}
			if(nt == PageIndexNodeEnumType.ROOT) {
				stats.roots++;
			}
			else if(nt == PageIndexNodeEnumType.SECTION) {
				stats.sections++;
			}
			else if(nt == PageIndexNodeEnumType.CHUNK) {
				stats.chunks++;
			}
		}
		logger.info("[" + label + "] persisted nodes=" + nodes.size() + " ROOT=" + stats.roots
			+ " SECTION=" + stats.sections + " CHUNK=" + stats.chunks + " maxLevel=" + stats.maxLevel);

		/// Exactly one ROOT for every document.
		assertTrue("[" + label + "] expected exactly one ROOT, got " + stats.roots, stats.roots == 1);

		/// parentId links: every non-root node's parent must exist in the node set.
		int leafWithOffsets = 0;
		int leafWithContent = 0;
		int interiorWithSummary = 0;
		int interiorCount = 0;
		List<String> interiorSummaryReport = new ArrayList<>();
		for(BaseRecord n : nodes) {
			PageIndexNodeEnumType nt = n.getEnum(FieldNames.FIELD_NODE_TYPE);
			long pid = (n.get(FieldNames.FIELD_PARENT_ID) != null ? (long) n.get(FieldNames.FIELD_PARENT_ID) : 0L);
			if(nt == PageIndexNodeEnumType.ROOT) {
				continue;
			}
			assertTrue("[" + label + "] non-root node has no parentId", pid != 0L);
			assertTrue("[" + label + "] parentId " + pid + " does not resolve to a node in the set", ids.contains(pid));

			if(nt == PageIndexNodeEnumType.CHUNK) {
				int so = n.get(FieldNames.FIELD_START_OFFSET);
				int eo = n.get(FieldNames.FIELD_END_OFFSET);
				String content = n.get(FieldNames.FIELD_CONTENT);
				if(eo > so) {
					leafWithOffsets++;
				}
				if(content != null && content.trim().length() > 0) {
					leafWithContent++;
				}
			}
		}

		/// Interior (ROOT + SECTION) nodes must carry a non-empty summary.
		for(BaseRecord n : nodes) {
			PageIndexNodeEnumType nt = n.getEnum(FieldNames.FIELD_NODE_TYPE);
			if(nt == PageIndexNodeEnumType.CHUNK) {
				continue;
			}
			interiorCount++;
			String summary = n.get(FieldNames.FIELD_SUMMARY);
			if(summary != null && summary.trim().length() > 0) {
				interiorWithSummary++;
			}
			String title = n.get(FieldNames.FIELD_TITLE);
			String cls = classifySummary(summary, title);
			String snip = (summary == null ? "(null)" : summary.substring(0, Math.min(160, summary.length())).replaceAll("\\s+", " "));
			interiorSummaryReport.add("    [" + nt + "] title=\"" + title + "\" summaryLen=" + (summary == null ? 0 : summary.length())
				+ " class=" + cls + " :: " + snip);
		}

		logger.info("[" + label + "] leaves(CHUNK): total=" + stats.chunks + " withOffsets(end>start)=" + leafWithOffsets
			+ " withNonEmptyContent=" + leafWithContent);
		logger.info("[" + label + "] interior nodes=" + interiorCount + " withNonEmptySummary=" + interiorWithSummary);
		logger.info("[" + label + "] SUMMARIZATION MODE: " + (summarizationLive ? "LLM (Azure Chat, verified live in STEP0)"
			: "FALLBACK excerpt/aggregate (LLM path NOT exercised) -- " + summarizationFinding));

		/// Diagnostic: localize any retrieve() gap by mirroring PageIndexUtil.loadRoots (nodeType="ROOT"
		/// filter) and loadChildren (by parentId). Informative logging only; not an assertion.
		try {
			Query rootQ = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_SOURCE_REFERENCE, doc.copyRecord(new String[] { FieldNames.FIELD_ID }));
			rootQ.field(FieldNames.FIELD_SOURCE_REFERENCE_TYPE, doc.getSchema());
			rootQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			rootQ.field(FieldNames.FIELD_NODE_TYPE, PageIndexNodeEnumType.ROOT.toString());
			int rootByFilter = IOSystem.getActiveContext().getSearch().count(rootQ);
			long rootId = 0L;
			for(BaseRecord n : nodes) {
				if(n.getEnum(FieldNames.FIELD_NODE_TYPE) == PageIndexNodeEnumType.ROOT) {
					rootId = n.get(FieldNames.FIELD_ID);
				}
			}
			int childrenOfRoot = 0;
			if(rootId > 0L) {
				Query childQ = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_PARENT_ID, rootId);
				childQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				childrenOfRoot = IOSystem.getActiveContext().getSearch().count(childQ);
			}
			logger.info("[" + label + "][DIAG] loadRoots(nodeType=\"ROOT\") count=" + rootByFilter
				+ " ; rootId=" + rootId + " ; loadChildren(parentId=rootId) count=" + childrenOfRoot);

			/// Full BFS from root using parentId queries (mirrors descend's traversal) — counts reachable
			/// nodes and CHUNK leaves so we can see exactly where retrieve()'s walk terminates.
			if(rootId > 0L) {
				int reachable = 0;
				int reachableChunks = 0;
				List<Long> frontier = new ArrayList<>();
				frontier.add(rootId);
				int depth = 0;
				while(!frontier.isEmpty() && depth < 12) {
					List<Long> next = new ArrayList<>();
					for(Long pid : frontier) {
						Query cq = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_PARENT_ID, pid);
						cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
						cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NODE_TYPE });
						cq.setRequestRange(0, 20000);
						QueryResult cr = IOSystem.getActiveContext().getSearch().find(cq);
						for(BaseRecord c : cr.getResults()) {
							reachable++;
							if(c.getEnum(FieldNames.FIELD_NODE_TYPE) == PageIndexNodeEnumType.CHUNK) {
								reachableChunks++;
							}
							next.add((long) c.get(FieldNames.FIELD_ID));
						}
					}
					frontier = next;
					depth++;
				}
				logger.info("[" + label + "][DIAG] BFS-from-root reachable nodes=" + reachable
					+ " reachableChunks=" + reachableChunks + " maxDepthWalked=" + depth);
			}
		}
		catch(Exception e) {
			logger.warn("[" + label + "][DIAG] retrieve-localization probe failed: " + e.getMessage());
		}
		for(String line : interiorSummaryReport) {
			logger.info("[" + label + "] summary" + System.lineSeparator() + line);
		}

		/// Assertions: leaves carry offsets + content; interior nodes carry summaries.
		assertTrue("[" + label + "] no CHUNK leaf carried a positive offset range", leafWithOffsets > 0);
		assertTrue("[" + label + "] not all CHUNK leaves carried content (" + leafWithContent + "/" + stats.chunks + ")",
			leafWithContent == stats.chunks);
		assertTrue("[" + label + "] not all interior nodes carried a summary (" + interiorWithSummary + "/" + interiorCount + ")",
			interiorWithSummary == interiorCount);

		return stats;
	}

	/// Heuristic classification for honesty reporting only (not an assertion): the excerpt fallback is
	/// exactly SUMMARY_CHAR_THRESHOLD (800) chars and begins with the node's aggregated content (title
	/// first); a genuine LLM description is shorter and does not start verbatim with the title.
	private String classifySummary(String summary, String title) {
		if(summary == null || summary.trim().length() == 0) {
			return "EMPTY";
		}
		if(summary.length() == PageIndexUtil.SUMMARY_CHAR_THRESHOLD) {
			return "FALLBACK-EXCERPT? (len==threshold 800)";
		}
		if(title != null && title.trim().length() > 0 && summary.startsWith(title.trim())) {
			return "VERBATIM/UNDER-THRESHOLD (aggregated stored raw)";
		}
		return "LLM-SUMMARY (likely)";
	}

	private void reportRetrieval(List<BaseRecord> hits, String question) {
		logger.info("[RETRIEVE] q=\"" + question + "\" returned " + hits.size() + " leaf CHUNK node(s):");
		int i = 0;
		for(BaseRecord h : hits) {
			Object score = h.get(FieldNames.FIELD_SCORE);
			String content = h.get(FieldNames.FIELD_CONTENT);
			String snip = (content == null ? "(null)" : content.substring(0, Math.min(180, content.length())).replaceAll("\\s+", " "));
			logger.info("    #" + (i++) + " score=" + score + " :: " + snip);
		}
	}

	private static class PageIndexStats {
		int roots = 0;
		int sections = 0;
		int chunks = 0;
		int maxLevel = 0;
	}

	private String buildMarkdownDoc() {
		StringBuilder sb = new StringBuilder();
		sb.append("# Kestrel Deep Research Station\n\n");
		sb.append("Kestrel is a manned deep-ocean research station anchored to the floor of the Aleutian Trench. ")
			.append("This overview describes its power generation, the marine biology laboratory, the crew rotation ")
			.append("schedule, and the emergency protocols that keep the station operational at extreme depth. ")
			.append("Each section can be read independently by visiting engineers and relief crew.\n\n");

		sb.append("## Power Systems\n\n");
		sb.append("Kestrel's primary electricity is produced by a closed-loop geothermal turbine that draws heat from a ")
			.append("nearby magma vent through an armored heat exchanger. The turbine spins a pair of redundant generators ")
			.append("rated at four megawatts each, and the hot brine is returned to the vent field after passing through the ")
			.append("desalination stack. Because the vent temperature fluctuates, an automated governor throttles the working ")
			.append("fluid to hold output steady. If the geothermal loop is taken offline for maintenance, a bank of lithium ")
			.append("titanate batteries carries critical loads for up to eighteen hours, and a sealed diesel generator provides ")
			.append("a final backup for the life-support scrubbers and the ballast pumps. All three sources feed a common ")
			.append("direct-current bus that is monitored continuously from the control cupola.\n\n");

		sb.append("## Marine Biology Laboratory\n\n");
		sb.append("The wet lab occupies the lowest pressure module and specializes in the study of bioluminescent cephalopods ")
			.append("that congregate around the vent field. Specimens are captured with a soft-actuator manipulator arm and held ")
			.append("in chilled pressure aquaria that mimic the ambient conditions of the trench. Researchers catalogue the ")
			.append("light-organ chemistry of each animal and sequence the symbiotic bacteria responsible for the glow. The lab ")
			.append("also maintains a long-term sediment core archive used to reconstruct the history of chemosynthetic ")
			.append("communities. Data collected here is transmitted to the surface tender twice per day over an acoustic modem.\n\n");

		sb.append("## Crew and Rotations\n\n");
		sb.append("Kestrel is home to twelve permanent crew: a station chief, two power engineers, three biologists, two ")
			.append("medics, a systems technician, and three general operators. Crew serve ninety-day rotations, after which a ")
			.append("relief team arrives aboard the surface tender and a saturation-diving transfer bell. Overlap between the ")
			.append("outgoing and incoming teams lasts three days to hand over ongoing experiments and maintenance logs. Morale ")
			.append("is managed with a strict day-night lighting cycle, a shared galley, and scheduled contact with families ")
			.append("through the acoustic link.\n\n");

		sb.append("## Emergency Protocols\n\n");
		sb.append("If the hull integrity monitor detects a breach, the affected module is automatically isolated by fast-acting ")
			.append("pressure doors and the crew musters at the escape trunk. Two free-ascent evacuation pods are kept pressurized ")
			.append("at all times; each carries six people and enough scrubber capacity for a slow, staged ascent to avoid ")
			.append("decompression injury. Fire is handled by flooding the affected space with an inert gas mixture rather than ")
			.append("water, because seawater intrusion at depth is more dangerous than the fire itself. Every drill is logged and ")
			.append("reviewed by the station chief and the surface operations director.\n\n");

		return sb.toString();
	}
}
