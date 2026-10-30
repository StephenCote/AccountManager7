package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * PictureBook V3 Phase 6 (P6-2, verifier 2026-10-29): {@link PbNodeExecutor#variantSeed} is pure arithmetic,
 * so it is checked here without a database. Variants of one attempt must never share a seed, whatever the
 * renderer reported for variant 0 - the pre-fix version folded the <em>sum</em> into range and so mapped
 * variants 1 and 2 of a base of -1 both onto seed 1.
 */
public class TestPbVariantSeed {

	@Test
	public void positiveBaseIsConsecutive() {
		assertEquals(4243, PbNodeExecutor.variantSeed(4242L, 1));
		assertEquals(4244, PbNodeExecutor.variantSeed(4242L, 2));
		assertEquals(7001, PbNodeExecutor.variantSeed(7001L, 0));
		assertEquals(1, PbNodeExecutor.variantSeed(1L, 0));
	}

	@Test
	public void nonPositiveAndOversizedBasesStayDistinctAcrossVariants() {
		long[] bases = { -1L, 0L, -7L, Long.MIN_VALUE, Integer.MAX_VALUE - 1L, Integer.MAX_VALUE, Integer.MAX_VALUE + 5L, Long.MAX_VALUE };
		for(long base : bases) {
			Set<Integer> seen = new HashSet<>();
			for(int v = 0; v < 8; v++) {
				int seed = PbNodeExecutor.variantSeed(base, v);
				assertTrue("base " + base + " variant " + v + " seed " + seed + " must be a positive int below MAX_VALUE",
					seed >= 1 && seed < Integer.MAX_VALUE);
				assertTrue("base " + base + " variant " + v + " repeats seed " + seed + " within the attempt", seen.add(Integer.valueOf(seed)));
			}
		}
	}

	@Test
	public void wrapAtTheTopIsContinuous() {
		/// the last in-range base plus one wraps to the first in-range seed, plus two to the second
		assertEquals(Integer.MAX_VALUE - 1, PbNodeExecutor.variantSeed(Integer.MAX_VALUE - 1L, 0));
		assertEquals(1, PbNodeExecutor.variantSeed(Integer.MAX_VALUE - 1L, 1));
		assertEquals(2, PbNodeExecutor.variantSeed(Integer.MAX_VALUE - 1L, 2));
	}
}
