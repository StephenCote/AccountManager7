package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Strategy;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Test;

/**
 * Pure tests for {@link StrategySelector} (P2-4, design §13/§19; P5-2 §2.2): every strategy is chosen
 * with a stated reason, referenced actors a family cannot honour degrade instead of throwing, and
 * DIRECT_REFERENCE / SEQUENTIAL_INPAINT follow the actors' referenceStage.
 */
public class TestStrategySelector {

	private static RenderPlan.Builder plan(boolean upscale) {
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues(upscale ? "FINAL" : "STANDARD", 20, 1, upscale, "CANONICAL"))
			.location(new RenderPlan.Location(null, "a cathedral"));
	}

	private static RendererCapabilities caps(String model) {
		RendererCapabilities c = RendererCapabilities.forModel(model);
		assertTrue("family for " + model, c != null);
		return c;
	}

	@Test
	public void textOnlyWhenNoReferencedActors() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, false))
			.build();
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, caps("flux2Klein_9b.safetensors"), false, degs);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, s.getName());
		assertEquals(Collections.singletonList(RenderPlan.STAGE_BASE), s.getStages());
		assertEquals(StrategySelector.REASON_NO_REFERENCED_ACTORS, s.getReason());
		assertTrue(degs.isEmpty());
	}

	@Test
	public void textOnlyWhenFamilyLacksReferences() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, true))
			.build();
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, caps("OfficialStableDiffusion/sd_xl_base_1.0.safetensors"), false, degs);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, s.getName());
		assertEquals("TEXT_ONLY: renderer family *xl* has no reference support", s.getReason());
		assertEquals(1, degs.size());
		assertEquals(Degradation.ACTOR_TEXT_ONLY, degs.get(0).getCode());
		assertEquals("c01", degs.get(0).getActorId());
		assertEquals(RenderPlan.STAGE_BASE, degs.get(0).getStage());
		assertEquals("Jideon rendered from description (renderer family *xl* has no reference support)", degs.get(0).getMessage());

		/// an unknown family (no capabilities) degrades the same way with its own reason
		List<Degradation> degs2 = new ArrayList<>();
		Strategy u = StrategySelector.select(p, null, false, degs2);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, u.getName());
		assertEquals(StrategySelector.REASON_UNKNOWN_FAMILY, u.getReason());
		assertEquals(1, degs2.size());
	}

	@Test
	public void textOnlyWhenForced() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, true))
			.build();
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, caps("flux2Klein_9b.safetensors"), true, degs);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, s.getName());
		assertEquals(StrategySelector.REASON_FORCED, s.getReason());
		assertTrue("forcing is the caller's choice, not a degradation", degs.isEmpty());
	}

	@Test
	public void upscaleStageOnlyForFinal() {
		Strategy std = StrategySelector.select(plan(false).build(), caps("flux2Klein_9b.safetensors"), false, null);
		assertEquals(Collections.singletonList(RenderPlan.STAGE_BASE), std.getStages());
		Strategy fin = StrategySelector.select(plan(true).build(), caps("flux2Klein_9b.safetensors"), false, null);
		assertEquals(Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_UPSCALE), fin.getStages());
		/// no preset at all is BASE only
		Strategy none = StrategySelector.select(RenderPlan.builder().purpose(RenderPlan.PURPOSE_SCENE).build(), null, false, null);
		assertEquals(Collections.singletonList(RenderPlan.STAGE_BASE), none.getStages());
	}

	/// P5-2: a plan whose referenced actors ride on a reference-capable family but which never resolved a
	/// SCENE_DIRECT pick (the chat scene path builds only a LOCATION pick) stays TEXT_ONLY and says why per
	/// actor - a reference strategy renders BASE with the SCENE_DIRECT pick, so there is nothing to render with.
	@Test
	public void referencedActorsWithoutASceneDirectPickDegradesNotThrows() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, true))
			.actor(new RenderPlan.Actor("c02", "Mara", RenderPlan.ROLE_BACKGROUND, "a hooded figure", true))
			.actor(new RenderPlan.Actor("c03", "Oru", RenderPlan.ROLE_BACKGROUND, null, false))
			.build();
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, caps("flux2Klein_9b.safetensors"), false, degs);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, s.getName());
		assertEquals(StrategySelector.REASON_NO_SCENE_DIRECT_PICK, s.getReason());
		assertEquals("one degradation per referenced actor", 2, degs.size());
		assertEquals("c01", degs.get(0).getActorId());
		assertEquals("c02", degs.get(1).getActorId());
		assertEquals("Mara rendered from description (no resolved SCENE_DIRECT pick)", degs.get(1).getMessage());
		/// the selector's output lands on the plan without disturbing the rest of it
		RenderPlan withStrategy = p.withStrategy(s, degs);
		assertEquals(2, withStrategy.getDegradations().size());
		assertEquals(3, withStrategy.getActors().size());
		assertTrue(withStrategy.getDegradations().get(0).toString().contains(Degradation.ACTOR_TEXT_ONLY));
		/// an unresolved SCENE_DIRECT pick is no pick
		RenderPlan unresolved = plan(false)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, true))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, null, null, "DEPLOYMENT_DEFAULT", "INVENTORY_UNAVAILABLE", true, null))
			.build();
		assertEquals(StrategySelector.REASON_NO_SCENE_DIRECT_PICK, StrategySelector.select(unresolved, caps("flux2Klein_9b.safetensors"), false, null).getReason());
	}

	private static RenderPlan.ModelPick sceneDirect(String model) {
		return new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, model, null, "STYLE_DEFAULT", "OK", false, null);
	}

	/// P5-2 §2.2: every referenced actor in BASE on a reference family -> DIRECT_REFERENCE, stages [BASE]
	/// (+UPSCALE for a FINAL preset), no degradation; a pre-P5-2 referenced actor with no stage reads as BASE.
	@Test
	public void directReferenceWhenEveryReferencedActorIsBase() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor.Builder("c01").name("Jideon").referenced(true).referenceStage(RenderPlan.STAGE_BASE).build())
			.actor(new RenderPlan.Actor("c02", "Mara", RenderPlan.ROLE_BACKGROUND, "a hooded figure", true))
			.actor(new RenderPlan.Actor("c03", "Oru", RenderPlan.ROLE_BACKGROUND, null, false))
			.model(sceneDirect("flux2Klein_9b.safetensors"))
			.build();
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, caps("flux2Klein_9b.safetensors"), false, degs);
		assertEquals(PbStrategyEnumType.DIRECT_REFERENCE, s.getName());
		assertEquals(Collections.singletonList(RenderPlan.STAGE_BASE), s.getStages());
		assertEquals("DIRECT_REFERENCE: 2 referenced actors within 3 slots", s.getReason());
		assertTrue("a honoured reference is not a degradation", degs.isEmpty());
		assertEquals("BASE renders with the SCENE_DIRECT pick", PbModelTaskEnumType.SCENE_DIRECT,
			p.withStrategy(s, degs).pickForStage(RenderPlan.STAGE_BASE).getTask());

		Strategy fin = StrategySelector.select(plan(true)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, true))
			.model(sceneDirect("flux2Klein_9b.safetensors")).build(), caps("flux2Klein_9b.safetensors"), false, null);
		assertEquals(PbStrategyEnumType.DIRECT_REFERENCE, fin.getName());
		assertEquals(Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_UPSCALE), fin.getStages());
		assertEquals("DIRECT_REFERENCE: 1 referenced actor within 3 slots", fin.getReason());
	}

	/// P5-2 §2.2: any ACTOR_REGION actor on a mask family -> SEQUENTIAL_INPAINT, stages [BASE, ACTOR_REGION]
	/// with UPSCALE last; the stage list names the kind once, the actor list is the per-actor expansion.
	@Test
	public void sequentialInpaintWhenAnyActorIsRegion() {
		RenderPlan.Builder b = plan(false)
			.actor(new RenderPlan.Actor.Builder("c01").name("Jideon").referenced(true).referenceStage(RenderPlan.STAGE_BASE).build())
			.actor(new RenderPlan.Actor.Builder("c02").name("Mara").box(new RenderPlan.Box(0.6, 0.1, 0.3, 0.8)).referenced(true).referenceStage(RenderPlan.STAGE_ACTOR_REGION).build())
			.actor(new RenderPlan.Actor.Builder("c03").name("Oru").box(new RenderPlan.Box(0.1, 0.1, 0.3, 0.8)).referenced(true).referenceStage(RenderPlan.STAGE_ACTOR_REGION).build())
			.actor(new RenderPlan.Actor("c04", "Bes", RenderPlan.ROLE_BACKGROUND, null, false))
			.model(sceneDirect("flux2Klein_9b.safetensors"));
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(b.build(), caps("flux2Klein_9b.safetensors"), false, degs);
		assertEquals(PbStrategyEnumType.SEQUENTIAL_INPAINT, s.getName());
		assertEquals(Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION), s.getStages());
		assertEquals("SEQUENTIAL_INPAINT: 3 referenced actors, 3 slots, 2 ACTOR_REGION passes; HARMONIZE deferred (unmeasured)", s.getReason());
		assertTrue(degs.isEmpty());

		/// region-only on SDXL (no BASE reference slots, masks yes) is still SEQUENTIAL_INPAINT, and the reason
		/// says the passes carry no portrait: the family takes no references, so the executor sends none
		RenderPlan xl = plan(true)
			.actor(new RenderPlan.Actor.Builder("c02").name("Mara").box(new RenderPlan.Box(0.6, 0.1, 0.3, 0.8)).referenced(true).referenceStage(RenderPlan.STAGE_ACTOR_REGION).build())
			.model(sceneDirect("OfficialStableDiffusion/sd_xl_base_1.0.safetensors")).build();
		RendererCapabilities xlCaps = caps("OfficialStableDiffusion/sd_xl_base_1.0.safetensors");
		assertFalse("precondition: the xl family takes no references", xlCaps.isSupportsReferences());
		Strategy sx = StrategySelector.select(xl, xlCaps, false, degs);
		assertEquals(PbStrategyEnumType.SEQUENTIAL_INPAINT, sx.getName());
		assertEquals(Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION, RenderPlan.STAGE_UPSCALE), sx.getStages());
		assertEquals("SEQUENTIAL_INPAINT: 1 referenced actors, 0 slots, 1 ACTOR_REGION pass; HARMONIZE deferred (unmeasured)"
			+ StrategySelector.REASON_REGION_TEXT_ONLY_SUFFIX, sx.getReason());
		assertTrue(degs.isEmpty());
		/// ...and a reference family's reason carries no such suffix
		assertFalse(s.getReason().contains(StrategySelector.REASON_REGION_TEXT_ONLY_SUFFIX));
		/// forcing still wins over any reference shape
		assertEquals(StrategySelector.REASON_FORCED, StrategySelector.select(b.build(), caps("flux2Klein_9b.safetensors"), true, null).getReason());
	}

	/// P5-2 §2.2: an ACTOR_REGION actor on a family with no mask support (Kontext) degrades every referenced
	/// actor to text, TEXT_ONLY with the family named; a hand-built plan can ask for what the family lacks.
	@Test
	public void masklessFamilyDegradesRegionActorsToTextOnly() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor.Builder("c01").name("Jideon").referenced(true).referenceStage(RenderPlan.STAGE_BASE).build())
			.actor(new RenderPlan.Actor.Builder("c02").name("Mara").box(new RenderPlan.Box(0.6, 0.1, 0.3, 0.8)).referenced(true).referenceStage(RenderPlan.STAGE_ACTOR_REGION).build())
			.model(sceneDirect("flux1_kontext_dev.safetensors"))
			.build();
		RendererCapabilities kontext = caps("flux1_kontext_dev.safetensors");
		assertTrue(kontext.isSupportsReferences());
		assertTrue(!kontext.isSupportsMasks());
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, kontext, false, degs);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, s.getName());
		assertEquals("TEXT_ONLY: renderer family *kontext* has no mask support", s.getReason());
		assertEquals(Collections.singletonList(RenderPlan.STAGE_BASE), s.getStages());
		assertEquals("every referenced actor is degraded, not only the region one", 2, degs.size());
		assertEquals("c01", degs.get(0).getActorId());
		assertEquals("Mara rendered from description (renderer family *kontext* has no mask support)", degs.get(1).getMessage());
		assertEquals(RenderPlan.STAGE_BASE, degs.get(1).getStage());
	}
}
