package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.illustration.DeploymentModelDefaults;
import org.cote.accountmanager.olio.illustration.ModelInventory;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.RenderCalibration;
import org.cote.accountmanager.olio.illustration.RenderEstimate;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbPageFormatEnumType;
import org.cote.accountmanager.schema.type.PbProposalStatusEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;

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
 * <p>
 * <b>One bookless entry point: {@link #listModelsForTask}.</b> The illustration models-by-task read
 * (V3 §16/§27, P2-2) has no book: its root is the <i>optional</i> style, authorized by identity through
 * {@code AccessPoint.find} exactly as a book is, and with no style it reads only deployment config and the
 * SD server's inventory. It reads no book and writes nothing, so the root-reference principle is kept in
 * the only form it can take here.
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

	// ─────────────────────────────── illustration: models by task (V3 §16/§27) ───────────────────────────────

	/**
	 * The installed checkpoints the SD server reports, each with its renderer family and whether it can
	 * serve {@code task}, plus what {@code ModelResolver} would pick for that task today — through the
	 * optional style's {@code taskModels}/{@code sdConfig} (steps 2–3) and the boot-pinned deployment
	 * defaults (step 4). There is no override step here: a per-request override is the render call's,
	 * and this read reports the policy underneath it.
	 * <p>
	 * {@code sdApiType} and {@code sdServer} are the deployment's values, passed in by the caller
	 * (Service7 reads them from its init-params and {@code ServerConfigUtil}); nothing here reads
	 * process-global state. The inventory is a per-server snapshot with a TTL
	 * ({@code SwarmModelInventory.TTL_MS}); a failed read is reported as {@code inventory.error} and
	 * {@code resolution.reason = INVENTORY_UNAVAILABLE}, never as "nothing installed". The wire shape
	 * omits the SD server URL on purpose (§6.C 152 f) - as a field and inside any error text; the URL
	 * and the raw API-type init-param are logged, not returned.
	 *
	 * @throws PictureBookException 401 no principal; 400 unknown task; 404 the style does not exist or the
	 *         caller may not read it; 500 the SD server is not configured or its API type is not recognised.
	 */
	public static Map<String, Object> listModelsForTask(BaseRecord user, String task, String sdApiType, String sdServer, String styleObjectId) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		PbModelTaskEnumType taskType = ModelTaskKeys.task(task);
		if(taskType == PbModelTaskEnumType.UNKNOWN) {
			throw new PictureBookException(400, "Unknown model task '" + task + "'");
		}
		if(sdServer == null || sdServer.isBlank()) {
			throw new PictureBookException(500, "SD server is not configured");
		}
		SDAPIEnumType apiType;
		try {
			apiType = SDAPIEnumType.valueOf(sdApiType == null ? "" : sdApiType.trim().toUpperCase());
		}
		catch(IllegalArgumentException e) {
			logger.error("SD server API type '" + sdApiType + "' is not recognised");
			throw new PictureBookException(500, "SD server API type is not recognised");
		}

		ModelResolver.Request.Builder req = ModelResolver.Request.builder(taskType);
		if(styleObjectId != null && !styleObjectId.isBlank()) {
			BaseRecord style = PbStyleUtil.readStyle(user, styleObjectId, orgOf(user));
			if(style == null) throw new PictureBookException(404, "Style not found");
			req.taskModels(ModelTaskKeys.parseTaskModels(style.get(OlioFieldNames.FIELD_PB_TASK_MODELS)));
			BaseRecord cfg = PbStyleUtil.readSdConfigModels(user, style);
			if(cfg != null) {
				req.styleModel(cfg.get(OlioFieldNames.FIELD_SD_MODEL));
				req.styleFlux2Model(cfg.get(OlioFieldNames.FIELD_SD_FLUX2_MODEL));
			}
		}
		DeploymentModelDefaults.Defaults defaults = DeploymentModelDefaults.current();
		req.deploymentModel(defaults.getModel()).deploymentFlux2Model(defaults.getFlux2Model());

		ModelInventory inventory = SwarmModelInventory.forServer(apiType, sdServer);
		ModelResolver.Resolution resolution = ModelResolver.resolve(req.build(), inventory);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("task", ModelTaskKeys.key(taskType));
		Map<String, Object> inv = new LinkedHashMap<>();
		inv.put("fetchedAt", inventory.fetchedAt());
		inv.put("error", inventory.error());
		inv.put("count", inventory.installedModels().size());
		out.put("inventory", inv);
		Map<String, Object> dep = new LinkedHashMap<>();
		dep.put("model", defaults.getModel());
		dep.put("flux2Model", defaults.getFlux2Model());
		out.put("deploymentDefault", dep);
		out.put("resolution", resolutionDto(resolution));
		List<Map<String, Object>> models = new ArrayList<>();
		for(String name : inventory.installedModels()) {
			RendererCapabilities rc = RendererCapabilities.forModel(name);
			ModelResolver.Reason reason = ModelResolver.supportsTask(rc, taskType, false);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("listedName", name);
			m.put("family", rc != null ? rc.getPattern() : null);
			m.put("enabled", rc != null && rc.isEnabled());
			m.put("supportedForTask", reason == ModelResolver.Reason.OK);
			m.put("reason", reason.name());
			models.add(m);
		}
		out.put("models", models);
		return out;
	}

	private static Map<String, Object> resolutionDto(ModelResolver.Resolution r) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("model", r.getModel());
		out.put("family", r.getFamily());
		out.put("step", r.getStep() != null ? r.getStep().name() : null);
		out.put("reason", r.getReason().name());
		out.put("degraded", r.isDegraded());
		out.put("degradationCode", r.getDegradationCode());
		out.put("degradationText", r.getDegradationText());
		out.put("inventoryFetchedAt", r.getInventoryFetchedAt());
		out.put("inventoryError", r.getInventoryError());
		List<Map<String, Object>> cands = new ArrayList<>();
		for(ModelResolver.Candidate c : r.getCandidates()) {
			Map<String, Object> cm = new LinkedHashMap<>();
			cm.put("step", c.getStep().name());
			cm.put("requested", c.getRequested());
			cm.put("listedName", c.getListedName());
			cm.put("family", c.getFamily());
			cm.put("reason", c.getReason().name());
			cands.add(cm);
		}
		out.put("candidates", cands);
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

	/**
	 * {@code canUpdate} on an already-read book, or a 403 - the gate every write-producing action must
	 * pass <b>before</b> any SD/LLM work or privileged write is reached, so a denial costs nothing and
	 * generates nothing (the v1 {@code authorizeSceneAccess(WRITE)} invariant). Reading the book, the
	 * workflow and the node proves only that the caller may <i>see</i> them.
	 */
	private static void requireBookUpdate(BaseRecord user, BaseRecord book, String what) {
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, book);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not authorized to " + what + " in this book");
		}
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

	/**
	 * An artifact addressed within a book: read by objectId (so {@code canRead} applies, with
	 * {@code artifactRequest()} projected so its chain can be resolved), then its producing node confirmed
	 * to belong to <b>this</b> book's workflow through {@link #requireNodeOfBook}. Cross-book addressing
	 * is a 404 for the same reason a foreign node is.
	 */
	private static BaseRecord requireArtifactOfBook(BaseRecord user, BaseRecord workflow, String artifactObjectId) {
		if(artifactObjectId == null || artifactObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "An artifact objectId is required");
		}
		BaseRecord artifact = PbArtifactUtil.readArtifact(user, artifactObjectId, (long) workflow.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(artifact == null) {
			throw new PictureBookException(404, "Artifact not found");
		}
		BaseRecord producedBy = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		if(producedBy == null || producedBy.get(FieldNames.FIELD_OBJECT_ID) == null) {
			throw new PictureBookException(404, "Artifact not found in this book");
		}
		requireNodeOfBook(user, workflow, (String) producedBy.get(FieldNames.FIELD_OBJECT_ID));
		return artifact;
	}

	/**
	 * A LOCATION node's render is promoted onto the location record on approve, and cleared from it on reject,
	 * so the record it would write must belong to this book's world. A node whose {@code scopeRef} points at
	 * another world's location (a re-pointed row, a shared-world mix-up) is "not found" here, and nothing is
	 * promoted. The same answer for a location {@code user} cannot read at all: it exists (checked as the olio
	 * principal), so its lock still protects it. Nodes of every other type have no subject and pass.
	 */
	private static void requireSubjectInBookWorld(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord artifact) {
		BaseRecord producedBy = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		BaseRecord node = requireNodeOfBook(user, workflow, (String) producedBy.get(FieldNames.FIELD_OBJECT_ID));
		PbLocationUtil.NodeSubject subject;
		try {
			subject = PbLocationUtil.subjectOf(user, node);
		}
		catch(PictureBookException e) {
			if(e.getStatus() == 404) {
				if(PbLocationUtil.subjectExists(node)) {
					throw new PictureBookException(404, "Location not found in this book's world");
				}
				/// a dangling scopeRef has no record to protect; PbArtifactUtil decides whether the action needs one
				return;
			}
			throw e;
		}
		if(subject != null && !PbLocationUtil.sameWorld(book, subject.location)) {
			throw new PictureBookException(404, "Location not found in this book's world");
		}
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/**
	 * The whole graph for one book: the workflow, its nodes with a recomputed status, and the edges.
	 * <p>
	 * Statuses come from one {@link PbStaleUtil#scan} per book (cached ~30 s, invalidated by every PB graph
	 * write in this JVM), which also sees an edit to a <i>bound record</i> - a character's age - that
	 * {@code recomputeStatus} alone cannot (V3 §11.1). The scan persists STALE, and only STALE, as the
	 * viewer; a viewer who cannot update the nodes still gets the statuses, with {@code markPersisted=false}
	 * on the stale entries. The <b>persisted</b> status is returned alongside as {@code storedStatus} so a
	 * client can see when the two disagree.
	 */
	/// COST: one node list plus one binding read per node for the scan, then one more binding read per node
	/// here for the edges (listBindings is uncached by design). At ~7 nodes per scene a 41-scene book is
	/// ~287 nodes, so ~574 binding queries on a cold scan and ~287 within the TTL. The second read goes away
	/// when the scan starts retaining its bindings, which it deliberately does not do yet (no BaseRecords
	/// in the cache).
	public static Map<String, Object> workflowView(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		PbStaleUtil.Scan scan = PbStaleUtil.scan(user, book, workflow, false);

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
		out.put("autoApprove", autoApproveOf(book));
		out.put("lastRunObjectId", lastRunObjectIdOf(user, workflow));

		List<BaseRecord> nodes = PbGraphUtil.listNodes(user, workflow);
		List<Map<String, Object>> nodeViews = new ArrayList<>();
		List<Map<String, Object>> edges = new ArrayList<>();
		for(BaseRecord n : nodes) {
			nodeViews.add(nodeSummary(user, n, book, scan));
			for(BaseRecord b : PbGraphUtil.listBindings(user, n)) {
				edges.add(edgeSummary(b, n));
			}
		}
		out.put("nodes", nodeViews);
		out.put("edges", edges);
		out.put("staleCount", scan.entries.size());
		out.put("staleMarkPersisted", scan.markPersisted);
		out.put("staleUndeterminedRefs", scan.undeterminedRefs.size());
		out.put("scanComplete", scan.complete);
		out.put("styleTruncatedAt", scan.styleTruncatedAt);
		out.put("styleCycleDetected", scan.styleCycleDetected);
		out.put("scanAt", scan.at);
		return out;
	}

	/**
	 * One node in detail: its summary, its bindings, and the full artifact revision chain per role.
	 * <p>
	 * The status comes from the book's cached drift scan, so a cold cache costs a whole-book scan here;
	 * within the TTL it is one node read plus this node's own bindings and artifacts.
	 */
	public static Map<String, Object> nodeView(BaseRecord user, String bookObjectId, String nodeObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);
		PbStaleUtil.Scan scan = PbStaleUtil.scan(user, book, workflow, false);

		Map<String, Object> out = nodeSummary(user, node, book, scan);
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
		BaseRecord artifact = requireArtifactOfBook(user, workflow, artifactObjectId);

		Map<String, Object> out = artifactSummary(artifact);
		out.put("generatorRequest", artifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST));
		out.put("sdConfigSnapshot", artifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT));
		out.put("artifactText", artifact.get(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT));
		return out;
	}

	/**
	 * Every stale node of the book, i.e. what a "regenerate what changed" run would touch, from a
	 * <b>fresh</b> {@link PbStaleUtil#scan} (never the cached one - this is the "re-check" call).
	 * <p>
	 * Each entry is the node summary plus {@code reason} ({@code hashChanged} / {@code refDrift} /
	 * {@code marked} / {@code propagated}), {@code driftedBindings} (the bound records whose watched fields
	 * changed), {@code affectedSceneIndexes} (the rendered scene-scope nodes downstream, itself included)
	 * and {@code markPersisted}. The scan persists STALE on what it finds, as the caller, so the stored
	 * status agrees with the report for everyone who views the book next - unless the caller cannot update
	 * the nodes, in which case {@code markPersisted} is false and the stored status is untouched.
	 * <p>
	 * <b>A node that has never succeeded is not stale.</b> {@code inputHash} is null until a node's first
	 * success, so such a node is PENDING/READY - reporting it as stale would tell a user that work needs
	 * redoing when it has never been done. FAILED, SKIPPED and RUNNING nodes are likewise never marked.
	 */
	public static List<Map<String, Object>> listStale(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		PbStaleUtil.Scan scan = PbStaleUtil.scan(user, book, workflow, true);
		List<Map<String, Object>> out = new ArrayList<>();
		if(scan.entries.isEmpty()) {
			return out;
		}
		/// Re-list after the scan so storedStatus reflects what it persisted.
		for(BaseRecord n : PbGraphUtil.listNodes(user, workflow)) {
			Long id = n.get(FieldNames.FIELD_ID);
			if(id != null && scan.entries.containsKey(id)) {
				out.add(nodeSummary(user, n, book, scan));
			}
		}
		return out;
	}

	/**
	 * What an edit to one bound record ({@code refModel/refObjectId}, e.g. a charPerson) touches in this
	 * book: the nodes bound to it, whether the scan currently sees it as drifted, and the rendered scenes
	 * downstream of those nodes - the number behind "affected scenes: N". Always a fresh scan.
	 */
	public static Map<String, Object> affectedByRecord(BaseRecord user, String bookObjectId, String refModel, String refObjectId) {
		/// Principal first, so an unauthenticated caller learns nothing about model registration.
		if(user == null) {
			throw new PictureBookException(401, "No authenticated principal");
		}
		if(refModel == null || refModel.trim().length() == 0 || refObjectId == null || refObjectId.trim().length() == 0) {
			throw new PictureBookException(400, "A refModel and refObjectId are required");
		}
		/// The route's path regex only shapes the segment; an unregistered model name must not reach the
		/// scan (a fresh scan is the one permitted read-path write), so it is refused here as well. The
		/// registered-name set is consulted before the schema resolver: a miss in RecordFactory.getSchema
		/// is a DB query plus a classpath probe plus a logged stack trace, repeatable per request.
		String model = refModel.trim();
		if(!ModelNames.MODELS.contains(model) || !AccessPoint.isResolvableModel(model)) {
			throw new PictureBookException(400, "Unknown refModel " + refModel);
		}
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord workflow = requireWorkflow(user, book);
		PbStaleUtil.Scan scan = PbStaleUtil.scan(user, book, workflow, true);

		Set<Long> bound = scan.boundNodes(refModel, refObjectId);
		List<PbStaleUtil.Entry> drifted = scan.driftedEntriesFor(refModel, refObjectId);
		Set<Integer> staleScenes = new java.util.TreeSet<>();
		List<String> driftedHandles = new ArrayList<>();
		for(PbStaleUtil.Entry e : drifted) {
			staleScenes.addAll(e.affectedSceneIndexes);
			driftedHandles.add(e.handle);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("refModel", refModel);
		out.put("refObjectId", refObjectId);
		out.put("boundNodes", bound.size());
		out.put("drifted", !drifted.isEmpty());
		out.put("driftedHandles", driftedHandles);
		out.put("affectedSceneIndexes", new ArrayList<>(scan.affectedScenes(bound)));
		out.put("staleSceneIndexes", new ArrayList<>(staleScenes));
		out.put("markPersisted", scan.markPersisted);
		return out;
	}

	// ─────────────────────────────── writes ───────────────────────────────

	/**
	 * Regenerate a node: submit an {@code olio.pb.run} for exactly this node and execute it synchronously
	 * through {@link PbNodeExecutor#executeNodeRun}, producing a new artifact revision (V3 §20, P1-3).
	 * <p>
	 * Until P1-3 this only marked the node STALE and relied on the next scene generation to do the work.
	 * It now executes: the node is stamped RUNNING for the duration, closes DONE (PORTRAIT) or
	 * DONE_UNVERIFIED (the other executable types) with its downstream marked STALE, or FAILED with
	 * {@code lastError} when the executor throws — in which case the exception propagates with its own
	 * status. There is no STALE pre-mark: a node that is about to be re-rendered does not need to be
	 * declared out of date first, and a refused request (pinned, non-executable, no SD backend) leaves
	 * the node exactly as it was.
	 * <p>
	 * Gate order: 404 (book/workflow/node not readable), then <b>403 unless the caller may update the
	 * book</b> - rendering writes a run, the node, an artifact and (as the olio principal) the character's
	 * narrative, so read access alone must not reach it - then 409 PINNED, then the executor's own 501/500
	 * gates. All of these fire before anything is written or any GPU time is spent. The SD backend is the
	 * {@code (sdApiType, sdServer)} pair the transport resolved from its deployment config, the same way
	 * {@code generateSceneImage} receives it; a missing server is a 500 for the SD-backed types.
	 *
	 * @return the executor's artifact + run DTO, plus {@code executed=true}
	 */
	public static Map<String, Object> requestRegenerate(BaseRecord user, String bookObjectId, String nodeObjectId,
			String sdApiType, String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "regenerate a node");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);

		Boolean pinned = node.get(OlioFieldNames.FIELD_PB_PINNED);
		if(pinned != null && pinned.booleanValue()) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is pinned. Unpin it first - pinning means do not replace this artifact.");
		}

		/// The synchronous path claims the node too (architect ruling Q4): an async run that already holds
		/// it is a 409 here, and a sync regenerate holds it against async submits until it returns. The run
		/// is opened inside executeNodeRun, so the claim is keyed by a request token rather than a run id.
		List<Long> nodeIds = List.of((Long) node.get(FieldNames.FIELD_ID));
		String claimKey = "sync:" + UUID.randomUUID();
		Long conflict = PbNodeInflight.tryClaim(nodeIds, claimKey);
		if(conflict != null) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is being rendered by run " + PbNodeInflight.runOf(conflict.longValue()));
		}
		try {
			Map<String, Object> out = new LinkedHashMap<>(
				PbNodeExecutor.executeNodeRun(user, book, workflow, node, sdApiType, sdServer));
			out.put("executed", Boolean.TRUE);
			return out;
		}
		finally {
			PbNodeInflight.release(nodeIds, claimKey);
		}
	}

	/**
	 * Render one scene from its render plan, synchronously (V3 §12, §16, §20, P2-5): the scene's RENDER_PLAN
	 * node is created on first use (one per scene, beside its landscape / composite nodes; {@code scene.sceneNode}
	 * is not touched), then executed exactly as {@link #requestRegenerate} executes a node - same pin check,
	 * same in-flight claim, same executor. The plan is persisted as the node's {@code renderPlan} artifact
	 * before anything is sent; a scene whose BASE stage has no installed model is refused with the resolver's
	 * reason (409, or 503 when the renderer's inventory could not be read) and no render happens.
	 *
	 * @return the executor's artifact + run DTO (plus {@code planArtifactObjectId}, {@code planContentHash},
	 *         {@code strategy}, {@code stagesPlanned}, {@code stagesRendered}), {@code sceneObjectId},
	 *         {@code sceneIndex}, {@code renderPath=v3} and {@code executed=true}
	 */
	public static Map<String, Object> requestRenderPlanScene(BaseRecord user, String bookObjectId, String sceneObjectId,
			String sdApiType, String sdServer) {
		if(user == null) {
			throw new PictureBookException(401, "No authenticated principal");
		}
		org.cote.accountmanager.olio.sd.SDUtil sdu = null;
		if(sdServer != null && !sdServer.isBlank()) {
			/// Same refusal planScene gives a misconfigured backend; a blank server stays null so the executor's
			/// own gate answers 500 "SD server not configured" for this SD-backed node type.
			try {
				sdu = new org.cote.accountmanager.olio.sd.SDUtil(SDAPIEnumType.valueOf(sdApiType == null ? "" : sdApiType.trim().toUpperCase()), sdServer);
			}
			catch(IllegalArgumentException e) {
				logger.error("SD server API type '" + sdApiType + "' is not recognised");
				throw new PictureBookException(500, "SD server API type is not recognised");
			}
		}
		return requestRenderPlanScene(user, bookObjectId, sceneObjectId, sdu);
	}

	/** {@link #requestRenderPlanScene} over an {@link org.cote.accountmanager.olio.sd.SDUtil} the caller built (test seam). */
	static Map<String, Object> requestRenderPlanScene(BaseRecord user, String bookObjectId, String sceneObjectId,
			org.cote.accountmanager.olio.sd.SDUtil sdu) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "render a scene plan");
		BaseRecord scene = requireSceneOfBook(user, book, sceneObjectId);
		BaseRecord workflow = ensureWorkflow(user, book);
		BaseRecord node = ensureRenderPlanNode(user, book, workflow, scene);

		Boolean pinned = node.get(OlioFieldNames.FIELD_PB_PINNED);
		if(pinned != null && pinned.booleanValue()) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is pinned. Unpin it first - pinning means do not replace this artifact.");
		}

		List<Long> nodeIds = List.of((Long) node.get(FieldNames.FIELD_ID));
		String claimKey = "sync:" + UUID.randomUUID();
		Long conflict = PbNodeInflight.tryClaim(nodeIds, claimKey);
		if(conflict != null) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is being rendered by run " + PbNodeInflight.runOf(conflict.longValue()));
		}
		try {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("sceneObjectId", scene.get(FieldNames.FIELD_OBJECT_ID));
			out.put("sceneIndex", scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
			out.put("renderPath", "v3");
			out.putAll(PbNodeExecutor.executeNodeRun(user, book, workflow, node, sdu));
			out.put("executed", Boolean.TRUE);
			return out;
		}
		finally {
			PbNodeInflight.release(nodeIds, claimKey);
		}
	}

	/**
	 * Submit an asynchronous render of one scene (V3 §21, P1-6): the scene's graph node is the terminal,
	 * its upstream closure the plan, and the request returns as soon as the run is opened and the job
	 * queued. See {@link PbRenderJobUtil#submit} for what is planned, executed, reused and refused.
	 * <p>
	 * Gate order: 404 book / workflow / scene (a scene of another book is a 404, not a 403 - the book in the
	 * path is the only compartment the caller addressed), 403 unless the caller may update the book, then
	 * 409 when the scene has no graph node yet (it was extracted but never built into the workflow - run
	 * the graph build first), then the planner's own 409 / 429 / 500.
	 *
	 * @return {@code jobId}, {@code runObjectId} and the plan summary; poll {@code GET /rest/job/{jobId}}
	 *         or read the run
	 */
	public static Map<String, Object> requestSceneRender(BaseRecord user, String bookObjectId, String sceneObjectId,
			String sdApiType, String sdServer) {
		return requestSceneRender(user, bookObjectId, sceneObjectId, sdApiType, sdServer, false);
	}

	/**
	 * {@link #requestSceneRender(BaseRecord, String, String, String, String)} with the planner's
	 * {@code force} flag. An explicit per-scene (re-)render from the reader is a re-roll of that scene's
	 * node even when it is current (the same contract the ChapBook page path uses, §21); without
	 * {@code force} a current node is reused and the planner refuses with a 409. {@code force} never
	 * overrides a pin: a pinned terminal is still reported as {@code pinned} and the request is still a
	 * 409 until the caller unpins it (§10). The bulk {@link #requestRenderAll} does not force.
	 */
	public static Map<String, Object> requestSceneRender(BaseRecord user, String bookObjectId, String sceneObjectId,
			String sdApiType, String sdServer, boolean force) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "render a scene");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord scene = requireSceneOfBook(user, book, sceneObjectId);
		BaseRecord sceneNode = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
		Long nodeId = (sceneNode != null ? sceneNode.get(FieldNames.FIELD_ID) : null);
		if(nodeId == null || nodeId.longValue() <= 0L) {
			throw new PictureBookException(409, "Scene " + scene.get(FieldNames.FIELD_NAME)
				+ " has no graph node yet; build the book's graph before rendering it");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sceneObjectId", scene.get(FieldNames.FIELD_OBJECT_ID));
		out.put("sceneIndex", scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
		out.putAll(PbRenderJobUtil.submit(user, book, workflow, List.of(nodeId), sdApiType, sdServer,
			PbRenderJobUtil.KIND_RENDER, force));
		return out;
	}

	/**
	 * Submit one asynchronous render run for every scene of the book that has a graph node (V3 §21,
	 * P1-6). The terminals are the scenes' nodes; the upstream closure (portraits, prompts, strips) is
	 * planned once and shared, so a character rendered for scene 1 is reused by scene 7 within the same
	 * run. Scenes without a node are reported in {@code scenesWithoutNode} and skipped; a book where no
	 * scene has a node is a 409.
	 */
	public static Map<String, Object> requestRenderAll(BaseRecord user, String bookObjectId, String sdApiType,
			String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "render the book");
		BaseRecord workflow = requireWorkflow(user, book);
		List<Long> terminals = new ArrayList<>();
		List<String> withoutNode = new ArrayList<>();
		for(BaseRecord scene : PbBookUtil.listScenes(user, book)) {
			BaseRecord sceneNode = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
			Long nodeId = (sceneNode != null ? sceneNode.get(FieldNames.FIELD_ID) : null);
			if(nodeId == null || nodeId.longValue() <= 0L) {
				withoutNode.add(scene.get(FieldNames.FIELD_OBJECT_ID));
				continue;
			}
			terminals.add(nodeId);
		}
		if(terminals.isEmpty()) {
			throw new PictureBookException(409, "No scene of this book has a graph node yet; build the book's graph before rendering it");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sceneCount", Integer.valueOf(terminals.size()));
		out.put("scenesWithoutNode", withoutNode);
		out.putAll(PbRenderJobUtil.submit(user, book, workflow, terminals, sdApiType, sdServer,
			PbRenderJobUtil.KIND_RENDER_ALL));
		return out;
	}

	/*
	 * ---------------------------------------------------------------------------------------------
	 * ChapBook pages on the Phase 1 path (V3 §24, P1-9)
	 * ---------------------------------------------------------------------------------------------
	 * A ChapBook page is one olio.pb.scene whose prompt is the scene's own sdPrompt. On the v3 path the
	 * page is a LANDSCAPE node of the book's workflow (handle landscapeHandle(sceneObjectId), scope
	 * "scene"), its image a "landscape" artifact of that node, and the render the same pb.render /
	 * pb.renderAll job the PictureBook scene path submits. The ChapBookUtil entry points call these; the
	 * old render path is untouched and stays the v1 default.
	 */

	/**
	 * The read-only gate of {@link #requestChapBookPageRender}: 404 book / page, 409 unless the book is on
	 * the v3 path, 403 unless the caller may update the book, 403 unless the book is a ChapBook. Nothing is
	 * written, so a refused request leaves no workflow or node behind (architect P1-9 B4).
	 *
	 * @return the scene, with its {@code book} FK confirmed to be the addressed book
	 */
	public static BaseRecord checkChapBookPageRender(BaseRecord user, String bookObjectId, String sceneObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireChapBook(book);
		requireBookUpdate(user, book, "render a page");
		return requireSceneOfBook(user, book, sceneObjectId);
	}

	/**
	 * Submit an asynchronous render of one ChapBook page (V3 §24, P1-9).
	 * <p>
	 * Gates first ({@link #checkChapBookPageRender}), then the lazy writes: the book's workflow is created
	 * if it has none (ChapBooks created before the workflow block existed have none), the page's LANDSCAPE
	 * node is created if missing and linked as {@code scene.sceneNode}, and the scene's {@code sdPrompt} /
	 * {@code configOverride} are mirrored onto the node. A page with no prompt is a 409 - there is nothing
	 * to render and no node is created for it. A pinned node is a 409 (approved pages are not replaced
	 * until unpinned). The job is submitted with {@code force=true}: a ChapBook page render is an explicit
	 * "render this page" and re-rolls a current node too, exactly as the v1 path always re-rendered.
	 *
	 * @return {@code sceneObjectId}, {@code sceneIndex}, {@code nodeObjectId}, plus {@link PbRenderJobUtil#submit}'s
	 *         {@code jobId} / {@code runObjectId} / plan summary
	 */
	public static Map<String, Object> requestChapBookPageRender(BaseRecord user, String bookObjectId,
			String sceneObjectId, String sdApiType, String sdServer) {
		return requestChapBookPageRender(user, bookObjectId, sceneObjectId,
			PbNodeExecutor.toSdUtil(sdApiType, sdServer), sdServer);
	}

	/** {@link #requestChapBookPageRender} over an {@link org.cote.accountmanager.olio.sd.SDUtil} the caller built (test seam). */
	static Map<String, Object> requestChapBookPageRender(BaseRecord user, String bookObjectId, String sceneObjectId,
			org.cote.accountmanager.olio.sd.SDUtil sdu, String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireChapBook(book);
		requireBookUpdate(user, book, "render a page");
		BaseRecord scene = requireSceneOfBook(user, book, sceneObjectId);
		String prompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		if(prompt == null || prompt.isBlank()) {
			throw new PictureBookException(409, "Page " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX)
				+ " has no prompt; set or generate its landscape prompt before rendering it");
		}
		BaseRecord workflow = requireChapBookWorkflow(user, book);
		BaseRecord node = ensureChapBookPageNode(user, book, workflow, scene);
		Boolean pinned = node.get(OlioFieldNames.FIELD_PB_PINNED);
		if(pinned != null && pinned.booleanValue()) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is pinned. Unpin it first - pinning means do not replace this artifact.");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sceneObjectId", scene.get(FieldNames.FIELD_OBJECT_ID));
		out.put("sceneIndex", scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.putAll(PbRenderJobUtil.submit(user, book, workflow, List.of((Long) node.get(FieldNames.FIELD_ID)),
			sdu, sdServer, PbRenderJobUtil.KIND_RENDER, true));
		return out;
	}

	/**
	 * Submit one asynchronous {@code pb.renderAll} run for every prompted page of a ChapBook (V3 §24,
	 * P1-9) - the bulk render as one job, never one job per page (architect P1-9 B1). Pages without a
	 * prompt are reported in {@code scenesWithoutPrompt} and get no node; a book with no prompted page is
	 * a 409. Submitted with {@code force=true} like the single-page render; pinned pages are skipped by the
	 * planner and reported in {@code pinnedNodeCount}, and a book whose every prompted page is pinned is the
	 * planner's 409.
	 *
	 * @return {@code sceneCount} (pages submitted), {@code scenesWithoutPrompt}, {@code nodeObjectIds} (in
	 *         page order), plus {@link PbRenderJobUtil#submit}'s {@code jobId} / {@code runObjectId} / summary
	 */
	public static Map<String, Object> requestChapBookRenderAll(BaseRecord user, String bookObjectId,
			String sdApiType, String sdServer) {
		return requestChapBookRenderAll(user, bookObjectId, PbNodeExecutor.toSdUtil(sdApiType, sdServer), sdServer);
	}

	/** {@link #requestChapBookRenderAll} over an {@link org.cote.accountmanager.olio.sd.SDUtil} the caller built (test seam). */
	static Map<String, Object> requestChapBookRenderAll(BaseRecord user, String bookObjectId,
			org.cote.accountmanager.olio.sd.SDUtil sdu, String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireChapBook(book);
		requireBookUpdate(user, book, "render the book");
		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
		List<BaseRecord> prompted = new ArrayList<>();
		List<String> withoutPrompt = new ArrayList<>();
		for(BaseRecord scene : scenes) {
			String prompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			if(prompt == null || prompt.isBlank()) {
				withoutPrompt.add(scene.get(FieldNames.FIELD_OBJECT_ID));
				continue;
			}
			prompted.add(scene);
		}
		if(prompted.isEmpty()) {
			throw new PictureBookException(409, "No page of this book has a prompt yet; set or generate the landscape prompts before rendering it");
		}
		BaseRecord workflow = requireChapBookWorkflow(user, book);
		List<Long> terminals = new ArrayList<>();
		List<String> nodeObjectIds = new ArrayList<>();
		for(BaseRecord scene : prompted) {
			BaseRecord node = ensureChapBookPageNode(user, book, workflow, scene);
			terminals.add((Long) node.get(FieldNames.FIELD_ID));
			nodeObjectIds.add(node.get(FieldNames.FIELD_OBJECT_ID));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sceneCount", Integer.valueOf(terminals.size()));
		out.put("scenesWithoutPrompt", withoutPrompt);
		out.put("nodeObjectIds", nodeObjectIds);
		out.putAll(PbRenderJobUtil.submit(user, book, workflow, terminals, sdu, sdServer,
			PbRenderJobUtil.KIND_RENDER_ALL, true));
		return out;
	}

	/** 403 unless the book is a ChapBook - same status and wording as {@code ChapBookUtil}'s guard. */
	private static void requireChapBook(BaseRecord book) {
		// Typed as Object on purpose: String.valueOf(record.get(..)) resolves to valueOf(char[]) and throws ClassCastException
		Object rawType = (book.hasField(OlioFieldNames.FIELD_PB_BOOK_TYPE) ? book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE) : null);
		String bookType = (rawType != null ? rawType.toString() : null);
		if(bookType == null || !"CHAPBOOK".equalsIgnoreCase(bookType)) {
			throw new PictureBookException(403, "Book " + book.get(FieldNames.FIELD_OBJECT_ID) + " is not a CHAPBOOK");
		}
	}

	/**
	 * The ChapBook's workflow, created on first use: {@code createChapBook} has made one since the workflow
	 * block was added, but older ChapBooks have none and a page render is the natural moment to add it.
	 * Reached only after the gates, so the create is an authorized write by the book's updater.
	 */
	private static BaseRecord requireChapBookWorkflow(BaseRecord user, BaseRecord book) {
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null || slug.isBlank()) {
			throw new PictureBookException(500, "ChapBook " + book.get(FieldNames.FIELD_OBJECT_ID) + " carries no slug");
		}
		BaseRecord workflow = PbGraphUtil.getCreateWorkflow(user, book, PbBookUtil.workflowGroupPath(slug));
		if(workflow == null) {
			throw new PictureBookException(500, "Failed to create the workflow for ChapBook " + slug);
		}
		return workflow;
	}

	/**
	 * The page's LANDSCAPE node, created and linked on first use and brought up to date after that:
	 * <ol>
	 * <li>found by handle ({@link PbPipelineUtil#landscapeHandle} of the scene objectId - deterministic, so
	 *     this is idempotent) or created with {@code scope=scene}, {@code scopeRef=} the scene objectId and
	 *     {@code sceneIndex} / ordinal = the page index;</li>
	 * <li>{@code scene.sdPrompt} mirrored to {@code node.promptText} and {@code scene.configOverride} to
	 *     {@code node.configOverride} when they differ - the scene stays the authority (the executor reads
	 *     the scene), the node carries the copy the review views show;</li>
	 * <li>{@code scene.sceneNode} patched to the node when it is not already that node.</li>
	 * </ol>
	 * Every write is a PATCH as the request user; a failed write is a 500 naming it, never silent.
	 */
	private static BaseRecord ensureChapBookPageNode(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord scene) {
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, handle);
		if(node == null) {
			String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
			node = PbGraphUtil.addNode(user, workflow, handle, PbNodeTypeEnumType.LANDSCAPE,
				PbBookUtil.workflowGroupPath(slug), idx);
			if(node == null) {
				throw new PictureBookException(500, "Failed to create the page node " + handle);
			}
			if(!PbPipelineUtil.patchNodeScope(user, workflow, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx)) {
				throw new PictureBookException(500, "Failed to record the page scope on node " + handle);
			}
		}

		if(!PbBookUtil.mirrorSceneOntoNode(user, scene, node)) {
			throw new PictureBookException(500, "Failed to mirror the page prompt / config override onto node " + handle);
		}

		BaseRecord sceneNodeRef = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
		Long linked = (sceneNodeRef != null ? sceneNodeRef.get(FieldNames.FIELD_ID) : null);
		Long nodeId = node.get(FieldNames.FIELD_ID);
		if(linked == null || !linked.equals(nodeId)) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
			try {
				patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				throw new PictureBookException(500, "Failed to assemble the sceneNode patch: " + e.getMessage());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				throw new PictureBookException(500, "Failed to link page " + idx + " to node " + handle);
			}
		}
		/// Re-read so the caller sees the mirrored fields and the current status / pin, not the pre-patch copy.
		BaseRecord fresh = PbGraphUtil.readNode(user, node.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(node));
		return (fresh != null ? fresh : node);
	}

	/**
	 * The book's workflow, created on first use (P2-5): a plain PictureBook whose scenes were planned but never
	 * generated has none, and the first render-plan execution is the natural moment to add it. Reached only
	 * after the gates, so the create is an authorized write by the book's updater.
	 */
	private static BaseRecord ensureWorkflow(BaseRecord user, BaseRecord book) {
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		if(slug == null || slug.isBlank()) {
			throw new PictureBookException(500, "Book " + book.get(FieldNames.FIELD_OBJECT_ID) + " carries no slug");
		}
		BaseRecord workflow = PbGraphUtil.getCreateWorkflow(user, book, PbBookUtil.workflowGroupPath(slug));
		if(workflow == null) {
			throw new PictureBookException(500, "Failed to create the workflow for book " + slug);
		}
		return workflow;
	}

	/**
	 * The scene's RENDER_PLAN node (V3 §12, P2-5), created on first use: found by handle
	 * ({@link PbPipelineUtil#renderPlanHandle} of the scene objectId - deterministic, so this is idempotent) or
	 * created with {@code scope=scene}, {@code scopeRef=} the scene objectId and {@code sceneIndex} / ordinal =
	 * the scene index. Unlike {@link #ensureChapBookPageNode} nothing is mirrored onto it and
	 * {@code scene.sceneNode} is left alone: the executor reads the scene and the plan is the record.
	 */
	private static BaseRecord ensureRenderPlanNode(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord scene) {
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.renderPlanHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, handle);
		if(node == null) {
			String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
			node = PbGraphUtil.addNode(user, workflow, handle, PbNodeTypeEnumType.RENDER_PLAN,
				PbBookUtil.workflowGroupPath(slug), idx);
			if(node == null) {
				throw new PictureBookException(500, "Failed to create the render-plan node " + handle);
			}
			if(!PbPipelineUtil.patchNodeScope(user, workflow, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx)) {
				throw new PictureBookException(500, "Failed to record the scene scope on node " + handle);
			}
		}
		BaseRecord fresh = PbGraphUtil.readNode(user, node.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(node));
		return (fresh != null ? fresh : node);
	}

	/**
	 * Request cooperative cancellation of a render run (V3 §21, P1-6).
	 * <p>
	 * The run is addressed through the book: 404 unless the caller can read the book and the run is a run
	 * of that book's workflow; 403 unless the caller may update the book (cancelling is a write to the run
	 * and its nodes). The cancel flips the job's own token ({@link PbRunCancelRegistry}); an in-flight LLM
	 * stream is aborted, an in-flight SD call runs to completion and its bytes are discarded, and the run
	 * closes CANCELLED at the next stage boundary - this call returns before that happens, so the caller
	 * reads the run (or polls the job) to see the terminal state.
	 * <p>
	 * A run this JVM is not executing cannot be cancelled from here: a run already terminal is a 409 naming
	 * its status; a RUNNING / PENDING run with no in-flight job in this process (another JVM's, or one
	 * orphaned by a restart) is a 409 too, telling the caller it is waiting on restart recovery (P1-7).
	 *
	 * @return {@code runObjectId}, {@code runStatus} as read before the cancel, {@code cancelRequested=true}
	 */
	public static Map<String, Object> cancelRun(BaseRecord user, String bookObjectId, String runObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "cancel a run");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord run = requireRunOfBook(user, workflow, runObjectId);
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		PbRunStatusEnumType status = run.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS);
		boolean inFlight = (status == PbRunStatusEnumType.PENDING || status == PbRunStatusEnumType.RUNNING);
		if(!inFlight) {
			throw new PictureBookException(409, "Run " + run.get(FieldNames.FIELD_NAME) + " is " + status
				+ " and cannot be cancelled");
		}
		if(!PbRunCancelRegistry.cancel(runOid)) {
			if(PbRunCancelRegistry.peek(runOid) != null) {
				throw new PictureBookException(409, "Run " + run.get(FieldNames.FIELD_NAME) + " is already being cancelled");
			}
			throw new PictureBookException(409, "Run " + run.get(FieldNames.FIELD_NAME) + " is " + status
				+ " but no job for it is in flight in this process; it will be reconciled by restart recovery");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runObjectId", runOid);
		out.put("runStatus", status.name());
		out.put("cancelRequested", Boolean.TRUE);
		return out;
	}

	/**
	 * Read one run of a book (V3 §21, P1-7): the run row plus, per node of the workflow, its current
	 * status - what the Ux needs to show stage progress after the submitting page is gone and to offer
	 * "Resume" on an INTERRUPTED run.
	 * <p>
	 * 404 unless the caller can read the book and the run belongs to the book's workflow. No update grant
	 * is needed: this is a read. {@code inFlightInThisProcess} says whether a job for the run exists in
	 * this JVM right now; {@code resumable} is the exact predicate {@link #resumeRun} applies (INTERRUPTED
	 * with a recorded execution set), so a client that keys "Resume" on it never gets a 409 it could have
	 * predicted. The node list is one read ({@link PbGraphUtil#nodesById}), not one per requested id.
	 */
	public static Map<String, Object> getRun(BaseRecord user, String bookObjectId, String runObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord run = requireRunOfBook(user, workflow, runObjectId);
		return runDto(user, workflow, run);
	}

	/**
	 * Resume an INTERRUPTED run (V3 §21, P1-7) by submitting a new one for its {@code requestedNodeIds};
	 * see {@link PbRenderJobUtil#resume} for what is reused and refused.
	 * <p>
	 * Gate order: 404 book / workflow / run, 403 unless the caller may update the book (a resume renders),
	 * then 409 unless the run is INTERRUPTED, 409 when it carries no execution set (opened before P1-6),
	 * then the planner's own 409 (everything already current) / 429 / 500.
	 */
	public static Map<String, Object> resumeRun(BaseRecord user, String bookObjectId, String runObjectId,
			String sdApiType, String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "resume a run");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord run = requireRunOfBook(user, workflow, runObjectId);
		return PbRenderJobUtil.resume(user, book, workflow, run, sdApiType, sdServer);
	}

	private static Map<String, Object> runDto(BaseRecord user, BaseRecord workflow, BaseRecord run) {
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		PbRunStatusEnumType status = run.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS);
		List<Long> requested = PbRenderJobUtil.requestedNodeIdsOf(run);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("runObjectId", runOid);
		out.put("name", run.get(FieldNames.FIELD_NAME));
		out.put("runStatus", (status != null ? status.name() : null));
		/// ZonedDateTime is not serializable by JSONUtil.exportObject (no JavaTimeModule): a raw value makes
		/// the whole DTO export null and the route answer 200 with an empty body.
		Object startedAt = run.get(OlioFieldNames.FIELD_PB_STARTED_AT);
		Object completedAt = run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT);
		out.put("startedAt", (startedAt != null ? startedAt.toString() : null));
		out.put("completedAt", (completedAt != null ? completedAt.toString() : null));
		out.put("requestedNodeIds", requested);
		out.put("executedNodeCount", run.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		out.put("failedNodeCount", run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		out.put("error", run.get(OlioFieldNames.FIELD_PB_ERROR));
		out.put("inFlightInThisProcess", Boolean.valueOf(runOid != null && PbRunCancelRegistry.peek(runOid) != null));
		out.put("jobId", (runOid != null ? PbRunCancelRegistry.jobIdOf(runOid) : null));
		out.put("resumable", Boolean.valueOf(status == PbRunStatusEnumType.INTERRUPTED && !requested.isEmpty()));
		List<Map<String, Object>> nodes = new ArrayList<>();
		Map<Long, BaseRecord> byId = PbGraphUtil.nodesById(user, workflow);
		for(Long id : requested) {
			BaseRecord node = byId.get(id);
			Map<String, Object> n = new LinkedHashMap<>();
			n.put("nodeId", id);
			if(node != null) {
				n.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
				n.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
				Object ns = node.get(OlioFieldNames.FIELD_PB_NODE_STATUS);
				n.put("nodeStatus", (ns != null ? String.valueOf(ns) : null));
			}
			else {
				n.put("nodeStatus", "MISSING");
			}
			nodes.add(n);
		}
		out.put("nodes", nodes);
		return out;
	}

	/** A scene readable by {@code user} that belongs to {@code book}; 404 otherwise. */
	private static BaseRecord requireSceneOfBook(BaseRecord user, BaseRecord book, String sceneObjectId) {
		if(sceneObjectId == null || sceneObjectId.isBlank()) {
			throw new PictureBookException(400, "A scene objectId is required");
		}
		BaseRecord scene = PbBookUtil.readScene(user, sceneObjectId, orgOf(user));
		BaseRecord sceneBook = (scene != null ? scene.get(OlioFieldNames.FIELD_PB_BOOK) : null);
		Long sceneBookId = (sceneBook != null ? sceneBook.get(FieldNames.FIELD_ID) : null);
		Long bookId = book.get(FieldNames.FIELD_ID);
		if(scene == null || sceneBookId == null || !sceneBookId.equals(bookId)) {
			throw new PictureBookException(404, "Scene " + sceneObjectId + " not found in this book");
		}
		return scene;
	}

	/** A run readable by {@code user} that belongs to {@code workflow}; 404 otherwise. */
	private static BaseRecord requireRunOfBook(BaseRecord user, BaseRecord workflow, String runObjectId) {
		if(runObjectId == null || runObjectId.isBlank()) {
			throw new PictureBookException(400, "A run objectId is required");
		}
		BaseRecord run = PbGraphUtil.readRun(user, runObjectId, orgOf(user));
		BaseRecord runWorkflow = (run != null ? run.get(OlioFieldNames.FIELD_PB_WORKFLOW) : null);
		Long runWorkflowId = (runWorkflow != null ? runWorkflow.get(FieldNames.FIELD_ID) : null);
		Long workflowId = workflow.get(FieldNames.FIELD_ID);
		if(run == null || runWorkflowId == null || !runWorkflowId.equals(workflowId)) {
			throw new PictureBookException(404, "Run " + runObjectId + " not found in this book");
		}
		return run;
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
	 * Get-or-create the series for {@code seriesSlug} and return the two objectIds the N-series client
	 * needs to launch per-chapter creation: the series objectId and its ONE shared world's objectId
	 * (every chapter's {@code book.world} = this series' {@code universe}).
	 * <p>
	 * <b>All resolution and authorization live in {@link PbSeriesUtil#getCreateSeries}.</b> This is the
	 * DTO seam only: it calls that get-or-create and projects the two objectIds off the returned series
	 * record (re-read as the acting user through {@code AccessPoint}). The acting {@code user} is passed
	 * straight through — {@code getCreateSeries} resolves the olio principal ITSELF for the privileged
	 * writes (the series row in the universe's Read-only Book group, and the {@code universe} link) and
	 * then re-reads as the caller to prove the universe Read grant landed. Service7 neither knows nor
	 * assumes the olio principal.
	 * <p>
	 * <b>Get-or-create, therefore POST-only.</b> An existing readable series is returned as-is (so
	 * chapter 2+ reuses chapter 1's world instead of colliding with it), but the create branch performs
	 * privileged writes — so this must never be reachable from a GET/read handler.
	 *
	 * @return {@code { seriesObjectId, worldObjectId }}; {@code worldObjectId} is the shared series
	 *         world ({@code universe} FK), or null if somehow unlinked.
	 */
	public static Map<String, Object> createSeries(BaseRecord user, String dataPath, String seriesSlug, String title) {
		if(user == null) {
			throw new PictureBookException(401, "No authenticated principal");
		}
		BaseRecord series = PbSeriesUtil.getCreateSeries(user, dataPath, seriesSlug, title);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("seriesObjectId", series.get(FieldNames.FIELD_OBJECT_ID));
		out.put("worldObjectId", fkObjectId(series, OlioFieldNames.FIELD_PB_UNIVERSE, null));
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
		/// Shadows were deleted and re-created: whatever the book's scan knew about its bound records is gone.
		PbStaleUtil.touchBook(user, book);

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
		if(merged > 0) {
			/// List results may carry schema only on the first element; the members are charPersons by construction.
			for(BaseRecord s : shadowMembers) {
				PbStaleUtil.touch(OlioModelNames.MODEL_CHAR_PERSON, s.get(FieldNames.FIELD_OBJECT_ID));
			}
		}

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

	/**
	 * Node summary with {@code status} taken from the book's drift scan (falling back to a per-node
	 * recompute when the scan does not know the node), plus the scan's stale entry fields
	 * ({@code reason}, {@code driftedBindings}, {@code affectedSceneIndexes}, {@code markPersisted}) when
	 * the node is in it.
	 */
	private static Map<String, Object> nodeSummary(BaseRecord user, BaseRecord node, BaseRecord book, PbStaleUtil.Scan scan) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objectId", node.get(FieldNames.FIELD_OBJECT_ID));
		m.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		m.put("nodeType", enumString(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
		/// Whether POST .../node/{oid}/test has an executor for this type (PbNodeExecutor.EXECUTABLE_TYPES).
		/// The canvas only offers "Test" when this is true; otherwise the route answers 501.
		m.put("executable", Boolean.valueOf(PbNodeExecutor.isExecutable(node)));
		m.put("storedStatus", enumString(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		Long nodeId = node.get(FieldNames.FIELD_ID);
		PbNodeStatusEnumType status = (scan != null && nodeId != null ? scan.statuses.get(nodeId) : null);
		PbStaleUtil.Entry stale = (scan != null && nodeId != null ? scan.entries.get(nodeId) : null);
		if(stale != null) {
			/// The scan's view: a drifted or propagated node is STALE whatever the hash says.
			status = PbNodeStatusEnumType.STALE;
		}
		else if(status == null) {
			status = PbGraphUtil.recomputeStatus(user, node, book);
		}
		m.put("status", String.valueOf(status));
		if(stale != null) {
			m.put("reason", stale.reason);
			List<Map<String, Object>> drifted = new ArrayList<>();
			for(PbStaleUtil.DriftedRef d : stale.driftedBindings) {
				Map<String, Object> dm = new LinkedHashMap<>();
				dm.put("role", d.role);
				dm.put("refModel", d.refModel);
				dm.put("refObjectId", d.refObjectId);
				drifted.add(dm);
			}
			m.put("driftedBindings", drifted);
			m.put("affectedSceneIndexes", new ArrayList<>(stale.affectedSceneIndexes));
			m.put("markPersisted", Boolean.valueOf(stale.persisted));
		}
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
		/// The int columns carry DDL default 0 and are read without wasNull (StatementUtil), so "never
		/// placed" is 0 on the wire, not null; null only when the node was projected without them.
		m.put("canvasX", node.get(OlioFieldNames.FIELD_PB_CANVAS_X, null));
		m.put("canvasY", node.get(OlioFieldNames.FIELD_PB_CANVAS_Y, null));
		m.put("canvasW", node.get(OlioFieldNames.FIELD_PB_CANVAS_W, null));
		m.put("canvasH", node.get(OlioFieldNames.FIELD_PB_CANVAS_H, null));
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
		PbReviewStatusEnumType rs = PbArtifactUtil.reviewStatusOf(artifact);
		m.put("reviewStatus", rs.name());
		/// approvedAt is never NULL in the column (DDL default now(); epoch-0 on a bare newInstance) and a
		/// demoted/rejected artifact keeps its old stamp, so neither is emitted unless the status says APPROVED.
		if(rs == PbReviewStatusEnumType.APPROVED) {
			m.put("approvedBy", artifact.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			Object at = artifact.get(OlioFieldNames.FIELD_PB_APPROVED_AT);
			m.put("approvedAt", (at != null ? at.toString() : null));
		}
		Long duration = artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS);
		m.put("durationMs", (duration != null && duration.longValue() > 0 ? duration : null));
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
	 * The requesting user's story {@code olio.pb.book} records in their organisation, sorted by name, as
	 * lightweight DTOs — objectId, slug, name, bookStatus — to populate the PB2 book selector.
	 * <p>
	 * <b>Why not {@code ownerId}.</b> Every {@code olio.pb.book} row is owned uniformly by the OLIO
	 * PRINCIPAL ({@link PbBookUtil#createBook} writes the row as {@code olioUser}), so an {@code ownerId =
	 * user.id} condition can never match and this list was always empty — which is how a book whose
	 * extraction failed became invisible in the Ux while its row still blocked a same-name retry with 409.
	 * The creator is recorded on the row itself as {@code createdByObjectId}, so that is the candidate key.
	 * <p>
	 * Same shape as {@link #listSeriesBooks(BaseRecord, String)}: {@code createdByObjectId} + {@code
	 * organizationId} only <i>enumerate a bounded candidate set</i> via raw search, and each candidate is
	 * then read as the ACTING user through {@link PbBookUtil#readBook(BaseRecord, String, long)}
	 * ({@code AccessPoint.find} -> per-record {@code canRead}), which is the authorization boundary —
	 * {@code AccessPoint.list} authorizes only the query shape (model-api.md), and a book whose grants were
	 * never applied is dropped here rather than leaked. Books created by other users are not candidates;
	 * shared books reached through collaboration roles are opened by objectId directly.
	 * <p>
	 * {@code CHAPBOOK} rows are excluded: they belong to the ChapBook list ({@code ChapBookUtil.listChapBooks})
	 * and the PB2 selector routes each entry into the story workflow. The exclusion is a condition of the
	 * candidate query itself, not only the per-row skip below, so a user with more ChapBooks than
	 * {@link #LIST_BOOKS_CANDIDATE_CAP} still sees every story book (P3-8 run 5: 550 ChapBooks hid all of them).
	 */
	public static List<Map<String, Object>> listBooks(BaseRecord user) {
		return listBooks(user, LIST_BOOKS_CANDIDATE_CAP);
	}

	/** Upper bound on candidate rows enumerated per call; every chapter of a series is its own row. */
	static final int LIST_BOOKS_CANDIDATE_CAP = 400;

	static List<Map<String, Object>> listBooks(BaseRecord user, int candidateCap) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		long orgId = orgOf(user);
		String userObjectId = user.get(FieldNames.FIELD_OBJECT_ID);
		List<Map<String, Object>> out = new ArrayList<>();
		if(userObjectId == null) return out;

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, userObjectId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
		q.setCache(false);
		q.setValue(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_NAME);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		/// "NOT bookType = 'CHAPBOOK'" also drops a SQL-NULL bookType (IS_NULL is not implemented by StatementUtil).
		/// Measured 2026-10-18 on am7db and the Docker stack DB: no NULL rows; rows from before the column existed
		/// hold the literal UNKNOWN. A NULL row would be missing from this list, not mis-typed.
		q.field(OlioFieldNames.FIELD_PB_BOOK_TYPE, ComparatorEnumType.NOT_EQUALS, PbBookTypeEnumType.CHAPBOOK.toString());
		q.setRequestRange(0, candidateCap);
		BaseRecord[] candidates = IOSystem.getActiveContext().getSearch().findRecords(q);
		if(candidates == null) return out;

		/// Series rows are olio-principal-owned (see listSeriesBooks), so their titles are resolved as
		/// that principal — once per distinct series, not once per chapter.
		BaseRecord olioUser = null;
		Map<String, String> seriesTitles = new HashMap<>();
		for(BaseRecord cand : candidates) {
			String candObjectId = cand.get(FieldNames.FIELD_OBJECT_ID);
			if(candObjectId == null) continue;
			BaseRecord b = PbBookUtil.readBook(user, candObjectId, orgId);
			if(b == null) continue;
			if("CHAPBOOK".equalsIgnoreCase(enumString(b, OlioFieldNames.FIELD_PB_BOOK_TYPE))) continue;
			Map<String, Object> dto = new LinkedHashMap<>();
			dto.put("objectId", b.get(FieldNames.FIELD_OBJECT_ID));
			dto.put("name", b.get(FieldNames.FIELD_NAME));
			dto.put("slug", b.get(OlioFieldNames.FIELD_PB_SLUG));
			dto.put("title", b.get(FieldNames.FIELD_DESCRIPTION));
			dto.put("bookStatus", enumString(b, OlioFieldNames.FIELD_PB_BOOK_STATUS));
			dto.put("chapter", b.get(OlioFieldNames.FIELD_PB_CHAPTER));
			dto.put("sceneCount", PictureBookUtil.countScenesForBook(user, b));
			dto.put("worldObjectId", fkObjectId(b, OlioFieldNames.FIELD_PB_WORLD, null));
			String seriesOid = fkObjectId(b, OlioFieldNames.FIELD_PB_SERIES, null);
			dto.put("seriesObjectId", seriesOid);
			if(seriesOid != null) {
				if(!seriesTitles.containsKey(seriesOid)) {
					if(olioUser == null) {
						olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
					}
					BaseRecord series = (olioUser != null) ? PbSeriesUtil.readSeries(olioUser, seriesOid, orgId) : null;
					String st = (series != null) ? series.get(FieldNames.FIELD_DESCRIPTION) : null;
					if(st == null && series != null) st = series.get(FieldNames.FIELD_NAME);
					seriesTitles.put(seriesOid, st);
				}
				dto.put("seriesName", seriesTitles.get(seriesOid));
			}
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
			dto.put("sceneCount", PictureBookUtil.countScenesForBook(user, book));
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
	 * The objectId of {@code workflow.lastRun}, or null when no run has ever been submitted.
	 * <p>
	 * {@code PbGraphUtil.workflowRequest()} deliberately omits {@code lastRun} (one half of the
	 * workflow <-> run cycle), so the record {@code requireWorkflow} hands out never carries it and
	 * {@link #fkObjectId} on it would always be null. The pointer is re-read here, as the caller, with a
	 * projection that names {@code lastRun}; the nested run arrives with its common fields (which include
	 * {@code objectId}), and if a build ever trims that to the bare id the run is read by id instead.
	 */
	private static String lastRunObjectIdOf(BaseRecord user, BaseRecord workflow) {
		String wfOid = workflow.get(FieldNames.FIELD_OBJECT_ID);
		if(wfOid == null) {
			return null;
		}
		long orgId = (long) workflow.get(FieldNames.FIELD_ORGANIZATION_ID);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_WORKFLOW, FieldNames.FIELD_OBJECT_ID, wfOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_LAST_RUN });
		q.setCache(false);
		BaseRecord fresh = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(fresh == null || !fresh.hasField(OlioFieldNames.FIELD_PB_LAST_RUN)) {
			return null;
		}
		BaseRecord lastRun = fresh.get(OlioFieldNames.FIELD_PB_LAST_RUN);
		if(lastRun == null) {
			return null;
		}
		String oid = (lastRun.hasField(FieldNames.FIELD_OBJECT_ID) ? lastRun.get(FieldNames.FIELD_OBJECT_ID) : null);
		if(oid != null) {
			return oid;
		}
		Object id = (lastRun.hasField(FieldNames.FIELD_ID) ? lastRun.get(FieldNames.FIELD_ID) : null);
		if(!(id instanceof Number) || ((Number) id).longValue() <= 0L) {
			return null;
		}
		Query rq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, FieldNames.FIELD_ID, ((Number) id).longValue());
		rq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		rq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID });
		rq.setCache(false);
		BaseRecord run = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
		return (run != null ? run.get(FieldNames.FIELD_OBJECT_ID) : null);
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
			// V3 §7.2 / §26: the page's location pointer. sceneRequest() projects both FKs bare, which
			// materializes the sub-model's common fields (objectId, name) - no further read per page.
			BaseRecord pageLoc = scene.get(FieldNames.FIELD_LOCATION);
			BaseRecord pageVar = scene.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
			p.put("locationObjectId", (pageLoc != null ? pageLoc.get(FieldNames.FIELD_OBJECT_ID) : null));
			p.put("locationName", (pageLoc != null ? pageLoc.get(FieldNames.FIELD_NAME) : null));
			p.put("locationVariantObjectId", (pageVar != null ? pageVar.get(FieldNames.FIELD_OBJECT_ID) : null));
			p.put("locationVariantName", (pageVar != null ? pageVar.get(FieldNames.FIELD_NAME) : null));

			String dataObjectId = null;
			BaseRecord node = null;
			BaseRecord artifact = null;
			if(!nodeMap.isEmpty()) {
				BaseRecord sceneNodeRef = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
				if(sceneNodeRef != null) {
					Object nodeIdObj = sceneNodeRef.get(FieldNames.FIELD_ID);
					Long nodeId = (nodeIdObj instanceof Number) ? ((Number) nodeIdObj).longValue() : null;
					if(nodeId != null) {
						node = nodeMap.get(nodeId);
						if(node != null) {
							artifact = PbArtifactUtil.findSelected(user, node, pageArtifactRole(node));
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
			/// Phase-1 per-page controls (V3 P1-10): the node this page shows, its STORED status (no scan
			/// here - the page list must stay one read per page), and the selected artifact's review state.
			/// reviewStatusOf folds a null artifact to GENERATED, so "no artifact" is emitted as null here.
			p.put("sceneNodeObjectId", (node != null ? node.get(FieldNames.FIELD_OBJECT_ID) : null));
			p.put("nodeStatus", (node != null ? enumString(node, OlioFieldNames.FIELD_PB_NODE_STATUS) : null));
			p.put("pinned", Boolean.valueOf(node != null && Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED, null))));
			p.put("reviewStatus", (artifact != null ? PbArtifactUtil.reviewStatusOf(artifact).name() : null));
			p.put("artifactObjectId", (artifact != null ? artifact.get(FieldNames.FIELD_OBJECT_ID) : null));

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
	 * The artifact role a page shows for its {@code sceneNode}: a PictureBook scene's node is the
	 * COMPOSITE ({@code composite}); a ChapBook page's node (V3 P1-9) is a LANDSCAPE
	 * ({@link PbPipelineUtil#ROLE_LANDSCAPE}). Anything else keeps the historical default.
	 */
	static String pageArtifactRole(BaseRecord node) {
		PbNodeTypeEnumType t = (node != null && node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE)
			? node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE) : null);
		if(t == PbNodeTypeEnumType.LANDSCAPE) {
			return PbPipelineUtil.ROLE_LANDSCAPE;
		}
		return "composite";
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
	 * "Use this image" (V3 §10 rule 2): mark an artifact as the selected revision in its (node, role) chain.
	 * <p>
	 * Gate order: book 404 → {@link #requireBookUpdate} 403 → workflow 404 → artifact/cross-book 404 →
	 * {@code PbArtifactUtil.select}, which refuses with 409 when the chain's current selection is APPROVED
	 * (approve the other variant, or reject the approved one) and otherwise patches all siblings.
	 */
	public static Map<String, Object> selectArtifact(BaseRecord user, String bookObjectId, String artifactObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "select an artifact");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord artifact = requireArtifactOfBook(user, workflow, artifactObjectId);
		BaseRecord selected = PbArtifactUtil.select(user, artifact);
		return reviewResult(selected);
	}

	/**
	 * Approve (V3 §10 rule 2): the artifact ends selected, APPROVED with {@code approvedBy} = the caller's
	 * objectId, and its node pinned. Same gate order as {@link #selectArtifact}; the state machine is
	 * {@code PbArtifactUtil.approve}.
	 */
	public static Map<String, Object> approveArtifact(BaseRecord user, String bookObjectId, String artifactObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "approve an artifact");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord artifact = requireArtifactOfBook(user, workflow, artifactObjectId);
		requireSubjectInBookWorld(user, book, workflow, artifact);
		BaseRecord approved = PbArtifactUtil.approve(user, artifact, user.get(FieldNames.FIELD_OBJECT_ID));
		return reviewResult(approved);
	}

	/**
	 * Reject (V3 §10 rule 4): the artifact is marked REJECTED and kept; if it was the selection the newest
	 * non-rejected sibling takes over. The node's pin is not touched. Same gate order as
	 * {@link #selectArtifact}; the state machine is {@code PbArtifactUtil.reject}.
	 */
	public static Map<String, Object> rejectArtifact(BaseRecord user, String bookObjectId, String artifactObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "reject an artifact");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord artifact = requireArtifactOfBook(user, workflow, artifactObjectId);
		requireSubjectInBookWorld(user, book, workflow, artifact);
		BaseRecord rejected = PbArtifactUtil.reject(user, artifact);
		return reviewResult(rejected);
	}

	/** The post-action state of the artifact (its summary) plus the node's current pin. */
	private static Map<String, Object> reviewResult(BaseRecord artifact) {
		Map<String, Object> out = artifactSummary(artifact);
		BaseRecord producedBy = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		out.put("nodeObjectId", (producedBy != null ? producedBy.get(FieldNames.FIELD_OBJECT_ID) : null));
		return out;
	}

	/** Review-queue filters accepted by {@link #listReviewQueue}. */
	public static final String REVIEW_FILTER_ALL = "all";
	public static final String REVIEW_FILTER_AUTO_APPROVED = "auto-approved";

	/**
	 * The book's review queue: every artifact of every node, grouped by node, with its review state - what
	 * the Ux review view and the "Auto-approved" filter (§10 rule 5) read.
	 * <p>
	 * {@code filter} is {@code all} (default; GENERATED, IN_REVIEW, APPROVED and REJECTED - ARCHIVED is
	 * history and is left out) or {@code auto-approved} (only artifacts whose {@code approvedBy} is
	 * {@link PbArtifactUtil#APPROVED_BY_AUTO}); anything else is a 400 here, so REST and MCP share one
	 * validation. Nodes with no artifacts are omitted. One uncached chain read per (node, role). The
	 * {@code renderPlan} role is left out: a plan artifact is the record of what a render decided (§12),
	 * not an output anyone reviews, and a plan-driven node always carries one beside its image.
	 */
	public static Map<String, Object> listReviewQueue(BaseRecord user, String bookObjectId, String filter) {
		String f = (filter == null || filter.trim().length() == 0 ? REVIEW_FILTER_ALL : filter.trim().toLowerCase());
		if(!REVIEW_FILTER_ALL.equals(f) && !REVIEW_FILTER_AUTO_APPROVED.equals(f)) {
			/// The caller's value is deliberately not echoed (P1-4 security finding 4).
			throw new PictureBookException(400, "Unknown review filter - use "
				+ REVIEW_FILTER_ALL + " or " + REVIEW_FILTER_AUTO_APPROVED);
		}
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		BaseRecord workflow = requireWorkflow(user, book);

		List<Map<String, Object>> nodes = new ArrayList<>();
		int artifactCount = 0;
		int pendingCount = 0;
		for(BaseRecord node : PbGraphUtil.listNodes(user, workflow)) {
			List<Map<String, Object>> arts = new ArrayList<>();
			for(String role : artifactRoles(user, node)) {
				if(PbPipelineUtil.ROLE_RENDER_PLAN.equals(role)) {
					continue;
				}
				for(BaseRecord a : PbArtifactUtil.listChain(user, node, role)) {
					PbReviewStatusEnumType rs = PbArtifactUtil.reviewStatusOf(a);
					if(rs == PbReviewStatusEnumType.ARCHIVED) {
						continue;
					}
					if(REVIEW_FILTER_AUTO_APPROVED.equals(f)
							&& !(rs == PbReviewStatusEnumType.APPROVED
								&& PbArtifactUtil.APPROVED_BY_AUTO.equals(a.get(OlioFieldNames.FIELD_PB_APPROVED_BY)))) {
						continue;
					}
					arts.add(artifactSummary(a));
					artifactCount++;
					if(rs == PbReviewStatusEnumType.GENERATED || rs == PbReviewStatusEnumType.IN_REVIEW) {
						pendingCount++;
					}
				}
			}
			if(arts.isEmpty()) {
				continue;
			}
			Map<String, Object> n = new LinkedHashMap<>();
			n.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
			n.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
			n.put("nodeType", enumString(node, OlioFieldNames.FIELD_PB_NODE_TYPE));
			n.put("nodeStatus", enumString(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
			n.put("sceneIndex", node.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
			n.put("pinned", node.get(OlioFieldNames.FIELD_PB_PINNED));
			n.put("artifacts", arts);
			nodes.add(n);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("filter", f);
		out.put("autoApprove", autoApproveOf(book));
		out.put("artifactCount", Integer.valueOf(artifactCount));
		out.put("pendingCount", Integer.valueOf(pendingCount));
		out.put("nodes", nodes);
		return out;
	}

	// ─────────────────────────────── auto-approve (V3 §10 rule 5) ───────────────────────────────

	/** The book's {@code autoApprove} switch with the null-means-off rule (a pre-Phase-1 row reads SQL NULL, the DDL default is false). */
	private static Boolean autoApproveOf(BaseRecord book) {
		Boolean on = (book != null && book.hasField(OlioFieldNames.FIELD_PB_AUTO_APPROVE)
			? book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE) : null);
		return Boolean.valueOf(on != null && on.booleanValue());
	}

	private static Map<String, Object> autoApproveResult(BaseRecord book) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("autoApprove", autoApproveOf(book));
		return out;
	}

	/** Read the book's auto-approve switch: {@code {bookObjectId, autoApprove}}. Book 404 only - any reader of the book may see it. */
	public static Map<String, Object> getAutoApprove(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		return autoApproveResult(book);
	}

	/**
	 * Set the book's auto-approve switch (§10 rule 5) and return the re-read state. Gate order: book 404 →
	 * {@link #requireBookUpdate} 403 → {@code PbBookUtil.setAutoApprove} (500 when the patch is refused).
	 * Switching it off never un-approves anything; it governs future attempts only.
	 */
	public static Map<String, Object> setAutoApprove(BaseRecord user, String bookObjectId, boolean on) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "change auto-approve");
		if(!PbBookUtil.setAutoApprove(user, book, on)) {
			throw new PictureBookException(500, "Failed to persist autoApprove=" + on + " on book "
				+ book.get(FieldNames.FIELD_NAME) + " - AccessPoint.update returned null (PBAC denial or validation failure; check the audit log)");
		}
		BaseRecord fresh = PbBookUtil.readBook(user, bookObjectId, orgOf(user));
		return autoApproveResult(fresh != null ? fresh : book);
	}

	// ─────────────────────────────── style (V3 §4 / §4.1, P2-3) ───────────────────────────────

	/**
	 * The body keys {@code PUT /{book}/style} accepts: the §4.1 style-field set
	 * ({@link PbStyleResolver#STYLE_FIELDS}) plus {@code parent}. Never {@code name}, {@code scope} or
	 * {@code locked} - the first two are the book style's fixed identity ({@code <slug>-style}, BOOK) and the
	 * lock has its own route.
	 */
	public static final List<String> STYLE_PUT_FIELDS;
	static {
		List<String> f = new ArrayList<>(PbStyleResolver.STYLE_FIELDS);
		f.add(OlioFieldNames.FIELD_PB_PARENT);
		STYLE_PUT_FIELDS = Collections.unmodifiableList(f);
	}

	/**
	 * Read the book's style: its own {@code olio.pb.style} (null when it has none), the §4.1 effective
	 * values with the hop that supplied each, and the chain that was walked. Book 404 only - any reader of the
	 * book may see its look. Not gated on the render path: the one model policy (§16) applies the style's
	 * task pins to PB1 renders too, so a PB1 book's style must be readable and editable.
	 */
	public static Map<String, Object> getStyle(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		return styleDto(user, book, PbStyleResolver.resolve(user, book));
	}

	/**
	 * Create or update the book's own BOOK-scope style from a sparse body of {@link #STYLE_PUT_FIELDS} - the
	 * single write path for book defaults (§4.1 "no step writes to its parent"). Gate order: book 404 →
	 * {@link #requireBookUpdate} 403 → body validation 400 → lock 409 → write.
	 * <p>
	 * Body rules: unknown keys are a 400; a JSON {@code null} clears the field; {@code sdConfig} is
	 * {@code {"objectId"}} / {@code {"id"}} of an {@code olio.sd.config} the caller can read (404 otherwise);
	 * {@code parent} is the objectId of a readable style (404 otherwise) that is not the book's own style and
	 * whose chain does not lead back to it (400), and whose own chain plus this style fits in
	 * {@link PbStyleResolver#MAX_DEPTH}; {@code taskModels} is an object (or a JSON-object string) keyed by
	 * §16 task names, canonicalised through {@code ModelTaskKeys} - an unknown task or a non-string value is a
	 * 400; {@code pageFormat}/{@code qualityPreset} are enum names (case-insensitive); {@code customWidth}/
	 * {@code customHeight} are non-negative integers (0 = unset); {@code photoreal} is a boolean.
	 * <p>
	 * Lock: when any hop of the effective chain is locked - the book's own style or an inherited one - the
	 * write is refused with 409 naming that style, because the §4 table reads "Approved identity. Edits
	 * require unlock" and a nearer hop overriding a locked ancestor would defeat the lock. {@code parent} is
	 * covered by the same refusal (re-parenting is an edit).
	 * <p>
	 * Write: no {@code book.style} → a new style named {@code <slug>-style} in the book's own group
	 * ({@code book.groupId}, which {@code PbBookUtil.createBook} placed at {@code bookGroupPath(slug)}),
	 * {@code scope = BOOK}, created as the caller through {@code AccessPoint.create}, then the book FK patched
	 * with {@code PbGraphUtil.patchOf}; otherwise a patch of exactly the changed fields on the existing style.
	 * Every update result is asserted (500 on null). The workflow's cached scan is dropped afterwards so the
	 * next read re-hashes against the new style (156 g). Clearing {@code book.style} itself is not routed; it
	 * is the generic {@code PATCH /rest/model} seam (§10.1); the orphan it leaves behind is not re-adopted by
	 * the next PUT (see {@link #createOwnStyle}).
	 * <p>
	 * The read → resolve → create/patch → FK sequence runs under {@link #styleWriteLock} for the book, so two
	 * concurrent first PUTs cannot both create (the second sees the first's {@code book.style} and patches it)
	 * and a PUT cannot slip between a concurrent lock's check and its write. In-process only: the generic
	 * {@code PATCH /rest/model} seam and a second JVM are outside it (§6.C 161).
	 */
	public static Map<String, Object> putStyle(BaseRecord user, String bookObjectId, Map<String, Object> body) {
		synchronized(styleWriteLock(bookObjectId)) {
			return putStyleLocked(user, bookObjectId, body);
		}
	}

	/// Striped so the table is bounded; an unrelated book on the same stripe waits for one short write.
	private static final Object[] STYLE_WRITE_LOCKS = new Object[64];
	static {
		for(int i = 0; i < STYLE_WRITE_LOCKS.length; i++) {
			STYLE_WRITE_LOCKS[i] = new Object();
		}
	}

	private static Object styleWriteLock(String bookObjectId) {
		int h = (bookObjectId == null ? 0 : bookObjectId.hashCode());
		return STYLE_WRITE_LOCKS[Math.floorMod(h, STYLE_WRITE_LOCKS.length)];
	}

	private static Map<String, Object> putStyleLocked(BaseRecord user, String bookObjectId, Map<String, Object> body) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "edit the style");
		if(body == null || body.isEmpty()) {
			throw new PictureBookException(400, "A JSON object with at least one style field is required; accepted: " + STYLE_PUT_FIELDS);
		}
		for(String key : body.keySet()) {
			if(!STYLE_PUT_FIELDS.contains(key)) {
				throw new PictureBookException(400, "Unknown style field '" + key + "'; accepted: " + STYLE_PUT_FIELDS);
			}
		}
		long orgId = orgOf(user);
		Map<String, Object> values = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : body.entrySet()) {
			values.put(e.getKey(), styleValue(user, orgId, e.getKey(), e.getValue()));
		}

		PbStyleResolver.EffectiveStyle es = PbStyleResolver.resolve(user, book);
		BaseRecord own = ownStyle(book, es);
		BaseRecord lockedBy = es.lockedBy();
		if(lockedBy != null) {
			throw new PictureBookException(409, "Style '" + lockedBy.get(FieldNames.FIELD_NAME) + "' ("
				+ enumString(lockedBy, OlioFieldNames.FIELD_PB_SCOPE) + ", " + lockedBy.get(FieldNames.FIELD_OBJECT_ID)
				+ ") is locked - unlock it before editing" + (own != null && own == lockedBy ? " (POST /{book}/style/lock {\"locked\":false})" : ""));
		}
		if(values.containsKey(OlioFieldNames.FIELD_PB_PARENT)) {
			requireValidParent(user, own, (BaseRecord) values.get(OlioFieldNames.FIELD_PB_PARENT));
		}

		if(own == null) {
			createOwnStyle(user, book, orgId, values);
		}
		else {
			BaseRecord patch = PbGraphUtil.patchOf(own, OlioModelNames.MODEL_PB_STYLE, values.keySet().toArray(new String[0]));
			try {
				for(Map.Entry<String, Object> e : values.entrySet()) {
					patch.set(e.getKey(), e.getValue());
				}
			}
			catch(Exception e) {
				throw new PictureBookException(500, "Failed to assemble style patch: " + e.getMessage());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				throw new PictureBookException(500, "Failed to update style " + own.get(FieldNames.FIELD_NAME)
					+ " - AccessPoint.update returned null (PBAC denial or validation failure; check the audit log)");
			}
		}
		PbStaleUtil.touchBook(user, book);
		BaseRecord fresh = PbBookUtil.readBook(user, bookObjectId, orgId);
		if(fresh == null) {
			fresh = book;
		}
		return styleDto(user, fresh, PbStyleResolver.resolve(user, fresh));
	}

	/**
	 * Set or clear the lock on the book's OWN style (§4.1). Gate order: book 404 → {@link #requireBookUpdate}
	 * 403 → no own style 404 (an inherited style is locked from its own book or the library, never through a
	 * chapter) → patch of {@code locked} alone, result asserted. Unlocking changes no value, so nothing goes
	 * stale by itself; the first edit after it does, through the normal hash path. Runs under the book's
	 * {@link #styleWriteLock} like {@link #putStyle}.
	 */
	public static Map<String, Object> lockStyle(BaseRecord user, String bookObjectId, boolean locked) {
		synchronized(styleWriteLock(bookObjectId)) {
			return lockStyleLocked(user, bookObjectId, locked);
		}
	}

	private static Map<String, Object> lockStyleLocked(BaseRecord user, String bookObjectId, boolean locked) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "lock the style");
		PbStyleResolver.EffectiveStyle es = PbStyleResolver.resolve(user, book);
		BaseRecord own = ownStyle(book, es);
		if(own == null) {
			throw new PictureBookException(404, "The book has no style of its own to lock; PUT /{book}/style creates one");
		}
		BaseRecord patch = PbGraphUtil.patchOf(own, OlioModelNames.MODEL_PB_STYLE, OlioFieldNames.FIELD_PB_LOCKED);
		try {
			patch.set(OlioFieldNames.FIELD_PB_LOCKED, Boolean.valueOf(locked));
		}
		catch(Exception e) {
			throw new PictureBookException(500, "Failed to assemble lock patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to persist locked=" + locked + " on style " + own.get(FieldNames.FIELD_NAME)
				+ " - AccessPoint.update returned null (PBAC denial or validation failure; check the audit log)");
		}
		return styleDto(user, book, PbStyleResolver.resolve(user, book));
	}

	// ─── render plan (V3 §12-§15, §18; P2-4 plan inspector) ─────────────────────

	/**
	 * The RenderPlan for one scene as the caller would render it, plus the estimate and the plan's
	 * content hash. A READ: nothing is persisted and nothing is sent to the renderer. Body keys (all
	 * optional): {@code promptOverride}, {@code raw}, {@code preset} (DRAFT/STANDARD/FINAL), {@code model}.
	 * Gate order: book 401/400/404 → scene 400/404 → SD server unconfigured 500 → API type 500 → preset 400
	 * → style chain partial 409. The SD server URL is never part of the response.
	 */
	public static Map<String, Object> planScene(BaseRecord user, String bookObjectId, String sceneObjectId,
			Map<String, Object> body, String sdApiType, String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		BaseRecord scene = requireSceneOfBook(user, book, sceneObjectId);
		if(sdServer == null || sdServer.isBlank()) {
			throw new PictureBookException(500, "SD server is not configured");
		}
		SDAPIEnumType apiType;
		try {
			apiType = SDAPIEnumType.valueOf(sdApiType == null ? "" : sdApiType.trim().toUpperCase());
		}
		catch(IllegalArgumentException e) {
			logger.error("SD server API type '" + sdApiType + "' is not recognised");
			throw new PictureBookException(500, "SD server API type is not recognised");
		}
		Map<String, Object> in = (body != null ? body : Collections.emptyMap());
		Object presetIn = in.get("preset");
		PbQualityPresetEnumType preset = null;
		if(presetIn != null && !presetIn.toString().isBlank()) {
			for(PbQualityPresetEnumType p : PbQualityPresetEnumType.values()) {
				if(p != PbQualityPresetEnumType.UNKNOWN && p.name().equalsIgnoreCase(presetIn.toString().trim())) {
					preset = p;
					break;
				}
			}
			if(preset == null) {
				throw new PictureBookException(400, "Unknown quality preset '" + presetIn + "'");
			}
		}
		Object promptIn = in.get("promptOverride");
		Object modelIn = in.get("model");
		PbRenderPlanUtil.PlanOptions opts = new PbRenderPlanUtil.PlanOptions(
			promptIn == null || promptIn.toString().isBlank() ? null : promptIn.toString(),
			Boolean.TRUE.equals(in.get("raw")) || "true".equalsIgnoreCase(String.valueOf(in.get("raw"))),
			preset,
			modelIn == null || modelIn.toString().isBlank() ? null : modelIn.toString());

		RenderPlan plan = PbRenderPlanUtil.buildScenePlan(user, book, scene, opts, apiType, sdServer);
		Map<String, Object> out = new LinkedHashMap<>(plan.toMap());
		RenderPlan.ModelPick base = plan.pickForStage(RenderPlan.STAGE_BASE);
		out.put("estimate", RenderEstimate.estimate(plan, RenderCalibration.sample(sdServer, base != null ? base.getFamily() : null)));
		out.put("contentHash", plan.contentHash());
		return out;
	}

	/**
	 * The book's own style as the resolver read it: {@code book.style} matched against hop 0 of the chain.
	 * Null when the book names no style. A named style the resolver could not read (hop 0 is the truncation)
	 * is a 409, not "none": creating a second style over it would orphan the first and silently swap the
	 * book's look, and the chain the caller sees is already flagged {@code truncatedAt}.
	 */
	private static BaseRecord ownStyle(BaseRecord book, PbStyleResolver.EffectiveStyle es) {
		BaseRecord ref = (book.hasField(OlioFieldNames.FIELD_STYLE) ? book.get(OlioFieldNames.FIELD_STYLE) : null);
		String key = PbStyleResolver.identity(ref);
		if(key == null) {
			return null;
		}
		if(!es.chain().isEmpty()) {
			BaseRecord first = es.chain().get(0);
			if(key.equals(first.get(FieldNames.FIELD_OBJECT_ID)) || key.equals(PbStyleResolver.identity(first))) {
				return first;
			}
		}
		throw new PictureBookException(409, "The book's style " + key + " could not be read"
			+ (es.truncatedAt() != null ? " (chain truncated at " + es.truncatedAt() + ")" : "")
			+ "; repair or clear book.style before editing");
	}

	/** One validated PUT value: the Java value to set on the style record (null clears), or a 400/404. */
	private static Object styleValue(BaseRecord user, long orgId, String field, Object raw) {
		if(raw == null) {
			switch(field) {
				case OlioFieldNames.FIELD_PB_CUSTOM_WIDTH:
				case OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT:
					return Integer.valueOf(0);
				case OlioFieldNames.FIELD_PB_PHOTOREAL:
					return Boolean.FALSE;
				default:
					return null;
			}
		}
		switch(field) {
			case FieldNames.FIELD_DESCRIPTION:
			case OlioFieldNames.FIELD_PB_EXCLUSIONS:
			case OlioFieldNames.FIELD_PB_PALETTE:
				if(!(raw instanceof String)) {
					throw new PictureBookException(400, field + " must be a string");
				}
				return raw;
			case OlioFieldNames.FIELD_PB_PHOTOREAL:
				if(!(raw instanceof Boolean)) {
					throw new PictureBookException(400, field + " must be a JSON boolean");
				}
				return raw;
			case OlioFieldNames.FIELD_PB_CUSTOM_WIDTH:
			case OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT: {
				if(!(raw instanceof Number) || ((Number) raw).doubleValue() != Math.floor(((Number) raw).doubleValue())
					|| ((Number) raw).intValue() < 0) {
					throw new PictureBookException(400, field + " must be a non-negative integer (0 = unset)");
				}
				return Integer.valueOf(((Number) raw).intValue());
			}
			case OlioFieldNames.FIELD_PB_PAGE_FORMAT: {
				PbPageFormatEnumType v = PbStyleResolver.enumOf(PbPageFormatEnumType.class, raw);
				if(v == null) {
					throw new PictureBookException(400, field + " must be one of " + enumNames(PbPageFormatEnumType.values()));
				}
				return (v == PbPageFormatEnumType.UNKNOWN ? null : v.toString());
			}
			case OlioFieldNames.FIELD_PB_QUALITY_PRESET: {
				PbQualityPresetEnumType v = PbStyleResolver.enumOf(PbQualityPresetEnumType.class, raw);
				if(v == null) {
					throw new PictureBookException(400, field + " must be one of " + enumNames(PbQualityPresetEnumType.values()));
				}
				return (v == PbQualityPresetEnumType.UNKNOWN ? null : v.toString());
			}
			case OlioFieldNames.FIELD_PB_TASK_MODELS:
				return canonicalTaskModelsOf(raw);
			case OlioFieldNames.FIELD_PB_SD_CONFIG:
				return requireSdConfig(user, orgId, raw);
			case OlioFieldNames.FIELD_PB_PARENT: {
				String oid = refObjectId(raw);
				if(oid == null) {
					throw new PictureBookException(400, "parent must be a style objectId (string or {\"objectId\"}) or null");
				}
				BaseRecord parent = PbStyleUtil.readStyle(user, oid, orgId);
				if(parent == null) {
					throw new PictureBookException(404, "Parent style " + oid + " not found");
				}
				return parent;
			}
			default:
				throw new PictureBookException(400, "Unknown style field '" + field + "'");
		}
	}

	/** The objectId named by a JSON string or a {@code {"objectId": …}} object, else null. */
	private static String refObjectId(Object raw) {
		if(raw instanceof String) {
			String s = ((String) raw).trim();
			return (s.isEmpty() ? null : s);
		}
		if(raw instanceof Map) {
			Object o = ((Map<?, ?>) raw).get(FieldNames.FIELD_OBJECT_ID);
			return (o instanceof String && !((String) o).isBlank() ? ((String) o).trim() : null);
		}
		return null;
	}

	/**
	 * The {@code olio.sd.config} a PUT names by {@code {"objectId"}} or {@code {"id"}}, read as the caller
	 * (404 when absent or unreadable - a config the caller cannot read cannot become the book's default).
	 */
	private static BaseRecord requireSdConfig(BaseRecord user, long orgId, Object raw) {
		String oid = refObjectId(raw);
		Long id = null;
		if(oid == null && raw instanceof Map) {
			Object o = ((Map<?, ?>) raw).get(FieldNames.FIELD_ID);
			if(o instanceof Number && ((Number) o).longValue() > 0L) {
				id = Long.valueOf(((Number) o).longValue());
			}
		}
		if(oid == null && id == null) {
			throw new PictureBookException(400, "sdConfig must be {\"objectId\": …} or {\"id\": …} of an olio.sd.config, or null");
		}
		Query q = (oid != null
			? QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_OBJECT_ID, oid)
			: QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_ID, id));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
		q.setCache(false);
		BaseRecord cfg = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(cfg == null) {
			throw new PictureBookException(404, "sdConfig " + (oid != null ? oid : "#" + id) + " not found");
		}
		return cfg;
	}

	/**
	 * {@code taskModels} from a PUT - a JSON object, or a string holding one - as the canonical JSON the
	 * resolver stores. Every key must name a §16 task and every value must be a non-blank string; an empty
	 * object clears the field.
	 */
	private static String canonicalTaskModelsOf(Object raw) {
		Map<String, Object> map = null;
		if(raw instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> m = (Map<String, Object>) raw;
			map = m;
		}
		else if(raw instanceof String) {
			try {
				map = JSONUtil.getMap(((String) raw).getBytes(StandardCharsets.UTF_8), String.class, Object.class);
			}
			catch(Exception e) {
				map = null;
			}
			if(map == null) {
				throw new PictureBookException(400, "taskModels must be a JSON object keyed by task (" + taskKeyNames() + ")");
			}
		}
		else {
			throw new PictureBookException(400, "taskModels must be a JSON object keyed by task (" + taskKeyNames() + ") or null");
		}
		Map<PbModelTaskEnumType, String> tm = new HashMap<>();
		for(Map.Entry<String, Object> e : map.entrySet()) {
			PbModelTaskEnumType t = ModelTaskKeys.task(e.getKey());
			if(t == PbModelTaskEnumType.UNKNOWN) {
				throw new PictureBookException(400, "taskModels key '" + e.getKey() + "' is not a model task; accepted: " + taskKeyNames());
			}
			Object v = e.getValue();
			if(v == null) {
				continue;
			}
			if(!(v instanceof String) || ((String) v).isBlank()) {
				throw new PictureBookException(400, "taskModels." + e.getKey() + " must be a non-blank checkpoint name or null");
			}
			tm.put(t, ((String) v).trim());
		}
		return PbStyleResolver.canonicalTaskModels(tm);
	}

	private static String taskKeyNames() {
		List<String> keys = new ArrayList<>();
		for(PbModelTaskEnumType t : PbModelTaskEnumType.values()) {
			String k = ModelTaskKeys.key(t);
			if(k != null) {
				keys.add(k);
			}
		}
		return String.join(", ", keys);
	}

	private static String enumNames(Enum<?>[] values) {
		List<String> names = new ArrayList<>();
		for(Enum<?> e : values) {
			if(!"UNKNOWN".equals(e.name())) {
				names.add(e.name());
			}
		}
		return String.join(", ", names);
	}

	/**
	 * A new {@code parent} must not be the book's own style, must not lead back to it, and must leave the
	 * resulting chain (parent's complete chain + this style) within {@link PbStyleResolver#MAX_DEPTH}.
	 * {@code parent == null} (clearing) is always valid.
	 */
	private static void requireValidParent(BaseRecord user, BaseRecord own, BaseRecord parent) {
		if(parent == null) {
			return;
		}
		String ownKey = (own != null ? PbStyleResolver.identity(own) : null);
		if(ownKey != null && ownKey.equals(PbStyleResolver.identity(parent))) {
			throw new PictureBookException(400, "A style cannot be its own parent");
		}
		PbStyleResolver.EffectiveStyle pes = PbStyleResolver.resolveFromStyle(user, parent);
		if(!pes.isComplete()) {
			throw new PictureBookException(400, "Parent style " + parent.get(FieldNames.FIELD_OBJECT_ID) + " has an unresolvable chain ("
				+ (pes.cycleDetected() ? "cycle" : "truncated at " + pes.truncatedAt()) + ")");
		}
		if(ownKey != null) {
			for(BaseRecord hop : pes.chain()) {
				if(ownKey.equals(PbStyleResolver.identity(hop))) {
					throw new PictureBookException(400, "Parent style " + parent.get(FieldNames.FIELD_OBJECT_ID) + " already inherits from this book's style - that would be a cycle");
				}
			}
		}
		if(pes.chain().size() + 1 > PbStyleResolver.MAX_DEPTH) {
			throw new PictureBookException(400, "Parent style " + parent.get(FieldNames.FIELD_OBJECT_ID) + " would make the chain deeper than " + PbStyleResolver.MAX_DEPTH);
		}
	}

	/**
	 * The one authorized create: a BOOK-scope style named {@code <slug>-style} in the book's own group, as the
	 * caller, then the book FK. {@code applyOwnership} rather than {@code applyNameGroupOwnership} because the
	 * group already exists ({@code book.groupId}) and a facade write must not run a {@code makePath}.
	 * <p>
	 * {@code styleModel.json} constrains {@code (name, groupId, organizationId)}, and clearing {@code book.style}
	 * through the generic {@code PATCH /rest/model} seam (§10.1) leaves the previous own style in the group as an
	 * orphan under that very name. The orphan is never re-adopted (it may be locked, and the lock route can no
	 * longer reach it once the FK is gone), so when a readable style already holds {@code <slug>-style} the new
	 * one is named {@code <slug>-style-<8 hex>} instead (§6.C 160). The name check is a find-only read as the
	 * caller; the one write stays {@code AccessPoint.create}.
	 */
	private static void createOwnStyle(BaseRecord user, BaseRecord book, long orgId, Map<String, Object> values) {
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		Object groupId = book.get(FieldNames.FIELD_GROUP_ID);
		if(slug == null || slug.isBlank() || groupId == null) {
			throw new PictureBookException(500, "Book " + book.get(FieldNames.FIELD_NAME) + " carries no slug/groupId - read it through PbBookUtil.bookRequest()");
		}
		String name = slug + "-style";
		BaseRecord taken = PbStyleUtil.findStyleByName(user, name, ((Number) groupId).longValue(), orgId);
		if(taken != null) {
			name = name + "-" + UUID.randomUUID().toString().substring(0, 8);
			logger.info("Style '" + slug + "-style' already exists in the book's group as " + taken.get(FieldNames.FIELD_OBJECT_ID)
				+ " (an orphan of a cleared book.style) - creating '" + name + "' instead");
		}
		BaseRecord style = null;
		try {
			style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
			style.set(FieldNames.FIELD_NAME, name);
			style.set(FieldNames.FIELD_GROUP_ID, groupId);
			IOSystem.getActiveContext().getRecordUtil().applyOwnership(user, style, orgId);
			style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
			for(Map.Entry<String, Object> e : values.entrySet()) {
				style.set(e.getKey(), e.getValue());
			}
		}
		catch(Exception e) {
			throw new PictureBookException(500, "Failed to assemble the book style: " + e.getMessage());
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, style);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create style '" + name + "' in the book's group"
				+ " - AccessPoint.create returned null (PBAC denial, validation failure, or a style this user cannot read"
				+ " already holds that name in the group; check the audit log)");
		}
		String styleOid = created.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord fresh = PbStyleUtil.readStyle(user, styleOid, orgId);
		if(fresh == null) {
			throw new PictureBookException(500, "Style '" + name + "' was created as " + styleOid + " but cannot be read back");
		}
		BaseRecord patch = PbGraphUtil.patchOf(book, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_STYLE);
		try {
			patch.set(OlioFieldNames.FIELD_STYLE, fresh);
		}
		catch(Exception e) {
			throw new PictureBookException(500, "Failed to assemble the book.style patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Style " + styleOid + " was created but the book FK could not be written"
				+ " - AccessPoint.update returned null (PBAC denial or validation failure; check the audit log)");
		}
	}

	/**
	 * The style DTO: {@code {bookObjectId, own, effective:{fields, taskModels, qualityPreset, pageFormat,
	 * sources}, chain:[{objectId, name, scope, locked}], lockedBy, truncatedAt, truncatedModel, cycleDetected,
	 * complete}}. {@code own} is the book's own style with its stored (not effective) values, or null;
	 * {@code effective.sources} maps each resolved field to the objectId of the hop that supplied it, which is
	 * what a style editor needs to show "inherited from …". Reference images are not included - they are a
	 * participation list read on demand through {@code PbStyleUtil.listReferenceImages}.
	 */
	private static Map<String, Object> styleDto(BaseRecord user, BaseRecord book, PbStyleResolver.EffectiveStyle es) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord own = null;
		try {
			own = ownStyle(book, es);
		}
		catch(PictureBookException e) {
			/// Unreadable own style: reported through truncatedAt below, never as a read failure.
			own = null;
		}
		out.put("own", (own != null ? ownStyleDto(own) : null));

		Map<String, Object> effective = new LinkedHashMap<>();
		Map<String, Object> fields = new LinkedHashMap<>();
		Map<String, Object> sources = new LinkedHashMap<>();
		for(String f : PbStyleResolver.STYLE_FIELDS) {
			Object v;
			if(OlioFieldNames.FIELD_PB_SD_CONFIG.equals(f)) {
				BaseRecord cfg = es.sdConfig();
				v = (cfg != null ? cfg.get(FieldNames.FIELD_OBJECT_ID) : null);
			}
			else if(OlioFieldNames.FIELD_PB_TASK_MODELS.equals(f)) {
				continue;
			}
			else {
				v = es.value(f);
			}
			fields.put(f, v);
			BaseRecord src = es.source(f);
			if(src != null) {
				sources.put(f, src.get(FieldNames.FIELD_OBJECT_ID));
			}
		}
		effective.put("fields", fields);
		Map<String, Object> taskModels = new LinkedHashMap<>();
		Map<String, Object> taskModelSources = new LinkedHashMap<>();
		for(Map.Entry<PbModelTaskEnumType, String> e : es.taskModels().entrySet()) {
			String k = ModelTaskKeys.key(e.getKey());
			if(k == null) {
				continue;
			}
			taskModels.put(k, e.getValue());
			BaseRecord src = es.taskModelSource(e.getKey());
			if(src != null) {
				taskModelSources.put(k, src.get(FieldNames.FIELD_OBJECT_ID));
			}
		}
		effective.put("taskModels", taskModels);
		effective.put("taskModelSources", taskModelSources);
		effective.put("qualityPreset", es.qualityPreset().toString());
		effective.put("pageFormat", es.pageFormat().toString());
		BaseRecord cfg = es.sdConfig();
		effective.put("sdConfigName", (cfg != null ? cfg.get(FieldNames.FIELD_NAME) : null));
		effective.put("sources", sources);
		out.put("effective", effective);

		List<Map<String, Object>> chain = new ArrayList<>();
		for(BaseRecord s : es.chain()) {
			Map<String, Object> hop = new LinkedHashMap<>();
			hop.put("objectId", s.get(FieldNames.FIELD_OBJECT_ID));
			hop.put("name", s.get(FieldNames.FIELD_NAME));
			hop.put("scope", enumString(s, OlioFieldNames.FIELD_PB_SCOPE));
			hop.put("locked", Boolean.valueOf(s.hasField(OlioFieldNames.FIELD_PB_LOCKED) && Boolean.TRUE.equals(s.get(OlioFieldNames.FIELD_PB_LOCKED))));
			chain.add(hop);
		}
		out.put("chain", chain);
		out.put("lockedBy", (es.lockedBy() != null ? es.lockedBy().get(FieldNames.FIELD_OBJECT_ID) : null));
		out.put("truncatedAt", es.truncatedAt());
		out.put("truncatedModel", es.truncatedModel());
		out.put("cycleDetected", Boolean.valueOf(es.cycleDetected()));
		out.put("complete", Boolean.valueOf(es.isComplete()));
		return out;
	}

	/** The own style's identity, wiring and stored values - what a PUT edits, as distinct from what is in effect. */
	private static Map<String, Object> ownStyleDto(BaseRecord own) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("objectId", own.get(FieldNames.FIELD_OBJECT_ID));
		o.put("name", own.get(FieldNames.FIELD_NAME));
		o.put("scope", enumString(own, OlioFieldNames.FIELD_PB_SCOPE));
		o.put("locked", Boolean.valueOf(own.hasField(OlioFieldNames.FIELD_PB_LOCKED) && Boolean.TRUE.equals(own.get(OlioFieldNames.FIELD_PB_LOCKED))));
		o.put("parent", (own.hasField(OlioFieldNames.FIELD_PB_PARENT) ? fkObjectId(own, OlioFieldNames.FIELD_PB_PARENT, null) : null));
		Map<String, Object> fields = new LinkedHashMap<>();
		for(String f : PbStyleResolver.STYLE_FIELDS) {
			if(!own.hasField(f)) {
				fields.put(f, null);
				continue;
			}
			if(OlioFieldNames.FIELD_PB_SD_CONFIG.equals(f)) {
				fields.put(f, fkObjectId(own, f, null));
			}
			else if(OlioFieldNames.FIELD_PB_TASK_MODELS.equals(f)) {
				Map<String, Object> tm = new LinkedHashMap<>();
				for(Map.Entry<PbModelTaskEnumType, String> e : ModelTaskKeys.parseTaskModels((String) own.get(f)).entrySet()) {
					tm.put(ModelTaskKeys.key(e.getKey()), e.getValue());
				}
				fields.put(f, tm);
			}
			else if(OlioFieldNames.FIELD_PB_PAGE_FORMAT.equals(f) || OlioFieldNames.FIELD_PB_QUALITY_PRESET.equals(f)) {
				String s = enumString(own, f);
				fields.put(f, (s != null && !"UNKNOWN".equalsIgnoreCase(s) ? s.toUpperCase() : null));
			}
			else {
				fields.put(f, own.get(f));
			}
		}
		o.put("fields", fields);
		return o;
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
		/// The scan carries handles; canvas geometry (saveCanvas) it does not, so that path leaves it alone.
		PbGraphUtil.invalidateScan(workflow);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", nodeObjectId);
		out.put("handle", newHandle);
		out.put("name", newName);
		return out;
	}

	/**
	 * Execute a single node synchronously against the SD backend and persist a new artifact revision,
	 * <b>without</b> opening a run — the canvas "Test" action. Use {@link #requestRegenerate} when the
	 * execution should be a tracked {@code olio.pb.run}.
	 * <p>
	 * The SD backend is the {@code (sdApiType, sdServer)} pair the transport resolved from its deployment
	 * config ({@code sd.server.apiType} + {@code ServerConfigUtil.SERVER_SD} with the {@code sd.server}
	 * init-param as fallback — exactly what the v1 scene-generate route passes). Before P1-3 this method
	 * resolved the server itself with a null fallback, which silently ignored the init-param tier and
	 * assumed SWARM. A missing server is a 500 at the executor for the SD-backed types, before the graph
	 * is touched.
	 * <p>
	 * Only the types in {@link PbNodeExecutor#EXECUTABLE_TYPES} are implemented; any other node type is a
	 * 501 naming the supported types. The graph DTO's per-node {@code executable} flag tells the canvas
	 * which nodes to offer "Test" on, so the 501 is reachable only by a hand-built request.
	 * <p>
	 * Same {@code canUpdate} gate as {@link #requestRegenerate}: "Test" opens no run but still renders,
	 * writes an artifact and refreshes the character's narrative, so a reader of the book is refused (403)
	 * before any of that.
	 */
	public static Map<String, Object> testNode(BaseRecord user, String bookObjectId, String nodeObjectId,
			String sdApiType, String sdServer) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbRenderPath.requireV3(book);
		requireBookUpdate(user, book, "test a node");
		BaseRecord workflow = requireWorkflow(user, book);
		BaseRecord node = requireNodeOfBook(user, workflow, nodeObjectId);
		return PbNodeExecutor.executeNode(user, book, workflow, node, sdApiType, sdServer);
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
		PbGraphUtil.invalidateScan(workflow);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("deleted", Boolean.TRUE);
		out.put("bindingObjectId", bindingObjectId);
		return out;
	}

	// ─────────────────────────────── locations (V3 §7, §7.1, §10, §26; P3-4) ───────────────────────────────

	/**
	 * The book's location library (V3 §26 "Locations"): every {@code olio.pb.location} of the book's world
	 * with its variants, the scenes of <b>this</b> book that use it, and - when this book's workflow already
	 * carries a LOCATION node for it - that node's landscape chain. Read-only: nothing is materialized here
	 * (a LOCATION node is created only by a render or a proposal accept, never by a GET), and every read
	 * is the caller's own through {@code AccessPoint}, so a location the caller may not see is simply absent.
	 * <p>
	 * Book-scoped (404 book, 409 when the book has no resolvable world) rather than the bare
	 * {@code /location/{id}} of design §27: the book in the path is the compartment the caller addressed, and
	 * a location of a series world is reachable through any chapter that shares it.
	 *
	 * @return {@code bookObjectId}, {@code worldObjectId}, {@code locations:[locationDto]}
	 */
	public static Map<String, Object> listLocations(BaseRecord user, String bookObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BookContext bctx = PbLocationUtil.requireBookContext(user, book);
		BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
		List<BaseRecord> locations = PbLocationUtil.listLocations(user, bctx);
		Map<Long, List<BaseRecord>> variants = variantsByLocation(user, bctx);
		List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);

		List<Map<String, Object>> dtos = new ArrayList<>();
		for(BaseRecord loc : locations) {
			long locId = PbHealthUtil.idOf(loc);
			dtos.add(locationDto(user, workflow, loc, variants.getOrDefault(Long.valueOf(locId), Collections.emptyList()), scenes));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("worldObjectId", bctx.getWorld().get(FieldNames.FIELD_OBJECT_ID));
		out.put("locations", dtos);
		return out;
	}

	/** One location of the book's world, same shape as an entry of {@link #listLocations}; 404 when it is not in that world. */
	public static Map<String, Object> getLocation(BaseRecord user, String bookObjectId, String locationObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		BookContext bctx = PbLocationUtil.requireBookContext(user, book);
		BaseRecord loc = PbLocationUtil.requireLocationOfBook(user, book, locationObjectId);
		BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
		List<BaseRecord> variants = variantsByLocation(user, bctx).getOrDefault(Long.valueOf(PbHealthUtil.idOf(loc)), Collections.emptyList());
		return locationDto(user, workflow, loc, variants, PbBookUtil.listScenes(user, book));
	}

	/**
	 * Create (or resolve by name/alias) a location in the book's world (V3 §7). {@code kind} is parsed
	 * case-insensitively ({@code PbLocationKindEnumType}; null/blank is UNKNOWN, anything else unknown is a
	 * 400). The write is the olio principal's through {@code PbLocationUtil} after the caller's update grant
	 * on the book is proven; a name that already resolves returns the existing record untouched. No LOCATION
	 * node is materialized - the first render does that.
	 */
	public static Map<String, Object> createLocation(BaseRecord user, String bookObjectId, String name, String description,
			String kind, List<String> aliases) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "add a location");
		BaseRecord loc = PbLocationUtil.getCreateLocation(user, book, name, description, PbLocationUtil.parseKind(kind), aliases);
		return getLocation(user, bookObjectId, loc.get(FieldNames.FIELD_OBJECT_ID));
	}

	/**
	 * Edit {@code description} / {@code kind} / {@code aliases} of a location (the editable set; {@code name}
	 * is not editable - a rename is a merge, §7.1). 400 on any other key, 404 outside the book's world, 409
	 * while the location is locked.
	 */
	public static Map<String, Object> updateLocation(BaseRecord user, String bookObjectId, String locationObjectId,
			Map<String, Object> changes) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "edit a location");
		PbLocationUtil.updateLocation(user, book, locationObjectId, changes);
		return getLocation(user, bookObjectId, locationObjectId);
	}

	/**
	 * Delete a location, its variants, this book's LOCATION nodes for them and (in a book world) the POI +
	 * store the §7.2 link created. 409 while locked or while any scene - of any book sharing the world - still
	 * references it or a variant; the steps are returned as the delete audit.
	 */
	public static Map<String, Object> deleteLocation(BaseRecord user, String bookObjectId, String locationObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "delete a location");
		PictureBookUtil.DeleteResult dr = PbLocationUtil.deleteLocation(user, book, locationObjectId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("deleted", Boolean.valueOf(dr.deleted));
		out.put("locationObjectId", locationObjectId);
		if(dr.reason != null) {
			out.put("reason", dr.reason);
		}
		out.put("steps", dr.steps);
		return out;
	}

	/**
	 * Add a variant (time of day / weather / season / viewpoint / extra) to a location (V3 §7). At least one
	 * delta is required (400); the name defaults to {@code <location> - <deltas>}; a duplicate name is a 409,
	 * as is a locked location.
	 */
	public static Map<String, Object> createLocationVariant(BaseRecord user, String bookObjectId, String locationObjectId,
			String name, String timeOfDay, String weather, String season, String viewpoint, String extra) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "add a location variant");
		BaseRecord variant = PbLocationUtil.createVariant(user, book, locationObjectId, name, timeOfDay, weather, season, viewpoint, extra);
		BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
		Map<String, Object> out = variantDto(user, workflow, variant, PbBookUtil.listScenes(user, book));
		out.put("locationObjectId", locationObjectId);
		return out;
	}

	/**
	 * Lock or unlock a location (V3 §10, "whole asset"): while locked, edits, approving or clearing the
	 * approved render of the location or any variant, new renders, new variants, delete and merge are all
	 * refused with 409. Locking itself is never refused by the lock - this is the one way out. Facade-only:
	 * generic {@code PATCH /rest/model} does not consult the flag.
	 */
	public static Map<String, Object> lockLocation(BaseRecord user, String bookObjectId, String locationObjectId, boolean locked) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, (locked ? "lock" : "unlock") + " a location");
		BaseRecord loc = PbLocationUtil.setLocked(user, book, locationObjectId, locked);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("locationObjectId", loc.get(FieldNames.FIELD_OBJECT_ID));
		out.put("locked", Boolean.valueOf(PbLocationUtil.isLocked(loc)));
		return out;
	}

	/**
	 * Submit an asynchronous render of one location or variant (V3 §7, §21; design §27
	 * {@code POST .../location/{id}/render?variant=}). The subject's LOCATION node is materialized on first
	 * use (and, for a location, seeded with the selected landscape of every scene of this book that uses it -
	 * unapproved, so the reviewer picks), then handed to {@link PbRenderJobUtil#submit} as the one terminal.
	 * <p>
	 * Gate order: 404 book / location / variant (a location of another world is a 404), 403 unless the caller
	 * may update the book, 409 while the location is locked or its node is pinned, then the planner's own
	 * 409 / 429 / 500. <b>No {@code renderPath} check</b>: the LOCATION node is V3-only machinery with no PB1
	 * counterpart, and a PictureBook still rendering its scenes on {@code v1} must be able to build its
	 * library (the §30 exit criterion "scenes 3/7/12 share one approved tavern").
	 *
	 * @param variantObjectId null for the location itself
	 * @return {@code locationObjectId}, {@code variantObjectId}, {@code nodeObjectId}, {@code seeded} /
	 *         {@code seedSkipped} when the node was just materialized, then {@code jobId}, {@code runObjectId}
	 *         and the plan summary
	 */
	public static Map<String, Object> requestLocationRender(BaseRecord user, String bookObjectId, String locationObjectId,
			String variantObjectId, String sdApiType, String sdServer, boolean force) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "render a location");
		BaseRecord[] subject = requireLocationSubject(user, book, locationObjectId, variantObjectId);
		PbLocationUtil.requireUnlocked(subject[0], "render it");
		BaseRecord workflow = ensureWorkflow(user, book);
		Map<String, Object> out = new LinkedHashMap<>();
		BaseRecord node = ensureLocationNode(user, book, workflow, subject[0], subject[1], out);
		requireNotPinned(node);
		Long nodeId = node.get(FieldNames.FIELD_ID);
		out.putAll(PbRenderJobUtil.submit(user, book, workflow, List.of(nodeId), sdApiType, sdServer,
			PbRenderJobUtil.KIND_RENDER, force));
		return out;
	}

	/**
	 * Synchronous counterpart of {@link #requestLocationRender} over an {@code SDUtil} the caller built (test
	 * seam, the {@link #requestRenderPlanScene(BaseRecord, String, String, org.cote.accountmanager.olio.sd.SDUtil)}
	 * shape): same gates, same materialization, then {@link PbNodeExecutor#executeNodeRun} under the in-flight
	 * claim so a concurrent job cannot double-render the node.
	 */
	static Map<String, Object> renderLocation(BaseRecord user, String bookObjectId, String locationObjectId,
			String variantObjectId, org.cote.accountmanager.olio.sd.SDUtil sdu) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "render a location");
		BaseRecord[] subject = requireLocationSubject(user, book, locationObjectId, variantObjectId);
		PbLocationUtil.requireUnlocked(subject[0], "render it");
		BaseRecord workflow = ensureWorkflow(user, book);
		Map<String, Object> out = new LinkedHashMap<>();
		BaseRecord node = ensureLocationNode(user, book, workflow, subject[0], subject[1], out);
		requireNotPinned(node);

		List<Long> nodeIds = List.of((Long) node.get(FieldNames.FIELD_ID));
		String claimKey = "sync:" + UUID.randomUUID();
		Long conflict = PbNodeInflight.tryClaim(nodeIds, claimKey);
		if(conflict != null) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is being rendered by run " + PbNodeInflight.runOf(conflict.longValue()));
		}
		try {
			out.putAll(PbNodeExecutor.executeNodeRun(user, book, workflow, node, sdu));
			out.put("executed", Boolean.TRUE);
			return out;
		}
		finally {
			PbNodeInflight.release(nodeIds, claimKey);
		}
	}

	/**
	 * Run the whole-book location pass (V3 §7.1, §28 step 3) on demand: every scene's setting is clustered
	 * by the LLM into {@code olio.pb.locationProposal} rows for the reviewer. Synchronous and LLM-bound
	 * (one call per 40 scenes); a hard LLM failure is reported on the result ({@code hardFailure}), never
	 * thrown. {@code chatConfigName} null means the book's resolved config.
	 */
	public static Map<String, Object> proposeLocations(BaseRecord user, String bookObjectId, String chatConfigName) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "propose locations");
		PbLocationUtil.LocationPassResult r = PbLocationUtil.proposeLocations(user, book, chatConfigName);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.putAll(locationPassDto(r));
		return out;
	}

	/**
	 * The book's location proposals, newest pass first as stored; {@code status} filters case-insensitively
	 * on {@code PbProposalStatusEnumType} (null = all; unknown = 400).
	 */
	public static Map<String, Object> listLocationProposals(BaseRecord user, String bookObjectId, String status) {
		BaseRecord book = requireBook(user, bookObjectId);
		PbProposalStatusEnumType st = null;
		if(status != null && !status.isBlank()) {
			for(PbProposalStatusEnumType s : PbProposalStatusEnumType.values()) {
				if(s.name().equalsIgnoreCase(status.trim())) {
					st = s;
				}
			}
			if(st == null) {
				throw new PictureBookException(400, "Unknown proposal status; one of proposed, accepted, rejected");
			}
		}
		List<Map<String, Object>> dtos = new ArrayList<>();
		for(BaseRecord p : PbLocationUtil.listProposals(user, book, st)) {
			dtos.add(proposalDto(p));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bookObjectId", book.get(FieldNames.FIELD_OBJECT_ID));
		out.put("proposals", dtos);
		return out;
	}

	/**
	 * Accept a proposal (V3 §7.1): resolve or create the location, re-point the proposal's scenes
	 * ({@code overwriteSceneLocation} also moves scenes that already point elsewhere), fold aliases, and -
	 * when this book already has a workflow - materialize the location's LOCATION node, seeded from the
	 * scenes' selected landscapes. A book with no workflow yet gets its node at the first render instead.
	 * 404 proposal not in this book, 409 unless it is PROPOSED.
	 */
	public static Map<String, Object> acceptLocationProposal(BaseRecord user, String bookObjectId, String proposalObjectId,
			boolean overwriteSceneLocation) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "accept a location proposal");
		BaseRecord proposal = requireProposalOfBook(user, book, proposalObjectId);
		PbLocationUtil.ProposalAcceptResult r = PbLocationUtil.acceptProposal(user, book, proposal, overwriteSceneLocation);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("proposal", (r.proposal != null ? proposalDto(r.proposal) : null));
		out.put("locationObjectId", (r.location != null ? r.location.get(FieldNames.FIELD_OBJECT_ID) : null));
		out.put("locationCreated", Boolean.valueOf(r.locationCreated));
		out.put("scenesRepointed", Integer.valueOf(r.scenesRepointed));
		out.put("scenesSkipped", Integer.valueOf(r.scenesSkipped));
		out.put("nodesMarkedStale", Integer.valueOf(r.nodesMarkedStale));
		out.put("aliasesFolded", Integer.valueOf(r.aliasesFolded));
		out.put("warnings", stripRaw(r.warnings));
		BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
		if(workflow != null && r.location != null && !PbLocationUtil.isLocked(r.location)) {
			BaseRecord node = ensureLocationNode(user, book, workflow, r.location, null, out);
			out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		}
		return out;
	}

	/** Reject a proposal with an optional reason; 404 not in this book, 409 unless PROPOSED. */
	public static Map<String, Object> rejectLocationProposal(BaseRecord user, String bookObjectId, String proposalObjectId, String reason) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "reject a location proposal");
		BaseRecord proposal = requireProposalOfBook(user, book, proposalObjectId);
		BaseRecord rejected = PbLocationUtil.rejectProposal(user, book, proposal, reason);
		return proposalDto(rejected != null ? rejected : proposal);
	}

	/**
	 * Merge {@code duplicate} into {@code survivor} (V3 §7.1 "sameAs"): scenes, variants and graph bindings
	 * re-pointed, aliases folded, the duplicate (and its orphan POI) deleted. 404 either side outside the
	 * world, 400 same record, 409 while either is locked, 403 when a scene re-point is not permitted.
	 */
	public static Map<String, Object> mergeLocations(BaseRecord user, String bookObjectId, String duplicateObjectId,
			String survivorObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "merge locations");
		BaseRecord dup = PbLocationUtil.requireLocationOfBook(user, book, duplicateObjectId);
		BaseRecord sur = PbLocationUtil.requireLocationOfBook(user, book, survivorObjectId);
		if(!PbLocationUtil.mergeLocations(user, book, dup, sur)) {
			throw new PictureBookException(403, "Not permitted to merge these locations");
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("merged", Boolean.TRUE);
		out.put("duplicateObjectId", duplicateObjectId);
		out.put("survivorObjectId", survivorObjectId);
		out.put("survivor", getLocation(user, bookObjectId, survivorObjectId));
		return out;
	}

	/**
	 * Point a scene or ChapBook page of {@code book} at a library location / variant, or clear the pointer
	 * (V3 §7.2, §24 P3-6 as-built, §26; design §27 {@code PUT /{book}/scene/{scene}/location}). The one
	 * pointing path with a staleness event: {@link PbBookUtil#setSceneLocation} patches the two FKs as the
	 * caller and then marks the scene's node STALE, which generic {@code PATCH /rest/model} cannot do.
	 * <p>
	 * Gate order: 404 book, 403 unless the caller may update the book, 404 scene outside the book, then the
	 * subject through {@link #requireLocationSubject} - a location of another world is a 404 (D4), a variant
	 * under a different location than the one named is a 404; naming only the variant resolves its parent.
	 * Both ids blank clears both pointers. A pinned node is <b>not</b> a conflict: the pointer is scene
	 * data, and a pin only stops the runner from replacing the node's artifact ({@code PbGraphUtil#setPinned}),
	 * so the scene is re-pointed, the node is marked STALE like any other, and {@code pinned:true} is echoed
	 * so the Ux can say "unpin to re-render". A RUNNING node is left alone by the mark ({@code nodeStale:false}).
	 *
	 * @return {@code sceneObjectId}, {@code locationObjectId} / {@code locationName} / {@code variantObjectId} /
	 *         {@code variantName} as persisted (null when cleared), {@code updated:true}, {@code nodeStale},
	 *         {@code pinned} and {@code nodeStatus} (null when the scene has no node)
	 */
	public static Map<String, Object> setSceneLocation(BaseRecord user, String bookObjectId, String sceneObjectId,
		String locationObjectId, String variantObjectId) {
		BaseRecord book = requireBook(user, bookObjectId);
		requireBookUpdate(user, book, "point a scene at a location");
		BaseRecord scene = requireSceneOfBook(user, book, sceneObjectId);
		BaseRecord location = null;
		BaseRecord variant = null;
		boolean hasLocation = (locationObjectId != null && !locationObjectId.isBlank());
		boolean hasVariant = (variantObjectId != null && !variantObjectId.isBlank());
		if(hasLocation || hasVariant) {
			BaseRecord[] subject = requireLocationSubject(user, book, (hasLocation ? locationObjectId.trim() : null),
				(hasVariant ? variantObjectId.trim() : null));
			location = subject[0];
			variant = subject[1];
		}
		boolean nodeStale = PbBookUtil.setSceneLocation(user, scene, location, variant);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sceneObjectId", scene.get(FieldNames.FIELD_OBJECT_ID));
		out.put("locationObjectId", (location != null ? location.get(FieldNames.FIELD_OBJECT_ID) : null));
		out.put("locationName", (location != null ? location.get(FieldNames.FIELD_NAME) : null));
		out.put("variantObjectId", (variant != null ? variant.get(FieldNames.FIELD_OBJECT_ID) : null));
		out.put("variantName", (variant != null ? variant.get(FieldNames.FIELD_NAME) : null));
		out.put("updated", Boolean.TRUE);
		out.put("nodeStale", Boolean.valueOf(nodeStale));
		BaseRecord nodeRef = scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
		Long nodeId = (nodeRef != null ? nodeRef.get(FieldNames.FIELD_ID) : null);
		BaseRecord node = (nodeId != null && nodeId.longValue() > 0L ? PbGraphUtil.readNodeById(user, nodeId.longValue(), orgOf(user)) : null);
		out.put("pinned", Boolean.valueOf(node != null && Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED, null))));
		out.put("nodeStatus", (node != null ? enumString(node, OlioFieldNames.FIELD_PB_NODE_STATUS) : null));
		return out;
	}

	/** {@code [location, variant|null]} of the book's world, or the util's 404. */
	private static BaseRecord[] requireLocationSubject(BaseRecord user, BaseRecord book, String locationObjectId, String variantObjectId) {
		if(variantObjectId != null && !variantObjectId.isBlank()) {
			BaseRecord[] parent = new BaseRecord[1];
			BaseRecord variant = PbLocationUtil.requireVariantOfBook(user, book, variantObjectId, parent);
			if(locationObjectId != null && !locationObjectId.isBlank()
				&& !locationObjectId.trim().equals(parent[0].get(FieldNames.FIELD_OBJECT_ID))) {
				throw new PictureBookException(404, "Location variant not found under that location");
			}
			return new BaseRecord[] { parent[0], variant };
		}
		return new BaseRecord[] { PbLocationUtil.requireLocationOfBook(user, book, locationObjectId), null };
	}

	private static void requireNotPinned(BaseRecord node) {
		Boolean pinned = node.get(OlioFieldNames.FIELD_PB_PINNED);
		if(pinned != null && pinned.booleanValue()) {
			throw new PictureBookException(409, "Node " + node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " is pinned. Unpin it first - pinning means do not replace this artifact.");
		}
	}

	private static BaseRecord requireProposalOfBook(BaseRecord user, BaseRecord book, String proposalObjectId) {
		if(proposalObjectId == null || proposalObjectId.isBlank()) {
			throw new PictureBookException(400, "A proposal objectId is required");
		}
		BaseRecord p = PbLocationUtil.readProposal(user, proposalObjectId, PbGraphUtil.orgId(book));
		if(p == null || PbHealthUtil.fkId(p, OlioFieldNames.FIELD_PB_BOOK) != PbHealthUtil.idOf(book)) {
			throw new PictureBookException(404, "Proposal not found in this book");
		}
		return p;
	}

	/**
	 * The LOCATION node of this book's workflow for {@code location} (or {@code variant}), created on first use
	 * - the {@link #ensureRenderPlanNode} shape: found by the deterministic handle
	 * ({@link PbPipelineUtil#locationHandle} / {@link PbPipelineUtil#locationVariantHandle}), else added with
	 * {@code scope=location|locationVariant}, {@code scopeRef=} the record objectId and ordinal 0.
	 * <p>
	 * <b>First materialization of a location node seeds its landscape chain</b> (design §28 step 3): the
	 * selected {@code landscape} artifact of every scene of this book that already points at the location is
	 * copied in as a candidate - same bytes, new artifact row on the LOCATION node, provenance in
	 * {@code refModel=olio.pb.artifact} / {@code refObjectId=<source>} - unapproved and with the node left
	 * PENDING, so a plain render still runs and the reviewer may approve a seeded candidate instead. Variant
	 * nodes are not seeded (a variant's render is a delta the scene landscapes never carried). A scene whose
	 * landscape cannot be read or copied is skipped and counted, never fatal. {@code out} receives
	 * {@code nodeObjectId}, {@code nodeMaterialized}, and {@code seeded} / {@code seedSkipped} on creation.
	 */
	private static BaseRecord ensureLocationNode(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord location,
			BaseRecord variant, Map<String, Object> out) {
		String locOid = location.get(FieldNames.FIELD_OBJECT_ID);
		String subjectOid = (variant != null ? variant.get(FieldNames.FIELD_OBJECT_ID) : locOid);
		String scope = (variant != null ? PbPipelineUtil.SCOPE_LOCATION_VARIANT : PbPipelineUtil.SCOPE_LOCATION);
		String handle = (variant != null ? PbPipelineUtil.locationVariantHandle(subjectOid) : PbPipelineUtil.locationHandle(locOid));
		BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, handle);
		boolean created = false;
		if(node == null) {
			String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
			node = PbGraphUtil.addNode(user, workflow, handle, PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(slug), 0);
			if(node == null) {
				throw new PictureBookException(500, "Failed to create the location node " + handle);
			}
			if(!PbPipelineUtil.patchNodeScope(user, workflow, node, scope, subjectOid, 0)) {
				throw new PictureBookException(500, "Failed to record the location scope on node " + handle);
			}
			created = true;
		}
		BaseRecord fresh = PbGraphUtil.readNode(user, node.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(node));
		node = (fresh != null ? fresh : node);
		if(out != null) {
			out.put("locationObjectId", locOid);
			out.put("variantObjectId", (variant != null ? subjectOid : null));
			out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
			out.put("nodeMaterialized", Boolean.valueOf(created));
		}
		if(created && variant == null) {
			int[] seeded = seedLocationNode(user, book, workflow, location, node);
			if(out != null) {
				out.put("seeded", Integer.valueOf(seeded[0]));
				out.put("seedSkipped", Integer.valueOf(seeded[1]));
			}
		}
		return node;
	}

	/** @return {@code {seeded, skipped}} */
	private static int[] seedLocationNode(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord location, BaseRecord node) {
		int seeded = 0;
		int skipped = 0;
		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String groupPath = PbBookUtil.artifactGroupPath(slug);
		for(BaseRecord scene : PbLocationUtil.scenesOfBookReferencing(user, book, location)) {
			String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
			try {
				BaseRecord landscapeNode = PbPipelineUtil.findNodeByHandle(user, workflow, PbPipelineUtil.landscapeHandle(sceneOid));
				BaseRecord source = (landscapeNode != null ? PbArtifactUtil.findSelected(user, landscapeNode, PbPipelineUtil.ROLE_LANDSCAPE) : null);
				BaseRecord data = (source != null ? source.get(OlioFieldNames.FIELD_PB_DATA) : null);
				if(data == null || PbHealthUtil.idOf(data) <= 0L) {
					skipped++;
					continue;
				}
				BaseRecord copy = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_LANDSCAPE,
					PbArtifactTypeEnumType.IMAGE, groupPath, data, null, null, null);
				/// Only the metrics the source actually carries - a present-but-null field in the patch writes NULL.
				List<String> carried = new ArrayList<>();
				carried.add(OlioFieldNames.FIELD_PB_REF_MODEL);
				carried.add(OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
				for(String f : new String[] { OlioFieldNames.FIELD_PB_CONTENT_HASH, OlioFieldNames.FIELD_PB_MIME_TYPE,
						OlioFieldNames.FIELD_PB_IMAGE_WIDTH, OlioFieldNames.FIELD_PB_IMAGE_HEIGHT,
						OlioFieldNames.FIELD_PB_BYTE_LENGTH, OlioFieldNames.FIELD_PB_SEED }) {
					if(source.hasField(f) && source.get(f) != null) {
						carried.add(f);
					}
				}
				BaseRecord patch = PbGraphUtil.patchOf(copy, OlioModelNames.MODEL_PB_ARTIFACT, carried.toArray(new String[0]));
				patch.set(OlioFieldNames.FIELD_PB_REF_MODEL, OlioModelNames.MODEL_PB_ARTIFACT);
				patch.set(OlioFieldNames.FIELD_PB_REF_OBJECT_ID, source.get(FieldNames.FIELD_OBJECT_ID));
				for(String f : carried.subList(2, carried.size())) {
					patch.set(f, source.get(f));
				}
				if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
					logger.warn("Seeded artifact " + copy.get(FieldNames.FIELD_NAME) + " kept without provenance: patch refused");
				}
				seeded++;
			}
			catch(PictureBookException | FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("Location node " + node.get(OlioFieldNames.FIELD_PB_HANDLE) + ": landscape of scene " + sceneOid
					+ " not seeded: " + e.getMessage());
				skipped++;
			}
		}
		return new int[] { seeded, skipped };
	}

	/** Every variant of the world's Locations group, read as the caller, keyed by parent location id. */
	private static Map<Long, List<BaseRecord>> variantsByLocation(BaseRecord user, BookContext bctx) {
		Map<Long, List<BaseRecord>> byLoc = new HashMap<>();
		BaseRecord grp = bctx.getGroup(FieldNames.FIELD_LOCATIONS);
		if(grp == null) {
			return byLoc;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION_VARIANT, FieldNames.FIELD_GROUP_ID, (long) grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, bctx.getOrganizationId());
		q.setRequest(PbBookUtil.locationVariantRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		if(recs == null) {
			return byLoc;
		}
		for(BaseRecord v : PbGraphUtil.restoreSchema(new ArrayList<>(List.of(recs)), OlioModelNames.MODEL_PB_LOCATION_VARIANT)) {
			long locId = PbHealthUtil.fkId(v, FieldNames.FIELD_LOCATION);
			byLoc.computeIfAbsent(Long.valueOf(locId), k -> new ArrayList<>()).add(v);
		}
		return byLoc;
	}

	private static Map<String, Object> locationDto(BaseRecord user, BaseRecord workflow, BaseRecord loc, List<BaseRecord> variants,
			List<BaseRecord> scenes) {
		Map<String, Object> m = new LinkedHashMap<>();
		String oid = loc.get(FieldNames.FIELD_OBJECT_ID);
		m.put("objectId", oid);
		m.put("name", loc.get(FieldNames.FIELD_NAME));
		m.put("description", loc.get(FieldNames.FIELD_DESCRIPTION));
		m.put("kind", enumString(loc, OlioFieldNames.FIELD_PB_KIND));
		List<String> aliases = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
		m.put("aliases", (aliases != null ? new ArrayList<>(aliases) : new ArrayList<>()));
		m.put("locked", Boolean.valueOf(PbLocationUtil.isLocked(loc)));
		m.put("approvedRenderObjectId", fkObjectId(loc, OlioFieldNames.FIELD_PB_APPROVED_RENDER, null));
		m.put("poiObjectId", fkObjectId(loc, OlioFieldNames.FIELD_PB_POI, null));
		m.put("worldObjectId", fkObjectId(loc, OlioFieldNames.FIELD_PB_WORLD, null));
		long locId = PbHealthUtil.idOf(loc);
		List<Map<String, Object>> used = new ArrayList<>();
		for(BaseRecord s : scenes) {
			if(PbHealthUtil.fkId(s, FieldNames.FIELD_LOCATION) == locId) {
				Map<String, Object> u = new LinkedHashMap<>();
				u.put("sceneObjectId", s.get(FieldNames.FIELD_OBJECT_ID));
				u.put("sceneIndex", s.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
				u.put("variantObjectId", fkObjectId(s, OlioFieldNames.FIELD_PB_LOCATION_VARIANT, null));
				used.add(u);
			}
		}
		m.put("usedByScenes", used);
		m.put("node", locationNodeDto(user, workflow, PbPipelineUtil.locationHandle(oid)));
		List<Map<String, Object>> vs = new ArrayList<>();
		for(BaseRecord v : variants) {
			vs.add(variantDto(user, workflow, v, scenes));
		}
		m.put("variants", vs);
		return m;
	}

	private static Map<String, Object> variantDto(BaseRecord user, BaseRecord workflow, BaseRecord v, List<BaseRecord> scenes) {
		Map<String, Object> m = new LinkedHashMap<>();
		String oid = v.get(FieldNames.FIELD_OBJECT_ID);
		m.put("objectId", oid);
		m.put("name", v.get(FieldNames.FIELD_NAME));
		m.put("locationObjectId", fkObjectId(v, FieldNames.FIELD_LOCATION, null));
		m.put("timeOfDay", v.get(OlioFieldNames.FIELD_PB_TIME_OF_DAY));
		m.put("weather", v.get(OlioFieldNames.FIELD_PB_WEATHER));
		m.put("season", v.get(OlioFieldNames.FIELD_PB_SEASON));
		m.put("viewpoint", v.get(OlioFieldNames.FIELD_PB_VIEWPOINT));
		m.put("extra", v.get(OlioFieldNames.FIELD_PB_EXTRA));
		m.put("approvedRenderObjectId", fkObjectId(v, OlioFieldNames.FIELD_PB_APPROVED_RENDER, null));
		long vid = PbHealthUtil.idOf(v);
		List<Map<String, Object>> used = new ArrayList<>();
		for(BaseRecord s : scenes) {
			if(PbHealthUtil.fkId(s, OlioFieldNames.FIELD_PB_LOCATION_VARIANT) == vid) {
				Map<String, Object> u = new LinkedHashMap<>();
				u.put("sceneObjectId", s.get(FieldNames.FIELD_OBJECT_ID));
				u.put("sceneIndex", s.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
				used.add(u);
			}
		}
		m.put("usedByScenes", used);
		m.put("node", locationNodeDto(user, workflow, PbPipelineUtil.locationVariantHandle(oid)));
		return m;
	}

	/**
	 * This book's LOCATION node for a handle with its landscape chain, or null when none has been materialized
	 * (nothing is created here). Stored status only - no drift scan, the canvas view does that.
	 */
	private static Map<String, Object> locationNodeDto(BaseRecord user, BaseRecord workflow, String handle) {
		if(workflow == null) {
			return null;
		}
		BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, handle);
		if(node == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objectId", node.get(FieldNames.FIELD_OBJECT_ID));
		m.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		m.put("storedStatus", enumString(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		m.put("pinned", node.get(OlioFieldNames.FIELD_PB_PINNED));
		m.put("lastError", node.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
		List<Map<String, Object>> chain = new ArrayList<>();
		for(BaseRecord a : PbArtifactUtil.listChain(user, node, PbPipelineUtil.ROLE_LANDSCAPE)) {
			Map<String, Object> am = artifactSummary(a);
			am.put("refModel", a.get(OlioFieldNames.FIELD_PB_REF_MODEL));
			am.put("refObjectId", a.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			chain.add(am);
		}
		m.put("chain", chain);
		return m;
	}

	private static Map<String, Object> proposalDto(BaseRecord p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objectId", p.get(FieldNames.FIELD_OBJECT_ID));
		m.put("name", p.get(FieldNames.FIELD_NAME));
		m.put("proposedName", p.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME));
		m.put("description", p.get(FieldNames.FIELD_DESCRIPTION));
		m.put("kind", enumString(p, OlioFieldNames.FIELD_PB_KIND));
		List<String> aliases = p.get(OlioFieldNames.FIELD_PB_ALIASES);
		m.put("aliases", (aliases != null ? new ArrayList<>(aliases) : new ArrayList<>()));
		List<String> sceneIds = p.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
		m.put("sceneObjectIds", (sceneIds != null ? new ArrayList<>(sceneIds) : new ArrayList<>()));
		m.put("sameAs", p.get(OlioFieldNames.FIELD_PB_SAME_AS));
		m.put("proposalStatus", PbLocationUtil.statusOf(p).name());
		m.put("locationObjectId", fkObjectId(p, FieldNames.FIELD_LOCATION, null));
		m.put("reason", p.get(OlioFieldNames.FIELD_PB_REASON));
		m.put("passRun", p.get(OlioFieldNames.FIELD_PB_PASS_RUN));
		return m;
	}

	public static Map<String, Object> locationPassDto(PbLocationUtil.LocationPassResult r) {
		Map<String, Object> m = new LinkedHashMap<>();
		if(r == null) {
			return m;
		}
		m.put("passRun", r.passRun);
		m.put("scenesConsidered", Integer.valueOf(r.scenesConsidered));
		m.put("scenesSkipped", Integer.valueOf(r.scenesSkipped));
		m.put("chunks", Integer.valueOf(r.chunks));
		m.put("chunksParsed", Integer.valueOf(r.chunksParsed));
		m.put("folded", Integer.valueOf(r.folded));
		m.put("proposed", Integer.valueOf(r.proposed));
		m.put("updated", Integer.valueOf(r.updated));
		m.put("settled", Integer.valueOf(r.settled));
		m.put("retired", Integer.valueOf(r.retired));
		m.put("hardFailure", Boolean.valueOf(r.hardFailure));
		m.put("warnings", stripRaw(r.warnings));
		List<Map<String, Object>> dtos = new ArrayList<>();
		for(BaseRecord p : r.proposals) {
			dtos.add(proposalDto(p));
		}
		m.put("proposals", dtos);
		return m;
	}

	/**
	 * Warnings for the wire: an extraction-failure entry ({@code {context, error, rawResponse, failedAt}} JSON
	 * from {@code PictureBookUtil.recordFailedExtraction}) loses its {@code rawResponse} - the whole LLM reply,
	 * which can be kilobytes of the source text - and keeps its length. Plain-text warnings pass through.
	 */
	public static List<String> stripRaw(List<String> warnings) {
		List<String> out = new ArrayList<>();
		if(warnings == null) {
			return out;
		}
		for(String w : warnings) {
			if(w == null) {
				continue;
			}
			String t = w.trim();
			if(t.startsWith("{") && t.contains("rawResponse")) {
				try {
					Map<String, Object> m = JSONUtil.getMap(t.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
					if(m != null && m.containsKey("rawResponse")) {
						Object raw = m.remove("rawResponse");
						m.put("rawResponseLength", Integer.valueOf(raw != null ? raw.toString().length() : 0));
						out.add(JSONUtil.exportObject(m));
						continue;
					}
				}
				catch(Exception e) {
					/// Not JSON after all - pass the text through as it is.
				}
			}
			out.add(w);
		}
		return out;
	}

	// ─────────────────────────────── health check + self-heal ───────────────────────────────

	/**
	 * Read-only health report for every book the caller created in their organization plus the
	 * organization-level checks (checkpoints, prompt templates, orphan counts). Creates nothing.
	 */
	public static Map<String, Object> orgHealth(BaseRecord user) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		return PbHealthUtil.checkOrg(user);
	}

	/**
	 * Repair the organization view: every healable finding on the caller's books that the caller may update,
	 * then the organization-level findings. {@code codes} null means every healable code;
	 * {@code overwriteTemplates} is the explicit opt-in for overwriting drifted prompt templates.
	 */
	public static Map<String, Object> healOrg(BaseRecord user, String dataPath, Set<String> codes, boolean overwriteTemplates) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		return PbHealthUtil.healOrg(user, dataPath, codes, overwriteTemplates);
	}

	/**
	 * Read-only health report for one book; 404 when the caller cannot read it. {@code PbHealthUtil} reads
	 * the book through {@code PbBookUtil.readBook} itself, so it is not re-read here.
	 */
	public static Map<String, Object> bookHealth(BaseRecord user, String bookObjectId) {
		return PbHealthUtil.checkBook(user, bookObjectId);
	}

	/**
	 * Repair one book; 404 when the caller cannot read it, 403 when the caller may not update it. Prompt
	 * templates are organization-level, so there is no {@code overwriteTemplates} at book scope - see
	 * {@link #healOrg}.
	 */
	public static Map<String, Object> healBook(BaseRecord user, String dataPath, String bookObjectId, Set<String> codes) {
		return PbHealthUtil.healBook(user, dataPath, bookObjectId, codes);
	}

	// ─────────────────────────────── orphan cleanup ───────────────────────────────

	/**
	 * Dry run: the stray PictureBook records the caller may remove. {@code orgWide} widens the scan to the
	 * whole organization and is refused (403) unless the caller is in AccountAdministrators.
	 */
	public static Map<String, Object> scanOrphans(BaseRecord user, boolean orgWide) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		return PbOrphanUtil.scan(user, orgWide);
	}

	/**
	 * Remove the orphans a fresh scan reports, optionally limited to {@code codes}. Same scope rule and 403
	 * as {@link #scanOrphans}.
	 */
	public static Map<String, Object> purgeOrphans(BaseRecord user, boolean orgWide, List<String> codes) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		return PbOrphanUtil.purge(user, orgWide, codes);
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
