package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
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
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.ColorUtil;
import org.cote.accountmanager.olio.EthnicityEnumType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.WorldUtil;
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
import org.cote.accountmanager.util.CryptoUtil;
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
    /// The book meta note's name. It lives in the same Scenes-sibling group walk as the scene notes,
    /// so anything iterating notes has to skip it by name.
    static final String META_NOTE_NAME = ".pictureBookMeta";
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

    // ── Character name canonicalisation (issue 3: duplicate unnamed characters) ──────────
    //
    // createFromScenes de-duplicated scene characters on the EXACT name string
    // (uniqueChars.containsKey(cname)), so an unnamed character the extraction refers to differently
    // in different chunks became several charPersons: "Darby's dad", "Darby's Dad", "the father" and
    // "Dad" are four characters, each with its own portrait, statistics and wardrobe. That is
    // reported issue 3, and it is worse than cosmetic: scene notes pin characters BY NAME, so the
    // scenes split across the duplicates too.
    //
    // Two halves, and this is the cheap mechanical one - normalise the spellings that are
    // unambiguously the same person. The judgement calls are left to mergeCharacters().

    /// Leading articles, dropped so "the guard" and "Guard" are one key.
    /// {@code (?:\s+|$)}, not {@code \s+}: a name that is NOTHING but an article must key to empty
    /// so callers drop it, rather than surviving as a character literally called "the".
    private static final Pattern NAME_ARTICLES = Pattern.compile("^(?:the|a|an)(?:\\s+|$)");

    /// Honorifics, dropped so "Mr. Smith" and "Smith" are one key. "Father"/"Sister" are
    /// deliberately ABSENT: they are also kinship words, and dropping them would fold the priest
    /// "Father Brown" into a character called "Brown".
    private static final Pattern NAME_HONORIFICS = Pattern.compile(
            "^(?:mr|mrs|ms|miss|dr|doctor|prof|professor|sir|madam|madame|lord|lady|"
            + "capt|captain|sgt|sergeant|lt|lieutenant|officer|const|constable|rev|reverend)(?:\\s+|$)");

    /**
     * Kinship and role synonyms, mapped to one canonical word. An unnamed character is referred to
     * by relation, and the relation word is exactly what varies between chunks.
     *
     * <p>Only unambiguous synonyms. "pop" maps to father but "popper" must not, which is why the
     * lookup is whole-token rather than substring.
     */
    private static final Map<String, String> NAME_RELATION_SYNONYMS = nameRelationSynonyms();

    private static Map<String, String> nameRelationSynonyms() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String w : new String[] { "dad", "daddy", "papa", "pa", "pop", "pops", "father" })
            m.put(w, "father");
        for (String w : new String[] { "mom", "mommy", "mum", "mummy", "mama", "ma", "mother" })
            m.put(w, "mother");
        for (String w : new String[] { "bro", "brother" }) m.put(w, "brother");
        for (String w : new String[] { "sis", "sister" }) m.put(w, "sister");
        for (String w : new String[] { "grandpa", "granddad", "grandad", "gramps", "grandfather" })
            m.put(w, "grandfather");
        for (String w : new String[] { "grandma", "grandmom", "granny", "gran", "nana", "grandmother" })
            m.put(w, "grandmother");
        for (String w : new String[] { "auntie", "aunty", "aunt" }) m.put(w, "aunt");
        for (String w : new String[] { "uncle" }) m.put(w, "uncle");
        for (String w : new String[] { "hubby", "husband" }) m.put(w, "husband");
        for (String w : new String[] { "wife" }) m.put(w, "wife");
        for (String w : new String[] { "son", "boy" }) m.put(w, "son");
        for (String w : new String[] { "daughter", "girl" }) m.put(w, "daughter");
        for (String w : new String[] { "cousin" }) m.put(w, "cousin");
        return m;
    }

    /// The canonical relation words, i.e. the VALUES above. A key consisting of nothing but one of
    /// these is a BARE relation ("Dad", "the father") - resolvable to a possessive form only when
    /// the book contains exactly one.
    private static final Set<String> NAME_RELATIONS = new HashSet<>(NAME_RELATION_SYNONYMS.values());

    /**
     * The comparison key for a scene-character name: two names sharing a key are the same character.
     *
     * <p>Accent- and case-insensitive, punctuation- and possessive-stripped, articles and honorifics
     * removed, kinship/role words folded to one canonical spelling. So {@code "Darby's Dad"},
     * {@code "darbys dad"} and {@code "Darby's father"} all key to {@code "darby father"}.
     *
     * <p>Returns the trimmed lowercase input when nothing normalises, and an empty string for
     * nothing usable — callers treat empty as "not a character".
     */
    public static String characterNameKey(String rawName) {
        if (rawName == null) return "";
        String t = stripAccentsLower(rawName).trim();
        /// Possessives first: "darby's" -> "darby". Curly apostrophes included - the extraction is
        /// LLM text, and a model will emit U+2019 as readily as an ASCII quote.
        t = t.replaceAll("[\u2019\u02bc']s\\b", " ");
        t = t.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "";
        /// Articles and honorifics can stack ("the Mr. Smith" is unlikely but "the old man" is not),
        /// so strip repeatedly rather than once.
        String prev;
        do {
            prev = t;
            t = NAME_ARTICLES.matcher(t).replaceFirst("");
            t = NAME_HONORIFICS.matcher(t).replaceFirst("");
        } while (!t.equals(prev) && !t.isEmpty());
        if (t.isEmpty()) return "";

        /// Drop identity-free filler first, so "Darby's old dad" and "Darby's dad" tokenize alike
        /// and the possessive rule below sees the relation word as the NEXT token either way.
        List<String> toks = new ArrayList<>();
        for (String tok : t.split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.equals("old") || tok.equals("young") || tok.equals("little")) continue;
            toks.add(tok);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toks.size(); i++) {
            String tok = toks.get(i);
            /// An APOSTROPHE-LESS possessive: "darbys dad". The apostrophised form was already
            /// handled above, but an LLM drops the apostrophe routinely, and without this
            /// "darbys dad" keys separately from "Darby's dad" - two characters again.
            ///
            /// Scoped to "trailing s immediately before a RELATION word" rather than "any trailing
            /// s", so an ordinary name is never truncated: "Charles" alone stays "charles". A
            /// possessor genuinely ending in s does get over-trimmed ("Charles's dad" and
            /// "Charles dad" both key to "charle father"), which is harmless and in fact required -
            /// they are the same person, the key is internal, and it is never displayed.
            boolean nextIsRelation = (i + 1 < toks.size())
                    && NAME_RELATIONS.contains(NAME_RELATION_SYNONYMS.getOrDefault(toks.get(i + 1), ""));
            if (nextIsRelation && tok.length() > 2 && tok.endsWith("s")
                    && !NAME_RELATION_SYNONYMS.containsKey(tok)) {
                tok = tok.substring(0, tok.length() - 1);
            }
            String rel = NAME_RELATION_SYNONYMS.get(tok);
            if (sb.length() > 0) sb.append(' ');
            sb.append(rel != null ? rel : tok);
        }
        return sb.toString().trim();
    }

    /**
     * Resolves each scene-character name the extraction produced to ONE canonical display name, so
     * the character loop creates one charPerson per person and every scene pins that same name.
     *
     * <p>Two rules, in order:
     * <ol>
     * <li><b>Same key, same character.</b> {@link #characterNameKey} folds case, accents,
     *     punctuation, possessives, articles, honorifics and kinship synonyms. Mechanical and safe.</li>
     * <li><b>A BARE relation joins the only possessive form of that relation.</b> {@code "Dad"} joins
     *     {@code "Darby's dad"} when Darby's is the ONLY father in the book. When there are two
     *     fathers it stays separate — a book with two families must not have every "Dad" collapsed
     *     into one person, and guessing which family is meant is not something this can know.</li>
     * </ol>
     *
     * <p>The FIRST spelling seen wins as the display name, which is what the user asked for: "there
     * needs to be a way to move/remove a duplicate and use just the first version".
     *
     * <p>Order-dependent by construction, so feed it names in scene order. Anything it cannot decide
     * is left as a separate character for {@code mergeCharacters} to fix by hand.
     */
    public static final class CharacterNameResolver {
        private final Map<String, String> canonicalByKey = new LinkedHashMap<>();
        /// Key -> canonical display name, for keys that END in a relation word and have a possessor
        /// ("darby father"). Only these can absorb a bare relation.
        private final Map<String, List<String>> possessiveKeysByRelation = new LinkedHashMap<>();
        private final Map<String, String> aliases = new LinkedHashMap<>();

        /**
         * @return the canonical display name for {@code rawName}, or null when the name is unusable.
         */
        public String resolve(String rawName) {
            if (rawName == null || rawName.trim().isEmpty()) return null;
            String key = characterNameKey(rawName);
            if (key.isEmpty()) return null;

            String known = canonicalByKey.get(key);
            if (known != null) {
                if (!known.equals(rawName.trim())) aliases.put(rawName.trim(), known);
                return known;
            }

            /// Rule 2: a bare relation joins the sole possessive form of the same relation.
            if (NAME_RELATIONS.contains(key)) {
                List<String> candidates = possessiveKeysByRelation.get(key);
                if (candidates != null && candidates.size() == 1) {
                    String canonical = candidates.get(0);
                    canonicalByKey.put(key, canonical);
                    aliases.put(rawName.trim(), canonical);
                    return canonical;
                }
            }

            String display = rawName.trim();
            canonicalByKey.put(key, display);
            /// Record the reverse direction too: a possessive form seen AFTER a bare relation must
            /// not retroactively steal it (the bare one already has its own entry), but it does
            /// become the anchor for any later bare mention.
            String[] toks = key.split("\\s+");
            if (toks.length > 1 && NAME_RELATIONS.contains(toks[toks.length - 1])) {
                possessiveKeysByRelation
                    .computeIfAbsent(toks[toks.length - 1], k -> new ArrayList<>())
                    .add(display);
            }
            return display;
        }

        /** Raw name -> canonical name, for every name that was folded into another. */
        public Map<String, String> getAliases() {
            return aliases;
        }

        /** The canonical display names, in first-seen order. */
        public List<String> getCanonicalNames() {
            return new ArrayList<>(new java.util.LinkedHashSet<>(canonicalByKey.values()));
        }
    }

    /**
     * Result of {@link #mergeCharacters}: what actually moved, so the caller can report it rather
     * than asserting success.
     */
    public static final class MergeResult {
        /** The surviving character's name. */
        public String keptName;
        /** Names of the characters that were folded in and deleted. */
        public final List<String> mergedNames = new ArrayList<>();
        /** Scene notes whose character list was rewritten. */
        public int scenesRepointed;
        /** Whether the book meta's scene character ids were rewritten. */
        public boolean metaUpdated;
        /** Characters that could not be deleted after their references moved. */
        public final List<String> failedDeletes = new ArrayList<>();
    }

    /**
     * Rewrite one scene note's character list, in place on the note's {@code text} JSON, replacing
     * every reference to a dropped character with the keeper.
     *
     * <p>Scene characters are persisted in TWO shapes and BOTH have to be handled: the note's text
     * JSON keeps the extraction's {@code [{name, role}]} maps (which is what
     * {@link #resolveSceneCharacter} resolves at imaging time — scene notes pin characters BY
     * NAME), while the book meta keeps a list of charPerson objectIds. Rewriting only one of them
     * leaves the book internally inconsistent: the Ux badges and the renderer would disagree about
     * who is in a scene.
     *
     * <p>Name matching goes through {@link #characterNameKey}, not string equality, so a scene that
     * spells the dropped character differently again is still repointed.
     *
     * <p>De-duplicates as it goes: a scene listing both "Dad" and "Darby's dad" must end up with
     * ONE entry, or the scene renders the same person twice and the two-character composite slots
     * are wasted on one person.
     *
     * @return true when the scene was changed and persisted
     */
    @SuppressWarnings("unchecked")
    private static boolean repointSceneCharacters(BaseRecord user, BaseRecord scene,
            Set<String> dropKeys, Set<String> dropOids, String keepName, String keepOid) {
        try {
            String existingText = scene.get("text");
            if (existingText == null || existingText.isEmpty()) return false;
            Map<String, Object> textData;
            try {
                textData = JSONUtil.getMap(existingText.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
            } catch (Exception ex) {
                logger.warn("mergeCharacters: unparseable text on scene "
                        + scene.get(FieldNames.FIELD_OBJECT_ID) + " - left untouched");
                return false;
            }
            Object charsObj = textData.get("characters");
            if (!(charsObj instanceof List)) return false;

            List<Object> chars = (List<Object>) charsObj;
            List<Object> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean changed = false;
            for (Object sc : chars) {
                Object entry = sc;
                if (sc instanceof Map) {
                    Map<String, Object> cm = (Map<String, Object>) sc;
                    Object raw = cm.get("name");
                    if (raw instanceof String
                            && dropKeys.contains(characterNameKey((String) raw))) {
                        cm.put("name", keepName);
                        changed = true;
                    }
                }
                else if (sc instanceof String) {
                    String v = (String) sc;
                    if (dropOids.contains(v) || dropKeys.contains(characterNameKey(v))) {
                        entry = (keepOid != null && dropOids.contains(v)) ? keepOid : keepName;
                        changed = true;
                    }
                }
                /// Identity for de-duplication: the name for a map entry, the value for a string.
                String identity;
                if (entry instanceof Map) {
                    Object n = ((Map<String, Object>) entry).get("name");
                    identity = (n instanceof String) ? characterNameKey((String) n) : String.valueOf(n);
                }
                else {
                    identity = characterNameKey(String.valueOf(entry));
                }
                if (!identity.isEmpty() && !seen.add(identity)) {
                    /// Already present - this entry is a duplicate created BY the merge.
                    changed = true;
                    continue;
                }
                out.add(entry);
            }
            if (!changed) return false;

            textData.put("characters", out);
            scene.set("text", JSONUtil.exportObject(textData));
            /// Never discard the update result - it is the only signal that the rewrite landed, and
            /// swallowing it turns a persistent failure into a silent no-op
            /// (.claude/rules/model-api.md).
            if (IOSystem.getActiveContext().getAccessPoint().update(user, scene) == null) {
                logger.error("mergeCharacters: FAILED to persist the repointed character list on scene "
                        + scene.get(FieldNames.FIELD_OBJECT_ID)
                        + " - that scene still references the merged-away character");
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("mergeCharacters: failed to repoint scene "
                    + scene.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Merge duplicate extracted characters into one: move every scene reference onto {@code keep},
     * union their scene-reference attributes, then delete the duplicates.
     *
     * <p><b>Why an endpoint and not just better extraction.</b> Canonicalisation
     * ({@link #canonicalizeSceneCharacterNames}) folds the spellings that are unambiguously the
     * same person, and the {@code knownCharacters} roster reduces how many the model invents in the
     * first place — but neither can decide whether the bare "Dad" in a book with two families is
     * Darby's or Mia's. Those are left as separate characters ON PURPOSE, and this is how they get
     * resolved: by the person who read the book.
     *
     * <p><b>Order is load-bearing.</b> References are repointed BEFORE the duplicate is deleted. The
     * reverse order would leave scenes naming a character that no longer exists, and because scene
     * notes pin characters by NAME those scenes would silently resolve nothing at render time — no
     * portrait, {@code refs=0}, a character-free image. That is the same failure mode that was
     * already diagnosed once when characters moved group.
     *
     * <p><b>Both representations are rewritten</b> — the scene notes' names and the book meta's
     * objectIds. See {@link #repointSceneCharacters}.
     *
     * <p>The keeper's {@code ATTR_SCENE_REFS} becomes the UNION of the merged characters' scene
     * indices, so scene-tagged apparel selection still sees every scene the person appears in.
     *
     * @param keepObjectId  the charPerson to keep — "use just the first version"
     * @param dropObjectIds the duplicates to fold in and delete
     * @throws PictureBookException 404 for an unknown book/character, 403 when the book denies the
     *         write, 400 for a request that is not a merge (no duplicates, or keep listed as a drop)
     */
    @SuppressWarnings("unchecked")
    public static MergeResult mergeCharacters(BaseRecord user, String bookObjectId,
            String keepObjectId, List<String> dropObjectIds) {
        if (keepObjectId == null || keepObjectId.isEmpty()) {
            throw new PictureBookException(400, "keepObjectId is required");
        }
        if (dropObjectIds == null || dropObjectIds.isEmpty()) {
            throw new PictureBookException(400, "At least one character to merge is required");
        }
        if (dropObjectIds.contains(keepObjectId)) {
            throw new PictureBookException(400, "A character cannot be merged into itself");
        }

        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
        BaseRecord bookGroup = resolveBookGroupEither(user, bookObjectId, orgId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);

        /// A merge deletes records and rewrites every scene in the book, so it needs UPDATE on the
        /// book group - not merely the ability to read a character. Same PBAC evaluator
        /// authorizeSceneRecord uses, for the same reason: the coarse @RolesAllowed on the endpoint
        /// says "is a user", never "may act on THIS book".
        PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil()
                .canUpdate(user, user, bookGroup);
        if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
            logger.warn("mergeCharacters: denied UPDATE on book group "
                    + bookGroup.get(FieldNames.FIELD_NAME) + " for user " + user.get(FieldNames.FIELD_NAME));
            throw new PictureBookException(403, "Not authorized for this book");
        }

        /// The characters group is derived SERVER-SIDE from the book's own world, never from a
        /// user-writable path in the meta note - the same trusted derivation listCharacters uses,
        /// and for the same reason (a tampered meta would otherwise point this at another user's
        /// population group, and this path DELETES).
        BaseRecord charsGroup = resolveTrustedCharsGroup(user,
                metaPb2BookObjectId(user, bookGroupPath, bookObjectId), bookGroupPath, orgId);
        if (charsGroup == null) throw new PictureBookException(404, "Book characters not found");
        long charsGroupId = ((Number) charsGroup.get(FieldNames.FIELD_ID)).longValue();

        BaseRecord keep = findBookCharacterById(user, keepObjectId, charsGroupId, orgId);
        if (keep == null) throw new PictureBookException(404, "Character to keep not found in this book");
        String keepName = keep.get(FieldNames.FIELD_NAME);

        MergeResult result = new MergeResult();
        result.keptName = keepName;

        List<BaseRecord> drops = new ArrayList<>();
        Set<String> dropKeys = new HashSet<>();
        Set<String> dropOids = new HashSet<>();
        for (String oid : dropObjectIds) {
            if (oid == null || oid.isEmpty()) continue;
            BaseRecord d = findBookCharacterById(user, oid, charsGroupId, orgId);
            if (d == null) throw new PictureBookException(404, "Character to merge not found in this book: " + oid);
            drops.add(d);
            dropOids.add(oid);
            String dn = d.get(FieldNames.FIELD_NAME);
            String dk = characterNameKey(dn);
            /// A duplicate whose name keys the SAME as the keeper's would make the repoint below
            /// match the keeper too. That cannot happen through the Ux (canonicalisation would have
            /// folded them), but a hand-built request could, and the consequence - rewriting the
            /// keeper's own entries - is silent.
            if (!dk.isEmpty() && !dk.equals(characterNameKey(keepName))) dropKeys.add(dk);
            result.mergedNames.add(dn);
        }

        /// 1. Scene notes FIRST - by name, which is what the renderer reads.
        BaseRecord scenesGroup = IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, bookGroupPath + "/Scenes", GroupEnumType.DATA.toString(), orgId);
        if (scenesGroup != null) {
            Query nq = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
                    FieldNames.FIELD_GROUP_ID, scenesGroup.get(FieldNames.FIELD_ID));
            nq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            nq.planMost(false);
            nq.setCache(false);
            BaseRecord[] notes = IOSystem.getActiveContext().getAccessPoint().list(user, nq).getResults();
            if (notes != null) {
                for (BaseRecord note : notes) {
                    String nname = note.get(FieldNames.FIELD_NAME);
                    if (META_NOTE_NAME.equals(nname)) continue;
                    if (repointSceneCharacters(user, note, dropKeys, dropOids, keepName, keepObjectId)) {
                        result.scenesRepointed++;
                    }
                }
            }
        }
        else {
            logger.warn("mergeCharacters: no Scenes group under " + bookGroupPath
                    + " - no scene notes were repointed");
        }

        /// 2. The book meta's objectId lists - what the Ux badges read.
        result.metaUpdated = repointMetaCharacters(user, bookGroupPath, dropOids, keepObjectId);

        /// 3. Union the scene references onto the keeper, so scene-tagged apparel selection still
        /// sees every scene this person appears in.
        unionSceneRefs(user, keep, drops);

        /// 4. Only now delete the duplicates, with their own foreign sub-records (profile,
        /// narrative, statistics, store, instinct, personality, state), which live outside the
        /// book's group subtree and would otherwise be orphaned - the same list and the same reason
        /// as the book-delete walk.
        for (BaseRecord d : drops) {
            if (!deleteBookCharacter(user, d)) {
                result.failedDeletes.add((String) d.get(FieldNames.FIELD_NAME));
            }
        }

        logger.info("mergeCharacters: merged " + result.mergedNames + " into '" + keepName
                + "' (" + result.scenesRepointed + " scene(s) repointed, meta "
                + (result.metaUpdated ? "updated" : "unchanged")
                + (result.failedDeletes.isEmpty() ? "" : ", FAILED to delete " + result.failedDeletes) + ")");
        return result;
    }

    /**
     * The {@code pb2BookObjectId} recorded in the book meta, falling back to the request id.
     *
     * <p>Read ONLY as an authorization-checked object-id HINT, never as a group path: it is resolved
     * through {@code deriveTrustedCharsGroupPath} → {@code readBook} → {@code AccessPoint} canRead,
     * so a tampered value yields the legacy fallback rather than another user's group. Same contract
     * as {@link #listCharacters}' use of it.
     */
    private static String metaPb2BookObjectId(BaseRecord user, String bookGroupPath, String fallback) {
        try {
            BaseRecord metaRec = loadMeta(user, bookGroupPath);
            if (metaRec != null) {
                String metaJson = metaRec.get("text");
                if (metaJson != null && !metaJson.isEmpty()) {
                    Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(StandardCharsets.UTF_8),
                            String.class, Object.class);
                    Object pb2 = meta.get("pb2BookObjectId");
                    if (pb2 instanceof String && !((String) pb2).isBlank()) return ((String) pb2).trim();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not read pb2BookObjectId from the book meta: " + e.getMessage());
        }
        return fallback;
    }

    /**
     * Rewrite the book meta's {@code scenes[].characters} objectId lists, replacing dropped ids with
     * the keeper and de-duplicating. Returns true when the meta was changed and persisted.
     */
    @SuppressWarnings("unchecked")
    private static boolean repointMetaCharacters(BaseRecord user, String bookGroupPath,
            Set<String> dropOids, String keepOid) {
        try {
            BaseRecord metaRec = loadMeta(user, bookGroupPath);
            if (metaRec == null) return false;
            String metaJson = metaRec.get("text");
            if (metaJson == null || metaJson.isEmpty()) return false;
            Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(StandardCharsets.UTF_8),
                    String.class, Object.class);
            Object scenesObj = meta.get("scenes");
            if (!(scenesObj instanceof List)) return false;
            boolean changed = false;
            for (Object so : (List<Object>) scenesObj) {
                if (!(so instanceof Map)) continue;
                Map<String, Object> sm = (Map<String, Object>) so;
                Object cl = sm.get("characters");
                if (!(cl instanceof List)) continue;
                List<Object> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Object c : (List<Object>) cl) {
                    Object v = c;
                    if (c instanceof String && dropOids.contains(c)) {
                        v = keepOid;
                        changed = true;
                    }
                    String id = String.valueOf(v);
                    if (!seen.add(id)) { changed = true; continue; }
                    out.add(v);
                }
                sm.put("characters", out);
            }
            if (!changed) return false;
            metaRec.set("text", JSONUtil.exportObject(meta));
            /// A PATCH, not a full-object update. loadMeta reads the note with planMost(TRUE), and
            /// handing that back to update() re-persists the whole populated graph - which
            /// .claude/rules/model-api.md warns can demand extra role grants and fail silently.
            /// `name` is in the field list deliberately: data.note inherits common.nameId, whose
            /// name carries a \S validation rule, and the writer validates THE PATCH rather than the
            /// merged result - so a patch without it is rejected while the update call still returns
            /// a value most callers would discard.
            BaseRecord metaPatch = metaRec.copyRecord(new String[] {
                    FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
            if (IOSystem.getActiveContext().getAccessPoint().update(user, metaPatch) == null) {
                logger.error("mergeCharacters: FAILED to persist the repointed book meta - the Ux "
                        + "character badges will still show the merged-away character");
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warn("mergeCharacters: failed to repoint the book meta: " + e.getMessage());
            return false;
        }
    }

    /**
     * Make the keeper's {@link #ATTR_SCENE_REFS} the union of its own and the merged characters'
     * scene indices, so {@code selectSceneApparel} still resolves for every scene the person is in.
     *
     * <p>Mutates the EXISTING attribute record and persists THAT directly — an attribute is
     * {@code referenced} storage, not a column, so folding it into a parent patch produces either an
     * empty SQL {@code SET} clause or a silent no-op (.claude/rules/model-api.md).
     */
    private static void unionSceneRefs(BaseRecord user, BaseRecord keep, List<BaseRecord> drops) {
        try {
            java.util.TreeSet<Integer> refs = new java.util.TreeSet<>();
            refs.addAll(parseSceneRefs(AttributeUtil.getAttributeValue(keep, ATTR_SCENE_REFS, (String) null)));
            for (BaseRecord d : drops) {
                refs.addAll(parseSceneRefs(AttributeUtil.getAttributeValue(d, ATTR_SCENE_REFS, (String) null)));
            }
            if (refs.isEmpty()) return;
            String csv = refs.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            BaseRecord existing = AttributeUtil.getAttribute(keep, ATTR_SCENE_REFS);
            if (existing != null) {
                existing.setFlex(FieldNames.FIELD_VALUE, csv);
                if (!IOSystem.getActiveContext().getRecordUtil().updateRecord(existing)) {
                    logger.warn("mergeCharacters: failed to update " + ATTR_SCENE_REFS + " on "
                            + keep.get(FieldNames.FIELD_NAME));
                }
            }
            else {
                IOSystem.getActiveContext().getRecordUtil().createRecord(
                        AttributeUtil.addAttribute(keep, ATTR_SCENE_REFS, csv));
            }
        } catch (Exception e) {
            logger.warn("mergeCharacters: failed to union " + ATTR_SCENE_REFS + ": " + e.getMessage());
        }
    }

    /** Parse an {@link #ATTR_SCENE_REFS} CSV, skipping anything non-numeric. */
    public static List<Integer> parseSceneRefs(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String tok : csv.split(",")) {
            try { out.add(Integer.valueOf(tok.trim())); } catch (NumberFormatException nfe) { /* skip */ }
        }
        return out;
    }

    /**
     * Delete one book character and its own foreign sub-records. Same field list and rationale as
     * the book-delete walk: those records live in shared {@code ~/Profiles}/{@code ~/Narratives}
     * buckets or the world's groups, not under the book's Characters subtree, so nothing else would
     * ever reach them. Each is created fresh per character, so this cannot orphan another
     * character's data.
     */
    private static boolean deleteBookCharacter(BaseRecord user, BaseRecord charPerson) {
        boolean ok = true;
        /// The portrait FIRST, while the profile that points at it still exists.
        ///
        /// AccessPoint.delete does not cascade, and the portrait is a data.data in the book world's
        /// Gallery group - OUTSIDE this character's own record graph. Deleting the profile without
        /// it leaves the image orphaned in the gallery forever, with no owner to reach it from. The
        /// book-delete walk gets these via its group sweep; a per-character delete has no sweep, so
        /// it has to do this explicitly.
        ///
        /// populate() first: "portrait" is a foreign data.data field, so a projection that merely
        /// names "profile" returns the profile's default query fields and portrait reads null - the
        /// same two-step this class already uses at the reimage call site.
        try {
            BaseRecord profile = charPerson.get(FieldNames.FIELD_PROFILE);
            if (profile != null) {
                Long pid = profile.get(FieldNames.FIELD_ID);
                if (pid != null && pid > 0L) {
                    IOSystem.getActiveContext().getReader().populate(profile, new String[] { "portrait" });
                    BaseRecord portrait = profile.get("portrait");
                    Long portraitId = (portrait != null) ? portrait.get(FieldNames.FIELD_ID) : null;
                    if (portraitId != null && portraitId > 0L) {
                        IOSystem.getActiveContext().getAccessPoint().delete(user, portrait);
                    }
                }
            }
        } catch (Exception e) {
            /// A leaked image is not worth failing the merge over - the references have already
            /// moved by this point, so aborting here would leave the book in a worse state than an
            /// orphaned thumbnail.
            logger.warn("mergeCharacters: could not delete " + charPerson.get(FieldNames.FIELD_NAME)
                    + "'s portrait image (left in the gallery): " + e.getMessage());
        }
        String[] charForeignFields = new String[] {
                "profile", "narrative", OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE,
                OlioFieldNames.FIELD_INSTINCT, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
        };
        for (String f : charForeignFields) {
            try {
                BaseRecord fk = charPerson.get(f);
                Long fkId = (fk != null) ? fk.get(FieldNames.FIELD_ID) : null;
                if (fkId != null && fkId > 0L) {
                    IOSystem.getActiveContext().getAccessPoint().delete(user, fk);
                }
            } catch (Exception e) {
                logger.warn("mergeCharacters: failed to delete "
                        + charPerson.get(FieldNames.FIELD_NAME) + "'s " + f + ": " + e.getMessage());
                ok = false;
            }
        }
        try {
            if (!IOSystem.getActiveContext().getAccessPoint().delete(user, charPerson)) {
                logger.error("mergeCharacters: failed to delete merged character "
                        + charPerson.get(FieldNames.FIELD_NAME));
                ok = false;
            }
        } catch (Exception e) {
            logger.error("mergeCharacters: failed to delete merged character "
                    + charPerson.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
            ok = false;
        }
        return ok;
    }

    /**
     * One book character by objectId, CONSTRAINED to the book's own characters group.
     *
     * <p>The group condition is the compartment boundary, and it is not optional:
     * {@code AccessPoint.list} authorizes the query SHAPE and returns whatever search returned with
     * no per-record filtering, so an org-wide by-objectId lookup would happily return another user's
     * character (.claude/rules/model-api.md). This path deletes.
     */
    private static BaseRecord findBookCharacterById(BaseRecord user, String objectId,
            long charsGroupId, long orgId) {
        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
        q.field(FieldNames.FIELD_GROUP_ID, charsGroupId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
                "profile", "narrative", OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE,
                OlioFieldNames.FIELD_INSTINCT, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE,
                FieldNames.FIELD_ATTRIBUTES });
        q.setCache(false);
        BaseRecord[] found = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
        return (found != null && found.length > 0) ? found[0] : null;
    }

    /**
     * Rewrite every scene's {@code characters[].name} to its canonical spelling, IN PLACE, and
     * return the alias map that was applied.
     *
     * <p>One pass, before anything else reads the scene list, because everything downstream keys on
     * that name and they all benefit at once:
     * <ul>
     * <li>the {@code uniqueChars} map creates ONE charPerson per person instead of one per spelling;</li>
     * <li>{@code charSceneIndices}/{@code charBlocks} aggregate every alias's scenes and passages
     *     under one character, so the reduce step sees ALL of that character's text — previously
     *     "Darby's dad" and "the father" each got a partial view and therefore a different
     *     appearance;</li>
     * <li>{@code createSceneNote} persists the canonical name, which is what
     *     {@code resolveSceneCharacter} resolves at imaging time (scene notes pin characters BY
     *     NAME);</li>
     * <li>{@code buildSceneEntry} maps it to the one charPerson objectId.</li>
     * </ul>
     *
     * <p>{@code seedNames} is applied to the resolver FIRST, so when Step 3 supplied a curated
     * character list those names are authoritative and scene mentions fold onto them rather than the
     * other way round.
     *
     * <p>Tolerates both persisted shapes ({@code {name:...}} map, bare string) exactly as the
     * readers around it do, and leaves any entry it cannot resolve untouched.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> canonicalizeSceneCharacterNames(List<Map<String, Object>> sceneList,
            List<String> seedNames) {
        CharacterNameResolver resolver = new CharacterNameResolver();
        if (seedNames != null) {
            for (String seed : seedNames) resolver.resolve(seed);
        }
        if (sceneList == null) return resolver.getAliases();
        for (Map<String, Object> scene : sceneList) {
            Object charsObj = (scene != null) ? scene.get("characters") : null;
            if (!(charsObj instanceof List)) continue;
            List<Object> chars = (List<Object>) charsObj;
            for (int i = 0; i < chars.size(); i++) {
                Object sc = chars.get(i);
                if (sc instanceof Map) {
                    Map<String, Object> cm = (Map<String, Object>) sc;
                    Object raw = cm.get("name");
                    if (!(raw instanceof String)) continue;
                    String canonical = resolver.resolve((String) raw);
                    if (canonical != null) cm.put("name", canonical);
                }
                else if (sc instanceof String) {
                    /// A bare string entry is a NAME only in the pre-buildSceneEntry shape this
                    /// method runs against; an objectId string appears only in the persisted meta,
                    /// which this never sees. Guarded anyway - a UUID keys to itself, so resolve()
                    /// returns it unchanged and the entry is a no-op.
                    String canonical = resolver.resolve((String) sc);
                    if (canonical != null) chars.set(i, canonical);
                }
            }
        }
        Map<String, String> aliases = resolver.getAliases();
        if (!aliases.isEmpty()) {
            logger.info("Character name canonicalisation folded " + aliases.size()
                    + " alias(es) into an existing character: " + aliases);
        }
        return aliases;
    }

    /**
     * Persist a character's scene references (Attribute 1, {@link #ATTR_SCENE_REFS}) and condensed
     * description (Attribute 2, {@link #ATTR_DESCRIPTION}) as attributes on the charPerson via the
     * referenced-attribute mechanism (the only pattern that actually persists an attribute — see
     * {@link #tagApparelSceneIndex}). Best-effort: a failure here must not fail the whole book build.
     */
    /**
     * Resolve the visual description to persist as {@link #ATTR_DESCRIPTION} for a character.
     *
     * <p>Prefers the LLM-reduced description; falls back to the {@code appearance} the scene
     * extraction already supplied for this character.
     *
     * <p><b>Why the fallback exists.</b> The reduce step that produces {@code reducedDescription}
     * runs only when {@code charData} arrived with NO {@code appearance} — so a character the source
     * text describes WELL got no {@code pbDescription} at all, while every character it described
     * poorly got one. That inversion is not cosmetic: {@code resolveSceneCharacter} treats a missing
     * {@code pbDescription} as "fall back to {@code narrative.sdPrompt}", which carries a random art
     * style and a random era/setting baked in at creation, and that text then fights the character's
     * own portrait reference in the composite. Measured live 2026-09-15 on "The Big Way Out": the
     * protagonist (present in all 58 scenes) was the ONLY one of 13 characters with no
     * {@code pbDescription}, and the only one whose likeness the composite did not preserve.
     *
     * <p>{@code appearance} is LLM-extracted, so it is screened with
     * {@link NarrativeUtil#isMeaningful(String)} rather than a blank check — an LLM that cannot
     * determine an attribute emits the literal string {@code "null"} as the value.
     */
    /// Public so the inversion above can be asserted directly (TestFlux2Composite
    /// appearanceBecomesTheImagingDescription) without standing up a whole book build. Pure function,
    /// no IO.
    public static String imagingDescription(String reducedDescription, Map<String, Object> charData) {
        if (reducedDescription != null && !reducedDescription.isBlank()) return reducedDescription;
        if (charData == null) return null;
        Object appearance = charData.get("appearance");
        if (appearance instanceof String && NarrativeUtil.isMeaningful((String) appearance)) {
            return ((String) appearance).trim();
        }
        return null;
    }

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

    /**
     * Issue 4: stamp the BOOK world's Gallery path ({@link SDUtil#ATTR_IMAGE_GALLERY_PATH}) on a PB2
     * charPerson so a later (re)image call — which frequently arrives with the DEFAULT grid
     * OlioContext (My Grid World) rather than the book's — stores this character's images under THIS
     * book's world gallery. That gallery tree is olio-owned and RUCD-granted to the olio role
     * ({@code getCreateBookContext} sets {@code scanNestedWorldGroups(true)}), so the olio user CAN
     * makePath/write there. Read back by {@link SDUtil#resolveCharacterImagePath}. Persisted via the
     * referenced-attribute mechanism (the only pattern that actually persists an attribute — see
     * {@link #persistCharacterSceneAttributes}). Best-effort: a failure must not fail the book build;
     * SDUtil then simply falls back to the passed context's world gallery.
     */
    private static void persistCharacterImageGalleryPath(BaseRecord charPerson, String bookGalleryPath) {
        if (charPerson == null || bookGalleryPath == null || bookGalleryPath.isBlank()) return;
        try {
            IOSystem.getActiveContext().getRecordUtil().createRecord(
                    AttributeUtil.addAttribute(charPerson, SDUtil.ATTR_IMAGE_GALLERY_PATH, bookGalleryPath));
        } catch (Exception e) {
            logger.warn("Failed to persist " + SDUtil.ATTR_IMAGE_GALLERY_PATH + " for "
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

        /**
         * Whether the run actually reached the end of the source text.
         *
         * <p>This is NOT "nobody cancelled". A chunked run also stops early on thread interruption
         * (a Tomcat stop or job-pool shutdown) and on the unreachable-LLM circuit breaker, and both
         * of those previously reported completion — one measured run reported
         * {@code extractionComplete: true} having extracted ZERO scenes because its chat config
         * could not be resolved. Only the chunk loop knows which happened, so it reports the fact
         * here rather than leaving each caller to re-derive it from the cancel flag (which cannot
         * express it) or from a current/total comparison (which put the determination in the
         * transport layer).
         *
         * <p>Always true for the single-shot path, which has exactly one step.
         */
        public final boolean complete;

        public ScenesOnlyResult(List<Map<String, Object>> scenes, boolean chunked) {
            this(scenes, chunked, new ArrayList<>(), true);
        }

        public ScenesOnlyResult(List<Map<String, Object>> scenes, boolean chunked, List<String> failedExtractions) {
            this(scenes, chunked, failedExtractions, true);
        }

        public ScenesOnlyResult(List<Map<String, Object>> scenes, boolean chunked,
                List<String> failedExtractions, boolean complete) {
            this.scenes = scenes;
            this.chunked = chunked;
            this.failedExtractions = failedExtractions != null ? failedExtractions : new ArrayList<>();
            this.complete = complete;
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
        q.field(FieldNames.FIELD_NAME, META_NOTE_NAME);
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
        plist.parameter(FieldNames.FIELD_NAME, META_NOTE_NAME);
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

    /// Opening tokens every machine-written portrait prompt in this pipeline carries —
    /// {@link #PORTRAIT_QUALITY_PREAMBLE}, {@code NarrativeUtil.buildPortraitPromptFromExtractedData}
    /// and {@code NarrativeUtil.getSDPrompt} all start with exactly this. Used to tell a GENERATED
    /// {@code narrative.sdPrompt} (safe to regenerate) from a HAND-EDITED one (must be preserved and
    /// must win) — the Manage Characters screen deliberately offers the full generic editor so
    /// {@code narrative.sdPrompt} can be written by hand, and silently overwriting that on every
    /// render would delete the user's work.
    private static final String GENERATED_PROMPT_PREFIX =
            "8k highly detailed ((highest quality)) ((ultra realistic))";

    /**
     * Was this stored {@code narrative.sdPrompt} written by a HUMAN rather than by the pipeline?
     *
     * <p>Every machine-written portrait prompt here starts with {@link #GENERATED_PROMPT_PREFIX};
     * anything else came from the generic editor, which the Manage Characters screen deliberately
     * links to for exactly this purpose. A hand-written prompt is never regenerated and always wins
     * for the portrait render — so the record-driven refresh cannot silently delete the user's work.
     *
     * <p>Blank/absent counts as NOT hand-written (there is nothing to preserve).
     */
    public static boolean isHandWrittenPrompt(String sdPrompt) {
        return sdPrompt != null && !sdPrompt.isBlank()
                && !sdPrompt.trim().startsWith(GENERATED_PROMPT_PREFIX);
    }

    /**
     * Holder for a character's narrative descriptions rebuilt from the persisted record.
     * {@code narration} is the composed, style- and setting-free imaging text; the three parts are
     * kept separately because they are also what gets written back onto {@code olio.narrative}.
     */
    private static final class RecordNarrative {
        final String physical;
        final String outfit;
        final String statistics;
        final String narration;
        RecordNarrative(String physical, String outfit, String statistics, String narration) {
            this.physical = physical;
            this.outfit = outfit;
            this.statistics = statistics;
            this.narration = narration;
        }
    }

    /**
     * Compose the imaging narration from a character's three narrative descriptions: appearance,
     * then statistics, then outfit. Pure string work, exposed for direct assertion.
     *
     * <p>Order is deliberate. Appearance first (what the character looks like), statistics second
     * (build/condition/bearing), outfit last — so the clause nearest the end of the prompt, where
     * diffusion models weight hardest, is the clothing, which is the part a scene most often needs
     * to override per scene via scene-tagged apparel.
     *
     * <p>Carries no art style and no setting: the book's ONE style is applied exactly once
     * downstream by {@link #appendConfigStyleOnce}, and the scene supplies its own
     * setting/action/mood. This is the whole reason the composition is here rather than reusing
     * {@code NarrativeUtil.getSDPrompt}, which bakes a RANDOM style and a RANDOM era/setting in.
     */
    public static String composeRecordNarration(String physical, String statistics, String outfit) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] { physical, statistics, outfit }) {
            if (part == null) continue;
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sb.charAt(sb.length() - 1) == '.' ? " " : ". ");
            sb.append(t);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Rebuild a character's narrative descriptions FROM THE PERSISTED RECORD, persist them back onto
     * {@code olio.narrative}, and return the composed imaging narration.
     *
     * <p><b>Why this exists — issue 2.</b> Every description the imaging path used was a frozen
     * LLM string written once at extraction: the {@code pbDescription} attribute (the reduce step's
     * output, or the extraction's {@code appearance}), and {@code narrative.physicalDescription},
     * which {@code ensureNarrative} overwrites with
     * {@code NarrativeUtil.buildPortraitPromptFromExtractedData}. None of them was derived from the
     * charPerson, so:
     * <ul>
     * <li>they disagreed with the record — hair/eye colour came from the random race palette until
     *     {@code createCharPerson} started seeding them from the extraction (see
     *     {@link #mapPersonColorOverride}), and statistics/apparel are not written until AFTER
     *     {@code ensureNarrative} runs, so a creation-time narrative cannot describe them at all;</li>
     * <li>every post-extraction edit in the Manage Characters screen — the statistics sliders, the
     *     outfit builder, an apparel change, anything through the generic editor — was invisible to
     *     image generation, which is the reported "inconsistent or wrong, sometimes wildly so".</li>
     * </ul>
     *
     * <p>Three things make it correct rather than merely different:
     * <ol>
     * <li><b>A genuinely fresh read.</b> {@code setCache(false)} plus an explicit {@code Query} is
     *     mandatory here, not defensive: the Ux edits statistics and apparel as NESTED records, and
     *     {@code CacheDBSearch.clearCache} only invalidates cache entries matching the updated
     *     record's OWN schema+identity — so a charPerson cached earlier in this same request still
     *     carries the pre-edit statistics. See {@code .claude/rules/model-api.md}.</li>
     * <li><b>A refreshed profile.</b> {@code ProfileUtil} memoizes {@code PersonalityProfile} by
     *     person id in a process-wide map that nothing invalidates, so {@code getProfile} would hand
     *     back statistics from whenever the character was first analysed. {@code updateProfile}
     *     re-derives it from the record we just read.</li>
     * <li><b>{@code describeOutfit} off the record, not the profile.</b> The plain-BaseRecord
     *     overload reads {@code store.apparel} through {@code ApparelUtil.getWearing}, which is where
     *     the outfit builder's output actually lives.</li>
     * </ol>
     *
     * <p>Returns null when the record cannot be read or yields nothing describable; the caller then
     * falls back to the legacy frozen strings rather than rendering a character with no description.
     * Persistence is best-effort and deliberately NOT a precondition of the return value — writing
     * the narrative back is for inspectability in the Ux, and a PBAC denial on the world-owned
     * narrative must not stop this render.
     */
    private static RecordNarrative refreshNarrativeFromRecord(BaseRecord user, BaseRecord charPerson, String cname) {
        if (charPerson == null) return null;
        String oid = charPerson.get(FieldNames.FIELD_OBJECT_ID);
        if (oid == null) return null;
        try {
            /// A fresh, fully-planned read. OlioUtil.planMost is the canonical Olio projection (it
            /// prunes the branches that would otherwise blow past PostgreSQL's 100-argument limit);
            /// setCache(false) is what makes a just-edited nested statistics/apparel record visible.
            Query fq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
            fq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
            OlioUtil.planMost(fq);
            fq.setCache(false);
            BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(user, fq);
            if (full == null) {
                logger.warn("Record-driven description for " + cname
                        + ": could not re-read the charPerson fully - falling back to the stored description");
                return null;
            }

            String physical = null;
            String statistics = null;
            /// describeOutfit's plain-BaseRecord overload needs no profile, so the outfit survives
            /// even when personality/statistics are too sparse to analyse.
            String outfit = NarrativeUtil.describeOutfit(full, false);
            try {
                /// updateProfile, not getProfile: see (2) above.
                PersonalityProfile pp = ProfileUtil.updateProfile(null, full);
                if (pp != null) {
                    physical = NarrativeUtil.describePhysical(pp);
                    statistics = NarrativeUtil.describeStatistics(pp);
                }
            } catch (Exception pe) {
                /// A character with no personality/instinct (created with no OlioContext, so
                /// buildRandomBaseline returned null) cannot be profiled - DarkTetradUtil
                /// dereferences charPerson.personality without a null check. Degrade to the
                /// record-only parts rather than losing the record-driven path entirely.
                logger.warn("Record-driven description for " + cname
                        + ": could not build a PersonalityProfile (" + pe.getMessage()
                        + ") - using the record's outfit only");
            }

            String narration = composeRecordNarration(physical, statistics, outfit);
            if (narration == null) {
                logger.warn("Record-driven description for " + cname
                        + ": the record yielded nothing describable - falling back to the stored description");
                return null;
            }
            persistRefreshedNarrative(user, full, cname, physical, outfit, statistics, narration);
            return new RecordNarrative(physical, outfit, statistics, narration);
        } catch (Exception e) {
            logger.warn("Record-driven description for " + cname + " failed: " + e.getMessage()
                    + " - falling back to the stored description");
            return null;
        }
    }

    /**
     * Write the refreshed descriptions back onto the character's {@code olio.narrative}, so what the
     * image was generated from is visible (and hand-editable) in the Ux rather than existing only as
     * a transient local.
     *
     * <p>{@code sdPrompt} is refreshed ONLY when it still looks machine-written (see
     * {@link #GENERATED_PROMPT_PREFIX}). A hand-written prompt is left exactly as it is — and
     * {@link #resolveSceneCharacter} then prefers it, so a manual override actually overrides.
     *
     * <p>Acts as the OLIO PRINCIPAL when it can be resolved: book narratives live in the book
     * world's {@code Narratives} group, which is olio-owned, and the request user is not entitled to
     * update rows there — the same reason {@code ensureNarrative} passes
     * {@code octx.getOlioUser()}. Falls back to the acting user (correct for legacy PB1 narratives
     * in the user's own {@code ~/Narratives}).
     *
     * <p>Best-effort by contract: every failure is a WARN, never a throw. But the update result is
     * never DISCARDED — {@code AccessPoint.update} returning null is the only signal there is, and
     * swallowing it turns a persistent failure into a silent no-op
     * ({@code .claude/rules/model-api.md}).
     */
    private static void persistRefreshedNarrative(BaseRecord user, BaseRecord full, String cname,
            String physical, String outfit, String statistics, String narration) {
        try {
            BaseRecord narrative = full.get("narrative");
            Long nid = (narrative != null) ? narrative.get(FieldNames.FIELD_ID) : null;
            if (narrative == null || nid == null || nid <= 0L) {
                logger.warn("Record-driven description for " + cname
                        + ": no persisted narrative to write back to (the description is still used for this render)");
                return;
            }

            List<String> fields = new ArrayList<>();
            fields.add(FieldNames.FIELD_ID);
            fields.add(FieldNames.FIELD_OBJECT_ID);
            boolean changed = false;
            String[][] derived = new String[][] {
                { "physicalDescription", physical },
                { "outfitDescription", outfit },
                { "statisticsDescription", statistics }
            };
            for (String[] pair : derived) {
                if (pair[1] == null || pair[1].isBlank()) continue;
                String current = null;
                try { current = narrative.get(pair[0]); } catch (Exception e) { /* ignore */ }
                if (!pair[1].equals(current)) changed = true;
                narrative.set(pair[0], pair[1]);
                fields.add(pair[0]);
            }
            String existingPrompt = narrative.get("sdPrompt");
            if (isHandWrittenPrompt(existingPrompt)) {
                logger.info("Character " + cname + ": narrative.sdPrompt looks hand-written - "
                        + "preserved as-is and used for the portrait render");
            }
            else {
                String refreshedPrompt = PORTRAIT_QUALITY_PREAMBLE + narration;
                if (!refreshedPrompt.equals(existingPrompt)) changed = true;
                narrative.set("sdPrompt", refreshedPrompt);
                fields.add("sdPrompt");
            }
            if (fields.size() <= 2) return;
            /// Nothing to write when the record already says exactly this. resolveSceneCharacter
            /// runs twice per scene (Stage 0 builds the composite prompt, Stage 1 renders the
            /// portrait) for every character in every scene, so on a 58-scene book an unconditional
            /// patch here would be hundreds of writes that change nothing — and would churn the
            /// record's modified state on every render.
            if (!changed) return;

            /// copyRecord(fields), not the bare RecordFactory.newInstance - the bare overload
            /// materialises EVERY field at its default and the writer persists every field present,
            /// which would blank the descriptions this patch is not carrying. Same idiom, and the
            /// same explicit field list, as ensureNarrative's own narrative patch.
            BaseRecord patch = narrative.copyRecord(fields.toArray(new String[0]));
            BaseRecord actor = user;
            try {
                long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
                BaseRecord olioUser = IOSystem.getActiveContext().getFactory()
                        .findUser(OlioContext.OLIO_USER_NAME, orgId);
                if (olioUser != null) actor = olioUser;
            } catch (Exception oe) {
                logger.warn("Could not resolve the olio principal to write " + cname
                        + "'s narrative; trying as the request user: " + oe.getMessage());
            }
            if (IOSystem.getActiveContext().getAccessPoint().update(actor, patch) == null) {
                logger.warn("Failed to persist the refreshed narrative descriptions for " + cname
                        + " - AccessPoint.update denied or failed (the description is still used for this render)");
            }
        } catch (Exception e) {
            logger.warn("Failed to write back the refreshed narrative for " + cname + ": " + e.getMessage());
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
    /**
     * The book world's {@code Population} group for a PB1 scene group path, or null.
     *
     * <p><b>Why this exists.</b> Since the per-book-world change, {@code createCharPerson} routes a
     * book's charPerson records into the WORLD's population group
     * ({@code world.population.path}), not the legacy PB1 {@code <book>/Characters} group. The
     * read side was never updated, so {@link #resolveSceneCharacter} kept looking in the PB1 group
     * — which for a post-change book exists but is EMPTY. Measured 2026-09-14 on
     * "The Big Way Out.pdf": PB1 {@code Characters} (group 619) held 0 charPersons while the book
     * world's {@code Population} (group 580) held all 6, including Darby. Every scene then logged
     * "Could not resolve scene character" (85 times), produced
     * "Stage 1 complete: 0 portraits generated", and the composite ran with
     * {@code refs=0} / {@code hasPromptImages=false} — character-free images.
     *
     * <p>Derivation is by slug, the same link PB2 uses elsewhere: the PB1 book group name is the
     * segment above {@code /Scenes}, {@code PbPipelineUtil.deriveSlug} maps it to the world name,
     * and the world container sits under {@code PbOlioContextUtil.bookWorldPath()}. Read as the
     * ACTING user, not the olio principal — the world's role pair is granted on this group
     * (verified: Population carries the book's Writer+Admin), so a user entitled to the book can
     * read it, and one who is not still must not.
     */
    /**
     * The book-world Population path a PB1 scene group maps to, or null when the scene path is not
     * a PB1 book scenes group or its name yields no valid slug. Separated from the group lookup so
     * the derivation — the fragile part — is testable without a database.
     */
    static List<String> bookPopulationPathCandidates(String sceneGroupPath, String explicitSlug) {
        List<String> out = new ArrayList<>();
        String worlds = PbOlioContextUtil.bookWorldPath();
        if (explicitSlug != null && !explicitSlug.isBlank()) {
            out.add(worlds + "/" + explicitSlug + "/Population");
        }
        if (sceneGroupPath != null) {
            int idx = sceneGroupPath.lastIndexOf("/Scenes");
            if (idx > 0) {
                String bookGroupPath = sceneGroupPath.substring(0, idx);
                int slash = bookGroupPath.lastIndexOf('/');
                String bookGroupName = (slash >= 0) ? bookGroupPath.substring(slash + 1) : bookGroupPath;

                /// Server rule. Keeps '.', so "The Big Way Out.pdf" -> "the-big-way-out.pdf".
                String serverSlug = PbPipelineUtil.deriveSlug(bookGroupName);
                if (serverSlug != null) {
                    String c = worlds + "/" + serverSlug + "/Population";
                    if (!out.contains(c)) out.add(c);
                }

                /// CLIENT rule, and it DISAGREES with the server's. pictureBook.js generateSlug is
                /// `[^a-z0-9]+ -> '-'`, which strips '.', while PbPipelineUtil.deriveSlug is
                /// `[^a-z0-9._-]+ -> '-'`, which keeps it. The client's slug is the one that
                /// actually created the world, so for any book named "<something>.pdf" the server
                /// derivation misses by exactly one character: the live world for
                /// "The Big Way Out.pdf" is "the-big-way-out-pdf", not "the-big-way-out.pdf".
                /// Both spellings are tried rather than picking one, because a book created by
                /// either route must resolve. See the note in PictureBookAsyncJobDesign /
                /// KnownIssues — the two generators should be converged, which is a separate change.
                String clientSlug = bookGroupName.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+", "").replaceAll("-+$", "");
                if (clientSlug.length() > 64) clientSlug = clientSlug.substring(0, 64);
                if (!clientSlug.isEmpty()) {
                    String c = worlds + "/" + clientSlug + "/Population";
                    if (!out.contains(c)) out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The book world's {@code Population} group for this scene, or null.
     *
     * <p>{@code explicitSlug} (the caller's {@code SceneGenerationParams.bookSlug}) is
     * AUTHORITATIVE when present — it is the slug the book was actually created with. The
     * path-derived candidates are a fallback for callers that only know the PB1 group, and there
     * are two of them because the client and server slug rules disagree (see above).
     */
    private static BaseRecord findBookPopulationGroup(BaseRecord user, String sceneGroupPath, String explicitSlug) {
        if (user == null) return null;
        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
        for (String popPath : bookPopulationPathCandidates(sceneGroupPath, explicitSlug)) {
            try {
                BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                        ModelNames.MODEL_GROUP, popPath, GroupEnumType.DATA.toString(), orgId);
                if (grp != null) {
                    return grp;
                }
            } catch (Exception e) {
                logger.warn("findBookPopulationGroup: " + popPath + " — " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Find a charPerson by name within one group: ILIKE+trim first, then a diacritic-insensitive
     * pass in Java.
     *
     * <p>Extracted so the same matching runs against every candidate group. The two-step is
     * deliberate and both steps are load-bearing: the DB ILIKE+trim catches case and whitespace
     * drift between the LLM's scene-character name and the persisted one (an exact EQUALS silently
     * missed "Jideon"), and the Java pass catches diacritics the DB does not fold ("Duña" vs
     * "Duna").
     */
    /**
     * The first candidate whose name is a GENUINE match for {@code cname} — accent- and
     * case-insensitive, but whole-string. Null when none is.
     *
     * <p>The point is the verification, not the search: it is what stops a DB-side {@code ILIKE}
     * substring hit ("Darby" matching "Darby's dad") from being accepted as the answer. Logs which
     * pass produced the match, because "resolved by exact name" and "resolved after folding
     * accents" are different facts about the data and the second one is worth seeing.
     */
    private static BaseRecord firstExactNameMatch(String cname, BaseRecord[] candidates, String how) {
        if (candidates == null) return null;
        for (BaseRecord cand : candidates) {
            String candName = cand.get(FieldNames.FIELD_NAME);
            if (namesMatchAccentInsensitive(cname, candName)) {
                if (!cname.equals(candName)) {
                    logger.info("Resolved scene character '" + cname + "' to persisted '"
                            + candName + "' via the " + how + " pass");
                }
                return cand;
            }
        }
        return null;
    }

    private static BaseRecord findCharPersonByNameInGroup(BaseRecord user, String cname, BaseRecord grp) {
        if (grp == null || cname == null) return null;
        String[] req = new String[]{"id", FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
            "narrative", "gender", "profile", FieldNames.FIELD_STORE, FieldNames.FIELD_ATTRIBUTES};

        /// Pass 1: EXACT. Index-friendly and unambiguous.
        ///
        /// This used to be a single ILIKE query, and that was a SUBSTRING match, not the
        /// case-insensitive exact match it reads as: StatementUtil silently wraps a LIKE/ILIKE value
        /// in %...% when the value contains no % of its own (StatementUtil.java:1312-1314). So
        /// looking up "Darby" emitted `name ILIKE '%Darby%'`, which also matches "Darby's dad" —
        /// and AccessPoint.find takes ONE row from an unordered result.
        ///
        /// MEASURED on am72db 2026-09-18, book "BWO 3" (population group 727, Darby=128,
        /// Darby's dad=129): that query returns the DAD first. Every scene referencing "Darby"
        /// therefore rendered her father, and used his portrait as the FLUX.2 reference — 17 of 17
        /// scene composites in that book, including scenes where Darby is the only character.
        /// Any character whose name is a substring of another's hits this; Veronique and Yolanda in
        /// the same book resolved correctly because nothing contains their names.
        Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
        cq.field(FieldNames.FIELD_NAME, cname.trim());
        cq.field(FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
        cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        cq.setRequest(req);
        BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
        if (cp != null) return cp;

        /// Pass 2: DB-side case-insensitive narrowing, then VERIFIED in Java.
        ///
        /// The ILIKE stays — it is the cheap way to let the database fold case, and it is why an
        /// exact EQUALS silently missing "Jideon" was reported in the first place — but it is now a
        /// CANDIDATE filter whose every result is checked for a genuine exact match, and it goes
        /// through list() rather than find() so a substring collision cannot hide the real record
        /// behind an arbitrary first row.
        Query likeq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
        likeq.field(FieldNames.FIELD_NAME, ComparatorEnumType.ILIKE, cname.trim());
        likeq.field(FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
        likeq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        likeq.setRequest(req);
        BaseRecord match = firstExactNameMatch(cname, IOSystem.getActiveContext()
                .getAccessPoint().list(user, likeq).getResults(), "case-insensitive");
        if (match != null) return match;

        /// Pass 3: the whole group, for diacritics the database does not fold ("Du\u00f1a" vs "Duna").
        Query allq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
        allq.field(FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
        allq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        allq.setRequest(req);
        return firstExactNameMatch(cname, IOSystem.getActiveContext()
                .getAccessPoint().list(user, allq).getResults(), "accent-insensitive");
    }

    private static ResolvedCharacter resolveSceneCharacter(BaseRecord user, Object charItem, String sceneGroupPath) {
        return resolveSceneCharacter(user, charItem, sceneGroupPath, null);
    }

    /**
     * @param bookSlug the book's authoritative slug when the caller knows it
     *                 ({@code SceneGenerationParams.bookSlug}); null falls back to deriving
     *                 candidates from {@code sceneGroupPath}.
     */
    private static ResolvedCharacter resolveSceneCharacter(BaseRecord user, Object charItem, String sceneGroupPath,
            String bookSlug) {
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
                // Search BOTH homes, legacy first. A pre-per-book-world book keeps its characters
                // in the PB1 <book>/Characters group; every book created since keeps them in the
                // book WORLD's Population group (createCharPerson B4 routes them there). Looking
                // only in the PB1 group made new books resolve nothing: measured 2026-09-14,
                // "The Big Way Out.pdf" had 0 charPersons in PB1 Characters and all 6 in the
                // world's Population, so every scene logged "Could not resolve scene character",
                // Stage 1 produced 0 portraits, and the composite ran with refs=0 — the reported
                // "composites no longer use the portraits".
                String charGroupPath = sceneGroupPath.replace("/Scenes", "/Characters");
                BaseRecord charGrp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                        ModelNames.MODEL_GROUP, charGroupPath, GroupEnumType.DATA.toString(),
                        (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
                if (charGrp != null) {
                    cp = findCharPersonByNameInGroup(user, cname, charGrp);
                }
                if (cp == null) {
                    BaseRecord popGrp = findBookPopulationGroup(user, sceneGroupPath, bookSlug);
                    if (popGrp != null) {
                        cp = findCharPersonByNameInGroup(user, cname, popGrp);
                        if (cp != null) {
                            logger.info("Resolved scene character '" + cname
                                    + "' from the book world's Population group");
                        }
                    }
                    else if (charGrp == null) {
                        logger.warn("No Characters group at " + charGroupPath
                                + " and no book-world Population group, while resolving scene character '"
                                + cname + "'");
                    }
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
        /// The RAW stored sdPrompt, kept separately because portraitPrompt below is reassigned to
        /// physicalDescription when it is blank - so it can no longer answer "what does the record
        /// actually store?", which is what the hand-edit check needs.
        String storedSdPrompt = null;
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
            storedSdPrompt = portraitPrompt;
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

        /// Deliberately NOT a bail-out any more. Both of these are the frozen extraction-time
        /// strings, and the record-driven path below does not need either of them: a character with
        /// no stored description at all is still fully describable from its own record (appearance,
        /// statistics, apparel). Returning null here skipped that character's portrait entirely —
        /// which is how the protagonist of "The Big Way Out", the one character with no
        /// pbDescription, ended up as the only one whose likeness the composite did not preserve.
        boolean noStoredDescription = (portraitPrompt == null || portraitPrompt.isBlank()) && pbDescription == null;
        if (noStoredDescription) {
            logger.info("Character " + cname + " has no stored portrait prompt or reduced description "
                    + "- deriving it from the record instead");
        }

        String sceneNarration;
        // THE RECORD IS THE SOURCE OF TRUTH. Rebuild the narrative (appearance + statistics + outfit)
        // from the persisted charPerson, write it back, and use it — so an edit made in the Manage
        // Characters screen AFTER extraction (statistics sliders, outfit builder, apparel, or the
        // generic editor) is actually what gets imaged. See refreshNarrativeFromRecord for the full
        // account of why the three frozen LLM strings below could never be right.
        //
        // The frozen strings remain as an ordered FALLBACK for the cases the record cannot serve: a
        // character whose full re-read fails, or one too sparse to describe (no apparel, no
        // statistics, no personality). A book built before this change needs no migration — its
        // records already carry everything this derives from.
        RecordNarrative fromRecord = refreshNarrativeFromRecord(user, cp, cname);
        if (fromRecord != null) {
            sceneNarration = fromRecord.narration;
            /// A hand-written sdPrompt WINS for the portrait render (persistRefreshedNarrative
            /// leaves it untouched and says so); otherwise the render base is the record-derived
            /// narration with the same quality preamble the Attr2 path used.
            portraitPrompt = isHandWrittenPrompt(storedSdPrompt)
                    ? storedSdPrompt
                    : PORTRAIT_QUALITY_PREAMBLE + fromRecord.narration;
        } else if (pbDescription != null) {
            // Attr2 fallback: one style/setting-free visual description drives BOTH the composite
            // charNarration and (with a quality preamble) the portrait render base.
            logger.warn("Character " + cname + ": using the frozen pbDescription attribute - "
                    + "post-extraction character edits will NOT be reflected in this image");
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
            if (sceneNarration == null || sceneNarration.isBlank()) {
                /// Record-driven failed AND there is no stored description of any kind. This is the
                /// one case where there is genuinely nothing to render this character from.
                logger.warn("No portrait prompt, reduced description, or describable record for: "
                        + cname + " - skipping portrait");
                return null;
            }
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
     * Is a landscape reference image worth producing for this scene at all?
     *
     * <p>Two independent reasons it is not, and BOTH cost the same when ignored: one LLM call
     * ({@link #resolveLandscapePrompt}) plus one full SD pass (Stage 2 of
     * {@link #generateSceneImage}), per scene.
     *
     * <ol>
     * <li><b>{@code skipLandscape} on the config</b> — the explicit switch. It already existed on
     *     {@code olio.sd.config} and was already honored by {@code ChatService.generateScene}; the
     *     picture-book pipeline simply never read it, so the toggle appeared to exist and did
     *     nothing here. {@code pinPictureBookDefaults} in Ux752 now pins it TRUE for new books,
     *     because with the FLUX.2 composite the landscape is not currently being used.</li>
     * <li><b>The composite mode will not consume it</b> — {@code compositeMode=flux2} with
     *     {@code flux2IncludeLandscapeRef=false}. {@code SceneCompositeUtil.buildSceneRequest}
     *     discards the landscape bytes in that case (and logs that it did), so generating them is
     *     pure waste. KONTEXT stitches the landscape into its panel strip and CLASSIC draws the
     *     portraits on top of it, so neither is ever auto-skipped.</li>
     * </ol>
     *
     * <p>Mode resolution goes through {@link SceneCompositeUtil#resolveMode} with
     * {@code legacyKontextDefault=false} — the same precedence {@code generateSceneImage}'s own
     * inline branch uses (compositeMode wins; a genuinely-cleared compositeMode falls back to the
     * legacy {@code useKontext} boolean, default false).
     *
     * <p>Public rather than private so the decision can be asserted directly without standing up
     * SD/LLM backends (same reason as {@link #imagingDescription}). Pure function, no IO.
     */
    public static boolean landscapeEnabled(BaseRecord sdConfig) {
        if (sdConfig != null) {
            Boolean skip = null;
            try { skip = sdConfig.get("skipLandscape"); } catch (Exception e) { /* field may not exist */ }
            if (skip != null && skip.booleanValue()) return false;
        }
        String mode = SceneCompositeUtil.resolveMode(sdConfig, false);
        return SceneCompositeUtil.includesLandscapeReference(mode, sdConfig);
    }

    /**
     * Human-readable reason {@link #landscapeEnabled} said no — logged so a scene that rendered
     * without a setting reference is never a silent mystery.
     */
    public static String landscapeSkipReason(BaseRecord sdConfig) {
        if (sdConfig != null) {
            Boolean skip = null;
            try { skip = sdConfig.get("skipLandscape"); } catch (Exception e) { /* field may not exist */ }
            if (skip != null && skip.booleanValue()) return "skipLandscape=true";
        }
        return "compositeMode=" + SceneCompositeUtil.resolveMode(sdConfig, false)
                + " will not consume a landscape reference (flux2IncludeLandscapeRef=false)";
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
            // Style is deliberately NOT given a REAL value to the LLM — it's a discrete, code-owned
            // fact appended via appendConfigStyleOnce(sdConfig) below; feeding a real {style} made the
            // LLM emit its own competing "cinematic photograph style" on top of the config style
            // (double/conflicting style). BUT callLlmInternal's UNSUBSTITUTED_PLACEHOLDER guard HARD-
            // refuses any call whose composed template still contains an unfilled "{name}", and the
            // landscape template's user section carries a "STYLE: {style}" line — so omitting the var
            // entirely hard-failed EVERY landscape prompt ("Refusing to call LLM for prompt
            // 'pictureBook.landscape-prompt' — ... first: '{style}'"). Supply an EMPTY style so the
            // placeholder resolves (no style signal reaches the LLM, so no competing style) while the
            // real style stays owned by appendConfigStyleOnce. Passing it here — rather than relying on
            // the template no longer having the line — makes the fix robust whether the template
            // resolves from a user/system DB promptTemplate record or the classpath fallback.
            landVars.put("style", "");
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
    private static Map<String, Object> parseLlmJsonObject(String response, String context, List<String> failedExtractions) {
        return parseLlmJsonObject(response, context, failedExtractions, null);
    }

    /**
     * Parse a JSON object out of an LLM response, salvaging the common ways a model mangles it.
     * <p>
     * <b>okOut distinguishes "parsed to nothing" from "could not parse".</b> This method returns an
     * empty map for BOTH (callers rely on never getting null back), which conflated a legitimate
     * {@code {}} — "this chunk contained no new scenes", a correct and common answer — with a parse
     * failure. In the chunk loop that meant burning a second ~90s LLM round and recording a bogus
     * failure for a response that was actually fine. When {@code okOut} is supplied, {@code okOut[0]}
     * is set true if the text genuinely parsed (even to an empty object) and false only on real
     * failure, so a retry is spent only when a retry could help.
     *
     * @param okOut optional single-element array receiving whether parsing succeeded
     */
    /// Package-private (not private) so the salvage logic can be unit tested directly from a
    /// same-package test, matching the BookContextTestAccess convention. Nothing production-side
    /// outside this package can reach it.
    static Map<String, Object> parseLlmJsonObject(String response, String context,
            List<String> failedExtractions, boolean[] okOut) {
        if (okOut != null && okOut.length > 0) okOut[0] = false;
        if (response == null || response.isEmpty()) {
            recordFailedExtraction(failedExtractions, context, "LLM returned no content", response);
            return new LinkedHashMap<>();
        }
        String trimmed = stripCodeFences(stripThink(response.trim()));
        String[] err = new String[1];
        /// Try each '{' in turn rather than committing to the first one. A conversational preamble
        /// can itself contain braces ("I will return a {json} object now:"), and the first brace is
        /// then a decoy that parses to nothing useful; the real payload is further along.
        int searchFrom = 0;
        int candidates = 0;
        boolean sawBrace = false;
        while (candidates < MAX_JSON_OBJECT_CANDIDATES) {
            int start = trimmed.indexOf('{', searchFrom);
            if (start < 0) break;
            sawBrace = true;
            candidates++;
            searchFrom = start + 1;

            /// Prefer the BALANCED close brace over lastIndexOf('}'). On a truncated response the
            /// last '}' in the text belongs to some inner object, so slicing to it yields a
            /// structurally unbalanced fragment no parser can read — which is what made a
            /// token-limit truncation look like an unparseable model.
            int end = findBalancedEnd(trimmed, start);
            String candidate;
            boolean repaired = false;
            if (end >= 0) {
                candidate = trimmed.substring(start, end + 1);
            } else {
                candidate = repairTruncatedJson(trimmed.substring(start));
                repaired = true;
            }

            Map<String, Object> parsed = JSONUtil.getLenientMap(candidate.getBytes(StandardCharsets.UTF_8),
                String.class, Object.class, err);
            if (parsed == null && !repaired) {
                /// Structurally balanced but still unreadable (an unterminated string inside an
                /// otherwise-closed object, say) — try the repair pass before giving up.
                String second = repairTruncatedJson(candidate);
                if (!second.equals(candidate)) {
                    parsed = JSONUtil.getLenientMap(second.getBytes(StandardCharsets.UTF_8),
                        String.class, Object.class, err);
                    repaired = parsed != null;
                }
            }
            if (parsed != null) {
                if (repaired) {
                    logger.warn("Recovered " + (context != null ? context : "LLM JSON")
                        + " by repairing a truncated/malformed response (" + parsed.size() + " top-level keys)");
                }
                if (okOut != null && okOut.length > 0) okOut[0] = true;
                return parsed;
            }
        }
        if (!sawBrace) {
            recordFailedExtraction(failedExtractions, context, "No JSON object ({...}) found in LLM response", response);
            return new LinkedHashMap<>();
        }
        /// Report the parser's OWN message. The previous code stored "JSON object parse returned
        /// null" because JSONUtil.getMap swallowed the IOException, which told an investigator
        /// nothing about what was actually wrong with the response.
        recordFailedExtraction(failedExtractions, context,
            err[0] != null ? err[0] : "JSON object could not be parsed", response);
        return new LinkedHashMap<>();
    }

    /// Bound on how many '{' positions the salvage will try before giving up, so a long prose
    /// reply full of braces cannot turn one failed chunk into an unbounded number of parse attempts.
    private static final int MAX_JSON_OBJECT_CANDIDATES = 5;

    /**
     * Remove markdown code fences anywhere in the text, not only at the very start.
     * <p>
     * The old check was {@code trimmed.startsWith("```")}, so a reply opening with a preamble
     * ("Here is the JSON:") kept its fences and the brace slice had to rescue it — which fails
     * outright when the preamble itself contains a brace. JSON never legitimately contains a triple
     * backtick, so stripping every fence marker (plus an immediately following language tag such as
     * {@code json}) is safe.
     */
    static String stripCodeFences(String s) {
        if (s == null || s.indexOf("```") < 0) return s;
        return s.replaceAll("```[ \t]*[A-Za-z0-9_+-]*[ \t]*\r?\n?", "").replace("```", "").trim();
    }

    /**
     * Index of the brace closing the object that opens at {@code start}, or -1 when the text ends
     * first (i.e. the response was truncated). String contents and backslash escapes are honoured
     * so a brace inside a quoted value does not shift the depth count.
     */
    static int findBalancedEnd(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) { esc = false; }
                else if (c == '\\') { esc = true; }
                else if (c == '"') { inStr = false; }
                continue;
            }
            if (c == '"') { inStr = true; }
            else if (c == '{' || c == '[') { depth++; }
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return i;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    /**
     * Close the dangling structures of a truncated JSON object so it can be parsed.
     * <p>
     * Generation stopping at the context/token ceiling is the likeliest cause of an "unparseable"
     * extraction chunk, and it gets likelier as a run proceeds: the chunk prompt carries every
     * previously identified scene forward, so the reply budget shrinks chunk by chunk. (Observed
     * live: the failure landed on chunk 10 of 17, not chunk 1.) Recovering the scenes the model DID
     * emit is worth far more than discarding a ~90s generation.
     * <p>
     * Terminates an unterminated string, drops a trailing partial token or separator, then closes
     * the open brackets in the order the tracking stack recorded them.
     */
    static String repairTruncatedJson(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder stack = new StringBuilder();
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) { esc = false; }
                else if (c == '\\') { esc = true; }
                else if (c == '"') { inStr = false; }
                continue;
            }
            if (c == '"') { inStr = true; }
            else if (c == '{') { stack.append('}'); }
            else if (c == '[') { stack.append(']'); }
            else if (c == '}' || c == ']') {
                if (stack.length() > 0) stack.setLength(stack.length() - 1);
            }
        }
        StringBuilder out = new StringBuilder(s);
        /// An unterminated string: close the quote. A dangling escape would escape that very quote,
        /// so drop the lone backslash first.
        if (inStr) {
            if (esc) out.setLength(out.length() - 1);
            out.append('"');
        }
        /// Trim a trailing separator or partial token so the close brackets attach to valid JSON.
        /// ALLOW_TRAILING_COMMA covers the comma case, but a dangling ":" does not parse.
        int cut = out.length();
        while (cut > 0) {
            char c = out.charAt(cut - 1);
            if (c == ',' || c == ':' || Character.isWhitespace(c)) { cut--; }
            else { break; }
        }
        out.setLength(cut);
        /// A key whose value never arrived ({@code "revisions":}) leaves an orphan key once that
        /// colon is trimmed, and {@code {"a":[],"b"}} is invalid under every leniency flag
        /// (ALLOW_MISSING_VALUES covers array holes, not object members). Drop the orphan back to
        /// the preceding comma, or to the opening brace when it is the only member.
        if (out.length() > 0 && out.charAt(out.length() - 1) == '"') {
            int q = findStringStart(out, out.length() - 1);
            if (q > 0) {
                int j = q - 1;
                while (j >= 0 && Character.isWhitespace(out.charAt(j))) { j--; }
                /// Only a member-position string is an orphan key. One preceded by ':' is a
                /// completed VALUE ({@code {"a":"x"}) and must be kept.
                if (j >= 0 && out.charAt(j) == ',') { out.setLength(j); }
                else if (j >= 0 && out.charAt(j) == '{') { out.setLength(j + 1); }
            }
        }
        out.append(stack.reverse());
        return out.toString();
    }

    /// Index of the opening quote of the string whose closing quote is at {@code closeQuote},
    /// honouring backslash escapes, or -1 if it cannot be located.
    private static int findStringStart(CharSequence s, int closeQuote) {
        for (int i = closeQuote - 1; i >= 0; i--) {
            if (s.charAt(i) != '"') { continue; }
            int back = i - 1;
            int slashes = 0;
            while (back >= 0 && s.charAt(back) == '\\') { slashes++; back--; }
            if (slashes % 2 == 0) { return i; }
        }
        return -1;
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

    /// Package-private bridge for ChapBookUtil — delegates directly to callLlmInternal without
    /// the promptTemplateOverride mechanism, which is a PictureBook-specific wizard feature.
    static String callLlmForChapBook(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
        return callLlmInternal(user, chatConfig, promptName, vars);
    }

    /// Package-private bridge for ChapBookUtil — variant that reports whether the LLM step HARD-failed
    /// (config/infra error, i.e. the LLM genuinely could not be used) vs. merely SOFT-declined (the
    /// LLM ran but produced no usable content for this input). hardFailureOut[0] is set true only on
    /// hard branches; a return value of null with hardFailureOut[0] still false is a soft decline.
    static String callLlmForChapBook(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars, boolean[] hardFailureOut) {
        return callLlmInternal(user, chatConfig, promptName, vars, hardFailureOut);
    }

    /// Package-private bridge for ChapBookUtil — exposes parseLlmJsonObject for non-PB callers
    /// in the same package that need structured LLM output parsed the same way.
    static Map<String, Object> parseLlmJsonObjectForChapBook(String response, String context, List<String> failedExtractions) {
        return parseLlmJsonObject(response, context, failedExtractions);
    }

    private static String callLlmInternal(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars) {
        return callLlmInternal(user, chatConfig, promptName, vars, new boolean[1]);
    }

    /// Loop-friendly variant: reuses a ResolvedPrompt obtained once via {@link #resolvePrompt}
    /// instead of re-resolving the template on every call. See ResolvedPrompt's javadoc for why
    /// that matters in the chunk loop.
    private static String callLlmResolved(BaseRecord user, BaseRecord chatConfig, String promptName,
            Map<String, String> vars, ResolvedPrompt resolved) {
        return callLlmInternal(user, chatConfig, promptName, vars, new boolean[1], resolved);
    }

    /// 5-arg variant that reports HARD failure separately from the String return value.
    ///
    /// Motivation (ChapBook "issue #3"): the 4-arg method collapses SIX distinct outcomes into a
    /// single `null` return — three of them SOFT (the LLM ran but declined/blanked for THIS input,
    /// a normal per-content result) and three of them HARD (missing template, no chat config, or an
    /// infrastructure exception — the LLM genuinely could not be used at all). Callers that render on
    /// a fallback prompt cannot tell "the LLM had nothing to add here" from "the LLM is down", so the
    /// render silently degrades and the UI never learns.
    ///
    /// hardFailureOut[0] is set true ONLY on the hard branches:
    ///   - "Prompt template not found"        (config)
    ///   - unsubstituted-placeholder guard    (malformed construction — refuse to call)
    ///   - "No chat config available"         (config)
    ///   - "Null LLM response"                (infra)
    ///   - exception thrown by chat.chat(...) (infra)
    /// It is left FALSE for the SOFT outcomes (conversational refusal; blank/think-only stripped-empty
    /// content) and on success. It is caller-initialized; this method only ever sets it to true.
    private static String callLlmInternal(BaseRecord user, BaseRecord chatConfig, String promptName, Map<String, String> vars, boolean[] hardFailureOut) {
        return callLlmInternal(user, chatConfig, promptName, vars, hardFailureOut,
            resolvePrompt(user, chatConfig, promptName));
    }

    /**
     * A resolved + composed prompt template pair, ready for variable substitution.
     * <p>
     * Resolution is stable for the life of a run: it depends only on the prompt name, the calling
     * user and the chat config, none of which change mid-loop. Callers that make MANY LLM calls
     * with the same prompt name should therefore {@link #resolvePrompt} once and pass the result
     * into {@link #callLlmInternal(BaseRecord, BaseRecord, String, Map, boolean[], ResolvedPrompt)}
     * rather than re-resolving per call.
     */
    static final class ResolvedPrompt {
        final String system;
        final String userTpl;
        ResolvedPrompt(String system, String userTpl) {
            this.system = system;
            this.userTpl = userTpl;
        }
        boolean isUsable() {
            return system != null && userTpl != null;
        }
    }

    /**
     * Resolve and compose the prompt template pair for {@code promptName}.
     * <p>
     * The lookup chain inside {@link ChatUtil#resolveConfig} is deliberate and must not be
     * shortened: it checks the calling user's own group FIRST and the shared system library
     * SECOND, so a user can override a shared prompt with their own copy, and it carries
     * backwards compatibility for the older {@code PromptConfig} model that {@code PromptTemplate}
     * replaced. A user who has not overridden the prompt simply has no own copy, so the first
     * query legitimately returns zero rows — which {@code AccessPoint.find} closes as
     * {@code AUDIT INVALID ... No results} and {@code AuditUtil.print} logs at WARN because it is
     * not a PERMIT. That WARN is an expected miss, NOT an authorization failure (a real one logs
     * {@code "One or more query fields were not or could not be authorized"} and prints
     * {@code [unknown resource]} instead).
     * <p>
     * What WAS wrong is that this ran once per LLM call. A 17-chunk extraction re-resolved and
     * re-composed the same template 17 times — 34 template queries and 17 of those expected-miss
     * WARNs — for a value that cannot change during the run. Hoisting it to once per run is why
     * this method exists; {@code extractScenesOnly} already resolves its chat config exactly once
     * in the same way.
     */
    static ResolvedPrompt resolvePrompt(BaseRecord user, BaseRecord chatConfig, String promptName) {
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
        return new ResolvedPrompt(system, userTpl);
    }

    /// Variant taking an already-resolved template pair, so a caller making many LLM calls with the
    /// same prompt name resolves and composes ONCE instead of per call. Everything from variable
    /// substitution onward is per-call and stays here. Behaviour is otherwise identical to the
    /// resolving overload, including which branches set hardFailureOut[0].
    private static String callLlmInternal(BaseRecord user, BaseRecord chatConfig, String promptName,
            Map<String, String> vars, boolean[] hardFailureOut, ResolvedPrompt resolved) {
        if (resolved == null || !resolved.isUsable()) {
            logger.warn("Prompt template not found: " + promptName);
            hardFailureOut[0] = true; // HARD: misconfiguration — the LLM step cannot run at all.
            return null;
        }
        String system = resolved.system;
        /// Local copy: substitution rewrites this per call, and `resolved` is shared across calls.
        String userTpl = resolved.userTpl;
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
            hardFailureOut[0] = true; // HARD: malformed construction — refuse the call, don't degrade silently.
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
                hardFailureOut[0] = true; // HARD: no config resolvable — the LLM step cannot run.
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
            	hardFailureOut[0] = true; // HARD: infra — a request was made but the service returned nothing.
            }
        } catch (Exception e) {
            logger.error("LLM call failed for " + promptName + ": " + e.getMessage());
            hardFailureOut[0] = true; // HARD: infra — the call threw (unreachable host, timeout, etc.).
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
    /**
     * How many of the most recent scenes are carried forward in FULL detail. Older ones go as
     * title-only.
     *
     * <p>Measured 2026-09-13 on a 17-chunk document: by chunk 7 the request had reached ~15.8KB
     * against a {@code num_ctx} of 8192, and the calls degraded 112s -> 259s -> two 305s timeouts,
     * at which point the circuit breaker stopped the run a third of the way through the story. The
     * accumulated scene list is the part that grows; the chunk itself is only ~2KB.
     *
     * <p>Title-only for older scenes is not an arbitrary truncation — <b>titles are the match key</b>
     * for {@code revisions} and {@code removals} (see {@link #mergeChunkResult}), so keeping every
     * title keeps every earlier scene addressable, while the detail the model actually needs for
     * continuity is the recent run of scenes. Dropping older scenes entirely would silently make
     * them un-revisable and invite duplicates.
     */
    static final int PROMPT_SCENE_DETAIL_WINDOW = 6;

    /**
     * A failed LLM call that returns in under this long is treated as INFRASTRUCTURE FAILURE —
     * the server is gone (refused, unreachable, shutting down), because a real model does not
     * decline in a couple of seconds. Anything slower is the model being slow, which is a
     * completely different situation.
     *
     * <p>5s is deliberately far below any plausible generation time and far above any plausible
     * connection failure: a refused or unroutable host fails in milliseconds, while the slowest
     * observed SUCCESSFUL chunk on this hardware took 294s. There is no realistic value in
     * between for this to get wrong.
     *
     * <p>This distinction is load-bearing, and getting it wrong caused a regression. The circuit
     * breaker below exists so a Tomcat shutdown does not spend 40 minutes calling a server that
     * has gone away. It was originally tripped by any two consecutive empty replies — which also
     * matched a perfectly alive model that had merely timed out twice. Measured 2026-09-14: a
     * 17-chunk run against a loaded DGX Spark hit two 300s timeouts at chunk 13 and the breaker
     * aborted the document with 5 chunks to go, where the previous behaviour was to record the
     * failures and carry on to the end. Slow is not dead.
     */
    static final long LLM_INFRA_FAILURE_MS = 5000L;

    /**
     * Scene characters whose name does NOT occur in the passage the scene was extracted from.
     *
     * <p><b>What this catches.</b> A scene's cast is whatever the LLM asserted; nothing ever checked
     * it against the text the scene came from. Observed on "BWO 3": scenes 12-13 sit inside a
     * fourteen-scene stretch belonging to Veronique but name Yolanda, who otherwise appears only
     * from scene 28 - a name borrowed from a different part of the book. Both the cast AND the
     * action text named her, so nothing downstream could tell it was wrong, and the scene rendered
     * the wrong person.
     *
     * <p><b>Why this only REPORTS.</b> A character can be legitimately present without being named
     * in the passage - pronouns, "her mother", an unnamed speaker - so an absent name is evidence,
     * not proof, and dropping the character on it would silently delete correct data. The scenes it
     * flags are exactly the ones worth a human glance, which is what the merge tooling is for.
     *
     * <p>Matching is accent- and case-insensitive on any name token longer than two characters, so
     * "Darby's dad" is satisfied by a passage naming "Darby", while "Veronique" is not satisfied by
     * "Yolanda". Short tokens and {@link #NAME_STOPWORDS} are skipped so an initial or an article
     * cannot match everything.
     *
     * @return the uncorroborated names in scene order; empty when the scene carries no source
     *         passage (nothing to check against) or every name is present
     */
    public static List<String> charactersNotInSourceText(Map<String, Object> scene) {
        List<String> missing = new ArrayList<>();
        if (scene == null) return missing;
        Object stObj = scene.get("sourceText");
        if (!(stObj instanceof String)) return missing;
        String block = stripAccentsLower((String) stObj);
        if (block.isBlank()) return missing;
        for (String name : sceneCharacterNames(scene)) {
            boolean found = false;
            for (String tok : stripAccentsLower(name).split("[^a-z0-9]+")) {
                if (tok.length() <= 2 || NAME_STOPWORDS.contains(tok)) continue;
                if (block.contains(tok)) { found = true; break; }
            }
            if (!found) missing.add(name);
        }
        return missing;
    }

    /**
     * Record the two extraction-quality diagnostics on a book's meta: which duplicate spellings were
     * folded together, and which scene cast members the source passage does not corroborate.
     *
     * <p>Shared by both {@code createFromScenes} overloads. They are near-identical copies of one
     * another, and that is exactly how the PB1 path ended up with name canonicalisation while the
     * PB2 path - the one every book with a world actually takes - silently did not.
     */
    private static void recordExtractionDiagnostics(BaseRecord meta, Map<String, String> nameAliases,
            List<String> unverifiedSceneCharacters) {
        if (meta == null) return;
        if (unverifiedSceneCharacters != null && !unverifiedSceneCharacters.isEmpty()) {
            /// Surfaced rather than logged only: this is the signal that a scene's cast came from
            /// somewhere other than its own passage, and the person who can judge it is the one who
            /// read the book.
            try { meta.set("unverifiedSceneCharacters", unverifiedSceneCharacters); }
            catch (Exception e) { logger.warn("Failed to record unverifiedSceneCharacters on meta: " + e.getMessage()); }
        }
        if (nameAliases != null && !nameAliases.isEmpty()) {
            List<String> merged = new ArrayList<>();
            for (Map.Entry<String, String> e : nameAliases.entrySet()) merged.add(e.getKey() + " -> " + e.getValue());
            try { meta.set("mergedCharacterNames", merged); }
            catch (Exception e) { logger.warn("Failed to record mergedCharacterNames on meta: " + e.getMessage()); }
        }
    }

    /**
     * Run {@link #charactersNotInSourceText} over a whole scene list, logging each finding and
     * returning them as {@code "scene N (title): name"} for the book meta.
     */
    public static List<String> collectUnverifiedSceneCharacters(List<Map<String, Object>> sceneList) {
        List<String> out = new ArrayList<>();
        if (sceneList == null) return out;
        for (int si = 0; si < sceneList.size(); si++) {
            Map<String, Object> scene = sceneList.get(si);
            for (String miss : charactersNotInSourceText(scene)) {
                out.add("scene " + si + " (" + (scene != null ? scene.get("title") : null) + "): " + miss);
                logger.warn("Scene " + si + " '" + (scene != null ? scene.get("title") : null)
                        + "' lists character '" + miss + "', but that name does not appear in the"
                        + " passage this scene was extracted from - it may have been carried over"
                        + " from a different part of the work");
            }
        }
        return out;
    }

    /**
     * The character names on ONE scene, tolerating both persisted shapes ({@code {name:...}} map,
     * bare string) and screening LLM placeholder values.
     *
     * <p>Shared by {@link #scenesForPrompt} and {@link #knownCharacterNames} so the reduced prompt
     * entry and the roster cannot disagree about who is in a scene.
     */
    @SuppressWarnings("unchecked")
    public static List<String> sceneCharacterNames(Map<String, Object> scene) {
        List<String> out = new ArrayList<>();
        Object charsObj = (scene != null) ? scene.get("characters") : null;
        if (!(charsObj instanceof List)) return out;
        for (Object sc : (List<Object>) charsObj) {
            String cn = (sc instanceof Map) ? (String) ((Map<String, Object>) sc).get("name")
                    : (sc instanceof String ? (String) sc : null);
            if (cn == null) continue;
            String t = cn.trim();
            /// isMeaningful, not !isBlank: these came out of an LLM and "null"/"unknown" turn up as
            /// VALUES, and telling the model that "null" is an established character is worse than
            /// telling it nothing.
            if (NarrativeUtil.isMeaningful(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * The distinct character names established so far, in first-seen order — the roster threaded
     * into the chunk-extraction prompt as {@code {knownCharacters}}.
     *
     * <p><b>Why a separate roster rather than relying on {@code previousScenes}.</b>
     * {@link #scenesForPrompt} reduces every scene older than the last
     * {@value #PROMPT_SCENE_DETAIL_WINDOW} to its TITLE ALONE, deliberately, to stop the prompt
     * growing O(n²). Characters are part of the detail that gets dropped — so by chunk 12 the model
     * can no longer see the name chunk 1 used for a character, and invents a fresh one. That is the
     * mechanism behind reported issue 3: an unnamed character arrives as "Darby's dad", then "the
     * father", then "Dad".
     *
     * <p>Names only, so it stays cheap where the scene detail could not: a 58-scene book with a
     * dozen characters is a couple of hundred bytes, and it is COMPLETE rather than windowed.
     *
     * <p>{@link #canonicalizeSceneCharacterNames} still runs afterwards — this reduces how many
     * duplicates the model produces, it does not guarantee zero.
     */
    public static List<String> knownCharacterNames(List<Map<String, Object>> scenes) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (scenes == null) return new ArrayList<>();
        for (Map<String, Object> s : scenes) names.addAll(sceneCharacterNames(s));
        return new ArrayList<>(names);
    }

    public static List<Map<String, Object>> scenesForPrompt(List<Map<String, Object>> scenes) {
        List<Map<String, Object>> out = new ArrayList<>(scenes.size());
        /// Index of the first scene that still gets full detail.
        int detailFrom = Math.max(0, scenes.size() - PROMPT_SCENE_DETAIL_WINDOW);
        for (int i = 0; i < scenes.size(); i++) {
            Map<String, Object> s = scenes.get(i);
            Map<String, Object> c = new LinkedHashMap<>();
            if (i < detailFrom) {
                /// Older scene: title, plus the NAMES of who is in it. Everything else is dropped -
                /// it no longer costs ~1.5KB of context it is not contributing anything to.
                ///
                /// The characters used to be dropped as well, and that left the model blind in a way
                /// that corrupted data. An older scene is still addressable by title for a
                /// revision/removal, so the model can rewrite a scene it can no longer see the cast
                /// of - and a revision overwrites `characters` wholesale. Observed on "BWO 3":
                /// scenes 12-13 ("The Budget Interface"/"The Budget Explanation") sit in the middle
                /// of a fourteen-scene stretch belonging to Veronique, but name Yolanda, who
                /// otherwise appears only from scene 28 onward. A name from a distant part of the
                /// book landed on two scenes in the middle of someone else's section.
                ///
                /// Names only, so this stays affordable: a dozen characters is a couple of hundred
                /// bytes against the ~1.5KB per scene the reduction exists to avoid.
                Object t = s.get("title");
                if (t != null) c.put("title", t);
                List<String> who = sceneCharacterNames(s);
                if (!who.isEmpty()) c.put("characters", who);
                /// Skip a titleless older scene entirely — an empty object would waste tokens and
                /// could not be matched against anyway.
                if (!c.containsKey("title")) continue;
            } else {
                for (String f : PROMPT_SCENE_FIELDS) {
                    Object v = s.get(f);
                    if (v != null) c.put(f, v);
                }
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Merge one chunk's {@code additions} / {@code revisions} / {@code removals} into the running
     * scene list, in that order.
     *
     * <p>Extracted from the chunk loop so it is directly testable. It is the part of extraction
     * that RESUME correctness depends on: revisions and removals are matched <b>by title</b>
     * against scenes that, after a resume, came back from a checkpoint rather than from this
     * process's memory. A test that re-implements this matching proves only that JSON round-trips
     * titles; it cannot catch a bug in the matching itself.
     *
     * @param sceneList   the running list, mutated in place
     * @param chunkResult the parsed LLM reply for this chunk
     * @param chunkText   the passage this chunk covers, attached to new scenes as the transient
     *                    {@code sourceText}
     * @param chunkIndex  0-based chunk number, stored as {@code sourceChunk}
     */
    @SuppressWarnings("unchecked")
    static void mergeChunkResult(List<Map<String, Object>> sceneList, Map<String, Object> chunkResult,
            String chunkText, int chunkIndex) {
        if (sceneList == null || chunkResult == null) return;

        Object addObj = chunkResult.get("additions");
        if (addObj instanceof List) {
            List<Map<String, Object>> additions = (List<Map<String, Object>>) addObj;
            for (Map<String, Object> scene : additions) {
                if (scene == null) continue;
                scene.put("index", sceneList.size());
                scene.put("userEdited", false);
                // Track the raw content block this scene (and thus its characters) was obtained
                // from — the passage where those characters actually appear. Transient carrier on
                // the in-memory scene map; used by createFromScenes to REDUCE per-character detail
                // from the right text, and stripped before the scene note is persisted
                // (createSceneNote) so it never bloats storage.
                scene.put("sourceText", chunkText);
                // Durable counterpart to the transient sourceText above: the checkpoint persists
                // this integer instead of the ~2000-char passage and rehydrates sourceText from it
                // on resume.
                scene.put("sourceChunk", chunkIndex);
                sceneList.add(scene);
            }
        }

        Object revObj = chunkResult.get("revisions");
        if (revObj instanceof List) {
            List<Map<String, Object>> revisions = (List<Map<String, Object>>) revObj;
            for (Map<String, Object> rev : revisions) {
                if (rev == null) continue;
                String revTitle = (String) rev.get("title");
                if (revTitle == null) continue;
                for (int si = 0; si < sceneList.size(); si++) {
                    String existingTitle = (String) sceneList.get(si).get("title");
                    if (revTitle.equals(existingTitle)) {
                        Map<String, Object> existing = sceneList.get(si);
                        for (Map.Entry<String, Object> e : rev.entrySet()) {
                            /// Never overwrite the title (it is the match key) and never clobber a
                            /// good value with a null the model happened to emit.
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
    }

    /**
     * The per-chunk LLM call, as a seam.
     *
     * <p>Exists so the chunk loop's control flow can be tested without an LLM. That flow is where
     * the expensive defects have actually lived — an interrupted run deleting its own checkpoint
     * and reporting COMPLETED, and a stopped run reporting {@code extractionComplete: true} — and
     * neither was reachable by any unit test, because every path through the loop needed a live
     * model server. Both were found only by running the real thing against Docker, which is a slow
     * and unreliable way to discover a branch bug.
     *
     * <p>Production passes {@link #defaultChunkLlm}; tests pass a script of canned replies,
     * including nulls (to exercise the circuit breaker) and truncated JSON (to exercise salvage
     * through the real loop rather than only through {@code parseLlmJsonObject} in isolation).
     */
    interface ChunkLlm {
        /**
         * @param vars    the prompt variables for this attempt
         * @param attempt 1-based; attempt 2 carries the corrective "your last reply was truncated"
         *                instruction
         * @return the raw model reply, or null when nothing came back
         */
        String call(Map<String, String> vars, int attempt);
    }

    // ----- Extraction checkpointing (incremental persistence + resume) ---------

    /**
     * Name of the scratch {@code data.note} that holds an in-progress chunked extraction.
     *
     * <p>Mirrors the existing {@code .pictureBookMeta} convention (a dot-prefixed {@code data.note}
     * whose {@code text} field carries JSON — see {@link #saveMeta}), for the same reason: the
     * {@code text} field has no length limit, and a dot-prefixed name keeps it out of ordinary
     * document listings.
     */
    private static final String EXTRACT_PROGRESS_NOTE = ".pbExtractProgress";

    /**
     * Persist accumulated scenes every this many chunks.
     *
     * <p>Sized against the real cost ratio, not guessed: a chunk costs one LLM call at ~80-110s
     * measured, while a checkpoint is a single {@code data.note} update. The write is lost in the
     * noise even at every chunk, so this bounds how much work a crash can destroy rather than
     * trading off write cost.
     *
     * <p><b>Why 1 rather than 2.</b> The interrupt-time save is best-effort and was observed
     * LOSING ITS RACE with IO teardown during a {@code docker restart} — the process died before
     * the write landed, so the resume fell back to the last periodic checkpoint. With a period of
     * 2 that could discard a completed chunk; with 1, whatever the interrupt save fails to record
     * is at most the chunk that was still in flight, which produced nothing anyway. The periodic
     * write is the durable one; treat the early-exit saves as an optimisation, not the mechanism.
     */
    public static final int EXTRACT_CHECKPOINT_EVERY = 1;

    /**
     * A resumable snapshot of a chunked extraction in flight.
     *
     * <p><b>Why this exists.</b> Extraction accumulated every scene in memory and wrote nothing
     * until the final chunk, so a dropped connection or a container restart destroyed the entire
     * run. Measured 2026-09-13: a 17-chunk run reached chunk 11 and ~27 minutes of LLM output was
     * discarded with nothing logged. The async job layer keeps a finished result alive for a TTL,
     * which covers a lost <i>connection</i>; this covers a lost <i>process</i>.
     *
     * <p>{@code textHash}, {@code chunkSize} and {@code overlap} are the validity guard. A
     * checkpoint is only resumable against byte-identical source text chunked exactly the same
     * way — otherwise chunk index {@code n} no longer denotes the same passage and resuming would
     * silently splice scenes from one document into another.
     */
    static final class ExtractCheckpoint {
        String textHash;
        int chunkSize;
        int overlap;
        int totalChunks;
        /** Number of chunks whose results are already merged into {@link #scenes}. */
        int chunksProcessed;
        List<Map<String, Object>> scenes = new ArrayList<>();
        List<String> failedExtractions = new ArrayList<>();
    }

    /** Stable digest of the source text, used to invalidate a checkpoint when the document changes. */
    static String extractTextHash(String text) {
        return CryptoUtil.getDigestAsString(text == null ? "" : text);
    }

    /**
     * Locate the work's own group path, which is where its extraction checkpoint lives.
     *
     * <p>The checkpoint is deliberately keyed to the <b>source document</b> rather than to a book:
     * at extraction time no book exists yet (the book is created later, by
     * {@code createFromScenes}), so the document's group is the only stable home available.
     */
    static String findWorkGroupPath(BaseRecord user, String workObjectId) {
        if (workObjectId == null) return null;
        BaseRecord work = findWork(user, workObjectId);
        if (work == null) return null;
        try {
            String gp = work.get(FieldNames.FIELD_GROUP_PATH);
            if (gp != null && !gp.isEmpty()) return gp;
        } catch (Exception e) { /* model may not carry groupPath */ }
        return null;
    }

    /** Find the checkpoint note for a work document, or null. */
    static BaseRecord loadProgressNote(BaseRecord user, String groupPath, String workObjectId) {
        if (groupPath == null || workObjectId == null) return null;
        BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(),
                (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
        if (grp == null) return null;
        Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID,
                grp.get(FieldNames.FIELD_ID));
        q.field(FieldNames.FIELD_NAME, EXTRACT_PROGRESS_NOTE + "." + workObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
        q.planMost(true);
        /// A checkpoint is written and re-read repeatedly within one run, so a cached hit from
        /// before the last write would resume from a stale chunk index.
        q.setCache(false);
        return IOSystem.getActiveContext().getAccessPoint().find(user, q);
    }

    /**
     * Write (or overwrite) the extraction checkpoint. Best-effort by design: losing a checkpoint
     * costs re-extraction of a few chunks, whereas failing the run over a scratch-record write
     * would throw away work that is otherwise complete.
     *
     * <p>{@code sourceText} is stripped from persisted scenes and replaced by the integer chunk
     * index it came from ({@code sourceChunk}). It is a transient ~2000-char carrier per scene,
     * rehydrated on resume from the identical chunk list — so this keeps the note small without
     * losing the information {@code createFromScenes} needs for its per-character reduce.
     */
    static void saveExtractCheckpoint(BaseRecord user, String groupPath, String workObjectId,
            ExtractCheckpoint cp) {
        if (groupPath == null || workObjectId == null) return;
        /// A checkpoint with nothing processed is unresumable by construction —
        /// loadExtractCheckpoint rejects chunksProcessed <= 0 — so writing one leaves a scratch
        /// note that can never be consumed and is only ever removed by some later run that happens
        /// to reach the end. Stopping on the very first chunk (cancel, interrupt, or the circuit
        /// breaker) hits this exactly.
        if (cp == null || cp.chunksProcessed <= 0) {
            return;
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("workObjectId", workObjectId);
            out.put("textHash", cp.textHash);
            out.put("chunkSize", cp.chunkSize);
            out.put("overlap", cp.overlap);
            out.put("totalChunks", cp.totalChunks);
            out.put("chunksProcessed", cp.chunksProcessed);
            out.put("updatedAt", ZonedDateTime.now().toString());
            List<Map<String, Object>> slim = new ArrayList<>(cp.scenes.size());
            for (Map<String, Object> s : cp.scenes) {
                Map<String, Object> c = new LinkedHashMap<>(s);
                c.remove("sourceText");
                slim.add(c);
            }
            out.put("scenes", slim);
            out.put("failedExtractions", cp.failedExtractions);
            String json = JSONUtil.exportObject(out);

            BaseRecord existing = loadProgressNote(user, groupPath, workObjectId);
            if (existing != null) {
                existing.set("text", json);
                IOSystem.getActiveContext().getAccessPoint().update(user, existing);
                return;
            }
            ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
            plist.parameter(FieldNames.FIELD_NAME, EXTRACT_PROGRESS_NOTE + "." + workObjectId);
            BaseRecord rec = IOSystem.getActiveContext().getFactory().newInstance(
                    ModelNames.MODEL_NOTE, user, null, plist);
            rec.set("text", json);
            IOSystem.getActiveContext().getAccessPoint().create(user, rec);
        } catch (Exception e) {
            logger.warn("Failed to persist extraction checkpoint for " + workObjectId + ": " + e.getMessage());
        }
    }

    /**
     * Load a checkpoint that is valid for this exact text and chunking, or null.
     *
     * <p>Returns null — meaning "start over" — for a mismatched hash or chunking, a checkpoint that
     * claims more processed chunks than exist, or unparseable JSON. Every one of those is a case
     * where resuming would corrupt the result rather than accelerate it, so the safe answer is a
     * fresh run.
     */
    @SuppressWarnings("unchecked")
    static ExtractCheckpoint loadExtractCheckpoint(BaseRecord user, String groupPath,
            String workObjectId, String textHash, int chunkSize, int overlap, int totalChunks) {
        BaseRecord note = loadProgressNote(user, groupPath, workObjectId);
        if (note == null) return null;
        String json = note.get("text");
        if (json == null || json.isEmpty()) return null;
        try {
            Map<String, Object> m = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8),
                    String.class, Object.class);
            if (m == null || m.isEmpty()) return null;
            ExtractCheckpoint cp = new ExtractCheckpoint();
            cp.textHash = (String) m.get("textHash");
            cp.chunkSize = intOf(m.get("chunkSize"));
            cp.overlap = intOf(m.get("overlap"));
            cp.totalChunks = intOf(m.get("totalChunks"));
            cp.chunksProcessed = intOf(m.get("chunksProcessed"));
            if (textHash != null && !textHash.equals(cp.textHash)) {
                logger.info("Discarding extraction checkpoint for " + workObjectId
                        + " — source text changed since it was written");
                return null;
            }
            if (cp.chunkSize != chunkSize || cp.overlap != overlap || cp.totalChunks != totalChunks) {
                logger.info("Discarding extraction checkpoint for " + workObjectId
                        + " — chunking changed (was " + cp.chunkSize + "/" + cp.overlap + "/"
                        + cp.totalChunks + ", now " + chunkSize + "/" + overlap + "/" + totalChunks + ")");
                return null;
            }
            if (cp.chunksProcessed <= 0 || cp.chunksProcessed > totalChunks) {
                return null;
            }
            Object sc = m.get("scenes");
            if (sc instanceof List) cp.scenes = (List<Map<String, Object>>) sc;
            Object fe = m.get("failedExtractions");
            if (fe instanceof List) cp.failedExtractions = (List<String>) fe;
            return cp;
        } catch (Exception e) {
            logger.warn("Unparseable extraction checkpoint for " + workObjectId
                    + " — starting fresh: " + e.getMessage());
            return null;
        }
    }

    /** Minimal escaping for a message embedded in a hand-built JSON string. */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }

    /** Tolerant numeric read — JSON round-trips ints as Integer, Long or Double depending on source. */
    static int intOf(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : 0;
    }

    /**
     * Delete a work's extraction checkpoint.
     *
     * <p>Called on <b>successful completion</b> only. A cancelled or crashed run keeps its
     * checkpoint on purpose — that retained partial work is what makes both restart-resume and
     * cancel-then-continue possible — while a completed run must not leave a stale checkpoint
     * behind for the next extraction of the same document to resume from.
     */
    public static void clearExtractCheckpoint(BaseRecord user, String workObjectId) {
        String groupPath = findWorkGroupPath(user, workObjectId);
        if (groupPath != null) {
            clearExtractCheckpointAt(user, groupPath, workObjectId);
            return;
        }
        /// The work document is gone (deleted while a run was in flight, or deleted later), so the
        /// group walk can never reach its checkpoint note and the note would survive forever. Fall
        /// back to the org-wide name search, the same shape deleteOrphanedMetaNotes uses for the
        /// equivalent `.pictureBookMeta` orphan.
        deleteOrphanedExtractCheckpoints(user, workObjectId);
    }

    /**
     * Delete a work's checkpoint note when its group can no longer be resolved.
     *
     * <p>The note name embeds the work objectId, so this is an exact-name lookup rather than the
     * JSON-linkage scan {@link #deleteOrphanedMetaNotes} needs — nothing else can match, so no
     * other user's or document's checkpoint is at risk. Best-effort and org-scoped; every failure
     * is swallowed and logged, because this is cleanup and never a reason to fail the caller.
     *
     * @return the number of orphaned checkpoint notes deleted
     */
    static int deleteOrphanedExtractCheckpoints(BaseRecord user, String workObjectId) {
        if (user == null || workObjectId == null || workObjectId.isBlank()) {
            return 0;
        }
        int deleted = 0;
        try {
            long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
            /// An explicit organizationId condition is required for a data.directory-derived list
            /// query or PBAC denies it (and the denial surfaces as an empty result, not an error).
            Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME,
                    EXTRACT_PROGRESS_NOTE + "." + workObjectId);
            q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            q.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID,
                FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_NAME });
            q.setCache(false);
            BaseRecord[] notes = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
            if (notes != null) {
                for (BaseRecord note : notes) {
                    try {
                        if (IOSystem.getActiveContext().getAccessPoint().delete(user, note)) {
                            deleted++;
                            logger.info("Deleted orphaned extraction checkpoint for gone work {}",
                                    workObjectId);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to delete an orphaned extraction checkpoint: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Orphaned-checkpoint cleanup failed for " + workObjectId + ": " + e.getMessage());
        }
        return deleted;
    }

    /**
     * Path-based counterpart, for callers that already hold the work's group path. The chunk loop
     * uses this so completing a run does not re-resolve the work record it just finished reading.
     */
    static void clearExtractCheckpointAt(BaseRecord user, String groupPath, String workObjectId) {
        try {
            BaseRecord note = loadProgressNote(user, groupPath, workObjectId);
            if (note != null) {
                IOSystem.getActiveContext().getAccessPoint().delete(user, note);
            }
        } catch (Exception e) {
            logger.warn("Failed to clear extraction checkpoint for " + workObjectId + ": " + e.getMessage());
        }
    }

    private static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text,
            SummarizeProgress cancelToken) {
        return extractChunkedInternal(user, chatConfig, text, cancelToken, null, null, null);
    }

    private static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text,
            SummarizeProgress cancelToken, List<String> failedExtractions) {
        return extractChunkedInternal(user, chatConfig, text, cancelToken, failedExtractions, null, null);
    }

    private static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text,
            SummarizeProgress cancelToken, List<String> failedExtractions, String workObjectId,
            boolean[] reachedEndOut) {
        return extractChunkedInternal(user, chatConfig, text, cancelToken, failedExtractions, workObjectId,
                reachedEndOut, null);
    }

    /**
     * @param workObjectId  the source document, enabling incremental persistence and resume. When
     *                      null, checkpointing is skipped entirely and the method behaves exactly
     *                      as before — which is what the in-memory callers and existing tests rely on.
     * @param reachedEndOut optional single-element sink set to whether the loop reached the end of
     *                      the text. Callers cannot derive this themselves: a clean finish, a
     *                      cancel, a thread interrupt and the unreachable-LLM circuit breaker all
     *                      return normally with a scene list, and conflating them is what caused an
     *                      interrupted run to report completion. Same out-param idiom as
     *                      {@link #parseLlmJsonObject}'s {@code okOut}.
     * @param llm           the per-chunk model call; null means the real one. See {@link ChunkLlm}.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractChunkedInternal(BaseRecord user, BaseRecord chatConfig, String text,
            SummarizeProgress cancelToken, List<String> failedExtractions, String workObjectId,
            boolean[] reachedEndOut, ChunkLlm llm) {
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

        // Resolve + compose the chunk prompt ONCE for the whole run. It depends only on the prompt
        // name, the user and the chat config, none of which change between chunks, so resolving it
        // per chunk re-ran the user-group→system-library lookup chain (and PromptTemplateComposer)
        // on every iteration: 34 template queries and 17 expected-miss WARNs for a 17-chunk
        // document. extractScenesOnly already resolves its chat config exactly once this way.
        // See ResolvedPrompt / resolvePrompt for why the lookup chain itself must stay intact.
        // Only the real LLM path needs the composed prompt; a test seam supplies replies directly
        // and must not require a resolvable chat config or a live template lookup.
        final ChunkLlm chunkLlm;
        if (llm != null) {
            chunkLlm = llm;
        } else {
            ResolvedPrompt chunkPrompt = resolvePrompt(user, chatConfig, "pictureBook.extract-chunk");
            chunkLlm = (vars, attempt) -> callLlmResolved(user, chatConfig, "pictureBook.extract-chunk",
                    vars, chunkPrompt);
        }

        // Incremental persistence + resume. Nothing here used to be written until the final chunk,
        // so a dropped connection or a container restart discarded the whole run (measured
        // 2026-09-13: chunk 11 of 17, ~27 minutes of LLM output, nothing logged). A checkpoint is
        // only honoured for byte-identical text chunked identically — see ExtractCheckpoint.
        String groupPath = (workObjectId == null) ? null : findWorkGroupPath(user, workObjectId);
        ExtractCheckpoint checkpoint = new ExtractCheckpoint();
        checkpoint.textHash = extractTextHash(text);
        checkpoint.chunkSize = chunkSize;
        checkpoint.overlap = overlap;
        checkpoint.totalChunks = chunks.size();
        List<Map<String, Object>> sceneList = new ArrayList<>();
        int startChunk = 0;
        if (groupPath != null) {
            ExtractCheckpoint prior = loadExtractCheckpoint(user, groupPath, workObjectId,
                    checkpoint.textHash, chunkSize, overlap, chunks.size());
            if (prior != null) {
                startChunk = prior.chunksProcessed;
                sceneList = prior.scenes;
                // sourceText is stripped on persistence and rehydrated here from the identical
                // chunk list, so resumed scenes carry the same passage they were extracted from
                // and createFromScenes's per-character reduce still has the right text.
                for (Map<String, Object> s : sceneList) {
                    /// Require a real numeric sourceChunk. intOf maps anything missing or
                    /// non-numeric to 0, which would silently attribute CHUNK 0's passage to the
                    /// scene — worse than leaving sourceText unset, because createFromScenes would
                    /// then reduce that character's detail from the wrong part of the document.
                    Object sc = s.get("sourceChunk");
                    if (!(sc instanceof Number)) continue;
                    int sci = ((Number) sc).intValue();
                    if (sci >= 0 && sci < chunks.size()) s.put("sourceText", chunks.get(sci));
                }
                if (failedExtractions != null && prior.failedExtractions != null) {
                    failedExtractions.addAll(prior.failedExtractions);
                }
                checkpoint.scenes = sceneList;
                checkpoint.chunksProcessed = startChunk;
                if (failedExtractions != null) checkpoint.failedExtractions = failedExtractions;
                logger.info("Resuming chunked extraction for " + workObjectId + " at chunk "
                        + (startChunk + 1) + "/" + chunks.size() + " with " + sceneList.size()
                        + " scene(s) already extracted");
                // Report resumed progress so a client polling the job sees the real position
                // rather than the run appearing to restart from zero.
                if (cancelToken != null) cancelToken.setCurrent(startChunk);
            } else {
                checkpoint.scenes = sceneList;
                if (failedExtractions != null) checkpoint.failedExtractions = failedExtractions;
            }
        }
        if (startChunk >= chunks.size()) {
            // Every chunk was already processed before the run died; nothing left to extract.
            logger.info("Extraction checkpoint for " + workObjectId + " is already complete ("
                    + sceneList.size() + " scenes)");
        }
        // Did the loop actually reach the end of the document? This is NOT the same as "returned
        // without throwing", and conflating the two destroyed real work: on a container shutdown
        // the worker is interrupted mid-LLM-call, every remaining chunk then fails instantly with
        // no server to call, and the loop sailed to the end and reported success. Measured
        // 2026-09-13: a 5-chunk run checkpointed at chunk 2, was interrupted, "processed" chunks
        // 3-5 in 11 seconds, and then DELETED its own checkpoint as a completed run.
        boolean reachedEnd = true;
        /// Consecutive chunks whose LLM call returned nothing at all (as opposed to returning
        /// something unparseable). Two in a row means the model server is gone, not that the model
        /// is having an off day — see the circuit breaker below.
        int consecutiveEmptyResponses = 0;
        for (int ci = startChunk; ci < chunks.size(); ci++) {
            // KI-10: checkpoint at the top of the chunk loop — a mid-run cancel (POST
            // /{workObjectId}/cancel) stops further LLM calls immediately; scenes already
            // extracted from earlier chunks are still returned, not discarded.
            if (cancelToken != null && cancelToken.isCancelled()) {
                logger.info("extractChunkedInternal: cancelled after " + ci + "/" + chunks.size()
                        + " chunks — returning " + sceneList.size() + " scenes extracted so far");
                // Persist before breaking out. A cancelled run KEEPS its checkpoint on purpose:
                // the partial work is real, and re-driving the same extraction should continue
                // rather than repeat the chunks already paid for.
                if (groupPath != null) {
                    /// NOT `= ci`. The loop index advances past a chunk that FAILED (no
                    /// reply, or unparseable after the retry) exactly as it does past one
                    /// that merged, so using it would mark a failed passage as done and a
                    /// resume would skip it for good. chunksProcessed is advanced ONLY at
                    /// the bottom of the loop, which the failure paths `continue` past on
                    /// purpose — persist that value unchanged.
                    saveExtractCheckpoint(user, groupPath, workObjectId, checkpoint);
                }
                reachedEnd = false;
                break;
            }
            /// Thread interruption is how AsyncJobRegistry.shutdown() (and therefore a Tomcat
            /// stop/redeploy) stops a job. Treat it exactly like a cancel: stop calling the LLM,
            /// keep what has been extracted, and leave the checkpoint in place so the next
            /// re-drive resumes. Without this the remaining chunks "fail" instantly against an
            /// unreachable server and the run looks complete.
            if (Thread.currentThread().isInterrupted()) {
                logger.warn("extractChunkedInternal: interrupted after " + ci + "/" + chunks.size()
                        + " chunks — keeping " + sceneList.size() + " scenes and the checkpoint");
                if (groupPath != null) {
                    /// NOT `= ci`. The loop index advances past a chunk that FAILED (no
                    /// reply, or unparseable after the retry) exactly as it does past one
                    /// that merged, so using it would mark a failed passage as done and a
                    /// resume would skip it for good. chunksProcessed is advanced ONLY at
                    /// the bottom of the loop, which the failure paths `continue` past on
                    /// purpose — persist that value unchanged.
                    saveExtractCheckpoint(user, groupPath, workObjectId, checkpoint);
                }
                reachedEnd = false;
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
            /// The COMPLETE roster, not the windowed one previousScenes carries - see
            /// knownCharacterNames. ALWAYS supplied, even empty: an unsupplied template variable is
            /// a HARD failure ("Refusing to call LLM for prompt ... first: '{knownCharacters}'"),
            /// which is how every landscape prompt once broke at once.
            List<String> roster = knownCharacterNames(sceneList);
            vars.put("knownCharacters", roster.isEmpty() ? "(none yet)" : String.join(", ", roster));
            vars.put("chunk", chunks.get(ci));
            // Extract this chunk's scenes, with a bounded retry: qwen3-class models occasionally emit
            // malformed JSON (a stray quote, a corrupted token mid-generation) — a fresh generation
            // almost always parses. Intermediate attempts pass a NULL failure-sink so a recovered
            // chunk leaves no spurious failedExtractions record; only the FINAL failure is recorded.
            String chunkCtx = "extract-scenes-chunk:" + (ci + 1) + "/" + chunks.size();
            /// Set when any attempt for THIS chunk failed slowly (i.e. timed out) rather than
            /// failing immediately. Reset per chunk.
            boolean sawSlowFailure = false;
            /// The model's own explanation for the last failure, when it gave one.
            String lastLlmError = null;
            Map<String, Object> chunkResult = null;
            String llmResp = null;
            boolean parseOk = false;
            for (int attempt = 1; attempt <= 2; attempt++) {
                /// On the retry, TELL the model what went wrong. The previous version re-issued a
                /// byte-identical request — same template, same vars, same options — so its only
                /// mechanism was sampling luck, at ~90s a try. Truncation in particular will just
                /// recur. A corrective instruction costs nothing and addresses the actual cause.
                Map<String, String> attemptVars = vars;
                if (attempt > 1) {
                    attemptVars = new LinkedHashMap<>(vars);
                    attemptVars.put("chunk", vars.get("chunk")
                        + "\n\nIMPORTANT: your previous reply could not be parsed as JSON (it was"
                        + " truncated or malformed). Reply with ONLY a single complete, valid JSON"
                        + " object. Keep every field short so the whole object fits in one reply.");
                }
                long attemptStart = System.currentTimeMillis();
                /// Clear first: this thread is pooled, so a reason left over from earlier work
                /// would otherwise be misreported as this chunk's.
                Chat.clearLastCallError();
                llmResp = chunkLlm.call(attemptVars, attempt);
                long attemptMs = System.currentTimeMillis() - attemptStart;
                // KI-10: count the chunk as processed once (first attempt) — progress reflects
                // "chunks attempted", matching ChatUtil.mapSummarize's incrementCurrent() placement.
                if (attempt == 1 && cancelToken != null) cancelToken.incrementCurrent();
                if (llmResp == null || llmResp.isEmpty()) {
                    /// Only a FAST empty reply suggests the server is gone; a slow one is a
                    /// timeout against a live-but-loaded model. See LLM_INFRA_FAILURE_MS.
                    boolean slowFailure = (attemptMs >= LLM_INFRA_FAILURE_MS);
                    if (slowFailure) {
                        sawSlowFailure = true;
                    }
                    String why = Chat.getLastCallError();
                    if (why != null) lastLlmError = why;
                    logger.error("No LLM content for " + chunkCtx + " (attempt " + attempt
                            + ", " + attemptMs + "ms)"
                            + (why != null ? " — " + why : " — no reason reported"));
                    if (slowFailure) {
                        /// NEVER retry a TIMEOUT. The retry above exists for MALFORMED JSON (see the
                        /// comment at the head of this loop) — a fresh generation usually parses. A
                        /// timeout is not that: the request did not fail because of sampling luck, it
                        /// failed because the server could not finish in time, and re-issuing it
                        /// cannot succeed for the reason it failed. Worse, AM7 does not (did not)
                        /// abort the outbound exchange on give-up, so the first generation is STILL
                        /// occupying the model server's slot; attempt 2 queues a second one behind it
                        /// and doubles the load on an already-saturated server. Measured 2026-09-14:
                        /// two threads looping timeout->retry left Ollama unable to answer a trivial
                        /// "Say OK" within 90s. Note also that attempt 2 appends a "your previous
                        /// reply could not be parsed as JSON" instruction, which is factually wrong
                        /// for a timeout and only makes the prompt larger.
                        /// Give up on THIS CHUNK only — the outer loop deliberately continues to the
                        /// next chunk (see the circuit breaker below), which stays unchanged.
                        logger.warn("Chunk " + chunkCtx + " timed out after " + attemptMs
                                + "ms — NOT retrying (a timeout cannot be fixed by repeating the call)");
                        break;
                    }
                    continue;
                }
                boolean[] ok = new boolean[1];
                Map<String, Object> parsed = parseLlmJsonObject(llmResp, chunkCtx, null, ok);
                /// Break on PARSE SUCCESS, not on non-emptiness. A validly-parsed empty object means
                /// "no new scenes in this chunk" — a correct answer — and retrying it wasted a full
                /// generation and then recorded a bogus failure.
                if (ok[0]) { chunkResult = parsed; parseOk = true; break; }
                if (attempt < 2) logger.warn("Chunk " + chunkCtx + " returned unparseable JSON — retrying once");
            }
            if (llmResp == null || llmResp.isEmpty()) {
                if (sawSlowFailure) {
                    /// Timed out against a live server. Record the chunk as failed (below) and
                    /// keep going — aborting the rest of the document because the model is having
                    /// a slow spell throws away every remaining passage for no reason.
                    consecutiveEmptyResponses = 0;
                    logger.warn("Chunk " + chunkCtx + " timed out but the model server is"
                            + " responding — recording the failure and continuing");
                } else {
                    consecutiveEmptyResponses++;
                }
            } else {
                consecutiveEmptyResponses = 0;
            }
            /// Circuit breaker, for an UNREACHABLE server only. Two chunks in a row whose
            /// attempts all failed IMMEDIATELY means nothing is listening — during a shutdown the
            /// interrupt flag may already have been consumed by whatever caught
            /// InterruptedException down in the HTTP stack, so this is the guard that does not
            /// depend on it. Timeouts explicitly do NOT count (see LLM_INFRA_FAILURE_MS): a slow
            /// model is alive, and treating it as dead aborted a real 17-chunk document five
            /// chunks from the end.
            if (consecutiveEmptyResponses >= 2) {
                /// Report the model's OWN reason. Asserting "the server is down" was wrong and
                /// actively misleading: the same instant-failure signature is produced by a
                /// mistyped model name (HTTP 404 "model 'x' not found" in ~12ms), and a user who
                /// made a typo was told their hardware had failed.
                logger.error("extractChunkedInternal: " + consecutiveEmptyResponses
                        + " consecutive chunks failed immediately at " + (ci + 1) + "/" + chunks.size()
                        + " — stopping and keeping the checkpoint (" + sceneList.size() + " scenes). "
                        + (lastLlmError != null ? "Reason: " + lastLlmError
                            : "No reason was reported by the model server."));
                /// Tell the CLIENT, not just the log. Without this the caller sees a run that
                /// stopped early with no explanation — and if it stopped on chunk 1 it sees an
                /// empty scene list that is indistinguishable from "the model found nothing".
                if (failedExtractions != null) {
                    /// Hand the model's own words to the CLIENT too — this is what the user sees,
                    /// and "model 'qweb3:8b' not found" is actionable where "unreachable" is not.
                    String reason = (lastLlmError != null) ? lastLlmError
                        : "the model server did not respond and gave no reason";
                    failedExtractions.add("{\"context\":\"" + chunkCtx
                        + "\",\"error\":\"" + consecutiveEmptyResponses
                        + " consecutive chunks failed immediately. " + jsonEscape(reason)
                        + " Extraction stopped early; fix the cause and re-run to resume from the"
                        + " checkpoint.\"}");
                }
                if (groupPath != null) {
                    /// The chunks that tripped the breaker produced nothing, so they
                    /// must stay un-merged and be retried on the next re-drive.
                    saveExtractCheckpoint(user, groupPath, workObjectId, checkpoint);
                }
                reachedEnd = false;
                break;
            }
            if (!parseOk) {
                // Record the final, unrecoverable failure (re-parse with the real sink so the raw text
                // is captured for inspection), then skip this chunk.
                //
                // The null/empty-response case is recorded too. Previously this was guarded by
                // `llmResp != null && !llmResp.isEmpty()`, so a chunk where BOTH attempts returned
                // nothing (a conversational refusal, a hard infra failure) produced no
                // failedExtractions entry at all and vanished behind a single WARN.
                parseLlmJsonObject(llmResp, chunkCtx, failedExtractions);
                logger.warn("Chunk " + chunkCtx + " still unparseable after retry — skipping");
                continue;
            }
            if (chunkResult == null || chunkResult.isEmpty()) {
                /// Parsed cleanly to an empty object: nothing to merge, and NOT a failure.
                logger.info("Chunk " + chunkCtx + " reported no new scenes");
                continue;
            }

            mergeChunkResult(sceneList, chunkResult, chunks.get(ci), ci);
            logger.info("Chunk " + (ci + 1) + "/" + chunks.size() + " processed: " + sceneList.size() + " scenes total");
            // Checkpoint. `ci + 1` chunks are now fully merged into sceneList, so that is the
            // index a resume must start from. Written every EXTRACT_CHECKPOINT_EVERY chunks and
            // always on the final chunk, so a completed-but-undeliverable run is still recoverable.
            if (groupPath != null) {
                /// THE ONLY place chunksProcessed advances, reached only when this
                /// chunk's result was actually merged.
                checkpoint.chunksProcessed = ci + 1;
                boolean due = ((ci + 1 - startChunk) % EXTRACT_CHECKPOINT_EVERY == 0)
                        || (ci + 1 == chunks.size());
                if (due) saveExtractCheckpoint(user, groupPath, workObjectId, checkpoint);
            }
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

        // The checkpoint's fate is decided HERE, where "did this run reach the end of the
        // document" is actually known. The callers cannot tell: a cancel, an interrupt and a clean
        // finish all return normally with a scene list, which is precisely how an interrupted run
        // came to delete its own checkpoint.
        //
        // Cleared only when the loop reached the end. Per-chunk parse failures do NOT block the
        // clear — they are already surfaced to the client in failedExtractions, the run genuinely
        // reached the end of the text, and chunksProcessed has advanced past them, so keeping the
        // checkpoint would leave a record that can never be consumed or cleared.
        /// Re-check interruption AFTER the loop. The top-of-loop guard cannot see an interrupt
        /// that lands during the FINAL chunk: that chunk's LLM call returns null, one empty
        /// response is not enough to trip the circuit breaker, the `!parseOk` branch continues, the
        /// loop ends normally and reachedEnd is still true — so the checkpoint would be deleted for
        /// a run that never processed its last chunk.
        if (reachedEnd && Thread.currentThread().isInterrupted()) {
            logger.warn("extractChunkedInternal: interrupted during the final chunk of "
                    + chunks.size() + " — keeping the checkpoint rather than reporting completion");
            reachedEnd = false;
        }
        if (reachedEndOut != null && reachedEndOut.length > 0) {
            reachedEndOut[0] = reachedEnd;
        }
        if (groupPath != null) {
            if (reachedEnd) {
                clearExtractCheckpointAt(user, groupPath, workObjectId);
            } else {
                logger.info("Extraction for " + workObjectId + " stopped early at chunk "
                        + checkpoint.chunksProcessed + "/" + chunks.size()
                        + " — checkpoint kept for resume");
            }
        }

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
     * Clamp free-text LLM-extracted gender to exactly one of the ecosystem-canonical
     * LOWERCASE forms "male"/"female", or "" when undetermined (Stephen's explicit decision —
     * no other values). identity.person.gender is a plain string (NOT an enum), so it is
     * persisted verbatim with no case normalization — the value returned here is exactly what
     * lands in the DB and what every consumer reads back. The rest of Olio stores lowercase
     * (CharacterUtil.randomPerson) and compares with case-sensitive "male".equals(gender)
     * (NarrativeUtil pronouns/labels, BodyStatsProvider, StatisticsUtil, Chat, PromptUtil,
     * VectorProvider, …), so any other case silently falls through to the female branch.
     * Never throws — any unrecognized/blank input maps to "" so a bad LLM value can never
     * abort character creation.
     */
    private static String normalizeGender(String raw) {
        if (raw == null) return "";
        String g = raw.trim().toLowerCase();
        if (g.equals("male") || g.equals("m")) return "male";
        if (g.equals("female") || g.equals("f")) return "female";
        return "";  // undetermined — caller falls back to baseline
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
     * Common hair/eye colour words the LLM emits, mapped to a name that actually exists in the
     * world's shared {@code data.color} library.
     *
     * <p>{@link ColorUtil#getColorByName} is an exact (ILIKE) match on the library entry's name, and
     * the library is the 864-entry web-colour set — so it has "Black", "Blond", "Auburn", "Hazel",
     * "Amber", "Dark Brown" and "Sandy Brown", but NOT "brown", "green", "blonde" or "grey", which
     * are exactly the words a model reaches for first. Without this table the mapping misses on the
     * most common inputs and every character silently keeps the race-palette random colour.
     *
     * <p>Only colours a person's hair or eyes actually come in. This is not a general colour
     * synonym table and must not grow into one — apparel colours have their own path
     * ({@code ApparelUtil}, which calls {@code getColorByName} directly).
     */
    private static final Map<String, String> PERSON_COLOR_SYNONYMS = personColorSynonyms();

    private static Map<String, String> personColorSynonyms() {
        Map<String, String> m = new LinkedHashMap<>();
        // Hair
        m.put("brown", "Brown (Traditional)");
        m.put("dark brown", "Dark Brown");
        m.put("light brown", "Light Brown");
        m.put("golden brown", "Golden Brown");
        m.put("mousy brown", "Pale Brown");
        m.put("blonde", "Blond");
        m.put("blond", "Blond");
        m.put("strawberry blonde", "Apricot");
        m.put("dirty blonde", "Sandy Brown");
        m.put("sandy", "Sandy Brown");
        m.put("ginger", "Ginger");
        m.put("red", "Auburn");
        m.put("redhead", "Auburn");
        m.put("auburn", "Auburn");
        m.put("chestnut", "Chestnut");
        m.put("copper", "Copper");
        m.put("black", "Black");
        m.put("jet black", "Black");
        m.put("raven", "Black");
        m.put("grey", "Gray");
        m.put("gray", "Gray");
        m.put("greying", "Gray");
        m.put("graying", "Gray");
        m.put("silver", "Silver");
        m.put("white", "White");
        m.put("platinum", "Platinum");
        m.put("salt and pepper", "Dim Gray");
        // Eyes
        m.put("hazel", "Hazel");
        m.put("amber", "Amber");
        m.put("green", "Dark Green");
        m.put("blue", "Blue");
        m.put("pale blue", "Baby Blue");
        m.put("light blue", "Baby Blue");
        m.put("ice blue", "Alice Blue");
        m.put("grey-blue", "Blue Gray");
        m.put("gray-blue", "Blue Gray");
        m.put("blue-grey", "Blue Gray");
        m.put("blue-gray", "Blue Gray");
        m.put("dark", "Dark Brown");
        return m;
    }

    /// Hair words that describe STYLE/length rather than colour — stripped before the colour scan so
    /// "long wavy strawberry-blonde" resolves on "strawberry blonde", and kept so the leftover can be
    /// used as the character's hairStyle.
    private static final Pattern HAIR_STYLE_WORDS = Pattern.compile(
            "\\b(long|short|shoulder-length|shoulder length|cropped|close-cropped|buzzed|buzz|"
            + "wavy|curly|straight|frizzy|coiled|kinky|braided|braids|plaited|dreadlocked|dreadlocks|"
            + "ponytail|bun|pixie|bob|bobbed|messy|unkempt|neat|tidy|slicked|slicked-back|swept|"
            + "thinning|balding|bald|receding|shaved|tousled|shaggy|wispy|thick|fine|coarse|"
            + "hair|haired|locks|mane|tresses)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Resolve a free-text person colour (LLM {@code physical.hair} / {@code physical.eyes}) to a
     * persisted {@code data.color} in the world's shared library, or null when nothing matches.
     *
     * <p>Three passes, cheapest first: the whole trimmed string through the synonym table, then the
     * library directly (so a literal library name like "Chestnut" works without a table entry), then
     * the longest matching synonym among the string's own word n-grams (so "long wavy
     * strawberry-blonde hair" finds "strawberry blonde").
     *
     * <p>Screened with {@link NarrativeUtil#isMeaningful} rather than a blank check, because this is
     * an LLM-extracted field: a model that cannot determine hair colour emits the literal string
     * {@code "null"} as the VALUE (see {@code .claude/rules/objects7-reference.md}).
     *
     * <p>Returns null rather than a raw string on a miss, so the caller KEEPS whatever race-palette
     * colour the random baseline assigned — the same contract as {@link #mapRaceOverride} and
     * {@link #mapEthnicityOverride}.
     */
    public static BaseRecord mapPersonColorOverride(OlioContext octx, String freeText) {
        if (octx == null || !NarrativeUtil.isMeaningful(freeText)) return null;
        String t = freeText.trim().toLowerCase();

        String direct = PERSON_COLOR_SYNONYMS.get(t);
        if (direct != null) {
            BaseRecord c = ColorUtil.getColorByName(octx, direct);
            if (c != null) return c;
        }
        BaseRecord literal = ColorUtil.getColorByName(octx, freeText.trim());
        if (literal != null) return literal;

        /// Longest n-gram first: "strawberry blonde" must beat the "blonde" inside it, and
        /// "dark brown" must beat "dark".
        String cleaned = HAIR_STYLE_WORDS.matcher(t).replaceAll(" ").replaceAll("[^a-z\\- ]", " ");
        String[] words = cleaned.trim().split("\\s+");
        for (int span = Math.min(3, words.length); span >= 1; span--) {
            for (int i = 0; i + span <= words.length; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < i + span; j++) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(words[j]);
                }
                String phrase = sb.toString().trim();
                if (phrase.isEmpty()) continue;
                String mapped = PERSON_COLOR_SYNONYMS.get(phrase);
                if (mapped == null && phrase.indexOf('-') >= 0) {
                    mapped = PERSON_COLOR_SYNONYMS.get(phrase.replace('-', ' '));
                }
                if (mapped != null) {
                    BaseRecord c = ColorUtil.getColorByName(octx, mapped);
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    /**
     * The STYLE half of an LLM {@code physical.hair} string ("long wavy strawberry-blonde" →
     * "long wavy"), or null when it carries no style words.
     *
     * <p>{@code hairStyle} is a plain string column, so unlike the colour it needs no library
     * lookup — but the colour words have to come off or {@code describePhysical} renders
     * "brown long wavy strawberry-blonde hair".
     */
    public static String extractHairStyle(String freeText) {
        if (!NarrativeUtil.isMeaningful(freeText)) return null;
        StringBuilder sb = new StringBuilder();
        Matcher m = HAIR_STYLE_WORDS.matcher(freeText.trim().toLowerCase());
        while (m.find()) {
            String w = m.group(1);
            /// "hair"/"haired"/"locks"/"mane"/"tresses" are the NOUN, not a style - they are in the
            /// pattern so the colour scan strips them, but they must not become the style itself.
            if (w.equals("hair") || w.equals("haired") || w.equals("locks")
                    || w.equals("mane") || w.equals("tresses")) continue;
            if (sb.indexOf(w) >= 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.length() > 0 ? sb.toString() : null;
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
    /**
     * KI-30 root-cause guard: acquire an {@link OlioContext} for {@code dataPath} without letting a
     * build failure escape the request. {@code OlioContextUtil.getOlioContext} →
     * {@code OlioContext.initialize()} <b>rethrows as a {@code RuntimeException}</b> when its build
     * throws before authorization is configured (OlioContext.java:948-953): an un-seeded world whose
     * population group is empty makes region/realm generation dereference a null record
     * ({@code object.getSchema()} NPE), which initialize() catches and rethrows because
     * {@code authorizationConfigured} is still false. Both KI-30 call sites (the pre-loop
     * {@code prepareGroups} warm-up and {@code createCharPerson}'s random-baseline acquisition)
     * previously called {@code getOlioContext} BARE, so that RuntimeException escaped
     * {@code createFromScenes} past the endpoint's {@code PictureBookException}-only catch as an
     * uncaught 500. Returning null here degrades to the pre-KI-30 sparse fallback (a plainly created
     * character with no world placement) — exactly the best-effort contract {@link #buildRandomBaseline}
     * already documents. {@code null}/empty {@code dataPath} returns null without a warning (already an
     * expected, logged-elsewhere configuration state).
     */
    private static OlioContext safeGetOlioContext(BaseRecord user, String dataPath, String forWhat) {
        if (dataPath == null || dataPath.isEmpty()) {
            return null;
        }
        try {
            return OlioContextUtil.getOlioContext(user, dataPath);
        } catch (RuntimeException e) {
            logger.warn("OlioContext init failed (dataPath=" + dataPath + ") for " + forWhat
                    + " — degrading to the sparse, world-unplaced character fallback: " + e.getMessage());
            return null;
        }
    }

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
            // Narrative was created by the olio principal when octx is available — use that principal
            // for the update to avoid PBAC denial (request user doesn't own world-group narratives).
            BaseRecord narrativeActor = (octx != null && octx.getOlioUser() != null) ? octx.getOlioUser() : user;
            BaseRecord narrativeFieldsPersisted = IOSystem.getActiveContext().getAccessPoint().update(narrativeActor, narrativePatch);
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
        return createCharPerson(user, chatConfig, charData, charsGroup, genre, failedApparelOut, failedStatisticsOut, dataPath, null);
    }

    private static BaseRecord createCharPerson(BaseRecord user, BaseRecord chatConfig, Map<String, Object> charData, BaseRecord charsGroup, String genre,
            List<String> failedApparelOut, List<String> failedStatisticsOut, String dataPath, OlioContext octxHint) {
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
            if (octxHint != null) {
                // Use the caller-supplied book OlioContext (PB2 path) — avoids opening
                // the default world, which may not exist for a PB2-only user.
                octx = octxHint;
            } else {
                // KI-30: tolerate an OlioContext init failure (e.g. an un-seeded, empty-population world
                // whose initialize() NPEs and rethrows a RuntimeException) — degrade to the sparse fallback
                // rather than letting it escape as a 500. See safeGetOlioContext().
                octx = safeGetOlioContext(user, dataPath, name);
            }
            if (octx == null) {
                logger.warn("OlioContext unavailable (dataPath=" + dataPath + ") for "
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

            // Apply gender — clamped to the ecosystem-canonical LOWERCASE "male"/"female" only,
            // never a raw/unrecognized LLM value (see normalizeGender()). Must happen before
            // create() so a bad LLM value never aborts character creation.
            String gender = normalizeGender((String) charData.get("gender"));
            // Undetermined LLM gender: fall back to the random baseline's gender. CharacterUtil
            // .randomPerson already stores lowercase "male"/"female", but lowercase() here keeps
            // the value canonical regardless of the baseline's source so case-sensitive consumers
            // (NarrativeUtil/BodyStatsProvider/StatisticsUtil) don't silently render female.
            if ((gender == null || gender.isEmpty()) && baseline != null) {
                Object baseGender = baseline.get(FieldNames.FIELD_GENDER);
                if (baseGender != null && !baseGender.toString().isBlank())
                    gender = baseGender.toString().trim().toLowerCase();
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

            // The EXTRACTION's own appearance, applied ON TOP of the baseline palette.
            //
            // This is issue 2's root cause. The LLM's physical.hair / physical.eyes reached only the
            // narrative's sdPrompt text (buildPortraitPromptFromExtractedData) — never the record —
            // while hairColor/eyeColor came from CharacterUtil.setStyleByRace's RANDOM race-appropriate
            // palette. So the persisted character and the text used to image it described two
            // different people BY CONSTRUCTION, and editing the character in the Ux could not fix it
            // because imaging never read the record. Seeding here makes the record the extraction,
            // which is what lets the description be derived from the record afterwards
            // (refreshNarrativeFromRecord) and lets a post-extraction edit actually take effect.
            //
            // Best-effort in both directions: an unmappable colour KEEPS the baseline (never a raw
            // string on a foreign ref), and a failure here never aborts character creation.
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> physAppearance = (Map<String, Object>) charData.get("physical");
                if (physAppearance != null) {
                    Object hairV = physAppearance.get("hair");
                    if (hairV instanceof String) {
                        BaseRecord hairColor = mapPersonColorOverride(octx, (String) hairV);
                        if (hairColor != null
                                && patchCharPersonField(user, charPerson, OlioFieldNames.FIELD_HAIR_COLOR, hairColor) != null) {
                            charPerson.set(OlioFieldNames.FIELD_HAIR_COLOR, hairColor);
                            logger.info("Character " + name + ": hairColor from extraction '" + hairV
                                    + "' -> " + hairColor.get(FieldNames.FIELD_NAME));
                        }
                        String hairStyle = extractHairStyle((String) hairV);
                        if (hairStyle != null) {
                            charPerson.set(OlioFieldNames.FIELD_HAIR_STYLE, hairStyle);
                            BaseRecord stylePatch = charPerson.copyRecord(new String[] {
                                    FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_HAIR_STYLE });
                            if (IOSystem.getActiveContext().getAccessPoint().update(user, stylePatch) == null) {
                                logger.warn("Failed to persist hairStyle '" + hairStyle + "' for " + name);
                            }
                        }
                    }
                    Object eyesV = physAppearance.get("eyes");
                    if (eyesV instanceof String) {
                        BaseRecord eyeColor = mapPersonColorOverride(octx, (String) eyesV);
                        if (eyeColor != null
                                && patchCharPersonField(user, charPerson, OlioFieldNames.FIELD_EYE_COLOR, eyeColor) != null) {
                            charPerson.set(OlioFieldNames.FIELD_EYE_COLOR, eyeColor);
                            logger.info("Character " + name + ": eyeColor from extraction '" + eyesV
                                    + "' -> " + eyeColor.get(FieldNames.FIELD_NAME));
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to apply extracted hair/eye appearance for " + name + ": " + e.getMessage());
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

            // Bring the narrative up to date with the record NOW, at the end of creation.
            //
            // ensureNarrative necessarily runs BEFORE statistics estimation and the apparel wizard
            // (its ordering relative to personality/instinct/state is load-bearing - see its
            // javadoc), so the narrative it writes cannot describe either. It also writes the
            // extraction-derived SD prompt into physicalDescription, which is a weighted prompt
            // string rather than a description. The per-render refresh in resolveSceneCharacter
            // would fix both at first render, but not before: the Manage Characters screen and the
            // reimage command both read these fields, so a character inspected or reimaged before
            // any scene was generated showed the wrong thing.
            //
            // Same helper, so there is exactly one definition of what a character's description is.
            // Best-effort: a failure here leaves the creation-time values, which the render-time
            // refresh still corrects.
            refreshNarrativeFromRecord(user, charPerson, name);

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
            // Likewise the checkpoint's chunk-index bookkeeping: it only means anything while an
            // extraction is mid-flight, and the book's scene notes outlive that entirely.
            sceneStore.remove("sourceChunk");
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
            /// extractChunkedInternal owns the checkpoint lifecycle, including clearing it on a
            /// genuinely complete run. Deciding that here was wrong: from out here a cancel, an
            /// interrupt and a clean finish are indistinguishable.
            boolean[] reachedEnd = new boolean[] { true };
            List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text, cancelToken,
                    failedExtractions, workObjectId, reachedEnd);
            return new ScenesOnlyResult(sceneList, true, failedExtractions, reachedEnd[0]);
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
        boolean[] reachedEnd = new boolean[] { true };
        List<Map<String, Object>> sceneList = extractChunkedInternal(user, chatConfig, text, cancelToken,
                failedExtractions, workObjectId, reachedEnd);
        BaseRecord result = buildResult();
        try {
            result.set("sceneList", sceneList);
            /// Report what actually happened. Both of these were hardcoded — `true` and `-1` — so
            /// a cancelled or partially-failed run was indistinguishable from a complete one, and
            /// the chunk count the progress token had all along was thrown away. `reachedEnd` (not
            /// the cancel flag) is the authority: an interrupt or the unreachable-LLM breaker also
            /// stops early without any cancel being requested.
            result.set("extractionComplete", reachedEnd[0]);
            result.set("chunksProcessed", cancelToken != null ? cancelToken.getCurrent() : -1);
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

        // Fold duplicate spellings of the same character into ONE name before anything reads the
        // scene list — issue 3. De-duplication below is an exact-string map, so without this an
        // unnamed character referred to as "Darby's dad" in one chunk and "the father" in another
        // became two charPersons with two portraits, two wardrobes and a split set of scenes.
        // Curated Step 3 names are seeded first so they win as the canonical spelling.
        List<String> curatedNames = new ArrayList<>();
        for (Map<String, Object> cd : charDataList) {
            Object cn = cd.get("name");
            if (cn instanceof String && !((String) cn).trim().isEmpty()) curatedNames.add((String) cn);
        }
        Map<String, String> nameAliases = canonicalizeSceneCharacterNames(sceneList, curatedNames);

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
        /// Scenes whose cast the source passage does not corroborate. Reported, never corrected -
        /// see charactersNotInSourceText for why an absent name is evidence rather than proof.
        List<String> unverifiedSceneCharacters = collectUnverifiedSceneCharacters(sceneList);

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
        // KI-30: guarded — a failing OlioContext.initialize() rethrows a RuntimeException, which here
        // (before the character loop) would otherwise escape as a 500. Degrade to legacy group routing.
        PbSubRecordUtil.prepareGroups(user, safeGetOlioContext(user, dataPath, "prepareGroups"));

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
                    // isMeaningful, not !isBlank: this came out of an LLM JSON object, and an LLM that
                    // cannot describe someone emits the literal string "null"/"n/a"/"unknown" as
                    // the VALUE instead of omitting the key. A blank check passes that through
                    // into pbDescription and on into the image prompt.
                    if (d instanceof String && NarrativeUtil.isMeaningful((String) d)) reducedDescription = ((String) d).trim();
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
                persistCharacterSceneAttributes(user, cp, charSceneIndices.get(cname),
                        imagingDescription(reducedDescription, charData));
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
        recordExtractionDiagnostics(meta, nameAliases, unverifiedSceneCharacters);
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
        // Record the ACTUAL group the charPerson records were written into so listCharacters reads
        // from the same place the write path used (legacy path: the {bookGroupPath}/Characters group).
        try { meta.set("charsGroupPath", charsGroup.get(FieldNames.FIELD_PATH)); } catch (Exception e) { logger.warn("Failed to record charsGroupPath on meta: " + e.getMessage()); }
        saveMeta(user, bookGroupPath, meta);
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
        // One LLM call per character needing detail extraction above — flush once at the end.
        OllamaModelUtil.unloadAll();

        return meta;
    }

    /**
     * Overload that accepts an optional PB2 book objectId.
     * <p>
     * When {@code pb2BookObjectId} is non-null, the created characters' sub-records (narratives,
     * statistics, etc.) are routed into the PB2 book's own Olio world groups rather than the
     * legacy home-directory groups. The returned meta's {@code bookObjectId} field is also
     * overridden to point at the PB2 book rather than the PB1 group.
     */
    @SuppressWarnings("unchecked")
    public static BaseRecord createFromScenes(BaseRecord user, String workObjectId, String chatConfigName,
            String genre, String bookName, List<Map<String, Object>> sceneList, List<Map<String, Object>> charDataListIn,
            String dataPath, String pb2BookObjectId) {
        if (pb2BookObjectId == null || pb2BookObjectId.isBlank()) {
            // backward-compat: delegate unchanged
            return createFromScenes(user, workObjectId, chatConfigName, genre, bookName, sceneList, charDataListIn, dataPath);
        }
        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
        BaseRecord pb2Book = PbBookUtil.readBook(user, pb2BookObjectId, orgId);
        if (pb2Book == null) {
            throw new PictureBookException(404, "PB2 book not found: " + pb2BookObjectId);
        }
        String bookSlug = pb2Book.get(OlioFieldNames.FIELD_PB_SLUG);
        if (bookSlug == null || bookSlug.isBlank()) {
            throw new PictureBookException(500, "PB2 book has no slug — cannot resolve its Olio context");
        }

        // Run the standard extraction but swap the prepareGroups context with the PB2 world's context
        // by temporarily resolving the book's OlioContext before the character loop.
        // Because createFromScenes calls prepareGroups inline, we intercept by calling the new
        // overload's path directly: reproduce the method body with the overridden prepareGroups call.
        OlioContext pb2OlioCtx = null;
        try {
            pb2OlioCtx = PbOlioContextUtil.getCreateBookContext(user, dataPath, bookSlug);
        } catch (OlioException e) {
            logger.warn("createFromScenes(pb2): could not resolve Olio context for slug=" + bookSlug + ": " + e.getMessage()
                + " — falling back to legacy group routing");
        }

        BaseRecord work = findWork(user, workObjectId);
        if (work == null) throw new PictureBookException(404, "Work not found");
        if (sceneList == null || sceneList.isEmpty()) throw new PictureBookException(400, "sceneList is required");

        List<Map<String, Object>> charDataList = (charDataListIn != null) ? new ArrayList<>(charDataListIn) : new ArrayList<>();
        String effectiveBookName = (bookName != null && !bookName.isEmpty()) ? bookName : work.get(FieldNames.FIELD_NAME);
        BaseRecord bookGroup = ensureBookGroup(user, effectiveBookName);
        if (bookGroup == null) throw new PictureBookException(500, "Failed to create book group");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        if (bookGroupPath == null) bookGroupPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + effectiveBookName;

        BaseRecord scenesGroup = ensureSubGroup(user, bookGroupPath, "Scenes");
        BaseRecord charsGroup = ensureSubGroup(user, bookGroupPath, "Characters");
        if (scenesGroup == null || charsGroup == null) throw new PictureBookException(500, "Failed to create sub-groups");

        BaseRecord chatConfig = null;
        if (chatConfigName != null) {
            chatConfig = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName, null);
        }
        String text = extractWorkText(user, work);

        /// Fold duplicate spellings of the same character into ONE name before anything reads the
        /// scene list. This was added to the PB1 overload only, and PB2 is the path every book
        /// created with a world takes - so the de-duplication the PB1 path got was not actually
        /// reaching new books. Same call, same ordering, same reason: the de-duplication below is
        /// an exact-string map, so "Darby's dad" and "the father" would otherwise become two
        /// charPersons with two portraits and a split set of scenes.
        List<String> curatedNames = new ArrayList<>();
        for (Map<String, Object> cd : charDataList) {
            Object cn = cd.get("name");
            if (cn instanceof String && !((String) cn).trim().isEmpty()) curatedNames.add((String) cn);
        }
        Map<String, String> nameAliases = canonicalizeSceneCharacterNames(sceneList, curatedNames);

        if (charDataList.isEmpty()) {
            Map<String, Map<String, Object>> uniqueChars = new LinkedHashMap<>();
            for (Map<String, Object> scene : sceneList) {
                Object charsObj = scene.get("characters");
                if (charsObj instanceof List) {
                    for (Object sc : (List<Object>) charsObj) {
                        String cname = null;
                        Map<String, Object> cmap = null;
                        if (sc instanceof Map) { cmap = (Map<String, Object>) sc; cname = (String) cmap.get("name"); }
                        else if (sc instanceof String) { cname = (String) sc; cmap = new LinkedHashMap<>(); cmap.put("name", cname); }
                        if (cname != null && !cname.isEmpty() && !uniqueChars.containsKey(cname)) uniqueChars.put(cname, cmap);
                    }
                }
            }
            for (Map.Entry<String, Map<String, Object>> entry : uniqueChars.entrySet()) charDataList.add(entry.getValue());
        }

        /// Scenes whose cast the source passage does not corroborate. Reported, never corrected -
        /// see charactersNotInSourceText for why an absent name is evidence rather than proof.
        List<String> unverifiedSceneCharacters = collectUnverifiedSceneCharacters(sceneList);

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
                if (block != null && !block.isBlank()) charBlocks.computeIfAbsent(cn, k -> new java.util.LinkedHashSet<>()).add(block);
            }
        }

        // Route sub-records into the PB2 book's world groups instead of the legacy home groups
        PbSubRecordUtil.prepareGroups(user, pb2OlioCtx);

        // B4: re-route charPerson into the world's population group rather than the legacy
        // ~/Data/PictureBooks/.../Characters group. findPath is read-only — the population
        // group is guaranteed to exist because getCreateBookContext created it above.
        if (pb2OlioCtx != null && pb2OlioCtx.getWorld() != null) {
            String populationPath = pb2OlioCtx.getWorld().get("population.path");
            if (populationPath == null) {
                // World may have been loaded shallow; try to re-query with population projected
                String worldObjId = pb2OlioCtx.getWorld().get(FieldNames.FIELD_OBJECT_ID);
                if (worldObjId != null) {
                    long orgId2 = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
                    Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldObjId);
                    wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId2);
                    wq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "population.path"});
                    wq.setCache(false);
                    BaseRecord fullWorld = IOSystem.getActiveContext().getAccessPoint().find(user, wq);
                    if (fullWorld != null) {
                        populationPath = fullWorld.get("population.path");
                    }
                }
            }
            if (populationPath != null && !populationPath.isBlank()) {
                BaseRecord worldCharsGroup = IOSystem.getActiveContext().getPathUtil()
                    .findPath(user, ModelNames.MODEL_GROUP, populationPath,
                        GroupEnumType.DATA.toString(), ((Number)user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
                if (worldCharsGroup != null) {
                    charsGroup = worldCharsGroup;
                } else {
                    logger.warn("createFromScenes(pb2): population group not found at {} — keeping legacy charsGroup", populationPath);
                }
            }
        }

        // Issue 4: resolve the BOOK world's Gallery path ONCE (a re-query per character would be
        // wasteful) so each charPerson can be stamped with SDUtil.ATTR_IMAGE_GALLERY_PATH. A later
        // (re)image call resolves the DEFAULT grid OlioContext when the request carries no
        // universe/world ids, so without this stamp SDUtil.resolveCharacterImagePath would store the
        // images under My Grid World's gallery instead of this book's world gallery. Mirrors the B4
        // population.path re-query above for the case where the world was loaded shallow.
        String bookGalleryPath = null;
        if (pb2OlioCtx != null && pb2OlioCtx.getWorld() != null) {
            bookGalleryPath = pb2OlioCtx.getWorld().get("gallery.path");
            if (bookGalleryPath == null || bookGalleryPath.isBlank()) {
                String worldObjId = pb2OlioCtx.getWorld().get(FieldNames.FIELD_OBJECT_ID);
                if (worldObjId != null) {
                    long orgIdG = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
                    Query gq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldObjId);
                    gq.field(FieldNames.FIELD_ORGANIZATION_ID, orgIdG);
                    gq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "gallery.path"});
                    gq.setCache(false);
                    BaseRecord fullWorld = IOSystem.getActiveContext().getAccessPoint().find(user, gq);
                    if (fullWorld != null) bookGalleryPath = fullWorld.get("gallery.path");
                }
            }
            if (bookGalleryPath == null || bookGalleryPath.isBlank()) {
                logger.warn("createFromScenes(pb2): could not resolve book world gallery.path — character images will fall back to the (re)image context's world gallery");
            }
        }

        Map<String, String> charObjectIds = new LinkedHashMap<>();
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
                    // isMeaningful, not !isBlank: this came out of an LLM JSON object, and an LLM that
                    // cannot describe someone emits the literal string "null"/"n/a"/"unknown" as
                    // the VALUE instead of omitting the key. A blank check passes that through
                    // into pbDescription and on into the image prompt.
                    if (d instanceof String && NarrativeUtil.isMeaningful((String) d)) reducedDescription = ((String) d).trim();
                    for (Map.Entry<String, Object> e : llmData.entrySet()) {
                        if (!charData.containsKey(e.getKey()) || charData.get(e.getKey()) == null
                                || ((charData.get(e.getKey()) instanceof String) && ((String) charData.get(e.getKey())).isEmpty())) {
                            charData.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
            BaseRecord cp = createCharPerson(user, chatConfig, charData, charsGroup, genre, failedApparel, failedStatistics, dataPath, pb2OlioCtx);
            if (cp != null) {
                charObjectIds.put(cname, cp.get(FieldNames.FIELD_OBJECT_ID));
                persistCharacterSceneAttributes(user, cp, charSceneIndices.get(cname),
                        imagingDescription(reducedDescription, charData));
                persistCharacterImageGalleryPath(cp, bookGalleryPath);
            } else {
                logger.error("createCharPerson failed for '" + cname + "' during /create-from-scenes (pb2) — character will be absent from the book");
                failedCharacters.add(cname);
            }
        }

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
        recordExtractionDiagnostics(meta, nameAliases, unverifiedSceneCharacters);
        if (!failedCharacters.isEmpty()) {
            try { meta.set("failedCharacters", failedCharacters); } catch (Exception e) { logger.warn("Failed to record failedCharacters: " + e.getMessage()); }
        }
        if (!failedApparel.isEmpty()) {
            try { meta.set("failedApparel", failedApparel); } catch (Exception e) { logger.warn("Failed to record failedApparel: " + e.getMessage()); }
        }
        if (!failedStatistics.isEmpty()) {
            try { meta.set("failedStatistics", failedStatistics); } catch (Exception e) { logger.warn("Failed to record failedStatistics: " + e.getMessage()); }
        }
        if (!failedExtractions.isEmpty()) {
            try { meta.set("failedExtractions", failedExtractions); } catch (Exception e) { logger.warn("Failed to record failedExtractions: " + e.getMessage()); }
        }
        try { meta.set("compositionContext", ""); } catch (Exception e) { logger.warn("Failed to default compositionContext: " + e.getMessage()); }
        // Store PB2 book objectId separately — bookObjectId remains the book GROUP objectId (needed for delete/reset)
        try { meta.set("pb2BookObjectId", pb2BookObjectId); } catch (Exception e) { logger.warn("Failed to set pb2BookObjectId on meta: " + e.getMessage()); }
        // Record the ACTUAL group the charPerson records were written into. For PB2 this is the world
        // population group (charsGroup was re-routed above), NOT the empty legacy {bookGroupPath}/Characters
        // group. listCharacters reads from this stored path so the read path matches the write path.
        try { meta.set("charsGroupPath", charsGroup.get(FieldNames.FIELD_PATH)); } catch (Exception e) { logger.warn("Failed to record charsGroupPath on meta: " + e.getMessage()); }
        saveMeta(user, bookGroupPath, meta);
        PictureBookProgressNotifier.getInstance().notifyProgress(user, "", "");
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
            //
            // ... unless this scene's config says a landscape is not wanted or cannot be consumed
            // (see landscapeEnabled). Both the LLM prompt call here and the SD pass in Stage 2 are
            // then skipped entirely, which is the whole point: generating an image the composite
            // throws away is one LLM call plus one full SD pass per scene for nothing.
            boolean wantLandscape = landscapeEnabled(common);
            String landscapePrompt = null;
            if (wantLandscape) {
                landscapePrompt = resolveLandscapePrompt(user, scene, chatConfig, setting, mood, common, params.promptTemplateOverride);
            }
            else {
                logger.info("Scene " + sceneObjectId + ": landscape SKIPPED (" + landscapeSkipReason(common)
                        + ") - no landscape prompt call and no landscape image; the setting reaches "
                        + "the model as prompt text via the scene prompt");
            }
            
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
                    ResolvedCharacter rc = resolveSceneCharacter(user, charItem, sceneGroupPath, params.bookSlug);
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
                    /// No LANDSCAPE_PROMPT node when no landscape prompt was resolved. Recording one
                    /// with a null payload would assert provenance for work that never happened.
                    if (landscapePrompt != null) {
                        BaseRecord lpNode = PbPipelineUtil.getCreateNode(pbGraph,
                                PbPipelineUtil.landscapePromptHandle(sceneObjectId),
                                PbNodeTypeEnumType.LANDSCAPE_PROMPT, 10,
                                PbPipelineUtil.SCOPE_SCENE, sceneObjectId);
                        PbGraphUtil.persistPromptText(user, lpNode, landscapePrompt);
                        PbPipelineUtil.recordText(pbGraph, lpNode, PbPipelineUtil.ROLE_LANDSCAPE_PROMPT,
                                PbArtifactTypeEnumType.PROMPT, landscapePrompt, common);
                        PbPipelineUtil.completeNode(pbGraph, lpNode);
                    }

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
            // The per-character text the COMPOSITE describes each person with — ResolvedCharacter's
            // sceneNarration, NOT its portraitPrompt.
            //
            // This held portraitPrompt, which is the portrait RENDER prompt, and that was the reported
            // "the portrait isn't used for this character" defect. For a character with a pbDescription
            // the two barely differ (portraitPrompt is just PORTRAIT_QUALITY_PREAMBLE + that same
            // description), so the bug was invisible on most characters. For a character WITHOUT one,
            // portraitPrompt falls back to narrative.sdPrompt — which bakes in a RANDOM art style, a
            // RANDOM era/setting and a random action at creation time. Measured live 2026-09-15 on
            // "The Big Way Out": Darby was the only one of 13 characters with no pbDescription, so her
            // composite text read "...16yo white teenaged girl... She is dressing in Renaissance-era
            // Florence's bustling piazza, circa 1500 AD. Comic book panel in Valiant Comics style from
            // the Golden Age 1930s-1940s with limited palette duotone." Her portrait WAS attached as a
            // reference (refs=2), but that text instructs an edit model to a different style, era and
            // setting than the reference photo, and at flux2 cfg 2.0 the text won — her father, whose
            // text was a clean physical description, kept his likeness in the same image.
            //
            // sceneNarration is the field resolveSceneCharacter already computes FOR this purpose and
            // sanitizes with stripTrailingConfigStyle (see its own comment on the three-styles bug).
            // It was only ever consumed by the Stage 0 scene text; the composite never read it.
            List<String> charSceneDescList = new ArrayList<>();
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
                    ResolvedCharacter rc = resolveSceneCharacter(user, charItem, sceneGroupPath, params.bookSlug);
                    if (rc == null) continue;
                    BaseRecord cp = rc.charPerson;
                    String cname = rc.name;
                    String portraitPrompt2 = rc.portraitPrompt;
                    // Style/setting-free description for the composite — see charSceneDescList.
                    String sceneDesc2 = rc.sceneNarration;

                    // Scene-tagged apparel: pick the highest sceneIndex-tagged outfit <= this
                    // scene's index, flip inuse, and fold its description into the portrait prompt.
                    // Returns false (no-op) for every character/book not using this feature — the
                    // common case, and the existing reuse-cache below must fire exactly as before.
                    boolean hasSceneApparel = selectSceneApparel(user, cp, currentSceneIndex);
                    if (hasSceneApparel) {
                        String outfitDesc = NarrativeUtil.describeOutfit(cp, false);
                        if (outfitDesc != null && !outfitDesc.isBlank()) {
                            portraitPrompt2 = portraitPrompt2 + ", " + outfitDesc;
                            // The composite must see the scene's outfit too, or it would describe the
                            // character in their creation-time clothes while the reference portrait
                            // (re-rendered just below, precisely because the outfit changed) shows the
                            // scene-tagged one.
                            sceneDesc2 = (sceneDesc2 == null || sceneDesc2.isBlank())
                                    ? outfitDesc : sceneDesc2 + ", " + outfitDesc;
                        }
                    }
                    // Never let the composite describe a person as nothing; sceneNarration is
                    // documented as never blank, but a stored record predating that guarantee would
                    // silently drop this character's description out of the prompt.
                    if (sceneDesc2 == null || sceneDesc2.isBlank()) sceneDesc2 = portraitPrompt2;

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
                        charSceneDescList.add(SWUtil.stripSDXLWeighting(sceneDesc2));
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
                        charSceneDescList.add(SWUtil.stripSDXLWeighting(sceneDesc2));

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
            //
            // Skipped wholesale when Stage 0 resolved no landscape prompt (landscapeEnabled said no).
            // landscapeBytes then stays null, which every composite branch below already tolerates:
            // FLUX.2 omits the setting reference, KONTEXT ships a shorter strip and CLASSIC degrades
            // to text-only txt2img. The stageCooldown that follows is skipped with it — there was no
            // GPU work to recover from.
            byte[] landscapeBytes = null;
            BaseRecord pbLandscapeNode = null;
            BaseRecord pbLandscapeArtifact = null;
            if (landscapePrompt != null) {
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
                landscapeBytes = ByteModelUtil.getValue(landscapeImage);
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
            }

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
            String leftDesc  = !charSceneDescList.isEmpty() ? charSceneDescList.get(0) : "";
            String rightDesc = charSceneDescList.size() > 1  ? charSceneDescList.get(1) : "";
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
            /// KONTEXT stitches the landscape into its panel strip and CLASSIC draws the portraits
            /// on top of it, so for those two modes a skipped landscape is a real capability loss,
            /// not a saving. landscapeEnabled never auto-skips them — this can only be reached by an
            /// explicit skipLandscape=true — so say so plainly rather than silently producing a
            /// setting-free composite that reads as a broken pipeline.
            if (landscapeBytes == null && (useKontext || !useFlux2)) {
                logger.warn("Scene " + sceneObjectId + ": compositeMode=" + (useKontext ? "kontext" : "classic")
                        + " CONSUMES the landscape image, but none was generated ("
                        + landscapeSkipReason(common) + ") - "
                        + (useKontext ? "the reference strip will carry portraits only"
                                      : "the classic pipeline degrades to text-only txt2img"));
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
        /// Same decision generateSceneImage makes, hoisted out of the loop: it depends only on the
        /// one config, not on the scene. Pre-resolving a landscape prompt the render will never use
        /// is an LLM call per scene for nothing — and the batch runs over the WHOLE book.
        boolean wantLandscape = landscapeEnabled(sdConfig);
        if (!wantLandscape) {
            logger.info("prepareSceneImagePrompts: landscape prompts SKIPPED for all "
                    + sceneObjectIds.size() + " scene(s) (" + landscapeSkipReason(sdConfig) + ")");
        }
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
                if (wantLandscape) {
                    resolveLandscapePrompt(user, scene, chatConfig, setting, mood, sdConfig, promptTemplateOverride);
                }

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
     * Re-derive the group that holds a PB2 book's charPerson records from a TRUSTED server-side anchor —
     * the book record's own world — never from a user-writable group path in the {@code .pictureBookMeta}
     * note.
     *
     * <p><b>Why this exists (security, horizontal-IDOR fix).</b> The meta note lives in a
     * {@code data.note} owned by the requesting user, so its {@code text} is rewritable by that user
     * through the generic {@code PATCH /rest/model} route. The previous implementation read a
     * {@code charsGroupPath} string straight out of that note and fed it to {@code PathUtil.findPath}
     * (which does NO read-authorization on the {@code doCreate=false} branch) and then to the
     * PBAC-BYPASSING {@code Search.findRecords}. A user could therefore rewrite their own book's meta to
     * point at another same-org user's world/population group and read that victim's characters
     * (name/gender/portrait/apparel). Cross-tenant was blocked by the org scope; same-org horizontal was
     * not. The group id fed to the charPerson search must instead be derived server-side from the book.
     *
     * <p><b>Why the {@code pb2BookObjectId} hint is safe even though it comes from the note.</b> Unlike a
     * raw group PATH, an object-id reference is resolved here through {@link PbBookUtil#readBook} →
     * {@code AccessPoint.find}, which runs per-record {@code canRead} on the result. A hint pointing at
     * ANOTHER user's book is denied at that read and yields {@code null} → the legacy fallback, never the
     * victim's world. Only a book THIS user is authorized to read resolves, and the group id is then
     * taken from that book's own world population group via the sanctioned read-only assembly
     * {@link PbBookUtil#openBookContext} → {@code BookContext.getGroupPath("population")} (the same
     * primitive {@code PbPipelineUtil} uses for the gallery group). {@code assembleBookContext} performs
     * the olio-principal world find internally and does not expose it, so no principal leaks here. This is
     * the SAME group {@code createFromScenes} wrote into, so a legitimate PB2 book resolves to its real
     * characters unchanged.
     *
     * @param pb2BookObjectId the {@code olio.pb.book} objectId (from the meta hint, or the request id
     *        itself); may be attacker-controlled — it is authorization-checked here, not trusted.
     * @return the PB2 world population group path for a book this user may read; {@code null} when the
     *         id is blank, names no readable pb2 book, or the book carries no resolvable world — in which
     *         case the caller falls back to the book's own {@code {bookGroupPath}/Characters} subgroup.
     */
    private static String deriveTrustedCharsGroupPath(BaseRecord user, String pb2BookObjectId, long orgId) {
        if (pb2BookObjectId == null || pb2BookObjectId.isBlank()) return null;
        // Authorization boundary: readBook -> AccessPoint.find runs canRead on the result, so a hint
        // naming another user's book returns null here (no disclosure), not the victim's world.
        BaseRecord book = PbBookUtil.readBook(user, pb2BookObjectId, orgId);
        if (book == null) return null;
        // Assemble the AUTHORIZED book's OWN world through the sanctioned read-only entry (openBookContext
        // -> assembleBookContext), then resolve the population group path via BookContext.getGroupPath.
        // This is the same primitive PbPipelineUtil uses for the gallery group. The population.path must
        // be read as an IN-MEMORY nested get off a fully-assembled/populated world (which getGroupPath
        // does: it populate()s the group and reads its virtual path). It CANNOT be read as a flat
        // setRequest({"population.path"}) projection — "population" is a foreign auth.group field on
        // olio.world, so Query rejects the dotted string as "Field 'population.path' was not found on
        // model olio.world" and the read returns null. (The createFromScenes write path has the same
        // broken flat-projection fallback at ~line 3850, but never reaches it because its in-memory
        // ctx.getWorld().get("population.path") succeeds first; that dead fallback is out of scope here.)
        BookContext bc = PbBookUtil.openBookContext(user, book);
        if (bc == null) return null;
        String populationPath = bc.getGroupPath(OlioFieldNames.FIELD_POPULATION);
        return (populationPath != null && !populationPath.isBlank()) ? populationPath : null;
    }

    /**
     * List a book's extracted characters (for the "Manage Characters" review/edit screen) —
     * objectId/name/gender/hasPortrait/apparelCount/per-apparel scene tags, plus failedApparel/
     * failedStatistics flags cross-referenced from the book's own meta (set during
     * extract()/createFromScenes() when createCharPerson's best-effort steps fail).
     */
    /**
     * Resolve a book group from EITHER a {@code data.group} objectId or an {@code olio.pb.book}
     * objectId — the UI passes whichever it has, depending on the code path.
     *
     * <p>Extracted from {@link #listCharacters} so {@link #mergeCharacters} resolves the book
     * identically. It was already duplicated between {@code listCharacters} and {@code reset};
     * a third hand-written copy in a path that DELETES records is not something to risk drifting.
     *
     * <p>Returns null for absent OR PBAC-denied — indistinguishable at the {@code AccessPoint.find}
     * boundary, and callers turn both into 404, which is the convention throughout this class.
     */
    static BaseRecord resolveBookGroupEither(BaseRecord user, String bookObjectId, long orgId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);
        if (bookGroup != null) return bookGroup;
        Query pbQ = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
        pbQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        pbQ.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_SLUG });
        BaseRecord pb2Book = IOSystem.getActiveContext().getAccessPoint().find(user, pbQ);
        if (pb2Book == null) return null;
        String slug = pb2Book.get(OlioFieldNames.FIELD_PB_SLUG);
        if (slug == null || slug.isBlank()) return null;
        return IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, "~/Data/" + PICTURE_BOOKS_DIR + "/" + slug,
                GroupEnumType.DATA.toString(), orgId);
    }

    /**
     * The group a book's charPerson records actually live in, derived SERVER-SIDE from the book's
     * own world — never from the {@code charsGroupPath} in the meta note.
     *
     * <p>That note is owned by, and PATCH-writable by, the requesting user, so a path taken from it
     * is attacker-controlled. Feeding it to a charPerson search was a horizontal IDOR (rewrite the
     * note to another same-org user's population group and read their characters), and feeding it to
     * {@link #mergeCharacters} would be strictly worse, because that path DELETES.
     *
     * <p>PB2 books resolve to their world's Population group (where {@code createCharPerson} writes);
     * legacy PB1 books fall back to {@code <book>/Characters}, which is structurally confined to the
     * caller's own book.
     *
     * <p>Extracted from {@link #listCharacters} so the merge path cannot diverge from it.
     */
    static BaseRecord resolveTrustedCharsGroup(BaseRecord user, String pb2Hint,
            String bookGroupPath, long orgId) {
        String trustedPopulationPath = deriveTrustedCharsGroupPath(user, pb2Hint, orgId);
        String charsGroupPath = (trustedPopulationPath != null)
                ? trustedPopulationPath : (bookGroupPath + "/" + CHARACTERS_DIR);
        return IOSystem.getActiveContext().getPathUtil().findPath(user,
                ModelNames.MODEL_GROUP, charsGroupPath, GroupEnumType.DATA.toString(), orgId);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listCharacters(BaseRecord user, String bookObjectId) {
        long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
        // Dual-lookup: a data.group objectId OR an olio.pb.book objectId — the UI passes whichever it
        // has. Shared with mergeCharacters via resolveBookGroupEither.
        BaseRecord bookGroup = resolveBookGroupEither(user, bookObjectId, orgId);
        if (bookGroup == null) throw new PictureBookException(404, "Book not found");
        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);

        // Parse the book's meta ONCE — but ONLY for the failedApparel/failedStatistics DISPLAY flags.
        // The characters GROUP is NOT taken from the meta: that note is owned by (and PATCH-writable by)
        // the requesting user, so any charsGroupPath inside it is attacker-controlled and must never
        // drive the PBAC-bypassing charPerson search (that was a horizontal IDOR — a user could rewrite
        // the note to another same-org user's population group and read their characters). We still read
        // the stored charsGroupPath, but solely to compare it against the trusted server-derived path and
        // warn on a mismatch; it is never fed to the query.
        Set<String> failedApparel = new HashSet<>();
        Set<String> failedStatistics = new HashSet<>();
        String storedCharsGroupPath = null;
        String metaPb2BookObjectId = null;
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec != null) {
            try {
                String metaJson = metaRec.get("text");
                if (metaJson != null && !metaJson.isEmpty()) {
                    Map<String, Object> meta = JSONUtil.getMap(metaJson.getBytes(), String.class, Object.class);
                    // Read ONLY as an authorization-checked object-id hint (never as a group path). It is
                    // resolved below through AccessPoint (readBook), so a tampered value cannot disclose
                    // another user's book.
                    Object pb2 = meta.get("pb2BookObjectId");
                    if (pb2 instanceof String && !((String) pb2).isBlank()) metaPb2BookObjectId = ((String) pb2).trim();
                    Object cgp = meta.get("charsGroupPath");
                    if (cgp instanceof String && !((String) cgp).isBlank()) storedCharsGroupPath = ((String) cgp).trim();
                    Object fa = meta.get("failedApparel");
                    if (fa instanceof List) for (Object o : (List<Object>) fa) failedApparel.add(String.valueOf(o));
                    Object fs = meta.get("failedStatistics");
                    if (fs instanceof List) for (Object o : (List<Object>) fs) failedStatistics.add(String.valueOf(o));
                }
            } catch (Exception e) {
                logger.warn("Failed to read pb2BookObjectId/charsGroupPath/failedApparel/failedStatistics from meta: " + e.getMessage());
            }
        }

        // Derive the characters group SERVER-SIDE from the book's own world (trusted), never from a
        // user-writable group path in the meta note. The pb2 book objectId hint is authorization-checked
        // by deriveTrustedCharsGroupPath (readBook -> AccessPoint canRead), so a tampered hint yields the
        // legacy fallback, not a victim's group. PB2 books resolve to their world population group (the
        // same group createFromScenes wrote into); legacy PB1 books fall back to their own
        // {bookGroupPath}/Characters subgroup, structurally confined to the caller's book. Prefer the meta
        // hint; otherwise the request id itself may be a pb2 book objectId (dual-lookup path above).
        String pb2Hint = (metaPb2BookObjectId != null) ? metaPb2BookObjectId : bookObjectId;
        BaseRecord charsGroup = resolveTrustedCharsGroup(user, pb2Hint, bookGroupPath, orgId);
        if (storedCharsGroupPath != null && charsGroup != null
                && !storedCharsGroupPath.equals(charsGroup.get(FieldNames.FIELD_PATH))) {
            logger.warn("listCharacters: stored charsGroupPath '" + storedCharsGroupPath + "' does not match the "
                + "server-derived characters group '" + charsGroup.get(FieldNames.FIELD_PATH)
                + "' — ignoring the stored value (possible meta tampering).");
        }
        if (charsGroup == null) return new ArrayList<>();

        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, charsGroup.get(FieldNames.FIELD_ID));
        q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        // Targeted projection of ONLY the fields this DTO consumes, via the canonical planField idiom
        // (see ChatUtil.getCharacterExportQuery). Two prior traps this avoids:
        //   1. planMost(true) recursively expands olio.charPerson's wide nested foreign models and can
        //      exceed PostgreSQL's 100-argument JSON_BUILD_OBJECT limit — DBSearch swallows that
        //      PSQLException and returns null/empty, which reads exactly like "no characters extracted".
        //   2. A DOTTED string in setRequest ("profile.portrait") is NOT a nested-path projection — Query
        //      treats it as a single literal field name, throws "Field 'profile.portrait' was not found on
        //      model olio.charPerson", and DBSearch again swallows it into a null result (same zero-character
        //      symptom). Nested foreign fields must be planned with planField(field, subFields, includeCommon).
        // Each apparel's inuse/attributes (not in apparel's default query set) are populated per-record below.
        q.plan(false);
        q.planField(FieldNames.FIELD_ID);
        q.planField(FieldNames.FIELD_OBJECT_ID);
        q.planField(FieldNames.FIELD_NAME);
        q.planField(FieldNames.FIELD_GROUP_ID);
        q.planField(FieldNames.FIELD_ORGANIZATION_ID);
        q.planField("gender");
        q.planField(FieldNames.FIELD_PROFILE, new String[] { "portrait" }, false);
        q.planField(FieldNames.FIELD_STORE, new String[] { OlioFieldNames.FIELD_APPAREL }, false);
        q.setCache(false);
        // Route the final character read through AccessClient/PBAC (AccessPoint.list -> authorizeQuery)
        // instead of the raw Search reader. The raw getSearch().findRecords(q) bypasses authorization
        // entirely and is bounded ONLY by the groupId+organizationId conditions on q; AccessPoint.list
        // adds a deterministic group-level read check — because common.groupExt.groupId is recursive,
        // PBAC resolves the auth.group named by this groupId and evaluates POLICY_SYSTEM_READ_OBJECT for
        // the CALLER against it, returning a failed QueryResult ("Query not authorized") if the caller
        // lacks read on that group. This is a defense-in-depth layer on top of the trusted server-side
        // group-path derivation above (deriveTrustedCharsGroupPath), not a replacement for it.
        QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
        BaseRecord[] chars = (qr != null && qr.getResults() != null) ? qr.getResults() : new BaseRecord[0];

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
                        IOSystem.getActiveContext().getReader().populate(a, new String[] { FieldNames.FIELD_ATTRIBUTES, OlioFieldNames.FIELD_IN_USE });
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
     * Result of a single {@link #deleteRecordExplained(BaseRecord, BaseRecord)} attempt. Carries the
     * concrete, human-readable reason a delete did not happen so the transport layer can surface it
     * verbatim (Issue 1: no more bare "Failed to delete"). {@code authorized} lets a caller map an
     * authorization failure to HTTP 403 versus a persistence failure to HTTP 500 without string-sniffing
     * the reason. On success {@code deleted=true}, {@code authorized=true}, {@code reason=null}.
     */
    public static class DeleteResult {
        public boolean deleted;
        public boolean authorized;
        public String reason;

        public DeleteResult() {}

        public DeleteResult(boolean deleted, boolean authorized, String reason) {
            this.deleted = deleted;
            this.authorized = authorized;
            this.reason = reason;
        }

        /** A successful delete: deleted + authorized, no reason. */
        public static DeleteResult ok() {
            return new DeleteResult(true, true, null);
        }

        /** A PBAC-denied delete: not deleted, not authorized, with a reason. */
        public static DeleteResult denied(String reason) {
            return new DeleteResult(false, false, reason);
        }

        /** An authorized delete that failed at persistence: not deleted but authorized, with a reason. */
        public static DeleteResult failed(String reason) {
            return new DeleteResult(false, true, reason);
        }
    }

    /**
     * Delete a single record and return a concrete, logged reason on failure instead of a bare boolean.
     * <p>
     * This is the Objects7 root of Issue 1 ("no more bare 'Failed to delete'"): it explicitly evaluates
     * {@code canDelete} first (so a PBAC denial is distinguishable from a persistence failure and carries
     * the actual policy messages), then performs the delete under a tightly-bracketed policy trace so the
     * server log holds the authorization reasoning for a persistence failure. The concrete reason is
     * logged here and returned to the caller so the transport layer can copy it through unchanged.
     *
     * @param actor  the acting user (also used as contextUser for the authorization evaluation)
     * @param target the record to delete
     * @return a {@link DeleteResult} — {@code deleted=true} on success; otherwise {@code deleted=false}
     *         with {@code authorized} distinguishing a PBAC denial (false) from a persistence failure
     *         (true), and a non-null {@code reason}
     */
    public static DeleteResult deleteRecordExplained(BaseRecord actor, BaseRecord target) {
        String schema = (target != null) ? target.getSchema() : "null";
        Object oid = (target != null) ? target.get(FieldNames.FIELD_OBJECT_ID) : null;
        PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canDelete(actor, actor, target);
        if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
            String detail = joinPolicyMessages(prr);
            String reason = "PBAC denied delete of " + schema + " " + oid
                + (detail.isEmpty() ? "" : ": " + detail);
            logger.warn(reason);
            return DeleteResult.denied(reason);
        }
        // canDelete already PERMITted above, so the delete is authorized; do NOT enable PolicyUtil.setTrace
        // here — it mutates a process-global singleton with no synchronization, so on a concurrent delete
        // one thread's finally-setTrace(false) races another thread's enable. The surfaced reason comes
        // from joinPolicyMessages / the persistence-failure string below, not from trace output.
        boolean ok = IOSystem.getActiveContext().getAccessPoint().delete(actor, target);
        if (!ok) {
            String reason = "Delete of " + schema + " " + oid
                + " failed at persistence (child/FK/writer); see server log.";
            logger.warn(reason);
            return DeleteResult.failed(reason);
        }
        return DeleteResult.ok();
    }

    /** Join the non-null policy-response messages (PolicyResponseType.getMessages) for a failure detail. */
    private static String joinPolicyMessages(PolicyResponseType prr) {
        if (prr == null) {
            return "";
        }
        List<String> messages = prr.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String m : messages) {
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(m);
            }
        }
        return sb.toString();
    }

    /**
     * Best-effort removal of orphaned {@code .pictureBookMeta} data.note records that reference a book
     * whose group/book no longer exists.
     * <p>
     * The PB1 "Legacy Books" list ({@code loadExistingBooks}) is populated by an ORG-WIDE search for
     * {@code data.note} records named {@code .pictureBookMeta}, keyed only on the {@code bookObjectId}
     * (or legacy {@code workObjectId}) embedded in each note's JSON {@code text}. {@link #reset} normally
     * deletes the meta note by walking the book GROUP path ({@code loadMeta} at {@code bookGroupPath});
     * when the group is already gone (the already-gone / 404 path) that walk never reaches the note, so
     * it survives and the list row REAPPEARS on the next reload. This finds the note(s) by the SAME JSON
     * linkage the list uses and deletes them, so a listed-but-already-gone book genuinely leaves the list.
     * <p>
     * Strictly scoped: only notes whose JSON {@code bookObjectId}/{@code workObjectId} equals
     * {@code bookObjectId} are deleted, so no other book's (or user's) meta is touched. Every failure is
     * swallowed and logged — this is a cleanup step, never a reason to fail the delete.
     *
     * @param user         the acting user (org-scoped search + authorized deletes)
     * @param bookObjectId the book objectId whose orphaned meta note(s) should be removed
     * @return the number of orphaned meta notes deleted
     */
    private static int deleteOrphanedMetaNotes(BaseRecord user, String bookObjectId) {
        if (user == null || bookObjectId == null || bookObjectId.isBlank()) {
            return 0;
        }
        int deleted = 0;
        try {
            long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
            // Mirror loadExistingBooks: org-wide name search for .pictureBookMeta notes, projecting text
            // so the JSON linkage can be read. An explicit organizationId condition is required for a
            // data.directory-derived list query or PBAC denies it.
            Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, META_NOTE_NAME);
            q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            q.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
                FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TEXT });
            q.setCache(false);
            BaseRecord[] notes = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
            if (notes == null) {
                return 0;
            }
            for (BaseRecord note : notes) {
                String text = note.get(FieldNames.FIELD_TEXT);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String ref = null;
                try {
                    Map<String, Object> m = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
                    if (m != null) {
                        Object b = m.get("bookObjectId");
                        Object w = m.get("workObjectId");
                        if (b instanceof String && !((String) b).isBlank()) {
                            ref = (String) b;
                        } else if (w instanceof String && !((String) w).isBlank()) {
                            ref = (String) w;
                        }
                    }
                } catch (Exception ignore) {
                    // Not JSON / unparseable — cannot be the note we are looking for.
                    continue;
                }
                if (bookObjectId.equals(ref)) {
                    try {
                        if (deleteRecordExplained(user, note).deleted) {
                            deleted++;
                            logger.info("Deleted orphaned .pictureBookMeta note {} referencing gone book {}",
                                note.get(FieldNames.FIELD_OBJECT_ID), bookObjectId);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to delete orphaned .pictureBookMeta note: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("deleteOrphanedMetaNotes failed for " + bookObjectId + ": " + e.getMessage());
        }
        return deleted;
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
     *
     * <p>Returns a {@link DeleteResult} rather than a bare boolean (Issue 1): a partial/persistence
     * failure surfaces {@code deleted=false} together with the concrete, logged {@code reason} of the
     * first failing terminal delete, so the transport layer can put that reason in the {@code reset:false}
     * response body instead of a generic literal. The incomplete/orphan and PB2 cleanup branches still
     * return a success result, and a genuinely-absent book still throws {@link PictureBookException} 404.
     *
     * @param user         the acting user
     * @param bookObjectId a data.group objectId or an olio.pb.book objectId
     * @return a {@link DeleteResult}: {@code deleted=true} on full success, else {@code deleted=false}
     *         with the concrete failure reason
     * @throws PictureBookException 404 when no such book/group exists, 403 from the incomplete/orphan guard
     */
    public static DeleteResult reset(BaseRecord user, String bookObjectId) {
        BaseRecord bookGroup = findBookGroup(user, bookObjectId);

        // If not found as a data.group objectId, try treating it as an olio.pb.book objectId
        String pb2BookToDelete = null;
        if (bookGroup == null) {
            long orgId2 = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
            Query pbQ = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
            pbQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId2);
            pbQ.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_SLUG });
            BaseRecord pb2Book = IOSystem.getActiveContext().getAccessPoint().find(user, pbQ);
            if (pb2Book == null) {
                // The acting user cannot READ the row through AccessPoint. Every olio.pb.book is owned
                // uniformly by the OLIO PRINCIPAL, and a book whose world creation FAILED mid-flight
                // never had the acting user's grants applied - so it is invisible and undeletable to
                // them through AccessPoint. That is exactly the reported "incomplete/failed book is
                // impossible to delete" defect. Re-resolve AS THE OLIO PRINCIPAL (the sanctioned pattern
                // for olio-owned rows) to tell "does not exist" apart from "exists but ungranted", then
                // delete it under a creator/orphan guard so this can never remove another user's book.
                if (deleteIncompleteBookAsOlio(user, bookObjectId, orgId2)) {
                    return DeleteResult.ok();
                }
            }
            if (pb2Book != null) {
                pb2BookToDelete = bookObjectId;
                String slug = pb2Book.get(OlioFieldNames.FIELD_PB_SLUG);
                if (slug != null && !slug.isBlank()) {
                    String bookPath = "~/Data/" + PICTURE_BOOKS_DIR + "/" + slug;
                    bookGroup = IOSystem.getActiveContext().getPathUtil().findPath(user,
                        ModelNames.MODEL_GROUP, bookPath, GroupEnumType.DATA.toString(), orgId2);
                }
            }
        }

        if (bookGroup == null) {
            // The book GROUP is gone, so the group-path walk below (loadMeta at bookGroupPath) can never
            // reach this book's .pictureBookMeta note. But the PB1 "Legacy Books" list (loadExistingBooks)
            // finds that note by an ORG-WIDE search on name=".pictureBookMeta", keyed only on the
            // bookObjectId/workObjectId embedded in the note's JSON — so an already-gone delete that
            // stops here leaves the note behind and the row REAPPEARS on the next reload (the reported
            // residual gap). Clear any meta note referencing THIS bookObjectId by the same JSON linkage
            // the list uses, so a listed-but-already-gone book genuinely leaves the list. Runs before
            // BOTH the benign ok() (pb2BookToDelete != null) and the 404 (pb2BookToDelete == null)
            // branches below, and preserves the 404 signal so the UX still shows "Already removed".
            deleteOrphanedMetaNotes(user, bookObjectId);

            // Orphaned record — no data.group found (creation likely failed mid-flight).
            // Delete whatever olio.pb.book record we found so the user can clear it from the list.
            if (pb2BookToDelete != null) {
                try {
                    long orgId4 = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
                    Query pbDelQ2 = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, pb2BookToDelete);
                    pbDelQ2.field(FieldNames.FIELD_ORGANIZATION_ID, orgId4);
                    BaseRecord orphan = IOSystem.getActiveContext().getAccessPoint().find(user, pbDelQ2);
                    if (orphan != null) {
                        IOSystem.getActiveContext().getAccessPoint().delete(user, orphan);
                        logger.info("Deleted orphaned olio.pb.book record: " + pb2BookToDelete);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to delete orphaned olio.pb.book: " + e.getMessage());
                }
                return DeleteResult.ok();
            }
            throw new PictureBookException(404, "Book not found");
        }

        String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
        boolean ok = true;
        // Capture the concrete reason of the first terminal-delete failure so the transport layer can
        // surface it in the reset:false response body (Issue 1) instead of a generic literal.
        String failReason = null;

        // Read meta before deleting to capture pb2BookObjectId for olio.pb.book cleanup
        if (pb2BookToDelete == null) {
            BaseRecord metaForPb2 = loadMeta(user, bookGroupPath);
            if (metaForPb2 != null) {
                try {
                    String metaText = metaForPb2.get(FieldNames.FIELD_TEXT);
                    if (metaText != null) {
                        Map<String, Object> metaMap = JSONUtil.getMap(metaText.getBytes(), String.class, Object.class);
                        if (metaMap != null) {
                            Object pb2ObjIdObj = metaMap.get("pb2BookObjectId");
                            if (pb2ObjIdObj instanceof String && !((String) pb2ObjIdObj).isBlank()) {
                                pb2BookToDelete = (String) pb2ObjIdObj;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // Recursively delete sub-groups (Scenes/, Characters/) and everything nested under them
        for (String sub : new String[]{"Scenes", "Characters"}) {
            String subPath = bookGroupPath + "/" + sub;
            BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
                    ModelNames.MODEL_GROUP, subPath, GroupEnumType.DATA.toString(),
                    (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
            if (grp != null) {
                try {
                    if (!deleteGroupRecursive(user, grp)) {
                        ok = false;
                        if (failReason == null) {
                            failReason = "Recursive delete of the " + sub + " group failed; see server log.";
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to recursively delete " + sub + " group: " + e.getMessage());
                    ok = false;
                    if (failReason == null) {
                        failReason = "Recursive delete of the " + sub + " group threw: " + e.getMessage();
                    }
                }
            }
        }

        // Delete .pictureBookMeta record
        BaseRecord metaRec = loadMeta(user, bookGroupPath);
        if (metaRec != null) {
            try {
                // Best-effort: a meta delete failure does not fail the whole reset (unchanged semantics).
                // deleteRecordExplained logs the concrete reason on failure.
                deleteRecordExplained(user, metaRec);
            } catch (Exception e) {
                logger.warn("Failed to delete meta: " + e.getMessage());
            }
        }

        // Delete the book group itself
        try {
            DeleteResult groupDel = deleteRecordExplained(user, bookGroup);
            if (!groupDel.deleted) {
                ok = false;
                if (failReason == null) {
                    failReason = groupDel.reason;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to delete book group: " + e.getMessage());
            ok = false;
            if (failReason == null) {
                failReason = "Delete of the book group threw: " + e.getMessage();
            }
        }

        // Delete the olio.pb.book record when this is (or was) a PB2 book
        if (pb2BookToDelete != null) {
            try {
                long orgId3 = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
                Query pbDelQ = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, pb2BookToDelete);
                pbDelQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId3);
                BaseRecord pb2BookRec = IOSystem.getActiveContext().getAccessPoint().find(user, pbDelQ);
                if (pb2BookRec != null) {
                    // Best-effort: deleteRecordExplained logs the concrete reason on failure; a failure
                    // here does not fail the whole reset (unchanged semantics).
                    deleteRecordExplained(user, pb2BookRec);
                }
            } catch (Exception e) {
                logger.warn("Failed to delete olio.pb.book record: " + e.getMessage());
            }
        }

        return ok ? DeleteResult.ok() : DeleteResult.failed(failReason);
    }

    /**
     * Delete an incomplete/failed {@code olio.pb.book} that the acting user cannot reach through
     * {@code AccessPoint} because the row is still owned solely by the olio principal - the state
     * {@code PbBookUtil.createBook} leaves behind when world creation throws after {@code writeBookRow}
     * but before the acting user's grants are applied. Resolving and deleting AS THE OLIO PRINCIPAL is
     * the sanctioned pattern for olio-principal-owned rows (see troubleshooting.md /
     * {@code WorldUtil.deleteWorld}); it is not a PBAC bypass, because the olio principal is the record's
     * legitimate owner. The creator/orphan guard below is the authorization decision for whether the
     * acting user may trigger that cleanup - a stranger can never remove another user's complete book.
     *
     * @return {@code true} if a row was found and deleted; {@code false} if no such row exists (the
     *         caller then proceeds to its normal 404). Throws {@link PictureBookException} 403 if the
     *         row exists, is {@code COMPLETE}, and was created by a different user.
     */
    static boolean deleteIncompleteBookAsOlio(BaseRecord user, String bookObjectId, long orgId) {
        BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
        if (olioUser == null) {
            return false;
        }
        Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
        q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        q.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_SLUG,
            OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, OlioFieldNames.FIELD_PB_BOOK_STATUS,
            OlioFieldNames.FIELD_PB_WORLD });
        q.setCache(false);
        BaseRecord asOlio = IOSystem.getActiveContext().getAccessPoint().find(olioUser, q);
        if (asOlio == null) {
            // Genuinely no such row (even the owner cannot find it) - let the caller raise its 404.
            return false;
        }
        String createdBy = asOlio.get(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID);
        String actorOid = user.get(FieldNames.FIELD_OBJECT_ID);
        boolean noCreator = (createdBy == null || createdBy.isBlank());
        // "Orphaned" (deletable-by-anyone cleanup) is strictly a book with NO creator link AND NO world:
        // the failed-mid-creation state createBook leaves behind (row written, world creation threw). A
        // book that HAS a world is a real book, never orphaned — so a null-createdBy edge case on a real
        // book can no longer be captured and deleted by a non-creator. isCreator remains the primary path.
        boolean hasWorld = (asOlio.get(OlioFieldNames.FIELD_PB_WORLD) != null);
        boolean orphaned = noCreator && !hasWorld;
        boolean isCreator = (!noCreator && createdBy.equals(actorOid));
        // Enums read back UPPERCASE in Java but a projected list value can be the raw lowercase -
        // compare case-insensitively (see CLAUDE.md).
        String statusStr = asOlio.get(OlioFieldNames.FIELD_PB_BOOK_STATUS);
        boolean complete = "COMPLETE".equalsIgnoreCase(statusStr);
        if (!isCreator && !(orphaned && !complete)) {
            // The row belongs to another user (or is a complete book with no creator link) and this
            // user holds no grant on it - deny rather than silently escalate to an olio-principal delete.
            throw new PictureBookException(403, "Not authorized to delete this book");
        }
        // Best-effort: clear the book's own group too (writeBookRow created it, olio-owned) so a failed
        // partial world does not linger and produce PathProvider "parent not found" log spam (KI-32).
        try {
            String slug = asOlio.get(OlioFieldNames.FIELD_PB_SLUG);
            if (slug != null && !slug.isBlank()) {
                String bookGroupPath = PbBookUtil.bookGroupPath(slug);
                BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(olioUser,
                    ModelNames.MODEL_GROUP, bookGroupPath, GroupEnumType.DATA.toString(), orgId);
                if (grp != null) {
                    deleteGroupRecursive(olioUser, grp);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to clean incomplete book group for " + bookObjectId + ": " + e.getMessage());
        }
        // The extended deleteGroupRecursive above now deletes the olio.pb.book row itself (step 4b, so a
        // same-slug recreate cannot collide), so the book row is normally ALREADY gone here. Re-check
        // before deleting it again: a second deleteRecordExplained on an already-deleted row would evaluate
        // canDelete against a nonexistent record, come back non-PERMIT, and throw a spurious 403. Only when
        // the row survived the group cleanup (e.g. it lived outside the Book group) do we delete it here.
        Query recheckQ = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
        recheckQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        recheckQ.setRequest(new String[]{ FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
        recheckQ.setCache(false);
        BaseRecord residual = IOSystem.getActiveContext().getSearch().findRecord(recheckQ);
        if (residual != null) {
            // The olio principal owns the row, so canDelete normally PERMITs and a non-deleted result is a
            // genuine persistence failure (FK/writer). Surface its concrete reason instead of returning
            // false: false falls through to the caller's generic 404 "ChapBook not found", exactly the
            // "general failure to delete with no reason" Issue 1 set out to kill. Map on the DeleteResult's
            // authorized flag exactly as deleteChapBook does — persistence failure 500, unexpected PBAC
            // denial 403.
            DeleteResult delRes = deleteRecordExplained(olioUser, residual);
            if (!delRes.deleted) {
                String reason = (delRes.reason != null && !delRes.reason.isBlank())
                    ? delRes.reason
                    : "Failed to delete incomplete book " + bookObjectId + "; see server log.";
                throw new PictureBookException(delRes.authorized ? 500 : 403, reason);
            }
        }
        logger.info("Deleted incomplete/failed olio.pb.book as olio principal: " + bookObjectId
            + " (orphaned=" + orphaned + ", isCreator=" + isCreator + ", status=" + statusStr + ")");
        return true;
    }

    /**
     * COMPLETE teardown of a readable CHAPBOOK/PictureBook and its entire book-world footprint, so that
     * NOTHING survives that could collide with a same-slug recreate.
     * <p>
     * This is the fix for the reported delete/recreate defect: the old readable path deleted ONLY the
     * {@code olio.pb.book} row (via {@code deleteRecordExplained}), leaving the {@code /Book} group, every
     * {@code olio.pb.scene} row, the workflow graph, the {@code olio.world}, and the cached
     * {@code OlioContext} behind. A same-slug recreate then reused the leftover {@code /Book} group and
     * every new scene {@code "Scene <slug> <idx>"} collided with a leftover scene on the unique
     * {@code (name, groupId, organizationId)} index — {@code createScene} threw, {@code createChapBook}
     * swallowed it, and a BLANK book was produced.
     * <p>
     * <b>Authorization</b> is decided as the acting {@code user} ({@code canDelete}); a read-but-not-delete
     * caller still gets a denial (mapped to 403 upstream). The <b>physical deletes</b> then run AS THE OLIO
     * PRINCIPAL — the legitimate owner of every {@code olio.pb.book}/{@code olio.world} row and of the book
     * groups {@code writeBookRow}/{@code BookWorldInitializationRule} created — which is the sanctioned
     * pattern for olio-owned rows (see {@code WorldUtil.deleteWorld} / troubleshooting.md), not a PBAC
     * bypass.
     * <p>
     * Order (each step tolerant of already-absent pieces): (1) resolve the world find-only via
     * {@code WorldUtil.findWorld}, capturing its objectId for cache eviction; (2) run the extended
     * {@link #deleteGroupRecursive} on the {@code Book}/{@code Workflow}/{@code Artifacts} subgroups —
     * this removes the PB2 rows (scenes/book/workflow/nodes/…) and those groups while the world's own
     * event/population FK groups still exist; (3) {@code WorldUtil.deleteWorld} to purge the world's
     * event/population records, the container group tree, and the world record itself; (4) a safety net
     * that deletes the book row directly if step 2 somehow missed it; (5) evict the cached
     * {@code OlioContext} keyed by the world so the recreate builds a fresh world rather than reusing a
     * stale context. Poems ({@code olio.cb.poem}) are deliberately NOT touched — they are user source
     * content, referenced only for their text and reused across a recreate.
     *
     * @param user  the acting user (authorization subject)
     * @param book  the book row (as returned by {@code PbBookUtil.readBook}; must carry slug + identity)
     * @param orgId the organization id
     * @return a {@link DeleteResult}: {@code deleted=true} on full teardown; a PBAC denial or a residual
     *         persistence failure otherwise, with the concrete reason
     */
    static DeleteResult teardownBookWorld(BaseRecord user, BaseRecord book, long orgId) {
        String bookOid = (book != null) ? book.get(FieldNames.FIELD_OBJECT_ID) : null;
        // 0. Authorization is the acting user's decision (preserves the read-but-not-delete → 403 case).
        PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canDelete(user, user, book);
        if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
            String detail = joinPolicyMessages(prr);
            // Lead with "Not authorized to delete this ChapBook" (consistent with the stranger/incomplete
            // deny wording) so the deny reason is legible, and append the PBAC policy detail for diagnostics.
            String reason = "Not authorized to delete this ChapBook " + bookOid
                + (detail.isEmpty() ? "" : ": " + detail);
            logger.warn(reason);
            return DeleteResult.denied(reason);
        }

        // Physical deletes run as the olio principal — the legitimate owner of the olio.pb.book/world rows
        // and the book groups. Fall back to the acting user only if the org has no olio principal.
        BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
        if (olioUser == null) {
            olioUser = user;
        }

        String slug = (book.hasField(OlioFieldNames.FIELD_PB_SLUG) ? book.get(OlioFieldNames.FIELD_PB_SLUG) : null);
        if (slug == null || slug.isBlank()) {
            // No slug to resolve the world/groups by — the safest we can do is delete the book row itself.
            logger.warn("teardownBookWorld: book " + bookOid + " has no slug; deleting the row only");
            return deleteRecordExplained(olioUser, book);
        }

        boolean ok = true;
        // 1. Resolve the world find-only (olio principal owns it) and capture its objectId for eviction.
        String worldObjectId = null;
        BaseRecord world = null;
        try {
            world = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug);
            if (world != null) {
                worldObjectId = world.get(FieldNames.FIELD_OBJECT_ID);
            }
        } catch (Exception e) {
            logger.warn("teardownBookWorld: failed to resolve world for slug=" + slug + ": " + e.getMessage());
        }

        // 2. Delete the PB2 rows + their groups (Book/Workflow/Artifacts) via the extended recursive walk.
        for (String grpPath : new String[] { PbBookUtil.bookGroupPath(slug), PbBookUtil.workflowGroupPath(slug),
                PbBookUtil.artifactGroupPath(slug) }) {
            BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(olioUser,
                ModelNames.MODEL_GROUP, grpPath, GroupEnumType.DATA.toString(), orgId);
            if (grp != null && !deleteGroupRecursive(olioUser, grp)) {
                ok = false;
            }
        }

        // 3. Purge the world (event/population records, container group tree, world record). When the world
        // is genuinely absent, fall back to clearing the container group tree so no orphan groups linger.
        if (world != null) {
            try {
                if (!WorldUtil.deleteWorld(olioUser, world)) {
                    ok = false;
                }
            } catch (Exception e) {
                logger.warn("teardownBookWorld: WorldUtil.deleteWorld failed for slug=" + slug + ": " + e.getMessage());
                ok = false;
            }
        } else {
            BaseRecord container = IOSystem.getActiveContext().getPathUtil().findPath(olioUser,
                ModelNames.MODEL_GROUP, PbBookUtil.bookContainerPath(slug), GroupEnumType.DATA.toString(), orgId);
            if (container != null && !deleteGroupRecursive(olioUser, container)) {
                ok = false;
            }
        }

        // 4. Safety net: if the book row somehow survived step 2 (e.g. it lived outside the Book group),
        // delete it directly and surface a concrete reason on failure.
        Query bookQ = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
        bookQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
        bookQ.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID });
        bookQ.setCache(false);
        BaseRecord residualBook = IOSystem.getActiveContext().getSearch().findRecord(bookQ);
        if (residualBook != null) {
            DeleteResult bookDel = deleteRecordExplained(olioUser, residualBook);
            if (!bookDel.deleted) {
                return bookDel;
            }
        }

        // 5. Evict the cached OlioContext keyed by this world so a same-slug recreate builds a fresh world.
        if (worldObjectId != null) {
            try {
                int evicted = OlioContextUtil.evictByWorld(orgId, worldObjectId);
                logger.info("teardownBookWorld: evicted " + evicted + " cached OlioContext(s) for world " + worldObjectId);
            } catch (Exception e) {
                logger.warn("teardownBookWorld: evictByWorld failed for world=" + worldObjectId + ": " + e.getMessage());
            }
        }

        if (!ok) {
            return DeleteResult.failed("Book " + bookOid + " teardown completed with one or more non-fatal "
                + "failures deleting nested artifacts; see server log.");
        }
        return DeleteResult.ok();
    }

    /**
     * Recursively delete a group's contents bottom-up, then the group itself — entirely through
     * {@code AccessPoint} (PBAC-respecting) per-record deletes, never the PBAC-bypassing raw
     * {@code writer.delete(query)} pattern used elsewhere (e.g. {@code WorldUtil.cleanupWorld}),
     * since this is a user-invoked action that must still respect ownership/authorization. See
     * KI-32. Order: nested {@code auth.group} subgroups first (deepest-first, recursively), then
     * {@code data.note} children (scene notes), then {@code olio.charPerson} children, then any
     * {@code data.data} children (generated images grouped directly here), then the PB2 graph rows
     * grouped here ({@code olio.pb.binding/run/node/workflow/scene/book}, child→parent), then the
     * group itself. The PB2-row step is what makes a book-world teardown COMPLETE: without it, scene
     * and book rows survive their deleted group and collide on the unique {@code (name, groupId,
     * organizationId)} index when a same-slug book is recreated (the reported delete/recreate defect).
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

        // 4b. Delete PB2 rows grouped here, child→parent so a foreign-key delete never precedes its
        // referent: binding→node, run→(workflow/node), node→workflow, scene→book. The Book group holds
        // scene + book rows; the Workflow group holds workflow/node/binding/run rows. These are NOT
        // data.note / data.data / olio.charPerson, so steps 2-4 never reached them — and neither did
        // WorldUtil.deleteGroupTree, which deletes GROUPS only. That is exactly why a same-slug recreate
        // collided: leftover olio.pb.scene rows survived their (deleted) group and tripped the unique
        // (name, groupId, organizationId) index. Harmless no-op for PB1/reset() groups that hold none.
        String[] pbChildToParent = new String[] {
                OlioModelNames.MODEL_PB_BINDING, OlioModelNames.MODEL_PB_RUN, OlioModelNames.MODEL_PB_NODE,
                OlioModelNames.MODEL_PB_WORKFLOW, OlioModelNames.MODEL_PB_SCENE, OlioModelNames.MODEL_PB_BOOK
        };
        for (String pbModel : pbChildToParent) {
            Query pbQ = QueryUtil.createQuery(pbModel, FieldNames.FIELD_GROUP_ID, groupId);
            pbQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
            pbQ.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
            pbQ.setCache(false);
            BaseRecord[] pbRows = IOSystem.getActiveContext().getSearch().findRecords(pbQ);
            for (BaseRecord r : pbRows) {
                try {
                    IOSystem.getActiveContext().getAccessPoint().delete(user, r);
                } catch (Exception e) {
                    logger.warn("Failed to delete " + pbModel + " " + r.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
                    ok = false;
                }
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
