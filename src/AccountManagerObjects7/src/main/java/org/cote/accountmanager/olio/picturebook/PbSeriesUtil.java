package org.cote.accountmanager.olio.picturebook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;

/**
 * The {@code olio.pb.series} lifecycle - the scope for "the chapters of one book" (N1, Q6/Q7).
 * <p>
 * A series owns ONE shared Olio world; every chapter book references it ({@code book.world =
 * series.universe}). This class is the series analogue of {@link PbBookUtil#createBook}, and it
 * mirrors that create ordering: <b>the series row first</b> (the unique {@code (name, groupId,
 * organizationId)} index is the serialization point), then the shared world via
 * {@link PbOlioContextUtil#getCreateSeriesContext(BaseRecord, String, String)}, then the patch that
 * links the row to the world. The world itself is find-or-create by slug, so a racer never orphans a
 * second world; the row index is what serializes concurrent creation.
 * <p>
 * <b>Where the series row lives, and the write asymmetry that follows.</b> Unlike a book row, the
 * series row lives in the universe's OWN {@code Book} group
 * ({@link PbOlioContextUtil#seriesRowGroupPath()}) - deliberately discoverable to every member of the
 * organization-wide universe {@code Reader} role (see that method's javadoc). But the universe grant
 * pass runs with {@code userWrite=false}, so the universe {@code Reader} role holds only <b>Read</b>
 * there. Consequently the acting user can READ the series row but cannot WRITE it. Every write here -
 * the initial row, the {@code universe} FK patch, and {@code bookCount} maintenance - therefore goes
 * through the <b>olio principal</b> ({@code RecordUtil}, PBAC bypass), exactly as
 * {@link PbBookUtil#writeBookRow} writes the book row; every READ goes through {@code AccessPoint} as
 * the acting user, which also proves the universe Read grant reached the series.
 * <p>
 * <b>{@code series.universe} holds the shared per-series WORLD.</b> Despite the model field's stale
 * "the Books universe this series belongs to" description, the binding plan (§3 N1) uses this
 * {@code olio.world} FK to hold the one shared series world, and {@code book.world = series.universe}
 * for every chapter. {@code olio.world.basis} on that world still points at the {@code Books}
 * universe, unchanged.
 */
public class PbSeriesUtil {
	public static final Logger logger = LogManager.getLogger(PbSeriesUtil.class);

	private PbSeriesUtil() {
		/// static utility
	}

	// ─────────────────────────────── create-or-get ───────────────────────────────

	/**
	 * Get-or-create the series for {@code seriesSlug}. Get-or-create, NOT create-only: a chapter book's
	 * creation resolves its series through here, so the second chapter must find the series the first one
	 * made rather than fail on it.
	 *
	 * @param user       the creator/opener; enrolled by {@code getCreateSeriesContext} in the series
	 *                   {@code Writer} and universe {@code Reader} roles on genuine creation
	 * @param dataPath   the Olio data path ({@code test.datagen.path} / the deployment's equivalent)
	 * @param seriesSlug validated against {@code PbOlioContextUtil.BOOK_SLUG_PATTERN}; unique per org and
	 *                   shared with the book-world slug namespace
	 * @param title      the human title; stored in {@code description}. The record's {@code name} is
	 *                   derived from the slug so the unique index cannot be tripped by two series sharing
	 *                   a title.
	 * @return the series row, re-read as {@code user} through {@code AccessPoint}
	 * @throws PictureBookException 400 for a malformed slug, 500 on world/link failure
	 */
	public static BaseRecord getCreateSeries(BaseRecord user, String dataPath, String seriesSlug, String title) {
		if(user == null) {
			throw new PictureBookException(400, "A series needs a creator");
		}
		try {
			PbOlioContextUtil.validateBookSlug(seriesSlug);
		}
		catch(OlioException e) {
			throw new PictureBookException(400, e.getMessage());
		}

		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx == null) {
			throw new PictureBookException(500, "Failed to find an organization context");
		}
		long orgId = octx.getOrganizationId();

		/// GET: an existing, readable series is returned as-is - this is what makes chapter 2+ reuse the
		/// series world instead of colliding with it. A series that exists but is not readable by this user
		/// returns null here and falls through to getCreateSeriesContext, which refuses the unentitled open.
		BaseRecord existing = findSeriesBySlug(user, seriesSlug, orgId);
		if(existing != null) {
			return existing;
		}

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		String seriesGroupPath = PbOlioContextUtil.seriesRowGroupPath();

		BaseRecord created = null;
		if(olioUser != null) {
			created = writeSeriesRow(ioContext, olioUser, seriesSlug, title, seriesGroupPath, orgId);
		}

