package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.NodeSubject;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P3-4 (PictureBook V3 design §7, §10, §28 step 3; §30 row 3): the {@code LOCATION} node, its approved render,
 * lock, variants, delete and merge, exercised through {@code PbServiceFacade} / {@code PbArtifactUtil} /
 * {@code PbLocationUtil} as a non-admin owner against {@code am7db}. Nothing is mocked; the one render case
 * uses the live Swarm node ({@code test.swarm.server}) and is gated by {@link SdTestGate} (a gated skip is a
 * skip, never a pass).
 * <ol>
 * <li>{@link #case01ApproveSharesAcrossChaptersAndDeleteClears}: two chapters of one series share the location;
 *     approving a landscape IMAGE of chapter A's LOCATION node pins it as {@code approvedRender}, and chapter
 *     B's node bound to the location surfaces {@code refDrift} in its stale scan; approve/reject move the
 *     pointer exactly as §10 says; deleting chapter A clears the pointer and keeps the location (exit criterion
 *     "scenes share one approved tavern; series chapters share it").</li>
 * <li>{@link #case02LockRefusesEveryFacadeWriteButNotGenericPatch}: the whole-asset lock (⚠ §6.C 174) - every
 *     facade write is a 409 naming what it refused, reject of a non-approved candidate is still allowed, and
 *     generic {@code AccessPoint.update} is NOT gated (documents §6.C 170 e).</li>
 * <li>{@link #case03StrangerAndOtherBookAre404}: a stranger gets "Book not found"; another book of the same
 *     owner cannot see a location outside its world.</li>
 * <li>{@link #case04VariantsDefaultNameAndUniqueness}: variant naming, duplicate 409, empty-delta 400.</li>
 * <li>{@link #case05DeleteRefusesWhileReferencedThenRemovesEverything}: delete audit steps, POI gone.</li>
 * <li>{@link #case06MergeRepointsScenesVariantsAndBindings}: duplicate merged into survivor; scene, variant
 *     and graph binding re-pointed, aliases folded, orphan POI deleted.</li>
 * <li>{@link #case07DeadRendererRefusesBeforeRenderingAndSeedsFromSceneLandscapes}: the first materialization
 *     seeds the node's landscape chain from the scenes' selected landscapes (§28 step 3), the plan is
 *     persisted and the refusal is a 503 with no host in it; a variant node is never seeded. No SD.</li>
 * <li>{@link #case08LiveRenderBindsAndApprovePins}: one real render per (location, variant) on the live Swarm
 *     node, bindings recorded after the render, approve pins the node so a second render is refused. SD-gated.</li>
 * <li>{@link #case09ForceNeverBypassesLockOrPin}: {@code POST .../render?force=true} through
 *     {@code requestLocationRender}: a locked location is 409 before the workflow exists, a pinned node is 409
 *     before any run or job exists and its approved render is untouched; the same {@code force} on an unpinned,
 *     current node does submit (so the two refusals are the gates, not {@code force} being ignored). No SD: the
 *     renderer is a dead port and the forced job ends FAILED.</li>
 * <li>{@link #case10MergeWithAMaterializedNodeOnTheDuplicate}: a merge whose duplicate already has a LOCATION node
 *     with a candidate: the survivor keeps its own (unlocked) lock decision, and nothing in this book's workflow
 *     still points at the deleted duplicate.</li>
 * <li>{@link #case11HardeningMessagesCleanTextVariantCapAndForeignWorldSubject}: the security hardening slice -
 *     a delete refused for another chapter's scene names no count, a same-book reference does; control
 *     characters in a create name or alias are stripped; the 33rd variant is a 409; a LOCATION node whose
 *     {@code scopeRef} is another world's location is 404 on approve and reject and promotes nothing, while a
 *     dangling {@code scopeRef} is still rejectable; a {@code scopeRef} the caller cannot read but which exists
 *     (another user's world, locked, with that user's own approved render) is the same 404 and never treated as
 *     dangling - the lock holds ({@code PbLocationUtil.subjectExists}).</li>
 * <li>{@link #case12HealthFindsAndHealsNodeDanglingAfterCrossChapterDelete}: chapter A deletes one shared location
 *     and merges another away; chapter B's LOCATION nodes on both are left dangling and {@code PbHealthUtil} reports
 *     {@code LOCATION_NODE_DANGLING} for each, which the heal removes with their candidate renders; a node with a
 *     live subject is untouched.</li>
 * </ol>
 */
public class TestPbLocationNode extends BaseTest {

	private static final String DEAD_SERVER = "http://127.0.0.1:1";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final String DESCRIPTION = "A low-beamed tavern by the quay, lanterns in the windows, barrels stacked by the door";

	private String server;

	@Before
	public void setupEach() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
		PbStaleUtil.clear();
	}

	// ───────────────────────────── helpers ─────────────────────────────

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord owner() {
		BaseRecord u = getCreateUser("pbLocNodeOwner");
		assertNotNull("owner", u);
		return u;
	}

	private BaseRecord stranger() {
		BaseRecord u = getCreateUser("pbLocNodeStranger");
		assertNotNull("stranger", u);
		return u;
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = rec.get(field);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v == null ? null : String.valueOf(v).toUpperCase());
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p34" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P3-4 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			logger.info("cleanup " + bk.get(OlioFieldNames.FIELD_PB_SLUG) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(OlioFieldNames.FIELD_PB_SLUG) + ": " + e.getMessage());
		}
	}

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene " + index, scene);
		if(setting != null) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING);
			patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
			assertNotNull("setting patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
		}
		return readScene(u, oidOf(scene), orgOf(u));
	}

	private void pointSceneAt(BaseRecord u, BaseRecord scene, BaseRecord loc) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
		patch.set(FieldNames.FIELD_LOCATION, loc);
		assertNotNull("scene.location patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
	}

	/// Un-assign: a present-but-null FK on a field-name patch writes NULL (§6.C 170 f).
	private void unassignScene(BaseRecord u, BaseRecord scene) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
		patch.set(FieldNames.FIELD_LOCATION, null);
		assertNotNull("scene.location clear patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
	}

	private BaseRecord readScene(BaseRecord actor, String objectId, long orgId) {
		BaseRecord s = PbBookUtil.readScene(actor, objectId, orgId);
		assertNotNull("scene " + objectId, s);
		return s;
	}

	private BaseRecord readLocation(BaseRecord actor, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.locationRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(actor, q);
	}

	private BaseRecord mustReadLocation(BaseRecord actor, String objectId, long orgId) {
		BaseRecord loc = readLocation(actor, objectId, orgId);
		assertNotNull("location " + objectId, loc);
		return loc;
	}

	private BaseRecord newLocation(BaseRecord u, BaseRecord bk, String name, String description) {
		BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, name, description, PbLocationKindEnumType.INTERIOR, List.of("the taproom"));
		assertNotNull("getCreateLocation " + name, loc);
		assertNotNull("a resolved location in a book world gets a map-free POI (§7.2 rule 1)", loc.get(OlioFieldNames.FIELD_PB_POI));
		assertNull("a fresh location has no approvedRender", loc.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		assertFalse("a fresh location is not locked", PbLocationUtil.isLocked(loc));
		return loc;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord node(BaseRecord u, BaseRecord bk, BaseRecord wf, String handle, PbNodeTypeEnumType type, String scope, String scopeRef) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("node " + handle, n);
		assertTrue("scope patch " + handle, PbPipelineUtil.patchNodeScope(u, wf, n, scope, scopeRef, 0));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(n), orgOf(u));
		assertNotNull(fresh);
		assertEquals(scopeRef, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		return fresh;
	}

	private BaseRecord locationNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String locOid) {
		return node(u, bk, wf, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, PbPipelineUtil.SCOPE_LOCATION, locOid);
	}

	private static byte[] png(int w, int h, Color c) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(c);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.WHITE);
		g.drawLine(0, 0, w - 1, h - 1);
		g.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	/// A data.data in the book's artifact gallery (the compartment node runs persist into); a ref outside it
	/// makes persistArtifact's create return null.
	private BaseRecord persistPng(BaseRecord u, BaseRecord bk, String name, byte[] bytes) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, PbBookUtil.artifactGroupPath(slug));
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, u, null, plist);
		ByteModelUtil.setValue(data, bytes);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(u, data);
		assertNotNull("data.data " + name, created);
		return created;
	}

	/// A landscape IMAGE artifact on {@code node} (what a LOCATION render records), 64x64 bytes of {@code c}.
	private BaseRecord landscapeArtifact(BaseRecord u, BaseRecord bk, BaseRecord node, Color c) throws Exception {
		byte[] bytes = png(64, 64, c);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord data = persistPng(u, bk, "loc-" + shortId() + ".png", bytes);
		BaseRecord artifact = PbArtifactUtil.persistArtifact(u, node, PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE,
			PbBookUtil.artifactGroupPath(slug), data, null, null, null);
		assertNotNull("landscape artifact", artifact);
		assertTrue("metrics", PbArtifactUtil.recordImageMetrics(u, artifact, bytes, "image/png", 64, 64, null));
		return PbArtifactUtil.readArtifact(u, oidOf(artifact), orgOf(u));
	}

	private BaseRecord readArtifact(BaseRecord u, String oid) {
		BaseRecord a = PbArtifactUtil.readArtifact(u, oid, orgOf(u));
		assertNotNull("artifact " + oid, a);
		return a;
	}

	private String markRendered(BaseRecord u, BaseRecord node, BaseRecord bk) {
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgOf(u));
		assertNotNull(fresh);
		String hash = PbGraphUtil.computeInputHash(u, fresh, bk);
		assertNotNull(hash);
		assertTrue(PbGraphUtil.persistInputHash(u, fresh, hash));
		assertTrue(PbGraphUtil.persistStatus(u, fresh, PbNodeStatusEnumType.DONE));
		BaseRecord again = PbGraphUtil.readNode(u, oidOf(node), orgOf(u));
		assertEquals(PbNodeStatusEnumType.DONE.name(), status(again, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return hash;
	}

	private static Map<String, Object> findSummary(List<Map<String, Object>> list, BaseRecord node) {
		String oid = oidOf(node);
		for(Map<String, Object> m : list) {
			if(oid.equals(m.get("objectId"))) {
				return m;
			}
		}
		return null;
	}

	private static PictureBookException expect(String what, int expected, String mustContain, Runnable r) {
		try {
			r.run();
		}
		catch(PictureBookException e) {
			assertEquals(what + " status (" + e.getMessage() + ")", expected, e.getStatus());
			if(mustContain != null) {
				assertTrue(what + " message must mention '" + mustContain + "': " + e.getMessage(), e.getMessage().contains(mustContain));
			}
			return e;
		}
		fail(what + " must throw PictureBookException " + expected);
		return null;
	}

	private static void assertRefusalShape(PictureBookException e, int status, String... mustContain) {
		assertEquals("status (" + e.getMessage() + ")", status, e.getStatus());
		for(String s : mustContain) {
			assertTrue("message must mention '" + s + "': " + e.getMessage(), e.getMessage().contains(s));
		}
		assertFalse("message never carries a host: " + e.getMessage(), e.getMessage().contains("127.0.0.1"));
		assertFalse("message never carries a LAN address: " + e.getMessage(), e.getMessage().contains("192.168."));
		assertFalse("message never carries a URL: " + e.getMessage(), e.getMessage().contains("http://"));
	}

	private static String planOidOf(PictureBookException e) {
		String marker = "(plan artifact ";
		int i = e.getMessage().lastIndexOf(marker);
		assertTrue("the refusal names the plan artifact: " + e.getMessage(), i >= 0);
		String rest = e.getMessage().substring(i + marker.length());
		int j = rest.indexOf(')');
		assertTrue(j > 0);
		return rest.substring(0, j);
	}

	private BaseRecord freshReadData(BaseRecord u, String objectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(u, q);
	}

	private byte[] artifactBytes(BaseRecord u, BaseRecord artifact) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("artifact points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = freshReadData(u, dataOid);
		assertNotNull("data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		return bytes;
	}

	private int nodeCount(BaseRecord u, BaseRecord bk) {
		if(PbGraphUtil.findWorkflow(u, bk) == null) {
			return 0;
		}
		Map<String, Object> view = PbServiceFacade.workflowView(u, oidOf(bk));
		Object nodes = view.get("nodes");
		return (nodes instanceof List ? ((List<?>) nodes).size() : 0);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> maps(Map<String, Object> m, String key) {
		Object v = m.get(key);
		assertTrue(key + " is a list: " + v, v instanceof List);
		return (List<Map<String, Object>>) v;
	}

	private static Map<String, Object> step(DeleteResult res, String name) {
		for(Map<String, Object> s : res.steps) {
			if(name.equals(s.get("step"))) {
				return s;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> step(Map<String, Object> out, String name) {
		Object steps = out.get("steps");
		assertTrue("steps is a list: " + steps, steps instanceof List);
		for(Object o : (List<Object>) steps) {
			Map<String, Object> s = (Map<String, Object>) o;
			if(name.equals(s.get("step"))) {
				return s;
			}
		}
		return null;
	}

	private static int count(Map<String, Object> step) {
		assertNotNull("step present", step);
		Object c = step.get("count");
		return (c instanceof Number ? ((Number) c).intValue() : -1);
	}

	// ─── 1. approve shares across chapters; delete clears ───────────────────────

	@Test
	public void case01ApproveSharesAcrossChaptersAndDeleteClears() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord series = PbSeriesUtil.getCreateSeries(u, dataPath(), "p34s" + tag, "P3-4 series " + tag);
		assertNotNull("series", series);
		BaseRecord chA = null;
		BaseRecord chB = null;
		try {
			chA = PbBookUtil.createBook(u, dataPath(), "p34a" + tag, "P3-4 chapter A " + tag, series, Integer.valueOf(0));
			chB = PbBookUtil.createBook(u, dataPath(), "p34b" + tag, "P3-4 chapter B " + tag, series, Integer.valueOf(1));
			assertNotNull(chA);
			assertNotNull(chB);
			String chAOid = oidOf(chA);
			String chBOid = oidOf(chB);

			BaseRecord loc = newLocation(u, chA, "The Gilded Tankard " + tag, DESCRIPTION);
			String locOid = oidOf(loc);
			assertNotNull("chapter B resolves the same location through the shared series world",
				PbLocationUtil.requireLocationOfBook(u, chB, locOid));

			/// Chapter B: a rendered node bound to the location record (what a scene plan binds in P3-5).
			BaseRecord wfB = workflow(u, chB);
			BaseRecord nB = node(u, chB, wfB, "render_plan@p34b" + tag, PbNodeTypeEnumType.RENDER_PLAN, PbPipelineUtil.SCOPE_SCENE, "none");
			BaseRecord binding = PbGraphUtil.addRecordBinding(u, wfB, nB, PbPipelineUtil.ROLE_LOCATION, 0,
				OlioModelNames.MODEL_PB_LOCATION, locOid, PbBookUtil.workflowGroupPath(chB.get(OlioFieldNames.FIELD_PB_SLUG)));
			assertNotNull("binding", binding);
			assertNotNull("refHash captured at bind time", binding.get(OlioFieldNames.FIELD_PB_REF_HASH));
			markRendered(u, nB, chB);

			/// Chapter A: the location's LOCATION node with a rendered candidate.
			BaseRecord wfA = workflow(u, chA);
			BaseRecord nA = locationNode(u, chA, wfA, locOid);
			BaseRecord a1 = landscapeArtifact(u, chA, nA, Color.ORANGE);
			assertEquals(Boolean.TRUE, a1.get(OlioFieldNames.FIELD_PB_SELECTED));
			assertEquals(PbReviewStatusEnumType.GENERATED.name(), status(a1, OlioFieldNames.FIELD_PB_REVIEW_STATUS));

			PbStaleUtil.clear();
			assertNull("nothing drifted yet", findSummary(PbServiceFacade.listStale(u, chBOid), nB));

			Map<String, Object> approved = PbServiceFacade.approveArtifact(u, chAOid, oidOf(a1));
			logger.info("approve: " + JSONUtil.exportObject(approved));
			assertEquals(oidOf(nA), approved.get("nodeObjectId"));
			BaseRecord loc1 = mustReadLocation(u, locOid, orgId);
			assertEquals("approve promotes the landscape IMAGE to the location's approvedRender", idOf(a1), fkId(loc1, OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			BaseRecord nA1 = PbGraphUtil.readNode(u, oidOf(nA), orgId);
			assertEquals("approve pins the node", Boolean.TRUE, nA1.get(OlioFieldNames.FIELD_PB_PINNED));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), status(readArtifact(u, oidOf(a1)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));

			/// The other chapter's node sees the drift (approvedRender is in the watched set).
			PbStaleUtil.clear();
			Map<String, Object> stale = findSummary(PbServiceFacade.listStale(u, chBOid), nB);
			assertNotNull("chapter B's node bound to the location is reported stale", stale);
			assertEquals(PbStaleUtil.REASON_REF_DRIFT, stale.get("reason"));
			List<Map<String, Object>> drifted = maps(stale, "driftedBindings");
			assertEquals(1, drifted.size());
			assertEquals(PbPipelineUtil.ROLE_LOCATION, drifted.get(0).get("role"));
			assertEquals(OlioModelNames.MODEL_PB_LOCATION, drifted.get(0).get("refModel"));
			assertEquals(locOid, drifted.get(0).get("refObjectId"));

			/// Idempotent: writing the same pointer again is a no-op.
			assertFalse(PbLocationUtil.writeApprovedRender(u, new NodeSubject(loc1, null), readArtifact(u, oidOf(a1))));

			/// A second candidate arrives unselected (the selection is APPROVED); approving it moves the pointer.
			BaseRecord a2 = landscapeArtifact(u, chA, nA, Color.BLUE);
			assertEquals("a new candidate does not displace an APPROVED selection", Boolean.FALSE, a2.get(OlioFieldNames.FIELD_PB_SELECTED));
			PbServiceFacade.approveArtifact(u, chAOid, oidOf(a2));
			assertEquals(idOf(a2), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals("the previous approval is demoted", PbReviewStatusEnumType.GENERATED.name(), status(readArtifact(u, oidOf(a1)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertEquals(Boolean.TRUE, readArtifact(u, oidOf(a2)).get(OlioFieldNames.FIELD_PB_SELECTED));

			/// Rejecting the approved render clears the pointer and falls the selection back.
			PbServiceFacade.rejectArtifact(u, chAOid, oidOf(a2));
			assertNull("reject of the approved render clears approvedRender", mustReadLocation(u, locOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(PbReviewStatusEnumType.REJECTED.name(), status(readArtifact(u, oidOf(a2)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertEquals("selection falls back to the newest non-rejected sibling", Boolean.TRUE, readArtifact(u, oidOf(a1)).get(OlioFieldNames.FIELD_PB_SELECTED));

			PbServiceFacade.approveArtifact(u, chAOid, oidOf(a1));
			assertEquals(idOf(a1), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			/// Deleting chapter A clears the pointer its artifact backed and leaves the shared location alone.
			DeleteResult del = PbDeleteUtil.deleteBookComplete(u, chAOid);
			logger.info("delete chapter A: " + JSONUtil.exportObject(del.steps));
			assertTrue("chapter A deleted: " + del.reason, del.deleted);
			chA = null;
			Map<String, Object> cleared = step(del, "approvedRendersCleared");
			assertEquals("one approvedRender pointed at chapter A's artifact", 1, count(cleared));
			assertEquals(Boolean.TRUE, cleared.get("ok"));
			BaseRecord after = PbLocationUtil.requireLocationOfBook(u, chB, locOid);
			assertNotNull("the location survives its chapter", after);
			assertNull("...with no dangling approvedRender", after.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertNotNull("...and its POI", after.get(OlioFieldNames.FIELD_PB_POI));
		}
		finally {
			deleteBook(u, chA);
			try {
				int chapters = PbSeriesUtil.deleteSeries(u, oidOf(series));
				logger.info("deleteSeries removed " + chapters + " chapter(s)");
				assertEquals("chapter B was the only chapter left", 1, chapters);
			}
			catch(Exception e) {
				logger.error("deleteSeries: " + e.getMessage());
				deleteBook(u, chB);
			}
		}
	}

	// ─── 2. lock ───────────────────────────────────────────────────────────────

	@Test
	public void case02LockRefusesEveryFacadeWriteButNotGenericPatch() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "lk");
		String bkOid = oidOf(bk);
		try {
			BaseRecord loc = newLocation(u, bk, "Locked Hall " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			String locName = loc.get(FieldNames.FIELD_NAME);
			BaseRecord loc2 = PbLocationUtil.getCreateLocation(u, bk, "Open Yard " + shortId(), "a yard", PbLocationKindEnumType.EXTERIOR, null);
			assertNotNull(loc2);

			Map<String, Object> locked = PbServiceFacade.lockLocation(u, bkOid, locOid, true);
			assertEquals(Boolean.TRUE, locked.get("locked"));
			assertEquals(locOid, locked.get("locationObjectId"));
			assertTrue(PbLocationUtil.isLocked(mustReadLocation(u, locOid, orgId)));

			expect("update while locked", 409, "Location " + locName + " is locked; unlock it to edit it",
				() -> PbServiceFacade.updateLocation(u, bkOid, locOid, Map.of(FieldNames.FIELD_DESCRIPTION, "changed")));
			assertEquals("the edit was not applied", DESCRIPTION, mustReadLocation(u, locOid, orgId).get(FieldNames.FIELD_DESCRIPTION));
			expect("variant while locked", 409, "unlock it to add a variant",
				() -> PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "night", null, null, null, null));
			expect("delete while locked", 409, "unlock it to delete it",
				() -> PbServiceFacade.deleteLocation(u, bkOid, locOid));
			assertNull("no workflow before the render attempt", PbGraphUtil.findWorkflow(u, bk));
			expect("render while locked", 409, "unlock it to render it",
				() -> PbServiceFacade.renderLocation(u, bkOid, locOid, null, new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER)));
			assertNull("the lock is checked before the workflow is materialized", PbGraphUtil.findWorkflow(u, bk));
			expect("merge locked duplicate", 409, "unlock it to merge it into another location",
				() -> PbServiceFacade.mergeLocations(u, bkOid, locOid, oidOf(loc2)));
			expect("merge into locked survivor", 409, "unlock it to merge another location into it",
				() -> PbServiceFacade.mergeLocations(u, bkOid, oidOf(loc2), locOid));

			/// Candidates on the node arrive through the util (a render would have been refused); approve is gated.
			BaseRecord wf = workflow(u, bk);
			BaseRecord n = locationNode(u, bk, wf, locOid);
			BaseRecord a1 = landscapeArtifact(u, bk, n, Color.RED);
			BaseRecord a2 = landscapeArtifact(u, bk, n, Color.GREEN);
			assertEquals(Boolean.TRUE, a2.get(OlioFieldNames.FIELD_PB_SELECTED));
			expect("approve while locked", 409, "unlock it to approve a render of it",
				() -> PbServiceFacade.approveArtifact(u, bkOid, oidOf(a1)));
			assertNull("nothing was promoted", mustReadLocation(u, locOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(PbReviewStatusEnumType.GENERATED.name(), status(readArtifact(u, oidOf(a1)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));

			/// Rejecting a candidate that is not the approved render is not a lock concern.
			PbServiceFacade.rejectArtifact(u, bkOid, oidOf(a2));
			assertEquals(PbReviewStatusEnumType.REJECTED.name(), status(readArtifact(u, oidOf(a2)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertEquals(Boolean.TRUE, readArtifact(u, oidOf(a1)).get(OlioFieldNames.FIELD_PB_SELECTED));

			/// Unlock -> approve -> lock -> the approved render cannot be cleared.
			assertEquals(Boolean.FALSE, PbServiceFacade.lockLocation(u, bkOid, locOid, false).get("locked"));
			PbServiceFacade.approveArtifact(u, bkOid, oidOf(a1));
			assertEquals(idOf(a1), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(Boolean.TRUE, PbServiceFacade.lockLocation(u, bkOid, locOid, true).get("locked"));
			expect("reject the approved render while locked", 409, "unlock it to reject its approved render",
				() -> PbServiceFacade.rejectArtifact(u, bkOid, oidOf(a1)));
			assertEquals("the pointer is intact", idOf(a1), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), status(readArtifact(u, oidOf(a1)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));

			/// Locking an already-locked location is a no-op, never a refusal.
			assertEquals(Boolean.TRUE, PbServiceFacade.lockLocation(u, bkOid, locOid, true).get("locked"));

			/// Facade-only (§6.C 170 e): a generic patch as the owner is not gated by the flag.
			BaseRecord bypass = PbGraphUtil.patchOf(mustReadLocation(u, locOid, orgId), OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_DESCRIPTION);
			bypass.set(FieldNames.FIELD_DESCRIPTION, "edited through a generic patch");
			assertNotNull("generic AccessPoint.update is not consulted by the lock (documented, not a defect of this task)",
				IOSystem.getActiveContext().getAccessPoint().update(u, bypass));
			assertEquals("edited through a generic patch", mustReadLocation(u, locOid, orgId).get(FieldNames.FIELD_DESCRIPTION));

			assertEquals(Boolean.FALSE, PbServiceFacade.lockLocation(u, bkOid, locOid, false).get("locked"));
			Map<String, Object> dto = PbServiceFacade.getLocation(u, bkOid, locOid);
			assertEquals(Boolean.FALSE, dto.get("locked"));
			assertEquals(oidOf(a1), dto.get("approvedRenderObjectId"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 3. stranger / other book ──────────────────────────────────────────────

	@Test
	public void case03StrangerAndOtherBookAre404() throws Exception {
		BaseRecord u = owner();
		BaseRecord s = stranger();
		assertNotEquals(idOf(u), idOf(s));
		BaseRecord bkA = newBook(u, "sa");
		BaseRecord bkB = newBook(u, "sb");
		try {
			BaseRecord loc = newLocation(u, bkA, "Private Chapel " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			String aOid = oidOf(bkA);
			String bOid = oidOf(bkB);

			expect("stranger get", 404, "Book not found", () -> PbServiceFacade.getLocation(s, aOid, locOid));
			expect("stranger list", 404, "Book not found", () -> PbServiceFacade.listLocations(s, aOid));
			expect("stranger lock", 404, "Book not found", () -> PbServiceFacade.lockLocation(s, aOid, locOid, true));
			expect("stranger variant", 404, "Book not found", () -> PbServiceFacade.createLocationVariant(s, aOid, locOid, null, "dusk", null, null, null, null));
			expect("stranger delete", 404, "Book not found", () -> PbServiceFacade.deleteLocation(s, aOid, locOid));
			expect("stranger render", 404, "Book not found", () -> PbServiceFacade.renderLocation(s, aOid, locOid, null, new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER)));
			assertFalse("nothing the stranger did locked it", PbLocationUtil.isLocked(mustReadLocation(u, locOid, orgOf(u))));

			expect("other book get", 404, "Location not found in this book's world", () -> PbServiceFacade.getLocation(u, bOid, locOid));
			expect("other book lock", 404, "Location not found in this book's world", () -> PbServiceFacade.lockLocation(u, bOid, locOid, true));
			expect("other book variant", 404, "Location not found in this book's world", () -> PbServiceFacade.createLocationVariant(u, bOid, locOid, null, "dusk", null, null, null, null));
			expect("other book delete", 404, "Location not found in this book's world", () -> PbServiceFacade.deleteLocation(u, bOid, locOid));
			expect("other book render", 404, "Location not found in this book's world", () -> PbServiceFacade.renderLocation(u, bOid, locOid, null, new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER)));
			assertNull("no workflow was materialized for the wrong book", PbGraphUtil.findWorkflow(u, bkB));
			assertNotNull("the location is still readable from its own book", PbLocationUtil.requireLocationOfBook(u, bkA, locOid));
		}
		finally {
			deleteBook(u, bkA);
			deleteBook(u, bkB);
		}
	}

	// ─── 4. variants ───────────────────────────────────────────────────────────

	@Test
	public void case04VariantsDefaultNameAndUniqueness() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "va");
		String bkOid = oidOf(bk);
		try {
			BaseRecord loc = newLocation(u, bk, "Harbour Steps " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			String locName = loc.get(FieldNames.FIELD_NAME);

			Map<String, Object> v = PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "night", "rain", null, null, null);
			logger.info("variant: " + JSONUtil.exportObject(v));
			assertEquals(locName + " - night, rain", v.get("name"));
			assertEquals(locOid, v.get("locationObjectId"));
			assertEquals("night", v.get("timeOfDay"));
			assertEquals("rain", v.get("weather"));
			String vOid = (String) v.get("objectId");
			assertNotNull(vOid);
			BaseRecord variant = PbLocationUtil.readVariant(u, vOid, orgId);
			assertNotNull("the variant is readable", variant);
			assertEquals(idOf(loc), fkId(variant, FieldNames.FIELD_LOCATION));
			assertNull("a fresh variant has no approvedRender", variant.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			expect("duplicate variant", 409, "already has a variant named",
				() -> PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "night", "rain", null, null, null));
			expect("no delta", 400, "needs at least one of",
				() -> PbServiceFacade.createLocationVariant(u, bkOid, locOid, "Named but empty", null, null, null, null, null));

			BaseRecord[] parent = new BaseRecord[1];
			BaseRecord again = PbLocationUtil.requireVariantOfBook(u, bk, vOid, parent);
			assertNotNull(again);
			assertEquals(locOid, oidOf(parent[0]));

			Map<String, Object> dto = PbServiceFacade.getLocation(u, bkOid, locOid);
			List<Map<String, Object>> variants = maps(dto, "variants");
			assertEquals(1, variants.size());
			assertEquals(vOid, variants.get(0).get("objectId"));
			assertNull("no node until a render or accept materializes one", variants.get(0).get("node"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 5. delete ─────────────────────────────────────────────────────────────

	@Test
	public void case05DeleteRefusesWhileReferencedThenRemovesEverything() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "dl");
		String bkOid = oidOf(bk);
		try {
			BaseRecord loc = newLocation(u, bk, "Old Mill " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			String locName = loc.get(FieldNames.FIELD_NAME);
			BaseRecord poiRef = loc.get(OlioFieldNames.FIELD_PB_POI);
			assertTrue("POI exists before delete", PbLocationUtil.poiExists(poiRef, orgId));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", "inside the old mill");
			pointSceneAt(u, scene, loc);
			Map<String, Object> v = PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "dusk", null, null, null, null);
			String vOid = (String) v.get("objectId");
			BaseRecord wf = workflow(u, bk);
			BaseRecord n = locationNode(u, bk, wf, locOid);
			BaseRecord vn = node(u, bk, wf, PbPipelineUtil.locationVariantHandle(vOid), PbNodeTypeEnumType.LOCATION, PbPipelineUtil.SCOPE_LOCATION_VARIANT, vOid);
			BaseRecord a1 = landscapeArtifact(u, bk, n, Color.GRAY);
			assertNotNull(a1);
			assertEquals(2, nodeCount(u, bk));

			expect("delete while a scene points at it", 409, "Location " + locName + " is still the location of 1 scene(s)",
				() -> PbServiceFacade.deleteLocation(u, bkOid, locOid));
			assertNotNull("still there", readLocation(u, locOid, orgId));

			unassignScene(u, readScene(u, oidOf(scene), orgId));
			assertNull("scene un-assigned", readScene(u, oidOf(scene), orgId).get(FieldNames.FIELD_LOCATION));

			Map<String, Object> out = PbServiceFacade.deleteLocation(u, bkOid, locOid);
			logger.info("deleteLocation: " + JSONUtil.exportObject(out));
			assertEquals(Boolean.TRUE, out.get("deleted"));
			assertEquals(locOid, out.get("locationObjectId"));
			assertEquals("both LOCATION nodes (location + variant) removed", 2, count(step(out, "locationNodes")));
			assertEquals(1, count(step(out, "variants")));
			assertEquals("the map-free POI is removed with the location (§7.2)", 1, count(step(out, "poi")));
			assertEquals(1, count(step(out, "location")));

			assertFalse("POI gone", PbLocationUtil.poiExists(poiRef, orgId));
			assertNull("location gone", readLocation(u, locOid, orgId));
			assertNull("variant gone", PbLocationUtil.readVariant(u, vOid, orgId));
			assertNull("location node gone", PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationHandle(locOid)));
			assertNull("variant node gone", PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationVariantHandle(vOid)));
			assertNull("artifact gone", PbArtifactUtil.readArtifact(u, oidOf(a1), orgId));
			assertEquals(0, nodeCount(u, bk));
			assertNull("the scene survives", readScene(u, oidOf(scene), orgId).get(FieldNames.FIELD_LOCATION));
			assertNotNull(vn);
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 6. merge ──────────────────────────────────────────────────────────────

	@Test
	public void case06MergeRepointsScenesVariantsAndBindings() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "mg");
		String bkOid = oidOf(bk);
		try {
			BaseRecord locA = newLocation(u, bk, "The Tavern " + shortId(), DESCRIPTION);
			BaseRecord locB = PbLocationUtil.getCreateLocation(u, bk, "Tavern Taproom " + shortId(), "the taproom of the tavern", PbLocationKindEnumType.INTERIOR, null);
			assertNotNull(locB);
			String aOid = oidOf(locA);
			String bOid = oidOf(locB);
			String bName = locB.get(FieldNames.FIELD_NAME);
			BaseRecord poiA = locA.get(OlioFieldNames.FIELD_PB_POI);
			BaseRecord poiB = locB.get(OlioFieldNames.FIELD_PB_POI);
			assertNotNull(poiB);
			assertTrue(PbLocationUtil.poiExists(poiB, orgId));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", "in the taproom");
			pointSceneAt(u, scene, locB);
			Map<String, Object> v = PbServiceFacade.createLocationVariant(u, bkOid, bOid, null, "night", null, null, null, null);
			String vOid = (String) v.get("objectId");
			BaseRecord wf = workflow(u, bk);
			BaseRecord n = node(u, bk, wf, "render_plan@mg" + shortId(), PbNodeTypeEnumType.RENDER_PLAN, PbPipelineUtil.SCOPE_SCENE, oidOf(scene));
			assertNotNull(PbGraphUtil.addRecordBinding(u, wf, n, PbPipelineUtil.ROLE_LOCATION, 0, OlioModelNames.MODEL_PB_LOCATION, bOid,
				PbBookUtil.workflowGroupPath(bk.get(OlioFieldNames.FIELD_PB_SLUG))));

			expect("self merge", 400, "cannot be merged into itself", () -> PbServiceFacade.mergeLocations(u, bkOid, aOid, aOid));

			Map<String, Object> out = PbServiceFacade.mergeLocations(u, bkOid, bOid, aOid);
			logger.info("merge: " + JSONUtil.exportObject(out));
			assertEquals(Boolean.TRUE, out.get("merged"));
			assertEquals(bOid, out.get("duplicateObjectId"));
			assertEquals(aOid, out.get("survivorObjectId"));

			assertNull("the duplicate is gone", readLocation(u, bOid, orgId));
			BaseRecord survivor = mustReadLocation(u, aOid, orgId);
			List<String> aliases = survivor.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertNotNull(aliases);
			assertTrue("the duplicate's name is folded into the survivor's aliases: " + aliases, aliases.contains(bName));
			assertEquals("the scene now points at the survivor", idOf(locA), fkId(readScene(u, oidOf(scene), orgId), FieldNames.FIELD_LOCATION));
			BaseRecord binding = PbPipelineUtil.findBinding(u, n, PbPipelineUtil.ROLE_LOCATION, 0);
			assertNotNull(binding);
			assertEquals("the graph binding is re-pointed", aOid, binding.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			BaseRecord variant = PbLocationUtil.readVariant(u, vOid, orgId);
			assertNotNull("the variant survives", variant);
			assertEquals("...under the survivor", idOf(locA), fkId(variant, FieldNames.FIELD_LOCATION));
			assertFalse("the duplicate's orphan POI is deleted", PbLocationUtil.poiExists(poiB, orgId));
			assertTrue("the survivor's POI is intact", PbLocationUtil.poiExists(poiA, orgId));

			@SuppressWarnings("unchecked")
			Map<String, Object> survivorDto = (Map<String, Object>) out.get("survivor");
			assertNotNull(survivorDto);
			assertEquals(aOid, survivorDto.get("objectId"));
			assertEquals(1, maps(survivorDto, "variants").size());
			assertEquals(1, maps(survivorDto, "usedByScenes").size());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 7. dead renderer: refused before rendering, seeded on first materialization ──

	@Test
	public void case07DeadRendererRefusesBeforeRenderingAndSeedsFromSceneLandscapes() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "dr");
		String bkOid = oidOf(bk);
		SDUtil dead = new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER);
		try {
			BaseRecord loc = newLocation(u, bk, "Lighthouse " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			String locName = loc.get(FieldNames.FIELD_NAME);
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", "the lighthouse at dusk");
			pointSceneAt(u, scene, loc);
			BaseRecord wf = workflow(u, bk);
			BaseRecord landscapeNode = node(u, bk, wf, PbPipelineUtil.landscapeHandle(oidOf(scene)), PbNodeTypeEnumType.LANDSCAPE, PbPipelineUtil.SCOPE_SCENE, oidOf(scene));
			BaseRecord source = landscapeArtifact(u, bk, landscapeNode, Color.MAGENTA);
			assertEquals(Boolean.TRUE, source.get(OlioFieldNames.FIELD_PB_SELECTED));
			assertEquals(1, nodeCount(u, bk));

			long start = System.nanoTime();
			PictureBookException e = expect("dead renderer", 503, null, () -> PbServiceFacade.renderLocation(u, bkOid, locOid, null, dead));
			logger.info("MEASURED: dead-renderer refusal in " + ((System.nanoTime() - start) / 1_000_000L) + " ms: " + e.getStatus() + " " + e.getMessage());
			assertRefusalShape(e, 503, "Renderer inventory unavailable", "location " + locName, "(plan artifact ");
			String planOid = planOidOf(e);

			BaseRecord n = PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationHandle(locOid));
			assertNotNull("the LOCATION node was materialized under its deterministic handle", n);
			assertEquals(2, nodeCount(u, bk));
			BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(n), orgId);
			assertEquals(PbNodeTypeEnumType.LOCATION.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_TYPE));
			assertEquals(PbPipelineUtil.SCOPE_LOCATION, fresh.get(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals(locOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
			assertEquals("a refused node is FAILED", PbNodeStatusEnumType.FAILED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
			String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
			assertNotNull(lastError);
			assertTrue("lastError is the refusal: " + lastError, lastError.contains("plan artifact " + planOid));

			/// §28 step 3: the scene's selected landscape was copied in as a candidate before the render ran.
			List<BaseRecord> chain = PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_LANDSCAPE);
			assertEquals("exactly the seeded candidate (no render happened)", 1, chain.size());
			BaseRecord seeded = readArtifact(u, oidOf(chain.get(0)));
			assertEquals(Integer.valueOf(1), seeded.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(PbArtifactTypeEnumType.IMAGE.name(), status(seeded, OlioFieldNames.FIELD_PB_ARTIFACT_TYPE));
			assertEquals(Boolean.TRUE, seeded.get(OlioFieldNames.FIELD_PB_SELECTED));
			assertEquals(PbReviewStatusEnumType.GENERATED.name(), status(seeded, OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertEquals("provenance: the source artifact", OlioModelNames.MODEL_PB_ARTIFACT, seeded.get(OlioFieldNames.FIELD_PB_REF_MODEL));
			assertEquals(oidOf(source), seeded.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			assertEquals("same bytes", fkId(source, OlioFieldNames.FIELD_PB_DATA), fkId(seeded, OlioFieldNames.FIELD_PB_DATA));
			assertNull("a seeded candidate is never auto-promoted", mustReadLocation(u, locOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertNull("bindings are recorded after a render, so a refusal leaves none", PbPipelineUtil.findBinding(u, fresh, PbPipelineUtil.ROLE_LOCATION, 0));

			BaseRecord planArtifact = PbArtifactUtil.readArtifact(u, planOid, orgId);
			assertNotNull("the plan is persisted even when the render is refused (§12 rule 1)", planArtifact);
			assertEquals(PbPipelineUtil.ROLE_RENDER_PLAN, planArtifact.get(OlioFieldNames.FIELD_PB_ROLE));
			RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(planArtifact);
			assertNotNull(plan);
			assertEquals(PbStrategyEnumType.TEXT_ONLY, plan.getStrategy().getName());
			RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
			assertNotNull(pick);
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), pick.getReason());
			assertFalse("the persisted plan never carries the server URL", plan.toJson().contains(DEAD_SERVER));
			assertTrue("the plan compiles the location description: " + plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(),
				plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt().contains("low-beamed tavern"));

			/// Second call: same node, no second seeding, a second plan revision.
			expect("dead renderer again", 503, "Renderer inventory unavailable", () -> PbServiceFacade.renderLocation(u, bkOid, locOid, null, dead));
			assertEquals("the node is re-used", 2, nodeCount(u, bk));
			assertEquals("seeding happens only on first materialization", 1, PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_LANDSCAPE).size());
			assertEquals(2, PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_RENDER_PLAN).size());

			/// A variant node: its own handle and scope, never seeded.
			Map<String, Object> v = PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "night", null, null, null, null);
			String vOid = (String) v.get("objectId");
			PictureBookException ev = expect("dead renderer, variant", 503, "location " + locName + " / " + v.get("name"),
				() -> PbServiceFacade.renderLocation(u, bkOid, locOid, vOid, dead));
			assertRefusalShape(ev, 503, "Renderer inventory unavailable", "(plan artifact ");
			BaseRecord vn = PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationVariantHandle(vOid));
			assertNotNull("variant node materialized", vn);
			BaseRecord vfresh = PbGraphUtil.readNode(u, oidOf(vn), orgId);
			assertEquals(PbPipelineUtil.SCOPE_LOCATION_VARIANT, vfresh.get(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals(vOid, vfresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
			assertEquals(PbNodeTypeEnumType.LOCATION.name(), status(vfresh, OlioFieldNames.FIELD_PB_NODE_TYPE));
			assertTrue("a variant node is never seeded", PbArtifactUtil.listChain(u, vfresh, PbPipelineUtil.ROLE_LANDSCAPE).isEmpty());
			assertEquals(3, nodeCount(u, bk));

			/// The location DTO shows the node and its state.
			Map<String, Object> dto = PbServiceFacade.getLocation(u, bkOid, locOid);
			@SuppressWarnings("unchecked")
			Map<String, Object> nodeDto = (Map<String, Object>) dto.get("node");
			assertNotNull("the DTO carries the node once it exists", nodeDto);
			assertEquals(oidOf(n), nodeDto.get("objectId"));
			assertEquals(1, maps(dto, "usedByScenes").size());
			assertEquals(oidOf(scene), maps(dto, "usedByScenes").get(0).get("sceneObjectId"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 8. live render on the Swarm node (SD-gated) ───────────────────────────

	@Test
	public void case08LiveRenderBindsAndApprovePins() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P3-4 location render");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		assertEquals("precondition: the deployment default this JVM resolves is the installed checkpoint (BaseTest pins it)",
			installed, SDUtil.getDefaultModel());
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "lv");
		String bkOid = oidOf(bk);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		try {
			BaseRecord loc = newLocation(u, bk, "Quay Tavern " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", "the tavern by the quay");
			pointSceneAt(u, scene, loc);
			assertEquals(0, nodeCount(u, bk));

			long start = System.nanoTime();
			Map<String, Object> out = PbServiceFacade.renderLocation(u, bkOid, locOid, null, sdu);
			long ms = (System.nanoTime() - start) / 1_000_000L;
			logger.info("P3-4 location DTO: " + JSONUtil.exportObject(out));
			assertEquals(Boolean.TRUE, out.get("executed"));
			assertEquals(Boolean.TRUE, out.get("nodeMaterialized"));
			assertEquals(Integer.valueOf(0), out.get("seeded"));
			assertEquals(locOid, out.get("locationObjectId"));
			assertNull(out.get("variantObjectId"));
			assertEquals("DONE_UNVERIFIED", out.get("nodeStatus"));
			assertEquals(PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
			assertEquals(List.of(RenderPlan.STAGE_BASE), out.get("stagesPlanned"));
			assertEquals(List.of(RenderPlan.STAGE_BASE), out.get("stagesRendered"));
			assertEquals(PbPipelineUtil.locationHandle(locOid), out.get("handle"));
			assertEquals(1, nodeCount(u, bk));

			String nodeOid = (String) out.get("nodeObjectId");
			BaseRecord n = PbGraphUtil.readNode(u, nodeOid, orgId);
			assertNotNull(n);
			assertEquals(PbNodeTypeEnumType.LOCATION.name(), status(n, OlioFieldNames.FIELD_PB_NODE_TYPE));
			assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(n, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals(PbPipelineUtil.SCOPE_LOCATION, n.get(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals(locOid, n.get(OlioFieldNames.FIELD_PB_SCOPE_REF));

			String artifactOid = (String) out.get("artifactObjectId");
			assertNotNull(artifactOid);
			BaseRecord selected = PbArtifactUtil.findSelected(u, n, PbPipelineUtil.ROLE_LANDSCAPE);
			assertNotNull("the render is the node's selected landscape", selected);
			assertEquals(artifactOid, oidOf(selected));
			BaseRecord artifact = readArtifact(u, artifactOid);
			assertEquals(PbArtifactTypeEnumType.IMAGE.name(), status(artifact, OlioFieldNames.FIELD_PB_ARTIFACT_TYPE));
			assertEquals(PbReviewStatusEnumType.GENERATED.name(), status(artifact, OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			BaseRecord snapshot = artifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
			assertNotNull(snapshot);
			assertEquals("the snapshot freezes the checkpoint that rendered", installed, snapshot.get(OlioFieldNames.FIELD_SD_MODEL));
			String snapDesc = snapshot.get(FieldNames.FIELD_DESCRIPTION);
			assertNotNull(snapDesc);
			assertTrue("the prompt sent carries the location description: " + snapDesc, snapDesc.contains("low-beamed tavern"));
			Object durationMs = artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS);
			assertTrue("durationMs recorded (presence only): " + durationMs, durationMs instanceof Number && ((Number) durationMs).longValue() > 0L);

			BaseRecord binding = PbPipelineUtil.findBinding(u, n, PbPipelineUtil.ROLE_LOCATION, 0);
			assertNotNull("the node binds the location record after the render", binding);
			assertEquals(OlioModelNames.MODEL_PB_LOCATION, binding.get(OlioFieldNames.FIELD_PB_REF_MODEL));
			assertEquals(locOid, binding.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			assertNotNull(binding.get(OlioFieldNames.FIELD_PB_REF_HASH));
			assertNull("no variant binding on a location node", PbPipelineUtil.findBinding(u, n, PbPipelineUtil.ROLE_LOCATION, 1));

			byte[] bytes = artifactBytes(u, artifact);
			File png = new File("target", "p34-location-" + slug + ".png");
			Files.write(png.toPath(), bytes);
			logger.info("MEASURED: P3-4 location render on " + server + " model=" + snapshot.get(OlioFieldNames.FIELD_SD_MODEL)
				+ " wallMs=" + ms + " durationMs=" + durationMs + " bytes=" + bytes.length + " png=" + png.getAbsolutePath());
			assertNull("a render never approves itself (autoApprove off)", mustReadLocation(u, locOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			/// Approve: the pointer moves, the node is pinned, a re-render is refused.
			PbServiceFacade.approveArtifact(u, bkOid, artifactOid);
			assertEquals(idOf(artifact), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(Boolean.TRUE, PbGraphUtil.readNode(u, nodeOid, orgId).get(OlioFieldNames.FIELD_PB_PINNED));
			expect("render a pinned node", 409, "is pinned", () -> PbServiceFacade.renderLocation(u, bkOid, locOid, null, sdu));
			assertEquals("the pinned render is untouched", 1, PbArtifactUtil.listChain(u, n, PbPipelineUtil.ROLE_LANDSCAPE).size());
			Map<String, Object> dto = PbServiceFacade.getLocation(u, bkOid, locOid);
			assertEquals(artifactOid, dto.get("approvedRenderObjectId"));

			/// Variant: its own node, both bindings, its own image.
			Map<String, Object> v = PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "night", null, null, null, null);
			String vOid = (String) v.get("objectId");
			start = System.nanoTime();
			Map<String, Object> vout = PbServiceFacade.renderLocation(u, bkOid, locOid, vOid, sdu);
			long vms = (System.nanoTime() - start) / 1_000_000L;
			logger.info("P3-4 variant DTO: " + JSONUtil.exportObject(vout));
			assertEquals(Boolean.TRUE, vout.get("executed"));
			assertEquals(locOid, vout.get("locationObjectId"));
			assertEquals(vOid, vout.get("variantObjectId"));
			assertEquals(PbPipelineUtil.locationVariantHandle(vOid), vout.get("handle"));
			assertEquals("DONE_UNVERIFIED", vout.get("nodeStatus"));
			assertEquals(2, nodeCount(u, bk));
			BaseRecord vn = PbGraphUtil.readNode(u, (String) vout.get("nodeObjectId"), orgId);
			assertEquals(PbPipelineUtil.SCOPE_LOCATION_VARIANT, vn.get(OlioFieldNames.FIELD_PB_SCOPE));
			assertEquals(vOid, vn.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
			BaseRecord vb0 = PbPipelineUtil.findBinding(u, vn, PbPipelineUtil.ROLE_LOCATION, 0);
			BaseRecord vb1 = PbPipelineUtil.findBinding(u, vn, PbPipelineUtil.ROLE_LOCATION, 1);
			assertNotNull(vb0);
			assertNotNull(vb1);
			assertEquals(OlioModelNames.MODEL_PB_LOCATION, vb0.get(OlioFieldNames.FIELD_PB_REF_MODEL));
			assertEquals(locOid, vb0.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			assertEquals(OlioModelNames.MODEL_PB_LOCATION_VARIANT, vb1.get(OlioFieldNames.FIELD_PB_REF_MODEL));
			assertEquals(vOid, vb1.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			BaseRecord vArtifact = readArtifact(u, (String) vout.get("artifactObjectId"));
			BaseRecord vSnapshot = vArtifact.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
			assertNotNull(vSnapshot);
			String vDesc = vSnapshot.get(FieldNames.FIELD_DESCRIPTION);
			assertTrue("the variant prompt carries the delta: " + vDesc, vDesc != null && vDesc.toLowerCase().contains("night"));
			byte[] vbytes = artifactBytes(u, vArtifact);
			File vpng = new File("target", "p34-location-variant-" + slug + ".png");
			Files.write(vpng.toPath(), vbytes);
			logger.info("MEASURED: P3-4 variant render on " + server + " wallMs=" + vms + " bytes=" + vbytes.length + " png=" + vpng.getAbsolutePath());
			assertNull("the variant's own approvedRender is still empty", PbLocationUtil.readVariant(u, vOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals("the location's pointer is untouched by a variant render", idOf(artifact), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			/// Approving the variant's render pins it on the VARIANT record, never the parent.
			PbServiceFacade.approveArtifact(u, bkOid, oidOf(vArtifact));
			assertEquals(idOf(vArtifact), fkId(PbLocationUtil.readVariant(u, vOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(idOf(artifact), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 9. force never bypasses the lock or the pin ───────────────────────────

	/** Every {@code olio.pb.run} of {@code workflow} as the owner sees it, uncached (a refusal must open none). */
	private int runCount(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(u, q).getResults();
		return (recs == null ? 0 : recs.length);
	}

	/** Bounded wait for a forced job; timings are never pass/fail, only "did it reach a terminal state". */
	private static final long FORCED_JOB_WAIT_MS = 180_000L;

	@Test
	public void case09ForceNeverBypassesLockOrPin() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "fo");
		String bkOid = oidOf(bk);
		String api = SDAPIEnumType.SWARM.name();
		try {
			BaseRecord loc = newLocation(u, bk, "Forced Gate " + shortId(), DESCRIPTION);
			String locOid = oidOf(loc);
			String locName = loc.get(FieldNames.FIELD_NAME);

			/// (a) locked + force: the lock answers before the workflow is even materialized.
			assertEquals(Boolean.TRUE, PbServiceFacade.lockLocation(u, bkOid, locOid, true).get("locked"));
			assertNull("no workflow before the forced attempt", PbGraphUtil.findWorkflow(u, bk));
			int jobsBefore = AsyncJobRegistry.activeCount(u);
			expect("force=true on a locked location", 409, "Location " + locName + " is locked; unlock it to render it",
				() -> PbServiceFacade.requestLocationRender(u, bkOid, locOid, null, api, DEAD_SERVER, true));
			assertNull("force did not get past the lock: still no workflow", PbGraphUtil.findWorkflow(u, bk));
			assertEquals("no job was queued", jobsBefore, AsyncJobRegistry.activeCount(u));
			assertEquals(Boolean.FALSE, PbServiceFacade.lockLocation(u, bkOid, locOid, false).get("locked"));

			/// (b) pinned + force: approve pins the node; the pin answers before a run row or a job exists.
			BaseRecord wf = workflow(u, bk);
			BaseRecord n = locationNode(u, bk, wf, locOid);
			BaseRecord a1 = landscapeArtifact(u, bk, n, Color.ORANGE);
			PbServiceFacade.approveArtifact(u, bkOid, oidOf(a1));
			assertEquals(Boolean.TRUE, PbGraphUtil.readNode(u, oidOf(n), orgId).get(OlioFieldNames.FIELD_PB_PINNED));
			assertEquals(idOf(a1), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			int runsBefore = runCount(u, wf);
			assertEquals("no run has been opened on this workflow yet", 0, runsBefore);
			jobsBefore = AsyncJobRegistry.activeCount(u);

			PictureBookException pinned = expect("force=true on a pinned node", 409, "is pinned",
				() -> PbServiceFacade.requestLocationRender(u, bkOid, locOid, null, api, DEAD_SERVER, true));
			assertTrue("the refusal names the node: " + pinned.getMessage(), pinned.getMessage().contains(PbPipelineUtil.locationHandle(locOid)));
			assertEquals("a pinned refusal opens no run", runsBefore, runCount(u, wf));
			assertEquals("a pinned refusal queues no job", jobsBefore, AsyncJobRegistry.activeCount(u));
			assertEquals("the approved render is untouched", PbReviewStatusEnumType.APPROVED.name(), status(readArtifact(u, oidOf(a1)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertEquals("the pointer is untouched", idOf(a1), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals("no candidate was added", 1, PbArtifactUtil.listChain(u, n, PbPipelineUtil.ROLE_LANDSCAPE).size());
			assertEquals(1, nodeCount(u, bk));
			/// The synchronous seam is gated the same way (case08 proves it on the live node; this needs no SD).
			expect("sync render of a pinned node", 409, "is pinned",
				() -> PbServiceFacade.renderLocation(u, bkOid, locOid, null, new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER)));
			assertEquals(runsBefore, runCount(u, wf));

			/// (c) unpinned + current: force=false is "nothing to render", force=true submits. This is what proves the
			/// two refusals above came from the gates and not from force being dropped on the floor.
			assertEquals(Boolean.FALSE, PbServiceFacade.setPinned(u, bkOid, oidOf(n), false).get("pinned"));
			markRendered(u, n, bk);
			PbStaleUtil.clear();
			PictureBookException current = expect("force=false on a current node", 409, "Nothing to render",
				() -> PbServiceFacade.requestLocationRender(u, bkOid, locOid, null, api, DEAD_SERVER, false));
			assertTrue("the planner counts it as reused: " + current.getMessage(), current.getMessage().contains("(1 reused, 0 pinned)"));
			assertEquals("a planner refusal opens no run", runsBefore, runCount(u, wf));

			Map<String, Object> out = PbServiceFacade.requestLocationRender(u, bkOid, locOid, null, api, DEAD_SERVER, true);
			logger.info("forced submit: " + JSONUtil.exportObject(out));
			assertEquals(Integer.valueOf(1), out.get("plannedNodeCount"));
			assertEquals(List.of(PbPipelineUtil.locationHandle(locOid)), out.get("planned"));
			assertEquals(Integer.valueOf(0), out.get("reusedNodeCount"));
			assertEquals(Integer.valueOf(0), out.get("pinnedNodeCount"));
			assertEquals(locOid, out.get("locationObjectId"));
			assertEquals(oidOf(n), out.get("nodeObjectId"));
			String jobId = (String) out.get("jobId");
			String runOid = (String) out.get("runObjectId");
			assertNotNull(jobId);
			assertNotNull(runOid);
			assertEquals("force opened exactly one run", runsBefore + 1, runCount(u, wf));

			long start = System.nanoTime();
			AsyncJob job = PbRenderJobUtil.awaitTerminal(u, jobId, FORCED_JOB_WAIT_MS);
			long ms = (System.nanoTime() - start) / 1_000_000L;
			assertNotNull("the forced job reached a terminal state within " + FORCED_JOB_WAIT_MS + " ms", job);
			logger.info("MEASURED: forced location job " + jobId + " -> " + job.getStatus() + " in " + ms + " ms; result=" + job.getResult() + " error=" + job.getError());
			assertTrue(job.isTerminal());
			BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
			assertNotNull("the run is readable", run);
			assertEquals("a dead renderer fails the forced run", PbRunStatusEnumType.FAILED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			BaseRecord after = PbGraphUtil.readNode(u, oidOf(n), orgId);
			assertEquals("the node the forced run executed is FAILED, not DONE", PbNodeStatusEnumType.FAILED.name(), status(after, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals("the approved render survives a failed forced render", idOf(a1), fkId(mustReadLocation(u, locOid, orgId), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), status(readArtifact(u, oidOf(a1)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 10. merge when the duplicate already has a LOCATION node ──────────────

	@Test
	public void case10MergeWithAMaterializedNodeOnTheDuplicate() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "mn");
		String bkOid = oidOf(bk);
		try {
			BaseRecord survivor = newLocation(u, bk, "The Harbour Inn " + shortId(), DESCRIPTION);
			BaseRecord dup = PbLocationUtil.getCreateLocation(u, bk, "Harbour Inn Taproom " + shortId(), "the taproom of the harbour inn", PbLocationKindEnumType.INTERIOR, null);
			assertNotNull(dup);
			String sOid = oidOf(survivor);
			String dOid = oidOf(dup);
			BaseRecord dupPoi = dup.get(OlioFieldNames.FIELD_PB_POI);
			assertNotNull(dupPoi);

			/// The duplicate was rendered (a LOCATION node with a candidate), and a scene uses it.
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", "in the taproom");
			pointSceneAt(u, scene, dup);
			BaseRecord wf = workflow(u, bk);
			BaseRecord dn = locationNode(u, bk, wf, dOid);
			BaseRecord da = landscapeArtifact(u, bk, dn, Color.CYAN);
			assertEquals(Boolean.TRUE, da.get(OlioFieldNames.FIELD_PB_SELECTED));
			assertEquals(1, nodeCount(u, bk));
			assertFalse(PbLocationUtil.isLocked(mustReadLocation(u, sOid, orgId)));

			Map<String, Object> out = PbServiceFacade.mergeLocations(u, bkOid, dOid, sOid);
			logger.info("merge: " + JSONUtil.exportObject(out));
			assertEquals(Boolean.TRUE, out.get("merged"));
			assertNull("the duplicate is gone", readLocation(u, dOid, orgId));
			assertFalse("the duplicate's POI is gone", PbLocationUtil.poiExists(dupPoi, orgId));
			assertEquals("the scene points at the survivor", idOf(survivor), fkId(readScene(u, oidOf(scene), orgId), FieldNames.FIELD_LOCATION));

			/// The survivor keeps its own lock decision (it was unlocked; a merge never locks it).
			BaseRecord sur = mustReadLocation(u, sOid, orgId);
			assertFalse("the survivor is still unlocked after the merge", PbLocationUtil.isLocked(sur));
			assertEquals(Boolean.FALSE, PbServiceFacade.getLocation(u, bkOid, sOid).get("locked"));
			assertEquals("a merge never promotes the duplicate's candidate", 0L, fkId(sur, OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			/// Nothing in this book's workflow may still point at the deleted duplicate: its LOCATION node (handle
			/// location@<dupOid>, scope LOCATION, scopeRef dupOid) and that node's artifacts must be gone - the same
			/// clean-up deleteLocation's "locationNodes" step performs.
			BaseRecord orphan = PbPipelineUtil.findNodeByHandle(u, wf, PbPipelineUtil.locationHandle(dOid));
			if(orphan != null) {
				BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(orphan), orgId);
				logger.error("ORPHAN after merge: handle=" + fresh.get(OlioFieldNames.FIELD_PB_HANDLE) + " scope=" + fresh.get(OlioFieldNames.FIELD_PB_SCOPE)
					+ " scopeRef=" + fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF) + " status=" + status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS)
					+ " chain=" + PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_LANDSCAPE).size());
			}
			assertNull("the duplicate's LOCATION node is removed by the merge (it points at a location that no longer exists)", orphan);
			for(BaseRecord node : PbGraphUtil.listNodes(u, wf)) {
				assertNotEquals("no node of this workflow still has scopeRef = the deleted duplicate (" + node.get(OlioFieldNames.FIELD_PB_HANDLE) + ")",
					dOid, node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
			}
			assertNull("the duplicate's candidate artifact is gone with its node", PbArtifactUtil.readArtifact(u, oidOf(da), orgId));
			assertEquals(0, nodeCount(u, bk));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─── 11. hardening: messages, control characters, variant cap, foreign-world subject ──

	@Test
	public void case11HardeningMessagesCleanTextVariantCapAndForeignWorldSubject() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord series = PbSeriesUtil.getCreateSeries(u, dataPath(), "p34hs" + tag, "P3-4 hardening series " + tag);
		assertNotNull("series", series);
		BaseRecord chA = null;
		BaseRecord chB = null;
		BaseRecord other = null;
		BaseRecord otherS = null;
		try {
			chA = PbBookUtil.createBook(u, dataPath(), "p34ha" + tag, "P3-4 hardening A " + tag, series, Integer.valueOf(0));
			chB = PbBookUtil.createBook(u, dataPath(), "p34hb" + tag, "P3-4 hardening B " + tag, series, Integer.valueOf(1));
			assertNotNull(chA);
			assertNotNull(chB);
			final BaseRecord bookA = chA;
			String chAOid = oidOf(chA);

			/// (b) Control characters in caller text never reach the record, the log or a 4xx body.
			BaseRecord loc = PbLocationUtil.getCreateLocation(u, chA, "Quay\r\nHouse\t" + tag, DESCRIPTION, PbLocationKindEnumType.INTERIOR,
				List.of("the\u0007quay", " the  long\u000Bbar ", "\u001b[31mred\u001b[0m"));
			assertNotNull(loc);
			String locOid = oidOf(loc);
			String locName = loc.get(FieldNames.FIELD_NAME);
			assertEquals("CR LF and TAB in the name become single spaces", "Quay House " + tag, locName);
			List<String> aliases = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertTrue("alias with a BEL stripped: " + aliases, aliases.contains("the quay"));
			assertTrue("alias with a VT stripped and its whitespace squeezed: " + aliases, aliases.contains("the long bar"));
			assertTrue("ANSI escape bytes stripped: " + aliases, aliases.contains("[31mred [0m"));
			for(String a : aliases) {
				assertFalse("no control character survives in alias '" + a + "'", a.chars().anyMatch(Character::isISOControl));
			}
			/// The same text names the same location (dedupe is on the cleaned name).
			assertEquals(locOid, oidOf(PbLocationUtil.getCreateLocation(u, chA, "Quay House\n" + tag, null, null, null)));
			/// A name that is nothing but control characters is "no name".
			expect("control-only name", 400, "A location needs a name",
				() -> PbLocationUtil.getCreateLocation(u, bookA, "\r\n\t\u0000", null, null, null));
			/// Variant deltas are cleaned the same way.
			Map<String, Object> v = PbServiceFacade.createLocationVariant(u, chAOid, locOid, "Quay\u0000House\r\nat night " + tag, "dusk\u0007", null, null, null, null);
			assertEquals("Quay House at night " + tag, v.get("name"));
			assertEquals("dusk", v.get("timeOfDay"));

			/// (a) The delete gate is world-wide, the count is this book's only: a reference from another chapter is
			/// named as such with no number; a reference in this book reports its count.
			BaseRecord sceneB = addScene(u, chB, 0, "Chapter B scene", "on the quay");
			pointSceneAt(u, sceneB, loc);
			PictureBookException crossChapter = expect("delete while chapter B references it", 409, "is still the location of a scene in another chapter",
				() -> PbServiceFacade.deleteLocation(u, chAOid, locOid));
			assertFalse("the other chapter's count is not disclosed: " + crossChapter.getMessage(), crossChapter.getMessage().matches(".*\\d+ scene.*"));
			assertNotNull("the location was not deleted", readLocation(u, locOid, orgId));
			BaseRecord sceneA = addScene(u, chA, 0, "Chapter A scene", "on the quay");
			pointSceneAt(u, sceneA, loc);
			expect("delete while this chapter references it", 409, "is still the location of 1 scene(s)",
				() -> PbServiceFacade.deleteLocation(u, chAOid, locOid));
			unassignScene(u, sceneA);
			unassignScene(u, sceneB);

			/// (c) The 33rd variant of one location is refused.
			BaseRecord capLoc = PbLocationUtil.getCreateLocation(u, chA, "Crowded Market " + tag, "a market square", PbLocationKindEnumType.EXTERIOR, null);
			assertNotNull(capLoc);
			String capOid = oidOf(capLoc);
			long t0 = System.currentTimeMillis();
			for(int i = 0; i < PbLocationUtil.MAX_VARIANTS_PER_LOCATION; i++) {
				Map<String, Object> vi = PbServiceFacade.createLocationVariant(u, chAOid, capOid, null, "hour " + i, null, null, null, null);
				assertNotNull("variant " + i, vi.get("objectId"));
			}
			logger.info("MEASURED: " + PbLocationUtil.MAX_VARIANTS_PER_LOCATION + " variants created in " + (System.currentTimeMillis() - t0) + " ms");
			assertEquals(PbLocationUtil.MAX_VARIANTS_PER_LOCATION, PbLocationUtil.variantsOf(capLoc, orgId).size());
			expect("33rd variant", 409, "the limit is " + PbLocationUtil.MAX_VARIANTS_PER_LOCATION,
				() -> PbServiceFacade.createLocationVariant(u, chAOid, capOid, null, "hour 32", null, null, null, null));
			assertEquals("nothing was created past the cap", PbLocationUtil.MAX_VARIANTS_PER_LOCATION, PbLocationUtil.variantsOf(capLoc, orgId).size());
			/// A duplicate name is still the duplicate 409, not the cap.
			expect("duplicate past the cap", 409, "already has a variant named",
				() -> PbServiceFacade.createLocationVariant(u, chAOid, capOid, null, "hour 0", null, null, null, null));

			/// (d) A LOCATION node whose scopeRef is another world's location: approve and reject are 404 at the
			/// facade and nothing is promoted or pinned - on either location.
			other = newBook(u, "hw");
			BaseRecord otherLoc = newLocation(u, other, "Far Lighthouse " + tag, DESCRIPTION);
			String otherOid = oidOf(otherLoc);
			assertFalse("the two books are in different worlds", PbLocationUtil.sameWorld(chA, otherLoc));
			BaseRecord wfA = workflow(u, chA);
			BaseRecord foreignNode = locationNode(u, chA, wfA, otherOid);
			BaseRecord fa = landscapeArtifact(u, chA, foreignNode, Color.MAGENTA);
			assertEquals(Boolean.TRUE, fa.get(OlioFieldNames.FIELD_PB_SELECTED));
			expect("approve with a foreign-world subject", 404, "Location not found in this book's world",
				() -> PbServiceFacade.approveArtifact(u, chAOid, oidOf(fa)));
			expect("reject with a foreign-world subject", 404, "Location not found in this book's world",
				() -> PbServiceFacade.rejectArtifact(u, chAOid, oidOf(fa)));
			assertEquals("the artifact is untouched", PbReviewStatusEnumType.GENERATED.name(), status(readArtifact(u, oidOf(fa)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertNotEquals("the node is not pinned", Boolean.TRUE, PbGraphUtil.readNode(u, oidOf(foreignNode), orgId).get(OlioFieldNames.FIELD_PB_PINNED));
			assertNull("the other world's location gained no approvedRender", mustReadLocation(u, otherOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertNull("this world's location gained none either", mustReadLocation(u, locOid, orgId).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			/// A dangling scopeRef has no record to protect: reject of a plain candidate still works, approve is the
			/// util's own 404 for the missing location.
			String gone = UUID.randomUUID().toString();
			BaseRecord danglingNode = locationNode(u, chA, wfA, gone);
			BaseRecord da = landscapeArtifact(u, chA, danglingNode, Color.PINK);
			expect("approve on a dangling scopeRef", 404, "Location " + gone + " of node",
				() -> PbServiceFacade.approveArtifact(u, chAOid, oidOf(da)));
			PbServiceFacade.rejectArtifact(u, chAOid, oidOf(da));
			assertEquals(PbReviewStatusEnumType.REJECTED.name(), status(readArtifact(u, oidOf(da)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));

			/// (e) "Gone" and "unreadable" are not the same thing. A node whose scopeRef names a location that exists
			/// but that this caller cannot read (another user's world) is refused 404 by approve AND reject, exactly
			/// like the foreign-world case - not treated as dangling (a dangling scopeRef lets reject proceed, above).
			/// Otherwise a caller with update on the book but no read on the location could stamp a candidate of a
			/// LOCKED location REJECTED without ever seeing the lock.
			/// The stranger pins a render of their own book on the location by the product path (approve) and then
			/// locks it. PBAC reads a record only when every populated foreign record is readable too
			/// (PolicyUtil.getSchemaRules adds a READ pattern per foreign field), so the pinned render has to be one
			/// the stranger can read: a render from the owner's book pinned here - a state approve never produces -
			/// would make the stranger's own location unreadable to the stranger.
			BaseRecord s = stranger();
			otherS = newBook(s, "hx");
			BaseRecord strangerLoc = newLocation(s, otherS, "Hidden Cove " + tag, DESCRIPTION);
			String strangerOid = oidOf(strangerLoc);
			assertNull("the owner cannot read the stranger's location", readLocation(u, strangerOid, orgId));
			BaseRecord wfS = workflow(s, otherS);
			BaseRecord strangerNode = locationNode(s, otherS, wfS, strangerOid);
			BaseRecord sa = landscapeArtifact(s, otherS, strangerNode, Color.ORANGE);
			PbServiceFacade.approveArtifact(s, oidOf(otherS), oidOf(sa));
			assertEquals("the stranger's own render is pinned", idOf(sa), fkId(mustReadLocation(s, strangerOid, orgOf(s)), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			PbServiceFacade.lockLocation(s, oidOf(otherS), strangerOid, true);
			assertTrue("locked", PbLocationUtil.isLocked(mustReadLocation(s, strangerOid, orgOf(s))));
			BaseRecord unreadableNode = locationNode(u, chA, wfA, strangerOid);
			BaseRecord ua = landscapeArtifact(u, chA, unreadableNode, Color.CYAN);
			assertTrue("the record exists for the existence test even though the caller cannot read it", PbLocationUtil.subjectExists(PbGraphUtil.readNode(u, oidOf(unreadableNode), orgId)));
			expect("approve with an unreadable subject", 404, "Location not found in this book's world",
				() -> PbServiceFacade.approveArtifact(u, chAOid, oidOf(ua)));
			expect("reject with an unreadable subject", 404, "Location not found in this book's world",
				() -> PbServiceFacade.rejectArtifact(u, chAOid, oidOf(ua)));
			assertEquals("the artifact is untouched", PbReviewStatusEnumType.GENERATED.name(), status(readArtifact(u, oidOf(ua)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			assertNotEquals("the node is not pinned", Boolean.TRUE, PbGraphUtil.readNode(u, oidOf(unreadableNode), orgId).get(OlioFieldNames.FIELD_PB_PINNED));
			assertEquals("the locked location keeps its approved render", idOf(sa), fkId(mustReadLocation(s, strangerOid, orgOf(s)), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertTrue("still locked", PbLocationUtil.isLocked(mustReadLocation(s, strangerOid, orgOf(s))));
			/// The util-level reject path is the same answer: PbArtifactUtil.reject does not treat it as dangling.
			expect("util reject with an unreadable subject", 404, "of node",
				() -> PbArtifactUtil.reject(u, readArtifact(u, oidOf(ua))));
			assertEquals("still untouched", PbReviewStatusEnumType.GENERATED.name(), status(readArtifact(u, oidOf(ua)), OlioFieldNames.FIELD_PB_REVIEW_STATUS));
			/// Unlock before teardown so the stranger's book can be deleted.
			PbServiceFacade.lockLocation(s, oidOf(otherS), strangerOid, false);
		}
		finally {
			deleteBook(stranger(), otherS);
			deleteBook(u, other);
			deleteBook(u, chA);
			try {
				int chapters = PbSeriesUtil.deleteSeries(u, oidOf(series));
				logger.info("deleteSeries removed " + chapters + " chapter(s)");
			}
			catch(Exception e) {
				logger.error("deleteSeries: " + e.getMessage());
				deleteBook(u, chB);
			}
		}
	}

	// ─── 12. health: a LOCATION node left dangling by another chapter's delete or merge ──

	/**
	 * A single-book delete or merge removes only its own book's LOCATION nodes (no cross-book writes); the other
	 * chapter's node keeps a scopeRef that no longer resolves. {@code PbHealthUtil} reports each as
	 * {@code LOCATION_NODE_DANGLING} for that chapter and the heal removes the node with its candidate renders.
	 * A node whose location still exists is never reported.
	 */
	@Test
	public void case12HealthFindsAndHealsNodeDanglingAfterCrossChapterDelete() throws Exception {
		BaseRecord u = owner();
		long orgId = orgOf(u);
		String tag = shortId();
		BaseRecord series = PbSeriesUtil.getCreateSeries(u, dataPath(), "p34ds" + tag, "P3-4 dangling series " + tag);
		assertNotNull("series", series);
		BaseRecord chA = null;
		BaseRecord chB = null;
		try {
			chA = PbBookUtil.createBook(u, dataPath(), "p34da" + tag, "P3-4 dangling A " + tag, series, Integer.valueOf(0));
			chB = PbBookUtil.createBook(u, dataPath(), "p34db" + tag, "P3-4 dangling B " + tag, series, Integer.valueOf(1));
			assertNotNull(chA);
			assertNotNull(chB);
			String chAOid = oidOf(chA);
			String chBOid = oidOf(chB);

			BaseRecord shared = newLocation(u, chA, "Shared Tavern " + tag, DESCRIPTION);
			BaseRecord keep = newLocation(u, chA, "Kept Chapel " + tag, DESCRIPTION);
			BaseRecord dup = newLocation(u, chA, "Old Mill " + tag, DESCRIPTION);
			BaseRecord survivor = newLocation(u, chA, "The Mill " + tag, DESCRIPTION);
			String sharedOid = oidOf(shared);
			String dupOid = oidOf(dup);
			assertTrue("chapters share one world", PbLocationUtil.sameWorld(chB, shared));

			BaseRecord wfB = workflow(u, chB);
			BaseRecord nodeShared = locationNode(u, chB, wfB, sharedOid);
			BaseRecord nodeKeep = locationNode(u, chB, wfB, oidOf(keep));
			BaseRecord nodeDup = locationNode(u, chB, wfB, dupOid);
			BaseRecord art = landscapeArtifact(u, chB, nodeShared, Color.ORANGE);
			BaseRecord dupArt = landscapeArtifact(u, chB, nodeDup, Color.MAGENTA);
			assertTrue("all subjects exist before the delete", PbLocationUtil.subjectExists(nodeShared) && PbLocationUtil.subjectExists(nodeKeep) && PbLocationUtil.subjectExists(nodeDup));

			Map<String, Object> before = PbHealthUtil.checkBook(u, chBOid);
			assertFalse("no dangling finding while the locations exist: " + findingCodes(before), findingCodes(before).contains(PbHealthUtil.LOCATION_NODE_DANGLING));

			/// Chapter A deletes the shared location: chapter B's node is left alone and now dangles.
			PbServiceFacade.deleteLocation(u, chAOid, sharedOid);
			assertNull("the location is gone", readLocation(u, sharedOid, orgId));
			assertNotNull("chapter B's node survived the other chapter's delete", PbGraphUtil.readNode(u, oidOf(nodeShared), orgId));
			assertFalse("its subject no longer exists", PbLocationUtil.subjectExists(PbGraphUtil.readNode(u, oidOf(nodeShared), orgId)));

			Map<String, Object> report = PbHealthUtil.checkBook(u, chBOid);
			List<Map<String, Object>> dangling = findingsOf(report, PbHealthUtil.LOCATION_NODE_DANGLING);
			assertEquals("exactly one dangling LOCATION node after the delete: " + findingCodes(report), 1, dangling.size());
			Map<String, Object> f = dangling.get(0);
			assertEquals(PbHealthUtil.SEV_WARN, f.get("severity"));
			assertEquals(Boolean.TRUE, f.get("healable"));
			@SuppressWarnings("unchecked")
			Map<String, Object> refs = (Map<String, Object>) f.get("refs");
			assertEquals("it names the dangling node", oidOf(nodeShared), refs.get("objectId"));
			assertEquals(sharedOid, refs.get("scopeRef"));

			/// Chapter A merges the duplicate away: same gap by the other route, chapter B's node on the duplicate dangles too.
			PbServiceFacade.mergeLocations(u, chAOid, dupOid, oidOf(survivor));
			assertNull("the duplicate is gone", readLocation(u, dupOid, orgId));
			assertNotNull("chapter B's node on the duplicate survived the other chapter's merge", PbGraphUtil.readNode(u, oidOf(nodeDup), orgId));
			report = PbHealthUtil.checkBook(u, chBOid);
			dangling = findingsOf(report, PbHealthUtil.LOCATION_NODE_DANGLING);
			assertEquals("two dangling LOCATION nodes after delete + merge: " + findingCodes(report), 2, dangling.size());
			List<String> danglingRefs = new ArrayList<>();
			for(Map<String, Object> d : dangling) {
				@SuppressWarnings("unchecked")
				Map<String, Object> r = (Map<String, Object>) d.get("refs");
				danglingRefs.add((String) r.get("scopeRef"));
			}
			assertTrue("the merged-away duplicate is reported: " + danglingRefs, danglingRefs.contains(dupOid));
			assertTrue("the deleted location is still reported: " + danglingRefs, danglingRefs.contains(sharedOid));

			Map<String, Object> healed = PbHealthUtil.healBook(u, dataPath(), chBOid, Set.of(PbHealthUtil.LOCATION_NODE_DANGLING));
			assertEquals("both dangling nodes healed: " + healedCodes(healed, "healed") + " skipped: " + healedCodes(healed, "skipped"),
				2, healedCodes(healed, "healed").stream().filter(c -> PbHealthUtil.LOCATION_NODE_DANGLING.equals(c)).count());
			assertNull("the dangling node was removed", PbGraphUtil.readNode(u, oidOf(nodeShared), orgId));
			assertNull("its candidate render went with it", PbArtifactUtil.readArtifact(u, oidOf(art), orgId));
			assertNull("the node on the merged-away duplicate was removed", PbGraphUtil.readNode(u, oidOf(nodeDup), orgId));
			assertNull("its candidate render went with it too", PbArtifactUtil.readArtifact(u, oidOf(dupArt), orgId));
			assertNotNull("the node with a live subject was left alone", PbGraphUtil.readNode(u, oidOf(nodeKeep), orgId));
			assertNotNull("the survivor still exists", readLocation(u, oidOf(survivor), orgId));
			assertFalse("clean after heal: " + findingCodes(healed), findingCodes(healed).contains(PbHealthUtil.LOCATION_NODE_DANGLING));
		}
		finally {
			deleteBook(u, chA);
			try {
				int chapters = PbSeriesUtil.deleteSeries(u, oidOf(series));
				logger.info("deleteSeries removed " + chapters + " chapter(s)");
			}
			catch(Exception e) {
				logger.error("deleteSeries: " + e.getMessage());
				deleteBook(u, chB);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> findingsOf(Map<String, Object> report, String code) {
		List<Map<String, Object>> out = new ArrayList<>();
		for(Map<String, Object> f : (List<Map<String, Object>>) report.get("findings")) {
			if(code.equals(f.get("code"))) {
				out.add(f);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<String> findingCodes(Map<String, Object> report) {
		List<String> out = new ArrayList<>();
		for(Map<String, Object> f : (List<Map<String, Object>>) report.get("findings")) {
			out.add((String) f.get("code"));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<String> healedCodes(Map<String, Object> report, String key) {
		List<String> out = new ArrayList<>();
		Object v = report.get(key);
		if(v instanceof List) {
			for(Map<String, Object> f : (List<Map<String, Object>>) v) {
				out.add((String) f.get("code"));
			}
		}
		return out;
	}
}
