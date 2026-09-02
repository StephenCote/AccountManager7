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
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookProgressNotifier;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;

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
	 * </ul>
	 */
	public enum SceneRenderStatus { RENDERED, SKIPPED_NO_PROMPT, FAILED }

	/**
	 * Immutable result of a single scene render. {@link #imageObjectId} is non-null only when
	 * {@link #status} is {@link SceneRenderStatus#RENDERED}; it is null for both {@code SKIPPED_NO_PROMPT}
	 * and {@code FAILED}.
	 */
	public static final class SceneRenderResult {
		/** The generated image {@code objectId}; non-null only when {@link #status} is {@code RENDERED}. */
		public final String imageObjectId;
		/** The render outcome. */
		public final SceneRenderStatus status;

		public SceneRenderResult(String imageObjectId, SceneRenderStatus status) {
			this.imageObjectId = imageObjectId;
			this.status = status;
		}
	}

	/**
	 * The single discriminator for "is this stored {@code sdPrompt} a GENUINE LLM landscape prompt?"
	 * used by the create path, the per-scene render path, and the bulk forward-threading alike. A
	 * stored prompt is genuine only when it is {@link NarrativeUtil#isMeaningful(String) meaningful}
	 * (so an LLM-emitted literal {@code "null"}/{@code "none"}/{@code "n/a"}/{@code "unknown"} is
	 * rejected) AND is not the {@code "landscape, "} no-LLM fallback shape.
	 * <p>
	 * Single-sourcing this matters for cross-layer consistency: the create path stores a genuine
	 * prompt only when this returns true and otherwise stores the {@code "landscape, "} fallback, so
	 * the ONLY "un-prompted" markers ever persisted are blank or {@code "landscape, "}-shaped. That is
	 * exactly what the Ux client's {@code isSceneUnprompted} keys on, so the two layers cannot drift
	 * (e.g. the backend skipping a scene the client would never flag for regeneration).
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
	 *   <li>Binary office formats (.doc/.docx/.rtf) — extract plain text via Apache Tika.</li>
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
			String effectiveCt = ct;
			if (effectiveCt == null || effectiveCt.isEmpty() || effectiveCt.startsWith("text/")) {
				String sniffed = DocumentUtil.sniffOfficeContentType(bytes);
				if (sniffed != null) {
					effectiveCt = sniffed;
				}
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
			+ "formats (.doc, .docx, .rtf) are supported");
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
	 * Return the first {@code olio.llm.chatConfig} record accessible to the user in their
	 * organization, or {@code null} when none is configured.
	 * <p>
	 * Uses {@code AccessPoint.find} (not {@code list}) to enforce per-record PBAC authorization.
	 * The query is scoped to the user's organization and owner to avoid returning configs that
	 * belong to other users in the same org.
	 *
	 * @param user the acting user (must be non-null and have a valid organizationId)
	 * @return first accessible chatConfig record, or null
	 */
	public static BaseRecord resolveDefaultChatConfig(BaseRecord user) {
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
			Map<String, String> vars = new LinkedHashMap<>();
			vars.put("poemText", poemText);
			String llmResult = PictureBookUtil.callLlmForChapBook(user, chatConfig, "chapBook.poem-analysis", vars);
			if (llmResult == null || llmResult.isBlank()) {
				logger.warn("analyzePoemTheme: LLM returned no result");
				return;
			}
			Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObjectForChapBook(llmResult, "poem-analysis", new ArrayList<>());
			String theme = guardNull(parsed.get("theme"));
			String mood = guardNull(parsed.get("mood"));
			String keywords = guardNull(parsed.get("keywords"));

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
		if (slug == null || slug.isBlank()) throw new PictureBookException(400, "slug is required for ChapBook creation");
		if (title == null || title.isBlank()) throw new PictureBookException(400, "title is required for ChapBook creation");
		if (poemObjectIds == null || poemObjectIds.isEmpty()) throw new PictureBookException(400, "at least one poem is required");

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		int effectiveMax = (maxLinesPerPage > 0) ? maxLinesPerPage : 8;

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
		// Running thread of prior scene landscape prompts, in book order, so scene N's LLM prompt is
		// generated with awareness of the poem's overall theme and the imagery already used earlier in
		// the book (the "prior mcp entries"). Only genuinely LLM-generated prompts are threaded forward.
		List<String> priorScenePrompts = new ArrayList<>();
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
			String poemTitle = poem.get(OlioFieldNames.FIELD_PB_TITLE);
			if (poemTitle == null) poemTitle = poem.get(FieldNames.FIELD_NAME);
			String mood = poem.get(OlioFieldNames.FIELD_CB_MOOD);
			String theme = poem.get(OlioFieldNames.FIELD_CB_THEME);
			String keywords = poem.get(OlioFieldNames.FIELD_CB_KEYWORDS);

			List<String> chunks = chunkPoem(poemText, effectiveMax);
			for (String chunk : chunks) {
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
				} catch (Exception e) {
					logger.warn("createChapBook: failed to create scene " + sceneIndex + " from poem " + poemObjectId + ": " + e.getMessage());
				}
			}
		}
		logger.info("createChapBook: created {} scenes for slug={}", sceneIndex, slug);

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
		String llmPrompt = null;
		// Patch poemStanza, mood, title, and sdPrompt onto the scene.
		// Use RecordUtil.updateRecord (bypass PBAC) — consistent with how createChapBook patches bookType.
		try {
			// Use LLM to generate a landscape SD prompt from the stanza when chatConfig is available.
			// This mirrors the pattern renderChapBook uses at render time (chapBook.landscape-prompt
			// template with stanzaText/mood/compositionContext/priorContext vars), so the stored sdPrompt
			// is an LLM-generated landscape description rather than a raw stanza excerpt.
			// Falls back to the stanza-excerpt placeholder when the LLM is not configured or returns blank.
			String sdPromptVal = null;
			if (chatConfig != null && stanzaText != null && !stanzaText.isBlank()) {
				Map<String, String> vars = new LinkedHashMap<>();
				vars.put("stanzaText", stanzaText);
				vars.put("mood", mood != null ? mood : "poetic");
				vars.put("compositionContext", poemTitle != null ? poemTitle : "poetic scene");
				// priorContext must always be non-blank or the UNSUBSTITUTED_PLACEHOLDER guard in
				// callLlmInternal would refuse the call; assemblePriorContext returns "none" when empty.
				vars.put("priorContext", NarrativeUtil.isMeaningful(priorContext) ? priorContext.trim() : "none");
				String llmResult = callLandscapePrompt(user, chatConfig, vars);
				// Store the LLM result only when it is a GENUINE prompt (isGenuineStoredPrompt): a bare
				// LLM sentinel like "none"/"null" or an accidentally "landscape, "-shaped reply must NOT be
				// persisted as a real prompt, or the render path would skip the scene while the client's
				// isSceneUnprompted (which only recognises blank / "landscape, ") would fail to flag it for
				// regeneration. Falling through to the fallback stores the "landscape, " discriminator both
				// layers agree on.
				if (isGenuineStoredPrompt(llmResult)) {
					sdPromptVal = llmResult.trim();
					llmPrompt = sdPromptVal;
					logger.info("createChapBookScene: LLM landscape prompt for scene {}: {}", sceneIndex,
						sdPromptVal.length() > 80 ? sdPromptVal.substring(0, 80) + "…" : sdPromptVal);
				} else {
					logger.warn("createChapBookScene: LLM returned no usable landscape prompt for scene {} — using landscape fallback", sceneIndex);
				}
			}
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
	private static String callLandscapePrompt(BaseRecord user, BaseRecord chatConfig, Map<String, String> vars) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			String llmResult = PictureBookUtil.callLlmForChapBook(user, chatConfig, "chapBook.landscape-prompt", vars);
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
	 * is not a CHAPBOOK, and <b>403</b> when PBAC denies the delete (canDelete is checked explicitly so
	 * a denial surfaces as 403 rather than a bare {@code AccessPoint.delete} {@code false}, which the
	 * transport layer would otherwise map to 500) — mirroring {@link #renderChapBook}. The boolean
	 * return distinguishes only a successful delete (true) from a genuine persistence failure (false)
	 * on a valid, authorized CHAPBOOK.
	 *
	 * @param user         the acting user
	 * @param bookObjectId objectId of the book to delete
	 * @return true if deleted, false if the delete itself failed on a valid, authorized CHAPBOOK
	 * @throws PictureBookException 400 for missing args, 404 if not found, 403 if not a CHAPBOOK or the delete is not authorized
	 */
	public static boolean deleteChapBook(BaseRecord user, String bookObjectId) {
		if (user == null || bookObjectId == null || bookObjectId.isBlank()) {
			throw new PictureBookException(400, "user and bookObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgId);
		if (book == null) {
			throw new PictureBookException(404, "ChapBook not found: " + bookObjectId);
		}
		String bookType = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		if (bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
			throw new PictureBookException(403, "Book " + bookObjectId + " is not a CHAPBOOK");
		}
		// Explicit PBAC check so a delete denial maps to 403, not a false-return the transport reads as 500.
		// AccessPoint.delete re-checks canDelete; the double check is intentional (correctness over a saved eval).
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canDelete(user, user, book);
		if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not authorized to delete ChapBook: " + bookObjectId);
		}
		return IOSystem.getActiveContext().getAccessPoint().delete(user, book);
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

		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
		if (scenes.isEmpty()) {
			logger.info("renderChapBook: no scenes for book {}", bookObjectId);
			return 0;
		}
		PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_stories", "ChapBook render: " + scenes.size() + " scene(s)…");

		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType.toUpperCase()), sdServer);

		int rendered = 0;
		int skipped = 0;
		// Running thread of the scenes' stored landscape prompts, in book order, so a re-generated
		// render-time prompt for scene N stays visually continuous with the imagery of earlier scenes.
		List<String> priorScenePrompts = new ArrayList<>();
		for (BaseRecord scene : scenes) {
			String sceneMood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
			String priorContext = assemblePriorContext(null, null, sceneMood, priorScenePrompts);
			SceneRenderResult result = renderResolvedScene(user, book, scene, sdu, chatConfig, clientSdConfig, priorContext);
			// Thread the scene's stored landscape prompt forward for continuity — but skip the
			// stanza-excerpt fallback shape ("landscape, …") so only real imagery accumulates.
			String storedPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			if (isGenuineStoredPrompt(storedPrompt)) {
				priorScenePrompts.add(storedPrompt);
			}
			if (result.status == SceneRenderStatus.RENDERED) {
				rendered++;
				PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Scene " + rendered + "/" + scenes.size() + " rendered");
			} else if (result.status == SceneRenderStatus.SKIPPED_NO_PROMPT) {
				skipped++;
			}
		}
		PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
		logger.info("renderChapBook: rendered {}/{} scenes for book {} ({} un-prompted, skipped)", rendered, scenes.size(), bookObjectId, skipped);
		return rendered;
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
	 * @return a {@link SceneRenderResult}: {@link SceneRenderStatus#RENDERED} (with the generated image
	 *         {@code objectId}) on success; {@link SceneRenderStatus#SKIPPED_NO_PROMPT} (imageObjectId null)
	 *         when the scene is un-prompted (no genuine LLM prompt available — no image produced, left for
	 *         explicit regeneration); {@link SceneRenderStatus#FAILED} (imageObjectId null) when SD returned
	 *         nothing, the patch failed, or an exception was caught
	 * @throws PictureBookException 400 for missing args / non-CHAPBOOK book, 404 if the scene or its
	 *         book is not found, 500 if the SD server is not configured
	 */
	public static SceneRenderResult renderChapBookScene(BaseRecord user, String sceneObjectId,
			String sdApiType, String sdServer, BaseRecord chatConfig, BaseRecord clientSdConfig) {
		if (user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		if (sdApiType == null || sdServer == null) {
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

		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType.toUpperCase()), sdServer);
		// Single-scene render: the client drives one scene at a time, so the full running thread of
		// sibling scenes is not reconstructed here (that would require loading and ordering every scene
		// of the book on each per-scene call). The continuity context is derived from THIS scene's mood.
		// DESIGN FORK: cross-scene continuity for the per-scene path would need an ordered scene load;
		// left minimal by design — the bulk renderChapBook path carries the full running thread.
		String sceneMood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
		String priorContext = assemblePriorContext(null, null, sceneMood, null);
		return renderResolvedScene(user, book, scene, sdu, chatConfig, clientSdConfig, priorContext);
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
			SDUtil sdu, BaseRecord chatConfig, BaseRecord clientSdConfig, String priorContext) {
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
				String r = callLandscapePrompt(user, chatConfig, vars);
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
		String sdPrompt = resolveScenePrompt(scene, stanza, recovery);
		if (sdPrompt == null || sdPrompt.isBlank()) {
			logger.info("renderChapBook: scene {} un-prompted (no genuine LLM prompt) — skipping, leaving for regeneration", sceneOid);
			return new SceneRenderResult(null, SceneRenderStatus.SKIPPED_NO_PROMPT);
		}

		// If we RECOVERED a fresh LLM prompt (the stored one was absent/fallback-shaped), persist it back
		// onto the scene AND set it on the in-memory record so the bulk renderChapBook loop's
		// forward-threading (which reads scene.get(FIELD_CB_SD_PROMPT) after this returns, lines ~880-883)
		// carries the recovered imagery forward. recoveredHolder[0] is set only when the recovery supplier
		// actually ran and returned non-blank — i.e. exactly the recovery branch of resolveScenePrompt.
		boolean recovered = recoveredHolder[0] != null && recoveredHolder[0].equals(sdPrompt);
		if (recovered) {
			try {
				scene.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("renderChapBook: could not set recovered sdPrompt on in-memory scene " + sceneOid + ": " + e.getMessage());
			}
			try {
				BaseRecord promptPatch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
					OlioFieldNames.FIELD_CB_SD_PROMPT);
				promptPatch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
				// Do not swallow the result — a null is the only signal the persist failed. Non-fatal to the
				// render (the prompt is already resolved), so log and continue rather than abort the image.
				if (IOSystem.getActiveContext().getAccessPoint().update(user, promptPatch) == null) {
					logger.warn("renderChapBook: failed to persist recovered sdPrompt on scene " + sceneOid);
				}
			} catch (FieldException | ValueException | ModelNotFoundException | PictureBookException e) {
				// patchOf throws the unchecked PictureBookException if name is unprojected; keep this
				// persist-back non-fatal so a failed save never aborts the batch render.
				logger.warn("renderChapBook: could not assemble/persist recovered sdPrompt patch for scene " + sceneOid + ": " + e.getMessage());
			}
		}
		String bookGroupPath = book.get(FieldNames.FIELD_GROUP_PATH);
		String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
		if (sceneGroupPath == null) sceneGroupPath = (bookGroupPath != null ? bookGroupPath + "/Scenes" : "~/Scenes");

		try {
			/// PB2 config precedence (§2.4): scene configOverride → book sdConfig → flux2Defaults →
			/// Flux2Defaults constants. A ChapBook scene has no sceneNode, so the SCENE itself is the
			/// override carrier — resolveEffectiveConfig duck-types the carrier via
			/// node.hasField(configOverride)/node.get(configOverride), and listScenes() projects that
			/// field (PbBookUtil.sceneRequest). The scene's configOverride stays a sparse JSON string;
			/// it is never materialized from RecordFactory.newInstance(olio.sd.config).
			BaseRecord sdConfig = PbConfigUtil.resolveEffectiveConfig(book, scene, false);
			if (clientSdConfig != null) {
				SDUtil.applyOverrides(sdConfig, clientSdConfig);
			}
			SDUtil.fillStyleDefaults(sdConfig);
			sdConfig.set("description", sdPrompt);

			String imageName = "chapbook_" + sceneOid + "_" + System.currentTimeMillis();
			List<BaseRecord> images = sdu.createImage(user, sceneGroupPath, sdConfig, imageName, 1, false, -1);
			if (images == null || images.isEmpty()) {
				logger.warn("renderChapBook: SD generation returned no images for scene " + sceneOid);
				return new SceneRenderResult(null, SceneRenderStatus.FAILED);
			}
			BaseRecord image = images.get(0);
			String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);

			// Patch imageObjectId onto the scene record using patchOf pattern
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			patch.set(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, imageOid);
			if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.warn("renderChapBook: failed to patch imageObjectId on scene " + sceneOid);
				return new SceneRenderResult(null, SceneRenderStatus.FAILED);
			}
			logger.info("renderChapBook: rendered scene " + sceneOid);
			return new SceneRenderResult(imageOid, SceneRenderStatus.RENDERED);
		} catch (Exception e) {
			logger.warn("renderChapBook: error rendering scene " + sceneOid + ": " + e.getMessage());
			return new SceneRenderResult(null, SceneRenderStatus.FAILED);
		}
	}

	/**
	 * Decide which SD prompt a ChapBook scene render should use, WITHOUT performing the SD call — the
	 * pure resolution seam extracted from {@link #renderResolvedScene} so the prefer-stored-vs-recover
	 * decision is deterministically unit-testable (no live LLM/SD required to prove which branch fires).
	 * <p>
	 * Resolution order:
	 * <ol>
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
