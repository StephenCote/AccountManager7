package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbGraphStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;

/**
 * The PictureBook 2 workflow graph: build, cycle check, input hashing, dirty propagation, status
 * repair, and run scheduling.
 * <p>
 * <b>Two representations of staleness, one authoritative (§2.3).</b> {@code node.inputHash} is truth:
 * a node is stale iff {@link #computeInputHash} differs from the stored value. {@code node.nodeStatus}
 * is a denormalized, indexed cache so "show me the stale nodes" is one query instead of a graph walk.
 * <b>Nothing in this class treats {@code nodeStatus} as a correctness input.</b>
 * <p>
 * <b>{@link #recomputeStatus} COMPUTES AND RETURNS. It does not write</b> (ratification 2). §2.3 had it
 * writing {@code nodeStatus} while being "invoked on opening a book's workflow view", which is either a
 * privileged write triggered by any reader or a caller-owned write failing silently into a discarded
 * update result - the {@code LibraryUtil} shape {@code architecture.md} warns about. Persisting is
 * {@link #persistStatus}, an explicit, authorized, result-checked write. The split is safe precisely
 * because the hash is truth and the status is a repairable cache, so nothing depends on the cache being
 * written during a read.
 * <p>
 * <b>Cycles need their own guard.</b> {@code HierarchyValidator.checkHierarchy} only walks
 * {@code parentId} chains, and a PB2 graph's edges are {@code binding.sourceNode -> binding.node}. So
 * {@link #validateAcyclic} is an explicit DFS with a three-colour map, and it is called <b>before</b> a
 * binding is persisted, including the pending edge - checking after the write would leave a cyclic graph
 * in the database with nothing able to plan a query over it.
 * <p>
 * <b>Never {@code planMost(true)} on {@code olio.pb.workflow} or {@code olio.pb.run}.</b> The mutual
 * reference {@code workflow.lastRun <-> run.workflow} is a two-hop cycle;
 * {@code QueryPlan.checkRecursion} only catches an immediate parent/child match and
 * {@code maximumDepth} only logs. Use {@link #FULL_PLAN_FILTER} with
 * {@code planMost(true, filter)}, or {@code planCommon}.
 */
public class PbGraphUtil {
	public static final Logger logger = LogManager.getLogger(PbGraphUtil.class);

	/**
	 * Folded into every {@code inputHash}. Bump it when the pipeline's semantics change in a way that
	 * makes existing outputs wrong even though every input is unchanged.
	 * <p>
	 * <b>Bumping this marks every node in every book stale</b> (§10). That is the point, and it is why
	 * {@link #computeInputHash} logs the version at DEBUG and why this constant carries a comment rather
	 * than being quietly edited.
	 */
	public static final String PB_PIPELINE_VERSION = "pb2/1";

	/**
	 * Fields excluded from a {@code planMost(true)} on the two models that carry the
	 * {@code workflow.lastRun <-> run.workflow} cycle. Pass to {@code Query.planMost(true, filter)}.
	 */
	public static final List<String> FULL_PLAN_FILTER = Collections.unmodifiableList(Arrays.asList(
		OlioFieldNames.FIELD_PB_LAST_RUN, OlioFieldNames.FIELD_PB_WORKFLOW
	));

	/** DFS colours for {@link #validateAcyclic}. GREY on the stack, BLACK finished. */
	private enum Colour { WHITE, GREY, BLACK }

	private PbGraphUtil() {
		/// static utility
	}

	// ─────────────────────────────── build ───────────────────────────────

