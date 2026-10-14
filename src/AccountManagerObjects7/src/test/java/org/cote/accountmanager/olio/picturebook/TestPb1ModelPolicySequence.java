package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.illustration.CheckpointNames;
import org.cote.accountmanager.olio.illustration.IllustrationModelPolicy;
import org.cote.accountmanager.olio.illustration.ModelInventory;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.SceneGenerationParams;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * P2-3 architect BLOCKER (fixed 2026-10-13 20:05, {@code PictureBookUtil.java:9815-9828}): PB1's
 * {@code common} config is an ALIAS of {@code params.sdConfig}, so reading the PORTRAIT override from
 * {@code params.sdConfig.model} AFTER the LOCATION pass had been written into {@code common} made the
 * LOCATION pick the PORTRAIT "override" — the style's PORTRAIT pin could never win. The fix captures
 * every task's explicit value through {@link PictureBookUtil#explicitModel} BEFORE the first write.
 * <p>
 * Lives in this package because {@code explicitModel} is package-private. Runs against the live Swarm
 * inventory ({@code test.swarm.server}) and {@code am7db} as {@code testUser1}; no render. Needs TWO
 * installed PORTRAIT/LOCATION-capable checkpoints (X for the portrait pin, Y for the location pin) so
 * the two picks are distinguishable by name — an {@code Assume} skip otherwise, which is NOT a pass.
 */
public class TestPb1ModelPolicySequence extends BaseTest {

	private static final String ORG = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SCRATCH_PATH = "~/P23ModelPolicy";

	private String server;

