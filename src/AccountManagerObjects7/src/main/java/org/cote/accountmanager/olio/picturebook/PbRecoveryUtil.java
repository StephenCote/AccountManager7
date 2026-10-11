package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.thread.AsyncJobRegistry;

/**
 * Restart recovery for the async render runs (design §21, P1-7): the boot-time sweep that reconciles what
 * a stopped JVM left RUNNING in the database.
 * <p>
 * <b>Boot-only, never REST-reachable.</b> The sweep runs once from the Service7 context listener after
 * the IO context is open and the organizations are provisioned, and from tests against their own
 * workflow. It is not a route and must not become one: it reads without a principal (there is none at
 * boot - the precedent is {@code SetupUtil}'s boot reads and the health/orphan scans) and then writes
 * <i>as each run's owner</i> through {@code AccessPoint}, never as the org admin and never through
 * {@code RecordUtil}; a denied write is counted and logged, never thrown. Exposing it on a request path
 * would let any caller trigger owner-impersonating writes as a side effect - exactly the read-path trap
 * {@code architecture.md} names.
 * <p>
 * <b>What it does.</b> Every {@code olio.pb.run} still {@code RUNNING} or {@code PENDING} belonged to a
 * JVM that is no longer executing it (this one just started; see the single-JVM constraint below), so it
 * is closed {@code INTERRUPTED} with its stored counters preserved and an error text saying why - the
 * run is the record of what happened, and {@code requestedNodeIds} is what a resume re-plans from. Every
 * {@code olio.pb.node} still {@code RUNNING}, <i>whatever its run says</i>, is returned to {@code READY}:
 * a RUNNING node behind a non-RUNNING run is the shape a worker leaves when its revert write was
 * refused, and a node nobody is executing must not stay refused at submit forever. A run or node this
 * JVM is actually executing ({@link PbRunCancelRegistry#peek}, {@link PbNodeInflight#runOf}) is skipped,
 * which is what makes the scoped overload safe to call while jobs are in flight.
 * <p>
 * <b>Exactly one Service7 JVM per database.</b> The sweep treats "RUNNING in the database" as "orphaned",
 * which is true only when no other JVM can be executing it. Two Service7 instances against one database
 * would each sweep the other's live runs at boot. Console7 must not call this.
 * <p>
 * <b>Bound.</b> A worker that is inside a Swarm call when the container stops cannot observe the stop
 * until the call returns, by which time the pool's 5 s grace has elapsed and the IO context is closed;
 * that run stays RUNNING in the database and this sweep is its only recovery. A worker between stages,
 * waiting for a render lane, or whose stage threw during the stop closes its own run INTERRUPTED
 * ({@code PbRenderJobUtil.Work}), and the sweep then finds nothing of it.
 */
public final class PbRecoveryUtil {

	private static final Logger logger = LogManager.getLogger(PbRecoveryUtil.class);

	private PbRecoveryUtil() {
	}

	/** The boot sweep: every workflow, every organization. */
	public static Map<String, Object> sweepInterrupted() {
		return sweepInterrupted(null);
	}

