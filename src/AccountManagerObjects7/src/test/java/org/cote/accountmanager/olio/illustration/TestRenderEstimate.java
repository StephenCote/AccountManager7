package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.cote.accountmanager.olio.illustration.RenderPlan.Strategy;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Pure tests for {@link RenderEstimate} and {@link RenderCalibration} (P2-4, design §18). */
public class TestRenderEstimate {

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

	@Test
	public void noMeasurementSaysSo() {
		Map<String, Object> e = RenderEstimate.estimate(plan(20, 2, false), null);
		assertEquals(1, e.get("scenes"));
		assertEquals(1, e.get("stages"));
		assertEquals(2, e.get("variants"));
		assertEquals(20, e.get("steps"));
		assertTrue(e.containsKey("secondsPerStep"));
		assertNull(e.get("secondsPerStep"));
		assertNull(e.get("seconds"));
		assertEquals(Boolean.FALSE, e.get("measured"));
		assertEquals(RenderEstimate.SOURCE_NONE, e.get("source"));
		/// nothing recorded for an unrelated server/family either
		assertNull(RenderCalibration.sample("http://192.168.1.39:7801", "*xl*"));
	}

	@Test
	public void measuredFormula() {
		/// 20 s for 20 steps at exactly 1 MP = 1.0 s/step/MP
		RenderCalibration.Sample s = RenderCalibration.record("srv", "*xl*", 20_000, 20, 1000, 1000);
		assertNotNull(s);
		assertEquals(1.0, s.getSecondsPerStepPerMegapixel(), 1e-9);
		Map<String, Object> e = RenderEstimate.estimate(plan(30, 2, true), s);
		assertEquals(2, e.get("stages"));
		assertEquals(2, e.get("variants"));
		assertEquals(30, e.get("steps"));
		/// 1.0 s/step/MP x 1.048576 MP = 1.05 s/step; x 30 steps x 2 variants x 2 stages = 125.83 s
		assertEquals(1.05, (Double) e.get("secondsPerStep"), 1e-9);
		assertEquals(125.83, (Double) e.get("seconds"), 1e-9);
		assertEquals(Boolean.TRUE, e.get("measured"));
		String source = (String) e.get("source");
		assertTrue(source, source.startsWith("MEASURED *xl* n=1 at "));
		assertTrue(source, source.contains(RenderEstimate.SOURCE_SCOPE));
		assertTrue(source, source.endsWith("; UPSCALE counted at BASE cost"));
		/// single stage: no UPSCALE note
		String single = (String) RenderEstimate.estimate(plan(30, 1, false), s).get("source");
		assertFalse(single.contains("UPSCALE"));
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
}
