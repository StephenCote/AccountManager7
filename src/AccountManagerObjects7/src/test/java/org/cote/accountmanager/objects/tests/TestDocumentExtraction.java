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

		double ratio = printableRatio(text);
		logger.info("testDocxExtractionVerse: {} chars extracted from .docx, printable-char ratio={}",
				text.length(), String.format("%.3f", ratio));
		assertTrue(
				"Extracted .docx text looks like binary garbage (printable-char ratio=" + ratio + ")",
				ratio > 0.60);
	}
}
