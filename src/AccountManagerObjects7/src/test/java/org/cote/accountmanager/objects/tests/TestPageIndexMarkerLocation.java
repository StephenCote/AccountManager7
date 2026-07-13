package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;

import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.PageIndexUtil;
import org.junit.Test;

/// Pure, deterministic unit tests (no DB / no LLM) for PageIndexUtil's tolerant marker-location fallback
/// (locateMarker/buildTolerantMarkerPattern) - the fix for the "docx content collapses into one trailing
/// section, breakdown reflects only front matter" bug reproduced live against catatone.docx (see
/// aiDocs/KnownIssues.md and TestPageIndex#TestPageIndexCatatoneDocxContentDistribution for the live
/// end-to-end regression test). locateMarker/buildTolerantMarkerPattern are private, so invoked via
/// reflection, mirroring the existing ScratchTocProbe-style access pattern used to diagnose this bug.
///
/// Root cause proven live: catatone.docx's extracted text contains "Duña‘s" - a LEFT single quote
/// (U+2018) used by Word as a possessive apostrophe - which DocumentUtil.replaceSmartQuotes did NOT
/// normalize (it only handled right single/double curly quotes). Any LLM-invented startMarker spanning
/// this word, once "cleaned up" to a plain apostrophe by the model, failed String.indexOf's exact match
/// and was dropped - and because buildLlmTocTree's forward-search cursor never advanced past that point,
/// every subsequent marker for that TOC group was dropped too, collapsing that entire span into flat
/// ROOT-direct preamble chunks instead of real SECTION nodes.
public class TestPageIndexMarkerLocation {

	private int[] locateInts(String content, String marker, int searchFrom) throws Exception {
		Method m = PageIndexUtil.class.getDeclaredMethod("locateMarker", String.class, String.class, int.class);
		m.setAccessible(true);
		return (int[]) m.invoke(null, content, marker, searchFrom);
	}

	@Test
	public void exactMatchStillWorks() throws Exception {
		String content = "The quick brown fox jumps over the lazy dog.";
		int[] hit = locateInts(content, "brown fox jumps", 0);
		assertArrayEquals(new int[] { 10, "brown fox jumps".length() }, hit);
	}

	@Test
	public void toleratesLeftSingleQuoteVsPlainApostrophe() throws Exception {
		/// Exactly the catatone.docx failure case: source has a LEFT single quote (U+2018) used as a
		/// possessive apostrophe (a genuine Word/Tika extraction artifact, not something
		/// DocumentUtil.replaceSmartQuotes' original regex covered), the LLM's "verbatim" marker uses a
		/// plain ASCII apostrophe instead.
		String content = "and Jideon slid Duña‘s drug-withdrawn body across the pleather seat.";
		String marker = "Jideon slid Duña's drug-withdrawn body"; // plain apostrophe, not ‘
		int[] hit = locateInts(content, marker, 0);
		org.junit.Assert.assertNotNull("Tolerant match should have located the marker despite the "
			+ "left-single-quote vs. plain-apostrophe mismatch", hit);
		int start = hit[0];
		int matchedLen = hit[1];
		org.junit.Assert.assertEquals("Located offset should point at the real occurrence in the source",
			content.indexOf("Jideon slid"), start);
		String matchedText = content.substring(start, start + matchedLen);
		/// The actual matched span (from the ORIGINAL content) must contain the real left single quote,
		/// proving this located the true text rather than fabricating/guessing a position.
		org.junit.Assert.assertTrue("Matched span should contain the source's real apostrophe character",
			matchedText.indexOf('‘') >= 0);
	}

	@Test
	public void toleratesDoubleVsSingleInternalWhitespace() throws Exception {
		/// Tika docx extraction commonly leaves double spaces after sentence-ending periods; an LLM
		/// echoing "verbatim" text very often collapses these to a single space.
		String content = "He rapped the opaque driver-side window.  The autopilot had abandoned him.";
		String marker = "window. The autopilot had abandoned him"; // single space, source has two
		int[] hit = locateInts(content, marker, 0);
		org.junit.Assert.assertNotNull("Tolerant match should collapse whitespace-run differences", hit);
		String matchedText = content.substring(hit[0], hit[0] + hit[1]);
		org.junit.Assert.assertEquals("window.  The autopilot had abandoned him", matchedText);
	}

	@Test
	public void toleratesEnDashVsHyphen() throws Exception {
		String content = "The reading was between 10–20 units on the gauge.";
		String marker = "between 10-20 units"; // plain hyphen, source has en dash
		int[] hit = locateInts(content, marker, 0);
		org.junit.Assert.assertNotNull("Tolerant match should treat en-dash and hyphen as equivalent", hit);
	}

	@Test
	public void neverFabricatesAnUnlocatableMarker() throws Exception {
		String content = "The quick brown fox jumps over the lazy dog.";
		int[] hit = locateInts(content, "this text does not appear anywhere in the source", 0);
		assertNull("A genuinely absent marker must still be reported as not located (never fabricated)", hit);
	}

	@Test
	public void searchFromIsHonoredForBothExactAndTolerantPaths() throws Exception {
		String content = "alpha beta alpha beta alpha";
		/// First "alpha" is at 0; searching from 1 should skip it and find the next occurrence at 11.
		int[] hit = locateInts(content, "alpha", 1);
		org.junit.Assert.assertNotNull(hit);
		org.junit.Assert.assertEquals(11, hit[0]);
	}

	/// End-to-end proof at the DocumentUtil layer: replaceSmartQuotes now normalizes the additional
	/// typographic characters (left single quote, en/em dash, ellipsis) that were previously passed
	/// through verbatim, which is the primary fix (locateMarker's tolerance is defense in depth for
	/// whatever this doesn't cover, e.g. direction-reversed mismatches).
	@Test
	public void documentUtilNormalizesLeftSingleQuoteAndDashes() {
		String raw = "Duña‘s drug–withdrawn body — gone.";
		String normalized = DocumentUtil.replaceSmartQuotes(raw);
		org.junit.Assert.assertEquals("Duña's drug-withdrawn body - gone.", normalized);
	}
}
