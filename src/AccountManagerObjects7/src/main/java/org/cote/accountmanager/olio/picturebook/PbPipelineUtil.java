package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;

/**
 * The phase-3 seam: {@code PictureBookUtil.generateSceneImage}'s existing per-stage boundaries recorded
 * as {@code olio.pb.*} node executions, bindings and artifacts.
 * <p>
 * <b>The seam map was already in the code.</b> Every node here corresponds to one
 * {@code PictureBookProgressNotifier.notifyProgress} call site in {@code generateSceneImage} - the
 * stages the pipeline already announces to the user are exactly the units whose output is worth
 * versioning. Nothing was re-cut for the graph's benefit:
 * <pre>
 *   Stage 0  (no notifier; before the SD calls)     -> LANDSCAPE_PROMPT, SCENE_PROMPT nodes
 *   "face"        Generating portraits...           -> one PORTRAIT node per scene character
 *   "landscape"   Generating landscape...           -> LANDSCAPE node
 *   "auto_awesome_mosaic" Preparing/Stitching refs  -> REFERENCE_STRIP node
 *   "image"       Compositing scene...              -> COMPOSITE node
 * </pre>
 * <p>
 * <b>FIND-ONLY for the book and its world; create-only inside them.</b> This class never creates a book
 * or a world. A scene-image generation call is a <i>use</i> of a book, and a use that silently created
 * the book (and therefore a universe, a world, three groups and a role pair, some of it as the org
 * admin) is precisely the {@code LibraryUtil} read-path-that-creates shape
 * {@code .claude/rules/architecture.md} names as a standing trap - it has been hit twice already. So
 * {@link #openSceneGraph} resolves an <b>existing</b> {@code olio.pb.book} and returns null when there
 * is none. {@code PbBookUtil.createBook} remains the one authorized creation path.
 * <p>
 * <b>Every failure here is swallowed with a log line, deliberately.</b> The graph is provenance. Losing
 * provenance must never lose an image the GPU spent ten minutes producing, and must never turn a
 * working PB1 pipeline into a failing one because a node write was denied. So
 * {@code generateSceneImage} calls into this class inside {@code try/catch(Exception)} and continues.
 * The one thing that is <i>not</i> silent is the log: a skipped recording says which book and which
 * stage, at WARN.
 * <p>
 * <b>Node handles carry their scope, and names derive from handles.</b> Ratification 8: {@code UrnProvider}
 * composes the urn from {@code name}, {@code common.urn} has no uniqueness constraint, and the unique
 * {@code (name, groupId, organizationId)} index is the only collision guard. Every workflow's nodes share
 * one group, so a handle must be unique across the whole book - hence the scene/character discriminator
 * in each handle rather than a bare "composite".
 */
public class PbPipelineUtil {
	public static final Logger logger = LogManager.getLogger(PbPipelineUtil.class);

	/** {@code node.scope} for a node whose {@code scopeRef} is a PB1 scene note objectId. */
	public static final String SCOPE_SCENE = "scene";
	/** {@code node.scope} for a node whose {@code scopeRef} is an {@code olio.charPerson} objectId. */
	public static final String SCOPE_CHARACTER = "character";

	/// Binding + artifact roles. These are the wire names §2.5 and §9's level-1 assertions use, so they
	/// are constants rather than literals scattered across the pipeline.
	public static final String ROLE_PROMPT = "prompt";
	public static final String ROLE_SCENE_PROMPT = "scenePrompt";
	public static final String ROLE_LANDSCAPE_PROMPT = "landscapePrompt";
	public static final String ROLE_PORTRAIT = "portrait";
	public static final String ROLE_PORTRAIT_0 = "portrait0";
	public static final String ROLE_PORTRAIT_1 = "portrait1";
	public static final String ROLE_LANDSCAPE = "landscape";
	public static final String ROLE_REFERENCE_STRIP = "referenceStrip";
	public static final String ROLE_COMPOSITE_CANVAS = "compositeCanvas";
	public static final String ROLE_COMPOSITE = "composite";
	public static final String ROLE_CHARACTER = "character";
	public static final String ROLE_SOURCE_TEXT = "sourceText";

