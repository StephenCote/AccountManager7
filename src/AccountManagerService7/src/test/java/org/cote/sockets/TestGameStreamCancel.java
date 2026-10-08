package org.cote.sockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

/**
 * Audit 2026-10-07 item 7: {@code GameStreamHandler} answered a client's {@code {actionId, cancel:true}}
 * with an empty {@code game.action.cancel} chirp and did nothing (the {@code // TODO: Implement action
 * cancellation} at the old :84), so the client dropped the action from its active list while the server
 * kept running it. The handler now tracks every submitted action's {@link Future} under session+actionId
 * and {@code cancel(true)}s it on request (and on session close).
 *
 * <p>Pure JUnit - no Tomcat, no DB, no WebSocket container. {@code chirp()} is null-session-safe, so a null
 * {@code Session} stands in for the socket; the registry and interrupt semantics are what is under test.
 */
public class TestGameStreamCancel {
    private static final Logger logger = LogManager.getLogger(TestGameStreamCancel.class);

    private static String newActionId() {
        return "act-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A running action is interrupted, reported cancelled, and removed from the registry. */
    @Test
    public void TestCancelInterruptsRunningAction() throws Exception {
        String actionId = newActionId();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean ranToCompletion = new AtomicBoolean(false);

        Future<?> f = GameStreamHandler.submitAction(null, actionId, () -> {
            started.countDown();
            try {
                Thread.sleep(30_000L);
                ranToCompletion.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        assertNotNull(f);
        assertTrue("action started", started.await(5, TimeUnit.SECONDS));
        assertTrue("action is tracked while running", GameStreamHandler.isInFlight(null, actionId));

        boolean cancelled = GameStreamHandler.cancelAction(null, actionId);
        logger.info("cancelAction(" + actionId + ") -> " + cancelled);
        assertTrue("a running action reports cancelled", cancelled);
        assertTrue("the task body observed the interrupt", finished.await(5, TimeUnit.SECONDS));
        assertTrue("sleep was interrupted", interrupted.get());
        assertFalse("the body did not run to completion", ranToCompletion.get());
        assertTrue("Future reports cancelled", f.isCancelled());
        assertFalse("registry entry removed on cancel", GameStreamHandler.isInFlight(null, actionId));
    }

    /** Cancelling an actionId that is not in flight is a no-op that reports known:false. */
    @Test
    public void TestCancelUnknownActionIsNotKnown() {
        String actionId = newActionId();
        assertFalse(GameStreamHandler.isInFlight(null, actionId));
        boolean cancelled = GameStreamHandler.cancelAction(null, actionId);
        assertFalse("nothing to cancel", cancelled);
        assertFalse("still not tracked", GameStreamHandler.isInFlight(null, actionId));
        assertFalse("null actionId is handled, not thrown", GameStreamHandler.cancelAction(null, null));
    }

    /** A completed action removes itself; a later cancel finds nothing. */
    @Test
    public void TestCompletedActionLeavesRegistry() throws Exception {
        String actionId = newActionId();
        CountDownLatch finished = new CountDownLatch(1);
        Future<?> f = GameStreamHandler.submitAction(null, actionId, finished::countDown);
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        f.get(5, TimeUnit.SECONDS);
        // The finally in the wrapper runs after body.run(); give it a moment to clear the entry.
        long deadline = System.currentTimeMillis() + 2000L;
        while (GameStreamHandler.isInFlight(null, actionId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse("completed action is no longer tracked", GameStreamHandler.isInFlight(null, actionId));
        assertFalse("cancel after completion is unknown", GameStreamHandler.cancelAction(null, actionId));
    }

    /** An action that throws still removes itself from the registry. */
    @Test
    public void TestFailedActionLeavesRegistry() throws Exception {
        String actionId = newActionId();
        Future<?> f = GameStreamHandler.submitAction(null, actionId, () -> {
            throw new IllegalStateException("boom");
        });
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        long deadline = System.currentTimeMillis() + 2000L;
        while (GameStreamHandler.isInFlight(null, actionId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertFalse("failed action is no longer tracked", GameStreamHandler.isInFlight(null, actionId));
    }

    /** Session cleanup cancels every in-flight action for that session (null session == the test "session"). */
    @Test
    public void TestCleanupSessionCancelsAllInFlight() throws Exception {
        int n = 3;
        CountDownLatch started = new CountDownLatch(n);
        CountDownLatch interrupted = new CountDownLatch(n);
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            ids[i] = newActionId();
            GameStreamHandler.submitAction(null, ids[i], () -> {
                started.countDown();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        for (String id : ids) {
            assertTrue(GameStreamHandler.isInFlight(null, id));
        }
        int cancelledCount = GameStreamHandler.cancelSessionActions(null);
        assertEquals("all three in-flight actions cancelled", n, cancelledCount);
        assertTrue("every body observed the interrupt", interrupted.await(5, TimeUnit.SECONDS));
        for (String id : ids) {
            assertFalse(GameStreamHandler.isInFlight(null, id));
        }
    }

    /** The registry key is per session, so one session's actionId cannot be cancelled through another's key. */
    @Test
    public void TestActionKeyIsPerSession() {
        assertEquals("-:abc", GameStreamHandler.actionKey(null, "abc"));
    }
}
