package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Chapter-heading → character-offset boundary detector for the PictureBook "N-series" (N3).
 * <p>
 * <b>Purpose.</b> Given the plain text of a single manuscript, find where each chapter begins and
 * return the spans as <b>character offsets into that exact text</b>. The offsets line up with the
 * {@code olio.pb.sourceRange} sub-record ({@code startOffset}/{@code endOffset}/{@code title}) so the
 * Ux review step can feed a chosen (possibly hand-edited) range straight into
 * {@link PbServiceFacade#createChapter}.
 * <p>
 * <b>Why not {@code VectorUtil.chunkByChapter}.</b> That method (a) tests only the case-sensitive
 * exact prefix {@code "Chapter "}, missing {@code CHAPTER ONE}, {@code PART TWO}, roman numerals, and
 * word-number forms; and (b) returns re-chunked, sentence-split <i>vector text chunks</i> for the
 * embedding store, not character offsets. It is left untouched because it serves embeddings; this
 * class is the offset-shaped detector N2/N3 need.
 * <p>
 * <b>Pure function.</b> {@link #detectBoundaries(String)} has no DB access, no embedding side effects,
 * and no I/O — it only reads the string it is handed. Manuscript resolution / text extraction lives in
 * {@link PbServiceFacade#detectSourceBoundaries}.
 * <p>
 * <b>Coverage contract.</b> The returned ranges are ordered, non-overlapping, strictly increasing, and
 * together cover the whole text {@code [0, text.length())} (so the union has no gaps). Each chapter
 * range runs from the start of its heading line (inclusive) to the start of the next heading line
 * (exclusive), or to the end of the text for the last chapter.
 * <p>
 * <b>Leading text (before the first heading).</b> Substantial content before the first detected
 * heading (a prologue — at least {@link #MIN_STANDALONE_LEAD_CHARS} non-whitespace-trimmed characters)
 * becomes its own leading range with a {@code null} title ("front matter"). A short lead — a title
 * page, an author line, whitespace — is folded into chapter&nbsp;1 (the first chapter's
 * {@code startOffset} is pulled back to {@code 0}). Measured on {@code HarlotsEight_Vol1_SM.docx}: the
 * lead is an 18-character title line, and giving it its own range produced a phantom, untitled
 * "Chapter 1" book with nothing to extract and shifted every real chapter's number by one. When the
 * text contains no detectable heading at all, a single range {@code [0, length)} with a {@code null}
 * title is returned.
 */
public class PbChapterBoundaryUtil {
	private static final Logger logger = LogManager.getLogger(PbChapterBoundaryUtil.class);

	private PbChapterBoundaryUtil() {
		/// static utility
	}

	/**
	 * Upper bound on the length of a heading line that carries a title AFTER its designator (e.g.
	 * "Chapter One: The Beginning") or after an explicit title separator (e.g. "Chapter — Dawn").
	 * A bare "Chapter 1" / "PART TWO" / roman / word-number heading is accepted regardless of this
	 * cap; only titled forms are length-gated so a prose sentence that merely begins with
	 * "Part Two decades ago, she walked..." is not mistaken for a heading. Manual override (Q11) in
	 * the Ux is the safety net either way, but requiring a designator plus this cap keeps the
	 * auto-detected set clean.
	 */
	private static final int MAX_HEADING_LEN = 72;

	/**
	 * Minimum trimmed length of text before the first heading for it to be returned as its own
	 * untitled range. Below this it is a title page, not a prologue: too short to hold a scene and
	 * half the size of one extraction chunk.
	 */
	public static final int MIN_STANDALONE_LEAD_CHARS = 1000;

	/**
	 * Number-word alternation (compound forms first so the alternation is leftmost-longest correct):
	 * "twenty-one".."ninety-nine" before the simple ones..hundred. Used both as a designator after a
	 * keyword and as a bare whole-line heading.
	 */
	private static final String WORDNUM =
		"(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)[\\s-](?:one|two|three|four|five|six|seven|eight|nine)"
		+ "|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen"
		+ "|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred";

	/**
	 * Keyword head: a line that starts (after trimming) with a structural keyword as a whole word,
	 * capturing the run of separator characters that follows so {@link #isHeadingLine(String)} can tell
	 * an explicit title separator (":", "-", en/em dash) from mere whitespace.
	 */
	private static final Pattern KEYWORD_HEAD =
		Pattern.compile("^(chapter|part|book|canto|section)\\b([\\s.:\\-–—]*)", Pattern.CASE_INSENSITIVE);

	/** A designator token (arabic number, roman numeral, or number-word) anchored at the start of the remainder. */
	private static final Pattern DESIGNATOR_PREFIX =
		Pattern.compile("^(?:\\d+|" + WORDNUM + "|[ivxlcdm]+)\\b", Pattern.CASE_INSENSITIVE);

	/** A bare roman-numeral line, optionally with a trailing period/colon. */
	private static final Pattern ROMAN_LINE =
		Pattern.compile("^[ivxlcdm]{1,8}[.:]?$", Pattern.CASE_INSENSITIVE);

	/** A bare number-word line, optionally with a trailing period/colon. */
	private static final Pattern WORDNUM_LINE =
		Pattern.compile("^(?:" + WORDNUM + ")[.:]?$", Pattern.CASE_INSENSITIVE);

	/** Explicit title-separator characters (as opposed to plain whitespace) that follow a keyword. */
	private static final Pattern EXPLICIT_TITLE_SEP = Pattern.compile("[:\\-–—]");

	/**
	 * An ordered, non-overlapping chapter span expressed as character offsets into the detected text.
	 * {@link #startOffset} is inclusive, {@link #endOffset} is exclusive; {@link #title} is the detected
	 * heading (verbatim, trimmed) or {@code null} for a leading front-matter / no-heading range.
	 */
	public static final class ChapterRange {
		private final int startOffset;
		private final int endOffset;
		private final String title;

		public ChapterRange(int startOffset, int endOffset, String title) {
			this.startOffset = startOffset;
			this.endOffset = endOffset;
			this.title = title;
		}

		/** Inclusive character offset of the first character of this chapter. */
		public int getStartOffset() { return startOffset; }

		/** Exclusive character offset one past the last character of this chapter. */
		public int getEndOffset() { return endOffset; }

		/** The detected heading text (trimmed), or {@code null} for a leading/no-heading range. */
		public String getTitle() { return title; }

		@Override
		public String toString() {
			return "ChapterRange[" + startOffset + "," + endOffset + ")=" + (title == null ? "(none)" : "\"" + title + "\"");
		}
	}

	/**
	 * Detect chapter boundaries in {@code text} and return them as ordered, whole-text-covering
	 * character-offset ranges. See the class javadoc for the coverage and leading-text contracts.
	 *
	 * @param text the manuscript text (already extracted from its document, e.g. via
	 *             {@code DocumentUtil.readDocument}); {@code null}/empty yields an empty list.
	 * @return ordered {@link ChapterRange} list; empty only when {@code text} is null/empty.
	 */
	public static List<ChapterRange> detectBoundaries(String text) {
		List<ChapterRange> ranges = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return ranges;
		}

		int n = text.length();

		/// Walk the text line by line, tracking the character offset of the START of each line, so a
		/// detected heading's offset is the beginning of its own line (leading whitespace/centring
		/// included) - which makes the previous range end exactly where the next begins (no gaps).
		List<int[]> headingStarts = new ArrayList<>();
		List<String> headingTitles = new ArrayList<>();
		int i = 0;
		while (i < n) {
			int lineStart = i;
			int j = i;
			while (j < n && text.charAt(j) != '\n' && text.charAt(j) != '\r') {
				j++;
			}
			String line = text.substring(lineStart, j);
			/// Advance past the line terminator (CRLF, lone CR, or LF).
			int nextStart = j;
			if (j < n) {
				if (text.charAt(j) == '\r' && j + 1 < n && text.charAt(j + 1) == '\n') {
					nextStart = j + 2;
				}
				else {
					nextStart = j + 1;
				}
			}
			String trimmed = line.trim();
			if (isHeadingLine(trimmed)) {
				headingStarts.add(new int[] { lineStart });
				headingTitles.add(trimmed);
			}
			i = nextStart;
		}

		/// No heading anywhere: the whole text is one untitled range.
		if (headingStarts.isEmpty()) {
			ranges.add(new ChapterRange(0, n, null));
			return ranges;
		}

		int firstStart = headingStarts.get(0)[0];
		int chapterOneStart = firstStart;
		if (firstStart > 0) {
			String lead = text.substring(0, firstStart);
			if (lead.trim().length() >= MIN_STANDALONE_LEAD_CHARS) {
				/// A prologue-sized lead before the first heading: its own untitled leading range.
				ranges.add(new ChapterRange(0, firstStart, null));
			}
			else {
				/// Title page / whitespace: fold it into chapter 1 so coverage starts at 0.
				chapterOneStart = 0;
			}
		}

		for (int k = 0; k < headingStarts.size(); k++) {
			int start = (k == 0) ? chapterOneStart : headingStarts.get(k)[0];
			int end = (k + 1 < headingStarts.size()) ? headingStarts.get(k + 1)[0] : n;
			ranges.add(new ChapterRange(start, end, headingTitles.get(k)));
		}
		return ranges;
	}

	/**
	 * Decide whether a single (already-trimmed) line is a chapter heading.
	 * <p>
	 * Three accepted forms, all case-insensitive and tolerant of leading/centred whitespace (the
	 * caller trims):
	 * <ol>
	 *   <li><b>Keyword form</b> — starts with {@code chapter|part|book|canto|section} as a whole word,
	 *       followed by a designator (arabic/roman/number-word), or by an explicit title separator plus
	 *       a title, or nothing (the keyword alone). A keyword followed only by ordinary prose (e.g.
	 *       "Part of the reason...") is <b>not</b> a heading — the designator/explicit-separator
	 *       requirement is what rejects it.</li>
	 *   <li><b>Bare roman numeral</b> — the whole line is a roman numeral (optionally with a trailing
	 *       "." / ":"). A single bare letter with no punctuation ("I", "V", ...) is rejected to avoid
	 *       matching the pronoun "I"; "I.", "II", "IV." are accepted.</li>
	 *   <li><b>Bare number-word</b> — the whole line is a number word ("One", "Twenty-One.", ...).</li>
	 * </ol>
	 * <p>
	 * Public (side-effect-free) so the classifier's true/false-positive behavior can be unit-tested
	 * directly, and so callers that already have a single candidate line can reuse it without
	 * re-scanning whole text.
	 */
	public static boolean isHeadingLine(String raw) {
		if (raw == null) {
			return false;
		}
		String s = raw.trim();
		if (s.isEmpty()) {
			return false;
		}

		/// Form 1: keyword head.
		Matcher km = KEYWORD_HEAD.matcher(s);
		if (km.find()) {
			String seps = km.group(2);
			String rest = s.substring(km.end()).trim();
			if (rest.isEmpty()) {
				/// "Chapter", "Part.", "Part:" - keyword alone.
				return true;
			}
			Matcher dm = DESIGNATOR_PREFIX.matcher(rest);
			if (dm.find() && dm.start() == 0) {
				String afterDesig = rest.substring(dm.end()).trim();
				if (afterDesig.isEmpty()) {
					/// "Chapter 1", "PART TWO", "Chapter IV".
					return true;
				}
				/// "Chapter One: The Beginning" - titled; accept only if the line is heading-short.
				return s.length() <= MAX_HEADING_LEN;
			}
			/// No designator: accept only when an EXPLICIT title separator (":", "-", dash) followed the
			/// keyword ("Chapter: Dawn"), not mere whitespace ("Part of the reason...").
			if (seps != null && EXPLICIT_TITLE_SEP.matcher(seps).find()) {
				return s.length() <= MAX_HEADING_LEN;
			}
			return false;
		}

		/// Form 2: bare roman numeral line (guard the ambiguous single bare letter).
		if (ROMAN_LINE.matcher(s).matches() && s.length() >= 2) {
			return true;
		}

		/// Form 3: bare number-word line.
		if (WORDNUM_LINE.matcher(s).matches()) {
			return true;
		}

		return false;
	}
}