	private PbPipelineUtil() {
		/// static utility
	}

	// ─────────────────────────────── the per-scene graph handle ───────────────────────────────

	/**
	 * Everything one scene's recording needs, resolved once. Held by the pipeline for the duration of a
	 * single {@code generateSceneImage} call and then discarded - it is a working handle, not a cache, and
	 * nothing in it is shared across calls or threads.
	 */
	public static final class SceneGraph {
		private final BaseRecord user;
		private final BaseRecord book;
		private final BaseRecord workflow;
		private final BaseRecord sceneRow;
		private final String workflowGroupPath;
		private final String artifactGroupPath;
		private final String galleryGroupPath;
		private final String sceneObjectId;
		private final int sceneIndex;
		private final Map<String, BaseRecord> nodes = new LinkedHashMap<>();
		private BaseRecord run;
		private int executed = 0;
		private int failed = 0;

		SceneGraph(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord sceneRow,
				String workflowGroupPath, String artifactGroupPath, String galleryGroupPath,
				String sceneObjectId, int sceneIndex) {
			this.user = user;
			this.book = book;
			this.workflow = workflow;
			this.sceneRow = sceneRow;
			this.workflowGroupPath = workflowGroupPath;
			this.artifactGroupPath = artifactGroupPath;
			this.galleryGroupPath = galleryGroupPath;
			this.sceneObjectId = sceneObjectId;
			this.sceneIndex = sceneIndex;
		}

		public BaseRecord getBook() {
			return book;
		}

		public BaseRecord getWorkflow() {
			return workflow;
		}

		public BaseRecord getSceneRow() {
			return sceneRow;
		}

		public BaseRecord getRun() {
			return run;
		}

		public String getArtifactGroupPath() {
			return artifactGroupPath;
		}

		/** Where artifact BYTES land: the world's {@code Gallery} (§2.5). */
		public String getGalleryGroupPath() {
			return galleryGroupPath;
		}

		public String getSceneObjectId() {
			return sceneObjectId;
		}

		public int getSceneIndex() {
			return sceneIndex;
		}

		/** A node already resolved on this graph, by handle. */
		public BaseRecord node(String handle) {
			return nodes.get(handle);
		}
	}

	// ─────────────────────────────── open ───────────────────────────────

