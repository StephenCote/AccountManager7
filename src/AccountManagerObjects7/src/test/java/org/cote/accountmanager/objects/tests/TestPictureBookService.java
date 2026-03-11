package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/**
 * Unit tests for PictureBook data model patterns and JSON meta structure.
 * These tests verify the record operations the service relies on without
 * requiring a live LLM or SD server.
 */
public class TestPictureBookService extends BaseTest {

    private static final int MAX_SCENES_DEFAULT = 3;

    // ── Helper: ensure Scenes/ sub-group ─────────────────────────────────

    private BaseRecord ensureGroup(BaseRecord user, String path) {
        return IOSystem.getActiveContext().getPathUtil().makePath(
                user, ModelNames.MODEL_GROUP, path,
                GroupEnumType.DATA.toString(),
                ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
    }

    // ── Test 1: Scene count cap ───────────────────────────────────────────

    @Test
    public void TestSceneCountCap() {
        int requested = 10;
        int capped = Math.min(requested, MAX_SCENES_DEFAULT);
        assertEquals("Scene count should be capped at " + MAX_SCENES_DEFAULT, MAX_SCENES_DEFAULT, capped);

        requested = 2;
        capped = Math.min(requested, MAX_SCENES_DEFAULT);
        assertEquals("Scene count below cap should be unchanged", 2, capped);
    }

    // ── Test 2: LLM JSON array parsing ───────────────────────────────────

    @Test
    public void TestParseLlmJsonArray() {
        String llmResponse = "```json\n[\n  {\"title\": \"Scene 1\", \"summary\": \"A duel at dawn\", \"characters\": [{\"name\": \"Alice\"}]},\n  {\"title\": \"Scene 2\", \"summary\": \"Escape through the forest\"}\n]\n```";

        // Strip markdown fences
        String trimmed = llmResponse.trim();
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl >= 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        assertTrue("Array brackets found", start >= 0 && end > start);
        trimmed = trimmed.substring(start, end + 1);

        List<Map<String, Object>> scenes = JSONUtil.getList(trimmed, Map.class, null);
        assertNotNull("Scene list should not be null", scenes);
        assertEquals("Should parse 2 scenes", 2, scenes.size());
        assertEquals("First scene title", "Scene 1", scenes.get(0).get("title"));
    }

    // ── Test 3: .pictureBookMeta structure ────────────────────────────────

    @Test
    public void TestPictureBookMetaStructure() {
        String workObjectId = "test-work-id-001";
        String workName = "My Test Story";

        List<Map<String, Object>> metaScenes = new ArrayList<>();
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("objectId", "scene-oid-1");
        s1.put("index", 0);
        s1.put("title", "The Duel at Dawn");
        s1.put("imageObjectId", null);
        s1.put("characters", Arrays.asList("Alice", "Bob"));
        metaScenes.add(s1);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workObjectId", workObjectId);
        meta.put("workName", workName);
        meta.put("sceneCount", metaScenes.size());
        meta.put("scenes", metaScenes);
        meta.put("extractedAt", java.time.ZonedDateTime.now().toString());
        meta.put("generatedAt", null);

        String metaJson = JSONUtil.exportObject(meta);
        assertNotNull("Meta JSON should not be null", metaJson);
        assertTrue("Meta should contain workObjectId", metaJson.contains(workObjectId));
        assertTrue("Meta should contain workName", metaJson.contains(workName));
        assertTrue("Meta should contain sceneCount", metaJson.contains("sceneCount"));
        assertTrue("Meta should contain scene title", metaJson.contains("The Duel at Dawn"));
    }

    // ── Test 4: Scene note record creation ────────────────────────────────

    @Test
    public void TestSceneNoteCreation() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
        assertNotNull("OrgContext should exist", orgContext);

        BaseRecord adminUser = orgContext.getAdminUser();
        assertNotNull("Admin user should not be null", adminUser);

        // Ensure a Scenes group
        String scenesPath = "~/PictureBookTest/Scenes";
        BaseRecord scenesGroup = ensureGroup(adminUser, scenesPath);
        assertNotNull("Scenes group should be created", scenesGroup);

        // Create a scene note
        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, scenesPath);
        plist.parameter(FieldNames.FIELD_NAME, "TestScene-" + System.currentTimeMillis());
        BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(
                ModelNames.MODEL_NOTE, adminUser, null, plist);
        Map<String, Object> sceneData = new LinkedHashMap<>();
        sceneData.put("title", "Dragon Pass");
        sceneData.put("summary", "Alice faces the dragon at the mountain pass.");
        sceneData.put("setting", "Mountain pass at dusk");
        sceneData.put("action", "Alice draws her sword");
        sceneData.put("mood", "tense, dark");
        sceneData.put("sceneIndex", 0);
        note.set("text", JSONUtil.exportObject(sceneData));

        BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(adminUser, note);
        assertNotNull("Scene note should be created", created);

        String oid = created.get(FieldNames.FIELD_OBJECT_ID);
        assertNotNull("Scene note objectId should not be null", oid);

        // Verify stored fields
        Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, oid);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, adminUser.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text"});
        BaseRecord found = IOSystem.getActiveContext().getAccessPoint().find(adminUser, q);
        assertNotNull("Scene note should be findable", found);
        String text = found.get("text");
        assertNotNull("text field should be set", text);
        assertTrue("text should contain scene title", text.contains("Dragon Pass"));
        assertTrue("text should contain scene summary", text.contains("dragon"));

        // Clean up
        IOSystem.getActiveContext().getAccessPoint().delete(adminUser, created);
    }

    // ── Test 5: Reorder scenes in meta ────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    public void TestReorderScenes() {
        List<Map<String, Object>> scenes = new ArrayList<>();
        String[] ids = {"oid-a", "oid-b", "oid-c"};
        for (int i = 0; i < ids.length; i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("objectId", ids[i]);
            s.put("index", i);
            s.put("title", "Scene " + i);
            scenes.add(s);
        }

        // Reorder: c, a, b
        List<String> newOrder = Arrays.asList("oid-c", "oid-a", "oid-b");
        List<Map<String, Object>> reordered = new ArrayList<>();
        for (int i = 0; i < newOrder.size(); i++) {
            final String oid = newOrder.get(i);
            final int newIdx = i;
            scenes.stream()
                    .filter(s -> oid.equals(s.get("objectId")))
                    .findFirst()
                    .ifPresent(s -> {
                        Map<String, Object> copy = new LinkedHashMap<>(s);
                        copy.put("index", newIdx);
                        reordered.add(copy);
                    });
        }

        assertEquals("Reordered list should have 3 entries", 3, reordered.size());
        assertEquals("First scene should be oid-c", "oid-c", reordered.get(0).get("objectId"));
        assertEquals("First scene index should be 0", 0, reordered.get(0).get("index"));
        assertEquals("Second scene should be oid-a", "oid-a", reordered.get(1).get("objectId"));
        assertEquals("Third scene should be oid-b", "oid-b", reordered.get(2).get("objectId"));
    }

    // ── Test 6: Genre theme mapping ───────────────────────────────────────

    @Test
    public void TestGenreThemeMapping() {
        Map<String, String> genreMap = new LinkedHashMap<>();
        genreMap.put("fantasy", "dark-medieval");
        genreMap.put("sci-fi", "sci-fi");
        genreMap.put("contemporary", "modern");
        genreMap.put("historical", "period");

        assertEquals("Fantasy maps to dark-medieval", "dark-medieval", genreMap.get("fantasy"));
        assertEquals("Sci-fi maps to sci-fi", "sci-fi", genreMap.get("sci-fi"));
        assertEquals("Contemporary maps to modern", "modern", genreMap.get("contemporary"));
        assertEquals("Historical maps to period", "period", genreMap.get("historical"));
        assertNull("Unknown genre maps to null", genreMap.get("romance"));
    }

    // ── Helpers for LLM / SD tests ────────────────────────────────────────

    private static Properties testProps = null;

    private Properties getTestProperties() {
        if (testProps != null) return testProps;
        testProps = new Properties();
        try (InputStream is = ClassLoader.getSystemResourceAsStream("resource.properties")) {
            if (is != null) testProps.load(is);
        } catch (Exception e) {
            logger.warn("Could not load resource.properties: " + e.getMessage());
        }
        return testProps;
    }

    private BaseRecord getOrCreateOllamaChatConfig(BaseRecord user, String name) throws FieldException, ModelNotFoundException, ValueException, FactoryException {
        Properties props = getTestProperties();
        // Check for existing config
        BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
        if (existing != null) return existing;

        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
        plist.parameter(FieldNames.FIELD_NAME, name);
        BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
        cfg.set("serviceType", org.cote.accountmanager.olio.llm.LLMServiceEnumType.OLLAMA);
        cfg.set("serverUrl", props.getProperty("test.llm.ollama.server", "http://192.168.1.42:11434"));
        cfg.set("model", props.getProperty("test.llm.ollama.model", "qwen3-coder:30b"));
        return IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
    }

    // ── Test 7: LLM scene extraction via Chat ─────────────────────────────

    @Test
    public void TestLlmSceneExtraction() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
        assertNotNull("OrgContext should exist", orgContext);

        BaseRecord adminUser = orgContext.getAdminUser();
        assertNotNull("Admin user should not be null", adminUser);

        // Create or retrieve a chat config pointing at the local Ollama server
        BaseRecord chatConfig = getOrCreateOllamaChatConfig(adminUser, "pictureBookTestConfig");
        assertNotNull("Chat config should be created", chatConfig);

        // Load the extract-scenes prompt template
        String system = PromptResourceUtil.getString("pictureBook.extract-scenes", "system");
        String userTpl = PromptResourceUtil.getString("pictureBook.extract-scenes", "user");
        assertNotNull("System prompt should load", system);
        assertNotNull("User prompt template should load", userTpl);

        // Short story excerpt used as the work text
        String storyText = "Elena stepped onto the rain-slicked cobblestones of the old quarter, " +
                "her lantern casting a trembling circle of gold against the fog. A shape moved in the alley — " +
                "the hooded figure she had tracked for three nights. She drew her blade, knowing that tonight " +
                "the chase would end one way or another. Later, at the tower's summit, she finally unmasked him: " +
                "her own brother, eyes hollow, the cursed medallion burning at his throat.";

        String userMsg = userTpl
                .replace("{count}", "2")
                .replace("{text}", storyText);

        // Call the LLM
        Chat chat = new Chat(adminUser, chatConfig, null);
        OpenAIRequest req = chat.newRequest(chat.getModel());
        req.setStream(false);
        chat.newMessage(req, system, Chat.systemRole);
        chat.newMessage(req, userMsg);
        OpenAIResponse resp = chat.chat(req);

        assertNotNull("LLM response should not be null", resp);
        assertNotNull("LLM message should not be null", resp.getMessage());
        String content = resp.getMessage().getContent();
        assertNotNull("LLM content should not be null", content);
        assertFalse("LLM content should not be empty", content.isBlank());

        // Strip fences and parse the JSON array
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl >= 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        assertTrue("Response should contain a JSON array", start >= 0 && end > start);
        trimmed = trimmed.substring(start, end + 1);

        List<Map<String, Object>> scenes = JSONUtil.getList(trimmed, Map.class, null);
        assertNotNull("Parsed scene list should not be null", scenes);
        assertFalse("Scene list should not be empty", scenes.isEmpty());
        assertNotNull("First scene should have a title", scenes.get(0).get("title"));
        logger.info("LLM extracted " + scenes.size() + " scenes: " + scenes.get(0).get("title"));
    }

    // ── Test 8: SD image generation via SDUtil ────────────────────────────

    @Test
    public void TestSdImageGeneration() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
        assertNotNull("OrgContext should exist", orgContext);

        Properties props = getTestProperties();
        String swarmServer = props.getProperty("test.swarm.server", "http://192.168.1.42:7801");
        String swarmModel  = props.getProperty("test.swarm.model", "sdXL_v10VAEFix.safetensors");

        BaseRecord adminUser = orgContext.getAdminUser();
        assertNotNull("Admin user should not be null", adminUser);

        // Ensure a group to store the test image
        String imgPath = "~/PictureBookTest/Images";
        BaseRecord imgGroup = ensureGroup(adminUser, imgPath);
        assertNotNull("Image group should be created", imgGroup);

        // Build a minimal sdConfig record in-memory (olio.sd.config is not persisted)
        BaseRecord sdConfig = SDUtil.randomSDConfig();
        sdConfig.setValue("model", swarmModel);
        sdConfig.setValue("steps", 15);
        sdConfig.setValue("cfg", 5);
        sdConfig.setValue("hires", false);
        sdConfig.setValue("style", "illustration");
        sdConfig.setValue("description", "A lone swordswoman stands on rain-slicked cobblestones at night, lantern glow, fog, cinematic");
        sdConfig.setValue("negativePrompt", "blurry, lowres, bad anatomy, watermark");

        // Generate the image
        SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
        String imageName = "test_scene_" + System.currentTimeMillis();
        List<BaseRecord> images = sdu.createImage(adminUser, imgPath, sdConfig, imageName, 1, false, -1);

        assertNotNull("Image list should not be null", images);
        assertFalse("Image list should not be empty", images.isEmpty());
        BaseRecord image = images.get(0);
        assertNotNull("Generated image record should not be null", image);
        String imgOid = image.get(FieldNames.FIELD_OBJECT_ID);
        assertNotNull("Image objectId should not be null", imgOid);
        logger.info("Generated SD image: " + imgOid + " name=" + image.get(FieldNames.FIELD_NAME));

        // Write image to disk for review, then clean up the DB record
        byte[] imgBytes = image.get(FieldNames.FIELD_BYTE_STORE);
        if (imgBytes != null && imgBytes.length > 0) {
            String outPath = "./target/test-images/" + image.get(FieldNames.FIELD_NAME) + ".png";
            org.cote.accountmanager.util.FileUtil.emitFile(outPath, imgBytes);
            logger.info("Image written to: " + outPath);
        }
        IOSystem.getActiveContext().getAccessPoint().delete(adminUser, image);
    }

    // ── Test 9: LLM scene-image prompt generation ─────────────────────────

    @Test
    public void TestLlmSceneImagePrompt() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
        assertNotNull("OrgContext should exist", orgContext);

        BaseRecord adminUser = orgContext.getAdminUser();
        assertNotNull("Admin user should not be null", adminUser);

        BaseRecord chatConfig = getOrCreateOllamaChatConfig(adminUser, "pictureBookTestConfig");
        assertNotNull("Chat config should be created", chatConfig);

        String system = PromptResourceUtil.getString("pictureBook.scene-image-prompt", "system");
        String userTpl = PromptResourceUtil.getString("pictureBook.scene-image-prompt", "user");
        assertNotNull("System prompt should load", system);
        assertNotNull("User template should load", userTpl);

        String userMsg = userTpl
                .replace("{style}", "illustration")
                .replace("{title}", "The Unmasking")
                .replace("{setting}", "Tower summit at night, moonlit, windy")
                .replace("{action}", "Elena unmasks her cursed brother")
                .replace("{mood}", "tense, shocked, sorrowful")
                .replace("{characters}", "Elena: determined swordswoman; Marcus: cursed man with hollow eyes");

        Chat chat = new Chat(adminUser, chatConfig, null);
        OpenAIRequest req = chat.newRequest(chat.getModel());
        req.setStream(false);
        chat.newMessage(req, system, Chat.systemRole);
        chat.newMessage(req, userMsg);
        OpenAIResponse resp = chat.chat(req);

        assertNotNull("LLM response should not be null", resp);
        String sdPrompt = resp.getMessage() != null ? resp.getMessage().getContent() : null;
        assertNotNull("SD prompt content should not be null", sdPrompt);
        assertFalse("SD prompt should not be empty", sdPrompt.isBlank());
        logger.info("Generated SD prompt (" + sdPrompt.length() + " chars): " + sdPrompt.substring(0, Math.min(120, sdPrompt.length())));
    }
}
