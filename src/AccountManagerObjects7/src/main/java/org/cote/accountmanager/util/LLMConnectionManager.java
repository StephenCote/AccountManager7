package org.cote.accountmanager.util;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Centralized registry for all active LLM and external service connections.
/// Tracks streaming futures, HTTP responses, and closeable HTTP clients
/// across all subsystems (chat, summarization, swarm, image tagging, etc.).
/// Provides a single shutdownAll() method for clean servlet shutdown.
public class LLMConnectionManager {

	public static final Logger logger = LogManager.getLogger(LLMConnectionManager.class);

	/// Active streaming futures — keyed by auto-incrementing stream ID.
	private static final ConcurrentHashMap<String, CompletableFuture<HttpResponse<Stream<String>>>> activeStreams = new ConcurrentHashMap<>();

	/// Per-stream label markers ("kind|startTimeMs") for diagnostics.
	/// Keyed by the same streamId as activeStreams.
	private static final ConcurrentHashMap<String, String> streamLabels = new ConcurrentHashMap<>();

	/// Thread-local label set by callers BEFORE invoking Chat.chat() so the
	/// internal registerStream call knows what kind of LLM call this is
	/// (chat / memory:keyframe / memory:extract / interaction / compliance / autotune / titleIcon).
	/// Cleared in finally by the caller. Defaults to "chat" if not set.
	private static final ThreadLocal<String> currentCallLabel = new ThreadLocal<>();

	public static void setCurrentCallLabel(String label) { currentCallLabel.set(label); }
	public static String getCurrentCallLabel() { return currentCallLabel.get(); }
	public static void clearCurrentCallLabel() { currentCallLabel.remove(); }

	/// Active HTTP responses — registered once the connection is established.
	private static final ConcurrentHashMap<String, HttpResponse<Stream<String>>> activeHttpResponses = new ConcurrentHashMap<>();

	/// Closeable HTTP clients — registered by subsystem name for cleanup on shutdown.
	private static final ConcurrentHashMap<String, AutoCloseable> registeredClients = new ConcurrentHashMap<>();

	/// Stream ID counter — monotonically increasing across all subsystems.
	private static final AtomicLong streamIdCounter = new AtomicLong(0);

	/// Active synchronous LLM HTTP calls (embedding, keyword extraction, etc.).
	/// These don't stream so they can't be tracked through registerStream, but
	/// they DO occupy Ollama's single inference slot and they DO hold GPU. Value
	/// is `label|startTimeMs` (same shape as AsyncLLMSlotRegistry markers).
	private static final ConcurrentHashMap<String, String> activeSyncCalls = new ConcurrentHashMap<>();
	private static final AtomicLong syncCallIdCounter = new AtomicLong(0);

	/// Graceful stop flags — keyed by request OID for interactive chat.
	private static final ConcurrentHashMap<String, Boolean> stopFlags = new ConcurrentHashMap<>();

	/// Register a new streaming future. Returns the stream ID for later cleanup.
	/// The label is taken from the thread-local set by the caller (or defaults
	/// to "chat") so each active stream is identifiable in the debug view.
	public static String registerStream(CompletableFuture<HttpResponse<Stream<String>>> future) {
		String streamId = "stream-" + streamIdCounter.incrementAndGet();
		activeStreams.put(streamId, future);
		String label = currentCallLabel.get();
		if (label == null || label.isEmpty()) label = "chat";
		streamLabels.put(streamId, label + "|" + System.currentTimeMillis());
		return streamId;
	}

	/// Overload that lets callers supply the label explicitly instead of via
	/// the thread-local.
	public static String registerStream(String label, CompletableFuture<HttpResponse<Stream<String>>> future) {
		String streamId = "stream-" + streamIdCounter.incrementAndGet();
		activeStreams.put(streamId, future);
		String safeLabel = (label == null || label.isEmpty()) ? "chat" : label;
		streamLabels.put(streamId, safeLabel + "|" + System.currentTimeMillis());
		return streamId;
	}

	/// Register the HTTP response once the connection is established.
	public static void registerHttpResponse(String streamId, HttpResponse<Stream<String>> response) {
		if (streamId != null && response != null) {
			activeHttpResponses.put(streamId, response);
		}
	}

	/// Remove a completed/failed stream from the registry.
	public static void unregisterStream(String streamId) {
		if (streamId != null) {
			activeStreams.remove(streamId);
			activeHttpResponses.remove(streamId);
			streamLabels.remove(streamId);
		}
	}

	/// Register a closeable client (HttpClient, Jakarta Client, etc.) for shutdown cleanup.
	/// Use a descriptive key like "imageTagUtil", "clientUtil.jakarta", "swarm.session".
	public static void registerClient(String key, AutoCloseable client) {
		if (key != null && client != null) {
			registeredClients.put(key, client);
		}
	}

	/// Unregister a client (e.g., when a subsystem is done with it).
	public static void unregisterClient(String key) {
		if (key != null) {
			registeredClients.remove(key);
		}
	}

