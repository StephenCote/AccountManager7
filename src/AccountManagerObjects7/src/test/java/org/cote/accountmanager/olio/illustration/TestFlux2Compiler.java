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

import org.cote.accountmanager.olio.illustration.RenderPlan.Actor;
import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Element;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Test;

/**
 * Golden-string tests for {@link Flux2Compiler} (P4-7, design §14): the placement opening per strategy,
 * one sentence per actor role, elements grouped by importance, style once, the fixed rules, LoRAs
 * outside the budget, raw intent, the 512-token drop order, the BASE cache for non-BASE stages, and
 * {@link PromptCompilers#forPick}. Pure: no DB, no SD, no BaseTest.
 */
public class TestFlux2Compiler {

	private static final String FLUX = "flux2Klein_9b.safetensors";
	private static final String XL = "OfficialStableDiffusion/sd_xl_base_1.0.safetensors";
	private static final String MAIN = "a flooded cathedral nave at dusk";
	private static final String STYLE = "ink wash, muted earth tones";
	private static final String RULES_TEXT_ONLY = Flux2Compiler.RULE_COHERENCE + Flux2Compiler.RULE_NO_EXTRA_PEOPLE + Flux2Compiler.RULE_SINGLE_SCENE;
	private static final String RULES_REFERENCE = Flux2Compiler.RULE_IDENTITY + Flux2Compiler.RULE_COHERENCE + Flux2Compiler.RULE_NO_EXTRA_PEOPLE
		+ Flux2Compiler.RULE_NO_REFERENCE_OBJECTS + Flux2Compiler.RULE_SINGLE_SCENE;

	private final Flux2Compiler compiler = new Flux2Compiler();

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