		/// THEN the shared world. Creator enrolment (series Writer + universe Reader) and grant
		/// verification all happen here, on genuine creation only.
		OlioContext ctx;
		try {
			ctx = PbOlioContextUtil.getCreateSeriesContext(user, dataPath, seriesSlug);
		}
		catch(OlioException e) {
			throw new PictureBookException(500, "Failed to create the shared world for series '" + seriesSlug + "': " + e.getMessage(), e);
		}
		if(ctx == null || ctx.getWorld() == null) {
			throw new PictureBookException(500, "No shared world was created for series '" + seriesSlug + "'");
		}

		if(created == null) {
			/// No olio principal existed before the world; the series world creation bootstrapped it. Write
			/// the row now - the unique name index still serializes concurrent creation.
			BaseRecord bootstrapped = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			if(bootstrapped == null) {
				throw new PictureBookException(500, "The series world was created but no olio principal exists in organization " + orgId);
			}
			created = writeSeriesRow(ioContext, bootstrapped, seriesSlug, title, seriesGroupPath, orgId);
		}

		/// THEN link the row to the shared world. As the OLIO PRINCIPAL, not the acting user: the series
		/// row sits in the universe's Read-only Book group, so the universe Reader role cannot write it.
		BaseRecord olioWriter = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		BaseRecord patch = PbGraphUtil.patchOf(created, OlioModelNames.MODEL_PB_SERIES, OlioFieldNames.FIELD_PB_UNIVERSE);
		try {
			patch.set(OlioFieldNames.FIELD_PB_UNIVERSE, ctx.getWorld());
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble the series world patch: " + e.getMessage());
		}
		if(!ioContext.getRecordUtil().updateRecord(patch)) {
			throw new PictureBookException(500, "Failed to link series '" + seriesSlug + "' to its shared world."
				+ " The world exists and the series row exists, but the series does not reference it.");
		}

