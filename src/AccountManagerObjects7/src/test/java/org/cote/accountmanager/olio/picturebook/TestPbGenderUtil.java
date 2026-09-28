package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/// Deterministic gender resolution for extracted PictureBook characters ({@link PbGenderUtil}).
///
/// The chain replaces the random-baseline fallback in {@code PictureBookUtil.createCharPerson}:
/// llm → names word list → pronoun majority in the character's own sentences → name-hash parity.
/// Everything except the names-list step is pure and tested in memory here; the names-list step
/// is tested against the real test database with a throw-away {@code data.word} group seeded by a
/// non-admin user (no Olio universe load required — the helper takes the group id directly).
public class TestPbGenderUtil extends BaseTest {

	/// Deliberately two-hander prose: Darby's sentences vote female (1 "she"), Marsh's vote male. A
	/// counter that looked at the WHOLE passage would see 5 male vs 1 female and mis-gender Darby;
	/// the per-character window is what makes the female answer come out.
	private static final String PASSAGE =
		"Darby Wren carried the lantern down the cellar steps. She set it on the barrel and pulled the loose board aside; underneath lay a tin box.\n"
		+ "Old Marsh waited at the top with one hand on the rail. He called down that the rain had started again, and his voice cracked. He coughed. He spat.\n"
		+ "Nobody answered him.";

	private static final Function<String, String> NO_NAME_HIT = fn -> "";

	// ------------------------------------------------------------------------------------------------
	// normalize / stop list
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testNormalizeClampsToCanonicalOrEmpty() {
		assertEquals("male", PbGenderUtil.normalize("Male"));
		assertEquals("male", PbGenderUtil.normalize(" m "));
		assertEquals("female", PbGenderUtil.normalize("FEMALE"));
		assertEquals("female", PbGenderUtil.normalize("f"));
		assertEquals("", PbGenderUtil.normalize(null));
		assertEquals("", PbGenderUtil.normalize(""));
		assertEquals("", PbGenderUtil.normalize("null"));
		assertEquals("", PbGenderUtil.normalize("unknown"));
		assertEquals("", PbGenderUtil.normalize("n/a"));
		assertEquals("", PbGenderUtil.normalize("non-binary"));
	}

	@Test
	public void testNameLookupCandidateRejectsArticlesTitlesAndMultiWord() {
		assertTrue(PbGenderUtil.isNameLookupCandidate("Darby"));
		assertTrue(PbGenderUtil.isNameLookupCandidate("  marsh "));
		assertTrue(PbGenderUtil.isNameLookupCandidate("Jo"));
		/// Stop words, with and without a trailing period, any case.
		for (String stop : new String[] { "The", "the", "A", "An", "Mr", "Mr.", "MRS", "Ms.", "Dr", "Dr.", "Sir", "Lady",
				"Captain", "Old", "Young", "Father", "Sister", "Saint", "St.", "Nurse", "Judge" }) {
			assertFalse("'" + stop + "' must not be looked up as a given name", PbGenderUtil.isNameLookupCandidate(stop));
		}
		/// Empty / too short / contains whitespace / not starting with a letter.
		assertFalse(PbGenderUtil.isNameLookupCandidate(null));
		assertFalse(PbGenderUtil.isNameLookupCandidate(""));
		assertFalse(PbGenderUtil.isNameLookupCandidate("X"));
		assertFalse(PbGenderUtil.isNameLookupCandidate("Darby Wren"));
		assertFalse(PbGenderUtil.isNameLookupCandidate("7th"));
		assertFalse(PbGenderUtil.isNameLookupCandidate("'Darby"));
	}

