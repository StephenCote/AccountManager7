package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
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
			if (ct == null || ct.isEmpty() || ct.startsWith("text/")) {
				return sanitizeText(ByteModelUtil.getValueString(data));
			}
			if (DocumentUtil.OFFICE_CONTENT_TYPES.contains(ct)) {
				byte[] bytes = ByteModelUtil.getValue(data);
				if (bytes == null || bytes.length == 0) return null;
				// Pass the known content type so the 3-arg overload routes .doc to POI
				// and provides a Tika content-type hint for .docx and WordPerfect.
				// Bounded extraction (MAX_EXTRACT_CHARS) — do not let Tika accumulate unbounded output.
				String extracted = DocumentUtil.readDocument(bytes, MAX_EXTRACT_CHARS, ct);
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

			List<String> chunks = chunkPoem(poemText, effectiveMax);
			for (String chunk : chunks) {
				try {
					createChapBookScene(user, book, sceneIndex, chunk, poemTitle, mood, bookGroupPath, chatConfig);
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
	 * called with the stanza text, mood, and poem title as variables, exactly as
	 * {@link #renderChapBook} does at render time. This stores an LLM-generated landscape
	 * prompt on the scene at creation, so the render step has a meaningful starting point even
	 * without a live LLM. Falls back to a stanza-excerpt placeholder when the LLM returns blank.
	 */
	static BaseRecord createChapBookScene(BaseRecord user, BaseRecord book, int sceneIndex,
			String stanzaText, String poemTitle, String mood, String bookGroupPath, BaseRecord chatConfig) {
		// Create the base scene record
		BaseRecord scene = PbBookUtil.createScene(user, book, sceneIndex, poemTitle, bookGroupPath);
		if (scene == null) {
			throw new PictureBookException(500, "Failed to create ChapBook scene at index " + sceneIndex);
		}
		// Patch poemStanza, mood, title, and sdPrompt onto the scene.
		// Use RecordUtil.updateRecord (bypass PBAC) — consistent with how createChapBook patches bookType.
		try {
			// Use LLM to generate a landscape SD prompt from the stanza when chatConfig is available.
			// This mirrors the pattern renderChapBook uses at render time (chapBook.landscape-prompt
			// template with stanzaText/mood/compositionContext vars), so the stored sdPrompt is an
			// LLM-generated landscape description rather than a raw stanza excerpt.
			// Falls back to the stanza-excerpt placeholder when the LLM is not configured or returns blank.
			String sdPromptVal = null;
			if (chatConfig != null && stanzaText != null && !stanzaText.isBlank()) {
				Map<String, String> vars = new LinkedHashMap<>();
				vars.put("stanzaText", stanzaText);
				vars.put("mood", mood != null ? mood : "poetic");
				vars.put("compositionContext", poemTitle != null ? poemTitle : "poetic scene");
				String llmResult = PictureBookUtil.callLlmForChapBook(user, chatConfig, "chapBook.landscape-prompt", vars);
				if (llmResult != null && !llmResult.isBlank()) {
					sdPromptVal = llmResult.trim();
					logger.info("createChapBookScene: LLM landscape prompt for scene {}: {}", sceneIndex,
						sdPromptVal.length() > 80 ? sdPromptVal.substring(0, 80) + "…" : sdPromptVal);
				} else {
					logger.warn("createChapBookScene: LLM returned no landscape prompt for scene {} — using stanza-excerpt fallback", sceneIndex);
				}
			}
			if (sdPromptVal == null || sdPromptVal.isBlank()) {
				// No-LLM fallback: use the first ~150 chars of stanza text for a meaningful placeholder
				String stanzaExcerpt = (stanzaText != null && !stanzaText.isBlank())
					? stanzaText.substring(0, Math.min(150, stanzaText.length())).replaceAll("\\s+", " ").trim()
					: null;
				sdPromptVal = "landscape, "
					+ (stanzaExcerpt != null ? stanzaExcerpt + ", " : (poemTitle != null ? poemTitle + ", " : "poetic scene, "))
					+ (mood != null ? mood : "poetic") + " atmosphere, painterly, soft light";
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
		return scene;
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
	 * Verifies the book has {@code bookType=CHAPBOOK} before deleting — returns {@code false}
	 * if the book is not found or is not a ChapBook (callers should 404/403 accordingly).
	 *
	 * @param user         the acting user
	 * @param bookObjectId objectId of the book to delete
	 * @return true if deleted, false if not found or not a CHAPBOOK
	 */
	public static boolean deleteChapBook(BaseRecord user, String bookObjectId) {
		if (user == null || bookObjectId == null || bookObjectId.isBlank()) return false;
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgId);
		if (book == null) return false;
		String bookType = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		if (bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
			logger.warn("deleteChapBook: book {} is not a CHAPBOOK (type={})", bookObjectId, bookType);
			return false;
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
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_BOOK_TYPE, OlioFieldNames.FIELD_PB_WORLD
		});
		bq.setCache(false);
		BaseRecord book = IOSystem.getActiveContext().getAccessPoint().find(user, bq);
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

		String bookGroupPath = book.get(FieldNames.FIELD_GROUP_PATH);
		SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType.toUpperCase()), sdServer);

		int rendered = 0;
		for (BaseRecord scene : scenes) {
			String sceneOid = (String) scene.get(FieldNames.FIELD_OBJECT_ID);
			String stanza = scene.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
			String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
			String sceneTitle = scene.get(OlioFieldNames.FIELD_PB_TITLE);

			// Use LLM to generate a landscape prompt from the stanza when chatConfig is available.
			// Falls back to the stored sdPrompt template when the LLM is not configured or returns blank.
			String sdPrompt = null;
			if (chatConfig != null && stanza != null && !stanza.isBlank()) {
				Map<String, String> vars = new LinkedHashMap<>();
				vars.put("stanzaText", stanza);
				vars.put("mood", mood != null ? mood : "poetic");
				vars.put("compositionContext", sceneTitle != null ? sceneTitle : "poetic scene");
				String llmResult = PictureBookUtil.callLlmForChapBook(user, chatConfig, "chapBook.landscape-prompt", vars);
				if (llmResult != null && !llmResult.isBlank()) {
					sdPrompt = llmResult.trim();
					logger.info("renderChapBook: LLM landscape prompt for scene {}: {}", sceneOid, sdPrompt.length() > 80 ? sdPrompt.substring(0, 80) + "…" : sdPrompt);
				} else {
					logger.warn("renderChapBook: LLM returned no landscape prompt for scene {} — falling back to stored sdPrompt", sceneOid);
				}
			}
			if (sdPrompt == null || sdPrompt.isBlank()) {
				sdPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			}
			if (sdPrompt == null || sdPrompt.isBlank()) {
				sdPrompt = stanza; // poemStanza fallback for freshly-created ChapBooks
			}
			if (sdPrompt == null || sdPrompt.isBlank()) {
				logger.warn("renderChapBook: scene {} has no sdPrompt or stanza — skipping", sceneOid);
				continue;
			}
			String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
			if (sceneGroupPath == null) sceneGroupPath = (bookGroupPath != null ? bookGroupPath + "/Scenes" : "~/Scenes");

			try {
				BaseRecord sdConfig = SDUtil.randomSDConfig();
				if (clientSdConfig != null) {
					SDUtil.applyOverrides(sdConfig, clientSdConfig);
				}
				SDUtil.fillStyleDefaults(sdConfig);
				sdConfig.set("description", sdPrompt);

				String imageName = "chapbook_" + sceneOid + "_" + System.currentTimeMillis();
				List<BaseRecord> images = sdu.createImage(user, sceneGroupPath, sdConfig, imageName, 1, false, -1);
				if (images == null || images.isEmpty()) {
					logger.warn("renderChapBook: SD generation returned no images for scene " + sceneOid);
					continue;
				}
				BaseRecord image = images.get(0);
				String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);

				// Patch imageObjectId onto the scene record using patchOf pattern
				BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
					OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
				patch.set(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, imageOid);
				if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
					logger.warn("renderChapBook: failed to patch imageObjectId on scene " + sceneOid);
				} else {
					rendered++;
					PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Scene " + rendered + "/" + scenes.size() + " rendered");
					logger.info("renderChapBook: rendered scene " + sceneOid);
				}
			} catch (Exception e) {
				logger.warn("renderChapBook: error rendering scene " + sceneOid + ": " + e.getMessage());
			}
		}
		PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
		logger.info("renderChapBook: rendered {}/{} scenes for book {}", rendered, scenes.size(), bookObjectId);
		return rendered;
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
