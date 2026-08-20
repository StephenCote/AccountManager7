package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;

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
	 * Create the next chapter of a book: a new book (its own world, groups and role pair) linked to the
	 * source, optionally copying named records into it.
	 * <p>
	 * {@code PbBookUtil.createBook} stays the one creation path - this does not hand-roll a world. Copying
	 * goes through {@code PbSharingUtil.copyToChapter}, which requires membership of <b>both</b> books and
	 * records the lineage as a {@code chapterSource} binding.
	 * <p>
	 * §3.5 chose COPY over reference deliberately: apparel/wearables are per character, so a shared
	 * instance would make deleting chapter 1 destroy chapter 2's data.
	 */
	public static Map<String, Object> createChapter(BaseRecord user, String dataPath, String fromBookObjectId,
			String toSlug, String toTitle, List<String> copyRecordObjectIds, String copyRecordModel) {
		BaseRecord fromBook = requireBook(user, fromBookObjectId);
		String fromSlug = fromBook.get(OlioFieldNames.FIELD_PB_SLUG);
		if(toSlug == null || toSlug.trim().length() == 0) {
			throw new PictureBookException(400, "A slug is required for the new chapter");
		}
		long orgId = orgOf(user);
		if(PbBookUtil.findBookBySlug(user, toSlug, orgId) != null) {
			throw new PictureBookException(409, "A book with slug '" + toSlug + "' already exists");
		}

		BaseRecord toBook = PbBookUtil.createBook(user, dataPath, toSlug,
			(toTitle != null && toTitle.trim().length() > 0) ? toTitle : toSlug);
		if(toBook == null) {
			throw new PictureBookException(500, "Failed to create chapter '" + toSlug + "'");
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fromBookObjectId", fromBook.get(FieldNames.FIELD_OBJECT_ID));
		out.put("fromSlug", fromSlug);
		out.put("bookObjectId", toBook.get(FieldNames.FIELD_OBJECT_ID));
		out.put("slug", toSlug);
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
		String destField = PbSubRecordUtil.WORLD_GROUP_FIELD.get(copyRecordModel);
		BaseRecord destGroup = (destField != null ? toCtx.getGroup(destField) : null);
		if(destGroup == null) {
			throw new PictureBookException(400, "No chapter destination group is declared for " + copyRecordModel
				+ " - copyable models are " + PbSubRecordUtil.WORLD_GROUP_FIELD.keySet());
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
		List<BaseRecord> copies = PbSharingUtil.copyToChapter(user, fromSlug, toSlug, sources, destGroup, null, null,
			null);
		out.put("copied", Integer.valueOf(copies.size()));
		List<String> copiedIds = new ArrayList<>();
		for(BaseRecord c : copies) {
			copiedIds.add((String) c.get(FieldNames.FIELD_OBJECT_ID));
		}
		out.put("copiedObjectIds", copiedIds);
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