	/**
	 * Resolve the graph for one PB1 scene, creating the workflow/scene/node skeleton inside an
	 * <b>existing</b> book if it is not there yet.
	 * <p>
	 * Returns <b>null</b>, having logged why, when v2 recording cannot proceed - no flag, no book for
	 * this slug, no world, or an unauthorized write. A null return is the pipeline's signal to run PB1
	 * only, which is what keeps the flag-off gate and the "graph failure never loses an image" property
	 * true.
	 *
	 * @param slug the book's PB2 slug; when null, derived from {@code pb1BookGroupName} by
	 *        {@link #deriveSlug}
	 */
	public static SceneGraph openSceneGraph(BaseRecord user, String slug, String pb1BookGroupName,
			String sceneObjectId, int sceneIndex, String sceneTitle) {
		if(!PbFeatureFlag.isV2Enabled()) {
			return null;
		}
		if(user == null || sceneObjectId == null) {
			return null;
		}
		String useSlug = (slug != null ? slug : deriveSlug(pb1BookGroupName));
		if(useSlug == null) {
			logger.warn("PB2 recording skipped: no book slug could be derived from PB1 book group '"
				+ pb1BookGroupName + "'");
			return null;
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		try {
			/// FIND-ONLY. A missing book is not created here - see the class javadoc.
			BaseRecord book = PbBookUtil.findBookBySlug(user, useSlug, orgId);
			if(book == null) {
				logger.warn("PB2 recording skipped: no olio.pb.book with slug '" + useSlug + "' in organization "
					+ orgId + ". Create it with PbBookUtil.createBook first - a generation call deliberately"
					+ " does not create a book, world, groups and roles as a side effect of rendering.");
				return null;
			}
			BookContext bctx = PbBookUtil.openBookContext(user, book);
			if(bctx == null) {
				logger.warn("PB2 recording skipped: book '" + useSlug + "' has no assemblable world");
				return null;
			}
			String workflowPath = PbBookUtil.workflowGroupPath(useSlug);
			String artifactPath = PbBookUtil.artifactGroupPath(useSlug);
			String galleryPath = bctx.getGroupPath(OlioFieldNames.FIELD_GALLERY);
			if(galleryPath == null) {
				/// Bytes have to land somewhere addressable; the Artifacts group is in the same compartment
				/// and carries the same grants, so it is a correct fallback rather than a guess.
				logger.warn("Book '" + useSlug + "' world has no gallery group; artifact bytes will land in "
					+ artifactPath);
				galleryPath = artifactPath;
			}

			BaseRecord workflow = PbGraphUtil.getCreateWorkflow(user, book, workflowPath);
			BaseRecord sceneRow = getCreateSceneRow(user, book, sceneIndex, sceneTitle, useSlug);

			SceneGraph g = new SceneGraph(user, book, workflow, sceneRow, workflowPath, artifactPath,
				galleryPath, sceneObjectId, sceneIndex);
			g.run = PbGraphUtil.startRun(user, workflow, workflowPath, null);
			PbGraphUtil.persistLastRun(user, workflow, g.run);
			return g;
		}
		catch(Exception e) {
			/// Provenance must never break rendering. Log the whole thing and let PB1 proceed.
			logger.warn("PB2 recording skipped for scene " + sceneObjectId + ": " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * A lowercase, {@code BOOK_SLUG_PATTERN}-legal slug derived from a PB1 book group name.
	 * <p>
	 * Deterministic, so the same PB1 book maps to the same PB2 book on every run - the mapping is the
	 * whole point and a timestamp or UUID in it would create a new book per call. Non-conforming
	 * characters collapse to {@code -}; a name that reduces to nothing yields null rather than a
	 * plausible-looking wrong slug.
	 */
	public static String deriveSlug(String pb1BookGroupName) {
		if(pb1BookGroupName == null) {
			return null;
		}
		String s = pb1BookGroupName.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+", "");
		while(s.endsWith("-")) {
			s = s.substring(0, s.length() - 1);
		}
		if(s.length() == 0) {
			return null;
		}
		if(s.length() > 64) {
			s = s.substring(0, 64);
		}
		if(!PbOlioContextUtil.BOOK_SLUG_PATTERN.matcher(s).matches()) {
			return null;
		}
		return s;
	}

	/**
	 * The {@code olio.pb.scene} row for {@code sceneIndex}, created if absent.
	 * <p>
	 * This is half of the phase-3 <b>dual write</b>: the PB1 {@code data.note} keeps being written exactly
	 * as before (so all 16 PB1 endpoints keep working), and the same facts land on a queryable
	 * {@code olio.pb.scene} row whose {@code sceneIndex} is an indexed column rather than an array position
	 * inside a JSON blob.
	 */
	private static BaseRecord getCreateSceneRow(BaseRecord user, BaseRecord book, int sceneIndex, String title,
			String slug) {
		List<BaseRecord> existing = PbBookUtil.listScenes(user, book);
		for(BaseRecord s : existing) {
			Integer idx = s.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(idx != null && idx.intValue() == sceneIndex) {
				return s;
			}
		}
		return PbBookUtil.createScene(user, book, sceneIndex, title, PbBookUtil.bookGroupPath(slug));
	}

	// ─────────────────────────────── nodes ───────────────────────────────

	/**
	 * Get-or-create a node on this graph by handle, and remember it for the rest of the call.
	 * <p>
	 * Get-or-create rather than create: a second generation of the same scene must reuse the same nodes,
	 * or every re-render would fork a parallel graph and the version chains on which staleness depends
	 * would each start again at revision 1.
	 */
	public static BaseRecord getCreateNode(SceneGraph g, String handle, PbNodeTypeEnumType nodeType, int ordinal,
			String scope, String scopeRef) {
		if(g == null || handle == null) {
			return null;
		}
		BaseRecord known = g.nodes.get(handle);
		if(known != null) {
			return known;
		}
		BaseRecord node = findNodeByHandle(g.user, g.workflow, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(g.user, g.workflow, handle, nodeType, g.workflowGroupPath, ordinal);
			/// scope/sceneIndex are what make a node addressable per scene without parsing its handle.
			patchNodeScope(g, node, scope, scopeRef);
			node = PbGraphUtil.readNode(g.user, node.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(g.workflow));
		}
		g.nodes.put(handle, node);
		return node;
	}

	private static void patchNodeScope(SceneGraph g, BaseRecord node, String scope, String scopeRef) {
		BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE,
			OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF,
			OlioFieldNames.FIELD_PB_SCENE_INDEX, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID);
		try {
			if(scope != null) {
				patch.set(OlioFieldNames.FIELD_PB_SCOPE, scope);
			}
			if(scopeRef != null) {
				patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, scopeRef);
			}
			patch.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, Integer.valueOf(g.sceneIndex));
			patch.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, g.user.get(FieldNames.FIELD_OBJECT_ID));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a node scope patch: " + e.getMessage());
		}
		/// Never discard an update result - it is the only signal a persistent failure gives.
		if(IOSystem.getActiveContext().getAccessPoint().update(g.user, patch) == null) {
			logger.error("Failed to record scope on node " + node.get(FieldNames.FIELD_NAME));
		}
	}

