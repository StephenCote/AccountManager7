package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;

/// Regression tests for DocumentUtil.sniffOfficeContentType's WordPerfect support.
///
/// Why this exists: real WordPerfect files are routinely named ".DOC".
/// ContentTypeUtil.getTypeFromExtension then reports application/msword,
/// DocumentUtil.readDocument routes that to POI HWPF, and HWPF cannot read a
/// non-OLE2 container — so extraction returned null and ChapBookUtil.extractPoemText
/// threw a 400. Measured over a 196-file corpus before the fix: 113 WordPerfect
/// files, 81 of them named ".DOC"; 5NIGHTS.DOC extracted as null under the declared
/// application/msword and 10,861 characters once the type was corrected.
///
/// Pure-function tests over synthesized headers — no DB, no LLM, no external corpus,
/// so this runs anywhere. The end-to-end check against the real poem corpus lives in
/// the ChapBook integration tests.
public class TestWordPerfectContentTypeSniff {
	public static final Logger logger = LogManager.getLogger(TestWordPerfectContentTypeSniff.class);

	/// 0xFF "WPC", then a 4-byte document-area pointer, then product type (0x01 =
	/// WordPerfect), file type (0x0A = document), major version, minor version.
	private static byte[] wpHeader(int majorVersion) {
		byte[] h = new byte[16];
		h[0] = (byte) 0xFF; h[1] = 'W'; h[2] = 'P'; h[3] = 'C';
		h[4] = 0x10; h[5] = 0x00; h[6] = 0x00; h[7] = 0x00;
		h[8] = 0x01;
		h[9] = 0x0A;
		h[10] = (byte) majorVersion;
		h[11] = 0x00;
		return h;
	}

	private static byte[] ole2Header() {
		return new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
			(byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0x00, 0x00, 0x00, 0x00 };
	}

	private static byte[] zipHeader() {
		return new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00,
			0x00, 0x00, 0x00, 0x00 };
	}

	@Test
	public void TestSniffWordPerfect6PlusIsExtractable() {
		/// Major version 0x02 = WP6.0+, which Tika's WPDParser supports. This is 109 of
		/// the 113 WordPerfect files in the measured corpus.
		String ct = DocumentUtil.sniffOfficeContentType(wpHeader(0x02));
		assertEquals("WP6+ must sniff as application/wordperfect", "application/wordperfect", ct);
		assertTrue("sniffed WordPerfect type must be in OFFICE_CONTENT_TYPES so extraction routes to Tika",
			DocumentUtil.OFFICE_CONTENT_TYPES.contains(ct));
	}

	@Test
	public void TestSniffWordPerfect5IsDistinguished() {
		/// Major version 0x00 = WP5.x, which WPDParser does NOT handle. It must be
		/// reported distinctly so such a file fails with a clear message instead of
		/// being handed to a parser that yields garbage.
		String ct = DocumentUtil.sniffOfficeContentType(wpHeader(0x00));
		assertEquals("WP5.x must sniff as application/x-wordperfect", "application/x-wordperfect", ct);
		assertTrue(DocumentUtil.OFFICE_CONTENT_TYPES.contains(ct));
	}

	/// The actual defect: the EXTENSION says Word, the BYTES say WordPerfect. Before the
	/// fix the extension won and extraction failed.
	@Test
	public void TestMisnamedDocResolvesToWordPerfectNotMsword() {
		byte[] data = wpHeader(0x02);
		String declared = ContentTypeUtil.getTypeFromExtension("DOC");
		assertEquals("precondition: a .DOC extension still declares Word",
			"application/msword", declared);

		String sniffed = DocumentUtil.sniffOfficeContentType(data);
		assertNotNull("container magic must be recognised", sniffed);
		assertTrue("magic must disagree with the extension-derived type — that disagreement IS the bug",
			!sniffed.equalsIgnoreCase(declared));
		assertEquals("application/wordperfect", sniffed);
	}

	@Test
	public void TestSniffPreservesExistingOle2AndZipBehavior() {
		assertEquals("application/msword", DocumentUtil.sniffOfficeContentType(ole2Header()));
		assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			DocumentUtil.sniffOfficeContentType(zipHeader()));
	}

	@Test
	public void TestSniffReturnsNullForNonContainerBytes() {
		/// PDF, RTF and plain text carry no magic this method claims, so they must sniff
		/// null and keep whatever type was declared — otherwise correcting the declared
		/// type would break the PDF path (which routes to readPDF, not Tika).
		assertNull("plain text must not be claimed", DocumentUtil.sniffOfficeContentType("Hello poem".getBytes()));
		assertNull("RTF must not be claimed", DocumentUtil.sniffOfficeContentType("{\\rtf1\\ansi".getBytes()));
		assertNull("PDF must not be claimed", DocumentUtil.sniffOfficeContentType("%PDF-1.7\n".getBytes()));
		assertNull(DocumentUtil.sniffOfficeContentType(null));
		assertNull(DocumentUtil.sniffOfficeContentType(new byte[0]));
	}

	/// A truncated WordPerfect header matches the 4-byte prefix but has no version byte.
	/// It must not index past the end of the array.
	@Test
	public void TestTruncatedWordPerfectHeaderDoesNotThrow() {
		byte[] shortWp = new byte[] { (byte) 0xFF, 'W', 'P', 'C' };
		String ct = DocumentUtil.sniffOfficeContentType(shortWp);
		assertEquals("a prefix-only match defaults to the extractable WP6+ type",
			"application/wordperfect", ct);
	}
}
