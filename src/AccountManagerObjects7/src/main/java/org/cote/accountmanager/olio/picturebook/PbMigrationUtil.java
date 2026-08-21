package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Import a PB1 book (an {@code auth.group} + {@code data.note} JSON under
 * {@code ~/Data/PictureBooks/}) into the PB2 schema
 * ({@code olio.pb.book}, {@code olio.pb.scene}, {@code olio.pb.workflow}).
 * <p>
 * <b>PB1 records are not modified.</b> Every read here is find-only against PB1 state. Scenes are
 * imported as PB2 rows with their text fields (title, blurb, summary, setting, action, mood)
 * populated from the per-scene {@code data.note} JSON.  The workflow graph skeleton ({@code olio.pb.workflow},
 * {@code olio.pb.node}) is created by the normal {@code PbBookUtil.createBook} path — nodes will
 * be {@code PENDING} until the next pipeline run re-generates their artifacts.
 * <p>
 * <b>Exit criterion (Phase 6):</b> {@code TestPbMigration} passes on a real existing PB1 book;
 * the PB1 {@code auth.group} and {@code .pictureBookMeta} note are untouched afterward.
 */
public class PbMigrationUtil {
	public static final Logger logger = LogManager.getLogger(PbMigrationUtil.class);

	/** PB1 meta note name — a string literal in {@code PictureBookUtil}, named here for migration use. */
	public static final String V1_META_NOTE_NAME = ".pictureBookMeta";

	private PbMigrationUtil() {
		// static utility
	}

	// ─────────────────────────────── result ───────────────────────────────

	/** Summary returned to the caller after a migration completes. */
	public static final class ImportResult {
		public final String slug;
		public final String bookObjectId;
		public final int scenesImported;
		public final int scenesFailed;
		public final List<String> warnings;

		ImportResult(String slug, String bookObjectId, int scenesImported, int scenesFailed,
				List<String> warnings) {
			this.slug = slug;
			this.bookObjectId = bookObjectId;
			this.scenesImported = scenesImported;
			this.scenesFailed = scenesFailed;
			this.warnings = Collections.unmodifiableList(warnings);
		}

		@Override
		public String toString() {
			return "ImportResult{slug='" + slug + "', scenes=" + scenesImported + "/"
				+ (scenesImported + scenesFailed) + ", warnings=" + warnings.size() + "}";
		}
	}

	// ─────────────────────────────── main entry point ───────────────────────────────

