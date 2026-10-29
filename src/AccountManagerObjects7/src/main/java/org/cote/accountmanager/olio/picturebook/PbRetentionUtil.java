package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.picturebook.PbRetentionSelector.Candidate;
import org.cote.accountmanager.olio.picturebook.PbRetentionSelector.Decision;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

/**
 * The §23 retention sweep: project every artifact of a book (or of every book in the organization) into
 * {@link PbRetentionSelector} candidates, and either report the policy's decisions ({@link #scanBook} /
 * {@link #scanOrg}, a dry run that writes nothing) or act on them ({@link #applyBook} / {@link #applyOrg}).
 * <p>
 * <b>Everything is done as the caller through {@code AccessPoint}.</b> A dry run reads what the caller may
 * read; an apply archives through {@link PbArtifactUtil#archive} and deletes bytes through
 * {@link PictureBookUtil#deleteRecordExplained}, so an artifact the caller may not update - or a
 * {@code data.data} they may not delete - is reported {@link #OUTCOME_DENIED} per item and left alone. Nothing
 * here runs as the organization admin or the olio principal. The apply re-runs the scan and acts on exactly
 * that item set, like {@link PbOrphanUtil#purge}, so a dry run and an apply cannot diverge.
 * <p>
 * <b>Deleting bytes</b> is: the artifact's {@code data.data} row removed as the caller, then the artifact's
 * {@code data} reference cleared with a second patch (PATCH does not cascade - model-api.md). The artifact row
 * itself stays, with its {@code byteLength}, {@code contentHash}, seed and sanitized request intact, so the
 * attempt is still explainable after its pixels are gone. The reference is cleared <i>after</i> the row is
 * deleted; a clear that then fails is reported {@link #OUTCOME_FAILED} naming the dangling reference, and the
 * next sweep reads a missing row as "no bytes" and repairs it.
 * <p>
 * {@code K} (attempts kept per role) and {@code D} (days after archival before bytes go) default to
 * {@link PbRetentionSelector#DEFAULT_KEEP_ATTEMPTS} / {@link PbRetentionSelector#DEFAULT_DELETE_BYTES_AFTER_DAYS}
 * and are overridable per request, never persisted on the book (§6.C 212). Out-of-range values are a 400.
 * There is no scheduler: a sweep runs when somebody asks for it.
 */
public final class PbRetentionUtil {
	private static final Logger logger = LogManager.getLogger(PbRetentionUtil.class);

	public static final String SCOPE_BOOK = "book";
	public static final String SCOPE_ORG = "org";

	/** The artifact was marked ARCHIVED (and stamped) by this apply. */
	public static final String OUTCOME_ARCHIVED = "archived";
	/** The artifact's {@code data.data} was deleted and its {@code data} reference cleared. */
	public static final String OUTCOME_BYTES_DELETED = "bytesDeleted";
	/** KEEP or NONE: nothing was written. */
	public static final String OUTCOME_KEPT = "kept";
	/** {@link PbArtifactUtil#archive} refused (409): selected or APPROVED since the scan. */
	public static final String OUTCOME_REFUSED = "refused";
	/** PBAC denied the archive patch or the bytes delete for the caller. */
	public static final String OUTCOME_DENIED = "denied";
	/** Authorized, but the write failed; {@code reason} names what. */
	public static final String OUTCOME_FAILED = "failed";

	/** Upper bound on book rows enumerated by an org-wide sweep. */
	static final int ORG_BOOK_CAP = 2000;

	private PbRetentionUtil() {
		/// static utility
	}

	// ─────────────────────────────── policy ───────────────────────────────

	/** The validated per-request policy parameters. */
	public static final class Policy {
		public final int keepAttempts;
		public final int deleteBytesAfterDays;

		private Policy(int keepAttempts, int deleteBytesAfterDays) {
			this.keepAttempts = keepAttempts;
			this.deleteBytesAfterDays = deleteBytesAfterDays;
		}

