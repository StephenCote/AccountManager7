package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;

/**
 * Verifies text extraction from binary office document formats via
 * {@link DocumentUtil}.
 * <p>
 * Does <em>not</em> extend {@link BaseTest} — no IOSystem, database, or
 * network connections are required.  Tests are gated on {@code assumeTrue}
 * so they skip gracefully when the media fixture files are absent.
 */
public class TestDocumentExtraction {

	private static final Logger logger = LogManager.getLogger(TestDocumentExtraction.class);

	/** Characters that look like readable text (printable ASCII + common whitespace). */
	private static double printableRatio(String text) {
		long readable = text.chars()
				.filter(c -> (c >= 0x20 && c <= 0x7E) || c == '\n' || c == '\r' || c == '\t')
				.count();
		return (double) readable / text.length();
	}

	/**
	 * Assert the extracted text actually contains distinctive prose from the document body — the
	 * real proof of correct extraction. Printable-char ratio alone can pass on structurally-valid
	 * but wrong/garbled content; asserting on known body phrases proves the words themselves came
	 * through. Every phrase below was confirmed present by reading the real extracted output.
	 */
	private static void assertBodyContains(String label, String text, String... phrases) {
		for (String phrase : phrases) {
			assertTrue(label + ": extracted text must contain the confirmed body phrase \"" + phrase
					+ "\" — its absence means the words did not extract correctly (garbled/wrong content). "
					+ "First 200 chars were: \"" + text.substring(0, Math.min(200, text.length())) + "\"",
					text.contains(phrase));
		}
		logger.info("{}: confirmed {} distinctive body phrase(s) present in extracted text", label, phrases.length);
	}

	/**
	 * .doc (OLE2/HWPF) extraction via POI.
	 * <p>
	 * Before the fix, {@code AutoDetectParser} without a content-type hint could
	 * fall back to a raw OLE2 stream dump for these files, producing upper-ASCII
	 * binary garbage.  The fix routes {@code application/msword} to Apache POI
	 * {@code HWPFDocument} + {@code WordExtractor} directly.
	 */
	@Test
	public void testDocExtraction() throws Exception {
		File docFile = new File("media/The Big Way Out.doc");
		org.junit.Assume.assumeTrue(
				"media/The Big Way Out.doc not found — skipping .doc extraction test",
				docFile.exists());

		byte[] bytes = Files.readAllBytes(docFile.toPath());
		String text = DocumentUtil.readDocument(bytes, 16 * 1024 * 1024, "application/msword");

		assertNotNull("Extracted text must not be null for .doc file", text);
		assertFalse("Extracted text must not be blank for .doc file", text.isBlank());
		// Distinctive prose confirmed present in the real body of "The Big Way Out.doc" (legacy binary):
		// characters Darby & Veronique, the "Nexon" car, and the "studying AI law" line. This proves the
		// POI HWPF path extracted the actual words, not just a printable-looking OLE2 stream dump.
		assertBodyContains(".doc body", text, "Darby", "Veronique", "Nexon", "studying AI law");

		double ratio = printableRatio(text);
		logger.info("testDocExtraction: {} chars extracted from .doc, printable-char ratio={}",
				text.length(), String.format("%.3f", ratio));
		assertTrue(
				"Extracted .doc text looks like binary garbage (printable-char ratio=" + ratio
						+ "); expected > 0.60",
				ratio > 0.60);
	}

	/**
	 * .docx (OOXML) extraction via Tika with content-type hint — regression guard.
	 */
	@Test
	public void testDocxExtraction() throws Exception {
		File docxFile = new File("media/HarlotsEight_Vol1_SM.docx");
		org.junit.Assume.assumeTrue(
				"media/HarlotsEight_Vol1_SM.docx not found — skipping .docx extraction test",
				docxFile.exists());

		byte[] bytes = Files.readAllBytes(docxFile.toPath());
		String text = DocumentUtil.readDocument(bytes, 16 * 1024 * 1024,
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document");

		assertNotNull("Extracted text must not be null for .docx file", text);
		assertFalse("Extracted text must not be blank for .docx file", text.isBlank());
		// Distinctive prose confirmed present in the real body of "HarlotsEight_Vol1_SM.docx".
		assertBodyContains(".docx body (Harlot's Eight)", text,
				"Simon Stewart walked to the comptroller's desk", "Braevarn", "pieces of eight");

		double ratio = printableRatio(text);
		logger.info("testDocxExtraction: {} chars extracted from .docx, printable-char ratio={}",
				text.length(), String.format("%.3f", ratio));
		assertTrue(
				"Extracted .docx text looks like binary garbage (printable-char ratio=" + ratio + ")",
				ratio > 0.60);
	}

	/**
	 * .docx extraction via Tika with content-type hint — second fixture.
	 */
	@Test
	public void testDocxExtractionVerse() throws Exception {
		File docxFile = new File("media/The Verse.docx");
		org.junit.Assume.assumeTrue(
				"media/The Verse.docx not found — skipping .docx extraction test",
				docxFile.exists());

		byte[] bytes = Files.readAllBytes(docxFile.toPath());
		String text = DocumentUtil.readDocument(bytes, 16 * 1024 * 1024,
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document");

		assertNotNull("Extracted text must not be null for .docx file", text);
		assertFalse("Extracted text must not be blank for .docx file", text.isBlank());
		// Distinctive prose confirmed present in the real body of "The Verse.docx".
		assertBodyContains(".docx body (The Verse)", text,
				"Mark Lucean stumbled through the bar's dilapidated back door", "fried mozzarella");

		double ratio = printableRatio(text);
		logger.info("testDocxExtractionVerse: {} chars extracted from .docx, printable-char ratio={}",
				text.length(), String.format("%.3f", ratio));
		assertTrue(
				"Extracted .docx text looks like binary garbage (printable-char ratio=" + ratio + ")",
				ratio > 0.60);
	}
}
