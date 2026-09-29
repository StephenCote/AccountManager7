package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

/**
 * A PictureBook character's race comes from the manuscript or not at all.
 *
 * <p>Before this change {@code PictureBookUtil.createCharPerson} copied the RANDOM race that
 * {@code CharacterUtil.randomPerson} rolled onto every extracted character (and linked that random
 * race's hair/eye palette), and {@code NarrativeUtil.describePhysical} — the preferred imaging
 * narration — then rendered "<age> year old <random race> <gender>" into every portrait and scene
 * prompt. Stephen: "STOP CHANGING THE RACE OF MY CHARACTERS."
 *
 * <p>Pure tests (no DB, no LLM): the race-resolution function, the record-derived narration over
 * a schema-built charPerson with an EMPTY race list, and the text-stated colouring that now
 * travels with the record instead ({@code ethnicity} + {@code pbAppearanceNotes}).
 */
public class TestPbCharacterRaceFromText {
	public static final Logger logger = LogManager.getLogger(TestPbCharacterRaceFromText.class);

	private static final List<String> RACE_WORDS = Arrays.asList(
		"white", "black", "asian", "american indian", "alaska native", "native hawaiian",
		"pacific islander", "lunatic", "robot", "monster", "succubus", "vampire", "exraterrestrial",
		"elf", "dwarf", "fairy", "unknown", "custom");

	private static Map<String, Object> charData(String race) {
		Map<String, Object> m = new HashMap<>();
		if (race != null) m.put("race", race);
		return m;
	}

	// ── resolveTextRace: the only source of a race on the record ─────────────────────────────

	@Test
	public void textStatedRaceIsKeptVerbatim() {
		assertEquals(Arrays.asList(RaceEnumType.E.name()), PictureBookUtil.resolveTextRace(charData("White"), "t"));
		assertEquals(Arrays.asList(RaceEnumType.C.name()), PictureBookUtil.resolveTextRace(charData("Black"), "t"));
		assertEquals(Arrays.asList(RaceEnumType.B.name()), PictureBookUtil.resolveTextRace(charData("asian"), "t"));
		assertEquals(Arrays.asList(RaceEnumType.X.name()), PictureBookUtil.resolveTextRace(charData("Elf"), "t"));
		// Already an enum constant name (a re-run over persisted data).
		assertEquals(Arrays.asList(RaceEnumType.E.name()), PictureBookUtil.resolveTextRace(charData("E"), "t"));
	}

	@Test
	public void noTextRaceMeansNoRaceAtAll() {
		assertTrue(PictureBookUtil.resolveTextRace(null, "t").isEmpty());
		assertTrue(PictureBookUtil.resolveTextRace(charData(null), "t").isEmpty());
		assertTrue("the reduce template's explicit 'not indicated' answer",
			PictureBookUtil.resolveTextRace(charData("Unknown"), "t").isEmpty());
		assertTrue("LLM literal placeholder", PictureBookUtil.resolveTextRace(charData("null"), "t").isEmpty());
		assertTrue("LLM literal placeholder", PictureBookUtil.resolveTextRace(charData("n/a"), "t").isEmpty());
		assertTrue(PictureBookUtil.resolveTextRace(charData("   "), "t").isEmpty());
		assertTrue("free text that maps to no RaceEnumType must NOT be coerced to anything",
			PictureBookUtil.resolveTextRace(charData("olive-skinned Mediterranean"), "t").isEmpty());
		Map<String, Object> notAString = new HashMap<>();
		notAString.put("race", Arrays.asList("White"));
		assertTrue(PictureBookUtil.resolveTextRace(notAString, "t").isEmpty());
	}

	// ── describePhysical over a record with no race: no race word may appear ──────────────────

