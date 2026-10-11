package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

/**
 * V3 §11.1 live drift detection: one scan per book that sees an <b>edit to a bound record</b> (a
 * character's age, a garment) without waiting for a run to re-bind it.
 * <p>
 * <b>Why a separate scan.</b> {@code node.inputHash} hashes each record binding as
 * {@code ref:<model>:<objectId>:<STORED refHash>}, so {@link PbGraphUtil#recomputeStatus} cannot see a
 * change to the record itself until {@code PbPipelineUtil.bindRecord} re-persists the hash during a run.
 * The scan recomputes the ref hashes ({@link PbGraphUtil#driftedRefBindings}'s leg) for every node that
 * has already succeeded, marks drifted nodes {@code STALE}, and walks their consumers.
 * <p>
 * <b>What the scan writes, and as whom.</b> Only {@code node.nodeStatus = STALE}, only through
 * {@link PbGraphUtil#persistStatus} <b>as the acting user</b> — never the org admin, never a create. It is
 * idempotent (a node already stored STALE is not rewritten) and a denied write is reported on the result
 * ({@link Scan#markPersisted}, {@link Entry#persisted}), never thrown out of a read. {@code binding.refHash}
 * is <b>never</b> rewritten by the scan: refreshing it is what a successful run does
 * ({@code bindRecord} → {@code persistRefHashes}), and rewriting it from a read would erase the evidence
 * that the node's last output was produced from older inputs.
 * <p>
 * <b>In-process cache, bounded by a TTL.</b> Scans are cached per workflow for {@link #DEFAULT_SCAN_TTL_MS}
 * and invalidated by every PB graph write in this JVM (node status/hash/binding writes in
 * {@code PbGraphUtil}/{@code PbPipelineUtil}, canvas/handle patches in {@code PbServiceFacade}) and by
 * {@link #touch} after every PB endpoint that edits a bound record. Edits that reach the database
 * <i>without</i> passing through this JVM's PB code — a generic {@code PATCH /rest/model}, the Olio
 * character editor, Console7, a second Service7 instance — are caught on the next scan after the TTL
 * expires, i.e. "the next time the book is viewed" plus at most {@code scanTtlMs}. {@code listStale}
 * always forces a fresh scan. There are no hooks in {@code AccessPoint.update} (layering).
 */
public class PbStaleUtil {
	public static final Logger logger = LogManager.getLogger(PbStaleUtil.class);

	public static final long DEFAULT_SCAN_TTL_MS = 30_000L;
	/** Bound on cached scans; beyond it the oldest is evicted. Tens of open books is the realistic load. */
	static final int MAX_CACHED_SCANS = 256;

	/** The node's own recomputed hash differs from the stored one (artifact/config/prompt change). */
	public static final String REASON_HASH_CHANGED = "hashChanged";
	/** A record the node binds has a watched-set edit the stored {@code refHash} does not reflect. */
	public static final String REASON_REF_DRIFT = "refDrift";
	/** Stored STALE from an earlier write or scan and nothing in this scan explains it. */
	public static final String REASON_MARKED = "marked";
	/** Downstream of a {@code hashChanged}/{@code refDrift} root in this scan; outranks {@code marked}. */
	public static final String REASON_PROPAGATED = "propagated";

	private static volatile long scanTtlMs = DEFAULT_SCAN_TTL_MS;

	private static final ConcurrentHashMap<Long, Scan> SCANS = new ConcurrentHashMap<>();
	/** {@code refKey -> workflow ids whose last scan bound that record}, so {@link #touch} is a lookup. */
	private static final ConcurrentHashMap<String, Set<Long>> REF_INDEX = new ConcurrentHashMap<>();
	/** {@code node id -> workflow id} for every node a cached scan saw, so a binding write needs no read. */
	private static final ConcurrentHashMap<Long, Long> NODE_INDEX = new ConcurrentHashMap<>();
	/**
	 * {@code workflow id -> invalidation count}. A scan captures it before computing and is only cached if
	 * it is unchanged afterwards, so a write that lands mid-compute cannot be hidden by the scan that
	 * started before it.
	 */
	private static final ConcurrentHashMap<Long, Long> EPOCHS = new ConcurrentHashMap<>();

