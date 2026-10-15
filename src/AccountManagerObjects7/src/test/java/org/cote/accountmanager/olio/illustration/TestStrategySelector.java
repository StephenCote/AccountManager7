package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Strategy;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Test;

/**
 * Pure tests for {@link StrategySelector} (P2-4, design §13/§19): TEXT_ONLY is the only Phase 2
 * strategy, chosen with a stated reason, and referenced actors degrade instead of throwing.
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
		assertEquals("Jideon rendered from description (reference rendering not available in this build)", degs.get(0).getMessage());

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

	@Test
	public void referencedActorsOnFlux2DegradesNotThrows() {
		RenderPlan p = plan(false)
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, true))
			.actor(new RenderPlan.Actor("c02", "Mara", RenderPlan.ROLE_BACKGROUND, "a hooded figure", true))
			.actor(new RenderPlan.Actor("c03", "Oru", RenderPlan.ROLE_BACKGROUND, null, false))
			.build();
		List<Degradation> degs = new ArrayList<>();
		Strategy s = StrategySelector.select(p, caps("flux2Klein_9b.safetensors"), false, degs);
		assertEquals(PbStrategyEnumType.TEXT_ONLY, s.getName());
		assertEquals(StrategySelector.REASON_REFERENCES_NOT_IMPLEMENTED, s.getReason());
		assertEquals("one degradation per referenced actor", 2, degs.size());
		assertEquals("c01", degs.get(0).getActorId());
		assertEquals("c02", degs.get(1).getActorId());
		assertTrue(degs.get(1).getMessage().startsWith("Mara rendered from description"));
		/// the selector's output lands on the plan without disturbing the rest of it
		RenderPlan withStrategy = p.withStrategy(s, degs);
		assertEquals(2, withStrategy.getDegradations().size());
		assertEquals(3, withStrategy.getActors().size());
		assertTrue(withStrategy.getDegradations().get(0).toString().contains(Degradation.ACTOR_TEXT_ONLY));
	}
}
