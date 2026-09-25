package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.rules.BookWorldInitializationRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;

/**
 * The {@code olio.pb.book} lifecycle, and the scene rows that replace PB1's per-scene {@code data.note}
 * JSON blob.
 * <p>
 * <b>Create ordering is ratification 7 and is not negotiable:</b> the <b>book row first</b>, then the
 * world, then a patch of the book's {@code world} FK. The unique {@code (slug, organizationId)} index is
 * the serialization point - a second racer's create fails on the index instead of building a second world
 * that nothing references. There is no per-slug JVM lock, and none is needed while the index rejects the
 * duplicate.
 * <p>
 * <b>Where the book row lives, and why it is not §2.2's answer verbatim.</b> §2.2 says the book lives in
 * {@code {world}/Book}, and it does - but that group is created by
 * {@code BookWorldInitializationRule} during {@code initialize()}, i.e. <i>after</i> the world, while
 * ratification 7 requires the row <i>before</i> it. The two are reconciled by creating the
 * {@code {container}/Book} group directly with {@code makePath} (get-or-create, olio-user-owned - exactly
 * what the rule itself does, so the rule then adopts it) and writing the row there. The alternative - the
 * universe's own {@code Book} group, where {@code olio.pb.series} lives - was rejected: that group is a
 * child of the universe container, so {@code resolveGrantTargets} grants Read on it to the shared
 * organization-wide universe {@code Reader} role, and <b>every book creator is a member of that role</b>.
 * Every book in the organization would be listable by every other book's creator, which is the exact
 * isolation property {@code TestPbSecurity} asserts.
 * <p>
 * <b>Consequence to state plainly:</b> for the FIRST book in an organization this pre-creates the
 * {@code /Olio/Universes/Books/Worlds} group skeleton ahead of the universe and world <i>records</i>.
 * {@code makePath} is get-or-create keyed on {@code (name, parentId, organizationId)}, so the subsequent
 * universe/world creation adopts those groups rather than duplicating them - measured in a fresh
 * organization by {@code TestPbSecurity}, not assumed.
 * <p>
 * <b>Ownership is the olio user, uniformly</b> (ratified Q1). The book row is written through
 * {@code RecordUtil.createRecord} as the olio principal, the same way {@code WorldUtil.getCreateWorld}
 * writes the world, because at that instant no grant exists on the group yet - the grants are applied by
 * {@code initialize()}, which runs next. Every subsequent read and write of the book goes through
 * {@code AccessPoint} as the acting user, and {@link #createBook} proves the grants landed by re-reading
 * the book that way before returning it.
 */
public class PbBookUtil {
	public static final Logger logger = LogManager.getLogger(PbBookUtil.class);

	private PbBookUtil() {
		/// static utility
	}

	// ─────────────────────────────── create ───────────────────────────────

	/**
	 * Create a book: row, world, and the FK patch that links them, in ratification 7's order.
	 *
	 * @param user the creator; enrolled by {@code getCreateBookContext} in the book {@code Writer} role
	 *        AND the universe {@code Reader} role - both tiers, which is not optional since the per-book
	 *        role holds nothing on the corpora
	 * @param dataPath the Olio data path ({@code test.datagen.path} / the deployment's equivalent)
	 * @param slug validated against {@code PbOlioContextUtil.BOOK_SLUG_PATTERN} before any path is built
	 * @param title the human title; the record's {@code name} is derived from it and the slug
	 * @return the book, re-read as {@code user} through {@code AccessPoint}
	 * @throws PictureBookException 409 when the slug is already taken, 400 for a malformed slug
	 */
	public static BaseRecord createBook(BaseRecord user, String dataPath, String slug, String title) {
		if(user == null) {
			throw new PictureBookException(400, "A book needs a creator");
		}
		try {
			PbOlioContextUtil.validateBookSlug(slug);
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

		/// A pre-flight read, so the common "that slug is taken" case is a clean 409 rather than a create
		/// failure. It is NOT the serialization point - the unique index is - and this method does not
		/// pretend otherwise: the create below is still checked, and a racer who slips through the window
		/// between this read and that write is refused there.
		if(findBookBySlug(user, slug, orgId) != null) {
			throw new PictureBookException(409, "A book with slug '" + slug + "' already exists in this organization");
		}

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			/// Uniform olioUser ownership (ratified Q1) means the principal has to exist before the row.
			/// The first Olio context in an organization creates it; a book must not be the thing that
			/// bootstraps authorization, so create the context first in that case.
			logger.info("No olio principal in organization " + orgId + " yet; the book world creation will bootstrap it");
		}

		String containerPath = bookContainerPath(slug);
		String bookGroupPath = containerPath + "/" + BookWorldInitializationRule.GROUP_BOOK;

		BaseRecord created = null;
		if(olioUser != null) {
			created = writeBookRow(ioContext, olioUser, slug, title, bookGroupPath, orgId);
		}

		/// A world already at this path is adopted by getCreateBookContext, not created, so it is not this
		/// call's to unwind. Probe find-only BEFORE creating so the rollback below knows the difference.
		boolean worldPreExisted = (olioUser != null
			&& WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug) != null);

