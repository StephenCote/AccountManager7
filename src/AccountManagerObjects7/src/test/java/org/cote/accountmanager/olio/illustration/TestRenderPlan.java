package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Test;

/**
 * Pure tests for {@link RenderPlan} (P2-4, design §12): deterministic JSON, lossless round trip
 * through the NON_EMPTY wire form, no seed field, copy-on-write. No DB, no SD, no BaseTest.
 */
public class TestRenderPlan {

	static RenderPlan samplePlan() {
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("model", "OfficialStableDiffusion/sd_xl_base_1.0.safetensors");
		cfg.put("steps", 20);
		cfg.put("cfg", 7);
		cfg.put("sampler", "dpmpp_2m");
		cfg.put("scheduler", "karras");
		cfg.put("width", 1024);
		cfg.put("height", 1024);
		cfg.put("refinerControlPercentage", 0.2);
		cfg.put("hires", true);
		cfg.put("negativePrompt", "blurry");
		List<Map<String, String>> cands = new ArrayList<>();
		Map<String, String> c = new LinkedHashMap<>();
		c.put("step", "STYLE_DEFAULT");
		c.put("requested", "dreamshaperXL.safetensors");
		c.put("reason", "NOT_INSTALLED");
		cands.add(c);
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 2, false, "CANONICAL"))
			.style(new RenderPlan.Style("6f1c", "9ab4", "ink wash, muted earth tones", "text, watermark", "dusk, amber rim light", false, Collections.emptyList()))
			.config(cfg)
			.location(new RenderPlan.Location(null, "a flooded cathedral nave at dusk"))
			.actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, false))
			.actor(new RenderPlan.Actor("c02", "Mara", RenderPlan.ROLE_BACKGROUND, "a hooded figure", false))
			.intent(new RenderPlan.Intent(null, false))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, "OfficialStableDiffusion/sd_xl_base_1.0.safetensors", "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, cands))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, "flux2Klein_9b.safetensors", "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.input("style", "9ab4")
			.input("config", "e77d")
			.build();
	}

	@Test
	public void jsonIsDeterministic() {
		RenderPlan a = samplePlan();
		RenderPlan b = samplePlan();
		assertEquals(a.toJson(), b.toJson());
		assertEquals(a.contentHash(), b.contentHash());
		assertEquals(64, a.contentHash().length());
		assertTrue("planVersion is pinned", a.toJson().contains("\"planVersion\":\"illustration/1\""));
		/// insertion order is the serialisation order
		String json = a.toJson();
		assertTrue(json.indexOf("\"purpose\"") < json.indexOf("\"canvas\""));
		assertTrue(json.indexOf("\"canvas\"") < json.indexOf("\"preset\""));
		assertTrue(json.indexOf("\"models\"") < json.indexOf("\"compiled\"") || !json.contains("\"compiled\""));
		assertTrue(json.indexOf("\"location\"") < json.indexOf("\"sceneDirect\""));
	}

	@Test
	public void roundTripFromJson() {
		RenderPlan a = samplePlan();
		String json = a.toJson();
		RenderPlan back = RenderPlan.fromJson(json);
		assertNotNull(back);
		assertEquals(json, back.toJson());
		assertEquals(a.contentHash(), back.contentHash());
		/// NON_EMPTY drops these on the wire; the read side restores the defaults
		assertFalse(json.contains("promptOverride"));
		assertFalse(json.contains("\"elements\""));
		assertFalse(json.contains("\"refs\""));
		assertFalse(json.contains("\"compiled\""));
		assertFalse(json.contains("\"degradations\""));
		assertNull(back.getIntent().getPromptOverride());
		assertFalse(back.getIntent().isRaw());
		assertTrue(back.getElements().isEmpty());
		assertTrue(back.getStyle().getRefs().isEmpty());
		assertTrue(back.getCompiled().isEmpty());
		assertTrue(back.getDegradations().isEmpty());
		assertNull(back.getStrategy());
		assertNull(back.getCamera());
		assertNull(back.getLocation().getLocationId());
		assertEquals("a flooded cathedral nave at dusk", back.getLocation().getDescription());
		assertEquals(2, back.getActors().size());
		assertEquals("Mara", back.getActors().get(1).getName());
		assertFalse(back.getActors().get(1).isForeground());
		assertEquals(PbModelTaskEnumType.LOCATION, back.getModels().get("location").getTask());
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, back.getModels().get("sceneDirect").getTask());
		assertEquals(1, back.getModels().get("location").getCandidates().size());
		assertEquals("NOT_INSTALLED", back.getModels().get("location").getCandidates().get(0).get("reason"));
		assertEquals(20, back.getConfig().get("steps"));
		assertEquals(0.2, ((Number) back.getConfig().get("refinerControlPercentage")).doubleValue(), 0.0);
		assertEquals(Boolean.TRUE, back.getConfig().get("hires"));
		assertEquals("9ab4", back.getInputs().get("style"));
	}

	@Test
	public void roundTripKeepsStrategyCompiledAndDegradations() {
		RenderPlan a = samplePlan()
			.withStrategy(new RenderPlan.Strategy(PbStrategyEnumType.TEXT_ONLY, Arrays.asList("BASE", "UPSCALE"), "TEXT_ONLY: forced"),
				Arrays.asList(new Degradation(Degradation.ACTOR_TEXT_ONLY, "Mara rendered from description", "c02", "BASE")));
		Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		compiled.put("BASE", new CompiledPrompt("p", "n", 2));
		compiled.put("UPSCALE", new CompiledPrompt("p", "n", 2));
		a = a.withCompiled(compiled, Arrays.asList(new Degradation(Degradation.PROMPT_TRUNCATED, "ELEM_LOW dropped", null, "BASE")));
		String json = a.toJson();
		RenderPlan back = RenderPlan.fromJson(json);
		assertEquals(json, back.toJson());
		assertEquals(PbStrategyEnumType.TEXT_ONLY, back.getStrategy().getName());
		assertEquals(Arrays.asList("BASE", "UPSCALE"), back.getStrategy().getStages());
		assertEquals("TEXT_ONLY: forced", back.getStrategy().getReason());
		assertEquals(2, back.getCompiled().size());
		assertEquals("p", back.getCompiled().get("UPSCALE").getPrompt());
		assertEquals(2, back.getDegradations().size());
		assertEquals(Degradation.ACTOR_TEXT_ONLY, back.getDegradations().get(0).getCode());
		assertEquals("c02", back.getDegradations().get(0).getActorId());
		assertEquals(Degradation.PROMPT_TRUNCATED, back.getDegradations().get(1).getCode());
		assertNull(back.getDegradations().get(1).getActorId());
	}

	@Test
	public void seedIsNotAPlanField() {
		RenderPlan a = samplePlan();
		Map<String, Object> m = a.toMap();
		assertFalse(m.containsKey("seed"));
		assertFalse(a.toJson().toLowerCase().contains("\"seed\""));
		/// a seed smuggled in through the config snapshot is the caller's defect, not the plan's: the
		/// plan builder in PB strips it, and the plan itself never declares one
		for(String k : m.keySet()) {
			assertFalse("top-level key " + k, k.equalsIgnoreCase("seed"));
		}
	}

	@Test
	public void withCompiledDoesNotMutateOriginal() {
		RenderPlan a = samplePlan();
		String before = a.toJson();
		String hashBefore = a.contentHash();
		Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		compiled.put("BASE", new CompiledPrompt("prompt", "negative", 2));
		List<Degradation> degs = new ArrayList<>();
		degs.add(new Degradation(Degradation.PROMPT_TRUNCATED, "x", null, "BASE"));
		RenderPlan b = a.withCompiled(compiled, degs);
		RenderPlan c = a.withStrategy(new RenderPlan.Strategy(PbStrategyEnumType.TEXT_ONLY, Arrays.asList("BASE"), "TEXT_ONLY: forced"), null);
		assertEquals(before, a.toJson());
		assertEquals(hashBefore, a.contentHash());
		assertTrue(a.getCompiled().isEmpty());
		assertTrue(a.getDegradations().isEmpty());
		assertNull(a.getStrategy());
		assertEquals(1, b.getCompiled().size());
		assertEquals(1, b.getDegradations().size());
		assertNotNull(c.getStrategy());
		assertFalse(b.toJson().equals(before));
		/// the copies are independent of the lists handed in
		degs.add(new Degradation(Degradation.QA_WARN, "y", null, "BASE"));
		compiled.put("UPSCALE", new CompiledPrompt("p", "n", 1));
		assertEquals(1, b.getDegradations().size());
		assertEquals(1, b.getCompiled().size());
	}

	@Test
	public void pickForStageFollowsPurposeAndStrategy() {
		RenderPlan a = samplePlan();
		assertEquals(PbModelTaskEnumType.LOCATION, a.pickForStage(RenderPlan.STAGE_BASE).getTask());
		assertEquals("no upscale pick falls back to the BASE pick", PbModelTaskEnumType.LOCATION, a.pickForStage(RenderPlan.STAGE_UPSCALE).getTask());
		RenderPlan d = a.withStrategy(new RenderPlan.Strategy(PbStrategyEnumType.DIRECT_REFERENCE, Arrays.asList("BASE"), "x"), null);
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, d.pickForStage(RenderPlan.STAGE_BASE).getTask());
		RenderPlan p = RenderPlan.builder().purpose(RenderPlan.PURPOSE_PORTRAIT)
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.PORTRAIT, "m", "*xl*", "OVERRIDE", "OK", false, null)).build();
		assertEquals(PbModelTaskEnumType.PORTRAIT, p.pickForStage(RenderPlan.STAGE_BASE).getTask());
		assertNull(RenderPlan.builder().purpose(RenderPlan.PURPOSE_SCENE).build().pickForStage(RenderPlan.STAGE_BASE));
	}

	@Test
	public void fromJsonOfGarbageIsNull() {
		assertNull(RenderPlan.fromJson(null));
		assertNull(RenderPlan.fromJson("   "));
		assertNull(RenderPlan.fromJson("not json"));
	}
}
