package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.CharacterUtil;
import org.junit.Test;

/**
 * Regression coverage for the PictureBook /extract infinite-spin defect in
 * {@link CharacterUtil#randomPerson}. The original loop redrew names from the world's NAME word group
 * and looped forever ("Name null null &lt;token&gt; exists .... trying again") whenever that group was
 * empty/degenerate, because {@code OlioUtil.nameInDirExists} caches every handed-out name and a tiny
 * distinct-name pool can never satisfy the population directory's uniqueness constraint.
 *
 * These tests exercise the two pure helpers that make the hang impossible, WITHOUT touching the
 * database or a live Olio context:
 * <ul>
 *   <li>{@code assemblePersonName} -- never emits the literal token "null" for a null/blank component
 *       (the "null null Smith" garbage), returning "" only when every component is unusable.</li>
 *   <li>{@code synthesizeUniquePersonName} -- the hard-bounded backstop that produces a usable,
 *       non-blank, non-"null" name even when the uniqueness predicate rejects every attempt (i.e. it
 *       provably terminates instead of spinning).</li>
 * </ul>
 *
 * This class intentionally does NOT extend {@code BaseTest}: it opens no IOSystem / DB connection, so
 * it is safe to run anywhere. The full end-to-end proof (drive {@code randomPerson} against an empty
 * NAME directory) lives in {@code TestRandomPersonTermination}, which requires a live context.
 */
public class TestCharacterName {
	public static final Logger logger = LogManager.getLogger(TestCharacterName.class);

	private static String assemble(String first, String middle, String last) throws Exception {
		Method m = CharacterUtil.class.getDeclaredMethod("assemblePersonName", String.class, String.class, String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, first, middle, last);
	}

	private static String synthesize(String baseLast, Predicate<String> exists) throws Exception {
		Method m = CharacterUtil.class.getDeclaredMethod("synthesizeUniquePersonName", String.class, Predicate.class);
		m.setAccessible(true);
		return (String) m.invoke(null, baseLast, exists);
	}

	private static int maxAttempts() throws Exception {
		Field f = CharacterUtil.class.getDeclaredField("MAX_RANDOM_NAME_ATTEMPTS");
		f.setAccessible(true);
		return f.getInt(null);
	}

	@Test
	public void testAssemblePersonNameSkipsNullAndBlank() throws Exception {
		/// All components unusable -> "" (caller then synthesizes a fallback). Critically NOT "null null null".
		assertEquals("All-null must collapse to empty, not 'null null null'", "", assemble(null, null, null));
		assertEquals("All-blank must collapse to empty", "", assemble("", "  ", "\t"));

		/// Healthy path is unchanged: three real tokens single-space-joined.
		assertEquals("John Q Public", assemble("John", "Q", "Public"));

		/// A null/blank middle (the exact "null null <surname>" trigger) must be dropped, never stringified.
		assertEquals("John Smith", assemble("John", null, "Smith"));
		assertEquals("Smith", assemble(null, null, "Smith"));
		assertEquals("John Smith", assemble("John", "   ", "Smith"));

		/// Surrounding whitespace on real tokens is trimmed.
		assertEquals("John Smith", assemble("  John  ", "   ", "  Smith "));

		/// No assembled result may contain the literal token "null".
		for(String n : new String[] { assemble("John", null, "Smith"), assemble(null, null, "Smith"), assemble("John", "Q", "Public") }) {
			for(String tok : n.split(" ")) {
				assertFalse("Assembled name must never contain a 'null' token: '" + n + "'", "null".equals(tok));
			}
		}
	}

	@Test
	public void testSynthesizeUniquePersonNameIsUsable() throws Exception {
		/// exists=false (nothing collides): first candidate is accepted and carries the supplied base.
		String withBase = synthesize("Smith", s -> false);
		assertNotNull(withBase);
		assertFalse("Synthesized name must not be blank", withBase.isBlank());
		assertTrue("Synthesized name should carry the base surname: '" + withBase + "'", withBase.startsWith("Smith "));
		assertFalse("Synthesized name must not start with the literal 'null': '" + withBase + "'", withBase.startsWith("null"));

		/// A null/blank base must degrade to the neutral "Person" default, never "null ...".
		String nullBase = synthesize(null, s -> false);
		assertTrue("Null base must fall back to 'Person': '" + nullBase + "'", nullBase.startsWith("Person "));
		String blankBase = synthesize("   ", s -> false);
		assertTrue("Blank base must fall back to 'Person': '" + blankBase + "'", blankBase.startsWith("Person "));
	}

	/**
	 * The core anti-hang guarantee: even when the uniqueness predicate claims EVERY candidate already
	 * exists (the exact condition that made the original loop spin forever), the backstop must return a
	 * usable name after a bounded number of attempts rather than looping indefinitely. The 5s JUnit
	 * timeout is a safety net; the real proof is that the predicate is consulted at most
	 * MAX_RANDOM_NAME_ATTEMPTS times.
	 */
	@Test(timeout = 5000)
	public void testSynthesizeUniquePersonNameTerminatesWhenEverythingExists() throws Exception {
		final int[] calls = new int[] { 0 };
		String name = synthesize("Smith", s -> { calls[0]++; return true; });

		assertNotNull("Backstop must still return a name when everything 'exists'", name);
		assertFalse("Returned name must not be blank", name.isBlank());
		assertTrue("Returned name should still carry the base surname: '" + name + "'", name.startsWith("Smith "));
		assertFalse("Returned name must not start with the literal 'null': '" + name + "'", name.startsWith("null"));

		int max = maxAttempts();
		assertTrue("Uniqueness predicate must be consulted a bounded number of times, was " + calls[0]
				+ " (cap " + max + ")", calls[0] <= max);
		logger.info("synthesizeUniquePersonName consulted the existence predicate " + calls[0]
				+ " times (cap " + max + ") before terminating with '" + name + "'");
	}
}
