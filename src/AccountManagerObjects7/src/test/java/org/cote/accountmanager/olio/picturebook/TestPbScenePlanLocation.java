package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P3-5 (PictureBook V3 design §12, §13, §19, §29; §30 row 3 "background-first in the plan"): a scene that
 * names an {@code olio.pb.location} (and optionally one of its variants) plans with the location's identity,
 * watched-field hash and approved render, and degrades to {@code LOCATION_TEXT_ONLY} when no render is
 * approved. Exercised through {@link PbRenderPlanUtil#buildScenePlan} and {@link PbServiceFacade#planScene}
 * as a non-admin owner against {@code am7db}; the one render pair uses the live Swarm node
 * ({@code test.swarm.server}) through {@link PbServiceFacade#requestRenderPlanScene} and is gated by
 * {@link SdTestGate} (a gated skip is a skip, never a pass). Every non-live case plans against a dead port:
 * the plan is a read, so a renderer that cannot be reached is an {@code INVENTORY_UNAVAILABLE} pick on the
 * plan, never an exception.
 * <ol>
 * <li>{@link #case01NoLocationIsPlainTextOnlyAndSilent}: no location named - no identity, no {@code inputs},
 *     no degradation; the description is the scene's own text.</li>
 * <li>{@link #case02UnrenderedLocationIsTextOnlyWithHashAndComposedText}: a location with no approved render -
 *     {@code LOCATION_TEXT_ONLY} on the BASE stage naming the location, {@code inputs.location} equal to the
 *     hash of the record as read, and the description composed location-first then scene text.</li>
 * <li>{@link #case03ApprovedRenderIsCarriedAndNotADegradation}: approving the location's landscape through the
 *     facade puts its artifact on the plan as {@code renderArtifactId}, moves the revision hash
 *     ({@code approvedRender} is in the watched set) and removes the degradation.</li>
 * <li>{@link #case04UnrenderedVariantOfRenderedLocationIsTextOnly}: D3 - the location's render is never a
 *     substitute for the named variant's; the message names the variant and {@code inputs.locationVariant}
 *     is present.</li>
 * <li>{@link #case05UnreadableLocationIsTextOnlyNotReadableAndNeverThrows}: a location the owner cannot read -
 *     "(not readable)", null revision hash, {@code inputs} carries the {@code location} key with a null value.
 *     The FK is set in memory on a scene the owner read: a persisted FK to an unreadable record makes the scene
 *     itself unreadable to its owner (PBAC follows every populated foreign field on READ), and no product path
 *     creates one.</li>
 * <li>{@link #case06PlanSceneFacadeCarriesLocationAndInputs}: the facade's plan map carries the same members.</li>
 * <li>{@link #case07ProjectionsCoverTheWatchedSetAndTheCallerContract}: {@code locationRequest()} /
 *     {@code locationVariantRequest()} are supersets of {@link PbWatchedFields#projection}, so one read per
 *     subject is enough; {@code sceneRequest()} names both FKs; and a scene read without them plans exactly as
 *     one that names no location (the {@code executeChapBookPage} contract until P3-6).</li>
 * <li>{@link #case08LiveRenderCarriesApprovedLocationAndTextOnlyVetoesAutoApprove}: one real render per scene
 *     on the live Swarm node - the persisted plan artifact carries the location, the node's binding hash is
 *     the plan's {@code inputs.location}, the prompt sent carries both texts, the located scene auto-approves
 *     and its unrendered twin is vetoed with {@code degraded: LOCATION_TEXT_ONLY}. SD-gated.</li>
 * <li>{@link #case09UnreadableVariantKeepsTheParentAndCarriesNoRender}: a variant the owner cannot read under a
 *     readable, approved parent - the parent's hash and text are kept, {@code inputs.locationVariant} is present
 *     and null, the parent's render never stands in (D3), "(not readable)" names the variant's objectId.</li>
 * <li>{@link #case10ApprovedVariantRenderRidesAndMovesOnlyItsOwnInput}: approving the variant's own landscape
 *     puts the VARIANT's artifact on the plan (never the parent's), moves only {@code inputs.locationVariant};
 *     a description edit on the parent then moves only {@code inputs.location} and the revision hash.</li>
 * <li>{@link #case11LocationSubjectTextOrderAndNullHandling}: the pure composer - fixed qualifier order,
 *     trimmed, "null"/blank dropped, empty when nothing is meaningful - and a location created without a
 *     description composing to the scene's text alone.</li>
 * <li>{@link #case12LocationOfAnotherWorldIsRefusedAsUnreadable}: a readable location of the owner's OTHER
 *     book (the generic model route does not world-check the FK) is refused: no hash, no text, no render, the
 *     variant falls with it, one degradation naming the location; the same location composes in its own book.</li>
 * <li>{@link #case13VariantOfAnotherLocationIsRefusedKeepingTheLocation}: a variant whose parent is not the
 *     scene's location is refused: the location's hash and text are kept, neither render rides, one
 *     degradation naming both.</li>
 * </ol>
 */
public class TestPbScenePlanLocation extends BaseTest {

	private static final String DEAD_SERVER = "http://127.0.0.1:1";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final String LOC_DESCRIPTION = "A low-beamed tavern by the quay, lanterns in the windows, barrels stacked by the door";
	private static final String SCENE_SETTING = "Rain hammers the shutters while the fiddler tunes up";

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

	// ───────────────────────────── helpers (TestPbLocationNode idioms) ─────────────────────────────

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord owner() {
		BaseRecord u = getCreateUser("pbScenePlanOwner");
		assertNotNull("owner", u);
		return u;
	}

	private BaseRecord stranger() {
		BaseRecord u = getCreateUser("pbScenePlanStranger");
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

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p35" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P3-5 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private BaseRecord readBook(BaseRecord u, BaseRecord bk) {
		BaseRecord b = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertNotNull("readBook " + bk.get(OlioFieldNames.FIELD_PB_SLUG), b);
		return b;
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
		return readScene(u, oidOf(scene));
	}

	private void pointSceneAt(BaseRecord u, BaseRecord scene, BaseRecord loc) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
		patch.set(FieldNames.FIELD_LOCATION, loc);
		assertNotNull("scene.location patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
	}

	private void pointSceneAtVariant(BaseRecord u, BaseRecord scene, BaseRecord variant) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
		patch.set(OlioFieldNames.FIELD_PB_LOCATION_VARIANT, variant);
		assertNotNull("scene.locationVariant patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
	}

	private BaseRecord readScene(BaseRecord actor, String objectId) {
		BaseRecord s = PbBookUtil.readScene(actor, objectId, orgOf(actor));
		assertNotNull("scene " + objectId, s);
		return s;
	}

	private BaseRecord mustReadLocation(BaseRecord actor, String objectId) {
		BaseRecord loc = PbLocationUtil.readLocation(actor, objectId, orgOf(actor));
		assertNotNull("location " + objectId, loc);
		return loc;
	}

	private BaseRecord mustReadVariant(BaseRecord actor, String objectId) {
		BaseRecord v = PbLocationUtil.readVariant(actor, objectId, orgOf(actor));
		assertNotNull("variant " + objectId, v);
		return v;
	}

	private BaseRecord newLocation(BaseRecord u, BaseRecord bk, String name, String description) {
		BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, name, description, PbLocationKindEnumType.INTERIOR, List.of("the taproom"));
		assertNotNull("getCreateLocation " + name, loc);
		assertNull("a fresh location has no approvedRender", loc.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return loc;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord locationNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String locOid) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord n = PbGraphUtil.addNode(u, wf, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("location node " + locOid, n);
		assertTrue("scope patch", PbPipelineUtil.patchNodeScope(u, wf, n, PbPipelineUtil.SCOPE_LOCATION, locOid, 0));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(n), orgOf(u));
		assertNotNull(fresh);
		assertEquals(locOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		return fresh;
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

	/// Approve a landscape of the location's LOCATION node through the facade so {@code approvedRender} is
	/// pinned the way the product pins it (never {@code writeApprovedRender} directly - §6.C case11 lesson).
	private BaseRecord approveLandscape(BaseRecord u, BaseRecord bk, String locOid, Color c) throws Exception {
		BaseRecord wf = workflow(u, bk);
		BaseRecord n = locationNode(u, bk, wf, locOid);
		BaseRecord a = landscapeArtifact(u, bk, n, c);
		Map<String, Object> approved = PbServiceFacade.approveArtifact(u, oidOf(bk), oidOf(a));
		assertEquals(oidOf(n), approved.get("nodeObjectId"));
		assertEquals("approve promotes the landscape to the location's approvedRender", idOf(a),
			fkId(mustReadLocation(u, locOid), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return a;
	}

	private BaseRecord variantNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String varOid) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord n = PbGraphUtil.addNode(u, wf, PbPipelineUtil.locationVariantHandle(varOid), PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("variant node " + varOid, n);
		assertTrue("scope patch", PbPipelineUtil.patchNodeScope(u, wf, n, PbPipelineUtil.SCOPE_LOCATION_VARIANT, varOid, 0));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(n), orgOf(u));
		assertNotNull(fresh);
		assertEquals(varOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		return fresh;
	}

	/// Approve a landscape of the variant's own LOCATION node: the pointer lands on the VARIANT record, never
	/// the parent (TestPbLocationNode proves the facade's side of that; this only relies on it).
	private BaseRecord approveVariantLandscape(BaseRecord u, BaseRecord bk, String varOid, Color c) throws Exception {
		BaseRecord wf = workflow(u, bk);
		BaseRecord n = variantNode(u, bk, wf, varOid);
		BaseRecord a = landscapeArtifact(u, bk, n, c);
		Map<String, Object> approved = PbServiceFacade.approveArtifact(u, oidOf(bk), oidOf(a));
		assertEquals(oidOf(n), approved.get("nodeObjectId"));
		assertEquals("approve promotes the landscape to the variant's approvedRender", idOf(a),
			fkId(mustReadVariant(u, varOid), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return a;
	}

	private String newVariant(BaseRecord u, BaseRecord bk, String locOid, String timeOfDay, String[] nameOut) {
		Map<String, Object> created = PbServiceFacade.createLocationVariant(u, oidOf(bk), locOid, null, timeOfDay, null, null, null, null);
		String varOid = (String) created.get("objectId");
		assertNotNull("variant objectId: " + created, varOid);
		String varName = (String) created.get("name");
		assertNotNull("variant name: " + created, varName);
		assertNull("a fresh variant has no approvedRender", mustReadVariant(u, varOid).get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		if(nameOut != null && nameOut.length > 0) {
			nameOut[0] = varName;
		}
		return varOid;
	}

	private BaseRecord plan(BaseRecord u, BaseRecord bk, BaseRecord scene, RenderPlan[] out) {
		out[0] = PbRenderPlanUtil.buildScenePlan(u, readBook(u, bk), scene, PbRenderPlanUtil.PlanOptions.NONE, SDAPIEnumType.SWARM, DEAD_SERVER);
		assertNotNull("plan", out[0]);
		logger.info("plan: " + out[0].toJson());
		return scene;
	}

	private RenderPlan planOf(BaseRecord u, BaseRecord bk, BaseRecord scene) {
		RenderPlan[] out = new RenderPlan[1];
		plan(u, bk, scene, out);
		return out[0];
	}

	private static List<RenderPlan.Degradation> locationDegradations(RenderPlan p) {
		List<RenderPlan.Degradation> out = new ArrayList<>();
		for(RenderPlan.Degradation d : p.getDegradations()) {
			if(RenderPlan.Degradation.LOCATION_TEXT_ONLY.equals(d.getCode())) {
				out.add(d);
			}
		}
		return out;
	}

	/// Exactly one LOCATION_TEXT_ONLY on BASE, no actor, and the full message as the inspector shows it.
	private static RenderPlan.Degradation theOneLocationDegradation(RenderPlan p, String expectedMessage) {
		List<RenderPlan.Degradation> ds = locationDegradations(p);
		assertEquals("exactly one LOCATION_TEXT_ONLY: " + p.getDegradations(), 1, ds.size());
		RenderPlan.Degradation d = ds.get(0);
		assertEquals("the shortfall is reported on the stage where the render would have been used", RenderPlan.STAGE_BASE, d.getStage());
		assertNull("not an actor degradation", d.getActorId());
		assertEquals("the message says what is missing and what the plan does instead", expectedMessage, d.getMessage());
		return d;
	}

	private static String noRenderForLocation(String nameOrOid, boolean unreadable) {
		return "No approved render for location " + nameOrOid + (unreadable ? " (not readable)" : "") + "; setting described in text";
	}

	private static String noRenderForVariant(String nameOrOid, boolean unreadable) {
		return "No approved render for variant " + nameOrOid + (unreadable ? " (not readable)" : "") + "; setting described in text";
	}

	private static void assertNoLocationDegradation(RenderPlan p) {
		assertTrue("no LOCATION_TEXT_ONLY expected: " + p.getDegradations(), locationDegradations(p).isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(Map<String, Object> out, String key) {
		Object v = out.get(key);
		return (v instanceof List ? (List<String>) v : List.of());
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

	// ───────────────────────────── cases ─────────────────────────────

	/** No location named: no identity, no {@code inputs}, no degradation; plain text-only is not a shortfall (D2). */
	@Test
	public void case01NoLocationIsPlainTextOnlyAndSilent() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "a");
		try {
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			assertNull("precondition: no location on the scene", scene.get(FieldNames.FIELD_LOCATION));

			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location loc = p.getLocation();
			assertNotNull("a scene plan always carries a Location (the setting text)", loc);
			assertNull(loc.getLocationId());
			assertNull(loc.getVariantId());
			assertNull(loc.getRevisionHash());
			assertNull(loc.getRenderArtifactId());
			assertEquals("the description is the scene's own text", SCENE_SETTING, loc.getDescription());
			assertFalse("no inputs.location when no location is named: " + p.getInputs(), p.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			assertFalse(p.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNoLocationDegradation(p);
			String json = p.toJson();
			assertTrue("on the wire the location is description-only: " + json, json.contains("\"location\":{\"description\":\"" + SCENE_SETTING + "\"}"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * A location with no approved render: {@code LOCATION_TEXT_ONLY} on BASE naming the location,
	 * {@code inputs.location} = the hash of the record as this user read it, and the description composed
	 * location-first then the scene's text (compose, not replace: the scene's action/mood is never dropped).
	 */
	@Test
	public void case02UnrenderedLocationIsTextOnlyWithHashAndComposedText() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "b");
		try {
			String locName = "The Gilded Tankard " + shortId();
			BaseRecord loc = newLocation(u, bk, locName, LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc);
			scene = readScene(u, oidOf(scene));
			assertEquals("precondition: the scene names the location", locOid, oidOf(scene.get(FieldNames.FIELD_LOCATION)));

			String expectedHash = PbWatchedFields.refHashOf(mustReadLocation(u, locOid));
			assertNotNull("a location read with locationRequest() hashes", expectedHash);

			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location pl = p.getLocation();
			assertEquals(locOid, pl.getLocationId());
			assertNull("no variant named", pl.getVariantId());
			assertEquals("revisionHash is the watched-field hash of the record as read", expectedHash, pl.getRevisionHash());
			assertNull("no approved render", pl.getRenderArtifactId());
			assertEquals("inputs.location is the same hash (one read per subject)", expectedHash, p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertFalse("no inputs.locationVariant when no variant is named", p.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));

			String desc = pl.getDescription();
			assertTrue("the description carries the location's description: " + desc, desc.contains(LOC_DESCRIPTION));
			assertTrue("...and the scene's own setting: " + desc, desc.contains(SCENE_SETTING));
			assertTrue("location first, scene text after: " + desc, desc.indexOf(LOC_DESCRIPTION) < desc.indexOf(SCENE_SETTING));
			assertEquals(PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, SCENE_SETTING)), desc);

			theOneLocationDegradation(p, noRenderForLocation(locName, false));

			/// The compiled BASE prompt is built from the composed description, so what the inspector shows is
			/// what the renderer would be sent (§13 "prompt shown = prompt sent").
			String prompt = p.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt();
			assertTrue("compiled prompt carries the location description: " + prompt, prompt.contains(LOC_DESCRIPTION));
			assertTrue("compiled prompt carries the scene setting: " + prompt, prompt.contains(SCENE_SETTING));

			/// Round trip: the persisted shape reads back identical (the plan artifact is this JSON).
			RenderPlan back = RenderPlan.fromJson(p.toJson());
			assertEquals(p.toJson(), back.toJson());
			assertEquals(p.contentHash(), back.contentHash());
			assertEquals(expectedHash, back.getInputs().get(PbPipelineUtil.ROLE_LOCATION));

			/// The plan is a read: the same scene plans the same bytes twice (no clock, no counter, no cache key).
			assertEquals("deterministic", p.toJson(), planOf(u, bk, readScene(u, oidOf(scene))).toJson());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * Approving the location's landscape through the facade: the plan carries the artifact as
	 * {@code renderArtifactId}, the revision hash moves ({@code approvedRender} is watched), no degradation.
	 * "Render carried, not consumed" (D4 option A): the plan's strategy is unchanged by the pointer.
	 */
	@Test
	public void case03ApprovedRenderIsCarriedAndNotADegradation() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "c");
		try {
			String locName = "The Gilded Tankard " + shortId();
			BaseRecord loc = newLocation(u, bk, locName, LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc);
			scene = readScene(u, oidOf(scene));

			RenderPlan before = planOf(u, bk, scene);
			String hashBefore = before.getLocation().getRevisionHash();
			assertNotNull(hashBefore);
			theOneLocationDegradation(before, noRenderForLocation(locName, false));
			PbStrategyEnumType strategyBefore = before.getStrategy().getName();

			BaseRecord approved = approveLandscape(u, bk, locOid, Color.ORANGE);
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(PbArtifactUtil.readArtifact(u, oidOf(approved), orgOf(u))));

			RenderPlan after = planOf(u, bk, readScene(u, oidOf(scene)));
			RenderPlan.Location pl = after.getLocation();
			assertEquals(locOid, pl.getLocationId());
			assertEquals("the approved render rides on the plan", oidOf(approved), pl.getRenderArtifactId());
			assertNotEquals("approvedRender is in the watched set, so the revision hash moves", hashBefore, pl.getRevisionHash());
			assertEquals(PbWatchedFields.refHashOf(mustReadLocation(u, locOid)), pl.getRevisionHash());
			assertEquals(pl.getRevisionHash(), after.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertNoLocationDegradation(after);
			assertEquals("the render is carried, not consumed: the strategy does not change", strategyBefore, after.getStrategy().getName());
			assertTrue("the description still carries both texts", pl.getDescription().contains(LOC_DESCRIPTION) && pl.getDescription().contains(SCENE_SETTING));

			String json = after.toJson();
			assertTrue(json, json.contains("\"renderArtifactId\":\"" + oidOf(approved) + "\""));
			assertTrue(json, json.contains("\"locationId\":\"" + locOid + "\""));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * D3: a scene at "the tavern at night" with only the daytime tavern approved is text-only; the location's
	 * render is never substituted for the variant's, the message names the variant, both inputs are present,
	 * and the variant's qualifiers follow the location's description in the composed text.
	 */
	@Test
	public void case04UnrenderedVariantOfRenderedLocationIsTextOnly() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "d");
		try {
			String bkOid = oidOf(bk);
			BaseRecord loc = newLocation(u, bk, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord approved = approveLandscape(u, bk, locOid, Color.ORANGE);

			Map<String, Object> created = PbServiceFacade.createLocationVariant(u, bkOid, locOid, null, "night", null, null, null, null);
			String varOid = (String) created.get("objectId");
			assertNotNull("variant objectId: " + created, varOid);
			String varName = (String) created.get("name");
			assertNotNull("variant name: " + created, varName);
			BaseRecord variant = mustReadVariant(u, varOid);
			assertNull("a fresh variant has no approvedRender", variant.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc);
			pointSceneAtVariant(u, scene, variant);
			scene = readScene(u, oidOf(scene));
			assertEquals(varOid, oidOf(scene.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT)));

			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location pl = p.getLocation();
			assertEquals(locOid, pl.getLocationId());
			assertEquals(varOid, pl.getVariantId());
			assertNull("the location's render (" + oidOf(approved) + ") is not a substitute for the variant's (D3)", pl.getRenderArtifactId());
			assertEquals(PbWatchedFields.refHashOf(mustReadLocation(u, locOid)), pl.getRevisionHash());
			assertEquals(pl.getRevisionHash(), p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			String variantHash = PbWatchedFields.refHashOf(mustReadVariant(u, varOid));
			assertNotNull("a variant read with locationVariantRequest() hashes", variantHash);
			assertEquals("inputs.locationVariant is the variant's hash as read", variantHash, p.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));

			theOneLocationDegradation(p, noRenderForVariant(varName, false));

			String desc = pl.getDescription();
			int iLoc = desc.indexOf(LOC_DESCRIPTION);
			int iNight = desc.indexOf("night");
			int iScene = desc.indexOf(SCENE_SETTING);
			assertTrue("location, then the variant qualifier, then the scene text: " + desc, iLoc >= 0 && iNight > iLoc && iScene > iNight);

			String json = p.toJson();
			assertTrue(json, json.contains("\"inputs\":{\"" + PbPipelineUtil.ROLE_LOCATION + "\":\"" + pl.getRevisionHash() + "\",\""
				+ PbRenderPlanUtil.INPUT_LOCATION_VARIANT + "\":\"" + p.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT) + "\"}"));
			assertFalse("no renderArtifactId on the wire", json.contains("renderArtifactId"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * A location the owner cannot read: text-only with "(not readable)", null revision hash, {@code inputs}
	 * carries the {@code location} key with a null value (absent on the wire), and the plan never throws.
	 * The FK is set in memory: a persisted FK to a record the owner cannot read makes the scene itself
	 * unreadable to the owner (PBAC follows every populated foreign field on READ), so the persisted form of
	 * this condition cannot be read back by its owner, and no product path writes it
	 * ({@code requireLocationOfBook} gates every pointing route).
	 */
	@Test
	public void case05UnreadableLocationIsTextOnlyNotReadableAndNeverThrows() throws Exception {
		BaseRecord u = owner();
		BaseRecord s = stranger();
		assertNotEquals(idOf(u), idOf(s));
		assertEquals("same organization", orgOf(u), orgOf(s));
		BaseRecord bk = newBook(u, "e");
		BaseRecord sbk = newBook(s, "e-stranger");
		try {
			BaseRecord sloc = newLocation(s, sbk, "The Stranger's Cellar " + shortId(), "A damp cellar nobody else may see");
			String slocOid = oidOf(sloc);
			assertNull("precondition: the owner cannot read the stranger's location", PbLocationUtil.readLocation(u, slocOid, orgOf(u)));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			scene.set(FieldNames.FIELD_LOCATION, sloc);

			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location pl = p.getLocation();
			assertEquals("the identity is still the pointer the scene carries", slocOid, pl.getLocationId());
			assertNull(pl.getVariantId());
			assertNull("no hash for a record this user cannot read", pl.getRevisionHash());
			assertNull(pl.getRenderArtifactId());
			assertEquals("no subject text from an unreadable record: the scene's own text only", SCENE_SETTING, pl.getDescription());
			assertTrue("inputs carries the location key...", p.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			assertNull("...with a null hash, as the executor's binding does", p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));

			/// "(not readable)", and the objectId - never a name the owner could not read.
			theOneLocationDegradation(p, noRenderForLocation(slocOid, true));

			String json = p.toJson();
			assertFalse("a null-valued input is absent on the wire: " + json, json.contains("\"inputs\""));
			assertTrue("the location member is identity + text only (style keeps its own revisionHash): " + json,
				json.contains("\"location\":{\"locationId\":\"" + slocOid + "\",\"description\":\"" + SCENE_SETTING + "\"}"));
		}
		finally {
			deleteBook(u, bk);
			deleteBook(s, sbk);
		}
	}

	/** The facade's plan map carries {@code location} and {@code inputs} exactly as {@code RenderPlan.toMap()} does. */
	@Test
	@SuppressWarnings("unchecked")
	public void case06PlanSceneFacadeCarriesLocationAndInputs() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "f");
		try {
			String bkOid = oidOf(bk);
			BaseRecord loc = newLocation(u, bk, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc);
			String expectedHash = PbWatchedFields.refHashOf(mustReadLocation(u, locOid));

			Map<String, Object> out = PbServiceFacade.planScene(u, bkOid, oidOf(scene), null, "SWARM", DEAD_SERVER);
			logger.info("planScene: " + JSONUtil.exportObject(out));
			Map<String, Object> pl = (Map<String, Object>) out.get("location");
			assertNotNull("location on the plan map: " + out.keySet(), pl);
			assertEquals(locOid, pl.get("locationId"));
			assertEquals(expectedHash, pl.get("revisionHash"));
			assertNull(pl.get("renderArtifactId"));
			assertTrue(String.valueOf(pl.get("description")).contains(LOC_DESCRIPTION));
			Map<String, Object> inputs = (Map<String, Object>) out.get("inputs");
			assertNotNull("inputs on the plan map: " + out.keySet(), inputs);
			assertEquals(expectedHash, inputs.get(PbPipelineUtil.ROLE_LOCATION));
			List<Map<String, Object>> ds = (List<Map<String, Object>>) out.get("degradations");
			assertNotNull(ds);
			boolean found = false;
			for(Map<String, Object> d : ds) {
				if(RenderPlan.Degradation.LOCATION_TEXT_ONLY.equals(d.get("code"))) {
					found = true;
					assertEquals(RenderPlan.STAGE_BASE, d.get("stage"));
				}
			}
			assertTrue("LOCATION_TEXT_ONLY on the facade map: " + ds, found);
			assertNotNull(out.get("contentHash"));
			assertNotNull(out.get("estimate"));

			/// Same scene, same user, same shape: the facade is the util, not a second resolver.
			RenderPlan direct = planOf(u, bk, readScene(u, oidOf(scene)));
			assertEquals(direct.contentHash(), out.get("contentHash"));

			/// After approval the facade map carries the artifact too.
			BaseRecord approved = approveLandscape(u, bk, locOid, Color.BLUE);
			Map<String, Object> out2 = PbServiceFacade.planScene(u, bkOid, oidOf(scene), null, "SWARM", DEAD_SERVER);
			Map<String, Object> pl2 = (Map<String, Object>) out2.get("location");
			assertEquals(oidOf(approved), pl2.get("renderArtifactId"));
			assertNotEquals("the revision moved with the approval", expectedHash, pl2.get("revisionHash"));
			for(Map<String, Object> d : (List<Map<String, Object>>) out2.get("degradations")) {
				assertNotEquals("no LOCATION_TEXT_ONLY once approved: " + d, RenderPlan.Degradation.LOCATION_TEXT_ONLY, d.get("code"));
			}
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * One read per subject is only sound if the resolver's read projection covers the watched set
	 * (architect ruling 1); {@code sceneRequest()} must name both FKs for the facade path; and the
	 * projection contract itself - a scene read without the FKs plans as if none were named - is what
	 * {@code PbNodeExecutor.executeChapBookPage} relies on until P3-6. The three callers of
	 * {@code buildScenePlan} are: {@code PbServiceFacade.planScene} (reads with {@code sceneRequest()}),
	 * {@code PbNodeExecutor.executeRenderPlan} (names both FKs in its own projection; proven live in case08),
	 * and {@code PbNodeExecutor.executeChapBookPage} (omits both by design).
	 */
	@Test
	public void case07ProjectionsCoverTheWatchedSetAndTheCallerContract() throws Exception {
		Set<String> locReq = new HashSet<>(Arrays.asList(PbBookUtil.locationRequest()));
		for(String f : PbWatchedFields.projection(OlioModelNames.MODEL_PB_LOCATION)) {
			assertTrue("locationRequest() must project watched field " + f, locReq.contains(f));
		}
		Set<String> varReq = new HashSet<>(Arrays.asList(PbBookUtil.locationVariantRequest()));
		for(String f : PbWatchedFields.projection(OlioModelNames.MODEL_PB_LOCATION_VARIANT)) {
			assertTrue("locationVariantRequest() must project watched field " + f, varReq.contains(f));
		}
		Set<String> sceneReq = new HashSet<>(Arrays.asList(PbBookUtil.sceneRequest()));
		assertTrue("sceneRequest() projects location", sceneReq.contains(FieldNames.FIELD_LOCATION));
		assertTrue("sceneRequest() projects locationVariant", sceneReq.contains(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));

		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "g");
		try {
			String locName = "The Gilded Tankard " + shortId();
			BaseRecord loc = newLocation(u, bk, locName, LOC_DESCRIPTION);
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc);

			/// Read WITH the FKs: located plan.
			RenderPlan located = planOf(u, bk, readScene(u, oidOf(scene)));
			assertEquals(oidOf(loc), located.getLocation().getLocationId());
			theOneLocationDegradation(located, noRenderForLocation(locName, false));

			/// Read WITHOUT them (the ChapBook page projection shape): plans exactly as a scene that names none.
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, oidOf(scene));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, OlioFieldNames.FIELD_PB_SCENE_INDEX,
				OlioFieldNames.FIELD_PB_TITLE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION,
				OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_CB_SD_PROMPT });
			q.setCache(false);
			BaseRecord narrow = IOSystem.getActiveContext().getAccessPoint().find(u, q);
			assertNotNull(narrow);
			assertFalse("precondition: the narrow read did not project location", narrow.hasField(FieldNames.FIELD_LOCATION));

			RenderPlan unlocated = planOf(u, bk, narrow);
			assertNull("no identity from a read that did not project the FK", unlocated.getLocation().getLocationId());
			assertNull(unlocated.getLocation().getRevisionHash());
			assertEquals(SCENE_SETTING, unlocated.getLocation().getDescription());
			assertTrue(unlocated.getInputs().isEmpty());
			assertNoLocationDegradation(unlocated);
			assertNotEquals("the two reads plan differently, and the content hash says so", located.contentHash(), unlocated.contentHash());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * Live pair on the Swarm node (SD-gated): scene A at a location with an approved render, scene B at a
	 * location without one, both rendered through {@code requestRenderPlanScene} with auto-approve on.
	 * A's persisted plan artifact carries the location identity, hash and render; the RENDER_PLAN node's
	 * {@code location} binding (ordinal 0) carries the same hash as {@code inputs.location}; the prompt sent
	 * (the artifact's config snapshot) carries both the location description and the scene setting; A
	 * auto-approves (no degradations on a fresh book - the same precondition TestPbRenderPlanExecutor asserts).
	 * B's DTO lists {@code LOCATION_TEXT_ONLY} and its auto-approve is vetoed with {@code degraded: ...}
	 * (D5, transitional: the veto keys on a field the executor does not yet consume).
	 */
	@Test
	public void case08LiveRenderCarriesApprovedLocationAndTextOnlyVetoesAutoApprove() throws Exception {
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P3-5 located scene render");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		assertEquals("precondition: the deployment default this JVM resolves is the installed checkpoint (BaseTest pins it)",
			installed, SDUtil.getDefaultModel());

		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "h");
		String bkOid = oidOf(bk);
		long orgId = orgOf(u);
		try {
			BaseRecord locA = newLocation(u, bk, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locAOid = oidOf(locA);
			BaseRecord approved = approveLandscape(u, bk, locAOid, Color.ORANGE);
			String locBName = "The Unpainted Chapel " + shortId();
			BaseRecord locB = newLocation(u, bk, locBName, "A whitewashed chapel on a headland, one bell, gulls on the roof");
			String locBOid = oidOf(locB);

			BaseRecord sceneA = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, sceneA, locA);
			BaseRecord sceneB = addScene(u, bk, 1, "Scene 1", "The congregation files in under a flat grey sky");
			pointSceneAt(u, sceneB, locB);

			Map<String, Object> sw = PbServiceFacade.setAutoApprove(u, bkOid, true);
			logger.info("autoApprove: " + JSONUtil.exportObject(sw));
			assertEquals(Boolean.TRUE, readBook(u, bk).get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));

			String expectedHashA = PbWatchedFields.refHashOf(mustReadLocation(u, locAOid));

			// ── A: located, approved render ──
			long t0 = System.currentTimeMillis();
			Map<String, Object> outA = PbServiceFacade.requestRenderPlanScene(u, bkOid, oidOf(sceneA), sdu);
			long msA = System.currentTimeMillis() - t0;
			logger.info("MEASURED: P3-5 located scene render on " + server + " wallMs=" + msA + " out=" + JSONUtil.exportObject(outA));
			assertEquals(Boolean.TRUE, outA.get("executed"));
			assertEquals("v3", outA.get("renderPath"));
			String planOidA = (String) outA.get("planArtifactObjectId");
			assertNotNull("plan artifact persisted", planOidA);
			RenderPlan planA = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(u, planOidA, orgId));
			assertNotNull("the plan artifact carries the plan", planA);
			RenderPlan.Location plA = planA.getLocation();
			assertEquals(locAOid, plA.getLocationId());
			assertEquals(expectedHashA, plA.getRevisionHash());
			assertEquals("the approved render rides on the persisted plan", oidOf(approved), plA.getRenderArtifactId());
			assertEquals(expectedHashA, planA.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertNoLocationDegradation(planA);

			List<String> degA = strings(outA, "degradations");
			for(String d : degA) {
				assertFalse("no LOCATION_TEXT_ONLY for an approved location: " + degA, d.startsWith(RenderPlan.Degradation.LOCATION_TEXT_ONLY));
			}
			assertTrue("a fresh book with no style resolves at DEPLOYMENT_DEFAULT with no substitution: " + degA, degA.isEmpty());
			assertEquals("a located scene with an approved render auto-approves: " + outA.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED),
				Boolean.TRUE, outA.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), outA.get("reviewStatus"));

			String nodeAOid = (String) outA.get("nodeObjectId");
			BaseRecord nodeA = PbGraphUtil.readNode(u, nodeAOid, orgId);
			assertNotNull(nodeA);
			BaseRecord bindingA = PbPipelineUtil.findBinding(u, nodeA, PbPipelineUtil.ROLE_LOCATION, 0);
			assertNotNull("the RENDER_PLAN node binds the location after the render", bindingA);
			assertEquals(OlioModelNames.MODEL_PB_LOCATION, bindingA.get(OlioFieldNames.FIELD_PB_REF_MODEL));
			assertEquals(locAOid, bindingA.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			assertEquals("the binding's refHash is the plan's inputs.location", expectedHashA, bindingA.get(OlioFieldNames.FIELD_PB_REF_HASH));
			assertNull("no variant binding when none is named", PbPipelineUtil.findBinding(u, nodeA, PbPipelineUtil.ROLE_LOCATION, 1));

			BaseRecord artifactA = PbArtifactUtil.readArtifact(u, (String) outA.get("artifactObjectId"), orgId);
			assertNotNull(artifactA);
			BaseRecord snapA = artifactA.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
			assertNotNull("frozen config on the artifact", snapA);
			String sentA = snapA.get(FieldNames.FIELD_DESCRIPTION);
			assertNotNull(sentA);
			assertTrue("the prompt sent carries the location description: " + sentA, sentA.contains("low-beamed tavern"));
			assertTrue("...and the scene setting: " + sentA, sentA.contains("fiddler tunes up"));
			assertEquals("prompt shown = prompt sent", planA.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(), sentA);
			byte[] bytesA = artifactBytes(u, artifactA);
			File pngA = new File("target", "p35-located-" + bk.get(OlioFieldNames.FIELD_PB_SLUG) + ".png");
			Files.write(pngA.toPath(), bytesA);
			logger.info("MEASURED: P3-5 scene A model=" + snapA.get(OlioFieldNames.FIELD_SD_MODEL) + " wallMs=" + msA + " bytes=" + bytesA.length + " png=" + pngA.getAbsolutePath());

			// ── B: located, no render ──
			t0 = System.currentTimeMillis();
			Map<String, Object> outB = PbServiceFacade.requestRenderPlanScene(u, bkOid, oidOf(sceneB), sdu);
			long msB = System.currentTimeMillis() - t0;
			logger.info("MEASURED: P3-5 unrendered-location scene render on " + server + " wallMs=" + msB + " out=" + JSONUtil.exportObject(outB));
			assertNotEquals(nodeAOid, outB.get("nodeObjectId"));
			RenderPlan planB = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(u, (String) outB.get("planArtifactObjectId"), orgId));
			assertNotNull(planB);
			assertEquals(locBOid, planB.getLocation().getLocationId());
			assertNull(planB.getLocation().getRenderArtifactId());
			theOneLocationDegradation(planB, noRenderForLocation(locBName, false));

			List<String> degB = strings(outB, "degradations");
			boolean textOnly = false;
			for(String d : degB) {
				if(d.startsWith(RenderPlan.Degradation.LOCATION_TEXT_ONLY + ": ")) {
					textOnly = true;
				}
			}
			assertTrue("the DTO lists LOCATION_TEXT_ONLY: " + degB, textOnly);
			assertEquals(Boolean.FALSE, outB.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = (String) outB.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
			assertNotNull(skipped);
			assertTrue("the veto names the degradation: " + skipped, skipped.startsWith("degraded: ") && skipped.contains(RenderPlan.Degradation.LOCATION_TEXT_ONLY));
			assertNull(outB.get("reviewStatus"));
			BaseRecord artifactB = PbArtifactUtil.readArtifact(u, (String) outB.get("artifactObjectId"), orgId);
			assertEquals("the vetoed artifact stays GENERATED for human review", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(artifactB));
			BaseRecord nodeB = PbGraphUtil.readNode(u, (String) outB.get("nodeObjectId"), orgId);
			BaseRecord bindingB = PbPipelineUtil.findBinding(u, nodeB, PbPipelineUtil.ROLE_LOCATION, 0);
			assertNotNull("B binds its location too", bindingB);
			assertEquals(locBOid, bindingB.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
			assertEquals(planB.getInputs().get(PbPipelineUtil.ROLE_LOCATION), bindingB.get(OlioFieldNames.FIELD_PB_REF_HASH));
			byte[] bytesB = artifactBytes(u, artifactB);
			File pngB = new File("target", "p35-unrendered-" + bk.get(OlioFieldNames.FIELD_PB_SLUG) + ".png");
			Files.write(pngB.toPath(), bytesB);
			logger.info("MEASURED: P3-5 scene B wallMs=" + msB + " bytes=" + bytesB.length + " png=" + pngB.getAbsolutePath());
		}
		finally {
			try {
				PbServiceFacade.setAutoApprove(u, bkOid, false);
			}
			catch(Exception e) {
				logger.warn("autoApprove reset: " + e.getMessage());
			}
			deleteBook(u, bk);
		}
	}

	/**
	 * A variant the owner cannot read, under a readable parent that has an approved render: the parent's hash
	 * and description are kept, {@code inputs.locationVariant} is present with a null hash (absent on the wire),
	 * the parent's render never stands in for the named variant (D3), and the message names the variant's
	 * objectId with "(not readable)" - never a name the owner could not read. Set in memory for the same reason
	 * as case05: a persisted FK to an unreadable record makes the scene itself unreadable to its owner.
	 */
	@Test
	public void case09UnreadableVariantKeepsTheParentAndCarriesNoRender() throws Exception {
		BaseRecord u = owner();
		BaseRecord s = stranger();
		BaseRecord bk = newBook(u, "i");
		BaseRecord sbk = newBook(s, "i-stranger");
		try {
			BaseRecord loc = newLocation(u, bk, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord locRender = approveLandscape(u, bk, locOid, Color.ORANGE);

			BaseRecord sloc = newLocation(s, sbk, "The Stranger's Cellar " + shortId(), "A damp cellar nobody else may see");
			String svarOid = newVariant(s, sbk, oidOf(sloc), "night", null);
			assertNull("precondition: the owner cannot read the stranger's variant", PbLocationUtil.readVariant(u, svarOid, orgOf(u)));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc);
			scene = readScene(u, oidOf(scene));
			scene.set(OlioFieldNames.FIELD_PB_LOCATION_VARIANT, mustReadVariant(s, svarOid));

			String locHash = PbWatchedFields.refHashOf(mustReadLocation(u, locOid));
			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location pl = p.getLocation();
			assertEquals(locOid, pl.getLocationId());
			assertEquals("the variant pointer is still carried", svarOid, pl.getVariantId());
			assertEquals("the readable parent still hashes", locHash, pl.getRevisionHash());
			assertEquals(locHash, p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertTrue("inputs carries the variant key...", p.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull("...with a null hash", p.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull("D3: the parent's render (" + oidOf(locRender) + ") never stands in for a variant the user cannot read", pl.getRenderArtifactId());
			assertEquals("the parent's description composes; nothing from the unreadable variant",
				PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, SCENE_SETTING)), pl.getDescription());

			theOneLocationDegradation(p, noRenderForVariant(svarOid, true));

			String json = p.toJson();
			assertTrue("only the parent's hash is on the wire: " + json, json.contains("\"inputs\":{\"" + PbPipelineUtil.ROLE_LOCATION + "\":\"" + locHash + "\"}"));
			assertFalse(json, json.contains(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertFalse(json, json.contains("renderArtifactId"));
		}
		finally {
			deleteBook(u, bk);
			deleteBook(s, sbk);
		}
	}

	/**
	 * Approving the variant's own landscape puts the VARIANT's artifact on the plan - never the parent's - and
	 * moves only {@code inputs.locationVariant} ({@code approvedRender} is watched on the variant);
	 * {@code inputs.location} does not move. A scene at the bare location keeps riding the parent's render. A
	 * description edit on the parent then moves {@code inputs.location} and the revision hash and leaves the
	 * variant's input alone ({@code location} is not in the variant's watched set), so a stale-check keys on the
	 * input that actually changed. Non-SD twin of case08's "render rides" half for the variant path.
	 */
	@Test
	public void case10ApprovedVariantRenderRidesAndMovesOnlyItsOwnInput() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "j");
		try {
			String bkOid = oidOf(bk);
			BaseRecord loc = newLocation(u, bk, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord locRender = approveLandscape(u, bk, locOid, Color.ORANGE);
			String[] varName = new String[1];
			String varOid = newVariant(u, bk, locOid, "night", varName);

			BaseRecord sceneA = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, sceneA, loc);
			pointSceneAtVariant(u, sceneA, mustReadVariant(u, varOid));
			BaseRecord sceneB = addScene(u, bk, 1, "Scene 1", SCENE_SETTING);
			pointSceneAt(u, sceneB, loc);

			RenderPlan beforeA = planOf(u, bk, readScene(u, oidOf(sceneA)));
			String locationHash = (String) beforeA.getInputs().get(PbPipelineUtil.ROLE_LOCATION);
			String variantHashBefore = (String) beforeA.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT);
			assertNotNull(locationHash);
			assertNotNull(variantHashBefore);
			assertNull("D3 before the variant is approved", beforeA.getLocation().getRenderArtifactId());
			theOneLocationDegradation(beforeA, noRenderForVariant(varName[0], false));

			BaseRecord varRender = approveVariantLandscape(u, bk, varOid, Color.MAGENTA);
			assertNotEquals(oidOf(locRender), oidOf(varRender));
			assertEquals("the parent's pointer is untouched by the variant's approval", idOf(locRender),
				fkId(mustReadLocation(u, locOid), OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			RenderPlan afterA = planOf(u, bk, readScene(u, oidOf(sceneA)));
			RenderPlan.Location plA = afterA.getLocation();
			assertEquals(locOid, plA.getLocationId());
			assertEquals(varOid, plA.getVariantId());
			assertEquals("the VARIANT's render rides, not the parent's", oidOf(varRender), plA.getRenderArtifactId());
			assertNoLocationDegradation(afterA);
			String variantHashAfter = (String) afterA.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT);
			assertNotEquals("approvedRender is watched on the variant, so its input moves", variantHashBefore, variantHashAfter);
			assertEquals(PbWatchedFields.refHashOf(mustReadVariant(u, varOid)), variantHashAfter);
			assertEquals("inputs.location is unchanged by the variant's approval", locationHash, afterA.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertEquals("revisionHash stays the location's hash", locationHash, plA.getRevisionHash());
			assertEquals("location, variant qualifier, scene text",
				PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, "night", SCENE_SETTING)), plA.getDescription());
			assertNotEquals(beforeA.contentHash(), afterA.contentHash());

			RenderPlan planB = planOf(u, bk, readScene(u, oidOf(sceneB)));
			assertEquals("the bare location still rides the parent's render", oidOf(locRender), planB.getLocation().getRenderArtifactId());
			assertNull(planB.getLocation().getVariantId());
			assertNoLocationDegradation(planB);
			assertFalse(planB.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertEquals(locationHash, planB.getInputs().get(PbPipelineUtil.ROLE_LOCATION));

			/// Edit the parent's description through the facade: only the location's input moves.
			String newDesc = LOC_DESCRIPTION + ", now with a new sign";
			Map<String, Object> edited = PbServiceFacade.updateLocation(u, bkOid, locOid, Map.<String, Object>of(FieldNames.FIELD_DESCRIPTION, newDesc));
			assertEquals(newDesc, edited.get(FieldNames.FIELD_DESCRIPTION));
			RenderPlan editedA = planOf(u, bk, readScene(u, oidOf(sceneA)));
			String locationHashEdited = (String) editedA.getInputs().get(PbPipelineUtil.ROLE_LOCATION);
			assertNotEquals("description is watched on the location", locationHash, locationHashEdited);
			assertEquals(PbWatchedFields.refHashOf(mustReadLocation(u, locOid)), locationHashEdited);
			assertEquals(locationHashEdited, editedA.getLocation().getRevisionHash());
			assertEquals("the variant's input does not move with its parent's description", variantHashAfter,
				editedA.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertEquals("the variant's render still rides", oidOf(varRender), editedA.getLocation().getRenderArtifactId());
			assertNoLocationDegradation(editedA);
			assertEquals(PbRenderPlanUtil.joinSentences(List.of(newDesc, "night", SCENE_SETTING)), editedA.getLocation().getDescription());
			assertNotEquals(afterA.contentHash(), editedA.contentHash());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * The pure composer: location description first, then time of day, weather, season, viewpoint, extra in that
	 * fixed order, each trimmed and only when {@code NarrativeUtil.isMeaningful} (so the literal "null" an LLM
	 * emits and blank strings are dropped); empty when nothing is meaningful; a description that already ends in
	 * a stop is not given a second one. Then the integration half: a location created with a null description
	 * composes to the scene's own text alone and still hashes and degrades by name.
	 */
	@Test
	public void case11LocationSubjectTextOrderAndNullHandling() throws Exception {
		BaseRecord loc = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
		loc.set(FieldNames.FIELD_DESCRIPTION, "  " + LOC_DESCRIPTION + "  ");
		BaseRecord var = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_VARIANT);
		var.set(OlioFieldNames.FIELD_PB_TIME_OF_DAY, "dusk");
		var.set(OlioFieldNames.FIELD_PB_WEATHER, " rain ");
		var.set(OlioFieldNames.FIELD_PB_SEASON, "null");
		var.set(OlioFieldNames.FIELD_PB_VIEWPOINT, " ");
		var.set(OlioFieldNames.FIELD_PB_EXTRA, "from the quay");
		assertEquals("fixed order, trimmed, \"null\" and blank dropped",
			LOC_DESCRIPTION + ". dusk. rain. from the quay", PbRenderPlanUtil.locationSubjectText(loc, var));
		assertEquals("no variant: the description alone", LOC_DESCRIPTION, PbRenderPlanUtil.locationSubjectText(loc, null));

		BaseRecord bare = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
		assertNull("precondition: a fresh record has no description", bare.get(FieldNames.FIELD_DESCRIPTION));
		assertEquals("qualifiers alone when the location has no description", "dusk. rain. from the quay", PbRenderPlanUtil.locationSubjectText(bare, var));
		assertEquals("nothing meaningful anywhere is the empty string", "", PbRenderPlanUtil.locationSubjectText(bare, RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_VARIANT)));
		assertEquals("", PbRenderPlanUtil.locationSubjectText(bare, null));

		BaseRecord stopped = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
		stopped.set(FieldNames.FIELD_DESCRIPTION, "A tavern by the quay.");
		assertEquals("a trailing stop is kept as the join, not doubled", "A tavern by the quay. dusk. rain. from the quay", PbRenderPlanUtil.locationSubjectText(stopped, var));
		BaseRecord clause = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
		clause.set(FieldNames.FIELD_DESCRIPTION, "A tavern by the quay,");
		assertEquals("a trailing clause separator is kept as the join", "A tavern by the quay, dusk. rain. from the quay", PbRenderPlanUtil.locationSubjectText(clause, var));

		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "k");
		try {
			String name = "The Nameless Yard " + shortId();
			BaseRecord noDesc = PbLocationUtil.getCreateLocation(u, bk, name, null, PbLocationKindEnumType.INTERIOR, List.of("the yard"));
			assertNotNull(noDesc);
			assertNull("precondition: created without a description", mustReadLocation(u, oidOf(noDesc)).get(FieldNames.FIELD_DESCRIPTION));
			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, noDesc);

			RenderPlan p = planOf(u, bk, readScene(u, oidOf(scene)));
			RenderPlan.Location pl = p.getLocation();
			assertEquals(oidOf(noDesc), pl.getLocationId());
			assertEquals("no subject text, so the scene's own text alone - no leading separator", SCENE_SETTING, pl.getDescription());
			assertNotNull("a described-less location still hashes (name, kind, aliases are watched)", pl.getRevisionHash());
			assertEquals(pl.getRevisionHash(), p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			theOneLocationDegradation(p, noRenderForLocation(name, false));
			assertEquals("prompt shown = prompt sent", p.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(),
				planOf(u, bk, readScene(u, oidOf(scene))).getCompiled().get(RenderPlan.STAGE_BASE).getPrompt());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/**
	 * A location of the owner's OTHER book - readable, so this is a refusal and not a read failure - persisted on
	 * the scene (the generic model route gates a populated FK on READ only and never world-checks it; the facade's
	 * pointing routes do, through {@code requireLocationOfBook}). The plan treats it as unreadable: pointer kept,
	 * no hash, no subject text, no render (the other world's approved landscape never rides), one
	 * {@code LOCATION_TEXT_ONLY} naming the location and the reason; a variant of the refused location falls with
	 * it, still as one degradation. Control: the same location composes and carries its render in its own book.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void case12LocationOfAnotherWorldIsRefusedAsUnreadable() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "l");
		BaseRecord bk2 = newBook(u, "l2");
		try {
			long worldId = fkId(readBook(u, bk), OlioFieldNames.FIELD_PB_WORLD);
			long world2Id = fkId(readBook(u, bk2), OlioFieldNames.FIELD_PB_WORLD);
			assertTrue("both books project a world", worldId > 0 && world2Id > 0);
			assertNotEquals("precondition: two standalone books of one owner live in different worlds", worldId, world2Id);

			String loc2Name = "The Other Book's Tankard " + shortId();
			BaseRecord loc2 = newLocation(u, bk2, loc2Name, LOC_DESCRIPTION);
			String loc2Oid = oidOf(loc2);
			BaseRecord loc2Render = approveLandscape(u, bk2, loc2Oid, Color.ORANGE);
			assertNotNull("precondition: the owner CAN read the other book's location", PbLocationUtil.readLocation(u, loc2Oid, orgOf(u)));
			assertFalse("precondition: it is not in this book's world", PbLocationUtil.sameWorld(readBook(u, bk), loc2));
			assertTrue(PbLocationUtil.sameWorld(readBook(u, bk2), loc2));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, loc2);
			scene = readScene(u, oidOf(scene));
			assertEquals("the record layer persisted the cross-world pointer", loc2Oid, oidOf(scene.get(FieldNames.FIELD_LOCATION)));

			String refused = "Location " + loc2Name + " is not in this book's world; setting described in text";
			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location pl = p.getLocation();
			assertEquals("the identity is still the pointer the scene carries", loc2Oid, pl.getLocationId());
			assertNull(pl.getVariantId());
			assertNull("refused is treated as unreadable: no hash", pl.getRevisionHash());
			assertNull("the other world's render (" + oidOf(loc2Render) + ") never rides", pl.getRenderArtifactId());
			assertEquals("no subject text from a refused record", SCENE_SETTING, pl.getDescription());
			assertTrue("inputs carries the location key...", p.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			assertNull("...with a null hash", p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertFalse(p.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			theOneLocationDegradation(p, refused);
			String json = p.toJson();
			assertFalse("a null-valued input is absent on the wire: " + json, json.contains("\"inputs\""));
			assertTrue(json, json.contains("\"location\":{\"locationId\":\"" + loc2Oid + "\",\"description\":\"" + SCENE_SETTING + "\"}"));

			/// The facade is the util: same refusal, same bytes.
			Map<String, Object> out = PbServiceFacade.planScene(u, oidOf(bk), oidOf(scene), null, "SWARM", DEAD_SERVER);
			assertEquals(p.contentHash(), out.get("contentHash"));
			Map<String, Object> fpl = (Map<String, Object>) out.get("location");
			assertEquals(loc2Oid, fpl.get("locationId"));
			assertNull(fpl.get("revisionHash"));
			assertNull(fpl.get("renderArtifactId"));

			/// A variant of the refused location falls with it: pointer kept, null input, one degradation naming the location.
			String var2Oid = newVariant(u, bk2, loc2Oid, "night", null);
			BaseRecord scene2 = addScene(u, bk, 1, "Scene 1", SCENE_SETTING);
			pointSceneAt(u, scene2, loc2);
			pointSceneAtVariant(u, scene2, mustReadVariant(u, var2Oid));
			RenderPlan p2 = planOf(u, bk, readScene(u, oidOf(scene2)));
			assertEquals(loc2Oid, p2.getLocation().getLocationId());
			assertEquals("the variant pointer is still carried", var2Oid, p2.getLocation().getVariantId());
			assertNull(p2.getLocation().getRevisionHash());
			assertNull(p2.getLocation().getRenderArtifactId());
			assertEquals(SCENE_SETTING, p2.getLocation().getDescription());
			assertTrue(p2.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull(p2.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull(p2.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			theOneLocationDegradation(p2, refused);
			assertFalse(p2.toJson().contains("\"inputs\""));

			/// Control: in its own book the same location composes and carries its render.
			BaseRecord home = addScene(u, bk2, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, home, loc2);
			RenderPlan ph = planOf(u, bk2, readScene(u, oidOf(home)));
			assertEquals(oidOf(loc2Render), ph.getLocation().getRenderArtifactId());
			assertEquals(PbWatchedFields.refHashOf(mustReadLocation(u, loc2Oid)), ph.getLocation().getRevisionHash());
			assertNoLocationDegradation(ph);
			assertEquals(PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, SCENE_SETTING)), ph.getLocation().getDescription());
		}
		finally {
			deleteBook(u, bk);
			deleteBook(u, bk2);
		}
	}

	/**
	 * A variant whose parent is not the scene's location (both readable, both in this book's world, both with
	 * approved renders - the record layer never cross-checks the two FKs): the variant is refused, the location
	 * is kept (hash, {@code inputs.location}, description), {@code inputs.locationVariant} is present and null,
	 * neither render rides (D3: the location's is not a substitute for the named variant's, and the named
	 * variant is not of this location), and the one degradation names both.
	 */
	@Test
	public void case13VariantOfAnotherLocationIsRefusedKeepingTheLocation() throws Exception {
		BaseRecord u = owner();
		BaseRecord bk = newBook(u, "m");
		try {
			String locAName = "The Gilded Tankard " + shortId();
			BaseRecord locA = newLocation(u, bk, locAName, LOC_DESCRIPTION);
			String locAOid = oidOf(locA);
			BaseRecord locARender = approveLandscape(u, bk, locAOid, Color.ORANGE);
			BaseRecord locB = newLocation(u, bk, "The Unpainted Chapel " + shortId(), "A whitewashed chapel on a headland, one bell, gulls on the roof");
			String[] vBName = new String[1];
			String vBOid = newVariant(u, bk, oidOf(locB), "night", vBName);
			BaseRecord vBRender = approveVariantLandscape(u, bk, vBOid, Color.MAGENTA);
			assertEquals("precondition: the variant belongs to B", oidOf(locB), oidOf(mustReadVariant(u, vBOid).get(FieldNames.FIELD_LOCATION)));

			BaseRecord scene = addScene(u, bk, 0, "Scene 0", SCENE_SETTING);
			pointSceneAt(u, scene, locA);
			pointSceneAtVariant(u, scene, mustReadVariant(u, vBOid));
			scene = readScene(u, oidOf(scene));
			assertEquals(locAOid, oidOf(scene.get(FieldNames.FIELD_LOCATION)));
			assertEquals("the record layer persisted the mismatched pair", vBOid, oidOf(scene.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT)));

			String locAHash = PbWatchedFields.refHashOf(mustReadLocation(u, locAOid));
			RenderPlan p = planOf(u, bk, scene);
			RenderPlan.Location pl = p.getLocation();
			assertEquals(locAOid, pl.getLocationId());
			assertEquals("the variant pointer is still carried", vBOid, pl.getVariantId());
			assertEquals("the location is kept: its hash", locAHash, pl.getRevisionHash());
			assertEquals(locAHash, p.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertTrue("inputs carries the variant key...", p.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull("...with a null hash", p.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull("neither A's render (" + oidOf(locARender) + ") nor B-night's (" + oidOf(vBRender) + ") rides", pl.getRenderArtifactId());
			assertEquals("A's description composes; nothing from the refused variant",
				PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, SCENE_SETTING)), pl.getDescription());
			assertFalse(pl.getDescription(), pl.getDescription().contains("night"));
			theOneLocationDegradation(p, "Variant " + vBName[0] + " is not a variant of location " + locAName + "; setting described in text");

			String json = p.toJson();
			assertTrue("only the location's hash is on the wire: " + json, json.contains("\"inputs\":{\"" + PbPipelineUtil.ROLE_LOCATION + "\":\"" + locAHash + "\"}"));
			assertFalse(json, json.contains("renderArtifactId"));

			Map<String, Object> out = PbServiceFacade.planScene(u, oidOf(bk), oidOf(scene), null, "SWARM", DEAD_SERVER);
			assertEquals("the facade is the util", p.contentHash(), out.get("contentHash"));
		}
		finally {
			deleteBook(u, bk);
		}
	}
}
