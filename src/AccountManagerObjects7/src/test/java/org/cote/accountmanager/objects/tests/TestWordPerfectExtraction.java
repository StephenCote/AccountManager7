package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Assume;
import org.junit.Test;

/// Regression tests for WordPerfect text extraction in DocumentUtil.readDocument(byte[], int, String).
///
/// Why this exists: Tika 3.1.0's mime magic only resolves application/vnd.wordperfect;version=6.x for
/// header bytes 0x0201 at offset 10 (WP 6.1). WP 6.0 (0x0200) and 6.3 (0x0203) detect as the bare
/// base type, which no parser claims, so AutoDetectParser fell to EmptyParser and returned "" with no
/// error. ChapBookUtil.extractPoemText then had nothing to import. Every 6.0 file in the poem corpus
/// failed this way (M-LAND.DOC among them) while 6.1 files (MAPLE.DOC) worked. readDocument now hands
/// WPC-magic bytes to WordPerfectParser directly.
///
/// Fixtures are real files from the poem corpus, committed under src/test/resources/documents/wordperfect.
/// No DB or LLM required.
public class TestWordPerfectExtraction {
	public static final Logger logger = LogManager.getLogger(TestWordPerfectExtraction.class);

	private static final int MAX_CHARS = 16 * 1024 * 1024;
	/// Optional full-corpus sweep; skipped when the directory is not present on this machine.
	private static final String CORPUS_DIR = "C:/temp/AccuRev/AccuRev/poems";

	private static byte[] fixture(String name) throws IOException {
		try (InputStream in = TestWordPerfectExtraction.class.getResourceAsStream("/documents/wordperfect/" + name)) {
			assertNotNull("missing fixture " + name, in);
			return in.readAllBytes();
		}
	}

	/// The reported failure: a WP 6.0 file, named .DOC, routed by its sniffed type exactly as
	/// ChapBookUtil.extractPoemText does.
	@Test
	public void TestWordPerfect60Extracts() throws IOException {
		byte[] data = fixture("M-LAND.DOC");
		assertEquals("precondition: WP 6.0 header", 0x00, data[11]);
		String sniffed = DocumentUtil.sniffOfficeContentType(data);
		assertEquals("application/wordperfect", sniffed);

		String text = DocumentUtil.readDocument(data, MAX_CHARS, sniffed);
		assertNotNull(text);
		assertTrue("WP 6.0 text must be extracted, got: [" + text + "]", text.contains("Microsoftland"));
		assertTrue(text.contains("The start menu slams"));
	}

	/// The hint must not matter: whatever label the record carries, WordPerfect bytes still go to the
	/// WordPerfect parser (getStringContent passes the stored content type through unchanged).
	@Test
	public void TestWordPerfect60ExtractsRegardlessOfHint() throws IOException {
		byte[] data = fixture("M-LAND.DOC");
		for (String hint : new String[] { null, "application/vnd.wordperfect", "application/x-wordperfect" }) {
			String text = DocumentUtil.readDocument(data, MAX_CHARS, hint);
			assertTrue("hint " + hint + " must still extract", text != null && text.contains("Microsoftland"));
		}
	}

	/// WP 6.1 was the case that already worked through AutoDetectParser; it must keep working.
	@Test
	public void TestWordPerfect61StillExtracts() throws IOException {
		byte[] data = fixture("MAPLE.DOC");
		assertEquals("precondition: WP 6.1 header", 0x01, data[11]);
		String text = DocumentUtil.readDocument(data, MAX_CHARS, DocumentUtil.sniffOfficeContentType(data));
		assertNotNull(text);
		assertTrue("WP 6.1 text must be extracted, got: [" + text + "]", text.contains("Miss Maple"));
		assertTrue(text.contains("Mysterious Miss Maddening"));
	}

	/// Every WordPerfect file in the local poem corpus must yield non-blank text. Skipped when the
	/// corpus is not on this machine.
	@Test
	public void TestCorpusWordPerfectFilesAllExtract() throws IOException {
		File dir = new File(CORPUS_DIR);
		Assume.assumeTrue("corpus not present: " + CORPUS_DIR, dir.isDirectory());
		File[] files = dir.listFiles(File::isFile);
		assertNotNull(files);
		int wp = 0;
		List<String> failed = new ArrayList<>();
		for (File f : files) {
			byte[] data = Files.readAllBytes(f.toPath());
			String sniffed = DocumentUtil.sniffOfficeContentType(data);
			if (sniffed == null || !sniffed.contains("wordperfect")) {
				continue;
			}
			wp++;
			String text = DocumentUtil.readDocument(data, MAX_CHARS, sniffed);
			if (text == null || text.isBlank()) {
				failed.add(f.getName() + " (v" + data[10] + "." + data[11] + ")");
			}
		}
		logger.info("WordPerfect corpus sweep: " + wp + " files, " + failed.size() + " failed " + failed);
		assertTrue("expected WordPerfect files in the corpus", wp > 0);
		assertTrue("WordPerfect files that extracted no text: " + failed, failed.isEmpty());
	}
}
