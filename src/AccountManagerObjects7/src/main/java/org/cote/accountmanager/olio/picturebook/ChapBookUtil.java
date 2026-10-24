package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PictureBookProgressNotifier;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;

/**
 * ChapBook utility — poem-library management and ChapBook (poetry picture book) creation.
 * <p>
 * A ChapBook is an {@code olio.pb.book} with {@code bookType=CHAPBOOK}: one {@code olio.pb.scene}
 * per stanza chunk across all selected poems, each scene carrying the stanza text in
 * {@code poemStanza} and a landscape SD prompt derived from the stanza's imagery and mood.
 * <p>
 * This class owns only: poem chunking, LLM theme-analysis of {@code olio.cb.poem} records,
 * scene-per-stanza creation, and the top-level {@link #createChapBook} orchestration.
 * The PB2 book creation (world, universe, groups, grants) is entirely delegated to
 * {@link PbBookUtil#createBook}, which already handles all of that.
 */
public class ChapBookUtil {
	private static final Logger logger = LogManager.getLogger(ChapBookUtil.class);

	private ChapBookUtil() {
		/// static utility
	}

	// ─────────────────────────────── render result types ───────────────────────────────

	/**
	 * Outcome of a single ChapBook scene render.
	 * <ul>
	 *   <li>{@code RENDERED} — an image was generated and its objectId persisted onto the scene.</li>
	 *   <li>{@code SKIPPED_NO_PROMPT} — the scene was <b>un-prompted</b> (no genuine LLM landscape prompt
	 *       could be resolved), so no image was produced; the scene is deliberately left for explicit
	 *       regeneration later. This is NOT a failure — a fallback/stanza image is never rendered.</li>
	 *   <li>{@code FAILED} — a genuine prompt existed but SD returned no image, the persist patch failed,
	 *       or an exception was thrown.</li>
	 *   <li>{@code PENDING} — v3 path only: the page's {@code pb.render} job was submitted but had not
	 *       reached a terminal state within the synchronous wait bound; the job keeps running and the
	 *       result carries its {@code jobId} / {@code runObjectId} / {@code nodeObjectId} for polling.</li>
	 * </ul>
	 */
	public enum SceneRenderStatus { RENDERED, SKIPPED_NO_PROMPT, FAILED, PENDING }

	/**
	 * Immutable result of a single scene render. {@link #imageObjectId} is non-null only when
	 * {@link #status} is {@link SceneRenderStatus#RENDERED}; it is null for both {@code SKIPPED_NO_PROMPT}
	 * and {@code FAILED}.
	 * <p>
	 * The v3 fields ({@link #renderPath} onward) are populated only when the book is on the Phase 1 graph
	 * path ({@code chapbook.renderPath=v3}, {@link PbRenderPath}); on the v1 path they are null / false.
	 */
	public static final class SceneRenderResult {
		/** The generated image {@code objectId}; non-null only when {@link #status} is {@code RENDERED}. */
		public final String imageObjectId;
		/** The render outcome. */
		public final SceneRenderStatus status;
		/** {@code "v1"} or {@code "v3"} - which render path produced this result. */
		public final String renderPath;
		/** v3: the {@code pb.render} job id. */
		public final String jobId;
		/** v3: the {@code olio.pb.run} objectId. */
		public final String runObjectId;
		/** v3: the page's LANDSCAPE node objectId. */
		public final String nodeObjectId;
		/** v3: the selected {@code olio.pb.artifact} objectId; null when nothing was recorded. */
		public final String artifactObjectId;
		/** v3: the node's status name after the job ({@code DONE_UNVERIFIED}, {@code FAILED}, ...). */
		public final String nodeStatus;
		/** v3: the selected artifact's review status name ({@code GENERATED}, {@code APPROVED}, ...). */
		public final String reviewStatus;
		/** v3: true when the book's auto-approve switch approved and pinned the new artifact. */
		public final boolean autoApproved;
		/** v3: why auto-approve did not apply ({@code "off"}, a degradation, ...); null when it did. */
		public final String autoApproveSkipReason;
		/**
		 * True when a HARD LLM/config failure occurred during THIS scene's landscape-prompt step —
		 * i.e. the LLM genuinely could not be used (missing template/config, unreachable host, null
		 * response), as distinct from a SOFT decline (the LLM ran but produced no usable content for
		 * this stanza — a normal per-content outcome). This is the truthful "the LLM was unavailable"
		 * signal the render path previously swallowed. Deliberately NOT set for a soft decline, nor
		 * for the deliberate no-LLM ({@code chatConfig == null}) path where no call was attempted.
		 */
		public final boolean llmUnavailable;
		/**
		 * True when the scene STILL rendered, but on the STORED prompt because the LLM recovery
		 * hard-failed — i.e. rendered WITHOUT a fresh LLM prompt. Lets the client offer a re-render
		 * once the LLM recovers, rather than treating the degraded image as final.
		 */
		public final boolean llmDegraded;

		public SceneRenderResult(String imageObjectId, SceneRenderStatus status) {
			this(imageObjectId, status, false, false);
		}

		public SceneRenderResult(String imageObjectId, SceneRenderStatus status, boolean llmUnavailable, boolean llmDegraded) {
			this(imageObjectId, status, llmUnavailable, llmDegraded, PbRenderPath.V1, null, null, null, null, null, null, false, null);
		}

		/** The v3 result: every field. {@code renderPath} is {@link PbRenderPath#V3}. */
		public SceneRenderResult(String imageObjectId, SceneRenderStatus status, boolean llmUnavailable, boolean llmDegraded,
				String renderPath, String jobId, String runObjectId, String nodeObjectId, String artifactObjectId,
				String nodeStatus, String reviewStatus, boolean autoApproved, String autoApproveSkipReason) {
			this.imageObjectId = imageObjectId;
			this.status = status;
			this.llmUnavailable = llmUnavailable;
			this.llmDegraded = llmDegraded;
			this.renderPath = renderPath;
			this.jobId = jobId;
			this.runObjectId = runObjectId;
			this.nodeObjectId = nodeObjectId;
			this.artifactObjectId = artifactObjectId;
			this.nodeStatus = nodeStatus;
			this.reviewStatus = reviewStatus;
			this.autoApproved = autoApproved;
			this.autoApproveSkipReason = autoApproveSkipReason;
		}
	}

	/**
	 * Aggregate outcome of a bulk {@link #renderChapBookSummary} over every scene of a ChapBook.
	 * <ul>
	 *   <li>{@link #rendered} — scenes for which an image was produced (includes degrade-renders).</li>
	 *   <li>{@link #skipped} — scenes left un-prompted (no image).</li>
	 *   <li>{@link #llmUnavailable} — scenes whose landscape-prompt step HARD-failed (config/infra),
	 *       i.e. the count of scenes where the LLM was genuinely unavailable. This is the truthful
	 *       "the LLM was down for N scenes" signal the bulk render previously swallowed.</li>
	 * </ul>
	 * The v3 fields ({@link #renderPath} onward) are populated only on the Phase 1 graph path; there
	 * {@link #pending} means the single {@code pb.renderAll} job was submitted and is still running (the
	 * caller detached, or the synchronous wait bound elapsed) and the counts describe only the pre-step.
	 */
	public static final class ChapBookRenderSummary {
		public final int rendered;
		public final int skipped;
		public final int llmUnavailable;
		/** {@code "v1"} or {@code "v3"}. */
		public final String renderPath;
		/** v3: the {@code pb.renderAll} job id; null when no job was submitted (nothing to render). */
		public final String jobId;
		/** v3: the {@code olio.pb.run} objectId. */
		public final String runObjectId;
		/** v3: pages whose node FAILED in the run. */
		public final int failed;
		/** v3: true while the job is still running - see the class javadoc. */
		public final boolean pending;
		/** v3: how many page nodes the run planned (pinned pages are left out). */
		public final int plannedNodeCount;

		public ChapBookRenderSummary(int rendered, int skipped, int llmUnavailable) {
			this(rendered, skipped, llmUnavailable, PbRenderPath.V1, null, null, 0, false, 0);
		}

		public ChapBookRenderSummary(int rendered, int skipped, int llmUnavailable, String renderPath,
				String jobId, String runObjectId, int failed, boolean pending, int plannedNodeCount) {
			this.rendered = rendered;
			this.skipped = skipped;
			this.llmUnavailable = llmUnavailable;
			this.renderPath = renderPath;
			this.jobId = jobId;
			this.runObjectId = runObjectId;
			this.failed = failed;
			this.pending = pending;
			this.plannedNodeCount = plannedNodeCount;
		}
	}

	/**
	 * Discriminator for "is this stored {@code sdPrompt} a GENUINE LLM landscape prompt vs. the create-time
	 * no-LLM fallback?" — used by the create path (whether the LLM's OWN output is stored), the render
	 * path's fallback branch, and the bulk forward-threading. A stored prompt is genuine only when it is
	 * {@link NarrativeUtil#isMeaningful(String) meaningful} (so an LLM-emitted literal
	 * {@code "null"}/{@code "none"}/{@code "n/a"}/{@code "unknown"} is rejected) AND is not the
	 * {@code "landscape, "} no-LLM fallback shape.
	 * <p>
	 * <b>Scope note:</b> this is a heuristic about the create-time fallback ONLY. It no longer decides a
	 * human edit's provenance — that is now carried EXPLICITLY by the persisted
	 * {@link OlioFieldNames#FIELD_PB_PROMPT_LOCKED promptLocked} marker
	 * ({@link #setSceneLandscapePrompt}). A human edit shaped like the {@code "landscape, "} fallback is
	 * honored verbatim by {@link #resolveScenePrompt}'s promptLocked branch and never reaches this
	 * text-shape check, so a deliberate edit can no longer be misclassified as the fallback and
	 * regenerated (the bug this fix removes).
	 */
	public static boolean isGenuineStoredPrompt(String p) {
		return NarrativeUtil.isMeaningful(p) && !p.trim().startsWith("landscape, ");
	}

	// ─────────────────────────────── poem text extraction ───────────────────────────────

	/**
	 * Upper bound on the number of characters extracted from an uploaded document. A finite cap
	 * prevents a crafted/oversized upload from exhausting heap during Tika extraction (Tika's own
	 * default is unbounded); 16M characters is generous for any reasonable poem/chapbook.
	 */
	private static final int MAX_EXTRACT_CHARS = 16 * 1024 * 1024;

	/**
	 * Extract readable, sanitized poem text from a {@code data.data} byteStore record.
	 * <ul>
	 *   <li>{@code text/*}, null, or empty content type — read directly as UTF-8 (backwards
	 *       compatible with existing plain-text imports).</li>
	 *   <li>Binary office formats (.doc/.docx/.rtf/.wpd) — extract plain text via Apache Tika
	 *       (legacy .doc via POI HWPF). The declared content type is corrected from the container
	 *       magic first, so a WordPerfect file misnamed ".DOC" still routes to the right parser.</li>
	 *   <li>Any other binary type — {@link PictureBookException} with status 400 so the transport
	 *       layer can return HTTP 400.</li>
	 * </ul>
	 * The returned text is always run through {@link #sanitizeText(String)}.
	 *
	 * @param data a {@code data.data} record with its byteStore populated
	 * @return sanitized text, or null if the source has no content
	 * @throws PictureBookException (status 400) for unsupported content types or extraction failure
	 */
	public static String extractPoemText(BaseRecord data) throws PictureBookException {
		String contentType = data.get(FieldNames.FIELD_CONTENT_TYPE);
		String ct = (contentType == null) ? null : contentType.trim().toLowerCase();

		try {
			byte[] bytes = ByteModelUtil.getValue(data);
			if (bytes == null || bytes.length == 0) return null;

			// A legacy .doc (or .docx) uploaded through a generic data/note path often arrives with
			// a missing or plain-text content type. Trusting that label reads the OLE2/ZIP binary
			// container as raw UTF-8 and yields binary garbage, so sniff the container magic and
			// override the declared type before deciding how to extract.
			//
			// The declared type is ALSO overridden when it simply disagrees with the container
			// magic — a missing/plain-text label is not the only way to arrive here wrong. Real
			// WordPerfect files are routinely named ".DOC", so ContentTypeUtil.getTypeFromExtension
			// reports application/msword, readDocument hands them to POI HWPF, and HWPF cannot read
			// a non-OLE2 container, so extraction returns null and this method throws a 400.
			// Measured over a 196-file corpus: 113 WordPerfect files, 81 of them named ".DOC";
			// 5NIGHTS.DOC yielded null as application/msword and 10,861 characters once corrected.
			// Magic bytes are evidence, a file extension is a guess — so the magic wins.
			//
			// Safe for the previously-working cases: a correctly-labelled .doc sniffs as OLE2 and a
			// correctly-labelled .docx as ZIP, both agreeing with the declared type, and formats
			// with no container magic here (PDF, RTF, plain text) sniff as null and keep their
			// declared type. Note OLE2 magic is shared by .doc/.xls/.ppt, so a mislabelled
			// spreadsheet resolves to application/msword and fails extraction rather than being
			// rejected as an unsupported type — both outcomes are a 400, only the message differs.
			String effectiveCt = ct;
			String sniffed = DocumentUtil.sniffOfficeContentType(bytes);
			if (sniffed != null && !sniffed.equalsIgnoreCase(effectiveCt)) {
				if (effectiveCt != null && !effectiveCt.isEmpty() && !effectiveCt.startsWith("text/")) {
					logger.info("Container magic (" + sniffed + ") overrides declared content type ("
						+ effectiveCt + ") for " + data.get(FieldNames.FIELD_NAME));
				}
				effectiveCt = sniffed;
			}

			if (effectiveCt == null || effectiveCt.isEmpty() || effectiveCt.startsWith("text/")) {
				return sanitizeText(ByteModelUtil.getValueString(data));
			}
			if (DocumentUtil.OFFICE_CONTENT_TYPES.contains(effectiveCt)) {
				// Pass the resolved content type so the 3-arg overload routes .doc to POI
				// and provides a Tika content-type hint for .docx and WordPerfect.
				// Bounded extraction (MAX_EXTRACT_CHARS) — do not let Tika accumulate unbounded output.
				String extracted = DocumentUtil.readDocument(bytes, MAX_EXTRACT_CHARS, effectiveCt);
				if (extracted == null) {
					throw new PictureBookException(400,
						"Failed to extract text from document (" + contentType + ")");
				}
				return sanitizeText(extracted);
			}
		} catch (ValueException | FieldException e) {
			throw new PictureBookException(400,
				"Failed to read document content (" + contentType + "): " + e.getMessage());
		}
		throw new PictureBookException(400,
			"Unsupported content type '" + contentType + "' — only text and common document "
			+ "formats (.doc, .docx, .rtf, .wpd) are supported");
	}

	/**
	 * Sanitize raw extracted text before persisting it as a poem.
	 * <p>
	 * Handles text extracted from DOCX, DOC, RTF, and other binary-adjacent formats that
	 * {@link ByteModelUtil#getValueString} may return with embedded null bytes, control
	 * characters, or Windows-style CRLF line endings — all of which PostgreSQL may reject
	 * with {@code ERROR: invalid byte sequence for encoding "UTF8"} or similar.
	 * <ul>
	 *   <li>Null bytes (U+0000) are stripped entirely — PostgreSQL rejects them regardless of
	 *       encoding.</li>
	 *   <li>C0 control characters (U+0001–U+001F) other than horizontal tab ({@code \t}),
	 *       newline ({@code \n}), and carriage return ({@code \r}) are stripped.</li>
	 *   <li>CRLF ({@code \r\n}) and bare {@code \r} are normalised to {@code \n}.</li>
	 * </ul>
	 *
	 * @param raw the raw string to sanitize; may be null
	 * @return sanitized string, or null if the result is null or blank after sanitization
	 */
	public static String sanitizeText(String raw) {
		if (raw == null) return null;
		// 1. Strip null bytes — PostgreSQL rejects U+0000 unconditionally.
		String s = raw.replace("\u0000", "");
		// 2. Strip C0 control characters except horizontal tab, LF, and CR.
		s = s.replaceAll("[\\p{Cntrl}&&[^\t\n\r]]", "");
		// 3. Normalize line endings to LF only.
		s = s.replace("\r\n", "\n").replace("\r", "\n");
		return s.isBlank() ? null : s;
	}

	// ─────────────────────────────── poem chunking ───────────────────────────────

	/**
	 * Split poem text into stanza chunks, each at most {@code maxLinesPerPage} lines.
	 * <p>
	 * Algorithm: split on one or more blank lines; if a stanza exceeds the limit, split
	 * further at the limit boundary.
	 *
	 * @param poemText       raw poem text
	 * @param maxLinesPerPage maximum lines per output chunk; 8 is the recommended default
	 * @return list of non-empty stanza strings
	 */
	public static List<String> chunkPoem(String poemText, int maxLinesPerPage) {
		List<String> chunks = new ArrayList<>();
		if (poemText == null || poemText.isBlank()) return chunks;
		// Split on one OR MORE consecutive blank lines — handles Tika's DOCX extraction
		// where each paragraph ends with \n\n, producing single-line "stanzas".
		String[] stanzas = poemText.split("(?m)(^\\s*$\\n?)+");
		List<String> realStanzas = new ArrayList<>();
		for (String s : stanzas) {
			String trimmed = s.trim();
			if (!trimmed.isEmpty()) realStanzas.add(trimmed);
		}
		if (realStanzas.isEmpty()) return chunks;

		// Strip a leading header line of the form "Title  by Author  (year)" — the
		// corpus format and Tika-extracted .docx headers both produce exactly this
		// pattern as the first single-line chunk before the first real stanza.
		// A poem whose opening line genuinely begins with "By" (e.g. "By moonlight pale")
		// will NOT be stripped because " by " requires a preceding space.
		if (!realStanzas.isEmpty()) {
			String first = realStanzas.get(0);
			if (!first.contains("\n") && first.toLowerCase().contains(" by ")) {
				realStanzas.remove(0);
			}
		}
		if (realStanzas.isEmpty()) return chunks;

		// Detect the Tika "one paragraph = one line" artifact: if the majority of stanzas
		// are single-line and there are at least 3 of them, treat them as bare lines and
		// re-chunk by maxLinesPerPage rather than by stanza.
		long singleLine = realStanzas.stream().filter(s -> !s.contains("\n")).count();
		boolean linePerParagraph = realStanzas.size() >= 3 && singleLine * 2 > realStanzas.size();

		if (linePerParagraph) {
			// Flatten all single-line stanzas into one list and page by maxLinesPerPage
			for (int i = 0; i < realStanzas.size(); i += maxLinesPerPage) {
				int end = Math.min(i + maxLinesPerPage, realStanzas.size());
				chunks.add(String.join("\n", realStanzas.subList(i, end)));
			}
		} else {
			for (String stanza : realStanzas) {
				String[] lines = stanza.split("\\r?\\n");
				if (lines.length <= maxLinesPerPage) {
					chunks.add(stanza);
				} else {
					// Further split long stanzas by maxLinesPerPage
					List<String> lineList = new ArrayList<>();
					for (String line : lines) lineList.add(line);
					for (int i = 0; i < lineList.size(); i += maxLinesPerPage) {
						int end = Math.min(i + maxLinesPerPage, lineList.size());
						chunks.add(String.join("\n", lineList.subList(i, end)));
					}
				}
			}
		}
		return chunks;
	}

