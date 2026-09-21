package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.cote.accountmanager.olio.picturebook.PbChapterBoundaryUtil;
import org.cote.accountmanager.olio.picturebook.PbChapterBoundaryUtil.ChapterRange;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Test;

/**
 * Focused unit test for {@link PbChapterBoundaryUtil#detectBoundaries(String)} (N3) over the REAL
 * manuscript {@code media/HarlotsEight_Vol1_SM.docx}.
 * <p>
 * This is a pure test: it extracts the manuscript text with {@link DocumentUtil#readDocument(byte[], int)}
 * (no DB, no LLM, no network) and asserts the detector returns {@code sourceRange}-shaped character-offset
 * ranges — ordered, non-overlapping, whole-text-covering, with sensible chapter titles — and NOT the
 * sentence-rechunked vector text chunks that {@code VectorUtil.chunkByChapter} produces.
 */
public class TestPbChapterBoundary {

	private static final int CAP = 16 * 1024 * 1024;

	private File locateFixture(String fileName) {
		File[] candidates = new File[] {
			new File("media", fileName),
			new File("AccountManagerObjects7/media", fileName),
			new File("src/AccountManagerObjects7/media", fileName)
		};
		for (File f : candidates) {
			if (f.exists()) {
				return f;
			}
		}
		return null;
	}

	/**
	 * The unit-level contract of the detector, exercised on synthetic text that packs every heading
	 * form N3 requires the broadened regex to catch (all of which the old case-sensitive
	 * {@code startsWith("Chapter ")} misses): all-caps, arabic, roman with/without a period, word-number
	 * (single and compound), {@code PART}, and a titled heading — plus a prose line beginning "Part of…"
	 * that must NOT be mistaken for a heading.
	 */
	@Test
	public void testDetectBoundariesHeadingForms() {
		String text =
			"Front matter before any chapter.\n" +   // leading, non-whitespace -> its own null-title range
			"CHAPTER ONE\n" +                        // all-caps + word-number
			"Body of the first chapter goes here.\n" +
			"Part of the reason he left was never explained.\n" + // prose 'Part of' -> NOT a heading
			"Chapter 2\n" +                          // arabic
			"Second chapter body.\n" +
			"IV.\n" +                                // bare roman with period
			"Fourth-ish body.\n" +
			"PART TWO\n" +                           // keyword + word-number
			"The march continued through cold rain.\n" + // prose body (must NOT begin with keyword+number)
			"Twenty-One\n" +                         // compound bare word-number
			"Late chapter body.\n" +
			"Chapter Nine: The Reckoning\n" +        // keyword + word-number + title
			"Final body.";

		List<ChapterRange> ranges = PbChapterBoundaryUtil.detectBoundaries(text);
		assertNotNull(ranges);

		// Titles in order: null (front matter), then the six real headings. The 'Part of the reason'
		// prose line must be absorbed into CHAPTER ONE's body, not create a range.
		StringBuilder titles = new StringBuilder();
		for (ChapterRange r : ranges) {
			titles.append("[").append(r.getTitle()).append("] ");
		}
		assertEquals("Detected titles were: " + titles, 7, ranges.size());
		assertEquals(null, ranges.get(0).getTitle());
		assertEquals("CHAPTER ONE", ranges.get(1).getTitle());
		assertEquals("Chapter 2", ranges.get(2).getTitle());
		assertEquals("IV.", ranges.get(3).getTitle());
		assertEquals("PART TWO", ranges.get(4).getTitle());
		assertEquals("Twenty-One", ranges.get(5).getTitle());
		assertEquals("Chapter Nine: The Reckoning", ranges.get(6).getTitle());

		assertContiguousCoverage(ranges, text.length());

		// The 'Part of the reason...' prose line is inside CHAPTER ONE's range, proving it was not
		// treated as a boundary.
		ChapterRange chapterOne = ranges.get(1);
		String chapterOneText = text.substring(chapterOne.getStartOffset(), chapterOne.getEndOffset());
		assertTrue("CHAPTER ONE range must absorb the 'Part of the reason' prose line",
			chapterOneText.contains("Part of the reason he left"));

		// Direct false-positive / true-positive checks on the classifier.
		assertFalse("'Part of the reason...' is prose, not a heading",
			PbChapterBoundaryUtil.isHeadingLine("Part of the reason he left was never explained."));
		assertFalse("bare pronoun 'I' is not a heading", PbChapterBoundaryUtil.isHeadingLine("I"));
		assertTrue("'I.' is a heading", PbChapterBoundaryUtil.isHeadingLine("I."));
		assertTrue("'CHAPTER ONE' is a heading", PbChapterBoundaryUtil.isHeadingLine("CHAPTER ONE"));
		assertTrue("'chapter 1' is a heading", PbChapterBoundaryUtil.isHeadingLine("chapter 1"));
		assertTrue("centred '   Chapter 3   ' is a heading", PbChapterBoundaryUtil.isHeadingLine("   Chapter 3   "));
	}

