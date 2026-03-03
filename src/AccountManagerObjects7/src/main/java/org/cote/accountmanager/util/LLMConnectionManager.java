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

	/// Active HTTP responses — registered once the connection is established.
	private static final ConcurrentHashMap<String, HttpResponse<Stream<String>>> activeHttpResponses = new ConcurrentHashMap<>();

	/// Closeable HTTP clients — registered by subsystem name for cleanup on shutdown.
	private static final ConcurrentHashMap<String, AutoCloseable> registeredClients = new ConcurrentHashMap<>();

	/// Stream ID counter — monotonically increasing across all subsystems.
	private static final AtomicLong streamIdCounter = new AtomicLong(0);

	/// Graceful stop flags — keyed by request OID for interactive chat.
	private static final ConcurrentHashMap<String, Boolean> stopFlags = new ConcurrentHashMap<>();

	/// Register a new streaming future. Returns the stream ID for later cleanup.
	public static String registerStream(CompletableFuture<HttpResponse<Stream<String>>> future) {
		String streamId = "stream-" + streamIdCounter.incrementAndGet();
		activeStreams.put(streamId, future);
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
		stopFlags.clear();
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
