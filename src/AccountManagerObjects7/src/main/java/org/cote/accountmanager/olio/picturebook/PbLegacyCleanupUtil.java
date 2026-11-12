package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;

/**
 * Phase 10 P10-5 (PictureBook V3, design §37.5): enumerate, and on an explicit request delete, the rows
 * only the removed PB1 / Kontext pipeline ever wrote.
 * <p>
 * {@link #scan} is read-only, organization-wide, and requires {@code AccountAdministrators}: it creates
 * nothing and never acts as the organization admin. {@link #purge} re-runs the scan and acts on exactly
 * that item set, PBAC-checked per row as the olio principal first and the caller second (the shape of
 * {@link PbOrphanUtil}), so a dry run and an apply cannot diverge. It is reachable only from an explicit
 * entry point (the Console7 {@code -pbLegacyCleanup} / {@code -pbLegacyPurge} action); nothing on a read
 * path calls it.
 * <p>
 * What counts as PB1-only, and what is kept:
 * <ul>
 * <li><b>{@code PB1_LEGACY_NODE}</b> - an {@code olio.pb.node} of type {@code REFERENCE_STRIP} or
 * {@code COMPOSITE}, the two types no executor has produced since P10-1. A legacy node is <b>retained</b>
 * (listed under {@code retained}, never deleted) when it is still live V3 data: a scene's
 * {@code sceneNode} points at it ({@code PbServiceFacade.sceneTerminal} follows that link and shows the
 * node's {@code composite} artifact), an artifact it produced is a location's or variant's
 * {@code approvedRender}, or an artifact it produced is referenced - by {@code artifact.supersedes} or
 * {@code binding.sourceArtifact} - or the node itself by {@code binding.sourceNode}, from a node that is
 * not itself a deletable legacy node. The last rule is a fixpoint: a legacy node referenced only from
 * another deletable legacy node goes with it; one referenced from a retained legacy node stays.</li>
 * <li><b>{@code PB1_LEGACY_BINDING}</b> / <b>{@code PB1_LEGACY_ARTIFACT}</b> - the bindings (by
 * {@code node} and by {@code sourceNode}) and artifacts (by {@code producedByNode}) of a deletable
 * legacy node, exactly the set {@code PbDeleteUtil.deleteNodeGraph} removes. An artifact's
 * {@code data.data} goes with it when no other artifact shares it. Also any artifact carrying the
 * {@code referenceStrip} role ({@link PbPipelineUtil#ROLE_REFERENCE_STRIP}) whose producer is not a
 * legacy node, under the same reference rules.</li>
 * <li><b>{@code PB1_SETTINGS_ROW}</b> - an {@code olio.sd.config} named {@code book-sdConfig} or
 * {@code book-compositeSdConfig}: {@code PictureBookUtil.persistBookSdConfigFk} wrote one per book
 * <i>before</i> checking for an {@code olio.pb.book} row and returned early for PB1 books, so PB1 books
 * left settings rows nothing references. Deletable only when no {@code olio.pb.book.sdConfig} /
 * {@code compositeSdConfig} and no {@code olio.pb.style.sdConfig} points at it <i>and</i> its group holds
 * no {@code olio.pb.book} at all (a V3 book's unlinked row is adopted by
 * {@code PbBookUtil.setBookSdConfig} on the next save, so it is that book's, not PB1 debris); otherwise
 * it is retained with its referrer.</li>
 * <li><b>{@code PB1_KONTEXT_CONFIG}</b> - an {@code olio.sd.config} whose {@code compositeMode} is the
 * retired literal {@code kontext} (refused as unsupported since P10-3). Same reference rule: an
 * unreferenced one is deletable; a referenced one is retained and reported with its referrer, so the
 * owner can repoint it, and is never deleted here.</li>
 * </ul>
 * The scan is typed on the two enum constants, so it can run only while {@code PbNodeTypeEnumType}
 * still declares them - which is also the condition under which those rows can be read at all
 * ({@code EnumValueType.setValue} rejects a persisted value the enum no longer declares and
 * {@code StatementUtil.populateRecord} then fails the whole result set). The constants therefore go
 * only after every served database reports zero legacy nodes, retained included.
 * <p>
 * Cross-JVM bound: a purge run from Console7 deletes through this JVM's {@code AccessPoint}; a running
 * AccountManagerService7 keeps its own in-process {@code CacheDBSearch} entries until they expire or it
 * is restarted. The Console7 action logs that.
 */
