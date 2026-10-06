package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;

/**
 * One delete path for a PictureBook / ChapBook / series chapter that removes EVERY entity the book
 * created, so a same-slug recreate on any environment starts clean and nothing is left for an orphan
 * sweep to find. {@link PictureBookUtil#reset}, {@code ChapBookUtil.deleteChapBook} and
 * {@code PbSeriesUtil.deleteSeries} all route through {@link #deleteBookComplete}.
 * <p>
 * Authorization is decided once, as the acting user, before anything physical happens: a readable book
 * via {@code canDelete}; an unreadable (incomplete / ungranted) row via the creator guard in
 * {@link PictureBookUtil#deleteIncompleteBookAsOlio}. The physical deletes of olio-owned rows then run
 * as the olio principal (the legitimate owner), and the caller's own {@code ~/Data/PictureBooks/<slug>}
 * tree is deleted as the caller. Every step is find-then-delete and tolerates an already-absent piece,
 * so the method is idempotent; a second call on a fully removed book raises 404.
 * <p>
 * Footprint, in order: cast groups and graph rows referencing the book by FK (wherever they live);
 * per-book roles; the {@code <Worlds>/<slug>/{Book,Workflow,Artifacts}} groups, the world (unless it is a
 * series' shared world), the residual row and the cached context ({@link PictureBookUtil#teardownBookFootprintAsOlio});
 * the caller's {@code ~/Data/PictureBooks/<slug>} tree and any {@code .pictureBookMeta} note linked to the
 * book; the {@code olio.pb.sourceRange} row; extraction checkpoints for the source document when no other
 * book still references it; the book context cache; and finally the generic orphan sweep for
 * {@code olio.pb.book}.
 */
public class PbDeleteUtil {
	private static final Logger logger = LogManager.getLogger(PbDeleteUtil.class);

	private PbDeleteUtil() {}

	/**
	 * @param user         the acting user
	 * @param bookObjectId an {@code olio.pb.book} objectId, or a legacy PB1 {@code auth.group} objectId
	 * @return a {@link DeleteResult} whose {@code steps} list records every footprint piece touched
	 * @throws PictureBookException 400/401 on missing arguments, 404 when nothing by that id exists, 403
	 *         when an unreadable row exists but the caller is not its creator
	 */
	public static DeleteResult deleteBookComplete(BaseRecord user, String bookObjectId) {
		if (user == null) {
			throw new PictureBookException(401, "Not authenticated");
		}
		if (bookObjectId == null || bookObjectId.isBlank()) {
			throw new PictureBookException(400, "bookObjectId is required");
		}
		IOContext ioContext = IOSystem.getActiveContext();
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		DeleteResult res = DeleteResult.ok();

		// Legacy PB1 id: the caller's own book GROUP. Tree-only delete, then follow the meta link to the
		// PB2 row (if any) so the two halves of a migrated book never survive each other.
		BaseRecord legacyGroup = PictureBookUtil.findBookGroup(user, bookObjectId);
		if (legacyGroup != null) {
			String pb2Oid = metaPb2BookObjectId(user, legacyGroup.get(FieldNames.FIELD_PATH));
			deleteUxTree(user, legacyGroup, res);
			if (pb2Oid != null && !pb2Oid.equals(bookObjectId)) {
				try {
					DeleteResult pb2 = deleteBookComplete(user, pb2Oid);
					res.steps.addAll(pb2.steps);
					if (!pb2.deleted) {
						fail(res, pb2.reason);
					}
				} catch (PictureBookException e) {
					if (e.getStatus() != 404) {
						throw e;
					}
				}
			}
			return res;
		}

		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgId);
		if (book != null) {
			PolicyResponseType prr = ioContext.getAuthorizationUtil().canDelete(user, user, book);
			if (prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
				String reason = "Not authorized to delete this book " + bookObjectId;
				logger.warn(reason);
				return DeleteResult.denied(reason);
			}
		} else {
			book = rawBookRow(bookObjectId, orgId);
			if (book == null) {
				int notes = PictureBookUtil.deleteOrphanedMetaNotes(user, bookObjectId);
				if (notes > 0) {
					logger.info("deleteBookComplete: removed " + notes + " stray meta note(s) for gone book " + bookObjectId);
				}
				throw new PictureBookException(404, "Book not found");
			}
			// Creator guard lives here (throws 403 for a stranger); it also removes the Book group + row.
			boolean removed = PictureBookUtil.deleteIncompleteBookAsOlio(user, bookObjectId, orgId);
			res.step("incompleteBookRow", OlioModelNames.MODEL_PB_BOOK, removed ? 1 : 0, true, null);
		}

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if (olioUser == null) {
			olioUser = user;
		}
		String slug = book.hasField(OlioFieldNames.FIELD_PB_SLUG) ? book.get(OlioFieldNames.FIELD_PB_SLUG) : null;
		BaseRecord sourceRange = book.hasField(OlioFieldNames.FIELD_PB_SOURCE_RANGE) ? book.get(OlioFieldNames.FIELD_PB_SOURCE_RANGE) : null;
		BaseRecord sourceData = book.hasField(OlioFieldNames.FIELD_PB_SOURCE_DATA) ? book.get(OlioFieldNames.FIELD_PB_SOURCE_DATA) : null;

