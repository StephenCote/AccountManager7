package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.illustration.CheckpointNames;
import org.cote.accountmanager.olio.illustration.ModelInventory;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PbStyleUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 2, P2-2: {@code ModelResolver} against the LIVE Swarm node's real inventory
 * ({@code test.swarm.server}, design §16/§36.1), and {@code PbServiceFacade.listModelsForTask} as a
 * non-admin user against the live {@code am7db}.
 * <p>
 * Nothing here names a checkpoint it assumes installed (§36.5): the SDXL checkpoint comes from
 * {@code test.swarm.model}, which {@code SdTestGate.resolveInstalledCheckpoints} wrote back in
 * {@code BaseTest.setup()} from the node's own list; the FLUX.2 name comes from
 * {@code test.swarm.flux2Model} and the test asserts the resolver reports whatever the inventory says
 * about it, installed or not. Every case that needs the SDXL checkpoint is SKIPPED (not passed) when
 * the gate resolved none. The pure resolution rules are {@code TestModelResolver}; this class proves the
 * two things that need a live server — the inventory read and the facade's end-to-end path.
 */
public class TestModelResolverLive extends BaseTest {

	private static final String ORG = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SCRATCH_PATH = "~/P22ModelResolver";
	/// A closed port on the loopback: the inventory read fails fast with a connection refusal.
	private static final String UNREACHABLE_SERVER = "http://127.0.0.1:1";

	private String server;

	@Before
	public void liveSetup() {
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SdTestGate.requireSwarmReachable(server);
		SwarmModelInventory.invalidateAll();
	}

