package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.util.LLMConnectionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// Tests for the labeled active-LLM-call tracking added to
/// LLMConnectionManager so the debug endpoint can show what each
/// in-flight LLM call is doing (chat / memory:keyframe / embed:keywords / etc).
public class TestLLMConnectionManagerLabels {

	@Before
	public void clean() {
		/// Static state shared across tests — wipe before each test.
		LLMConnectionManager.stopAllStreams();
		LLMConnectionManager.clearCurrentCallLabel();
	}

	@After
	public void cleanup() {
		LLMConnectionManager.stopAllStreams();
		LLMConnectionManager.clearCurrentCallLabel();
	}

	// ── registerSyncCall / unregisterSyncCall ──────────────────────────

	@Test
	public void registerSyncCall_returnsUniqueIds() {
		String a = LLMConnectionManager.registerSyncCall("embed:keywords");
		String b = LLMConnectionManager.registerSyncCall("embed:topics");
		assertNotNull(a);
		assertNotNull(b);
		assertFalse("ids must be unique", a.equals(b));
		LLMConnectionManager.unregisterSyncCall(a);
		LLMConnectionManager.unregisterSyncCall(b);
	}

	@Test
	public void unregisterSyncCall_nullSafe() {
		LLMConnectionManager.unregisterSyncCall(null); // must not throw
	}

	@Test
	public void getActiveSyncCallCount_reflectsAcquireRelease() {
		assertEquals(0, LLMConnectionManager.getActiveSyncCallCount());
		String a = LLMConnectionManager.registerSyncCall("embed:keywords");
		assertEquals(1, LLMConnectionManager.getActiveSyncCallCount());
		String b = LLMConnectionManager.registerSyncCall("embed:topics");
		assertEquals(2, LLMConnectionManager.getActiveSyncCallCount());
		LLMConnectionManager.unregisterSyncCall(a);
		assertEquals(1, LLMConnectionManager.getActiveSyncCallCount());
		LLMConnectionManager.unregisterSyncCall(b);
		assertEquals(0, LLMConnectionManager.getActiveSyncCallCount());
	}

	@Test
	public void getActiveSyncCallLabels_containsKindAndTimestamp() {
		LLMConnectionManager.registerSyncCall("embed:summary");
		Set<String> labels = LLMConnectionManager.getActiveSyncCallLabels();
		assertEquals(1, labels.size());
		String marker = labels.iterator().next();
		assertTrue("marker must start with kind, got " + marker, marker.startsWith("embed:summary|"));
	}

	@Test
	public void registerSyncCall_nullLabelGetsQuestionMark() {
		LLMConnectionManager.registerSyncCall(null);
		Set<String> labels = LLMConnectionManager.getActiveSyncCallLabels();
		assertEquals(1, labels.size());
		assertTrue("null label should fall back to '?', got " + labels,
			labels.iterator().next().startsWith("?|"));
	}

	// ── getActiveLLMCallCount ──────────────────────────────────────────

	@Test
	public void getActiveLLMCallCount_includesBothStreamsAndSync() {
		assertEquals(0, LLMConnectionManager.getActiveLLMCallCount());
		String syncId = LLMConnectionManager.registerSyncCall("embed:summary");
		assertEquals(1, LLMConnectionManager.getActiveLLMCallCount());
		/// We can't easily register a real stream future here, but the
		/// count surfaces the sync calls — which is the whole point of
		/// adding this tracker (previously embedding pressure was invisible).
		LLMConnectionManager.unregisterSyncCall(syncId);
		assertEquals(0, LLMConnectionManager.getActiveLLMCallCount());
	}

	// ── thread-local current call label ─────────────────────────────────

	@Test
	public void currentCallLabel_nullWhenUnset() {
		assertNull(LLMConnectionManager.getCurrentCallLabel());
	}

	@Test
	public void currentCallLabel_setAndGet() {
		LLMConnectionManager.setCurrentCallLabel("memory:keyframe");
		assertEquals("memory:keyframe", LLMConnectionManager.getCurrentCallLabel());
		LLMConnectionManager.clearCurrentCallLabel();
		assertNull(LLMConnectionManager.getCurrentCallLabel());
	}

	@Test
	public void currentCallLabel_isThreadLocal() throws Exception {
		LLMConnectionManager.setCurrentCallLabel("main-thread-label");
		final String[] otherThreadSaw = new String[1];
		Thread t = new Thread(() -> {
			otherThreadSaw[0] = LLMConnectionManager.getCurrentCallLabel();
		});
		t.start();
		t.join();
		assertNull("other thread must see null", otherThreadSaw[0]);
		assertEquals("main thread retains its label", "main-thread-label",
			LLMConnectionManager.getCurrentCallLabel());
	}

	// ── stopAllStreams clears sync calls ────────────────────────────────

	@Test
	public void stopAllStreams_clearsSyncCallRegistry() {
		LLMConnectionManager.registerSyncCall("embed:keywords");
		LLMConnectionManager.registerSyncCall("embed:topics");
		assertEquals(2, LLMConnectionManager.getActiveSyncCallCount());
		LLMConnectionManager.stopAllStreams();
		assertEquals(0, LLMConnectionManager.getActiveSyncCallCount());
		assertEquals(0, LLMConnectionManager.getActiveLLMCallCount());
	}

	// ── snapshotActiveLLMCalls ──────────────────────────────────────────

	@Test
	public void snapshotActiveLLMCalls_includesSyncMarkers() {
		LLMConnectionManager.registerSyncCall("embed:summary");
		LLMConnectionManager.registerSyncCall("embed:tags");
		Map<String, String> snap = LLMConnectionManager.snapshotActiveLLMCalls();
		assertEquals(2, snap.size());
		boolean foundSummary = false;
		boolean foundTags = false;
		for (String marker : snap.values()) {
			if (marker.startsWith("embed:summary|")) foundSummary = true;
			if (marker.startsWith("embed:tags|")) foundTags = true;
		}
		assertTrue("snapshot must include embed:summary marker", foundSummary);
		assertTrue("snapshot must include embed:tags marker", foundTags);
	}

	@Test
	public void snapshotActiveLLMCalls_emptyWhenIdle() {
		assertEquals(0, LLMConnectionManager.snapshotActiveLLMCalls().size());
	}
}
