package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Cast;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.CastTier;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Context;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Proposal;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Staging;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;

/**
 * PictureBook V3 Phase 9 (P9-1): the cover-type compositions of a book - {@code COVER}, {@code TITLE_PAGE},
 * {@code CHAPTER_OPENER} (one per book, named {@code cover.<bookOid>} etc.) and {@code SERIES_ART} (one per
 * series, {@code series.<seriesOid>} FK set, hosted in the group of the chapter it was first made from).
 * They are ordinary {@code olio.pb.composition} rows with {@code scene} null, so the row editor, the actor /
 * element rows and {@link PbComposeUtil#patchCompositionFields} work on them unchanged, and
 * {@code PbDeleteUtil.deleteGraphByBook} deletes them with the book.
 * <p>
 * {@link #getCreate} is get-or-create: an existing row is returned untouched ({@code existingReturned}),
 * never recomposed - a cover is edited through the row editor or deleted and made again. A new row is
 * written in one of three {@link Mode}s: {@link Mode#AUTO} stages it with the LLM from the book's title,
 * description, hero cast and locations ({@code pictureBook.compose-cover}); {@link Mode#FROM_SCENE} clones a
 * SCENE composition's fields and rows ({@code source CLONE}); {@link Mode#MANUAL} writes an empty row
 * ({@code source USER}) for the user to fill in. {@link #findCover} / {@link #listCovers} are read-only.
 * <p>
 * Everything runs as the caller through {@code AccessPoint}; the only writes are the composition and its
 * rows, in the book's group, after {@link PbBookUtil#requireBookUpdate} (403 as "create cover art").
 */
public class PbCoverUtil {

	public static final Logger logger = LogManager.getLogger(PbCoverUtil.class);

	public static final String COVER_NAME_PREFIX = "cover.";
	public static final String TITLE_PAGE_NAME_PREFIX = "title_page.";
	public static final String CHAPTER_OPENER_NAME_PREFIX = "chapter_opener.";
	public static final String SERIES_ART_NAME_PREFIX = "series_art.";

	/** The {@code what} of the 403 from {@link PbBookUtil#requireBookUpdate}. */
	static final String WHAT = "create cover art";

	/** How a cover-type composition is first written. */
	public enum Mode {
		/** Stage it with the LLM from the book's title, description, hero cast and locations ({@code source LLM}). */
		AUTO,
		/** Clone a SCENE composition's fields and rows ({@code source CLONE}, {@code revision 0}). */
		FROM_SCENE,
		/** An empty composition for the user to fill in ({@code source USER}). */
		MANUAL
	}

	/** Outcome of one {@link #getCreate}. Fields are public and plain so Service7 can serialize it as-is. */
	public static final class CoverResult {
		public BaseRecord composition;
		public List<BaseRecord> actors = new ArrayList<>();
		public List<BaseRecord> elements = new ArrayList<>();
		public PbCompositionPurposeEnumType purpose;
		public Mode mode;
		/** True when this call wrote the composition. */
		public boolean created;
		/** True when a composition of this purpose already existed and was returned untouched. */
		public boolean existingReturned;
		public PbCompositionSourceEnumType source = PbCompositionSourceEnumType.UNKNOWN;
		/** AUTO only: where the HERO CAST block came from. */
		public CastTier castTier = CastTier.NONE;
		/** AUTO only: the names offered as the hero cast, in prompt order. */
		public List<String> heroCast = new ArrayList<>();
		/** AUTO only: the LLM returned nothing usable; nothing was written. */
		public boolean llmFailure;
		public boolean hardFailure;
		public String failureReason;
		public List<String> warnings = new ArrayList<>();
		public List<String> unresolvedNames = new ArrayList<>();
		public List<String> unrecognizedPlacements = new ArrayList<>();
		public String locationHint;
		/** FROM_SCENE only: the scene whose composition was cloned. */
		public String sourceSceneObjectId;
		public long elapsedMs;

		/** This call wrote a complete composition. */
		public boolean isWritten() {
			return created && composition != null && !hardFailure;
		}
	}

	// ─────────────────────────────── naming / words ───────────────────────────────

	public static boolean isCoverPurpose(PbCompositionPurposeEnumType purpose) {
		return purpose == PbCompositionPurposeEnumType.COVER || purpose == PbCompositionPurposeEnumType.TITLE_PAGE
			|| purpose == PbCompositionPurposeEnumType.CHAPTER_OPENER || purpose == PbCompositionPurposeEnumType.SERIES_ART;
	}

	static PbCompositionPurposeEnumType requireCoverPurpose(PbCompositionPurposeEnumType purpose) {
		if(!isCoverPurpose(purpose)) {
			throw new PictureBookException(400, "Purpose must be one of COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART");
		}
		return purpose;
	}

	/** A route word ({@code cover}, {@code title-page}, {@code series_art}, ...) as a cover purpose; 400 otherwise. */
	public static PbCompositionPurposeEnumType purposeOf(String word) {
		if(word == null || word.isBlank()) {
			throw new PictureBookException(400, "Purpose must be one of COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART");
		}
		String norm = word.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for(PbCompositionPurposeEnumType p : PbCompositionPurposeEnumType.values()) {
			if(p.toString().equals(norm)) {
				return requireCoverPurpose(p);
			}
		}
		throw new PictureBookException(400, "Purpose must be one of COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART");
	}

