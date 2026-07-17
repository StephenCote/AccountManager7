package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;

/**
 * PictureBookUtil — Objects7 home for the PictureBook (illustrated picture book generation)
 * business logic, moved out of Service7's {@code PictureBookService} (see
 * .claude/rules/architecture.md — "no business logic in Service7"). Mirrors the
 * {@code GroupExportUtil} (Objects7) / {@code GroupExportService} (Service7) split: this class
 * is a plain static utility driven entirely through {@code AccessPoint} (already PBAC-wrapped),
 * takes an explicit {@code contextUser}/{@code BaseRecord} on every call, and has no
 * HttpServletRequest/ServletContext dependency of any kind — so it can be exercised directly
 * from an Objects7-tree JUnit test with zero request/servlet mocking.
 *
 * <p>Failures that used to short-circuit with a specific {@code Response.status(code)} are
 * signalled here via {@link PictureBookException} (status + message) so the thin REST layer can
 * reproduce the exact same HTTP response shape it built inline before the move. Progress
 * notifications ("Generating portraits...", etc.) that used to go straight to
 * {@code WebSocketService.chirpUser} now go through {@link PictureBookProgressNotifier}, which
 * Service7 subscribes to — see that class's javadoc.
 *
 * <p>The SD backend address ({@code sd.server}/{@code sd.server.apiType}) is passed in as plain
 * strings by the caller rather than resolved here. In the original Service7 implementation these
 * came from the servlet {@code ServletContext} init-params; that resolution is inherently a
 * transport/deployment concern (like the web.xml-configured DB connection) and stays in
 * Service7. Passing plain strings also means a test can supply them exactly the way
 * {@code TestPictureBookPipeline} already does (real {@code test.swarm.server} config value, no
 * ServletContext proxy/mock of any kind).
 */
public class PictureBookUtil {

    private static final Logger logger = LogManager.getLogger(PictureBookUtil.class);

    // SD generation defaults — enforced unless pictureBook.hq feature flag is true
    public static final int DEFAULT_STEPS = 20;
    public static final int DEFAULT_REFINER_STEPS = 20;
    public static final int DEFAULT_CFG = 5;
    public static final boolean DEFAULT_HIRES = false;

    // Default scene count when not specified — LLM decides actual count
    public static final int MAX_SCENES_DEFAULT = 10;

    public static final String NEG_PROMPT =
        "blurry, lowres, bad anatomy, extra limbs, watermark, text, logo, cartoon, anime, nsfw, deformed, disfigured, ugly, duplicate, mutated, out of frame";