	/**
	 * Import a PB1 book into PB2.
	 * <p>
	 * Creates a PB2 {@code olio.pb.book} (and its Olio world, grants, and workflow) for the given
	 * PB1 group, then creates one {@code olio.pb.scene} row per scene in the PB1
	 * {@code .pictureBookMeta}, with text fields copied from the per-scene {@code data.note} JSON.
	 * PB1 records ({@code auth.group}, {@code data.note}) are <b>not modified</b>.
	 *
	 * @param user              the acting user (must have read access to the PB1 group)
	 * @param dataPath          the Olio data path ({@code test.datagen.path} / deployment config)
	 * @param bookGroupObjectId the {@code objectId} of the PB1 {@code auth.group}
	 * @return an {@link ImportResult} with counts and any per-scene warnings
	 * @throws PictureBookException 404 if the group or meta note is absent or unreadable,
	 *                              400 if the slug cannot be derived or the meta cannot be parsed,
	 *                              409 if a PB2 book with this slug already exists
	 */
	public static ImportResult importV1Book(BaseRecord user, String dataPath, String bookGroupObjectId) {
		// 1. Find the PB1 book group
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(user, bookGroupObjectId);
		if(bookGroup == null) {
			throw new PictureBookException(404,
				"PB1 book group '" + bookGroupObjectId + "' not found or not accessible");
		}
		String groupName = bookGroup.get(FieldNames.FIELD_NAME);
		String groupPath = bookGroup.get(FieldNames.FIELD_PATH);
		long orgId = ((Number) bookGroup.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// 2. Derive slug from the PB1 group name
		String slug = PbPipelineUtil.deriveSlug(groupName);
		if(slug == null) {
			throw new PictureBookException(400,
				"Cannot derive a valid PB2 slug from PB1 book name '" + groupName + "'");
		}

		// 3. Load the PB1 meta note (read-only — PB1 is never written here)
		BaseRecord metaNote = loadV1MetaNote(user, groupPath, orgId);
		if(metaNote == null) {
			throw new PictureBookException(404,
				"No " + V1_META_NOTE_NAME + " note found in PB1 book '" + groupName + "'");
		}
		String metaJson = metaNote.get("text");
		if(metaJson == null || metaJson.isEmpty()) {
			throw new PictureBookException(400,
				"PB1 meta note for '" + groupName + "' has no content");
		}

		// 4. Parse the meta JSON into a typed olio.pictureBookMeta record
		BaseRecord meta = null;
		try {
			meta = JSONUtil.importObject(metaJson, LooseRecord.class,
				RecordDeserializerConfig.getUnfilteredModule());
		}
		catch(Exception e) {
			throw new PictureBookException(400,
				"Failed to parse PB1 meta JSON for '" + groupName + "': " + e.getMessage());
		}
		if(meta == null) {
			throw new PictureBookException(400, "PB1 meta for '" + groupName + "' parsed as null");
		}

		// 5. Extract title (fall back to group name if workName absent or blank)
		String workName = meta.get("workName");
		String title = (workName != null && !workName.isBlank()) ? workName : groupName;

		// 6. Guard against a duplicate slug before creating the world
		if(PbBookUtil.findBookBySlug(user, slug, orgId) != null) {
			throw new PictureBookException(409,
				"A PB2 book with slug '" + slug + "' already exists."
				+ " Delete or rename the existing book before re-importing.");
		}

		// 7. Create the PB2 book — world, grants, workflow group skeleton — via the canonical path
		BaseRecord book = PbBookUtil.createBook(user, dataPath, slug, title);
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);

		// 8. Patch compositionContext and sdConfig from the meta into the PB2 book
		List<String> warnings = new ArrayList<>();
		patchBookMetaFields(user, book, meta, warnings);

		// 9. Import scenes
		String sceneGroupPath = PbBookUtil.bookGroupPath(slug);
		List<BaseRecord> sceneList = extractMetaScenes(meta);
		int imported = 0;
		int failed = 0;
		for(int i = 0; i < sceneList.size(); i++) {
			BaseRecord pbScene = sceneList.get(i);
			try {
				importScene(user, book, sceneGroupPath, i, pbScene, orgId, warnings);
				imported++;
			}
			catch(Exception e) {
				String msg = "Scene " + i + " (" + pbScene.get(FieldNames.FIELD_OBJECT_ID) + "): "
					+ e.getMessage();
				logger.warn("PB1 migration: scene skipped — {}", msg);
				warnings.add(msg);
				failed++;
			}
		}

		// 10. Mark the book extracted when at least one scene imported (content ready, generation pending)
		if(imported > 0) {
			PbBookUtil.setBookStatus(user, book, PbBookStatusEnumType.EXTRACTED);
		}

		logger.info("PB1 migration complete: '{}' → slug '{}': {}/{} scenes, {} warning(s)",
			groupName, slug, imported, sceneList.size(), warnings.size());
		return new ImportResult(slug, bookObjectId, imported, failed, warnings);
	}

	// ─────────────────────────────── private helpers ───────────────────────────────

	/**
	 * Load the {@code .pictureBookMeta} {@code data.note} from the given group path.
	 * <p>
	 * Replicates the private {@code loadMeta} pattern in {@code PictureBookUtil}, read-only —
	 * the PB1 record is found via {@code AccessPoint.find} and never written.
	 */
	private static BaseRecord loadV1MetaNote(BaseRecord user, String groupPath, long orgId) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		if(grp == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
			FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, V1_META_NOTE_NAME);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Patch {@code compositionContext}, {@code sdConfig}, and {@code compositeSdConfig} from the
	 * PB1 meta onto the freshly-created PB2 book.
	 * <p>
	 * All three are optional — absent or null fields in the meta are silently skipped.
	 */
	private static void patchBookMetaFields(BaseRecord user, BaseRecord book, BaseRecord meta,
			List<String> warnings) {
		String compositionContext = meta.get(OlioFieldNames.FIELD_PB_COMPOSITION_CONTEXT);
		if(compositionContext != null && !compositionContext.isBlank()) {
			BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK,
				OlioFieldNames.FIELD_PB_COMPOSITION_CONTEXT);
			try {
				patch.set(OlioFieldNames.FIELD_PB_COMPOSITION_CONTEXT, compositionContext);
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				warnings.add("compositionContext patch error: " + e.getMessage());
				return;
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				warnings.add("compositionContext patch was not persisted");
			}
		}

