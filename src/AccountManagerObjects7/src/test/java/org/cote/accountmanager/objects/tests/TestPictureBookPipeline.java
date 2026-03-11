package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/**
 * Full picture-book image pipeline test using the CardFox story.
 *
 * Pipeline:
 *   Phase 1 — Extraction
 *     1a. LLM extracts all characters (name, physical, clothing)
 *     1b. LLM extracts all key scenes (setting, action, mood, characters)
 *
 *   Phase 2 — Character portraits (one per unique character)
 *     For each character:
 *       - LLM generates SD portrait prompt from character JSON
 *       - SDXL generates portrait image
 *
 *   Phase 3 — Per-scene image generation (first MAX_SCENES scenes)
 *     For each scene:
 *       - LLM generates landscape SD prompt from scene setting/mood
 *       - SDXL generates landscape image
 *       - Stitch reference composite [char1 | char2 | landscape]
 *       - Flux Kontext generates final scene image
 *
 * Output in target/test-images/cardfox/:
 *   characters.json                  LLM-extracted character list
 *   scenes.json                      LLM-extracted scene list
 *   {name}_character.txt             Per-character extracted JSON
 *   {name}_portrait_prompt.txt       Per-character SD portrait prompt
 *   {name}_portrait.png              Per-character SDXL portrait
 *   scene_{n}_extract.txt            Per-scene extracted JSON
 *   scene_{n}_landscape_prompt.txt   Per-scene landscape prompt
 *   scene_{n}_landscape.png          Per-scene SDXL landscape
 *   scene_{n}_reference.png          Per-scene stitched reference composite
 *   scene_{n}_composite_prompt.txt   Per-scene Flux Kontext prompt
 *   scene_{n}_composite.png          Per-scene Flux Kontext final image
 *   README.txt
 */
public class TestPictureBookPipeline extends BaseTest {

    private static final String OUT_DIR    = "./target/test-images/cardfox/";
    private static final String GROUP_PATH = "~/PictureBookTest/CardFox";
    private static final String NEG_PROMPT =
            "blurry, lowres, bad anatomy, extra limbs, watermark, text, logo, cartoon, anime, " +
            "nsfw, deformed, disfigured, ugly, duplicate, mutated, out of frame";

    /** Maximum number of scenes to generate images for. */
    private static final int MAX_SCENES = 3;

    // ── CardFox story ──────────────────────────────────────────────────────

    private static final String CARDFOX_STORY =
        "The CardFox Tournament\n\n" +
        "The great hall of Ravenwick Academy had been transformed overnight into an arena of card " +
        "magic. Kateri Redfox moved between the tables with practiced grace, her long black " +
        "hair swept back in a single braid, the turquoise beads at her wrist catching the " +
        "candlelight. At twenty-two she was the youngest dealer the tournament had ever licensed, " +
        "her dark brown eyes reading each player's tell before a single card was laid. She wore the " +
        "dealer's uniform — a fitted crimson vest over a white button-front shirt, black slacks, and " +
        "polished shoes — though the small silver fox pendant at her throat was her own, inherited " +
        "from her grandmother. Her skin was a warm medium brown, her build athletic and spare. " +
        "She was Native American, from a long line of traders.\n\n" +
        "At the far table, Mark Ashford studied his hand with the deliberate calm that had won him " +
        "three championships. He was thirty-one, the dark brown hair he'd had at his first " +
        "tournament now showing the first hints of grey at the temples. His blue eyes moved from " +
        "card to card with quiet precision. Medium build, broad shoulders kept easy, a dark charcoal " +
        "jacket over a slate-grey shirt, a silver tournament badge pinned to the lapel. He carried " +
        "himself like a man who had learned patience from the cards themselves.\n\n" +
        "When their eyes met across the candlelit room, the torchlight flickered. The CardFox — " +
        "the ancient spirit said to live inside the academy's oldest deck — was watching. Kateri set " +
        "down her dealing wand and walked toward his table. Mark turned over his final card: the Fox " +
        "of Embers. The carved fox statue in the glass cabinet behind the dealer's podium caught the " +
        "amber light and seemed, just for a moment, to smile.";