	/** A request word as a {@link Mode}; null / blank is {@link Mode#AUTO}, anything else unknown is 400. */
	public static Mode modeOf(String word) {
		if(word == null || word.isBlank()) {
			return Mode.AUTO;
		}
		switch(word.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
			case "auto":
			case "llm":
				return Mode.AUTO;
			case "from_scene":
			case "fromscene":
			case "scene":
			case "clone":
				return Mode.FROM_SCENE;
			case "manual":
			case "empty":
				return Mode.MANUAL;
			default:
				throw new PictureBookException(400, "Mode must be one of auto, from_scene, manual");
		}
	}

	/** The deterministic row name: {@code cover.<bookOid>} etc., {@code series_art.<seriesOid>} for SERIES_ART. */
	public static String coverName(PbCompositionPurposeEnumType purpose, String bookObjectId, String seriesObjectId) {
		switch(requireCoverPurpose(purpose)) {
			case COVER:
				return COVER_NAME_PREFIX + bookObjectId;
			case TITLE_PAGE:
				return TITLE_PAGE_NAME_PREFIX + bookObjectId;
			case CHAPTER_OPENER:
				return CHAPTER_OPENER_NAME_PREFIX + bookObjectId;
			default:
				return SERIES_ART_NAME_PREFIX + seriesObjectId;
		}
	}

