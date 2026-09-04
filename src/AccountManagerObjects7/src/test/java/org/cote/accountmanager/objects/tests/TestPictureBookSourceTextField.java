package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Regression test for the reported "server error during character extraction:
/// Invalid field: olio.pictureBookScene.sourceText".
///
/// Root cause (verified): the two-step PictureBook/STORY flow (POST .../extract-scenes-only ->
/// client -> POST .../create-from-scenes) carries a transient "sourceText" on each in-memory scene
/// map -- the raw passage a scene was extracted from, so the per-character reduce reads the RIGHT
/// text (PictureBookUtil sets it at extractChunkedInternal/extractScenesOnly and reads it in
/// createFromScenes). The olio.pictureBookScene model did NOT declare sourceText, so on the Step-2
/// create-from-scenes request deserialization (PictureBookService.parseParams ->
/// JSONUtil.importObject of an olio.pictureBookRequest whose sceneList carries olio.pictureBookScene
/// elements) RecordDeserializer logged "Invalid field: olio.pictureBookScene.sourceText" and SILENTLY
/// DROPPED it (KI-24 pattern; RecordDeserializer.java:250-254 logs, does not throw) -- starving the
/// per-character reduce of its source passages.
///
/// Fix: declare sourceText as an EPHEMERAL string on pictureBookSceneModel.json. That makes it a
/// KNOWN field (so it round-trips through the client for the reduce) while never being persisted
/// (the model is ioConstraints:["unknown"] anyway) and never reaching an LLM (scenesForPrompt
/// whitelists PROMPT_SCENE_FIELDS, which excludes it) or the scene note (createSceneNote strips it).
///
/// These assertions are pure in-memory schema/serialization checks -- no LLM, no DB writes -- so they
/// run deterministically. BaseTest.setup() registers the olio models (OlioModelNames.use()).
public class TestPictureBookSourceTextField extends BaseTest {

	private static final String SCENE_MODEL = OlioModelNames.MODEL_PICTURE_BOOK_SCENE;
	private static final String RESULT_MODEL = OlioModelNames.MODEL_PICTURE_BOOK_RESULT;
	private static final String SOURCE_TEXT =
		"Outside, the rain began to fall, light and misty, fog churning like a smoldering fire "
		+ "through the streets. Two strangers, abandoned at the singles event, met eyes.";

	/// The field must exist on the schema and be ephemeral (KNOWN => not dropped by the deserializer;
	/// ephemeral => never persisted / no DDL). Before the fix, getFieldSchema("sourceText") is null.
	@Test
	public void TestSourceTextIsEphemeralDeclaredField() throws Exception {
		ModelSchema ms = RecordFactory.getSchema(SCENE_MODEL);
		assertNotNull("olio.pictureBookScene schema must load", ms);
		FieldSchema fs = ms.getFieldSchema("sourceText");
		assertNotNull("sourceText must be a declared field on olio.pictureBookScene", fs);
		assertTrue("sourceText must be ephemeral (never persisted)", fs.isEphemeral());

		/// Setting it on a typed record must not throw (pre-fix, set() -> newFieldInstance would throw
		/// FieldException: field was not found).
		BaseRecord scene = RecordFactory.newInstance(SCENE_MODEL);
		scene.set("sourceText", SOURCE_TEXT);
		assertEquals("sourceText must be readable back off a typed scene record", SOURCE_TEXT, scene.get("sourceText"));
	}

	/// THE meaningful regression: mirror PictureBookService.parseParams / createFromScenes -- import an
	/// olio.pictureBookRequest whose sceneList scene carries sourceText, exactly as the Ux posts it.
	/// Must not throw, the scene must survive, and sourceText MUST be preserved (pre-fix it is dropped
	/// to null, breaking the per-character reduce).
	@Test
	public void TestSourceTextSurvivesCreateFromScenesDeserialization() throws Exception {
		String json =
			"{"
			+ "\"schema\":\"" + OlioModelNames.MODEL_PICTURE_BOOK_REQUEST + "\","
			+ "\"count\":3,"
			+ "\"bookName\":\"SourceText Roundtrip\","
			+ "\"sceneList\":[{"
			+   "\"schema\":\"" + SCENE_MODEL + "\","
			+   "\"index\":0,"
			+   "\"title\":\"Two Strangers\","
			+   "\"setting\":\"A rainy street at night\","
			+   "\"action\":\"Two strangers meet after being abandoned\","
			+   "\"mood\":\"melancholy\","
			+   "\"sourceText\":\"" + SOURCE_TEXT + "\","
			+   "\"characters\":[\"Aime\",\"Stranger\"]"
			+ "}]"
			+ "}";

		BaseRecord req = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("olio.pictureBookRequest must deserialize", req);

		List<BaseRecord> sceneList = req.get("sceneList");
		assertNotNull("sceneList must survive deserialization", sceneList);
		assertEquals("exactly one scene expected", 1, sceneList.size());

		BaseRecord scene = sceneList.get(0);
		assertNotNull("scene element must survive deserialization", scene);
		assertEquals("scene title must survive", "Two Strangers", scene.get("title"));

		String preserved = scene.get("sourceText");
		assertNotNull("sourceText MUST survive the create-from-scenes round-trip (was silently dropped pre-fix)", preserved);
		assertEquals("sourceText content must be preserved verbatim", SOURCE_TEXT, preserved);
	}

	/// Guard the serialization coercion site (PictureBookService:235 out.set("sceneList", result.scenes)
	/// / PictureBookUtil result.set("sceneList", sceneList) with raw List<Map> elements carrying
	/// sourceText, then toFullString()). Must not throw.
	@Test
	public void TestSourceTextSceneListSerializationDoesNotThrow() throws Exception {
		BaseRecord result = RecordFactory.newInstance(RESULT_MODEL);

		List<Object> scenes = new ArrayList<>();
		Map<String, Object> sc = new HashMap<>();
		sc.put("index", 0);
		sc.put("title", "Two Strangers");
		sc.put("sourceText", SOURCE_TEXT);
		sc.put("characters", Arrays.asList("Aime", "Stranger"));
		scenes.add(sc);

		result.set("sceneList", scenes);
		String out = result.toFullString();
		assertNotNull("pictureBookResult must serialize with a raw-map sceneList", out);
		assertTrue("serialized result must include sceneList", out.contains("sceneList"));
	}
}