public class PbLegacyCleanupUtil {
	private static final Logger logger = LogManager.getLogger(PbLegacyCleanupUtil.class);

	public static final String PB1_LEGACY_NODE = "PB1_LEGACY_NODE";
	public static final String PB1_LEGACY_BINDING = "PB1_LEGACY_BINDING";
	public static final String PB1_LEGACY_ARTIFACT = "PB1_LEGACY_ARTIFACT";
	public static final String PB1_SETTINGS_ROW = "PB1_SETTINGS_ROW";
	public static final String PB1_KONTEXT_CONFIG = "PB1_KONTEXT_CONFIG";

	/** Dependents before the rows they hang off, so no delete is refused for a child/FK. */
	public static final String[] PURGE_ORDER = new String[] {
		PB1_LEGACY_BINDING, PB1_LEGACY_ARTIFACT, PB1_LEGACY_NODE, PB1_SETTINGS_ROW, PB1_KONTEXT_CONFIG
	};

	public static final String OUTCOME_DELETED = "deleted";
	public static final String OUTCOME_DENIED = "denied";
	public static final String OUTCOME_FAILED = "failed";

	/** The node types only the removed pipeline produced (P10-1 note in design §37.5). */
	public static final PbNodeTypeEnumType[] LEGACY_NODE_TYPES = new PbNodeTypeEnumType[] {
		PbNodeTypeEnumType.REFERENCE_STRIP, PbNodeTypeEnumType.COMPOSITE
	};

	/** The names {@code PictureBookUtil.setBookSdConfig} gives a book's persisted settings rows. */
	public static final String[] SETTINGS_ROW_NAMES = new String[] {"book-sdConfig", "book-compositeSdConfig"};

	/** The retired {@code compositeMode} literal (design §37.3; unsupported since P10-3). */
	public static final String LEGACY_COMPOSITE_MODE = "kontext";

	public static String describe(String code) {
		switch(code) {
			case PB1_LEGACY_NODE: return "olio.pb.node of type REFERENCE_STRIP or COMPOSITE that nothing live references";
			case PB1_LEGACY_BINDING: return "olio.pb.binding by node or sourceNode of a deletable legacy node";
			case PB1_LEGACY_ARTIFACT: return "olio.pb.artifact produced by a deletable legacy node, or a referenceStrip-role artifact nothing references (its data.data goes with it when unshared)";
			case PB1_SETTINGS_ROW: return "olio.sd.config named book-sdConfig / book-compositeSdConfig that no olio.pb.book or olio.pb.style references";
			case PB1_KONTEXT_CONFIG: return "olio.sd.config with compositeMode 'kontext' that no olio.pb.book or olio.pb.style references";
			default: return code;
		}
	}

	// ─────────────────────────────── public API ───────────────────────────────

