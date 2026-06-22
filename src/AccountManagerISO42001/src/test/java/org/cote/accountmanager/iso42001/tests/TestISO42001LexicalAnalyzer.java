package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.iso42001.scoring.LexicalAnalyzer;
import org.cote.accountmanager.iso42001.scoring.LexicalResult;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pure-logic verification of {@link LexicalAnalyzer} category matching and
 * frequency scoring (iso42001-bias.md §2.3), against hand-counted fixtures and
 * the shipped lexicon resources.
 */
@Category(UnitTest.class)
public class TestISO42001LexicalAnalyzer {

	private static final double EPS = 1.0e-6;

	private static Set<String> set(String... words) {
		return new LinkedHashSet<>(java.util.Arrays.asList(words));
	}

	/**
	 * Controlled fixture. categories: authority={led,expert,capable},
	 * threat={risk,dangerous}, dignity={kind,warm}.
	 * Text tokens (16): she led the team as a capable expert the risk was real
	 *                    but she was kind
	 * authority hits: led, capable, expert = 3; threat: risk = 1; dignity: kind = 1.
	 * frequencies: authority 3/16=0.1875, threat 1/16=0.0625, dignity 1/16=0.0625.
	 */
	@Test
	public void testCategoryCountsAndFrequencies() {
		Map<String, Set<String>> cats = new LinkedHashMap<>();
		cats.put("authority", set("led", "expert", "capable"));
		cats.put("threat", set("risk", "dangerous"));
		cats.put("dignity", set("kind", "warm"));
		LexicalAnalyzer la = new LexicalAnalyzer(cats);

		String text = "She led the team as a capable expert. The risk was real, but she was kind.";
		LexicalResult r = la.analyze(text);

		assertEquals(16, r.getTotalTokens());
		assertEquals(3, r.getCount("authority"));
		assertEquals(1, r.getCount("threat"));
		assertEquals(1, r.getCount("dignity"));
		assertEquals(0.1875, r.getFrequency("authority"), EPS);
		assertEquals(0.0625, r.getFrequency("threat"), EPS);
		assertEquals(0.0625, r.getFrequency("dignity"), EPS);
	}

	/** Case-insensitive, repeated-word counting. */
	@Test
	public void testCaseInsensitiveAndRepeats() {
		Map<String, Set<String>> cats = new LinkedHashMap<>();
		cats.put("threat", set("risk"));
		LexicalAnalyzer la = new LexicalAnalyzer(cats);
		// "Risk", "RISK", "risk" → 3 hits; total tokens = 4 ("a" included)
		LexicalResult r = la.analyze("Risk a RISK risk");
		assertEquals(4, r.getTotalTokens());
		assertEquals(3, r.getCount("threat"));
	}

	/** Empty text yields zero counts and zero frequency (no divide-by-zero). */
	@Test
	public void testEmptyText() {
		Map<String, Set<String>> cats = new LinkedHashMap<>();
		cats.put("threat", set("risk"));
		LexicalAnalyzer la = new LexicalAnalyzer(cats);
		LexicalResult r = la.analyze("");
		assertEquals(0, r.getTotalTokens());
		assertEquals(0, r.getCount("threat"));
		assertEquals(0.0, r.getFrequency("threat"), EPS);
	}

	/**
	 * Shipped lexicons load from the classpath (the 7 categories from §2.3), and
	 * the words land in their declared categories.
	 */
	@Test
	public void testLoadShippedLexicons() {
		LexicalAnalyzer la = LexicalAnalyzer.fromClasspath();
		assertEquals(7, la.getCategories().size());
		assertTrue(la.getCategories().contains("competence_language"));
		assertTrue(la.getCategories().contains("positive_professional"));

		// "capable" + "expert" → competence_language; "leader" + "outstanding" → positive_professional
		LexicalResult r = la.analyze("She is a capable leader and an outstanding expert.");
		assertEquals(2, r.getCount("competence_language"));
		assertEquals(2, r.getCount("positive_professional"));
	}
}
