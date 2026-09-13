package org.cote.accountmanager.thread;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * Principal-scoped registry and bounded executor for long-running background work.
 *
 * <p><b>Why this exists.</b> The long PictureBook/ChapBook operations ran synchronously inside one
 * HTTP request, so their completion depended on that connection staying open for the whole run.
 * Measured 2026-09-13: a 17-chunk scene extraction took ~27 minutes, nginx's
 * {@code proxy_read_timeout} returned 504 at exactly 900s, Tomcat carried on to chunk 11/17 four
 * minutes later, and every chunk of LLM work was discarded — nothing was persisted and nothing
 * server-side even logged an error, because from the server's point of view nothing had failed.
 * Submitting the work here instead decouples "is the work finished" from "is the caller still
 * connected".
 *
 * <p><b>Ownership semantics are inherited deliberately from
 * {@code PictureBookCancelRegistry}</b> (read its javadoc before changing anything here — it
 * documents a real security defect that this keying fixed). The composite key is
 * {@code <principal objectId> + ' ' + <jobId>}, so a poll or cancel by a non-owner simply misses
 * the map and is <b>indistinguishable</b> from an unknown job id. Both return nothing, so the
 * endpoint cannot be used to probe whether some other user has a job in flight.
 *
 * <p><b>Why a jobId and not the work/book id.</b> The older registry keyed on the client-supplied
 * work/book objectId, which meant {@code extract-scenes-only} and {@code prepare-images} shared one
 * flat key space: a user running two phases at once silently clobbered one token with the other. A
 * server-generated jobId removes that collision and lets one user run several jobs concurrently.
 *
 * <p><b>Concurrency is capped on purpose.</b> Making this work async makes it trivial to launch
 * several 27-minute jobs at once, and they all target one GPU-backed LLM server that processes
 * requests sequentially — {@code ChatUtil.DEFAULT_SUMMARY_WORKERS} is 2 for exactly this reason
 * ("more than 2 concurrent calls causes queue-induced timeouts"), and the hardware is known to fall
 * over under sustained load. So the pool is small and jobs beyond it sit visibly {@code QUEUED}
 * rather than piling onto the server.
 *
 * <p><b>Not the ForkJoin common pool.</b> These tasks block for tens of minutes on outbound HTTP.
 * Running them on the common pool would starve everything else that uses it
 * ({@code ChatUtil.mapSummarize}, keyframe and memory side-effects), and the common pool offers no
 * way to bound them as above.
 */
public final class AsyncJobRegistry {

	private static final Logger logger = LogManager.getLogger(AsyncJobRegistry.class);

	/**
	 * Concurrent long-running jobs. Matches {@code ChatUtil.DEFAULT_SUMMARY_WORKERS}; see the class
	 * javadoc for why this is a correctness constraint and not a tuning knob.
	 */
	public static final int MAX_CONCURRENT_JOBS = 2;

	/**
	 * How long a finished job (and its result) is retained for collection. Generous on purpose: the
	 * whole point is that the client may have been disconnected when the job finished, so it needs
	 * a real window in which to come back and ask. Bounded because this is in-memory — a leak here
	 * is unbounded heap growth in a long-running Tomcat, the same hazard
	 * {@code PictureBookCancelRegistry} warns about.
	 */
	public static final long COMPLETED_TTL_MS = 30L * 60L * 1000L;

	/** Hard cap on retained jobs, so a pathological client cannot grow the map without limit. */
	private static final int MAX_RETAINED_JOBS = 200;

	/** Composite-keyed {@code <principal objectId> <jobId>}; see class javadoc. */
	private static final Map<String, AsyncJob> registry = new ConcurrentHashMap<>();

	private static final AtomicInteger threadSeq = new AtomicInteger(0);

	/**
	 * The worker pool, created on demand and discarded by {@link #shutdown()}.
	 *
	 * <p><b>Recreatable on purpose.</b> This was originally a {@code static final} pool, which made
	 * {@link #shutdown()} permanent for the life of the classloader: after one
	 * {@code IOSystem.close()} every later {@link #submit} threw
	 * {@code RejectedExecutionException}. That breaks two ordinary situations, not just tests — a
	 * Tomcat context redeploy inside the same JVM (routine during development), and any
	 * close/reopen of the IO context. Lazily recreating the pool makes shutdown a drain rather than
	 * a one-way door.
	 *
	 * <p>Daemon threads: a stuck 27-minute LLM call must never hold up JVM/Tomcat shutdown. The
	 * work is restart-tolerant by design (callers persist incrementally), so abandoning a thread at
	 * shutdown is safer than blocking on it.
	 */
	private static volatile ExecutorService executor;

