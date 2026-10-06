package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil.GrantAudit;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;

/**
 * PictureBook health check and self-heal.
 * <p>
 * Every fix a book or an organization needs is expressed here as a <b>finding</b> (a code, a severity,
 * a message and the records it points at) plus an idempotent <b>heal</b> that repairs it through the
 * same code paths the product uses - never by editing the database - so a repair applied on one
 * environment is reproducible on every other one by pressing the same button.
 * <p>
 * <b>Checks create nothing and never act as the organization administrator.</b> {@link #checkBook} and
 * {@link #checkOrg} resolve the book through {@code PbBookUtil.readBook} (the authorization boundary) and
 * then inspect olio-owned rows find-only. {@link #healBook} and {@link #healOrg} authorize as the caller
 * ({@code canUpdate} on the book, or the creator stamp) and then act as the olio principal only on the
 * rows the olio principal owns; everything in the caller's own compartment is written as the caller.
 * <p>
 * The standing failure this exists for: the old PictureBook delete left the book world, its
 * {@code Book/Workflow/Artifacts} groups and an {@code olio.pb.workflow} whose {@code book} FK pointed at a
 * deleted row. A same-slug recreate adopted that world, {@code PbGraphUtil.getCreateWorkflow} then collided
 * with the stale workflow on the unique {@code (name, groupId, organizationId)} index, the pipeline
 * swallowed the failure, and every render saved an image but recorded no {@code olio.pb.scene} row -
 * "N scenes extracted, none rendered yet". {@link #STALE_GRAPH} finds that, {@link #purgeStaleGraph}
 * removes it (also run by {@code PbBookUtil.createBook} when it adopts a pre-existing world), and
 * {@link #SCENE_ROW_MISSING} backfills the missing rows from the images that were saved.
 */
public class PbHealthUtil {
	private static final Logger logger = LogManager.getLogger(PbHealthUtil.class);

	public static final String SEV_ERROR = "ERROR";
	public static final String SEV_WARN = "WARN";
	public static final String SEV_INFO = "INFO";

	public static final String OLIO_PRINCIPAL_MISSING = "OLIO_PRINCIPAL_MISSING";
	public static final String UNIVERSE_MISSING = "UNIVERSE_MISSING";
	public static final String WORLD_MISSING = "WORLD_MISSING";
	public static final String CONTAINER_GROUP_MISSING = "CONTAINER_GROUP_MISSING";
	public static final String STALE_GRAPH = "STALE_GRAPH";
	public static final String WORKFLOW_DUPLICATE = "WORKFLOW_DUPLICATE";
	public static final String WORKFLOW_MISSING = "WORKFLOW_MISSING";
	public static final String SCENE_ROW_MISSING = "SCENE_ROW_MISSING";
	public static final String SCENE_IMAGE_UNRECORDED = "SCENE_IMAGE_UNRECORDED";
	public static final String SCENE_IMAGE_DANGLING = "SCENE_IMAGE_DANGLING";
	public static final String SCENE_NODE_DANGLING = "SCENE_NODE_DANGLING";
	public static final String ROLES_MISSING = "ROLES_MISSING";
	public static final String GRANTS_MISSING = "GRANTS_MISSING";
	public static final String META_LINK_BROKEN = "META_LINK_BROKEN";
	public static final String META_MISSING = "META_MISSING";
	public static final String CHECKPOINT_DANGLING = "CHECKPOINT_DANGLING";
	public static final String PROMPT_TEMPLATE_MISSING = "PROMPT_TEMPLATE_MISSING";
	public static final String PROMPT_TEMPLATE_DRIFT = "PROMPT_TEMPLATE_DRIFT";
	public static final String PROMPT_TEMPLATE_OVERRIDE = "PROMPT_TEMPLATE_OVERRIDE";

	private static final String[] PROMPT_TEMPLATE_PREFIXES = new String[] {"pictureBook.", "chapBook."};

	private PbHealthUtil() {}

	// ─────────────────────────────── public API ───────────────────────────────

	/**
	 * Read-only health report for one book. Creates nothing, grants nothing.
	 *
	 * @throws PictureBookException 404 when the book is not readable by {@code user}
	 */
	public static Map<String, Object> checkBook(BaseRecord user, String bookObjectId) {
		BookAudit a = auditBook(user, requireBook(user, bookObjectId));
		return report("book", a, null, null);
	}

	/**
	 * Repair one book: audit, apply every healable finding whose code is selected, audit again.
	 *
	 * @param dataPath           the Olio data path ({@code test.datagen.path} / the service's configured path),
	 *                           needed only when a world or its grants must be (re)created
	 * @param codes              finding codes to heal, or null for all
	 * @param overwriteTemplates unused at book scope; accepted for signature parity with {@link #healOrg}
	 * @throws PictureBookException 404 when the book is not readable, 403 when the caller may not update it
	 */
	public static Map<String, Object> healBook(BaseRecord user, String dataPath, String bookObjectId, Set<String> codes, boolean overwriteTemplates) {
		BaseRecord book = requireBook(user, bookObjectId);
		if(!canHeal(user, book)) {
			throw new PictureBookException(403, "Not authorized to repair this book");
		}
		List<Map<String, Object>> healed = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		BookAudit a = auditBook(user, book);
		applyBookHeals(a, dataPath, codes, healed, skipped);
		BookAudit after = auditBook(user, requireBook(user, bookObjectId));
		return report("book", after, healed, skipped);
	}

	/**
	 * Read-only health report for every book the caller created in the organization (the same set the
	 * PictureBook list shows), plus the organization-level checks: dangling extraction checkpoints and the
	 * shared PictureBook / ChapBook prompt templates.
	 */
	public static Map<String, Object> checkOrg(BaseRecord user) {
		List<Finding> findings = new ArrayList<>();
		long orgId = orgOf(user);
		for(BaseRecord book : readableBooks(user, orgId)) {
			BookAudit a = auditBook(user, book);
			findings.addAll(tagWithBook(a));
		}
		findings.addAll(auditCheckpoints(user));
		findings.addAll(auditPromptTemplates(user, orgId, false));
		findings.addAll(auditOrphans(user));
		return orgReport(findings, null, null);
	}

	/**
	 * Repair the organization view: every book the caller created and may update, then the
	 * organization-level findings.
	 *
	 * @param overwriteTemplates when true, {@link #PROMPT_TEMPLATE_DRIFT} findings are healed by overwriting
	 *                           the library template's sections with the shipped resource; otherwise they are
	 *                           reported and skipped
	 */
	public static Map<String, Object> healOrg(BaseRecord user, String dataPath, Set<String> codes, boolean overwriteTemplates) {
		List<Map<String, Object>> healed = new ArrayList<>();
		List<Map<String, Object>> skipped = new ArrayList<>();
		long orgId = orgOf(user);
		for(BaseRecord book : readableBooks(user, orgId)) {
			BookAudit a = auditBook(user, book);
			if(!canHeal(user, book)) {
				for(Finding f : a.findings) {
					if(f.healable) {
						skipped.add(skip(withBook(f, a), "not authorized to repair this book"));
					}
				}
				continue;
			}
			List<Map<String, Object>> bookHealed = new ArrayList<>();
			List<Map<String, Object>> bookSkipped = new ArrayList<>();
			applyBookHeals(a, dataPath, codes, bookHealed, bookSkipped);
			for(Map<String, Object> h : bookHealed) {
				h.put("slug", a.slug);
				h.put("bookObjectId", a.bookObjectId);
			}
			for(Map<String, Object> s : bookSkipped) {
				s.put("slug", a.slug);
				s.put("bookObjectId", a.bookObjectId);
			}
			healed.addAll(bookHealed);
			skipped.addAll(bookSkipped);
		}
		healCheckpoints(user, codes, healed, skipped);
		healPromptTemplates(user, orgId, codes, overwriteTemplates, healed, skipped);

		List<Finding> after = new ArrayList<>();
		for(BaseRecord book : readableBooks(user, orgId)) {
			after.addAll(tagWithBook(auditBook(user, book)));
		}
		after.addAll(auditCheckpoints(user));
		after.addAll(auditPromptTemplates(user, orgId, overwriteTemplates));
		after.addAll(auditOrphans(user));
		return orgReport(after, healed, skipped);
	}

