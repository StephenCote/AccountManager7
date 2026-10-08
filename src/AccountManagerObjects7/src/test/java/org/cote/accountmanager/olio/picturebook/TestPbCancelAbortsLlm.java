package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.LLMConnectionManager;
import org.junit.Before;
import org.junit.Test;

/// KI-74 (audit 2026-10-07 item 5): PictureBook cancel used to set a flag the extraction loops polled
/// BETWEEN LLM calls, so the call already running generated to completion first (minutes, on a long
/// chunk) for a caller that had given up. Now PictureBookCancelRegistry.register binds the token as the
/// worker thread's LLMConnectionManager cancel scope, every stream Chat registers from that thread is
/// attached to it, and cancel() aborts those streams (close response body + cancel request future - the
/// same two primitives the abort-all drains) in addition to setting the flag.
///
/// Two halves: a pure registry test with a fake never-completing future (no LLM), and a live test that
/// starts a long generation on the resolved LLM route, cancels from another thread, and asserts Chat.chat
/// returns within seconds rather than after the full generation. Quote the [LLM-GATE] line with the result.
public class TestPbCancelAbortsLlm extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		OlioModelNames.use();
	}

	/// Pure: a stream registered on the token's thread is aborted by the OWNER's cancel (and only the
	/// owner's), a stream registered with no scope is untouched, and unregister clears the binding.
	@Test
	public void TestCancelAbortsScopedStreamOnly() throws Exception {
		BaseRecord user = getCreateUser("pbCancelAbortOwner");
		BaseRecord other = getCreateUser("pbCancelAbortOther");
		assertNotNull(user);
		assertNotNull(other);
		String key = "pure-" + shortId();
		int baseline = LLMConnectionManager.getActiveStreamCount();

		assertNull("no scope bound before register", LLMConnectionManager.currentCancelScope());
		SummarizeProgress token = PictureBookCancelRegistry.register(user, key);
		CompletableFuture<HttpResponse<Stream<String>>> scoped = new CompletableFuture<>();
		CompletableFuture<HttpResponse<Stream<String>>> unscoped = new CompletableFuture<>();
		final AtomicReference<String> unscopedId = new AtomicReference<>();
		try {
			assertSame("register binds the token as this thread's cancel scope", token, LLMConnectionManager.currentCancelScope());
			String scopedId = LLMConnectionManager.registerStream("pbCancelTest.scoped", scoped);
			assertNotNull(scopedId);
			assertEquals("the stream registered on the bound thread is attached to the token", 1, PictureBookCancelRegistry.inFlightLlmCalls(token));

			/// A stream registered from a thread with NO scope (another user's chat, say) is not ours.
			Thread t = new Thread(() -> unscopedId.set(LLMConnectionManager.registerStream("pbCancelTest.unscoped", unscoped)), "pb-cancel-unscoped");
			t.start();
			t.join(5000L);
			assertNotNull(unscopedId.get());
			assertEquals(baseline + 2, LLMConnectionManager.getActiveStreamCount());

			/// Another principal's cancel on the same client key misses - nothing aborted, token untouched.
			assertFalse("another user's cancel is a miss", PictureBookCancelRegistry.cancel(other, key));
			assertFalse(token.isCancelled());
			assertFalse(scoped.isCancelled());
			assertEquals(1, PictureBookCancelRegistry.inFlightLlmCalls(token));

			/// The owner's cancel, from a different thread (as the REST cancel always is).
			AtomicBoolean cancelled = new AtomicBoolean(false);
			Thread c = new Thread(() -> cancelled.set(PictureBookCancelRegistry.cancel(user, key)), "pb-cancel-caller");
			c.start();
			c.join(5000L);
			assertTrue("owner's cancel is honoured", cancelled.get());
			assertTrue("flag set", token.isCancelled());
			assertTrue("the scoped stream's request future was cancelled", scoped.isCancelled());
			assertFalse("the unscoped stream was not touched", unscoped.isCancelled());
			assertEquals("aborted stream detached from the token", 0, PictureBookCancelRegistry.inFlightLlmCalls(token));
			assertEquals("aborted stream removed from the global registry; the unscoped one remains",
				baseline + 1, LLMConnectionManager.getActiveStreamCount());
			assertFalse("second cancel is a no-op (already cancelled)", PictureBookCancelRegistry.cancel(user, key));
		}
		finally {
			PictureBookCancelRegistry.unregister(user, key, token);
			/// The unscoped fake is this test's own; take it out of the registry so nothing leaks.
			LLMConnectionManager.unregisterStream(unscopedId.get());
			unscoped.cancel(true);
		}
		assertEquals("registry back at baseline", baseline, LLMConnectionManager.getActiveStreamCount());
		assertNull("unregister clears the thread's binding", LLMConnectionManager.currentCancelScope());
		assertEquals("no bookkeeping left for the token", 0, LLMConnectionManager.getCancelScopeStreamCount(token));
	}

	/// Live: a long generation in flight on the worker thread is aborted by cancel from the main thread;
	/// Chat.chat returns within seconds (null - the exchange was torn down), the token's in-flight count
	/// is back to zero and the stream is gone from the registry.
	@Test
	public void TestCancelAbortsInFlightLlmCall() throws Exception {
		BaseRecord user = getCreateUser("pbCancelAbortOwner");
		assertNotNull(user);
		final BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(user, "pbCancelAbort-" + shortId(), testProperties);
		assertNotNull("chatConfig on the resolved LLM route", cfg);
		logger.info("LLM route: " + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier"));
		final String key = "live-" + shortId();
		final int baseline = LLMConnectionManager.getActiveStreamCount();

		final CountDownLatch registered = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(1);
		final AtomicReference<SummarizeProgress> tokenRef = new AtomicReference<>();
		final AtomicReference<OpenAIResponse> respRef = new AtomicReference<>();
		final AtomicReference<Throwable> errRef = new AtomicReference<>();
		final AtomicLong chatMs = new AtomicLong(-1L);

		Thread worker = new Thread(() -> {
			SummarizeProgress token = PictureBookCancelRegistry.register(user, key);
			tokenRef.set(token);
			registered.countDown();
			try {
				Chat chat = new Chat(user, cfg, null);
				chat.setLlmSystemPrompt("You are a tireless storyteller. You never summarize and never stop early.");
				OpenAIRequest req = chat.newRequest(chat.getModel());
				req.setStream(false);
				chat.newMessage(req, "Write a 4000-word adventure story about a lighthouse keeper and a storm."
					+ " Number every paragraph. Each paragraph must be at least 120 words. Do not stop until"
					+ " you have written at least 30 numbered paragraphs.");
				Chat.clearLastCallError();
				long t0 = System.currentTimeMillis();
				respRef.set(chat.chat(req));
				chatMs.set(System.currentTimeMillis() - t0);
			}
			catch (Throwable t) {
				errRef.set(t);
			}
			finally {
				PictureBookCancelRegistry.unregister(user, key, tokenRef.get());
				done.countDown();
			}
		}, "pb-cancel-worker");
		worker.setDaemon(true);
		worker.start();

		assertTrue("worker registered its token", registered.await(10, TimeUnit.SECONDS));
		SummarizeProgress token = tokenRef.get();
		assertNotNull(token);

		/// Wait for Chat to register its stream on the worker's scope (connection setup + request build).
		long deadline = System.currentTimeMillis() + 60_000L;
		while (PictureBookCancelRegistry.inFlightLlmCalls(token) == 0 && done.getCount() > 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(50L);
		}
		assertEquals("the worker is still inside chat()", 1L, done.getCount());
		assertEquals("Chat registered its stream on the worker's cancel scope", 1, PictureBookCancelRegistry.inFlightLlmCalls(token));
		/// Let the model actually start generating so the abort exercises the mid-body path too.
		Thread.sleep(3000L);
		assertEquals("still generating before cancel", 1L, done.getCount());

		long cancelAt = System.currentTimeMillis();
		assertTrue("owner's cancel is honoured", PictureBookCancelRegistry.cancel(user, key));
		assertTrue("chat() returned after cancel (did not generate to completion)", done.await(30, TimeUnit.SECONDS));
		long returnedAfterMs = System.currentTimeMillis() - cancelAt;
		logger.info("KI-74: cancel -> chat() returned after " + returnedAfterMs + "ms (chat() total " + chatMs.get()
			+ "ms); lastCallError=" + Chat.getLastCallError());

		assertNull("worker did not throw: " + errRef.get(), errRef.get());
		assertTrue("returned within 15s of the cancel, not after the full generation (" + returnedAfterMs + "ms)", returnedAfterMs < 15_000L);
		OpenAIResponse resp = respRef.get();
		/// A torn-down exchange yields no reply; if the transport unwinds quietly Chat may hand back the
		/// partial text instead - either way it must not be a complete 30-paragraph story.
		if (resp != null && resp.getMessage() != null && resp.getMessage().getContent() != null) {
			String content = resp.getMessage().getContent();
			logger.info("KI-74: partial content length after abort = " + content.length());
			assertFalse("the reply is not the full requested generation", content.contains("30.") && content.length() > 20_000);
		}
		assertTrue(token.isCancelled());
		assertEquals("no stream left on the token", 0, PictureBookCancelRegistry.inFlightLlmCalls(token));
		assertEquals("stream gone from the global registry", baseline, LLMConnectionManager.getActiveStreamCount());
		assertEquals("token bookkeeping released by unregister", 0, LLMConnectionManager.getCancelScopeStreamCount(token));
	}
}
