package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.junit.Test;

/**
 * Issue 2: the character description handed to the LLM/SD prompts was "inconsistent or wrong,
 * sometimes wildly so", and editing the character after extraction changed nothing.
 *
 * <p>Cause: every description the imaging path used was a frozen LLM string written once at
 * extraction — the {@code pbDescription} attribute, or {@code narrative.physicalDescription}, which
 * {@code ensureNarrative} overwrote with {@code buildPortraitPromptFromExtractedData}. None was
 * derived from the charPerson record, and the record's own hair/eye colour came from the random
 * race palette rather than from the extraction, so the two described different people.
 *
 * <p>These are the PURE halves of the fix — the free-text-to-colour mapping that seeds the record
 * from the extraction, the hair style/colour split, the narration composition, and the
 * hand-written-prompt guard that stops the refresh deleting a manual edit. The DB-bound halves
 * ({@code refreshNarrativeFromRecord} re-reading with {@code cache:false},
 * {@code ProfileUtil.updateProfile}) need a live database and a populated Olio world; they are
 * exercised by the picture-book integration tests rather than here.
 */
public class TestPictureBookCharacterDescription {
	public static final Logger logger = LogManager.getLogger(TestPictureBookCharacterDescription.class);

	// ── Hair style / colour split ────────────────────────────────────────────

	/// The LLM emits hair as one blob: "long wavy strawberry-blonde". hairStyle is a plain string
	/// column and hairColor is a data.color FOREIGN ref, so the blob has to be split or
	/// describePhysical renders "brown long wavy strawberry-blonde hair".
	@Test
	public void hairStyleIsSeparatedFromTheColourWords() {
		assertEquals("long wavy", PictureBookUtil.extractHairStyle("long wavy strawberry-blonde hair"));
		assertEquals("short cropped", PictureBookUtil.extractHairStyle("short cropped black hair"));
		assertEquals("curly", PictureBookUtil.extractHairStyle("curly auburn"));
	}

	/// "hair"/"haired"/"locks"/"mane"/"tresses" are the NOUN. They are in the strip pattern so the
	/// colour scan can ignore them, but they must never BECOME the style — "hair" is not a hairstyle.
	@Test
	public void theWordHairIsNeverTheHairStyle() {
		assertNull("A pure colour carries no style", PictureBookUtil.extractHairStyle("brown hair"));
		assertNull(PictureBookUtil.extractHairStyle("auburn locks"));
		assertNull(PictureBookUtil.extractHairStyle("dark-haired"));
	}

	/// An LLM that cannot determine hair emits the literal string "null" as the VALUE. A blank check
	/// passes that through; isMeaningful is the project-standard guard.
	@Test
	public void literalNullIsNotAHairStyle() {
		assertNull(PictureBookUtil.extractHairStyle("null"));
		assertNull(PictureBookUtil.extractHairStyle("n/a"));
		assertNull(PictureBookUtil.extractHairStyle("unknown"));
		assertNull(PictureBookUtil.extractHairStyle(""));
		assertNull(PictureBookUtil.extractHairStyle(null));
	}

	// ── Colour mapping guards (the library-backed half needs a world; these do not) ──

	/// No OlioContext means no shared colour library to resolve against, so there is nothing to map
	/// to — and a null return is the contract: the caller KEEPS the baseline colour rather than
	/// writing a raw string onto a foreign ref.
	@Test
	public void colourMappingWithoutAWorldKeepsTheBaseline() {
		assertNull(PictureBookUtil.mapPersonColorOverride(null, "brown"));
		assertNull(PictureBookUtil.mapPersonColorOverride(null, "hazel"));
	}

	/// Same literal-"null" guard on the colour side.
	@Test
	public void literalNullIsNotAColour() {
		assertNull(PictureBookUtil.mapPersonColorOverride(null, "null"));
		assertNull(PictureBookUtil.mapPersonColorOverride(null, null));
	}

	// ── Narration composition ────────────────────────────────────────────────

