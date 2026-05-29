package org.cote.accountmanager.olio.llm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Per-stream idle watchdog. While a stream is active, the consumer thread
/// calls `touch()` each time a chunk is received. A scheduled task checks
/// `now - lastChunkAt` against the idle timeout; if exceeded, it invokes
/// the supplied `onIdle` callback (which is responsible for force-closing
/// the underlying HTTP response body so the blocking iterator unwinds).
///
/// Accumulated content is preserved automatically by the consumer because
/// chunks are appended to its response buffer as they arrive — when the
/// iterator throws on close, the buffer holds whatever made it through.
///
/// All entries are removed in `stop()` (success path) or by the idle
/// callback (force-close path). The class is decoupled from Chat /
/// HttpResponse so it can be unit tested with a fake onIdle.
public final class StreamIdleWatchdog {

	public static final Logger logger = LogManager.getLogger(StreamIdleWatchdog.class);

	/// Shared daemon scheduler — one thread is enough for thousands of streams
	/// because each scheduled check is microseconds of work.
	private final ScheduledExecutorService scheduler;
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
	private final long checkIntervalMs;

	public StreamIdleWatchdog() {
		this(5_000L);
	}

	public StreamIdleWatchdog(long checkIntervalMs) {
		this.checkIntervalMs = checkIntervalMs;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "StreamIdleWatchdog");
			t.setDaemon(true);
			return t;
		});
	}

	private static final class Entry {
		final String label;
		final long idleTimeoutMs;
		final Consumer<String> onIdle;
		volatile long lastChunkAt;
		ScheduledFuture<?> task;
		volatile boolean fired = false;

		Entry(String label, long idleTimeoutMs, Consumer<String> onIdle, long nowMs) {
			this.label = label == null ? "?" : label;
			this.idleTimeoutMs = idleTimeoutMs;
			this.onIdle = onIdle;
			this.lastChunkAt = nowMs;
		}
	}

	/// Start watching `streamId`. `onIdle.accept(streamId)` fires once if
	/// the stream is idle for longer than `idleTimeoutMs`. Caller MUST call
	/// `stop(streamId)` when the stream completes / errors normally.
	/// idleTimeoutMs <= 0 disables watchdog (no-op).
	public void start(String streamId, String label, long idleTimeoutMs, Consumer<String> onIdle) {
		if (streamId == null || idleTimeoutMs <= 0 || onIdle == null) return;
		Entry e = new Entry(label, idleTimeoutMs, onIdle, System.currentTimeMillis());
		entries.put(streamId, e);
		e.task = scheduler.scheduleAtFixedRate(
			() -> check(streamId),
			checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
	}

	/// Record that data arrived for `streamId`. Cheap — single map.get + volatile write.
	public void touch(String streamId) {
		if (streamId == null) return;
		Entry e = entries.get(streamId);
		if (e != null) e.lastChunkAt = System.currentTimeMillis();
	}

	/// Cancel and unregister watchdog for `streamId`. Safe to call when
	/// there's no entry (no-op).
	public void stop(String streamId) {
		if (streamId == null) return;
		Entry e = entries.remove(streamId);
		if (e != null && e.task != null) e.task.cancel(false);
	}

	/// Test/diagnostic — how many streams are being watched.
	public int activeCount() {
		return entries.size();
	}

	/// Test/diagnostic — pure helper used by check(). Exposed for unit
	/// testing without involving the scheduler.
	public static boolean isIdle(long lastChunkAt, long nowMs, long idleTimeoutMs) {
		if (idleTimeoutMs <= 0) return false;
		return (nowMs - lastChunkAt) > idleTimeoutMs;
	}

	/// Periodic scheduled check — fires onIdle once per stream, then removes.
	void check(String streamId) {
		Entry e = entries.get(streamId);
		if (e == null) return;
		if (e.fired) return;
		if (isIdle(e.lastChunkAt, System.currentTimeMillis(), e.idleTimeoutMs)) {
			e.fired = true;
			long idleFor = System.currentTimeMillis() - e.lastChunkAt;
			logger.warn("[STREAM-IDLE] " + e.label + " stream=" + streamId
				+ " idle for " + idleFor + "ms (threshold=" + e.idleTimeoutMs
				+ "ms) — invoking onIdle");
			try {
				e.onIdle.accept(streamId);
			} catch (Exception ex) {
				logger.warn("[STREAM-IDLE] onIdle callback threw: " + ex.getMessage());
			} finally {
				stop(streamId);
			}
		}
	}

	/// Shutdown the scheduler — for clean servlet shutdown only. The
	/// LLMConnectionManager.shutdownAll path can call this.
	public void shutdown() {
		try {
			scheduler.shutdownNow();
		} catch (Exception ignore) { /* best effort */ }
		entries.clear();
	}
}
