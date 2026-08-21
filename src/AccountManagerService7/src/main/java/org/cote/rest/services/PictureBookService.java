package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.IPictureBookProgressHandler;
import org.cote.accountmanager.olio.picturebook.PictureBookCancelRegistry;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
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
import org.cote.accountmanager.util.ServerConfigUtil;

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
 *   GET  /{bookObjectId}/settings              — Last-used image generation settings for this book
 *   PUT  /{bookObjectId}/settings              — Store the book's common (+ optional composite) olio.sd.config once
 *   POST /{bookObjectId}/prepare-images        — Batch-resolve landscape prompts for a set of scenes, then flush idle Ollama models once
 *   PUT  /{bookObjectId}/scenes/order         — Reorder scenes
 *   PUT  /scene/{sceneObjectId}/status        — Persist a client-driven scene status (accepted/skipped/pending/...)
 *   POST /{key}/cancel                        — KI-10: cancel an in-flight extraction/prepare-images call (key = the same workObjectId/bookObjectId passed to the call being cancelled)
 *   DELETE /{bookObjectId}/reset              — Delete entire book group
 *
 * PB2 bridge (book group objectId → olio.pb.book objectId):
 *   GET  /{bookGroupObjectId}/pb2                             — resolve PB1 book group to PB2 book; 404 if no PB2 book yet
 *
 * PB2 phase 4 (the olio.pb.* workflow graph; bookObjectId here is the olio.pb.book objectId, NOT the
 * PB1 book group — every one of these delegates to PbServiceFacade, which reads the book with
 * AccessPoint.find before anything else, per the KI-67 disposition):
 *   GET  /{bookObjectId}/workflow                              — nodes + edges, with stored and recomputed status
 *   GET  /{bookObjectId}/workflow/node/{nodeObjectId}          — one node: bindings + artifact revision chains
 *   GET  /{bookObjectId}/artifact/{artifactObjectId}           — one artifact's provenance (never the bytes)
 *   GET  /{bookObjectId}/stale                                 — nodes whose recomputed status is STALE
 *   POST /{bookObjectId}/node/{nodeObjectId}/regenerate        — MARK stale + downstream (does not execute)
 *   POST /{bookObjectId}/node/{nodeObjectId}/pin               — pin/unpin a node
 *   POST /{bookObjectId}/members                               — enrol users in both tiers
 *   POST /chapter                                              — create the next chapter, optionally copying records
 */
@DeclareRoles({"admin", "user"})
@Path("/olio/picture-book")
public class PictureBookService {

    private static final Logger logger = LogManager.getLogger(PictureBookService.class);

    private static final String PB_REQUEST_SCHEMA = "olio.pictureBookRequest";

