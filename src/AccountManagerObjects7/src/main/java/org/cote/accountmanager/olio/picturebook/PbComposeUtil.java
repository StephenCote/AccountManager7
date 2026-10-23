package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PbLayoutUtil.Placement;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PbFacingEnumType;
import org.cote.accountmanager.schema.type.PbImportanceEnumType;
import org.cote.accountmanager.schema.type.PbReferencePolicyEnumType;
import org.cote.accountmanager.schema.type.PbShotTypeEnumType;
import org.cote.accountmanager.schema.type.PbViewpointEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.AttributeUtil;

/**
 * PictureBook V3 Phase 4 (design §9, §29): the compose step. Stages one scene as one illustration -
 * who is in the picture, what each figure does and where it stands, where the camera is and what else
 * is in frame - and persists the result as one {@code olio.pb.composition} with its
 * {@code olio.pb.sceneActor} and {@code olio.pb.sceneElement} rows, in the scene's own group, as the
 * request user through {@code AccessPoint}.
 * <p>
 * <b>Sources.</b> {@code LLM}: the {@code pictureBook.compose-scene} prompt answered and parsed.
 * {@code MIGRATED}: built from the scene's own fields and roster without an LLM - the fallback when the
 * LLM is unavailable or its reply cannot be parsed, and the one-off migration shape (P4-10). A
 * {@code MIGRATED} composition that the user has not edited is flagged for re-compose by
 * {@link #needsCompose(BaseRecord)}. {@code USER}: never written here; a row-level edit through the
 * board sets {@code userEdited}, and such a composition is never overwritten unless {@code force}.
 * <p>
 * <b>Never blocks.</b> A compose failure never destroys an existing composition: the reply is called
 * and parsed before anything is deleted, and when there is nothing to fall back on the existing rows are
 * left untouched and the result says so ({@link ComposeResult#hardFailure}). Names the LLM uses that
 * resolve to no character are kept as {@code NAMED_BACKGROUND} rows with {@code character = null},
 * never dropped (§9.1). Unknown placement words fall back to {@link PbLayoutUtil#defaultPlacement} and
 * are reported; nothing here throws on LLM content.
 */
public class PbComposeUtil {
	public static final Logger logger = LogManager.getLogger(PbComposeUtil.class);

	public static final String PROMPT_COMPOSE_SCENE = "pictureBook.compose-scene";
	public static final String COMPOSITION_NAME_PREFIX = "composition.";
	/** A ChapBook page's {@code CHAPBOOK_PAGE} composition is named {@code page.<sceneObjectId>} (P4-8). */
	public static final String PAGE_COMPOSITION_NAME_PREFIX = "page.";
	public static final String ACTOR_NAME_PREFIX = "actor.";
	public static final String ELEMENT_NAME_PREFIX = "element.";

	/** Scene text handed to the prompt is capped here; longer text is truncated with a warning. */
	public static final int MAX_SCENE_TEXT_CHARS = 4096;
	/** Per-cast-member description cap in the CAST block. */
	public static final int MAX_CAST_DESCRIPTION_CHARS = 256;
	/** When the scene has no roster and no scene-ref hits, at most this many population members are offered. */
	public static final int MAX_POPULATION_CAST = 40;
	/** Cap applied to every free-text string taken from the LLM reply. */
	public static final int MAX_FIELD_CHARS = 512;
	/** Reply rows beyond these are dropped with a warning: every LLM-driven loop that writes rows carries a cap. */
	public static final int MAX_ACTORS = 24;
	public static final int MAX_ELEMENTS = 32;
	/** Largest {@code count} a CROWD row may carry. */
	public static final int MAX_CROWD_COUNT = 500;
	static final int MAX_EXPRESSION_CHARS = 256;
	static final int MAX_MOOD_CHARS = 256;
	static final int MAX_CAST_LIST = 200;
	static final String NONE = "(none)";
	static final Pattern FIRST_INTEGER = Pattern.compile("[0-9]{1,9}");
	static final String RETRY_HINT = "\n\nIMPORTANT: your previous reply could not be parsed as JSON (it was truncated or malformed). "
		+ "Reply with ONLY a single complete, valid JSON object. Keep every string short so the whole object fits in one reply.";

	/** Which pool the CAST block (and the MIGRATED roster) came from. */
	public enum CastTier {
		/** {@code scene.characters} participations. */
		SCENE_MEMBERS,
		/** Population members whose {@code pbSceneRefs} attribute lists this scene index. */
		SCENE_REFS,
		/** The whole population (capped at {@link #MAX_POPULATION_CAST}). */
		POPULATION,
		/** No characters were available at all. */
		NONE
	}

	/** Outcome of one compose. Fields are public and plain so Service7 can serialize it as-is. */
	public static final class ComposeResult {
		public BaseRecord composition;
		public List<BaseRecord> actors = new ArrayList<>();
		public List<BaseRecord> elements = new ArrayList<>();
		public PbCompositionSourceEnumType source = PbCompositionSourceEnumType.UNKNOWN;
		/** The existing composition is user-edited and {@code force} was false: nothing was called or written. */
		public boolean skippedUserEdited;
		/** The LLM path failed and a {@code MIGRATED} composition was written instead. */
		public boolean migratedFallback;
		/** Nothing could be written (LLM failed and an existing composition was left untouched, or a write failed). */
		public boolean hardFailure;
		public String failureReason;
		public List<String> warnings = new ArrayList<>();
		/** Names the LLM used that resolved to no character (kept as NAMED_BACKGROUND rows). */
		public List<String> unresolvedNames = new ArrayList<>();
		/** Placement words the LLM used that {@link PbLayoutUtil#place} did not recognise. */
		public List<String> unrecognizedPlacements = new ArrayList<>();
		/** What the LLM said about the place ({@code name / sameAs / variant}); informational, never bound. */
		public String locationHint;
		public CastTier castTier = CastTier.NONE;
		public long elapsedMs;
		/**
		 * The job's cancel flag was up when the LLM call returned (the registry's cancel scope aborts the
		 * stream): nothing was written, and an existing composition is left as it was. Not a failure.
		 */
		public boolean cancelled;
		/** Outfit changes the LLM reported: alternates created, existing alternates reused, and ones that could not be built (warned). */
		public int outfitsCreated;
		public int outfitsReused;
		public int outfitsSkipped;
		/** Actor rows that kept the pin of the composition being replaced (same character). */
		public int pinsPreserved;

		public boolean isWritten() {
			return composition != null && !skippedUserEdited && !hardFailure && !cancelled;
		}
	}

	/** What {@link #ensureComposition} found or did. */
	public static final class EnsureResult {
		/** The composition the render should plan from; null only when the compose was cancelled or wrote nothing. */
		public BaseRecord composition;
		/** True when this call composed the scene (the render ran ahead of the compose job: COMPOSED_OUT_OF_ORDER). */
		public boolean composedNow;
		/** The compose outcome when {@code composedNow}; null when an existing composition was found. */
		public ComposeResult result;
	}

	// ─────────────────────────────── parsed reply ───────────────────────────────

	/** The LLM reply after {@link #applyReply(Map)}: words only, nothing resolved yet. Package-private for tests. */
	static final class Proposal {
		String locationName;
		String sameAs;
		String variant;
		String shotWord;
		String viewpointWord;
		String focalName;
		List<ActorSpec> actors = new ArrayList<>();
		List<ElementSpec> elements = new ArrayList<>();
		String mood;
		/** Rows the reply carried beyond {@link #MAX_ACTORS} / {@link #MAX_ELEMENTS}, dropped. */
		int droppedActors;
		int droppedElements;

		boolean isEmpty() {
			return actors.isEmpty() && elements.isEmpty();
		}
	}

	static final class ActorSpec {
		String name;
		String roleWord;
		String pose;
		String expression;
		String action;
		String placement;
		String description;
		Integer count;
		/** What the figure wears, only when the scene text states it (design §6.1); null otherwise. */
		String outfit;
	}

	static final class ElementSpec {
		String typeWord;
		String description;
		String placement;
	}

	/** One actor after name resolution, role rules and layout. */
	static final class ResolvedActor {
		BaseRecord character;
		String displayName;
		PbActorRoleEnumType role = PbActorRoleEnumType.UNKNOWN;
		String pose;
		String expression;
		String action;
		String description;
		String placementWord;
		int count = 1;
		Placement placement;
		boolean placementRecognized;
		String outfit;
		/** The wardrobe entry this row is pinned to: a preserved pin, or the alternate built for {@link #outfit}. Null = resolved at read time. */
		BaseRecord apparel;
	}

	static final class ResolvedElement {
		PbElementTypeEnumType type = PbElementTypeEnumType.OTHER;
		String description;
		String placementWord;
		Placement placement;
		BaseRecord item;
	}

	/** Everything that becomes rows: composition fields plus the resolved actors and elements. */
	static final class Staging {
		PbShotTypeEnumType shot = PbShotTypeEnumType.MEDIUM;
		PbViewpointEnumType viewpoint = PbViewpointEnumType.EYE_LEVEL;
		String cameraText;
		String mood;
		String settingText;
		List<ResolvedActor> actors = new ArrayList<>();
		List<ResolvedElement> elements = new ArrayList<>();
		int focalIndex = -1;
		PbCompositionSourceEnumType source = PbCompositionSourceEnumType.UNKNOWN;
	}

	// ─────────────────────────────── projections ───────────────────────────────

