package org.cote.rest.services;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;
import org.cote.sockets.WebSocketService;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * PictureBookService — REST service for generating illustrated picture books from documents.
 * Auto-registered via RestServiceConfig packages("org.cote.rest.services").
 *
 * Endpoints under /olio/picture-book:
 *   POST /{workObjectId}/extract              — Full LLM extraction: scenes + characters → creates ~/PictureBooks/{bookName}/
 *   POST /{workObjectId}/extract-scenes-only  — Scene extraction only (no character creation)
 *   POST /scene/{sceneObjectId}/generate      — Generate SD image for one scene
 *   POST /scene/{sceneObjectId}/blurb         — Regenerate scene blurb via LLM
 *   GET  /{bookObjectId}/scenes               — Ordered scene list from .pictureBookMeta (bookObjectId = book group objectId)
 *   PUT  /{bookObjectId}/scenes/order         — Reorder scenes
 *   DELETE /{bookObjectId}/reset              — Delete entire book group
 */
@DeclareRoles({"admin", "user"})
@Path("/olio/picture-book")
public class PictureBookService {

    private static final Logger logger = LogManager.getLogger(PictureBookService.class);

    // SD generation defaults — enforced unless pictureBook.hq feature flag is true
    private static final int DEFAULT_STEPS = 20;
    private static final int DEFAULT_REFINER_STEPS = 20;
    private static final int DEFAULT_CFG = 5;
    private static final boolean DEFAULT_HIRES = false;

    // Default scene count when not specified — LLM decides actual count
    private static final int MAX_SCENES_DEFAULT = 10;

    private static final String NEG_PROMPT =
        "blurry, lowres, bad anatomy, extra limbs, watermark, text, logo, cartoon, anime, nsfw, deformed, disfigured, ugly, duplicate, mutated, out of frame";

    // Genre → SD theme mapping
    private static final Map<String, String> GENRE_THEME_MAP = new HashMap<>();
    static {
        GENRE_THEME_MAP.put("fantasy", "dark-medieval");
        GENRE_THEME_MAP.put("sci-fi", "sci-fi");
        GENRE_THEME_MAP.put("contemporary", "modern");
        GENRE_THEME_MAP.put("historical", "period");
    }

    // ----- Helpers -------------------------------------------------------

    private static final String PB_REQUEST_SCHEMA = "olio.pictureBookRequest";
    private static final String PICTURE_BOOKS_DIR = "PictureBooks";

    /**
     * Ensure the JSON body has a schema field so the deserializer can parse it.
     */
    private String ensureSchema(String json) {
        if (json == null) return null;
        OlioModelNames.use();
        String trimmed = json.trim();
        if (trimmed.contains("\"schema\"")) return json;
        if (trimmed.startsWith("{")) {
            return "{\"schema\":\"" + PB_REQUEST_SCHEMA + "\"," + trimmed.substring(1);
        }
        return json;
    }

