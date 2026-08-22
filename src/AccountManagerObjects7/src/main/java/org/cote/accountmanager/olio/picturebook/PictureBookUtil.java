package org.cote.accountmanager.olio.picturebook;

import java.text.Normalizer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.EthnicityEnumType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.PromptTemplateComposer;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SdConfigUtil;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
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

    // Let the GPU recover between the pipeline's own heavy SD stages (portraits -> landscape ->
    // composite) — with hires/refiner enabled these can each be a full base+refiner pass, and
    // running them back-to-back with zero gap (the composite is the heaviest of the three, and
    // runs immediately after the landscape pass) was implicated in a real thermal-critical event
    // on shared GPU hardware. Same 5s value as the Ux752 wizard's between-SCENE cooldown — this
    // is the within-scene, between-STAGE counterpart to that.
    private static final long STAGE_COOLDOWN_MS = 5000;

    private static void stageCooldown() {
        try { Thread.sleep(STAGE_COOLDOWN_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // Default scene count when not specified — LLM decides actual count
    public static final int MAX_SCENES_DEFAULT = 10;

    // Max chars of source text sent per LLM call (scene extraction, per-character detail
    // extraction) and the auto-chunk trigger threshold in extractScenesOnly. Text longer than
    // this is hard-truncated at the character boundary (not chunked) wherever it's used as a
    // single-call prompt var — only extractScenesOnly's auto-chunk path processes the full text.
    // Tune this if a caller's document/model needs a different cutoff than the qwen3-class 8K
    // context window this default was picked for.
    public static final int MAX_EXTRACTION_TEXT_CHARS = 8000;

    // A leftover "{name}"-shaped token in a prompt template after var substitution means the
    // caller's vars map didn't match what the template actually needs — see callLlmInternal's
    // guard for the full explanation (promptTemplateOverride cross-purpose contamination).
    private static final Pattern UNSUBSTITUTED_PLACEHOLDER = Pattern.compile("\\{[a-zA-Z][a-zA-Z0-9_]*\\}");

    // Quality/anatomy negative prompt only — deliberately NOT style-exclusionary. PictureBook
    // derives style from the common olio.sd.config via SDUtil.getSDConfigPrompt(), so a caller can
    // select any canonical style; hardcoding "cartoon, anime" here would silently fight that
    // selection. Also deliberately excludes any censorship term ("nsfw" etc.) — this project's
    // content policy is explicit that character/apparel art is not filtered/censored (see
    // AccountManagerUx752/CLAUDE.md's Content Policy). Reuses NarrativeUtil's own general-purpose
    // negative prompt (used for character portraits) rather than maintaining a second, divergent copy.
    public static final String NEG_PROMPT = NarrativeUtil.getDefaultNegativePrompt();

    // Genre → SD theme mapping
    private static final Map<String, String> GENRE_THEME_MAP = new HashMap<>();
    static {
        GENRE_THEME_MAP.put("fantasy", "dark-medieval");
        GENRE_THEME_MAP.put("sci-fi", "sci-fi");
        GENRE_THEME_MAP.put("contemporary", "modern");
        GENRE_THEME_MAP.put("historical", "period");
    }

    private static final String PICTURE_BOOKS_DIR = "PictureBooks";

    /**
     * Name of the sub-group every book scene note is created in (see createFromScenes'
     * {@code ensureSubGroup(user, bookGroupPath, "Scenes")}). Used by
     * {@link #resolveSceneBookGroup} to tell a real book scene (whose owning book group is the
     * parent of this group) from the legacy {@code ~/Chat} single-image fallback.
     */
    private static final String SCENES_DIR = "Scenes";

    /**
     * Name of the sub-group every book charPerson is created in (see createFromScenes'
     * {@code ensureSubGroup(user, bookGroupPath, "Characters")}). Used by
     * {@link #authorizeCharacterApparel} to reach the owning book group the same way
     * {@link #resolveSceneBookGroup} does from a scene.
     */
    private static final String CHARACTERS_DIR = "Characters";

    // Per-character attributes written by createFromScenes' reduce step. ATTR_SCENE_REFS = CSV of the
    // scene indices the character appears in; ATTR_DESCRIPTION = the LLM-reduced, style/setting-free
    // visual description condensed from those scenes' content blocks — the source used for imaging
    // (read in resolveSceneCharacter). Distinct from the per-character style-override attribute.
    public static final String ATTR_SCENE_REFS = "pbSceneRefs";
    public static final String ATTR_DESCRIPTION = "pbDescription";
    // Prepended to ATTR_DESCRIPTION when it drives a portrait render, matching
    // NarrativeUtil.getSDPrompt's own opening tokens so an Attr2-based portrait keeps render quality.
    private static final String PORTRAIT_QUALITY_PREAMBLE =
            "8k highly detailed ((highest quality)) ((ultra realistic)) ((full body)) of ";

    private PictureBookUtil() {
    }

    // Stopwords skipped when counting a character's name mentions, so "The Guard" scores on "guard"
    // (not "the") and "Jideon de Rosa" on "jideon"/"rosa" (not "de").
    private static final Set<String> NAME_STOPWORDS = new HashSet<>(Arrays.asList(
            "the", "a", "an", "of", "and", "de", "la", "le", "el", "von", "van", "di", "da"));
    // Physical/costume descriptor cues used to weight how DESCRIPTIVE a passage is for a character.
    private static final Pattern DESCRIPTOR_WORDS = Pattern.compile(
            "\\b(hair|eyes?|wearing|wore|dressed|beard|mo(?:u)?stache|skin|complexion|freckl\\w*|tattoo\\w*|"
            + "tall|short|stocky|slender|slim|burly|lean|muscular|thin|heavy|build|scar\\w*|bald|"
            + "young|old|elderly|middle-aged|aged|man|woman|girl|boy|lady|gentleman|"
            + "armou?r|dress|gown|robe|cloak|coat|jacket|shirt|tunic|trousers|pants|skirt|blouse|"
            + "boots|shoes|sandals|hat|helmet|gloves|belt|hood|cape|scarf)\\b",
            Pattern.CASE_INSENSITIVE);

    private static String stripAccentsLower(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase();
    }

    /**
     * Relevance of a content block to a character: weighted name mentions + physical/costume
     * descriptor density. A high score means the passage both features and physically DESCRIBES the
     * character — exactly what the reduce wants. Name matching is accent/case-insensitive and skips
     * stopword name tokens.
     */
    private static int passageRelevance(String characterName, String block) {
        String nb = stripAccentsLower(block);
        int mentions = 0;
        for (String tok : stripAccentsLower(characterName).split("\\s+")) {
            if (tok.length() < 3 || NAME_STOPWORDS.contains(tok)) continue;
            int idx = 0;
            while ((idx = nb.indexOf(tok, idx)) >= 0) { mentions++; idx += tok.length(); }
        }
        int desc = 0;
        Matcher m = DESCRIPTOR_WORDS.matcher(block);
        while (m.find()) desc++;
        return mentions * 3 + desc;
    }

    /**
     * Concatenate a character's content blocks into a single passage for the reduce LLM call, bounded
     * to {@code maxChars} so a prolific character (present in many blocks) can't exceed the context
     * window. Blocks are RANKED by {@link #passageRelevance} first, so when the cap forces truncation
     * the MOST descriptive passages are kept — not whichever blocks happened to come first by
     * discovery order (this is the relevance weighting that keeps a Jideon-in-everything bounded).
     */
    private static String boundedPassages(String characterName, java.util.Collection<String> blocks, int maxChars) {
        List<String> ordered = new ArrayList<>();
        for (String b : blocks) if (b != null && !b.isBlank()) ordered.add(b);
        ordered.sort((a, b) -> Integer.compare(passageRelevance(characterName, b), passageRelevance(characterName, a)));
        StringBuilder sb = new StringBuilder();
        for (String b : ordered) {
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(b.trim());
            if (sb.length() >= maxChars) break;
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    /**
     * Persist a character's scene references (Attribute 1, {@link #ATTR_SCENE_REFS}) and condensed
     * description (Attribute 2, {@link #ATTR_DESCRIPTION}) as attributes on the charPerson via the
     * referenced-attribute mechanism (the only pattern that actually persists an attribute — see
     * {@link #tagApparelSceneIndex}). Best-effort: a failure here must not fail the whole book build.
     */
    private static void persistCharacterSceneAttributes(BaseRecord user, BaseRecord charPerson,
            List<Integer> sceneIndices, String description) {
        try {
            if (sceneIndices != null && !sceneIndices.isEmpty()) {
                String csv = sceneIndices.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","));
                IOSystem.getActiveContext().getRecordUtil().createRecord(
                        AttributeUtil.addAttribute(charPerson, ATTR_SCENE_REFS, csv));
            }
            if (description != null && !description.isBlank()) {
                IOSystem.getActiveContext().getRecordUtil().createRecord(
                        AttributeUtil.addAttribute(charPerson, ATTR_DESCRIPTION, description.trim()));
            }
        } catch (Exception e) {
            logger.warn("Failed to persist scene refs/description attributes for "
                    + charPerson.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
        }
    }

    // ----- Public parameter/result holders --------------------------------

    /** Result of {@link #extractScenesOnly}: either a raw scene list, or a chunked extraction summary. */
    public static final class ScenesOnlyResult {
        public final List<Map<String, Object>> scenes;
        public final boolean chunked;
        /**
         * Raw LLM responses that failed to parse as JSON during this extraction, one JSON-encoded
         * {context,error,rawResponse,failedAt} blob per failure — never null, empty when nothing
         * failed. No book/meta exists yet at this point in the pipeline (extractScenesOnly runs
         * before createFromScenes), so this is the only place a pre-book parse failure can surface;
         * once a book exists, the same failures are captured on .pictureBookMeta's failedExtractions
         * field instead. To investigate: inspect rawResponse, fix the JSON by hand, and re-drive the
         * normal entry point (e.g. patch the corrected scene into your own sceneList and call
         * createFromScenes/extract again) — there is no separate "redo" API.
         */
        public final List<String> failedExtractions;

        public ScenesOnlyResult(List<Map<String, Object>> scenes, boolean chunked) {
            this(scenes, chunked, new ArrayList<>());
        }

        public ScenesOnlyResult(List<Map<String, Object>> scenes, boolean chunked, List<String> failedExtractions) {
            this.scenes = scenes;
            this.chunked = chunked;
            this.failedExtractions = failedExtractions != null ? failedExtractions : new ArrayList<>();
        }
    }

    /**
     * Parsed request parameters for {@link #generateSceneImage}. All SD generation params (steps,
     * cfg, hires, seed, model, samplers, schedulers, loras, style, useKontext, sceneCreativity, …)
     * now live ON the supplied {@code olio.sd.config} record(s) — the single canonical style/param
     * seam, read via {@link SDUtil#getSDConfigPrompt(BaseRecord)} — not as flattened scalars here.
     */
    public static final class SceneGenerationParams {
        public String chatConfigName;
        public String promptOverride;
        public String promptTemplateOverride;
        // The book's COMMON olio.sd.config (style + composition + generation params). One config
        // drives portraits, landscape, and scene; style comes from getSDConfigPrompt(sdConfig).
        // When null, generateSceneImage falls back to the book's stored config, then randomSDConfig.
        public BaseRecord sdConfig;
        // Optional ALTERNATE olio.sd.config for the composite/Kontext step only (a different
        // pipeline/model); falls back to the common sdConfig when null.
        public BaseRecord compositeSdConfig;
        // Optional SPARSE per-scene override (a delta) overlaid onto the common sdConfig via
        // SDUtil.applyOverrides for this one scene.
        public BaseRecord sdConfigOverride;
        // Explicit book/fallback flag — defaults to true (all current picture-book scenes are
        // created under .../Scenes/); the client may pass isBook:false for the legacy ~/Chat
        // fallback that should not persist/reuse portraits.
        public Boolean isBookOverride;
        // PB2 (picturebook.v2) only: the olio.pb.book slug this scene's graph belongs to. When null,
        // PbPipelineUtil.deriveSlug() derives one from the PB1 book group name. Naming it explicitly
        // is preferred — the derivation is a convenience for callers that only know the PB1 group, and
        // a slug that fails to resolve means v2 recording is SKIPPED (logged), never that a book is
        // created on a render path. Ignored entirely when the flag is off.
        public String bookSlug;
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
     * Re-read a {@code data.data} as a TOP-LEVEL record so it is usable as a foreign reference.
     *
     * <p>Needed because a record reached through a parent's foreign field — {@code profile.portrait},
     * say — is a <b>nested sub-model</b>, and the query planner deliberately restricts the fields it
     * projects on sub-models to prevent recursion. The result is not fully identified, so handing it to
     * {@code AccessPoint.create}/{@code update} as an FK value makes the write return null. The same
     * applies to a record just returned by {@code AccessPoint.create}, which yields identity fields only.
     *
     * @return the fully-read record, or null when {@code objectId} is null or unreadable
     */
    private static BaseRecord readDataRecord(BaseRecord user, String objectId) {
        if (objectId == null) return null;
        Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        BaseRecord found = IOSystem.getActiveContext().getAccessPoint().find(user, q);
        if (found == null) {
            logger.warn("Could not re-read data.data " + objectId + " as a top-level record;"
                    + " a foreign reference to it will not persist");
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

    // ----- Scene-addressed authorization ---------------------------------

    /** The action a caller wants to take on a scene, for {@link #authorizeSceneAccess}. */
    public enum SceneAccessType {
        /** Read-only use of the scene (list/inspect). Requires Read on the owning book group. */
        READ,
        /**
         * Anything that mutates the scene or produces book content from it (image generation,
         * blurb regeneration, status persistence, prompt pre-resolution). Requires Update on the
         * owning book group.
         */
        WRITE
    }

    /**
     * Resolve a scene note by objectId AND authorize the caller against the <b>book that owns it</b>.
     *
     * <p><b>Why (security defect, fixed 2026-08-14).</b> The scene-addressed entry points —
     * {@link #generateSceneImage}, {@link #regenerateBlurb}, {@link #setSceneStatus} and
     * {@link #prepareSceneImagePrompts} — used to resolve the scene by objectId and act on it
     * without ever resolving, let alone authorizing, its book. That is a direct object reference
     * with no book-level check: the coarse {@code @RolesAllowed({"admin","user"})} on the REST
     * endpoints says "is a user", never "may act on <i>this</i> book". PictureBook2Plan.md §5.6
     * requires each of them to resolve the scene's book and re-authorize.
     *
     * <p><b>Where the check lives.</b> Here, in Objects7, driven by {@code AuthorizationUtil} (the
     * same PBAC evaluator {@code AccessPoint} itself uses) — not as an {@code if} block in the
     * Jersey resource method, which would be business logic in Service7 (.claude/rules/
     * architecture.md). Service7 keeps doing exactly one thing: catch {@link PictureBookException}
     * and map its status. This also mirrors §5.6's "{@code findBookGroup} stays the single choke
     * point" by putting the scene-side choke point immediately beside it.
     *
     * <p><b>Book resolution is by id, never by path.</b> Per §5.6b ("there is no read-up"), the
     * book group is reached by direct reference — scene {@code groupId} → its group → that group's
     * {@code parentId} — and each hop goes through {@code AccessPoint}, so an unreadable hop denies
     * rather than silently succeeding. Resolving a book <i>path</i> as the acting user and treating
     * success as authorization is explicitly forbidden there, and is not done.
     *
     * <p><b>Status codes.</b> A scene that does not exist, and a scene this caller cannot read, are
     * both {@code 404 "Scene not found"} — the convention already used throughout this class, where
     * {@code findBookGroup} returning null (absent OR PBAC-denied, indistinguishable at the
     * {@code AccessPoint.find} boundary) becomes {@code 404 "Book not found"}. A caller who CAN read
     * the scene but lacks rights on its book gets {@code 403} instead: that discloses nothing extra,
     * because a readable scene already implies its book exists.
     *
     * <p><b>Non-book scenes.</b> {@link #generateSceneImage} also supports a legacy single-image
     * fallback whose "scene" lives under {@code ~/Chat} rather than {@code <book>/Scenes}. There is
     * no book to authorize, so the scene's own group is authorized instead — the check is never
     * skipped.
     *
     * @return the resolved scene note (so callers don't re-query it)
     * @throws PictureBookException 404 when absent/unreadable, 403 when the book denies the action
     */
    public static BaseRecord authorizeSceneAccess(BaseRecord user, String sceneObjectId, SceneAccessType access) {
        if (user == null || sceneObjectId == null || sceneObjectId.isEmpty()) {
            throw new PictureBookException(404, "Scene not found");
        }
        Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        sq.planMost(false);
        BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        if (scene == null) throw new PictureBookException(404, "Scene not found");
        authorizeSceneRecord(user, scene, access);
        return scene;
    }

    /**
     * The book-authorization half of {@link #authorizeSceneAccess}, for callers that already hold a
     * scene record read through {@code AccessPoint} (e.g. the per-scene loop in
     * {@link #prepareSceneImagePrompts}). Throws 403 when the owning book denies the action.
     */
    public static void authorizeSceneRecord(BaseRecord user, BaseRecord scene, SceneAccessType access) {
        BaseRecord container = resolveSceneBookGroup(user, scene);
        if (container == null) {
            throw new PictureBookException(403, "Not authorized for this book");
        }
        PolicyResponseType prr = (access == SceneAccessType.WRITE)
                ? IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, container)
                : IOSystem.getActiveContext().getAuthorizationUtil().canRead(user, user, container);
        if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
            logger.warn("Denied " + access + " on book group " + container.get(FieldNames.FIELD_NAME)
                    + " for scene " + scene.get(FieldNames.FIELD_OBJECT_ID)
                    + " (user " + user.get(FieldNames.FIELD_NAME) + ")");
            throw new PictureBookException(403, "Not authorized for this book");
        }
    }

    /**
     * Resolve the group that owns a scene for authorization purposes: the book group when the scene
     * sits in a book's {@code Scenes} sub-group, otherwise the scene's own group (the legacy
     * {@code ~/Chat} single-image case). Every hop is an id-based {@code AccessPoint} read — no
     * path resolution, no read-up (§5.6b). Returns null when nothing could be resolved/read, which
     * the caller treats as a denial.
     */
    private static BaseRecord resolveSceneBookGroup(BaseRecord user, BaseRecord scene) {
        if (scene == null) return null;
        Long groupId = scene.get(FieldNames.FIELD_GROUP_ID);
        if (groupId == null || groupId <= 0L) return null;
        BaseRecord sceneGroup = IOSystem.getActiveContext().getAccessPoint()
                .findById(user, ModelNames.MODEL_GROUP, groupId);
        if (sceneGroup == null) return null;
        if (!SCENES_DIR.equals(sceneGroup.get(FieldNames.FIELD_NAME))) {
            // Not a book scene (legacy ~/Chat single-image fallback) — authorize its own group.
            return sceneGroup;
        }
        Long parentId = sceneGroup.get(FieldNames.FIELD_PARENT_ID);
        if (parentId == null || parentId <= 0L) return sceneGroup;
        BaseRecord bookGroup = IOSystem.getActiveContext().getAccessPoint()
                .findById(user, ModelNames.MODEL_GROUP, parentId);
        return (bookGroup != null ? bookGroup : null);
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
        return getBookSdConfigByPath(user, bookGroupPath);
    }

    /**
     * Read the book's stored common olio.sd.config directly from a known book group path (the
     * path-based counterpart to {@link #getBookSdConfig}, used by {@link #generateSceneImage} which
     * already has the scene's group path in hand and derives the book path from it). Returns null
     * when there's no meta / no stored config.
     */
    private static BaseRecord getBookSdConfigByPath(BaseRecord user, String bookGroupPath) {
        if (bookGroupPath == null) return null;
        BaseRecord meta = loadTypedMeta(user, bookGroupPath);
        if (meta == null) return null;
        return meta.get("sdConfig");
    }

    /**
     * Companion to {@link #persistBookSdConfig} for the optional ALTERNATE composite/Kontext config
     * — writes meta key "compositeSdConfig". Best-effort, same as persistBookSdConfig.
     */
    private static void persistBookCompositeSdConfig(BaseRecord user, String bookGroupPath, BaseRecord compositeSdConfig) {
        try {
            BaseRecord meta = loadTypedMeta(user, bookGroupPath);
            if (meta == null) return;
            meta.set("compositeSdConfig", compositeSdConfig);
            saveMeta(user, bookGroupPath, meta);
        } catch (Exception e) {
            logger.warn("Failed to persist book compositeSdConfig: " + e.getMessage());
        }
    }

    /**
     * Store the book's COMMON (and optional ALTERNATE composite) olio.sd.config once — the settings
     * the generation pipeline reads back as the base for every scene (portraits/landscape/scene).
     * Lets the test/Ux "set one config" then have generation pick it up (PUT /{bookObjectId}/settings).
     * fillStyleDefaults is applied so the stored config yields a complete getSDConfigPrompt style.
     * Returns the stored common config (or null if none supplied / no book meta).
     * <p>
     * S6: also persists each config as an olio.sd.config row and patches the olio.pb.book FK when
     * a PB2 book record exists in the book group.
     */
    public static BaseRecord setBookSdConfig(BaseRecord user, String bookObjectId, BaseRecord sdConfig, BaseRecord compositeSdConfig) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        long bookGroupId = bookGroup.get(FieldNames.FIELD_ID);
        long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
        if (sdConfig != null) {
            SDUtil.fillStyleDefaults(sdConfig);
            persistBookSdConfig(user, bookGroupPath, sdConfig);
            persistBookSdConfigFk(user, sdConfig, "book-sdConfig",
                OlioFieldNames.FIELD_PB_SD_CONFIG, bookGroupId, orgId);
        }
        if (compositeSdConfig != null) {
            SDUtil.fillStyleDefaults(compositeSdConfig);
            persistBookCompositeSdConfig(user, bookGroupPath, compositeSdConfig);
            persistBookSdConfigFk(user, compositeSdConfig, "book-compositeSdConfig",
                OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG, bookGroupId, orgId);
        }
        return getBookSdConfigByPath(user, bookGroupPath);
    }

    /**
     * Persist an olio.sd.config as a FK row in the book group and patch the book record's field.
     * Best-effort: no-ops if there is no PB2 book record in the group yet.
     */
    private static void persistBookSdConfigFk(BaseRecord user, BaseRecord sdConfig,
            String configName, String bookFieldName, long bookGroupId, long orgId) {
        try {
            String existingOid = sdConfig.get(FieldNames.FIELD_OBJECT_ID);
            if (existingOid == null || existingOid.trim().isEmpty()) {
                sdConfig.set(FieldNames.FIELD_NAME, configName);
                sdConfig.set(FieldNames.FIELD_GROUP_ID, bookGroupId);
                sdConfig.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            }
            BaseRecord persisted = SdConfigUtil.createOrUpdateConfig(user, sdConfig);
            if (persisted == null) {
                logger.warn("persistBookSdConfigFk: failed to persist " + configName);
                return;
            }
            // Find the olio.pb.book in the book group (PB2 only; PB1 books have no row)
            Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK,
                FieldNames.FIELD_GROUP_ID, bookGroupId);
            bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            bq.setRequest(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID,
                FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
            bq.setCache(false);
            BaseRecord book = IOSystem.getActiveContext().getAccessPoint().find(user, bq);
            if (book == null) {
                return; // PB1 book, no olio.pb.book row
            }
            BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK, bookFieldName);
            patch.set(bookFieldName, persisted);
            if (IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
                logger.warn("persistBookSdConfigFk: failed to patch book " + bookFieldName);
            }
        } catch (Exception e) {
            logger.warn("persistBookSdConfigFk error for " + configName + ": " + e.getMessage());
        }
    }

    /**
     * Set (or clear) a per-character LOCAL style override on the book, persisted on
     * {@code pictureBookMeta.characterStyles} keyed by charPerson objectId, and syncs the same
     * config to the persisted UI config store ({@code sdcfg-<charObjectId>} in the book owner's
     * {@code ~/Data/.preferences}) so the pipeline and the UI reimage workflow share one store (S5).
     * Styles ONLY that character's pipeline-rendered portrait; the composite, landscape, and every
     * other character keep the book's global common config. A null {@code sdConfig} removes the
     * meta-embedded override (but does NOT delete the persisted UI config, since the UI manages that
     * independently). Returns the resulting override list size.
     */
    @SuppressWarnings("unchecked")
    public static int setCharacterStyleOverride(BaseRecord user, String bookObjectId, String characterObjectId,
            String characterName, BaseRecord sdConfig) {
        if (characterObjectId == null || characterObjectId.isBlank()) {
            throw new PictureBookException(400, "characterObjectId is required");
        }
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        BaseRecord meta = loadTypedMeta(user, bookGroupPath);
        if (meta == null) throw new PictureBookException(404, "Book meta not found");
        try {
            List<BaseRecord> styles = meta.get("characterStyles");
            if (styles == null) { styles = new ArrayList<>(); }
            styles.removeIf(s -> s != null && characterObjectId.equals(s.get("characterObjectId")));
            if (sdConfig != null) {
                SDUtil.fillStyleDefaults(sdConfig);
                BaseRecord entry = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_CHARACTER_STYLE);
                entry.set("characterObjectId", characterObjectId);
                if (characterName != null) entry.set("characterName", characterName);
                entry.set("sdConfig", sdConfig);
                styles.add(entry);
            }
            meta.set("characterStyles", styles);
            saveMeta(user, bookGroupPath, meta);
            // S5: sync to the persisted UI config store so pipeline and reimage.js share one store
            if (sdConfig != null) {
                try {
                    BaseRecord prefsGroup = resolveUserPrefsGroup(user);
                    if (prefsGroup != null) {
                        long prefsGroupId = prefsGroup.get(FieldNames.FIELD_ID);
                        long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
                        SdConfigUtil.syncConfig(user, "sdcfg-" + characterObjectId, prefsGroupId, orgId, sdConfig);
                    }
                }
                catch (Exception e) {
                    logger.warn("setCharacterStyleOverride: failed to sync to SdConfigUtil for " + characterObjectId + ": " + e.getMessage());
                }
            }
            return styles.size();
        } catch (PictureBookException pbe) {
            throw pbe;
        } catch (Exception e) {
            logger.error("Failed to set character style override for " + characterObjectId + ": " + e.getMessage(), e);
            throw new PictureBookException(500, e.getMessage());
        }
    }

    /**
     * Read back the per-character LOCAL style override's {@code olio.sd.config} for a character, or
     * null if none is set. Symmetric with {@link #setCharacterStyleOverride}; used by the UI (and
     * tests) to show/verify the override. Does NOT read the UI reimage {@code <name>-SD.json} config.
     */
    @SuppressWarnings("unchecked")
    public static BaseRecord getCharacterStyleOverride(BaseRecord user, String bookObjectId, String characterObjectId) {
        if (characterObjectId == null) return null;
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        BaseRecord meta = loadTypedMeta(user, bookGroupPath);
        if (meta == null) return null;
        List<BaseRecord> styles = meta.get("characterStyles");
        if (styles == null) return null;
        for (BaseRecord s : styles) {
            if (s != null && characterObjectId.equals(s.get("characterObjectId"))) return s.get("sdConfig");
        }
        return null;
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
     * A scene-referenced character resolved to its actual charPerson record + portrait prompt text.
     * Shared holder so Stage 0 (building the scene-image LLM prompt's charNarrations, before any
     * SD call) and Stage 1 (actually rendering the portrait) resolve a character exactly once via
     * resolveSceneCharacter(), rather than duplicating the lookup/narrative-resolution logic twice.
     */
    private static final class ResolvedCharacter {
        final BaseRecord charPerson;
        final String name;
        // Full narrative sdPrompt — used to actually RENDER this character's portrait (Stage 1).
        // Carries per-character quality tokens AND (by construction, see resolveSceneCharacter) a
        // random art style + random setting/era baked in at narrative-creation time.
        final String portraitPrompt;
        // Style- AND setting-free appearance+outfit description — used ONLY for the scene-image
        // PROMPT's charNarrations, so a multi-character scene doesn't inherit each character's own
        // random style/era (which stitched conflicting styles into one composite — see the field's
        // derivation in resolveSceneCharacter). The book's single style is applied once elsewhere.
        final String sceneNarration;
        ResolvedCharacter(BaseRecord charPerson, String name, String portraitPrompt, String sceneNarration) {
            this.charPerson = charPerson;
            this.name = name;
            this.portraitPrompt = portraitPrompt;
            this.sceneNarration = sceneNarration;
        }
    }

    /**
     * Resolve one scene-referenced character (a {name:...} map or a bare objectId string, per
     * buildSceneEntry()'s persisted shape) to its charPerson record and portrait prompt text.
     * Pure DB lookups — no LLM/SD calls — so this is safe to call from Stage 0 (prompt-building,
     * before the LLM flush) as well as Stage 1 (portrait rendering).
     */
    @SuppressWarnings("unchecked")
    private static ResolvedCharacter resolveSceneCharacter(BaseRecord user, Object charItem, String sceneGroupPath) {
        String cname = null;
        String charOid = null;
        if (charItem instanceof Map) {
            cname = (String) ((Map<String, Object>) charItem).get("name");
        } else if (charItem instanceof String) {
            charOid = (String) charItem;
        }

        BaseRecord cp = null;
        try {
            if (charOid != null) {
                Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
                cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile", FieldNames.FIELD_STORE, FieldNames.FIELD_ATTRIBUTES});
                cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
            } else if (cname != null) {
                // Case-insensitive, whitespace-tolerant (ILIKE, trimmed) — the LLM's own scene-character
                // name and the name createCharPerson actually persisted aren't guaranteed to match on
                // case (confirmed live: an exact-match EQUALS query silently missed "Jideon" this way).
                String charGroupPath = sceneGroupPath.replace("/Scenes", "/Characters");
                BaseRecord charGrp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                        ModelNames.MODEL_GROUP, charGroupPath, GroupEnumType.DATA.toString(),
                        (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
                if (charGrp != null) {
                    Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
                    cq.field(FieldNames.FIELD_NAME, ComparatorEnumType.ILIKE, cname.trim());
                    cq.field(FieldNames.FIELD_GROUP_ID, charGrp.get(FieldNames.FIELD_ID));
                    cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                    cq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile", FieldNames.FIELD_STORE, FieldNames.FIELD_ATTRIBUTES});
                    cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
                    if (cp == null) {
                        // B6: the DB ILIKE+trim above does not fold Unicode diacritics, so a scene name
                        // like "Duña" silently misses a persisted "Duna" (and vice versa). On a miss,
                        // load this Characters group's records and match diacritic- + case-insensitively
                        // in Java (namesMatchAccentInsensitive). The ILIKE+trim primary path is kept
                        // exactly as-is; this only runs when it already returned nothing.
                        Query allq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
                        allq.field(FieldNames.FIELD_GROUP_ID, charGrp.get(FieldNames.FIELD_ID));
                        allq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                        allq.setRequest(new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative", "gender", "profile", FieldNames.FIELD_STORE, FieldNames.FIELD_ATTRIBUTES});
                        BaseRecord[] candidates = IOSystem.getActiveContext().getAccessPoint().list(user, allq).getResults();
                        if (candidates != null) {
                            for (BaseRecord cand : candidates) {
                                if (namesMatchAccentInsensitive(cname, cand.get(FieldNames.FIELD_NAME))) {
                                    logger.info("Resolved scene character '" + cname + "' to persisted '"
                                            + cand.get(FieldNames.FIELD_NAME) + "' via accent-insensitive fallback match");
                                    cp = cand;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    logger.warn("No Characters group found at " + charGroupPath + " while resolving scene character '" + cname + "'");
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to find character: " + (cname != null ? cname : charOid) + ": " + e.getMessage());
            return null;
        }

        if (cp == null) {
            logger.warn("Could not resolve scene character '" + (cname != null ? cname : charOid)
                    + "' to a charPerson record — this character's portrait will be skipped");
            return null;
        }
        if (cname == null) cname = cp.get(FieldNames.FIELD_NAME);

        // narrative is a foreign model (olio.narrative) — read its prompt fields from it. sdPrompt
        // is the full render prompt; physicalDescription/outfitDescription are the style- and
        // setting-free building blocks used for the scene narration (see sceneNarration below).
        String portraitPrompt = null;
        String physicalDesc = null;
        String outfitDesc = null;
        BaseRecord cpNarrative = cp.get("narrative");
        if (cpNarrative != null) {
            portraitPrompt = cpNarrative.get("sdPrompt");
            physicalDesc = cpNarrative.get("physicalDescription");
            outfitDesc = cpNarrative.get("outfitDescription");
            // The charPerson query above requests the bare "narrative" field name (no nested
            // dot-path/plan), which — per .claude/rules/model-api.md — only returns the foreign
            // model's default query fields. olio.narrative's "query" array is just ["id","groupId"],
            // so these come back null here even though they are persisted in the DB. Mirror the
            // profile->portrait two-step populate() pattern: explicitly re-populate the narrative
            // record itself for the fields actually needed.
            if ((portraitPrompt == null || portraitPrompt.isBlank())
                    || (physicalDesc == null || physicalDesc.isBlank())
                    || (outfitDesc == null || outfitDesc.isBlank())) {
                try {
                    IOSystem.getActiveContext().getReader().populate(cpNarrative,
                            new String[] { "sdPrompt", "physicalDescription", "outfitDescription" });
                    portraitPrompt = cpNarrative.get("sdPrompt");
                    physicalDesc = cpNarrative.get("physicalDescription");
                    outfitDesc = cpNarrative.get("outfitDescription");
                } catch (Exception e) {
                    logger.warn("Failed to populate narrative prompt fields for " + cname + ": " + e.getMessage());
                }
            }
            if (portraitPrompt == null || portraitPrompt.isBlank()) {
                portraitPrompt = physicalDesc;
            }
        }
        // Attribute 2 (ATTR_DESCRIPTION): the reduced, style/setting-free visual description condensed
        // from the character's OWN scenes (createFromScenes' reduce step) — PREFERRED for imaging.
        // Read from the charPerson's attributes (FIELD_ATTRIBUTES requested in the query above).
        String pbDescription = null;
        try {
            String d = AttributeUtil.getAttributeValue(cp, ATTR_DESCRIPTION, (String) null);
            if (d != null && !d.isBlank()) pbDescription = d.trim();
        } catch (Exception e) {
            logger.warn("Failed to read " + ATTR_DESCRIPTION + " attribute for " + cname + ": " + e.getMessage());
        }

        if ((portraitPrompt == null || portraitPrompt.isBlank()) && pbDescription == null) {
            logger.warn("No portrait prompt or reduced description for: " + cname + " — skipping portrait");
            return null;
        }

        String sceneNarration;
        if (pbDescription != null) {
            // Attr2 preferred: one style/setting-free visual description drives BOTH the composite
            // charNarration and (with a quality preamble) the portrait render base.
            sceneNarration = pbDescription;
            portraitPrompt = PORTRAIT_QUALITY_PREAMBLE + pbDescription;
        } else {
            // Fallback (books created before the reduce step): APPEARANCE + OUTFIT only, deliberately
            // NOT the full sdPrompt — which bakes in a per-character RANDOM art style and RANDOM
            // setting/era at creation (NarrativeUtil.getSDPrompt), the double-style/era bug. The book's
            // ONE style is applied once by appendConfigStyleOnce; the scene supplies its own
            // setting/action/mood.
            sceneNarration = (physicalDesc != null && !physicalDesc.isBlank()) ? physicalDesc.trim() : "";
            if (outfitDesc != null && !outfitDesc.isBlank()) {
                String o = outfitDesc.trim();
                sceneNarration = sceneNarration.isEmpty() ? o
                        : (sceneNarration.endsWith(".") ? sceneNarration + " " : sceneNarration + ". ") + o;
            }
            if (sceneNarration.isBlank()) sceneNarration = portraitPrompt; // never blank
        }
        // The comment above used to assert that physicalDescription/outfitDescription are
        // "style/setting-free". They are not, and neither is a stored pbDescription: whatever wrote
        // them may have carried the character's OWN creation-time style clause
        // (getSDConfigPrompt(randomSDConfig) via NarrativeUtil.getSDPrompt). Reported live by Stephen
        // 2026-08-10 — one composite prompt carried THREE styles: "Comic book panel in Archie Comics
        // style ..." for the first character, "Fashion photography for CR Fashion Book ..." for the
        // second, and the book's actual "Photograph taken with a Polaroid SX-70 ..." at the end,
        // while the book and both portraits were configured as `photograph`.
        //
        // The portrait path already guards against this (buildPortraitPrompt strips before appending);
        // the SCENE narration path never did, so every per-character style survived into the
        // composite. Strip here so the book's single config style, applied once downstream, is the
        // ONLY style in the prompt. Must happen BEFORE the FLUX/Kontext SDXL-weighting strip, while
        // getSDConfigPrompt's balanced parentheses are still present for stripTrailingConfigStyle to
        // recognise — after that strip the clause is bare prose and cannot be told from description.
        sceneNarration = stripTrailingConfigStyle(sceneNarration);
        return new ResolvedCharacter(cp, cname, portraitPrompt, sceneNarration);
    }

    /**
     * Append the canonical config style suffix ({@link SDUtil#getSDConfigPrompt(BaseRecord)}) to a
     * resolved prompt exactly once, so EVERY image in the book carries the SAME style guidance derived
     * from the one common {@code olio.sd.config} — the single style seam used across
     * portraits/landscape/scene, never a hand-rolled clause left to the LLM. Idempotent: a no-op if
     * the suffix is already present.
     */
    /**
     * Re-apply the CURRENT config's style to a cached prompt.
     *
     * <p>Reported by Stephen 2026-08-10: start generating with style #1, stop, change the style,
     * restart — the regenerated images still came out wrong. Cause: a resolved scene/landscape prompt
     * is PERSISTED into the scene note with the style clause of whatever config produced it, and the
     * cache-hit path returned it verbatim. Changing the book's style therefore never invalidated it,
     * so every "corrected" regeneration re-sent style #1's clause while the rest of the run used
     * style #2 — the two mixed, which is exactly the strange/incomplete output.
     *
     * <p>Re-styling is cheap and deterministic, so there is no reason to make the user clear a cache:
     * strip whatever trailing style clause is on the cached value and append the current one. The
     * LLM-authored description (and any prepended composition context) is untouched — only the style
     * suffix, which is code-owned, changes. Returns the input unchanged when it already carries the
     * current style, so a persist only happens on a real change.
     */
    private static String restyleCached(String cached, BaseRecord sdConfig) {
        if (cached == null || cached.isBlank()) return cached;
        String restyled = appendConfigStyleOnce(stripTrailingConfigStyle(cached), sdConfig);
        if (!cached.equals(restyled)) {
            logger.info("Cached prompt carried a stale style clause — re-styled to the current config");
        }
        return restyled;
    }

    /**
     * The ONE string resolveLandscapePrompt may produce when it has no setting and no mood to work
     * from. Named because two places must agree on it exactly: the write path and the cache-validation
     * guard that decides whether a cached value is legitimate or a pre-fix hallucination.
     */
    private static final String BLANK_LANDSCAPE_FALLBACK = "A detailed environment";

    private static String appendConfigStyleOnce(String prompt, BaseRecord sdConfig) {
        String clause = SDUtil.getSDConfigPrompt(sdConfig);
        if (prompt == null || prompt.isBlank()) return clause;
        String p = prompt.trim();
        if (clause == null || clause.isBlank()) return p;
        if (p.contains(clause)) return p;
        return p + (p.endsWith(".") || p.endsWith(",") ? " " : ". ") + clause;
    }

    /**
     * Remove a trailing {@link SDUtil#getSDConfigPrompt}-shaped style clause from a prompt so a fresh
     * style (the book global, or a per-character override) can be applied cleanly instead of stacking
     * on top of whatever style the source prompt already carried. A character's narrative
     * {@code sdPrompt} bakes in a RANDOM style at creation time ({@code getSDConfigPrompt(randomSDConfig)}
     * via NarrativeUtil.getSDPrompt); appending the book style on top of that produced double-styled
     * portraits. getSDConfigPrompt always emits its style as a single balanced-parenthesised group at
     * the very end — {@code (art).} or {@code ((Photograph) taken with a (X) camera ...).} — so this
     * strips exactly that final balanced group (and its trailing '.'), leaving the appearance/outfit/
     * setting text untouched. A no-op when the prompt doesn't end in such a group (e.g. a
     * physicalDescription fallback ending in plain text) or when the parens are unbalanced (never risk
     * mangling — fall back to the original).
     */
    public static String stripTrailingConfigStyle(String prompt) {
        if (prompt == null) return null;
        String t = prompt.stripTrailing();
        String noDot = t.endsWith(".") ? t.substring(0, t.length() - 1).stripTrailing() : t;
        if (!noDot.endsWith(")")) return t;            // no trailing style clause
        int depth = 0, cut = -1;
        for (int i = noDot.length() - 1; i >= 0; i--) {
            char c = noDot.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') { depth--; if (depth == 0) { cut = i; break; } }
        }
        if (cut < 0) return t;                          // unbalanced — don't risk mangling
        return noDot.substring(0, cut).stripTrailing();
    }

    /**
     * Build the SD {@code description} for a character portrait: strip whatever (often random) style
     * the source narrative prompt already carried, then apply exactly ONE style — the given effective
     * config (a per-character LOCAL override, or the book global {@code common}). The appearance/outfit
     * text is left intact; only the style clause is (re)set. This is the single seam the Stage 1
     * portrait render uses, so it can be unit-tested without an SD call.
     */
    public static String buildPortraitDescription(String portraitPrompt, BaseRecord effectiveStyleConfig) {
        return appendConfigStyleOnce(stripTrailingConfigStyle(portraitPrompt), effectiveStyleConfig);
    }

    /**
     * Find the book owner's {@code ~/Data/.preferences} group (DATA type).
     * Uses the denormalized {@code homeDirectory.path} string (always stored on the user record,
     * even when {@code homeDirectory} model is not planned) to build the full path, then delegates
     * to {@code PathUtil.findPath}. Returns {@code null} when the group has never been created by
     * the UI — callers must degrade gracefully. Never creates.
     */
    private static BaseRecord resolveUserPrefsGroup(BaseRecord user) {
        try {
            long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
            // FIELD_HOME_DIRECTORY_FIELD_PATH = "homeDirectory.path" — a denormalized string field
            // always stored on the user row, even without planning the homeDirectory model.
            // PathUtil.makePath uses the same field, so both expand ~/... the same way.
            String homePath = user.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
            if (homePath != null && !homePath.isEmpty()) {
                String prefsPath = homePath + "/Data/.preferences";
                return IOSystem.getActiveContext().getPathUtil().findPath(
                    user, ModelNames.MODEL_GROUP, prefsPath, GroupEnumType.DATA.toString(), orgId);
            }
            // Fallback: walk via homeDirectory group model if the path string is not available.
            BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
            if (homeDir == null) return null;
            Long homeId = homeDir.get(FieldNames.FIELD_ID);
            if (homeId == null || homeId == 0L) return null;
            Query dataQ = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_NAME, "Data");
            dataQ.field(FieldNames.FIELD_PARENT_ID, homeId);
            dataQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            dataQ.setCache(false);
            BaseRecord dataDir = IOSystem.getActiveContext().getAccessPoint().find(user, dataQ);
            if (dataDir == null) return null;
            Long dataId = dataDir.get(FieldNames.FIELD_ID);
            Query prefQ = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_NAME, ".preferences");
            prefQ.field(FieldNames.FIELD_PARENT_ID, dataId);
            prefQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            prefQ.setCache(false);
            return IOSystem.getActiveContext().getAccessPoint().find(user, prefQ);
        }
        catch (Exception e) {
            logger.warn("resolveUserPrefsGroup: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the effective STYLE config for ONE character's portrait. Priority (highest first):
     * <ol>
     *   <li>The persisted {@code olio.sd.config} record keyed {@code sdcfg-<charObjectId>} in the
     *       user's {@code ~/Data/.preferences} group — written by the UI reimage workflow (S5).</li>
     *   <li>The book's per-character LOCAL meta override ({@code pictureBookMeta.characterStyles},
     *       matched by charPerson objectId).</li>
     *   <li>The book's global {@code common} config.</li>
     * </ol>
     * Only the style clause differs across configs — other generation params come from {@code common}
     * at the call site. Never throws — any lookup failure degrades to {@code common}.
     * <p>
     * Public so the S5 convergence test can call it without a live SD server.
     */
    @SuppressWarnings("unchecked")
    public static BaseRecord resolveCharacterStyleConfig(BaseRecord user, String sceneGroupPath, String characterObjectId, BaseRecord common) {
        if (characterObjectId == null || sceneGroupPath == null) return common;
        BaseRecord metaOverride = null;
        try {
            String bookGroupPath = sceneGroupPath.replace("/Scenes", "");
            BaseRecord meta = loadTypedMeta(user, bookGroupPath);
            if (meta != null) {
                List<BaseRecord> styles = meta.get("characterStyles");
                if (styles != null) {
                    for (BaseRecord s : styles) {
                        if (s != null && characterObjectId.equals(s.get("characterObjectId"))) {
                            BaseRecord ov = s.get("sdConfig");
                            if (ov != null) {
                                SDUtil.fillStyleDefaults(ov);
                                metaOverride = ov;
                                break;
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            logger.warn("Failed to resolve meta character style override for " + characterObjectId + ": " + e.getMessage());
        }
        // Check persisted UI config store (sdcfg-<charObjectId>) — preferred over meta
        try {
            BaseRecord prefsGroup = resolveUserPrefsGroup(user);
            if (prefsGroup != null) {
                long prefsGroupId = prefsGroup.get(FieldNames.FIELD_ID);
                long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
                BaseRecord persisted = SdConfigUtil.findConfig(user, "sdcfg-" + characterObjectId, prefsGroupId, orgId);
                if (persisted != null) {
                    SDUtil.fillStyleDefaults(persisted);
                    logger.info("Portrait style override for character " + characterObjectId
                            + " -> persisted sdcfg record, style '" + persisted.get("style") + "'");
                    return persisted;
                }
            }
        }
        catch (Exception e) {
            logger.warn("Failed to check persisted style config for " + characterObjectId + ": " + e.getMessage());
        }
        if (metaOverride != null) {
            logger.info("Portrait style override for character " + characterObjectId
                    + " -> meta inline style '" + metaOverride.get("style") + "'");
            return metaOverride;
        }
        return common;
    }

    /**
     * Load the book-level composition/art-direction context (a discrete, persisted fact on
     * .pictureBookMeta) for the scene's book, so every scene/landscape prompt can be anchored to the
     * SAME context and stay consistent across the whole book. Returns "" when there's no book meta or
     * no context set — additive, never a hard failure.
     */
    private static String loadCompositionContext(BaseRecord user, BaseRecord scene) {
        try {
            String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
            if (sceneGroupPath == null) return "";
            String bookGroupPath = sceneGroupPath.replace("/Scenes", "");
            BaseRecord meta = loadTypedMeta(user, bookGroupPath);
            if (meta == null) return "";
            String ctx = meta.get("compositionContext");
            return (ctx != null) ? ctx.trim() : "";
        } catch (Exception e) {
            logger.warn("Failed to load composition context: " + e.getMessage());
            return "";
        }
    }

    /**
     * Prepend the book's composition/art-direction context to a resolved prompt exactly once — a
     * discrete, code-owned fact shared by every scene so the book stays consistent; the LLM composes
     * the scene-specific part on top of it. Idempotent; a no-op when the context is blank/present.
     */
    private static String prependContextOnce(String context, String prompt) {
        if (context == null || context.isBlank()) return prompt;
        String c = context.trim();
        if (prompt == null || prompt.isBlank()) return c;
        if (prompt.contains(c)) return prompt;
        return c + (c.endsWith(".") || c.endsWith(",") ? " " : ". ") + prompt.trim();
    }

    /**
     * Resolve (and cache) the scene-image (composite) prompt for a scene, combining resolved scene
     * characters' portrait descriptions with setting/action/mood into a proper SD tag-style prompt
     * via the pictureBook.scene-image-prompt template — rather than the old raw narrative-sentence
     * concatenation. Same cache/fallback/persist shape as resolveLandscapePrompt: check the scene
     * note's cached "scenePrompt" first, call the LLM, fall back to a raw concatenation (never leave
     * the composite with no prompt at all) on failure, cache either way. Callers MUST invoke this —
     * and OllamaModelUtil.unloadAll() — before any SD call in the same pipeline run (Stage 0).
     */
    private static String resolveScenePrompt(BaseRecord user, BaseRecord scene, BaseRecord chatConfig,
            String action, String setting, String mood, BaseRecord sdConfig, List<String> charNarrations, String promptTemplateOverride) {
        String cached = getSceneTextField(scene, "scenePrompt");
        // Self-heal: a scene generated before the guards in isErrorOrEmptyPayload/callLlmInternal
        // existed may have a conversational-refusal or unsubstituted-placeholder string cached as
        // its "scenePrompt" (see KI-31 follow-up) — don't trust the cache in that case, regenerate.
        String charNarrationsText = String.join("\n", charNarrations);
        boolean hasRealInputNow = (setting != null && !setting.isBlank())
            || (action != null && !action.isBlank())
            || (mood != null && !mood.isBlank())
            || !charNarrationsText.isEmpty();
        if (cached != null && !cached.isBlank() && !isErrorOrEmptyPayload(cached)) {
            // Second self-heal (2026-07-23, found live on Stephen's /Public catatone book): if
            // setting/action/mood/characters are STILL blank right now, the only thing this method
            // can legitimately produce (per the guard below) is SDUtil.getSDConfigPrompt(sdConfig)'s
            // fixed style text — anything else cached must be a pre-fix hallucination from a
            // blank-input LLM call that produced plausible-but-unrelated content (not error-shaped,
            // so the check above never caught it). This is a precise check, not a fuzzy
            // content-similarity guess: with the guard in place, blank input can never again produce
            // anything but that one known string, so a mismatch is conclusive, not a heuristic.
            // Same correction as the landscape guard below: compare against what the write path
            // actually persists (style suffix + optional composition-context prefix applied), not
            // against the bare style clause.
            String deterministicBlankScene = appendConfigStyleOnce(
                prependContextOnce(loadCompositionContext(user, scene), SDUtil.getSDConfigPrompt(sdConfig)), sdConfig);
            if (hasRealInputNow || deterministicBlankScene.equals(cached)
                    || SDUtil.getSDConfigPrompt(sdConfig).equals(cached)) {
                // Serve the cached DESCRIPTION but with the CURRENT style — a style change must not
                // be defeated by a prompt cached under the previous one.
                String restyled = restyleCached(cached, sdConfig);
                if (!restyled.equals(cached)) {
                    updateSceneTextField(user, scene, "scenePrompt", restyled);
                }
                return restyled;
            }
            logger.warn("Scene-image prompt: cached value doesn't match blank-input's only legitimate "
                + "output even though setting/action/mood/characters are still blank — this must be a "
                + "pre-fix hallucinated result; discarding and regenerating");
        }

        // Same principle as resolveLandscapePrompt's guard: don't ask the LLM to invent a scene
        // from nothing. If setting/action/mood are all blank AND there are no character
        // descriptions either, there is no real information for the LLM to work with — it will
        // fabricate something plausible-but-disconnected rather than error, which then gets cached
        // as if it were a real result (see the landscape-prompt guard's fuller explanation).
        String scenePrompt;
        if (!hasRealInputNow) {
            logger.warn("Scene-image prompt: setting/action/mood/characters are all blank — skipping "
                + "the LLM call and using the deterministic fallback instead of risking an unrelated "
                + "hallucinated result");
            scenePrompt = SDUtil.getSDConfigPrompt(sdConfig);
        } else {
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("setting", setting);
            vars.put("action", action);
            vars.put("mood", mood);
            vars.put("charNarrations", charNarrationsText.isEmpty() ? "(no characters in this scene)" : charNarrationsText);
            scenePrompt = callLlm(user, chatConfig, "pictureBook.scene-image-prompt", vars, promptTemplateOverride);
            if (isErrorOrEmptyPayload(scenePrompt)) {
                logger.warn("Scene-image prompt LLM call failed — falling back to raw concatenation");
                StringBuilder fallback = new StringBuilder();
                if (!charNarrationsText.isEmpty()) fallback.append(charNarrationsText).append(". ");
                if (action != null && !action.isEmpty()) fallback.append("They are ").append(action).append(". ");
                if (setting != null && !setting.isEmpty()) fallback.append("Setting: ").append(setting).append(". ");
                if (mood != null && !mood.isEmpty()) fallback.append("Mood: ").append(mood).append(". ");
                fallback.append(SDUtil.getSDConfigPrompt(sdConfig));
                scenePrompt = fallback.toString();
            }
        }
        // Discrete, code-owned facts applied deterministically (not left to the LLM): the book-level
        // composition/art-direction anchor (prepended, shared by every scene) and the config style
        // suffix (appended) — so the whole book stays visually consistent, matching how SDUtil builds
        // prompts from the one common olio.sd.config.
        scenePrompt = prependContextOnce(loadCompositionContext(user, scene), scenePrompt);
        scenePrompt = appendConfigStyleOnce(scenePrompt, sdConfig);
        updateSceneTextField(user, scene, "scenePrompt", scenePrompt);
        return scenePrompt;
    }

    /**
     * Resolve a scene's ordinal position within its book. data.note scene records have no "index"
     * field — the ordinal only exists on the olio.pictureBookScene DTO inside the book's
     * .pictureBookMeta JSON blob (see buildSceneEntry()). Returns 0 (safe default — matches
     * "always eligible" for scene-tagged apparel with sceneIndex 0) if it can't be resolved, e.g.
     * for the ~/Chat single-image fallback which has no book meta at all.
     */
    @SuppressWarnings("unchecked")
    private static int resolveCurrentSceneIndex(BaseRecord user, String sceneGroupPath, String sceneObjectId) {
        if (sceneGroupPath == null || !sceneGroupPath.endsWith("/Scenes")) return 0;
        String bookGroupPath = sceneGroupPath.substring(0, sceneGroupPath.length() - "/Scenes".length());
        try {
            BaseRecord metaRec = loadMeta(user, bookGroupPath);
            if (metaRec == null) return 0;
            String metaJson = metaRec.get("text");
            if (metaJson == null || metaJson.isEmpty()) return 0;
            Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
            Object scenesObj = meta.get("scenes");
            if (scenesObj instanceof List) {
                for (Object so : (List<Object>) scenesObj) {
                    if (so instanceof Map) {
                        Map<String, Object> sm = (Map<String, Object>) so;
                        if (sceneObjectId.equals(sm.get("objectId"))) {
                            Object idxObj = sm.get("index");
                            if (idxObj instanceof Number) return ((Number) idxObj).intValue();
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve current scene index for " + sceneObjectId + ": " + e.getMessage());
        }
        return 0;
    }

    /**
     * Scene-tagged apparel selection: pick the highest sceneIndex-tagged apparel entry
     * &lt;= currentSceneIndex, set it inuse=true and every other *tagged* entry inuse=false
     * (toggle only the inuse boolean — never unlink/replace, so every scene outfit stays in
     * store.apparel for reuse as the user moves through scenes). Returns whether the character
     * has ANY scene-tagged apparel at all — false means "leave everything exactly as-is," which
     * is the common case (untagged base outfit from the wizard) and must never regress existing
     * behavior for books/characters not using this feature.
     */
    private static boolean selectSceneApparel(BaseRecord user, BaseRecord charPerson, int currentSceneIndex) {
        BaseRecord storeRef = charPerson.get(FieldNames.FIELD_STORE);
        Long storeId = (storeRef != null) ? storeRef.get(FieldNames.FIELD_ID) : null;
        if (storeId == null || storeId <= 0L) return false;

        // reader.populate() is a no-op here — store.apparel/apparel.attributes are list fields
        // that BaseRecord already default-instantiates to an empty list, so populate() sees the
        // field as "already set" and never actually queries the DB (confirmed live: a second
        // apparel linked via member() never appeared, and a tagged sceneIndex attribute never
        // resolved, until switched to an explicit fresh Query — the exact same class of gotcha as
        // .claude/rules/model-api.md's "list schema loss"/cache-staleness notes, just triggered by
        // populate()'s own "already populated" skip instead of a search-result cache).
        Query storeQ = QueryUtil.createQuery(OlioModelNames.MODEL_STORE, FieldNames.FIELD_ID, storeId);
        storeQ.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        storeQ.setCache(false);
        storeQ.planMost(true);
        BaseRecord store = IOSystem.getActiveContext().getAccessPoint().find(user, storeQ);
        if (store == null) return false;
        List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
        if (appl == null || appl.isEmpty()) return false;

        BaseRecord best = null;
        int bestIdx = Integer.MIN_VALUE;
        boolean anyTagged = false;
        for (BaseRecord a : appl) {
            try {
                Long apparelId = a.get(FieldNames.FIELD_ID);
                Query attrQ = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_ID, apparelId);
                attrQ.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                attrQ.setCache(false);
                attrQ.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ATTRIBUTES });
                BaseRecord aFresh = IOSystem.getActiveContext().getAccessPoint().find(user, attrQ);
                Integer si = (aFresh != null) ? AttributeUtil.getAttributeValue(aFresh, "sceneIndex", null) : null;
                if (si == null) continue;
                anyTagged = true;
                if (si <= currentSceneIndex && si > bestIdx) { bestIdx = si; best = a; }
            } catch (Exception e) {
                logger.warn("Failed to read sceneIndex attribute on apparel: " + e.getMessage());
            }
        }
        if (!anyTagged || best == null) return false;

        for (BaseRecord a : appl) {
            boolean shouldUse = (a == best);
            Boolean cur = a.get(OlioFieldNames.FIELD_IN_USE);
            if (cur == null || cur.booleanValue() != shouldUse) {
                try {
                    a.setValue(OlioFieldNames.FIELD_IN_USE, shouldUse);
                    BaseRecord patch = a.copyRecord(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_IN_USE });
                    IOSystem.getActiveContext().getAccessPoint().update(user, patch);
                } catch (Exception e) {
                    logger.warn("Failed to persist apparel inuse flip: " + e.getMessage());
                }
            }
        }
        return true;
    }

    /**
     * Tag an apparel entry with the scene index it should first apply from (see
     * selectSceneApparel). Used by the character-editor UI after generating a new outfit via the
     * existing outfitBuilder.js flow — retroactively tags the freshly-generated apparel.
     *
     * <p><b>PB2 §5.6's last remaining REST authorization gap, closed 2026-08-17.</b> This method
     * used to resolve an apparel record by objectId and write to it with <b>no book check at all</b> —
     * the same shape {@code authorizeSceneAccess} was written to fix for the scene-addressed entry
     * points, left as a follow-up when that patch landed. The REST route already carries the owning
     * character ({@code PUT /character/{objectId}/apparel/{apparelObjectId}/scene-tag}) and simply
     * discarded it. Now the character is the authorized root: it is read through {@code AccessPoint}
     * (so {@code canRead} applies), its book group is authorized for {@code WRITE}, and the apparel
     * must actually be in <b>that</b> character's store.
     *
     * <p><b>Why the apparel's own group cannot be the check.</b> {@code ApparelUtil.constructApparel}
     * creates apparel in the <i>world's</i> Apparel group, olio-owned and shared — so authorizing
     * the apparel's group would authorize the shared corpus, not the book. Authorization has to come
     * from the character, which does live in {@code <book>/Characters}.
     *
     * @param charObjectId the owning character; required, and the thing actually authorized
     * @throws PictureBookException 404 when the character or apparel is absent/unreadable, 403 when
     *         the owning book denies the write, 400 when the apparel is not this character's
     */
    public static boolean tagApparelSceneIndex(BaseRecord user, String charObjectId, String apparelObjectId,
            int sceneIndex) {
        // PB1 guard: identical pattern to persistBookSdConfigFk. Characters in a PB1 world have no
        // olio.pb.book row; those characters may live in a group other than a book's "Characters" folder.
        // If the book-group lookup returns null, skip silently instead of throwing a 403.
        Query guardCq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON,
                FieldNames.FIELD_OBJECT_ID, charObjectId);
        guardCq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        guardCq.setRequest(new String[] { FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
        guardCq.setCache(false);
        BaseRecord guardChar = IOSystem.getActiveContext().getAccessPoint().find(user, guardCq);
        if (guardChar != null) {
            Long guardGroupId = guardChar.get(FieldNames.FIELD_GROUP_ID);
            if (guardGroupId != null && guardGroupId > 0L) {
                BaseRecord guardCharGroup = IOSystem.getActiveContext().getAccessPoint()
                        .findById(user, ModelNames.MODEL_GROUP, guardGroupId);
                Long bookGroupId = null;
                if (guardCharGroup != null && CHARACTERS_DIR.equals(guardCharGroup.get(FieldNames.FIELD_NAME))) {
                    bookGroupId = guardCharGroup.get(FieldNames.FIELD_PARENT_ID);
                }
                if (bookGroupId != null && bookGroupId > 0L) {
                    Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK,
                            FieldNames.FIELD_GROUP_ID, bookGroupId);
                    bq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                    bq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
                    bq.setCache(false);
                    BaseRecord book = IOSystem.getActiveContext().getAccessPoint().find(user, bq);
                    if (book == null) {
                        return false; // PB1 book — no olio.pb.book row
                    }
                } else {
                    return false; // Character not in a book's Characters group — PB1
                }
            }
        }
        authorizeCharacterApparel(user, charObjectId, apparelObjectId);
        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, apparelObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID,
                FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ATTRIBUTES });
        BaseRecord apparel = IOSystem.getActiveContext().getAccessPoint().find(user, q);
        if (apparel == null) throw new PictureBookException(404, "Apparel not found");
        try {
            // Attributes are referenced-table storage, not a normal column — folding them into a
            // parent-record patch (copyRecord + AccessPoint.update) never actually cascades the
            // write (confirmed live: the attribute silently never persisted, even after fixing an
            // earlier empty-SQL-SET-clause bug in that same approach). The only proven pattern for
            // persisting an attribute is to create/update the attribute record ITSELF directly —
            // see LibraryUtil.java:45, `ctx.getRecordUtil().createRecord(AttributeUtil.addAttribute(...))`.
            BaseRecord existing = AttributeUtil.getAttribute(apparel, "sceneIndex");
            boolean ok;
            if (existing != null) {
                existing.setFlex(FieldNames.FIELD_VALUE, sceneIndex);
                ok = IOSystem.getActiveContext().getRecordUtil().updateRecord(existing);
            } else {
                BaseRecord newAttr = AttributeUtil.addAttribute(apparel, "sceneIndex", sceneIndex);
                ok = IOSystem.getActiveContext().getRecordUtil().createRecord(newAttr);
            }
            return ok;
        } catch (PictureBookException pbe) {
            throw pbe;
        } catch (Exception e) {
            logger.error("Failed to tag apparel " + apparelObjectId + " with sceneIndex " + sceneIndex + ": " + e.getMessage(), e);
            throw new PictureBookException(500, e.getMessage());
        }
    }

    /**
     * Authorize an apparel write through its owning CHARACTER's book, and confirm the apparel really
     * belongs to that character. See {@link #tagApparelSceneIndex}'s javadoc for why the character —
     * not the apparel — is the authorized root.
     *
     * <p>Every hop is an id-based {@code AccessPoint} read, never a path resolution (§5.6b: there is
     * no read-up). The book group is reached as character {@code groupId} → its group → that group's
     * {@code parentId}, exactly as {@code resolveSceneBookGroup} reaches it from a scene.
     */
    private static void authorizeCharacterApparel(BaseRecord user, String charObjectId, String apparelObjectId) {
        if (user == null || charObjectId == null || charObjectId.isEmpty()) {
            throw new PictureBookException(404, "Character not found");
        }
        if (apparelObjectId == null || apparelObjectId.isEmpty()) {
            throw new PictureBookException(404, "Apparel not found");
        }
        Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charObjectId);
        cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
                FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_STORE });
        cq.setCache(false);
        BaseRecord charPerson = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
        if (charPerson == null) {
            throw new PictureBookException(404, "Character not found");
        }

        // Book-group authorization: <book>/Characters -> <book>.
        Long groupId = charPerson.get(FieldNames.FIELD_GROUP_ID);
        BaseRecord container = null;
        if (groupId != null && groupId > 0L) {
            BaseRecord charGroup = IOSystem.getActiveContext().getAccessPoint()
                    .findById(user, ModelNames.MODEL_GROUP, groupId);
            if (charGroup != null) {
                Long parentId = charGroup.get(FieldNames.FIELD_PARENT_ID);
                if (CHARACTERS_DIR.equals(charGroup.get(FieldNames.FIELD_NAME)) && parentId != null && parentId > 0L) {
                    container = IOSystem.getActiveContext().getAccessPoint()
                            .findById(user, ModelNames.MODEL_GROUP, parentId);
                }
                if (container == null) {
                    /// A character outside a book's Characters group authorizes against its own group —
                    /// never skipped, same rule as a legacy ~/Chat scene.
                    container = charGroup;
                }
            }
        }
        if (container == null) {
            throw new PictureBookException(403, "Not authorized for this book");
        }
        PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, container);
        if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
            logger.warn("Denied apparel scene-tag on book group " + container.get(FieldNames.FIELD_NAME)
                    + " for character " + charObjectId + " (user " + user.get(FieldNames.FIELD_NAME) + ")");
            throw new PictureBookException(403, "Not authorized for this book");
        }

        // The apparel must be THIS character's. Without this, an authorized book grant would let a
        // caller tag any apparel record in the organization by pairing it with a character it holds.
        BaseRecord storeRef = charPerson.get(FieldNames.FIELD_STORE);
        Long storeId = (storeRef != null) ? storeRef.get(FieldNames.FIELD_ID) : null;
        if (storeId == null || storeId <= 0L) {
            throw new PictureBookException(400, "Character has no store, so it owns no apparel");
        }
        Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_STORE, FieldNames.FIELD_ID, storeId);
        sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        sq.setCache(false);
        sq.planMost(true);
        BaseRecord store = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
        List<BaseRecord> appl = (store != null) ? store.get(OlioFieldNames.FIELD_APPAREL) : null;
        if (appl != null) {
            for (BaseRecord a : appl) {
                if (apparelObjectId.equals(a.get(FieldNames.FIELD_OBJECT_ID))) {
                    return;
                }
            }
        }
        throw new PictureBookException(400, "Apparel " + apparelObjectId + " does not belong to character "
                + charPerson.get(FieldNames.FIELD_NAME));
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
            String setting, String mood, BaseRecord sdConfig, String promptTemplateOverride) {
        String cached = getSceneTextField(scene, "landscapePrompt");
        // Confirmed live 2026-07-23 (Stephen's /Public catatone book): when setting/mood are both
        // blank, the LLM was still called anyway — with a wire request literally reading "SETTING: \n
        // MOOD: \nTIME: \nSTYLE: photograph" — and it does NOT refuse or error; it invents a
        // plausible-but-entirely-unrelated landscape (repeatedly: "alpine meadow ... crystal-clear
        // river ... snow-capped mountains" for a dystopian rain-soaked city scene). That response is
        // well-formed, coherent prompt text — not JSON-shaped, not a conversational refusal, not an
        // unsubstituted placeholder — so isErrorOrEmptyPayload's guards (KI-31/its follow-up) never
        // catch it, and it gets cached and reused forever, surviving unrelated fixes entirely (the
        // exact same hallucinated text was served again, byte-for-byte, in a later run after the
        // negative-prompt fix landed, proving it was a stale cache, not a fresh LLM call). Fix:
        // don't ask the LLM to invent a landscape from nothing — if there is no real setting/mood
        // text at all, skip the LLM call and go straight to the deterministic fallback, same as an
        // LLM failure would. Garbage/absent input never becomes a confident-looking wrong answer.
        boolean hasRealInput = (setting != null && !setting.isBlank()) || (mood != null && !mood.isBlank());
        if (cached != null && !cached.isBlank() && !isErrorOrEmptyPayload(cached)) {
            // Second self-heal: if setting/mood are STILL blank right now, the only thing this
            // method can legitimately produce (per the guard below) is the fixed string "A detailed
            // environment" — anything else cached must be a pre-fix hallucination from a blank-input
            // LLM call. Precise, not a fuzzy content-similarity guess: with the guard in place, blank
            // input can never again produce anything but that one string, so a mismatch is conclusive.
            //
            // The comparison must be made against what this method ACTUALLY writes, not against the
            // bare fallback text. KI-38 made the config style suffix (and the optional composition
            // context prefix) part of the persisted value, so the stored string is
            // "A detailed environment. ((Baroque painting ...))." — which never equalled the bare
            // fallback. The guard therefore condemned its OWN legitimate output as a hallucination on
            // every subsequent call: it re-generated forever and logged a false "pre-fix hallucinated
            // result" warning each time. Reconstruct the deterministic value the same way the write
            // path builds it, and keep accepting the bare form for values cached before the suffix.
            String deterministicBlankOutput = appendConfigStyleOnce(
                prependContextOnce(loadCompositionContext(user, scene), BLANK_LANDSCAPE_FALLBACK), sdConfig);
            if (hasRealInput || deterministicBlankOutput.equals(cached) || BLANK_LANDSCAPE_FALLBACK.equals(cached)) {
                // Same as the scene prompt: re-style rather than serve the previous style's clause.
                String restyled = restyleCached(cached, sdConfig);
                if (!restyled.equals(cached)) {
                    updateSceneTextField(user, scene, "landscapePrompt", restyled);
                }
                return restyled;
            }
            logger.warn("Landscape prompt: cached value doesn't match blank-input's only legitimate "
                + "output (\"" + deterministicBlankOutput + "\") even though setting/mood are still blank — "
                + "this must be a pre-fix hallucinated result; discarding and regenerating");
        }

        String landscapePrompt;
        if (!hasRealInput) {
            logger.warn("Landscape prompt: setting and mood are both blank — skipping the LLM call "
                + "(it cannot describe a scene it was given no information about) and using the "
                + "deterministic fallback instead of risking an unrelated hallucinated result");
            landscapePrompt = BLANK_LANDSCAPE_FALLBACK;
        } else {
            Map<String, String> landVars = new LinkedHashMap<>();
            landVars.put("setting", setting);
            landVars.put("mood", mood);
            landVars.put("time", "");
            // Style is NOT sent to the LLM — it's a discrete, code-owned fact appended via
            // appendConfigStyleOnce(sdConfig) below. Feeding {style} here made the LLM emit its own
            // "cinematic photograph style" on top of the config style (double/conflicting style).
            landscapePrompt = callLlm(user, chatConfig, "pictureBook.landscape-prompt", landVars, promptTemplateOverride);
            if (isErrorOrEmptyPayload(landscapePrompt)) {
                logger.warn("Landscape prompt failed — falling back to setting text");
                landscapePrompt = setting.isEmpty() ? BLANK_LANDSCAPE_FALLBACK : setting;
            }
        }
        // Same discrete, code-owned facts as the scene prompt: the book-level composition anchor
        // (prepended) + the config style suffix (appended), applied deterministically so the landscape
        // stays consistent with the rest of the book rather than relying on the LLM.
        landscapePrompt = prependContextOnce(loadCompositionContext(user, scene), landscapePrompt);
        landscapePrompt = appendConfigStyleOnce(landscapePrompt, sdConfig);
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
        /// Resolves the scene AND authorizes the caller against the book that owns it — a bare
        /// AccessPoint.find here was a direct object reference with no book-level check.
        BaseRecord scene = authorizeSceneAccess(user, sceneObjectId, SceneAccessType.WRITE);
        updateSceneStatus(user, scene, status, null);
    }

    /**
     * Detect content that LOOKS like an upstream error/empty payload rather than real prompt
     * text — see KI-31. Live logs showed a 200-OK LLM response whose message content was itself
     * an error-shaped JSON object (e.g. {@code {"error":"No story text provided"}}) or an empty
     * JSON array ({@code []}); neither is null/blank, so the old null/blank-only guard in
     * {@link #resolveScenePrompt} / {@link #resolveLandscapePrompt} let it through to be cached
     * and forwarded straight into {@code SDUtil.txt2img} as literal prompt text.
     *
     * <p>Deliberately kept local to those two prompt-resolvers rather than centralized inside
     * {@link #callLlmInternal} itself: that method has other callers (extract-chunk,
     * extract-scenes, extract-character, scene-blurb) whose responses are JSON objects/arrays by
     * design and are already parsed defensively by {@link #parseLlmJsonArray}/
     * {@link #parseLlmJsonObject} (which tolerate malformed/empty JSON on their own) — rejecting
     * any `{`/`[`-shaped content at that shared choke point would risk misclassifying a
     * legitimately-shaped extraction result as an error.
     *
     * <p>Also catches a second failure shape (found live 2026-07-23, same root incident as the
     * unsubstituted-placeholder guard in {@link #callLlmInternal}): a plain-prose conversational
     * clarifying question — e.g. {@code "I'm happy to help identify the most visually compelling
     * scenes, but I need the actual story text and the number of scenes you'd like selected."} —
     * which is neither JSON-shaped nor blank, so the checks above miss it entirely. This happened
     * when a promptTemplateOverride meant for scene EXTRACTION was applied to a scene-image/
     * landscape-prompt call instead; the LLM, given a template asking for {@code {text}}/
     * {@code {count}} that were never filled in (wrong vars for that template), reasonably asked
     * for them back in prose instead of returning an SD prompt. {@link #callLlmInternal}'s
     * placeholder guard now stops this at construction time for NEW calls, but this method is
     * also used to validate an already-CACHED prompt on read (see {@link #resolveScenePrompt}/
     * {@link #resolveLandscapePrompt}) so a scene poisoned by this bug before the fix landed
     * self-heals the next time it's touched, instead of serving the same garbage forever.
     *
     * @param content raw (already {@link #stripThink}-ed) LLM message content, may be null
     * @return true if content is null/blank, an empty JSON array, a JSON object containing an
     *         "error" key, or looks like a conversational request for missing input
     */
    public static boolean isErrorOrEmptyPayload(String content) {
        if (content == null) return true;
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return true;
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                if (trimmed.startsWith("[")) {
                    List<Object> arr = JSONUtil.getList(trimmed, Object.class, null);
                    return (arr == null || arr.isEmpty());
                } else {
                    Map<String, Object> obj = JSONUtil.getMap(trimmed.getBytes(), String.class, Object.class);
                    return (obj != null && obj.containsKey("error"));
                }
            } catch (Exception e) {
                // Not parseable as JSON despite the leading brace/bracket — treat as real (if odd)
                // prompt text rather than silently discarding it; not the failure shape this guards.
                return false;
            }
        }
        // A leftover unsubstituted "{name}" placeholder in what should be finished prompt text —
        // the same tell-tale sign callLlmInternal's construction-time guard looks for, kept here
        // too so an already-cached value carrying one (from before that guard existed) self-heals.
        if (UNSUBSTITUTED_PLACEHOLDER.matcher(trimmed).find()) return true;
        return CONVERSATIONAL_REFUSAL.matcher(trimmed).find();
    }

    // Heuristic for "this reads like the assistant asking for missing input", not an SD prompt.
    // Real SD prompts are comma/tag-heavy declarative fragments; they don't address the reader in
    // first person or ask questions. Deliberately conservative (specific phrases + a question mark
    // requirement on most branches) to avoid false-positiving on legitimate prompt text.
    private static final Pattern CONVERSATIONAL_REFUSAL = Pattern.compile(
        "(?i)(i'm happy to help|i am happy to help|could you (please )?provide|can you (please )?provide|"
        + "i need the actual|i don't have (the|any) (story|text)|please provide the (story|actual) text|"
        + "the number of scenes you.d like|"
        // Outright refusals — the model DECLINING rather than producing a prompt (e.g. "I'm sorry, but
        // I can't help with that."). Real SD prompts are comma/tag declarative fragments and never
        // address the reader in the first person like this, so these are safe to treat as failures.
        + "i'?m sorry|i am sorry|i can'?t (help|assist|comply|create|generate|produce|fulfill|provide|do that|do this)|"
        + "i cannot (help|assist|comply|create|generate|produce|fulfill|provide)|"
        + "i'?m (not able|unable) to|i am (not able|unable) to|i won'?t be able to|"
        + "against my (guidelines|programming|policy)|as an ai (language )?model)");

    /**
     * Parse LLM JSON response into a list of maps, stripping markdown fences if present.
     */
    /**
     * Strip a `&lt;think&gt;...&lt;/think&gt;` reasoning block some models emit even when the
     * request set think:false (a hybrid-reasoning model may ignore that option entirely). Shared
     * by every LLM-response path in this class — JSON extraction paths already needed this;
     * callLlmInternal's raw-text path (landscape prompt, blurb, scene prompt) did not, which is
     * how raw chain-of-thought ended up inside an actual SD prompt sent to Swarm.
     */
    public static String stripThink(String text) {
        if (text == null) return null;
        String result = text.replaceAll("(?s)<think>.*?</think>", "");
        // Some models (confirmed live against a real landscape-prompt call, qwen3-vl:8b-instruct)
        // emit a full reasoning trace ("We need to output...", "Let's craft:...") followed by a
        // bare closing </think> tag with NO matching opening tag at all — the regex above only
        // matches a *paired* <think>...</think> block, so an orphan closing tag (and everything
        // before it) sails straight through untouched. If one is present, treat everything up to
        // and including the LAST closing tag as reasoning and keep only what follows it.
        int lastClose = result.lastIndexOf("</think>");
        if (lastClose >= 0) {
            result = result.substring(lastClose + "</think>".length());
        }
        return result.trim();
    }

    /**
     * Public so tests can parse a real LLM response through THE PRODUCTION PARSER instead of
     * reimplementing fence-stripping/array-slicing/JSON-binding in the test. A test copy of this
     * logic tests the copy: TestLlmSceneExtraction had its own hand-rolled version that lacked the
     * {@code stripThink} call and diverged, so it failed on responses production handles fine.
     * Same rationale as {@link #isErrorOrEmptyPayload(String)} already being public.
     */
    public static List<Map<String, Object>> parseLlmJsonArray(String response) {
        return parseLlmJsonArray(response, null, null);
    }

    /**
     * Parse an LLM JSON array response (e.g. pictureBook.extract-scenes' scene list).
     *
     * @param context short label identifying which call produced {@code response} (e.g.
     *   "extract-scenes:{workObjectId}") — stored alongside the raw response so a persisted
     *   failure can be traced back to what was being extracted.
     * @param failedExtractions sink for {context,error,rawResponse,failedAt} JSON blobs when
     *   parsing fails; null means "don't bother capturing" (used by call sites that don't have a
     *   meta/result record to attach failures to). See {@link #recordFailedExtraction}.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseLlmJsonArray(String response, String context, List<String> failedExtractions) {
        if (response == null || response.isEmpty()) return new ArrayList<>();
        String trimmed = stripThink(response.trim());
        // Strip markdown code fences
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl >= 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        // Find first [ ... ] array
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            recordFailedExtraction(failedExtractions, context, "No JSON array ([...]) found in LLM response", response);
            return new ArrayList<>();
        }
        trimmed = trimmed.substring(start, end + 1);
        try {
            List<Map<String, Object>> parsed = JSONUtil.getList(trimmed, Map.class, null);
            if (parsed != null) return parsed;
            recordFailedExtraction(failedExtractions, context, "JSON array parse returned null", response);
        } catch (Exception e) {
            logger.warn("Failed to parse LLM JSON array: " + e.getMessage());
            recordFailedExtraction(failedExtractions, context, e.getMessage(), response);
        }
        return new ArrayList<>();
    }

    /**
     * Parse a single LLM JSON object response.
     */
    private static Map<String, Object> parseLlmJsonObject(String response) {
        return parseLlmJsonObject(response, null, null);
    }

    /** @see #parseLlmJsonArray(String, String, List) — same context/failedExtractions contract. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseLlmJsonObject(String response, String context, List<String> failedExtractions) {
        if (response == null || response.isEmpty()) return new LinkedHashMap<>();
        String trimmed = stripThink(response.trim());
        if (trimmed.startsWith("```")) {
            int nl = trimmed.indexOf('\n');
            if (nl >= 0) trimmed = trimmed.substring(nl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            recordFailedExtraction(failedExtractions, context, "No JSON object ({...}) found in LLM response", response);
            return new LinkedHashMap<>();
        }
        trimmed = trimmed.substring(start, end + 1);
        try {
            // JSONUtil.getMap swallows its own IOException and returns null rather than throwing —
            // must explicitly null-check here, or a malformed response silently returns null instead
            // of an empty map, and every caller's `.isEmpty()` NPEs instead of degrading gracefully.
            Map<String, Object> parsed = JSONUtil.getMap(trimmed.getBytes(), String.class, Object.class);
            if (parsed != null) return parsed;
            recordFailedExtraction(failedExtractions, context, "JSON object parse returned null", response);
        } catch (Exception e) {
            logger.warn("Failed to parse LLM JSON object: " + e.getMessage());
            recordFailedExtraction(failedExtractions, context, e.getMessage(), response);
        }
        return new LinkedHashMap<>();
    }

    /**
     * Capture a malformed LLM extraction response for later investigation/redo instead of letting
     * it vanish behind a log line. Callers attach the accumulated list to whatever durable record
     * they have on hand — .pictureBookMeta's failedExtractions field (extract/createFromScenes,
     * once a book exists) or ScenesOnlyResult.failedExtractions (extractScenesOnly, pre-book). To
     * redo: read the note back, find the rawResponse for the failed context, fix it by hand into
     * valid JSON, and re-drive the same entry point with the corrected data — this is deliberately
     * not a separate API, just enough breadcrumb to not lose the LLM's original (bad) output.
     */
    private static void recordFailedExtraction(List<String> sink, String context, String error, String rawResponse) {
        if (sink == null) return;
        try {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("context", context);
            failure.put("error", error);
            failure.put("rawResponse", rawResponse);
            failure.put("failedAt", ZonedDateTime.now().toString());
            sink.add(JSONUtil.exportObject(failure));
        } catch (Exception e) {
            logger.warn("Failed to record failed extraction for investigation: " + e.getMessage());
        }
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

        // KI-37: user-customizable prompt template first (user's group → system library), composed
        // through the CANONICAL PromptTemplateComposer — the same path Chat/ChatUtil/
        // InteractionExtractor use — rather than the hand-rolled section loop that used to live here.
        //
        // That loop matched only the literal roles "system"/"user", but a section's role is OPTIONAL
        // per promptSectionModel.json ("If empty, inherits from parent template"), so every role-less
        // section was silently DROPPED and never reached the LLM. It also ignored `extends`
        // inheritance, per-section `condition`s, `sectionOrder`/`priority`, and ${...} token
        // replacement, all of which the composer handles.
        //
        // Note the deliberate behavior change that follows from doing this correctly: compose()
        // includes a section whose role is empty OR equals the target, so a shared/role-less section
        // now appears in BOTH the system and user messages. That is the composer's defined semantics
        // (PromptTemplateComposer.java:70-74) — PictureBook DB templates should set section roles
        // explicitly where that duplication isn't wanted.
        boolean templateResolved = false;
        try {
            BaseRecord pt = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_PROMPT_TEMPLATE, promptName, null);
            if (pt != null) {
                templateResolved = true;
                String composedSystem = PromptTemplateComposer.composeSystem(pt, null, chatConfig);
                String composedUser = PromptTemplateComposer.composeUser(pt, null, chatConfig);
                if (composedSystem != null && !composedSystem.isBlank()) system = composedSystem;
                if (composedUser != null && !composedUser.isBlank()) userTpl = composedUser;
            }
        } catch (Exception e) {
            logger.debug("Prompt template lookup failed for " + promptName + ": " + e.getMessage());
        }

        // Fallback to classpath resource.
        //
        // Only when NO DB template resolved at all. Backfilling a single missing half from the
        // classpath (what this used to do) Frankensteins a prompt: a DB template supplying only
        // system sections got its user half from an unrelated classpath resource, producing a
        // mismatched, incoherent pair. If a template resolved but composed to nothing for one role,
        // that is the template's own content and must not be silently patched from elsewhere.
        if (!templateResolved) {
            if (system == null) {
                logger.warn("No prompt template record for '" + promptName + "' — falling back to the classpath system prompt.");
                system = PromptResourceUtil.getString(promptName, "system");
            }
            if (userTpl == null) {
                logger.warn("No prompt template record for '" + promptName + "' — falling back to the classpath user prompt.");
                userTpl = PromptResourceUtil.getString(promptName, "user");
            }
        }
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
        // Guard: refuse to call the LLM if the resolved template still has unsubstituted
        // "{name}"-style placeholders after applying the caller's vars. Root cause this closes:
        // promptTemplateOverride is a single field applied by the wizard's "single prompt
        // template" mode to EVERY LLM call (extract-scenes, scene-image-prompt, landscape-prompt
        // alike — see resolveScenePrompt/resolveLandscapePrompt/extractScenesOnly callers) — if a
        // user picks a custom template meant for one purpose (e.g. pictureBook.extract-scenes,
        // which expects {text}/{count}) it silently overrides an unrelated call (e.g.
        // scene-image-prompt, whose vars are setting/action/mood/charNarrations). The unfilled
        // template still gets sent to the LLM, which reasonably responds with a conversational
        // clarifying question ("I need the actual story text and the number of scenes...") — prose,
        // not JSON/empty, so isErrorOrEmptyPayload's shape check doesn't catch it, and it was
        // getting cached and forwarded to SDUtil.txt2img as literal prompt text (confirmed live,
        // 2026-07-23). Catching the malformed CONSTRUCTION here, before the network call, is more
        // robust than trying to pattern-match every way a confused LLM might phrase "I don't have
        // enough information" after the fact.
        Matcher unresolved = UNSUBSTITUTED_PLACEHOLDER.matcher(userTpl);
        if (unresolved.find()) {
            logger.error("Refusing to call LLM for prompt '" + promptName + "' — template has unsubstituted "
                + "placeholder(s) (first: '" + unresolved.group() + "'), most likely because a "
                + "promptTemplateOverride belonging to a different operation was applied here. Vars supplied: "
                + (vars != null ? vars.keySet() : "none"));
            return null;
        }
        // These prompt templates put /no_think at the end of the SYSTEM prompt, but Qwen's own
        // documented convention for this inline toggle checks the LATEST USER message, not the
        // system prompt — confirmed live that think:false (already sent both at the top-level
        // OpenAIRequest.think field and in options.think) did not stop a real reasoning-trace leak
        // from qwen3-vl:8b-instruct. Appending it to the user turn too is a cheap additional
        // attempt at suppressing it; stripThink() below remains the actual backstop regardless.
        if (system != null && system.contains("/no_think") && !userTpl.contains("/no_think")) {
            userTpl = userTpl + "\n/no_think";
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
            
            logger.info("**** SCENE CALL");
            logger.info(system);
            
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
                String out = stripThink(resp.getMessage().getContent());
                // Central safeguard: if the model DECLINED (a conversational refusal like "I'm sorry,
                // but I can't help with that.") instead of producing usable content, never return the
                // refusal text — it must not become an SD prompt or persisted content. Return null so
                // callers fall back (raw-text paths) or mark it a failed extraction (JSON paths).
                if (out != null && CONVERSATIONAL_REFUSAL.matcher(out.trim()).find()) {
                    String snip = out.trim();
                    logger.warn("LLM refused/declined for prompt '" + promptName + "' — discarding refusal text: "
                        + snip.substring(0, Math.min(160, snip.length())));
                    // Log the exact content sent to the LLM so the offending input is identifiable —
                    // which prompt template + which substituted values it balked on.
                    logger.warn("  refused-request system=[" + system + "]");
                    String sentUser = (userTpl != null) ? userTpl : "";
                    if (sentUser.length() > 4000) sentUser = sentUser.substring(0, 4000) + " …(truncated, " + userTpl.length() + " chars total)";
                    logger.warn("  refused-request user=[" + sentUser + "]");
                    return null;
                }
                logger.info(out);
                logger.info("END LLM *****");
                return out;
            }
            else {
            	logger.error("Null LLM response");
            }
        } catch (Exception e) {
            logger.error("LLM call failed for " + promptName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Internal chunked extraction — shared by extract-scenes-only (auto-chunk) and extract-chunked.
     *
     * @param cancelToken KI-10: optional cancellation flag, mirroring {@code SummarizeProgress}'s
     *   use in {@code ChatUtil}'s map/reduce summarization loops. Checked once per chunk, at the
     *   top of the loop — a cancelled request stops making further LLM calls and returns whatever
     *   scenes were already extracted from prior chunks, rather than running to completion. May be
     *   null (no cancellation support requested by the caller).
     */
    // The ONLY fields the chunk extractor's running "previousScenes" context needs for the LLM to
    // recognize/dedupe/revise existing scenes (it matches revisions by title). Everything else is
    // dropped from the prompt: the verbose per-scene "diffusionPrompt" paragraph (the single biggest
    // field, and pure output redundancy here), the transient raw "sourceText" block, and bookkeeping
    // (index/userEdited). Whitelist, not blacklist, so future scene fields don't silently bloat the prompt.
    private static final String[] PROMPT_SCENE_FIELDS = { "title", "blurb", "setting", "action", "mood", "characters" };

    /**
     * Project the scene maps down to just {@link #PROMPT_SCENE_FIELDS} for anything that serializes
     * scenes into an LLM prompt (the chunk extractor's {@code previousScenes}). The full scene maps —
     * with diffusionPrompt, sourceText, index, etc. — are untouched on the returned sceneList; this
     * only trims what is SENT to the model, which otherwise re-sent every field of every accumulated
     * scene on every chunk (O(n^2) prompt growth). sourceText in particular must never reach an LLM.
     */
    public static List<Map<String, Object>> scenesForPrompt(List<Map<String, Object>> scenes) {
        List<Map<String, Object>> out = new ArrayList<>(scenes.size());
        for (Map<String, Object> s : scenes) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String f : PROMPT_SCENE_FIELDS) {
                Object v = s.get(f);
                if (v != null) c.put(f, v);
            }
            out.add(c);
        }
        return out;
    }

    private static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text,
            SummarizeProgress cancelToken) {
        return extractChunkedInternal(user, chatConfig, text, cancelToken, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text,
            SummarizeProgress cancelToken, List<String> failedExtractions) {
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

        // KI-10: populate progress (total/current), same as ChatUtil's mapSummarize/reduceSummaries
        // do with their own SummarizeProgress — lets a caller/test observe how many chunks have
        // actually completed, not just whether cancellation was requested.
        if (cancelToken != null) {
            cancelToken.setTotal(chunks.size());
            cancelToken.setCurrent(0);
        }

        List<Map<String, Object>> sceneList = new ArrayList<>();
        for (int ci = 0; ci < chunks.size(); ci++) {
            // KI-10: checkpoint at the top of the chunk loop — a mid-run cancel (POST
            // /{workObjectId}/cancel) stops further LLM calls immediately; scenes already
            // extracted from earlier chunks are still returned, not discarded.
            if (cancelToken != null && cancelToken.isCancelled()) {
                logger.info("extractChunkedInternal: cancelled after " + ci + "/" + chunks.size()
                        + " chunks — returning " + sceneList.size() + " scenes extracted so far");
                break;
            }
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome",
                    "Extracting chunk " + (ci + 1) + "/" + chunks.size() + "...");
            // MUST strip the transient raw "sourceText" content block before sending the running
            // scene list back into the chunk LLM — otherwise every chunk re-sends the full raw text of
            // every prior scene, ballooning the prompt (100KB+) and growing O(n^2) as scenes
            // accumulate. sourceText stays on the returned sceneList (for the later per-character
            // reduce) and is only ever excluded from LLM prompts / persisted notes.
            String previousJson = sceneList.isEmpty() ? "[]" : JSONUtil.exportObject(scenesForPrompt(sceneList));
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("previousScenes", previousJson);
            vars.put("chunk", chunks.get(ci));
            // Extract this chunk's scenes, with a bounded retry: qwen3-class models occasionally emit
            // malformed JSON (a stray quote, a corrupted token mid-generation) — a fresh generation
            // almost always parses. Intermediate attempts pass a NULL failure-sink so a recovered
            // chunk leaves no spurious failedExtractions record; only the FINAL failure is recorded.
            String chunkCtx = "extract-scenes-chunk:" + (ci + 1) + "/" + chunks.size();
            Map<String, Object> chunkResult = null;
            String llmResp = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                llmResp = callLlm(user, chatConfig, "pictureBook.extract-chunk", vars);
                // KI-10: count the chunk as processed once (first attempt) — progress reflects
                // "chunks attempted", matching ChatUtil.mapSummarize's incrementCurrent() placement.
                if (attempt == 1 && cancelToken != null) cancelToken.incrementCurrent();
                if (llmResp == null || llmResp.isEmpty()) continue;
                Map<String, Object> parsed = parseLlmJsonObject(llmResp, chunkCtx, null);
                if (parsed != null && !parsed.isEmpty()) { chunkResult = parsed; break; }
                if (attempt < 2) logger.warn("Chunk " + chunkCtx + " returned unparseable JSON — retrying once");
            }
            if (chunkResult == null || chunkResult.isEmpty()) {
                // Record the final, unrecoverable failure (re-parse with the real sink so the raw text
                // is captured for inspection), then skip this chunk.
                if (llmResp != null && !llmResp.isEmpty()) parseLlmJsonObject(llmResp, chunkCtx, failedExtractions);
                logger.warn("Chunk " + chunkCtx + " still unparseable after retry — skipping");
                continue;
            }

            Object addObj = chunkResult.get("additions");
            if (addObj instanceof List) {
                List<Map<String, Object>> additions = (List<Map<String, Object>>) addObj;
                for (Map<String, Object> scene : additions) {
                    scene.put("index", sceneList.size());
                    scene.put("userEdited", false);
                    // Track the raw content block this scene (and thus its characters) was obtained
                    // from — the passage where those characters actually appear. Transient carrier on
                    // the in-memory scene map; used by createFromScenes to REDUCE per-character detail
                    // from the right text, and stripped before the scene note is persisted
                    // (createSceneNote) so it never bloats storage.
                    scene.put("sourceText", chunks.get(ci));
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
        if (raw == null) return "";
        String g = raw.trim().toLowerCase();
        if (g.equals("male") || g.equals("m")) return "male";
        if (g.equals("female") || g.equals("f")) return "female";
        return "";  // undetermined — lowercase to match randomPerson/rollHeight; caller falls back to baseline
    }

    // C2: comma-separated human-readable RaceEnumType / EthnicityEnumType values, used to CONSTRAIN the
    // extraction prompt to labels the enum's own valueOfVal() can map back. The enum is the single
    // source of truth — these lists are derived from it at call time, never a hand-maintained copy.
    public static String raceOptionsCsv() {
        List<String> vals = new ArrayList<>();
        for (RaceEnumType r : RaceEnumType.values()) vals.add(RaceEnumType.valueOf(r));
        return String.join(", ", vals);
    }

    public static String ethnicityOptionsCsv() {
        List<String> vals = new ArrayList<>();
        for (EthnicityEnumType e : EthnicityEnumType.values()) vals.add(EthnicityEnumType.valueOf(e));
        return String.join(", ", vals);
    }

    /**
     * C2: map a free-text race string (LLM output) to the {@link RaceEnumType} constant NAME that the
     * random-generation path stores in {@code charPerson.race} — {@code CharacterUtil.randomPerson}
     * persists {@code RaceEnumType.name()} (e.g. "E"), and {@code NarrativeUtil}/
     * {@code setStyleByRace} read it back via the built-in {@code RaceEnumType.valueOf(name)}. So the
     * override path must store the SAME shape (a constant name), never the LLM's raw text.
     *
     * <p>Matching order: exact human-readable value ({@link RaceEnumType#valueOfVal}), then a
     * case-insensitive scan of {@link RaceEnumType#values()} by human-readable value and constant
     * name. Returns null when nothing maps — callers KEEP the random baseline's enum value. The
     * extraction prompt is constrained to {@link #raceOptionsCsv()}, so a well-behaved LLM response
     * is always a value this maps; there is deliberately no hand-maintained synonym table.
     */
    public static String mapRaceOverride(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        RaceEnumType exact = RaceEnumType.valueOfVal(t);
        if (exact != null) return exact.name();
        for (RaceEnumType r : RaceEnumType.values()) {
            if (RaceEnumType.valueOf(r).equalsIgnoreCase(t)) return r.name();
            if (r.name().equalsIgnoreCase(t)) return r.name();
        }
        return null;
    }

    /**
     * C2: map a free-text ethnicity string (LLM output) to the {@link EthnicityEnumType} constant
     * NAME. Same contract as {@link #mapRaceOverride} but for ethnicity: {@code ethnicity} is a
     * {@code list<string>} whose values are enum constant names, read back via
     * {@code NarrativeUtil.getEthnicityDescription -> EthnicityEnumType.valueOf(name)} — storing the
     * LLM's raw text (the pre-fix behavior) throws {@code IllegalArgumentException} there. Returns
     * null when nothing maps; callers then leave ethnicity unset (KEEP baseline; never a raw string).
     */
    public static String mapEthnicityOverride(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        EthnicityEnumType exact = EthnicityEnumType.valueOfVal(t);
        if (exact != null) return exact.name();
        for (EthnicityEnumType e : EthnicityEnumType.values()) {
            if (EthnicityEnumType.valueOf(e).equalsIgnoreCase(t)) return e.name();
            if (e.name().equalsIgnoreCase(t)) return e.name();
        }
        return null;
    }

    /**
     * B6: strip Unicode diacritics via NFD decomposition + combining-mark removal, so an
     * accent-only difference ("Duña" vs "Duna") doesn't defeat a name match. Returns null for null.
     */
    public static String stripDiacritics(String s) {
        if (s == null) return null;
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    /**
     * B6: accent- and case-insensitive, whitespace-trimmed name equality — the Java-side fallback
     * used by {@link #resolveSceneCharacter} after its primary ILIKE+trim DB query misses (the DB
     * ILIKE does not fold diacritics). Both sides are diacritic-stripped before comparison.
     */
    public static boolean namesMatchAccentInsensitive(String a, String b) {
        if (a == null || b == null) return false;
        String na = stripDiacritics(a.trim());
        String nb = stripDiacritics(b.trim());
        return na.equalsIgnoreCase(nb);
    }

    /**
     * B7: extract a scene character's name from either persisted shape — a {@code {name:...}} map or
     * a bare name/objectId string (see buildSceneEntry) — tolerating any other element type by
     * returning null instead of ClassCastException-ing. Harmonizes extract()'s character collection
     * with createFromScenes()'s per-element {@code instanceof} guard.
     */
    @SuppressWarnings("unchecked")
    public static String extractCharName(Object sceneCharItem) {
        if (sceneCharItem instanceof Map) {
            Object n = ((Map<String, Object>) sceneCharItem).get("name");
            return (n instanceof String) ? (String) n : null;
        }
        if (sceneCharItem instanceof String) return (String) sceneCharItem;
        return null;
    }

    /**
     * B7: return the character-data map for a scene character item — the map itself when it is a
     * {@code {name:...}} object, or a fresh single-key {@code {name}} map when it is a bare string.
     * Mirrors createFromScenes()'s synthetic-map handling so extract() tolerates both shapes.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sceneCharDataMap(Object sceneCharItem, String name) {
        if (sceneCharItem instanceof Map) return (Map<String, Object>) sceneCharItem;
        Map<String, Object> m = new LinkedHashMap<>();
        if (name != null) m.put("name", name);
        return m;
    }

    /**
     * Parse the LLM-extracted "age_approx" field (free text — "mid-30s", "25", "elderly", etc.)
     * into a plain int. Returns 0 (StatisticsUtil's own "adult, no special-case" convention —
     * see rollStatistics/rollHeight's own age&lt;=0 checks) for anything that doesn't start with a
     * parseable number, rather than guessing.
     */
    private static int parseAgeApprox(Map<String, Object> charData) {
        Object ageObj = charData.get("age_approx");
        if (!(ageObj instanceof String)) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher((String) ageObj);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return 0;
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
     * KI-30: build a fully-populated random baseline character via the same population-
     * generation recipe ordinary Olio world population uses — {@code CharacterUtil.randomPerson()}
     * followed by {@code StatisticsUtil.rollStatistics}/{@code rollHeight} and
     * {@code ProfileUtil.rollPersonality} (mirrors {@code CharacterUtil.java}'s own population
     * loop, ~line 415-423, and {@code EvolutionUtil.java:124-126}'s birth path — the only two real
     * precedents for calling {@code randomPerson()}; it does not roll statistics/personality on
     * its own). LLM-extracted overrides are applied ON TOP of this baseline afterward by
     * {@code createCharPerson()} itself — this method only builds the baseline.
     *
     * <p>Returns an in-memory, UNPERSISTED {@code olio.charPerson} whose own statistics/instinct/
     * personality/state/store/profile sub-records are themselves in-memory placeholders scoped to
     * the Olio world's own directories (population.path, statistics.path, etc. — see
     * {@code CharacterUtil.randomPerson}). Callers copy field VALUES from these placeholders onto
     * their own persisted, book-scoped records (see {@link #copyBaselineFieldValues}) rather than
     * reusing the placeholders directly, so PictureBook character data never leaks into the Olio
     * world's population hierarchy.
     *
     * <p>Best-effort: returns null (falling back to the pre-KI-30 sparse baseline) on a missing
     * {@code dataPath}, {@code OlioContext} init failure, etc., rather than throwing — this is an
     * enhancement to character richness, not a hard requirement for character creation.
     */
    private static BaseRecord buildRandomBaseline(OlioContext octx, String preferredLastName, int ageApprox) {
        if (octx == null) return null;
        try {
            BaseRecord baseline = CharacterUtil.randomPerson(octx,
                    (preferredLastName != null && !preferredLastName.isEmpty()) ? preferredLastName : null);
            if (baseline == null) return null;

            BaseRecord baseStats = baseline.get(OlioFieldNames.FIELD_STATISTICS);
            List<String> baseRace = baseline.get(OlioFieldNames.FIELD_RACE);
            String baseGender = baseline.get(FieldNames.FIELD_GENDER);
            if (baseStats != null) {
                StatisticsUtil.rollStatistics(baseStats, ageApprox);
                StatisticsUtil.rollHeight(baseStats, baseRace, baseGender, ageApprox);
            }
            BaseRecord basePersonality = baseline.get(FieldNames.FIELD_PERSONALITY);
            if (basePersonality != null) {
                ProfileUtil.rollPersonality(basePersonality);
            }
            return baseline;
        } catch (Exception e) {
            logger.warn("Failed to build random baseline person (lastName=" + preferredLastName + "): " + e.getMessage());
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
    /**
     * Ensure the character's narrative is a real persisted record carrying the SD portrait prompt, and
     * attach it by a PATCH-shaped update (identity + narrative only) — never a full-object update on the
     * shallow {@code planMost(false)} charPerson, which would risk re-persisting other foreign refs and a
     * silent PBAC denial.
     *
     * <p><b>MUST run after personality/instinct/state, not before — measured 2026-08-17.</b> Extracted
     * from the middle of {@code createCharPerson} for exactly this reason.
     * {@code NarrativeUtil.getCreateNarrative} → {@code ProfileUtil.getProfile} →
     * {@code analyzePersonality} → {@code DarkTetradUtil.getAggressiveness} dereferences
     * {@code charPerson.personality} without a null check ({@code DarkTetradUtil.java:254}). While the
     * factory's placeholder personality was silently auto-created before {@code create}, that reference was
     * always non-null and the ordering never mattered; once the placeholders are detached so the sub-records
     * can land in the world groups, running this first NPEs inside {@code getCreateNarrative}, which then
     * falls back to a plain {@code createSubRecord}. The narrative still persisted and still landed in the
     * right group, so every group assertion passed — the only visible symptom was a WARN, and the canonical
     * Olio utility had quietly stopped being used.
     *
     * <p>The two-step write is deliberate and is not redundant: {@code patchCharPersonField} rewrites only
     * {@code charPerson.narrative}'s FK reference (per {@code model-api.md}, "foreign fields patch by ID
     * reference") and does <b>not</b> cascade the narrative's own field values, so {@code sdPrompt} /
     * {@code physicalDescription} need their own update on {@code olio.narrative} — confirmed live:
     * {@code narrative.sdPrompt} read back null without it. That update goes through
     * {@code AccessPoint.update} rather than {@code getCreateNarrative}'s internal
     * {@code RecordUtil.updateRecord} (a PBAC bypass, appropriate for Olio's own population generation, not
     * for a live end-user session), so PBAC applies and a failure is detectable here.
     *
     * @return false when the narrative could not be created, persisted or attached — the caller aborts
     */
    private static boolean ensureNarrative(BaseRecord user, OlioContext octx, BaseRecord charPerson, String name,
            String portraitPrompt) {
        try {
            BaseRecord narrative = charPerson.get("narrative");
            Long existingNarrativeId = (narrative != null) ? narrative.get(FieldNames.FIELD_ID) : null;
            if (narrative == null || existingNarrativeId == null || existingNarrativeId <= 0L) {
                // Through the CANONICAL Olio utility, which already builds the narrative in
                // {world}/Narratives, creates-or-patches it, links it back onto the person and
                // flushes the queue. The hand-rolled createPersistedForeignInstance it replaces
                // targeted ~/Narratives in the ACTING USER'S HOME — which is KI-60's collision
                // target (a write there recovered onto "#151 Apparel" for "#1049 Narratives").
                // Falls back to a plainly-created record when there is no usable OlioContext:
                // getCreateNarrative needs a world to build into, and returning null there would
                // abort character creation for a reason unrelated to the character.
                narrative = PbSubRecordUtil.getCreateNarrative(octx, charPerson, null);
                if (narrative == null) {
                    narrative = PbSubRecordUtil.createSubRecord(user, octx, OlioModelNames.MODEL_NARRATIVE);
                }
                if (narrative == null) {
                    logger.error("Failed to create persisted narrative for charPerson " + name);
                    return false;
                }
            }
            narrative.set("sdPrompt", portraitPrompt);
            narrative.set("physicalDescription", portraitPrompt);

            BaseRecord narrativePatch = narrative.copyRecord(
                    new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "sdPrompt", "physicalDescription" });
            BaseRecord narrativeFieldsPersisted = IOSystem.getActiveContext().getAccessPoint().update(user, narrativePatch);
            if (narrativeFieldsPersisted == null) {
                logger.error("Failed to persist narrative.sdPrompt/physicalDescription for charPerson " + name + " — AccessPoint.update denied or failed (PBAC/persist)");
                return false;
            }

            BaseRecord narrativeLinked = patchCharPersonField(user, charPerson, "narrative", narrative);
            if (narrativeLinked == null) {
                logger.error("Failed to attach narrative to charPerson " + name + " — AccessPoint.update denied or failed (PBAC/persist)");
                return false;
            }
            charPerson.set("narrative", narrative);
            return true;
        } catch (Exception e) {
            logger.error("Failed to set portrait prompt/narrative for " + name + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Drop a {@code CharPersonFactory}-built, still-unpersisted foreign sub-record off {@code charPerson}
     * so {@code DBWriter}'s auto-create does not write it into the acting user's home directory.
     * <p>
     * Only ever clears a placeholder: a sub-record that already has an identity (a real id) belongs to
     * someone and is left exactly where it is. Best-effort — a failure here means the record keeps its
     * pre-phase-3 home destination, which is a wrong group, not a lost character.
     */
    private static void detachFactoryPlaceholder(BaseRecord charPerson, String name, String fieldName) {
        try {
            BaseRecord existing = charPerson.get(fieldName);
            if (existing == null) {
                return;
            }
            Long existingId = existing.get(FieldNames.FIELD_ID);
            if (existingId != null && existingId.longValue() > 0L) {
                /// Already persisted somewhere — not a factory placeholder. Leave it alone.
                return;
            }
            charPerson.set(fieldName, null);
        } catch (Exception e) {
            logger.warn("Could not detach the factory placeholder charPerson." + fieldName + " for " + name
                    + " — it will be auto-created in the acting user's home instead of the world group: "
                    + e.getMessage());
        }
    }

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
     *
     * @param dataPath the {@code datagen.path} init-param value (see {@code GameService}'s
     *   identical use), needed by {@link #buildRandomBaseline} to acquire an {@code OlioContext}
     *   via {@code OlioContextUtil.getOlioContext(user, dataPath)} — KI-30. May be null/empty, in
     *   which case baseline generation is skipped and the character falls back to the pre-KI-30
     *   sparse-field creation path (non-fatal).
     */
    @SuppressWarnings("unchecked")
    private static BaseRecord createCharPerson(BaseRecord user, BaseRecord chatConfig, Map<String, Object> charData, BaseRecord charsGroup, String genre,
            List<String> failedApparelOut, List<String> failedStatisticsOut, String dataPath) {
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

        // KI-30: run the general random-character generator FIRST to get a fully-populated
        // baseline (statistics/instinct/personality/state/store/profile/race/alignment), then
        // apply the LLM-extracted overrides on top of it below — instead of building the
        // charPerson from an almost-empty record. Age is needed by rollStatistics/rollHeight, so
        // it's parsed here (ahead of its other, pre-existing use further down) rather than
        // duplicating the parseAgeApprox() call.
        int age = parseAgeApprox(charData);
        // KI-30 + C3 (shared-library colors): acquire the memoized OlioContext once and thread it into
        // BOTH the random baseline and the apparel/color path, so apparel colors resolve against the
        // world's shared color library (ctx.getUniverse().colors) rather than a per-owner fallback group.
        OlioContext octx = null;
        if (dataPath != null && !dataPath.isEmpty()) {
            octx = OlioContextUtil.getOlioContext(user, dataPath);
            if (octx == null) {
                logger.warn("OlioContextUtil.getOlioContext returned null (dataPath=" + dataPath + ") for "
                        + name + " — random baseline + shared-library colors unavailable");
            }
        } else {
            logger.warn("No datagen.path configured — skipping random baseline for " + name
                    + " (pre-KI-30 sparse fallback); apparel colors fall back to the per-owner group");
        }
        BaseRecord baseline = buildRandomBaseline(octx, lastName, age);

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
            String gender = normalizeGender((String) charData.get("gender"));
            // Undetermined LLM gender must NOT persist as an invalid value — the old "UNKNOWN" (7 chars)
            // exceeded the apparel gender field's maxLength and crashed apparel creation. Fall back to
            // the random baseline's valid lowercase gender (KI-30: override only when the LLM determined one).
            if ((gender == null || gender.isEmpty()) && baseline != null) {
                Object baseGender = baseline.get(FieldNames.FIELD_GENDER);
                if (baseGender != null && !baseGender.toString().isBlank()) gender = baseGender.toString();
            }
            charPerson.set("gender", gender);

            // KI-30: race/alignment are plain (non-foreign) fields directly on charPerson, so the
            // baseline value can be applied straight onto the in-memory record before create() —
            // no separate persisted-instance/PATCH step needed, unlike the foreign sub-models
            // below. Only applied when the LLM didn't already determine something more specific
            // (it never extracts race/alignment today, so this is unconditional for now).
            if (baseline != null) {
                List<String> baseRace = baseline.get(OlioFieldNames.FIELD_RACE);
                if (baseRace != null && !baseRace.isEmpty()) charPerson.set(OlioFieldNames.FIELD_RACE, baseRace);
                Object baseAlignment = baseline.get(FieldNames.FIELD_ALIGNMENT);
                if (baseAlignment != null) charPerson.set(FieldNames.FIELD_ALIGNMENT, baseAlignment);
            }

            // C2: race is a list<string> whose values must be RaceEnumType constant NAMES (same as the
            // random baseline sets). The extraction prompt does not surface race today, but if it ever
            // does, map the free text to the enum constant and override the baseline; an unmappable
            // value leaves the baseline race in place (never a raw string). No-op when charData has no
            // "race" key or it doesn't map.
            Object raceObj = charData.get("race");
            if (raceObj instanceof String && NarrativeUtil.isMeaningful((String) raceObj)) {
                String raceEnum = mapRaceOverride((String) raceObj);
                if (raceEnum != null) {
                    charPerson.set(OlioFieldNames.FIELD_RACE, Arrays.asList(raceEnum));
                } else {
                    logger.info("LLM race '" + ((String) raceObj).trim() + "' for " + name
                            + " maps to no RaceEnumType constant — keeping baseline race");
                }
            }

            // Age/ethnicity/skills — plain columns on identity.person/charPerson (not foreign/
            // referenced records), so these can be set directly before create(), same as gender.
            // NarrativeUtil.isMeaningful() filters literal placeholder strings the LLM emits for
            // fields it couldn't determine ("null", "n/a", "unknown", etc. — confirmed live: this
            // extraction prompt returns the literal text "null" for ethnicity far more often than
            // a real JSON null, which a plain != null/isBlank() check would not catch).
            if (age > 0) charPerson.set("age", age);
            // C2: ethnicity is a list<string> whose values must be EthnicityEnumType constant NAMES
            // (NarrativeUtil.getEthnicityDescription reads them back via EthnicityEnumType.valueOf(name),
            // which throws on raw free text). Map the LLM's free-text value to the enum constant the
            // random-generation path would produce; if it maps to no valid constant, KEEP the baseline
            // (leave ethnicity unset) rather than storing a raw string.
            Object ethnicityObj = charData.get("ethnicity");
            if (ethnicityObj instanceof String && NarrativeUtil.isMeaningful((String) ethnicityObj)) {
                String ethEnum = mapEthnicityOverride((String) ethnicityObj);
                if (ethEnum != null) {
                    charPerson.set("ethnicity", Arrays.asList(ethEnum));
                } else {
                    logger.info("LLM ethnicity '" + ((String) ethnicityObj).trim() + "' for " + name
                            + " maps to no EthnicityEnumType constant — keeping baseline (unset), not storing raw text");
                }
            }
            Object skillsObj = charData.get("skills");
            if (skillsObj instanceof List) {
                List<String> skills = new ArrayList<>();
                for (Object s : (List<?>) skillsObj) {
                    if (s instanceof String && NarrativeUtil.isMeaningful((String) s)) skills.add(((String) s).trim());
                }
                if (!skills.isEmpty()) charPerson.set("trades", skills);
            }

            // ── DETACH the factory's home-directory placeholders before create ──
            //
            // MEASURED 2026-08-17 (TestPictureBookWorkflow#TestFreshCharacterSubRecordsAndPortraitRender,
            // the first run that ever executed this path): six of the seven sub-records were still landing
            // in the ACTING USER'S HOME, i.e. phase 3's reroute was not merely unexercised, it was
            // BYPASSED. Two facts, both the opposite of what the comments below used to assert:
            //   1. ModelSchema.autoCreateForeignReference DEFAULTS TO TRUE (ModelSchema.java:60). A model
            //      that "does not set" it therefore HAS it on. olio.charPerson does not set it ⇒ on.
            //   2. CharPersonFactory.implement() pre-builds statistics/instinct/behavior/personality/
            //      state/store/profile as in-memory records path-scoped to "~/" + schemaGroup, and
            //      DBWriter.applyAutoCreateList (:367-403) creates every non-identity foreign child on
            //      CREATE via RecordUtil.createRecords — a PBAC bypass, which is why there is no ADD audit
            //      line for them and why this went unnoticed.
            // So by the time the `id <= 0` guards below run, the placeholders already have real ids in
            // ~/Profiles, ~/Statistics, ~/Stores, ... and every PbSubRecordUtil call site is skipped.
            // Only `narrative` ever reached the reroute, because the factory does not pre-build one.
            //
            // Detaching them makes the auto-create list empty for these fields, so the blocks below create
            // them through PbSubRecordUtil in the world groups and link them by PATCH.
            //
            // Gated on having somewhere else to put them, deliberately: with no OlioContext the world
            // destination does not exist, and detaching would delete behaviour rather than move it. Same
            // for instinct/personality/state, whose creation below is conditional on `baseline != null` —
            // detaching those without a baseline would leave the character with none at all.
            // `behavior` is intentionally NOT detached: it is not one of the seven, nothing routes it, and
            // it keeps its existing ~/Behaviors destination.
            if (octx != null) {
                detachFactoryPlaceholder(charPerson, name, FieldNames.FIELD_PROFILE);
                detachFactoryPlaceholder(charPerson, name, OlioFieldNames.FIELD_STATISTICS);
                detachFactoryPlaceholder(charPerson, name, FieldNames.FIELD_STORE);
                if (baseline != null) {
                    detachFactoryPlaceholder(charPerson, name, OlioFieldNames.FIELD_INSTINCT);
                    detachFactoryPlaceholder(charPerson, name, FieldNames.FIELD_PERSONALITY);
                    detachFactoryPlaceholder(charPerson, name, FieldNames.FIELD_STATE);
                }
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
            // Ensure the profile is a real *persisted* record in the right group. CharPersonFactory builds
            // an in-memory placeholder (a path-scoped identity.profile with no id) in the ACTING USER'S
            // HOME, and — corrected 2026-08-17, measured — autoCreateForeignReference defaults to TRUE, so
            // DBWriter DOES cascade that placeholder into the database on create. That is why the detach
            // above exists: without it this guard never fires and the profile lands in ~/Profiles. With no
            // OlioContext (nothing detached) the guard still catches a genuinely absent profile.
            // Without a persisted profile, portraits can never be linked to the character later.
            BaseRecord profile = charPerson.get("profile");
            Long existingProfileId = (profile != null) ? profile.get(FieldNames.FIELD_ID) : null;
            if (profile == null || existingProfileId == null || existingProfileId <= 0L) {
                BaseRecord newProfile = PbSubRecordUtil.createSubRecord(user, octx, ModelNames.MODEL_PROFILE,
                        baseline != null ? baseline.get(FieldNames.FIELD_PROFILE) : null);
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

            /// The narrative is created LAST of the seven, after personality/instinct/state below - see
            /// ensureNarrative's javadoc. The ordering is load-bearing, not stylistic.

            // Ensure statistics/store are real *persisted* records — same gap as profile/narrative
            // above, and reachable for the same reason (the detach before create; CharPersonFactory's
            // placeholders DO otherwise cascade, into the acting user's home). Unlike profile/narrative,
            // these are hard prerequisites for the statistics-estimation and apparel-wizard steps
            // below, not independently optional — if either fails to persist, abort character
            // creation the same way a profile/narrative failure already does, rather than letting
            // the statistics/apparel steps silently patch a record with id<=0.
            BaseRecord statistics = charPerson.get(OlioFieldNames.FIELD_STATISTICS);
            Long existingStatsId = (statistics != null) ? statistics.get(FieldNames.FIELD_ID) : null;
            if (statistics == null || existingStatsId == null || existingStatsId <= 0L) {
                BaseRecord newStats = PbSubRecordUtil.createSubRecord(user, octx, OlioModelNames.MODEL_CHAR_STATISTICS,
                        baseline != null ? baseline.get(OlioFieldNames.FIELD_STATISTICS) : null);
                if (newStats == null) {
                    logger.error("Failed to create persisted statistics for charPerson " + name);
                    return null;
                }
                BaseRecord statsLinked = patchCharPersonField(user, charPerson, OlioFieldNames.FIELD_STATISTICS, newStats);
                if (statsLinked == null) {
                    logger.error("Failed to link persisted statistics to charPerson " + name);
                    return null;
                }
                charPerson.set(OlioFieldNames.FIELD_STATISTICS, newStats);
                statistics = newStats;
            }

            BaseRecord store = charPerson.get(FieldNames.FIELD_STORE);
            Long existingStoreId = (store != null) ? store.get(FieldNames.FIELD_ID) : null;
            if (store == null || existingStoreId == null || existingStoreId <= 0L) {
                BaseRecord newStore = PbSubRecordUtil.createSubRecord(user, octx, OlioModelNames.MODEL_STORE,
                        baseline != null ? baseline.get(FieldNames.FIELD_STORE) : null);
                if (newStore == null) {
                    logger.error("Failed to create persisted store for charPerson " + name);
                    return null;
                }
                BaseRecord storeLinked = patchCharPersonField(user, charPerson, FieldNames.FIELD_STORE, newStore);
                if (storeLinked == null) {
                    logger.error("Failed to link persisted store to charPerson " + name);
                    return null;
                }
                charPerson.set(FieldNames.FIELD_STORE, newStore);
                store = newStore;
            }

            // KI-30: instinct/personality/state — new, best-effort persisted foreign sub-records
            // seeded from the random baseline (previously never created at all here; charPerson.
            // instinct/personality/state stayed permanently null/unpersisted). Not hard-required
            // like statistics/store above — nothing in the current PictureBook pipeline reads
            // them yet, so a failure is logged and character creation continues rather than
            // aborting.
            if (baseline != null) {
                try {
                    BaseRecord instinct = charPerson.get(OlioFieldNames.FIELD_INSTINCT);
                    Long existingInstinctId = (instinct != null) ? instinct.get(FieldNames.FIELD_ID) : null;
                    if (instinct == null || existingInstinctId == null || existingInstinctId <= 0L) {
                        BaseRecord newInstinct = PbSubRecordUtil.createSubRecord(user, octx, OlioModelNames.MODEL_INSTINCT,
                                baseline.get(OlioFieldNames.FIELD_INSTINCT));
                        if (newInstinct != null) {
                            BaseRecord linked = patchCharPersonField(user, charPerson, OlioFieldNames.FIELD_INSTINCT, newInstinct);
                            if (linked != null) charPerson.set(OlioFieldNames.FIELD_INSTINCT, newInstinct);
                            else logger.warn("Failed to link persisted instinct to charPerson " + name);
                        } else {
                            logger.warn("Failed to create persisted instinct for charPerson " + name);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed instinct baseline for " + name + ": " + e.getMessage());
                }

                try {
                    BaseRecord personality = charPerson.get(FieldNames.FIELD_PERSONALITY);
                    Long existingPersonalityId = (personality != null) ? personality.get(FieldNames.FIELD_ID) : null;
                    if (personality == null || existingPersonalityId == null || existingPersonalityId <= 0L) {
                        BaseRecord newPersonality = PbSubRecordUtil.createSubRecord(user, octx, ModelNames.MODEL_PERSONALITY,
                                baseline.get(FieldNames.FIELD_PERSONALITY));
                        if (newPersonality != null) {
                            BaseRecord linked = patchCharPersonField(user, charPerson, FieldNames.FIELD_PERSONALITY, newPersonality);
                            if (linked != null) {
                                charPerson.set(FieldNames.FIELD_PERSONALITY, newPersonality);
                                // Populate OCEAN/personality with the SAME roll the population loop uses
                                // (CharacterUtil.java:423) — copy-from-baseline alone leaves the traits at
                                // 0 — then persist the full record.
                                try {
                                    ProfileUtil.rollPersonality(newPersonality);
                                    IOSystem.getActiveContext().getAccessPoint().update(user, newPersonality);
                                } catch (Exception re) {
                                    logger.warn("Failed to roll/persist personality for " + name + ": " + re.getMessage());
                                }
                            }
                            else logger.warn("Failed to link persisted personality to charPerson " + name);
                        } else {
                            logger.warn("Failed to create persisted personality for charPerson " + name);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed personality baseline for " + name + ": " + e.getMessage());
                }

                try {
                    BaseRecord state = charPerson.get(FieldNames.FIELD_STATE);
                    Long existingStateId = (state != null) ? state.get(FieldNames.FIELD_ID) : null;
                    if (state == null || existingStateId == null || existingStateId <= 0L) {
                        BaseRecord newState = PbSubRecordUtil.createSubRecord(user, octx, OlioModelNames.MODEL_CHAR_STATE,
                                baseline.get(FieldNames.FIELD_STATE));
                        if (newState != null) {
                            BaseRecord linked = patchCharPersonField(user, charPerson, FieldNames.FIELD_STATE, newState);
                            if (linked != null) charPerson.set(FieldNames.FIELD_STATE, newState);
                            else logger.warn("Failed to link persisted state to charPerson " + name);
                        } else {
                            logger.warn("Failed to create persisted state for charPerson " + name);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed state baseline for " + name + ": " + e.getMessage());
                }

                // Hair/eye color — top-level data.color FOREIGN refs on charPerson, set on the random
                // baseline by CharacterUtil.setStyleByRace (race-appropriate palette). copyBaselineFieldValues
                // can't carry them (it skips foreign fields), so link them explicitly by FK reference, the
                // same PATCH mechanism the sub-models above use.
                try {
                    BaseRecord baseHair = (baseline.get(OlioFieldNames.FIELD_HAIR_COLOR) instanceof BaseRecord)
                            ? (BaseRecord) baseline.get(OlioFieldNames.FIELD_HAIR_COLOR) : null;
                    if (baseHair != null) {
                        Long hairId = baseHair.get(FieldNames.FIELD_ID);
                        if (hairId != null && hairId > 0L
                                && patchCharPersonField(user, charPerson, OlioFieldNames.FIELD_HAIR_COLOR, baseHair) != null) {
                            charPerson.set(OlioFieldNames.FIELD_HAIR_COLOR, baseHair);
                        } else {
                            logger.warn("Failed to link hairColor (id=" + hairId + ") to charPerson " + name);
                        }
                    }
                    BaseRecord baseEye = (baseline.get(OlioFieldNames.FIELD_EYE_COLOR) instanceof BaseRecord)
                            ? (BaseRecord) baseline.get(OlioFieldNames.FIELD_EYE_COLOR) : null;
                    if (baseEye != null) {
                        Long eyeId = baseEye.get(FieldNames.FIELD_ID);
                        if (eyeId != null && eyeId > 0L
                                && patchCharPersonField(user, charPerson, OlioFieldNames.FIELD_EYE_COLOR, baseEye) != null) {
                            charPerson.set(OlioFieldNames.FIELD_EYE_COLOR, baseEye);
                        } else {
                            logger.warn("Failed to link eyeColor (id=" + eyeId + ") to charPerson " + name);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed hair/eye color baseline for " + name + ": " + e.getMessage());
                }
            }

            /// LAST of the seven, and it has to be: see ensureNarrative.
            if (!ensureNarrative(user, octx, charPerson, name, portraitPrompt)) {
                return null;
            }

            // Best-effort statistics estimation + apparel wizard — enhancements on top of an
            // already-usable character (Stage 1 already runs fine with empty store.apparel/default
            // statistics), unlike profile/narrative/store/statistics above which are hard-required.
            // Failures here are logged and degrade gracefully rather than aborting creation.
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> physical = (Map<String, Object>) charData.get("physical");
                StatisticsUtil.estimateFromExtractedPhysical(statistics, physical, normalizeGender(gender), parseAgeApprox(charData));
                // Persist the FULL rolled statistics — the earlier partial 6-field patch dropped every
                // other rolled stat (mental/social/etc.), leaving them 0. olio.statistics has no foreign
                // refs, so a full AccessPoint update is PBAC-safe and saves everything rollStatistics set.
                BaseRecord statsPersisted = IOSystem.getActiveContext().getAccessPoint().update(user, statistics);
                if (statsPersisted == null) {
                    logger.warn("Failed to persist estimated statistics for " + name + " — AccessPoint.update denied or failed");
                    if (failedStatisticsOut != null) failedStatisticsOut.add(name);
                }
            } catch (Exception e) {
                logger.warn("Failed to estimate statistics for " + name + ": " + e.getMessage());
                if (failedStatisticsOut != null) failedStatisticsOut.add(name);
            }

            try {
                BaseRecord apparel = generateApparelFromCharData(user, chatConfig, charPerson, charData, octx);
                if (apparel != null) {
                    // Neither the LLM-guess path nor the randomApparel fallback marks the apparel
                    // or its wearables `inuse` on its own —
                    // ApparelUtil.getWearing()/NarrativeUtil.describeOutfit() both filter on
                    // inuse==true (apparel AND per-wearable), falling back to literal
                    // "naked/nude, wearing no clothes" text otherwise. Both real precedents
                    // (applyAutfit, outfitAndStage) explicitly set this on both levels — mirror
                    // them exactly, or every character renders/describes as nude regardless of
                    // how much wardrobe logic ran.
                    apparel.setValue(OlioFieldNames.FIELD_IN_USE, true);
                    List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
                    if (wearables != null) {
                        for (BaseRecord w : wearables) w.setValue(OlioFieldNames.FIELD_IN_USE, true);
                    }
                    IOSystem.getActiveContext().getRecordUtil().createRecord(apparel);
                    IOSystem.getActiveContext().getMemberUtil().member(user, store, OlioFieldNames.FIELD_APPAREL, apparel, null, true);
                    // member() only writes the participation link to the DB — it does NOT mutate
                    // store's own in-memory apparel list. Without this, ApparelUtil.getWearing()
                    // (called by describeOutfit() just below, on this same in-memory charPerson)
                    // reads a stale empty list and falls back to "naked/nude, wearing no clothes"
                    // even though the apparel is correctly persisted+linked — confirmed live.
                    List<BaseRecord> storeApparelList = store.get(OlioFieldNames.FIELD_APPAREL);
                    if (storeApparelList != null) storeApparelList.add(apparel);

                    // Makes the existing, unmodified charPerson reimage command's
                    // am7olio.setNarDescription() (builds its SD prompt from
                    // narrative.physicalDescription + narrative.outfitDescription) pick up this
                    // apparel automatically, with no frontend change needed.
                    BaseRecord narrativeForOutfit = charPerson.get("narrative");
                    if (narrativeForOutfit != null) {
                        narrativeForOutfit.set("outfitDescription", NarrativeUtil.describeOutfit(charPerson, false));
                        BaseRecord outfitPatch = narrativeForOutfit.copyRecord(
                                new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "outfitDescription" });
                        BaseRecord outfitPersisted = IOSystem.getActiveContext().getAccessPoint().update(user, outfitPatch);
                        if (outfitPersisted == null) {
                            logger.warn("Failed to persist narrative.outfitDescription for " + name);
                        }
                    }
                } else {
                    logger.warn("Apparel wizard returned no apparel for " + name);
                    if (failedApparelOut != null) failedApparelOut.add(name);
                }
            } catch (Exception e) {
                logger.warn("Failed to generate apparel for " + name + ": " + e.getMessage());
                if (failedApparelOut != null) failedApparelOut.add(name);
            }

            return charPerson;

        } catch (Exception e) {
            logger.error("Failed to create charPerson " + name + ": " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * LLM-guessed apparel: asks the LLM to pick 2-5 items from ApparelUtil's real wardrobe catalog
     * that fit the character's extracted appearance/clothing_style/outfit_notes/role, then builds
     * a real apparel record from those exact names via ApparelUtil.constructApparel/getEmbeddedOutfit
     * (same construction path contextApparel's random wizard uses — the only difference is WHICH
     * item names get chosen). Falls back to ApparelUtil.randomApparel whenever the LLM path can't
     * produce something usable: no meaningful charData to guess from, the call/parse fails, or none
     * of the guessed names matched the catalog (getEmbeddedOutfit silently drops unmatched names,
     * so an apparel with zero wearables is treated as "nothing usable", not a success).
     *
     * Logs both the LLM request (vars sent) and the resulting outfit — either the LLM's own
     * one-sentence description, or "(fallback: random outfit)" when the guess didn't pan out — so
     * a run can be audited without re-deriving what happened from the wearables list alone.
     */
    private static BaseRecord generateApparelFromCharData(BaseRecord user, BaseRecord chatConfig,
            BaseRecord charPerson, Map<String, Object> charData, OlioContext octx) {
        String name = (String) charData.get("name");
        String gender = charPerson.get(FieldNames.FIELD_GENDER);
        long ownerId = charPerson.get(FieldNames.FIELD_OWNER_ID);

        String appearance = extractMeaningfulPhysicalSummary(charData);
        String clothingStyle = meaningfulOrEmpty(charData.get("clothing_style"));
        String outfitNotes = meaningfulOrEmpty(charData.get("outfit_notes"));
        String role = meaningfulOrEmpty(charData.get("role"));

        if (appearance.isEmpty() && clothingStyle.isEmpty() && outfitNotes.isEmpty() && role.isEmpty()) {
            logger.info("No meaningful appearance/clothing_style/outfit_notes/role for " + name + " — skipping apparel-guess LLM call, using random outfit");
            return ApparelUtil.randomApparel(octx, charPerson);
        }

        List<String> catalog = ApparelUtil.getApparelCatalogNames(gender);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("name", name);
        vars.put("gender", gender != null ? gender : "unknown");
        vars.put("appearance", appearance.isEmpty() ? "(none given)" : appearance);
        vars.put("clothingStyle", clothingStyle.isEmpty() ? "(none given)" : clothingStyle);
        vars.put("outfitNotes", outfitNotes.isEmpty() ? "(none given)" : outfitNotes);
        vars.put("role", role.isEmpty() ? "(none given)" : role);
        vars.put("catalog", String.join(", ", catalog));
        logger.info("Apparel-guess LLM request for " + name + ": appearance=[" + vars.get("appearance")
                + "] clothingStyle=[" + vars.get("clothingStyle") + "] outfitNotes=[" + vars.get("outfitNotes")
                + "] role=[" + vars.get("role") + "]");

        String llmResponse = callLlm(user, chatConfig, "pictureBook.guess-apparel", vars);
        Map<String, Object> guess = parseLlmJsonObject(llmResponse, "guess-apparel:" + name, null);

        @SuppressWarnings("unchecked")
        List<String> items = (guess.get("items") instanceof List) ? (List<String>) guess.get("items") : null;
        String description = (String) guess.get("description");
        // C3: the LLM may return a small "colors" palette of plain color NAMES — resolved below
        // against the shared color library and applied as data.color FOREIGN references.
        List<String> guessedColors = new ArrayList<>();
        if (guess.get("colors") instanceof List) {
            for (Object c : (List<?>) guess.get("colors")) {
                if (c instanceof String && NarrativeUtil.isMeaningful((String) c)) guessedColors.add(((String) c).trim());
            }
        }

        if (items == null || items.isEmpty()) {
            logger.warn("Apparel-guess LLM returned no usable items for " + name + " (raw=" + llmResponse + ") — falling back to random outfit");
            return ApparelUtil.randomApparel(octx, charPerson);
        }

        // Olio-owned by design (ctx.getOlioUser(), world groups) so colors resolve from the shared color
        // library, which the complementary-color computation requires. Dress-up/down access for the
        // acting user is granted via the OlioUsers role on the apparel/wearables/qualities world groups,
        // rather than by making these records user-owned.
        BaseRecord apparel = ApparelUtil.constructApparel(octx, ownerId, charPerson, items.toArray(new String[0]));
        List<BaseRecord> wearables = (apparel != null) ? apparel.get(OlioFieldNames.FIELD_WEARABLES) : null;
        if (apparel == null || wearables == null || wearables.isEmpty()) {
            logger.warn("Apparel-guess LLM items " + items + " for " + name + " matched nothing in the catalog — falling back to random outfit");
            return ApparelUtil.randomApparel(octx, charPerson);
        }

        ApparelUtil.designApparel(apparel);
        // C3: route any LLM-guessed color names through the shared color-library lookup and store
        // them as data.color FOREIGN references (never a raw string). Applied AFTER designApparel so
        // the guessed colors win over the random/harmonized picks; unresolved names silently keep
        // the random fallback color designWearable already assigned.
        int colorsApplied = ApparelUtil.applyGuessedColors(octx, apparel, guessedColors);
        logger.info("Apparel-guess outfit for " + name + ": items=" + items
                + " description=[" + (description != null ? description : "(none given)") + "]"
                + " colorsGuessed=" + guessedColors + " colorsResolved=" + colorsApplied);
        return apparel;
    }

    /** charData.get(key) as a trimmed string, or "" when missing/blank/an LLM literal-null placeholder. */
    private static String meaningfulOrEmpty(Object val) {
        if (!(val instanceof String) || !NarrativeUtil.isMeaningful((String) val)) return "";
        return ((String) val).trim();
    }

    /** Short "build, hair, eyes" summary from charData.physical, skipping non-meaningful fields. */
    @SuppressWarnings("unchecked")
    private static String extractMeaningfulPhysicalSummary(Map<String, Object> charData) {
        Object physicalObj = charData.get("physical");
        List<String> parts = new ArrayList<>();
        String appearanceField = meaningfulOrEmpty(charData.get("appearance"));
        if (!appearanceField.isEmpty()) parts.add(appearanceField);
        if (physicalObj instanceof Map) {
            Map<String, Object> physical = (Map<String, Object>) physicalObj;
            for (String key : new String[] { "build", "hair", "eyes", "skin" }) {
                String v = meaningfulOrEmpty(physical.get(key));
                if (!v.isEmpty()) parts.add(v);
            }
        }
        return String.join(", ", parts);
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
            // Drop the transient raw content block (used only to reduce per-character detail during
            // createFromScenes) so it never persists into every scene note's text JSON.
            sceneStore.remove("sourceText");
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
     * Smart scene extraction — auto-chunks if text > {@link #MAX_EXTRACTION_TEXT_CHARS} chars.
     */
    public static ScenesOnlyResult extractScenesOnly(BaseRecord user, String workObjectId, int count,
            String chatConfigName, String promptTemplateOverride) {
        return extractScenesOnly(user, workObjectId, count, chatConfigName, promptTemplateOverride, null);
    }

    /**
     * KI-10 overload: same as {@link #extractScenesOnly(BaseRecord, String, int, String, String)},
     * plus an optional {@code cancelToken} threaded down to {@link #extractChunkedInternal} for the
     * auto-chunk path (only path that makes multiple sequential LLM calls here).
     */
    public static ScenesOnlyResult extractScenesOnly(BaseRecord user, String workObjectId, int count,
            String chatConfigName, String promptTemplateOverride, SummarizeProgress cancelToken) {
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

        List<String> failedExtractions = new ArrayList<>();

        // Auto-chunk if text exceeds MAX_EXTRACTION_TEXT_CHARS
        if (text.length() > MAX_EXTRACTION_TEXT_CHARS) {
            List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text, cancelToken, failedExtractions);
            return new ScenesOnlyResult(sceneList, true, failedExtractions);
        }

        // Short text — single-shot extraction
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome", "Extracting scenes...");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("count", String.valueOf(count));
        vars.put("text", text);

        String llmResponse = callLlm(user, chatConfig, "pictureBook.extract-scenes", vars, promptTemplateOverride);
        List<Map<String, Object>> scenes = parseLlmJsonArray(llmResponse, "extract-scenes:" + workObjectId, failedExtractions);
        // Normalize: LLM may return "summary" instead of "blurb"
        for (Map<String, Object> scene : scenes) {
            if (scene.get("blurb") == null && scene.get("summary") != null) {
                scene.put("blurb", scene.get("summary"));
            }
            // Single-shot path: the whole (short) text is the one content block (see the chunked
            // path's per-scene sourceText). Transient; stripped before persistence in createSceneNote.
            scene.put("sourceText", text);
        }
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        OllamaModelUtil.unloadAll();
        return new ScenesOnlyResult(scenes, false, failedExtractions);
    }

    /**
     * Chunked scene extraction — kept for backward compatibility; extractScenesOnly now auto-chunks.
     */
    public static BaseRecord extractChunked(BaseRecord user, String workObjectId, String chatConfigName) {
        return extractChunked(user, workObjectId, chatConfigName, null);
    }

    /**
     * KI-10 overload: same as {@link #extractChunked(BaseRecord, String, String)}, plus an optional
     * {@code cancelToken} threaded down to {@link #extractChunkedInternal}.
     */
    public static BaseRecord extractChunked(BaseRecord user, String workObjectId, String chatConfigName,
            SummarizeProgress cancelToken) {
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

        List<String> failedExtractions = new ArrayList<>();
        List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text, cancelToken, failedExtractions);
        BaseRecord result = buildResult();
        try {
            result.set("sceneList", sceneList);
            result.set("extractionComplete", true);
            result.set("chunksProcessed", -1);
            if (!failedExtractions.isEmpty()) result.set("failedExtractions", failedExtractions);
        } catch (Exception e) { logger.warn("Failed to build chunked result: " + e.getMessage()); }
        return result;
    }

    /**
     * Full extraction: scenes + characters + outfit + narrate. Returns .pictureBookMeta.
     *
     * @param dataPath the {@code datagen.path} init-param value, threaded down to
     *   {@code createCharPerson} for KI-30's random-baseline-then-override character creation
     *   (see {@code PictureBookService}, which reads it from the servlet context — mirrors
     *   {@code GameService}'s identical use of the same init param).
     */
    public static BaseRecord extract(BaseRecord user, String workObjectId, int count, String chatConfigName,
            String genre, String bookName, String dataPath) {
        if (count > MAX_SCENES_DEFAULT) count = MAX_SCENES_DEFAULT;
        // Legacy all-in-one endpoint, now implemented as the SAME two steps as the Ux flow so it shares
        // ONE character-creation path — the block-scoped reduce (pictureBook.reduce-character over each
        // character's OWN content blocks), the scene-ref (ATTR_SCENE_REFS) + condensed-description
        // (ATTR_DESCRIPTION) attributes, and Attr2-driven imaging — instead of a divergent inline
        // extract-character loop over the truncated work opening (which mis-described characters
        // introduced later, e.g. 'Thug'). Step 1 extracts scenes (chunked for long works, so each scene
        // carries its source content block); step 2 (createFromScenes) creates characters + scene notes
        // + meta. extractScenesOnly's own scene-parse failures surface in its ScenesOnlyResult (as they
        // do for the Ux flow); createFromScenes records its per-character reduce failures on the meta.
        ScenesOnlyResult scenes = extractScenesOnly(user, workObjectId, count, chatConfigName, null);
        return createFromScenes(user, workObjectId, chatConfigName, genre, bookName,
                scenes.scenes, new ArrayList<>(), dataPath);
    }

    /**
     * Takes user-curated scene list from Step 2, creates book group, scene notes, extracts +
     * creates charPerson records, saves meta. Returns the .pictureBookMeta record.
     *
     * @param dataPath the {@code datagen.path} init-param value, threaded down to
     *   {@code createCharPerson} for KI-30's random-baseline-then-override character creation —
     *   see {@link #extract} for the same parameter.
     */
    @SuppressWarnings("unchecked")
    public static BaseRecord createFromScenes(BaseRecord user, String workObjectId, String chatConfigName,
            String genre, String bookName, List<Map<String, Object>> sceneList, List<Map<String, Object>> charDataListIn,
            String dataPath) {
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

        // Map each character (by name) to the scene indices it appears in and the raw content blocks
        // those scenes were extracted from — the passages where the character actually appears. Used
        // below to REDUCE per-character detail from the RIGHT text (not the truncated work opening,
        // which described the wrong passage for a character introduced later, e.g. 'Thug'), to persist
        // scene references (ATTR_SCENE_REFS), and to produce the condensed imaging description (ATTR_DESCRIPTION).
        Map<String, List<Integer>> charSceneIndices = new LinkedHashMap<>();
        Map<String, java.util.LinkedHashSet<String>> charBlocks = new LinkedHashMap<>();
        for (int si = 0; si < sceneList.size(); si++) {
            Map<String, Object> scene = sceneList.get(si);
            Object stObj = scene.get("sourceText");
            String block = (stObj instanceof String) ? (String) stObj : null;
            Object charsObj = scene.get("characters");
            if (!(charsObj instanceof List)) continue;
            for (Object sc : (List<Object>) charsObj) {
                String cn = (sc instanceof Map) ? (String) ((Map<String, Object>) sc).get("name")
                        : (sc instanceof String ? (String) sc : null);
                if (cn == null || cn.isEmpty()) continue;
                charSceneIndices.computeIfAbsent(cn, k -> new ArrayList<>()).add(si);
                if (block != null && !block.isBlank()) {
                    charBlocks.computeIfAbsent(cn, k -> new java.util.LinkedHashSet<>()).add(block);
                }
            }
        }

        // KI-42: resolve every foreign sub-model group once, before the character loop, rather than
        // letting all 13 createPersistedForeignInstance call sites re-run the same get-or-create for
        // each character.
        //
        // The context is threaded in, corrected 2026-08-17. This passed `null`, which pre-resolved the
        // LEGACY ~/{schemaGroup} groups — not the ones createCharPerson actually writes into, since it
        // resolves its own OlioContext from this same dataPath. So the pre-resolution warmed the wrong
        // seven groups and left the real destinations to be get-or-created per character, i.e. it did
        // exactly nothing for the race it exists to shrink. With no dataPath there is no context and the
        // legacy destinations are the real ones, so passing null then is correct.
        PbSubRecordUtil.prepareGroups(user, (dataPath != null && !dataPath.isEmpty())
                ? OlioContextUtil.getOlioContext(user, dataPath) : null);

        // Create charPerson records — use LLM for detail extraction if needed
        Map<String, String> charObjectIds = new LinkedHashMap<>();
        // createCharPerson() failures are never silently dropped — collected here so a 200
        // response can never mean "silently 0 characters created".
        List<String> failedCharacters = new ArrayList<>();
        List<String> failedApparel = new ArrayList<>();
        List<String> failedStatistics = new ArrayList<>();
        List<String> failedExtractions = new ArrayList<>();
        int cfsCharIdx = 0;
        for (Map<String, Object> charData : charDataList) {
            String cname = (String) charData.get("name");
            if (cname == null || cname.isEmpty()) continue;
            cfsCharIdx++;
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "person",
                    "Creating character " + cfsCharIdx + "/" + charDataList.size() + ": " + cname);

            // REDUCE per-character detail from the character's OWN content blocks (the passages where
            // they appear) — not the truncated work opening, which described the wrong passage for a
            // character introduced later (e.g. 'Thug'). Also yields the condensed, style/setting-free
            // "description" used for imaging (ATTR_DESCRIPTION). Falls back to the (bounded) work text
            // only when a character has no mapped blocks. Runs only when appearance isn't already given.
            String reducedDescription = null;
            java.util.LinkedHashSet<String> blocks = charBlocks.get(cname);
            String passages = (blocks != null && !blocks.isEmpty())
                    ? boundedPassages(cname, blocks, MAX_EXTRACTION_TEXT_CHARS)
                    : (text != null && !text.isEmpty()
                        ? (text.length() > MAX_EXTRACTION_TEXT_CHARS ? text.substring(0, MAX_EXTRACTION_TEXT_CHARS) : text)
                        : null);
            if ((charData.get("appearance") == null || ((String) charData.getOrDefault("appearance", "")).isEmpty())
                    && passages != null && !passages.isBlank() && chatConfig != null) {
                Map<String, String> charVars = new LinkedHashMap<>();
                charVars.put("name", cname);
                charVars.put("passages", passages);
                charVars.put("raceOptions", raceOptionsCsv());
                charVars.put("ethnicityOptions", ethnicityOptionsCsv());
                String llmChar = callLlm(user, chatConfig, "pictureBook.reduce-character", charVars);
                Map<String, Object> llmData = parseLlmJsonObject(llmChar, "reduce-character:" + cname, failedExtractions);
                if (!llmData.isEmpty()) {
                    Object d = llmData.remove("description");
                    if (d instanceof String && !((String) d).isBlank()) reducedDescription = ((String) d).trim();
                    // Merge structured fields without overwriting user/client-provided edits
                    for (Map.Entry<String, Object> e : llmData.entrySet()) {
                        if (!charData.containsKey(e.getKey()) || charData.get(e.getKey()) == null
                                || ((charData.get(e.getKey()) instanceof String) && ((String) charData.get(e.getKey())).isEmpty())) {
                            charData.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }

            BaseRecord cp = createCharPerson(user, chatConfig, charData, charsGroup, genre, failedApparel, failedStatistics, dataPath);
            if (cp != null) {
                charObjectIds.put(cname, cp.get(FieldNames.FIELD_OBJECT_ID));
                // Attribute 1 (scene refs) + Attribute 2 (condensed description). Attr2 is read at
                // imaging time (resolveSceneCharacter) as this character's visual description.
                persistCharacterSceneAttributes(user, cp, charSceneIndices.get(cname), reducedDescription);
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
        if (!failedApparel.isEmpty()) {
            try { meta.set("failedApparel", failedApparel); } catch (Exception e) { logger.warn("Failed to record failedApparel on meta: " + e.getMessage()); }
        }
        if (!failedStatistics.isEmpty()) {
            try { meta.set("failedStatistics", failedStatistics); } catch (Exception e) { logger.warn("Failed to record failedStatistics on meta: " + e.getMessage()); }
        }
        if (!failedExtractions.isEmpty()) {
            try { meta.set("failedExtractions", failedExtractions); } catch (Exception e) { logger.warn("Failed to record failedExtractions on meta: " + e.getMessage()); }
        }
        // Book-level composition/art-direction anchor: intentionally left BLANK by default (no
        // auto-seeded hardcoded art-direction line). Real book-wide style/composition consistency now
        // comes from the common olio.sd.config's style + bodyStyle/imageSetting/imageAction fields
        // (SDUtil.getSDConfigPrompt) — the single style seam shared across portraits/landscape/scene.
        // The compositionContext mechanism (loadCompositionContext/prependContextOnce) is kept intact
        // so it can be set explicitly later as optional extra prompt-level reinforcement; the
        // pictureBook.art-direction.json resource remains in place but is no longer auto-applied.
        try {
            meta.set("compositionContext", "");
        } catch (Exception e) { logger.warn("Failed to default compositionContext on meta: " + e.getMessage()); }
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
        /// Resolves the scene AND authorizes the caller against the book that owns it. This runs
        /// BEFORE any SD/LLM work, so a denial costs nothing and generates nothing.
        BaseRecord scene = authorizeSceneAccess(user, sceneObjectId, SceneAccessType.WRITE);

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

        String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
        if (sceneGroupPath == null) sceneGroupPath = "~/Chat";
        // Real book scenes live under .../Scenes; the ~/Chat single-image fallback has no book meta.
        String bookGroupPath = sceneGroupPath.endsWith("/Scenes")
                ? sceneGroupPath.substring(0, sceneGroupPath.length() - "/Scenes".length()) : null;

        // Resolve the ONE common olio.sd.config that drives this scene's portraits, landscape, and
        // composite — the single canonical style/param seam (SDUtil.getSDConfigPrompt). Precedence:
        // the request's sdConfig, else the book's stored config, else a random canonical config.
        // fillStyleDefaults guarantees the per-style detail fields are populated so getSDConfigPrompt
        // yields a full style string; an optional sparse per-scene override is overlaid on top (then
        // re-filled so a style change in the override pulls in that style's detail fields).
        BaseRecord common = params.sdConfig;
        if (common == null && bookGroupPath != null) common = getBookSdConfigByPath(user, bookGroupPath);
        if (common == null) common = SDUtil.randomSDConfig();
        SDUtil.fillStyleDefaults(common);
        if (params.sdConfigOverride != null) {
            SDUtil.applyOverrides(common, params.sdConfigOverride);
            SDUtil.fillStyleDefaults(common);
        }

        // Generation params now live ON the common config, not flattened scalars. Read the few the
        // portrait/Kontext stages consume directly as locals, falling back to the old code's defaults
        // only when a field is genuinely unset (null) or non-positive — landscape/classic scene read
        // the rest of the params straight off `common` via SWUtil.newSceneTxt2Img.
        Integer stepsV = common.get("steps");
        int steps = (stepsV != null && stepsV > 0) ? stepsV.intValue() : DEFAULT_STEPS;
        Integer cfgV = common.get("cfg");
        int cfg = (cfgV != null && cfgV > 0) ? cfgV.intValue() : DEFAULT_CFG;
        Boolean hiresV = common.get("hires");
        boolean hires = (hiresV != null) ? hiresV.booleanValue() : DEFAULT_HIRES;
        Integer seedV = common.get("seed");
        int seed = (seedV != null) ? seedV.intValue() : -1;
        String sdModelName = common.get("model");
        String sdSampler = common.get("sampler");
        String sdScheduler = common.get("scheduler");

        SDUtil sdu = new SDUtil(SDAPIEnumType.valueOf(sdApiType), sdServer);

        // Mark generation started — persisted so the wizard's progress survives a reload
        // (see listScenes()'s status/error merge and .claude/rules/model-api.md's PATCH pattern).
        updateSceneStatus(user, scene, "generating", null);

        // Auto-capture the resolved common config on the book so images can be recreated with the
        // same settings later (see persistBookSdConfig) — only for real book scenes (under
        // .../Scenes), not the ~/Chat single-image fallback which has no book meta.
        if (bookGroupPath != null) {
            persistBookSdConfig(user, bookGroupPath, common);
        }

        // promptOverride: skip pipeline, direct SDXL generation
        if (params.promptOverride != null && !params.promptOverride.isEmpty()) {
            // No LLM call in this branch (the caller supplied the prompt directly), but flush
            // defensively before the SD call anyway — cheap no-op if nothing is tracked as loaded.
            OllamaModelUtil.unloadAll();
            try {
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Generating image...");
                // Unchanged behavior: the caller supplied the exact prompt — use it verbatim as the
                // description (createImage uses description as-is, bypassing getSDConfigPrompt). The
                // common config still carries its resolved style; only the prompt text is overridden.
                common.set("description", params.promptOverride);
                String imageName = "scene_" + sceneObjectId + "_" + System.currentTimeMillis();
                List<BaseRecord> images = sdu.createImage(user, sceneGroupPath, common, imageName, 1, hires, -1);
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
            // Stage 0: Resolve (and cache) the landscape prompt AND the scene-image (composite)
            // prompt BEFORE any SD calls — keeps every LLM call ahead of every GPU-heavy SD call
            // so the model can be unloaded once instead of sitting loaded in VRAM across the whole
            // portrait/landscape/composite sequence. Scene characters are resolved here (DB-only,
            // no LLM/SD calls) purely to build charNarrations for the scene-image prompt; Stage 1
            // resolves them again (same resolveSceneCharacter helper) when it actually renders.
            String landscapePrompt = resolveLandscapePrompt(user, scene, chatConfig, setting, mood, common, params.promptTemplateOverride);
            
            //logger.info("Landscape prompt: " + landscapePrompt);
            
            Object charsObjForPrompt = sceneData.get("characters");
            List<String> charNarrationsForPrompt = new ArrayList<>();
            // Collected for the v2 graph's record bindings (role "character"): a binding onto the
            // charPerson is the ONLY thing that can see a character EDIT, which artifact chaining
            // structurally cannot — see PbWatchedFields and §2.3. Cheap: these are the same records
            // the loop already resolved, so nothing extra is read.
            List<String> promptCharObjectIds = new ArrayList<>();
            if (charsObjForPrompt instanceof List) {
                for (Object charItem : (List<Object>) charsObjForPrompt) {
                    if (charNarrationsForPrompt.size() >= 2) break;
                    ResolvedCharacter rc = resolveSceneCharacter(user, charItem, sceneGroupPath);
                    if (rc != null) {
                        charNarrationsForPrompt.add(rc.name + ": " + SWUtil.stripSDXLWeighting(rc.sceneNarration));
                        if (rc.charPerson != null) {
                            String rcOid = rc.charPerson.get(FieldNames.FIELD_OBJECT_ID);
                            if (rcOid != null) promptCharObjectIds.add(rcOid);
                        }
                    }
                }
            }
            String scenePrompt = resolveScenePrompt(user, scene, chatConfig, action, setting, mood, common,
                    charNarrationsForPrompt, params.promptTemplateOverride);
            OllamaModelUtil.unloadAll();

            // Resolved once for the whole call — used by Stage 1's per-character scene-tagged
            // apparel selection below (no-ops entirely for every character/book not using it).
            int currentSceneIndex = resolveCurrentSceneIndex(user, sceneGroupPath, sceneObjectId);

            // ══════════════════════════════════════════════════════════════════
            // PB2 (picturebook.v2) — open the workflow graph for this scene
            // ══════════════════════════════════════════════════════════════════
            // Behind the flag, and DEFAULT OFF: with v2 off every call below is a no-op and this
            // method behaves exactly as PictureBook 1 did, which is what makes
            // TestPictureBookCustom#TestPictureBookCustomPipeline a real non-regression gate.
            //
            // openSceneGraph is FIND-ONLY for the book and its world — it never creates them. A
            // render is a USE of a book; a use that created a book (and so a universe, a world,
            // three groups and a role pair) would be the LibraryUtil read-path-that-creates shape
            // .claude/rules/architecture.md warns about. A missing book logs and returns null.
            //
            // Every v2 call in this method is wrapped: the graph is PROVENANCE, and losing
            // provenance must never lose an image the GPU spent ten minutes producing.
            PbPipelineUtil.SceneGraph pbGraph = null;
            String pbBookGroupName = null;
            if (PbFeatureFlag.isV2Enabled()) {
                try {
                    if (bookGroupPath != null) {
                        int lastSlash = bookGroupPath.lastIndexOf('/');
                        pbBookGroupName = (lastSlash >= 0 && lastSlash < bookGroupPath.length() - 1)
                                ? bookGroupPath.substring(lastSlash + 1) : bookGroupPath;
                    }
                    pbGraph = PbPipelineUtil.openSceneGraph(user, params.bookSlug, pbBookGroupName,
                            sceneObjectId, currentSceneIndex, (String) sceneData.get("title"));
                } catch (Exception pbe) {
                    logger.warn("PB2: failed to open the scene graph; continuing with PB1 only: " + pbe.getMessage(), pbe);
                    pbGraph = null;
                }
            }

            // Stage 0's two prompt nodes. Recorded here, once the prompts are resolved, so the
            // artifact holds what was actually used rather than what was requested. Each carries a
            // sdConfigSnapshot because the resolved style is part of the prompt (getSDConfigPrompt).
            if (pbGraph != null) {
                try {
                    BaseRecord lpNode = PbPipelineUtil.getCreateNode(pbGraph,
                            PbPipelineUtil.landscapePromptHandle(sceneObjectId),
                            PbNodeTypeEnumType.LANDSCAPE_PROMPT, 10,
                            PbPipelineUtil.SCOPE_SCENE, sceneObjectId);
                    PbGraphUtil.persistPromptText(user, lpNode, landscapePrompt);
                    PbPipelineUtil.recordText(pbGraph, lpNode, PbPipelineUtil.ROLE_LANDSCAPE_PROMPT,
                            PbArtifactTypeEnumType.PROMPT, landscapePrompt, common);
                    PbPipelineUtil.completeNode(pbGraph, lpNode);

                    BaseRecord spNode = PbPipelineUtil.getCreateNode(pbGraph,
                            PbPipelineUtil.scenePromptHandle(sceneObjectId),
                            PbNodeTypeEnumType.SCENE_PROMPT, 11,
                            PbPipelineUtil.SCOPE_SCENE, sceneObjectId);
                    // The character bindings go on the PROMPT node, not the composite: the prompt is
                    // what the character's description actually feeds, so an edit to the character
                    // invalidates the prompt first and everything downstream by propagation.
                    for (int ci = 0; ci < promptCharObjectIds.size(); ci++) {
                        PbPipelineUtil.bindRecord(pbGraph, spNode, PbPipelineUtil.ROLE_CHARACTER, ci,
                                OlioModelNames.MODEL_CHAR_PERSON, promptCharObjectIds.get(ci));
                    }
                    PbGraphUtil.persistPromptText(user, spNode, scenePrompt);
                    PbPipelineUtil.recordText(pbGraph, spNode, PbPipelineUtil.ROLE_SCENE_PROMPT,
                            PbArtifactTypeEnumType.PROMPT, scenePrompt, common);
                    PbPipelineUtil.completeNode(pbGraph, spNode);
                } catch (Exception pbe) {
                    logger.warn("PB2: failed to record the Stage 0 prompt nodes: " + pbe.getMessage(), pbe);
                }
            }

            // Stage 1: Portrait bytes for up to 2 scene characters
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "face", "Generating portraits...");
            // Characters may be stored as [{name:...}] maps or as objectId strings
            List<byte[]> portraitBytesList = new ArrayList<>();
            List<String> portraitPromptList = new ArrayList<>();
            // PB2: parallel to portraitBytesList, so the composite can bind portrait0/portrait1 to the
            // exact artifact REVISIONS it consumed. §2.5's attribution row — PB1 passes null,null for
            // systemCharacter/userCharacter on every book image, so today nothing records which
            // characters an image actually contains.
            List<BaseRecord> pbPortraitNodes = new ArrayList<>();
            List<BaseRecord> pbPortraitArtifacts = new ArrayList<>();
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
                    ResolvedCharacter rc = resolveSceneCharacter(user, charItem, sceneGroupPath);
                    if (rc == null) continue;
                    BaseRecord cp = rc.charPerson;
                    String cname = rc.name;
                    String portraitPrompt2 = rc.portraitPrompt;

                    // Scene-tagged apparel: pick the highest sceneIndex-tagged outfit <= this
                    // scene's index, flip inuse, and fold its description into the portrait prompt.
                    // Returns false (no-op) for every character/book not using this feature — the
                    // common case, and the existing reuse-cache below must fire exactly as before.
                    boolean hasSceneApparel = selectSceneApparel(user, cp, currentSceneIndex);
                    if (hasSceneApparel) {
                        String outfitDesc = NarrativeUtil.describeOutfit(cp, false);
                        if (outfitDesc != null && !outfitDesc.isBlank()) {
                            portraitPrompt2 = portraitPrompt2 + ", " + outfitDesc;
                        }
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
                    // Bypassed when this character has scene-tagged apparel in use, or the outfit
                    // would never actually change across scenes.
                    if (isBook && !hasSceneApparel && existingPortraitBytes != null && existingPortraitBytes.length > 0) {
                        portraitBytesList.add(existingPortraitBytes);
                        portraitPromptList.add(SWUtil.stripSDXLWeighting(portraitPrompt2));
                        logger.info("Reusing persisted portrait for " + cname + " (no re-render)");
                        if (pbGraph != null) {
                            // A reused portrait produced NOTHING this run, so it must not mint a new
                            // revision — that would make every scene fork the version chain for a
                            // portrait that was demonstrably not regenerated. Record a revision only
                            // when the chain is empty (a portrait that predates the graph), and label
                            // it DONE_UNVERIFIED: we genuinely do not know the config/seed that made
                            // it, and claiming DONE would assert provenance we do not have.
                            try {
                                BaseRecord pNode = PbPipelineUtil.getCreateNode(pbGraph,
                                        PbPipelineUtil.portraitHandle(cp.get(FieldNames.FIELD_OBJECT_ID)),
                                        PbNodeTypeEnumType.PORTRAIT, 20,
                                        PbPipelineUtil.SCOPE_CHARACTER, cp.get(FieldNames.FIELD_OBJECT_ID));
                                PbPipelineUtil.bindRecord(pbGraph, pNode, PbPipelineUtil.ROLE_CHARACTER, 0,
                                        OlioModelNames.MODEL_CHAR_PERSON, cp.get(FieldNames.FIELD_OBJECT_ID));
                                BaseRecord reused = PbArtifactUtil.findSelected(user, pNode, PbPipelineUtil.ROLE_PORTRAIT);
                                if (reused == null) {
                                    // The portrait artifact points at the CHARACTER PROFILE's portrait —
                                    // that is the portrait's canonical home and there is no second copy.
                                    //
                                    // But it must be re-read as a TOP-LEVEL data.data first. The record
                                    // reached through profile.portrait is a nested sub-model, and the query
                                    // planner deliberately restricts fields on sub-models to prevent
                                    // recursion, so what comes back is not fully identified — handing it
                                    // to AccessPoint.create as a foreign reference makes the create return
                                    // null. MEASURED 2026-08-17: the artifact create failed for both
                                    // reused portraits and no artifact row existed at all, while
                                    // persistArtifact's message blamed the unique-revision index (since
                                    // fixed to name both causes).
                                    BaseRecord existingPortrait = (profile != null) ? profile.get("portrait") : null;
                                    BaseRecord portraitRef = readDataRecord(user,
                                            (existingPortrait != null) ? existingPortrait.get(FieldNames.FIELD_OBJECT_ID) : null);
                                    reused = PbPipelineUtil.recordImage(pbGraph, pNode, PbPipelineUtil.ROLE_PORTRAIT,
                                            PbArtifactTypeEnumType.IMAGE, portraitRef, existingPortraitBytes,
                                            "image/png", null, null, null, null);
                                    PbGraphUtil.persistStatus(user, pNode, PbNodeStatusEnumType.DONE_UNVERIFIED);
                                }
                                pbPortraitNodes.add(pNode);
                                pbPortraitArtifacts.add(reused);
                            } catch (Exception pbe) {
                                logger.warn("PB2: failed to record the reused portrait for " + cname + ": " + pbe.getMessage(), pbe);
                            }
                        }
                        continue;
                    }

                    try {
                        // Portrait inherits the common SD config (model, sampler, scheduler) but forces hires=false
                        BaseRecord portCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
                        portCfg.set("steps", steps);
                        portCfg.set("cfg", cfg);
                        portCfg.set("hires", false);
                        portCfg.set("seed", seed);
                        // Style THIS portrait with exactly one style: the character's LOCAL override
                        // (pictureBookMeta.characterStyles, by objectId) if set, else the book GLOBAL
                        // (common) — "global unless overridden locally". buildPortraitDescription strips
                        // the RANDOM style the narrative sdPrompt baked in at creation (which otherwise
                        // stacked on top of the appended book style — double-styled portraits) before
                        // applying the effective one. createImage uses description verbatim and otherwise
                        // BYPASSES getSDConfigPrompt. The composite/landscape/other characters are
                        // unaffected — they stay on `common`. The UI reimage <name>-SD.json config is
                        // untouched (a persisted portrait is reused as-is by the branch above).
                        BaseRecord effStyleCfg = resolveCharacterStyleConfig(user, sceneGroupPath,
                                cp.get(FieldNames.FIELD_OBJECT_ID), common);
                        portCfg.set("description", buildPortraitDescription(portraitPrompt2, effStyleCfg));
                        portCfg.set("negativePrompt", NEG_PROMPT);
                        if (sdModelName != null && !sdModelName.isEmpty()) portCfg.set("model", sdModelName);
                        if (sdSampler != null && !sdSampler.isEmpty()) portCfg.set("sampler", sdSampler);
                        if (sdScheduler != null && !sdScheduler.isEmpty()) portCfg.set("scheduler", sdScheduler);
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

                        if (pbGraph != null) {
                            // A real render: a real new revision, with the config that produced it
                            // frozen as sdConfigSnapshot (§2.5's "one overwriting book config snapshot"
                            // becomes per-artifact). portCfg is the effective portrait config, not the
                            // book's common one — the portrait forces hires=false and carries the
                            // character's own style override.
                            try {
                                BaseRecord pNode = PbPipelineUtil.getCreateNode(pbGraph,
                                        PbPipelineUtil.portraitHandle(cp.get(FieldNames.FIELD_OBJECT_ID)),
                                        PbNodeTypeEnumType.PORTRAIT, 20,
                                        PbPipelineUtil.SCOPE_CHARACTER, cp.get(FieldNames.FIELD_OBJECT_ID));
                                PbPipelineUtil.bindRecord(pbGraph, pNode, PbPipelineUtil.ROLE_CHARACTER, 0,
                                        OlioModelNames.MODEL_CHAR_PERSON, cp.get(FieldNames.FIELD_OBJECT_ID));
                                PbGraphUtil.persistPromptText(user, pNode, portCfg.get("description"));
                                // Re-read as a top-level data.data for the same reason as the reuse branch:
                                // AccessPoint.create returns identity fields only, so the record it handed
                                // back is not a usable foreign reference as-is.
                                BaseRecord portraitRef = readDataRecord(user,
                                        (String) portImages.get(0).get(FieldNames.FIELD_OBJECT_ID));
                                BaseRecord pArt = PbPipelineUtil.recordImage(pbGraph, pNode,
                                        PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
                                        portraitRef, portBytes, "image/png",
                                        Long.valueOf(extractSeedFromImage(portImages.get(0))), portCfg,
                                        JSONUtil.exportObject(portCfg), null);
                                PbPipelineUtil.completeNode(pbGraph, pNode);
                                pbPortraitNodes.add(pNode);
                                pbPortraitArtifacts.add(pArt);
                            } catch (Exception pbe) {
                                logger.warn("PB2: failed to record the portrait for " + cname + ": " + pbe.getMessage(), pbe);
                            }
                        }

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
                                    BaseRecord newProfile = PbSubRecordUtil.createSubRecord(user, null, ModelNames.MODEL_PROFILE);
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
            if (!portraitBytesList.isEmpty()) {
                // Only cool down if Stage 1 actually did GPU work — nothing to recover from if
                // zero portraits were generated (the common case for landscape-only scenes).
                stageCooldown();
            }

            // Stage 2: Landscape generation — prompt was already resolved (LLM or cache) in Stage 0
            // above, before the model was unloaded, so this is pure SD work.
            PictureBookProgressNotifier.getInstance().notifyProgress(user, "landscape", "Generating landscape...");
            SWTxt2Img landReq = SWUtil.newSceneTxt2Img(landscapePrompt, NEG_PROMPT, common);
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

            // PB2 Stage 2: the LANDSCAPE node, bound to the landscape-prompt node's artifact so a
            // prompt change propagates. The forced 1024x768 here is exactly what §9's level-1
            // dimension assertion checks against — recordImage measures the DECODED bytes, not the
            // request, so a hires/refiner pass that silently returns another size is caught.
            BaseRecord pbLandscapeNode = null;
            BaseRecord pbLandscapeArtifact = null;
            if (pbGraph != null) {
                try {
                    pbLandscapeNode = PbPipelineUtil.getCreateNode(pbGraph,
                            PbPipelineUtil.landscapeHandle(sceneObjectId),
                            PbNodeTypeEnumType.LANDSCAPE, 30, PbPipelineUtil.SCOPE_SCENE, sceneObjectId);
                    BaseRecord lpNode = pbGraph.node(PbPipelineUtil.landscapePromptHandle(sceneObjectId));
                    if (lpNode != null) {
                        PbPipelineUtil.bindNode(pbGraph, pbLandscapeNode, PbPipelineUtil.ROLE_PROMPT, 0, lpNode,
                                PbArtifactUtil.findSelected(user, lpNode, PbPipelineUtil.ROLE_LANDSCAPE_PROMPT));
                    }
                    pbLandscapeArtifact = PbPipelineUtil.recordImage(pbGraph, pbLandscapeNode,
                            PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE, landscapeImage,
                            landscapeBytes, "image/png",
                            Long.valueOf(extractSeedFromImage(landscapeImage)), common,
                            JSONUtil.exportObject(landReq), null);
                    PbPipelineUtil.completeNode(pbGraph, pbLandscapeNode);
                } catch (Exception pbe) {
                    logger.warn("PB2: failed to record the landscape node: " + pbe.getMessage(), pbe);
                }
            }

            // Landscape generation is a full hires/refiner pass when enabled — let the GPU
            // recover before the composite stage, which is heavier still (img2img on top of its
            // own base+refiner pass) and runs immediately after with zero gap otherwise. This is
            // the specific back-to-back sequence implicated in a real thermal-critical event.
            stageCooldown();

            // Stage 3/4: Composite scene — branch between Kontext (stitch-and-prompt) and classic
            // (Graphics2D composite + SDXL img2img) pipelines, driven by the common config's
            // useKontext field. When the config leaves it unset the fallback is the CLASSIC pipeline —
            // live E2E visual comparison (TestPictureBookUtilE2E diagnostic run, see git history)
            // showed Kontext reliably returns a technically-valid image that does NOT preserve
            // character likeness (wrong hair color/face — Kontext "succeeds" so the
            // empty-result-only fallback below never triggers), while the classic pipeline's
            // Graphics2D-drawn real portrait pixels visibly preserve likeness (confirmed by
            // Stephen + coordinator independently inspecting the emitted composites/portraits).
            // Kontext stays available as an explicit opt-in (config useKontext=true) when likeness
            // fidelity matters less.
            String leftDesc  = !portraitPromptList.isEmpty() ? portraitPromptList.get(0) : "";
            String rightDesc = portraitPromptList.size() > 1  ? portraitPromptList.get(1) : "";
            byte[] leftBytes   = !portraitBytesList.isEmpty() ? portraitBytesList.get(0) : null;
            byte[] centerBytes = portraitBytesList.size() > 1  ? portraitBytesList.get(1) : null;

            // compositeMode supersedes the legacy useKontext boolean. When unset we fall back to it,
            // so existing book configs behave exactly as before.
            String compositeMode = common.get("compositeMode");
            Boolean useKontextV = common.get("useKontext");
            boolean useKontext = (useKontextV != null) ? useKontextV.booleanValue() : false;
            boolean useFlux2 = false;
            if (compositeMode != null && !compositeMode.isBlank()) {
                String mode = compositeMode.trim().toLowerCase();
                useFlux2 = "flux2".equals(mode);
                useKontext = "kontext".equals(mode);
                if (!useFlux2 && !useKontext && !"classic".equals(mode)) {
                    logger.warn("generateSceneImage: unrecognized compositeMode '" + compositeMode
                        + "' — expected flux2|kontext|classic; falling back to classic");
                }
            }
            // Kontext 2-pass needs moderate creativity — enough to restructure panels while
            // preserving faces; classic img2img needs more room to blend the drawn-on portraits.
            double sceneCreativity = useKontext ? 0.65 : 0.85;
            Double sceneCreativityV = common.get("sceneCreativity");
            if (sceneCreativityV != null) sceneCreativity = sceneCreativityV.doubleValue();

            String sceneName = "scene_" + sceneObjectId + "_" + System.currentTimeMillis();
            List<BaseRecord> finalImages = new ArrayList<>();

            // PB2 Stage 3/4 nodes. The REFERENCE node is created for all three composite modes
            // because all three build references — they just differ in shape (letterboxed separates,
            // a stitched strip, or a Graphics2D canvas), which is what artifactType records.
            // referenceArtifactOids feeds sanitizeGeneratorRequest: the base64 payloads are REPLACED
            // by these objectIds, which is what makes the persisted request both small and readable.
            BaseRecord pbReferenceNode = null;
            BaseRecord pbCompositeNode = null;
            List<String> pbReferenceArtifactOids = new ArrayList<>();
            // The request ACTUALLY sent, per branch. Captured as a local rather than re-derived, because
            // the three branches build genuinely different requests and re-deriving one would record a
            // request that was never sent — which is the exact class of dishonesty §9 is guarding against.
            String pbCompositeRequestJson = null;
            BaseRecord pbCompositeSnapshot = common;
            if (pbGraph != null) {
                try {
                    pbReferenceNode = PbPipelineUtil.getCreateNode(pbGraph,
                            PbPipelineUtil.referenceHandle(sceneObjectId),
                            PbNodeTypeEnumType.REFERENCE_STRIP, 40, PbPipelineUtil.SCOPE_SCENE, sceneObjectId);
                    pbCompositeNode = PbPipelineUtil.getCreateNode(pbGraph,
                            PbPipelineUtil.compositeHandle(sceneObjectId),
                            PbNodeTypeEnumType.COMPOSITE, 50, PbPipelineUtil.SCOPE_SCENE, sceneObjectId);
                    // The reference node consumes the portraits and the landscape.
                    for (int pi = 0; pi < pbPortraitNodes.size() && pi < 2; pi++) {
                        PbPipelineUtil.bindNode(pbGraph, pbReferenceNode, PbPipelineUtil.portraitRole(pi), 0,
                                pbPortraitNodes.get(pi), pbPortraitArtifacts.get(pi));
                    }
                    if (pbLandscapeNode != null) {
                        PbPipelineUtil.bindNode(pbGraph, pbReferenceNode, PbPipelineUtil.ROLE_LANDSCAPE, 0,
                                pbLandscapeNode, pbLandscapeArtifact);
                    }
                } catch (Exception pbe) {
                    logger.warn("PB2: failed to create the reference/composite nodes: " + pbe.getMessage(), pbe);
                    pbReferenceNode = null;
                    pbCompositeNode = null;
                }
            }

            if (useFlux2) {
                // FLUX.2 MULTI-REFERENCE PIPELINE. Three deliberate departures from the Kontext path,
                // each fixing something observed in media/flux/bad.composite.png:
                //  - references stay SEPARATE (people, then setting) instead of being stitched into
                //    one wide panel strip, which the model read as a picture and drew into the scene
                //    as a propped-up board;
                //  - references are letterboxed, not center-cropped, so a 1024x768 landscape keeps
                //    all of its width (stitchSceneImages would have discarded 44% of it);
                //  - CFG comes from flux2Cfg (2.5), NOT the SDXL `cfg` (5) that the Kontext call was
                //    being handed — far outside the 1.0-3.5 an edit model tolerates.
                // Request construction goes through SceneCompositeUtil - the SAME builder the chat
                // endpoint uses - rather than being assembled inline here. It was inline, and that
                // duplication immediately bit: flux2IncludeLandscapeRef was added to the shared builder
                // only, so this branch passed the landscape unconditionally and the config field was
                // silently ignored for every picture-book scene while appearing to work.
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome_mosaic", "Preparing references...");
                BaseRecord flux2Cfg = (params.compositeSdConfig != null) ? params.compositeSdConfig : common;
                if (params.compositeSdConfig != null) SDUtil.fillStyleDefaults(flux2Cfg);

                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Compositing scene...");
                SWTxt2Img flux2Req = SceneCompositeUtil.buildSceneRequest(SceneCompositeUtil.MODE_FLUX2,
                        leftDesc, rightDesc, action, setting, mood, scenePrompt, NEG_PROMPT,
                        leftBytes, centerBytes, landscapeBytes, sceneCreativity, flux2Cfg);
                if (flux2Req == null) {
                    logger.warn("generateSceneImage: could not build a FLUX.2 request — falling back to classic");
                    useFlux2 = false;
                }
                // PB2 §2.5: the FLUX.2 letterboxed references exist ONLY as base64 inside the request
                // today — nothing persists them, so there is no way to see what the model was actually
                // shown. Persist each as an IMAGE artifact on the reference node and keep its objectId,
                // which then REPLACES the base64 in the stored generatorRequest.
                if (pbGraph != null && pbReferenceNode != null && flux2Req != null) {
                    try {
                        pbReferenceArtifactOids.addAll(recordFlux2References(pbGraph, pbReferenceNode,
                                flux2Req.getPromptImages(), flux2Cfg, sceneObjectId));
                        PbPipelineUtil.completeNode(pbGraph, pbReferenceNode);
                    } catch (Exception pbe) {
                        logger.warn("PB2: failed to record the FLUX.2 references: " + pbe.getMessage(), pbe);
                    }
                }
                if (flux2Req != null) {
                    pbCompositeRequestJson = JSONUtil.exportObject(flux2Req);
                    pbCompositeSnapshot = flux2Cfg;
                }
                finalImages = (flux2Req != null)
                    ? sdu.createSceneImage(user, sceneGroupPath, sceneName, flux2Req, null, null)
                    : new ArrayList<>();
                if (finalImages == null || finalImages.isEmpty()) {
                    logger.warn("generateSceneImage: FLUX.2 pipeline produced no images — falling back to classic");
                    useFlux2 = false;
                }
            }

            if (useKontext) {
                // KONTEXT PIPELINE: stitch [portrait1 | portrait2 | landscape] into one composite
                // reference image, hand it to Flux Kontext as a single promptImage.
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "auto_awesome_mosaic", "Stitching reference...");
                byte[] stitchLeft   = leftBytes != null ? leftBytes : landscapeBytes;
                byte[] stitchCenter = centerBytes != null ? centerBytes : landscapeBytes;
                byte[] refComposite = SDUtil.stitchSceneImages(stitchLeft, stitchCenter, landscapeBytes, 1024);

                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Compositing scene...");
                // Composite uses the optional ALTERNATE config (compositeSdConfig) if supplied, else
                // the common config. useConfigStyle=true means the style suffix is derived from
                // getSDConfigPrompt (FLUX-stripped) — the SAME canonical style as portraits/landscape —
                // not the legacy styleClause. fillStyleDefaults keeps a sparse alternate config
                // producing a full style string. steps/cfg/negative-prompt are threaded through so
                // Kontext respects them like every other stage.
                BaseRecord kontextCfg = (params.compositeSdConfig != null) ? params.compositeSdConfig : common;
                if (params.compositeSdConfig != null) SDUtil.fillStyleDefaults(kontextCfg);
                SWTxt2Img kontextReq = SWUtil.newKontextSceneTxt2Img(leftDesc, rightDesc, action, setting, null, mood,
                        kontextCfg, steps, cfg, NEG_PROMPT, true);
                if (refComposite != null) {
                    List<String> promptImages = new ArrayList<>();
                    promptImages.add("data:image/png;base64," + Base64.getEncoder().encodeToString(refComposite));
                    kontextReq.setPromptImages(promptImages);
                }
                // PB2 §2.5: the Kontext stitched strip was a ./land-*.png-class throwaway. Persist it as
                // an IMAGE_STRIP artifact — it is the single reference the model sees in this mode, so
                // without it a bad composite cannot be diagnosed.
                if (pbGraph != null && pbReferenceNode != null && refComposite != null) {
                    try {
                        BaseRecord stripData = PbPipelineUtil.persistBytes(pbGraph,
                                "reference_strip_" + sceneObjectId + "_" + System.currentTimeMillis(),
                                refComposite, "image/png");
                        BaseRecord stripArt = PbPipelineUtil.recordImage(pbGraph, pbReferenceNode,
                                PbPipelineUtil.ROLE_REFERENCE_STRIP, PbArtifactTypeEnumType.IMAGE_STRIP,
                                stripData, refComposite, "image/png", null, kontextCfg, null, null);
                        if (stripArt != null) {
                            pbReferenceArtifactOids.add((String) stripArt.get(FieldNames.FIELD_OBJECT_ID));
                        }
                        PbPipelineUtil.completeNode(pbGraph, pbReferenceNode);
                    } catch (Exception pbe) {
                        logger.warn("PB2: failed to record the Kontext reference strip: " + pbe.getMessage(), pbe);
                    }
                }
                pbCompositeRequestJson = JSONUtil.exportObject(kontextReq);
                pbCompositeSnapshot = kontextCfg;
                finalImages = sdu.createSceneImage(user, sceneGroupPath, sceneName, kontextReq, null, null);
                if (finalImages == null || finalImages.isEmpty()) {
                    logger.warn("generateSceneImage: Kontext pipeline produced no images — falling back to classic");
                    useKontext = false;
                }
            }

            if (!useKontext && !useFlux2) {
                // CLASSIC PIPELINE: literally draw the real portrait pixels onto the landscape
                // canvas via Graphics2D (SDUtil.compositeSceneCanvas), then run SDXL img2img at a
                // controlled creativity/denoise strength — the real portrait pixels are physically
                // present in the input before refinement, which is what actually preserves identity.
                PictureBookProgressNotifier.getInstance().notifyProgress(user, "image", "Compositing scene...");
                // Resolved once in Stage 0 (LLM-generated SD tag-style prompt via
                // pictureBook.scene-image-prompt, with its own raw-concatenation fallback) — no
                // longer a hand-built narrative-sentence StringBuilder here.
                SWTxt2Img classicReq = SWUtil.newSceneTxt2Img(scenePrompt, NEG_PROMPT, common);
                logger.info("generateSceneImage: requesting composite canvas at " + classicReq.getWidth() + "x" + classicReq.getHeight()
                        + " (landscapeBytes=" + (landscapeBytes != null ? landscapeBytes.length : 0)
                        + " leftBytes=" + (leftBytes != null ? leftBytes.length : 0)
                        + " centerBytes=" + (centerBytes != null ? centerBytes.length : 0) + ")");
                byte[] compositeBytes = SDUtil.compositeSceneCanvas(landscapeBytes, leftBytes, centerBytes,
                        classicReq.getWidth(), classicReq.getHeight());
                if (compositeBytes != null) {
                    // PB2 §2.5: these two lines were the whole persistence story for the composite
                    // canvas and the landscape — a debug dump into the process working directory, where
                    // nothing found them, nothing cleaned them up and the product could not show them.
                    // The canvas matters more than the name "debug dump" suggests: it is the image the
                    // real portrait PIXELS are drawn onto, which is what actually preserves likeness, so
                    // it is the one artifact that explains a composite. Under v2 it becomes a
                    // COMPOSITE_CANVAS artifact in the world's Gallery. The emitFile calls stay on the
                    // v2-off path so flag-off behaviour is unchanged.
                    if (pbGraph != null && pbReferenceNode != null) {
                        try {
                            BaseRecord canvasData = PbPipelineUtil.persistBytes(pbGraph,
                                    "composite_canvas_" + sceneObjectId + "_" + System.currentTimeMillis(),
                                    compositeBytes, "image/png");
                            BaseRecord canvasArt = PbPipelineUtil.recordImage(pbGraph, pbReferenceNode,
                                    PbPipelineUtil.ROLE_COMPOSITE_CANVAS, PbArtifactTypeEnumType.COMPOSITE_CANVAS,
                                    canvasData, compositeBytes, "image/png", null, common, null, null);
                            if (canvasArt != null) {
                                pbReferenceArtifactOids.add((String) canvasArt.get(FieldNames.FIELD_OBJECT_ID));
                            }
                            PbPipelineUtil.completeNode(pbGraph, pbReferenceNode);
                        } catch (Exception pbe) {
                            logger.warn("PB2: failed to record the classic composite canvas: " + pbe.getMessage(), pbe);
                        }
                    } else {
                        FileUtil.emitFile("./comp-" + sceneObjectId + ".png", compositeBytes);
                        FileUtil.emitFile("./land-" + sceneObjectId + ".png", landscapeBytes);
                    }
                    classicReq.setInitImage("data:image/png;base64," + Base64.getEncoder().encodeToString(compositeBytes));
                    classicReq.setInitImageCreativity(sceneCreativity);
                }
                pbCompositeRequestJson = JSONUtil.exportObject(classicReq);
                pbCompositeSnapshot = common;
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
            // ══════════════════════════════════════════════════════════════════
            // PB2 Stage 4 — the COMPOSITE node, its bindings, and the run
            // ══════════════════════════════════════════════════════════════════
            // This is where §2.5's attribution row is actually satisfied: portrait0/portrait1 bindings
            // name the characters in the image, where PB1 passes null,null. The generatorRequest is
            // sanitized on the way in — the base64 references are replaced by the reference artifacts'
            // objectIds and the Swarm session_id is stripped.
            if (pbGraph != null && pbCompositeNode != null) {
                try {
                    for (int pi = 0; pi < pbPortraitNodes.size() && pi < 2; pi++) {
                        PbPipelineUtil.bindNode(pbGraph, pbCompositeNode, PbPipelineUtil.portraitRole(pi), 0,
                                pbPortraitNodes.get(pi), pbPortraitArtifacts.get(pi));
                    }
                    if (pbLandscapeNode != null) {
                        PbPipelineUtil.bindNode(pbGraph, pbCompositeNode, PbPipelineUtil.ROLE_LANDSCAPE, 0,
                                pbLandscapeNode, pbLandscapeArtifact);
                    }
                    BaseRecord spNode = pbGraph.node(PbPipelineUtil.scenePromptHandle(sceneObjectId));
                    if (spNode != null) {
                        PbPipelineUtil.bindNode(pbGraph, pbCompositeNode, PbPipelineUtil.ROLE_PROMPT, 0, spNode,
                                PbArtifactUtil.findSelected(user, spNode, PbPipelineUtil.ROLE_SCENE_PROMPT));
                    }
                    if (pbReferenceNode != null && !pbReferenceArtifactOids.isEmpty()) {
                        PbPipelineUtil.bindNode(pbGraph, pbCompositeNode, PbPipelineUtil.ROLE_REFERENCE_STRIP, 0,
                                pbReferenceNode, null);
                    }
                    PbGraphUtil.persistPromptText(user, pbCompositeNode, scenePrompt);
                    // Must go through ByteModelUtil — a raw .get() bypasses decompression/decryption.
                    byte[] finalBytes = ByteModelUtil.getValue(finalImage);
                    PbPipelineUtil.recordImage(pbGraph, pbCompositeNode, PbPipelineUtil.ROLE_COMPOSITE,
                            PbArtifactTypeEnumType.IMAGE, finalImage, finalBytes, "image/png",
                            Long.valueOf(extractSeedFromImage(finalImage)), pbCompositeSnapshot,
                            pbCompositeRequestJson, pbReferenceArtifactOids);
                    PbPipelineUtil.completeNode(pbGraph, pbCompositeNode);
                    PbPipelineUtil.dualWriteScene(pbGraph, (String) sceneData.get("title"), setting, action, mood,
                            (String) sceneData.get("blurb"));
                    PbPipelineUtil.closeRun(pbGraph, true, null);
                } catch (Exception pbe) {
                    logger.warn("PB2: failed to record the composite node: " + pbe.getMessage(), pbe);
                    PbPipelineUtil.closeRun(pbGraph, false, pbe.getMessage());
                }
            }

            BaseRecord genResult = buildResult();
            genResult.set("imageObjectId", finalImageOid);
            // B9: report the actual prompt sent to SD (the Stage-0 resolved scene prompt), not a
            // throwaway action+setting reconstruction that never reflected what SD received.
            genResult.set("prompt", scenePrompt);
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
     * PB2 §2.5: persist each FLUX.2 letterboxed reference as its own {@code IMAGE} artifact and return
     * their objectIds.
     * <p>
     * The references exist <b>only</b> as base64 data URLs inside the request today, so there is no way
     * to see what the model was actually shown - and the FLUX.2 path is precisely the one where a wrong
     * reference (a center-cropped landscape, a propped-up board) is the observed failure mode. The
     * returned objectIds replace the base64 in the persisted {@code generatorRequest}.
     * <p>
     * Ordinal order is request order, so {@code referenceArtifactObjectIds[i]} corresponds to
     * {@code promptImages[i]} - which is what makes the stored request readable rather than merely small.
     */
    private static List<String> recordFlux2References(PbPipelineUtil.SceneGraph graph, BaseRecord referenceNode,
            List<String> promptImages, BaseRecord snapshotCfg, String sceneObjectId) {
        List<String> oids = new ArrayList<>();
        if (promptImages == null || promptImages.isEmpty()) {
            logger.warn("PB2: the FLUX.2 request carried no promptImages — nothing to record as references");
            return oids;
        }
        for (int i = 0; i < promptImages.size(); i++) {
            String dataUrl = promptImages.get(i);
            if (dataUrl == null) continue;
            int comma = dataUrl.indexOf(',');
            String b64 = (comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl);
            byte[] refBytes;
            try {
                refBytes = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException iae) {
                logger.warn("PB2: FLUX.2 reference " + i + " is not decodable base64: " + iae.getMessage());
                continue;
            }
            BaseRecord refData = PbPipelineUtil.persistBytes(graph,
                    "flux2_reference_" + i + "_" + sceneObjectId + "_" + System.currentTimeMillis(),
                    refBytes, "image/png");
            BaseRecord art = PbPipelineUtil.recordImage(graph, referenceNode,
                    PbPipelineUtil.ROLE_REFERENCE_STRIP + i, PbArtifactTypeEnumType.IMAGE, refData, refBytes,
                    "image/png", null, snapshotCfg, null, null);
            if (art != null) {
                oids.add((String) art.get(FieldNames.FIELD_OBJECT_ID));
            }
        }
        return oids;
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
            String chatConfigName, BaseRecord sdConfig, String promptTemplateOverride) {
        prepareSceneImagePrompts(user, sceneObjectIds, chatConfigName, sdConfig, promptTemplateOverride, null);
    }

    /**
     * KI-10 overload: same as
     * {@link #prepareSceneImagePrompts(BaseRecord, List, String, BaseRecord, String)}, plus an
     * optional {@code cancelToken} (mirrors {@code SummarizeProgress}'s use in {@code ChatUtil}'s
     * map/reduce loops) checked at the top of the per-scene loop — a mid-batch cancel (POST
     * /{bookObjectId}/cancel) stops making further per-scene LLM calls immediately. Scenes already
     * processed keep their resolved/cached prompts; unprocessed scenes are left to fall back to
     * their setting text at generation time (same as any other per-scene LLM failure).
     */
    public static void prepareSceneImagePrompts(BaseRecord user, List<String> sceneObjectIds,
            String chatConfigName, BaseRecord sdConfig, String promptTemplateOverride, SummarizeProgress cancelToken) {
        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }
        // The common olio.sd.config is the single style seam (getSDConfigPrompt). Fill its per-style
        // detail fields so the style suffix baked into each cached prompt here matches what
        // generateSceneImage will produce. Null is tolerated (getSDConfigPrompt falls back).
        if (sdConfig != null) SDUtil.fillStyleDefaults(sdConfig);
        for (String sceneObjectId : sceneObjectIds) {
            if (cancelToken != null && cancelToken.isCancelled()) {
                logger.info("prepareSceneImagePrompts: cancelled — stopping before scene " + sceneObjectId
                        + " (" + sceneObjectIds.indexOf(sceneObjectId) + "/" + sceneObjectIds.size() + " already processed)");
                break;
            }
            BaseRecord scene = null;
            try {
                Query sq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
                sq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
                sq.planMost(false);
                scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
            } catch (Exception e) {
                logger.warn("prepareSceneImagePrompts: failed to resolve scene " + sceneObjectId + ": " + e.getMessage());
                continue;
            }
            if (scene == null) {
                /// Unchanged tolerance: an id the client no longer has (deleted scene) skips the
                /// batch entry rather than failing the whole call.
                logger.warn("prepareSceneImagePrompts: scene not found: " + sceneObjectId);
                continue;
            }
            /// Deliberately OUTSIDE the per-scene try/catch below: that catch exists to tolerate
            /// LLM/prompt failures, and swallowing an authorization denial there would turn "you
            /// may not act on this book" into a silent 200 with nothing done. A 403 must abort.
            authorizeSceneRecord(user, scene, SceneAccessType.WRITE);
            try {
                String sceneText = scene.get("text");
                Map<String, Object> sceneData = sceneText != null ? parseLlmJsonObject(sceneText) : new LinkedHashMap<>();
                String setting = (String) sceneData.getOrDefault("setting", "");
                String action = (String) sceneData.getOrDefault("action", "");
                String mood = (String) sceneData.getOrDefault("mood", "");
                resolveLandscapePrompt(user, scene, chatConfig, setting, mood, sdConfig, promptTemplateOverride);

                String sceneGroupPath = scene.get(FieldNames.FIELD_GROUP_PATH);
                if (sceneGroupPath == null) sceneGroupPath = "~/Chat";
                Object charsObj = sceneData.get("characters");
                List<String> charNarrations = new ArrayList<>();
                if (charsObj instanceof List) {
                    for (Object charItem : (List<Object>) charsObj) {
                        if (charNarrations.size() >= 2) break;
                        ResolvedCharacter rc = resolveSceneCharacter(user, charItem, sceneGroupPath);
                        if (rc != null) charNarrations.add(rc.name + ": " + SWUtil.stripSDXLWeighting(rc.sceneNarration));
                    }
                }
                resolveScenePrompt(user, scene, chatConfig, action, setting, mood, sdConfig, charNarrations, promptTemplateOverride);
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
        /// Resolves the scene AND authorizes the caller against the book that owns it, before any
        /// LLM call is made.
        BaseRecord scene = authorizeSceneAccess(user, sceneObjectId, SceneAccessType.WRITE);

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
     * List a book's extracted characters (for the "Manage Characters" review/edit screen) —
     * objectId/name/gender/hasPortrait/apparelCount/per-apparel scene tags, plus failedApparel/
     * failedStatistics flags cross-referenced from the book's own meta (set during
     * extract()/createFromScenes() when createCharPerson's best-effort steps fail).
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listCharacters(BaseRecord user, String bookObjectId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        String charsGroupPath = bookGroupPath + "/Characters";
        BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(),
                (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
        if (charsGroup == null) return new ArrayList<>();

        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(q);

        Set<String> failedApparel = new HashSet<>();
        Set<String> failedStatistics = new HashSet<>();
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec != null) {
            try {
                String metaJson = metaRec.get("text");
                if (metaJson != null && !metaJson.isEmpty()) {
                    Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
                    Object fa = meta.get("failedApparel");
                    if (fa instanceof List) for (Object o : (List<Object>) fa) failedApparel.add(String.valueOf(o));
                    Object fs = meta.get("failedStatistics");
                    if (fs instanceof List) for (Object o : (List<Object>) fs) failedStatistics.add(String.valueOf(o));
                }
            } catch (Exception e) {
                logger.warn("Failed to read failedApparel/failedStatistics from meta: " + e.getMessage());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (BaseRecord cp : chars) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String cname = cp.get(FieldNames.FIELD_NAME);
            entry.put("objectId", cp.get(FieldNames.FIELD_OBJECT_ID));
            entry.put("name", cname);
            entry.put("gender", cp.get("gender"));
            BaseRecord profile = cp.get("profile");
            entry.put("hasPortrait", profile != null && profile.get("portrait") != null);
            BaseRecord store = cp.get(FieldNames.FIELD_STORE);
            List<BaseRecord> appl = (store != null) ? store.get(OlioFieldNames.FIELD_APPAREL) : null;
            entry.put("apparelCount", appl != null ? appl.size() : 0);
            List<Map<String, Object>> sceneTags = new ArrayList<>();
            if (appl != null) {
                for (BaseRecord a : appl) {
                    try {
                        IOSystem.getActiveContext().getReader().populate(a, new String[] { FieldNames.FIELD_ATTRIBUTES });
                        Integer si = AttributeUtil.getAttributeValue(a, "sceneIndex", null);
                        Map<String, Object> tag = new LinkedHashMap<>();
                        tag.put("apparelObjectId", a.get(FieldNames.FIELD_OBJECT_ID));
                        tag.put("sceneIndex", si);
                        tag.put("inuse", a.get(OlioFieldNames.FIELD_IN_USE));
                        sceneTags.add(tag);
                    } catch (Exception e) {
                        logger.warn("Failed to read apparel scene tag: " + e.getMessage());
                    }
                }
            }
            entry.put("sceneTags", sceneTags);
            entry.put("failedApparel", failedApparel.contains(cname));
            entry.put("failedStatistics", failedStatistics.contains(cname));
            result.add(entry);
        }
        return result;
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
     * Explicit child deletion — AccessPoint.delete on a group does NOT cascade — so
     * {@link #deleteGroupRecursive(BaseRecord, BaseRecord)} walks and deletes every record nested
     * under Scenes/Characters bottom-up before either sub-group (and, subsequently, the book group
     * itself) is deleted. See KI-32: previously this method deleted exactly 4 top-level rows and
     * left everything nested underneath (scenes, characters, generated images, nested subgroups)
     * orphaned, which surfaced later as {@code PathProvider} "Parent auth.group index not found"
     * log spam for any surviving record whose parentId chain climbed through one of the deleted-out
     * -from-under-it intermediate groups.
     *
     * <p>Also deletes each character's own foreign single-model sub-records (profile, narrative,
     * statistics, store, instinct, personality, state — see {@code createPersistedForeignInstance}),
     * which are persisted under the acting user's own shared {@code ~/Profiles}/{@code ~/Narratives}
     * /etc. buckets rather than grouped under the book's Characters subtree — this group-subtree
     * walk would otherwise never reach them (closed 2026-07-23, previously a documented gap here).
     */
    public static boolean reset(BaseRecord user, String bookObjectId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");

        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        boolean ok = true;

        // Recursively delete sub-groups (Scenes/, Characters/) and everything nested under them
        for (String sub : new String[]{"Scenes", "Characters"}) {
            String subPath = bookGroupPath + "/" + sub;
            BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                    ModelNames.MODEL_GROUP, subPath, GroupEnumType.DATA.toString(),
                    (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
            if (grp != null) {
                try {
                    if (!deleteGroupRecursive(user, grp)) ok = false;
                } catch (Exception e) {
                    logger.warn("Failed to recursively delete " + sub + " group: " + e.getMessage());
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

    /**
     * Recursively delete a group's contents bottom-up, then the group itself — entirely through
     * {@code AccessPoint} (PBAC-respecting) per-record deletes, never the PBAC-bypassing raw
     * {@code writer.delete(query)} pattern used elsewhere (e.g. {@code WorldUtil.cleanupWorld}),
     * since this is a user-invoked action that must still respect ownership/authorization. See
     * KI-32. Order: nested {@code auth.group} subgroups first (deepest-first, recursively), then
     * {@code data.note} children (scene notes), then {@code olio.charPerson} children, then any
     * {@code data.data} children (generated images grouped directly here), then the group itself.
     */
    private static boolean deleteGroupRecursive(BaseRecord user, BaseRecord group) {
        boolean ok = true;
        long groupId = group.get(FieldNames.FIELD_ID);
        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

        // 1. Recurse into nested auth.group subgroups first (deepest-first)
        Query subQ = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, groupId);
        subQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        BaseRecord[] subGroups = IOSystem.getActiveContext().getSearch().findRecords(subQ);
        for (BaseRecord sg : subGroups) {
            if (!deleteGroupRecursive(user, sg)) ok = false;
        }

        // 2. Delete data.note children (e.g. scene notes)
        Query noteQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, groupId);
        noteQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        BaseRecord[] notes = IOSystem.getActiveContext().getSearch().findRecords(noteQ);
        for (BaseRecord n : notes) {
            try {
                IOSystem.getActiveContext().getAccessPoint().delete(user, n);
            } catch (Exception e) {
                logger.warn("Failed to delete note " + n.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
                ok = false;
            }
        }

        // 3. Delete olio.charPerson children, and each character's own dedicated foreign
        // sub-records (profile/narrative/statistics/store/instinct/personality/state — see
        // createPersistedForeignInstance) first. Those live in the acting user's shared
        // ~/Profiles, ~/Narratives, etc. buckets, not grouped under this book's Characters
        // subtree, so this group-subtree walk would otherwise never reach them (the KI-32
        // follow-up gap this closes). Each is created fresh, once, per character —
        // createPersistedForeignInstance is never called with a shared/reused instance — so
        // deleting them alongside their owning character cannot orphan another character's data.
        String[] charForeignFields = new String[] {
                "profile", "narrative", OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE,
                OlioFieldNames.FIELD_INSTINCT, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
        };
        Query charQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, groupId);
        charQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        List<String> charRequest = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID));
        charRequest.addAll(Arrays.asList(charForeignFields));
        charQ.setRequest(charRequest.toArray(new String[0]));
        BaseRecord[] chars = IOSystem.getActiveContext().getSearch().findRecords(charQ);
        for (BaseRecord cp : chars) {
            for (String foreignField : charForeignFields) {
                try {
                    BaseRecord fk = cp.get(foreignField);
                    Long fkId = (fk != null) ? fk.get(FieldNames.FIELD_ID) : null;
                    if (fkId != null && fkId > 0L) {
                        IOSystem.getActiveContext().getAccessPoint().delete(user, fk);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to delete character " + cp.get(FieldNames.FIELD_OBJECT_ID) + "'s " + foreignField + ": " + e.getMessage());
                    ok = false;
                }
            }
            try {
                IOSystem.getActiveContext().getAccessPoint().delete(user, cp);
            } catch (Exception e) {
                logger.warn("Failed to delete character " + cp.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
                ok = false;
            }
        }

        // 4. Delete data.data children (generated portraits/landscapes/composites grouped directly
        // here, as opposed to a charPerson's own foreign store/profile records — see reset()'s note)
        Query dataQ = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
        dataQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        BaseRecord[] datas = IOSystem.getActiveContext().getSearch().findRecords(dataQ);
        for (BaseRecord d : datas) {
            try {
                IOSystem.getActiveContext().getAccessPoint().delete(user, d);
            } catch (Exception e) {
                logger.warn("Failed to delete data " + d.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
                ok = false;
            }
        }

        // 5. Finally delete the group itself
        try {
            IOSystem.getActiveContext().getAccessPoint().delete(user, group);
        } catch (Exception e) {
            logger.warn("Failed to delete group " + ((String) group.get(FieldNames.FIELD_PATH)) + ": " + e.getMessage());
            ok = false;
        }
        return ok;
    }
}