		/// From here on the row exists. Any failure before the book is linked and readable must take the
		/// row - and the world, if this call made it - back out with it. Otherwise the slug stays taken by
		/// a row the creator can never see (createdByObjectId is only stamped by the patch below), and a
		/// retry with the same title dies on 409 against an artifact no list will ever show.
		try {
			return linkBookToWorld(ioContext, user, created, dataPath, slug, title, bookGroupPath, orgId);
		}
		catch(RuntimeException e) {
			rollbackCreate(ioContext, slug, orgId, worldPreExisted, e);
			throw e;
		}
	}

	/** The world, bootstrap row, FK patch and creator read-back halves of {@link #createBook}. */
	private static BaseRecord linkBookToWorld(IOContext ioContext, BaseRecord user, BaseRecord created,
			String dataPath, String slug, String title, String bookGroupPath, long orgId) {
		/// THEN the world. Grants, both-tier creator enrolment and post-init verification all happen here.
		OlioContext ctx = null;
		try {
			ctx = PbOlioContextUtil.getCreateBookContext(user, dataPath, slug);
		}
		catch(OlioException e) {
			throw new PictureBookException(500, "Failed to create the world for book '" + slug + "': " + e.getMessage(), e);
		}
		if(ctx == null || ctx.getWorld() == null) {
			throw new PictureBookException(500, "No world was created for book '" + slug + "'");
		}

		if(created == null) {
			/// The organization had no olio principal, so the row could not be written before the world.
			/// It is written now, immediately after - the slug index still serializes concurrent creation,
			/// and this branch is reachable only for the very first Olio context in an organization.
			BaseRecord bootstrapped = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			if(bootstrapped == null) {
				throw new PictureBookException(500, "The book world was created but no olio principal exists in organization " + orgId);
			}
			created = writeBookRow(ioContext, bootstrapped, slug, title, bookGroupPath, orgId);
		}

		/// THEN patch the world FK. PATCH-shaped: schema + id + objectId + NAME + the changed field, and
		/// the result is asserted - a discarded update result is the difference between a persistent
		/// failure and a silent no-op.
		BaseRecord patch = PbGraphUtil.patchOf(created, OlioModelNames.MODEL_PB_BOOK,
			OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID);
		try {
			patch.set(OlioFieldNames.FIELD_PB_WORLD, ctx.getWorld());
			patch.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, user.get(FieldNames.FIELD_OBJECT_ID));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble the book world patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to link book '" + slug + "' to its world."
				+ " The world exists and the book row exists, but the book does not reference it.");
		}

		/// Prove the grants landed: re-read as the ACTING user through AccessPoint. A book the creator
		/// cannot read is a failed creation, not a successful one, and this is the only check that
		/// distinguishes them.
		BaseRecord readBack = findBookBySlug(user, slug, orgId);
		if(readBack == null) {
			throw new PictureBookException(500, "Book '" + slug + "' was created but is not readable by its creator"
				+ " - the world authorization grants did not reach " + bookGroupPath);
		}
		return readBack;
	}

	/**
	 * Unwind a failed {@link #createBook}: the row this call wrote and, unless it was already there when the
	 * call began, the world and groups {@code getCreateBookContext} built for it. Runs as the olio principal
	 * (the owner of every artifact involved); the creator holds no grant at this point to be authorized
	 * against, and the caller is throwing the original failure regardless. A rollback failure is logged
	 * with the artifact it left behind - it never masks {@code cause}. Package-private so the rollback path
	 * can be driven directly by {@code TestPbCreateBookRollback} for the fresh-world branch, which no input
	 * to {@link #createBook} can make fail on demand.
	 */
	static void rollbackCreate(IOContext ioContext, String slug, long orgId, boolean worldPreExisted,
			RuntimeException cause) {
		logger.warn("Rolling back book '" + slug + "' after: " + cause.getMessage());
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			logger.error("Cannot roll back book '" + slug + "': no olio principal in organization " + orgId);
			return;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_SERIES});
		q.setCache(false);
		BaseRecord row = ioContext.getSearch().findRecord(q);

		if(worldPreExisted) {
			if(row != null) {
				PictureBookUtil.DeleteResult del = PictureBookUtil.deleteRecordExplained(olioUser, row);
				if(!del.deleted) {
					logger.error("Rollback of book '" + slug + "' left its row behind: " + del.reason);
				}
			}
			return;
		}
		if(row == null) {
			/// The row was never written (or is already gone) but the world may be. A slug-only stand-in is
			/// enough for the teardown to resolve the world and groups by slug.
			try {
				row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {OlioFieldNames.FIELD_PB_SLUG});
				row.set(OlioFieldNames.FIELD_PB_SLUG, slug);
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				logger.error("Rollback of book '" + slug + "' could not build a slug stand-in: " + e.getMessage());
				return;
			}
		}
		PictureBookUtil.DeleteResult del = PictureBookUtil.teardownBookFootprintAsOlio(olioUser, row, orgId);
		if(!del.deleted) {
			logger.error("Rollback of book '" + slug + "' left artifacts behind: " + del.reason);
		}
	}

	/**
	 * Create a CHAPTER book inside an existing series, or - when {@code series} is null - a standalone book
	 * via the unchanged 4-arg {@link #createBook(BaseRecord, String, String, String)}.
	 * <p>
	 * <b>A chapter does NOT get its own world.</b> It references the series' one shared world
	 * ({@code book.world = series.universe}, see {@link PbSeriesUtil}), carries a {@code series} FK and a
	 * {@code chapter} ordinal, and its row/scenes/workflow/artifacts live in the chapter's OWN container
	 * groups ({@code /Olio/Universes/Books/Worlds/{slug}/{Book,Workflow,Artifacts}}) - created here with
	 * {@code makePath}, NOT by an {@code initialize()} pass (there is no per-chapter world to initialize).
	 * <p>
	 * <b>Because no {@code initialize()} runs, nothing grants the chapter's own groups (§8 REQUIRED #2).</b>
	 * So this method explicitly grants the SERIES {@code Writer} and {@code Admin} roles CRUD on all three
	 * chapter groups via {@code setEntitlement} - the same grant {@code configureWorldAuthorization} would
	 * have applied to a per-book role pair. The creator is a series {@code Writer}, so after that grant it
	 * can write the FK patch and read the book back - both proven before this returns.
	 *
	 * @param user    the acting user; must already hold the series' {@code Writer} or {@code Admin} role
	 * @param dataPath the Olio data path (unused on the chapter path - kept for signature symmetry and the
	 *                delegated standalone path)
	 * @param slug    the chapter book's own slug, validated and unique per organization
	 * @param title   the human title; the record's {@code name} is derived from it and the slug
	 * @param series  the {@code olio.pb.series} the chapter belongs to, carrying its shared world in
	 *                {@code universe}; null routes to the standalone path
	 * @param chapter the chapter ordinal within the series; null defaults to the series' current book count
	 *                (0-based position)
	 * @return the chapter book, re-read as {@code user} through {@code AccessPoint}
	 * @throws PictureBookException 400 malformed slug / series without a world, 403 unentitled to the
	 *         series, 409 slug already taken, 500 on link failure
	 */
	public static BaseRecord createBook(BaseRecord user, String dataPath, String slug, String title,
			BaseRecord series, Integer chapter) {
		if(series == null) {
			return createBook(user, dataPath, slug, title);
		}
		if(user == null) {
			throw new PictureBookException(400, "A book needs a creator");
		}
		try {
			PbOlioContextUtil.validateBookSlug(slug);
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

		/// The series carries its one shared world in 'universe' (the field name is legacy; the value is the
		/// per-series olio.world - see PbSeriesUtil). Every chapter's book.world points at exactly this.
		BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		if(seriesWorld == null) {
			throw new PictureBookException(400, "Series '" + series.get(FieldNames.FIELD_NAME)
				+ "' has no shared world; create it through PbSeriesUtil.getCreateSeries first");
		}
		String seriesSlug = seriesWorld.get(FieldNames.FIELD_NAME);
		if(seriesSlug == null) {
			throw new PictureBookException(500, "The series' shared world has no name");
		}

		/// Entitlement is checked HERE, up front: opening/adding to a series requires the caller to already
		/// hold its Writer or Admin role. getCreateSeries enrolled the series creator; a different user must
		/// have been granted in.
		if(!PbOlioContextUtil.isEntitledToSeries(user, octx, seriesSlug)) {
			throw new PictureBookException(403, user.get(FieldNames.FIELD_NAME)
				+ " is not entitled to series '" + seriesSlug + "'");
		}

		if(findBookBySlug(user, slug, orgId) != null) {
			throw new PictureBookException(409, "A book with slug '" + slug + "' already exists in this organization");
		}

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			/// The series world creation already bootstrapped the olio principal, so its absence here is a
			/// real inconsistency, not the first-context case the standalone path tolerates.
			throw new PictureBookException(500, "No olio principal in organization " + orgId
				+ " though series '" + seriesSlug + "' exists");
		}

		String bookGroupPath = bookGroupPath(slug);
		/// The chapter's OWN container groups. makePath is get-or-create as the olio principal; no world
		/// init runs on the chapter, so these three are all that exist and all that must be granted.
		BaseRecord bookGroup = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath, GroupEnumType.DATA.toString(), orgId);
		BaseRecord workflowGroup = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, workflowGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		BaseRecord artifactGroup = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, artifactGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		if(bookGroup == null || workflowGroup == null || artifactGroup == null) {
			throw new PictureBookException(500, "Failed to resolve the chapter container groups for '" + slug + "'");
		}

		BaseRecord created = writeBookRow(ioContext, olioUser, slug, title, bookGroupPath, orgId);

		/// §8 REQUIRED #2: grant the SERIES role pair CRUD on the chapter's own groups. Without this the
		/// creator - a series Writer - could not write the FK patch below or read the book back, because no
		/// initialize() ever touched these groups.
		grantSeriesRolesOnChapterGroups(ioContext, octx, orgId, seriesSlug,
			new BaseRecord[] {bookGroup, workflowGroup, artifactGroup});

		int ordinal = (chapter != null ? chapter.intValue() : seriesBookCount(series));

		/// Link the chapter to the series' shared world, series and ordinal. As the ACTING user now that the
		/// series-role CRUD grant has landed - which also proves that grant, exactly as the read-back proves
		/// the Read half. PATCH-shaped, result asserted.
		BaseRecord patch = PbGraphUtil.patchOf(created, OlioModelNames.MODEL_PB_BOOK,
			OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_SERIES, OlioFieldNames.FIELD_PB_CHAPTER,
			OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID);
		try {
			patch.set(OlioFieldNames.FIELD_PB_WORLD, seriesWorld);
			patch.set(OlioFieldNames.FIELD_PB_SERIES, series);
			patch.set(OlioFieldNames.FIELD_PB_CHAPTER, Integer.valueOf(ordinal));
			patch.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, user.get(FieldNames.FIELD_OBJECT_ID));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble the chapter link patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to link chapter '" + slug + "' to series '" + seriesSlug + "'");
		}

		/// Maintain the series' book count. Best-effort: a chapter that is linked but not counted is a
		/// display nuisance, not a broken book, so a failed increment logs rather than unwinds the chapter.
		PbSeriesUtil.incrementBookCount(series);

		BaseRecord readBack = findBookBySlug(user, slug, orgId);
		if(readBack == null) {
			throw new PictureBookException(500, "Chapter '" + slug + "' was created but is not readable by its creator"
				+ " - the series-role grants did not reach " + bookGroupPath);
		}
		return readBack;
	}

	/** The series' current {@code bookCount}, or 0 when unset. */
	private static int seriesBookCount(BaseRecord series) {
		Integer c = series.get(OlioFieldNames.FIELD_PB_BOOK_COUNT);
		return (c != null ? c.intValue() : 0);
	}

	/**
	 * Grant the series {@code Writer} and {@code Admin} roles CRUD ({@code Read, Update, Create, Delete})
	 * on each chapter group - the DATA+GROUP entitlement {@code configureWorldAuthorization} applies to a
	 * world's own groups, targeted here at the series role pair because the chapter has no world init of its
	 * own. Resolved find-only as the olio principal (the roles were created by {@code newSeriesConfiguration});
	 * granted as the organization admin, exactly as {@code configureWorldAuthorization} does.
	 */
	private static void grantSeriesRolesOnChapterGroups(IOContext ioContext, OrganizationContext octx, long orgId,
			String seriesSlug, BaseRecord[] groups) {
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal to resolve series roles for '" + seriesSlug + "'");
		}
		BaseRecord writerRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.seriesWriterRolePath(seriesSlug), RoleEnumType.USER.toString(), orgId);
		BaseRecord adminRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.seriesAdminRolePath(seriesSlug), RoleEnumType.USER.toString(), orgId);
		if(writerRole == null || adminRole == null) {
			throw new PictureBookException(500, "Series '" + seriesSlug + "' is missing its Writer/Admin roles");
		}
		String[] crudperms = new String[] {"Read", "Update", "Create", "Delete"};
		String[] entTypes = new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()};
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), writerRole, groups, crudperms, entTypes);
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), adminRole, groups, crudperms, entTypes);
	}

	// ─────────────────────────────── chapter shadow character group ───────────────────────────────

	/**
	 * A per-chapter SHADOW character group's name. Holds this chapter's overwritable {@code charPerson}
	 * shadows (and their re-homed render-state sub-records), kept OUT of the series baseline
	 * {@code Population} group so the {@code (name, groupId, organizationId)} constraint never collides -
	 * a baseline character and its per-chapter shadows all share the SAME {@code name} by design (the name
	 * is load-bearing prompt/roster text and must be preserved), so they must live in different groups.
	 */
	public static String chapterShadowCharGroupName(String chapterSlug) {
		return "Chapter Population " + chapterSlug;
	}

	/**
	 * {@code {population-parent}/Chapter Population {chapterSlug}} - a SIBLING of the series world's
	 * {@code Population} group, derived from that group's own path so it always lands inside the series
	 * world's group tree.
	 * <p>
	 * <b>Sibling, never a child.</b> {@code common.groupExt}'s {@code groupId} is {@code recursive:true},
	 * so a {@code groupId} query on the baseline {@code Population} group descends into its subgroups - a
	 * shadow group nested under Population would leak its shadow characters into every baseline roster read.
	 * A sibling is outside that recursive scope.
	 *
	 * @param populationPath the series world's {@code Population} group path (from
	 *        {@code BookContext.getGroupPath("population")}); never hardcoded
	 * @param chapterSlug    the chapter book's slug
	 * @return the sibling shadow group path, or null when {@code populationPath} is null
	 */
	public static String chapterShadowCharGroupPath(String populationPath, String chapterSlug) {
		if(populationPath == null || chapterSlug == null) {
			return null;
		}
		String parent = populationPath;
		int idx = populationPath.lastIndexOf('/');
		if(idx > 0) {
			parent = populationPath.substring(0, idx);
		}
		return parent + "/" + chapterShadowCharGroupName(chapterSlug);
	}

	/**
	 * Get-or-create a chapter's shadow character group as a sibling of the series world's
	 * {@code Population} group, grant the series {@code Writer}/{@code Admin} roles CRUD on it, and prove
	 * the grant landed by reading the group back through {@code AccessPoint} as the acting user.
	 * <p>
	 * The group is made as the olio principal (uniform world-group ownership, exactly as the chapter's own
	 * {@code Book}/{@code Workflow}/{@code Artifacts} groups are made in {@link #createBook(BaseRecord,
	 * String, String, String, BaseRecord, Integer)}). Because no {@code initialize()} pass ever touches
	 * this group, nothing else grants it - so the same {@link #grantSeriesRolesOnChapterGroups} sweep that
	 * covers the chapter's container groups is applied here.
	 *
	 * @param user           the acting user; must hold the series {@code Writer}/{@code Admin} role (proven
	 *                       by the read-back)
	 * @param seriesSlug     the series world's slug, to resolve the series role pair to grant
	 * @param populationPath the series world's {@code Population} group path
	 * @param chapterSlug    the chapter book's slug
	 * @param organizationId the organization
	 * @return the shadow character group, read back as {@code user}
	 * @throws PictureBookException 400 on missing args, 500 when the group cannot be created, granted, or
	 *         read back by its creator
	 */
	public static BaseRecord getCreateChapterShadowCharGroup(BaseRecord user, String seriesSlug,
			String populationPath, String chapterSlug, long organizationId) {
		if(user == null || seriesSlug == null || populationPath == null || chapterSlug == null) {
			throw new PictureBookException(400,
				"A chapter shadow character group needs user, seriesSlug, populationPath and chapterSlug");
		}
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx == null) {
			throw new PictureBookException(500, "Failed to find an organization context");
		}
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, organizationId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal in organization " + organizationId);
		}
		String shadowPath = chapterShadowCharGroupPath(populationPath, chapterSlug);
		if(shadowPath == null) {
			throw new PictureBookException(500, "Could not derive a shadow character group path from '" + populationPath + "'");
		}
		BaseRecord group = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, shadowPath,
			GroupEnumType.DATA.toString(), organizationId);
		if(group == null) {
			throw new PictureBookException(500, "Failed to create the chapter shadow character group " + shadowPath);
		}
		/// §8 REQUIRED #2, extended: this NEW group has no world-init grant, so grant the series role pair
		/// CRUD on it - the same sweep the chapter's own container groups get.
		grantSeriesRolesOnChapterGroups(ioContext, octx, organizationId, seriesSlug, new BaseRecord[] {group});

		/// Prove the grant landed: read the group record back as the ACTING user through AccessPoint. A
		/// shadow group the creator cannot read is a failed create - later shadow charPerson reads (which
		/// rely on the group-only PBAC shortcut via a groupId condition) would silently return nothing.
		Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_OBJECT_ID,
			group.get(FieldNames.FIELD_OBJECT_ID));
		gq.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		gq.setCache(false);
		BaseRecord readBack = ioContext.getAccessPoint().find(user, gq);
		if(readBack == null) {
			throw new PictureBookException(500, "Chapter shadow character group " + shadowPath
				+ " was created but is not readable by " + user.get(FieldNames.FIELD_NAME)
				+ " - the series role grants did not reach it");
		}
		return readBack;
	}

	/**
	 * Write the book row. As the olio principal via {@code RecordUtil.createRecord}, because no grant
	 * exists on the group at this point - {@code initialize()} applies them next.
	 * <p>
	 * The name is set <b>explicitly</b>: {@code applyNameGroupOwnership} does not set it on this model
	 * (it gates on {@code common.name}), and a null name defeats the unique
	 * {@code (name, groupId, organizationId)} index because PostgreSQL treats NULLs as distinct.
	 */
	private static BaseRecord writeBookRow(IOContext ioContext, BaseRecord olioUser, String slug, String title,
			String bookGroupPath, long orgId) {
		BaseRecord group = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath,
			GroupEnumType.DATA.toString(), orgId);
		if(group == null) {
			throw new PictureBookException(500, "Failed to resolve the book group " + bookGroupPath);
		}
		String name = bookName(slug, title);
		BaseRecord book = null;
		try {
			book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
			ioContext.getRecordUtil().applyNameGroupOwnership(olioUser, book, name, bookGroupPath, orgId);
			book.set(FieldNames.FIELD_NAME, name);
			book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
			book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
			/// Stamp the variant AT CREATION. Only ChapBookUtil ever set this (patching CHAPBOOK
			/// right after calling here), so every PictureBook was left UNKNOWN — and a book that
			/// never reaches the end of the wizard therefore has real assets (world, gallery,
			/// character portraits) behind a row that type-filtered listings skip, which is why a
			/// half-finished book could not be found or cleaned up. STORY is this enum's documented
			/// "standard PictureBook 2 narrative book"; the ChapBook path overwrites it immediately.
			book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
			if(title != null) {
				book.set(FieldNames.FIELD_DESCRIPTION, title);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a book: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble book '" + slug + "'");
		}
		if(!ioContext.getRecordUtil().createRecord(book)) {
			/// The unique (slug, organizationId) index is the serialization point; a racer lands here.
			throw new PictureBookException(409, "Failed to create book '" + slug + "'."
				+ " The unique (slug, organizationId) index rejects a duplicate, so a concurrent creation of"
				+ " the same slug is the expected cause - no world has been created for this attempt.");
		}
		return book;
	}

	// ─────────────────────────────── read ───────────────────────────────

	/**
	 * The book with {@code slug}, read as {@code user} through {@code AccessPoint}, with the config
	 * columns projected.
	 * <p>
	 * <b>Uncached and with an explicit {@code organizationId} condition.</b> The condition is not
	 * optional: a list query over a {@code data.directory}-derived model without one is denied by PBAC
	 * with "Group could not be found". The value is a <b>number</b> - a string silently matches nothing.
	 */
	public static BaseRecord findBookBySlug(BaseRecord user, String slug, long organizationId) {
		if(user == null || slug == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(bookRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	public static BaseRecord readBook(BaseRecord user, String objectId, long organizationId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(bookRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Resolve a {@link BookContext} from an already-authorized book, <b>by FK, never by path</b>.
	 * <p>
	 * This is the public authorized read entry the phase-1 javadoc promised for phase 2: an
	 * {@code AccessPoint} read of the book (which is where authorization happens), then the
	 * {@code book.world} FK, then {@code assembleBookContext} - which creates nothing and runs no
	 * {@code initialize()}. It satisfies §5.6b's root-reference principle by construction: there is no
	 * step that resolves a book by name as the acting user.
	 *
	 * @return null when the book carries no world yet, or the world cannot be assembled
	 */
	public static BookContext openBookContext(BaseRecord user, BaseRecord book) {
		if(user == null || book == null) {
			return null;
		}
		BaseRecord world = book.get(OlioFieldNames.FIELD_PB_WORLD);
		if(world == null) {
			logger.warn("Book " + book.get(FieldNames.FIELD_NAME) + " has no world reference");
			return null;
		}
		return PbOlioContextUtil.assembleBookContext(world);
	}

	// ─────────────────────────────── scenes ───────────────────────────────

	/**
	 * Create a scene row.
	 * <p>
	 * Replaces PB1's per-scene JSON inside a {@code data.note}: scene order becomes the indexed
	 * {@code sceneIndex} column instead of array position in a blob, so reordering is N patches.
	 * <p>
	 * {@code sceneIndex} is deliberately <b>not</b> part of any unique constraint - reordering writes
	 * overlapping indices transiently and a unique index would reject the intermediate state. The name is
	 * derived from the book slug and the index so it is unique within the group.
	 */
	public static BaseRecord createScene(BaseRecord user, BaseRecord book, int sceneIndex, String title,
			String groupPath) {
		if(book == null) {
			throw new PictureBookException(400, "A scene must belong to a book");
		}
		String name = sceneName(book, sceneIndex);
		BaseRecord scene = null;
		try {
			scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, scene, name, groupPath,
				PbGraphUtil.orgId(book));
			scene.set(FieldNames.FIELD_NAME, name);
			scene.set(OlioFieldNames.FIELD_PB_BOOK, book);
			scene.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, Integer.valueOf(sceneIndex));
			if(title != null) {
				scene.set(OlioFieldNames.FIELD_PB_TITLE, title);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a scene: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble scene " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, scene);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create scene " + name);
		}
		return readScene(user, created.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(book));
	}

	/** The book's scenes, ascending by {@code sceneIndex}. Uncached, so a just-written reorder is visible. */
	public static List<BaseRecord> listScenes(BaseRecord user, BaseRecord book) {
		if(user == null || book == null) {
			return Collections.emptyList();
		}
		/// A condition on a FOREIGN MODEL field takes the RECORD, never its id: Query.field routes the
		/// value through FieldUtil.setFlex, which calls setModel() for a MODEL-typed field, so a Long is
		/// rejected and the condition silently becomes "book = null" - it matches nothing and logs
		/// nothing at the call site. StatementUtil casts the value to BaseRecord and reads its id
		/// (StatementUtil.java:1367). Measured on am7db 2026-08-15.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, book);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(book));
		q.setRequest(sceneRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, OlioFieldNames.FIELD_PB_SCENE_INDEX);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_SCENE)
			: Collections.emptyList());
	}

	/**
	 * Reorder scenes: N patches on {@code sceneIndex}, one per scene, in the order given.
	 * <p>
	 * Every patch carries {@code name} (the writer validates the patch record, not the merged result) and
	 * every update result is asserted. A partial failure is reported as one - it is not rolled back, so
	 * the caller is told exactly how far it got rather than being handed a success it did not receive.
	 *
	 * @param sceneObjectIdsInOrder the scenes' objectIds, in the intended order; index 0 becomes
	 *        {@code sceneIndex = 0}
	 * @return the number of scenes repositioned
	 */
	public static int reorderScenes(BaseRecord user, BaseRecord book, List<String> sceneObjectIdsInOrder) {
		if(sceneObjectIdsInOrder == null || sceneObjectIdsInOrder.isEmpty()) {
			return 0;
		}
		long orgId = PbGraphUtil.orgId(book);
		List<String> failed = new ArrayList<>();
		int moved = 0;
		for(int i = 0; i < sceneObjectIdsInOrder.size(); i++) {
			String oid = sceneObjectIdsInOrder.get(i);
			BaseRecord scene = readScene(user, oid, orgId);
			if(scene == null) {
				failed.add(oid + " (not readable)");
				continue;
			}
			Integer current = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(current != null && current.intValue() == i) {
				continue;
			}
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_SCENE_INDEX);
			try {
				patch.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, Integer.valueOf(i));
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				throw new PictureBookException(500, "Failed to assemble a sceneIndex patch: " + e.getMessage());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				failed.add(oid);
				continue;
			}
			moved++;
		}
		if(!failed.isEmpty()) {
			throw new PictureBookException(500, "Reordered " + moved + " scene(s) but failed on: " + failed
				+ " - the order is now partially applied and was NOT rolled back");
		}
		return moved;
	}

	public static BaseRecord readScene(BaseRecord user, String objectId, long organizationId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(sceneRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Persist a per-scene sparse {@code configOverride}.
	 * <p>
	 * The override is a SPARSE JSON STRING carrying schema {@code olio.sd.config} and only the fields the
	 * caller explicitly set — it is NOT a {@code RecordFactory.newInstance(olio.sd.config)} record, which
	 * would materialize every defaulted field and make "override" indistinguishable from "default" (see
	 * {@link PbConfigUtil} and the {@code configOverride} field description on {@code olio.pb.scene}). A
	 * ChapBook scene has no {@code sceneNode}, so this field is the ONLY way such a scene can feed the top
	 * tier of the config-precedence merge {@link PbConfigUtil#resolveEffectiveConfig} performs at render.
	 * <p>
	 * PATCH-shaped and authorized: identity + the model's validated {@code name} + the changed field only
	 * (via {@link PbGraphUtil#patchOf}, so the writer's validation of the patch record does not reject a
	 * null {@code name}), through {@code AccessPoint.update}. The update result is asserted, never
	 * discarded — a swallowed null there converts a persistent failure into a silent no-op.
	 *
	 * @param user           the acting user (must have WRITE access to the scene)
	 * @param sceneObjectId  objectId of the {@code olio.pb.scene}
	 * @param configOverride the sparse override JSON, or null/blank to clear the field
	 * @return true when the scene was updated
	 * @throws PictureBookException 400 when args are missing or a non-blank override is not valid sparse
	 *         {@code olio.sd.config} JSON; 404 when the scene is not readable by {@code user}
	 */
	public static boolean setSceneConfigOverride(BaseRecord user, String sceneObjectId, String configOverride) {
		if(user == null || sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "user and sceneObjectId are required");
		}
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord scene = readScene(user, sceneObjectId, orgId);
		if(scene == null) {
			throw new PictureBookException(404, "Scene not found: " + sceneObjectId);
		}
		String sparse = (configOverride != null && !configOverride.isBlank()) ? configOverride.trim() : null;
		/// Validate a non-blank override before persisting: parseOverride returns null when the JSON is
		/// unparseable or does not carry schema olio.sd.config, and such a string would be silently dropped
		/// by resolveEffectiveConfig at render time. Reject it loudly here instead of storing dead data.
		if(sparse != null && PbConfigUtil.parseOverride(sparse) == null) {
			throw new PictureBookException(400,
				"configOverride must be sparse " + OlioModelNames.MODEL_SD_CONFIG + " JSON carrying its schema");
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
			OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
		try {
			patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, sparse);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a configOverride patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to persist configOverride on scene " + sceneObjectId);
			return false;
		}
		return true;
	}

	// ─────────────────────────────── status ───────────────────────────────

	/** Persist a book status. Explicit, authorized, PATCH-shaped, result asserted. */
	public static boolean setBookStatus(BaseRecord user, BaseRecord book, PbBookStatusEnumType status) {
		if(book == null || status == null) {
			return false;
		}
		BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK,
			OlioFieldNames.FIELD_PB_BOOK_STATUS);
		try {
			patch.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, status.toString());
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a book status patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to persist bookStatus=" + status + " on book " + book.get(FieldNames.FIELD_NAME));
			return false;
		}
		return true;
	}

	// ─────────────────────────────── names & paths ───────────────────────────────

	/** {@code /Olio/Universes/Books/Worlds/{slug}} - the book world's container group. */
	public static String bookContainerPath(String slug) {
		return PbOlioContextUtil.bookWorldPath() + "/" + slug;
	}

	/** {@code {container}/Book} - where the book row and its scenes live. */
	public static String bookGroupPath(String slug) {
		return bookContainerPath(slug) + "/" + BookWorldInitializationRule.GROUP_BOOK;
	}

	/** {@code {container}/Workflow} - where the workflow, nodes, bindings and runs live. */
	public static String workflowGroupPath(String slug) {
		return bookContainerPath(slug) + "/" + BookWorldInitializationRule.GROUP_WORKFLOW;
	}

	/** {@code {container}/Artifacts} - where artifact provenance records live. */
	public static String artifactGroupPath(String slug) {
		return bookContainerPath(slug) + "/" + BookWorldInitializationRule.GROUP_ARTIFACTS;
	}

	/**
	 * The book record's derived name. Includes the slug, which is unique per organization, so the unique
	 * {@code (name, groupId, organizationId)} index cannot be tripped by two books sharing a title.
	 */
	public static String bookName(String slug, String title) {
		return "Book " + slug;
	}

	/** {@code Scene <slug> <index>} - unique within the book group. */
	public static String sceneName(BaseRecord book, int sceneIndex) {
		Object slug = (book.hasField(OlioFieldNames.FIELD_PB_SLUG) ? book.get(OlioFieldNames.FIELD_PB_SLUG) : null);
		return "Scene " + (slug != null ? slug : book.get(FieldNames.FIELD_OBJECT_ID)) + " " + sceneIndex;
	}

	/**
	 * What to project on a book read. Includes the two serialized config columns, which are NOT query
	 * fields - without them {@code PbConfigUtil.resolveEffectiveConfig} silently sees no book tier and
	 * produces a valid-looking but wrong effective config.
	 */
	public static String[] bookRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_SERIES,
			OlioFieldNames.FIELD_PB_CHAPTER, OlioFieldNames.FIELD_PB_SOURCE_DATA,
			OlioFieldNames.FIELD_PB_SOURCE_RANGE,
			OlioFieldNames.FIELD_PB_SD_CONFIG, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG,
			OlioFieldNames.FIELD_PB_BOOK_STATUS, OlioFieldNames.FIELD_PB_COMPOSITION_CONTEXT,
			OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE,
			OlioFieldNames.FIELD_PB_FIX_PAGE_HEIGHT
		};
	}

	public static String[] sceneRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_SCENE_INDEX, OlioFieldNames.FIELD_PB_TITLE,
			OlioFieldNames.FIELD_PB_SUMMARY, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION,
			OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_PB_BLURB, OlioFieldNames.FIELD_PB_USER_EDITED,
			OlioFieldNames.FIELD_PB_SCENE_NODE,
			OlioFieldNames.FIELD_CB_POEM_STANZA,
			OlioFieldNames.FIELD_CB_SD_PROMPT,
			OlioFieldNames.FIELD_PB_PROMPT_LOCKED,
			OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID,
			OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE,
			OlioFieldNames.FIELD_PB_PAGE_FONT,
			OlioFieldNames.FIELD_PB_PAGE_BG_COLOR,
			OlioFieldNames.FIELD_PB_PAGE_BG_OPACITY,
			OlioFieldNames.FIELD_PB_PAGE_TEXT_ALIGN,
			OlioFieldNames.FIELD_PB_PAGE_TEXT_COLOR,
			OlioFieldNames.FIELD_PB_IMAGE_STALE
		};
	}
}
