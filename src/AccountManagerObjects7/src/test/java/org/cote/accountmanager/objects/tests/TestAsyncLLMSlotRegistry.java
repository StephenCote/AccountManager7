package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.olio.llm.AsyncLLMSlotRegistry;
import org.junit.Test;

/// Phase 5.2 (ConversationQualityPlan) unit tests for the async-LLM
/// slot registry. Pure-function tests, no DB / no LLM.
public class TestAsyncLLMSlotRegistry {

	private static final long TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

	// ── makeMarker / parseTimestamp / parseKind ─────────────────────────

	@Test
	public void makeMarker_includesKindAndTimestamp() {
		assertEquals("keyframe|12345", AsyncLLMSlotRegistry.makeMarker("keyframe", 12345L));
	}

	@Test
	public void makeMarker_nullKindFallback() {
		assertEquals("?|12345", AsyncLLMSlotRegistry.makeMarker(null, 12345L));
	}

	@Test
	public void parseTimestamp_validMarker() {
		assertEquals(12345L, AsyncLLMSlotRegistry.parseTimestamp("keyframe|12345"));
	}

	@Test
	public void parseTimestamp_nullReturnsNegativeOne() {
		assertEquals(-1L, AsyncLLMSlotRegistry.parseTimestamp(null));
	}

	@Test
	public void parseTimestamp_malformedReturnsNegativeOne() {
		assertEquals(-1L, AsyncLLMSlotRegistry.parseTimestamp("nopipe"));
		assertEquals(-1L, AsyncLLMSlotRegistry.parseTimestamp("trailingpipe|"));
		assertEquals(-1L, AsyncLLMSlotRegistry.parseTimestamp("kind|notanumber"));
	}

	@Test
	public void parseTimestamp_zeroIsValid() {
		/// 0 is a legitimate epoch timestamp — must not be treated as parse failure.
		assertEquals(0L, AsyncLLMSlotRegistry.parseTimestamp("keyframe|0"));
	}

	@Test
	public void parseKind_extractsKindBeforePipe() {
		assertEquals("keyframe", AsyncLLMSlotRegistry.parseKind("keyframe|12345"));
		assertEquals("compliance", AsyncLLMSlotRegistry.parseKind("compliance|0"));
	}

	@Test
	public void parseKind_nullSafe() {
		assertEquals("?", AsyncLLMSlotRegistry.parseKind(null));
	}

	@Test
	public void parseKind_noPipeReturnsWhole() {
		assertEquals("keyframe", AsyncLLMSlotRegistry.parseKind("keyframe"));
	}

	// ── tryAcquire / release ────────────────────────────────────────────

	@Test
	public void firstAcquireSucceeds() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertTrue(r.tryAcquire("chat1", "keyframe", 1000L));
	}

	@Test
	public void secondAcquireForDifferentKindBlocks() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertTrue(r.tryAcquire("chat1", "keyframe", 1000L));
		assertFalse(r.tryAcquire("chat1", "compliance", 1500L));
		assertFalse(r.tryAcquire("chat1", "interaction", 2000L));
	}

	@Test
	public void differentChatConfigsIndependent() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertTrue(r.tryAcquire("chat1", "keyframe", 1000L));
		assertTrue(r.tryAcquire("chat2", "keyframe", 1000L));
		assertTrue(r.tryAcquire("chat3", "compliance", 1000L));
	}

	@Test
	public void releaseAllowsReacquire() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertTrue(r.tryAcquire("chat1", "keyframe", 1000L));
		assertFalse(r.tryAcquire("chat1", "compliance", 1500L));
		r.release("chat1");
		assertTrue(r.tryAcquire("chat1", "compliance", 2000L));
	}

	@Test
	public void releaseWithoutAcquireSafe() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		r.release("chat1"); // should not throw
		assertTrue(r.tryAcquire("chat1", "keyframe", 1000L));
	}

	@Test
	public void releaseNullKeySafe() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		r.release(null); // should not throw
	}

	@Test
	public void nullKeyAlwaysAcquires() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertTrue(r.tryAcquire(null, "keyframe", 1000L));
		/// Null key is ungated — second acquire also succeeds because nothing was recorded.
		assertTrue(r.tryAcquire(null, "compliance", 2000L));
	}

	@Test
	public void expiredSlotReclaimed() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(1000L); // 1s timeout
		assertTrue(r.tryAcquire("chat1", "keyframe", 0L));
		assertFalse(r.tryAcquire("chat1", "compliance", 500L)); // still inside timeout
		/// Move past timeout — should reclaim and acquire as compliance.
		assertTrue(r.tryAcquire("chat1", "compliance", 1500L));
	}

	@Test
	public void currentHolderReportsKind() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertNull(r.currentHolder("chat1"));
		r.tryAcquire("chat1", "compliance", 7777L);
		String holder = r.currentHolder("chat1");
		assertTrue("holder should contain 'compliance', got " + holder, holder.startsWith("compliance"));
		assertEquals(7777L, AsyncLLMSlotRegistry.parseTimestamp(holder));
		assertEquals("compliance", AsyncLLMSlotRegistry.parseKind(holder));
	}

	@Test
	public void clearWipesAll() {
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		r.tryAcquire("chat1", "keyframe", 1000L);
		r.tryAcquire("chat2", "interaction", 1000L);
		r.clear();
		assertNull(r.currentHolder("chat1"));
		assertNull(r.currentHolder("chat2"));
		assertTrue(r.tryAcquire("chat1", "keyframe", 2000L));
		assertTrue(r.tryAcquire("chat2", "interaction", 2000L));
	}

	@Test
	public void sameKindReacquireFails() {
		/// Even the same kind doesn't get to acquire twice — caller must release.
		AsyncLLMSlotRegistry r = new AsyncLLMSlotRegistry(TIMEOUT_MS);
		assertTrue(r.tryAcquire("chat1", "keyframe", 1000L));
		assertFalse(r.tryAcquire("chat1", "keyframe", 1500L));
	}
}