	private PbStaleUtil() {
		/// static utility
	}

	// ─────────────────────────────── result types ───────────────────────────────

	/** One record binding whose recomputed {@code refHash} differs from the stored one. */
	public static final class DriftedRef {
		public final String role;
		public final String refModel;
		public final String refObjectId;

		DriftedRef(String role, String refModel, String refObjectId) {
			this.role = role;
			this.refModel = refModel;
			this.refObjectId = refObjectId;
		}
	}

	/**
	 * One stale node as the scan saw it. A scan is computed with the scanning user's read view, so only a
	 * {@link Scan#complete complete} scan (every bound record readable, every mark persisted) is cached and
	 * shared with other viewers.
	 */
	public static final class Entry {
		public final long nodeId;
		public final String nodeObjectId;
		public final String handle;
		public final String reason;
		public final List<DriftedRef> driftedBindings;
		/** Distinct {@code sceneIndex} of {@code scope == "scene"} nodes in this node's closure (itself included). */
		public final SortedSet<Integer> affectedSceneIndexes;
		/** True when the stored status is STALE after this scan; false when the scan could not persist it. */
		public final boolean persisted;

		Entry(long nodeId, String nodeObjectId, String handle, String reason, List<DriftedRef> driftedBindings,
				SortedSet<Integer> affectedSceneIndexes, boolean persisted) {
			this.nodeId = nodeId;
			this.nodeObjectId = nodeObjectId;
			this.handle = handle;
			this.reason = reason;
			this.driftedBindings = Collections.unmodifiableList(driftedBindings);
			this.affectedSceneIndexes = Collections.unmodifiableSortedSet(affectedSceneIndexes);
			this.persisted = persisted;
		}

		Entry withPersisted(boolean p) {
			return new Entry(nodeId, nodeObjectId, handle, reason, new ArrayList<>(driftedBindings),
				new TreeSet<>(affectedSceneIndexes), p);
		}
	}

	/** The immutable result of one scan over one workflow. */
	public static final class Scan {
		public final long at;
		public final long workflowId;
		/** Stale nodes keyed by node id, in node list order. */
		public final Map<Long, Entry> entries;
		/** Every node's recomputed status, so a view needs no second recompute. */
		public final Map<Long, PbNodeStatusEnumType> statuses;
		/** Every {@code refModel:refObjectId} the workflow binds. */
		public final Set<String> refKeys;
		/** False when at least one STALE mark could not be persisted (typically a read-only viewer). */
		public final boolean markPersisted;
		/** Bound records the scanning user could not read, so nodes bound to them were not assessed. */
		public final Set<String> undeterminedRefs;
		/**
		 * True when every bound record was readable and every mark persisted — the only kind of scan that is
		 * cached, so a narrower viewer's result is never served to a user who could see or persist more.
		 */
		public final boolean complete;

		private final Map<Long, List<Long>> adjacency;
		private final Map<Long, Integer> sceneIndexByEligibleSceneNode;
		private final Map<String, Set<Long>> boundNodesByRef;

		Scan(long at, long workflowId, Map<Long, Entry> entries, Map<Long, PbNodeStatusEnumType> statuses,
				Set<String> refKeys, boolean markPersisted, Set<String> undeterminedRefs, Map<Long, List<Long>> adjacency,
				Map<Long, Integer> sceneIndexByEligibleSceneNode, Map<String, Set<Long>> boundNodesByRef) {
			this.at = at;
			this.workflowId = workflowId;
			this.entries = Collections.unmodifiableMap(entries);
			this.statuses = Collections.unmodifiableMap(statuses);
			this.refKeys = Collections.unmodifiableSet(refKeys);
			this.markPersisted = markPersisted;
			this.undeterminedRefs = Collections.unmodifiableSet(undeterminedRefs);
			this.complete = markPersisted && undeterminedRefs.isEmpty();
			this.adjacency = adjacency;
			this.sceneIndexByEligibleSceneNode = sceneIndexByEligibleSceneNode;
			this.boundNodesByRef = boundNodesByRef;
		}

		/** The nodes whose bindings reference {@code refModel/refObjectId}. */
		public Set<Long> boundNodes(String refModel, String refObjectId) {
			Set<Long> s = boundNodesByRef.get(refKey(refModel, refObjectId));
			return (s != null ? Collections.unmodifiableSet(s) : Collections.emptySet());
		}

