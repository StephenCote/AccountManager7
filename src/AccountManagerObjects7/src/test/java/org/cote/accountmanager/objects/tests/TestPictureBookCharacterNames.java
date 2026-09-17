package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.junit.Test;

/**
 * Issue 3: "Character extraction can repeat for unnamed characters like Darby's dad."
 *
 * <p>Two mechanisms produced the duplicates, and both are covered here.
 *
 * <p><b>The extraction lost track of established names.</b> {@code scenesForPrompt} reduces every
 * scene older than the last six to its TITLE ALONE (deliberately — otherwise the chunk prompt grows
 * quadratically), and characters are part of the detail that gets dropped. So by a late chunk the
 * model could no longer see what it had called someone earlier. A complete names-only roster
 * ({@code knownCharacters}) is now threaded into the prompt instead.
 *
 * <p><b>De-duplication was an exact string match.</b> {@code createFromScenes} keyed
 * {@code uniqueChars} on the raw name, so "Darby's dad", "Darby's Dad", "the father" and "Dad" were
 * four characters — each with its own portrait, statistics and wardrobe, and each holding only part
 * of that person's scenes. Scene notes pin characters BY NAME, so the scenes split too.
 *
 * <p>These are pure tests: no DB, no LLM. The canonicalisation is deliberately conservative —
 * anything genuinely ambiguous is left alone for the merge endpoint rather than guessed at.
 */
public class TestPictureBookCharacterNames {
	public static final Logger logger = LogManager.getLogger(TestPictureBookCharacterNames.class);

	private static Map<String, Object> scene(String... charNames) {
		Map<String, Object> s = new LinkedHashMap<>();
		List<Object> chars = new ArrayList<>();
		for (String n : charNames) {
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("name", n);
			c.put("role", "someone");
			chars.add(c);
		}
		s.put("characters", chars);
		return s;
	}

	@SuppressWarnings("unchecked")
	private static List<String> namesOf(Map<String, Object> s) {
		List<String> out = new ArrayList<>();
		for (Object o : (List<Object>) s.get("characters")) {
			out.add((String) ((Map<String, Object>) o).get("name"));
		}
		return out;
	}

	// ── The comparison key ───────────────────────────────────────────────────

	/// Case, punctuation, possessive form and kinship synonym all collapse: these are the four
	/// spellings the report named, and they must be ONE character.
	@Test
	public void theReportedSpellingsAllShareOneKey() {
		String k = PictureBookUtil.characterNameKey("Darby's dad");
		assertEquals(k, PictureBookUtil.characterNameKey("Darby's Dad"));
		assertEquals(k, PictureBookUtil.characterNameKey("darbys dad"));
		assertEquals(k, PictureBookUtil.characterNameKey("Darby's father"));
		assertEquals(k, PictureBookUtil.characterNameKey("Darby's Papa"));
		assertEquals("darby father", k);
	}

	/// Curly apostrophes too. The extraction is LLM prose, and a model emits U+2019 as readily as
	/// an ASCII quote — if only ASCII were handled, half the possessives would key separately.
	@Test
	public void curlyApostrophesKeyTheSameAsAsciiOnes() {
		assertEquals(PictureBookUtil.characterNameKey("Darby's dad"),
			PictureBookUtil.characterNameKey("Darby’s dad"));
	}

	/// Articles and honorifics are noise.
	@Test
	public void articlesAndHonorificsAreStripped() {
		assertEquals(PictureBookUtil.characterNameKey("Guard"), PictureBookUtil.characterNameKey("The Guard"));
		assertEquals(PictureBookUtil.characterNameKey("Smith"), PictureBookUtil.characterNameKey("Mr. Smith"));
		assertEquals(PictureBookUtil.characterNameKey("Smith"), PictureBookUtil.characterNameKey("Mr Smith"));
	}

	/// "old"/"young"/"little" carry no identity: "Darby's old dad" is Darby's dad.
	@Test
	public void ageFillerIsStripped() {
		assertEquals(PictureBookUtil.characterNameKey("Darby's dad"),
			PictureBookUtil.characterNameKey("Darby's old dad"));
	}

	/// "Father Brown" the priest must NOT become "Brown". Honorific stripping deliberately excludes
	/// the kinship words for exactly this reason.
	@Test
	public void aPriestlyFatherIsNotAnHonorific() {
		assertNotEquals("Father Brown must not collapse to Brown",
			PictureBookUtil.characterNameKey("Brown"),
			PictureBookUtil.characterNameKey("Father Brown"));
	}

	/// Two genuinely different people must never share a key.
	@Test
	public void differentPeopleKeepDifferentKeys() {
		assertNotEquals(PictureBookUtil.characterNameKey("Darby"),
			PictureBookUtil.characterNameKey("Darby's dad"));
		assertNotEquals(PictureBookUtil.characterNameKey("Darby's dad"),
			PictureBookUtil.characterNameKey("Darby's mom"));
		assertNotEquals(PictureBookUtil.characterNameKey("Jideon de Rosa"),
			PictureBookUtil.characterNameKey("Jideon"));
	}