	/**
	 * The N3 acceptance test: run the detector over the actual manuscript and prove it found real
	 * chapter boundaries as character offsets (not vector chunks).
	 */
	@Test
	public void testDetectBoundariesInRealManuscript() throws Exception {
		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		assertTrue("Fixture must exist: " + docx.getAbsolutePath(), docx.exists());

		byte[] bytes = Files.readAllBytes(docx.toPath());
		assertTrue("Manuscript .docx must be non-empty", bytes.length > 0);

		String text = DocumentUtil.readDocument(bytes, CAP);
		assertNotNull("DocumentUtil.readDocument must extract text from the .docx", text);
		assertTrue("Extracted manuscript should be substantial prose (got " + text.length() + " chars)",
			text.length() > 2000);

		List<ChapterRange> ranges = PbChapterBoundaryUtil.detectBoundaries(text);
		assertNotNull(ranges);

		// ── PRINT the detected ranges (offsets + titles) so the result is visible in the build log ──
		System.out.println("=== HarlotsEight_Vol1_SM.docx: extracted " + text.length()
			+ " chars, detected " + ranges.size() + " ranges ===");
		int shown = 0;
		for (ChapterRange r : ranges) {
			if (shown++ >= 12) {
				System.out.println("  ... (" + (ranges.size() - 12) + " more)");
				break;
			}
			String headSnippet = text.substring(r.getStartOffset(),
				Math.min(r.getEndOffset(), r.getStartOffset() + 48)).replaceAll("\\s+", " ").trim();
			System.out.println(String.format("  [%d, %d) len=%-6d title=%-28s | %s",
				r.getStartOffset(), r.getEndOffset(), (r.getEndOffset() - r.getStartOffset()),
				(r.getTitle() == null ? "(front matter)" : "\"" + r.getTitle() + "\""), headSnippet));
		}

		// ── Coverage / ordering: ordered, non-overlapping, gapless over the whole text ──
		assertContiguousCoverage(ranges, text.length());

		// ── Multiple chapters detected with sensible chapter titles for the first several ──
		int titled = 0;
		int chapterTitled = 0;
		for (ChapterRange r : ranges) {
			if (r.getTitle() != null) {
				titled++;
				if (r.getTitle().toLowerCase().startsWith("chapter")) {
					chapterTitled++;
				}
			}
		}
		assertTrue("Expected multiple chapter boundaries in a full manuscript, got " + titled,
			titled >= 4);
		assertTrue("Most detected titles should be 'Chapter ...' headings; got " + chapterTitled
			+ " of " + titled + " titled ranges", chapterTitled >= 4);

		// ── NOT vector chunks: at least one chapter body must be a whole-chapter span (>> a sentence
		// chunk). VectorUtil.chunkByChapter would return sentence-sized serialized MODEL_VECTOR_CHUNK
		// strings; these are large contiguous offset spans of the ORIGINAL text. ──
		int maxLen = 0;
		for (ChapterRange r : ranges) {
			maxLen = Math.max(maxLen, r.getEndOffset() - r.getStartOffset());
		}
		assertTrue("A detected chapter span should be whole-chapter sized (not a sentence chunk); "
			+ "longest span was " + maxLen + " chars", maxLen > 1000);

		// ── Each titled range's offset actually points at its heading in the original text ──
		for (ChapterRange r : ranges) {
			if (r.getTitle() != null) {
				String fromStart = text.substring(r.getStartOffset()).stripLeading();
				assertTrue("Range at offset " + r.getStartOffset() + " should begin with its detected "
					+ "heading \"" + r.getTitle() + "\" but began with: "
					+ fromStart.substring(0, Math.min(40, fromStart.length())),
					fromStart.startsWith(r.getTitle()));
			}
		}
	}

	/** Assert the ranges are ordered, non-overlapping, non-empty, and cover exactly {@code [0, total)}. */
	private static void assertContiguousCoverage(List<ChapterRange> ranges, int total) {
		assertFalse("ranges must be non-empty for non-empty text", ranges.isEmpty());
		assertEquals("first range must start at 0", 0, ranges.get(0).getStartOffset());
		assertEquals("last range must end at text length", total,
			ranges.get(ranges.size() - 1).getEndOffset());
		int prevEnd = 0;
		for (ChapterRange r : ranges) {
			assertTrue("range must be non-empty: " + r, r.getEndOffset() > r.getStartOffset());
			assertEquals("ranges must be contiguous (gapless, non-overlapping) at " + r,
				prevEnd, r.getStartOffset());
			prevEnd = r.getEndOffset();
		}
	}
}
