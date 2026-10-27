package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.illustration.RenderPlan.Actor;
import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Test;

/**
 * Golden-string tests for {@link InpaintRegionCompiler} (P5-2, design §14): one actor, one reference,
 * region-local wording, the fixed rules, style once, LoRAs outside the budget, the negative from style
 * exclusions plus the config negative, and the over-budget record against the actor. Pure: no DB, no SD.
 */
public class TestInpaintRegionCompiler {

	private static final String FLUX = "flux2Klein_9b.safetensors";
	private static final String MAIN = "a flooded cathedral nave at dusk";
	private static final String STYLE = "ink wash, muted earth tones";
	private static final String RULES = Flux2Compiler.RULE_IDENTITY + InpaintRegionCompiler.RULE_REGION_COHERENCE
		+ Flux2Compiler.RULE_NO_EXTRA_PEOPLE + InpaintRegionCompiler.RULE_NO_REFERENCE_OBJECT + Flux2Compiler.RULE_SINGLE_SCENE;

	private final InpaintRegionCompiler compiler = new InpaintRegionCompiler();

	private static Map<String, Object> config(List<String> loras) {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", FLUX);
		cfg.put("steps", 4);
		cfg.put("negativePrompt", "blurry");
		if(loras != null) {
			cfg.put("loras", loras);
		}
		return cfg;
	}

