package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PbStageCancelledException;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil.SceneGraph;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobLimitException;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.thread.AsyncJobWork;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Asynchronous, durable render runs (V3 §21, P1-6): plan the stages that bring a set of terminal nodes up
 * to date, open one {@code olio.pb.run} for them, and execute the stages on an {@link AsyncJobRegistry}
 * worker so the HTTP request returns a {@code 202 {jobId, runObjectId}} and the render continues after
 * the client leaves the page.
 * <p>
 * <b>Planning (submit, on the HTTP thread).</b> {@code terminals} plus every transitive producer
 * ({@link PbGraphUtil#upstreamOf}) is the candidate set; a fresh drift scan ({@link PbStaleUtil#scan}
 * with {@code force}) supplies every node's effective status - STALE when the scan lists it as drifted,
 * else the recomputed status. A node is <b>executed</b> when it is executable
 * ({@link PbNodeExecutor#isExecutable(BaseRecord)}), not pinned, and PENDING / READY / STALE / FAILED
 * (a render request is the retry of a failed node; SKIPPED is a decision and stays). DONE and
 * DONE_UNVERIFIED nodes are reused, pinned nodes are treated as DONE whatever their status, and a node
 * whose type has no executor (SOURCE_TEXT, CHARACTER, ...) is a source. A RUNNING node is a 409 - either
 * another run in this JVM holds it ({@link PbNodeInflight}) or a JVM died with it RUNNING, which only the
 * restart sweep (P1-7) repairs. An empty execution set is a 409 too: there is nothing to render.
 * <p>
 * <b>What the run records.</b> {@code requestedNodeIds} is the execution set, in topological order
 * (producers before consumers; ordinal then id breaks ties) - "the nodes this run was asked to execute"
 * literally, which is also what a resume re-plans from. The run is written PENDING, the nodes are
 * claimed under its objectId, and the job is submitted; a claim conflict or a refused submission closes
 * that run FAILED with the reason before the 409/429/500 is thrown, so no PENDING row is ever left behind
 * by a request that was turned away. Only an accepted run becomes {@code workflow.lastRun} - the pointer
 * is written after the submit, so a refused run never displaces the one in flight; if that write fails
 * the job already owns the run, so the result carries {@code lastRunUpdated:false} rather than a 500.
 * <p>
 * <b>Execution (the worker).</b> {@link PbGraphUtil#markRunning} first - a run that cannot be stamped
 * RUNNING is closed FAILED and nothing is rendered. Then, per stage in plan order: the cancel flag is
 * checked; a node whose planned producer failed earlier in this run is stamped FAILED
 * {@code "blocked by failed producer <handle>"} without executing; otherwise the node is re-read (a pin
 * applied since submit skips it), stamped RUNNING, an SD-backed stage takes the backend's
 * {@link PbRenderLanes} permit (waiting observes cancel), and
 * {@link PbNodeExecutor#executeStage} runs on a graph opened with
 * {@link PbPipelineUtil#openNodeGraph(BaseRecord, BaseRecord, BaseRecord, BaseRecord, BaseRecord, SummarizeProgress)}
 * so the executor sees this run and the job's own cancel token. A successful stage is auto-approved
 * ({@link PbNodeExecutor#applyAutoApprove}, §10 rule 5 per stage - deviation §6.C item 44) inside the
 * claim. A failed stage is FAILED with {@code lastError} and the loop continues: its consumers in the
 * plan are the ones blocked, nothing else is. The run closes COMPLETED only when every planned node is
 * DONE / DONE_UNVERIFIED (or skipped as pinned), else FAILED naming the failed handles.
 * <p>
 * <b>Cancel.</b> {@link PbRunCancelRegistry#cancel} (the book-gated {@code /run/{id}/cancel} route) and
 * {@code AsyncJobRegistry.cancel} (the submitter's {@code /rest/job/{id}/cancel}) flip the same
 * {@link SummarizeProgress} flag. Still QUEUED: the registry invokes {@link #onCancelledBeforeStart} and
 * the run is closed CANCELLED from there. Between stages: the pre-stage check stops the loop. Inside an SD
 * call: Phase 1 has no Swarm interrupt (design §21, dated edit 2026-10-11), so the call runs to completion,
 * the executor discards its bytes and throws {@link PbStageCancelledException}, and the node is returned to
 * the status {@link PbGraphUtil#recomputeStatus} computes from the handle read before the RUNNING stamp.
 * Inside an LLM stream: the registry's cancel scope aborts the stream, the stage throws, and because the
 * flag is up the worker treats it as a cancelled stage, not a failed one. Completed stages keep their
 * artifacts and selections; a cancelled attempt never changes {@code selected}. The worker returns
 * normally after closing the run CANCELLED - a throw would make the job FAILED while the run says
 * CANCELLED.
 * <p>
 * <b>Statics and bounds (architect ruling Q5).</b> Lane permit and node claim are released in
 * {@code finally}; the lane wait observes cancel; the permit count is boot-pinned; and all of it is
 * per-JVM - the database ({@code run.runStatus} / {@code node.nodeStatus} RUNNING) is the only cross-JVM
 * signal, which is why a RUNNING node is refused at submit until the restart sweep clears it.
 */
public final class PbRenderJobUtil {

	private static final Logger logger = LogManager.getLogger(PbRenderJobUtil.class);

	/** Job kind for a single scene (one terminal: the scene's node). */
	public static final String KIND_RENDER = "pb.render";
	/** Job kind for the whole book (every scene node a terminal). */
	public static final String KIND_RENDER_ALL = "pb.renderAll";
	/** Job kind for the resume of an INTERRUPTED run (its {@code requestedNodeIds} are the terminals). */
	public static final String KIND_RESUME = "pb.resume";
	/** Job kind for the whole-book compose pass (V3 §9.1), run by {@link PbComposeJobUtil}; keyed by the book. */
	public static final String KIND_COMPOSE = "pb.compose";
	/** Job kind for the whole-book location pass alone (V3 §7.1), run by {@link PbComposeJobUtil}; keyed by the book. */
	public static final String KIND_PROPOSE_LOCATIONS = "pb.proposeLocations";

	private static final Set<PbNodeStatusEnumType> EXECUTE_WHEN = Collections.unmodifiableSet(new HashSet<>(List.of(
		PbNodeStatusEnumType.PENDING, PbNodeStatusEnumType.READY, PbNodeStatusEnumType.STALE,
		PbNodeStatusEnumType.FAILED, PbNodeStatusEnumType.UNKNOWN)));

	private PbRenderJobUtil() {
	}

	// ─────────────────────────────── submit ───────────────────────────────

	/**
	 * Plan, open the run, claim the nodes and submit the job.
	 * <p>
	 * The caller has already authorized the book for update and resolved {@code terminals} inside its
	 * workflow ({@code PbServiceFacade}); this method trusts those records and never re-authorizes.
	 *
	 * @param terminals node ids (of {@code workflow}) the caller wants current; their upstream closure is
	 *        the plan
	 * @param sdApiType {@code SDAPIEnumType} name the transport resolved; null/blank with an SD-backed
	 *        stage in the plan is a 500 before anything is written
	 * @param kind {@link #KIND_RENDER} or {@link #KIND_RENDER_ALL}
	 * @return {@code jobId}, {@code jobStatus}, {@code runObjectId}, {@code runStatus} (PENDING),
	 *         {@code plannedNodeCount}, {@code planned} (handles in execution order),
	 *         {@code reusedNodeCount}, {@code pinnedNodeCount}
	 * @throws PictureBookException 400 missing input; 409 nothing to render / a planned node RUNNING;
	 *         429 the principal's job cap; 500 no SD backend or a write failed
	 */
	public static Map<String, Object> submit(BaseRecord user, BaseRecord book, BaseRecord workflow,
			Collection<Long> terminals, String sdApiType, String sdServer, String kind) {
		return submit(user, book, workflow, terminals, sdApiType, sdServer, kind, false);
	}

	/**
	 * {@link #submit(BaseRecord, BaseRecord, BaseRecord, Collection, String, String, String)} with an
	 * explicit re-roll. With {@code force} the <b>terminals</b> are executed even when their effective
	 * status is current (DONE / DONE_UNVERIFIED); their upstream closure is still planned from the drift
	 * scan, and a pinned terminal is still skipped (the caller refuses pinned targets before submitting).
	 * V3 P1-9: a ChapBook page re-render is a re-roll of a current node, and the planner must say so
	 * explicitly rather than fabricate staleness with a STALE pre-mark (§10 rule 3).
	 */
	public static Map<String, Object> submit(BaseRecord user, BaseRecord book, BaseRecord workflow,
			Collection<Long> terminals, String sdApiType, String sdServer, String kind, boolean force) {
		return submit(user, book, workflow, terminals, PbNodeExecutor.toSdUtil(sdApiType, sdServer), sdServer, kind, force);
	}

	/**
	 * {@link #submit(BaseRecord, BaseRecord, BaseRecord, Collection, String, String, String)} with the SD
	 * client already built - the seam a test uses to observe or fail the backend call (the
	 * {@code executeNodeRun(..., SDUtil)} idiom of P1-3). {@code sdServer} still keys the render lane.
	 */
	static Map<String, Object> submit(BaseRecord user, BaseRecord book, BaseRecord workflow,
			Collection<Long> terminals, SDUtil sdu, String sdServer, String kind) {
		return submit(user, book, workflow, terminals, sdu, sdServer, kind, false);
	}

	static Map<String, Object> submit(BaseRecord user, BaseRecord book, BaseRecord workflow,
			Collection<Long> terminals, SDUtil sdu, String sdServer, String kind, boolean force) {
		if(user == null || book == null || workflow == null) {
			throw new PictureBookException(400, "A user, book and workflow are required");
		}
		if(terminals == null || terminals.isEmpty()) {
			throw new PictureBookException(400, "At least one target node is required");
		}
		String useKind = (kind != null && !kind.isBlank() ? kind : KIND_RENDER);
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null || slug.isBlank()) {
			throw new PictureBookException(400, "Book " + book.get(FieldNames.FIELD_OBJECT_ID) + " has no slug");
		}
		/// Refuse before the drift scan and before a run row exists; the registry re-checks under its own
		/// lock at submit, so a cap reached between here and there still lands in the catch below.
		int active = AsyncJobRegistry.activeCount(user);
		if(active >= AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL) {
			throw new PictureBookException(429, "Principal already has " + active + " of "
				+ AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL + " jobs queued or running");
		}

		Plan plan = plan(user, book, workflow, terminals, force);
		if(plan.execute.isEmpty()) {
			if(KIND_RESUME.equals(useKind)) {
				throw new PictureBookException(409, "Nothing to resume: every node this run requested is now current ("
					+ plan.reused + " reused, " + plan.pinned + " pinned)");
			}
			throw new PictureBookException(409, "Nothing to render: every node of " + plan.scopeLabel
				+ " is current (" + plan.reused + " reused, " + plan.pinned + " pinned)");
		}
		if(sdu == null && plan.needsSd) {
			throw new PictureBookException(500, "SD server not configured");
		}
		/// Early refusal for a node another run of this JVM holds. The authoritative claim comes after the
		/// run row exists (it is keyed by the run's objectId); this pre-check keeps the common refusal from
		/// writing a run at all. The narrow race between the two is closed by the claim below.
		for(BaseRecord n : plan.execute) {
			String holder = PbNodeInflight.runOf(idOf(n));
			if(holder != null) {
				throw new PictureBookException(409, "Node " + handleOf(n) + " is being rendered by run " + holder);
			}
		}

		List<Long> executeIds = new ArrayList<>();
		for(BaseRecord n : plan.execute) {
			executeIds.add(Long.valueOf(idOf(n)));
		}
		BaseRecord run = PbGraphUtil.startRun(user, workflow, PbBookUtil.workflowGroupPath(slug), executeIds);
		if(run == null) {
			throw new PictureBookException(500, "Failed to open a run for " + plan.scopeLabel);
		}
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);

		Long conflict = PbNodeInflight.tryClaim(executeIds, runOid);
		if(conflict != null) {
			String holder = PbNodeInflight.runOf(conflict.longValue());
			String msg = "Node " + handleOf(plan.byId.get(conflict)) + " is being rendered by run " + holder;
			PbGraphUtil.completeRun(user, run, PbRunStatusEnumType.FAILED, 0, 0, msg);
			throw new PictureBookException(409, msg);
		}

		Work work = new Work(user, book, workflow, run, plan, sdu, sdServer);
		AsyncJob job;
		try {
			job = AsyncJobRegistry.submit(user, useKind, runOid, work);
		}
		catch(AsyncJobLimitException e) {
			PbNodeInflight.release(executeIds, runOid);
			PbGraphUtil.completeRun(user, run, PbRunStatusEnumType.FAILED, 0, 0, "Refused: " + e.getMessage());
			throw new PictureBookException(429, e.getMessage());
		}
		catch(RuntimeException e) {
			PbNodeInflight.release(executeIds, runOid);
			PbGraphUtil.completeRun(user, run, PbRunStatusEnumType.FAILED, 0, 0, "Submit failed: " + e.getMessage());
			throw e;
		}
		if(job == null) {
			PbNodeInflight.release(executeIds, runOid);
			PbGraphUtil.completeRun(user, run, PbRunStatusEnumType.FAILED, 0, 0, "The principal could not be keyed for an async job");
			throw new PictureBookException(500, "Render job could not be submitted for this principal");
		}
		/// Register on this thread, with the job's own token, then re-check: a job that already finished
		/// (or was cancelled while QUEUED and swept through onCancelledBeforeStart) must not leave an entry.
		PbRunCancelRegistry.register(runOid, job.getProgress(), job.getJobId());
		if(job.isTerminal()) {
			PbRunCancelRegistry.unregister(runOid, job.getProgress());
		}
		/// Only an accepted run becomes workflow.lastRun, so a refused one never displaces the run in
		/// flight. The job owns the run from here on: a pointer that cannot be written is reported on the
		/// result, never thrown, and the run stays reachable by its objectId.
		boolean lastRunUpdated = false;
		try {
			lastRunUpdated = PbGraphUtil.persistLastRun(user, workflow, run);
		}
		catch(RuntimeException e) {
			logger.error("workflow.lastRun could not be updated for run " + runOid, e);
		}
		if(!lastRunUpdated) {
			logger.error("Run " + runOid + " was submitted but workflow.lastRun could not be updated");
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("jobId", job.getJobId());
		out.put("jobStatus", job.getStatus().name().toLowerCase());
		out.put("kind", useKind);
		out.put("runObjectId", runOid);
		out.put("runStatus", run.get(OlioFieldNames.FIELD_PB_RUN_STATUS));
		out.put("plannedNodeCount", Integer.valueOf(plan.execute.size()));
		out.put("planned", plan.handles());
		out.put("reusedNodeCount", Integer.valueOf(plan.reused));
		out.put("pinnedNodeCount", Integer.valueOf(plan.pinned));
		out.put("lastRunUpdated", Boolean.valueOf(lastRunUpdated));
		return out;
	}

	// ─────────────────────────────── resume ───────────────────────────────

	/**
	 * Resume an INTERRUPTED run (design §21, P1-7): a <b>new</b> run is submitted whose terminals are the
	 * interrupted run's {@code requestedNodeIds}, so {@link #submit} re-plans from a fresh drift scan and
	 * every node that finished before the interruption (DONE, with its artifact) is reused rather than
	 * re-rendered. The interrupted run is not reopened or rewritten - it stays INTERRUPTED as the record
	 * of what happened; the result names it in {@code resumedFromRunObjectId}, and the new run becomes
	 * {@code workflow.lastRun}.
	 * <p>
	 * Refusals: 409 unless the run is INTERRUPTED (a COMPLETED, FAILED, CANCELLED or in-flight run has
	 * nothing to resume); 409 when it carries no execution set (a run opened before P1-6 recorded
	 * {@code requestedNodeIds}); and {@link #submit}'s own 409 when every requested node is already
	 * current - resuming twice is the second one.
	 *
	 * @param interruptedRun the run as read through {@link PbGraphUtil#readRun} (its {@code runStatus} and
	 *        {@code requestedNodeIds} decide)
	 */
	public static Map<String, Object> resume(BaseRecord user, BaseRecord book, BaseRecord workflow,
			BaseRecord interruptedRun, String sdApiType, String sdServer) {
		return resume(user, book, workflow, interruptedRun, PbNodeExecutor.toSdUtil(sdApiType, sdServer), sdServer);
	}

	/** {@link #resume(BaseRecord, BaseRecord, BaseRecord, BaseRecord, String, String)} with the SD client built. */
	static Map<String, Object> resume(BaseRecord user, BaseRecord book, BaseRecord workflow,
			BaseRecord interruptedRun, SDUtil sdu, String sdServer) {
		if(interruptedRun == null) {
			throw new PictureBookException(400, "A run to resume is required");
		}
		String name = interruptedRun.get(FieldNames.FIELD_NAME);
		PbRunStatusEnumType status = interruptedRun.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS);
		if(status != PbRunStatusEnumType.INTERRUPTED) {
			throw new PictureBookException(409, "Run " + name + " is " + status + "; only an INTERRUPTED run can be resumed");
		}
		List<Long> requested = requestedNodeIdsOf(interruptedRun);
		if(requested.isEmpty()) {
			throw new PictureBookException(409, "Run " + name + " has no execution set to resume (opened before P1-6)");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("resumedFromRunObjectId", interruptedRun.get(FieldNames.FIELD_OBJECT_ID));
		out.putAll(submit(user, book, workflow, requested, sdu, sdServer, KIND_RESUME));
		return out;
	}

	/** {@code run.requestedNodeIds} as longs; a {@code long} list field reads back as numbers of some width. */
	public static List<Long> requestedNodeIdsOf(BaseRecord run) {
		List<Long> out = new ArrayList<>();
		if(run == null || !run.hasField(OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS)) {
			return out;
		}
		List<? extends Number> raw = run.get(OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS);
		if(raw == null) {
			return out;
		}
		for(Number n : raw) {
			if(n != null && n.longValue() > 0L) {
				out.add(Long.valueOf(n.longValue()));
			}
		}
		return out;
	}

	// ─────────────────────────────── await ───────────────────────────────

	/** Poll interval of {@link #awaitTerminal}; the cadence a client polling {@code GET /rest/job/{id}} would use. */
	public static final long AWAIT_POLL_MS = 250L;

	/**
	 * Poll the registry, as a client polling {@code GET /rest/job/{id}} would, until the job is terminal
	 * or {@code maxMs} elapses. Returns the job when it reached a terminal state within the bound, else
	 * {@code null} - the job keeps running and the caller must hand its identifiers back rather than
	 * fail (V3 P1-9: the synchronous ChapBook page render is a bounded wait over the same
	 * {@code pb.render} job the async path uses). Never throws on a missing job: a job that is no longer
	 * visible to its owner is reported as null too, and the caller reads the node instead.
	 * <p>
	 * Must only be called from a request thread, never from inside a registry job: a job that awaits
	 * another job holds one of the pool's threads while waiting for the other.
	 */
	public static AsyncJob awaitTerminal(BaseRecord user, String jobId, long maxMs) {
		if(user == null || jobId == null) {
			return null;
		}
		long deadline = System.currentTimeMillis() + Math.max(0L, maxMs);
		while(true) {
			AsyncJob job = AsyncJobRegistry.get(user, jobId);
			if(job == null) {
				return null;
			}
			if(job.isTerminal()) {
				return job;
			}
			if(System.currentTimeMillis() >= deadline) {
				return null;
			}
			try {
				Thread.sleep(AWAIT_POLL_MS);
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}

	// ─────────────────────────────── plan ───────────────────────────────

	/** The execution set and what was left out, from one scan. */
	static final class Plan {
		/** Nodes to execute, producers before consumers. */
		final List<BaseRecord> execute = new ArrayList<>();
		/** Every node of the workflow by id (the records the stages start from). */
		final Map<Long, BaseRecord> byId;
		/** {@code consumerId -> [producerId]} over the whole workflow. */
		final Map<Long, List<Long>> producers;
		int reused = 0;
		int pinned = 0;
		boolean needsSd = false;
		String scopeLabel = "the plan";

		Plan(Map<Long, BaseRecord> byId, Map<Long, List<Long>> producers) {
			this.byId = byId;
			this.producers = producers;
		}

		List<String> handles() {
			List<String> out = new ArrayList<>();
			for(BaseRecord n : execute) {
				out.add(handleOf(n));
			}
			return out;
		}
	}

	/**
	 * Candidate set → effective status → execution set, topologically ordered. Package-private so a test
	 * can assert the plan without submitting.
	 */
	static Plan plan(BaseRecord user, BaseRecord book, BaseRecord workflow, Collection<Long> terminals) {
		return plan(user, book, workflow, terminals, false);
	}

	/** {@link #plan(BaseRecord, BaseRecord, BaseRecord, Collection)}; with {@code force} a current, unpinned terminal is executed anyway. */
	static Plan plan(BaseRecord user, BaseRecord book, BaseRecord workflow, Collection<Long> terminals, boolean force) {
		Map<Long, BaseRecord> byId = PbGraphUtil.nodesById(user, workflow);
		List<BaseRecord> bindings = PbGraphUtil.listWorkflowBindings(user, workflow);
		Map<Long, List<Long>> producers = PbGraphUtil.producersOf(bindings);
		Set<Long> candidates = PbGraphUtil.upstreamOf(bindings, terminals);
		Plan plan = new Plan(byId, producers);
		plan.scopeLabel = (terminals.size() == 1 ? "this scene" : "this book");

		PbStaleUtil.Scan scan = PbStaleUtil.scan(user, book, workflow, true);
		Set<Long> executeIds = new LinkedHashSet<>();
		for(Long id : candidates) {
			BaseRecord n = byId.get(id);
			if(n == null) {
				/// A binding that points outside the workflow (or at a deleted node): nothing to run.
				continue;
			}
			if(!PbNodeExecutor.isExecutable(n)) {
				continue;
			}
			Boolean pinned = n.get(OlioFieldNames.FIELD_PB_PINNED);
			if(pinned != null && pinned.booleanValue()) {
				plan.pinned++;
				continue;
			}
			PbNodeStatusEnumType effective = effectiveStatus(scan, n);
			if(effective == PbNodeStatusEnumType.RUNNING) {
				String holder = PbNodeInflight.runOf(id.longValue());
				throw new PictureBookException(409, "Node " + handleOf(n) + " is RUNNING"
					+ (holder != null ? " in run " + holder : " - if no run is in flight, restart recovery has not yet reconciled it"));
			}
			if(!EXECUTE_WHEN.contains(effective) && !(force && terminals.contains(id))) {
				plan.reused++;
				continue;
			}
			executeIds.add(id);
		}

		for(BaseRecord n : topological(executeIds, byId, producers)) {
			plan.execute.add(n);
			PbNodeTypeEnumType t = n.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
			if(t != null && PbNodeExecutor.SD_BACKED_TYPES.contains(t)) {
				plan.needsSd = true;
			}
		}
		return plan;
	}

	/**
	 * A stored RUNNING stamp wins outright: {@code recomputeStatus} maps a RUNNING node with no input hash to
	 * READY/PENDING, which would let a node orphaned by a dead JVM be re-planned before the restart sweep
	 * reconciles it. Otherwise STALE when the drift scan lists the node (a ref-drifted node's recomputed
	 * status can still read DONE), else the scan's recomputed status, else the stored status.
	 */
	static PbNodeStatusEnumType effectiveStatus(PbStaleUtil.Scan scan, BaseRecord node) {
		PbNodeStatusEnumType stored = node.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
		if(stored == PbNodeStatusEnumType.RUNNING) {
			return stored;
		}
		Long id = node.get(FieldNames.FIELD_ID);
		if(scan != null && id != null) {
			if(scan.entries.containsKey(id)) {
				return PbNodeStatusEnumType.STALE;
			}
			PbNodeStatusEnumType s = scan.statuses.get(id);
			if(s != null) {
				return s;
			}
		}
		return (stored != null ? stored : PbNodeStatusEnumType.UNKNOWN);
	}

	/**
	 * Producers before consumers, considering only edges between members of {@code ids}; ordinal then id
	 * breaks ties. A cycle (which the graph builder never writes) would leave nodes unplaced - they are
	 * appended in ordinal order so the plan is still complete and the failure, if any, is the executor's.
	 */
	private static List<BaseRecord> topological(Set<Long> ids, Map<Long, BaseRecord> byId, Map<Long, List<Long>> producers) {
		List<BaseRecord> remaining = new ArrayList<>();
		for(Long id : ids) {
			remaining.add(byId.get(id));
		}
		remaining.sort((a, b) -> {
			int oa = ordinalOf(a);
			int ob = ordinalOf(b);
			return (oa != ob ? Integer.compare(oa, ob) : Long.compare(idOf(a), idOf(b)));
		});
		List<BaseRecord> out = new ArrayList<>();
		Set<Long> placed = new HashSet<>();
		boolean progress = true;
		while(!remaining.isEmpty() && progress) {
			progress = false;
			for(int i = 0; i < remaining.size(); i++) {
				BaseRecord n = remaining.get(i);
				boolean ready = true;
				for(Long p : producers.getOrDefault(Long.valueOf(idOf(n)), Collections.emptyList())) {
					if(ids.contains(p) && !placed.contains(p)) {
						ready = false;
						break;
					}
				}
				if(ready) {
					out.add(n);
					placed.add(Long.valueOf(idOf(n)));
					remaining.remove(i);
					progress = true;
					break;
				}
			}
		}
		if(!remaining.isEmpty()) {
			logger.warn("Render plan contains a cycle among " + remaining.size() + " node(s); appending them in ordinal order");
			out.addAll(remaining);
		}
		return out;
	}

	// ─────────────────────────────── the worker ───────────────────────────────

	/** One run's stages, executed on the registry's worker thread. */
	static final class Work implements AsyncJobWork {
		private final BaseRecord user;
		private final BaseRecord book;
		private final BaseRecord workflow;
		private final BaseRecord run;
		private final Plan plan;
		private final SDUtil sdu;
		private final String sdServer;
		private final String runOid;
		private final List<Long> claimed;

		Work(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord run, Plan plan, SDUtil sdu, String sdServer) {
			this.user = user;
			this.book = book;
			this.workflow = workflow;
			this.run = run;
			this.plan = plan;
			this.sdu = sdu;
			this.sdServer = sdServer;
			this.runOid = run.get(FieldNames.FIELD_OBJECT_ID);
			this.claimed = new ArrayList<>();
			for(BaseRecord n : plan.execute) {
				claimed.add(Long.valueOf(idOf(n)));
			}
		}

		@Override
		public void onCancelledBeforeStart(AsyncJob job) {
			try {
				if(!PbGraphUtil.completeRun(user, run, PbRunStatusEnumType.CANCELLED, 0, 0, "Cancelled before starting")) {
					logger.error("Run " + runOid + " was cancelled before starting but could not be closed CANCELLED");
				}
			}
			finally {
				PbNodeInflight.release(claimed, runOid);
				PbRunCancelRegistry.unregister(runOid, job.getProgress());
				PbGraphUtil.invalidateScan(workflow);
			}
		}

		@Override
		public String run(AsyncJob job) {
			SummarizeProgress progress = job.getProgress();
			long started = System.currentTimeMillis();
			int executed = 0;
			int failed = 0;
			int skipped = 0;
			boolean cancelled = false;
			boolean interrupted = false;
			List<String> failedHandles = new ArrayList<>();
			Set<Long> failedThisRun = new HashSet<>();
			List<Map<String, Object>> outcomes = new ArrayList<>();
			PbRunStatusEnumType status = PbRunStatusEnumType.FAILED;
			String error = null;
			try {
				if(!PbGraphUtil.markRunning(user, run)) {
					error = "Run " + run.get(FieldNames.FIELD_NAME) + " could not be stamped RUNNING; nothing was rendered";
					throw new PictureBookException(500, error);
				}
				progress.setTotal(plan.execute.size());
				progress.setCurrent(0);

				for(BaseRecord planned : plan.execute) {
					if(shuttingDown()) {
						interrupted = true;
						break;
					}
					if(progress.isCancelled()) {
						cancelled = true;
						break;
					}
					long nodeId = idOf(planned);
					String handle = handleOf(planned);
					Map<String, Object> outcome = new LinkedHashMap<>();
					outcome.put("nodeObjectId", planned.get(FieldNames.FIELD_OBJECT_ID));
					outcome.put("handle", handle);
					outcomes.add(outcome);

					String blockedBy = blockedBy(nodeId, failedThisRun);
					if(blockedBy != null) {
						String msg = "blocked by failed producer " + blockedBy;
						PbPipelineUtil.failNode(user, planned, msg);
						failed++;
						failedThisRun.add(Long.valueOf(nodeId));
						failedHandles.add(handle);
						outcome.put("status", PbNodeStatusEnumType.FAILED.name());
						outcome.put("error", msg);
						continue;
					}

					BaseRecord node = PbGraphUtil.readNode(user, (String) planned.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(workflow));
					if(node == null) {
						String msg = "node is no longer readable";
						PbPipelineUtil.failNode(user, planned, msg);
						failed++;
						failedThisRun.add(Long.valueOf(nodeId));
						failedHandles.add(handle);
						outcome.put("status", PbNodeStatusEnumType.FAILED.name());
						outcome.put("error", msg);
						continue;
					}
					Boolean pinned = node.get(OlioFieldNames.FIELD_PB_PINNED);
					if(pinned != null && pinned.booleanValue()) {
						skipped++;
						outcome.put("status", "SKIPPED_PINNED");
						progress.incrementCurrent();
						continue;
					}

					progress.setPhase(handle);
					/// Verifier NOTE 7 (P1-3): the stamp result is asserted. A node that cannot be written
					/// RUNNING is a node this principal cannot write at all - abort the run rather than render
					/// into a status the restart sweep would never see.
					if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.RUNNING)) {
						error = "Node " + handle + " could not be stamped RUNNING; the run was aborted";
						throw new PictureBookException(500, error);
					}

					PbNodeTypeEnumType nodeType = node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
					boolean sdBacked = (nodeType != null && PbNodeExecutor.SD_BACKED_TYPES.contains(nodeType));
					boolean laneHeld = false;
					long stageStarted = System.currentTimeMillis();
					try {
						if(sdBacked) {
							laneHeld = PbRenderLanes.acquire(sdServer, progress);
							if(!laneHeld) {
								/// The wait was abandoned: nothing ran. Shutdown and thread interrupt outrank
								/// cancel - a run abandoned by the container is INTERRUPTED, not the user's cancel.
								if(shuttingDown()) {
									revertAfterInterrupt(node, outcome, "interrupted by shutdown while waiting for the render lane");
									interrupted = true;
									break;
								}
								revertAfterCancel(node, outcome, "cancelled while waiting for the render lane");
								cancelled = true;
								break;
							}
						}
						SceneGraph g = PbPipelineUtil.openNodeGraph(user, book, workflow, node, run, progress);
						Map<String, Object> out = PbNodeExecutor.executeStage(g, node, sdu);
						PbNodeExecutor.applyAutoApprove(g, out);
						executed++;
						outcome.put("status", String.valueOf(out.get("nodeStatus") != null ? out.get("nodeStatus") : "DONE"));
						outcome.put("artifactObjectId", out.get("artifactObjectId"));
						outcome.put(PbNodeExecutor.KEY_AUTO_APPROVED, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
						if(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED) != null) {
							outcome.put(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED, out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
						}
						if(out.get("reviewStatus") != null) {
							outcome.put("reviewStatus", out.get("reviewStatus"));
						}
						if(out.get("degradations") != null) {
							outcome.put("degradations", out.get("degradations"));
						}
						if(out.get(PbNodeExecutor.KEY_CONFIG_NOT_APPLIED) != null) {
							outcome.put(PbNodeExecutor.KEY_CONFIG_NOT_APPLIED, out.get(PbNodeExecutor.KEY_CONFIG_NOT_APPLIED));
						}
					}
					catch(PbStageCancelledException e) {
						revertAfterCancel(node, outcome, e.getMessage());
						cancelled = true;
						break;
					}
					catch(RuntimeException e) {
						String msg = (e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName());
						if(shuttingDown()) {
							/// The stage failed while the container was stopping (a closing connection, an aborted
							/// stream): the node is returned to READY for the resume, and the run says INTERRUPTED,
							/// not FAILED. Only observable when the stage throws before the IO context is gone.
							revertAfterInterrupt(node, outcome, "interrupted by shutdown: " + msg);
							interrupted = true;
							break;
						}
						if(progress.isCancelled()) {
							/// An aborted LLM stream (the registry's cancel scope) surfaces as the stage's own
							/// exception. The flag says why; this is a cancel, not a failure.
							revertAfterCancel(node, outcome, "cancelled: " + msg);
							cancelled = true;
							break;
						}
						/// executeStage stamps FAILED for a dispatch failure; a gate refusal (501/500) throws
						/// before that, so stamp here as well - the second write, when it happens, is idempotent.
						PbPipelineUtil.failNode(user, node, msg);
						failed++;
						failedThisRun.add(Long.valueOf(nodeId));
						failedHandles.add(handle);
						outcome.put("status", PbNodeStatusEnumType.FAILED.name());
						outcome.put("error", msg);
						logger.warn("Render run " + runOid + " stage " + handle + " failed: " + msg);
					}
					finally {
						if(laneHeld) {
							PbRenderLanes.release(sdServer);
						}
						outcome.put("durationMs", Long.valueOf(System.currentTimeMillis() - stageStarted));
					}
					if(!cancelled && !interrupted) {
						progress.incrementCurrent();
					}
				}

				if(interrupted) {
					status = PbRunStatusEnumType.INTERRUPTED;
					error = "Interrupted by shutdown after " + executed + " of " + plan.execute.size() + " stage(s)";
				}
				else if(cancelled) {
					status = PbRunStatusEnumType.CANCELLED;
					error = "Cancelled after " + executed + " of " + plan.execute.size() + " stage(s)";
				}
				else if(failed > 0) {
					status = PbRunStatusEnumType.FAILED;
					error = failed + " node(s) failed: " + String.join(", ", failedHandles);
				}
				else {
					status = PbRunStatusEnumType.COMPLETED;
					error = null;
				}
			}
			catch(RuntimeException e) {
				String msg = (e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName());
				if(shuttingDown()) {
					/// A read or stamp that failed because the container is stopping. No node is RUNNING from
					/// this run at this point (the stamp is the last thing before the stage's own try).
					status = PbRunStatusEnumType.INTERRUPTED;
					error = "Interrupted by shutdown after " + executed + " of " + plan.execute.size() + " stage(s): " + msg;
					logger.warn("Render run " + runOid + " interrupted by shutdown: " + msg);
				}
				else {
					status = PbRunStatusEnumType.FAILED;
					if(error == null) {
						error = msg;
					}
					logger.error("Render run " + runOid + " aborted: " + error, e);
				}
			}
			finally {
				try {
					if(!PbGraphUtil.completeRun(user, run, status, executed, failed, error)) {
						logger.error("Render run " + runOid + " finished " + status + " but the run row could not be closed");
					}
				}
				finally {
					PbNodeInflight.release(claimed, runOid);
					PbRunCancelRegistry.unregister(runOid, progress);
					PbGraphUtil.invalidateScan(workflow);
				}
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("runObjectId", runOid);
			result.put("runStatus", status.name());
			result.put("executedNodeCount", Integer.valueOf(executed));
			result.put("failedNodeCount", Integer.valueOf(failed));
			result.put("skippedNodeCount", Integer.valueOf(skipped));
			result.put("plannedNodeCount", Integer.valueOf(plan.execute.size()));
			if(error != null) {
				result.put("error", error);
			}
			result.put("nodes", outcomes);
			result.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
			return JSONUtil.exportObject(result);
		}

		/** The handle of a planned producer of {@code nodeId} that failed in this run, or null. */
		private String blockedBy(long nodeId, Set<Long> failedThisRun) {
			for(Long p : plan.producers.getOrDefault(Long.valueOf(nodeId), Collections.emptyList())) {
				if(failedThisRun.contains(p)) {
					BaseRecord producer = plan.byId.get(p);
					return (producer != null ? handleOf(producer) : String.valueOf(p));
				}
			}
			return null;
		}

		/**
		 * Return a node the worker stamped RUNNING, and then did not finish, to the status its pre-stamp
		 * handle computes (architect finding 4: {@code recomputeStatus} is compute-only). A write that is
		 * refused is logged; the restart sweep reconciles a RUNNING node behind a non-RUNNING run.
		 */
		private void revertAfterCancel(BaseRecord preStampNode, Map<String, Object> outcome, String why) {
			PbNodeStatusEnumType back = PbGraphUtil.recomputeStatus(user, preStampNode, book);
			if(back == null || back == PbNodeStatusEnumType.UNKNOWN) {
				back = PbNodeStatusEnumType.READY;
			}
			if(!PbGraphUtil.persistStatus(user, preStampNode, back)) {
				logger.error("Cancelled stage " + handleOf(preStampNode) + " of run " + runOid
					+ " could not be returned to " + back + "; it reads RUNNING until restart recovery");
			}
			outcome.put("status", "CANCELLED");
			outcome.put("revertedTo", back.name());
			outcome.put("error", why);
		}

		/**
		 * Return a node the worker stamped RUNNING, and then abandoned because the container is stopping,
		 * to READY - the same state the boot sweep ({@link PbRecoveryUtil}) gives a RUNNING node it finds
		 * behind a dead run, so a resume re-plans it either way. Not {@code recomputeStatus}: the point of
		 * an interrupt is that nothing about the node's inputs changed, only that its stage did not finish.
		 */
		private void revertAfterInterrupt(BaseRecord node, Map<String, Object> outcome, String why) {
			if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.READY)) {
				logger.error("Interrupted stage " + handleOf(node) + " of run " + runOid
					+ " could not be returned to READY; it reads RUNNING until restart recovery");
			}
			outcome.put("status", "INTERRUPTED");
			outcome.put("revertedTo", PbNodeStatusEnumType.READY.name());
			outcome.put("error", why);
		}

		/**
		 * The container has announced its stop ({@link AsyncJobRegistry#beginShutdown()}) or the pool has
		 * interrupted this worker ({@code shutdownNow()}). Checked between stages, after an abandoned lane
		 * wait and when a stage throws; an SD call in progress cannot observe it until it returns.
		 */
		private static boolean shuttingDown() {
			return AsyncJobRegistry.isShuttingDown() || Thread.currentThread().isInterrupted();
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	static long idOf(BaseRecord node) {
		Long id = (node != null ? node.get(FieldNames.FIELD_ID) : null);
		return (id != null ? id.longValue() : 0L);
	}

	static int ordinalOf(BaseRecord node) {
		Integer o = (node != null && node.hasField(OlioFieldNames.FIELD_PB_ORDINAL) ? node.get(OlioFieldNames.FIELD_PB_ORDINAL) : null);
		return (o != null ? o.intValue() : 0);
	}

	static String handleOf(BaseRecord node) {
		if(node == null) {
			return "(unknown)";
		}
		String handle = (node.hasField(OlioFieldNames.FIELD_PB_HANDLE) ? node.get(OlioFieldNames.FIELD_PB_HANDLE) : null);
		if(handle != null && !handle.isBlank()) {
			return handle;
		}
		String name = node.get(FieldNames.FIELD_NAME);
		if(name != null) {
			return name;
		}
		Object oid = node.get(FieldNames.FIELD_OBJECT_ID);
		return (oid != null ? oid.toString() : "(unknown)");
	}
}
