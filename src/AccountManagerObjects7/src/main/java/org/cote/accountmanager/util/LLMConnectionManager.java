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

	/// ---- Per-operation cancel scopes (KI-74) -------------------------------------------------------
	///
	/// A long-running operation (a PictureBook extraction, an async job) makes many LLM calls from ONE
	/// worker thread, and its cancel token is checked only between those calls - so a cancel used to
	/// take effect after the call already running finished, while the model kept generating for a
	/// caller that had given up. The operation binds a scope object (its cancel token, compared by
	/// identity) to its thread before it starts; every stream Chat registers from that thread is then
	/// attached to the scope, and a cancel arriving on ANOTHER thread can {@link #abortCancelScope}
	/// it: close the response body (the primitive that actually stops a generation whose headers have
	/// arrived) and cancel the raw sendAsync future (the one that works while the request is still
	/// queued upstream) - the same two phases, in the same order, as {@link #stopAllStreams()}, but for
	/// one operation's streams only. Same thread-local idiom as {@code currentCallLabel} above.
	private static final ThreadLocal<Object> cancelScope = new ThreadLocal<>();
	private static final ConcurrentHashMap<Object, Set<String>> scopeStreams = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Object> streamScopes = new ConcurrentHashMap<>();

	/// Bind {@code scope} to the current thread: every stream registered from this thread until
	/// {@link #unbindCancelScope} is attached to it. Null clears the binding. Call on the thread that
	/// will make the LLM calls (registration happens on the calling thread, not the stream's).
	public static void bindCancelScope(Object scope) {
		if (scope == null) {
			cancelScope.remove();
		} else {
			cancelScope.set(scope);
		}
	}

	/// The scope bound to the current thread, or null.
	public static Object currentCancelScope() {
		return cancelScope.get();
	}

	/// Clear the current thread's binding - only if it is still {@code scope} (or scope is null), so a
	/// stale unbind cannot drop a newer binding. Bookkeeping for the scope's streams is kept until
	/// {@link #releaseCancelScope}; a stream still in flight at unbind time stays abortable.
	public static void unbindCancelScope(Object scope) {
		Object cur = cancelScope.get();
		if (scope == null || cur == scope) {
			cancelScope.remove();
		}
	}

	/// Drop all bookkeeping for {@code scope}. Call when the operation is over (in a finally, beside
	/// the token's own unregister). Does NOT abort anything.
	public static void releaseCancelScope(Object scope) {
		if (scope == null) return;
		Set<String> ids = scopeStreams.remove(scope);
		if (ids != null) {
			for (String id : ids) {
				streamScopes.remove(id, scope);
			}
		}
	}

	/// Abort every stream currently attached to {@code scope}. Returns how many were aborted. The
	/// scope's bookkeeping stays in place (the operation releases it when it unwinds), so a stream
	/// registered after this call - e.g. a retry the loop fires before it sees the cancel flag - is
	/// still attached and a second abort call reaches it.
	public static int abortCancelScope(Object scope) {
		if (scope == null) return 0;
		Set<String> ids = scopeStreams.get(scope);
		if (ids == null || ids.isEmpty()) return 0;
		int n = 0;
		for (String id : new java.util.ArrayList<>(ids)) {
			if (abortStream(id)) n++;
		}
		if (n > 0) {
			logger.info("abortCancelScope: aborted " + n + " in-flight LLM stream(s)");
		}
		return n;
	}

	/// Streams currently attached to {@code scope} (registered and not yet unregistered/aborted).
	public static int getCancelScopeStreamCount(Object scope) {
		if (scope == null) return 0;
		Set<String> ids = scopeStreams.get(scope);
		return ids == null ? 0 : ids.size();
	}

	private static void attachToScope(String streamId) {
		Object scope = cancelScope.get();
		if (scope == null || streamId == null) return;
		scopeStreams.computeIfAbsent(scope, k -> ConcurrentHashMap.newKeySet()).add(streamId);
		streamScopes.put(streamId, scope);
	}

	private static void detachFromScope(String streamId) {
		if (streamId == null) return;
		Object scope = streamScopes.remove(streamId);
		if (scope != null) {
			Set<String> ids = scopeStreams.get(scope);
			if (ids != null) ids.remove(streamId);
		}
	}

	/// Abort ONE outbound LLM exchange and drop it from the registry: close its response body if the
	/// headers have arrived (closing the socket is what makes the model server stop generating), and
	/// cancel the raw future if they have not (the request is still queued upstream). Per-stream form
	/// of {@link #stopAllStreams()}; idempotent and exception-safe - a failed abort is logged at debug
	/// and never thrown, because it must not replace whatever error the caller is already handling.
	/// Returns true when the stream was registered and an abort was attempted.
	public static boolean abortStream(String streamId) {
		if (streamId == null) return false;
		CompletableFuture<HttpResponse<Stream<String>>> future = activeStreams.remove(streamId);
		boolean closed = closeHttpResponse(streamId);
		boolean cancelled = false;
		if (future != null) {
			try {
				if (!future.isDone()) {
					cancelled = future.cancel(true);
				}
			} catch (Exception e) {
				logger.debug("abortStream: cancel future " + streamId + ": " + e.getMessage());
			}
		}
		streamLabels.remove(streamId);
		detachFromScope(streamId);
		boolean known = (future != null || closed);
		if (known) {
			logger.info("abortStream " + streamId + ": closedResponseBody=" + closed + " cancelledFuture=" + cancelled);
		}
		return known;
	}

	/// Register a new streaming future. Returns the stream ID for later cleanup.
	/// The label is taken from the thread-local set by the caller (or defaults
	/// to "chat") so each active stream is identifiable in the debug view.
	public static String registerStream(CompletableFuture<HttpResponse<Stream<String>>> future) {
		String streamId = "stream-" + streamIdCounter.incrementAndGet();
		activeStreams.put(streamId, future);
		String label = currentCallLabel.get();
		if (label == null || label.isEmpty()) label = "chat";
		streamLabels.put(streamId, label + "|" + System.currentTimeMillis());
		attachToScope(streamId);
		return streamId;
	}

	/// Overload that lets callers supply the label explicitly instead of via
	/// the thread-local.
	public static String registerStream(String label, CompletableFuture<HttpResponse<Stream<String>>> future) {
		String streamId = "stream-" + streamIdCounter.incrementAndGet();
		activeStreams.put(streamId, future);
		String safeLabel = (label == null || label.isEmpty()) ? "chat" : label;
		streamLabels.put(streamId, safeLabel + "|" + System.currentTimeMillis());
		attachToScope(streamId);
		return streamId;
	}

	/// Register the HTTP response once the connection is established.
	public static void registerHttpResponse(String streamId, HttpResponse<Stream<String>> response) {
		if (streamId != null && response != null) {
			activeHttpResponses.put(streamId, response);
		}
	}

	/// Close and release the HTTP response body registered for ONE stream.
	///
	/// This is the per-stream form of stopAllStreams()'s Phase 1 and is the primitive that actually
	/// aborts an outbound LLM exchange: closing the body stream closes the socket, so the model
	/// server sees the client go away and can drop the generation instead of finishing it for
	/// nobody while the next request queues behind it. Cancelling a future does NOT do this once the
	/// response headers have arrived (BodyHandlers.ofLines() completes the future at headers, so the
	/// future is already done while generation is still streaming).
	///
	/// Idempotent and exception-safe: removes the entry first, so a second call is a no-op, and a
	/// close that throws is logged at debug and swallowed — an abort that fails must never replace
	/// the error the caller is already handling. Returns true when a registered response was found
	/// and close() was attempted.
	public static boolean closeHttpResponse(String streamId) {
		if (streamId == null) {
			return false;
		}
		HttpResponse<Stream<String>> response = activeHttpResponses.remove(streamId);
		if (response == null) {
			return false;
		}
		try {
			response.body().close();
		} catch (Exception e) {
			logger.debug("closeHttpResponse: close response body " + streamId + ": " + e.getMessage());
		}
		return true;
	}

	/// Remove a completed/failed stream from the registry.
	public static void unregisterStream(String streamId) {
		if (streamId != null) {
			activeStreams.remove(streamId);
			activeHttpResponses.remove(streamId);
			streamLabels.remove(streamId);
			detachFromScope(streamId);
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
		/// Every stream is gone, so no scope has anything left to abort; the scope objects
		/// themselves are released by their operations when they unwind.
		streamScopes.clear();
		scopeStreams.clear();
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
