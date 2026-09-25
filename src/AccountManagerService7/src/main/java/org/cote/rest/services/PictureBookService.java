package org.cote.rest.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.IPictureBookProgressHandler;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil;
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
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
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
 *   PUT  /scene/{sceneObjectId}/config-override — Persist a per-scene sparse olio.sd.config override (ChapBook scenes feed the config-precedence merge through this)
 *   POST /{key}/cancel                        — KI-10: cancel an in-flight extraction/prepare-images call (key = the same workObjectId/bookObjectId passed to the call being cancelled)
 *   DELETE /{bookObjectId}/reset              — Delete entire book group
 *
 * PB2 bridge (book group objectId → olio.pb.book objectId):
 *   GET  /{bookGroupObjectId}/pb2                             — resolve PB1 book group to PB2 book; 404 if no PB2 book yet
 *
 * PB2 Phase 5b (book list + page view):
 *   GET  /books                                               — list all olio.pb.book records the user can read
 *   GET  /series/{seriesObjectId}/books                       — a series' chapter books (entitled non-owner), with series/chapter/world linkage
 *   GET  /{bookObjectId}/pages                                — ordered scene pages with composite artifact dataObjectId
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
        return Response.status(status).entity("{\"error\":" + escapeJson(message) + "}").build();
    }

    /** Serialize a Java String as a JSON string literal, escaping backslash, double-quote,
     *  newline, and carriage-return so the result is safe to embed in a hand-built JSON body. */
    private static String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private Response handlePictureBookException(PictureBookException e) {
        return errorResponse(e.getStatus(), e.getMessage());
    }

    private Integer getInt(BaseRecord params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return null;
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
            @QueryParam("async") @DefaultValue("false") boolean async,
            @QueryParam("fresh") @DefaultValue("false") boolean fresh,
            @QueryParam("startOffset") Integer startOffset,
            @QueryParam("endOffset") Integer endOffset,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);

        // N-series: startOffset/endOffset (both null = the whole document) bound this call to ONE
        // chapter of a shared manuscript, so a full novel submitted as N chapters runs as N bounded
        // jobs instead of one unbounded ~4-5h job (the UAT poll timeout). Query params, not body
        // fields, for the SAME reason `async` is (see the block below): they need no
        // olio.pictureBookRequest schema change to take effect on a provisioned deployment and are
        // trivially curl-able. Forwarded verbatim — PictureBookUtil validates/clamps the range against
        // the actual extracted text server-side and REJECTS an inverted one; transport does not judge it.
        //
        // Extraction now checkpoints partial scenes to a scratch note keyed on the source document
        // (and, per range, suffixed by it), and a run that was CANCELLED or died mid-flight keeps its
        // checkpoint so the next attempt continues instead of re-paying for chunks already extracted.
        // `fresh=true` is the escape hatch: discard the checkpoint and re-extract from chunk 1. Without
        // it a user who cancelled a run because its output was wrong could never get a clean one.
        if (fresh) {
            // Discard THIS chapter's own range-suffixed checkpoint, not the bare/whole-document one —
            // otherwise a per-chapter fresh re-run would wipe a sibling chapter's checkpoint.
            PictureBookUtil.clearExtractCheckpoint(user, workObjectId, startOffset, endOffset);
        }

        int count = PictureBookUtil.MAX_SCENES_DEFAULT;
        String chatConfigName = null;
        String promptTemplateOverride = null;
        String seriesObjectId = null;
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
            /// N-series item 4: when the client is extracting a chapter of a series, it sends the
            /// series objectId so the extraction prompt's cross-chapter character roster is seeded
            /// from the series baseline cast (PictureBookUtil.resolveSeriesRoster). Transport only:
            /// the field is forwarded verbatim; all resolution stays in Objects7. Absent/blank on a
            /// standalone extraction leaves the roster in-run-only, exactly as before.
            seriesObjectId = params.get("seriesObjectId");
        }

        // Async mode: hand the work to the background job executor and return 202 immediately, so
        // completion no longer depends on this HTTP connection surviving the whole run. Measured
        // 2026-09-13: a 17-chunk extraction ran ~27 minutes and nginx returned 504 at exactly 900s
        // while Tomcat carried on to chunk 11/17 — every chunk of LLM work was discarded, and
        // nothing server-side had failed so nothing was logged. The job retains its result for a
        // TTL, so a client that was disconnected (proxy timeout, reload, navigate-away) can still
        // collect it from GET /rest/job/{jobId}.
        //
        // Opt-in via an explicit `async` flag rather than auto-switching on text length: this
        // endpoint ALREADY returns two different shapes depending on whether the text chunked
        // (a bare array vs. { sceneList, ... }), which the client has to special-case, and making
        // the shape depend on a second hidden condition is how that became confusing in the first
        // place. The synchronous path below is unchanged, so existing callers and specs keep working.
        // A query parameter, not a body field, on purpose. Adding `async` to the
        // olio.pictureBookRequest model would have no runtime effect on an already-provisioned
        // deployment: RecordFactory.getSchema reads the PERSISTED ModelSchema from
        // a7_system_modelschema_0_1 first and only falls back to the resource when the DB has no
        // row, so a model-JSON edit silently does nothing until the schema is explicitly updated
        // or the database is fresh (see .claude/rules/objects7-reference.md). A query param needs
        // no schema at all and is trivially exercisable with curl.
        if (async) {
            final int fCount = count;
            final String fChatConfig = chatConfigName;
            final String fPromptTemplate = promptTemplateOverride;
            final String fSeriesObjectId = seriesObjectId;
            final Integer fStartOffset = startOffset;
            final Integer fEndOffset = endOffset;
            AsyncJob job = AsyncJobRegistry.submit(user, "pb.extractScenes", workObjectId, j -> {
                // The job's OWN progress token is the cancel signal, so POST /rest/job/{id}/cancel
                // reaches the chunk loop's existing checkpoint. Do not reuse the
                // PictureBookCancelRegistry token here: that one is keyed on workObjectId and is
                // the sync path's mechanism.
                PictureBookUtil.ScenesOnlyResult r = PictureBookUtil.extractScenesOnly(
                        user, workObjectId, fCount, fChatConfig, fPromptTemplate, j.getProgress(),
                        fSeriesObjectId, fStartOffset, fEndOffset);
                BaseRecord out = PictureBookUtil.buildResult();
                out.set("sceneList", r.scenes);
                // "Complete" must mean the run reached the end of the text, not merely "nobody
                // cancelled": it also stops early on thread interruption (shutdown) and on the
                // unreachable-LLM circuit breaker. One observed run reported
                // extractionComplete=true having extracted ZERO scenes because its chat config
                // could not be resolved. ScenesOnlyResult.complete carries the chunk loop's own
                // answer — deriving it here from current/total would put the determination in the
                // transport layer, which architecture.md forbids.
                out.set("extractionComplete", r.complete);
                out.set("chunksProcessed", j.getProgress().getCurrent());
                out.set("chunked", r.chunked);
                if (r.failedExtractions != null && !r.failedExtractions.isEmpty()) {
                    out.set("failedExtractions", r.failedExtractions);
                }
                return toJson(out);
            });
            if (job != null) {
                return Response.status(202).entity("{\"jobId\":\"" + job.getJobId()
                        + "\",\"status\":\"" + job.getStatus().name().toLowerCase() + "\"}").build();
            }
            // Could not register a job (no usable principal). Fall through and run synchronously
            // rather than silently dropping the request.
            logger.warn("Async extraction requested but the job could not be submitted — running synchronously");
        }

        // KI-10: registered under (principal, workObjectId) — the same id the client already holds
        // to fire a concurrent POST /{workObjectId}/cancel while this call is still in-flight.
        SummarizeProgress cancelToken = PictureBookCancelRegistry.register(user, workObjectId);
        try {
            PictureBookUtil.ScenesOnlyResult result = PictureBookUtil.extractScenesOnly(
                    user, workObjectId, count, chatConfigName, promptTemplateOverride, cancelToken,
                    seriesObjectId, startOffset, endOffset);
            if (result.chunked) {
                BaseRecord out = PictureBookUtil.buildResult();
                try {
                    out.set("sceneList", result.scenes);
                    /// Same correction as the async branch: this was hardcoded true, so a run that
                    /// stopped early (cancel, interrupt, unreachable LLM) was reported as complete.
                    out.set("extractionComplete", result.complete);
                    /// The real count, from the progress token the chunk loop already maintains
                    /// (setTotal/incrementCurrent in extractChunkedInternal). This was hardcoded
                    /// to -1, so a client had no way to tell a 17-chunk run from a 2-chunk one,
                    /// nor a complete run from one that stopped early on cancel.
                    out.set("chunksProcessed", cancelToken != null ? cancelToken.getCurrent() : -1);
                    out.set("chunked", true);
                    /// Surface the per-chunk parse failures. extractChunked/extractScenesOnly both
                    /// populate ScenesOnlyResult.failedExtractions, but this hand-built chunked
                    /// response dropped it, so on the auto-chunk path a client was never told that
                    /// some chunks failed to parse — a partial extraction looked identical to a
                    /// complete one. The non-chunked branch has no equivalent problem because it
                    /// returns the raw scene array.
                    if (result.failedExtractions != null && !result.failedExtractions.isEmpty()) {
                        out.set("failedExtractions", result.failedExtractions);
                        logger.warn("Chunked extraction for " + workObjectId + " completed with "
                            + result.failedExtractions.size() + " failed chunk extraction(s)");
                    }
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
        String seriesObjectId = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
            /// N-series item 4: forwarded verbatim to seed the cross-chapter roster from the series
            /// baseline cast (resolution stays in Objects7). Absent/blank = in-run-only, as before.
            seriesObjectId = params.get("seriesObjectId");
        }

        // KI-10: see extractScenesOnly()'s identical registration pattern.
        SummarizeProgress cancelToken = PictureBookCancelRegistry.register(user, workObjectId);
        try {
            BaseRecord result = PictureBookUtil.extractChunked(user, workObjectId, chatConfigName, cancelToken, seriesObjectId);
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
        String pb2BookObjectId = null;
        List<Map<String, Object>> sceneList = new ArrayList<>();
        List<Map<String, Object>> charDataList = new ArrayList<>();
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
            genre = params.get("genre");
            bookName = params.get("bookName");
            pb2BookObjectId = params.get("pb2BookObjectId");
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
                    sceneList, charDataList, dataPath, pb2BookObjectId);
            return Response.status(200).entity(toJson(meta)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } catch (RuntimeException e) {
            // Catch unchecked exceptions (e.g. from OlioContext init or createCharPerson) so Jersey
            // returns a parseable JSON body instead of a 500 HTML page. The UX reads body.error.
            logger.error("createFromScenes failed unexpectedly: " + e.getMessage(), e);
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String causeMsg = (root != e && root.getMessage() != null) ? root.getMessage() : null;
            if (causeMsg != null) {
                return Response.status(500).entity(
                    "{\"error\":" + escapeJson(errMsg) + ",\"cause\":" + escapeJson(causeMsg) + "}"
                ).build();
            }
            return errorResponse(500, errMsg);
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
     * POST /{bookObjectId}/characters/merge
     *
     * <p>Fold duplicate extracted characters into one. Body:
     * {@code { keepObjectId: "...", mergeObjectIds: ["...", "..."] }}.
     *
     * <p>Exists because extraction cannot always tell that two references are the same person. The
     * chunked extractor refers to an unnamed character differently in different chunks ("Darby's
     * dad", "the father", "Dad"); PictureBookUtil now canonicalises the spellings that are
     * unambiguously equivalent, but a bare relation in a book with two families genuinely cannot be
     * resolved automatically and is deliberately left as a separate character. This is how the
     * reader resolves it.
     *
     * <p>Transport only: PictureBookUtil.mergeCharacters authorizes the book (UPDATE), derives the
     * characters group server-side, repoints both persisted representations of a scene's characters,
     * and deletes the duplicates. Nothing here decides anything.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/characters/merge")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response mergeCharacters(@PathParam("bookObjectId") String bookObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        String keepObjectId = null;
        List<String> mergeObjectIds = new ArrayList<>();
        try {
            /// Read straight off the JSON rather than through olio.pictureBookRequest: the two
            /// fields this takes are a string and a string LIST, and adding a list field to that
            /// model for one endpoint is more surface than reading two keys.
            Map<String, Object> body = (json != null && !json.isBlank())
                    ? JSONUtil.getMap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            String.class, Object.class)
                    : null;
            if (body != null) {
                Object k = body.get("keepObjectId");
                if (k instanceof String) keepObjectId = ((String) k).trim();
                Object m = body.get("mergeObjectIds");
                if (m instanceof List) {
                    for (Object o : (List<?>) m) {
                        if (o instanceof String && !((String) o).isBlank()) mergeObjectIds.add(((String) o).trim());
                    }
                }
            }
        } catch (Exception e) {
            return Response.status(400).entity("{\"error\":true,\"message\":\"Malformed request body\"}").build();
        }
        if (keepObjectId == null || keepObjectId.isEmpty()) {
            return Response.status(400).entity("{\"error\":true,\"message\":\"keepObjectId is required\"}").build();
        }
        if (mergeObjectIds.isEmpty()) {
            return Response.status(400).entity("{\"error\":true,\"message\":\"mergeObjectIds is required\"}").build();
        }
        try {
            PictureBookUtil.MergeResult merged = PictureBookUtil.mergeCharacters(
                    user, bookObjectId, keepObjectId, mergeObjectIds);
            /// Report what actually moved, not a bare success flag: a merge that repointed no scenes,
            /// or could not delete a duplicate, is something the caller needs to see.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("keptName", merged.keptName);
            out.put("mergedNames", merged.mergedNames);
            out.put("scenesRepointed", merged.scenesRepointed);
            out.put("metaUpdated", merged.metaUpdated);
            out.put("failedDeletes", merged.failedDeletes);
            return Response.status(200).entity(JSONUtil.exportObject(out)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } catch (Exception e) {
            return Response.status(500).entity("{\"error\":true,\"message\":\"" + e.getMessage() + "\"}").build();
        }
    }

    /**
     * DELETE /{bookObjectId}/character/{objectId}
     *
     * <p>Remove one extracted character from the book and detach it from every scene that names it.
     * Extraction sometimes yields non-people (an animal, an expression) that a merge cannot dispose
     * of because there is nothing to fold them into; this is the removal path.
     *
     * <p>Transport only: PictureBookUtil.deleteCharacter authorizes the book (UPDATE), derives the
     * characters group server-side, detaches the scene notes, the book meta and the PB2 graph, then
     * deletes the record.
     */
    @RolesAllowed({"admin", "user"})
    @DELETE
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/character/{objectId:[0-9A-Za-z\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteCharacter(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("objectId") String objectId, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            PictureBookUtil.DeleteCharacterResult res = PictureBookUtil.deleteCharacter(user, bookObjectId, objectId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deleted", res.deleted);
            out.put("deletedName", res.deletedName);
            out.put("scenesDetached", res.scenesDetached);
            out.put("metaUpdated", res.metaUpdated);
            out.put("bindingsRemoved", res.bindingsRemoved);
            out.put("nodesRemoved", res.nodesRemoved);
            out.put("artifactsRemoved", res.artifactsRemoved);
            return Response.status(200).entity(JSONUtil.exportObject(out)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } catch (Exception e) {
            return Response.status(500).entity("{\"error\":true,\"message\":\"" + e.getMessage() + "\"}").build();
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
     * PUT /scene/{sceneObjectId}/config-override
     * Persist a per-scene SPARSE image-config override — the top tier of the PB2 config-precedence
     * merge (PbConfigUtil.resolveEffectiveConfig) for a ChapBook scene, which has no sceneNode of its
     * own. Body: { configOverride: "<sparse olio.sd.config JSON>" }; an absent/blank value clears it.
     *
     * <p>Transport only: the override is read as a STRING and handed to Objects7 verbatim. It is
     * deliberately NOT deserialized into an olio.sd.config record here — a materialized record carries
     * every defaulted field, which would make "overridden" indistinguishable from "default" and defeat
     * the sparse design (see PbConfigUtil and the olio.pb.scene.configOverride field description). All
     * validation (that the string is well-formed sparse olio.sd.config JSON) and the authorized,
     * PATCH-shaped persistence live in PbBookUtil.setSceneConfigOverride.
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/scene/{sceneObjectId:[0-9A-Za-z\\-]+}/config-override")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setSceneConfigOverride(@PathParam("sceneObjectId") String sceneObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");
        BaseRecord params = parseParams(json);
        String configOverride = (params != null ? params.get("configOverride") : null);
        try {
            boolean ok = PbBookUtil.setSceneConfigOverride(user, sceneObjectId, configOverride);
            return Response.status(200).entity("{\"updated\":" + ok + "}").build();
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
     * POST /series
     * Get-or-create the series for a slug, returning { seriesObjectId, worldObjectId } — the two ids the
     * N-series client needs before it fans a novel out into one bounded per-chapter extraction each
     * (every chapter's book.world = this series' shared world). Body: { seriesSlug, title? }.
     *
     * <p>POST, not GET, on purpose: this is get-or-create, and the create branch performs privileged
     * writes (the series row and its shared world) — {@code getCreateSeries} must never be reachable
     * from a read handler. Transport only: the acting user is passed straight through and every
     * decision (slug validation, olio-principal writes, the re-read that proves the caller's grant
     * landed) lives in {@code PbServiceFacade.createSeries} / {@code PbSeriesUtil.getCreateSeries}.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/series")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSeries(String json, @Context HttpServletRequest request,
            @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        if (params == null) {
            return errorResponse(400, "A request body is required");
        }
        String seriesSlug = params.get("seriesSlug");
        String title = params.get("title");
        try {
            return Response.status(200).entity(JSONUtil.exportObject(PbServiceFacade.createSeries(user,
                context.getInitParameter("datagen.path"), seriesSlug, title))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /chapter
     * Create the next chapter of a series (or a standalone book when no series is given), persisting its
     * linkage and source provenance and optionally seeding its cast. Body:
     * { seriesObjectId?, fromBookObjectId?, slug, title?, chapter?, sourceDataObjectId?,
     *   sourceRange?: { startOffset, endOffset, title }, copyRecordModel?, copyRecordObjectIds?: [...] }.
     *
     * <p>When {@code seriesObjectId} is present the chapter shares the series' ONE world and
     * {@code copyRecordObjectIds} (charPersons) seed overwritable per-chapter SHADOWS of the shared
     * baseline. Absent, it is a standalone book copied into its own world — §3.5's COPY-not-reference
     * choice, so deleting one book never destroys another's data. Transport only: all decisions and
     * writes live in {@code PbServiceFacade.createChapter}.
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
        String seriesObjectId = params.get("seriesObjectId");
        String fromBookObjectId = params.get("fromBookObjectId");
        String slug = params.get("slug");
        String title = params.get("title");
        // int returns 0 (not null) when absent (KI-25) — hasField is the only presence signal, so an
        // unsent ordinal stays null and the facade lets the series' bookCount choose it.
        Integer chapter = params.hasField("chapter") ? getInt(params, "chapter") : null;
        String sourceDataObjectId = params.get("sourceDataObjectId");
        String copyRecordModel = params.get("copyRecordModel");
        List<String> copyIds = new ArrayList<>();
        Object idsObj = params.get("copyRecordObjectIds");
        if (idsObj instanceof List) {
            for (Object o : (List<?>) idsObj) {
                if (o instanceof String) copyIds.add((String) o);
            }
        }
        // sourceRange is a nested olio.pb.sourceRange model on the request; forward ONLY its span
        // values, never the deserialized record itself — the facade creates its own owned by the caller,
        // so a client-supplied id/objectId/owner can never ride into persistence.
        Map<String, Object> sourceRange = null;
        Object srObj = params.get("sourceRange");
        if (srObj instanceof BaseRecord) {
            BaseRecord sr = (BaseRecord) srObj;
            sourceRange = new LinkedHashMap<>();
            if (sr.hasField("startOffset")) sourceRange.put("startOffset", sr.get("startOffset"));
            if (sr.hasField("endOffset")) sourceRange.put("endOffset", sr.get("endOffset"));
            Object rt = sr.get("title");
            if (rt != null) sourceRange.put("title", rt);
            if (sourceRange.isEmpty()) sourceRange = null;
        }
        try {
            return Response.status(200).entity(JSONUtil.exportObject(PbServiceFacade.createChapter(user,
                context.getInitParameter("datagen.path"), seriesObjectId, fromBookObjectId, slug, title,
                chapter, sourceDataObjectId, sourceRange, copyIds, copyRecordModel))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/cast/recopy
     * Recopy a series chapter's shadow cast from the series baseline (Q6 sync op "recopy"): discard this
     * chapter's shadow edits and reseed each shadow wholesale from its baseline counterpart. Scene links
     * resolve by name, so the reseed auto-relinks the chapter's scenes. Returns
     * { bookObjectId, slug, seriesObjectId, recopied, recopiedObjectIds }.
     *
     * <p>Transport only: authorization ({@code canUpdate}), the clear-then-reseed scoped to this chapter's
     * slug, and the olio-principal physical deletes all live in {@code PbServiceFacade.recopyChapter}.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/cast/recopy")
    @Produces(MediaType.APPLICATION_JSON)
    public Response recopyChapterCast(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200).entity(JSONUtil.exportObject(
                PbServiceFacade.recopyChapter(user, bookObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/cast/merge
     * Merge series baseline updates into a chapter's existing shadows (Q6 sync op "merge"): pull the
     * baseline's shared scalar attributes into each shadow while KEEPING the chapter's own overrides
     * (apparel, state/pose, narrative, portrait). Non-destructive; matched by name; no scene link is
     * re-pointed. Returns { bookObjectId, slug, seriesObjectId, merged }.
     *
     * <p>Not to be confused with {@code /{bookObjectId}/characters/merge}, which de-duplicates two distinct
     * characters within one book. Transport only: the pull and its authorization live in
     * {@code PbServiceFacade.mergeChapter} / {@code PbSharingUtil.mergeChapterShadows}.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/cast/merge")
    @Produces(MediaType.APPLICATION_JSON)
    public Response mergeChapterCast(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200).entity(JSONUtil.exportObject(
                PbServiceFacade.mergeChapter(user, bookObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /chapter/detect-boundaries?sourceDataObjectId=...
     * Read-only chapter-heading → character-offset boundary detection for a manuscript (data.data).
     * Returns the detected spans as [{startOffset, endOffset, title}, ...] — the exact shape the
     * POST /chapter body's {@code sourceRange} accepts, so the Ux review step can hand a chosen (or
     * hand-edited) range straight back to {@code createChapter}. A {@code title} is null for a leading
     * front-matter or no-heading range; an empty array means the document extracted to no usable text.
     *
     * <p>Transport only: manuscript resolution (as the acting user, via AccessPoint), bounded
     * content-type-aware text extraction, and the pure offset detector all live in
     * {@code PbServiceFacade.detectSourceBoundaries} / {@code PbChapterBoundaryUtil} in Objects7.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/chapter/detect-boundaries")
    @Produces(MediaType.APPLICATION_JSON)
    public Response detectChapterBoundaries(
            @QueryParam("sourceDataObjectId") String sourceDataObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200).entity(JSONUtil.exportObject(
                PbServiceFacade.detectSourceBoundaries(user, sourceDataObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /books
     * All olio.pb.book records the authenticated user can read in their organisation, sorted by name.
     * Returns lightweight DTOs: objectId, name, slug, bookStatus.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/books")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listBooks(@Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.listBooks(user))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /series/{seriesObjectId}/books
     * All chapter books of ONE series (N4), each with its series/chapter/world linkage, so the canvas can
     * render a whole-series view and order chapters within it. Unlike GET /books (the owner-filtered
     * selector), this returns a series' chapters to any ENTITLED caller — a holder of the series
     * Writer/Admin role — regardless of record owner. Returns DTOs: objectId, name, slug, bookStatus,
     * chapter, seriesObjectId, worldObjectId.
     *
     * <p>Transport only: series resolution (as the olio principal, for the FK), candidate enumeration by
     * the series FK, and per-chapter authorization as the acting user all live in
     * {@code PbServiceFacade.listSeriesBooks} in Objects7.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/series/{seriesObjectId:[0-9A-Za-z\\-]+}/books")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listSeriesBooks(@PathParam("seriesObjectId") String seriesObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.listSeriesBooks(user, seriesObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * GET /{bookObjectId}/pages
     * Ordered scene pages for a PB2 book: sceneIndex, title, blurb, summary, and the selected
     * composite artifact's dataObjectId (null when no composite generated yet). bookObjectId here is
     * the olio.pb.book objectId (not the PB1 book group). Delegates to PbServiceFacade.bookPageView,
     * which authorises via requireBook before listing scenes or touching artifacts.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/pages")
    @Produces(MediaType.APPLICATION_JSON)
    public Response bookPages(@PathParam("bookObjectId") String bookObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.bookPageView(user, bookObjectId))).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * PUT /{bookObjectId}/artifact/{artifactObjectId}/select
     * Mark an artifact as the selected revision in its (node, role) chain.
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/artifact/{artifactObjectId:[0-9A-Za-z\\-]+}/select")
    @Produces(MediaType.APPLICATION_JSON)
    public Response selectArtifact(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("artifactObjectId") String artifactObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.selectArtifact(user, bookObjectId, artifactObjectId)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * PUT /{bookObjectId}/node/{nodeObjectId}/canvas
     * Persist canvas geometry. Body: { x, y, w, h } — any subset accepted (absent keys are not touched).
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/node/{nodeObjectId:[0-9A-Za-z\\-]+}/canvas")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveCanvas(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId, String json,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        Integer x = getInt(params, "x");
        Integer y = getInt(params, "y");
        Integer w = getInt(params, "w");
        Integer h = getInt(params, "h");
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(
                    PbServiceFacade.saveCanvas(user, bookObjectId, nodeObjectId, x, y, w, h)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * PUT /{bookObjectId}/node/{nodeObjectId}/handle
     * Rename a node's handle (and its derived name). Body: { handle: "new-handle" }
     */
    @RolesAllowed({"admin", "user"})
    @PUT
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/node/{nodeObjectId:[0-9A-Za-z\\-]+}/handle")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response renameHandle(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId, String json,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(json);
        String handle = params != null ? (String) params.get("handle") : null;
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(
                    PbServiceFacade.renameHandle(user, bookObjectId, nodeObjectId, handle)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/node/{nodeObjectId}/test
     * Execute a single node synchronously: generate a new artifact revision from the SD backend.
     * Returns the new artifact identity, byte length, and downstream staleness list.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/node/{nodeObjectId:[0-9A-Za-z\\-]+}/test")
    @Produces(MediaType.APPLICATION_JSON)
    public Response testNode(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(PbServiceFacade.testNode(user, bookObjectId, nodeObjectId)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /{bookObjectId}/node/{nodeObjectId}/bind
     * Add a directed edge from sourceNodeObjectId → nodeObjectId (the consumer) with the given role.
     * Both nodes must belong to this book's workflow (cross-book addressing is a 404). A binding that
     * would create a cycle is refused with 400. Body: { role, sourceNodeObjectId }.
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/node/{nodeObjectId:[0-9A-Za-z\\-]+}/bind")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addBinding(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("nodeObjectId") String nodeObjectId,
            String body, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        BaseRecord params = parseParams(body);
        if (params == null) {
            return errorResponse(400, "Request body with role and sourceNodeObjectId is required");
        }
        String role = params.get("role");
        String sourceNodeObjectId = params.get("sourceNodeObjectId");
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(
                    PbServiceFacade.addBinding(user, bookObjectId, nodeObjectId, role, sourceNodeObjectId)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * DELETE /{bookObjectId}/binding/{bindingObjectId}
     * Delete a binding. The binding's consumer node must belong to this book's workflow —
     * a binding from another book is a 404.
     */
    @RolesAllowed({"admin", "user"})
    @DELETE
    @Path("/{bookObjectId:[0-9A-Za-z\\-]+}/binding/{bindingObjectId:[0-9A-Za-z\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBinding(@PathParam("bookObjectId") String bookObjectId,
            @PathParam("bindingObjectId") String bindingObjectId,
            @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        try {
            return Response.status(200)
                .entity(JSONUtil.exportObject(
                    PbServiceFacade.deleteBinding(user, bookObjectId, bindingObjectId)))
                .build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
    }

    /**
     * POST /migrate-v1
     * Migrate a PB1 book group to a PB2 {@code olio.pb.book}.
     * Body: { groupObjectId: "&lt;v1 book group objectId&gt;" }
     * Returns: { bookObjectId, slug, scenesImported, scenesFailed, warnings[] }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/migrate-v1")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response migrateV1Book(String json, @Context HttpServletRequest request,
            @Context ServletContext context) {
        OlioModelNames.use();
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        BaseRecord params = parseParams(json);
        if (params == null) return errorResponse(400, "Request body required: {groupObjectId}");

        String groupObjectId = params.get("groupObjectId");
        if (groupObjectId == null || groupObjectId.isBlank()) {
            return errorResponse(400, "groupObjectId is required");
        }

        String dataPath = context.getInitParameter("datagen.path");
        try {
            PbMigrationUtil.ImportResult result = PbMigrationUtil.importV1Book(user, dataPath, groupObjectId);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("bookObjectId", result.bookObjectId);
            out.put("slug", result.slug);
            out.put("scenesImported", result.scenesImported);
            out.put("scenesFailed", result.scenesFailed);
            out.put("warnings", result.warnings);
            return Response.status(200).entity(JSONUtil.exportObject(out)).build();
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        } catch (Exception e) {
            logger.error("migrateV1Book failed: " + e.getMessage(), e);
            return errorResponse(500, "Migration failed: " + e.getMessage());
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
        // Transport-only: PictureBookUtil.reset owns the delete logic and returns a concrete reason on a
        // partial/persistence failure (Issue 1). Copy that reason through unchanged — do not fabricate one.
        PictureBookUtil.DeleteResult rr;
        try {
            rr = PictureBookUtil.reset(user, bookObjectId);
        } catch (PictureBookException e) {
            return handlePictureBookException(e);
        }
        try {
            BaseRecord resetResult = PictureBookUtil.buildResult();
            resetResult.set("reset", rr.deleted);
            if (!rr.deleted && rr.reason != null) {
                resetResult.set("reason", rr.reason);
            }
            return Response.status(200).entity(toJson(resetResult)).build();
        } catch (Exception ex) {
            return Response.status(200).entity("{\"reset\":" + rr.deleted
                + (!rr.deleted && rr.reason != null ? ",\"reason\":" + escapeJson(rr.reason) : "")
                + "}").build();
        }
    }
}