	@Test
	public void unusableNamesKeyToEmpty() {
		assertEquals("", PictureBookUtil.characterNameKey(null));
		assertEquals("", PictureBookUtil.characterNameKey("   "));
		assertEquals("", PictureBookUtil.characterNameKey("---"));
		assertEquals("", PictureBookUtil.characterNameKey("the"));
	}

	// ── The resolver ─────────────────────────────────────────────────────────

	/// The FIRST spelling seen wins, which is what was asked for: "use just the first version".
	@Test
	public void theFirstSpellingSeenBecomesTheCanonicalName() {
		PictureBookUtil.CharacterNameResolver r = new PictureBookUtil.CharacterNameResolver();
		assertEquals("Darby's dad", r.resolve("Darby's dad"));
		assertEquals("Darby's dad", r.resolve("Darby's Dad"));
		assertEquals("Darby's dad", r.resolve("Darby's father"));
		assertEquals(1, r.getCanonicalNames().size());
		assertEquals(2, r.getAliases().size());
	}

	/// A BARE relation joins the sole possessive form of that relation — the "Dad" case.
	@Test
	public void aBareRelationJoinsTheOnlyPossessiveFormOfIt() {
		PictureBookUtil.CharacterNameResolver r = new PictureBookUtil.CharacterNameResolver();
		r.resolve("Darby");
		r.resolve("Darby's dad");
		assertEquals("Darby's dad", r.resolve("Dad"));
		assertEquals("Darby's dad", r.resolve("the father"));
		assertEquals("Darby and Darby's dad, nothing more", 2, r.getCanonicalNames().size());
	}

	/// ...but NOT when the book has two of them. A book with two families must not have every "Dad"
	/// collapsed onto one person, and which family is meant is not something this can know — so the
	/// bare mention stays separate and mergeCharacters decides.
	@Test
	public void anAmbiguousBareRelationIsLeftAloneForManualMerge() {
		PictureBookUtil.CharacterNameResolver r = new PictureBookUtil.CharacterNameResolver();
		r.resolve("Darby's dad");
		r.resolve("Mia's dad");
		String bare = r.resolve("Dad");
		assertEquals("An ambiguous bare relation must stay as itself", "Dad", bare);
		assertEquals(3, r.getCanonicalNames().size());
	}

	/// A possessive form arriving AFTER a bare one must not retroactively steal it — the bare name
	/// already created a character with scenes attached, and silently repointing it would move
	/// scenes onto someone the earlier chunks never said they belonged to.
	@Test
	public void aLaterPossessiveDoesNotStealAnEarlierBareRelation() {
		PictureBookUtil.CharacterNameResolver r = new PictureBookUtil.CharacterNameResolver();
		assertEquals("Dad", r.resolve("Dad"));
		assertEquals("Darby's dad", r.resolve("Darby's dad"));
		assertEquals("Dad", r.resolve("Dad"));
	}

	@Test
	public void unusableNamesResolveToNull() {
		PictureBookUtil.CharacterNameResolver r = new PictureBookUtil.CharacterNameResolver();
		assertNull(r.resolve(null));
		assertNull(r.resolve("  "));
		assertNull(r.resolve("!!!"));
	}

	// ── The in-place scene pass ──────────────────────────────────────────────

	/// The whole point: the scene notes themselves must end up carrying the canonical name, because
	/// resolveSceneCharacter looks characters up BY NAME at imaging time. Rewriting only the
	/// character list would leave the scenes pointing at names no charPerson has.
	@Test
	public void sceneCharacterNamesAreRewrittenInPlace() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Darby", "Darby's dad"));
		scenes.add(scene("Darby", "Darby's Dad"));
		scenes.add(scene("the father"));
		scenes.add(scene("Dad", "Darby"));

		Map<String, String> aliases = PictureBookUtil.canonicalizeSceneCharacterNames(scenes, null);