		// 1. Rows that point at this book by FK, wherever they were grouped. The group walk below also
		// catches the normally-placed ones; this is what reaches a stale graph left under another container.
		deleteGraphByBook(olioUser, book, orgId, res);

		// 2. Per-book roles (olio-owned, under the olio home). Universe-level Reader/Writer are shared.
		if (slug != null && !slug.isBlank()) {
			deleteBookRoles(olioUser, slug, orgId, res);
		}

		// 3. Book/Workflow/Artifacts groups, world (never a series' shared world), residual row, context eviction.
		DeleteResult footprint = PictureBookUtil.teardownBookFootprintAsOlio(olioUser, book, orgId);
		res.step("footprint", OlioModelNames.MODEL_PB_BOOK, 1, footprint.deleted, footprint.deleted ? null : footprint.reason);
		if (!footprint.deleted) {
			fail(res, footprint.reason);
		}

		// 4. The caller's own ~/Data/PictureBooks/<slug> tree (scenes, characters, images, meta).
		if (slug != null && !slug.isBlank()) {
			BaseRecord uxGroup = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_GROUP,
				"~/Data/" + PictureBookUtil.PICTURE_BOOKS_DIR + "/" + slug, GroupEnumType.DATA.toString(), orgId);
			if (uxGroup != null) {
				deleteUxTree(user, uxGroup, res);
			}
		}
		// Any meta note still linked to this book by its JSON (a tree under a different slug, or a note
		// whose group was already gone) — this is what keeps a deleted book out of the legacy list.
		int metaNotes = PictureBookUtil.deleteOrphanedMetaNotes(user, bookObjectId);
		res.step("metaNotes", ModelNames.MODEL_NOTE, metaNotes, true, null);

		// 5. The chapter's source range row (owned by whoever created the chapter; no back-reference).
		deleteSourceRange(user, olioUser, sourceRange, orgId, res);

		// 6. Extraction checkpoints for the source document, unless another book still extracts from it.
		deleteCheckpointsIfUnreferenced(user, book, sourceData, orgId, res);

		// 7. Caches + the generic orphan sweep for the book model.
		if (slug != null && !slug.isBlank()) {
			try {
				PbOlioContextUtil.evictBookContext(user, slug);
			} catch (Exception e) {
				logger.warn("deleteBookComplete: evictBookContext failed for slug=" + slug + ": " + e.getMessage());
			}
		}
		boolean swept = RecordFactory.cleanupOrphansExplained(OlioModelNames.MODEL_PB_BOOK);
		res.step("cleanupOrphans", OlioModelNames.MODEL_PB_BOOK, 0, swept, swept ? null : "cleanupOrphans failed; see server log");

		logger.info("deleteBookComplete: book " + bookObjectId + " (slug=" + slug + ") " + (res.deleted ? "fully removed" : "removed with failures: " + res.reason));
		return res;
	}

	/**
	 * Delete every graph row that references {@code book} by FK — scenes, cast groups, and each workflow
	 * with its bindings, artifacts, runs and nodes (child→parent). Shared with the health check's stale-graph
	 * purge, which hands it a dead book stand-in carrying only {@code id}.
	 *
	 * @return the number of rows deleted
	 */
	static int deleteGraphByBook(BaseRecord actor, BaseRecord book, long orgId, DeleteResult res) {
		int total = 0;
		total += deleteByFk(actor, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, book, orgId, res, "scenesByBook");
		total += deleteByFk(actor, OlioModelNames.MODEL_PB_CAST_GROUP, OlioFieldNames.FIELD_PB_BOOK, book, orgId, res, "castGroupsByBook");
		for (BaseRecord wf : findByFk(OlioModelNames.MODEL_PB_WORKFLOW, OlioFieldNames.FIELD_PB_BOOK, book, orgId)) {
			total += deleteWorkflowGraph(actor, wf, orgId, res);
		}
		return total;
	}

	/**
	 * Delete one workflow and everything hanging off it: bindings (by node and by sourceNode), artifacts
	 * (by producedByNode), runs, nodes, then the workflow row.
	 */
	static int deleteWorkflowGraph(BaseRecord actor, BaseRecord workflow, long orgId, DeleteResult res) {
		int total = 0;
		List<BaseRecord> nodes = findByFk(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_WORKFLOW, workflow, orgId);
		for (BaseRecord node : nodes) {
			total += deleteByFk(actor, OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE, node, orgId, res, "bindingsByNode");
			total += deleteByFk(actor, OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_SOURCE_NODE, node, orgId, res, "bindingsBySourceNode");
			total += deleteByFk(actor, OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, node, orgId, res, "artifactsByNode");
		}
		total += deleteByFk(actor, OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, workflow, orgId, res, "runsByWorkflow");
		if (!nodes.isEmpty()) {
			int n = 0;
			for (BaseRecord node : nodes) {
				if (deleteOne(actor, node, res)) n++;
			}
			res.step("nodesByWorkflow", OlioModelNames.MODEL_PB_NODE, n, n == nodes.size(), n == nodes.size() ? null : (nodes.size() - n) + " node(s) not deleted");
			total += n;
		}
		boolean wfOk = deleteOne(actor, workflow, res);
		res.step("workflow", OlioModelNames.MODEL_PB_WORKFLOW, wfOk ? 1 : 0, wfOk, wfOk ? null : "workflow row was not deleted");
		if (wfOk) total++;
		return total;
	}

	/** Raw (unauthorized, find-only) lookup of rows whose {@code fkField} points at {@code target}, org-scoped. */
	static List<BaseRecord> findByFk(String model, String fkField, BaseRecord target, long orgId) {
		List<BaseRecord> out = new ArrayList<>();
		if (target == null || target.get(FieldNames.FIELD_ID) == null) {
			return out;
		}
		try {
			Query q = QueryUtil.createQuery(model, fkField, target);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID });
			q.setCache(false);
			BaseRecord[] rows = IOSystem.getActiveContext().getSearch().findRecords(q);
			if (rows != null) {
				for (BaseRecord r : rows) {
					if (r.getSchema() == null) {
						r.setSchema(model);
					}
					out.add(r);
				}
			}
		} catch (Exception e) {
			logger.warn("findByFk " + model + "." + fkField + " failed: " + e.getMessage());
		}
		return out;
	}

	private static int deleteByFk(BaseRecord actor, String model, String fkField, BaseRecord target, long orgId, DeleteResult res, String stepName) {
		List<BaseRecord> rows = findByFk(model, fkField, target, orgId);
		if (rows.isEmpty()) {
			return 0;
		}
		int n = 0;
		for (BaseRecord r : rows) {
			if (deleteOne(actor, r, res)) n++;
		}
		res.step(stepName, model, n, n == rows.size(), n == rows.size() ? null : (rows.size() - n) + " row(s) not deleted");
		return n;
	}

	private static boolean deleteOne(BaseRecord actor, BaseRecord rec, DeleteResult res) {
		DeleteResult d = PictureBookUtil.deleteRecordExplained(actor, rec);
		if (!d.deleted) {
			fail(res, d.reason);
		}
		return d.deleted;
	}

	private static void deleteBookRoles(BaseRecord olioUser, String slug, long orgId, DeleteResult res) {
		int n = 0;
		boolean ok = true;
		for (String rolePath : new String[] { PbOlioContextUtil.writerRolePath(slug), PbOlioContextUtil.adminRolePath(slug),
				PbOlioContextUtil.BOOK_ROLE_BASE + "/" + slug }) {
			try {
				BaseRecord role = IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, rolePath,
					RoleEnumType.USER.toString(), orgId);
				if (role == null) {
					continue;
				}
				if (deleteOne(olioUser, role, res)) {
					n++;
				} else {
					ok = false;
				}
			} catch (Exception e) {
				logger.warn("deleteBookRoles: " + rolePath + ": " + e.getMessage());
				ok = false;
				fail(res, "Failed to delete role " + rolePath + ": " + e.getMessage());
			}
		}
		res.step("bookRoles", ModelNames.MODEL_ROLE, n, ok, ok ? null : "one or more book roles were not deleted");
	}

	/** The caller's PB1 tree: Scenes/Characters subgroups, images, meta note, then the group itself. */
	private static void deleteUxTree(BaseRecord user, BaseRecord group, DeleteResult res) {
		boolean ok;
		try {
			ok = PictureBookUtil.deleteGroupRecursive(user, group);
		} catch (Exception e) {
			logger.warn("deleteUxTree: " + group.get(FieldNames.FIELD_PATH) + ": " + e.getMessage());
			ok = false;
		}
		res.step("uxTree", ModelNames.MODEL_GROUP, 1, ok, ok ? null : "Recursive delete of " + group.get(FieldNames.FIELD_PATH) + " failed; see server log.");
		if (!ok) {
			fail(res, "Recursive delete of the book group " + group.get(FieldNames.FIELD_PATH) + " failed; see server log.");
		}
	}

	private static String metaPb2BookObjectId(BaseRecord user, String groupPath) {
		if (groupPath == null) {
			return null;
		}
		try {
			BaseRecord meta = PictureBookUtil.loadMeta(user, groupPath);
			if (meta == null) {
				return null;
			}
			String text = meta.get(FieldNames.FIELD_TEXT);
			if (text == null || text.isBlank()) {
				return null;
			}
			Map<String, Object> m = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
			Object v = (m != null) ? m.get("pb2BookObjectId") : null;
			return (v instanceof String && !((String) v).isBlank()) ? (String) v : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static void deleteSourceRange(BaseRecord user, BaseRecord olioUser, BaseRecord sourceRange, long orgId, DeleteResult res) {
		if (sourceRange == null || sourceRange.get(FieldNames.FIELD_ID) == null
				|| ((Number) sourceRange.get(FieldNames.FIELD_ID)).longValue() <= 0L) {
			return;
		}
		try {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SOURCE_RANGE, FieldNames.FIELD_ID, sourceRange.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_OWNER_ID });
			q.setCache(false);
			BaseRecord range = IOSystem.getActiveContext().getSearch().findRecord(q);
			if (range == null) {
				return;
			}
			// The range is owned by whoever created the chapter (persistSourceProvenance applies the acting
			// user's ownership); try the caller first, then the olio principal.
			DeleteResult d = PictureBookUtil.deleteRecordExplained(user, range);
			if (!d.deleted && olioUser != user) {
				d = PictureBookUtil.deleteRecordExplained(olioUser, range);
			}
			res.step("sourceRange", OlioModelNames.MODEL_PB_SOURCE_RANGE, d.deleted ? 1 : 0, d.deleted, d.deleted ? null : d.reason);
			if (!d.deleted) {
				fail(res, d.reason);
			}
		} catch (Exception e) {
			logger.warn("deleteSourceRange failed: " + e.getMessage());
			res.step("sourceRange", OlioModelNames.MODEL_PB_SOURCE_RANGE, 0, false, e.getMessage());
			fail(res, "Failed to delete the source range: " + e.getMessage());
		}
	}

	private static void deleteCheckpointsIfUnreferenced(BaseRecord user, BaseRecord book, BaseRecord sourceData, long orgId, DeleteResult res) {
		if (sourceData == null || sourceData.get(FieldNames.FIELD_ID) == null) {
			return;
		}
		try {
			String sourceOid = sourceData.hasField(FieldNames.FIELD_OBJECT_ID) ? sourceData.get(FieldNames.FIELD_OBJECT_ID) : null;
			if (sourceOid == null) {
				Query dq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, sourceData.get(FieldNames.FIELD_ID));
				dq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				dq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
				dq.setCache(false);
				BaseRecord d = IOSystem.getActiveContext().getSearch().findRecord(dq);
				sourceOid = (d != null) ? d.get(FieldNames.FIELD_OBJECT_ID) : null;
			}
			if (sourceOid == null) {
				return;
			}
			String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
			for (BaseRecord other : findByFk(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SOURCE_DATA, sourceData, orgId)) {
				if (!bookOid.equals(other.get(FieldNames.FIELD_OBJECT_ID))) {
					res.step("checkpoints", ModelNames.MODEL_NOTE, 0, true, "kept: source document still referenced by book " + other.get(FieldNames.FIELD_OBJECT_ID));
					return;
				}
			}
			int n = PictureBookUtil.deleteOrphanedExtractCheckpoints(user, sourceOid);
			res.step("checkpoints", ModelNames.MODEL_NOTE, n, true, null);
		} catch (Exception e) {
			logger.warn("deleteCheckpointsIfUnreferenced failed: " + e.getMessage());
			res.step("checkpoints", ModelNames.MODEL_NOTE, 0, false, e.getMessage());
		}
	}

	/** Raw (olio-owned rows are invisible to an ungranted caller) lookup of a book row by objectId. */
	static BaseRecord rawBookRow(String bookObjectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, OlioFieldNames.FIELD_PB_BOOK_STATUS,
			OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_SERIES, OlioFieldNames.FIELD_PB_SOURCE_DATA,
			OlioFieldNames.FIELD_PB_SOURCE_RANGE });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private static void fail(DeleteResult res, String reason) {
		res.deleted = false;
		if (res.reason == null && reason != null) {
			res.reason = reason;
		}
	}
}
