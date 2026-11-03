package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ConfigNotApplied;
import org.cote.accountmanager.olio.illustration.FamilyRequest;
import org.cote.accountmanager.olio.illustration.FixtureGraphClient;
import org.cote.accountmanager.olio.illustration.MaskImage;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PlannedRender;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.RenderBackend;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil.SceneGraph;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.Flux2Defaults;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P9-7 (§6.C 239 c): a zero-reference BASE (TEXT_ONLY - a ChapBook cover, a book cover with a
 * portrait-less cast) goes out as a plain T2I request. {@code PbNodeExecutor.renderPlanned} builds that
 * request from the config snapshot's {@code cfg} / {@code sampler} / {@code scheduler}, which are the
 * SDXL-tuned values; a FLUX.2 pick must render with the FLUX.2 knobs instead. Offline through the
 * package-private {@link RenderBackend} seam, the way {@code TestPbRenderStagesOffline} does: the scripted
 * adapter records what the executor sends, the {@link SDUtil} points at a closed port and nothing opens
 * a connection. The preset's step count is built the way {@code PbRenderPlanUtil.scaffold} builds it -
 * {@code RendererCapabilities.forModel(pick).recommendedSteps(mode)} - so "the FLUX.2 steps" here are the
 * family's own count, carried by the preset (steps are preset-governed on the plan path, D6).
 * <ol>
 * <li>{@link #flux2PickTakesTheConfiguredFlux2Knobs}: FLUX.2 pick, config carrying {@code flux2Cfg 2.5}
 *     next to the SDXL {@code cfg 7.0 / dpmpp_2m / karras} (and a {@code flux2Steps} that must not be
 *     read) -> the one request is a plain T2I (no graph) at cfg 2.5, the FLUX.2 family's recommended
 *     steps, the FLUX.2 sampler and scheduler, the FLUX.2 model.</li>
 * <li>{@link #flux2PickFallsBackToTheResourceCfg}: FLUX.2 pick, config without {@code flux2Cfg} ->
 *     {@code flux2Defaults.json}'s cfg, sampler and scheduler.</li>
 * <li>{@link #sdxlPickIsUnchanged}: SDXL pick ({@code juggernautXL_ragnarokBy}), the same config -> the
 *     request still carries cfg 7.0 / dpmpp_2m / karras and the SDXL family's recommended steps.</li>
 * <li>{@link #helperChangesOnlyTheThreeKnobs}: the helper itself ({@code FamilyRequest.apply}, P10-A 5), no
 *     backend - steps, prompt, model, canvas, seed, images, hires and config survive; a null or zeroed config
 *     means the resource cfg; null capabilities leave the request as it is.</li>
 * </ol>
 * P10-A 5 (§6.C 239 c family, 245 d): the mapping is {@code FamilyRequest.apply}, applied per stage from the
 * stage's own pick, and every family's cfg is clamped into its capabilities.json {@code cfgRange}:
 * <ol start="7">
 * <li>{@link #helperTakesTheConfiguredFlux2SamplerAndScheduler}: the config's {@code flux2Sampler} /
 *     {@code flux2Scheduler} win over the resource; unset, blank or the literal "null" mean the resource; an
 *     SDXL pick never reads them.</li>
 * <li>{@link #helperClampsCfgIntoTheFamilyRangeOnce}: {@code flux2Cfg 7} on a FLUX.2 pick goes out at the
 *     family's cfgMax with one CFG_CLAMPED entry naming the stage, the value, the range and what was sent;
 *     a second application changes nothing and reports nothing new.</li>
 * <li>{@link #helperClampsTheSdxlCfgToo}: {@code cfg 12} on an SDXL pick goes out at the SDXL cfgMax.</li>
 * <li>{@link #helperLeavesADegenerateRangeAlone}: a family whose range is {@code [x, x]} (*kontext*) clamps
 *     nothing.</li>
 * <li>{@link #outOfRangeCfgIsClampedOncePerRenderAcrossVariants}: two variants through
 *     {@code renderPlanned} both go out clamped and the attempt reports CFG_CLAMPED once.</li>
 * <li>{@link #sdxlPickOutOfRangeCfgIsClampedOnThePlanPath}: the SDXL clamp on the plan path itself.</li>
 * </ol>
 * The graph path sends the same three knobs (§6.C 245 g): {@code WorkflowCompiler.compile} puts the request's
 * sampler, scheduler and cfg into the generator params Swarm builds the graph from, so a referenced BASE and an
 * ACTOR_REGION pass on a FLUX.2 pick went out at the SDXL values too. {@code TestPbRenderStagesOffline}'s plans
 * already carry the FLUX.2 values in {@code cfg} / {@code sampler} / {@code scheduler}, so they cannot see it;
 * these two use the SDXL config and read the generator params the fixture client recorded:
 * <ol start="5">
 * <li>{@link #flux2PickOnTheGraphPathTakesTheFlux2Knobs}: DIRECT_REFERENCE, one portrait on BASE, FLUX.2 pick,
 *     the SDXL config with {@code flux2Cfg 2.5} -> the one request is a graph whose request and generator params
 *     carry cfg 2.5 and the FLUX.2 sampler / scheduler; the portrait still travels.</li>
 * <li>{@link #flux2PassOnTheGraphPathTakesTheFlux2KnobsToo}: SEQUENTIAL_INPAINT, FLUX.2 BASE and INPAINT picks ->
 *     both the BASE graph and the inpaint pass go out at the FLUX.2 knobs.</li>
 * </ol>
 */
public class TestPbFlux2TextOnlyOffline extends BaseTest {

	private static final String FLUX2_MODEL = "flux2Klein_9b";
	private static final String SDXL_MODEL = "juggernautXL_ragnarokBy";
	private static final String OFFLINE_SERVER = "http://127.0.0.1:9";
	private static final String PROMPT = "a basalt headland above a grey sea at dusk, title page";
	private static final String NEGATIVE = "blurry, text";
	private static final int W = 1024;
	private static final int H = 768;
	private static final double SDXL_CFG = 7.0;
	private static final String SDXL_SAMPLER = "dpmpp_2m";
	private static final String SDXL_SCHEDULER = "karras";
	private static final double BOOK_FLUX2_CFG = 2.5;
	/// a config-tier step count that the plan path must not send (steps are preset-governed, D6)
	private static final int BOOK_FLUX2_STEPS = 6;
	/// config-tier FLUX.2 sampler / scheduler (olio.sd.config flux2Sampler / flux2Scheduler, P10-A 5)
	private static final String BOOK_FLUX2_SAMPLER = "res_multistep";
	private static final String BOOK_FLUX2_SCHEDULER = "beta";
	/// the disabled *kontext* family: cfgRange [1, 1], the degenerate case the clamp must leave alone
	private static final String KONTEXT_MODEL = "flux1Kontext_dev";
	private static final double EPS = 0.0001;
	private static final String NO_QA_NOTE = "offline test: no QA provider";
	/// the graph-path cases: two fixture actors with solid-colour portraits, as in TestPbRenderStagesOffline
	private static final String ACTOR_A = "actorA";
	private static final String ACTOR_B = "actorB";
	private static final String PASS_PROMPT = "Tobin, a weathered sailor in an oilskin coat";
	private static final int PORTRAIT_W = 96;
	private static final int PORTRAIT_H = 128;

	@Before
	public void offlineSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void flux2PickTakesTheConfiguredFlux2Knobs() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97flux");
			Map<String, Object> cfg = sdxlConfig();
			cfg.put("flux2Cfg", BOOK_FLUX2_CFG);
			cfg.put("flux2Steps", BOOK_FLUX2_STEPS);
			RenderPlan plan = plan(FLUX2_MODEL, cfg);
			int flux2Steps = familySteps(FLUX2_MODEL);
			assertEquals("the preset carries the FLUX.2 family's recommended steps", flux2Steps, plan.getPreset().getSteps());
			assertNotEquals("precondition: the config-tier flux2Steps differs from the preset's", flux2Steps, BOOK_FLUX2_STEPS);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 4242, 10L));
			PlannedRender out = render(f, plan, backend(adapter));
			assertNotNull("render result", out);

			assertEquals("one request: the BASE T2I", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertFalse("a zero-reference BASE is a plain T2I request, not a graph", sent.isGraph());
			assertEquals("model", FLUX2_MODEL, sent.getModel());
			assertEquals("cfg is the configured flux2Cfg, not the SDXL cfg", BOOK_FLUX2_CFG, sent.getCfg(), EPS);
			assertEquals("steps are the FLUX.2 family's, via the preset", flux2Steps, sent.getSteps());
			assertEquals("sampler is the FLUX.2 sampler", Flux2Defaults.sampler(), sent.getSampler());
			assertEquals("scheduler is the FLUX.2 scheduler", Flux2Defaults.scheduler(), sent.getScheduler());
			assertEquals("prompt is untouched", PROMPT, sent.getPrompt().getPrompt());
			assertEquals("width", W, sent.getWidth());
			assertEquals("height", H, sent.getHeight());
			assertEquals("stage", RenderPlan.STAGE_BASE, sent.getStage());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void flux2PickFallsBackToTheResourceCfg() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97fdef");
			Map<String, Object> cfg = sdxlConfig();
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 4243, 10L));
			render(f, plan(FLUX2_MODEL, cfg), backend(adapter));

			assertEquals("one request", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertFalse("plain T2I", sent.isGraph());
			assertEquals("model", FLUX2_MODEL, sent.getModel());
			assertEquals("cfg is flux2Defaults.json's", Flux2Defaults.cfgScale(), sent.getCfg(), EPS);
			assertTrue("the resource cfg is in the FLUX.2 range, not the SDXL one", sent.getCfg() < 5.0);
			assertEquals("steps are the FLUX.2 family's, via the preset", familySteps(FLUX2_MODEL), sent.getSteps());
			assertEquals("sampler", Flux2Defaults.sampler(), sent.getSampler());
			assertEquals("scheduler", Flux2Defaults.scheduler(), sent.getScheduler());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void sdxlPickIsUnchanged() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97sdxl");
			Map<String, Object> cfg = sdxlConfig();
			/// the effective config carries the FLUX.2 knobs whatever the pick (applyFlux2Defaults fills them);
			/// an SDXL pick must not read them
			cfg.put("flux2Cfg", BOOK_FLUX2_CFG);
			cfg.put("flux2Steps", BOOK_FLUX2_STEPS);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 4244, 10L));
			render(f, plan(SDXL_MODEL, cfg), backend(adapter));

			assertEquals("one request", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertFalse("plain T2I", sent.isGraph());
			assertEquals("model", SDXL_MODEL, sent.getModel());
			assertEquals("cfg stays the SDXL cfg", SDXL_CFG, sent.getCfg(), EPS);
			assertEquals("steps stay the preset's (SDXL family)", familySteps(SDXL_MODEL), sent.getSteps());
			assertEquals("sampler stays", SDXL_SAMPLER, sent.getSampler());
			assertEquals("scheduler stays", SDXL_SCHEDULER, sent.getScheduler());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void helperChangesOnlyTheThreeKnobs() {
		Map<String, Object> cfg = sdxlConfig();
		cfg.put("flux2Cfg", BOOK_FLUX2_CFG);
		cfg.put("flux2Steps", BOOK_FLUX2_STEPS);
		int presetSteps = familySteps(FLUX2_MODEL);
		StageRequest req = sdxlRequest(cfg, presetSteps);
		List<String> notApplied = new ArrayList<>();

		StageRequest out = FamilyRequest.apply(req, flux2Caps(), cfg, notApplied);
		assertEquals("cfg", BOOK_FLUX2_CFG, out.getCfg(), EPS);
		assertEquals("sampler", Flux2Defaults.sampler(), out.getSampler());
		assertEquals("scheduler", Flux2Defaults.scheduler(), out.getScheduler());
		assertEquals("steps stay the request's (preset-governed); flux2Steps is not read", presetSteps, out.getSteps());
		assertEquals("model", FLUX2_MODEL, out.getModel());
		assertEquals("prompt", PROMPT, out.getPrompt().getPrompt());
		assertEquals("negative", NEGATIVE, out.getPrompt().getNegative());
		assertEquals("width", W, out.getWidth());
		assertEquals("height", H, out.getHeight());
		assertEquals("seed", 777, out.getSeed());
		assertEquals("images", 2, out.getImages());
		assertFalse("hires", out.isHires());
		assertEquals("config travels unchanged (the builder copies it)", cfg, out.getConfig());
		assertFalse("no graph", out.isGraph());
		assertTrue("2.5 is inside the FLUX.2 range: nothing reported", notApplied.isEmpty());

		StageRequest defaults = FamilyRequest.apply(req, flux2Caps(), null, notApplied);
		assertEquals("null config: resource cfg", Flux2Defaults.cfgScale(), defaults.getCfg(), EPS);
		assertEquals("null config: resource sampler", Flux2Defaults.sampler(), defaults.getSampler());
		assertEquals("null config: steps untouched", presetSteps, defaults.getSteps());

		Map<String, Object> zeroed = sdxlConfig();
		zeroed.put("flux2Cfg", 0.0);
		StageRequest z = FamilyRequest.apply(req, flux2Caps(), zeroed, notApplied);
		assertEquals("zero flux2Cfg is unset: resource cfg", Flux2Defaults.cfgScale(), z.getCfg(), EPS);
		assertTrue("nothing reported", notApplied.isEmpty());

		assertSame("null capabilities (no family matched) leave the request as it is", req, FamilyRequest.apply(req, (RendererCapabilities) null, cfg, notApplied));
		assertNull("a null request stays null", FamilyRequest.apply(null, flux2Caps(), cfg, notApplied));
	}

	@Test
	public void helperTakesTheConfiguredFlux2SamplerAndScheduler() {
		Map<String, Object> cfg = sdxlConfig();
		cfg.put("flux2Cfg", BOOK_FLUX2_CFG);
		cfg.put("flux2Sampler", BOOK_FLUX2_SAMPLER);
		cfg.put("flux2Scheduler", BOOK_FLUX2_SCHEDULER);
		StageRequest req = sdxlRequest(cfg, familySteps(FLUX2_MODEL));
		StageRequest out = FamilyRequest.apply(req, flux2Caps(), cfg, new ArrayList<>());
		assertEquals("the config's flux2Sampler wins over the resource", BOOK_FLUX2_SAMPLER, out.getSampler());
		assertEquals("the config's flux2Scheduler wins over the resource", BOOK_FLUX2_SCHEDULER, out.getScheduler());
		assertEquals("cfg", BOOK_FLUX2_CFG, out.getCfg(), EPS);
		assertNotEquals("precondition: the configured sampler is not the resource's", Flux2Defaults.sampler(), BOOK_FLUX2_SAMPLER);
		assertNotEquals("precondition: the configured scheduler is not the resource's", Flux2Defaults.scheduler(), BOOK_FLUX2_SCHEDULER);

		/// an unset, blank or LLM-literal value means the resource governs (nullable column: every pre-existing row is null)
		for(Object unset : new Object[] {null, "", "   ", "null"}) {
			Map<String, Object> c2 = sdxlConfig();
			c2.put("flux2Sampler", unset);
			c2.put("flux2Scheduler", unset);
			StageRequest o2 = FamilyRequest.apply(req, flux2Caps(), c2, new ArrayList<>());
			assertEquals("sampler for '" + unset + "' is the resource's", Flux2Defaults.sampler(), o2.getSampler());
			assertEquals("scheduler for '" + unset + "' is the resource's", Flux2Defaults.scheduler(), o2.getScheduler());
		}

		/// the SDXL pick never reads them
		StageRequest sdxl = FamilyRequest.apply(req, sdxlCaps(), cfg, new ArrayList<>());
		assertEquals("SDXL sampler stays", SDXL_SAMPLER, sdxl.getSampler());
		assertEquals("SDXL scheduler stays", SDXL_SCHEDULER, sdxl.getScheduler());
		assertEquals("SDXL cfg stays", SDXL_CFG, sdxl.getCfg(), EPS);
		assertSame("nothing to change on an SDXL pick inside its range: the same request", req, sdxl);
	}

	@Test
	public void helperClampsCfgIntoTheFamilyRangeOnce() {
		RendererCapabilities flux2 = flux2Caps();
		assertTrue("precondition: the FLUX.2 range is non-degenerate", flux2.getCfgMin() < flux2.getCfgMax());
		Map<String, Object> cfg = sdxlConfig();
		cfg.put("flux2Cfg", SDXL_CFG);
		StageRequest req = sdxlRequest(cfg, familySteps(FLUX2_MODEL));
		List<String> notApplied = new ArrayList<>();

		StageRequest out = FamilyRequest.apply(req, flux2, cfg, notApplied);
		assertEquals("a FLUX.2 flux2Cfg of 7 is clamped to the family's cfgMax", flux2.getCfgMax(), out.getCfg(), EPS);
		assertEquals("sampler", Flux2Defaults.sampler(), out.getSampler());
		assertEquals("one entry", 1, notApplied.size());
		String entry = notApplied.get(0);
		assertTrue("the entry is CFG_CLAMPED: " + entry, entry.startsWith(ConfigNotApplied.CFG_CLAMPED + ": "));
		assertTrue("it names the stage: " + entry, entry.contains(RenderPlan.STAGE_BASE + " cfg 7 "));
		assertTrue("it names the family and the range: " + entry, entry.contains(flux2.getPattern() + " family's range [1, 3.50]"));
		assertTrue("it says what was sent: " + entry, entry.endsWith("; sent 3.50"));

		/// idempotent: applying it to the mapped request changes nothing and reports nothing new
		StageRequest again = FamilyRequest.apply(out, flux2, cfg, notApplied);
		assertSame("nothing to change the second time: the same request", out, again);
		assertEquals("still one entry", 1, notApplied.size());
		/// and a list that already carries the code (another pass, another variant) takes no second entry
		FamilyRequest.apply(req, flux2, cfg, notApplied);
		assertEquals("deduplicated by code", 1, notApplied.size());
		assertEquals("a null list is tolerated", flux2.getCfgMax(), FamilyRequest.apply(req, flux2, cfg, null).getCfg(), EPS);

		/// below the range clamps up
		Map<String, Object> low = sdxlConfig();
		low.put("flux2Cfg", 0.5);
		List<String> lowNa = new ArrayList<>();
		assertEquals("0.5 is clamped to cfgMin", flux2.getCfgMin(), FamilyRequest.apply(req, flux2, low, lowNa).getCfg(), EPS);
		assertEquals("reported", 1, lowNa.size());
		assertTrue(lowNa.get(0), lowNa.get(0).contains(" cfg 0.50 is outside "));
	}

	@Test
	public void helperClampsTheSdxlCfgToo() {
		RendererCapabilities sdxl = sdxlCaps();
		assertTrue("precondition: the SDXL range is non-degenerate", sdxl.getCfgMin() < sdxl.getCfgMax());
		Map<String, Object> cfg = sdxlConfig();
		cfg.put("cfg", 12.0);
		StageRequest req = sdxlRequest(cfg, familySteps(SDXL_MODEL)).toBuilder().model(SDXL_MODEL).cfg(12.0).build();
		List<String> notApplied = new ArrayList<>();
		StageRequest out = FamilyRequest.apply(req, sdxl, cfg, notApplied);
		assertEquals("an SDXL cfg of 12 is clamped to the family's cfgMax", sdxl.getCfgMax(), out.getCfg(), EPS);
		assertEquals("sampler stays the config's", SDXL_SAMPLER, out.getSampler());
		assertEquals("scheduler stays the config's", SDXL_SCHEDULER, out.getScheduler());
		assertEquals("one entry", 1, notApplied.size());
		assertTrue(notApplied.get(0), notApplied.get(0).startsWith(ConfigNotApplied.CFG_CLAMPED + ": " + RenderPlan.STAGE_BASE + " cfg 12 is outside the " + sdxl.getPattern()));
	}

	@Test
	public void helperLeavesADegenerateRangeAlone() {
		RendererCapabilities kontext = RendererCapabilities.forModel(KONTEXT_MODEL);
		assertNotNull("capabilities of " + KONTEXT_MODEL, kontext);
		assertEquals("precondition: the Kontext range is degenerate", kontext.getCfgMin(), kontext.getCfgMax(), EPS);
		assertNotEquals("precondition: not the FLUX.2 pattern", RendererCapabilities.PATTERN_FLUX2, kontext.getPattern());
		Map<String, Object> cfg = sdxlConfig();
		StageRequest req = sdxlRequest(cfg, 28).toBuilder().model(KONTEXT_MODEL).build();
		List<String> notApplied = new ArrayList<>();
		StageRequest out = FamilyRequest.apply(req, kontext, cfg, notApplied);
		assertSame("a degenerate range clamps nothing: the same request", req, out);
		assertEquals("cfg 7 is not pulled to 1", SDXL_CFG, out.getCfg(), EPS);
		assertTrue("nothing reported", notApplied.isEmpty());
	}

	@Test
	public void outOfRangeCfgIsClampedOncePerRenderAcrossVariants() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97clamp");
			Map<String, Object> cfg = sdxlConfig();
			cfg.put("flux2Cfg", SDXL_CFG);
			RenderPlan plan = plan(FLUX2_MODEL, cfg);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 4246, 10L), ok(f.png, 4247, 10L));
			f.graph.setRenderBackend(backend(adapter));
			PlannedRender out = PbNodeExecutor.renderPlanned(f.graph, f.node, plan, "scene 0 of " + f.slug, f.sdu, "p97_" + f.slug, "Scene has no text to render", 2);
			assertNotNull("render result", out);
			assertEquals("two variants rendered", 2, out.variants.size());
			assertEquals("two requests", 2, adapter.requests.size());
			double max = flux2Caps().getCfgMax();
			for(StageRequest sent : adapter.requests) {
				assertEquals("every variant goes out at the clamped cfg", max, sent.getCfg(), EPS);
				assertEquals("sampler", Flux2Defaults.sampler(), sent.getSampler());
			}
			List<String> clamped = out.configNotApplied.stream().filter(s -> s.startsWith(ConfigNotApplied.CFG_CLAMPED + ":")).toList();
			assertEquals("CFG_CLAMPED is reported once for the render, not once per variant: " + out.configNotApplied, 1, clamped.size());
			assertTrue("the plan's authored negative compiled, so DEFAULT_NEGATIVE is not reported: " + out.configNotApplied,
				out.configNotApplied.stream().noneMatch(s -> s.startsWith(PbNodeExecutor.NOT_APPLIED_DEFAULT_NEGATIVE + ":")));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void sdxlPickOutOfRangeCfgIsClampedOnThePlanPath() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97sdxlc");
			Map<String, Object> cfg = sdxlConfig();
			cfg.put("cfg", 12.0);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 4248, 10L));
			PlannedRender out = render(f, plan(SDXL_MODEL, cfg), backend(adapter));
			assertEquals("one request", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertEquals("model", SDXL_MODEL, sent.getModel());
			assertEquals("cfg 12 is clamped to the SDXL family's cfgMax", sdxlCaps().getCfgMax(), sent.getCfg(), EPS);
			assertEquals("sampler stays", SDXL_SAMPLER, sent.getSampler());
			assertEquals("scheduler stays", SDXL_SCHEDULER, sent.getScheduler());
			List<String> clamped = out.configNotApplied.stream().filter(s -> s.startsWith(ConfigNotApplied.CFG_CLAMPED + ":")).toList();
			assertEquals("reported once: " + out.configNotApplied, 1, clamped.size());
			assertTrue(clamped.get(0), clamped.get(0).contains(RenderPlan.STAGE_BASE + " cfg 12 is outside the " + sdxlCaps().getPattern()));
		}
		finally {
			cleanup(f);
		}
	}

	/// The shared request renderPlanned builds: the config snapshot's SDXL knobs on a FLUX.2 model.
	private static StageRequest sdxlRequest(Map<String, Object> cfg, int presetSteps) {
		return StageRequest.builder()
			.stage(RenderPlan.STAGE_BASE).prompt(new CompiledPrompt(PROMPT, NEGATIVE, 0)).model(FLUX2_MODEL)
			.width(W).height(H).steps(presetSteps).cfg(SDXL_CFG)
			.sampler(SDXL_SAMPLER).scheduler(SDXL_SCHEDULER)
			.seed(777).images(2).hires(false).config(cfg).build();
	}

	private static RendererCapabilities flux2Caps() {
		RendererCapabilities caps = RendererCapabilities.forModel(FLUX2_MODEL);
		assertNotNull("capabilities of " + FLUX2_MODEL, caps);
		assertEquals("the FLUX.2 family", RendererCapabilities.PATTERN_FLUX2, caps.getPattern());
		return caps;
	}

	private static RendererCapabilities sdxlCaps() {
		RendererCapabilities caps = RendererCapabilities.forModel(SDXL_MODEL);
		assertNotNull("capabilities of " + SDXL_MODEL, caps);
		assertNotEquals("not the FLUX.2 family", RendererCapabilities.PATTERN_FLUX2, caps.getPattern());
		return caps;
	}

	@Test
	public void flux2PickOnTheGraphPathTakesTheFlux2Knobs() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97gr", true);
			Map<String, Object> cfg = sdxlConfig();
			cfg.put("flux2Cfg", BOOK_FLUX2_CFG);
			cfg.put("flux2Steps", BOOK_FLUX2_STEPS);
			RenderPlan plan = referencedPlan(f, cfg, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 4245, 10L));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			PlannedRender out = render(f, plan, backend(adapter, client));
			assertNotNull("render result", out);
			assertTrue("no stage errors: " + out.stageErrors, out.stageErrors.isEmpty());

			assertEquals("one request: the BASE graph", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertTrue("a referenced BASE goes through the graph path", sent.isGraph());
			assertEquals("model", FLUX2_MODEL, sent.getModel());
			assertEquals("the portrait still travels", f.portraitA, sent.getInputs().getReferenceArtifactIds().get(0));
			assertEquals("request cfg is the configured flux2Cfg", BOOK_FLUX2_CFG, sent.getCfg(), EPS);
			assertEquals("request sampler", Flux2Defaults.sampler(), sent.getSampler());
			assertEquals("request scheduler", Flux2Defaults.scheduler(), sent.getScheduler());

			/// what the generator was asked for is what Swarm builds the graph from
			assertEquals("one generated workflow", 1, client.requests().size());
			assertFlux2GeneratorParams("BASE", client.requests().get(0), BOOK_FLUX2_CFG);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void flux2PassOnTheGraphPathTakesTheFlux2KnobsToo() throws Exception {
		Fixture f = null;
		try {
			f = fixture("p97si", true);
			Map<String, Object> cfg = sdxlConfig();
			cfg.put("flux2Cfg", BOOK_FLUX2_CFG);
			RenderPlan plan = referencedPlan(f, cfg, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_ACTOR_REGION);
			byte[] passPng = MaskImage.solid(W, H, new Color(160, 100, 30));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(f.png, 11, 10L), ok(passPng, 12, 10L));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			PlannedRender out = render(f, plan, backend(adapter, client));
			assertNotNull("render result", out);
			assertTrue("no stage errors: " + out.stageErrors, out.stageErrors.isEmpty());

			assertEquals("two requests: BASE and the pass", 2, adapter.requests.size());
			StageRequest pass = adapter.requests.get(1);
			assertEquals("stage", RenderPlan.STAGE_ACTOR_REGION, pass.getStage());
			assertTrue("the pass is a graph", pass.isGraph());
			assertEquals("pass cfg is the configured flux2Cfg", BOOK_FLUX2_CFG, pass.getCfg(), EPS);
			assertEquals("pass sampler", Flux2Defaults.sampler(), pass.getSampler());
			assertEquals("pass scheduler", Flux2Defaults.scheduler(), pass.getScheduler());
			assertEquals("the pass steps are the family's inpaint steps, not a config value",
				RendererCapabilities.forModel(FLUX2_MODEL).getInpaint().getSteps(), pass.getSteps());

			assertEquals("two generated workflows", 2, client.requests().size());
			assertTrue("the second was an inpaint", client.requests().get(1).containsKey("initimage"));
			assertFlux2GeneratorParams("BASE", client.requests().get(0), BOOK_FLUX2_CFG);
			assertFlux2GeneratorParams("pass", client.requests().get(1), BOOK_FLUX2_CFG);
		}
		finally {
			cleanup(f);
		}
	}

	/// The generator params WorkflowCompiler.compile hands the client: model, sampler, scheduler and cfgscale
	/// are the FLUX.2 values, never the SDXL ones the config snapshot carries.
	private static void assertFlux2GeneratorParams(String label, Map<String, Object> params, double cfg) {
		assertEquals(label + " generator model", FLUX2_MODEL, params.get("model"));
		assertEquals(label + " generator sampler", Flux2Defaults.sampler(), params.get("sampler"));
		assertEquals(label + " generator scheduler", Flux2Defaults.scheduler(), params.get("scheduler"));
		assertTrue(label + " generator cfgscale is a number: " + params.get("cfgscale"), params.get("cfgscale") instanceof Number);
		assertEquals(label + " generator cfgscale", cfg, ((Number) params.get("cfgscale")).doubleValue(), EPS);
		assertNotEquals(label + " generator cfgscale is not the SDXL cfg", SDXL_CFG, ((Number) params.get("cfgscale")).doubleValue(), EPS);
	}

	// ─────────────────────────────── plan ───────────────────────────────

	/// The SDXL-tuned values the shared config snapshot carries whatever the pick.
	private static Map<String, Object> sdxlConfig() {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("cfg", SDXL_CFG);
		cfg.put("sampler", SDXL_SAMPLER);
		cfg.put("scheduler", SDXL_SCHEDULER);
		cfg.put("steps", 20);
		return cfg;
	}

	/// What PbRenderPlanUtil.scaffold puts on the preset: the pick family's recommended steps for the mode.
	private static int familySteps(String model) {
		RendererCapabilities caps = RendererCapabilities.forModel(model);
		assertNotNull("capabilities of " + model, caps);
		return caps.recommendedSteps(QualityPresets.STEPS_DEFAULT);
	}

	/// A TEXT_ONLY scene plan with a described location and no actors: no reference on BASE, so the
	/// executor's zero-reference branch is the one that runs. The BASE pick of a TEXT_ONLY scene is the
	/// LOCATION pick (RenderPlan.pickForStage), and the preset's steps are that pick family's.
	private static RenderPlan plan(String model, Map<String, Object> cfg) {
		String family = RendererCapabilities.forModel(model).getPattern();
		assertNotNull("family of " + model, family);
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(W, H))
			.preset(new RenderPlan.PresetValues("p97", familySteps(model), 1, false, null))
			.config(cfg)
			.location(new RenderPlan.Location("loc-" + model, "a basalt headland above a grey sea"))
			.strategy(new RenderPlan.Strategy(PbStrategyEnumType.TEXT_ONLY, List.of(RenderPlan.STAGE_BASE), "p97 offline"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, model, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null))
			.compiled(RenderPlan.STAGE_BASE, new CompiledPrompt(PROMPT, NEGATIVE, 0))
			.build();
	}

	/// A referenced plan on FLUX.2 picks, the shape TestPbRenderStagesOffline uses: actor A's portrait on BASE
	/// (DIRECT_REFERENCE), or A on BASE and B on an ACTOR_REGION pass with the INPAINT pick (SEQUENTIAL_INPAINT).
	/// The BASE pick is SCENE_DIRECT, so the graph path's own pick decides the knobs.
	private static RenderPlan referencedPlan(Fixture f, Map<String, Object> cfg, PbStrategyEnumType strategy, String stageB) {
		String family = RendererCapabilities.forModel(FLUX2_MODEL).getPattern();
		assertNotNull("family of " + FLUX2_MODEL, family);
		boolean sequential = (strategy == PbStrategyEnumType.SEQUENTIAL_INPAINT);
		List<String> stages = (sequential ? List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION) : List.of(RenderPlan.STAGE_BASE));
		RenderPlan.Builder b = RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(W, H))
			.preset(new RenderPlan.PresetValues("p97", familySteps(FLUX2_MODEL), 1, false, null))
			.config(cfg)
			.actor(actor(ACTOR_A, "Mara", 0, f.portraitA, RenderPlan.STAGE_BASE, new RenderPlan.Box(0.05, 0.1, 0.4, 0.8)))
			.strategy(new RenderPlan.Strategy(strategy, stages, "p97 offline"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX2_MODEL, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null))
			.compiled(RenderPlan.STAGE_BASE, new CompiledPrompt(PROMPT, NEGATIVE, 0));
		if(sequential) {
			b.actor(actor(ACTOR_B, "Tobin", 1, f.portraitB, stageB, new RenderPlan.Box(0.55, 0.1, 0.4, 0.8)));
			b.model(new RenderPlan.ModelPick(PbModelTaskEnumType.INPAINT, FLUX2_MODEL, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null));
			b.compiled(RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B), new CompiledPrompt(PASS_PROMPT, NEGATIVE, 0));
		}
		return b.build();
	}

	private static RenderPlan.Actor actor(String id, String name, int priority, String portraitOid, String stage, RenderPlan.Box box) {
		return new RenderPlan.Actor.Builder(id)
			.name(name)
			.role(RenderPlan.ROLE_FOREGROUND)
			.priority(priority)
			.box(box)
			.identity(new RenderPlan.Identity(portraitOid, null, name))
			.referenced(true)
			.referenceStage(stage)
			.build();
	}

	private static PlannedRender render(Fixture f, RenderPlan plan, RenderBackend backend) {
		f.graph.setRenderBackend(backend);
		return PbNodeExecutor.renderPlanned(f.graph, f.node, plan, "scene 0 of " + f.slug, f.sdu, "p97_" + f.slug, "Scene has no text to render", 1);
	}

	// ─────────────────────────────── scripted renderer ───────────────────────────────

	private static final class ScriptedAdapter implements RendererAdapter {
		final Deque<StageResult> script = new ArrayDeque<>();
		final List<StageRequest> requests = new ArrayList<>();

		ScriptedAdapter answer(StageResult... results) {
			for(StageResult r : results) {
				script.add(r);
			}
			return this;
		}

		@Override
		public RendererCapabilities capabilities(String model) {
			return RendererCapabilities.forModel(model);
		}

		@Override
		public List<String> listModels() {
			return List.of(FLUX2_MODEL, SDXL_MODEL);
		}

		@Override
		public StageResult execute(StageRequest request, CancelToken cancel) {
			requests.add(request);
			StageResult r = script.poll();
			if(r == null) {
				throw new IllegalStateException("The script has no answer for request " + requests.size() + " (" + request.getStage() + ")");
			}
			return r;
		}
	}

	private static StageResult ok(byte[] png, int seed, long durationMs) {
		return StageResult.builder()
			.image(new StageImage(png, seed, true, W, H))
			.durationMs(durationMs)
			.requestJson("{\"offline\":true}")
			.backendGraph("{\"stage\":\"offline\"}")
			.build();
	}

	private static RenderBackend backend(ScriptedAdapter adapter) {
		return backend(adapter, new FixtureGraphClient());
	}

	private static RenderBackend backend(ScriptedAdapter adapter, FixtureGraphClient client) {
		return new RenderBackend() {
			@Override
			public RendererAdapter adapter(SDUtil sdu) {
				return adapter;
			}
			@Override
			public SwarmGraphClient client(SDUtil sdu) {
				return client;
			}
			@Override
			public PbQaUtil.Selection qaProvider(BaseRecord user, BaseRecord chatConfig, SDUtil sdu, String checkpoint) {
				return new PbQaUtil.Selection(null, NO_QA_NOTE);
			}
		};
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A V3 book with one scene and its RENDER_PLAN node, owned by a non-admin user; portraits only for
	/// the graph-path cases.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		String slug;
		String bookOid;
		String sceneOid;
		byte[] png;
		String portraitA;
		String portraitB;
		SceneGraph graph;
		SDUtil sdu;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	private Fixture fixture(String prefix) throws Exception {
		return fixture(prefix, false);
	}

	private Fixture fixture(String prefix, boolean portraits) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Offline flux2 text-only " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = oidOf(f.book);
		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("scene", scene);
		f.sceneOid = oidOf(scene);

		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		f.workflow = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		assertNotNull("workflow", f.workflow);
		BaseRecord n = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.renderPlanHandle(f.sceneOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
		assertNotNull("render-plan node", n);
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, f.sceneOid);
		assertNotNull("node scope patch must be written", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		f.node = PbGraphUtil.readNode(f.owner, oidOf(n), orgOf(f.owner));
		assertNotNull("re-read node", f.node);

		f.png = MaskImage.solid(W, H, new Color(60, 60, 90));
		if(portraits) {
			f.portraitA = portraitArtifact(f, "a", MaskImage.solid(PORTRAIT_W, PORTRAIT_H, new Color(200, 40, 40)));
			f.portraitB = portraitArtifact(f, "b", MaskImage.solid(PORTRAIT_W, PORTRAIT_H, new Color(40, 40, 200)));
		}
		f.graph = PbPipelineUtil.openNodeGraph(f.owner, f.book, f.workflow, f.node, null, null);
		assertNotNull("scene graph", f.graph);
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER);
		return f;
	}

	/// A portrait artifact on its own PORTRAIT node, the way TestPbRenderStagesOffline builds one.
	private String portraitArtifact(Fixture f, String tag, byte[] png) throws Exception {
		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord pNode = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.portraitHandle(UUID.randomUUID().toString()),
			PbNodeTypeEnumType.PORTRAIT, wfPath, 0);
		assertNotNull("portrait node " + tag, pNode);
		String groupPath = PbBookUtil.artifactGroupPath(f.slug);
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(f.owner, image, "p97-portrait-" + tag + "-" + shortId() + ".png", groupPath, orgOf(f.owner));
		image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(image, png);
		BaseRecord data = ioContext.getAccessPoint().create(f.owner, image);
		assertNotNull("portrait data " + tag, data);
		BaseRecord art = PbArtifactUtil.persistArtifact(f.owner, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
			groupPath, data, null, null, null);
		assertNotNull("portrait artifact " + tag, art);
		assertTrue("portrait metrics " + tag, PbArtifactUtil.recordImageMetrics(f.owner, art, png, "image/png", PORTRAIT_W, PORTRAIT_H, null));
		return oidOf(art);
	}

	private void cleanup(Fixture f) {
		if(f == null || f.book == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("Cleanup of " + f.slug + ": " + e.getMessage());
		}
	}
}
