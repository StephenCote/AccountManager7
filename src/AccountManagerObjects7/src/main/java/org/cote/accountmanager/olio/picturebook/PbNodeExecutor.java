package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.ComfyGraph;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ConfigNotApplied;
import org.cote.accountmanager.olio.illustration.Flux2Compiler;
import org.cote.accountmanager.olio.illustration.GraphCompileException;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmRendererAdapter;
import org.cote.accountmanager.olio.illustration.WorkflowCompiler;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil.SceneGraph;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.ResolvedCharacter;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.Flux2Defaults;
import org.cote.accountmanager.olio.sd.PbCalibrationUtil;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Single-node executor for the PB2 canvas. Drives one node synchronously, persists a new artifact
 * revision, marks it selected, and propagates staleness downstream.
 * <p>
 * PORTRAIT, LANDSCAPE, SCENE_PROMPT, LANDSCAPE_PROMPT, REFERENCE_STRIP, COMPOSITE and (V3 P2-5)
 * RENDER_PLAN are implemented. Callers discover unsupported types via a 501 {@link PictureBookException}
 * so the API can be extended incrementally without silent no-ops.
 * <p>
 * Two entry points (V3 P1-3):
 * <ul>
 * <li>{@link #executeNode(BaseRecord, BaseRecord, BaseRecord, BaseRecord, String, String)} runs the
 * node <b>without</b> an {@code olio.pb.run} row — the canvas "Test" action and the single-node tests.
 * The node is never stamped RUNNING on this path, so an abandoned call leaves nothing for the restart
 * sweep (design §21) to find; that is correct for a synchronous call with no run to recover.</li>
 * <li>{@link #executeNodeRun(BaseRecord, BaseRecord, BaseRecord, BaseRecord, String, String)} opens a
 * run for the one node, stamps the run and the node RUNNING before dispatch, and closes the run
 * COMPLETED or FAILED. This is what {@code PbServiceFacade.requestRegenerate} submits.</li>
 * </ul>
 * Both take the SD backend as the {@code (sdApiType, sdServer)} pair the caller resolved from its own
 * deployment config, exactly as {@code PictureBookUtil.generateSceneImage} does; a null or blank server
 * yields no {@link SDUtil} and the SD-backed types refuse with a 500 before touching the graph.
 */
public class PbNodeExecutor {

	public static final Logger logger = LogManager.getLogger(PbNodeExecutor.class);

	/**
	 * The node types {@link #executeNode} can drive on their own. This is the single source of truth
	 * for the {@code switch} below, for the {@code executable} flag the graph DTO carries per node
	 * ({@code PbServiceFacade.nodeSummary}), and for the 501 message. The remaining
	 * {@link PbNodeTypeEnumType} values (SOURCE_TEXT, SCENE_EXTRACT, SCENE, CHARACTER,
	 * CHARACTER_DESCRIPTION, APPAREL, MANNEQUIN, PAGE, BOOK_ASSEMBLY, STYLE_BIBLE) are stages the
	 * whole-book pipeline ({@code PictureBookUtil.createFromScenes} / {@code PbPipelineUtil}) produces
	 * as a unit; they have no single-node executor, so the canvas must not offer "Test" on them.
	 */
	public static final Set<PbNodeTypeEnumType> EXECUTABLE_TYPES = Collections.unmodifiableSet(EnumSet.of(
		PbNodeTypeEnumType.PORTRAIT,
		PbNodeTypeEnumType.LANDSCAPE,
		PbNodeTypeEnumType.SCENE_PROMPT,
		PbNodeTypeEnumType.LANDSCAPE_PROMPT,
		PbNodeTypeEnumType.REFERENCE_STRIP,
		PbNodeTypeEnumType.COMPOSITE,
		PbNodeTypeEnumType.RENDER_PLAN,
		PbNodeTypeEnumType.LOCATION
	));

	/** The subset of {@link #EXECUTABLE_TYPES} that needs an SD backend; the rest are pure Java / DB work. */
	public static final Set<PbNodeTypeEnumType> SD_BACKED_TYPES = Collections.unmodifiableSet(EnumSet.of(
		PbNodeTypeEnumType.PORTRAIT,
		PbNodeTypeEnumType.LANDSCAPE,
		PbNodeTypeEnumType.COMPOSITE,
		PbNodeTypeEnumType.RENDER_PLAN,
		PbNodeTypeEnumType.LOCATION
	));

	private PbNodeExecutor() {
		/// static utility
	}

	/** True when {@link #executeNode} has an implementation for {@code nodeType}. Null is not executable. */
	public static boolean isExecutable(PbNodeTypeEnumType nodeType) {
		return nodeType != null && EXECUTABLE_TYPES.contains(nodeType);
	}

	/** {@link #isExecutable(PbNodeTypeEnumType)} read off a node record's {@code nodeType} field. */
	public static boolean isExecutable(BaseRecord node) {
		if(node == null || !node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE)) {
			return false;
		}
		PbNodeTypeEnumType nodeType = node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
		return isExecutable(nodeType);
	}

	/** Stable, sorted names of {@link #EXECUTABLE_TYPES} for messages and DTOs. */
	public static List<String> executableTypeNames() {
		List<String> names = new ArrayList<>();
		for(PbNodeTypeEnumType t : EXECUTABLE_TYPES) {
			names.add(t.name());
		}
		Collections.sort(names);
		return names;
	}

	/**
	 * The {@link SDUtil} for a resolved {@code (sdApiType, sdServer)} pair, or null when either is
	 * missing. Mirrors {@code PictureBookUtil.generateSceneImage(String, String)}: the executor never
	 * assumes SWARM and never invents a server. A blank server is treated as missing — {@code SDUtil}'s
	 * constructor accepts a null/blank server without complaint and the failure would otherwise surface
	 * only as a connection error deep inside the render.
	 */
	static SDUtil toSdUtil(String sdApiType, String sdServer) {
		if(sdApiType == null || sdApiType.isBlank() || sdServer == null || sdServer.isBlank()) {
			return null;
		}
		return new SDUtil(SDAPIEnumType.valueOf(sdApiType), sdServer);
	}

	// ─── entry points ────────────────────────────────────────────────────────────

	/**
	 * Execute {@code node} against the SWARM backend at {@code swarmServer}. Retained for callers that
	 * predate the {@code (sdApiType, sdServer)} form; it is exactly
	 * {@code executeNode(user, book, workflow, node, "SWARM", swarmServer)}.
	 */
	public static Map<String, Object> executeNode(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String swarmServer) {
		return executeNode(user, book, workflow, node, SDAPIEnumType.SWARM.name(), swarmServer);
	}

	/**
	 * Execute {@code node} against the SD backend at {@code sdServer} and persist a new artifact
	 * revision. <b>No run row is opened</b> — see the class comment.
	 * <p>
	 * {@code book} and {@code workflow} must be the fully-loaded records from
	 * {@link PbServiceFacade#requireBook} / {@link PbServiceFacade#requireWorkflow} — not the shallow FK
	 * stubs that come back on the node. This is a requirement because {@link PbGraphUtil#downstreamOf} (and
	 * {@link PbPipelineUtil#completeNode}'s {@link PbGraphUtil#markStaleDownstream})
	 * calls {@link PbGraphUtil#listNodes} which conditions on the workflow record and reads its {@code id}
	 * via {@link org.cote.accountmanager.io.StatementUtil}.
	 * <p>
	 * Gate order, before any IO: 400 for a null node; 501 for a type outside {@link #EXECUTABLE_TYPES}
	 * (the message names the supported types so the caller can tell a product gap from a broken request);
	 * 500 "SD server not configured" for an SD-backed type with no backend. Only then is the graph opened.
	 *
	 * @param sdApiType the {@code SDAPIEnumType} name (e.g. "SWARM") the caller resolved from its config
	 * @param sdServer the SD backend base URL, same resolution; null/blank means "not configured"
	 * @return DTO map describing the new artifact
	 */
	public static Map<String, Object> executeNode(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String sdApiType, String sdServer) {
		return executeNode(user, book, workflow, node, toSdUtil(sdApiType, sdServer));
	}

	/**
	 * Body of {@link #executeNode(BaseRecord, BaseRecord, BaseRecord, BaseRecord, String, String)} taking
	 * an already-constructed {@link SDUtil}. Package-private so a test can drive the executor through an
	 * {@code SDUtil} subclass (one whose {@code createImage} throws, or observes state mid-call) without a
	 * Swarm backend having to misbehave on cue.
	 */
	static Map<String, Object> executeNode(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, SDUtil sdu) {
		PbNodeTypeEnumType nodeType = gate(node, sdu);
		SceneGraph g = PbPipelineUtil.openNodeGraph(user, book, workflow, node, false);
		try {
			return dispatch(g, node, nodeType, sdu);
		}
		catch(Exception e) {
			/// V3 P0-8: a node that threw mid-execution (unreachable Swarm, empty image, a 4xx/5xx from
			/// the per-type executor) used to keep whatever status it had — PENDING/STALE — with no
			/// lastError, so the canvas showed a node that looked never-run after a run that failed.
			/// Stamp FAILED + lastError + lastRunAt, then rethrow unchanged: the caller still gets the
			/// real exception (and its HTTP status, for a PictureBookException).
			PbPipelineUtil.failNode(g, node, errorMessage(e));
			throw e;
		}
	}

	/**
	 * Execute {@code node} as its own {@code olio.pb.run} (V3 §20/§21): open a run requesting exactly this
	 * node, stamp the run RUNNING ({@link PbGraphUtil#markRunning}) and the node RUNNING before dispatch,
	 * then close the run COMPLETED on success or FAILED (with the node FAILED + {@code lastError}) when
	 * the per-type executor throws. The exception is rethrown unchanged after the run is closed.
	 * <p>
	 * With one requested node, a node failure <i>is</i> the run failure — there is no partial outcome to
	 * report as COMPLETED-with-failures. CANCELLED is not written here; that arrives with the async job
	 * layer (P1-6). A JVM that dies between {@code markRunning} and {@code closeRun} leaves the run
	 * RUNNING and the node RUNNING, which is precisely what the restart sweep (P1-7) looks for.
	 * <p>
	 * Same gate order as {@link #executeNode(BaseRecord, BaseRecord, BaseRecord, BaseRecord, String, String)}:
	 * a refused request opens no run. Once the run is open, a RUNNING stamp (run or node) that cannot be
	 * written aborts with 500 before any SD work - the run is closed FAILED with that reason rather than
	 * left PENDING behind a render nobody authorized. Authorization itself is the facade's {@code canUpdate}
	 * gate on the book; this is the backstop for a write that fails after it.
	 *
	 * @return the artifact DTO of {@code executeNode}, plus {@code runObjectId}, {@code runStatus},
	 *         {@code executedNodeCount}, {@code failedNodeCount} and {@code durationMs} (informational —
	 *         never a threshold)
	 */
	public static Map<String, Object> executeNodeRun(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String sdApiType, String sdServer) {
		return executeNodeRun(user, book, workflow, node, toSdUtil(sdApiType, sdServer));
	}

	/** Body of {@link #executeNodeRun(BaseRecord, BaseRecord, BaseRecord, BaseRecord, String, String)}; see {@link #executeNode(BaseRecord, BaseRecord, BaseRecord, BaseRecord, SDUtil)}. */
	static Map<String, Object> executeNodeRun(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, SDUtil sdu) {
		return executeNodeRun(user, book, workflow, node, sdu, PbRenderPlanUtil.PlanOptions.NONE, null);
	}

	/**
	 * {@link #executeNodeRun(BaseRecord, BaseRecord, BaseRecord, BaseRecord, SDUtil)} with the scene plan built
	 * under {@code opts} and a cancel token (V3 P4-4, the compose job's Quick-start DRAFT render). A cancel
	 * noticed after the renderer answered discards the image, returns the node to the status its handle
	 * computes, closes the run CANCELLED and rethrows {@link PbStageCancelledException}: the caller decides
	 * whether that ends its own loop. Without a token this is exactly the five-argument form.
	 */
	static Map<String, Object> executeNodeRun(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, SDUtil sdu, PbRenderPlanUtil.PlanOptions opts, SummarizeProgress cancelToken) {
		return executeNodeRun(user, book, workflow, node, sdu, opts, cancelToken, RenderBackend.SWARM);
	}

	/**
	 * {@link #executeNodeRun(BaseRecord, BaseRecord, BaseRecord, BaseRecord, SDUtil, PbRenderPlanUtil.PlanOptions, SummarizeProgress)}
	 * against {@code backend} (P6-2: the offline tests' seam into a real run - RUNNING stamps, the variant
	 * batch, auto-approve - with no Swarm server). Production always passes {@link RenderBackend#SWARM}.
	 */
	static Map<String, Object> executeNodeRun(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, SDUtil sdu, PbRenderPlanUtil.PlanOptions opts, SummarizeProgress cancelToken,
			RenderBackend backend) {
		PbNodeTypeEnumType nodeType = gate(node, sdu);
		long started = System.currentTimeMillis();
		SceneGraph g = PbPipelineUtil.openNodeGraph(user, book, workflow, node, true);
		g.setPlanOptions(opts);
		g.setCancelToken(cancelToken);
		g.setRenderBackend(backend);
		Map<String, Object> out = null;
		try {
			/// Both stamps are writes the caller must be entitled to. A false here is not something to log
			/// and render past: a run that never reads RUNNING is invisible to the restart sweep, and a
			/// caller who cannot write the node must not be able to spend GPU time on it either. The
			/// facade's canUpdate gate makes a denial here unlikely; whatever the cause, abort before any
			/// SD work and let the catch below close the run FAILED with the reason.
			if(!PbGraphUtil.markRunning(user, g.getRun())) {
				throw new PictureBookException(500, "Run " + (g.getRun() != null ? g.getRun().get(FieldNames.FIELD_NAME) : "(none)")
					+ " for node " + node.get(FieldNames.FIELD_NAME) + " could not be stamped RUNNING; nothing was rendered");
			}
			if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.RUNNING)) {
				throw new PictureBookException(500, "Node " + node.get(FieldNames.FIELD_NAME)
					+ " could not be stamped RUNNING; nothing was rendered");
			}
			out = dispatch(g, node, nodeType, sdu);
			PbPipelineUtil.closeRun(g, PbRunStatusEnumType.COMPLETED, null);
		}
		catch(PbStageCancelledException e) {
			/// Not a failure: nothing was persisted. Return the node to what its handle computes (the same
			/// revert the async render job does) and close the run CANCELLED, never FAILED.
			PbNodeStatusEnumType back = PbGraphUtil.recomputeStatus(user, node, book);
			if(back == null || back == PbNodeStatusEnumType.UNKNOWN) {
				back = PbNodeStatusEnumType.READY;
			}
			if(!PbGraphUtil.persistStatus(user, node, back)) {
				logger.error("Cancelled node " + node.get(FieldNames.FIELD_NAME) + " could not be returned to " + back
					+ "; it reads RUNNING until restart recovery");
			}
			PbPipelineUtil.closeRun(g, PbRunStatusEnumType.CANCELLED, e.getMessage());
			throw e;
		}
		catch(Exception e) {
			String msg = errorMessage(e);
			PbPipelineUtil.failNode(g, node, msg);
			PbPipelineUtil.closeRun(g, PbRunStatusEnumType.FAILED, msg);
			throw e;
		}
		finally {
			if(!g.isRunClosed()) {
				/// Only an Error (OOM, a linkage failure) gets here: the catch above handles every Exception.
				PbPipelineUtil.closeRun(g, PbRunStatusEnumType.FAILED, "Execution abandoned before the run could be closed");
			}
		}
		/// V3 §10 rule 5 fires "when an attempt finishes", and §23 defines an attempt as a run - so the hook
		/// sits here, after the run is closed COMPLETED and outside the try: nothing the auto-approver does can
		/// turn a completed run FAILED, and the no-run "Test" path (executeNode) never auto-approves.
		applyAutoApprove(g, out);
		BaseRecord run = g.getRun();
		if(run != null) {
			out.put("runObjectId", run.get(FieldNames.FIELD_OBJECT_ID));
			out.put("runStatus", run.get(OlioFieldNames.FIELD_PB_RUN_STATUS));
		}
		out.put("executedNodeCount", Integer.valueOf(g.getExecuted()));
		out.put("failedNodeCount", Integer.valueOf(g.getFailed()));
		out.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
		return out;
	}

	/**
	 * Execute one node as a <b>stage</b> of a run somebody else owns (V3 §21, the async render job): gate,
	 * dispatch, and on a failure stamp the node FAILED + {@code lastError} and rethrow — exactly
	 * {@link #executeNode(BaseRecord, BaseRecord, BaseRecord, BaseRecord, SDUtil)}'s contract, but on a
	 * graph the caller opened with
	 * {@link PbPipelineUtil#openNodeGraph(BaseRecord, BaseRecord, BaseRecord, BaseRecord, BaseRecord, org.cote.accountmanager.olio.llm.SummarizeProgress)}
	 * so the stage sees the run and the job's cancel token. The caller stamps RUNNING, opens and closes the
	 * run, and applies auto-approve; this method does none of that.
	 * <p>
	 * A {@link PbStageCancelledException} is <b>not</b> a failure: the stage noticed the cancel after its
	 * backend call returned, discarded the bytes, and persisted nothing. It is rethrown without touching the
	 * node, which the caller then returns to the status {@link PbGraphUtil#recomputeStatus} computes.
	 */
	static Map<String, Object> executeStage(SceneGraph g, BaseRecord node, SDUtil sdu) {
		PbNodeTypeEnumType nodeType = gate(node, sdu);
		try {
			return dispatch(g, node, nodeType, sdu);
		}
		catch(PbStageCancelledException e) {
			throw e;
		}
		catch(RuntimeException e) {
			PbPipelineUtil.failNode(g, node, errorMessage(e));
			throw e;
		}
	}

	/**
	 * Thrown by an SD-backed executor when the owning job's cancel flag was raised while the backend call
	 * was in flight. The call itself was not interrupted — Phase 1 has no Swarm interrupt (design §21,
	 * 2026-10-11) — but its bytes were discarded and no artifact or status was written.
	 */
	static final class PbStageCancelledException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		PbStageCancelledException(String message) {
			super(message);
		}
	}

	/**
	 * After a backend call returns: if the job was cancelled meanwhile, delete the {@code data.data} rows
	 * the backend left behind (best-effort, as the user, through PBAC — a row that cannot be deleted is
	 * logged and left) and abandon the stage. Every SD-backed executor calls this between the backend
	 * returning and the artifact being persisted, which is the one point a late cancel can still discard
	 * rather than record.
	 */
	private static void discardIfCancelled(SceneGraph g, List<BaseRecord> images, String what) {
		if(g == null || !g.isCancelRequested()) {
			return;
		}
		int discarded = 0;
		if(images != null) {
			for(BaseRecord img : images) {
				if(img == null) {
					continue;
				}
				try {
					if(IOSystem.getActiveContext().getAccessPoint().delete(g.getUser(), img)) {
						discarded++;
					}
					else {
						logger.warn("Cancelled " + what + ": image " + img.get(FieldNames.FIELD_OBJECT_ID) + " could not be deleted and was left in place");
					}
				}
				catch(RuntimeException e) {
					logger.warn("Cancelled " + what + ": deleting image " + img.get(FieldNames.FIELD_OBJECT_ID) + " threw " + errorMessage(e));
				}
			}
		}
		throw new PbStageCancelledException("Cancelled " + what + " after the backend call returned; "
			+ discarded + " image(s) discarded, nothing recorded");
	}

	/** The DTO keys {@link #applyAutoApprove} writes. */
	public static final String KEY_AUTO_APPROVED = "autoApproved";
	public static final String KEY_AUTO_APPROVE_SKIPPED = "autoApproveSkipped";
	public static final String SKIP_OFF = "off";
	/** Fixed text for an auto-approve failure; the exception detail goes to the log, never to the response. */
	public static final String SKIP_FAILED_INTERNAL = "failed: internal error";

	/**
	 * Auto-approve (§10 rule 5) the attempt's new artifact when the book's {@code autoApprove} switch is on,
	 * via {@link PbArtifactUtil#autoApprove}, and report the outcome on the DTO: {@code autoApproved}
	 * (boolean, always) and {@code autoApproveSkipped} (the reason, when not approved — {@code "off"} when
	 * the switch is off). When approved, {@code reviewStatus} is set to APPROVED so a caller need not re-read
	 * the artifact to learn it. Never throws: the attempt is already complete and recorded.
	 * <p>
	 * The book is the one the facade loaded with {@code bookRequest()} (which projects {@code autoApprove});
	 * a book handed in without the field reads as off. The artifact is re-read with
	 * {@link PbArtifactUtil#artifactRequest()} because the executors' persisted records are not all projected
	 * with {@code producedByNode}/{@code role}, which the chain walk needs.
	 */
	static void applyAutoApprove(SceneGraph g, Map<String, Object> out) {
		BaseRecord book = g.getBook();
		Boolean on = (book != null && book.hasField(OlioFieldNames.FIELD_PB_AUTO_APPROVE)
			? book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE) : null);
		if(on == null || !on.booleanValue()) {
			out.put(KEY_AUTO_APPROVED, Boolean.FALSE);
			out.put(KEY_AUTO_APPROVE_SKIPPED, SKIP_OFF);
			return;
		}
		String skipped;
		try {
			BaseRecord user = g.getUser();
			long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
			String artifactOid = (String) out.get("artifactObjectId");
			BaseRecord artifact = PbArtifactUtil.readArtifact(user, artifactOid, orgId);
			skipped = (artifact != null
				? PbArtifactUtil.autoApprove(user, artifact, degradationsOf(out))
				: "failed: artifact " + artifactOid + " could not be re-read");
		}
		catch(RuntimeException e) {
			logger.error("Auto-approve threw after a completed run: " + errorMessage(e), e);
			skipped = SKIP_FAILED_INTERNAL;
		}
		out.put(KEY_AUTO_APPROVED, Boolean.valueOf(skipped == null));
		if(skipped != null) {
			out.put(KEY_AUTO_APPROVE_SKIPPED, skipped);
		}
		else {
			out.put("reviewStatus", PbReviewStatusEnumType.APPROVED.name());
		}
	}

	/**
	 * The pre-IO gates shared by both entry points, in order: 400 null node, 501 non-executable type,
	 * 500 no SD backend for an SD-backed type. Returns the (non-null) node type on success.
	 */
	private static PbNodeTypeEnumType gate(BaseRecord node, SDUtil sdu) {
		if(node == null) {
			throw new PictureBookException(400, "A node is required");
		}
		PbNodeTypeEnumType nodeType = node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
		if(nodeType == null) {
			nodeType = PbNodeTypeEnumType.UNKNOWN;
		}
		if(!isExecutable(nodeType)) {
			throw new PictureBookException(501,
				"Single-node execution is not implemented for node type " + nodeType
				+ "; executable types are " + String.join(", ", executableTypeNames()));
		}
		if(sdu == null && SD_BACKED_TYPES.contains(nodeType)) {
			throw new PictureBookException(500, "SD server not configured");
		}
		return nodeType;
	}

	private static Map<String, Object> dispatch(SceneGraph g, BaseRecord node, PbNodeTypeEnumType nodeType, SDUtil sdu) {
		switch(nodeType) {
			case PORTRAIT:
				return executePortrait(g, node, sdu);
			case LANDSCAPE:
				return (isChapBook(g.getBook()) ? executeChapBookPage(g, node, sdu) : executeLandscape(g, node, sdu));
			case SCENE_PROMPT:
			case LANDSCAPE_PROMPT:
				return executePromptNode(g, node, nodeType);
			case REFERENCE_STRIP:
				warnLegacyNode(g, node, nodeType);
				return executeReferenceStrip(g, node);
			case COMPOSITE:
				warnLegacyNode(g, node, nodeType);
				return executeComposite(g, node, sdu);
			case RENDER_PLAN:
				return executeRenderPlan(g, node, sdu);
			case LOCATION:
				return executeLocation(g, node, sdu);
			default:
				throw new PictureBookException(501,
					"Single-node execution is not yet implemented for node type " + nodeType);
		}
	}

	/// One line per execution of a PB2-era node (§28.1 Stage 1 Coexist, P5-3): the type is deprecated in
	/// favour of RENDER_PLAN and goes with PB1 at Stage 4 (Phase 10). Only persisted legacy nodes reach
	/// here - PB1's own path never enters dispatch - so each line is one real re-execution, worth keeping.
	private static void warnLegacyNode(SceneGraph g, BaseRecord node, PbNodeTypeEnumType nodeType) {
		BaseRecord book = g.getBook();
		logger.warn("Legacy PB2 node type " + nodeType + " executed: node " + node.get(FieldNames.FIELD_OBJECT_ID)
			+ " (" + node.get(OlioFieldNames.FIELD_PB_HANDLE) + ") in book " + (book != null ? book.get(FieldNames.FIELD_OBJECT_ID) : "?")
			+ " - replaced by RENDER_PLAN; removed with PB1 at design §28.1 Stage 4 (Phase 10)");
	}

	/// The message stamped on the node and the run; a BASE stage failure also carries the renderer's detail
	/// (the Swarm node and its reason) so it is not lost with the exception that only reaches the caller.
	private static String errorMessage(Exception e) {
		String msg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.getClass().getSimpleName();
		if(e instanceof PbStageFailedException) {
			for(Map<String, Object> se : ((PbStageFailedException) e).getStageErrors()) {
				Object detail = se.get("detail");
				if(detail != null) {
					msg = msg + " [" + se.get("stage") + ": " + detail + "]";
				}
			}
		}
		return msg;
	}

	// ─── style + model policy (P2-3) ─────────────────────────────────────────────

	/**
	 * The book's effective style chain, resolved once per node run as the acting user and shared by the
	 * effective config and the model policy below. A chain this user cannot finish reading - or one that
	 * cycles - is refused as a 409 rather than rendered: a partial chain yields a partial config and an
	 * inputHash no complete reader would recognise (⚠ §6.C 157).
	 */
	private static PbStyleResolver.EffectiveStyle styleFor(SceneGraph g, BaseRecord node) {
		PbStyleResolver.EffectiveStyle style = PbStyleResolver.resolve(g.getUser(), g.getBook());
		if(!style.isComplete()) {
			throw new PictureBookException(409, "Style chain of book " + g.getBook().get(OlioFieldNames.FIELD_PB_SLUG)
				+ (style.cycleDetected() ? " cycles" : " is not readable at " + style.truncatedModel() + " " + style.truncatedAt())
				+ "; node " + node.get(OlioFieldNames.FIELD_PB_HANDLE) + " cannot render against a partial style");
		}
		return style;
	}

	/**
	 * Apply the one model policy for {@code task} to the node's effective config: the node's own sparse
	 * {@code configOverride} checkpoint is the override, the style chain supplies the task pin and the
	 * style-tier default, and the deployment default closes. The pick is written into
	 * {@code effectiveConfig}; the returned Resolution is what a pre-render surface reports (P2-5).
	 */
	private static void applyModelPolicy(SceneGraph g, BaseRecord node, boolean composite, PbStyleResolver.EffectiveStyle style,
			PbModelTaskEnumType task, BaseRecord effectiveConfig, SDUtil sdu, boolean referencesRequested) {
		PbModelPolicy.applyForStyle(style, PbConfigUtil.styleTierConfig(g.getBook(), composite, style), task, effectiveConfig,
			PbModelPolicy.nodeOverride(node, task), sdu.getApiType(), sdu.getServer(), referencesRequested);
	}

	// ─── landscape ───────────────────────────────────────────────────────────────

	private static Map<String, Object> executeLandscape(SceneGraph g, BaseRecord node, SDUtil sdu) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Landscape node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_MOOD
		});
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}

		List<String> degradations = new ArrayList<>();
		/// P4-10: the SCENE composition is the authority; its promptOverride is read first, the scene's
		/// mirrored sdPrompt second, the generic fallback last.
		String sdPrompt = compositionPrompt(findComposition(user, book, scene, orgId, PbCompositionPurposeEnumType.SCENE));
		if(sdPrompt == null) {
			sdPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		}
		if(sdPrompt == null || sdPrompt.trim().isEmpty()) {
			String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
			sdPrompt = "landscape, scenic, painterly, soft light" + (mood != null ? ", " + mood + " atmosphere" : "");
			logger.warn("executeLandscape: scene {} has no composition promptOverride or sdPrompt — using generic fallback", scopeRef);
			degradations.add(PbPipelineUtil.ROLE_LANDSCAPE + ": scene has no sdPrompt; generic fallback prompt used");
		}

		PbStyleResolver.EffectiveStyle style = styleFor(g, node);
		BaseRecord effectiveConfig = PbConfigUtil.resolveEffectiveConfig(book, node, false, null, style);
		applyModelPolicy(g, node, false, style, PbModelTaskEnumType.LOCATION, effectiveConfig, sdu, false);
		try {
			effectiveConfig.set("description", sdPrompt);
			effectiveConfig.set("hires", false);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set description on effective landscape config: " + e.getMessage());
		}

		String artifactPath = g.getArtifactGroupPath();
		String landscapeName = "landscape_" + System.currentTimeMillis();

		long callStarted = System.currentTimeMillis();
		List<BaseRecord> images = sdu.createImage(user, artifactPath, effectiveConfig, landscapeName, 1, SDUtil.hiresOf(effectiveConfig), -1);
		long callMs = System.currentTimeMillis() - callStarted;
		discardIfCancelled(g, images, "landscape of scene " + scopeRef);
		if(images == null || images.isEmpty()) {
			throw new PictureBookException(500, "SD backend returned no images for landscape of scene " + scopeRef);
		}
		BaseRecord image = images.get(0);
		byte[] bytes;
		try {
			bytes = ByteModelUtil.getValue(image);
		}
		catch(FieldException | ValueException e) {
			throw new PictureBookException(500, "Failed to read landscape bytes for scene " + scopeRef + ": " + e.getMessage());
		}
		if(bytes == null || bytes.length == 0) {
			throw new PictureBookException(500, "Landscape image for scene " + scopeRef + " decoded to empty bytes");
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_LANDSCAPE,
			PbArtifactTypeEnumType.IMAGE, artifactPath, image, null, effectiveConfig, null);
		int[] wh = PbPipelineUtil.decodeDimensions(bytes);
		PbArtifactUtil.recordImageMetrics(user, artifact, bytes, "image/png", wh[0], wh[1], null, sdu.getApiType(), callMs);

		/// completeNode (not a bare persistStatus) so the node gets its inputHash/lastRunAt and counts on the
		/// run: a node with inputHash == null is never current to the planner, so an un-forced re-render of a
		/// finished landscape was accepted, and the drift scan never marks it.
		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		return artifactDto(node, "DONE_UNVERIFIED", artifact, Long.valueOf(bytes.length), downstream, degradations);
	}

	// ─── render plan (RENDER_PLAN node; V3 §12, §16, §20, P2-5) ──────────────────

	/**
	 * Render a scene from its {@link RenderPlan} (V3 §12): build the plan for this node, persist it as the
	 * node's {@code renderPlan} artifact BEFORE anything is checked (§12 rule 1 - the plan is the record of
	 * what was decided, including a refusal), refuse with the resolver's reason when the BASE stage has no
	 * resolved model, then send exactly the BASE stage through the renderer adapter and record the image as
	 * the node's {@code base} artifact.
	 * <p>
	 * An UPSCALE stage the preset planned is reported as a degradation, never silently dropped. V3 §23
	 * (P6-2): inside a run the preset's {@code variants} finals are rendered for the one attempt (the run),
	 * each recorded as a {@code base} artifact stamped with the attempt and its variant index; only variant 0
	 * takes the selection, and {@link #KEY_VARIANTS} lists them all. Test mode renders one.
	 */
	private static Map<String, Object> executeRenderPlan(SceneGraph g, BaseRecord node, SDUtil sdu) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		String handle = node.get(OlioFieldNames.FIELD_PB_HANDLE);
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Render-plan node " + handle + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_PB_SCENE_INDEX, OlioFieldNames.FIELD_PB_TITLE, "summary",
			OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD,
			OlioFieldNames.FIELD_CB_SD_PROMPT, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT
		});
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		String sceneLabel = "scene " + (sceneIndex != null ? sceneIndex.toString() : scopeRef);

		/// V3 §23 (P6-2): the attempt's finals are all written against the selection that stood before the
		/// attempt, captured here before anything renders, so the second final never supersedes the first
		/// and only variant 0 takes the selection (PbPipelineUtil.recordImage, batch overload).
		BaseRecord preBatch = PbArtifactUtil.findSelected(user, node, PbPipelineUtil.ROLE_BASE);
		String attempt = attemptOf(g);

		PlannedRender r = renderPlannedScene(g, node, scene, sceneLabel, sdu,
			"render_plan_" + (sceneIndex != null ? sceneIndex : "x"),
			"Scene " + (sceneIndex != null ? sceneIndex : scopeRef) + " has no text to render: set its setting/action or sdPrompt", true);
		List<BaseRecord> artifacts = new ArrayList<>();
		for(PlannedRender.Variant v : r.variants) {
			BaseRecord a;
			if(r.variants.size() > 1) {
				a = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_BASE, PbArtifactTypeEnumType.IMAGE,
					v.data, v.bytes, "image/png", v.seed, r.frozen, v.requestJson, v.referenceArtifactObjectIds, sdu.getApiType(), v.durationMs,
					v.backendGraph, preBatch, v.variantIndex, attempt);
			}
			else {
				a = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_BASE, PbArtifactTypeEnumType.IMAGE,
					v.data, v.bytes, "image/png", v.seed, r.frozen, v.requestJson, v.referenceArtifactObjectIds, sdu.getApiType(), v.durationMs,
					v.backendGraph, attempt);
			}
			artifacts.add(a);
		}
		BaseRecord artifact = artifacts.get(0);
		bindSceneLocation(g, node, r.plan, r.degradations);

		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = artifactDto(node, "DONE_UNVERIFIED", artifact, Long.valueOf(r.bytes.length), downstream, r.degradations);
		r.describe(out);
		out.put(KEY_VARIANTS, variantsDto(artifacts, r.variants));
		return out;
	}

	/// V3 §23: the attempt an artifact recorded on this graph belongs to - the run's objectId, or null in Test
	/// mode (no run), where the artifact is stamped with no attempt.
	static String attemptOf(SceneGraph g) {
		return (g != null && g.getRun() != null ? g.getRun().get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	/// {@link #KEY_VARIANTS}: one entry per recorded final, in variant order.
	private static List<Map<String, Object>> variantsDto(List<BaseRecord> artifacts, List<PlannedRender.Variant> variants) {
		List<Map<String, Object>> list = new ArrayList<>();
		for(int i = 0; i < artifacts.size() && i < variants.size(); i++) {
			BaseRecord a = artifacts.get(i);
			PlannedRender.Variant v = variants.get(i);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("artifactObjectId", (a != null ? a.get(FieldNames.FIELD_OBJECT_ID) : null));
			m.put("variantIndex", Integer.valueOf(v.variantIndex));
			m.put("seed", v.seed);
			m.put("byteLength", Long.valueOf(v.bytes != null ? v.bytes.length : 0L));
			m.put("artifactRevision", (a != null ? a.get(OlioFieldNames.FIELD_PB_REVISION) : null));
			Boolean sel = (a != null ? a.get(OlioFieldNames.FIELD_PB_SELECTED) : null);
			m.put("selected", Boolean.valueOf(sel != null && sel.booleanValue()));
			list.add(m);
		}
		return list;
	}

	/**
	 * Bind the location (ordinal 0) and variant (ordinal 1) the plan accepted to the scene's RENDER_PLAN node
	 * - and, for a ChapBook page, its LANDSCAPE page node (P3-6) - under role {@code location}, after the
	 * render, so that the node's watched set carries the location's {@code approvedRender} and a later approve
	 * (or an edit of the location) stales this scene across every chapter that shares the world asset (§7,
	 * §10). The verdict is the plan's, not the scene's raw pointer: a location the resolver refused or could
	 * not read has no {@code revisionHash}, a refused or unreadable variant has a null
	 * {@code inputs.locationVariant} ({@link PbRenderPlanUtil#resolveSceneLocation}), and neither is bound -
	 * the watched set carries only what contributed to the render, so another world's edits or approvals
	 * cannot stale this scene. A scene re-pointed at another location, or at none, between two renders drops
	 * the stale binding ({@link PbPipelineUtil#syncRecordBinding}); so does a pointer that became refused.
	 * <p>
	 * The image is already recorded when this runs, so a binding failure is a degradation on the result, not
	 * a failed render (P4-8): the scene keeps its image and the message says the watch was not updated.
	 */
	private static void bindSceneLocation(SceneGraph g, BaseRecord node, RenderPlan plan, List<String> degradations) {
		RenderPlan.Location pl = (plan != null ? plan.getLocation() : null);
		String locOid = (pl != null && pl.getRevisionHash() != null ? pl.getLocationId() : null);
		String varOid = (locOid != null && pl.getVariantId() != null
			&& plan.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT) != null ? pl.getVariantId() : null);
		try {
			PbPipelineUtil.syncRecordBinding(g, node, PbPipelineUtil.ROLE_LOCATION, 0, OlioModelNames.MODEL_PB_LOCATION, locOid);
			PbPipelineUtil.syncRecordBinding(g, node, PbPipelineUtil.ROLE_LOCATION, 1, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid);
		}
		catch(RuntimeException e) {
			logger.warn("bindSceneLocation: node {} location binding could not be updated: {}", node.get(OlioFieldNames.FIELD_PB_HANDLE), e.getMessage());
			degradations.add(PbPipelineUtil.ROLE_LOCATION + ": location binding could not be updated; a later approve or edit of the location may not stale this scene");
		}
	}

	// ─── location (LOCATION node; V3 §7, §7.1, §10, P3-4) ──────────────────

	/**
	 * Render one {@code olio.pb.location} (scope {@code location}) or one of its variants (scope
	 * {@code locationVariant}) from a location plan ({@link PbRenderPlanUtil#buildLocationPlan}): the same
	 * plan path as RENDER_PLAN - plan persisted first, BASE stage only, TEXT_ONLY - recorded as the node's
	 * {@code landscape} artifact and then bound to the location (ordinal 0) and variant (ordinal 1) under
	 * role {@code location}, mirroring {@link #executePortrait}. The artifact is a world-asset candidate:
	 * approving it promotes it to the record's {@code approvedRender} ({@code PbArtifactUtil.approve}), and
	 * the book's {@code autoApprove} applies through {@link #applyAutoApprove} like every other node.
	 * <p>
	 * The location is re-read as the acting user and refused with 404 unless its world is this book's world
	 * (§6.C 170 m i): the node's {@code scopeRef} was written by a book-scoped route, but the executor does not
	 * trust it across worlds. A locked location is refused with 409 - the render job records that as this
	 * node's failure and carries on with the rest of the plan. A location whose {@code approvedRender} is set
	 * still renders - a new candidate never replaces an approval, the reviewer does (§10 rule 5).
	 */
	private static Map<String, Object> executeLocation(SceneGraph g, BaseRecord node, SDUtil sdu) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		PbLocationUtil.NodeSubject subject = PbLocationUtil.subjectOf(user, node);
		if(subject == null) {
			throw new PictureBookException(400, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE) + " is not a LOCATION node");
		}
		if(!PbLocationUtil.sameWorld(book, subject.location)) {
			throw new PictureBookException(404, "Location not found in this book's world");
		}
		PbLocationUtil.requireUnlocked(subject.location, "render it");
		BaseRecord location = subject.location;
		BaseRecord variant = subject.variant;
		String locationOid = location.get(FieldNames.FIELD_OBJECT_ID);
		String label = subject.label();

		RenderPlan plan = PbRenderPlanUtil.buildLocationPlan(user, book, location, variant, node,
			PbRenderPlanUtil.PlanOptions.NONE, sdu.getApiType(), sdu.getServer());
		PlannedRender r = renderPlanned(g, node, plan, label, sdu,
			"location_" + PbPipelineUtil.shortRef(variant != null ? variant.get(FieldNames.FIELD_OBJECT_ID) : locationOid),
			"Location " + location.get(FieldNames.FIELD_NAME) + " has no description to render from");
		BaseRecord artifact = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE,
			r.data, r.bytes, "image/png", r.seed, r.frozen, r.requestJson, r.referenceArtifactObjectIds, sdu.getApiType(), r.durationMs,
			r.backendGraph, attemptOf(g));

		/// Bind the subject AFTER the render, as executePortrait does: the binding's refHash is the location's
		/// watched fields (approvedRender included), so approving a later candidate stales this node too.
		PbPipelineUtil.bindRecord(g, node, PbPipelineUtil.ROLE_LOCATION, 0, OlioModelNames.MODEL_PB_LOCATION, locationOid);
		if(variant != null) {
			PbPipelineUtil.bindRecord(g, node, PbPipelineUtil.ROLE_LOCATION, 1, OlioModelNames.MODEL_PB_LOCATION_VARIANT,
				variant.get(FieldNames.FIELD_OBJECT_ID));
		}

		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = artifactDto(node, "DONE_UNVERIFIED", artifact, Long.valueOf(r.bytes.length), downstream, r.degradations);
		r.describe(out);
		out.put("locationObjectId", locationOid);
		if(variant != null) {
			out.put("variantObjectId", variant.get(FieldNames.FIELD_OBJECT_ID));
		}
		return out;
	}

	/**
	 * What one plan-driven render produced, before the caller records it under its node's role: the final
	 * image of the stage sequence (P5-2 §2.4 - the BASE render, or the last good ACTOR_REGION pass) with the
	 * references and raw-graph provenance that image carries, plus what every stage did.
	 */
	static final class PlannedRender {
		final RenderPlan plan;
		final String planOid;
		final CompiledPrompt compiled;
		/// Variant 0's final image and provenance (the fields every single-variant caller reads).
		final byte[] bytes;
		final BaseRecord data;
		final BaseRecord frozen;
		final Long seed;
		final String requestJson;
		final long durationMs;
		final List<String> degradations;
		final List<String> configNotApplied;
		/// Artifact objectIds the final image's request referenced (its portraits and location render, or for
		/// a pass its own portrait and the stage artifact it painted over); empty on the plain T2I path.
		final List<String> referenceArtifactObjectIds;
		/// The final image's {@code backendGraph} envelope; null on the plain T2I path.
		final String backendGraph;
		final List<String> stagesRendered;
		/// Every variant's stage errors, in variant order; entries of variant 1+ carry a {@code variant} key.
		final List<Map<String, Object>> stageErrors;
		final boolean calibrationPersisted;
		/// V3 §23 (P6-2): every final the attempt produced, variant 0 first. {@code variants.get(0)} is what
		/// the scalar fields above describe; a single-variant render has exactly one entry.
		final List<Variant> variants;

		private PlannedRender(RenderPlan plan, String planOid, CompiledPrompt compiled, BaseRecord frozen,
				List<String> degradations, List<String> configNotApplied, List<Map<String, Object>> stageErrors,
				boolean calibrationPersisted, List<Variant> variants) {
			Variant v0 = variants.get(0);
			this.plan = plan;
			this.planOid = planOid;
			this.compiled = compiled;
			this.bytes = v0.bytes;
			this.data = v0.data;
			this.frozen = frozen;
			this.seed = v0.seed;
			this.requestJson = v0.requestJson;
			this.durationMs = v0.durationMs;
			this.degradations = degradations;
			this.configNotApplied = configNotApplied;
			this.referenceArtifactObjectIds = v0.referenceArtifactObjectIds;
			this.backendGraph = v0.backendGraph;
			this.stagesRendered = v0.stagesRendered;
			this.stageErrors = stageErrors;
			this.calibrationPersisted = calibrationPersisted;
			this.variants = variants;
		}

		/// One final of the attempt (V3 §23): the last stage's image of its own BASE → ACTOR_REGION sequence,
		/// with that image's provenance. {@code variantIndex} is its position in the attempt (0 = the one that
		/// takes the selection).
		static final class Variant {
			final int variantIndex;
			final byte[] bytes;
			final BaseRecord data;
			final Long seed;
			final String requestJson;
			final long durationMs;
			final List<String> referenceArtifactObjectIds;
			final String backendGraph;
			final List<String> stagesRendered;

			private Variant(int variantIndex, byte[] bytes, BaseRecord data, Long seed, String requestJson, long durationMs,
					List<String> referenceArtifactObjectIds, String backendGraph, List<String> stagesRendered) {
				this.variantIndex = variantIndex;
				this.bytes = bytes;
				this.data = data;
				this.seed = seed;
				this.requestJson = requestJson;
				this.durationMs = durationMs;
				this.referenceArtifactObjectIds = referenceArtifactObjectIds;
				this.backendGraph = backendGraph;
				this.stagesRendered = stagesRendered;
			}
		}

		/// The plan keys every plan-driven node reports (§12): which plan, its hash, the strategy and stages,
		/// the stages actually rendered ({@code BASE}, {@code ACTOR_REGION#<actorId>}, ...) and their errors
		/// ({@link #KEY_STAGE_ERRORS}), whether the calibration samples were persisted, the one-line actor
		/// summary (P4-7) and the effective-config values a plan never carries ({@link #KEY_CONFIG_NOT_APPLIED}).
		void describe(Map<String, Object> out) {
			String strategy = plan.getStrategy().getName().name();
			out.put("planArtifactObjectId", planOid);
			out.put("planContentHash", plan.contentHash());
			out.put("strategy", strategy);
			out.put("stagesPlanned", plan.getStrategy().getStages());
			out.put("stagesRendered", stagesRendered);
			out.put(KEY_STAGE_ERRORS, stageErrors);
			out.put(KEY_CALIBRATION_PERSISTED, Boolean.valueOf(calibrationPersisted));
			out.put("planSummary", plan.actorSummary() + "; strategy " + strategy);
			out.put(KEY_CONFIG_NOT_APPLIED, configNotApplied);
			out.put(KEY_VARIANTS_PLANNED, Integer.valueOf(plan.getPreset() != null ? Math.max(1, plan.getPreset().getVariants()) : 1));
			out.put(KEY_VARIANTS_RENDERED, Integer.valueOf(variants.size()));
		}
	}

	/// DTO key (V3 §23, P6-2): how many finals the preset planned for this attempt.
	public static final String KEY_VARIANTS_PLANNED = "variantsPlanned";
	/// DTO key (V3 §23, P6-2): how many finals this attempt actually produced; less than planned when a
	/// later variant's BASE failed (reported as a stage error and a degradation, the attempt kept).
	public static final String KEY_VARIANTS_RENDERED = "variantsRendered";
	/// DTO key (V3 §23, P6-2): the attempt's finals in variant order, each
	/// {@code {artifactObjectId, variantIndex, seed, byteLength, artifactRevision, selected}}.
	public static final String KEY_VARIANTS = "variants";

	/// DTO key: one entry per stage that failed (P5-2 §6), each {@code {stage, code, message, detail}};
	/// {@code detail} is the adapter's {@link StageResult#getErrorDetail()} when it carried one.
	public static final String KEY_STAGE_ERRORS = "stageErrors";
	/// DTO key: true when every graph stage's calibration sample was persisted as the user (P5-2 §4); false
	/// on the plain T2I path, which calibrates in-process only.
	public static final String KEY_CALIBRATION_PERSISTED = "calibrationPersisted";

	/**
	 * A BASE stage that failed (P5-2 §6.3): the node fails with {@code Render of <label> failed: <code>: ...}
	 * and the stage error that produced it travels with the exception so a caller can classify it by
	 * {@code code} rather than by parsing the message.
	 */
	static final class PbStageFailedException extends PictureBookException {
		private static final long serialVersionUID = 1L;
		private final transient List<Map<String, Object>> stageErrors;

		PbStageFailedException(int status, String message, List<Map<String, Object>> stageErrors) {
			super(status, message);
			this.stageErrors = stageErrors;
		}

		List<Map<String, Object>> getStageErrors() {
			return stageErrors;
		}
	}

	/// One stage error entry for {@link #KEY_STAGE_ERRORS}.
	private static Map<String, Object> stageError(String stage, String code, String message, String detail) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("stage", stage);
		m.put("code", code);
		m.put("message", message);
		if(detail != null && !detail.isBlank()) {
			m.put("detail", detail);
		}
		return m;
	}

	/// {@code <code>: <text>} for a stage error string: the adapter's own code-prefixed errors stay as they
	/// are; its prose errors (unreachable, no images, cancelled, backend) gain their §6.1 code in front.
	private static String codedError(String error) {
		String e = (error != null ? error : RendererAdapter.ERROR_NO_IMAGES);
		String code = RendererAdapter.errorCode(e);
		return (e.startsWith(code + ": ") ? e : code + ": " + e);
	}

	/**
	 * The plan-driven render both the RENDER_PLAN node and the ChapBook page share (V3 §12, §24): build the
	 * plan for {@code node}, persist it as the node's {@code renderPlan} artifact BEFORE anything is checked
	 * (§12 rule 1 - the plan is the record of what was decided, including a refusal), refuse with the
	 * resolver's reason when the BASE stage has no resolved model, send exactly the BASE stage through the
	 * renderer adapter on {@code sdu}, and persist the bytes. The caller records the image under its own
	 * role and closes the node.
	 * <p>
	 * Nothing the plan does not carry reaches the renderer, and nothing is dropped silently: the
	 * {@code sd.config} tier's {@code style} clause, {@code hires} and the v1 default negative prompt are
	 * not part of a plan (the compiler takes style from {@code olio.pb.style} and the negative from the
	 * style's exclusions plus the config's {@code negativePrompt}), so each is reported under
	 * {@link #KEY_CONFIG_NOT_APPLIED} when the effective config asked for it. They are <b>not</b>
	 * degradations: a degradation is the render falling short of its own plan (§19 - a substituted model,
	 * a planned stage not rendered) and vetoes auto-approve (§10 rule 5), whereas these are v1 knobs the
	 * plan path governs by preset and style instead; every deployment-default {@code sd.config} carries
	 * {@code hires} and a {@code style}, so counting them would leave auto-approve unreachable on the plan
	 * path. A planned UPSCALE stage is a degradation: reported, not rendered (Phase 2 renders BASE only,
	 * one image regardless of the preset's variants).
	 *
	 * @param noTextMessage the 409 message when the compiled BASE prompt is blank; the plan artifact id is appended
	 */
	static PlannedRender renderPlannedScene(SceneGraph g, BaseRecord node, BaseRecord scene, String sceneLabel, SDUtil sdu,
			String imageNamePrefix, String noTextMessage) {
		return renderPlannedScene(g, node, scene, sceneLabel, sdu, imageNamePrefix, noTextMessage, false);
	}

	/**
	 * {@link #renderPlannedScene(SceneGraph, BaseRecord, BaseRecord, String, SDUtil, String, String)} that,
	 * when {@code multiVariant} is set AND the graph belongs to a run, renders as many finals as the
	 * preset's {@code variants} says (V3 §23, P6-2; {@link #renderPlanned(SceneGraph, BaseRecord, RenderPlan, String, SDUtil, String, String, int)}).
	 * Test mode (no run) and every caller that passes false render one. The RENDER_PLAN node passes true;
	 * the ChapBook page and the LOCATION node stay at one variant (§23: variants are a scene-final affair).
	 */
	static PlannedRender renderPlannedScene(SceneGraph g, BaseRecord node, BaseRecord scene, String sceneLabel, SDUtil sdu,
			String imageNamePrefix, String noTextMessage, boolean multiVariant) {
		/// V3 §9.1 (P4-4): a scene rendered before the compose job reached it is composed here, by itself,
		/// as the request user, and the plan says so. A ChapBook page has no SCENE composition (P4-8), and the
		/// preview path (PbServiceFacade.planScene) never comes through here, so a read stays a read.
		PbComposeUtil.EnsureResult ensured = null;
		if(!isChapBook(g.getBook())) {
			ensured = PbComposeUtil.ensureComposition(g.getUser(), g.getBook(), scene, g.getCancelToken());
		}
		RenderPlan plan = PbRenderPlanUtil.buildScenePlan(g.getUser(), g.getBook(), scene, node, g.getPlanOptions(),
			sdu.getApiType(), sdu.getServer());
		if(ensured != null && ensured.composedNow) {
			plan = plan.withDegradations(List.of(new RenderPlan.Degradation(RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER,
				"Composed by this render, out of story order" + (ensured.result != null && ensured.result.migratedFallback
					? " (MIGRATED: " + ensured.result.failureReason + ")" : "") + "; re-compose in order to clear",
				null, RenderPlan.STAGE_BASE)));
		}
		int variants = 1;
		if(multiVariant && g.getRun() != null && plan.getPreset() != null) {
			variants = Math.max(1, plan.getPreset().getVariants());
		}
		return renderPlanned(g, node, plan, sceneLabel, sdu, imageNamePrefix, noTextMessage, variants);
	}

	/**
	 * {@link #renderPlannedScene} for a plan the caller already built (P3-4: the LOCATION node builds its plan
	 * from the location record, not from a scene). Persists {@code plan} as the node's {@code renderPlan}
	 * artifact first, then renders exactly the BASE stage as described above - one variant.
	 */
	static PlannedRender renderPlanned(SceneGraph g, BaseRecord node, RenderPlan plan, String sceneLabel, SDUtil sdu,
			String imageNamePrefix, String noTextMessage) {
		return renderPlanned(g, node, plan, sceneLabel, sdu, imageNamePrefix, noTextMessage, 1);
	}

	/**
	 * V3 §23 (P6-2): the seed of variant {@code variant} of an attempt whose variant 0 rendered with
	 * {@code baseSeed}: {@code baseSeed + variant}, folded back into Swarm's positive int range when the sum
	 * would leave it. Variant 0 is always random; the others are reproducible from its reported seed, so an
	 * attempt's finals are distinct and the strip can say why.
	 */
	static int variantSeed(long baseSeed, int variant) {
		long s = baseSeed + variant;
		if(s <= 0 || s >= Integer.MAX_VALUE) {
			s = 1 + Math.floorMod(s, (long) (Integer.MAX_VALUE - 1));
		}
		return (int) s;
	}

	/**
	 * The renderer a planned render talks to: the adapter that executes each {@link StageRequest} and the
	 * graph client the compiler asks for generated workflows and the server version. {@link #SWARM} is the
	 * production backend and the only one outside tests; a test hands in fakes so {@link #renderStages}
	 * runs without a Swarm server (P5-3, §6.C 209 b). A parameter rather than a static so nothing is
	 * process-global.
	 */
	interface RenderBackend {
		RendererAdapter adapter(SDUtil sdu);
		SwarmGraphClient client(SDUtil sdu);

		RenderBackend SWARM = new RenderBackend() {
			@Override
			public RendererAdapter adapter(SDUtil sdu) {
				return new SwarmRendererAdapter(sdu);
			}
			@Override
			public SwarmGraphClient client(SDUtil sdu) {
				return new SwarmGraphClient(sdu);
			}
		};
	}

	/**
	 * {@link #renderPlanned(SceneGraph, BaseRecord, RenderPlan, String, SDUtil, String, String)} producing
	 * {@code variants} finals for one attempt (V3 §23, P6-2), against the graph's
	 * {@link SceneGraph#getRenderBackend() render backend}. Each variant is its own BASE → ACTOR_REGION
	 * sequence ({@link #renderStages}): variant 0 renders with a random seed exactly as before, variant
	 * {@code i} with {@link #variantSeed}(seed of variant 0, i) so the finals are distinct and reproducible.
	 * Every sequence renders before anything is persisted (a cancel during any variant discards the whole
	 * attempt, as a cancel during any stage already did); a BASE failure on variant 0 fails the node as
	 * before, a BASE failure on a later variant keeps what rendered and is reported as that variant's stage
	 * error plus a degradation (the attempt fell short of its plan, §19 - so auto-approve is vetoed). The
	 * persisted stage artifacts of every variant share the attempt and carry their variant's index.
	 */
	static PlannedRender renderPlanned(SceneGraph g, BaseRecord node, RenderPlan plan, String sceneLabel, SDUtil sdu,
			String imageNamePrefix, String noTextMessage, int variants) {
		BaseRecord user = g.getUser();
		RenderBackend backend = g.getRenderBackend();
		int variantCount = Math.max(1, variants);
		BaseRecord planArtifact = PbRenderPlanUtil.persistPlanArtifact(user, node, g.getArtifactGroupPath(), plan);
		String planOid = planArtifact.get(FieldNames.FIELD_OBJECT_ID);

		RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
		if(pick == null || !pick.isResolved()) {
			throw new PictureBookException(unresolvedModelStatus(pick), unresolvedModelMessage(sceneLabel, pick, plan, planOid));
		}
		CompiledPrompt compiled = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		if(!hasSubject(plan) || compiled == null || compiled.getPrompt() == null || compiled.getPrompt().isBlank()) {
			throw new PictureBookException(409, noTextMessage + " (plan artifact " + planOid + ")");
		}

		List<String> degradations = new ArrayList<>();
		for(RenderPlan.Degradation d : plan.getDegradations()) {
			degradations.add(d.getCode() + ": " + d.getMessage());
		}
		List<String> stagesPlanned = plan.getStrategy().getStages();
		if(stagesPlanned.contains(RenderPlan.STAGE_UPSCALE)) {
			degradations.add("upscale: UPSCALE stage planned by the preset but not rendered (Phase 2 renders BASE only)");
		}
		Map<String, Object> cfg = plan.getConfig();
		List<String> configNotApplied = new ArrayList<>();
		if(Boolean.TRUE.equals(cfg.get("hires"))) {
			configNotApplied.add(NOT_APPLIED_HIRES + ": the effective config asks for hires but the plan renders the BASE stage only; no refiner pass was sent");
		}
		Object styleClause = cfg.get("style");
		if(styleClause instanceof String && NarrativeUtil.isMeaningful((String) styleClause)) {
			configNotApplied.add(NOT_APPLIED_STYLE_CLAUSE + ": the effective config's style '" + styleClause
				+ "' is not applied on the plan path; the compiled style comes from the book's olio.pb.style chain only");
		}
		if(compiled.getNegative() == null || compiled.getNegative().isBlank()) {
			configNotApplied.add(NOT_APPLIED_DEFAULT_NEGATIVE + ": no negative prompt compiled (the style names no exclusions and the config carries no negativePrompt); the v1 default negative is not applied on the plan path");
		}

		Object cfgVal = cfg.get("cfg");
		double cfgScale = (cfgVal instanceof Number ? ((Number) cfgVal).doubleValue() : 7.0);
		StageRequest req = StageRequest.builder()
			.plan(plan)
			.stage(RenderPlan.STAGE_BASE)
			.prompt(compiled)
			.model(pick.getModel())
			.width(plan.getCanvas().getWidth())
			.height(plan.getCanvas().getHeight())
			.steps(plan.getPreset().getSteps())
			.cfg(cfgScale)
			.sampler((String) cfg.get("sampler"))
			.scheduler((String) cfg.get("scheduler"))
			.seed(StageRequest.SEED_RANDOM)
			.images(1)
			.hires(false)
			.config(cfg)
			.build();

		/// ─── render every variant before persisting any (§2.4 step 5, extended to the attempt) ───
		List<StageSequence> sequences = new ArrayList<>();
		/// the variant number of each rendered sequence: a failed later variant leaves a gap, and the artifacts of
		/// the ones after it keep their own index (their seed is derived from it)
		List<Integer> sequenceVariants = new ArrayList<>();
		List<RenderPlan.Degradation> added = new ArrayList<>();
		List<Map<String, Object>> stageErrors = new ArrayList<>();
		boolean allCalibrated = true;
		boolean anyGraph = false;
		Long seed0 = null;
		for(int v = 0; v < variantCount; v++) {
			StageRequest vReq = req;
			String vLabel = sceneLabel;
			if(v > 0) {
				vLabel = sceneLabel + " variant " + v;
				if(seed0 != null) {
					vReq = req.toBuilder().seed(variantSeed(seed0.longValue(), v)).build();
				}
				else {
					/// the renderer reported no seed for variant 0 (plain T2I with no metadata): this variant is
					/// random too, and the degradation says the attempt's seeds are not derived
					added.add(new RenderPlan.Degradation(RenderPlan.Degradation.VARIANT_SEED_UNKNOWN,
						"variant " + v + " rendered with a random seed: the renderer reported no seed for variant 0", null, RenderPlan.STAGE_BASE));
				}
			}
			StageSequence seq;
			try {
				seq = renderStages(g, node, plan, planOid, vLabel, sdu, vReq, pick, backend);
			}
			catch(PbStageFailedException e) {
				if(v == 0) {
					throw e;
				}
				/// §23: a later variant's BASE failure is not the node's failure - what rendered stands, and the
				/// attempt says it fell short (a free-form degradation, so auto-approve is vetoed; §19)
				for(Map<String, Object> se : e.getStageErrors()) {
					Map<String, Object> copy = new LinkedHashMap<>(se);
					copy.put("variant", Integer.valueOf(v));
					stageErrors.add(copy);
				}
				added.add(new RenderPlan.Degradation(RenderPlan.Degradation.VARIANT_FAILED,
					"variant " + v + " not rendered: " + e.getMessage(), null, RenderPlan.STAGE_BASE));
				logger.warn("renderPlanned: {} variant {} BASE failed; keeping {} rendered variant(s): {}", sceneLabel, v, sequences.size(), e.getMessage());
				continue;
			}
			if(v == 0) {
				seed0 = seq.outputs.get(0).seed;
			}
			sequences.add(seq);
			sequenceVariants.add(Integer.valueOf(v));
			added.addAll(seq.added);
			for(Map<String, Object> se : seq.stageErrors) {
				if(v == 0) {
					stageErrors.add(se);
				}
				else {
					Map<String, Object> copy = new LinkedHashMap<>(se);
					copy.put("variant", Integer.valueOf(v));
					stageErrors.add(copy);
				}
			}
			anyGraph |= seq.graphPath;
			allCalibrated &= (!seq.graphPath || seq.calibrationPersisted);
		}
		for(RenderPlan.Degradation d : added) {
			degradations.add(d.getCode() + ": " + d.getMessage());
		}
		if(!added.isEmpty()) {
			plan = recordExecutorDegradations(user, planArtifact, plan, added);
		}
		BaseRecord frozen = frozenConfig(plan, pick, compiled);
		String attempt = attemptOf(g);
		/// The `stage` images of every variant of this attempt are written against the role's pre-batch
		/// selection (the same rule as the finals - PbPipelineUtil.recordImage's batch overload), so variant 1's
		/// passes never supersede variant 0's.
		BaseRecord preStage = (variantCount > 1 ? PbArtifactUtil.findSelected(user, node, PbPipelineUtil.ROLE_STAGE) : null);

		/// §2.4 step 5: nothing was persisted while a later stage could still cancel; now every stage of every
		/// variant lands in order - its renderGraph artifact, and for every stage but the last its image as a
		/// `stage` artifact (backendGraph, references, duration) - then each variant's last image is persisted
		/// for the caller to record under its own role (§5.3: one `stage` image per intermediate pass, never a
		/// copy of the final). The plain T2I path persists exactly what it did before.
		List<PlannedRender.Variant> finals = new ArrayList<>();
		for(int s = 0; s < sequences.size(); s++) {
			StageSequence seq = sequences.get(s);
			int v = sequenceVariants.get(s).intValue();
			String vPrefix = (v == 0 ? imageNamePrefix : imageNamePrefix + "_v" + v);
			String vLabel = (v == 0 ? sceneLabel : sceneLabel + " variant " + v);
			String prevStageOid = null;
			if(seq.graphPath) {
				for(int i = 0; i < seq.outputs.size(); i++) {
					StageOutput so = seq.outputs.get(i);
					if(so.graph != null) {
						PbRenderPlanUtil.persistGraphArtifact(user, node, g.getArtifactGroupPath(), so.label, so.graph, seq.swarmVersion);
					}
					if(i == seq.outputs.size() - 1) {
						break;
					}
					BaseRecord stageData = PbPipelineUtil.persistBytes(g, vPrefix + "_" + so.label + "_" + System.currentTimeMillis(), so.bytes, "image/png");
					if(stageData == null) {
						throw new PictureBookException(500, "Failed to persist the " + so.label + " stage bytes for " + vLabel);
					}
					List<String> refs = new ArrayList<>(so.referenceIds);
					if(so.chainsPrevious && prevStageOid != null) {
						refs.add(prevStageOid);
					}
					BaseRecord stageArtifact;
					if(variantCount > 1) {
						stageArtifact = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_STAGE, PbArtifactTypeEnumType.IMAGE,
							stageData, so.bytes, "image/png", so.seed, frozen, so.requestJson, refs, sdu.getApiType(), so.durationMs, so.backendGraph,
							preStage, v, attempt);
					}
					else {
						stageArtifact = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_STAGE, PbArtifactTypeEnumType.IMAGE,
							stageData, so.bytes, "image/png", so.seed, frozen, so.requestJson, refs, sdu.getApiType(), so.durationMs, so.backendGraph,
							attempt);
					}
					so.artifactOid = (stageArtifact != null ? stageArtifact.get(FieldNames.FIELD_OBJECT_ID) : null);
					prevStageOid = so.artifactOid;
				}
			}
			StageOutput last = seq.outputs.get(seq.outputs.size() - 1);
			BaseRecord data = PbPipelineUtil.persistBytes(g, vPrefix + "_" + System.currentTimeMillis(), last.bytes, "image/png");
			if(data == null) {
				throw new PictureBookException(500, "Failed to persist the rendered bytes for " + vLabel);
			}
			List<String> finalRefs = new ArrayList<>(last.referenceIds);
			if(last.chainsPrevious) {
				/// the stage artifact the final pass painted over is the one persisted just before it
				String prior = seq.stageOid(seq.outputs.size() - 2);
				if(prior != null) {
					finalRefs.add(prior);
				}
			}
			finals.add(new PlannedRender.Variant(v, last.bytes, data, last.seed, last.requestJson, last.durationMs, finalRefs,
				last.backendGraph, seq.labels()));
		}
		return new PlannedRender(plan, planOid, compiled, frozen, degradations, configNotApplied, stageErrors,
			(anyGraph && allCalibrated), finals);
	}

	/// One stage's output, held in memory until the sequence ends (P5-2 §2.4 step 5).
	private static final class StageOutput {
		final String label;
		final byte[] bytes;
		final Long seed;
		final String requestJson;
		final long durationMs;
		final String backendGraph;
		/// The compiled graph the stage posted; null on the plain T2I path.
		final ComfyGraph graph;
		/// Artifact ids the stage's request referenced (portraits and location render, or a pass's own portrait).
		final List<String> referenceIds;
		/// True for a pass painted over the previous stage's image, whose artifact id joins its references.
		final boolean chainsPrevious;
		String artifactOid;

		StageOutput(String label, byte[] bytes, Long seed, String requestJson, long durationMs, String backendGraph,
				ComfyGraph graph, List<String> referenceIds, boolean chainsPrevious) {
			this.label = label;
			this.bytes = bytes;
			this.seed = seed;
			this.requestJson = requestJson;
			this.durationMs = durationMs;
			this.backendGraph = backendGraph;
			this.graph = graph;
			this.referenceIds = referenceIds;
			this.chainsPrevious = chainsPrevious;
		}
	}

	/// What {@link #renderStages} produced: every stage's output in order (the last is the final image), the
	/// stage errors, the executor-time degradations (ACTOR_FAILED, MODEL_SUBSTITUTED) to append to the plan,
	/// whether any stage went through the graph path, and whether every graph stage's calibration persisted.
	private static final class StageSequence {
		final List<StageOutput> outputs = new ArrayList<>();
		final List<Map<String, Object>> stageErrors = new ArrayList<>();
		final List<RenderPlan.Degradation> added = new ArrayList<>();
		boolean graphPath;
		boolean calibrationPersisted;
		/// The renderer version the graph client reported (null when it reported none); stamped on every
		/// renderGraph artifact and calibration sample of the sequence.
		String swarmVersion;

		List<String> labels() {
			List<String> l = new ArrayList<>();
			for(StageOutput so : outputs) {
				l.add(so.label);
			}
			return l;
		}

		String stageOid(int index) {
			return (index >= 0 && index < outputs.size() ? outputs.get(index).artifactOid : null);
		}
	}

	/**
	 * The stage sequence of P5-2 §2.4: BASE (a compiled graph carrying the BASE-stage portraits and the
	 * location render as references when the strategy sends any, otherwise the plain T2I request exactly as
	 * before), then one ACTOR_REGION pass per actor referenced on that stage, each a masked init-image pass
	 * over the last good image with the actor's own portrait as its one reference. A BASE failure ends the
	 * node ({@link PbStageFailedException}, §6.3); a failed pass is an ACTOR_FAILED degradation and the
	 * sequence continues on the last good image (§2.4 step 4); a cancel anywhere discards everything
	 * ({@link PbStageCancelledException}). Each graph stage records its calibration sample as the user
	 * (§4); the T2I path keeps calibrating in-process only (§10 row 6). Every call to the renderer goes
	 * through {@code backend} - the adapter for the stages, the client for generated workflows and the
	 * server version - so a test backend makes this method run with no server at all.
	 */
	private static StageSequence renderStages(SceneGraph g, BaseRecord node, RenderPlan plan, String planOid,
			String sceneLabel, SDUtil sdu, StageRequest req, RenderPlan.ModelPick basePick, RenderBackend backend) {
		BaseRecord user = g.getUser();
		long orgId = PbGraphUtil.orgId(node);
		StageSequence seq = new StageSequence();
		RendererAdapter adapter = backend.adapter(sdu);
		SwarmGraphClient client = backend.client(sdu);
		WorkflowCompiler compiler = new WorkflowCompiler();
		boolean allCalibrated = true;
		int graphStages = 0;

		/// ─── BASE ───
		List<RenderPlan.Actor> baseActors = new ArrayList<>();
		List<RenderPlan.Actor> regionActors = new ArrayList<>();
		boolean references = Flux2Compiler.usesReferences(plan.getStrategy());
		for(RenderPlan.Actor a : plan.getActors()) {
			if(!references || !a.isReferenced()) {
				continue;
			}
			if(RenderPlan.STAGE_BASE.equals(a.getReferenceStage())) {
				baseActors.add(a);
			}
			else if(RenderPlan.STAGE_ACTOR_REGION.equals(a.getReferenceStage())) {
				regionActors.add(a);
			}
		}
		baseActors.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
		regionActors.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
		/// A family that takes no references (SDXL) still renders SEQUENTIAL_INPAINT, but every image it is
		/// handed must be one its graph has a node for: no location render on BASE, no portrait on a pass.
		RendererCapabilities baseCaps = (basePick != null && basePick.isResolved() ? RendererCapabilities.forModel(basePick.getModel()) : null);
		boolean baseTakesReferences = (baseCaps != null && baseCaps.isSupportsReferences());
		String locationRef = (references && baseTakesReferences && plan.getLocation() != null ? plan.getLocation().getRenderArtifactId() : null);

		StageRequest baseReq = req;
		WorkflowCompiler.CompiledGraph baseGraph = null;
		List<String> baseRefIds = new ArrayList<>();
		if(!baseActors.isEmpty() || locationRef != null) {
			List<WorkflowCompiler.Reference> refs = new ArrayList<>();
			try {
				int k = 0;
				for(RenderPlan.Actor a : baseActors) {
					String oid = a.getIdentity().getPortraitArtifactId();
					byte[] png = referenceBytes(user, oid, orgId);
					if(png == null) {
						throw baseFailure(seq, sceneLabel, planOid, RendererAdapter.ERROR_GRAPH_INVALID + ": reference " + oid + " not readable", null);
					}
					refs.add(new WorkflowCompiler.Reference(referenceName(k++), oid, png));
					baseRefIds.add(oid);
				}
				if(locationRef != null) {
					byte[] png = referenceBytes(user, locationRef, orgId);
					if(png == null) {
						throw baseFailure(seq, sceneLabel, planOid, RendererAdapter.ERROR_GRAPH_INVALID + ": reference " + locationRef + " not readable", null);
					}
					refs.add(new WorkflowCompiler.Reference("location", locationRef, png));
					baseRefIds.add(locationRef);
				}
				baseGraph = compiler.compileBase(req, refs, client);
			}
			catch(GraphCompileException | IllegalArgumentException e) {
				throw baseFailure(seq, sceneLabel, planOid, RendererAdapter.ERROR_GRAPH_INVALID + ": " + e.getMessage(), null);
			}
			baseReq = req.toBuilder().graph(baseGraph.getGraph()).inputs(baseGraph.getInputs()).seed((int) baseGraph.getSeed())
				.steps(baseGraph.getSteps()).build();
		}

		StageResult base = adapter.execute(baseReq, g::isCancelRequested);
		if(!base.isOk()) {
			String error = (base.getError() != null ? base.getError() : RendererAdapter.ERROR_NO_IMAGES);
			if(RendererAdapter.ERROR_CANCELLED.equals(error) || g.isCancelRequested()) {
				throw new PbStageCancelledException("Cancelled render of " + sceneLabel + "; nothing recorded");
			}
			throw baseFailure(seq, sceneLabel, planOid, error, base.getErrorDetail());
		}
		if(g.isCancelRequested()) {
			throw new PbStageCancelledException("Cancelled render of " + sceneLabel + " after the renderer answered; image discarded");
		}
		StageImage baseImg = base.getImages().get(0);
		if(baseImg.getBytes() == null || baseImg.getBytes().length == 0) {
			throw new PictureBookException(500, "Rendered image for " + sceneLabel + " decoded to empty bytes (plan artifact " + planOid + ")");
		}
		Long baseSeed = (baseImg.isSeedReported() ? Long.valueOf(baseImg.getSeed()) : (baseGraph != null ? Long.valueOf(baseGraph.getSeed()) : null));
		seq.outputs.add(new StageOutput(RenderPlan.STAGE_BASE, baseImg.getBytes(), baseSeed, base.getRequestJson(), base.getDurationMs(),
			base.getBackendGraph(), (baseGraph != null ? baseGraph.getGraph() : null), baseRefIds, false));
		if(baseGraph != null) {
			seq.graphPath = true;
			graphStages++;
			allCalibrated &= PbCalibrationUtil.recordSample(user, sdu.getServer(), basePick.getFamily(), RenderPlan.STAGE_BASE,
				baseGraph.getGraph().graphHash(), base.getDurationMs(), baseGraph.getSteps(), req.getWidth(), req.getHeight(),
				baseRefIds.size(), swarmVersion(seq, client));
		}

		/// ─── ACTOR_REGION passes ───
		if(!regionActors.isEmpty() && plan.getStrategy().getStages().contains(RenderPlan.STAGE_ACTOR_REGION)) {
			RenderPlan.ModelPick inpaintPick = plan.getModels().get(ModelTaskKeys.key(PbModelTaskEnumType.INPAINT));
			RenderPlan.ModelPick regionPick = plan.pickForStage(RenderPlan.STAGE_ACTOR_REGION);
			if((inpaintPick == null || !inpaintPick.isResolved()) && regionPick != null && regionPick.isResolved()) {
				seq.added.add(new RenderPlan.Degradation(RenderPlan.Degradation.MODEL_SUBSTITUTED,
					"INPAINT pick unresolved; using BASE pick " + regionPick.getModel(), null, RenderPlan.STAGE_ACTOR_REGION));
			}
			RendererCapabilities caps = (regionPick != null && regionPick.isResolved() ? RendererCapabilities.forModel(regionPick.getModel()) : null);
			RendererCapabilities.InpaintParams inpaint = (caps != null ? caps.getInpaint() : null);
			boolean regionTakesReferences = (caps != null && caps.isSupportsReferences());
			for(RenderPlan.Actor a : regionActors) {
				StageOutput lastGood = seq.outputs.get(seq.outputs.size() - 1);
				String label = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, a.getActorId());
				String oid = a.getIdentity().getPortraitArtifactId();
				CompiledPrompt cp = plan.compiledForActor(RenderPlan.STAGE_ACTOR_REGION, a.getActorId());
				String invalid = null;
				if(regionPick == null || !regionPick.isResolved()) {
					invalid = "no model resolved for the pass";
				}
				else if(inpaint == null) {
					invalid = "family of " + regionPick.getModel() + " declares no inpaint parameters";
				}
				else if(cp == null || cp.getPrompt() == null || cp.getPrompt().isBlank()) {
					invalid = "no prompt compiled for " + label;
				}
				else if(a.getBox() == null) {
					invalid = "actor " + a.getActorId() + " has no box";
				}
				byte[] png = (invalid == null && regionTakesReferences ? referenceBytes(user, oid, orgId) : null);
				if(invalid == null && regionTakesReferences && png == null) {
					invalid = "reference " + oid + " not readable";
				}
				if(invalid != null) {
					actorFailed(seq, a, label, RendererAdapter.ERROR_GRAPH_INVALID + ": " + invalid, null);
					continue;
				}
				List<WorkflowCompiler.Reference> passRefs = (png != null ? List.of(new WorkflowCompiler.Reference(referenceName(0), oid, png)) : List.of());
				List<String> passRefIds = (png != null ? List.of(oid) : List.of());
				WorkflowCompiler.CompiledGraph cg;
				try {
					WorkflowCompiler.ActorTarget target = new WorkflowCompiler.ActorTarget(a, lastGood.bytes, List.of(a.getBox()));
					StageRequest reqA = req.toBuilder().stage(RenderPlan.STAGE_ACTOR_REGION).prompt(cp).model(regionPick.getModel()).build();
					cg = compiler.compileActorRegion(reqA, passRefs, target, inpaint, client);
					reqA = reqA.toBuilder().graph(cg.getGraph()).inputs(cg.getInputs()).seed((int) cg.getSeed()).steps(cg.getSteps()).build();
					StageResult res = adapter.execute(reqA, g::isCancelRequested);
					if(!res.isOk()) {
						String error = (res.getError() != null ? res.getError() : RendererAdapter.ERROR_NO_IMAGES);
						if(RendererAdapter.ERROR_CANCELLED.equals(error) || g.isCancelRequested()) {
							throw new PbStageCancelledException("Cancelled render of " + sceneLabel + " during " + label + "; nothing recorded");
						}
						actorFailed(seq, a, label, error, res.getErrorDetail());
						continue;
					}
					if(g.isCancelRequested()) {
						throw new PbStageCancelledException("Cancelled render of " + sceneLabel + " after " + label + " answered; images discarded");
					}
					StageImage img = res.getImages().get(0);
					if(img.getBytes() == null || img.getBytes().length == 0) {
						actorFailed(seq, a, label, RendererAdapter.ERROR_NO_IMAGES, null);
						continue;
					}
					Long seed = (img.isSeedReported() ? Long.valueOf(img.getSeed()) : Long.valueOf(cg.getSeed()));
					seq.outputs.add(new StageOutput(label, img.getBytes(), seed, res.getRequestJson(), res.getDurationMs(), res.getBackendGraph(),
						cg.getGraph(), passRefIds, true));
					seq.graphPath = true;
					graphStages++;
					allCalibrated &= PbCalibrationUtil.recordSample(user, sdu.getServer(), regionPick.getFamily(), RenderPlan.STAGE_ACTOR_REGION,
						cg.getGraph().graphHash(), res.getDurationMs(), cg.getSteps() - cg.getStartAtStep(), req.getWidth(), req.getHeight(),
						passRefIds.size(), swarmVersion(seq, client));
				}
				catch(GraphCompileException | IllegalArgumentException e) {
					actorFailed(seq, a, label, RendererAdapter.ERROR_GRAPH_INVALID + ": " + e.getMessage(), null);
				}
			}
		}
		seq.calibrationPersisted = (graphStages > 0 && allCalibrated);
		return seq;
	}

	/// The renderer version for the sequence, asked of the graph client the first time a graph stage needs
	/// it and kept on the sequence; asked again only while the client reports none, as the session cache
	/// behind it does. The plain T2I path never asks.
	private static String swarmVersion(StageSequence seq, SwarmGraphClient client) {
		if(seq.swarmVersion == null) {
			seq.swarmVersion = client.swarmVersion();
		}
		return seq.swarmVersion;
	}

	/// The image tag name of the i-th actor reference: `actora`, `actorb`, ... Swarm matches request keys on
	/// their lowercase letters only (design §38.1), so `actor1` / `actor2` would be one parameter on the
	/// server and {@code GraphInputs} refuses the second; a letter suffix stays distinct after the reduction.
	/// One letter, so 26 is the ceiling; past it the message names the limit rather than yielding `actor{`.
	static final int MAX_REFERENCE_NAMES = 26;

	static String referenceName(int index) {
		if(index < 0 || index >= MAX_REFERENCE_NAMES) {
			throw new IllegalArgumentException("Reference index " + index + " is outside the " + MAX_REFERENCE_NAMES + " letter-suffixed tags");
		}
		return "actor" + (char) ('a' + index);
	}

	/// A BASE stage failure (§6.3): 503 when the renderer could not be reached, 500 otherwise; the message
	/// keeps the {@code Render of <label> failed: } prefix and the plan artifact suffix the run error carries.
	private static PbStageFailedException baseFailure(StageSequence seq, String sceneLabel, String planOid, String error, String detail) {
		String coded = codedError(error);
		String code = RendererAdapter.errorCode(error);
		seq.stageErrors.add(stageError(RenderPlan.STAGE_BASE, code, coded, detail));
		int status = (RendererAdapter.CODE_UNREACHABLE.equals(code) ? 503 : 500);
		return new PbStageFailedException(status, "Render of " + sceneLabel + " failed: " + coded + " (plan artifact " + planOid + ")", seq.stageErrors);
	}

	/// A failed ACTOR_REGION pass (§2.4 step 4, §6.3): an ACTOR_FAILED degradation on the plan and a stage
	/// error; the sequence goes on with the last good image.
	private static void actorFailed(StageSequence seq, RenderPlan.Actor a, String label, String error, String detail) {
		String coded = codedError(error);
		seq.stageErrors.add(stageError(label, RendererAdapter.errorCode(error), coded, detail));
		seq.added.add(new RenderPlan.Degradation(RenderPlan.Degradation.ACTOR_FAILED,
			"ACTOR_REGION pass for " + a.getName() + " failed: " + coded, a.getActorId(), RenderPlan.STAGE_ACTOR_REGION));
		logger.warn("{}: {}", label, coded);
	}

	/// The PNG bytes of a reference artifact, read as the user; null when the artifact or its data cannot be
	/// read (the readability probe of the plan only ran at plan time, so the executor checks again).
	private static byte[] referenceBytes(BaseRecord user, String artifactOid, long orgId) {
		if(artifactOid == null || artifactOid.isBlank()) {
			return null;
		}
		BaseRecord artifact = PbArtifactUtil.readArtifact(user, artifactOid, orgId);
		byte[] png = readArtifactBytes(user, artifact);
		return (png != null && png.length > 0 ? png : null);
	}

	/// Append the executor-time degradations to the persisted plan artifact (its text and content hash) so
	/// the artifact a reviewer opens says what the render actually did; the in-memory plan is returned with
	/// them appended either way, and a failed patch is logged, never thrown.
	private static RenderPlan recordExecutorDegradations(BaseRecord user, BaseRecord planArtifact, RenderPlan plan,
			List<RenderPlan.Degradation> added) {
		RenderPlan updated = plan.withDegradations(added);
		String planOid = planArtifact.get(FieldNames.FIELD_OBJECT_ID);
		try {
			BaseRecord patch = PbGraphUtil.patchOf(planArtifact, OlioModelNames.MODEL_PB_ARTIFACT,
				OlioFieldNames.FIELD_PB_ARTIFACT_TEXT, OlioFieldNames.FIELD_PB_CONTENT_HASH);
			String json = updated.toJson();
			patch.set(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT, json);
			patch.set(OlioFieldNames.FIELD_PB_CONTENT_HASH, PbConfigUtil.sha256Hex(json));
			BaseRecord res = IOSystem.getActiveContext().getAccessPoint().update(user, patch);
			if(res == null) {
				logger.warn("Executor degradations were not recorded on plan artifact " + planOid);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("Executor degradations were not recorded on plan artifact " + planOid + ": " + e.getMessage());
		}
		return updated;
	}

	/**
	 * Whether {@code plan} depicts anything: an intent, location text, or at least one actor or element with
	 * a name or description. The compiled BASE prompt is not the test - the compiler also folds the camera
	 * (P4-10: a MIGRATED composition carries a default shot type and viewpoint), palette, style and photoreal
	 * boilerplate into it, and those are modifiers of a subject, not one; "medium, eye level" alone is not a
	 * scene to render.
	 */
	static boolean hasSubject(RenderPlan plan) {
		if(plan.getIntent() != null && NarrativeUtil.isMeaningful(plan.getIntent().getPromptOverride())) return true;
		if(plan.getLocation() != null && NarrativeUtil.isMeaningful(plan.getLocation().getDescription())) return true;
		for(RenderPlan.Actor a : plan.getActors()) {
			if(NarrativeUtil.isMeaningful(a.getName()) || NarrativeUtil.isMeaningful(a.getDescription())) return true;
		}
		for(RenderPlan.Element e : plan.getElements()) {
			if(NarrativeUtil.isMeaningful(e.getName()) || NarrativeUtil.isMeaningful(e.getDescription())) return true;
		}
		return false;
	}

	/// DTO key: effective sd.config values the plan path never applies (§24 as-built), each "CODE: text".
	/// The vocabulary is the illustration service's ({@link ConfigNotApplied}) so chat reports the same codes.
	public static final String KEY_CONFIG_NOT_APPLIED = ConfigNotApplied.KEY;
	public static final String NOT_APPLIED_HIRES = ConfigNotApplied.HIRES;
	public static final String NOT_APPLIED_STYLE_CLAUSE = ConfigNotApplied.STYLE_CLAUSE;
	public static final String NOT_APPLIED_DEFAULT_NEGATIVE = ConfigNotApplied.DEFAULT_NEGATIVE;

	/// 503 when the refusal is the renderer's (its inventory could not be read), 409 when it is the plan's
	/// (nothing installed matches the request); both are "reported before rendering" (§12).
	private static int unresolvedModelStatus(RenderPlan.ModelPick pick) {
		return ModelResolver.unresolvedStatus(pick);
	}

	/// The resolver's reason, never the server URL or API type (those stay in the log, as the inventory does).
	private static String unresolvedModelMessage(String sceneLabel, RenderPlan.ModelPick pick, RenderPlan plan, String planOid) {
		return ModelResolver.unresolvedMessage(sceneLabel, pick, plan, planOid);
	}

	/// The sd.config the artifact's snapshot freezes: the plan's config values (already a schema-ordered
	/// snapshot of the effective config) re-hydrated through the same path a node override takes, with the
	/// model, prompt and negative this stage sent on top; steps/canvas stay as snapshotted (generatorRequest is the record).
	private static BaseRecord frozenConfig(RenderPlan plan, RenderPlan.ModelPick pick, CompiledPrompt compiled) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", OlioModelNames.MODEL_SD_CONFIG);
		m.putAll(plan.getConfig());
		BaseRecord cfg = PbConfigUtil.parseOverride(JSONUtil.exportObject(m));
		try {
			if(cfg == null) {
				logger.warn("Plan config did not re-hydrate as " + OlioModelNames.MODEL_SD_CONFIG + "; freezing the stage values only");
				cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG,
					new String[] {"model", "description", "negativePrompt", "hires"});
			}
			cfg.set("model", pick.getModel());
			cfg.set("description", compiled.getPrompt());
			cfg.set("negativePrompt", compiled.getNegative() != null ? compiled.getNegative() : "");
			cfg.set("hires", false);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to freeze the render config: " + e.getMessage());
		}
		return cfg;
	}

	// ─── ChapBook page (LANDSCAPE node of a CHAPBOOK book; V3 §24, P1-9) ─────────

	/** True when {@code book.bookType} is CHAPBOOK (enum compared case-insensitively: lowercase on the wire, UPPERCASE in Java). */
	static boolean isChapBook(BaseRecord book) {
		if(book == null || !book.hasField(OlioFieldNames.FIELD_PB_BOOK_TYPE)) {
			return false;
		}
		Object bt = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		return bt != null && "CHAPBOOK".equalsIgnoreCase(String.valueOf(bt));
	}

	/**
	 * Render one ChapBook page as a LANDSCAPE node, plan-driven (V3 §24, P2-6): the page is a TEXT_ONLY
	 * scene with zero actors, so its {@link RenderPlan} is built exactly as a RENDER_PLAN node's is
	 * ({@link #renderPlannedScene}) and the page's {@code sdPrompt} - the prompt the continuity composer
	 * produced up front and the user may have locked - is the plan's {@code location.text}, sent verbatim
	 * as the MAIN clause (the compiler trims whitespace, nothing else). Same artifact, metrics, downstream-stale
	 * and DONE_UNVERIFIED closing as {@link #executeLandscape}, with the page-path specifics:
	 * <ul>
	 * <li>no generic fallback prompt - the facade refuses an un-prompted page before a node exists, so a
	 *     page whose {@code sdPrompt} is not meaningful here is a 409, not a degraded render;</li>
	 * <li>the image is named {@code chapbook_<sceneOid>_<ts>} and, after the artifact exists, the page's
	 *     {@code imageObjectId} is pointed at it and {@code imageStale} cleared, as the requesting user. A
	 *     refused or failed scene write-back is logged and reported in {@code degradations}; it never fails
	 *     the node, because the artifact is the record of truth on this path and {@code bookPageView}
	 *     reads the selected artifact first.</li>
	 * </ul>
	 * The node is the config-override carrier: {@code PbServiceFacade.ensureChapBookPageNode} mirrors the
	 * scene's {@code configOverride} and {@code sdPrompt} onto it before every submit, so the plan's node
	 * tier is the scene's override - minus the per-request client overlay, which the v3 path drops by
	 * design (§24). The v1 page path's {@code fillStyleDefaults} / {@code applyConfigStyle} / default
	 * negative are not applied here; see {@link #renderPlannedScene} for how each is reported instead.
	 * <p>
	 * Optional page location (V3 §24, P3-6): a page may point at an {@code olio.pb.location} of the book's
	 * world through the same two scene FKs a PictureBook scene uses ({@code location}, {@code locationVariant}),
	 * so both are projected here and {@link PbRenderPlanUtil#resolveSceneLocation} runs for the page exactly
	 * as for a RENDER_PLAN scene. Set, the compiled BASE MAIN clause is the location's subject text followed
	 * by the page's {@code sdPrompt} ({@code PbRenderPlanUtil.joinSentences}); the location's approved render
	 * is carried on {@code RenderPlan.Location.renderArtifactId} and not consumed by the TEXT_ONLY strategy
	 * (D4), and a location with no approved render is a {@code LOCATION_TEXT_ONLY} degradation that vetoes
	 * auto-approve (D5). A pointer the resolver refuses (another world, a variant of another location, §19)
	 * plans as unlocated with the same degradation. Unset, nothing changes: the MAIN clause is the
	 * {@code sdPrompt} alone and no binding is written. After the image and the page patch, the page node
	 * binds the location (ordinal 0) and variant (ordinal 1) the plan accepted under role {@code location}
	 * ({@link #bindSceneLocation}) - a refused pointer is not bound - so an approve or an edit of the
	 * location stales the page through the drift scan. {@code scene.sdPrompt} stays the page's own text; the full compiled prompt lives on the
	 * plan artifact. Because the FK is now projected, a page whose pointer this user cannot read is itself
	 * unreadable (nested-foreign read rule) and is reported as 404 "Page not found".
	 */
	private static Map<String, Object> executeChapBookPage(SceneGraph g, BaseRecord node, SDUtil sdu) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "ChapBook page node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_PB_SCENE_INDEX, OlioFieldNames.FIELD_CB_SD_PROMPT,
			FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT
		});
		if(scene == null) {
			throw new PictureBookException(404, "Page " + scopeRef + " not found");
		}
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		/// The page prompt is the CHAPBOOK_PAGE composition's promptOverride - the authority (P4-10); the
		/// composition is read FIRST and scene.sdPrompt, its mirror, second. The plan builder makes the same
		/// choice (PbRenderPlanUtil.locationText); this gate only refuses a page that has neither.
		String pagePrompt = compositionPrompt(findComposition(user, book, scene, orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
		if(pagePrompt == null) {
			pagePrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		}
		if(!NarrativeUtil.isMeaningful(pagePrompt)) {
			throw new PictureBookException(409, "Page " + sceneIndex
				+ " has no prompt; set or generate its landscape prompt before rendering it");
		}
		String sceneLabel = "page " + (sceneIndex != null ? sceneIndex.toString() : sceneOid);

		PlannedRender r = renderPlannedScene(g, node, scene, sceneLabel, sdu, "chapbook_" + sceneOid,
			"Page " + sceneIndex + " has no text to render: set or generate its landscape prompt");
		List<String> degradations = r.degradations;
		byte[] bytes = r.bytes;
		BaseRecord artifact = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE,
			r.data, bytes, "image/png", r.seed, r.frozen, r.requestJson, r.referenceArtifactObjectIds, sdu.getApiType(), r.durationMs,
			r.backendGraph, attemptOf(g));

		/// The scene keeps pointing at the newest image so the v1 readers (bookPageView's fallback, the
		/// health scan's SCENE_IMAGE_* findings) stay coherent; the artifact chain is what review acts on.
		String imageOid = r.data.get(FieldNames.FIELD_OBJECT_ID);
		try {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, OlioFieldNames.FIELD_PB_IMAGE_STALE);
			patch.set(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, imageOid);
			patch.set(OlioFieldNames.FIELD_PB_IMAGE_STALE, false);
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.warn("executeChapBookPage: failed to point page {} at image {}", sceneOid, imageOid);
				degradations.add(PbPipelineUtil.ROLE_LANDSCAPE + ": page imageObjectId could not be updated; the artifact is selected but the page still points at its previous image");
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException | PictureBookException e) {
			logger.warn("executeChapBookPage: could not assemble the page patch for {}: {}", sceneOid, e.getMessage());
			degradations.add(PbPipelineUtil.ROLE_LANDSCAPE + ": page imageObjectId could not be updated: " + e.getMessage());
		}

		/// After the page patch, so a binding failure cannot leave the page pointing at its previous image.
		bindSceneLocation(g, node, r.plan, degradations);

		/// completeNode (not a bare persistStatus) so the page node gets its inputHash/lastRunAt: the drift
		/// scan never marks a node with inputHash == null, and a page that cannot go STALE cannot be reviewed.
		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = artifactDto(node, "DONE_UNVERIFIED", artifact, Long.valueOf(bytes.length), downstream, degradations);
		out.put("sceneObjectId", sceneOid);
		out.put("imageObjectId", imageOid);
		r.describe(out);
		return out;
	}

	/// The scene's composition of the given purpose (CHAPBOOK_PAGE for a ChapBook page, SCENE for a story
	/// scene), or null when it has none or it cannot be read (P4-8 / P4-10). Keyed on the book's group — the
	/// group every composition is created in — not the scene's. Never throws: the engine readers fall back to
	/// the scene's mirrored fields.
	private static BaseRecord findComposition(BaseRecord user, BaseRecord book, BaseRecord scene, long orgId, PbCompositionPurposeEnumType purpose) {
		if(book == null || scene == null) return null;
		long groupId = (book.hasField(FieldNames.FIELD_GROUP_ID) && book.get(FieldNames.FIELD_GROUP_ID) != null
			? ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue() : 0L);
		if(groupId <= 0L) return null;
		try {
			return PbComposeUtil.findSceneComposition(user, scene, groupId, orgId, purpose);
		}
		catch(RuntimeException e) {
			logger.warn("findComposition: scene {} {} composition unreadable: {}", scene.get(FieldNames.FIELD_OBJECT_ID), purpose, e.getMessage());
			return null;
		}
	}

	/// The composition's promptOverride when it carries a meaningful one (NarrativeUtil.isMeaningful - the
	/// literal "null"/"none" an LLM may have written counts as absent), else null so the caller falls back to
	/// the scene's mirrored sdPrompt (P4-10).
	private static String compositionPrompt(BaseRecord comp) {
		if(comp == null || !comp.hasField(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE)) return null;
		String p = comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE);
		return (NarrativeUtil.isMeaningful(p) ? p : null);
	}

	// ─── prompt nodes (SCENE_PROMPT / LANDSCAPE_PROMPT) ──────────────────────────

	private static Map<String, Object> executePromptNode(SceneGraph g, BaseRecord node, PbNodeTypeEnumType nodeType) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Prompt node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, FieldNames.FIELD_DESCRIPTION
		});
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}

		String role = (nodeType == PbNodeTypeEnumType.LANDSCAPE_PROMPT)
			? PbPipelineUtil.ROLE_LANDSCAPE_PROMPT
			: PbPipelineUtil.ROLE_SCENE_PROMPT;

		String promptText;
		if(nodeType == PbNodeTypeEnumType.LANDSCAPE_PROMPT) {
			/// P4-10: the recorded landscape prompt is what the LANDSCAPE node renders from and drifts on, so
			/// it resolves the same way executeLandscape / executeChapBookPage do - the composition's
			/// promptOverride first, the scene's mirrored sdPrompt second.
			PbCompositionPurposeEnumType purpose = (isChapBook(book) ? PbCompositionPurposeEnumType.CHAPBOOK_PAGE : PbCompositionPurposeEnumType.SCENE);
			promptText = compositionPrompt(findComposition(user, book, scene, orgId, purpose));
			if(promptText == null) {
				promptText = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			}
		}
		else {
			promptText = scene.get(FieldNames.FIELD_DESCRIPTION);
		}
		List<String> degradations = new ArrayList<>();
		if(promptText == null || promptText.isBlank()) {
			promptText = (promptText == null ? "" : promptText);
			degradations.add(role + ": scene has no "
				+ (nodeType == PbNodeTypeEnumType.LANDSCAPE_PROMPT ? "sdPrompt" : "description") + "; empty prompt recorded");
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, role,
			PbArtifactTypeEnumType.TEXT, g.getArtifactGroupPath(), null, promptText, null, null);

		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		return artifactDto(node, "DONE_UNVERIFIED", artifact, null, downstream, degradations);
	}

	// ─── reference strip ─────────────────────────────────────────────────────────

	/**
	 * Stitch the source portrait and landscape artifacts side-by-side into an IMAGE_STRIP for use
	 * as a FLUX.2 reference image. Does NOT require an SD server — pure Java 2D work.
	 *
	 * @deprecated PB2-era node (§28.1 Stage 1 Coexist): only PB1 ({@code PictureBookUtil.generateSceneImage})
	 *             creates REFERENCE_STRIP nodes; V3 passes references through the compiled graph
	 *             ({@link #renderStages}) and never stitches. Kept so persisted nodes still execute; removed
	 *             with PB1 at Stage 4 (Phase 10) together with {@code SDUtil.stitchSceneImages}.
	 */
	@Deprecated
	private static Map<String, Object> executeReferenceStrip(SceneGraph g, BaseRecord node) {
		BaseRecord user = g.getUser();
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Gather portrait and landscape bytes from bound source nodes (cache:false via listBindings)
		List<BaseRecord> bindings = PbGraphUtil.listBindings(user, node);
		byte[] portrait0Bytes = null;
		byte[] portrait1Bytes = null;
		byte[] landscapeBytes = null;
		List<String> degradations = new ArrayList<>();

		for(BaseRecord binding : bindings) {
			String role = binding.get(OlioFieldNames.FIELD_PB_ROLE);
			if(role == null) continue;
			if(!PbPipelineUtil.ROLE_PORTRAIT_0.equals(role) && !PbPipelineUtil.ROLE_PORTRAIT_1.equals(role)
					&& !PbPipelineUtil.ROLE_LANDSCAPE.equals(role)) {
				continue;
			}
			BaseRecord srcStub = binding.get(OlioFieldNames.FIELD_PB_SOURCE_NODE);
			String srcOid = (srcStub != null ? srcStub.get(FieldNames.FIELD_OBJECT_ID) : null);
			BaseRecord srcNode = (srcOid != null ? PbGraphUtil.readNode(user, srcOid, orgId) : null);
			if(srcNode == null) {
				degradations.add(role + ": source node unreadable");
				continue;
			}

			String imgRole = PbPipelineUtil.ROLE_LANDSCAPE.equals(role)
				? PbPipelineUtil.ROLE_LANDSCAPE : PbPipelineUtil.ROLE_PORTRAIT;
			BaseRecord sel = PbArtifactUtil.findSelected(user, srcNode, imgRole);
			if(sel == null) {
				degradations.add(role + ": no selected artifact");
				continue;
			}
			byte[] artifactBytes = readArtifactBytes(user, sel);
			if(artifactBytes == null || artifactBytes.length == 0) {
				degradations.add(role + ": artifact has no image bytes");
				continue;
			}

			if(PbPipelineUtil.ROLE_PORTRAIT_0.equals(role)) {
				portrait0Bytes = artifactBytes;
			} else if(PbPipelineUtil.ROLE_PORTRAIT_1.equals(role)) {
				portrait1Bytes = artifactBytes;
			} else if(PbPipelineUtil.ROLE_LANDSCAPE.equals(role)) {
				landscapeBytes = artifactBytes;
			}
		}

		if(portrait0Bytes == null && portrait1Bytes == null && landscapeBytes == null) {
			throw new PictureBookException(400, "Reference-strip node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " has no resolved source artifacts — execute portrait and landscape nodes first");
		}

		int panelSize = Flux2Defaults.referenceSize();
		byte[] stripBytes = SDUtil.stitchSceneImages(portrait0Bytes, portrait1Bytes, landscapeBytes, panelSize);
		if(stripBytes == null || stripBytes.length == 0) {
			throw new PictureBookException(500, "stitchSceneImages returned empty bytes for reference strip");
		}

		String artifactPath = g.getArtifactGroupPath();
		String stripName = "ref_strip_" + node.get(OlioFieldNames.FIELD_PB_HANDLE) + "_" + System.currentTimeMillis();

		// Create and persist a data.data record for the stitched bytes (pattern from PbPipelineUtil.persistBytes)
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, artifactPath);
		plist.parameter(FieldNames.FIELD_NAME, stripName);
		BaseRecord dataRecord;
		try {
			dataRecord = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
			ByteModelUtil.setValue(dataRecord, stripBytes);
			dataRecord.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble reference strip data record: " + e.getMessage());
		}
		BaseRecord createdData = IOSystem.getActiveContext().getAccessPoint().create(user, dataRecord);
		if(createdData == null) {
			throw new PictureBookException(500, "Failed to create reference strip data record in " + artifactPath);
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_REFERENCE_STRIP,
			PbArtifactTypeEnumType.IMAGE_STRIP, artifactPath, createdData, null, null, null);

		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		return artifactDto(node, "DONE_UNVERIFIED", artifact, Long.valueOf(stripBytes.length), downstream, degradations);
	}

	// ─── composite ───────────────────────────────────────────────────────────────

	/**
	 * The PB2-era scene composite: one {@code SceneCompositeUtil.buildSceneRequest} T2I request from the
	 * bound portraits, landscape and the scene's mirrored prompt.
	 *
	 * @deprecated PB2-era node (§28.1 Stage 1 Coexist): only PB1 creates COMPOSITE nodes; V3 renders the
	 *             scene through RENDER_PLAN ({@link #executeRenderPlan}). Kept so persisted nodes still
	 *             execute - including the retired-mode refusal it shares with every other entry point -
	 *             and removed with PB1 at Stage 4 (Phase 10).
	 */
	@Deprecated
	private static Map<String, Object> executeComposite(SceneGraph g, BaseRecord node, SDUtil sdu) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Composite node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Load scene for action/setting/mood/prompt
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_MOOD,
			OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, FieldNames.FIELD_DESCRIPTION
		});
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}
		String action = scene.get(OlioFieldNames.FIELD_PB_ACTION);
		String setting = scene.get(OlioFieldNames.FIELD_PB_SETTING);
		String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
		/// P4-10: the SCENE composition's promptOverride first, the scene's mirrored sdPrompt second, the
		/// scene description last - this text goes to SD through buildSceneRequest.
		String scenePrompt = compositionPrompt(findComposition(user, book, scene, orgId, PbCompositionPurposeEnumType.SCENE));
		if(scenePrompt == null) {
			scenePrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		}
		if(scenePrompt == null || scenePrompt.isBlank()) {
			scenePrompt = scene.get(FieldNames.FIELD_DESCRIPTION);
		}
		if(scenePrompt == null) scenePrompt = "";

		// Gather portrait bytes + descriptions and landscape bytes from bound source nodes
		List<BaseRecord> bindings = PbGraphUtil.listBindings(user, node);
		byte[] leftBytes = null;     // PORTRAIT_0
		byte[] centerBytes = null;   // PORTRAIT_1 (mapped to buildSceneRequest's rightBytes param)
		byte[] landscapeBytes = null;
		String leftDesc = "";
		String rightDesc = "";
		List<String> degradations = new ArrayList<>();

		for(BaseRecord binding : bindings) {
			String bRole = binding.get(OlioFieldNames.FIELD_PB_ROLE);
			if(bRole == null) continue;
			boolean isPort0 = PbPipelineUtil.ROLE_PORTRAIT_0.equals(bRole);
			boolean isPort1 = PbPipelineUtil.ROLE_PORTRAIT_1.equals(bRole);
			boolean isLand  = PbPipelineUtil.ROLE_LANDSCAPE.equals(bRole);
			if(!isPort0 && !isPort1 && !isLand) continue;

			BaseRecord srcStub = binding.get(OlioFieldNames.FIELD_PB_SOURCE_NODE);
			String srcOid = (srcStub != null ? srcStub.get(FieldNames.FIELD_OBJECT_ID) : null);
			BaseRecord srcNode = (srcOid != null ? PbGraphUtil.readNode(user, srcOid, orgId) : null);
			if(srcNode == null) {
				degradations.add(bRole + ": source node unreadable");
				continue;
			}

			String imgRole = isLand ? PbPipelineUtil.ROLE_LANDSCAPE : PbPipelineUtil.ROLE_PORTRAIT;
			BaseRecord sel = PbArtifactUtil.findSelected(user, srcNode, imgRole);
			if(sel == null) {
				degradations.add(bRole + ": no selected artifact");
				continue;
			}
			byte[] artifactBytes = readArtifactBytes(user, sel);
			if(artifactBytes == null || artifactBytes.length == 0) {
				degradations.add(bRole + ": artifact has no image bytes");
				continue;
			}

			if(isPort0) {
				leftBytes = artifactBytes;
				String pt = srcNode.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT);
				if(pt != null && !pt.isBlank()) leftDesc = pt;
			} else if(isPort1) {
				centerBytes = artifactBytes;
				String pt = srcNode.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT);
				if(pt != null && !pt.isBlank()) rightDesc = pt;
			} else {
				landscapeBytes = artifactBytes;
			}
		}

		PbStyleResolver.EffectiveStyle style = styleFor(g, node);
		BaseRecord effectiveConfig = PbConfigUtil.resolveEffectiveConfig(book, node, true, null, style);
		String mode = SceneCompositeUtil.resolveMode(effectiveConfig, false);
		/// V3 §37.3 Disable: a retired mode fails the node (and the run, via executeNodeRun's catch)
		/// before the model policy and before any SD contact.
		PbRenderPath.requireEnabledCompositeMode(mode, "scene " + scopeRef);
		boolean flux2 = SceneCompositeUtil.MODE_FLUX2.equals(mode);
		boolean hasRefs = (leftBytes != null || centerBytes != null || landscapeBytes != null);
		applyModelPolicy(g, node, true, style, (flux2 ? PbModelTaskEnumType.SCENE_DIRECT : PbModelTaskEnumType.LOCATION),
			effectiveConfig, sdu, flux2 && hasRefs);
		double creativity = SceneCompositeUtil.defaultCreativity(mode);
		String negPrompt = NarrativeUtil.getDefaultNegativePrompt();

		// centerBytes maps to buildSceneRequest's "rightBytes" parameter (second portrait)
		SWTxt2Img sceneRequest = SceneCompositeUtil.buildSceneRequest(mode,
			leftDesc, rightDesc, action, setting, mood, scenePrompt, negPrompt,
			leftBytes, centerBytes, landscapeBytes, creativity, effectiveConfig);
		if(sceneRequest == null) {
			throw new PictureBookException(500,
				"Could not build a composite scene request for scene " + scopeRef + " mode=" + mode);
		}

		String artifactPath = g.getArtifactGroupPath();
		String sceneName = "composite_" + scopeRef + "_" + System.currentTimeMillis();

		long callStarted = System.currentTimeMillis();
		List<BaseRecord> images = sdu.createSceneImage(user, artifactPath, sceneName, sceneRequest, null, null);
		long callMs = System.currentTimeMillis() - callStarted;
		discardIfCancelled(g, images, "composite of scene " + scopeRef);
		if(images == null || images.isEmpty()) {
			throw new PictureBookException(500, "SD backend returned no images for composite of scene " + scopeRef);
		}
		BaseRecord image = images.get(0);
		byte[] bytes;
		try {
			bytes = ByteModelUtil.getValue(image);
		}
		catch(FieldException | ValueException e) {
			throw new PictureBookException(500, "Failed to read composite bytes for scene " + scopeRef + ": " + e.getMessage());
		}
		if(bytes == null || bytes.length == 0) {
			throw new PictureBookException(500, "Composite image for scene " + scopeRef + " decoded to empty bytes");
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_COMPOSITE,
			PbArtifactTypeEnumType.IMAGE, artifactPath, image, null, effectiveConfig, null);
		int[] wh = PbPipelineUtil.decodeDimensions(bytes);
		PbArtifactUtil.recordImageMetrics(user, artifact, bytes, "image/png", wh[0], wh[1], null, sdu.getApiType(), callMs);

		List<BaseRecord> downstream = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);
		PbPipelineUtil.completeNode(g, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		return artifactDto(node, "DONE_UNVERIFIED", artifact, Long.valueOf(bytes.length), downstream, degradations);
	}

	// ─── portrait ────────────────────────────────────────────────────────────────

	/**
	 * Render a character portrait the way the v1 scene pipeline's Stage 1 does
	 * ({@code PictureBookUtil.generateSceneImage}; gap doc G-30), so a portrait regenerated from the
	 * canvas is the portrait the book would have rendered:
	 * <ol>
	 * <li>the character is resolved through {@code PictureBookUtil.resolveSceneCharacter} — a
	 * PBAC-checked read as the caller, with the record-driven narrative refresh, the hand-written
	 * {@code sdPrompt} rule and the frozen-description fallbacks — not a {@code GameUtil.findCharacter}
	 * bypass of a bare {@code narrative.sdPrompt};</li>
	 * <li>scene-tagged apparel is selected for the node's {@code sceneIndex} and the outfit folded into
	 * the prompt ({@code selectSceneApparel} / {@code NarrativeUtil.describeOutfit});</li>
	 * <li>the prompt is styled once, through {@code buildPortraitDescription} over the character's own
	 * style config ({@code resolveCharacterStyleConfig}) on top of the node's effective config, with the
	 * default negative prompt when no tier set one, and {@code hires=false};</li>
	 * <li>the CHARACTER binding is (re)bound <b>after</b> the apparel flip so its {@code refHash} is
	 * taken over what this render consumed; the resolved prompt is cached on the node; the image is
	 * recorded with its seed and config snapshot; the node is completed DONE with an {@code inputHash};</li>
	 * <li>the new image is linked as the character profile's portrait
	 * ({@code PictureBookUtil.linkPortraitToProfile}) so the v1 reuse branch and the character screens see
	 * it. A failed link is reported on the DTO ({@code portraitLinked=false}), never thrown — the artifact
	 * already exists and the GPU has already been paid for.</li>
	 * </ol>
	 * Unlike v1 there is no reuse branch: a single-node execution is an explicit request for a <i>new</i>
	 * attempt. And unlike the other executors the node closes DONE (not DONE_UNVERIFIED): the config, seed
	 * and bindings that produced the image are all recorded, so there is nothing unverified about it.
	 */
	private static Map<String, Object> executePortrait(SceneGraph g, BaseRecord node, SDUtil sdu) {
		BaseRecord user = g.getUser();
		BaseRecord book = g.getBook();
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Portrait node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (charPerson objectId)");
		}
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String bookGroupPath = PbBookUtil.bookGroupPath(slug);

		ResolvedCharacter rc = PictureBookUtil.resolveSceneCharacter(user, scopeRef, bookGroupPath, slug);
		if(rc == null) {
			throw new PictureBookException(404, "Character " + scopeRef + " not found or not readable");
		}
		BaseRecord charPerson = rc.charPerson;
		String charName = (rc.name != null) ? rc.name : PbPipelineUtil.shortRef(scopeRef);
		String cpOid = charPerson.get(FieldNames.FIELD_OBJECT_ID);
		String portraitPrompt = rc.portraitPrompt;
		if(portraitPrompt == null || portraitPrompt.isBlank()) {
			throw new PictureBookException(500, "No portrait prompt could be resolved for character " + charName);
		}

		boolean hasSceneApparel = PictureBookUtil.selectSceneApparel(user, charPerson, g.getSceneIndex());
		if(hasSceneApparel) {
			String outfitDesc = NarrativeUtil.describeOutfit(charPerson, false);
			if(outfitDesc != null && !outfitDesc.isBlank()) {
				portraitPrompt = portraitPrompt + ", " + outfitDesc;
			}
		}

		PbStyleResolver.EffectiveStyle style = styleFor(g, node);
		BaseRecord effectiveConfig = PbConfigUtil.resolveEffectiveConfig(book, node, false, null, style);
		applyModelPolicy(g, node, false, style, PbModelTaskEnumType.PORTRAIT, effectiveConfig, sdu, false);
		BaseRecord styleConfig = PictureBookUtil.resolveCharacterStyleConfig(user, bookGroupPath, cpOid, effectiveConfig);
		String description = PictureBookUtil.buildPortraitDescription(portraitPrompt, styleConfig);
		try {
			effectiveConfig.set("description", description);
			String negative = effectiveConfig.get("negativePrompt");
			if(negative == null || negative.isBlank()) {
				effectiveConfig.set("negativePrompt", PictureBookUtil.NEG_PROMPT);
			}
			effectiveConfig.set("hires", false);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set description on effective config: " + e.getMessage());
		}

		String artifactPath = g.getArtifactGroupPath();
		String portName = "portrait_" + charName.replace(" ", "_") + "_" + System.currentTimeMillis();

		long callStarted = System.currentTimeMillis();
		List<BaseRecord> portImages = sdu.createImage(user, artifactPath, effectiveConfig, portName, 1, SDUtil.hiresOf(effectiveConfig), -1);
		long callMs = System.currentTimeMillis() - callStarted;
		discardIfCancelled(g, portImages, "portrait of " + charName);
		if(portImages == null || portImages.isEmpty()) {
			throw new PictureBookException(500, "SD backend returned no images for portrait of " + charName);
		}
		BaseRecord portImage = portImages.get(0);
		byte[] portBytes;
		try {
			portBytes = ByteModelUtil.getValue(portImage);
		}
		catch(FieldException | ValueException e) {
			throw new PictureBookException(500, "Failed to read portrait bytes for " + charName + ": " + e.getMessage());
		}
		if(portBytes == null || portBytes.length == 0) {
			throw new PictureBookException(500, "Portrait image for " + charName + " decoded to empty bytes");
		}

		/// Bound after the apparel flip (inuse is a watched field) so the refHash matches what was rendered.
		PbPipelineUtil.bindRecord(g, node, PbPipelineUtil.ROLE_CHARACTER, 0, OlioModelNames.MODEL_CHAR_PERSON, cpOid);
		PbGraphUtil.persistPromptText(user, node, description);
		/// Re-read as a top-level data.data: AccessPoint.create returns identity fields only, so the record
		/// createImage handed back is not a usable foreign reference as-is (measured 2026-08-17, v1 reuse branch).
		BaseRecord portraitRef = PictureBookUtil.readDataRecord(user, portImage.get(FieldNames.FIELD_OBJECT_ID));
		if(portraitRef == null) {
			throw new PictureBookException(500, "Portrait image for " + charName + " was created but could not be re-read");
		}
		BaseRecord artifact = PbPipelineUtil.recordImage(g, node, PbPipelineUtil.ROLE_PORTRAIT,
			PbArtifactTypeEnumType.IMAGE, portraitRef, portBytes, "image/png",
			Long.valueOf(PictureBookUtil.extractSeedFromImage(portImage)), effectiveConfig,
			JSONUtil.exportObject(effectiveConfig), null, sdu.getApiType(), callMs);
		if(artifact == null) {
			throw new PictureBookException(500, "Portrait artifact for " + charName + " could not be recorded");
		}
		PbPipelineUtil.completeNode(g, node);
		List<BaseRecord> marked = PbGraphUtil.downstreamOf(user, g.getWorkflow(), node);

		/// V3 §10 rules 1 and 3: when the chain's selection is APPROVED the newcomer is recorded unselected,
		/// and the profile keeps the approved portrait - linking the unselected image would make the approved
		/// one vanish from the character screens. Select/approve on a PORTRAIT node re-linking the profile is
		/// a recorded follow-up, not part of this path.
		Boolean artifactSelected = artifact.get(OlioFieldNames.FIELD_PB_SELECTED);
		boolean selected = (artifactSelected != null && artifactSelected.booleanValue());
		boolean linked = false;
		if(selected) {
			linked = PictureBookUtil.linkPortraitToProfile(user, charPerson, null, portImage);
			if(!linked) {
				logger.error("Portrait for " + charName + " was rendered and recorded but could not be linked to the character profile");
			}
		}
		else {
			logger.info("Portrait for " + charName + " was recorded unselected (an APPROVED revision is selected); the profile portrait is unchanged");
		}

		Map<String, Object> out = artifactDto(node, PbNodeStatusEnumType.DONE.name(), artifact, Long.valueOf(portBytes.length), marked, null);
		out.put("characterObjectId", cpOid);
		out.put("portraitLinked", Boolean.valueOf(linked));
		out.put("artifactSelected", Boolean.valueOf(selected));
		if(!selected) {
			out.put("portraitLinkSkipped", "the selected revision is APPROVED");
		}
		out.put("sceneApparel", Boolean.valueOf(hasSceneApparel));
		return out;
	}

	// ─── DTO ─────────────────────────────────────────────────────────────────────

	/**
	 * The DTO every executor returns. {@code degradations} is always present (empty when the attempt consumed
	 * everything the graph bound): each entry names an input the executor went without — a bound reference
	 * role whose source resolved to nothing, a scene with no prompt text so a generic fallback was rendered.
	 * The §19 {@code degradations[]} precursor; the auto-approver (§10 rule 5) refuses to pin a degraded
	 * attempt, since nothing on the v1 path's {@code landscapeFailed} flag exists here to key on.
	 */
	private static Map<String, Object> artifactDto(BaseRecord node, String nodeStatus, BaseRecord artifact,
			Long byteLength, List<BaseRecord> downstream, List<String> degradations) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("nodeStatus", nodeStatus);
		out.put("artifactObjectId", artifact.get(FieldNames.FIELD_OBJECT_ID));
		out.put("artifactRevision", artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		if(byteLength != null) {
			out.put("byteLength", byteLength);
		}
		out.put("downstreamMarked", Integer.valueOf(downstream.size()));
		List<String> downstreamHandles = new ArrayList<>();
		for(BaseRecord d : downstream) {
			downstreamHandles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", downstreamHandles);
		out.put("degradations", new ArrayList<>(degradations != null ? degradations : Collections.emptyList()));
		return out;
	}

	/** The {@code degradations} list an executor put on its DTO, never null. */
	@SuppressWarnings("unchecked")
	static List<String> degradationsOf(Map<String, Object> out) {
		Object d = (out != null ? out.get("degradations") : null);
		return (d instanceof List ? (List<String>) d : Collections.emptyList());
	}

	// ─── scene lookup helper ─────────────────────────────────────────────────────

	/**
	 * Resolve the {@code olio.pb.scene} record for a node whose {@code scopeRef} may be either:
	 * <ol>
	 *   <li>the {@code olio.pb.scene} objectId (new pipeline nodes created by {@code getCreateSceneRow}), or</li>
	 *   <li>the PB1 {@code data.note} objectId stored by {@code getCreateNode} when the node was first
	 *       written (legacy nodes from early PB2 seeding runs).</li>
	 * </ol>
	 * The direct lookup is attempted first. If it fails, the method falls back to listing all scenes for
	 * the book and matching by {@code sceneIndex} from the node record. This keeps callers honest about
	 * what objectId they are dealing with without requiring a re-seed.
	 *
	 * @param requestFields fields to include in the projection (passed to both the direct and fallback queries)
	 * @return the matching {@code olio.pb.scene}, or {@code null} if none can be found
	 */
	private static BaseRecord findSceneByNodeRef(BaseRecord user, BaseRecord book, BaseRecord node,
			long orgId, String[] requestFields) {
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			return null;
		}

		// Primary: direct objectId lookup
		Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, scopeRef);
		sq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sq.setRequest(requestFields);
		sq.setCache(false);
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
		if(scene != null) {
			return scene;
		}

		// Fallback: scopeRef is a PB1 note objectId — match by book + sceneIndex
		Integer sceneIdx = node.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		if(sceneIdx == null || book == null) {
			logger.warn("findSceneByNodeRef: direct lookup of {} failed and no sceneIndex/book to fall back on", scopeRef);
			return null;
		}
		logger.debug("findSceneByNodeRef: direct lookup of {} failed; trying sceneIndex={} fallback", scopeRef, sceneIdx);
		Query fallback = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, book);
		fallback.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		fallback.field(OlioFieldNames.FIELD_PB_SCENE_INDEX, sceneIdx);
		fallback.setRequest(requestFields);
		fallback.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, fallback);
	}

	// ─── artifact bytes helper ────────────────────────────────────────────────────

	/**
	 * Read the raw bytes stored in the {@code data.data} record linked by {@code artifact.data}.
	 * Uses {@link ByteModelUtil#getValue} to handle compression/encryption transparently.
	 * Returns {@code null} (logged) if the artifact carries no data FK, or if the data record
	 * cannot be found or decoded.
	 */
	private static byte[] readArtifactBytes(BaseRecord user, BaseRecord artifact) {
		if(artifact == null) return null;
		BaseRecord dataStub = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		if(dataStub == null) return null;
		String dataOid = dataStub.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) return null;
		long orgId = PbGraphUtil.orgId(artifact);
		Query dq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataOid);
		dq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		dq.planMost(false);
		dq.setCache(false);
		BaseRecord dataRecord = IOSystem.getActiveContext().getAccessPoint().find(user, dq);
		if(dataRecord == null) {
			logger.warn("readArtifactBytes: data record {} not found", dataOid);
			return null;
		}
		try {
			return ByteModelUtil.getValue(dataRecord);
		}
		catch(FieldException | ValueException e) {
			logger.warn("readArtifactBytes: failed to decode bytes from data record {}: {}", dataOid, e.getMessage());
			return null;
		}
	}
}
