package org.cote.accountmanager.olio.picturebook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.util.LLMConnectionManager;

/**
 * Cancel tokens for in-flight render runs (V3 §21, P1-6): {@code run objectId -> the job's own
 * SummarizeProgress}.
 * <p>
 * {@code PictureBookCancelRegistry} keys on principal + client key and hands out a fresh token the
 * worker then has to carry; a render run already has a durable identity (the {@code olio.pb.run} row's
 * objectId) and the {@code AsyncJobRegistry} already gave the job a token, so this registry stores
 * <b>that</b> token under <b>that</b> id - cancelling through {@code /run/{runId}/cancel} and
 * cancelling through {@code /rest/job/{jobId}/cancel} then flip the same flag.
 * <p>
 * There is deliberately no principal in the key. The authorization boundary is the facade:
 * {@code requireBook -> requireBookUpdate -> requireWorkflow -> readRun + run.workflow == workflow}
 * must all pass before {@link #cancel} is reached, so a caller can only cancel a run of a book they may
 * update. This class never decides who may cancel; it only flips the flag.
 * <p>
 * Lifecycle: the HTTP thread registers right after {@code AsyncJobRegistry.submit}, then re-checks
 * {@code job.isTerminal()} and unregisters if the worker already finished (otherwise a job that completes
 * between submit and register would leak its entry). The worker unregisters in its {@code finally} with
 * {@code remove(key, value)}, so a late unregister cannot evict an entry a resumed run has since
 * registered under the same objectId. The worker also binds the token as its
 * {@link LLMConnectionManager} cancel scope on its own thread (the binding is thread-local), so
 * {@link #cancel} can abort an in-flight prompt-stage LLM stream. There is no equivalent for the SD call
 * - Phase 1 cancel is cooperative and takes effect at the next stage boundary (design §21, dated edit
 * 2026-10-11).
 */
public final class PbRunCancelRegistry {

	private static final Logger logger = LogManager.getLogger(PbRunCancelRegistry.class);

	private static final Map<String, SummarizeProgress> registry = new ConcurrentHashMap<>();

	private PbRunCancelRegistry() {
	}

	/** Publish {@code progress} as the cancel token for {@code runObjectId}. */
	public static void register(String runObjectId, SummarizeProgress progress) {
		if(runObjectId == null || progress == null) {
			return;
		}
		registry.put(runObjectId, progress);
	}

	/** Remove the entry only if it still maps to {@code progress}. */
	public static void unregister(String runObjectId, SummarizeProgress progress) {
		if(runObjectId == null || progress == null) {
			return;
		}
		registry.remove(runObjectId, progress);
	}

	/**
	 * Flip the run's cancel flag and abort any LLM stream bound to its token.
	 *
	 * @return false when no run with that id is in flight in this JVM or it was already cancelled;
	 *         true when this call cancelled it
	 */
	public static boolean cancel(String runObjectId) {
		if(runObjectId == null) {
			return false;
		}
		SummarizeProgress progress = registry.get(runObjectId);
		if(progress == null || progress.isCancelled()) {
			return false;
		}
		progress.cancel();
		int aborted = LLMConnectionManager.abortCancelScope(progress);
		logger.info("Cancel requested for render run " + runObjectId + " (phase=" + progress.getPhase()
			+ ", " + progress.getCurrent() + "/" + progress.getTotal() + ", aborted LLM streams=" + aborted + ")");
		return true;
	}

	/** The token registered for {@code runObjectId}, or null. */
	public static SummarizeProgress peek(String runObjectId) {
		return (runObjectId == null ? null : registry.get(runObjectId));
	}

	/** Test/diagnostic: number of runs registered in this JVM. */
	public static int size() {
		return registry.size();
	}
}
