package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.LLMConnectionManager;
import org.junit.Test;

/// DETERMINISTIC coverage (no live LLM, no network beyond 127.0.0.1) for the give-up abort path in
/// Chat.chatInternal — i.e. "when AM7 stops waiting for an LLM call, does it actually tear the
/// outbound HTTP exchange down, or does it leave the model server generating for a caller that is
/// already gone?"
///
/// That question is what turns a slow model server into a PERMANENTLY saturated one: an abandoned
/// exchange keeps the generation slot busy, and the caller's retry queues a second request behind
/// the first (measured 2026-09-14 — two threads looping timeout->retry left Ollama unable to answer
/// a trivial "Say OK" within 90s while /api/tags replied in 23ms).
///
/// The observation point is a RAW SOCKET, not a mock: BlackholeServer accepts the connection, reads
/// the request, then stops cooperating and blocks on read(). EOF/reset on that read can only be
/// caused by the CLIENT closing the connection, so `clientDisconnected` is direct evidence that the
/// exchange was torn down — not an assertion about internal bookkeeping that might be cosmetic.
///
/// There are exactly two failure SHAPES and they need two different primitives:
///
///   Shape A — the request is queued upstream and the response HEADERS NEVER ARRIVE. The timeout
///     completes our stage exceptionally, whenComplete runs, latch.countDown() fires, so
///     latch.await() returns TRUE and the only pre-existing abort call site
///     (`if (!latch.await(...))`) is SKIPPED; flow falls through to the bufferError branch. Nothing
///     is registered to close (no response yet), so the primitive that works is cancelling the RAW
///     sendAsync future.
///
///   Shape B — the HEADERS ARRIVE and generation runs past the caller's patience. BodyHandlers
///     .ofLines() completes the future as soon as headers are read, so the future is already DONE
///     and cancel() is a guaranteed no-op; the primitive that works is closing the response BODY,
///     which closes the socket (the same primitive LLMConnectionManager.stopAllStreams() Phase 1
///     and Chat's idle watchdog already use).
///
/// Test order of argument:
///   1. CONTROL — the PREVIOUS give-up shape leaves the exchange live. Without this the positive
///      assertions would be vacuous, because a socket can close for many reasons.
///   2/3. Each abort primitive in isolation, with NO timer running that could be responsible.
///   4/5. The two shapes end-to-end through Chat.chat().
///   6. The measurement that decides where the transport timeout may be applied.
///
/// Extends BaseTest only so the Olio model schemas are registered (OpenAIRequest's constructor goes
/// through RecordFactory). No live LLM call is made, nothing is written, no schema is reset.
public class TestLLMAbortOnTimeout extends BaseTest {

	/// Chat's buffer-mode give-up budget is requestTimeout for the orTimeout and requestTimeout + 5
	/// for both the latch and the transport backstop. Chat's mid-stream idle watchdog defaults to
	/// 30s when there is no chatConfig, so it cannot be what closes the socket inside the 20s
	/// observation window below — the abort under test is the only candidate.
	private static final int REQUEST_TIMEOUT_SECONDS = 4;
	private static final int OBSERVE_SECONDS = 20;

	/// A raw-socket stand-in for a model server that has stopped answering.
	///
	/// sendHeaders=false -> never writes anything at all (Shape A).
	/// sendHeaders=true  -> writes SSE response headers immediately, then never writes a chunk
	///                      (Shape B), unless streamChunks is set.
	/// streamChunks > 0  -> writes that many SSE chunks, 800ms apart, after the headers (used only
	///                      by the request-timeout scope measurement).
	///
	/// The server never closes the connection itself, so the only way its read loop can end is the
	/// client tearing the exchange down.
	private static final class BlackholeServer implements AutoCloseable {
		private final ServerSocket ss;
		private final Thread thread;
		private final boolean sendHeaders;
		private final int streamChunks;
		final CountDownLatch requestReceived = new CountDownLatch(1);
		final CountDownLatch clientDisconnected = new CountDownLatch(1);
		volatile long acceptedAtMs = -1;
		volatile long disconnectedAtMs = -1;
		volatile String endReason = "still-connected";

		BlackholeServer(boolean sendHeaders) throws Exception {
			this(sendHeaders, 0);
		}

