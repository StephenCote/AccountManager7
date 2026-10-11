package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// The scan cache's check-then-act guard, exercised in-memory with synthetic scans (no DB): a scan
/// captures the workflow's invalidation epoch before computing and is only cached if no write landed in
/// between. Same package as PbStaleUtil so the package-private seams (Scan constructor, put, epochOf)
/// are reachable without widening the production API.
public class TestPbStaleCacheEpoch {

	private static final long WF_A = 910_001L;
	private static final long WF_B = 910_002L;

	@Before
	public void reset() {
		PbStaleUtil.clear();
	}

	@After
	public void cleanup() {
		PbStaleUtil.clear();
	}

	private static PbStaleUtil.Scan scanOf(long wfId, long at, Set<String> refKeys, Set<Long> nodeIds) {
		Map<Long, PbNodeStatusEnumType> statuses = new HashMap<>();
		for(Long n : nodeIds) {
			statuses.put(n, PbNodeStatusEnumType.DONE);
		}
		return new PbStaleUtil.Scan(at, wfId, new LinkedHashMap<>(), statuses, refKeys, true,
			Collections.emptySet(), new HashMap<>(), new HashMap<>(), new HashMap<>());
	}

	@Test
	public void scanOvertakenByAWriteIsNotCached() {
		Set<String> refs = new HashSet<>();
		refs.add(PbStaleUtil.refKey("olio.charPerson", "c-1"));
		Set<Long> nodes = new HashSet<>();
		nodes.add(1L);

		long epoch = PbStaleUtil.epochOf(WF_A);
		/// A write lands while the scan is computing.
		PbStaleUtil.invalidate(WF_A);
		assertFalse("A scan captured before an invalidation must not be cached",
			PbStaleUtil.put(scanOf(WF_A, 1L, refs, nodes), epoch));
		assertNull(PbStaleUtil.cached(WF_A));

		/// The next scan captures the bumped epoch and is cached normally.
		long again = PbStaleUtil.epochOf(WF_A);
		assertEquals(epoch + 1L, again);
		PbStaleUtil.Scan s = scanOf(WF_A, 2L, refs, nodes);
		assertTrue(PbStaleUtil.put(s, again));
		assertSame(s, PbStaleUtil.cached(WF_A));

		/// touch() via the ref index and invalidateByNode() via the node index both bump the epoch.
		PbStaleUtil.touch("olio.charPerson", "c-1");
		assertNull("touch must drop the cached scan", PbStaleUtil.cached(WF_A));
		assertEquals(again + 1L, PbStaleUtil.epochOf(WF_A));
		assertTrue(PbStaleUtil.put(s, PbStaleUtil.epochOf(WF_A)));
		PbStaleUtil.invalidateByNode(1L);
		assertNull("invalidateByNode must drop the cached scan", PbStaleUtil.cached(WF_A));
		assertEquals(again + 2L, PbStaleUtil.epochOf(WF_A));
	}

	@Test
	public void clearBumpsEveryWorkflowWithAnInFlightScan() {
		long ea = PbStaleUtil.epochOf(WF_A);
		/// scan() creates the epoch entry before computing; mirror that here so clear() has something to bump.
		PbStaleUtil.put(scanOf(WF_A, 1L, Collections.emptySet(), Collections.emptySet()), ea);
		long eb = PbStaleUtil.epochOf(WF_B);
		PbStaleUtil.put(scanOf(WF_B, 1L, Collections.emptySet(), Collections.emptySet()), eb);
		assertEquals(WF_A, PbStaleUtil.cached(WF_A).workflowId);
		assertEquals(WF_B, PbStaleUtil.cached(WF_B).workflowId);
		PbStaleUtil.clear();
		assertNull(PbStaleUtil.cached(WF_A));
		assertNull(PbStaleUtil.cached(WF_B));
		assertFalse("A scan captured before clear() must not be cached",
			PbStaleUtil.put(scanOf(WF_A, 2L, Collections.emptySet(), Collections.emptySet()), ea));
		assertFalse(PbStaleUtil.put(scanOf(WF_B, 2L, Collections.emptySet(), Collections.emptySet()), eb));
		assertNull(PbStaleUtil.cached(WF_A));
		assertNull(PbStaleUtil.cached(WF_B));
	}

	@Test
	public void replacingAScanKeepsTheIndexOfTheWinner() {
		Set<String> refs = new HashSet<>();
		refs.add(PbStaleUtil.refKey("olio.charPerson", "c-2"));
		Set<Long> nodes = new HashSet<>();
		nodes.add(2L);
		long e = PbStaleUtil.epochOf(WF_A);
		assertTrue(PbStaleUtil.put(scanOf(WF_A, 1L, refs, nodes), e));
		PbStaleUtil.Scan newer = scanOf(WF_A, 2L, refs, nodes);
		assertTrue("A same-epoch replacement is allowed (two scans raced, neither overtaken by a write)",
			PbStaleUtil.put(newer, e));
		assertSame(newer, PbStaleUtil.cached(WF_A));
		/// The old scan's unindex must not have stripped the shared keys from the winner.
		PbStaleUtil.touch("olio.charPerson", "c-2");
		assertNull("The winner must still be reachable through the ref index", PbStaleUtil.cached(WF_A));
		assertTrue(PbStaleUtil.put(newer, PbStaleUtil.epochOf(WF_A)));
		PbStaleUtil.invalidateByNode(2L);
		assertNull("The winner must still be reachable through the node index", PbStaleUtil.cached(WF_A));
	}
}