	/**
	 * One INFO finding per non-empty orphan category from {@link PbOrphanUtil#scan} (own scope), pointing at
	 * the "Clean up orphans" action rather than healing in place. Dangling checkpoints are omitted because
	 * {@link #CHECKPOINT_DANGLING} already reports each of them individually.
	 */
	private static List<Finding> auditOrphans(BaseRecord user) {
		List<Finding> out = new ArrayList<>();
		try {
			Map<String, Object> scan = PbOrphanUtil.scan(user, false);
			Object cats = scan.get("categories");
			if(!(cats instanceof List)) {
				return out;
			}
			for(Object o : (List<?>) cats) {
				if(!(o instanceof Map)) {
					continue;
				}
				Map<?, ?> cat = (Map<?, ?>) o;
				String code = String.valueOf(cat.get("code"));
				int count = cat.get("count") instanceof Number ? ((Number) cat.get("count")).intValue() : 0;
				if(count == 0 || PbOrphanUtil.ORPHAN_CHECKPOINT.equals(code)) {
					continue;
				}
				out.add(new Finding(code, SEV_INFO,
					count + " orphan record(s): " + PbOrphanUtil.describe(code)
					+ " - left by earlier deletes or failed extractions; remove with 'Clean up orphans'", false)
					.ref("count", count));
			}
		}
		catch(Exception e) {
			logger.warn("Orphan audit skipped: " + e.getMessage());
		}
		return out;
	}

