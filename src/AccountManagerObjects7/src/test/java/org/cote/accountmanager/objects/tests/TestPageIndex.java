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

		/// Content-specific retrieval targeting exactly ONE section (Power Systems / geothermal). The answer
		/// to "how is electricity generated" lives only in the Power Systems body ("geothermal turbine ...").
		String q = "How is the station's electricity generated?";
		List<BaseRecord> hits = PageIndexUtil.retrieve(doc, q, 5);
		reportRetrieval(hits, q);
		assertAllChunks(hits, "MARKDOWN");
		assertTrue("[MARKDOWN] retrieve returned no leaves — retrieval regressed (build/persist is correct; see [DIAG])",
			!hits.isEmpty());
		/// Top-ranked leaf must be the Power Systems passage, not merely any leaf.
		assertTopChunkContains(hits, "MARKDOWN", "geothermal", "turbine", "power");
		/// Real cosine ranking: the best-scored leaf outranks the worst-scored returned leaf.
		assertDescendingScores(hits, "MARKDOWN");
	}

	/* ------------------------------------------------------------------ media docs ------------------------------------------------------------------ */

	/// Prose PDF (AIME.pdf): an avant-garde Valentine's Day story about attending "The AI And Me" singles
	/// event where your date is your own AI assistant / phone. No markdown headers → the NEW LLM-TOC path
	/// must synthesize a real section hierarchy. Content-specific retrieval targets the one passage that
	/// restricts enabling the AI assistant's "share nearby" mode.
	@Test
	public void TestPageIndexAimePdf() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== TestPageIndex AIME.pdf (prose PDF; LLM-TOC hierarchy + content retrieval) ===");

		BaseRecord testUser = pageIndexTestUser();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");
		ensureAzureContentAnalysis(octx, testUser);

		MediaBuild mb = buildMediaDoc(testUser, "./media/AIME.pdf", "AIME");

		/// (2) Real LLM-TOC hierarchy on a prose doc: a genuine tree, not flat.
		assertTrue("[AIME] expected the LLM-TOC path to produce >=1 SECTION node (got " + mb.stats.sections
			+ ") — prose hierarchy did not build", mb.stats.sections > 0);
		assertTrue("[AIME] expected tree depth maxLevel >= 2 (root->section->chunk), got " + mb.stats.maxLevel,
			mb.stats.maxLevel >= 2);

		/// (1) Content-specific retrieval: the answer ("enabling ... share nearby mode is strongly discouraged,
		/// nay, restricted, and will lead to being excused from the event") lives in ONE passage. The query
		/// targets that unique restriction/"excused" concept (avoiding the generic "singles event" phrase that
		/// collides with the event-intro chunk).
		String q = "Are attendees allowed to enable their AI assistant's share nearby mode?";
		List<BaseRecord> hits = retrieveReport(mb.doc, "AIME", q);
		assertTopChunkContains(hits, "AIME", "share nearby", "nearby");
		assertDescendingScores(hits, "AIME");
	}

	/// Prose DOCX (Vore.docx): a dystopian story set in the Lawrencium Arcology; its distinctive concept is
	/// the "cybervore" (a human overruled by cybernetic implants that consumes implants). Its extracted prose
	/// exceeds one LLM-TOC group (PAGEINDEX_TOC_GROUP_CHARS=16000), so this case exercises MULTI-GROUP LLM-TOC
	/// live and asserts the resulting multi-group tree is still one connected ROOT with resolvable parents.
	@Test
	public void TestPageIndexVoreDocx() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== TestPageIndex Vore.docx (multi-group prose LLM-TOC + content retrieval) ===");

		BaseRecord testUser = pageIndexTestUser();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");
		ensureAzureContentAnalysis(octx, testUser);

		MediaBuild mb = buildMediaDoc(testUser, "./media/Vore.docx", "VORE");

		/// (3) Multi-group LLM-TOC executed live: the extracted text must exceed one TOC group so
		/// splitIntoGroups produced >1 group. This closes the "multi-group not executed live" gap.
		int expectedGroups = expectedTocGroups(mb.extractedLen);
		logger.info("[VORE] extractedLen=" + mb.extractedLen + " PAGEINDEX_TOC_GROUP_CHARS="
			+ PageIndexUtil.PAGEINDEX_TOC_GROUP_CHARS + " overlap=" + PageIndexUtil.PAGEINDEX_TOC_GROUP_OVERLAP
			+ " => computed LLM-TOC groups=" + expectedGroups);
		assertTrue("[VORE] extracted text (" + mb.extractedLen + " chars) must exceed one TOC group ("
			+ PageIndexUtil.PAGEINDEX_TOC_GROUP_CHARS + ") to genuinely exercise multi-group LLM-TOC",
			mb.extractedLen > PageIndexUtil.PAGEINDEX_TOC_GROUP_CHARS);
		assertTrue("[VORE] expected multi-group (>1) TOC processing, computed " + expectedGroups, expectedGroups > 1);

		/// (2) A valid connected tree with real hierarchy (buildAndInspect already asserted single ROOT +
		/// parentId resolution + leaf offsets/content).
		assertTrue("[VORE] expected the LLM-TOC path to produce >=1 SECTION node (got " + mb.stats.sections + ")",
			mb.stats.sections > 0);
		assertTrue("[VORE] expected tree depth maxLevel >= 2, got " + mb.stats.maxLevel, mb.stats.maxLevel >= 2);

		/// (1) Content-specific retrieval: the "cybervore" definition passage.
		String q = "What is a cybervore and what does it consume?";
		List<BaseRecord> hits = retrieveReport(mb.doc, "VORE", q);
		assertTopChunkContains(hits, "VORE", "cybervore");
		assertDescendingScores(hits, "VORE");
	}

	/// The Verse.docx (~407K chars) is very expensive: many LLM-TOC groups + ~1200 embeddings (~7min+). Gate
	/// it behind an explicit heavy flag so the default PAGEINDEX_LLM run stays reasonable. Enable with
	/// PAGEINDEX_HEAVY=1 (in addition to PAGEINDEX_LLM=1).
	@Test
	public void TestPageIndexVerseDocx() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		assumeTrue("PAGEINDEX_HEAVY not set — skipping the expensive The Verse.docx case", heavyEnabled());
		logger.info("=== TestPageIndex The Verse.docx (HEAVY: multi-group prose LLM-TOC + content retrieval) ===");

		BaseRecord testUser = pageIndexTestUser();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");
		ensureAzureContentAnalysis(octx, testUser);

		MediaBuild mb = buildMediaDoc(testUser, "./media/The Verse.docx", "VERSE");

		assertTrue("[VERSE] expected the LLM-TOC path to produce >=1 SECTION node (got " + mb.stats.sections + ")",
			mb.stats.sections > 0);
		assertTrue("[VERSE] expected tree depth maxLevel >= 2, got " + mb.stats.maxLevel, mb.stats.maxLevel >= 2);

		String q = "Who is Mark Lucean?";
		List<BaseRecord> hits = retrieveReport(mb.doc, "VERSE", q);
		assertTopChunkContains(hits, "VERSE", "lucean", "mark");
		assertDescendingScores(hits, "VERSE");
	}

	/// (4) Fallback-to-flat path exercised LIVE (not inspection-only). Point the 'contentAnalysis' chat config
	/// at an unreachable (connection-refused) endpoint so callChat fails for every TOC group and summarization
	/// call: buildLlmTocTree returns false and the build degrades to the flat ROOT->CHUNK tree WITHOUT hard
	/// failing. Asserts a valid flat tree (one ROOT, CHUNK leaves, zero SECTION, maxLevel 1).
	@Test
	public void TestPageIndexFlatFallbackWhenLlmTocUnavailable() {
		assumeTrue("PAGEINDEX_LLM not set — skipping live LLM PageIndex test", llmEnabled());
		logger.info("=== TestPageIndex flat-fallback (LLM-TOC forced to fail via dead chat connection) ===");

		BaseRecord testUser = pageIndexTestUser();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");

		BaseRecord dead = pointContentAnalysisAtDead(octx, testUser);
		assertNotNull("[FALLBACK] contentAnalysis config did not resolve after repointing at dead connection", dead);

		String prose = buildProseDoc();
		assertTrue("[FALLBACK] prose fixture too short to produce multiple leaf chunks", prose.length() > 1000);
		BaseRecord doc = getCreateData(testUser, "Fallback-Prose.txt", "text/plain", prose.getBytes(), "~/PageIndex", octx.getOrganizationId());
		assertNotNull("[FALLBACK] source doc is null", doc);

		PageIndexStats stats = buildAndInspect(testUser, doc, "FALLBACK");

		assertTrue("[FALLBACK] expected exactly one ROOT (got " + stats.roots + ")", stats.roots == 1);
		assertTrue("[FALLBACK] expected multiple CHUNK leaves in the flat tree (got " + stats.chunks + ")", stats.chunks > 1);
		assertTrue("[FALLBACK] expected ZERO SECTION nodes (flat tree) but the LLM-TOC path produced "
			+ stats.sections + " — the dead connection did NOT force the flat fallback", stats.sections == 0);
		assertTrue("[FALLBACK] flat tree should be shallow (root->chunk => maxLevel 1), got maxLevel="
			+ stats.maxLevel, stats.maxLevel == 1);
		logger.info("[FALLBACK] flat ROOT->CHUNK tree built without hard-fail: ROOT=" + stats.roots
			+ " SECTION=" + stats.sections + " CHUNK=" + stats.chunks + " maxLevel=" + stats.maxLevel);
	}

	/* ---------------------------------------------------------- media build + retrieval helpers ---------------------------------------------------------- */

	private boolean heavyEnabled() {
		return System.getenv("PAGEINDEX_HEAVY") != null;
	}

	private BaseRecord pageIndexTestUser() {
		Factory mf = ioContext.getFactory();
		OrganizationContext octx = getTestOrganization("/Development/PageIndex");
		BaseRecord testUser = mf.getCreateUser(octx.getAdminUser(), "testUser1", octx.getOrganizationId());
		assertNotNull("Test user is null", testUser);
		return testUser;
	}

	/// Holder for a built media index: the source doc, its shape stats, and the extracted-text length.
	private static class MediaBuild {
		BaseRecord doc;
		PageIndexStats stats;
		long extractedLen;
	}

	/// Extract + store the media doc, build the index through AccessPoint.pageIndex, inspect shared invariants,
	/// and report the actual split path taken (LLM-TOC hierarchy vs flat fallback). Returns shape stats.
	private MediaBuild buildMediaDoc(BaseRecord testUser, String path, String label) {
		File f = new File(path);
		assumeTrue("Media file not present: " + path, f.exists());

		/// Honesty check: report extraction result up front.
		String extracted = path.endsWith(".pdf") ? DocumentUtil.readPDF(path) : DocumentUtil.readDocument(path);
		long extractedLen = (extracted != null ? extracted.length() : 0);
		logger.info("[MEDIA][" + label + "] extracted text length=" + extractedLen);
		if(extracted == null || extracted.trim().length() == 0) {
			logger.error("[MEDIA][" + label + "] FINDING: DocumentUtil failed to extract any text from " + path);
		}
		assertTrue("[MEDIA][" + label + "] no text extracted from " + path + " — genuine extraction failure", extractedLen > 0);

		/// getCreateDocument extracts + stores as text/plain data (no markdown headers survive → LLM-TOC path).
		BaseRecord doc = getCreateDocument(testUser, path);
		assertNotNull("Source doc is null for " + path, doc);

		long t0 = System.currentTimeMillis();
		PageIndexStats stats = buildAndInspect(testUser, doc, label + " " + f.getName());
		long buildMs = System.currentTimeMillis() - t0;
		logger.info("[MEDIA][" + label + "] build+persist+embed elapsed=" + buildMs + "ms");

		assertTrue("[MEDIA][" + label + "] expected exactly one ROOT (got " + stats.roots + ")", stats.roots == 1);
		assertTrue("[MEDIA][" + label + "] expected >=1 CHUNK leaf (got " + stats.chunks + ")", stats.chunks >= 1);

		/// Correct path-taken reporting (fixes the prior mislabeled '#' header-line log). Extracted PDF/DOCX
		/// prose has NO ATX markdown headers, so a hierarchy here is the LLM-TOC path; zero SECTION is the flat
		/// fallback. It is never the markdown-header path for these docs.
		String pathTaken = (stats.sections > 0
			? "LLM-TOC hierarchy (prose split synthesized " + stats.sections + " SECTION node(s), maxLevel " + stats.maxLevel + ")"
			: "FLAT ROOT->CHUNK fallback (LLM-TOC produced no usable sections)");
		logger.info("[MEDIA][" + label + "] tree shape: ROOT=" + stats.roots + " SECTION=" + stats.sections
			+ " CHUNK=" + stats.chunks + " maxLevel=" + stats.maxLevel + "  => " + pathTaken);

		MediaBuild mb = new MediaBuild();
		mb.doc = doc;
		mb.stats = stats;
		mb.extractedLen = extractedLen;
		return mb;
	}

	/// Run retrieve(), log elapsed + hits, assert the shortlist is non-empty and all CHUNK leaves. Returns hits.
	private List<BaseRecord> retrieveReport(BaseRecord doc, String label, String query) {
		long r0 = System.currentTimeMillis();
		List<BaseRecord> hits = PageIndexUtil.retrieve(doc, query, 5);
		logger.info("[MEDIA][" + label + "] retrieve elapsed=" + (System.currentTimeMillis() - r0) + "ms");
		reportRetrieval(hits, query);
		assertAllChunks(hits, label);
		assertTrue("[" + label + "] retrieve returned no leaves — retrieval regressed (build/persist correct; see [DIAG])",
			!hits.isEmpty());
		return hits;
	}

	private void assertAllChunks(List<BaseRecord> hits, String label) {
		for(BaseRecord h : hits) {
			assertTrue("[" + label + "] retrieve returned a non-CHUNK node",
				h.getEnum(FieldNames.FIELD_NODE_TYPE) == PageIndexNodeEnumType.CHUNK);
		}
	}

	/// Relevance assertion: the TOP-ranked returned CHUNK must actually contain the expected answer text (any
	/// of the given key terms, case-insensitive) — not merely that some leaf came back.
	private void assertTopChunkContains(List<BaseRecord> hits, String label, String... terms) {
		assertTrue("[" + label + "] retrieve returned no leaves — cannot assert relevance", !hits.isEmpty());
		BaseRecord top = hits.get(0);
		String content = top.get(FieldNames.FIELD_CONTENT);
		String lc = (content == null ? "" : content.toLowerCase());
		boolean match = false;
		StringBuilder ts = new StringBuilder();
		for(String t : terms) {
			ts.append(" \"").append(t).append("\"");
			if(t != null && lc.contains(t.toLowerCase())) {
				match = true;
			}
		}
		String snip = (content == null ? "(null)" : content.substring(0, Math.min(300, content.length())).replaceAll("\\s+", " "));
		logger.info("[RETRIEVE][" + label + "] top chunk (score=" + scoreOf(top) + ") vs expected term(s)" + ts + " => matched=" + match);
		logger.info("[RETRIEVE][" + label + "] top chunk text: " + snip);
		assertTrue("[" + label + "] top-ranked CHUNK does not contain any expected term" + ts
			+ " — retrieval returned an irrelevant passage. Top chunk was: \"" + snip + "\"", match);
	}

	/// Real descending order: with >=2 results the best-scored leaf must strictly outrank the worst-scored
	/// returned leaf (scores are the ephemeral cosine doubles set during descent), proving ranking is not
	/// degenerate/all-equal.
	private void assertDescendingScores(List<BaseRecord> hits, String label) {
		if(hits.size() < 2) {
			logger.info("[RETRIEVE][" + label + "] only " + hits.size() + " result(s); descending-score assertion N/A");
			return;
		}
		double first = scoreOf(hits.get(0));
		double last = scoreOf(hits.get(hits.size() - 1));
		logger.info("[RETRIEVE][" + label + "] descending-score check: first=" + first + " > last=" + last);
		assertTrue("[" + label + "] returned scores are NOT in real descending order (first " + first
			+ " !> last " + last + ") — cosine ranking is degenerate/all-equal", first > last);
	}

	private double scoreOf(BaseRecord node) {
		Object s = node.get(FieldNames.FIELD_SCORE);
		return (s instanceof Double ? (Double) s : 0.0);
	}

	/// Mirror of PageIndexUtil.splitIntoGroups so the test can assert (and report) how many LLM-TOC groups a
	/// document of the given extracted length produces — the deterministic proof multi-group code ran.
	private int expectedTocGroups(long len) {
		int group = PageIndexUtil.PAGEINDEX_TOC_GROUP_CHARS;
		int overlap = PageIndexUtil.PAGEINDEX_TOC_GROUP_OVERLAP;
		if(len <= group) {
			return 1;
		}
		int groups = 0;
		long pos = 0;
		while(pos < len) {
			long end = Math.min(pos + group, len);
			groups++;
			if(end >= len) {
				break;
			}
			long nextPos = end - overlap;
			pos = (nextPos > pos ? nextPos : end);
		}
		return groups;
	}

	/// Point the shared 'contentAnalysis' chat config at an unreachable (connection-refused) endpoint so both
	/// the LLM-TOC extraction and summarization Chat calls fail fast, deterministically forcing the flat
	/// fallback. Mirrors ensureAzureContentAnalysis's create/update pattern but with a dead connection and no
	/// live proof. (Every other test re-points contentAnalysis at Azure at its start, so this does not leak.)
	private BaseRecord pointContentAnalysisAtDead(OrganizationContext octx, BaseRecord testUser) {
		BaseRecord adminUser = octx.getAdminUser();
		String model = testProperties.getProperty("test.llm.openai.model");
		String version = testProperties.getProperty("test.llm.openai.version");

		ChatLibraryUtil.getCreateChatConfigLibrary(adminUser);
		ChatLibraryUtil.getCreateConnectionLibrary(adminUser);

		/// Connection-refused target (localhost port 1) => callChat throws/errors immediately and returns null.
		BaseRecord deadConn = OlioTestUtil.getCreateConnection(testUser, "Dead PageIndex Conn", "http://127.0.0.1:1", "dead-key", 20);
		assertNotNull("[FALLBACK] dead connection could not be created", deadConn);

		BaseRecord cfg = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, LIB_CONTENT_ANALYSIS);
		try {
			if(cfg == null) {
				BaseRecord chatLibDir = ChatLibraryUtil.findLibraryDir(adminUser, ChatLibraryUtil.LIBRARY_CHAT_CONFIGS);
				assertNotNull("[FALLBACK] chat config library dir is null", chatLibDir);
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, chatLibDir.get("path"));
				plist.parameter(FieldNames.FIELD_NAME, LIB_CONTENT_ANALYSIS);
				cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, adminUser, null, plist);
				ChatUtil.applyChatConfigTemplate(cfg, LIB_CONTENT_ANALYSIS);
				cfg.set("connection", deadConn);
				cfg.set("serviceType", LLMServiceEnumType.OPENAI);
				cfg.set("apiVersion", version);
				cfg.set("model", model);
				cfg = IOSystem.getActiveContext().getAccessPoint().create(adminUser, cfg);
			}
			else {
				cfg.set("connection", deadConn);
				cfg.set("serviceType", LLMServiceEnumType.OPENAI);
				cfg.set("apiVersion", version);
				cfg.set("model", model);
				cfg = IOSystem.getActiveContext().getAccessPoint().update(adminUser, cfg);
			}
		}
		catch(Exception e) {
			logger.error("[FALLBACK] Failed to point contentAnalysis at dead connection", e);
		}

		BaseRecord resolved = ChatUtil.getLibraryConfig(testUser, OlioModelNames.MODEL_CHAT_CONFIG, LIB_CONTENT_ANALYSIS);
		logger.info("[FALLBACK] contentAnalysis repointed at dead connection: url="
			+ describeConnectionUrl(testUser, resolved != null ? resolved.get("connection") : null));
		return resolved;
	}

	/// Plain prose (NO markdown headers) for the fallback test: several paragraphs so the flat tree has
	/// multiple leaf chunks and the ROOT summary exercises the excerpt fallback.
	private String buildProseDoc() {
		StringBuilder sb = new StringBuilder();
		sb.append("The lighthouse keeper woke before dawn, as he had every morning for thirty years. ")
			.append("He climbed the spiral stair, checked the great lamp, and wiped the salt film from the glass. ")
			.append("The sea beyond the rocks was grey and restless, and a low fog pressed against the shore. ")
			.append("He noted the wind in his logbook and set a kettle to boil on the small iron stove.\n\n");
		sb.append("By midmorning a fishing boat limped into the cove with a torn sail and a cracked rudder. ")
			.append("The keeper rowed out to meet it and towed the two exhausted fishermen back to the pier. ")
			.append("He gave them dry blankets and hot broth and listened while they described the sudden squall ")
			.append("that had caught them far past the headland where the charts warned of shifting sandbars.\n\n");
		sb.append("In the afternoon the keeper repaired the boat's rudder with spare oak from the boathouse. ")
			.append("He explained that the currents near the headland changed with the season and that no map ")
			.append("could be fully trusted after a storm. The younger fisherman wrote down every word, ")
			.append("determined never to be caught unprepared by the treacherous water again.\n\n");
		sb.append("That night the fog lifted and the stars burned cold and clear above the tower. ")
			.append("The keeper lit the lamp, watched its beam sweep the black water, and felt the old ")
			.append("satisfaction of a light held steady against the dark. The fishermen slept soundly, ")
			.append("and in the morning they sailed home with a mended boat and a hard-won lesson.\n\n");
		return sb.toString();
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