	// ─────────────────────────────── fixtures ───────────────────────────────

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
		return createStyle(u, name, taskModels, sdConfig, SCRATCH_PATH);
	}

	private BaseRecord createStyle(BaseRecord u, String name, String taskModels, BaseRecord sdConfig, String groupPath) {
		BaseRecord style = null;
		try {
			style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, style, name, groupPath, orgOf(u));
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		assertNotNull("expected a map", o);
		assertTrue("expected a map, got " + o.getClass(), o instanceof Map);
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		assertNotNull("expected a list", o);
		assertTrue("expected a list, got " + o.getClass(), o instanceof List);
		return (List<Map<String, Object>>) o;
	}

	// ─────────────────────────────── 1–2: the live inventory ───────────────────────────────

	@Test
	public void case01_inventoryListsTheGateCheckpoint() {
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		assertEquals(server, inv.server());
		assertTrue("fetchedAt must be a real timestamp", inv.fetchedAt() > 0L);
		String listed = CheckpointNames.matchInstalled(inv.installedModels(), gate);
		assertNotNull("The gate's own checkpoint '" + gate + "' must be in the live list " + inv.installedModels(), listed);
		logger.info("Live inventory for " + server + ": " + inv.installedModels().size() + " checkpoints; gate model listed as '" + listed + "'");
	}

	@Test
	public void case02_inventoryIsCachedWithinTtlAndInvalidateForcesAReread() {
		ModelInventory first = liveInventory();
		ModelInventory second = SwarmModelInventory.forServer(SDAPIEnumType.SWARM, server);
		assertTrue("A second read within the TTL must return the cached instance", first == second);
		assertEquals(first.fetchedAt(), second.fetchedAt());
		SwarmModelInventory.invalidate(server);
		ModelInventory third = SwarmModelInventory.forServer(SDAPIEnumType.SWARM, server);
		assertNull("The re-read must succeed: " + third.error(), third.error());
		assertTrue("After invalidate the read must be a fresh instance", first != third);
		assertTrue("The fresh read is no older than the cached one", third.fetchedAt() >= first.fetchedAt());
		assertEquals("Same node, same list", first.installedModels(), third.installedModels());
	}

	@Test
	public void case03_unreachableServerIsInventoryUnavailableNotAnException() {
		ModelInventory inv = SwarmModelInventory.forServer(SDAPIEnumType.SWARM, UNREACHABLE_SERVER);
		assertNotNull("A refused connection must surface as inventory.error", inv.error());
		assertTrue("…and an empty list", inv.installedModels().isEmpty());
		ModelResolver.Resolution r = ModelResolver.resolve(
			ModelResolver.Request.builder(PbModelTaskEnumType.PORTRAIT).deploymentModel("anything").build(), inv);
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE, r.getReason());
		assertFalse(r.isResolved());
		assertNotNull(r.getInventoryError());
		/// Security review BLOCKER: the server URL is deployment config and must not ride on user-visible error text.
		assertNoServerUrl("inventory.error()", inv.error());
		assertNoServerUrl("Resolution.inventoryError", r.getInventoryError());
		assertNoServerUrl("Resolution.degradationText", r.getDegradationText());
	}

	private static void assertNoServerUrl(String what, String text) {
		if(text == null) {
			return;
		}
		assertFalse(what + " must not embed the SD server URL " + UNREACHABLE_SERVER + " (got: " + text + ")", text.contains(UNREACHABLE_SERVER));
		assertFalse(what + " must not embed the SD server host:port (got: " + text + ")", text.contains("127.0.0.1:1"));
	}

	// ─────────────────────────────── 4–7: resolution against the live list ───────────────────────────────

	@Test
	public void case04_gateCheckpointResolvesAsOverrideForPortrait() {
		String gate = gateModel();
		ModelInventory inv = liveInventory();
		ModelResolver.Resolution r = ModelResolver.resolve(
			ModelResolver.Request.builder(PbModelTaskEnumType.PORTRAIT).override(gate).build(), inv);
		assertTrue("Override of an installed SDXL checkpoint must resolve: " + r, r.isResolved());
		assertEquals(ModelResolver.Step.OVERRIDE, r.getStep());
		assertEquals(ModelResolver.Reason.OK, r.getReason());
		assertFalse("A first-step hit is not a degradation", r.isDegraded());
		assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), r.getModel());
		assertEquals(RendererCapabilities.PATTERN_SDXL, r.getFamily());
		assertEquals(inv.fetchedAt(), r.getInventoryFetchedAt());
		assertEquals(1, r.getCandidates().size());
	}

	@Test
	public void case05_missingOverrideFallsThroughToDeploymentDefaultAndSaysSo() {
		String gate = gateModel();
		String bogus = "p22-not-installed-" + shortId();
		ModelInventory inv = liveInventory();
		ModelResolver.Resolution r = ModelResolver.resolve(
			ModelResolver.Request.builder(PbModelTaskEnumType.PORTRAIT).override(bogus).deploymentModel(gate).build(), inv);
		assertTrue("The deployment default must still resolve: " + r, r.isResolved());
		assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT, r.getStep());
		assertTrue("A substitution is a degradation", r.isDegraded());
		assertEquals(ModelResolver.DEGRADATION_MODEL_SUBSTITUTED, r.getDegradationCode());
		assertNotNull(r.getDegradationText());
		assertTrue("The degradation text names the rejected override: " + r.getDegradationText(),
			r.getDegradationText().contains(bogus));
		assertEquals(2, r.getCandidates().size());
		assertEquals(ModelResolver.Reason.NOT_INSTALLED, r.getCandidates().get(0).getReason());
		assertEquals(ModelResolver.Reason.OK, r.getCandidates().get(1).getReason());
	}

	@Test
	public void case06_flux2ForSceneDirectReportsTheInventorysTruth() {
		String flux2 = flux2Model();
		ModelInventory inv = liveInventory();
		String listed = CheckpointNames.matchInstalled(inv.installedModels(), flux2);
		ModelResolver.Resolution r = ModelResolver.resolve(
			ModelResolver.Request.builder(PbModelTaskEnumType.SCENE_DIRECT).styleFlux2Model(flux2).referencesRequested(true).build(), inv);
		assertEquals(1, r.getCandidates().size());
		ModelResolver.Candidate c = r.getCandidates().get(0);
		assertEquals(ModelResolver.Step.STYLE_DEFAULT, c.getStep());
		assertEquals(flux2, c.getRequested());
		if(listed == null) {
			logger.info("FLUX.2 checkpoint '" + flux2 + "' is NOT installed on " + server + "; resolver must say NOT_INSTALLED");
			assertEquals(ModelResolver.Reason.NOT_INSTALLED, c.getReason());
			assertFalse(r.isResolved());
			assertEquals(ModelResolver.Reason.NO_CANDIDATE, r.getReason());
			assertTrue(r.getDegradationText().contains(flux2));
		}
		else {
			logger.info("FLUX.2 checkpoint '" + flux2 + "' is installed on " + server + " as '" + listed + "'");
			assertEquals(ModelResolver.Reason.OK, c.getReason());
			assertTrue(r.isResolved());
			assertEquals(listed, r.getModel());
			assertEquals(RendererCapabilities.PATTERN_FLUX2, r.getFamily());
			assertTrue("flux2 capabilities carry supportsReferences", r.getCapabilities().isSupportsReferences());
		}
	}

	@Test
	public void case07_anyInstalledKontextIsFamilyDisabledEvenAsAnOverride() {
		ModelInventory inv = liveInventory();
		String kontext = null;
		for(String name : inv.installedModels()) {
			RendererCapabilities rc = RendererCapabilities.forModel(name);
			if(rc != null && RendererCapabilities.PATTERN_KONTEXT.equals(rc.getPattern())) {
				kontext = name;
				break;
			}
		}
		Assume.assumeTrue("SKIPPED: " + server + " lists no Kontext checkpoint. This is NOT a pass.", kontext != null);
		ModelResolver.Resolution r = ModelResolver.resolve(
			ModelResolver.Request.builder(PbModelTaskEnumType.PORTRAIT).override(kontext).build(), inv);
		assertFalse("Kontext is retired (§37.3): " + r, r.isResolved());
		assertEquals(1, r.getCandidates().size());
		assertEquals(ModelResolver.Reason.FAMILY_DISABLED, r.getCandidates().get(0).getReason());
		assertEquals(kontext, r.getCandidates().get(0).getListedName());
	}

	// ─────────────────────────────── 8–11: the facade, as the test user ───────────────────────────────

	@Test
	public void case08_facadeListsEveryInstalledCheckpointAndResolvesTheDeploymentDefault() {
		String gate = gateModel();
		BaseRecord u = user();
		ModelInventory inv = liveInventory();
		Map<String, Object> dto = PbServiceFacade.listModelsForTask(u, "portrait", "SWARM", server, null);
		assertEquals("portrait", dto.get("task"));
		Map<String, Object> invDto = map(dto.get("inventory"));
		assertNull(invDto.get("error"));
		assertEquals(inv.installedModels().size(), ((Number) invDto.get("count")).intValue());
		List<Map<String, Object>> models = list(dto.get("models"));
		assertEquals("One row per installed checkpoint", inv.installedModels().size(), models.size());
		int supported = 0;
		String gateListed = CheckpointNames.matchInstalled(inv.installedModels(), gate);
		Map<String, Object> gateRow = null;
		for(Map<String, Object> m : models) {
			assertNotNull(m.get("listedName"));
			assertNotNull(m.get("reason"));
			assertTrue(inv.installedModels().contains(m.get("listedName")));
			if(Boolean.TRUE.equals(m.get("supportedForTask"))) {
				supported++;
				assertEquals("A supported row's reason is OK", ModelResolver.Reason.OK.name(), m.get("reason"));
				assertEquals("A supported row is an enabled family", Boolean.TRUE, m.get("enabled"));
				assertNotNull("A supported row names its family", m.get("family"));
			}
			else {
				assertTrue("An unsupported row must say why, not OK: " + m, !ModelResolver.Reason.OK.name().equals(m.get("reason")));
			}
			if(gateListed.equals(m.get("listedName"))) {
				gateRow = m;
			}
		}
		assertTrue("The gate's SDXL checkpoint is supported for PORTRAIT, so at least one row is", supported >= 1);
		/// The gate checkpoint is a known SDXL-family row: its family, enabled and support flags are checkable, not just non-null.
		assertNotNull("The gate checkpoint '" + gateListed + "' must have a row", gateRow);
		assertEquals(RendererCapabilities.PATTERN_SDXL, gateRow.get("family"));
		assertEquals(Boolean.TRUE, gateRow.get("enabled"));
		assertEquals(Boolean.TRUE, gateRow.get("supportedForTask"));
		assertEquals(ModelResolver.Reason.OK.name(), gateRow.get("reason"));
		/// §6.C 152 f / architect correction 8: the wire DTO never carries the SD server URL as a key.
		assertFalse("DTO must not carry a top-level server key", dto.containsKey("server"));
		assertFalse("inventory must not carry a server key", invDto.containsKey("server"));
		assertFalse("resolution must not carry a server key", map(dto.get("resolution")).containsKey("server"));
		/// BaseTest.setup pinned SDUtil.setDefaultModel to the gate model, so the deployment default is it.
		Map<String, Object> dep = map(dto.get("deploymentDefault"));
		assertEquals(gate, dep.get("model"));
		Map<String, Object> res = map(dto.get("resolution"));
		assertEquals(ModelResolver.Reason.OK.name(), res.get("reason"));
		assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT.name(), res.get("step"));
		assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), res.get("model"));
		assertEquals(Boolean.FALSE, res.get("degraded"));
		assertEquals(1, list(res.get("candidates")).size());
	}

	@Test
	public void case09_facadeRejectsBadInputBeforeTouchingTheServer() {
		BaseRecord u = user();
		try {
			PbServiceFacade.listModelsForTask(u, "bogus", "SWARM", server, null);
			fail("An unknown task must be a 400");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().contains("bogus"));
		}
		try {
			PbServiceFacade.listModelsForTask(null, "portrait", "SWARM", server, null);
			fail("No principal must be a 401");
		}
		catch(PictureBookException e) {
			assertEquals(401, e.getStatus());
		}
		try {
			PbServiceFacade.listModelsForTask(u, "portrait", "SWARM", "", null);
			fail("A blank SD server must be a 500");
		}
		catch(PictureBookException e) {
			assertEquals(500, e.getStatus());
		}
		try {
			PbServiceFacade.listModelsForTask(u, "portrait", "SWARM", server, "p22-no-such-style-" + shortId());
			fail("An absent style must be a 404");
		}
		catch(PictureBookException e) {
			assertEquals(404, e.getStatus());
		}
		/// An unrecognised sd.server.apiType is a 500 whose message does not echo the raw init-param value.
		try {
			PbServiceFacade.listModelsForTask(u, "portrait", "NOPE", server, null);
			fail("An unrecognised SD API type must be a 500");
		}
		catch(PictureBookException e) {
			assertEquals(500, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().contains("not recognised"));
			assertFalse("The 500 must not echo the raw apiType value (got: " + e.getMessage() + ")", e.getMessage().contains("NOPE"));
		}
	}

	@Test
	public void case10_facadeUnreachableServerIsA200WithInventoryUnavailable() {
		BaseRecord u = user();
		Map<String, Object> dto = PbServiceFacade.listModelsForTask(u, "portrait", "SWARM", UNREACHABLE_SERVER, null);
		Map<String, Object> invDto = map(dto.get("inventory"));
		assertNotNull("inventory.error must carry the read failure", invDto.get("error"));
		assertEquals(0, ((Number) invDto.get("count")).intValue());
		assertTrue(list(dto.get("models")).isEmpty());
		Map<String, Object> res = map(dto.get("resolution"));
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), res.get("reason"));
		assertNull(res.get("model"));
		assertNull(res.get("step"));
		assertTrue(list(res.get("candidates")).isEmpty());
		assertNotNull(res.get("inventoryError"));
		assertNotNull(res.get("degradationText"));
		/// Security review BLOCKER (§6.C 152 f / architect correction 8): no server key, and no URL inside any wire string.
		assertFalse("DTO must not carry a top-level server key", dto.containsKey("server"));
		assertFalse("inventory must not carry a server key", invDto.containsKey("server"));
		assertFalse("resolution must not carry a server key", res.containsKey("server"));
		assertNoServerUrl("inventory.error", (String) invDto.get("error"));
		assertNoServerUrl("resolution.inventoryError", (String) res.get("inventoryError"));
		assertNoServerUrl("resolution.degradationText", (String) res.get("degradationText"));
	}

	@Test
	public void case11_facadeResolvesThroughTheStyleStepsAsTheOwningUser() {
		String gate = gateModel();
		String flux2 = flux2Model();
		BaseRecord u = user();
		ModelInventory inv = liveInventory();
		String tag = shortId();
		BaseRecord cfg = createSdConfig(u, "p22-sdcfg-" + tag, gate, flux2);
		BaseRecord style = createStyle(u, "p22-style-" + tag, "{\"portrait\":\"" + gate + "\"}", cfg);
		String styleOid = style.get(FieldNames.FIELD_OBJECT_ID);

		/// portrait: the style's per-task entry wins at STYLE_TASK.
		Map<String, Object> portrait = PbServiceFacade.listModelsForTask(u, "portrait", "SWARM", server, styleOid);
		Map<String, Object> res = map(portrait.get("resolution"));
		assertEquals(ModelResolver.Reason.OK.name(), res.get("reason"));
		assertEquals(ModelResolver.Step.STYLE_TASK.name(), res.get("step"));
		assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), res.get("model"));

		/// location: no per-task entry, so the style's sdConfig.model is next (STYLE_DEFAULT).
		Map<String, Object> location = PbServiceFacade.listModelsForTask(u, "location", "SWARM", server, styleOid);
		res = map(location.get("resolution"));
		assertEquals(ModelResolver.Reason.OK.name(), res.get("reason"));
		assertEquals(ModelResolver.Step.STYLE_DEFAULT.name(), res.get("step"));

		/// sceneDirect: the style's sdConfig.flux2Model is tried at STYLE_DEFAULT and reported as the
		/// inventory has it — installed and OK, or NOT_INSTALLED and on to the deployment default.
		Map<String, Object> scene = PbServiceFacade.listModelsForTask(u, "sceneDirect", "SWARM", server, styleOid);
		res = map(scene.get("resolution"));
		List<Map<String, Object>> cands = list(res.get("candidates"));
		assertFalse(cands.isEmpty());
		Map<String, Object> styleStep = cands.get(0);
		assertEquals(ModelResolver.Step.STYLE_DEFAULT.name(), styleStep.get("step"));
		assertEquals(flux2, styleStep.get("requested"));
		String listed = CheckpointNames.matchInstalled(inv.installedModels(), flux2);
		if(listed == null) {
			assertEquals(ModelResolver.Reason.NOT_INSTALLED.name(), styleStep.get("reason"));
			assertTrue("Not resolved at the style step: " + res, !ModelResolver.Step.STYLE_DEFAULT.name().equals(res.get("step")));
		}
		else {
			assertEquals(ModelResolver.Reason.OK.name(), styleStep.get("reason"));
			assertEquals(ModelResolver.Step.STYLE_DEFAULT.name(), res.get("step"));
			assertEquals(listed, res.get("model"));
		}
		logger.info("sceneDirect through style " + styleOid + ": " + res);
	}

	// ─────────────────────────────── 12–16: added by the test-author audit ───────────────────────────────

	/// Fixture cleanup as the owning user through AccessPoint; a failed delete is logged, never asserted,
	/// so cleanup can never mask the case's own result.
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

	private BaseRecord secondUser() {
		OrganizationContext org = getTestOrganization(ORG);
		BaseRecord u2 = ioContext.getFactory().getCreateUser(org.getAdminUser(), "testUser2", org.getOrganizationId());
		assertNotNull("Failed to resolve testUser2", u2);
		return u2;
	}

	/**
	 * Gap (b): a style whose {@code taskModels} names a checkpoint the live node does not list. The facade
	 * must report the STYLE_TASK rejection as NOT_INSTALLED and fall through to the style's sdConfig.model
	 * (STYLE_DEFAULT), flagged as a MODEL_SUBSTITUTED degradation that names the rejected checkpoint.
	 */
	@Test
	public void case12_facadeStyleTaskNamingAnUninstalledCheckpointIsDegradedToStyleDefault() {
		String gate = gateModel();
		BaseRecord u = user();
		ModelInventory inv = liveInventory();
		String tag = shortId();
		String bogus = "p22-not-installed-" + tag;
		assertNull("Precondition: the bogus name must not be installed", CheckpointNames.matchInstalled(inv.installedModels(), bogus));
		BaseRecord cfg = createSdConfig(u, "p22-sdcfg-" + tag, gate, null);
		BaseRecord style = createStyle(u, "p22-style-" + tag, "{\"portrait\":\"" + bogus + "\"}", cfg);
		try {
			String styleOid = style.get(FieldNames.FIELD_OBJECT_ID);
			Map<String, Object> dto = PbServiceFacade.listModelsForTask(u, "portrait", "SWARM", server, styleOid);
			Map<String, Object> res = map(dto.get("resolution"));
			assertEquals(ModelResolver.Reason.OK.name(), res.get("reason"));
			assertEquals("The style's sdConfig.model is the next step", ModelResolver.Step.STYLE_DEFAULT.name(), res.get("step"));
			assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), res.get("model"));
			assertEquals(Boolean.TRUE, res.get("degraded"));
			assertEquals(ModelResolver.DEGRADATION_MODEL_SUBSTITUTED, res.get("degradationCode"));
			assertNotNull(res.get("degradationText"));
			assertTrue("degradationText names the rejected style-task checkpoint: " + res.get("degradationText"),
				((String) res.get("degradationText")).contains(bogus));
			List<Map<String, Object>> cands = list(res.get("candidates"));
			assertEquals("STYLE_TASK rejected, STYLE_DEFAULT accepted", 2, cands.size());
			assertEquals(ModelResolver.Step.STYLE_TASK.name(), cands.get(0).get("step"));
			assertEquals(bogus, cands.get(0).get("requested"));
			assertNull("A NOT_INSTALLED candidate has no listed name", cands.get(0).get("listedName"));
			assertEquals(ModelResolver.Reason.NOT_INSTALLED.name(), cands.get(0).get("reason"));
			assertEquals(ModelResolver.Step.STYLE_DEFAULT.name(), cands.get(1).get("step"));
			assertEquals(gate, cands.get(1).get("requested"));
			assertEquals(ModelResolver.Reason.OK.name(), cands.get(1).get("reason"));
		}
		finally {
			cleanup(u, style, cfg);
		}
	}

	/**
	 * Gap (a): the style references an {@code olio.sd.config} the asserting user cannot read.
	 * <p>
	 * The slice brief asked for "step 3 absent (DEPLOYMENT_DEFAULT), not an exception". Measured on am7db
	 * 2026-10-13 (bracketed {@code PolicyUtil} trace, log C:/tmp/p2-2-ta-o7-case13.log): <b>PBAC makes that
	 * state unreachable for a non-owner.</b> A style cannot be CREATED as testUser1 against a config testUser1
	 * cannot read ({@code AUDIT DENY testUser1 to ADD olio.pb.style}); and when testUser2 owns both records -
	 * the config in its private {@code ~/P22ModelResolver}, the style in a {@code ~/P22ModelResolverShared}
	 * group on which testUser2 grants testUser1 Read/GROUP + Read/DATA (the direct-grant shape
	 * {@code TestPbAutoApprove.readOnlyReader} uses) - the style's own group check passes
	 * ({@code groupdatareadaccess ... home.testuser2.p22modelresolvershared = true}) but {@code PolicyUtil} then adds
	 * a {@code READ foreign access pattern for olio.pb.style.sdConfig} rule against the private config, every
	 * branch of it fails, and the WHOLE style read is denied
	 * ({@code Evaluation Results for resource.policy.systemreadobject: ALL Pass = 1 Size = 2 = false}).
	 * <p>
	 * So the honest assertion is what the system does: the config-less sibling in the same shared group is
	 * readable by testUser1 (the grant works), the config-bearing style is not ({@code readStyle} null), the
	 * facade reports that as the same 404 as an absent style, and the owner resolves at STYLE_DEFAULT through
	 * the identical call - the principal is the only difference. The facade's "no readable style default"
	 * branch is exercised for the non-owner through the readable sibling (DEPLOYMENT_DEFAULT, one candidate),
	 * and for a dangling reference in {@link #case16_facadeStyleWhoseSdConfigWasDeletedResolvesAtDeploymentDefault}.
	 */
	@Test
	public void case13_facadeDeniesTheWholeStyleWhenItsSdConfigIsUnreadable() {
		String gate = gateModel();
		BaseRecord u1 = user();
		BaseRecord u2 = secondUser();
		assertTrue("Two distinct principals are required", !u1.get(FieldNames.FIELD_ID).equals(u2.get(FieldNames.FIELD_ID)));
		ModelInventory inv = liveInventory();
		String tag = shortId();
		long orgId = orgOf(u2);
		/// testUser2's config names an INSTALLED model, so a readable config resolves at STYLE_DEFAULT (the owner control).
		BaseRecord cfg2 = createSdConfig(u2, "p22-sdcfg-u2-" + tag, gate, null);
		BaseRecord style2 = createStyle(u2, "p22-style-u2-" + tag, null, cfg2, SCRATCH_PATH + "Shared");
		/// Control style in the same shared group with NO sdConfig: proves the group grant itself works, so a
		/// deny on style2 can only be about its foreign sdConfig.
		BaseRecord styleNoCfg = createStyle(u2, "p22-style-u2-nocfg-" + tag, null, null, SCRATCH_PATH + "Shared");
		long gid = ((Number) style2.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord sharedDir = ioContext.getAccessPoint().findById(u2, ModelNames.MODEL_GROUP, gid);
		assertNotNull("testUser2 must read its own shared group", sharedDir);
		BaseRecord readGroup = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		BaseRecord readData = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read/GROUP permission", readGroup);
		assertNotNull("Read/DATA permission", readData);
		try {
			String styleOid = style2.get(FieldNames.FIELD_OBJECT_ID);
			String siblingOid = styleNoCfg.get(FieldNames.FIELD_OBJECT_ID);
			assertTrue("grant testUser1 Read/GROUP on the shared group", ioContext.getMemberUtil().member(u2, sharedDir, u1, readGroup, true));
			assertTrue("grant testUser1 Read/DATA on the shared group", ioContext.getMemberUtil().member(u2, sharedDir, u1, readData, true));
			CacheUtil.clearCache();

			/// Control 1: the grant lets testUser1 read the config-less sibling in the shared group.
			BaseRecord sibling = PbStyleUtil.readStyle(u1, siblingOid, orgOf(u1));
			assertNotNull("The group grant must let testUser1 read a config-less style in the shared group", sibling);
			assertNull("A style with no sdConfig has no style default", PbStyleUtil.readSdConfigModels(u1, sibling));

			/// Control 2: the owner reads the config-bearing style AND its config through the same query shapes.
			BaseRecord asOwner = PbStyleUtil.readStyle(u2, styleOid, orgId);
			assertNotNull("The owner must read its own style", asOwner);
			BaseRecord cfgAsOwner = PbStyleUtil.readSdConfigModels(u2, asOwner);
			assertNotNull("The owner must read its own sdConfig through readSdConfigModels", cfgAsOwner);
			assertEquals(gate, cfgAsOwner.get(OlioFieldNames.FIELD_SD_MODEL));

			/// The measured behaviour: the foreign-field READ pattern denies the whole style, not just the config.
			BaseRecord styleAsU1 = PbStyleUtil.readStyle(u1, styleOid, orgOf(u1));
			assertNull("PBAC denies testUser1 the whole style read when its foreign sdConfig is unreadable"
				+ " (the config-less sibling in the same group IS readable); got: " + styleAsU1, styleAsU1);

			/// ...so the facade reports it exactly as an absent style (no disclosure that a style exists).
			try {
				PbServiceFacade.listModelsForTask(u1, "location", "SWARM", server, styleOid);
				fail("A style whose sdConfig the caller cannot read is unreadable as a whole and must be a 404");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
				assertEquals("Style not found", e.getMessage());
			}

			/// The non-owner's "no readable style default" path, through the sibling: location has no per-task
			/// entry and there is no config, so the deployment default is the first and only candidate.
			Map<String, Object> dto = PbServiceFacade.listModelsForTask(u1, "location", "SWARM", server, siblingOid);
			Map<String, Object> res = map(dto.get("resolution"));
			assertEquals(ModelResolver.Reason.OK.name(), res.get("reason"));
			assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT.name(), res.get("step"));
			assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), res.get("model"));
			assertEquals("An absent style default is not a substitution", Boolean.FALSE, res.get("degraded"));
			List<Map<String, Object>> cands = list(res.get("candidates"));
			assertEquals("Only the deployment default was tried: " + cands, 1, cands.size());
			assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT.name(), cands.get(0).get("step"));

			/// And the owner, through the same facade call on the config-bearing style, DOES get the style
			/// default - so the only difference between 404 and STYLE_DEFAULT is the principal.
			Map<String, Object> ownerDto = PbServiceFacade.listModelsForTask(u2, "location", "SWARM", server, styleOid);
			Map<String, Object> ownerRes = map(ownerDto.get("resolution"));
			assertEquals(ModelResolver.Step.STYLE_DEFAULT.name(), ownerRes.get("step"));
			assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), ownerRes.get("model"));
		}
		finally {
			/// Revoke the grants so a re-run starts from the same denied baseline, then drop the records.
			ioContext.getMemberUtil().member(u2, sharedDir, u1, readData, false);
			ioContext.getMemberUtil().member(u2, sharedDir, u1, readGroup, false);
			cleanup(u2, style2, styleNoCfg, cfg2);
		}
	}

	/**
	 * Gap (e) at the facade: a deployment whose {@code sd.server.apiType} is not SWARM gets a 200-shaped DTO
	 * with INVENTORY_UNAVAILABLE (no inventory source for that API). The reason names the supported API,
	 * not the deployment's configured one (security review: deployment config stays off the wire).
	 */
	@Test
	public void case14_facadeNonSwarmApiTypeIsInventoryUnavailable() {
		BaseRecord u = user();
		Map<String, Object> dto = PbServiceFacade.listModelsForTask(u, "portrait", "auto1111", server, null);
		Map<String, Object> invDto = map(dto.get("inventory"));
		String err = (String) invDto.get("error");
		assertNotNull("inventory.error must say why there is no inventory", err);
		assertTrue("…naming the supported API: " + err, err.contains("only available for SWARM"));
		assertFalse("…without echoing the configured API type (got: " + err + ")", err.contains("AUTO1111"));
		assertFalse("…without the server URL (got: " + err + ")", err.contains(server));
		assertEquals(0, ((Number) invDto.get("count")).intValue());
		assertTrue(list(dto.get("models")).isEmpty());
		Map<String, Object> res = map(dto.get("resolution"));
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), res.get("reason"));
		assertNull(res.get("model"));
		assertNull(res.get("step"));
		assertEquals(err, res.get("inventoryError"));
		assertTrue(list(res.get("candidates")).isEmpty());
	}

	/**
	 * Security review note 6: a style owned by another user is a 404 from the facade for a caller who cannot
	 * read it - indistinguishable from an absent style (case09), so the response discloses nothing.
	 */
	@Test
	public void case15_facadeAnotherUsersStyleIsA404() {
		BaseRecord u1 = user();
		BaseRecord u2 = secondUser();
		assertTrue("Two distinct principals are required", !u1.get(FieldNames.FIELD_ID).equals(u2.get(FieldNames.FIELD_ID)));
		String tag = shortId();
		BaseRecord style = createStyle(u1, "p22-style-private-" + tag, null, null);
		try {
			String styleOid = style.get(FieldNames.FIELD_OBJECT_ID);
			/// Precondition: the owner resolves through it fine, so the 404 below is about the principal, not the style.
			Map<String, Object> ownerDto = PbServiceFacade.listModelsForTask(u1, "portrait", "SWARM", server, styleOid);
			assertEquals("portrait", ownerDto.get("task"));
			assertNull("Precondition: testUser2 must not read testUser1's private style", PbStyleUtil.readStyle(u2, styleOid, orgOf(u2)));
			try {
				PbServiceFacade.listModelsForTask(u2, "portrait", "SWARM", server, styleOid);
				fail("Another user's unreadable style must be a 404");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
				assertEquals("Same message as an absent style: no disclosure", "Style not found", e.getMessage());
			}
		}
		finally {
			cleanup(u1, style);
		}
	}

	/**
	 * The one way a READABLE style can carry an sdConfig reference that resolves to nothing: the config was
	 * deleted after the style was saved (foreign fields are plain id columns, so the reference dangles).
	 * This is the facade's {@code cfg == null} branch for a style that DOES name a config - the branch gap (a)
	 * asked about - reached through the owner, since PBAC denial cannot reach it (case13). The owner first
	 * resolves at STYLE_DEFAULT (control), then after the delete must resolve at DEPLOYMENT_DEFAULT with the
	 * style still readable, no STYLE_DEFAULT candidate, no degradation and no exception.
	 */
	@Test
	public void case16_facadeStyleWhoseSdConfigWasDeletedResolvesAtDeploymentDefault() {
		String gate = gateModel();
		BaseRecord u = user();
		ModelInventory inv = liveInventory();
		String tag = shortId();
		BaseRecord cfg = createSdConfig(u, "p22-sdcfg-dangling-" + tag, gate, null);
		BaseRecord style = createStyle(u, "p22-style-dangling-" + tag, null, cfg);
		try {
			String styleOid = style.get(FieldNames.FIELD_OBJECT_ID);
			/// Control: with the config present the owner resolves at STYLE_DEFAULT.
			Map<String, Object> before = map(PbServiceFacade.listModelsForTask(u, "location", "SWARM", server, styleOid).get("resolution"));
			assertEquals("Control: STYLE_DEFAULT while the config exists", ModelResolver.Step.STYLE_DEFAULT.name(), before.get("step"));

			assertTrue("The owner must be able to delete its own olio.sd.config", ioContext.getAccessPoint().delete(u, cfg));
			CacheUtil.clearCache();

			BaseRecord readBack = PbStyleUtil.readStyle(u, styleOid, orgOf(u));
			assertNotNull("A style whose sdConfig was deleted must still be readable by its owner (got null)", readBack);
			assertNull("readSdConfigModels must be null for a dangling sdConfig reference, never an exception",
				PbStyleUtil.readSdConfigModels(u, readBack));

			Map<String, Object> dto = PbServiceFacade.listModelsForTask(u, "location", "SWARM", server, styleOid);
			Map<String, Object> res = map(dto.get("resolution"));
			assertEquals(ModelResolver.Reason.OK.name(), res.get("reason"));
			assertEquals("Step 3 is absent, not an error", ModelResolver.Step.DEPLOYMENT_DEFAULT.name(), res.get("step"));
			assertEquals(CheckpointNames.matchInstalled(inv.installedModels(), gate), res.get("model"));
			assertEquals(Boolean.FALSE, res.get("degraded"));
			assertNull(res.get("degradationCode"));
			List<Map<String, Object>> cands = list(res.get("candidates"));
			assertEquals("Only the deployment default was tried: " + cands, 1, cands.size());
			assertEquals(ModelResolver.Step.DEPLOYMENT_DEFAULT.name(), cands.get(0).get("step"));
		}
		finally {
			/// cfg is already gone on the happy path; cleanup logs and never asserts, so a second delete is harmless.
			cleanup(u, style, cfg);
		}
	}
}