		/// Prove the universe Read grant landed: re-read as the ACTING user through AccessPoint.
		BaseRecord readBack = findSeriesBySlug(user, seriesSlug, orgId);
		if(readBack == null) {
			throw new PictureBookException(500, "Series '" + seriesSlug + "' was created but is not readable by its creator"
				+ " - the universe authorization grant did not reach " + seriesGroupPath);
		}
		return readBack;
	}

	/**
	 * Write the series row as the olio principal (PBAC bypass), {@code universe} FK null and
	 * {@code bookCount} zero. The name is set explicitly so the unique {@code (name, groupId,
	 * organizationId)} index cannot be defeated by a NULL name.
	 */
	private static BaseRecord writeSeriesRow(IOContext ioContext, BaseRecord olioUser, String seriesSlug, String title,
			String seriesGroupPath, long orgId) {
		BaseRecord group = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, seriesGroupPath,
			GroupEnumType.DATA.toString(), orgId);
		if(group == null) {
			throw new PictureBookException(500, "Failed to resolve the series group " + seriesGroupPath);
		}
		String name = seriesName(seriesSlug);
		BaseRecord series = null;
		try {
			series = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SERIES);
			ioContext.getRecordUtil().applyNameGroupOwnership(olioUser, series, name, seriesGroupPath, orgId);
			series.set(FieldNames.FIELD_NAME, name);
			series.set(OlioFieldNames.FIELD_PB_BOOK_COUNT, Integer.valueOf(0));
			if(title != null) {
				series.set(FieldNames.FIELD_DESCRIPTION, title);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a series: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble series '" + seriesSlug + "'");
		}
		if(!ioContext.getRecordUtil().createRecord(series)) {
			/// The unique (name, groupId, organizationId) index rejects a duplicate; a concurrent
			/// first-creation of the same series lands here. The caller's next findSeriesBySlug would
			/// resolve the winner, so a 409 is the correct, retryable signal.
			throw new PictureBookException(409, "Failed to create series '" + seriesSlug + "'."
				+ " A concurrent creation of the same series is the expected cause.");
		}
		return series;
	}

	// ─────────────────────────────── read ───────────────────────────────

	/**
	 * The series for {@code seriesSlug}, read as {@code user} through {@code AccessPoint}, or null when
	 * absent OR not readable by {@code user}. Keyed by the derived {@code name} (there is no {@code slug}
	 * field on the series model) plus an explicit numeric {@code organizationId} condition; uncached.
	 */
	public static BaseRecord findSeriesBySlug(BaseRecord user, String seriesSlug, long organizationId) {
		if(user == null || seriesSlug == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, FieldNames.FIELD_NAME, seriesName(seriesSlug));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(seriesRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	public static BaseRecord readSeries(BaseRecord user, String objectId, long organizationId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(seriesRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── series-world detection (delete guard) ───────────────────────────────

	/**
	 * Reverse lookup: the {@code olio.pb.series} whose shared world (its {@code universe} FK) is
	 * {@code world}, or {@code null} when no series references it. This is the detection every
	 * chapter/world delete path keys on to satisfy the binding §5/§8 constraint that a chapter delete
	 * (or a raw world delete) must <b>never</b> wipe the ONE world shared by a whole series - doing so
	 * would destroy the baseline cast plus <i>every</i> other chapter's shadow cast, population and
	 * events in a single call ({@link org.cote.accountmanager.olio.WorldUtil#deleteWorld} runs
	 * {@code cleanupWorld} over the entire population group).
	 * <p>
	 * <b>Queried by the world RECORD, not its id.</b> {@code universe} is a {@code foreign} {@code model}
	 * field, so the condition value must be the {@code BaseRecord} (see {@code model-api.md}: a {@code Long}
	 * there silently becomes {@code universe = null} and matches nothing).
	 * <p>
	 * <b>Read via the raw search (PBAC bypass), not {@code AccessPoint}.</b> This is a defensive guard that
	 * must return a reliable answer regardless of who is performing the delete - a caller that cannot READ
	 * the series row must still be prevented from deleting the world out from under it. The series row's
	 * mere existence is not sensitive, the query is constrained to the world's own {@code organizationId},
	 * and utilities may bypass the access client for exactly this kind of internal decision.
	 *
	 * @param world the {@code olio.world} being considered for deletion; {@code null} yields {@code null}
	 * @return the backing series row (id/objectId/name/universe/bookCount projected), or {@code null}
	 */
	public static BaseRecord findSeriesByWorld(BaseRecord world) {
		if(world == null) {
			return null;
		}
		Object orgObj = world.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(orgObj == null) {
			return null;
		}
		long orgId = ((Number) orgObj).longValue();
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, OlioFieldNames.FIELD_PB_UNIVERSE, world);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(seriesRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/**
	 * Convenience predicate over {@link #findSeriesByWorld(BaseRecord)}: {@code true} when {@code world}
	 * is the shared world of some {@code olio.pb.series}.
	 */
	public static boolean isSeriesWorld(BaseRecord world) {
		return findSeriesByWorld(world) != null;
	}

	// ─────────────────────────────── bookCount ───────────────────────────────

	/**
	 * Increment the series' {@code bookCount} by one (a chapter was added). Written as the olio principal
	 * because the series row is universe-Read-only for the acting user; PATCH-shaped; the update result is
	 * asserted, never discarded.
	 *
	 * @param series the series row, with {@code bookCount} projected (as {@link #seriesRequest()} does)
	 * @return the new count, or the current count unchanged on failure
	 */
	public static int incrementBookCount(BaseRecord series) {
		if(series == null) {
			return 0;
		}
		Integer current = series.get(OlioFieldNames.FIELD_PB_BOOK_COUNT);
		int next = (current != null ? current.intValue() : 0) + 1;
		IOContext ioContext = IOSystem.getActiveContext();
		long orgId = ((Number) series.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			logger.error("No olio principal in organization " + orgId + "; cannot maintain series bookCount");
			return (current != null ? current.intValue() : 0);
		}
		BaseRecord patch = PbGraphUtil.patchOf(series, OlioModelNames.MODEL_PB_SERIES, OlioFieldNames.FIELD_PB_BOOK_COUNT);
		try {
			patch.set(OlioFieldNames.FIELD_PB_BOOK_COUNT, Integer.valueOf(next));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a series bookCount patch: " + e.getMessage());
		}
		if(!ioContext.getRecordUtil().updateRecord(patch)) {
			logger.error("Failed to persist bookCount=" + next + " on series " + series.get(FieldNames.FIELD_NAME));
			return (current != null ? current.intValue() : 0);
		}
		return next;
	}

	// ─────────────────────────────── names & projection ───────────────────────────────

	/**
	 * The series record's derived name. Includes the slug, which is unique per organization, so the unique
	 * {@code (name, groupId, organizationId)} index cannot be tripped by two series sharing a title.
	 */
	public static String seriesName(String seriesSlug) {
		return "Series " + seriesSlug;
	}

	/** What to project on a series read. Includes {@code universe} (the shared world FK) and {@code bookCount}. */
	public static String[] seriesRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_UNIVERSE, OlioFieldNames.FIELD_PB_BOOK_COUNT
		};
	}
}