		BlackholeServer(boolean sendHeaders, int streamChunks) throws Exception {
			this.sendHeaders = sendHeaders;
			this.streamChunks = streamChunks;
			this.ss = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
			this.thread = new Thread(this::run, "blackhole-" + ss.getLocalPort());
			this.thread.setDaemon(true);
			this.thread.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + ss.getLocalPort();
		}

		String chatUrl() {
			return baseUrl() + "/api/chat";
		}

		private void run() {
			try (Socket sock = ss.accept()) {
				acceptedAtMs = System.currentTimeMillis();
				InputStream in = sock.getInputStream();
				/// Read to the end of the request headers so we know the client really sent the
				/// request; the body follows and is simply consumed by the drain loop below.
				int[] last = new int[4];
				int n = 0;
				int b;
				while ((b = in.read()) != -1) {
					last[n % 4] = b;
					n++;
					if (n >= 4
							&& last[(n - 4) % 4] == '\r' && last[(n - 3) % 4] == '\n'
							&& last[(n - 2) % 4] == '\r' && last[(n - 1) % 4] == '\n') {
						break;
					}
				}
				requestReceived.countDown();
				if (sendHeaders) {
					OutputStream out = sock.getOutputStream();
					out.write(("HTTP/1.1 200 OK\r\n"
							+ "Content-Type: text/event-stream\r\n"
							+ "Cache-Control: no-cache\r\n"
							+ "Transfer-Encoding: chunked\r\n"
							+ "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
					out.flush();
					for (int i = 0; i < streamChunks; i++) {
						String payload = "{\"message\":{\"content\":\"tok" + i + "\"},\"done\":false}\n";
						out.write((Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n")
								.getBytes(StandardCharsets.UTF_8));
						out.flush();
						Thread.sleep(800);
					}
					/// ...and now generate nothing more, forever. The saturated-server case.
				}
				byte[] sink = new byte[1024];
				while (in.read(sink) != -1) {
					/// drain the request body and anything else, but never respond
				}
				endReason = "EOF";
			} catch (Exception e) {
				endReason = e.getClass().getSimpleName() + ": " + e.getMessage();
			} finally {
				disconnectedAtMs = System.currentTimeMillis();
				requestReceived.countDown();
				clientDisconnected.countDown();
			}
		}

		long msFromRequestToDisconnect() {
			if (acceptedAtMs < 0 || disconnectedAtMs < 0) return -1;
			return disconnectedAtMs - acceptedAtMs;
		}

		@Override
		public void close() {
			try { ss.close(); } catch (Exception e) { /* teardown */ }
			thread.interrupt();
		}
	}

	private static OpenAIRequest bufferModeRequest() {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("qwen3:8b");
		/// stream=false is BUFFER mode — the mode every extraction / summarize / compliance / ISO
		/// call uses, and precisely where the long timeouts happen.
		req.setStream(false);
		OpenAIMessage m = new OpenAIMessage();
		m.setRole("user");
		m.setContent("Say OK");
		req.addMessage(m);
		return req;
	}

	private static Chat chatPointedAt(String baseUrl) {
		Chat chat = new Chat();
		chat.setServiceType(LLMServiceEnumType.OLLAMA);
		chat.setServerUrl(baseUrl);
		chat.setRequestTimeout(REQUEST_TIMEOUT_SECONDS);
		return chat;
	}

	/// (1) CONTROL — the give-up shape as it was BEFORE this change: the 3-arg transport (no
	/// HttpRequest timeout), orTimeout applied to a DERIVED stage, and a cancel() on that derived
	/// stage. Reproduces both reasons the old cancelOutbound could not work:
	///   - orTimeout completes the derived stage, so cancelOutbound's `if (f == null || f.isDone())
	///     return;` guard returned immediately without calling cancel at all, and
	///   - even if it had, a future DERIVED from a cancelable future is "not necessarily cancelable"
	///     (JDK HttpClient.sendAsync implNote).
	/// Expected: the exchange stays LIVE. This is what kept the model server busy.
	@Test
	public void testControl_oldGiveUpShapeLeavesExchangeLive() throws Exception {
		try (BlackholeServer server = new BlackholeServer(false)) {
			CompletableFuture<HttpResponse<Stream<String>>> raw =
				ClientUtil.postToRecordAndStream(server.chatUrl(), null, "{\"model\":\"x\"}");
			CompletableFuture<HttpResponse<Stream<String>>> derived =
				raw.whenComplete((r, e) -> { /* the old unregisterClient bookkeeping stage */ });
			derived = derived.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			assertTrue("blackhole server never saw the request",
				server.requestReceived.await(15, TimeUnit.SECONDS));

			try {
				derived.get(REQUEST_TIMEOUT_SECONDS + 6, TimeUnit.SECONDS);
			} catch (Exception expected) {
				logger.info("[ABORT-CTRL] derived stage completed as expected: "
					+ expected.getClass().getSimpleName());
			}
			boolean oldCancelWouldHaveRun = !derived.isDone();
			boolean cancelled = derived.cancel(true);
			logger.info("[ABORT-CTRL] old cancelOutbound would have reached cancel: "
				+ oldCancelWouldHaveRun + "; cancel() on the derived stage returned: " + cancelled);

			boolean closed = server.clientDisconnected.await(10, TimeUnit.SECONDS);
			logger.info("[ABORT-CTRL] exchange torn down within 10s of give-up? " + closed
				+ " (endReason=" + server.endReason + ")");
			assertFalse("CONTROL FAILED: the pre-fix give-up shape is supposed to leave the exchange"
				+ " LIVE. If it does not, every positive assertion in this class proves nothing,"
				+ " because the socket is closing for some reason other than the abort.", closed);

			/// Don't leak the abandoned exchange out of the test — this is exactly the leak the
			/// production fix removes; here it is cleaned up by hand.
			raw.cancel(true);
		}
	}

	/// (2) Fix (b) in isolation: ClientUtil.postToRecordAndStream now returns the RAW sendAsync
	/// future instead of a derived bookkeeping stage, so cancel() reaches the exchange. Run with
	/// requestTimeoutSeconds=0 so no JDK timer exists that could be credited with the teardown —
	/// the cancel is the only candidate. Compare with the control above, which is the same scenario
	/// through a derived stage and does NOT close.
	@Test
	public void testRawFutureCancelAbortsExchange() throws Exception {
		try (BlackholeServer server = new BlackholeServer(false)) {
			CompletableFuture<HttpResponse<Stream<String>>> raw =
				ClientUtil.postToRecordAndStream(server.chatUrl(), null, "{\"model\":\"x\"}", null, 0);
			assertTrue("blackhole server never saw the request",
				server.requestReceived.await(15, TimeUnit.SECONDS));
			assertFalse("the exchange must still be live before the cancel",
				server.clientDisconnected.await(2, TimeUnit.SECONDS));

			boolean cancelled = raw.cancel(true);
			boolean closed = server.clientDisconnected.await(10, TimeUnit.SECONDS);
			logger.info("[ABORT-RAW] cancel() returned " + cancelled + "; exchange torn down? "
				+ closed + " (endReason=" + server.endReason + ")");
			assertTrue("cancel() on the RAW sendAsync future must abort the exchange", cancelled);
			assertTrue("cancelling the raw future did not tear the exchange down — the handle"
				+ " callers hold is still not cancelable", closed);
		}
	}

	/// (3) Fix (d) phase 1 in isolation: LLMConnectionManager.closeHttpResponse(streamId) closes the
	/// registered body for ONE exchange. This is the only primitive that works once the response
	/// headers have arrived, because BodyHandlers.ofLines() has already completed the future.
	/// Again run with requestTimeoutSeconds=0 so no timer can be responsible, and with a reader
	/// thread blocked on the body exactly as Chat's thenAccept is.
	@Test
	public void testCloseHttpResponseAbortsStalledBody() throws Exception {
		try (BlackholeServer server = new BlackholeServer(true)) {
			CompletableFuture<HttpResponse<Stream<String>>> raw =
				ClientUtil.postToRecordAndStream(server.chatUrl(), null, "{\"model\":\"x\"}", null, 0);
			HttpResponse<Stream<String>> resp = raw.get(15, TimeUnit.SECONDS);
			logger.info("[ABORT-CLOSE] response headers arrived, status=" + resp.statusCode()
				+ "; future done at headers? " + raw.isDone());

			String streamId = LLMConnectionManager.registerStream("test:abort", raw);
			LLMConnectionManager.registerHttpResponse(streamId, resp);

			CountDownLatch readerFinished = new CountDownLatch(1);
			Thread reader = new Thread(() -> {
				try {
					Iterator<String> it = resp.body().iterator();
					while (it.hasNext()) {
						it.next();
					}
				} catch (Exception e) {
					logger.info("[ABORT-CLOSE] reader unwound: " + e.getClass().getSimpleName());
				} finally {
					readerFinished.countDown();
				}
			}, "abort-test-reader");
			reader.setDaemon(true);
			reader.start();

			assertFalse("the exchange must still be live before the close",
				server.clientDisconnected.await(2, TimeUnit.SECONDS));
			/// cancel() is a no-op here — this is what made the old cancelOutbound useless for
			/// Shape B — so assert it, then show close DOES work.
			logger.info("[ABORT-CLOSE] future already completed at headers, so cancel() returns "
				+ raw.cancel(true));

			assertTrue("closeHttpResponse must find the registered response",
				LLMConnectionManager.closeHttpResponse(streamId));
			boolean closed = server.clientDisconnected.await(10, TimeUnit.SECONDS);
			logger.info("[ABORT-CLOSE] exchange torn down? " + closed + " after "
				+ server.msFromRequestToDisconnect() + "ms (endReason=" + server.endReason + ")");
			assertTrue("closing the registered response body must close the socket", closed);
			assertTrue("the blocked reader must unwind", readerFinished.await(10, TimeUnit.SECONDS));
			assertFalse("closeHttpResponse must be idempotent — a second call is a no-op",
				LLMConnectionManager.closeHttpResponse(streamId));

			LLMConnectionManager.unregisterStream(streamId);
		}
	}

	/// (4) SHAPE A end-to-end — headers never arrive. The old code could not abort this AT ALL: the
	/// timeout counts the latch down, so latch.await() returns true and the only abort call site is
	/// skipped. Asserts the exchange is now torn down.
	@Test
	public void testShapeA_timeoutBeforeHeadersAbortsExchange() throws Exception {
		try (BlackholeServer server = new BlackholeServer(false)) {
			int baseline = LLMConnectionManager.getActiveLLMCallCount();
			Chat chat = chatPointedAt(server.baseUrl());

			long start = System.currentTimeMillis();
			OpenAIResponse resp = chat.chat(bufferModeRequest());
			long elapsed = System.currentTimeMillis() - start;
			logger.info("[ABORT-A] chat() returned after " + elapsed + "ms, resp="
				+ (resp == null ? "null" : "present") + ", lastCallError=" + Chat.getLastCallError());

			assertNull("buffer-mode chat against a blackhole must give up and return null", resp);
			assertTrue("blackhole server never saw the request",
				server.requestReceived.await(5, TimeUnit.SECONDS));

			boolean closed = server.clientDisconnected.await(OBSERVE_SECONDS, TimeUnit.SECONDS);
			logger.info("[ABORT-A] exchange torn down? " + closed + " after "
				+ server.msFromRequestToDisconnect() + "ms (endReason=" + server.endReason + ")");
			assertTrue("Shape A: giving up on the LLM call must ABORT the outbound exchange. The"
				+ " socket was still open " + OBSERVE_SECONDS + "s later, which means the model"
				+ " server is still generating for a caller that is gone.", closed);

			assertTrue("active LLM call registry did not return to baseline (" + baseline + ")",
				awaitCallCount(baseline, OBSERVE_SECONDS));
		}
	}

	/// (5) SHAPE B end-to-end — headers arrived, generation never produces a chunk. cancel() is a
	/// no-op here, so this can only pass if the abort closes the registered response body.
	@Test
	public void testShapeB_stalledGenerationAfterHeadersAbortsExchange() throws Exception {
		try (BlackholeServer server = new BlackholeServer(true)) {
			int baseline = LLMConnectionManager.getActiveLLMCallCount();
			Chat chat = chatPointedAt(server.baseUrl());

			long start = System.currentTimeMillis();
			OpenAIResponse resp = chat.chat(bufferModeRequest());
			long elapsed = System.currentTimeMillis() - start;
			logger.info("[ABORT-B] chat() returned after " + elapsed + "ms, resp="
				+ (resp == null ? "null" : "present") + ", lastCallError=" + Chat.getLastCallError());

			assertNull("buffer-mode chat against a stalled generation must give up and return null", resp);
			assertTrue("blackhole server never saw the request",
				server.requestReceived.await(5, TimeUnit.SECONDS));

			boolean closed = server.clientDisconnected.await(OBSERVE_SECONDS, TimeUnit.SECONDS);
			logger.info("[ABORT-B] exchange torn down? " + closed + " after "
				+ server.msFromRequestToDisconnect() + "ms (endReason=" + server.endReason + ")");
			assertTrue("Shape B: giving up after the response headers arrived must CLOSE the"
				+ " response body (cancel() is a no-op once ofLines() has completed the future)."
				+ " The socket was still open " + OBSERVE_SECONDS + "s later.", closed);

			assertTrue("active LLM call registry did not return to baseline (" + baseline + ")",
				awaitCallCount(baseline, OBSERVE_SECONDS));
		}
	}

	/// (6) The MEASUREMENT behind Chat's decision to apply HttpRequest.timeout() in BUFFER MODE
	/// ONLY. The javadoc for HttpRequest.Builder.timeout says "if the response is not received
	/// within the specified timeout", which reads as though it bounds only the wait for the
	/// response — but it bounds the WHOLE exchange: a response actively delivering a chunk every
	/// 800ms is killed mid-stream at the deadline. Applying it to interactive streaming would
	/// therefore cut off any conversation that streams for longer than requestTimeout (120s by
	/// default) even while tokens are flowing.
	///
	/// If a future JDK changes this, this test goes red and Chat's buffer-mode-only restriction can
	/// be revisited — that is the point of asserting it rather than leaving it in a comment.
	@Test
	public void testRequestTimeoutBoundsTheWholeExchangeNotJustHeaders() throws Exception {
		try (BlackholeServer server = new BlackholeServer(true, 15)) {
			long start = System.currentTimeMillis();
			CompletableFuture<HttpResponse<Stream<String>>> raw = ClientUtil.postToRecordAndStream(
				server.chatUrl(), null, "{\"model\":\"x\"}", null, REQUEST_TIMEOUT_SECONDS);
			HttpResponse<Stream<String>> resp = raw.get(15, TimeUnit.SECONDS);
			long headersAt = System.currentTimeMillis() - start;

			int lines = 0;
			String failure = "none — the stream completed normally";
			try {
				Iterator<String> it = resp.body().iterator();
				while (it.hasNext()) {
					it.next();
					lines++;
				}
			} catch (Exception e) {
				failure = e.getClass().getName() + ": " + e.getMessage();
			}
			long endedAt = System.currentTimeMillis() - start;
			logger.info("[ABORT-SCOPE] requestTimeout=" + REQUEST_TIMEOUT_SECONDS + "s, headers at "
				+ headersAt + "ms, " + lines + " chunk(s) delivered, stream ended at " + endedAt
				+ "ms with: " + failure);

			assertTrue("headers must arrive well before the deadline for this measurement to mean"
				+ " anything (got " + headersAt + "ms)", headersAt < 2000);
			assertTrue("the chunk stream must have been running when the deadline hit (got "
				+ lines + " chunks)", lines >= 2);
			assertTrue("HttpRequest.timeout() is expected to kill an ACTIVELY STREAMING response at"
				+ " the deadline; it ended at " + endedAt + "ms with: " + failure,
				endedAt < (REQUEST_TIMEOUT_SECONDS + 3) * 1000L && !failure.startsWith("none"));
		}
	}

	private static boolean awaitCallCount(int expected, int seconds) throws InterruptedException {
		long deadline = System.currentTimeMillis() + (seconds * 1000L);
		while (System.currentTimeMillis() < deadline) {
			if (LLMConnectionManager.getActiveLLMCallCount() <= expected) {
				return true;
			}
			Thread.sleep(250);
		}
		logger.error("active LLM calls still " + LLMConnectionManager.getActiveLLMCallCount()
			+ " (expected <= " + expected + "): " + LLMConnectionManager.snapshotActiveLLMCalls());
		return false;
	}
}