	private static RenderPlan.Builder base() {
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 4, 1, false, "CANONICAL"))
			.config(config(null))
			.style(new RenderPlan.Style("6f1c", "9ab4", STYLE, "text, watermark", null, false, Collections.emptyList()))
			.location(new RenderPlan.Location(null, MAIN))
			.strategy(new RenderPlan.Strategy(PbStrategyEnumType.SEQUENTIAL_INPAINT, Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION), "test"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.INPAINT, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null));
	}

	private static Actor.Builder region(String id, String name, String text) {
		return new Actor.Builder(id).characterId("ch-" + id).name(name).role(RenderPlan.ROLE_SUPPORTING).priority(3)
			.identity(new RenderPlan.Identity("art-" + id, null, text)).referenced(true).referenceStage(RenderPlan.STAGE_ACTOR_REGION)
			.box(new RenderPlan.Box(0.66, 0.2, 0.33, 0.8));
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
	public void oneActorRegionLocalWithEveryClause() {
		Actor tam = region("a3", "Tam", "a wiry young man with cropped black hair")
			.outfit(new RenderPlan.Outfit("ap-3", "wearing a bright yellow scarf and a grey coat", null))
			.pose("crouching").expression("intent").action("picking up neuro chips").facing("left").build();
		RenderPlan p = base()
			.actor(new Actor.Builder("a1").name("Jideon").role(RenderPlan.ROLE_HERO).priority(1)
				.identity(new RenderPlan.Identity("art-a1", null, "a tall man")).referenced(true).build())
			.actor(tam)
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, tam, degs);
		assertEquals("The person in the reference image is Tam, a wiry young man with cropped black hair, wearing a bright yellow scarf and a grey coat. "
			+ "Tam is crouching, intent, picking up neuro chips, facing left. "
			+ STYLE + ". " + RULES.trim(), cp.getPrompt());
		assertEquals("text, watermark, blurry", cp.getNegative());
		assertEquals(PromptBudget.estimateTokens(cp.getPrompt()), cp.getEstimatedTokens());
		assertTrue(degs.isEmpty());

		/// region-local: the other actor and the setting are never spoken of, and the reference is singular
		assertFalse(cp.getPrompt().contains("Jideon"));
		assertFalse(cp.getPrompt().contains("cathedral"));
		assertFalse(cp.getPrompt().contains("reference images"));
		assertFalse(cp.getPrompt().contains("first reference"));
		for(char c : cp.getPrompt().toCharArray()) {
			assertTrue("non-ASCII in prompt: U+" + Integer.toHexString(c), c < 128);
		}
		String lower = cp.getPrompt().toLowerCase();
		for(String banned : new String[] {"bias", "identity policy", "diverse", "diversity", "inclusive", "ethnic", "gender"}) {
			assertFalse("policy text leaked: " + banned, lower.contains(banned));
		}
	}

	@Test
	public void optionalClausesAreOmittedWhenNotMeaningful() {
		/// identity only: no outfit, no pose sentence
		Actor bare = region("a3", "Tam", "a wiry young man").build();
		RenderPlan p = base().actor(bare).build();
		assertEquals("The person in the reference image is Tam, a wiry young man. " + STYLE + ". " + RULES.trim(),
			compiler.compile(p, bare, null).getPrompt());

		/// no name: the pose sentence falls back to "The same person"
		Actor unnamed = region("a4", "null", "a stooped crone").pose("seated").build();
		assertEquals("The person in the reference image is a stooped crone. The same person is seated. " + STYLE + ". " + RULES.trim(),
			compiler.compile(base().actor(unnamed).build(), unnamed, null).getPrompt());

		/// facing alone still makes a pose sentence
		Actor facing = region("a5", "Bes", "a crone").facing("right").build();
		assertTrue(compiler.compile(base().actor(facing).build(), facing, null).getPrompt().contains("The person in the reference image is Bes, a crone. Bes is facing right. "));

		/// nothing meaningful at all: the reference sentence alone, then style and rules
		Actor empty = region("a6", "  ", "n/a").build();
		assertEquals("The person in the reference image. " + STYLE + ". " + RULES.trim(), compiler.compile(base().actor(empty).build(), empty, null).getPrompt());

		/// no style: rules follow the actor directly; the negative is the config's alone
		RenderPlan noStyle = base().style(null).actor(bare).build();
		CompiledPrompt ns = compiler.compile(noStyle, bare, null);
		assertEquals("The person in the reference image is Tam, a wiry young man. " + RULES.trim(), ns.getPrompt());
		assertEquals("blurry", ns.getNegative());

		assertEquals("", compiler.compile(null, bare, null).getPrompt());
		assertEquals("", compiler.compile(p, null, null).getPrompt());
	}

	@Test
	public void lorasOutsideTheBudgetAndRawIntentIgnored() {
		Actor bare = region("a3", "Tam", "a wiry young man").build();
		RenderPlan p = base().config(config(Arrays.asList("detailTweaker:0.8", "", "inkStyle:1.0")))
			.intent(new RenderPlan.Intent("a red barn in snow", true))
			.actor(bare).build();
		CompiledPrompt cp = compiler.compile(p, bare, null);
		String expected = "The person in the reference image is Tam, a wiry young man. " + STYLE + ". " + RULES.trim();
		assertEquals(expected + ", <lora:detailTweaker:0.8>, <lora:inkStyle:1.0>", cp.getPrompt());
		assertEquals(PromptBudget.estimateTokens(expected), cp.getEstimatedTokens());
		assertFalse("the scene-level raw override is not a region instruction", cp.getPrompt().contains("barn"));
	}

	@Test
	public void overBudgetIsRecordedAgainstTheActorAndSentUncut() {
		Actor huge = region("a3", "Tam", words("f", 600)).build();
		RenderPlan p = base().actor(huge).build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, huge, degs);
		assertEquals(1, degs.size());
		Degradation d = degs.get(0);
		assertEquals(Degradation.PROMPT_TRUNCATED, d.getCode());
		assertEquals("a3", d.getActorId());
		assertEquals(RenderPlan.STAGE_ACTOR_REGION, d.getStage());
		assertTrue(d.getMessage(), d.getMessage().startsWith(InpaintRegionCompiler.OVER_BUDGET_UNCUT + " ("));
		assertTrue(d.getMessage(), d.getMessage().endsWith(" tokens over budget 512)"));
		assertTrue(cp.getPrompt().contains("f600"));
		assertTrue(cp.getEstimatedTokens() > 512);

		/// the budget follows the ACTOR_REGION pick: with no INPAINT pick the SCENE_DIRECT (flux2) pick still budgets at 512;
		/// an SDXL INPAINT pick budgets at 225
		RenderPlan xl = base()
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.INPAINT, "OfficialStableDiffusion/sd_xl_base_1.0.safetensors", "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.actor(region("a3", "Tam", words("f", 300)).build()).build();
		List<Degradation> xd = new ArrayList<>();
		compiler.compile(xl, xl.getActors().get(0), xd);
		assertEquals(1, xd.size());
		assertTrue(xd.get(0).getMessage(), xd.get(0).getMessage().endsWith("over budget 225)"));

		/// under budget nothing is recorded and null out is tolerated
		List<Degradation> none = new ArrayList<>();
		compiler.compile(base().actor(region("a3", "Tam", "a wiry young man").build()).build(), region("a3", "Tam", "a wiry young man").build(), none);
		assertTrue(none.isEmpty());
		assertEquals("over budget with a null out list still compiles the same prompt", cp.getPrompt(), compiler.compile(p, huge, null).getPrompt());
	}
}