	/**
	 * Read-only legacy report for the caller's organization.
	 *
	 * @return {@code {scope:"org", organizationId, checkedAt, categories:[{code, count, items:[{code, model,
	 *         id, objectId, name, reason, ...}]}], retained:[{code, model, id, objectId, name, reason}],
	 *         total, retainedTotal, warnings}}
	 * @throws PictureBookException 401 without a user, 403 for a non-administrator
	 */
	public static Map<String, Object> scan(BaseRecord user) {
		long orgId = requireAdmin(user);
		Scan s = doScan(orgId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", "org");
		out.put("organizationId", orgId);
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
		List<Map<String, Object>> retained = new ArrayList<>();
		for(Item it : s.retained) {
			retained.add(it.toMap());
		}
		out.put("retained", retained);
		out.put("total", total);
		out.put("retainedTotal", retained.size());
		out.put("warnings", new ArrayList<>(s.warnings));
		return out;
	}

	/**
	 * Re-scan, then delete every deletable item in the selected categories ({@code codes} null or empty =
	 * all), in {@link #PURGE_ORDER}. Retained items are never touched.
	 *
	 * @return {@code {scope:"org", organizationId, purgedAt, codes, results:[item + outcome, reason, count],
	 *         deleted, denied, failed, retainedTotal, warnings}}
	 * @throws PictureBookException 401 without a user, 403 for a non-administrator
	 */
	public static Map<String, Object> purge(BaseRecord user, List<String> codes) {
		long orgId = requireAdmin(user);
		Set<String> selected = new LinkedHashSet<>();
		if(codes == null || codes.isEmpty()) {
			selected.addAll(Arrays.asList(PURGE_ORDER));
		}
		else {
			for(String c : codes) {
				if(c != null && Arrays.asList(PURGE_ORDER).contains(c.trim())) {
					selected.add(c.trim());
				}
			}
		}
		Scan s = doScan(orgId);
		BaseRecord olio = s.olioUser != null ? s.olioUser : user;
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
					o = purgeItem(user, olio, orgId, s, it);
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
		if(!results.isEmpty()) {
			CacheUtil.clearCache();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("scope", "org");
		out.put("organizationId", orgId);
		out.put("purgedAt", ZonedDateTime.now().toString());
		out.put("codes", new ArrayList<>(selected));
		out.put("results", results);
		out.put("deleted", deleted);
		out.put("denied", denied);
		out.put("failed", failed);
		out.put("retainedTotal", s.retained.size());
		out.put("warnings", new ArrayList<>(s.warnings));
		return out;
	}

	// ─────────────────────────────── scan ───────────────────────────────

	private static class Item {
		final String code;
		final BaseRecord rec;
		final String model;
		final String reason;
		/** {@code data.data} id of a deletable artifact, 0 when it has none. */
		long dataId = 0L;
		/** Extra report columns (node type, role, config mode, referrer). */
		final Map<String, Object> extra = new LinkedHashMap<>();

		Item(String code, BaseRecord rec, String model, String reason) {
			this.code = code;
			this.rec = rec;
			this.model = model;
			this.reason = reason;
		}

		long id() {
			Object o = rec.hasField(FieldNames.FIELD_ID) ? rec.get(FieldNames.FIELD_ID) : null;
			return (o instanceof Number) ? ((Number) o).longValue() : 0L;
		}

		String objectId() {
			Object o = rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null;
			return o == null ? null : o.toString();
		}

		String name() {
			Object o = rec.hasField(FieldNames.FIELD_NAME) ? rec.get(FieldNames.FIELD_NAME) : null;
			return o == null ? null : o.toString();
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", code);
			m.put("model", model);
			m.put("id", id());
			m.put("objectId", objectId());
			m.put("name", name());
			m.putAll(extra);
			if(dataId > 0L) {
				m.put("dataId", dataId);
			}
			m.put("reason", reason);
			return m;
		}
	}

	private static class Scan {
		BaseRecord olioUser;
		final Map<String, List<Item>> byCode = new LinkedHashMap<>();
		final List<Item> retained = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();

		List<Item> items(String code) {
			return byCode.computeIfAbsent(code, k -> new ArrayList<>());
		}

		void add(Item it) {
			items(it.code).add(it);
		}
	}

	/** One legacy node with everything hanging off it and the reasons, if any, it has to stay. */
	private static class LegacyNode {
		final BaseRecord rec;
		final long id;
		final String type;
		final List<BaseRecord> artifacts = new ArrayList<>();
		final List<BaseRecord> bindings = new ArrayList<>();
		/** Reasons that hold unconditionally (scene link, approved render, non-legacy referrer). */
		final List<String> hardReasons = new ArrayList<>();
		/** Reasons that hold only if the named legacy node (by id) is itself retained. */
		final List<Map.Entry<Long, String>> conditionalReasons = new ArrayList<>();
		String retainedFor = null;

		LegacyNode(BaseRecord rec, String type) {
			this(rec, type, PbHealthUtil.idOf(rec));
		}

		LegacyNode(BaseRecord rec, String type, long id) {
			this.rec = rec;
			this.type = type;
			this.id = id;
		}
	}

	private static Scan doScan(long orgId) {
		IOContext ioContext = IOSystem.getActiveContext();
		Scan s = new Scan();
		s.olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(s.olioUser == null) {
			s.warnings.add("No olio principal in organization " + orgId + "; deletes run as the caller only");
		}
		scanLegacyNodes(orgId, s);
		scanStripArtifacts(orgId, s);
		scanSdConfigs(orgId, s);
		return s;
	}

	private static void scanLegacyNodes(long orgId, Scan s) {
		Map<Long, LegacyNode> nodes = new LinkedHashMap<>();
		for(PbNodeTypeEnumType t : LEGACY_NODE_TYPES) {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_NODE_TYPE, t.toString());
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
				FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_HANDLE, OlioFieldNames.FIELD_PB_NODE_TYPE});
			for(BaseRecord n : raw(q, OlioModelNames.MODEL_PB_NODE)) {
				LegacyNode ln = new LegacyNode(n, t.toString());
				if(ln.id > 0L) {
					nodes.put(ln.id, ln);
				}
			}
		}
		if(nodes.isEmpty()) {
			return;
		}