	/**
	 * The one workflow for {@code book}, or null.
	 * <p>
	 * Find-only, and deliberately so: a read path that creates the workflow would be the
	 * {@code LibraryUtil} shape. {@link #getCreateWorkflow} is the authorized write path.
	 */
	public static BaseRecord findWorkflow(BaseRecord user, BaseRecord book) {
		if(user == null || book == null) {
			return null;
		}
		Long bookId = book.get(FieldNames.FIELD_ID);
		if(bookId == null || bookId.longValue() <= 0L) {
			return null;
		}
		/// A condition on a FOREIGN MODEL field takes the RECORD, never its id: Query.field routes the
		/// value through FieldUtil.setFlex, which calls setModel() for a MODEL-typed field, so a Long is
		/// rejected and the condition silently becomes "book = null" - it matches nothing and logs
		/// nothing at the call site. StatementUtil casts the value to BaseRecord and reads its id
		/// (StatementUtil.java:1367). Measured on am7db 2026-08-15.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_WORKFLOW, OlioFieldNames.FIELD_PB_BOOK, book);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(book));
		q.setRequest(workflowRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Create the book's single workflow, or return the existing one.
	 * <p>
	 * A write path, so it is the caller's job to be authorized - which {@code AccessPoint.create}
	 * enforces. The unique {@code (book, organizationId)} constraint is what makes "one per book" true
	 * rather than merely intended: a second racer's create fails on the index.
	 * <p>
	 * <b>The name is set explicitly and is derived.</b> {@code applyNameGroupOwnership} does NOT set
	 * {@code name} on these models (it gates on {@code common.name}), a null name defeats the unique
	 * {@code (name, groupId, organizationId)} constraint because PostgreSQL treats NULLs as distinct, and
	 * that constraint is ratification 8's urn-collision guard.
	 *
	 * @param groupPath the world's {@code Workflow} group path
	 */
	public static BaseRecord getCreateWorkflow(BaseRecord user, BaseRecord book, String groupPath) {
		BaseRecord existing = findWorkflow(user, book);
		if(existing != null) {
			return existing;
		}
		String name = workflowName(book);
		BaseRecord wf = null;
		try {
			wf = RecordFactory.newInstance(OlioModelNames.MODEL_PB_WORKFLOW);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, wf, name, groupPath, orgId(book));
			wf.set(FieldNames.FIELD_NAME, name);
			wf.set(OlioFieldNames.FIELD_PB_BOOK, book);
			wf.set(OlioFieldNames.FIELD_PB_GRAPH_VERSION, Integer.valueOf(1));
			wf.set(OlioFieldNames.FIELD_PB_GRAPH_STATUS, PbGraphStatusEnumType.DIRTY.toString());
			wf.set(OlioFieldNames.FIELD_PB_NODE_COUNT, Integer.valueOf(0));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a workflow: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble a workflow for book " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, wf);
		if(created == null) {
			/// Could be the unique (book, organizationId) index rejecting a racer, in which case the
			/// other racer's row is now readable - re-probe before reporting a failure.
			BaseRecord raced = findWorkflow(user, book);
			if(raced != null) {
				logger.warn("Lost a workflow create race for book " + name + "; using the persisted workflow");
				return raced;
			}
			throw new PictureBookException(500, "Failed to create a workflow for book " + name);
		}
		return findWorkflow(user, book);
	}

	/**
	 * Add a node to {@code workflow}.
	 * <p>
	 * The name is <b>derived from {@code handle}</b> and must be unique within the group: ratification 8
	 * requires it because {@code UrnProvider} composes the urn from {@code name}, not {@code handle}, and
	 * {@code common.urn} carries no uniqueness constraint of its own. The unique
	 * {@code (name, groupId, organizationId)} index is what actually catches a collision.
	 */
	public static BaseRecord addNode(BaseRecord user, BaseRecord workflow, String handle, PbNodeTypeEnumType nodeType,
			String groupPath, int ordinal) {
		if(handle == null || handle.trim().length() == 0) {
			throw new PictureBookException(400, "A node handle is required - the node's unique name is derived from it");
		}
		String name = nodeName(handle);
		BaseRecord node = null;
		try {
			node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, node, name, groupPath, orgId(workflow));
			node.set(FieldNames.FIELD_NAME, name);
			node.set(OlioFieldNames.FIELD_PB_HANDLE, handle);
			node.set(OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
			node.set(OlioFieldNames.FIELD_PB_NODE_TYPE, (nodeType != null ? nodeType : PbNodeTypeEnumType.UNKNOWN).toString());
			/// PENDING, never STALE: inputHash is null until the first successful run, and "stale iff
			/// recompute != inputHash" would otherwise mark every brand-new node stale (§2.3's carve-out).
			node.set(OlioFieldNames.FIELD_PB_NODE_STATUS, PbNodeStatusEnumType.PENDING.toString());
			node.set(OlioFieldNames.FIELD_PB_ORDINAL, Integer.valueOf(ordinal));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a node: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble node " + handle);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, node);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create node " + handle
				+ " - a duplicate handle in the same group is the usual cause, since the derived name is unique");
		}
		return readNode(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId(workflow));
	}

	/**
	 * Add an edge.
	 * <p>
	 * <b>{@link #validateAcyclic} runs FIRST, with the pending edge included</b>, and this method throws
	 * {@code PictureBookException(400, ..)} rather than persisting a cycle. Checking afterwards would
	 * leave the cycle in the database.
	 * <p>
	 * The name is derived from {@code role + bindingOrdinal} (ratification 8) and scoped by the consuming
	 * node's handle, so two nodes may each carry a {@code portrait0/0} binding.
	 *
	 * @param sourceNode the producer, or null for an external root
	 * @param sourceArtifact the exact artifact revision consumed, or null
	 */
	public static BaseRecord addBinding(BaseRecord user, BaseRecord workflow, BaseRecord node, String role,
			int bindingOrdinal, BaseRecord sourceNode, BaseRecord sourceArtifact, String groupPath) {
		if(node == null) {
			throw new PictureBookException(400, "A binding needs a consuming node");
		}
		if(role == null || role.trim().length() == 0) {
			throw new PictureBookException(400, "A binding needs a role - its unique name is derived from role + bindingOrdinal");
		}
		/// BEFORE the write, including the edge that does not exist yet.
		validateAcyclic(user, workflow, node, sourceNode);

		String name = bindingName(node, role, bindingOrdinal);
		BaseRecord binding = null;
		try {
			binding = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BINDING);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, binding, name, groupPath, orgId(workflow));
			binding.set(FieldNames.FIELD_NAME, name);
			binding.set(OlioFieldNames.FIELD_PB_NODE, node);
			binding.set(OlioFieldNames.FIELD_PB_ROLE, role);
			binding.set(OlioFieldNames.FIELD_PB_BINDING_ORDINAL, Integer.valueOf(bindingOrdinal));
			if(sourceNode != null) {
				binding.set(OlioFieldNames.FIELD_PB_SOURCE_NODE, sourceNode);
			}
			if(sourceArtifact != null) {
				binding.set(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT, sourceArtifact);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a binding: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble binding " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, binding);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create binding " + name
				+ " - the unique (node, role, bindingOrdinal, organizationId) index rejects a duplicate edge");
		}
		return readBinding(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId(workflow));
	}

	/**
	 * Bind an ordinary AM7 record (a character, a garment, a source document) rather than an artifact,
	 * capturing its {@code refHash} <b>at bind time</b> - the mechanism that lets a later edit to that
	 * record be detected (see {@link PbWatchedFields}).
	 */
	public static BaseRecord addRecordBinding(BaseRecord user, BaseRecord workflow, BaseRecord node, String role,
			int bindingOrdinal, String refModel, String refObjectId, String groupPath) {
		BaseRecord binding = addBinding(user, workflow, node, role, bindingOrdinal, null, null, groupPath);
		String refHash = PbWatchedFields.computeRefHash(user, refModel, refObjectId);
		BaseRecord patch = patchOf(binding, OlioModelNames.MODEL_PB_BINDING,
			OlioFieldNames.FIELD_PB_REF_MODEL, OlioFieldNames.FIELD_PB_REF_OBJECT_ID,
			OlioFieldNames.FIELD_PB_REF_HASH);
		try {
			patch.set(OlioFieldNames.FIELD_PB_REF_MODEL, refModel);
			patch.set(OlioFieldNames.FIELD_PB_REF_OBJECT_ID, refObjectId);
			patch.set(OlioFieldNames.FIELD_PB_REF_HASH, refHash);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a binding patch: " + e.getMessage());
		}
		/// Never discard an update result: a false/null here is the ONLY signal that the write failed,
		/// and swallowing it turns a persistent failure into a silent no-op.
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to record the reference on binding " + binding.get(FieldNames.FIELD_NAME));
		}
		return readBinding(user, binding.get(FieldNames.FIELD_OBJECT_ID), orgId(workflow));
	}

	// ─────────────────────────────── cycle check ───────────────────────────────

	/**
	 * Reject a cyclic graph. Explicit DFS over {@code sourceNode -> node} edges with a three-colour map;
	 * a GREY node reached again is a back edge, i.e. a cycle.
	 * <p>
	 * Called with the <b>pending</b> edge so a binding that would close a cycle is refused before it is
	 * written. A self-edge ({@code pendingSource == pendingConsumer}) is a cycle of length one and is
	 * refused by the same walk.
	 *
	 * @param pendingConsumer the node the caller is about to bind an input to, or null for a check of the
	 *        persisted graph alone
	 * @param pendingSource the producer of that pending input, or null
	 * @throws PictureBookException 400 naming the cycle
	 */
	public static void validateAcyclic(BaseRecord user, BaseRecord workflow, BaseRecord pendingConsumer, BaseRecord pendingSource) {
		if(workflow == null) {
			throw new PictureBookException(400, "A cycle check needs a workflow");
		}
		Map<Long, List<Long>> adjacency = adjacency(user, workflow);
		Map<Long, String> labels = nodeLabels(user, workflow);

		if(pendingConsumer != null && pendingSource != null) {
			Long from = pendingSource.get(FieldNames.FIELD_ID);
			Long to = pendingConsumer.get(FieldNames.FIELD_ID);
			if(from != null && to != null) {
				adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
				labels.putIfAbsent(from, "#" + from);
				labels.putIfAbsent(to, "#" + to);
			}
		}

		Map<Long, Colour> colour = new HashMap<>();
		for(Long id : new ArrayList<>(adjacency.keySet())) {
			colour.putIfAbsent(id, Colour.WHITE);
		}
		for(List<Long> tos : adjacency.values()) {
			for(Long to : tos) {
				colour.putIfAbsent(to, Colour.WHITE);
			}
		}
		for(Long id : colour.keySet()) {
			if(colour.get(id) == Colour.WHITE) {
				List<Long> stack = new ArrayList<>();
				Long cycleAt = visit(id, adjacency, colour, stack);
				if(cycleAt != null) {
					throw new PictureBookException(400, "The binding would create a cycle in workflow "
						+ workflow.get(FieldNames.FIELD_NAME) + ": " + describeCycle(stack, cycleAt, labels));
				}
			}
		}
	}

	/** Iterative DFS so a deep graph cannot overflow the stack. Returns the node a back edge reaches. */
	private static Long visit(Long start, Map<Long, List<Long>> adjacency, Map<Long, Colour> colour, List<Long> path) {
		Deque<Long> stack = new ArrayDeque<>();
		stack.push(start);
		while(!stack.isEmpty()) {
			Long id = stack.peek();
			if(colour.get(id) == Colour.WHITE) {
				colour.put(id, Colour.GREY);
				path.add(id);
				for(Long to : adjacency.getOrDefault(id, Collections.emptyList())) {
					Colour c = colour.getOrDefault(to, Colour.WHITE);
					if(c == Colour.GREY) {
						return to;
					}
					if(c == Colour.WHITE) {
						stack.push(to);
					}
				}
			}
			else if(colour.get(id) == Colour.GREY && stack.peek().equals(id)) {
				/// Every descendant is finished only once this frame is reached again with nothing pushed
				boolean pending = false;
				for(Long to : adjacency.getOrDefault(id, Collections.emptyList())) {
					if(colour.getOrDefault(to, Colour.WHITE) == Colour.WHITE) {
						pending = true;
						stack.push(to);
					}
				}
				if(!pending) {
					colour.put(id, Colour.BLACK);
					if(!path.isEmpty() && path.get(path.size() - 1).equals(id)) {
						path.remove(path.size() - 1);
					}
					stack.pop();
				}
			}
			else {
				stack.pop();
			}
		}
		return null;
	}

	private static String describeCycle(List<Long> path, Long cycleAt, Map<Long, String> labels) {
		StringBuilder sb = new StringBuilder();
		int from = path.indexOf(cycleAt);
		for(int i = (from >= 0 ? from : 0); i < path.size(); i++) {
			sb.append(labels.getOrDefault(path.get(i), "#" + path.get(i))).append(" -> ");
		}
		sb.append(labels.getOrDefault(cycleAt, "#" + cycleAt));
		return sb.toString();
	}

	// ─────────────────────────────── input hashing ───────────────────────────────

	/**
	 * The authoritative staleness value: a stable SHA-256 over the node's resolved inputs.
	 * <p>
	 * <b>SHA-256 is named here, at the call site, and the string is encoded as explicit UTF-8</b>, via
	 * {@link PbConfigUtil#sha256Hex(String)}. {@code CryptoUtil} cannot be used: its
	 * {@code defaultHashAlgorithm} is a mutable static currently on SHA-512, and
	 * {@code getDigestAsString(String)} encodes with the platform default charset - so a hash taken
	 * through it is stable neither across hosts nor against another caller reassigning the static.
	 * <p>
	 * Canonical form, in fixed order (§2.3):
	 * <ol>
	 * <li>{@link #PB_PIPELINE_VERSION};</li>
	 * <li>{@code nodeType};</li>
	 * <li>each binding, <b>sorted by {@code (role, bindingOrdinal)}</b> via {@code String.compareTo} -
	 * participation/query order is not guaranteed, and an unsorted rendering would hash the same graph
	 * differently on different runs. Each contributes {@code role}, {@code bindingOrdinal}, and its
	 * resolved source, tried in order: {@code sourceArtifact.contentHash}, then
	 * {@code refModel + refObjectId + refHash}, then {@code valueHash};</li>
	 * <li>{@code configHash} of the <b>merged effective</b> config, not the override (§2.3);</li>
	 * <li>a hash of {@code promptText}.</li>
	 * </ol>
	 * Every null renders as {@link PbConfigUtil#NULL_TOKEN} - never {@code ""}, never {@code "null"} -
	 * and doubles via {@code BigDecimal.stripTrailingZeros().toPlainString()}. Nothing in the canonical
	 * form uses a locale-sensitive operation.
	 *
	 * @param book the node's book, read with at least {@link PbConfigUtil#requestFields()}
	 */
	public static String computeInputHash(BaseRecord user, BaseRecord node, BaseRecord book) {
		return PbConfigUtil.sha256Hex(canonicalInput(user, node, book));
	}

	/**
	 * The exact string {@link #computeInputHash} hashes. Public so a checked-in golden vector can pin it
	 * and so a mismatch can be diagnosed by diffing two strings rather than two digests.
	 */
	public static String canonicalInput(BaseRecord user, BaseRecord node, BaseRecord book) {
		if(node == null) {
			throw new PictureBookException(400, "Cannot hash the inputs of a null node");
		}
		StringBuilder sb = new StringBuilder(PB_PIPELINE_VERSION);
		sb.append(PbConfigUtil.PAIR_SEPARATOR).append("nodeType=")
			.append(PbConfigUtil.token(node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE) ? node.get(OlioFieldNames.FIELD_PB_NODE_TYPE) : null));

		for(BaseRecord b : sortedBindings(listBindings(user, node))) {
			sb.append(PbConfigUtil.PAIR_SEPARATOR).append("binding")
				.append(PbConfigUtil.PAIR_SEPARATOR).append("  role=").append(PbConfigUtil.token(b.get(OlioFieldNames.FIELD_PB_ROLE)))
				.append(PbConfigUtil.PAIR_SEPARATOR).append("  ordinal=").append(PbConfigUtil.token(b.get(OlioFieldNames.FIELD_PB_BINDING_ORDINAL)))
				.append(PbConfigUtil.PAIR_SEPARATOR).append("  source=").append(bindingSourceToken(user, b));
		}

		BaseRecord effective = PbConfigUtil.resolveEffectiveConfig(book, node, isCompositeNode(node));
		sb.append(PbConfigUtil.PAIR_SEPARATOR).append("configHash=").append(PbConfigUtil.token(PbConfigUtil.configHash(effective)));

		String promptText = (node.hasField(OlioFieldNames.FIELD_PB_PROMPT_TEXT) ? node.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT) : null);
		sb.append(PbConfigUtil.PAIR_SEPARATOR).append("promptHash=")
			.append(PbConfigUtil.token(promptText != null ? PbConfigUtil.sha256Hex(promptText) : null));

		if(logger.isDebugEnabled()) {
			logger.debug("inputHash canonical form for node " + node.get(FieldNames.FIELD_NAME)
				+ " under " + PB_PIPELINE_VERSION);
		}
		return sb.toString();
	}

	/**
	 * One binding's resolved source, in the §2.3 precedence: the exact artifact revision's
	 * {@code contentHash}, else the referenced record's identity plus its {@code refHash}, else the
	 * literal's {@code valueHash}.
	 * <p>
	 * §2.3's formula names a {@code refRevision} field that does not exist and never did - the mechanism
	 * that actually detects an external edit is {@code refHash} over a declared watched set
	 * ({@link PbWatchedFields}).
	 */
	private static String bindingSourceToken(BaseRecord user, BaseRecord binding) {
		BaseRecord artifact = (binding.hasField(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT) ? binding.get(OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT) : null);
		if(artifact != null) {
			String contentHash = artifact.get(OlioFieldNames.FIELD_PB_CONTENT_HASH);
			if(contentHash == null) {
				/// The FK was projected but the artifact's own fields were not; read it rather than
				/// silently hashing a null, which would make two different artifacts hash identically.
				BaseRecord full = PbArtifactUtil.readArtifact(user, artifact.get(FieldNames.FIELD_OBJECT_ID), orgId(binding));
				contentHash = (full != null ? full.get(OlioFieldNames.FIELD_PB_CONTENT_HASH) : null);
			}
			return "artifact:" + PbConfigUtil.token(contentHash);
		}
		String refModel = binding.get(OlioFieldNames.FIELD_PB_REF_MODEL);
		if(refModel != null) {
			return "ref:" + PbConfigUtil.token(refModel) + ":" + PbConfigUtil.token(binding.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID))
				+ ":" + PbConfigUtil.token(binding.get(OlioFieldNames.FIELD_PB_REF_HASH));
		}
		String valueHash = binding.get(OlioFieldNames.FIELD_PB_VALUE_HASH);
		if(valueHash != null) {
			return "value:" + PbConfigUtil.token(valueHash);
		}
		return "unbound:" + PbConfigUtil.NULL_TOKEN;
	}

	private static boolean isCompositeNode(BaseRecord node) {
		String t = (node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE) ? node.get(OlioFieldNames.FIELD_PB_NODE_TYPE) : null);
		return PbNodeTypeEnumType.COMPOSITE.toString().equals(t);
	}

	/** Bindings sorted by {@code (role, bindingOrdinal)}, role compared with {@code String.compareTo}. */
	public static List<BaseRecord> sortedBindings(List<BaseRecord> bindings) {
		List<BaseRecord> out = new ArrayList<>(bindings);
		out.sort(Comparator
			.comparing((BaseRecord b) -> {
				String r = b.get(OlioFieldNames.FIELD_PB_ROLE);
				return (r != null ? r : "");
			})
			.thenComparingInt(b -> {
				Integer o = b.get(OlioFieldNames.FIELD_PB_BINDING_ORDINAL);
				return (o != null ? o.intValue() : 0);
			}));
		return out;
	}

	// ─────────────────────────────── staleness ───────────────────────────────

	/**
	 * Compute a node's derived status. <b>Computes and returns; writes nothing</b> (ratification 2).
	 * <p>
	 * The rules, in order:
	 * <ul>
	 * <li>a {@code FAILED} or {@code SKIPPED} node keeps that status - both are terminal statements about
	 * a run, not derivations from the inputs;</li>
	 * <li>{@code inputHash == null} means the node has never run successfully, so it is {@code READY} when
	 * every required binding resolves and {@code PENDING} otherwise - <b>never {@code STALE}</b>
	 * (§2.3's explicit carve-out; without it every new node would be born stale);</li>
	 * <li>otherwise {@code STALE} iff the recomputed hash differs, else the stored status if it is
	 * {@code DONE}/{@code DONE_UNVERIFIED}, else {@code READY}.</li>
	 * </ul>
	 * A {@code pinned} node is still reported {@code STALE} - knowing an approved output is now
	 * inconsistent is worth having; refusing to re-run it is the executor's job, not this one's.
	 */
	public static PbNodeStatusEnumType recomputeStatus(BaseRecord user, BaseRecord node, BaseRecord book) {
		if(node == null) {
			return PbNodeStatusEnumType.UNKNOWN;
		}
		PbNodeStatusEnumType stored = node.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
		if(stored == PbNodeStatusEnumType.FAILED || stored == PbNodeStatusEnumType.SKIPPED) {
			return stored;
		}
		String storedHash = node.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
		if(storedHash == null) {
			return (requiredBindingsResolve(user, node) ? PbNodeStatusEnumType.READY : PbNodeStatusEnumType.PENDING);
		}
		String current = computeInputHash(user, node, book);
		if(!storedHash.equals(current)) {
			return PbNodeStatusEnumType.STALE;
		}
		if(stored == PbNodeStatusEnumType.DONE || stored == PbNodeStatusEnumType.DONE_UNVERIFIED) {
			return stored;
		}
		return PbNodeStatusEnumType.READY;
	}

	/**
	 * Recompute every binding's {@code refHash} for {@code node} and report whether any changed.
	 * <p>
	 * This is the leg that sees an <b>edit to an external record</b> - a character's age or description -
	 * which artifact chaining structurally cannot. It is a projected read per referenced record, so it is
	 * a deliberate operation, never per-request (see {@link PbWatchedFields}).
	 * <p>
	 * Writes nothing, for the same reason {@link #recomputeStatus} writes nothing. Persisting the fresh
	 * hashes is {@link #persistRefHashes}.
	 *
	 * @return the bindings whose recomputed hash differs from the stored one
	 */
	public static List<BaseRecord> driftedRefBindings(BaseRecord user, BaseRecord node) {
		List<BaseRecord> drifted = new ArrayList<>();
		for(BaseRecord b : listBindings(user, node)) {
			String refModel = b.get(OlioFieldNames.FIELD_PB_REF_MODEL);
			String refObjectId = b.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
			if(refModel == null || refObjectId == null) {
				continue;
			}
			String stored = b.get(OlioFieldNames.FIELD_PB_REF_HASH);
			String fresh = PbWatchedFields.computeRefHash(user, refModel, refObjectId);
			if(fresh == null) {
				/// Cannot determine is NOT unchanged. Say so and move on rather than silently treating an
				/// unreadable or unwatched reference as clean.
				logger.warn("Could not recompute refHash for binding " + b.get(FieldNames.FIELD_NAME)
					+ " (" + refModel + " " + refObjectId + "); staleness for it is undetermined");
				continue;
			}
			if(!fresh.equals(stored)) {
				drifted.add(b);
			}
		}
		return drifted;
	}

	/**
	 * Persist fresh {@code refHash} values on the given bindings. An explicit, authorized write - the
	 * counterpart to {@link #driftedRefBindings}, which only computes.
	 *
	 * @return the number of bindings updated
	 */
	public static int persistRefHashes(BaseRecord user, List<BaseRecord> bindings) {
		int updated = 0;
		for(BaseRecord b : bindings) {
			String refModel = b.get(OlioFieldNames.FIELD_PB_REF_MODEL);
			String refObjectId = b.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
			String fresh = PbWatchedFields.computeRefHash(user, refModel, refObjectId);
			if(fresh == null) {
				continue;
			}
			BaseRecord patch = patchOf(b, OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_REF_HASH);
			try {
				patch.set(OlioFieldNames.FIELD_PB_REF_HASH, fresh);
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				throw new PictureBookException(500, "Failed to assemble a refHash patch: " + e.getMessage());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				logger.error("Failed to persist refHash on binding " + b.get(FieldNames.FIELD_NAME));
				continue;
			}
			updated++;
		}
		return updated;
	}

	/**
	 * Persist a node's derived status. The <b>only</b> place {@code nodeStatus} is written from a
	 * recompute, and an explicitly authorized write - never reached from a read path.
	 * <p>
	 * PATCH-shaped: {@code schema} + {@code id} + {@code objectId} + <b>{@code name}</b> + the changed
	 * field. {@code name} is mandatory even though it has not changed - the writer validates the patch
	 * record itself, not the merged result. And the update result is asserted, never discarded: a
	 * swallowed null is the difference between a persistent failure and a silent no-op.
	 */
	public static boolean persistStatus(BaseRecord user, BaseRecord node, PbNodeStatusEnumType status) {
		if(node == null || status == null) {
			return false;
		}
		BaseRecord patch = patchOf(node, OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_NODE_STATUS);
		try {
			patch.set(OlioFieldNames.FIELD_PB_NODE_STATUS, status.toString());
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a status patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to persist nodeStatus=" + status + " on node " + node.get(FieldNames.FIELD_NAME));
			return false;
		}
		return true;
	}

	/** Persist a node's {@code inputHash}, normally right after a successful run. Result asserted. */
	public static boolean persistInputHash(BaseRecord user, BaseRecord node, String inputHash) {
		return patchNode(user, node, OlioFieldNames.FIELD_PB_INPUT_HASH, inputHash);
	}

	/** Persist a node's resolved prompt cache. */
	public static boolean persistPromptText(BaseRecord user, BaseRecord node, String promptText) {
		return patchNode(user, node, OlioFieldNames.FIELD_PB_PROMPT_TEXT, promptText);
	}

	/**
	 * Persist a node's sparse {@code configOverride}. Build the string with
	 * {@link PbConfigUtil#sparseOverride}, never from a full {@code newInstance} graph.
	 */
	public static boolean persistConfigOverride(BaseRecord user, BaseRecord node, String sparseJson) {
		return patchNode(user, node, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, sparseJson);
	}

	/**
	 * Pin or unpin a node.
	 * <p>
	 * {@code pinned} is separate from status on purpose: propagation still marks a pinned node STALE, and
	 * only {@link #nextRunnable(BaseRecord, BaseRecord, boolean)} honours the pin.
	 */
	public static boolean setPinned(BaseRecord user, BaseRecord node, boolean pinned) {
		return patchNode(user, node, OlioFieldNames.FIELD_PB_PINNED, Boolean.valueOf(pinned));
	}

	/** Mark a binding required, which is what makes it block {@link #requiredBindingsResolve}. */
	public static boolean setBindingRequired(BaseRecord user, BaseRecord binding, boolean required) {
		return patchField(user, binding, OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_REQUIRED,
			Boolean.valueOf(required));
	}

	private static boolean patchNode(BaseRecord user, BaseRecord node, String field, Object value) {
		return patchField(user, node, OlioModelNames.MODEL_PB_NODE, field, value);
	}

	private static boolean patchField(BaseRecord user, BaseRecord src, String model, String field, Object value) {
		if(src == null) {
			return false;
		}
		BaseRecord patch = patchOf(src, model, field);
		try {
			patch.set(field, value);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a " + model + "." + field + " patch: " + e.getMessage());
		}
		/// Never discard the update result - it is the only signal a persistent failure gives.
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to persist " + model + "." + field + " on " + src.get(FieldNames.FIELD_NAME));
			return false;
		}
		return true;
	}

	// ─────────────────────────────── runs ───────────────────────────────

	/**
	 * Open an {@code olio.pb.run} for {@code workflow}.
	 * <p>
	 * <b>The name carries the run's start instant</b> (ratification 8): the unique
	 * {@code (name, groupId, organizationId)} index is the urn-collision guard, and every run of one
	 * workflow would otherwise derive the same name. A UUID suffix is appended because two runs can start
	 * inside the same millisecond.
	 */
	public static BaseRecord startRun(BaseRecord user, BaseRecord workflow, String groupPath, List<Long> requestedNodeIds) {
		if(workflow == null) {
			throw new PictureBookException(400, "A run must name its workflow");
		}
		ZonedDateTime startedAt = ZonedDateTime.now();
		String name = runName(workflow, startedAt);
		BaseRecord run = null;
		try {
			run = RecordFactory.newInstance(OlioModelNames.MODEL_PB_RUN);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, run, name, groupPath, orgId(workflow));
			run.set(FieldNames.FIELD_NAME, name);
			run.set(OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
			run.set(OlioFieldNames.FIELD_PB_RUN_STATUS, PbRunStatusEnumType.PENDING.toString());
			run.set(OlioFieldNames.FIELD_PB_STARTED_AT, startedAt);
			run.set(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT, Integer.valueOf(0));
			run.set(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT, Integer.valueOf(0));
			if(requestedNodeIds != null && !requestedNodeIds.isEmpty()) {
				run.set(OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS, new ArrayList<>(requestedNodeIds));
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a run: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble run " + name);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, run);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create run " + name);
		}
		return readRun(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId(workflow));
	}

	/**
	 * Point {@code workflow.lastRun} at {@code run} - the other half of the documented two-hop cycle.
	 * <p>
	 * Written as a patch, and this is exactly the shape whose <i>read</i> side must never be
	 * {@code planMost(true)} without {@link #FULL_PLAN_FILTER}.
	 */
	public static boolean persistLastRun(BaseRecord user, BaseRecord workflow, BaseRecord run) {
		return patchField(user, workflow, OlioModelNames.MODEL_PB_WORKFLOW, OlioFieldNames.FIELD_PB_LAST_RUN, run);
	}

	/** Close a run out. Result asserted; a discarded result would leave a run PENDING forever. */
	public static boolean completeRun(BaseRecord user, BaseRecord run, PbRunStatusEnumType status,
			int executed, int failed, String error) {
		if(run == null || status == null) {
			return false;
		}
		BaseRecord patch = patchOf(run, OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_RUN_STATUS,
			OlioFieldNames.FIELD_PB_COMPLETED_AT, OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT,
			OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT, OlioFieldNames.FIELD_PB_ERROR);
		try {
			patch.set(OlioFieldNames.FIELD_PB_RUN_STATUS, status.toString());
			patch.set(OlioFieldNames.FIELD_PB_COMPLETED_AT, ZonedDateTime.now());
			patch.set(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT, Integer.valueOf(executed));
			patch.set(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT, Integer.valueOf(failed));
			if(error != null) {
				patch.set(OlioFieldNames.FIELD_PB_ERROR, error);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a run patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to complete run " + run.get(FieldNames.FIELD_NAME));
			return false;
		}
		return true;
	}

	public static BaseRecord readRun(BaseRecord user, String objectId, long organizationId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(runRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/** {@code Run <workflowName> <ISO instant> <uuid8>} - unique within the group, per ratification 8. */
	public static String runName(BaseRecord workflow, ZonedDateTime startedAt) {
		return "Run " + workflow.get(FieldNames.FIELD_NAME) + " "
			+ startedAt.format(DateTimeFormatter.ISO_INSTANT) + " "
			+ UUID.randomUUID().toString().substring(0, 8);
	}

	public static String[] runRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_WORKFLOW, OlioFieldNames.FIELD_PB_RUN_STATUS,
			OlioFieldNames.FIELD_PB_STARTED_AT, OlioFieldNames.FIELD_PB_COMPLETED_AT,
			OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS, OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT,
			OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT, OlioFieldNames.FIELD_PB_ERROR
		};
	}

	/**
	 * Mark everything downstream of {@code changed} stale, breadth-first with a visited set.
	 * <p>
	 * Consumers are found by querying bindings whose {@code sourceNode} is a node already in the frontier,
	 * which is why {@code binding.sourceNode} carries a {@code hints} entry: this is the exact path
	 * propagation walks. A <b>pinned</b> node is marked like any other - the executor refuses to re-run it
	 * without {@code force}, but suppressing the mark would hide that an approved output is now
	 * inconsistent.
	 * <p>
	 * {@code changed} itself is NOT marked: its own status is decided by whatever produced the new
	 * revision.
	 *
	 * @return the nodes marked, in the order they were reached
	 */
	public static List<BaseRecord> markStaleDownstream(BaseRecord user, BaseRecord workflow, BaseRecord changed) {
		List<BaseRecord> marked = new ArrayList<>();
		if(changed == null) {
			return marked;
		}
		Map<Long, BaseRecord> nodes = nodesById(user, workflow);
		Map<Long, List<Long>> adjacency = adjacency(user, workflow);

		Set<Long> visited = new LinkedHashSet<>();
		Deque<Long> frontier = new ArrayDeque<>();
		Long startId = changed.get(FieldNames.FIELD_ID);
		visited.add(startId);
		frontier.add(startId);

		while(!frontier.isEmpty()) {
			Long id = frontier.poll();
			for(Long consumer : adjacency.getOrDefault(id, Collections.emptyList())) {
				if(!visited.add(consumer)) {
					continue;
				}
				frontier.add(consumer);
				BaseRecord n = nodes.get(consumer);
				if(n == null) {
					logger.warn("Downstream node #" + consumer + " is not readable; not marked");
					continue;
				}
				if(persistStatus(user, n, PbNodeStatusEnumType.STALE)) {
					marked.add(n);
				}
			}
		}
		if(!marked.isEmpty()) {
			logger.info("Marked " + marked.size() + " node(s) STALE downstream of " + changed.get(FieldNames.FIELD_NAME));
		}
		return marked;
	}

	/**
	 * The nodes that could run now: status {@code READY}, {@code PENDING} or {@code STALE}, every
	 * <b>required</b> binding resolves, and no upstream producer is itself pending work.
	 * <p>
	 * Ordered by {@code ordinal} then id, so a caller gets a stable schedule rather than whatever order
	 * the query returned. A {@code pinned} node is included only when {@code force} is true, which is
	 * where §2.2's "the executor refuses to re-run a pinned node without force" is actually enforced.
	 */
	public static List<BaseRecord> nextRunnable(BaseRecord user, BaseRecord workflow, boolean force) {
		Map<Long, BaseRecord> nodes = nodesById(user, workflow);
		Map<Long, List<Long>> adjacency = adjacency(user, workflow);

		/// Reverse the adjacency once: a node is blocked when any producer still has work to do.
		Map<Long, List<Long>> producers = new HashMap<>();
		adjacency.forEach((from, tos) -> tos.forEach(to -> producers.computeIfAbsent(to, k -> new ArrayList<>()).add(from)));

		List<BaseRecord> out = new ArrayList<>();
		for(BaseRecord n : nodes.values()) {
			PbNodeStatusEnumType st = n.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
			if(st != PbNodeStatusEnumType.READY && st != PbNodeStatusEnumType.PENDING && st != PbNodeStatusEnumType.STALE) {
				continue;
			}
			Boolean pinned = n.get(OlioFieldNames.FIELD_PB_PINNED);
			if(pinned != null && pinned.booleanValue() && !force) {
				continue;
			}
			if(!requiredBindingsResolve(user, n)) {
				continue;
			}
			boolean blocked = false;
			for(Long p : producers.getOrDefault((Long) n.get(FieldNames.FIELD_ID), Collections.emptyList())) {
				BaseRecord up = nodes.get(p);
				PbNodeStatusEnumType ust = (up != null ? up.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS) : null);
				if(ust == PbNodeStatusEnumType.PENDING || ust == PbNodeStatusEnumType.READY
					|| ust == PbNodeStatusEnumType.STALE || ust == PbNodeStatusEnumType.RUNNING) {
					blocked = true;
					break;
				}
			}
			if(!blocked) {
				out.add(n);
			}
		}
		out.sort(Comparator
			.comparingInt((BaseRecord n) -> {
				Integer o = n.get(OlioFieldNames.FIELD_PB_ORDINAL);
				return (o != null ? o.intValue() : 0);
			})
			.thenComparingLong(n -> {
				Long id = n.get(FieldNames.FIELD_ID);
				return (id != null ? id.longValue() : 0L);
			}));
		return out;
	}

	/** Does every binding marked {@code required} have a resolved source? */
	public static boolean requiredBindingsResolve(BaseRecord user, BaseRecord node) {
		for(BaseRecord b : listBindings(user, node)) {
			Boolean required = b.get(OlioFieldNames.FIELD_PB_REQUIRED);
			if(required == null || !required.booleanValue()) {
				continue;
			}
			if(bindingSourceToken(user, b).startsWith("unbound:")) {
				return false;
			}
		}
		return true;
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/** Every node of {@code workflow}, keyed by id. Explicit projection - no {@code planMost(true)}. */
	public static Map<Long, BaseRecord> nodesById(BaseRecord user, BaseRecord workflow) {
		Map<Long, BaseRecord> out = new HashMap<>();
		for(BaseRecord n : listNodes(user, workflow)) {
			out.put((Long) n.get(FieldNames.FIELD_ID), n);
		}
		return out;
	}

	/** Every node of {@code workflow}. */
	public static List<BaseRecord> listNodes(BaseRecord user, BaseRecord workflow) {
		if(user == null || workflow == null) {
			return Collections.emptyList();
		}
		/// A condition on a FOREIGN MODEL field takes the RECORD, never its id: Query.field routes the
		/// value through FieldUtil.setFlex, which calls setModel() for a MODEL-typed field, so a Long is
		/// rejected and the condition silently becomes "workflow = null" - it matches nothing and logs
		/// nothing at the call site. StatementUtil casts the value to BaseRecord and reads its id
		/// (StatementUtil.java:1367). Measured on am7db 2026-08-15.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(workflow));
		q.setRequest(nodeRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_NODE) : Collections.emptyList());
	}

	/** Every binding consumed by {@code node}. */
	public static List<BaseRecord> listBindings(BaseRecord user, BaseRecord node) {
		if(user == null || node == null) {
			return Collections.emptyList();
		}
		/// A condition on a FOREIGN MODEL field takes the RECORD, never its id: Query.field routes the
		/// value through FieldUtil.setFlex, which calls setModel() for a MODEL-typed field, so a Long is
		/// rejected and the condition silently becomes "node = null" - it matches nothing and logs
		/// nothing at the call site. StatementUtil casts the value to BaseRecord and reads its id
		/// (StatementUtil.java:1367). Measured on am7db 2026-08-15.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE, node);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(node));
		q.setRequest(bindingRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_BINDING) : Collections.emptyList());
	}

	/** Every binding of every node of {@code workflow}, i.e. the whole edge set. */
	public static List<BaseRecord> listWorkflowBindings(BaseRecord user, BaseRecord workflow) {
		List<BaseRecord> out = new ArrayList<>();
		for(BaseRecord n : listNodes(user, workflow)) {
			out.addAll(listBindings(user, n));
		}
		return out;
	}

	public static BaseRecord readNode(BaseRecord user, String objectId, long organizationId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(nodeRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	public static BaseRecord readBinding(BaseRecord user, String objectId, long organizationId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BINDING, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(bindingRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Edges as {@code sourceNodeId -> [consumerNodeId]}. Built from the persisted bindings, skipping
	 * external roots (a null {@code sourceNode}).
	 */
	private static Map<Long, List<Long>> adjacency(BaseRecord user, BaseRecord workflow) {
		Map<Long, List<Long>> out = new HashMap<>();
		for(BaseRecord b : listWorkflowBindings(user, workflow)) {
			BaseRecord src = b.get(OlioFieldNames.FIELD_PB_SOURCE_NODE);
			BaseRecord consumer = b.get(OlioFieldNames.FIELD_PB_NODE);
			if(src == null || consumer == null) {
				continue;
			}
			Long from = src.get(FieldNames.FIELD_ID);
			Long to = consumer.get(FieldNames.FIELD_ID);
			if(from == null || to == null) {
				continue;
			}
			out.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
		}
		return out;
	}

	private static Map<Long, String> nodeLabels(BaseRecord user, BaseRecord workflow) {
		Map<Long, String> out = new HashMap<>();
		for(BaseRecord n : listNodes(user, workflow)) {
			String handle = n.get(OlioFieldNames.FIELD_PB_HANDLE);
			out.put((Long) n.get(FieldNames.FIELD_ID), (handle != null ? handle : (String) n.get(FieldNames.FIELD_NAME)));
		}
		return out;
	}

	// ─────────────────────────────── names & projections ───────────────────────────────

	/** {@code Workflow <bookName>} - unique in the world's {@code Workflow} group, one per book. */
	public static String workflowName(BaseRecord book) {
		return "Workflow " + book.get(FieldNames.FIELD_NAME);
	}

	/** {@code Node <handle>} - derived from {@code handle}, per ratification 8. */
	public static String nodeName(String handle) {
		return "Node " + handle;
	}

	/** {@code Binding <nodeHandle> <role> <ordinal>} - derived from role + bindingOrdinal. */
	public static String bindingName(BaseRecord node, String role, int bindingOrdinal) {
		Object handle = (node.hasField(OlioFieldNames.FIELD_PB_HANDLE) ? node.get(OlioFieldNames.FIELD_PB_HANDLE) : null);
		return "Binding " + (handle != null ? handle : node.get(FieldNames.FIELD_OBJECT_ID)) + " " + role + " " + bindingOrdinal;
	}

	public static String[] workflowRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_GRAPH_VERSION,
			OlioFieldNames.FIELD_PB_GRAPH_STATUS, OlioFieldNames.FIELD_PB_NODE_COUNT
			/// lastRun is deliberately absent: it is one half of the workflow <-> run cycle
		};
	}

	public static String[] nodeRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_WORKFLOW, OlioFieldNames.FIELD_PB_HANDLE, OlioFieldNames.FIELD_PB_NODE_TYPE,
			OlioFieldNames.FIELD_PB_NODE_STATUS, OlioFieldNames.FIELD_PB_PINNED, OlioFieldNames.FIELD_PB_ORDINAL,
			OlioFieldNames.FIELD_PB_SCENE_INDEX, OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF,
			OlioFieldNames.FIELD_PB_PROMPT_TEMPLATE_NAME, OlioFieldNames.FIELD_PB_PROMPT_TEXT,
			OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, OlioFieldNames.FIELD_PB_INPUT_HASH,
			OlioFieldNames.FIELD_PB_CONFIG_HASH, OlioFieldNames.FIELD_PB_LAST_ERROR,
			OlioFieldNames.FIELD_PB_LAST_RUN_AT
		};
	}

	public static String[] bindingRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_NODE, OlioFieldNames.FIELD_PB_ROLE, OlioFieldNames.FIELD_PB_BINDING_ORDINAL,
			OlioFieldNames.FIELD_PB_SOURCE_NODE, OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT,
			OlioFieldNames.FIELD_PB_REF_MODEL, OlioFieldNames.FIELD_PB_REF_OBJECT_ID, OlioFieldNames.FIELD_PB_REF_HASH,
			OlioFieldNames.FIELD_PB_VALUE_TEXT, OlioFieldNames.FIELD_PB_VALUE_HASH, OlioFieldNames.FIELD_PB_REQUIRED
		};
	}

	/**
	 * A PATCH-shaped record: {@code schema} + {@code id} + {@code objectId} + <b>{@code name}</b> + exactly
	 * the fields named in {@code changedFields}, and <b>nothing else</b>.
	 * <p>
	 * <b>The {@code changedFields} argument is not a convenience - it is the whole correctness of a patch.</b>
	 * {@code RecordFactory.newInstance(model)} materialises <b>every</b> field of the model at its default
	 * value, and the writer persists every field present on the record it is handed. So a patch built from
	 * the no-argument overload silently overwrites every field the caller did not set: measured on
	 * {@code am7db} 2026-08-15, a book patch that set only {@code world} blanked {@code slug} and
	 * {@code description} and reset {@code bookStatus} to UNKNOWN, and the failure was visible only as a
	 * later read that found nothing. The field-name overload
	 * ({@code newInstance(model, String[] fieldNames)}) materialises only the named fields, which is what a
	 * partial update actually means.
	 * <p>
	 * {@code name} is always included and is not optional. The writer validates the patch record itself
	 * rather than the merged result, so a model whose {@code name} carries a validation rule rejects a
	 * patch that omits it. It is taken from {@code src} - never from a freshly created record, because
	 * {@code AccessPoint.create} returns identity fields ONLY and {@code created.get("name")} is null.
	 *
	 * @param changedFields the fields this patch will set; may be empty for a name-only touch
	 */
	static BaseRecord patchOf(BaseRecord src, String model, String... changedFields) {
		List<String> fields = new ArrayList<>(Arrays.asList(
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME));
		if(changedFields != null) {
			for(String f : changedFields) {
				if(f != null && !fields.contains(f)) {
					fields.add(f);
				}
			}
		}
		BaseRecord patch = null;
		try {
			patch = RecordFactory.newInstance(model, fields.toArray(new String[0]));
			patch.set(FieldNames.FIELD_ID, src.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, src.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_NAME, src.get(FieldNames.FIELD_NAME));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a patch for " + model + ": " + e.getMessage());
		}
		if(patch.get(FieldNames.FIELD_NAME) == null) {
			/// Would fail validation in the writer and be visible only in the log, with the update call
			/// returning a value most callers discard. Refuse here instead.
			throw new PictureBookException(500, "Cannot patch " + model
				+ " without a name - the writer validates the patch record itself, not the merged result."
				+ " Read the record with its name projected before patching it.");
		}
		return patch;
	}

	/**
	 * Restore the {@code schema} property lists lose after the first element.
	 * <p>
	 * The serializer omits it on subsequent items to shrink the payload, so a consumer that reads
	 * {@code getSchema()} on element 2 gets null - and every {@code get()} against a schema-less record
	 * silently misbehaves.
	 */
	static List<BaseRecord> restoreSchema(List<BaseRecord> recs, String model) {
		for(BaseRecord r : recs) {
			if(r.getSchema() == null) {
				r.setSchema(model);
			}
		}
		return recs;
	}

	static long orgId(BaseRecord rec) {
		Long id = rec.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(id == null) {
			throw new PictureBookException(500, "No organizationId on " + rec.getSchema()
				+ " - a list query over a data.directory-derived model without one is denied by PBAC");
		}
		return id.longValue();
	}
}