		/**
		 * @param keepAttempts K, or null for {@link PbRetentionSelector#DEFAULT_KEEP_ATTEMPTS}
		 * @param deleteBytesAfterDays D, or null for {@link PbRetentionSelector#DEFAULT_DELETE_BYTES_AFTER_DAYS}
		 * @throws PictureBookException 400 when either is outside the selector's bounds
		 */
		public static Policy of(Integer keepAttempts, Integer deleteBytesAfterDays) {
			int k = (keepAttempts != null ? keepAttempts.intValue() : PbRetentionSelector.DEFAULT_KEEP_ATTEMPTS);
			int d = (deleteBytesAfterDays != null ? deleteBytesAfterDays.intValue() : PbRetentionSelector.DEFAULT_DELETE_BYTES_AFTER_DAYS);
			if(k < 1 || k > PbRetentionSelector.MAX_KEEP_ATTEMPTS) {
				throw new PictureBookException(400, "keepAttempts must be 1.." + PbRetentionSelector.MAX_KEEP_ATTEMPTS + "; got " + k);
			}
			if(d < 0 || d > PbRetentionSelector.MAX_DELETE_BYTES_AFTER_DAYS) {
				throw new PictureBookException(400, "deleteBytesAfterDays must be 0.." + PbRetentionSelector.MAX_DELETE_BYTES_AFTER_DAYS + "; got " + d);
			}
			return new Policy(k, d);
		}

		public static Policy defaults() {
			return of(null, null);
		}
	}

	// ─────────────────────────────── public API ───────────────────────────────

	/**
	 * Dry run over one book. The caller has already been shown to read the book and its workflow
	 * ({@code PbServiceFacade.requireBook}/{@code requireWorkflow}); nothing is written.
	 */
	public static Map<String, Object> scanBook(BaseRecord user, BaseRecord book, BaseRecord workflow, Policy policy) {
		requireUser(user);
		Policy p = (policy != null ? policy : Policy.defaults());
		Sweep s = new Sweep(SCOPE_BOOK, p, false);
		sweepBook(user, book, workflow, s);
		return s.toMap();
	}

	/**
	 * Apply over one book: the scan, then every ARCHIVE and DELETE_BYTES decision acted on as the caller. The
	 * caller must already hold UPDATE on the book ({@code PbServiceFacade.requireBookUpdate}) - the per-artifact
	 * PBAC checks still run, so a collaborator who may update the book but not a particular artifact sees
	 * {@link #OUTCOME_DENIED} on it rather than a sweep-wide failure.
	 */
	public static Map<String, Object> applyBook(BaseRecord user, BaseRecord book, BaseRecord workflow, Policy policy) {
		requireUser(user);
		Policy p = (policy != null ? policy : Policy.defaults());
		Sweep s = new Sweep(SCOPE_BOOK, p, true);
		sweepBook(user, book, workflow, s);
		logger.info("Retention apply (book " + book.get(OlioFieldNames.FIELD_PB_SLUG) + ") by " + user.get(FieldNames.FIELD_NAME)
			+ ": archived=" + s.archived + " bytesDeleted=" + s.bytesDeleted + " bytesFreed=" + s.bytesFreed
			+ " refused=" + s.refused + " denied=" + s.denied + " failed=" + s.failed);
		return s.toMap();
	}

	/** Dry run over every book of the caller's organization. Requires {@code AccountAdministrators}. */
	public static Map<String, Object> scanOrg(BaseRecord user, Policy policy) {
		long orgId = requireOrgScope(user);
		Policy p = (policy != null ? policy : Policy.defaults());
		Sweep s = new Sweep(SCOPE_ORG, p, false);
		sweepOrg(user, orgId, s);
		return s.toMap();
	}

	/** Apply over every book of the caller's organization. Requires {@code AccountAdministrators}. */
	public static Map<String, Object> applyOrg(BaseRecord user, Policy policy) {
		long orgId = requireOrgScope(user);
		Policy p = (policy != null ? policy : Policy.defaults());
		Sweep s = new Sweep(SCOPE_ORG, p, true);
		sweepOrg(user, orgId, s);
		logger.info("Retention apply (org " + orgId + ") by " + user.get(FieldNames.FIELD_NAME)
			+ ": books=" + s.books + " archived=" + s.archived + " bytesDeleted=" + s.bytesDeleted + " bytesFreed=" + s.bytesFreed
			+ " refused=" + s.refused + " denied=" + s.denied + " failed=" + s.failed);
		return s.toMap();
	}

