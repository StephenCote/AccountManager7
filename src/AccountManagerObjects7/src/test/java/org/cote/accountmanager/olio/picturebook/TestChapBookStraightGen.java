package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * ChapBook straight generation on the V3 plan path (2026-11-10): a ChapBook TEXT_ONLY plan takes the effective
 * config's steps, style clause and default negative, and its BASE request the config's hires and seed - what the
 * v1 page path (ChapBookUtil.renderResolvedSceneV1) always honoured. A referenced (non-TEXT_ONLY) plan is
 * untouched. No SD server is contacted; the plan is built directly. Runs under BaseTest so the model registry (olio.sd.config, the sdConfigData vocabulary) is the one Service7 runs with.
 */
public class TestChapBookStraightGen extends BaseTest {

	private static final String PAGE_PROMPT = "a lighthouse on a rocky headland at dusk";

	@Before
	public void setupEach() {
		OlioModelNames.use();
	}

	private static Map<String, Object> pageConfig() {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", "OfficialStableDiffusion/sd_xl_base_1.0.safetensors");
		cfg.put("steps", 40);
		cfg.put("cfg", 5);
		cfg.put("hires", true);
		cfg.put("refinerModel", "lustifySDXLNSFW_endgame.safetensors");
		cfg.put("refinerSteps", 60);
		cfg.put("refinerCfg", 7);
		cfg.put("style", "comic");
		cfg.put("seed", 4242);
		return cfg;
	}

	private static RenderPlan plan(PbStrategyEnumType strategy, Map<String, Object> cfg, String styleDescription, String negative) {
		return RenderPlan.builder()
			.purpose("SCENE")
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 2, false, "CANONICAL"))
			.style(new RenderPlan.Style(null, null, styleDescription, null, null, false, new ArrayList<>()))
			.config(cfg)
			.strategy(new RenderPlan.Strategy(strategy, new ArrayList<>(List.of(RenderPlan.STAGE_BASE)), "test"))
			.compiled(RenderPlan.STAGE_BASE, new CompiledPrompt(PAGE_PROMPT, negative, 10))
			.build();
	}

	@Test
	public void stepsStyleAndNegativeComeFromTheConfig() {
		RenderPlan in = plan(PbStrategyEnumType.TEXT_ONLY, pageConfig(), null, "");
		RenderPlan out = ChapBookStraightGen.applyToTextOnly(in);
		assertEquals("config steps replace the preset's 20", 40, out.getPreset().getSteps());
		assertEquals("preset name kept", "STANDARD", out.getPreset().getPreset());
		assertEquals("variants kept", 2, out.getPreset().getVariants());
		CompiledPrompt base = out.getCompiled().get(RenderPlan.STAGE_BASE);
		assertTrue("page prompt kept first: " + base.getPrompt(), base.getPrompt().startsWith(PAGE_PROMPT));
		assertTrue("comic style clause appended (v1 applyConfigStyle): " + base.getPrompt(), base.getPrompt().contains("Comic book panel"));
		assertFalse("no literal null in the clause: " + base.getPrompt(), base.getPrompt().contains("(null)"));
		assertEquals("default negative when none compiled", NarrativeUtil.getDefaultNegativePrompt(), base.getNegative());
		assertTrue("hires from the config", ChapBookStraightGen.hires(out.getConfig()));
		assertEquals("config seed", 4242, ChapBookStraightGen.seed(out.getConfig()));
	}

	@Test
	public void aBookStyleDescriptionIsTheStyleAndANegativeIsKept() {
		RenderPlan in = plan(PbStrategyEnumType.TEXT_ONLY, pageConfig(), "ink wash illustration", "blurry");
		CompiledPrompt base = ChapBookStraightGen.applyToTextOnly(in).getCompiled().get(RenderPlan.STAGE_BASE);
		assertEquals("no config clause on top of the book style", PAGE_PROMPT, base.getPrompt());
		assertEquals("compiled negative kept", "blurry", base.getNegative());
	}

	@Test
	public void unsetConfigFallsBackToThePreset() {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", "OfficialStableDiffusion/sd_xl_base_1.0.safetensors");
		cfg.put("seed", -1);
		RenderPlan out = ChapBookStraightGen.applyToTextOnly(plan(PbStrategyEnumType.TEXT_ONLY, cfg, null, "x"));
		assertEquals("no config steps: preset steps", 20, out.getPreset().getSteps());
		assertEquals("no style: prompt unchanged", PAGE_PROMPT, out.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt());
		assertFalse("no hires", ChapBookStraightGen.hires(cfg));
		assertEquals("seed -1 is random", StageRequest.SEED_RANDOM, ChapBookStraightGen.seed(cfg));
	}

	@Test
	public void aReferencedPlanIsUntouched() {
		RenderPlan in = plan(PbStrategyEnumType.DIRECT_REFERENCE, pageConfig(), null, "");
		assertSame("DIRECT_REFERENCE (FLUX.2 BASE) keeps the preset steps and its compiled prompt", in, ChapBookStraightGen.applyToTextOnly(in));
		assertFalse(ChapBookStraightGen.isTextOnly(in));
	}

	@Test
	public void honouredNotAppliedNotesAreDropped() {
		RenderPlan p = plan(PbStrategyEnumType.TEXT_ONLY, pageConfig(), null, "");
		List<String> notes = new ArrayList<>(Arrays.asList(
			PbNodeExecutor.NOT_APPLIED_HIRES + ": x", PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE + ": x",
			PbNodeExecutor.NOT_APPLIED_DEFAULT_NEGATIVE + ": x", PbNodeExecutor.NOT_APPLIED_CFG_CLAMPED + ": x"));
		assertEquals(Arrays.asList(PbNodeExecutor.NOT_APPLIED_CFG_CLAMPED + ": x"), ChapBookStraightGen.dropHonoured(notes, p));

		RenderPlan styled = plan(PbStrategyEnumType.TEXT_ONLY, pageConfig(), "ink wash", "");
		List<String> kept = new ArrayList<>(List.of(PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE + ": x"));
		assertEquals("with a book style the config clause really is not applied, so the note stays", 1, ChapBookStraightGen.dropHonoured(kept, styled).size());
		assertNotEquals(PbNodeExecutor.NOT_APPLIED_HIRES, PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE);
	}
}
