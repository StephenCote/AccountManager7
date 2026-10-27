package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cote.accountmanager.olio.illustration.RenderPlan.Actor;
import org.cote.accountmanager.olio.illustration.RenderPlan.Strategy;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// Pure tests for {@link RenderEstimate}, {@link RenderCalibration} and {@link CalibrationSample}
/// (P2-4 design §18; P5-2 §4.4: per-stage samples, the reference term, the per-pass ACTOR_REGION term,
/// and a source string that says whether the figure was measured here, read from a row, or seeded).
public class TestRenderEstimate {

	private static final String FLUX = "flux2Klein_9b.safetensors";

	@Before
	public void clear() {
		RenderCalibration.clear();
	}

	@After
	public void clearAfter() {
		RenderCalibration.clear();
	}

	private static RenderPlan plan(int steps, int variants, boolean upscale) {
		RenderPlan p = RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues(upscale ? "FINAL" : "STANDARD", steps, variants, upscale, "CANONICAL"))
			.build();
		return p.withStrategy(new Strategy(PbStrategyEnumType.TEXT_ONLY,
			upscale ? Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_UPSCALE) : Arrays.asList(RenderPlan.STAGE_BASE), "TEXT_ONLY: forced"), null);
	}

	/// flux2 picks on every stage, a 1 MP canvas, one BASE-referenced actor, a rendered location (two base
	/// references) and one ACTOR_REGION actor with a box.
	private static RenderPlan inpaintPlan(int steps, int variants) {
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1000, 1000))
			.preset(new RenderPlan.PresetValues("STANDARD", steps, variants, false, "CANONICAL"))
			.location(new RenderPlan.Location("loc-1", null, null, "art-loc", "a flooded nave"))
			.strategy(new Strategy(PbStrategyEnumType.SEQUENTIAL_INPAINT, Arrays.asList(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION), "test"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.INPAINT, FLUX, "flux2*", "DEPLOYMENT_DEFAULT", "OK", false, null))
			.actor(new Actor.Builder("a1").name("Jideon").role(RenderPlan.ROLE_HERO).priority(1)
				.identity(new RenderPlan.Identity("art-a1", null, "a tall man")).referenced(true).build())
			.actor(new Actor.Builder("a3").name("Tam").role(RenderPlan.ROLE_SUPPORTING).priority(3)
				.identity(new RenderPlan.Identity("art-a3", null, "a wiry young man")).referenced(true)
				.referenceStage(RenderPlan.STAGE_ACTOR_REGION).box(new RenderPlan.Box(0.66, 0.2, 0.33, 0.8)).build())
			.build();
	}

	@Test
	public void noMeasurementSaysSo() {
		Map<String, Object> e = RenderEstimate.estimate(plan(20, 2, false), (RenderCalibration.Sample) null);
		assertEquals(1, e.get("scenes"));
		assertEquals(1, e.get("stages"));
		assertEquals(2, e.get("variants"));
		assertEquals(20, e.get("steps"));
		assertTrue(e.containsKey("secondsPerStep"));
		assertNull(e.get("secondsPerStep"));
		assertNull(e.get("seconds"));
		assertEquals(Boolean.FALSE, e.get("measured"));
		assertEquals(Boolean.FALSE, e.get("complete"));
		assertEquals(RenderEstimate.SOURCE_NONE, e.get("source"));
		/// the map form with no BASE key says the same
		Map<String, Object> m = RenderEstimate.estimate(plan(20, 2, false), new LinkedHashMap<>());
		assertNull(m.get("seconds"));
		assertEquals(RenderEstimate.SOURCE_NONE, m.get("source"));
		/// nothing recorded for an unrelated server/family either
		assertNull(RenderCalibration.sample("http://192.168.1.39:7801", "*xl*"));
	}

	@Test
	public void measuredFormula() {
		/// 20 s for 20 steps at exactly 1 MP = 1.0 s/step/MP
		RenderCalibration.Sample s = RenderCalibration.record("srv", "*xl*", 20_000, 20, 1000, 1000);
		assertNotNull(s);
		assertEquals(1.0, s.getSecondsPerStepPerMegapixel(), 1e-9);
		assertTrue(s.isMeasured());
		assertEquals(RenderPlan.STAGE_BASE, s.getStage());
		Map<String, Object> e = RenderEstimate.estimate(plan(30, 2, true), s);
		assertEquals(2, e.get("stages"));
		assertEquals(2, e.get("variants"));
		assertEquals(30, e.get("steps"));
		assertEquals(0, e.get("references"));
		assertEquals(0, e.get("regionPasses"));
		/// 1.0 s/step/MP x 1.048576 MP = 1.05 s/step; x 30 steps x 2 variants x 2 stages = 125.83 s
		assertEquals(1.05, (Double) e.get("secondsPerStep"), 1e-9);
		assertEquals(125.83, (Double) e.get("seconds"), 1e-9);
		assertEquals(Boolean.TRUE, e.get("measured"));
		assertEquals(Boolean.TRUE, e.get("complete"));
		String source = (String) e.get("source");
		assertTrue(source, source.startsWith(CalibrationSample.SOURCE_MEASURED + " (1 samples since JVM start)"));
		assertTrue(source, source.endsWith("; " + RenderEstimate.NOTE_UPSCALE));
		assertFalse(source, source.contains("ACTOR_REGION"));
		@SuppressWarnings("unchecked")
		Map<String, Object> perStage = (Map<String, Object>) e.get("perStage");
		assertEquals(31.46, (Double) perStage.get(RenderPlan.STAGE_BASE), 1e-9);
		assertEquals(31.46, (Double) perStage.get(RenderPlan.STAGE_UPSCALE), 1e-9);
		assertFalse(perStage.containsKey(RenderPlan.STAGE_ACTOR_REGION));
		/// single stage: no UPSCALE note
		String single = (String) RenderEstimate.estimate(plan(30, 1, false), s).get("source");
		assertFalse(single.contains("UPSCALE"));
	}

	@Test
	public void sequentialInpaintAddsReferenceAndRegionTerms() {
		RenderPlan p = inpaintPlan(4, 1);
		assertEquals(2, RenderEstimate.baseReferences(p));
		assertEquals(1, RenderEstimate.regionActors(p).size());
		assertEquals(5.0, RenderEstimate.perReference(p), 1e-9);
		/// flux2* inpaint: 8 steps, creativity 0.6 -> start_at_step 3 -> 5 effective
		assertEquals(5, RenderEstimate.effectiveRegionSteps(p));

		Map<String, CalibrationSample> samples = new LinkedHashMap<>();
		samples.put(RenderPlan.STAGE_BASE, CalibrationSample.measured(RenderPlan.STAGE_BASE, 1.0, 3, Instant.now()));
		samples.put(RenderPlan.STAGE_ACTOR_REGION, CalibrationSample.seed(RenderPlan.STAGE_ACTOR_REGION, 2.0));
		Map<String, Object> e = RenderEstimate.estimate(p, samples);
		assertEquals(2, e.get("stages"));
		assertEquals(2, e.get("references"));
		assertEquals(1, e.get("regionPasses"));
		assertEquals(5, e.get("regionEffectiveSteps"));
		@SuppressWarnings("unchecked")
		Map<String, Object> perStage = (Map<String, Object>) e.get("perStage");
		/// BASE = 1.0 x 4 steps x 1 MP + 5.0 s/step/ref x 4 steps x 2 refs = 44; ACTOR_REGION = 2.0 x 5 x 1 MP x 1 pass = 10
		assertEquals(44.0, (Double) perStage.get(RenderPlan.STAGE_BASE), 1e-9);
		assertEquals(10.0, (Double) perStage.get(RenderPlan.STAGE_ACTOR_REGION), 1e-9);
		assertEquals(54.0, (Double) e.get("seconds"), 1e-9);
		assertEquals(Boolean.TRUE, e.get("measured"));
		assertEquals(Boolean.TRUE, e.get("complete"));
		String source = (String) e.get("source");
		assertTrue(source, source.startsWith(CalibrationSample.SOURCE_MEASURED + " (3 samples since JVM start)"));
		assertTrue(source, source.contains("; ACTOR_REGION: " + CalibrationSample.SOURCE_SEED));
		@SuppressWarnings("unchecked")
		Map<String, Object> sources = (Map<String, Object>) e.get("sources");
		assertEquals(CalibrationSample.SOURCE_SEED, sources.get(RenderPlan.STAGE_ACTOR_REGION));

		/// two variants double the whole
		assertEquals(108.0, (Double) RenderEstimate.estimate(inpaintPlan(4, 2), samples).get("seconds"), 1e-9);

		/// no ACTOR_REGION figure: the BASE term stands, the estimate says it is incomplete
		samples.remove(RenderPlan.STAGE_ACTOR_REGION);
		Map<String, Object> partial = RenderEstimate.estimate(p, samples);
		assertEquals(44.0, (Double) partial.get("seconds"), 1e-9);
		assertEquals(Boolean.FALSE, partial.get("complete"));
		assertTrue(((String) partial.get("source")).contains("; ACTOR_REGION: " + RenderEstimate.SOURCE_NONE));

		/// a seeded BASE figure is a number, not a measurement
		samples.put(RenderPlan.STAGE_BASE, CalibrationSample.seed(RenderPlan.STAGE_BASE, 9.2));
		samples.put(RenderPlan.STAGE_ACTOR_REGION, CalibrationSample.seed(RenderPlan.STAGE_ACTOR_REGION, 13.8));
		Map<String, Object> seeded = RenderEstimate.estimate(p, samples);
		assertEquals(Boolean.FALSE, seeded.get("measured"));
		assertEquals(Boolean.TRUE, seeded.get("complete"));
		assertTrue(((String) seeded.get("source")).startsWith(CalibrationSample.SOURCE_SEED));
		/// 9.2 x 4 + 5 x 4 x 2 = 76.8; 13.8 x 5 = 69 -> 145.8
		assertEquals(145.8, (Double) seeded.get("seconds"), 1e-9);
	}

	@Test
	public void sourceStringsSayWhereTheFigureCameFrom() {
		Instant at = Instant.parse("2026-10-26T10:00:00Z");
		CalibrationSample measured = CalibrationSample.measured(RenderPlan.STAGE_BASE, 1.5, 4, at);
		assertEquals("measured in-process (4 samples since JVM start)", measured.getSource());
		assertFalse(measured.isSeed());
		assertEquals(4, measured.getSampleCount());

		CalibrationSample own = CalibrationSample.persisted(RenderPlan.STAGE_ACTOR_REGION, 2.5, 7, at, "192.168.1.39:7801", true);
		assertEquals("persisted from your renders on 192.168.1.39:7801 (7 samples, last " + at + ")", own.getSource());
		assertFalse(own.isSeed());
		assertEquals(RenderPlan.STAGE_ACTOR_REGION, own.getStage());

		/// another user's row: the figure is used, the provenance is not claimed as the reader's
		CalibrationSample other = CalibrationSample.persisted(RenderPlan.STAGE_BASE, 2.5, 7, at, "192.168.1.39:7801", false);
		assertEquals("a persisted record on 192.168.1.39:7801", other.getSource());
		assertFalse(other.getSource().contains("your"));

		CalibrationSample seed = CalibrationSample.seed(RenderPlan.STAGE_BASE, 9.2);
		assertEquals(CalibrationSample.SOURCE_SEED, seed.getSource());
		assertTrue(seed.isSeed());
		assertEquals(0, seed.getSampleCount());
		assertNull(seed.getMeasuredAt());

		/// from the in-process table, by origin
		RenderCalibration.Sample held = RenderCalibration.record("srv", "flux2*", RenderPlan.STAGE_ACTOR_REGION, 10_000, 5, 1000, 1000);
		assertEquals(CalibrationSample.SOURCE_MEASURED + " (1 samples since JVM start)", CalibrationSample.of(held, "srv", true).getSource());
		RenderCalibration.Sample primed = RenderCalibration.prime("srv", "*xl*", RenderPlan.STAGE_BASE, 1.5, 3, at);
		assertFalse(primed.isMeasured());
		assertTrue(CalibrationSample.of(primed, "srv", true).getSource().startsWith(CalibrationSample.SOURCE_PERSISTED_OWN + "srv (3 samples, last " + at + ")"));
		assertEquals(CalibrationSample.SOURCE_PERSISTED_OTHER + "srv", CalibrationSample.of(primed, "srv", false).getSource());
		assertNull(CalibrationSample.of(null, "srv", true));
	}

	@Test
	public void ewmaAndImmutableSwap() {
		RenderCalibration.Sample first = RenderCalibration.record("srv", "flux2*", 10_000, 10, 1000, 1000);
		assertEquals(1.0, first.getSecondsPerStepPerMegapixel(), 1e-9);
		assertEquals(1, first.getCount());
		/// 2.0 s/step/MP folded at alpha 0.3 -> 1.3
		RenderCalibration.Sample second = RenderCalibration.record("srv", "flux2*", 20_000, 10, 1000, 1000);
		assertEquals(1.3, second.getSecondsPerStepPerMegapixel(), 1e-9);
		assertEquals(2, second.getCount());
		/// the first sample object is unchanged: samples are immutable and swapped whole
		assertEquals(1.0, first.getSecondsPerStepPerMegapixel(), 1e-9);
		assertEquals(1, first.getCount());
		assertTrue(RenderCalibration.sample("srv", "flux2*") == second);
		/// keyed by server and family independently
		assertNull(RenderCalibration.sample("other", "flux2*"));
		assertNull(RenderCalibration.sample("srv", "*xl*"));
		/// non-positive inputs are refused rather than averaged in
		assertNull(RenderCalibration.record("srv", "flux2*", 0, 10, 1000, 1000));
		assertNull(RenderCalibration.record("srv", "flux2*", 1000, 0, 1000, 1000));
		assertNull(RenderCalibration.record("srv", "flux2*", 1000, 10, 0, 1000));
		assertNull(RenderCalibration.record("srv", null, 1000, 10, 1000, 1000));
		assertEquals(2, RenderCalibration.sample("srv", "flux2*").getCount());
	}

	@Test
	public void stagesAreKeyedApartAndPrimingNeverReplacesAMeasurement() {
		/// BASE and ACTOR_REGION for one server + family are separate entries
		RenderCalibration.Sample base = RenderCalibration.record("srv", "flux2*", RenderPlan.STAGE_BASE, 10_000, 10, 1000, 1000);
		RenderCalibration.Sample region = RenderCalibration.record("srv", "flux2*", RenderPlan.STAGE_ACTOR_REGION, 10_000, 5, 1000, 1000);
		assertEquals(1.0, base.getSecondsPerStepPerMegapixel(), 1e-9);
		assertEquals(2.0, region.getSecondsPerStepPerMegapixel(), 1e-9);
		assertTrue(RenderCalibration.sample("srv", "flux2*") == base);
		assertTrue(RenderCalibration.sample("srv", "flux2*", RenderPlan.STAGE_BASE) == base);
		assertTrue(RenderCalibration.sample("srv", "flux2*", RenderPlan.STAGE_ACTOR_REGION) == region);
		/// null / blank stage means BASE
		assertTrue(RenderCalibration.sample("srv", "flux2*", null) == base);
		assertTrue(RenderCalibration.sample("srv", "flux2*", " ") == base);
		assertEquals(RenderPlan.STAGE_BASE, RenderCalibration.stageOrBase(null));
		assertEquals(RenderPlan.STAGE_ACTOR_REGION, RenderCalibration.stageOrBase(" ACTOR_REGION "));

		/// priming an occupied key is a no-op that returns what is held
		RenderCalibration.Sample still = RenderCalibration.prime("srv", "flux2*", RenderPlan.STAGE_BASE, 7.7, 99, Instant.now());
		assertTrue(still == base);
		assertTrue(still.isMeasured());

		/// priming an empty key holds the persisted figure, flagged as such; a measurement then restarts the
		/// in-process count at 1 so "n samples since JVM start" stays true
		Instant at = Instant.parse("2026-10-20T08:00:00Z");
		RenderCalibration.Sample primed = RenderCalibration.prime("srv", "*xl*", RenderPlan.STAGE_BASE, 1.5, 6, at);
		assertNotNull(primed);
		assertFalse(primed.isMeasured());
		assertEquals(RenderCalibration.ORIGIN_PERSISTED, primed.getOrigin());
		assertEquals(6, primed.getCount());
		assertEquals(at, primed.getMeasuredAt());
		assertTrue(RenderCalibration.sample("srv", "*xl*") == primed);
		RenderCalibration.Sample measured = RenderCalibration.record("srv", "*xl*", 20_000, 10, 1000, 1000);
		assertTrue(measured.isMeasured());
		assertEquals(1, measured.getCount());
		/// 2.0 folded at alpha 0.3 over the primed 1.5 -> 1.65: the persisted figure still anchors the average
		assertEquals(1.65, measured.getSecondsPerStepPerMegapixel(), 1e-9);
		/// prime refuses what it cannot hold
		assertNull(RenderCalibration.prime("srv", "*xl*", RenderPlan.STAGE_ACTOR_REGION, 0, 1, at));
		assertNull(RenderCalibration.prime("srv", "*xl*", RenderPlan.STAGE_ACTOR_REGION, 1.0, 0, at));
		assertNull(RenderCalibration.prime("srv", " ", RenderPlan.STAGE_ACTOR_REGION, 1.0, 1, at));
		assertNull(RenderCalibration.sample("srv", "*xl*", RenderPlan.STAGE_ACTOR_REGION));
	}
}
