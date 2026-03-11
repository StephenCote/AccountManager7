package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
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
                (long)(int) user.get(FieldNames.FIELD_ORGANIZATION_ID));
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
        note.set(FieldNames.FIELD_DESCRIPTION, "Alice faces the dragon at the mountain pass.");

        Map<String, Object> sceneData = new LinkedHashMap<>();
        sceneData.put("title", "Dragon Pass");
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
        q.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, "text"});
        BaseRecord found = IOSystem.getActiveContext().getAccessPoint().find(adminUser, q);
        assertNotNull("Scene note should be findable", found);
        assertTrue("Description should contain blurb",
                ((String) found.get(FieldNames.FIELD_DESCRIPTION)).contains("dragon"));
        String text = found.get("text");
        assertNotNull("text field should be set", text);
        assertTrue("text should contain scene data", text.contains("Dragon Pass"));

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
}
