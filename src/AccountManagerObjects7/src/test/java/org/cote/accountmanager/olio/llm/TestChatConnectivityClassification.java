package org.cote.accountmanager.olio.llm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

/// Chat's buffer-mode error classification. Lives in this package to reach the package-private
/// isConnectivityFailure.
///
/// The defect this pins: HttpConnectTimeoutException EXTENDS HttpTimeoutException, so a host that
/// had dropped off the network was reported as "Request timed out after 900 seconds" after a 10s
/// TCP connect failure. The extraction loop treats a timeout as "slow model, still alive" and keeps
/// going — so with the Ollama host unplugged it skipped every chunk of a chapter in 140s, cleared
/// its checkpoint and reported COMPLETED with zero scenes. Measured 2026-09-25.
public class TestChatConnectivityClassification {

	/// The HttpClient future completes exceptionally with the cause wrapped — this is the shape
	/// whenComplete actually receives, so the walker must look through the wrapper.
	@Test
	public void TestConnectTimeoutWrappedInCompletionExceptionIsConnectivity() {
		Throwable t = new CompletionException(new HttpConnectTimeoutException("HTTP connect timed out"));
		assertTrue("a wrapped connect timeout means the host could not be reached",
				Chat.isConnectivityFailure(t));
	}

	@Test
	public void TestBareConnectTimeoutIsConnectivity() {
		assertTrue(Chat.isConnectivityFailure(new HttpConnectTimeoutException("HTTP connect timed out")));
	}

	@Test
	public void TestConnectionRefusedIsConnectivity() {
		Throwable t = new CompletionException(new ConnectException("Connection refused"));
		assertTrue(Chat.isConnectivityFailure(t));
	}

	@Test
	public void TestUnknownHostAndNoRouteAreConnectivity() {
		assertTrue(Chat.isConnectivityFailure(new CompletionException(new UnknownHostException("ollama.local"))));
		assertTrue(Chat.isConnectivityFailure(new CompletionException(new NoRouteToHostException("No route to host"))));
	}

	/// Deeper nesting: IOException wrapping the connect failure, itself wrapped by the future.
	@Test
	public void TestConnectivityIsFoundAnywhereInTheCauseChain() {
		Throwable t = new CompletionException(new IOException("send failed",
				new ConnectException("Connection refused")));
		assertTrue(Chat.isConnectivityFailure(t));
	}

	/// THE DISTINCTION. A plain request timeout means the server ACCEPTED the connection and was
	/// too slow to answer — the model is alive. It must NOT be classified as unreachable, or the
	/// loop would abort a whole document because the model had a slow spell (the regression
	/// TestExtractChunkLoop.TestSlowTimeoutsDoNotTripTheBreaker guards).
	@Test
	public void TestRequestTimeoutIsNotConnectivity() {
		assertFalse("HttpTimeoutException (request timed out) is a live-but-slow server",
				Chat.isConnectivityFailure(new CompletionException(new HttpTimeoutException("request timed out"))));
		assertFalse("java.util.concurrent.TimeoutException from our own get(timeout) is the same",
				Chat.isConnectivityFailure(new CompletionException(new TimeoutException())));
		assertFalse(Chat.isConnectivityFailure(new HttpTimeoutException("request timed out")));
	}

	@Test
	public void TestOtherErrorsAreNotConnectivity() {
		assertFalse(Chat.isConnectivityFailure(new CompletionException(new IOException("broken pipe"))));
		assertFalse(Chat.isConnectivityFailure(new IllegalStateException("bad state")));
		assertFalse(Chat.isConnectivityFailure(null));
	}

	/// A self-referential cause chain must not spin the walker forever.
	@Test
	public void TestCyclicCauseChainTerminates() {
		RuntimeException a = new RuntimeException("a");
		RuntimeException b = new RuntimeException("b", a);
		a.initCause(b);
		assertFalse(Chat.isConnectivityFailure(a));
	}
}