		/// Dependents and references, per node.
		for(LegacyNode ln : nodes.values()) {
			ln.artifacts.addAll(rawByFk(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, ln.rec, orgId,
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME,
				OlioFieldNames.FIELD_PB_ROLE, FieldNames.FIELD_DATA));
			ln.bindings.addAll(rawByFk(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE, ln.rec, orgId,
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME));

			for(BaseRecord scene : PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE, ln.rec, orgId)) {
				ln.hardReasons.add("scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + " links to it (scene.sceneNode); V3 reads its composite artifact");
			}
			/// Bindings of OTHER nodes that consume this node's current output.
			for(BaseRecord b : rawByFk(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_SOURCE_NODE, ln.rec, orgId,
					FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_NODE)) {
				long consumer = PbHealthUtil.fkId(b, OlioFieldNames.FIELD_PB_NODE);
				if(consumer == ln.id) {
					continue;
				}
				if(nodes.containsKey(consumer)) {
					ln.conditionalReasons.add(Map.entry(consumer, "binding " + b.get(FieldNames.FIELD_OBJECT_ID) + " of legacy node " + consumer + " consumes it (binding.sourceNode)"));
					ln.bindings.add(b);
				}
				else {
					ln.hardReasons.add("binding " + b.get(FieldNames.FIELD_OBJECT_ID) + " of node " + consumer + " consumes it (binding.sourceNode)");
				}
			}
			for(BaseRecord a : ln.artifacts) {
				collectArtifactReferences(a, ln, nodes, orgId);
			}
		}

		/// Fixpoint over the conditional reasons.
		for(LegacyNode ln : nodes.values()) {
			if(!ln.hardReasons.isEmpty()) {
				ln.retainedFor = ln.hardReasons.get(0);
			}
		}
		boolean changed = true;
		while(changed) {
			changed = false;
			for(LegacyNode ln : nodes.values()) {
				if(ln.retainedFor != null) {
					continue;
				}
				for(Map.Entry<Long, String> cr : ln.conditionalReasons) {
					LegacyNode ref = nodes.get(cr.getKey());
					if(ref != null && ref.retainedFor != null) {
						ln.retainedFor = cr.getValue() + ", and that node is retained";
						changed = true;
						break;
					}
				}
			}
		}