	/**
	 * The sweep scoped to one workflow - what a test calls so it never touches runs of other tests sharing
	 * the database, and what makes assertions by own run id meaningful. The condition is by record
	 * because {@code run.workflow} is a foreign model field (a bare id would silently match nothing).
	 *
	 * @param workflow null sweeps everything
	 * @return a summary: {@code runsInterrupted}, {@code pendingInterrupted}, {@code nodesReset},
	 *         {@code runsSkippedInFlight}, {@code nodesSkippedInFlight}, {@code ownerUnresolved},
	 *         {@code denied} (the owner's write was refused), {@code errored} (the write threw),
	 *         {@code problems} (one line per record that could not be reconciled). Never throws: a sweep
	 *         that fails must not stop the container from booting.
	 */
	public static Map<String, Object> sweepInterrupted(BaseRecord workflow) {
		int runsInterrupted = 0;
		int pendingInterrupted = 0;
		int nodesReset = 0;
		int runsSkippedInFlight = 0;
		int nodesSkippedInFlight = 0;
		int ownerUnresolved = 0;
		int denied = 0;
		int errored = 0;
		List<String> problems = new ArrayList<>();
		long started = System.currentTimeMillis();

		if(AsyncJobRegistry.size() > 0 || PbNodeInflight.size() > 0) {
			logger.warn("PB restart recovery is running with " + AsyncJobRegistry.size() + " job(s) retained and "
				+ PbNodeInflight.size() + " node claim(s) held in this process; those are skipped, not reconciled");
		}

		try {
			for(PbRunStatusEnumType orphanStatus : new PbRunStatusEnumType[] {PbRunStatusEnumType.RUNNING, PbRunStatusEnumType.PENDING}) {
				for(BaseRecord run : rawRuns(orphanStatus, workflow)) {
					String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
					String runName = run.get(FieldNames.FIELD_NAME);
					if(runOid != null && PbRunCancelRegistry.peek(runOid) != null) {
						runsSkippedInFlight++;
						continue;
					}
					BaseRecord owner = ownerOf(run);
					if(owner == null) {
						ownerUnresolved++;
						problems.add("run " + runName + " (" + runOid + "): owner could not be resolved; left " + orphanStatus);
						continue;
					}
					int executed = intOf(run, OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT);
					int failed = intOf(run, OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT);
					boolean resumable = !PbRenderJobUtil.requestedNodeIdsOf(run).isEmpty();
					String text = (orphanStatus == PbRunStatusEnumType.RUNNING
						? "Interrupted: the server stopped while this run was RUNNING"
						: "Interrupted: the server stopped before this run was dispatched")
						+ (resumable ? "; resume to continue" : "");
					boolean ok = false;
					try {
						ok = PbGraphUtil.completeRun(owner, run, PbRunStatusEnumType.INTERRUPTED, executed, failed, text);
					}
					catch(RuntimeException e) {
						errored++;
						problems.add("run " + runName + " (" + runOid + "): " + e.getMessage());
						continue;
					}
					if(!ok) {
						denied++;
						problems.add("run " + runName + " (" + runOid + "): could not be closed INTERRUPTED as its owner");
						continue;
					}
					if(orphanStatus == PbRunStatusEnumType.RUNNING) {
						runsInterrupted++;
					}
					else {
						pendingInterrupted++;
					}
				}
			}

			for(BaseRecord node : rawRunningNodes(workflow)) {
				Long nodeId = node.get(FieldNames.FIELD_ID);
				String handle = PbRenderJobUtil.handleOf(node);
				if(nodeId != null && PbNodeInflight.runOf(nodeId.longValue()) != null) {
					nodesSkippedInFlight++;
					continue;
				}
				BaseRecord owner = ownerOf(node);
				if(owner == null) {
					ownerUnresolved++;
					problems.add("node " + handle + ": owner could not be resolved; left RUNNING");
					continue;
				}
				boolean ok = false;
				try {
					ok = PbGraphUtil.persistStatus(owner, node, PbNodeStatusEnumType.READY);
				}
				catch(RuntimeException e) {
					errored++;
					problems.add("node " + handle + ": " + e.getMessage());
					continue;
				}
				if(!ok) {
					denied++;
					problems.add("node " + handle + ": could not be returned to READY as its owner");
					continue;
				}
				nodesReset++;
			}
		}
		catch(RuntimeException e) {
			logger.error("PB restart recovery aborted: " + e.getMessage(), e);
			problems.add("sweep aborted: " + e.getMessage());
		}
		finally {
			if(workflow != null) {
				PbGraphUtil.invalidateScan(workflow);
			}
			else {
				PbStaleUtil.clear();
			}
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runsInterrupted", Integer.valueOf(runsInterrupted));
		out.put("pendingInterrupted", Integer.valueOf(pendingInterrupted));
		out.put("nodesReset", Integer.valueOf(nodesReset));
		out.put("runsSkippedInFlight", Integer.valueOf(runsSkippedInFlight));
		out.put("nodesSkippedInFlight", Integer.valueOf(nodesSkippedInFlight));
		out.put("ownerUnresolved", Integer.valueOf(ownerUnresolved));
		out.put("denied", Integer.valueOf(denied));
		out.put("errored", Integer.valueOf(errored));
		out.put("problems", problems);
		out.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
		logger.info("PB restart recovery (one Service7 JVM per database assumed): " + runsInterrupted
			+ " RUNNING and " + pendingInterrupted + " PENDING run(s) closed INTERRUPTED, " + nodesReset
			+ " RUNNING node(s) returned to READY, " + runsSkippedInFlight + "/" + nodesSkippedInFlight
			+ " run(s)/node(s) in flight skipped, " + ownerUnresolved + " owner(s) unresolved, " + denied
			+ " write(s) refused, " + errored + " write(s) threw"
			+ (problems.isEmpty() ? "" : "; problems: " + String.join(" | ", problems)));
		return out;
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/**
	 * Runs in {@code status}, read raw: there is no principal at boot. {@code PbGraphUtil.runRequest()}
	 * is the projection the owner-scoped writes need (identity, name, counters, requestedNodeIds).
	 */
	private static List<BaseRecord> rawRuns(PbRunStatusEnumType status, BaseRecord workflow) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_RUN_STATUS, status.toString());
		if(workflow != null) {
			q.field(OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
		}
		q.setRequest(PbGraphUtil.runRequest());
		return raw(q, OlioModelNames.MODEL_PB_RUN);
	}

	private static List<BaseRecord> rawRunningNodes(BaseRecord workflow) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_NODE_STATUS,
			PbNodeStatusEnumType.RUNNING.toString());
		if(workflow != null) {
			q.field(OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
		}
		q.setRequest(PbGraphUtil.nodeRequest());
		return raw(q, OlioModelNames.MODEL_PB_NODE);
	}

	private static List<BaseRecord> raw(Query q, String model) {
		List<BaseRecord> out = new ArrayList<>();
		q.setCache(false);
		BaseRecord[] rows = IOSystem.getActiveContext().getSearch().findRecords(q);
		if(rows != null) {
			for(BaseRecord r : rows) {
				if(r.getSchema() == null) {
					r.setSchema(model);
				}
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * The owner of {@code rec} as the principal its reconciliation is written as. Resolved by id through
	 * the reader (the {@code ApparelUtil} / {@code ColorUtil} idiom); null when the id is missing, the
	 * user is gone, or the record carries no organization the writer could authorize against.
	 */
	private static BaseRecord ownerOf(BaseRecord rec) {
		Long ownerId = rec.get(FieldNames.FIELD_OWNER_ID);
		if(ownerId == null || ownerId.longValue() <= 0L) {
			return null;
		}
		try {
			BaseRecord owner = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_USER, ownerId.longValue());
			if(owner == null || owner.get(FieldNames.FIELD_ORGANIZATION_ID) == null) {
				return null;
			}
			return owner;
		}
		catch(Exception e) {
			logger.warn("Owner " + ownerId + " could not be read: " + e.getMessage());
			return null;
		}
	}

	private static int intOf(BaseRecord rec, String field) {
		Integer v = (rec.hasField(field) ? rec.get(field) : null);
		return (v != null ? v.intValue() : 0);
	}
}
