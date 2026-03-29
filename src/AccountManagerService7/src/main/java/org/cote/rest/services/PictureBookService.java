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
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

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
 *   POST /{workObjectId}/extract              — Full LLM extraction: scenes + characters
 *   POST /{workObjectId}/extract-scenes-only  — Scene extraction only (no character creation)
 *   POST /scene/{sceneObjectId}/generate      — Generate SD image for one scene
 *   POST /scene/{sceneObjectId}/blurb         — Regenerate scene blurb via LLM
 *   GET  /{workObjectId}/scenes               — Ordered scene list from .pictureBookMeta
 *   PUT  /{workObjectId}/scenes/order         — Reorder scenes
 *   DELETE /{workObjectId}/reset              — Delete Scenes/ and Characters/ groups
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

    // Default scene count cap for non-HQ mode
    private static final int MAX_SCENES_DEFAULT = 3;

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
     * Resolve the work record from its objectId.
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
     * Find or create a sub-group of the work's group.
     */
    private BaseRecord ensureSubGroup(BaseRecord user, BaseRecord work, String subName) {
        String workGroupPath = work.get(FieldNames.FIELD_GROUP_PATH);
        if (workGroupPath == null || workGroupPath.isEmpty()) return null;
        String subPath = workGroupPath + "/" + subName;
        return IOSystem.getActiveContext().getPathUtil().makePath(user,
                ModelNames.MODEL_GROUP, subPath, GroupEnumType.DATA.toString(),
                (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
    }

    /**
     * Load or create the .pictureBookMeta data record in the work's group.
     */
    private BaseRecord loadMeta(BaseRecord user, BaseRecord work) {
        String workGroupPath = work.get(FieldNames.FIELD_GROUP_PATH);
        if (workGroupPath == null) return null;
        BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, workGroupPath, GroupEnumType.DATA.toString(),
                (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
        if (grp == null) return null;

        Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
        q.field(FieldNames.FIELD_NAME, ".pictureBookMeta");
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        return IOSystem.getActiveContext().getAccessPoint().find(user, q);
    }

    /**
     * Save .pictureBookMeta JSON blob to the work's group.
     */
    @SuppressWarnings("unchecked")
    private BaseRecord saveMeta(BaseRecord user, BaseRecord work, Map<String, Object> meta) {
        String workGroupPath = work.get(FieldNames.FIELD_GROUP_PATH);
        if (workGroupPath == null) return null;
        String metaJson = JSONUtil.exportObject(meta);

        BaseRecord existing = loadMeta(user, work);
        if (existing != null) {
            try {
                existing.set(FieldNames.FIELD_DESCRIPTION, metaJson);
                IOSystem.getActiveContext().getAccessPoint().update(user, existing);
                return existing;
            } catch (Exception e) {
                logger.error("Failed to update meta: " + e.getMessage());
                return null;
            }
        }

        // Create new
        ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, workGroupPath);
        plist.parameter(FieldNames.FIELD_NAME, ".pictureBookMeta");
        try {
            BaseRecord newRec = IOSystem.getActiveContext().getFactory().newInstance(
                    ModelNames.MODEL_DATA, user, null, plist);
            newRec.set(FieldNames.FIELD_DESCRIPTION, metaJson);
            newRec.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
            return IOSystem.getActiveContext().getAccessPoint().create(user, newRec);
        } catch (Exception e) {
            logger.error("Failed to create meta: " + e.getMessage());
            return null;
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
     */
    private String callLlm(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
        String system = PromptResourceUtil.getString(promptName, "system");
        String userTpl = PromptResourceUtil.getString(promptName, "user");
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
            Chat chat = new Chat(user, chatConfig, null);
            OpenAIRequest req = chat.newRequest(chat.getModel());
            req.setStream(false);
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
     * Determine genre theme from genre hint string.
     */
    private String genreToTheme(String genre) {
        if (genre == null) return null;
        return GENRE_THEME_MAP.getOrDefault(genre.toLowerCase(), null);
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

            // Build SD portrait prompt from extracted character data and store in narrative.
            // Store full extracted JSON in description for future reference.
            String portraitPrompt = NarrativeUtil.buildPortraitPromptFromExtractedData(name, charData);
            String charDataJson = JSONUtil.exportObject(charData);
            try {
                if (portraitPrompt != null && !portraitPrompt.isEmpty()) {
                    charPerson.set("narrative", portraitPrompt);
                }
                if (charDataJson != null) {
                    charPerson.set(FieldNames.FIELD_DESCRIPTION, charDataJson);
                }
                IOSystem.getActiveContext().getAccessPoint().update(user, charPerson);
            } catch (Exception e) {
                logger.warn("Failed to set portrait prompt/description for " + name + ": " + e.getMessage());
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
     * LLM scene extraction only — no character creation.
     * Returns raw scene JSON array for client review.
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

        BaseRecord params = null;
        int count = MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        if (json != null && !json.trim().isEmpty()) {
            try {
                params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
                Object countObj = params.get("count");
                if (countObj instanceof Number) count = ((Number) countObj).intValue();
                chatConfigName = params.get("chatConfig");
            } catch (Exception e) {
                logger.warn("Failed to parse extract request body: " + e.getMessage());
            }
        }
        // Cap scene count in default mode
        if (count > MAX_SCENES_DEFAULT) count = MAX_SCENES_DEFAULT;

        String text = extractWorkText(user, work);
        if (text == null || text.isEmpty()) {
            return Response.status(400).entity("{\"error\":\"No text content found in work\"}").build();
        }

        // Load chat config
        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, null, chatConfigName);
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("count", String.valueOf(count));
        vars.put("text", text.length() > 8000 ? text.substring(0, 8000) : text);

        String llmResponse = callLlm(user, chatConfig, "pictureBook.extract-scenes", vars);
        List<Map<String, Object>> scenes = parseLlmJsonArray(llmResponse);

        return Response.status(200).entity(JSONUtil.exportObject(scenes,
                RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
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
        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                Object countObj = params.get("count");
                if (countObj instanceof Number) count = ((Number) countObj).intValue();
                chatConfigName = params.get("chatConfig");
                genre = params.get("genre");
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

        // Ensure sub-groups
        BaseRecord scenesGroup = ensureSubGroup(user, work, "Scenes");
        BaseRecord charsGroup = ensureSubGroup(user, work, "Characters");
        if (scenesGroup == null || charsGroup == null) {
            return Response.status(500).entity("{\"error\":\"Failed to create sub-groups\"}").build();
        }

        // Extract scenes
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
        for (Map.Entry<String, Map<String, Object>> entry : uniqueChars.entrySet()) {
            String cname = entry.getKey();
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
        List<Map<String, Object>> metaScenes = new ArrayList<>();
        int idx = 0;
        for (Map<String, Object> sceneData : extractedScenes) {
            BaseRecord note = createSceneNote(user, scenesGroup, sceneData, idx);
            if (note != null) {
                Map<String, Object> ms = new LinkedHashMap<>();
                ms.put("objectId", note.get(FieldNames.FIELD_OBJECT_ID));
                ms.put("index", idx);
                ms.put("title", sceneData.getOrDefault("title", "Scene " + idx));
                ms.put("description", sceneData.getOrDefault("summary", ""));
                ms.put("imageObjectId", null);

                // Collect character objectIds for this scene
                List<String> sceneCharIds = new ArrayList<>();
                Object charsObj = sceneData.get("characters");
                if (charsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> scs = (List<Map<String, Object>>) charsObj;
                    for (Map<String, Object> sc : scs) {
                        String cname = (String) sc.get("name");
                        if (cname != null && charObjectIds.containsKey(cname)) {
                            sceneCharIds.add(charObjectIds.get(cname));
                        }
                    }
                }
                ms.put("characters", sceneCharIds);
                metaScenes.add(ms);
            }
            idx++;
        }

        // Build and save .pictureBookMeta
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workObjectId", workObjectId);
        meta.put("workName", work.get(FieldNames.FIELD_NAME));
        meta.put("sceneCount", metaScenes.size());
        meta.put("scenes", metaScenes);
        meta.put("extractedAt", ZonedDateTime.now().toString());
        meta.put("generatedAt", null);

        saveMeta(user, work, meta);

        return Response.status(200).entity(JSONUtil.exportObject(meta)).build();
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
        int steps = DEFAULT_STEPS;
        int refinerSteps = DEFAULT_REFINER_STEPS;
        int cfg = DEFAULT_CFG;
        boolean hires = DEFAULT_HIRES;

        if (json != null && !json.trim().isEmpty()) {
            try {
                BaseRecord params = JSONUtil.importObject(ensureSchema(json), LooseRecord.class,
                        RecordDeserializerConfig.getUnfilteredModule());
                chatConfigName = params.get("chatConfig");
                promptOverride = params.get("promptOverride");
                BaseRecord sdConf = params.get("sdConfig");
                if (sdConf != null) {
                    Object sv = sdConf.get("steps"); if (sv instanceof Number) steps = ((Number) sv).intValue();
                    Object rv = sdConf.get("refinerSteps"); if (rv instanceof Number) refinerSteps = ((Number) rv).intValue();
                    Object cv = sdConf.get("cfg"); if (cv instanceof Number) cfg = ((Number) cv).intValue();
                    Object hv = sdConf.get("hires"); if (hv instanceof Boolean) hires = (Boolean) hv;
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
                IOSystem.getActiveContext().getAccessPoint().member(user, scene, image, null, true);
                return Response.status(200).entity("{\"imageObjectId\":\"" + image.get(FieldNames.FIELD_OBJECT_ID) + "\"}").build();
            } catch (Exception e) {
                logger.error("Override SD generation failed: " + e.getMessage());
                return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
            }
        }

        try {
            // Stage 1: Portrait bytes for up to 2 scene characters
            List<byte[]> portraitBytesList = new ArrayList<>();
            List<String> portraitPromptList = new ArrayList<>();
            Object charsObj = sceneData.get("characters");
            if (charsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> sceneChars = (List<Map<String, Object>>) charsObj;
                for (Map<String, Object> sc : sceneChars) {
                    if (portraitBytesList.size() >= 2) break;
                    String cname = (String) sc.get("name");
                    if (cname == null) continue;
                    Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_NAME, cname);
                    cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                    cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender"});
                    BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
                    if (cp == null) continue;
                    String portraitPrompt = cp.get("narrative");
                    if (portraitPrompt == null || portraitPrompt.isBlank()) {
                        logger.warn("No portrait prompt (narrative) for: " + cname);
                        continue;
                    }
                    BaseRecord portCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
                    portCfg.set("steps", steps);
                    portCfg.set("cfg", cfg);
                    portCfg.set("hires", false);
                    portCfg.set("description", portraitPrompt);
                    portCfg.set("negativePrompt", NEG_PROMPT);
                    String portName = "portrait_" + cname.replace(" ", "_") + "_" + System.currentTimeMillis();
                    List<BaseRecord> portImages = sdu.createImage(user, sceneGroupPath, portCfg, portName, 1, false, -1);
                    if (portImages == null || portImages.isEmpty()) { logger.warn("Portrait failed: " + cname); continue; }
                    byte[] portBytes = portImages.get(0).get(FieldNames.FIELD_BYTE_STORE);
                    if (portBytes == null || portBytes.length == 0) {
                        try { IOSystem.getActiveContext().getAccessPoint().delete(user, portImages.get(0)); } catch (Exception ignored) {}
                        continue;
                    }
                    portraitBytesList.add(portBytes);
                    portraitPromptList.add(SWUtil.stripSDXLWeighting(portraitPrompt));
                    try { IOSystem.getActiveContext().getAccessPoint().delete(user, portImages.get(0)); } catch (Exception ignored) {}
                }
            }

            // Stage 2: Landscape generation
            Map<String, String> landVars = new LinkedHashMap<>();
            landVars.put("setting", setting);
            landVars.put("mood", mood);
            landVars.put("time", "");
            landVars.put("style", "illustration");
            String landscapePrompt = callLlm(user, chatConfig, "pictureBook.landscape-prompt", landVars);
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
            byte[] leftBytes   = !portraitBytesList.isEmpty() ? portraitBytesList.get(0) : landscapeBytes;
            byte[] centerBytes = portraitBytesList.size() > 1  ? portraitBytesList.get(1) : landscapeBytes;
            byte[] refComposite = SDUtil.stitchSceneImages(leftBytes, centerBytes, landscapeBytes, 1024);
            if (refComposite == null)
                return Response.status(500).entity("{\"error\":\"Stitch failed\"}").build();

            // Stage 4: Flux Kontext composite
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
            IOSystem.getActiveContext().getAccessPoint().member(user, scene, finalImage, null, true);
            return Response.status(200).entity("{\"imageObjectId\":\"" + finalImage.get(FieldNames.FIELD_OBJECT_ID) + "\"}").build();
        } catch (Exception e) {
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

        return Response.status(200).entity("{\"blurb\":" + JSONUtil.exportObject(blurb.trim()) + "}").build();
    }

    /**
     * GET /{workObjectId}/scenes
     * Returns ordered scene list from .pictureBookMeta.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/scenes")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listScenes(@PathParam("workObjectId") String workObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

        BaseRecord metaRec = loadMeta(user, work);
        if (metaRec == null) {
            // No meta yet — return empty
            return Response.status(200).entity("[]").build();
        }

        String metaJson = metaRec.get(FieldNames.FIELD_DESCRIPTION);
        if (metaJson == null || metaJson.isEmpty()) {
            return Response.status(200).entity("[]").build();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
            Object scenes = meta.get("scenes");
            if (scenes == null) return Response.status(200).entity("[]").build();
            return Response.status(200).entity(JSONUtil.exportObject(scenes)).build();
        } catch (Exception e) {
            logger.error("Failed to parse meta: " + e.getMessage());
            return Response.status(500).entity("{\"error\":\"Failed to read meta\"}").build();
        }
    }

    /**
     * PUT /{workObjectId}/scenes/order
     * Reorder scenes. Body: { scenes: ["objectId1", ...] }
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/scenes/order")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reorderScenes(@PathParam("workObjectId") String workObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

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

        BaseRecord metaRec = loadMeta(user, work);
        if (metaRec == null) return Response.status(404).entity("{\"error\":\"Meta not found\"}").build();

        String metaJson = metaRec.get(FieldNames.FIELD_DESCRIPTION);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
            Object scenesObj = meta.get("scenes");
            if (!(scenesObj instanceof List)) return Response.status(400).entity("{\"error\":\"No scenes in meta\"}").build();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenes = (List<Map<String, Object>>) scenesObj;

            // Rebuild list in new order
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
            meta.put("scenes", reordered);
            saveMeta(user, work, meta);
            return Response.status(200).entity("{\"reordered\":true}").build();
        } catch (Exception e) {
            logger.error("Failed to reorder scenes: " + e.getMessage());
            return Response.status(500).entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    /**
     * DELETE /{workObjectId}/reset
     * Delete Scenes/ and Characters/ sub-groups for a work.
     */
    @RolesAllowed({"admin", "user"})
    @DELETE
    @Path("/{workObjectId:[0-9A-Za-z\\-]+}/reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reset(@PathParam("workObjectId") String workObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return Response.status(404).entity("{\"error\":\"Work not found\"}").build();

        String workGroupPath = work.get(FieldNames.FIELD_GROUP_PATH);
        boolean ok = true;

        for (String sub : new String[]{"Scenes", "Characters"}) {
            String subPath = workGroupPath + "/" + sub;
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

        // Delete meta record
        BaseRecord metaRec = loadMeta(user, work);
        if (metaRec != null) {
            try {
                IOSystem.getActiveContext().getAccessPoint().delete(user, metaRec);
            } catch (Exception e) {
                logger.warn("Failed to delete meta: " + e.getMessage());
            }
        }

        return Response.status(200).entity("{\"reset\":" + ok + "}").build();
    }
}
