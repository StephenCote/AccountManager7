package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.junit.Test;

/**
 * Golden-string tests for {@link SdxlCompiler} (P2-4, design §14): style once, exclusions always,
 * placeholder and smart-quote hygiene, photoreal boilerplate, override/raw intent, LoRAs outside the
 * budget, the recorded drop order and the MAIN hard cut. Pure: no DB, no SD, no BaseTest.
 */
public class TestSdxlCompiler {

	private static final String XL = "OfficialStableDiffusion/sd_xl_base_1.0.safetensors";

	private static final String BASE_PROMPT = "a flooded cathedral nave at dusk, Jideon, Mara, a hooded figure, dusk, amber rim light, ink wash, muted earth tones";
	private static final String BASE_NEGATIVE = "text, watermark, blurry";

	private final SdxlCompiler compiler = new SdxlCompiler();

	private static RenderPlan.Builder base() {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", XL);
		cfg.put("steps", 20);
		cfg.put("negativePrompt", "blurry");
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 2, false, "CANONICAL"))
			.config(cfg)
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, XL, "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, null));
	}

	private static RenderPlan.Style style(String description, String exclusions, String palette, boolean photoreal) {
		return new RenderPlan.Style("6f1c", "9ab4", description, exclusions, palette, photoreal, Collections.emptyList());
	}

	private static String words(String prefix, int n) {
		StringBuilder sb = new StringBuilder();
		for(int i = 1; i <= n; i++) {
			if(i > 1) sb.append(' ');
			sb.append(prefix).append(i);
		}
		return sb.toString();
	}

	@Test
	public void styleAppendedExactlyOnce() {
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(TestRenderPlan.samplePlan(), RenderPlan.STAGE_BASE, degs);
		assertEquals(BASE_PROMPT, cp.getPrompt());
		assertEquals(BASE_NEGATIVE, cp.getNegative());
		assertEquals(1, countOf(cp.getPrompt(), "ink wash, muted earth tones"));
		assertTrue(degs.isEmpty());
		assertEquals(PromptBudget.estimateTokens(BASE_PROMPT), cp.getEstimatedTokens());

		/// a main description that already ends with the style text is not given the style twice
		RenderPlan already = base()
			.style(style("ink wash", "text", null, false))
			.location(new RenderPlan.Location(null, "a cathedral, ink wash"))
			.build();
		assertEquals("a cathedral, ink wash", compiler.compile(already, RenderPlan.STAGE_BASE, null).getPrompt());
	}

	@Test
	public void exclusionsAlwaysInNegative() {
		RenderPlan p = base()
			.style(style("ink wash", "Text, watermark, TEXT, signature", null, false))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		/// exclusions first, config negative after, case-insensitive de-dupe keeps the first spelling
		assertEquals("Text, watermark, signature, blurry", cp.getNegative());

		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", XL);
		cfg.put("negativePrompt", "watermark, lowres");
		RenderPlan q = base().config(cfg)
			.style(style("ink wash", "text, watermark", null, false))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.intent(new RenderPlan.Intent("a barn", true))
			.build();
		assertEquals("raw keeps the exclusions", "text, watermark, lowres", compiler.compile(q, RenderPlan.STAGE_BASE, null).getNegative());

		RenderPlan noStyle = base().location(new RenderPlan.Location(null, "a cathedral")).build();
		assertEquals("blurry", compiler.compile(noStyle, RenderPlan.STAGE_BASE, null).getNegative());
	}

	@Test
	public void literalNullStringsDropped() {
		RenderPlan p = base()
			.style(style("null", "text", "n/a", false))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, "null", false))
			.actor(new RenderPlan.Actor("c02", "none", RenderPlan.ROLE_BACKGROUND, "unknown", false))
			.element(new RenderPlan.Element("lantern", RenderPlan.PRIORITY_HIGH, "unspecified"))
			.element(new RenderPlan.Element("N/A", RenderPlan.PRIORITY_HIGH, "   "))
			.camera(new RenderPlan.Camera("NULL"))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("a cathedral, Jideon, lantern", cp.getPrompt());
		assertFalse(cp.getPrompt().toLowerCase().contains("null"));
		assertEquals("text, blurry", cp.getNegative());
	}

	@Test
	public void smartQuotesNormalised() {
		RenderPlan p = base()
			.style(style("“ink wash”", "text—free", "Duña’s amber", false))
			.location(new RenderPlan.Location(null, "the king’s hall – at dusk"))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("the king's hall - at dusk, Duña's amber, \"ink wash\"", cp.getPrompt());
		assertEquals("text-free, blurry", cp.getNegative());
	}

	@Test
	public void photorealOnEmitsBoilerplate() {
		RenderPlan p = base()
			.style(style("35mm film still", "text", null, true))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("a cathedral, 35mm film still, " + SdxlCompiler.PHOTOREAL_BOILERPLATE, cp.getPrompt());
		assertTrue(cp.getPrompt().endsWith(SdxlCompiler.PHOTOREAL_BOILERPLATE));
	}

	@Test
	public void photorealOffEmitsNone() {
		RenderPlan p = base()
			.style(style("35mm film still", "text", null, false))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.build();
		assertEquals("a cathedral, 35mm film still", compiler.compile(p, RenderPlan.STAGE_BASE, null).getPrompt());

		/// raw suppresses the boilerplate even when the style is photoreal
		RenderPlan raw = base()
			.style(style("35mm film still", "text", null, true))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.intent(new RenderPlan.Intent(null, true))
			.build();
		assertEquals("a cathedral", compiler.compile(raw, RenderPlan.STAGE_BASE, null).getPrompt());
	}

	@Test
	public void promptOverrideIsMainDescription() {
		RenderPlan p = base()
			.style(style("ink wash", "text", "amber", false))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, "a tall man", false))
			.intent(new RenderPlan.Intent("a red barn in snow", false))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("a red barn in snow, Jideon, a tall man, amber, ink wash", cp.getPrompt());
		assertFalse(cp.getPrompt().contains("cathedral"));
		assertEquals("text, blurry", cp.getNegative());
	}

	@Test
	public void rawFlagSkipsStyleButKeepsExclusions() {
		RenderPlan p = base()
			.style(style("ink wash", "text, watermark", "amber", true))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, "a tall man", false))
			.intent(new RenderPlan.Intent("a red barn in snow", true))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("a red barn in snow, Jideon, a tall man, amber", cp.getPrompt());
		assertFalse(cp.getPrompt().contains("ink wash"));
		assertFalse(cp.getPrompt().contains(SdxlCompiler.PHOTOREAL_BOILERPLATE));
		assertEquals("text, watermark, blurry", cp.getNegative());
	}

	@Test
	public void lorasAppendedAfterBudget() {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", XL);
		cfg.put("negativePrompt", "blurry");
		cfg.put("loras", Arrays.asList("detailTweaker:0.8", "", "inkStyle:1.0"));
		RenderPlan p = base().config(cfg)
			.style(style("ink wash", "text", null, false))
			.location(new RenderPlan.Location(null, "a cathedral"))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("a cathedral, ink wash, <lora:detailTweaker:0.8>, <lora:inkStyle:1.0>", cp.getPrompt());
		/// the estimate is of the prompt before the LoRA tags: they never count against the budget
		assertEquals(PromptBudget.estimateTokens("a cathedral, ink wash"), cp.getEstimatedTokens());

		/// a prompt right at the budget still carries the LoRAs, with no truncation recorded
		List<Degradation> degs = new ArrayList<>();
		RenderPlan full = base().config(cfg)
			.style(style("ink wash", "text", null, false))
			.location(new RenderPlan.Location(null, words("w", 220)))
			.build();
		CompiledPrompt fp = compiler.compile(full, RenderPlan.STAGE_BASE, degs);
		assertTrue(fp.getPrompt().endsWith(", <lora:detailTweaker:0.8>, <lora:inkStyle:1.0>"));
		assertTrue(fp.getEstimatedTokens() <= 225);
		assertTrue(degs.isEmpty());
	}

	@Test
	public void truncationDropOrderLowMediumExtrasBackground() {
		RenderPlan p = base()
			.style(style("ink wash", "text", "amber and teal", false))
			.location(new RenderPlan.Location(null, words("m", 100)))
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, "a tall man", false))
			.actor(new RenderPlan.Actor("c02", "Mara", RenderPlan.ROLE_BACKGROUND, words("b", 120), false))
			.element(new RenderPlan.Element("lantern", RenderPlan.PRIORITY_HIGH, null))
			.element(new RenderPlan.Element("rubble", RenderPlan.PRIORITY_MEDIUM, words("e", 20)))
			.element(new RenderPlan.Element("gulls", RenderPlan.PRIORITY_LOW, words("l", 20)))
			.camera(new RenderPlan.Camera("wide shot low angle"))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);

		List<String> order = new ArrayList<>();
		for(Degradation d : degs) {
			assertEquals(Degradation.PROMPT_TRUNCATED, d.getCode());
			assertEquals(RenderPlan.STAGE_BASE, d.getStage());
			assertNull(d.getActorId());
			assertTrue(d.getMessage(), d.getMessage().matches(".* \\(\\d+ tokens over budget 225\\)"));
			order.add(d.getMessage().substring(0, d.getMessage().indexOf(" (")));
		}
		assertEquals(Arrays.asList("ELEM_LOW dropped", "ELEM_MEDIUM dropped", "PALETTE dropped", "CAMERA dropped", "ACTOR_BG detail dropped"), order);

		String prompt = cp.getPrompt();
		assertTrue(prompt.startsWith(words("m", 100) + ", Jideon, a tall man, Mara, lantern"));
		assertTrue("style is never dropped", prompt.endsWith(", ink wash"));
		assertFalse(prompt.contains("gulls"));
		assertFalse(prompt.contains("rubble"));
		assertFalse(prompt.contains("amber and teal"));
		assertFalse(prompt.contains("wide shot"));
		assertFalse("background detail dropped, name kept", prompt.contains("b1 "));
		assertTrue(prompt.contains("Mara"));
		assertTrue(cp.getEstimatedTokens() <= 225);
		assertEquals(PromptBudget.estimateTokens(prompt), cp.getEstimatedTokens());
	}

	@Test
	public void hardCutRecordsTruncation() {
		StringBuilder main = new StringBuilder();
		for(int i = 0; i < 30; i++) {
			if(i > 0) main.append(", ");
			main.append(words("s" + i + "x", 10));
		}
		RenderPlan p = base()
			.style(style("ink wash", "text", null, false))
			.location(new RenderPlan.Location(null, main.toString()))
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, "a tall man", false))
			.element(new RenderPlan.Element("lantern", RenderPlan.PRIORITY_HIGH, null))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals(1, degs.size());
		assertEquals(Degradation.PROMPT_TRUNCATED, degs.get(0).getCode());
		assertTrue(degs.get(0).getMessage(), degs.get(0).getMessage().startsWith(SdxlCompiler.MAIN_CUT + " ("));
		String prompt = cp.getPrompt();
		assertTrue(prompt.startsWith(words("s0x", 10)));
		assertTrue("foreground actor, high element and style survive the cut", prompt.endsWith(", Jideon, a tall man, lantern, ink wash"));
		assertFalse(prompt.contains("s29x1"));
		assertTrue(cp.getEstimatedTokens() <= 225);
		assertEquals(PromptBudget.estimateTokens(prompt), cp.getEstimatedTokens());

		/// the cut lands on a ", " boundary of the main description, never mid-word
		String cutMain = prompt.substring(0, prompt.indexOf(", Jideon"));
		assertTrue(main.toString().startsWith(cutMain));
		assertTrue(main.toString().substring(cutMain.length()).startsWith(", "));
	}

	@Test
	public void upscaleStageEqualsBase() {
		RenderPlan p = TestRenderPlan.samplePlan();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt b = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		CompiledPrompt u = compiler.compile(p, RenderPlan.STAGE_UPSCALE, degs);
		assertEquals(b.getPrompt(), u.getPrompt());
		assertEquals(b.getNegative(), u.getNegative());
		assertEquals(b.getEstimatedTokens(), u.getEstimatedTokens());
		assertTrue(degs.isEmpty());

		/// once BASE is compiled onto the plan, UPSCALE reuses it verbatim
		Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		compiled.put(RenderPlan.STAGE_BASE, new CompiledPrompt("pinned", "neg", 1));
		RenderPlan withBase = p.withCompiled(compiled, null);
		assertEquals("pinned", compiler.compile(withBase, RenderPlan.STAGE_UPSCALE, null).getPrompt());
		assertEquals(b.getPrompt(), compiler.compile(withBase, RenderPlan.STAGE_BASE, null).getPrompt());
	}

	private static int countOf(String haystack, String needle) {
		int n = 0;
		int idx = 0;
		while((idx = haystack.indexOf(needle, idx)) >= 0) {
			n++;
			idx += needle.length();
		}
		return n;
	}
}