	/**
	 * Remove every PB2 graph row in the book's container groups that is not bound to {@code bookRow}:
	 * workflows pointing at another (deleted) book, their nodes, runs and bindings, artifacts produced by
	 * those nodes, and scenes / cast groups pointing at another book. Runs as the olio principal because
	 * those rows are olio-owned. Called by {@code PbBookUtil.createBook} when it adopts a pre-existing
	 * world, and by {@link #healBook} for {@link #STALE_GRAPH}.
	 *
	 * @return the number of rows deleted
	 */
	static int purgeStaleGraph(BaseRecord olioUser, BaseRecord bookRow, String slug, long orgId) {
		if(olioUser == null || bookRow == null || slug == null) {
			return 0;
		}
		Long bookId = bookRow.get(FieldNames.FIELD_ID);
		if(bookId == null) {
			return 0;
		}
		StaleScan scan = scanStale(olioUser, bookId.longValue(), slug, orgId);
		int n = 0;
		for(BaseRecord rec : scan.stale) {
			DeleteResult d = PictureBookUtil.deleteRecordExplained(olioUser, rec);
			if(d.deleted) {
				n++;
			}
			else {
				logger.warn("Stale graph row " + rec.getSchema() + " " + rec.get(FieldNames.FIELD_OBJECT_ID) + " not deleted: " + d.reason);
			}
		}
		if(n > 0) {
			logger.info("Purged " + n + " stale PB2 graph row(s) left in the containers of book '" + slug + "'");
			BaseRecord world = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), slug);
			if(world != null) {
				OlioContextUtil.evictByWorld(orgId, world.get(FieldNames.FIELD_OBJECT_ID));
			}
		}
		return n;
	}

	/** The organization administrator, or a member of the {@code AccountAdministrators} role. */
	static boolean isAccountAdministrator(BaseRecord user, long orgId) {
		if(user == null) {
			return false;
		}
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx != null && octx.getAdminUser() != null
			&& Objects.equals(octx.getAdminUser().get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ID))) {
			return true;
		}
		BaseRecord role = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_ROLE,
			"/" + AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR, RoleEnumType.USER.toString(), orgId);
		return role != null && ioContext.getMemberUtil().isMember(user, role, null);
	}

	// ─────────────────────────────── findings ───────────────────────────────

	private static final class Finding {
		final String code;
		final String severity;
		final String message;
		final boolean healable;
		final Map<String, Object> refs = new LinkedHashMap<>();

		Finding(String code, String severity, String message, boolean healable) {
			this.code = code;
			this.severity = severity;
			this.message = message;
			this.healable = healable;
		}

		Finding ref(String key, Object value) {
			if(value != null) {
				refs.put(key, value);
			}
			return this;
		}

		Finding rec(BaseRecord r) {
			if(r != null) {
				ref("model", r.getSchema());
				ref("id", r.get(FieldNames.FIELD_ID));
				ref("objectId", r.get(FieldNames.FIELD_OBJECT_ID));
				ref("name", r.get(FieldNames.FIELD_NAME));
			}
			return this;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", code);
			m.put("severity", severity);
			m.put("message", message);
			m.put("healable", healable);
			m.put("refs", new LinkedHashMap<>(refs));
			return m;
		}
	}

	/** Everything one book audit discovered, including the payloads the heals act on. */
	private static final class BookAudit {
		BaseRecord user;
		long orgId;
		OrganizationContext octx;
		BaseRecord book;
		String bookObjectId;
		String slug;
		boolean chapter;
		boolean chapBook;
		BaseRecord series;
		String seriesSlug;
		String worldSlug;
		BaseRecord olioUser;
		BaseRecord world;
		boolean worldLinkBroken;
		final List<Finding> findings = new ArrayList<>();
		final List<String> missingContainerPaths = new ArrayList<>();
		StaleScan stale;
		final List<BaseRecord> duplicateWorkflows = new ArrayList<>();
		final List<Map<String, Object>> missingSceneEntries = new ArrayList<>();
		final Map<BaseRecord, String> unrecordedImageScenes = new LinkedHashMap<>();
		final List<BaseRecord> scenesWithDanglingImage = new ArrayList<>();
		final List<BaseRecord> scenesWithDanglingNode = new ArrayList<>();
		GrantAudit grants;
		boolean chapterGrantsMissing;
		BaseRecord metaRec;
		Map<String, Object> metaMap;
		boolean metaLinkBroken;
	}

	private static final class StaleScan {
		/** Child-to-parent order: bindings, artifacts, runs, nodes, scenes, cast groups, workflows. */
		final List<BaseRecord> stale = new ArrayList<>();
		final Set<Long> liveWorkflowIds = new HashSet<>();
		final Set<Long> liveNodeIds = new HashSet<>();
		final List<BaseRecord> liveWorkflows = new ArrayList<>();
	}

	// ─────────────────────────────── audit ───────────────────────────────

	private static BookAudit auditBook(BaseRecord user, BaseRecord book) {
		IOContext ioContext = IOSystem.getActiveContext();
		BookAudit a = new BookAudit();
		a.user = user;
		a.book = book;
		a.bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		a.slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		a.octx = ioContext.findOrganizationContext(user);
		a.orgId = (a.octx != null ? a.octx.getOrganizationId() : orgOf(user));
		Object bt = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		a.chapBook = (bt != null && "CHAPBOOK".equalsIgnoreCase(bt.toString()));

		BaseRecord seriesRef = book.get(OlioFieldNames.FIELD_PB_SERIES);
		if(seriesRef != null && idOf(seriesRef) > 0) {
			a.chapter = true;
			a.series = resolveSeries(user, seriesRef, a.orgId);
			a.seriesSlug = (a.series != null ? PbSeriesUtil.seriesSlug(a.series) : null);
		}
		a.worldSlug = (a.chapter ? a.seriesSlug : a.slug);

		if(a.slug == null) {
			a.findings.add(new Finding(WORLD_MISSING, SEV_ERROR, "Book row has no slug; nothing can be resolved for it", false).rec(book));
			return a;
		}
		if(a.chapter && a.seriesSlug == null) {
			a.findings.add(new Finding(WORLD_MISSING, SEV_ERROR, "Chapter references a series that cannot be resolved", false).rec(book));
			return a;
		}

		a.olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, a.orgId);
		if(a.olioUser == null) {
			a.findings.add(new Finding(OLIO_PRINCIPAL_MISSING, SEV_ERROR,
				"No olio principal exists in this organization; the book world, groups and roles cannot be resolved", true).rec(book));
			return a;
		}

		auditWorld(a);
		auditContainerGroups(a);
		auditGraph(a);
		auditScenes(a);
		if(a.world != null) {
			auditGrants(a);
		}
		if(!a.chapBook) {
			auditMeta(a);
		}
		return a;
	}

	private static void auditWorld(BookAudit a) {
		a.world = WorldUtil.findWorld(a.olioUser, PbOlioContextUtil.bookWorldPath(), a.worldSlug);
		BaseRecord linked = a.book.get(OlioFieldNames.FIELD_PB_WORLD);
		if(a.world == null) {
			a.worldLinkBroken = true;
			a.findings.add(new Finding(WORLD_MISSING, SEV_ERROR,
				(a.chapter ? "Series world '" : "Book world '") + a.worldSlug + "' does not exist under " + PbOlioContextUtil.bookWorldPath(), true)
				.rec(a.book).ref("path", PbOlioContextUtil.bookWorldPath() + "/" + a.worldSlug));
			return;
		}
		if(PbOlioContextUtil.assembleBookContext(a.world) == null) {
			a.findings.add(new Finding(UNIVERSE_MISSING, SEV_ERROR,
				"The " + PbOlioContextUtil.BOOKS_UNIVERSE + " universe cannot be assembled for world '" + a.worldSlug + "'", true)
				.rec(a.world));
		}
		if(linked == null || idOf(linked) != idOf(a.world)) {
			a.worldLinkBroken = true;
			a.findings.add(new Finding(WORLD_MISSING, SEV_ERROR,
				"Book row is not linked to its world '" + a.worldSlug + "' (world FK " + (linked == null ? "null" : "#" + idOf(linked)) + ")", true)
				.rec(a.book).ref("worldObjectId", a.world.get(FieldNames.FIELD_OBJECT_ID)));
		}
	}

	private static void auditContainerGroups(BookAudit a) {
		IOContext ioContext = IOSystem.getActiveContext();
		for(String path : containerPaths(a.slug)) {
			BaseRecord grp = ioContext.getPathUtil().findPath(a.olioUser, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), a.orgId);
			if(grp == null) {
				a.missingContainerPaths.add(path);
				a.findings.add(new Finding(CONTAINER_GROUP_MISSING, SEV_ERROR, "Container group " + path + " does not exist", true)
					.rec(a.book).ref("path", path));
			}
		}
	}

	private static void auditGraph(BookAudit a) {
		long bookId = idOf(a.book);
		a.stale = scanStale(a.olioUser, bookId, a.slug, a.orgId);
		if(!a.stale.stale.isEmpty()) {
			Map<String, Integer> byModel = new LinkedHashMap<>();
			for(BaseRecord r : a.stale.stale) {
				byModel.merge(r.getSchema(), 1, Integer::sum);
			}
			a.findings.add(new Finding(STALE_GRAPH, SEV_ERROR,
				a.stale.stale.size() + " PB2 graph row(s) in this book's containers belong to a previously deleted copy of the book ("
				+ byModel + "); they block workflow creation, so renders save images but record no scene rows", true)
				.rec(a.book).ref("counts", byModel));
		}
		List<BaseRecord> workflows = a.stale.liveWorkflows;
		if(workflows.size() > 1) {
			BaseRecord keep = null;
			int keepNodes = -1;
			Map<BaseRecord, Integer> nodeCounts = new HashMap<>();
			for(BaseRecord wf : workflows) {
				int n = PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_WORKFLOW, wf, a.orgId).size();
				nodeCounts.put(wf, n);
				if(n > keepNodes) {
					keep = wf;
					keepNodes = n;
				}
			}
			for(BaseRecord wf : workflows) {
				if(wf != keep) {
					a.duplicateWorkflows.add(wf);
				}
			}
			a.findings.add(new Finding(WORKFLOW_DUPLICATE, SEV_ERROR,
				workflows.size() + " workflows are bound to this book; keeping the one with " + keepNodes + " node(s) and purging " + a.duplicateWorkflows.size(), true)
				.rec(keep).ref("duplicates", a.duplicateWorkflows.size()));
		}
	}

	private static void auditScenes(BookAudit a) {
		List<BaseRecord> rows;
		try {
			rows = PbBookUtil.listScenes(a.user, a.book);
		}
		catch(PictureBookException e) {
			rows = new ArrayList<>();
		}
		Map<Integer, BaseRecord> rowsByIndex = new HashMap<>();
		for(BaseRecord row : rows) {
			Integer idx = row.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			if(idx != null && !rowsByIndex.containsKey(idx)) {
				rowsByIndex.put(idx, row);
			}
		}

		List<Map<String, Object>> entries = new ArrayList<>();
		if(!a.chapBook) {
			try {
				entries = PictureBookUtil.listScenes(a.user, a.bookObjectId);
			}
			catch(PictureBookException e) {
				/// 404 here means no PictureBooks group / meta - the book has not been extracted (yet)
				entries = new ArrayList<>();
			}
		}
		int imagesInMeta = 0;
		for(Map<String, Object> entry : entries) {
			Object imgObj = entry.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			String imageObjectId = (imgObj instanceof String && !((String) imgObj).isBlank()) ? (String) imgObj : null;
			if(imageObjectId == null || !dataExists(imageObjectId, a.orgId)) {
				continue;
			}
			imagesInMeta++;
			Integer idx = indexOf(entry);
			if(idx == null) {
				continue;
			}
			BaseRecord row = rowsByIndex.get(idx);
			if(row == null) {
				a.missingSceneEntries.add(entry);
				a.findings.add(new Finding(SCENE_ROW_MISSING, SEV_ERROR,
					"Scene " + idx + " ('" + entry.get("title") + "') has a rendered image but no olio.pb.scene row; the reader shows it as unrendered", true)
					.ref("model", OlioModelNames.MODEL_PB_SCENE).ref("sceneIndex", idx).ref("imageObjectId", imageObjectId).ref("sceneObjectId", entry.get("objectId")));
				continue;
			}
			String rowImage = row.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			if(row.get(OlioFieldNames.FIELD_PB_SCENE_NODE) == null && (rowImage == null || rowImage.isBlank())) {
				a.unrecordedImageScenes.put(row, imageObjectId);
				a.findings.add(new Finding(SCENE_IMAGE_UNRECORDED, SEV_WARN,
					"Scene " + idx + " has a rendered image that its olio.pb.scene row does not reference", true)
					.rec(row).ref("sceneIndex", idx).ref("imageObjectId", imageObjectId));
			}
		}

		Set<Long> liveNodes = (a.stale != null ? a.stale.liveNodeIds : new HashSet<>());
		for(BaseRecord row : rows) {
			Integer idx = row.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			String rowImage = row.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			if(rowImage != null && !rowImage.isBlank() && !dataExists(rowImage, a.orgId)) {
				a.scenesWithDanglingImage.add(row);
				a.findings.add(new Finding(SCENE_IMAGE_DANGLING, SEV_WARN,
					"Scene " + idx + " references image " + rowImage + " which no longer exists", true)
					.rec(row).ref("sceneIndex", idx).ref("imageObjectId", rowImage));
			}
			BaseRecord nodeRef = row.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
			if(nodeRef != null && idOf(nodeRef) > 0 && !liveNodes.contains(idOf(nodeRef))) {
				a.scenesWithDanglingNode.add(row);
				a.findings.add(new Finding(SCENE_NODE_DANGLING, SEV_WARN,
					"Scene " + idx + " references workflow node #" + idOf(nodeRef) + " which is not part of this book's workflow", true)
					.rec(row).ref("sceneIndex", idx).ref("nodeId", idOf(nodeRef)));
			}
		}

		if(a.stale != null && a.stale.liveWorkflows.isEmpty() && (imagesInMeta > 0 || !rows.isEmpty())) {
			boolean blocked = !a.stale.stale.isEmpty();
			a.findings.add(new Finding(WORKFLOW_MISSING, SEV_INFO,
				"No workflow is bound to this book yet" + (blocked ? " - creation is blocked by the stale graph above until it is purged" : "; it is created on the next render"), false)
				.rec(a.book));
		}
	}

	private static void auditGrants(BookAudit a) {
		a.grants = PbOlioContextUtil.checkGrants(a.user, a.worldSlug, a.chapter);
		if(a.grants.error != null) {
			a.findings.add(new Finding(GRANTS_MISSING, SEV_ERROR, "Grant audit incomplete: " + a.grants.error, true).rec(a.book));
		}
		for(String rolePath : a.grants.missingRoles) {
			a.findings.add(new Finding(ROLES_MISSING, SEV_ERROR, "Role " + rolePath + " does not exist", true).rec(a.book).ref("path", rolePath));
		}
		for(Map<String, Object> gap : a.grants.missingGrants) {
			a.findings.add(new Finding(GRANTS_MISSING, SEV_ERROR,
				"Role " + gap.get("role") + " lacks Read on " + gap.get("tier") + " group " + gap.get("group") + " (#" + gap.get("groupId") + ")", true)
				.rec(a.book).ref("tier", gap.get("tier")).ref("role", gap.get("role")).ref("group", gap.get("group")).ref("groupId", gap.get("groupId")));
		}
		if(a.chapter) {
			IOContext ioContext = IOSystem.getActiveContext();
			BaseRecord readPerm = ioContext.getPathUtil().findPath(a.olioUser, ModelNames.MODEL_PERMISSION, "/Read", PermissionEnumType.DATA.toString(), a.orgId);
			for(String rolePath : new String[] {PbOlioContextUtil.seriesWriterRolePath(a.seriesSlug), PbOlioContextUtil.seriesAdminRolePath(a.seriesSlug)}) {
				BaseRecord role = ioContext.getPathUtil().findPath(a.olioUser, ModelNames.MODEL_ROLE, rolePath, RoleEnumType.USER.toString(), a.orgId);
				if(role == null || readPerm == null) {
					continue;
				}
				for(String path : containerPaths(a.slug)) {
					BaseRecord grp = ioContext.getPathUtil().findPath(a.olioUser, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), a.orgId);
					if(grp == null) {
						continue;
					}
					if(!ioContext.getAuthorizationUtil().checkEntitlement(role, readPerm, grp)) {
						a.chapterGrantsMissing = true;
						a.findings.add(new Finding(GRANTS_MISSING, SEV_ERROR,
							"Series role " + role.get(FieldNames.FIELD_NAME) + " lacks Read on chapter group " + path, true)
							.rec(a.book).ref("tier", "chapter").ref("role", role.get(FieldNames.FIELD_NAME)).ref("path", path).ref("groupId", grp.get(FieldNames.FIELD_ID)));
					}
				}
			}
		}
	}

	private static void auditMeta(BookAudit a) {
		BaseRecord group = PictureBookUtil.resolveBookGroupEither(a.user, a.bookObjectId, a.orgId);
		if(group == null) {
			a.findings.add(new Finding(META_MISSING, SEV_INFO, "No PictureBooks scene group / meta for this book (not extracted yet, or PB2-only)", false).rec(a.book));
			return;
		}
		a.metaRec = findMetaNote(a.user, group, a.orgId);
		if(a.metaRec == null) {
			a.findings.add(new Finding(META_MISSING, SEV_INFO, "Scene group " + group.get(FieldNames.FIELD_PATH) + " has no " + PictureBookUtil.META_NOTE_NAME + " note", false)
				.rec(group));
			return;
		}
		String text = a.metaRec.get(FieldNames.FIELD_TEXT);
		try {
			a.metaMap = (text == null || text.isBlank()) ? new LinkedHashMap<>() : JSONUtil.getMap(text.getBytes(), String.class, Object.class);
		}
		catch(Exception e) {
			a.findings.add(new Finding(META_LINK_BROKEN, SEV_WARN, "Meta note is not parseable JSON: " + e.getMessage(), false).rec(a.metaRec));
			return;
		}
		if(a.metaMap == null) {
			a.metaMap = new LinkedHashMap<>();
		}
		Object link = a.metaMap.get("pb2BookObjectId");
		if(link == null || !a.bookObjectId.equals(link.toString())) {
			a.metaLinkBroken = true;
			a.findings.add(new Finding(META_LINK_BROKEN, SEV_WARN,
				"Meta note pb2BookObjectId is " + (link == null ? "missing" : "'" + link + "'") + "; expected this book's objectId", true)
				.rec(a.metaRec).ref("expected", a.bookObjectId));
		}
	}

	// ─────────────────────────────── heal ───────────────────────────────

	private static void applyBookHeals(BookAudit a, String dataPath, Set<String> codes, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		IOContext ioContext = IOSystem.getActiveContext();
		boolean needGrants = false;

		/// 1. principal / universe / world
		boolean worldWork = has(a, OLIO_PRINCIPAL_MISSING, codes) || has(a, UNIVERSE_MISSING, codes) || (has(a, WORLD_MISSING, codes) && a.worldLinkBroken);
		if(worldWork) {
			if(a.olioUser == null || a.world == null || findingPresent(a, UNIVERSE_MISSING)) {
				try {
					if(a.chapter) {
						PbOlioContextUtil.getCreateSeriesContext(a.user, dataPath, a.seriesSlug);
					}
					else {
						PbOlioContextUtil.getCreateBookContext(a.user, dataPath, a.slug);
					}
					a.olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, a.orgId);
					a.world = WorldUtil.findWorld(a.olioUser, PbOlioContextUtil.bookWorldPath(), a.worldSlug);
					healed.add(done(WORLD_MISSING, a.book, "world '" + a.worldSlug + "' resolved via " + (a.chapter ? "getCreateSeriesContext" : "getCreateBookContext")));
				}
				catch(OlioException | PictureBookException e) {
					skipped.add(skipRaw(WORLD_MISSING, a.book, "context creation failed: " + e.getMessage()));
				}
			}
			if(a.world != null && a.worldLinkBroken) {
				if(patchWorldLink(a)) {
					healed.add(done(WORLD_MISSING, a.book, "book.world linked to " + a.world.get(FieldNames.FIELD_OBJECT_ID)));
				}
				else {
					skipped.add(skipRaw(WORLD_MISSING, a.book, "book.world patch was not persisted"));
				}
			}
		}
		if(a.olioUser == null) {
			return;
		}

		/// 2. container groups
		if(has(a, CONTAINER_GROUP_MISSING, codes)) {
			for(String path : a.missingContainerPaths) {
				BaseRecord grp = ioContext.getPathUtil().makePath(a.olioUser, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), a.orgId);
				if(grp != null) {
					healed.add(done(CONTAINER_GROUP_MISSING, grp, "created " + path));
					needGrants = true;
				}
				else {
					skipped.add(skipRaw(CONTAINER_GROUP_MISSING, a.book, "makePath failed for " + path));
				}
			}
		}

		/// 3. roles / grants
		if(needGrants || has(a, ROLES_MISSING, codes) || has(a, GRANTS_MISSING, codes)) {
			healGrants(a, dataPath, healed, skipped);
		}

		/// 4. stale graph
		if(has(a, STALE_GRAPH, codes)) {
			int n = purgeStaleGraph(a.olioUser, a.book, a.slug, a.orgId);
			if(n > 0 || a.stale == null || a.stale.stale.isEmpty()) {
				healed.add(done(STALE_GRAPH, a.book, "purged " + n + " stale graph row(s)"));
			}
			else {
				skipped.add(skipRaw(STALE_GRAPH, a.book, "stale rows could not be deleted as the olio principal - see log"));
			}
		}

		/// 5. duplicate workflows
		if(has(a, WORKFLOW_DUPLICATE, codes)) {
			int n = 0;
			for(BaseRecord wf : a.duplicateWorkflows) {
				n += PbDeleteUtil.deleteWorkflowGraph(a.olioUser, wf, a.orgId, DeleteResult.ok());
			}
			healed.add(done(WORKFLOW_DUPLICATE, a.book, "purged " + a.duplicateWorkflows.size() + " duplicate workflow(s), " + n + " row(s)"));
		}

		/// 6. scene rows missing for rendered images
		if(has(a, SCENE_ROW_MISSING, codes)) {
			for(Map<String, Object> entry : a.missingSceneEntries) {
				Integer idx = indexOf(entry);
				String title = (entry.get("title") instanceof String && !((String) entry.get("title")).isBlank()) ? (String) entry.get("title") : "Scene " + (idx + 1);
				try {
					BaseRecord scene = PbBookUtil.createScene(a.user, a.book, idx.intValue(), title, PbBookUtil.bookGroupPath(a.slug));
					Map<String, Object> data = new HashMap<>();
					copyIfPresent(entry, data, OlioFieldNames.FIELD_PB_SUMMARY);
					copyIfPresent(entry, data, OlioFieldNames.FIELD_PB_SETTING);
					copyIfPresent(entry, data, OlioFieldNames.FIELD_PB_ACTION);
					copyIfPresent(entry, data, OlioFieldNames.FIELD_PB_MOOD);
					copyIfPresent(entry, data, OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
					/// PictureBookUtil.listScenes merges the scene note's current blurb into "description"; the meta
					/// entry's own "blurb" is the extraction-time text, so the note wins when both are present.
					Object blurb = entry.get(FieldNames.FIELD_DESCRIPTION) != null ? entry.get(FieldNames.FIELD_DESCRIPTION) : entry.get(OlioFieldNames.FIELD_PB_BLURB);
					if(blurb != null) {
						data.put(OlioFieldNames.FIELD_PB_BLURB, blurb);
						data.put(FieldNames.FIELD_DESCRIPTION, blurb);
					}
					List<String> warnings = new ArrayList<>();
					boolean ok = PbMigrationUtil.patchSceneTextFields(a.user, scene, data, warnings);
					if(ok) {
						healed.add(done(SCENE_ROW_MISSING, scene, "created scene row " + idx + " from image " + entry.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID)));
					}
					else {
						skipped.add(skipRaw(SCENE_ROW_MISSING, scene, "row created but text/image patch failed: " + String.join("; ", warnings)));
					}
				}
				catch(PictureBookException e) {
					skipped.add(skipRaw(SCENE_ROW_MISSING, a.book, "scene " + idx + ": " + e.getMessage()));
				}
			}
		}

		/// 7. scene rows that exist but do not reference their rendered image
		if(has(a, SCENE_IMAGE_UNRECORDED, codes)) {
			for(Map.Entry<BaseRecord, String> e : a.unrecordedImageScenes.entrySet()) {
				if(patchScene(a.user, e.getKey(), OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, e.getValue())) {
					healed.add(done(SCENE_IMAGE_UNRECORDED, e.getKey(), "imageObjectId set to " + e.getValue()));
				}
				else {
					skipped.add(skipRaw(SCENE_IMAGE_UNRECORDED, e.getKey(), "patch was not persisted"));
				}
			}
		}

		/// 8. dangling image references
		if(has(a, SCENE_IMAGE_DANGLING, codes)) {
			for(BaseRecord row : a.scenesWithDanglingImage) {
				BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, OlioFieldNames.FIELD_PB_IMAGE_STALE);
				boolean ok;
				try {
					patch.set(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID, null);
					patch.set(OlioFieldNames.FIELD_PB_IMAGE_STALE, true);
					ok = ioContext.getAccessPoint().update(a.user, patch) != null;
				}
				catch(FieldException | ValueException | ModelNotFoundException ex) {
					ok = false;
				}
				if(ok) {
					healed.add(done(SCENE_IMAGE_DANGLING, row, "imageObjectId cleared, imageStale=true"));
				}
				else {
					skipped.add(skipRaw(SCENE_IMAGE_DANGLING, row, "patch was not persisted"));
				}
			}
		}

		/// 9. dangling node references
		if(has(a, SCENE_NODE_DANGLING, codes)) {
			for(BaseRecord row : a.scenesWithDanglingNode) {
				/// The materialised-but-unset MODEL field binds as 0 and the reader only sets an FK when > 0,
				/// so a patch carrying a null sceneNode is how the reference is cleared.
				BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
				if(ioContext.getAccessPoint().update(a.user, patch) != null) {
					healed.add(done(SCENE_NODE_DANGLING, row, "sceneNode cleared"));
				}
				else {
					skipped.add(skipRaw(SCENE_NODE_DANGLING, row, "patch was not persisted"));
				}
			}
		}

		/// 10. meta link
		if(has(a, META_LINK_BROKEN, codes) && a.metaLinkBroken && a.metaRec != null && a.metaMap != null) {
			a.metaMap.put("pb2BookObjectId", a.bookObjectId);
			BaseRecord patch = PbGraphUtil.patchOf(a.metaRec, ModelNames.MODEL_NOTE, FieldNames.FIELD_TEXT);
			boolean ok;
			try {
				patch.set(FieldNames.FIELD_TEXT, JSONUtil.exportObject(a.metaMap));
				ok = ioContext.getAccessPoint().update(a.user, patch) != null;
			}
			catch(FieldException | ValueException | ModelNotFoundException ex) {
				ok = false;
			}
			if(ok) {
				healed.add(done(META_LINK_BROKEN, a.metaRec, "pb2BookObjectId set to " + a.bookObjectId));
			}
			else {
				skipped.add(skipRaw(META_LINK_BROKEN, a.metaRec, "meta patch was not persisted"));
			}
		}
	}

	private static void healGrants(BookAudit a, String dataPath, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		IOContext ioContext = IOSystem.getActiveContext();
		PbOlioContextUtil.evictBookContext(a.user, a.worldSlug);
		if(a.world != null) {
			OlioContextUtil.evictByWorld(a.orgId, a.world.get(FieldNames.FIELD_OBJECT_ID));
		}
		try {
			if(a.chapter) {
				PbOlioContextUtil.getCreateSeriesContext(a.user, dataPath, a.seriesSlug);
				List<BaseRecord> groups = new ArrayList<>();
				for(String path : containerPaths(a.slug)) {
					BaseRecord grp = ioContext.getPathUtil().findPath(a.olioUser, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), a.orgId);
					if(grp != null) {
						groups.add(grp);
					}
				}
				if(!groups.isEmpty() && a.octx != null) {
					PbBookUtil.grantSeriesRolesOnChapterGroups(ioContext, a.octx, a.orgId, a.seriesSlug, groups.toArray(new BaseRecord[0]));
				}
			}
			else {
				PbOlioContextUtil.getCreateBookContext(a.user, dataPath, a.slug);
			}
			healed.add(done(GRANTS_MISSING, a.book, "roles and grants re-applied via " + (a.chapter ? "getCreateSeriesContext + series role grants on chapter groups" : "getCreateBookContext")));
		}
		catch(OlioException | PictureBookException e) {
			skipped.add(skipRaw(GRANTS_MISSING, a.book, "grant repair failed: " + e.getMessage()));
		}
	}

	private static boolean patchWorldLink(BookAudit a) {
		BaseRecord patch = PbGraphUtil.patchOf(a.book, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_WORLD);
		try {
			patch.set(OlioFieldNames.FIELD_PB_WORLD, a.world);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			return false;
		}
		IOContext ioContext = IOSystem.getActiveContext();
		if(ioContext.getAccessPoint().update(a.user, patch) != null) {
			return true;
		}
		/// The row is olio-owned; a creator whose grants are also broken cannot write it, so link it as the
		/// owner - the caller was authorized for the heal above, and the grant repair follows.
		return ioContext.getAccessPoint().update(a.olioUser, patch) != null;
	}

	private static boolean patchScene(BaseRecord user, BaseRecord row, String field, String value) {
		BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_SCENE, field);
		try {
			patch.set(field, value);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			return false;
		}
		return IOSystem.getActiveContext().getAccessPoint().update(user, patch) != null;
	}

	// ─────────────────────────────── org-level: checkpoints ───────────────────────────────

	private static List<Finding> auditCheckpoints(BaseRecord user) {
		List<Finding> out = new ArrayList<>();
		List<Map<String, Object>> rows;
		try {
			rows = PictureBookUtil.listExtractCheckpoints(user);
		}
		catch(Exception e) {
			logger.warn("Checkpoint audit failed: " + e.getMessage());
			return out;
		}
		for(Map<String, Object> row : rows) {
			if(Boolean.TRUE.equals(row.get("workMissing"))) {
				out.add(new Finding(CHECKPOINT_DANGLING, SEV_WARN,
					"Extraction checkpoint for source document " + row.get("workObjectId") + " whose document no longer exists", true)
					.ref("model", ModelNames.MODEL_NOTE).ref("objectId", row.get("noteObjectId")).ref("workObjectId", row.get("workObjectId"))
					.ref("startOffset", row.get("startOffset")).ref("endOffset", row.get("endOffset")));
			}
		}
		return out;
	}

	private static void healCheckpoints(BaseRecord user, Set<String> codes, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		if(!selected(CHECKPOINT_DANGLING, codes)) {
			return;
		}
		for(Finding f : auditCheckpoints(user)) {
			String workObjectId = (String) f.refs.get("workObjectId");
			Integer start = f.refs.get("startOffset") instanceof Number ? ((Number) f.refs.get("startOffset")).intValue() : null;
			Integer end = f.refs.get("endOffset") instanceof Number ? ((Number) f.refs.get("endOffset")).intValue() : null;
			try {
				int n = PictureBookUtil.discardExtractCheckpoint(user, workObjectId, start, end);
				Map<String, Object> h = done(CHECKPOINT_DANGLING, null, "discarded " + n + " checkpoint note(s)");
				h.put("refs", new LinkedHashMap<>(f.refs));
				healed.add(h);
			}
			catch(PictureBookException e) {
				skipped.add(skip(f, e.getMessage()));
			}
		}
	}

	// ─────────────────────────────── org-level: prompt templates ───────────────────────────────

	private static List<Finding> auditPromptTemplates(BaseRecord user, long orgId, boolean overwriteTemplates) {
		List<Finding> out = new ArrayList<>();
		BaseRecord libDir = null;
		try {
			libDir = ChatLibraryUtil.findLibraryDir(user, ChatLibraryUtil.LIBRARY_PROMPT_TEMPLATES);
		}
		catch(Exception e) {
			logger.warn("Prompt template library lookup failed: " + e.getMessage());
		}
		for(String name : ChatUtil.getPromptTemplateTemplateNames()) {
			if(!isPictureBookTemplate(name)) {
				continue;
			}
			BaseRecord resource = ChatUtil.loadPromptTemplateTemplate(name);
			if(resource == null) {
				continue;
			}
			BaseRecord lib = (libDir != null ? findLibraryTemplate(user, libDir, name, orgId) : null);
			if(lib == null) {
				out.add(new Finding(PROMPT_TEMPLATE_MISSING, SEV_ERROR,
					"Prompt template '" + name + "' is not in the shared " + ChatLibraryUtil.LIBRARY_PROMPT_TEMPLATES + " library (or not readable)", true)
					.ref("model", OlioModelNames.MODEL_PROMPT_TEMPLATE).ref("name", name));
				continue;
			}
			String diff = templateDiff(lib, resource);
			if(diff != null) {
				out.add(new Finding(PROMPT_TEMPLATE_DRIFT, SEV_WARN,
					"Library prompt template '" + name + "' differs from the shipped resource: " + diff
					+ (overwriteTemplates ? "" : " (reported only - enable 'overwrite templates' to replace it)"), overwriteTemplates)
					.rec(lib).ref("detail", diff));
			}
			BaseRecord override = findUserOverride(user, name, orgId);
			if(override != null && idOf(override) != idOf(lib)) {
				out.add(new Finding(PROMPT_TEMPLATE_OVERRIDE, SEV_INFO,
					"You have a personal copy of prompt template '" + name + "' that takes precedence over the library one", false)
					.rec(override));
			}
		}
		return out;
	}

	private static void healPromptTemplates(BaseRecord user, long orgId, Set<String> codes, boolean overwriteTemplates, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		List<Finding> findings = auditPromptTemplates(user, orgId, overwriteTemplates);
		boolean populated = false;
		for(Finding f : findings) {
			if(PROMPT_TEMPLATE_MISSING.equals(f.code) && selected(PROMPT_TEMPLATE_MISSING, codes)) {
				if(!populated) {
					ChatLibraryUtil.populatePromptDefaults(user);
					populated = true;
				}
				Map<String, Object> h = done(PROMPT_TEMPLATE_MISSING, null, "populated missing library prompt templates from the shipped resources");
				h.put("refs", new LinkedHashMap<>(f.refs));
				healed.add(h);
			}
			else if(PROMPT_TEMPLATE_DRIFT.equals(f.code) && selected(PROMPT_TEMPLATE_DRIFT, codes)) {
				if(!overwriteTemplates) {
					skipped.add(skip(f, "overwriteTemplates not set"));
					continue;
				}
				String name = (String) f.refs.get("name");
				BaseRecord libDir = ChatLibraryUtil.findLibraryDir(user, ChatLibraryUtil.LIBRARY_PROMPT_TEMPLATES);
				BaseRecord lib = (libDir != null ? findLibraryTemplate(user, libDir, name, orgId) : null);
				BaseRecord resource = ChatUtil.loadPromptTemplateTemplate(name);
				if(lib == null || resource == null) {
					skipped.add(skip(f, "library template or resource no longer resolvable"));
					continue;
				}
				String reason = overwriteTemplate(user, orgId, lib, resource);
				if(reason == null) {
					Map<String, Object> h = done(PROMPT_TEMPLATE_DRIFT, lib, "sections, sectionOrder and extends overwritten from the shipped resource");
					healed.add(h);
				}
				else {
					skipped.add(skip(f, reason));
				}
			}
		}
		if(populated || !healed.isEmpty()) {
			CacheUtil.clearCacheByModel(OlioModelNames.MODEL_PROMPT_TEMPLATE);
		}
	}

	/** @return null on success, otherwise the reason the overwrite did not persist */
	private static String overwriteTemplate(BaseRecord user, long orgId, BaseRecord lib, BaseRecord resource) {
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord patch = PbGraphUtil.patchOf(lib, OlioModelNames.MODEL_PROMPT_TEMPLATE, "sections", "sectionOrder", "extends");
		try {
			patch.set("sections", resource.get("sections"));
			patch.set("sectionOrder", resource.get("sectionOrder"));
			patch.set("extends", resource.get("extends"));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			return "patch assembly failed: " + e.getMessage();
		}
		if(ioContext.getAccessPoint().update(user, patch) != null) {
			return null;
		}
		if(isAccountAdministrator(user, orgId)) {
			OrganizationContext octx = ioContext.findOrganizationContext(user);
			if(octx != null && octx.getAdminUser() != null && ioContext.getAccessPoint().update(octx.getAdminUser(), patch) != null) {
				return null;
			}
		}
		return "not authorized to update the shared library template (AccountAdministrators only)";
	}

	private static boolean isPictureBookTemplate(String name) {
		if(name == null) {
			return false;
		}
		for(String p : PROMPT_TEMPLATE_PREFIXES) {
			if(name.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	private static final String[] TEMPLATE_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
		FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, "extends", "sections", "sectionOrder", "role"
	};

	/** Uncached mirror of {@code ChatUtil.getLibraryConfig} for one template, read as the caller. */
	private static BaseRecord findLibraryTemplate(BaseRecord user, BaseRecord libDir, String name, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_TEMPLATE, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, libDir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(TEMPLATE_REQUEST);
		q.setCache(false);
		q.setContextUser(user);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * The caller's own copy of a template, if any: {@code ownerId == user}, which is what
	 * {@code ChatUtil.resolveConfig} prefers over the library copy. Read raw on identity fields only -
	 * the authorized form of this query (no {@code groupId}) logs an ERROR-level PBAC line on every miss.
	 */
	private static BaseRecord findUserOverride(BaseRecord user, String name, long orgId) {
		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_TEMPLATE, FieldNames.FIELD_NAME, name);
			q.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID});
			q.setCache(false);
			BaseRecord r = IOSystem.getActiveContext().getSearch().findRecord(q);
			if(r != null && r.getSchema() == null) {
				r.setSchema(OlioModelNames.MODEL_PROMPT_TEMPLATE);
			}
			return r;
		}
		catch(Exception e) {
			return null;
		}
	}

	/** @return null when the library copy matches the shipped resource, otherwise a short description of the drift */
	private static String templateDiff(BaseRecord lib, BaseRecord resource) {
		List<String> diffs = new ArrayList<>();
		Set<String> libSections = normalizeSections(lib.get("sections"));
		Set<String> resSections = normalizeSections(resource.get("sections"));
		if(!libSections.equals(resSections)) {
			Set<String> onlyLib = new TreeSet<>(libSections);
			onlyLib.removeAll(resSections);
			Set<String> onlyRes = new TreeSet<>(resSections);
			onlyRes.removeAll(libSections);
			diffs.add("sections differ (" + sectionNames(onlyLib) + " in library vs " + sectionNames(onlyRes) + " in resource)");
		}
		List<String> libOrder = stringList(lib.get("sectionOrder"));
		List<String> resOrder = stringList(resource.get("sectionOrder"));
		if(!libOrder.equals(resOrder)) {
			diffs.add("sectionOrder differs");
		}
		String libExt = lib.get("extends");
		String resExt = resource.get("extends");
		if(!Objects.equals(blankToNull(libExt), blankToNull(resExt))) {
			diffs.add("extends differs (" + libExt + " vs " + resExt + ")");
		}
		return diffs.isEmpty() ? null : String.join("; ", diffs);
	}

	private static Set<String> normalizeSections(Object sectionsObj) {
		Set<String> out = new TreeSet<>();
		if(!(sectionsObj instanceof List)) {
			return out;
		}
		for(Object o : (List<?>) sectionsObj) {
			if(!(o instanceof BaseRecord)) {
				continue;
			}
			BaseRecord s = (BaseRecord) o;
			Object priority = s.hasField("priority") ? s.get("priority") : null;
			StringBuilder sb = new StringBuilder();
			sb.append(valueOrEmpty(s.hasField("sectionName") ? s.get("sectionName") : null)).append('|');
			sb.append(valueOrEmpty(s.hasField("role") ? s.get("role") : null)).append('|');
			sb.append(valueOrEmpty(s.hasField("condition") ? s.get("condition") : null)).append('|');
			sb.append(priority == null ? "" : priority.toString()).append('|');
			sb.append(String.join("\n", stringList(s.hasField("lines") ? s.get("lines") : null)));
			out.add(sb.toString());
		}
		return out;
	}

	private static String sectionNames(Set<String> normalized) {
		List<String> names = new ArrayList<>();
		for(String n : normalized) {
			int bar = n.indexOf('|');
			names.add(bar > 0 ? n.substring(0, bar) : n);
		}
		return names.isEmpty() ? "none" : String.join(",", names);
	}

	// ─────────────────────────────── stale scan ───────────────────────────────

	private static StaleScan scanStale(BaseRecord olioUser, long bookId, String slug, long orgId) {
		IOContext ioContext = IOSystem.getActiveContext();
		StaleScan scan = new StaleScan();
		BaseRecord wfGroup = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, PbBookUtil.workflowGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		BaseRecord bookGroup = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		BaseRecord artGroup = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, PbBookUtil.artifactGroupPath(slug), GroupEnumType.DATA.toString(), orgId);
		BaseRecord[] allGroups = new BaseRecord[] {wfGroup, bookGroup, artGroup};

		List<BaseRecord> staleWorkflows = new ArrayList<>();
		for(BaseRecord wf : rowsInGroups(OlioModelNames.MODEL_PB_WORKFLOW, allGroups, orgId, OlioFieldNames.FIELD_PB_BOOK)) {
			if(fkId(wf, OlioFieldNames.FIELD_PB_BOOK) == bookId) {
				scan.liveWorkflowIds.add(idOf(wf));
				scan.liveWorkflows.add(wf);
			}
			else {
				staleWorkflows.add(wf);
			}
		}
		List<BaseRecord> staleNodes = new ArrayList<>();
		for(BaseRecord node : rowsInGroups(OlioModelNames.MODEL_PB_NODE, allGroups, orgId, OlioFieldNames.FIELD_PB_WORKFLOW)) {
			if(scan.liveWorkflowIds.contains(fkId(node, OlioFieldNames.FIELD_PB_WORKFLOW))) {
				scan.liveNodeIds.add(idOf(node));
			}
			else {
				staleNodes.add(node);
			}
		}
		List<BaseRecord> staleRuns = new ArrayList<>();
		for(BaseRecord run : rowsInGroups(OlioModelNames.MODEL_PB_RUN, allGroups, orgId, OlioFieldNames.FIELD_PB_WORKFLOW)) {
			if(!scan.liveWorkflowIds.contains(fkId(run, OlioFieldNames.FIELD_PB_WORKFLOW))) {
				staleRuns.add(run);
			}
		}
		List<BaseRecord> staleBindings = new ArrayList<>();
		for(BaseRecord b : rowsInGroups(OlioModelNames.MODEL_PB_BINDING, allGroups, orgId, OlioFieldNames.FIELD_PB_NODE, OlioFieldNames.FIELD_PB_SOURCE_NODE)) {
			long node = fkId(b, OlioFieldNames.FIELD_PB_NODE);
			long source = fkId(b, OlioFieldNames.FIELD_PB_SOURCE_NODE);
			if(!scan.liveNodeIds.contains(node) || (source > 0 && !scan.liveNodeIds.contains(source))) {
				staleBindings.add(b);
			}
		}
		List<BaseRecord> staleArtifacts = new ArrayList<>();
		for(BaseRecord art : rowsInGroups(OlioModelNames.MODEL_PB_ARTIFACT, allGroups, orgId, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE)) {
			long node = fkId(art, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
			if(node > 0 && !scan.liveNodeIds.contains(node)) {
				staleArtifacts.add(art);
			}
		}
		List<BaseRecord> staleScenes = new ArrayList<>();
		for(BaseRecord s : rowsInGroups(OlioModelNames.MODEL_PB_SCENE, allGroups, orgId, OlioFieldNames.FIELD_PB_BOOK)) {
			if(fkId(s, OlioFieldNames.FIELD_PB_BOOK) != bookId) {
				staleScenes.add(s);
			}
		}
		List<BaseRecord> staleCast = new ArrayList<>();
		for(BaseRecord cg : rowsInGroups(OlioModelNames.MODEL_PB_CAST_GROUP, allGroups, orgId, OlioFieldNames.FIELD_PB_BOOK)) {
			long cgBook = fkId(cg, OlioFieldNames.FIELD_PB_BOOK);
			/// A series-scoped cast group carries no book FK and is not this book's to judge.
			if(cgBook > 0 && cgBook != bookId) {
				staleCast.add(cg);
			}
		}
		scan.stale.addAll(staleBindings);
		scan.stale.addAll(staleArtifacts);
		scan.stale.addAll(staleRuns);
		scan.stale.addAll(staleNodes);
		scan.stale.addAll(staleScenes);
		scan.stale.addAll(staleCast);
		scan.stale.addAll(staleWorkflows);
		return scan;
	}

	/** Raw, find-only rows of {@code model} in any of the given groups, projecting identity + the named FK fields. */
	static List<BaseRecord> rowsInGroups(String model, BaseRecord[] groups, long orgId, String... fkFields) {
		List<BaseRecord> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for(BaseRecord grp : groups) {
			if(grp == null) {
				continue;
			}
			try {
				Query q = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
				q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				List<String> req = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID));
				req.addAll(Arrays.asList(fkFields));
				q.setRequest(req.toArray(new String[0]));
				q.setCache(false);
				BaseRecord[] rows = IOSystem.getActiveContext().getSearch().findRecords(q);
				if(rows == null) {
					continue;
				}
				for(BaseRecord r : rows) {
					if(r.getSchema() == null) {
						r.setSchema(model);
					}
					if(seen.add(idOf(r))) {
						out.add(r);
					}
				}
			}
			catch(Exception e) {
				logger.warn("Scan of " + model + " in group #" + grp.get(FieldNames.FIELD_ID) + " failed: " + e.getMessage());
			}
		}
		return out;
	}

	// ─────────────────────────────── shared helpers ───────────────────────────────

	private static BaseRecord requireBook(BaseRecord user, String bookObjectId) {
		if(user == null) {
			throw new PictureBookException(401, "No user");
		}
		if(bookObjectId == null || bookObjectId.isBlank()) {
			throw new PictureBookException(400, "bookObjectId is required");
		}
		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgOf(user));
		if(book == null) {
			throw new PictureBookException(404, "Book not found");
		}
		return book;
	}

	private static boolean canHeal(BaseRecord user, BaseRecord book) {
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, book);
		if(prr != null && prr.getType() == PolicyResponseEnumType.PERMIT) {
			return true;
		}
		return Objects.equals(book.get(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID), user.get(FieldNames.FIELD_OBJECT_ID));
	}

	/** The same candidate set the PictureBook list shows: books in the organization stamped with the caller's objectId. */
	private static List<BaseRecord> readableBooks(BaseRecord user, long orgId) {
		List<BaseRecord> out = new ArrayList<>();
		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.field(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, user.get(FieldNames.FIELD_OBJECT_ID));
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
			q.setCache(false);
			q.setRequestRange(0, 400);
			BaseRecord[] rows = IOSystem.getActiveContext().getSearch().findRecords(q);
			if(rows != null) {
				for(BaseRecord r : rows) {
					BaseRecord book = PbBookUtil.readBook(user, r.get(FieldNames.FIELD_OBJECT_ID), orgId);
					if(book != null) {
						out.add(book);
					}
				}
			}
		}
		catch(Exception e) {
			logger.warn("Book enumeration failed: " + e.getMessage());
		}
		return out;
	}

	private static BaseRecord resolveSeries(BaseRecord user, BaseRecord seriesRef, long orgId) {
		String oid = seriesRef.get(FieldNames.FIELD_OBJECT_ID);
		if(oid != null) {
			BaseRecord s = PbSeriesUtil.readSeries(user, oid, orgId);
			if(s != null) {
				return s;
			}
		}
		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SERIES, FieldNames.FIELD_ID, seriesRef.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(PbSeriesUtil.seriesRequest());
			q.setCache(false);
			BaseRecord s = IOSystem.getActiveContext().getSearch().findRecord(q);
			if(s != null && s.getSchema() == null) {
				s.setSchema(OlioModelNames.MODEL_PB_SERIES);
			}
			return s;
		}
		catch(Exception e) {
			return null;
		}
	}

	private static BaseRecord findMetaNote(BaseRecord user, BaseRecord group, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, PictureBookUtil.META_NOTE_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_TEXT});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static boolean dataExists(String objectId, long orgId) {
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
			q.setCache(false);
			return IOSystem.getActiveContext().getSearch().findRecord(q) != null;
		}
		catch(Exception e) {
			return false;
		}
	}

	private static List<String> containerPaths(String slug) {
		return Arrays.asList(PbBookUtil.bookGroupPath(slug), PbBookUtil.workflowGroupPath(slug), PbBookUtil.artifactGroupPath(slug));
	}

	private static long orgOf(BaseRecord user) {
		Object o = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(!(o instanceof Number)) {
			throw new PictureBookException(500, "User record carries no organizationId");
		}
		return ((Number) o).longValue();
	}

	static long idOf(BaseRecord rec) {
		if(rec == null) {
			return 0L;
		}
		Object o = rec.get(FieldNames.FIELD_ID);
		return (o instanceof Number) ? ((Number) o).longValue() : 0L;
	}

	static long fkId(BaseRecord rec, String field) {
		Object o = rec.hasField(field) ? rec.get(field) : null;
		return (o instanceof BaseRecord) ? idOf((BaseRecord) o) : 0L;
	}

	private static Integer indexOf(Map<String, Object> entry) {
		Object idx = entry.get("index");
		return (idx instanceof Number) ? ((Number) idx).intValue() : null;
	}

	private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
		Object v = from.get(key);
		if(v != null && !v.toString().isBlank()) {
			to.put(key, v);
		}
	}

	private static List<String> stringList(Object o) {
		List<String> out = new ArrayList<>();
		if(o instanceof List) {
			for(Object e : (List<?>) o) {
				if(e != null) {
					out.add(e.toString());
				}
			}
		}
		return out;
	}

	private static String valueOrEmpty(Object o) {
		return o == null ? "" : o.toString();
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}

	private static boolean selected(String code, Set<String> codes) {
		return codes == null || codes.isEmpty() || codes.contains(code);
	}

	private static boolean findingPresent(BookAudit a, String code) {
		for(Finding f : a.findings) {
			if(f.code.equals(code)) {
				return true;
			}
		}
		return false;
	}

	private static boolean has(BookAudit a, String code, Set<String> codes) {
		if(!selected(code, codes)) {
			return false;
		}
		for(Finding f : a.findings) {
			if(f.code.equals(code) && f.healable) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, Object> done(String code, BaseRecord rec, String action) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("code", code);
		Map<String, Object> refs = new LinkedHashMap<>();
		if(rec != null) {
			refs.put("model", rec.getSchema());
			refs.put("id", rec.get(FieldNames.FIELD_ID));
			refs.put("objectId", rec.get(FieldNames.FIELD_OBJECT_ID));
			refs.put("name", rec.get(FieldNames.FIELD_NAME));
		}
		m.put("refs", refs);
		m.put("action", action);
		return m;
	}

	private static Map<String, Object> skipRaw(String code, BaseRecord rec, String reason) {
		Map<String, Object> m = done(code, rec, null);
		m.remove("action");
		m.put("reason", reason);
		return m;
	}

	private static Map<String, Object> skip(Finding f, String reason) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("code", f.code);
		m.put("refs", new LinkedHashMap<>(f.refs));
		m.put("reason", reason);
		return m;
	}

	private static Finding withBook(Finding f, BookAudit a) {
		f.ref("slug", a.slug);
		f.ref("bookObjectId", a.bookObjectId);
		return f;
	}

	private static List<Finding> tagWithBook(BookAudit a) {
		for(Finding f : a.findings) {
			withBook(f, a);
		}
		return a.findings;
	}

	private static Map<String, Object> report(String scope, BookAudit a, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("scope", scope);
		m.put("bookObjectId", a.bookObjectId);
		m.put("slug", a.slug);
		m.put("checkedAt", ZonedDateTime.now().toString());
		fillFindings(m, a.findings, healed, skipped);
		return m;
	}

	private static Map<String, Object> orgReport(List<Finding> findings, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("scope", "org");
		m.put("checkedAt", ZonedDateTime.now().toString());
		fillFindings(m, findings, healed, skipped);
		return m;
	}

	private static void fillFindings(Map<String, Object> m, List<Finding> findings, List<Map<String, Object>> healed, List<Map<String, Object>> skipped) {
		List<Map<String, Object>> out = new ArrayList<>();
		int errors = 0, warnings = 0, infos = 0, healable = 0;
		for(Finding f : findings) {
			out.add(f.toMap());
			if(SEV_ERROR.equals(f.severity)) errors++;
			else if(SEV_WARN.equals(f.severity)) warnings++;
			else infos++;
			if(f.healable) healable++;
		}
		m.put("findings", out);
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("errors", errors);
		summary.put("warnings", warnings);
		summary.put("infos", infos);
		summary.put("healable", healable);
		m.put("summary", summary);
		m.put("healed", healed != null ? healed : new ArrayList<>());
		m.put("skipped", skipped != null ? skipped : new ArrayList<>());
	}
}