	@Test
	public void testResolveSkipsNameLookupForStopWordFirstName() {
		List<String> lookedUp = new ArrayList<>();
		Function<String, String> spy = fn -> { lookedUp.add(fn); return "male"; };
		/// "The Stranger" → firstName "The": the lookup must not be consulted at all, and with no
		/// pronouns in range the chain must fall to the hash.
		PbGenderUtil.Resolution r = PbGenderUtil.resolve("", "The Stranger", "The", "A door closed somewhere.", spy);
		assertTrue("stop-word first name must never reach the names lookup: " + lookedUp, lookedUp.isEmpty());
		assertEquals(PbGenderUtil.Source.HASH, r.source());
		assertEquals(PbGenderUtil.hashGender("The Stranger"), r.gender());
	}

	// ------------------------------------------------------------------------------------------------
	// (c) pronoun counter
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testPronounsFemaleMajorityInOwnSentences() {
		assertEquals("female", PbGenderUtil.genderFromPronouns("Darby Wren", "Darby", PASSAGE));
	}

	@Test
	public void testPronounsMaleMajorityInOwnSentences() {
		assertEquals("male", PbGenderUtil.genderFromPronouns("Old Marsh", "Marsh", PASSAGE));
	}

	@Test
	public void testPronounsOutsideWindowAreIgnored() {
		/// Ivy is mentioned once in a sentence with NO pronouns; the next sentence (window +1) has none
		/// either. The rest of the text is drenched in "he" — none of it may count.
		String text = "Ivy stood at the gate. The wind picked up.\n"
			+ "He shouted. He ran. He fell and his hat rolled away and he swore at it. He laughed at himself.";
		assertEquals("pronouns outside the mention window must not vote", "", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", text));

		/// Same text, but now the pronoun-heavy sentence directly FOLLOWS the mention → it is in range.
		String adjacent = "Ivy stood at the gate. He shouted. The wind picked up.";
		assertEquals("male", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", adjacent));
	}

	@Test
	public void testPronounsTieOrZeroIsUndetermined() {
		String tie = "Ivy stood at the gate. She waved to him.";
		assertEquals("", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", tie));
		assertEquals("", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", "Ivy stood at the gate. The wind picked up."));
		assertEquals("", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", ""));
		assertEquals("", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", null));
		/// Name absent from the passage entirely → nothing to window on.
		assertEquals("", PbGenderUtil.genderFromPronouns("Zed", "Zed", PASSAGE));
	}

	@Test
	public void testPronounsMatchWholeTokensOnly() {
		/// "The", "Heather", "hermit", "shed" contain he/her as substrings and must not count.
		String text = "Ivy stood by the shed. Heather the hermit watched the heron.";
		assertEquals("", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", text));
		/// Whole-token matches are case-insensitive and tolerate punctuation.
		assertEquals("female", PbGenderUtil.genderFromPronouns("Ivy", "Ivy", "Ivy paused, unsure of herself. \"Her?\" SHE asked."));
	}

	@Test
	public void testPronounsMatchRoleNameWithoutItsArticle() {
		/// Verbatim from the Harlot's Eight recording: the LLM named this character "The shopkeeper"
		/// and returned gender "other"; the manuscript never says "The shopkeeper", so the exact-name
		/// window was empty and the chain fell through to the hash. The role word must select the sentence.
		String text = "Simon looked over his shoulder, but the fat man had turned his back and flicked his cigar ash onto the street. "
			+ "A dark skinned shopkeeper draped sheets of silk over a beach wood dowel. She smoothed her embroidered apron and asked what he wanted. "
			+ "He shrugged and turned to leave.";
		assertEquals("female", PbGenderUtil.genderFromPronouns("The shopkeeper", "The", text));
		PbGenderUtil.Resolution r = PbGenderUtil.resolve("other", "The shopkeeper", "The", text, fn -> "");
		assertEquals("female", r.gender());
		assertEquals(PbGenderUtil.Source.PRONOUN, r.source());

		assertEquals("shopkeeper", PbGenderUtil.stripLeadingStopWord("The shopkeeper"));
		assertEquals("Dalloway", PbGenderUtil.stripLeadingStopWord("Mrs. Dalloway"));
		assertEquals("Old Man", PbGenderUtil.stripLeadingStopWord("The Old Man"));
		assertEquals("no stop-word prefix", null, PbGenderUtil.stripLeadingStopWord("Darby Wren"));
		assertEquals("single token", null, PbGenderUtil.stripLeadingStopWord("Ivy"));
		assertEquals("remainder too short", null, PbGenderUtil.stripLeadingStopWord("The X"));
		/// A generic residual must not sweep in unrelated sentences: only sentences with the token vote.
		assertEquals("", PbGenderUtil.genderFromPronouns("The stranger", "The", "Ivy paused, unsure of herself. She waited."));
	}

	// ------------------------------------------------------------------------------------------------
	// chain order
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testChainLlmWinsOverEverything() {
		/// Passage votes female for Darby and the name lookup says female; a non-empty LLM value still wins.
		PbGenderUtil.Resolution r = PbGenderUtil.resolve("Male", "Darby Wren", "Darby", PASSAGE, fn -> "female");
		assertEquals("male", r.gender());
		assertEquals(PbGenderUtil.Source.LLM, r.source());
		assertEquals("male via llm", r.toString());
	}

	@Test
	public void testChainNameLookupBeatsPronouns() {
		/// LLM empty, names list knows Darby as male, passage says female → NAME wins (list is authoritative).
		List<String> lookedUp = new ArrayList<>();
		PbGenderUtil.Resolution r = PbGenderUtil.resolve(null, "Darby Wren", "Darby", PASSAGE,
			fn -> { lookedUp.add(fn); return "M"; });
		assertEquals("male", r.gender());
		assertEquals(PbGenderUtil.Source.NAME, r.source());
		assertEquals("the lookup receives the trimmed first name", List.of("Darby"), lookedUp);
	}

	@Test
	public void testChainPronounUsedWhenLlmEmptyAndNameUnknown() {
		PbGenderUtil.Resolution r = PbGenderUtil.resolve("", "Darby Wren", "Darby", PASSAGE, NO_NAME_HIT);
		assertEquals("female", r.gender());
		assertEquals(PbGenderUtil.Source.PRONOUN, r.source());

		/// The literal string "null" from an LLM is NOT a gender.
		PbGenderUtil.Resolution r2 = PbGenderUtil.resolve("null", "Old Marsh", "Marsh", PASSAGE, NO_NAME_HIT);
		assertEquals("male", r2.gender());
		assertEquals(PbGenderUtil.Source.PRONOUN, r2.source());

		/// A throwing lookup is contained and the chain continues.
		PbGenderUtil.Resolution r3 = PbGenderUtil.resolve("", "Darby Wren", "Darby", PASSAGE,
			fn -> { throw new IllegalStateException("db down"); });
		assertEquals("female", r3.gender());
		assertEquals(PbGenderUtil.Source.PRONOUN, r3.source());

		/// Skipping the lookup entirely (null function) also lands on the pronoun step.
		PbGenderUtil.Resolution r4 = PbGenderUtil.resolve("", "Darby Wren", "Darby", PASSAGE, null);
		assertEquals(PbGenderUtil.Source.PRONOUN, r4.source());
	}

	@Test
	public void testChainHashIsDeterministicAcrossCallsAndNeverEmpty() {
		String[] names = { "Zed", "Quill", "Ambrose Pike", "  the stranger  ", "X", "", "Ø" };
		for (String n : names) {
			PbGenderUtil.Resolution a = PbGenderUtil.resolve("", n, null, "No pronouns here at all.", NO_NAME_HIT);
			PbGenderUtil.Resolution b = PbGenderUtil.resolve("", n, null, "No pronouns here at all.", NO_NAME_HIT);
			assertEquals(PbGenderUtil.Source.HASH, a.source());
			assertEquals("hash step must be stable across calls for '" + n + "'", a.gender(), b.gender());
			assertTrue("hash step must always produce a canonical gender for '" + n + "'",
				a.gender().equals("male") || a.gender().equals("female"));
			assertEquals(PbGenderUtil.hashGender(n), a.gender());
		}
		/// The parity is over the TRIMMED, LOWER-CASED name, so casing/whitespace cannot flip it.
		assertEquals(PbGenderUtil.hashGender("Darby Wren"), PbGenderUtil.hashGender("  DARBY WREN "));
		/// Pin the formula so a future "improvement" cannot silently re-gender every replayed cast.
		for (String n : names) {
			String expected = Math.floorMod(n.trim().toLowerCase(java.util.Locale.ROOT).hashCode(), 2) == 0 ? "male" : "female";
			assertEquals(expected, PbGenderUtil.hashGender(n));
		}
		assertEquals("null name is treated as empty", PbGenderUtil.hashGender(""), PbGenderUtil.hashGender(null));
	}

	// ------------------------------------------------------------------------------------------------
	// (b) names word list — real DB, throw-away group, non-admin user
	// ------------------------------------------------------------------------------------------------

	private static BaseRecord seedWord(BaseRecord user, String groupPath, String name, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord w = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORD, user, null, plist);
		assertNotNull("data.word factory newInstance returned null for " + name, w);
		w.set(FieldNames.FIELD_GENDER, gender);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, w);
		assertNotNull("AccessPoint.create returned null for data.word '" + name + "'", created);
		return created;
	}

	@Test
	public void testGenderFromNamesGroupAgainstDatabase() throws Exception {
		BaseRecord user = getCreateUser("pbGenderUser");
		assertNotNull(user);
		String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String groupPath = "~/PbGenderNames-" + nonce;

		/// Seed: one male-only, one female-only, one present under BOTH genders (e.g. "Jordan"), and
		/// a mixed-case spelling to prove the match is case-insensitive but exact.
		BaseRecord first = seedWord(user, groupPath, "Ambrose", "M");
		seedWord(user, groupPath, "Ottoline", "F");
		seedWord(user, groupPath, "Jordan", "M");
		seedWord(user, groupPath, "Jordan", "F");
		seedWord(user, groupPath, "MaryAnne", "F");

		long groupId = first.get(FieldNames.FIELD_GROUP_ID);
		assertTrue("seeded word must carry its groupId", groupId > 0L);

		assertEquals("male", PbGenderUtil.genderFromNamesGroup(groupId, "Ambrose"));
		assertEquals("case-insensitive", "male", PbGenderUtil.genderFromNamesGroup(groupId, "ambrose"));
		assertEquals("case-insensitive", "male", PbGenderUtil.genderFromNamesGroup(groupId, "AMBROSE"));
		assertEquals("female", PbGenderUtil.genderFromNamesGroup(groupId, "Ottoline"));
		assertEquals("camel-case row found by its own spelling (IN fast path)", "female", PbGenderUtil.genderFromNamesGroup(groupId, "MaryAnne"));
		assertEquals("camel-case row found from a lower-cased token (ILIKE prefix fallback + exact filter)", "female",
			PbGenderUtil.genderFromNamesGroup(groupId, "maryanne"));
		assertEquals("camel-case row found from an UPPER token", "female", PbGenderUtil.genderFromNamesGroup(groupId, "MARYANNE"));
		assertEquals("a name listed under both genders is undetermined", "", PbGenderUtil.genderFromNamesGroup(groupId, "Jordan"));
		assertEquals("unknown name is undetermined", "", PbGenderUtil.genderFromNamesGroup(groupId, "Zebulon"));
		assertEquals("prefix must not match (no LIKE semantics)", "", PbGenderUtil.genderFromNamesGroup(groupId, "Amb"));
		assertEquals("prefix of a camel-case row must not match either (the ILIKE superset is filtered exactly)", "",
			PbGenderUtil.genderFromNamesGroup(groupId, "Mary"));
		assertEquals("SQL pattern characters never reach the database", "", PbGenderUtil.genderFromNamesGroup(groupId, "Mary%"));
		assertEquals("SQL pattern characters never reach the database", "", PbGenderUtil.genderFromNamesGroup(groupId, "M_ryAnne"));
		assertEquals("stop word never hits the DB", "", PbGenderUtil.genderFromNamesGroup(groupId, "The"));
		assertEquals("bad group id is undetermined, not an error", "", PbGenderUtil.genderFromNamesGroup(-1L, "Ambrose"));

		/// Through the full chain: LLM empty → NAME step answers from the DB, beating the passage.
		PbGenderUtil.Resolution r = PbGenderUtil.resolve("", "Ambrose Pike", "Ambrose",
			"Ambrose Pike stood at the gate. She waved. She laughed.", fn -> PbGenderUtil.genderFromNamesGroup(groupId, fn));
		assertEquals("male", r.gender());
		assertEquals(PbGenderUtil.Source.NAME, r.source());
	}

	// ------------------------------------------------------------------------------------------------
	// (e) wired into PictureBookUtil.createCharPerson — real DB, real PB2 book world, NO LLM
	// ------------------------------------------------------------------------------------------------

	/// A scene the way extractChunkedInternal shapes one, with a cast of bare {name[, gender]} stubs.
	/// No role/appearance/clothing on any character, so createCharPerson's apparel step takes the
	/// random-outfit branch and never reaches callLlm (which would otherwise resolve "generalChat").
	private static Map<String, Object> castScene(String title, String sourceText, List<Map<String, Object>> cast) {
		Map<String, Object> scene = new LinkedHashMap<>();
		scene.put("title", title);
		scene.put("summary", title + " summary");
		scene.put("setting", "a salt cellar under the quay");
		scene.put("action", "the tin box is opened");
		scene.put("mood", "hushed");
		scene.put("sourceText", sourceText);
		scene.put("characters", cast);
		return scene;
	}

	private static Map<String, Object> stub(String name, String gender) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("name", name);
		if (gender != null) c.put("gender", gender);
		return c;
	}

	/// gender column of every olio.charPerson the persisted meta's scenes point at (PB2 routes the
	/// characters into the book WORLD's population group, not a "Characters" sub-group of the PB1 book
	/// group, so the meta's per-scene objectId list is the authoritative way back to them). Uncached.
	private static Map<String, String> charGenders(BaseRecord user, BaseRecord meta) throws Exception {
		List<BaseRecord> scenes = meta.get("scenes");
		assertNotNull("meta.scenes", scenes);
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		Map<String, String> out = new LinkedHashMap<>();
		for (BaseRecord scene : scenes) {
			List<String> charOids = scene.get("characters");
			if (charOids == null) continue;
			for (String oid : charOids) {
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
				q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GENDER });
				q.setCache(false);
				QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
				assertTrue("scene character " + oid + " must resolve to a persisted charPerson",
					qr != null && qr.getResults() != null && qr.getResults().length == 1);
				BaseRecord cp = qr.getResults()[0];
				out.put(cp.get(FieldNames.FIELD_NAME), cp.get(FieldNames.FIELD_GENDER));
			}
		}
		return out;
	}

	/// Drives the chain THROUGH createFromScenes → createCharPerson with chatConfigName == null, so no
	/// LLM call is possible, against a real PB2 book world whose universe carries the SSA names list
	/// (dataPath/names/yob2022.txt). Each character is built so that the three lower steps DISAGREE
	/// with the expected answer; the persisted gender therefore identifies which step fired:
	///
	///   Gwendolyn Marsh   llm ""       names F-only → female   pronouns male    hash male    ⇒ female (NAME)
	///   Darby Wren        llm ""       names F+M    → ""       pronouns female  hash male    ⇒ female (PRONOUN)
	///   Persephone Hale   llm "Male"   names F-only → female   pronouns female  hash female  ⇒ male   (LLM)
	///   Quorvix Crane     llm ""       names absent → ""       no pronouns      hash         ⇒ hashGender (HASH)
	///
	/// The expected answers are mixed (not all one value), so a constant default cannot pass. A second,
	/// fresh book from the SAME scenes must produce the SAME four genders — that is the
	/// replay-determinism the chain exists for. The run log carries one
	/// {@code gender resolved for "<name>": <gender> via <step>} line per character.
	@Test
	public void testCreateCharPersonResolvesGenderDeterministicallyWithoutLlm() throws Exception {
		OlioModelNames.use();
		BaseRecord user = getCreateUser("pbGenderUser");
		assertNotNull(user);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		/// Pin the parity the test relies on, so a renamed character cannot silently weaken the proof.
		assertEquals("male", PbGenderUtil.hashGender("Gwendolyn Marsh"));
		assertEquals("male", PbGenderUtil.hashGender("Darby Wren"));
		assertEquals("female", PbGenderUtil.hashGender("Persephone Hale"));
		String quorvixHash = PbGenderUtil.hashGender("Quorvix Crane");

		Map<String, String> first = null;
		Map<String, String> second = null;
		for (int run = 0; run < 2; run++) {
			/// Fresh maps per run: createFromScenes writes the resolved gender BACK into each character
			/// stub (charData.put("gender", ...)), so re-using run 0's list would hand run 1 an "LLM"
			/// value for every character and prove nothing about the lower steps.
			List<Map<String, Object>> scenes = new ArrayList<>();
			scenes.add(castScene("The Cellar",
				"Gwendolyn Marsh came down the cellar steps first. He held the lamp high and he did not look back. "
				+ "Darby Wren followed with the tin box. She set it on the barrel and she pried the lid loose. "
				+ "The cellar smelled of salt.",
				new ArrayList<>(List.of(stub("Gwendolyn Marsh", null), stub("Darby Wren", null)))));
			scenes.add(castScene("The Quay",
				"Persephone Hale waited on the quay. She counted the boats and she counted them again. "
				+ "Quorvix Crane said nothing at all. The tide turned.",
				new ArrayList<>(List.of(stub("Persephone Hale", "Male"), stub("Quorvix Crane", null)))));

			String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
			ParameterList wplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/PbGenderWork");
			wplist.parameter(FieldNames.FIELD_NAME, "gender-src-" + tag);
			BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, wplist);
			work.set("text", "A short manuscript. Nobody in it is described.");
			work = IOSystem.getActiveContext().getAccessPoint().create(user, work);
			assertNotNull("work note", work);

			String slug = "pbgender" + tag;
			String bookName = "PB Gender Book " + tag;
			BaseRecord pb2Book = PbBookUtil.createBook(user, dataPath, slug, bookName);
			assertNotNull("PB2 book", pb2Book);

			BaseRecord meta = PictureBookUtil.createFromScenes(user, work.get(FieldNames.FIELD_OBJECT_ID), null, "fiction",
				bookName, scenes, new ArrayList<>(), dataPath, pb2Book.get(FieldNames.FIELD_OBJECT_ID));
			assertNotNull("run " + run + " returned meta", meta);

			Map<String, String> genders = charGenders(user, meta);
			assertEquals("run " + run + " created all four characters: " + genders, 4, genders.size());
			assertEquals("run " + run + " NAME step: SSA list says Gwendolyn is F-only, beating male pronouns and a male hash",
				"female", genders.get("Gwendolyn Marsh"));
			assertEquals("run " + run + " PRONOUN step: Darby is F+M in the list, so her own sentences decide, beating a male hash",
				"female", genders.get("Darby Wren"));
			assertEquals("run " + run + " LLM step: the extracted value wins over an F-only list, female pronouns and a female hash",
				"male", genders.get("Persephone Hale"));
			assertEquals("run " + run + " HASH step: unknown name, no pronouns in range → name-hash parity",
				quorvixHash, genders.get("Quorvix Crane"));
			assertTrue("hash result is canonical", "male".equals(quorvixHash) || "female".equals(quorvixHash));
			if (run == 0) first = genders; else second = genders;
		}
		assertEquals("a replay from the same scenes into a fresh book yields the same genders", first, second);
		System.out.println("=== createCharPerson GENDER CHAIN PROOF (no LLM) === run1=" + first + " run2=" + second);
	}
}
