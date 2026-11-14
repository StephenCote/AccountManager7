package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The single-shot (short text) extraction path must stamp the same scene provenance the chunked
 * path does: {@code sourceChunk} (durable, 0 for the one-chunk text) and {@code sourceText}
 * (transient). Pure static — no model server, no database.
 *
 * <p>Found 2026-11-13 by {@code pictureBookChapteredManuscriptEmulated.spec.js} on Harlot's Eight
 * chapter 21 (under {@link PictureBookUtil#MAX_EXTRACTION_TEXT_CHARS}, so single-shot): all 10 of
 * its persisted scenes carried no {@code sourceChunk} while every chunked chapter's did.
 */
public class TestPbSingleShotProvenance {

	private static Map<String, Object> scene(String title) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("index", 0);
		s.put("title", title);
		return s;
	}

	@Test
	public void singleShotStampsChunkZeroAndSourceText() {
		String text = "Chapter 21\n\nSimon woke to the smell of smoke.";
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Simon in the Morning"));
		Map<String, Object> withSummary = scene("Townsfolk Burning Bodies");
		withSummary.put("summary", "The townsfolk burn the dead.");
		scenes.add(withSummary);
		scenes.add(null);

		PictureBookUtil.normalizeSingleShotScenes(scenes, text);

		for (Map<String, Object> s : scenes) {
			if (s == null) continue;
			assertEquals("single-shot provenance is chunk 0 of a one-chunk text", Integer.valueOf(0), s.get("sourceChunk"));
			assertTrue("sourceChunk must be the integer the chunked path stamps", s.get("sourceChunk") instanceof Integer);
			assertEquals("the whole text is the one content block", text, s.get("sourceText"));
		}
		assertNull("a scene without summary gains no blurb", scenes.get(0).get("blurb"));
		assertEquals("summary is carried into blurb", "The townsfolk burn the dead.", scenes.get(1).get("blurb"));
	}

	@Test
	public void singleShotProvenanceSurvivesPersistenceShape() {
		String text = "Chapter 21\n\nShort chapter.";
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Nocht's Question"));
		PictureBookUtil.normalizeSingleShotScenes(scenes, text);

		Map<String, Object> store = PictureBookUtil.sceneNoteStore(scenes.get(0), 9);
		assertEquals("sourceChunk is retained in the scene note JSON", Integer.valueOf(0), store.get("sourceChunk"));
		assertFalse("sourceText is stripped before persistence", store.containsKey("sourceText"));
		assertEquals(Integer.valueOf(9), store.get("sceneIndex"));
	}

	@Test
	public void oneChunkTextIsExactlyOneChunk() {
		/// The stamp's premise: anything the single-shot path accepts is one chunkText chunk, so
		/// index 0 is what the chunked path would have assigned.
		StringBuilder sb = new StringBuilder();
		while (sb.length() < PictureBookUtil.MAX_EXTRACTION_TEXT_CHARS) sb.append("A sentence of the chapter. ");
		String atLimit = sb.substring(0, PictureBookUtil.MAX_EXTRACTION_TEXT_CHARS);
		assertEquals(1, PictureBookUtil.chunkText(atLimit).size());
		assertEquals(1, PictureBookUtil.chunkText("tiny").size());
	}

	@Test
	public void nullAndEmptyListsAreNoOps() {
		PictureBookUtil.normalizeSingleShotScenes(null, "x");
		List<Map<String, Object>> empty = new ArrayList<>();
		PictureBookUtil.normalizeSingleShotScenes(empty, "x");
		assertTrue(empty.isEmpty());
	}
}
