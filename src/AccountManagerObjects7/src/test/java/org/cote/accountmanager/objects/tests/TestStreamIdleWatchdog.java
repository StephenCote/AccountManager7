package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.olio.llm.StreamIdleWatchdog;
import org.junit.After;
import org.junit.Test;

/// Unit tests for the per-stream idle watchdog. Pure-logic tests for the
/// `isIdle` helper plus a couple of scheduler-based tests that confirm
/// onIdle fires for a stalled stream and does NOT fire for an active one.
public class TestStreamIdleWatchdog {

	private StreamIdleWatchdog watchdog;

	@After
	public void tearDown() {
		if (watchdog != null) watchdog.shutdown();
	}

	// ── isIdle (pure) ───────────────────────────────────────────────────

	@Test
	public void isIdle_disabledWhenTimeoutZero() {
		assertFalse(StreamIdleWatchdog.isIdle(0L, 100_000L, 0L));
		assertFalse(StreamIdleWatchdog.isIdle(0L, 100_000L, -5L));
	}

	@Test
	public void isIdle_falseWhenInsideThreshold() {
		assertFalse(StreamIdleWatchdog.isIdle(0L, 999L, 1000L));
		assertFalse(StreamIdleWatchdog.isIdle(0L, 1000L, 1000L));
	}

	@Test
	public void isIdle_trueWhenPastThreshold() {
		assertTrue(StreamIdleWatchdog.isIdle(0L, 1001L, 1000L));
		assertTrue(StreamIdleWatchdog.isIdle(0L, 10_000L, 1000L));
	}

	@Test
	public void isIdle_negativeElapsedNeverIdle() {
		/// Future timestamp (clock skew) — guard against returning true.
		assertFalse(StreamIdleWatchdog.isIdle(2000L, 1000L, 500L));
	}

	// ── start / stop / touch wiring ────────────────────────────────────

	@Test
	public void startNullStreamIdNoOp() {
		watchdog = new StreamIdleWatchdog(100L);
		watchdog.start(null, "test", 1000L, sid -> { /* ignore */ });
		assertEquals(0, watchdog.activeCount());
	}

	@Test
	public void startZeroTimeoutNoOp() {
		watchdog = new StreamIdleWatchdog(100L);
		watchdog.start("s1", "test", 0L, sid -> { /* ignore */ });
		assertEquals(0, watchdog.activeCount());
	}

	@Test
	public void startNullCallbackNoOp() {
		watchdog = new StreamIdleWatchdog(100L);
		watchdog.start("s1", "test", 1000L, null);
		assertEquals(0, watchdog.activeCount());
	}

	@Test
	public void startThenStopRemovesEntry() {
		watchdog = new StreamIdleWatchdog(60_000L); // long interval so nothing fires
		watchdog.start("s1", "test", 1000L, sid -> { /* ignore */ });
		assertEquals(1, watchdog.activeCount());
		watchdog.stop("s1");
		assertEquals(0, watchdog.activeCount());
	}

	@Test
	public void stopUnknownStreamSafe() {
		watchdog = new StreamIdleWatchdog(60_000L);
		watchdog.stop("never-started"); // must not throw
		watchdog.stop(null); // must not throw
	}

	@Test
	public void touchUnknownStreamSafe() {
		watchdog = new StreamIdleWatchdog(60_000L);
		watchdog.touch("never-started"); // must not throw
		watchdog.touch(null); // must not throw
	}

	// ── scheduler-driven behaviour ─────────────────────────────────────

	@Test
	public void idleStreamFiresOnIdleCallback() throws Exception {
		/// Short check interval + short idle timeout so the test runs fast.
		watchdog = new StreamIdleWatchdog(50L);
		final AtomicInteger fired = new AtomicInteger(0);
		final AtomicReference<String> firedFor = new AtomicReference<>(null);
		watchdog.start("s-idle", "test", 100L, sid -> {
			fired.incrementAndGet();
			firedFor.set(sid);
		});
		/// Wait for two check intervals + idle threshold.
		Thread.sleep(300L);
		assertEquals("onIdle must have fired exactly once", 1, fired.get());
		assertEquals("s-idle", firedFor.get());
		/// After firing, entry should be cleaned up.
		assertEquals(0, watchdog.activeCount());
	}

	@Test
	public void activeStreamDoesNotFire() throws Exception {
		watchdog = new StreamIdleWatchdog(50L);
		final AtomicInteger fired = new AtomicInteger(0);
		watchdog.start("s-active", "test", 200L, sid -> fired.incrementAndGet());
		/// Touch every 50ms for ~500ms — should stay well inside the 200ms idle window.
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < 500L) {
			watchdog.touch("s-active");
			Thread.sleep(50L);
		}
		assertEquals("onIdle must NOT fire for an actively-touched stream", 0, fired.get());
		assertEquals(1, watchdog.activeCount());
		watchdog.stop("s-active");
	}

	@Test
	public void firedOnceEvenIfCheckRunsMultipleTimes() throws Exception {
		watchdog = new StreamIdleWatchdog(30L);
		final AtomicInteger fired = new AtomicInteger(0);
		watchdog.start("s-once", "test", 50L, sid -> fired.incrementAndGet());
		/// Wait long enough that the scheduler would have fired many checks
		/// if not for the `fired` guard.
		Thread.sleep(400L);
		assertEquals("onIdle must fire exactly once", 1, fired.get());
	}

	@Test
	public void callbackExceptionDoesNotKillScheduler() throws Exception {
		watchdog = new StreamIdleWatchdog(30L);
		final AtomicInteger fired = new AtomicInteger(0);
		watchdog.start("s-throw", "test", 50L, sid -> {
			fired.incrementAndGet();
			throw new RuntimeException("boom");
		});
		Thread.sleep(200L);
		assertEquals(1, fired.get());
		/// Start a second stream after the first threw — scheduler should
		/// still be running.
		final AtomicInteger fired2 = new AtomicInteger(0);
		watchdog.start("s-second", "test", 50L, sid -> fired2.incrementAndGet());
		Thread.sleep(200L);
		assertEquals("second stream must still get its onIdle", 1, fired2.get());
	}

	@Test
	public void shutdownClearsEntries() {
		watchdog = new StreamIdleWatchdog(60_000L);
		watchdog.start("s1", "test", 1000L, sid -> { });
		watchdog.start("s2", "test", 1000L, sid -> { });
		assertEquals(2, watchdog.activeCount());
		watchdog.shutdown();
		assertEquals(0, watchdog.activeCount());
		watchdog = null; // prevent tearDown double-shutdown
	}
}
