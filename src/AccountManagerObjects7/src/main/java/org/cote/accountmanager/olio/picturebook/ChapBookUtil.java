package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;

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
		// Split on one or more blank lines (blank = only whitespace)
		String[] stanzas = poemText.split("(?m)^\\s*$\\n?");
		for (String stanza : stanzas) {
			String trimmed = stanza.trim();
			if (trimmed.isEmpty()) continue;
			String[] lines = trimmed.split("\\r?\\n");
			if (lines.length <= maxLinesPerPage) {
				chunks.add(trimmed);
			} else {
				// Further split by maxLinesPerPage
				List<String> lineList = new ArrayList<>();
				for (String line : lines) lineList.add(line);
				for (int i = 0; i < lineList.size(); i += maxLinesPerPage) {
					int end = Math.min(i + maxLinesPerPage, lineList.size());
					chunks.add(String.join("\n", lineList.subList(i, end)));
				}
			}
		}
		return chunks;
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

		// Patch bookType = CHAPBOOK
		try {
			BaseRecord typePatch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK,
				OlioFieldNames.FIELD_PB_BOOK_TYPE);
			typePatch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
			if (IOSystem.getActiveContext().getAccessPoint().update(user, typePatch) == null) {
				logger.warn("createChapBook: failed to set bookType CHAPBOOK on book " + slug);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("createChapBook: could not patch bookType: " + e.getMessage());
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
					createChapBookScene(user, book, sceneIndex, chunk, poemTitle, mood, bookGroupPath);
					sceneIndex++;
				} catch (Exception e) {
					logger.warn("createChapBook: failed to create scene " + sceneIndex + " from poem " + poemObjectId + ": " + e.getMessage());
				}
			}
		}
		logger.info("createChapBook: created {} scenes for slug={}", sceneIndex, slug);
		return book;
	}

	/**
	 * Create one {@code olio.pb.scene} for a stanza chunk.
	 * <p>
	 * Uses {@link PbBookUtil#createScene} for the base record, then patches {@code poemStanza},
	 * {@code mood}, and {@code title} onto the returned scene.
	 */
	static BaseRecord createChapBookScene(BaseRecord user, BaseRecord book, int sceneIndex,
			String stanzaText, String poemTitle, String mood, String bookGroupPath) {
		// Create the base scene record
		BaseRecord scene = PbBookUtil.createScene(user, book, sceneIndex, poemTitle, bookGroupPath);
		if (scene == null) {
			throw new PictureBookException(500, "Failed to create ChapBook scene at index " + sceneIndex);
		}
		// Patch poemStanza, mood, title, and sdPrompt onto the scene
		try {
			String sdPromptVal = "landscape, " + (poemTitle != null ? poemTitle : "poetic scene") + ", "
				+ (mood != null ? mood : "poetic") + " atmosphere, painterly, soft light";
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_CB_POEM_STANZA, OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_PB_TITLE,
				OlioFieldNames.FIELD_CB_SD_PROMPT);
			if (stanzaText != null) patch.set(OlioFieldNames.FIELD_CB_POEM_STANZA, stanzaText);
			if (mood != null) patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
			if (poemTitle != null) patch.set(OlioFieldNames.FIELD_PB_TITLE, poemTitle);
			patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, sdPromptVal);
			if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.warn("createChapBookScene: failed to patch stanza/mood/sdPrompt onto scene " + sceneIndex);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("createChapBookScene: patch field error at index " + sceneIndex + ": " + e.getMessage());
		}
		return scene;
	}

	// ─────────────────────────────── ChapBook rendering ───────────────────────────────

	/**
	 * Generate SD images for all scenes of a CHAPBOOK-typed {@code olio.pb.book}.
	 * Delegates to {@link #renderChapBook(BaseRecord, String, String, String, BaseRecord)} with no chatConfig.
	 */
	public static int renderChapBook(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer) {
		return renderChapBook(user, bookObjectId, sdApiType, sdServer, null);
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
			String sdApiType, String sdServer, BaseRecord chatConfig) {
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
				logger.warn("renderChapBook: scene " + sceneOid + " has no sdPrompt — skipping");
				continue;
			}
			String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
			if (sceneGroupPath == null) sceneGroupPath = (bookGroupPath != null ? bookGroupPath + "/Scenes" : "~/Scenes");

			try {
				BaseRecord sdConfig = SDUtil.randomSDConfig();
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
					logger.info("renderChapBook: rendered scene " + sceneOid);
				}
			} catch (Exception e) {
				logger.warn("renderChapBook: error rendering scene " + sceneOid + ": " + e.getMessage());
			}
		}
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