    /*
     * KI-10 cancellation registry: MOVED to Objects7's {@link PictureBookCancelRegistry}
     * (2026-08-14) as part of fixing the authorization defect described in PictureBook2Plan.md
     * §5.6. It used to be a static flat map here keyed only by the client-supplied
     * workObjectId/bookObjectId path param, with the cancel endpoint discarding its principal —
     * so any authenticated user could cancel any other user's in-flight extraction. The registry
     * is now keyed by (principal, key) and the ownership check is Objects7 authorization logic,
     * not an if-block in this transport class. Registration/cleanup lifecycle is unchanged:
     * register right before the blocking call, unregister in a finally.
     */

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
     * Smart scene extraction — auto-chunks if text > PictureBookUtil.MAX_EXTRACTION_TEXT_CHARS.
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
            /// params.get("count") returns 0 (the int field's unset primitive default) when the
            /// client never included "count" in the request body at all -- e.g. the wizard's
            /// doExtract() -> extractScenes(workObjectId, chatConfigName(), null, ...) never sends
            /// count, relying on this endpoint's own default. The old `countObj instanceof Number`
            /// check couldn't tell "field absent" from "field present with value 0" and silently
            /// asked the LLM for the 0 most notable scenes, which returns an empty (but valid,
            /// fast) array -- masquerading as "no scenes returned" with no error anywhere.
            if (params.hasField("count")) {
                Object countObj = params.get("count");
                if (countObj instanceof Number) count = ((Number) countObj).intValue();
            }
            chatConfigName = params.get("chatConfig");
            promptTemplateOverride = params.get("promptTemplate");
        }

        // KI-10: registered under (principal, workObjectId) — the same id the client already holds
        // to fire a concurrent POST /{workObjectId}/cancel while this call is still in-flight.
        SummarizeProgress cancelToken = PictureBookCancelRegistry.register(user, workObjectId);
        try {
            PictureBookUtil.ScenesOnlyResult result = PictureBookUtil.extractScenesOnly(
                    user, workObjectId, count, chatConfigName, promptTemplateOverride, cancelToken);
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
        } finally {
            PictureBookCancelRegistry.unregister(user, workObjectId, cancelToken);
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

        // KI-10: see extractScenesOnly()'s identical registration pattern.
        SummarizeProgress cancelToken = PictureBookCancelRegistry.register(user, workObjectId);
        try {
            BaseRecord result = PictureBookUtil.extractChunked(user, workObjectId, chatConfigName, cancelToken);
            return Response.status(200).entity(toJson(result)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } finally {
            PictureBookCancelRegistry.unregister(user, workObjectId, cancelToken);
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
            String json, @Context HttpServletRequest request, @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);

        int count = PictureBookUtil.MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        String genre = null;
        String bookName = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            /// See extractScenesOnly()'s identical guard: params.get("count") returns 0 (the int
            /// field's unset primitive default) when the client never sent "count" at all, which
            /// silently asked the LLM for the 0 most notable scenes instead of falling back to
            /// MAX_SCENES_DEFAULT.
            if (params.hasField("count")) {
                Object countObj = params.get("count");
                if (countObj instanceof Number) count = ((Number) countObj).intValue();
            }
            chatConfigName = params.get("chatConfig");
            genre = params.get("genre");
            bookName = params.get("bookName");
        }

        // KI-30: threaded down to createCharPerson so it can obtain an OlioContext for
        // CharacterUtil.randomPerson() — same init param GameService already reads for the same
        // purpose (OlioContextUtil.getOlioContext(user, ...)).
        String dataPath = context.getInitParameter("datagen.path");

        try {
            BaseRecord meta = PictureBookUtil.extract(user, workObjectId, count, chatConfigName, genre, bookName, dataPath);
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
            String json, @Context HttpServletRequest request, @Context ServletContext context) {
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

        // KI-30: see extract()'s identical use of this init param.
        String dataPath = context.getInitParameter("datagen.path");

        try {
            BaseRecord meta = PictureBookUtil.createFromScenes(user, workObjectId, chatConfigName, genre, bookName,
                    sceneList, charDataList, dataPath);
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
            // The nested sdConfig / compositeSdConfig / sdConfigOverride are full olio.sd.config
            // records (ephemeral model fields declared on olio.pictureBookRequest). ALL SD
            // generation params now live on those records — PictureBookUtil merges
            // (common -> override -> fillStyleDefaults) and derives style via getSDConfigPrompt.
            // This layer stays pure transport: parse the records, no flattened-scalar extraction.
            Object sdc = params.get("sdConfig");
            if (sdc instanceof BaseRecord) sgp.sdConfig = (BaseRecord) sdc;
            Object csdc = params.get("compositeSdConfig");
            if (csdc instanceof BaseRecord) sgp.compositeSdConfig = (BaseRecord) csdc;
            Object osdc = params.get("sdConfigOverride");
            if (osdc instanceof BaseRecord) sgp.sdConfigOverride = (BaseRecord) osdc;
        }

        String sdApiType = context.getInitParameter("sd.server.apiType");
        String sdServer  = ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_SD, context.getInitParameter("sd.server"));

        try {
            BaseRecord genResult = PictureBookUtil.generateSceneImage(user, sceneObjectId, sgp, sdApiType, sdServer);
            return Response.status(200).entity(toJson(genResult)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/prepare-images
     * Batch-resolve (and cache) the landscape prompt for every listed scene, then flush idle
     * Ollama models ONCE — so a "Generate All" run does all of its LLM calls up front instead of
     * interleaving one LLM call per scene between rounds of GPU-heavy SD calls. Call this before
     * looping the per-scene /generate calls. bookObjectId is accepted for routing symmetry with
     * the other book-scoped endpoints but isn't otherwise used — the scene objectIds carry
     * everything PictureBookUtil needs.
     * Body: { sceneObjectIds: [...], chatConfig, promptTemplate, sdConfig: { style } }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/prepare-images")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prepareSceneImagePrompts(@PathParam("bookObjectId") String bookObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);

        List<String> sceneObjectIds = new ArrayList<>();
        String chatConfigName = null;
        String promptTemplateOverride = null;
        BaseRecord sdConfig = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
            promptTemplateOverride = params.get("promptTemplate");
            Object idsObj = params.get("sceneObjectIds");
            if (idsObj instanceof List) {
                for (Object o : (List<?>) idsObj) {
                    if (o instanceof String) sceneObjectIds.add((String) o);
                }
            }
            // The common olio.sd.config (ephemeral model field). Its style is the single seam
            // (getSDConfigPrompt) baked into each pre-resolved prompt; PictureBookUtil fills defaults.
            Object sdc = params.get("sdConfig");
            if (sdc instanceof BaseRecord) sdConfig = (BaseRecord) sdc;
        }

        // KI-10: registered under (principal, bookObjectId) — the same id the client already holds
        // to fire a concurrent POST /{bookObjectId}/cancel while this batch is still in-flight.
        SummarizeProgress cancelToken = PictureBookCancelRegistry.register(user, bookObjectId);
        try {
            PictureBookUtil.prepareSceneImagePrompts(user, sceneObjectIds, chatConfigName, sdConfig, promptTemplateOverride, cancelToken);
            BaseRecord result = PictureBookUtil.buildResult();
            return Response.status(200).entity(toJson(result)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } finally {
            PictureBookCancelRegistry.unregister(user, bookObjectId, cancelToken);
        }
    }

    /**
     * POST /{key}/cancel
     * KI-10: cancel an in-flight extraction ({@code /extract-scenes-only}, {@code /extract-chunked})
     * or {@code /prepare-images} call. {@code key} must be the exact workObjectId/bookObjectId the
     * client passed to the call it wants to cancel — the client always already has this value
     * (it's the path param of the call being cancelled), so no separate session/token bookkeeping
     * is needed, unlike {@code ChatService}'s session-scoped {@code summarizingRefs}. A 200 with
     * {@code cancelled:false} (not an error) is returned when there's nothing in-flight for that
     * key — e.g. the call already finished, or the client raced the cancel ahead of the call
     * actually registering.
     *
     * <p>The key is scoped to the authenticated principal ({@link PictureBookCancelRegistry}).
     * Before 2026-08-14 the principal was fetched and discarded here and the registry was a flat
     * process-wide map, so any authenticated user could cancel any other user's in-flight
     * extraction by supplying its id. A cancel for a key owned by someone else now returns the
     * same {@code cancelled:false} an unknown key returns, so it discloses nothing.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{key:[0-9A-Za-z\\-]+}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancel(@PathParam("key") String key, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        boolean cancelled = PictureBookCancelRegistry.cancel(user, key);
        return Response.status(200).entity("{\"cancelled\":" + cancelled + "}").build();
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
     * GET /{bookObjectId}/characters
     * List a book's extracted characters for the "Manage Characters" review/edit screen —
     * objectId/name/gender/hasPortrait/apparelCount/per-apparel scene tags/failedApparel-or-
     * Statistics flags.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/characters")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCharacters(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            List<Map<String, Object>> characters = PictureBookUtil.listCharacters(user, bookObjectId);
            return Response.status(200).entity(JSONUtil.exportObject(characters)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * PUT /character/{objectId}/apparel/{apparelObjectId}/scene-tag
     * Tag an apparel entry with the scene index it should first apply from (see
     * PictureBookUtil.selectSceneApparel). Used by the character editor after generating a new
     * outfit via the existing outfitBuilder.js flow. Body: { sceneIndex: n }
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/character/{objectId:[0-9A-Za-z\\-]+}/apparel/{apparelObjectId:[0-9A-Za-z\\-]+}/scene-tag")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagApparelSceneIndex(@PathParam("objectId") String objectId,
            @PathParam("apparelObjectId") String apparelObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        // KI-25's trap, not a null check: sceneIndex is an int field, so an ABSENT one reads back as 0,
        // never null. hasField() is the only thing that distinguishes "not sent" from "sent as 0" — and
        // 0 is a legitimate value here (scene 1). The previous `== null` guard could never fire once the
        // field existed, and before 2026-08-17 the field was not declared on olio.pictureBookRequest at
        // all, so it was dropped by the deserializer and the guard fired on EVERY request instead.
        if (params == null || !params.hasField("sceneIndex")) {
            return Response.status(400).entity("{\"error\":true,\"message\":\"sceneIndex is required\"}").build();
        }
        int sceneIndex = ((Number) params.get("sceneIndex")).intValue();
        try {
            // The character objectId in the path is now PASSED, not discarded: PictureBookUtil
            // authorizes the owning character's BOOK (PB2 §5.6's last REST authorization gap) and
            // refuses an apparel that is not in that character's store. Before 2026-08-17 this
            // resolved an apparel record by objectId with no book check at all.
            boolean ok = PictureBookUtil.tagApparelSceneIndex(user, objectId, apparelObjectId, sceneIndex);
            BaseRecord result = PictureBookUtil.buildResult();
            result.set("tagged", ok);
            return Response.status(200).entity(toJson(result)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } catch (Exception e) {
            return Response.status(500).entity("{\"error\":true,\"message\":\"" + e.getMessage() + "\"}").build();
        }
    }

    /**
     * GET /{bookObjectId}/settings
     * Returns the last-used image generation settings for this book (auto-captured on every
     * scene generation — see PictureBookUtil.persistBookSdConfig), or {} if none have been
     * saved yet (a fresh book that hasn't generated an image).
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/settings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBookSdConfig(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            BaseRecord sdConfig = PictureBookUtil.getBookSdConfig(user, bookObjectId);
            return Response.status(200).entity(sdConfig != null ? toJson(sdConfig) : "{}").build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * PUT /{bookObjectId}/settings
     * Store the book's COMMON (and optional composite) image config once, so subsequent scene
     * generation reads it back as the base for every scene (portraits/landscape/scene). This lets
     * the test/Ux "set one config" per book, matching the CardGame _default pattern. Body:
     * { sdConfig: {...olio.sd.config...}, compositeSdConfig?: {...olio.sd.config...} }. Transport
     * only — the fill/merge logic lives in PictureBookUtil.setBookSdConfig. Returns the stored
     * common config (toFullString), or {"updated":true} if nothing was supplied.
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/settings")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setBookSdConfig(@PathParam("bookObjectId") String bookObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        BaseRecord sdConfig = null;
        BaseRecord compositeSdConfig = null;
        if (params != null) {
            // Both are ephemeral olio.sd.config model fields on olio.pictureBookRequest.
            Object sdc = params.get("sdConfig");
            if (sdc instanceof BaseRecord) sdConfig = (BaseRecord) sdc;
            Object csdc = params.get("compositeSdConfig");
            if (csdc instanceof BaseRecord) compositeSdConfig = (BaseRecord) csdc;
        }
        try {
            BaseRecord stored = PictureBookUtil.setBookSdConfig(user, bookObjectId, sdConfig, compositeSdConfig);
            return Response.status(200).entity(stored != null ? toJson(stored) : "{\"updated\":true}").build();
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
     * PUT /scene/{sceneObjectId}/status
     * Persist a client-driven scene status (accepted/skipped/pending/etc.) so the wizard's
     * progress survives a reload/reopen. Body: { status: "accepted" }
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setSceneStatus(@PathParam("sceneObjectId") String sceneObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        String status = params != null ? params.get("status") : null;
        try {
            PictureBookUtil.setSceneStatus(user, sceneObjectId, status);
            BaseRecord result = PictureBookUtil.buildResult();
            return Response.status(200).entity(toJson(result)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /{bookGroupObjectId}/pb2
     * Resolve a PB1 book group objectId to the corresponding olio.pb.book.
     * Returns {pb2BookObjectId, slug, bookName}, or 404 if no PB2 book has been created for this group.
     * Used by the workflow graph UI to bridge the PB1 auth.group objectId (in the URL) to the
     * olio.pb.book objectId required by the Phase 4 workflow endpoints.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookGroupObjectId:[0-9A-Za-z\\-]+}/pb2")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBookInfo(@PathParam("bookGroupObjectId") String bookGroupObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.bookInfo(user, bookGroupObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PB2 PHASE 4 — the workflow graph, over the olio.pb.* models
    //
    // Every one of these is a thin delegate to PbServiceFacade (Objects7). None of them reads a
    // model, builds a query, or makes an authorization decision here, and that is deliberate:
    //
    //   * The KI-67 disposition is that every PB2 list is reached from an AUTHORIZED read of the
    //     book. PbServiceFacade.requireBook does that read with AccessPoint.find (which runs
    //     canRead on its result) and 404s otherwise, so the constraint lives in Objects7 where it
    //     cannot be forgotten per endpoint. If it lived here it would have to be re-typed eight
    //     times and would be a business rule in a transport class.
    //   * NO generic /rest/model/search over olio.pb.* is exposed, and NO endpoint accepts a
    //     caller-supplied groupId or organizationId to list on. The book objectId in the path is
    //     the only addressable root; a node or artifact belonging to a different book is a 404.
    //
    // Response bodies are the facade's DTO maps via JSONUtil.exportObject, matching /scenes and
    // /characters. Artifact bytes are never inlined - the DTO carries dataObjectId and the existing
    // resource route serves the content.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * GET /{bookObjectId}/workflow
     * The book's whole workflow graph: nodes (with both the persisted status and the recomputed one)
     * and edges. bookObjectId is the olio.pb.book objectId, not the PB1 book group.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/workflow")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getWorkflow(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.workflowView(user, bookObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /{bookObjectId}/workflow/node/{nodeObjectId}
     * One node in detail: bindings, and the artifact revision chain per role.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/workflow/node/{nodeObjectId:[0-9A-Za-z\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getWorkflowNode(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.nodeView(user, bookObjectId, nodeObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /{bookObjectId}/artifact/{artifactObjectId}
     * One artifact's provenance: revision, seed, contentHash, dimensions, the sanitized
     * generatorRequest and the sdConfigSnapshot. Never the bytes.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/artifact/{artifactObjectId:[0-9A-Za-z\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getArtifact(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("artifactObjectId") String artifactObjectId, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.artifactView(user, bookObjectId, artifactObjectId)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /{bookObjectId}/stale
     * Every node whose recomputed status is STALE. A node that has never succeeded is NOT stale
     * (inputHash is null until the first success) — see PbServiceFacade.listStale.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/stale")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listStale(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.listStale(user, bookObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/node/{nodeObjectId}/regenerate
     * MARK a node (and everything downstream of it) for regeneration. This is not a scheduler:
     * execution happens on the next scene generation call, and the response says so
     * ({@code executed:false}). A pinned node is refused with 409.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/node/{nodeObjectId:[0-9A-Za-z\\-]+}/regenerate")
    @Produces(MediaType.APPLICATION_JSON)
    public Response regenerateNode(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.requestRegenerate(user, bookObjectId, nodeObjectId)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/node/{nodeObjectId}/pin
     * Pin or unpin a node. Body: { pinned: true|false }; an absent body pins (the common case, and
     * the unpin call is the one worth being explicit about).
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/node/{nodeObjectId:[0-9A-Za-z\\-]+}/pin")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pinNode(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId, String json,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        boolean pinned = true;
        BaseRecord params = parseParams(json);
        if (params != null) {
            Object p = params.get("pinned");
            if (p instanceof Boolean) pinned = ((Boolean) p).booleanValue();
        }
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.setPinned(user, bookObjectId, nodeObjectId, pinned)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/members
     * Enrol users in the book, in both tiers (book Writer/Admin + the organization-wide universe
     * Reader). Body: { userNames: ["a","b"], asAdmin?: false }.
     *
     * <p>Measured and worth knowing before calling: a book <b>Writer</b> cannot enrol anyone —
     * OlioContext.register's authorizing role is the Admin tier — so this needs the org admin or an
     * explicit Admin grant. Per-target outcomes are reported individually rather than collapsed.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/members")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addMembers(@PathParam("bookObjectId") String bookObjectId, String json,
            @Context HttpServletRequest request, @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        List<String> userNames = new ArrayList<>();
        boolean asAdmin = false;
        BaseRecord params = parseParams(json);
        if (params != null) {
            Object namesObj = params.get("userNames");
            if (namesObj instanceof List) {
                for (Object o : (List<?>) namesObj) {
                    if (o instanceof String) userNames.add((String) o);
                }
            }
            Object aa = params.get("asAdmin");
            if (aa instanceof Boolean) asAdmin = ((Boolean) aa).booleanValue();
        }
        try {
            return Response.status(200).entity(JSONUtil.exportObject(PbServiceFacade.addMembers(user,
                context.getInitParameter("datagen.path"), bookObjectId, userNames, asAdmin))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /chapter
     * Create the next chapter of a book: a new book with its own world, groups and role pair, and
     * optionally copy records into it. Body:
     * { fromBookObjectId, slug, title?, copyRecordModel?, copyRecordObjectIds?: [...] }.
     *
     * <p>§3.5 chose COPY over reference deliberately — apparel/wearables are per character, so a
     * shared instance would make deleting chapter 1 destroy chapter 2's data.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/chapter")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createChapter(String json, @Context HttpServletRequest request,
            @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        if (params == null) {
            return errorResponse(400, "A request body is required");
        }
        String fromBookObjectId = params.get("fromBookObjectId");
        String slug = params.get("slug");
        String title = params.get("title");
        String copyRecordModel = params.get("copyRecordModel");
        List<String> copyIds = new ArrayList<>();
        Object idsObj = params.get("copyRecordObjectIds");
        if (idsObj instanceof List) {
            for (Object o : (List<?>) idsObj) {
                if (o instanceof String) copyIds.add((String) o);
            }
        }
        try {
            return Response.status(200).entity(JSONUtil.exportObject(PbServiceFacade.createChapter(user,
                context.getInitParameter("datagen.path"), fromBookObjectId, slug, title, copyIds,
                copyRecordModel))).build();
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