	/// Appearance, then statistics, then outfit. Order is load-bearing: diffusion models weight the
	/// end of the prompt hardest, and the outfit is the part a scene most often overrides.
	@Test
	public void narrationIsAppearanceThenStatisticsThenOutfit() {
		String n = PictureBookUtil.composeRecordNarration(
			"a 40 year old man with blue eyes and brown messy hair.",
			"average, is lean and is of average build, has average wisdom, and is plain looking.",
			"wearing a wool coat and leather boots");
		assertNotNull(n);
		int appearance = n.indexOf("40 year old");
		int stats = n.indexOf("average wisdom");
		int outfit = n.indexOf("wool coat");
		assertTrue("appearance must come first", appearance >= 0 && appearance < stats);
		assertTrue("outfit must come last", outfit > stats);
	}

	/// Missing parts are dropped, not rendered as gaps or literal nulls — a character with no
	/// statistics record still gets a usable description from appearance + outfit.
	@Test
	public void missingPartsAreDroppedNotRendered() {
		String n = PictureBookUtil.composeRecordNarration("a tall woman.", null, "wearing a red dress");
		assertEquals("a tall woman. wearing a red dress", n);
		assertFalse(n.contains("null"));

		assertEquals("wearing a red dress",
			PictureBookUtil.composeRecordNarration(null, "   ", "wearing a red dress"));
		assertNull("Nothing describable at all must be null, so the caller can fall back",
			PictureBookUtil.composeRecordNarration(null, null, null));
		assertNull(PictureBookUtil.composeRecordNarration("", "  ", ""));
	}

	/// No doubled separators when a part already ends in a full stop.
	@Test
	public void sentencePunctuationIsNotDoubled() {
		String n = PictureBookUtil.composeRecordNarration("a tall woman.", "she is strong.", "wearing a red dress");
		assertFalse("A part ending in a full stop must not gain another", n.contains(".."));
		assertTrue(n.contains("woman. she is strong. wearing"));
	}

	/// The composition carries NO art style and NO setting. That is the whole reason it exists
	/// instead of reusing NarrativeUtil.getSDPrompt, which bakes a RANDOM style and era in at
	/// creation — the documented triple-style bug where one composite prompt carried an Archie
	/// Comics style, a CR Fashion Book style, and the book's actual Polaroid style.
	@Test
	public void narrationCarriesNoStyleClause() {
		String n = PictureBookUtil.composeRecordNarration(
			"a 40 year old man with blue eyes and brown messy hair.", null, "wearing a wool coat");
		assertFalse(n.contains("("));
		assertFalse(n.toLowerCase().contains("8k"));
		assertFalse(n.toLowerCase().contains("photograph taken"));
	}

	// ── Hand-written prompt guard ────────────────────────────────────────────

	/// The Manage Characters screen deliberately links to the full generic editor so
	/// narrative.sdPrompt can be written by hand. The record-driven refresh must not delete that.
	@Test
	public void aHandWrittenPromptIsRecognisedAndPreserved() {
		assertTrue(PictureBookUtil.isHandWrittenPrompt(
			"oil painting of the father, weathered face, standing in a doorway"));
		assertTrue(PictureBookUtil.isHandWrittenPrompt("portrait of Darby, hand written"));
	}

	/// ...and every prompt the pipeline itself writes must be recognised as regenerable. All three
	/// generators (PORTRAIT_QUALITY_PREAMBLE, buildPortraitPromptFromExtractedData,
	/// NarrativeUtil.getSDPrompt) share these opening tokens.
	@Test
	public void generatedPromptsAreRecognisedAsRegenerable() {
		assertFalse("PORTRAIT_QUALITY_PREAMBLE shape", PictureBookUtil.isHandWrittenPrompt(
			"8k highly detailed ((highest quality)) ((ultra realistic)) ((full body)) of a tall woman"));
		assertFalse("buildPortraitPromptFromExtractedData shape", PictureBookUtil.isHandWrittenPrompt(
			"8k highly detailed ((highest quality)) ((ultra realistic)) ((professional portrait)) of a man"));
		assertFalse("getSDPrompt shape", PictureBookUtil.isHandWrittenPrompt(
			"8k highly detailed ((highest quality)) ((ultra realistic)) ((full body)) of a plain looking"));
	}

	/// Blank or absent is NOT a hand edit — there is nothing to preserve, so the refresh fills it.
	@Test
	public void blankPromptIsNotAHandEdit() {
		assertFalse(PictureBookUtil.isHandWrittenPrompt(null));
		assertFalse(PictureBookUtil.isHandWrittenPrompt(""));
		assertFalse(PictureBookUtil.isHandWrittenPrompt("   "));
	}
}