		/** The entries whose drifted bindings reference {@code refModel/refObjectId}. */
		public List<Entry> driftedEntriesFor(String refModel, String refObjectId) {
			List<Entry> out = new ArrayList<>();
			for(Entry e : entries.values()) {
				for(DriftedRef d : e.driftedBindings) {
					if(d.refModel != null && d.refModel.equals(refModel) && d.refObjectId != null && d.refObjectId.equals(refObjectId)) {
						out.add(e);
						break;
					}
				}
			}
			return out;
		}

		/**
		 * Distinct scene indexes of the already-rendered scene-scope nodes reachable from {@code starts}
		 * (inclusive) — what "affected scenes: N" counts for a record.
		 */
		public SortedSet<Integer> affectedScenes(Set<Long> starts) {
			SortedSet<Integer> out = new TreeSet<>();
			for(Long id : closure(starts, adjacency)) {
				Integer si = sceneIndexByEligibleSceneNode.get(id);
				if(si != null) {
					out.add(si);
				}
			}
			return out;
		}
	}

	// ─────────────────────────────── the scan ───────────────────────────────

	/**
	 * Scan {@code workflow} for stale nodes, serving the cached result when it is younger than the TTL
	 * and {@code force} is false.
	 * <p>
	 * One node list, one binding list per node, one {@code computeRefHash} per distinct bound record
	 * (memoized within the scan). Nodes that have never succeeded ({@code inputHash == null}) and nodes
	 * stored FAILED/SKIPPED/RUNNING are never marked, in or downstream: the first is §2.3's carve-out, the
	 * rest are statements about a run that a scan must not overwrite. Downstream propagation walks
	 * through such nodes but only marks the eligible ones.
	 */
	public static Scan scan(BaseRecord user, BaseRecord book, BaseRecord workflow, boolean force) {
		if(user == null || book == null || workflow == null) {
			throw new PictureBookException(400, "A scan needs a user, a book and a workflow");
		}
		Long wfId = workflow.get(FieldNames.FIELD_ID);
		if(wfId == null) {
			throw new PictureBookException(400, "The workflow carries no id");
		}
		long now = System.currentTimeMillis();
		if(!force) {
			Scan cached = SCANS.get(wfId);
			if(cached != null && (now - cached.at) < scanTtlMs) {
				return cached;
			}
		}
		long epoch = epochOf(wfId.longValue());
		Scan fresh = compute(user, book, workflow, wfId.longValue(), now);
		/// An incomplete scan is this user's partial view (unreadable refs skipped, marks not persisted);
		/// caching it would serve that narrower view to a user who could see or persist more. A scan
		/// overtaken by a write is cached by nobody; the next caller recomputes.
		if(fresh.complete) {
			put(fresh, epoch);
		}
		return fresh;
	}

	/** The cached scan for {@code workflowId} regardless of age, or null. Test hook. */
	public static Scan cached(long workflowId) {
		return SCANS.get(Long.valueOf(workflowId));
	}

