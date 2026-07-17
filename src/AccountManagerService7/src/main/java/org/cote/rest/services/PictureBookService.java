package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.picturebook.IPictureBookProgressHandler;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookProgressNotifier;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
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
 * PictureBookService — thin REST transport for generating illustrated picture books from
 * documents. Auto-registered via RestServiceConfig packages("org.cote.rest.services").
 *
 * All business logic (LLM prompt orchestration, character/scene extraction, the 4-stage SD image
 * pipeline, meta persistence, etc.) lives in Objects7's {@link PictureBookUtil} — see
 * .claude/rules/architecture.md ("no business logic in Service7") and that class's javadoc. This
 * class's only jobs are: parse the incoming request JSON, call into PictureBookUtil with the
 * authenticated user, and build the HTTP Response — mirroring {@code GroupExportService}'s split
 * from {@code GroupExportUtil}.
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

    private static final String PB_REQUEST_SCHEMA = "olio.pictureBookRequest";

    // ----- WebSocket progress-forwarding registration --------------------

    /**
     * Lazily registers a handler with {@link PictureBookProgressNotifier} that forwards each
     * progress event to {@code WebSocketService.chirpUser} — mirrors
     * {@code GameStreamHandler.getHandlerInstance()}'s registration with {@code GameEventNotifier}.
     * Objects7's {@code PictureBookUtil} has no dependency on Service7's WebSocket transport; this
     * is the one place that bridges the two.
     */
    private static volatile boolean progressHandlerRegistered = false;

    private static synchronized void ensureProgressHandlerRegistered() {
        if (progressHandlerRegistered) return;
        PictureBookProgressNotifier.getInstance().addHandler(new IPictureBookProgressHandler() {
            @Override
            public void onProgress(BaseRecord user, String icon, String message) {
                WebSocketService.chirpUser(user, new String[] { "bgActivity", icon, message });
            }
        });
        progressHandlerRegistered = true;
        logger.info("PictureBookService progress handler registered with PictureBookProgressNotifier");
    }

    public PictureBookService() {
        ensureProgressHandlerRegistered();
    }

    // ----- Request JSON parsing helpers (transport only) ------------------

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

    private BaseRecord parseParams(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return JSONUtil.importObject(ensureSchema(json), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
        } catch (Exception e) {
            logger.warn("Failed to parse request body: " + e.getMessage());
            return null;
        }
    }

    private String toJson(BaseRecord rec) {
        return rec.toFullString();
    }

    private Response errorResponse(int status, String message) {
        return Response.status(status).entity("{\"error\":\"" + message + "\"}").build();
    }

    private Response handlePictureBookException(PictureBookException e) {
        return errorResponse(e.getStatus(), e.getMessage());
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

        int count = PictureBookUtil.MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        String promptTemplateOverride = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            Object countObj = params.get("count");
            if (countObj instanceof Number) count = ((Number) countObj).intValue();
            chatConfigName = params.get("chatConfig");
            promptTemplateOverride = params.get("promptTemplate");
        }

        try {
            PictureBookUtil.ScenesOnlyResult result = PictureBookUtil.extractScenesOnly(
                    user, workObjectId, count, chatConfigName, promptTemplateOverride);
            if (result.chunked) {
                BaseRecord out = PictureBookUtil.buildResult();
                try {
                    out.set("sceneList", result.scenes);
                    out.set("extractionComplete", true);
                    out.set("chunksProcessed", -1);
                    out.set("chunked", true);
                } catch (Exception e) { logger.warn("Failed to build chunked result: " + e.getMessage()); }
                return Response.status(200).entity(toJson(out)).build();
            }
            return Response.status(200).entity(JSONUtil.exportObject(result.scenes,
                    RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{workObjectId}/extract-chunked
     * Chunked scene extraction — delegates to PictureBookUtil.extractChunked.
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

        String chatConfigName = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
        }

        try {
            BaseRecord result = PictureBookUtil.extractChunked(user, workObjectId, chatConfigName);
            return Response.status(200).entity(toJson(result)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
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

        int count = PictureBookUtil.MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        String genre = null;
        String bookName = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            Object countObj = params.get("count");
            if (countObj instanceof Number) count = ((Number) countObj).intValue();
            chatConfigName = params.get("chatConfig");
            genre = params.get("genre");
            bookName = params.get("bookName");
        }

        try {
            BaseRecord meta = PictureBookUtil.extract(user, workObjectId, count, chatConfigName, genre, bookName);
            return Response.status(200).entity(toJson(meta)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
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

        String chatConfigName = null;
        String genre = null;
        String bookName = null;
        List<Map<String, Object>> sceneList = new ArrayList<>();
        List<Map<String, Object>> charDataList = new ArrayList<>();
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
            genre = params.get("genre");
            bookName = params.get("bookName");
            Object sl = params.get("sceneList");
            if (sl instanceof List) {
                for (Object item : (List<?>) sl) {
                    sceneList.add(toMap(item));
                }
            }
            Object cl = params.get("characters");
            if (cl instanceof List) {
                for (Object item : (List<?>) cl) {
                    charDataList.add(toMap(item));
                }
            }
        }

        try {
            BaseRecord meta = PictureBookUtil.createFromScenes(user, workObjectId, chatConfigName, genre, bookName,
                    sceneList, charDataList);
            return Response.status(200).entity(toJson(meta)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /** Converts a deserialized list item (BaseRecord or Map) into a plain Map for PictureBookUtil. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object item) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (item instanceof BaseRecord) {
            BaseRecord r = (BaseRecord) item;
            for (FieldType f : r.getFields()) m.put(f.getName(), r.get(f.getName()));
        } else if (item instanceof Map) {
            m.putAll((Map<String, Object>) item);
        }
        return m;
    }

    /**
     * POST /scene/{sceneObjectId}/generate
     * Generate SD image for one scene using the 4-stage pipeline (see PictureBookUtil.generateSceneImage).
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

        PictureBookUtil.SceneGenerationParams sgp = new PictureBookUtil.SceneGenerationParams();
        BaseRecord params = parseParams(json);
        if (params != null) {
            sgp.chatConfigName = params.get("chatConfig");
            sgp.promptOverride = params.get("promptOverride");
            sgp.promptTemplateOverride = params.get("promptTemplate");
            Object ibv = params.get("isBook");
            if (ibv instanceof Boolean) sgp.isBookOverride = (Boolean) ibv;
            BaseRecord sdConf = params.get("sdConfig");
            if (sdConf != null) {
                Object sv = sdConf.get("steps"); if (sv instanceof Number) sgp.steps = ((Number) sv).intValue();
                Object rv = sdConf.get("refinerSteps"); if (rv instanceof Number) sgp.refinerSteps = ((Number) rv).intValue();
                Object cv = sdConf.get("cfg"); if (cv instanceof Number) sgp.cfg = ((Number) cv).intValue();
                Object hv = sdConf.get("hires"); if (hv instanceof Boolean) sgp.hires = (Boolean) hv;
                Object seedV = sdConf.get("seed"); if (seedV instanceof Number) sgp.seed = ((Number) seedV).intValue();
                Object mv = sdConf.get("model"); if (mv instanceof String) sgp.sdModelName = (String) mv;
                Object rmv = sdConf.get("refinerModel"); if (rmv instanceof String) sgp.sdRefinerModelName = (String) rmv;
                Object dv = sdConf.get("denoisingStrength"); if (dv instanceof Number) sgp.denoisingStrength = ((Number) dv).doubleValue();
                Object smpv = sdConf.get("sampler"); if (smpv instanceof String) sgp.sdSampler = (String) smpv;
                Object schv = sdConf.get("scheduler"); if (schv instanceof String) sgp.sdScheduler = (String) schv;
                Object rsmpv = sdConf.get("refinerSampler"); if (rsmpv instanceof String) sgp.sdRefinerSampler = (String) rsmpv;
                Object rschv = sdConf.get("refinerScheduler"); if (rschv instanceof String) sgp.sdRefinerScheduler = (String) rschv;
                @SuppressWarnings("unchecked")
                Object lorasV = sdConf.get("loras"); if (lorasV instanceof List) sgp.sdLoras = (List<String>) lorasV;
                Object styv = sdConf.get("style"); if (styv instanceof String && !((String) styv).isEmpty()) sgp.style = (String) styv;
            }
        }

        String sdApiType = context.getInitParameter("sd.server.apiType");
        String sdServer  = context.getInitParameter("sd.server");

        try {
            BaseRecord genResult = PictureBookUtil.generateSceneImage(user, sceneObjectId, sgp, sdApiType, sdServer);
            return Response.status(200).entity(toJson(genResult)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /scene/{sceneObjectId}/blurb
     * Regenerate scene blurb via LLM. Updates data.note.text (blurb key).
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/blurb")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response regenerateBlurb(@PathParam("sceneObjectId") String sceneObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);

        String chatConfigName = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
        }

        try {
            BaseRecord blurbResult = PictureBookUtil.regenerateBlurb(user, sceneObjectId, chatConfigName);
            return Response.status(200).entity(toJson(blurbResult)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
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
        try {
            List<Map<String, Object>> scenes = PictureBookUtil.listScenes(user, bookObjectId);
            return Response.status(200).entity(JSONUtil.exportObject(scenes)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
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

        List<String> newOrder = new ArrayList<>();
        BaseRecord params = parseParams(json);
        if (params != null) {
            Object scenesObj = params.get("scenes");
            if (scenesObj instanceof List) {
                for (Object o : (List<?>) scenesObj) {
                    if (o instanceof String) newOrder.add((String) o);
                }
            }
        }

        try {
            BaseRecord result = PictureBookUtil.reorderScenes(user, bookObjectId, newOrder);
            return Response.status(200).entity(toJson(result)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * DELETE /{bookObjectId}/reset
     * Delete the book group contents (Scenes/, Characters/, meta) then the group itself.
     */
    @RolesAllowed({"admin", "user"})
    @DELETE
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reset(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        boolean ok;
        try {
            ok = PictureBookUtil.reset(user, bookObjectId);
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
        try {
            BaseRecord resetResult = PictureBookUtil.buildResult();
            resetResult.set("reset", ok);
            return Response.status(200).entity(toJson(resetResult)).build();
        } catch (Exception ex) {
            return Response.status(200).entity("{\"reset\":" + ok + "}").build();
        }
    }
}
