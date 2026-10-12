package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PbStaleUtil;
import org.cote.accountmanager.olio.picturebook.PbWatchedFields;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.ErrorUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Before;
import org.junit.Test;

/// PictureBook V3 phase 1, P1-2: live drift detection ({@link PbStaleUtil}) against the real DB
/// through {@code AccessPoint} as a non-admin user. No LLM, no SD.
///
/// The book world {@code pb2c-graph} is a shared, stable fixture (TestPbGraph's): nothing in it is
/// deleted and no new slug is created. Isolation comes from random node handles and freshly created
/// charPersons; every negative assertion is on nodes created in this run.
public class TestPbStale extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";
	private static final String REFS_GROUP = "~/PbGraphRefs";
	private static final String ROLE_CHARACTER = "character";
	private static final String ROLE_PORTRAIT = "portrait";

	@Before
	public void staleSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
	}

	// ─────────────────────────────── fixture (copied from TestPbGraph) ───────────────────────────────

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private BaseRecord book(BaseRecord u) {
		long orgId = u.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord existing = PbBookUtil.findBookBySlug(u, SLUG, orgId);
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		assertNotNull("Failed to create the " + SLUG + " fixture book", created);
		return created;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("Failed to get/create the workflow", wf);
		return wf;
	}

	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type, int ordinal) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), ordinal);
		assertNotNull("Failed to add node " + handle, n);
		return n;
	}

	private long orgOf(BaseRecord u) {
		return (long) u.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private PbNodeStatusEnumType statusOf(BaseRecord u, BaseRecord node) {
		BaseRecord r = PbGraphUtil.readNode(u, node.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull("Node vanished: " + node.get(FieldNames.FIELD_NAME), r);
		return r.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
	}

	// ─────────────────────────────── stale-specific helpers ───────────────────────────────

	private String bookObjectId(BaseRecord bk) {
		return bk.get(FieldNames.FIELD_OBJECT_ID);
	}

	private String groupPath() {
		return PbBookUtil.workflowGroupPath(SLUG);
	}

	private long idOf(BaseRecord rec) {
		return ((Long) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	/** The user's own reference group (TestPbGraph case12 pattern). */
	private BaseRecord refsGroup(BaseRecord u) {
		BaseRecord grp = ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, REFS_GROUP,
			GroupEnumType.DATA.toString(), orgOf(u));
		assertNotNull("Failed to make/resolve " + REFS_GROUP + " for " + u.get(FieldNames.FIELD_NAME), grp);
		return grp;
	}

	/** A fresh charPerson in the user's reference group (TestPbSceneCharacterRename pattern). */
	private BaseRecord createCharPerson(BaseRecord u, String prefix) throws Exception {
		BaseRecord grp = refsGroup(u);
		String gpath = grp.get(FieldNames.FIELD_PATH);
		String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, gpath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set("firstName", "Before");
		cp.set("lastName", "Drift");
		cp.set("gender", "female");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name + " failed (AccessPoint.create returned null)", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("created charPerson carries no objectId", oid);
		/// Re-read so the record we hand around carries name + objectId + id for later patches.
		BaseRecord full = ioContext.getAccessPoint().findByObjectId(u, OlioModelNames.MODEL_CHAR_PERSON, oid);
		assertNotNull("Could not re-read created charPerson " + oid, full);
		assertEquals(name, full.get(FieldNames.FIELD_NAME));
		return full;
	}

	/** The factory-created olio.statistics record behind a charPerson (foreign; must be projected explicitly). */
	private BaseRecord statisticsOf(BaseRecord u, BaseRecord cp) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "statistics"});
		q.setCache(false);
		BaseRecord withStats = ioContext.getAccessPoint().find(u, q);
		assertNotNull("Could not re-read charPerson with statistics projected", withStats);
		BaseRecord stats = withStats.get("statistics");
		assertNotNull("charPerson carries no statistics record; the foreign-field read rule cannot be satisfied for a viewer", stats);
		long sid = stats.get(FieldNames.FIELD_ID);
		assertTrue("charPerson.statistics reference has no id", sid > 0L);
		return stats;
	}

	/**
	 * PATCH one field of a charPerson with the explicit field-name newInstance idiom: identity + name
	 * (validated, carried from the existing record) + exactly the changed field, nothing else.
	 */
	private void patchCharPerson(BaseRecord u, BaseRecord cp, String field, Object value) throws Exception {
		String name = cp.get(FieldNames.FIELD_NAME);
		assertNotNull("The charPerson we are about to patch carries no name; the patch would fail validation", name);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, field
		});
		patch.set(FieldNames.FIELD_ID, cp.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, name);
		patch.set(field, value);
		assertNotNull("AccessPoint.update of charPerson." + field + " returned null: the edit that should drift the graph was NOT written",
			ioContext.getAccessPoint().update(u, patch));
		/// Prove the edit landed before asserting anything about drift.
		BaseRecord after = ioContext.getAccessPoint().findByObjectId(u, OlioModelNames.MODEL_CHAR_PERSON, cp.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("charPerson vanished after patch", after);
	}

	/** Patch a node to scope=scene / sceneIndex=N (TestPbGraph case16 shape). */
	private void scopeToScene(BaseRecord u, BaseRecord node, int sceneIndex) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCENE_INDEX
		});
		patch.set(FieldNames.FIELD_ID, node.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, node.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, node.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, sceneIndex);
		assertNotNull("AccessPoint.update of node scope/sceneIndex returned null for " + node.get(FieldNames.FIELD_NAME),
			ioContext.getAccessPoint().update(u, patch));
		BaseRecord r = PbGraphUtil.readNode(u, node.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull(r);
		assertEquals("scope did not persist", PbPipelineUtil.SCOPE_SCENE, r.get(OlioFieldNames.FIELD_PB_SCOPE));
		assertEquals("sceneIndex did not persist", Integer.valueOf(sceneIndex), r.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
	}

	/**
	 * "Render" a node: compute its inputHash over its CURRENT bindings and scope, persist it, and mark
	 * DONE. Must be called after every binding/scope change the test makes to that node.
	 */
	private String markRendered(BaseRecord u, BaseRecord node, BaseRecord bk) {
		BaseRecord fresh = PbGraphUtil.readNode(u, node.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull("Node vanished before render: " + node.get(FieldNames.FIELD_NAME), fresh);
		String hash = PbGraphUtil.computeInputHash(u, fresh, bk);
		assertNotNull("computeInputHash returned null", hash);
		assertTrue("persistInputHash returned false: the node would never become eligible for staleness",
			PbGraphUtil.persistInputHash(u, fresh, hash));
		assertTrue("persistStatus(DONE) returned false", PbGraphUtil.persistStatus(u, fresh, PbNodeStatusEnumType.DONE));
		assertEquals("Node should read back DONE after render", PbNodeStatusEnumType.DONE, statusOf(u, fresh));
		return hash;
	}

	private BaseRecord bindCharacter(BaseRecord u, BaseRecord wf, BaseRecord node, BaseRecord cp) {
		BaseRecord b = PbGraphUtil.addRecordBinding(u, wf, node, ROLE_CHARACTER, 0,
			OlioModelNames.MODEL_CHAR_PERSON, cp.get(FieldNames.FIELD_OBJECT_ID), groupPath());
		assertNotNull("addRecordBinding returned null for " + node.get(FieldNames.FIELD_NAME), b);
		assertNotNull("The record binding captured no refHash at bind time; drift could never be detected",
			b.get(OlioFieldNames.FIELD_PB_REF_HASH));
		return b;
	}

	private Map<String, Object> findSummary(List<Map<String, Object>> list, BaseRecord node) {
		String oid = node.get(FieldNames.FIELD_OBJECT_ID);
		for(Map<String, Object> m : list) {
			if(oid.equals(m.get("objectId"))) {
				return m;
			}
		}
		return null;
	}

	private BaseRecord bindingWithRole(BaseRecord u, BaseRecord node, String role) {
		for(BaseRecord b : PbGraphUtil.listBindings(u, node)) {
			if(role.equals(b.get(OlioFieldNames.FIELD_PB_ROLE))) {
				return b;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private List<Integer> intList(Object o) {
		assertNotNull("expected a list of scene indexes, got null", o);
		List<Integer> out = new ArrayList<>();
		for(Object x : (List<Object>) o) {
			out.add(((Number) x).intValue());
		}
		return out;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void case01_charPersonPatchDriftsAndPropagatesToScenes() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		String bookOid = bookObjectId(bk);

		BaseRecord p = node(u, wf, "c01-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord s = node(u, wf, "c01-scene", PbNodeTypeEnumType.SCENE_PROMPT, 1);
		BaseRecord c = createCharPerson(u, "c01-char");
		String cOid = c.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord pBind = bindCharacter(u, wf, p, c);
		String refHashAtBind = pBind.get(OlioFieldNames.FIELD_PB_REF_HASH);
		bindCharacter(u, wf, s, c);
		assertNotNull("addBinding S<-P returned null",
			PbGraphUtil.addBinding(u, wf, s, ROLE_PORTRAIT, 0, p, null, groupPath()));
		scopeToScene(u, s, 3);

		markRendered(u, p, bk);
		markRendered(u, s, bk);

		PbStaleUtil.Scan pre = PbStaleUtil.scan(u, bk, wf, true);
		assertFalse("Precondition: freshly rendered P must not be stale before any edit", pre.entries.containsKey(idOf(p)));
		assertFalse("Precondition: freshly rendered S must not be stale before any edit", pre.entries.containsKey(idOf(s)));

		patchCharPerson(u, c, "firstName", "After");
		PbStaleUtil.clear();

		List<Map<String, Object>> stale = PbServiceFacade.listStale(u, bookOid);
		Map<String, Object> pm = findSummary(stale, p);
		Map<String, Object> sm = findSummary(stale, s);
		assertNotNull("P (bound to the edited charPerson) is missing from listStale: " + stale, pm);
		assertNotNull("S (bound to the edited charPerson, downstream of P) is missing from listStale: " + stale, sm);

		assertEquals("P's reason", PbStaleUtil.REASON_REF_DRIFT, pm.get("reason"));
		assertEquals("S's reason (S is itself bound to C, so it is a drift root, not merely propagated)",
			PbStaleUtil.REASON_REF_DRIFT, sm.get("reason"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> pDrift = (List<Map<String, Object>>) pm.get("driftedBindings");
		assertNotNull("P reports no driftedBindings", pDrift);
		assertEquals("P should report exactly one drifted binding", 1, pDrift.size());
		assertEquals(ROLE_CHARACTER, pDrift.get(0).get("role"));
		assertEquals(OlioModelNames.MODEL_CHAR_PERSON, pDrift.get(0).get("refModel"));
		assertEquals(cOid, pDrift.get(0).get("refObjectId"));

		assertTrue("P's affectedSceneIndexes should contain scene 3 (S is downstream): " + pm.get("affectedSceneIndexes"),
			intList(pm.get("affectedSceneIndexes")).contains(3));
		assertTrue("S's affectedSceneIndexes should contain its own scene 3: " + sm.get("affectedSceneIndexes"),
			intList(sm.get("affectedSceneIndexes")).contains(3));

		assertEquals("P's stored status must be STALE after the scan persisted it", PbNodeStatusEnumType.STALE, statusOf(u, p));
		assertEquals("S's stored status must be STALE after the scan persisted it", PbNodeStatusEnumType.STALE, statusOf(u, s));

		BaseRecord pBindFresh = bindingWithRole(u, p, ROLE_CHARACTER);
		assertNotNull("P's character binding vanished", pBindFresh);
		assertEquals("The scan must never rewrite binding.refHash (that is the regenerate step's job)",
			refHashAtBind, pBindFresh.get(OlioFieldNames.FIELD_PB_REF_HASH));

		PbStaleUtil.Scan post = PbStaleUtil.scan(u, bk, wf, true);
		assertTrue("markPersisted should be true for the owning user", post.markPersisted);
	}

	@Test
	public void case02_secondScanIsIdempotent() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord p = node(u, wf, "c02-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord s = node(u, wf, "c02-scene", PbNodeTypeEnumType.SCENE_PROMPT, 1);
		BaseRecord c = createCharPerson(u, "c02-char");
		bindCharacter(u, wf, p, c);
		assertNotNull(PbGraphUtil.addBinding(u, wf, s, ROLE_PORTRAIT, 0, p, null, groupPath()));
		scopeToScene(u, s, 4);
		markRendered(u, p, bk);
		markRendered(u, s, bk);

		patchCharPerson(u, c, "lastName", "Changed");
		PbStaleUtil.clear();

		PbStaleUtil.Scan first = PbStaleUtil.scan(u, bk, wf, true);
		PbStaleUtil.Entry p1 = first.entries.get(idOf(p));
		PbStaleUtil.Entry s1 = first.entries.get(idOf(s));
		assertNotNull("P not stale after a watched-field edit", p1);
		assertNotNull("S (downstream of P) not stale after a watched-field edit", s1);
		assertEquals(PbStaleUtil.REASON_REF_DRIFT, p1.reason);
		assertEquals("S is not bound to C itself, so its reason must be propagated", PbStaleUtil.REASON_PROPAGATED, s1.reason);
		assertTrue("first scan markPersisted", first.markPersisted);
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, p));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, s));

		PbStaleUtil.Scan second = PbStaleUtil.scan(u, bk, wf, true);
		assertNotSame("a forced scan must compute, not return the cached object", first, second);
		PbStaleUtil.Entry p2 = second.entries.get(idOf(p));
		PbStaleUtil.Entry s2 = second.entries.get(idOf(s));
		assertNotNull("P dropped out of the report on the second scan", p2);
		assertNotNull("S dropped out of the report on the second scan", s2);
		/// P still carries a drifted refHash (the scan never re-stamps bindings; only a re-render does), so
		/// its reason is stable across scans.
		assertEquals("P's reason changed between scans", p1.reason, p2.reason);
		/// S is stored STALE on the second scan (written by the first) and has no drift of its own, but it
		/// is still downstream of P, which is still a live root this scan, so "propagated" outranks
		/// "marked" (P1-2 verifier Q4, 2026-10-10: the first cut reported "marked" here). "marked" is
		/// reserved for a stored STALE that nothing in the current scan explains.
		assertEquals("S on the first scan is downstream-only", PbStaleUtil.REASON_PROPAGATED, s1.reason);
		assertEquals("S on the second scan is still downstream of the live root P, so it must stay 'propagated'",
			PbStaleUtil.REASON_PROPAGATED, s2.reason);
		assertEquals(p1.affectedSceneIndexes, p2.affectedSceneIndexes);
		assertEquals(s1.affectedSceneIndexes, s2.affectedSceneIndexes);
		assertTrue("second scan markPersisted", second.markPersisted);
		assertTrue("already-persisted entries report persisted", p2.persisted && s2.persisted);
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, p));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, s));
	}

	@Test
	public void case03_cacheServesWithinTtlAndTouchInvalidates() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord n = node(u, wf, "c03-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord c = createCharPerson(u, "c03-char");
		String cOid = c.get(FieldNames.FIELD_OBJECT_ID);
		bindCharacter(u, wf, n, c);

		PbStaleUtil.Scan a = PbStaleUtil.scan(u, bk, wf, true);
		assertTrue("scan a must index C's refKey or touch(C) could never find this workflow",
			a.refKeys.contains(OlioModelNames.MODEL_CHAR_PERSON + ":" + cOid));
		PbStaleUtil.Scan b = PbStaleUtil.scan(u, bk, wf, false);
		assertSame("an unforced scan inside the TTL must return the cached object", a, b);
		assertSame(a, PbStaleUtil.cached(idOf(wf)));

		PbStaleUtil.touch(OlioModelNames.MODEL_CHAR_PERSON, cOid);
		assertNull("touch(C) must drop the cached scan of every workflow bound to C", PbStaleUtil.cached(idOf(wf)));
		PbStaleUtil.Scan d = PbStaleUtil.scan(u, bk, wf, false);
		assertNotSame("after touch(C) an unforced scan must recompute", a, d);
		assertTrue("the recomputed scan's timestamp must not precede the original's", d.at >= a.at);

		PbStaleUtil.clear();
		PbStaleUtil.Scan e = PbStaleUtil.scan(u, bk, wf, false);
		assertNotSame("after clear() an unforced scan must recompute", d, e);
		assertTrue(e.at >= d.at);
	}

	@Test
	public void case04_neverRenderedNodeIsNeverStale() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord n = node(u, wf, "c04-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord c = createCharPerson(u, "c04-char");
		bindCharacter(u, wf, n, c);
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull(fresh);
		assertNull("Precondition: a never-rendered node has no inputHash", fresh.get(OlioFieldNames.FIELD_PB_INPUT_HASH));

		patchCharPerson(u, c, "firstName", "After");
		PbStaleUtil.clear();

		PbStaleUtil.Scan scan = PbStaleUtil.scan(u, bk, wf, true);
		assertFalse("A node that has never succeeded must not be reported stale", scan.entries.containsKey(idOf(n)));
		PbNodeStatusEnumType st = scan.statuses.get(idOf(n));
		assertTrue("never-rendered node status should be READY or PENDING, was " + st,
			st == PbNodeStatusEnumType.READY || st == PbNodeStatusEnumType.PENDING);
		PbNodeStatusEnumType stored = statusOf(u, n);
		assertTrue("stored status must not have been written STALE, was " + stored, stored != PbNodeStatusEnumType.STALE);
	}

	@Test
	public void case05_unwatchedFieldEditDoesNotDrift() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord n = node(u, wf, "c05-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord c = createCharPerson(u, "c05-char");
		bindCharacter(u, wf, n, c);
		markRendered(u, n, bk);

		patchCharPerson(u, c, FieldNames.FIELD_DESCRIPTION, "An unwatched edit " + UUID.randomUUID());
		BaseRecord after = ioContext.getAccessPoint().findByObjectId(u, OlioModelNames.MODEL_CHAR_PERSON, c.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull(after);
		/// Typed locals: assertEquals(x.get(..), y.get(..)) infers Object[] from the generic get() and binds
		/// the deprecated assertEquals(Object[], Object[]) overload, which ClassCastExceptions.
		String nameBefore = c.get(FieldNames.FIELD_NAME);
		String nameAfter = after.get(FieldNames.FIELD_NAME);
		assertEquals("name must be unchanged by the description patch", nameBefore, nameAfter);
		PbStaleUtil.clear();

		PbStaleUtil.Scan scan = PbStaleUtil.scan(u, bk, wf, true);
		assertFalse("An edit to an unwatched field (description) must not drift the node", scan.entries.containsKey(idOf(n)));
		assertEquals(PbNodeStatusEnumType.DONE, scan.statuses.get(idOf(n)));
		assertEquals("stored status must still be DONE", PbNodeStatusEnumType.DONE, statusOf(u, n));
	}

	@Test
	public void case06_affectedByRecordCountsScenes() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		String bookOid = bookObjectId(bk);

		BaseRecord p = node(u, wf, "c06-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord s = node(u, wf, "c06-scene3", PbNodeTypeEnumType.SCENE_PROMPT, 1);
		BaseRecord s2 = node(u, wf, "c06-scene5", PbNodeTypeEnumType.SCENE_PROMPT, 2);
		BaseRecord c = createCharPerson(u, "c06-char");
		String cOid = c.get(FieldNames.FIELD_OBJECT_ID);

		bindCharacter(u, wf, p, c);
		bindCharacter(u, wf, s, c);
		bindCharacter(u, wf, s2, c);
		assertNotNull(PbGraphUtil.addBinding(u, wf, s, ROLE_PORTRAIT, 0, p, null, groupPath()));
		assertNotNull(PbGraphUtil.addBinding(u, wf, s2, ROLE_PORTRAIT, 0, p, null, groupPath()));
		scopeToScene(u, s, 3);
		scopeToScene(u, s2, 5);
		markRendered(u, p, bk);
		markRendered(u, s, bk);
		markRendered(u, s2, bk);
		PbStaleUtil.clear();

		Map<String, Object> before = PbServiceFacade.affectedByRecord(u, bookOid, OlioModelNames.MODEL_CHAR_PERSON, cOid);
		assertEquals("boundNodes before edit: " + before, 3, ((Number) before.get("boundNodes")).intValue());
		assertEquals("drifted before edit: " + before, Boolean.FALSE, before.get("drifted"));
		assertEquals("affectedSceneIndexes before edit: " + before, Arrays.asList(3, 5), intList(before.get("affectedSceneIndexes")));
		assertTrue("staleSceneIndexes before edit should be empty: " + before, intList(before.get("staleSceneIndexes")).isEmpty());

		patchCharPerson(u, c, "firstName", "After");
		PbStaleUtil.clear();

		Map<String, Object> afterEdit = PbServiceFacade.affectedByRecord(u, bookOid, OlioModelNames.MODEL_CHAR_PERSON, cOid);
		assertEquals("boundNodes after edit: " + afterEdit, 3, ((Number) afterEdit.get("boundNodes")).intValue());
		assertEquals("drifted after edit: " + afterEdit, Boolean.TRUE, afterEdit.get("drifted"));
		assertEquals("affectedSceneIndexes after edit: " + afterEdit, Arrays.asList(3, 5), intList(afterEdit.get("affectedSceneIndexes")));
		assertEquals("staleSceneIndexes after edit: " + afterEdit, Arrays.asList(3, 5), intList(afterEdit.get("staleSceneIndexes")));
		assertEquals("markPersisted after edit: " + afterEdit, Boolean.TRUE, afterEdit.get("markPersisted"));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, p));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, s));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, s2));
	}

	/**
	 * V3 P1-10 S0(d): an unregistered {@code refModel} is refused with 400 BEFORE the scan runs. The
	 * setup is one that a scan would visibly act on - a rendered node whose bound charPerson has since
	 * been edited - so "no scan ran" is proven by the node still reading DONE and nothing being cached,
	 * and the same call with the real model name is then shown to mark it STALE.
	 */
	@Test
	public void case06b_affectedByRecordRefusesUnregisteredModelWithoutScanning() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		String bookOid = bookObjectId(bk);

		BaseRecord p = node(u, wf, "c06b-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord c = createCharPerson(u, "c06b-char");
		String cOid = c.get(FieldNames.FIELD_OBJECT_ID);
		bindCharacter(u, wf, p, c);
		markRendered(u, p, bk);
		patchCharPerson(u, c, "firstName", "After");
		PbStaleUtil.clear();
		assertNull("precondition: nothing cached after clear", PbStaleUtil.cached(idOf(wf)));

		/// The refusal must be side-effect-free: a miss in RecordFactory.getSchema runs a DB query, a
		/// classpath probe and logs a stack trace through ResourceUtil/ErrorUtil, so those loggers are
		/// captured (TestKontextDeprecation's LoggerConfig pattern) across the refusing call alone.
		LoggerContext lctx = (LoggerContext) LogManager.getContext(false);
		LoggerConfig rootCfg = lctx.getConfiguration().getLoggerConfig(ResourceUtil.class.getName());
		final List<String> resolverLines = java.util.Collections.synchronizedList(new ArrayList<>());
		final List<String> watched = Arrays.asList(ResourceUtil.class.getName(), ErrorUtil.class.getName(), RecordFactory.class.getName());
		AbstractAppender capture = new AbstractAppender("c06bResolverCapture", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if(watched.contains(event.getLoggerName()) && event.getLevel().isMoreSpecificThan(Level.WARN)) {
					resolverLines.add(event.getLoggerName() + ": " + event.getMessage().getFormattedMessage());
				}
			}
		};
		capture.start();
		rootCfg.addAppender(capture, Level.WARN, null);
		try {
			PbServiceFacade.affectedByRecord(u, bookOid, "no.such.model", cOid);
			fail("An unregistered refModel must be refused");
		}
		catch(PictureBookException e) {
			assertEquals("unregistered refModel is a 400: " + e.getMessage(), 400, e.getStatus());
			assertTrue("400 names the refusal: " + e.getMessage(), e.getMessage().startsWith("Unknown refModel"));
		}
		finally {
			rootCfg.removeAppender("c06bResolverCapture");
			capture.stop();
		}
		assertTrue("the refusal must not reach the schema resolver (no ResourceUtil/ErrorUtil/RecordFactory WARN+ lines): " + resolverLines,
			resolverLines.isEmpty());
		try {
			PbServiceFacade.affectedByRecord(null, bookOid, "no.such.model", cOid);
			fail("No principal must be refused before the model check");
		}
		catch(PictureBookException e) {
			assertEquals("no principal is 401, not a model-registration oracle: " + e.getMessage(), 401, e.getStatus());
		}
		assertNull("the refusal must not have run (and cached) a scan", PbStaleUtil.cached(idOf(wf)));
		assertEquals("the refusal must not have persisted STALE on the drifted node",
			PbNodeStatusEnumType.DONE, statusOf(u, p));

		/// The same setup through the registered model name proves the scan WOULD have acted.
		Map<String, Object> real = PbServiceFacade.affectedByRecord(u, bookOid, OlioModelNames.MODEL_CHAR_PERSON, cOid);
		assertEquals("drifted via the real model: " + real, Boolean.TRUE, real.get("drifted"));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, p));
	}

	@Test
	public void case07_readOnlyViewerGetsReportWithoutPersisting() throws Exception {
		BaseRecord a = user();
		BaseRecord bk = book(a);
		BaseRecord wf = workflow(a, bk);
		String bookOid = bookObjectId(bk);
		OrganizationContext org = getTestOrganization(ORG_A);
		long orgId = org.getOrganizationId();

		/// Second user B. Admin is used ONLY to create B and to grant the fixture entitlements below;
		/// no assertion is made as admin.
		BaseRecord b = ioContext.getFactory().getCreateUser(org.getAdminUser(), "pbStaleReader", orgId);
		assertNotNull("Failed to get/create pbStaleReader", b);

		String bookGroupPath = PbBookUtil.bookGroupPath(SLUG);
		String workflowGroupPath = PbBookUtil.workflowGroupPath(SLUG);
		BaseRecord bookGroup = ioContext.getPathUtil().findPath(a, ModelNames.MODEL_GROUP, bookGroupPath, GroupEnumType.DATA.toString(), orgId);
		BaseRecord workflowGroup = ioContext.getPathUtil().findPath(a, ModelNames.MODEL_GROUP, workflowGroupPath, GroupEnumType.DATA.toString(), orgId);
		if(bookGroup == null) {
			logger.warn("Book group not resolvable as " + TEST_USER + " at " + bookGroupPath + "; resolving as org admin for the fixture grant only");
			bookGroup = ioContext.getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_GROUP, bookGroupPath, GroupEnumType.DATA.toString(), orgId);
		}
		if(workflowGroup == null) {
			logger.warn("Workflow group not resolvable as " + TEST_USER + " at " + workflowGroupPath + "; resolving as org admin for the fixture grant only");
			workflowGroup = ioContext.getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_GROUP, workflowGroupPath, GroupEnumType.DATA.toString(), orgId);
		}
		assertNotNull("Could not resolve the book group at " + bookGroupPath + " (org " + orgId + "); cannot grant the viewer", bookGroup);
		assertNotNull("Could not resolve the workflow group at " + workflowGroupPath + " (org " + orgId + "); cannot grant the viewer", workflowGroup);
		/// B must also be able to READ the charPerson, or PbWatchedFields.computeRefHash(B, ...) returns
		/// null ("undetermined") and the scan cannot report the drift at all. The charPersons of this
		/// test live in A's ~/PbGraphRefs group.
		BaseRecord refsGroup = refsGroup(a);
		/// Measured 2026-10-10 (PBAC trace): a Read grant on the Book + Workflow groups alone is NOT
		/// enough to read olio.pb.book as B. The dynamic foreign-field rule for olio.pb.book.world
		/// evaluates the referenced olio.world record as its own resource (olio-user owned, group
		/// /Olio/Universes/Books/Worlds) and denies: "Does pbstalereader have permission read for
		/// am6:olio.world:...:olio.universes.books.worlds:pb2c.graph = false" -> AUDIT DENY on the book.
		/// So the viewer also gets Read on the book's world record. Still no Update anywhere.
		BaseRecord world = bk.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("The fixture book carries no world reference; cannot grant the viewer Read on it", world);
		long worldId = world.get(FieldNames.FIELD_ID);
		assertTrue("The fixture book's world reference has no id; cannot grant the viewer Read on it", worldId > 0L);

		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), b,
			new BaseRecord[] {bookGroup, workflowGroup, refsGroup},
			new String[] {"Read"},
			new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), b, world,
			new String[] {"Read"}, PermissionEnumType.DATA.toString());
		CacheUtil.clearCache();

		/// Control (TestPbSecurity.probeHierarchy pattern): if the grant itself did not land, nothing below
		/// measures anything.
		BaseRecord readPerm = ioContext.getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_PERMISSION,
			"/Read", PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read permission not resolved", readPerm);
		assertTrue("Control: the DATA Read grant must exist on the book group for B",
			ioContext.getAuthorizationUtil().checkEntitlement(b, readPerm, bookGroup));
		assertTrue("Control: the DATA Read grant must exist on the workflow group for B",
			ioContext.getAuthorizationUtil().checkEntitlement(b, readPerm, workflowGroup));
		assertTrue("Control: the DATA Read grant must exist on the book's world record for B",
			ioContext.getAuthorizationUtil().checkEntitlement(b, readPerm, world));

		/// Bracketed PBAC trace around the one read under investigation.
		ioContext.getPolicyUtil().setTrace(true);
		BaseRecord viewerBook;
		try {
			viewerBook = PbBookUtil.readBook(b, bookOid, orgId);
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		assertNotNull("Precondition: the read-only viewer must be able to read the book after the Read grant on "
			+ bookGroupPath + "; readBook(B) returned null", viewerBook);

		/// As A: a rendered node bound to a fresh charPerson, then a watched-field edit.
		BaseRecord n = node(a, wf, "c07-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord c = createCharPerson(a, "c07-char");
		bindCharacter(a, wf, n, c);
		markRendered(a, n, bk);
		/// Measured 2026-10-10 (PBAC trace): Read on ~/PbGraphRefs alone is NOT enough for B to read the
		/// charPerson. The dynamic foreign-field rule "READ foreign access pattern for
		/// olio.charPerson.statistics" evaluates the factory-created olio.statistics record (in A's
		/// ~/Statistics group) as its own resource and denies: "... read for
		/// am6:auth.group.data:...:home.testuser1.statistics = false" -> "Evaluation Results for
		/// resource.systemreadobject: ALL Pass = 1 Size = 2 = false". So B also gets Read (DATA only,
		/// admin-granted) on that one statistics record.
		BaseRecord stats = statisticsOf(a, c);
		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), b, stats,
			new String[] {"Read"}, PermissionEnumType.DATA.toString());
		CacheUtil.clearCache();
		assertTrue("Control: the DATA Read grant must exist on the charPerson's statistics record for B",
			ioContext.getAuthorizationUtil().checkEntitlement(b, readPerm, stats));

		/// Precondition on the exact read the scan performs as B: computeRefHash(B, charPerson) must be
		/// non-null, or the drift is "undetermined" for B and the node is not reported at all.
		assertTrue("Control: the DATA Read grant must exist on " + REFS_GROUP + " for B",
			ioContext.getAuthorizationUtil().checkEntitlement(b, readPerm, refsGroup));
		ioContext.getPolicyUtil().setTrace(true);
		String viewerRefHash;
		try {
			viewerRefHash = PbWatchedFields.computeRefHash(b, OlioModelNames.MODEL_CHAR_PERSON, c.get(FieldNames.FIELD_OBJECT_ID));
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		assertNotNull("Precondition: PbWatchedFields.computeRefHash(B, charPerson) returned null; B cannot read the bound charPerson, so drift is undetermined for B",
			viewerRefHash);
		patchCharPerson(a, c, "firstName", "After");
		PbStaleUtil.clear();

		/// As B: the report must come back, and nothing must have been persisted.
		List<Map<String, Object>> viewerStale = PbServiceFacade.listStale(b, bookOid);
		Map<String, Object> nm = findSummary(viewerStale, n);
		assertNotNull("The read-only viewer did not get the drifted node in its report: " + viewerStale, nm);
		assertEquals(PbStaleUtil.REASON_REF_DRIFT, nm.get("reason"));
		assertEquals("The viewer cannot update nodes, so markPersisted must be false", Boolean.FALSE, nm.get("markPersisted"));
		assertEquals("The viewer's scan must not have written STALE; stored status must still be DONE",
			PbNodeStatusEnumType.DONE, statusOf(a, n));
		/// B's scan is incomplete (its marks were not persisted), so it must not be cached: an unforced
		/// scan by A within the TTL must compute its own, not be served B's.
		assertNull("An incomplete (unpersisted) scan must not be cached for other viewers", PbStaleUtil.cached(idOf(wf)));

		/// As A, NOT forced: the same report, now persisted, and cached because it is complete.
		PbStaleUtil.Scan ownerScan = PbStaleUtil.scan(a, bk, wf, false);
		assertTrue("The owner's scan must report the drifted node", ownerScan.entries.containsKey(idOf(n)));
		assertTrue("The owner's scan must have persisted its marks", ownerScan.markPersisted);
		assertTrue("The owner's scan must be complete", ownerScan.complete);
		assertSame("A complete scan is cached", ownerScan, PbStaleUtil.cached(idOf(wf)));
		List<Map<String, Object>> ownerStale = PbServiceFacade.listStale(a, bookOid);
		Map<String, Object> om = findSummary(ownerStale, n);
		assertNotNull("The owner did not get the drifted node in its report: " + ownerStale, om);
		assertEquals(Boolean.TRUE, om.get("markPersisted"));
		assertEquals("stored status must be STALE after the owner's scan", PbNodeStatusEnumType.STALE, statusOf(a, n));
	}

	/**
	 * Security review finding (2026-10-10): a viewer who cannot read a bound record gets an "undetermined"
	 * scan with that node missing. Such a scan must never be cached, or the owner's next unforced view
	 * (workflow / node) within the TTL would be served the narrower result and hide the drift.
	 */
	@Test
	public void case08_viewerWithUnreadableRefDoesNotPoisonTheOwnersView() throws Exception {
		BaseRecord a = user();
		BaseRecord bk = book(a);
		BaseRecord wf = workflow(a, bk);
		OrganizationContext org = getTestOrganization(ORG_A);
		long orgId = org.getOrganizationId();

		/// Viewer C: Read on the book + workflow groups and the book's world (so the book and graph are
		/// readable) but NOT on the charPerson or its statistics. Admin is used only to create C and grant.
		BaseRecord c = ioContext.getFactory().getCreateUser(org.getAdminUser(), "pbStaleBlindReader", orgId);
		assertNotNull("Failed to get/create pbStaleBlindReader", c);
		BaseRecord bookGroup = ioContext.getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_GROUP,
			PbBookUtil.bookGroupPath(SLUG), GroupEnumType.DATA.toString(), orgId);
		BaseRecord workflowGroup = ioContext.getPathUtil().findPath(org.getAdminUser(), ModelNames.MODEL_GROUP,
			PbBookUtil.workflowGroupPath(SLUG), GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Could not resolve the book group", bookGroup);
		assertNotNull("Could not resolve the workflow group", workflowGroup);
		BaseRecord world = bk.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("The fixture book carries no world reference", world);
		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), c,
			new BaseRecord[] {bookGroup, workflowGroup},
			new String[] {"Read"},
			new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), c, world,
			new String[] {"Read"}, PermissionEnumType.DATA.toString());
		CacheUtil.clearCache();
		assertNotNull("Precondition: C must be able to read the book", PbBookUtil.readBook(c, bookObjectId(bk), orgId));

		/// As A: a rendered node bound to a fresh charPerson, then a watched-field edit.
		BaseRecord n = node(a, wf, "c08-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord cp = createCharPerson(a, "c08-char");
		bindCharacter(a, wf, n, cp);
		markRendered(a, n, bk);
		String cpOid = cp.get(FieldNames.FIELD_OBJECT_ID);
		assertNull("Precondition: C must NOT be able to read the bound charPerson (no grant on it)",
			PbWatchedFields.computeRefHash(c, OlioModelNames.MODEL_CHAR_PERSON, cpOid));
		patchCharPerson(a, cp, "firstName", "Blind");
		PbStaleUtil.clear();

		/// As C: the drift is undetermined, the node is absent, the scan is incomplete and NOT cached.
		PbStaleUtil.Scan blind = PbStaleUtil.scan(c, bk, wf, true);
		assertFalse("C cannot assess the node; it must not appear as stale", blind.entries.containsKey(idOf(n)));
		assertTrue("The unreadable charPerson must be reported as undetermined",
			blind.undeterminedRefs.contains(OlioModelNames.MODEL_CHAR_PERSON + ":" + cpOid));
		assertFalse("A scan with an undetermined ref is incomplete", blind.complete);
		assertNull("An incomplete scan must not be cached", PbStaleUtil.cached(idOf(wf)));
		assertEquals("C's scan must not have written anything", PbNodeStatusEnumType.DONE, statusOf(a, n));

		/// As A, NOT forced, within the TTL: must not be served C's view.
		PbStaleUtil.Scan owner = PbStaleUtil.scan(a, bk, wf, false);
		assertNotSame("The owner must get its own scan, not the blind viewer's", blind, owner);
		assertTrue("The owner's scan must see the drift C could not", owner.entries.containsKey(idOf(n)));
		assertEquals(PbStaleUtil.REASON_REF_DRIFT, owner.entries.get(idOf(n)).reason);
		assertTrue("The owner's scan must be complete", owner.complete);
		assertSame("The owner's complete scan is the one cached", owner, PbStaleUtil.cached(idOf(wf)));
		assertEquals("stored status must be STALE after the owner's scan", PbNodeStatusEnumType.STALE, statusOf(a, n));
	}

	/**
	 * "marked" is the reason for a stored STALE that nothing in the current scan explains: no drift of its
	 * own and no live root upstream. The moment a root appears upstream the reason becomes "propagated"
	 * (case02's second scan); without one it stays "marked" and the upstream node is not reported at all.
	 */
	@Test
	public void case09_storedStaleWithNoLiveRootIsMarked() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord p = node(u, wf, "c09-portrait", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord s = node(u, wf, "c09-scene", PbNodeTypeEnumType.SCENE_PROMPT, 1);
		BaseRecord c = createCharPerson(u, "c09-char");
		bindCharacter(u, wf, p, c);
		assertNotNull(PbGraphUtil.addBinding(u, wf, s, ROLE_PORTRAIT, 0, p, null, groupPath()));
		scopeToScene(u, s, 9);
		markRendered(u, p, bk);
		markRendered(u, s, bk);

		/// An earlier write marked S STALE; nothing about P or C has changed since.
		assertTrue(PbGraphUtil.persistStatus(u, s, PbNodeStatusEnumType.STALE));
		PbStaleUtil.clear();

		PbStaleUtil.Scan scan = PbStaleUtil.scan(u, bk, wf, true);
		assertFalse("P has no drift and must not be reported", scan.entries.containsKey(idOf(p)));
		PbStaleUtil.Entry se = scan.entries.get(idOf(s));
		assertNotNull("A stored STALE must still be reported", se);
		assertEquals(PbStaleUtil.REASON_MARKED, se.reason);
		assertTrue("A stored STALE needs no new write", se.persisted);
		assertEquals(Arrays.asList(9), new ArrayList<>(se.affectedSceneIndexes));
		assertTrue(scan.complete);
		assertEquals(PbNodeStatusEnumType.DONE, statusOf(u, p));

		/// Now a live root appears upstream: S's explanation changes to "propagated".
		patchCharPerson(u, c, "lastName", "Rooted");
		PbStaleUtil.Scan rooted = PbStaleUtil.scan(u, bk, wf, true);
		assertEquals(PbStaleUtil.REASON_REF_DRIFT, rooted.entries.get(idOf(p)).reason);
		assertEquals(PbStaleUtil.REASON_PROPAGATED, rooted.entries.get(idOf(s)).reason);
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, p));
	}
}
