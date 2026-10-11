package org.cote.accountmanager.olio.picturebook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.thread.AsyncJobRegistry;

/**
 * Per-SD-server render lanes for the async render jobs (V3 §21, P1-6): one fair {@link Semaphore}
 * per backend URL, created lazily with the boot-pinned permit count.
 * <p>
 * The {@code AsyncJobRegistry} pool is {@code MAX_CONCURRENT_JOBS} wide, so two render jobs can run at
 * once; without this, both would fire at the same Swarm and queue inside it, where a cancel cannot
 * reach and where the second request's wall-clock includes the first's. A lane makes the wait happen
 * <i>here</i>, where the waiting job still observes its own cancel token.
 * <p>
 * Five statements that bound what this class does (architect ruling Q5, 2026-10-11):
 * <ol>
 * <li>A permit is taken around one SD-backed <b>stage</b>, not a whole run, and is released in the
 * stage's {@code finally} - so a run's prompt and reference-strip stages never hold the GPU lane, and a
 * thrown or cancelled stage cannot strand a permit.</li>
 * <li>{@link #acquire} polls rather than blocks, and returns {@code false} without a permit as soon as
 * the job's {@link SummarizeProgress} is cancelled: a job waiting for a busy backend can be cancelled
 * without first being served by it.</li>
 * <li>The permit count is boot-pinned ({@link #CONFIG_KEY}, read once by the transport's startup
 * listener, default {@link #DEFAULT_PERMITS}). Changing it after a lane exists does not resize that
 * lane - a {@link Semaphore} cannot be resized in place - so {@link #configure} warns when called late
 * and the new count applies only to lanes created afterwards.</li>
 * <li>The lane is per JVM. A second Service7 or Console7 against the same backend shares nothing
 * here; the only cross-JVM bound is the {@code olio.pb.run} row's RUNNING status and the 409 the
 * in-flight claim ({@link PbNodeInflight}) returns at submit time within one JVM.</li>
 * <li>Only the render job takes a lane. {@code PictureBookUtil.generateSceneImage} (the v1 scene path),
 * the synchronous regenerate and every other {@code SDUtil} caller still talk to the backend directly,
 * exactly as before; the lane serialises async render jobs among themselves, nothing else.</li>
 * </ol>
 */
public final class PbRenderLanes {

	private static final Logger logger = LogManager.getLogger(PbRenderLanes.class);

	/** Service7 init-param / Console7 property naming the per-backend permit count. */
	public static final String CONFIG_KEY = "pb.render.sdPermits";

	public static final int DEFAULT_PERMITS = 1;

	/** Key used when a stage carries no backend URL at all; such a stage still serialises with its peers. */
	public static final String NO_SERVER_LANE = "(none)";

	/** How long one {@code tryAcquire} waits before the cancel flag is re-checked. */
	static final long ACQUIRE_POLL_MS = 250L;

	private static volatile int permits = DEFAULT_PERMITS;

	private static final Map<String, Semaphore> lanes = new ConcurrentHashMap<>();

	private PbRenderLanes() {
	}

	/**
	 * Pin the permit count for lanes created from now on. Values below 1 are clamped to 1 - a zero-permit
	 * lane would make every render job wait until cancelled. Logged at INFO when the value changes; at
	 * WARN when a lane already exists, because that lane keeps its old count (statement 3).
	 */
	public static void configure(int count) {
		int use = Math.max(1, count);
		if(use != permits) {
			logger.info(CONFIG_KEY + " set to " + use + " (was " + permits + ")");
		}
		if(!lanes.isEmpty()) {
			logger.warn(CONFIG_KEY + " changed to " + use + " after " + lanes.size()
				+ " lane(s) were created; existing lanes keep their previous permit count until restart");
		}
		permits = use;
	}

	public static int permits() {
		return permits;
	}

	/**
	 * The lane key for a backend URL: trimmed, lower-cased, trailing slashes removed, so
	 * {@code http://host:7801} and {@code http://HOST:7801/} share one lane. Null/blank maps to
	 * {@link #NO_SERVER_LANE}.
	 */
	public static String laneKey(String sdServer) {
		if(sdServer == null) {
			return NO_SERVER_LANE;
		}
		String s = sdServer.trim().toLowerCase();
		while(s.endsWith("/")) {
			s = s.substring(0, s.length() - 1);
		}
		return (s.isEmpty() ? NO_SERVER_LANE : s);
	}

	/**
	 * Take one permit on {@code sdServer}'s lane, waiting as long as it takes <b>unless</b> {@code progress}
	 * is cancelled first (statement 2).
	 *
	 * @param progress the job's own token; null means "cannot be cancelled" and the wait is unbounded
	 * @return true when a permit is held and {@link #release} must be called; false when the wait was
	 *         abandoned on cancel, on an announced shutdown ({@link AsyncJobRegistry#isShuttingDown()})
	 *         or on thread interrupt, in which case nothing is held
	 */
	public static boolean acquire(String sdServer, SummarizeProgress progress) {
		Semaphore lane = laneOf(sdServer);
		try {
			while(true) {
				if(progress != null && progress.isCancelled()) {
					return false;
				}
				if(AsyncJobRegistry.isShuttingDown()) {
					return false;
				}
				if(lane.tryAcquire(ACQUIRE_POLL_MS, TimeUnit.MILLISECONDS)) {
					return true;
				}
			}
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Return the permit {@link #acquire} handed out. Call from a {@code finally}, and only after a true acquire. */
	public static void release(String sdServer) {
		laneOf(sdServer).release();
	}

	/** Test/diagnostic: permits currently free on the lane (creating it if absent). */
	public static int available(String sdServer) {
		return laneOf(sdServer).availablePermits();
	}

	/** Test/diagnostic: threads waiting on the lane right now (creating it if absent). */
	public static int waiting(String sdServer) {
		return laneOf(sdServer).getQueueLength();
	}

	/** Test/diagnostic: number of lanes created so far in this JVM. */
	public static int laneCount() {
		return lanes.size();
	}

	private static Semaphore laneOf(String sdServer) {
		return lanes.computeIfAbsent(laneKey(sdServer), k -> {
			logger.info("Render lane created for " + k + " with " + permits + " permit(s)");
			return new Semaphore(permits, true);
		});
	}
}