	private static Scan compute(BaseRecord user, BaseRecord book, BaseRecord workflow, long wfId, long now) {
		List<BaseRecord> nodes = PbGraphUtil.listNodes(user, workflow);
		Map<Long, BaseRecord> nodesById = new LinkedHashMap<>();
		Map<Long, List<BaseRecord>> bindingsByNode = new HashMap<>();
		List<BaseRecord> allBindings = new ArrayList<>();
		for(BaseRecord n : nodes) {
			Long id = n.get(FieldNames.FIELD_ID);
			if(id == null) {
				continue;
			}
			nodesById.put(id, n);
			List<BaseRecord> bs = PbGraphUtil.listBindings(user, n);
			bindingsByNode.put(id, bs);
			allBindings.addAll(bs);
		}
		Map<Long, List<Long>> adjacency = PbGraphUtil.adjacencyOf(allBindings);

		Map<Long, PbNodeStatusEnumType> statuses = new HashMap<>();
		Map<Long, Integer> sceneIndexByEligibleSceneNode = new HashMap<>();
		Map<String, Set<Long>> boundNodesByRef = new HashMap<>();
		Set<String> refKeys = new HashSet<>();
		Set<Long> eligible = new HashSet<>();
		Set<Long> roots = new LinkedHashSet<>();
		Map<Long, String> reasons = new LinkedHashMap<>();
		Map<Long, List<DriftedRef>> drifts = new HashMap<>();
		Map<String, String> hashMemo = new HashMap<>();
		Set<String> undetermined = new HashSet<>();

		for(Map.Entry<Long, BaseRecord> me : nodesById.entrySet()) {
			Long id = me.getKey();
			BaseRecord n = me.getValue();
			List<BaseRecord> bs = bindingsByNode.get(id);
			PbNodeStatusEnumType stored = n.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
			PbNodeStatusEnumType status = PbGraphUtil.recomputeStatus(user, n, book, bs);
			statuses.put(id, status);

			for(BaseRecord b : bs) {
				String refModel = b.get(OlioFieldNames.FIELD_PB_REF_MODEL);
				String refObjectId = b.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
				if(refModel == null || refObjectId == null) {
					continue;
				}
				String key = refKey(refModel, refObjectId);
				refKeys.add(key);
				boundNodesByRef.computeIfAbsent(key, k -> new HashSet<>()).add(id);
			}

			String inputHash = n.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
			boolean isEligible = inputHash != null
				&& stored != PbNodeStatusEnumType.FAILED
				&& stored != PbNodeStatusEnumType.SKIPPED
				&& stored != PbNodeStatusEnumType.RUNNING;
			if(!isEligible) {
				continue;
			}
			eligible.add(id);
			if(PbPipelineUtil.SCOPE_SCENE.equals(n.get(OlioFieldNames.FIELD_PB_SCOPE))) {
				Integer si = n.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
				if(si != null) {
					sceneIndexByEligibleSceneNode.put(id, si);
				}
			}

			if(status == PbNodeStatusEnumType.STALE) {
				roots.add(id);
				reasons.put(id, REASON_HASH_CHANGED);
				continue;
			}
			List<DriftedRef> drifted = driftedRefs(user, bs, hashMemo, undetermined);
			if(!drifted.isEmpty()) {
				roots.add(id);
				reasons.put(id, REASON_REF_DRIFT);
				drifts.put(id, drifted);
				continue;
			}
			if(stored == PbNodeStatusEnumType.STALE) {
				reasons.put(id, REASON_MARKED);
			}
		}

		/// Downstream of every root, eligible nodes get "propagated" unless they are roots themselves.
		/// A stored STALE ("marked") downstream of a live root is explained by that root this scan.
		for(Long consumer : closure(roots, adjacency)) {
			if(eligible.contains(consumer) && !roots.contains(consumer)) {
				reasons.put(consumer, REASON_PROPAGATED);
			}
		}

		/// Persist STALE on everything newly seen, as the acting user, stopping at the first denial so a
		/// read-only viewer costs one WARN rather than one ERROR per node.
		boolean markPersisted = true;
		boolean persisting = true;
		boolean wrote = false;
		Map<Long, Entry> entries = new LinkedHashMap<>();
		for(Map.Entry<Long, String> re : reasons.entrySet()) {
			Long id = re.getKey();
			BaseRecord n = nodesById.get(id);
			PbNodeStatusEnumType stored = n.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
			boolean persisted = (stored == PbNodeStatusEnumType.STALE);
			if(!persisted) {
				if(persisting && !canUpdate(user, n)) {
					persisting = false;
					logger.warn("Drift scan of workflow " + workflow.get(FieldNames.FIELD_NAME) + " found "
						+ reasons.size() + " stale node(s) but " + user.get(FieldNames.FIELD_NAME)
						+ " may not update them; statuses are reported, not persisted");
				}
				if(persisting) {
					/// The scan's own mark is already in its result; it must not invalidate the scan.
					persisted = PbGraphUtil.persistStatus(user, n, PbNodeStatusEnumType.STALE, false);
					if(persisted) {
						wrote = true;
					}
					else {
						persisting = false;
					}
				}
				if(!persisted) {
					markPersisted = false;
				}
			}
			SortedSet<Integer> affected = new TreeSet<>();
			for(Long c : closure(Collections.singleton(id), adjacency)) {
				Integer si = sceneIndexByEligibleSceneNode.get(c);
				if(si != null) {
					affected.add(si);
				}
			}
			entries.put(id, new Entry(id.longValue(), n.get(FieldNames.FIELD_OBJECT_ID),
				n.get(OlioFieldNames.FIELD_PB_HANDLE), re.getValue(),
				drifts.getOrDefault(id, Collections.emptyList()), affected, persisted));
		}
		if(!entries.isEmpty() && logger.isDebugEnabled()) {
			logger.debug("Drift scan of workflow " + workflow.get(FieldNames.FIELD_NAME) + ": " + entries.size()
				+ " stale of " + nodesById.size() + " node(s), " + roots.size() + " root(s)");
		}
		if(wrote && !(markPersisted && undetermined.isEmpty())) {
			/// Wrote some marks but will not be cached: drop any older cached scan that predates them.
			invalidate(wfId);
		}
		return new Scan(now, wfId, entries, statuses, refKeys, markPersisted, undetermined, adjacency,
			sceneIndexByEligibleSceneNode, boundNodesByRef);
	}