	/** The prompt's PURPOSE word: {@code cover}, {@code title page}, {@code chapter opener}, {@code series art}. */
	static String purposeWord(PbCompositionPurposeEnumType purpose) {
		return purpose.toString().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/**
	 * The book's composition of a cover {@code purpose} as {@code user} reads it, or null. Read-only, uncached.
	 * COVER / TITLE_PAGE / CHAPTER_OPENER are keyed by name in the book's group; SERIES_ART is keyed by the
	 * book's {@code series} FK across the organization (the row lives in whichever chapter's group it was first
	 * made in, and every chapter sees that same one - as far as the caller may read it, which follows the host
	 * chapter's grants, §6.C 237 j), null when the book is not a chapter. Duplicates resolve to the lowest id
	 * with a warning.
	 *
	 * @throws PictureBookException 400 for a non-cover purpose, 409 when the book record carries no group
	 */
	public static BaseRecord findCover(BaseRecord user, BaseRecord book, PbCompositionPurposeEnumType purpose) {
		requireCoverPurpose(purpose);
		if(user == null || book == null) {
			return null;
		}
		long orgId = PbGraphUtil.orgId(book);
		if(purpose == PbCompositionPurposeEnumType.SERIES_ART) {
			BaseRecord seriesRef = (book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
			return (seriesRef != null ? findSeriesArt(user, seriesRef, orgId) : null);
		}
		Long gid = book.get(FieldNames.FIELD_GROUP_ID);
		if(gid == null || gid.longValue() <= 0L) {
			throw new PictureBookException(409, "Book " + PbComposeUtil.str(book.get(OlioFieldNames.FIELD_PB_SLUG)) + " has no group");
		}
		String name = coverName(purpose, PbComposeUtil.str(book.get(FieldNames.FIELD_OBJECT_ID)), null);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, gid.longValue());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		return lowestOfPurpose(user, q, purpose, name);
	}

	/// The series' SERIES_ART row as {@code user} may read it: null when there is none, and null when there is one
	/// the user is not entitled to (§6.C 237 j) - a read path never tells the two apart.
	static BaseRecord findSeriesArt(BaseRecord user, BaseRecord seriesRef, long orgId) {
		SeriesArtLookup look = lookupSeriesArt(user, seriesRef, orgId);
		return (look.denied ? null : look.row);
	}

	/** Outcome of {@link #lookupSeriesArt}: the row the series has, and whether the caller may read it. */
	static final class SeriesArtLookup {
		/** The row as the caller reads it; null when the series has none or the caller is not entitled. */
		BaseRecord row;
		/** True when a row exists that the caller cannot read. */
		boolean denied;
	}

	/// SERIES_ART by the series FK + organizationId, no group: AccessPoint.list is not a per-record boundary
	/// (model-api.md), which is how a sibling chapter finds the row hosted in another chapter's group - and also
	/// why the list alone leaked it to any member of the sibling (§6.C 237 j). The hit is therefore re-read as
	/// the caller through AccessPoint.find, whose per-record canRead decides: visibility of the shared row follows
	/// the grants on the host chapter's group, never the calling chapter's.
	static SeriesArtLookup lookupSeriesArt(BaseRecord user, BaseRecord seriesRef, long orgId) {
		SeriesArtLookup look = new SeriesArtLookup();
		if(user == null || seriesRef == null) {
			return look;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_SERIES, seriesRef);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		String label = "series art of series " + PbHealthUtil.idOf(seriesRef);
		BaseRecord hit = lowestOfPurpose(user, q, PbCompositionPurposeEnumType.SERIES_ART, label);
		if(hit == null) {
			return look;
		}
		String oid = PbComposeUtil.str(hit.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord asUser = PbComposeUtil.readComposition(user, oid, orgId);
		if(asUser == null) {
			look.denied = true;
			logger.warn(label + ": row " + hit.get(FieldNames.FIELD_ID) + " (group " + hit.get(FieldNames.FIELD_GROUP_ID)
				+ ") is not readable by user " + user.get(FieldNames.FIELD_ID) + "; treated as absent");
			return look;
		}
		look.row = asUser;
		return look;
	}

	private static BaseRecord lowestOfPurpose(BaseRecord user, Query q, PbCompositionPurposeEnumType purpose, String label) {
		q.setRequest(PbComposeUtil.compositionRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		if(recs == null || recs.length == 0) {
			return null;
		}
		BaseRecord hit = null;
		int hits = 0;
		for(BaseRecord c : PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_COMPOSITION)) {
			if(!purpose.toString().equalsIgnoreCase(PbComposeUtil.str(c.get(OlioFieldNames.FIELD_PB_PURPOSE)))) {
				continue;
			}
			hits++;
			if(hit == null) {
				hit = c;
			}
		}
		if(hits > 1) {
			logger.warn(label + " has " + hits + " " + purpose + " compositions; using the lowest id " + hit.get(FieldNames.FIELD_ID));
		}
		return hit;
	}

	/**
	 * Every cover-type composition the book has, keyed by purpose in enum order: the book-level three from the
	 * book's group, SERIES_ART through the series FK when the book is a chapter. Read-only, uncached.
	 *
	 * @throws PictureBookException 409 when the book record carries no group
	 */
	public static Map<PbCompositionPurposeEnumType, BaseRecord> listCovers(BaseRecord user, BaseRecord book) {
		Map<PbCompositionPurposeEnumType, BaseRecord> out = new LinkedHashMap<>();
		if(user == null || book == null) {
			return out;
		}
		Long gid = book.get(FieldNames.FIELD_GROUP_ID);
		if(gid == null || gid.longValue() <= 0L) {
			throw new PictureBookException(409, "Book " + PbComposeUtil.str(book.get(OlioFieldNames.FIELD_PB_SLUG)) + " has no group");
		}
		long orgId = PbGraphUtil.orgId(book);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_BOOK, book);
		q.field(FieldNames.FIELD_GROUP_ID, gid.longValue());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbComposeUtil.compositionRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		Map<PbCompositionPurposeEnumType, BaseRecord> found = new HashMap<>();
		if(recs != null && recs.length > 0) {
			for(BaseRecord c : PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_COMPOSITION)) {
				PbCompositionPurposeEnumType p = purposeOfRow(c);
				// SERIES_ART is keyed on the series, not the book: resolved below so a sibling-hosted row is seen too
				if(p != null && isCoverPurpose(p) && p != PbCompositionPurposeEnumType.SERIES_ART) {
					found.putIfAbsent(p, c);
				}
			}
		}
		BaseRecord seriesRef = (book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
		if(seriesRef != null) {
			BaseRecord art = findSeriesArt(user, seriesRef, orgId);
			if(art != null) {
				found.put(PbCompositionPurposeEnumType.SERIES_ART, art);
			}
		}
		for(PbCompositionPurposeEnumType p : PbCompositionPurposeEnumType.values()) {
			if(found.containsKey(p)) {
				out.put(p, found.get(p));
			}
		}
		return out;
	}

	/** The row's {@code purpose} as the enum (any case), or null when unset / not a known purpose. */
	static PbCompositionPurposeEnumType purposeOfRow(BaseRecord comp) {
		String word = (comp == null || !comp.hasField(OlioFieldNames.FIELD_PB_PURPOSE) ? null : PbComposeUtil.str(comp.get(OlioFieldNames.FIELD_PB_PURPOSE)));
		if(word == null) {
			return null;
		}
		for(PbCompositionPurposeEnumType p : PbCompositionPurposeEnumType.values()) {
			if(p.toString().equalsIgnoreCase(word)) {
				return p;
			}
		}
		return null;
	}

	// ─────────────────────────────── get-or-create ───────────────────────────────

	public static CoverResult getCreate(BaseRecord user, BaseRecord book, PbCompositionPurposeEnumType purpose, Mode mode, String sceneObjectId) {
		return getCreate(user, book, purpose, mode, sceneObjectId, null, null);
	}

	/**
	 * The book's composition of {@code purpose}, written now in {@code mode} when it does not exist yet.
	 *
	 * @param sceneObjectId FROM_SCENE only: the scene whose SCENE composition is cloned
	 * @param chatConfig    AUTO only: the chat config for the LLM call; null = the organization's {@code generalChat}
	 * @param dataPath      the deployment's {@code datagen.path} when the caller has one (wardrobe alternates)
	 * @throws PictureBookException 400 non-cover purpose / bad scene; 403 from {@link PbBookUtil#requireBookUpdate};
	 *                              404 scene or its composition missing, or a SERIES_ART row exists that the caller
	 *                              may not read (worded as "has no SERIES_ART composition", §6.C 237 j);
	 *                              409 SERIES_ART on a book that is not a chapter; 500 when the row cannot be written
	 */
	public static CoverResult getCreate(BaseRecord user, BaseRecord book, PbCompositionPurposeEnumType purpose, Mode mode,
			String sceneObjectId, BaseRecord chatConfig, String dataPath) {
		long t0 = System.currentTimeMillis();
		CoverResult r = new CoverResult();
		r.purpose = requireCoverPurpose(purpose);
		r.mode = (mode != null ? mode : Mode.AUTO);
		Context ctx = PbComposeUtil.openBookContext(user, book, WHAT);
		ctx.purpose = purpose;
		ctx.chatConfig = chatConfig;
		ctx.dataPath = dataPath;
		BaseRecord series = (purpose == PbCompositionPurposeEnumType.SERIES_ART ? requireSeries(user, ctx) : null);
		BaseRecord existing = (series != null ? requireReadableSeriesArt(user, ctx, series) : findCover(user, ctx.book, purpose));
		if(existing != null) {
			return existing(user, existing, r, t0);
		}
		try {
			switch(r.mode) {
				case FROM_SCENE:
					cloneFromScene(user, ctx, series, sceneObjectId, r);
					break;
				case MANUAL:
					createManual(user, ctx, series, r);
					break;
				default:
					composeAuto(user, ctx, series, r);
					break;
			}
		}
		catch(PictureBookException e) {
			// A concurrent creator may have won the (name, group) constraint: the row is there, return it.
			BaseRecord raced = (e.getStatus() == 500
				? (series != null ? findSeriesArt(user, series, ctx.orgId) : findCover(user, ctx.book, purpose)) : null);
			if(raced != null) {
				logger.warn("compose-cover " + ctx.label() + ": " + e.getMessage() + "; a " + purpose + " row now exists, returning it");
				return existing(user, raced, r, t0);
			}
			throw e;
		}
		r.elapsedMs = System.currentTimeMillis() - t0;
		logger.info("compose-cover " + ctx.label() + ": mode=" + r.mode + " created=" + r.created + " source=" + r.source
			+ " tier=" + r.castTier + " hard=" + r.hardFailure + " actors=" + r.actors.size() + " elements=" + r.elements.size()
			+ " ms=" + r.elapsedMs);
		return r;
	}

	private static CoverResult existing(BaseRecord user, BaseRecord existing, CoverResult r, long t0) {
		r.composition = existing;
		r.existingReturned = true;
		r.source = PbComposeUtil.sourceOf(existing);
		r.actors = PbComposeUtil.listActors(user, existing);
		r.elements = PbComposeUtil.listElements(user, existing);
		r.elapsedMs = System.currentTimeMillis() - t0;
		return r;
	}

	/// The series' existing SERIES_ART row for get-or-create, or null when the series has none yet. A row the caller
	/// cannot read is a 404 worded exactly like the "no row" 404 of the read routes (§6.C 237 j): the row is never
	/// returned, and the create branch is never reached - the (name, groupId) index is per group, so creating would
	/// write a second series_art.<seriesOid> in this chapter's group rather than fail.
	private static BaseRecord requireReadableSeriesArt(BaseRecord user, Context ctx, BaseRecord series) {
		SeriesArtLookup look = lookupSeriesArt(user, series, ctx.orgId);
		if(look.denied) {
			throw new PictureBookException(404, "Book " + ctx.slug + " has no SERIES_ART composition");
		}
		return look.row;
	}

	/// The series a SERIES_ART composition is for: the chapter's {@code series} FK read as the caller.
	static BaseRecord requireSeries(BaseRecord user, Context ctx) {
		BaseRecord ref = (ctx.book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? ctx.book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
		if(ref == null) {
			throw new PictureBookException(409, "Book " + ctx.slug + " is not a chapter of a series; SERIES_ART needs one");
		}
		BaseRecord series = readSeries(user, ref, ctx.orgId);
		if(series == null) {
			throw new PictureBookException(404, "The series of book " + ctx.slug + " is not readable");
		}
		return series;
	}

	/// The series row behind a book's FK, by objectId when the FK carries one, else by id.
	private static BaseRecord readSeries(BaseRecord user, BaseRecord ref, long orgId) {
		String oid = (ref.hasField(FieldNames.FIELD_OBJECT_ID) ? PbComposeUtil.str(ref.get(FieldNames.FIELD_OBJECT_ID)) : null);
		if(oid != null) {
			return PbSeriesUtil.readSeries(user, oid, orgId);
		}
		long id = PbHealthUtil.idOf(ref);
		if(id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbSeriesUtil.seriesRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── AUTO ───────────────────────────────

	/// Title + description + hero cast + locations -> pictureBook.compose-cover -> PbComposeUtil.stage -> rows.
	/// An LLM failure (unavailable, empty, unparseable twice) writes nothing: hardFailure + llmFailure on the result.
	private static void composeAuto(BaseRecord user, Context ctx, BaseRecord series, CoverResult r) {
		ComposeResult aux = new ComposeResult();
		BookTally tally = tallyBook(user, ctx, aux);
		Cast cast = coverCast(user, ctx, series, tally, aux, r);
		r.castTier = cast.tier;
		for(BaseRecord cp : cast.members) {
			r.heroCast.add(PbComposeUtil.str(cp.get(FieldNames.FIELD_NAME)));
		}
		Map<String, String> vars = coverVariables(user, ctx, series, cast, tally, aux);
		boolean[] hard = new boolean[] { false };
		Proposal p = null;
		String reply = PictureBookUtil.callLlmForChapBook(user, ctx.chatConfig, PbComposeUtil.PROMPT_COMPOSE_COVER, vars, hard);
		if(reply != null && !reply.isBlank()) {
			p = PbComposeUtil.parseStagedProposal(reply, "reply", ctx, aux);
			if(p == null) {
				// The cover template has no ITEMS block; the retry hint rides on the last block instead.
				vars.put("locations", vars.get("locations") + PbComposeUtil.RETRY_HINT);
				reply = PictureBookUtil.callLlmForChapBook(user, ctx.chatConfig, PbComposeUtil.PROMPT_COMPOSE_COVER, vars, hard);
				if(reply != null && !reply.isBlank()) {
					p = PbComposeUtil.parseStagedProposal(reply, "retry", ctx, aux);
				}
			}
		}
		if(p == null) {
			copyNotes(aux, r);
			r.llmFailure = true;
			r.hardFailure = true;
			r.failureReason = (hard[0] ? "LLM unavailable" : (reply == null || reply.isBlank() ? "empty LLM reply" : "unparseable LLM reply"));
			logger.error("compose-cover " + ctx.label() + ": " + r.failureReason + "; nothing written");
			return;
		}
		if(p.isEmpty()) {
			aux.warnings.add("The reply staged no figures and no elements; the " + purposeWord(ctx.purpose) + " is setting and mood only");
		}
		Staging s = PbComposeUtil.stage(user, ctx, cast, p, aux);
		copyNotes(aux, r);
		BaseRecord comp = createCover(user, ctx, series, s.source, 1, s, null);
		writeRows(user, ctx, comp, s, r);
	}

	/** What one pass over the book's SCENE compositions yields for the cover prompt. */
	static final class BookTally {
		/** Characters cast as HERO, most frequent first, ties in first-seen order. */
		List<Long> heroIds = new ArrayList<>();
		/** The {@code location} FK most SCENE compositions share, or 0. */
		long topLocationId;
		int topLocationUses;
	}

	/// HERO rows and location FKs over the book's SCENE compositions in id order, read as the caller; a read
	/// failure leaves the tally empty with a warning - the cover is still composed from the population.
	static BookTally tallyBook(BaseRecord user, Context ctx, ComposeResult aux) {
		BookTally t = new BookTally();
		Map<Long, BaseRecord> comps;
		try {
			comps = PbComposeUtil.listSceneCompositions(user, ctx.book);
		}
		catch(RuntimeException e) {
			aux.warnings.add("Could not list the book's scene compositions: " + e.getMessage());
			logger.warn("compose-cover " + ctx.label() + ": scene composition list failed: " + e.getMessage());
			return t;
		}
		List<BaseRecord> ordered = new ArrayList<>(comps.values());
		ordered.sort((a, b) -> Long.compare(PbHealthUtil.idOf(a), PbHealthUtil.idOf(b)));
		Map<Long, Integer> heroCounts = new LinkedHashMap<>();
		Map<Long, Integer> locCounts = new LinkedHashMap<>();
		for(BaseRecord comp : ordered) {
			long locId = PbHealthUtil.fkId(comp, FieldNames.FIELD_LOCATION);
			if(locId > 0L) {
				locCounts.merge(locId, 1, Integer::sum);
			}
			List<BaseRecord> rows;
			try {
				rows = PbComposeUtil.listActors(user, comp);
			}
			catch(RuntimeException e) {
				aux.warnings.add("Could not list the actors of composition " + comp.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
				continue;
			}
			for(BaseRecord row : rows) {
				String role = PbComposeUtil.str(row.get(OlioFieldNames.FIELD_PB_ROLE));
				long charId = PbHealthUtil.fkId(row, OlioFieldNames.FIELD_PB_CHARACTER);
				if(charId > 0L && PbActorRoleEnumType.HERO.toString().equalsIgnoreCase(role)) {
					heroCounts.merge(charId, 1, Integer::sum);
				}
			}
		}
		t.heroIds.addAll(heroCounts.keySet());
		t.heroIds.sort((a, b) -> heroCounts.get(b).intValue() - heroCounts.get(a).intValue());
		for(Map.Entry<Long, Integer> e : locCounts.entrySet()) {
			if(e.getValue().intValue() > t.topLocationUses) {
				t.topLocationId = e.getKey().longValue();
				t.topLocationUses = e.getValue().intValue();
			}
		}
		return t;
	}

	/// (a) the book's heroes; (b) the series' baseline cast when the book is a chapter without hero rows;
	/// (c) the population, capped at MAX_POPULATION_CAST. Records come from the population read (the same
	/// projection castBlock describes), falling back to a by-id read for a hero cast from another group.
	static Cast coverCast(BaseRecord user, Context ctx, BaseRecord series, BookTally tally, ComposeResult aux, CoverResult r) {
		Cast cast = new Cast();
		List<BaseRecord> population = PbComposeUtil.populationMembers(user, ctx, aux);
		Map<Long, BaseRecord> byId = new HashMap<>();
		for(BaseRecord cp : population) {
			byId.put(Long.valueOf(PbHealthUtil.idOf(cp)), cp);
		}
		List<BaseRecord> heroes = resolveIds(user, ctx, tally.heroIds, byId, aux);
		if(!heroes.isEmpty()) {
			cast.members = heroes;
			cast.tier = CastTier.HEROES;
			return cast;
		}
		List<BaseRecord> baseline = baselineCast(user, ctx, series, byId, aux);
		if(!baseline.isEmpty()) {
			cast.members = baseline;
			cast.tier = CastTier.BASELINE_CAST;
			return cast;
		}
		if(population.isEmpty()) {
			aux.warnings.add("The book has no hero rows, no baseline cast and no population; composing without a cast");
			return cast;
		}
		if(population.size() > PbComposeUtil.MAX_POPULATION_CAST) {
			aux.warnings.add("The book has no hero rows; offering the first " + PbComposeUtil.MAX_POPULATION_CAST + " of "
				+ population.size() + " population members");
			population = new ArrayList<>(population.subList(0, PbComposeUtil.MAX_POPULATION_CAST));
		}
		cast.members = population;
		cast.tier = CastTier.POPULATION;
		return cast;
	}

	private static List<BaseRecord> resolveIds(BaseRecord user, Context ctx, List<Long> ids, Map<Long, BaseRecord> byId, ComposeResult aux) {
		List<BaseRecord> out = new ArrayList<>();
		for(Long id : ids) {
			if(out.size() >= PbComposeUtil.MAX_ACTORS) {
				break;
			}
			BaseRecord cp = byId.get(id);
			if(cp == null) {
				cp = PbWardrobeUtil.readCharacterById(user, id.longValue(), ctx.orgId);
			}
			if(cp == null) {
				aux.warnings.add("Hero character " + id + " is not readable; left out of the cast");
				continue;
			}
			out.add(cp);
		}
		return out;
	}

	/// The series' baseline cast group members (PbCastUtil), for a chapter of a series; empty for a standalone
	/// book or when the group / its members cannot be read. Scoped like PbServiceFacade.requireSeriesScope: the
	/// cast group lives in the series world's book group.
	static List<BaseRecord> baselineCast(BaseRecord user, Context ctx, BaseRecord series, Map<Long, BaseRecord> byId, ComposeResult aux) {
		BaseRecord ref = (ctx.book.hasField(OlioFieldNames.FIELD_PB_SERIES) ? ctx.book.get(OlioFieldNames.FIELD_PB_SERIES) : null);
		if(ref == null) {
			return Collections.emptyList();
		}
		try {
			BaseRecord ser = (series != null ? series : readSeries(user, ref, ctx.orgId));
			if(ser == null) {
				aux.warnings.add("The book's series is not readable; no baseline cast offered");
				return Collections.emptyList();
			}
			BaseRecord world = (ser.hasField(OlioFieldNames.FIELD_PB_UNIVERSE) ? ser.get(OlioFieldNames.FIELD_PB_UNIVERSE) : null);
			String seriesSlug = (world != null ? PbComposeUtil.str(world.get(FieldNames.FIELD_NAME)) : null);
			if(seriesSlug == null) {
				seriesSlug = PbSeriesUtil.seriesSlug(ser);
			}
			if(seriesSlug == null) {
				return Collections.emptyList();
			}
			BaseRecord group = PbCastUtil.findCastGroup(user, PbCastUtil.baselineCastGroupName(seriesSlug),
				PbBookUtil.bookGroupPath(seriesSlug), ctx.orgId);
			if(group == null) {
				return Collections.emptyList();
			}
			List<Long> ids = new ArrayList<>();
			for(BaseRecord m : PbCastUtil.listCastMembers(user, group)) {
				long id = PbHealthUtil.idOf(m);
				if(id > 0L) {
					ids.add(Long.valueOf(id));
				}
			}
			return resolveIds(user, ctx, ids, byId, aux);
		}
		catch(RuntimeException e) {
			aux.warnings.add("Could not read the series' baseline cast: " + e.getMessage());
			logger.warn("compose-cover " + ctx.label() + ": baseline cast read failed: " + e.getMessage());
			return Collections.emptyList();
		}
	}

	/// The six placeholders of pictureBook.compose-cover, every one non-null (callLlmInternal skips null values,
	/// which would leave the placeholder in the text and hard-fail the call). Free text is sanitized.
	static Map<String, String> coverVariables(BaseRecord user, Context ctx, BaseRecord series, Cast cast, BookTally tally, ComposeResult aux) {
		Map<String, String> vars = new HashMap<>();
		vars.put("purpose", purposeWord(ctx.purpose));
		vars.put("style", PbComposeUtil.styleName(user, ctx, aux));
		vars.put("title", PbComposeUtil.orNone(PbLocationUtil.sanitizeForPrompt(
			PbComposeUtil.str(ctx.book.get(FieldNames.FIELD_NAME)), PbComposeUtil.MAX_FIELD_CHARS)));
		vars.put("summary", summaryText(ctx, series, aux));
		vars.put("cast", PbComposeUtil.castBlock(cast));
		vars.put("locations", coverLocationsBlock(user, ctx, tally, aux));
		return vars;
	}

	/// The book's description; for SERIES_ART the series' description first, then the host chapter's.
	static String summaryText(Context ctx, BaseRecord series, ComposeResult aux) {
		List<String> parts = new ArrayList<>();
		if(series != null) {
			String sd = PbComposeUtil.str(series.get(FieldNames.FIELD_DESCRIPTION));
			if(NarrativeUtil.isMeaningful(sd)) {
				parts.add(sd.trim());
			}
		}
		String bd = PbComposeUtil.str(ctx.book.get(FieldNames.FIELD_DESCRIPTION));
		if(NarrativeUtil.isMeaningful(bd)) {
			parts.add(bd.trim());
		}
		if(parts.isEmpty()) {
			aux.warnings.add("The book has no description; composing the " + purposeWord(ctx.purpose) + " from its title and cast only");
			return PbComposeUtil.NONE;
		}
		String text = String.join("\n\n", parts);
		if(text.length() > PbComposeUtil.MAX_SCENE_TEXT_CHARS) {
			aux.warnings.add("Book description truncated from " + text.length() + " to " + PbComposeUtil.MAX_SCENE_TEXT_CHARS + " characters for the prompt");
		}
		return PbComposeUtil.orNone(PbLocationUtil.sanitizeForPrompt(text, PbComposeUtil.MAX_SCENE_TEXT_CHARS));
	}

	/// The KNOWN LOCATIONS block with the book's most-used scene location first and tagged, so the LLM has a
	/// reason to set the cover there without being told to.
	static String coverLocationsBlock(BaseRecord user, Context ctx, BookTally tally, ComposeResult aux) {
		List<BaseRecord> locs;
		try {
			locs = PbLocationUtil.listLocations(user, ctx.bctx);
		}
		catch(Exception e) {
			aux.warnings.add("Could not list the book's locations: " + e.getMessage());
			return PbComposeUtil.NONE;
		}
		if(locs == null || locs.isEmpty()) {
			return PbComposeUtil.NONE;
		}
		List<BaseRecord> ordered = new ArrayList<>(locs);
		if(tally.topLocationId > 0L) {
			for(int i = 0; i < ordered.size(); i++) {
				if(PbHealthUtil.idOf(ordered.get(i)) == tally.topLocationId) {
					ordered.add(0, ordered.remove(i));
					break;
				}
			}
		}
		return PbComposeUtil.locationLines(ordered, tally.topLocationId, "(most used in this book)");
	}

	private static void copyNotes(ComposeResult aux, CoverResult r) {
		for(String w : aux.warnings) {
			if(!r.warnings.contains(w)) {
				r.warnings.add(w);
			}
		}
		for(String n : aux.unresolvedNames) {
			if(!r.unresolvedNames.contains(n)) {
				r.unresolvedNames.add(n);
			}
		}
		for(String p : aux.unrecognizedPlacements) {
			if(!r.unrecognizedPlacements.contains(p)) {
				r.unrecognizedPlacements.add(p);
			}
		}
		if(aux.locationHint != null) {
			r.locationHint = aux.locationHint;
		}
	}

	// ─────────────────────────────── FROM_SCENE / MANUAL ───────────────────────────────

	/** Composition fields a FROM_SCENE clone carries over (scene, purpose, source, revision and titleSafeBand are not among them). */
	static final String[] CLONED_COMPOSITION_FIELDS = new String[] {
		OlioFieldNames.FIELD_PB_SETTING_TEXT, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD,
		OlioFieldNames.FIELD_PB_SHOT_TYPE, OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_CAMERA_TEXT,
		OlioFieldNames.FIELD_PB_PROPS, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT,
		OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, OlioFieldNames.FIELD_PB_PROMPT_LOCKED
	};

	static final String[] CLONED_ACTOR_FIELDS = new String[] {
		OlioFieldNames.FIELD_PB_CHARACTER, OlioFieldNames.FIELD_APPAREL, OlioFieldNames.FIELD_PB_ROLE,
		OlioFieldNames.FIELD_PB_PRIORITY, OlioFieldNames.FIELD_PB_POSE, FieldNames.FIELD_EXPRESSION,
		OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_FACING, OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y,
		OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H, OlioFieldNames.FIELD_PB_DEPTH, FieldNames.FIELD_DESCRIPTION,
		FieldNames.FIELD_COUNT, OlioFieldNames.FIELD_PB_REFERENCE_POLICY
	};

	static final String[] CLONED_ELEMENT_FIELDS = new String[] {
		OlioFieldNames.FIELD_PB_ELEMENT_TYPE, FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y,
		OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H, OlioFieldNames.FIELD_PB_DEPTH, OlioFieldNames.FIELD_PB_IMPORTANCE,
		OlioFieldNames.FIELD_ITEM
	};

	/// Copy a scene's SCENE composition (fields, actor rows, element rows, focal re-pointed) as this cover:
	/// source CLONE, revision 0, scene null, titleSafeBand left UNKNOWN for the user to set.
	private static void cloneFromScene(BaseRecord user, Context ctx, BaseRecord series, String sceneObjectId, CoverResult r) {
		if(sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "FROM_SCENE needs the scene to copy");
		}
		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, ctx.orgId);
		if(scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		long sceneBookId = PbHealthUtil.fkId(scene, OlioFieldNames.FIELD_PB_BOOK);
		if(sceneBookId <= 0L || sceneBookId != PbHealthUtil.idOf(ctx.book)) {
			throw new PictureBookException(400, "Scene " + sceneObjectId + " does not belong to book " + ctx.slug);
		}
		BaseRecord src = PbComposeUtil.findSceneComposition(user, scene, ctx.bookGroupId, ctx.orgId);
		if(src == null) {
			Integer idx = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			throw new PictureBookException(404, "Scene " + (idx != null ? idx : sceneObjectId) + " has no composition to copy");
		}
		r.sourceSceneObjectId = sceneObjectId;
		BaseRecord comp = createCover(user, ctx, series, PbCompositionSourceEnumType.CLONE, 0, null, src);
		String compOid = PbComposeUtil.str(comp.get(FieldNames.FIELD_OBJECT_ID));
		Map<Long, BaseRecord> newByOldId = new HashMap<>();
		List<BaseRecord> srcActors = PbComposeUtil.listActors(user, src);
		for(int i = 0; i < srcActors.size(); i++) {
			BaseRecord row = cloneRow(user, ctx, comp, OlioModelNames.MODEL_PB_SCENE_ACTOR,
				PbComposeUtil.ACTOR_NAME_PREFIX + compOid + "." + i, srcActors.get(i), CLONED_ACTOR_FIELDS);
			if(row == null) {
				fail(r, comp, "Failed to copy actor row " + i + " onto composition " + compOid);
				PbComposeUtil.flagForRecompose(user, comp);
				return;
			}
			newByOldId.put(Long.valueOf(PbHealthUtil.idOf(srcActors.get(i))), row);
		}
		List<BaseRecord> srcElements = PbComposeUtil.listElements(user, src);
		for(int i = 0; i < srcElements.size(); i++) {
			if(cloneRow(user, ctx, comp, OlioModelNames.MODEL_PB_SCENE_ELEMENT,
					PbComposeUtil.ELEMENT_NAME_PREFIX + compOid + "." + i, srcElements.get(i), CLONED_ELEMENT_FIELDS) == null) {
				fail(r, comp, "Failed to copy element row " + i + " onto composition " + compOid);
				PbComposeUtil.flagForRecompose(user, comp);
				return;
			}
		}
		BaseRecord focal = newByOldId.get(Long.valueOf(PbHealthUtil.fkId(src, OlioFieldNames.FIELD_PB_FOCAL_ACTOR)));
		if(focal != null && !patchFocal(user, comp, focal)) {
			fail(r, comp, "Failed to finalise composition " + compOid);
			return;
		}
		finish(user, ctx, comp, compOid, PbCompositionSourceEnumType.CLONE, r);
	}

	private static BaseRecord cloneRow(BaseRecord user, Context ctx, BaseRecord comp, String model, String name, BaseRecord src, String[] fields) {
		BaseRecord row;
		try {
			row = RecordFactory.newInstance(model);
			PbComposeUtil.own(user, row, ctx, name);
			row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
			for(String f : fields) {
				Object v = (src.hasField(f) ? src.get(f) : null);
				if(v != null) {
					row.set(f, v);
				}
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble " + model + " " + name + ": " + e.getMessage(), e);
			return null;
		}
		return IOSystem.getActiveContext().getAccessPoint().create(user, row);
	}

	/// An empty composition the user fills in through the row editor: source USER, revision 1, no rows.
	private static void createManual(BaseRecord user, Context ctx, BaseRecord series, CoverResult r) {
		BaseRecord comp = createCover(user, ctx, series, PbCompositionSourceEnumType.USER, 1, null, null);
		finish(user, ctx, comp, PbComposeUtil.str(comp.get(FieldNames.FIELD_OBJECT_ID)), PbCompositionSourceEnumType.USER, r);
	}

	// ─────────────────────────────── writes ───────────────────────────────

	/**
	 * Create the cover-type composition row: the deterministic name in the book's group, {@code book} = this
	 * book, {@code purpose}, {@code series} for SERIES_ART, {@code scene} null, unedited. With a {@link Staging}
	 * the staged fields (including {@code titleSafeBand}, since the context has no scene) are applied; with a
	 * {@code cloneOf} the {@link #CLONED_COMPOSITION_FIELDS} are copied; otherwise only {@code source} is set.
	 */
	private static BaseRecord createCover(BaseRecord user, Context ctx, BaseRecord series, PbCompositionSourceEnumType source,
			int revision, Staging s, BaseRecord cloneOf) {
		String name = coverName(ctx.purpose, PbComposeUtil.str(ctx.book.get(FieldNames.FIELD_OBJECT_ID)),
			(series != null ? PbComposeUtil.str(series.get(FieldNames.FIELD_OBJECT_ID)) : null));
		BaseRecord comp;
		try {
			comp = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION);
			PbComposeUtil.own(user, comp, ctx, name);
			comp.set(OlioFieldNames.FIELD_PB_BOOK, ctx.book);
			comp.set(OlioFieldNames.FIELD_PB_PURPOSE, ctx.purpose.toString());
			if(series != null) {
				comp.set(OlioFieldNames.FIELD_PB_SERIES, series);
			}
			comp.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
			comp.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(revision));
			if(s != null) {
				PbComposeUtil.applyStagingFields(comp, ctx, s);
			}
			else {
				comp.set(OlioFieldNames.FIELD_PB_SOURCE, source.toString());
			}
			if(cloneOf != null) {
				for(String f : CLONED_COMPOSITION_FIELDS) {
					Object v = (cloneOf.hasField(f) ? cloneOf.get(f) : null);
					if(v != null) {
						comp.set(f, v);
					}
				}
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
		BaseRecord fresh = PbComposeUtil.readComposition(user, PbComposeUtil.str(created.get(FieldNames.FIELD_OBJECT_ID)), ctx.orgId);
		if(fresh == null) {
			throw new PictureBookException(500, "Composition " + name + " was created but cannot be read back");
		}
		return fresh;
	}

	/// The staged actor and element rows, then the focal pointer - the new-composition half of PbComposeUtil.persist.
	private static void writeRows(BaseRecord user, Context ctx, BaseRecord comp, Staging s, CoverResult r) {
		String compOid = PbComposeUtil.str(comp.get(FieldNames.FIELD_OBJECT_ID));
		List<BaseRecord> createdActors = new ArrayList<>();
		for(int i = 0; i < s.actors.size(); i++) {
			BaseRecord created = PbComposeUtil.createActor(user, ctx, comp, compOid, s.actors.get(i), i);
			if(created == null) {
				fail(r, comp, "Failed to create actor row " + i + " of composition " + compOid);
				PbComposeUtil.flagForRecompose(user, comp);
				return;
			}
			createdActors.add(created);
		}
		for(int i = 0; i < s.elements.size(); i++) {
			if(PbComposeUtil.createElement(user, ctx, comp, compOid, s.elements.get(i), i) == null) {
				fail(r, comp, "Failed to create element row " + i + " of composition " + compOid);
				PbComposeUtil.flagForRecompose(user, comp);
				return;
			}
		}
		BaseRecord focal = (s.focalIndex >= 0 && s.focalIndex < createdActors.size() ? createdActors.get(s.focalIndex) : null);
		if(focal != null && !patchFocal(user, comp, focal)) {
			fail(r, comp, "Failed to finalise composition " + compOid);
			return;
		}
		finish(user, ctx, comp, compOid, s.source, r);
	}

	private static boolean patchFocal(BaseRecord user, BaseRecord comp, BaseRecord focal) {
		BaseRecord patch = PbGraphUtil.patchOf(comp, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
		PbComposeUtil.setQuiet(patch, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, focal);
		return IOSystem.getActiveContext().getAccessPoint().update(user, patch) != null;
	}

	private static void finish(BaseRecord user, Context ctx, BaseRecord comp, String compOid, PbCompositionSourceEnumType source, CoverResult r) {
		BaseRecord fresh = PbComposeUtil.readComposition(user, compOid, ctx.orgId);
		r.composition = (fresh != null ? fresh : comp);
		r.actors = PbComposeUtil.listActors(user, r.composition);
		r.elements = PbComposeUtil.listElements(user, r.composition);
		r.source = source;
		r.created = true;
	}

	private static void fail(CoverResult r, BaseRecord comp, String why) {
		r.hardFailure = true;
		r.failureReason = why;
		r.composition = comp;
		logger.error("compose-cover: " + why);
	}
}