    /**
     * Resolve the work record (source document) from its objectId.
     */
    private BaseRecord findWork(BaseRecord user, String workObjectId) {
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
     * Find the ~/PictureBooks/ parent directory, creating it if needed.
     */
    private BaseRecord ensurePictureBooksDir(BaseRecord user) {
        long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
        return IOSystem.getActiveContext().getPathUtil().makePath(user,
                ModelNames.MODEL_GROUP, "~/Data/" + PICTURE_BOOKS_DIR, GroupEnumType.DATA.toString(), orgId);
    }

    /**
     * Find or create a named book group under ~/PictureBooks/{bookName}/.
     */
    private BaseRecord ensureBookGroup(BaseRecord user, String bookName) {
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
    private BaseRecord findBookGroup(BaseRecord user, String bookGroupObjectId) {
        Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID, bookGroupObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        return IOSystem.getActiveContext().getAccessPoint().find(user, q);
    }

    /**
     * Extract text from a work record. Uses DocumentUtil.getStringContent for PDF/DOCX/text,
     * falling back to description/text fields for plain records.
     */
    private String extractWorkText(BaseRecord user, BaseRecord work) {
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
    private BaseRecord ensureSubGroup(BaseRecord user, String parentGroupPath, String subName) {
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
    private BaseRecord loadMeta(BaseRecord user, String groupPath) {
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
    private BaseRecord saveMeta(BaseRecord user, String groupPath, BaseRecord meta) {
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
     * Update a scene note's text JSON with the generated imageObjectId.
     * This persists the image reference so the viewer fallback can find it.
     */
    @SuppressWarnings("unchecked")
    private void updateSceneImageId(BaseRecord user, BaseRecord scene, String imageObjectId) {
        try {
            String existingText = scene.get("text");
            Map<String, Object> textData = new LinkedHashMap<>();
            if (existingText != null && !existingText.isEmpty()) {
                try {
                    textData = JSONUtil.getMap(existingText.getBytes(), String.class, Object.class);
                } catch (Exception ex) { /* ignore parse errors */ }
            }
            textData.put("imageObjectId", imageObjectId);
            scene.set("text", JSONUtil.exportObject(textData));
            IOSystem.getActiveContext().getAccessPoint().update(user, scene);
        } catch (Exception e) {
            logger.warn("Failed to update scene imageObjectId: " + e.getMessage());
        }
    }

    /**
     * Parse LLM JSON response into a list of maps, stripping markdown fences if present.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseLlmJsonArray(String response) {
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
    private Map<String, Object> parseLlmJsonObject(String response) {
        if (response == null || response.isEmpty()) return new LinkedHashMap<>();
        String trimmed = response.trim();
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
     * Call the LLM with a prompt template substitution.
     * Resolves prompt by: 1) user's ~/Chat group, 2) system library, 3) classpath resource.
     */
    /**
     * Call LLM with optional prompt template override name.
     */
    private String callLlm(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars, String overrideName) {
        if (overrideName != null && !overrideName.isEmpty()) {
            promptName = overrideName;
        }
        return callLlmInternal(user, chatConfig, promptName, vars);
    }

    private String callLlm(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
        return callLlmInternal(user, chatConfig, promptName, vars);
    }

    private String callLlmInternal(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
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
                chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, "generalChat");
            }
            if (chatConfig == null) {
                logger.error("No chat config available — cannot call LLM for " + promptName);
                return null;
            }
            Chat chat = new Chat(user, chatConfig, null);
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
            chat.newMessage(req, system, Chat.systemRole);
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
    private List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text) {
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
            WebSocketService.chirpUser(user, new String[] {
                "bgActivity", "auto_awesome", "Extracting chunk " + (ci + 1) + "/" + chunks.size() + "..."
            });
            String previousJson = sceneList.isEmpty() ? "[]" : JSONUtil.exportObject(sceneList);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("previousScenes", previousJson);
            vars.put("chunk", chunks.get(ci));
            String llmResp = callLlm(user, chatConfig, "pictureBook.extract-chunk", vars);
            if (llmResp == null || llmResp.isEmpty()) continue;
            Map<String, Object> chunkResult = parseLlmJsonObject(llmResp);

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
        WebSocketService.chirpUser(user, new String[] {
            "bgActivity", "", ""
        });
        return sceneList;
    }

    /**
     * Extract the actual seed from a generated image record's attributes.
     * SDUtil stores it as AttributeUtil.addAttribute(data, "seed", seedl).
     */
    private int extractSeedFromImage(BaseRecord image) {
        try {
            int seedVal = AttributeUtil.getAttributeValue(image, "seed", -1);
            if (seedVal > 0) return seedVal;
        } catch (Exception e) { /* attribute may not exist */ }
        return -1;
    }

    /**
     * Determine genre theme from genre hint string.
     */
    private String genreToTheme(String genre) {
        if (genre == null) return null;
        return GENRE_THEME_MAP.getOrDefault(genre.toLowerCase(), null);
    }

    /**
     * Build a pictureBookMeta record using the typed model.
     */
    private BaseRecord buildMeta(String sourceObjectId, String bookObjectId, String workName, List<BaseRecord> scenes) {
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
    private BaseRecord buildSceneEntry(BaseRecord note, Map<String, Object> sceneData, int idx, Map<String, String> charObjectIds) {
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
    private BaseRecord buildResult() {
        try {
            return RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_RESULT);
        } catch (Exception e) {
            logger.error("Failed to create result: " + e.getMessage());
            return null;
        }
    }

    /**
     * Serialize a model record to JSON for HTTP response.
     */
    private String toJson(BaseRecord rec) {
        return rec.toFullString();
    }

    /**
     * Create an olio.charPerson for an extracted character, apply outfit, call narrate.
     */
    @SuppressWarnings("unchecked")
    private BaseRecord createCharPerson(BaseRecord user, Map<String, Object> charData, BaseRecord charsGroup, String genre) {
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

            // Apply gender if available
            String gender = (String) charData.get("gender");
            if (gender != null && !gender.isEmpty()) {
                charPerson.set("gender", gender.toUpperCase());
            }

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
            try {
                BaseRecord narrative = charPerson.get("narrative");
                if (narrative == null) {
                    narrative = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE);
                    charPerson.set("narrative", narrative);
                }
                narrative.set("sdPrompt", portraitPrompt);
                narrative.set("physicalDescription", portraitPrompt);
                IOSystem.getActiveContext().getAccessPoint().update(user, charPerson);
            } catch (Exception e) {
                logger.warn("Failed to set portrait prompt for " + name + ": " + e.getMessage(), e);
            }
            return charPerson;

        } catch (Exception e) {
            logger.error("Failed to create charPerson " + name + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a data.note record for a scene.
     */
    @SuppressWarnings("unchecked")
    private BaseRecord createSceneNote(BaseRecord user, BaseRecord scenesGroup, Map<String, Object> sceneData, int idx) {
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

    // ----- Endpoints -----------------------------------------------------

    /**
     * POST /{workObjectId}/extract-scenes-only
     * Smart scene extraction — auto-chunks if text > 8000 chars.
     * Returns raw scene JSON array for client review (short text)
     * or { sceneList, extractionComplete, chunksProcessed } (chunked).
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/extract-scenes-only")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response extractScenesOnly(@PathParam("workObjectId") String workObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

        int count = MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        String promptTemplateOverride = null;
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
                Object countObj = params.get("count");
                if (countObj instanceof Number) count = ((Number) countObj).intValue();
                chatConfigName = params.get("chatConfig");
                promptTemplateOverride = params.get("promptTemplate");
            } catch (Exception e) {
                logger.warn("Failed to parse extract request body: " + e.getMessage());
            }
        }

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            return Response.status(400).entity("{\"error\":\"No text content found in work\"}").build();
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);
        }

        // Auto-chunk if text exceeds 8000 chars
        if (text.length() > 8000) {
            List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text);
            BaseRecord result = buildResult();
            try {
                result.set("sceneList", sceneList);
                result.set("extractionComplete", true);
                result.set("chunksProcessed", -1);
                result.set("chunked", true);
            } catch (Exception e) { logger.warn("Failed to build chunked result: " + e.getMessage()); }
            return Response.status(200).entity(toJson(result)).build();
        }

        // Short text — single-shot extraction
        WebSocketService.chirpUser(user, new String[] { "bgActivity", "auto_awesome", "Extracting scenes..." });
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
        WebSocketService.chirpUser(user, new String[] { "bgActivity", "", "" });

        return Response.status(200).entity(JSONUtil.exportObject(scenes,
                RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
    }

    /**
     * POST /{workObjectId}/extract-chunked
     * Chunked scene extraction — delegates to extractChunkedInternal.
     * Kept for backward compatibility; extract-scenes-only now auto-chunks.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/extract-chunked")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response extractChunked(@PathParam("workObjectId") String workObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

        String chatConfigName = null;
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                chatConfigName = params.get("chatConfig");
            } catch (Exception e) {
                logger.warn("Failed to parse chunked extract request: " + e.getMessage());
            }
        }

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            return Response.status(400).entity("{\"error\":\"No text content found\"}").build();
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);
        }

        List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text);
        BaseRecord result = buildResult();
        try {
            result.set("sceneList", sceneList);
            result.set("extractionComplete", true);
            result.set("chunksProcessed", -1);
        } catch (Exception e) { logger.warn("Failed to build chunked result: " + e.getMessage()); }
        return Response.status(200).entity(toJson(result)).build();
    }

    /**
     * POST /{workObjectId}/extract
     * Full extraction: scenes + characters + outfit + narrate.
     * Returns .pictureBookMeta JSON.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/extract")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response extract(@PathParam("workObjectId") String workObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

        int count = MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        String genre = null;
        String bookName = null;
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                Object countObj = params.get("count");
                if (countObj instanceof Number) count = ((Number) countObj).intValue();
                chatConfigName = params.get("chatConfig");
                genre = params.get("genre");
                bookName = params.get("bookName");
            } catch (Exception e) {
                logger.warn("Failed to parse extract request: " + e.getMessage());
            }
        }
        if (count > MAX_SCENES_DEFAULT) count = MAX_SCENES_DEFAULT;

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            return Response.status(400).entity("{\"error\":\"No text content found\"}").build();
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);
        }

        // Create book group under ~/PictureBooks/{bookName}/
        String effectiveBookName = (bookName != null && !bookName.isEmpty()) ? bookName : work.get(FieldNames.FIELD_NAME);
        BaseRecord bookGroup = ensureBookGroup(user, effectiveBookName);
        if (bookGroup == null) {
            return Response.status(500).entity("{\"error\":\"Failed to create book group\"}").build();
        }
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        if (bookGroupPath == null) bookGroupPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + effectiveBookName;

        // Ensure sub-groups under book group
        BaseRecord scenesGroup = ensureSubGroup(user, bookGroupPath, "Scenes");
        BaseRecord charsGroup = ensureSubGroup(user, bookGroupPath, "Characters");
        if (scenesGroup == null || charsGroup == null) {
            return Response.status(500).entity("{\"error\":\"Failed to create sub-groups\"}").build();
        }

        // Extract scenes
        WebSocketService.chirpUser(user, new String[] { "bgActivity", "auto_awesome", "Extracting scenes..." });
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
        int charIdx = 0;
        for (Map.Entry<String, Map<String, Object>> entry : uniqueChars.entrySet()) {
            String cname = entry.getKey();
            charIdx++;
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "person", "Extracting character " + charIdx + "/" + uniqueChars.size() + ": " + cname });
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
        WebSocketService.chirpUser(user, new String[] { "bgActivity", "save", "Saving book..." });
        BaseRecord meta = buildMeta(workObjectId, bookGroup.get(FieldNames.FIELD_OBJECT_ID), effectiveBookName, metaScenes);
        saveMeta(user, bookGroupPath, meta);
        WebSocketService.chirpUser(user, new String[] { "bgActivity", "", "" });

        return Response.status(200).entity(toJson(meta)).build();
    }

    /**
     * POST /{workObjectId}/create-from-scenes
     * Takes user-curated scene list from Step 2, creates book group, scene notes,
     * extracts + creates charPerson records, saves meta. Returns bookObjectId.
     * Body: { bookName, chatConfig, genre, sceneList: [...], characters: [...] }
     */
    @SuppressWarnings("unchecked")
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/create-from-scenes")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createFromScenes(@PathParam("workObjectId") String workObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

        String chatConfigName = null;
        String genre = null;
        String bookName = null;
        List<Map<String, Object>> sceneList = new ArrayList<>();
        List<Map<String, Object>> charDataList = new ArrayList<>();
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                chatConfigName = params.get("chatConfig");
                genre = params.get("genre");
                bookName = params.get("bookName");
                Object sl = params.get("sceneList");
                if (sl instanceof List) {
                    for (Object item : (List<?>) sl) {
                        if (item instanceof BaseRecord) {
                            BaseRecord r = (BaseRecord) item;
                            Map<String, Object> m = new LinkedHashMap<>();
                            for (FieldType f : r.getFields()) m.put(f.getName(), r.get(f.getName()));
                            sceneList.add(m);
                        } else if (item instanceof Map) {
                            sceneList.add((Map<String, Object>) item);
                        }
                    }
                }
                Object cl = params.get("characters");
                if (cl instanceof List) {
                    for (Object item : (List<?>) cl) {
                        if (item instanceof BaseRecord) {
                            BaseRecord r = (BaseRecord) item;
                            Map<String, Object> m = new LinkedHashMap<>();
                            for (FieldType f : r.getFields()) m.put(f.getName(), r.get(f.getName()));
                            charDataList.add(m);
                        } else if (item instanceof Map) {
                            charDataList.add((Map<String, Object>) item);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse create-from-scenes request: " + e.getMessage());
            }
        }
        if (sceneList.isEmpty()) {
            return Response.status(400).entity("{\"error\":\"sceneList is required\"}").build();
        }

        String effectiveBookName = (bookName != null && !bookName.isEmpty()) ? bookName : work.get(FieldNames.FIELD_NAME);
        BaseRecord bookGroup = ensureBookGroup(user, effectiveBookName);
        if (bookGroup == null) {
            return Response.status(500).entity("{\"error\":\"Failed to create book group\"}").build();
        }
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        if (bookGroupPath == null) bookGroupPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + effectiveBookName;

        BaseRecord scenesGroup = ensureSubGroup(user, bookGroupPath, "Scenes");
        BaseRecord charsGroup = ensureSubGroup(user, bookGroupPath, "Characters");
        if (scenesGroup == null || charsGroup == null) {
            return Response.status(500).entity("{\"error\":\"Failed to create sub-groups\"}").build();
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);
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
        int cfsCharIdx = 0;
        for (Map<String, Object> charData : charDataList) {
            String cname = (String) charData.get("name");
            if (cname == null || cname.isEmpty()) continue;
            cfsCharIdx++;
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "person", "Creating character " + cfsCharIdx + "/" + charDataList.size() + ": " + cname });

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

        WebSocketService.chirpUser(user, new String[] { "bgActivity", "save", "Saving book..." });
        BaseRecord meta = buildMeta(workObjectId, bookGroup.get(FieldNames.FIELD_OBJECT_ID), effectiveBookName, metaScenes);
        saveMeta(user, bookGroupPath, meta);
        WebSocketService.chirpUser(user, new String[] { "bgActivity", "", "" });

        return Response.status(200).entity(toJson(meta)).build();
    }

    /**
     * POST /scene/{sceneObjectId}/generate
     * Generate SD image for one scene using the 4-stage pipeline:
     *   Stage 1: SDXL portrait generation per scene character (uses narrative prompt stored on charPerson)
     *   Stage 2: SDXL landscape generation via LLM prompt
     *   Stage 3: Stitch 3-panel reference composite [portrait1 | portrait2|landscape | landscape]
     *   Stage 4: Flux Kontext composite from reference + scene description
     * Body: { chatConfig, sdConfig: {steps,refinerSteps,cfg,hires}, promptOverride }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/generate")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response generateSceneImage(@PathParam("sceneObjectId") String sceneObjectId,
            String json, @Context HttpServletRequest request, @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);

        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        sq.planMost(false);
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        if (scene == null) return Response.status(404).entity("{\"error\":\"Scene not found\"}").build();

        String chatConfigName = null;
        String promptOverride = null;
        String promptTemplateOverride = null;
        int steps = DEFAULT_STEPS;
        int refinerSteps = DEFAULT_REFINER_STEPS;
        int cfg = DEFAULT_CFG;
        boolean hires = DEFAULT_HIRES;
        int seed = -1;
        String sdModelName = null;
        String sdRefinerModelName = null;
        double denoisingStrength = -1;
        String sdSampler = null;
        String sdScheduler = null;

        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                chatConfigName = params.get("chatConfig");
                promptOverride = params.get("promptOverride");
                promptTemplateOverride = params.get("promptTemplate");
                BaseRecord sdConf = params.get("sdConfig");
                if (sdConf != null) {
                    Object sv = sdConf.get("steps"); if (sv instanceof Number) steps = ((Number) sv).intValue();
                    Object rv = sdConf.get("refinerSteps"); if (rv instanceof Number) refinerSteps = ((Number) rv).intValue();
                    Object cv = sdConf.get("cfg"); if (cv instanceof Number) cfg = ((Number) cv).intValue();
                    Object hv = sdConf.get("hires"); if (hv instanceof Boolean) hires = (Boolean) hv;
                    Object seedV = sdConf.get("seed"); if (seedV instanceof Number) seed = ((Number) seedV).intValue();
                    Object mv = sdConf.get("model"); if (mv instanceof String) sdModelName = (String) mv;
                    Object rmv = sdConf.get("refinerModel"); if (rmv instanceof String) sdRefinerModelName = (String) rmv;
                    Object dv = sdConf.get("denoisingStrength"); if (dv instanceof Number) denoisingStrength = ((Number) dv).doubleValue();
                    Object smpv = sdConf.get("sampler"); if (smpv instanceof String) sdSampler = (String) smpv;
                    Object schv = sdConf.get("scheduler"); if (schv instanceof String) sdScheduler = (String) schv;
                }
            } catch (Exception e) { logger.warn("Failed to parse generate request: " + e.getMessage()); }
        }

        String sdApiType = context.getInitParameter("sd.server.apiType");
        String sdServer  = context.getInitParameter("sd.server");
        if (sdApiType == null || sdServer == null)
            return Response.status(500).entity("{\"error\":\"SD server not configured\"}").build();

        String sceneText = scene.get("text");
        Map<String, Object> sceneData = sceneText != null ? parseLlmJsonObject(sceneText) : new LinkedHashMap<>();
        String setting = (String) sceneData.getOrDefault("setting", "");
        String action  = (String) sceneData.getOrDefault("action", "");
        String mood    = (String) sceneData.getOrDefault("mood", "");

        BaseRecord chatConfig = null;
        if (chatConfigName != null)
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);

        BaseRecord sdConfigRec;
        try {
            sdConfigRec = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
            sdConfigRec.set("steps", steps);
            sdConfigRec.set("refinerSteps", refinerSteps);
            sdConfigRec.set("cfg", cfg);
            sdConfigRec.set("hires", hires);
            sdConfigRec.set("seed", seed);
            if (sdModelName != null && !sdModelName.isEmpty()) sdConfigRec.set("model", sdModelName);
            if (sdRefinerModelName != null && !sdRefinerModelName.isEmpty()) sdConfigRec.set("refinerModel", sdRefinerModelName);
            if (denoisingStrength >= 0) sdConfigRec.set("denoisingStrength", denoisingStrength);
            if (sdSampler != null && !sdSampler.isEmpty()) sdConfigRec.set("sampler", sdSampler);
            if (sdScheduler != null && !sdScheduler.isEmpty()) sdConfigRec.set("scheduler", sdScheduler);
        } catch (Exception e) {
            logger.error("Failed to create sdConfig: " + e.getMessage());
            return Response.status(500).entity("{\"error\":\"SD config error\"}").build();
        }

        String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
        if (sceneGroupPath == null) sceneGroupPath = "~/Chat";
        SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType), sdServer);

        // promptOverride: skip pipeline, direct SDXL generation
        if (promptOverride != null && !promptOverride.isEmpty()) {
            try {
                sdConfigRec.set("description", promptOverride);
                sdConfigRec.set("style", "illustration");
                String imageName = "scene_" + sceneObjectId + "_" + System.currentTimeMillis();
                List<BaseRecord> images = sdu.createImage(user, sceneGroupPath, sdConfigRec, imageName, 1, hires, -1);
                if (images == null || images.isEmpty())
                    return Response.status(500).entity("{\"error\":\"SD generation failed\"}").build();
                BaseRecord image = images.get(0);
                String imageOid = image.get(FieldNames.FIELD_OBJECT_ID);
                IOSystem.getActiveContext().getAccessPoint().member(user, scene, image, null, true);
                updateSceneImageId(user, scene, imageOid);
                BaseRecord genResult = buildResult();
                genResult.set("imageObjectId", imageOid);
                genResult.set("prompt", promptOverride);
                genResult.set("seed", extractSeedFromImage(image));
                return Response.status(200).entity(toJson(genResult)).build();
            } catch (Exception e) {
                logger.error("Override SD generation failed: " + e.getMessage());
                return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
            }
        }

        try {
            // Stage 1: Portrait bytes for up to 2 scene characters
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "face", "Generating portraits..." });
            // Characters may be stored as [{name:...}] maps or as objectId strings
            List<byte[]> portraitBytesList = new ArrayList<>();
            List<String> portraitPromptList = new ArrayList<>();
            Object charsObj = sceneData.get("characters");
            if (charsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> charItems = (List<Object>) charsObj;
                for (Object charItem : charItems) {
                    if (portraitBytesList.size() >= 2) break;
                    String cname = null;
                    String charOid = null;
                    if (charItem instanceof Map) {
                        @SuppressWarnings("unchecked")
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
                            cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender"});
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
                                cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender"});
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
                    }
                    if (portraitPrompt2 == null || portraitPrompt2.isBlank()) {
                        logger.warn("No portrait prompt (narrative) for: " + cname + " — skipping portrait");
                        continue;
                    }
                    try {
                        // Portrait inherits user's SD config (model, sampler, scheduler) but forces hires=false
                        BaseRecord portCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
                        portCfg.set("steps", steps);
                        portCfg.set("cfg", cfg);
                        portCfg.set("hires", false);
                        portCfg.set("seed", seed);
                        portCfg.set("description", portraitPrompt2);
                        portCfg.set("negativePrompt", NEG_PROMPT);
                        if (sdModelName != null && !sdModelName.isEmpty()) portCfg.set("model", sdModelName);
                        if (sdSampler != null && !sdSampler.isEmpty()) portCfg.set("sampler", sdSampler);
                        if (sdScheduler != null && !sdScheduler.isEmpty()) portCfg.set("scheduler", sdScheduler);
                        String portName = "portrait_" + cname.replace(" ", "_") + "_" + System.currentTimeMillis();
                        List<BaseRecord> portImages = sdu.createImage(user, sceneGroupPath, portCfg, portName, 1, false, -1);
                        if (portImages == null || portImages.isEmpty()) { logger.warn("Portrait generation failed: " + cname); continue; }
                        byte[] portBytes = portImages.get(0).get(FieldNames.FIELD_BYTE_STORE);
                        if (portBytes == null || portBytes.length == 0) {
                            try { IOSystem.getActiveContext().getAccessPoint().delete(user, portImages.get(0)); } catch (Exception ignored) {}
                            continue;
                        }
                        portraitBytesList.add(portBytes);
                        portraitPromptList.add(SWUtil.stripSDXLWeighting(portraitPrompt2));
                        try { IOSystem.getActiveContext().getAccessPoint().delete(user, portImages.get(0)); } catch (Exception ignored) {}
                    } catch (Exception e) {
                        logger.warn("Portrait generation error for " + cname + ": " + e.getMessage());
                    }
                }
            }
            logger.info("Stage 1 complete: " + portraitBytesList.size() + " portraits generated");

            // Stage 2: Landscape generation
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "landscape", "Generating landscape..." });
            Map<String, String> landVars = new LinkedHashMap<>();
            landVars.put("setting", setting);
            landVars.put("mood", mood);
            landVars.put("time", "");
            landVars.put("style", "illustration");
            String landscapePrompt = callLlm(user, chatConfig, "pictureBook.landscape-prompt", landVars, promptTemplateOverride);
            if (landscapePrompt == null || landscapePrompt.isBlank()) {
                logger.warn("Landscape prompt failed — falling back to setting text");
                landscapePrompt = setting.isEmpty() ? "A detailed environment" : setting;
            }
            SWTxt2Img landReq = SWUtil.newSceneTxt2Img(landscapePrompt, NEG_PROMPT, sdConfigRec);
            landReq.setWidth(1024);
            landReq.setHeight(768);
            List<BaseRecord> landImages = sdu.createSceneImage(user, sceneGroupPath,
                    "landscape_" + sceneObjectId + "_" + System.currentTimeMillis(), landReq, null, null);
            if (landImages == null || landImages.isEmpty())
                return Response.status(500).entity("{\"error\":\"Landscape generation failed\"}").build();
            byte[] landscapeBytes = landImages.get(0).get(FieldNames.FIELD_BYTE_STORE);
            try { IOSystem.getActiveContext().getAccessPoint().delete(user, landImages.get(0)); } catch (Exception ignored) {}
            if (landscapeBytes == null || landscapeBytes.length == 0)
                return Response.status(500).entity("{\"error\":\"Empty landscape image\"}").build();

            // Stage 3: Stitch reference composite [portrait1 | portrait2|landscape | landscape]
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "auto_awesome_mosaic", "Stitching reference..." });
            byte[] leftBytes   = !portraitBytesList.isEmpty() ? portraitBytesList.get(0) : landscapeBytes;
            byte[] centerBytes = portraitBytesList.size() > 1  ? portraitBytesList.get(1) : landscapeBytes;
            byte[] refComposite = SDUtil.stitchSceneImages(leftBytes, centerBytes, landscapeBytes, 1024);
            if (refComposite == null)
                return Response.status(500).entity("{\"error\":\"Stitch failed\"}").build();

            // Stage 4: Flux Kontext composite
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "image", "Compositing scene..." });
            String leftDesc  = !portraitPromptList.isEmpty() ? portraitPromptList.get(0) : "";
            String rightDesc = portraitPromptList.size() > 1  ? portraitPromptList.get(1) : "";
            SWTxt2Img kontextReq = SWUtil.newKontextSceneTxt2Img(leftDesc, rightDesc, action, setting, sdConfigRec);
            List<String> promptImages = new ArrayList<>();
            promptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(refComposite));
            kontextReq.setPromptImages(promptImages);
            List<BaseRecord> finalImages = sdu.createSceneImage(user, sceneGroupPath,
                    "scene_" + sceneObjectId + "_" + System.currentTimeMillis(), kontextReq, null, null);
            if (finalImages == null || finalImages.isEmpty())
                return Response.status(500).entity("{\"error\":\"Kontext composite generation failed\"}").build();
            BaseRecord finalImage = finalImages.get(0);
            String finalImageOid = finalImage.get(FieldNames.FIELD_OBJECT_ID);
            IOSystem.getActiveContext().getAccessPoint().member(user, scene, finalImage, null, true);
            updateSceneImageId(user, scene, finalImageOid);
            String compositePrompt = action + " " + setting;
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "", "" });
            BaseRecord genResult = buildResult();
            genResult.set("imageObjectId", finalImageOid);
            genResult.set("prompt", compositePrompt);
            genResult.set("seed", extractSeedFromImage(finalImage));
            return Response.status(200).entity(toJson(genResult)).build();
        } catch (Exception e) {
            WebSocketService.chirpUser(user, new String[] { "bgActivity", "", "" });
            logger.error("Scene image generation pipeline failed: " + e.getMessage(), e);
            return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    /**
     * POST /scene/{sceneObjectId}/blurb
     * Regenerate scene blurb via LLM. Updates data.note.description.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/blurb")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response regenerateBlurb(@PathParam("sceneObjectId") String sceneObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);

        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        sq.planMost(false);
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        if (scene == null) return Response.status(404).entity("{\"error\":\"Scene not found\"}").build();

        String chatConfigName = null;
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                chatConfigName = params.get("chatConfig");
            } catch (Exception e) {
                logger.warn("Failed to parse blurb request: " + e.getMessage());
            }
        }

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
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", title != null ? title : "");
        vars.put("setting", setting);
        vars.put("action", action);
        vars.put("characterList", charList);
        String blurb = callLlm(user, chatConfig, "pictureBook.scene-blurb", vars);
        if (blurb == null || blurb.isEmpty()) {
            return Response.status(500).entity("{\"error\":\"Blurb generation failed\"}").build();
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
            return Response.status(500).entity("{\"error\":\"Failed to save blurb\"}").build();
        }

        try {
            BaseRecord blurbResult = buildResult();
            blurbResult.set("blurb", blurb.trim());
            return Response.status(200).entity(toJson(blurbResult)).build();
        } catch (Exception ex) {
            return Response.status(200).entity("{\"blurb\":" + JSONUtil.exportObject(blurb.trim()) + "}").build();
        }
    }

    /**
     * GET /{bookObjectId}/scenes
     * Returns ordered scene list from .pictureBookMeta.
     * bookObjectId is the objectId of the book group under ~/PictureBooks/.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/scenes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listScenes(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) return Response.status(404).entity("{\"error\":\"Book not found\"}").build();
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);

        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec == null) {
            // No meta yet — return empty
            return Response.status(200).entity("[]").build();
        }

        String metaJson = metaRec.get("text");
        if (metaJson == null || metaJson.isEmpty()) {
            return Response.status(200).entity("[]").build();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
            Object scenesObj = meta.get("scenes");
            if (scenesObj == null) return Response.status(200).entity("[]").build();

            // Merge current blurb from each scene note into the meta's description field
            // so blurb edits persist across page reloads
            if (scenesObj instanceof List) {
                @SuppressWarnings("unchecked")
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
                            }
                        }
                    } catch (Exception e) {
                        // Non-fatal — scene keeps its original description
                    }
                }
            }

            return Response.status(200).entity(JSONUtil.exportObject(scenesObj)).build();
        } catch (Exception e) {
            logger.error("Failed to parse meta: " + e.getMessage());
            return Response.status(500).entity("{\"error\":\"Failed to read meta\"}").build();
        }
    }

    /**
     * PUT /{bookObjectId}/scenes/order
     * Reorder scenes. Body: { scenes: ["objectId1", ...] }
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/scenes/order")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reorderScenes(@PathParam("bookObjectId") String bookObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) return Response.status(404).entity("{\"error\":\"Book not found\"}").build();

        List<String> newOrder = new ArrayList<>();
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                Object scenesObj = params.get("scenes");
                if (scenesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> sl = (List<Object>) scenesObj;
                    for (Object o : sl) {
                        if (o instanceof String) newOrder.add((String) o);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse reorder request: " + e.getMessage());
            }
        }

        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec == null) return Response.status(404).entity("{\"error\":\"Meta not found\"}").build();

        String metaJson = metaRec.get("text");
        try {
            BaseRecord meta = JSONUtil.importObject(metaJson, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
            if (meta == null) return Response.status(400).entity("{\"error\":\"Failed to parse meta\"}").build();

            @SuppressWarnings("unchecked")
            List<BaseRecord> scenes = meta.get("scenes");
            if (scenes == null || scenes.isEmpty()) return Response.status(400).entity("{\"error\":\"No scenes in meta\"}").build();

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
            return Response.status(200).entity(toJson(result)).build();
        } catch (Exception e) {
            logger.error("Failed to reorder scenes: " + e.getMessage());
            return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    /**
     * DELETE /{bookObjectId}/reset
     * Delete the book group contents (Scenes/, Characters/, meta) then the group itself.
     * Explicit child deletion — AccessPoint.delete on a group does NOT cascade.
     */
    @RolesAllowed({"admin", "user"})
    @DELETE
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reset(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) return Response.status(404).entity("{\"error\":\"Book not found\"}").build();

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

        try {
            BaseRecord resetResult = buildResult();
            resetResult.set("reset", ok);
            return Response.status(200).entity(toJson(resetResult)).build();
        } catch (Exception ex) {
            return Response.status(200).entity("{\"reset\":" + ok + "}").build();
        }
    }
}
