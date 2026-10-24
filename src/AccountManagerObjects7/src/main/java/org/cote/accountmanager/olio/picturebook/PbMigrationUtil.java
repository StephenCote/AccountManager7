package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.cote.accountmanager.olio.sd.SdConfigUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
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

	/**
	 * PB1 scene-note JSON key holding the {@code objectId} of the {@code data.data} that carries the
	 * scene's rendered landscape ({@code PictureBookUtil.updateSceneLandscapeId}). A note key, not a
	 * model field — PB1 scenes are untyped JSON.
	 */
	static final String V1_LANDSCAPE_KEY = "landscapeObjectId";

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
		/** PB1 landscape images imported as unapproved {@code landscape} artifacts (V3 §28 step 3). */
		public final int landscapesImported;
		/** The whole-book location pass that ran after import, or null when no scene was imported or the pass threw. */
		public final PbLocationUtil.LocationPassResult locationPass;
		public final List<String> warnings;

		ImportResult(String slug, String bookObjectId, int scenesImported, int scenesFailed,
				int landscapesImported, PbLocationUtil.LocationPassResult locationPass, List<String> warnings) {
			this.slug = slug;
			this.bookObjectId = bookObjectId;
			this.scenesImported = scenesImported;
			this.scenesFailed = scenesFailed;
			this.landscapesImported = landscapesImported;
			this.locationPass = locationPass;
			this.warnings = Collections.unmodifiableList(warnings);
		}

		@Override
		public String toString() {
			return "ImportResult{slug='" + slug + "', scenes=" + scenesImported + "/"
				+ (scenesImported + scenesFailed) + ", landscapes=" + landscapesImported
				+ ", locationProposals=" + (locationPass != null ? locationPass.proposals.size() : "n/a")
				+ ", warnings=" + warnings.size() + "}";
		}
	}

	/**
	 * Summary of one {@link #migrateCompositions} pass. Fields are public and plain so Service7 can serialize it
	 * as-is (see {@link #toMap()}); the tag counters come from the {@link PbWardrobeUtil#migrateSceneTags} pass
	 * that runs once after the scenes.
	 */
	public static final class CompositionMigrationResult {
		/** Scenes examined. */
		public int scanned;
		/** {@code MIGRATED} compositions written by this pass. */
		public int created;
		/** Scenes that already had a {@code SCENE}-purpose composition (any source) and were left alone. */
		public int skippedExisting;
		/** Scenes whose existing composition is user-edited; counted separately from {@link #skippedExisting}, never overwritten. */
		public int skippedUserEdited;
		/** Scenes where the migration wrote nothing (compose failure, write failure, or a thrown error). */
		public int failed;
		/**
		 * The objectIds of the {@link #failed} scenes. A compose job must not LLM-compose these: a fresh LLM
		 * composition carries the scene's legacy sdPrompt/promptLocked only through the MIGRATED path, so composing
		 * past a failed migration would silently drop a legacy lock.
		 */
		public final Set<String> failedSceneObjectIds = new LinkedHashSet<>();
		/** Legacy scene tags pinned onto actor rows by the wardrobe pass. */
		public int tagsPinned;
		/** Legacy scene tags that were already pinned. */
		public int tagsSkippedPinned;
		/** Legacy scene tags that could not be resolved to exactly one actor. */
		public int tagsAmbiguous;
		/** Scenes whose resolved outfits the pins changed (tagged scenes and the carried chain after them). */
		public int tagsAffected;
		/** Of {@link #tagsAffected}, the scenes whose render node was marked STALE. */
		public int tagsStaled;
		public final List<String> warnings = new ArrayList<>();

		/** A serializable view of the counters; the warnings list is copied. */
		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("scanned", scanned);
			m.put("created", created);
			m.put("skippedExisting", skippedExisting);
			m.put("skippedUserEdited", skippedUserEdited);
			m.put("failed", failed);
			m.put("failedSceneObjectIds", new ArrayList<>(failedSceneObjectIds));
			m.put("tagsPinned", tagsPinned);
			m.put("tagsSkippedPinned", tagsSkippedPinned);
			m.put("tagsAmbiguous", tagsAmbiguous);
			m.put("tagsAffected", tagsAffected);
			m.put("tagsStaled", tagsStaled);
			m.put("warnings", new ArrayList<>(warnings));
			return m;
		}

		@Override
		public String toString() {
			return "CompositionMigrationResult{scanned=" + scanned + ", created=" + created
				+ ", existing=" + skippedExisting + ", userEdited=" + skippedUserEdited + ", failed=" + failed
				+ ", tagsPinned=" + tagsPinned + ", tagsSkippedPinned=" + tagsSkippedPinned
				+ ", tagsAmbiguous=" + tagsAmbiguous + ", tagsAffected=" + tagsAffected + ", tagsStaled=" + tagsStaled
				+ ", warnings=" + warnings.size() + "}";
		}
	}

	/** One imported scene plus the PB1 landscape reference found in its note, if any. */
	private static final class SceneImport {
		final BaseRecord scene;
		final String landscapeObjectId;

		SceneImport(BaseRecord scene, String landscapeObjectId) {
			this.scene = scene;
			this.landscapeObjectId = landscapeObjectId;
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
		List<SceneImport> imports = new ArrayList<>();
		int failed = 0;
		for(int i = 0; i < sceneList.size(); i++) {
			BaseRecord pbScene = sceneList.get(i);
			try {
				imports.add(importScene(user, book, sceneGroupPath, i, pbScene, orgId, warnings));
			}
			catch(Exception e) {
				String msg = "Scene " + i + " (" + pbScene.get(FieldNames.FIELD_OBJECT_ID) + "): "
					+ e.getMessage();
				logger.warn("PB1 migration: scene skipped — {}", msg);
				warnings.add(msg);
				failed++;
			}
		}
		int imported = imports.size();

		// 10. Create one SCENE node per imported scene, marked DONE_UNVERIFIED:
		// honest labelling that PB1 had output for each scene but its provenance is unknown to PB2.
		// Then (V3 §28 step 3) carry each PB1 landscape image over as an unapproved landscape artifact.
		int landscapes = 0;
		if(imported > 0) {
			createMigrationNodes(user, book, slug, imported, warnings);
			landscapes = importLandscapes(user, book, slug, orgId, imports, warnings);
		}

		// 11. Mark the book extracted (content ready, generation pending)
		if(imported > 0) {
			PbBookUtil.setBookStatus(user, book, PbBookStatusEnumType.EXTRACTED);
		}

		// 12. Whole-book location pass (V3 §7.1 / §28 step 3): proposals only, reviewed later.
		// Never fatal - a location failure must not fail the migration (§30 row 3 exit criterion).
		PbLocationUtil.LocationPassResult locationPass = null;
		if(imported > 0) {
			locationPass = proposeLocations(user, book, slug, warnings);
		}

		logger.info("PB1 migration complete: '{}' → slug '{}': {}/{} scenes, {} landscape(s), {} proposal(s), {} warning(s)",
			groupName, slug, imported, sceneList.size(), landscapes,
			(locationPass != null ? locationPass.proposals.size() : 0), warnings.size());
		return new ImportResult(slug, bookObjectId, imported, failed, landscapes, locationPass, warnings);
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
		if(!(raw instanceof BaseRecord)) {
			warnings.add(fieldName + " is not a BaseRecord; skipping FK persistence");
			return;
		}
		BaseRecord cfg = (BaseRecord) raw;
		// S6: persist as an olio.sd.config FK row before patching the book field
		try {
			String oid = cfg.get(FieldNames.FIELD_OBJECT_ID);
			if(oid == null || oid.trim().isEmpty()) {
				long bookGroupId = book.get(FieldNames.FIELD_GROUP_ID);
				long orgId = book.get(FieldNames.FIELD_ORGANIZATION_ID);
				cfg.set(FieldNames.FIELD_NAME, "migrate-" + fieldName);
				cfg.set(FieldNames.FIELD_GROUP_ID, bookGroupId);
				cfg.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			}
			BaseRecord persisted = SdConfigUtil.createOrUpdateConfig(user, cfg);
			if(persisted == null) {
				warnings.add(fieldName + " config row could not be persisted");
				return;
			}
			cfg = persisted;
		}
		catch(Exception e) {
			warnings.add(fieldName + " config persistence error: " + e.getMessage());
			return;
		}
		BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK, fieldName);
		try {
			patch.set(fieldName, cfg);
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
	 *
	 * @return the created PB2 scene plus the PB1 {@code landscapeObjectId} from its note (null when absent)
	 */
	private static SceneImport importScene(BaseRecord user, BaseRecord book, String sceneGroupPath,
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
		String landscapeOid = null;
		if(sceneData != null) {
			patchSceneTextFields(user, scene, sceneData, warnings);
			Object l = sceneData.get(V1_LANDSCAPE_KEY);
			if(l instanceof String && !((String) l).isBlank()) {
				landscapeOid = ((String) l).trim();
			}
		}
		return new SceneImport(scene, landscapeOid);
	}

	/**
	 * V3 §28 step 3: carry every PB1 landscape image over as an <b>unapproved</b> {@code landscape}
	 * artifact on the scene's canonical LANDSCAPE node, so the V3 review views and the Phase 3/4
	 * location seeding see it without re-rendering.
	 * <p>
	 * Per imported scene with a {@code landscapeObjectId}:
	 * <ol>
	 * <li>the PB1 {@code data.data} is read as the request user (find-only; a miss or a denial is a
	 *     warning, never a failure - PB1 is never written);</li>
	 * <li>the node is found by {@link PbPipelineUtil#landscapeHandle} or created with {@code scope=scene}
	 *     / {@code scopeRef=} the scene objectId - the same get-or-create {@code PbServiceFacade} uses
	 *     for ChapBook pages, so a later render lands on the same node;</li>
	 * <li>when the node's {@code landscape} chain is empty, one IMAGE artifact referencing that
	 *     {@code data.data} is persisted (GENERATED, selected, never APPROVED - the user approves) and
	 *     the node is marked {@code DONE_UNVERIFIED}; a non-empty chain means a prior run already did
	 *     this, so the step is idempotent.</li>
	 * </ol>
	 * The bytes are referenced, not copied: the artifact's {@code data} points at the PB1 record.
	 *
	 * @return the number of landscape artifacts created by this call
	 */
	private static int importLandscapes(BaseRecord user, BaseRecord book, String slug, long orgId,
			List<SceneImport> imports, List<String> warnings) {
		int created = 0;
		List<SceneImport> withLandscape = new ArrayList<>();
		for(SceneImport si : imports) {
			if(si.landscapeObjectId != null) {
				withLandscape.add(si);
			}
		}
		if(withLandscape.isEmpty()) {
			return 0;
		}
		String workflowPath = PbBookUtil.workflowGroupPath(slug);
		BaseRecord workflow;
		try {
			workflow = PbGraphUtil.getCreateWorkflow(user, book, workflowPath);
		}
		catch(Exception e) {
			warnings.add("Landscape import skipped - workflow unavailable: " + e.getMessage());
			return 0;
		}
		if(workflow == null) {
			warnings.add("Landscape import skipped - workflow was null");
			return 0;
		}
		String artifactPath = PbBookUtil.artifactGroupPath(slug);
		for(SceneImport si : withLandscape) {
			String sceneOid = si.scene.get(FieldNames.FIELD_OBJECT_ID);
			String sceneName = si.scene.get(FieldNames.FIELD_NAME);
			try {
				BaseRecord data = findData(user, si.landscapeObjectId, orgId);
				if(data == null) {
					warnings.add("Scene '" + sceneName + "': PB1 landscape " + si.landscapeObjectId
						+ " not found or not accessible - not imported");
					continue;
				}
				Integer idxObj = si.scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
				int idx = (idxObj != null ? idxObj.intValue() : 0);
				String handle = PbPipelineUtil.landscapeHandle(sceneOid);
				BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, handle);
				if(node == null) {
					node = PbGraphUtil.addNode(user, workflow, handle, PbNodeTypeEnumType.LANDSCAPE, workflowPath, idx);
					if(node == null) {
						warnings.add("Scene '" + sceneName + "': could not create landscape node " + handle);
						continue;
					}
					if(!PbPipelineUtil.patchNodeScope(user, workflow, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx)) {
						warnings.add("Scene '" + sceneName + "': could not record the scene scope on node " + handle);
						continue;
					}
				}
				if(!PbArtifactUtil.listChain(user, node, PbPipelineUtil.ROLE_LANDSCAPE).isEmpty()) {
					logger.info("PB1 migration: node {} already carries a landscape chain - not re-imported", handle);
					continue;
				}
				PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_LANDSCAPE,
					PbArtifactTypeEnumType.IMAGE, artifactPath, data, null, null, null);
				if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED)) {
					warnings.add("Scene '" + sceneName + "': could not mark node " + handle + " as DONE_UNVERIFIED");
				}
				created++;
			}
			catch(Exception e) {
				String msg = "Scene '" + sceneName + "': landscape " + si.landscapeObjectId + " not imported - " + e.getMessage();
				logger.warn("PB1 migration: {}", msg);
				warnings.add(msg);
			}
		}
		return created;
	}

	/** Find-only read of a {@code data.data} by objectId as the request user; identity + descriptor fields only. */
	private static BaseRecord findData(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_CONTENT_TYPE
		});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Run {@link PbLocationUtil#proposeLocations(BaseRecord, BaseRecord, BaseRecord)} against the
	 * user's default chat configuration. Proposals only - nothing is bound to the graph until the
	 * user accepts them - and never fatal: an LLM or persistence failure becomes a warning.
	 */
	private static PbLocationUtil.LocationPassResult proposeLocations(BaseRecord user, BaseRecord book, String slug,
			List<String> warnings) {
		try {
			PbLocationUtil.LocationPassResult pass = PbLocationUtil.proposeLocations(user, book, (BaseRecord) null);
			if(pass != null) {
				warnings.addAll(pass.warnings);
				if(pass.hardFailure) {
					warnings.add("Location pass for '" + slug + "' did not complete - review the warnings above and re-run it");
				}
			}
			return pass;
		}
		catch(PictureBookException e) {
			String msg = "Location pass for '" + slug + "' failed (" + e.getStatus() + "): " + e.getMessage();
			logger.warn("PB1 migration: {}", msg);
			warnings.add(msg);
			return null;
		}
	}

	/**
	 * Patch the PB2 scene's text fields from the PB1 scene note JSON.
	 * <p>
	 * PATCH-shaped per {@code model-api.md}: identity + name + the changed fields. Fields absent in
	 * the PB1 data are skipped. The update result is asserted — a discarded null is a silent no-op.
	 */
	static boolean patchSceneTextFields(BaseRecord user, BaseRecord scene,
			Map<String, Object> sceneData, List<String> warnings) {
		String[] candidates = {
			OlioFieldNames.FIELD_PB_SUMMARY,  // "summary"
			OlioFieldNames.FIELD_PB_SETTING,  // "setting"
			OlioFieldNames.FIELD_PB_ACTION,   // "action"
			OlioFieldNames.FIELD_PB_MOOD,     // "mood"
			OlioFieldNames.FIELD_PB_BLURB,    // "blurb"
			FieldNames.FIELD_DESCRIPTION,     // "description"
			OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, // "imageObjectId" (rendered image recorded on the PB1 scene note)
		};

		List<String> toSet = new ArrayList<>();
		for(String f : candidates) {
			if(sceneData.containsKey(f) && sceneData.get(f) != null && !sceneData.get(f).toString().isBlank()) {
				toSet.add(f);
			}
		}
		if(toSet.isEmpty()) {
			return true;
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
			return false;
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			warnings.add("Scene '" + scene.get(FieldNames.FIELD_NAME) + "': text field patch was not persisted");
			return false;
		}
		return true;
	}

	/**
	 * Create the workflow for the migrated book and add one {@code SCENE}-type node per imported
	 * scene, each marked {@code DONE_UNVERIFIED} with {@code inputHash = null}: honest labelling
	 * that content existed in PB1 but its pipeline provenance is unknown to PB2.
	 * <p>
	 * Handles are prefixed {@code "v1scene-"} to avoid collisions with nodes that PbPipelineUtil
	 * creates later using its own handle conventions.
	 */
	private static void createMigrationNodes(BaseRecord user, BaseRecord book, String slug,
			int scenesImported, List<String> warnings) {
		String workflowPath = PbBookUtil.workflowGroupPath(slug);
		BaseRecord workflow;
		try {
			workflow = PbGraphUtil.getCreateWorkflow(user, book, workflowPath);
		}
		catch(Exception e) {
			warnings.add("Migration workflow creation failed: " + e.getMessage());
			return;
		}
		if(workflow == null) {
			warnings.add("Migration workflow was null after creation");
			return;
		}
		for(int i = 0; i < scenesImported; i++) {
			String handle = "v1scene-" + i;
			try {
				BaseRecord node = PbGraphUtil.addNode(user, workflow, handle,
					PbNodeTypeEnumType.SCENE, workflowPath, i);
				if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED)) {
					warnings.add("Could not mark migration node " + handle + " as DONE_UNVERIFIED");
				}
			}
			catch(Exception e) {
				warnings.add("Failed to create migration node " + handle + ": " + e.getMessage());
			}
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

	// ─────────────────────────────── composition migration (V3 §28 step 4) ───────────────────────────────

	/**
	 * One-off migration of a STORY book's legacy scene fields into {@code olio.pb.composition} rows
	 * (design §28 step 4, §9.1). Runs inside the first-open compose job as the job's user, so every write
	 * goes through {@code AccessPoint} as that user; nothing here runs as the org admin.
	 * <p>
	 * For every scene that has no {@code SCENE}-purpose composition in the <b>book's</b> group, a
	 * {@code MIGRATED} composition is written from the scene's own fields and roster
	 * ({@link PbComposeUtil#composeMigrated}); {@code sdPrompt} becomes {@code promptOverride},
	 * {@code promptLocked} and {@code configOverride} are carried across. A scene that already has a composition
	 * is counted and left alone — a user-edited one is counted separately and is never overwritten. After the
	 * scenes, {@link PbWardrobeUtil#migrateSceneTags} runs once to pin legacy scene tags onto actor rows.
	 * <p>
	 * Idempotent: a second pass over the same book scans every scene, creates nothing, and re-runs only the
	 * (itself idempotent) tag pass. Per-scene failures are counted and warned, never thrown; the caller's loop
	 * continues. ChapBooks are not migrated (their page compositions are created by the ChapBook path): the
	 * result is empty with one warning.
	 *
	 * @param user   the job's user; all reads and writes are as this user
	 * @param book   the book (a fresh read; its {@code groupId} and {@code slug} are used)
	 * @param scenes the book's scenes, as returned by {@code PbBookUtil.listScenes}
	 * @return the counters and warnings; never null
	 */
	public static CompositionMigrationResult migrateCompositions(BaseRecord user, BaseRecord book, List<BaseRecord> scenes) {
		CompositionMigrationResult result = new CompositionMigrationResult();
		if(user == null || book == null) {
			result.warnings.add("composition migration skipped: user or book is null");
			return result;
		}
		String slug = str(book.get(OlioFieldNames.FIELD_PB_SLUG));
		if(PbNodeExecutor.isChapBook(book)) {
			result.warnings.add("composition migration skipped: '" + slug + "' is a ChapBook (page compositions are created by the ChapBook path)");
			return result;
		}
		Long bookGroupId = book.get(FieldNames.FIELD_GROUP_ID);
		Long orgId = book.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(bookGroupId == null || orgId == null) {
			result.warnings.add("composition migration skipped: book '" + slug + "' has no group or organization");
			return result;
		}
		List<BaseRecord> list = (scenes != null ? scenes : Collections.emptyList());
		for(BaseRecord scene : list) {
			if(scene == null) {
				continue;
			}
			result.scanned++;
			String sceneLabel = sceneLabel(scene);
			try {
				BaseRecord existing = PbComposeUtil.findSceneComposition(user, scene, bookGroupId.longValue(), orgId.longValue());
				if(existing != null) {
					if(Boolean.TRUE.equals(existing.get(OlioFieldNames.FIELD_PB_USER_EDITED))) {
						result.skippedUserEdited++;
					}
					else {
						result.skippedExisting++;
					}
					continue;
				}
				PbComposeUtil.ComposeResult r = PbComposeUtil.composeMigrated(user, book, scene, false);
				for(String w : r.warnings) {
					result.warnings.add(sceneLabel + ": " + w);
				}
				if(r.isWritten()) {
					result.created++;
				}
				else {
					result.failed++;
					result.failedSceneObjectIds.add(str(scene.get(FieldNames.FIELD_OBJECT_ID)));
					result.warnings.add(sceneLabel + ": migrated composition was not written"
						+ (r.failureReason != null ? " (" + r.failureReason + ")" : ""));
				}
			}
			catch(RuntimeException e) {
				result.failed++;
				result.failedSceneObjectIds.add(str(scene.get(FieldNames.FIELD_OBJECT_ID)));
				result.warnings.add(sceneLabel + ": " + PbServiceFacade.curatedMessage(e));
				logger.warn("composition migration " + slug + " " + sceneLabel + " failed: " + e.getMessage());
			}
		}
		if(result.scanned > 0) {
			try {
				PbWardrobeUtil.MigrationResult tags = PbWardrobeUtil.migrateSceneTags(user, book);
				if(tags != null) {
					result.tagsPinned = tags.pinned;
					result.tagsSkippedPinned = tags.skippedPinned;
					result.tagsAmbiguous = tags.ambiguous;
					result.tagsAffected = tags.affected;
					result.tagsStaled = tags.staled;
					result.warnings.addAll(tags.warnings);
				}
			}
			catch(RuntimeException e) {
				result.warnings.add("scene tag migration failed: " + PbServiceFacade.curatedMessage(e));
				logger.warn("composition migration " + slug + " tag pass failed: " + e.getMessage());
			}
		}
		logger.info("composition migration " + slug + ": " + result.scanned + " scanned, " + result.created + " created, "
			+ result.skippedExisting + " existing, " + result.skippedUserEdited + " user-edited, " + result.failed + " failed, "
			+ result.tagsPinned + " tags pinned, " + result.tagsSkippedPinned + " tags already pinned");
		return result;
	}

	/** {@code scene <index> '<name>'} for warnings; falls back to the objectId when the scene has no name. */
	private static String sceneLabel(BaseRecord scene) {
		Object idx = scene.hasField(OlioFieldNames.FIELD_PB_SCENE_INDEX) ? scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX) : null;
		String name = str(scene.hasField(FieldNames.FIELD_NAME) ? scene.get(FieldNames.FIELD_NAME) : null);
		if(name == null) {
			name = str(scene.get(FieldNames.FIELD_OBJECT_ID));
		}
		return "scene " + (idx != null ? idx + " " : "") + "'" + name + "'";
	}

	private static String str(Object v) {
		return (v == null ? null : v.toString());
	}
}