    // ── Main test ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    public void TestCardFoxFullPipeline() throws Exception {
        assertNotNull("IOSystem should be active", IOSystem.getActiveContext());
        assertNotNull("OrgContext should exist", orgContext);

        BaseRecord adminUser = orgContext.getAdminUser();
        assertNotNull("Admin user should not be null", adminUser);

        new File(OUT_DIR).mkdirs();

        BaseRecord chatConfig = OlioTestUtil.getChatConfig(
                adminUser, LLMServiceEnumType.OLLAMA, "pictureBookPipelineConfig", testProperties);
        assertNotNull("Chat config should not be null", chatConfig);

        String swarmServer = testProperties.getProperty("test.swarm.server", "http://192.168.1.42:7801");
        String swarmModel  = testProperties.getProperty("test.swarm.model",  "sdXL_v10VAEFix.safetensors");
        SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);

        ensureGroup(adminUser, GROUP_PATH);

        // ── Phase 1a: Extract character list ──────────────────────────────

        logger.info("=== Phase 1a: Extracting character list ===");
        String charListRaw = extractList(adminUser, chatConfig, "pictureBook.extract-character-list", CARDFOX_STORY);
        assertNotNull("Character list should not be null", charListRaw);
        FileUtil.emitFile(OUT_DIR + "characters.json", charListRaw);

        // List extraction gives names only; full details come from the single-character prompt per name
        List<Map<String, Object>> nameList = JSONUtil.getList(charListRaw, Map.class, null);
        assertNotNull("Character name list parse should not be null", nameList);
        assertFalse("Character name list should not be empty", nameList.isEmpty());
        logger.info("Extracted " + nameList.size() + " character name(s)");

        // Build full character data using the proven single-character extraction prompt
        List<Map<String, Object>> characterList = new ArrayList<>();
        for (Map<String, Object> entry : nameList) {
            String charName = str(entry, "name");
            if (charName.isEmpty()) continue;
            String charJson = extractCharacter(adminUser, chatConfig, charName, CARDFOX_STORY);
            if (charJson == null) continue;
            Map<String, Object> charData = JSONUtil.getMap(charJson.getBytes(), String.class, Object.class);
            if (charData == null) charData = new java.util.HashMap<>();
            if (!charData.containsKey("name")) charData.put("name", charName);
            characterList.add(charData);
        }

        // ── Phase 1b: Extract scene list ──────────────────────────────────

        logger.info("=== Phase 1b: Extracting scene list ===");
        String sceneListRaw = extractList(adminUser, chatConfig, "pictureBook.extract-scene-list", CARDFOX_STORY);
        assertNotNull("Scene list should not be null", sceneListRaw);
        FileUtil.emitFile(OUT_DIR + "scenes.json", sceneListRaw);

        List<Map<String, Object>> sceneList = JSONUtil.getList(sceneListRaw, Map.class, null);
        assertNotNull("Scene list parse should not be null", sceneList);
        assertFalse("Scene list should not be empty", sceneList.isEmpty());
        logger.info("Extracted " + sceneList.size() + " scene(s)");

        // Print scene list summary
        for (int i = 0; i < sceneList.size(); i++) {
            Map<String, Object> sc = sceneList.get(i);
            logger.info("  Scene " + (i + 1) + ": " + str(sc, "title") + " — " + preview(str(sc, "setting")));
        }

        // ── Phase 2: Generate character portraits ─────────────────────────

        logger.info("=== Phase 2: Generating character portraits ===");

        // portraitBytes keyed by lowercase character name for scene lookup
        Map<String, byte[]> portraitBytesMap = new LinkedHashMap<>();
        // DB records to clean up
        List<BaseRecord> toDelete = new ArrayList<>();

        for (Map<String, Object> charData : characterList) {
            String name = str(charData, "name");
            if (name.isEmpty()) continue;
            String key = name.toLowerCase();
            String filePrefix = OUT_DIR + key + "_";

            logger.info("--- Character: " + name + " ---");
            String charJson = JSONUtil.exportObject(charData);
            FileUtil.emitFile(filePrefix + "character.txt", charJson);

            // Portrait SD prompt
            String portraitPrompt = buildSDCharacterPrompt(name, charData);
            if (portraitPrompt == null || portraitPrompt.isBlank()) {
                logger.warn("Portrait prompt generation failed for " + name + " — skipping");
                continue;
            }
            FileUtil.emitFile(filePrefix + "portrait_prompt.txt", portraitPrompt);
            logger.info(name + " portrait prompt: " + preview(portraitPrompt));

            // Portrait image
            BaseRecord img = generatePortraitImage(sdu, adminUser, swarmModel,
                    portraitPrompt, key + "_portrait_" + System.currentTimeMillis());
            if (img == null) {
                logger.warn("Portrait image generation failed for " + name + " — skipping");
                continue;
            }
            byte[] imgBytes = img.get(FieldNames.FIELD_BYTE_STORE);
            assertNotNull(name + " portrait bytes should not be null", imgBytes);
            FileUtil.emitFile(filePrefix + "portrait.png", imgBytes);
            logger.info(name + " portrait saved: " + imgBytes.length + " bytes");

            portraitBytesMap.put(key, imgBytes);
            toDelete.add(img);
        }

        assertTrue("At least one portrait should have been generated", !portraitBytesMap.isEmpty());

        // ── Phase 3: Per-scene image generation ───────────────────────────

        logger.info("=== Phase 3: Generating images for first " + MAX_SCENES + " scene(s) ===");

        int scenesToProcess = Math.min(MAX_SCENES, sceneList.size());
        for (int si = 0; si < scenesToProcess; si++) {
            Map<String, Object> scene = sceneList.get(si);
            String sceneTitle   = str(scene, "title");
            String sceneSetting = str(scene, "setting");
            String sceneAction  = str(scene, "action");
            String sceneMood    = str(scene, "mood");
            List<String> sceneCharNames = (List<String>) scene.get("characters");

            String prefix = OUT_DIR + "scene_" + (si + 1) + "_";
            logger.info("--- Scene " + (si + 1) + ": " + sceneTitle + " ---");

            FileUtil.emitFile(prefix + "extract.txt", JSONUtil.exportObject(scene));

            // Landscape prompt from story-extracted setting
            String landscapePrompt = buildLandscapePrompt(adminUser, chatConfig,
                    sceneSetting, sceneMood, "evening", "illustration");
            if (landscapePrompt == null || landscapePrompt.isBlank()) {
                logger.warn("Landscape prompt failed for scene " + (si + 1) + " — skipping");
                continue;
            }
            FileUtil.emitFile(prefix + "landscape_prompt.txt", landscapePrompt);
            logger.info("Landscape prompt: " + preview(landscapePrompt));

            // Landscape image
            SWTxt2Img landReq = SWUtil.newSceneTxt2Img(landscapePrompt, NEG_PROMPT, null);
            landReq.setModel(swarmModel);
            landReq.setWidth(1024);
            landReq.setHeight(768);
            landReq.setSteps(25);
            List<BaseRecord> landImages = sdu.createSceneImage(adminUser, GROUP_PATH,
                    "scene" + (si + 1) + "_land_" + System.currentTimeMillis(), landReq, null, null);
            if (landImages == null || landImages.isEmpty()) {
                logger.warn("Landscape generation failed for scene " + (si + 1) + " — skipping");
                continue;
            }
            byte[] landscapeBytes = landImages.get(0).get(FieldNames.FIELD_BYTE_STORE);
            assertNotNull("Scene " + (si + 1) + " landscape bytes should not be null", landscapeBytes);
            FileUtil.emitFile(prefix + "landscape.png", landscapeBytes);
            logger.info("Scene " + (si + 1) + " landscape: " + landscapeBytes.length + " bytes");
            toDelete.add(landImages.get(0));

            // Find up to 2 portrait byte arrays for characters in this scene
            byte[] leftBytes  = null;
            byte[] rightBytes = null;
            String leftDesc   = null;
            String rightDesc  = null;

            if (sceneCharNames != null) {
                for (String cname : sceneCharNames) {
                    byte[] pb = portraitBytesMap.get(cname.toLowerCase());
                    if (pb == null) continue;
                    String pd = readFile(OUT_DIR + cname.toLowerCase() + "_portrait_prompt.txt");
                    if (leftBytes == null) {
                        leftBytes = pb; leftDesc = pd;
                    } else if (rightBytes == null) {
                        rightBytes = pb; rightDesc = pd;
                        break;
                    }
                }
            }

            // Fall back to first two available portraits if scene characters have none
            if (leftBytes == null) {
                List<byte[]> all = new ArrayList<>(portraitBytesMap.values());
                if (!all.isEmpty()) leftBytes = all.get(0);
                if (all.size() > 1) rightBytes = all.get(1);
            }

            if (leftBytes == null) {
                logger.warn("No portrait bytes available for scene " + (si + 1) + " — skipping composite");
                continue;
            }

            // Stitch reference composite: [left portrait | right portrait | landscape]
            // Use landscape twice if only one character is present
            byte[] stitchRight = rightBytes != null ? rightBytes : landscapeBytes;
            byte[] refComposite = SDUtil.stitchSceneImages(leftBytes, stitchRight, landscapeBytes, 1024);
            assertNotNull("Scene " + (si + 1) + " reference composite should not be null", refComposite);
            FileUtil.emitFile(prefix + "reference.png", refComposite);
            logger.info("Scene " + (si + 1) + " reference composite: " + refComposite.length + " bytes");

            // Flux Kontext scene prompt
            String cleanLeft  = SWUtil.stripSDXLWeighting(leftDesc  != null ? leftDesc  : "");
            String cleanRight = SWUtil.stripSDXLWeighting(rightDesc != null ? rightDesc : "");
            SWTxt2Img kontextReq = SWUtil.newKontextSceneTxt2Img(
                    cleanLeft, cleanRight, sceneAction, sceneSetting, null);

            List<String> promptImages = new ArrayList<>();
            promptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(refComposite));
            kontextReq.setPromptImages(promptImages);

            FileUtil.emitFile(prefix + "composite_prompt.txt", kontextReq.getPrompt());
            logger.info("Scene " + (si + 1) + " Kontext prompt: " + preview(kontextReq.getPrompt()));

            // Flux Kontext composite
            List<BaseRecord> compositeImages = sdu.createSceneImage(adminUser, GROUP_PATH,
                    "scene" + (si + 1) + "_composite_" + System.currentTimeMillis(), kontextReq, null, null);
            if (compositeImages == null || compositeImages.isEmpty()) {
                logger.warn("Kontext composite failed for scene " + (si + 1));
                continue;
            }
            byte[] compositeBytes = compositeImages.get(0).get(FieldNames.FIELD_BYTE_STORE);
            assertNotNull("Scene " + (si + 1) + " composite bytes should not be null", compositeBytes);
            FileUtil.emitFile(prefix + "composite.png", compositeBytes);
            logger.info("Scene " + (si + 1) + " composite saved: " + compositeBytes.length + " bytes");
            toDelete.add(compositeImages.get(0));
        }

        // ── Manifest ──────────────────────────────────────────────────────

        StringBuilder manifest = new StringBuilder();
        manifest.append("CardFox Pipeline — ").append(new java.util.Date()).append("\n");
        manifest.append("Output: ").append(new File(OUT_DIR).getAbsolutePath()).append("\n\n");
        manifest.append("== Extracted Data ==\n");
        manifest.append("  characters.json              All characters extracted from story\n");
        manifest.append("  scenes.json                  All key scenes extracted from story\n\n");
        manifest.append("== Per-Character ==\n");
        manifest.append("  {name}_character.txt         Character JSON\n");
        manifest.append("  {name}_portrait_prompt.txt   SD portrait prompt\n");
        manifest.append("  {name}_portrait.png          SDXL portrait image\n\n");
        manifest.append("== Per-Scene (first ").append(MAX_SCENES).append(") ==\n");
        manifest.append("  scene_N_extract.txt          Scene JSON (setting/action/mood/characters)\n");
        manifest.append("  scene_N_landscape_prompt.txt Landscape SD prompt (from story scene)\n");
        manifest.append("  scene_N_landscape.png        SDXL landscape\n");
        manifest.append("  scene_N_reference.png        Stitched reference sent to Flux Kontext\n");
        manifest.append("  scene_N_composite_prompt.txt Flux Kontext scene prompt\n");
        manifest.append("  scene_N_composite.png        Flux Kontext final scene image\n\n");
        manifest.append("== Scene List ==\n");
        for (int i = 0; i < sceneList.size(); i++) {
            manifest.append("  ").append(i + 1).append(". ").append(str(sceneList.get(i), "title")).append("\n");
        }
        FileUtil.emitFile(OUT_DIR + "README.txt", manifest.toString());
        logger.info("=== Pipeline complete. Files at: " + new File(OUT_DIR).getAbsolutePath() + " ===");

        // ── Cleanup DB records ────────────────────────────────────────────

        for (BaseRecord rec : toDelete) {
            try {
                IOSystem.getActiveContext().getAccessPoint().delete(adminUser, rec);
            } catch (Exception e) {
                logger.warn("Cleanup failed for " + rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
            }
        }
    }

    // ── LLM helpers ────────────────────────────────────────────────────────

    private String callLlm(BaseRecord user, BaseRecord chatConfig, String system, String userMsg) {
        try {
            Chat chat = new Chat(user, chatConfig, null);
            OpenAIRequest req = chat.newRequest(chat.getModel());
            req.setStream(false);
            chat.newMessage(req, system, Chat.systemRole);
            chat.newMessage(req, userMsg);
            OpenAIResponse resp = chat.chat(req);
            if (resp != null && resp.getMessage() != null) {
                return resp.getMessage().getContent();
            }
        } catch (Exception e) {
            logger.error("LLM call failed: " + e.getMessage());
        }
        return null;
    }

    private String extractCharacter(BaseRecord user, BaseRecord chatConfig, String name, String story) {
        String system  = PromptResourceUtil.getString("pictureBook.extract-character", "system");
        String userTpl = PromptResourceUtil.getString("pictureBook.extract-character", "user");
        assertNotNull("extract-character system prompt", system);
        assertNotNull("extract-character user template", userTpl);
        String raw = callLlm(user, chatConfig, system, userTpl.replace("{name}", name).replace("{text}", story));
        return extractJsonObject(raw);
    }

    /** Extract a JSON array from the story using the given prompt template name. */
    private String extractList(BaseRecord user, BaseRecord chatConfig, String promptName, String story) {
        String system  = PromptResourceUtil.getString(promptName, "system");
        String userTpl = PromptResourceUtil.getString(promptName, "user");
        assertNotNull(promptName + " system prompt", system);
        assertNotNull(promptName + " user template", userTpl);
        String raw = callLlm(user, chatConfig, system, userTpl.replace("{text}", story));
        return extractJsonArray(raw);
    }

    private String extractJsonArray(String raw) {
        if (raw == null) return null;
        String t = stripCodeFence(raw);
        int start = t.indexOf('['); int end = t.lastIndexOf(']');
        return (start >= 0 && end > start) ? t.substring(start, end + 1) : t;
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String t = stripCodeFence(raw);
        int start = t.indexOf('{'); int end = t.lastIndexOf('}');
        return (start >= 0 && end > start) ? t.substring(start, end + 1) : t;
    }

    private static String stripCodeFence(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.lastIndexOf("```")).trim();
        }
        return t;
    }

    /**
     * Build an SD portrait prompt in NarrativeUtil format directly from extracted character JSON.
     * Mirrors NarrativeUtil.getSDPrompt / getSDMinPrompt weighting syntax without requiring
     * a full olio.charPerson record.  Outfit is triple-weighted to guarantee it is included —
     * a missing outfit would otherwise produce a nude prompt.
     */
    @SuppressWarnings("unchecked")
    private String buildSDCharacterPrompt(String name, Map<String, Object> charData) {
        if (charData == null) return null;

        Map<String, Object> phys = (Map<String, Object>) charData.get("physical");

        String gender      = str(charData, "gender").toLowerCase();
        String ageApprox   = str(charData, "age_approx");   // e.g. "22", "twenty-two", "early twenties"
        String build       = phys != null ? str(phys, "build") : "";
        String hair        = phys != null ? str(phys, "hair")  : "";
        String eyes        = phys != null ? str(phys, "eyes")  : "";
        String skin        = phys != null ? str(phys, "skin")  : "";
        String outfitNotes = str(charData, "outfit_notes");

        boolean isMale = gender.contains("male") && !gender.contains("female");
        String pronoun = isMale ? "He" : "She";
        String label   = isMale ? "man" : "woman";

        // Outfit guard: if extraction returned nothing, use a safe generic fallback
        if (outfitNotes == null || outfitNotes.isBlank()) {
            outfitNotes = "fully clothed in appropriate attire";
            logger.warn("No outfit_notes for " + name + " — using fallback to avoid nude prompt");
        }

        StringBuilder min = new StringBuilder();

        // "a [build] ((age:1.5) (skin) (woman/man)) with ((hair)) and (eyes). She is (((outfit)))."
        min.append("a");
        if (!build.isBlank()) min.append(" (").append(build).append(")");
        min.append(" ((");
        if (!ageApprox.isBlank()) min.append(ageApprox).append(":1.5) (");
        if (!skin.isBlank())     min.append(skin.toLowerCase()).append(") (");
        min.append(label).append("))");
        if (!hair.isBlank())  min.append(" with ((").append(hair).append("))");
        if (!eyes.isBlank())  min.append(" and (").append(eyes).append(" eyes)");
        min.append(". ").append(pronoun).append(" is (((").append(outfitNotes).append("))).");

        // Full prompt wrapper matching NarrativeUtil.getSDPrompt leading segment
        return "8k highly detailed ((highest quality)) ((ultra realistic)) ((professional portrait)) of "
                + min;
    }

    private String buildLandscapePrompt(BaseRecord user, BaseRecord chatConfig,
            String setting, String mood, String time, String style) {
        String system  = PromptResourceUtil.getString("pictureBook.landscape-prompt", "system");
        String userTpl = PromptResourceUtil.getString("pictureBook.landscape-prompt", "user");
        assertNotNull("landscape-prompt system", system);
        assertNotNull("landscape-prompt user template", userTpl);
        return callLlm(user, chatConfig, system, userTpl
                .replace("{setting}", setting != null ? setting : "")
                .replace("{mood}",    mood    != null ? mood    : "")
                .replace("{time}",    time    != null ? time    : "")
                .replace("{style}",   style   != null ? style   : ""));
    }

    // ── SD helpers ─────────────────────────────────────────────────────────

    private BaseRecord generatePortraitImage(SDUtil sdu, BaseRecord user, String model,
            String prompt, String name) {
        BaseRecord sdConfig = SDUtil.randomSDConfig();
        sdConfig.setValue("model",          model);
        sdConfig.setValue("steps",          25);
        sdConfig.setValue("cfg",            5);
        sdConfig.setValue("hires",          false);
        sdConfig.setValue("description",    prompt);
        sdConfig.setValue("negativePrompt", NEG_PROMPT);

        List<BaseRecord> images = sdu.createImage(user, GROUP_PATH, sdConfig, name, 1, false, -1);
        if (images == null || images.isEmpty()) return null;
        return images.get(0);
    }

    private BaseRecord ensureGroup(BaseRecord user, String path) {
        return IOSystem.getActiveContext().getPathUtil().makePath(
                user, ModelNames.MODEL_GROUP, path,
                GroupEnumType.DATA.toString(),
                ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
    }

    // ── Misc helpers ───────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String key) {
        if (m == null) return "";
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    private static String preview(String s) {
        if (s == null) return "(null)";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static String readFile(String path) {
        byte[] b = FileUtil.getFile(path);
        return b != null ? new String(b) : null;
    }
}
