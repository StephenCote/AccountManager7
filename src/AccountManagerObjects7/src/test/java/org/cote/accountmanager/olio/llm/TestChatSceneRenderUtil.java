package org.cote.accountmanager.olio.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ConfigNotApplied;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.StrategySelector;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 P2-7 (design §24.1): the chat scene image through the shared illustration service.
 * Cases (a)-(d) and the offline 503 case run with NO renderer (the inventory for a null server is
 * "unavailable" without a network call, so the plan path is exercised deterministically); case (e)
 * renders for real and is gated on {@code test.swarm.server} answering - when it does not, it is a
 * visible SKIP, never a pass.
 */
public class TestChatSceneRenderUtil extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	private static final String USER_NAME = "chatSceneTestUser";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;

	private static final String SYS_OID = "sys-oid-mira";
	private static final String USR_OID = "usr-oid-theo";
	private static final String SCENE_DESC = "sharing tea at a harbor cafe";
	private static final String SETTING_DESC = "a fog-wrapped harbor at dusk";
	private static final String SYS_DESC = "a 30 year old woman with copper hair";
	private static final String USR_DESC = "a 34 year old man in a grey coat";
	private static final String CHAR_NEGATIVE = "extra limbs, deformed hands";
	private static final String LABEL = "Mira and Theo";
	private static final String CUSTOM_STYLE = "ink wash illustration";
	private static final String CUSTOM_STYLE_CLAUSE = "(" + CUSTOM_STYLE + ").";

	private BaseRecord testUser;

	@Before
	public void setUpChatScene() {
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		assertNotNull("test organization " + ORG_PATH, ctx);
		testUser = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), USER_NAME, ctx.getOrganizationId());
		assertNotNull(USER_NAME + " must be created", testUser);
		SwarmModelInventory.invalidateAll();
	}

	// ─────────────────────────────── (a) plan shape (no renderer) ───────────────────────────────

	@Test
	public void buildPlanShapeTextOnlyWithoutPortraits() throws Exception {
		BaseRecord cfg = sparseConfig(map("style", "custom", "customPrompt", CUSTOM_STYLE, "width", 768, "height", 512,
			"steps", 12, "negativePrompt", "blurry, watermark"));
		Chat.ScenePromptResult scene = scene(null, null, CHAR_NEGATIVE);

		RenderPlan plan = ChatSceneRenderUtil.buildPlan(cfg, scene, SYS_OID, USR_OID, null, null, null);
		assertNotNull(plan);
		assertEquals(RenderPlan.PURPOSE_SCENE, plan.getPurpose());

		/// strategy: no portraits means no referenced actors, TEXT_ONLY, exactly one BASE stage
		assertEquals(PbStrategyEnumType.TEXT_ONLY, plan.getStrategy().getName());
		assertEquals(StrategySelector.REASON_NO_REFERENCED_ACTORS, plan.getStrategy().getReason());
		assertEquals(Arrays.asList(RenderPlan.STAGE_BASE), plan.getStrategy().getStages());

		/// preset: STANDARD, no upscale; steps from the config because no capability family resolved offline
		assertEquals("STANDARD", plan.getPreset().getPreset());
		assertFalse("STANDARD preset must not plan an upscale", plan.getPreset().isUpscale());
		assertEquals(12, plan.getPreset().getSteps());

		/// canvas from the config's width/height (STANDARD scale 1.0, already on the 64 grid)
		assertEquals(768, plan.getCanvas().getWidth());
		assertEquals(512, plan.getCanvas().getHeight());

		/// two FOREGROUND actors, first names, positioned, not referenced
		List<RenderPlan.Actor> actors = plan.getActors();
		assertEquals(2, actors.size());
		RenderPlan.Actor sys = actors.get(0);
		RenderPlan.Actor usr = actors.get(1);
		assertEquals(SYS_OID, sys.getActorId());
		assertEquals("Mira", sys.getName());
		assertEquals(RenderPlan.ROLE_FOREGROUND, sys.getRole());
		assertTrue(sys.isForeground());
		assertEquals(SYS_DESC + " on the left", sys.getDescription());
		assertFalse("no portrait bytes -> not referenced", sys.isReferenced());
		assertEquals(USR_OID, usr.getActorId());
		assertEquals("Theo", usr.getName());
		assertEquals(RenderPlan.ROLE_FOREGROUND, usr.getRole());
		assertEquals(USR_DESC + " on the right", usr.getDescription());
		assertFalse("no portrait bytes -> not referenced", usr.isReferenced());

		/// the two-figure cue is a HIGH element
		assertEquals(1, plan.getElements().size());
		RenderPlan.Element cue = plan.getElements().get(0);
		assertEquals(ChatSceneRenderUtil.TWO_PEOPLE_CUE, cue.getName());
		assertEquals(RenderPlan.PRIORITY_HIGH, cue.getPriority());

		/// location = setting; intent = scene + ", " + setting
		assertNotNull(plan.getLocation());
		assertNull(plan.getLocation().getLocationId());
		assertEquals(SETTING_DESC, plan.getLocation().getDescription());
		assertNotNull(plan.getIntent());
		assertEquals(SCENE_DESC + ", " + SETTING_DESC, plan.getIntent().getPromptOverride());
		assertFalse(plan.getIntent().isRaw());

		/// style = the sdConfig style clause; exclusions = the character's negative
		assertNotNull(plan.getStyle());
		assertEquals(CUSTOM_STYLE_CLAUSE, plan.getStyle().getDescription());
		assertEquals(CHAR_NEGATIVE, plan.getStyle().getExclusions());

		/// the config snapshot carries what was passed in
		assertEquals(768, ((Number) plan.getConfig().get("width")).intValue());
		assertEquals(512, ((Number) plan.getConfig().get("height")).intValue());
		assertEquals("custom", plan.getConfig().get("style"));

		/// compiled BASE prompt names everything; negative carries the char negative and the config negative
		assertEquals(1, plan.getCompiled().size());
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		assertNotNull("BASE stage must compile", base);
		String prompt = base.getPrompt();
		logger.info("compiled BASE prompt: " + prompt);
		logger.info("compiled BASE negative: " + base.getNegative());
		assertTrue(prompt, prompt.contains(SCENE_DESC));
		assertTrue(prompt, prompt.contains(SETTING_DESC));
		assertTrue(prompt, prompt.contains("Mira, " + SYS_DESC + " on the left"));
		assertTrue(prompt, prompt.contains("Theo, " + USR_DESC + " on the right"));
		assertTrue(prompt, prompt.contains(ChatSceneRenderUtil.TWO_PEOPLE_CUE));
		assertTrue(prompt, prompt.contains(CUSTOM_STYLE_CLAUSE));
		assertTrue(base.getNegative(), base.getNegative().contains("extra limbs"));
		assertTrue(base.getNegative(), base.getNegative().contains("deformed hands"));
		assertTrue(base.getNegative(), base.getNegative().contains("blurry"));
		assertTrue(base.getNegative(), base.getNegative().contains("watermark"));

		/// offline, the inventory is unavailable: the BASE pick is unresolved for that reason and reported as a degradation
		RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
		assertNotNull(pick);
		assertFalse("no server -> no inventory -> unresolved pick", pick.isResolved());
		assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), pick.getReason());
		assertEquals(1, countCode(plan, RenderPlan.Degradation.MODEL_SUBSTITUTED));
		assertEquals("no portraits -> no ACTOR_TEXT_ONLY", 0, countCode(plan, RenderPlan.Degradation.ACTOR_TEXT_ONLY));

		/// the plan round-trips and hashes
		assertNotNull(plan.contentHash());
		assertFalse(plan.contentHash().isBlank());
		RenderPlan back = RenderPlan.fromJson(plan.toJson());
		assertEquals(plan.contentHash(), back.contentHash());
	}

	@Test
	public void buildPlanMarksReferencedOnlyWhenPortraitBytesSupplied() throws Exception {
		BaseRecord cfg = sparseConfig(map("style", "custom", "customPrompt", CUSTOM_STYLE));
		byte[] portrait = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
		Chat.ScenePromptResult scene = scene(portrait, new byte[0], CHAR_NEGATIVE);

		RenderPlan plan = ChatSceneRenderUtil.buildPlan(cfg, scene, SYS_OID, USR_OID, null, null, null);
		assertTrue("sys portrait bytes supplied -> referenced", plan.getActors().get(0).isReferenced());
		assertFalse("empty usr portrait bytes -> not referenced", plan.getActors().get(1).isReferenced());

		/// Phase 2: referenced actors are still TEXT_ONLY, reported per actor, never silently dropped
		assertEquals(PbStrategyEnumType.TEXT_ONLY, plan.getStrategy().getName());
		assertEquals(StrategySelector.REASON_UNKNOWN_FAMILY, plan.getStrategy().getReason());
		assertEquals(Arrays.asList(RenderPlan.STAGE_BASE), plan.getStrategy().getStages());
		List<RenderPlan.Degradation> textOnly = ofCode(plan, RenderPlan.Degradation.ACTOR_TEXT_ONLY);
		assertEquals("exactly one ACTOR_TEXT_ONLY, for the portrait-bearing actor", 1, textOnly.size());
		assertEquals(SYS_OID, textOnly.get(0).getActorId());
		assertEquals(RenderPlan.STAGE_BASE, textOnly.get(0).getStage());
		assertTrue(textOnly.get(0).getMessage(), textOnly.get(0).getMessage().contains("Mira"));
	}

	@Test
	public void buildPlanDefaultsWhenSceneAndSettingAreBlank() throws Exception {
		BaseRecord cfg = sparseConfig(map("style", "custom", "customPrompt", CUSTOM_STYLE));
		Chat.ScenePromptResult scene = scene(null, null, null);
		scene.sceneDesc = "   ";
		scene.settingDesc = "";
		scene.sysFirstName = null;
		scene.usrCharDesc = null;

		RenderPlan plan = ChatSceneRenderUtil.buildPlan(cfg, scene, SYS_OID, USR_OID, null, null, null);
		assertEquals("two people facing each other", plan.getIntent().getPromptOverride());
		assertNull("blank setting -> no location text", plan.getLocation().getDescription());
		assertNull("blank first name -> no actor name", plan.getActors().get(0).getName());
		assertEquals("missing description -> position only", "on the right", plan.getActors().get(1).getDescription());
		assertNull("no character negative -> no exclusions", plan.getStyle().getExclusions());
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		assertTrue(base.getPrompt(), base.getPrompt().startsWith("two people facing each other"));
	}

	@Test
	public void buildPlanWithNoConfigUsesTheProfessionalPhotographClauseAndDefaultCanvas() throws Exception {
		Chat.ScenePromptResult scene = scene(null, null, null);
		RenderPlan plan = ChatSceneRenderUtil.buildPlan(null, scene, SYS_OID, USR_OID, null, null, null);
		assertEquals("(professional photograph).", plan.getStyle().getDescription());
		assertTrue(plan.getConfig().isEmpty());
		assertEquals(1024, plan.getCanvas().getWidth());
		assertEquals(1024, plan.getCanvas().getHeight());
		assertEquals(20, plan.getPreset().getSteps());
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		assertTrue(base.getPrompt(), base.getPrompt().contains("(professional photograph)."));
	}

	// ─────────────────────────────── (b) configNotApplied ───────────────────────────────

	@Test
	public void configNotAppliedReportsEachCodeOnceAndNeverStyleClause() {
		CompiledPrompt withNegative = new CompiledPrompt("p", "blurry", 1);
		CompiledPrompt noNegative = new CompiledPrompt("p", null, 1);
		CompiledPrompt blankNegative = new CompiledPrompt("p", "  ", 1);

		/// HIRES
		List<String> out = ChatSceneRenderUtil.configNotApplied(map("hires", Boolean.TRUE), withNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).startsWith(ConfigNotApplied.HIRES + ": "));
		out = ChatSceneRenderUtil.configNotApplied(map("hires", Boolean.FALSE), withNegative, "TEXT_ONLY");
		assertTrue("hires=false is not reported: " + out, out.isEmpty());

		/// DEFAULT_NEGATIVE (null and blank)
		out = ChatSceneRenderUtil.configNotApplied(map(), noNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).startsWith(ConfigNotApplied.DEFAULT_NEGATIVE + ": "));
		out = ChatSceneRenderUtil.configNotApplied(map(), blankNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).startsWith(ConfigNotApplied.DEFAULT_NEGATIVE + ": "));

		/// COMPOSITE_MODE (meaningful string only)
		out = ChatSceneRenderUtil.configNotApplied(map("compositeMode", "flux2"), withNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).startsWith(ConfigNotApplied.COMPOSITE_MODE + ": "));
		assertTrue(out.get(0), out.get(0).contains("'flux2'"));
		assertTrue(out.get(0), out.get(0).contains("TEXT_ONLY"));
		out = ChatSceneRenderUtil.configNotApplied(map("compositeMode", ""), withNegative, "TEXT_ONLY");
		assertTrue("blank compositeMode is not reported: " + out, out.isEmpty());

		/// SKIP_LANDSCAPE (presence, either value)
		out = ChatSceneRenderUtil.configNotApplied(map("skipLandscape", Boolean.FALSE), withNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).startsWith(ConfigNotApplied.SKIP_LANDSCAPE + ": "));
		assertTrue(out.get(0), out.get(0).contains("skipLandscape=false"));
		out = ChatSceneRenderUtil.configNotApplied(map("skipLandscape", Boolean.TRUE), withNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).contains("skipLandscape=true"));

		/// SCENE_CREATIVITY (numeric only)
		out = ChatSceneRenderUtil.configNotApplied(map("sceneCreativity", 0.65d), withNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.get(0), out.get(0).startsWith(ConfigNotApplied.SCENE_CREATIVITY + ": "));
		assertTrue(out.get(0), out.get(0).contains("0.65"));
		out = ChatSceneRenderUtil.configNotApplied(map("sceneCreativity", "0.65"), withNegative, "TEXT_ONLY");
		assertTrue("a non-numeric sceneCreativity is not reported: " + out, out.isEmpty());

		/// empty
		out = ChatSceneRenderUtil.configNotApplied(map(), withNegative, "TEXT_ONLY");
		assertTrue("nothing to report: " + out, out.isEmpty());

		/// all five at once, each exactly once, in order, and never STYLE_CLAUSE
		out = ChatSceneRenderUtil.configNotApplied(map("hires", Boolean.TRUE, "compositeMode", "kontext", "skipLandscape", Boolean.TRUE,
			"sceneCreativity", 0.4d, "style", "photograph"), noNegative, "TEXT_ONLY");
		assertEquals(out.toString(), 5, out.size());
		assertTrue(out.get(0).startsWith(ConfigNotApplied.HIRES + ": "));
		assertTrue(out.get(1).startsWith(ConfigNotApplied.DEFAULT_NEGATIVE + ": "));
		assertTrue(out.get(2).startsWith(ConfigNotApplied.COMPOSITE_MODE + ": "));
		assertTrue(out.get(3).startsWith(ConfigNotApplied.SKIP_LANDSCAPE + ": "));
		assertTrue(out.get(4).startsWith(ConfigNotApplied.SCENE_CREATIVITY + ": "));
		for(String s : out) {
			assertFalse("STYLE_CLAUSE must never be reported on the chat path: " + s, s.startsWith(ConfigNotApplied.STYLE_CLAUSE));
		}
	}

	/// Through the plan path: a schema-default config (hires=true, compositeMode=flux2, skipLandscape=false,
	/// sceneCreativity=0.65, no negativePrompt) reports the four v1 knobs plus DEFAULT_NEGATIVE, and the style
	/// clause is IN the compiled prompt, so STYLE_CLAUSE is never reported.
	@Test
	public void configNotAppliedFromADefaultConfigThroughThePlan() throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.setValue("style", "photograph");
		assertEquals(Boolean.TRUE, cfg.get("hires"));
		assertEquals("flux2", cfg.get("compositeMode"));
		Chat.ScenePromptResult scene = scene(null, null, null);

		RenderPlan plan = ChatSceneRenderUtil.buildPlan(cfg, scene, SYS_OID, USR_OID, null, null, null);
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		String clause = plan.getStyle().getDescription();
		assertTrue(clause, clause.startsWith("((Photograph) taken with a ("));
		assertFalse("the per-style defaults must be filled, never the literal 'null': " + clause, clause.contains("null"));
		assertTrue(base.getPrompt(), base.getPrompt().contains(clause));

		List<String> out = ChatSceneRenderUtil.configNotApplied(plan.getConfig(), base, plan.getStrategy().getName().name());
		logger.info("configNotApplied: " + out);
		assertEquals(out.toString(), 5, out.size());
		assertEquals(1, countPrefix(out, ConfigNotApplied.HIRES));
		assertEquals(1, countPrefix(out, ConfigNotApplied.DEFAULT_NEGATIVE));
		assertEquals(1, countPrefix(out, ConfigNotApplied.COMPOSITE_MODE));
		assertEquals(1, countPrefix(out, ConfigNotApplied.SKIP_LANDSCAPE));
		assertEquals(1, countPrefix(out, ConfigNotApplied.SCENE_CREATIVITY));
		assertEquals(0, countPrefix(out, ConfigNotApplied.STYLE_CLAUSE));
	}

	// ─────────────────────────────── (c) RenderFailure statuses ───────────────────────────────

	@Test
	public void renderRefusesWithTheDocumentedStatuses() throws Exception {
		BaseRecord cfg = sparseConfig(map("style", "custom", "customPrompt", CUSTOM_STYLE));
		Chat.ScenePromptResult scene = scene(null, null, CHAR_NEGATIVE);
		SDUtil offline = new SDUtil(SDAPIEnumType.SWARM, null);

		ChatSceneRenderUtil.RenderFailure f = expectFailure(() -> ChatSceneRenderUtil.render(null, offline, cfg, scene, SYS_OID, USR_OID, null));
		assertEquals(401, f.getStatus());
		assertEquals("No authenticated principal", f.getMessage());

		f = expectFailure(() -> ChatSceneRenderUtil.render(testUser, null, cfg, scene, SYS_OID, USR_OID, null));
		assertEquals(500, f.getStatus());
		assertEquals("No renderer transport", f.getMessage());

		f = expectFailure(() -> ChatSceneRenderUtil.render(testUser, offline, cfg, null, SYS_OID, USR_OID, null));
		assertEquals(500, f.getStatus());
		assertEquals("No scene prompt was produced for the chat", f.getMessage());

		f = expectFailure(() -> ChatSceneRenderUtil.buildPlan(cfg, null, SYS_OID, USR_OID, null, null, null));
		assertEquals(500, f.getStatus());
	}

	/// No SD server configured on the transport: the inventory is unavailable, so the BASE pick is unresolved
	/// and render refuses with 503 BEFORE any renderer call, naming the label and never the server.
	@Test
	public void renderRefuses503WhenTheInventoryIsUnavailable() throws Exception {
		BaseRecord cfg = sparseConfig(map("style", "custom", "customPrompt", CUSTOM_STYLE));
		Chat.ScenePromptResult scene = scene(null, null, CHAR_NEGATIVE);
		SDUtil offline = new SDUtil(SDAPIEnumType.SWARM, null);

		ChatSceneRenderUtil.RenderFailure f = expectFailure(() -> ChatSceneRenderUtil.render(testUser, offline, cfg, scene, SYS_OID, USR_OID, null));
		assertEquals(503, f.getStatus());
		assertTrue(f.getMessage(), f.getMessage().startsWith("Renderer inventory unavailable; cannot verify a model for " + LABEL + " before rendering"));
		assertTrue("the MODEL_SUBSTITUTED degradation rides on the message: " + f.getMessage(),
			f.getMessage().contains("Model for LOCATION is unresolved: INVENTORY_UNAVAILABLE"));

		/// a blank label falls back to "chat scene"
		scene.label = "  ";
		f = expectFailure(() -> ChatSceneRenderUtil.render(testUser, offline, cfg, scene, SYS_OID, USR_OID, null));
		assertEquals(503, f.getStatus());
		assertTrue(f.getMessage(), f.getMessage().contains("cannot verify a model for chat scene before rendering"));
	}

	// ─────────────────────────────── (d) the passed-in config is not mutated ───────────────────────────────

	@Test
	public void buildPlanDoesNotMutateTheSdConfig() throws Exception {
		BaseRecord cfg = sparseConfig(map("style", "photograph", "width", 640, "height", 640, "model", "explicit-client-model.safetensors"));
		int fieldCount = cfg.getFields().size();
		String before = cfg.toFullString();
		assertFalse("fixture: the photograph detail fields start unset", cfg.hasField("stillCamera"));

		Chat.ScenePromptResult scene = scene(null, null, CHAR_NEGATIVE);
		RenderPlan plan = ChatSceneRenderUtil.buildPlan(cfg, scene, SYS_OID, USR_OID, null, null, null);

		/// the copy got the per-style defaults and the deployment-default model; the original got nothing
		assertTrue(plan.getStyle().getDescription(), plan.getStyle().getDescription().startsWith("((Photograph) taken with a ("));
		assertEquals("no field was materialised on the original", fieldCount, cfg.getFields().size());
		assertFalse("fillStyleDefaults must run on the copy only", cfg.hasField("stillCamera"));
		assertEquals("the model policy writes into the copy only", "explicit-client-model.safetensors", cfg.get("model"));
		assertEquals(before, cfg.toFullString());

		/// the snapshot was taken from the config as passed (before the policy wrote the deployment default)
		assertEquals("explicit-client-model.safetensors", plan.getConfig().get("model"));
	}

	// ─────────────────────────────── (e) LIVE render (SD-gated) ───────────────────────────────

	/**
	 * Renders one BASE stage through the real Swarm transport and verifies the persisted record as the
	 * requesting (non-admin) user through PBAC. Gated on {@code test.swarm.server}: when it does not answer
	 * this is a visible SKIP and is NOT a pass.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void renderPersistsTheSceneUnderTheGalleryAsTheUser() throws Exception {
		String server = swarmServer();
		BaseRecord cfg = SdTestGate.requireRenderConfig(testProperties, server, "chat scene v3 render");
		cfg.setValue("style", "custom");
		cfg.setValue("customPrompt", CUSTOM_STYLE);
		cfg.setValue("width", 512);
		cfg.setValue("height", 512);
		cfg.setValue("steps", 8);
		cfg.setValue("hires", false);
		Chat.ScenePromptResult scene = scene(null, null, CHAR_NEGATIVE);

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		sdu.setImageAccessUser(testUser);

		long start = System.currentTimeMillis();
		ChatSceneRenderUtil.ChatSceneRender render = ChatSceneRenderUtil.render(testUser, sdu, cfg, scene, SYS_OID, USR_OID, null);
		logger.info("chat scene v3 live render took " + (System.currentTimeMillis() - start) + " ms (recorded, not asserted)");
		assertNotNull(render);
		assertNotNull(render.getData());
		assertTrue(render.getPick().isResolved());

		/// describe() carries every key the transport returns
		Map<String, Object> d = render.describe();
		for(String k : new String[] { "renderPath", "strategy", "compiledPrompt", "compiledNegative", "model", "canvas", "planContentHash",
				"seed", "durationMs", "degradations", ConfigNotApplied.KEY }) {
			assertTrue("describe() must carry " + k + ": " + d.keySet(), d.containsKey(k));
		}
		assertEquals(ChatSceneRenderUtil.RENDER_PATH_V3, d.get("renderPath"));
		assertEquals("TEXT_ONLY", d.get("strategy"));
		assertEquals(render.getPick().getModel(), d.get("model"));
		assertEquals(render.getPlan().contentHash(), d.get("planContentHash"));

		/// the record is readable by the user through AccessPoint, under ~/Gallery/Scenes/<label>
		String oid = render.getData().get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("the persisted record carries an objectId", oid);
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		/// planMost(true) filters `attributes` out (RecordUtil.getMostRequestFields); project them explicitly
		q.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID
		});
		q.setCache(false);
		BaseRecord full = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("the user must be able to read the scene record back", full);

		BaseRecord gallery = IOSystem.getActiveContext().getPathUtil().findPath(testUser, ModelNames.MODEL_GROUP,
			ChatSceneRenderUtil.SCENE_GALLERY_PREFIX + LABEL, "DATA", orgId);
		assertNotNull("the gallery folder exists", gallery);
		long galleryId = gallery.get(FieldNames.FIELD_ID);
		long recordGroupId = full.get(FieldNames.FIELD_GROUP_ID);
		assertEquals("the record lives in ~/Gallery/Scenes/<label>", galleryId, recordGroupId);
		assertEquals("image/png", full.get(FieldNames.FIELD_CONTENT_TYPE));
		String name = full.get(FieldNames.FIELD_NAME);
		assertTrue(name, name.startsWith(LABEL + " - scene - 1 - "));

		/// attributes
		Object seedAttr = AttributeUtil.getAttributeValue(full, "seed", (Object) null);
		assertNotNull("seed attribute", seedAttr);
		long seedl = (render.getSeed() != null ? render.getSeed().longValue() : -1L);
		assertEquals(String.valueOf(seedl), String.valueOf(seedAttr));
		assertEquals("scene", AttributeUtil.getAttributeValue(full, "imageType", (String) null));
		assertEquals(SYS_OID, AttributeUtil.getAttributeValue(full, "systemCharacter", (String) null));
		assertEquals(USR_OID, AttributeUtil.getAttributeValue(full, "userCharacter", (String) null));
		String planJson = AttributeUtil.getAttributeValue(full, "renderPlan", (String) null);
		assertNotNull("renderPlan attribute", planJson);
		assertEquals(render.getPlan().contentHash(), RenderPlan.fromJson(planJson).contentHash());
		if(render.getRequestJson() != null) {
			assertNotNull("stageRequest attribute", AttributeUtil.getAttributeValue(full, "stageRequest", (String) null));
		}

		/// bytes are a PNG
		byte[] bytes = ByteModelUtil.getValue(full);
		assertNotNull(bytes);
		assertTrue("PNG bytes expected, got " + bytes.length, bytes.length > PNG_MAGIC.length);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File out = new File(System.getProperty("java.io.tmpdir"), "p27-chat-scene-v3-" + oid + ".png");
		FileUtil.emitFile(out.getAbsolutePath(), bytes);
		logger.info("chat scene v3 image written for inspection: " + out.getAbsolutePath());
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private String swarmServer() {
		String s = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", s);
		return s;
	}

	private static Chat.ScenePromptResult scene(byte[] sysPortrait, byte[] usrPortrait, String charNegative) {
		Chat.ScenePromptResult s = new Chat.ScenePromptResult();
		s.label = LABEL;
		s.sysFirstName = "Mira";
		s.usrFirstName = "Theo";
		s.sysCharDesc = SYS_DESC;
		s.usrCharDesc = USR_DESC;
		s.sceneDesc = SCENE_DESC;
		s.settingDesc = SETTING_DESC;
		s.charNegativePrompt = charNegative;
		s.sysPortraitBytes = sysPortrait;
		s.usrPortraitBytes = usrPortrait;
		s.prompt = SCENE_DESC;
		return s;
	}

	/// A config carrying ONLY the named fields (the bare newInstance materialises every schema default).
	private static BaseRecord sparseConfig(Map<String, Object> values) throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, values.keySet().toArray(new String[0]));
		for(Map.Entry<String, Object> e : values.entrySet()) {
			cfg.setValue(e.getKey(), e.getValue());
		}
		return cfg;
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private static List<RenderPlan.Degradation> ofCode(RenderPlan plan, String code) {
		List<RenderPlan.Degradation> out = new ArrayList<>();
		for(RenderPlan.Degradation d : plan.getDegradations()) {
			if(code.equals(d.getCode())) {
				out.add(d);
			}
		}
		return out;
	}

	private static int countCode(RenderPlan plan, String code) {
		return ofCode(plan, code).size();
	}

	private static int countPrefix(List<String> list, String code) {
		int n = 0;
		for(String s : list) {
			if(s.startsWith(code + ": ")) {
				n++;
			}
		}
		return n;
	}

	private interface Thrower {
		void run() throws Exception;
	}

	private static ChatSceneRenderUtil.RenderFailure expectFailure(Thrower t) throws Exception {
		try {
			t.run();
		}
		catch(ChatSceneRenderUtil.RenderFailure f) {
			return f;
		}
		fail("expected a RenderFailure");
		return null;
	}
}