	private AsyncJobRegistry() {
	}

	private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "am7-asyncjob-" + threadSeq.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	};

	/**
	 * The live pool, creating one if absent or if a previous {@link #shutdown()} retired it.
	 * Synchronized rather than a plain double-checked assignment so two concurrent submissions
	 * cannot each build a pool and leak one.
	 */
	private static synchronized ExecutorService executor() {
		if (executor == null || executor.isShutdown()) {
			executor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS, THREAD_FACTORY);
		}
		return executor;
	}

	/**
	 * Build the composite key, or null when either half is missing. A null principal (or one with
	 * no objectId) must never share a key space with a real one, so it yields null and every
	 * operation becomes a no-op — same rule as {@code PictureBookCancelRegistry.compositeKey}.
	 */
	private static String compositeKey(String ownerObjectId, String jobId) {
		if (ownerObjectId == null || ownerObjectId.isEmpty() || jobId == null || jobId.isEmpty()) {
			return null;
		}
		return ownerObjectId + " " + jobId;
	}

	private static String ownerOf(BaseRecord user) {
		if (user == null) {
			return null;
		}
		return user.get(FieldNames.FIELD_OBJECT_ID);
	}

	/**
	 * Submit {@code work} to run in the background on {@code user}'s behalf and return the job
	 * immediately, so the caller's HTTP request can return at once.
	 *
	 * @param kind short job-kind label, e.g. {@code pb.extractScenes}
	 * @param key  the work/book objectId the job operates on
	 * @return the registered job, or null when the principal cannot be keyed (in which case nothing
	 *         was submitted — the caller must fall back to running synchronously rather than
	 *         silently dropping the request)
	 */
	public static AsyncJob submit(BaseRecord user, String kind, String key, AsyncJobWork work) {
		String owner = ownerOf(user);
		if (owner == null || owner.isEmpty() || work == null) {
			logger.warn("Async job not submitted — no usable principal (kind=" + kind + " key=" + key + ")");
			return null;
		}
		sweep();
		String jobId = UUID.randomUUID().toString();
		String ck = compositeKey(owner, jobId);
		final AsyncJob job = new AsyncJob(jobId, kind, key, owner, new SummarizeProgress());
		registry.put(ck, job);

		executor().submit(new Runnable() {
			@Override
			public void run() {
				runJob(job, work);
			}
		});
		logger.info("Async job submitted: kind=" + kind + " key=" + key + " jobId=" + jobId
			+ " (live jobs: " + registry.size() + ")");
		return job;
	}

	private static void runJob(AsyncJob job, AsyncJobWork work) {
		/// A cancel can land while the job is still QUEUED, before any work starts. Honour it
		/// rather than running work the user already asked to stop.
		if (job.getProgress().isCancelled()) {
			finish(job, AsyncJob.Status.CANCELLED, null, "Cancelled before starting");
			return;
		}
		job.setStatus(AsyncJob.Status.RUNNING);
		job.getProgress().setPhase("running");
		try {
			String result = work.run(job);
			/// Cancellation is cooperative: the work loops break and RETURN what they completed
			/// rather than throwing. That partial output is still worth keeping — it is the whole
			/// reason cancel is safe to offer — so it is stored either way and only the status
			/// differs.
			if (job.getProgress().isCancelled()) {
				job.setResult(result);
				finish(job, AsyncJob.Status.CANCELLED, result, null);
				return;
			}
			finish(job, AsyncJob.Status.COMPLETED, result, null);
		} catch (Exception e) {
			logger.error("Async job failed: kind=" + job.getKind() + " jobId=" + job.getJobId()
				+ " — " + e.getMessage(), e);
			finish(job, AsyncJob.Status.FAILED, null, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private static void finish(AsyncJob job, AsyncJob.Status status, String result, String error) {
		if (result != null) {
			job.setResult(result);
		}
		if (error != null) {
			job.setError(error);
		}
		job.setCompletedAt(System.currentTimeMillis());
		job.setStatus(status);
		job.getProgress().setPhase(status.name().toLowerCase());
		logger.info("Async job " + status + ": kind=" + job.getKind() + " jobId=" + job.getJobId()
			+ " elapsed=" + job.getElapsedSeconds() + "s");
	}

	/**
	 * The caller's own job, or null. Never returns another principal's job, so an unknown id and
	 * someone else's id are indistinguishable.
	 */
	public static AsyncJob get(BaseRecord user, String jobId) {
		String ck = compositeKey(ownerOf(user), jobId);
		if (ck == null) {
			return null;
		}
		sweep();
		return registry.get(ck);
	}

	/**
	 * Request cooperative cancellation of the caller's own job.
	 *
	 * @return true only when a job owned by this principal was found and newly cancelled. An
	 *         unknown id, another user's id, an already-cancelled job and an already-finished job
	 *         all return false — deliberately the same answer, matching
	 *         {@code PictureBookCancelRegistry.cancel}.
	 */
	public static boolean cancel(BaseRecord user, String jobId) {
		AsyncJob job = get(user, jobId);
		if (job == null || job.isTerminal() || job.getProgress().isCancelled()) {
			return false;
		}
		job.getProgress().cancel();
		logger.info("Async job cancel requested: jobId=" + jobId + " kind=" + job.getKind());
		return true;
	}

	/**
	 * All of the caller's live and recently-completed jobs, newest first. This is what lets a
	 * reopened/reloaded client reattach to a run that is still going instead of starting a second
	 * one.
	 */
	public static List<AsyncJob> list(BaseRecord user) {
		List<AsyncJob> out = new ArrayList<>();
		String owner = ownerOf(user);
		if (owner == null || owner.isEmpty()) {
			return out;
		}
		sweep();
		for (AsyncJob job : registry.values()) {
			if (owner.equals(job.getOwnerObjectId())) {
				out.add(job);
			}
		}
		out.sort(Comparator.comparingLong(AsyncJob::getCreatedAt).reversed());
		return out;
	}

	/**
	 * Evict finished jobs past {@link #COMPLETED_TTL_MS}, and — if the map is still over
	 * {@link #MAX_RETAINED_JOBS} — the oldest finished jobs beyond that cap. Running jobs are never
	 * evicted regardless of age: a legitimate run can take well over an hour, and dropping its
	 * registration would strand the work with no way to collect it.
	 *
	 * <p>Swept lazily on registry access rather than by a timer thread, so there is no background
	 * thread to own, and an idle deployment does no work.
	 */
	private static void sweep() {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, AsyncJob> e : registry.entrySet()) {
			AsyncJob job = e.getValue();
			if (job.isTerminal() && job.getCompletedAt() > 0
					&& (now - job.getCompletedAt()) > COMPLETED_TTL_MS) {
				registry.remove(e.getKey(), job);
			}
		}
		if (registry.size() <= MAX_RETAINED_JOBS) {
			return;
		}
		List<Map.Entry<String, AsyncJob>> finished = new ArrayList<>();
		for (Map.Entry<String, AsyncJob> e : registry.entrySet()) {
			if (e.getValue().isTerminal()) {
				finished.add(e);
			}
		}
		finished.sort(Comparator.comparingLong(e -> e.getValue().getCompletedAt()));
		int excess = registry.size() - MAX_RETAINED_JOBS;
		for (int i = 0; i < finished.size() && i < excess; i++) {
			registry.remove(finished.get(i).getKey(), finished.get(i).getValue());
		}
	}

	/**
	 * Stop accepting work and abandon in-flight jobs. Called from {@code IOSystem.close()} beside
	 * the other context-owned threads. Does not wait long: the threads are daemons and the work is
	 * restart-tolerant, so a slow LLM call must not delay shutdown.
	 */
	public static synchronized void shutdown() {
		ExecutorService current = executor;
		/// Null when nothing was ever submitted — a close with no jobs must not build a pool just
		/// to tear it down.
		if (current != null) {
			current.shutdownNow();
			try {
				current.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			/// Clear the reference so executor() builds a fresh pool if this context is reopened.
			executor = null;
		}
		registry.clear();
	}

	/** Test/diagnostic accessor: number of retained jobs across all principals. */
	public static int size() {
		return registry.size();
	}
}