		/// Items.
		Set<Long> seenBindings = new LinkedHashSet<>();
		Set<Long> seenArtifacts = new LinkedHashSet<>();
		for(LegacyNode ln : nodes.values()) {
			String handle = null;
			if(ln.rec.hasField(OlioFieldNames.FIELD_PB_HANDLE)) {
				handle = ln.rec.get(OlioFieldNames.FIELD_PB_HANDLE);
			}
			if(ln.retainedFor != null) {
				Item it = new Item(PB1_LEGACY_NODE, ln.rec, OlioModelNames.MODEL_PB_NODE, ln.retainedFor);
				it.extra.put("nodeType", ln.type);
				it.extra.put("handle", handle);
				it.extra.put("artifacts", ln.artifacts.size());
				it.extra.put("bindings", ln.bindings.size());
				s.retained.add(it);
				continue;
			}
			for(BaseRecord b : ln.bindings) {
				long bid = PbHealthUtil.idOf(b);
				if(bid > 0L && seenBindings.add(bid)) {
					s.add(new Item(PB1_LEGACY_BINDING, b, OlioModelNames.MODEL_PB_BINDING, "binding of legacy " + ln.type + " node " + handle));
				}
			}
			for(BaseRecord a : ln.artifacts) {
				long aid = PbHealthUtil.idOf(a);
				if(aid > 0L && seenArtifacts.add(aid)) {
					Item it = new Item(PB1_LEGACY_ARTIFACT, a, OlioModelNames.MODEL_PB_ARTIFACT, "produced by legacy " + ln.type + " node " + handle);
					Object role = a.hasField(OlioFieldNames.FIELD_PB_ROLE) ? a.get(OlioFieldNames.FIELD_PB_ROLE) : null;
					it.extra.put("role", role);
					it.dataId = PbHealthUtil.fkId(a, FieldNames.FIELD_DATA);
					s.add(it);
				}
			}
			Item it = new Item(PB1_LEGACY_NODE, ln.rec, OlioModelNames.MODEL_PB_NODE, "legacy " + ln.type + " node; no scene, approved render, binding or artifact outside this set references it");
			it.extra.put("nodeType", ln.type);
			it.extra.put("handle", handle);
			it.extra.put("artifacts", ln.artifacts.size());
			it.extra.put("bindings", ln.bindings.size());
			s.add(it);
		}
	}

	/**
	 * Records on {@code owner} every reason artifact {@code a} is still referenced: as an approved render,
	 * by {@code artifact.supersedes}, or by {@code binding.sourceArtifact}. A referrer that belongs to
	 * another legacy node is conditional on that node; any other referrer is a hard reason.
	 */
	private static void collectArtifactReferences(BaseRecord a, LegacyNode owner, Map<Long, LegacyNode> nodes, long orgId) {
		String aoid = a.get(FieldNames.FIELD_OBJECT_ID);
		for(BaseRecord loc : PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_APPROVED_RENDER, a, orgId)) {
			owner.hardReasons.add("artifact " + aoid + " is the approved render of location " + loc.get(FieldNames.FIELD_OBJECT_ID));
		}
		for(BaseRecord v : PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_LOCATION_VARIANT, OlioFieldNames.FIELD_PB_APPROVED_RENDER, a, orgId)) {
			owner.hardReasons.add("artifact " + aoid + " is the approved render of location variant " + v.get(FieldNames.FIELD_OBJECT_ID));
		}
		for(BaseRecord r : rawByFk(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_SUPERSEDES, a, orgId,
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE)) {
			long producer = PbHealthUtil.fkId(r, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
			String why = "artifact " + r.get(FieldNames.FIELD_OBJECT_ID) + " supersedes artifact " + aoid;
			if(owner.id > 0L && producer == owner.id) {
				continue;
			}
			if(nodes.containsKey(producer)) {
				owner.conditionalReasons.add(Map.entry(producer, why + " (produced by legacy node " + producer + ")"));
			}
			else {
				owner.hardReasons.add(why);
			}
		}
		for(BaseRecord b : rawByFk(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT, a, orgId,
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_NODE)) {
			long consumer = PbHealthUtil.fkId(b, OlioFieldNames.FIELD_PB_NODE);
			String why = "binding " + b.get(FieldNames.FIELD_OBJECT_ID) + " of node " + consumer + " consumes artifact " + aoid + " (binding.sourceArtifact)";
			if(owner.id > 0L && consumer == owner.id) {
				continue;
			}
			if(nodes.containsKey(consumer)) {
				owner.conditionalReasons.add(Map.entry(consumer, why));
			}
			else {
				owner.hardReasons.add(why);
			}
		}
	}

	/** {@code referenceStrip}-role artifacts whose producer is not a legacy node (or is absent). */
	private static void scanStripArtifacts(long orgId, Scan s) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_ROLE, PbPipelineUtil.ROLE_REFERENCE_STRIP);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_ROLE, FieldNames.FIELD_DATA, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE});
		Set<Long> already = new LinkedHashSet<>();
		for(Item it : s.items(PB1_LEGACY_ARTIFACT)) {
			already.add(it.id());
		}
		Set<Long> retainedNodeIds = new LinkedHashSet<>();
		for(Item it : s.retained) {
			if(PB1_LEGACY_NODE.equals(it.code)) {
				retainedNodeIds.add(it.id());
			}
		}
		for(BaseRecord a : raw(q, OlioModelNames.MODEL_PB_ARTIFACT)) {
			long aid = PbHealthUtil.idOf(a);
			if(aid <= 0L || already.contains(aid)) {
				continue;
			}
			long producer = PbHealthUtil.fkId(a, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
			if(retainedNodeIds.contains(producer)) {
				/// Part of a retained legacy node's graph; it stays with the node.
				continue;
			}
			/// A stand-in owner with no node id, so every referrer counts as a hard reason.
			LegacyNode probe = new LegacyNode(a, "artifact", 0L);
			collectArtifactReferences(a, probe, new LinkedHashMap<>(), orgId);
			Item it = new Item(PB1_LEGACY_ARTIFACT, a, OlioModelNames.MODEL_PB_ARTIFACT,
				probe.hardReasons.isEmpty()
					? "referenceStrip-role artifact (producer " + (producer > 0L ? "node " + producer : "absent") + " is not a legacy node); nothing references it"
					: probe.hardReasons.get(0));
			it.extra.put("role", PbPipelineUtil.ROLE_REFERENCE_STRIP);
			it.extra.put("producedByNode", producer);
			if(probe.hardReasons.isEmpty()) {
				it.dataId = PbHealthUtil.fkId(a, FieldNames.FIELD_DATA);
				s.add(it);
			}
			else {
				s.retained.add(it);
			}
		}
	}

	/** Settings rows by name and Kontext configs by mode, both held only when nothing references them. */
	private static void scanSdConfigs(long orgId, Scan s) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_OWNER_ID, "compositeMode"});
		List<BaseRecord> configs = raw(q, OlioModelNames.MODEL_SD_CONFIG);
		for(BaseRecord cfg : configs) {
			String name = null;
			if(cfg.hasField(FieldNames.FIELD_NAME)) {
				name = cfg.get(FieldNames.FIELD_NAME);
			}
			Object modeObj = cfg.hasField("compositeMode") ? cfg.get("compositeMode") : null;
			String mode = modeObj == null ? null : modeObj.toString().trim();
			boolean settingsRow = name != null && Arrays.asList(SETTINGS_ROW_NAMES).contains(name);
			boolean kontext = mode != null && LEGACY_COMPOSITE_MODE.equalsIgnoreCase(mode);
			if(!settingsRow && !kontext) {
				continue;
			}
			List<String> referrers = sdConfigReferrers(cfg, orgId);
			if(settingsRow && referrers.isEmpty()) {
				/// An unlinked settings row in a group that holds an olio.pb.book is that V3 book's, not PB1
				/// debris: PbBookUtil.setBookSdConfig adopts an unlinked book-sdConfig row on the next save.
				/// PB1 books have no olio.pb.book row, which is what makes their rows orphans.
				for(BaseRecord bk : booksInGroup(cfg, orgId)) {
					referrers.add("olio.pb.book " + bk.get(FieldNames.FIELD_OBJECT_ID) + " (same group; unlinked, adoptable by setBookSdConfig)");
				}
			}
			String code = settingsRow ? PB1_SETTINGS_ROW : PB1_KONTEXT_CONFIG;
			Item it;
			if(referrers.isEmpty()) {
				it = new Item(code, cfg, OlioModelNames.MODEL_SD_CONFIG,
					settingsRow
						? "PB1 settings row '" + name + "'; no olio.pb.book or olio.pb.style references it" + (kontext ? " (compositeMode kontext)" : "")
						: "compositeMode '" + mode + "' is retired; no olio.pb.book or olio.pb.style references it");
			}
			else {
				it = new Item(code, cfg, OlioModelNames.MODEL_SD_CONFIG,
					(settingsRow ? "settings row '" + name + "'" : "compositeMode '" + mode + "'")
						+ " is still referenced by " + String.join(", ", referrers) + "; never deleted here"
						+ (kontext ? " - repoint compositeMode to flux2|classic on the referrer's config" : ""));
			}
			it.extra.put("compositeMode", mode);
			if(!referrers.isEmpty()) {
				it.extra.put("referencedBy", referrers);
				s.retained.add(it);
			}
			else {
				s.add(it);
			}
		}
	}

	/** The {@code olio.pb.book} rows in the config's own group (a V3 book; PB1 books have none). */
	private static List<BaseRecord> booksInGroup(BaseRecord cfg, long orgId) {
		Object g = cfg.hasField(FieldNames.FIELD_GROUP_ID) ? cfg.get(FieldNames.FIELD_GROUP_ID) : null;
		long groupId = (g instanceof Number) ? ((Number) g).longValue() : 0L;
		if(groupId <= 0L) {
			return new ArrayList<>();
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME});
		return raw(q, OlioModelNames.MODEL_PB_BOOK);
	}

	private static List<String> sdConfigReferrers(BaseRecord cfg, long orgId) {
		List<String> out = new ArrayList<>();
		for(BaseRecord b : PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SD_CONFIG, cfg, orgId)) {
			out.add("olio.pb.book " + b.get(FieldNames.FIELD_OBJECT_ID) + " (sdConfig)");
		}
		for(BaseRecord b : PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG, cfg, orgId)) {
			out.add("olio.pb.book " + b.get(FieldNames.FIELD_OBJECT_ID) + " (compositeSdConfig)");
		}
		for(BaseRecord st : PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_STYLE, OlioFieldNames.FIELD_PB_SD_CONFIG, cfg, orgId)) {
			out.add("olio.pb.style " + st.get(FieldNames.FIELD_OBJECT_ID) + " (sdConfig)");
		}
		return out;
	}

	// ─────────────────────────────── purge ───────────────────────────────

	private static class Outcome {
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

	private static Outcome purgeItem(BaseRecord user, BaseRecord olio, long orgId, Scan s, Item it) {
		switch(it.code) {
			case PB1_LEGACY_BINDING:
			case PB1_LEGACY_NODE:
			case PB1_SETTINGS_ROW:
			case PB1_KONTEXT_CONFIG:
				return Outcome.of(deleteAsEither(olio, user, it.rec), 1);
			case PB1_LEGACY_ARTIFACT: {
				DeleteResult d = deleteAsEither(olio, user, it.rec);
				if(!d.deleted) {
					return Outcome.of(d, 0);
				}
				int n = 1;
				if(it.dataId > 0L) {
					n += deleteDataIfUnshared(user, olio, it.dataId, orgId, s);
				}
				return Outcome.deleted(n);
			}
			default:
				return Outcome.failed("Unknown category " + it.code);
		}
	}

	/** The artifact's {@code data.data}, once no artifact (this one is already gone) still points at it. */
	private static int deleteDataIfUnshared(BaseRecord user, BaseRecord olio, long dataId, long orgId, Scan s) {
		BaseRecord data = rawById(ModelNames.MODEL_DATA, dataId, orgId);
		if(data == null) {
			return 0;
		}
		if(!PbDeleteUtil.findByFk(OlioModelNames.MODEL_PB_ARTIFACT, FieldNames.FIELD_DATA, data, orgId).isEmpty()) {
			return 0;
		}
		DeleteResult d = deleteAsEither(olio, user, data);
		if(!d.deleted) {
			s.warnings.add("data.data " + dataId + " of a deleted artifact was not removed: " + d.reason);
			return 0;
		}
		return 1;
	}

	private static DeleteResult deleteAsEither(BaseRecord first, BaseRecord second, BaseRecord rec) {
		DeleteResult d = PictureBookUtil.deleteRecordExplained(first, rec);
		if(!d.deleted && second != first) {
			d = PictureBookUtil.deleteRecordExplained(second, rec);
		}
		return d;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static long requireAdmin(BaseRecord user) {
		if(user == null) {
			throw new PictureBookException(401, "Not authenticated");
		}
		Object o = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(!(o instanceof Number)) {
			throw new PictureBookException(500, "User record carries no organizationId");
		}
		long orgId = ((Number) o).longValue();
		if(!PbHealthUtil.isAccountAdministrator(user, orgId)) {
			throw new PictureBookException(403, "PB1 legacy cleanup is organization-wide and requires AccountAdministrators");
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

	/** {@link PbDeleteUtil#findByFk} with a caller-chosen projection. */
	private static List<BaseRecord> rawByFk(String model, String fkField, BaseRecord target, long orgId, String... request) {
		if(target == null || target.get(FieldNames.FIELD_ID) == null) {
			return new ArrayList<>();
		}
		Query q = QueryUtil.createQuery(model, fkField, target);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(request);
		return raw(q, model);
	}

	private static BaseRecord rawById(String model, long id, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME});
		q.setCache(false);
		try {
			BaseRecord r = IOSystem.getActiveContext().getSearch().findRecord(q);
			if(r != null && r.getSchema() == null) {
				r.setSchema(model);
			}
			return r;
		}
		catch(Exception e) {
			logger.warn("Raw lookup of " + model + " " + id + " failed: " + e.getMessage());
			return null;
		}
	}
}
