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
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.util.MemberUtil;

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
	/** {@code node.scope} for a LOCATION node whose {@code scopeRef} is an {@code olio.pb.location} objectId (V3 §7.1, P3-4). */
	public static final String SCOPE_LOCATION = "location";
	/** {@code node.scope} for a LOCATION node whose {@code scopeRef} is an {@code olio.pb.locationVariant} objectId; the variant's {@code location} FK names the location. */
	public static final String SCOPE_LOCATION_VARIANT = "locationVariant";

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
	public static final String ROLE_RENDER_PLAN = "renderPlan";
	/**
	 * The compiled Comfy graph a raw-graph stage posted (V3 §38.3, P5-1): a JSON artifact on the same node as
	 * the image it produced, with image tags only - never image bytes. One revision per render.
	 */
	public static final String ROLE_RENDER_GRAPH = "renderGraph";
	/** The image a RENDER_PLAN node's BASE stage produced (V3 §11, P2-5); same node as its {@code renderPlan} JSON. */
	public static final String ROLE_BASE = "base";
	/**
	 * An intermediate stage image a RENDER_PLAN node's multi-stage render produced (V3 §11, P5-2): the BASE
	 * result before an ACTOR_REGION pass, and each pass's result. Same node as the final {@code base} image;
	 * one revision per stage, with the stage (and actor) in the artifact name.
	 */
	public static final String ROLE_STAGE = "stage";
	/**
	 * Binding role for the {@code olio.pb.location} record (ordinal 0) and its {@code olio.pb.locationVariant}
	 * (ordinal 1) a node renders against (V3 §7, P3-4). A LOCATION node binds its own subject under this role
	 * after a render; a RENDER_PLAN node binds the scene's location so {@code approvedRender} drift stales it.
	 */
	public static final String ROLE_LOCATION = "location";

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
		/// W2: human-readable graph-write failures accumulated during a single scene's recording, so a
		/// swallowed provenance write surfaces on the scene RESULT (not only in the log). Kept non-fatal
		/// to the image - the graph is provenance, and losing provenance must never lose an image the GPU
		/// already spent minutes producing - but no longer silent.
		private final List<String> graphWriteFailures = new ArrayList<>();
		private BaseRecord run;
		private int executed = 0;
		private int failed = 0;
		/// V3 P0-8: set by closeRun (either overload) so generateSceneImage's finally can tell a run
		/// that was closed on the normal path from one the pipeline abandoned mid-flight by throwing.
		private boolean closed = false;
		/// V3 P1-6: the async render job's own progress token, so a stage can notice a cancel that landed
		/// while the backend call was in flight. Null on every other path (sync regenerate, PB1 mirror).
		private SummarizeProgress cancelToken;
		/// V3 P4-4: how the scene plan is built on this graph. NONE everywhere except a Quick-start DRAFT
		/// render from the compose job; read only by the RENDER_PLAN / ChapBook page scene-plan path.
		private PbRenderPlanUtil.PlanOptions planOptions = PbRenderPlanUtil.PlanOptions.NONE;
		/// V3 P6-2: the renderer a plan-driven render on this graph talks to. SWARM everywhere outside tests;
		/// an offline test sets a fake so executeNodeRun / the render job run with no Swarm server (P5-3's
		/// seam, moved onto the graph so it travels with the call rather than through every signature).
		private PbNodeExecutor.RenderBackend renderBackend = PbNodeExecutor.RenderBackend.SWARM;

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

		/** The principal every write on this graph is made as. */
		public BaseRecord getUser() {
			return user;
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

		/** Nodes completed on this graph so far ({@link PbPipelineUtil#completeNode}); copied onto the run by closeRun. */
		public int getExecuted() {
			return executed;
		}

		/** Nodes failed on this graph so far ({@link PbPipelineUtil#failNode}); copied onto the run by closeRun. */
		public int getFailed() {
			return failed;
		}

		/**
		 * True once {@link PbPipelineUtil#closeRun(SceneGraph, PbRunStatusEnumType, String)} has been
		 * invoked on this graph (whether or not the run-row write itself succeeded — closeRun is
		 * best-effort and logs rather than throws). {@code generateSceneImage}'s finally consults this to
		 * close a run the pipeline abandoned by throwing, and only that one.
		 */
		public boolean isRunClosed() {
			return closed;
		}

		/** The async job's cancel token, or null when this graph is not being executed by a render job. */
		public SummarizeProgress getCancelToken() {
			return cancelToken;
		}

		/** True only when a render job owns this graph and its cancel flag has been raised. */
		public boolean isCancelRequested() {
			return (cancelToken != null && cancelToken.isCancelled());
		}

		/** Never null; {@link PbRenderPlanUtil.PlanOptions#NONE} unless a Quick-start render set a preset. */
		public PbRenderPlanUtil.PlanOptions getPlanOptions() {
			return planOptions;
		}

		void setPlanOptions(PbRenderPlanUtil.PlanOptions opts) {
			this.planOptions = (opts != null ? opts : PbRenderPlanUtil.PlanOptions.NONE);
		}

		void setCancelToken(SummarizeProgress token) {
			this.cancelToken = token;
		}

		/** Never null; {@link PbNodeExecutor.RenderBackend#SWARM} unless an offline test set a fake. */
		PbNodeExecutor.RenderBackend getRenderBackend() {
			return renderBackend;
		}

		void setRenderBackend(PbNodeExecutor.RenderBackend backend) {
			this.renderBackend = (backend != null ? backend : PbNodeExecutor.RenderBackend.SWARM);
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

		/**
		 * W2: record one concise, human-readable graph-write failure (what write failed + the exception
		 * message). Accumulated for the life of this scene's recording and attached to the scene result
		 * by {@code generateSceneImage} so the failure is visible in the API response, not only the log.
		 */
		public void addGraphWriteFailure(String failure) {
			if(failure != null && !failure.isBlank()) {
				graphWriteFailures.add(failure);
			}
		}

		/** W2: the graph-write failures accumulated during this scene's recording (never null). */
		public List<String> getGraphWriteFailures() {
			return graphWriteFailures;
		}
	}

	// ─────────────────────────────── open ───────────────────────────────

	/**
	 * Resolve the graph for one PB1 scene, creating the workflow/scene/node skeleton inside an
	 * <b>existing</b> book if it is not there yet.
	 * <p>
	 * Returns <b>null</b>, having logged why, when graph recording cannot proceed - no book for this
	 * slug, no world, or an unauthorized write. A null return is the pipeline's signal to run the image
	 * pipeline without recording the graph, which is what keeps the "graph failure never loses an image"
	 * property true.
	 *
	 * @param slug the book's PB2 slug; when null, derived from {@code pb1BookGroupName} by
	 *        {@link #deriveSlug}
	 */
	public static SceneGraph openSceneGraph(BaseRecord user, String slug, String pb1BookGroupName,
			String sceneObjectId, int sceneIndex, String sceneTitle) {
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
			/// Provenance must never break rendering, but a skipped recording is what leaves a rendered image
			/// with no olio.pb.scene row ("N scenes extracted - none rendered yet"), so it is an ERROR with the
			/// remedy named, not a WARN that scrolls past.
			logger.error("PB2 recording skipped for scene " + sceneObjectId + " (index " + sceneIndex + ") of book "
				+ slug + ": " + e.getMessage() + ". The image will be saved but no scene row will be recorded;"
				+ " run the book health check (GET /olio/picture-book/{id}/health) and Repair to purge any stale"
				+ " workflow left by a previously deleted copy of this book and to backfill the scene rows.", e);
			return null;
		}
	}

	/**
	 * Open a graph handle for executing one <b>existing</b> node directly - the V3 path where the graph
	 * is the input rather than something recorded beside a PB1 render.
	 * <p>
	 * Unlike {@link #openSceneGraph} there is no PB1 scene to mirror, so {@code sceneRow} and
	 * {@code sceneObjectId} are null and {@link #dualWriteScene} is a no-op; {@code sceneIndex} is the
	 * node's own {@code sceneIndex} (0 when unset), which is what scene-tagged apparel selection keys on.
	 * Bytes land in the artifact group - the graph is the canonical home here, not the PB1 gallery.
	 * <p>
	 * Nothing is created: the book, workflow and node must already exist. The {@code requestedNodeIds}
	 * of the run names exactly this node.
	 *
	 * @param withRun when true an {@code olio.pb.run} is opened (PENDING; {@link PbGraphUtil#markRunning}
	 *        stamps RUNNING) and pointed to by {@code workflow.lastRun}; when false no run row is written,
	 *        which is the node-only execution mode that must not leak PENDING rows
	 * @throws PictureBookException 400 when a required record is missing
	 */
	public static SceneGraph openNodeGraph(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord node,
			boolean withRun) {
		SceneGraph g = nodeGraph(user, book, workflow, node);
		if(withRun) {
			Long nodeId = node.get(FieldNames.FIELD_ID);
			List<Long> requested = new ArrayList<>();
			if(nodeId != null && nodeId.longValue() > 0L) {
				requested.add(nodeId);
			}
			g.run = PbGraphUtil.startRun(user, workflow, g.workflowGroupPath, requested);
			PbGraphUtil.persistLastRun(user, workflow, g.run);
		}
		return g;
	}

	/**
	 * Open a graph handle for executing one stage of an <b>already-open</b> multi-node run (V3 §21, the
	 * async render job). Nothing is created or written: the run row was opened by the submitter and is
	 * closed by the job, so this handle carries it but never starts or closes it. {@code cancelToken} is
	 * the job's own progress token, consulted by the executor after a backend call returns so a cancelled
	 * stage discards its bytes instead of persisting them.
	 *
	 * @throws PictureBookException 400 when a required record is missing
	 */
	public static SceneGraph openNodeGraph(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord node,
			BaseRecord run, SummarizeProgress cancelToken) {
		SceneGraph g = nodeGraph(user, book, workflow, node);
		g.run = run;
		g.cancelToken = cancelToken;
		return g;
	}

	private static SceneGraph nodeGraph(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord node) {
		if(user == null || book == null || workflow == null || node == null) {
			throw new PictureBookException(400, "A user, book, workflow and node are required to open a node graph");
		}
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null || slug.isBlank()) {
			throw new PictureBookException(400, "Book " + book.get(FieldNames.FIELD_OBJECT_ID) + " has no slug");
		}
		String workflowPath = PbBookUtil.workflowGroupPath(slug);
		String artifactPath = PbBookUtil.artifactGroupPath(slug);
		Integer sceneIndex = node.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int useIndex = (sceneIndex != null && sceneIndex.intValue() > 0) ? sceneIndex.intValue() : 0;
		return new SceneGraph(user, book, workflow, null, workflowPath, artifactPath, artifactPath, null, useIndex);
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
		patchNodeScope(g.user, g.workflow, node, scope, scopeRef, g.sceneIndex);
	}

	/**
	 * Record {@code scope}/{@code scopeRef}/{@code sceneIndex}/{@code createdByObjectId} on a node
	 * without a {@link SceneGraph} (V3 P1-9: the ChapBook page node is created lazily at render time,
	 * outside the scene pipeline). Returns whether the patch persisted; the failure is also logged.
	 */
	static boolean patchNodeScope(BaseRecord user, BaseRecord workflow, BaseRecord node, String scope,
			String scopeRef, int sceneIndex) {
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
			patch.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, Integer.valueOf(sceneIndex));
			patch.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, user.get(FieldNames.FIELD_OBJECT_ID));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a node scope patch: " + e.getMessage());
		}
		/// Never discard an update result - it is the only signal a persistent failure gives.
		boolean ok = (IOSystem.getActiveContext().getAccessPoint().update(user, patch) != null);
		if(!ok) {
			logger.error("Failed to record scope on node " + node.get(FieldNames.FIELD_NAME));
		}
		PbGraphUtil.invalidateScan(workflow);
		return ok;
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
				PbGraphUtil.invalidateScan(g.workflow);
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
			PbGraphUtil.invalidateScan(g.workflow);
			return PbGraphUtil.readBinding(g.user, existing.get(FieldNames.FIELD_OBJECT_ID),
				PbGraphUtil.orgId(consumer));
		}
		return PbGraphUtil.addRecordBinding(g.user, g.workflow, consumer, role, ordinal, refModel, refObjectId,
			g.workflowGroupPath);
	}

	/**
	 * {@link #bindRecord} that also follows a reference that moved or went away between runs: an existing
	 * binding on {@code (consumer, role, ordinal)} that points at another record is dropped and re-bound,
	 * and a null {@code refObjectId} drops it. The scene → location leg uses this (a scene re-pointed at
	 * another location, or at none, between two renders).
	 *
	 * @return the binding, or null when nothing is bound
	 */
	public static BaseRecord syncRecordBinding(SceneGraph g, BaseRecord consumer, String role, int ordinal,
			String refModel, String refObjectId) {
		if(g == null || consumer == null) {
			return null;
		}
		BaseRecord existing = findBinding(g.user, consumer, role, ordinal);
		if(existing != null) {
			String bound = existing.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
			if(refObjectId != null && refObjectId.equals(bound)) {
				return bindRecord(g, consumer, role, ordinal, refModel, refObjectId);
			}
			if(!IOSystem.getActiveContext().getAccessPoint().delete(g.user, existing)) {
				throw new PictureBookException(500, "Failed to drop binding " + existing.get(FieldNames.FIELD_NAME)
					+ " whose reference changed");
			}
			PbGraphUtil.invalidateScan(g.workflow);
		}
		if(refObjectId == null) {
			return null;
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
		return recordImage(g, node, role, artifactType, data, bytes, mimeType, seed, sdConfigSnapshot, rawRequestJson,
			referenceArtifactObjectIds, null, 0L);
	}

	/**
	 * {@link #recordImage(SceneGraph, BaseRecord, String, PbArtifactTypeEnumType, BaseRecord, byte[], String, Long, BaseRecord, String, List)}
	 * plus the backend that produced the bytes and the wall-clock of the call (V3 §21; recorded, never asserted).
	 */
	public static BaseRecord recordImage(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, BaseRecord data, byte[] bytes, String mimeType, Long seed,
			BaseRecord sdConfigSnapshot, String rawRequestJson, List<String> referenceArtifactObjectIds,
			SDAPIEnumType backend, long durationMs) {
		return recordImage(g, node, role, artifactType, data, bytes, mimeType, seed, sdConfigSnapshot, rawRequestJson,
			referenceArtifactObjectIds, backend, durationMs, null);
	}

	/**
	 * {@link #recordImage(SceneGraph, BaseRecord, String, PbArtifactTypeEnumType, BaseRecord, byte[], String, Long, BaseRecord, String, List, SDAPIEnumType, long)}
	 * plus the raw-graph provenance of a V3 §38 stage ({@code backendGraph}: stage, graphHash, saveNodeId,
	 * swarmVersion - never the graph body; P5-2 §3.4). Written only when non-null.
	 */
	public static BaseRecord recordImage(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, BaseRecord data, byte[] bytes, String mimeType, Long seed,
			BaseRecord sdConfigSnapshot, String rawRequestJson, List<String> referenceArtifactObjectIds,
			SDAPIEnumType backend, long durationMs, String backendGraph) {
		return recordImage(g, node, role, artifactType, data, bytes, mimeType, seed, sdConfigSnapshot, rawRequestJson,
			referenceArtifactObjectIds, backend, durationMs, backendGraph, null);
	}

	/**
	 * {@link #recordImage(SceneGraph, BaseRecord, String, PbArtifactTypeEnumType, BaseRecord, byte[], String, Long, BaseRecord, String, List, SDAPIEnumType, long, String)}
	 * stamped with the {@code attempt} that produced it (V3 §23, P6-2: the run objectId, or null outside a
	 * run). A single-variant record: variantIndex 0, and the artifact it supersedes is the role's current
	 * selection at the time of the write, exactly as before.
	 */
	public static BaseRecord recordImage(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, BaseRecord data, byte[] bytes, String mimeType, Long seed,
			BaseRecord sdConfigSnapshot, String rawRequestJson, List<String> referenceArtifactObjectIds,
			SDAPIEnumType backend, long durationMs, String backendGraph, String attempt) {
		return recordImageCore(g, node, role, artifactType, data, bytes, mimeType, seed, sdConfigSnapshot, rawRequestJson,
			referenceArtifactObjectIds, backend, durationMs, backendGraph, null, 0, attempt, true);
	}

	/**
	 * One variant of a batch (V3 §23, P6-2): every final of the attempt is written against the SAME
	 * pre-batch selection ({@code supersedes}, captured by the caller with
	 * {@code PbArtifactUtil.findSelected} before anything was recorded) so variant 1 never supersedes
	 * variant 0, and only variant 0 takes the selection (§10 rule 1; {@code PbArtifactUtil.persistArtifact}).
	 */
	public static BaseRecord recordImage(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, BaseRecord data, byte[] bytes, String mimeType, Long seed,
			BaseRecord sdConfigSnapshot, String rawRequestJson, List<String> referenceArtifactObjectIds,
			SDAPIEnumType backend, long durationMs, String backendGraph, BaseRecord supersedes, int variantIndex, String attempt) {
		return recordImageCore(g, node, role, artifactType, data, bytes, mimeType, seed, sdConfigSnapshot, rawRequestJson,
			referenceArtifactObjectIds, backend, durationMs, backendGraph, supersedes, variantIndex, attempt, false);
	}

	private static BaseRecord recordImageCore(SceneGraph g, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, BaseRecord data, byte[] bytes, String mimeType, Long seed,
			BaseRecord sdConfigSnapshot, String rawRequestJson, List<String> referenceArtifactObjectIds,
			SDAPIEnumType backend, long durationMs, String backendGraph, BaseRecord supersedes, int variantIndex,
			String attempt, boolean resolveSupersedes) {
		if(g == null || node == null) {
			return null;
		}
		String sanitized = PbArtifactUtil.sanitizeGeneratorRequest(rawRequestJson, referenceArtifactObjectIds);
		BaseRecord artifact = PbArtifactUtil.persistArtifact(g.user, node, role, artifactType,
			g.artifactGroupPath, data, null, sdConfigSnapshot, sanitized, supersedes, variantIndex, attempt, resolveSupersedes);
		int[] wh = decodeDimensions(bytes);
		PbArtifactUtil.recordImageMetrics(g.user, artifact, bytes, mimeType, wh[0], wh[1], seed, backend, durationMs, backendGraph);
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
		return org.cote.accountmanager.olio.illustration.ImageDims.decode(bytes);
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
		completeNode(g, node, PbNodeStatusEnumType.DONE);
	}

	/**
	 * {@link #completeNode(SceneGraph, BaseRecord)} closing the node at {@code status} rather than DONE.
	 * V3 P1-9: a reviewed node type (ChapBook page) completes at {@code DONE_UNVERIFIED} and still needs
	 * its {@code inputHash}/{@code lastRunAt} so the drift scan can tell current from stale.
	 */
	public static void completeNode(SceneGraph g, BaseRecord node, PbNodeStatusEnumType status) {
		if(g == null || node == null) {
			return;
		}
		try {
			BaseRecord fresh = PbGraphUtil.readNode(g.user, node.get(FieldNames.FIELD_OBJECT_ID),
				PbGraphUtil.orgId(node));
			BaseRecord target = (fresh != null ? fresh : node);
			String inputHash = PbGraphUtil.computeInputHash(g.user, target, g.book);
			PbGraphUtil.persistInputHash(g.user, target, inputHash);
			PbGraphUtil.persistStatus(g.user, target, (status != null ? status : PbNodeStatusEnumType.DONE));
			stampLastRunAt(g, target);
			PbGraphUtil.markStaleDownstream(g.user, g.workflow, target);
			g.executed++;
		}
		catch(Exception e) {
			logger.warn("Failed to complete node " + node.get(FieldNames.FIELD_NAME) + ": " + e.getMessage(), e);
		}
	}

	/** Close a node out as failed, recording the message on the node itself, and count it on the run. */
	public static void failNode(SceneGraph g, BaseRecord node, String error) {
		if(g == null || node == null) {
			return;
		}
		try {
			failNode(g.user, node, error);
			g.failed++;
		}
		catch(Exception e) {
			logger.warn("Failed to fail node " + node.get(FieldNames.FIELD_NAME) + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Stamp {@code node} {@code FAILED} with {@code lastError}/{@code lastRunAt}, without a
	 * {@link SceneGraph}. V3 P0-8: this is the write {@link #failNode(SceneGraph, BaseRecord, String)}
	 * always did; split out so a caller with no graph handle can record a failure the same way the
	 * scene pipeline does. Since V3 P1-3 {@link PbNodeExecutor} always holds a handle from
	 * {@link #openNodeGraph} and goes through the graph overload (which counts the failure on the run);
	 * this one remains for the pre-handle gates and for callers outside the executor. Best-effort: a
	 * failed write is logged, never thrown — the caller is already propagating the real exception.
	 */
	static void failNode(BaseRecord user, BaseRecord node, String error) {
		if(user == null || node == null) {
			return;
		}
		try {
			BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE,
				OlioFieldNames.FIELD_PB_NODE_STATUS, OlioFieldNames.FIELD_PB_LAST_ERROR,
				OlioFieldNames.FIELD_PB_LAST_RUN_AT);
			patch.set(OlioFieldNames.FIELD_PB_NODE_STATUS, PbNodeStatusEnumType.FAILED.toString());
			patch.set(OlioFieldNames.FIELD_PB_LAST_ERROR, error);
			patch.set(OlioFieldNames.FIELD_PB_LAST_RUN_AT, ZonedDateTime.now());
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.error("Failed to record FAILED on node " + node.get(FieldNames.FIELD_NAME));
			}
			PbGraphUtil.invalidateScanOfNode(node);
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
		PbGraphUtil.invalidateScan(g.workflow);
	}

	/**
	 * Close the run out as COMPLETED or FAILED.
	 *
	 * <p>Use {@link #closeRun(SceneGraph, PbRunStatusEnumType, String)} to record a cancel: a
	 * user's own cancellation is neither of these two outcomes, and this boolean cannot express it.
	 */
	public static void closeRun(SceneGraph g, boolean ok, String error) {
		closeRun(g, ok ? PbRunStatusEnumType.COMPLETED : PbRunStatusEnumType.FAILED, error);
	}

	/**
	 * Close the run out with an explicit terminal status.
	 *
	 * <p>Exists so {@code CANCELLED} is actually recordable now that the ChapBook bulk operations
	 * run on the async job layer and can genuinely be stopped mid-flight. The older comment here
	 * asserted runs were synchronous with no cancel endpoint; that is no longer true.
	 *
	 * <p>Still best-effort: a failure to write the run row is logged, not thrown. That is a
	 * deliberate carry-over — the graph row is bookkeeping alongside the authoritative PB1 records,
	 * and losing the status stamp must not fail an operation whose real output already persisted.
	 * It does mean a run row can be left open if the write fails or if this is never called, so the
	 * row is not a reliable liveness signal; {@code GET /rest/job} is. Since V3 P0-8
	 * {@code generateSceneImage} closes the run FAILED from its finally when the pipeline throws, so
	 * "never called" is now limited to the write failing. Which open status is left behind depends on
	 * the path: {@link PbGraphUtil#startRun} writes PENDING, and since V3 P1-3
	 * {@link PbNodeExecutor#executeNodeRun} stamps RUNNING ({@link PbGraphUtil#markRunning}) before
	 * dispatch, so an abandoned executor run reads RUNNING (recoverable by the restart sweep) while an
	 * abandoned PB1 scene run still reads PENDING (the PB1 pipeline does not stamp RUNNING).
	 *
	 * <p>Marks the graph closed ({@link SceneGraph#isRunClosed}) whether or not the write succeeds,
	 * and even when there is no run row to close — "closed" means closeRun has been invoked, so the
	 * pipeline's finally does not retry it.
	 */
	public static void closeRun(SceneGraph g, PbRunStatusEnumType status, String error) {
		if(g == null) {
			return;
		}
		g.closed = true;
		if(g.run == null) {
			return;
		}
		try {
			PbGraphUtil.completeRun(g.user, g.run, status, g.executed, g.failed, error);
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

	/**
	 * The import half of the dual write: one {@code olio.pb.scene} row per scene {@code createFromScenes}
	 * just saved into a PB2 book, so the compose job that follows the import (V3 §9.1, "straight after
	 * createFromScenes") has scenes to compose instead of tallying none. Until now a row only appeared when
	 * the scene first rendered ({@link #openSceneGraph}), which is exactly the moment composing up front
	 * is meant to precede.
	 * <p>
	 * Idempotent by {@code sceneIndex}: a re-run of the import refreshes the existing row (text fields only,
	 * never the node pointer a render set) rather than forking a second one. The text fields are the ones
	 * {@link #dualWriteScene} mirrors plus {@code description}, and the scene's extracted cast is enrolled
	 * as {@code characters} participations (idempotent), which is what the compose cast and
	 * {@code PbRenderPlanUtil.sceneActors} read before falling back to the whole population. Every write is
	 * made as the request user through {@code AccessPoint}. Never throws: the PB1 note a row mirrors is
	 * already saved, so a row that cannot be written is logged and the import still succeeds.
	 *
	 * @param sceneList  the imported scene maps, in story order (index = {@code sceneIndex})
	 * @param metaScenes the {@code olio.pictureBookScene} entries built from them, same order; each carries
	 *                   the resolved {@code charPerson} objectIds of its cast as {@code characters}
	 * @return the number of rows created or refreshed
	 */
	public static int dualWriteImportedScenes(BaseRecord user, BaseRecord book, List<Map<String, Object>> sceneList,
			List<BaseRecord> metaScenes) {
		if(user == null || book == null || sceneList == null || sceneList.isEmpty()) {
			return 0;
		}
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null || slug.isBlank()) {
			logger.warn("Import dual write skipped: book " + book.get(FieldNames.FIELD_OBJECT_ID) + " has no slug");
			return 0;
		}
		int written = 0;
		try {
			long orgId = PbGraphUtil.orgId(book);
			String groupPath = PbBookUtil.bookGroupPath(slug);
			Map<Integer, BaseRecord> byIndex = new LinkedHashMap<>();
			for(BaseRecord s : PbBookUtil.listScenes(user, book)) {
				Integer idx = s.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
				if(idx != null && !byIndex.containsKey(idx)) {
					byIndex.put(idx, s);
				}
			}
			Map<String, BaseRecord> people = new LinkedHashMap<>();
			for(int idx = 0; idx < sceneList.size(); idx++) {
				Map<String, Object> sceneData = sceneList.get(idx);
				if(sceneData == null) {
					continue;
				}
				BaseRecord entry = (metaScenes != null && idx < metaScenes.size()) ? metaScenes.get(idx) : null;
				try {
					Object t = sceneData.get(OlioFieldNames.FIELD_PB_TITLE);
					String title = (t instanceof String && !((String) t).isBlank()) ? (String) t : "Scene " + idx;
					BaseRecord row = byIndex.get(Integer.valueOf(idx));
					if(row == null) {
						row = PbBookUtil.createScene(user, book, idx, title, groupPath);
						byIndex.put(Integer.valueOf(idx), row);
					}
					Map<String, Object> data = new LinkedHashMap<>(PictureBookUtil.sceneNoteStore(sceneData, idx));
					data.put(OlioFieldNames.FIELD_PB_TITLE, title);
					Object blurb = data.get(OlioFieldNames.FIELD_PB_BLURB);
					if(blurb instanceof String && !((String) blurb).isBlank()) {
						data.put(FieldNames.FIELD_DESCRIPTION, blurb);
					}
					List<String> warnings = new ArrayList<>();
					if(!patchImportedSceneText(user, row, data, warnings)) {
						logger.warn("Import dual write: scene " + idx + " of '" + slug + "' row text not refreshed: " + warnings);
					}
					enrolImportedCast(user, row, entry, orgId, people, slug, idx);
					written++;
				}
				catch(Exception e) {
					logger.error("Import dual write: no olio.pb.scene row for scene " + idx + " of '" + slug + "': " + e.getMessage()
						+ ". The PB1 scene note is saved; the row is created on first render or by the book health Repair.", e);
				}
			}
		}
		catch(Exception e) {
			logger.error("Import dual write failed for '" + slug + "': " + e.getMessage(), e);
		}
		return written;
	}

	/// dualWriteScene's field set plus summary/description, PATCH-shaped (identity + the fields set), as the user.
	private static boolean patchImportedSceneText(BaseRecord user, BaseRecord row, Map<String, Object> data,
			List<String> warnings) {
		String[] candidates = { OlioFieldNames.FIELD_PB_TITLE, OlioFieldNames.FIELD_PB_SUMMARY, OlioFieldNames.FIELD_PB_SETTING,
			OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_PB_BLURB, FieldNames.FIELD_DESCRIPTION };
		List<String> toSet = new ArrayList<>();
		for(String f : candidates) {
			Object v = data.get(f);
			if(v instanceof String && !((String) v).isBlank()) {
				toSet.add(f);
			}
		}
		if(toSet.isEmpty()) {
			return true;
		}
		try {
			BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_SCENE, toSet.toArray(new String[0]));
			for(String f : toSet) {
				patch.set(f, ((String) data.get(f)).trim());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				warnings.add("text patch of " + row.get(FieldNames.FIELD_NAME) + " was not persisted");
				return false;
			}
			return true;
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			warnings.add("text patch of " + row.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			return false;
		}
	}

	/**
	 * Enrol the meta entry's charPerson objectIds as {@code scene.characters}, each through
	 * {@code AccessPoint.member} so PBAC decides: the user must be able to read the person and update the scene
	 * row, or the participation is refused (logged, never thrown) and the import carries on. A person the user
	 * cannot read is skipped before the attempt.
	 */
	private static void enrolImportedCast(BaseRecord user, BaseRecord row, BaseRecord entry, long orgId,
			Map<String, BaseRecord> people, String slug, int idx) {
		if(entry == null || !entry.hasField(OlioFieldNames.FIELD_PB_CHARACTERS)) {
			return;
		}
		List<String> oids = entry.get(OlioFieldNames.FIELD_PB_CHARACTERS);
		if(oids == null || oids.isEmpty()) {
			return;
		}
		for(String oid : oids) {
			if(oid == null || oid.isBlank()) {
				continue;
			}
			BaseRecord person = people.get(oid);
			if(person == null) {
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
				q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
				q.setCache(false);
				person = IOSystem.getActiveContext().getAccessPoint().find(user, q);
				if(person == null) {
					logger.warn("Import dual write: scene " + idx + " of '" + slug + "': cast member " + oid + " not readable; not enrolled");
					continue;
				}
				people.put(oid, person);
			}
			try {
				MemberUtil mu = IOSystem.getActiveContext().getMemberUtil();
				/// AccessPoint.member answers false when PBAC refuses (cannot read the person or update the
				/// scene), when the row could not be written, and when the participation already exists (a
				/// re-import); only the first two are worth a warning.
				if(!IOSystem.getActiveContext().getAccessPoint().member(user, row, OlioFieldNames.FIELD_PB_CHARACTERS, person, null, true)
						&& !mu.isMember(person, row, OlioFieldNames.FIELD_PB_CHARACTERS)) {
					logger.warn("Import dual write: scene " + idx + " of '" + slug + "': cast member "
						+ person.get(FieldNames.FIELD_NAME) + " was not enrolled");
				}
			}
			catch(Exception e) {
				logger.warn("Import dual write: scene " + idx + " of '" + slug + "': enrolling " + person.get(FieldNames.FIELD_NAME)
					+ " failed: " + e.getMessage());
			}
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

	/** The scene's RENDER_PLAN node (V3 §12, P2-5): one per scene, beside its landscape/composite nodes, never {@code scene.sceneNode}. */
	public static String renderPlanHandle(String sceneObjectId) {
		return "render_plan@" + shortRef(sceneObjectId);
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

	/**
	 * A LOCATION node is keyed on the location record (V3 §7.1, P3-4): one node per location per book, its
	 * version chain holding every render of that location, so scenes 3, 7 and 12 share one approved tavern.
	 */
	public static String locationHandle(String locationObjectId) {
		return "location@" + shortRef(locationObjectId);
	}

	/** One LOCATION node per (location, variant) pair; keyed on the variant, whose {@code location} FK names the location. */
	public static String locationVariantHandle(String variantObjectId) {
		return "location_variant@" + shortRef(variantObjectId);
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