	/// Set a graceful stop flag for a request.
	public static void requestStop(String requestId) {
		if (requestId != null) {
			stopFlags.put(requestId, true);
		}
	}

	/// Check if a request has been flagged for stop.
	public static boolean isStopRequested(String requestId) {
		return requestId != null && Boolean.TRUE.equals(stopFlags.get(requestId));
	}

	/// Clear a stop flag.
	public static void clearStopFlag(String requestId) {
		if (requestId != null) {
			stopFlags.remove(requestId);
		}
	}

	/// Get count of all active streams.
	public static int getActiveStreamCount() {
		return activeStreams.size();
	}

	/// Register a synchronous LLM HTTP call (embedding, keyword/topic/sentiment/etc.
	/// extraction). Returns an opaque id the caller MUST pass back to
	/// unregisterSyncCall in a finally block. `label` is recorded for diagnostics.
	public static String registerSyncCall(String label) {
		String id = "sync-" + syncCallIdCounter.incrementAndGet();
		String marker = (label == null ? "?" : label) + "|" + System.currentTimeMillis();
		activeSyncCalls.put(id, marker);
		return id;
	}

	/// Release a sync-call slot. Safe to call with a null id.
	public static void unregisterSyncCall(String id) {
		if (id == null) return;
		activeSyncCalls.remove(id);
	}

	/// Active count of synchronous LLM HTTP calls (embeddings, keyword extraction, etc.).
	public static int getActiveSyncCallCount() {
		return activeSyncCalls.size();
	}

	/// Total active LLM HTTP activity = streams + sync calls. This is the
	/// correct number for pressure-based deferral decisions: stream-only
	/// counting misses embedding work that also occupies the GPU.
	public static int getActiveLLMCallCount() {
		return activeStreams.size() + activeSyncCalls.size();
	}

	/// Diagnostic — labels of currently-in-flight sync calls.
	public static Set<String> getActiveSyncCallLabels() {
		return Set.copyOf(activeSyncCalls.values());
	}

	/// Diagnostic — labels of currently-in-flight streams.
	public static Set<String> getActiveStreamLabels() {
		return Set.copyOf(streamLabels.values());
	}

	/// Combined snapshot of every in-flight LLM call (streams + sync) as
	/// `kind|startTimeMs` markers. Useful for a debug UI that lists active
	/// activity with what each call is doing. Returns immutable copies of
	/// the markers from each registry; ordering is arbitrary.
	public static Map<String, String> snapshotActiveLLMCalls() {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		out.putAll(streamLabels);
		out.putAll(activeSyncCalls);
		return out;
	}

	/// Get count of registered clients.
	public static int getRegisteredClientCount() {
		return registeredClients.size();
	}

	/// Get the set of registered client keys (for debug panel).
	public static Set<String> getRegisteredClientKeys() {
		return registeredClients.keySet();
	}

	/// Stop all active streams — close HTTP response bodies, cancel futures.
	public static void stopAllStreams() {
		int streamCount = activeStreams.size();
		int responseCount = activeHttpResponses.size();
		logger.info("stopAllStreams: aborting " + streamCount + " stream(s), " + responseCount + " response(s)");

		/// Phase 1: Close HTTP response body streams to force IO exceptions
		for (Map.Entry<String, HttpResponse<Stream<String>>> entry : activeHttpResponses.entrySet()) {
			try {
				entry.getValue().body().close();
			} catch (Exception e) {
				logger.debug("stopAllStreams: close response body " + entry.getKey() + ": " + e.getMessage());
			}
		}
		/// Phase 2: Cancel futures
		for (Map.Entry<String, CompletableFuture<HttpResponse<Stream<String>>>> entry : activeStreams.entrySet()) {
			try {
				entry.getValue().cancel(true);
			} catch (Exception e) {
				logger.debug("stopAllStreams: cancel future " + entry.getKey() + ": " + e.getMessage());
			}
		}
		activeStreams.clear();
		activeHttpResponses.clear();
		streamLabels.clear();
		stopFlags.clear();
		/// Sync calls aren't cancellable from here (no future to cancel) but we
		/// clear the registry so the view of "active" matches reality after a stop.
		activeSyncCalls.clear();
	}

	/// Close all registered clients gracefully.
	public static void closeAllClients() {
		int count = registeredClients.size();
		logger.info("closeAllClients: closing " + count + " registered client(s)");
		for (Map.Entry<String, AutoCloseable> entry : registeredClients.entrySet()) {
			try {
				logger.info("closeAllClients: closing " + entry.getKey());
				entry.getValue().close();
			} catch (Exception e) {
				logger.warn("closeAllClients: failed to close " + entry.getKey() + ": " + e.getMessage());
			}
		}
		registeredClients.clear();
	}

	/// Master shutdown — stops all streams, closes all clients.
	/// Call this from the servlet shutdown hook.
	public static void shutdownAll() {
		logger.info("shutdownAll: beginning graceful shutdown of all LLM/service connections");
		stopAllStreams();
		closeAllClients();
		logger.info("shutdownAll: complete");
	}
}
