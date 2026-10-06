package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.rules.BookWorldInitializationRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Finds and removes the records a failed extraction, a crashed render or the pre-fix PictureBook delete
 * leaves behind. {@link #scan} is read-only (creates nothing, never acts as the organization admin);
 * {@link #purge} re-runs the scan and acts on exactly that item set, so a dry run and an apply cannot
 * diverge.
 * <p>
 * Scope: {@code own} is what the caller left behind - books they created, their own
 * {@code ~/Data/PictureBooks} trees, notes and source ranges they own, and the worlds, containers, graph
 * rows and roles of slugs attributable to them (a book they created, a meta note linking to it, or a
 * per-book Writer/Admin role they are a member of). {@code org} covers the whole organization and
 * requires {@code AccountAdministrators}.
 * <p>
 * Dead foreign keys read back as {@code null} under the PostgreSQL model-mode reader, so a PB2 graph row
 * is an orphan when a key that is never legitimately null is null: {@code workflow.book},
 * {@code scene.book}, {@code node.workflow}, {@code run.workflow}, {@code binding.node}, and a cast group
 * with neither {@code book} nor {@code series}. Artifacts are not scanned - {@code producedByNode} is
 * legitimately null - and go with their workflow.
 */
public class PbOrphanUtil {
	private static final Logger logger = LogManager.getLogger(PbOrphanUtil.class);

	public static final String ORPHAN_CHECKPOINT = "ORPHAN_CHECKPOINT";
	public static final String ORPHAN_META_NOTE = "ORPHAN_META_NOTE";
	public static final String ORPHAN_UX_GROUP = "ORPHAN_UX_GROUP";
	public static final String ORPHAN_PB2_ROW = "ORPHAN_PB2_ROW";
	public static final String ORPHAN_SOURCE_RANGE = "ORPHAN_SOURCE_RANGE";
	public static final String ORPHAN_BOOK_ROW = "ORPHAN_BOOK_ROW";
	public static final String ORPHAN_CHAPTER_CONTAINER = "ORPHAN_CHAPTER_CONTAINER";
	public static final String ORPHAN_WORLD = "ORPHAN_WORLD";
	public static final String ORPHAN_BOOK_ROLE = "ORPHAN_BOOK_ROLE";

	/** Purge order: leaves before the containers that hold them, worlds before the roles granted on them. */
	public static final String[] PURGE_ORDER = new String[] {
		ORPHAN_CHECKPOINT, ORPHAN_META_NOTE, ORPHAN_UX_GROUP, ORPHAN_PB2_ROW, ORPHAN_SOURCE_RANGE,
		ORPHAN_BOOK_ROW, ORPHAN_CHAPTER_CONTAINER, ORPHAN_WORLD, ORPHAN_BOOK_ROLE
	};

	public static final String OUTCOME_DELETED = "deleted";
	public static final String OUTCOME_DENIED = "denied";
	public static final String OUTCOME_FAILED = "failed";

	private static final String[] CONTAINER_CHILD_GROUPS = new String[] {
		BookWorldInitializationRule.GROUP_BOOK, BookWorldInitializationRule.GROUP_WORKFLOW, BookWorldInitializationRule.GROUP_ARTIFACTS
	};

	private PbOrphanUtil() {}

	// ─────────────────────────────── public API ───────────────────────────────

	/** Human-readable singular noun for a category code, for messages and the UI. */
	public static String describe(String code) {
		switch(code == null ? "" : code) {
			case ORPHAN_CHECKPOINT: return "extraction checkpoint whose document is gone";
			case ORPHAN_META_NOTE: return "book meta note linked to a deleted book";
			case ORPHAN_UX_GROUP: return "PictureBooks folder with no live book";
			case ORPHAN_PB2_ROW: return "workflow/scene graph row pointing at a deleted record";
			case ORPHAN_SOURCE_RANGE: return "source range no book references";
			case ORPHAN_BOOK_ROW: return "book row with no world";
			case ORPHAN_CHAPTER_CONTAINER: return "world container group with no book";
			case ORPHAN_WORLD: return "book world with no book row";
			case ORPHAN_BOOK_ROLE: return "per-book role with no book";
			default: return "record";
		}
	}

	/**
	 * Read-only orphan report.
	 *
	 * @return {@code {scope, checkedAt, categories:[{code, count, items:[{code, model, id, objectId, name,
	 *         path, slug, reason}]}], total, warnings}}
	 * @throws PictureBookException 401 without a user, 403 for {@code orgWide} by a non-administrator
	 */
	public static Map<String, Object> scan(BaseRecord user, boolean orgWide) {
		long orgId = requireScope(user, orgWide);
		Scan s = doScan(user, orgId, orgWide);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", orgWide ? "org" : "own");
		out.put("checkedAt", ZonedDateTime.now().toString());
		List<Map<String, Object>> categories = new ArrayList<>();
		int total = 0;
		for(String code : PURGE_ORDER) {
			List<Item> items = s.items(code);
			Map<String, Object> cat = new LinkedHashMap<>();
			cat.put("code", code);
			cat.put("count", items.size());
			List<Map<String, Object>> rows = new ArrayList<>();
			for(Item it : items) {
				rows.add(it.toMap());
			}
			cat.put("items", rows);
			categories.add(cat);
			total += items.size();
		}
		out.put("categories", categories);
		out.put("total", total);
		out.put("warnings", new ArrayList<>(s.warnings));
		return out;
	}

	/**
	 * Re-scan, then remove every item in the selected categories ({@code codes} null or empty = all).
	 *
	 * @return {@code {scope, purgedAt, codes, results:[item + outcome, reason, count], deleted, denied,
	 *         failed, cleanupOrphansRan, warnings}}
	 * @throws PictureBookException 401 without a user, 403 for {@code orgWide} by a non-administrator
	 */
	public static Map<String, Object> purge(BaseRecord user, boolean orgWide, List<String> codes) {
		long orgId = requireScope(user, orgWide);
		Set<String> selected = new LinkedHashSet<>();
		if(codes == null || codes.isEmpty()) {
			selected.addAll(Arrays.asList(PURGE_ORDER));
		}
		else {
			for(String c : codes) {
				if(c != null && Arrays.asList(PURGE_ORDER).contains(c)) {
					selected.add(c);
				}
			}
		}
		Scan s = doScan(user, orgId, orgWide);
		List<Map<String, Object>> results = new ArrayList<>();
		int deleted = 0;
		int denied = 0;
		int failed = 0;
		for(String code : PURGE_ORDER) {
			if(!selected.contains(code)) {
				continue;
			}
			for(Item it : s.items(code)) {
				Outcome o;
				try {
					o = purgeItem(user, orgId, s, it);
				}
				catch(PictureBookException e) {
					o = (e.getStatus() == 403) ? Outcome.denied(e.getMessage()) : Outcome.failed(e.getMessage());
				}
				catch(Exception e) {
					logger.warn("Purge of " + it.code + " " + it.model + " " + it.objectId() + " failed: " + e.getMessage());
					o = Outcome.failed(e.getMessage());
				}
				Map<String, Object> row = it.toMap();
				row.put("outcome", o.outcome);
				if(o.reason != null) {
					row.put("reason", o.reason);
				}
				row.put("count", o.count);
				results.add(row);
				if(OUTCOME_DELETED.equals(o.outcome)) {
					deleted++;
				}
				else if(OUTCOME_DENIED.equals(o.outcome)) {
					denied++;
				}
				else {
					failed++;
				}
			}
		}
		boolean swept = false;
		if(!results.isEmpty()) {
			swept = RecordFactory.cleanupOrphansExplained(null);
			CacheUtil.clearCache();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", orgWide ? "org" : "own");
		out.put("purgedAt", ZonedDateTime.now().toString());
		out.put("codes", new ArrayList<>(selected));
		out.put("results", results);
		out.put("deleted", deleted);
		out.put("denied", denied);
		out.put("failed", failed);
		out.put("cleanupOrphansRan", swept);
		out.put("warnings", new ArrayList<>(s.warnings));
		logger.info("Orphan purge (" + (orgWide ? "org" : "own") + ") by " + user.get(FieldNames.FIELD_NAME)
			+ ": deleted=" + deleted + " denied=" + denied + " failed=" + failed);
		return out;
	}

	// ─────────────────────────────── scan ───────────────────────────────

	private static final class Item {
		final String code;
		final BaseRecord rec;
		final String model;
		final String path;
		final String slug;
		final String reason;

		Item(String code, BaseRecord rec, String model, String path, String slug, String reason) {
			this.code = code;
			this.rec = rec;
			this.model = model;
			this.path = path;
			this.slug = slug;
			this.reason = reason;
		}

		String objectId() {
			return rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", code);
			m.put("model", model);
			if(rec != null) {
				m.put("id", rec.get(FieldNames.FIELD_ID));
				m.put("objectId", objectId());
				m.put("name", rec.hasField(FieldNames.FIELD_NAME) ? rec.get(FieldNames.FIELD_NAME) : null);
			}
			if(path != null) {
				m.put("path", path);
			}
			if(slug != null) {
				m.put("slug", slug);
			}
			m.put("reason", reason);
			return m;
		}
	}

	private static final class Scan {
		final boolean orgWide;
		final Map<String, List<Item>> byCode = new LinkedHashMap<>();
		final List<String> warnings = new ArrayList<>();
		BaseRecord olioUser;
		/// reference sets
		final Set<String> bookSlugs = new HashSet<>();
		final Set<String> bookOids = new HashSet<>();
		final Set<Long> referencedRangeIds = new HashSet<>();
		final Map<String, BaseRecord> bookByOid = new HashMap<>();
		final List<BaseRecord> books = new ArrayList<>();
		final Set<String> seriesSlugs = new HashSet<>();
		final Set<String> worldNames = new HashSet<>();
		final Set<String> orphanWorldNames = new HashSet<>();
		final List<BaseRecord> worlds = new ArrayList<>();
		final List<BaseRecord> containers = new ArrayList<>();
		final Set<String> ownSlugs = new HashSet<>();
		final Set<Long> flaggedUxGroupIds = new HashSet<>();
		final Map<Long, BaseRecord> groupCache = new HashMap<>();

		Scan(boolean orgWide) {
			this.orgWide = orgWide;
			for(String code : PURGE_ORDER) {
				byCode.put(code, new ArrayList<>());
			}
		}

		List<Item> items(String code) {
			return byCode.get(code);
		}

		void add(Item it) {
			byCode.get(it.code).add(it);
		}

		void warn(String w) {
			warnings.add(w);
			logger.warn("Orphan scan: " + w);
		}

		/** Names a book, series or surviving world carries; an orphan world does not make its name live. */
		boolean isLiveName(String name) {
			return name != null && (bookSlugs.contains(name) || seriesSlugs.contains(name)
				|| (worldNames.contains(name) && !orphanWorldNames.contains(name)));
		}

		boolean inScope(String slug) {
			return orgWide || (slug != null && ownSlugs.contains(slug));
		}
	}

	private static Scan doScan(BaseRecord user, long orgId, boolean orgWide) {
		Scan s = new Scan(orgWide);
		IOContext ioContext = IOSystem.getActiveContext();
		s.olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		loadReferences(user, orgId, s);

		scanCheckpoints(user, orgId, s);
		scanUxGroups(user, orgId, s);
		scanMetaNotes(user, orgId, s);
		scanSourceRanges(user, orgId, s);
		if(s.olioUser == null) {
			s.warn("Olio principal not found in organization " + orgId + "; world, container, graph-row, book-row and role scans skipped");
			return s;
		}
		attributeSlugsByRole(user, orgId, s);
		scanPb2Rows(orgId, s);
		scanBookRows(user, orgId, s);
		scanWorlds(orgId, s);
		scanContainers(s);
		scanRoles(orgId, s);
		return s;
	}

	private static void loadReferences(BaseRecord user, long orgId, Scan s) {
		String userOid = user.get(FieldNames.FIELD_OBJECT_ID);
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, OlioFieldNames.FIELD_PB_WORLD,
			OlioFieldNames.FIELD_PB_SERIES, OlioFieldNames.FIELD_PB_SOURCE_RANGE, OlioFieldNames.FIELD_PB_BOOK_STATUS});
		for(BaseRecord b : raw(bq, OlioModelNames.MODEL_PB_BOOK)) {
			s.books.add(b);
			String slug = b.get(OlioFieldNames.FIELD_PB_SLUG);
			String oid = b.get(FieldNames.FIELD_OBJECT_ID);
			if(slug != null) {
				s.bookSlugs.add(slug);
			}
			if(oid != null) {
				s.bookOids.add(oid);
				s.bookByOid.put(oid, b);
			}
			long rangeId = PbHealthUtil.fkId(b, OlioFieldNames.FIELD_PB_SOURCE_RANGE);
			if(rangeId > 0L) {
				s.referencedRangeIds.add(rangeId);
			}
			if(slug != null && userOid != null && userOid.equals(b.get(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID))) {
				s.ownSlugs.add(slug);
			}
		}

		Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sq.setRequest(PbSeriesUtil.seriesRequest());
		for(BaseRecord ser : raw(sq, OlioModelNames.MODEL_PB_SERIES)) {
			String slug = PbSeriesUtil.seriesSlug(ser);
			if(slug != null) {
				s.seriesSlugs.add(slug);
			}
		}

		if(s.olioUser == null) {
			return;
		}
		BaseRecord worldsGroup = IOSystem.getActiveContext().getPathUtil().findPath(s.olioUser, ModelNames.MODEL_GROUP,
			PbOlioContextUtil.bookWorldPath(), GroupEnumType.DATA.toString(), orgId);
		if(worldsGroup == null) {
			return;
		}
		Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_GROUP_ID, worldsGroup.get(FieldNames.FIELD_ID));
		wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		wq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		for(BaseRecord w : raw(wq, OlioModelNames.MODEL_WORLD)) {
			s.worlds.add(w);
			String name = w.get(FieldNames.FIELD_NAME);
			if(name != null) {
				s.worldNames.add(name);
			}
		}
		s.containers.addAll(childGroups(worldsGroup, orgId));
	}

	/** Checkpoint notes whose work document no longer exists (any owner when org-wide). */
	private static void scanCheckpoints(BaseRecord user, long orgId, Scan s) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, PictureBookUtil.EXTRACT_PROGRESS_NOTE + ".%");
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		if(!s.orgWide) {
			q.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		}
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TEXT, FieldNames.FIELD_MODIFIED_DATE});
		Map<String, Boolean> workExists = new HashMap<>();
		for(BaseRecord note : raw(q, ModelNames.MODEL_NOTE)) {
			Map<String, Object> row = PictureBookUtil.describeCheckpointNote(note);
			if(row == null) {
				continue;
			}
			String wid = (String) row.get("workObjectId");
			if(wid == null || wid.isBlank()) {
				continue;
			}
			boolean exists = workExists.computeIfAbsent(wid, k -> rawById(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, k, orgId) != null
				|| rawById(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, k, orgId) != null);
			if(!exists) {
				s.add(new Item(ORPHAN_CHECKPOINT, note, ModelNames.MODEL_NOTE, null, null,
					"Extraction checkpoint for work document " + wid + ", which no longer exists"));
			}
		}
	}

	/**
	 * {@code ~/Data/PictureBooks/<name>} groups with no {@code .pictureBookMeta} note (an extraction that
	 * never completed) or whose meta links to a PB2 book that is gone. A meta without a PB2 link (legacy
	 * PB1) is never flagged. Also attributes meta-linked slugs to the caller for the own scope.
	 */
	private static void scanUxGroups(BaseRecord user, long orgId, Scan s) {
		List<BaseRecord> roots = new ArrayList<>();
		if(s.orgWide) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_NAME, PictureBookUtil.PICTURE_BOOKS_DIR);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(groupFields());
			for(BaseRecord g : raw(q, ModelNames.MODEL_GROUP)) {
				String path = groupPathOf(g, orgId, s.groupCache);
				if(path.endsWith("/Data/" + PictureBookUtil.PICTURE_BOOKS_DIR)) {
					roots.add(g);
				}
			}
		}
		else {
			BaseRecord root = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP,
				"~/Data/" + PictureBookUtil.PICTURE_BOOKS_DIR, GroupEnumType.DATA.toString(), orgId);
			if(root != null) {
				roots.add(root);
			}
		}
		for(BaseRecord root : roots) {
			String rootPath = groupPathOf(root, orgId, s.groupCache);
			for(BaseRecord grp : childGroups(root, orgId)) {
				String path = rootPath + "/" + grp.get(FieldNames.FIELD_NAME);
				BaseRecord meta = rawMetaNote(grp, orgId);
				if(meta == null) {
					s.add(new Item(ORPHAN_UX_GROUP, grp, ModelNames.MODEL_GROUP, path, null,
						"No .pictureBookMeta note: left by an extraction that failed or was abandoned (an extraction still in progress also looks like this)"));
					s.flaggedUxGroupIds.add(PbHealthUtil.idOf(grp));
					continue;
				}
				String link = metaBookLink(meta);
				if(link == null) {
					continue;
				}
				if(!s.bookOids.contains(link)) {
					s.add(new Item(ORPHAN_UX_GROUP, grp, ModelNames.MODEL_GROUP, path, null,
						"Linked PB2 book " + link + " no longer exists"));
					s.flaggedUxGroupIds.add(PbHealthUtil.idOf(grp));
				}
				else if(!s.orgWide) {
					BaseRecord book = s.bookByOid.get(link);
					String slug = book != null ? book.get(OlioFieldNames.FIELD_PB_SLUG) : null;
					if(slug != null) {
						s.ownSlugs.add(slug);
					}
				}
			}
		}
	}

	/** Meta notes linked to a gone PB2 book, outside the groups already flagged. */
	private static void scanMetaNotes(BaseRecord user, long orgId, Scan s) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, PictureBookUtil.META_NOTE_NAME);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		if(!s.orgWide) {
			q.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		}
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_NAME, FieldNames.FIELD_TEXT});
		for(BaseRecord note : raw(q, ModelNames.MODEL_NOTE)) {
			Object gid = note.get(FieldNames.FIELD_GROUP_ID);
			if(gid instanceof Number && s.flaggedUxGroupIds.contains(((Number) gid).longValue())) {
				continue;
			}
			String link = metaBookLink(note);
			if(link != null && !s.bookOids.contains(link)) {
				s.add(new Item(ORPHAN_META_NOTE, note, ModelNames.MODEL_NOTE, null, null,
					"Meta note links to PB2 book " + link + ", which no longer exists"));
			}
		}
	}

	/** Source ranges no book references (the model has no back-reference, so a deleted chapter strands them). */
	private static void scanSourceRanges(BaseRecord user, long orgId, Scan s) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SOURCE_RANGE, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		if(!s.orgWide) {
			q.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		}
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_TITLE, OlioFieldNames.FIELD_PB_START_OFFSET, OlioFieldNames.FIELD_PB_END_OFFSET});
		for(BaseRecord r : raw(q, OlioModelNames.MODEL_PB_SOURCE_RANGE)) {
			if(!s.referencedRangeIds.contains(PbHealthUtil.idOf(r))) {
				Object so = r.get(OlioFieldNames.FIELD_PB_START_OFFSET);
				Object eo = r.get(OlioFieldNames.FIELD_PB_END_OFFSET);
				s.add(new Item(ORPHAN_SOURCE_RANGE, r, OlioModelNames.MODEL_PB_SOURCE_RANGE, null, null,
					"Source range [" + so + ", " + eo + "] is referenced by no book (a chapter being created right now links its range a moment after writing it)"));
			}
		}
	}

	/** A per-book Writer/Admin role the caller belongs to names a slug the caller may clean up. */
	private static void attributeSlugsByRole(BaseRecord user, long orgId, Scan s) {
		if(s.orgWide) {
			return;
		}
		for(SlugRole sr : slugRoles(orgId, s)) {
			for(BaseRecord child : childRoles(sr.role, orgId)) {
				if(IOSystem.getActiveContext().getMemberUtil().isMember(user, child, null)) {
					s.ownSlugs.add(sr.role.get(FieldNames.FIELD_NAME));
					break;
				}
			}
		}
	}

	/** PB2 graph rows in the Book/Workflow/Artifacts groups of each container whose never-null key is null. */
	private static void scanPb2Rows(long orgId, Scan s) {
		for(BaseRecord container : s.containers) {
			String slug = container.get(FieldNames.FIELD_NAME);
			if(!s.inScope(slug)) {
				continue;
			}
			List<BaseRecord> groups = new ArrayList<>();
			for(BaseRecord g : childGroups(container, orgId)) {
				if(Arrays.asList(CONTAINER_CHILD_GROUPS).contains(g.get(FieldNames.FIELD_NAME))) {
					groups.add(g);
				}
			}
			if(groups.isEmpty()) {
				continue;
			}
			BaseRecord[] grpArr = groups.toArray(new BaseRecord[0]);
			String basePath = PbBookUtil.bookContainerPath(slug);
			flagNullFk(s, PbHealthUtil.rowsInGroups(OlioModelNames.MODEL_PB_WORKFLOW, grpArr, orgId, OlioFieldNames.FIELD_PB_BOOK),
				OlioModelNames.MODEL_PB_WORKFLOW, OlioFieldNames.FIELD_PB_BOOK, basePath, slug, groups);
			flagNullFk(s, PbHealthUtil.rowsInGroups(OlioModelNames.MODEL_PB_SCENE, grpArr, orgId, OlioFieldNames.FIELD_PB_BOOK),
				OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, basePath, slug, groups);
			flagNullFk(s, PbHealthUtil.rowsInGroups(OlioModelNames.MODEL_PB_NODE, grpArr, orgId, OlioFieldNames.FIELD_PB_WORKFLOW),
				OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_WORKFLOW, basePath, slug, groups);
			flagNullFk(s, PbHealthUtil.rowsInGroups(OlioModelNames.MODEL_PB_RUN, grpArr, orgId, OlioFieldNames.FIELD_PB_WORKFLOW),
				OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, basePath, slug, groups);
			flagNullFk(s, PbHealthUtil.rowsInGroups(OlioModelNames.MODEL_PB_BINDING, grpArr, orgId, OlioFieldNames.FIELD_PB_NODE),
				OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE, basePath, slug, groups);
			for(BaseRecord cg : PbHealthUtil.rowsInGroups(OlioModelNames.MODEL_PB_CAST_GROUP, grpArr, orgId, OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_SERIES)) {
				if(PbHealthUtil.fkId(cg, OlioFieldNames.FIELD_PB_BOOK) == 0L && PbHealthUtil.fkId(cg, OlioFieldNames.FIELD_PB_SERIES) == 0L) {
					s.add(new Item(ORPHAN_PB2_ROW, cg, OlioModelNames.MODEL_PB_CAST_GROUP, rowPath(basePath, cg, groups), slug,
						"Cast group bound to neither a book nor a series (its book was deleted)"));
				}
			}
		}
	}

	private static void flagNullFk(Scan s, List<BaseRecord> rows, String model, String fk, String basePath, String slug, List<BaseRecord> groups) {
		for(BaseRecord r : rows) {
			if(PbHealthUtil.fkId(r, fk) == 0L) {
				s.add(new Item(ORPHAN_PB2_ROW, r, model, rowPath(basePath, r, groups), slug,
					model.substring(model.lastIndexOf('.') + 1) + "." + fk + " points at a deleted record"));
			}
		}
	}

	private static String rowPath(String basePath, BaseRecord row, List<BaseRecord> groups) {
		Object gid = row.get(FieldNames.FIELD_GROUP_ID);
		for(BaseRecord g : groups) {
			if(gid instanceof Number && PbHealthUtil.idOf(g) == ((Number) gid).longValue()) {
				return basePath + "/" + g.get(FieldNames.FIELD_NAME);
			}
		}
		return basePath;
	}

	/** Book rows with no world and no world of their slug - the create that never finished, or a world deleted underneath. */
	private static void scanBookRows(BaseRecord user, long orgId, Scan s) {
		String userOid = user.get(FieldNames.FIELD_OBJECT_ID);
		for(BaseRecord b : s.books) {
			String slug = b.get(OlioFieldNames.FIELD_PB_SLUG);
			if(slug == null) {
				continue;
			}
			if(!s.orgWide && (userOid == null || !userOid.equals(b.get(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID)))) {
				continue;
			}
			if(PbHealthUtil.fkId(b, OlioFieldNames.FIELD_PB_WORLD) != 0L) {
				continue;
			}
			if(WorldUtil.findWorld(s.olioUser, PbOlioContextUtil.bookWorldPath(), slug) != null) {
				continue;
			}
			s.add(new Item(ORPHAN_BOOK_ROW, b, OlioModelNames.MODEL_PB_BOOK, PbBookUtil.bookGroupPath(slug), slug,
				"Book row has no world and no world named '" + slug + "' exists (status " + b.get(OlioFieldNames.FIELD_PB_BOOK_STATUS)
				+ "); a book whose extraction is running right now looks like this until its world is built - do not purge one you just started"));
		}
	}

	/** Containers under Worlds named by no book, series or world; an orphan world's container goes with that world's teardown. */
	private static void scanContainers(Scan s) {
		for(BaseRecord c : s.containers) {
			String name = c.get(FieldNames.FIELD_NAME);
			if(s.isLiveName(name) || s.worldNames.contains(name) || !s.inScope(name)) {
				continue;
			}
			s.add(new Item(ORPHAN_CHAPTER_CONTAINER, c, ModelNames.MODEL_GROUP, PbBookUtil.bookContainerPath(name), name,
				"Container group for '" + name + "' with no book, series or world of that name"));
		}
	}

	/** Worlds no book references, that are not a series world, and whose name is no live slug. */
	private static void scanWorlds(long orgId, Scan s) {
		for(BaseRecord w : s.worlds) {
			String name = w.get(FieldNames.FIELD_NAME);
			if(name == null || s.bookSlugs.contains(name) || s.seriesSlugs.contains(name) || !s.inScope(name)) {
				continue;
			}
			if(!PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_WORLD, w, orgId).isEmpty()) {
				continue;
			}
			if(PbSeriesUtil.isSeriesWorld(w)) {
				continue;
			}
			s.orphanWorldNames.add(name);
			s.add(new Item(ORPHAN_WORLD, w, OlioModelNames.MODEL_WORLD, PbBookUtil.bookContainerPath(name), name,
				"Book world '" + name + "' with no book row (left by a delete that removed only the row)"));
		}
	}

	/** Per-slug role containers under the book and series role bases with no live slug of that name. */
	private static void scanRoles(long orgId, Scan s) {
		for(SlugRole sr : slugRoles(orgId, s)) {
			String name = sr.role.get(FieldNames.FIELD_NAME);
			if(s.isLiveName(name) || !s.inScope(name)) {
				continue;
			}
			s.add(new Item(ORPHAN_BOOK_ROLE, sr.role, ModelNames.MODEL_ROLE, sr.base + "/" + name, name,
				"Role container for '" + name + "' (Writer/Admin) with no book, series or world of that name"));
		}
	}

	private static final class SlugRole {
		final BaseRecord role;
		final String base;

		SlugRole(BaseRecord role, String base) {
			this.role = role;
			this.base = base;
		}
	}

	/** The slug-named children of the book and series role bases; Reader/Writer (not slugs) are skipped. */
	private static List<SlugRole> slugRoles(long orgId, Scan s) {
		List<SlugRole> out = new ArrayList<>();
		for(String base : new String[] {PbOlioContextUtil.BOOK_ROLE_BASE, PbOlioContextUtil.SERIES_ROLE_BASE}) {
			BaseRecord baseRole = IOSystem.getActiveContext().getPathUtil().findPath(s.olioUser, ModelNames.MODEL_ROLE, base,
				RoleEnumType.USER.toString(), orgId);
			if(baseRole == null) {
				continue;
			}
			for(BaseRecord r : childRoles(baseRole, orgId)) {
				String name = r.get(FieldNames.FIELD_NAME);
				if(name == null || !PbOlioContextUtil.BOOK_SLUG_PATTERN.matcher(name).matches()) {
					continue;
				}
				out.add(new SlugRole(r, base));
			}
		}
		return out;
	}

	private static List<BaseRecord> childRoles(BaseRecord parent, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_ROLE, FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_TYPE});
		return raw(q, ModelNames.MODEL_ROLE);
	}

	// ─────────────────────────────── purge ───────────────────────────────

	private static final class Outcome {
		final String outcome;
		final String reason;
		final int count;

		Outcome(String outcome, String reason, int count) {
			this.outcome = outcome;
			this.reason = reason;
			this.count = count;
		}

		static Outcome deleted(int count) {
			return new Outcome(OUTCOME_DELETED, null, count);
		}

		static Outcome denied(String reason) {
			return new Outcome(OUTCOME_DENIED, reason, 0);
		}

		static Outcome failed(String reason) {
			return new Outcome(OUTCOME_FAILED, reason, 0);
		}

		static Outcome of(DeleteResult d, int count) {
			if(d.deleted) {
				return deleted(count);
			}
			return d.authorized ? failed(d.reason) : denied(d.reason);
		}
	}

	private static Outcome purgeItem(BaseRecord user, long orgId, Scan s, Item it) {
		BaseRecord olio = s.olioUser != null ? s.olioUser : user;
		switch(it.code) {
			case ORPHAN_CHECKPOINT:
			case ORPHAN_META_NOTE:
				return Outcome.of(PictureBookUtil.deleteRecordExplained(user, it.rec), 1);
			case ORPHAN_UX_GROUP: {
				boolean ok = PictureBookUtil.deleteGroupRecursive(user, it.rec);
				return ok ? Outcome.deleted(1) : Outcome.failed("Recursive delete of " + it.path + " failed; see server log");
			}
			case ORPHAN_PB2_ROW:
				return purgeGraphRow(user, olio, orgId, it);
			case ORPHAN_SOURCE_RANGE: {
				DeleteResult d = PictureBookUtil.deleteRecordExplained(user, it.rec);
				if(!d.deleted && olio != user) {
					d = PictureBookUtil.deleteRecordExplained(olio, it.rec);
				}
				return Outcome.of(d, 1);
			}
			case ORPHAN_BOOK_ROW: {
				try {
					DeleteResult d = PbDeleteUtil.deleteBookComplete(user, it.objectId());
					return Outcome.of(d, 1);
				}
				catch(PictureBookException e) {
					if(e.getStatus() == 404) {
						return Outcome.deleted(0);
					}
					throw e;
				}
			}
			case ORPHAN_CHAPTER_CONTAINER: {
				boolean ok = PictureBookUtil.deleteGroupRecursive(olio, it.rec);
				return ok ? Outcome.deleted(1) : Outcome.failed("Recursive delete of " + it.path + " failed; see server log");
			}
			case ORPHAN_WORLD:
				return purgeWorld(olio, orgId, it);
			case ORPHAN_BOOK_ROLE:
				return purgeRole(olio, orgId, it);
			default:
				return Outcome.failed("Unknown orphan category " + it.code);
		}
	}

	private static Outcome purgeGraphRow(BaseRecord user, BaseRecord olio, long orgId, Item it) {
		DeleteResult res = DeleteResult.ok();
		int n;
		switch(it.model) {
			case OlioModelNames.MODEL_PB_WORKFLOW:
				n = PbDeleteUtil.deleteWorkflowGraph(olio, it.rec, orgId, res);
				break;
			case OlioModelNames.MODEL_PB_NODE: {
				n = 0;
				List<BaseRecord> deps = new ArrayList<>();
				deps.addAll(PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE, it.rec, orgId));
				deps.addAll(PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_SOURCE_NODE, it.rec, orgId));
				deps.addAll(PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, it.rec, orgId));
				for(BaseRecord dep : deps) {
					if(deleteAsEither(olio, user, dep).deleted) {
						n++;
					}
				}
				DeleteResult d = deleteAsEither(olio, user, it.rec);
				if(!d.deleted) {
					return Outcome.of(d, n);
				}
				n++;
				break;
			}
			default: {
				DeleteResult d = deleteAsEither(olio, user, it.rec);
				return Outcome.of(d, d.deleted ? 1 : 0);
			}
		}
		if(!res.deleted) {
			return Outcome.failed(res.reason != null ? res.reason : "one or more graph rows were not deleted");
		}
		return Outcome.deleted(n);
	}

	private static DeleteResult deleteAsEither(BaseRecord first, BaseRecord second, BaseRecord rec) {
		DeleteResult d = PictureBookUtil.deleteRecordExplained(first, rec);
		if(!d.deleted && second != first) {
			d = PictureBookUtil.deleteRecordExplained(second, rec);
		}
		return d;
	}

	/** World + its container tree through the same teardown a complete delete uses, via a slug-only stand-in. */
	private static Outcome purgeWorld(BaseRecord olio, long orgId, Item it) {
		if(PbSeriesUtil.isSeriesWorld(it.rec)) {
			return Outcome.failed("World '" + it.slug + "' is now a series world; refusing to delete it");
		}
		if(!PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_WORLD, it.rec, orgId).isEmpty()) {
			return Outcome.failed("World '" + it.slug + "' is now referenced by a book; refusing to delete it");
		}
		BaseRecord standIn;
		try {
			standIn = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {OlioFieldNames.FIELD_PB_SLUG});
			standIn.set(OlioFieldNames.FIELD_PB_SLUG, it.slug);
		}
		catch(Exception e) {
			return Outcome.failed("Could not build the teardown stand-in for '" + it.slug + "': " + e.getMessage());
		}
		return Outcome.of(PictureBookUtil.teardownBookFootprintAsOlio(olio, standIn, orgId), 1);
	}

	private static Outcome purgeRole(BaseRecord olio, long orgId, Item it) {
		int n = 0;
		for(BaseRecord child : childRoles(it.rec, orgId)) {
			DeleteResult d = PictureBookUtil.deleteRecordExplained(olio, child);
			if(!d.deleted) {
				return Outcome.of(d, n);
			}
			n++;
		}
		DeleteResult d = PictureBookUtil.deleteRecordExplained(olio, it.rec);
		return Outcome.of(d, d.deleted ? n + 1 : n);
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static long requireScope(BaseRecord user, boolean orgWide) {
		if(user == null) {
			throw new PictureBookException(401, "Not authenticated");
		}
		Object o = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(!(o instanceof Number)) {
			throw new PictureBookException(500, "User record carries no organizationId");
		}
		long orgId = ((Number) o).longValue();
		if(orgWide && !PbHealthUtil.isAccountAdministrator(user, orgId)) {
			throw new PictureBookException(403, "Organization-wide orphan cleanup requires AccountAdministrators");
		}
		return orgId;
	}

	private static List<BaseRecord> raw(Query q, String model) {
		List<BaseRecord> out = new ArrayList<>();
		try {
			q.setCache(false);
			BaseRecord[] rows = IOSystem.getActiveContext().getSearch().findRecords(q);
			if(rows != null) {
				for(BaseRecord r : rows) {
					if(r.getSchema() == null) {
						r.setSchema(model);
					}
					out.add(r);
				}
			}
		}
		catch(Exception e) {
			logger.warn("Raw scan of " + model + " failed: " + e.getMessage());
		}
		return out;
	}

	private static BaseRecord rawById(String model, String field, Object value, long orgId) {
		Query q = QueryUtil.createQuery(model, field, value);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		try {
			BaseRecord r = IOSystem.getActiveContext().getSearch().findRecord(q);
			if(r != null && r.getSchema() == null) {
				r.setSchema(model);
			}
			return r;
		}
		catch(Exception e) {
			logger.warn("Raw lookup of " + model + " by " + field + " failed: " + e.getMessage());
			return null;
		}
	}

	private static String[] groupFields() {
		return new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID,
			FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID};
	}

	private static List<BaseRecord> childGroups(BaseRecord parent, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, parent.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(groupFields());
		return raw(q, ModelNames.MODEL_GROUP);
	}

	/** {@code path} is virtual on {@code auth.group}; walk {@code parentId} instead. */
	private static String groupPathOf(BaseRecord group, long orgId, Map<Long, BaseRecord> cache) {
		Deque<String> names = new ArrayDeque<>();
		BaseRecord cur = group;
		int depth = 0;
		while(cur != null && depth++ < 24) {
			Object nm = cur.get(FieldNames.FIELD_NAME);
			names.addFirst(nm != null ? nm.toString() : "");
			Object pid = cur.hasField(FieldNames.FIELD_PARENT_ID) ? cur.get(FieldNames.FIELD_PARENT_ID) : null;
			long parentId = (pid instanceof Number) ? ((Number) pid).longValue() : 0L;
			if(parentId <= 0L) {
				break;
			}
			cur = cache.computeIfAbsent(parentId, id -> {
				Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, id);
				q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				q.setRequest(groupFields());
				List<BaseRecord> rows = raw(q, ModelNames.MODEL_GROUP);
				return rows.isEmpty() ? null : rows.get(0);
			});
		}
		return "/" + String.join("/", names);
	}

	private static BaseRecord rawMetaNote(BaseRecord group, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, PictureBookUtil.META_NOTE_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TEXT});
		List<BaseRecord> rows = raw(q, ModelNames.MODEL_NOTE);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The {@code pb2BookObjectId} a meta note links to, or null when it has none (legacy PB1) or is unparseable. */
	private static String metaBookLink(BaseRecord note) {
		String text = note.hasField(FieldNames.FIELD_TEXT) ? note.get(FieldNames.FIELD_TEXT) : null;
		if(text == null || text.isBlank()) {
			return null;
		}
		try {
			Map<String, Object> m = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
			Object v = (m != null) ? m.get("pb2BookObjectId") : null;
			return (v instanceof String && !((String) v).isBlank()) ? ((String) v).trim() : null;
		}
		catch(Exception e) {
			return null;
		}
	}
}
