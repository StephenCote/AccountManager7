package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Text-parity test for per-chapter extraction (S6).
 *
 * <p>Chapter offsets ({@code olio.pb.sourceRange.startOffset/endOffset}) are computed by
 * {@link PbServiceFacade#detectSourceBoundaries(BaseRecord, String)} over the text returned by
 * {@code ChapBookUtil.extractPoemText} — the canonical reader that runs {@code sanitizeText}
 * (CRLF→LF, C0 stripped). Those offsets are later applied by {@code PictureBookUtil.createFromScenes} and
 * {@code extractScenesOnly} to the text returned by {@link PictureBookUtil#extractWorkText}. If the two
 * readers do not produce the SAME string, every {@code substring(start, end)} lands on the wrong
 * characters — drifting by one per CRLF — and chapter N is extracted from a window that begins
 * mid-sentence in chapter N-1.
 *
 * <p>This test persists the REAL manuscript {@code media/HarlotsEight_Vol1_SM.docx} as a {@code data.data}
 * owned by a non-admin user, detects boundaries through the production facade, and asserts that, for
 * every detected range, {@code extractWorkText(...).substring(start, end)} begins with that range's
 * detected heading. It also pins the manuscript's structure: 21 ranges titled exactly
 * "Chapter 1".."Chapter 21", unique, monotonic, contiguous.
 *
 * <p>No LLM, no SD — pure DB + text extraction; runs in the default suite. Never runs as admin.
 */
public class TestPbChapterTextParity extends BaseTest {

	private static final String DOCX_MIME =
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	private static final int EXPECTED_CHAPTERS = 21;

	/** Offset of the first line in {@code text} whose trimmed form starts with {@code prefix}, or -1. */
	private static int indexOfLineStartingWith(String text, String prefix) {
		int i = 0;
		int n = text.length();
		while (i < n) {
			int j = i;
			while (j < n && text.charAt(j) != '\n' && text.charAt(j) != '\r') j++;
			if (text.substring(i, j).trim().startsWith(prefix)) return i;
			i = (j < n && text.charAt(j) == '\r' && j + 1 < n && text.charAt(j + 1) == '\n') ? j + 2 : j + 1;
		}
		return -1;
	}

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

	@Test
	public void testChapterOffsetsSliceTheSameTextTheExtractorReads() throws Exception {
		OlioModelNames.use();

		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		byte[] bytes = Files.readAllBytes(docx.toPath());
		assertTrue("Manuscript .docx must be non-empty", bytes.length > 0);

		BaseRecord owner = getCreateUser("pbParityOwner");
		assertNotNull("owner test user", owner);
		assertFalse("acting user must not be admin", "admin".equals(owner.get(FieldNames.FIELD_NAME)));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		String docName = "harlots-parity-" + System.currentTimeMillis() + ".docx";
		BaseRecord manuscript = getCreateData(owner, docName, DOCX_MIME, bytes, "~/Data", orgId);
		assertNotNull("manuscript data.data must be created", manuscript);
		String sourceOid = manuscript.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("manuscript objectId", sourceOid);

		// ── 1. Offsets: the production facade path (ChapBookUtil.extractPoemText + PbChapterBoundaryUtil) ──
		List<Map<String, Object>> ranges = PbServiceFacade.detectSourceBoundaries(owner, sourceOid);
		assertNotNull("detectSourceBoundaries must return a list", ranges);
		System.out.println("=== detectSourceBoundaries returned " + ranges.size() + " ranges for " + docName + " ===");

		// Structure: 21 titled chapters, "Chapter 1".."Chapter 21", unique, monotonic, contiguous.
		// A leading untitled front-matter range is tolerated only if the detector emits one; the
		// manuscript's title page is below MIN_STANDALONE_LEAD_CHARS so none is expected.
		List<Map<String, Object>> titled = new ArrayList<>();
		for (Map<String, Object> r : ranges) {
			if (r.get(OlioFieldNames.FIELD_PB_TITLE) != null) titled.add(r);
		}
		assertEquals("manuscript must yield exactly " + EXPECTED_CHAPTERS + " titled chapter ranges (got "
			+ titled.size() + " titled of " + ranges.size() + " total)", EXPECTED_CHAPTERS, titled.size());
		assertTrue("at most one untitled front-matter range is allowed (total=" + ranges.size() + ")",
			ranges.size() - titled.size() <= 1);

		Set<String> seenTitles = new HashSet<>();
		int prevEnd = -1;
		for (int i = 0; i < ranges.size(); i++) {
			Map<String, Object> r = ranges.get(i);
			Object so = r.get(OlioFieldNames.FIELD_PB_START_OFFSET);
			Object eo = r.get(OlioFieldNames.FIELD_PB_END_OFFSET);
			assertTrue("startOffset must be an Integer (was " + so + ")", so instanceof Integer);
			assertTrue("endOffset must be an Integer (was " + eo + ")", eo instanceof Integer);
			int start = ((Integer) so).intValue();
			int end = ((Integer) eo).intValue();
			assertTrue("range " + i + " must be non-empty [" + start + ", " + end + ")", end > start);
			if (prevEnd >= 0) {
				assertEquals("range " + i + " must be contiguous with the previous range (prevEnd=" + prevEnd
					+ ", start=" + start + ")", prevEnd, start);
			} else {
				assertEquals("first range must start at offset 0", 0, start);
			}
			prevEnd = end;
		}
		for (int c = 0; c < titled.size(); c++) {
			String title = (String) titled.get(c).get(OlioFieldNames.FIELD_PB_TITLE);
			assertEquals("titled range " + c + " must be 'Chapter " + (c + 1) + "'", "Chapter " + (c + 1), title);
			assertTrue("chapter titles must be unique (duplicate '" + title + "')", seenTitles.add(title));
		}

		// ── 2. Text: what createFromScenes / extractScenesOnly actually slice with those offsets ──
		BaseRecord work = PictureBookUtil.findWork(owner, sourceOid);
		assertNotNull("findWork must resolve the manuscript for its owner", work);
		String extractorText = PictureBookUtil.extractWorkText(owner, work);
		assertNotNull("extractWorkText must return text", extractorText);
		assertFalse("extractWorkText must return non-empty text", extractorText.isEmpty());

		// The facade computed its offsets over ChapBookUtil.extractPoemText(data). Read that string here
		// too so the failure message can show the actual drift when the two readers disagree.
		String facadeText = ChapBookUtil.extractPoemText(work);
		assertNotNull("facade reader must return text", facadeText);
		System.out.println("facade text length=" + facadeText.length() + ", extractor text length="
			+ extractorText.length() + ", CR count in extractor text="
			+ extractorText.chars().filter(ch -> ch == '\r').count());

		int lastEnd = ((Integer) ranges.get(ranges.size() - 1).get(OlioFieldNames.FIELD_PB_END_OFFSET)).intValue();
		assertTrue("last range end (" + lastEnd + ") must not exceed the extractor text length ("
			+ extractorText.length() + ")", lastEnd <= extractorText.length());

		// ── 3. Parity: every titled range, sliced from the EXTRACTOR's text, must begin with its heading ──
		List<String> mismatches = new ArrayList<>();
		for (int c = 0; c < titled.size(); c++) {
			Map<String, Object> r = titled.get(c);
			int start = ((Integer) r.get(OlioFieldNames.FIELD_PB_START_OFFSET)).intValue();
			int end = ((Integer) r.get(OlioFieldNames.FIELD_PB_END_OFFSET)).intValue();
			String title = (String) r.get(OlioFieldNames.FIELD_PB_TITLE);
			String slice = extractorText.substring(start, Math.min(end, extractorText.length()));
			// Same convention as resolveExtractRange/createFromScenes: the slice is what reaches the
			// prompt, so the heading must be at its very start (modulo leading blank lines). The ONE
			// exception is chapter 1 when the detector folded a sub-MIN_STANDALONE_LEAD_CHARS title page
			// into it (start == 0): there the heading must appear as a line start within that short lead.
			String head = slice.stripLeading();
			boolean ok;
			if (c == 0 && start == 0) {
				int at = indexOfLineStartingWith(slice, title);
				ok = at >= 0 && slice.substring(0, at).trim().length() < PbChapterBoundaryUtil.MIN_STANDALONE_LEAD_CHARS;
			} else {
				ok = head.startsWith(title);
			}
			if (!ok) {
				String shown = head.length() > 48 ? head.substring(0, 48) : head;
				mismatches.add("[" + start + ", " + end + ") expected to start with '" + title + "' but starts with '"
					+ shown.replace("\n", "\\n").replace("\r", "\\r") + "'");
			}
		}
		if (!mismatches.isEmpty()) {
			System.out.println("=== TEXT PARITY MISMATCHES (" + mismatches.size() + " of " + titled.size() + ") ===");
			for (String m : mismatches) System.out.println("  " + m);
		}
		assertTrue("extractWorkText must return the SAME text the facade computed chapter offsets over; "
			+ mismatches.size() + " of " + titled.size() + " chapter slices do not begin with their heading. First: "
			+ (mismatches.isEmpty() ? "" : mismatches.get(0)), mismatches.isEmpty());

		// Whole-string parity is the stronger statement; assert it last so the per-chapter diagnostics above
		// are what a failure shows.
		assertEquals("extractWorkText and ChapBookUtil.extractPoemText must produce identical text",
			facadeText, extractorText);
	}

	/**
	 * The drift case the DOCX fixture does not exhibit (Tika emits LF-only text for it): a plain-text
	 * manuscript uploaded from Windows with CRLF line endings. The facade computes offsets over
	 * {@code sanitizeText}'d text (CRLF→LF), so every chapter's offset is short by the number of CRLFs
	 * before it; a reader that hands back the raw CRLF bytes slices chapter N starting inside chapter N-1.
	 * Before S6 {@code extractWorkText} read the raw text and this fails; after S6 both paths share the
	 * canonical reader and it passes.
	 */
	@Test
	public void testCrlfPlainTextManuscriptParity() throws Exception {
		OlioModelNames.use();

		BaseRecord owner = getCreateUser("pbParityOwner");
		assertNotNull("owner test user", owner);
		assertFalse("acting user must not be admin", "admin".equals(owner.get(FieldNames.FIELD_NAME)));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		final int chapters = 6;
		StringBuilder sb = new StringBuilder();
		sb.append("THE CRLF MANUSCRIPT\r\nby a Windows text editor\r\n\r\n");
		for (int c = 1; c <= chapters; c++) {
			sb.append("Chapter ").append(c).append("\r\n\r\n");
			for (int p = 0; p < 12; p++) {
				sb.append("Paragraph ").append(p + 1).append(" of chapter ").append(c)
				  .append(". Marla crossed the yard and the gate swung shut behind her; the dog did not bark.\r\n");
			}
			sb.append("\r\n");
		}
		String crlfText = sb.toString();
		long crlfCount = crlfText.chars().filter(ch -> ch == '\r').count();
		assertTrue("fixture must actually contain CRLF line endings", crlfCount > chapters);

		String docName = "crlf-parity-" + System.currentTimeMillis() + ".txt";
		BaseRecord manuscript = getCreateData(owner, docName, "text/plain",
			crlfText.getBytes(java.nio.charset.StandardCharsets.UTF_8), "~/Data", orgId);
		assertNotNull("CRLF manuscript data.data must be created", manuscript);
		String sourceOid = manuscript.get(FieldNames.FIELD_OBJECT_ID);

		List<Map<String, Object>> ranges = PbServiceFacade.detectSourceBoundaries(owner, sourceOid);
		assertNotNull(ranges);
		List<Map<String, Object>> titled = new ArrayList<>();
		for (Map<String, Object> r : ranges) {
			if (r.get(OlioFieldNames.FIELD_PB_TITLE) != null) titled.add(r);
		}
		assertEquals("CRLF manuscript must yield " + chapters + " titled chapters", chapters, titled.size());

		BaseRecord work = PictureBookUtil.findWork(owner, sourceOid);
		assertNotNull("findWork must resolve the CRLF manuscript", work);
		String extractorText = PictureBookUtil.extractWorkText(owner, work);
		assertNotNull("extractWorkText must return text", extractorText);
		String facadeText = ChapBookUtil.extractPoemText(work);
		System.out.println("CRLF case: facade text length=" + facadeText.length() + ", extractor text length="
			+ extractorText.length() + ", CRs in extractor text=" + extractorText.chars().filter(ch -> ch == '\r').count()
			+ ", CRs in source=" + crlfCount);

		List<String> mismatches = new ArrayList<>();
		for (int c = 0; c < titled.size(); c++) {
			Map<String, Object> r = titled.get(c);
			int start = ((Integer) r.get(OlioFieldNames.FIELD_PB_START_OFFSET)).intValue();
			int end = ((Integer) r.get(OlioFieldNames.FIELD_PB_END_OFFSET)).intValue();
			String title = (String) r.get(OlioFieldNames.FIELD_PB_TITLE);
			if (end > extractorText.length()) {
				mismatches.add("[" + start + ", " + end + ") '" + title + "' end exceeds extractor text length "
					+ extractorText.length());
				continue;
			}
			String slice = extractorText.substring(start, end);
			boolean ok;
			if (c == 0 && start == 0) {
				int at = indexOfLineStartingWith(slice, title);
				ok = at >= 0 && slice.substring(0, at).trim().length() < PbChapterBoundaryUtil.MIN_STANDALONE_LEAD_CHARS;
			} else {
				ok = slice.stripLeading().startsWith(title);
			}
			if (!ok) {
				String head = slice.stripLeading();
				String shown = head.length() > 48 ? head.substring(0, 48) : head;
				mismatches.add("[" + start + ", " + end + ") expected to start with '" + title + "' but starts with '"
					+ shown.replace("\n", "\\n").replace("\r", "\\r") + "'");
			}
		}
		if (!mismatches.isEmpty()) {
			System.out.println("=== CRLF TEXT PARITY MISMATCHES (" + mismatches.size() + " of " + titled.size() + ") ===");
			for (String m : mismatches) System.out.println("  " + m);
		}
		assertTrue("extractWorkText must return the SAME text the facade computed chapter offsets over; "
			+ mismatches.size() + " of " + titled.size() + " chapter slices do not begin with their heading. First: "
			+ (mismatches.isEmpty() ? "" : mismatches.get(0)), mismatches.isEmpty());
		assertEquals("extractWorkText and ChapBookUtil.extractPoemText must produce identical text (CRLF source)",
			facadeText, extractorText);
	}
}