	private static List<DriftedRef> driftedRefs(BaseRecord user, List<BaseRecord> bindings, Map<String, String> memo,
			Set<String> undetermined) {
		List<DriftedRef> out = new ArrayList<>();
		for(BaseRecord b : bindings) {
			String refModel = b.get(OlioFieldNames.FIELD_PB_REF_MODEL);
			String refObjectId = b.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
			if(refModel == null || refObjectId == null) {
				continue;
			}
			String key = refKey(refModel, refObjectId);
			String fresh;
			if(memo.containsKey(key)) {
				fresh = memo.get(key);
			}
			else {
				fresh = PbWatchedFields.computeRefHash(user, refModel, refObjectId);
				memo.put(key, fresh);
			}
			if(fresh == null) {
				/// Cannot determine is NOT unchanged - but say so once per record, not once per binding.
				if(undetermined.add(key)) {
					logger.warn("Could not recompute refHash for " + refModel + " " + refObjectId
						+ "; staleness of nodes bound to it is undetermined");
				}
				continue;
			}
			String stored = b.get(OlioFieldNames.FIELD_PB_REF_HASH);
			if(!fresh.equals(stored)) {
				out.add(new DriftedRef(b.get(OlioFieldNames.FIELD_PB_ROLE), refModel, refObjectId));
			}
		}
		return out;
	}

	private static boolean canUpdate(BaseRecord user, BaseRecord node) {
		try {
			PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, node);
			return prr != null && prr.getType() == PolicyResponseEnumType.PERMIT;
		}
		catch(Exception e) {
			logger.warn("Could not evaluate update on node " + node.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			return false;
		}
	}

	/** Breadth-first closure of {@code starts} over {@code adjacency}, inclusive of the starts. */
	private static Set<Long> closure(Set<Long> starts, Map<Long, List<Long>> adjacency) {
		Set<Long> visited = new LinkedHashSet<>(starts);
		Deque<Long> frontier = new ArrayDeque<>(starts);
		while(!frontier.isEmpty()) {
			Long id = frontier.poll();
			for(Long to : adjacency.getOrDefault(id, Collections.emptyList())) {
				if(visited.add(to)) {
					frontier.add(to);
				}
			}
		}
		return visited;
	}

	// ─────────────────────────────── cache ───────────────────────────────

	/** Drop the cached scan for a workflow. Called by every PB graph write in this JVM. */
	public static synchronized void invalidate(long workflowId) {
		Long wfId = Long.valueOf(workflowId);
		EPOCHS.merge(wfId, 1L, Long::sum);
		Scan old = SCANS.remove(wfId);
		if(old != null) {
			unindex(old);
		}
	}

	/**
	 * Drop the cached scan of the workflow that contains {@code nodeId}. A node no cached scan saw belongs
	 * to a workflow with no cached scan (node creation invalidates), so a miss is correctly a no-op.
	 */
	public static void invalidateByNode(long nodeId) {
		Long wfId = NODE_INDEX.get(Long.valueOf(nodeId));
		if(wfId != null) {
			invalidate(wfId.longValue());
		}
	}