		// sdConfig and compositeSdConfig are model fields — pass the BaseRecord directly
		patchModelConfigField(user, book, meta, OlioFieldNames.FIELD_PB_SD_CONFIG, warnings);
		patchModelConfigField(user, book, meta, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG, warnings);
	}

	private static void patchModelConfigField(BaseRecord user, BaseRecord book, BaseRecord meta,
			String fieldName, List<String> warnings) {
		Object raw = null;
		try {
			raw = meta.get(fieldName);
		}
		catch(Exception e) {
			// field absent in this meta version — not a warning
			return;
		}
		if(raw == null) {
			return;
		}
		BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK, fieldName);
		try {
			patch.set(fieldName, raw);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			warnings.add(fieldName + " patch error: " + e.getMessage());
			return;
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			warnings.add(fieldName + " patch was not persisted");
		}
	}

	/**
	 * Import one PB1 scene into PB2.
	 * <p>
	 * The {@code olio.pictureBookScene} from the meta list carries the scene note's
	 * {@code objectId}; the note's {@code text} JSON carries the actual content fields.
	 */
	private static void importScene(BaseRecord user, BaseRecord book, String sceneGroupPath,
			int sceneIndex, BaseRecord pbScene, long orgId, List<String> warnings) {
		String sceneNoteOid = pbScene.get(FieldNames.FIELD_OBJECT_ID);
		if(sceneNoteOid == null || sceneNoteOid.isBlank()) {
			throw new PictureBookException(400, "meta scene entry at index " + sceneIndex + " has no objectId");
		}

		// Load the PB1 scene note — read-only
		Query nq = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
			FieldNames.FIELD_OBJECT_ID, sceneNoteOid);
		nq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		nq.planMost(true);
		BaseRecord sceneNote = IOSystem.getActiveContext().getAccessPoint().find(user, nq);
		if(sceneNote == null) {
			throw new PictureBookException(404, "scene note " + sceneNoteOid + " not found or not accessible");
		}

		// Parse the scene note text JSON as a raw map — it is not a typed record
		String noteText = sceneNote.get("text");
		Map<String, Object> sceneData = null;
		if(noteText != null && !noteText.isEmpty()) {
			sceneData = JSONUtil.getMap(noteText.getBytes(StandardCharsets.UTF_8),
				String.class, Object.class);
		}

		// Title: prefer the LLM-extracted "title" key; fall back to the note's name
		String title = sceneNote.get(FieldNames.FIELD_NAME);
		if(sceneData != null) {
			Object t = sceneData.get(OlioFieldNames.FIELD_PB_TITLE);
			if(t instanceof String && !((String) t).isBlank()) {
				title = (String) t;
			}
		}

		// Create the PB2 scene row via the canonical path (AccessPoint-authorized)
		BaseRecord scene = PbBookUtil.createScene(user, book, sceneIndex, title, sceneGroupPath);

		// Patch the text fields from the PB1 scene data
		if(sceneData != null) {
			patchSceneTextFields(user, scene, sceneData, warnings);
		}
	}

	/**
	 * Patch the PB2 scene's text fields from the PB1 scene note JSON.
	 * <p>
	 * PATCH-shaped per {@code model-api.md}: identity + name + the changed fields. Fields absent in
	 * the PB1 data are skipped. The update result is asserted — a discarded null is a silent no-op.
	 */
	private static void patchSceneTextFields(BaseRecord user, BaseRecord scene,
			Map<String, Object> sceneData, List<String> warnings) {
		String[] candidates = {
			OlioFieldNames.FIELD_PB_SUMMARY,  // "summary"
			OlioFieldNames.FIELD_PB_SETTING,  // "setting"
			OlioFieldNames.FIELD_PB_ACTION,   // "action"
			OlioFieldNames.FIELD_PB_MOOD,     // "mood"
			OlioFieldNames.FIELD_PB_BLURB,    // "blurb"
		};

		List<String> toSet = new ArrayList<>();
		for(String f : candidates) {
			if(sceneData.containsKey(f) && sceneData.get(f) != null) {
				toSet.add(f);
			}
		}
		if(toSet.isEmpty()) {
			return;
		}

		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
			toSet.toArray(new String[0]));
		try {
			for(String f : toSet) {
				patch.set(f, sceneData.get(f).toString());
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			warnings.add("Scene '" + scene.get(FieldNames.FIELD_NAME) + "' text patch error: " + e.getMessage());
			return;
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			warnings.add("Scene '" + scene.get(FieldNames.FIELD_NAME) + "': text field patch was not persisted");
		}
	}

	/**
	 * Extract the {@code scenes} list from the PB1 meta record.
	 * <p>
	 * The {@code olio.pictureBookMeta.scenes} field is a typed list of {@code olio.pictureBookScene}
	 * records — each record's {@code objectId} is the corresponding {@code data.note}'s objectId.
	 */
	@SuppressWarnings("unchecked")
	private static List<BaseRecord> extractMetaScenes(BaseRecord meta) {
		Object raw = null;
		try {
			raw = meta.get("scenes");
		}
		catch(Exception e) {
			return Collections.emptyList();
		}
		if(raw instanceof List) {
			return (List<BaseRecord>) raw;
		}
		return Collections.emptyList();
	}
}
