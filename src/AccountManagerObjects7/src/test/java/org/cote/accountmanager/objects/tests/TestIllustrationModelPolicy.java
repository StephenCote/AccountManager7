package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.illustration.CheckpointNames;
import org.cote.accountmanager.olio.illustration.DeploymentModelDefaults;
import org.cote.accountmanager.olio.illustration.IllustrationModelPolicy;
import org.cote.accountmanager.olio.illustration.ModelInventory;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbConfigUtil;
import org.cote.accountmanager.olio.picturebook.PbModelPolicy;
import org.cote.accountmanager.olio.picturebook.PbStyleResolver;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 2, P2-3 (⚠ §6.C 156 e/f, 157 b/f/u): the one model policy ({@link IllustrationModelPolicy}
 * through {@link PbModelPolicy}) driven with each image-producing caller's EXACT inputs, against the
 * live Swarm inventory ({@code test.swarm.server}) and the live {@code am7db} as {@code testUser1}.
 * <p>
 * Per caller row of 156 e: OlioService reimage (PORTRAIT / LOCATION), ChatService (LOCATION /
 * SCENE_DIRECT with references), PB1 (LOCATION → PORTRAIT → SCENE_DIRECT in sequence), and the V3
 * executor (landscape and ChapBook page → LOCATION, composite → SCENE_DIRECT or LOCATION by mode,
 * portrait → PORTRAIT). Each asserts {@code Resolution.getStep()}, the copy's {@code model} /
 * {@code flux2Model}, and that the SOURCE {@code olio.sd.config} row re-read with {@code setCache(false)}
 * is unchanged — nothing persists. No live render anywhere in this class.
 * <p>
 * Gating (⚠ MY JUDGMENT, stated in the hand-back): the live rows call {@link #requireLive()} per case
 * (the {@code SdTestGate.requireSwarmReachable} skip, "This is NOT a pass") rather than in
 * {@code @Before}, because the four {@code offline*} cases resolve against the loopback / in-memory
 * records by design and need no Swarm; with the node unreachable they still exercise the write and
 * non-persistence rules. {@code SwarmModelInventory.invalidateAll()} IS in {@code @Before} as
 * {@code TestModelResolverLive} does.
 * <p>
 * Nothing names a checkpoint it assumes installed (§36.5): the SDXL gate comes from
 * {@code test.swarm.model} (written back by {@code SdTestGate.resolveInstalledCheckpoints} in
 * {@code BaseTest.setup()}, which also pins {@code SDUtil.setDefaultModel} to it so the deployment
 * default IS the gate); a second checkpoint, when the node lists one, is taken from the inventory.
 */
public class TestIllustrationModelPolicy extends BaseTest {

	private static final String ORG = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SCRATCH_PATH = "~/P23ModelPolicy";
	/// A closed port on the loopback: the inventory read fails fast with a connection refusal.
	private static final String UNREACHABLE_SERVER = "http://127.0.0.1:1";

	private String server;

	@Before
	public void policySetup() {
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
	}

	// ─────────────────────────────── gates & fixtures ───────────────────────────────

	private void requireLive() {
		SdTestGate.requireSwarmReachable(server);
	}

	private String gateModel() {
		String model = testProperties.getProperty(SdTestGate.PROP_SWARM_MODEL);
		Assume.assumeTrue("SKIPPED: no SDXL-family checkpoint resolved for " + server + ". This is NOT a pass.",
			model != null && !model.isBlank());
		return model;
	}

	private String flux2Model() {
		String model = testProperties.getProperty("test.swarm.flux2Model");
		Assume.assumeTrue("SKIPPED: test.swarm.flux2Model is not set. This is NOT a pass.", model != null && !model.isBlank());
		return model;
	}

	private ModelInventory liveInventory() {
		ModelInventory inv = SwarmModelInventory.forServer(SDAPIEnumType.SWARM, server);
		assertNull("The live inventory read must succeed against " + server + ": " + inv.error(), inv.error());
		assertFalse("The live node must list at least one checkpoint", inv.installedModels().isEmpty());
		return inv;
	}

	private String listed(ModelInventory inv, String want) {
		String l = CheckpointNames.matchInstalled(inv.installedModels(), want);
		assertNotNull("'" + want + "' must be in the live list " + inv.installedModels(), l);
		return l;
	}

	/// A second installed checkpoint, different from the listed gate name, usable for PORTRAIT/LOCATION; null if none.
	private String secondInstalled(ModelInventory inv, String excludeListed) {
		for(String n : inv.installedModels()) {
			if(n == null || n.equals(excludeListed)) {
				continue;
			}
			RendererCapabilities rc = RendererCapabilities.forModel(n);
			if(rc != null && rc.isEnabled() && ModelResolver.supportsTask(rc, PbModelTaskEnumType.PORTRAIT, false) == ModelResolver.Reason.OK) {
				return n;
			}
		}
		return null;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return (long) u.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private static String oid(BaseRecord r) {
		return r.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private BaseRecord createSdConfig(BaseRecord u, String name, String model, String flux2) {
		BaseRecord cfg = null;
		try {
			cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, cfg, name, SCRATCH_PATH, orgOf(u));
			cfg.set(FieldNames.FIELD_NAME, name);
			cfg.set("style", "art");
			if(model != null) {
				cfg.set(OlioFieldNames.FIELD_SD_MODEL, model);
			}
			if(flux2 != null) {
				cfg.set(OlioFieldNames.FIELD_SD_FLUX2_MODEL, flux2);
			}
		}
		catch(Exception e) {
			fail("Failed to assemble olio.sd.config: " + e.getMessage());
		}
		BaseRecord created = ioContext.getAccessPoint().create(u, cfg);
		assertNotNull("Failed to persist olio.sd.config " + name, created);
		return created;
	}

	/// A library-scope style in the test user's own scratch group, created through AccessPoint as that user.
	private BaseRecord createStyle(BaseRecord u, String name, String taskModels, BaseRecord sdConfig) {
		BaseRecord style = null;
		try {
			style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, style, name, SCRATCH_PATH, orgOf(u));
			style.set(FieldNames.FIELD_NAME, name);
			style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.LIBRARY.toString());
			if(taskModels != null) {
				style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, taskModels);
			}
			if(sdConfig != null) {
				style.set(OlioFieldNames.FIELD_PB_SD_CONFIG, sdConfig);
			}
		}
		catch(Exception e) {
			fail("Failed to assemble olio.pb.style '" + name + "': " + e.getMessage());
		}
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("AccessPoint.create must accept olio.pb.style '" + name + "'", created);
		assertNotNull("create returns identity", created.get(FieldNames.FIELD_OBJECT_ID));
		return created;
	}

	private static String taskModelsJson(PbModelTaskEnumType task, String model) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(ModelTaskKeys.key(task), model);
		return JSONUtil.exportObject(m);
	}

	private PbStyleResolver.EffectiveStyle chainOf(BaseRecord u, BaseRecord style) {
		PbStyleResolver.EffectiveStyle es = PbStyleResolver.resolveFromStyle(u, style);
		assertNotNull(es);
		assertFalse("the scratch style must be in the chain: " + es, es.isEmpty());
		assertTrue("the scratch style's chain must resolve complete: " + es, es.isComplete());
		return es;
	}

	private void cleanup(BaseRecord owner, BaseRecord... records) {
		for(BaseRecord r : records) {
			if(r == null) {
				continue;
			}
			try {
				if(!ioContext.getAccessPoint().delete(owner, r)) {
					logger.warn("Cleanup: could not delete " + r.getSchema() + " " + r.get(FieldNames.FIELD_OBJECT_ID));
				}
			}
			catch(Exception e) {
				logger.warn("Cleanup: " + e.getMessage());
			}
		}
	}

	/// The source row, re-read through AccessPoint as the owner with the cache off (the PbStyleUtil.readSdConfig shape).
	private BaseRecord readSdConfigUncached(BaseRecord u, String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.planMost(false);
		q.setCache(false);
		BaseRecord cfg = ioContext.getAccessPoint().find(u, q);
		assertNotNull("olio.sd.config " + objectId + " must be readable by its owner", cfg);
		return cfg;
	}

	private void assertSourceUnchanged(BaseRecord u, String objectId, String expectModel, String expectFlux2) {
		BaseRecord again = readSdConfigUncached(u, objectId);
		assertEquals("nothing persists: source olio.sd.config.model", expectModel, again.get(OlioFieldNames.FIELD_SD_MODEL));
		assertEquals("nothing persists: source olio.sd.config.flux2Model", expectFlux2, again.get(OlioFieldNames.FIELD_SD_FLUX2_MODEL));
	}

	/// A client body the way it arrives at OlioService.reimage / ChatService.generateScene: filtered module, only the keys named.
	private static BaseRecord sparseBody(String json) {
		BaseRecord rec = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertNotNull("client body must deserialize: " + json, rec);
		assertEquals(OlioModelNames.MODEL_SD_CONFIG, rec.getSchema());
		return rec;
	}

	/// An in-memory olio.pb.node carrying (or not) a sparse configOverride, as the executor reads it.
	private static BaseRecord inMemoryNode(String overrideJson) throws Exception {
		BaseRecord node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
		if(overrideJson != null) {
			node.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, overrideJson);
		}
		return node;
	}

	private static String model(BaseRecord cfg) {
		return (cfg != null && cfg.hasField(OlioFieldNames.FIELD_SD_MODEL) ? cfg.get(OlioFieldNames.FIELD_SD_MODEL) : null);
	}

	private static String flux2(BaseRecord cfg) {
		return (cfg != null && cfg.hasField(OlioFieldNames.FIELD_SD_FLUX2_MODEL) ? cfg.get(OlioFieldNames.FIELD_SD_FLUX2_MODEL) : null);
	}

	private static List<String> fieldNames(BaseRecord r) {
		List<String> out = new ArrayList<>();
		for(FieldType f : r.getFields()) {
			out.add(f.getName());
		}
		return out;
	}

	// ─────────────────────────────── offline: no Swarm needed ───────────────────────────────

	/**
	 * 156 f: an unreadable inventory writes the DEPLOYMENT default into the copy's field for the task and
	 * reports INVENTORY_UNAVAILABLE (step null, no candidates) — and the persisted SOURCE row the copy
	 * was read from is untouched. The override is the source's own (uninstalled) model, exactly what
	 * OlioService captures with {@code currentModel(imp, PORTRAIT)}.
	 */
	@Test
	public void offline01_unresolvedWritesDeploymentDefaultAndDoesNotPersist() throws Exception {
		BaseRecord u = user();
		String tag = shortId();
		String srcModel = "p23-src-not-installed-" + tag;
		String srcFlux2 = "p23-src-flux2-" + tag;
		BaseRecord src = createSdConfig(u, "p23-src-" + tag, srcModel, srcFlux2);
		try {
			BaseRecord imp = readSdConfigUncached(u, oid(src));
			assertEquals(srcModel, model(imp));
			assertEquals(srcFlux2, flux2(imp));
			String override = IllustrationModelPolicy.currentModel(imp, PbModelTaskEnumType.PORTRAIT);
			assertEquals("the override is the client's explicit value (157 b/f)", srcModel, override);
			DeploymentModelDefaults.Defaults dep = DeploymentModelDefaults.current();
			assertNotNull("a deployment default model must exist", dep.getModel());
			assertNotNull("a deployment default flux2 model must exist", dep.getFlux2Model());
			assertNotEquals("precondition: the source model differs from the deployment default", dep.getModel(), srcModel);
			logger.info("MEASURED: deployment defaults model=" + dep.getModel() + " flux2Model=" + dep.getFlux2Model());

			ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, imp, override, SDAPIEnumType.SWARM, UNREACHABLE_SERVER, false);
			assertNotNull(res);
			assertFalse("an unreadable inventory cannot resolve: " + res, res.isResolved());
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res.getReason());
			assertNull("no step when the inventory is unavailable", res.getStep());
			assertNull(res.getModel());
			assertNotNull("the inventory error is reported on the Resolution", res.getInventoryError());
			assertTrue("no candidates were evaluated", res.getCandidates().isEmpty());
			assertEquals(PbModelTaskEnumType.PORTRAIT, res.getTask());
			assertEquals("the copy's model is now the deployment default", dep.getModel(), model(imp));
			assertEquals("PORTRAIT does not touch flux2Model", srcFlux2, flux2(imp));

			/// SCENE_DIRECT lands in flux2Model, not model.
			String f2Override = IllustrationModelPolicy.currentModel(imp, PbModelTaskEnumType.SCENE_DIRECT);
			assertEquals(srcFlux2, f2Override);
			ModelResolver.Resolution res2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.SCENE_DIRECT, imp, f2Override, SDAPIEnumType.SWARM, UNREACHABLE_SERVER, true);
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res2.getReason());
			assertEquals("the copy's flux2Model is now the deployment flux2 default", dep.getFlux2Model(), flux2(imp));
			assertEquals("SCENE_DIRECT does not touch model", dep.getModel(), model(imp));

			/// The transport overloads: a blank / unknown apiType is INVENTORY_UNAVAILABLE too, never a throw.
			BaseRecord copy2 = readSdConfigUncached(u, oid(src));
			ModelResolver.Resolution res3 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, copy2, srcModel, "", UNREACHABLE_SERVER, false);
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res3.getReason());
			assertEquals(dep.getModel(), model(copy2));
			ModelResolver.Resolution res4 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, readSdConfigUncached(u, oid(src)), srcModel, "NOPE", UNREACHABLE_SERVER, false);
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res4.getReason());

			assertSourceUnchanged(u, oid(src), srcModel, srcFlux2);
		}
		finally {
			cleanup(u, src);
		}
	}

	/// A null copy resolves (the caller still gets the Resolution) and writes nowhere.
	@Test
	public void offline02_nullCopyResolvesWithoutWriting() {
		ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, null, null, SDAPIEnumType.SWARM, UNREACHABLE_SERVER, false);
		assertNotNull("a null copy must still return the Resolution", res);
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res.getReason());
		assertEquals(PbModelTaskEnumType.PORTRAIT, res.getTask());

		Map<PbModelTaskEnumType, String> pins = new EnumMap<>(PbModelTaskEnumType.class);
		pins.put(PbModelTaskEnumType.PORTRAIT, "p23-pinned");
		ModelResolver.Resolution res2 = IllustrationModelPolicy.apply(PbModelTaskEnumType.PORTRAIT, null, "p23-override", pins, "p23-style", "p23-style-flux2",
			SDAPIEnumType.SWARM, UNREACHABLE_SERVER, false);
		assertNotNull(res2);
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res2.getReason());

		/// With a null style and a null tier the PictureBook adapter is the same call.
		ModelResolver.Resolution res3 = PbModelPolicy.applyForStyle(null, null, PbModelTaskEnumType.SCENE_DIRECT, null, null, SDAPIEnumType.SWARM, UNREACHABLE_SERVER, true);
		assertNotNull(res3);
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, res3.getTask());

		/// A null task is UNKNOWN, not a throw.
		ModelResolver.Resolution res4 = PbModelPolicy.applyWithoutBook(null, null, null, SDAPIEnumType.SWARM, UNREACHABLE_SERVER, false);
		assertNotNull(res4);
		assertEquals(PbModelTaskEnumType.UNKNOWN, res4.getTask());

		/// Live when the node answers: a null copy with a resolvable request still resolves.
		if(SdTestGate.isReachable(server)) {
			ModelResolver.Resolution live = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, null, null, SDAPIEnumType.SWARM, server, false);
			assertNotNull(live);
			logger.info("MEASURED: null-copy live resolution = " + live);
		}
		else {
			logger.info("MEASURED: " + server + " unreachable; the null-copy live branch was not exercised");
		}
	}

	/**
	 * 157 u: a record whose schema declares no {@code model} / {@code flux2Model} field is left alone — no
	 * exception, nothing written, the Resolution still returned. {@code auth.group} stands in for the
	 * brief's {@code ModelNames.MODEL_MODEL}: "model" is the base field-type name and has no model JSON,
	 * so {@code RecordFactory.newInstance("model")} throws ModelNotFoundException before the policy is
	 * reached (stated in the hand-back).
	 */
	@Test
	public void offline03_copyWhoseSchemaLacksTheFieldIsLeftAlone() throws Exception {
		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_GROUP);
		assertNotNull(ms);
		assertNull("precondition: auth.group declares no 'model' field", ms.getFieldSchema(IllustrationModelPolicy.FIELD_MODEL));
		assertNull("precondition: auth.group declares no 'flux2Model' field", ms.getFieldSchema(IllustrationModelPolicy.FIELD_FLUX2_MODEL));
		BaseRecord grp = RecordFactory.newInstance(ModelNames.MODEL_GROUP);
		assertFalse(grp.hasField(IllustrationModelPolicy.FIELD_MODEL));
		List<String> before = fieldNames(grp);

		ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, grp, "p23-override", SDAPIEnumType.SWARM, UNREACHABLE_SERVER, false);
		assertNotNull("the Resolution is still returned", res);
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res.getReason());
		assertFalse("no 'model' field was materialised on auth.group", grp.hasField(IllustrationModelPolicy.FIELD_MODEL));

		ModelResolver.Resolution res2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.SCENE_DIRECT, grp, null, SDAPIEnumType.SWARM, UNREACHABLE_SERVER, true);
		assertNotNull(res2);
		assertFalse("no 'flux2Model' field was materialised on auth.group", grp.hasField(IllustrationModelPolicy.FIELD_FLUX2_MODEL));
		assertEquals("the record's field set is exactly what it was", before, fieldNames(grp));

		if(SdTestGate.isReachable(server)) {
			ModelResolver.Resolution live = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, grp, null, SDAPIEnumType.SWARM, server, false);
			assertNotNull(live);
			assertFalse("a RESOLVED pick is not applied to a schema without the field either", grp.hasField(IllustrationModelPolicy.FIELD_MODEL));
			assertEquals(before, fieldNames(grp));
			logger.info("MEASURED: live resolution against auth.group = " + live + "; field set unchanged");
		}
		else {
			logger.info("MEASURED: " + server + " unreachable; the resolved-pick branch was exercised with the deployment default only");
		}
	}

	/**
	 * 157 u: a client body that names no {@code model} key carries NO model field after deserialization
	 * (presence is not the test — the schema declaring it is), and the pick is materialised on it.
	 */
	@Test
	public void offline04_sparseCopyWithoutModelFieldReceivesPick() {
		BaseRecord body = sparseBody("{\"schema\":\"olio.sd.config\",\"steps\":20}");
		assertFalse("a client body without a model key carries no model field", body.hasField(IllustrationModelPolicy.FIELD_MODEL));
		assertFalse(body.hasField(IllustrationModelPolicy.FIELD_FLUX2_MODEL));
		assertNull("currentModel on a sparse body is null, not a throw", IllustrationModelPolicy.currentModel(body, PbModelTaskEnumType.PORTRAIT));
		assertNull(IllustrationModelPolicy.currentModel(body, PbModelTaskEnumType.SCENE_DIRECT));
		int steps = body.get("steps");
		assertEquals(20, steps);

		boolean live = SdTestGate.isReachable(server);
		String srv = (live ? server : UNREACHABLE_SERVER);
		logger.info("MEASURED: sparse-copy case resolving against " + (live ? "the live node " + server : "the loopback (" + server + " unreachable)"));
		DeploymentModelDefaults.Defaults dep = DeploymentModelDefaults.current();

		ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, body, null, SDAPIEnumType.SWARM, srv, false);
		assertNotNull(res);
		String expect = (res.isResolved() ? res.getModel() : dep.getModel());
		assertNotNull(expect);
		assertTrue("the pick must be materialised on the sparse copy", body.hasField(IllustrationModelPolicy.FIELD_MODEL));
		assertEquals(expect, model(body));
		if(res.isResolved()) {
			assertEquals("no override, no style: the deployment default is the pick", ModelResolver.Step.DEPLOYMENT_DEFAULT, res.getStep());
		}
		else {
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, res.getReason());
		}
		assertFalse("PORTRAIT does not materialise flux2Model", body.hasField(IllustrationModelPolicy.FIELD_FLUX2_MODEL));
		int stepsAfter = body.get("steps");
		assertEquals("the rest of the body is untouched", 20, stepsAfter);

		ModelResolver.Resolution res2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.SCENE_DIRECT, body, null, SDAPIEnumType.SWARM, srv, true);
		String expect2 = (res2.isResolved() ? res2.getModel() : dep.getFlux2Model());
		assertTrue("SCENE_DIRECT materialises flux2Model on the sparse copy", body.hasField(IllustrationModelPolicy.FIELD_FLUX2_MODEL));
		assertEquals(expect2, flux2(body));
		assertEquals("SCENE_DIRECT leaves model as PORTRAIT wrote it", expect, model(body));
	}

	// ─────────────────────────────── live: one case per caller row (156 e) ───────────────────────────────

	/**
	 * OlioService.reimageWithConfig, PORTRAIT ({@code OlioService.java:194,272,309,568}): the client body's
	 * own {@code model} captured once as {@code currentModel(imp, PORTRAIT)} is the override.
	 */
	@Test
	public void live01_olioServiceReimagePortrait() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		BaseRecord u = user();
		String tag = shortId();

		/// (a) the body names the gate checkpoint: OVERRIDE, written back in its LISTED form.
		BaseRecord imp = sparseBody("{\"schema\":\"olio.sd.config\",\"model\":\"" + gate + "\",\"steps\":20}");
		String clientModel = IllustrationModelPolicy.currentModel(imp, PbModelTaskEnumType.PORTRAIT);
		assertEquals(gate, clientModel);
		ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, imp, clientModel, SDAPIEnumType.SWARM, server, false);
		assertTrue(res.toString(), res.isResolved());
		assertEquals(ModelResolver.Step.OVERRIDE, res.getStep());
		assertEquals(ModelResolver.Reason.OK, res.getReason());
		assertFalse(res.isDegraded());
		assertEquals(listedGate, res.getModel());
		assertEquals("the copy carries the listed pick", listedGate, model(imp));
		assertFalse("PORTRAIT does not materialise flux2Model", imp.hasField(IllustrationModelPolicy.FIELD_FLUX2_MODEL));

		/// (b) a persisted source whose model is not installed: OVERRIDE rejected, DEPLOYMENT_DEFAULT (= gate) written; the row is untouched.
		String srcModel = "p23-src-not-installed-" + tag;
		BaseRecord src = createSdConfig(u, "p23-src-" + tag, srcModel, null);
		try {
			BaseRecord copy = readSdConfigUncached(u, oid(src));
			String override = IllustrationModelPolicy.currentModel(copy, PbModelTaskEnumType.PORTRAIT);
			assertEquals(srcModel, override);
			ModelResolver.Resolution r2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.PORTRAIT, copy, override, SDAPIEnumType.SWARM, server, false);
			assertTrue(r2.toString(), r2.isResolved());
			assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT, r2.getStep());
			assertTrue("a substitution is a degradation", r2.isDegraded());
			assertEquals(2, r2.getCandidates().size());
			assertEquals(ModelResolver.Reason.NOT_INSTALLED, r2.getCandidates().get(0).getReason());
			assertEquals(srcModel, r2.getCandidates().get(0).getRequested());
			assertEquals(listedGate, r2.getModel());
			assertEquals(listedGate, model(copy));
			String srcFlux2 = flux2(copy);
			assertSourceUnchanged(u, oid(src), srcModel, srcFlux2);
		}
		finally {
			cleanup(u, src);
		}
	}

	/**
	 * OlioService.reimageWithConfig, LOCATION ({@code OlioService.java:201,236,494,650}): the same captured
	 * {@code clientModel} drives LOCATION — same field, same override semantics.
	 */
	@Test
	public void live02_olioServiceReimageLocation() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord imp = sparseBody("{\"schema\":\"olio.sd.config\",\"model\":\"" + gate + "\"}");
		String clientModel = IllustrationModelPolicy.currentModel(imp, PbModelTaskEnumType.PORTRAIT);
		ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, imp, clientModel, SDAPIEnumType.SWARM, server, false);
		assertTrue(res.toString(), res.isResolved());
		assertEquals(PbModelTaskEnumType.LOCATION, res.getTask());
		assertEquals(ModelResolver.Step.OVERRIDE, res.getStep());
		assertEquals(listedGate, model(imp));

		/// A sparse body with no model at all: DEPLOYMENT_DEFAULT, field materialised.
		BaseRecord bare = sparseBody("{\"schema\":\"olio.sd.config\",\"steps\":20}");
		String none = IllustrationModelPolicy.currentModel(bare, PbModelTaskEnumType.PORTRAIT);
		assertNull(none);
		ModelResolver.Resolution r2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, bare, none, SDAPIEnumType.SWARM, server, false);
		assertTrue(r2.toString(), r2.isResolved());
		assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT, r2.getStep());
		assertFalse(r2.isDegraded());
		assertEquals(1, r2.getCandidates().size());
		assertTrue(bare.hasField(IllustrationModelPolicy.FIELD_MODEL));
		assertEquals(listedGate, model(bare));

		/// Persisted source, uninstalled model: the copy is rewritten, the row is not.
		String srcModel = "p23-src-not-installed-" + tag;
		BaseRecord src = createSdConfig(u, "p23-src-" + tag, srcModel, null);
		try {
			BaseRecord copy = readSdConfigUncached(u, oid(src));
			String srcFlux2 = flux2(copy);
			ModelResolver.Resolution r3 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, copy,
				IllustrationModelPolicy.currentModel(copy, PbModelTaskEnumType.PORTRAIT), SDAPIEnumType.SWARM, server, false);
			assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT, r3.getStep());
			assertEquals(listedGate, model(copy));
			assertSourceUnchanged(u, oid(src), srcModel, srcFlux2);
		}
		finally {
			cleanup(u, src);
		}
	}

	/**
	 * ChatService.generateScene, LOCATION ({@code ChatService.java:1437,1526,1574,1600}): the landscape pass
	 * and the classic scene pass use {@code clientModel = currentModel(sdConfig, LOCATION)} captured
	 * before any pass rewrote the config, so the second pass never mistakes the first pick for the request.
	 */
	@Test
	public void live03_chatServiceLandscapeAndClassicSceneLocation() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		BaseRecord u = user();
		String tag = shortId();

		/// The typical chat body names a mode and no checkpoint.
		BaseRecord sdConfig = sparseBody("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"classic\",\"steps\":20}");
		String clientModel = IllustrationModelPolicy.currentModel(sdConfig, PbModelTaskEnumType.LOCATION);
		String clientFlux2Model = IllustrationModelPolicy.currentModel(sdConfig, PbModelTaskEnumType.SCENE_DIRECT);
		assertNull(clientModel);
		assertNull(clientFlux2Model);
		/// landscape pass
		ModelResolver.Resolution r1 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, sdConfig, clientModel, SDAPIEnumType.SWARM, server, false);
		assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT, r1.getStep());
		assertEquals(listedGate, model(sdConfig));
		/// classic scene pass: the override is STILL the captured client value (null), not the landscape pick.
		ModelResolver.Resolution r2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, sdConfig, clientModel, SDAPIEnumType.SWARM, server, false);
		assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT, r2.getStep());
		assertEquals(1, r2.getCandidates().size());
		assertEquals(listedGate, model(sdConfig));
		assertFalse("LOCATION never materialises flux2Model", sdConfig.hasField(IllustrationModelPolicy.FIELD_FLUX2_MODEL));

		/// Had the override been read from the config AFTER the first pass (the bug the capture prevents),
		/// the second pass would be an OVERRIDE of the first pick — show the difference on a fresh copy.
		BaseRecord aliased = sparseBody("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"classic\"}");
		PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, aliased, null, SDAPIEnumType.SWARM, server, false);
		String lateRead = IllustrationModelPolicy.currentModel(aliased, PbModelTaskEnumType.LOCATION);
		assertEquals(listedGate, lateRead);
		ModelResolver.Resolution rAliased = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, aliased, lateRead, SDAPIEnumType.SWARM, server, false);
		assertEquals("a late read of the config is an OVERRIDE of the earlier pick - the captured value is what keeps it DEPLOYMENT_DEFAULT",
			ModelResolver.Step.OVERRIDE, rAliased.getStep());

		/// Persisted source with an explicit installed model: OVERRIDE, row untouched.
		BaseRecord src = createSdConfig(u, "p23-src-" + tag, gate, null);
		try {
			BaseRecord copy = readSdConfigUncached(u, oid(src));
			String srcFlux2 = flux2(copy);
			String ov = IllustrationModelPolicy.currentModel(copy, PbModelTaskEnumType.LOCATION);
			assertEquals(gate, ov);
			ModelResolver.Resolution r3 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.LOCATION, copy, ov, SDAPIEnumType.SWARM, server, false);
			assertEquals(ModelResolver.Step.OVERRIDE, r3.getStep());
			assertEquals(listedGate, model(copy));
			assertSourceUnchanged(u, oid(src), gate, srcFlux2);
		}
		finally {
			cleanup(u, src);
		}
	}

	/**
	 * ChatService.generateScene, SCENE_DIRECT with references ({@code ChatService.java:1438,1571}):
	 * {@code clientFlux2Model = currentModel(sdConfig, SCENE_DIRECT)} is the override, references
	 * requested, and the pick lands in {@code flux2Model}. The FLUX.2 name is whatever
	 * {@code test.swarm.flux2Model} says; the assertion follows the inventory's truth about it
	 * (installed → OVERRIDE; not installed → unresolved, deployment flux2 default written).
	 */
	@Test
	public void live04_chatServiceSceneDirectWithReferences() throws Exception {
		requireLive();
		String flux2Name = flux2Model();
		ModelInventory inv = liveInventory();
		String listedFlux2 = CheckpointNames.matchInstalled(inv.installedModels(), flux2Name);
		BaseRecord u = user();
		String tag = shortId();
		DeploymentModelDefaults.Defaults dep = DeploymentModelDefaults.current();

		BaseRecord sdConfig = sparseBody("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"flux2\",\"flux2Model\":\"" + flux2Name + "\"}");
		String clientModel = IllustrationModelPolicy.currentModel(sdConfig, PbModelTaskEnumType.LOCATION);
		String clientFlux2Model = IllustrationModelPolicy.currentModel(sdConfig, PbModelTaskEnumType.SCENE_DIRECT);
		assertNull("no model key in the body", clientModel);
		assertEquals(flux2Name, clientFlux2Model);
		assertEquals(SceneCompositeUtil.MODE_FLUX2, SceneCompositeUtil.resolveMode(sdConfig, false));

		ModelResolver.Resolution res = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.SCENE_DIRECT, sdConfig, clientFlux2Model, SDAPIEnumType.SWARM, server, true);
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, res.getTask());
		assertFalse("SCENE_DIRECT never materialises model", sdConfig.hasField(IllustrationModelPolicy.FIELD_MODEL));
		assertFalse(res.getCandidates().isEmpty());
		ModelResolver.Candidate first = res.getCandidates().get(0);
		assertEquals(ModelResolver.Step.OVERRIDE, first.getStep());
		assertEquals(flux2Name, first.getRequested());
		if(listedFlux2 != null) {
			logger.info("MEASURED: FLUX.2 checkpoint '" + flux2Name + "' is installed on " + server + " as '" + listedFlux2 + "'");
			assertTrue(res.toString(), res.isResolved());
			assertEquals(ModelResolver.Step.OVERRIDE, res.getStep());
			assertEquals(ModelResolver.Reason.OK, res.getReason());
			assertEquals(listedFlux2, res.getModel());
			assertEquals(RendererCapabilities.PATTERN_FLUX2, res.getFamily());
			assertTrue("references were requested, so the family must support them", res.getCapabilities().isSupportsReferences());
			assertEquals("the copy's flux2Model is the listed pick", listedFlux2, flux2(sdConfig));
		}
		else {
			logger.info("MEASURED: FLUX.2 checkpoint '" + flux2Name + "' is NOT installed on " + server + "; resolver must say NOT_INSTALLED and fall to the deployment flux2 default");
			assertEquals(ModelResolver.Reason.NOT_INSTALLED, first.getReason());
			assertFalse(res.toString(), res.isResolved());
			assertNull(res.getStep());
			assertEquals(ModelResolver.Reason.NO_CANDIDATE, res.getReason());
			assertEquals("unresolved: the deployment flux2 default is written", dep.getFlux2Model(), flux2(sdConfig));
		}

		/// Persisted source whose flux2Model is uninstalled: the copy is rewritten, the row is not.
		String srcFlux2 = "p23-src-flux2-" + tag;
		BaseRecord src = createSdConfig(u, "p23-src-" + tag, null, srcFlux2);
		try {
			BaseRecord copy = readSdConfigUncached(u, oid(src));
			String srcModel = model(copy);
			String ov = IllustrationModelPolicy.currentModel(copy, PbModelTaskEnumType.SCENE_DIRECT);
			assertEquals(srcFlux2, ov);
			ModelResolver.Resolution r2 = PbModelPolicy.applyWithoutBook(PbModelTaskEnumType.SCENE_DIRECT, copy, ov, SDAPIEnumType.SWARM, server, true);
			assertEquals(ModelResolver.Reason.NOT_INSTALLED, r2.getCandidates().get(0).getReason());
			assertNotEquals("the copy's flux2Model no longer carries the uninstalled name", srcFlux2, flux2(copy));
			assertEquals("model is untouched by SCENE_DIRECT", srcModel, model(copy));
			assertSourceUnchanged(u, oid(src), srcModel, srcFlux2);
		}
		finally {
			cleanup(u, src);
		}
	}

	/**
	 * PB1 ({@code PictureBookUtil.java:9815-9828, 10023-10044, 10287-10293}): one resolved chain, the style
	 * tier's config as the STYLE_DEFAULT source, three tasks in sequence against three copies —
	 * LOCATION into {@code common}, PORTRAIT into a fresh {@code portCfg}, SCENE_DIRECT into
	 * {@code flux2Cfg} (= {@code common} when no compositeSdConfig) with references. The override for each
	 * is the client's explicit value captured BEFORE the first write ({@code PictureBookUtil.explicitModel};
	 * null here for a sparse body, as it is for a client that named no checkpoint).
	 */
	@Test
	public void live05_pb1LocationThenPortraitThenSceneDirect() throws Exception {
		requireLive();
		String gate = gateModel();
		String flux2Name = flux2Model();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		String second = secondInstalled(inv, listedGate);
		String pin = (second != null ? second : listedGate);
		logger.info("MEASURED: PB1 row - gate='" + listedGate + "', portrait pin='" + pin + "'" + (second == null ? " (node lists no second usable checkpoint; the pin IS the gate)" : ""));
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord styleCfg = createSdConfig(u, "p23-pb1-cfg-" + tag, listedGate, flux2Name);
		BaseRecord style = createStyle(u, "p23-pb1-style-" + tag, taskModelsJson(PbModelTaskEnumType.PORTRAIT, pin), styleCfg);
		try {
			PbStyleResolver.EffectiveStyle pbStyle = chainOf(u, style);
			assertEquals("the chain's portrait pin", pin, pbStyle.taskModels().get(PbModelTaskEnumType.PORTRAIT));
			assertNotNull("the chain's sdConfig is the scratch style's", pbStyle.sdConfig());
			assertEquals(oid(styleCfg), oid(pbStyle.sdConfig()));

			/// PB1 :9592-9598 — the client's sparse body is `common`; fillStyleDefaults does not touch model.
			BaseRecord common = sparseBody("{\"schema\":\"olio.sd.config\",\"style\":\"art\",\"steps\":20}");
			SDUtil.fillStyleDefaults(common);
			assertFalse("precondition: the client named no checkpoint", common.hasField(IllustrationModelPolicy.FIELD_MODEL));
			/// :9815-9826 — overrides captured before any write; the style tier is the chain's config.
			String explicitLocation = IllustrationModelPolicy.currentModel(common, PbModelTaskEnumType.LOCATION);
			String explicitPortrait = IllustrationModelPolicy.currentModel(common, PbModelTaskEnumType.PORTRAIT);
			String explicitSceneDirect = IllustrationModelPolicy.currentModel(common, PbModelTaskEnumType.SCENE_DIRECT);
			assertNull(explicitLocation);
			assertNull(explicitPortrait);
			assertNull(explicitSceneDirect);
			BaseRecord pbStyleTier = (pbStyle.sdConfig() != null ? pbStyle.sdConfig() : common.copyRecord());
			assertEquals(listedGate, model(pbStyleTier));

			/// :9827 LOCATION → common
			ModelResolver.Resolution rLoc = PbModelPolicy.applyForStyle(pbStyle, pbStyleTier, PbModelTaskEnumType.LOCATION, common, explicitLocation, SDAPIEnumType.SWARM, server, false);
			assertTrue(rLoc.toString(), rLoc.isResolved());
			assertEquals("no LOCATION pin, no override: the style's config model", ModelResolver.Step.STYLE_DEFAULT, rLoc.getStep());
			assertEquals(listedGate, rLoc.getModel());
			assertEquals(listedGate, model(common));

			/// :10023-10044 PORTRAIT → a fresh bare portCfg (model materialised at the schema default)
			BaseRecord portCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			portCfg.set("steps", 20);
			ModelResolver.Resolution rPort = PbModelPolicy.applyForStyle(pbStyle, pbStyleTier, PbModelTaskEnumType.PORTRAIT, portCfg, explicitPortrait, SDAPIEnumType.SWARM, server, false);
			assertTrue(rPort.toString(), rPort.isResolved());
			assertEquals("the style's portrait pin wins for PORTRAIT", ModelResolver.Step.STYLE_TASK, rPort.getStep());
			assertEquals(pin, rPort.getModel());
			assertEquals(pin, model(portCfg));
			assertEquals("the LOCATION pick on common is not disturbed by the PORTRAIT pass", listedGate, model(common));

			/// :10287-10293 SCENE_DIRECT → flux2Cfg (= common), references requested
			BaseRecord flux2Cfg = common;
			ModelResolver.Resolution rScene = PbModelPolicy.applyForStyle(pbStyle, pbStyleTier, PbModelTaskEnumType.SCENE_DIRECT, flux2Cfg, explicitSceneDirect, SDAPIEnumType.SWARM, server, true);
			assertEquals(PbModelTaskEnumType.SCENE_DIRECT, rScene.getTask());
			String listedFlux2 = CheckpointNames.matchInstalled(inv.installedModels(), flux2Name);
			ModelResolver.Candidate c0 = rScene.getCandidates().get(0);
			assertEquals("no pin, no override: the style's flux2Model is the first candidate", ModelResolver.Step.STYLE_DEFAULT, c0.getStep());
			assertEquals(flux2Name, c0.getRequested());
			if(listedFlux2 != null) {
				assertEquals(ModelResolver.Step.STYLE_DEFAULT, rScene.getStep());
				assertEquals(listedFlux2, flux2(common));
			}
			else {
				logger.info("MEASURED: FLUX.2 '" + flux2Name + "' not installed on " + server + "; SCENE_DIRECT unresolved → deployment flux2 default");
				assertFalse(rScene.isResolved());
				assertEquals(DeploymentModelDefaults.current().getFlux2Model(), flux2(common));
			}
			assertEquals("SCENE_DIRECT leaves common.model as LOCATION wrote it", listedGate, model(common));

			/// Nothing persisted: the style's config row (the tier record) is unchanged on disk and in memory.
			assertEquals(listedGate, model(pbStyleTier));
			assertEquals(flux2Name, flux2(pbStyleTier));
			assertSourceUnchanged(u, oid(styleCfg), listedGate, flux2Name);
		}
		finally {
			cleanup(u, style, styleCfg);
		}
	}

	/**
	 * Executor landscape ({@code PbNodeExecutor.java:506}) and ChapBook page ({@code :612}): LOCATION on
	 * {@code resolveEffectiveConfig(book, node, false, null, style)} with
	 * {@code nodeOverride(node, LOCATION)} as the override — a node with no override, one naming an
	 * uninstalled checkpoint (the merged effective config already carries it; the policy rewrites it),
	 * and one naming an installed checkpoint.
	 */
	@Test
	public void live06_executorLandscapeAndPageLocation() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		String second = secondInstalled(inv, listedGate);
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord styleCfg = createSdConfig(u, "p23-exec-cfg-" + tag, listedGate, null);
		BaseRecord style = createStyle(u, "p23-exec-style-" + tag, null, styleCfg);
		try {
			PbStyleResolver.EffectiveStyle es = chainOf(u, style);
			assertEquals(oid(styleCfg), oid(es.sdConfig()));
			BaseRecord tier = PbConfigUtil.styleTierConfig(null, false, es);
			assertNotNull(tier);
			assertEquals(listedGate, model(tier));

			/// (a) no node override
			BaseRecord node = inMemoryNode(null);
			BaseRecord eff = PbConfigUtil.resolveEffectiveConfig(null, node, false, null, es);
			assertEquals("the merged effective config already carries the tier's model", listedGate, model(eff));
			String ov = PbModelPolicy.nodeOverride(node, PbModelTaskEnumType.LOCATION);
			assertNull("no configOverride → no override", ov);
			ModelResolver.Resolution r1 = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.LOCATION, eff, ov, SDAPIEnumType.SWARM, server, false);
			assertEquals(ModelResolver.Step.STYLE_DEFAULT, r1.getStep());
			assertEquals(1, r1.getCandidates().size());
			assertEquals(listedGate, model(eff));

			/// (b) node override naming an uninstalled checkpoint
			String bogus = "p23-node-not-installed-" + tag;
			BaseRecord node2 = inMemoryNode("{\"schema\":\"olio.sd.config\",\"model\":\"" + bogus + "\"}");
			BaseRecord eff2 = PbConfigUtil.resolveEffectiveConfig(null, node2, false, null, es);
			assertEquals("precondition: the merged config carries the node's uninstalled name", bogus, model(eff2));
			String ov2 = PbModelPolicy.nodeOverride(node2, PbModelTaskEnumType.LOCATION);
			assertEquals(bogus, ov2);
			ModelResolver.Resolution r2 = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.LOCATION, eff2, ov2, SDAPIEnumType.SWARM, server, false);
			assertTrue(r2.toString(), r2.isResolved());
			assertEquals("OVERRIDE rejected (NOT_INSTALLED) → STYLE_DEFAULT", ModelResolver.Step.STYLE_DEFAULT, r2.getStep());
			assertTrue(r2.isDegraded());
			assertEquals(ModelResolver.Reason.NOT_INSTALLED, r2.getCandidates().get(0).getReason());
			assertEquals("the policy REWROTE the merged config's uninstalled name", listedGate, model(eff2));

			/// (c) node override naming an installed checkpoint
			if(second != null) {
				BaseRecord node3 = inMemoryNode("{\"schema\":\"olio.sd.config\",\"model\":\"" + second + "\"}");
				BaseRecord eff3 = PbConfigUtil.resolveEffectiveConfig(null, node3, false, null, es);
				String ov3 = PbModelPolicy.nodeOverride(node3, PbModelTaskEnumType.LOCATION);
				assertEquals(second, ov3);
				ModelResolver.Resolution r3 = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.LOCATION, eff3, ov3, SDAPIEnumType.SWARM, server, false);
				assertEquals(ModelResolver.Step.OVERRIDE, r3.getStep());
				assertEquals(second, model(eff3));
			}
			else {
				logger.info("MEASURED: " + server + " lists no second usable checkpoint; the installed-override branch was not exercised");
			}

			/// The tier record was never written to; the row is unchanged.
			assertEquals(listedGate, model(tier));
			assertSourceUnchanged(u, oid(styleCfg), listedGate, flux2(tier));
		}
		finally {
			cleanup(u, style, styleCfg);
		}
	}

	/**
	 * Executor composite ({@code PbNodeExecutor.java:902-908}): the task follows the effective config's
	 * {@code compositeMode} — SCENE_DIRECT with references for flux2, LOCATION otherwise — on
	 * {@code resolveEffectiveConfig(book, node, true, null, style)}.
	 */
	@Test
	public void live07_executorCompositeSceneDirectOrLocation() throws Exception {
		requireLive();
		String gate = gateModel();
		String flux2Name = flux2Model();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		String listedFlux2 = CheckpointNames.matchInstalled(inv.installedModels(), flux2Name);
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord styleCfg = createSdConfig(u, "p23-comp-cfg-" + tag, listedGate, flux2Name);
		BaseRecord style = createStyle(u, "p23-comp-style-" + tag, null, styleCfg);
		try {
			PbStyleResolver.EffectiveStyle es = chainOf(u, style);
			BaseRecord tier = PbConfigUtil.styleTierConfig(null, true, es);
			assertEquals(oid(styleCfg), oid(tier));

			/// flux2 mode with references attached → SCENE_DIRECT, refs=true, pick into flux2Model
			BaseRecord nodeF = inMemoryNode("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"flux2\"}");
			BaseRecord effF = PbConfigUtil.resolveEffectiveConfig(null, nodeF, true, null, es);
			String mode = SceneCompositeUtil.resolveMode(effF, false);
			assertEquals(SceneCompositeUtil.MODE_FLUX2, mode);
			boolean flux2 = SceneCompositeUtil.MODE_FLUX2.equals(mode);
			boolean hasRefs = true;
			PbModelTaskEnumType task = (flux2 ? PbModelTaskEnumType.SCENE_DIRECT : PbModelTaskEnumType.LOCATION);
			String ov = PbModelPolicy.nodeOverride(nodeF, task);
			assertNull("the node override names no flux2Model", ov);
			String modelBefore = model(effF);
			ModelResolver.Resolution rF = PbModelPolicy.applyForStyle(es, tier, task, effF, ov, SDAPIEnumType.SWARM, server, flux2 && hasRefs);
			assertEquals(PbModelTaskEnumType.SCENE_DIRECT, rF.getTask());
			assertEquals(ModelResolver.Step.STYLE_DEFAULT, rF.getCandidates().get(0).getStep());
			assertEquals(flux2Name, rF.getCandidates().get(0).getRequested());
			if(listedFlux2 != null) {
				assertEquals(ModelResolver.Step.STYLE_DEFAULT, rF.getStep());
				assertEquals(listedFlux2, flux2(effF));
			}
			else {
				logger.info("MEASURED: FLUX.2 '" + flux2Name + "' not installed on " + server + "; composite SCENE_DIRECT unresolved → deployment flux2 default");
				assertFalse(rF.isResolved());
				assertEquals(DeploymentModelDefaults.current().getFlux2Model(), flux2(effF));
			}
			assertEquals("SCENE_DIRECT leaves model alone", modelBefore, model(effF));

			/// classic mode → LOCATION, refs=false, pick into model
			BaseRecord nodeC = inMemoryNode("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"classic\"}");
			BaseRecord effC = PbConfigUtil.resolveEffectiveConfig(null, nodeC, true, null, es);
			String modeC = SceneCompositeUtil.resolveMode(effC, false);
			assertEquals(SceneCompositeUtil.MODE_CLASSIC, modeC);
			boolean flux2C = SceneCompositeUtil.MODE_FLUX2.equals(modeC);
			PbModelTaskEnumType taskC = (flux2C ? PbModelTaskEnumType.SCENE_DIRECT : PbModelTaskEnumType.LOCATION);
			String flux2Before = flux2(effC);
			ModelResolver.Resolution rC = PbModelPolicy.applyForStyle(es, tier, taskC, effC, PbModelPolicy.nodeOverride(nodeC, taskC), SDAPIEnumType.SWARM, server, flux2C && hasRefs);
			assertEquals(PbModelTaskEnumType.LOCATION, rC.getTask());
			assertEquals(ModelResolver.Step.STYLE_DEFAULT, rC.getStep());
			assertEquals(listedGate, model(effC));
			assertEquals("LOCATION leaves flux2Model alone", flux2Before, flux2(effC));

			assertSourceUnchanged(u, oid(styleCfg), listedGate, flux2Name);
		}
		finally {
			cleanup(u, style, styleCfg);
		}
	}

	/**
	 * Executor portrait ({@code PbNodeExecutor.java:1015}): PORTRAIT on the merged effective config. The
	 * effective record already carries the tier's checkpoint; the override is the NODE's sparse value
	 * (null here), never the merged model — so the style's PORTRAIT pin beats the tier config (156 f).
	 */
	@Test
	public void live08_executorPortrait() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		String second = secondInstalled(inv, listedGate);
		String pin = (second != null ? second : listedGate);
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord styleCfg = createSdConfig(u, "p23-port-cfg-" + tag, listedGate, null);
		BaseRecord style = createStyle(u, "p23-port-style-" + tag, taskModelsJson(PbModelTaskEnumType.PORTRAIT, pin), styleCfg);
		try {
			PbStyleResolver.EffectiveStyle es = chainOf(u, style);
			BaseRecord tier = PbConfigUtil.styleTierConfig(null, false, es);
			BaseRecord node = inMemoryNode(null);
			BaseRecord eff = PbConfigUtil.resolveEffectiveConfig(null, node, false, null, es);
			assertEquals("the merged config carries the tier's model before the policy", listedGate, model(eff));
			String ov = PbModelPolicy.nodeOverride(node, PbModelTaskEnumType.PORTRAIT);
			assertNull(ov);
			ModelResolver.Resolution r = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.PORTRAIT, eff, ov, SDAPIEnumType.SWARM, server, false);
			assertTrue(r.toString(), r.isResolved());
			assertEquals("the style's PORTRAIT pin is the pick, not the merged config's model", ModelResolver.Step.STYLE_TASK, r.getStep());
			assertEquals(pin, r.getModel());
			assertEquals(pin, model(eff));
			if(second != null) {
				assertNotEquals("the pin beat the tier config's model", listedGate, model(eff));
			}
			else {
				logger.info("MEASURED: " + server + " lists one usable checkpoint; STYLE_TASK is proved by the step, not by a different name");
			}

			/// Had the merged model been fed back as the override (the rule the class comment forbids), the
			/// tier's checkpoint would win over the pin — show it on a fresh merge.
			BaseRecord effAlias = PbConfigUtil.resolveEffectiveConfig(null, node, false, null, es);
			ModelResolver.Resolution rAlias = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.PORTRAIT, effAlias,
				IllustrationModelPolicy.currentModel(effAlias, PbModelTaskEnumType.PORTRAIT), SDAPIEnumType.SWARM, server, false);
			assertEquals("feeding the merged model back as the override makes it OVERRIDE - which is why callers must not",
				ModelResolver.Step.OVERRIDE, rAlias.getStep());
			assertEquals(listedGate, model(effAlias));

			assertSourceUnchanged(u, oid(styleCfg), listedGate, flux2(tier));
		}
		finally {
			cleanup(u, style, styleCfg);
		}
	}

	// ─────────────────────────────── tier order (157 b/f) ───────────────────────────────

	/// A style's task pin wins over the deployment default when nothing explicit was asked for.
	@Test
	public void live09_stylePinWinsOverDeploymentDefault() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		String second = secondInstalled(inv, listedGate);
		String pin = (second != null ? second : listedGate);
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord style = createStyle(u, "p23-pin-style-" + tag, taskModelsJson(PbModelTaskEnumType.PORTRAIT, pin), null);
		try {
			PbStyleResolver.EffectiveStyle es = chainOf(u, style);
			assertEquals(pin, es.taskModels().get(PbModelTaskEnumType.PORTRAIT));
			BaseRecord tier = PbConfigUtil.styleTierConfig(null, false, es);
			logger.info("MEASURED: style tier config for a pin-only style = " + (tier == null ? "null (no library sdConfig)" : "library/chain config " + oid(tier)));
			BaseRecord copy = sparseBody("{\"schema\":\"olio.sd.config\",\"steps\":20}");
			ModelResolver.Resolution r = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.PORTRAIT, copy, null, SDAPIEnumType.SWARM, server, false);
			assertTrue(r.toString(), r.isResolved());
			assertEquals(ModelResolver.Step.STYLE_TASK, r.getStep());
			assertEquals(ModelResolver.Reason.OK, r.getReason());
			assertFalse(r.isDegraded());
			assertEquals(1, r.getCandidates().size());
			assertEquals(pin, r.getModel());
			assertEquals(pin, model(copy));
			if(second != null) {
				assertNotEquals("the deployment default (= gate) was not chosen", DeploymentModelDefaults.current().getModel(), model(copy));
			}
			/// The pin is for PORTRAIT only: LOCATION on the same style falls past it.
			BaseRecord copy2 = sparseBody("{\"schema\":\"olio.sd.config\",\"steps\":20}");
			ModelResolver.Resolution r2 = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.LOCATION, copy2, null, SDAPIEnumType.SWARM, server, false);
			assertTrue(r2.toString(), r2.isResolved());
			assertNotEquals("no LOCATION pin", ModelResolver.Step.STYLE_TASK, r2.getStep());
			assertTrue(r2.getStep() == ModelResolver.Step.STYLE_DEFAULT || r2.getStep() == ModelResolver.Step.DEPLOYMENT_DEFAULT);
			logger.info("MEASURED: LOCATION on a portrait-pinned style resolved at " + r2.getStep() + " → " + r2.getModel());
		}
		finally {
			cleanup(u, style);
		}
	}

	/// 157 b/f: the client's explicit value (the override) wins over the style's task pin.
	@Test
	public void live10_overrideWinsOverStylePin() throws Exception {
		requireLive();
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		String listedGate = listed(inv, gate);
		String second = secondInstalled(inv, listedGate);
		String pin = (second != null ? second : listedGate);
		BaseRecord u = user();
		String tag = shortId();

		BaseRecord style = createStyle(u, "p23-ov-style-" + tag, taskModelsJson(PbModelTaskEnumType.PORTRAIT, pin), null);
		try {
			PbStyleResolver.EffectiveStyle es = chainOf(u, style);
			BaseRecord tier = PbConfigUtil.styleTierConfig(null, false, es);
			/// The client body names the gate explicitly: that IS the override.
			BaseRecord copy = sparseBody("{\"schema\":\"olio.sd.config\",\"model\":\"" + gate + "\"}");
			String override = IllustrationModelPolicy.currentModel(copy, PbModelTaskEnumType.PORTRAIT);
			assertEquals(gate, override);
			ModelResolver.Resolution r = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.PORTRAIT, copy, override, SDAPIEnumType.SWARM, server, false);
			assertTrue(r.toString(), r.isResolved());
			assertEquals(ModelResolver.Step.OVERRIDE, r.getStep());
			assertEquals(1, r.getCandidates().size());
			assertEquals(listedGate, r.getModel());
			assertEquals(listedGate, model(copy));
			if(second != null) {
				assertNotEquals("the pin was NOT chosen", pin, model(copy));
			}
			else {
				logger.info("MEASURED: " + server + " lists one usable checkpoint; OVERRIDE is proved by the step and the single candidate");
			}

			/// The same through IllustrationModelPolicy.apply with the chain's inputs spelled out.
			BaseRecord copy2 = sparseBody("{\"schema\":\"olio.sd.config\",\"model\":\"" + gate + "\"}");
			ModelResolver.Resolution r2 = IllustrationModelPolicy.apply(PbModelTaskEnumType.PORTRAIT, copy2, override, es.taskModels(), null, null, SDAPIEnumType.SWARM, server, false);
			assertEquals(ModelResolver.Step.OVERRIDE, r2.getStep());
			assertEquals(listedGate, model(copy2));

			/// An override that is NOT installed loses to the pin (degraded substitution, names the rejected request).
			String bogus = "p23-ov-not-installed-" + tag;
			BaseRecord copy3 = sparseBody("{\"schema\":\"olio.sd.config\",\"model\":\"" + bogus + "\"}");
			ModelResolver.Resolution r3 = PbModelPolicy.applyForStyle(es, tier, PbModelTaskEnumType.PORTRAIT, copy3,
				IllustrationModelPolicy.currentModel(copy3, PbModelTaskEnumType.PORTRAIT), SDAPIEnumType.SWARM, server, false);
			assertEquals(ModelResolver.Step.STYLE_TASK, r3.getStep());
			assertTrue(r3.isDegraded());
			assertTrue(r3.getDegradationText(), r3.getDegradationText().contains(bogus));
			assertEquals(pin, model(copy3));
		}
		finally {
			cleanup(u, style);
		}
	}
}
