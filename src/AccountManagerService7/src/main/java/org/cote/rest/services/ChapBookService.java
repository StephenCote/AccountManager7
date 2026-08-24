package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.util.ServerConfigUtil;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * ChapBookService — thin REST transport for the ChapBook (poetry picture book) subsystem.
 * Auto-registered via RestServiceConfig packages("org.cote.rest.services").
 * <p>
 * Business logic lives in Objects7's {@link ChapBookUtil}. This class only parses the
 * incoming request JSON, calls ChapBookUtil with the authenticated user, and builds the HTTP
 * Response — following the architecture.md "no business logic in Service7" rule.
 * <p>
 * Endpoints under /olio/chap-book:
 *   POST /analyze/{poemObjectId}     — trigger LLM theme/mood/keywords analysis on one poem
 *   POST /create                     — create a ChapBook from selected poems
 *   GET  /poems                      — list olio.cb.poem records
 *   GET  /sets                       — list olio.cb.set records
 *   POST /set                        — create a poem set
 */
@DeclareRoles({"admin", "user"})
@Path("/olio/chap-book")
public class ChapBookService {

    private static final Logger logger = LogManager.getLogger(ChapBookService.class);

    private static final String CB_REQUEST_SCHEMA = "olio.pictureBookRequest";

    private String ensureSchema(String json) {
        if (json == null) return null;
        OlioModelNames.use();
        String trimmed = json.trim();
        if (trimmed.contains("\"schema\"")) return json;
        if (trimmed.startsWith("{")) {
            return "{\"schema\":\"" + CB_REQUEST_SCHEMA + "\"," + trimmed.substring(1);
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

    private Response errorResponse(int status, String message) {
        return Response.status(status).entity("{\"error\":\"" + message + "\"}").build();
    }

    // ─────────────────────────────── Poem analysis ───────────────────────────────

    /**
     * POST /analyze/{poemObjectId}
     * Trigger LLM theme/mood/keywords analysis on one {@code olio.cb.poem} record.
     * Body: { chatConfig: "configName" }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/analyze/{poemObjectId:[0-9A-Za-z\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response analyzePoemTheme(@PathParam("poemObjectId") String poemObjectId,
            String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        String chatConfigName = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            chatConfigName = params.get("chatConfig");
        }

        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM, FieldNames.FIELD_OBJECT_ID, poemObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        q.setCache(false);
        BaseRecord poem = IOSystem.getActiveContext().getAccessPoint().find(user, q);
        if (poem == null) {
            return errorResponse(404, "Poem not found: " + poemObjectId);
        }

        BaseRecord chatConfig = null;
        if (chatConfigName != null && !chatConfigName.isBlank()) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }
        if (chatConfig == null) {
            return errorResponse(400, "chatConfig is required for theme analysis");
        }

        try {
            ChapBookUtil.analyzePoemTheme(user, poem, chatConfig);
            return Response.status(200).entity("{\"success\":true}").build();
        } catch (Exception e) {
            logger.error("analyzePoemTheme failed for " + poemObjectId + ": " + e.getMessage(), e);
            return errorResponse(500, "Analysis failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────── ChapBook creation ───────────────────────────────

    /**
     * POST /create
     * Create a ChapBook (olio.pb.book with bookType=CHAPBOOK) from selected poems.
     * Body: { slug, title, poemObjectIds: [...], maxLinesPerPage, chatConfig }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/create")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createChapBook(String json, @Context HttpServletRequest request,
            @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        BaseRecord params = parseParams(json);
        if (params == null) return errorResponse(400, "Request body is required");

        String slug = params.get("slug");
        String title = params.get("title");
        if (slug == null || slug.isBlank()) return errorResponse(400, "slug is required");
        if (title == null || title.isBlank()) return errorResponse(400, "title is required");

        List<String> poemObjectIds = new ArrayList<>();
        Object poList = params.get("poemObjectIds");
        if (poList instanceof List) {
            for (Object item : (List<?>) poList) {
                if (item instanceof String) poemObjectIds.add((String) item);
                else if (item instanceof BaseRecord) poemObjectIds.add(((BaseRecord) item).get(FieldNames.FIELD_OBJECT_ID));
            }
        }
        if (poemObjectIds.isEmpty()) return errorResponse(400, "poemObjectIds must contain at least one poem objectId");

        int maxLinesPerPage = 8;
        Object mlp = params.get("maxLinesPerPage");
        if (mlp instanceof Number) maxLinesPerPage = ((Number) mlp).intValue();

        String chatConfigName = params.get("chatConfig");
        BaseRecord chatConfig = null;
        if (chatConfigName != null && !chatConfigName.isBlank()) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }

        // KI-30: same datagen.path init param used by GameService and PictureBookService
        String dataPath = context.getInitParameter("datagen.path");

        try {
            BaseRecord book = ChapBookUtil.createChapBook(user, dataPath, slug, title, poemObjectIds, maxLinesPerPage, chatConfig);
            return Response.status(200).entity(book.toFullString()).build();
        } catch (PictureBookException e) {
            return errorResponse(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            logger.error("createChapBook failed: " + e.getMessage(), e);
            return errorResponse(500, "ChapBook creation failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────── ChapBook rendering ───────────────────────────────

    /**
     * POST /render/{bookObjectId}
     * Generate SD images for all scenes of a CHAPBOOK book.
     * {@code sdApiType} and {@code sdServer} are read per-request from Servlet init-params —
     * never cached on the service class per architecture.md "Per-org config must never be
     * written to process-global state".
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/render/{bookObjectId:[0-9A-Za-z\\-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response renderChapBook(@PathParam("bookObjectId") String bookObjectId,
            String json, @Context HttpServletRequest request,
            @Context ServletContext context) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        String sdApiType = context.getInitParameter("sd.server.apiType");
        String sdServer  = ServerConfigUtil.getServerUrl(ServerConfigUtil.SERVER_SD, context.getInitParameter("sd.server"));
        if (sdApiType == null || sdServer == null) {
            return errorResponse(500, "SD server not configured (sd.server.apiType / sd.server init-params)");
        }

        BaseRecord chatConfig = null;
        BaseRecord params = parseParams(json);
        if (params != null) {
            String chatConfigName = params.get("chatConfig");
            if (chatConfigName != null && !chatConfigName.isBlank()) {
                chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
                if (chatConfig == null) {
                    logger.warn("renderChapBook: chatConfig '{}' not found — landscape prompts will use stored sdPrompt", chatConfigName);
                }
            }
        }

        try {
            int rendered = ChapBookUtil.renderChapBook(user, bookObjectId, sdApiType, sdServer, chatConfig);
            return Response.status(200).entity("{\"rendered\":" + rendered + "}").build();
        } catch (PictureBookException e) {
            return errorResponse(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            logger.error("renderChapBook failed: " + e.getMessage(), e);
            return errorResponse(500, "Render failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────── Poem library ───────────────────────────────

    /**
     * GET /poems
     * List {@code olio.cb.poem} records accessible to the user, ordered by name.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/poems")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listPoems(@QueryParam("startRecord") long startRecord,
            @QueryParam("recordCount") int recordCount,
            @Context HttpServletRequest request) {
        OlioModelNames.use();
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        int count = (recordCount > 0) ? recordCount : 25;
        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        q.setRequestRange(startRecord, count);
        q.setRequest(new String[]{
            FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
            FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
            "title", "author", "theme", "mood", "keywords"
        });
        try {
            q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
            q.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
        } catch (Exception ignored) {}

        QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
        BaseRecord[] results = (qr != null) ? qr.getResults() : new BaseRecord[0];
        return Response.status(200).entity(
            JSONUtil.exportObject(results, RecordSerializerConfig.getForeignUnfilteredModule())).build();
    }

    /**
     * POST /poem
     * Create an {@code olio.cb.poem} record.
     * Body: { title, author?, text, groupPath? }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/poem")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPoem(String json, @Context HttpServletRequest request) {
        OlioModelNames.use();
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        BaseRecord params = parseParams(json);
        if (params == null) return errorResponse(400, "Request body is required");

        String title = params.get("title");
        String author = params.get("author");
        String text = params.get("text");
        String groupPath = params.get("groupPath");

        if (title == null || title.isBlank()) return errorResponse(400, "title is required");
        if (text == null || text.isBlank()) return errorResponse(400, "text is required");

        try {
            BaseRecord created = ChapBookUtil.createPoem(user, title, author, text, groupPath);
            return Response.status(200).entity(created.toFullString()).build();
        } catch (PictureBookException e) {
            return errorResponse(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            logger.error("createPoem failed: " + e.getMessage(), e);
            return errorResponse(500, "Failed to create poem: " + e.getMessage());
        }
    }

    // ─────────────────────────────── Poem sets ───────────────────────────────

    /**
     * GET /sets
     * List {@code olio.cb.set} records accessible to the user.
     */
    @RolesAllowed({"admin", "user"})
    @GET
    @Path("/sets")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listSets(@QueryParam("startRecord") long startRecord,
            @QueryParam("recordCount") int recordCount,
            @Context HttpServletRequest request) {
        OlioModelNames.use();
        int count = (recordCount > 0) ? recordCount : 25;
        QueryResult result = ServiceUtil.generateListQueryResponse(
            OlioModelNames.MODEL_CB_SET, null, null, startRecord, count, request);
        if (result == null) return errorResponse(500, "Failed to list poem sets");
        return Response.status(200).entity(
            JSONUtil.exportObject(result.getResults(), RecordSerializerConfig.getForeignUnfilteredModule())).build();
    }

    /**
     * POST /set
     * Create a {@code olio.cb.set} (named poem collection).
     * Body: { name, description, groupPath }
     */
    @RolesAllowed({"admin", "user"})
    @POST
    @Path("/set")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSet(String json, @Context HttpServletRequest request) {
        BaseRecord user = ServiceUtil.getPrincipalUser(request);
        if (user == null) return errorResponse(401, "Unauthorized");

        BaseRecord params = parseParams(json);
        if (params == null) return errorResponse(400, "Request body is required");

        String name = params.get("name");
        if (name == null || name.isBlank()) return errorResponse(400, "name is required");

        String groupPath = params.get("groupPath");
        if (groupPath == null || groupPath.isBlank()) groupPath = "~/ChapBook";

        String description = params.get("description");

        try {
            org.cote.accountmanager.io.ParameterList plist = org.cote.accountmanager.io.ParameterList.newParameterList(
                FieldNames.FIELD_PATH, groupPath);
            plist.parameter(FieldNames.FIELD_NAME, name);
            BaseRecord set = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_SET, user, null, plist);
            if (set == null) return errorResponse(500, "Failed to instantiate poem set");
            if (description != null) set.set("description", description);
            BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, set);
            if (created == null) return errorResponse(500, "Failed to create poem set");
            return Response.status(200).entity(created.toFullString()).build();
        } catch (Exception e) {
            logger.error("createSet failed: " + e.getMessage(), e);
            return errorResponse(500, "Failed to create poem set: " + e.getMessage());
        }
    }
}