	// ─────────────────────────────── sweep ───────────────────────────────

	private static final class Sweep {
		final String scope;
		final Policy policy;
		final boolean apply;
		final ZonedDateTime now = ZonedDateTime.now();
		final List<Map<String, Object>> items = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		String bookObjectId;
		String slug;
		int books;
		int booksSkipped;
		int nodes;
		/// decision counts (scan and apply)
		int keep;
		int archive;
		int deleteBytes;
		int none;
		long bytesReclaimable;
		/// outcome counts (apply only)
		int archived;
		int bytesDeleted;
		long bytesFreed;
		int refused;
		int denied;
		int failed;

		Sweep(String scope, Policy policy, boolean apply) {
			this.scope = scope;
			this.policy = policy;
			this.apply = apply;
		}

		void warn(String w) {
			warnings.add(w);
			logger.warn("Retention sweep: " + w);
		}

		Map<String, Object> toMap() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("scope", scope);
			if(SCOPE_BOOK.equals(scope)) {
				out.put("bookObjectId", bookObjectId);
				out.put("slug", slug);
			}
			else {
				out.put("books", Integer.valueOf(books));
				out.put("booksSkipped", Integer.valueOf(booksSkipped));
			}
			out.put(apply ? "appliedAt" : "checkedAt", now.toString());
			out.put("keepAttempts", Integer.valueOf(policy.keepAttempts));
			out.put("deleteBytesAfterDays", Integer.valueOf(policy.deleteBytesAfterDays));
			out.put("nodes", Integer.valueOf(nodes));
			out.put("items", items);
			out.put("total", Integer.valueOf(items.size()));
			Map<String, Object> counts = new LinkedHashMap<>();
			counts.put("keep", Integer.valueOf(keep));
			counts.put("archive", Integer.valueOf(archive));
			counts.put("deleteBytes", Integer.valueOf(deleteBytes));
			counts.put("none", Integer.valueOf(none));
			out.put("counts", counts);
			out.put("bytesReclaimable", Long.valueOf(bytesReclaimable));
			if(apply) {
				out.put("archived", Integer.valueOf(archived));
				out.put("bytesDeleted", Integer.valueOf(bytesDeleted));
				out.put("bytesFreed", Long.valueOf(bytesFreed));
				out.put("refused", Integer.valueOf(refused));
				out.put("denied", Integer.valueOf(denied));
				out.put("failed", Integer.valueOf(failed));
			}
			out.put("warnings", new ArrayList<>(warnings));
			return out;
		}
	}

	private static void sweepOrg(BaseRecord user, long orgId, Sweep s) {
		/// Candidate rows are enumerated raw (book rows are olio-principal-owned, see PbServiceFacade.listBooks) and
		/// each is then read as the acting user, which is the authorization boundary; a book the admin cannot
		/// read is counted and named, never swept blind.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_SLUG});
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setRequestRange(0, ORG_BOOK_CAP);
		q.setCache(false);
		BaseRecord[] rows = null;
		try {
			rows = IOSystem.getActiveContext().getSearch().findRecords(q);
		}
		catch(Exception e) {
			s.warn("Book enumeration failed: " + e.getMessage());
		}
		if(rows == null) {
			return;
		}
		if(rows.length >= ORG_BOOK_CAP) {
			s.warn("Book enumeration hit the cap of " + ORG_BOOK_CAP + " rows; the sweep is partial");
		}
		for(BaseRecord row : rows) {
			String oid = row.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord book = (oid != null ? PbBookUtil.readBook(user, oid, orgId) : null);
			if(book == null) {
				s.booksSkipped++;
				s.warn("Book " + (oid != null ? oid : "?") + " (" + row.get(OlioFieldNames.FIELD_PB_SLUG) + ") is not readable by the caller; skipped");
				continue;
			}
			BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
			if(workflow == null) {
				/// Never generated: no artifacts to retain.
				s.booksSkipped++;
				continue;
			}
			s.books++;
			sweepBook(user, book, workflow, s);
		}
	}

	private static void sweepBook(BaseRecord user, BaseRecord book, BaseRecord workflow, Sweep s) {
		if(book == null || workflow == null) {
			throw new PictureBookException(400, "A book and its workflow are required");
		}
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(SCOPE_BOOK.equals(s.scope)) {
			s.bookObjectId = bookOid;
			s.slug = slug;
		}
		for(BaseRecord node : PbGraphUtil.listNodes(user, workflow)) {
			s.nodes++;
			List<BaseRecord> artifacts = listNodeArtifacts(user, node);
			if(artifacts.isEmpty()) {
				continue;
			}
			List<Candidate> candidates = new ArrayList<>(artifacts.size());
			for(BaseRecord a : artifacts) {
				candidates.add(candidateOf(a));
			}
			List<Decision> decisions = PbRetentionSelector.select(candidates, s.policy.keepAttempts, s.policy.deleteBytesAfterDays, s.now);
			for(int i = 0; i < decisions.size(); i++) {
				Decision d = decisions.get(i);
				BaseRecord artifact = artifacts.get(i);
				Map<String, Object> item = itemOf(d, artifact, node, bookOid, slug, s);
				count(s, d, artifact);
				if(s.apply) {
					act(user, d, artifact, item, s);
				}
				s.items.add(item);
			}
		}
	}

	// ─────────────────────────────── projection ───────────────────────────────

	/** Every artifact of {@code node}, all roles, read as the caller, uncached, ascending by revision. */
	static List<BaseRecord> listNodeArtifacts(BaseRecord user, BaseRecord node) {
		/// A condition on a FOREIGN MODEL field takes the RECORD, never its id (PbArtifactUtil.listChain).
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, node);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(node));
		q.setRequest(PbArtifactUtil.artifactRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, OlioFieldNames.FIELD_PB_REVISION);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? PbGraphUtil.restoreSchema(new ArrayList<>(Arrays.asList(recs)), OlioModelNames.MODEL_PB_ARTIFACT)
			: Collections.emptyList());
	}

	static Candidate candidateOf(BaseRecord a) {
		Integer rev = a.get(OlioFieldNames.FIELD_PB_REVISION);
		Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
		return new Candidate(
			a.get(FieldNames.FIELD_OBJECT_ID),
			a.get(FieldNames.FIELD_NAME),
			a.get(OlioFieldNames.FIELD_PB_ROLE),
			PbArtifactUtil.attemptOf(a),
			PbArtifactUtil.variantIndexOf(a),
			(rev != null ? rev.intValue() : 0),
			PbArtifactUtil.reviewStatusOf(a),
			(sel != null && sel.booleanValue()),
			PbArtifactUtil.archivedAtOf(a),
			hasBytes(a),
			PbArtifactUtil.referenceArtifactIdsOf(a)
		);
	}

	/** A {@code data} reference is "bytes"; a TEXT/JSON artifact ({@code artifactText}) has none to delete. */
	static boolean hasBytes(BaseRecord artifact) {
		return PbHealthUtil.fkId(artifact, OlioFieldNames.FIELD_PB_DATA) > 0L;
	}

	private static Map<String, Object> itemOf(Decision d, BaseRecord artifact, BaseRecord node, String bookOid, String slug, Sweep s) {
		Candidate c = d.getCandidate();
		Map<String, Object> m = new LinkedHashMap<>();
		if(SCOPE_ORG.equals(s.scope)) {
			m.put("bookObjectId", bookOid);
			m.put("slug", slug);
		}
		m.put("objectId", c.getObjectId());
		m.put("name", c.getName());
		m.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		m.put("nodeHandle", node.hasField(OlioFieldNames.FIELD_PB_HANDLE) ? node.get(OlioFieldNames.FIELD_PB_HANDLE) : null);
		m.put("role", c.getRole());
		m.put("attempt", c.getAttempt());
		m.put("variantIndex", Integer.valueOf(c.getVariantIndex()));
		m.put("revision", Integer.valueOf(c.getRevision()));
		m.put("reviewStatus", c.getReviewStatus().name());
		m.put("selected", Boolean.valueOf(c.isSelected()));
		m.put("hasBytes", Boolean.valueOf(c.hasBytes()));
		long len = byteLength(artifact);
		m.put("byteLength", (len > 0L ? Long.valueOf(len) : null));
		m.put("archivedAt", (c.getArchivedAt() != null ? c.getArchivedAt().toString() : null));
		m.put("action", d.getAction().name());
		m.put("reason", d.getReason());
		m.put("bytesDueAt", (d.getBytesDueAt() != null ? d.getBytesDueAt().toString() : null));
		return m;
	}

	private static void count(Sweep s, Decision d, BaseRecord artifact) {
		switch(d.getAction()) {
			case KEEP: s.keep++; break;
			case ARCHIVE: s.archive++; break;
			case DELETE_BYTES: s.deleteBytes++; s.bytesReclaimable += byteLength(artifact); break;
			case NONE:
			default: s.none++; break;
		}
	}

	/** The artifact's recorded byteLength metric, or 0 when unset; the DB row is not read for its size. */
	static long byteLength(BaseRecord artifact) {
		Long len = (artifact.hasField(OlioFieldNames.FIELD_PB_BYTE_LENGTH) ? artifact.get(OlioFieldNames.FIELD_PB_BYTE_LENGTH) : null);
		return (len != null && len.longValue() > 0L ? len.longValue() : 0L);
	}

	// ─────────────────────────────── apply ───────────────────────────────

	private static void act(BaseRecord user, Decision d, BaseRecord artifact, Map<String, Object> item, Sweep s) {
		String outcome;
		String reason = null;
		long freed = 0L;
		try {
			switch(d.getAction()) {
				case ARCHIVE: {
					if(!permits(canUpdate(user, artifact))) {
						outcome = OUTCOME_DENIED;
						reason = "PBAC denied update of artifact " + artifact.get(FieldNames.FIELD_OBJECT_ID);
					}
					else {
						PbArtifactUtil.archive(user, artifact);
						outcome = OUTCOME_ARCHIVED;
					}
					break;
				}
				case DELETE_BYTES: {
					Object[] r = deleteBytes(user, artifact);
					outcome = (String) r[0];
					reason = (String) r[1];
					freed = ((Long) r[2]).longValue();
					break;
				}
				case KEEP:
				case NONE:
				default:
					outcome = OUTCOME_KEPT;
					break;
			}
		}
		catch(PictureBookException e) {
			if(e.getStatus() == 409) {
				outcome = OUTCOME_REFUSED;
			}
			else if(e.getStatus() == 403) {
				outcome = OUTCOME_DENIED;
			}
			else {
				outcome = OUTCOME_FAILED;
			}
			reason = e.getMessage();
		}
		catch(Exception e) {
			logger.warn("Retention " + d.getAction() + " of " + artifact.get(FieldNames.FIELD_OBJECT_ID) + " failed: " + e.getMessage());
			outcome = OUTCOME_FAILED;
			reason = e.getMessage();
		}
		item.put("outcome", outcome);
		if(reason != null) {
			item.put("outcomeReason", reason);
		}
		switch(outcome) {
			case OUTCOME_ARCHIVED: s.archived++; break;
			case OUTCOME_BYTES_DELETED: s.bytesDeleted++; s.bytesFreed += freed; item.put("bytesFreed", Long.valueOf(freed)); break;
			case OUTCOME_REFUSED: s.refused++; break;
			case OUTCOME_DENIED: s.denied++; break;
			case OUTCOME_FAILED: s.failed++; break;
			default: break;
		}
	}

	/**
	 * Delete the artifact's {@code data.data} as the caller and clear the reference.
	 *
	 * @return {@code {outcome, reason, bytesFreed}}
	 */
	private static Object[] deleteBytes(BaseRecord user, BaseRecord artifact) {
		long orgId = PbGraphUtil.orgId(artifact);
		long dataId = PbHealthUtil.fkId(artifact, OlioFieldNames.FIELD_PB_DATA);
		long freed = byteLength(artifact);
		if(dataId <= 0L) {
			return new Object[] {OUTCOME_KEPT, "no data reference", Long.valueOf(0L)};
		}
		/// The artifact patch is checked first: deleting the row and then being refused the clear would leave a
		/// dangling reference that reads as bytes it no longer has.
		if(!permits(canUpdate(user, artifact))) {
			return new Object[] {OUTCOME_DENIED, "PBAC denied update of artifact " + artifact.get(FieldNames.FIELD_OBJECT_ID), Long.valueOf(0L)};
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, Long.valueOf(dataId));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID});
		q.setCache(false);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(data == null) {
			/// Missing, or unreadable by the caller: the two look the same through AccessPoint, so the row is
			/// checked raw. A dangling reference is repaired (nothing to delete); an unreadable row is denied.
			q.setCache(false);
			BaseRecord raw = IOSystem.getActiveContext().getSearch().findRecord(q);
			if(raw != null) {
				return new Object[] {OUTCOME_DENIED, "PBAC denied read of data.data " + dataId + " for the caller", Long.valueOf(0L)};
			}
			if(!clearData(user, artifact)) {
				return new Object[] {OUTCOME_FAILED, "data.data " + dataId + " is already gone and the dangling reference could not be cleared", Long.valueOf(0L)};
			}
			return new Object[] {OUTCOME_BYTES_DELETED, "data.data " + dataId + " was already gone; reference cleared", Long.valueOf(0L)};
		}
		DeleteResult del = PictureBookUtil.deleteRecordExplained(user, data);
		if(!del.deleted) {
			return new Object[] {(del.authorized ? OUTCOME_FAILED : OUTCOME_DENIED), del.reason, Long.valueOf(0L)};
		}
		if(!clearData(user, artifact)) {
			return new Object[] {OUTCOME_FAILED, "bytes of " + artifact.get(FieldNames.FIELD_OBJECT_ID)
				+ " deleted (data.data " + dataId + ") but the artifact's data reference could not be cleared", Long.valueOf(freed)};
		}
		return new Object[] {OUTCOME_BYTES_DELETED, null, Long.valueOf(freed)};
	}

	/**
	 * Clear the artifact's {@code data} reference: the field is materialised on the patch and left unset, the
	 * way {@link PbLocationUtil#writeApprovedRender} clears {@code approvedRender}. The writer stores 0 for a
	 * null MODEL field; an uncached re-read then populates {@code data} as null.
	 */
	private static boolean clearData(BaseRecord user, BaseRecord artifact) {
		BaseRecord patch = PbGraphUtil.patchOf(artifact, OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_DATA);
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.warn("The data reference of artifact " + artifact.get(FieldNames.FIELD_OBJECT_ID) + " could not be cleared; see the audit log");
			return false;
		}
		return true;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static PolicyResponseType canUpdate(BaseRecord user, BaseRecord rec) {
		return IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, rec);
	}

	private static boolean permits(PolicyResponseType prr) {
		return prr != null && prr.getType() == PolicyResponseEnumType.PERMIT;
	}

	private static void requireUser(BaseRecord user) {
		if(user == null) {
			throw new PictureBookException(401, "Not authenticated");
		}
	}

	private static long requireOrgScope(BaseRecord user) {
		requireUser(user);
		Object o = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(!(o instanceof Number)) {
			throw new PictureBookException(500, "User record carries no organizationId");
		}
		long orgId = ((Number) o).longValue();
		if(!PbHealthUtil.isAccountAdministrator(user, orgId)) {
			throw new PictureBookException(403, "Organization-wide retention requires AccountAdministrators");
		}
		return orgId;
	}
}
