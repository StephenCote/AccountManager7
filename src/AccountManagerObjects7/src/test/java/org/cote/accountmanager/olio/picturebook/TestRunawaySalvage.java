package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.Chat;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// What the chunk loop gets out of a reply the runaway detector cut short — the reason Chat returns
/// the truncated text instead of null. The recorded Ourselves.doc chunk-6 reply (see
/// TestChatRunawayDetection) is cut where its loop begins and fed through the REAL package-private
/// parser the chunk loop uses. If the truncated JSON repairs, the chunk yields its distinct scenes
/// with no second LLM round at all; if it did not, the stall flag would buy one re-attempt. Either
/// is better than the five minutes of GPU and zero scenes that chunk actually produced.
public class TestRunawaySalvage extends BaseTest {

	@Test
	public void testTruncatedRunawayReplyRepairsToTheDistinctScenes() throws Exception {
		String content;
		try (InputStream is = TestRunawaySalvage.class.getResourceAsStream("/llm-fixtures/runaway/ourselves-chunk6-runaway.json")) {
			assertNotNull("fixture missing", is);
			content = (String) new ObjectMapper().readValue(is, Map.class).get("content");
		}
		/// Same growing-prefix feed the stream reader gives the detector.
		Chat.RunawayDetector det = new Chat.RunawayDetector();
		int cut = -1;
		for (int len = 0; len <= content.length() && cut < 0; len += 40) {
			if (det.check(content.substring(0, Math.min(len, content.length())))) cut = det.firstRepeatIndex;
		}
		assertTrue("detector did not fire on the recorded runaway", cut > 0);
		String truncated = content.substring(0, cut);

		boolean[] ok = new boolean[1];
		List<String> failed = new ArrayList<>();
		Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObject(truncated, "test:runaway-salvage", failed, ok);
		assertTrue("the truncated reply must repair into a parseable object (failures: " + failed + ")", ok[0]);
		Object add = parsed.get("additions");
		assertTrue("repaired object must carry the additions list", add instanceof List);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> scenes = (List<Map<String, Object>>) add;
		List<String> titles = new ArrayList<>();
		for (Map<String, Object> s : scenes) titles.add(String.valueOf(s.get("title")));
		logger.info("[RUNAWAY-SALVAGE] cut at " + cut + " of " + content.length() + " chars -> " + scenes.size()
			+ " scenes: " + titles);
		/// Scenes 0-9 are complete before the cut; scene 10 is cut mid-body and may or may not
		/// survive the repair. Ten distinct scenes is the floor.
		assertTrue("expected at least the ten complete scenes before the loop, got " + scenes.size(), scenes.size() >= 10);
		assertEquals("Lara examines a concave counter top", titles.get(0));
		assertTrue("the last fully distinct scene must survive", titles.contains("Silver web appears at the edge of a countertop indentation"));
		for (String t : titles) {
			assertTrue("a looped scene leaked through the cut: " + t,
				!t.contains("palms as she turns from the counter top") && !t.contains("Materialization of items occurs"));
		}
	}
}