	/**
	 * A node of {@code workflow} with {@code handle}, or null.
	 * <p>
	 * The {@code workflow} condition takes the <b>RECORD</b>. Passing its id makes {@code FieldUtil.setFlex}
	 * reject the {@code Long} for a {@code MODEL}-typed field, the condition silently becomes
	 * {@code workflow = null}, it matches nothing, and <b>nothing logs at the call site</b>
	 * (measured on {@code am7db} 2026-08-15; {@code StatementUtil.java:1367} casts the value to a
	 * {@code BaseRecord} and reads the id itself).
	 */
	public static BaseRecord findNodeByHandle(BaseRecord user, BaseRecord workflow, String handle) {
		if(user == null || workflow == null || handle == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
		q.field(OlioFieldNames.FIELD_PB_HANDLE, ComparatorEnumType.EQUALS, handle);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(workflow));
		q.setRequest(PbGraphUtil.nodeRequest());
		/// Uncached: a node created moments ago in this same call must be visible.
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── bindings ───────────────────────────────

	/**
	 * Bind a producer node's output to a consumer node, if that edge is not already there.
	 * <p>
	 * Idempotent because {@code generateSceneImage} runs repeatedly over the same scene and the unique
	 * {@code (node, role, bindingOrdinal, organizationId)} index would otherwise refuse the second run -
	 * which, being a create failure, would throw out of the recording path.
	 */
	public static BaseRecord bindNode(SceneGraph g, BaseRecord consumer, String role, int ordinal,
			BaseRecord producer, BaseRecord sourceArtifact) {
		if(g == null || consumer == null) {
			return null;
		}
		BaseRecord existing = findBinding(g.user, consumer, role, ordinal);
		if(existing != null) {
			/// The edge exists; keep it pointed at the artifact revision actually consumed THIS run, or
			/// inputHash would keep hashing a superseded revision and nothing downstream would ever be stale.
			if(sourceArtifact != null) {
				BaseRecord patch = PbGraphUtil.patchOf(existing, OlioModelNames.MODEL_PB_BINDING,
					OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT);
				try {
					patch.set(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT, sourceArtifact);
				}
				catch(FieldException | ValueException | ModelNotFoundException e) {
					throw new PictureBookException(500, "Failed to assemble a binding patch: " + e.getMessage());
				}
				if(IOSystem.getActiveContext().getAccessPoint().update(g.user, patch) == null) {
					logger.error("Failed to repoint binding " + existing.get(FieldNames.FIELD_NAME)
						+ " at the artifact revision consumed this run");
				}
			}
			return PbGraphUtil.readBinding(g.user, existing.get(FieldNames.FIELD_OBJECT_ID),
				PbGraphUtil.orgId(consumer));
		}
		return PbGraphUtil.addBinding(g.user, g.workflow, consumer, role, ordinal, producer, sourceArtifact,
			g.workflowGroupPath);
	}

	/**
	 * Bind an ordinary AM7 record - a {@code charPerson}, a garment, the source document - capturing its
	 * {@code refHash} at bind time.
	 * <p>
	 * This is the leg that makes <b>editing a character</b> mark the scene stale. Artifact chaining
	 * structurally cannot see a record edit (§2.3), and the reference test hand-rolls exactly this check
	 * as {@code if((int)duna.get("age") != 15) { re-imprint; re-apparel; re-portrait }}.
	 */
	public static BaseRecord bindRecord(SceneGraph g, BaseRecord consumer, String role, int ordinal,
			String refModel, String refObjectId) {
		if(g == null || consumer == null || refObjectId == null) {
			return null;
		}
		BaseRecord existing = findBinding(g.user, consumer, role, ordinal);
		if(existing != null) {
			/// Refresh the hash so the NEXT run compares against what this run actually consumed. Without
			/// this a character edited between runs would stay stale forever.
			PbGraphUtil.persistRefHashes(g.user, Collections.singletonList(existing));
			return PbGraphUtil.readBinding(g.user, existing.get(FieldNames.FIELD_OBJECT_ID),
				PbGraphUtil.orgId(consumer));
		}
		return PbGraphUtil.addRecordBinding(g.user, g.workflow, consumer, role, ordinal, refModel, refObjectId,
			g.workflowGroupPath);
	}

	/** The binding on {@code (node, role, ordinal)}, or null. Foreign condition takes the RECORD. */
	public static BaseRecord findBinding(BaseRecord user, BaseRecord node, String role, int ordinal) {
		if(user == null || node == null || role == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE, node);
		q.field(OlioFieldNames.FIELD_PB_ROLE, ComparatorEnumType.EQUALS, role);
		q.field(OlioFieldNames.FIELD_PB_BINDING_ORDINAL, ComparatorEnumType.EQUALS, Integer.valueOf(ordinal));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(node));
		q.setRequest(PbGraphUtil.bindingRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── artifacts ───────────────────────────────

	/**
	 * Record a text payload - an extracted scene, a resolved prompt, a serialized structure - as an
	 * artifact revision. Bytes stay null; {@code artifactText} carries it and
	 * {@code PbArtifactUtil.persistArtifact} hashes it into {@code contentHash}.
	 */
	public static BaseRecord recordText(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, String text, BaseRecord sdConfigSnapshot) {
		if(g == null || node == null || text == null) {
			return null;
		}
		return PbArtifactUtil.persistArtifact(g.user, node, role, artifactType, g.artifactGroupPath,
			null, text, sdConfigSnapshot, null);
	}

	/**
	 * Record an image output as an artifact revision, with its measurements read from the bytes.
	 * <p>
	 * <b>{@code generatorRequest} is sanitized structurally before it is persisted.</b> Neither
	 * {@code SWTxt2Img.initImage} nor {@code promptImages} carries a {@code @JsonIgnore}, so a raw
	 * request is multi-megabyte base64 plus the Swarm {@code session_id}; the references are replaced by
	 * the objectIds of the artifacts that supplied them, which is also what makes the provenance readable
	 * (§9's level-1 assertion checks exactly this). {@code PbArtifactUtil.sanitizeGeneratorRequest}
	 * returns null rather than the original if it cannot parse - refusing to persist beats persisting the
	 * payload this exists to strip.
	 *
	 * @param data the {@code data.data} record holding the bytes, or null when the bytes are not persisted
	 *        separately
	 * @param referenceArtifactObjectIds objectIds of the artifacts that supplied this request's references
	 */
	public static BaseRecord recordImage(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, BaseRecord data, byte[] bytes, String mimeType, Long seed,
			BaseRecord sdConfigSnapshot, String rawRequestJson, List<String> referenceArtifactObjectIds) {
		if(g == null || node == null) {
			return null;
		}
		String sanitized = PbArtifactUtil.sanitizeGeneratorRequest(rawRequestJson, referenceArtifactObjectIds);
		BaseRecord artifact = PbArtifactUtil.persistArtifact(g.user, node, role, artifactType,
			g.artifactGroupPath, data, null, sdConfigSnapshot, sanitized);
		int[] wh = decodeDimensions(bytes);
		PbArtifactUtil.recordImageMetrics(g.user, artifact, bytes, mimeType, wh[0], wh[1], seed);
		return PbArtifactUtil.readArtifact(g.user, artifact.get(FieldNames.FIELD_OBJECT_ID),
			PbGraphUtil.orgId(node));
	}

	/**
	 * Persist raw bytes as a {@code data.data} in the book world's {@code Gallery}, so an artifact can
	 * point at them.
	 * <p>
	 * <b>This is what replaces the two {@code FileUtil.emitFile("./comp-*.png")} / {@code ("./land-*.png")}
	 * debug dumps</b> (§2.5). Those wrote the classic composite canvas and the landscape to the process
	 * working directory, where nothing could find them, nothing cleaned them up, and they were invisible to
	 * the product - the canvas in particular is the input that actually preserves character likeness, so it
	 * is worth keeping as provenance rather than as a stray file.
	 * <p>
	 * Bytes go in through {@code ByteModelUtil.setValue}, never a raw {@code set} on {@code byteStore}:
	 * {@code data.data} inherits {@code crypto.cryptoByteStore}, so a raw write bypasses
	 * compression/encryption and the matching {@code ByteModelUtil.getValue} read then returns garbage.
	 *
	 * @return the created {@code data.data}, or null (logged) when it could not be persisted
	 */
	public static BaseRecord persistBytes(SceneGraph g, String name, byte[] bytes, String contentType) {
		if(g == null || bytes == null || bytes.length == 0) {
			return null;
		}
		try {
			org.cote.accountmanager.io.ParameterList plist =
				org.cote.accountmanager.io.ParameterList.newParameterList(FieldNames.FIELD_PATH, g.galleryGroupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(
				org.cote.accountmanager.schema.ModelNames.MODEL_DATA, g.user, null, plist);
			org.cote.accountmanager.util.ByteModelUtil.setValue(data, bytes);
			data.set(FieldNames.FIELD_CONTENT_TYPE, contentType != null ? contentType : "image/png");
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(g.user, data);
			if(created == null) {
				logger.error("Failed to persist " + bytes.length + " bytes as '" + name + "' in " + g.galleryGroupPath);
				return null;
			}
			return created;
		}
		catch(Exception e) {
			logger.warn("Failed to persist bytes as '" + name + "': " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Decoded {@code [width, height]}, or {@code [0, 0]}.
	 * <p>
	 * Read from the bytes with {@code ImageIO} rather than taken from the request: the request states what
	 * was <i>asked for</i>, and §9's level-1 assertion is about what actually came back. A hires/refiner
	 * pass that silently returns a different size is exactly the class of defect this catches.
	 */
	public static int[] decodeDimensions(byte[] bytes) {
		if(bytes == null || bytes.length == 0) {
			return new int[] {0, 0};
		}
		try {
			java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
			if(img == null) {
				return new int[] {0, 0};
			}
			return new int[] {img.getWidth(), img.getHeight()};
		}
		catch(Exception e) {
			logger.warn("Could not decode image bytes to measure them: " + e.getMessage());
			return new int[] {0, 0};
		}
	}

	// ─────────────────────────────── node completion ───────────────────────────────

	/**
	 * Close a node out as successful: persist its {@code inputHash}, set {@code DONE}, stamp
	 * {@code lastRunAt}, and mark everything downstream {@code STALE}.
	 * <p>
	 * Order matters. The hash is persisted <b>after</b> the artifact exists and the bindings point at it,
	 * because {@code computeInputHash} reads the bindings' resolved sources - hashing first would freeze a
	 * hash of the previous revision and the node would read as stale immediately after succeeding.
	 * <p>
	 * {@code recomputeStatus} is not used here: it <b>computes and returns</b> (ratification 2) and this is
	 * a write path that knows the node just succeeded. {@code persistStatus} is the only writer.
	 */
	public static void completeNode(SceneGraph g, BaseRecord node) {
		if(g == null || node == null) {
			return;
		}
		try {
			BaseRecord fresh = PbGraphUtil.readNode(g.user, node.get(FieldNames.FIELD_OBJECT_ID),
				PbGraphUtil.orgId(node));
			BaseRecord target = (fresh != null ? fresh : node);
			String inputHash = PbGraphUtil.computeInputHash(g.user, target, g.book);
			PbGraphUtil.persistInputHash(g.user, target, inputHash);
			PbGraphUtil.persistStatus(g.user, target, PbNodeStatusEnumType.DONE);
			stampLastRunAt(g, target);
			PbGraphUtil.markStaleDownstream(g.user, g.workflow, target);
			g.executed++;
		}
		catch(Exception e) {
			logger.warn("Failed to complete node " + node.get(FieldNames.FIELD_NAME) + ": " + e.getMessage(), e);
		}
	}

	/** Close a node out as failed, recording the message on the node itself. */
	public static void failNode(SceneGraph g, BaseRecord node, String error) {
		if(g == null || node == null) {
			return;
		}
		try {
			BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE,
				OlioFieldNames.FIELD_PB_NODE_STATUS, OlioFieldNames.FIELD_PB_LAST_ERROR,
				OlioFieldNames.FIELD_PB_LAST_RUN_AT);
			patch.set(OlioFieldNames.FIELD_PB_NODE_STATUS, PbNodeStatusEnumType.FAILED.toString());
			patch.set(OlioFieldNames.FIELD_PB_LAST_ERROR, error);
			patch.set(OlioFieldNames.FIELD_PB_LAST_RUN_AT, ZonedDateTime.now());
			if(IOSystem.getActiveContext().getAccessPoint().update(g.user, patch) == null) {
				logger.error("Failed to record FAILED on node " + node.get(FieldNames.FIELD_NAME));
			}
			g.failed++;
		}
		catch(Exception e) {
			logger.warn("Failed to fail node " + node.get(FieldNames.FIELD_NAME) + ": " + e.getMessage(), e);
		}
	}

	private static void stampLastRunAt(SceneGraph g, BaseRecord node) {
		BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE,
			OlioFieldNames.FIELD_PB_LAST_RUN_AT);
		try {
			patch.set(OlioFieldNames.FIELD_PB_LAST_RUN_AT, ZonedDateTime.now());
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a lastRunAt patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(g.user, patch) == null) {
			logger.error("Failed to stamp lastRunAt on node " + node.get(FieldNames.FIELD_NAME));
		}
	}

	/**
	 * Close the run out. Synchronous by design - there is no cancel endpoint and
	 * {@code PbRunStatusEnumType} deliberately has no {@code CANCELLED} value, because a value nothing
	 * can set is a false affordance.
	 */
	public static void closeRun(SceneGraph g, boolean ok, String error) {
		if(g == null || g.run == null) {
			return;
		}
		try {
			PbGraphUtil.completeRun(g.user, g.run,
				(ok ? PbRunStatusEnumType.COMPLETED : PbRunStatusEnumType.FAILED),
				g.executed, g.failed, error);
		}
		catch(Exception e) {
			logger.warn("Failed to close run " + g.run.get(FieldNames.FIELD_NAME) + ": " + e.getMessage(), e);
		}
	}

	// ─────────────────────────────── dual write ───────────────────────────────

	/**
	 * Copy the PB1 scene's parsed fields onto the {@code olio.pb.scene} row.
	 * <p>
	 * The other half of the dual write. PB1's {@code data.note} stays authoritative for the 16 PB1
	 * endpoints; this makes {@code setting}/{@code action}/{@code mood} real columns so a phase-4 list
	 * endpoint does not have to parse a blob to show a scene.
	 */
	public static void dualWriteScene(SceneGraph g, String title, String setting, String action, String mood,
			String blurb) {
		if(g == null || g.sceneRow == null) {
			return;
		}
		try {
			BaseRecord patch = PbGraphUtil.patchOf(g.sceneRow, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_TITLE, OlioFieldNames.FIELD_PB_SETTING,
				OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD,
				OlioFieldNames.FIELD_PB_BLURB, OlioFieldNames.FIELD_PB_SCENE_NODE);
			if(title != null) {
				patch.set(OlioFieldNames.FIELD_PB_TITLE, title);
			}
			if(setting != null) {
				patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
			}
			if(action != null) {
				patch.set(OlioFieldNames.FIELD_PB_ACTION, action);
			}
			if(mood != null) {
				patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
			}
			if(blurb != null) {
				patch.set(OlioFieldNames.FIELD_PB_BLURB, blurb);
			}
			BaseRecord composite = g.nodes.get(compositeHandle(g.sceneObjectId));
			if(composite != null) {
				patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, composite);
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(g.user, patch) == null) {
				logger.error("Failed to dual-write scene " + g.sceneRow.get(FieldNames.FIELD_NAME));
			}
		}
		catch(Exception e) {
			logger.warn("Failed to dual-write the PB2 scene row: " + e.getMessage(), e);
		}
	}

	// ─────────────────────────────── handles ───────────────────────────────

	/**
	 * A short, stable discriminator for a scene or character objectId.
	 * <p>
	 * Handles go into node names and therefore into urns, and {@code UrnProvider.getNormalizedString}
	 * strips non-alphanumerics - so the discriminator must survive that transformation and stay unique. A
	 * UUID's first 8 hex characters do; its hyphens would not have mattered either way, but they are
	 * dropped for readability.
	 */
	public static String shortRef(String objectId) {
		if(objectId == null) {
			return "none";
		}
		String s = objectId.replace("-", "");
		return (s.length() > 8 ? s.substring(0, 8) : s);
	}

	public static String scenePromptHandle(String sceneObjectId) {
		return "scene_prompt@" + shortRef(sceneObjectId);
	}

	public static String landscapePromptHandle(String sceneObjectId) {
		return "landscape_prompt@" + shortRef(sceneObjectId);
	}

	public static String landscapeHandle(String sceneObjectId) {
		return "landscape@" + shortRef(sceneObjectId);
	}

	public static String referenceHandle(String sceneObjectId) {
		return "reference@" + shortRef(sceneObjectId);
	}

	public static String compositeHandle(String sceneObjectId) {
		return "composite@" + shortRef(sceneObjectId);
	}

	/**
	 * A portrait node is keyed on the CHARACTER, not the scene, and that is the point: one portrait per
	 * character per book, reused across scenes, exactly matching the pipeline's existing "reuse a
	 * persisted portrait, no re-render" branch. Keying it per scene would create a fresh node (and so a
	 * fresh version chain) for a portrait that was demonstrably not regenerated.
	 */
	public static String portraitHandle(String charObjectId) {
		return "portrait@" + shortRef(charObjectId);
	}

	/** {@code portrait0} / {@code portrait1} - the composite's reference roles, per §2.5's attribution row. */
	public static String portraitRole(int ordinal) {
		return ROLE_PORTRAIT + ordinal;
	}

	/** The node types this phase records, in pipeline order. Exposed so a test can assert coverage. */
	public static List<PbNodeTypeEnumType> recordedNodeTypes() {
		return Collections.unmodifiableList(Arrays.asList(
			PbNodeTypeEnumType.LANDSCAPE_PROMPT, PbNodeTypeEnumType.SCENE_PROMPT,
			PbNodeTypeEnumType.PORTRAIT, PbNodeTypeEnumType.LANDSCAPE,
			PbNodeTypeEnumType.REFERENCE_STRIP, PbNodeTypeEnumType.COMPOSITE));
	}

	/** Every node recorded for one PB1 scene, by handle. Used by the phase-3 test and phase 4's reads. */
	public static List<BaseRecord> listSceneNodes(BaseRecord user, BaseRecord workflow, String sceneObjectId) {
		List<BaseRecord> out = new ArrayList<>();
		for(String h : new String[] {landscapePromptHandle(sceneObjectId), scenePromptHandle(sceneObjectId),
				landscapeHandle(sceneObjectId), referenceHandle(sceneObjectId), compositeHandle(sceneObjectId)}) {
			BaseRecord n = findNodeByHandle(user, workflow, h);
			if(n != null) {
				out.add(n);
			}
		}
		return out;
	}
}
