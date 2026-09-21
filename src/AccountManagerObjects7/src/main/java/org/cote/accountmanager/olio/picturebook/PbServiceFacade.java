package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

/**
 * The one resolution layer phase 4's REST endpoints delegate to - the PictureBook equivalent of
 * {@code ISO42001ServiceFacade}, and the reason Service7 gains no authorization logic.
 * <p>
 * <b>Every entry point here starts from a book objectId and reads that book with
 * {@code AccessPoint.find}.</b> That is not a convenience, it is the KI-67 disposition made structural.
 * {@code AccessPoint.find} runs {@code canRead} on the record it returns ({@code AccessPoint.java:511-517}),
 * so a book objectId the caller may not read resolves to null and the request 404s before any list runs.
 * Every subsequent list is then reached from that authorized root and constrained by the book's own
 * workflow - never by a caller-supplied {@code groupId} or {@code organizationId}, and never through the
 * generic {@code /rest/model/search} over an {@code olio.pb.*} model. §5.6b's root-reference principle:
 * authorize the root by identity, then read inside its compartment.
 * <p>
 * This is also why nothing here post-filters a result set. {@code AccessPoint.list} authorizes the records
 * its query is <b>constrained by</b> ({@code authorizeQuery} -> {@code evaluateQueryToReadPolicyResponses},
 * {@code PolicyUtil.java:266-297}), so a list constrained inside an authorized book's compartment is already
 * authorized. KI-67's measurement was of a query constrained only by {@code organizationId} - a query-shape
 * problem, which this class avoids by construction.
 * <p>
 * <b>Cross-book addressing is refused, not merely unauthorized.</b> A node/artifact objectId that belongs
 * to a different book than the one in the path is a 404 even when the caller can read both, so a client
 * cannot use one book's grant to address another's graph.
 * <p>
 * <b>Returns DTO maps, not records.</b> This is the "DTO seam" half of phase 4: the wire shape is a small
 * map of primitives and nested maps, assembled here, so that adding a field to a model does not silently
 * widen the API and Service7 never has to know a projection. Mirrors
 * {@code PictureBookUtil.listScenes}/{@code listCharacters}, which already return
 * {@code List<Map<String, Object>>}.
 */
public class PbServiceFacade {
	public static final Logger logger = LogManager.getLogger(PbServiceFacade.class);

	private PbServiceFacade() {
		/// static utility
	}

	// ─────────────────────────────── bridge: PB1 group → PB2 book ───────────────────────────────