	/// Both picks are flux2 so TEXT_ONLY (location pick) and DIRECT_REFERENCE (sceneDirect pick) budget at 512.
	private static RenderPlan.Builder base(PbStrategyEnumType strategy) {
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 4, 1, false, "CANONICAL"))
			.config(config(null))
			.style(style(STYLE, "text, watermark"))
			.location(new RenderPlan.Location(null, MAIN))
			.strategy(new RenderPlan.Strategy(strategy, Arrays.asList(RenderPlan.STAGE_BASE), strategy + ": test"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null));
	}

	private static RenderPlan.Style style(String description, String exclusions) {
		return new RenderPlan.Style("6f1c", "9ab4", description, exclusions, null, false, Collections.emptyList());
	}

	private static Actor actor(String id, String name, String role, String text, boolean referenced, int priority) {
		return new Actor.Builder(id).characterId("ch-" + id).name(name).role(role).priority(priority)
			.identity(new RenderPlan.Identity(referenced ? "art-" + id : null, null, text)).referenced(referenced).build();
	}

	private static Actor extra(String id, String name, String role, String text) {
		return new Actor.Builder(id).name(name).role(role).identity(text == null ? null : new RenderPlan.Identity(null, null, text)).build();
	}

	private static String words(String prefix, int n) {
		StringBuilder sb = new StringBuilder();
		for(int i = 1; i <= n; i++) {
			if(i > 1) sb.append(' ');
			sb.append(prefix).append(i);
		}
		return sb.toString();
	}

	private static List<String> dropOrder(List<Degradation> degs, int budget) {
		List<String> order = new ArrayList<>();
		for(Degradation d : degs) {
			assertEquals(Degradation.PROMPT_TRUNCATED, d.getCode());
			assertEquals(RenderPlan.STAGE_BASE, d.getStage());
			assertNull(d.getActorId());
			assertTrue(d.getMessage(), d.getMessage().matches(".* \\(\\d+ tokens over budget " + budget + "\\)"));
			order.add(d.getMessage().substring(0, d.getMessage().lastIndexOf(" (")));
		}
		return order;
	}

	@Test
	public void textOnlyListsEveryActorAsTextWithNoReferenceWording() {
		/// Jideon carries a portrait and is flagged referenced on the actor, but the strategy is TEXT_ONLY:
		/// no ordinal is spoken and no reference rule is emitted (architect MUST-FIX 1).
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_FOREGROUND, "a hooded figure", false, 2))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals("Place the people described below in " + MAIN + ". "
			+ "The first person is Jideon, a tall man. "
			+ "The second person is Mara, a hooded figure. "
			+ STYLE + ". " + RULES_TEXT_ONLY.trim(), cp.getPrompt());
		assertFalse(cp.getPrompt().contains("reference"));
		assertFalse(cp.getPrompt().contains(Flux2Compiler.RULE_IDENTITY.trim()));
		assertFalse(cp.getPrompt().contains(Flux2Compiler.RULE_NO_REFERENCE_OBJECTS.trim()));
		assertEquals("text, watermark, blurry", cp.getNegative());
		assertTrue(degs.isEmpty());
		assertEquals(PromptBudget.estimateTokens(cp.getPrompt()), cp.getEstimatedTokens());

		RenderPlan one = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_SUPPORTING, "a tall man", false, 1))
			.build();
		assertTrue(compiler.compile(one, RenderPlan.STAGE_BASE, null).getPrompt().startsWith("Place the person described below in " + MAIN + ". The first person is Jideon, a tall man. "));

		/// no actors at all: MAIN alone is the scene sentence
		RenderPlan none = base(PbStrategyEnumType.TEXT_ONLY).build();
		assertEquals(MAIN + ". " + STYLE + ". " + RULES_TEXT_ONLY.trim(), compiler.compile(none, RenderPlan.STAGE_BASE, null).getPrompt());

		/// a plan with no strategy at all is text-only too
		RenderPlan noStrategy = base(PbStrategyEnumType.TEXT_ONLY).strategy(null)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.build();
		assertFalse(compiler.compile(noStrategy, RenderPlan.STAGE_BASE, null).getPrompt().contains("reference"));
	}

	@Test
	public void directReferenceOneActorNamesTheOrdinalsAndTheLocationRefAfterTheActors() {
		RenderPlan p = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", false, 2))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("Take the exact person and face from the first reference image and place them, with the other people described below, in " + MAIN + ". "
			+ "The first person (the first reference image) is Jideon, a tall man. "
			+ "The second person is Mara, a hooded figure. "
			+ STYLE + ". " + RULES_REFERENCE.trim(), cp.getPrompt());

		/// the one referenced actor alone: "place them in", no "other people"
		RenderPlan alone = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.build();
		assertTrue(compiler.compile(alone, RenderPlan.STAGE_BASE, null).getPrompt()
			.startsWith("Take the exact person and face from the first reference image and place them in " + MAIN + ". The first person (the first reference image) is Jideon, a tall man. "));

		/// with an approved location render the environment is the reference AFTER the actors' (second)
		RenderPlan withLoc = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.location(new RenderPlan.Location("loc-1", null, "ab12", "art-loc", MAIN))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", false, 2))
			.build();
		String prompt = compiler.compile(withLoc, RenderPlan.STAGE_BASE, null).getPrompt();
		assertTrue(prompt, prompt.startsWith("Take the exact person and face from the first reference image and place them, with the other people described below, in "
			+ "the environment shown in the second reference image (" + MAIN + "). The first person (the first reference image) is Jideon, a tall man. "));

		/// no actor referenced but the location is: the location is the first reference and the identity rules still apply
		RenderPlan locOnly = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.location(new RenderPlan.Location("loc-1", null, "ab12", "art-loc", MAIN))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", false, 2))
			.build();
		String lp = compiler.compile(locOnly, RenderPlan.STAGE_BASE, null).getPrompt();
		assertTrue(lp, lp.startsWith("Place the person described below in the environment shown in the first reference image (" + MAIN + "). The first person is Mara, a hooded figure. "));
		assertTrue(lp, lp.endsWith(RULES_REFERENCE.trim()));

		/// no actors, location referenced: the environment sentence alone
		RenderPlan envOnly = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.location(new RenderPlan.Location("loc-1", null, "ab12", "art-loc", MAIN))
			.build();
		assertTrue(compiler.compile(envOnly, RenderPlan.STAGE_BASE, null).getPrompt()
			.startsWith("The environment shown in the first reference image (" + MAIN + "). " + STYLE + ". "));
	}

	@Test
	public void directReferenceTwoActorsOrderedByPriorityNotByListOrder() {
		/// Jideon is listed first but carries priority 2; Mara is priority 1 so she is the FIRST reference image,
		/// while the person numbering follows the list order.
		RenderPlan p = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.location(new RenderPlan.Location("loc-1", null, "ab12", "art-loc", MAIN))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 2))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", true, 1))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		assertEquals("Combine the exact person and face from the first reference image with the exact person and face from the second reference image. "
			+ "Place them together in the environment shown in the third reference image (" + MAIN + "). "
			+ "The first person (the second reference image) is Jideon, a tall man. "
			+ "The second person (the first reference image) is Mara, a hooded figure. "
			+ STYLE + ". " + RULES_REFERENCE.trim(), cp.getPrompt());

		/// a third, text-only actor: "them all, with the other people described below"
		RenderPlan three = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", true, 2))
			.actor(actor("a3", "Tam", RenderPlan.ROLE_FOREGROUND, "a boy with a lantern", false, 3))
			.build();
		String prompt = compiler.compile(three, RenderPlan.STAGE_BASE, null).getPrompt();
		assertTrue(prompt, prompt.startsWith("Combine the exact person and face from the first reference image with the exact person and face from the second reference image. "
			+ "Place them all, with the other people described below, in " + MAIN + ". "
			+ "The first person (the first reference image) is Jideon, a tall man. "
			+ "The second person (the second reference image) is Mara, a hooded figure. "
			+ "The third person is Tam, a boy with a lantern. "));

		/// three references: "and the third reference image"
		RenderPlan trio = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", true, 2))
			.actor(actor("a3", "Tam", RenderPlan.ROLE_FOREGROUND, "a boy with a lantern", true, 3))
			.build();
		assertTrue(compiler.compile(trio, RenderPlan.STAGE_BASE, null).getPrompt().startsWith(
			"Combine the exact person and face from the first reference image with the exact person and face from the second reference image and the third reference image. Place them together in "));
	}

	@Test
	public void everyRoleHasItsSentence() {
		Actor hero = new Actor.Builder("a1").characterId("ch-1").name("Jideon").role(RenderPlan.ROLE_HERO).priority(1)
			.identity(new RenderPlan.Identity(null, null, "a tall man"))
			.outfit(new RenderPlan.Outfit("ap-1", "wearing a grey cloak", null))
			.pose("standing").expression("scowling").action("drawing a sword").facing("left").build();
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(hero)
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", false, 2))
			.actor(actor("a3", "Tam", RenderPlan.ROLE_FOREGROUND, "a boy", false, 3))
			.actor(new Actor.Builder("a4").characterId("ch-4").name("Old Bes").role(RenderPlan.ROLE_NAMED_BACKGROUND).priority(4)
				.identity(new RenderPlan.Identity(null, null, "a stooped crone")).outfit(new RenderPlan.Outfit(null, "in a patched shawl", null)).build())
			.actor(extra("a5", "dockhands", RenderPlan.ROLE_EXTRA, "hauling rope"))
			.actor(extra("a6", null, RenderPlan.ROLE_CROWD, "onlookers in the rain"))
			.actor(extra("a7", "3 guards", RenderPlan.ROLE_EXTRA, "in red livery"))
			.build();
		String prompt = compiler.compile(p, RenderPlan.STAGE_BASE, null).getPrompt();
		assertEquals("Place the people described below in " + MAIN + ". "
			+ "The first person is Jideon, a tall man, wearing a grey cloak, standing, scowling, drawing a sword, facing left. "
			+ "The second person is Mara, a hooded figure. "
			+ "The third person is Tam, a boy. "
			+ "In the background, Old Bes is a stooped crone, in a patched shawl. "
			+ "Several dockhands, hauling rope. "
			+ "Several onlookers in the rain. "
			+ "3 guards, in red livery. "
			+ STYLE + ". " + Flux2Compiler.RULE_COHERENCE + Flux2Compiler.RULE_SINGLE_SCENE.trim(), prompt);
		assertFalse("extras asked for: no 'No extra people'", prompt.contains(Flux2Compiler.RULE_NO_EXTRA_PEOPLE.trim()));

		/// the legacy BACKGROUND alias and a named character staged as an EXTRA both take the background sentence;
		/// a referenced character in an extra role, and a NAMED_BACKGROUND one referenced under REQUIRE_REFERENCE,
		/// keep their ordinals so every reference the opening counts is bound to a person
		RenderPlan bg = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(new Actor("a2", "Mara", RenderPlan.ROLE_BACKGROUND, "a hooded figure", false))
			.actor(actor("a3", "Tam", RenderPlan.ROLE_EXTRA, "a boy", true, 3))
			.actor(actor("a4", "Bes", RenderPlan.ROLE_NAMED_BACKGROUND, "a crone", true, 4))
			.build();
		String bp = compiler.compile(bg, RenderPlan.STAGE_BASE, null).getPrompt();
		assertTrue(bp, bp.contains("The first person (the first reference image) is Jideon, a tall man. "
			+ "In the background, Mara is a hooded figure. "
			+ "In the background (the second reference image), Tam is a boy. "
			+ "In the background (the third reference image), Bes is a crone. "));
		assertTrue(bp, bp.startsWith("Combine the exact person and face from the first reference image with the exact person and face from the second reference image and the third reference image. "
			+ "Place them all, with the other people described below, in "));
		assertTrue("a named character staged as an extra is not a crowd", !bp.contains("Several"));

		/// an actor with no meaningful text is skipped entirely and not counted
		RenderPlan blank = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(extra("a1", "null", RenderPlan.ROLE_HERO, "  "))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_SUPPORTING, "a hooded figure", false, 2))
			.build();
		assertTrue(compiler.compile(blank, RenderPlan.STAGE_BASE, null).getPrompt().startsWith("Place the person described below in " + MAIN + ". The first person is Mara, a hooded figure. "));
	}

	@Test
	public void elementsGroupedHighMediumLow() {
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", false, 1))
			.element(new Element("gulls", RenderPlan.PRIORITY_LOW, null))
			.element(new Element("PROP", "rubble", "a drift of broken pews", null, 0, RenderPlan.PRIORITY_MEDIUM, null))
			.element(new Element("lantern", RenderPlan.PRIORITY_HIGH, "a brass lantern on a chain"))
			.element(new Element("PROP", "sextant", null, null, 0, null, "item-9"))
			.element(new Element("ANIMAL", "cat", "a black cat on the altar", null, 0, RenderPlan.PRIORITY_HIGH, null))
			.element(new Element("n/a", RenderPlan.PRIORITY_HIGH, "unknown"))
			.build();
		String prompt = compiler.compile(p, RenderPlan.STAGE_BASE, null).getPrompt();
		assertEquals("Place the person described below in " + MAIN + ". "
			+ "The first person is Jideon, a tall man. "
			+ "With a brass lantern on a chain; a black cat on the altar. "
			+ "With a drift of broken pews; sextant. "
			+ "With gulls. "
			+ STYLE + ". " + RULES_TEXT_ONLY.trim(), prompt);
	}

	@Test
	public void styleOnceWithTrailingPeriodAndSuppressedWhenMainEndsWithIt() {
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY).style(style("ink wash", "text")).build();
		assertEquals(MAIN + ". ink wash. " + RULES_TEXT_ONLY.trim(), compiler.compile(p, RenderPlan.STAGE_BASE, null).getPrompt());

		RenderPlan dotted = base(PbStrategyEnumType.TEXT_ONLY).style(style("ink wash.", "text")).build();
		assertEquals(MAIN + ". ink wash. " + RULES_TEXT_ONLY.trim(), compiler.compile(dotted, RenderPlan.STAGE_BASE, null).getPrompt());

		RenderPlan already = base(PbStrategyEnumType.TEXT_ONLY).style(style("Ink Wash", "text"))
			.location(new RenderPlan.Location(null, "a cathedral, ink wash")).build();
		String ap = compiler.compile(already, RenderPlan.STAGE_BASE, null).getPrompt();
		assertEquals("a cathedral, ink wash. " + RULES_TEXT_ONLY.trim(), ap);

		RenderPlan noStyle = base(PbStrategyEnumType.TEXT_ONLY).style(null).build();
		assertEquals(MAIN + ". " + RULES_TEXT_ONLY.trim(), compiler.compile(noStyle, RenderPlan.STAGE_BASE, null).getPrompt());
		assertEquals("blurry", compiler.compile(noStyle, RenderPlan.STAGE_BASE, null).getNegative());
	}

	@Test
	public void rulesKeepTheAsciiHyphenAndCarryNoPolicyText() {
		assertTrue(Flux2Compiler.RULE_NO_REFERENCE_OBJECTS.contains("themselves - no poster"));
		assertFalse(Flux2Compiler.RULE_NO_REFERENCE_OBJECTS.contains("—"));
		assertFalse(Flux2Compiler.RULE_NO_REFERENCE_OBJECTS.contains("–"));
		RenderPlan p = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.build();
		String prompt = compiler.compile(p, RenderPlan.STAGE_BASE, null).getPrompt();
		assertTrue(prompt, prompt.endsWith(RULES_REFERENCE.trim()));
		for(char c : prompt.toCharArray()) {
			assertTrue("non-ASCII in prompt: U+" + Integer.toHexString(c), c < 128);
		}
		String lower = prompt.toLowerCase();
		for(String banned : new String[] {"bias", "identity policy", "diverse", "diversity", "inclusive", "ethnic", "gender"}) {
			assertFalse("policy text leaked: " + banned, lower.contains(banned));
		}
		assertEquals(1, countOf(prompt, Flux2Compiler.RULE_IDENTITY.trim()));
		assertEquals(1, countOf(prompt, Flux2Compiler.RULE_SINGLE_SCENE.trim()));
	}

	@Test
	public void lorasAppendedOutsideTheBudget() {
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.config(config(Arrays.asList("detailTweaker:0.8", "", "inkStyle:1.0")))
			.build();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, null);
		String expected = MAIN + ". " + STYLE + ". " + RULES_TEXT_ONLY.trim();
		assertEquals(expected + ", <lora:detailTweaker:0.8>, <lora:inkStyle:1.0>", cp.getPrompt());
		assertEquals(PromptBudget.estimateTokens(expected), cp.getEstimatedTokens());
	}

	@Test
	public void rawIsTheOverrideAndLorasOnly() {
		RenderPlan p = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.config(config(Arrays.asList("inkStyle:1.0")))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.element(new Element("lantern", RenderPlan.PRIORITY_HIGH, null))
			.intent(new RenderPlan.Intent("a red barn in snow, “the king’s hall”", true))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals("a red barn in snow, \"the king's hall\", <lora:inkStyle:1.0>", cp.getPrompt());
		assertEquals("text, watermark, blurry", cp.getNegative());
		assertEquals(PromptBudget.estimateTokens("a red barn in snow, \"the king's hall\""), cp.getEstimatedTokens());
		assertTrue(degs.isEmpty());

		/// an override without raw is MAIN: it replaces the location text inside the placement clause
		RenderPlan override = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", false, 1))
			.intent(new RenderPlan.Intent("a red barn in snow", false))
			.build();
		String op = compiler.compile(override, RenderPlan.STAGE_BASE, null).getPrompt();
		assertTrue(op, op.startsWith("Place the person described below in a red barn in snow. The first person is Jideon, a tall man. " + STYLE + ". "));
		assertFalse(op.contains("cathedral"));

		/// raw with no override falls back to the composed prompt without style or rules
		RenderPlan rawNoOverride = base(PbStrategyEnumType.TEXT_ONLY)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", false, 1))
			.intent(new RenderPlan.Intent(null, true))
			.build();
		assertEquals("Place the person described below in " + MAIN + ". The first person is Jideon, a tall man.", compiler.compile(rawNoOverride, RenderPlan.STAGE_BASE, null).getPrompt());
	}

	@Test
	public void budgetDropOrderLowMediumExtrasBackgroundThenMainCut() {
		int budget = 512;
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.location(new RenderPlan.Location(null, words("m", 500)))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", false, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_NAMED_BACKGROUND, words("b", 150), false, 2))
			.actor(extra("a3", "guards", RenderPlan.ROLE_EXTRA, words("x", 60)))
			.element(new Element("lantern", RenderPlan.PRIORITY_HIGH, null))
			.element(new Element("rubble", RenderPlan.PRIORITY_MEDIUM, words("e", 40)))
			.element(new Element("gulls", RenderPlan.PRIORITY_LOW, words("l", 40)))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals(Arrays.asList(
			Flux2Compiler.GROUP_ELEM_LOW + " dropped: gulls",
			Flux2Compiler.GROUP_ELEM_MEDIUM + " dropped: rubble",
			Flux2Compiler.GROUP_EXTRAS + " dropped: guards",
			Flux2Compiler.GROUP_ACTOR_BG + " detail dropped: Mara",
			Flux2Compiler.MAIN_CUT), dropOrder(degs, budget));

		String prompt = cp.getPrompt();
		assertTrue(prompt, prompt.startsWith("Place the people described below in m1 m2 "));
		assertFalse("main was cut", prompt.contains("m500"));
		assertTrue(prompt, prompt.contains(". The first person is Jideon, a tall man. In the background, Mara. With lantern. " + STYLE + ". "));
		assertFalse(prompt.contains("b1 "));
		assertFalse(prompt.contains("Several"));
		assertFalse(prompt.contains("x1 "));
		assertFalse(prompt.contains("e1 "));
		assertFalse(prompt.contains("l1 "));
		assertTrue("once the extras are dropped the no-extra-people rule returns", prompt.endsWith(RULES_TEXT_ONLY.trim()));
		assertTrue(cp.getEstimatedTokens() + " <= " + budget, cp.getEstimatedTokens() <= budget);
		assertEquals(PromptBudget.estimateTokens(prompt), cp.getEstimatedTokens());

		/// the cut lands on a word boundary of the main description
		String cutMain = prompt.substring("Place the people described below in ".length(), prompt.indexOf(". The first person"));
		assertTrue(words("m", 500).startsWith(cutMain));
		assertTrue(words("m", 500).substring(cutMain.length()).startsWith(" "));
	}

	@Test
	public void referencedBackgroundActorKeepsItsOrdinalThroughTheDetailDrop() {
		/// a NAMED_BACKGROUND actor referenced under REQUIRE_REFERENCE is counted in the opening; when the budget drops the
		/// background detail, the sentence that binds its reference ordinal to it must survive the drop
		RenderPlan p = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.location(new RenderPlan.Location(null, words("m", 500)))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_NAMED_BACKGROUND, words("b", 150), true, 2))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals(Arrays.asList(Flux2Compiler.GROUP_ACTOR_BG + " detail dropped: Mara", Flux2Compiler.MAIN_CUT), dropOrder(degs, 512));
		String prompt = cp.getPrompt();
		assertTrue(prompt, prompt.startsWith("Combine the exact person and face from the first reference image with the exact person and face from the second reference image. Place them together in m1 m2 "));
		assertTrue(prompt, prompt.contains(". The first person (the first reference image) is Jideon, a tall man. In the background (the second reference image), Mara. " + STYLE + ". "));
		assertFalse("background detail was dropped", prompt.contains("b1 "));
		assertTrue(cp.getEstimatedTokens() <= 512);
	}

	@Test
	public void budgetStopsAtTheFirstDropThatFits() {
		/// ELEM_LOW alone pushes it over: only that one drop, the rest intact (foreground, medium, extras, bg detail)
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.location(new RenderPlan.Location(null, words("m", 380)))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", false, 1))
			.actor(actor("a2", "Mara", RenderPlan.ROLE_NAMED_BACKGROUND, words("b", 20), false, 2))
			.actor(extra("a3", "guards", RenderPlan.ROLE_EXTRA, words("x", 20)))
			.element(new Element("rubble", RenderPlan.PRIORITY_MEDIUM, words("e", 20)))
			.element(new Element("gulls", RenderPlan.PRIORITY_LOW, words("l", 60)))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals(Arrays.asList(Flux2Compiler.GROUP_ELEM_LOW + " dropped: gulls"), dropOrder(degs, 512));
		String prompt = cp.getPrompt();
		assertTrue(prompt.contains("In the background, Mara is b1 "));
		assertTrue(prompt.contains("Several guards, x1 "));
		assertTrue(prompt.contains("With e1 "));
		assertFalse(prompt.contains("l1 "));
		assertFalse("extras kept: no 'No extra people'", prompt.contains(Flux2Compiler.RULE_NO_EXTRA_PEOPLE.trim()));
		assertTrue(cp.getEstimatedTokens() <= 512);

		/// at or under the budget nothing is recorded
		RenderPlan under = base(PbStrategyEnumType.TEXT_ONLY)
			.location(new RenderPlan.Location(null, words("m", 400)))
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", false, 1))
			.element(new Element("gulls", RenderPlan.PRIORITY_LOW, words("l", 20)))
			.build();
		List<Degradation> none = new ArrayList<>();
		CompiledPrompt up = compiler.compile(under, RenderPlan.STAGE_BASE, none);
		assertTrue(none.isEmpty());
		assertTrue(up.getPrompt().contains("With l1 "));
		assertTrue(up.getEstimatedTokens() <= 512);
	}

	@Test
	public void overBudgetWithNothingLeftToDropIsRecordedUncut() {
		/// no MAIN to cut and a foreground actor (never dropped) alone past the budget
		RenderPlan p = base(PbStrategyEnumType.TEXT_ONLY)
			.location(null)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, words("f", 600), false, 1))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt cp = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		assertEquals(Arrays.asList(Flux2Compiler.OVER_BUDGET_UNCUT), dropOrder(degs, 512));
		assertTrue(cp.getPrompt().startsWith("Place the person described below in a coherent shared setting. The first person is Jideon, f1 "));
		assertTrue(cp.getPrompt().contains("f600"));
		assertTrue(cp.getEstimatedTokens() > 512);

		/// the budget follows the stage's pick: an SDXL location pick under TEXT_ONLY budgets at 225
		RenderPlan xl = base(PbStrategyEnumType.TEXT_ONLY)
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, XL, "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.location(new RenderPlan.Location(null, words("m", 300)))
			.element(new Element("gulls", RenderPlan.PRIORITY_LOW, "wheeling gulls"))
			.build();
		List<Degradation> xd = new ArrayList<>();
		compiler.compile(xl, RenderPlan.STAGE_BASE, xd);
		assertFalse(xd.isEmpty());
		assertTrue(xd.get(0).getMessage(), xd.get(0).getMessage().endsWith("over budget 225)"));
	}

	@Test
	public void nonBaseStageCompilesBaseAndReusesACompiledBase() {
		RenderPlan p = base(PbStrategyEnumType.DIRECT_REFERENCE)
			.actor(actor("a1", "Jideon", RenderPlan.ROLE_HERO, "a tall man", true, 1))
			.build();
		List<Degradation> degs = new ArrayList<>();
		CompiledPrompt b = compiler.compile(p, RenderPlan.STAGE_BASE, degs);
		CompiledPrompt u = compiler.compile(p, RenderPlan.STAGE_UPSCALE, degs);
		CompiledPrompt nul = compiler.compile(p, null, degs);
		assertEquals(b.getPrompt(), u.getPrompt());
		assertEquals(b.getPrompt(), nul.getPrompt());
		assertEquals(b.getNegative(), u.getNegative());
		assertEquals(b.getEstimatedTokens(), u.getEstimatedTokens());
		assertTrue(degs.isEmpty());

		Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		compiled.put(RenderPlan.STAGE_BASE, new CompiledPrompt("pinned", "neg", 1));
		RenderPlan withBase = p.withCompiled(compiled, null);
		assertEquals("pinned", compiler.compile(withBase, RenderPlan.STAGE_UPSCALE, null).getPrompt());
		assertEquals(b.getPrompt(), compiler.compile(withBase, RenderPlan.STAGE_BASE, null).getPrompt());

		assertEquals("", compiler.compile(null, RenderPlan.STAGE_BASE, null).getPrompt());
		assertEquals(RendererCapabilities.PATTERN_FLUX2, compiler.family());
	}

	@Test
	public void forPickIsFamilyFirstThenLegacyModelMatch() {
		assertTrue(PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof Flux2Compiler);
		assertTrue("the family on the pick wins over the model name",
			PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, XL, "FLUX2*", "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof Flux2Compiler);
		assertTrue(PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, XL, "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof SdxlCompiler);
		assertTrue("the family on the pick wins even when the model is flux2",
			PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX, "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof SdxlCompiler);
		/// legacy pick without a family: the checkpoint name is matched through RendererCapabilities
		assertTrue(PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, "Flux/" + FLUX, null, "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof Flux2Compiler);
		assertTrue(PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX, "", "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof Flux2Compiler);
		assertTrue(PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, XL, null, "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof SdxlCompiler);
		assertTrue("an unknown family falls back to SDXL",
			PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, "mystery_v1.safetensors", null, "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof SdxlCompiler);
		assertTrue(PromptCompilers.forPick(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, "k.safetensors", "*kontext*", "DEPLOYMENT_DEFAULT", "OK", false, null)) instanceof SdxlCompiler);
		assertTrue(PromptCompilers.forPick(null) instanceof SdxlCompiler);
		assertTrue(PromptCompilers.forFamily(null) instanceof SdxlCompiler);
		assertTrue(PromptCompilers.forFamily("flux2*") instanceof Flux2Compiler);
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
