package org.cote.accountmanager.olio.picturebook;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-JVM claim of the nodes a render run is working on (V3 §21, P1-6): {@code node id -> run objectId}.
 * <p>
 * Two runs that both plan the same node would both execute it, both persist an artifact and race to
 * stamp its status. The DB row cannot arbitrate that - {@code nodeStatus} is a derived cache, not a lock
 * - so a run claims its planned nodes here, all-or-nothing, before it is submitted; a conflicting
 * request is refused at submit (409) rather than discovered mid-render.
 * <p>
 * The claim is per JVM (statement 4 in {@link PbRenderLanes}). A second Service7 against the same
 * database sees only the {@code olio.pb.run} RUNNING row, which P1-7's restart sweep reconciles.
 * The claim is released by the job in its {@code finally} - and, for a job cancelled while still
 * queued, by {@code AsyncJobWork.onCancelledBeforeStart} - keyed on the run objectId so a release
 * can never drop a claim the same node has since been re-granted to a later run.
 */
public final class PbNodeInflight {

	private static final Map<Long, String> inflight = new ConcurrentHashMap<>();

	private PbNodeInflight() {
	}

	/**
	 * Claim every node for {@code runObjectId}, or none of them.
	 *
	 * @return null when all nodes are now held by this run; otherwise the id of the first node already
	 *         held by a <b>different</b> run, in which case nothing was claimed. A node already held by
	 *         this same run is not a conflict.
	 */
	public static synchronized Long tryClaim(Collection<Long> nodeIds, String runObjectId) {
		if(nodeIds == null || runObjectId == null) {
			return null;
		}
		for(Long id : nodeIds) {
			String holder = inflight.get(id);
			if(holder != null && !holder.equals(runObjectId)) {
				return id;
			}
		}
		for(Long id : nodeIds) {
			inflight.put(id, runObjectId);
		}
		return null;
	}

	/** Release the nodes {@code runObjectId} holds; nodes held by another run are left alone. */
	public static void release(Collection<Long> nodeIds, String runObjectId) {
		if(nodeIds == null || runObjectId == null) {
			return;
		}
		for(Long id : nodeIds) {
			inflight.remove(id, runObjectId);
		}
	}

	/** The run objectId currently holding {@code nodeId}, or null. */
	public static String runOf(long nodeId) {
		return inflight.get(nodeId);
	}

	/** Test/diagnostic: number of nodes currently claimed in this JVM. */
	public static int size() {
		return inflight.size();
	}
}