	private static BaseRecord charPerson(List<String> race, List<String> ethnicity) throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_CHAR_PERSON);
		RecordFactory.model("data.color");
		RecordFactory.model(ModelNames.MODEL_ATTRIBUTE);
		BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		person.set(FieldNames.FIELD_NAME, "Test Person");
		person.set(FieldNames.FIELD_GENDER, "female");
		person.set(FieldNames.FIELD_AGE, 28);
		person.set(OlioFieldNames.FIELD_RACE, new ArrayList<>(race));
		if (ethnicity != null) person.set(OlioFieldNames.FIELD_ETHNICITY, new ArrayList<>(ethnicity));
		BaseRecord hair = RecordFactory.newInstance("data.color");
		hair.set(FieldNames.FIELD_NAME, "Auburn");
		BaseRecord eye = RecordFactory.newInstance("data.color");
		eye.set(FieldNames.FIELD_NAME, "Green");
		person.set(OlioFieldNames.FIELD_HAIR_COLOR, hair);
		person.set(OlioFieldNames.FIELD_EYE_COLOR, eye);
		return person;
	}

	private static PersonalityProfile profileFor(BaseRecord person) {
		PersonalityProfile pp = new PersonalityProfile();
		pp.setRecord(person);
		pp.setAge(28);
		pp.setGender("female");
		return pp;
	}

	private static void assertNoRaceWord(String d) {
		String lower = d.toLowerCase();
		for (String w : RACE_WORDS) {
			assertFalse("A record whose text stated no race must carry no race word; found '" + w
				+ "' in: " + d, lower.contains(w));
		}
	}

	@Test
	public void emptyRaceListRendersNoRaceWord() throws Exception {
		BaseRecord person = charPerson(Collections.emptyList(), null);
		String d = NarrativeUtil.describePhysical(profileFor(person));
		assertNotNull(d);
		logger.info("describePhysical (race unset): " + d);
		assertNoRaceWord(d);
		assertFalse(d.toLowerCase().contains("null"));
		assertFalse("no doubled space where the race word used to be: " + d, d.contains("  "));
		assertTrue(d.contains("28 year old woman with green eyes and auburn hair."));
	}

	@Test
	public void textStatedRaceIsRendered() throws Exception {
		BaseRecord person = charPerson(Arrays.asList(RaceEnumType.E.name()), null);
		String d = NarrativeUtil.describePhysical(profileFor(person));
		logger.info("describePhysical (race E): " + d);
		assertTrue("the text's own race word must survive: " + d, d.contains("28 year old White woman"));
	}

	@Test
	public void nullRaceListIsTolerated() {
		assertEquals("", NarrativeUtil.getRaceDescription(null));
		assertEquals("", NarrativeUtil.getRaceDescription(Collections.emptyList()));
		assertEquals("", NarrativeUtil.getEthnicityDescription(null, null));
		assertEquals("Irish", NarrativeUtil.getEthnicityDescription(Arrays.asList("NINE"), null));
	}

	// ── appearanceNotes: the manuscript's own words for skin / marks ─────────────────────────

	@SuppressWarnings("unchecked")
	private static Map<String, Object> withPhysical(String skin, String distinguishing) {
		Map<String, Object> physical = new LinkedHashMap<>();
		if (skin != null) physical.put("skin", skin);
		if (distinguishing != null) physical.put("distinguishing", distinguishing);
		Map<String, Object> m = new HashMap<>();
		m.put("physical", physical);
		return m;
	}

	@Test
	public void appearanceNotesAreTextOnly() {
		assertEquals("", PictureBookUtil.appearanceNotes(null));
		assertEquals("", PictureBookUtil.appearanceNotes(new HashMap<>()));
		assertEquals("", PictureBookUtil.appearanceNotes(withPhysical(null, null)));
		assertEquals("LLM placeholders are not appearance", "",
			PictureBookUtil.appearanceNotes(withPhysical("null", "unknown")));
		assertEquals("pale freckled skin", PictureBookUtil.appearanceNotes(withPhysical("pale freckled", null)));
		assertEquals("no doubled 'skin skin'", "dark skin",
			PictureBookUtil.appearanceNotes(withPhysical("dark skin", null)));
		assertEquals("olive complexion", PictureBookUtil.appearanceNotes(withPhysical("olive complexion", null)));
		assertEquals("pale skin, scar over left brow",
			PictureBookUtil.appearanceNotes(withPhysical("pale", "scar over left brow (mentioned in ch. 2).")));
		assertEquals("scar over left brow", PictureBookUtil.appearanceNotes(withPhysical(null, "scar over left brow")));
	}

	@Test
	public void appendExtrasKeepsOneSentence() {
		assertEquals("x.", PictureBookUtil.appendExtras("x.", Collections.emptyList()));
		assertEquals(null, PictureBookUtil.appendExtras(null, Collections.emptyList()));
		assertEquals("a, b.", PictureBookUtil.appendExtras(null, Arrays.asList("a", "b")));
		assertEquals("28 year old woman with green eyes and auburn hair, Irish heritage, pale freckled skin.",
			PictureBookUtil.appendExtras("28 year old woman with green eyes and auburn hair.",
				Arrays.asList("Irish heritage", "pale freckled skin")));
	}

	/// The whole record-derived physical sentence for a character whose text gave skin + ethnicity
	/// but no census race: coloured by the manuscript's words, with no race word substituted in.
	@Test
	public void recordNarrationCarriesTextColouringWithoutARaceWord() throws Exception {
		BaseRecord person = charPerson(Collections.emptyList(), Arrays.asList("NINE"));
		AttributeUtil.addAttribute(person, PictureBookUtil.ATTR_APPEARANCE_NOTES,
			PictureBookUtil.appearanceNotes(withPhysical("pale freckled", "scar over left brow")));

		String physical = NarrativeUtil.describePhysical(profileFor(person));
		String folded = PictureBookUtil.appendTextAppearance(physical, person, person, "Test Person");
		logger.info("record narration (text colouring, no race): " + folded);
		assertNoRaceWord(folded);
		assertTrue(folded, folded.endsWith("28 year old woman with green eyes and auburn hair, Irish heritage, "
			+ "pale freckled skin, scar over left brow."));
		assertEquals("one sentence", folded.indexOf('.'), folded.length() - 1);

		// Nothing stated -> nothing added: the sentence is exactly describePhysical's.
		BaseRecord bare = charPerson(Collections.emptyList(), null);
		String bareD = NarrativeUtil.describePhysical(profileFor(bare));
		assertEquals(bareD, PictureBookUtil.appendTextAppearance(bareD, bare, bare, "Bare"));
	}

	// ── groundRaceAndEthnicity: the LLM's label must be in the passages it was given ─────────
	//
	// Passages are lifted from HarlotsEight_Vol1_SM.docx, the manuscript the failures were measured
	// on. The real reduce step returned "Scottish" for Lara MacIntyre and Caleb MacKay, "Other
	// Asian" for the dark-skinned shopkeeper, "Vampire" for Vlad and "European/Anglo Saxon" for
	// Mister MacWhish, none of which the text says anywhere.

	private static final String FAIRY_PASSAGE = "The dark fairy, Visella, slipped between the pines. "
		+ "Skin black as night, wings folded tight against her back, she watched the road.";
	private static final String MACINTYRE_PASSAGE = "Lara MacIntyre pulled her shawl tighter and glanced at "
		+ "Caleb MacKay. \"We're late,\" she said. Her red hair was tied back with a strip of leather.";
	private static final String SHOPKEEPER_PASSAGE = "The dark skinned shopkeeper wiped the counter and "
		+ "did not look up. He wore a stained apron over a dirty white shirt.";
	private static final String VLAD_PASSAGE = "Vlad's near-white skin caught the lamplight. He smiled "
		+ "without warmth and adjusted his black gloves.";

	private static Map<String, Object> llm(String race, String raceEvidence, String ethnicity, String ethnicityEvidence) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", "x");
		if (race != null) m.put("race", race);
		if (raceEvidence != null) m.put(PictureBookUtil.KEY_RACE_EVIDENCE, raceEvidence);
		if (ethnicity != null) m.put("ethnicity", ethnicity);
		if (ethnicityEvidence != null) m.put(PictureBookUtil.KEY_ETHNICITY_EVIDENCE, ethnicityEvidence);
		m.put("clothing_style", "worn leather");
		return m;
	}

	@Test
	public void statedFairyIsKept() {
		// Old prompt: no evidence field, the word is in the passages.
		Map<String, Object> d = llm("Fairy", null, "", null);
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Fairy", d.get("race"));
		// New prompt: a real quote that contains the word.
		d = llm("Fairy", "The dark fairy, Visella", "", "");
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Fairy", d.get("race"));
		// Inflected surface form of the label's own word.
		d = llm("Fairy", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "Two fairies argued over the last honeycake.", "t");
		assertEquals("Fairy", d.get("race"));
	}

	@Test
	public void surnameInferredScottishIsDropped() {
		Map<String, Object> d = llm("White", null, "Scottish", null);
		PictureBookUtil.groundRaceAndEthnicity(d, MACINTYRE_PASSAGE, "Lara MacIntyre");
		assertEquals("no 'white' anywhere in her passages", "Unknown", d.get("race"));
		assertEquals("'Scottish'/'Scot'/'Scotland' appear nowhere in the text", "", d.get("ethnicity"));
		// Downstream contract: a dropped label yields no race and no ethnicity on the record.
		assertTrue(PictureBookUtil.resolveTextRace(d, "Lara MacIntyre").isEmpty());
		assertEquals(null, PictureBookUtil.mapEthnicityOverride((String) d.get("ethnicity")));
	}

	@Test
	public void skinColourIsNotAnEthnicity() {
		Map<String, Object> d = llm("Unknown", null, "Other Asian", null);
		PictureBookUtil.groundRaceAndEthnicity(d, SHOPKEEPER_PASSAGE, "Shopkeeper");
		assertEquals("", d.get("ethnicity"));
		assertEquals("Unknown", d.get("race"));
	}

	@Test
	public void objectColourDoesNotGroundAColourWordRace() {
		// "dirty white shirt" is the only 'white' in the passage.
		Map<String, Object> d = llm("White", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, SHOPKEEPER_PASSAGE, "Shopkeeper");
		assertEquals("Unknown", d.get("race"));
		// Even when the model quotes it as evidence.
		d = llm("White", "a dirty white shirt", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, SHOPKEEPER_PASSAGE, "Shopkeeper");
		assertEquals("Unknown", d.get("race"));
		// "black gloves" does not make Vlad Black either.
		d = llm("Black", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, VLAD_PASSAGE, "Vlad");
		assertEquals("Unknown", d.get("race"));
	}

	@Test
	public void colourWordOfAPersonIsKept() {
		// "Skin black as night" describes Visella herself.
		Map<String, Object> d = llm("Black", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Black", d.get("race"));
		// "near-white skin" describes Vlad himself.
		d = llm("White", "near-white skin", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, VLAD_PASSAGE, "Vlad");
		assertEquals("White", d.get("race"));
	}

	@Test
	public void vampireWithoutTheWordIsDropped() {
		Map<String, Object> d = llm("Vampire", null, "", null);
		PictureBookUtil.groundRaceAndEthnicity(d, VLAD_PASSAGE, "Vlad");
		assertEquals("Unknown", d.get("race"));
		d = llm("Vampire", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "The vampires of the lower city kept to their crypts.", "Vlad");
		assertEquals("Vampire", d.get("race"));
	}

	@Test
	public void fabricatedEvidenceIsRejectedEvenWhenTheWordOccurs() {
		// The word is in the passages, but the model's quote is not — a quote that isn't a quote
		// is a fabrication signal and loses.
		Map<String, Object> d = llm("Fairy", "Visella was a fairy of the northern court", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Unknown", d.get("race"));
		// A genuine quote that does not name the race is not evidence of it.
		d = llm("Fairy", "wings folded tight against her back", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Unknown", d.get("race"));
	}

	@Test
	public void evidenceQuoteMatchingIsForgivingOfQuotesAndWhitespace() {
		Map<String, Object> d = llm("Fairy", "“The dark   fairy,\nVisella”", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Fairy", d.get("race"));
		d = llm("White", "'...near–white skin...'", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, VLAD_PASSAGE.replace("near-white", "near–white"), "Vlad");
		assertEquals("White", d.get("race"));
	}

	@Test
	public void evidenceKeysNeverReachCharData() {
		Map<String, Object> d = llm("Fairy", "The dark fairy", "Irish", "");
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_EVIDENCE));
		assertFalse(d.containsKey(PictureBookUtil.KEY_ETHNICITY_EVIDENCE));
		assertEquals("", d.get("ethnicity"));
		assertEquals("worn leather", d.get("clothing_style"));
	}

	@Test
	public void statedEthnicityIsKeptAndDemonymFormsCount() {
		Map<String, Object> d = llm(null, null, "Irish", null);
		PictureBookUtil.groundRaceAndEthnicity(d, "An Irish tinker mended pots by the well.", "t");
		assertEquals("Irish", d.get("ethnicity"));
		d = llm(null, null, "Scottish", null);
		PictureBookUtil.groundRaceAndEthnicity(d, "He had come south from Scotland the winter before.", "t");
		assertEquals("Scottish", d.get("ethnicity"));
		d = llm(null, null, "Scottish", null);
		PictureBookUtil.groundRaceAndEthnicity(d, "The mascot was a scotty dog.", "t");
		assertEquals("'scotty' is not a whole-word match for scot/scots", "", d.get("ethnicity"));
	}

	@Test
	public void unknownUnmappableAndPlaceholderLabelsPassThroughUntouched() {
		Map<String, Object> d = llm("Unknown", null, "", null);
		PictureBookUtil.groundRaceAndEthnicity(d, MACINTYRE_PASSAGE, "t");
		assertEquals("Unknown", d.get("race"));
		assertEquals("", d.get("ethnicity"));
		d = llm("null", null, "n/a", null);
		PictureBookUtil.groundRaceAndEthnicity(d, MACINTYRE_PASSAGE, "t");
		assertEquals("null", d.get("race"));
		assertEquals("n/a", d.get("ethnicity"));
		// Free text that maps to no enum is resolveTextRace's problem (it drops it); the gate
		// leaves it alone rather than inventing a verdict.
		d = llm("olive-skinned Mediterranean", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, MACINTYRE_PASSAGE, "t");
		assertEquals("olive-skinned Mediterranean", d.get("race"));
		assertTrue(PictureBookUtil.resolveTextRace(d, "t").isEmpty());
		// Null-safe.
		PictureBookUtil.groundRaceAndEthnicity(null, MACINTYRE_PASSAGE, "t");
		d = llm("Fairy", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, null, "t");
		assertEquals("no passages means nothing is stated", "Unknown", d.get("race"));
	}

	// ── Custom race (RaceEnumType.O) + optional raceLabel ────────────────────────────────────
	//
	// O is a SINK: the passages literally name a race the enum does not list ("Mer-folk"), the
	// grounding gate promotes it to race="Custom" + race_label=<the text's word>, and the record
	// carries the word in charPerson.raceLabel. The word "Custom" itself is never a choice offered
	// to the LLM, never a mapping target, and never rendered into a narration or prompt.

	private static final String MERFOLK_PASSAGE = "The Mer-folk of the bay surfaced at dusk, their scaled "
		+ "shoulders glinting. Nerine, eldest of the Mer-folk, watched the lamps come on along the quay.";

	/// Pinned as a LITERAL, not derived from values(): this exact string is embedded in the 106
	/// recorded LLM-emulator fixtures, so any drift (including a "fix" of the Exraterrestrial typo)
	/// silently invalidates every one of them. Custom must NOT be offered.
	@Test
	public void raceOptionsCsvOmitsCustomAndIsPinned() {
		assertEquals("American Indian/Alaska Native, Asian, Black, Native Hawaiian or other Pacific Islander, "
			+ "White, Lunatic, Robot, Monster, Succubus, Unknown, Vampire, Exraterrestrial, Elf, Dwarf, Fairy",
			PictureBookUtil.raceOptionsCsv());
		assertFalse(PictureBookUtil.raceOptionsCsv().toLowerCase().contains("custom"));
	}

	@Test
	public void customIsNeverAMappingTarget() {
		assertEquals(null, PictureBookUtil.mapRaceOverride("Custom"));
		assertEquals(null, PictureBookUtil.mapRaceOverride("custom"));
		assertEquals(null, PictureBookUtil.mapRaceOverride("O"));
		assertEquals(null, PictureBookUtil.mapRaceOverride("o"));
		assertEquals(null, PictureBookUtil.mapRaceOverride("  Custom  "));
		// Every other constant still maps by label and by name.
		assertEquals("Z", PictureBookUtil.mapRaceOverride("Fairy"));
		assertEquals("Z", PictureBookUtil.mapRaceOverride("z"));
		assertTrue(RaceEnumType.isCustom("O"));
		assertTrue(RaceEnumType.isCustom(" o "));
		assertFalse(RaceEnumType.isCustom("Custom"));
		assertFalse(RaceEnumType.isCustom(null));
	}

	@Test
	public void customWithLabelResolvesToOAndLabel() {
		Map<String, Object> d = charData("Custom");
		d.put(PictureBookUtil.KEY_RACE_LABEL, "Mer-folk");
		assertEquals(Arrays.asList(RaceEnumType.O.name()), PictureBookUtil.resolveTextRace(d, "Nerine"));
		assertEquals("Mer-folk", PictureBookUtil.resolveTextRaceLabel(d));
		// The constant name is accepted as the Custom marker too (a re-run over persisted data).
		d = charData("O");
		d.put(PictureBookUtil.KEY_RACE_LABEL, "  Mer-folk  ");
		assertEquals(Arrays.asList(RaceEnumType.O.name()), PictureBookUtil.resolveTextRace(d, "Nerine"));
		assertEquals("trimmed", "Mer-folk", PictureBookUtil.resolveTextRaceLabel(d));
	}

	@Test
	public void customWithoutAMeaningfulLabelContributesNothing() {
		Map<String, Object> d = charData("Custom");
		assertTrue("Custom with no label is 'not stated'", PictureBookUtil.resolveTextRace(d, "t").isEmpty());
		assertEquals(null, PictureBookUtil.resolveTextRaceLabel(d));
		d.put(PictureBookUtil.KEY_RACE_LABEL, "null");
		assertTrue("LLM literal placeholder label", PictureBookUtil.resolveTextRace(d, "t").isEmpty());
		assertEquals(null, PictureBookUtil.resolveTextRaceLabel(d));
		d.put(PictureBookUtil.KEY_RACE_LABEL, "   ");
		assertTrue(PictureBookUtil.resolveTextRace(d, "t").isEmpty());
		d.put(PictureBookUtil.KEY_RACE_LABEL, Arrays.asList("Mer-folk"));
		assertTrue("label must be a string", PictureBookUtil.resolveTextRace(d, "t").isEmpty());
		assertEquals(null, PictureBookUtil.resolveTextRaceLabel(null));
	}

	@Test
	public void groundedOffListRaceBecomesCustomWithLabel() {
		Map<String, Object> d = llm("Mer-folk", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, MERFOLK_PASSAGE, "Nerine");
		assertEquals("Custom", d.get("race"));
		assertEquals("Mer-folk", d.get(PictureBookUtil.KEY_RACE_LABEL));
		// Downstream contract: O on the record, the text's own word as the label.
		assertEquals(Arrays.asList(RaceEnumType.O.name()), PictureBookUtil.resolveTextRace(d, "Nerine"));
		assertEquals("Mer-folk", PictureBookUtil.resolveTextRaceLabel(d));
		// With a genuine evidence quote that contains the word.
		d = llm("Mer-folk", "eldest of the Mer-folk", null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, MERFOLK_PASSAGE, "Nerine");
		assertEquals("Custom", d.get("race"));
		assertEquals("Mer-folk", d.get(PictureBookUtil.KEY_RACE_LABEL));
	}

	@Test
	public void offListRaceNeedsEveryWordOfTheLabelInThePassages() {
		// Only "olive-skinned" is in the passage; "Mediterranean" is not — left untouched, no label,
		// and resolveTextRace drops it (same contract the pass-through test above pins).
		Map<String, Object> d = llm("olive-skinned Mediterranean", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "Her olive-skinned hands worked the rope.", "t");
		assertEquals("olive-skinned Mediterranean", d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(d, "t").isEmpty());
		assertEquals(null, PictureBookUtil.resolveTextRaceLabel(d));
		// A shared word does not carry the rest: "folk" alone does not ground "Mer-folk".
		d = llm("Mer-folk", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "The folk of the bay kept to their boats.", "Nerine");
		assertEquals("Mer-folk", d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(d, "Nerine").isEmpty());
	}

	@Test
	public void groundedEthnicityWordAsRaceIsNeverPromotedToCustom() {
		// "Scottish" maps to an EthnicityEnumType; even when the passage says Scotland it is not a race.
		Map<String, Object> d = llm("Scottish", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "He had come south from Scotland the winter before.", "Caleb");
		assertEquals("left for resolveTextRace to drop", "Scottish", d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(d, "Caleb").isEmpty());
	}

	@Test
	public void llmSuppliedRaceLabelIsStripped() {
		// On-list race with a label the model invented: only the gate may attach race_label.
		Map<String, Object> d = llm("Fairy", null, null, null);
		d.put(PictureBookUtil.KEY_RACE_LABEL, "Sidhe");
		PictureBookUtil.groundRaceAndEthnicity(d, FAIRY_PASSAGE, "Visella");
		assertEquals("Fairy", d.get("race"));
		assertFalse("the LLM's own race_label must not survive the gate", d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertEquals(Arrays.asList(RaceEnumType.Z.name()), PictureBookUtil.resolveTextRace(d, "Visella"));
		// Ungrounded off-list race with an LLM label: label stripped, race untouched, nothing resolves.
		d = llm("Mer-folk", null, null, null);
		d.put(PictureBookUtil.KEY_RACE_LABEL, "Mer-folk");
		PictureBookUtil.groundRaceAndEthnicity(d, MACINTYRE_PASSAGE, "t");
		assertEquals("Mer-folk", d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(d, "t").isEmpty());
	}

	@Test
	public void llmAnsweringCustomIsNotStated() {
		Map<String, Object> d = llm("Custom", null, null, null);
		d.put(PictureBookUtil.KEY_RACE_LABEL, "Mer-folk");
		PictureBookUtil.groundRaceAndEthnicity(d, MERFOLK_PASSAGE, "Nerine");
		assertEquals("'Custom' names nothing the text could state", "Unknown", d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(d, "Nerine").isEmpty());
		d = llm("O", null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, MERFOLK_PASSAGE, "Nerine");
		assertEquals("Unknown", d.get("race"));
	}

	/// A label longer than identity.person raceLabel.maxLength (64) is never promoted: the gate
	/// leaves the raw string untouched with no race_label, resolveTextRace drops it, and creation
	/// proceeds with no race — the over-long label is DROPPED, not truncated and not an abort.
	@Test
	public void overLongOffListLabelIsDroppedNotPromoted() {
		String longLabel = "Mer-folk of the deep bay who surface at dusk with scaled shoulders and glinting eyes";
		assertTrue(longLabel.length() > 64);
		// Every word of the label IS in the passage, so only the length rule can reject it.
		String passage = "Nerine was one of the " + longLabel + ".";
		Map<String, Object> d = llm(longLabel, null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, passage, "Nerine");
		assertEquals("left untouched for resolveTextRace to drop", longLabel, d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(d, "Nerine").isEmpty());
		assertEquals(null, PictureBookUtil.resolveTextRaceLabel(d));
		// Exactly 64 characters is still allowed and promoted.
		String label64 = "Mer-folk " + "a".repeat(64 - "Mer-folk ".length());
		assertEquals(64, label64.length());
		d = llm(label64, null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "The " + label64 + " sang.", "Nerine");
		assertEquals("Custom", d.get("race"));
		assertEquals(label64, d.get(PictureBookUtil.KEY_RACE_LABEL));
		// 65 is not.
		String label65 = label64 + "a";
		d = llm(label65, null, null, null);
		PictureBookUtil.groundRaceAndEthnicity(d, "The " + label65 + " sang.", "Nerine");
		assertEquals(label65, d.get("race"));
		assertFalse(d.containsKey(PictureBookUtil.KEY_RACE_LABEL));
	}

	@Test
	public void raceDescriptionSubstitutesTheLabelForCustomOnly() {
		assertEquals("Fae", NarrativeUtil.getRaceDescription(Arrays.asList("O"), "Fae"));
		assertEquals("Fairy and Fae", NarrativeUtil.getRaceDescription(Arrays.asList("Z", "O"), "Fae"));
		assertEquals("no dangling conjunction", "White", NarrativeUtil.getRaceDescription(Arrays.asList("E", "O"), null));
		assertEquals("", NarrativeUtil.getRaceDescription(Arrays.asList("O"), null));
		assertEquals("LLM literal placeholder is not a label", "", NarrativeUtil.getRaceDescription(Arrays.asList("O"), "null"));
		assertEquals("", NarrativeUtil.getRaceDescription(Arrays.asList("O"), "   "));
		assertEquals("trimmed", "Fae", NarrativeUtil.getRaceDescription(Arrays.asList("O"), "  Fae  "));
		assertEquals("single-arg form drops O", "White", NarrativeUtil.getRaceDescription(Arrays.asList("E", "O")));
		assertEquals("", NarrativeUtil.getRaceDescription(Arrays.asList("O")));
		// The label never applies to a non-custom element.
		assertEquals("White", NarrativeUtil.getRaceDescription(Arrays.asList("E"), "Fae"));
		assertEquals(Arrays.asList("Fairy", "Fae"), NarrativeUtil.describeRaces(Arrays.asList("Z", "O"), "Fae"));
		assertEquals(Collections.emptyList(), NarrativeUtil.describeRaces(null, "Fae"));
		// The word "Custom" is never emitted.
		for (String s : Arrays.asList(NarrativeUtil.getRaceDescription(Arrays.asList("O"), null),
				NarrativeUtil.getRaceDescription(Arrays.asList("Z", "O"), "Fae"))) {
			assertFalse(s, s.toLowerCase().contains("custom"));
		}
	}

	// ── cast (caller-supplied) races: user-authored, honored without grounding ──────────────────

	@Test
	public void offListCastRaceBecomesCustomWithLabelWithoutGrounding() {
		Map<String, Object> cd = charData("Mer-folk");
		PictureBookUtil.normalizeCastRace(cd, "Nerine");
		assertEquals("Custom", cd.get("race"));
		assertEquals("Mer-folk", cd.get(PictureBookUtil.KEY_RACE_LABEL));
		assertEquals(Arrays.asList("O"), PictureBookUtil.resolveTextRace(cd, "Nerine"));
		assertEquals("Mer-folk", PictureBookUtil.resolveTextRaceLabel(cd));

		// Multi-word and untrimmed input: the label is the caller's own words, trimmed.
		Map<String, Object> mw = charData("  Deep Sea Mer-folk ");
		PictureBookUtil.normalizeCastRace(mw, "Nerine");
		assertEquals("Custom", mw.get("race"));
		assertEquals("Deep Sea Mer-folk", mw.get(PictureBookUtil.KEY_RACE_LABEL));
	}

	@Test
	public void onListAndExplicitCustomCastRacesPassThroughUnchanged() {
		Map<String, Object> fairy = charData("Fairy");
		PictureBookUtil.normalizeCastRace(fairy, "Tam");
		assertEquals("Fairy", fairy.get("race"));
		assertFalse(fairy.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertEquals(Arrays.asList("Z"), PictureBookUtil.resolveTextRace(fairy, "Tam"));

		Map<String, Object> explicit = charData("Custom");
		explicit.put(PictureBookUtil.KEY_RACE_LABEL, "Selkie");
		PictureBookUtil.normalizeCastRace(explicit, "Roan");
		assertEquals("Custom", explicit.get("race"));
		assertEquals("Selkie", explicit.get(PictureBookUtil.KEY_RACE_LABEL));

		// Constant-name shape is accepted too.
		Map<String, Object> constant = charData("O");
		constant.put(PictureBookUtil.KEY_RACE_LABEL, "Selkie");
		PictureBookUtil.normalizeCastRace(constant, "Roan");
		assertEquals("O", constant.get("race"));
		assertEquals("Selkie", constant.get(PictureBookUtil.KEY_RACE_LABEL));
	}

	@Test
	public void castRaceExclusionsMatchTheLlmPath() {
		// An ethnicity word is not a race and is never promoted (Stephen: drop it, never promote).
		Map<String, Object> eth = charData("Scottish");
		PictureBookUtil.normalizeCastRace(eth, "Moira");
		assertEquals("left for resolveTextRace to drop", "Scottish", eth.get("race"));
		assertFalse(eth.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(eth, "Moira").isEmpty());

		// Over the column width: dropped, never truncated into a label.
		String longRace = "A".repeat(PictureBookUtil.raceLabelMaxLength() + 1);
		Map<String, Object> over = charData(longRace);
		PictureBookUtil.normalizeCastRace(over, "X");
		assertEquals(longRace, over.get("race"));
		assertFalse(over.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertTrue(PictureBookUtil.resolveTextRace(over, "X").isEmpty());
		// Exactly the width is fine.
		String maxRace = "B".repeat(PictureBookUtil.raceLabelMaxLength());
		Map<String, Object> max = charData(maxRace);
		PictureBookUtil.normalizeCastRace(max, "X");
		assertEquals("Custom", max.get("race"));
		assertEquals(maxRace, max.get(PictureBookUtil.KEY_RACE_LABEL));

		// Placeholders and blanks are not races and contribute nothing.
		for (String s : Arrays.asList("null", "n/a", "unknown", "", "   ")) {
			Map<String, Object> ph = charData(s);
			PictureBookUtil.normalizeCastRace(ph, "X");
			assertEquals(s, ph.get("race"));
			assertFalse(s, ph.containsKey(PictureBookUtil.KEY_RACE_LABEL));
			assertTrue(s, PictureBookUtil.resolveTextRace(ph, "X").isEmpty());
		}
		// Null-safe.
		PictureBookUtil.normalizeCastRace(null, "X");
		PictureBookUtil.enforceRaceLabelInvariant(null);
	}

	@Test
	public void explicitCustomWithOverLongLabelYieldsNoRaceAndNoLabel() {
		// normalizeCastRace passes an explicit Custom+race_label pair through unchecked, so the width
		// guard has to hold at resolveTextRaceLabel — otherwise the label reaches identity.person.raceLabel
		// (maxLength 64), RecordValidator rejects it, and the whole charPerson create fails.
		String longLabel = "L".repeat(PictureBookUtil.raceLabelMaxLength() + 1);
		for (String customShape : Arrays.asList("Custom", "O")) {
			Map<String, Object> explicit = charData(customShape);
			explicit.put(PictureBookUtil.KEY_RACE_LABEL, longLabel);
			PictureBookUtil.normalizeCastRace(explicit, "Roan");
			assertEquals(customShape, longLabel, explicit.get(PictureBookUtil.KEY_RACE_LABEL));
			assertEquals(customShape, null, PictureBookUtil.resolveTextRaceLabel(explicit));
			assertTrue(customShape + ": Custom without a usable label leaves race unset",
				PictureBookUtil.resolveTextRace(explicit, "Roan").isEmpty());
		}
		// Exactly the width still resolves.
		String maxLabel = "M".repeat(PictureBookUtil.raceLabelMaxLength());
		Map<String, Object> max = charData("Custom");
		max.put(PictureBookUtil.KEY_RACE_LABEL, maxLabel);
		PictureBookUtil.normalizeCastRace(max, "Roan");
		assertEquals(maxLabel, PictureBookUtil.resolveTextRaceLabel(max));
		assertEquals(Arrays.asList("O"), PictureBookUtil.resolveTextRace(max, "Roan"));
		// Whitespace padding does not count toward the width.
		Map<String, Object> padded = charData("Custom");
		padded.put(PictureBookUtil.KEY_RACE_LABEL, "  " + maxLabel + "  ");
		assertEquals(maxLabel, PictureBookUtil.resolveTextRaceLabel(padded));
	}

	@Test
	public void raceLabelWidthComesFromThePersonSchema() {
		// The guard must not drift from the column: it is read from identity.person raceLabel.maxLength.
		int schemaMax = RecordFactory.getSchema(ModelNames.MODEL_PERSON)
			.getFieldSchema(OlioFieldNames.FIELD_RACE_LABEL).getMaxLength();
		assertTrue("personModel.json must declare a positive maxLength on raceLabel", schemaMax > 0);
		assertEquals(schemaMax, PictureBookUtil.raceLabelMaxLength());
	}

	@Test
	public void raceLabelControlCharactersAreStrippedAtWriteTime() {
		// The label is rendered verbatim into narration and SD prompts, so a newline/tab/escape smuggled
		// in through cast data or the LLM must be gone from the value that gets STORED, not only the read.
		Map<String, Object> explicit = charData("Custom");
		explicit.put(PictureBookUtil.KEY_RACE_LABEL, "Mer-\nfolk\t\u001b[31m");
		PictureBookUtil.normalizeCastRace(explicit, "Nerine");
		assertEquals("Mer- folk [31m", PictureBookUtil.resolveTextRaceLabel(explicit));

		Map<String, Object> raw = charData("Sea\r\nElf");
		PictureBookUtil.normalizeCastRace(raw, "Nerine");
		assertEquals("Custom", raw.get("race"));
		assertEquals("Sea Elf", raw.get(PictureBookUtil.KEY_RACE_LABEL));
		assertEquals("Sea Elf", PictureBookUtil.resolveTextRaceLabel(raw));

		// Nothing but control characters is not a label at all.
		Map<String, Object> junk = charData("Custom");
		junk.put(PictureBookUtil.KEY_RACE_LABEL, "\n\t\u0007");
		assertEquals(null, PictureBookUtil.resolveTextRaceLabel(junk));
		assertTrue(PictureBookUtil.resolveTextRace(junk, "Nerine").isEmpty());
	}

	@Test
	public void raceLabelNeverSitsBesideANonCustomRace() {
		// A cast label without a Custom race is a stray and is removed up front.
		Map<String, Object> stray = charData("Fairy");
		stray.put(PictureBookUtil.KEY_RACE_LABEL, "Selkie");
		PictureBookUtil.normalizeCastRace(stray, "Tam");
		assertEquals("Fairy", stray.get("race"));
		assertFalse(stray.containsKey(PictureBookUtil.KEY_RACE_LABEL));

		Map<String, Object> noRace = new HashMap<>();
		noRace.put(PictureBookUtil.KEY_RACE_LABEL, "Selkie");
		PictureBookUtil.normalizeCastRace(noRace, "Tam");
		assertFalse(noRace.containsKey(PictureBookUtil.KEY_RACE_LABEL));

		// The orphan case: cast says Fairy (on-list, wins the fill-only merge), the LLM grounded an
		// off-list race and its label alone would have merged in beside the cast's race.
		Map<String, Object> cast = charData("Fairy");
		PictureBookUtil.normalizeCastRace(cast, "Tam");
		Map<String, Object> llm = new LinkedHashMap<>();
		llm.put("race", "Custom");
		llm.put(PictureBookUtil.KEY_RACE_LABEL, "Selkie");
		llm.put("age", "30");
		for (Map.Entry<String, Object> e : llm.entrySet()) {
			if (!cast.containsKey(e.getKey()) || cast.get(e.getKey()) == null
					|| ((cast.get(e.getKey()) instanceof String) && ((String) cast.get(e.getKey())).isEmpty())) {
				cast.put(e.getKey(), e.getValue());
			}
		}
		assertEquals("pre-invariant: the merge did orphan a label", "Selkie", cast.get(PictureBookUtil.KEY_RACE_LABEL));
		PictureBookUtil.enforceRaceLabelInvariant(cast);
		assertEquals("Fairy", cast.get("race"));
		assertFalse("the orphaned label is removed", cast.containsKey(PictureBookUtil.KEY_RACE_LABEL));
		assertEquals("other merged keys untouched", "30", cast.get("age"));

		// And when the pair arrives together it is kept whole.
		Map<String, Object> pair = new HashMap<>();
		pair.put("race", "Custom");
		pair.put(PictureBookUtil.KEY_RACE_LABEL, "Selkie");
		PictureBookUtil.enforceRaceLabelInvariant(pair);
		assertEquals("Selkie", pair.get(PictureBookUtil.KEY_RACE_LABEL));
	}

	@Test
	public void raceLabelControlCharactersAreStrippedBeforeRendering() throws Exception {
		BaseRecord person = charPerson(Arrays.asList(RaceEnumType.O.name()), null);
		person.set(OlioFieldNames.FIELD_RACE_LABEL, "Fae\nfolk\t(of the\u0007hollow)");
		String label = NarrativeUtil.getRaceLabel(person);
		assertEquals("Fae folk (of the hollow)", label);
		assertEquals("Fae folk (of the hollow)", NarrativeUtil.getRaceDescription(Arrays.asList("O"), "Fae\r\nfolk (of the\u0007hollow)"));
		String d = NarrativeUtil.describePhysical(profileFor(person));
		assertFalse(d, d.contains("\n") || d.contains("\t") || d.contains("\u0007"));
		assertTrue(d, d.contains("Fae folk (of the hollow) woman"));
		// Control characters alone are not a label.
		BaseRecord junk = charPerson(Arrays.asList(RaceEnumType.O.name()), null);
		junk.set(OlioFieldNames.FIELD_RACE_LABEL, "\n\t\u0001");
		assertEquals(null, NarrativeUtil.getRaceLabel(junk));
		assertEquals("", NarrativeUtil.getRaceDescription(Arrays.asList("O"), "\n\t"));
	}

	@Test
	public void describePhysicalRendersTheLabelNeverTheWordCustom() throws Exception {
		BaseRecord person = charPerson(Arrays.asList(RaceEnumType.O.name()), null);
		person.set(OlioFieldNames.FIELD_RACE_LABEL, "Fae");
		assertEquals("Fae", NarrativeUtil.getRaceLabel(person));
		assertEquals("Fae", profileFor(person).getRaceLabel());
		String d = NarrativeUtil.describePhysical(profileFor(person));
		logger.info("describePhysical (race O, label Fae): " + d);
		assertTrue(d, d.contains("28 year old Fae woman"));
		assertFalse(d, d.toLowerCase().contains("custom"));
		assertFalse(d.contains("  "));

		// O with no label: no race word at all (RACE_WORDS now includes "custom").
		BaseRecord unlabeled = charPerson(Arrays.asList(RaceEnumType.O.name()), null);
		String u = NarrativeUtil.describePhysical(profileFor(unlabeled));
		logger.info("describePhysical (race O, no label): " + u);
		assertNoRaceWord(u);
		assertFalse(u.contains("  "));
		assertTrue(u, u.contains("28 year old woman with green eyes and auburn hair."));

		// O with a placeholder label behaves like no label.
		BaseRecord placeholder = charPerson(Arrays.asList(RaceEnumType.O.name()), null);
		placeholder.set(OlioFieldNames.FIELD_RACE_LABEL, "unknown");
		assertEquals(null, NarrativeUtil.getRaceLabel(placeholder));
		assertNoRaceWord(NarrativeUtil.describePhysical(profileFor(placeholder)));

		// Mixed list: the enum label and the custom label both render.
		BaseRecord mixed = charPerson(Arrays.asList(RaceEnumType.Z.name(), RaceEnumType.O.name()), null);
		mixed.set(OlioFieldNames.FIELD_RACE_LABEL, "Fae");
		String mx = NarrativeUtil.describePhysical(profileFor(mixed));
		assertTrue(mx, mx.contains("28 year old Fairy and Fae woman"));
	}

	// ── prompt resources: the classpath prompt and its DB-seeded template twin must agree ────────

	/**
	 * resolvePrompt prefers a DB template (seeded from templates/promptTemplate.<name>.json) and
	 * falls back to prompts/<name>.json only when none resolves, so the two must carry the same
	 * text or a provisioned deployment and a fresh one send different instructions. Both must ask
	 * for the evidence quotes the grounding gate checks and must not tell the model to "be specific"
	 * about race or ethnicity, which is exactly the instruction that produced the invented labels.
	 */
	@Test
	public void reduceCharacterPromptAndTemplateAreInSyncAndDoNotInviteInference() {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_PROMPT_TEMPLATE);
		RecordFactory.model(OlioModelNames.MODEL_PROMPT_SECTION);
		String name = "pictureBook.reduce-character";
		String promptSystem = PromptResourceUtil.getString(name, "system");
		String promptUser = PromptResourceUtil.getString(name, "user");
		assertNotNull(promptSystem);
		assertNotNull(promptUser);

		// Same resource + deserializer as ChatUtil.loadPromptTemplateTemplate; ChatUtil itself cannot be
		// class-loaded here because its static initializer builds Queries against the (absent) IOSystem.
		String templateJson = ResourceUtil.getInstance().getResource("olio/llm/templates/promptTemplate." + name + ".json");
		assertNotNull("templates/promptTemplate." + name + ".json must exist", templateJson);
		BaseRecord template = JSONUtil.importObject(templateJson, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("templates/promptTemplate." + name + ".json must deserialize", template);
		assertEquals(name, template.get(FieldNames.FIELD_NAME));

		String tplSystem = null;
		String tplUser = null;
		List<BaseRecord> sections = template.get("sections");
		for (BaseRecord s : sections) {
			List<String> lines = s.get("lines");
			String joined = String.join("\n", lines);
			if ("system".equals(s.get("role"))) tplSystem = joined;
			if ("user".equals(s.get("role"))) tplUser = joined;
		}
		assertEquals("system text drifted between prompt and template", promptSystem, tplSystem);
		assertEquals("user text drifted between prompt and template", promptUser, tplUser);

		for (String text : Arrays.asList(promptSystem, promptUser)) {
			String lower = text.toLowerCase();
			assertFalse(lower.contains("be specific about race"));
			assertFalse(lower.contains("be specific about ethnicity"));
		}
		assertTrue(promptSystem.contains("NEVER infer race or ethnicity"));
		assertTrue(promptUser.contains("\"" + PictureBookUtil.KEY_RACE_EVIDENCE + "\""));
		assertTrue(promptUser.contains("\"" + PictureBookUtil.KEY_ETHNICITY_EVIDENCE + "\""));
		assertTrue(promptUser.contains("{raceOptions}"));
		assertTrue(promptUser.contains("{ethnicityOptions}"));
		assertTrue(promptUser.contains("{passages}"));

		String composedSystem = PromptTemplateComposer.composeSystem(template, null, null);
		String composedUser = PromptTemplateComposer.composeUser(template, null, null);
		assertEquals(promptSystem, composedSystem);
		assertTrue("composed user prompt must still end with the passages token", composedUser.endsWith("{passages}"));
		assertTrue(composedUser.contains(PictureBookUtil.KEY_RACE_EVIDENCE));
	}

	@Test
	public void repeatedSceneTitlesGetDistinctNoteNames() {
		java.util.Set<String> used = new java.util.HashSet<>();
		assertEquals("Dirk and Simon Exchange Gloves", PictureBookUtil.uniqueSceneNoteName("Dirk and Simon Exchange Gloves", 0, used));
		assertEquals("Dirk and Simon Exchange Gloves (2)", PictureBookUtil.uniqueSceneNoteName("Dirk and Simon Exchange Gloves", 1, used));
		assertEquals("dirk and simon exchange gloves (3)", PictureBookUtil.uniqueSceneNoteName("dirk and simon exchange gloves", 2, used));
		assertEquals("The Market", PictureBookUtil.uniqueSceneNoteName("  The Market ", 3, used));
		assertEquals("Scene 4", PictureBookUtil.uniqueSceneNoteName(null, 4, used));
		assertEquals("Scene 5", PictureBookUtil.uniqueSceneNoteName("   ", 5, used));
		assertEquals("Scene 5 (2)", PictureBookUtil.uniqueSceneNoteName("Scene 5", 6, used));
		assertEquals(7, used.size());
	}
}
