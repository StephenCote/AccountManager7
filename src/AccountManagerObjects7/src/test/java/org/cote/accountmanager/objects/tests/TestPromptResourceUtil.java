package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.*;

import java.util.Map;

import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.junit.Test;

/**
 * Verifies the prompt-resource error handling: (1) the two picture-book prompt resources that had
 * unescaped inner double-quotes (line-2 JSON syntax errors — the JsonParseException seen in
 * prepareSceneImagePrompts) now parse, and (2) PromptResourceUtil caches results so a single
 * malformed/missing resource can't cascade into a repeated re-parse/re-log on every call.
 * Pure classpath-resource + parse logic — no DB/LLM, so it does not extend BaseTest.
 */
public class TestPromptResourceUtil {

	@Test
	public void testPreviouslyMalformedPromptsNowParse() {
		Map<String, Object> sceneImg = PromptResourceUtil.load("pictureBook.scene-image-prompt");
		assertNotNull("scene-image-prompt resource must parse (was malformed: unescaped inner quotes)", sceneImg);
		assertNotNull("scene-image-prompt must expose a system field",
				PromptResourceUtil.getString("pictureBook.scene-image-prompt", "system"));
		assertNotNull("scene-image-prompt must expose a user field",
				PromptResourceUtil.getString("pictureBook.scene-image-prompt", "user"));

		Map<String, Object> landscape = PromptResourceUtil.load("pictureBook.landscape-prompt");
		assertNotNull("landscape-prompt resource must parse (was malformed: unescaped inner quotes)", landscape);
		assertNotNull(PromptResourceUtil.getString("pictureBook.landscape-prompt", "system"));
	}

	@Test
	public void testValidResourceIsCached() {
		Map<String, Object> a = PromptResourceUtil.load("pictureBook.scene-image-prompt");
		Map<String, Object> b = PromptResourceUtil.load("pictureBook.scene-image-prompt");
		assertNotNull(a);
		// Same instance == the parse path was not re-entered — this is the mechanism that stops the
		// per-scene cascade (a re-parse would build a new map each call).
		assertSame("repeated load() must return the cached instance, not re-parse", a, b);
	}

	@Test
	public void testMissingResourceCachedAsNullNoCascade() {
		String missing = "pictureBook.__does_not_exist__";
		assertNull("missing resource returns null without throwing", PromptResourceUtil.load(missing));
		assertNull("second call also null (failure cached — no repeat lookup/log)", PromptResourceUtil.load(missing));
		assertNull(PromptResourceUtil.getString(missing, "system"));
	}
}
