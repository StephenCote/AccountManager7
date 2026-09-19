package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/**
 * A scene's cast came from the LLM and was never checked against the passage the scene was
 * extracted from.
 *
 * <p>MEASURED on am72db 2026-09-19, book "BWO 3". Scenes 12 and 13 ("The Budget Interface" /
 * "The Budget Explanation") sit in the middle of a fourteen-scene apartment sequence that belongs to
 * Veronique (scenes 9-22), but both name <b>Yolanda</b> — a real character who otherwise appears
 * only from scene 28 onward. Both the {@code characters} list AND the {@code action} text named her,
 * so every downstream consumer was internally consistent and the scene simply rendered the wrong
 * person.
 *
 * <p>Two things made it possible, and both are addressed here:
 * <ol>
 * <li>{@code scenesForPrompt} reduced every scene older than the last six to its TITLE ALONE, while
 *     still leaving it addressable by title for a {@code revisions} entry that overwrites
 *     {@code characters} wholesale. The model could rewrite the cast of a scene whose cast it could
 *     no longer see. The reduced entry now carries the character names.</li>
 * <li>Nothing corroborated a scene's cast against its own source passage.
 *     {@code charactersNotInSourceText} now does, and reports rather than corrects.</li>
 * </ol>
 */
public class TestSceneCharacterProvenance {
	public static final Logger logger = LogManager.getLogger(TestSceneCharacterProvenance.class);

	private static Map<String, Object> scene(String title, String sourceText, String... charNames) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("title", title);
		s.put("blurb", "b");
		s.put("setting", "a room");
		s.put("action", "something happens");
		s.put("mood", "quiet");
		s.put("diffusionPrompt", "p");
		if (sourceText != null) s.put("sourceText", sourceText);
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

	// ── The source-passage check ─────────────────────────────────────────────

	/// The reported case, reduced: the passage is Veronique's, the cast says Yolanda.
	@Test
	public void aCastMemberMissingFromThePassageIsReported() {
		Map<String, Object> s = scene("The Budget Explanation",
			"Veronique gestured at the budget display, explaining the draw bar to Darby.",
			"Yolanda", "Darby");

		List<String> missing = PictureBookUtil.charactersNotInSourceText(s);
		assertEquals("Only the uncorroborated name is reported", List.of("Yolanda"), missing);
	}

	/// ...and a cast the passage does corroborate is silent. A check that fires on correct data is
	/// worse than no check.
	@Test
	public void aCorroboratedCastIsNotReported() {
		Map<String, Object> s = scene("The First Interaction",
			"Veronique extends her hand, guiding Darby into the living room.",
			"Veronique", "Darby");
		assertTrue(PictureBookUtil.charactersNotInSourceText(s).isEmpty());
	}

	/// A name matches on any token longer than two characters, so a relational name is corroborated
	/// by the person it refers to. "Darby's dad" is satisfied by a passage naming Darby.
	@Test
	public void aRelationalNameMatchesOnItsPossessor() {
		Map<String, Object> s = scene("The Last Look",
			"Darby stood behind the hatchback while her father loaded the last case.",
			"Darby's dad", "Darby");
		assertTrue("'Darby's dad' is corroborated by 'Darby' in the passage",
			PictureBookUtil.charactersNotInSourceText(s).isEmpty());
	}

	/// Case and accents must not produce false positives - the passage and the persisted name come
	/// from different LLM calls and routinely disagree on both.
	@Test
	public void matchingIsCaseAndAccentInsensitive() {
		assertTrue(PictureBookUtil.charactersNotInSourceText(
			scene("t", "VERONIQUE opened the door.", "Veronique")).isEmpty());
		assertTrue(PictureBookUtil.charactersNotInSourceText(
			scene("t", "Duna crossed the hall.", "Duña")).isEmpty());
	}