		assertEquals(List.of("Darby", "Darby's dad"), namesOf(scenes.get(0)));
		assertEquals(List.of("Darby", "Darby's dad"), namesOf(scenes.get(1)));
		assertEquals(List.of("Darby's dad"), namesOf(scenes.get(2)));
		assertEquals(List.of("Darby's dad", "Darby"), namesOf(scenes.get(3)));
		assertEquals(3, aliases.size());
		assertTrue(aliases.containsKey("Darby's Dad"));
		assertTrue(aliases.containsKey("the father"));
		assertTrue(aliases.containsKey("Dad"));
	}

	/// After the pass, an exact-string de-duplication (which is what createFromScenes does) yields
	/// ONE character — and every scene that person appears in is attributed to them, so the reduce
	/// step sees all of their passages instead of a partial view per spelling.
	@Test
	public void exactStringDeduplicationNowYieldsOneCharacter() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Darby's dad"));
		scenes.add(scene("Darby's Dad"));
		scenes.add(scene("the father"));
		PictureBookUtil.canonicalizeSceneCharacterNames(scenes, null);

		Map<String, Integer> sceneCountByName = new LinkedHashMap<>();
		for (Map<String, Object> s : scenes) {
			for (String n : namesOf(s)) {
				sceneCountByName.merge(n, 1, Integer::sum);
			}
		}
		assertEquals("One character, not three", 1, sceneCountByName.size());
		assertEquals("...holding all three scenes", Integer.valueOf(3), sceneCountByName.get("Darby's dad"));
	}

	/// Curated Step 3 names are authoritative: a scene mention folds onto the user's spelling, not
	/// the other way round.
	@Test
	public void curatedNamesWinAsTheCanonicalSpelling() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Darby's dad"));
		PictureBookUtil.canonicalizeSceneCharacterNames(scenes, List.of("Darby's Father"));
		assertEquals(List.of("Darby's Father"), namesOf(scenes.get(0)));
	}

	/// Malformed scene lists must not throw — extraction output is LLM JSON.
	@Test
	public void malformedSceneListsAreTolerated() {
		assertNotNull(PictureBookUtil.canonicalizeSceneCharacterNames(null, null));
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(new LinkedHashMap<>());
		Map<String, Object> notAList = new LinkedHashMap<>();
		notAList.put("characters", "Darby");
		scenes.add(notAList);
		assertNotNull(PictureBookUtil.canonicalizeSceneCharacterNames(scenes, null));
	}

	// ── The roster threaded into the chunk prompt ────────────────────────────

	/// Complete and in first-seen order — unlike previousScenes, which only carries characters for
	/// the last six scenes.
	@Test
	public void theRosterIsCompleteAndOrdered() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Darby", "Darby's dad"));
		for (int i = 0; i < 10; i++) scenes.add(scene("Mia"));
		List<String> roster = PictureBookUtil.knownCharacterNames(scenes);
		assertEquals(List.of("Darby", "Darby's dad", "Mia"), roster);
	}

	/// A roster telling the model that "null" is an established character is worse than no roster,
	/// so LLM placeholder values are screened out with isMeaningful rather than a blank check.
	@Test
	public void theRosterExcludesLlmPlaceholderValues() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Darby", "null", "unknown", "n/a", "  "));
		assertEquals(List.of("Darby"), PictureBookUtil.knownCharacterNames(scenes));
	}

	@Test
	public void theRosterIsEmptyRatherThanNullForNoScenes() {
		assertTrue(PictureBookUtil.knownCharacterNames(null).isEmpty());
		assertTrue(PictureBookUtil.knownCharacterNames(new ArrayList<>()).isEmpty());
	}

	/// The prompt resource has to actually declare the variable, or the roster is computed and
	/// thrown away. Both halves matter: the raw prompt AND the library template, which are separate
	/// resources that have drifted before.
	@Test
	public void theChunkPromptDeclaresTheRosterVariable() {
		String user = PromptResourceUtil.getString("pictureBook.extract-chunk", "user");
		assertNotNull("The extract-chunk user prompt must load", user);
		assertTrue("The prompt must declare {knownCharacters}", user.contains("{knownCharacters}"));
		assertTrue("...and must still declare the pre-existing variables",
			user.contains("{previousScenes}") && user.contains("{chunk}"));
		String system = PromptResourceUtil.getString("pictureBook.extract-chunk", "system");
		assertNotNull(system);
		assertTrue("The system prompt must tell the model to reuse established names",
			system.toLowerCase().contains("reuse those names"));
	}

	// ── Merge support: scene-reference union ──────────────────────────────

	/// When duplicates are merged, the keeper's scene references must become the UNION, or
	/// scene-tagged apparel selection stops resolving for the scenes that belonged to the duplicate.
	@Test
	public void sceneRefsParseForUnioning() {
		assertEquals(List.of(0, 3, 7), PictureBookUtil.parseSceneRefs("0,3,7"));
		assertEquals(List.of(0, 3), PictureBookUtil.parseSceneRefs(" 0 , 3 "));
	}

	/// The attribute is LLM-adjacent free text written by the extraction pipeline, so a malformed
	/// entry must be skipped rather than throwing mid-merge — references have already been
	/// repointed by the time this runs, and an exception there would leave the book half-merged.
	@Test
	public void malformedSceneRefsAreSkippedNotThrown() {
		assertEquals(List.of(1, 2), PictureBookUtil.parseSceneRefs("1,x,2"));
		assertTrue(PictureBookUtil.parseSceneRefs("").isEmpty());
		assertTrue(PictureBookUtil.parseSceneRefs("   ").isEmpty());
		assertTrue(PictureBookUtil.parseSceneRefs(null).isEmpty());
		assertTrue(PictureBookUtil.parseSceneRefs("not,a,number").isEmpty());
	}
}
