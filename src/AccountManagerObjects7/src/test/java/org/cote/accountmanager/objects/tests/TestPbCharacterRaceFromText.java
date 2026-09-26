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
		"elf", "dwarf", "fairy", "unknown");

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