	public static String[] compositionRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_PURPOSE, OlioFieldNames.FIELD_PB_SCENE,
			FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT, OlioFieldNames.FIELD_PB_SETTING_TEXT,
			OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_PB_SHOT_TYPE,
			OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, OlioFieldNames.FIELD_PB_CAMERA_TEXT,
			OlioFieldNames.FIELD_PB_PROPS, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE,
			OlioFieldNames.FIELD_PB_PROMPT_LOCKED, OlioFieldNames.FIELD_PB_SOURCE, OlioFieldNames.FIELD_PB_USER_EDITED,
			OlioFieldNames.FIELD_PB_REVISION
		};
	}

	public static String[] actorRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_COMPOSITION, OlioFieldNames.FIELD_PB_CHARACTER, OlioFieldNames.FIELD_PB_ROLE,
			OlioFieldNames.FIELD_PB_PRIORITY, OlioFieldNames.FIELD_APPAREL, OlioFieldNames.FIELD_PB_POSE,
			FieldNames.FIELD_EXPRESSION, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_FACING,
			OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H,
			OlioFieldNames.FIELD_PB_DEPTH, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_COUNT,
			OlioFieldNames.FIELD_PB_REFERENCE_POLICY
		};
	}

	public static String[] elementRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_COMPOSITION, OlioFieldNames.FIELD_PB_ELEMENT_TYPE, FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H,
			OlioFieldNames.FIELD_PB_DEPTH, OlioFieldNames.FIELD_PB_IMPORTANCE, OlioFieldNames.FIELD_ITEM
		};
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/**
	 * The scene's {@code SCENE}-purpose composition as {@code user} reads it, or null. Uncached. Rows whose
	 * purpose is anything else — including an unset purpose, which reads {@code UNKNOWN} — are ignored. More than
	 * one is a defect that is logged and resolved to the lowest id so the caller still gets a deterministic answer.
	 */
	public static BaseRecord findSceneComposition(BaseRecord user, BaseRecord scene, long bookGroupId, long orgId) {
		return findSceneComposition(user, scene, bookGroupId, orgId, PbCompositionPurposeEnumType.SCENE);
	}

	/**
	 * The scene's composition of the given {@code purpose} ({@code SCENE} for an illustrated scene,
	 * {@code CHAPBOOK_PAGE} for a ChapBook page) as {@code user} reads it, or null. Uncached. Rows of any other
	 * purpose — including an unset one, which reads {@code UNKNOWN} — are ignored. More than one is a defect that
	 * is logged and resolved to the lowest id so the caller still gets a deterministic answer.
	 */
	public static BaseRecord findSceneComposition(BaseRecord user, BaseRecord scene, long bookGroupId, long orgId, PbCompositionPurposeEnumType purpose) {
		if(user == null || scene == null || purpose == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_SCENE, scene);
		q.field(FieldNames.FIELD_GROUP_ID, bookGroupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(compositionRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		if(recs == null || recs.length == 0) {
			return null;
		}
		List<BaseRecord> comps = PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_COMPOSITION);
		BaseRecord hit = null;
		int hits = 0;
		for(BaseRecord c : comps) {
			String cp = str(c.get(OlioFieldNames.FIELD_PB_PURPOSE));
			if(!purpose.toString().equalsIgnoreCase(cp)) {
				continue;
			}
			hits++;
			if(hit == null) {
				hit = c;
			}
		}
		if(hits > 1) {
			logger.warn("Scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + " has " + hits
				+ " " + purpose + " compositions; using the lowest id " + hit.get(FieldNames.FIELD_ID));
		}
		return hit;
	}

	/**
	 * Every {@code SCENE}-purpose composition of the book, keyed by the scene's numeric id, in one query -
	 * the shape a whole-book tally needs instead of one {@link #findSceneComposition} per scene. Resolved like
	 * {@code findSceneComposition}: the book's group (the scenes' group, where every composition is written),
	 * non-SCENE purposes ignored, duplicates to the lowest id. {@code AccessPoint.list} is not a per-record
	 * boundary, so without the group condition a row in another group of the organization that points at the
	 * book would count. Uncached.
	 *
	 * @throws PictureBookException 409 when the book record carries no group
	 */
	public static Map<Long, BaseRecord> listSceneCompositions(BaseRecord user, BaseRecord book) {
		Map<Long, BaseRecord> bySceneId = new HashMap<>();
		if(user == null || book == null) {
			return bySceneId;
		}
		Long gid = book.get(FieldNames.FIELD_GROUP_ID);
		if(gid == null || gid.longValue() <= 0L) {
			throw new PictureBookException(409, "Book " + str(book.get(OlioFieldNames.FIELD_PB_SLUG)) + " has no group");
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_BOOK, book);
		q.field(FieldNames.FIELD_GROUP_ID, gid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(book));
		q.setRequest(compositionRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		if(recs == null || recs.length == 0) {
			return bySceneId;
		}
		for(BaseRecord c : PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_COMPOSITION)) {
			String purpose = str(c.get(OlioFieldNames.FIELD_PB_PURPOSE));
			if(!PbCompositionPurposeEnumType.SCENE.toString().equalsIgnoreCase(purpose)) {
				continue;
			}
			BaseRecord sceneRef = c.get(OlioFieldNames.FIELD_PB_SCENE);
			Long sid = (sceneRef != null ? sceneRef.get(FieldNames.FIELD_ID) : null);
			if(sid == null || sid.longValue() <= 0L) {
				continue;
			}
			bySceneId.putIfAbsent(sid, c);
		}
		return bySceneId;
	}

	/** The composition's actor rows ascending by {@code priority}. Uncached. */
	public static List<BaseRecord> listActors(BaseRecord user, BaseRecord composition) {
		return listRows(user, composition, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorRequest(), OlioFieldNames.FIELD_PB_PRIORITY);
	}

	/** The composition's element rows ascending by {@code depth} then id. Uncached. */
	public static List<BaseRecord> listElements(BaseRecord user, BaseRecord composition) {
		return listRows(user, composition, OlioModelNames.MODEL_PB_SCENE_ELEMENT, elementRequest(), FieldNames.FIELD_ID);
	}

	private static List<BaseRecord> listRows(BaseRecord user, BaseRecord composition, String model, String[] request, String sortField) {
		if(user == null || composition == null) {
			return Collections.emptyList();
		}
		Object gidObj = (composition.hasField(FieldNames.FIELD_GROUP_ID) ? composition.get(FieldNames.FIELD_GROUP_ID) : null);
		long gid = (gidObj instanceof Number ? ((Number) gidObj).longValue() : 0L);
		if(gid <= 0L) {
			/// Rows are group-scoped; an unconstrained list would read across the organization (189 d).
			throw new PictureBookException(409, "Composition " + str(composition.get(FieldNames.FIELD_OBJECT_ID)) + " has no group");
		}
		Query q = QueryUtil.createQuery(model, OlioFieldNames.FIELD_PB_COMPOSITION, composition);
		q.field(FieldNames.FIELD_GROUP_ID, gid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(composition));
		q.setRequest(request);
		q.setValue(FieldNames.FIELD_SORT_FIELD, sortField);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? PbGraphUtil.restoreSchema(Arrays.asList(recs), model) : Collections.emptyList());
	}

	/**
	 * True when the scene should be (re)composed by the LLM: no composition yet, or a {@code SCENE}-purpose
	 * {@code MIGRATED} one the user has not edited (§29). A {@code USER}/{@code LLM} composition, any user-edited
	 * one, or one whose purpose is not explicitly {@code SCENE} (an unset purpose reads {@code UNKNOWN}) is left alone.
	 */
	public static boolean needsCompose(BaseRecord composition) {
		if(composition == null) {
			return true;
		}
		String source = str(composition.get(OlioFieldNames.FIELD_PB_SOURCE));
		String purpose = str(composition.get(OlioFieldNames.FIELD_PB_PURPOSE));
		boolean edited = Boolean.TRUE.equals(composition.get(OlioFieldNames.FIELD_PB_USER_EDITED));
		// An unset purpose reads UNKNOWN on a persisted row (never null); only an explicit SCENE purpose is a scene composition.
		boolean scenePurpose = PbCompositionPurposeEnumType.SCENE.toString().equalsIgnoreCase(purpose);
		return PbCompositionSourceEnumType.MIGRATED.toString().equalsIgnoreCase(source) && !edited && scenePurpose;
	}

	// ─────────────────────────────── entry points ───────────────────────────────

	/**
	 * Compose {@code scene} with the chat config named {@code chatConfigName} (resolved as {@code user}; 404 when
	 * named but not found), or the organization's {@code generalChat} fallback when the name is null or blank.
	 */
	public static ComposeResult composeScene(BaseRecord user, BaseRecord book, BaseRecord scene, String chatConfigName, boolean force) {
		BaseRecord cfg = null;
		if(chatConfigName != null && !chatConfigName.isBlank()) {
			cfg = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName.trim(), null);
			if(cfg == null) {
				throw new PictureBookException(404, "Chat config not found: " + chatConfigName.trim());
			}
		}
		return composeScene(user, book, scene, cfg, force);
	}

	/**
	 * Compose {@code scene} as one illustration and persist it (design §9, §29).
	 * <p>
	 * Order of operations is the safety property: authorize, read, build the prompt, call and parse the LLM,
	 * and only then touch rows. An existing user-edited composition is returned untouched unless
	 * {@code force}. When the LLM is unavailable or its reply cannot be parsed, a scene with no composition
	 * gets a {@code MIGRATED} one from its own fields; a scene that already has one keeps it and the result
	 * reports {@code hardFailure}.
	 *
	 * @param chatConfig null for the organization's {@code generalChat} fallback
	 */
	public static ComposeResult composeScene(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord chatConfig, boolean force) {
		return composeScene(user, book, scene, chatConfig, force, null);
	}

	/**
	 * As {@link #composeScene(BaseRecord, BaseRecord, BaseRecord, BaseRecord, boolean)}, observing a job's
	 * cancel flag. The registry's cancel scope aborts the in-flight LLM stream, which comes back as an
	 * empty reply with the hard flag up - indistinguishable from "LLM unavailable" - so the flag is checked
	 * after each call and a cancelled compose returns {@link ComposeResult#cancelled} with nothing written
	 * instead of a MIGRATED fallback.
	 *
	 * @param progress null when not running under a job
	 */
	public static ComposeResult composeScene(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord chatConfig, boolean force, SummarizeProgress progress) {
		return composeScene(user, book, scene, chatConfig, force, progress, null);
	}

	/**
	 * As {@link #composeScene(BaseRecord, BaseRecord, BaseRecord, BaseRecord, boolean, SummarizeProgress)}, able to
	 * build wardrobe alternates for outfit changes the LLM reports (design §6.1): an actor whose reply row carries
	 * {@code outfit} gets an alternate created in the book world's Apparel group (or an existing one reused) and
	 * is pinned to it. That needs the world's {@link org.cote.accountmanager.olio.OlioContext}, opened from
	 * {@code dataPath} unless a session already holds it; without either, outfits are skipped with a warning and
	 * the compose otherwise proceeds. Pins of the composition being replaced are preserved per character.
	 *
	 * @param dataPath the deployment's {@code datagen.path}, or null
	 */
	public static ComposeResult composeScene(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord chatConfig, boolean force, SummarizeProgress progress, String dataPath) {
		long t0 = System.currentTimeMillis();
		ComposeResult result = new ComposeResult();
		Context ctx = openContext(user, book, scene, "compose scenes");
		ctx.chatConfig = chatConfig;
		ctx.dataPath = dataPath;
		BaseRecord existing = findSceneComposition(user, ctx.scene, ctx.bookGroupId, ctx.orgId);
		if(existing != null && !force && Boolean.TRUE.equals(existing.get(OlioFieldNames.FIELD_PB_USER_EDITED))) {
			return skipped(user, existing, result, t0);
		}
		if(isCancelled(progress)) {
			return cancelled(user, existing, result, ctx, t0);
		}
		ctx.pins = pinsOf(user, existing);
		Cast cast = collectCast(user, ctx, result);
		result.castTier = cast.tier;

		Map<String, String> vars = promptVariables(user, ctx, cast, result);
		boolean[] hard = new boolean[] { false };
		String reply = PictureBookUtil.callLlmForChapBook(user, chatConfig, PROMPT_COMPOSE_SCENE, vars, hard);
		if(isCancelled(progress)) {
			return cancelled(user, existing, result, ctx, t0);
		}
		Proposal proposal = null;
		if(reply != null && !reply.isBlank()) {
			proposal = parseStagedProposal(reply, "reply", ctx, result);
			if(proposal == null) {
				vars.put("items", vars.get("items") + RETRY_HINT);
				String retry = PictureBookUtil.callLlmForChapBook(user, chatConfig, PROMPT_COMPOSE_SCENE, vars, hard);
				if(isCancelled(progress)) {
					return cancelled(user, existing, result, ctx, t0);
				}
				if(retry != null && !retry.isBlank()) {
					proposal = parseStagedProposal(retry, "retry", ctx, result);
				}
			}
		}

		Staging staging;
		if(proposal != null) {
			staging = stage(user, ctx, cast, proposal, result);
		}
		else {
			String why = (hard[0] ? "LLM unavailable" : (reply == null || reply.isBlank() ? "empty LLM reply" : "unparseable LLM reply"));
			if(existing != null) {
				result.hardFailure = true;
				result.failureReason = why + "; existing composition left untouched";
				result.composition = existing;
				result.actors = listActors(user, existing);
				result.elements = listElements(user, existing);
				result.source = sourceOf(existing);
				result.elapsedMs = System.currentTimeMillis() - t0;
				logger.warn("compose-scene " + ctx.label() + ": " + result.failureReason);
				return result;
			}
			result.migratedFallback = true;
			result.failureReason = why + "; wrote a MIGRATED composition from the scene's own fields";
			result.warnings.add(result.failureReason);
			staging = migratedStaging(ctx.scene, cast.members);
		}
		persist(user, ctx, existing, staging, result);
		result.elapsedMs = System.currentTimeMillis() - t0;
		return result;
	}

	/**
	 * Write (or refresh) a {@code MIGRATED} composition from the scene's own fields and roster, without any
	 * LLM call. The P4-10 migration shape; {@link #needsCompose} flags it for a real compose later.
	 */
	public static ComposeResult composeMigrated(BaseRecord user, BaseRecord book, BaseRecord scene, boolean force) {
		long t0 = System.currentTimeMillis();
		ComposeResult result = new ComposeResult();
		Context ctx = openContext(user, book, scene, "compose scenes");
		BaseRecord existing = findSceneComposition(user, ctx.scene, ctx.bookGroupId, ctx.orgId);
		if(existing != null && !force && Boolean.TRUE.equals(existing.get(OlioFieldNames.FIELD_PB_USER_EDITED))) {
			return skipped(user, existing, result, t0);
		}
		Cast cast = collectCast(user, ctx, result);
		result.castTier = cast.tier;
		ctx.pins = pinsOf(user, existing);
		Staging staging = migratedStaging(ctx.scene, cast.members);
		preservePins(ctx, staging, result);
		persist(user, ctx, existing, staging, result);
		result.elapsedMs = System.currentTimeMillis() - t0;
		return result;
	}

	/// The pins of the composition about to be replaced, by character id. Rows are deleted and recreated on a
	/// recompose; a user's pin survives it (design §6.2) by being carried onto the new row for the same character.
	static Map<Long, BaseRecord> pinsOf(BaseRecord user, BaseRecord existing) {
		Map<Long, BaseRecord> pins = new HashMap<>();
		if(existing == null) {
			return pins;
		}
		for(BaseRecord row : listActors(user, existing)) {
			long apparelId = PbWardrobeUtil.pinnedApparelId(row);
			BaseRecord cref = (row.hasField(OlioFieldNames.FIELD_PB_CHARACTER) ? row.get(OlioFieldNames.FIELD_PB_CHARACTER) : null);
			long charId = (cref != null ? PbWardrobeUtil.idOf(cref) : 0L);
			if(apparelId > 0L && charId > 0L) {
				pins.putIfAbsent(charId, row.get(OlioFieldNames.FIELD_APPAREL));
			}
		}
		return pins;
	}

	private static void preservePins(Context ctx, Staging s, ComposeResult result) {
		if(ctx.pins == null || ctx.pins.isEmpty()) {
			return;
		}
		for(ResolvedActor ra : s.actors) {
			if(ra.character == null || ra.apparel != null) {
				continue;
			}
			BaseRecord pin = ctx.pins.get(PbWardrobeUtil.idOf(ra.character));
			if(pin != null) {
				ra.apparel = pin;
				result.pinsPreserved++;
			}
		}
	}

	private static ComposeResult skipped(BaseRecord user, BaseRecord existing, ComposeResult result, long t0) {
		result.skippedUserEdited = true;
		result.composition = existing;
		result.actors = listActors(user, existing);
		result.elements = listElements(user, existing);
		result.source = sourceOf(existing);
		result.elapsedMs = System.currentTimeMillis() - t0;
		return result;
	}

	private static boolean isCancelled(SummarizeProgress progress) {
		return progress != null && progress.isCancelled();
	}

	/// Nothing written; whatever composition existed is reported back unchanged.
	private static ComposeResult cancelled(BaseRecord user, BaseRecord existing, ComposeResult result, Context ctx, long t0) {
		result.cancelled = true;
		result.failureReason = "cancelled; nothing written";
		if(existing != null) {
			result.composition = existing;
			result.actors = listActors(user, existing);
			result.elements = listElements(user, existing);
			result.source = sourceOf(existing);
		}
		result.elapsedMs = System.currentTimeMillis() - t0;
		logger.info("compose-scene " + ctx.label() + ": cancelled; nothing written");
		return result;
	}

	/**
	 * The lazy per-scene fallback (design §9.1): a render that finds no usable composition composes the
	 * scene itself, out of story order, with the organization's default chat config. Runs only on an
	 * authorized write path ({@link #composeScene} re-authorizes through {@link #openContext}); never
	 * forces over a user-edited row; on cancel returns with nothing written. The caller marks the plan
	 * {@code COMPOSED_OUT_OF_ORDER} when {@link EnsureResult#composedNow}.
	 */
	public static EnsureResult ensureComposition(BaseRecord user, BaseRecord book, BaseRecord scene, SummarizeProgress cancelToken) {
		EnsureResult out = new EnsureResult();
		Context ctx = openContext(user, book, scene, "compose scenes");
		BaseRecord existing = findSceneComposition(user, ctx.scene, ctx.bookGroupId, ctx.orgId);
		if(existing != null && !needsCompose(existing)) {
			out.composition = existing;
			return out;
		}
		ComposeResult result = composeScene(user, ctx.book, ctx.scene, (BaseRecord) null, false, cancelToken);
		out.result = result;
		out.composedNow = result.isWritten();
		out.composition = (result.cancelled ? existing : result.composition);
		return out;
	}

	// ─────────────────────────────── context ───────────────────────────────

	/** The authorized, re-read inputs of one compose. */
	static final class Context {
		BaseRecord book;
		BaseRecord scene;
		BookContext bctx;
		long orgId;
		long bookGroupId;
		String slug;
		int sceneIndex;
		/** The location's store items, read once per compose (prompt block and element matching both use them). */
		List<BaseRecord> items;
		/** The chat config of this compose; the wardrobe step's outfit guess uses the same one. */
		BaseRecord chatConfig;
		/** The deployment's {@code datagen.path}, when the caller had one; opens the world for alternates. */
		String dataPath;
		/** Pins of the composition being replaced, by character id (see {@link PbComposeUtil#pinsOf}). */
		Map<Long, BaseRecord> pins = new HashMap<>();
		/** The world's Olio context, resolved lazily on the first outfit; {@code octxTried} stops repeated failures. */
		OlioContext octx;
		boolean octxTried;
		String octxFailure;

		String label() {
			return slug + ":scene" + sceneIndex;
		}
	}

	/// Package-private so tests can stage a hand-built proposal against a real book without an LLM.
	static Context openContext(BaseRecord user, BaseRecord book, BaseRecord scene, String what) {
		if(scene == null) {
			throw new PictureBookException(400, "No scene");
		}
		Context ctx = new Context();
		ctx.book = PbBookUtil.requireBookUpdate(user, book, what);
		ctx.bctx = PbLocationUtil.requireBookContext(user, ctx.book);
		ctx.orgId = PbGraphUtil.orgId(ctx.book);
		ctx.slug = str(ctx.book.get(OlioFieldNames.FIELD_PB_SLUG));
		String sceneOid = str(scene.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord fresh = PbBookUtil.readScene(user, sceneOid, ctx.orgId);
		if(fresh == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneOid);
		}
		BaseRecord sceneBook = fresh.get(OlioFieldNames.FIELD_PB_BOOK);
		Long sceneBookId = (sceneBook != null ? sceneBook.get(FieldNames.FIELD_ID) : null);
		Long bookId = ctx.book.get(FieldNames.FIELD_ID);
		if(sceneBookId == null || bookId == null || sceneBookId.longValue() != bookId.longValue()) {
			throw new PictureBookException(400, "Scene " + sceneOid + " does not belong to book " + ctx.slug);
		}
		// Compositions are keyed on the BOOK's group, not the scene's: the planner, listSceneCompositions and the
		// page path all look there, and a chapter-group scene would otherwise write where nothing reads.
		Long gid = ctx.book.get(FieldNames.FIELD_GROUP_ID);
		if(gid == null) {
			throw new PictureBookException(409, "Book " + ctx.slug + " has no group");
		}
		ctx.scene = fresh;
		ctx.bookGroupId = gid.longValue();
		Integer idx = fresh.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		ctx.sceneIndex = (idx != null ? idx.intValue() : 0);
		return ctx;
	}

	// ─────────────────────────────── cast ───────────────────────────────

	/** The characters offered to the prompt, in order, and where they came from. */
	static final class Cast {
		List<BaseRecord> members = new ArrayList<>();
		CastTier tier = CastTier.NONE;
	}

	private static final String[] CHAR_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_FIRST_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, OlioFieldNames.FIELD_NARRATIVE, FieldNames.FIELD_ATTRIBUTES
	};

	/// Tier (a): the scene's own roster. Tier (b): population members whose pbSceneRefs name this scene.
	/// Tier (c): the whole population, capped. Reads run as the caller; any read error empties the tier
	/// with a warning rather than aborting - a compose with a thin cast is still a compose.
	static Cast collectCast(BaseRecord user, Context ctx, ComposeResult result) {
		Cast cast = new Cast();
		List<BaseRecord> members = sceneMembers(user, ctx, result);
		if(!members.isEmpty()) {
			cast.members = members;
			cast.tier = CastTier.SCENE_MEMBERS;
			return cast;
		}
		List<BaseRecord> population = populationMembers(user, ctx, result);
		if(population.isEmpty()) {
			return cast;
		}
		List<BaseRecord> refs = new ArrayList<>();
		for(BaseRecord cp : population) {
			String csv = null;
			try {
				csv = AttributeUtil.getAttributeValue(cp, PictureBookUtil.ATTR_SCENE_REFS, (String) null);
			}
			catch(Exception e) {
				logger.warn("Could not read " + PictureBookUtil.ATTR_SCENE_REFS + " of " + cp.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			}
			if(csv != null && PictureBookUtil.parseSceneRefs(csv).contains(Integer.valueOf(ctx.sceneIndex))) {
				refs.add(cp);
			}
		}
		if(!refs.isEmpty()) {
			cast.members = refs;
			cast.tier = CastTier.SCENE_REFS;
			return cast;
		}
		if(population.size() > MAX_POPULATION_CAST) {
			result.warnings.add("Scene has no roster; offering the first " + MAX_POPULATION_CAST + " of " + population.size() + " population members");
			logger.warn("compose-scene " + ctx.label() + ": population cast capped at " + MAX_POPULATION_CAST + " of " + population.size());
			population = new ArrayList<>(population.subList(0, MAX_POPULATION_CAST));
		}
		cast.members = population;
		cast.tier = CastTier.POPULATION;
		return cast;
	}

	/// Mirrors PbRenderPlanUtil.sceneActors: participations of scene.characters, then one IN query as the caller.
	private static List<BaseRecord> sceneMembers(BaseRecord user, Context ctx, ComposeResult result) {
		try {
			List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil().findMembers(ctx.scene,
				OlioFieldNames.FIELD_PB_CHARACTERS, OlioModelNames.MODEL_CHAR_PERSON, 0L);
			if(parts == null || parts.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> ids = new ArrayList<>();
			for(BaseRecord part : parts) {
				Object pid = part.get(FieldNames.FIELD_PARTICIPANT_ID);
				if(pid instanceof Number) {
					ids.add(Long.toString(((Number) pid).longValue()));
				}
			}
			if(ids.isEmpty()) {
				return Collections.emptyList();
			}
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
			q.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, String.join(",", ids));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, ctx.orgId);
			q.setRequest(CHAR_REQUEST);
			q.setCache(false);
			BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
			return (recs != null ? PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_CHAR_PERSON) : Collections.emptyList());
		}
		catch(Exception e) {
			result.warnings.add("Could not read the scene roster: " + e.getMessage());
			logger.warn("compose-scene " + ctx.label() + ": roster read failed: " + e.getMessage());
			return Collections.emptyList();
		}
	}

	/// The population of every character group the book resolves names in, shadow first (same order as
	/// PictureBookUtil.findSceneCharacterGroups), de-duplicated by id, at most MAX_CAST_LIST per group.
	private static List<BaseRecord> populationMembers(BaseRecord user, Context ctx, ComposeResult result) {
		LinkedHashMap<Long, BaseRecord> byId = new LinkedHashMap<>();
		for(BaseRecord grp : characterGroups(user, ctx)) {
			try {
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, (long) grp.get(FieldNames.FIELD_ID));
				q.field(FieldNames.FIELD_ORGANIZATION_ID, ctx.orgId);
				q.setRequest(CHAR_REQUEST);
				q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
				q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
				q.setRequestRange(0L, MAX_CAST_LIST);
				q.setCache(false);
				BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
				if(recs == null) {
					continue;
				}
				for(BaseRecord cp : PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_CHAR_PERSON)) {
					Long id = cp.get(FieldNames.FIELD_ID);
					if(id != null && !byId.containsKey(id)) {
						byId.put(id, cp);
					}
				}
			}
			catch(Exception e) {
				result.warnings.add("Could not list population group " + grp.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
				logger.warn("compose-scene " + ctx.label() + ": population read failed: " + e.getMessage());
			}
		}
		return new ArrayList<>(byId.values());
	}

	/// The chapter's shadow character group first (only when the book is a chapter of a series), then the
	/// world's population group. Same precedence as PictureBookUtil.findSceneCharacterGroups.
	private static List<BaseRecord> characterGroups(BaseRecord user, Context ctx) {
		List<BaseRecord> groups = new ArrayList<>();
		BaseRecord series = (ctx.book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? ctx.book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
		BaseRecord world = (ctx.book.hasField(OlioFieldNames.FIELD_PB_WORLD) ? ctx.book.get(OlioFieldNames.FIELD_PB_WORLD) : null);
		if(series != null && world != null && ctx.slug != null) {
			String popPath = ctx.bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			if(popPath != null) {
				String shadowPath = PbBookUtil.chapterShadowCharGroupPath(popPath, ctx.slug);
				BaseRecord shadow = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, shadowPath,
					GroupEnumType.DATA.toString(), ctx.orgId);
				if(shadow != null) {
					groups.add(shadow);
				}
			}
		}
		BaseRecord pop = ctx.bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
		if(pop != null) {
			groups.add(pop);
		}
		return groups;
	}

	// ─────────────────────────────── prompt variables ───────────────────────────────

	/// All ten placeholders of the prompt, every one non-null (callLlmInternal skips null values, which
	/// would leave the placeholder in the text and hard-fail the call). Free text is sanitized.
	private static Map<String, String> promptVariables(BaseRecord user, Context ctx, Cast cast, ComposeResult result) {
		Map<String, String> vars = new HashMap<>();
		BaseRecord scene = ctx.scene;
		vars.put("style", styleName(user, ctx, result));
		vars.put("sceneIndex", Integer.toString(ctx.sceneIndex));
		vars.put("title", orNone(PbLocationUtil.sanitizeForPrompt(str(scene.get(OlioFieldNames.FIELD_PB_TITLE)), MAX_FIELD_CHARS)));
		vars.put("setting", orNone(PbLocationUtil.sanitizeForPrompt(str(scene.get(OlioFieldNames.FIELD_PB_SETTING)), MAX_FIELD_CHARS)));
		vars.put("action", orNone(PbLocationUtil.sanitizeForPrompt(str(scene.get(OlioFieldNames.FIELD_PB_ACTION)), MAX_FIELD_CHARS)));
		vars.put("mood", orNone(PbLocationUtil.sanitizeForPrompt(str(scene.get(OlioFieldNames.FIELD_PB_MOOD)), MAX_MOOD_CHARS)));
		vars.put("sceneText", sceneText(ctx, result));
		vars.put("cast", castBlock(cast));
		vars.put("locations", locationsBlock(user, ctx, result));
		vars.put("items", itemsBlock(user, ctx, result));
		return vars;
	}

	private static String styleName(BaseRecord user, Context ctx, ComposeResult result) {
		BaseRecord styleRef = (ctx.book.hasField(OlioFieldNames.FIELD_STYLE) ? ctx.book.get(OlioFieldNames.FIELD_STYLE) : null);
		if(styleRef == null) {
			return NONE;
		}
		try {
			BaseRecord style = PbStyleUtil.readStyle(user, styleRef, ctx.orgId);
			String name = (style != null ? str(style.get(FieldNames.FIELD_NAME)) : null);
			return orNone(PbLocationUtil.sanitizeForPrompt(name, MAX_FIELD_CHARS));
		}
		catch(Exception e) {
			result.warnings.add("Could not read the book style: " + e.getMessage());
			return NONE;
		}
	}

	/// First meaningful of description / summary / blurb, capped with a warning.
	private static String sceneText(Context ctx, ComposeResult result) {
		String text = null;
		for(String f : new String[] { FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_SUMMARY, OlioFieldNames.FIELD_PB_BLURB }) {
			String v = str(ctx.scene.get(f));
			if(NarrativeUtil.isMeaningful(v)) {
				text = v.trim();
				break;
			}
		}
		if(text == null) {
			result.warnings.add("Scene has no description, summary or blurb; composing from setting/action/mood only");
			return NONE;
		}
		if(text.length() > MAX_SCENE_TEXT_CHARS) {
			result.warnings.add("Scene text truncated from " + text.length() + " to " + MAX_SCENE_TEXT_CHARS + " characters for the prompt");
		}
		return orNone(PbLocationUtil.sanitizeForPrompt(text, MAX_SCENE_TEXT_CHARS));
	}

	private static String castBlock(Cast cast) {
		if(cast.members.isEmpty()) {
			return NONE;
		}
		StringBuilder sb = new StringBuilder();
		for(BaseRecord cp : cast.members) {
			String name = PbLocationUtil.sanitizeForPrompt(str(cp.get(FieldNames.FIELD_NAME)), MAX_FIELD_CHARS);
			if(!NarrativeUtil.isMeaningful(name)) {
				continue;
			}
			String desc = PbLocationUtil.sanitizeForPrompt(PbRenderPlanUtil.actorDescription(cp), MAX_CAST_DESCRIPTION_CHARS);
			sb.append("- ").append(name.trim());
			if(NarrativeUtil.isMeaningful(desc)) {
				sb.append(": ").append(desc.trim());
			}
			sb.append('\n');
		}
		return (sb.length() == 0 ? NONE : sb.toString().trim());
	}

	private static String locationsBlock(BaseRecord user, Context ctx, ComposeResult result) {
		List<BaseRecord> locs;
		try {
			locs = PbLocationUtil.listLocations(user, ctx.bctx);
		}
		catch(Exception e) {
			result.warnings.add("Could not list the book's locations: " + e.getMessage());
			return NONE;
		}
		if(locs == null || locs.isEmpty()) {
			return NONE;
		}
		StringBuilder sb = new StringBuilder();
		for(BaseRecord loc : locs) {
			String name = PbLocationUtil.sanitizeForPrompt(str(loc.get(FieldNames.FIELD_NAME)), MAX_FIELD_CHARS);
			if(!NarrativeUtil.isMeaningful(name)) {
				continue;
			}
			sb.append("- ").append(name.trim());
			List<String> aliases = (loc.hasField(OlioFieldNames.FIELD_PB_ALIASES) ? loc.get(OlioFieldNames.FIELD_PB_ALIASES) : null);
			if(aliases != null && !aliases.isEmpty()) {
				List<String> clean = new ArrayList<>();
				for(String a : aliases) {
					String s = PbLocationUtil.sanitizeForPrompt(a, MAX_FIELD_CHARS);
					if(NarrativeUtil.isMeaningful(s)) {
						clean.add(s.trim());
					}
				}
				if(!clean.isEmpty()) {
					sb.append(" (aliases: ").append(String.join(", ", clean)).append(')');
				}
			}
			sb.append('\n');
		}
		return (sb.length() == 0 ? NONE : sb.toString().trim());
	}

	private static String itemsBlock(BaseRecord user, Context ctx, ComposeResult result) {
		List<BaseRecord> items = locationItems(user, ctx, result);
		if(items.isEmpty()) {
			return NONE;
		}
		StringBuilder sb = new StringBuilder();
		for(BaseRecord item : items) {
			String name = PbLocationUtil.sanitizeForPrompt(str(item.get(FieldNames.FIELD_NAME)), MAX_FIELD_CHARS);
			if(!NarrativeUtil.isMeaningful(name)) {
				continue;
			}
			sb.append("- ").append(name.trim()).append('\n');
		}
		return (sb.length() == 0 ? NONE : sb.toString().trim());
	}

	/// scene.location -> olio.pb.location -> poi -> store -> items participations -> olio.item rows, all read as
	/// the caller. Any miss or read failure yields an empty list with a warning; the prompt then says "(none)".
	private static List<BaseRecord> locationItems(BaseRecord user, Context ctx, ComposeResult result) {
		if(ctx.items == null) {
			ctx.items = readLocationItems(user, ctx, result);
		}
		return ctx.items;
	}

	private static List<BaseRecord> readLocationItems(BaseRecord user, Context ctx, ComposeResult result) {
		BaseRecord locRef = (ctx.scene.hasField(FieldNames.FIELD_LOCATION) ? ctx.scene.get(FieldNames.FIELD_LOCATION) : null);
		if(locRef == null) {
			return Collections.emptyList();
		}
		try {
			BaseRecord store = poiStoreOf(user, locRef, ctx.orgId);
			if(store == null) {
				return Collections.emptyList();
			}
			return storeItems(user, store, ctx.orgId);
		}
		catch(Exception e) {
			result.warnings.add("Could not read the location's items: " + e.getMessage());
			logger.warn("compose-scene " + ctx.label() + ": item read failed: " + e.getMessage());
			return Collections.emptyList();
		}
	}

	/// {@code olio.pb.location} ref -> its POI (read as the caller, cache off) -> the POI's {@code store} ref.
	/// Null when the location, its POI or the POI's store is absent.
	static BaseRecord poiStoreOf(BaseRecord user, BaseRecord locRef, long orgId) {
		if(locRef == null) {
			return null;
		}
		return poiStoreOfLocation(user, PbLocationUtil.readLocation(user, str(locRef.get(FieldNames.FIELD_OBJECT_ID)), orgId), orgId);
	}

	/// Same as {@link #poiStoreOf} for a location already read with {@link PbBookUtil#locationRequest()}.
	static BaseRecord poiStoreOfLocation(BaseRecord user, BaseRecord loc, long orgId) {
		BaseRecord poiRef = (loc != null && loc.hasField(OlioFieldNames.FIELD_PB_POI) ? loc.get(OlioFieldNames.FIELD_PB_POI) : null);
		if(poiRef == null) {
			return null;
		}
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_ID, (long) poiRef.get(FieldNames.FIELD_ID));
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_STORE });
		pq.setCache(false);
		BaseRecord poi = IOSystem.getActiveContext().getAccessPoint().find(user, pq);
		return (poi != null && poi.hasField(FieldNames.FIELD_STORE) ? poi.get(FieldNames.FIELD_STORE) : null);
	}

	/// The store's {@code items} participations resolved to {@code olio.item} rows (name order), read as the caller.
	/// {@code AccessPoint.list} filters nothing per record: the id set is bounded by a store the caller already read through PBAC.
	static List<BaseRecord> storeItems(BaseRecord user, BaseRecord store, long orgId) throws IndexException, ReaderException {
		List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil().findMembers(store, OlioFieldNames.FIELD_ITEMS,
			OlioModelNames.MODEL_ITEM, 0L);
		if(parts == null || parts.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> ids = new ArrayList<>();
		for(BaseRecord part : parts) {
			Object pid = part.get(FieldNames.FIELD_PARTICIPANT_ID);
			if(pid instanceof Number) {
				ids.add(Long.toString(((Number) pid).longValue()));
			}
		}
		if(ids.isEmpty()) {
			return Collections.emptyList();
		}
		Query iq = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM);
		iq.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, String.join(",", ids));
		iq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		iq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_DESCRIPTION });
		iq.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		iq.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		iq.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, iq).getResults();
		return (recs != null ? PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_ITEM) : Collections.emptyList());
	}

	// ─────────────────────────────── parse ───────────────────────────────

	/**
	 * A parseable object that stages no actor and no element is treated exactly like an unparseable one (the
	 * usual cause is a stray brace that dropped the {@code actors}/{@code elements} wrappers), so the caller's
	 * single {@link #RETRY_HINT} retry covers both.
	 */
	private static Proposal parseStagedProposal(String reply, String which, Context ctx, ComposeResult result) {
		Proposal p = parseProposal(reply, ctx, result);
		if(p != null && p.isEmpty()) {
			String w = "The " + which + " staged no actors and no elements; treated as malformed";
			result.warnings.add(w);
			logger.warn("compose-scene " + ctx.label() + ": " + w);
			return null;
		}
		return p;
	}

	private static Proposal parseProposal(String reply, Context ctx, ComposeResult result) {
		boolean[] ok = new boolean[] { false };
		Map<String, Object> map = PictureBookUtil.parseLlmJsonObject(reply, "compose-scene:" + ctx.label(), result.warnings, ok);
		if(!ok[0] || map == null) {
			return null;
		}
		Proposal p = applyReply(map);
		if(p.droppedActors > 0 || p.droppedElements > 0) {
			String w = "The reply carried more rows than allowed; dropped " + p.droppedActors + " actor(s) beyond " + MAX_ACTORS
				+ " and " + p.droppedElements + " element(s) beyond " + MAX_ELEMENTS;
			result.warnings.add(w);
			logger.warn("compose-scene " + ctx.label() + ": " + w);
		}
		return p;
	}

	/**
	 * Pure: lift the parsed reply into a {@link Proposal}. Keys are matched case-insensitively, every string is
	 * passed through {@link NarrativeUtil#isMeaningful} (so the literal {@code "null"} / {@code "none"} become
	 * null) and capped, and nothing is resolved or validated against enums yet. Package-private for tests.
	 */
	static Proposal applyReply(Map<String, Object> map) {
		Proposal p = new Proposal();
		if(map == null) {
			return p;
		}
		Map<String, Object> m = lower(map);
		Object loc = m.get("location");
		if(loc instanceof Map) {
			Map<String, Object> lm = lower(castMap(loc));
			p.locationName = text(lm.get("name"), MAX_FIELD_CHARS);
			p.sameAs = text(lm.get("sameas"), MAX_FIELD_CHARS);
			p.variant = text(lm.get("variant"), MAX_FIELD_CHARS);
		}
		else if(loc != null) {
			p.locationName = text(loc, MAX_FIELD_CHARS);
		}
		Object cam = m.get("camera");
		if(cam instanceof Map) {
			Map<String, Object> cm = lower(castMap(cam));
			p.shotWord = text(cm.get("shot"), MAX_FIELD_CHARS);
			p.viewpointWord = text(cm.get("viewpoint"), MAX_FIELD_CHARS);
			p.focalName = text(cm.get("focal"), MAX_FIELD_CHARS);
		}
		else {
			p.shotWord = text(m.get("shot"), MAX_FIELD_CHARS);
			p.viewpointWord = text(m.get("viewpoint"), MAX_FIELD_CHARS);
			p.focalName = text(m.get("focal"), MAX_FIELD_CHARS);
		}
		Object actors = m.get("actors");
		if(actors instanceof List) {
			for(Object o : (List<?>) actors) {
				if(!(o instanceof Map)) {
					continue;
				}
				Map<String, Object> am = lower(castMap(o));
				ActorSpec a = new ActorSpec();
				a.name = text(am.get("character"), MAX_FIELD_CHARS);
				if(a.name == null) {
					a.name = text(am.get("name"), MAX_FIELD_CHARS);
				}
				a.roleWord = text(am.get("role"), MAX_FIELD_CHARS);
				a.pose = text(am.get("pose"), MAX_FIELD_CHARS);
				a.expression = text(am.get("expression"), MAX_EXPRESSION_CHARS);
				a.action = text(am.get("action"), MAX_FIELD_CHARS);
				a.placement = text(am.get("placement"), MAX_FIELD_CHARS);
				a.description = text(am.get("description"), MAX_FIELD_CHARS);
				a.count = integer(am.get("count"));
				a.outfit = text(am.get("outfit"), MAX_FIELD_CHARS);
				if(a.name == null && a.description == null && a.pose == null && a.action == null) {
					continue;
				}
				if(p.actors.size() >= MAX_ACTORS) {
					p.droppedActors++;
					continue;
				}
				p.actors.add(a);
			}
		}
		Object elements = m.get("elements");
		if(elements instanceof List) {
			for(Object o : (List<?>) elements) {
				if(!(o instanceof Map)) {
					continue;
				}
				Map<String, Object> em = lower(castMap(o));
				ElementSpec e = new ElementSpec();
				e.typeWord = text(em.get("type"), MAX_FIELD_CHARS);
				e.description = text(em.get("description"), MAX_FIELD_CHARS);
				if(e.description == null) {
					e.description = text(em.get("name"), MAX_FIELD_CHARS);
				}
				e.placement = text(em.get("placement"), MAX_FIELD_CHARS);
				if(e.description == null) {
					continue;
				}
				if(p.elements.size() >= MAX_ELEMENTS) {
					p.droppedElements++;
					continue;
				}
				p.elements.add(e);
			}
		}
		p.mood = text(m.get("mood"), MAX_MOOD_CHARS);
		return p;
	}

	/** Tolerant role words → enum; null when the word is unknown so the caller can apply its own rule. */
	static PbActorRoleEnumType roleFor(String word) {
		String w = norm(word);
		if(w == null) {
			return null;
		}
		switch(w) {
			case "hero": case "protagonist": case "main": case "lead": case "primary":
				return PbActorRoleEnumType.HERO;
			case "supporting": case "secondary": case "companion": case "support":
				return PbActorRoleEnumType.SUPPORTING;
			case "named_background": case "named background": case "background": case "named": case "namedbackground":
				return PbActorRoleEnumType.NAMED_BACKGROUND;
			case "extra": case "bystander": case "minor": case "extras":
				return PbActorRoleEnumType.EXTRA;
			case "crowd": case "group": case "mob": case "patrons": case "onlookers": case "crowds":
				return PbActorRoleEnumType.CROWD;
			default:
				return null;
		}
	}

	/** Tolerant element-type words → enum; {@code OTHER} for anything unknown. */
	static PbElementTypeEnumType elementTypeFor(String word) {
		String w = norm(word);
		if(w == null) {
			return PbElementTypeEnumType.OTHER;
		}
		switch(w) {
			case "prop": case "object": case "tool": case "weapon": case "props": case "item":
				return PbElementTypeEnumType.PROP;
			case "furniture": case "furnishing": case "table": case "chair": case "bed":
				return PbElementTypeEnumType.FURNITURE;
			case "animal": case "creature": case "beast": case "pet": case "horse": case "dog": case "animals":
				return PbElementTypeEnumType.ANIMAL;
			case "vehicle": case "cart": case "wagon": case "ship": case "boat": case "carriage":
				return PbElementTypeEnumType.VEHICLE;
			case "structure": case "building": case "wall": case "door": case "bridge": case "architecture":
				return PbElementTypeEnumType.STRUCTURE;
			case "nature": case "tree": case "plant": case "rock": case "landscape": case "weather": case "sky": case "natural":
				return PbElementTypeEnumType.NATURE;
			default:
				return PbElementTypeEnumType.OTHER;
		}
	}

	private static String norm(String word) {
		if(!NarrativeUtil.isMeaningful(word)) {
			return null;
		}
		return word.trim().toLowerCase(Locale.ROOT).replace('-', '_').replaceAll("\\s+", " ");
	}

	private static Map<String, Object> lower(Map<String, Object> in) {
		Map<String, Object> out = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : in.entrySet()) {
			if(e.getKey() != null) {
				out.putIfAbsent(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object o) {
		return (Map<String, Object>) o;
	}

	/// A meaningful, trimmed, capped string from any scalar; null for maps/lists, blanks and "null"-words.
	private static String text(Object v, int max) {
		if(v == null || v instanceof Map || v instanceof List) {
			return null;
		}
		String s = v.toString();
		if(!NarrativeUtil.isMeaningful(s)) {
			return null;
		}
		s = s.trim();
		return (s.length() > max ? s.substring(0, max).trim() : s);
	}

	private static Integer integer(Object v) {
		if(v instanceof Number) {
			int n = ((Number) v).intValue();
			return (n > 0 ? Integer.valueOf(n) : null);
		}
		String s = text(v, MAX_FIELD_CHARS);
		if(s == null) {
			return null;
		}
		// Only the first run of digits counts: "3 or 4" is 3, not 34; the whole phrase is scanned, so
		// "a dozen or so, 12 figures" is 12, not a truncated 1.
		Matcher m = FIRST_INTEGER.matcher(s);
		if(!m.find()) {
			return null;
		}
		try {
			int n = Integer.parseInt(m.group());
			return (n > 0 ? Integer.valueOf(n) : null);
		}
		catch(NumberFormatException e) {
			return null;
		}
	}

	// ─────────────────────────────── stage ───────────────────────────────

	/// Resolve names, apply the role rules (§9.1), pick the camera and lay the figures out. Nothing here
	/// throws on content: an unknown name becomes a NAMED_BACKGROUND row, an unknown placement word a
	/// default slot, an unknown role a rule-derived one.
	static Staging stage(BaseRecord user, Context ctx, Cast cast, Proposal p, ComposeResult result) {
		Staging s = new Staging();
		s.source = PbCompositionSourceEnumType.LLM;
		List<BaseRecord> groups = characterGroups(user, ctx);
		boolean heroSeen = false;
		for(ActorSpec a : p.actors) {
			ResolvedActor ra = new ResolvedActor();
			ra.pose = a.pose;
			ra.expression = a.expression;
			ra.action = a.action;
			ra.description = a.description;
			ra.placementWord = a.placement;
			ra.count = (a.count != null && a.count.intValue() > 1 ? a.count.intValue() : 1);
			PbActorRoleEnumType word = roleFor(a.roleWord);
			if(a.name != null) {
				ra.character = resolveCharacter(user, a.name, cast, groups);
				if(ra.character != null) {
					ra.displayName = str(ra.character.get(FieldNames.FIELD_NAME));
					ra.outfit = a.outfit;
					if(word != null && word != PbActorRoleEnumType.CROWD) {
						ra.role = word;
					}
					else {
						ra.role = (heroSeen ? PbActorRoleEnumType.SUPPORTING : PbActorRoleEnumType.HERO);
					}
					if(ra.role == PbActorRoleEnumType.HERO) {
						heroSeen = true;
					}
					ra.count = 1;
				}
				else {
					ra.displayName = a.name;
					ra.role = PbActorRoleEnumType.NAMED_BACKGROUND;
					String detail = firstMeaningful(a.description, a.pose, a.action);
					ra.description = (detail != null ? a.name + " - " + detail : a.name);
					ra.count = 1;
					if(!result.unresolvedNames.contains(a.name)) {
						result.unresolvedNames.add(a.name);
					}
				}
			}
			else {
				if(word == PbActorRoleEnumType.NAMED_BACKGROUND || word == PbActorRoleEnumType.EXTRA || word == PbActorRoleEnumType.CROWD) {
					ra.role = word;
				}
				else {
					ra.role = (ra.count > 1 ? PbActorRoleEnumType.CROWD : PbActorRoleEnumType.EXTRA);
				}
				ra.displayName = (a.description != null ? a.description : ra.role.toString().toLowerCase(Locale.ROOT));
				if(ra.role != PbActorRoleEnumType.CROWD) {
					ra.count = 1;
				}
			}
			s.actors.add(ra);
		}
		preservePins(ctx, s, result);
		resolveOutfits(user, ctx, s, result);
		int n = s.actors.size();
		s.shot = PbLayoutUtil.resolveShotType(PbLayoutUtil.parseShotType(p.shotWord), n);
		s.viewpoint = PbLayoutUtil.resolveViewpoint(PbLayoutUtil.parseViewpoint(p.viewpointWord));
		s.cameraText = cameraText(p);
		for(int i = 0; i < n; i++) {
			ResolvedActor ra = s.actors.get(i);
			layoutActor(ra, i, n, s.shot, result);
		}
		s.focalIndex = focalIndex(s.actors, p.focalName);

		List<BaseRecord> items = locationItems(user, ctx, result);
		int en = p.elements.size();
		for(int i = 0; i < en; i++) {
			ElementSpec e = p.elements.get(i);
			ResolvedElement re = new ResolvedElement();
			re.type = elementTypeFor(e.typeWord);
			re.description = e.description;
			re.placementWord = e.placement;
			re.item = matchItem(e.description, items);
			if(e.placement != null) {
				Placement pl = PbLayoutUtil.place(e.placement, s.shot);
				if(pl.recognized()) {
					re.placement = pl;
				}
				else {
					noteUnrecognized(e.placement, result);
				}
			}
			if(re.placement == null) {
				re.placement = PbLayoutUtil.defaultPlacement(i, en, s.shot, null);
			}
			s.elements.add(re);
		}

		String sceneMood = str(ctx.scene.get(OlioFieldNames.FIELD_PB_MOOD));
		s.mood = (NarrativeUtil.isMeaningful(sceneMood) ? sceneMood.trim() : p.mood);
		String sceneSetting = str(ctx.scene.get(OlioFieldNames.FIELD_PB_SETTING));
		if(NarrativeUtil.isMeaningful(sceneSetting)) {
			s.settingText = sceneSetting.trim();
		}
		else if(p.locationName != null) {
			s.settingText = (p.variant != null ? p.locationName + ", " + p.variant : p.locationName);
		}
		result.locationHint = locationHint(p);
		return s;
	}

	/**
	 * The wardrobe step of a compose (design §6.1): for each cast actor whose reply row carried {@code outfit}
	 * and that holds no pin, build (or reuse) an alternate in the character's wardrobe and pin the row to it.
	 * A pinned actor keeps its pin - the user's choice outranks the text. Every failure here is a warning and a
	 * skipped outfit, never a failed compose: the composition is worth writing without its outfits.
	 */
	private static void resolveOutfits(BaseRecord user, Context ctx, Staging s, ComposeResult result) {
		for(ResolvedActor ra : s.actors) {
			if(ra.character == null || ra.apparel != null || !NarrativeUtil.isMeaningful(ra.outfit)) {
				continue;
			}
			OlioContext octx = olioContextFor(user, ctx);
			if(octx == null) {
				result.outfitsSkipped++;
				result.warnings.add("Outfit \"" + ra.outfit + "\" for " + ra.displayName + " skipped: " + ctx.octxFailure);
				continue;
			}
			try {
				BaseRecord cp = PbWardrobeUtil.readCharacterById(user, PbWardrobeUtil.idOf(ra.character), ctx.orgId);
				BaseRecord store = (cp != null ? PbWardrobeUtil.storeOf(user, cp) : null);
				if(store == null) {
					throw new PictureBookException(400, "character has no store");
				}
				PbWardrobeUtil.CreateResult cr = PbWardrobeUtil.createAlternateUnchecked(user, ctx.chatConfig, octx, cp, store, ra.outfit);
				ra.apparel = cr.apparel;
				if(cr.reused) {
					result.outfitsReused++;
				}
				else {
					result.outfitsCreated++;
				}
			}
			catch(RuntimeException e) {
				result.outfitsSkipped++;
				result.warnings.add("Outfit \"" + ra.outfit + "\" for " + ra.displayName + " skipped: " + PbServiceFacade.curatedMessage(e));
				logger.warn("compose-scene " + ctx.label() + ": outfit for " + ra.displayName + " skipped", e);
			}
		}
	}

	/// Opened once per compose, on the first outfit; a failure is remembered so later actors do not retry it.
	private static OlioContext olioContextFor(BaseRecord user, Context ctx) {
		if(ctx.octxTried) {
			return ctx.octx;
		}
		ctx.octxTried = true;
		try {
			ctx.octx = PbWardrobeUtil.resolveOlioContext(user, ctx.book, ctx.bctx, ctx.dataPath);
		}
		catch(OlioException e) {
			/// OlioException text is written by this module and PbOlioContextUtil (slug, role path); safe for the owner
			ctx.octxFailure = (e.getMessage() != null ? e.getMessage() : "the book's world could not be opened");
			logger.warn("compose-scene " + ctx.label() + ": no Olio context for wardrobe alternates", e);
		}
		catch(RuntimeException e) {
			ctx.octxFailure = PbServiceFacade.curatedMessage(e);
			logger.warn("compose-scene " + ctx.label() + ": no Olio context for wardrobe alternates", e);
		}
		return ctx.octx;
	}

	/**
	 * Package-private pure builder of the {@code MIGRATED} staging: the roster in order (first HERO, rest
	 * SUPPORTING), default slots, {@link PbLayoutUtil#defaultShotType} and eye level; mood and setting from the
	 * scene's own fields. No LLM content is involved.
	 */
	static Staging migratedStaging(BaseRecord scene, List<BaseRecord> roster) {
		Staging s = new Staging();
		s.source = PbCompositionSourceEnumType.MIGRATED;
		List<BaseRecord> cast = (roster != null ? roster : Collections.emptyList());
		int n = cast.size();
		s.shot = PbLayoutUtil.defaultShotType(n);
		s.viewpoint = PbLayoutUtil.defaultViewpoint();
		for(int i = 0; i < n; i++) {
			BaseRecord cp = cast.get(i);
			ResolvedActor ra = new ResolvedActor();
			ra.character = cp;
			ra.displayName = str(cp.get(FieldNames.FIELD_NAME));
			ra.role = (i == 0 ? PbActorRoleEnumType.HERO : PbActorRoleEnumType.SUPPORTING);
			ra.placement = PbLayoutUtil.defaultPlacement(i, n, s.shot, ra.role);
			ra.placementRecognized = false;
			s.actors.add(ra);
		}
		s.focalIndex = (n > 0 ? 0 : -1);
		String mood = str(scene.get(OlioFieldNames.FIELD_PB_MOOD));
		s.mood = (NarrativeUtil.isMeaningful(mood) ? mood.trim() : null);
		String setting = str(scene.get(OlioFieldNames.FIELD_PB_SETTING));
		s.settingText = (NarrativeUtil.isMeaningful(setting) ? setting.trim() : null);
		return s;
	}

	private static void layoutActor(ResolvedActor ra, int ordinal, int count, PbShotTypeEnumType shot, ComposeResult result) {
		if(ra.placementWord != null) {
			Placement pl = PbLayoutUtil.place(ra.placementWord, shot, ra.role);
			if(pl.recognized()) {
				ra.placement = pl;
				ra.placementRecognized = true;
				return;
			}
			noteUnrecognized(ra.placementWord, result);
		}
		ra.placement = PbLayoutUtil.defaultPlacement(ordinal, count, shot, ra.role);
		ra.placementRecognized = false;
	}

	private static void noteUnrecognized(String words, ComposeResult result) {
		if(!result.unrecognizedPlacements.contains(words)) {
			result.unrecognizedPlacements.add(words);
			logger.warn("compose-scene: unrecognised placement '" + words + "', using a default slot");
		}
	}

	/// The focal actor: the one whose name the LLM named, else the first HERO, else the first actor.
	private static int focalIndex(List<ResolvedActor> actors, String focalName) {
		if(actors.isEmpty()) {
			return -1;
		}
		if(focalName != null) {
			for(int i = 0; i < actors.size(); i++) {
				ResolvedActor ra = actors.get(i);
				if(ra.displayName != null && PictureBookUtil.namesMatchAccentInsensitive(ra.displayName, focalName)) {
					return i;
				}
				if(ra.character != null && firstNameMatches(ra.character, focalName)) {
					return i;
				}
			}
		}
		for(int i = 0; i < actors.size(); i++) {
			if(actors.get(i).role == PbActorRoleEnumType.HERO) {
				return i;
			}
		}
		return 0;
	}

	/// Cast candidates first (full name, then a unique first name), then the book's character groups shadow-first.
	private static BaseRecord resolveCharacter(BaseRecord user, String name, Cast cast, List<BaseRecord> groups) {
		for(BaseRecord cp : cast.members) {
			String cn = str(cp.get(FieldNames.FIELD_NAME));
			if(cn != null && PictureBookUtil.namesMatchAccentInsensitive(cn, name)) {
				return cp;
			}
		}
		BaseRecord byFirst = null;
		int firstHits = 0;
		for(BaseRecord cp : cast.members) {
			if(firstNameMatches(cp, name)) {
				firstHits++;
				byFirst = cp;
			}
		}
		if(firstHits == 1) {
			return byFirst;
		}
		for(BaseRecord grp : groups) {
			try {
				BaseRecord hit = PictureBookUtil.findCharPersonByNameInGroup(user, name, grp);
				if(hit != null) {
					return hit;
				}
			}
			catch(Exception e) {
				logger.warn("Lookup of '" + name + "' in group " + grp.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
			}
		}
		return null;
	}

	private static boolean firstNameMatches(BaseRecord cp, String name) {
		String fn = (cp.hasField(FieldNames.FIELD_FIRST_NAME) ? str(cp.get(FieldNames.FIELD_FIRST_NAME)) : null);
		if(fn == null || fn.isBlank()) {
			String full = str(cp.get(FieldNames.FIELD_NAME));
			if(full == null) {
				return false;
			}
			int sp = full.trim().indexOf(' ');
			fn = (sp > 0 ? full.trim().substring(0, sp) : full.trim());
		}
		return PictureBookUtil.namesMatchAccentInsensitive(fn, name.trim());
	}

	/// An element whose description names a known item (case-insensitive, whole words, optional plural s/es) links
	/// that item; the longest matching name wins. Whole-word so "cup" does not link inside "cupboard".
	private static BaseRecord matchItem(String description, List<BaseRecord> items) {
		if(description == null || items.isEmpty()) {
			return null;
		}
		BaseRecord best = null;
		int bestLen = 0;
		for(BaseRecord item : items) {
			String n = str(item.get(FieldNames.FIELD_NAME));
			if(n == null || n.isBlank()) {
				continue;
			}
			String ln = n.trim();
			if(ln.length() > bestLen && itemNamePattern(ln).matcher(description).find()) {
				best = item;
				bestLen = ln.length();
			}
		}
		return best;
	}

	private static Pattern itemNamePattern(String name) {
		return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?:e?s)?(?![\\p{L}\\p{N}])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	private static String cameraText(Proposal p) {
		List<String> parts = new ArrayList<>();
		if(p.shotWord != null) {
			parts.add(p.shotWord);
		}
		if(p.viewpointWord != null) {
			parts.add(p.viewpointWord);
		}
		return (parts.isEmpty() ? null : String.join(", ", parts));
	}

	private static String locationHint(Proposal p) {
		List<String> parts = new ArrayList<>();
		if(p.locationName != null) {
			parts.add(p.locationName);
		}
		if(p.sameAs != null) {
			parts.add("sameAs=" + p.sameAs);
		}
		if(p.variant != null) {
			parts.add("variant=" + p.variant);
		}
		return (parts.isEmpty() ? null : String.join("; ", parts));
	}

	// ─────────────────────────────── persist ───────────────────────────────

	/// Writes as the request user through AccessPoint, in the scene's group. New: composition, rows, then the
	/// focalActor patch. Existing: clear focalActor, delete every row (each delete checked - abort before
	/// recreating on the first false), recreate rows, then one patch with the staging fields, source,
	/// userEdited=false, revision+1 and focalActor. Every update result is checked.
	static void persist(BaseRecord user, Context ctx, BaseRecord existing, Staging s, ComposeResult result) {
		BaseRecord comp;
		if(existing == null) {
			comp = createComposition(user, ctx, s);
		}
		else {
			comp = existing;
			BaseRecord clear = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			setQuiet(clear, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, null);
			if(IOSystem.getActiveContext().getAccessPoint().update(user, clear) == null) {
				fail(result, existing, "Failed to clear focalActor on composition " + comp.get(FieldNames.FIELD_OBJECT_ID));
				return;
			}
			List<BaseRecord> oldRows = new ArrayList<>(listActors(user, comp));
			oldRows.addAll(listElements(user, comp));
			for(int i = 0; i < oldRows.size(); i++) {
				BaseRecord row = oldRows.get(i);
				if(!IOSystem.getActiveContext().getAccessPoint().delete(user, row)) {
					fail(result, existing, "Failed to delete " + row.getSchema() + " " + row.get(FieldNames.FIELD_OBJECT_ID)
						+ " of composition " + comp.get(FieldNames.FIELD_OBJECT_ID) + "; rows left as they are");
					// focalActor is already cleared above, so even a first-row failure leaves the composition inconsistent
					flagForRecompose(user, comp);
					return;
				}
			}
		}
		String compOid = str(comp.get(FieldNames.FIELD_OBJECT_ID));
		List<BaseRecord> createdActors = new ArrayList<>();
		for(int i = 0; i < s.actors.size(); i++) {
			BaseRecord created = createActor(user, ctx, comp, compOid, s.actors.get(i), i);
			if(created == null) {
				fail(result, comp, "Failed to create actor row " + i + " of composition " + compOid);
				flagForRecompose(user, comp);
				return;
			}
			createdActors.add(created);
		}
		for(int i = 0; i < s.elements.size(); i++) {
			if(createElement(user, ctx, comp, compOid, s.elements.get(i), i) == null) {
				fail(result, comp, "Failed to create element row " + i + " of composition " + compOid);
				flagForRecompose(user, comp);
				return;
			}
		}
		BaseRecord focal = (s.focalIndex >= 0 && s.focalIndex < createdActors.size() ? createdActors.get(s.focalIndex) : null);
		BaseRecord patch;
		if(existing == null) {
			patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			setQuiet(patch, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, focal);
		}
		else {
			Integer rev = existing.get(OlioFieldNames.FIELD_PB_REVISION);
			patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_FOCAL_ACTOR,
				OlioFieldNames.FIELD_PB_SETTING_TEXT, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD,
				OlioFieldNames.FIELD_PB_SHOT_TYPE, OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_CAMERA_TEXT,
				OlioFieldNames.FIELD_PB_SOURCE, OlioFieldNames.FIELD_PB_USER_EDITED, OlioFieldNames.FIELD_PB_REVISION,
				FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
			setQuiet(patch, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, focal);
			applyStagingFields(patch, ctx, s);
			setQuiet(patch, OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
			setQuiet(patch, OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf((rev != null ? rev.intValue() : 0) + 1));
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			fail(result, comp, "Failed to finalise composition " + compOid);
			return;
		}
		BaseRecord fresh = readComposition(user, compOid, ctx.orgId);
		result.composition = (fresh != null ? fresh : comp);
		result.actors = listActors(user, result.composition);
		result.elements = listElements(user, result.composition);
		result.source = s.source;
	}

	private static void fail(ComposeResult result, BaseRecord comp, String why) {
		result.hardFailure = true;
		result.failureReason = why;
		result.composition = comp;
		logger.error("compose-scene: " + why);
	}

	/// A composition whose rows are partially written must not read as a finished LLM/USER one: mark it MIGRATED
	/// and unedited so needsCompose() flags it. Best effort - a failure here is logged, the hard failure already stands.
	private static void flagForRecompose(BaseRecord user, BaseRecord comp) {
		try {
			BaseRecord patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_SOURCE,
				OlioFieldNames.FIELD_PB_USER_EDITED);
			setQuiet(patch, OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.MIGRATED.toString());
			setQuiet(patch, OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.error("compose-scene: could not flag composition " + comp.get(FieldNames.FIELD_OBJECT_ID) + " for re-compose");
			}
		}
		catch(RuntimeException e) {
			logger.error("compose-scene: could not flag composition " + comp.get(FieldNames.FIELD_OBJECT_ID) + " for re-compose: " + e.getMessage());
		}
	}

	/**
	 * Create the scene's {@code SCENE}-purpose composition from the staging. The scene's {@code configOverride}
	 * is carried onto every new composition regardless of source (the composition is the render's config
	 * authority, design §9.1, so a scene-level override must not vanish on the first LLM compose). The legacy
	 * {@code sdPrompt} / {@code promptLocked} pair is carried only for a {@code MIGRATED} composition — the
	 * P4-10 one-off shape — because an LLM compose is expected to supersede the legacy prompt, and
	 * {@link #persist} never patches {@code promptOverride}/{@code promptLocked}/{@code configOverride} on an
	 * existing composition, so values carried here survive later recomposes.
	 */
	private static BaseRecord createComposition(BaseRecord user, Context ctx, Staging s) {
		String name = COMPOSITION_NAME_PREFIX + str(ctx.scene.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord comp;
		try {
			comp = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION);
			own(user, comp, ctx, name);
			comp.set(OlioFieldNames.FIELD_PB_BOOK, ctx.book);
			comp.set(OlioFieldNames.FIELD_PB_PURPOSE, PbCompositionPurposeEnumType.SCENE.toString());
			comp.set(OlioFieldNames.FIELD_PB_SCENE, ctx.scene);
			comp.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
			comp.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(1));
			applyStagingFields(comp, ctx, s);
			// Every source: the scene's config override is the composition's from the start.
			String cfg = str(ctx.scene.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			if(cfg != null && !cfg.isBlank()) {
				comp.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, cfg);
			}
			// MIGRATED only: the legacy prompt and its lock.
			if(s.source == PbCompositionSourceEnumType.MIGRATED) {
				String sdPrompt = str(ctx.scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
				if(NarrativeUtil.isMeaningful(sdPrompt)) {
					comp.set(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, sdPrompt.trim());
				}
				Boolean locked = ctx.scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
				comp.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.valueOf(Boolean.TRUE.equals(locked)));
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble composition " + name + ": " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble composition " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, comp);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create composition " + name);
		}
		BaseRecord fresh = readComposition(user, str(created.get(FieldNames.FIELD_OBJECT_ID)), ctx.orgId);
		if(fresh == null) {
			throw new PictureBookException(500, "Composition " + name + " was created but cannot be read back");
		}
		return fresh;
	}

	/// The fields both the create and the recompose patch carry. Enum fields are set as their names.
	private static void applyStagingFields(BaseRecord comp, Context ctx, Staging s) {
		setQuiet(comp, OlioFieldNames.FIELD_PB_SETTING_TEXT, s.settingText);
		String action = str(ctx.scene.get(OlioFieldNames.FIELD_PB_ACTION));
		setQuiet(comp, OlioFieldNames.FIELD_PB_ACTION, (NarrativeUtil.isMeaningful(action) ? action.trim() : null));
		setQuiet(comp, OlioFieldNames.FIELD_PB_MOOD, cap(s.mood, MAX_MOOD_CHARS));
		setQuiet(comp, OlioFieldNames.FIELD_PB_SHOT_TYPE, s.shot.toString());
		setQuiet(comp, OlioFieldNames.FIELD_PB_VIEWPOINT, s.viewpoint.toString());
		setQuiet(comp, OlioFieldNames.FIELD_PB_CAMERA_TEXT, s.cameraText);
		setQuiet(comp, OlioFieldNames.FIELD_PB_SOURCE, s.source.toString());
		setQuiet(comp, FieldNames.FIELD_LOCATION, (ctx.scene.hasField(FieldNames.FIELD_LOCATION) ? ctx.scene.get(FieldNames.FIELD_LOCATION) : null));
		setQuiet(comp, OlioFieldNames.FIELD_PB_LOCATION_VARIANT,
			(ctx.scene.hasField(OlioFieldNames.FIELD_PB_LOCATION_VARIANT) ? ctx.scene.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT) : null));
	}

	private static BaseRecord createActor(BaseRecord user, Context ctx, BaseRecord comp, String compOid, ResolvedActor ra, int index) {
		String name = ACTOR_NAME_PREFIX + compOid + "." + index;
		BaseRecord row;
		try {
			row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ACTOR);
			own(user, row, ctx, name);
			row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
			if(ra.character != null) {
				row.set(OlioFieldNames.FIELD_PB_CHARACTER, ra.character);
				if(ra.apparel != null) {
					row.set(OlioFieldNames.FIELD_APPAREL, ra.apparel);
				}
			}
			row.set(OlioFieldNames.FIELD_PB_ROLE, ra.role.toString());
			row.set(OlioFieldNames.FIELD_PB_PRIORITY, Integer.valueOf(index));
			setQuiet(row, OlioFieldNames.FIELD_PB_POSE, cap(ra.pose, MAX_FIELD_CHARS));
			setQuiet(row, FieldNames.FIELD_EXPRESSION, cap(ra.expression, MAX_EXPRESSION_CHARS));
			setQuiet(row, OlioFieldNames.FIELD_PB_ACTION, ra.action);
			setQuiet(row, FieldNames.FIELD_DESCRIPTION, ra.description);
			row.set(FieldNames.FIELD_COUNT, Integer.valueOf(Math.min(MAX_CROWD_COUNT, Math.max(1, ra.count))));
			applyPlacement(row, ra.placement);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble actor row " + name + ": " + e.getMessage(), e);
			return null;
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, row);
		if(created == null) {
			return null;
		}
		return created;
	}

	private static BaseRecord createElement(BaseRecord user, Context ctx, BaseRecord comp, String compOid, ResolvedElement re, int index) {
		String name = ELEMENT_NAME_PREFIX + compOid + "." + index;
		BaseRecord row;
		try {
			row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ELEMENT);
			own(user, row, ctx, name);
			row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
			row.set(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, re.type.toString());
			setQuiet(row, FieldNames.FIELD_DESCRIPTION, re.description);
			row.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.MEDIUM.toString());
			if(re.item != null) {
				row.set(OlioFieldNames.FIELD_ITEM, re.item);
			}
			applyPlacement(row, re.placement);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble element row " + name + ": " + e.getMessage(), e);
			return null;
		}
		return IOSystem.getActiveContext().getAccessPoint().create(user, row);
	}

	private static void applyPlacement(BaseRecord row, Placement pl) throws FieldException, ValueException, ModelNotFoundException {
		if(pl == null) {
			return;
		}
		row.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(pl.x()));
		row.set(OlioFieldNames.FIELD_PB_Y, Double.valueOf(pl.y()));
		row.set(OlioFieldNames.FIELD_PB_W, Double.valueOf(pl.w()));
		row.set(OlioFieldNames.FIELD_PB_H, Double.valueOf(pl.h()));
		row.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(pl.depth()));
	}

	/// Group = the scene's group (no makePath: a compose never creates a directory); owner = the caller.
	/// None of the three models inherits common.name, so the name is set explicitly.
	private static void own(BaseRecord user, BaseRecord rec, Context ctx, String name) throws FieldException, ValueException, ModelNotFoundException {
		IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, rec, ctx.orgId);
		rec.set(FieldNames.FIELD_GROUP_ID, Long.valueOf(ctx.bookGroupId));
		rec.set(FieldNames.FIELD_NAME, name);
	}

	public static BaseRecord readComposition(BaseRecord user, String objectId, long orgId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(compositionRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	public static BaseRecord readElement(BaseRecord user, String objectId, long orgId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE_ELEMENT, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(elementRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	public static BaseRecord readActor(BaseRecord user, String objectId, long orgId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE_ACTOR, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(actorRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── row-level edits (design §8, §24, §27; P4-8) ───────────────────────────────

	/**
	 * Outcome of one row-level edit through the board: the composition's revision after the edit and what
	 * happened to the scene's render node. {@code nodeStale} is false when the scene has no node, the node is
	 * RUNNING, or the stale write failed; {@code nodeStatus} is the node's status as re-read (null without a node).
	 */
	public static class EditResult {
		public int revision;
		public boolean nodeStale;
		public String nodeStatus;
	}

	/** Composition fields the board may change; {@code focalActorObjectId} is the wire name of {@code focalActor}. */
	static final List<String> COMPOSITION_EDITABLE = Arrays.asList(
		OlioFieldNames.FIELD_PB_SETTING_TEXT, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD,
		OlioFieldNames.FIELD_PB_SHOT_TYPE, OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_CAMERA_TEXT,
		OlioFieldNames.FIELD_PB_PROPS, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE,
		OlioFieldNames.FIELD_PB_PROMPT_LOCKED, "focalActorObjectId");
	static final List<String> ACTOR_EDITABLE = Arrays.asList(
		OlioFieldNames.FIELD_PB_ROLE, OlioFieldNames.FIELD_PB_PRIORITY, OlioFieldNames.FIELD_PB_POSE, FieldNames.FIELD_EXPRESSION,
		OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_FACING, OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y,
		OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H, OlioFieldNames.FIELD_PB_DEPTH, FieldNames.FIELD_DESCRIPTION,
		FieldNames.FIELD_COUNT, OlioFieldNames.FIELD_PB_REFERENCE_POLICY);
	static final List<String> ELEMENT_EDITABLE = Arrays.asList(
		OlioFieldNames.FIELD_PB_ELEMENT_TYPE, FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y,
		OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H, OlioFieldNames.FIELD_PB_DEPTH, OlioFieldNames.FIELD_PB_IMPORTANCE);
	/** Fields a client may name but this path refuses, with where the change belongs (400 "<field> cannot be set here; <where>"). */
	static final Map<String, String> COMPOSITION_REFUSED = refusedMap(
		OlioFieldNames.FIELD_PB_BOOK, "a composition's book is fixed",
		OlioFieldNames.FIELD_PB_SCENE, "a composition's scene is fixed",
		OlioFieldNames.FIELD_PB_PURPOSE, "a composition's purpose is fixed",
		OlioFieldNames.FIELD_PB_SOURCE, "source is set by the composer and by edits",
		OlioFieldNames.FIELD_PB_REVISION, "revision is bumped by every edit",
		OlioFieldNames.FIELD_PB_USER_EDITED, "userEdited is set by every edit",
		FieldNames.FIELD_NAME, "a composition's name is its handle",
		FieldNames.FIELD_LOCATION, "point the scene at a location with PUT /scene/{id}/location",
		OlioFieldNames.FIELD_PB_LOCATION_VARIANT, "point the scene at a location with PUT /scene/{id}/location",
		OlioFieldNames.FIELD_PB_FOCAL_ACTOR, "name the actor as focalActorObjectId");
	static final Map<String, String> ACTOR_REFUSED = refusedMap(
		OlioFieldNames.FIELD_PB_COMPOSITION, "an actor's composition is fixed",
		FieldNames.FIELD_NAME, "an actor's name is its handle",
		OlioFieldNames.FIELD_PB_CHARACTER, "an actor's character is fixed; add a new actor instead",
		"characterObjectId", "an actor's character is fixed; add a new actor instead",
		OlioFieldNames.FIELD_APPAREL, "pin an outfit with PUT .../actor/{id}/apparel",
		"apparelObjectId", "pin an outfit with PUT .../actor/{id}/apparel");
	static final Map<String, String> ELEMENT_REFUSED = refusedMap(
		OlioFieldNames.FIELD_PB_COMPOSITION, "an element's composition is fixed",
		FieldNames.FIELD_NAME, "an element's name is its handle",
		OlioFieldNames.FIELD_ITEM, "link a world item with POST .../element/{id}/promote",
		"itemObjectId", "link a world item with POST .../element/{id}/promote");

	private static Map<String, String> refusedMap(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	/**
	 * The next free numeric handle suffix for rows named {@code <prefix><compositionObjectId>.<n>}: one more
	 * than the largest suffix in {@code rows}, never below 1, so a row added after a middle delete never reuses
	 * a name a sibling still carries (the row name is unique per group).
	 */
	static int nextHandleIndex(List<BaseRecord> rows) {
		int max = -1;
		if(rows != null) {
			for(BaseRecord r : rows) {
				String name = str(r.get(FieldNames.FIELD_NAME));
				int dot = (name != null ? name.lastIndexOf('.') : -1);
				if(dot < 0 || dot + 1 >= name.length()) {
					continue;
				}
				try {
					max = Math.max(max, Integer.parseInt(name.substring(dot + 1)));
				}
				catch(NumberFormatException e) {
					/// a hand-named row carries no suffix to count from
				}
			}
		}
		return Math.max(1, max + 1);
	}

	/**
	 * Adds one actor row to {@code comp} from a client map ({@code characterObjectId} or a {@code description},
	 * plus any {@link #ACTOR_EDITABLE} field), as {@code user} through {@code AccessPoint}, in the composition's
	 * own group; the caller has already authorized the book for update and matched the composition to the book.
	 * Defaults: role SUPPORTING, priority = the handle index, count 1, referencePolicy AUTO, and a box from
	 * {@link PbLayoutUtil#defaultPlacement} under the composition's shot type when none is given.
	 *
	 * @return the row as re-read (never the create stub)
	 * @throws PictureBookException 409 when the composition is a ChapBook page (G-35: actors are off on pages);
	 *         400 on an unknown, refused, mistyped or out-of-range field, or an actor with neither a character nor
	 *         a description; 404 when {@code characterObjectId} is not a character of this book
	 */
	public static BaseRecord createActorRow(BaseRecord user, BaseRecord book, BaseRecord comp, Map<String, Object> in) {
		if(user == null || book == null || comp == null) {
			throw new PictureBookException(400, "A composition is required");
		}
		if(PbCompositionPurposeEnumType.CHAPBOOK_PAGE.toString().equalsIgnoreCase(str(comp.get(OlioFieldNames.FIELD_PB_PURPOSE)))) {
			throw new PictureBookException(409, "Actors are not enabled on ChapBook pages");
		}
		Map<String, Object> body = (in != null ? in : Collections.emptyMap());
		checkKeys(body, ACTOR_EDITABLE, ACTOR_REFUSED, Arrays.asList("characterObjectId"));
		long orgId = PbGraphUtil.orgId(comp);
		String compOid = str(comp.get(FieldNames.FIELD_OBJECT_ID));
		List<BaseRecord> rows = listActors(user, comp);
		int n = nextHandleIndex(rows);
		String name = ACTOR_NAME_PREFIX + compOid + "." + n;

		String characterOid = stringField(body, "characterObjectId", 0);
		BaseRecord character = (characterOid != null ? PbWardrobeUtil.requireCharacterOfBook(user, book, characterOid) : null);
		String description = stringField(body, FieldNames.FIELD_DESCRIPTION, MAX_FIELD_CHARS);
		if(character == null && description == null) {
			throw new PictureBookException(400, "An actor without a character needs a description");
		}
		PbActorRoleEnumType role = enumField(body, OlioFieldNames.FIELD_PB_ROLE, PbActorRoleEnumType.values());
		if(role == null) {
			role = PbActorRoleEnumType.SUPPORTING;
		}
		Integer priority = intField(body, OlioFieldNames.FIELD_PB_PRIORITY);
		Integer count = intField(body, FieldNames.FIELD_COUNT);
		PbReferencePolicyEnumType refPolicy = enumField(body, OlioFieldNames.FIELD_PB_REFERENCE_POLICY, PbReferencePolicyEnumType.values());
		PbFacingEnumType facing = enumField(body, OlioFieldNames.FIELD_PB_FACING, PbFacingEnumType.values());
		String pose = stringField(body, OlioFieldNames.FIELD_PB_POSE, MAX_FIELD_CHARS);
		String expression = stringField(body, FieldNames.FIELD_EXPRESSION, MAX_EXPRESSION_CHARS);
		String action = stringField(body, OlioFieldNames.FIELD_PB_ACTION, 0);
		Placement dflt = PbLayoutUtil.defaultPlacement(rows.size(), rows.size() + 1, shotTypeOf(comp), role);
		double[] box = boxFields(body, dflt);
		Integer depth = intField(body, OlioFieldNames.FIELD_PB_DEPTH);

		BaseRecord row;
		try {
			row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ACTOR);
			ownRow(user, row, comp, orgId, name);
			row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
			if(character != null) {
				row.set(OlioFieldNames.FIELD_PB_CHARACTER, character);
			}
			row.set(OlioFieldNames.FIELD_PB_ROLE, role.toString());
			row.set(OlioFieldNames.FIELD_PB_PRIORITY, Integer.valueOf(priority != null ? priority.intValue() : n));
			row.set(OlioFieldNames.FIELD_PB_POSE, pose);
			row.set(FieldNames.FIELD_EXPRESSION, expression);
			row.set(OlioFieldNames.FIELD_PB_ACTION, action);
			row.set(FieldNames.FIELD_DESCRIPTION, description);
			if(facing != null) {
				row.set(OlioFieldNames.FIELD_PB_FACING, facing.toString());
			}
			row.set(FieldNames.FIELD_COUNT, Integer.valueOf(clampCount(count != null ? count.intValue() : 1)));
			row.set(OlioFieldNames.FIELD_PB_REFERENCE_POLICY, (refPolicy != null ? refPolicy : PbReferencePolicyEnumType.AUTO).toString());
			row.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(box[0]));
			row.set(OlioFieldNames.FIELD_PB_Y, Double.valueOf(box[1]));
			row.set(OlioFieldNames.FIELD_PB_W, Double.valueOf(box[2]));
			row.set(OlioFieldNames.FIELD_PB_H, Double.valueOf(box[3]));
			row.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(depth != null ? depth.intValue() : dflt.depth()));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble actor row " + name + ": " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble actor row " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, row);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create actor row " + name);
		}
		BaseRecord fresh = readActor(user, str(created.get(FieldNames.FIELD_OBJECT_ID)), orgId);
		if(fresh == null) {
			throw new PictureBookException(500, "Actor row " + name + " was created but cannot be read back");
		}
		return fresh;
	}

	/**
	 * Adds one element row to {@code comp} from a client map ({@code elementType} and {@code description}
	 * required, plus any {@link #ELEMENT_EDITABLE} field). Same ownership, naming, defaults and errors as
	 * {@link #createActorRow}; importance defaults to MEDIUM. Allowed on every composition purpose.
	 */
	public static BaseRecord createElementRow(BaseRecord user, BaseRecord comp, Map<String, Object> in) {
		if(user == null || comp == null) {
			throw new PictureBookException(400, "A composition is required");
		}
		Map<String, Object> body = (in != null ? in : Collections.emptyMap());
		checkKeys(body, ELEMENT_EDITABLE, ELEMENT_REFUSED, Collections.emptyList());
		long orgId = PbGraphUtil.orgId(comp);
		String compOid = str(comp.get(FieldNames.FIELD_OBJECT_ID));
		List<BaseRecord> rows = listElements(user, comp);
		int n = nextHandleIndex(rows);
		String name = ELEMENT_NAME_PREFIX + compOid + "." + n;

		PbElementTypeEnumType type = enumField(body, OlioFieldNames.FIELD_PB_ELEMENT_TYPE, PbElementTypeEnumType.values());
		if(type == null) {
			throw new PictureBookException(400, "elementType is required");
		}
		String description = stringField(body, FieldNames.FIELD_DESCRIPTION, MAX_FIELD_CHARS);
		if(description == null) {
			throw new PictureBookException(400, "An element needs a description");
		}
		PbImportanceEnumType importance = enumField(body, OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.values());
		Placement dflt = PbLayoutUtil.defaultPlacement(rows.size(), rows.size() + 1, shotTypeOf(comp), null);
		double[] box = boxFields(body, dflt);
		Integer depth = intField(body, OlioFieldNames.FIELD_PB_DEPTH);

		BaseRecord row;
		try {
			row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ELEMENT);
			ownRow(user, row, comp, orgId, name);
			row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
			row.set(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, type.toString());
			row.set(FieldNames.FIELD_DESCRIPTION, description);
			row.set(OlioFieldNames.FIELD_PB_IMPORTANCE, (importance != null ? importance : PbImportanceEnumType.MEDIUM).toString());
			row.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(box[0]));
			row.set(OlioFieldNames.FIELD_PB_Y, Double.valueOf(box[1]));
			row.set(OlioFieldNames.FIELD_PB_W, Double.valueOf(box[2]));
			row.set(OlioFieldNames.FIELD_PB_H, Double.valueOf(box[3]));
			row.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(depth != null ? depth.intValue() : dflt.depth()));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble element row " + name + ": " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble element row " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, row);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create element row " + name);
		}
		BaseRecord fresh = readElement(user, str(created.get(FieldNames.FIELD_OBJECT_ID)), orgId);
		if(fresh == null) {
			throw new PictureBookException(500, "Element row " + name + " was created but cannot be read back");
		}
		return fresh;
	}

	/**
	 * Applies a client map of {@link #COMPOSITION_EDITABLE} fields to {@code comp} as one sparse patch (identity +
	 * the fields named). {@code focalActorObjectId} must name one of this composition's own actor rows (null
	 * clears it); {@code configOverride} must parse as sparse {@code olio.sd.config} JSON carrying its schema
	 * (null or blank clears it). Does not bump the revision - the caller follows with {@link #touchEdited}.
	 *
	 * @throws PictureBookException 400 on an empty map, an unknown or refused key, a mistyped value, an enum
	 *         outside its set, a focal actor of another composition, or a non-sparse configOverride; 500 when the
	 *         write is refused
	 */
	public static void patchCompositionFields(BaseRecord user, BaseRecord comp, Map<String, Object> in) {
		if(user == null || comp == null) {
			throw new PictureBookException(400, "A composition is required");
		}
		if(in == null || in.isEmpty()) {
			throw new PictureBookException(400, "No fields to update");
		}
		checkKeys(in, COMPOSITION_EDITABLE, COMPOSITION_REFUSED, Collections.emptyList());
		List<String> changed = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		for(String key : in.keySet()) {
			switch(key) {
				case OlioFieldNames.FIELD_PB_SETTING_TEXT:
				case OlioFieldNames.FIELD_PB_ACTION:
				case OlioFieldNames.FIELD_PB_CAMERA_TEXT:
				case OlioFieldNames.FIELD_PB_PROPS:
					values.put(key, stringField(in, key, 0));
					break;
				case OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE: {
					// Mirrored onto scene.sdPrompt for a ChapBook page: clean it exactly as the scene-only prompt route does.
					String p = ChapBookUtil.sanitizeText(stringField(in, key, 0));
					values.put(key, (p == null || p.isBlank() ? null : p.trim()));
					break;
				}
				case OlioFieldNames.FIELD_PB_MOOD:
					values.put(key, stringField(in, key, MAX_MOOD_CHARS));
					break;
				case OlioFieldNames.FIELD_PB_SHOT_TYPE:
					values.put(key, requiredEnum(enumField(in, key, PbShotTypeEnumType.values()), key, PbShotTypeEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_VIEWPOINT:
					values.put(key, requiredEnum(enumField(in, key, PbViewpointEnumType.values()), key, PbViewpointEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_PROMPT_LOCKED:
					values.put(key, booleanField(in, key));
					break;
				case OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE: {
					String cfg = stringField(in, key, 0);
					if(cfg != null && PbConfigUtil.parseOverride(cfg) == null) {
						throw new PictureBookException(400, "configOverride must be sparse " + OlioModelNames.MODEL_SD_CONFIG + " JSON carrying its schema");
					}
					values.put(key, cfg);
					break;
				}
				case "focalActorObjectId": {
					String actorOid = stringField(in, key, 0);
					BaseRecord actor = null;
					if(actorOid != null) {
						actor = readActor(user, actorOid, PbGraphUtil.orgId(comp));
						if(actor == null || PbHealthUtil.fkId(actor, OlioFieldNames.FIELD_PB_COMPOSITION) != PbHealthUtil.idOf(comp)) {
							throw new PictureBookException(400, "focalActor must be an actor of this composition");
						}
					}
					values.put(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, actor);
					break;
				}
				default:
					throw new PictureBookException(400, "Unknown field " + key);
			}
		}
		changed.addAll(values.keySet());
		BaseRecord patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, changed.toArray(new String[0]));
		for(Map.Entry<String, Object> e : values.entrySet()) {
			setQuiet(patch, e.getKey(), e.getValue());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to persist composition " + comp.get(FieldNames.FIELD_OBJECT_ID));
		}
		for(Map.Entry<String, Object> e : values.entrySet()) {
			setQuiet(comp, e.getKey(), e.getValue());
		}
	}

	/**
	 * Applies a client map of {@link #ACTOR_EDITABLE} fields to one actor row as one sparse patch. The character
	 * and apparel are refused here (400): a character is fixed for the row's life, an outfit is pinned through
	 * the wardrobe route. {@code count} is clamped to 1..{@link #MAX_CROWD_COUNT}; a box value outside 0..1 is a
	 * 400, not clamped; an actor without a character keeps needing a description.
	 */
	public static void patchActorRow(BaseRecord user, BaseRecord actor, Map<String, Object> in) {
		if(user == null || actor == null) {
			throw new PictureBookException(400, "An actor is required");
		}
		if(in == null || in.isEmpty()) {
			throw new PictureBookException(400, "No fields to update");
		}
		checkKeys(in, ACTOR_EDITABLE, ACTOR_REFUSED, Collections.emptyList());
		Map<String, Object> values = new LinkedHashMap<>();
		for(String key : in.keySet()) {
			switch(key) {
				case OlioFieldNames.FIELD_PB_ROLE:
					values.put(key, requiredEnum(enumField(in, key, PbActorRoleEnumType.values()), key, PbActorRoleEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_FACING:
					values.put(key, requiredEnum(enumField(in, key, PbFacingEnumType.values()), key, PbFacingEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_REFERENCE_POLICY:
					values.put(key, requiredEnum(enumField(in, key, PbReferencePolicyEnumType.values()), key, PbReferencePolicyEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_PRIORITY:
				case OlioFieldNames.FIELD_PB_DEPTH:
					values.put(key, requiredInt(in, key));
					break;
				case FieldNames.FIELD_COUNT:
					values.put(key, Integer.valueOf(clampCount(requiredInt(in, key).intValue())));
					break;
				case OlioFieldNames.FIELD_PB_X:
				case OlioFieldNames.FIELD_PB_Y:
				case OlioFieldNames.FIELD_PB_W:
				case OlioFieldNames.FIELD_PB_H:
					values.put(key, unitField(in, key));
					break;
				case OlioFieldNames.FIELD_PB_POSE:
					values.put(key, stringField(in, key, MAX_FIELD_CHARS));
					break;
				case FieldNames.FIELD_EXPRESSION:
					values.put(key, stringField(in, key, MAX_EXPRESSION_CHARS));
					break;
				case OlioFieldNames.FIELD_PB_ACTION:
					values.put(key, stringField(in, key, 0));
					break;
				case FieldNames.FIELD_DESCRIPTION: {
					String d = stringField(in, key, MAX_FIELD_CHARS);
					if(d == null && PbHealthUtil.fkId(actor, OlioFieldNames.FIELD_PB_CHARACTER) <= 0L) {
						throw new PictureBookException(400, "An actor without a character needs a description");
					}
					values.put(key, d);
					break;
				}
				default:
					throw new PictureBookException(400, "Unknown field " + key);
			}
		}
		writeRowPatch(user, actor, OlioModelNames.MODEL_PB_SCENE_ACTOR, values, "actor");
	}

	/** Applies a client map of {@link #ELEMENT_EDITABLE} fields to one element row as one sparse patch; see {@link #patchActorRow}. */
	public static void patchElementRow(BaseRecord user, BaseRecord element, Map<String, Object> in) {
		if(user == null || element == null) {
			throw new PictureBookException(400, "An element is required");
		}
		if(in == null || in.isEmpty()) {
			throw new PictureBookException(400, "No fields to update");
		}
		checkKeys(in, ELEMENT_EDITABLE, ELEMENT_REFUSED, Collections.emptyList());
		Map<String, Object> values = new LinkedHashMap<>();
		for(String key : in.keySet()) {
			switch(key) {
				case OlioFieldNames.FIELD_PB_ELEMENT_TYPE:
					values.put(key, requiredEnum(enumField(in, key, PbElementTypeEnumType.values()), key, PbElementTypeEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_IMPORTANCE:
					values.put(key, requiredEnum(enumField(in, key, PbImportanceEnumType.values()), key, PbImportanceEnumType.values()).toString());
					break;
				case OlioFieldNames.FIELD_PB_DEPTH:
					values.put(key, requiredInt(in, key));
					break;
				case OlioFieldNames.FIELD_PB_X:
				case OlioFieldNames.FIELD_PB_Y:
				case OlioFieldNames.FIELD_PB_W:
				case OlioFieldNames.FIELD_PB_H:
					values.put(key, unitField(in, key));
					break;
				case FieldNames.FIELD_DESCRIPTION: {
					String d = stringField(in, key, MAX_FIELD_CHARS);
					if(d == null) {
						throw new PictureBookException(400, "An element needs a description");
					}
					values.put(key, d);
					break;
				}
				default:
					throw new PictureBookException(400, "Unknown field " + key);
			}
		}
		writeRowPatch(user, element, OlioModelNames.MODEL_PB_SCENE_ELEMENT, values, "element");
	}

	/**
	 * Deletes one actor row as {@code user}. When the composition's {@code focalActor} is this row it is cleared
	 * first (the FK would otherwise dangle), which the result reports.
	 *
	 * @return true when the focal actor was cleared
	 */
	public static boolean deleteActorRow(BaseRecord user, BaseRecord comp, BaseRecord actor) {
		if(user == null || comp == null || actor == null) {
			throw new PictureBookException(400, "An actor is required");
		}
		boolean cleared = false;
		if(PbHealthUtil.fkId(comp, OlioFieldNames.FIELD_PB_FOCAL_ACTOR) == PbHealthUtil.idOf(actor)) {
			BaseRecord patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			setQuiet(patch, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, null);
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				throw new PictureBookException(500, "Failed to clear the focal actor of composition " + comp.get(FieldNames.FIELD_OBJECT_ID));
			}
			setQuiet(comp, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, null);
			cleared = true;
		}
		if(!IOSystem.getActiveContext().getAccessPoint().delete(user, actor)) {
			throw new PictureBookException(500, "Failed to delete actor " + actor.get(FieldNames.FIELD_OBJECT_ID));
		}
		PbStaleUtil.touch(actor);
		return cleared;
	}

	/** Deletes one element row as {@code user}; 500 when the delete is refused. */
	public static void deleteElementRow(BaseRecord user, BaseRecord element) {
		if(user == null || element == null) {
			throw new PictureBookException(400, "An element is required");
		}
		if(!IOSystem.getActiveContext().getAccessPoint().delete(user, element)) {
			throw new PictureBookException(500, "Failed to delete element " + element.get(FieldNames.FIELD_OBJECT_ID));
		}
		PbStaleUtil.touch(element);
	}

	/**
	 * Records that a user edited {@code comp} or one of its rows: {@code userEdited = true}, {@code source = USER},
	 * {@code revision + 1}, the in-process stale cache touched, and the scene's render node marked STALE through
	 * {@link PbBookUtil#markSceneNodeStale} (never a 409 on a RUNNING node - the write lands, the result says the
	 * node did not go stale). {@code scene} may be null (a composition without a scene); then no node is touched.
	 *
	 * @throws PictureBookException 500 when the composition patch is refused
	 */
	public static EditResult touchEdited(BaseRecord user, BaseRecord comp, BaseRecord scene) {
		if(user == null || comp == null) {
			throw new PictureBookException(400, "A composition is required");
		}
		Object rev = comp.get(OlioFieldNames.FIELD_PB_REVISION);
		int next = (rev instanceof Number ? ((Number) rev).intValue() : 0) + 1;
		BaseRecord patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION,
			OlioFieldNames.FIELD_PB_USER_EDITED, OlioFieldNames.FIELD_PB_SOURCE, OlioFieldNames.FIELD_PB_REVISION);
		setQuiet(patch, OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
		setQuiet(patch, OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.USER.toString());
		setQuiet(patch, OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(next));
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to persist composition " + comp.get(FieldNames.FIELD_OBJECT_ID));
		}
		setQuiet(comp, OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
		setQuiet(comp, OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.USER.toString());
		setQuiet(comp, OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(next));
		PbStaleUtil.touch(comp);
		EditResult out = new EditResult();
		out.revision = next;
		if(scene != null) {
			out.nodeStale = PbBookUtil.markSceneNodeStale(user, scene);
			BaseRecord nodeRef = (scene.hasField(OlioFieldNames.FIELD_PB_SCENE_NODE) ? scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE) : null);
			long nodeId = (nodeRef != null ? PbHealthUtil.idOf(nodeRef) : 0L);
			BaseRecord node = (nodeId > 0L ? PbGraphUtil.readNodeById(user, nodeId, PbGraphUtil.orgId(comp)) : null);
			out.nodeStatus = (node != null ? str(node.get(OlioFieldNames.FIELD_PB_NODE_STATUS)) : null);
		}
		return out;
	}

	/**
	 * The ChapBook page's {@code CHAPBOOK_PAGE} composition, created on first use (design §27, P4-8): named
	 * {@code page.<sceneObjectId>}, in the book's group (the same group every SCENE composition and every reader
	 * of a page composition keys on), as {@code user} through {@code AccessPoint}, with the given {@code source},
	 * {@code revision = 1}, {@code userEdited = false} and no rows - a page composition exists to carry the
	 * page's {@code promptOverride}/{@code promptLocked}, nothing more (G-35). Only write paths call this; a
	 * read of a page without one gets null from {@link #findSceneComposition}.
	 *
	 * @throws PictureBookException 409 when the book has no group; 500 when the create or read-back fails
	 */
	public static BaseRecord getCreatePageComposition(BaseRecord user, BaseRecord book, BaseRecord scene, PbCompositionSourceEnumType source) {
		if(user == null || book == null || scene == null) {
			throw new PictureBookException(400, "A scene is required");
		}
		long orgId = PbGraphUtil.orgId(book);
		Object gid = (book.hasField(FieldNames.FIELD_GROUP_ID) ? book.get(FieldNames.FIELD_GROUP_ID) : null);
		long groupId = (gid instanceof Number ? ((Number) gid).longValue() : 0L);
		String sceneOid = str(scene.get(FieldNames.FIELD_OBJECT_ID));
		if(groupId <= 0L) {
			throw new PictureBookException(409, "Book " + str(book.get(OlioFieldNames.FIELD_PB_SLUG)) + " has no group");
		}
		BaseRecord existing = findSceneComposition(user, scene, groupId, orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
		if(existing != null) {
			return existing;
		}
		String name = PAGE_COMPOSITION_NAME_PREFIX + sceneOid;
		BaseRecord comp;
		try {
			comp = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION);
			IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, comp, orgId);
			comp.set(FieldNames.FIELD_GROUP_ID, Long.valueOf(groupId));
			comp.set(FieldNames.FIELD_NAME, name);
			comp.set(OlioFieldNames.FIELD_PB_BOOK, book);
			comp.set(OlioFieldNames.FIELD_PB_PURPOSE, PbCompositionPurposeEnumType.CHAPBOOK_PAGE.toString());
			comp.set(OlioFieldNames.FIELD_PB_SCENE, scene);
			comp.set(OlioFieldNames.FIELD_PB_SOURCE, (source != null ? source : PbCompositionSourceEnumType.USER).toString());
			comp.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
			comp.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(1));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble page composition " + name + ": " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble page composition " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, comp);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create page composition " + name);
		}
		BaseRecord fresh = readComposition(user, str(created.get(FieldNames.FIELD_OBJECT_ID)), orgId);
		if(fresh == null) {
			throw new PictureBookException(500, "Page composition " + name + " was created but cannot be read back");
		}
		return fresh;
	}

	/// Group = the composition's group; owner = the caller. None of the row models inherits common.name.
	private static void ownRow(BaseRecord user, BaseRecord row, BaseRecord comp, long orgId, String name) throws FieldException, ValueException, ModelNotFoundException {
		IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, row, orgId);
		Object gid = comp.get(FieldNames.FIELD_GROUP_ID);
		row.set(FieldNames.FIELD_GROUP_ID, Long.valueOf(gid instanceof Number ? ((Number) gid).longValue() : 0L));
		row.set(FieldNames.FIELD_NAME, name);
	}

	private static void writeRowPatch(BaseRecord user, BaseRecord row, String model, Map<String, Object> values, String what) {
		BaseRecord patch = PbGraphUtil.patchOf(row, model, values.keySet().toArray(new String[0]));
		for(Map.Entry<String, Object> e : values.entrySet()) {
			setQuiet(patch, e.getKey(), e.getValue());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to persist " + what + " " + row.get(FieldNames.FIELD_OBJECT_ID));
		}
		for(Map.Entry<String, Object> e : values.entrySet()) {
			setQuiet(row, e.getKey(), e.getValue());
		}
		PbStaleUtil.touch(row);
	}

	/// Every key must be editable (or in {@code alsoAllowed}); a refused key says where the change belongs.
	/// {@code alsoAllowed} wins over {@code refused}: it is how a create admits a key its patch refuses.
	private static void checkKeys(Map<String, Object> in, List<String> editable, Map<String, String> refused, List<String> alsoAllowed) {
		for(String key : in.keySet()) {
			if(key == null) {
				throw new PictureBookException(400, "Unknown field null");
			}
			if(alsoAllowed.contains(key)) {
				continue;
			}
			if(refused.containsKey(key)) {
				throw new PictureBookException(400, key + " cannot be set here; " + refused.get(key));
			}
			if(!editable.contains(key)) {
				throw new PictureBookException(400, "Unknown field " + key);
			}
		}
	}

	/// Absent, null or blank → null (clears); a non-string → 400; longer than {@code max} (when > 0) → 400.
	private static String stringField(Map<String, Object> in, String key, int max) {
		Object v = in.get(key);
		if(v == null) {
			return null;
		}
		if(!(v instanceof String)) {
			throw new PictureBookException(400, key + " must be a string");
		}
		String s = ((String) v).trim();
		if(s.isEmpty()) {
			return null;
		}
		if(max > 0 && s.length() > max) {
			throw new PictureBookException(400, key + " must be at most " + max + " characters");
		}
		return s;
	}

	/// Absent or null → null; a non-number → 400.
	private static Integer intField(Map<String, Object> in, String key) {
		Object v = in.get(key);
		if(v == null) {
			return null;
		}
		if(!(v instanceof Number)) {
			throw new PictureBookException(400, key + " must be a number");
		}
		return Integer.valueOf(((Number) v).intValue());
	}

	private static Integer requiredInt(Map<String, Object> in, String key) {
		Integer i = intField(in, key);
		if(i == null) {
			throw new PictureBookException(400, key + " must be a number");
		}
		return i;
	}

	/// A present box coordinate: a number in 0..1 inclusive, else 400 (never clamped).
	private static Double unitField(Map<String, Object> in, String key) {
		Object v = in.get(key);
		if(!(v instanceof Number)) {
			throw new PictureBookException(400, key + " must be a number");
		}
		double d = ((Number) v).doubleValue();
		if(Double.isNaN(d) || d < 0.0d || d > 1.0d) {
			throw new PictureBookException(400, key + " must be between 0 and 1");
		}
		return Double.valueOf(d);
	}

	/// {x, y, w, h}: the default placement with every coordinate the client named replaced (validated 0..1).
	private static double[] boxFields(Map<String, Object> in, Placement dflt) {
		double[] box = new double[] { dflt.x(), dflt.y(), dflt.w(), dflt.h() };
		String[] keys = new String[] { OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H };
		for(int i = 0; i < keys.length; i++) {
			if(in.containsKey(keys[i]) && in.get(keys[i]) != null) {
				box[i] = unitField(in, keys[i]).doubleValue();
			}
		}
		return box;
	}

	private static Boolean booleanField(Map<String, Object> in, String key) {
		Object v = in.get(key);
		if(!(v instanceof Boolean)) {
			throw new PictureBookException(400, key + " must be a boolean");
		}
		return (Boolean) v;
	}

	/// Absent or null → null; a non-string or a name outside the set → 400 "<key> must be one of A, B, C".
	private static <E extends Enum<E>> E enumField(Map<String, Object> in, String key, E[] values) {
		Object v = in.get(key);
		if(v == null) {
			return null;
		}
		if(v instanceof String) {
			String s = ((String) v).trim();
			for(E e : values) {
				if(e.name().equalsIgnoreCase(s)) {
					return e;
				}
			}
		}
		throw new PictureBookException(400, key + " must be one of " + enumNames(values));
	}

	private static <E extends Enum<E>> E requiredEnum(E value, String key, E[] values) {
		if(value == null) {
			throw new PictureBookException(400, key + " must be one of " + enumNames(values));
		}
		return value;
	}

	private static <E extends Enum<E>> String enumNames(E[] values) {
		StringBuilder sb = new StringBuilder();
		for(E e : values) {
			if(sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(e.name());
		}
		return sb.toString();
	}

	private static int clampCount(int count) {
		return Math.min(MAX_CROWD_COUNT, Math.max(1, count));
	}

	static PbShotTypeEnumType shotTypeOf(BaseRecord comp) {
		String s = (comp != null && comp.hasField(OlioFieldNames.FIELD_PB_SHOT_TYPE) ? str(comp.get(OlioFieldNames.FIELD_PB_SHOT_TYPE)) : null);
		if(s != null) {
			for(PbShotTypeEnumType e : PbShotTypeEnumType.values()) {
				if(e.name().equalsIgnoreCase(s)) {
					return e;
				}
			}
		}
		return PbShotTypeEnumType.UNKNOWN;
	}

	// ─────────────────────────────── promote (design §7.2 rule 4) ───────────────────────────────

	/** {@code olio.item.type} of an item promoted from a scene element; the world catalogue rows are {@code "template"}. */
	public static final String ITEM_TYPE_PROP = "prop";
	public static final int ITEM_NAME_MAX = 64;
	public static final int ITEM_DESCRIPTION_MAX = 512;
	static final String[] ITEM_TEXT_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
		FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_MATERIALS,
		OlioFieldNames.FIELD_COLOR, OlioFieldNames.FIELD_COMPLEMENT_COLOR, OlioFieldNames.FIELD_ACCENT_COLOR
	};

	/** Outcome of one {@link #promoteElement}. Public plain fields so Service7 can shape its DTO from it. */
	public static final class PromoteResult {
		public BaseRecord element;
		public BaseRecord item;
		public BaseRecord scene;
		/** The element now references an existing {@code olio.item} row. */
		public boolean promoted;
		/** A new item row was written for this call. */
		public boolean created;
		/** The element was linked to an item that already existed (store match, same-name prop, or an earlier promote). */
		public boolean reused;
		/** {@link #elementText(BaseRecord, BaseRecord)} of the element after the link, from the freshly loaded item. */
		public String elementText;
		/** 1 when the scene's node was marked STALE by this call, else 0. */
		public int staled;
		public final List<String> warnings = new ArrayList<>();
	}

	/**
	 * Promote one scene element to an {@code olio.item} held by the scene's location (design §7.2 rule 4):
	 * the item is created as the olio principal <b>after</b> the caller has been authorized to update the
	 * book, enrolled in the location's POI {@code store.items} as the caller through
	 * {@code AccessPoint.member} (never the bare {@code MemberUtil} write), and {@code sceneElement.item} is
	 * set by a row-level patch as the caller.
	 * <p>
	 * Order: (1) book update authorization, (2) world context, (3) composition belongs to the book else 404,
	 * (4) element belongs to the composition else 404, (5) idempotent when the element already links an
	 * item whose row still exists, (6) scene, (7) location = the composition's else the scene's, and its
	 * POI store - none is a 409 (no orphan item is ever written), (8) {@code canUpdate(user, store)} before
	 * any write else 403, (9) reuse the store item the description names, (10) else reuse a same-name
	 * {@code type=prop} item of the world Items group, else create one as the olio principal, (11) enrol,
	 * (12) link, (13) mark the scene node stale ({@code sceneElement.item} is a watched field).
	 * <p>
	 * Self-heal: when step 11 or 12 fails after a create, the item row stays in the Items group as a
	 * {@code type=prop} orphan and the next promote of the same element finds it by name at step 10
	 * instead of writing a second one.
	 */
	public static PromoteResult promoteElement(BaseRecord user, BaseRecord book, String compositionObjectId, String elementObjectId) {
		BaseRecord fresh = PbBookUtil.requireBookUpdate(user, book, "promote an element");
		long orgId = ((Number) fresh.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		if(compositionObjectId == null || compositionObjectId.isBlank()) {
			throw new PictureBookException(400, "A composition objectId is required");
		}
		if(elementObjectId == null || elementObjectId.isBlank()) {
			throw new PictureBookException(400, "An element objectId is required");
		}
		BookContext bctx = PbLocationUtil.requireBookContext(user, fresh);

		BaseRecord comp = readComposition(user, compositionObjectId, orgId);
		if(comp == null || PbHealthUtil.fkId(comp, OlioFieldNames.FIELD_PB_BOOK) != PbHealthUtil.idOf(fresh)) {
			throw new PictureBookException(404, "Composition " + compositionObjectId + " not found in this book");
		}
		BaseRecord element = readElement(user, elementObjectId, orgId);
		if(element == null || PbHealthUtil.fkId(element, OlioFieldNames.FIELD_PB_COMPOSITION) != PbHealthUtil.idOf(comp)) {
			throw new PictureBookException(404, "Element " + elementObjectId + " not found in this composition");
		}
		PromoteResult result = new PromoteResult();
		result.element = element;
		String elementOid = str(element.get(FieldNames.FIELD_OBJECT_ID));
		String description = str(element.get(FieldNames.FIELD_DESCRIPTION));

		long linkedId = PbHealthUtil.fkId(element, OlioFieldNames.FIELD_ITEM);
		if(linkedId > 0L) {
			if(itemExists(linkedId, orgId)) {
				result.item = readItemById(user, linkedId, orgId);
				result.promoted = true;
				result.reused = true;
				result.elementText = elementText(user, element);
				return result;
			}
			result.warnings.add("Element " + elementOid + " linked item #" + linkedId + " which no longer exists; re-promoting");
		}

		String sceneOid = fkObjectId(comp, OlioFieldNames.FIELD_PB_SCENE);
		BaseRecord scene = (sceneOid != null ? PbBookUtil.readScene(user, sceneOid, orgId) : null);
		if(scene == null) {
			result.warnings.add("Composition " + compositionObjectId + " has no readable scene; the scene node cannot be marked stale");
		}
		result.scene = scene;

		BaseRecord locRef = locationRefOf(comp, scene);
		if(locRef == null) {
			throw new PictureBookException(409, "Composition " + compositionObjectId + " has no location to hold the item");
		}
		/// Same-world rule of P3-4 (PbLocationUtil.requireLocationOfBook): a location of another world is "not found" here
		BaseRecord loc = PbLocationUtil.readLocation(user, str(locRef.get(FieldNames.FIELD_OBJECT_ID)), orgId);
		if(loc == null || !PbLocationUtil.sameWorld(fresh, loc)) {
			throw new PictureBookException(404, "Location not found in this book's world");
		}
		BaseRecord storeRef = poiStoreOfLocation(user, loc, orgId);
		BaseRecord store = (storeRef != null ? readStoreById(user, PbHealthUtil.idOf(storeRef), orgId) : null);
		if(store == null) {
			String locName = str(loc.get(FieldNames.FIELD_NAME));
			throw new PictureBookException(409, "Location " + (locName != null ? "'" + locName + "'" : str(loc.get(FieldNames.FIELD_OBJECT_ID)))
				+ " has no point of interest store to hold the item");
		}
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, store);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not authorized to update the location's store");
		}

		BaseRecord item = null;
		boolean enrol = true;
		try {
			item = matchItem(description, storeItems(user, store, orgId));
		}
		catch(IndexException | ReaderException e) {
			logger.error("promoteElement " + elementOid + ": could not read the store's items: " + e.getMessage(), e);
			throw new PictureBookException(500, "Could not read the location's items");
		}
		if(item != null) {
			result.reused = true;
			enrol = false;
		}
		else {
			String name = itemNameOf(description);
			if(name == null) {
				throw new PictureBookException(409, "Element " + elementOid + " has no description to name an item from");
			}
			BaseRecord itemsGroup = bctx.getGroup(OlioFieldNames.FIELD_ITEMS);
			String itemsPath = bctx.getGroupPath(OlioFieldNames.FIELD_ITEMS);
			if(itemsGroup == null || itemsPath == null) {
				throw new PictureBookException(409, "Book " + fresh.get(OlioFieldNames.FIELD_PB_SLUG) + " has no world Items group");
			}
			item = findPropByName(user, name, PbHealthUtil.idOf(itemsGroup), orgId);
			if(item != null) {
				result.reused = true;
			}
			else {
				item = createProp(user, name, description, itemsPath, orgId);
				result.created = true;
			}
		}
		String itemName = str(item.get(FieldNames.FIELD_NAME));
		if(enrol) {
			/// AccessPoint.member: canRead(user, item) + canUpdate(user, store), audited - never the bare MemberUtil write
			if(!IOSystem.getActiveContext().getAccessPoint().member(user, store, OlioFieldNames.FIELD_ITEMS, item, null, true)) {
				throw new PictureBookException(500, "Item '" + itemName + "' was " + (result.created ? "created" : "found")
					+ " but could not be added to the location's store");
			}
		}

		BaseRecord patch = PbGraphUtil.patchOf(element, OlioModelNames.MODEL_PB_SCENE_ELEMENT, OlioFieldNames.FIELD_ITEM);
		setQuiet(patch, OlioFieldNames.FIELD_ITEM, item);
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to link element " + elementOid + " to item '" + itemName + "'");
		}
		PbStaleUtil.touch(element);
		result.item = item;
		result.promoted = true;
		if(scene != null && PbBookUtil.markSceneNodeStale(user, scene)) {
			result.staled = 1;
		}
		setQuiet(element, OlioFieldNames.FIELD_ITEM, item);
		result.elementText = elementText(user, element);
		return result;
	}

	/**
	 * The text the render plan carries for one element: from the freshly loaded {@code olio.item} when the
	 * element is promoted ({@code "<colour> <materials> <name>, <description> with <complement> and <accent>
	 * accents"}), else the element's own description when meaningful, else {@code ""}. Never the literal
	 * {@code "null"}.
	 */
	public static String elementText(BaseRecord user, BaseRecord element) {
		if(element == null) {
			return "";
		}
		String description = str(element.hasField(FieldNames.FIELD_DESCRIPTION) ? element.get(FieldNames.FIELD_DESCRIPTION) : null);
		long itemId = PbHealthUtil.fkId(element, OlioFieldNames.FIELD_ITEM);
		if(itemId > 0L && user != null) {
			BaseRecord item = readItemById(user, itemId, PbGraphUtil.orgId(element));
			if(item != null) {
				String text = itemText(str(item.get(FieldNames.FIELD_NAME)), str(item.get(FieldNames.FIELD_DESCRIPTION)),
					colourNames(item), item.hasField(OlioFieldNames.FIELD_MATERIALS) ? item.get(OlioFieldNames.FIELD_MATERIALS) : null);
				if(!text.isEmpty()) {
					return text;
				}
			}
		}
		return (NarrativeUtil.isMeaningful(description) ? tidy(description) : "");
	}

	/**
	 * Pure composition of an item's prompt text (no I/O): {@code colours[0] + materials + name}, then
	 * {@code ", " + description} when meaningful and not just the name again, then
	 * {@code " with <c1> and <c2> accents"} for any further colours. Lower-cased, parentheticals removed,
	 * whitespace collapsed; {@code ""} when the name is not meaningful.
	 */
	static String itemText(String name, String description, List<String> colourNames, List<String> materials) {
		if(!NarrativeUtil.isMeaningful(name)) {
			return "";
		}
		List<String> colours = new ArrayList<>();
		if(colourNames != null) {
			for(String c : colourNames) {
				if(NarrativeUtil.isMeaningful(c)) {
					colours.add(tidy(c));
				}
			}
		}
		List<String> mats = new ArrayList<>();
		if(materials != null) {
			for(String m : materials) {
				if(NarrativeUtil.isMeaningful(m)) {
					mats.add(tidy(m));
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		if(!colours.isEmpty()) {
			sb.append(colours.get(0)).append(' ');
		}
		if(!mats.isEmpty()) {
			sb.append(String.join(" and ", mats)).append(' ');
		}
		String tname = tidy(name);
		sb.append(tname);
		if(NarrativeUtil.isMeaningful(description)) {
			String tdesc = tidy(description);
			// the prop's name is its description minus the article, so compare both sides bare
			if(!tdesc.isEmpty() && !tdesc.equals(tname) && !bare(tdesc).equals(bare(tname))) {
				sb.append(", ").append(tdesc);
			}
		}
		if(colours.size() > 1) {
			sb.append(" with ").append(String.join(" and ", colours.subList(1, colours.size()))).append(" accents");
		}
		return sb.toString().replaceAll("\\s+", " ").trim();
	}

	/// Item name from an element description: trimmed, whitespace collapsed, leading article dropped,
	/// lower-cased, capped at ITEM_NAME_MAX; null when nothing meaningful remains.
	static String itemNameOf(String description) {
		if(!NarrativeUtil.isMeaningful(description)) {
			return null;
		}
		String s = bare(tidy(description));
		if(s.length() > ITEM_NAME_MAX) {
			s = s.substring(0, ITEM_NAME_MAX).trim();
		}
		return (s.isEmpty() ? null : s);
	}

	private static String bare(String tidied) {
		return tidied.replaceFirst("^(?:a|an|the|some)\\s+", "");
	}

	private static String tidy(String s) {
		if(s == null) {
			return "";
		}
		return s.replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	private static List<String> colourNames(BaseRecord item) {
		List<String> names = new ArrayList<>();
		for(String f : new String[] { OlioFieldNames.FIELD_COLOR, OlioFieldNames.FIELD_COMPLEMENT_COLOR, OlioFieldNames.FIELD_ACCENT_COLOR }) {
			BaseRecord c = (item.hasField(f) ? item.get(f) : null);
			String n = (c != null && c.hasField(FieldNames.FIELD_NAME) ? str(c.get(FieldNames.FIELD_NAME)) : null);
			names.add(n);
		}
		return names;
	}

	/// The composition's own location ref when it carries an objectId, else the scene's; null when neither does.
	private static BaseRecord locationRefOf(BaseRecord comp, BaseRecord scene) {
		BaseRecord ref = (comp.hasField(FieldNames.FIELD_LOCATION) ? comp.get(FieldNames.FIELD_LOCATION) : null);
		if(ref != null && str(ref.get(FieldNames.FIELD_OBJECT_ID)) != null) {
			return ref;
		}
		ref = (scene != null && scene.hasField(FieldNames.FIELD_LOCATION) ? scene.get(FieldNames.FIELD_LOCATION) : null);
		return (ref != null && str(ref.get(FieldNames.FIELD_OBJECT_ID)) != null ? ref : null);
	}

	private static String fkObjectId(BaseRecord rec, String field) {
		BaseRecord fk = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (fk != null ? str(fk.get(FieldNames.FIELD_OBJECT_ID)) : null);
	}

	/// The store re-read as the caller with just what PBAC needs; null when unreadable.
	private static BaseRecord readStoreById(BaseRecord user, long storeId, long orgId) {
		if(storeId <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_STORE, FieldNames.FIELD_ID, storeId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Existence only (no PBAC): a deleted FK target reads back as an id-only stub, so "linked" means the row is still there.
	private static boolean itemExists(long itemId, long orgId) {
		if(itemId <= 0L) {
			return false;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_ID, itemId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q) != null;
	}

	/// The item with its text fields and colour names, read as the caller; null when absent or unreadable.
	static BaseRecord readItemById(BaseRecord user, long itemId, long orgId) {
		if(user == null || itemId <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_ID, itemId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(ITEM_TEXT_REQUEST);
		q.planField(OlioFieldNames.FIELD_COLOR, new String[] { FieldNames.FIELD_NAME });
		q.planField(OlioFieldNames.FIELD_COMPLEMENT_COLOR, new String[] { FieldNames.FIELD_NAME });
		q.planField(OlioFieldNames.FIELD_ACCENT_COLOR, new String[] { FieldNames.FIELD_NAME });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// A same-name type=prop item of the world Items group, read as the caller with an explicit groupId
	/// (the group-only PBAC shortcut). Catalogue rows are type=template and are never reused here.
	private static BaseRecord findPropByName(BaseRecord user, String name, long itemsGroupId, long orgId) {
		if(itemsGroupId <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, itemsGroupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_TYPE, ITEM_TYPE_PROP);
		q.setRequest(ITEM_TEXT_REQUEST);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// Writes the item as the olio principal (the book has already authorized the caller for update) and
	/// re-reads it as the caller so the returned record is what the caller is allowed to see.
	private static BaseRecord createProp(BaseRecord user, String name, String description, String itemsPath, long orgId) {
		BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal in organization " + orgId);
		}
		BaseRecord item;
		try {
			item = RecordFactory.newInstance(OlioModelNames.MODEL_ITEM);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(olioUser, item, name, itemsPath, orgId);
			item.set(FieldNames.FIELD_NAME, name);
			item.set(FieldNames.FIELD_TYPE, ITEM_TYPE_PROP);
			item.set(FieldNames.FIELD_DESCRIPTION, cap(description, ITEM_DESCRIPTION_MAX));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble item '" + name + "': " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble item '" + name + "'");
		}
		if(!IOSystem.getActiveContext().getRecordUtil().createRecord(item)) {
			throw new PictureBookException(500, "Failed to create item '" + name + "'");
		}
		BaseRecord readBack = readItemById(user, PbHealthUtil.idOf(item), orgId);
		if(readBack == null) {
			logger.error("Item '" + name + "' (#" + PbHealthUtil.idOf(item) + ") was created in " + itemsPath + " but is not readable by "
				+ user.get(FieldNames.FIELD_NAME));
			throw new PictureBookException(500, "Item '" + name + "' was created but is not readable by the caller");
		}
		return readBack;
	}

	// ─────────────────────────────── small helpers ───────────────────────────────

	private static void setQuiet(BaseRecord rec, String field, Object value) {
		try {
			rec.set(field, value);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set " + rec.getSchema() + "." + field + ": " + e.getMessage());
		}
	}

	private static PbCompositionSourceEnumType sourceOf(BaseRecord comp) {
		String s = str(comp.get(OlioFieldNames.FIELD_PB_SOURCE));
		if(s == null) {
			return PbCompositionSourceEnumType.UNKNOWN;
		}
		for(PbCompositionSourceEnumType e : PbCompositionSourceEnumType.values()) {
			if(e.toString().equalsIgnoreCase(s)) {
				return e;
			}
		}
		return PbCompositionSourceEnumType.UNKNOWN;
	}

	private static String firstMeaningful(String... vals) {
		for(String v : vals) {
			if(NarrativeUtil.isMeaningful(v)) {
				return v.trim();
			}
		}
		return null;
	}

	private static String cap(String s, int max) {
		if(s == null) {
			return null;
		}
		String t = s.trim();
		return (t.length() > max ? t.substring(0, max).trim() : t);
	}

	private static String orNone(String s) {
		return (NarrativeUtil.isMeaningful(s) ? s.trim() : NONE);
	}

	private static String str(Object v) {
		return (v == null ? null : v.toString());
	}
}