    // Allowed style values — mirrors configModel.json's style `limit`. Client-supplied style is
    // clamped to this set so no arbitrary text flows into LLM/FLUX prompt vars.
    public static final Set<String> ALLOWED_STYLES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "art", "movie", "photograph", "selfie", "anime", "portrait", "comic", "digitalArt", "fashion", "vintage", "custom", "illustration")));

    // Genre → SD theme mapping
    private static final Map<String, String> GENRE_THEME_MAP = new HashMap<>();
    static {
        GENRE_THEME_MAP.put("fantasy", "dark-medieval");
        GENRE_THEME_MAP.put("sci-fi", "sci-fi");
        GENRE_THEME_MAP.put("contemporary", "modern");
        GENRE_THEME_MAP.put("historical", "period");
    }

    private static final String PICTURE_BOOKS_DIR = "PictureBooks";

    private PictureBookUtil() {
    }

    // ----- Public parameter/result holders --------------------------------

    /** Result of {@link #extractScenesOnly}: either a raw scene list, or a chunked extraction summary. */
    public static final class ScenesOnlyResult {
        public final List<Map<String, Object>> scenes;
        public final boolean chunked;

        public ScenesOnlyResult(List<Map<String, Object>> scenes, boolean chunked) {
            this.scenes = scenes;
            this.chunked = chunked;
        }
    }

    /** Parsed request parameters for {@link #generateSceneImage} — mirrors the JSON body's {@code sdConfig}. */
    public static final class SceneGenerationParams {
        public String chatConfigName;
        public String promptOverride;
        public String promptTemplateOverride;
        public int steps = DEFAULT_STEPS;
        public int refinerSteps = DEFAULT_REFINER_STEPS;
        public int cfg = DEFAULT_CFG;
        public boolean hires = DEFAULT_HIRES;
        public int seed = -1;
        public String sdModelName;
        public String sdRefinerModelName;
        public double denoisingStrength = -1;
        public String sdSampler;
        public String sdScheduler;
        public String sdRefinerSampler;
        public String sdRefinerScheduler;
        public List<String> sdLoras;
        // Default to "illustration" to preserve prior behavior when sdConfig omits style
        public String style = "illustration";
        // Explicit book/fallback flag — defaults to true (all current picture-book scenes are
        // created under .../Scenes/); the client may pass isBook:false for the legacy ~/Chat
        // fallback that should not persist/reuse portraits.
        public Boolean isBookOverride;
        // Mirrors ChatService.generateScene's sdConfig.useKontext/sceneCreativity flags in shape
        // (Kontext stitch-and-prompt vs. classic Graphics2D+SDXL-img2img), but PictureBook's
        // null/unset default is FALSE (classic) — the opposite of ChatService's true default. See
        // generateSceneImage()'s Stage 3/4 comment for why: a live E2E visual comparison showed
        // Kontext does not reliably preserve character likeness even when it "succeeds" (returns a
        // valid image), so the classic pipeline's real-pixel compositing is the safer default here.
        // sceneCreativity is resolved from useKontext (0.65 Kontext / 0.85 classic) unless
        // explicitly overridden.
        public Boolean useKontext;
        public Double sceneCreativity;
    }

    // ----- Helpers -------------------------------------------------------

    /**
     * Resolve the work record (source document) from its objectId.
     */
    public static BaseRecord findWork(BaseRecord user, String workObjectId) {
        Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, workObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        BaseRecord found = IOSystem.getActiveContext().getAccessPoint().find(user, q);
        if (found == null) {
            // Also try data.note
            q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, workObjectId);
            q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
            q.planMost(true);
            found = IOSystem.getActiveContext().getAccessPoint().find(user, q);
        }
        return found;
    }

    /**
     * Find or create a named book group under ~/PictureBooks/{bookName}/.
     */
    private static BaseRecord ensureBookGroup(BaseRecord user, String bookName) {
        long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
        String bookPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + bookName;
        BaseRecord grp = IOSystem.getActiveContext().getPathUtil().makePath(user,
                ModelNames.MODEL_GROUP, bookPath, GroupEnumType.DATA.toString(), orgId);
        if (grp != null) {
            try { grp.set(FieldNames.FIELD_PATH, bookPath); } catch (Exception e) { /* already set */ }
        }
        return grp;
    }

    /**
     * Find a book group by its objectId (auth.group).
     */
    public static BaseRecord findBookGroup(BaseRecord user, String bookGroupObjectId) {
        Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, bookGroupObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        return IOSystem.getActiveContext().getAccessPoint().find(user, q);
    }

    /**
     * Extract text from a work record. Uses DocumentUtil.getStringContent for PDF/DOCX/text,
     * falling back to description/text fields for plain records.
     */
    private static String extractWorkText(BaseRecord user, BaseRecord work) {
        if (work == null) return null;

        // Try DocumentUtil.getStringContent — handles PDF, DOCX, and text/* automatically
        try {
            String extracted = DocumentUtil.getStringContent(work);
            if (extracted != null && !extracted.isEmpty()) return extracted;
        } catch (Exception e) {
            logger.warn("Failed to extract document content: " + e.getMessage());
        }

        // Plain text — try description, then text field
        String text = work.get(FieldNames.FIELD_DESCRIPTION);
        if (text != null && !text.isEmpty()) return text;
        text = work.get("text");
        if (text != null && !text.isEmpty()) return text;
        return null;
    }

    /**
     * Find or create a sub-group under a given parent group path.
     */
    private static BaseRecord ensureSubGroup(BaseRecord user, String parentGroupPath, String subName) {
        if (parentGroupPath == null || parentGroupPath.isEmpty()) return null;
        String subPath = parentGroupPath + "/" + subName;
        BaseRecord grp = IOSystem.getActiveContext().getPathUtil().makePath(user,
                ModelNames.MODEL_GROUP, subPath, GroupEnumType.DATA.toString(),
                (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
        if (grp != null) {
            try { grp.set(FieldNames.FIELD_PATH, subPath); } catch (Exception e) { /* already set */ }
        }
        return grp;
    }

    /**
     * Load the .pictureBookMeta record from a group path.
     * Uses data.note (text field has no length limit).
     */
    private static BaseRecord loadMeta(BaseRecord user, String groupPath) {
        if (groupPath == null) return null;
        BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(),
                (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
        if (grp == null) return null;

        Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
        q.field(FieldNames.FIELD_NAME, ".pictureBookMeta");
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        return IOSystem.getActiveContext().getAccessPoint().find(user, q);
    }

    /**
     * Save .pictureBookMeta JSON to a group as a data.note (text field, no length limit).
     */
    private static BaseRecord saveMeta(BaseRecord user, String groupPath, BaseRecord meta) {
        if (groupPath == null) return null;
        String metaJson = toJson(meta);

        BaseRecord existing = loadMeta(user, groupPath);
        if (existing != null) {
            try {
                existing.set("text", metaJson);
                IOSystem.getActiveContext().getAccessPoint().update(user, existing);
                return existing;
            } catch (Exception e) {
                logger.error("Failed to update meta: " + e.getMessage());
                return null;
            }
        }

        // Create new data.note
        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
        plist.parameter(FieldNames.FIELD_NAME, ".pictureBookMeta");
        try {
            BaseRecord newRec = IOSystem.getActiveContext().getFactory().newInstance(
                    ModelNames.MODEL_NOTE, user, null, plist);
            newRec.set("text", metaJson);
            return IOSystem.getActiveContext().getAccessPoint().create(user, newRec);
        } catch (Exception e) {
            logger.error("Failed to create meta: " + e.getMessage());
            return null;
        }
    }

    /**
     * Re-parse .pictureBookMeta's JSON blob back into a typed olio.pictureBookMeta record using
     * the schema embedded in the JSON (written by buildMeta()'s meta.toFullString()) — mirrors
     * reorderScenes()'s load/mutate/save pattern, so nested fields (scenes, sdConfig) round-trip
     * as proper typed models rather than raw maps.
     */
    private static BaseRecord loadTypedMeta(BaseRecord user, String bookGroupPath) {
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec == null) return null;
        String metaJson = metaRec.get("text");
        if (metaJson == null || metaJson.isEmpty()) return null;
        try {
            return JSONUtil.importObject(metaJson, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
        } catch (Exception e) {
            logger.error("Failed to parse meta: " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist the last-used image generation settings for a book, so images can be recreated
     * with the same settings later. Best-effort: a failure here must not fail the actual
     * generation request the caller is in the middle of servicing.
     */
    private static void persistBookSdConfig(BaseRecord user, String bookGroupPath, BaseRecord sdConfig) {
        try {
            BaseRecord meta = loadTypedMeta(user, bookGroupPath);
            if (meta == null) return;
            meta.set("sdConfig", sdConfig);
            saveMeta(user, bookGroupPath, meta);
        } catch (Exception e) {
            logger.warn("Failed to persist book sdConfig: " + e.getMessage());
        }
    }

    /**
     * Read back the last-used image generation settings for a book (see persistBookSdConfig),
     * or null if the book has never generated an image / has no meta yet.
     */
    public static BaseRecord getBookSdConfig(BaseRecord user, String bookObjectId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        BaseRecord meta = loadTypedMeta(user, bookGroupPath);
        if (meta == null) return null;
        return meta.get("sdConfig");
    }

    /**
     * Update a scene note's text JSON with a single key/value pair, preserving existing keys.
     * Used to persist generated image object ids so the viewer fallback can find them.
     */
    @SuppressWarnings("unchecked")
    private static void updateSceneTextField(BaseRecord user, BaseRecord scene, String key, String value) {
        try {
            String existingText = scene.get("text");
            Map<String, Object> textData = new LinkedHashMap<>();
            if (existingText != null && !existingText.isEmpty()) {
                try {
                    textData = JSONUtil.getMap(existingText.getBytes(), String.class, Object.class);
                } catch (Exception ex) { /* ignore parse errors */ }
            }
            textData.put(key, value);
            scene.set("text", JSONUtil.exportObject(textData));
            IOSystem.getActiveContext().getAccessPoint().update(user, scene);
        } catch (Exception e) {
            logger.warn("Failed to update scene " + key + ": " + e.getMessage());
        }
    }

    /**
     * Read a single key back out of a scene note's text JSON blob, or null if absent/unparseable.
     * Read-side counterpart to updateSceneTextField.
     */
    private static String getSceneTextField(BaseRecord scene, String key) {
        try {
            String existingText = scene.get("text");
            if (existingText == null || existingText.isEmpty()) return null;
            Map<String, Object> textData = JSONUtil.getMap(existingText.getBytes(), String.class, Object.class);
            Object v = textData.get(key);
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve (and cache) the landscape prompt for a scene. If prepareSceneImagePrompts() already
     * computed one for this scene, reuse it (no LLM call); otherwise call the LLM live, falling
     * back to the raw setting text on failure, and persist the result either way so a later call
     * (retry, or the SD stages later in this same pipeline run) never re-triggers the LLM. Callers
     * MUST invoke this — and OllamaModelUtil.unloadAll() — before any SD call in the same pipeline
     * run, so a large model isn't still resident in VRAM when the heavy composite/img2img SD call
     * happens (see generateSceneImage's Stage 0).
     */
    private static String resolveLandscapePrompt(BaseRecord user, BaseRecord scene, BaseRecord chatConfig,
            String setting, String mood, String style, String promptTemplateOverride) {
        String cached = getSceneTextField(scene, "landscapePrompt");
        if (cached != null && !cached.isBlank()) return cached;

        Map<String, String> landVars = new LinkedHashMap<>();
        landVars.put("setting", setting);
        landVars.put("mood", mood);
        landVars.put("time", "");
        landVars.put("style", (style != null && !style.isEmpty()) ? style : "illustration");
        String landscapePrompt = callLlm(user, chatConfig, "pictureBook.landscape-prompt", landVars, promptTemplateOverride);
        if (landscapePrompt == null || landscapePrompt.isBlank()) {
            logger.warn("Landscape prompt failed — falling back to setting text");
            landscapePrompt = setting.isEmpty() ? "A detailed environment" : setting;
        }
        updateSceneTextField(user, scene, "landscapePrompt", landscapePrompt);
        return landscapePrompt;
    }

    /**
     * Update a scene note's text JSON with the generated imageObjectId.
     * This persists the image reference so the viewer fallback can find it.
     */
    private static void updateSceneImageId(BaseRecord user, BaseRecord scene, String imageObjectId) {
        updateSceneTextField(user, scene, "imageObjectId", imageObjectId);
    }

    /**
     * Update a scene note's text JSON with the generated landscape's objectId (see
     * pictureBookSceneModel.json#landscapeObjectId). The landscape record is not deleted after
     * use — this persists the reference to the retained record.
     */
    private static void updateSceneLandscapeId(BaseRecord user, BaseRecord scene, String landscapeObjectId) {
        updateSceneTextField(user, scene, "landscapeObjectId", landscapeObjectId);
    }

    private static final Set<String> ALLOWED_SCENE_STATUSES = new HashSet<>(Arrays.asList(
            "pending", "generating", "done", "error", "accepted", "skipped"));

    /**
     * Update a scene note's text JSON with its generation status and (optionally) an error
     * message, so the wizard's progress survives a reload/reopen. Mirrors updateSceneImageId's
     * pattern. A null/empty error clears any previously stored error (e.g. on a successful retry).
     */
    private static void updateSceneStatus(BaseRecord user, BaseRecord scene, String status, String error) {
        updateSceneTextField(user, scene, "status", status);
        updateSceneTextField(user, scene, "error", error);
    }

    /**
     * Persist a client-driven scene status (accepted/skipped/pending/etc.) — the counterpart to
     * the server-driven statuses (generating/done/error) written inside generateSceneImage.
     */
    public static void setSceneStatus(BaseRecord user, String sceneObjectId, String status) {
        if (status == null || !ALLOWED_SCENE_STATUSES.contains(status)) {
            throw new PictureBookException(400, "Invalid status: " + status);
        }
        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        if (scene == null) throw new PictureBookException(404, "Scene not found");
        updateSceneStatus(user, scene, status, null);
    }

    /**
     * Parse LLM JSON response into a list of maps, stripping markdown fences if present.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseLlmJsonArray(String response) {
        if (response == null || response.isEmpty()) return new ArrayList<>();
        String trimmed = response.trim();
        // Strip markdown code fences
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl >= 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        // Find first [ ... ] array
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) return new ArrayList<>();
        trimmed = trimmed.substring(start, end + 1);
        try {
            List<Map<String, Object>> parsed = JSONUtil.getList(trimmed, Map.class, null);
            if (parsed != null) return parsed;
        } catch (Exception e) {
            logger.warn("Failed to parse LLM JSON array: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Parse a single LLM JSON object response.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseLlmJsonObject(String response) {
        if (response == null || response.isEmpty()) return new LinkedHashMap<>();
        String trimmed = response.trim();
        // Strip <think>...</think> blocks (Qwen3 etc. may ignore think:false)
        trimmed = trimmed.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl >= 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return new LinkedHashMap<>();
        trimmed = trimmed.substring(start, end + 1);
        try {
            return JSONUtil.getMap(trimmed.getBytes(), String.class, Object.class);
        } catch (Exception e) {
            logger.warn("Failed to parse LLM JSON object: " + e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    /**
     * Call LLM with optional prompt template override name.
     */
    private static String callLlm(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars, String overrideName) {
        if (overrideName != null && !overrideName.isEmpty()) {
            promptName = overrideName;
        }
        return callLlmInternal(user, chatConfig, promptName, vars);
    }

    private static String callLlm(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
        return callLlmInternal(user, chatConfig, promptName, vars);
    }

    private static String callLlmInternal(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
        String system = null;
        String userTpl = null;

        // Try user-customizable prompt template first (user's group → system library)
        try {
            BaseRecord pt = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_PROMPT_TEMPLATE, promptName, null);
            if (pt != null) {
                // Compose system and user strings from sections
                @SuppressWarnings("unchecked")
                List<BaseRecord> sections = pt.get("sections");
                if (sections != null) {
                    StringBuilder sysBuf = new StringBuilder();
                    StringBuilder usrBuf = new StringBuilder();
                    for (BaseRecord sec : sections) {
                        String role = sec.get("role");
                        @SuppressWarnings("unchecked")
                        List<String> lines = sec.get("lines");
                        if (lines == null) continue;
                        String text = String.join("\n", lines);
                        if ("system".equals(role)) sysBuf.append(text).append("\n");
                        else if ("user".equals(role)) usrBuf.append(text).append("\n");
                    }
                    if (sysBuf.length() > 0) system = sysBuf.toString().trim();
                    if (usrBuf.length() > 0) userTpl = usrBuf.toString().trim();
                }
            }
        } catch (Exception e) {
            logger.debug("Prompt template lookup failed for " + promptName + ": " + e.getMessage());
        }

        // Fallback to classpath resource
        if (system == null) system = PromptResourceUtil.getString(promptName, "system");
        if (userTpl == null) userTpl = PromptResourceUtil.getString(promptName, "user");
        if (system == null || userTpl == null) {
            logger.warn("Prompt template not found: " + promptName);
            return null;
        }
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                if (e.getValue() != null) {
                    userTpl = userTpl.replace("{" + e.getKey() + "}", e.getValue());
                }
            }
        }
        try {
            // Fall back to default chat config if none provided
            if (chatConfig == null) {
                chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, "generalChat", null);
            }
            if (chatConfig == null) {
                logger.error("No chat config available — cannot call LLM for " + promptName);
                return null;
            }
            Chat chat = new Chat(user, chatConfig, null);
            chat.setLlmSystemPrompt(system);
            OpenAIRequest req = chat.newRequest(chat.getModel());
            req.setStream(false);
            // Disable thinking for structured extraction tasks (Qwen3, etc.)
            try {
                BaseRecord reqOpts = req.get("options");
                if (reqOpts == null) {
                    reqOpts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
                    req.set("options", reqOpts);
                }
                reqOpts.set("think", false);
            } catch (Exception ex) { /* ignore if field doesn't exist */ }
            chat.newMessage(req, userTpl);
            OpenAIResponse resp = chat.chat(req);
            if (resp != null && resp.getMessage() != null) {
                return resp.getMessage().getContent();
            }
        } catch (Exception e) {
            logger.error("LLM call failed for " + promptName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Internal chunked extraction — shared by extract-scenes-only (auto-chunk) and extract-chunked.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text) {
        int chunkSize = 2000;
        int overlap = 200;
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf('.', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int breakAt = Math.max(lastPeriod, lastNewline);
                if (breakAt > pos + chunkSize / 2) end = breakAt + 1;
            }
            chunks.add(text.substring(pos, end));
            pos = end - overlap;
            if (pos < 0) pos = 0;
            if (end >= text.length()) break;
        }

        List<Map<String, Object>> sceneList = new ArrayList<>();
        for (int ci = 0; ci < chunks.size(); ci++) {
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome",
                    "Extracting chunk " + (ci + 1) + "/" + chunks.size() + "...");
            String previousJson = sceneList.isEmpty() ? "[]" : JSONUtil.exportObject(sceneList);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("previousScenes", previousJson);
            vars.put("chunk", chunks.get(ci));
            String llmResp = callLlm(user, chatConfig, "pictureBook.extract-chunk", vars);
            if (llmResp == null || llmResp.isEmpty()) continue;
            Map<String, Object> chunkResult = parseLlmJsonObject(llmResp);
            if (chunkResult == null || chunkResult.isEmpty()) {
                logger.warn("Chunk " + (ci + 1) + "/" + chunks.size() + " returned unparseable LLM response — skipping");
                continue;
            }

            Object addObj = chunkResult.get("additions");
            if (addObj instanceof List) {
                List<Map<String, Object>> additions = (List<Map<String, Object>>) addObj;
                for (Map<String, Object> scene : additions) {
                    scene.put("index", sceneList.size());
                    scene.put("userEdited", false);
                    sceneList.add(scene);
                }
            }
            Object revObj = chunkResult.get("revisions");
            if (revObj instanceof List) {
                List<Map<String, Object>> revisions = (List<Map<String, Object>>) revObj;
                for (Map<String, Object> rev : revisions) {
                    String revTitle = (String) rev.get("title");
                    if (revTitle == null) continue;
                    for (int si = 0; si < sceneList.size(); si++) {
                        String existingTitle = (String) sceneList.get(si).get("title");
                        if (revTitle.equals(existingTitle)) {
                            Map<String, Object> existing = sceneList.get(si);
                            for (Map.Entry<String, Object> e : rev.entrySet()) {
                                if (!"title".equals(e.getKey()) && e.getValue() != null) {
                                    existing.put(e.getKey(), e.getValue());
                                }
                            }
                            break;
                        }
                    }
                }
            }
            Object remObj = chunkResult.get("removals");
            if (remObj instanceof List) {
                List<String> removals = (List<String>) remObj;
                sceneList.removeIf(s -> removals.contains(s.get("title")));
            }
            logger.info("Chunk " + (ci + 1) + "/" + chunks.size() + " processed: " + sceneList.size() + " scenes total");
        }
        for (int i = 0; i < sceneList.size(); i++) {
            Map<String, Object> scene = sceneList.get(i);
            scene.put("index", i);
            // Normalize: LLM may return "summary" instead of "blurb"
            if (scene.get("blurb") == null && scene.get("summary") != null) {
                scene.put("blurb", scene.get("summary"));
            }
        }
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        // Chunked extraction can make many LLM calls in a row — flush once at the end rather
        // than per-chunk (per-chunk would just force an immediate reload for the next chunk).
        OllamaModelUtil.unloadAll();
        return sceneList;
    }

    /**
     * Extract the actual seed from a generated image record's attributes.
     * SDUtil stores it as AttributeUtil.addAttribute(data, "seed", seedl).
     */
    private static int extractSeedFromImage(BaseRecord image) {
        try {
            int seedVal = AttributeUtil.getAttributeValue(image, "seed", -1);
            if (seedVal > 0) return seedVal;
        } catch (Exception e) { /* attribute may not exist */ }
        return -1;
    }

    /**
     * Clamp free-text LLM-extracted gender to exactly one of MALE/FEMALE/UNKNOWN
     * (Stephen's explicit decision — no other values). identity.person.gender is a plain
     * string with maxLength 10, so all three values always fit; this is a logic fix, not a
     * schema change. Never throws — any unrecognized/blank input maps to UNKNOWN so a bad LLM
     * value can never abort character creation.
     */
    private static String normalizeGender(String raw) {
        if (raw == null) return "UNKNOWN";
        String g = raw.trim().toLowerCase();
        if (g.equals("male") || g.equals("m")) return "MALE";
        if (g.equals("female") || g.equals("f")) return "FEMALE";
        return "UNKNOWN";
    }

    /**
     * Determine genre theme from genre hint string.
     */
    @SuppressWarnings("unused")
    private static String genreToTheme(String genre) {
        if (genre == null) return null;
        return GENRE_THEME_MAP.getOrDefault(genre.toLowerCase(), null);
    }

    /**
     * Build a pictureBookMeta record using the typed model.
     */
    private static BaseRecord buildMeta(String sourceObjectId, String bookObjectId, String workName, List<BaseRecord> scenes) {
        try {
            BaseRecord meta = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_META);
            meta.set("sourceObjectId", sourceObjectId);
            meta.set("bookObjectId", bookObjectId);
            meta.set("workName", workName);
            meta.set("sceneCount", scenes.size());
            meta.set("scenes", scenes);
            meta.set("extractedAt", ZonedDateTime.now().toString());
            return meta;
        } catch (Exception e) {
            logger.error("Failed to build meta: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a pictureBookScene record from scene data + note objectId.
     */
    @SuppressWarnings("unchecked")
    private static BaseRecord buildSceneEntry(BaseRecord note, Map<String, Object> sceneData, int idx, Map<String, String> charObjectIds) {
        try {
            BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_SCENE);
            scene.set(FieldNames.FIELD_OBJECT_ID, note.get(FieldNames.FIELD_OBJECT_ID));
            scene.set("index", idx);
            scene.set("title", sceneData.getOrDefault("title", "Scene " + idx));
            String desc = (String) sceneData.getOrDefault("blurb", sceneData.getOrDefault("summary", sceneData.getOrDefault("description", "")));
            scene.set(FieldNames.FIELD_DESCRIPTION, desc);
            List<String> charIds = new ArrayList<>();
            Object charsObj = sceneData.get("characters");
            if (charsObj instanceof List && charObjectIds != null) {
                for (Object sc : (List<Object>) charsObj) {
                    String cn = null;
                    if (sc instanceof Map) cn = (String) ((Map<String, Object>) sc).get("name");
                    else if (sc instanceof String) cn = (String) sc;
                    if (cn != null && charObjectIds.containsKey(cn)) charIds.add(charObjectIds.get(cn));
                }
            }
            scene.set("characters", charIds);
            return scene;
        } catch (Exception e) {
            logger.error("Failed to build scene entry: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a pictureBookResult response record.
     */
    public static BaseRecord buildResult() {
        try {
            return RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_RESULT);
        } catch (Exception e) {
            logger.error("Failed to create result: " + e.getMessage());
            return null;
        }
    }

    /**
     * Serialize a model record to JSON.
     */
    private static String toJson(BaseRecord rec) {
        return rec.toFullString();
    }

    /**
     * Create and persist a foreign-model instance (e.g. olio.narrative, identity.profile) with
     * a resolvable group path — mirrors CharPersonFactory's nested-model path convention
     * ("~/" + schema.getGroup()). Unlike CharPersonFactory's in-memory placeholders (which rely
     * on autoCreateForeignReference cascading — NOT enabled for olio.charPerson — so they are
     * silently never persisted), this goes through AccessPoint so the record gets a real
     * id/objectId immediately and can be safely linked to a parent via a PATCH-shaped update.
     */
    private static BaseRecord createPersistedForeignInstance(BaseRecord user, String modelName) {
        try {
            ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
                    "~/" + RecordFactory.getSchema(modelName).getGroup());
            BaseRecord inst = IOSystem.getActiveContext().getFactory().newInstance(modelName, user, null, plist);
            BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, inst);
            if (created == null) {
                logger.error("Failed to persist new " + modelName + " instance — AccessPoint.create returned null (denied or persist failure)");
            }
            return created;
        } catch (Exception e) {
            logger.error("Failed to create persisted " + modelName + " instance: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * PATCH-shaped update: identity fields (id, objectId) + a single foreign field on
     * olio.charPerson. Deliberately avoids a full-object update on a shallow/partially
     * populated charPerson record, which would risk re-persisting other foreign refs (e.g. a
     * groupless system.user reference) and a PBAC denial that silently drops the intended
     * change. See .claude/rules/model-api.md — PATCH / partial updates.
     *
     * <p>Uses {@code BaseRecord.copyRecord(fieldNames)} on the already-loaded {@code charPerson}
     * — the same "mutate the live record, then derive a minimal patch via copyRecord(fields)"
     * idiom {@code NarrativeUtil.getCreateNarrative}/{@code RecordUtil.patch} and
     * {@code SDUtil.generateSDImages}/{@code Queue.queueUpdate} use elsewhere in Olio — rather
     * than hand-building the patch with {@code RecordFactory.newInstance(schema, fieldNames)}.
     * {@code copyRecord(fieldNames)} calls that exact same
     * {@code RecordFactory.newInstance(getSchema(), outFieldNames)} internally, so the reason
     * an explicit fieldNames list is required is unchanged: olio.charPerson inherits
     * identity.person -> data.directory -> common.nameId -> common.name, whose "name" field is
     * required/$notEmpty, and restricting the field list keeps "name" out of the patch entirely
     * so it's never instantiated or validated.
     *
     * <p>Deliberately calls {@code AccessPoint.update()} directly here rather than routing
     * through the shared static {@code Queue}/{@code Queue.processQueue(user)} deferred-batch
     * mechanism those Olio callers use: {@code Queue.processQueue(user)} discards the per-record
     * update count and drains the ENTIRE process-wide queue (not just what this call queued),
     * which is fine for Olio's single-threaded, per-world population/evolution batch jobs but
     * unsafe for this live, concurrently-invoked, multi-user REST endpoint — and this method's
     * callers need a definitive per-call success/failure signal (a null return here becomes a
     * logged, surfaced failedCharacters/failedPortraits entry, not a silent no-op).
     */
    private static BaseRecord patchCharPersonField(BaseRecord user, BaseRecord charPerson, String fieldName, BaseRecord value) {
        try {
            charPerson.set(fieldName, value);
            BaseRecord patch = charPerson.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, fieldName });
            return IOSystem.getActiveContext().getAccessPoint().update(user, patch);
        } catch (Exception e) {
            logger.error("Failed to PATCH charPerson." + fieldName + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create an olio.charPerson for an extracted character, apply outfit, call narrate.
     */
    @SuppressWarnings("unchecked")
    private static BaseRecord createCharPerson(BaseRecord user, Map<String, Object> charData, BaseRecord charsGroup, String genre) {
        String name = (String) charData.get("name");
        if (name == null || name.isEmpty()) return null;

        // Split name into first/last
        String firstName = name;
        String lastName = "";
        int sp = name.lastIndexOf(' ');
        if (sp > 0) {
            firstName = name.substring(0, sp).trim();
            lastName = name.substring(sp + 1).trim();
        }

        // Check existing
        Query eq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, name);
        eq.field(FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
        eq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        BaseRecord existing = IOSystem.getActiveContext().getAccessPoint().find(user, eq);
        if (existing != null) return existing;

        try {
            ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
                    charsGroup.get(FieldNames.FIELD_PATH));
            plist.parameter(FieldNames.FIELD_NAME, name);
            BaseRecord charPerson = IOSystem.getActiveContext().getFactory().newInstance(
                    OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);

            charPerson.set(FieldNames.FIELD_NAME, name);
            if (!firstName.isEmpty()) charPerson.set("firstName", firstName);
            if (!lastName.isEmpty()) charPerson.set("lastName", lastName);

            // Apply gender — clamped to MALE/FEMALE/UNKNOWN only, never a raw/unrecognized
            // LLM value (see normalizeGender()). Must happen before create() so a bad LLM
            // value never aborts character creation.
            String gender = (String) charData.get("gender");
            charPerson.set("gender", normalizeGender(gender));

            charPerson = IOSystem.getActiveContext().getAccessPoint().create(user, charPerson);
            if (charPerson == null) return null;

            // Re-fetch the full record — create returns identity-only partial
            String cpOid = charPerson.get(FieldNames.FIELD_OBJECT_ID);
            Query refetch = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
            refetch.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
            refetch.planMost(false);
            charPerson = IOSystem.getActiveContext().getAccessPoint().find(user, refetch);
            if (charPerson == null) return null;

            // Build SD portrait prompt from extracted character data.
            // narrative is a foreign model (olio.narrative) — set sdPrompt on it, not a raw string.
            String portraitPrompt = NarrativeUtil.buildPortraitPromptFromExtractedData(name, charData);
            if (portraitPrompt == null || portraitPrompt.isEmpty()) {
                String appearance = (String) charData.getOrDefault("appearance", "");
                String role = (String) charData.getOrDefault("role", "");
                String gender2 = (String) charData.getOrDefault("gender", "person");
                portraitPrompt = "portrait of " + name + ", " + gender2;
                if (!appearance.isEmpty()) portraitPrompt += ", " + appearance;
                if (!role.isEmpty()) portraitPrompt += ", " + role;
                portraitPrompt += ", detailed face, cinematic lighting, high quality";
            }
            // Ensure the profile is a real *persisted* record. CharPersonFactory only builds an
            // in-memory placeholder (a path-scoped identity.profile with no id) and olio.charPerson
            // does not set autoCreateForeignReference, so that placeholder is never cascaded into
            // the database on create — profile.id stays 0/null forever unless we persist it here
            // explicitly. Without this, portraits can never be linked to the character later.
            BaseRecord profile = charPerson.get("profile");
            Long existingProfileId = (profile != null) ? profile.get(FieldNames.FIELD_ID) : null;
            if (profile == null || existingProfileId == null || existingProfileId <= 0L) {
                BaseRecord newProfile = createPersistedForeignInstance(user, ModelNames.MODEL_PROFILE);
                if (newProfile == null) {
                    logger.error("Failed to create persisted profile for charPerson " + name);
                    return null;
                }
                BaseRecord profileLinked = patchCharPersonField(user, charPerson, "profile", newProfile);
                if (profileLinked == null) {
                    logger.error("Failed to link persisted profile to charPerson " + name);
                    return null;
                }
                charPerson.set("profile", newProfile);
            }

            // Ensure the narrative is a real *persisted* record and carries the SD portrait
            // prompt, then attach it via a PATCH-shaped update (identity + narrative only) —
            // NOT a full-object update on the shallow (planMost(false)) charPerson, which would
            // risk re-persisting other foreign refs and a silent PBAC denial.
            try {
                BaseRecord narrative = charPerson.get("narrative");
                Long existingNarrativeId = (narrative != null) ? narrative.get(FieldNames.FIELD_ID) : null;
                if (narrative == null || existingNarrativeId == null || existingNarrativeId <= 0L) {
                    narrative = createPersistedForeignInstance(user, OlioModelNames.MODEL_NARRATIVE);
                    if (narrative == null) {
                        logger.error("Failed to create persisted narrative for charPerson " + name);
                        return null;
                    }
                }
                narrative.set("sdPrompt", portraitPrompt);
                narrative.set("physicalDescription", portraitPrompt);

                // patchCharPersonField() below only rewrites charPerson.narrative's FK reference
                // (per .claude/rules/model-api.md, "foreign fields patch by ID reference") — it does
                // NOT cascade-write the narrative record's own fields to the DB. sdPrompt/
                // physicalDescription were set on the in-memory narrative object above (AFTER
                // createPersistedForeignInstance()'s initial AccessPoint.create()), so without this
                // second, separate update directly on olio.narrative, those field writes are lost —
                // confirmed live: narrative.sdPrompt reads back null after the full pipeline runs.
                // Mirrors NarrativeUtil.getCreateNarrative's own "patch an existing record's
                // content fields" idiom — derive the minimal patch via copyRecord(fieldNames) on
                // the already-loaded record (same as RecordUtil.patch's targ.copyRecord(upf))
                // rather than hand-building it with RecordFactory.newInstance(schema, fieldNames).
                // Unlike getCreateNarrative's RecordUtil.patch call (which writes via
                // RecordUtil.updateRecord() — a PBAC bypass appropriate for Olio's internal
                // population-generation, not for this live end-user REST session), this still
                // goes through AccessPoint.update() directly so PBAC is respected and a
                // null/failure return remains detectable (see the check just below).
                BaseRecord narrativePatch = narrative.copyRecord(
                        new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "sdPrompt", "physicalDescription" });
                BaseRecord narrativeFieldsPersisted = IOSystem.getActiveContext().getAccessPoint().update(user, narrativePatch);
                if (narrativeFieldsPersisted == null) {
                    logger.error("Failed to persist narrative.sdPrompt/physicalDescription for charPerson " + name + " — AccessPoint.update denied or failed (PBAC/persist)");
                    return null;
                }

                BaseRecord narrativeLinked = patchCharPersonField(user, charPerson, "narrative", narrative);
                if (narrativeLinked == null) {
                    logger.error("Failed to attach narrative to charPerson " + name + " — AccessPoint.update denied or failed (PBAC/persist)");
                    return null;
                }
                charPerson.set("narrative", narrative);
            } catch (Exception e) {
                logger.error("Failed to set portrait prompt/narrative for " + name + ": " + e.getMessage(), e);
                return null;
            }
            return charPerson;

        } catch (Exception e) {
            logger.error("Failed to create charPerson " + name + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create a data.note record for a scene.
     */
    private static BaseRecord createSceneNote(BaseRecord user, BaseRecord scenesGroup, Map<String, Object> sceneData, int idx) {
        String title = (String) sceneData.getOrDefault("title", "Scene " + idx);
        String summary = (String) sceneData.getOrDefault("summary", "");

        try {
            ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH,
                    scenesGroup.get(FieldNames.FIELD_PATH));
            plist.parameter(FieldNames.FIELD_NAME, title);
            BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(
                    ModelNames.MODEL_NOTE, user, null, plist);
            note.set(FieldNames.FIELD_NAME, title);

            // Store scene metadata + summary as JSON in the text field
            // (data.note has no 'description' field — summary goes in the metadata)
            Map<String, Object> sceneStore = new LinkedHashMap<>(sceneData);
            sceneStore.put("sceneIndex", idx);
            sceneStore.put("blurb", summary);
            note.set("text", JSONUtil.exportObject(sceneStore));

            return IOSystem.getActiveContext().getAccessPoint().create(user, note);
        } catch (Exception e) {
            logger.error("Failed to create scene note: " + e.getMessage());
            return null;
        }
    }

    // ----- Public pipeline entry points (one per REST endpoint) -----------

    /**
     * Smart scene extraction — auto-chunks if text > 8000 chars.
     */
    public static ScenesOnlyResult extractScenesOnly(BaseRecord user, String workObjectId, int count,
            String chatConfigName, String promptTemplateOverride) {
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) throw new PictureBookException(404, "Work not found");

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            throw new PictureBookException(400, "No text content found in work");
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }

        // Auto-chunk if text exceeds 8000 chars
        if (text.length() > 8000) {
            List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text);
            return new ScenesOnlyResult(sceneList, true);
        }

        // Short text — single-shot extraction
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome", "Extracting scenes...");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("count", String.valueOf(count));
        vars.put("text", text);

        String llmResponse = callLlm(user, chatConfig, "pictureBook.extract-scenes", vars, promptTemplateOverride);
        List<Map<String, Object>> scenes = parseLlmJsonArray(llmResponse);
        // Normalize: LLM may return "summary" instead of "blurb"
        for (Map<String, Object> scene : scenes) {
            if (scene.get("blurb") == null && scene.get("summary") != null) {
                scene.put("blurb", scene.get("summary"));
            }
        }
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        OllamaModelUtil.unloadAll();
        return new ScenesOnlyResult(scenes, false);
    }

    /**
     * Chunked scene extraction — kept for backward compatibility; extractScenesOnly now auto-chunks.
     */
    public static BaseRecord extractChunked(BaseRecord user, String workObjectId, String chatConfigName) {
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) throw new PictureBookException(404, "Work not found");

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            throw new PictureBookException(400, "No text content found");
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }

        List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text);
        BaseRecord result = buildResult();
        try {
            result.set("sceneList", sceneList);
            result.set("extractionComplete", true);
            result.set("chunksProcessed", -1);
        } catch (Exception e) { logger.warn("Failed to build chunked result: " + e.getMessage()); }
        return result;
    }

    /**
     * Full extraction: scenes + characters + outfit + narrate. Returns .pictureBookMeta.
     */
    public static BaseRecord extract(BaseRecord user, String workObjectId, int count, String chatConfigName,
            String genre, String bookName) {
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) throw new PictureBookException(404, "Work not found");

        if (count > MAX_SCENES_DEFAULT) count = MAX_SCENES_DEFAULT;

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            throw new PictureBookException(400, "No text content found");
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }

        // Create book group under ~/PictureBooks/{bookName}/
        String effectiveBookName = (bookName != null && !bookName.isEmpty()) ? bookName : work.get(FieldNames.FIELD_NAME);
        BaseRecord bookGroup = ensureBookGroup(user, effectiveBookName);
        if (bookGroup == null) {
            throw new PictureBookException(500, "Failed to create book group");
        }
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        if (bookGroupPath == null) bookGroupPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + effectiveBookName;

        // Ensure sub-groups under book group
        BaseRecord scenesGroup = ensureSubGroup(user, bookGroupPath, "Scenes");
        BaseRecord charsGroup = ensureSubGroup(user, bookGroupPath, "Characters");
        if (scenesGroup == null || charsGroup == null) {
            throw new PictureBookException(500, "Failed to create sub-groups");
        }

        // Extract scenes
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome", "Extracting scenes...");
        Map<String, String> sceneVars = new LinkedHashMap<>();
        sceneVars.put("count", String.valueOf(count));
        sceneVars.put("text", text.length() > 8000 ? text.substring(0, 8000) : text);
        String llmScenes = callLlm(user, chatConfig, "pictureBook.extract-scenes", sceneVars);
        List<Map<String, Object>> extractedScenes = parseLlmJsonArray(llmScenes);

        // Collect unique character names across all scenes
        Map<String, Map<String, Object>> uniqueChars = new LinkedHashMap<>();
        for (Map<String, Object> scene : extractedScenes) {
            Object charsObj = scene.get("characters");
            if (charsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> sceneChars = (List<Map<String, Object>>) charsObj;
                for (Map<String, Object> sc : sceneChars) {
                    String cname = (String) sc.get("name");
                    if (cname != null && !cname.isEmpty() && !uniqueChars.containsKey(cname)) {
                        uniqueChars.put(cname, sc);
                    }
                }
            }
        }

        // Extract character details and create charPerson records
        Map<String, String> charObjectIds = new LinkedHashMap<>();
        // createCharPerson() failures are never silently dropped — collected here so a 200
        // response can never mean "silently 0 characters created".
        List<String> failedCharacters = new ArrayList<>();
        int charIdx = 0;
        for (Map.Entry<String, Map<String, Object>> entry : uniqueChars.entrySet()) {
            String cname = entry.getKey();
            charIdx++;
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "person",
                    "Extracting character " + charIdx + "/" + uniqueChars.size() + ": " + cname);
            Map<String, String> charVars = new LinkedHashMap<>();
            charVars.put("name", cname);
            charVars.put("text", text.length() > 8000 ? text.substring(0, 8000) : text);
            String llmChar = callLlm(user, chatConfig, "pictureBook.extract-character", charVars);
            Map<String, Object> charData = parseLlmJsonObject(llmChar);
            if (charData.isEmpty()) charData = new LinkedHashMap<>(entry.getValue());
            if (!charData.containsKey("name")) charData.put("name", cname);

            BaseRecord cp = createCharPerson(user, charData, charsGroup, genre);
            if (cp != null) {
                charObjectIds.put(cname, cp.get(FieldNames.FIELD_OBJECT_ID));
            } else {
                logger.error("createCharPerson failed for '" + cname + "' during /extract — character will be absent from the book");
                failedCharacters.add(cname);
            }
        }

        // Create scene data.note records
        List<BaseRecord> metaScenes = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> sceneData : extractedScenes) {
            BaseRecord note = createSceneNote(user, scenesGroup, sceneData, idx);
            if (note != null) {
                BaseRecord sceneEntry = buildSceneEntry(note, sceneData, idx, charObjectIds);
                if (sceneEntry != null) metaScenes.add(sceneEntry);
            }
            idx++;
        }

        // Build and save .pictureBookMeta
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "save", "Saving book...");
        BaseRecord meta = buildMeta(workObjectId, bookGroup.get(FieldNames.FIELD_OBJECT_ID), effectiveBookName, metaScenes);
        if (!failedCharacters.isEmpty()) {
            try { meta.set("failedCharacters", failedCharacters); } catch (Exception e) { logger.warn("Failed to record failedCharacters on meta: " + e.getMessage()); }
        }
        saveMeta(user, bookGroupPath, meta);
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        // Many LLM calls happen above (scene extraction + one per unique character) — flush once
        // at the very end, not per-call.
        OllamaModelUtil.unloadAll();

        return meta;
    }

    /**
     * Takes user-curated scene list from Step 2, creates book group, scene notes, extracts +
     * creates charPerson records, saves meta. Returns the .pictureBookMeta record.
     */
    @SuppressWarnings("unchecked")
    public static BaseRecord createFromScenes(BaseRecord user, String workObjectId, String chatConfigName,
            String genre, String bookName, List<Map<String, Object>> sceneList, List<Map<String, Object>> charDataListIn) {
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) throw new PictureBookException(404, "Work not found");

        if (sceneList == null || sceneList.isEmpty()) {
            throw new PictureBookException(400, "sceneList is required");
        }
        List<Map<String, Object>> charDataList = (charDataListIn != null) ? new ArrayList<>(charDataListIn) : new ArrayList<>();

        String effectiveBookName = (bookName != null && !bookName.isEmpty()) ? bookName : work.get(FieldNames.FIELD_NAME);
        BaseRecord bookGroup = ensureBookGroup(user, effectiveBookName);
        if (bookGroup == null) {
            throw new PictureBookException(500, "Failed to create book group");
        }
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        if (bookGroupPath == null) bookGroupPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + effectiveBookName;

        BaseRecord scenesGroup = ensureSubGroup(user, bookGroupPath, "Scenes");
        BaseRecord charsGroup = ensureSubGroup(user, bookGroupPath, "Characters");
        if (scenesGroup == null || charsGroup == null) {
            throw new PictureBookException(500, "Failed to create sub-groups");
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }

        // Extract text for LLM character extraction (if no pre-built character data)
        String text = extractWorkText(user, work);

        // If character data was provided from Step 3, use it directly; otherwise extract from scenes
        if (charDataList.isEmpty()) {
            // Collect unique character names from scene list
            Map<String, Map<String, Object>> uniqueChars = new LinkedHashMap<>();
            for (Map<String, Object> scene : sceneList) {
                Object charsObj = scene.get("characters");
                if (charsObj instanceof List) {
                    List<Object> sceneChars = (List<Object>) charsObj;
                    for (Object sc : sceneChars) {
                        String cname = null;
                        Map<String, Object> cmap = null;
                        if (sc instanceof Map) {
                            cmap = (Map<String, Object>) sc;
                            cname = (String) cmap.get("name");
                        } else if (sc instanceof String) {
                            cname = (String) sc;
                            cmap = new LinkedHashMap<>();
                            cmap.put("name", cname);
                        }
                        if (cname != null && !cname.isEmpty() && !uniqueChars.containsKey(cname)) {
                            uniqueChars.put(cname, cmap);
                        }
                    }
                }
            }
            for (Map.Entry<String, Map<String, Object>> entry : uniqueChars.entrySet()) {
                charDataList.add(entry.getValue());
            }
        }

        // Create charPerson records — use LLM for detail extraction if needed
        Map<String, String> charObjectIds = new LinkedHashMap<>();
        // createCharPerson() failures are never silently dropped — collected here so a 200
        // response can never mean "silently 0 characters created".
        List<String> failedCharacters = new ArrayList<>();
        int cfsCharIdx = 0;
        for (Map<String, Object> charData : charDataList) {
            String cname = (String) charData.get("name");
            if (cname == null || cname.isEmpty()) continue;
            cfsCharIdx++;
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "person",
                    "Creating character " + cfsCharIdx + "/" + charDataList.size() + ": " + cname);

            // If appearance is missing and we have text, use LLM to extract details
            if ((charData.get("appearance") == null || ((String) charData.getOrDefault("appearance", "")).isEmpty())
                    && text != null && !text.isEmpty() && chatConfig != null) {
                Map<String, String> charVars = new LinkedHashMap<>();
                charVars.put("name", cname);
                charVars.put("text", text.length() > 8000 ? text.substring(0, 8000) : text);
                String llmChar = callLlm(user, chatConfig, "pictureBook.extract-character", charVars);
                Map<String, Object> llmData = parseLlmJsonObject(llmChar);
                if (!llmData.isEmpty()) {
                    // Merge LLM data without overwriting user edits
                    for (Map.Entry<String, Object> e : llmData.entrySet()) {
                        if (!charData.containsKey(e.getKey()) || charData.get(e.getKey()) == null
                                || ((charData.get(e.getKey()) instanceof String) && ((String) charData.get(e.getKey())).isEmpty())) {
                            charData.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }

            BaseRecord cp = createCharPerson(user, charData, charsGroup, genre);
            if (cp != null) {
                charObjectIds.put(cname, cp.get(FieldNames.FIELD_OBJECT_ID));
            } else {
                logger.error("createCharPerson failed for '" + cname + "' during /create-from-scenes — character will be absent from the book");
                failedCharacters.add(cname);
            }
        }

        // Create scene notes
        List<BaseRecord> metaScenes = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> sceneData : sceneList) {
            BaseRecord note = createSceneNote(user, scenesGroup, sceneData, idx);
            if (note != null) {
                BaseRecord sceneEntry = buildSceneEntry(note, sceneData, idx, charObjectIds);
                if (sceneEntry != null) metaScenes.add(sceneEntry);
            }
            idx++;
        }

        PictureBookProgressNotifier.getInstance().notifyProgress(user, "save", "Saving book...");
        BaseRecord meta = buildMeta(workObjectId, bookGroup.get(FieldNames.FIELD_OBJECT_ID), effectiveBookName, metaScenes);
        if (!failedCharacters.isEmpty()) {
            try { meta.set("failedCharacters", failedCharacters); } catch (Exception e) { logger.warn("Failed to record failedCharacters on meta: " + e.getMessage()); }
        }
        saveMeta(user, bookGroupPath, meta);
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        // One LLM call per character needing detail extraction above — flush once at the end.
        OllamaModelUtil.unloadAll();

        return meta;
    }

    /**
     * Generate SD image for one scene using the 4-stage pipeline:
     *   Stage 1: SDXL portrait generation per scene character (uses narrative prompt stored on charPerson)
     *   Stage 2: SDXL landscape generation via LLM prompt
     *   Stage 3: Stitch 3-panel reference composite [portrait1 | portrait2|landscape | landscape]
     *   Stage 4: Flux Kontext composite from reference + scene description
     *
     * @param sdApiType the {@code SDAPIEnumType} name (e.g. "SWARM") — resolved by the caller
     *                  from whatever deployment config it uses (web.xml init-param in
     *                  production Service7, a plain test-config string in tests).
     * @param sdServer  the SD backend base URL — same resolution note as {@code sdApiType}.
     */
    @SuppressWarnings("unchecked")
    public static BaseRecord generateSceneImage(BaseRecord user, String sceneObjectId, SceneGenerationParams params,
            String sdApiType, String sdServer) {
        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        sq.planMost(false);
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        if (scene == null) throw new PictureBookException(404, "Scene not found");

        // Clamp client-supplied style to the allowed set once, so both the FLUX and landscape-LLM
        // paths only ever see a valid value (the model's `limit` is not enforced on this in-memory record).
        String style = params.style;
        final String requestedStyle = style;
        if (requestedStyle == null || ALLOWED_STYLES.stream().noneMatch(s -> s.equalsIgnoreCase(requestedStyle))) {
            style = "illustration";
        }

        if (sdApiType == null || sdServer == null) {
            throw new PictureBookException(500, "SD server not configured");
        }

        String sceneText = scene.get("text");
        Map<String, Object> sceneData = sceneText != null ? parseLlmJsonObject(sceneText) : new LinkedHashMap<>();
        String setting = (String) sceneData.getOrDefault("setting", "");
        String action  = (String) sceneData.getOrDefault("action", "");
        String mood    = (String) sceneData.getOrDefault("mood", "");

        BaseRecord chatConfig = null;
        if (params.chatConfigName != null)
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, params.chatConfigName, null);

        BaseRecord sdConfigRec;
        try {
            sdConfigRec = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
            sdConfigRec.set("steps", params.steps);
            sdConfigRec.set("refinerSteps", params.refinerSteps);
            sdConfigRec.set("cfg", params.cfg);
            sdConfigRec.set("hires", params.hires);
            sdConfigRec.set("seed", params.seed);
            if (params.sdModelName != null && !params.sdModelName.isEmpty()) sdConfigRec.set("model", params.sdModelName);
            if (params.sdRefinerModelName != null && !params.sdRefinerModelName.isEmpty()) sdConfigRec.set("refinerModel", params.sdRefinerModelName);
            if (params.denoisingStrength >= 0) sdConfigRec.set("denoisingStrength", params.denoisingStrength);
            if (params.sdSampler != null && !params.sdSampler.isEmpty()) sdConfigRec.set("sampler", params.sdSampler);
            if (params.sdScheduler != null && !params.sdScheduler.isEmpty()) sdConfigRec.set("scheduler", params.sdScheduler);
            if (params.sdRefinerSampler != null && !params.sdRefinerSampler.isEmpty()) sdConfigRec.set("refinerSampler", params.sdRefinerSampler);
            if (params.sdRefinerScheduler != null && !params.sdRefinerScheduler.isEmpty()) sdConfigRec.set("refinerScheduler", params.sdRefinerScheduler);
            if (params.sdLoras != null && !params.sdLoras.isEmpty()) sdConfigRec.set("loras", params.sdLoras);
            if (style != null && !style.isEmpty()) sdConfigRec.set("style", style);
            if (params.useKontext != null) sdConfigRec.set("useKontext", params.useKontext);
            if (params.sceneCreativity != null) sdConfigRec.set("sceneCreativity", params.sceneCreativity);
        } catch (Exception e) {
            logger.error("Failed to create sdConfig: " + e.getMessage());
            throw new PictureBookException(500, "SD config error");
        }

        String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
        if (sceneGroupPath == null) sceneGroupPath = "~/Chat";
        SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType), sdServer);

        // Mark generation started — persisted so the wizard's progress survives a reload
        // (see listScenes()'s status/error merge and .claude/rules/model-api.md's PATCH pattern).
        updateSceneStatus(user, scene, "generating", null);

        // Auto-capture these settings on the book so images can be recreated with the same
        // settings later (see persistBookSdConfig) — only for real book scenes (under .../Scenes),
        // not the ~/Chat single-image fallback which has no book meta to attach settings to.
        if (sceneGroupPath.endsWith("/Scenes")) {
            String bookGroupPath = sceneGroupPath.substring(0, sceneGroupPath.length() - "/Scenes".length());
            persistBookSdConfig(user, bookGroupPath, sdConfigRec);
        }

        // promptOverride: skip pipeline, direct SDXL generation
        if (params.promptOverride != null && !params.promptOverride.isEmpty()) {
            // No LLM call in this branch (the caller supplied the prompt directly), but flush
            // defensively before the SD call anyway — cheap no-op if nothing is tracked as loaded.
            OllamaModelUtil.unloadAll();
            try {
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Generating image...");
                sdConfigRec.set("description", params.promptOverride);
                // Flow the user-selected style through (defaults to "illustration" when absent)
                sdConfigRec.set("style", style);
                String imageName = "scene_" + sceneObjectId + "_" + System.currentTimeMillis();
                List<BaseRecord> images = sdu.createImage(user, sceneGroupPath, sdConfigRec, imageName, 1, params.hires, -1);
                if (images == null || images.isEmpty())
                    throw new PictureBookException(500, "SD generation failed");
                BaseRecord image = images.get(0);
                String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);
                // Must go through ByteModelUtil — raw .get() bypasses decompression/decryption
                // (see ByteModelUtil.getValue(); data.data inherits crypto.cryptoByteStore).
                ByteModelUtil.getValue(image);
                IOSystem.getActiveContext().getAccessPoint().member(user, scene, image, null, true);
                updateSceneImageId(user, scene, imageOid);
                updateSceneStatus(user, scene, "done", null);
                BaseRecord genResult = buildResult();
                genResult.set("imageObjectId", imageOid);
                genResult.set("prompt", params.promptOverride);
                genResult.set("seed", extractSeedFromImage(image));
                return genResult;
            } catch (PictureBookException pbe) {
                updateSceneStatus(user, scene, "error", pbe.getMessage());
                throw pbe;
            } catch (Exception e) {
                logger.error("Override SD generation failed: " + e.getMessage());
                updateSceneStatus(user, scene, "error", e.getMessage());
                throw new PictureBookException(500, e.getMessage());
            } finally {
                // Clear the activity bar on ALL exits (early 500, success, catch 500)
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
            }
        }

        try {
            // Stage 0: Resolve (and cache) the landscape prompt BEFORE any SD calls — keeps every
            // LLM call ahead of every GPU-heavy SD call so the model can be unloaded once instead
            // of sitting loaded in VRAM across the whole portrait/landscape/composite sequence.
            String landscapePrompt = resolveLandscapePrompt(user, scene, chatConfig, setting, mood, style, params.promptTemplateOverride);
            OllamaModelUtil.unloadAll();

            // Stage 1: Portrait bytes for up to 2 scene characters
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "face", "Generating portraits...");
            // Characters may be stored as [{name:...}] maps or as objectId strings
            List<byte[]> portraitBytesList = new ArrayList<>();
            List<String> portraitPromptList = new ArrayList<>();
            // Persist+link+reuse portraits only for real books; the caller drives this
            // explicitly via isBook (default true) rather than inferring intent from scene
            // group path text. false selects the legacy ~/Chat fallback render-use-delete
            // behavior so portraits are not scattered/orphaned outside a book.
            boolean isBook = (params.isBookOverride != null) ? params.isBookOverride : true;
            List<String> failedPortraits = new ArrayList<>();
            Object charsObj = sceneData.get("characters");
            if (charsObj instanceof List) {
                List<Object> charItems = (List<Object>) charsObj;
                for (Object charItem : charItems) {
                    if (portraitBytesList.size() >= 2) break;
                    String cname = null;
                    String charOid = null;
                    if (charItem instanceof Map) {
                        Map<String, Object> cmap = (Map<String, Object>) charItem;
                        cname = (String) cmap.get("name");
                    } else if (charItem instanceof String) {
                        charOid = (String) charItem;
                    }

                    BaseRecord cp = null;
                    try {
                        if (charOid != null) {
                            Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
                            cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                            cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile"});
                            cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
                        } else if (cname != null) {
                            // Search by name — scope to scene's parent group's Characters/ sibling
                            String charGroupPath = sceneGroupPath.replace("/Scenes", "/Characters");
                            BaseRecord charGrp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                                    ModelNames.MODEL_GROUP, charGroupPath, GroupEnumType.DATA.toString(),
                                    (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
                            if (charGrp != null) {
                                Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, cname);
                                cq.field(FieldNames.FIELD_GROUP_ID, charGrp.get(FieldNames.FIELD_ID));
                                cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                                cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile"});
                                cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to find character: " + (cname != null ? cname : charOid) + ": " + e.getMessage());
                        continue;
                    }

                    if (cp == null) continue;
                    if (cname == null) cname = cp.get(FieldNames.FIELD_NAME);
                    // narrative is a foreign model (olio.narrative) — read sdPrompt from it
                    String portraitPrompt2 = null;
                    BaseRecord cpNarrative = cp.get("narrative");
                    if (cpNarrative != null) {
                        portraitPrompt2 = cpNarrative.get("sdPrompt");
                        if (portraitPrompt2 == null || portraitPrompt2.isBlank()) {
                            portraitPrompt2 = cpNarrative.get("physicalDescription");
                        }
                        // The charPerson query above requests the bare "narrative" field name
                        // (no nested dot-path / plan), which — per .claude/rules/model-api.md —
                        // only returns the foreign model's default query fields. olio.narrative's
                        // "query" array is just ["id","groupId"], so sdPrompt/physicalDescription
                        // come back null here even though they are persisted in the DB. Mirror the
                        // profile->portrait two-step populate() pattern used just below: explicitly
                        // re-populate the narrative record itself for the fields actually needed.
                        if (portraitPrompt2 == null || portraitPrompt2.isBlank()) {
                            try {
                                IOSystem.getActiveContext().getReader().populate(cpNarrative, new String[] { "sdPrompt", "physicalDescription" });
                                portraitPrompt2 = cpNarrative.get("sdPrompt");
                                if (portraitPrompt2 == null || portraitPrompt2.isBlank()) {
                                    portraitPrompt2 = cpNarrative.get("physicalDescription");
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to populate narrative sdPrompt/physicalDescription for " + cname + ": " + e.getMessage());
                            }
                        }
                    }
                    if (portraitPrompt2 == null || portraitPrompt2.isBlank()) {
                        logger.warn("No portrait prompt (narrative) for: " + cname + " — skipping portrait");
                        continue;
                    }

                    // B1: Populate the character's profile + portrait (with byteStore) so we can
                    // reuse an already-persisted portrait rather than regenerating it every scene.
                    BaseRecord profile = cp.get("profile");
                    if (profile == null) {
                        try {
                            IOSystem.getActiveContext().getReader().populate(cp, new String[] { "profile" });
                            profile = cp.get("profile");
                        } catch (Exception e) {
                            logger.warn("Failed to populate profile for " + cname + ": " + e.getMessage());
                        }
                    }
                    byte[] existingPortraitBytes = null;
                    if (profile != null) {
                        try {
                            IOSystem.getActiveContext().getReader().populate(profile, new String[] { "portrait" });
                            BaseRecord existingPortrait = profile.get("portrait");
                            if (existingPortrait != null) {
                                IOSystem.getActiveContext().getReader().populate(existingPortrait, new String[] { FieldNames.FIELD_BYTE_STORE });
                                // Must go through ByteModelUtil — a raw .get() bypasses
                                // decompression/decryption (see ByteModelUtil.getValue()).
                                existingPortraitBytes = ByteModelUtil.getValue(existingPortrait);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to populate portrait for " + cname + ": " + e.getMessage());
                        }
                    }

                    // Reuse branch: a book character with a persisted portrait — no re-render.
                    if (isBook && existingPortraitBytes != null && existingPortraitBytes.length > 0) {
                        portraitBytesList.add(existingPortraitBytes);
                        portraitPromptList.add(SWUtil.stripSDXLWeighting(portraitPrompt2));
                        logger.info("Reusing persisted portrait for " + cname + " (no re-render)");
                        continue;
                    }

                    try {
                        // Portrait inherits user's SD config (model, sampler, scheduler) but forces hires=false
                        BaseRecord portCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
                        portCfg.set("steps", params.steps);
                        portCfg.set("cfg", params.cfg);
                        portCfg.set("hires", false);
                        portCfg.set("seed", params.seed);
                        portCfg.set("description", portraitPrompt2);
                        portCfg.set("negativePrompt", NEG_PROMPT);
                        if (params.sdModelName != null && !params.sdModelName.isEmpty()) portCfg.set("model", params.sdModelName);
                        if (params.sdSampler != null && !params.sdSampler.isEmpty()) portCfg.set("sampler", params.sdSampler);
                        if (params.sdScheduler != null && !params.sdScheduler.isEmpty()) portCfg.set("scheduler", params.sdScheduler);
                        String portName = "portrait_" + cname.replace(" ", "_") + "_" + System.currentTimeMillis();
                        // Render book portraits into the book's Characters/ group (not the Scenes group);
                        // the fallback (~/Chat) renders in place and is deleted below.
                        String portraitGroupPath = isBook ? sceneGroupPath.replace("/Scenes", "/Characters") : sceneGroupPath;
                        List<BaseRecord> portImages = sdu.createImage(user, portraitGroupPath, portCfg, portName, 1, false, -1);
                        if (portImages == null || portImages.isEmpty()) { logger.warn("Portrait generation failed: " + cname); continue; }
                        // Must go through ByteModelUtil — raw .get() bypasses decompression/decryption.
                        byte[] portBytes = ByteModelUtil.getValue(portImages.get(0));
                        if (portBytes == null || portBytes.length == 0) {
                            // Unusable image — delete regardless of book/fallback
                            try { IOSystem.getActiveContext().getAccessPoint().delete(user, portImages.get(0)); } catch (Exception ignored) {}
                            continue;
                        }
                        portraitBytesList.add(portBytes);
                        portraitPromptList.add(SWUtil.stripSDXLWeighting(portraitPrompt2));

                        if (isBook) {
                            // Persist+link: attach the rendered portrait to the character via a
                            // PBAC-safe partial identity.profile update (id + portrait only) — do NOT
                            // re-persist the full charPerson graph (avoids groupless denial).
                            BaseRecord newImage = portImages.get(0);
                            try {
                                Long profIdObj = (profile != null) ? profile.get(FieldNames.FIELD_ID) : null;
                                long profId = (profIdObj != null) ? profIdObj.longValue() : 0L;
                                // Tracks whichever profile record is actually live/loaded and about to
                                // receive the portrait FK — mirrors SDUtil.generateSDImages's `prof`
                                // variable (the already-loaded profile fetched off the person).
                                BaseRecord effectiveProfile = profile;

                                if (profId <= 0L) {
                                    // No usable profile id — this character predates the createCharPerson()
                                    // fix that persists a real profile up-front. Resolve/create one now
                                    // rather than leaving the rendered portrait silently unlinked.
                                    BaseRecord newProfile = createPersistedForeignInstance(user, ModelNames.MODEL_PROFILE);
                                    if (newProfile != null) {
                                        BaseRecord linked = patchCharPersonField(user, cp, "profile", newProfile);
                                        if (linked != null) {
                                            Long newIdObj = newProfile.get(FieldNames.FIELD_ID);
                                            profId = (newIdObj != null) ? newIdObj.longValue() : 0L;
                                            effectiveProfile = newProfile;
                                            logger.info("Resolved missing profile for " + cname + " (new profile id " + profId + ")");
                                        } else {
                                            logger.error("Failed to link newly-created profile to charPerson " + cname);
                                        }
                                    } else {
                                        logger.error("Failed to create a replacement profile for " + cname);
                                    }
                                }

                                if (profId > 0L && effectiveProfile != null) {
                                    // Mirrors SDUtil.generateSDImages's `prof.setValue("portrait", ...)` —
                                    // mutate the already-loaded profile record, then derive the minimal
                                    // patch via copyRecord(fieldNames) (same idiom as
                                    // NarrativeUtil.getCreateNarrative/RecordUtil.patch's
                                    // targ.copyRecord(upf)) instead of hand-building a bare
                                    // RecordFactory.newInstance(MODEL_PROFILE) patch. Still goes through
                                    // AccessPoint.update() directly (not the shared static
                                    // Queue/Queue.processQueue(user)) so PBAC is respected AND a
                                    // null/failure return is directly detectable here for failedPortraits —
                                    // see .claude/rules/model-api.md PATCH rules and this class's javadoc.
                                    effectiveProfile.set("portrait", newImage);
                                    BaseRecord profilePatch = effectiveProfile.copyRecord(
                                            new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "portrait" });
                                    BaseRecord portraitLinked = IOSystem.getActiveContext().getAccessPoint().update(user, profilePatch);
                                    if (portraitLinked == null) {
                                        logger.error("Failed to link portrait to character " + cname + " — AccessPoint.update denied or failed (profile id " + profId + ")");
                                        failedPortraits.add(cname);
                                    } else {
                                        logger.info("Persisted+linked portrait for " + cname + " (profile id " + profId + ")");
                                    }
                                } else {
                                    // Fail loudly rather than leave a silently-unlinked portrait.
                                    logger.error("Character " + cname + " has no persisted profile id even after resolution — portrait kept in group but left unlinked",
                                            new IllegalStateException("unresolved profile for " + cname));
                                    failedPortraits.add(cname);
                                }
                            } catch (Exception e) {
                                logger.error("Failed to link portrait to character " + cname + ": " + e.getMessage(), e);
                                failedPortraits.add(cname);
                            }
                        } else {
                            // Legacy fallback: not a book — image is only used for the composite, delete it
                            try { IOSystem.getActiveContext().getAccessPoint().delete(user, portImages.get(0)); } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        logger.error("Portrait generation error for " + cname + ": " + e.getMessage(), e);
                        failedPortraits.add(cname);
                    }
                }
            }
            logger.info("Stage 1 complete: " + portraitBytesList.size() + " portraits generated");

            // Stage 2: Landscape generation — prompt was already resolved (LLM or cache) in Stage 0
            // above, before the model was unloaded, so this is pure SD work.
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "landscape", "Generating landscape...");
            SWTxt2Img landReq = SWUtil.newSceneTxt2Img(landscapePrompt, NEG_PROMPT, sdConfigRec);
            landReq.setWidth(1024);
            landReq.setHeight(768);
            List<BaseRecord> landImages = sdu.createSceneImage(user, sceneGroupPath,
                    "landscape_" + sceneObjectId + "_" + System.currentTimeMillis(), landReq, null, null);
            if (landImages == null || landImages.isEmpty())
                throw new PictureBookException(500, "Landscape generation failed");
            BaseRecord landscapeImage = landImages.get(0);
            // Must go through ByteModelUtil — raw .get() bypasses decompression/decryption.
            byte[] landscapeBytes = ByteModelUtil.getValue(landscapeImage);
            if (landscapeBytes == null || landscapeBytes.length == 0)
                throw new PictureBookException(500, "Empty landscape image");
            // Retain the persisted landscape record (previously deleted immediately after use,
            // which meant only the final composite ever survived) and record its objectId on the
            // scene so it is discoverable/reusable like the composite.
            String landscapeOid = landscapeImage.get(FieldNames.FIELD_OBJECT_ID);
            updateSceneLandscapeId(user, scene, landscapeOid);

            // Stage 3/4: Composite scene — branch between Kontext (stitch-and-prompt) and classic
            // (Graphics2D composite + SDXL img2img) pipelines. Unlike ChatService.generateScene
            // (which defaults useKontext=true), PictureBook defaults to the CLASSIC pipeline —
            // live E2E visual comparison (TestPictureBookUtilE2E diagnostic run, see git history)
            // showed Kontext reliably returns a technically-valid image that does NOT preserve
            // character likeness (wrong hair color/face — Kontext "succeeds" so the
            // empty-result-only fallback below never triggers), while the classic pipeline's
            // Graphics2D-drawn real portrait pixels visibly preserve likeness (confirmed by
            // Stephen + coordinator independently inspecting the emitted composites/portraits).
            // Kontext remains available as an explicit opt-in (params.useKontext = true) since it
            // is still faster/lighter-weight when likeness fidelity matters less.
            String leftDesc  = !portraitPromptList.isEmpty() ? portraitPromptList.get(0) : "";
            String rightDesc = portraitPromptList.size() > 1  ? portraitPromptList.get(1) : "";
            byte[] leftBytes   = !portraitBytesList.isEmpty() ? portraitBytesList.get(0) : null;
            byte[] centerBytes = portraitBytesList.size() > 1  ? portraitBytesList.get(1) : null;

            boolean useKontext = (params.useKontext != null) ? params.useKontext : false;
            // Kontext 2-pass needs moderate creativity — enough to restructure panels while
            // preserving faces; classic img2img needs more room to blend the drawn-on portraits.
            double sceneCreativity = useKontext ? 0.65 : 0.85;
            if (params.sceneCreativity != null) sceneCreativity = params.sceneCreativity;

            String sceneName = "scene_" + sceneObjectId + "_" + System.currentTimeMillis();
            List<BaseRecord> finalImages = new ArrayList<>();

            if (useKontext) {
                // KONTEXT PIPELINE: stitch [portrait1 | portrait2 | landscape] into one composite
                // reference image, hand it to Flux Kontext as a single promptImage.
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome_mosaic", "Stitching reference...");
                byte[] stitchLeft   = leftBytes != null ? leftBytes : landscapeBytes;
                byte[] stitchCenter = centerBytes != null ? centerBytes : landscapeBytes;
                byte[] refComposite = SDUtil.stitchSceneImages(stitchLeft, stitchCenter, landscapeBytes, 1024);

                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Compositing scene...");
                // Thread the caller's actual requested steps/cfg/negative-prompt through to Kontext
                // instead of letting it silently ignore them (every other stage — portraits,
                // landscape, classic pipeline — already respects params.steps/params.cfg/NEG_PROMPT).
                SWTxt2Img kontextReq = SWUtil.newKontextSceneTxt2Img(leftDesc, rightDesc, action, setting, style, mood,
                        sdConfigRec, params.steps, params.cfg, NEG_PROMPT);
                if (refComposite != null) {
                    List<String> promptImages = new ArrayList<>();
                    promptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(refComposite));
                    kontextReq.setPromptImages(promptImages);
                }
                finalImages = sdu.createSceneImage(user, sceneGroupPath, sceneName, kontextReq, null, null);
                if (finalImages == null || finalImages.isEmpty()) {
                    logger.warn("generateSceneImage: Kontext pipeline produced no images — falling back to classic");
                    useKontext = false;
                }
            }

            if (!useKontext) {
                // CLASSIC PIPELINE: literally draw the real portrait pixels onto the landscape
                // canvas via Graphics2D (SDUtil.compositeSceneCanvas), then run SDXL img2img at a
                // controlled creativity/denoise strength — the real portrait pixels are physically
                // present in the input before refinement, which is what actually preserves identity.
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Compositing scene...");
                StringBuilder classicPrompt = new StringBuilder();
                if (!leftDesc.isEmpty()) classicPrompt.append(leftDesc).append(". ");
                if (!rightDesc.isEmpty()) classicPrompt.append(rightDesc).append(". ");
                if (!action.isEmpty()) classicPrompt.append("They are ").append(action).append(". ");
                if (!setting.isEmpty()) classicPrompt.append("Setting: ").append(setting).append(". ");
                if (!mood.isEmpty()) classicPrompt.append("Mood: ").append(mood).append(". ");
                classicPrompt.append(SWUtil.styleClause(style));

                SWTxt2Img classicReq = SWUtil.newSceneTxt2Img(classicPrompt.toString(), NEG_PROMPT, sdConfigRec);
                byte[] compositeBytes = SDUtil.compositeSceneCanvas(landscapeBytes, leftBytes, centerBytes,
                        classicReq.getWidth(), classicReq.getHeight());
                if (compositeBytes != null) {
                    classicReq.setInitImage("data:image/png;base64," + Base64.getEncoder().encodeToString(compositeBytes));
                    classicReq.setInitImageCreativity(sceneCreativity);
                }
                finalImages = sdu.createSceneImage(user, sceneGroupPath, sceneName, classicReq, null, null);
            }

            if (finalImages == null || finalImages.isEmpty())
                throw new PictureBookException(500, "Scene composite generation failed");
            BaseRecord finalImage = finalImages.get(0);
            String finalImageOid = finalImage.get(FieldNames.FIELD_OBJECT_ID);
            // Must go through ByteModelUtil — raw .get() bypasses decompression/decryption.
            ByteModelUtil.getValue(finalImage);
            IOSystem.getActiveContext().getAccessPoint().member(user, scene, finalImage, null, true);
            updateSceneImageId(user, scene, finalImageOid);
            updateSceneStatus(user, scene, "done", null);
            String compositePrompt = action + " " + setting;
            BaseRecord genResult = buildResult();
            genResult.set("imageObjectId", finalImageOid);
            genResult.set("prompt", compositePrompt);
            genResult.set("seed", extractSeedFromImage(finalImage));
            if (!failedPortraits.isEmpty()) {
                genResult.set("failedPortraits", failedPortraits);
            }
            return genResult;
        } catch (PictureBookException pbe) {
            updateSceneStatus(user, scene, "error", pbe.getMessage());
            throw pbe;
        } catch (Exception e) {
            logger.error("Scene image generation pipeline failed: " + e.getMessage(), e);
            updateSceneStatus(user, scene, "error", e.getMessage());
            throw new PictureBookException(500, e.getMessage());
        } finally {
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        }
    }

    /**
     * Batch-resolve (and cache) the landscape prompt for every listed scene, then flush idle
     * Ollama models ONCE — so a multi-scene "Generate All" run does all of its LLM calls up front
     * instead of interleaving one LLM call per scene between rounds of GPU-heavy SD calls (which
     * keeps a model like a large gpt-oss variant resident in VRAM for the whole batch). Each
     * subsequent generateSceneImage() call picks up the cached prompt automatically (see
     * resolveLandscapePrompt) and skips its own LLM call. Per-scene failures are logged and
     * skipped — a scene that can't get an LLM-generated prompt still falls back to its setting
     * text (same behavior as a live call), so this never blocks the batch.
     */
    public static void prepareSceneImagePrompts(BaseRecord user, List<String> sceneObjectIds,
            String chatConfigName, String style, String promptTemplateOverride) {
        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }
        final String requestedStyle = style;
        String effectiveStyle = (requestedStyle == null || ALLOWED_STYLES.stream().noneMatch(s -> s.equalsIgnoreCase(requestedStyle)))
                ? "illustration" : requestedStyle;
        for (String sceneObjectId : sceneObjectIds) {
            try {
                Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
                sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                sq.planMost(false);
                BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
                if (scene == null) {
                    logger.warn("prepareSceneImagePrompts: scene not found: " + sceneObjectId);
                    continue;
                }
                String sceneText = scene.get("text");
                Map<String, Object> sceneData = sceneText != null ? parseLlmJsonObject(sceneText) : new LinkedHashMap<>();
                String setting = (String) sceneData.getOrDefault("setting", "");
                String mood = (String) sceneData.getOrDefault("mood", "");
                resolveLandscapePrompt(user, scene, chatConfig, setting, mood, effectiveStyle, promptTemplateOverride);
            } catch (Exception e) {
                logger.warn("prepareSceneImagePrompts: failed for scene " + sceneObjectId + ": " + e.getMessage());
            }
        }
        OllamaModelUtil.unloadAll();
    }

    /**
     * Regenerate scene blurb via LLM. Updates data.note.text (blurb key), returns the
     * pictureBookResult carrying the new blurb.
     */
    public static BaseRecord regenerateBlurb(BaseRecord user, String sceneObjectId, String chatConfigName) {
        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        sq.planMost(false);
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        if (scene == null) throw new PictureBookException(404, "Scene not found");

        String sceneText = scene.get("text");
        Map<String, Object> sceneData = sceneText != null ? parseLlmJsonObject(sceneText) : new LinkedHashMap<>();
        String title = (String) sceneData.getOrDefault("title", scene.get(FieldNames.FIELD_NAME));
        String setting = (String) sceneData.getOrDefault("setting", "");
        String action = (String) sceneData.getOrDefault("action", "");
        String charList = "";
        Object charsObj = sceneData.get("characters");
        if (charsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cs = (List<Map<String, Object>>) charsObj;
            List<String> names = new ArrayList<>();
            for (Map<String, Object> c : cs) {
                String cname = (String) c.get("name");
                if (cname != null) names.add(cname);
            }
            charList = String.join(", ", names);
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", title != null ? title : "");
        vars.put("setting", setting);
        vars.put("action", action);
        vars.put("characterList", charList);
        String blurb = callLlm(user, chatConfig, "pictureBook.scene-blurb", vars);
        if (blurb == null || blurb.isEmpty()) {
            throw new PictureBookException(500, "Blurb generation failed");
        }

        try {
            // data.note has no 'description' field — store blurb in the text JSON blob
            String existingText = scene.get("text");
            Map<String, Object> textData = new LinkedHashMap<>();
            if (existingText != null && !existingText.isEmpty()) {
                try {
                    textData = JSONUtil.getMap(existingText.getBytes(), String.class, Object.class);
                } catch (Exception ex) { /* ignore parse errors */ }
            }
            textData.put("blurb", blurb.trim());
            scene.set("text", JSONUtil.exportObject(textData));
            IOSystem.getActiveContext().getAccessPoint().update(user, scene);
        } catch (Exception e) {
            logger.error("Failed to update scene blurb: " + e.getMessage());
            throw new PictureBookException(500, "Failed to save blurb");
        }

        BaseRecord blurbResult = buildResult();
        try {
            blurbResult.set("blurb", blurb.trim());
        } catch (Exception e) {
            // Unreachable in practice for a valid olio.pictureBookResult schema — logged rather
            // than silently swallowed.
            logger.warn("Failed to set blurb field on result record: " + e.getMessage());
        }
        OllamaModelUtil.unloadAll();
        return blurbResult;
    }

    /**
     * Returns the ordered scene list from .pictureBookMeta (merging any live blurb/imageObjectId
     * edits from each scene note), or an empty list if no meta/scenes exist yet.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listScenes(BaseRecord user, String bookObjectId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);

        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec == null) {
            return new ArrayList<>();
        }

        String metaJson = metaRec.get("text");
        if (metaJson == null || metaJson.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
            Object scenesObj = meta.get("scenes");
            if (scenesObj == null) return new ArrayList<>();

            // Merge current blurb from each scene note into the meta's description field
            // so blurb edits persist across page reloads
            if (scenesObj instanceof List) {
                List<Map<String, Object>> scenesList = (List<Map<String, Object>>) scenesObj;
                for (Map<String, Object> scene : scenesList) {
                    String sceneOid = (String) scene.get("objectId");
                    if (sceneOid == null) continue;
                    try {
                        Query noteQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneOid);
                        noteQ.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                        noteQ.planMost(true);
                        BaseRecord sceneNote = IOSystem.getActiveContext().getAccessPoint().find(user, noteQ);
                        if (sceneNote != null) {
                            String text = sceneNote.get("text");
                            if (text != null && !text.isEmpty()) {
                                Map<String, Object> textData = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
                                String blurb = (String) textData.get("blurb");
                                if (blurb != null && !blurb.isEmpty()) {
                                    scene.put("description", blurb);
                                }
                                // Also merge imageObjectId if present
                                String imgOid = (String) textData.get("imageObjectId");
                                if (imgOid != null) {
                                    scene.put("imageObjectId", imgOid);
                                }
                                // Also merge generation status/error so the wizard can resume
                                // (pending/generating/done/error/accepted/skipped — see updateSceneStatus)
                                String status = (String) textData.get("status");
                                if (status != null && !status.isEmpty()) {
                                    scene.put("status", status);
                                }
                                String error = (String) textData.get("error");
                                if (error != null && !error.isEmpty()) {
                                    scene.put("error", error);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Non-fatal — scene keeps its original description
                    }
                }
                return scenesList;
            }
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("Failed to parse meta: " + e.getMessage());
            throw new PictureBookException(500, "Failed to read meta");
        }
    }

    /**
     * Reorder scenes within a book's .pictureBookMeta.
     */
    public static BaseRecord reorderScenes(BaseRecord user, String bookObjectId, List<String> newOrder) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");

        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec == null) throw new PictureBookException(404, "Meta not found");

        String metaJson = metaRec.get("text");
        try {
            BaseRecord meta = JSONUtil.importObject(metaJson, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
            if (meta == null) throw new PictureBookException(400, "Failed to parse meta");

            @SuppressWarnings("unchecked")
            List<BaseRecord> scenes = meta.get("scenes");
            if (scenes == null || scenes.isEmpty()) throw new PictureBookException(400, "No scenes in meta");

            // Rebuild list in new order
            List<BaseRecord> reordered = new ArrayList<>();
            for (int i = 0; i < newOrder.size(); i++) {
                final String oid = newOrder.get(i);
                final int newIdx = i;
                scenes.stream()
                        .filter(s -> oid.equals(s.get("objectId")))
                        .findFirst()
                        .ifPresent(s -> {
                            try { s.set("index", newIdx); } catch (Exception ex) { /* ignore */ }
                            reordered.add(s);
                        });
            }
            meta.set("scenes", reordered);
            saveMeta(user, bookGroupPath, meta);

            BaseRecord result = buildResult();
            result.set("reordered", true);
            return result;
        } catch (PictureBookException pbe) {
            throw pbe;
        } catch (Exception e) {
            logger.error("Failed to reorder scenes: " + e.getMessage());
            throw new PictureBookException(500, e.getMessage());
        }
    }

    /**
     * Delete the book group contents (Scenes/, Characters/, meta) then the group itself.
     * Explicit child deletion — AccessPoint.delete on a group does NOT cascade.
     */
    public static boolean reset(BaseRecord user, String bookObjectId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");

        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        boolean ok = true;

        // Delete sub-groups (Scenes/, Characters/) and their contents
        for (String sub : new String[]{"Scenes", "Characters"}) {
            String subPath = bookGroupPath + "/" + sub;
            BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                    ModelNames.MODEL_GROUP, subPath, GroupEnumType.DATA.toString(),
                    (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
            if (grp != null) {
                try {
                    IOSystem.getActiveContext().getAccessPoint().delete(user, grp);
                } catch (Exception e) {
                    logger.warn("Failed to delete " + sub + " group: " + e.getMessage());
                    ok = false;
                }
            }
        }

        // Delete .pictureBookMeta record
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec != null) {
            try {
                IOSystem.getActiveContext().getAccessPoint().delete(user, metaRec);
            } catch (Exception e) {
                logger.warn("Failed to delete meta: " + e.getMessage());
            }
        }

        // Delete the book group itself
        try {
            IOSystem.getActiveContext().getAccessPoint().delete(user, bookGroup);
        } catch (Exception e) {
            logger.warn("Failed to delete book group: " + e.getMessage());
            ok = false;
        }

        return ok;
    }
}