	/// No passage means nothing to check against. Scenes resumed from a checkpoint before their
	/// sourceText is rehydrated must not all be flagged.
	@Test
	public void aSceneWithNoSourcePassageIsNotFlagged() {
		assertTrue(PictureBookUtil.charactersNotInSourceText(
			scene("t", null, "Yolanda")).isEmpty());
		assertTrue(PictureBookUtil.charactersNotInSourceText(
			scene("t", "   ", "Yolanda")).isEmpty());
		assertTrue(PictureBookUtil.charactersNotInSourceText(null).isEmpty());
	}

	/// The collector reports scene index and title, because "Yolanda is unverified" is not
	/// actionable without knowing where.
	@Test
	public void theCollectorNamesTheSceneAndTheCharacter() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("The First Interaction", "Veronique guides Darby in.", "Veronique", "Darby"));
		scenes.add(scene("The Budget Explanation", "Veronique explained the draw bar to Darby.", "Yolanda", "Darby"));

		List<String> found = PictureBookUtil.collectUnverifiedSceneCharacters(scenes);
		assertEquals(1, found.size());
		assertTrue(found.get(0), found.get(0).contains("scene 1"));
		assertTrue(found.get(0), found.get(0).contains("The Budget Explanation"));
		assertTrue(found.get(0), found.get(0).contains("Yolanda"));
	}

	// ── The reduced prompt entry ─────────────────────────────────────────────

	/// A scene older than the detail window keeps its TITLE and its CAST. The cast is what the
	/// model needs to avoid re-titling or re-casting a scene it can no longer see, and it is cheap:
	/// names only, against the ~1.5KB per scene the reduction exists to avoid.
	@Test
	public void olderScenesKeepTheirCastInTheChunkPrompt() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		// Comfortably past PROMPT_SCENE_DETAIL_WINDOW so scene 0 is definitely reduced.
		scenes.add(scene("The Budget Interface", "p", "Veronique", "Darby"));
		for (int i = 1; i < 12; i++) scenes.add(scene("Later Scene " + i, "p", "Darby"));

		List<Map<String, Object>> forPrompt = PictureBookUtil.scenesForPrompt(scenes);
		Map<String, Object> reduced = forPrompt.get(0);

		assertEquals("The Budget Interface", reduced.get("title"));
		assertEquals("A reduced scene must still say who is in it",
			List.of("Veronique", "Darby"), reduced.get("characters"));
		// Still reduced: the expensive per-scene fields stay out.
		assertFalse(reduced.containsKey("blurb"));
		assertFalse(reduced.containsKey("diffusionPrompt"));
		assertFalse(reduced.containsKey("sourceText"));
	}

	/// The reduction must stay cheap. Names only - not the full character objects with their roles.
	@Test
	public void theReducedEntryStaysSmall() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Opening", "a very long passage ".repeat(100), "Veronique", "Darby"));
		for (int i = 1; i < 12; i++) scenes.add(scene("Later " + i, "p", "Darby"));

		String json = JSONUtil.exportObject(PictureBookUtil.scenesForPrompt(scenes).get(0));
		assertFalse("the transient passage must never reach the prompt", json.contains("very long passage"));
		assertFalse("roles are not needed to tell scenes apart", json.contains("role"));
		assertTrue("a reduced entry must stay tiny: " + json.length() + " chars", json.length() < 220);
	}

	/// LLM placeholder values must not be presented to the model as established characters.
	@Test
	public void placeholderNamesAreExcludedFromTheReducedEntry() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Opening", "p", "Darby", "null", "unknown"));
		for (int i = 1; i < 12; i++) scenes.add(scene("Later " + i, "p", "Darby"));

		assertEquals(List.of("Darby"), PictureBookUtil.scenesForPrompt(scenes).get(0).get("characters"));
	}

	/// A scene inside the detail window is untouched by this change - it already carried everything.
	@Test
	public void recentScenesKeepFullDetail() {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Only Scene", "p", "Darby"));
		Map<String, Object> full = PictureBookUtil.scenesForPrompt(scenes).get(0);
		assertTrue(full.containsKey("blurb"));
		assertTrue(full.containsKey("characters"));
	}
}