	/**
	 * Resolve a PB1 book group (auth.group) objectId to the corresponding olio.pb.book objectId.
	 * Returns {pb2BookObjectId, slug, bookName}, or 404 when no PB2 book has been created for the group.
	 */
	public static Map<String, Object> bookInfo(BaseRecord user, String bookGroupObjectId) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		if(bookGroupObjectId == null || bookGroupObjectId.trim().isEmpty())
			throw new PictureBookException(400, "A book group objectId is required");
		long orgId = orgOf(user);
		BaseRecord group = IOSystem.getActiveContext().getAccessPoint()
			.findByObjectId(user, ModelNames.MODEL_GROUP, bookGroupObjectId);
		if(group == null) throw new PictureBookException(404, "Book group not found");
		String groupName = group.get(FieldNames.FIELD_NAME);
		String slug = PbPipelineUtil.deriveSlug(groupName);
		if(slug == null)
			throw new PictureBookException(404, "Could not derive a PB2 slug from group name '" + groupName + "'");
		BaseRecord book = PbBookUtil.findBookBySlug(user, slug, orgId);
		if(book == null)
			throw new PictureBookException(404, "No PB2 workflow book found for '" + groupName + "'");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("pb2BookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", book.get(OlioFieldNames.FIELD_PB_SLUG));
		out.put("bookName", groupName);
		return out;
	}

	// ─────────────────────────────── the authorized root ───────────────────────────────

	/**
	 * The book, read by objectId through {@code AccessPoint.find}, or a 404.
	 * <p>
	 * Absent and PBAC-denied collapse to the same 404 deliberately - the convention
	 * {@code PictureBookUtil.findBookGroup} already uses - so the response discloses nothing about books
	 * the caller may not see.
	 */
	private static BaseRecord requireBook(BaseRecord user, String bookObjectId) {
		if(user == null) {
			throw new PictureBookException(401, "No authenticated principal");
		}
		if(bookObjectId == null || bookObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A book objectId is required");
		}
		long orgId = orgOf(user);
		BaseRecord book = PbBookUtil.readBook(user, bookObjectId, orgId);
		if(book == null) {
			throw new PictureBookException(404, "Book not found");
		}
		return book;
	}

	/** The book's workflow, or a 404 - a book with no workflow has never been generated. */
	private static BaseRecord requireWorkflow(BaseRecord user, BaseRecord book) {
		BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
		if(workflow == null) {
			throw new PictureBookException(404, "This book has no workflow yet - generate a scene first");
		}
		return workflow;
	}

	private static long orgOf(BaseRecord user) {
		Long id = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(id == null) {
			throw new PictureBookException(500, "The principal carries no organizationId");
		}
		return id.longValue();
	}

	/**
	 * A node addressed within a book: read by objectId (so {@code canRead} applies), then confirmed to
	 * belong to <b>this</b> book's workflow.
	 */
	private static BaseRecord requireNodeOfBook(BaseRecord user, BaseRecord workflow, String nodeObjectId) {
		if(nodeObjectId == null || nodeObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A node objectId is required");
		}
		BaseRecord node = PbGraphUtil.readNode(user, nodeObjectId, (long) workflow.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(node == null) {
			throw new PictureBookException(404, "Node not found");
		}
		BaseRecord nodeWorkflow = node.get(OlioFieldNames.FIELD_PB_WORKFLOW);
		Long nodeWorkflowId = (nodeWorkflow != null ? nodeWorkflow.get(FieldNames.FIELD_ID) : null);
		Long workflowId = workflow.get(FieldNames.FIELD_ID);
		if(nodeWorkflowId == null || workflowId == null || nodeWorkflowId.longValue() != workflowId.longValue()) {
			/// 404, not 403: the caller addressed this book and the node is not in it. Saying "forbidden"
			/// would confirm the node exists somewhere else.
			throw new PictureBookException(404, "Node not found in this book");
		}
		return node;
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/**
	 * The whole graph for one book: the workflow, its nodes with a recomputed status, and the edges.
	 * <p>
	 * {@code recomputeStatus} is compute-only by ratification 2 - it returns a status and writes nothing.
	 * The <b>persisted</b> status is returned alongside it as {@code storedStatus} so a client can see when
	 * the two disagree (which is exactly what "this node is stale but has not been re-run" looks like)
	 * without this read path acquiring a write.
	 */
	/// COST, stated rather than discovered later: this is O(nodes) queries, and each node costs TWO binding
	/// reads - one from listBindings here and one inside recomputeStatus. At ~7 nodes per scene a 41-scene
	/// book is ~287 nodes, so ~570 binding queries for one view. Fine for a chapter, not fine for a whole
	/// long book, and the fix when it bites is to list the workflow's bindings ONCE
	/// (PbGraphUtil.listWorkflowBindings) and hand recomputeStatus a prebuilt map. Not done here because it
	/// changes recomputeStatus's signature, and no measurement yet says it is needed.
	public static Map<String, Object> workflowView(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", book.get(OlioFieldNames.FIELD_PB_SLUG));
		out.put("bookName", book.get(FieldNames.FIELD_NAME));
		/// N4 whole-series view: the client needs THIS chapter's series (and its ordinal) to call
		/// listSeriesBooks and render the whole-series canvas. Both come from the same bookRequest()
		/// projection requireBook already loaded as the acting user - null when the book is standalone
		/// (not part of a series). Transport-only: no new read, no business logic.
		out.put("seriesObjectId", fkObjectId(book, OlioFieldNames.FIELD_PB_SERIES, null));
		out.put("chapter", book.get(OlioFieldNames.FIELD_PB_CHAPTER));
		out.put("workflowObjectId", workflow.get(FieldNames.FIELD_OBJECT_ID));
		out.put("graphVersion", workflow.get(OlioFieldNames.FIELD_PB_GRAPH_VERSION));
		out.put("graphStatus", enumString(workflow, OlioFieldNames.FIELD_PB_GRAPH_STATUS));
		out.put("nodeCount", workflow.get(OlioFieldNames.FIELD_PB_NODE_COUNT));

		List<BaseRecord> nodes = PbGraphUtil.listNodes(user, workflow);
		List<Map<String, Object>> nodeViews = new ArrayList<>();
		List<Map<String, Object>> edges = new ArrayList<>();
		for(BaseRecord n : nodes) {
			nodeViews.add(nodeSummary(user, n, book));
			for(BaseRecord b : PbGraphUtil.listBindings(user, n)) {
				edges.add(edgeSummary(b, n));
			}
		}
		out.put("nodes", nodeViews);
		out.put("edges", edges);
		return out;
	}

	/** One node in detail: its summary, its bindings, and the full artifact revision chain per role. */
	public static Map<String, Object> nodeView(BaseRecord user, String bookObjectId, String nodeObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);

		Map<String, Object> out = nodeSummary(user, node, book);
		List<Map<String, Object>> bindings = new ArrayList<>();
		for(BaseRecord b : PbGraphUtil.sortedBindings(PbGraphUtil.listBindings(user, node))) {
			bindings.add(edgeSummary(b, node));
		}
		out.put("bindings", bindings);

		/// The revision history, per role, newest first - what the Ux workflow view needs to offer
		/// "restore this revision". Roles are discovered from the artifacts themselves rather than from a
		/// hardcoded list, so a role added to the pipeline shows up here without an edit.
		Map<String, List<Map<String, Object>>> chains = new LinkedHashMap<>();
		for(String role : artifactRoles(user, node)) {
			List<Map<String, Object>> chain = new ArrayList<>();
			for(BaseRecord a : PbArtifactUtil.listChain(user, node, role)) {
				chain.add(artifactSummary(a));
			}
			chains.put(role, chain);
		}
		out.put("artifacts", chains);
		return out;
	}

	/**
	 * One artifact, addressed within a book. The bytes are NOT returned - {@code data.objectId} is, and the
	 * existing {@code ResourceService} route serves the bytes for it. An artifact JSON carrying a base64
	 * payload is how a 1.6 MB response gets into a list view.
	 */
	public static Map<String, Object> artifactView(BaseRecord user, String bookObjectId, String artifactObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		if(artifactObjectId == null || artifactObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "An artifact objectId is required");
		}
		BaseRecord artifact = PbArtifactUtil.readArtifact(user, artifactObjectId, orgOf(user));
		if(artifact == null) {
			throw new PictureBookException(404, "Artifact not found");
		}
		/// Same cross-book refusal as a node: resolve the producing node and require it to be in this book.
		BaseRecord producedBy = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		if(producedBy == null || producedBy.get(FieldNames.FIELD_OBJECT_ID) == null) {
			throw new PictureBookException(404, "Artifact not found in this book");
		}
		requireNodeOfBook(user, workflow, (String) producedBy.get(FieldNames.FIELD_OBJECT_ID));

		Map<String, Object> out = artifactSummary(artifact);
		out.put("generatorRequest", artifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST));
		out.put("sdConfigSnapshot", artifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT));
		out.put("artifactText", artifact.get(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT));
		return out;
	}

	/**
	 * Every node of the book whose recomputed status is STALE, i.e. what a "regenerate what changed" run
	 * would touch.
	 * <p>
	 * <b>A node that has never succeeded is not stale.</b> {@code inputHash} is null until a node's first
	 * success, so such a node is PENDING/READY - reporting it as stale would tell a user that work needs
	 * redoing when it has never been done.
	 */
	public static List<Map<String, Object>> listStale(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		List<Map<String, Object>> out = new ArrayList<>();
		for(BaseRecord n : PbGraphUtil.listNodes(user, workflow)) {
			if(PbNodeStatusEnumType.STALE == PbGraphUtil.recomputeStatus(user, n, book)) {
				out.add(nodeSummary(user, n, book));
			}
		}
		return out;
	}

	// ─────────────────────────────── writes ───────────────────────────────

	/**
	 * Request that a node be regenerated: persist STALE on it and propagate staleness downstream.
	 * <p>
	 * <b>This marks, it does not execute.</b> Nothing in phase 3 or 4 is a scheduler - a node runs when the
	 * pipeline next generates the scene it belongs to
	 * ({@code POST /olio/picture-book/scene/{sceneObjectId}/generate}). Saying "regenerated" and returning
	 * without having produced anything would be the false claim; the response reports what was marked and
	 * says so.
	 * <p>
	 * A PINNED node is refused rather than silently marked, because pinning exists precisely to say "do not
	 * replace this".
	 */
	public static Map<String, Object> requestRegenerate(BaseRecord user, String bookObjectId, String nodeObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);

		Boolean pinned = node.get(OlioFieldNames.FIELD_PB_PINNED);
		if(pinned != null && pinned.booleanValue()) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is pinned. Unpin it first - pinning means do not replace this artifact.");
		}

		if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.STALE)) {
			throw new PictureBookException(500, "Failed to mark node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " stale");
		}
		List<BaseRecord> downstream = PbGraphUtil.markStaleDownstream(user, workflow, node);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("marked", "STALE");
		out.put("downstreamMarked", downstream.size());
		List<String> handles = new ArrayList<>();
		for(BaseRecord d : downstream) {
			handles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", handles);
		out.put("executed", Boolean.FALSE);
		out.put("note", "Marked for regeneration. Execution happens on the next scene generation call;"
			+ " this endpoint is not a scheduler.");
		return out;
	}

	/** Pin or unpin a node. A pinned node's artifact is not replaced by a regeneration. */
	public static Map<String, Object> setPinned(BaseRecord user, String bookObjectId, String nodeObjectId,
			boolean pinned) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);

		if(!PbGraphUtil.setPinned(user, node, pinned)) {
			throw new PictureBookException(500, "Failed to " + (pinned ? "pin" : "unpin") + " node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("pinned", Boolean.valueOf(pinned));
		return out;
	}

	/**
	 * Enrol users in a book, in both tiers, through {@code PbSharingUtil.shareBook}.
	 * <p>
	 * The book is resolved from its objectId here, so the caller cannot name a slug it never authorized;
	 * {@code shareBook}'s own authorization ({@code OlioContext.register}, the book <b>Admin</b> tier) is
	 * what decides. Measured in phase 2c and worth restating because it surprises: <b>a book Writer cannot
	 * enrol anyone</b> - it takes the org admin or an explicit Admin grant.
	 * <p>
	 * Per-target outcomes are reported individually. A partial success is the truth when three names are
	 * submitted and the second is not a user, and collapsing that into one boolean would hide it.
	 */
	public static Map<String, Object> addMembers(BaseRecord user, String dataPath, String bookObjectId,
			List<String> userNames, boolean asAdmin) {
		BaseRecord book = requireBook(user, bookObjectId);
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null) {
			throw new PictureBookException(500, "Book " + bookObjectId + " carries no slug");
		}
		if(userNames == null || userNames.isEmpty()) {
			throw new PictureBookException(400, "At least one user name is required");
		}

		long orgId = orgOf(user);
		List<Map<String, Object>> results = new ArrayList<>();
		int enrolled = 0;
		for(String name : userNames) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("userName", name);
			BaseRecord target = IOSystem.getActiveContext().getFactory().findUser(name, orgId);
			if(target == null) {
				r.put("enrolled", Boolean.FALSE);
				r.put("error", "No such user in this organization");
				results.add(r);
				continue;
			}
			try {
				boolean ok = PbSharingUtil.shareBook(user, target, dataPath, slug, asAdmin);
				r.put("enrolled", Boolean.valueOf(ok));
				if(ok) {
					enrolled++;
				}
			}
			catch(PictureBookException e) {
				/// Reported, not swallowed and not fatal to the other targets. shareBook is both-or-fail per
				/// target, so a throw here means THAT target is not enrolled in either tier - except for the
				/// case its own message names, where the book tier landed and the universe tier did not.
				r.put("enrolled", Boolean.FALSE);
				r.put("status", Integer.valueOf(e.getStatus()));
				r.put("error", e.getMessage());
			}
			results.add(r);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", slug);
		out.put("asAdmin", Boolean.valueOf(asAdmin));
		out.put("enrolled", Integer.valueOf(enrolled));
		out.put("requested", Integer.valueOf(userNames.size()));
		out.put("results", results);
		return out;
	}

	/**
	 * Create the next chapter of a series (or a standalone book when no series is given), persisting the
	 * chapter's linkage and source provenance and, optionally, seeding its shadow cast from the series
	 * baseline.
	 * <p>
	 * <b>World creation goes through {@link PbBookUtil#createBook(BaseRecord, String, String, String,
	 * BaseRecord, Integer)}, never hand-rolled here.</b> When {@code seriesObjectId} is given the chapter
	 * shares the series' ONE world ({@code book.world = series.universe}) and carries its {@code series} FK
	 * and {@code chapter} ordinal - no per-chapter world is created (Q6). When it is absent the unchanged
	 * 4-arg {@code createBook} makes a standalone book with its own world, exactly as before.
	 * <p>
	 * <b>Source provenance (Q8).</b> {@code sourceDataObjectId} links the chapter to the manuscript
	 * ({@code data.data}) it was cut from; {@code sourceRange} narrows that to one span
	 * ({@code startOffset}/{@code endOffset}/{@code title}) as a dedicated {@code olio.pb.sourceRange}
	 * sub-record. {@code createBook} sets neither, so both are patched onto the book here.
	 * <p>
	 * <b>Copy is REDIRECTED to the shadow model on the series path (Q6).</b> {@code copyRecordObjectIds}
	 * naming baseline {@code charPerson}s are cloned into the ONE series world's Population group and
	 * enrolled into this chapter's book-scoped shadow cast group, so each chapter gets its own overwritable
	 * shadow of a shared baseline. On the standalone path the legacy {@link PbSharingUtil#copyToChapter}
	 * (per-book membership, sub-record re-homing into the chapter's own world) is used unchanged, honouring
	 * §3.5's COPY-not-reference choice so deleting one book never destroys another's data.
	 */
	public static Map<String, Object> createChapter(BaseRecord user, String dataPath, String seriesObjectId,
			String fromBookObjectId, String toSlug, String toTitle, Integer chapter, String sourceDataObjectId,
			Map<String, Object> sourceRange, List<String> copyRecordObjectIds, String copyRecordModel) {
		if(toSlug == null || toSlug.trim().length() == 0) {
			throw new PictureBookException(400, "A slug is required for the new chapter");
		}
		long orgId = orgOf(user);

		/// The series this chapter belongs to, when given. Read as the ACTING user through AccessPoint - a
		/// series objectId the caller cannot read collapses to 404, never a leak. createBook does the
		/// entitlement check itself; this only proves the series is real and visible.
		BaseRecord series = null;
		if(seriesObjectId != null && seriesObjectId.trim().length() > 0) {
			series = PbSeriesUtil.readSeries(user, seriesObjectId.trim(), orgId);
			if(series == null) {
				throw new PictureBookException(404, "Series not found: " + seriesObjectId);
			}
		}

		/// null/empty fromBookObjectId means "no explicit predecessor". A non-null value must be readable;
		/// it is recorded as provenance (and, on the standalone path, gates the copy membership check) - the
		/// series FK is the real chapter linkage now.
		BaseRecord fromBook = (fromBookObjectId != null && !fromBookObjectId.trim().isEmpty())
				? requireBook(user, fromBookObjectId) : null;
		String fromSlug = (fromBook != null ? (String) fromBook.get(OlioFieldNames.FIELD_PB_SLUG) : null);

		if(PbBookUtil.findBookBySlug(user, toSlug, orgId) != null) {
			throw new PictureBookException(409, "A book with slug '" + toSlug + "' already exists");
		}

		/// The manuscript this chapter is cut from, when given. Read as the acting user so canRead applies -
		/// linking a chapter to a document the caller cannot read is a 404, not a silent FK.
		BaseRecord sourceData = null;
		if(sourceDataObjectId != null && sourceDataObjectId.trim().length() > 0) {
			sourceData = IOSystem.getActiveContext().getAccessPoint()
				.findByObjectId(user, ModelNames.MODEL_DATA, sourceDataObjectId.trim());
			if(sourceData == null) {
				throw new PictureBookException(404, "Source document not found: " + sourceDataObjectId);
			}
		}

		String title = (toTitle != null && toTitle.trim().length() > 0) ? toTitle : toSlug;
		BaseRecord toBook = (series != null)
				? PbBookUtil.createBook(user, dataPath, toSlug, title, series, chapter)
				: PbBookUtil.createBook(user, dataPath, toSlug, title);
		if(toBook == null) {
			throw new PictureBookException(500, "Failed to create chapter '" + toSlug + "'");
		}

		/// Persist source provenance the creation path does not: the sourceData FK and, when a span is
		/// given, a dedicated sourceRange sub-record.
		persistSourceProvenance(user, orgId, toBook, sourceData, sourceRange);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("seriesObjectId", series != null ? series.get(FieldNames.FIELD_OBJECT_ID) : null);
		out.put("fromBookObjectId", fromBook != null ? fromBook.get(FieldNames.FIELD_OBJECT_ID) : null);
		out.put("fromSlug", fromSlug);
		out.put("bookObjectId", toBook.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", toSlug);
		out.put("chapter", toBook.get(OlioFieldNames.FIELD_PB_CHAPTER));
		out.put("sourceDataObjectId", sourceData != null ? sourceData.get(FieldNames.FIELD_OBJECT_ID) : null);
		out.put("copied", Integer.valueOf(0));

		if(copyRecordObjectIds == null || copyRecordObjectIds.isEmpty()) {
			return out;
		}
		if(copyRecordModel == null || copyRecordModel.trim().length() == 0) {
			throw new PictureBookException(400, "copyRecordModel is required when copyRecordObjectIds is given"
				+ " - the destination group depends on the model");
		}

		BookContext toCtx = PbBookUtil.openBookContext(user, toBook);
		if(toCtx == null) {
			throw new PictureBookException(500, "Chapter '" + toSlug + "' has no assemblable world to copy into");
		}

		List<BaseRecord> sources = new ArrayList<>();
		for(String oid : copyRecordObjectIds) {
			BaseRecord src = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, copyRecordModel, oid);
			if(src == null) {
				throw new PictureBookException(404, "Cannot copy " + copyRecordModel + " " + oid
					+ " - not found or not readable");
			}
			sources.add(src);
		}

		List<BaseRecord> copies;
		if(series != null) {
			/// SERIES PATH (Q6): seed this chapter's SHADOW cast from the baseline. Only charPerson is a cast
			/// member; the shadow clones land in the ONE series world's Population group (not a fresh per-book
			/// world) and are tagged by this chapter's book-scoped shadow cast group. Overwriting a shadow
			/// later, or deleting this chapter, touches only these tagged clones - never the baseline and
			/// never another chapter's shadows.
			if(!OlioModelNames.MODEL_CHAR_PERSON.equals(copyRecordModel)) {
				throw new PictureBookException(400, "Only " + OlioModelNames.MODEL_CHAR_PERSON
					+ " may be seeded as chapter shadows on a series chapter; got " + copyRecordModel);
			}
			/// The series world's baseline Population group path is the anchor for this chapter's shadow
			/// character group (a SIBLING of it). Shadows must NOT land in Population itself: a baseline
			/// character and its per-chapter shadows share the same load-bearing name by design, so the
			/// (name, groupId, organizationId) constraint would collide (Defect 1).
			String populationPath = toCtx.getGroupPath("population");
			if(populationPath == null) {
				throw new PictureBookException(500,
					"The series world has no population group to anchor the chapter shadow group");
			}
			BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
			String seriesSlug = (seriesWorld != null ? (String) seriesWorld.get(FieldNames.FIELD_NAME) : null);
			if(seriesSlug == null) {
				throw new PictureBookException(500, "The series' shared world has no name to scope the cast group by");
			}
			/// The chapter's own shadow character group: distinct groupId (no name collision), names
			/// preserved, inside the series world's group tree, granted to the series role pair and proven
			/// readable by the creator. The shadow clones and their re-homed render-state sub-records land
			/// here; the book-scoped shadow cast group only TAGS them by chapter.
			BaseRecord shadowCharGroup = PbBookUtil.getCreateChapterShadowCharGroup(user, seriesSlug,
				populationPath, toSlug, orgId);
			BaseRecord shadowCast = PbCastUtil.getCreateShadowCastGroup(user, toBook, toSlug,
				PbBookUtil.bookGroupPath(seriesSlug), orgId);
			copies = PbSharingUtil.copyToChapterShadow(user, sources, shadowCharGroup, shadowCast, null, null, null);
		}
		else {
			/// STANDALONE PATH: unchanged legacy copy into the chapter's OWN world, keyed by the model's world
			/// group and gated by per-book membership of both slugs.
			String destField = PbSubRecordUtil.WORLD_GROUP_FIELD.get(copyRecordModel);
			BaseRecord destGroup = (destField != null ? toCtx.getGroup(destField) : null);
			if(destGroup == null) {
				throw new PictureBookException(400, "No chapter destination group is declared for " + copyRecordModel
					+ " - copyable models are " + PbSubRecordUtil.WORLD_GROUP_FIELD.keySet());
			}
			copies = PbSharingUtil.copyToChapter(user, fromSlug, toSlug, sources, destGroup, null, null, null);
		}

		out.put("copied", Integer.valueOf(copies.size()));
		List<String> copiedIds = new ArrayList<>();
		for(BaseRecord c : copies) {
			copiedIds.add((String) c.get(FieldNames.FIELD_OBJECT_ID));
		}
		out.put("copiedObjectIds", copiedIds);
		return out;
	}

	// ─────────────────────────────── shadow-cast sync ops (Q6: recopy / merge) ───────────────────────────────

	/**
	 * <b>Recopy</b> a chapter's shadow cast from the series baseline: discard this chapter's shadow edits and
	 * reseed each shadow wholesale from its baseline counterpart (Q6 sync op "recopy"). The counterpart of
	 * {@link #mergeChapter}, which keeps the chapter's edits and only overlays shared attributes.
	 * <p>
	 * <b>Authorized as an UPDATE of the chapter, as the acting user.</b> {@link #requireBook} 404s a book the
	 * caller cannot read; {@code AuthorizationUtil.canUpdate} then gates the write so a read-but-not-update
	 * caller gets a 403 (mirrors {@code teardownBookWorld}'s {@code canDelete} gate).
	 * <p>
	 * <b>Clear then reseed, both scoped to THIS chapter's slug.</b> The clear reuses
	 * {@code PictureBookUtil.dropChapterShadowGroups} - the exact teardown a chapter-delete runs (#3e) - so the
	 * series baseline and every OTHER chapter's shadows are untouched; the physical deletes run as the olio
	 * principal (the shadow records' owner). The reseed recreates the (now empty) shadow character and cast
	 * groups and calls {@link PbSharingUtil#copyToChapterShadow}, exactly as first-time seeding does in
	 * {@link #createChapter}.
	 * <p>
	 * <b>Reseeds the chapter's CURRENT cast, by name.</b> Only baseline characters whose name matches an
	 * existing shadow are reseeded, so recopy rebuilds the cast this chapter had rather than pulling in
	 * characters it never included. When the chapter has no shadows yet, the whole baseline is seeded
	 * (first-time semantics). Scene-to-character links resolve BY NAME at render time, so reseeding the same
	 * names auto-relinks the chapter's scenes - no participation is re-pointed.
	 *
	 * @return {bookObjectId, slug, seriesObjectId, recopied (count), recopiedObjectIds}
	 */
	public static Map<String, Object> recopyChapter(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		long orgId = orgOf(user);
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, book);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not authorized to recopy this chapter's cast");
		}

		SeriesScope scope = requireSeriesScope(user, book, orgId);

		/// The baseline cast and its members - the canonical characters to reseed from.
		BaseRecord baselineCast = PbCastUtil.findCastGroup(user, PbCastUtil.baselineCastGroupName(scope.seriesSlug),
			scope.castGroupPath, orgId);
		if(baselineCast == null) {
			throw new PictureBookException(404, "The series has no baseline cast to recopy from");
		}
		List<BaseRecord> baselineMembers = PbCastUtil.listCastMembers(user, baselineCast);
		if(baselineMembers.isEmpty()) {
			throw new PictureBookException(404, "The series baseline cast is empty; nothing to recopy");
		}

		/// Capture the names of THIS chapter's current shadows BEFORE the clear so the reseed restores exactly
		/// the same cast (and no more). Read now - after dropChapterShadowGroups the shadow cast is gone.
		Set<String> keepNames = new HashSet<>();
		BaseRecord shadowCast = PbCastUtil.findCastGroup(user, PbCastUtil.shadowCastGroupName(scope.slug),
			scope.castGroupPath, orgId);
		if(shadowCast != null) {
			for(BaseRecord m : PbCastUtil.listCastMembers(user, shadowCast)) {
				String nm = m.get(FieldNames.FIELD_NAME);
				if(nm != null && nm.trim().length() > 0) {
					keepNames.add(nm.trim().toLowerCase());
				}
			}
		}

		/// Select baseline members to reseed: those matching an existing shadow (rebuild the current cast), or
		/// the whole baseline when the chapter has no shadows yet (first-time seed).
		List<BaseRecord> toSeed = new ArrayList<>();
		for(BaseRecord b : baselineMembers) {
			String nm = b.get(FieldNames.FIELD_NAME);
			String key = (nm != null ? nm.trim().toLowerCase() : null);
			if(keepNames.isEmpty() || (key != null && keepNames.contains(key))) {
				toSeed.add(b);
			}
		}
		if(toSeed.isEmpty()) {
			throw new PictureBookException(404, "No baseline characters match this chapter's cast; nothing to recopy");
		}

		/// Clear this chapter's existing shadows as the olio principal, scoped to THIS chapter's slug - the same
		/// teardown a chapter delete runs. Leaves the shared Population group and baseline intact.
		BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal in organization " + orgId);
		}
		if(!PictureBookUtil.dropChapterShadowGroups(olioUser, book, scope.slug, orgId)) {
			throw new PictureBookException(500,
				"Failed to clear this chapter's existing shadows before recopy; see server log");
		}

		/// Recreate the (now empty) shadow character + cast groups and reseed from baseline, exactly as
		/// first-time seeding does in createChapter's series path.
		BookContext ctx = PbBookUtil.openBookContext(user, book);
		if(ctx == null) {
			throw new PictureBookException(500, "This chapter has no assemblable world to reseed shadows into");
		}
		String populationPath = ctx.getGroupPath("population");
		if(populationPath == null) {
			throw new PictureBookException(500,
				"The series world has no population group to anchor the chapter shadow group");
		}
		BaseRecord shadowCharGroup = PbBookUtil.getCreateChapterShadowCharGroup(user, scope.seriesSlug,
			populationPath, scope.slug, orgId);
		BaseRecord newShadowCast = PbCastUtil.getCreateShadowCastGroup(user, book, scope.slug, scope.castGroupPath, orgId);
		List<BaseRecord> copies = PbSharingUtil.copyToChapterShadow(user, toSeed, shadowCharGroup, newShadowCast,
			null, null, null);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", scope.slug);
		out.put("seriesObjectId", scope.series.get(FieldNames.FIELD_OBJECT_ID));
		out.put("recopied", Integer.valueOf(copies.size()));
		List<String> ids = new ArrayList<>();
		for(BaseRecord c : copies) {
			ids.add((String) c.get(FieldNames.FIELD_OBJECT_ID));
		}
		out.put("recopiedObjectIds", ids);
		return out;
	}

	/**
	 * <b>Merge</b> series baseline updates into a chapter's existing shadows: pull the baseline's shared scalar
	 * attributes into each shadow while KEEPING the chapter's own overrides - apparel, state/pose, narrative,
	 * portrait (Q6 sync op "merge"). The non-destructive counterpart of {@link #recopyChapter}.
	 * <p>
	 * <b>Authorized as an UPDATE of the chapter, as the acting user</b> (same gate as {@link #recopyChapter}).
	 * The pull itself is {@link PbSharingUtil#mergeChapterShadows}, which matches shadow to baseline by name,
	 * overwrites only non-null baseline scalars, and leaves every foreign/nested override standing. Nothing is
	 * cleared or re-created, and no scene link is re-pointed (the shadow keeps its identity and name).
	 *
	 * @return {bookObjectId, slug, seriesObjectId, merged (count)}
	 */
	public static Map<String, Object> mergeChapter(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		long orgId = orgOf(user);
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, book);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not authorized to merge this chapter's cast");
		}

		SeriesScope scope = requireSeriesScope(user, book, orgId);

		BaseRecord baselineCast = PbCastUtil.findCastGroup(user, PbCastUtil.baselineCastGroupName(scope.seriesSlug),
			scope.castGroupPath, orgId);
		if(baselineCast == null) {
			throw new PictureBookException(404, "The series has no baseline cast to merge from");
		}
		BaseRecord shadowCast = PbCastUtil.findCastGroup(user, PbCastUtil.shadowCastGroupName(scope.slug),
			scope.castGroupPath, orgId);
		if(shadowCast == null) {
			throw new PictureBookException(404, "This chapter has no shadow cast to merge into; seed it first");
		}
		List<BaseRecord> baselineMembers = PbCastUtil.listCastMembers(user, baselineCast);
		List<BaseRecord> shadowMembers = PbCastUtil.listCastMembers(user, shadowCast);
		int merged = PbSharingUtil.mergeChapterShadows(user, shadowMembers, baselineMembers);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", scope.slug);
		out.put("seriesObjectId", scope.series.get(FieldNames.FIELD_OBJECT_ID));
		out.put("merged", Integer.valueOf(merged));
		return out;
	}

	/**
	 * The series-scoped facts both sync ops need, resolved once and validated: the book must be a series
	 * chapter (carry a {@code series} FK), the series must be readable, and the shared world must have a name.
	 * Mirrors {@code createChapter}'s series-path resolution so recopy/merge target exactly the groups seeding
	 * created.
	 */
	private static SeriesScope requireSeriesScope(BaseRecord user, BaseRecord book, long orgId) {
		BaseRecord seriesRef = book.get(OlioFieldNames.FIELD_PB_SERIES);
		if(seriesRef == null) {
			throw new PictureBookException(400,
				"This book is not a series chapter; there is no baseline cast to sync from");
		}
		String seriesObjectId = seriesRef.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord series = PbSeriesUtil.readSeries(user, seriesObjectId, orgId);
		if(series == null) {
			throw new PictureBookException(404, "The series this chapter belongs to is not readable");
		}
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null || slug.trim().length() == 0) {
			throw new PictureBookException(500, "This chapter has no slug to scope its shadows by");
		}
		BaseRecord seriesWorld = series.get(OlioFieldNames.FIELD_PB_UNIVERSE);
		String seriesSlug = (seriesWorld != null ? (String) seriesWorld.get(FieldNames.FIELD_NAME) : null);
		if(seriesSlug == null) {
			throw new PictureBookException(500, "The series' shared world has no name to scope the cast group by");
		}
		SeriesScope scope = new SeriesScope();
		scope.series = series;
		scope.slug = slug;
		scope.seriesSlug = seriesSlug;
		scope.castGroupPath = PbBookUtil.bookGroupPath(seriesSlug);
		return scope;
	}

	/** Resolved series-scoped facts for a chapter sync op. */
	private static final class SeriesScope {
		private BaseRecord series;
		private String slug;
		private String seriesSlug;
		private String castGroupPath;
	}

	/**
	 * Patch the chapter's {@code sourceData} FK and, when a range is supplied, create and link a dedicated
	 * {@code olio.pb.sourceRange} sub-record (Q8).
	 * <p>
	 * The sourceRange is groupless provenance ({@code common.baseLight}); it is created owned by the acting
	 * user via {@code RecordUtil} (its FK is then read back through the authorized book projection) and its
	 * reference is patched onto the book as the acting user, PATCH-shaped (identity + name + only the fields
	 * being set) and result-asserted so a silent write failure cannot pass for success. Only the fields
	 * actually being set are included in the patch, so a range-only call never blanks an existing
	 * {@code sourceData} and vice versa.
	 */
	private static void persistSourceProvenance(BaseRecord user, long orgId, BaseRecord book,
			BaseRecord sourceData, Map<String, Object> sourceRange) {
		boolean haveRange = (sourceRange != null && !sourceRange.isEmpty());
		if(sourceData == null && !haveRange) {
			return;
		}

		BaseRecord rangeRec = null;
		if(haveRange) {
			try {
				rangeRec = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SOURCE_RANGE);
				IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, rangeRec, orgId);
				Object so = sourceRange.get(OlioFieldNames.FIELD_PB_START_OFFSET);
				Object eo = sourceRange.get(OlioFieldNames.FIELD_PB_END_OFFSET);
				if(so instanceof Number) {
					rangeRec.set(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(((Number) so).intValue()));
				}
				if(eo instanceof Number) {
					rangeRec.set(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(((Number) eo).intValue()));
				}
				Object rt = sourceRange.get(OlioFieldNames.FIELD_PB_TITLE);
				if(rt != null && rt.toString().trim().length() > 0) {
					rangeRec.set(OlioFieldNames.FIELD_PB_TITLE, rt.toString());
				}
				/// The span's own back-reference to the manuscript, when known - the book keeps its own
				/// sourceData FK too, but the range records which document its offsets index into.
				if(sourceData != null) {
					rangeRec.set(OlioFieldNames.FIELD_PB_SOURCE_DATA, sourceData);
				}
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				throw new PictureBookException(500, "Failed to assemble the chapter source range: " + e.getMessage());
			}
			if(!IOSystem.getActiveContext().getRecordUtil().createRecord(rangeRec)) {
				throw new PictureBookException(500, "Failed to persist the chapter source range");
			}
		}

		List<String> patchFields = new ArrayList<>();
		if(sourceData != null) {
			patchFields.add(OlioFieldNames.FIELD_PB_SOURCE_DATA);
		}
		if(rangeRec != null) {
			patchFields.add(OlioFieldNames.FIELD_PB_SOURCE_RANGE);
		}
		BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK,
			patchFields.toArray(new String[0]));
		try {
			if(sourceData != null) {
				patch.set(OlioFieldNames.FIELD_PB_SOURCE_DATA, sourceData);
			}
			if(rangeRec != null) {
				patch.set(OlioFieldNames.FIELD_PB_SOURCE_RANGE, rangeRec);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble the chapter source patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Chapter '" + book.get(OlioFieldNames.FIELD_PB_SLUG)
				+ "' was created but its source provenance could not be linked");
		}
	}

	/**
	 * Detect chapter boundaries in a manuscript ({@code data.data}) and return them as character-offset
	 * ranges shaped to feed {@link #createChapter}'s {@code sourceRange} argument (N3).
	 * <p>
	 * <b>Read-only.</b> Nothing is written. The manuscript is resolved as the ACTING user through
	 * {@code AccessPoint.find} - a manuscript uploaded by the user is user-owned, so it resolves under the
	 * caller's own PBAC and an objectId the caller cannot read collapses to a 404, never a leak. Text is
	 * extracted with the repo's bounded, content-type-aware office-doc path
	 * ({@link ChapBookUtil#extractPoemText(BaseRecord)}, which reads the byteStore via
	 * {@code ByteModelUtil.getValue} and routes to {@code DocumentUtil.readDocument(bytes, cap, ct)} - never
	 * a raw byteStore read and never Tika in Service7). Detection is the pure, side-effect-free
	 * {@link PbChapterBoundaryUtil#detectBoundaries(String)} (no DB, no embedding, unlike
	 * {@code VectorUtil.chunkByChapter}).
	 * <p>
	 * Returns one ordered map per range: {@code {startOffset, endOffset, title}} - exactly the shape the
	 * {@code POST /chapter} body's {@code sourceRange} accepts, so a chosen (or hand-edited) range can be
	 * handed straight back to {@link #createChapter}. {@code title} is {@code null} for a leading
	 * front-matter or no-heading range. An empty list means the document extracted to no usable text.
	 */
	public static List<Map<String, Object>> detectSourceBoundaries(BaseRecord user, String sourceDataObjectId) {
		if(user == null) {
			throw new PictureBookException(401, "No authenticated principal");
		}
		if(sourceDataObjectId == null || sourceDataObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A sourceDataObjectId is required");
		}
		long orgId = orgOf(user);

		/// Resolve the manuscript as the acting user so canRead applies. planMost(true) + no cache so the
		/// byteStore is populated with fresh bytes - a minimal findByObjectId projection would omit it.
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, sourceDataObjectId.trim());
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(data == null) {
			throw new PictureBookException(404, "Source document not found: " + sourceDataObjectId);
		}

		/// Bounded, content-type-aware extraction (16MB cap, POI/Tika routing) - the repo's canonical
		/// office-doc text path. Throws PictureBookException(400) on unsupported type / extraction failure.
		String text = ChapBookUtil.extractPoemText(data);

		List<Map<String, Object>> out = new ArrayList<>();
		if(text == null || text.isBlank()) {
			return out;
		}
		for(PbChapterBoundaryUtil.ChapterRange r : PbChapterBoundaryUtil.detectBoundaries(text)) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(r.getStartOffset()));
			m.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(r.getEndOffset()));
			m.put(OlioFieldNames.FIELD_PB_TITLE, r.getTitle());
			out.add(m);
		}
		return out;
	}

	// ─────────────────────────────── DTO assembly ───────────────────────────────

	private static Map<String, Object> nodeSummary(BaseRecord user, BaseRecord node, BaseRecord book) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objectId", node.get(FieldNames.FIELD_OBJECT_ID));
		m.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		m.put("nodeType", enumString(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
		m.put("storedStatus", enumString(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		m.put("status", String.valueOf(PbGraphUtil.recomputeStatus(user, node, book)));
		m.put("pinned", node.get(OlioFieldNames.FIELD_PB_PINNED));
		m.put("ordinal", node.get(OlioFieldNames.FIELD_PB_ORDINAL));
		m.put("sceneIndex", node.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
		m.put("scope", node.get(OlioFieldNames.FIELD_PB_SCOPE));
		m.put("scopeRef", node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		m.put("promptText", node.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));
		m.put("configOverride", node.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		m.put("inputHash", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		m.put("configHash", node.get(OlioFieldNames.FIELD_PB_CONFIG_HASH));
		m.put("lastError", node.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
		return m;
	}

	private static Map<String, Object> edgeSummary(BaseRecord binding, BaseRecord consumer) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objectId", binding.get(FieldNames.FIELD_OBJECT_ID));
		m.put("consumerObjectId", consumer.get(FieldNames.FIELD_OBJECT_ID));
		m.put("role", binding.get(OlioFieldNames.FIELD_PB_ROLE));
		m.put("bindingOrdinal", binding.get(OlioFieldNames.FIELD_PB_BINDING_ORDINAL));
		m.put("required", binding.get(OlioFieldNames.FIELD_PB_REQUIRED));
		BaseRecord src = binding.get(OlioFieldNames.FIELD_PB_SOURCE_NODE);
		m.put("sourceNodeObjectId", (src != null ? src.get(FieldNames.FIELD_OBJECT_ID) : null));
		BaseRecord art = binding.get(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT);
		m.put("sourceArtifactObjectId", (art != null ? art.get(FieldNames.FIELD_OBJECT_ID) : null));
		m.put("refModel", binding.get(OlioFieldNames.FIELD_PB_REF_MODEL));
		m.put("refObjectId", binding.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
		m.put("refHash", binding.get(OlioFieldNames.FIELD_PB_REF_HASH));
		m.put("valueHash", binding.get(OlioFieldNames.FIELD_PB_VALUE_HASH));
		return m;
	}

	private static Map<String, Object> artifactSummary(BaseRecord artifact) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objectId", artifact.get(FieldNames.FIELD_OBJECT_ID));
		m.put("artifactType", enumString(artifact, OlioFieldNames.FIELD_PB_ARTIFACT_TYPE));
		m.put("role", artifact.get(OlioFieldNames.FIELD_PB_ROLE));
		m.put("revision", artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		m.put("selected", artifact.get(OlioFieldNames.FIELD_PB_SELECTED));
		m.put("seed", artifact.get(OlioFieldNames.FIELD_PB_SEED));
		m.put("contentHash", artifact.get(OlioFieldNames.FIELD_PB_CONTENT_HASH));
		m.put("mimeType", artifact.get(OlioFieldNames.FIELD_PB_MIME_TYPE));
		m.put("imageWidth", artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH));
		m.put("imageHeight", artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT));
		m.put("byteLength", artifact.get(OlioFieldNames.FIELD_PB_BYTE_LENGTH));
		m.put("backend", enumString(artifact, OlioFieldNames.FIELD_PB_BACKEND));
		/// The objectId of the bytes, never the bytes. ResourceService already serves data.data content.
		BaseRecord data = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		m.put("dataObjectId", (data != null ? data.get(FieldNames.FIELD_OBJECT_ID) : null));
		BaseRecord supersedes = artifact.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		m.put("supersedesObjectId", (supersedes != null ? supersedes.get(FieldNames.FIELD_OBJECT_ID) : null));
		return m;
	}

	/**
	 * Every artifact role present on a node, in first-seen order.
	 * <p>
	 * Read off the artifacts rather than a hardcoded role list, so a role the pipeline starts producing is
	 * visible here without an edit. Uses one org-scoped list constrained by the node - the node itself is
	 * already authorized by {@code requireNodeOfBook}.
	 */
	private static List<String> artifactRoles(BaseRecord user, BaseRecord node) {
		List<String> roles = new ArrayList<>();
		org.cote.accountmanager.io.Query q = org.cote.accountmanager.io.QueryUtil.createQuery(
			OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, node);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, node.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_ROLE });
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		if(recs == null) {
			return roles;
		}
		for(BaseRecord r : recs) {
			String role = r.get(OlioFieldNames.FIELD_PB_ROLE);
			if(role != null && !roles.contains(role)) {
				roles.add(role);
			}
		}
		return roles;
	}

	// ─────────────────────────────── Phase 5b: book list + page view ───────────────────────────────

	/**
	 * All {@code olio.pb.book} records owned by the requesting user in their organisation, sorted by name.
	 * Returns lightweight DTOs — objectId, slug, name, bookStatus — to populate a book selector.
	 * <p>
	 * Uses {@code AccessPoint.list} with explicit {@code organizationId} and {@code ownerId} conditions
	 * following §5.6b: the query shape is authorized through PBAC's query-evaluation path; per-record
	 * filtering is not applied; however {@code ownerId} constrains results to the requesting user's own
	 * books, closing the KI-67 cross-owner leak for the selector view. Shared books accessible via
	 * collaboration roles are not listed here — they are reached by objectId directly.
	 */
	public static List<Map<String, Object>> listBooks(BaseRecord user) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		long orgId = orgOf(user);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_OWNER_ID, (Long) user.get(FieldNames.FIELD_ID));
		q.setRequest(PbBookUtil.bookRequest());
		q.setCache(false);
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setRequestRange(0, 100);
		BaseRecord[] books = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		List<Map<String, Object>> out = new ArrayList<>();
		if(books == null) return out;
		for(BaseRecord b : books) {
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("objectId", b.get(FieldNames.FIELD_OBJECT_ID));
			dto.put("name", b.get(FieldNames.FIELD_NAME));
			dto.put("slug", b.get(OlioFieldNames.FIELD_PB_SLUG));
			dto.put("bookStatus", enumString(b, OlioFieldNames.FIELD_PB_BOOK_STATUS));
			out.add(dto);
		}
		return out;
	}

	/**
	 * All chapter books of ONE series (N4), each carrying its series/chapter/world linkage, so the canvas
	 * can render a whole-series view and order chapters within it. This is a SERIES-scoped listing, NOT the
	 * owner-filtered {@link #listBooks(BaseRecord)} selector: it deliberately does <b>not</b> filter by
	 * {@code ownerId}, because a series' chapters are owned by the olio principal (or another collaborator)
	 * and must be visible to an <i>entitled non-owner</i> - a caller who holds the series {@code Writer} or
	 * {@code Admin} role. Authorization is the series role inheritance that already exists
	 * ({@link PbBookUtil#grantSeriesRolesOnChapterGroups}, which grants the series role pair CRUD on every
	 * chapter's {@code Book}/{@code Workflow}/{@code Artifacts} group); nothing new is added here.
	 * <p>
	 * <b>Why this is not {@code AccessPoint.list} over a {@code series} FK.</b> {@code AccessPoint.list}
	 * authorizes the query <i>shape</i>, not each record (see {@code model-api.md}: "AccessPoint.list is NOT
	 * a per-record authorization boundary"), and a series' chapters each live in their OWN {@code Book} group
	 * with no shared, policy-driving constrained field - the only row-level link to the series is the
	 * {@code series} FK, which is not a dynamic-policy field, so a {@code list} scoped by it would authorize
	 * on {@code organizationId} alone and return the series' chapters to any org member, entitled or not.
	 * So the {@code series} FK is used only to <b>enumerate the candidate chapters</b> (a bounded set - this
	 * series, never the whole org), and each candidate is then <b>read as the ACTING user</b> through
	 * {@link PbBookUtil#readBook(BaseRecord, String, long)} ({@code AccessPoint.find} -> per-record
	 * {@code canRead}), which is where the series-role entitlement is actually enforced: an entitled caller
	 * gets the projected chapter, a non-entitled caller gets {@code null} and the chapter is dropped. This is
	 * "constrain the candidate set, then filter per record itself", the model-api.md-endorsed compensating
	 * control - not an org-wide list post-filtered client-side.
	 * <p>
	 * <b>The series record is resolved as the olio principal, not the acting user.</b> Series rows are
	 * olio-principal-owned; resolving them as the request user can return {@code null} even for an entitled
	 * caller. This lookup only supplies the {@code series} RECORD the FK candidate query needs (a
	 * {@code foreign model} condition takes the record, never its id - {@code model-api.md}); it is never an
	 * authorization decision - that stays on the per-chapter {@code readBook} as the acting user.
	 *
	 * @return one DTO per readable chapter, ascending by {@code chapter} ordinal, each with
	 *         {@code objectId, name, slug, bookStatus, seriesObjectId, chapter, worldObjectId}
	 */
	public static List<Map<String, Object>> listSeriesBooks(BaseRecord user, String seriesObjectId) {
		if(user == null) {
			throw new PictureBookException(401, "No authenticated principal");
		}
		if(seriesObjectId == null || seriesObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A seriesObjectId is required");
		}
		long orgId = orgOf(user);

		/// Resolve the series as the OLIO principal - it owns the series row, and this record is used only to
		/// key the FK candidate query, never to authorize anything.
		BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal in organization " + orgId);
		}
		BaseRecord series = PbSeriesUtil.readSeries(olioUser, seriesObjectId.trim(), orgId);
		if(series == null) {
			throw new PictureBookException(404, "Series not found: " + seriesObjectId);
		}
		String resolvedSeriesObjectId = series.get(FieldNames.FIELD_OBJECT_ID);

		/// Candidate enumeration: the series' chapter rows, keyed by the series FK RECORD (a foreign model
		/// condition takes the record, not its id) and scoped to this organization. Raw search (PBAC bypass)
		/// as an internal, reliable decision - exactly PbSeriesUtil.findSeriesByWorld's rationale - so every
		/// chapter is a candidate regardless of the acting user's per-group grants; the per-chapter readBook
		/// below is the real authorization boundary. Ascending by chapter ordinal, uncached so a
		/// just-created chapter is visible. Only id/objectId/chapter are needed here.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SERIES, series);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_CHAPTER });
		q.setValue(FieldNames.FIELD_SORT_FIELD, OlioFieldNames.FIELD_PB_CHAPTER);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		q.setCache(false);
		BaseRecord[] candidates = IOSystem.getActiveContext().getSearch().findRecords(q);

		List<Map<String, Object>> out = new ArrayList<>();
		if(candidates == null) {
			return out;
		}
		for(BaseRecord cand : candidates) {
			String candObjectId = cand.get(FieldNames.FIELD_OBJECT_ID);
			if(candObjectId == null) {
				continue;
			}
			/// The authorization boundary: read each chapter as the ACTING user. An entitled caller (series
			/// Writer/Admin role on this chapter's Book group) gets the projected book; a non-entitled caller
			/// gets null and the chapter is dropped - so the entitlement is load-bearing, not decorative.
			BaseRecord book = PbBookUtil.readBook(user, candObjectId, orgId);
			if(book == null) {
				continue;
			}
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("objectId", book.get(FieldNames.FIELD_OBJECT_ID));
			dto.put("name", book.get(FieldNames.FIELD_NAME));
			dto.put("slug", book.get(OlioFieldNames.FIELD_PB_SLUG));
			dto.put("bookStatus", enumString(book, OlioFieldNames.FIELD_PB_BOOK_STATUS));
			dto.put("chapter", book.get(OlioFieldNames.FIELD_PB_CHAPTER));
			dto.put("seriesObjectId", fkObjectId(book, OlioFieldNames.FIELD_PB_SERIES, resolvedSeriesObjectId));
			dto.put("worldObjectId", fkObjectId(book, OlioFieldNames.FIELD_PB_WORLD, null));
			out.add(dto);
		}
		return out;
	}

	/**
	 * The {@code objectId} of a projected foreign-model FK, or {@code fallback} when the FK is null. A book
	 * projected through {@link PbBookUtil#bookRequest()} carries its {@code series}/{@code world} FKs as a
	 * minimal ref that includes {@code objectId} (see {@code requireSeriesScope}, which reads
	 * {@code series.objectId} exactly this way).
	 */
	private static String fkObjectId(BaseRecord rec, String field, String fallback) {
		BaseRecord fk = rec.get(field);
		if(fk == null) {
			return fallback;
		}
		String oid = fk.get(FieldNames.FIELD_OBJECT_ID);
		return (oid != null ? oid : fallback);
	}

	/**
	 * Ordered scene pages for a PB2 book, each with scene metadata and the composite artifact's
	 * data objectId (or null when no composite has been generated yet).
	 * <p>
	 * Authorized via {@code requireBook} before anything else — KI-67 pattern. Scenes are listed
	 * ordered by {@code sceneIndex}. For each scene the composite node is resolved via the
	 * {@code sceneNode} FK and the selected composite artifact is fetched. Migrated scenes
	 * ({@code sceneNode} null) return {@code dataObjectId: null} — correct, since migration does not
	 * produce PB2 artifacts.
	 */
	public static List<Map<String, Object>> bookPageView(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		long orgId = orgOf(user);
		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
		if(scenes.isEmpty()) {
			return new ArrayList<>();
		}

		Map<Long, BaseRecord> nodeMap = new HashMap<>();
		try {
			BaseRecord workflow = requireWorkflow(user, book);
			nodeMap = PbGraphUtil.nodesById(user, workflow);
		}
		catch(PictureBookException e) {
			// no workflow yet — pages have null dataObjectId
		}

		List<Map<String, Object>> out = new ArrayList<>();
		for(BaseRecord scene : scenes) {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("objectId", scene.get(FieldNames.FIELD_OBJECT_ID));
			p.put("sceneIndex", scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
			p.put("title", scene.get(OlioFieldNames.FIELD_PB_TITLE));
			p.put("blurb", scene.get(OlioFieldNames.FIELD_PB_BLURB));
			p.put("summary", scene.get(OlioFieldNames.FIELD_PB_SUMMARY));
			p.put("poemStanza", scene.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
			// Per-page style + staleness — the reader (loadReaderBook → /pages → renderChapBookPage) has no
			// other source for these, so without them the reader falls back to the historical hardcoded look
			// (white text / Georgia serif / center) and the author's chosen Text color / Font / Bg / Align
			// never render. listScenes already projects them via PbBookUtil.sceneRequest(); just surface them.
			p.put("pageFont", scene.get(OlioFieldNames.FIELD_PB_PAGE_FONT));
			p.put("pageBgColor", scene.get(OlioFieldNames.FIELD_PB_PAGE_BG_COLOR));
			p.put("pageBgOpacity", scene.get(OlioFieldNames.FIELD_PB_PAGE_BG_OPACITY));
			p.put("pageTextAlign", scene.get(OlioFieldNames.FIELD_PB_PAGE_TEXT_ALIGN));
			p.put("pageTextColor", scene.get(OlioFieldNames.FIELD_PB_PAGE_TEXT_COLOR));
			p.put("imageStale", scene.get(OlioFieldNames.FIELD_PB_IMAGE_STALE));

			String dataObjectId = null;
			if(!nodeMap.isEmpty()) {
				BaseRecord sceneNodeRef = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
				if(sceneNodeRef != null) {
					Object nodeIdObj = sceneNodeRef.get(FieldNames.FIELD_ID);
					Long nodeId = (nodeIdObj instanceof Number) ? ((Number) nodeIdObj).longValue() : null;
					if(nodeId != null) {
						BaseRecord node = nodeMap.get(nodeId);
						if(node != null) {
							BaseRecord artifact = PbArtifactUtil.findSelected(user, node, "composite");
							if(artifact != null) {
								BaseRecord data = artifact.get(OlioFieldNames.FIELD_PB_DATA);
								if(data != null) {
									dataObjectId = data.get(FieldNames.FIELD_OBJECT_ID);
								}
							}
						}
					}
				}
			}
			// For ChapBook scenes with no workflow node, fall back to the directly-generated image
			if (dataObjectId == null) {
				dataObjectId = (String) scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			}
			p.put("dataObjectId", dataObjectId);

			// The viewer builds a MediaServlet URL (/media/{dotPath}/data.data{groupPath}/{name}),
			// which is path-based, not objectId-based. Neither path above yields those fields: the
			// composite artifact projects its 'data' FK id-only (PbArtifactUtil.artifactRequest), and
			// the ChapBook fallback carries only the objectId string. So resolve the data.data record
			// explicitly, projecting the path fields the URL needs. Foreign/non-query fields are NOT
			// auto-populated — they must be requested.
			String imageGroupPath = null;
			String imageName = null;
			String imageContentType = null;
			if (dataObjectId != null) {
				BaseRecord data = resolveImageData(user, dataObjectId, orgId);
				if (data != null) {
					imageGroupPath = data.get(FieldNames.FIELD_GROUP_PATH);
					imageName = data.get(FieldNames.FIELD_NAME);
					imageContentType = data.get(FieldNames.FIELD_CONTENT_TYPE);
				}
			}
			p.put("imageGroupPath", imageGroupPath);
			p.put("imageName", imageName);
			p.put("imageContentType", imageContentType);
			out.add(p);
		}
		return out;
	}

	/**
	 * Load the {@code data.data} image record for a scene by objectId, projecting only the fields a
	 * MediaServlet/ThumbnailServlet URL needs ({@code groupPath}, {@code name}, {@code contentType}).
	 * <p>
	 * Read through {@code AccessPoint.find} so {@code canRead} applies to the image record itself, and
	 * constrained by {@code organizationId} so the objectId is resolved within the caller's tenant.
	 * Returns {@code null} when the objectId does not resolve to a readable {@code data.data}.
	 */
	private static BaseRecord resolveImageData(BaseRecord user, String dataObjectId, long orgId) {
		if(dataObjectId == null || dataObjectId.trim().length() == 0) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		// groupPath is a virtual field computed by PathProvider from groupId — it must be projected too,
		// or the provider has no baseProperty to walk and groupPath comes back null.
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_CONTENT_TYPE,
			FieldNames.FIELD_ORGANIZATION_ID
		});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	// ─────────────────────────────── canvas writes ───────────────────────────────

	/**
	 * Mark an artifact as the selected revision in its (node, role) chain.
	 * <p>
	 * Authorization: the artifact must belong to a node in this book's workflow (cross-book addressing
	 * is a 404). {@code PbArtifactUtil.setSelected} then patches all siblings atomically.
	 */
	public static Map<String, Object> selectArtifact(BaseRecord user, String bookObjectId, String artifactObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		if(artifactObjectId == null || artifactObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "An artifact objectId is required");
		}
		long orgId = PbGraphUtil.orgId(book);
		BaseRecord artifact = PbArtifactUtil.readArtifact(user, artifactObjectId, orgId);
		if(artifact == null) {
			throw new PictureBookException(404, "Artifact not found");
		}
		// cross-book check: the artifact's producedByNode must be in this book's workflow
		BaseRecord producedBy = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		if(producedBy != null) {
			String nodeOid = producedBy.get(FieldNames.FIELD_OBJECT_ID);
			if(nodeOid != null) {
				requireNodeOfBook(user, workflow, nodeOid);
			}
		}
		BaseRecord selected = PbArtifactUtil.setSelected(user, artifact);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("artifactObjectId", selected.get(FieldNames.FIELD_OBJECT_ID));
		out.put("selected", Boolean.TRUE);
		return out;
	}

	/**
	 * Persist canvas geometry ({@code canvasX, canvasY, canvasW, canvasH}) on a node.
	 * <p>
	 * Any combination of the four keys is accepted; absent keys are not touched (PATCH semantics).
	 * All four are nullable — a null value clears the stored position, falling back to auto-layout.
	 */
	public static Map<String, Object> saveCanvas(BaseRecord user, String bookObjectId, String nodeObjectId,
			Integer x, Integer y, Integer w, Integer h) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);

		List<String> changedFields = new ArrayList<>();
		changedFields.add(OlioFieldNames.FIELD_PB_CANVAS_X);
		changedFields.add(OlioFieldNames.FIELD_PB_CANVAS_Y);
		changedFields.add(OlioFieldNames.FIELD_PB_CANVAS_W);
		changedFields.add(OlioFieldNames.FIELD_PB_CANVAS_H);
		BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE,
			changedFields.toArray(new String[0]));
		try {
			patch.set(OlioFieldNames.FIELD_PB_CANVAS_X, x);
			patch.set(OlioFieldNames.FIELD_PB_CANVAS_Y, y);
			patch.set(OlioFieldNames.FIELD_PB_CANVAS_W, w);
			patch.set(OlioFieldNames.FIELD_PB_CANVAS_H, h);
		}
		catch(Exception e) {
			throw new PictureBookException(500, "Failed to assemble canvas patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to save canvas geometry for node " + nodeObjectId);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", nodeObjectId);
		out.put("canvasX", x);
		out.put("canvasY", y);
		out.put("canvasW", w);
		out.put("canvasH", h);
		return out;
	}

	/**
	 * Rename a node's handle (and its derived {@code name}, since name = handle-based unique key).
	 * <p>
	 * The handle is the stable human-readable token used in prompt templates ({@code @handle}); the
	 * derived name is what the URN provider composes from. Both are updated atomically.
	 */
	public static Map<String, Object> renameHandle(BaseRecord user, String bookObjectId,
			String nodeObjectId, String newHandle) {
		if(newHandle == null || newHandle.trim().length() == 0) {
			throw new PictureBookException(400, "A non-blank handle is required");
		}
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);

		String newName = PbGraphUtil.nodeName(newHandle);
		BaseRecord patch = PbGraphUtil.patchOf(node, OlioModelNames.MODEL_PB_NODE,
			OlioFieldNames.FIELD_PB_HANDLE, FieldNames.FIELD_NAME);
		try {
			patch.set(OlioFieldNames.FIELD_PB_HANDLE, newHandle);
			patch.set(FieldNames.FIELD_NAME, newName);
		}
		catch(Exception e) {
			throw new PictureBookException(500, "Failed to assemble handle patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to rename handle for node " + nodeObjectId);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", nodeObjectId);
		out.put("handle", newHandle);
		out.put("name", newName);
		return out;
	}

	/**
	 * Execute a single node synchronously against the SD backend and persist a new artifact revision.
	 * <p>
	 * The SD server URL is resolved from {@link org.cote.accountmanager.util.ServerConfigUtil#SERVER_SD}
	 * (the DB-backed runtime-configurable connection), falling back to {@code null} if not configured. A
	 * null server is a 503 at the executor rather than a silent no-op, so the error reaches the caller
	 * clearly.
	 * <p>
	 * Only PORTRAIT nodes are implemented today. All other node types return 400.
	 */
	public static Map<String, Object> testNode(BaseRecord user, String bookObjectId, String nodeObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);
		String swarmServer = org.cote.accountmanager.util.ServerConfigUtil.getServerUrl(
			org.cote.accountmanager.util.ServerConfigUtil.SERVER_SD, null);
		return PbNodeExecutor.executeNode(user, book, workflow, node, swarmServer);
	}

	// ─────────────────────────────── Phase D: binding writes ───────────────────────────────

	/**
	 * Add an edge from {@code sourceNodeObjectId} to {@code consumerNodeObjectId} with the given role.
	 * <p>
	 * <b>Both nodes are verified as belonging to this book's workflow</b> (architect requirement): a node
	 * from another book cannot be named as the source, and a node from another book cannot be named as
	 * the consumer. Cross-book addressing is a 404, not an authorization decision, so it discloses nothing
	 * about the other book.
	 * <p>
	 * The binding ordinal is derived from the consumer node's current binding count, so subsequent calls
	 * with different roles produce distinct ordinals. {@code PbGraphUtil.addBinding} runs
	 * {@link PbGraphUtil#validateAcyclic} before persisting, so a cycle is a 400.
	 */
	public static Map<String, Object> addBinding(BaseRecord user, String bookObjectId,
			String consumerNodeObjectId, String role, String sourceNodeObjectId) {
		if(role == null || role.trim().length() == 0) {
			throw new PictureBookException(400, "A role is required for the binding");
		}
		if(sourceNodeObjectId == null || sourceNodeObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A sourceNodeObjectId is required for the binding");
		}
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		/// ARCHITECT REQUIRED: verify BOTH nodes belong to this book's workflow.
		BaseRecord consumerNode = requireNodeOfBook(user, workflow, consumerNodeObjectId);
		BaseRecord sourceNode = requireNodeOfBook(user, workflow, sourceNodeObjectId);

		int ordinal = PbGraphUtil.listBindings(user, consumerNode).size();
		String groupPath = PbBookUtil.workflowGroupPath((String) book.get(OlioFieldNames.FIELD_PB_SLUG));
		BaseRecord binding = PbGraphUtil.addBinding(user, workflow, consumerNode, role, ordinal, sourceNode, null, groupPath);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bindingObjectId", binding.get(FieldNames.FIELD_OBJECT_ID));
		out.put("consumerNodeObjectId", consumerNodeObjectId);
		out.put("sourceNodeObjectId", sourceNodeObjectId);
		out.put("role", role);
		out.put("ordinal", Integer.valueOf(ordinal));
		return out;
	}

	/**
	 * Delete a binding, after verifying it belongs to this book's workflow.
	 * <p>
	 * <b>The consumer node is resolved from the binding and verified</b> (architect requirement): a
	 * binding not reachable from this book's workflow is a 404 — the same cross-book refusal as
	 * {@link #requireNodeOfBook}.
	 */
	public static Map<String, Object> deleteBinding(BaseRecord user, String bookObjectId, String bindingObjectId) {
		if(bindingObjectId == null || bindingObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A bindingObjectId is required");
		}
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);

		long orgId = orgOf(user);
		/// Read the binding so we can resolve its consumer node and verify book membership.
		BaseRecord binding = PbGraphUtil.readBinding(user, bindingObjectId, orgId);
		if(binding == null) {
			throw new PictureBookException(404, "Binding not found");
		}

		/// The binding's node FK carries at least its numeric id. Look up the full node from this
		/// workflow's node map so we have its objectId for requireNodeOfBook.
		BaseRecord nodeRef = binding.get(OlioFieldNames.FIELD_PB_NODE);
		Long nodeId = (nodeRef != null) ? (Long) nodeRef.get(FieldNames.FIELD_ID) : null;
		Map<Long, BaseRecord> nodes = PbGraphUtil.nodesById(user, workflow);
		BaseRecord consumerNode = (nodeId != null) ? nodes.get(nodeId) : null;
		if(consumerNode == null) {
			/// The binding's consumer node is not in this book's workflow — cross-book addressing.
			throw new PictureBookException(404, "Binding does not belong to this book's workflow");
		}
		/// ARCHITECT REQUIRED: verify via requireNodeOfBook (reads by objectId and checks workflow FK).
		String consumerNodeObjectId = consumerNode.get(FieldNames.FIELD_OBJECT_ID);
		requireNodeOfBook(user, workflow, consumerNodeObjectId);

		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(user, binding);
		if(!deleted) {
			throw new PictureBookException(500, "Failed to delete binding " + bindingObjectId);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("deleted", Boolean.TRUE);
		out.put("bindingObjectId", bindingObjectId);
		return out;
	}

	/**
	 * An enum field as a string, tolerant of the wire/Java case split.
	 * <p>
	 * Enums serialize lowercase on the wire and read back UPPERCASE in Java, and a list projection can
	 * return the raw lowercase. {@code getEnum} normalises; a null enum stays null rather than becoming the
	 * string "null".
	 */
	private static String enumString(BaseRecord rec, String field) {
		try {
			Object e = rec.getEnum(field);
			return (e != null ? e.toString() : null);
		}
		catch(Exception ex) {
			Object raw = rec.get(field);
			return (raw != null ? raw.toString() : null);
		}
	}
}
