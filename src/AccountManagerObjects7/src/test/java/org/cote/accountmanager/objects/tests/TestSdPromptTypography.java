package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.BeforeClass;
import org.junit.Test;

/// Pure, deterministic unit tests (no DB / no LLM / no SD server) for the ASCII-typography
/// normalization applied to Stable Diffusion prompts at the SDUtil.appendLoras seam.
///
/// Reproduced live from AccountManagerObjects7/logs/accountManagerObjects7.log: picture-book scene
/// prompts reach SD carrying U+2011 NON-BREAKING HYPHENS the composing LLM (gpt-oss:120b) emitted
/// itself - e.g. "masterpiece, best quality, interior of a futuristic gull<U+2011>wing cab, ..." and
/// "high<U+2011>altitude perspective of glass<U+2011>like air...". Verified by hexdump: the log bytes
/// are E2 80 91, so this is NOT mojibake and NOT a charset bug - the text is well-formed UTF-8 from
/// the resource file all the way to the wire (ClientUtil posts via BodyPublishers.ofString, UTF-8).
/// The characters are genuinely present, and CLIP's byte-level BPE tokenizes "gull<U+2011>wing"
/// differently from "gull-wing".
///
/// Normalizing the LLM's INPUTS cannot fix this, because the characters come out of the LLM's own
/// OUTPUT - hence the guard sits on the finished prompt, at the one seam every setPrompt(...) call in
/// SDUtil already passes through.
public class TestSdPromptTypography {
	public static final Logger logger = LogManager.getLogger(TestSdPromptTypography.class);

	/// The exact prompt shape observed live in the log. The separators below are U+2011, NOT ASCII
	/// hyphens - the two are visually identical in source, so verify by hexdump, not by eye. The
	/// _ASCII twin is what must come out the other side.
	private static final String LIVE_SCENE_PROMPT =
		"masterpiece, best quality, interior of a futuristic gull‑wing cab, glossy pleather seats, "
		+ "neon‑lit dashboard, high‑altitude perspective of glass‑like airlanes";

	private static final String LIVE_SCENE_PROMPT_ASCII =
		"masterpiece, best quality, interior of a futuristic gull-wing cab, glossy pleather seats, "
		+ "neon-lit dashboard, high-altitude perspective of glass-like airlanes";

	/// Schema registration only - no IOSystem, no DB. use() just adds the names to ModelNames.MODELS;
	/// the schema itself is not resolvable until something actually loads it off the classpath, hence
	/// the explicit RecordFactory.model(...) (loading the one model needed rather than all of them).
	@BeforeClass
	public static void useOlioModels() {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
	}

	private static void assertPureAscii(String label, String s) {
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			assertTrue(label + ": non-ASCII U+" + String.format("%04X", (int) c) + " survived at index " + i
				+ " -> " + s, c < 128);
		}
	}

	/// The dash family is the actual defect class. Each of these must collapse to a plain ASCII
	/// hyphen-minus, and each substitution must be 1:1 - PageIndexUtil's marker location relies on
	/// character offsets into replaceSmartQuotes' output staying stable (see the method's contract).
	@Test
	public void replaceSmartQuotesNormalizesTheWholeDashFamily() {
		char[] dashes = new char[] {
			'‐',  // hyphen
			'‑',  // non-breaking hyphen  <- the one seen live
			'‒',  // figure dash
			'–',  // en dash
			'—',  // em dash
			'―',  // horizontal bar
			'−'   // minus sign
		};
		for(char d : dashes) {
			String in = "gull" + d + "wing";
			String out = DocumentUtil.replaceSmartQuotes(in);
			assertEquals("U+" + String.format("%04X", (int) d) + " must normalize to an ASCII hyphen",
				"gull-wing", out);
			assertEquals("U+" + String.format("%04X", (int) d) + " must be a 1:1 replacement - character "
				+ "offsets into the result must stay stable for PageIndexUtil's marker location",
				in.length(), out.length());
		}
	}

	/// Guard the behavior that already existed, so extending the dash class didn't regress it.
	@Test
	public void replaceSmartQuotesStillNormalizesQuotesEllipsisAndNbsp() {
		assertEquals("\"quoted\"", DocumentUtil.replaceSmartQuotes("“quoted”"));
		assertEquals("Duña's", DocumentUtil.replaceSmartQuotes("Duña‘s"));
		assertEquals("Duña's", DocumentUtil.replaceSmartQuotes("Duña’s"));
		assertEquals("wait...", DocumentUtil.replaceSmartQuotes("wait…"));
		assertEquals("a b", DocumentUtil.replaceSmartQuotes("a b"));
		assertNull("A null input must stay null", DocumentUtil.replaceSmartQuotes(null));
	}

	/// A prompt with no sdConfig at all still has to be normalized - appendLoras used to return the
	/// prompt untouched on that path, and it is a real path (callers pass a null config).
	@Test
	public void appendLorasNormalizesEvenWithNoConfig() {
		String out = SDUtil.appendLoras(LIVE_SCENE_PROMPT, null);
		assertEquals("The live scene prompt must reach SD as plain ASCII", LIVE_SCENE_PROMPT_ASCII, out);
		assertPureAscii("no-config path", out);
		assertFalse("No U+2011 may survive to SD", out.indexOf('‑') >= 0);
	}

	/// A null prompt must still be handled - normalization must not turn it into "null" or throw.
	@Test
	public void appendLorasNullPromptStaysNull() {
		assertNull(SDUtil.appendLoras(null, null));
	}

	/// The main path: a real olio.sd.config carrying LORAs. The prompt must be normalized AND the
	/// LORA entries appended, in that order, with the LORA syntax itself left intact.
	@Test
	public void appendLorasNormalizesAndStillAppendsLoras() throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("loras", Arrays.asList("detailTweaker:0.8", "filmGrain:0.4"));

		String out = SDUtil.appendLoras(LIVE_SCENE_PROMPT, cfg);

		assertEquals("Prompt must be normalized and both LORAs appended",
			LIVE_SCENE_PROMPT_ASCII + ", <lora:detailTweaker:0.8>, <lora:filmGrain:0.4>", out);
		assertPureAscii("lora path", out);
	}

	/// An sdConfig with an empty LORA list falls through appendLoras' loop to the trailing return -
	/// another path that previously returned the prompt untouched.
	@Test
	public void appendLorasNormalizesWhenConfigHasNoLoras() throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		String out = SDUtil.appendLoras(LIVE_SCENE_PROMPT, cfg);
		assertEquals("An empty LORA list must not skip normalization", LIVE_SCENE_PROMPT_ASCII, out);
		assertPureAscii("empty-lora path", out);
	}

	/// The em dashes that the pictureBook.scene-image-prompt template itself contributes, plus the
	/// smart quotes an LLM likes to wrap style tags in, must not reach SD either.
	@Test
	public void appendLorasNormalizesEmDashAndSmartQuotesInComposedPrompt() {
		String composed = "masterpiece, best quality, a rain‑slick alley — neon signs, "
			+ "“cinematic” lighting, moody…";
		String out = SDUtil.appendLoras(composed, null);
		assertEquals("masterpiece, best quality, a rain-slick alley - neon signs, "
			+ "\"cinematic\" lighting, moody...", out);
		assertPureAscii("composed path", out);
	}
}