	/** Drop the cached scan of every workflow that binds {@code record}. No-op for an unbound record. */
	public static void touch(BaseRecord record) {
		if(record == null) {
			return;
		}
		touch(record.getSchema(), record.get(FieldNames.FIELD_OBJECT_ID));
	}

	/** Drop the cached scan of every workflow that binds {@code model/objectId}. */
	public static void touch(String model, String objectId) {
		if(model == null || objectId == null) {
			return;
		}
		Set<Long> wfIds = REF_INDEX.get(refKey(model, objectId));
		if(wfIds == null) {
			return;
		}
		for(Long id : new ArrayList<>(wfIds)) {
			invalidate(id.longValue());
		}
	}

	/** Drop the cached scan of {@code book}'s workflow, if it has one and {@code user} can read it. */
	public static void touchBook(BaseRecord user, BaseRecord book) {
		if(user == null || book == null) {
			return;
		}
		try {
			BaseRecord wf = PbGraphUtil.findWorkflow(user, book);
			Long id = (wf != null ? wf.get(FieldNames.FIELD_ID) : null);
			if(id != null) {
				invalidate(id.longValue());
			}
		}
		catch(Exception e) {
			logger.warn("Could not resolve the workflow to invalidate for book " + book.get(FieldNames.FIELD_NAME)
				+ ": " + e.getMessage());
			clear();
		}
	}

	/** Drop every cached scan. */
	public static synchronized void clear() {
		SCANS.clear();
		REF_INDEX.clear();
		NODE_INDEX.clear();
		/// Every workflow with a scan in flight has an entry (epochOf creates it before the compute).
		EPOCHS.replaceAll((k, v) -> v + 1L);
	}

	static void setScanTtlMsForTest(long ttlMs) {
		scanTtlMs = (ttlMs >= 0L ? ttlMs : DEFAULT_SCAN_TTL_MS);
	}

	static long scanTtlMs() {
		return scanTtlMs;
	}

	static String refKey(String model, String objectId) {
		return model + ":" + objectId;
	}

	/**
	 * The invalidation count a scan of {@code workflowId} must still see at {@link #put} time to be cached.
	 * Creates the entry, so a later {@link #clear} bumps it for the scan in flight.
	 */
	static long epochOf(long workflowId) {
		return EPOCHS.computeIfAbsent(Long.valueOf(workflowId), k -> 0L).longValue();
	}

	/** Cache {@code scan} unless the workflow was invalidated since {@code epoch} was captured. */
	static synchronized boolean put(Scan scan, long epoch) {
		Long wfId = Long.valueOf(scan.workflowId);
		if(EPOCHS.getOrDefault(wfId, 0L).longValue() != epoch) {
			return false;
		}
		if(SCANS.size() >= MAX_CACHED_SCANS) {
			sweep(scan.at);
		}
		Scan old = SCANS.put(wfId, scan);
		if(old != null) {
			unindex(old);
		}
		for(String key : scan.refKeys) {
			REF_INDEX.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(wfId);
		}
		for(Long nodeId : scan.statuses.keySet()) {
			NODE_INDEX.put(nodeId, wfId);
		}
		return true;
	}

	private static void unindex(Scan scan) {
		Long wfId = Long.valueOf(scan.workflowId);
		for(String key : scan.refKeys) {
			Set<Long> ids = REF_INDEX.get(key);
			if(ids != null) {
				ids.remove(wfId);
				if(ids.isEmpty()) {
					REF_INDEX.remove(key, ids);
				}
			}
		}
		for(Long nodeId : scan.statuses.keySet()) {
			NODE_INDEX.remove(nodeId, wfId);
		}
	}

	/** Evict expired scans; if still full, evict the oldest. */
	private static void sweep(long now) {
		Scan oldest = null;
		for(Scan s : new ArrayList<>(SCANS.values())) {
			if((now - s.at) >= scanTtlMs) {
				invalidate(s.workflowId);
			}
			else if(oldest == null || s.at < oldest.at) {
				oldest = s;
			}
		}
		if(SCANS.size() >= MAX_CACHED_SCANS && oldest != null) {
			invalidate(oldest.workflowId);
		}
	}
}