	// ─────────────────────────────── chatConfig resolution ───────────────────────────────

	/**
	 * Return the default {@code olio.llm.chatConfig} record for the acting user, preferring a specific
	 * <b>named</b> shared/system (library) config over an arbitrary one.
	 * <p>
	 * Resolution order (first non-null wins):
	 * <ol>
	 *   <li>The named shared-library config {@link ChatUtil#DEFAULT_ANALYSIS_CHAT_CONFIG_NAME}
	 *       ({@code "contentAnalysis"}) — the deterministic first-choice default for content analysis.</li>
	 *   <li>The named shared-library config {@link ChatUtil#DEFAULT_GENERAL_CHAT_CONFIG_NAME}
	 *       ({@code "generalChat"}) — a general-purpose second choice.</li>
	 *   <li>The FIRST config in the shared <b>ChatConfigs</b> library dir with no name filter
	 *       ({@link ChatUtil#getLibraryConfig(BaseRecord, String)}) — a last-resort shared default.</li>
	 *   <li>Only if no library config exists at all, the first config OWNED BY the acting user in their org.</li>
	 * </ol>
	 * Every step goes through {@code AccessPoint.find} (not {@code list}) so per-record PBAC authorization
	 * is enforced. The prior implementation asked for the no-name library overload first, which returns
	 * the FIRST library config non-deterministically (whatever the query happened to order first) — so
	 * two organizations with the same library could resolve to different configs. Resolving the named
	 * {@code contentAnalysis} then {@code generalChat} configs first makes the default deterministic.
	 *
	 * @param user the acting user (must be non-null and have a valid organizationId)
	 * @return the named/shared-library chatConfig if one exists, else the user's own, else null
	 */
	public static BaseRecord resolveDefaultChatConfig(BaseRecord user) {
		// 1. Prefer the named "contentAnalysis" shared-library config (deterministic first choice).
		BaseRecord named = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG,
			ChatUtil.DEFAULT_ANALYSIS_CHAT_CONFIG_NAME);
		if (named != null) {
			return named;
		}
		// 2. Then the named "generalChat" shared-library config (deterministic second choice).
		named = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG,
			ChatUtil.DEFAULT_GENERAL_CHAT_CONFIG_NAME);
		if (named != null) {
			return named;
		}
		// 3. Last-resort shared default: the first library config with no name filter (PBAC-gated).
		BaseRecord libraryConfig = ChatUtil.getLibraryConfig(user, OlioModelNames.MODEL_CHAT_CONFIG);
		if (libraryConfig != null) {
			return libraryConfig;
		}
		// 4. Fall back to a user-owned config only when no shared-library config exists.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		q.setContextUser(user);
		q.planMost(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── LLM theme analysis ───────────────────────────────

	/**
	 * Analyze a poem's theme, mood, and imagery keywords via LLM, then PATCH the result
	 * onto the {@code olio.cb.poem} record.
	 * <p>
	 * A null or empty poem text is logged and skipped — this is a best-effort enrichment,
	 * not a prerequisite for ChapBook creation.
	 *
	 * @param user       the acting user
	 * @param poem       the {@code olio.cb.poem} record (must have objectId, name, groupId,
	 *                   organizationId, and a text field)
	 * @param chatConfig the {@code olio.llm.chatConfig} to use; null = skip the LLM call
	 */
	public static void analyzePoemTheme(BaseRecord user, BaseRecord poem, BaseRecord chatConfig) {
		if (poem == null || chatConfig == null) {
			logger.warn("analyzePoemTheme: poem or chatConfig is null — skipping");
			return;
		}
		String poemText = poem.get("text");
		if (poemText == null || poemText.isBlank()) {
			logger.warn("analyzePoemTheme: poem has no text content — skipping");
			return;
		}
		try {
			Map<String, String> analysis = analyzeThemeMoodKeywords(user, chatConfig, poemText);
			if (analysis == null) {
				logger.warn("analyzePoemTheme: LLM returned no result");
				return;
			}
			String theme = analysis.get("theme");
			String mood = analysis.get("mood");
			String keywords = analysis.get("keywords");

			String poemObjectId = poem.get(FieldNames.FIELD_OBJECT_ID);
			long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM, FieldNames.FIELD_OBJECT_ID, poemObjectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID
			});
			q.setCache(false);
			BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			if (existing == null) {
				logger.warn("analyzePoemTheme: poem not found by objectId " + poemObjectId);
				return;
			}
			BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CB_POEM,
				new String[] {
					FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
					OlioFieldNames.FIELD_CB_THEME, OlioFieldNames.FIELD_CB_MOOD, OlioFieldNames.FIELD_CB_KEYWORDS
				});
			patch.set(FieldNames.FIELD_ID, existing.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, poemObjectId);
			patch.set(FieldNames.FIELD_NAME, existing.get(FieldNames.FIELD_NAME));
			patch.set(FieldNames.FIELD_GROUP_ID, existing.get(FieldNames.FIELD_GROUP_ID));
			patch.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			if (theme != null) patch.set(OlioFieldNames.FIELD_CB_THEME, theme);
			if (mood != null) patch.set(OlioFieldNames.FIELD_CB_MOOD, mood);
			if (keywords != null) patch.set(OlioFieldNames.FIELD_CB_KEYWORDS, keywords);
			if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.warn("analyzePoemTheme: failed to patch poem " + poemObjectId);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error("analyzePoemTheme field/value error: " + e.getMessage(), e);
		} catch (Exception e) {
			logger.error("analyzePoemTheme error: " + e.getMessage(), e);
		}
	}

	private static String guardNull(Object val) {
		if (val == null) return null;
		String s = val.toString().trim();
		if (s.isEmpty() || "null".equalsIgnoreCase(s) || "n/a".equalsIgnoreCase(s) || "unknown".equalsIgnoreCase(s)) return null;
		return s;
	}

	/**
	 * Shared LLM theme/mood/keywords analysis step used by BOTH {@link #analyzePoemTheme} (whole poem) and
	 * {@link #analyzeSceneTheme} (one scene's stanza): run the {@code chapBook.poem-analysis} template on a
	 * block of text and return the parsed {@code {theme, mood, keywords}} map.
	 * <p>
	 * Each value is passed through {@link #guardNull} so a literal LLM {@code "null"}/{@code "n/a"}/
	 * {@code "unknown"} becomes a real Java null rather than leaking downstream. An individual key may be
	 * null when the LLM omitted/declined it. Returns {@code null} (the whole map) only when the LLM
	 * produced no result at all — callers treat that as "no analysis available".
	 *
	 * @param user       the acting user
	 * @param chatConfig the resolved {@code olio.llm.chatConfig} (must be non-null)
	 * @param text       the text to analyze (poem body or a single stanza)
	 * @return a map with keys {@code theme}, {@code mood}, {@code keywords} (values possibly null), or
	 *         null when the LLM returned nothing usable
	 */
	private static Map<String, String> analyzeThemeMoodKeywords(BaseRecord user, BaseRecord chatConfig, String text) {
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("poemText", text);
		String llmResult = PictureBookUtil.callLlmForChapBook(user, chatConfig, "chapBook.poem-analysis", vars);
		if (llmResult == null || llmResult.isBlank()) {
			return null;
		}
		Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObjectForChapBook(llmResult, "poem-analysis", new ArrayList<>());
		Map<String, String> out = new LinkedHashMap<>();
		out.put("theme", guardNull(parsed.get("theme")));
		out.put("mood", guardNull(parsed.get("mood")));
		out.put("keywords", guardNull(parsed.get("keywords")));
		return out;
	}

	/**
	 * Analyze ONE ChapBook scene's {@code poemStanza} via the same LLM theme/mood analysis
	 * {@link #analyzePoemTheme} runs, then PATCH the result onto the {@code olio.pb.scene} record.
	 * <p>
	 * The scene model has a {@code mood} field but no {@code theme}/{@code keywords} fields, so only the
	 * analyzed {@code mood} is persisted (per the endpoint contract — no new scene fields are added). The
	 * scene is read with {@code poemStanza} + {@code mood} + identity + group projected (via
	 * {@link PbBookUtil#readScene}, whose {@code sceneRequest} already includes {@code poemStanza} — the
	 * default {@code olio.pb.scene} query fields omit it, the same lesson as the poem {@code text}
	 * projection in {@link #analyzePoemTheme}).
	 * <p>
	 * Persistence is PATCH-shaped and authorized AS THE ACTING USER: identity + the validated {@code name}
	 * + the changed {@code mood} field only (via {@link PbGraphUtil#patchOf}), through {@code AccessPoint
	 * .update}. The update result is asserted, never discarded.
	 *
	 * @param user          the acting user (must have WRITE access to the scene)
	 * @param sceneObjectId objectId of the {@code olio.pb.scene} to analyze
	 * @param chatConfig    the resolved {@code olio.llm.chatConfig} (the transport layer resolves the org
	 *                      default and returns 503 when none exists, mirroring {@link #analyzePoemTheme})
	 * @return the persisted mood string
	 * @throws PictureBookException 400 for missing args or a scene with no stanza; 404 when the scene is
	 *         not readable; 503 when {@code chatConfig} is null; 500 when the LLM produced no usable mood
	 *         or the patch failed
	 */
	public static String analyzeSceneTheme(BaseRecord user, String sceneObjectId, BaseRecord chatConfig) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		if (chatConfig == null) {
			throw new PictureBookException(503, "No chatConfig is configured for this organization");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, orgId);
		if (scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		String stanza = scene.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		if (stanza == null || stanza.isBlank()) {
			throw new PictureBookException(400, "Scene has no poemStanza to analyze: " + sceneObjectId);
		}
		Map<String, String> analysis = analyzeThemeMoodKeywords(user, chatConfig, stanza);
		String mood = (analysis != null) ? analysis.get("mood") : null;
		if (mood == null || mood.isBlank()) {
			throw new PictureBookException(500,
				"The analysis LLM did not produce a usable mood for scene " + sceneObjectId);
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
			OlioFieldNames.FIELD_PB_MOOD);
		try {
			patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
		}
		catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble an analyze patch: " + e.getMessage());
		}
		if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to persist analyzed mood on scene " + sceneObjectId);
		}
		return mood;
	}

	// ─────────────────────────────── poem creation ───────────────────────────────

	/**
	 * Create an {@code olio.cb.poem} record in the specified group path (default {@code ~/Poems}).
	 * The group is created if absent (via the factory path resolution). Returns the created
	 * record's identity fields only — callers that need the full record should do a subsequent find.
	 *
	 * @param user      the acting user
	 * @param title     poem title — also used as the {@code name} field (required)
	 * @param author    optional author attribution
	 * @param text      the full poem text (required)
	 * @param groupPath the directory path; null or blank defaults to {@code ~/Poems}
	 * @return the identity fields of the created record
	 * @throws PictureBookException 400 if required fields are absent, 500 on persistence failure
	 */
	public static BaseRecord createPoem(BaseRecord user, String title, String author, String text, String groupPath) {
		return createPoem(user, title, author, text, groupPath, null);
	}

	/**
	 * Create an {@code olio.cb.poem} record, optionally scoped to a chapbook.
	 * <p>
	 * When {@code book} is non-null the poem's {@code book} FK is stamped so the poem belongs to that
	 * chapbook (see {@link #listPoems}); when null the poem lands in the global poem library, which is
	 * the backward-compatible behavior of the 5-arg overload. A {@code foreign} {@code model} field
	 * takes the RECORD, not its id (see {@code model-api.md}); the caller resolves the book first via
	 * {@link #resolveScopeBook}.
	 *
	 * @param book optional {@code olio.pb.book} record to scope this poem to; null = global library
	 */
	public static BaseRecord createPoem(BaseRecord user, String title, String author, String text, String groupPath, BaseRecord book) {
		if (title == null || title.isBlank()) throw new PictureBookException(400, "title is required");
		if (text == null || text.isBlank()) throw new PictureBookException(400, "text is required");
		String effectivePath = (groupPath != null && !groupPath.isBlank()) ? groupPath : "~/Poems";
		try {
			org.cote.accountmanager.io.ParameterList plist = org.cote.accountmanager.io.ParameterList.newParameterList(
				FieldNames.FIELD_PATH, effectivePath);
			plist.parameter(FieldNames.FIELD_NAME, title);
			BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			if (poem == null) throw new PictureBookException(500, "Failed to instantiate poem record");
			poem.set(OlioFieldNames.FIELD_PB_TITLE, title);
			if (author != null && !author.isBlank()) poem.set("author", author);
			poem.set("text", text);
			// A foreign model field takes the RECORD, not its id (model-api.md). Stamp the chapbook
			// scope so listPoems(book) can filter to it; null leaves the poem in the global library.
			if (book != null) poem.set(OlioFieldNames.FIELD_PB_BOOK, book);
			// Use a direct write to bypass PBAC — the ~/Poems group was just created by
			// factory.newInstance → PathUtil.makePath → writer.write(group) and carries no
			// entitlements yet, so AccessPoint.create would fail PBAC (no DATA-Create grant).
			// This mirrors the pattern used by WorldUtil.getCreateWorld and other init paths.
			try {
				IOSystem.getActiveContext().getRecordUtil().createRecord(poem);
			} catch (Exception createEx) {
				// Possible UNIQUE constraint violation — look up existing poem by name in the group
				org.cote.accountmanager.io.Query fq = org.cote.accountmanager.io.QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM, FieldNames.FIELD_NAME, title);
				Object gid = poem.get(FieldNames.FIELD_GROUP_ID);
				if (gid instanceof Number && ((Number)gid).longValue() > 0L) fq.field(FieldNames.FIELD_GROUP_ID, ((Number)gid).longValue());
				fq.setCache(false);
				BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().find(user, fq);
				if (existing != null) return existing;
				throw new PictureBookException(500, "Failed to create poem in path " + effectivePath + ": " + createEx.getMessage());
			}
			// createRecord populates the id field in-place; treat poem itself as the created record
			BaseRecord created = (poem.get(FieldNames.FIELD_ID) != null && (long) poem.get(FieldNames.FIELD_ID) > 0L)
				? poem
				: null;
			if (created == null) throw new PictureBookException(500, "Failed to create poem in path " + effectivePath);
			return created;
		} catch (PictureBookException e) {
			throw e;
		} catch (Exception e) {
			throw new PictureBookException(500, "createPoem failed: " + e.getMessage());
		}
	}

	// ─────────────────────────────── ChapBook creation ───────────────────────────────

	/**
	 * Create an {@code olio.pb.book} (with {@code bookType=CHAPBOOK}) containing one scene per
	 * stanza chunk across all selected poems.
	 * <p>
	 * <ol>
	 * <li>Creates the PB2 base book via {@link PbBookUtil#createBook} (world, universe, groups, grants).</li>
	 * <li>Patches {@code bookType=CHAPBOOK} onto the book record.</li>
	 * <li>For each poem objectId: loads the poem, chunks it, creates one {@code olio.pb.scene} per chunk.</li>
	 * <li>Each scene carries the stanza text in {@code poemStanza} and the poem's title/mood/keywords
	 *     in its {@code title}/{@code mood} fields.</li>
	 * </ol>
	 *
	 * @param user            the acting user
	 * @param dataPath        the {@code datagen.path} init param used by {@link PbOlioContextUtil}
	 * @param slug            book slug (must match {@link PbOlioContextUtil#BOOK_SLUG_PATTERN})
	 * @param title           human-readable book title (stored as {@code name})
	 * @param poemObjectIds   ordered list of {@code olio.cb.poem} objectIds whose stanzas become scenes
	 * @param maxLinesPerPage maximum lines per stanza chunk; 8 when 0 or negative
	 * @param chatConfig      optional {@code olio.llm.chatConfig}; null means no LLM landscape prompts
	 * @return the created {@code olio.pb.book} record
	 */
	public static BaseRecord createChapBook(BaseRecord user, String dataPath, String slug, String title,
			List<String> poemObjectIds, int maxLinesPerPage, BaseRecord chatConfig) {
		return createChapBook(user, dataPath, slug, title, poemObjectIds, maxLinesPerPage, chatConfig, null);
	}

	/**
	 * Same as {@link #createChapBook(BaseRecord, String, String, String, List, int, BaseRecord)} plus
	 * an optional {@code cancelToken} for progress reporting and cooperative cancellation.
	 *
	 * <p>Needed because this is a long, LLM-bound bulk operation — one landscape-prompt call per
	 * stanza chunk — that previously ran to completion inside a single HTTP request with no way to
	 * observe it and no way to stop it. {@code ChapBookService} had no cancel or progress wiring at
	 * all, and ChapBook never rendered the background-activity indicator, so its existing server
	 * chirps went nowhere.
	 *
	 * <p>Cancelling is safe here for the same reason a 504 was survivable: each scene is persisted
	 * as it is created, so stopping early leaves a real book with fewer scenes rather than losing
	 * the work. The book record is returned either way.
	 */
	public static BaseRecord createChapBook(BaseRecord user, String dataPath, String slug, String title,
			List<String> poemObjectIds, int maxLinesPerPage, BaseRecord chatConfig,
			SummarizeProgress cancelToken) {
		if (slug == null || slug.isBlank()) throw new PictureBookException(400, "slug is required for ChapBook creation");
		if (title == null || title.isBlank()) throw new PictureBookException(400, "title is required for ChapBook creation");
		if (poemObjectIds == null || poemObjectIds.isEmpty()) throw new PictureBookException(400, "at least one poem is required");

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		int effectiveMax = (maxLinesPerPage > 0) ? maxLinesPerPage : 8;

		// Report a phase BEFORE createBook. The scene total is not knowable until the poems are read
		// (see the pre-pass below), but createBook builds the PB2 world, and on the first chapbook in
		// an organization that alone runs for minutes while Olio seed data loads. Leaving the token at
		// 0/0 with no phase for that whole stretch reads as a hung job.
		if (cancelToken != null) {
			cancelToken.setPhase("preparing book world");
		}
		// Create the PB2 base book (book row + world + world FK patch + grants)
		BaseRecord book = PbBookUtil.createBook(user, dataPath, slug, title);
		if (book == null) throw new PictureBookException(500, "createBook returned null for slug=" + slug);

		// Patch bookType = CHAPBOOK — use RecordUtil directly (book is owned by the olio principal,
		// so accessPoint.update(user,...) would be denied; this is an internal post-create write
		// consistent with writeBookRow using RecordUtil.createRecord for the same reason).
		try {
			BaseRecord typePatch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK,
				OlioFieldNames.FIELD_PB_BOOK_TYPE);
			typePatch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
			if (!IOSystem.getActiveContext().getRecordUtil().updateRecord(typePatch)) {
				logger.warn("createChapBook: failed to set bookType CHAPBOOK on book " + slug);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("createChapBook: could not patch bookType: " + e.getMessage());
		}

		// Patch maxLinesPerPage if the caller supplied a positive value
		if (maxLinesPerPage > 0) {
			try {
				BaseRecord mlpPatch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK,
					OlioFieldNames.FIELD_PB_MAX_LINES_PER_PAGE);
				mlpPatch.set(OlioFieldNames.FIELD_PB_MAX_LINES_PER_PAGE, maxLinesPerPage);
				if (!IOSystem.getActiveContext().getRecordUtil().updateRecord(mlpPatch)) {
					logger.warn("createChapBook: failed to persist maxLinesPerPage on book " + slug);
				}
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("createChapBook: could not patch maxLinesPerPage: " + e.getMessage());
			}
		}

		String bookGroupPath = PbBookUtil.bookGroupPath(slug);
		int sceneIndex = 0;
		// Fail-loudly accounting: how many scenes we EXPECTED to create (sum of stanza chunks across all
		// readable poems) and the first concrete scene-creation failure. If every scene fails we must NOT
		// return a silently-blank book — that is exactly the reported symptom when residual artifacts from a
		// prior same-slug book collide on the unique (name, groupId, organizationId) scene index.
		int expectedScenes = 0;
		String firstSceneFailure = null;

		// Pre-pass: load each poem ONCE and chunk it up front. This is what gives progress a real
		// denominator — the unit of work here is a stanza chunk (one LLM landscape call each), not a
		// poem, and that count is not knowable until the poems are read. Poems that are missing or
		// empty are dropped here with the same warnings as before, so they never reach the work loop.
		List<BaseRecord> loadedPoems = new ArrayList<>();
		List<List<String>> loadedChunks = new ArrayList<>();
		for (String poemObjectId : poemObjectIds) {
			BaseRecord poem = loadPoem(user, poemObjectId, orgId);
			if (poem == null) {
				logger.warn("createChapBook: poem not found: " + poemObjectId);
				continue;
			}
			String poemText = poem.get("text");
			if (poemText == null || poemText.isBlank()) {
				logger.warn("createChapBook: poem has no text content: " + poemObjectId);
				continue;
			}
			List<String> poemChunks = chunkPoem(poemText, effectiveMax);
			loadedPoems.add(poem);
			loadedChunks.add(poemChunks);
			expectedScenes += poemChunks.size();
		}
		if (cancelToken != null) {
			cancelToken.setTotal(expectedScenes);
			cancelToken.setCurrent(0);
			cancelToken.setPhase("creating scenes");
		}

		/// Cooperative stop, checked at scene boundaries. Set by a cancel OR by thread interruption
		/// (a Tomcat stop / job-pool shutdown), so a shutdown does not spend minutes of LLM time on
		/// work nobody is waiting for.
		boolean stopped = false;
		for (int pi = 0; pi < loadedPoems.size() && !stopped; pi++) {
			// Running thread of prior scene landscape prompts WITHIN this poem, in scene order, so scene
			// N's LLM prompt is generated with awareness of the imagery already used earlier in the SAME
			// poem. Declared INSIDE the poem loop so it RESETS at the start of each poem: continuity must
			// stay within a poem and must NOT bleed across poems — otherwise poem 1's imagery (e.g. a
			// volcano) themes every later poem's landscapes (the reported cross-poem leak). The per-poem
			// theme/mood/keywords below are already read fresh per poem. Only genuinely LLM-generated
			// prompts are threaded forward.
			List<String> priorScenePrompts = new ArrayList<>();
			BaseRecord poem = loadedPoems.get(pi);
			String poemObjectId = poem.get(FieldNames.FIELD_OBJECT_ID);
			String poemTitle = poem.get(OlioFieldNames.FIELD_PB_TITLE);
			if (poemTitle == null) poemTitle = poem.get(FieldNames.FIELD_NAME);
			String mood = poem.get(OlioFieldNames.FIELD_CB_MOOD);
			String theme = poem.get(OlioFieldNames.FIELD_CB_THEME);
			String keywords = poem.get(OlioFieldNames.FIELD_CB_KEYWORDS);

			List<String> chunks = loadedChunks.get(pi);
			for (String chunk : chunks) {
				if ((cancelToken != null && cancelToken.isCancelled())
						|| Thread.currentThread().isInterrupted()) {
					logger.info("createChapBook: stopping early after " + sceneIndex + "/"
							+ expectedScenes + " scene(s) for slug=" + slug
							+ " — scenes already created are persisted and kept");
					stopped = true;
					break;
				}
				try {
					// Assemble the prior context (poem theme/mood/keywords + recent scene imagery) and
					// thread it into the landscape-prompt LLM call so scenes stay visually continuous.
					String priorContext = assemblePriorContext(theme, keywords, mood, priorScenePrompts);
					String generatedPrompt = createChapBookScene(user, book, sceneIndex, chunk, poemTitle, mood, bookGroupPath, chatConfig, priorContext);
					// Only feed real LLM-generated prompts forward — never the stanza-excerpt fallback.
					if (NarrativeUtil.isMeaningful(generatedPrompt)) {
						priorScenePrompts.add(generatedPrompt);
					}
					sceneIndex++;
					if (cancelToken != null) cancelToken.incrementCurrent();
					PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_stories",
							"ChapBook scene " + sceneIndex + "/" + expectedScenes);
				} catch (Exception e) {
					if (firstSceneFailure == null) firstSceneFailure = e.getMessage();
					logger.warn("createChapBook: failed to create scene " + sceneIndex + " from poem " + poemObjectId + ": " + e.getMessage());
				}
			}
		}
		logger.info("createChapBook: created {} scenes for slug={}", sceneIndex, slug);

		// Fail LOUDLY rather than returning a blank book. If we expected at least one scene but created
		// none, every createChapBookScene threw — the dominant cause is a leftover scene from a prior
		// same-slug book tripping the unique (name, groupId, organizationId) index. Surface the concrete
		// reason (and the hint) instead of silently returning an empty book the caller would persist.
		/// `!stopped` matters: a run cancelled before its first scene legitimately has zero scenes,
		/// and throwing a 500 about "residual artifacts" would misreport the user's own cancel as a
		/// data-collision failure.
		if (sceneIndex == 0 && expectedScenes > 0 && !stopped) {
			throw new PictureBookException(500, "createChapBook produced a BLANK book for slug=" + slug
				+ ": expected " + expectedScenes + " scene(s) but created 0"
				+ (firstSceneFailure != null ? "; first failure: " + firstSceneFailure : "")
				+ ". This usually means residual artifacts from a previously-deleted same-slug book collided "
				+ "on the unique (name, groupId, organizationId) scene index — the existing book world must be "
				+ "fully torn down (deleteChapBook/teardownBookWorld) before a same-slug book is recreated.");
		}

		// Book and scenes are already committed. Workflow creation is non-fatal.
		String workflowPath = PbBookUtil.workflowGroupPath(slug);
		try {
			BaseRecord workflow = PbGraphUtil.getCreateWorkflow(user, book, workflowPath);
			if (workflow != null) {
				for (int i = 0; i < sceneIndex; i++) {
					String handle = "cb-scene-" + i;
					try {
						PbGraphUtil.addNode(user, workflow, handle, PbNodeTypeEnumType.SCENE, workflowPath, i);
					} catch (Exception e) {
						logger.warn("createChapBook: failed to add workflow node {} : {}", handle, e.getMessage());
					}
				}
			}
		} catch (Exception e) {
			logger.warn("createChapBook: workflow creation failed (non-fatal): {}", e.getMessage());
		}

		return book;
	}

	/**
	 * Create one {@code olio.pb.scene} for a stanza chunk.
	 * <p>
	 * Uses {@link PbBookUtil#createScene} for the base record, then patches {@code poemStanza},
	 * {@code mood}, and {@code title} onto the returned scene.
	 * <p>
	 * When {@code chatConfig} is non-null the {@code chapBook.landscape-prompt} template is
	 * called with the stanza text, mood, poem title, and the {@code priorContext} continuity
	 * string as variables, exactly as {@link #renderChapBook} does at render time. This stores an
	 * LLM-generated landscape prompt on the scene at creation, so the render step has a meaningful
	 * starting point even without a live LLM. Falls back to a stanza-excerpt placeholder when the
	 * LLM returns blank.
	 *
	 * @param priorContext continuity context assembled by {@link #assemblePriorContext} — the poem's
	 *                     theme/mood/keywords plus the imagery of earlier scenes; never blank (the
	 *                     sentinel {@code "none"} is used when there is nothing to carry) so the
	 *                     {@code {priorContext}} template placeholder is always substituted.
	 * @return the LLM-generated landscape prompt that was stored, or {@code null} when the LLM was
	 *         unavailable/blank and the stanza-excerpt fallback was stored instead. Only a non-null
	 *         return should be threaded forward as prior context for later scenes.
	 */
	static String createChapBookScene(BaseRecord user, BaseRecord book, int sceneIndex,
			String stanzaText, String poemTitle, String mood, String bookGroupPath, BaseRecord chatConfig,
			String priorContext) {
		// Create the base scene record
		BaseRecord scene = PbBookUtil.createScene(user, book, sceneIndex, poemTitle, bookGroupPath);
		if (scene == null) {
			throw new PictureBookException(500, "Failed to create ChapBook scene at index " + sceneIndex);
		}
		// The LLM-generated landscape prompt (null when the LLM was unavailable and the fallback ran).
		// The stanza+mood → landscape-prompt LLM step is factored into generateLandscapePrompt, shared
		// verbatim with regenerateSceneLandscapePrompt so both paths produce identical LLM prompts.
		String llmPrompt = generateLandscapePrompt(user, chatConfig, stanzaText, mood, poemTitle, priorContext);
		// Patch poemStanza, mood, title, and sdPrompt onto the scene.
		// Use RecordUtil.updateRecord (bypass PBAC) — consistent with how createChapBook patches bookType.
		try {
			// generateLandscapePrompt returns the GENUINE LLM prompt, or null when the LLM was
			// unavailable/blank or produced a non-genuine ("landscape, " fallback shape / sentinel) reply.
			// A null keeps this scene on the stanza-excerpt-free fallback below.
			String sdPromptVal = llmPrompt;
			if (sdPromptVal == null || sdPromptVal.isBlank()) {
				// No-LLM fallback. NEVER embed raw stanza text: the old excerpt form produced the exact
				// reported bad prompt "landscape, <poem text>, poetic atmosphere, painterly, soft light",
				// which handed the poem body straight to SD. Use only the poem TITLE (a title, not stanza
				// body) + mood + generic landscape tags. The "landscape, " prefix is retained deliberately
				// — it is the discriminator renderResolvedScene/renderChapBook key their recovery on
				// (isMeaningful && !startsWith("landscape, ")) to tell a real LLM prompt from this fallback.
				sdPromptVal = "landscape, "
					+ (poemTitle != null ? poemTitle + ", " : "")
					+ (mood != null ? mood : "poetic") + " atmosphere, painterly, soft light, wide natural vista";
			}
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_CB_POEM_STANZA, OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_PB_TITLE,
				OlioFieldNames.FIELD_CB_SD_PROMPT);
			if (stanzaText != null) patch.set(OlioFieldNames.FIELD_CB_POEM_STANZA, stanzaText);
			if (mood != null) patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
			if (poemTitle != null) patch.set(OlioFieldNames.FIELD_PB_TITLE, poemTitle);
			patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPromptVal);
			if (!IOSystem.getActiveContext().getRecordUtil().updateRecord(patch)) {
				logger.warn("createChapBookScene: failed to patch stanza/mood/sdPrompt onto scene " + sceneIndex);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("createChapBookScene: patch field error at index " + sceneIndex + ": " + e.getMessage());
		}
		return llmPrompt;
	}

	/**
	 * Assemble the "prior context" continuity string threaded into the {@code chapBook.landscape-prompt}
	 * LLM call. It combines the poem-level analysis (theme, mood, imagery keywords) with the imagery of
	 * the most recent prior scenes so scene N is generated with awareness of the poem and the imagery
	 * already used earlier in the book.
	 * <p>
	 * Every fragment is guarded with {@link NarrativeUtil#isMeaningful(String)} so an LLM-emitted literal
	 * {@code "null"}/{@code "n/a"}/{@code "unknown"} never leaks into the assembled prompt. The method
	 * <b>never returns blank</b>: when there is nothing meaningful to carry it returns the sentinel
	 * {@code "none"}, which keeps the {@code {priorContext}} placeholder substituted (so the
	 * UNSUBSTITUTED_PLACEHOLDER guard does not refuse the LLM call) and tells the template to base the
	 * prompt on the stanza alone.
	 * <p>
	 * Public so the continuity string threaded into scene generation is directly unit-testable.
	 *
	 * @param theme             poem theme (may be null/blank/"null")
	 * @param keywords          poem imagery keywords (may be null/blank/"null")
	 * @param mood              poem overall mood (may be null/blank/"null")
	 * @param priorScenePrompts LLM prompts of earlier scenes, in book order (may be null/empty)
	 * @return a non-blank continuity string, or the sentinel {@code "none"}
	 */
	public static String assemblePriorContext(String theme, String keywords, String mood, List<String> priorScenePrompts) {
		StringBuilder sb = new StringBuilder();
		if (NarrativeUtil.isMeaningful(theme)) sb.append("Poem theme: ").append(theme.trim()).append(". ");
		if (NarrativeUtil.isMeaningful(mood)) sb.append("Overall mood: ").append(mood.trim()).append(". ");
		if (NarrativeUtil.isMeaningful(keywords)) sb.append("Imagery keywords: ").append(keywords.trim()).append(". ");
		if (priorScenePrompts != null && !priorScenePrompts.isEmpty()) {
			// Only the two most recent prior scenes — enough for continuity without an unbounded prompt.
			int from = Math.max(0, priorScenePrompts.size() - 2);
			List<String> recent = new ArrayList<>();
			for (int i = from; i < priorScenePrompts.size(); i++) {
				String p = priorScenePrompts.get(i);
				if (NarrativeUtil.isMeaningful(p)) {
					String t = p.trim();
					recent.add(t.length() > 200 ? t.substring(0, 200).trim() : t);
				}
			}
			if (!recent.isEmpty()) {
				sb.append("Earlier scene imagery (keep visual continuity, do not copy verbatim): ")
					.append(String.join(" || ", recent)).append(".");
			}
		}
		String s = sb.toString().trim();
		return s.isEmpty() ? "none" : s;
	}

	/**
	 * Call the {@code chapBook.landscape-prompt} LLM template with a bounded single retry, returning
	 * the first non-blank result (already trimmed) or {@code null} if every attempt was blank.
	 * <p>
	 * qwen3-class reasoning models intermittently emit a think-only response even under {@code /no_think}
	 * (confirmed live 2026-09-01: an otherwise-identical two-scene book had scene 0 come back blank while
	 * scene 1 succeeded). {@code callLlmInternal} strips the think block and returns an empty string, so
	 * the scene silently stored the {@code "landscape, <stanza> … painterly, soft light"} fallback — the
	 * exact reported regression. A one-shot retry (the same bounded pattern
	 * {@code extractChunkedInternal} uses for unparseable JSON, PictureBookUtil.java attempt&nbsp;loop)
	 * recovers the common single-blank case before the fallback ever fires. It does NOT guarantee a
	 * non-blank result — a double blank still falls back — so callers must keep their fallback path.
	 */
	/**
	 * Shared landscape-prompt LLM step used by BOTH {@link #createChapBookScene} (at book creation) and
	 * {@link #regenerateSceneLandscapePrompt} (on explicit user request): assemble the
	 * {@code chapBook.landscape-prompt} template variables from a stanza + mood (+ optional composition and
	 * prior-scene context) and return the GENUINE LLM landscape prompt.
	 * <p>
	 * Returns {@code null} when the LLM was not usable — no {@code chatConfig}, a blank/missing stanza, an
	 * unavailable/blank LLM response, or a non-genuine reply (a bare sentinel like {@code "none"}/{@code "null"}
	 * or the {@code "landscape, "} no-LLM fallback shape, per {@link #isGenuineStoredPrompt}). Callers decide
	 * what a null means: {@code createChapBookScene} substitutes its stanza-title fallback;
	 * {@code regenerateSceneLandscapePrompt} treats it as a hard failure (nothing to persist).
	 *
	 * @param user               the acting user
	 * @param chatConfig         the resolved chatConfig; null returns null (no LLM step)
	 * @param stanzaText         the stanza to base the landscape imagery on; blank/null returns null
	 * @param mood               the scene mood; defaulted to {@code "poetic"} when null
	 * @param compositionContext composition hint (typically the poem/scene title); defaulted when null
	 * @param priorContext       earlier-scene continuity; the {@code "none"} sentinel is used when blank
	 * @return the genuine LLM landscape prompt, or null
	 */
	private static String generateLandscapePrompt(BaseRecord user, BaseRecord chatConfig,
			String stanzaText, String mood, String compositionContext, String priorContext) {
		if (chatConfig == null || stanzaText == null || stanzaText.isBlank()) {
			return null;
		}
		Map<String, String> vars = new LinkedHashMap<>();
		vars.put("stanzaText", stanzaText);
		vars.put("mood", mood != null ? mood : "poetic");
		vars.put("compositionContext", compositionContext != null ? compositionContext : "poetic scene");
		// priorContext must always be non-blank or the UNSUBSTITUTED_PLACEHOLDER guard in callLlmInternal
		// would refuse the call; substitute the "none" sentinel when there is nothing to carry.
		vars.put("priorContext", NarrativeUtil.isMeaningful(priorContext) ? priorContext.trim() : "none");
		String llmResult = callLandscapePrompt(user, chatConfig, vars);
		// Accept only a GENUINE prompt: a bare LLM sentinel like "none"/"null" or an accidentally
		// "landscape, "-shaped reply must NOT be treated as a real prompt (see isGenuineStoredPrompt), or
		// the render path would skip the scene while the client's isSceneUnprompted (which only recognises
		// blank / "landscape, ") would fail to flag it for regeneration.
		if (isGenuineStoredPrompt(llmResult)) {
			String prompt = llmResult.trim();
			logger.info("generateLandscapePrompt: LLM landscape prompt: {}",
				prompt.length() > 80 ? prompt.substring(0, 80) + "…" : prompt);
			return prompt;
		}
		logger.warn("generateLandscapePrompt: LLM returned no usable landscape prompt — caller will fall back");
		return null;
	}

	private static String callLandscapePrompt(BaseRecord user, BaseRecord chatConfig, Map<String, String> vars) {
		return callLandscapePrompt(user, chatConfig, vars, new boolean[1]);
	}

	/**
	 * {@code boolean[]} out-param variant of {@link #callLandscapePrompt(BaseRecord, BaseRecord, Map)}
	 * that reports whether the landscape-prompt LLM step HARD-failed (config/infra — the LLM genuinely
	 * could not be used) vs. merely produced a blank/soft result. Because the retry loop makes two
	 * attempts, a hard failure on EITHER attempt marks the whole call hard: an infrastructure fault
	 * (unreachable host, null response) is not made soft by a second identical attempt also failing.
	 * {@code hardFailureOut[0]} is left false when both attempts merely came back blank (the qwen3
	 * think-only soft-decline case), which must not raise the "LLM unavailable" alarm.
	 */
	private static String callLandscapePrompt(BaseRecord user, BaseRecord chatConfig, Map<String, String> vars, boolean[] hardFailureOut) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			boolean[] attemptHard = new boolean[1];
			String llmResult = PictureBookUtil.callLlmForChapBook(user, chatConfig, "chapBook.landscape-prompt", vars, attemptHard);
			if (attemptHard[0]) {
				hardFailureOut[0] = true;
				/// NEVER retry a HARD failure. The retry exists for the SOFT case only — a qwen3
				/// think-only reply, where a fresh generation usually produces real content. A hard
				/// failure is config (missing template / no chat config) or infra (timeout,
				/// unreachable host, null response); neither is fixed by re-issuing the identical
				/// request. For the timeout case it is actively harmful: the first exchange may still
				/// be occupying the model server's generation slot, so attempt 2 queues a second one
				/// behind it and doubles the load on an already-saturated server (measured
				/// 2026-09-14). Give up now and let the caller fall back.
				logger.warn("chapBook.landscape-prompt HARD-failed on attempt " + attempt
						+ " (config/infra) — not retrying");
				break;
			}
			if (llmResult != null && !llmResult.isBlank()) {
				return llmResult.trim();
			}
			if (attempt < 2) {
				logger.warn("chapBook.landscape-prompt returned blank — retrying once (qwen3 think-only response)");
			}
		}
		return null;
	}

	// ─────────────────────────────── poem scoping / listing ───────────────────────────────

	/**
	 * Resolve a chapbook {@code olio.pb.book} record by objectId for per-chapbook poem scoping.
	 * <p>
	 * A null/blank {@code bookObjectId} returns null (the caller keeps the global, unscoped behavior).
	 * A non-blank objectId that does not resolve throws {@link PictureBookException} (404) so the
	 * transport layer surfaces a bad scope reference rather than silently importing/listing globally.
	 * <p>
	 * <b>Scope policy (intentional):</b> resolution goes through {@link PbBookUtil#readBook} →
	 * {@code AccessPoint.find}, so scoping requires only <i>read</i> access to the target chapbook, not
	 * write. Tagging a poem to a book the caller can read (but not write) is allowed by design — the
	 * poem library is an org-shared collection and the {@code book} FK is an organizational label, not a
	 * grant of authority over the book. Confirmed as intended 2026-08-31.
	 *
	 * @param user         the acting user
	 * @param bookObjectId optional {@code olio.pb.book} objectId to scope to; null/blank = unscoped
	 * @return the resolved book record, or null when {@code bookObjectId} is null/blank
	 * @throws PictureBookException 404 when a non-blank objectId does not resolve to a book
	 */
	public static BaseRecord resolveScopeBook(BaseRecord user, String bookObjectId) {
		if (bookObjectId == null || bookObjectId.isBlank()) return null;
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgId);
		if (book == null) throw new PictureBookException(404, "ChapBook not found: " + bookObjectId);
		return book;
	}

	/**
	 * List {@code olio.cb.poem} records accessible to the user, ordered by name.
	 * <p>
	 * When {@code bookObjectId} is provided the list is filtered to poems whose {@code book} FK
	 * points at that chapbook (a {@code foreign} {@code model} query condition takes the RECORD, not
	 * its id — see {@code model-api.md}); when null/blank the full org-wide poem library is returned
	 * (backward compatible). Serialization is left to the transport layer.
	 *
	 * @param user         the acting user
	 * @param bookObjectId optional chapbook objectId to filter by; null/blank = unscoped
	 * @param startRecord  pagination offset
	 * @param recordCount  page size; 25 when 0 or negative
	 * @return matching poem records (never null)
	 * @throws PictureBookException 404 when a non-blank {@code bookObjectId} does not resolve
	 */
	public static List<BaseRecord> listPoems(BaseRecord user, String bookObjectId, long startRecord, int recordCount) {
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		int count = (recordCount > 0) ? recordCount : 25;

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		BaseRecord scopeBook = resolveScopeBook(user, bookObjectId);
		if (scopeBook != null) {
			// Foreign model condition takes the RECORD; a Long would silently become "book = null".
			q.field(OlioFieldNames.FIELD_PB_BOOK, scopeBook);
		}
		q.setRequestRange(startRecord, count);
		// Fresh read: the poem queue re-fetches immediately after a delete, and the server search
		// cache is keyed by query shape — a deleted poem's own identity invalidation does not clear
		// this book-filtered list entry, so without this the removed poem lingers. Mirrors listChapBooks.
		q.setCache(false);
		q.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_TITLE, "author",
			OlioFieldNames.FIELD_CB_THEME, OlioFieldNames.FIELD_CB_MOOD, OlioFieldNames.FIELD_CB_KEYWORDS,
			OlioFieldNames.FIELD_PB_BOOK
		});
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
			q.set(FieldNames.FIELD_ORDER, "ASCENDING");
		} catch (Exception ignored) {}

		org.cote.accountmanager.io.QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		BaseRecord[] results = (qr != null) ? qr.getResults() : null;
		return results != null ? Arrays.asList(results) : new ArrayList<>();
	}

	// ─────────────────────────────── ChapBook list / delete ───────────────────────────────

	/**
	 * List all {@code olio.pb.book} records with {@code bookType=CHAPBOOK} accessible to the user
	 * within their organization.
	 * <p>
	 * Uses {@link PbBookUtil#bookRequest()} as the projection extended with
	 * {@link OlioFieldNames#FIELD_PB_BOOK_TYPE} so clients can confirm the type. Sorted by name.
	 *
	 * @param user the acting user
	 * @return list of matching book records (never null)
	 */
	public static List<BaseRecord> listChapBooks(BaseRecord user) {
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
		// Build a projection that is a superset of bookRequest() plus bookType
		String[] base = PbBookUtil.bookRequest();
		List<String> req = new ArrayList<>(Arrays.asList(base));
		if (!req.contains(OlioFieldNames.FIELD_PB_BOOK_TYPE)) {
			req.add(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		}
		q.setRequest(req.toArray(new String[0]));
		q.setCache(false);
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
			q.set(FieldNames.FIELD_ORDER, "ASCENDING");
		} catch (Exception ignored) {}
		org.cote.accountmanager.io.QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		BaseRecord[] results = (qr != null) ? qr.getResults() : null;
		return results != null ? Arrays.asList(results) : new ArrayList<>();
	}

	/**
	 * Delete an {@code olio.pb.book} by objectId.
	 * <p>
	 * Verifies the book exists and has {@code bookType=CHAPBOOK} before deleting. This method owns
	 * the 404-vs-403 distinction so the transport layer does not have to re-implement the readBook +
	 * bookType check (which would violate the Service7 transport-only rule): it throws
	 * {@link PictureBookException} <b>404</b> when the book is not found, <b>403</b> when it exists but
	 * is not a CHAPBOOK. The delete itself is {@link PbDeleteUtil#deleteBookComplete}: a PBAC denial
	 * surfaces as <b>403</b> and a persistence failure as <b>500</b>, both carrying the concrete reason via
	 * {@link PictureBookException}. The method returns true only on a successful delete of a valid,
	 * authorized CHAPBOOK; any failure throws.
	 *
	 * @param user         the acting user
	 * @param bookObjectId objectId of the book to delete
	 * @return true if deleted (any failure throws {@link PictureBookException})
	 * @throws PictureBookException 400 for missing args, 404 if not found, 403 if not a CHAPBOOK or the
	 *         delete is PBAC-denied, 500 if the delete fails at persistence
	 */
	public static boolean deleteChapBook(BaseRecord user, String bookObjectId) {
		if (user == null || bookObjectId == null || bookObjectId.isBlank()) {
			throw new PictureBookException(400, "user and bookObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgId);
		if (book != null) {
			String bookType = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
			if (bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
				throw new PictureBookException(403, "Book " + bookObjectId + " is not a CHAPBOOK");
			}
		}
		// An unreadable row (olio-owned, creator's grants never applied because world creation failed) is
		// resolved inside deleteBookComplete as the olio principal under its creator/orphan guard, so an
		// incomplete ChapBook stays deletable by its creator and a stranger's book still gets 403.
		PictureBookUtil.DeleteResult result;
		try {
			result = PbDeleteUtil.deleteBookComplete(user, bookObjectId);
		} catch (PictureBookException e) {
			if (e.getStatus() == 404) {
				throw new PictureBookException(404, "ChapBook not found: " + bookObjectId);
			}
			throw e;
		}
		if (!result.deleted) {
			throw new PictureBookException(result.authorized ? 500 : 403, result.reason);
		}
		return true;
	}

	// ─────────────────────────────── scene review edits ───────────────────────────────

	/**
	 * Resolve the parent {@code olio.pb.book} of a scene from the scene's {@code book} FK, projected with
	 * {@link PbBookUtil#bookRequest()} so the resolved book carries {@code bookType} (for the CHAPBOOK
	 * guard) plus the {@code id}/{@code organizationId} that {@link PbBookUtil#listScenes} and
	 * {@link PbBookUtil#reorderScenes} require.
	 * <p>
	 * A bare-projected FK reliably carries its numeric {@code id} (the stored FK column) but may NOT carry
	 * {@code objectId} (see model-api.md): prefer {@code objectId} when present, otherwise resolve by the
	 * FK's {@code id}, so this cannot throw a spurious 404 for a book that exists. Mirrors the resolution
	 * in {@link #renderChapBookScene}. All reads are performed AS THE ACTING USER through AccessPoint.
	 *
	 * @throws PictureBookException 404 when the scene has no book FK or the book is not readable by {@code user}
	 */
	private static BaseRecord resolveSceneBook(BaseRecord user, BaseRecord scene, long orgId) {
		BaseRecord bookFk = scene.get(OlioFieldNames.FIELD_PB_BOOK);
		if (bookFk == null) {
			throw new PictureBookException(404, "Book not found for scene: " + scene.get(FieldNames.FIELD_OBJECT_ID));
		}
		String bookObjectId = bookFk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord book;
		if (bookObjectId != null && !bookObjectId.isBlank()) {
			book = PbBookUtil.readBook(user, bookObjectId, orgId);
		} else {
			Object bookIdVal = bookFk.get(FieldNames.FIELD_ID);
			long bookId = (bookIdVal instanceof Number ? ((Number) bookIdVal).longValue() : 0L);
			if (bookId <= 0) {
				throw new PictureBookException(404, "Book not found for scene: " + scene.get(FieldNames.FIELD_OBJECT_ID));
			}
			Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ID, bookId);
			bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			bq.setRequest(PbBookUtil.bookRequest());
			bq.setCache(false);
			book = IOSystem.getActiveContext().getAccessPoint().find(user, bq);
		}
		if (book == null) {
			throw new PictureBookException(404, "Book not found for scene: " + scene.get(FieldNames.FIELD_OBJECT_ID));
		}
		return book;
	}

	/**
	 * Guard: the resolved book must be a CHAPBOOK. Mirrors {@link #deleteChapBook}'s 403.
	 */
	private static void requireChapBook(BaseRecord book) {
		String bookType = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		if (bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
			throw new PictureBookException(403, "Book " + book.get(FieldNames.FIELD_OBJECT_ID) + " is not a CHAPBOOK");
		}
	}

	/**
	 * Fold the NEXT scene (by {@code sceneIndex} within the same book) into scene {@code sceneObjectId},
	 * then delete the next scene and reindex the survivors so {@code sceneIndex} is a clean {@code 0..n-1}.
	 * <p>
	 * The absorbing scene's {@code poemStanza} becomes {@code thisStanza + "\n" + nextStanza} and its
	 * {@code imageStale} is set true (the rendered image no longer matches the longer merged text). The
	 * absorbing scene's {@code sdPrompt} and {@code promptLocked} are deliberately PRESERVED: a
	 * hand-edited or LOCKED prompt must survive a merge, so the user can re-render with their own prompt,
	 * or the LLM regenerates from the merged stanza when the prompt is unlocked. All of that lands in ONE
	 * authorized PATCH carrying identity + the validated {@code name} + exactly those two changed fields.
	 * <p>
	 * <b>Every write is performed AS THE ACTING USER through {@code AccessPoint}</b> — scenes are
	 * user-owned + group-scoped (created via {@code AccessPoint.create(user, ...)} and reordered via
	 * {@code AccessPoint.update(user, ...)}), so the olio principal is not involved. Every AccessPoint
	 * result is asserted: a null update or a false delete fails loudly with a {@link PictureBookException}
	 * rather than being swallowed into a silent no-op.
	 * <p>
	 * The folded scene's compositions (its page composition with its scene actors and elements) are deleted
	 * child→parent, as the user, <i>before</i> the stanza patch — a refused composition row aborts with
	 * 403/500 and nothing has changed yet.
	 *
	 * @param user          the acting user (must have WRITE access to the scenes)
	 * @param sceneObjectId objectId of the scene that ABSORBS its successor
	 * @return the objectId of the surviving (absorbing) scene
	 * @throws PictureBookException 400 for missing args or when {@code sceneObjectId} is the last scene
	 *         (no following scene to fold in); 403 when the parent book is not a CHAPBOOK or PBAC refuses a
	 *         composition row of the folded scene; 404 when the scene or its book is not readable; 500 when
	 *         a patch/delete/reindex fails
	 */
	public static String mergeSceneUp(BaseRecord user, String sceneObjectId) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, orgId);
		if (scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		BaseRecord book = resolveSceneBook(user, scene, orgId);
		requireChapBook(book);

		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
		int pos = -1;
		for (int i = 0; i < scenes.size(); i++) {
			if (sceneObjectId.equals(scenes.get(i).get(FieldNames.FIELD_OBJECT_ID))) {
				pos = i;
				break;
			}
		}
		if (pos < 0) {
			throw new PictureBookException(404, "Scene not found in its book: " + sceneObjectId);
		}
		if (pos >= scenes.size() - 1) {
			throw new PictureBookException(400,
				"Cannot merge up the last scene: there is no following scene to fold into " + sceneObjectId);
		}
		BaseRecord absorbing = scenes.get(pos);
		BaseRecord next = scenes.get(pos + 1);

		String thisStanza = absorbing.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		String nextStanza = next.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		String merged = (thisStanza != null ? thisStanza : "") + "\n" + (nextStanza != null ? nextStanza : "");

		// The folded scene's compositions go first, before any write: a refused row then leaves the stanza,
		// both scenes and the page intact instead of a merged stanza beside a scene that could not be folded.
		deleteSceneCompositions(user, next, orgId);

		// PRESERVE the absorbing scene's sdPrompt/promptLocked — a hand-edited or LOCKED prompt must
		// survive a merge. Only poemStanza and imageStale change: the merged text is longer, so the
		// rendered image is stale, but the prompt stays so the user can re-render with their own prompt
		// (or the LLM regenerates from the merged stanza when the prompt is unlocked).
		BaseRecord patch = PbGraphUtil.patchOf(absorbing, OlioModelNames.MODEL_PB_SCENE,
			OlioFieldNames.FIELD_CB_POEM_STANZA, OlioFieldNames.FIELD_PB_IMAGE_STALE);
		try {
			patch.set(OlioFieldNames.FIELD_CB_POEM_STANZA, merged);
			patch.set(OlioFieldNames.FIELD_PB_IMAGE_STALE, Boolean.TRUE);
		}
		catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a merge patch: " + e.getMessage());
		}
		if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to persist merged stanza on scene " + sceneObjectId);
		}
		PbBookUtil.markSceneNodeStale(user, absorbing);

		if (!IOSystem.getActiveContext().getAccessPoint().delete(user, next)) {
			throw new PictureBookException(500,
				"Merged stanza was persisted but the folded scene " + next.get(FieldNames.FIELD_OBJECT_ID)
				+ " could not be deleted");
		}

		reindexSurvivors(user, book);
		return sceneObjectId;
	}

	/**
	 * Tear down every composition that points at {@code scene} (its page composition and anything else keyed to it)
	 * child→parent as {@code user}, before the scene row itself goes: the composition's {@code scene} FK would
	 * otherwise dangle. A refused row aborts with 403 (PBAC) or 500 (persistence) and the scene is left in place —
	 * a page is never half-deleted.
	 */
	private static void deleteSceneCompositions(BaseRecord user, BaseRecord scene, long orgId) {
		PictureBookUtil.DeleteResult refused = PbDeleteUtil.deleteCompositionsByScene(user, scene, orgId,
			PictureBookUtil.DeleteResult.ok());
		if (refused != null) {
			throw new PictureBookException(refused.authorized ? 500 : 403,
				"Composition row of scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + " could not be deleted: "
				+ refused.reason);
		}
	}

	/**
	 * Delete a single scene and reindex the survivors so {@code sceneIndex} is a clean {@code 0..n-1}.
	 * <p>
	 * As with {@link #mergeSceneUp}, all writes are performed AS THE ACTING USER through {@code AccessPoint}
	 * and every result is asserted. The parent book must be a CHAPBOOK. The scene's compositions (page
	 * composition, scene actors, scene elements) are deleted child→parent before the scene row; a refused
	 * composition row aborts with 403/500 and the scene is left in place.
	 *
	 * @param user          the acting user (must have DELETE access to the scene)
	 * @param sceneObjectId objectId of the scene to delete
	 * @throws PictureBookException 400 for missing args; 403 when the parent book is not a CHAPBOOK or PBAC
	 *         refuses a composition row; 404 when the scene or its book is not readable; 500 when the delete
	 *         or reindex fails
	 */
	public static void deleteSceneAndReindex(BaseRecord user, String sceneObjectId) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, orgId);
		if (scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		BaseRecord book = resolveSceneBook(user, scene, orgId);
		requireChapBook(book);

		deleteSceneCompositions(user, scene, orgId);
		if (!IOSystem.getActiveContext().getAccessPoint().delete(user, scene)) {
			throw new PictureBookException(500, "Failed to delete scene " + sceneObjectId);
		}

		reindexSurvivors(user, book);
	}

	/**
	 * Re-list a book's scenes (uncached, ascending by {@code sceneIndex}) and reassign {@code sceneIndex}
	 * to a gap-free {@code 0..n-1} via {@link PbBookUtil#reorderScenes}. Called after a scene delete/merge.
	 */
	private static void reindexSurvivors(BaseRecord user, BaseRecord book) {
		List<BaseRecord> survivors = PbBookUtil.listScenes(user, book);
		List<String> orderedIds = new ArrayList<>(survivors.size());
		for (BaseRecord s : survivors) {
			orderedIds.add(s.get(FieldNames.FIELD_OBJECT_ID));
		}
		PbBookUtil.reorderScenes(user, book, orderedIds);
	}

	// ─────────────────────────────── ChapBook rendering ───────────────────────────────

	/**
	 * Generate SD images for all scenes of a CHAPBOOK-typed {@code olio.pb.book}.
	 * Delegates to {@link #renderChapBook(BaseRecord, String, String, String, BaseRecord, BaseRecord)} with no chatConfig or sdConfig.
	 */
	public static int renderChapBook(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer) {
		return renderChapBook(user, bookObjectId, sdApiType, sdServer, null, null);
	}

	/**
	 * Generate SD images for all scenes of a CHAPBOOK-typed {@code olio.pb.book}.
	 * <p>
	 * When {@code chatConfig} is provided, the LLM generates a landscape SD prompt from each
	 * scene's {@code poemStanza} text (using the {@code chapBook.landscape-prompt} template),
	 * producing imagery appropriate for the poem's imagery and atmosphere. When null, the
	 * stored {@code sdPrompt} template string is used as a fallback.
	 * <p>
	 * The resulting {@code data.data} image objectId is stored in {@code imageObjectId} on
	 * each scene; {@code bookPageView} uses this as the {@code dataObjectId} fallback when
	 * no workflow sceneNode exists.
	 *
	 * @param user         the acting user (must have WRITE access to the book)
	 * @param bookObjectId objectId of the {@code olio.pb.book} to render
	 * @param sdApiType    SD API type string (e.g. "SWARM"); must match a {@link SDAPIEnumType} name
	 * @param sdServer     SD server URL
	 * @param chatConfig   optional LLM config for landscape prompt generation; null = use stored sdPrompt
	 * @return number of scenes for which an image was successfully generated
	 * @throws PictureBookException if the book is not found or is not a CHAPBOOK
	 */
	public static int renderChapBook(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig) {
		return renderChapBookSummary(user, bookObjectId, sdApiType, sdServer, chatConfig, clientSdConfig).rendered;
	}

	/**
	 * Bulk-render variant of {@link #renderChapBook(BaseRecord, String, String, String, BaseRecord, BaseRecord)}
	 * that returns the full {@link ChapBookRenderSummary} (rendered / skipped / llmUnavailable counts)
	 * instead of only the rendered count. The transport layer uses this to report to the client how many
	 * scenes rendered, how many were left un-prompted, and — critically — for how many the LLM landscape
	 * step was genuinely unavailable, rather than silently degrading the whole render.
	 *
	 * @return a {@link ChapBookRenderSummary}; {@code rendered} matches the legacy {@code renderChapBook} return.
	 * @throws PictureBookException if the book is not found or is not a CHAPBOOK
	 */
	public static ChapBookRenderSummary renderChapBookSummary(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig) {
		return renderChapBookSummary(user, bookObjectId, sdApiType, sdServer, chatConfig, clientSdConfig, null);
	}

	/**
	 * Same as {@link #renderChapBookSummary(BaseRecord, String, String, String, BaseRecord, BaseRecord)}
	 * plus an optional {@code cancelToken} for progress and cooperative cancellation.
	 *
	 * <p>A bulk render is one SD generation per scene (often preceded by an LLM landscape call), so a
	 * chapbook of any size runs for many minutes. Every scene's image is persisted as it is produced,
	 * so stopping early keeps what has rendered — the counts in the returned summary describe what
	 * actually happened rather than what was requested.
	 */
	public static ChapBookRenderSummary renderChapBookSummary(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig,
			SummarizeProgress cancelToken) {
		/// A non-null cancelToken means the caller is already inside a registry job (Service7's cb.render
		/// wrapper); on the v3 path such a caller detaches, because a registry job must never await another.
		return renderChapBookSummary(user, bookObjectId, sdApiType, sdServer, chatConfig, clientSdConfig,
			cancelToken, cancelToken != null, false);
	}

	/**
	 * V3 §24 (P1-9): submit a ChapBook bulk render as one {@code pb.renderAll} job and return immediately.
	 * Runs the prompt pre-step for every page on the calling thread, then submits; the returned summary
	 * carries {@code pending=true} and the job / run identifiers, which the caller polls through
	 * {@code GET /rest/job/{jobId}}. Returns {@code null} when the book is on the {@code v1} render path,
	 * meaning "there is no detachable job here - use {@link #renderChapBookSummary} as before".
	 *
	 * @throws PictureBookException 400/404 as {@link #renderChapBookSummary}; 409 from the planner when
	 *         every page node is already current or pinned
	 */
	public static ChapBookRenderSummary submitChapBookRender(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig) {
		return renderChapBookSummary(user, bookObjectId, sdApiType, sdServer, chatConfig, clientSdConfig, null, true, true);
	}

	private static ChapBookRenderSummary renderChapBookSummary(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig,
			SummarizeProgress cancelToken, boolean detach, boolean v3Only) {
		if (user == null || bookObjectId == null || bookObjectId.isBlank()) {
			throw new PictureBookException(400, "user and bookObjectId are required");
		}
		if (sdApiType == null || sdServer == null) {
			throw new PictureBookException(500, "SD server not configured");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = loadRenderBook(user, bookObjectId, orgId);
		if (book == null) {
			throw new PictureBookException(404, "Book not found: " + bookObjectId);
		}

		String bookType = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		if (bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
			throw new PictureBookException(400, "Book " + bookObjectId + " is not a CHAPBOOK");
		}
		boolean v3 = PbRenderPath.isV3(book);
		if (v3Only && !v3) {
			return null;
		}

		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
		if (scenes.isEmpty()) {
			logger.info("renderChapBook: no scenes for book {}", bookObjectId);
			return (v3 ? new ChapBookRenderSummary(0, 0, 0, PbRenderPath.V3, null, null, 0, false, 0)
				: new ChapBookRenderSummary(0, 0, 0));
		}
		PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_stories", "ChapBook render: " + scenes.size() + " scene(s)…");
		if (cancelToken != null) {
			cancelToken.setTotal(scenes.size());
			cancelToken.setCurrent(0);
			cancelToken.setPhase("rendering scenes");
		}

		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType.toUpperCase()), sdServer);

		/// V3 §24 (P1-9): on the graph path every prompted page renders as a LANDSCAPE node inside one
		/// pb.renderAll job; the v1 per-scene SD loop below is not run.
		if (v3) {
			if (clientSdConfig != null) {
				logger.warn("renderChapBook: book {} renders on the v3 path; the per-request sdConfig overlay is not applied"
					+ " - persist it as a page configOverride instead", bookObjectId);
			}
			try {
				return renderChapBookSummaryV3(user, book, scenes, sdu, chatConfig, detach);
			} finally {
				PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
			}
		}

		int rendered = 0;
		int skipped = 0;
		int llmUnavailable = 0;
		// Running thread of the scenes' stored landscape prompts, in scene order WITHIN a poem, so a
		// re-generated render-time prompt for scene N stays visually continuous with the imagery of
		// earlier scenes of the SAME poem. RESET at each poem boundary (mirrors createChapBook's
		// per-poem scoping) so poem 1's imagery does not bleed into poem 2's landscapes — the reported
		// cross-poem leak. listScenes() returns scenes ordered by sceneIndex, and every ChapBook scene
		// carries its source poem's title (FIELD_PB_TITLE), so consecutive same-poem scenes share a
		// title and a change of title marks the start of a new poem.
		List<String> priorScenePrompts = new ArrayList<>();
		String priorSceneTitle = null;
		for (BaseRecord scene : scenes) {
			if ((cancelToken != null && cancelToken.isCancelled())
					|| Thread.currentThread().isInterrupted()) {
				logger.info("renderChapBook: stopping early for book {} — {} rendered, {} skipped;"
					+ " images already generated are persisted", bookObjectId, rendered, skipped);
				break;
			}
			String sceneTitle = scene.get(OlioFieldNames.FIELD_PB_TITLE);
			if (priorSceneTitle != null && !java.util.Objects.equals(priorSceneTitle, sceneTitle)) {
				priorScenePrompts.clear();
			}
			priorSceneTitle = sceneTitle;
			String sceneMood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
			String priorContext = assemblePriorContext(null, null, sceneMood, priorScenePrompts);
			SceneRenderResult result = renderResolvedScene(user, book, scene, sdu, chatConfig, clientSdConfig, priorContext, null);
			// Thread the scene's stored landscape prompt forward for continuity — skip the stanza-excerpt
			// fallback shape ("landscape, …") so only real imagery accumulates, BUT always thread a locked
			// human edit (any shape) so a deliberate edit still contributes to later scenes' prior context.
			String storedPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			Boolean sceneLockedFlag = scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
			boolean sceneLocked = (sceneLockedFlag != null && sceneLockedFlag.booleanValue());
			if (isGenuineStoredPrompt(storedPrompt) || (sceneLocked && NarrativeUtil.isMeaningful(storedPrompt))) {
				priorScenePrompts.add(storedPrompt);
			}
			// Count every scene whose landscape LLM step genuinely could not run — whether it went on to
			// degrade-render or was skipped — so the caller can surface "the LLM was down for N scenes".
			if (result.llmUnavailable) {
				llmUnavailable++;
			}
			/// Progress counts scenes ATTEMPTED. Counting only rendered ones made the bar stall on a
			/// book with skipped scenes and never reach its total, which reads as a hung job.
			if (cancelToken != null) cancelToken.incrementCurrent();
			if (result.status == SceneRenderStatus.RENDERED) {
				rendered++;
				PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Scene " + rendered + "/" + scenes.size() + " rendered");
			} else if (result.status == SceneRenderStatus.SKIPPED_NO_PROMPT) {
				skipped++;
			}
		}
		PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
		logger.info("renderChapBook: rendered {}/{} scenes for book {} ({} un-prompted, skipped; {} llm-unavailable)",
			rendered, scenes.size(), bookObjectId, skipped, llmUnavailable);
		return new ChapBookRenderSummary(rendered, skipped, llmUnavailable);
	}

	/**
	 * Render the SD image for a SINGLE ChapBook scene, resolving the scene's parent book itself.
	 * <p>
	 * This is the per-scene, client-driven analogue of {@link #renderChapBook}: PB2 solved the
	 * gateway-timeout problem of the whole-book synchronous render by having the client call one
	 * per-scene endpoint per scene, so no single HTTP request runs long. ChapBook mirrors that shape
	 * here. The render body is identical to the bulk loop — both funnel through
	 * {@link #renderResolvedScene(BaseRecord, BaseRecord, BaseRecord, SDUtil, BaseRecord, BaseRecord, String)}
	 * so there is exactly one code path.
	 *
	 * @param user           the acting user (must have WRITE access to the scene/book)
	 * @param sceneObjectId  objectId of the {@code olio.pb.scene} to render
	 * @param sdApiType      SD API type string (e.g. "SWARM"); must match a {@link SDAPIEnumType} name
	 * @param sdServer       SD server URL
	 * @param chatConfig     optional LLM config for landscape prompt generation; null = use stored sdPrompt
	 * @param clientSdConfig optional client SD-config overrides applied over the resolved config
	 * @param promptOverride optional human-edited landscape prompt; when non-blank it is PERSISTED to the
	 *                       scene first (via {@link #setSceneLandscapePrompt}) and then used VERBATIM for
	 *                       this render, short-circuiting the stored-vs-recover resolution so even an edit
	 *                       shaped like {@code "landscape, ..."} is honored. Null/blank = normal behaviour.
	 * @return a {@link SceneRenderResult}: {@link SceneRenderStatus#RENDERED} (with the generated image
	 *         {@code objectId}) on success; {@link SceneRenderStatus#SKIPPED_NO_PROMPT} (imageObjectId null)
	 *         when the scene is un-prompted (no genuine LLM prompt available — no image produced, left for
	 *         explicit regeneration); {@link SceneRenderStatus#FAILED} (imageObjectId null) when SD returned
	 *         nothing, the patch failed, or an exception was caught
	 * @throws PictureBookException 400 for missing args / non-CHAPBOOK book, 404 if the scene or its
	 *         book is not found, 500 if the SD server is not configured
	 */
	public static SceneRenderResult renderChapBookScene(BaseRecord user, String sceneObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig,
			String promptOverride) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		if (sdApiType == null || sdServer == null) {
			throw new PictureBookException(500, "SD server not configured");
		}
		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType.toUpperCase()), sdServer);
		return renderChapBookScene(user, sceneObjectId, sdu, chatConfig, clientSdConfig, promptOverride);
	}

	/**
	 * Per-scene render against a caller-supplied {@link SDUtil}. Package-private seam so a test can hand
	 * in a capturing subclass and assert the exact config/prompt that reaches {@code createImage}
	 * without a live SD server; the public overloads build the {@link SDUtil} from
	 * {@code sdApiType}/{@code sdServer} and delegate here. Everything else - scene/book resolution,
	 * CHAPBOOK check, prompt-override persistence - is identical.
	 */
	static SceneRenderResult renderChapBookScene(BaseRecord user, String sceneObjectId, SDUtil sdu,
			BaseRecord chatConfig, BaseRecord clientSdConfig, String promptOverride) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		if (sdu == null) {
			throw new PictureBookException(500, "SD server not configured");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		/// Load the scene org-scoped with the SAME projection listScenes() uses — it carries poemStanza,
		/// sdPrompt (FIELD_CB_SD_PROMPT), mood, title, configOverride and the book FK (FIELD_PB_BOOK).
		Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
		sq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sq.setRequest(PbBookUtil.sceneRequest());
		sq.setCache(false);
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
		if (scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}

		/// Resolve the parent book from the scene's book FK, loaded with the render projection so the
		/// config tier is populated (identical projection to renderChapBook's book load). A bare-projected
		/// FK reliably carries its numeric id (the stored FK column) but may NOT carry objectId (see
		/// model-api.md). Prefer objectId when present, otherwise resolve by the FK's id so this path
		/// cannot throw a spurious 404 for a book that exists.
		BaseRecord bookFk = scene.get(OlioFieldNames.FIELD_PB_BOOK);
		if (bookFk == null) {
			throw new PictureBookException(404, "Book not found for scene: " + sceneObjectId);
		}
		String bookObjectId = bookFk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord book;
		if (bookObjectId != null && !bookObjectId.isBlank()) {
			book = loadRenderBook(user, bookObjectId, orgId);
		} else {
			Object bookIdVal = bookFk.get(FieldNames.FIELD_ID);
			long bookId = (bookIdVal instanceof Number ? ((Number) bookIdVal).longValue() : 0L);
			if (bookId <= 0) {
				throw new PictureBookException(404, "Book not found for scene: " + sceneObjectId);
			}
			book = loadRenderBookById(user, bookId, orgId);
		}
		if (book == null) {
			throw new PictureBookException(404, "Book not found for scene: " + sceneObjectId);
		}
		if (bookObjectId == null || bookObjectId.isBlank()) {
			bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		}

		String bookType = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		if (bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
			throw new PictureBookException(400, "Book " + bookObjectId + " is not a CHAPBOOK");
		}

		// Single-scene render: the client drives one scene at a time, so the full running thread of
		// sibling scenes is not reconstructed here (that would require loading and ordering every scene
		// of the book on each per-scene call). The continuity context is derived from THIS scene's mood.
		// DESIGN FORK: cross-scene continuity for the per-scene path would need an ordered scene load;
		// left minimal by design — the bulk renderChapBook path carries the full running thread.
		String sceneMood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
		String priorContext = assemblePriorContext(null, null, sceneMood, null);

		// A human-edited prompt override is authoritative: PERSIST it first (single persist path,
		// verbatim — setSceneLandscapePrompt does NOT run it through the "landscape, " discriminator),
		// then mirror it onto the in-memory scene so the render funnel and any forward-threading see the
		// same value, and hand it to renderResolvedScene as the EXPLICIT verbatim override.
		String effectiveOverride = (promptOverride != null && !promptOverride.isBlank()) ? promptOverride.trim() : null;
		if (effectiveOverride != null) {
			setSceneLandscapePrompt(user, sceneObjectId, effectiveOverride);
			try {
				scene.set(OlioFieldNames.FIELD_CB_SD_PROMPT, effectiveOverride);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("renderChapBookScene: could not set override sdPrompt on in-memory scene {}: {}", sceneObjectId, e.getMessage());
			}
		}
		return renderResolvedScene(user, book, scene, sdu, chatConfig, clientSdConfig, priorContext, effectiveOverride);
	}

	/**
	 * Backward-compatible 6-arg overload — renders a scene with NO human-edited prompt override, so the
	 * stored-continuity-prompt-vs-LLM-recovery resolution runs exactly as before. Existing callers (the
	 * bulk render loop, tests, and the Service7 transport) are untouched; delegates to the 7-arg overload
	 * with {@code promptOverride == null}.
	 */
	public static SceneRenderResult renderChapBookScene(BaseRecord user, String sceneObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig) {
		return renderChapBookScene(user, sceneObjectId, sdApiType, sdServer, chatConfig, clientSdConfig, null);
	}

	/**
	 * Load an {@code olio.pb.book} by objectId with the render projection shared by both
	 * {@link #renderChapBook} and {@link #renderChapBookScene}.
	 * <p>
	 * The book tier of the config-precedence merge ({@code PbConfigUtil.resolveEffectiveConfig}) is
	 * invisible unless sdConfig/compositeSdConfig are projected — a missing tier fails SILENTLY to
	 * resource defaults. Union the render-specific fields (groupPath/ownerId/bookType/world) with
	 * {@code PbConfigUtil.requestFields()} so the book always carries its config columns, and stays
	 * correct if requestFields() grows.
	 */
	private static BaseRecord loadRenderBook(BaseRecord user, String bookObjectId, long orgId) {
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		java.util.LinkedHashSet<String> bookReq = new java.util.LinkedHashSet<>(Arrays.asList(
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_BOOK_TYPE, OlioFieldNames.FIELD_PB_WORLD
		));
		bookReq.addAll(Arrays.asList(PbConfigUtil.requestFields()));
		bq.setRequest(bookReq.toArray(new String[0]));
		bq.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, bq);
	}

	/**
	 * Load an {@code olio.pb.book} by numeric {@code id} with the render projection — identical to
	 * {@link #loadRenderBook(BaseRecord, String, long)} except it queries {@code FIELD_ID} instead of
	 * {@code FIELD_OBJECT_ID}. Used as the fallback in {@link #renderChapBookScene} when the scene's
	 * bare-projected book FK carries only its numeric id and not objectId, so the render path resolves
	 * the book regardless of which identity field the FK projection populated. The projection (and
	 * {@code setCache(false)}) is kept identical so the config-precedence tier stays populated.
	 */
	private static BaseRecord loadRenderBookById(BaseRecord user, long bookId, long orgId) {
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ID, bookId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		java.util.LinkedHashSet<String> bookReq = new java.util.LinkedHashSet<>(Arrays.asList(
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_BOOK_TYPE, OlioFieldNames.FIELD_PB_WORLD
		));
		bookReq.addAll(Arrays.asList(PbConfigUtil.requestFields()));
		bq.setRequest(bookReq.toArray(new String[0]));
		bq.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, bq);
	}

	/**
	 * Read a ChapBook's stored book-level SD config (V3 P0-10a). Resolves the book by objectId with the
	 * render projection, requires it to be a CHAPBOOK, and delegates to
	 * {@link PbBookUtil#getBookSdConfig}. Never writes.
	 *
	 * @return the stored {@code olio.sd.config}, or null when the book has none yet
	 * @throws PictureBookException 400 for missing args, 404 when the book is not readable, 403 when it is
	 *         not a CHAPBOOK
	 */
	public static BaseRecord getBookSdConfig(BaseRecord user, String bookObjectId) {
		BaseRecord book = loadChapBookForConfig(user, bookObjectId);
		return PbBookUtil.getBookSdConfig(user, book);
	}

	/**
	 * Persist a ChapBook's book-level SD config from a sparse client record (V3 P0-10a / G-26 for
	 * ChapBook) - see {@link PbBookUtil#setBookSdConfig} for the persistence contract. The next render
	 * of any scene in the book picks the stored row up through the book tier of
	 * {@link PbConfigUtil#resolveEffectiveConfig}.
	 *
	 * @return the stored config after the write, re-read fresh
	 * @throws PictureBookException 400 for missing args, 404 when the book is not readable, 403 when it is
	 *         not a CHAPBOOK, 500 when a write fails
	 */
	public static BaseRecord setBookSdConfig(BaseRecord user, String bookObjectId, BaseRecord sparse) {
		BaseRecord book = loadChapBookForConfig(user, bookObjectId);
		return PbBookUtil.setBookSdConfig(user, book, sparse);
	}

	private static BaseRecord loadChapBookForConfig(BaseRecord user, String bookObjectId) {
		if (user == null || bookObjectId == null || bookObjectId.isBlank()) {
			throw new PictureBookException(400, "user and bookObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = loadRenderBook(user, bookObjectId, orgId);
		if (book == null) {
			throw new PictureBookException(404, "Book not found: " + bookObjectId);
		}
		requireChapBook(book);
		return book;
	}

	/**
	 * Shared render core for a single ChapBook scene, given already-resolved {@code book} and
	 * {@code scene} records and a live {@link SDUtil}. Both the bulk {@link #renderChapBook} loop and
	 * the per-scene {@link #renderChapBookScene} path funnel through this so the render behaviour is a
	 * single code path.
	 *
	 * @return a {@link SceneRenderResult}: {@link SceneRenderStatus#RENDERED} (with the generated image
	 *         {@code objectId}) on success; {@link SceneRenderStatus#SKIPPED_NO_PROMPT} (imageObjectId
	 *         null) when the scene is un-prompted — no genuine LLM prompt is available, so no image is
	 *         produced and the scene is left for explicit regeneration; {@link SceneRenderStatus#FAILED}
	 *         (imageObjectId null) when SD returned no image, the imageObjectId patch failed, or an
	 *         exception was caught.
	 */
	private static SceneRenderResult renderResolvedScene(BaseRecord user, BaseRecord book, BaseRecord scene,
			SDUtil sdu, BaseRecord chatConfig, BaseRecord clientSdConfig, String priorContext, String promptOverride) {
		String sceneOid = (String) scene.get(FieldNames.FIELD_OBJECT_ID);
		PromptPreStep pre = resolveAndPersistScenePrompt(user, book, scene, chatConfig, priorContext, promptOverride);
		if (pre.sdPrompt == null) {
			return new SceneRenderResult(null, SceneRenderStatus.SKIPPED_NO_PROMPT, pre.llmUnavailable, false,
				PbRenderPath.valueFor(book), null, null, null, null, null, null, false, null);
		}
		String sdPrompt = pre.sdPrompt;
		boolean llmUnavailable = pre.llmUnavailable;
		boolean llmDegraded = pre.llmDegraded;
		/// V3 §24 (P1-9): on the graph path the prompt pre-step above is all this method does for the page;
		/// the image is produced by the page's LANDSCAPE node inside a pb.render job, from the prompt the
		/// facade reads off the scene row. The two paths share the pre-step literally so v3 does the same,
		/// not more. A recovered prompt whose persist failed is the one case where the two disagree.
		if (PbRenderPath.isV3(book)) {
			if (pre.recoveredPersistFailed) {
				logger.warn("renderChapBook: scene {} — recovered prompt was not persisted; the v3 page render uses the stored prompt", sceneOid);
			}
			return renderResolvedSceneV3(user, book, scene, sdu, llmUnavailable, llmDegraded);
		}
		return renderResolvedSceneV1(user, book, scene, sdu, clientSdConfig, sdPrompt, llmUnavailable, llmDegraded);
	}

	/** What the prompt pre-step decided for one scene; {@code sdPrompt == null} means {@code SKIPPED_NO_PROMPT}. */
	private static final class PromptPreStep {
		final String sdPrompt;
		final boolean llmUnavailable;
		final boolean llmDegraded;
		final boolean recoveredPersistFailed;

		PromptPreStep(String sdPrompt, boolean llmUnavailable, boolean llmDegraded, boolean recoveredPersistFailed) {
			this.sdPrompt = sdPrompt;
			this.llmUnavailable = llmUnavailable;
			this.llmDegraded = llmDegraded;
			this.recoveredPersistFailed = recoveredPersistFailed;
		}
	}

	/**
	 * The prompt pre-step shared by both render paths: honor an explicit override, else prefer the stored
	 * continuity prompt, else recover one from the LLM (when a chatConfig is given) and persist it back onto
	 * the scene; decide degrade-render vs. honest skip when the LLM step was unavailable. Performs no SD
	 * call. Extracted verbatim from the v1 render body so the v3 bulk path runs exactly this and nothing
	 * more (V3 §24, P1-9).
	 * <p>
	 * P4-10: a recovered prompt is written to the page composition FIRST (the authority, source LLM, not a
	 * human edit) and then mirrored onto {@code scene.sdPrompt}; {@code book} is what decides whether the
	 * scene has a page composition (a ChapBook) and may be null, in which case only the scene is written.
	 */
	private static PromptPreStep resolveAndPersistScenePrompt(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord chatConfig,
			String priorContext, String promptOverride) {
		String sceneOid = (String) scene.get(FieldNames.FIELD_OBJECT_ID);
		String stanza = scene.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
		String sceneTitle = scene.get(OlioFieldNames.FIELD_PB_TITLE);

		// PREFER the continuity-aware landscape prompt built at scene CREATE time (createChapBookScene),
		// which already carries full cross-scene continuity from assemblePriorContext. Only RE-generate
		// (recovery) when that stored prompt is absent or is the "landscape, " no-LLM fallback shape.
		// resolveScenePrompt is the pure decision; the recovery supplier below is the ONLY place the LLM
		// is called, and resolveScenePrompt invokes it solely in the recovery branch — never when a
		// genuine stored prompt exists. This is the whole point of the fix: the create-time continuity
		// prompt is authoritative on the per-scene render path the Ux client actually hits.
		final String[] recoveredHolder = new String[1];
		// Captures whether the landscape-prompt LLM step HARD-failed (config/infra) during recovery —
		// distinct from a soft blank. The recovery supplier writes into it; we read it after
		// resolveScenePrompt to decide degrade-render vs. honest skip vs. normal un-prompted skip.
		final boolean[] llmHard = new boolean[1];
		Supplier<String> recovery = null;
		if (chatConfig != null && stanza != null && !stanza.isBlank()) {
			recovery = () -> {
				Map<String, String> vars = new LinkedHashMap<>();
				vars.put("stanzaText", stanza);
				vars.put("mood", mood != null ? mood : "poetic");
				vars.put("compositionContext", sceneTitle != null ? sceneTitle : "poetic scene");
				// priorContext must always be non-blank or the UNSUBSTITUTED_PLACEHOLDER guard would refuse
				// the call; assemblePriorContext already returns the "none" sentinel when empty.
				vars.put("priorContext", NarrativeUtil.isMeaningful(priorContext) ? priorContext.trim() : "none");
				String r = callLandscapePrompt(user, chatConfig, vars, llmHard);
				if (r != null && !r.isBlank()) {
					recoveredHolder[0] = r.trim();
					logger.info("renderChapBook: RECOVERED landscape prompt for scene {} (stored was absent/fallback): {}",
						sceneOid, recoveredHolder[0].length() > 80 ? recoveredHolder[0].substring(0, 80) + "…" : recoveredHolder[0]);
				} else {
					logger.warn("renderChapBook: LLM returned no landscape prompt for scene {} during recovery — falling back to stored/stanza", sceneOid);
				}
				return r;
			};
		}
		// An EXPLICIT human-edited prompt override is authoritative and used VERBATIM — short-circuit
		// resolveScenePrompt (and therefore the "landscape, " discriminator) so an edit shaped like
		// "landscape, ..." is honored, never misclassified as the no-LLM fallback and regenerated. When
		// no override is supplied, fall through to the normal prefer-stored-vs-recover resolution.
		String sdPrompt;
		if (promptOverride != null && !promptOverride.isBlank()) {
			sdPrompt = promptOverride.trim();
		} else {
			// P4-10: the page composition is the authority. Its promptOverride/promptLocked are overlaid onto the
			// in-memory scene before the decision so a stale mirror (a refused scene write after a board edit)
			// can neither trigger recovery over a locked composition prompt nor render the wrong text.
			overlayPageComposition(user, book, scene);
			sdPrompt = resolveScenePrompt(scene, stanza, recovery);
		}
		// hardFail is true only when the recovery LLM call was ATTEMPTED and hard-failed (unreachable
		// host, missing config/template, null response). It stays false for a soft blank/decline and for
		// the deliberate no-LLM path (chatConfig == null → recovery never built → never invoked).
		// The landscape-prompt LLM step is "unavailable" for this scene when it HARD-failed during recovery
		// (unreachable host, missing config/template, null response) OR when there was no chat config at all
		// so the recovery supplier was never built — either way the LLM could not produce a prompt for this
		// scene. This is a DOMAIN determination and it belongs HERE in Objects7, not in the REST transport
		// layer: Service7 must be able to emit the resulting llmUnavailable signal as a pure pass-through
		// (architecture.md — no business logic in Service7). hardFail alone is insufficient, because
		// chatConfig == null leaves llmHard[0] false while the LLM step is every bit as unavailable.
		boolean hardFail = llmHard[0];
		boolean llmStepUnavailable = hardFail || (chatConfig == null);
		// llmUnavailable / llmDegraded travel with whatever this render ultimately returns. They are set
		// non-false ONLY on the LLM-unavailable degrade/skip path below; the normal render — a genuine
		// stored prompt, or a soft blank/decline while the LLM WAS reachable — leaves both false.
		boolean llmUnavailable = false;
		boolean llmDegraded = false;
		if (sdPrompt == null || sdPrompt.isBlank()) {
			// A blank sdPrompt means: no genuine stored prompt AND recovery produced nothing. Had a genuine
			// stored prompt existed, resolveScenePrompt would have returned it and we would never reach here
			// — so the PRESERVE invariant (a scene rendering on a genuine stored prompt stays
			// llmUnavailable=false / llmDegraded=false even when chatConfig == null) holds structurally.
			if (llmStepUnavailable) {
				// The landscape LLM genuinely could not run for this scene (hard failure, OR no chat config
				// at all). A silent skip here is indistinguishable from "nothing to render", which is exactly
				// the swallowed-failure the fix targets. If the scene has ANY stored prompt (even the
				// "landscape, " fallback shape), degrade-render on it so the client still gets an image AND a
				// truthful llmDegraded signal to offer a re-render once the LLM is available. With no stored
				// prompt at all, skip but report llmUnavailable so the outcome is never mistaken for a normal
				// un-prompted skip.
				String storedRaw = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
				if (storedRaw != null && !storedRaw.isBlank()) {
					logger.warn("renderChapBook: scene {} — landscape LLM unavailable; degrade-rendering on stored prompt (llmDegraded)", sceneOid);
					sdPrompt = storedRaw.trim();
					llmUnavailable = true;
					llmDegraded = true;
					// fall through to the SD render block below with the degraded prompt
				} else {
					logger.warn("renderChapBook: scene {} — landscape LLM unavailable and no stored prompt to render on; skipping (llmUnavailable)", sceneOid);
					return new PromptPreStep(null, true, false, false);
				}
			} else {
				// The LLM WAS reachable (chatConfig != null) but recovery returned a soft blank/decline — a
				// normal per-stanza conversational refusal, or simply nothing to generate from. This must
				// stay benign: no false alarm.
				logger.info("renderChapBook: scene {} un-prompted (LLM reachable but returned no prompt) — skipping, leaving for regeneration", sceneOid);
				return new PromptPreStep(null, false, false, false);
			}
		}

		// If we RECOVERED a fresh LLM prompt (the stored one was absent/fallback-shaped), persist it back
		// onto the scene AND set it on the in-memory record so the bulk renderChapBook loop's
		// forward-threading (which reads scene.get(FIELD_CB_SD_PROMPT) after this returns, lines ~880-883)
		// carries the recovered imagery forward. recoveredHolder[0] is set only when the recovery supplier
		// actually ran and returned non-blank — i.e. exactly the recovery branch of resolveScenePrompt.
		boolean recovered = recoveredHolder[0] != null && recoveredHolder[0].equals(sdPrompt);
		boolean recoveredPersistFailed = false;
		if (recovered) {
			// P4-10: the page composition is the authority and is written FIRST (source LLM, not a human edit,
			// so promptLocked stays false and userEdited is untouched). The scene mirror is written only after
			// the composition landed: a mirror that runs ahead of a refused authority write would show one text
			// in the UI while every composition-first reader renders another. The render in flight keeps its
			// resolved prompt either way; a refused write is reported as recoveredPersistFailed and retried on
			// the next render.
			boolean compositionLanded = true;
			if (book != null && PbNodeExecutor.isChapBook(book)) {
				try {
					if (writePageComposition(user, book, scene, sdPrompt, false, PbCompositionSourceEnumType.LLM, false) == null) {
						compositionLanded = false;
						logger.warn("renderChapBook: page composition write for the recovered prompt on scene " + sceneOid + " was refused; the scene mirror is not written");
					}
				} catch (RuntimeException e) {
					compositionLanded = false;
					logger.warn("renderChapBook: page composition write for the recovered prompt on scene " + sceneOid + " failed (" + e.getMessage() + "); the scene mirror is not written");
				}
			}
			try {
				scene.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("renderChapBook: could not set recovered sdPrompt on in-memory scene " + sceneOid + ": " + e.getMessage());
			}
			if (!compositionLanded) {
				recoveredPersistFailed = true;
			} else {
				try {
					BaseRecord promptPatch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
						OlioFieldNames.FIELD_CB_SD_PROMPT);
					promptPatch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
					// Do not swallow the result — a null is the only signal the persist failed. Non-fatal to the
					// render (the prompt is already resolved), so log and continue rather than abort the image.
					if (IOSystem.getActiveContext().getAccessPoint().update(user, promptPatch) == null) {
						logger.warn("renderChapBook: failed to persist recovered sdPrompt on scene " + sceneOid);
						recoveredPersistFailed = true;
					}
				} catch (FieldException | ValueException | ModelNotFoundException | PictureBookException e) {
					// patchOf throws the unchecked PictureBookException if name is unprojected; keep this
					// persist-back non-fatal so a failed save never aborts the batch render.
					logger.warn("renderChapBook: could not assemble/persist recovered sdPrompt patch for scene " + sceneOid + ": " + e.getMessage());
					recoveredPersistFailed = true;
				}
			}
		}
		return new PromptPreStep(sdPrompt, llmUnavailable, llmDegraded, recoveredPersistFailed);
	}

	/**
	 * P4-10: overlay the page composition's {@code promptOverride}/{@code promptLocked} onto the in-memory
	 * scene when the book is a ChapBook and the composition carries a meaningful prompt, so the v1 render
	 * path decides from the authority rather than from a possibly stale mirror. The lookup never throws
	 * and never writes; without a composition (or a prompt on it) the scene fields stand as they are.
	 */
	private static void overlayPageComposition(BaseRecord user, BaseRecord book, BaseRecord scene) {
		if (book == null || !PbNodeExecutor.isChapBook(book)) {
			return;
		}
		BaseRecord comp = PbBookUtil.findSceneComposition(user, scene, book);
		if (comp == null) {
			return;
		}
		String compPrompt = (comp.hasField(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE) ? comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE) : null);
		if (!NarrativeUtil.isMeaningful(compPrompt)) {
			return;
		}
		Boolean compLocked = (comp.hasField(OlioFieldNames.FIELD_PB_PROMPT_LOCKED) ? comp.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED) : null);
		try {
			scene.set(OlioFieldNames.FIELD_CB_SD_PROMPT, compPrompt.trim());
			scene.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.valueOf(Boolean.TRUE.equals(compLocked)));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("renderChapBook: could not overlay the page composition prompt onto scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
		}
	}

	/**
	 * The v1 (direct SD) tail of a page render: resolve the effective config, call SD once, and patch the
	 * resulting {@code imageObjectId} onto the scene. Unchanged from before P1-9 - only moved out of
	 * {@link #renderResolvedScene} so the prompt pre-step could be shared with the graph path.
	 */
	private static SceneRenderResult renderResolvedSceneV1(BaseRecord user, BaseRecord book, BaseRecord scene,
			SDUtil sdu, BaseRecord clientSdConfig, String sdPrompt, boolean llmUnavailable, boolean llmDegraded) {
		String sceneOid = (String) scene.get(FieldNames.FIELD_OBJECT_ID);
		String bookGroupPath = book.get(FieldNames.FIELD_GROUP_PATH);
		String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
		if (sceneGroupPath == null) sceneGroupPath = (bookGroupPath != null ? bookGroupPath + "/Scenes" : "~/Scenes");

		try {
			/// PB2 config precedence (§2.4): scene configOverride → client overlay → book sdConfig →
			/// flux2Defaults → Flux2Defaults constants. A ChapBook scene has no sceneNode, so the SCENE
			/// itself is the override carrier — resolveEffectiveConfig duck-types the carrier via
			/// node.hasField(configOverride)/node.get(configOverride), and listScenes() projects that
			/// field (PbBookUtil.sceneRequest). The scene's configOverride stays a sparse JSON string;
			/// it is never materialized from RecordFactory.newInstance(olio.sd.config). The client
			/// overlay (V3 P0-10b) is a tier BELOW the scene override, so a per-page override survives it.
			BaseRecord sdConfig = PbConfigUtil.resolveEffectiveConfig(book, scene, false, clientSdConfig);
			SDUtil.fillStyleDefaults(sdConfig);
			/// V3 P0-9 (G-25): createImage uses `description` verbatim as the positive prompt and never
			/// consults getSDConfigPrompt, so the style clause has to be appended here or the whole
			/// style tier (style/photoStyle/artStyle/… that fillStyleDefaults just completed) is dead
			/// weight on the ChapBook path. applyConfigStyle is idempotent on re-render.
			sdConfig.set("description", SDUtil.applyConfigStyle(sdPrompt, sdConfig));
			String negative = sdConfig.get("negativePrompt");
			if (negative == null || negative.isBlank()) {
				sdConfig.set("negativePrompt", NarrativeUtil.getDefaultNegativePrompt());
			}

			String imageName = "chapbook_" + sceneOid + "_" + System.currentTimeMillis();
			List<BaseRecord> images = sdu.createImage(user, sceneGroupPath, sdConfig, imageName, 1, SDUtil.hiresOf(sdConfig), -1);
			if (images == null || images.isEmpty()) {
				logger.warn("renderChapBook: SD generation returned no images for scene " + sceneOid);
				// SD failed, not the LLM: carry llmUnavailable if the LLM was in fact down, but never
				// llmDegraded (nothing rendered).
				return new SceneRenderResult(null, SceneRenderStatus.FAILED, llmUnavailable, false);
			}
			BaseRecord image = images.get(0);
			String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);

			// Patch imageObjectId onto the scene record using patchOf pattern
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			patch.set(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, imageOid);
			if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.warn("renderChapBook: failed to patch imageObjectId on scene " + sceneOid);
				return new SceneRenderResult(null, SceneRenderStatus.FAILED, llmUnavailable, false);
			}
			logger.info("renderChapBook: rendered scene " + sceneOid);
			return new SceneRenderResult(imageOid, SceneRenderStatus.RENDERED, llmUnavailable, llmDegraded);
		} catch (Exception e) {
			logger.warn("renderChapBook: error rendering scene " + sceneOid + ": " + e.getMessage());
			return new SceneRenderResult(null, SceneRenderStatus.FAILED, llmUnavailable, false);
		}
	}

	// ─────────────────────────────── v3 (graph) render path — V3 §24, P1-9 ───────────────────────────────

	/**
	 * Upper bound of the synchronous wait for a single page's {@code pb.render} job. Below nginx's
	 * {@code proxy_read_timeout} (3600 s) so a caller that waits this long still gets a response - a
	 * {@link SceneRenderStatus#PENDING} result with the job identifiers - instead of a proxy 504 with
	 * nothing in the server log. A bound on the wait, never a judgment on how long a render may take.
	 */
	static final long V3_SYNC_PAGE_WAIT_MS = 30L * 60L * 1000L;

	/** Same bound for the whole-book {@code pb.renderAll} job on the synchronous bulk path. */
	static final long V3_SYNC_BOOK_WAIT_MS = 55L * 60L * 1000L;

	/**
	 * The v3 continuation of {@link #renderResolvedScene}: submit the page's {@code pb.render} job through
	 * {@link PbServiceFacade#requestChapBookPageRender} (which creates the workflow / LANDSCAPE node when
	 * missing, mirrors the scene's prompt and config override onto the node, and refuses a pinned page),
	 * wait for it within {@link #V3_SYNC_PAGE_WAIT_MS}, and read the node and its selected artifact back.
	 * <p>
	 * The facade's 409 for a page with no prompt maps to {@link SceneRenderStatus#SKIPPED_NO_PROMPT} - the
	 * v1 meaning of that outcome. Every other {@link PictureBookException} (pinned 409, 403, 404) propagates:
	 * on the v1 path those conditions did not exist, and silently turning them into {@code FAILED} would
	 * hide the one thing the caller has to act on (unpin first).
	 */
	private static SceneRenderResult renderResolvedSceneV3(BaseRecord user, BaseRecord book, BaseRecord scene,
			SDUtil sdu, boolean llmUnavailable, boolean llmDegraded) {
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		Map<String, Object> submitted;
		try {
			submitted = PbServiceFacade.requestChapBookPageRender(user, bookOid, sceneOid, sdu, sdu.getServer());
		} catch (PictureBookException e) {
			if (e.getStatus() == 409 && e.getMessage() != null && e.getMessage().contains("has no prompt")) {
				logger.info("renderChapBook: scene {} un-prompted on the v3 path — skipping, leaving for regeneration", sceneOid);
				return new SceneRenderResult(null, SceneRenderStatus.SKIPPED_NO_PROMPT, llmUnavailable, false,
					PbRenderPath.V3, null, null, null, null, null, null, false, null);
			}
			throw e;
		}
		String jobId = (String) submitted.get("jobId");
		String runOid = (String) submitted.get("runObjectId");
		String nodeOid = (String) submitted.get("nodeObjectId");
		AsyncJob job = PbRenderJobUtil.awaitTerminal(user, jobId, V3_SYNC_PAGE_WAIT_MS);
		if (job == null) {
			logger.info("renderChapBook: scene {} page render job {} still running after the synchronous bound; returning PENDING", sceneOid, jobId);
			return new SceneRenderResult(null, SceneRenderStatus.PENDING, llmUnavailable, false,
				PbRenderPath.V3, jobId, runOid, nodeOid, null, null, null, false, null);
		}
		return v3PageResult(user, sceneOid, jobId, runOid, nodeOid, job, llmUnavailable, llmDegraded);
	}

	/**
	 * Read a finished page job back into a {@link SceneRenderResult}: the node's status from the node row,
	 * the image / review status from the node's selected {@code landscape} artifact, and the auto-approve
	 * outcome from the job's per-node outcome. {@code RENDERED} requires a selected artifact whose
	 * {@code data} is readable; a node that is not DONE/DONE_UNVERIFIED, or a job that failed before the
	 * stage ran, is {@code FAILED}.
	 */
	private static SceneRenderResult v3PageResult(BaseRecord user, String sceneOid, String jobId, String runOid,
			String nodeOid, AsyncJob job, boolean llmUnavailable, boolean llmDegraded) {
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		Map<?, ?> outcome = v3NodeOutcome(job, nodeOid);
		boolean autoApproved = outcome != null && Boolean.TRUE.equals(outcome.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		Object skipReason = (outcome != null ? outcome.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED) : null);
		BaseRecord node = (nodeOid != null ? PbGraphUtil.readNode(user, nodeOid, orgId) : null);
		Object rawNodeStatus = (node != null ? node.get(OlioFieldNames.FIELD_PB_NODE_STATUS) : null);
		String nodeStatus = (rawNodeStatus != null ? rawNodeStatus.toString() : null);
		BaseRecord artifact = (node != null ? PbArtifactUtil.findSelected(user, node, PbPipelineUtil.ROLE_LANDSCAPE) : null);
		String artifactOid = (artifact != null ? artifact.get(FieldNames.FIELD_OBJECT_ID) : null);
		String reviewStatus = (artifact != null ? PbArtifactUtil.reviewStatusOf(artifact).name() : null);
		BaseRecord data = (artifact != null ? artifact.get(OlioFieldNames.FIELD_PB_DATA) : null);
		String imageOid = (data != null ? data.get(FieldNames.FIELD_OBJECT_ID) : null);
		boolean done = nodeStatus != null && (PbNodeStatusEnumType.DONE.name().equalsIgnoreCase(nodeStatus)
			|| PbNodeStatusEnumType.DONE_UNVERIFIED.name().equalsIgnoreCase(nodeStatus));
		boolean jobOk = job.getStatus() == AsyncJob.Status.COMPLETED;
		if (jobOk && done && imageOid != null) {
			logger.info("renderChapBook: rendered scene {} on the v3 path (job {}, node {}, artifact {}, review {})",
				sceneOid, jobId, nodeOid, artifactOid, reviewStatus);
			return new SceneRenderResult(imageOid, SceneRenderStatus.RENDERED, llmUnavailable, llmDegraded,
				PbRenderPath.V3, jobId, runOid, nodeOid, artifactOid, nodeStatus, reviewStatus, autoApproved,
				(skipReason != null ? String.valueOf(skipReason) : null));
		}
		String why = (outcome != null && outcome.get("error") != null ? String.valueOf(outcome.get("error"))
			: (job.getError() != null ? job.getError() : "job " + job.getStatus() + ", node " + nodeStatus));
		logger.warn("renderChapBook: v3 page render of scene {} did not produce an image: {}", sceneOid, why);
		return new SceneRenderResult(null, SceneRenderStatus.FAILED, llmUnavailable, false,
			PbRenderPath.V3, jobId, runOid, nodeOid, artifactOid, nodeStatus, reviewStatus, false,
			(skipReason != null ? String.valueOf(skipReason) : null));
	}

	/** The job result's per-node outcome for {@code nodeOid} ({@code result.nodes[]}), or null. */
	private static Map<?, ?> v3NodeOutcome(AsyncJob job, String nodeOid) {
		Map<?, ?> result = v3JobResult(job);
		if (result == null || nodeOid == null || !(result.get("nodes") instanceof List)) {
			return null;
		}
		for (Object o : (List<?>) result.get("nodes")) {
			if (o instanceof Map && nodeOid.equals(((Map<?, ?>) o).get("nodeObjectId"))) {
				return (Map<?, ?>) o;
			}
		}
		return null;
	}

	/** The job's result JSON as a map, or null when the job has none (failed before it produced one). */
	private static Map<?, ?> v3JobResult(AsyncJob job) {
		if (job == null || job.getResult() == null || job.getResult().isBlank()) {
			return null;
		}
		return JSONUtil.importObject(job.getResult(), Map.class);
	}

	private static int v3Count(Map<?, ?> result, String key) {
		Object v = (result != null ? result.get(key) : null);
		return (v instanceof Number ? ((Number) v).intValue() : 0);
	}

	/**
	 * The v3 bulk render: run the v1 prompt pre-step over every scene on the calling thread - the same
	 * per-poem continuity thread, stored-vs-recover resolution and recovered-prompt persist the v1 loop
	 * performs, minus the SD call - then submit one {@code pb.renderAll} job for every prompted page through
	 * {@link PbServiceFacade#requestChapBookRenderAll} (one job per book, never one per page).
	 * <p>
	 * With {@code detach} (an asynchronous caller, or a caller already inside a registry job - which must
	 * never await another job) the summary returns as soon as the job is submitted, {@code pending=true}
	 * with its identifiers. Otherwise the job is awaited within {@link #V3_SYNC_BOOK_WAIT_MS} and the counts
	 * come from its result; a wait that reaches the bound also returns {@code pending=true}.
	 * <p>
	 * The planner's 409 ("every node is current / pinned") propagates. On the v1 path a bulk render of
	 * all-pinned pages did not exist; here it is the honest answer and the caller sees it as a 409.
	 */
	private static ChapBookRenderSummary renderChapBookSummaryV3(BaseRecord user, BaseRecord book, List<BaseRecord> scenes,
			SDUtil sdu, BaseRecord chatConfig, boolean detach) {
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		int skipped = 0;
		int llmUnavailable = 0;
		List<String> priorScenePrompts = new ArrayList<>();
		String priorSceneTitle = null;
		for (BaseRecord scene : scenes) {
			String sceneTitle = scene.get(OlioFieldNames.FIELD_PB_TITLE);
			if (priorSceneTitle != null && !java.util.Objects.equals(priorSceneTitle, sceneTitle)) {
				priorScenePrompts.clear();
			}
			priorSceneTitle = sceneTitle;
			String sceneMood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
			String priorContext = assemblePriorContext(null, null, sceneMood, priorScenePrompts);
			PromptPreStep pre = resolveAndPersistScenePrompt(user, book, scene, chatConfig, priorContext, null);
			String storedPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			Boolean sceneLockedFlag = scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
			boolean sceneLocked = (sceneLockedFlag != null && sceneLockedFlag.booleanValue());
			if (isGenuineStoredPrompt(storedPrompt) || (sceneLocked && NarrativeUtil.isMeaningful(storedPrompt))) {
				priorScenePrompts.add(storedPrompt);
			}
			if (pre.llmUnavailable) {
				llmUnavailable++;
			}
			if (pre.sdPrompt == null) {
				skipped++;
			}
		}
		Map<String, Object> submitted;
		try {
			submitted = PbServiceFacade.requestChapBookRenderAll(user, bookOid, sdu, sdu.getServer());
		} catch (PictureBookException e) {
			if (e.getStatus() == 409 && e.getMessage() != null && e.getMessage().contains("has a prompt")) {
				// Every page is un-prompted: v1 answered this with rendered=0 / skipped=N, not an error.
				logger.info("renderChapBook: no page of book {} has a prompt; nothing submitted on the v3 path ({} skipped)", bookOid, skipped);
				return new ChapBookRenderSummary(0, skipped, llmUnavailable, PbRenderPath.V3, null, null, 0, false, 0);
			}
			throw e;
		}
		String jobId = (String) submitted.get("jobId");
		String runOid = (String) submitted.get("runObjectId");
		int planned = (submitted.get("plannedNodeCount") instanceof Number ? ((Number) submitted.get("plannedNodeCount")).intValue() : 0);
		if (detach) {
			logger.info("renderChapBook: v3 bulk render of book {} submitted as job {} (run {}, {} page(s) planned); caller detached",
				bookOid, jobId, runOid, planned);
			return new ChapBookRenderSummary(0, skipped, llmUnavailable, PbRenderPath.V3, jobId, runOid, 0, true, planned);
		}
		AsyncJob job = PbRenderJobUtil.awaitTerminal(user, jobId, V3_SYNC_BOOK_WAIT_MS);
		if (job == null) {
			logger.info("renderChapBook: v3 bulk render job {} of book {} still running after the synchronous bound; returning pending", jobId, bookOid);
			return new ChapBookRenderSummary(0, skipped, llmUnavailable, PbRenderPath.V3, jobId, runOid, 0, true, planned);
		}
		Map<?, ?> result = v3JobResult(job);
		int rendered = v3Count(result, "executedNodeCount");
		int failed = v3Count(result, "failedNodeCount");
		if (job.getStatus() != AsyncJob.Status.COMPLETED) {
			logger.warn("renderChapBook: v3 bulk render job {} of book {} ended {}: {}", jobId, bookOid, job.getStatus(),
				(result != null && result.get("error") != null ? result.get("error") : job.getError()));
		}
		logger.info("renderChapBook: v3 rendered {}/{} page(s) for book {} ({} un-prompted, skipped; {} failed; {} llm-unavailable; job {})",
			rendered, scenes.size(), bookOid, skipped, failed, llmUnavailable, jobId);
		return new ChapBookRenderSummary(rendered, skipped, llmUnavailable, PbRenderPath.V3, jobId, runOid, failed, false, planned);
	}

	/**
	 * Decide which SD prompt a ChapBook scene render should use, WITHOUT performing the SD call — the
	 * pure resolution seam extracted from {@link #renderResolvedScene} so the prefer-stored-vs-recover
	 * decision is deterministically unit-testable (no live LLM/SD required to prove which branch fires).
	 * <p>
	 * Resolution order:
	 * <ol>
	 *   <li><b>Honor an explicit human-edit lock.</b> When the scene's
	 *       {@link OlioFieldNames#FIELD_PB_PROMPT_LOCKED promptLocked} marker is true and the stored
	 *       {@code sdPrompt} is {@link NarrativeUtil#isMeaningful(String) meaningful}, the stored value is
	 *       authoritative and returned VERBATIM regardless of text shape — a human edit shaped like the
	 *       {@code "landscape, "} fallback is NOT regenerated. {@code recovery} is NOT invoked. This
	 *       replaces the old string-shape provenance inference (which discarded such edits).</li>
	 *   <li><b>Prefer the stored continuity prompt.</b> When the scene's stored {@code sdPrompt}
	 *       ({@link OlioFieldNames#FIELD_CB_SD_PROMPT}) is a genuine LLM prompt —
	 *       {@code NarrativeUtil.isMeaningful(stored) && !stored.trim().startsWith("landscape, ")} — it is
	 *       authoritative (it already carries the full create-time cross-scene continuity built by
	 *       {@link #assemblePriorContext}) and is returned verbatim. {@code recovery} is NOT invoked.</li>
	 *   <li><b>Recovery.</b> Otherwise, if {@code recovery} is non-null and {@code stanza} is present, call
	 *       it (the LLM landscape-prompt supplier) and, when it returns non-blank, use that.</li>
	 * </ol>
	 * When both steps fail this method returns {@code null} — the scene is <b>un-prompted</b>. A null return
	 * means the render MUST skip and produce no image: never the {@code "landscape, "} fallback-shaped stored
	 * string, and never the raw {@code stanza}. The un-prompted scene is deliberately left for explicit
	 * regeneration later (heavier fallback behaviour: an un-prompted scene stays image-less rather than
	 * getting a low-quality stand-in image).
	 * <p>
	 * The {@code "landscape, "} discriminator is exactly the one the bulk {@link #renderChapBook}
	 * forward-thread uses (line ~881) to skip fallback-shaped prompts — this method stays consistent with it.
	 * <p>
	 * Public so the decision is directly unit-testable from the test package (mirrors
	 * {@link #assemblePriorContext}); it performs no SD/network work of its own.
	 *
	 * @param scene    the scene record (must carry {@link OlioFieldNames#FIELD_CB_SD_PROMPT} in its projection)
	 * @param stanza   the scene's poem stanza (used only to decide whether recovery can run; never used as a
	 *                 render prompt itself)
	 * @param recovery a supplier that regenerates the landscape prompt via the LLM; may be null. Invoked at
	 *                 most once and ONLY in the recovery branch (never when a genuine stored prompt exists).
	 * @return the chosen genuine prompt string, or {@code null} when the scene is un-prompted (render must
	 *         skip and produce no image)
	 */
	public static String resolveScenePrompt(BaseRecord scene, String stanza, Supplier<String> recovery) {
		String stored = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		// 0. EXPLICIT human-edit lock is authoritative — honor it VERBATIM regardless of text shape. A
		//    human edit shaped like the "landscape, " fallback must NOT be regenerated: the promptLocked
		//    marker (set by setSceneLandscapePrompt) records provenance explicitly, so string shape no
		//    longer decides it. recovery is NOT invoked.
		Boolean lockedFlag = scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		boolean promptLocked = (lockedFlag != null && lockedFlag.booleanValue());
		if (promptLocked && NarrativeUtil.isMeaningful(stored)) {
			return stored.trim();
		}
		// 1. Prefer the authoritative create-time continuity prompt — no LLM call.
		if (isGenuineStoredPrompt(stored)) {
			return stored.trim();
		}
		// 2. RECOVERY: stored is absent or fallback-shaped — regenerate via the LLM if we can.
		if (recovery != null && stanza != null && !stanza.isBlank()) {
			String recovered = recovery.get();
			if (recovered != null && !recovered.isBlank()) {
				return recovered.trim();
			}
		}
		// 3. No genuine LLM prompt is available → un-prompted. Return null so the render SKIPS and produces
		//    NO image (never the "landscape, " fallback-shaped stored string, never the raw stanza). The
		//    scene is left for explicit regeneration later.
		return null;
	}

	/**
	 * Persist a human-edited landscape SD prompt onto a ChapBook scene, VERBATIM.
	 * <p>
	 * A human edit is authoritative: unlike the create/render paths, the value is NOT run through
	 * {@link #isGenuineStoredPrompt} / the {@code "landscape, "} discriminator (that discriminator only
	 * distinguishes the auto no-LLM fallback from genuine LLM output — it must not veto a deliberate
	 * human edit). The value is trimmed; a null/blank value CLEARS the field (persists null).
	 * <p>
	 * Authorized and PATCH-shaped as the REQUEST USER (scene writes as the request user are correct and
	 * already in use on the render path): identity + the model's validated {@code name} + the changed
	 * field only, via {@link PbGraphUtil#patchOf} (so the writer's validation of the patch record itself
	 * does not reject a null {@code name}), through {@code AccessPoint.update}. The update result is
	 * asserted, never discarded — a swallowed null there would turn a persistent failure into a silent
	 * no-op (mirrors {@link PbBookUtil#setSceneConfigOverride}).
	 *
	 * <b>The page composition is the authority; the scene is its mirror (V3 §24, P4-10).</b> The write lands on
	 * the page's {@code CHAPBOOK_PAGE} composition FIRST, then is mirrored onto {@code scene.sdPrompt} /
	 * {@code scene.promptLocked}, then the page node is marked STALE. In-process form: a partial write
	 * (composition landed, scene mirror refused) is a 500 so a caller such as {@link #renderChapBookScene}
	 * never renders a page whose two copies disagree. The transport form is
	 * {@link #setSceneLandscapePromptResult}. A scene of a story book is a 409 - its prompt is edited on the
	 * composition board, never through this entry point.
	 *
	 * @param user          the acting user (must have WRITE access to the scene)
	 * @param sceneObjectId objectId of the {@code olio.pb.scene}
	 * @param sdPrompt      the human-edited landscape prompt; null/blank clears the field
	 * @return true when the page composition and the scene mirror were updated; false when the authorized
	 *         composition update returned null (logged; nothing was written)
	 * @throws PictureBookException 400 when args are missing, 404 when the scene is not readable by
	 *         {@code user}, 409 when the scene belongs to a story book, 500 when the patch could not be
	 *         assembled or when the composition was written but the scene mirror was not (the message says
	 *         so; the page node is already STALE)
	 */
	public static boolean setSceneLandscapePrompt(BaseRecord user, String sceneObjectId, String sdPrompt)
			throws PictureBookException {
		PromptWriteResult result = setSceneLandscapePromptResult(user, sceneObjectId, sdPrompt);
		if (result.isPartial()) {
			throw new PictureBookException(500, result.getError());
		}
		return result.isUpdated();
	}

	/**
	 * What a page-prompt write landed where, for the transport layer to report as one body instead of a
	 * status. <b>The page composition is the authority; the scene is its mirror (V3 §24, P4-10)</b>, so
	 * {@code updated} is the composition write ({@code compositionUpdated} is kept as its alias for existing
	 * consumers), {@code sceneUpdated} is the scene mirror, and a partial write ({@code updated} without
	 * {@code sceneUpdated}) carries the refusal in {@code error} with the composition already ahead of the
	 * scene and the page node STALE. Nothing-written ({@code updated:false}) means the composition write was
	 * refused and the scene was not touched.
	 */
	public static final class PromptWriteResult {
		private final String prompt;
		private final boolean locked;
		private final boolean updated;
		private final boolean sceneUpdated;
		private final String compositionObjectId;
		private final String error;

		private PromptWriteResult(String prompt, boolean locked, boolean updated, boolean sceneUpdated,
				String compositionObjectId, String error) {
			this.prompt = prompt;
			this.locked = locked;
			this.updated = updated;
			this.sceneUpdated = sceneUpdated;
			this.compositionObjectId = compositionObjectId;
			this.error = error;
		}

		static PromptWriteResult nothingWritten(String prompt, boolean locked) {
			return new PromptWriteResult(prompt, locked, false, false, null, null);
		}

		static PromptWriteResult partial(String prompt, boolean locked, String compositionObjectId, String error) {
			return new PromptWriteResult(prompt, locked, true, false, compositionObjectId, error);
		}

		static PromptWriteResult complete(String prompt, boolean locked, String compositionObjectId) {
			return new PromptWriteResult(prompt, locked, true, true, compositionObjectId, null);
		}

		public String getPrompt() { return prompt; }
		public boolean isLocked() { return locked; }
		/** True when the page composition (the authority) was written. */
		public boolean isUpdated() { return updated; }
		/** Alias of {@link #isUpdated()}: the composition is what "updated" means since P4-10. */
		public boolean isCompositionUpdated() { return updated; }
		/** True when the scene mirror ({@code sdPrompt} / {@code promptLocked}) matches the composition on return. */
		public boolean isSceneUpdated() { return sceneUpdated; }
		public String getCompositionObjectId() { return compositionObjectId; }
		public String getError() { return error; }
		public boolean isPartial() { return updated && !sceneUpdated; }

		/** The wire shape; {@code compositionObjectId} and {@code error} are present only when they have a value. */
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("updated", Boolean.valueOf(updated));
			m.put("compositionUpdated", Boolean.valueOf(updated));
			m.put("sceneUpdated", Boolean.valueOf(sceneUpdated));
			m.put("promptLocked", Boolean.valueOf(locked));
			if (compositionObjectId != null) {
				m.put("compositionObjectId", compositionObjectId);
			}
			if (error != null) {
				m.put("error", error);
			}
			return m;
		}
	}

	/**
	 * The scene's book, which must be a ChapBook for the page-prompt entry points (P4-10): a story scene's
	 * prompt lives on its composition board and is edited there.
	 *
	 * @throws PictureBookException 500 when the scene has no readable book; 409 when the book is a story book
	 */
	private static BaseRecord requireChapBookOfScene(BaseRecord user, BaseRecord scene, long orgId) {
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord bookRef = (scene.hasField(OlioFieldNames.FIELD_PB_BOOK) ? scene.get(OlioFieldNames.FIELD_PB_BOOK) : null);
		String bookOid = (bookRef != null ? bookRef.get(FieldNames.FIELD_OBJECT_ID) : null);
		BaseRecord book = (bookOid != null ? PbBookUtil.readBook(user, bookOid, orgId) : null);
		if (book == null) {
			throw new PictureBookException(500, "Scene " + sceneOid + " has no readable book");
		}
		if (!PbNodeExecutor.isChapBook(book)) {
			throw new PictureBookException(409, "Scene " + sceneOid + " belongs to a story book; edit its composition board instead");
		}
		return book;
	}

	/**
	 * Transport form of {@link #setSceneLandscapePrompt}: the same write, but a partial write is reported on
	 * the result ({@code updated:true, sceneUpdated:false, error}) instead of thrown, so the route can
	 * answer 200 with a body that says exactly what landed. Argument, lookup, story-book and patch-assembly
	 * failures are still thrown (400/404/409/500) — nothing was written in those cases.
	 */
	public static PromptWriteResult setSceneLandscapePromptResult(BaseRecord user, String sceneObjectId, String sdPrompt)
			throws PictureBookException {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, orgId);
		if (scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		BaseRecord book = requireChapBookOfScene(user, scene, orgId);
		// Sanitize (strip null/C0 control bytes PostgreSQL rejects, normalize CRLF), then trim; treat
		// null/blank/control-only as CLEAR. Persist VERBATIM otherwise — no isGenuineStoredPrompt
		// discriminator. sanitizeText mirrors the poem-import path so a prompt pasted from a document does
		// not fail the write with a quiet invalid-byte-sequence rejection.
		String value = sanitizeText(sdPrompt);
		if (value != null) {
			value = value.trim();
			if (value.isEmpty()) value = null;
		}
		// Explicit provenance: a real (non-blank) human edit LOCKS the prompt so the render path honors it
		// verbatim regardless of text shape; a CLEAR (null/blank) unlocks it so the fallback/LLM prompt is
		// free to be regenerated again. This replaces the old "landscape, " string-shape inference.
		boolean locked = (value != null);
		// P4-10: the page's CHAPBOOK_PAGE composition is the authority and is written FIRST (design §24/§27).
		// A human edit is USER-sourced and marks the composition userEdited. A refused write means nothing
		// was written; a structural failure (no group, assembly) is thrown - nothing was written either.
		BaseRecord comp = writePageComposition(user, book, scene, value, locked, PbCompositionSourceEnumType.USER, true);
		if (comp == null) {
			logger.warn("Failed to persist the landscape prompt on the page composition of scene " + sceneObjectId);
			return PromptWriteResult.nothingWritten(value, locked);
		}
		String compOid = comp.get(FieldNames.FIELD_OBJECT_ID);
		// The scene is the composition's mirror: sdPrompt / promptLocked are brought in step as the acting
		// user. A refused mirror is a partial write reported on the result - the composition, which every
		// engine reader consults first, already carries the prompt - never a plain false, which would read
		// as "nothing written".
		boolean sceneOk = PbBookUtil.mirrorCompositionOntoScene(user, scene, comp, true, false);
		// The page is new from this point whether or not the mirror landed; the node mirror reads the
		// composition first, so a refused scene write still leaves the page reviewable with the new prompt.
		PbBookUtil.markSceneNodeStale(user, scene);
		if (!sceneOk) {
			String msg = "Page composition " + compOid + " persisted but scene " + sceneObjectId + " sdPrompt was not: the scene mirror write was refused";
			logger.error(msg);
			return PromptWriteResult.partial(value, locked, compOid, msg);
		}
		return PromptWriteResult.complete(value, locked, compOid);
	}

	/**
	 * Write a page prompt onto the page's {@code CHAPBOOK_PAGE} composition - the authority since P4-10 -
	 * created on first write through {@link PbComposeUtil#getCreatePageComposition}: {@code promptOverride},
	 * {@code promptLocked}, {@code source}, {@code revision + 1}, and {@code userEdited = true} for a human
	 * edit (an LLM regenerate leaves {@code userEdited} as it was). As the acting user through
	 * {@code AccessPoint}; the update result is asserted. The returned record carries the new values in
	 * memory so {@link PbBookUtil#mirrorCompositionOntoScene} can mirror them without a re-read.
	 *
	 * @return the composition with the new values set, or null when the authorized update was refused
	 *         (logged; nothing was written)
	 * @throws PictureBookException 409 when the book has no group (from {@code getCreatePageComposition});
	 *         500 when the patch could not be assembled
	 */
	private static BaseRecord writePageComposition(BaseRecord user, BaseRecord book, BaseRecord scene, String prompt, boolean locked,
			PbCompositionSourceEnumType source, boolean humanEdit) {
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		if (book == null) {
			throw new PictureBookException(500, "Scene " + sceneOid + " has no readable book");
		}
		BaseRecord comp = PbComposeUtil.getCreatePageComposition(user, book, scene, source);
		Object rev = comp.get(OlioFieldNames.FIELD_PB_REVISION);
		int next = (rev instanceof Number ? ((Number) rev).intValue() : 0) + 1;
		List<String> fields = new ArrayList<>(Arrays.asList(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE,
			OlioFieldNames.FIELD_PB_PROMPT_LOCKED, OlioFieldNames.FIELD_PB_SOURCE, OlioFieldNames.FIELD_PB_REVISION));
		if (humanEdit) {
			fields.add(OlioFieldNames.FIELD_PB_USER_EDITED);
		}
		BaseRecord patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, fields.toArray(new String[0]));
		try {
			patch.set(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, prompt);
			patch.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, locked);
			patch.set(OlioFieldNames.FIELD_PB_SOURCE, source.toString());
			patch.set(OlioFieldNames.FIELD_PB_REVISION, next);
			if (humanEdit) {
				patch.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			}
		}
		catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a page composition patch: " + e.getMessage());
		}
		if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to persist page composition " + comp.get(FieldNames.FIELD_OBJECT_ID) + " for scene " + sceneOid);
			return null;
		}
		PbStaleUtil.touch(comp);
		// Carry the written values on the returned record so the scene mirror reads them without a re-read.
		try {
			comp.set(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, prompt);
			comp.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, locked);
			comp.set(OlioFieldNames.FIELD_PB_SOURCE, source.toString());
			comp.set(OlioFieldNames.FIELD_PB_REVISION, next);
			if (humanEdit) {
				comp.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			}
		}
		catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("Page composition " + comp.get(FieldNames.FIELD_OBJECT_ID) + " was written but its in-memory copy could not be updated: " + e.getMessage());
		}
		return comp;
	}

	/**
	 * Regenerate ONE ChapBook scene's landscape SD prompt from its own {@code poemStanza} + {@code mood}
	 * via the {@code chapBook.landscape-prompt} LLM (prompt-only — NO SD image is rendered), persist the
	 * fresh prompt, and set {@code promptLocked=false}.
	 * <p>
	 * This is an EXPLICIT user request, so it deliberately OVERRIDES any existing prompt — including a
	 * previously LOCKED (human-edited) one: the user asked for a regeneration. The freshly generated value
	 * is LLM-authored, not a human edit, so {@code promptLocked} is left {@code false} (the render path is
	 * then free to regenerate it again, and a later human edit re-locks it via
	 * {@link #setSceneLandscapePrompt}).
	 * <p>
	 * Uses the shared {@link #generateLandscapePrompt} LLM step (the same one {@link #createChapBookScene}
	 * uses), so a regenerated prompt is identical in shape to a create-time prompt. There is no create-time
	 * stanza-title fallback here: an explicit regenerate that cannot produce a GENUINE LLM prompt is a hard
	 * failure (500) rather than silently downgrading to the fallback shape.
	 * <p>
	 * Persistence is PATCH-shaped and authorized AS THE ACTING USER: identity + the validated {@code name}
	 * + the changed fields only (via {@link PbGraphUtil#patchOf}), through {@code AccessPoint.update}. The
	 * update result is asserted, never discarded. <b>The page composition is the authority; the scene is its
	 * mirror (V3 §24, P4-10)</b>: the fresh prompt lands on the page's {@code CHAPBOOK_PAGE} composition
	 * first (source LLM, unlocked), then is mirrored onto {@code scene.sdPrompt} / {@code scene.promptLocked},
	 * then the page node is marked STALE. A scene of a story book is a 409 - its prompt is regenerated by the
	 * compose job, never through this entry point.
	 *
	 * @param user          the acting user (must have WRITE access to the scene)
	 * @param sceneObjectId objectId of the {@code olio.pb.scene} to regenerate a prompt for
	 * @param chatConfig    the resolved {@code olio.llm.chatConfig} (the transport layer resolves the org
	 *                      default and returns 503 when none exists, mirroring {@link #analyzePoemTheme})
	 * In-process form: a partial write (composition landed, scene mirror refused) is a 500. The transport
	 * form is {@link #regenerateSceneLandscapePromptResult}.
	 *
	 * @return the new landscape prompt that was persisted
	 * @throws PictureBookException 400 for missing args or a scene with no stanza; 404 when the scene is
	 *         not readable; 409 when the scene belongs to a story book; 503 when {@code chatConfig} is null;
	 *         500 when the LLM produced no usable prompt, the patch failed, or the composition was written
	 *         but the scene mirror was not
	 */
	public static String regenerateSceneLandscapePrompt(BaseRecord user, String sceneObjectId, BaseRecord chatConfig) {
		PromptWriteResult result = regenerateSceneLandscapePromptResult(user, sceneObjectId, chatConfig);
		if (result.isPartial()) {
			throw new PictureBookException(500, result.getError());
		}
		return result.getPrompt();
	}

	/**
	 * Transport form of {@link #regenerateSceneLandscapePrompt}: the same LLM step and the same writes, but a
	 * refused scene-mirror write is reported on the result ({@code updated:true, sceneUpdated:false, error})
	 * instead of thrown. The result's {@code prompt} is the persisted LLM prompt and {@code locked} is always
	 * false. Every failure before the composition write (args, lookup, story book, no stanza, no chatConfig,
	 * no usable LLM prompt) is still thrown — nothing was written in those cases; a refused composition write
	 * is {@code updated:false} (nothing written).
	 */
	public static PromptWriteResult regenerateSceneLandscapePromptResult(BaseRecord user, String sceneObjectId, BaseRecord chatConfig) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		if (chatConfig == null) {
			throw new PictureBookException(503, "No chatConfig is configured for this organization");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, orgId);
		if (scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		// P4-10: a story scene is refused before the LLM is called - nothing is spent on a write that would
		// be refused.
		BaseRecord book = requireChapBookOfScene(user, scene, orgId);
		String stanza = scene.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		if (stanza == null || stanza.isBlank()) {
			throw new PictureBookException(400, "Scene has no poemStanza to regenerate a prompt from: " + sceneObjectId);
		}
		String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
		String title = scene.get(OlioFieldNames.FIELD_PB_TITLE);
		// priorContext is not threaded on an explicit single-scene regenerate — the "none" sentinel keeps
		// the template's {priorContext} placeholder substituted.
		String newPrompt = generateLandscapePrompt(user, chatConfig, stanza, mood, title, "none");
		if (newPrompt == null || newPrompt.isBlank()) {
			throw new PictureBookException(500,
				"The landscape-prompt LLM did not produce a usable prompt for scene " + sceneObjectId);
		}
		// P4-10: the page composition is the authority and is written FIRST - the regenerated prompt is
		// LLM-sourced, UNLOCKED (overrides any prior locked human prompt), userEdited untouched. A refused
		// write means nothing was written.
		BaseRecord comp = writePageComposition(user, book, scene, newPrompt, false, PbCompositionSourceEnumType.LLM, false);
		if (comp == null) {
			logger.warn("Failed to persist the regenerated prompt on the page composition of scene " + sceneObjectId);
			return PromptWriteResult.nothingWritten(newPrompt, false);
		}
		String compOid = comp.get(FieldNames.FIELD_OBJECT_ID);
		// The scene is the composition's mirror: sdPrompt / promptLocked brought in step as the acting user.
		boolean sceneOk = PbBookUtil.mirrorCompositionOntoScene(user, scene, comp, true, false);
		// The page is new from this point whether or not the mirror landed; the node mirror reads the
		// composition first, so a refused scene write still leaves the page reviewable with the new prompt.
		PbBookUtil.markSceneNodeStale(user, scene);
		if (!sceneOk) {
			String msg = "Page composition " + compOid + " persisted but scene " + sceneObjectId + " sdPrompt was not: the scene mirror write was refused";
			logger.error(msg);
			return PromptWriteResult.partial(newPrompt, false, compOid, msg);
		}
		return PromptWriteResult.complete(newPrompt, false, compOid);
	}

	/**
	 * Load an {@code olio.cb.poem} by objectId with the fields needed for stanza chunking
	 * and scene creation.
	 */
	private static BaseRecord loadPoem(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			"title", "author", OlioFieldNames.FIELD_CB_THEME,
			OlioFieldNames.FIELD_CB_MOOD, OlioFieldNames.FIELD_CB_KEYWORDS,
			"text"
		});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}
}