	@Before
	public void sequenceSetup() {
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
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

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/// Installed checkpoints usable for PORTRAIT (and so LOCATION), in inventory order.
	private static List<String> usableCheckpoints(ModelInventory inv) {
		List<String> out = new ArrayList<>();
		for(String n : inv.installedModels()) {
			if(n == null) {
				continue;
			}
			RendererCapabilities rc = RendererCapabilities.forModel(n);
			if(rc != null && rc.isEnabled() && ModelResolver.supportsTask(rc, PbModelTaskEnumType.PORTRAIT, false) == ModelResolver.Reason.OK
				&& ModelResolver.supportsTask(rc, PbModelTaskEnumType.LOCATION, false) == ModelResolver.Reason.OK) {
				out.add(n);
			}
		}
		return out;
	}

	private BaseRecord createStyle(BaseRecord u, String name, String taskModels) {
		BaseRecord style = null;
		try {
			style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, style, name, SCRATCH_PATH, orgOf(u));
			style.set(FieldNames.FIELD_NAME, name);
			style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.LIBRARY.toString());
			style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, taskModels);
		}
		catch(Exception e) {
			fail("Failed to assemble olio.pb.style '" + name + "': " + e.getMessage());
		}
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("AccessPoint.create must accept olio.pb.style '" + name + "'", created);
		return created;
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

	/// The PB1 client body as it arrives: filtered module, no model key.
	private static BaseRecord sparseClientConfig() {
		BaseRecord rec = JSONUtil.importObject("{\"schema\":\"olio.sd.config\",\"style\":\"art\",\"steps\":20}", LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertNotNull(rec);
		assertEquals(OlioModelNames.MODEL_SD_CONFIG, rec.getSchema());
		assertFalse("precondition: the client named no checkpoint", rec.hasField(OlioFieldNames.FIELD_SD_MODEL));
		return rec;
	}

	private static String model(BaseRecord cfg) {
		return (cfg != null && cfg.hasField(OlioFieldNames.FIELD_SD_MODEL) ? cfg.get(OlioFieldNames.FIELD_SD_MODEL) : null);
	}

	/// Against the LIVE node: two installed checkpoints from its inventory.
	@Test
	public void pb1PortraitOverrideIsNotTheLocationPick() throws Exception {
		SdTestGate.requireSwarmReachable(server);
		ModelInventory inv = SwarmModelInventory.forServer(SDAPIEnumType.SWARM, server);
		assertNull("The live inventory read must succeed against " + server + ": " + inv.error(), inv.error());
		List<String> usable = usableCheckpoints(inv);
		Assume.assumeTrue("SKIPPED: " + server + " lists fewer than two installed PORTRAIT-capable checkpoints (" + usable
			+ "), so a portrait pin and a location pin cannot be told apart by name. This is NOT a pass.", usable.size() >= 2);
		runSequence(server, usable.get(0), usable.get(1));
	}

	/**
	 * The same product path with the ONLY remote dependency - the installed-checkpoint list read from
	 * the Swarm node - replaced by a snapshot seeded into {@link SwarmModelInventory}'s private cache
	 * (test-only instrumentation, reflection, nothing in product code). Everything else is the real
	 * code: {@code explicitModel}, {@code PbModelPolicy.applyForStyle}, {@code IllustrationModelPolicy}'s
	 * resolve + write, the live {@code am7db} style chain. Exists so the aliasing regression is pinned
	 * on every run, not only when {@code 192.168.1.39} answers; the live case above remains the
	 * end-to-end proof.
	 */
	@Test
	public void pb1PortraitOverrideIsNotTheLocationPick_seededInventory() throws Exception {
		String seededServer = "http://p23-seeded-inventory.invalid:1";
		String x = "p23SeededPortrait_sdxl.safetensors";
		String y = "p23SeededLocation_sdxl.safetensors";
		assertNotNull("precondition: the seeded names must be of a known, PORTRAIT-capable family", RendererCapabilities.forModel(x));
		assertEquals(ModelResolver.Reason.OK, ModelResolver.supportsTask(RendererCapabilities.forModel(x), PbModelTaskEnumType.PORTRAIT, false));
		assertEquals(ModelResolver.Reason.OK, ModelResolver.supportsTask(RendererCapabilities.forModel(y), PbModelTaskEnumType.LOCATION, false));
		seedInventory(seededServer, List.of(x, y));
		try {
			ModelInventory inv = SwarmModelInventory.forServer(SDAPIEnumType.SWARM, seededServer);
			assertNull("the seeded snapshot must be served from the cache, not fetched", inv.error());
			assertEquals(List.of(x, y), inv.installedModels());
			runSequence(seededServer, x, y);
		}
		finally {
			SwarmModelInventory.invalidate(seededServer);
		}
	}

	/// Put a successful in-memory snapshot into SwarmModelInventory.CACHE under {@code server} (TTL from now).
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void seedInventory(String server, List<String> installed) throws Exception {
		java.lang.reflect.Field cacheField = SwarmModelInventory.class.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		Map cache = (Map) cacheField.get(null);
		java.lang.reflect.Constructor<SwarmModelInventory> ctor = SwarmModelInventory.class.getDeclaredConstructor(String.class, List.class, long.class);
		ctor.setAccessible(true);
		cache.put(server.trim(), ctor.newInstance(server.trim(), new ArrayList<>(installed), System.currentTimeMillis()));
	}

	private void runSequence(String server, String x, String y) throws Exception {
		assertNotEquals(x, y);
		logger.info("MEASURED: portrait pin X='" + x + "', location pin Y='" + y + "' on " + server);

		BaseRecord u = user();
		Map<String, String> pins = new LinkedHashMap<>();
		pins.put(ModelTaskKeys.key(PbModelTaskEnumType.PORTRAIT), x);
		pins.put(ModelTaskKeys.key(PbModelTaskEnumType.LOCATION), y);
		BaseRecord style = createStyle(u, "p23-seq-style-" + shortId(), JSONUtil.exportObject(pins));
		try {
			PbStyleResolver.EffectiveStyle pbStyle = PbStyleResolver.resolveFromStyle(u, style);
			assertNotNull(pbStyle);
			assertTrue("the scratch style's chain must resolve complete: " + pbStyle, pbStyle.isComplete());
			assertEquals(x, pbStyle.taskModels().get(PbModelTaskEnumType.PORTRAIT));
			assertEquals(y, pbStyle.taskModels().get(PbModelTaskEnumType.LOCATION));
			logger.info("MEASURED: chain sdConfig = " + (pbStyle.sdConfig() == null ? "null (no style default)" : "library/chain config " + pbStyle.sdConfig().get(FieldNames.FIELD_OBJECT_ID)));

			// ── NEW sequence (PictureBookUtil :9592-9598, :9815-9828, :10023-10044) ──
			SceneGenerationParams params = new SceneGenerationParams();
			params.sdConfig = sparseClientConfig();
			BaseRecord common = params.sdConfig;                       // the alias, exactly as PB1 holds it
			SDUtil.fillStyleDefaults(common);
			assertFalse(common.hasField(OlioFieldNames.FIELD_SD_MODEL));
			String explicitLocation = PictureBookUtil.explicitModel(params, PbModelTaskEnumType.LOCATION);
			String explicitPortrait = PictureBookUtil.explicitModel(params, PbModelTaskEnumType.PORTRAIT);
			String explicitSceneDirect = PictureBookUtil.explicitModel(params, PbModelTaskEnumType.SCENE_DIRECT);
			assertNull("no explicit LOCATION model", explicitLocation);
			assertNull("no explicit PORTRAIT model", explicitPortrait);
			assertNull("no explicit SCENE_DIRECT model", explicitSceneDirect);
			BaseRecord pbStyleTier = (pbStyle.sdConfig() != null ? pbStyle.sdConfig() : common.copyRecord());
			String tierModelBefore = model(pbStyleTier);

			ModelResolver.Resolution rLoc = PbModelPolicy.applyForStyle(pbStyle, pbStyleTier, PbModelTaskEnumType.LOCATION, common, explicitLocation, SDAPIEnumType.SWARM, server, false);
			assertTrue(rLoc.toString(), rLoc.isResolved());
			assertEquals(ModelResolver.Step.STYLE_TASK, rLoc.getStep());
			assertEquals(y, rLoc.getModel());
			assertEquals("the LOCATION pin is written into common (= params.sdConfig)", y, model(common));
			assertEquals("...and params.sdConfig IS common", y, model(params.sdConfig));

			BaseRecord portCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			ModelResolver.Resolution rPort = PbModelPolicy.applyForStyle(pbStyle, pbStyleTier, PbModelTaskEnumType.PORTRAIT, portCfg, explicitPortrait, SDAPIEnumType.SWARM, server, false);
			assertTrue(rPort.toString(), rPort.isResolved());
			assertEquals("PORTRAIT resolves at the style's PORTRAIT pin, not as an OVERRIDE of the LOCATION pick", ModelResolver.Step.STYLE_TASK, rPort.getStep());
			assertEquals(x, rPort.getModel());
			assertEquals(x, model(portCfg));
			assertNotEquals("the PORTRAIT pick is NOT the LOCATION pick", y, model(portCfg));
			assertEquals("the LOCATION write on common survives the PORTRAIT pass", y, model(common));

			if(pbStyle.sdConfig() == null) {
				assertFalse("pbStyleTier (common.copyRecord() taken before the first apply) still carries no model after the LOCATION write",
					pbStyleTier.hasField(OlioFieldNames.FIELD_SD_MODEL) && model(pbStyleTier) != null);
			}
			else {
				assertEquals("the chain's config record is not written by either pass", tierModelBefore, model(pbStyleTier));
				logger.info("MEASURED: the chain carried a library sdConfig (" + tierModelBefore + "); the copyRecord branch was not the tier here");
			}

			// ── OLD sequence, reproduced inline (the override read AFTER the LOCATION write) ──
			SceneGenerationParams oldParams = new SceneGenerationParams();
			oldParams.sdConfig = sparseClientConfig();
			BaseRecord oldCommon = oldParams.sdConfig;
			SDUtil.fillStyleDefaults(oldCommon);
			BaseRecord oldTier = (pbStyle.sdConfig() != null ? pbStyle.sdConfig() : oldCommon.copyRecord());
			ModelResolver.Resolution oldLoc = PbModelPolicy.applyForStyle(pbStyle, oldTier, PbModelTaskEnumType.LOCATION, oldCommon,
				PictureBookUtil.explicitModel(oldParams, PbModelTaskEnumType.LOCATION), SDAPIEnumType.SWARM, server, false);
			assertEquals(y, oldLoc.getModel());
			String explicitPortraitOld = PictureBookUtil.explicitModel(oldParams, PbModelTaskEnumType.PORTRAIT);   // read late: sees the alias
			assertEquals("the late read returns the LOCATION pick through the alias", y, explicitPortraitOld);
			BaseRecord oldPortCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			ModelResolver.Resolution oldPort = PbModelPolicy.applyForStyle(pbStyle, oldTier, PbModelTaskEnumType.PORTRAIT, oldPortCfg, explicitPortraitOld, SDAPIEnumType.SWARM, server, false);
			assertEquals("on the old ordering PORTRAIT is an OVERRIDE...", ModelResolver.Step.OVERRIDE, oldPort.getStep());
			assertEquals("...of the LOCATION pick, so the portrait pin X never wins - this is the assertion the fix makes pass", y, model(oldPortCfg));
			logger.info("MEASURED: old ordering -> PORTRAIT step=" + oldPort.getStep() + " model=" + model(oldPortCfg) + "; new ordering -> step=" + rPort.getStep() + " model=" + model(portCfg));
		}
		finally {
			cleanup(u, style);
		}
	}

	/// explicitModel's precedence (composite config for SCENE_DIRECT, then the per-scene override, then the request config) without any inventory.
	@Test
	public void explicitModelPrecedence() {
		assertNull(PictureBookUtil.explicitModel(null, PbModelTaskEnumType.PORTRAIT));
		SceneGenerationParams p = new SceneGenerationParams();
		assertNull("nothing set", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.PORTRAIT));

		p.sdConfig = JSONUtil.importObject("{\"schema\":\"olio.sd.config\",\"model\":\"req-model\",\"flux2Model\":\"req-flux2\"}", LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertEquals("req-model", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.PORTRAIT));
		assertEquals("req-model", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.LOCATION));
		assertEquals("req-flux2", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.SCENE_DIRECT));

		p.sdConfigOverride = JSONUtil.importObject("{\"schema\":\"olio.sd.config\",\"model\":\"scene-model\"}", LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertEquals("the per-scene override wins over the request config", "scene-model", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.PORTRAIT));
		assertEquals("...for every model-field task", "scene-model", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.LOCATION));
		assertEquals("a per-scene override without flux2Model does not shadow the request's", "req-flux2", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.SCENE_DIRECT));

		p.compositeSdConfig = JSONUtil.importObject("{\"schema\":\"olio.sd.config\",\"model\":\"comp-model\",\"flux2Model\":\"comp-flux2\"}", LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertEquals("the composite config is consulted first for SCENE_DIRECT only", "comp-flux2", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.SCENE_DIRECT));
		assertEquals("...never for PORTRAIT", "scene-model", PictureBookUtil.explicitModel(p, PbModelTaskEnumType.PORTRAIT));

		/// A blank value is "nothing named", not an override.
		SceneGenerationParams q = new SceneGenerationParams();
		q.sdConfig = JSONUtil.importObject("{\"schema\":\"olio.sd.config\",\"model\":\"  \"}", LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertNull(PictureBookUtil.explicitModel(q, PbModelTaskEnumType.PORTRAIT));
		assertNull(IllustrationModelPolicy.currentModel(q.sdConfig, PbModelTaskEnumType.PORTRAIT));
		assertNull(CheckpointNames.matchInstalled(new ArrayList<>(), "anything"));
	}
}
